package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.floor.ActiveBootRules;

/**
 * Server-owned physics lifetime; negotiation itself never grants movement authority.
 */
public final class PlayerPhysicsServer {
    public record AcceptedBodyReference(long requestId, CardinalFrame frame, Vec3d tangentHeading,
                                        PlayerBodyReference.Source source) {
        public AcceptedBodyReference(long requestId, CardinalFrame frame, Vec3d tangentHeading) {
            this(requestId, frame, tangentHeading, PlayerBodyReference.Source.MANUAL);
        }
        public AcceptedBodyReference {
            if (requestId < 0 || source == PlayerBodyReference.Source.MANUAL && requestId == 0
                    || source != PlayerBodyReference.Source.MANUAL && requestId != 0)
                throw new IllegalArgumentException("Accepted body reference request identity does not match its source");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(tangentHeading, "tangentHeading");
            Objects.requireNonNull(source, "source");
        }
    }
    public record PresentationBodyReference(PlayerPhysicalSnapshot snapshot, AcceptedBodyReference acceptedLock) {
        public PresentationBodyReference {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(acceptedLock, "acceptedLock");
            if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                    || shape.frame() != acceptedLock.frame())
                throw new IllegalArgumentException("Presentation lock differs from accepted physical geometry");
        }
    }
    /** Same-frame accepted Free presentation identity and its owner-correction fence. */
    public record FreePresentationSnapshot(PlayerPhysicalSnapshot snapshot, long correctionRevision) {
        public FreePresentationSnapshot {
            Objects.requireNonNull(snapshot, "snapshot");
            if (correctionRevision < 0) throw new IllegalArgumentException("Negative owner correction revision");
            if (!(snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule))
                throw new IllegalArgumentException("Free presentation snapshot must identify accepted Free geometry");
        }
    }
    /** Dormant until the complete runtime is installed; shared only by common-side production hooks. */
    public static final PlayerPhysicsServer INSTANCE = new PlayerPhysicsServer(GIntentServer.INSTANCE, PlayerPhysicsNetwork::sendTo);
    private static final PlayerPhysicsProtocol.Controls IDLE = new PlayerPhysicsProtocol.Controls(
            new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5);
    private static final long INPUT_TIMEOUT_TICKS = 20;
    private static final long SERVER_CONTROL_PROBE_MIN_INTERVAL_TICKS = 20;
    private static final long SERVER_CONTROL_PROBE_SUMMARY_INTERVAL_TICKS = 100;
    private static final long NATIVE_RESTORE_TIMEOUT_TICKS = 40;
    private static final long FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS = 1_000_000_000L;
    private static final long NATIVE_RESTORE_RESEND_TICKS = 5;
    private static final long NATIVE_RESTORE_ACK_TIMEOUT_TICKS = 40;
    private static final int MAX_NATIVE_RESTORE_SENT_STATES = 16;
    private static final Marker FREE_RESIZE_TRACE_MARKER = MarkerManager.getMarker("UG_FREE_RESIZE_TRACE");
    private static final int UNOBSERVED_EYE_BITS = Integer.MIN_VALUE;
    private static final CollisionQueryFootprint.Limits POSE_SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final CardinalMovementResolver.Limits POSE_MOVE =
            new CardinalMovementResolver.Limits(128, 8192, 65536);
    private final GIntentServer intent;
    private final BiConsumer<EntityPlayerMP, PlayerPhysicsProtocol.Packet> outbound;
    private final Map<NetworkManager, Capability> capabilities = new IdentityHashMap<>();
    private final Map<NetworkManager, Session> sessions = new IdentityHashMap<>();
    private long serverTick;
    private long worldEpoch;
    private boolean installed;
    private RemoteTransfer preparingRemote;
    private InitializationInstallation initializingPose;
    private PlayerPhysicsStep movement;
    private NativeEnvironmentScope nativeEnvironmentScope;

    /** Test-scoped DEBUG trace; closed in normal operation, with no geometry reads or eager formatting. */
    private static void traceFreeResize(Session session, PlayerPhysicalSnapshot source,
            PlayerPhysicalSnapshot anchor, PlayerPhysicalSnapshot after, String stage, String outcome,
            long worldTick, boolean worldTickObserved, long correctionRevision, boolean proposalPresent,
            int rawEyeBits, int effectiveEyeBits, Object observed, Object detail, Throwable failure) {
        if (!UnigravMod.LOGGER.isDebugEnabled(FREE_RESIZE_TRACE_MARKER) || source == null
                || !(source.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) return;
        var sourceDimensions = source.placement().shape().dimensions();
        var anchorDimensions = anchor == null ? null : anchor.placement().shape().dimensions();
        UnigravMod.LOGGER.debug(FREE_RESIZE_TRACE_MARKER,
                "UG_FREE_RESIZE_TRACE scope={} proposal={} stage={} outcome={} worldTick={} worldTickObserved={}"
                        + " correction={} sourceRevision={} anchorRevision={} afterRevision={} proposalPresent={}"
                        + " rawEyeBits={} effectiveEyeBits={} sourceDimensions={} anchorDimensions={} observed={} detail={}",
                session.scope, session.proposal, stage, outcome, worldTick, worldTickObserved, correctionRevision,
                source.revision(), anchor == null ? -1 : anchor.revision(), after == null ? -1 : after.revision(),
                proposalPresent, rawEyeBits, effectiveEyeBits, sourceDimensions, anchorDimensions, observed, detail,
                failure);
    }

    /** Emits captured step outcome details only while the bounded test trace is enabled. */
    private static void traceFreeResizeStepResult(Session session, PlayerPhysicalSnapshot source,
            PlayerPhysicalSnapshot after, long worldTick, long correctionRevision, boolean proposalPresent,
            PlayerPhysicsStep.Result result, Throwable stepFailure) {
        if (!UnigravMod.LOGGER.isDebugEnabled(FREE_RESIZE_TRACE_MARKER) || source == null
                || !(source.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) return;
        String resultType = result == null ? "none" : result.getClass().getSimpleName();
        String outcome;
        String reason;
        Throwable cause = stepFailure;
        if (stepFailure != null) {
            outcome = "STEP_FAILED";
            reason = "step or transaction failure";
        } else if (result instanceof PlayerPhysicsStep.Applied) {
            outcome = "APPLIED";
            reason = "applied";
        } else if (result instanceof PlayerPhysicsStep.Unavailable unavailable) {
            outcome = "UNAVAILABLE";
            reason = unavailable.reason();
            cause = unavailable.cause().orElse(null);
        } else if (result instanceof PlayerPhysicsStep.CommittedFailure failure) {
            outcome = "COMMITTED_FAILURE";
            reason = "committed failure";
            cause = failure.cause();
        } else if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred) {
            outcome = "COMMITTED_DEFERRED";
            reason = deferred.tail().reason();
            cause = deferred.tail().cause().orElse(null);
        } else {
            outcome = "NO_RESULT";
            reason = "step returned no result";
        }
        String causeSummary = cause == null ? "none" : cause.getClass().getName();
        UnigravMod.LOGGER.debug(FREE_RESIZE_TRACE_MARKER,
                "UG_FREE_RESIZE_TRACE scope={} proposal={} stage=STEP_RESULT outcome={} worldTick={}"
                        + " worldTickObserved=true correction={} sourceRevision={} afterRevision={}"
                        + " proposalPresent={} resultType={} reason={} cause={}",
                session.scope, session.proposal, outcome, worldTick, correctionRevision, source.revision(),
                after == null ? -1 : after.revision(), proposalPresent, resultType, reason, causeSummary, cause);
    }

    /** Ends only the attributed native environment operation; unrelated vanilla failures still propagate. */
    public synchronized void runNativeEnvironment(EntityPlayerMP player, Runnable original) {
        Objects.requireNonNull(original);
        Session session = freeEnvironmentSession(player);
        if (session == null) { original.run(); return; }
        // Vanilla may tick the actor between sending a correction and consuming its confirmation.
        // That expected pause must preserve the owner's ledger for completeNativeCorrectionLifetime.
        if (current(session) && session.owner.teleportPending) return;
        var previous = nativeEnvironmentScope;
        // Death may occur after START lifecycle retirement but before the native entity tick.
        // With no enclosing operation or installed journal, this is normal owner retirement;
        // a transaction already in flight still reaches its existing failure/finalization boundary.
        if (previous == null && session.world.isCallingFromMinecraftThread()
                && !player.isEntityAlive() && FreeOwnerTransaction.nativeInstallation(player) == null) {
            retire(session.manager);
            original.run();
            return;
        }
        if (previous != null && previous.session.player == player) {
            previous.check();
            original.run();
            previous.check();
            return;
        }
        try {
            nativeEnvironmentScope = new NativeEnvironmentScope(session);
            original.run();
            nativeEnvironmentScope.check();
        } catch (PauseNativeEnvironment pause) {
            // A live correction fences further side effects; the transaction finalizer still owns any installed journal.
            if (FreeOwnerTransaction.nativeInstallation(player) != null) throw pause;
        } catch (AbortNativeEnvironment abort) {
            // The installed journal/debit belongs to the finalizer, which must observe the original failure.
            if (FreeOwnerTransaction.nativeInstallation(player) != null) throw abort.original;
        } finally {
            nativeEnvironmentScope = previous;
        }
    }

    public synchronized boolean nativeWaterMovement(EntityPlayerMP player, BooleanSupplier original) {
        return nativeFluid(player, original, true);
    }

    public synchronized boolean nativeInLava(EntityPlayerMP player, BooleanSupplier original) {
        return nativeFluid(player, original, false);
    }

    private boolean nativeFluid(EntityPlayerMP player, BooleanSupplier original, boolean water) {
        Objects.requireNonNull(original);
        Session session = freeEnvironmentSession(player);
        if (session == null) return original.getAsBoolean();
        NativeEnvironmentScope scope = nativeEnvironmentScope;
        if (scope == null || scope.session != session) {
            // No boolean can represent UNKNOWN. The caller needs a real whole-operation abort boundary.
            scope = new NativeEnvironmentScope(session);
        }
        try {
            scope.requireCurrent();
            boolean result = water ? scope.environment().waterMovement() : scope.environment().inLava();
            scope.requireCurrent();
            return result;
        } catch (PauseNativeEnvironment pause) {
            throw pause;
        } catch (AbortNativeEnvironment abort) {
            throw abort;
        } catch (RuntimeException failure) {
            throw failNativeEnvironment(scope.session, scope.owner, failure, water ? "water" : "lava");
        }
    }

    private Session freeEnvironmentSession(EntityPlayerMP player) {
        if (player == null || player.connection == null) return null;
        Session session = sessions.get(player.connection.netManager);
        if (session == null || session.player != player || session.owner == null || session.owner.nativeDelegated)
            return null;
        var installed = FreeOwnerTransaction.nativeInstallation(player);
        return session.owner.snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                || installed != null && installed.journal().placement().shape() instanceof PlayerBodyShape.FreeCapsule
                ? session : null;
    }

    private AbortNativeEnvironment failNativeEnvironment(Session session, Owned owner,
                                                         RuntimeException failure, String stage) {
        owner.nativeCardinalMotion = null;
        // A callback may have replaced the owner; never retire or charge that successor for this operation.
        if (sessions.get(session.manager) == session && session.owner == owner) {
            invalidateAttitudeRequest(owner);
            owner.pendingCardinalPose = null;
            owner.freeResources.invalidate();
            owner.controls = IDLE;
            if (owner.nativeEnvironmentFailure == null) {
                owner.nativeEnvironmentFailure = failure;
                UnigravMod.LOGGER.error("ugmotion1 owner environment failed: stage={} scope={} revision={} tick={}",
                        stage, session.scope, owner.snapshot.revision(), session.world.getTotalWorldTime(), failure);
                if (serverTick - owner.recoveryWindowTick >= 80) {
                    owner.recoveryWindowTick = serverTick;
                    owner.recoveryRequests = 0;
                }
                if (++owner.recoveryRequests > 3) owner.recoveryTerminal = true;
                else {
                    owner.recoveryStartedTick = serverTick;
                    owner.predictionTimeline.retire();
                    owner.recoveryPending = true;
                    owner.recoveryAwaitingMirror = false;
                    owner.recoveryPacket = null;
                }
            }
        }
        return new AbortNativeEnvironment(failure);
    }

    /** Preserve the committed endpoint before a post-Step Applied admission is rejected. */
    private void failAppliedOwnerAdmission(Session session, Owned owner,
            Optional<FreeOwnerTransaction.InstallationCustody> custody,
            RuntimeException failure, String stage) {
        retainFailedInstallationCustody(session, owner, custody);
        if (sessions.get(session.manager) == session && session.owner == owner) {
            owner.predictionTimeline.retire();
            try {
                failNativeEnvironment(session, owner, failure, stage);
            } catch (RuntimeException recoveryFailure) {
                if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
                UnigravMod.LOGGER.error("ugmotion1 failed to record committed Applied admission failure: stage={} scope={} revision={}",
                        stage, session.scope, owner.snapshot.revision(), failure);
            }
        } else {
            UnigravMod.LOGGER.error("ugmotion1 committed Applied admission was superseded: stage={} scope={} revision={}",
                    stage, session.scope, owner.snapshot.revision(), failure);
        }
    }

    /** Retain an immutable failure observation on its original owner even after its live fence expires. */
    private static void retainFailedInstallationCustody(Session session, Owned owner,
            Optional<FreeOwnerTransaction.InstallationCustody> custodyValue) {
        if (custodyValue.isEmpty()) return;
        var custody = custodyValue.orElseThrow();
        if (custody.ownerIdentity() != owner || custody.actor() != session.player
                || !custody.scope().equals(session.scope) || custody.connection() != session.handler
                || custody.stage() == FreeOwnerTransaction.InstallationStage.NONE
                || custody.stage() == FreeOwnerTransaction.InstallationStage.PREPARED) return;
        owner.failedInstallationCustody = custody;
        session.failedInstallationCustody = custody;
    }

    /** Expected control flow; a boolean cannot stand in for an unobserved fluid result. */
    private static final class PauseNativeEnvironment extends RuntimeException {
        private PauseNativeEnvironment() { super("Free native environment paused for its pending native correction"); }
    }

    private static final class AbortNativeEnvironment extends RuntimeException {
        private final RuntimeException original;
        private AbortNativeEnvironment(RuntimeException original) {
            super("Free native environment operation aborted", original);
            this.original = original;
        }
    }

    private final class NativeEnvironmentScope {
        private final Session session;
        private final Owned owner;
        private final PlayerPhysicalSnapshot ownerSnapshot;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsInboundConnections.ConnectionHandle connection;
        private final PlayerPhysicsInboundConnections.ScopeHandle scope;
        private final long correction;
        private final FreeOwnerTransaction.NativeInstallation installation;
        private FreeTickResources resources;
        private FreeNativeEnvironment observer;

        private NativeEnvironmentScope(Session session) {
            this.session = session;
            owner = session.owner;
            if (current(session) && owner.teleportPending) throw new PauseNativeEnvironment();
            if (owner.nativeEnvironmentFailure != null)
                throw new AbortNativeEnvironment(owner.nativeEnvironmentFailure);
            if (owner.recoveryPending || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror
                    || owner.recoveryTerminal)
                throw new AbortNativeEnvironment(new IllegalStateException("Free native environment is fenced by recovery"));
            ownerSnapshot = owner.snapshot;
            connection = session.connectionHandle;
            scope = session.scopeHandle;
            correction = owner.travel.correctionRevision();
            installation = FreeOwnerTransaction.nativeInstallation(session.player);
            accepted = installation == null ? ownerSnapshot : installation.journal();
            try {
                if (installation != null && installation.transaction().accepted() != ownerSnapshot)
                    throw new IllegalStateException("Native journal does not belong to the actual accepted owner snapshot");
                requireCurrent();
                resources = owner.freeResources.acquire(owner, session.world, session.scope, correction);
                if (installation != null && installation.transaction().resources() != resources)
                    throw new IllegalStateException("Native installed journal changed its shared resources");
            } catch (PauseNativeEnvironment pause) { throw pause; }
            catch (RuntimeException failure) {
                throw failNativeEnvironment(session, owner, failure, "scope");
            }
        }

        private void requireCurrent() {
            if (current(session) && session.owner == owner && owner.teleportPending)
                throw new PauseNativeEnvironment();
            String invalid = !session.world.isCallingFromMinecraftThread() ? "server-thread"
                    : !current(session) ? "session-current"
                    : session.owner != owner ? "owner-identity"
                    : owner.snapshot != ownerSnapshot ? "snapshot-identity"
                    : session.connectionHandle != connection ? "connection-identity"
                    : session.scopeHandle != scope ? "scope-identity"
                    : owner.travel.correctionRevision() != correction ? "correction-revision"
                    : owner.teleportPending ? "teleport-pending"
                    : owner.nativeDelegated ? "native-delegated"
                    : owner.recoveryPending ? "recovery-pending"
                    : owner.recoveryAwaitingApply ? "recovery-awaiting-apply"
                    : owner.recoveryAwaitingMirror ? "recovery-awaiting-mirror"
                    : owner.recoveryTerminal ? "recovery-terminal"
                    : session.world.captureBlockSnapshots ? "world-capturing-snapshots"
                    : session.world.restoringBlockSnapshots ? "world-restoring-snapshots"
                    : !session.player.isEntityAlive() ? "actor-alive"
                    : !matchesPlacement(session.player, accepted.placement()) ? "actor-placement" : null;
            if (invalid != null)
                throw new IllegalStateException("Free native environment lost its exact owner or geometry"
                        + "; firstInvalid=" + invalid + "; acceptedRevision=" + accepted.revision()
                        + "; ownerRevision=" + owner.snapshot.revision()
                        + "; actorAlive=" + session.player.isEntityAlive() + "; actorDead=" + session.player.isDead
                        + "; installedJournal=" + (installation != null));
            if (installation != null) {
                if (FreeOwnerTransaction.nativeInstallation(session.player) != installation)
                    throw new IllegalStateException("Free native journal scope changed");
                installation.transaction().requireCurrent();
            }
            if (resources != null) resources.requireCurrent(owner, session.world, session.scope, correction);
        }

        private FreeNativeEnvironment environment() {
            if (observer == null) observer = new FreeNativeEnvironment(this, session.player, accepted, resources,
                    this::requireCurrent, Optional.of(delta -> {
                        requireCurrent();
                        Vec3 next = owner.pendingImpulse.add(delta);
                        if (!finite(next)) throw new IllegalArgumentException("Native water force accumulation overflow");
                        owner.pendingImpulse = next;
                    }));
            return observer;
        }

        private void check() {
            try { requireCurrent(); }
            catch (PauseNativeEnvironment pause) { throw pause; }
            catch (RuntimeException failure) { throw failNativeEnvironment(session, owner, failure, "fence"); }
        }
    }

    public PlayerPhysicsServer(GIntentServer intent, BiConsumer<EntityPlayerMP, PlayerPhysicsProtocol.Packet> outbound) {
        this.intent = Objects.requireNonNull(intent, "intent");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    public synchronized Optional<PlayerPhysicalSnapshot> accepted(NetHandlerPlayServer handler) {
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics read off-thread");
        Session session = sessions.get(handler.netManager);
        return session != null && session.handler == handler && session.player == handler.player
                && current(session) && session.owner != null
                ? Optional.of(session.owner.snapshot) : Optional.empty();
    }

    /** Accepted Cardinal geometry may shape Aqua's clearance query, but cannot authorize installation. */
    public synchronized AxisAlignedBB aquaPoseClearanceBox(EntityPlayerMP player, AxisAlignedBB aquaCandidate) {
        Objects.requireNonNull(aquaCandidate, "aquaCandidate");
        if (!drivesMotion(player) || PlayerKinematics.requiresNative(player)) return aquaCandidate;
        PlayerPhysicalSnapshot accepted = accepted(player.connection).orElse(null);
        if (accepted == null || !(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal)
                || !matchesPhysicalGeometry(player, accepted)) return aquaCandidate;
        return AquaCardinalPoseClearance.candidate(player, accepted, aquaCandidate);
    }

    synchronized Optional<FreeOwnerTransaction.InstallationCustody> failedInstallationCustody(
            NetHandlerPlayServer handler) {
        if (handler == null || handler.player == null) return Optional.empty();
        Session session = sessions.get(handler.netManager);
        return session != null && session.handler == handler && session.player == handler.player
                ? Optional.ofNullable(session.failedInstallationCustody) : Optional.empty();
    }

    /** Test-facing immutable budget diagnostics; never advances or mutates the accepted budget. */
    synchronized Optional<BoostMovementBudget.Snapshot> movementBudgetSnapshot(NetHandlerPlayServer handler) {
        if (handler == null || handler.player == null) return Optional.empty();
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics budget read off-thread");
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || session.player != handler.player
                || !current(session) || session.owner == null) return Optional.empty();
        return Optional.of(session.owner.budget.snapshot());
    }

    /** Read-only Cardinal render admission; never repairs geometry or changes protocol custody. */
    public synchronized Optional<PlayerPhysicalSnapshot> presentationAccepted(NetHandlerPlayServer handler) {
        if (handler == null) return Optional.empty();
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics presentation read off-thread");
        Session session = sessions.get(handler.netManager);
        if (!presentationEligible(session, handler)) return Optional.empty();
        return Optional.of(session.owner.snapshot);
    }

    /** Same-frame physical authority for a tracker that needs the accepted lock and its frozen heading. */
    public synchronized Optional<PresentationBodyReference> presentationBodyReference(NetHandlerPlayServer handler) {
        if (handler == null) return Optional.empty();
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics presentation read off-thread");
        Session session = sessions.get(handler.netManager);
        if (!presentationEligible(session, handler) || session.owner.travel.cardinalReference().isEmpty()) return Optional.empty();
        if (session.owner.travel.cardinalReference().isEmpty()) return Optional.empty();
        PlayerBodyReference.State state = session.owner.travel.bodyReference();
        PlayerBodyReference.Effective effective = state.effective();
        long requestId = effective.source() == PlayerBodyReference.Source.MANUAL
                ? state.manualIntent().map(PlayerBodyReference.ManualIntent::requestId).orElseThrow() : 0;
        return Optional.of(new PresentationBodyReference(session.owner.snapshot,
                new AcceptedBodyReference(requestId, effective.frame(), effective.tangentHeading(), effective.source())));
    }

    /** A completed presentation transition, never a grant of native movement custody. */
    public record PresentationRelease(PlayerPhysicalSnapshot.Scope scope, long revision, long generation) { }

    public synchronized Optional<PresentationRelease> presentationRelease(NetHandlerPlayServer handler) {
        if (handler == null || handler.player == null) return Optional.empty();
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Presentation release read off-thread");
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || session.player != handler.player
                || !current(session) || session.owner == null) return Optional.empty();
        Owned owner = session.owner;
        PresentationRelease release = owner.presentationRelease;
        if (release == null || !release.scope().equals(session.scope)
                || release.revision() != owner.snapshot.revision() || release.generation() != session.proposal
                || !owner.nativeDelegated || !presentationReleaseSettled(session, owner)) return Optional.empty();
        return Optional.of(release);
    }

    private boolean presentationReleaseSettled(Session session, Owned owner) {
        return current(session) && session.owner == owner && session.pending == null
                && !owner.teleportPending && !owner.resumeOffered && owner.nativeEnvironmentFailure == null
                && !owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement
                && !owner.nativeRestoreTerminalFailure && !owner.nativeHandoffPrepared
                && !owner.recoveryPending && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror
                && !owner.recoveryTerminal && !owner.recoveryRestoreActive && owner.failedInstallationCustody == null
                && owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape
                && shape.frame() == CardinalFrame.Y_POS;
    }

    private void completePresentationRelease(NativePresentationOffer sent) {
        if (sent == null) return;
        Session session = sent.session();
        Owned owner = sent.owner();
        if (!current(session) || session.owner != owner || owner.snapshot != sent.accepted()
                || session.proposal != sent.proposal()) return;
        boolean geometryCurrent = matchesAcceptedGeometry(session);
        if (!geometryCurrent || !current(session) || session.owner != owner || owner.snapshot != sent.accepted()
                || session.proposal != sent.proposal() || !owner.nativeDelegated
                || !presentationReleaseSettled(session, owner)) return;
        // Freeze only at the exact successful send. Later native translation must not be compared to this old box.
        owner.presentationRelease = new PresentationRelease(session.scope, owner.snapshot.revision(), session.proposal);
    }

    /**
     * Read-only admission for a consumer of the installed Free presentation. The snapshot and correction
     * revision are captured under the server owner lock and are returned only while the exact live actor
     * geometry still matches that accepted Free state.
     */
    public synchronized Optional<FreePresentationSnapshot> presentationFreeSnapshot(NetHandlerPlayServer handler) {
        if (handler == null || handler.player == null) return Optional.empty();
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Free presentation read off-thread");
        Session session = sessions.get(handler.netManager);
        if (!presentationFreeEligible(session, handler)) return Optional.empty();
        Owned owner = session.owner;
        PlayerPhysicalSnapshot accepted = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || !matchesPhysicalGeometry(session.player, accepted)
                || !current(session) || session.owner != owner || owner.snapshot != accepted
                || owner.travel.correctionRevision() != correction
                || !presentationFreeEligible(session, handler)) return Optional.empty();
        return Optional.of(new FreePresentationSnapshot(accepted, correction));
    }

    private boolean presentationFreeEligible(Session session, NetHandlerPlayServer handler) {
        if (session == null || session.handler != handler || session.player != handler.player
                || !current(session) || session.pending != null || session.owner == null) return false;
        Owned owner = session.owner;
        return !owner.nativeDelegated && !owner.teleportPending && !PlayerKinematics.requiresNative(session.player)
                && !owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement
                && !owner.nativeRestoreTerminalFailure
                && !owner.nativeHandoffPrepared && !owner.recoveryPending && !owner.recoveryAwaitingApply
                && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal;
    }

    private boolean presentationEligible(Session session, NetHandlerPlayServer handler) {
        if (session == null || session.handler != handler || session.player != handler.player
                || !current(session) || session.pending != null || session.owner == null) return false;
        Owned owner = session.owner;
        return !owner.nativeDelegated && !owner.teleportPending && !PlayerKinematics.requiresNative(session.player)
                && owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal
                && matchesAcceptedGeometry(session);
    }

    /** Protocol custody includes native delegation; use drivesMotion for competing movement writes. */
    public boolean owns(EntityPlayerMP player) {
        return player.connection != null && player.connection.player == player && accepted(player.connection).isPresent();
    }

    public synchronized Optional<PlayerPhysicalSnapshot> ownedPhysicalBody(EntityPlayerMP player) {
        if (initializingPose != null && initializingPose.session.player == player)
            return initializingPose.presentationJournal();
        if (!drivesMotion(player) || PlayerKinematics.requiresNative(player)) return Optional.empty();
        var installed = FreeOwnerTransaction.nativeInstallation(player);
        if (installed != null) {
            installed.transaction().requireOwnerFence();
            return Optional.of(installed.journal());
        }
        return accepted(player.connection);
    }

    public synchronized boolean drivesMotion(EntityPlayerMP player) {
        if (player == null || player.connection == null || player.connection.player != player) return false;
        Session session = sessions.get(player.connection.netManager);
        if (session == null || session.player != player || !current(session) || session.owner == null) return false;
        Owned owner = session.owner;
        return !owner.nativeDelegated && !owner.teleportPending;
    }

    /** Keep the endpoint installed by this exact owner while its native correction is pending. */
    public synchronized boolean preservesNativeUpdateGeometry(EntityPlayerMP player) {
        if (player == null || player.connection == null || player.connection.player != player) return false;
        NetHandlerPlayServer handler = player.connection;
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || session.player != player
                || !current(session) || session.owner == null) return false;
        Owned owner = session.owner;
        if (owner.teleportPending) return owner.nativeTeleportId.isPresent();
        return drivesMotion(player);
    }

    /** Observe the collision-checked native pose operation, never infer a resize from an arbitrary later write. */
    public synchronized void runNativePose(EntityPlayerMP player, Runnable original) {
        Objects.requireNonNull(original, "original");
        if (!drivesMotion(player)) { original.run(); return; }
        Session session = sessions.get(player.connection.netManager);
        Owned owner = session.owner;
        // Aqua's independent callback and Artemis's event can run inside updateSize.
        if (owner.poseDepth > 0) {
            original.run();
            return;
        }
        var ownerSnapshot = owner.snapshot;
        var entryReference = owner.travel.ownerReference();
        var entryManual = owner.travel.pendingManualIntent().orElse(null);
        var entryReceipt = owner.bodyReferenceReceipt;
        var installation = FreeOwnerTransaction.nativeInstallation(player);
        var anchor = installation == null ? ownerSnapshot : installation.journal();
        long correction = owner.travel.correctionRevision();
        long tick = session.world.getTotalWorldTime();
        var connectionHandle = session.connectionHandle;
        var scopeHandle = session.scopeHandle;
        long proposal = session.proposal;
        float rawEyeHeightBeforePose = player.eyeHeight;
        float effectiveEyeHeightBeforePose = player.getEyeHeight();
        var callbackEntry = new NativePoseEntry(WorldCollisionCandidates.ActorGeometry.read(player),
                rawEyeHeightBeforePose, effectiveEyeHeightBeforePose, player.motionX, player.motionY, player.motionZ,
                player.onGround, player.collided, player.collidedHorizontally, player.collidedVertically,
                player.fallDistance, connectionHandle, scopeHandle, proposal,
                ownerSnapshot.placement().shape() instanceof PlayerBodyShape.Cardinal ? nativePoseEyeProfile(player) : null,
                entryReference, entryManual, entryReceipt);
        boolean anchorCurrent = nativePoseAnchorCurrent(session, owner, ownerSnapshot, anchor,
                installation, correction, tick);
        boolean ownerFenceCurrent = anchorCurrent && nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot,
                anchor, installation, correction, tick, callbackEntry);
        boolean callbackEntryGeometryCurrent = ownerFenceCurrent
                && callbackEntry.geometry().equals(WorldCollisionCandidates.ActorGeometry.read(player));
        boolean anchored = anchorCurrent && ownerFenceCurrent && callbackEntryGeometryCurrent;
        boolean proposalPendingAtCapture = owner.trustedFreeResizeProposal != null;
        RuntimeException callbackFailure = null;
        boolean callbackReturned = false;
        owner.poseContext = anchored && installation == null
                && ownerSnapshot.placement().shape() instanceof PlayerBodyShape.Cardinal
                ? new PoseContext(ownerSnapshot, correction, tick) : null;
        owner.poseGrowth = null;
        owner.poseDepth++;
        float observedEyeHeight = Float.NaN;
        try {
            original.run();
            callbackReturned = true;
            // Read while poseDepth still identifies the controlled native callback. This is
            // the effective vanilla eye (which may differ from the stored raw eye field).
            boolean rawEyeWritten = Float.floatToRawIntBits(player.eyeHeight)
                    != Float.floatToRawIntBits(rawEyeHeightBeforePose);
            var completedProducer = rawEyeWritten ? nativePoseEyeProfile(player) : null;
            // Only this successful controlled raw write can explain a new producer value.
            observedEyeHeight = rawEyeWritten && completedProducer != null
                    ? completedProducer.effectiveEye() : player.getEyeHeight();
        } catch (RuntimeException failure) {
            callbackFailure = failure;
        } finally {
            owner.poseDepth--;
        }
        String anchorOutcome = !anchorCurrent ? "NOT_ANCHORED"
                : !ownerFenceCurrent || !callbackEntryGeometryCurrent ? "FENCE_REJECTED" : "ANCHORED";
        traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", anchorOutcome, tick, true,
                correction, proposalPendingAtCapture, Float.floatToIntBits(callbackEntry.rawEyeHeight()),
                Float.floatToIntBits(callbackEntry.effectiveEyeHeight()), callbackEntry.geometry(),
                "nativePoseAnchorCurrent/ownerFence/callbackEntryGeometry", null);
        if (callbackFailure != null)
            traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE",
                    callbackReturned ? "EFFECTIVE_EYE_OBSERVATION_FAILED" : "CALLBACK_FAILED", tick, true,
                    correction, proposalPendingAtCapture, Float.floatToIntBits(callbackEntry.rawEyeHeight()),
                    Float.floatToIntBits(observedEyeHeight), callbackEntry.geometry(),
                    "controlled native callback", callbackFailure);
        if (callbackFailure != null && nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor,
                installation, correction, tick, callbackEntry)) owner.nativeCardinalMotion = null;
        try {
            adoptNativePose(session, owner, ownerSnapshot, anchor, installation, anchored,
                    correction, tick, callbackFailure == null, observedEyeHeight, callbackFailure, callbackEntry);
        } catch (RuntimeException recoveryFailure) {
            if (nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry)) owner.nativeCardinalMotion = null;
            traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "ADOPTION_FAILED",
                    tick, true, correction, owner.trustedFreeResizeProposal != null,
                    Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                    callbackEntry.geometry(), "native pose adoption", recoveryFailure);
            if (ownerSnapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                failFreeNativePoseIfAnchorLost(session, owner, ownerSnapshot, anchor, installation,
                        correction, tick, callbackEntry, callbackFailure == null ? recoveryFailure : callbackFailure,
                        "native pose recovery");
            if (callbackFailure == null) throw recoveryFailure;
            if (recoveryFailure != callbackFailure) callbackFailure.addSuppressed(recoveryFailure);
            if (anchor.placement().shape() instanceof PlayerBodyShape.Cardinal && anchored) {
                try {
                    scheduleCardinalPoseRecoveryAfterRestoreFailure(session, owner, ownerSnapshot,
                            anchor, installation, correction, tick, callbackEntry, callbackFailure, recoveryFailure);
                } catch (RuntimeException schedulingFailure) {
                    if (schedulingFailure != callbackFailure) callbackFailure.addSuppressed(schedulingFailure);
                }
            }
        } finally {
            owner.poseContext = null;
            owner.poseGrowth = null;
        }
        if (callbackFailure != null && ownerSnapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
            failFreeNativePoseIfAnchorLost(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, callbackFailure, "native pose callback");
        // Checked geometry already written by a failed callback must not strand the owner.
        // Its original failure still reaches the authoritative caller with cleanup failures attached.
        if (callbackFailure != null) throw callbackFailure;
    }

    /**
     * Retire a failed Free callback only while its exact owner/correction still owns the actor.
     * ActorGeometry.read and getEyeHeight are virtual boundaries and may install a newer correction.
     */
    private void failFreeNativePoseIfAnchorLost(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry, RuntimeException failure, String stage) {
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)) return;
        boolean mismatched;
        try {
            var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
            if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry)) return;
            mismatched = !matchesPhysicalAnchorGeometry(actual, ownerSnapshot);
            if (!mismatched) {
                float eyeHeight = session.player.getEyeHeight();
                if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                        correction, tick, callbackEntry)) return;
                mismatched = eyeHeight != ownerSnapshot.placement().shape().dimensions().eyeHeight();
            }
        } catch (RuntimeException observationFailure) {
            if (observationFailure != failure) failure.addSuppressed(observationFailure);
            if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry)) return;
            mismatched = true;
        }
        // The geometry/effective-eye observations above may have re-entered native correction handling.
        if (!mismatched || !nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)) return;
        try {
            failNativeEnvironment(session, owner, failure, stage);
        } catch (RuntimeException recoveryFailure) {
            if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
        }
    }

    private boolean nativePoseAnchorCurrent(Session session, Owned owner, PlayerPhysicalSnapshot ownerSnapshot,
            PlayerPhysicalSnapshot anchor, FreeOwnerTransaction.NativeInstallation installation,
            long correction, long tick) {
        EntityPlayerMP player = session.player;
        if (!current(session) || session.owner != owner || owner.snapshot != ownerSnapshot
                || owner.teleportPending || owner.travel.correctionRevision() != correction
                || session.world.getTotalWorldTime() != tick || player.connection != session.handler
                || owner.nativeDelegated || owner.recoveryPending || owner.recoveryAwaitingApply
                || owner.recoveryAwaitingMirror || owner.recoveryTerminal) return false;
        if (installation == null) return matchesPhysicalAnchorGeometry(player, ownerSnapshot);
        return FreeOwnerTransaction.nativeInstallation(player) == installation
                && installation.transaction().actor() == player
                && installation.transaction().accepted() == ownerSnapshot
                && installation.transaction().world() == session.world
                && installation.transaction().tick() == tick
                && anchor.scope().equals(session.scope)
                && anchor.revision() == Math.incrementExact(ownerSnapshot.revision())
                && matchesPhysicalAnchorGeometry(player, anchor);
    }

    private boolean nativePoseOwnerFenceCurrent(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry) {
        return current(session) && session.owner == owner && owner.snapshot == ownerSnapshot
                && !owner.teleportPending && owner.travel.correctionRevision() == correction
                && session.world.getTotalWorldTime() == tick && session.player.connection == session.handler
                && session.connectionHandle == callbackEntry.connectionHandle()
                && session.scopeHandle == callbackEntry.scopeHandle()
                && session.proposal == callbackEntry.proposal()
                && !owner.nativeDelegated && !owner.recoveryPending && !owner.recoveryAwaitingApply
                && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal
                && session.scope.equals(anchor.scope())
                && (installation == null || FreeOwnerTransaction.nativeInstallation(session.player) == installation);
    }

    /** The caller has consumed vanilla's exact confirmation and verified the corrected native geometry. */
    private void completeNativeCorrectionLifetime(Session session, Owned owner, long correction) {
        // Callers finish their virtual geometry reads first, then establish the full pure owner fence.
        // Do not call back into the player after budget/snapshot mutation has begun.
        owner.freeResources.completeNativeCorrection(owner, session.world, session.scope, correction);
    }

    private void adoptNativePose(Session session, Owned owner, PlayerPhysicalSnapshot ownerSnapshot,
            PlayerPhysicalSnapshot anchor, FreeOwnerTransaction.NativeInstallation installation,
            boolean anchored, long correction, long tick, boolean callbackSucceeded, float observedEyeHeight,
            RuntimeException callbackFailure, NativePoseEntry callbackEntry) {
        var player = session.player;
        if (!anchored) return;
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor,
                installation, correction, tick, callbackEntry)) {
            traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "FENCE_REJECTED",
                    tick, true, correction, owner.trustedFreeResizeProposal != null,
                    Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                    callbackEntry.geometry(), "adoption owner fence", callbackFailure);
            return;
        }
        var retainedNativeMotion = owner.nativeCardinalMotion;
        var retainedReference = owner.travel.ownerReference();
        var retainedManual = owner.travel.pendingManualIntent().orElse(null);
        var retainedReceipt = owner.bodyReferenceReceipt;
        boolean retainedRestoreAwaiting = owner.nativeRestoreAwaitingAcknowledgement;
        boolean retainedReturnToOwned = owner.nativeRestoreReturnToOwned;
        var actual = WorldCollisionCandidates.ActorGeometry.read(player);
        if (anchor.placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            owner.nativeCardinalMotion = null;
            adoptTrustedFreeResizeProposal(session, owner, ownerSnapshot, anchor, installation,
                    actual, correction, tick, callbackSucceeded, observedEyeHeight,
                    callbackFailure, callbackEntry);
            return;
        }
        if (!callbackSucceeded) {
            owner.nativeCardinalMotion = null;
            restoreFailedCardinalNativePose(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry);
            return;
        }
        var growth = owner.poseGrowth;
        boolean completedGrowth = growth != null && actual.position().equals(growth.position())
                && actual.bounds().equals(growth.bounds()) && actual.width() == growth.width()
                && actual.height() == growth.height();
        if (!actual.position().equals(ownerSnapshot.placement().transport()) && !completedGrowth) return;
        if (!(ownerSnapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)) return;
        var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), observedEyeHeight);
        PendingCardinalPose previousPending = owner.pendingCardinalPose;
        if (previousPending != null && actual.width() == shape.dimensions().width()
                && actual.height() == shape.dimensions().height()
                && previousPending.matchesSnapshot(player, ownerSnapshot, observedEyeHeight)) return;
        if (dimensions.equals(shape.dimensions()) && actual.bounds().equals(ownerSnapshot.placement().bounds())) {
            var continuation = prepareNativeCardinalMotion(session, owner, ownerSnapshot, ownerSnapshot,
                    retainedNativeMotion, callbackEntry, false);
            boolean continuationCurrent = continuation == null || continuation.body.current()
                    && continuation.pose.equals(NativePoseProfile.read(player));
            var finalGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
            if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry) || owner.nativeCardinalMotion != retainedNativeMotion
                    || !continuationCurrent || !matchesPhysicalAnchorGeometry(finalGeometry, ownerSnapshot)
                    || continuation != null && Float.floatToRawIntBits(player.eyeHeight) != continuation.rawEye) return;
            if (owner.pendingCardinalPose == previousPending) owner.pendingCardinalPose = null;
            owner.nativeCardinalMotion = continuation;
            return;
        }
        var placement = shape.frame() == CardinalFrame.Y_POS || completedGrowth
                ? PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                        ownerSnapshot.placement().attitude(), new PlayerBodyShape.Cardinal(shape.frame(), dimensions))
                : ownerSnapshot.placement().proposeDimensionsAtFeet(dimensions);
        var captured = WorldCollisionCandidates.capture(session.world, player, placement.bounds(),
                CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, POSE_SCAN);
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)
                || !actual.equals(WorldCollisionCandidates.ActorGeometry.read(player))) return;
        // A mod may expand without a native collision test. Keep a viable accepted collider,
        // and let its next controlled callback retry the desired size after space becomes free.
        if (captured instanceof WorldCollisionCandidates.Unavailable
                || !((WorldCollisionCandidates.Snapshot) captured).boxes().isEmpty()) {
            var retained = shape.dimensions();
            var pending = new PendingCardinalPose(session, owner, retained, dimensions);
            if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry) || owner.pendingCardinalPose != previousPending) return;
            owner.pendingCardinalPose = pending;
            placement = completedGrowth ? placement.proposeDimensionsAtFeet(retained)
                    : PlayerBodyPlacement.fromCardinalBounds(ownerSnapshot.placement().bounds(),
                    ownerSnapshot.placement().transport(), ownerSnapshot.placement().attitude(),
                    new PlayerBodyShape.Cardinal(shape.frame(), retained));
            owner.lastFailure = captured instanceof WorldCollisionCandidates.Unavailable failure
                    ? "pose query " + failure.reason() + ": " + failure.detail() : "pose target occupied";
        } else {
            if (owner.pendingCardinalPose != previousPending) return;
            owner.pendingCardinalPose = null;
            owner.lastFailure = null;
        }
        PendingCardinalPose installedPending = owner.pendingCardinalPose;
        installPoseGeometry(player, placement);
        var proposed = new PlayerPhysicalSnapshot(ownerSnapshot.scope(), Math.incrementExact(ownerSnapshot.revision()),
                placement, ownerSnapshot.worldVelocity());
        var support = CardinalReorientation.probeSupport(session.world, player, proposed, shape.frame());
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)
                || owner.pendingCardinalPose != installedPending
                || !WorldCollisionCandidates.ActorGeometry.read(player).position().equals(placement.transport())
                || !WorldCollisionCandidates.ActorGeometry.read(player).bounds().equals(placement.bounds())) return;
        player.onGround = support.orElse(false);
        var candidate = new PlayerPhysicalSnapshot(ownerSnapshot.scope(), proposed.revision(), placement,
                ownerSnapshot.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(player.onGround,
                false, false, player.fallDistance)));
        long completedMotionX = Double.doubleToRawLongBits(player.motionX);
        long completedMotionY = Double.doubleToRawLongBits(player.motionY);
        long completedMotionZ = Double.doubleToRawLongBits(player.motionZ);
        boolean completedOnGround = player.onGround;
        boolean completedCollided = player.collided;
        boolean completedHorizontal = player.collidedHorizontally;
        boolean completedVertical = player.collidedVertically;
        int completedFall = Float.floatToRawIntBits(player.fallDistance);
        boolean nativeShapeEvent = Float.floatToRawIntBits(actual.width())
                    != Float.floatToRawIntBits(callbackEntry.geometry().width())
                || Float.floatToRawIntBits(actual.height())
                    != Float.floatToRawIntBits(callbackEntry.geometry().height());
        var motionReceipt = prepareNativeCardinalMotion(session, owner, ownerSnapshot, candidate,
                retainedNativeMotion, callbackEntry, nativeShapeEvent && installedPending == null);
        var currentEyeProducer = nativePoseEyeProfile(player);
        float completedEye = player.getEyeHeight();
        boolean completedEyeMatches = installedPending == null
                ? completedEye == placement.shape().dimensions().eyeHeight()
                : installedPending.matchesSnapshot(player, candidate, completedEye);
        var confirmedEyeProducer = nativePoseEyeProfile(player);
        boolean motionBodyCurrent = motionReceipt == null || motionReceipt.body.current();
        boolean motionPoseCurrent = motionReceipt == null || motionReceipt.pose.equals(NativePoseProfile.read(player));
        // All eye/profile/pending virtual reads are finished. Reobserve geometry, then read
        // mutable native fields directly before the final pure owner fence and commit.
        var completedGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
        boolean nativeFieldsUnchanged = Double.doubleToRawLongBits(player.motionX) == completedMotionX
                && Double.doubleToRawLongBits(player.motionY) == completedMotionY
                && Double.doubleToRawLongBits(player.motionZ) == completedMotionZ
                && player.onGround == completedOnGround && player.collided == completedCollided
                && player.collidedHorizontally == completedHorizontal && player.collidedVertically == completedVertical
                && Float.floatToRawIntBits(player.fallDistance) == completedFall
                && player.posX == placement.transport().x() && player.posY == placement.transport().y()
                && player.posZ == placement.transport().z()
                && player.width == placement.shape().dimensions().width()
                && player.height == placement.shape().dimensions().height()
                && player.dimension == candidate.scope().dimension() && !player.isDead;
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry) || owner.pendingCardinalPose != installedPending
                || owner.travel.ownerReference() != retainedReference
                || owner.travel.pendingManualIntent().orElse(null) != retainedManual
                || owner.bodyReferenceReceipt != retainedReceipt
                || owner.nativeCardinalMotion != retainedNativeMotion
                || owner.nativeRestoreAwaitingAcknowledgement != retainedRestoreAwaiting
                || owner.nativeRestoreReturnToOwned != retainedReturnToOwned
                || !nativeFieldsUnchanged
                || !completedGeometry.position().equals(placement.transport())
                || !completedGeometry.bounds().equals(placement.bounds())
                || completedGeometry.width() != placement.shape().dimensions().width()
                || completedGeometry.height() != placement.shape().dimensions().height()
                || !completedEyeMatches || !motionBodyCurrent || !motionPoseCurrent
                || !Objects.equals(currentEyeProducer, confirmedEyeProducer)) return;
        // Native pose callbacks may rebuild a Y box before restoring the identical non-Y owner.
        // Complete collision/support/installation validation above, but do not invalidate a published
        // prediction checkpoint merely because that transient native box differed from the owner.
        boolean unchanged = candidate.scope().equals(ownerSnapshot.scope())
                && candidate.placement().equals(ownerSnapshot.placement())
                && candidate.worldVelocity().equals(ownerSnapshot.worldVelocity())
                && candidate.contacts().equals(ownerSnapshot.contacts())
                && previousPending == null && installedPending == null
                && !retainedRestoreAwaiting && !retainedReturnToOwned
                && callbackEntry.reference() == retainedReference && callbackEntry.manual() == retainedManual
                && callbackEntry.receipt() == retainedReceipt
                && Objects.equals(callbackEntry.eyeProducer(), currentEyeProducer);
        if (!unchanged) {
            if (!placement.transport().equals(ownerSnapshot.placement().transport())) {
                var transport = placement.transport();
                owner.budget.rebase(new Vec3(transport.x(), transport.y(), transport.z()), owner.budgetTick);
            }
            owner.snapshot = candidate;
        }
        owner.nativeCardinalMotion = motionReceipt == null ? null
                : motionReceipt.withSnapshot(owner.snapshot);
        if (motionReceipt != null) owner.nativeCardinalMotionGeneration = motionReceipt.generation;
    }

    /** Lifetime provenance, never a collision-query or movement-budget certificate. */
    final class NativeCardinalMotionReceipt {
        private final Session session;
        private final Owned owner;
        private final PlayerPhysicalSnapshot snapshot;
        private final WorldServer world;
        private final Object connection, scopeHandle;
        private final long proposal, correction, generation;
        private final NativeCardinalBodyProfile.Value body;
        private final NativePoseProfile pose;
        private final int rawEye;
        private NativeCardinalMotionReceipt(Session session, Owned owner, PlayerPhysicalSnapshot snapshot,
                NativeCardinalBodyProfile.Value body, NativePoseProfile pose, long generation) {
            this.session = session; this.owner = owner; this.snapshot = snapshot; this.body = body;
            this.pose = pose; this.generation = generation; world = session.world;
            connection = session.connectionHandle; scopeHandle = session.scopeHandle;
            proposal = session.proposal; correction = owner.travel.correctionRevision();
            rawEye = Float.floatToRawIntBits(session.player.eyeHeight);
        }
        private NativeCardinalMotionReceipt(NativeCardinalMotionReceipt previous, PlayerPhysicalSnapshot snapshot) {
            session = previous.session; owner = previous.owner; this.snapshot = snapshot; world = previous.world;
            connection = previous.connection; scopeHandle = previous.scopeHandle; proposal = previous.proposal;
            correction = previous.correction; generation = previous.generation; body = previous.body;
            pose = previous.pose; rawEye = previous.rawEye;
        }
        private NativeCardinalMotionReceipt withSnapshot(PlayerPhysicalSnapshot successor) {
            return new NativeCardinalMotionReceipt(this, successor);
        }
        private boolean identity(PlayerPhysicalSnapshot exact) {
            return PlayerPhysicsServer.this.current(session) && session.owner == owner && owner.snapshot == exact && snapshot == exact
                    && owner.nativeCardinalMotion == this && session.world == world
                    && session.connectionHandle == connection && session.scopeHandle == scopeHandle
                    && session.proposal == proposal && session.scope.equals(snapshot.scope())
                    && owner.travel.correctionRevision() == correction && !owner.teleportPending
                    && !owner.nativeDelegated && !owner.recoveryPending && !owner.recoveryAwaitingApply
                    && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal
                    && exact.placement().shape() instanceof PlayerBodyShape.Cardinal;
        }
        private boolean bodyCurrent(PlayerPhysicalSnapshot exact) {
            if (!identity(exact)) return false;
            boolean valid = body.current();
            return valid && identity(exact);
        }
        private boolean physicalCurrent(PlayerPhysicalSnapshot exact) {
            if (!bodyCurrent(exact)) return false;
            var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
            return identity(exact) && matchesPhysicalAnchorGeometry(actual, exact)
                    && Float.floatToRawIntBits(session.player.eyeHeight) == rawEye;
        }
        boolean current(EntityPlayerMP actor, PlayerPhysicalSnapshot exact) {
            if (actor != session.player) return false;
            boolean valid = physicalCurrent(exact);
            if (valid) {
                var observedPose = NativePoseProfile.read(actor);
                valid = pose.equals(observedPose) && physicalCurrent(exact);
            }
            if (!valid && identity(exact)) owner.nativeCardinalMotion = null;
            return valid;
        }
    }

    private NativeCardinalMotionReceipt prepareNativeCardinalMotion(Session session, Owned owner,
            PlayerPhysicalSnapshot before, PlayerPhysicalSnapshot candidate,
            NativeCardinalMotionReceipt previous, NativePoseEntry entry, boolean nativeShapeEvent) {
        if (!(before.placement().shape() instanceof PlayerBodyShape.Cardinal oldShape)
                || !(candidate.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                || oldShape.frame() != shape.frame() || !before.placement().attitude().equals(candidate.placement().attitude()))
            return null;
        // A blocked native stand-up can update pose markers, never a changed D/L/source qualification.
        boolean continuation = previous != null && previous.physicalCurrent(before)
                && StoredRealizationLimits.sameRaw(shape.dimensions().width(), oldShape.dimensions().width())
                && StoredRealizationLimits.sameRaw(shape.dimensions().height(), oldShape.dimensions().height())
                && candidate.placement().bounds().equals(before.placement().bounds())
                && candidate.placement().transport().equals(before.placement().transport());
        var body = continuation ? previous.body : NativeCardinalBodyProfile.sample(session.player).orElse(null);
        if (body == null || !body.current()) return null;
        var pose = NativePoseProfile.read(session.player);
        boolean supportedEvent = nativeShapeEvent && (pose.sneaking() || pose.sleeping() || pose.gliding()
                || body.profile().source() == NativeBodyProfileProducer.Source.AQUA_STANDING);
        if (!continuation && !supportedEvent) return null;
        return new NativeCardinalMotionReceipt(session, owner, candidate, body, pose,
                continuation ? previous.generation : Math.incrementExact(owner.nativeCardinalMotionGeneration));
    }

    /** A failed Cardinal callback cannot commit its partial pose; restore only while its exact fence survives. */
    private void restoreFailedCardinalNativePose(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry) {
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)) return;
        EntityPlayerMP player = session.player;
        var placement = anchor.placement();
        var dimensions = placement.shape().dimensions();
        var bounds = placement.bounds();
        var transport = placement.transport();

        player.width = (float) dimensions.width();
        player.height = (float) dimensions.height();
        requireNativePoseRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "Cardinal dimensions");

        // Restore the exact accepted raw field, then verify the active profile through its real getter.
        // The getter is virtual and may re-enter; no stale field write follows it.
        player.eyeHeight = callbackEntry.rawEyeHeight();
        float restoredEyeHeight = player.getEyeHeight();
        requireNativePoseRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "Cardinal eye-height getter");
        if (Float.floatToIntBits(restoredEyeHeight) != Float.floatToIntBits(callbackEntry.effectiveEyeHeight())
                || Float.floatToIntBits(restoredEyeHeight) != Float.floatToIntBits((float) dimensions.eyeHeight()))
            throw new IllegalStateException("Cardinal native profile cannot express its exact accepted eye height"
                    + " (accepted=" + dimensions.eyeHeight() + ", actual=" + restoredEyeHeight + ")");

        player.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        // Test and mod subclasses can re-enter from this native setter. Never write position after a lost fence.
        requireNativePoseRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "Cardinal bounds setter");

        player.posX = transport.x();
        player.posY = transport.y();
        player.posZ = transport.z();
        player.motionX = callbackEntry.motionX();
        player.motionY = callbackEntry.motionY();
        player.motionZ = callbackEntry.motionZ();
        player.onGround = callbackEntry.onGround();
        player.collided = callbackEntry.collided();
        player.collidedHorizontally = callbackEntry.collidedHorizontally();
        player.collidedVertically = callbackEntry.collidedVertically();
        player.fallDistance = callbackEntry.fallDistance();
        requireNativePoseRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "Cardinal actor mirror");

        boolean geometryRestored = WorldCollisionCandidates.ActorGeometry.read(player).equals(callbackEntry.geometry())
                && matchesPhysicalGeometry(player, anchor);
        boolean dynamicsRestored = nativePoseDynamicsMatch(player, callbackEntry);
        requireNativePoseRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "Cardinal restore verification");
        if (!geometryRestored || !dynamicsRestored)
            throw new IllegalStateException("Failed Cardinal native pose did not restore its exact callback-entry mirror");
    }

    private void requireNativePoseRestoreFence(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry, String stage) {
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry))
            throw new IllegalStateException("Cardinal native pose restore stopped after owner fence changed at " + stage);
    }

    private static boolean nativePoseDynamicsMatch(EntityPlayerMP player, NativePoseEntry callbackEntry) {
        return Double.doubleToLongBits(player.motionX) == Double.doubleToLongBits(callbackEntry.motionX())
                && Double.doubleToLongBits(player.motionY) == Double.doubleToLongBits(callbackEntry.motionY())
                && Double.doubleToLongBits(player.motionZ) == Double.doubleToLongBits(callbackEntry.motionZ())
                && player.onGround == callbackEntry.onGround() && player.collided == callbackEntry.collided()
                && player.collidedHorizontally == callbackEntry.collidedHorizontally()
                && player.collidedVertically == callbackEntry.collidedVertically()
                && Float.floatToIntBits(player.fallDistance) == Float.floatToIntBits(callbackEntry.fallDistance());
    }

    /** Enter the existing bounded scope-recovery path only if the failed pose still owns the exact fence. */
    private void scheduleCardinalPoseRecoveryAfterRestoreFailure(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry, RuntimeException callbackFailure, RuntimeException restoreFailure) {
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)) return;
        invalidateAttitudeRequest(owner);
        owner.freeResources.invalidate();
        owner.controls = IDLE;
        owner.lastFailure = "Cardinal native pose restore failed: " + restoreFailure.getMessage();
        if (serverTick - owner.recoveryWindowTick >= 80) {
            owner.recoveryWindowTick = serverTick;
            owner.recoveryRequests = 0;
        }
        if (++owner.recoveryRequests > 3) owner.recoveryTerminal = true;
        else {
            owner.recoveryStartedTick = serverTick;
            owner.predictionTimeline.retire();
            owner.recoveryPending = true;
            owner.recoveryAwaitingMirror = false;
            owner.recoveryPacket = null;
        }
        UnigravMod.LOGGER.error("ugmotion1 Cardinal native pose recovery failed: stage={}, scope={}, revision={}, tick={}",
                "restore accepted actor mirror", session.scope, ownerSnapshot.revision(), tick, callbackFailure);
    }

    private void adoptTrustedFreeResizeProposal(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation,
            WorldCollisionCandidates.ActorGeometry observed, long correction, long tick,
            boolean callbackSucceeded, float observedEyeHeight, RuntimeException callbackFailure,
            NativePoseEntry callbackEntry) {
        EntityPlayerMP player = session.player;
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry)) {
            traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "FENCE_REJECTED",
                    tick, true, correction, owner.trustedFreeResizeProposal != null,
                    Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                    observed, "trusted resize adoption owner fence", callbackFailure);
            return;
        }
        var anchorPlacement = anchor.placement();
        NativePoseEyeProducer.Profile observedEyeProfile = null;
        RawEyeInputReceipt rawEyeInput = null;
        // The callback already failed before adoption. Restoration is cleanup of that same
        // operation, so it cannot become the primary failure at the owner boundary.
        RuntimeException primary = callbackFailure;
        try {
            if (!callbackSucceeded) {
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "CALLBACK_FAILED",
                        tick, true, correction, owner.trustedFreeResizeProposal != null,
                        Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                        observed, "controlled native callback", callbackFailure);
                return;
            }
            float producedRawEye = player.eyeHeight;
            observedEyeProfile = captureNativePoseEyeProfile(player, observedEyeHeight);
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "native eye producer capture");
            var producerGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "native eye producer geometry");
            if (!producerGeometry.equals(observed))
                throw new IllegalStateException("Native eye producer changed the controlled callback geometry");
            if (Float.floatToRawIntBits(player.eyeHeight) != Float.floatToRawIntBits(producedRawEye))
                throw new IllegalStateException("Native eye producer changed its captured raw input");
            if (observedEyeProfile != null && Float.floatToRawIntBits(producedRawEye)
                    != Float.floatToRawIntBits(callbackEntry.rawEyeHeight()))
                rawEyeInput = new RawEyeInputReceipt(ownerSnapshot, anchor, installation, callbackEntry,
                        correction, tick, observedEyeProfile, producedRawEye);
            PlayerBodyShape.Dimensions dimensions;
            try {
                dimensions = new PlayerBodyShape.Dimensions(observed.width(), observed.height(), observedEyeHeight);
            } catch (IllegalArgumentException invalidProfile) {
                clearMatchingTrustedResizeProposal(owner, ownerSnapshot, anchor, installation);
                owner.lastFailure = "Free native resize profile rejected: " + invalidProfile.getMessage();
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "PROFILE_INVALID",
                        tick, true, correction, owner.trustedFreeResizeProposal != null,
                        Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                        observed, "observed dimensions", invalidProfile);
                return;
            }
            var acceptedFree = (PlayerBodyShape.FreeCapsule) anchorPlacement.shape();
            var bodyProfile = NativeCardinalBodyProfile.sample(player).filter(value ->
                    acceptedFree.eyeAnchor().isPresent()
                    || !StoredRealizationLimits.sameRaw(dimensions.width(), value.physical().diameter())
                    || !StoredRealizationLimits.sameRaw(dimensions.height(), value.physical().length()));
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "native physical profile capture");
            boolean nativeWrite = Float.floatToRawIntBits(observed.width())
                    != Float.floatToRawIntBits(callbackEntry.geometry().width())
                    || Float.floatToRawIntBits(observed.height())
                    != Float.floatToRawIntBits(callbackEntry.geometry().height())
                    || Float.floatToRawIntBits(player.eyeHeight)
                    != Float.floatToRawIntBits(callbackEntry.rawEyeHeight());
            var retained = owner.trustedFreeResizeProposal;
            boolean retainNativeWrite = !nativeWrite && observed.position().equals(anchorPlacement.transport())
                    && retained != null && retained.nativeWrite()
                    && retained.ownerIdentity() == owner && retained.ownerSnapshot() == ownerSnapshot
                    && retained.anchorSnapshot() == anchor && retained.installation() == installation
                    && retained.world() == session.world && retained.connection() == session.connectionHandle
                    && retained.scopeHandle() == session.scopeHandle && retained.sessionProposal() == session.proposal
                    && retained.scope().equals(session.scope) && retained.correctionRevision() == correction
                    && tick >= retained.capturedWorldTick() && tick - retained.capturedWorldTick() <= 1
                    && Objects.equals(retained.eyeProducer(), observedEyeProfile)
                    && retained.observation().bodyProfileCurrent();
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "native write supersession");
            if (retainNativeWrite && owner.trustedFreeResizeProposal == retained) {
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "RETAIN_NATIVE_WRITE",
                        tick, true, correction, true, Float.floatToIntBits(callbackEntry.rawEyeHeight()),
                        Float.floatToIntBits(observedEyeHeight), observed, dimensions, null);
                return;
            }
            var acceptedDimensions = anchorPlacement.shape().dimensions();
            boolean exactNoop = observed.position().equals(anchorPlacement.transport())
                    && observed.bounds().equals(anchorPlacement.bounds())
                    && dimensions.equals(acceptedDimensions)
                    && (bodyProfile.isEmpty() || bodyProfile.orElseThrow().physical().equals(acceptedFree.collisionProfile()));
            if (exactNoop) {
                clearMatchingTrustedResizeProposal(owner, ownerSnapshot, anchor, installation);
                owner.lastFailure = null;
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "EXACT_NOOP",
                        tick, true, correction, owner.trustedFreeResizeProposal != null,
                        Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                        observed, dimensions, null);
                return;
            }
            // Only dimensions and eye observed inside the controlled native callback are trusted.
            // The raw native box and position never become resize authority.
            if (!observed.position().equals(anchorPlacement.transport())
                    || (dimensions.equals(acceptedDimensions)
                        && (bodyProfile.isEmpty() || bodyProfile.orElseThrow().physical().equals(acceptedFree.collisionProfile())))
                    || (!dimensions.supportsCapsule() && bodyProfile.isEmpty())) {
                clearMatchingTrustedResizeProposal(owner, ownerSnapshot, anchor, installation);
                owner.lastFailure = "Free native pose changed geometry outside a representable trusted resize";
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "UNREPRESENTABLE",
                        tick, true, correction, owner.trustedFreeResizeProposal != null,
                        Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                        observed, dimensions, null);
                return;
            }
            owner.trustedFreeResizeProposal = new NativeResizeProposal(owner, ownerSnapshot, anchor,
                    installation, session.world, session.connectionHandle, session.scopeHandle,
                    session.proposal, session.scope, correction, tick,
                    rawEyeInput == null ? callbackEntry.rawEyeHeight() : rawEyeInput.producedRawEye(),
                    new ModeTransitionPrepared.TrustedResizeObservation(dimensions, observed, bodyProfile), observedEyeProfile, nativeWrite);
            owner.lastFailure = null;
            traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "CREATED",
                    tick, true, correction, true, Float.floatToIntBits(callbackEntry.rawEyeHeight()),
                    Float.floatToIntBits(observedEyeHeight), observed, dimensions, null);
        } catch (RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            boolean ownerFenceCurrent = nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor,
                    installation, correction, tick, callbackEntry);
            if (ownerFenceCurrent) {
                try {
                    // Only a successful controlled event can retain producer input across physical restore.
                    var successfulRawInput = primary == null && callbackSucceeded ? rawEyeInput : null;
                    float restoredRawEye = successfulRawInput == null ? callbackEntry.rawEyeHeight()
                            : successfulRawInput.producedRawEye();
                    restoreTrustedFreeAnchorGeometry(session, owner, ownerSnapshot, anchor, installation,
                            correction, tick, callbackEntry, observedEyeProfile, successfulRawInput);
                    if (primary == null && callbackSucceeded && observedEyeProfile != null) {
                        if (installation == null)
                            commitStandaloneNativeEyeProfile(session, owner, ownerSnapshot, anchor,
                                    correction, tick, callbackEntry, observedEyeProfile, restoredRawEye);
                        else installation.transaction().recordInstalledEyeObservation(installation,
                                observedEyeProfile, restoredRawEye);
                    }
                } catch (RuntimeException restoreFailure) {
                    RuntimeException primaryFailure = primary == null ? restoreFailure : primary;
                    if (restoreFailure != primaryFailure) primaryFailure.addSuppressed(restoreFailure);
                    try {
                        if (nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                                correction, tick, callbackEntry))
                            failNativeEnvironment(session, owner, primaryFailure, "native pose restore");
                    } catch (RuntimeException recoveryFailure) {
                        if (recoveryFailure != primaryFailure) primaryFailure.addSuppressed(recoveryFailure);
                    }
                    if (primary == null) throw restoreFailure;
                }
            } else {
                traceFreeResize(session, ownerSnapshot, anchor, owner.snapshot, "CAPTURE", "FENCE_REJECTED",
                        tick, true, correction, owner.trustedFreeResizeProposal != null,
                        Float.floatToIntBits(callbackEntry.rawEyeHeight()), Float.floatToIntBits(observedEyeHeight),
                        observed, "anchor restoration owner fence", primary);
            }
        }
    }

    private static void clearMatchingTrustedResizeProposal(Owned owner, PlayerPhysicalSnapshot ownerSnapshot,
            PlayerPhysicalSnapshot anchor, FreeOwnerTransaction.NativeInstallation installation) {
        NativeResizeProposal pending = owner.trustedFreeResizeProposal;
        if (pending != null && pending.ownerIdentity() == owner && pending.ownerSnapshot() == ownerSnapshot
                && pending.anchorSnapshot() == anchor && pending.installation() == installation)
            owner.trustedFreeResizeProposal = null;
    }

    /** A controlled successful producer may change eye metadata without moving or resizing the body. */
    private void commitStandaloneNativeEyeProfile(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor, long correction,
            long tick, NativePoseEntry callbackEntry, NativePoseEyeProducer.Profile profile, float restoredRawEye) {
        if (Float.floatToIntBits((float) anchor.placement().shape().dimensions().eyeHeight())
                == Float.floatToIntBits(profile.effectiveEye())) return;
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, null,
                correction, tick, callbackEntry, "eye metadata preparation");
        if (ownerSnapshot != anchor)
            throw new IllegalStateException("Standalone eye metadata requires the exact accepted anchor");
        var numeric = owner.freeResources.acquire(owner, session.world, session.scope, correction).numeric();
        var replacement = anchor.placement().withEyeMetadata(profile.effectiveEye(), numeric);
        var successor = new PlayerPhysicalSnapshot(anchor.scope(), Math.incrementExact(anchor.revision()),
                replacement, anchor.worldVelocity(), anchor.contacts());
        var retained = owner.trustedFreeResizeProposal;
        NativeResizeProposal rebased = null;
        if (retained != null && retained.ownerIdentity() == owner && retained.ownerSnapshot() == anchor
                && retained.anchorSnapshot() == anchor && retained.installation() == null
                && retained.observation().supportsPhysicalCapsule() && retained.observation().bodyProfileCurrent()
                && (!StoredRealizationLimits.sameRaw(retained.observation().dimensions().width(),
                        replacement.shape().dimensions().width())
                    || !StoredRealizationLimits.sameRaw(retained.observation().dimensions().height(),
                        replacement.shape().dimensions().height())))
            rebased = new NativeResizeProposal(owner, successor, successor, null, retained.world(),
                    retained.connection(), retained.scopeHandle(), retained.sessionProposal(), retained.scope(),
                    retained.correctionRevision(), retained.capturedWorldTick(), retained.anchorRawEyeHeight(),
                    retained.observation(), profile, retained.nativeWrite());
        var actualProfile = nativePoseEyeProfile(session.player);
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, null,
                correction, tick, callbackEntry, "eye metadata producer");
        var physical = WorldCollisionCandidates.ActorGeometry.read(session.player);
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, null,
                correction, tick, callbackEntry, "eye metadata final physical fence");
        if (!profile.equals(actualProfile) || !matchesPhysicalAnchorGeometry(physical, anchor)
                || !nativePoseDynamicsMatch(session.player, callbackEntry)
                || Float.floatToIntBits(session.player.eyeHeight) != Float.floatToIntBits(restoredRawEye)
                || owner.trustedFreeResizeProposal != retained)
            throw new IllegalStateException("Controlled eye metadata lost its producer or physical anchor");
        // All external observations precede this one owner-local publication commit.
        owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
        owner.snapshot = successor;
        owner.trustedFreeResizeProposal = rebased;
        owner.eyeProfilePublicationPending = successor;
    }

    private static Optional<NativeResizeProposal> consumeTrustedFreeResizeProposal(
            Session session, Owned owner, PlayerPhysicalSnapshot accepted, long worldTick,
            long correction, PlayerPhysicsInboundConnections.ConnectionHandle connection,
            PlayerPhysicsInboundConnections.ScopeHandle scopeHandle, String consumeMode) {
        NativeResizeProposal proposal = owner.trustedFreeResizeProposal;
        owner.trustedFreeResizeProposal = null;
        if (proposal == null) {
            traceFreeResize(session, accepted, null, owner.snapshot, "CONSUME", "ABSENT", worldTick, true,
                    correction, false, UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null, consumeMode, null);
            return Optional.empty();
        }
        if (proposal.ownerIdentity() != owner || proposal.world() != session.world
                || proposal.connection() != connection || proposal.scopeHandle() != scopeHandle
                || proposal.sessionProposal() != session.proposal || !proposal.scope().equals(session.scope)
                || proposal.correctionRevision() != correction || worldTick < proposal.capturedWorldTick()
                || worldTick - proposal.capturedWorldTick() > 1) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_IDENTITY_TIME", worldTick, true, correction, true,
                    UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null, consumeMode, null);
            return Optional.empty();
        }
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_ANCHOR_RAW_EYE", worldTick, true, correction, true,
                    UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null, consumeMode, null);
            return Optional.empty();
        }
        if (!matchesPhysicalAnchorGeometry(session.player, accepted)) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_ANCHOR_RAW_EYE", worldTick, true, correction, true,
                    UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null, consumeMode, null);
            return Optional.empty();
        }
        int rawEyeBits = Float.floatToIntBits(session.player.eyeHeight);
        if (rawEyeBits != Float.floatToIntBits(proposal.anchorRawEyeHeight())) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_ANCHOR_RAW_EYE", worldTick, true, correction, true,
                    rawEyeBits, UNOBSERVED_EYE_BITS, null, consumeMode, null);
            return Optional.empty();
        }

        if (proposal.installation() == null) {
            if (proposal.ownerSnapshot() != accepted || proposal.anchorSnapshot() != accepted) {
                traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                        "REJECT_SNAPSHOT", worldTick, true, correction, true,
                        rawEyeBits, UNOBSERVED_EYE_BITS, null, consumeMode, null);
                return Optional.empty();
            }
        } else {
            PlayerPhysicalSnapshot journal = proposal.anchorSnapshot();
            if (proposal.ownerSnapshot() != accepted || proposal.installation().journal() != journal
                    || !journal.scope().equals(accepted.scope())
                    || journal.revision() != accepted.revision()
                    || journal.placement() != accepted.placement()) {
                traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                        "REJECT_SNAPSHOT", worldTick, true, correction, true,
                        rawEyeBits, UNOBSERVED_EYE_BITS, null, consumeMode, null);
                return Optional.empty();
            }
        }
        float actualEye;
        try {
            actualEye = session.player.getEyeHeight();
        } catch (RuntimeException | Error failure) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_EFFECTIVE_EYE_FAILURE", worldTick, true, correction, true,
                    rawEyeBits, UNOBSERVED_EYE_BITS, null, consumeMode, failure);
            throw failure;
        }
        float acceptedEye = (float) accepted.placement().shape().dimensions().eyeHeight();
        float observedEye = (float) proposal.observation().dimensions().eyeHeight();
        int effectiveEyeBits = Float.floatToIntBits(actualEye);
        if (effectiveEyeBits != Float.floatToIntBits(acceptedEye)
                && effectiveEyeBits != Float.floatToIntBits(observedEye)) {
            traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                    "REJECT_EFFECTIVE_EYE", worldTick, true, correction, true,
                    rawEyeBits, effectiveEyeBits, proposal.observation().observedGeometry(), consumeMode, null);
            return Optional.empty();
        }
        if (proposal.eyeProducer() != null) {
            var actualProducer = nativePoseEyeProfile(session.player);
            if (!proposal.eyeProducer().equals(actualProducer) || session.owner != owner
                    || owner.snapshot != accepted || session.player.world != session.world
                    || session.player.connection != session.handler || owner.teleportPending
                    || owner.travel.correctionRevision() != correction
                    || session.connectionHandle != connection || session.scopeHandle != scopeHandle
                    || session.proposal != proposal.sessionProposal()) {
                traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME",
                        "REJECT_EYE_PRODUCER", worldTick, true, correction, true,
                        rawEyeBits, effectiveEyeBits, proposal.observation().observedGeometry(), consumeMode, null);
                return Optional.empty();
            }
        }
        traceFreeResize(session, accepted, proposal.anchorSnapshot(), owner.snapshot, "CONSUME", "ACCEPT",
                worldTick, true, correction, true, rawEyeBits, effectiveEyeBits,
                proposal.observation().observedGeometry(), consumeMode, null);
        return Optional.of(proposal);
    }

    private static void promoteTrustedResizeProposalAfterInstalledTick(Owned owner,
            PlayerPhysicalSnapshot source, PlayerPhysicalSnapshot installed, float installedRawEyeHeight) {
        NativeResizeProposal proposal = owner.trustedFreeResizeProposal;
        if (proposal == null || proposal.installation() == null) return;
        PlayerPhysicalSnapshot journal = proposal.anchorSnapshot();
        if (proposal.ownerIdentity() == owner && proposal.ownerSnapshot() == source
                && proposal.installation().transaction().accepted() == source
                && proposal.installation().journal() == journal
                && installed.scope().equals(journal.scope()) && installed.revision() == journal.revision()
                && installed.placement() == journal.placement()) {
            owner.trustedFreeResizeProposal = new NativeResizeProposal(owner, installed, journal,
                    proposal.installation(), proposal.world(), proposal.connection(), proposal.scopeHandle(),
                    proposal.sessionProposal(), proposal.scope(), proposal.correctionRevision(),
                    proposal.capturedWorldTick(), installedRawEyeHeight, proposal.observation(), proposal.eyeProducer(), proposal.nativeWrite());
        } else owner.trustedFreeResizeProposal = null;
    }

    private static void rebaseInstalledPhysicalResizeIntent(Owned owner, PlayerPhysicalSnapshot source,
            PlayerPhysicalSnapshot installed, FreeOwnerTransaction.EyeMetadataCompletion completion,
            float rawEye) {
        var intent = owner.trustedFreeResizeProposal;
        owner.trustedFreeResizeProposal = null;
        if (intent == null || intent.ownerIdentity() != owner || intent.ownerSnapshot() != source
                || intent.anchorSnapshot() != completion.journal() || intent.installation() == null
                || intent.installation().journal() != completion.journal()) return;
        var desired = intent.observation().dimensions();
        var actual = installed.placement().shape().dimensions();
        if (StoredRealizationLimits.sameRaw(desired.width(), actual.width())
                && StoredRealizationLimits.sameRaw(desired.height(), actual.height())) return;
        owner.trustedFreeResizeProposal = new NativeResizeProposal(owner, installed, installed, null,
                intent.world(), intent.connection(), intent.scopeHandle(), intent.sessionProposal(), intent.scope(),
                intent.correctionRevision(), intent.capturedWorldTick(), rawEye, intent.observation(),
                completion.producer(), intent.nativeWrite());
    }

    private boolean retainBlockedPhysicalResizeIntent(Session session, Owned owner,
            PlayerPhysicalSnapshot accepted, Optional<NativeResizeProposal> consumed, long worldTick) {
        if (consumed.isEmpty()) return false;
        var intent = consumed.orElseThrow();
        if (intent.eyeProducer() == null || intent.installation() != null
                || intent.ownerIdentity() != owner || intent.ownerSnapshot() != accepted
                || worldTick < intent.capturedWorldTick() || worldTick - intent.capturedWorldTick() > 1) return false;
        var producer = nativePoseEyeProfile(session.player);
        boolean physical = matchesPhysicalGeometry(session.player, accepted);
        if (!intent.eyeProducer().equals(producer) || !physical || !current(session)
                || session.owner != owner || owner.snapshot != accepted || owner.teleportPending
                || session.world != intent.world() || session.connectionHandle != intent.connection()
                || session.scopeHandle != intent.scopeHandle() || session.proposal != intent.sessionProposal()
                || owner.travel.correctionRevision() != intent.correctionRevision()) return false;
        owner.trustedFreeResizeProposal = new NativeResizeProposal(owner, accepted, accepted, null,
                intent.world(), intent.connection(), intent.scopeHandle(), intent.sessionProposal(), intent.scope(),
                intent.correctionRevision(), intent.capturedWorldTick(), intent.anchorRawEyeHeight(),
                intent.observation(), intent.eyeProducer(), intent.nativeWrite());
        return true;
    }

    private static void discardUnpromotedTrustedResizeProposal(Owned owner,
            PlayerPhysicalSnapshot source) {
        NativeResizeProposal proposal = owner.trustedFreeResizeProposal;
        if (proposal != null && proposal.ownerIdentity() == owner && proposal.ownerSnapshot() == source)
            owner.trustedFreeResizeProposal = null;
    }

    /**
     * Exact pending native request shared with the internal floor sampler across its package boundary.
     * This read-only token is neither an eye setter nor Free authority; every observation must revalidate it.
     */
    public final class PendingCardinalPose {
        private final Session session;
        private final Owned owner;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final Object connection;
        private final Object scopeHandle;
        private final long correction;
        private final long proposal;
        private final PlayerBodyShape.Dimensions retained;
        private final PlayerBodyShape.Dimensions desired;
        private NativePoseProfile profile;
        private RuntimeException failure;

        private PendingCardinalPose(Session session, Owned owner, PlayerBodyShape.Dimensions retained,
                PlayerBodyShape.Dimensions desired) {
            this.session = session; this.owner = owner; this.scope = session.scope;
            connection = session.connectionHandle; scopeHandle = session.scopeHandle;
            correction = owner.travel.correctionRevision(); proposal = session.proposal;
            this.retained = retained; this.desired = desired;
            profile = NativePoseProfile.read(session.player);
        }

        public boolean current() {
            return PlayerPhysicsServer.this.current(session) && session.owner == owner
                    && owner.pendingCardinalPose == this && session.scope.equals(scope)
                    && session.connectionHandle == connection && session.scopeHandle == scopeHandle
                    && session.proposal == proposal && owner.travel.correctionRevision() == correction
                    && session.player.connection == session.handler && session.player.world == session.world
                    && !owner.teleportPending && !owner.nativeDelegated && !owner.recoveryPending
                    && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal;
        }

        boolean nativeProfileCurrent() {
            if (!current()) return false;
            boolean matches = profile.equals(NativePoseProfile.read(session.player));
            return matches && current() && Float.floatToIntBits(session.player.eyeHeight) == profile.rawEye();
        }

        /**
         * Movement owns these two flags. Advancing them does not re-author a blocked resize:
         * its retained/desired geometry, eye producer, scale and lifecycle must stay exact.
         * Aqua's subsequent pose callback still has to produce and admit any new dimensions.
         */
        void applyOwnedFlags(EntityPlayerMP actor, boolean sneaking, boolean clearGliding) {
            if (actor != session.player || !nativeProfileCurrent())
                throw new IllegalStateException("Pending pose is stale before owned flag update: " + diagnostic(actor));
            NativePoseProfile expected = new NativePoseProfile(profile.rawEye(), sneaking,
                    profile.sleeping(), clearGliding ? false : profile.gliding(),
                    profile.widthScale(), profile.heightScale());
            actor.setSneaking(sneaking);
            if (clearGliding) actor.clearElytraFlying();
            NativePoseProfile observed = NativePoseProfile.read(actor);
            if (!current() || !expected.equals(observed))
                throw new IllegalStateException("Owned pose flag update changed another native producer: expected="
                        + expected + "; " + diagnostic(actor));
            profile = expected;
        }

        String diagnostic(EntityPlayerMP actor) {
            return "scope=" + scope + "; ownerRevision=" + owner.snapshot.revision()
                    + "; current=" + current() + "; expectedProfile=" + profile
                    + "; observedProfile=" + NativePoseProfile.read(actor)
                    + "; retained=" + retained + "; desired=" + desired
                    + "; observedWidth=" + actor.width + "; observedHeight=" + actor.height
                    + "; observedEffectiveEye=" + actor.getEyeHeight();
        }

        boolean allowsInstallationEye(EntityPlayerMP actor, PlayerPhysicalSnapshot.Scope candidateScope,
                PlayerBodyPlacement placement, int observedEye) {
            return actor == session.player && scope.equals(candidateScope)
                    && placement.shape() instanceof PlayerBodyShape.Cardinal
                    && (placement.shape().dimensions().equals(retained) || placement.shape().dimensions().equals(desired))
                    && observedEye == Float.floatToIntBits((float) desired.eyeHeight()) && nativeProfileCurrent();
        }

        /** Only the exact retained Cardinal source can hand its pending native eye to a proved prone body. */
        boolean allowsProneSuccessor(EntityPlayerMP actor, PlayerPhysicalSnapshot source,
                PlayerBodyPlacement target, int observedEye) {
            if (actor != session.player || source != owner.snapshot || !scope.equals(source.scope())
                    || !(source.placement().shape() instanceof PlayerBodyShape.Cardinal)
                    || !source.placement().shape().dimensions().equals(retained)
                    || !(target.shape() instanceof PlayerBodyShape.FreeCapsule free) || free.pronePose().isEmpty()) return false;
            var dimensions = free.dimensions();
            return Float.floatToIntBits((float) dimensions.width()) == Float.floatToIntBits((float) retained.width())
                    && Float.floatToIntBits((float) dimensions.height()) == Float.floatToIntBits((float) retained.height())
                    && observedEye == Float.floatToIntBits((float) desired.eyeHeight())
                    && observedEye == Float.floatToIntBits((float) dimensions.eyeHeight())
                    && nativeProfileCurrent();
        }

        boolean allowsEye(EntityPlayerMP actor, PlayerPhysicalSnapshot.Scope candidateScope,
                PlayerBodyPlacement placement, int observedEye) {
            if (actor != session.player || !scope.equals(candidateScope) || !current()
                    || !(placement.shape() instanceof PlayerBodyShape.Cardinal)
                    || !placement.shape().dimensions().equals(retained)
                    || observedEye != Float.floatToIntBits((float) desired.eyeHeight())) return false;
            return nativeProfileCurrent();
        }

        public boolean matchesSnapshot(EntityPlayerMP actor, PlayerPhysicalSnapshot snapshot, float observedEye) {
            return (snapshot.revision() == owner.snapshot.revision()
                    || snapshot.revision() == Math.incrementExact(owner.snapshot.revision()))
                    && allowsEye(actor, snapshot.scope(), snapshot.placement(), Float.floatToIntBits(observedEye))
                    && matchesPhysicalAnchorGeometry(actor, snapshot) && current();
        }
    }

    private record NativePoseProfile(int rawEye, boolean sneaking, boolean sleeping, boolean gliding,
                                     long widthScale, long heightScale) {
        static NativePoseProfile read(EntityPlayerMP actor) {
            var width = actor.getAttributeMap().getAttributeInstanceByName("artemislib.entityWidth");
            var height = actor.getAttributeMap().getAttributeInstanceByName("artemislib.entityHeight");
            return new NativePoseProfile(Float.floatToIntBits(actor.eyeHeight), actor.isSneaking(),
                    actor.isPlayerSleeping(), actor.isElytraFlying(),
                    Double.doubleToLongBits(width == null ? 1 : width.getAttributeValue()),
                    Double.doubleToLongBits(height == null ? 1 : height.getAttributeValue()));
        }
    }

    private PendingCardinalPose pendingCardinalPose(EntityPlayerMP player) {
        if (player.connection == null) return null;
        Session session = sessions.get(player.connection.netManager);
        if (session == null || session.player != player || session.owner == null) return null;
        PendingCardinalPose pending = session.owner.pendingCardinalPose;
        return pending != null && pending.current() ? pending : null;
    }

    private record PoseContext(PlayerPhysicalSnapshot snapshot, long correction, long tick) { }
    private record NativePoseEntry(WorldCollisionCandidates.ActorGeometry geometry,
            float rawEyeHeight, float effectiveEyeHeight, double motionX, double motionY, double motionZ,
            boolean onGround, boolean collided, boolean collidedHorizontally, boolean collidedVertically,
            float fallDistance, PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle,
            PlayerPhysicsInboundConnections.ScopeHandle scopeHandle, long proposal, NativePoseEyeProducer.Profile eyeProducer,
            PlayerBodyReference.OwnerReference reference, PlayerBodyReference.ManualIntent manual,
            PlayerPhysicsProtocol.BodyReferenceReceipt receipt) { }
    private record PoseGrowth(Vec3d position, Aabb bounds, float width, float height) { }
    private record NativeResizeProposal(Owned ownerIdentity, PlayerPhysicalSnapshot ownerSnapshot,
            PlayerPhysicalSnapshot anchorSnapshot, FreeOwnerTransaction.NativeInstallation installation,
            WorldServer world, PlayerPhysicsInboundConnections.ConnectionHandle connection,
            PlayerPhysicsInboundConnections.ScopeHandle scopeHandle, long sessionProposal,
            PlayerPhysicalSnapshot.Scope scope, long correctionRevision, long capturedWorldTick,
            float anchorRawEyeHeight, ModeTransitionPrepared.TrustedResizeObservation observation,
            NativePoseEyeProducer.Profile eyeProducer, boolean nativeWrite) { }

    /** Only the exact Aqua growth call site may reserve this movement inside an anchored pose callback. */
    public synchronized void runPoseMove(EntityPlayerMP player, MoverType type, Vec3d delta, Consumer<Vec3d> original) {
        Objects.requireNonNull(original, "original");
        if (!drivesMotion(player)) { original.accept(delta); return; }
        var session = sessions.get(player.connection.netManager);
        var owner = session.owner;
        var context = owner.poseContext;
        if (context == null || owner.poseDepth == 0) { original.accept(delta); return; }
        if (!poseContextCurrent(session, owner, context)) return;
        if (owner.poseGrowth != null || owner.budget.snapshot().pending())
            throw new IllegalStateException("Pose growth cannot open a second movement reservation");
        var before = WorldCollisionCandidates.ActorGeometry.read(player);
        var box = player.getEntityBoundingBox();
        float width = (float) (box.maxX - box.minX);
        float height = (float) (box.maxY - box.minY);
        double expected = (double) (player.width - width);
        if (type != MoverType.SELF || !(width > player.width) || delta.x() != expected
                || delta.y() != 0 || delta.z() != expected
                || box.maxX != box.minX + (double) width || box.maxY != box.minY + (double) height
                || box.maxZ != box.minZ + (double) width)
            throw new IllegalArgumentException("Unrecognized Aqua growth movement geometry");
        var previous = context.snapshot();
        var shape = (PlayerBodyShape.Cardinal) previous.placement().shape();
        var dimensions = new PlayerBodyShape.Dimensions(width, height, player.getEyeHeight());
        if (!poseContextCurrent(session, owner, context)
                || !before.equals(WorldCollisionCandidates.ActorGeometry.read(player))) return;
        var base = previous.placement().proposeDimensionsAtFeet(dimensions);
        PlayerBodyPlacement candidate;
        Vec3d worldDelta;
        if (shape.frame() == CardinalFrame.Y_POS) {
            var center = before.bounds().center();
            candidate = PlayerBodyPlacement.fromCardinalBounds(before.bounds(),
                    new Vec3d(center.x(), before.bounds().min().y(), center.z()), previous.placement().attitude(),
                    new PlayerBodyShape.Cardinal(shape.frame(), dimensions));
            worldDelta = delta;
        } else {
            // Aqua preserves the old upright min corner before moving. In a physical frame,
            // the corresponding half-width offset and requested move are both local tangents.
            double halfGrowth = ((double) width - before.width()) / 2;
            var shift = shape.frame().toWorld(new Vec3d(halfGrowth, 0, halfGrowth));
            var bounds = base.bounds();
            var shifted = new Aabb(bounds.min().add(shift), bounds.max().add(shift));
            candidate = PlayerBodyPlacement.fromCardinalBounds(shifted,
                    new Vec3d(shifted.center().x(), shifted.min().y(), shifted.center().z()),
                    previous.placement().attitude(), new PlayerBodyShape.Cardinal(shape.frame(), dimensions));
            worldDelta = shape.frame().toWorld(delta);
            installPoseGeometry(player, candidate);
            var anchor = base.transport();
            owner.budget.rebase(new Vec3(anchor.x(), anchor.y(), anchor.z()), owner.budgetTick);
        }
        var destination = candidate.transport().add(worldDelta);
        var admission = owner.budget.prepareClipped(Math.incrementExact(owner.budget.lastSequence()), owner.budgetTick,
                new Vec3(destination.x(), destination.y(), destination.z()));
        if (!admission.accepted()) throw new IllegalStateException("Pose growth budget: " + admission.failure());
        Vec3d bounded = new Vec3d(admission.destination().x(), admission.destination().y(), admission.destination().z())
                .subtract(candidate.transport());
        RuntimeException primary = null;
        CardinalMoveFinalizer.Ready finalizer = null;
        try {
            if (shape.frame() == CardinalFrame.Y_POS) original.accept(bounded);
            else {
                var sample = new PlayerPhysicalSnapshot(previous.scope(), previous.revision(), candidate, previous.worldVelocity());
                var request = new CardinalMovementResolver.Request(shape.frame(), player.getEntityBoundingBox(), bounded,
                        type, player.onGround, player.isSneaking(), player.stepHeight);
                var observation = WorldCollisionCandidates.resolveCardinal(session.world, player, sample, request, POSE_SCAN, POSE_MOVE);
                if (observation.worldFailure().isPresent()) {
                    var failure = observation.worldFailure().orElseThrow();
                    throw new IllegalStateException("Pose growth collision: " + failure.reason(), failure.cause().orElse(null));
                }
                if (!(observation.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved resolved)
                        || !(CardinalMoveFinalizer.preparePhysical(player, request, resolved) instanceof CardinalMoveFinalizer.Ready ready))
                    throw new IllegalStateException("Pose growth collision did not commit");
                finalizer = ready;
                if (!(ready.commit() instanceof CardinalMoveFinalizer.Applied))
                    throw new IllegalStateException("Pose growth collision did not commit");
            }
        } catch (RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                if (poseContextCurrent(session, owner, context)) {
                    var actual = WorldCollisionCandidates.ActorGeometry.read(player);
                    var point = actual.position();
                    boolean installed = shape.frame() == CardinalFrame.Y_POS
                            ? point.x() == actual.bounds().center().x() && point.y() == actual.bounds().min().y()
                            && point.z() == actual.bounds().center().z()
                            && withinNativeGrowth(box, actual.bounds(), bounded, player.stepHeight)
                            : finalizer != null && finalizer.installation().filter(value ->
                            value.transport().equals(point) && value.bounds().equals(player.getEntityBoundingBox())).isPresent();
                    if (installed) {
                        if (!owner.budget.commitNative(admission, new Vec3(point.x(), point.y(), point.z())))
                            throw new IllegalStateException("Pose growth lost its reserved movement ticket");
                        owner.poseGrowth = new PoseGrowth(point, actual.bounds(), width, height);
                    }
                }
            } catch (RuntimeException recoveryFailure) {
                if (primary == null) throw recoveryFailure;
                if (recoveryFailure != primary) primary.addSuppressed(recoveryFailure);
            } finally { owner.budget.abort(admission); }
        }
    }

    private static boolean withinNativeGrowth(AxisAlignedBB start, Aabb end, Vec3d delta, float stepHeight) {
        // Native collision may shorten each tangent, or take its bounded step path. A block
        // callback's unrelated setPosition is not a completion of this reserved growth move.
        return end.min().x() >= Math.min(start.minX, start.minX + delta.x())
                && end.min().x() <= Math.max(start.minX, start.minX + delta.x())
                && end.min().z() >= Math.min(start.minZ, start.minZ + delta.z())
                && end.min().z() <= Math.max(start.minZ, start.minZ + delta.z())
                && end.min().y() >= start.minY && end.min().y() <= start.minY + stepHeight;
    }

    private boolean poseContextCurrent(Session session, Owned owner, PoseContext context) {
        return current(session) && session.owner == owner && owner.poseContext == context
                && owner.snapshot == context.snapshot() && !owner.teleportPending
                && owner.travel.correctionRevision() == context.correction()
                && session.world.getTotalWorldTime() == context.tick()
                && !PlayerKinematics.requiresNative(session.player);
    }

    private void installPoseGeometry(EntityPlayerMP player, PlayerBodyPlacement placement) {
        var dimensions = placement.shape().dimensions();
        var bounds = placement.bounds();
        var transport = placement.transport();
        player.width = (float) dimensions.width();
        player.height = (float) dimensions.height();
        PendingCardinalPose pending = pendingCardinalPose(player);
        if (pending == null) FreeOwnerTransaction.installEffectiveEyeHeight(player, dimensions.eyeHeight());
        else FreeOwnerTransaction.installEffectiveEyeHeight(player, dimensions.eyeHeight(), () -> {
            if (!pending.current()) throw new IllegalStateException("Pending Cardinal pose changed during eye installation");
        }, pending, pending.scope, placement);
        player.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        if (pending != null && !pending.current())
            throw new IllegalStateException("Pending Cardinal pose changed during bounds installation");
        player.posX = transport.x();
        player.posY = transport.y();
        player.posZ = transport.z();
    }

    /**
     * Restore the physical owner anchor after a trusted Free native callback without asking a
     * fixed native profile to represent the stored eye height. The raw eye field is an exact
     * callback-entry snapshot; the profile getter is checked only after the old collider is back.
     */
    /** Successful raw producer input; physical placement authority remains the exact old anchor. */
    private record RawEyeInputReceipt(PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, NativePoseEntry entry,
            long correction, long tick, NativePoseEyeProducer.Profile producer, float producedRawEye) { }

    private void restoreTrustedFreeAnchorGeometry(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry, NativePoseEyeProducer.Profile observedEyeProfile,
            RawEyeInputReceipt rawEyeInput) {
        EntityPlayerMP player = session.player;
        var placement = anchor.placement();
        var dimensions = placement.shape().dimensions();
        var bounds = placement.bounds();
        var transport = placement.transport();
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "entry");
        if (rawEyeInput != null && (rawEyeInput.ownerSnapshot() != ownerSnapshot
                || rawEyeInput.anchor() != anchor || rawEyeInput.installation() != installation
                || rawEyeInput.entry() != callbackEntry || rawEyeInput.correction() != correction
                || rawEyeInput.tick() != tick || rawEyeInput.producer() != observedEyeProfile
                || Float.floatToRawIntBits(player.eyeHeight)
                    != Float.floatToRawIntBits(rawEyeInput.producedRawEye())))
            throw new IllegalStateException("Native raw eye input lost its exact callback receipt");
        float restoredRawEye = rawEyeInput == null ? callbackEntry.rawEyeHeight()
                : rawEyeInput.producedRawEye();
        player.width = (float) dimensions.width();
        player.height = (float) dimensions.height();
        player.eyeHeight = restoredRawEye;
        player.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "bounding-box setter");
        player.posX = transport.x();
        player.posY = transport.y();
        player.posZ = transport.z();
        player.motionX = callbackEntry.motionX();
        player.motionY = callbackEntry.motionY();
        player.motionZ = callbackEntry.motionZ();
        player.onGround = callbackEntry.onGround();
        player.collided = callbackEntry.collided();
        player.collidedHorizontally = callbackEntry.collidedHorizontally();
        player.collidedVertically = callbackEntry.collidedVertically();
        player.fallDistance = callbackEntry.fallDistance();
        var actualGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "actor geometry getter");
        if (!matchesPhysicalAnchorGeometry(actualGeometry, anchor))
            throw new IllegalStateException("Free native resize observation did not restore its exact physical anchor");
        int expectedRaw = Float.floatToIntBits(restoredRawEye);
        int actualRaw = Float.floatToIntBits(player.eyeHeight);
        if (actualRaw != expectedRaw)
            throw new IllegalStateException("Free native resize observation did not restore its exact raw eye base");
        float actualEffectiveEyeHeight = player.getEyeHeight();
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "effective-eye getter");
        int expectedEffective = Float.floatToIntBits(callbackEntry.effectiveEyeHeight());
        int actualEffective = Float.floatToIntBits(actualEffectiveEyeHeight);
        actualRaw = Float.floatToIntBits(player.eyeHeight);
        if (actualRaw != expectedRaw)
            throw new IllegalStateException("Free native resize observation changed its exact raw eye base");
        boolean observedProfileCurrent = false;
        if (observedEyeProfile != null) {
            var actualProfile = nativePoseEyeProfile(player);
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "restored native eye producer");
            observedProfileCurrent = observedEyeProfile.equals(actualProfile)
                    && actualEffective == Float.floatToIntBits(observedEyeProfile.effectiveEye());
            if (!observedProfileCurrent)
                throw new IllegalStateException("Free native pose eye producer changed while restoring its physical anchor");
            var producerGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
            requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                    correction, tick, callbackEntry, "restored producer physical dependencies");
            if (!matchesPhysicalAnchorGeometry(producerGeometry, anchor)
                    || !nativePoseDynamicsMatch(player, callbackEntry)
                    || Float.floatToIntBits(player.eyeHeight) != expectedRaw)
                throw new IllegalStateException("Native eye producer changed its restored physical dependencies");
        }
        var finalGeometry = WorldCollisionCandidates.ActorGeometry.read(player);
        requireTrustedFreeRestoreFence(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry, "final restored physical dependencies");
        if (!matchesPhysicalAnchorGeometry(finalGeometry, anchor)
                || !nativePoseDynamicsMatch(player, callbackEntry)
                || Float.floatToIntBits(player.eyeHeight) != expectedRaw)
            throw new IllegalStateException("Native pose changed its final restored physical dependencies");
        if (actualEffective != expectedEffective && !observedProfileCurrent)
            throw new IllegalStateException("Free native resize observation changed its anchor profile eye"
                    + " (expected=" + callbackEntry.effectiveEyeHeight() + ", actual=" + actualEffectiveEyeHeight + ")");
    }

    private static NativePoseEyeProducer.Profile nativePoseEyeProfile(EntityPlayerMP player) {
        return player instanceof NativePoseEyeProducer producer
                ? producer.unigrav$nativePoseEyeProfile().orElse(null) : null;
    }

    private static NativePoseEyeProducer.Profile captureNativePoseEyeProfile(EntityPlayerMP player,
            float observedEye) {
        var profile = nativePoseEyeProfile(player);
        if (profile != null && Float.floatToIntBits(profile.effectiveEye()) != Float.floatToIntBits(observedEye))
            throw new IllegalStateException("Native pose eye producer does not explain the controlled callback eye");
        return profile;
    }

    private void requireTrustedFreeRestoreFence(Session session, Owned owner,
            PlayerPhysicalSnapshot ownerSnapshot, PlayerPhysicalSnapshot anchor,
            FreeOwnerTransaction.NativeInstallation installation, long correction, long tick,
            NativePoseEntry callbackEntry, String stage) {
        if (!nativePoseOwnerFenceCurrent(session, owner, ownerSnapshot, anchor, installation,
                correction, tick, callbackEntry))
            throw new IllegalStateException("Free native resize restore stopped after owner fence changed at " + stage);
    }

    /** Native setPlayerLocation already moved the actor and sent its correction before this hook. */
    public synchronized void observeNativeTeleport(NetHandlerPlayServer handler, int teleportId) {
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics correction off-thread");
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || !current(session)) return;
        if (teleportId < 0) throw new IllegalArgumentException("Negative native teleport ID");
        session.pending = null;
        if (session.owner == null) {
            session.lastOfferTick = serverTick - 20;
            return;
        }
        if (recoveryFenced(session.owner)
                && session.owner.recoveryCorrectionConsumedId == teleportId) return;
        session.owner.nativeCardinalMotion = null;
        retireNativeTransferForCorrection(session, session.owner);
        session.owner.recoveryCorrectionAnchor = null;
        session.owner.nativeCorrectionCompletion = null;
        session.owner.nativeCorrectionControl = null;
        session.owner.nativeTeleportId = OptionalInt.of(teleportId);
        session.owner.predictionTimeline.retire();
        session.owner.teleportPending = true;
        session.owner.presentationRelease = null;
        invalidateAttitudeRequest(session.owner);
        session.owner.travel.invalidate();
    }

    /** Re-anchor only after vanilla consumed this exact confirmation. No new owner or budget is created. */
    public void confirmNativeTeleport(NetHandlerPlayServer handler, int teleportId, boolean pendingCleared) {
        var states = new ArrayList<Offer>();
        RuntimeException primary = null;
        String failureStage = null;
        String outboundFailureStage = null;
        Session diagnosticSession = null;
        synchronized (this) {
            if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
                throw new IllegalStateException("Physics confirmation off-thread");
            Session session = sessions.get(handler.netManager);
            if (session == null || session.handler != handler || !current(session) || session.owner == null) return;
            diagnosticSession = session;
            Owned owner = session.owner;
            if (!pendingCleared || !owner.teleportPending || owner.nativeTeleportId.orElse(-1) != teleportId) return;
            if (owner.nativeCorrectionCompletion == null
                    || owner.nativeCorrectionCompletion.teleportId != teleportId)
                owner.nativeCorrectionCompletion = new NativeCorrectionCompletion(session, owner, teleportId, serverTick);
            if (recoveryFenced(owner)) {
                try {
                    prepareRecoveryCorrectionAnchor(session, owner, teleportId);
                    NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
                    if (completion != null && completion.reclassifyPending
                            && nativeCompletionIdentity(completion, true)) owner.teleportPending = false;
                } catch (RuntimeException failure) {
                    NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
                    if (completion != null && nativeCompletionIdentity(completion, true)) {
                        completion.reclassifyPending = true;
                        completion.failure = failure;
                        owner.teleportPending = false;
                    }
                    UnigravMod.LOGGER.error("ugmotion1 recovery correction preparation failed: scope={}, teleportId={}",
                            session.scope, teleportId, failure);
                    throw failure;
                }
                return;
            }
            if (owner.nativeDelegated) {
                try {
                    NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
                    var previous = owner.snapshot;
                    long correction = owner.travel.correctionRevision();
                    var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
                    var finalActual = WorldCollisionCandidates.ActorGeometry.read(session.player);
                    long completionWorldTick = session.world.getTotalWorldTime();
                    boolean live = session.player.isEntityAlive();
                    if (!live || actual.dead() || !actual.id().equals(session.scope.playerId())
                            || actual.dimension() != session.scope.dimension() || !actual.equals(finalActual)
                            || !session.world.isCallingFromMinecraftThread() || !current(session)
                            || session.owner != owner || owner.snapshot != previous || !owner.teleportPending
                            || owner.nativeTeleportId.orElse(-1) != teleportId
                            || owner.travel.correctionRevision() != correction || !owner.nativeDelegated
                            || !nativeCompletionIdentity(completion, true))
                        throw new IllegalStateException("Delegated native correction geometry changed before rebase");
                    owner.budget.rebase(new Vec3(finalActual.position().x(), finalActual.position().y(), finalActual.position().z()),
                            owner.budgetTick);
                    completeNativeCorrectionLifetime(session, owner, correction);
                    owner.teleportPending = false;
                    owner.lastWorldTick = completionWorldTick;
                    // No callback follows the one-time resource completion. The historical
                    // accepted snapshot and vanilla velocity are not a new handoff base.
                    owner.nativeCorrectionControl = new NativeCorrectionControl(
                            completion,
                            PlayerPhysicsProtocol.nativeTransferPacket(
                                    PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE, session.scope,
                                    Math.incrementExact(session.outgoingSequence), session.proposal,
                                    new PlayerPhysicsProtocol.NativeCorrectionCompleted(correction, teleportId)));
                    session.outgoingSequence = owner.nativeCorrectionControl.packet.sequence();
                    queueNativeCorrectionControl(owner.nativeCorrectionControl, states);
                } catch (RuntimeException failure) {
                    primary = failure;
                    failureStage = "rebase delegated correction";
                }
            } else if (owner.holdsNativeGeometry()) {
                var previous = owner.snapshot;
                long correction = owner.travel.correctionRevision();
                try {
                    acceptLockedNativeCorrection(session, owner, states, previous, correction, teleportId);
                } catch (RuntimeException failure) {
                    primary = failure;
                    failureStage = "classify locked native correction";
                    if (current(session) && session.owner == owner && owner.snapshot == previous
                            && owner.teleportPending && owner.nativeTeleportId.orElse(-1) == teleportId
                            && owner.travel.correctionRevision() == correction) {
                        try {
                            recoverNativeCorrection(session, owner, states, failureStage);
                        } catch (RuntimeException recoveryFailure) {
                            if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
                        }
                    }
                }
            } else {
                var previous = owner.snapshot;
                long correction = owner.travel.correctionRevision();
                String stage = "observe geometry";
                var shape = (PlayerBodyShape.Cardinal) previous.placement().shape();
                if (shape.frame() != CardinalFrame.Y_POS) {
                    try {
                        recoverNativeCorrection(session, owner, states, "unsupported non-Y+ physical frame");
                    } catch (RuntimeException failure) {
                        primary = failure;
                        failureStage = "recover unsupported non-Y+ correction";
                    }
                } else try {
                    var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
                    var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), session.player.getEyeHeight());
                    var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                            previous.placement().attitude(), new PlayerBodyShape.Cardinal(shape.frame(), dimensions));
                    var corrected = new PlayerPhysicalSnapshot(previous.scope(), Math.incrementExact(previous.revision()),
                            placement, previous.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(
                            session.player.onGround, session.player.collidedHorizontally, session.player.collidedVertically,
                            session.player.fallDistance)));
                    var finalActual = WorldCollisionCandidates.ActorGeometry.read(session.player);
                    if (actual.dead() || !actual.id().equals(session.scope.playerId())
                            || actual.dimension() != session.scope.dimension())
                        throw new IllegalStateException("Native correction identity changed before commit");
                    if (!actual.equals(finalActual) || !current(session) || session.owner != owner
                            || owner.snapshot != previous || !previous.scope().equals(session.scope)
                            || !owner.teleportPending || owner.nativeTeleportId.orElse(-1) != teleportId
                            || owner.travel.correctionRevision() != correction) return;
                    stage = "commit anchor";
                    owner.budget.rebase(new Vec3(finalActual.position().x(), finalActual.position().y(),
                            finalActual.position().z()), owner.budgetTick);
                    completeNativeCorrectionLifetime(session, owner, correction);
                    owner.snapshot = corrected;
                    owner.teleportPending = false;
                    owner.lastWorldTick = session.world.getTotalWorldTime();
                    owner.lastFailure = null;
                    stage = "publish accepted";
                    beginPredictionBootstrap(session, owner);
                    publishAccepted(session, states);
                } catch (RuntimeException failure) {
                    primary = failure;
                    failureStage = stage;
                    // Only this consumed correction can leave WAITING_ACK. A callback's newer
                    // correction or lifetime is never cleared by the failed older operation.
                    if (current(session) && session.owner == owner && owner.snapshot == previous
                            && owner.teleportPending && owner.nativeTeleportId.orElse(-1) == teleportId
                            && owner.travel.correctionRevision() == correction) {
                        try {
                            recoverNativeCorrection(session, owner, states, stage);
                        } catch (RuntimeException recoveryFailure) {
                            if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
                        }
                    }
                }
            }
        }
        for (Offer state : states) {
            try { sendRecoveryOrOrdinary(state); }
            catch (RuntimeException publicationFailure) {
                String stage = "publish " + state.packet().kind();
                if (primary == null) {
                    primary = publicationFailure;
                    failureStage = stage;
                    break;
                }
                if (outboundFailureStage == null) outboundFailureStage = stage;
                if (publicationFailure != primary) primary.addSuppressed(publicationFailure);
                continue;
            }
            if (state.restoreStateOffer() != null) {
                try {
                    recordNativeRestoreStateSent(state.restoreStateOffer());
                } catch (RuntimeException recordFailure) {
                    String stage = "record successful " + state.packet().kind() + " send";
                    if (primary == null) {
                        primary = recordFailure;
                        failureStage = stage;
                        break;
                    }
                    if (outboundFailureStage == null) outboundFailureStage = stage;
                    if (recordFailure != primary) primary.addSuppressed(recordFailure);
                }
            }
        }
        if (primary != null) {
            Session session = Objects.requireNonNull(diagnosticSession, "native correction diagnostic session");
            UnigravMod.LOGGER.error("ugmotion1 native correction failed: stage={}, outboundStage={}, session={}, scope={}, dimension={}, teleportId={}",
                    failureStage, outboundFailureStage == null ? "none" : outboundFailureStage,
                    session.scope.sessionId(), session.scope, session.scope.dimension(), teleportId, primary);
            throw primary;
        }
    }

    private static boolean recoveryFenced(Owned owner) {
        return owner.recoveryPending || owner.recoveryTerminal || owner.recoveryAwaitingApply
                || owner.recoveryAwaitingMirror || owner.recoveryNativeResume;
    }

    private record RecoveryCorrectionAnchor(Session session, Owned owner,
            PlayerPhysicalSnapshot source, PlayerPhysicalSnapshot corrected,
            WorldCollisionCandidates.ActorGeometry actual,
            PlayerPhysicsInboundConnections.ConnectionHandle connection,
            PlayerPhysicsInboundConnections.ScopeHandle scopeHandle, long proposal,
            long correction, int teleportId, PlayerBodyReference.OwnerReference reference,
            PlayerBodyReference.State safeReference) { }

    private boolean recoveryAnchorCurrent(RecoveryCorrectionAnchor anchor, boolean pending) {
        Session session = anchor.session(); Owned owner = anchor.owner();
        return current(session) && session.owner == owner && owner.snapshot == anchor.source()
                && session.player.connection == session.handler && session.player.getServerWorld() == session.world
                && session.connectionHandle == anchor.connection() && session.scopeHandle == anchor.scopeHandle()
                && session.proposal == anchor.proposal() && owner.travel.correctionRevision() == anchor.correction()
                && owner.nativeTeleportId.orElse(-1) == anchor.teleportId()
                && owner.teleportPending == pending && owner.travel.ownerReference() == anchor.reference()
                && sessions.get(session.manager) == session && session.owner == owner
                && session.player.connection == session.handler && session.player.world == session.world;
    }

    /** Pure final fence: no actor callback may run after the last actual geometry observation. */
    private boolean recoveryAnchorPureCurrent(RecoveryCorrectionAnchor anchor, boolean pending) {
        Session session = anchor.session(); Owned owner = anchor.owner();
        return sessions.get(session.manager) == session && session.owner == owner && owner.snapshot == anchor.source()
                && session.player.connection == session.handler && session.player.world == session.world
                && session.player.dimension == session.scope.dimension() && !session.player.isDead
                && session.connectionHandle == anchor.connection() && session.scopeHandle == anchor.scopeHandle()
                && session.proposal == anchor.proposal() && owner.travel.correctionRevision() == anchor.correction()
                && owner.nativeTeleportId.orElse(-1) == anchor.teleportId()
                && owner.teleportPending == pending && owner.travel.ownerReference() == anchor.reference();
    }

    /** A retired owner receives observation authority only while this exact recovery remains current. */
    private EnvironmentObservationWork recoveryObservationWork(Session session, Owned owner,
            RecoveryCorrectionAnchor anchor, boolean pendingTeleport) {
        var snapshot = owner.snapshot; var connection = session.connectionHandle; var scope = session.scopeHandle;
        long proposal = session.proposal, correction = owner.travel.correctionRevision();
        long tick = session.world.getTotalWorldTime();
        var failure = owner.nativeEnvironmentFailure;
        boolean recoveryPending = owner.recoveryPending, recoveryTerminal = owner.recoveryTerminal;
        boolean awaitingApply = owner.recoveryAwaitingApply, awaitingMirror = owner.recoveryAwaitingMirror;
        boolean nativeResume = owner.recoveryNativeResume, restoreActive = owner.recoveryRestoreActive;
        boolean restorePending = owner.nativeRestorePending, restoreTerminal = owner.nativeRestoreTerminalFailure;
        boolean restoreAck = owner.nativeRestoreAwaitingAcknowledgement;
        var recoveryPacket = owner.recoveryPacket; var correctionAnchor = owner.recoveryCorrectionAnchor;
        long recoveryStarted = owner.recoveryStartedTick;
        int consumedTeleport = owner.recoveryCorrectionConsumedId;
        Runnable fence = () -> {
            if (sessions.get(session.manager) != session || session.owner != owner
                    || owner.snapshot != snapshot || session.player.connection != session.handler
                    || session.player.world != session.world || session.player.isDead
                    || session.player.dimension != session.scope.dimension()
                    || session.connectionHandle != connection || session.scopeHandle != scope || session.proposal != proposal
                    || owner.nativeEnvironmentFailure != failure || owner.recoveryPending != recoveryPending
                    || owner.recoveryTerminal != recoveryTerminal || owner.recoveryAwaitingApply != awaitingApply
                    || owner.recoveryAwaitingMirror != awaitingMirror || owner.recoveryNativeResume != nativeResume
                    || owner.recoveryRestoreActive != restoreActive || owner.nativeRestorePending != restorePending
                    || owner.nativeRestoreTerminalFailure != restoreTerminal || owner.nativeRestoreAwaitingAcknowledgement != restoreAck
                    || owner.recoveryPacket != recoveryPacket || owner.recoveryCorrectionAnchor != correctionAnchor
                    || owner.recoveryStartedTick != recoveryStarted || owner.recoveryCorrectionConsumedId != consumedTeleport
                    || owner.travel.correctionRevision() != correction || owner.teleportPending != pendingTeleport
                    || session.world.getTotalWorldTime() != tick
                    || (anchor == null ? !recoveryPending || recoveryTerminal || correctionAnchor != null
                            : !recoveryFenced(owner) || !recoveryAnchorPureCurrent(anchor,pendingTeleport)))
                throw new IllegalStateException("Recovery observation lost its fenced owner context");
        };
        return owner.freeResources.acquireRecoveryObservation(owner, session.world, session.scope, correction, fence);
    }
    private boolean freshRecoveryAnchor(RecoveryCorrectionAnchor anchor, boolean pending) {
        if (!recoveryAnchorCurrent(anchor, pending)) return false;
        Session session = anchor.session();
        long tick = session.world.getTotalWorldTime();
        var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
        if (!actual.equals(anchor.actual()) || !recoveryAnchorCurrent(anchor, pending)) return false;
        var occupancy = WorldCollisionCandidates.capture(session.world, session.player,
                anchor.corrected().placement().bounds(), CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, POSE_SCAN,
                recoveryObservationWork(session, anchor.owner(), anchor, pending));
        if (!(occupancy instanceof WorldCollisionCandidates.Snapshot clear) || !clear.boxes().isEmpty()
                || !recoveryAnchorCurrent(anchor, pending)) return false;
        var observed = new PlayerEnvironmentSampler(
                Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder"),
                MagneticResponseRules.builtIn(), 0.5, ActiveBootRules.core()).sample(session.player,
                        anchor.corrected(), intent.observe(session.player).map(GIntentObservation::active), null,
                        recoveryObservationWork(session, anchor.owner(), anchor, pending));
        boolean ownerCurrent = recoveryAnchorCurrent(anchor, pending);
        long finalTick = session.world.getTotalWorldTime();
        var finalActual = WorldCollisionCandidates.ActorGeometry.read(session.player);
        return observed instanceof PlayerEnvironmentSampler.Sample sample && sample.physical() == anchor.corrected()
                && sample.worldTick() == tick && finalTick == tick && actual.equals(finalActual)
                && ownerCurrent && recoveryAnchorPureCurrent(anchor, pending);
    }

    private void prepareRecoveryCorrectionAnchor(Session session, Owned owner, int teleportId) {
        long correction = owner.travel.correctionRevision();
        NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
        boolean pending = owner.teleportPending;
        if (completion == null || !nativeCompletionIdentity(completion, pending)
                || owner.recoveryCorrectionConsumedId == teleportId && !completion.reclassifyPending) return;
        // Consume the native completion once. Subsequent attempts reuse only its exact
        // retained identity and do not repeat confirmation, teleport or correction commit.
        owner.recoveryCorrectionConsumedId = teleportId;
        completion.reclassifyPending = true;
        var source = owner.snapshot;
        var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
        var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), session.player.getEyeHeight());
        var canonical = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        if (actual.dead() || !actual.id().equals(session.scope.playerId())
                || actual.dimension() != session.scope.dimension()) return;
        var corrected = new PlayerPhysicalSnapshot(source.scope(), Math.incrementExact(source.revision()),
                canonical, source.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(
                        session.player.onGround, session.player.collidedHorizontally,
                        session.player.collidedVertically, session.player.fallDistance)));
        var reference = owner.travel.ownerReference();
        var anchor = new RecoveryCorrectionAnchor(session, owner, source, corrected, actual,
                session.connectionHandle, session.scopeHandle, session.proposal, correction, teleportId,
                reference, prepareSafeNativeReference(reference, session.player.rotationYaw,
                        PlayerBodyReference.Transition.STABLE));
        if (!freshRecoveryAnchor(anchor, pending)) return;
        completion.reclassifyPending = false;
        owner.recoveryCorrectionAnchor = anchor;
        owner.teleportPending = false;
        owner.recoveryTerminal = false;
        owner.predictionTimeline.retire();
        owner.recoveryPending = true;
        owner.recoveryAwaitingApply = false;
        owner.recoveryAwaitingMirror = false;
        owner.recoveryApplyReceived = false;
        owner.recoveryPacket = null;
        owner.recoveryStartedTick = serverTick;
    }

    /** Only an exact, same-scope native completion with a canonical Y+ collider can replace a locked baseline. */
    private void acceptLockedNativeCorrection(Session session, Owned owner, ArrayList<Offer> states,
            PlayerPhysicalSnapshot previous, long correction, int teleportId) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
        if (!current(session) || session.owner != owner || owner.snapshot != previous || !owner.teleportPending
                || owner.nativeTeleportId.orElse(-1) != teleportId || owner.travel.correctionRevision() != correction
                || actual.dead() || !actual.id().equals(session.scope.playerId())
                || actual.dimension() != session.scope.dimension()) return;
        var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), session.player.getEyeHeight());
        var canonical = PlayerBodyPlacement.fromTransport(actual.position(), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        if (!canonical.bounds().equals(actual.bounds())
                || !actual.equals(WorldCollisionCandidates.ActorGeometry.read(session.player))) {
            rejectUnclassifiedNativeCorrection(session, owner, states, "native completion is not canonical Y+");
            return;
        }

        PlayerPhysicalSnapshot acceptedY = new PlayerPhysicalSnapshot(previous.scope(),
                Math.incrementExact(previous.revision()), canonical, previous.worldVelocity(),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(session.player.onGround,
                        session.player.collidedHorizontally, session.player.collidedVertically,
                        session.player.fallDistance)));
        if (!current(session) || session.owner != owner || owner.snapshot != previous || !owner.teleportPending
                || owner.nativeTeleportId.orElse(-1) != teleportId || owner.travel.correctionRevision() != correction
                || !actual.equals(WorldCollisionCandidates.ActorGeometry.read(session.player))) return;

        long restoreRequestId = owner.nativeRestoreRequestId > 0 ? owner.nativeRestoreRequestId
                : latestBodyReferenceRequestId(owner);
        boolean requiresNative = PlayerKinematics.requiresNative(session.player);
        boolean explicitUnlock = owner.nativeRestoreExplicitUnlock;
        BodyReferenceLock previousLock = owner.bodyReferenceLock;
        PlayerBodyReference.OwnerReference previousReference = owner.travel.ownerReference();
        PlayerBodyReference.Transition safeTransition = requiresNative
                ? PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING
                : explicitUnlock ? PlayerBodyReference.Transition.MANUAL_CLEARED
                        : previousLock == null ? PlayerBodyReference.Transition.STABLE
                                : PlayerBodyReference.Transition.MANUAL_DEFERRED;
        PlayerBodyReference.State safeNativeReference = prepareSafeNativeReference(previousReference,
                session.player.rotationYaw, safeTransition);
        // Finish every virtual actor read before the pure authority fence. A getter may re-enter
        // teleport confirmation while preserving its return value, so the following checks must
        // still compare the exact owner and correction identities immediately before mutation.
        boolean geometryCurrent = actual.equals(WorldCollisionCandidates.ActorGeometry.read(session.player));
        boolean requiresNativeAfterReads = PlayerKinematics.requiresNative(session.player);
        if (!geometryCurrent
                || !current(session) || session.owner != owner || owner.snapshot != previous
                || !previous.scope().equals(session.scope) || !owner.teleportPending
                || owner.nativeTeleportId.orElse(-1) != teleportId
                || owner.travel.correctionRevision() != correction
                || owner.bodyReferenceLock != previousLock
                || owner.nativeRestoreExplicitUnlock != explicitUnlock
                || owner.travel.ownerReference() != previousReference
                || requiresNativeAfterReads != requiresNative) return;

        if (requiresNative) {
            owner.budget.rebase(new Vec3(actual.position().x(), actual.position().y(), actual.position().z()), owner.budgetTick);
            completeNativeCorrectionLifetime(session, owner, correction);
            owner.snapshot = acceptedY;
            owner.travel.bodyReference(Objects.requireNonNull(safeNativeReference, "safe native reference"));
            owner.nativeRestorePending = false;
            owner.nativeRestoreTerminalFailure = false;
            owner.nativeRestoreAwaitingAcknowledgement = true;
            owner.nativeHandoffPrepared = false;
            owner.nativeRestoreRequestId = restoreRequestId;
            resetNativeRestoreAcknowledgements(owner, acceptedY.revision());
            owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF;
            owner.bodyReferenceReceipt = nativeHandoffReceipt(owner, restoreRequestId);
            owner.teleportPending = false;
            owner.lastWorldTick = session.world.getTotalWorldTime();
            owner.lastFailure = null;
            queueBodyReferenceState(session, owner, states);
            owner.lastRestoreStateWorldTick = session.world.getTotalWorldTime();
            return;
        }

        if (explicitUnlock) {
            owner.budget.rebase(new Vec3(actual.position().x(), actual.position().y(), actual.position().z()), owner.budgetTick);
            completeNativeCorrectionLifetime(session, owner, correction);
            owner.snapshot = acceptedY;
            owner.bodyReferenceLock = null;
            owner.travel.pendingManualIntent(Optional.empty());
            owner.travel.bodyReference(Objects.requireNonNull(safeNativeReference, "explicit unlock reference"));
            owner.bodyReferenceReceipt = receipt(restoreRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, Optional.empty(), Optional.empty(),
                    Optional.empty());
            clearNativeRestore(owner);
        } else {
            owner.budget.rebase(new Vec3(actual.position().x(), actual.position().y(), actual.position().z()), owner.budgetTick);
            completeNativeCorrectionLifetime(session, owner, correction);
            owner.snapshot = acceptedY;
            owner.travel.bodyReference(Objects.requireNonNull(safeNativeReference, "safe native reference"));
            owner.bodyReferenceReceipt = owner.bodyReferenceLock == null
                    ? receipt(restoreRequestId, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    Optional.empty(), Optional.empty(), Optional.empty()) : deferredManualReceipt(owner, Optional.empty());
            clearNativeRestore(owner);
        }
        owner.teleportPending = false;
        owner.lastWorldTick = session.world.getTotalWorldTime();
        owner.lastFailure = null;
        beginPredictionBootstrap(session, owner);
        publishAccepted(session, states);
    }

    private void rejectUnclassifiedNativeCorrection(Session session, Owned owner, ArrayList<Offer> states,
            String reason) {
        long requestId = owner.nativeRestoreRequestId > 0 ? owner.nativeRestoreRequestId
                : latestBodyReferenceRequestId(owner);
        NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
        if (completion != null && nativeCompletionIdentity(completion, owner.teleportPending))
            completion.reclassifyPending = true;
        owner.teleportPending = false;
        owner.lastWorldTick = session.world.getTotalWorldTime();
        owner.lastFailure = "native correction retained: " + reason;
        if (!owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement)
            beginNativeRestore(session, owner, requestId,
                    PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED, false);
        owner.nativeRestorePending = true;
        owner.nativeRestoreAwaitingAcknowledgement = false;
        owner.nativeRestoreAcknowledged = false;
        owner.nativeRestoreTerminalFailure = true;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED;
        owner.bodyReferenceReceipt = receipt(owner, requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
        owner.nativeRestoreRevision = owner.snapshot.revision();
        queueBodyReferenceState(session, owner, states);
        owner.lastRestoreStateWorldTick = session.world.getTotalWorldTime();
    }

    private void recoverNativeCorrection(Session session, Owned owner, ArrayList<Offer> states, String stage) {
        NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
        if (completion == null || !nativeCompletionIdentity(completion, true)) return;
        completion.reclassifyPending = true;
        owner.nativeTransfer = null;
        invalidateAttitudeRequest(owner);
        owner.predictionTimeline.retire();
        owner.controls = IDLE;
        session.pending = null;
        rejectUnclassifiedNativeCorrection(session, owner, states, stage);
    }

    private boolean nativeCompletionIdentity(NativeCorrectionCompletion completion, boolean pending) {
        Session session = completion.session; Owned owner = completion.owner;
        return sessions.get(session.manager) == session && session.owner == owner
                && owner.nativeCorrectionCompletion == completion && owner.snapshot == completion.source
                && session.handler == completion.handler && session.player.connection == completion.handler
                && completion.handler.player == session.player && session.player.world == completion.world
                && session.world == completion.world && session.player.dimension == session.scope.dimension()
                && session.connectionHandle == completion.connection && session.scopeHandle == completion.scopeHandle
                && session.scope.equals(completion.scope) && session.proposal == completion.proposal
                && owner.travel.correctionRevision() == completion.correction
                && owner.nativeTeleportId.orElse(-1) == completion.teleportId
                && owner.teleportPending == pending && !session.player.isDead;
    }

    /** Reclassify the already consumed completion; never replay native teleport or its impulse. */
    private boolean retryNativeCorrectionClassification(Session session, Owned owner, ArrayList<Offer> states) {
        NativeCorrectionCompletion completion = owner.nativeCorrectionCompletion;
        if (completion == null || !completion.reclassifyPending) return false;
        if (!nativeCompletionIdentity(completion, false)) return false;
        if (serverTick >= completion.deadlineTick || serverTick <= completion.lastAttemptTick) return true;
        completion.lastAttemptTick = serverTick;
        try {
            if (recoveryFenced(owner)) {
                // The completion event was consumed once; fresh classification may be retried.
                prepareRecoveryCorrectionAnchor(session, owner, completion.teleportId);
                return true;
            }
            var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
            float eye = session.player.getEyeHeight();
            var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), eye);
            var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            var safeReference = prepareSafeNativeReference(owner.travel.ownerReference(), session.player.rotationYaw,
                    PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING);
            boolean requiresNative = PlayerKinematics.requiresNative(session.player);
            boolean live = current(session) && session.player.isEntityAlive();
            var last = WorldCollisionCandidates.ActorGeometry.read(session.player);
            if (!live || actual.dead() || !actual.equals(last)
                    || Float.floatToIntBits(eye) != Float.floatToIntBits(session.player.getEyeHeight())
                    || !nativeCompletionIdentity(completion, false)) return true;
            long worldTick = session.world.getTotalWorldTime();
            if (!nativeCompletionIdentity(completion, false)) return true;
            var corrected = new PlayerPhysicalSnapshot(completion.source.scope(),
                    Math.incrementExact(completion.source.revision()), placement, completion.source.worldVelocity(),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(session.player.onGround,
                            session.player.collidedHorizontally, session.player.collidedVertically, session.player.fallDistance)));
            owner.budget.rebase(new Vec3(actual.position().x(), actual.position().y(), actual.position().z()), owner.budgetTick);
            completeNativeCorrectionLifetime(session, owner, completion.correction);
            owner.snapshot = corrected;
            owner.travel.bodyReference(safeReference);
            owner.lastWorldTick = worldTick;
            owner.nativeRestorePending = false;
            owner.nativeRestoreTerminalFailure = false;
            owner.nativeRestoreAwaitingAcknowledgement = true;
            owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF;
            owner.nativeHandoffPrepared = false;
            owner.nativeRestoreReturnToOwned = !requiresNative;
            resetNativeRestoreAcknowledgements(owner, corrected.revision());
            owner.bodyReferenceReceipt = nativeHandoffReceipt(owner, owner.nativeRestoreRequestId);
            completion.reclassifyPending = false;
            queueBodyReferenceState(session, owner, states);
            owner.lastRestoreStateWorldTick = worldTick;
        } catch (RuntimeException failure) {
            if (nativeCompletionIdentity(completion, false)) {
                completion.failure = failure;
                owner.lastFailure = "native completion reclassification failed: " + failure;
            }
            UnigravMod.LOGGER.error("ugmotion1 native completion reclassification failed for scope " + completion.scope, failure);
        }
        return true;
    }

    /** Queues J/m for the next owner step; callbacks cannot overwrite an in-flight movement snapshot. */
    public synchronized boolean addImpulse(Entity entity, Vec3 impulseNewtonSeconds) {
        if (!(entity instanceof EntityPlayerMP player) || !drivesMotion(player)) return false;
        Objects.requireNonNull(impulseNewtonSeconds, "impulse");
        if (!finite(impulseNewtonSeconds)) throw new IllegalArgumentException("Non-finite impulse");
        var session = sessions.get(player.connection.netManager);
        Owned owner = session.owner;
        var snapshot = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        var body = Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder").bodyPhysicsOf(player);
        double mass = body == null ? BodyPhysics.estimate(player).massKg() : body.massKg();
        if (!Double.isFinite(mass) || mass <= 0) throw new IllegalArgumentException("Invalid impulse mass");
        boolean live = current(session);
        if (!live || sessions.get(session.manager) != session || session.owner != owner
                || owner.snapshot != snapshot || owner.travel.correctionRevision() != correction
                || player.connection != session.handler || player.world != session.world
                || owner.nativeDelegated || owner.teleportPending)
            throw new IllegalStateException("Impulse collection lost its exact movement custodian");
        Vec3 pending = owner.pendingImpulse.add(impulseNewtonSeconds.scale(1 / mass));
        if (!finite(pending)) throw new IllegalArgumentException("Impulse accumulation overflow");
        owner.pendingImpulse = pending;
        return true;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(Math.hypot(Math.hypot(vector.x(), vector.y()), vector.z()));
    }

    /** Native velocity deltas are blocks/tick, not force or impulse in newton-seconds. */
    public synchronized boolean captureNativeVelocityDelta(Entity entity, Vec3 blocksPerTick) {
        if (!(entity instanceof EntityPlayerMP player) || !drivesMotion(player)) return false;
        Objects.requireNonNull(blocksPerTick, "native velocity delta");
        if (!finite(blocksPerTick)) throw new IllegalArgumentException("Non-finite native velocity delta");
        var owner = sessions.get(player.connection.netManager).owner;
        Vec3 pending = owner.pendingImpulse.add(blocksPerTick.scale(GravityEntityBinder.TICKS_PER_SECOND));
        if (!finite(pending)) throw new IllegalArgumentException("Native velocity accumulation overflow");
        owner.pendingImpulse = pending;
        return true;
    }

    private static final class InitializationDeferred extends RuntimeException {
        private InitializationDeferred(String reason) { super(reason); }
        private InitializationDeferred(String reason, RuntimeException cause) { super(reason, cause); }
    }

    /** First-owner target-only proof. It is deliberately separate from ordinary same-centre transitions. */
    private InitializationInstallation prepareInitialization(Session session,
            PlayerPosePersistence.InitializationHistory history, NativePlayerMovement.CenterWindow window,
            NativePlayerMovement.Receipt receipt, Vec3d velocity) {
        if (initializingPose != null) throw new IllegalStateException("Nested first-owner pose initialization");
        var body = NativeCardinalBodyProfile.sample(session.player);
        if (body.isEmpty()) throw new InitializationDeferred("current-full-body-producer-unqualified");
        if (!(session.player instanceof ProneBodyProducer producer))
            throw new InitializationDeferred("current-standing-eye-producer-unavailable");
        var input = producer.unigrav$proneBodyInput();
        if (input.isEmpty()) throw new InitializationDeferred("current-standing-eye-input-unavailable");
        var profile = body.orElseThrow();
        for (var sample : window.samples()) {
            if (sample.source().profile().filter(profile.profile()::equals).isEmpty())
                throw new InitializationDeferred("completed-centre-samples-do-not-match-qualified-body-producer");
        }
        var transport = receipt.placement().transport();
        var staged = new Owned(new BoostMovementBudget(ordinaryCeiling(), serverTick,
                new Vec3(transport.x(), transport.y(), transport.z())), serverTick,
                session.world.getTotalWorldTime(), nativeNoGravity(session.player, session.player.hasNoGravity()));
        var resources = staged.freeResources.acquire(staged, session.world, session.scope, staged.travel.correctionRevision());
        var prepared = new InitializationInstallation(session, history, window, receipt, velocity,
                profile, producer, input.orElseThrow(), staged, resources);
        prepared.requireNativeSource();
        var saved = history.saved();
        prepared.selectTarget();
        prepared.requireNativeSource();
        if (!StoredRealizationLimits.sameRaw(prepared.target.transport(), transport))
            throw new IllegalStateException("First-owner rebuilt pose changed the actual native transport");
        // Equal physical provenance must reconstruct the saved centre exactly; history is not a fit proof.
        if (saved.physical().filter(profile.physical()::equals).isPresent()
                && saved.attitude().equals(prepared.target.attitude())
                && !StoredRealizationLimits.sameRaw(prepared.target.center(), saved.center()))
            throw new IllegalStateException("First-owner unchanged physical profile reconstructed a different stored centre");
        prepared.staged.initializeReference(prepared.target, session.player.rotationYaw);
        if (prepared.target.shape() instanceof PlayerBodyShape.Cardinal cardinal) {
            var forward = prepared.target.attitude().basisZ().multiply(-1);
            var tangent = forward.subtract(cardinal.frame().up().multiply(forward.dot(cardinal.frame().up())));
            double magnitude = Math.sqrt(tangent.lengthSquared());
            if (!(magnitude > 0)) throw new IllegalStateException("Restored Cardinal attitude has no tangent heading");
            prepared.staged.travel.bodyReference(PlayerBodyReference.State.initial(cardinal.frame(), tangent.multiply(1 / magnitude)));
        }
        prepared.journal = new PlayerPhysicalSnapshot(session.scope, 0, prepared.target, velocity,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, session.player.fallDistance)));
        return prepared;
    }

    /** Unforgeable local installation journal, never exposed as a session owner before readback. */
    final class InitializationInstallation {
        private final Session session;
        private final PlayerPosePersistence.InitializationHistory history;
        private final NativePlayerMovement.CenterWindow window;
        private final NativePlayerMovement.Receipt receipt;
        private final NativeCardinalBodyProfile.Value body;
        private final ProneBodyProducer producer;
        private final ProneBodyProducer.Input input;
        private final Owned staged;
        private final FreeTickResources resources;
        private final PlayerPhysicsProtocol.Packet pending;
        private final long proposal, worldTick;
        private final GIntentObservation source;
        private final Vec3d velocity;
        private final WorldCollisionCandidates.ActorGeometry sourceGeometry;
        private WorldCollisionCandidates.ActorGeometry installedGeometry;
        private PlayerBodyPlacement target;
        private PlayerPhysicalSnapshot journal;
        private WorldCollisionCandidates.Snapshot capture;
        private CapsulePlacementMath.Pose environmentPose;
        private CapsuleEnvironmentObservation.Prepared environment;
        private PlayerEnvironmentSampler.InitializationReference initializationReference;
        private Vec3 initializationReferencePoint;
        private CardinalFrame selectionFrame;
        private Optional<CardinalFrame> selectionReference;
        private Optional<PlayerBodyShape.PronePose> selectedPose;
        private boolean mutating, installed, published;

        private InitializationInstallation(Session session, PlayerPosePersistence.InitializationHistory history,
                NativePlayerMovement.CenterWindow window, NativePlayerMovement.Receipt receipt, Vec3d velocity,
                NativeCardinalBodyProfile.Value body, ProneBodyProducer producer, ProneBodyProducer.Input input,
                Owned staged, FreeTickResources resources) {
            this.session = session; this.history = history; this.window = window; this.receipt = receipt;
            this.velocity = velocity; this.body = body; this.producer = producer; this.input = input;
            this.staged = staged; this.resources = resources; pending = session.pending;
            proposal = session.proposal; source = session.source; worldTick = session.world.getTotalWorldTime();
            sourceGeometry = WorldCollisionCandidates.ActorGeometry.read(session.player);
        }

        private void requireBase() {
            history.requireCurrent();
            resources.requireCurrent(staged, session.world, session.scope, staged.travel.correctionRevision());
            if (published || !current(session) || session.owner != null || session.pending != pending
                    || session.proposal != proposal || session.source != source
                    || session.world.getTotalWorldTime() != worldTick || session.player.velocityChanged
                    || (mutating && initializingPose != this))
                throw new IllegalStateException("First-owner initialization lost its exact pending session or installation stage");
        }

        private void requireSources() {
            requireBase();
            boolean valid = !NativeFlags.read(session.player).requiresNative()
                    && body.current() && producer.unigrav$proneBodyInput().filter(input::equals).isPresent()
                    && intent.observe(session.player).filter(source::equals).isPresent();
            requireBase();
            if (!valid) throw new IllegalStateException("First-owner body, eye or intent source changed");
        }

        private void requireNativeSource() {
            if (mutating) throw new IllegalStateException("Native initialization source used after mutation began");
            requireSources();
            var centers = NativePlayerMovement.INSTANCE.acceptedCenters(session.handler);
            var currentReceipt = NativePlayerMovement.INSTANCE.current(session.handler);
            if (centers.isEmpty() || currentReceipt.orElse(null) != receipt
                    || !centers.orElseThrow().equals(window)
                    || !sourceGeometry.equals(WorldCollisionCandidates.ActorGeometry.read(session.player)))
                throw new IllegalStateException("First-owner completed native source or centre window changed");
            requireBase();
        }

        private PlayerBodyPlacement fromTransport(Rotation3d attitude, PlayerBodyShape shape) {
            var numeric = resources.numeric();
            var origin = new PlayerBodyPlacement(new Vec3d(0, 0, 0), attitude, shape, numeric);
            var position = receipt.placement().transport();
            var arithmetic = BootQueryArithmetic.fromSameWork(numeric);
            var center = new Vec3d(position.x(), arithmetic.roundNearest(arithmetic.subtract(
                    arithmetic.exact(position.y()), arithmetic.exact(origin.bounds().min().y()))), position.z());
            var result = new PlayerBodyPlacement(center, attitude, shape, numeric);
            if (!StoredRealizationLimits.sameRaw(result.transport(), position))
                throw new IllegalStateException("Rebuilt initialization body cannot represent the unchanged native transport");
            return result;
        }

        private PlayerBodyPlacement freeTarget(Rotation3d attitude, Optional<PlayerBodyShape.PronePose> prone) {
            var arithmetic = BootQueryArithmetic.fromSameWork(resources.numeric());
            var offset = arithmetic.subtract(arithmetic.exact(input.standingEyeHeight()),
                    arithmetic.multiply(arithmetic.exact(body.physical().length()), arithmetic.exact(.5)));
            var up = attitude.basisY();
            var eye = new Vec3d(arithmetic.roundNearest(arithmetic.multiply(arithmetic.exact(up.x()), offset)),
                    arithmetic.roundNearest(arithmetic.multiply(arithmetic.exact(up.y()), offset)),
                    arithmetic.roundNearest(arithmetic.multiply(arithmetic.exact(up.z()), offset)));
            var anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, new Vec3d(0, 0, 0), eye, attitude);
            var raw = receipt.placement().shape().dimensions();
            return fromTransport(attitude, new PlayerBodyShape.FreeCapsule(raw, body.physical(), Optional.of(anchor), prone));
        }

        private boolean flightSelected() {
            return session.player.capabilities.isFlying || session.player.isElytraFlying()
                    || personalGBackpackControl(session);
        }

        EntityPlayerMP referenceActor() { return session.player; }
        EnvironmentObservationWork referenceWork() { return resources.environmentObservationWork(); }
        Vec3 referencePoint() { return initializationReferencePoint; }
        boolean referenceZeroG() { return source.active(); }
        boolean referenceNativeGravity(Optional<GravityMode> mode) {
            return !source.active() && !staged.travel.nativeNoGravity
                    && mode.filter(value -> value == GravityMode.REPLACE).isEmpty()
                    && !session.player.capabilities.isFlying;
        }
        void requireReferenceCurrent() { requireSources(); }

        private Optional<PlayerBodyShape.PronePose> selectPose(boolean water) {
            // No currently accepted swim state exists at first ownership. Historical SWIMMING is not permission.
            return ProneLocomotion.selectPose(session.player, input, Optional.empty(), selectionReference,
                    flightSelected(), water, session.player.isSprinting());
        }

        private void selectTarget() {
            var saved = history.saved();
            var reference = saved.reference();
            if (reference.isEmpty() && !flightSelected()) {
                var free = freeTarget(saved.attitude(), Optional.empty());
                reference = observeInitializationReference(free);
            }
            // This placeholder is never published as reference when the current field has none.
            selectionReference = reference;
            selectionFrame = reference.orElse(CardinalFrame.Y_POS);
            var look = saved.kind() == SavedPlayerPose.Kind.CRAWLING || saved.kind() == SavedPlayerPose.Kind.SWIMMING
                    ? saved.attitude().basisY() : saved.attitude().basisZ().multiply(-1);
            PlayerBodyPlacement standing = prepareStanding(reference, look);
            selectedPose = selectPose(false);
            record Selection(Optional<CardinalFrame> reference, Optional<PlayerBodyShape.PronePose> pose) { }
            var visited = new LinkedHashSet<Selection>();
            // Finite mode fixed point: each distinct current candidate gets its own full fit and actual-fluid scan.
            while (visited.add(new Selection(reference, selectedPose))) {
                if (selectedPose.isPresent()) {
                    var pose = selectedPose.orElseThrow();
                    boolean sameMode = saved.kind().name().equals(pose.kind().name());
                    var q = sameMode ? saved.attitude() : ProneLocomotion.attitude(look, saved.attitude(), pose,
                            saved.kind() == SavedPlayerPose.Kind.CRAWLING || saved.kind() == SavedPlayerPose.Kind.SWIMMING);
                    target = freeTarget(q, selectedPose);
                } else target = standing == null ? freeTarget(saved.attitude(), Optional.empty()) : standing;
                environmentPose = CapsulePlacementMath.pose(target, resources.numeric());
                var observed = CapsuleEnvironmentObservation.observe(this, session.player, environmentPose,
                        resources.queries(), this::requireSources, FreeNativeEnvironment.serverLoadedChunks(session.world),
                        resources.environmentObservationWork());
                requireNativeSource();
                if (observed instanceof CapsuleEnvironmentObservation.Unavailable failure)
                    throw new InitializationDeferred("current-full-target-fluid-unavailable: " + failure.reason(), failure.cause().orElse(null));
                environment = (CapsuleEnvironmentObservation.Prepared) observed;
                if (saved.reference().isEmpty() && !flightSelected()) {
                    var targetReference = observeInitializationReference(target);
                    if (!targetReference.equals(reference)) {
                        reference = targetReference;
                        selectionReference = reference;
                        selectionFrame = reference.orElse(CardinalFrame.Y_POS);
                        standing = prepareStanding(reference, look);
                        selectedPose = selectPose(environment.inWater());
                        continue;
                    }
                }
                var currentSelection = selectPose(environment.inWater());
                requireNativeSource();
                if (currentSelection.equals(selectedPose)) {
                    if (!fit()) throw new InitializationDeferred("current-full-target-occupied: " + selectedPose);
                    requireEnvironment();
                    return;
                }
                selectedPose = currentSelection;
            }
            throw new InitializationDeferred("current-pose-and-full-body-fluid-selection-not-stable");
        }

        private Optional<CardinalFrame> observeInitializationReference(PlayerBodyPlacement candidate) {
            initializationReferencePoint = new Vec3(candidate.center().x(), candidate.center().y(), candidate.center().z());
            initializationReference = new PlayerEnvironmentSampler(
                    Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder"),
                    MagneticResponseRules.builtIn(), 0.5, ActiveBootRules.core()).sampleInitializationReference(this);
            var reference = initializationReference.frame();
            requireNativeSource();
            return reference;
        }

        private PlayerBodyPlacement prepareStanding(Optional<CardinalFrame> reference, Vec3d look) {
            if (reference.isEmpty() || flightSelected()) return null;
            var saved = history.saved();
            Rotation3d standingQ = saved.attitude();
            if (saved.kind() != SavedPlayerPose.Kind.CARDINAL) {
                var tangent = look.subtract(selectionFrame.up().multiply(look.dot(selectionFrame.up())));
                double magnitude = Math.sqrt(tangent.lengthSquared());
                tangent = magnitude > 0 ? tangent.multiply(1 / magnitude)
                        : PlayerBodyReference.initialHeading(selectionFrame, session.player.rotationYaw);
                var q = PlayerRenderPoseProtocol.Quaternion.fromUpForward(selectionFrame.up(), tangent);
                standingQ = new Rotation3d(q.x(), q.y(), q.z(), q.w());
            }
            var dimensions = new PlayerBodyShape.Dimensions(body.physical().diameter(), body.physical().length(), input.standingEyeHeight());
            target = fromTransport(standingQ, new PlayerBodyShape.Cardinal(selectionFrame, dimensions));
            return target;
        }

        private void requireEnvironment() {
            if (initializationReference != null) {
                initializationReference.requireCurrent();
                var center = target.center();
                if (!initializationReferencePoint.equals(new Vec3(center.x(), center.y(), center.z())))
                    throw new IllegalStateException("First-owner reference was not observed at the final target centre");
            }
            if (environment == null || !environment.matches(this, environmentPose)
                    || !selectPose(environment.inWater()).equals(selectedPose))
                throw new IllegalStateException("First-owner current fluid, pose intent or standing selection changed");
            requireSources();
        }

        private WorldCollisionCandidates.Snapshot captureTarget() {
            var result = WorldCollisionCandidates.capture(session.world, session.player, target.bounds(),
                    target.shape() instanceof PlayerBodyShape.FreeCapsule
                            ? CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT : CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT,
                    POSE_SCAN, resources.environmentObservationWork());
            requireSources();
            if (result instanceof WorldCollisionCandidates.Unavailable failure)
                throw new InitializationDeferred("target-capture-unavailable: " + failure.reason()
                        + "; " + failure.detail(), failure.cause().orElse(null));
            return (WorldCollisionCandidates.Snapshot) result;
        }

        private boolean fit() {
            requireNativeSource();
            capture = captureTarget();
            requireNativeSource();
            if (!capture.actorGeometry().equals(sourceGeometry))
                throw new IllegalStateException("First-owner target capture used a different native actor");
            if (!(target.shape() instanceof PlayerBodyShape.FreeCapsule)) return capture.boxes().isEmpty();
            var capsule = CapsulePlacementMath.pose(target, resources.numeric()).capsule();
            for (var obstacle : capture.boxes()) {
                requireNativeSource();
                var contact = resources.queries().query(capsule, obstacle, FreeOwnerTransaction.ACCURACY, "first-owner-initialization-target");
                requireNativeSource();
                if (contact.relation() == Relation.OVERLAPPING) return false;
                if (contact.relation() != Relation.SEPARATED && contact.relation() != Relation.TOUCHING)
                    throw new IllegalStateException("First-owner full target relation unresolved: " + contact.relation());
            }
            return true;
        }

        private Optional<PlayerPhysicalSnapshot> presentationJournal() {
            if (!mutating || published || journal == null) return Optional.empty();
            requireBase();
            return Optional.of(journal);
        }

        private void requireInstalled() {
            requireSources();
            requireEnvironment();
            var actor = session.player;
            if (!installed || !matchesPhysicalGeometry(actor, journal)
                    || actor.motionX != velocity.x() * GravityEntityBinder.TICK_SECONDS
                    || actor.motionY != velocity.y() * GravityEntityBinder.TICK_SECONDS
                    || actor.motionZ != velocity.z() * GravityEntityBinder.TICK_SECONDS
                    || actor.onGround || actor.collidedHorizontally || actor.collidedVertically
                    || actor.fallDistance != journal.contacts().orElseThrow().fallDistance()
                    || !installedGeometry.equals(WorldCollisionCandidates.ActorGeometry.read(session.player)))
                throw new IllegalStateException("First-owner installed geometry or eye readback changed");
            requireBase();
        }

        WorldCollisionCandidates.ActorGeometry sourceGeometry() { return sourceGeometry; }
        WorldCollisionCandidates.ActorGeometry installedGeometry() { return installedGeometry; }
        void requireInstalledComparison(WorldCollisionCandidates.Snapshot before, WorldCollisionCandidates.Snapshot after) {
            requireInstalled();
            if (before != capture || after == null || !before.footprint().requestedBounds().equals(target.bounds())
                    || !after.footprint().requestedBounds().equals(target.bounds()))
                throw new IllegalStateException("First-owner world bridge is not the prepared target observation");
        }

        private PlayerPhysicalSnapshot install() {
            requireNativeSource();
            requireEnvironment();
            if (journal == null || !capture.sameObservation(captureTarget()))
                throw new IllegalStateException("First-owner target world changed before installation");
            requireNativeSource();
            history.consume();
            initializingPose = this;
            mutating = true;
            try {
                var actor = session.player;
                var dimensions = target.shape().dimensions();
                var bounds = target.bounds();
                requireBase();
                actor.width = (float) dimensions.width();
                actor.height = (float) dimensions.height();
                if (actor instanceof NativePoseEyeProducer.OwnedStandingMirror mirror) {
                    mirror.unigrav$installStandingEye(new NativePoseEyeProducer.StandingEyeWrite(actor,
                            input.standingRawEyeHeight(), (float) dimensions.eyeHeight(), this::requireBase));
                } else FreeOwnerTransaction.installEffectiveEyeHeight(actor, dimensions.eyeHeight());
                requireBase();
                actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
                requireBase();
                // No setPosition or resetPositionToBB: the exact original transport is retained.
                actor.motionX = velocity.x() * GravityEntityBinder.TICK_SECONDS;
                actor.motionY = velocity.y() * GravityEntityBinder.TICK_SECONDS;
                actor.motionZ = velocity.z() * GravityEntityBinder.TICK_SECONDS;
                actor.onGround = false; actor.collidedHorizontally = false; actor.collidedVertically = false;
                installedGeometry = WorldCollisionCandidates.ActorGeometry.read(actor);
                installed = true;
                requireInstalled();
                var after = captureTarget();
                if (!capture.sameInitializationWorld(after, this))
                    throw new IllegalStateException("First-owner target world changed across installation");
                requireInstalled();
                staged.snapshot = journal;
                session.owner = staged;
                published = true;
                initializingPose = null;
                reconcileMobilityControl(session, staged);
                history.requireCurrent();
                resources.requireCurrent(staged, session.world, session.scope, staged.travel.correctionRevision());
                if (!current(session) || session.owner != staged || staged.snapshot != journal
                        || !matchesPhysicalGeometry(actor, journal) || !current(session)
                        || session.owner != staged || staged.snapshot != journal)
                    throw new IllegalStateException("First-owner publication changed during mobility reconciliation");
                UnigravMod.LOGGER.info("UG pose initialization committed: scope={}, savedKind={}, targetShape={}, centre={}, transport={}, velocity={}",
                        session.scope, history.saved().kind(), target.shape(), target.center(), target.transport(), velocity);
                return journal;
            } catch (RuntimeException failure) {
                // A partial first installation has no accepted predecessor to resume. Isolate this connection.
                if (initializingPose == this) initializingPose = null;
                try { if (sessions.get(session.manager) == session) retire(session.manager); }
                catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                try { session.handler.disconnect(new TextComponentString("UG pose initialization failed; see server log")); }
                catch (RuntimeException cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                UnigravMod.LOGGER.error("UG pose initialization failed during installation: scope={}, target={}",
                        session.scope, target.transport(), failure);
                return null;
            } finally {
                if (initializingPose == this) initializingPose = null;
            }
        }
    }

    /**
     * Router-only: both legacy finishers and all packet callbacks have already returned normally.
     */
    void afterLegacyCompletion(NetHandlerPlayServer handler, RemoteBoostServer remote,
                               RemoteBoostServer.CompletedMovement completion) {
        PlayerPhysicsProtocol.Packet owned;
        synchronized (this) {
            Session session = sessions.get(handler.netManager);
            if (session == null || !current(session) || (session.owner != null && !session.owner.nativeDelegated) || session.pending == null
                    || serverTick - session.pendingTick > 2) return;
            boolean resuming = session.owner != null;
            if (resuming && (!session.owner.resumeOffered || session.owner.teleportPending)) return;
            Optional<GIntentObservation> source = intent.observe(session.player);
            if (source.isEmpty() || !source.orElseThrow().equals(session.source)) return;
            boolean remoteSource = source.orElseThrow().source() == GIntentObservation.Source.DEDICATED_MODE
                    || session.pending.application().isPresent();
            if (!remoteSource && remote.hasMovementCustody(handler)) return;
            EntityPlayerMP actor = session.player;
            if (PlayerKinematics.requiresNative(actor) || actor.velocityChanged) return;
            // Keep the native observation/force-mutation fence through installation.
            synchronized (NativePlayerMovement.INSTANCE) {
                var centers = NativePlayerMovement.INSTANCE.acceptedCenters(handler);
                var receipt = NativePlayerMovement.INSTANCE.current(handler);
                if (centers.isEmpty() || receipt.isEmpty()) return;
                var window = centers.orElseThrow();
                var lastReceipt = receipt.orElseThrow();
                if (!window.scope().equals(lastReceipt.scope()) || window.delegateRevision() != lastReceipt.delegateRevision()
                        || window.samples().size() < 2) return;
                var samples = window.samples();
                var before = samples.get(samples.size() - 2);
                var last = samples.getLast();
                long ticks = last.serverTick() - before.serverTick();
                if (ticks < 1 || ticks > 2 || last.serverTick() != session.world.getTotalWorldTime()) return;
                if (!before.source().equals(last.source()) || !last.placement().equals(lastReceipt.placement())) return;
                double vx = (last.center().x() - before.center().x()) * 20.0 / ticks;
                double vy = (last.center().y() - before.center().y()) * 20.0 / ticks;
                double vz = (last.center().z() - before.center().z()) * 20.0 / ticks;
                if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                        || !Double.isFinite(Math.hypot(Math.hypot(vx, vy), vz))) return;
                var snapshot = new PlayerPhysicalSnapshot(session.scope,
                        resuming ? Math.incrementExact(session.owner.snapshot.revision()) : 0,
                        lastReceipt.placement(), new Vec3d(vx, vy, vz),
                        Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                                actor.collidedVertically, actor.fallDistance)));
                var application = session.pending.application();
                boolean initialized = false;
                // Failed observation work cannot be reset by another packet/proposal in this world tick.
                if (session.poseInitializationAttemptTick == session.world.getTotalWorldTime()) return;
                if (!resuming && !remoteSource) {
                    try {
                        var history = PlayerPosePersistence.initializationHistory(actor);
                        if (history.isPresent()) {
                            if (snapshot.worldVelocity().lengthSquared() > ordinaryCeiling() * ordinaryCeiling()) return;
                            session.poseInitializationAttemptTick = session.world.getTotalWorldTime();
                            var installation = prepareInitialization(session, history.orElseThrow(), window, lastReceipt,
                                    snapshot.worldVelocity());
                            snapshot = installation.install();
                            if (snapshot == null) return;
                            initialized = true;
                        }
                    } catch (RuntimeException failure) {
                        session.poseInitializationAttemptTick = session.world.getTotalWorldTime();
                        String reason = failure.getClass().getName() + ": " + failure.getMessage();
                        if (!reason.equals(session.lastPoseInitializationFailure)) {
                            session.lastPoseInitializationFailure = reason;
                            if (failure instanceof InitializationDeferred && failure.getCause() == null)
                                UnigravMod.LOGGER.warn("UG pose initialization deferred: scope={}, nativeReceipt={}, reason={}",
                                        session.scope, lastReceipt.delegateRevision(), failure.getMessage());
                            else UnigravMod.LOGGER.error("UG pose initialization preparation failed: scope={}, nativeReceipt={}",
                                        session.scope, lastReceipt.delegateRevision(), failure);
                        }
                        return;
                    }
                }
                if (resuming) {
                    if (remoteSource || Math.hypot(Math.hypot(vx, vy), vz) > session.owner.budget.ceilingMps()) return;
                    Owned retained = session.owner;
                    var retainedSnapshot = retained.snapshot;
                    long correction = retained.travel.correctionRevision();
                    var retainedReference = retained.travel.ownerReference();
                    var retainedReceipt = retained.bodyReferenceReceipt;
                    var retainedPendingManual = retained.travel.pendingManualIntent().orElse(null);
                    var completedReceipt = retainedReceipt;
                    if (completedReceipt != null
                            && completedReceipt.decision() != PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED
                            && completedReceipt.reason().filter(reason ->
                            reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF
                                    || reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING).isPresent()) {
                        completedReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(
                                completedReceipt.requestId(), completedReceipt.decision(), completedReceipt.acceptedLockRequestId(),
                                completedReceipt.acceptedFrame(), completedReceipt.tangentHeading(), Optional.empty());
                    }
                    var actual = WorldCollisionCandidates.ActorGeometry.read(actor);
                    if (!matchesPhysicalGeometry(actor, snapshot)) return;
                    var finalActual = WorldCollisionCandidates.ActorGeometry.read(actor);
                    var completedReference = retainedReference;
                    if (retainedReference instanceof PlayerBodyReference.CardinalReference cardinalReference
                            && cardinalReference.state().transition() == PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING) {
                        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinalShape)
                                || cardinalReference.state().effective().frame() != cardinalShape.frame()) return;
                        var state = cardinalReference.state();
                        completedReference = new PlayerBodyReference.CardinalReference(
                                state.accepted(state.effective(), PlayerBodyReference.Transition.STABLE));
                    }
                    if (!actual.equals(finalActual) || !current(session) || sessions.get(session.manager) != session
                            || session.owner != retained || retained.snapshot != retainedSnapshot
                            || !retained.nativeDelegated || !retained.resumeOffered || retained.teleportPending
                            || retained.travel.correctionRevision() != correction
                            || retained.travel.ownerReference() != retainedReference
                            || retained.bodyReferenceReceipt != retainedReceipt
                            || retained.travel.pendingManualIntent().orElse(null) != retainedPendingManual) return;
                    retained.freeResources.acquireNativeResume(retained, session.world, session.scope, correction);
                    invalidateAttitudeRequest(retained);
                    var position = snapshot.placement().transport();
                    retained.budget.rebase(new Vec3(position.x(), position.y(), position.z()), retained.budgetTick);
                    retained.snapshot = snapshot;
                    retained.travel.ownerReference(completedReference);
                    retained.bodyReferenceReceipt = completedReceipt;
                    retained.nativeDelegated = false;
                    retained.nativeCorrectionControl = null;
                    retained.resumeOffered = false;
                    retained.nativeTeleportId = OptionalInt.empty();
                    retained.lastWorldTick = session.world.getTotalWorldTime();
                    retained.lastStateTick = serverTick;
                    retained.controls = IDLE;
                    retained.inputWorldTick = Long.MIN_VALUE / 2;
                } else if (remoteSource) {
                    var negotiated = intent.observeNegotiated(actor);
                    if (application.isEmpty() || negotiated.isEmpty()
                            || negotiated.orElseThrow().active() != source.orElseThrow().active()) return;
                    if (preparingRemote != null) throw new IllegalStateException("Nested physical handoff");
                    preparingRemote = new RemoteTransfer(remote, session, snapshot, negotiated.orElseThrow());
                    try {
                        if (!remote.transferCompletedMovement(handler, completion, application.orElseThrow(),
                                before.serverTick(), this)) return;
                    } finally {
                        preparingRemote = null;
                    }
                } else if (!initialized) {
                    double limit = ordinaryCeiling();
                    if (Math.hypot(Math.hypot(vx, vy), vz) > limit) return;
                    Vec3d transport = snapshot.placement().transport();
                    var budget = new BoostMovementBudget(limit, serverTick, new Vec3(transport.x(), transport.y(), transport.z()));
                    session.owner = new Owned(snapshot, budget, serverTick, session.world.getTotalWorldTime(),
                            nativeNoGravity(actor, actor.hasNoGravity()), actor.rotationYaw);
                    reconcileMobilityControl(session, session.owner);
                }
                session.pending = null;
                owned = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, session.scope,
                        Math.incrementExact(session.outgoingSequence), session.proposal, application,
                        Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                        session.owner.travel.cardinalReference());
                session.outgoingSequence = owned.sequence();
                if (session.owner.recoveryNativeResume) {
                    session.owner.recoveryNativeResume = false;
                    session.owner.recoveryAwaitingMirror = true;
                    session.owner.recoveryOwnedSent = false;
                    session.owner.recoveryPacket = owned;
                    session.owner.recoverySends = 1;
                    session.owner.recoveryLastSendTick = serverTick;
                }
                NativePlayerMovement.INSTANCE.invalidate(handler);
            }
        }
        // External effects are after authority installation. Failure must not reactivate legacy or reseed credit.
        try {
            handler.player.setNoGravity(true);
        } catch (RuntimeException cause) {
            synchronized (this) {
                Session session = sessions.get(handler.netManager);
                if (session != null && session.owner != null && current(session))
                    failNativeEnvironment(session, session.owner, cause, "initial OWNED native gravity flag");
            }
            throw cause;
        }
        var observedOwned = prepareOwnedPredictionSeed(handler, owned);
        observedOwned.ifPresent(packet -> sendRecoveryOrOrdinary(new Offer(handler.player, packet)));
    }

    /** One bounded owner observation after adoption/flags, never a movement warm-up or resend hook. */
    private synchronized Optional<PlayerPhysicsProtocol.Packet> prepareOwnedPredictionSeed(
            NetHandlerPlayServer handler, PlayerPhysicsProtocol.Packet owned) {
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || !current(session) || session.owner == null)
            return Optional.empty();
        Owned owner = session.owner;
        PlayerPhysicalSnapshot snapshot = owned.snapshot().orElseThrow();
        if (owner.snapshot != snapshot || !owned.scope().equals(session.scope)
                || owned.offerSequence() != session.proposal) return Optional.empty();
        var connection = session.connectionHandle; var scope = session.scopeHandle;
        long correction = owner.travel.correctionRevision();
        long worldTick = session.world.getTotalWorldTime();
        var source = intent.observe(session.player);
        Runnable requireCurrent = () -> {
            String invalid = !current(session) ? "session-current"
                    : session.owner != owner ? "owner-identity"
                    : owner.snapshot != snapshot ? "snapshot-identity"
                    : session.handler != handler ? "handler-identity"
                    : session.connectionHandle != connection ? "connection-identity"
                    : session.scopeHandle != scope ? "scope-identity"
                    : owner.travel.correctionRevision() != correction ? "correction-revision"
                    : owner.teleportPending ? "teleport-pending"
                    : session.world.getTotalWorldTime() != worldTick ? "world-tick"
                    : !source.equals(intent.observe(session.player)) ? "intent-observation"
                    : !matchesPhysicalGeometry(session.player, snapshot) ? "actor-physical-geometry" : null;
            if (invalid != null)
                throw new IllegalStateException("initial prediction observation lost its adopted owner context"
                        + "; firstInvalid=" + invalid + "; acceptedRevision=" + snapshot.revision()
                        + "; ownerRevision=" + owner.snapshot.revision()
                        + "; expectedPlacement=" + snapshot.placement()
                        + "; actorPosition=(" + session.player.posX + "," + session.player.posY + "," + session.player.posZ + ")"
                        + "; nativeSize=" + session.player.width + "x" + session.player.height
                        + "; rawEyeHeight=" + session.player.eyeHeight
                        + "; recoveryAwaitingApply=" + owner.recoveryAwaitingApply
                        + "; recoveryAwaitingMirror=" + owner.recoveryAwaitingMirror);
        };
        if (movement == null && UnigravMod.entityBinder != null) movement = createMovement();
        CardinalPredictionSeed seed = movement == null
                ? new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, Optional.empty())
                : movement.observePredictionSeed(session.player, snapshot,
                source.map(GIntentObservation::active), owner.travel, owner.budget.ceilingMps(),
                owner.freeResources.acquire(owner, session.world, session.scope, correction), requireCurrent,
                owner.pendingCardinalPose);
        if (seed instanceof CardinalPredictionSeed.Unavailable failure
                && failure.reason() == CardinalPredictionSeed.Reason.SAMPLING_FAILED && failure.cause().isPresent()) {
            failNativeEnvironment(session, owner, failure.cause().orElseThrow(), "initial OWNED prediction environment");
            return Optional.empty();
        }
        try { requireCurrent.run(); }
        catch (RuntimeException cause) {
            failNativeEnvironment(session, owner, cause, "initial OWNED prediction publication");
            return Optional.empty();
        }
        owner.travel.predictionSeed(snapshot, seed);
        if (owner.predictionOwnedSequence != owned.sequence()) {
            beginPredictionBootstrap(session, owner);
            owner.predictionOwnedSequence = owned.sequence();
        }
        var publication = publishedPrediction(session, owner, seed, true);
        if (publication.isEmpty()) return Optional.empty();
        var published = publication.orElseThrow();
        if (!predictionPublicationCurrent(session, owner, published.identity())) return Optional.empty();
        try { requireCurrent.run(); }
        catch (RuntimeException cause) {
            failNativeEnvironment(session, owner, cause, "initial OWNED prediction phase publication");
            return Optional.empty();
        }
        if (!predictionPublicationCurrent(session, owner, published.identity())) return Optional.empty();
        var packet = new PlayerPhysicsProtocol.Packet(owned.kind(), owned.scope(), owned.sequence(),
                owned.offerSequence(), owned.application(), owned.snapshot(), owned.controls(), owned.nativeTeleportId(),
                owned.bodyReferenceReceipt(), owned.bodyReferenceAcknowledgement(), owned.bodyReferenceState(),
                owned.predictionInput(), published.boundary(), Optional.of(seed), Optional.of(published.phase()),
                Optional.empty(), Optional.of(owner.travel.mobilityState(owner.snapshot)));
        if (owner.recoveryPacket == owned) owner.recoveryPacket = packet;
        return Optional.of(packet);
    }

    public synchronized Optional<PlayerMobilityIntent.State> mobilityState(EntityPlayerMP actor) {
        if (actor == null || actor.connection == null) return Optional.empty();
        Session session = sessions.get(actor.connection.netManager);
        if (session == null || session.player != actor || !current(session) || session.owner == null)
            return Optional.empty();
        return Optional.of(session.owner.travel.mobilityState(session.owner.snapshot));
    }

    public synchronized boolean requestMobility(EntityPlayerMP actor, PlayerMobilityIntent.Mode mode,
            PlayerMobilityIntent.Hover hover) {
        Objects.requireNonNull(mode); Objects.requireNonNull(hover);
        if (actor == null || actor.connection == null) return false;
        Session session = sessions.get(actor.connection.netManager);
        if (session == null || session.player != actor || !current(session) || session.owner == null) return false;
        Owned owner = session.owner;
        reconcileMobilityControl(session, owner);
        if (owner.teleportPending || owner.nativeDelegated || owner.recoveryPending || owner.recoveryTerminal) return false;
        long id = owner.travel.mobility().nextRequestId();
        boolean eligible = personalGBackpackControl(session);
        if (!owner.travel.mobility().accept(new PlayerMobilityIntent.Request(id, mode, hover), eligible)) {
            owner.lastStateTick = Long.MIN_VALUE;
            return false;
        }
        if (owner.travel.mobility().blocksCapture()) owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
        owner.lastStateTick = Long.MIN_VALUE;
        return true;
    }

    private static PlayerPhysicsStep createMovement() {
        return new PlayerPhysicsStep(new PlayerEnvironmentSampler(
                Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder"), MagneticResponseRules.builtIn(),
                0.5, ActiveBootRules.core()));
    }

    /** Only callable inside the router's prepared transaction; never returns a budget to a caller. */
    public synchronized boolean installRemoteCompletion(RemoteBoostServer remote, NetHandlerPlayServer handler,
                                                        Optional<BoostMovementBudget> transferredBudget, double carriedCeiling,
                                                        boolean originalNoGravity) {
        RemoteTransfer transfer = preparingRemote;
        if (transfer == null || transfer.remote() != remote || transfer.session().handler != handler) return false;
        Session session = transfer.session();
        Vec3d velocity = transfer.snapshot().worldVelocity();
        Vec3d transport = transfer.snapshot().placement().transport();
        double ceiling = transferredBudget.isPresent() ? carriedCeiling : ordinaryCeiling();
        if (!current(session) || session.owner != null
                || Math.hypot(Math.hypot(velocity.x(), velocity.y()), velocity.z()) > ceiling) return false;
        BoostMovementBudget budget = transferredBudget.orElseGet(() -> new BoostMovementBudget(ceiling,
                serverTick, new Vec3(transport.x(), transport.y(), transport.z())));
        if (budget.snapshot().pending()
                || !budget.position().equals(new Vec3(transport.x(), transport.y(), transport.z()))) return false;
        session.owner = new Owned(transfer.snapshot(), budget, serverTick, session.world.getTotalWorldTime(),
                nativeNoGravity(session.player, originalNoGravity), session.player.rotationYaw);
        session.owner.boostCarrier = transferredBudget.isPresent();
        session.source = transfer.negotiated();
        reconcileMobilityControl(session, session.owner);
        return true;
    }

    private boolean reconcileMobilityControl(Session session, Owned owner) {
        boolean changed = owner.travel.mobility().reconcileControl(personalGBackpackControl(session));
        if (changed) owner.lastStateTick = Long.MIN_VALUE;
        return changed;
    }

    private boolean personalGBackpackControl(Session session) {
        if (session == null || session.player == null || session.world == null
                || session.player.world != session.world || !session.player.interactionManager.isCreative()) return false;
        return intent.observe(session.player).map(GIntentObservation::active).orElse(false);
    }

    private record RemoteTransfer(RemoteBoostServer remote, Session session, PlayerPhysicalSnapshot snapshot,
                                  GIntentObservation negotiated) { }

    private static double ordinaryCeiling() {
        double configured = UnigravConfig.maxEntitySpeed;
        if (!Double.isFinite(configured) || configured < 0)
            throw new IllegalArgumentException("Invalid max_entity_speed");
        return configured == 0 ? 500 : Math.min(configured, 500);
    }

    private static boolean nativeNoGravity(EntityPlayerMP player, boolean observed) {
        return UnigravMod.entityBinder == null ? observed
                : UnigravMod.entityBinder.nativeNoGravityBeforeReplacement(player, observed);
    }

    /**
     * Do not install until the complete motion owner and retirement hooks are available.
     */
    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    @SubscribeEvent
    public synchronized void onConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).connected = true;
    }

    @SubscribeEvent
    public synchronized void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER || !event.getRegistrations().contains(PlayerPhysicsProtocol.CHANNEL)) return;
        var capability = capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability());
        if ("REGISTER".equals(event.getOperation())) capability.registered = true;
        else if ("UNREGISTER".equals(event.getOperation())) capability.retired = true;
    }

    @SubscribeEvent
    public synchronized void onDisconnect(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).retired = true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var offers = new ArrayList<Offer>();
        synchronized (this) {
            serverTick = Math.incrementExact(serverTick);
            for (NetworkManager manager : capabilities.keySet().toArray(NetworkManager[]::new)) {
                var capability = capabilities.get(manager);
                if (capability.retired || !manager.isChannelOpen()) {
                    retire(manager, !manager.isChannelOpen());
                    capabilities.remove(manager);
                    continue;
                }
                if (!channelCurrent(manager) || !(manager.getNetHandler() instanceof NetHandlerPlayServer handler))
                    continue;
                if (!handler.player.getServerWorld().isCallingFromMinecraftThread()) {
                    throw new IllegalStateException("Physics lifecycle off server thread");
                }
                Session session = sessions.get(manager);
                if (session != null && (!sessionOwnerCurrent(session)
                        || session.scopeHandle != null && !current(session))) {
                    retire(manager);
                    session = null;
                }
                if (!actorCurrent(handler, manager)) continue;
                if (session != null && session.scopeHandle == null && !registerSessionScope(session)) continue;
                if (session != null && session.owner != null) {
                    reconcileMobilityControl(session, session.owner);
                    if (retryNativeCorrectionClassification(session, session.owner, offers)) continue;
                    processRecovery(session, offers);
                    session = sessions.get(manager);
                    boolean recoveryBlocksAdvance = session.owner.recoveryPending
                            || session.owner.recoveryAwaitingApply || session.owner.recoveryAwaitingMirror
                            || session.owner.recoveryTerminal;
                    if (recoveryBlocksAdvance) session.owner.controls = IDLE;
                    PlayerPhysicalSnapshot tickAdmissionSource = session.owner.snapshot;
                    if (recoveryBlocksAdvance)
                        traceFreeResize(session, tickAdmissionSource, null, tickAdmissionSource,
                                "TICK_ADMISSION", "RECOVERY", -1, false,
                                session.owner.travel.correctionRevision(),
                                session.owner.trustedFreeResizeProposal != null,
                                UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null,
                                "recovery gates native admission", null);
                    if (session.owner.teleportPending)
                        traceFreeResize(session, tickAdmissionSource, null, tickAdmissionSource,
                                "TICK_ADMISSION", "TELEPORT", -1, false,
                                session.owner.travel.correctionRevision(),
                                session.owner.trustedFreeResizeProposal != null,
                                UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null,
                                "teleport pending before native delegation", null);
                    if (handleNativeDelegation(session, offers)) {
                        session.owner.predictionTimeline.retire();
                        traceFreeResize(session, tickAdmissionSource, null, session.owner.snapshot,
                                "TICK_ADMISSION", "NATIVE_DELEGATION", -1, false,
                                session.owner.travel.correctionRevision(),
                                session.owner.trustedFreeResizeProposal != null,
                                UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null,
                                "handleNativeDelegation returned true", null);
                        continue;
                    }
                    if (session.owner.nativeRestoreTerminalFailure
                            && session.owner.nativeRestoreReason
                            == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED) {
                        long worldTick = session.world.getTotalWorldTime();
                        boolean resendDue = restoreStateResendDue(worldTick, session.owner.lastRestoreStateWorldTick);
                        if (resendDue) {
                            queueBodyReferenceState(session, session.owner, offers);
                            session.owner.nativeRestoreRevision = session.owner.snapshot.revision();
                            session.owner.lastRestoreStateWorldTick = worldTick;
                        }
                        traceFreeResize(session, tickAdmissionSource, null, session.owner.snapshot,
                                "TICK_ADMISSION", "NATIVE_RESTORE_TERMINAL", worldTick, true,
                                session.owner.travel.correctionRevision(),
                                session.owner.trustedFreeResizeProposal != null,
                                UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null,
                                resendDue ? "state resend queued" : "state resend not due", null);
                        continue;
                    }
                    advanceOwned(session, offers);
                    if (!session.owner.nativeDelegated && !session.owner.teleportPending
                            && serverTick - session.owner.lastStateTick >= 20 && matchesAcceptedPlacement(session))
                        publishAccepted(session, offers);
                    continue;
                }
                if (PlayerKinematics.requiresNative(handler.player)) continue;
                Optional<GIntentObservation> source = intent.observe(handler.player);
                if (source.isEmpty()) {
                    if (session != null) {
                        session.pending = null;
                        session.source = null;
                    }
                    continue;
                }
                if (session == null) {
                    worldEpoch = Math.incrementExact(worldEpoch);
                    session = new Session(handler, worldEpoch);
                    sessions.put(manager, session);
                    if (!registerSessionScope(session)) continue;
                }
                boolean sourceChanged = !source.orElseThrow().equals(session.source);
                if (sourceChanged) {
                    session.source = source.orElseThrow();
                    session.pending = null;
                }
                if (session.pending != null && serverTick - session.pendingTick > 2) session.pending = null;
                if (sourceChanged || session.proposal == 0
                        || session.pending == null && serverTick - session.lastOfferTick >= 20) {
                    if (session.owner != null) session.owner.nativeCardinalMotion = null;
                    session.proposal = Math.incrementExact(session.proposal);
                    session.lastOfferTick = serverTick;
                    var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, session.scope,
                            Math.incrementExact(session.outgoingSequence), session.proposal, Optional.empty(), Optional.empty(), Optional.empty());
                    session.outgoingSequence = packet.sequence();
                    offers.add(new Offer(session.player, packet));
                }
            }
        }
        // No external network callback may see a half-mutated registry or hold its monitor.
        for (Offer offer : offers) {
            if (!sendRecoveryOrOrdinary(offer)) continue;
            if (offer.restoreStateOffer() != null) recordNativeRestoreStateSent(offer.restoreStateOffer());
        }
    }

    /** Rotation runs after ingress has settled its lease, retaining the existing movement authority. */
    private void processRecovery(Session session, ArrayList<Offer> offers) {
        Owned owner = session.owner;
        if (owner.recoveryTerminal) return;
        if (owner.recoveryPending) {
            if (serverTick - owner.recoveryStartedTick >= 80) {
                owner.recoveryTerminal = true;
                UnigravMod.LOGGER.error("ugmotion1 recovery timed out before safe scope rotation: {}", session.scope);
                return;
            }
            RecoveryCorrectionAnchor correctionAnchor = owner.recoveryCorrectionAnchor;
            if (correctionAnchor != null && !freshRecoveryAnchor(correctionAnchor, false)) return;
            if (owner.teleportPending || correctionAnchor == null && (owner.nativeRestorePending
                    || owner.nativeRestoreTerminalFailure
                    || PlayerKinematics.requiresNative(session.player) && !owner.nativeRestoreAwaitingAcknowledgement)) return;
            if (correctionAnchor == null && owner.recoveryObservationRequired) {
                // A handshake cannot repair an unsupported callback domain. Require a fresh successful
                // endpoint observation before rotating scope; the existing 80-tick bound still applies.
                var snapshot = owner.snapshot;
                long correction = owner.travel.correctionRevision();
                long observedTick = session.world.getTotalWorldTime();
                var observed = new PlayerEnvironmentSampler(
                        Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder"),
                        MagneticResponseRules.builtIn(), 0.5, ActiveBootRules.core())
                        .sample(session.player, owner.snapshot, intent.observe(session.player).map(GIntentObservation::active),
                                owner.pendingCardinalPose, recoveryObservationWork(session, owner, null, false));
                if (!current(session) || session.owner != owner || owner.snapshot != snapshot
                        || owner.travel.correctionRevision() != correction || owner.teleportPending
                        || session.world.getTotalWorldTime() != observedTick) return;
                if (!(observed instanceof PlayerEnvironmentSampler.Sample sample)
                        || sample.worldTick() != observedTick || !matchesPhysicalGeometry(session.player, snapshot)) return;
            }
            PlayerPhysicalSnapshot recoverySource = correctionAnchor == null ? owner.snapshot : correctionAnchor.corrected();
            Session oldSession = session;
            var rotationSnapshot = owner.snapshot;
            var rotationReference = owner.travel.ownerReference();
            var rotationConnection = oldSession.connectionHandle;
            var rotationScope = oldSession.scopeHandle;
            long rotationProposal = oldSession.proposal, rotationCorrection = owner.travel.correctionRevision();
            var rotationTeleport = owner.nativeTeleportId;
            var environmentFailure = owner.nativeEnvironmentFailure;
            BooleanSupplier rotationCurrent = () -> sessions.get(oldSession.manager) == oldSession
                    && oldSession.owner == owner && owner.snapshot == rotationSnapshot
                    && owner.travel.ownerReference() == rotationReference
                    && owner.travel.correctionRevision() == rotationCorrection
                    && owner.recoveryPending && owner.recoveryCorrectionAnchor == correctionAnchor
                    && oldSession.connectionHandle == rotationConnection && oldSession.scopeHandle == rotationScope
                    && oldSession.proposal == rotationProposal && owner.nativeTeleportId.equals(rotationTeleport)
                    && owner.nativeEnvironmentFailure == environmentFailure
                    && oldSession.player.connection == oldSession.handler
                    && oldSession.player.world == oldSession.world && !oldSession.player.isDead
                    && oldSession.player.dimension == oldSession.scope.dimension() && !owner.teleportPending;
            invalidateAttitudeRequest(owner);
            worldEpoch = Math.incrementExact(worldEpoch);
            session = new Session(oldSession.handler, worldEpoch);
            if (correctionAnchor != null && (!freshRecoveryAnchor(correctionAnchor, false)
                    || session.world != oldSession.world || session.player != oldSession.player)) return;
            if (!rotationCurrent.getAsBoolean()) return;
            FreeTickResources.Store recoveredResources = owner.freeResources.transferRecoveryScope(owner,
                    oldSession.world, oldSession.scope, session.scope, owner.travel.correctionRevision(), () -> {
                        if (!rotationCurrent.getAsBoolean())
                            throw new IllegalStateException("Recovery scope clock observation lost its captured owner context");
                    });
            // Resource transfer reads the real world clock. A reentrant successor cannot be overwritten.
            if (!rotationCurrent.getAsBoolean())
                throw new IllegalStateException("Recovery scope resource transfer lost its captured owner context");
            // Read after every observation callback; retained timelines require the latest monotonic generation.
            session.predictionGeneration = oldSession.predictionGeneration;
            session.owner = owner;
            session.source = oldSession.source;
            if (session.owner != null) session.owner.nativeCardinalMotion = null;
            session.proposal = 1;
            var scope = session.scope;
            PlayerPhysicsInboundConnections.INSTANCE.retireScope(oldSession.scopeHandle);
            oldSession.scopeHandle = null;
            oldSession.dispatcher.invalidate();
            oldSession.pending = null;
            sessions.put(session.manager, session);
            // Only the committed successor can clear this retained owner's old-scope publication identity.
            owner.predictionOwnedSequence = -1;
            owner.snapshot = new PlayerPhysicalSnapshot(scope, recoverySource.revision(),
                    recoverySource.placement(), recoverySource.worldVelocity(), recoverySource.contacts());
            owner.recoveryObservationRequired = false;
            if (correctionAnchor != null) {
                owner.budget.rebase(new Vec3(recoverySource.placement().transport().x(),
                        recoverySource.placement().transport().y(), recoverySource.placement().transport().z()), owner.budgetTick);
                owner.travel.bodyReference(correctionAnchor.safeReference());
                clearNativeRestore(owner);
                owner.recoveryObservationRequired = false;
                owner.recoveryCorrectionAnchor = null;
            }
            // Request IDs are deduplicated within scope; accepted preferences outlive that scope.
            owner.lastBodyReferenceRequestId = 0;
            owner.lastBodyReferenceIntent = null;
            owner.nativeRestoreSentStates.clear();
            owner.nativeRestoreAcknowledged = false;
            owner.recoveryRestoreActive = owner.nativeRestoreAwaitingAcknowledgement;
            owner.recoveryNativeSends = 0;
            owner.nativeTeleportId = OptionalInt.empty();
            session.pending = null;
            owner.freeResources = recoveredResources;
            var installedRecoverySnapshot = owner.snapshot;
            var installedRecoveryReference = owner.travel.ownerReference();
            long installedPredictionGeneration = session.predictionGeneration;
            boolean registered = registerSessionScope(session);
            // Registration observes the actor/connection. A retired callback path cannot alter its successor.
            if (sessions.get(session.manager) != session || session.owner != owner
                    || owner.snapshot != installedRecoverySnapshot || owner.freeResources != recoveredResources
                    || owner.travel.ownerReference() != installedRecoveryReference || !owner.recoveryPending
                    || session.predictionGeneration != installedPredictionGeneration
                    || owner.travel.correctionRevision() != rotationCorrection
                    || session.player.connection != session.handler || session.player.world != session.world
                    || session.player.isDead || session.player.dimension != session.scope.dimension()) return;
            if (!registered) {
                owner.recoveryTerminal = true;
                return;
            }
            owner.recoveryPending = false;
            // The exact new scope retains the same spent frame until the next real world tick.
            owner.recoveryAwaitingApply = !owner.nativeDelegated;
            owner.resumeOffered = false;
            owner.recoverySends = 0;
            owner.recoveryLastSendTick = Long.MIN_VALUE / 2;
            if (owner.nativeDelegated) {
                owner.recoveryNativeResume = true;
                return;
            }
            long offerWireSequence = Math.incrementExact(session.outgoingSequence);
            owner.recoveryPacket = environmentFailure != null && correctionAnchor == null
                    ? PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER, scope,
                            offerWireSequence, session.proposal, new PlayerPhysicsProtocol.ServerEnvironmentRecovery(
                                    oldSession.scope, rotationProposal, rotationTeleport))
                    : new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope,
                            offerWireSequence, session.proposal, Optional.empty(), Optional.empty(), Optional.empty());
            session.outgoingSequence = owner.recoveryPacket.sequence();
        }
        if (owner.recoveryApplyReceived) {
            owner.recoveryApplyReceived = false;
            owner.recoveryAwaitingApply = false;
            owner.recoveryAwaitingMirror = true;
            owner.recoveryOwnedSent = false;
            owner.recoverySends = 0;
            owner.recoveryLastSendTick = Long.MIN_VALUE / 2;
            owner.recoveryPacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED,
                    session.scope, Math.incrementExact(session.outgoingSequence), session.proposal,
                    Optional.empty(), Optional.of(owner.snapshot), Optional.empty(), OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal
                    ? owner.travel.cardinalReference() : Optional.empty());
            session.outgoingSequence = owner.recoveryPacket.sequence();
            owner.recoveryPacket = prepareOwnedPredictionSeed(session.handler, owner.recoveryPacket).orElse(null);
        }
        if (owner.recoveryPacket == null || !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror
                && !owner.recoveryNativeResume) return;
        if (serverTick - owner.recoveryStartedTick >= 80
                || owner.recoverySends >= 3 && serverTick - owner.recoveryLastSendTick >= 20) {
            owner.recoveryTerminal = true;
            UnigravMod.LOGGER.error("ugmotion1 recovery exhausted its bounded handshake: {}", session.scope);
            return;
        }
        if (serverTick - owner.recoveryLastSendTick >= 20) {
            owner.recoverySends++;
            owner.recoveryLastSendTick = serverTick;
            offers.add(new Offer(session.player, owner.recoveryPacket));
        }
    }

    private boolean sendRecoveryOrOrdinary(Offer offer) {
        if (offer.packet().kind() == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE)
            return sendNativeCorrectionControl(offer);
        if (offer.packet().nativeTransfer().isPresent()
                && (offer.packet().kind() == PlayerPhysicsProtocol.Kind.NATIVE
                || offer.packet().kind() == PlayerPhysicsProtocol.Kind.NATIVE_MOTION))
            return sendNativeTransfer(offer);
        Session session;
        synchronized (this) { session = sessions.get(offer.player().connection.netManager); }
        Owned owner = session == null ? null : session.owner;
        boolean handshake = owner != null && offer.packet() == owner.recoveryPacket;
        boolean restoreState = owner != null && owner.recoveryRestoreActive && offer.restoreStateOffer() != null
                && offer.restoreStateOffer().session() == session && offer.restoreStateOffer().owner() == owner;
        boolean restoreHandoff = owner != null && owner.recoveryRestoreActive && offer.handoffSession() == session
                && offer.handoffOwner() == owner;
        boolean recovery = handshake || restoreState || restoreHandoff;
        if (!recovery) { outbound.accept(offer.player(), offer.packet()); return true; }
        synchronized (this) {
            if (!current(session) || session.owner != owner || owner.recoveryTerminal
                    || handshake && owner.recoveryPacket != offer.packet()) return false;
            if (restoreHandoff) {
                if (owner.recoveryNativeSends >= 3 || serverTick - owner.recoveryStartedTick >= 80) {
                    owner.recoveryTerminal = true;
                    UnigravMod.LOGGER.error("ugmotion1 recovery native handoff exhausted: {}", session.scope);
                    return false;
                }
                if (owner.recoveryNativeSends > 0 && serverTick - owner.recoveryNativeLastSendTick < 20) return false;
                owner.recoveryNativeSends++;
                owner.recoveryNativeLastSendTick = serverTick;
            }
        }
        try {
            outbound.accept(offer.player(), offer.packet());
            synchronized (this) {
                if (current(session) && session.owner == owner && owner.recoveryPacket == offer.packet()
                        && offer.packet().kind() == PlayerPhysicsProtocol.Kind.OWNED) owner.recoveryOwnedSent = true;
            }
            return true;
        } catch (RuntimeException failure) {
            UnigravMod.LOGGER.error("ugmotion1 recovery handshake send failed for scope " + session.scope, failure);
            return false;
        }
    }

    /** Record only STATE packets whose outbound send returned successfully. */
    private synchronized void recordNativeRestoreStateSent(NativeRestoreStateOffer sent) {
        Session session = sent.session();
        Owned owner = sent.owner();
        NativeRestoreStateIdentity identity = sent.identity();
        var packet = sent.packet();
        var snapshot = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        var connection = session.connectionHandle;
        var scopeHandle = session.scopeHandle;
        if (!current(session) || session.owner != owner || !owner.nativeRestoreAwaitingAcknowledgement
                || owner.nativeRestoreTerminalFailure || !session.scope.equals(identity.scope())
                || session.proposal != identity.proposal() || snapshot.revision() != identity.revision()
                || owner.nativeRestoreRevision != identity.revision()
                || packet.kind() != PlayerPhysicsProtocol.Kind.STATE || packet.sequence() != identity.sequence()
                || packet.offerSequence() != identity.proposal() || !packet.scope().equals(identity.scope())
                || packet.snapshot().orElse(null) != snapshot || !PlayerPhysicsProtocol.nativeRestorePurpose(packet)
                || !(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                || shape.frame() != CardinalFrame.Y_POS || !matchesAcceptedGeometry(session)) return;
        // Geometry getters can replace authority; only the exact successful send may acquire ACK authority.
        if (!current(session) || session.owner != owner || owner.snapshot != snapshot
                || owner.travel.correctionRevision() != correction || session.connectionHandle != connection
                || session.scopeHandle != scopeHandle || !session.scope.equals(identity.scope())
                || session.proposal != identity.proposal() || !owner.nativeRestoreAwaitingAcknowledgement
                || owner.nativeRestoreTerminalFailure || owner.nativeRestoreRevision != identity.revision()
                || owner.teleportPending) return;
        owner.nativeRestoreSentStates.add(identity);
        while (owner.nativeRestoreSentStates.size() > MAX_NATIVE_RESTORE_SENT_STATES)
            owner.nativeRestoreSentStates.remove(owner.nativeRestoreSentStates.iterator().next());
        if (owner.nativeRestoreAckDeadlineWorldTick == Long.MIN_VALUE)
            owner.nativeRestoreAckDeadlineWorldTick = Math.addExact(session.world.getTotalWorldTime(),
                    NATIVE_RESTORE_ACK_TIMEOUT_TICKS);
    }

    /** The frozen lifetime is checked on both sides of every callback; this last check is pure. */
    private void retireNativeTransferForCorrection(Session session, Owned owner) {
        NativeTransferPending transfer = owner.nativeTransfer;
        owner.nativeTransferRecoveryFailure = null; owner.nativeTransferRecoveryPending = false;
        owner.nativeTransferRecoveryTerminal = false;
        if (transfer == null) return;
        if (!transfer.released && nativeTransferIdentity(transfer)) {
            var before = owner.snapshot;
            if (!before.worldVelocity().equals(transfer.serverVelocity))
                owner.snapshot = new PlayerPhysicalSnapshot(before.scope(), Math.incrementExact(before.revision()),
                        before.placement(), transfer.serverVelocity, before.contacts());
        }
        owner.nativeTransfer = null;
    }

    private boolean nativeTransferIdentity(NativeTransferPending transfer) {
        Session session = transfer.session;
        Owned owner = transfer.owner;
        return sessions.get(session.manager) == session && session.owner == owner
                && owner.nativeTransfer == transfer && owner.snapshot == transfer.restoreSnapshot
                && session.handler == transfer.handler && session.player.connection == transfer.handler
                && transfer.handler.player == session.player && session.world == transfer.world
                && session.player.world == transfer.world && session.player.dimension == transfer.scope.dimension()
                && session.connectionHandle == transfer.connection && session.scopeHandle == transfer.scopeHandle
                && session.scope.equals(transfer.scope) && session.proposal == transfer.proposal
                && owner.travel.correctionRevision() == transfer.correction
                && owner.nativeTeleportId.equals(transfer.teleportId) && !owner.teleportPending
                && !owner.recoveryPending && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror
                && !owner.recoveryTerminal && !session.player.isDead;
    }

    private boolean nativeTransferCurrent(NativeTransferPending transfer) {
        if (!nativeTransferIdentity(transfer)) return false;
        boolean live = current(transfer.session) && transfer.session.player.isEntityAlive();
        return live && nativeTransferIdentity(transfer);
    }

    /** Mark delivery uncertain before calling transport, including deliver-then-throw transports. */
    private boolean sendNativeTransfer(Offer offer) {
        NativeTransferPending transfer;
        synchronized (this) {
            Session session = offer.handoffSession();
            Owned owner = offer.handoffOwner();
            transfer = owner == null ? null : owner.nativeTransfer;
            if (transfer == null || transfer.session != session || !nativeTransferCurrent(transfer)
                    || offer.packet() != transfer.basePacket
                    && (transfer.outbox == null || offer.packet() != transfer.outbox.packet)) return false;
            transfer.attempted = true;
            if (transfer.outbox != null && offer.packet() == transfer.outbox.packet)
                transfer.outbox.attempted = true;
            transfer.lastAttemptTick = serverTick;
        }
        try {
            outbound.accept(offer.player(), offer.packet());
            return true;
        } catch (RuntimeException failure) {
            synchronized (this) {
                // Never reclaim the old writer: this packet may already have been applied remotely.
                if (nativeTransferIdentity(transfer)) transfer.sendFailure = failure;
            }
            UnigravMod.LOGGER.error("ugmotion1 native transfer send uncertain for scope " + transfer.scope, failure);
            return false;
        }
    }

    private void queueNativeTransfer(NativeTransferPending transfer, PlayerPhysicsProtocol.Packet packet,
            ArrayList<Offer> states) {
        states.add(new Offer(transfer.session.player, packet, transfer.session, transfer.owner));
    }

    private static boolean hasPendingImpulse(Owned owner) {
        return owner.pendingImpulse.x() != 0 || owner.pendingImpulse.y() != 0 || owner.pendingImpulse.z() != 0;
    }

    /** Feedback authorizes recovery only for the actual attempted, unreleased immutable packet. */
    private boolean acceptNativeTransferFailure(Session session, PlayerPhysicsProtocol.Packet packet,
            PlayerPhysicsProtocol.NativeTransferFailure failed) {
        Owned owner = session.owner;
        NativeTransferPending transfer = owner == null ? null : owner.nativeTransfer;
        if (owner == null || owner.nativeTransferRecoveryTerminal) return false;
        if (failed.equals(owner.nativeTransferRecoveryFailure)) {
            // The first feedback already invalidated this transfer. A retry cannot mint a revision.
            session.lastIncomingSequence = packet.sequence();
            return true;
        }
        if (transfer == null || transfer.released || !transfer.attempted || !nativeTransferCurrent(transfer)
                || failed.handoffId() != transfer.base.handoffId()
                || failed.restoreRevision() != transfer.base.restoreRevision()) return false;
        boolean base = failed.motionSequence() == 0 && !transfer.baseAcknowledged
                && failed.failedSequence() == transfer.basePacket.sequence();
        boolean motion = transfer.baseAcknowledged && transfer.outbox != null && transfer.outbox.attempted
                && failed.motionSequence() == transfer.outbox.payload.motionSequence()
                && failed.failedSequence() == transfer.outbox.packet.sequence();
        if (!base && !motion) return false;
        if (owner.nativeTransferRecoveryAttempts >= 3) {
            owner.nativeTransferRecoveryTerminal = true;
            return false;
        }
        owner.nativeTransferRecoveryAttempts++;
        owner.nativeTransferRecoveryFailure = failed;
        owner.nativeTransferRecoveryPending = true;
        session.lastIncomingSequence = packet.sequence();
        return true;
    }

    /** No force, impulse consumption, budget rebase or actor velocity write occurs here. */
    private boolean prepareNativeTransferRecovery(Session session, Owned owner, ArrayList<Offer> states) {
        if (owner.nativeTransferRecoveryTerminal) return true;
        if (!owner.nativeTransferRecoveryPending) return false;
        var transfer = owner.nativeTransfer;
        if (transfer == null || !nativeTransferCurrent(transfer)) return true;
        long worldTick = session.world.getTotalWorldTime();
        if (!nativeTransferCurrent(transfer)) return true;
        boolean geometry = matchesAcceptedGeometry(session);
        if (!geometry || !nativeTransferCurrent(transfer)) return true;
        var before = owner.snapshot;
        var restored = new PlayerPhysicalSnapshot(before.scope(), Math.incrementExact(before.revision()),
                before.placement(), transfer.serverVelocity, before.contacts());
        if (!nativeTransferIdentity(transfer)) return true;
        // Callback-free authority cutover: old ACKs/outboxes lose permission before any send.
        owner.snapshot = restored;
        owner.nativeTransfer = null;
        owner.nativeTransferRecoveryPending = false;
        owner.nativeDelegated = false;
        owner.nativeRestorePending = false;
        owner.nativeRestoreAwaitingAcknowledgement = true;
        owner.nativeRestoreTerminalFailure = false;
        owner.nativeRestoreReturnToOwned = false;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF;
        owner.bodyReferenceReceipt = nativeHandoffReceipt(owner, owner.nativeRestoreRequestId);
        resetNativeRestoreAcknowledgements(owner, restored.revision());
        queueBodyReferenceState(session, owner, states);
        owner.lastRestoreStateWorldTick = worldTick;
        return true;
    }

    /** One tick can emit only one immutable batch; send callbacks accumulate into the next batch. */
    private boolean advanceNativeTransfer(Session session, Owned owner, ArrayList<Offer> states) {
        NativeTransferPending transfer = owner.nativeTransfer;
        if (transfer == null || transfer.released) return false;
        if (!nativeTransferCurrent(transfer)) return true;
        if (!transfer.baseAcknowledged) {
            if (!transfer.attempted || serverTick - transfer.lastAttemptTick >= 20)
                queueNativeTransfer(transfer, transfer.basePacket, states);
            return true;
        }
        if (transfer.outbox != null) {
            if (serverTick - transfer.lastAttemptTick >= 20)
                queueNativeTransfer(transfer, transfer.outbox.packet, states);
            return true;
        }
        if (hasPendingImpulse(owner)) {
            // No callbacks between taking this batch, applying it and assigning its immutable outbox.
            Vec3 pending = owner.pendingImpulse;
            Vec3 currentVelocity = new Vec3(transfer.serverVelocity.x(), transfer.serverVelocity.y(),
                    transfer.serverVelocity.z());
            Vec3 next = boundedImpulseVelocity(currentVelocity, pending, owner.budget.ceilingMps());
            long sequence = Math.incrementExact(transfer.motionSequence);
            var velocity = new Vec3d(next.x(), next.y(), next.z());
            var payload = new PlayerPhysicsProtocol.NativeMotion(transfer.base.handoffId(),
                    transfer.base.restoreRevision(), sequence, velocity);
            long wireSequence = Math.incrementExact(session.outgoingSequence);
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_MOTION,
                    transfer.scope, wireSequence, transfer.proposal, payload);
            if (!nativeTransferIdentity(transfer)) return true;
            session.player.motionX = next.x() * GravityEntityBinder.TICK_SECONDS;
            session.player.motionY = next.y() * GravityEntityBinder.TICK_SECONDS;
            session.player.motionZ = next.z() * GravityEntityBinder.TICK_SECONDS;
            owner.pendingImpulse = Vec3.ZERO;
            transfer.serverVelocity = velocity;
            transfer.motionSequence = sequence;
            session.outgoingSequence = wireSequence;
            transfer.outbox = new NativeMotionOutbox(payload, packet);
            queueNativeTransfer(transfer, packet, states);
            return true;
        }
        // Geometry/current getters can reenter and add a delta. Recheck its queue after them.
        boolean geometry = matchesAcceptedGeometry(session);
        if (!geometry || !nativeTransferCurrent(transfer)) return true;
        NativePlayerMovement.INSTANCE.invalidate(session.handler);
        if (!nativeTransferCurrent(transfer)) return true;
        if (hasPendingImpulse(owner) || transfer.outbox != null || !transfer.baseAcknowledged
                || !nativeTransferIdentity(transfer)) return true;
        // Callback-free ownership cutover. No unassigned queue entry can cross this boundary.
        invalidateAttitudeRequest(owner);
        owner.predictionTimeline.retire();
        owner.nativeCardinalMotion = null;
        owner.nativeDelegated = true;
        owner.recoveryRestoreActive = false;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreAwaitingAcknowledgement = false;
        owner.nativeRestorePending = false;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        owner.resumeOffered = false;
        owner.controls = IDLE;
        session.pending = null;
        owner.lastStateTick = serverTick;
        transfer.released = true;
        owner.nativeTransferRecoveryFailure = null;
        owner.presentationRelease = new PresentationRelease(session.scope, owner.snapshot.revision(), session.proposal);
        return true;
    }

    /** ACK only records application; it cannot release custody within an outbound callback. */
    private boolean acceptNativeTransferAcknowledgement(Session session, PlayerPhysicsProtocol.Packet packet) {
        Owned owner = session.owner;
        NativeTransferPending transfer = owner == null ? null : owner.nativeTransfer;
        if (transfer == null || !nativeTransferCurrent(transfer) || !transfer.attempted) return false;
        var value = packet.nativeTransfer().orElse(null);
        if (value instanceof PlayerPhysicsProtocol.NativeHandoffAcknowledgement ack) {
            if (ack.handoffId() != transfer.base.handoffId()
                    || ack.restoreRevision() != transfer.base.restoreRevision()) return false;
            transfer.baseAcknowledged = true;
        } else if (value instanceof PlayerPhysicsProtocol.NativeMotionAcknowledgement ack) {
            if (ack.handoffId() != transfer.base.handoffId()
                    || ack.restoreRevision() != transfer.base.restoreRevision()) return false;
            if (!transfer.baseAcknowledged || transfer.outbox == null || !transfer.outbox.attempted
                    || ack.motionSequence() != transfer.outbox.payload.motionSequence()) return false;
            transfer.outbox = null;
        } else return false;
        session.lastIncomingSequence = packet.sequence();
        return true;
    }

    /** Snapshot changes invalidate old restore ACKs before any base payload is frozen. */
    private boolean mergeNativeRestoreImpulse(Session session, Owned owner) {
        if (!hasPendingImpulse(owner)) return false;
        var before = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        var connection = session.connectionHandle;
        var scopeHandle = session.scopeHandle;
        var handler = session.handler;
        long proposal = session.proposal;
        boolean geometry = matchesAcceptedGeometry(session);
        if (!geometry || !current(session) || sessions.get(session.manager) != session
                || session.owner != owner || owner.snapshot != before
                || session.connectionHandle != connection || session.scopeHandle != scopeHandle
                || session.handler != handler || session.player.connection != handler || session.player.world != session.world
                || session.proposal != proposal || owner.travel.correctionRevision() != correction
                || owner.teleportPending || owner.nativeTransfer != null)
            throw new IllegalStateException("native restore impulse lost its exact accepted owner");
        Vec3 pending = owner.pendingImpulse;
        if (!hasPendingImpulse(owner)) return false;
        Vec3 velocity = new Vec3(before.worldVelocity().x(), before.worldVelocity().y(), before.worldVelocity().z());
        Vec3 next = boundedImpulseVelocity(velocity, pending, owner.budget.ceilingMps());
        var snapshot = new PlayerPhysicalSnapshot(before.scope(), Math.incrementExact(before.revision()),
                before.placement(), new Vec3d(next.x(), next.y(), next.z()), before.contacts());
        owner.snapshot = snapshot;
        owner.pendingImpulse = Vec3.ZERO;
        session.player.motionX = next.x() * GravityEntityBinder.TICK_SECONDS;
        session.player.motionY = next.y() * GravityEntityBinder.TICK_SECONDS;
        session.player.motionZ = next.z() * GravityEntityBinder.TICK_SECONDS;
        return true;
    }

    private static Vec3 boundedImpulseVelocity(Vec3 velocity, Vec3 delta, double ceiling) {
        Vec3 next = velocity.add(delta);
        double speed = Math.hypot(Math.hypot(next.x(), next.y()), next.z());
        if (!Double.isFinite(speed)) throw new IllegalArgumentException("Impulse velocity overflow");
        return speed > ceiling ? next.scale(ceiling / speed) : next;
    }

    /** Preserve the owner/budget while vanilla handles a kinematic state, then negotiate a fresh cutover. */
    private boolean handleNativeDelegation(Session session, ArrayList<Offer> states) {
        Owned owner = session.owner;
        NativeCorrectionControl control = owner.nativeCorrectionControl;
        if (control != null && !control.acknowledged) {
            if (!nativeCorrectionControlCurrent(control)) return true;
            if (!control.attempted || serverTick - control.lastAttemptTick >= 20)
                queueNativeCorrectionControl(control, states);
            return true;
        }
        if (prepareNativeTransferRecovery(session, owner, states)) return true;
        if (advanceNativeTransfer(session, owner, states)) return true;
        boolean nativeState = PlayerKinematics.requiresNative(session.player);
        if (nativeState && !owner.nativeDelegated) {
            invalidateAttitudeRequest(owner);
            owner.travel.pendingManualIntent(Optional.empty());
        }
        if (nativeState && !owner.nativeDelegated) {
            var gIntent = intent.observe(session.player);
            if (owner.bodyReferenceLock != null && (gIntent.isEmpty() || !gIntent.orElseThrow().active()
                    || !session.player.interactionManager.isCreative())) {
                clearManualForGOff(owner);
            }
            admitOwnedWorldTick(session, owner);
            return restoreLockedBodyForNative(session, owner, states,
                    PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF);
        }
        if (!nativeState && !owner.nativeDelegated) {
            if (owner.nativeRestoreAwaitingAcknowledgement) {
                owner.nativeRestoreReturnToOwned = true;
                return restoreLockedBodyForNative(session, owner, states,
                        PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF);
            }
            return false;
        }
        if (nativeState && (!owner.nativeDelegated || owner.resumeOffered)) {
            invalidateAttitudeRequest(owner);
            owner.delegationProposal = session.proposal;
            owner.predictionTimeline.retire();
            owner.nativeCardinalMotion = null;
            owner.nativeDelegated = true;
            owner.resumeOffered = false;
            session.pending = null;
            owner.controls = IDLE;
            restoreNativeGravity(session);
            NativePlayerMovement.INSTANCE.invalidate(session.handler);
            publishNative(session, states);
        } else if (serverTick - owner.lastStateTick >= 20) publishNative(session, states);
        admitOwnedWorldTick(session, owner);
        if (nativeState || owner.teleportPending) return true;
        if (owner.recoveryTerminal) return true;
        if (owner.recoveryNativeResume && owner.resumeOffered) return true;
        var source = intent.observe(session.player);
        if (source.isEmpty()) { session.pending = null; return true; }
        boolean sourceChanged = !source.orElseThrow().equals(session.source);
        if (session.pending != null && serverTick - session.pendingTick > 2) session.pending = null;
        if (!owner.resumeOffered || sourceChanged || session.pending == null && serverTick - session.lastOfferTick >= 20) {
            invalidateAttitudeRequest(owner);
            session.pending = null;
            session.source = source.orElseThrow();
            if (session.owner != null) session.owner.nativeCardinalMotion = null;
            session.proposal = Math.incrementExact(session.proposal);
            session.lastOfferTick = serverTick;
            owner.resumeOffered = true;
            session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
            var resumePacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,
                    session.scope, session.outgoingSequence, session.proposal, Optional.empty(), Optional.empty(), Optional.empty());
            if (owner.recoveryNativeResume) {
                owner.recoveryPacket = resumePacket;
                owner.recoverySends = 1;
                owner.recoveryLastSendTick = serverTick;
            }
            states.add(new Offer(session.player, resumePacket));
        }
        return true;
    }

    /** Admit the current owner's budget at most once per eligible server/world tick pair. */
    private boolean admitOwnedWorldTick(Session session, Owned owner) {
        if (session.owner != owner || !current(session) || serverTick <= owner.adoptedAtTick
                || owner.teleportPending || owner.recoveryPending || owner.recoveryAwaitingApply
                || owner.recoveryAwaitingMirror || owner.recoveryTerminal) return false;
        long worldTick = session.world.getTotalWorldTime();
        if (worldTick <= owner.lastWorldTick) return false;
        owner.lastWorldTick = worldTick;
        owner.budgetTick = Math.incrementExact(owner.budgetTick);
        owner.budget.advance(owner.budgetTick);
        return true;
    }

    /** Restore only a body lock introduced by this protocol before handing movement back to native code. */
    private boolean restoreLockedBodyForNative(Session session, Owned owner, ArrayList<Offer> states,
                                               PlayerPhysicsProtocol.BodyReferenceReason cause) {
        if (!owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement) {
            long requestId = latestBodyReferenceRequestId(owner);
            beginNativeRestore(session, owner, requestId, cause, false);
        }
        long worldTick = session.world.getTotalWorldTime();

        if (owner.nativeRestoreTerminalFailure) {
            if (restoreStateResendDue(worldTick, owner.lastRestoreStateWorldTick)) {
                queueBodyReferenceState(session, owner, states);
                owner.lastRestoreStateWorldTick = worldTick;
            }
            return true;
        }

        if (owner.nativeRestoreAwaitingAcknowledgement) {
            if (owner.nativeRestoreAcknowledged) {
                if (owner.teleportPending || !(owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                        || shape.frame() != CardinalFrame.Y_POS || !matchesAcceptedGeometry(session)) {
                    owner.nativeRestorePending = true;
                    owner.nativeRestoreAwaitingAcknowledgement = false;
                    owner.nativeRestoreAcknowledged = false;
                    owner.nativeRestoreTerminalFailure = true;
                    owner.nativeRestoreSentStates.clear();
                    owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
                    owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED;
                    owner.bodyReferenceReceipt = receipt(owner, owner.nativeRestoreRequestId,
                            PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                            PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
                    owner.nativeRestoreRevision = owner.snapshot.revision();
                    queueBodyReferenceState(session, owner, states);
                    owner.lastRestoreStateWorldTick = worldTick;
                    return true;
                }
                if (owner.nativeRestoreRevision != owner.snapshot.revision()) {
                    resetNativeRestoreAcknowledgements(owner, owner.snapshot.revision());
                    queueBodyReferenceState(session, owner, states);
                    owner.lastRestoreStateWorldTick = worldTick;
                    return true;
                }
                // A delta received while the restore STATE was in flight belongs to a new
                // accepted revision. The old ACK cannot authorize a different velocity base.
                if (owner.nativeTransferRecoveryFailure == null && mergeNativeRestoreImpulse(session, owner)) {
                    resetNativeRestoreAcknowledgements(owner, owner.snapshot.revision());
                    queueBodyReferenceState(session, owner, states);
                    owner.lastRestoreStateWorldTick = worldTick;
                    return true;
                }
                if (owner.nativeRestoreReturnToOwned && owner.nativeTransferRecoveryFailure == null) {
                    boolean nativeNow = PlayerKinematics.requiresNative(session.player);
                    if (!current(session) || session.owner != owner || owner.teleportPending) return true;
                    if (!nativeNow) {
                        // The acknowledged restore is complete; following owned STATEs have ordinary purpose.
                        var completedReference = owner.travel.ownerReference();
                        if (completedReference instanceof PlayerBodyReference.CardinalReference cardinal
                                && cardinal.state().transition() == PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING
                                && owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal completedShape
                                && cardinal.state().effective().frame() == completedShape.frame()) {
                            completedReference = new PlayerBodyReference.CardinalReference(
                                    cardinal.state().accepted(cardinal.state().effective(), PlayerBodyReference.Transition.STABLE));
                        }
                        var completedReceipt = owner.bodyReferenceReceipt;
                        if (completedReceipt != null
                                && completedReceipt.decision() != PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED
                                && completedReceipt.reason().filter(reason ->
                                reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF
                                        || reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING).isPresent()) {
                            completedReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(
                                    completedReceipt.requestId(), completedReceipt.decision(), completedReceipt.acceptedLockRequestId(),
                                    completedReceipt.acceptedFrame(), completedReceipt.tangentHeading(), Optional.empty());
                        }
                        owner.travel.ownerReference(completedReference);
                        owner.bodyReferenceReceipt = completedReceipt;
                        owner.nativeRestoreReturnToOwned = false;
                        clearNativeRestore(owner);
                        beginPredictionBootstrap(session, owner);
                        return false;
                    }
                    owner.nativeRestoreReturnToOwned = false;
                }
                var restored = owner.snapshot;
                long correction = owner.travel.correctionRevision();
                var connection = session.connectionHandle;
                var scopeHandle = session.scopeHandle;
                var handler = session.handler;
                long proposal = session.proposal;
                if (!owner.nativeHandoffPrepared) {
                    restoreNativeGravity(session);
                    if (!current(session) || session.owner != owner || owner.snapshot != restored
                            || owner.travel.correctionRevision() != correction || owner.teleportPending
                            || session.connectionHandle != connection || session.scopeHandle != scopeHandle
                            || session.handler != handler || session.proposal != proposal) return true;
                    NativePlayerMovement.INSTANCE.invalidate(handler);
                    owner.nativeHandoffPrepared = true;
                }
                if (hasPendingImpulse(owner) && owner.nativeTransferRecoveryFailure == null) {
                    if (mergeNativeRestoreImpulse(session, owner)) {
                        resetNativeRestoreAcknowledgements(owner, owner.snapshot.revision());
                        queueBodyReferenceState(session, owner, states);
                        owner.lastRestoreStateWorldTick = worldTick;
                    }
                    return true;
                }
                if (!current(session) || session.owner != owner || owner.snapshot != restored
                        || owner.travel.correctionRevision() != correction || owner.teleportPending
                        || session.connectionHandle != connection || session.scopeHandle != scopeHandle
                        || session.handler != handler || session.proposal != proposal
                        || !owner.nativeRestoreAcknowledged || owner.nativeRestoreRevision != restored.revision()) return true;
                long handoffId = Math.incrementExact(session.nativeHandoffSerial);
                long wireSequence = Math.incrementExact(session.outgoingSequence);
                var base = new PlayerPhysicsProtocol.NativeHandoff(handoffId, restored.revision(), restored.worldVelocity());
                var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                        session.scope, wireSequence, proposal, base);
                // All callbacks ended. Freeze the base and retain writer custody before send.
                session.nativeHandoffSerial = handoffId;
                session.outgoingSequence = wireSequence;
                owner.delegationProposal = proposal;
                owner.nativeCorrectionControl = null;
                owner.nativeTransfer = new NativeTransferPending(session, owner, base, packet);
                session.player.motionX = base.baseVelocity().x() * GravityEntityBinder.TICK_SECONDS;
                session.player.motionY = base.baseVelocity().y() * GravityEntityBinder.TICK_SECONDS;
                session.player.motionZ = base.baseVelocity().z() * GravityEntityBinder.TICK_SECONDS;
                queueNativeTransfer(owner.nativeTransfer, packet, states);
                return true;
            }
            if (owner.nativeRestoreAckDeadlineWorldTick != Long.MIN_VALUE
                    && worldTick >= owner.nativeRestoreAckDeadlineWorldTick) {
                owner.nativeRestoreTerminalFailure = true;
                owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE;
                owner.nativeRestoreSentStates.clear();
                owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
                owner.bodyReferenceReceipt = receipt(owner, owner.nativeRestoreRequestId,
                        PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                        PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE);
                queueBodyReferenceState(session, owner, states);
                owner.lastRestoreStateWorldTick = worldTick;
                return true;
            }
            if (restoreStateResendDue(worldTick, owner.lastRestoreStateWorldTick)) {
                queueBodyReferenceState(session, owner, states);
                owner.lastRestoreStateWorldTick = worldTick;
            }
            return true;
        }
        if (worldTick >= owner.nativeRestoreDeadlineWorldTick) {
            owner.nativeRestoreTerminalFailure = true;
            owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE;
            owner.bodyReferenceReceipt = receipt(owner, owner.nativeRestoreRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                    PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE);
            queueBodyReferenceState(session, owner, states);
            owner.lastRestoreStateWorldTick = worldTick;
            return true;
        }
        if (worldTick < owner.nativeRestoreNextAttemptWorldTick) return true;
        owner.nativeRestoreNextAttemptWorldTick = Math.addExact(worldTick, 1);

        if (owner.snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            if (!matchesPhysicalAnchorGeometry(session.player, owner.snapshot)) {
                rejectUnclassifiedNativeCorrection(session, owner, states,
                        "Free native restore source anchor changed before proof preparation");
                return true;
            }
            PlayerBodyReference.State safeNativeReference;
            try {
                safeNativeReference = prepareSafeNativeReference(owner.travel.ownerReference(),
                        session.player.rotationYaw, PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING);
            } catch (RuntimeException failure) {
                failNativeEnvironment(session, owner, failure, "Free native restore reference preparation");
                return true;
            }
            return restoreFreeOwnerForNative(session, owner, states, worldTick, safeNativeReference);
        }

        PlayerPhysicalSnapshot restoredSnapshot = null;
        PlayerPhysicsProtocol.BodyReferenceReason rejectedReason = null;
        PlayerBodyReference.State safeNativeReference = null;
        if (!matchesAcceptedGeometry(session)) {
            rejectedReason = PlayerPhysicsProtocol.BodyReferenceReason.GEOMETRY_MISMATCH;
        } else {
            // Prepare before restoreForNative can rebase the shared budget or install geometry.
            safeNativeReference = prepareSafeNativeReference(owner.travel.ownerReference(),
                    session.player.rotationYaw, PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING);
            var shape = (PlayerBodyShape.Cardinal) owner.snapshot.placement().shape();
            if (shape.frame() == CardinalFrame.Y_POS) {
                restoredSnapshot = owner.snapshot;
            } else {
                PlayerPhysicalSnapshot restoreSource = owner.snapshot;
                long correction = owner.travel.correctionRevision();
                var connection = session.connectionHandle;
                var scopeHandle = session.scopeHandle;
                var handler = session.handler;
                long proposal = session.proposal;
                BooleanSupplier restoreCurrent = () -> current(session) && session.owner == owner
                        && owner.snapshot == restoreSource && session.handler == handler
                        && session.player.connection == handler && session.player.world == session.world
                        && session.connectionHandle == connection && session.scopeHandle == scopeHandle
                        && session.proposal == proposal && owner.travel.correctionRevision() == correction
                        && !owner.teleportPending && owner.nativeRestorePending
                        && !owner.recoveryPending && !owner.recoveryAwaitingApply
                        && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal;
                try (var transaction = new FreeOwnerTransaction(session.player, restoreSource, owner.travel,
                        owner.budget, restoreCurrent, owner,
                        owner.freeResources.acquire(owner, session.world, session.scope, correction))) {
                    try {
                        var outcome = CardinalReorientation.restoreForNative(transaction, owner.budget, owner.budgetTick);
                        if (outcome instanceof CardinalReorientation.Installed installed)
                            restoredSnapshot = installed.snapshot();
                        else if (outcome instanceof CardinalReorientation.Recovered recovered)
                            restoredSnapshot = recovered.snapshot();
                        else if (outcome instanceof CardinalReorientation.CommittedFailure failure) {
                            retainFailedInstallationCustody(session, owner, failure.custody());
                            if (current(session) && session.owner == owner && owner.snapshot == restoreSource
                                    && session.connectionHandle == connection && session.scopeHandle == scopeHandle
                                    && session.handler == handler && owner.travel.correctionRevision() == correction
                                    && !owner.teleportPending) {
                                failure.custody().filter(custody -> custody.ownerIdentity() == owner
                                        && custody.actor() == session.player && custody.scope().equals(session.scope)
                                        && custody.connection() == handler && custody.correctionRevision() == correction
                                        && custody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                                        && custody.observedGeometry().filter(observed -> observed.equals(
                                                WorldCollisionCandidates.ActorGeometry.read(session.player))).isPresent()
                                        && matchesPhysicalGeometry(session.player, failure.snapshot()))
                                        .ifPresent(custody -> owner.snapshot = custody.expectedJournal());
                                failNativeEnvironment(session, owner, failure.cause(), "CLEARANCE native restore tail");
                            }
                            return true;
                        } else {
                            var rejected = (CardinalReorientation.Rejected) outcome;
                            if (rejected.cause().isPresent()) throw rejected.cause().orElseThrow();
                            rejectedReason = bodyReferenceReason(rejected.reason());
                        }
                    } catch (RuntimeException failure) {
                        transaction.recordFailure(failure);
                        throw failure;
                    }
                }
            }
        }

        if (restoredSnapshot == null) {
            owner.nativeRestoreReason = Objects.requireNonNull(rejectedReason, "restore rejection reason");
            PlayerBodyReference.OwnerReference sourceReference = owner.travel.ownerReference();
            if (sourceReference instanceof PlayerBodyReference.CardinalReference cardinal)
                owner.travel.bodyReference(cardinal.state().blocked(
                        PlayerBodyReference.Transition.SAFE_NATIVE_ADOPTION_BLOCKED, CardinalFrame.Y_POS,
                        owner.nativeRestoreReason));
            owner.bodyReferenceReceipt = receipt(owner, owner.nativeRestoreRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, owner.nativeRestoreReason);
            if (restoreStateResendDue(worldTick, owner.lastRestoreStateWorldTick)) {
                queueBodyReferenceState(session, owner, states);
                owner.lastRestoreStateWorldTick = worldTick;
            }
            return true;
        }

        owner.snapshot = restoredSnapshot;
        owner.travel.bodyReference(Objects.requireNonNull(safeNativeReference, "safe native reference"));
        owner.nativeRestorePending = false;
        owner.nativeRestoreAwaitingAcknowledgement = true;
        owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF;
        resetNativeRestoreAcknowledgements(owner, restoredSnapshot.revision());
        owner.bodyReferenceReceipt = nativeHandoffReceipt(owner, owner.nativeRestoreRequestId);
        queueBodyReferenceState(session, owner, states);
        owner.lastRestoreStateWorldTick = worldTick;
        owner.lastStateTick = serverTick;
        return true;
    }

    private boolean restoreFreeOwnerForNative(Session session, Owned owner, ArrayList<Offer> states,
            long worldTick, PlayerBodyReference.State safeNativeReference) {
        Objects.requireNonNull(safeNativeReference, "safeNativeReference");
        PlayerBodyReference.CardinalReference targetOwnerReference =
                new PlayerBodyReference.CardinalReference(safeNativeReference);
        PlayerPhysicalSnapshot accepted = owner.snapshot;
        PlayerBodyReference.OwnerReference sourceValue = owner.travel.ownerReference();
        if (!(sourceValue instanceof PlayerBodyReference.FreeReference sourceReference)) {
            rejectUnclassifiedNativeCorrection(session, owner, states,
                    "Free accepted geometry lost its typed Free owner reference");
            return true;
        }
        long correction = owner.travel.correctionRevision();
        PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle = session.connectionHandle;
        PlayerPhysicsInboundConnections.ScopeHandle scopeHandle = session.scopeHandle;
        long proposal = session.proposal;
        long requestId = owner.nativeRestoreRequestId;
        boolean explicitUnlock = owner.nativeRestoreExplicitUnlock;
        PlayerBodyReference.ManualIntent pendingManual = owner.travel.pendingManualIntent().orElse(null);
        FreeTickResources resources;
        try {
            resources = owner.freeResources.acquire(owner, session.world, session.scope, correction);
        } catch (RuntimeException failure) {
            failNativeEnvironment(session, owner, failure, "Free native restore resource acquisition");
            return true;
        }

        Optional<ModeTransitionPrepared.TrustedResizeObservation> callbackProfile;
        try {
            callbackProfile = consumeTrustedFreeResizeProposal(session, owner, accepted, worldTick,
                    correction, connectionHandle, scopeHandle, "NATIVE_RESTORE")
                    .map(NativeResizeProposal::observation);
        } catch (RuntimeException failure) {
            failNativeEnvironment(session, owner, failure, "Free native restore callback profile capture");
            return true;
        }
        if (callbackProfile.isPresent()) {
            ModeTransitionPrepared.TrustedResizeObservation qualified = callbackProfile.orElseThrow();
            WorldCollisionCandidates.ActorGeometry callbackGeometry = qualified.observedGeometry();
            PlayerBodyShape.Dimensions acceptedDimensions = accepted.placement().shape().dimensions();
            PlayerBodyShape.Dimensions callbackDimensions = qualified.dimensions();
            if (!callbackGeometry.position().equals(accepted.placement().transport())
                    || !callbackGeometry.bounds().equals(accepted.placement().bounds())
                    || Float.floatToIntBits(callbackGeometry.width())
                        != Float.floatToIntBits((float) acceptedDimensions.width())
                    || Float.floatToIntBits(callbackGeometry.height())
                        != Float.floatToIntBits((float) acceptedDimensions.height())
                    || Float.floatToIntBits((float) callbackDimensions.width())
                        != Float.floatToIntBits((float) acceptedDimensions.width())
                    || Float.floatToIntBits((float) callbackDimensions.height())
                        != Float.floatToIntBits((float) acceptedDimensions.height())) {
                rejectUnclassifiedNativeCorrection(session, owner, states,
                        "qualified native callback changed the Free source collider before restoration");
                return true;
            }
        }
        NativeRestoreAuthority authority;
        try {
            authority = mintNativeRestoreAuthority(session, owner, accepted, sourceReference, resources,
                    callbackProfile);
        } catch (RuntimeException failure) {
            failNativeEnvironment(session, owner, failure, "Free native restore source capture");
            return true;
        }

        FreeOwnerTransaction[] activeTransaction = new FreeOwnerTransaction[1];
        BooleanSupplier ownerCurrent = () -> authority.transactionOwnerFenceCurrent(
                activeTransaction[0], targetOwnerReference);

        FreeOwnerTransaction transaction = null;
        FreeReferenceModePrepared.Preparation preparation = null;
        FreeReferenceModeFinalizer.Result installed = null;
        Optional<FreeOwnerTransaction.InstallationCustody> custody = Optional.empty();
        RuntimeException failure = null;
        try {
            transaction = new FreeOwnerTransaction(session.player, accepted, owner.travel, owner.budget,
                    ownerCurrent, owner, resources, null, authority);
            activeTransaction[0] = transaction;
            NativeRestoreObservation source = NativeRestoreObservation.capture(transaction, authority);
            var selection = new FreeReferenceModePrepared.Selection(safeNativeReference.effective(),
                    safeNativeReference.manualIntent(), targetOwnerReference, requestId);
            preparation = FreeReferenceModePrepared.prepare(transaction, source, sourceReference,
                    pendingManual, null, selection, new Vec3d(0.0D, 0.0D, 0.0D));
            if (preparation instanceof FreeReferenceModePrepared.Prepared prepared)
                installed = FreeReferenceModeFinalizer.install(prepared, owner.travel, owner.budgetTick);
        } catch (RuntimeException problem) {
            failure = problem;
            if (transaction != null) transaction.recordFailure(problem);
        } finally {
            if (transaction != null) {
                try {
                    custody = transaction.installationCustody();
                } catch (RuntimeException custodyFailure) {
                    if (failure == null) failure = custodyFailure;
                    else if (failure != custodyFailure) failure.addSuppressed(custodyFailure);
                }
                try {
                    transaction.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else if (failure != closeFailure) failure.addSuppressed(closeFailure);
                }
            }
        }

        if (failure != null) {
            retainFailedInstallationCustody(session, owner, custody);
            if (custody.isPresent()) {
                FreeOwnerTransaction.InstallationCustody installedCustody = custody.orElseThrow();
                if (installedCustody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                        && transaction != null && owner.snapshot == accepted
                        && transaction.matchesCommittedOwnerReference(targetOwnerReference)
                        && ownerCurrent.getAsBoolean()
                        && installedCustody.expectedJournal() == transaction.installedJournal().orElse(null)) {
                    owner.snapshot = installedCustody.expectedJournal();
                }
            }
            markNativeRestoreFailure(owner, requestId,
                    PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
            failNativeEnvironment(session, owner, failure, "Free native restore installation");
            return true;
        }

        if (preparation instanceof FreeReferenceModePrepared.Unavailable unavailable) {
            if (unavailable.cause().isPresent()) {
                RuntimeException cause = unavailable.cause().orElseThrow();
                markNativeRestoreFailure(owner, requestId,
                        PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
                failNativeEnvironment(session, owner, cause, "Free native restore preparation");
                return true;
            }
            retainNativeRestoreRejection(session, owner, states, worldTick,
                    unavailable.reason(), unavailable.detail());
            return true;
        }

        if (installed instanceof FreeReferenceModeFinalizer.Unavailable unavailable) {
            if (unavailable.cause().isPresent()) {
                RuntimeException cause = unavailable.cause().orElseThrow();
                markNativeRestoreFailure(owner, requestId,
                        PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
                failNativeEnvironment(session, owner, cause, "Free native restore final recapture");
                return true;
            }
            retainNativeRestoreRejection(session, owner, states, worldTick,
                    unavailable.reason(), unavailable.detail());
            return true;
        }

        if (installed instanceof FreeReferenceModeFinalizer.CommittedFailure committed) {
            Optional<FreeOwnerTransaction.InstallationCustody> committedCustody = committed.custody().isPresent()
                    ? committed.custody() : custody;
            retainFailedInstallationCustody(session, owner, committedCustody);
            if (committedCustody.isPresent()) {
                FreeOwnerTransaction.InstallationCustody installedCustody = committedCustody.orElseThrow();
                if (installedCustody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                        && transaction != null && owner.snapshot == accepted
                        && transaction.matchesCommittedOwnerReference(targetOwnerReference)
                        && ownerCurrent.getAsBoolean()
                        && installedCustody.expectedJournal() == transaction.installedJournal().orElse(null))
                    owner.snapshot = committed.snapshot();
            }
            markNativeRestoreFailure(owner, requestId,
                    PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
            failNativeEnvironment(session, owner, committed.cause(), "Free native restore committed tail");
            return true;
        }

        if (!(installed instanceof FreeReferenceModeFinalizer.Applied applied))
            throw new IllegalStateException("Free native restore completed without a typed finalizer result");
        try {
            if (transaction == null || !(preparation instanceof FreeReferenceModePrepared.Prepared prepared)
                    || owner.snapshot != accepted || !ownerCurrent.getAsBoolean()
                    || !transaction.matchesCommittedOwnerReference(targetOwnerReference)
                    || applied.snapshot().placement() != prepared.targetCardinal()
                    || applied.snapshot().revision() != Math.incrementExact(accepted.revision())
                    || !applied.snapshot().scope().equals(accepted.scope()))
                throw new IllegalStateException("Free native restore lost its exact installed owner before STATE publication");
            owner.snapshot = applied.snapshot();
            owner.nativeRestorePending = false;
            owner.nativeRestoreAwaitingAcknowledgement = true;
            owner.nativeRestoreAcknowledged = false;
            owner.nativeRestoreTerminalFailure = false;
            owner.nativeHandoffPrepared = false;
            owner.nativeRestoreReason = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF;
            resetNativeRestoreAcknowledgements(owner, applied.snapshot().revision());
            owner.bodyReferenceReceipt = nativeHandoffReceipt(owner, requestId);
            queueBodyReferenceState(session, owner, states);
            owner.lastRestoreStateWorldTick = worldTick;
            owner.lastStateTick = serverTick;
            owner.lastFailure = null;
        } catch (RuntimeException publishFailure) {
            retainFailedInstallationCustody(session, owner, custody);
            markNativeRestoreFailure(owner, requestId,
                    PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
            failNativeEnvironment(session, owner, publishFailure, "Free native restore STATE preparation");
        }
        return true;
    }

    private void retainNativeRestoreRejection(Session session, Owned owner, ArrayList<Offer> states,
            long worldTick, PlayerPhysicsProtocol.BodyReferenceReason reason, String detail) {
        try {
            owner.nativeRestoreReason = Objects.requireNonNull(reason, "restore rejection reason");
            owner.bodyReferenceReceipt = receipt(owner, owner.nativeRestoreRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, reason);
            owner.lastFailure = Objects.requireNonNull(detail, "restore rejection detail");
            if (restoreStateResendDue(worldTick, owner.lastRestoreStateWorldTick)) {
                queueBodyReferenceState(session, owner, states);
                owner.lastRestoreStateWorldTick = worldTick;
            }
        } catch (RuntimeException publicationFailure) {
            markNativeRestoreFailure(owner, owner.nativeRestoreRequestId, reason);
            failNativeEnvironment(session, owner, publicationFailure, "Free native restore rejection publication");
        }
    }

    private static void markNativeRestoreFailure(Owned owner, long requestId,
            PlayerPhysicsProtocol.BodyReferenceReason reason) {
        owner.nativeRestorePending = true;
        owner.nativeRestoreAwaitingAcknowledgement = false;
        owner.nativeRestoreAcknowledged = false;
        owner.nativeRestoreTerminalFailure = true;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreReason = reason;
        owner.nativeRestoreRevision = owner.snapshot.revision();
        owner.bodyReferenceReceipt = receipt(owner, requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, reason);
    }

    private static boolean installationGeometryStillMatches(
            FreeOwnerTransaction.InstallationCustody custody, EntityPlayerMP actor, RuntimeException cause) {
        try {
            return custody.observedGeometry().filter(WorldCollisionCandidates.ActorGeometry.read(actor)::equals)
                    .isPresent();
        } catch (RuntimeException observationFailure) {
            if (observationFailure != cause) cause.addSuppressed(observationFailure);
            return false;
        }
    }

    private NativeRestoreAuthority mintNativeRestoreAuthority(Session session, Owned owner,
            PlayerPhysicalSnapshot accepted, PlayerBodyReference.FreeReference sourceReference,
            FreeTickResources resources, Optional<ModeTransitionPrepared.TrustedResizeObservation> callbackProfile) {
        EntityPlayerMP actor = session.player;
        WorldCollisionCandidates.ActorGeometry before = WorldCollisionCandidates.ActorGeometry.read(actor);
        NativeFlags flags = NativeFlags.read(actor);
        float rawEye = actor.eyeHeight;
        float effectiveEye = actor.getEyeHeight();
        PlayerBodyShape.Dimensions acceptedDimensions = accepted.placement().shape().dimensions();
        PlayerBodyShape.Dimensions dimensions = new PlayerBodyShape.Dimensions(
                acceptedDimensions.width(), acceptedDimensions.height(), effectiveEye);
        if (callbackProfile.isPresent()) {
            ModeTransitionPrepared.TrustedResizeObservation qualified = callbackProfile.orElseThrow();
            PlayerBodyShape.Dimensions callbackDimensions = qualified.dimensions();
            if (qualified.observedGeometry().position().equals(before.position())
                    && Float.floatToIntBits(qualified.observedGeometry().width())
                        == Float.floatToIntBits(before.width())
                    && Float.floatToIntBits(qualified.observedGeometry().height())
                        == Float.floatToIntBits(before.height())
                    && Float.floatToIntBits((float) callbackDimensions.width())
                        == Float.floatToIntBits((float) acceptedDimensions.width())
                    && Float.floatToIntBits((float) callbackDimensions.height())
                        == Float.floatToIntBits((float) acceptedDimensions.height())
                    && Float.floatToIntBits((float) callbackDimensions.eyeHeight())
                        == Float.floatToIntBits(effectiveEye))
                dimensions = new PlayerBodyShape.Dimensions(acceptedDimensions.width(),
                        acceptedDimensions.height(), callbackDimensions.eyeHeight());
        }
        if (!flags.requiresNative() || !PlayerKinematics.requiresNative(actor)
                || !(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || owner.snapshot != accepted || owner.travel.ownerReference() != sourceReference
                || !matchesPhysicalAnchorGeometry(before, accepted)
                || Float.floatToIntBits(rawEye) != Float.floatToIntBits(actor.eyeHeight)
                || Float.floatToIntBits(effectiveEye) != Float.floatToIntBits(actor.getEyeHeight())
                || !before.equals(WorldCollisionCandidates.ActorGeometry.read(actor))
                || !current(session) || session.owner != owner || session.world != actor.getServerWorld()
                || session.scopeHandle == null || owner.teleportPending || owner.nativeDelegated
                || !owner.nativeRestorePending || owner.nativeRestoreAwaitingAcknowledgement
                || owner.nativeRestoreTerminalFailure || resources == null)
            throw new IllegalStateException("native restore authority could not bind the current Free owner source");
        return new NativeRestoreAuthority(this, session, owner, accepted, sourceReference, session.world,
                session.handler, session.connectionHandle, session.scopeHandle, session.proposal, session.scope,
                owner.travel.correctionRevision(), session.world.getTotalWorldTime(), owner.budgetTick,
                owner.nativeRestoreRequestId, owner.nativeRestoreExplicitUnlock,
                owner.travel.pendingManualIntent().orElse(null), resources, owner.budget, flags, dimensions,
                rawEye, effectiveEye, before);
    }

    private void queueBodyReferenceState(Session session, Owned owner, ArrayList<Offer> states) {
        if (owner.recoveryPending || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror || owner.recoveryTerminal) return;
        session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, session.scope,
                session.outgoingSequence, session.proposal, Optional.empty(), Optional.of(owner.snapshot),
                Optional.empty(), owner.nativeTeleportId, Optional.ofNullable(owner.bodyReferenceReceipt), Optional.empty(),
                owner.travel.cardinalReference(), Optional.empty(), Optional.empty(),
                Optional.of(owner.travel.predictionSeed(owner.snapshot)), Optional.of(new PlayerPhysicsProtocol.PredictionUnavailable(
                        owner.snapshot.revision(), PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE)),
                Optional.empty(), Optional.of(owner.travel.mobilityState(owner.snapshot)),
                Optional.ofNullable(owner.nativeTransferRecoveryFailure).map(PlayerPhysicsProtocol.NativeTransferRecovery::new));
        NativeRestoreStateOffer restoreStateOffer = null;
        if (owner.nativeRestoreAwaitingAcknowledgement && !owner.nativeRestoreTerminalFailure
                && owner.nativeRestoreRevision == owner.snapshot.revision()
                && PlayerPhysicsProtocol.nativeRestorePurpose(packet)
                && owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape
                && shape.frame() == CardinalFrame.Y_POS) {
            restoreStateOffer = new NativeRestoreStateOffer(session, owner, packet,
                    new NativeRestoreStateIdentity(session.scope, session.proposal, packet.sequence(),
                            owner.snapshot.revision()));
        }
        states.add(new Offer(session.player, packet, null, null, restoreStateOffer));
        owner.lastStateTick = serverTick;
    }

    private static void resetNativeRestoreAcknowledgements(Owned owner, long revision) {
        owner.nativeRestoreRevision = revision;
        owner.nativeRestoreAcknowledged = false;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
    }

    private static boolean restoreStateResendDue(long worldTick, long lastStateWorldTick) {
        return lastStateWorldTick == Long.MIN_VALUE
                || worldTick - lastStateWorldTick >= NATIVE_RESTORE_RESEND_TICKS;
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt receipt(long requestId,
            PlayerPhysicsProtocol.BodyReferenceDecision decision, Optional<CardinalFrame> frame,
            Optional<Vec3d> heading, PlayerPhysicsProtocol.BodyReferenceReason reason) {
        return receipt(requestId, decision, frame.isPresent() ? requestId : 0, frame, heading, Optional.of(reason));
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt receipt(long requestId,
            PlayerPhysicsProtocol.BodyReferenceDecision decision, Optional<CardinalFrame> frame,
            Optional<Vec3d> heading, Optional<PlayerPhysicsProtocol.BodyReferenceReason> reason) {
        return receipt(requestId, decision, frame.isPresent() ? requestId : 0, frame, heading, reason);
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt receipt(long requestId,
            PlayerPhysicsProtocol.BodyReferenceDecision decision, long acceptedLockRequestId,
            Optional<CardinalFrame> frame, Optional<Vec3d> heading,
            Optional<PlayerPhysicsProtocol.BodyReferenceReason> reason) {
        return new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId, decision, acceptedLockRequestId,
                frame, heading, reason);
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt receipt(Owned owner, long requestId,
            PlayerPhysicsProtocol.BodyReferenceDecision decision,
            PlayerPhysicsProtocol.BodyReferenceReason reason) {
        boolean retained = decision != PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED
                && owner.bodyReferenceLock != null;
        return receipt(requestId, decision, retained ? owner.bodyReferenceLock.requestId() : 0,
                retained ? acceptedLockFrame(owner) : Optional.empty(),
                retained ? acceptedLockHeading(owner) : Optional.empty(), Optional.of(reason));
    }

    private static Optional<CardinalFrame> acceptedLockFrame(Owned owner) {
        return owner.bodyReferenceLock == null ? Optional.empty() : Optional.of(owner.bodyReferenceLock.frame());
    }

    private static Optional<Vec3d> acceptedLockHeading(Owned owner) {
        return owner.bodyReferenceLock == null ? Optional.empty() : Optional.of(owner.bodyReferenceLock.tangentHeading());
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt deferredManualReceipt(
            Owned owner, Optional<PlayerPhysicsProtocol.BodyReferenceReason> reason) {
        BodyReferenceLock manual = Objects.requireNonNull(owner.bodyReferenceLock, "manual body reference");
        return new PlayerPhysicsProtocol.BodyReferenceReceipt(manual.requestId(),
                PlayerPhysicsProtocol.BodyReferenceDecision.DEFERRED, manual.requestId(), Optional.of(manual.frame()),
                Optional.of(manual.tangentHeading()), reason);
    }

    private static PlayerPhysicsProtocol.BodyReferenceReceipt nativeHandoffReceipt(Owned owner, long requestId) {
        if (owner.bodyReferenceLock == null)
            return receipt(requestId, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    Optional.empty(), Optional.empty(), PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF);
        BodyReferenceLock manual = owner.bodyReferenceLock;
        return new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.DEFERRED, manual.requestId(),
                Optional.of(manual.frame()), Optional.of(manual.tangentHeading()),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
    }

    private static long latestBodyReferenceRequestId(Owned owner) {
        return Math.max(1, Math.max(owner.lastBodyReferenceRequestId,
                owner.bodyReferenceLock == null ? 0 : owner.bodyReferenceLock.requestId()));
    }

    private static void setManualReference(Owned owner, BodyReferenceLock manual,
                                           PlayerBodyReference.Transition transition) {
        Optional<PlayerBodyReference.ManualIntent> next = Optional.ofNullable(manual)
                .map(value -> new PlayerBodyReference.ManualIntent(value.requestId(), value.frame(), value.tangentHeading()));
        owner.bodyReferenceLock = manual;
        owner.travel.pendingManualIntent(Optional.empty());
        if (owner.travel.ownerReference() instanceof PlayerBodyReference.FreeReference free) {
            owner.travel.ownerReference(new PlayerBodyReference.FreeReference(next,
                    free.pendingTarget().filter(target -> target.source() != PlayerBodyReference.Source.MANUAL),
                    free.previousEffective()));
        } else {
            PlayerBodyReference.State previous = owner.travel.bodyReference();
            owner.travel.bodyReference(new PlayerBodyReference.State(next, previous.effective(), transition,
                    Optional.empty(), Optional.empty()));
        }
    }

    private static Optional<PlayerBodyReference.Effective> previousEffectiveForFree(
            PlayerPhysicsStep.TravelState travel) {
        if (travel.ownerReference() instanceof PlayerBodyReference.CardinalReference cardinal)
            return Optional.of(cardinal.state().effective());
        if (travel.ownerReference() instanceof PlayerBodyReference.FreeReference free)
            return free.previousEffective();
        return Optional.empty();
    }

    static PlayerBodyReference.State prepareSafeNativeReference(PlayerBodyReference.OwnerReference previous,
            float actorYaw, PlayerBodyReference.Transition transition) {
        Objects.requireNonNull(previous, "owner reference");
        Objects.requireNonNull(transition, "transition");
        PlayerBodyReference.Effective effectiveBefore = previous instanceof PlayerBodyReference.CardinalReference cardinal
                ? cardinal.state().effective()
                : ((PlayerBodyReference.FreeReference) previous).previousEffective().orElse(null);
        Vec3d heading = effectiveBefore == null
                ? PlayerBodyReference.initialHeading(CardinalFrame.Y_POS, actorYaw)
                : effectiveBefore.frame() == CardinalFrame.Y_POS ? effectiveBefore.tangentHeading()
                        : PlayerBodyReference.transportHeading(effectiveBefore.frame(), CardinalFrame.Y_POS,
                                effectiveBefore.tangentHeading());
        var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE,
                CardinalFrame.Y_POS, heading);
        return new PlayerBodyReference.State(previous.manualIntent(), effective, transition,
                Optional.empty(), Optional.empty());
    }

    private static void refreshManualReceipt(Owned owner) {
        BodyReferenceLock manual = owner.bodyReferenceLock;
        PlayerPhysicsProtocol.BodyReferenceReceipt previous = owner.bodyReferenceReceipt;
        if (manual == null || previous != null
                && previous.decision() == PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED
                && previous.requestId() >= manual.requestId()) return;
        PlayerBodyReference.Effective effective = owner.travel.cardinalReference().map(PlayerBodyReference.State::effective).orElse(null);
        boolean installed = effective != null && effective.source() == PlayerBodyReference.Source.MANUAL
                && effective.frame() == manual.frame() && effective.tangentHeading().equals(manual.tangentHeading());
        owner.bodyReferenceReceipt = installed
                ? new PlayerPhysicsProtocol.BodyReferenceReceipt(manual.requestId(),
                        PlayerPhysicsProtocol.BodyReferenceDecision.LOCKED, manual.requestId(), Optional.of(manual.frame()),
                        Optional.of(manual.tangentHeading()), Optional.empty())
                : deferredManualReceipt(owner, Optional.empty());
    }

    private static PlayerPhysicsProtocol.BodyReferenceReason bodyReferenceReason(CardinalReorientation.Reason reason) {
        return switch (reason) {
            case SAME_FRAME -> PlayerPhysicsProtocol.BodyReferenceReason.GEOMETRY_MISMATCH;
            case UNSUPPORTED_SHAPE -> PlayerPhysicsProtocol.BodyReferenceReason.UNSUPPORTED_SHAPE;
            case ACTOR_CHANGED -> PlayerPhysicsProtocol.BodyReferenceReason.ACTOR_CHANGED;
            case TARGET_OCCUPIED -> PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED;
            case PATH_BLOCKED -> PlayerPhysicsProtocol.BodyReferenceReason.PATH_BLOCKED;
            case WORLD_UNAVAILABLE -> PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE;
            case INSTALL_REJECTED -> PlayerPhysicsProtocol.BodyReferenceReason.INSTALL_REJECTED;
        };
    }

    private void publishNative(Session session, ArrayList<Offer> states) {
        NativeCorrectionControl control = session.owner.nativeCorrectionControl;
        if (control != null) {
            if (nativeCorrectionControlCurrent(control) && !control.acknowledged)
                queueNativeCorrectionControl(control, states);
            // Completion controls never resurrect a pre-teleport velocity base.
            session.owner.lastStateTick = serverTick;
            return;
        }
        NativeTransferPending transfer = session.owner.nativeTransfer;
        if (transfer == null || !nativeTransferCurrent(transfer))
            throw new IllegalStateException("Native publication requires an acknowledged restore installation and frozen handoff");
        session.owner.lastStateTick = serverTick;
        queueNativeTransfer(transfer, transfer.basePacket, states);
    }

    /** Retry one authenticated native request using this tick's existing owner resources. */
    private boolean retryPendingCardinalPose(Session session, Owned owner, long worldTick,
            ArrayList<Offer> outboundStates) {
        PendingCardinalPose pending = owner.pendingCardinalPose;
        if (pending == null || !pending.current() || owner.pendingCardinalPoseAttemptTick == worldTick) return false;
        var before = owner.snapshot;
        float observedEye = session.player.getEyeHeight();
        if (!pending.matchesSnapshot(session.player, before, observedEye)) return false;
        owner.pendingCardinalPoseAttemptTick = worldTick;
        var target = before.placement().proposeDimensionsAtFeet(pending.desired);
        FreeOwnerTransaction transaction = null;
        FreeOwnerTransaction.PreparedInstallation prepared = null;
        try {
            transaction = new FreeOwnerTransaction(session.player, before, owner.travel, owner.budget,
                    () -> pending.current() && owner.snapshot == before, owner,
                    owner.freeResources.acquire(owner, session.world, session.scope, owner.travel.correctionRevision()),
                    null, null, pending);
            var capture = WorldCollisionCandidates.capture(transaction, target.bounds(),
                    CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, POSE_SCAN, transaction.resources().environmentObservationWork());
            transaction.requireCurrent();
            if (capture instanceof WorldCollisionCandidates.Unavailable failure) {
                owner.lastFailure = "pending Cardinal pose query " + failure.reason() + ": " + failure.detail();
                pending.failure = failure.cause().orElse(null);
                return false;
            }
            if (!((WorldCollisionCandidates.Snapshot) capture).boxes().isEmpty()) {
                owner.lastFailure = "pending Cardinal pose target occupied";
                return false;
            }
            prepared = transaction.prepareInstallation(target);
            transaction.beginInstallation(prepared);
            var actor = session.player;
            actor.width = (float) pending.desired.width();
            actor.height = (float) pending.desired.height();
            transaction.requireOwnerFence();
            transaction.installEffectiveEyeHeight(pending.desired.eyeHeight());
            var bounds = target.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                    bounds.max().x(), bounds.max().y(), bounds.max().z()));
            transaction.requireOwnerFence();
            var transport = target.transport();
            actor.posX = transport.x(); actor.posY = transport.y(); actor.posZ = transport.z();
            transaction.recordInstalled(prepared);
            transaction.requireCurrent();
            if (!pending.nativeProfileCurrent())
                throw new IllegalStateException("Pending Cardinal pose native profile changed after installation");
            transaction.requireOwnerFence();
            // Resize about the accepted physical feet is an anchor rebase, never movement credit.
            owner.budget.rebase(new Vec3(transport.x(), transport.y(), transport.z()), owner.budgetTick);
            owner.snapshot = prepared.journal();
            if (owner.pendingCardinalPose == pending) owner.pendingCardinalPose = null;
            owner.lastFailure = null;
            publishAccepted(session, outboundStates);
            return true; // The resize is this tick's sole successor; do not also execute movement.
        } catch (RuntimeException failure) {
            if (transaction != null) {
                try {
                    transaction.recordMutationFailure(prepared, failure);
                    retainFailedInstallationCustody(session, owner, transaction.installationCustody());
                } catch (RuntimeException cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                }
            }
            if (current(session) && session.owner == owner && owner.pendingCardinalPose == pending)
                failNativeEnvironment(session, owner, failure, "pending Cardinal pose retry");
            else throw failure;
            return true;
        } finally {
            if (transaction != null) transaction.close();
        }
    }

    private static OwnedPredictionTimeline.Context predictionContext(Session session, Owned owner) {
        return new OwnedPredictionTimeline.Context(session.scope, session.proposal, session.connectionHandle,
                session.scopeHandle, owner, session.world, owner.travel.correctionRevision());
    }

    private boolean predictionStable(Session session, Owned owner, PlayerPhysicsProtocol.Controls controls) {
        return predictionStable(session, owner, owner.snapshot, controls, false);
    }
    private boolean predictionStable(Session session, Owned owner, PlayerPhysicsProtocol.Controls controls,
            boolean initialOwned) {
        return predictionStable(session, owner, owner.snapshot, controls, initialOwned);
    }
    private boolean predictionStable(Session session, Owned owner, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicsProtocol.Controls controls, boolean initialOwned) {
        return predictionStable(session, owner, snapshot, owner.snapshot, controls, initialOwned);
    }
    private boolean predictionStable(Session session, Owned owner, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicalSnapshot expectedOwnerSnapshot, PlayerPhysicsProtocol.Controls controls,
            boolean initialOwned) {
        var context = predictionContext(session, owner);
        long correction = owner.travel.correctionRevision();
        // Publication runs after actor installation but before the atomic owner commit. A Free
        // predecessor cannot service native fluid hooks against that new Cardinal geometry.
        // Defer capture until the next committed owner slot; never borrow the predecessor's scope.
        boolean stable = snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal
                && expectedOwnerSnapshot.placement().shape() instanceof PlayerBodyShape.Cardinal
                && !owner.travel.retainedCardinal(snapshot)
                && !owner.travel.mobility().blocksCapture()
                && controls.mobility().request().map(request -> request.mode() == PlayerMobilityIntent.Mode.WALK).orElse(true)
                && controls.bodyReferenceIntent().action() == PlayerPhysicsProtocol.BodyReferenceAction.NONE
                && controls.desiredBodyAttitude().isEmpty() && owner.pendingAttitudeRequest == null
                && owner.pendingCardinalPose == null && !owner.teleportPending && !owner.nativeDelegated
                && !owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement && !owner.nativeHandoffPrepared
                && !owner.recoveryPending && !owner.recoveryAwaitingApply && (!owner.recoveryAwaitingMirror || initialOwned)
                && !owner.recoveryTerminal && !owner.recoveryNativeResume
                && !session.player.isInWater() && !session.player.isInLava() && !session.player.isElytraFlying();
        if (!stable || !controls.boost()) return stable;
        // A sampled key is not propulsion permission. Read current G at this consumption
        // boundary; session.source describes admission and may predate a same-owner G change.
        boolean creative = session.player.interactionManager.isCreative();
        var observed = intent.observe(session.player);
        if (observed.isEmpty() || !current(session) || session.owner != owner || owner.snapshot != expectedOwnerSnapshot
                || owner.travel.correctionRevision() != correction
                || !predictionContext(session, owner).equals(context)) return false;
        return !PlayerBoostEligibility.requested(true,
                observed.map(GIntentObservation::active).orElse(false), creative);
    }

    private static boolean predictionFrameCurrent(Owned owner) {
        return predictionFrameCurrent(owner, owner.snapshot);
    }
    private static boolean predictionFrameCurrent(Owned owner, PlayerPhysicalSnapshot snapshot) {
        var placement = snapshot.placement();
        return Objects.equals(owner.predictionShape, placement.shape())
                && Objects.equals(owner.predictionAttitude, placement.attitude())
                && predictionReferenceEquivalent(owner.predictionReference, owner.travel.cardinalReference());
    }

    /** Kernel geometry/qualification follows effective source, frame and accepted tangent heading.
     * Manual preferences and pending/transition metadata do not change an installed prediction basis;
     * their eventual selection is observed through effective(), while Ready carries sampled forces
     * and standing qualification. Never mint another transaction for metadata-only cleanup.
     */
    static boolean predictionReferenceEquivalent(Optional<PlayerBodyReference.State> previous,
            Optional<PlayerBodyReference.State> current) {
        return previous.map(PlayerBodyReference.State::effective).equals(current.map(PlayerBodyReference.State::effective));
    }

    private static void suspendChangedPredictionReference(Session session, Owned owner) {
        suspendChangedPredictionReference(session, owner, owner.snapshot);
    }
    private static void suspendChangedPredictionReference(Session session, Owned owner,
            PlayerPhysicalSnapshot snapshot) {
        var placement = snapshot.placement();
        if (Objects.equals(owner.predictionShape, placement.shape())
                && Objects.equals(owner.predictionAttitude, placement.attitude())
                && !predictionReferenceEquivalent(owner.predictionReference, owner.travel.cardinalReference()))
            owner.predictionTimeline.suspendReference(predictionContext(session, owner));
    }

    private void completePredictionSlot(Session session, Owned owner,
            Optional<OwnedPredictionTimeline.Frozen> frozen, PlayerPhysicsProtocol.StepOutcome outcome) {
        completePredictionSlot(session, owner, owner.snapshot, frozen, outcome);
    }
    private void completePredictionSlot(Session session, Owned owner, PlayerPhysicalSnapshot snapshot,
            Optional<OwnedPredictionTimeline.Frozen> frozen, PlayerPhysicsProtocol.StepOutcome outcome) {
        if (owner.travel.retainedCardinal(snapshot) || owner.travel.mobility().blocksCapture()
                || snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
            owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
            return;
        }
        suspendChangedPredictionReference(session, owner, snapshot);
        if (owner.predictionTimeline.suspended() || frozen.isEmpty()) return;
        if (!predictionStable(session, owner, snapshot, owner.snapshot, frozen.orElseThrow().controls(), false)
                || !predictionFrameCurrent(owner, snapshot)) {
            owner.predictionTimeline.retire();
            return;
        }
        var completedBoundary = owner.predictionTimeline.complete(predictionContext(session, owner), frozen.orElseThrow(), outcome,
                // A committed move may defer its tail after the jump already happened.
                owner.travel.predictionJumpCooldown(), outcome == PlayerPhysicsProtocol.StepOutcome.APPLIED
                        || snapshot.revision() > frozen.orElseThrow().preSnapshotRevision()
                        ? owner.travel.jumpOutcomeForRevision(frozen.orElseThrow().preSnapshotRevision())
                        : PlayerPhysicsProtocol.JumpOutcome.NONE);
        owner.predictionCheckpoint = snapshot;
        if (UnigravMod.LOGGER.isInfoEnabled()
                && owner.travel.jumpOutcomeForRevision(frozen.orElseThrow().preSnapshotRevision())
                        == PlayerPhysicsProtocol.JumpOutcome.APPLIED) {
            // Slot completion is not a network-send receipt. Keep callback and committed outcome distinct.
            UnigravMod.LOGGER.info("ug-jump-slot scope={} worldTick={} preRevision={} completedRevision={}"
                            + " callbackOutcome={} stepOutcome={} completedGround={} cooldown={} boundary={}",
                    snapshot.scope(), session.world.getTotalWorldTime(), frozen.orElseThrow().preSnapshotRevision(),
                    snapshot.revision(), PlayerPhysicsProtocol.JumpOutcome.APPLIED, outcome,
                    serverControlGround(snapshot), owner.travel.predictionJumpCooldown(), completedBoundary);
        }
    }

    private static void beginPredictionBootstrap(Session session, Owned owner) {
        beginPredictionBootstrap(session, owner, owner.snapshot);
    }

    private static void beginPredictionBootstrap(Session session, Owned owner, PlayerPhysicalSnapshot snapshot) {
        long generation = Math.incrementExact(session.predictionGeneration);
        owner.predictionTimeline.begin(predictionContext(session, owner),
                new PlayerPhysicsProtocol.PredictionBootstrap(generation, owner.travel.correctionRevision()));
        session.predictionGeneration = generation;
        owner.predictionShape = snapshot.placement().shape();
        owner.predictionAttitude = snapshot.placement().attitude();
        owner.predictionReference = owner.travel.cardinalReference();
        owner.predictionCheckpoint = null;
    }
    /** Result authority includes a legal bootstrap change made inside this publication. */
    private record PredictionPublicationIdentity(OwnedPredictionTimeline.Context context,
            PlayerPhysicalSnapshot snapshot, long generation, PlayerPhysicalSnapshot ownerSnapshot) { }
    private record PublishedPrediction(PlayerPhysicsProtocol.PredictionPhase phase,
            Optional<PlayerPhysicsProtocol.PredictionBoundary> boundary, PredictionPublicationIdentity identity) { }

    private boolean predictionPublicationCurrent(Session session, Owned owner, PredictionPublicationIdentity identity) {
        return current(session) && sessions.get(session.manager) == session && session.owner == owner
                && owner.snapshot == identity.ownerSnapshot() && session.predictionGeneration == identity.generation()
                && identity.context().same(predictionContext(session, owner));
    }

    private static Optional<PublishedPrediction> predictionPublication(PredictionPublicationIdentity identity,
            PlayerPhysicsProtocol.PredictionPhase phase, Optional<PlayerPhysicsProtocol.PredictionBoundary> boundary) {
        return Optional.of(new PublishedPrediction(phase, boundary, identity));
    }

    /** Missing boundary is never used to infer a fresh mapping lifetime. */
    private Optional<PublishedPrediction> publishedPrediction(Session session, Owned owner, CardinalPredictionSeed seed,
            boolean initialOwned) {
        return publishedPrediction(session, owner, owner.snapshot, owner.snapshot, seed, initialOwned);
    }

    private Optional<PublishedPrediction> publishedPrediction(Session session, Owned owner,
            PlayerPhysicalSnapshot snapshot, PlayerPhysicalSnapshot expectedOwnerSnapshot,
            CardinalPredictionSeed seed, boolean initialOwned) {
        long revision = snapshot.revision();
        var context = predictionContext(session, owner);
        var identity = new PredictionPublicationIdentity(context, snapshot, session.predictionGeneration,
                expectedOwnerSnapshot);
        boolean stable = predictionStable(session, owner, snapshot, expectedOwnerSnapshot,
                owner.controls, initialOwned);
        // Virtual actor reads may reenter TP/recovery. Never mint result authority for that successor.
        if (!predictionPublicationCurrent(session, owner, identity)) return Optional.empty();
        if (owner.travel.retainedCardinal(snapshot) || owner.travel.mobility().blocksCapture()
                || snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
            owner.predictionTimeline.suspendMobility(context);
        suspendChangedPredictionReference(session, owner, snapshot);
        if (owner.predictionTimeline.suspended()) {
            if (owner.predictionTimeline.rearmRequested() && stable && seed instanceof CardinalPredictionSeed.Ready) {
                beginPredictionBootstrap(session, owner, snapshot);
                // Only our callback-free bootstrap transition can change the publication generation.
                identity = new PredictionPublicationIdentity(context, snapshot, session.predictionGeneration,
                        expectedOwnerSnapshot);
                if (!predictionPublicationCurrent(session, owner, identity)) return Optional.empty();
            } else return predictionPublication(identity, owner.predictionTimeline.phase(context,revision,
                    owner.travel.predictionJumpCooldown()),Optional.empty());
        }
        if (!stable || !predictionFrameCurrent(owner, snapshot)) {
            owner.predictionTimeline.retire();
            return predictionPublication(identity, new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                    PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE), Optional.empty());
        }
        var phase = owner.predictionTimeline.phase(context, revision, owner.travel.predictionJumpCooldown());
        var boundary = owner.predictionTimeline.boundary(context);
        if (phase instanceof PlayerPhysicsProtocol.Anchored && owner.predictionCheckpoint != snapshot) {
            owner.predictionTimeline.retire();
            return predictionPublication(identity, new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                    PlayerPhysicsProtocol.PredictionUnavailableReason.CHECKPOINT_CHANGED), Optional.empty());
        }
        // A completed DEFERRED slot is still an anchored fact when its environment seed is unavailable.
        // It cannot authorize client dry prediction, whose eligibility separately requires Ready.
        if (phase instanceof PlayerPhysicsProtocol.AwaitingFirstResolvedSlot && !(seed instanceof CardinalPredictionSeed.Ready))
            return predictionPublication(identity, new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                    PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE), Optional.empty());
        return predictionPublication(identity, phase, phase instanceof PlayerPhysicsProtocol.Anchored ? boundary : Optional.empty());
    }

    private void advanceOwned(Session session, ArrayList<Offer> outboundStates) {
        Owned owner = session.owner;
        try {
            advanceOwnedTracked(session, outboundStates);
        } finally {
            // An aborted or fenced invocation never becomes a processed simulation slot.
            if (owner.predictionTimeline.hasInFlight()) owner.predictionTimeline.retire();
        }
    }

    private void advanceOwnedTracked(Session session, ArrayList<Offer> outboundStates) {
        Owned owner = session.owner;
        reconcileMobilityControl(session, owner);
        Optional<PlayerAttitudeRequest> pendingAttitudeRequest = currentAttitudeRequest(session, owner);
        if (owner.recoveryPending || owner.recoveryAwaitingApply
                || owner.recoveryAwaitingMirror || owner.recoveryTerminal) {
            discardUnpromotedTrustedResizeProposal(owner, owner.snapshot);
            return;
        }
        if (owner.teleportPending) {
            discardUnpromotedTrustedResizeProposal(owner, owner.snapshot);
            return;
        }
        if (owner.eyeProfilePublicationPending == owner.snapshot) {
            int beforePublication = outboundStates.size();
            publishAccepted(session, outboundStates);
            if (outboundStates.size() > beforePublication) owner.eyeProfilePublicationPending = null;
        }
        long worldTick = session.world.getTotalWorldTime();
        if (serverTick <= owner.adoptedAtTick) {
            traceFreeResize(session, owner.snapshot, null, owner.snapshot, "TICK_ADMISSION",
                    "ADOPTION_TICK_NOT_ADVANCED", worldTick, true, owner.travel.correctionRevision(),
                    owner.trustedFreeResizeProposal != null, UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS,
                    null, "server tick has not advanced beyond adoption", null);
            return;
        }
        if (worldTick <= owner.lastWorldTick) {
            traceFreeResize(session, owner.snapshot, null, owner.snapshot, "TICK_ADMISSION",
                    "WORLD_TICK_NOT_ADVANCED", worldTick, true, owner.travel.correctionRevision(),
                    owner.trustedFreeResizeProposal != null, UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS,
                    null, "world tick has not advanced", null);
            return;
        }
        traceFreeResize(session, owner.snapshot, null, owner.snapshot, "TICK_ADMISSION", "ADVANCE",
                worldTick, true, owner.travel.correctionRevision(), owner.trustedFreeResizeProposal != null,
                UNOBSERVED_EYE_BITS, UNOBSERVED_EYE_BITS, null, "adoption and world tick admitted", null);
        owner.lastWorldTick = worldTick;
        owner.budgetTick = Math.incrementExact(owner.budgetTick);
        if (retryPendingCardinalPose(session, owner, worldTick, outboundStates)) return;
        boolean cardinalWalkHeadLookOnly = PlayerPhysicsStep.cardinalWalkHeadLookOnly(owner.snapshot, owner.travel);
        if (cardinalWalkHeadLookOnly && pendingAttitudeRequest.isPresent()
                && owner.pendingAttitudeRequest == pendingAttitudeRequest.orElseThrow()) {
            // This packet carried camera LOOK while the accepted Cardinal owner is in WALK;
            // consume the non-body request without starting a Cardinal-to-Free conversion.
            invalidateAttitudeRequest(owner);
        }
        final Optional<PlayerAttitudeRequest> attitudeRequest = cardinalWalkHeadLookOnly
                ? Optional.empty() : pendingAttitudeRequest;
        if ((owner.nativeRestorePending || owner.nativeRestoreAwaitingAcknowledgement || owner.nativeHandoffPrepared)
                && !PlayerKinematics.requiresNative(session.player)) {
            if (owner.nativeRestoreExplicitUnlock) {
                long requestId = Math.max(1, owner.nativeRestoreRequestId);
                setManualReference(owner, null, PlayerBodyReference.Transition.MANUAL_CLEARED);
                owner.bodyReferenceReceipt = receipt(requestId,
                        PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, Optional.empty(), Optional.empty(),
                        Optional.empty());
            } else {
                refreshManualReceipt(owner);
            }
            clearNativeRestore(owner);
        }
        if (owner.bodyReferenceLock != null) {
            var currentIntent = intent.observe(session.player);
            if (currentIntent.isEmpty() || !currentIntent.orElseThrow().active()
                    || !session.player.interactionManager.isCreative()) {
                clearManualForGOff(owner);
                if (!owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement)
                    clearNativeRestore(owner);
            }
        } else if (owner.travel.pendingManualIntent().isPresent()) {
            var currentIntent = intent.observe(session.player);
            if (currentIntent.isEmpty() || !currentIntent.orElseThrow().active()
                    || !session.player.interactionManager.isCreative()) clearManualForGOff(owner);
        }
        if (!session.player.hasNoGravity()) {
            // A removed REPLACE binding (or an explicit native re-enable) must not strand the sampler.
            owner.travel.nativeNoGravity = false;
            session.player.setNoGravity(true);
        }
        boolean impulseApplied = applyPendingImpulse(session);
        if (movement == null) movement = createMovement();
        var source = intent.observe(session.player);
        owner.boostCarrier |= source.map(GIntentObservation::active).orElse(false)
                && session.player.interactionManager.isCreative();
        var before = owner.snapshot;
        if (owner.travel.mobility().blocksCapture() || before.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
            owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
        if (owner.predictionTimeline.active() && owner.latestAdmittedInput!=null && !owner.latestAdmittedPredictionAccepted)
            owner.predictionTimeline.suspendUnmapped(predictionContext(session,owner),owner.latestAdmittedInput.sequence());
        suspendChangedPredictionReference(session, owner);
        if (owner.predictionTimeline.active() && (!predictionStable(session, owner, owner.controls)
                || !predictionFrameCurrent(owner))) owner.predictionTimeline.retire();
        Optional<OwnedPredictionTimeline.Frozen> predictionSlot = owner.predictionTimeline.active()
                ? owner.predictionTimeline.freeze(predictionContext(session, owner), before.revision(),
                        worldTick, INPUT_TIMEOUT_TICKS, IDLE) : Optional.empty();
        PlayerPhysicsProtocol.Controls stepControls = predictionSlot.map(OwnedPredictionTimeline.Frozen::controls)
                .orElse(worldTick - owner.inputWorldTick <= INPUT_TIMEOUT_TICKS ? owner.controls : IDLE);
        boolean freshInput = owner.inputWorldTick != Long.MIN_VALUE
                && worldTick >= owner.inputWorldTick && worldTick - owner.inputWorldTick <= INPUT_TIMEOUT_TICKS;
        int detachThreshold = UnigravConfig.longSpaceDetachTicks;
        boolean creative = session.player.interactionManager.isCreative();
        boolean capabilityFlying = session.player.capabilities.isFlying;
        boolean observedGActive = source.map(GIntentObservation::active).orElse(false);
        boolean nativeFlight = creative && capabilityFlying
                && source.map(observation -> !observation.active()).orElse(false);
        boolean gFlight = creative && observedGActive
                && owner.travel.mobility().mode() == PlayerMobilityIntent.Mode.FLIGHT;
        long stepInputSequence = predictionSlot.map(value -> value.packet().sequence())
                .orElse(owner.latestAdmittedInput == null ? session.lastIncomingSequence : owner.latestAdmittedInput.sequence());
        var mobilityTick = owner.travel.mobility().advance(worldTick, stepInputSequence,
                stepControls.mobility(), freshInput, detachThreshold);
        if (stepControls.mobility().pressId() > 0 || stepControls.mobility().focused()) {
            // Native jumping consumes a held key, so landing can start another jump after
            // the normal cooldown. The backpack's press edge only owns its WALK gesture.
            boolean sustainedJump = !observedGActive || nativeFlight || gFlight || mobilityTick.detachedNow();
            boolean verticalHeld = sustainedJump && freshInput
                    && owner.travel.mobility().heldCurrent(stepControls.mobility(), worldTick);
            stepControls = stepControls.withJump(sustainedJump ? verticalHeld : mobilityTick.shortJump());
        }
        if (mobilityTick.detachedNow()) {
            owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
            predictionSlot = Optional.empty();
        }
        PlayerBodyReference.OwnerReference referenceBefore = owner.travel.ownerReference();
        long correctionBefore = owner.travel.correctionRevision();
        long mobilityGenerationBefore = owner.travel.mobility().generation();
        var connectionBefore = session.connectionHandle;
        var scopeBefore = session.scopeHandle;
        var worldBefore = session.world;
        var exactPlayer = session.player;
        long proposalBefore = session.proposal;
        Optional<NativeResizeProposal> trustedResizeProposal = consumeTrustedFreeResizeProposal(
                session, owner, before, worldTick, correctionBefore, connectionBefore, scopeBefore, "ADVANCE");
        Optional<ModeTransitionPrepared.TrustedResizeObservation> trustedResize =
                trustedResizeProposal.map(NativeResizeProposal::observation);
        PlayerPhysicsStep.Result result = null;
        FreeOwnerTransaction ownerTransaction = null;
        FreeOwnerTransaction.ClosedStepAdmissionSeal closedStepAdmissionSeal = null;
        Optional<FreeOwnerTransaction.InstallationCustody> capturedCustody = Optional.empty();
        Optional<FreeOwnerTransaction.EyeMetadataCompletion> eyeCompletion = Optional.empty();
        Optional<FreeOwnerTransaction.TerminalMotionReceipt> terminalMotion = Optional.empty();
        RuntimeException stepFailure = null;
        PendingCardinalPose pendingBefore = owner.pendingCardinalPose;
        BooleanSupplier ownerCurrent =
                () -> current(session) && session.owner == owner && owner.snapshot == before
                        && owner.pendingCardinalPose == pendingBefore
                        && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                        && owner.travel.correctionRevision() == correctionBefore
                        && owner.travel.mobility().generation() == mobilityGenerationBefore && !owner.teleportPending
                        && (attitudeRequest.isEmpty() || owner.pendingAttitudeRequest == attitudeRequest.orElseThrow()
                            && isCurrentAttitudeRequest(session, owner, attitudeRequest.orElseThrow(),
                                    session.world.getTotalWorldTime()));
        NetHandlerPlayServer exactHandler = session.handler;
        FreeOwnerTransaction.TrustedResizeAuthority resizeAuthority = trustedResizeProposal.map(proposal ->
                new FreeOwnerTransaction.TrustedResizeAuthority(proposal, proposal.ownerIdentity(),
                        proposal.ownerSnapshot(), proposal.anchorSnapshot(), proposal.world(), session.handler,
                        proposal.connection(), proposal.scopeHandle(), proposal.sessionProposal(), proposal.scope(),
                        proposal.correctionRevision(), proposal.capturedWorldTick(), proposal.observation(),
                        () -> current(session) && session.owner == proposal.ownerIdentity()
                                && owner.snapshot == proposal.ownerSnapshot()
                                && session.world == proposal.world() && session.handler == exactHandler
                                && session.connectionHandle == proposal.connection()
                                && session.scopeHandle == proposal.scopeHandle()
                                && session.proposal == proposal.sessionProposal()
                                && session.scope.equals(proposal.scope())
                                && owner.travel.correctionRevision() == proposal.correctionRevision()
                                && session.world.getTotalWorldTime() >= proposal.capturedWorldTick()
                                && session.world.getTotalWorldTime() - proposal.capturedWorldTick() <= 1
                                && proposal.observation().bodyProfileCurrent()))
                .orElse(null);
        try {
            ownerTransaction = new FreeOwnerTransaction(session.player, before, owner.travel, owner.budget, ownerCurrent,
                    owner, owner.freeResources.acquire(owner, session.world, session.scope, correctionBefore),
                    resizeAuthority, null, pendingBefore, owner.nativeCardinalMotion);
            result = movement.stepOwned(session.player, before, source.map(GIntentObservation::active),
                    stepControls,
                    owner.budget, owner.budgetTick, owner.boostCarrier, owner.travel, ownerCurrent,
                    ownerTransaction, attitudeRequest, trustedResize);
        } catch (RuntimeException failure) {
            stepFailure = failure;
            if (ownerTransaction != null) ownerTransaction.recordFailure(failure);
        }
        if (ownerTransaction != null) {
            try {
                capturedCustody = ownerTransaction.installationCustody();
                if (stepFailure == null && result instanceof PlayerPhysicsStep.Applied applied) {
                    terminalMotion = ownerTransaction.terminalMotionReceipt();
                    FreeOwnerTransaction.TerminalMotionReceipt receipt = terminalMotion.orElseThrow(() ->
                            new IllegalStateException("Applied Step returned without its exact terminal motion receipt"));
                    if (!receipt.matchesStepSnapshot(ownerTransaction, applied.snapshot()))
                        throw new IllegalStateException("Applied Step snapshot does not match its exact terminal response receipt");
                }
                if (stepFailure == null && (result instanceof PlayerPhysicsStep.Applied
                        || result instanceof PlayerPhysicsStep.CommittedDeferred))
                    eyeCompletion = ownerTransaction.exportEyeMetadataCompletion();
            } catch (RuntimeException custodyFailure) {
                if (stepFailure == null) stepFailure = custodyFailure;
                else if (custodyFailure != stepFailure) stepFailure.addSuppressed(custodyFailure);
            }
            try {
                ownerTransaction.close();
            } catch (RuntimeException closeFailure) {
                if (stepFailure == null) stepFailure = closeFailure;
                else if (closeFailure != stepFailure) stepFailure.addSuppressed(closeFailure);
            }
            if (stepFailure == null && result instanceof PlayerPhysicsStep.Applied applied) {
                try {
                    closedStepAdmissionSeal = ownerTransaction.exportClosedStepAdmissionSeal(applied.snapshot());
                } catch (RuntimeException sealFailure) {
                    stepFailure = sealFailure;
                }
            }
        }
        if (stepFailure == null && eyeCompletion.isPresent()) {
            try {
                var completion = eyeCompletion.orElseThrow();
                var original = result instanceof PlayerPhysicsStep.Applied applied ? applied.snapshot()
                        : ((PlayerPhysicsStep.CommittedDeferred) result).snapshot();
                if (completion.source() != before || completion.journal().placement() != original.placement()
                        || completion.journal().revision() != original.revision()
                        || original.revision() != Math.incrementExact(before.revision())
                        || !original.scope().equals(before.scope()) || !ownerCurrent.getAsBoolean())
                    throw new IllegalStateException("Native eye completion lost its exact movement result");
                var candidate = new PlayerPhysicalSnapshot(original.scope(), original.revision(),
                        completion.placement(), original.worldVelocity(), original.contacts());
                var producer = nativePoseEyeProfile(session.player);
                if (!ownerCurrent.getAsBoolean() || !completion.producer().equals(producer)
                        || !matchesPhysicalGeometry(session.player, candidate) || !ownerCurrent.getAsBoolean())
                    throw new IllegalStateException("Native eye completion changed after transaction closure");
                var finalProducer = nativePoseEyeProfile(session.player);
                if (!completion.producer().equals(finalProducer)
                        || !matchesPhysicalAnchorGeometry(session.player, candidate)
                        || Float.floatToIntBits(session.player.eyeHeight) != completion.rawEyeBits()
                        || !ownerCurrent.getAsBoolean())
                    throw new IllegalStateException("Native eye producer changed during completion verification");
                result = result instanceof PlayerPhysicsStep.Applied applied
                        ? new PlayerPhysicsStep.Applied(candidate, applied.freeMovementStopDiagnostic(),
                                applied.rejectedAttitudeDiagnostic())
                        : new PlayerPhysicsStep.CommittedDeferred(candidate,
                                ((PlayerPhysicsStep.CommittedDeferred) result).tail());
            } catch (RuntimeException completionFailure) {
                stepFailure = completionFailure;
            }
        }
        if (stepFailure != null || result instanceof PlayerPhysicsStep.CommittedFailure
                || result instanceof PlayerPhysicsStep.CommittedDeferred || result instanceof PlayerPhysicsStep.CommittedTailStop)
            owner.nativeCardinalMotion = null;
        traceFreeResizeStepResult(session, before, owner.snapshot, worldTick, correctionBefore,
                trustedResizeProposal.isPresent(), result, stepFailure);
        logServerControlProbe(session, owner, before, worldTick, stepControls, source, creative,
                capabilityFlying, observedGActive, nativeFlight, gFlight, mobilityTick, freshInput,
                detachThreshold, result, stepFailure, capturedCustody, terminalMotion, ownerTransaction);
        PlayerAttitudeRequest consumedReferencePriorityRequest = null;
        if (stepFailure == null && result instanceof PlayerPhysicsStep.Applied) {
            var marker = owner.travel.consumeReferencePriorityAttitude();
            if (marker.isPresent()) {
                var preemption = marker.orElseThrow();
                PlayerAttitudeRequest preempted = preemption.request();
                if (preemption.owner() == owner.travel && preemption.accepted() == before
                        && preemption.worldTick() == worldTick && worldTick == session.world.getTotalWorldTime()
                        && attitudeRequest.filter(request -> request == preempted).isPresent()
                        && owner.pendingAttitudeRequest == preempted
                        && current(session) && session.owner == owner && owner.snapshot == before
                        && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                        && owner.travel.correctionRevision() == correctionBefore
                        && owner.travel.mobility().generation() == mobilityGenerationBefore && !owner.teleportPending
                        && isCurrentAttitudeRequest(session, owner, preempted, worldTick)) {
                    invalidateAttitudeRequest(owner);
                    consumedReferencePriorityRequest = preempted;
                }
            }
        }
        if (stepFailure != null) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            retainFailedInstallationCustody(session, owner, capturedCustody);
            if (capturedCustody.isPresent()) {
                var custody = capturedCustody.orElseThrow();
                if (custody.ownerIdentity() == owner && custody.actor() == session.player
                        && custody.scope().equals(session.scope) && custody.connection() == session.handler
                        && custody.worldTick() == worldTick && custody.correctionRevision() == correctionBefore
                        && custody.stage() != FreeOwnerTransaction.InstallationStage.PREPARED
                        && current(session) && session.owner == owner && owner.snapshot == before
                        && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                        && owner.travel.correctionRevision() == correctionBefore
                        && owner.travel.mobility().generation() == mobilityGenerationBefore && !owner.teleportPending
                        && !PlayerKinematics.requiresNative(session.player)) {
                    owner.failedInstallationCustody = custody;
                    if (custody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                            && custody.observedGeometry().filter(observed -> observed.equals(
                                    WorldCollisionCandidates.ActorGeometry.read(session.player))).isPresent()
                            && matchesPhysicalGeometry(session.player, custody.expectedJournal())) {
                        owner.snapshot = custody.expectedJournal();
                        retireCardinalPendingAfterFreeCommit(owner);
                        if (custody.expectedPlacement().shape() instanceof PlayerBodyShape.FreeCapsule)
                            owner.travel.ownerReference(new PlayerBodyReference.FreeReference(
                                    owner.travel.ownerReference().manualIntent(), Optional.empty(),
                                    previousEffectiveForFree(owner.travel)));
                    }
                    failNativeEnvironment(session, owner,
                            ownerTransaction.originalFailure().orElse(stepFailure),
                            custody.stage() == FreeOwnerTransaction.InstallationStage.PARTIAL
                                    ? "partial owner installation" : "committed owner installation");
                    return;
                }
                // The failure belongs to a transaction whose authority fence changed. Keep its
                // evidence on that session, but never apply it to a successor or repair stale geometry.
                if (custody.stage() != FreeOwnerTransaction.InstallationStage.PREPARED) return;
            }
            commitReferenceGeometryIfInstalled(session, owner, before);
            settlePendingManualReference(session, owner, before);
            if (before.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    || capturedCustody.isPresent()) {
                failNativeEnvironment(session, owner, stepFailure, "owner step");
                return;
            }
            throw stepFailure;
        }
        if (result instanceof PlayerPhysicsStep.CommittedTailStop stopped) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            retainFailedInstallationCustody(session, owner, stopped.custody());
            if (!stopped.nativeTransition()) return;
            var custody = stopped.custody().orElseThrow(() ->
                    new IllegalStateException("installed native transition lost its installation custody"));
            boolean exactOwner = ownerCurrent.getAsBoolean();
            boolean exactGeometry = exactOwner && custody.ownerIdentity() == owner
                    && custody.actor() == session.player && custody.connection() == session.handler
                    && custody.scope().equals(session.scope) && custody.correctionRevision() == correctionBefore
                    && custody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                    && custody.expectedJournal().revision() == stopped.snapshot().revision()
                    && custody.expectedPlacement() == stopped.snapshot().placement()
                    && matchesPhysicalGeometry(session.player, stopped.snapshot());
            if (!ownerCurrent.getAsBoolean()) return;
            if (!exactGeometry)
                throw new IllegalStateException("installed native transition geometry or custody changed");
            if (!PlayerKinematics.requiresNative(session.player)) return;
            if (!ownerCurrent.getAsBoolean()) return;
            owner.snapshot = stopped.snapshot();
            retireCardinalPendingAfterFreeCommit(owner);
            if (stopped.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                owner.travel.ownerReference(new PlayerBodyReference.FreeReference(
                        owner.travel.ownerReference().manualIntent(), Optional.empty(),
                        previousEffectiveForFree(owner.travel)));
            refreshManualReceipt(owner);
            handleNativeDelegation(session, outboundStates);
            return;
        }
        if (result instanceof PlayerPhysicsStep.CommittedFailure failure) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            Optional<FreeOwnerTransaction.InstallationCustody> custodyValue = failure.custody().isPresent()
                    ? failure.custody() : capturedCustody;
            retainFailedInstallationCustody(session, owner, custodyValue);
            boolean exactFailureOwner = current(session) && session.owner == owner && owner.snapshot == before
                    && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                    && owner.travel.correctionRevision() == correctionBefore
                        && owner.travel.mobility().generation() == mobilityGenerationBefore && !owner.teleportPending
                    && !PlayerKinematics.requiresNative(session.player);
            boolean exactInstalledGeometry = exactFailureOwner
                    && matchesPhysicalGeometry(session.player, failure.snapshot());
            if (custodyValue.isPresent()) {
                var custody = custodyValue.orElseThrow();
                if (exactFailureOwner && custody.ownerIdentity() == owner && custody.actor() == session.player
                        && custody.scope().equals(session.scope) && custody.connection() == session.handler
                        && custody.correctionRevision() == correctionBefore) {
                    owner.failedInstallationCustody = custody;
                    boolean custodyGeometryCurrent = custody.observedGeometry().filter(observed -> observed.equals(
                            WorldCollisionCandidates.ActorGeometry.read(session.player))).isPresent();
                    if (custody.stage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                            && custodyGeometryCurrent && exactInstalledGeometry) {
                        if (!ownerCurrent.getAsBoolean()) {
                            UnigravMod.LOGGER.error("ugmotion1 owner environment failed: stage=superseded committed state capture scope={} revision={}",
                                    before.scope(), before.revision(), failure.cause());
                            return;
                        }
                        var committedSnapshot = custody.expectedJournal();
                        if (custody.expectedPlacement().shape() instanceof PlayerBodyShape.FreeCapsule) {
                            // The immutable journal predates native tail effects. Recovery must
                            // install this proven endpoint's actual response and consumed fall state.
                            try {
                                var actor = session.player;
                                committedSnapshot = new PlayerPhysicalSnapshot(committedSnapshot.scope(),
                                        committedSnapshot.revision(), committedSnapshot.placement(),
                                        new Vec3d(actor.motionX, actor.motionY, actor.motionZ)
                                                .multiply(1 / GravityEntityBinder.TICK_SECONDS),
                                        Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround,
                                                actor.collidedHorizontally, actor.collidedVertically,
                                                actor.fallDistance, false)));
                            } catch (RuntimeException observationFailure) {
                                if (observationFailure != failure.cause()) failure.cause().addSuppressed(observationFailure);
                                failNativeEnvironment(session, owner, failure.cause(), "committed owner state capture");
                                owner.recoveryTerminal = true;
                                return;
                            }
                        }
                        owner.snapshot = committedSnapshot;
                        retireCardinalPendingAfterFreeCommit(owner);
                        if (custody.expectedPlacement().shape() instanceof PlayerBodyShape.FreeCapsule)
                            owner.travel.ownerReference(new PlayerBodyReference.FreeReference(
                                    owner.travel.ownerReference().manualIntent(), Optional.empty(),
                                    previousEffectiveForFree(owner.travel)));
                        refreshManualReceipt(owner);
                    }
                }
            } else if (exactInstalledGeometry) {
                owner.snapshot = failure.snapshot();
                retireCardinalPendingAfterFreeCommit(owner);
                refreshManualReceipt(owner);
            }
            if (!exactFailureOwner) {
                // Superseded authority cannot be recovered here, but an unexpected callback
                // failure still belongs to this captured operation and must remain observable.
                UnigravMod.LOGGER.error("ugmotion1 owner environment failed: stage=superseded committed owner tail scope={} revision={} installedRevision={} tick={}",
                        before.scope(), before.revision(), failure.snapshot().revision(),
                        owner.lastWorldTick, failure.cause());
                return;
            }
            if (failure.stage() == PlayerPhysicsStep.CommittedFailureStage.CLEARANCE) {
                // The installation custody above records exact geometry or an explicit partial
                // mutation. Neither permits replay or a normal-success STATE for this tick.
                var exactCustody = custodyValue.filter(custody -> custody.ownerIdentity() == owner
                        && custody.actor() == session.player && custody.scope().equals(session.scope)
                        && custody.connection() == session.handler && custody.correctionRevision() == correctionBefore);
                if (exactCustody.isEmpty()) throw failure.cause();
                failNativeEnvironment(session, owner, failure.cause(), "CLEARANCE committed owner tail");
                return;
            }
            if (exactInstalledGeometry
                    && failure.cause() instanceof PlayerPhysicsStep.PostCommitObservationUnavailable) {
                owner.recoveryObservationRequired = true;
                failNativeEnvironment(session, owner, failure.cause(), "post-commit endpoint observation");
                return;
            }
            // Preserve the original cause and fail-fast behavior, but not a fictional pre-move state.
            // Do not replay the failed tail or publish a normal-success STATE from this failed tick.
            if (before.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    || failure.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
                failNativeEnvironment(session, owner, failure.cause(), "committed owner tail");
                return;
            }
            throw failure.cause();
        }
        try {
            settlePendingManualReference(session, owner, before);
        } catch (RuntimeException settlementFailure) {
            if (result instanceof PlayerPhysicsStep.Applied)
                failAppliedOwnerAdmission(session, owner, capturedCustody, settlementFailure,
                        "applied terminal reference settlement");
            else throw settlementFailure;
            return;
        }
        boolean exactCurrentOwner;
        try {
            exactCurrentOwner = current(session) && session.owner == owner;
        } catch (RuntimeException ownerCheckFailure) {
            if (result instanceof PlayerPhysicsStep.Applied)
                failAppliedOwnerAdmission(session, owner, capturedCustody, ownerCheckFailure,
                        "applied terminal owner check");
            else throw ownerCheckFailure;
            return;
        }
        if (!exactCurrentOwner) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            if (result instanceof PlayerPhysicsStep.Applied)
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied Step owner was superseded before publication"),
                        "applied terminal superseded owner");
            return;
        }
        boolean referenceGeometryCommitted = false;
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable
                && unavailable.referenceCommitSnapshot().isPresent())
            referenceGeometryCommitted = commitReferenceGeometryIfInstalled(session, owner, before);
        boolean nativeRequired;
        try {
            nativeRequired = PlayerKinematics.requiresNative(session.player);
        } catch (RuntimeException nativeCheckFailure) {
            if (result instanceof PlayerPhysicsStep.Applied)
                failAppliedOwnerAdmission(session, owner, capturedCustody, nativeCheckFailure,
                        "applied terminal native qualification");
            else throw nativeCheckFailure;
            return;
        }
        if (nativeRequired) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            try {
                handleNativeDelegation(session, outboundStates);
            } catch (RuntimeException delegationFailure) {
                if (result instanceof PlayerPhysicsStep.Applied)
                    failAppliedOwnerAdmission(session, owner, capturedCustody, delegationFailure,
                            "applied terminal native delegation");
                else throw delegationFailure;
            }
            return;
        }
        // Native corrections may be initiated by a world callback during this step.
        if (owner.teleportPending || owner.snapshot != before && !referenceGeometryCommitted) {
            discardUnpromotedTrustedResizeProposal(owner, before);
            if (result instanceof PlayerPhysicsStep.Applied)
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied Step owner entered teleport or changed its snapshot before publication"),
                        "applied terminal owner transition");
            return;
        }
        if (!(result instanceof PlayerPhysicsStep.Unavailable unavailable
                && unavailable.reason().startsWith("Cardinal-to-Free arc proof failed:"))) {
            owner.lastCardinalArcFailureKey = null;
            owner.suppressedCardinalArcFailureCount = 0;
        }
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred) {
            if (eyeCompletion.isEmpty()) discardUnpromotedTrustedResizeProposal(owner, before);
            if (trustedResize.isPresent() && !matchesPhysicalGeometry(session.player, deferred.snapshot())) {
                failNativeEnvironment(session, owner, new IllegalStateException(
                        "trusted native resize deferred without an exact installed owner geometry"),
                        "trusted native resize deferred tail");
                return;
            }
            if (eyeCompletion.isPresent() && (!current(session) || session.owner != owner
                    || owner.snapshot != before || session.connectionHandle != connectionBefore
                    || session.scopeHandle != scopeBefore || owner.travel.correctionRevision() != correctionBefore
                    || owner.travel.mobility().generation() != mobilityGenerationBefore || owner.teleportPending)) return;
            owner.snapshot = deferred.snapshot();
            retireCardinalPendingAfterFreeCommit(owner);
            if (eyeCompletion.isPresent()) {
                rebaseInstalledPhysicalResizeIntent(owner, before, deferred.snapshot(),
                        eyeCompletion.orElseThrow(), session.player.eyeHeight);
                owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
            }
            if (!deferred.tail().reason().equals(owner.lastFailure))
                UnigravMod.LOGGER.warn("ugmotion1 committed move with deferred tail: {}",
                        deferred.tail().reason(), deferred.tail().cause().orElse(null));
            owner.lastFailure = deferred.tail().reason();
            completePredictionSlot(session, owner, predictionSlot, PlayerPhysicsProtocol.StepOutcome.DEFERRED);
            publishAccepted(session, outboundStates);
            return;
        }
        if (result instanceof PlayerPhysicsStep.Unavailable failure) {
            if (!retainBlockedPhysicalResizeIntent(session, owner, before, trustedResizeProposal, worldTick))
                discardUnpromotedTrustedResizeProposal(owner, before);
            if (trustedResize.isPresent() && !matchesPhysicalGeometry(session.player, before)) {
                RuntimeException cause = failure.cause().orElseGet(() -> new IllegalStateException(
                        "trusted native resize preparation unavailable: " + failure.reason()));
                failNativeEnvironment(session, owner, new IllegalStateException(
                        "trusted native resize could not be applied while its effective eye differed from the accepted owner",
                        cause), "trusted native resize unavailable");
                return;
            }
            if (failure.freeMovementDiagnostic().isPresent()) {
                if (owner.freeMovementFailuresSinceLastLog < Long.MAX_VALUE)
                    owner.freeMovementFailuresSinceLastLog++;
                long now = System.nanoTime();
                if (owner.lastFreeMovementFailureLogNanos == 0
                        || now - owner.lastFreeMovementFailureLogNanos >= FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS) {
                    logFreeMovementFailure(session, owner, before, worldTick, stepControls,
                            source.map(GIntentObservation::active).orElse(false),
                            failure.freeMovementDiagnostic().orElseThrow(),
                            owner.freeMovementFailuresSinceLastLog, failure.cause());
                    owner.lastFreeMovementFailureLogNanos = now;
                    owner.freeMovementFailuresSinceLastLog = 0;
                }
            } else if (failure.reason().startsWith("Cardinal-to-Free arc proof failed:")
                    || !failure.reason().equals(owner.lastFailure)) {
                logUnavailableStepFailure(owner, failure);
            }
            owner.lastFailure = failure.reason();
            boolean predictionDeferred = predictionSlot.isPresent() && matchesAcceptedGeometry(session);
            if (predictionDeferred)
                completePredictionSlot(session, owner, predictionSlot, PlayerPhysicsProtocol.StepOutcome.DEFERRED);
            if (owner.manualFailureReceiptPending || predictionDeferred || impulseApplied && matchesAcceptedPlacement(session) || referenceGeometryCommitted
                    || !referenceBefore.equals(owner.travel.ownerReference()) && matchesAcceptedGeometry(session)) {
                refreshManualReceipt(owner);
                publishAccepted(session, outboundStates);
            }
            return;
        }
        try {
            var appliedResult = (PlayerPhysicsStep.Applied) result;
            PlayerPhysicalSnapshot installed = appliedResult.snapshot();
            if (!matchesPhysicalGeometry(session.player, installed)) {
                discardUnpromotedTrustedResizeProposal(owner, before);
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        trustedResize.isPresent()
                                ? "trusted native resize applied without exact target geometry"
                                : "Applied Step lost its exact installed owner geometry"),
                        "applied terminal geometry");
                return;
            }
            if (eyeCompletion.isPresent() && (!current(session) || session.owner != owner
                    || owner.snapshot != before || session.connectionHandle != connectionBefore
                    || session.scopeHandle != scopeBefore || owner.travel.correctionRevision() != correctionBefore
                    || owner.travel.mobility().generation() != mobilityGenerationBefore || owner.teleportPending)) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied Step eye completion lost its exact owner admission"),
                        "applied terminal eye completion");
                return;
            }
            // Only this step's fully validated reference-priority consumption may retire its input Q.
            // Step callbacks still use ownerCurrent and cannot admit a missing or replaced request.
            PlayerAttitudeRequest committedReferencePriorityRequest = consumedReferencePriorityRequest;
            // Native qualification can call actor getters. Recheck the pure input identity afterwards
            // and again after the final geometry/profile getters, without opening another callback window.
            BooleanSupplier commitOwnerIdentity = () -> current(session) && session.owner == owner
                    && owner.snapshot == before && owner.pendingCardinalPose == pendingBefore
                    && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                    && owner.travel.correctionRevision() == correctionBefore
                    && owner.travel.mobility().generation() == mobilityGenerationBefore && !owner.teleportPending
                    && (committedReferencePriorityRequest == null
                        ? attitudeRequest.isEmpty() || owner.pendingAttitudeRequest == attitudeRequest.orElseThrow()
                        : owner.pendingAttitudeRequest == null)
                    && (attitudeRequest.isEmpty() || session.source != null
                        && session.scope.equals(attitudeRequest.orElseThrow().scope())
                        && owner.snapshot.scope().equals(attitudeRequest.orElseThrow().scope())
                        && session.proposal == attitudeRequest.orElseThrow().proposal()
                        && session.connectionHandle == attitudeRequest.orElseThrow().connection()
                        && session.scopeHandle == attitudeRequest.orElseThrow().scopeHandle()
                        && session.world == attitudeRequest.orElseThrow().world()
                        && session.lastIncomingSequence == attitudeRequest.orElseThrow().inputSequence()
                        && owner.inputWorldTick == attitudeRequest.orElseThrow().receivedWorldTick()
                        && owner.travel.correctionRevision() == attitudeRequest.orElseThrow().correctionRevision()
                        && owner.controls.desiredBodyAttitude()
                            .filter(attitudeRequest.orElseThrow().rawDesiredQ()::equals).isPresent()
                        && !owner.nativeDelegated && !owner.resumeOffered && !owner.recoveryPending
                        && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal
                        && !owner.recoveryNativeResume && !owner.nativeRestorePending
                        && !owner.nativeRestoreAwaitingAcknowledgement && !owner.nativeRestoreTerminalFailure
                        && !owner.nativeHandoffPrepared);
            BooleanSupplier commitOwnerCurrent = () -> {
                boolean qualified = committedReferencePriorityRequest == null ? ownerCurrent.getAsBoolean()
                        : attitudeRequest.filter(request -> request == committedReferencePriorityRequest).isPresent()
                            && owner.pendingAttitudeRequest == null
                            && isCurrentAttitudeRequest(session, owner, committedReferencePriorityRequest, worldTick);
                return qualified && session.world.getTotalWorldTime() == worldTick && commitOwnerIdentity.getAsBoolean();
            };
            var predecessorMotion = owner.nativeCardinalMotion;
            boolean continueMotion = predecessorMotion != null && predecessorMotion.bodyCurrent(before)
                    && before.placement().shape() instanceof PlayerBodyShape.Cardinal oldCardinal
                    && installed.placement().shape() instanceof PlayerBodyShape.Cardinal newCardinal
                    && oldCardinal.frame() == newCardinal.frame()
                    && oldCardinal.dimensions().equals(newCardinal.dimensions())
                    && before.placement().attitude().equals(installed.placement().attitude())
                    && predecessorMotion.pose.equals(NativePoseProfile.read(session.player));
            if (!commitOwnerCurrent.getAsBoolean() || !matchesPhysicalGeometry(session.player, installed)) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal owner admission changed before publication"),
                        "applied terminal owner admission");
                return;
            }
            if (continueMotion) continueMotion = predecessorMotion.bodyCurrent(before)
                    && predecessorMotion.pose.equals(NativePoseProfile.read(session.player));
            var completedMotionGeometry = WorldCollisionCandidates.ActorGeometry.read(session.player);
            if (!matchesPhysicalAnchorGeometry(completedMotionGeometry, installed)
                    || session.world.getTotalWorldTime() != worldTick || !commitOwnerIdentity.getAsBoolean()
                    || owner.nativeCardinalMotion != predecessorMotion) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal geometry or owner identity changed before publication"),
                        "applied terminal geometry admission");
                return;
            }
            boolean consumeAttitudeRequest = attitudeRequest.isPresent()
                    && owner.pendingAttitudeRequest == attitudeRequest.orElseThrow()
                    && isCurrentAttitudeRequest(session, owner, attitudeRequest.orElseThrow(), worldTick)
                    && installed.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    && installed.placement().attitude().equals(attitudeRequest.orElseThrow().rawDesiredQ());
            if (eyeCompletion.isPresent()) owner.predictionTimeline.suspendMobility(predictionContext(session, owner));
            refreshManualReceipt(owner);
            if (appliedResult.rejectedAttitudeDiagnostic().isPresent()) {
                if (owner.freeAttitudeRejectionsSinceLastLog < Long.MAX_VALUE)
                    owner.freeAttitudeRejectionsSinceLastLog++;
                long now = System.nanoTime();
                if (owner.lastFreeAttitudeRejectionLogNanos == 0
                        || now - owner.lastFreeAttitudeRejectionLogNanos >= FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS) {
                    UnigravMod.LOGGER.info("ug-free-attitude-rejected scope={} sourceRevision={} resultRevision={} tick={} inputSeq={} motionOutcome=APPLIED attitudeOutcome=RETAINED_PENDING observations={} detail={}",
                            before.scope(), before.revision(), installed.revision(), worldTick,
                            attitudeRequest.map(PlayerAttitudeRequest::inputSequence).orElse(-1L),
                            owner.freeAttitudeRejectionsSinceLastLog,
                            appliedResult.rejectedAttitudeDiagnostic().orElseThrow());
                    owner.lastFreeAttitudeRejectionLogNanos = now;
                    owner.freeAttitudeRejectionsSinceLastLog = 0;
                }
            }
            if (appliedResult.freeMovementStopDiagnostic().isPresent()) {
                var diagnostic = appliedResult.freeMovementStopDiagnostic().orElseThrow();
                long now = System.nanoTime();
                if (diagnostic.kind() == PlayerPhysicsStep.FreeMovementStopKind.FULLY_BLOCKED) {
                    if (owner.freeMovementBlockedStopsSinceLastLog < Long.MAX_VALUE)
                        owner.freeMovementBlockedStopsSinceLastLog++;
                    if (owner.lastFreeMovementBlockedStopLogNanos == 0
                            || now - owner.lastFreeMovementBlockedStopLogNanos >= FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS) {
                        logFreeMovementStop(session, owner, before, worldTick, stepControls,
                                source.map(GIntentObservation::active).orElse(false), diagnostic,
                                owner.freeMovementBlockedStopsSinceLastLog);
                        owner.lastFreeMovementBlockedStopLogNanos = now;
                        owner.freeMovementBlockedStopsSinceLastLog = 0;
                    }
                } else {
                    if (owner.freeMovementPartialStopsSinceLastLog < Long.MAX_VALUE)
                        owner.freeMovementPartialStopsSinceLastLog++;
                    if (owner.lastFreeMovementPartialStopLogNanos == 0
                            || now - owner.lastFreeMovementPartialStopLogNanos >= FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS) {
                        logFreeMovementStop(session, owner, before, worldTick, stepControls,
                                source.map(GIntentObservation::active).orElse(false), diagnostic,
                                owner.freeMovementPartialStopsSinceLastLog);
                        owner.lastFreeMovementPartialStopLogNanos = now;
                        owner.freeMovementPartialStopsSinceLastLog = 0;
                    }
                }
            }
            owner.lastFailure = null;
            owner.lastCardinalArcFailureKey = null;
            owner.suppressedCardinalArcFailureCount = 0;
            completePredictionSlot(session, owner, installed, predictionSlot, PlayerPhysicsProtocol.StepOutcome.APPLIED);
            Optional<PreparedAppliedPublication> preparedPublication = prepareAppliedPublication(
                    session, owner, before, installed);
            if (preparedPublication.isEmpty()) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal STATE offer could not be prepared under the current owner"),
                        "applied terminal STATE preparation");
                return;
            }
            PreparedAppliedPublication publication = preparedPublication.orElseThrow();
            if (!commitOwnerCurrent.getAsBoolean() || !matchesPhysicalGeometry(session.player, installed)) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal owner changed during STATE preparation"),
                        "applied terminal STATE admission");
                return;
            }
            var finalMotionGeometry = WorldCollisionCandidates.ActorGeometry.read(session.player);
            boolean callbackAdmissionCurrent = matchesPhysicalAnchorGeometry(finalMotionGeometry, installed)
                    && session.world.getTotalWorldTime() == worldTick && commitOwnerIdentity.getAsBoolean()
                    && owner.nativeCardinalMotion == predecessorMotion;
            if (!callbackAdmissionCurrent) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal callback admission changed during STATE preparation"),
                        "applied terminal callback admission");
                return;
            }
            if (closedStepAdmissionSeal == null
                    || !closedStepAdmissionSeal.consume(session.player, installed, eyeCompletion.orElse(null))) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal response seal or motion changed before owner publication"),
                        "applied terminal closed-seal admission");
                return;
            }
            boolean pureCommitIdentity = sessions.get(session.manager) == session && session.owner == owner && session.player == exactPlayer
                    && session.world == worldBefore && session.handler == exactHandler
                    && session.connectionHandle == connectionBefore && session.scopeHandle == scopeBefore
                    && session.scope.equals(before.scope()) && session.proposal == proposalBefore
                    && owner.snapshot == before && owner.pendingCardinalPose == pendingBefore
                    && owner.travel.correctionRevision() == correctionBefore
                    && owner.travel.mobility().generation() == mobilityGenerationBefore
                    && !owner.teleportPending && owner.nativeCardinalMotion == predecessorMotion
                    && exactPlayer.world == worldBefore && exactPlayer.dimension == before.scope().dimension()
                    && exactPlayer.connection == exactHandler && exactHandler.player == exactPlayer
                    && !exactPlayer.isDead && !worldBefore.captureBlockSnapshots && !worldBefore.restoringBlockSnapshots
                    && (committedReferencePriorityRequest == null
                        ? attitudeRequest.isEmpty() || owner.pendingAttitudeRequest == attitudeRequest.orElseThrow()
                        : owner.pendingAttitudeRequest == null)
                    && (attitudeRequest.isEmpty() || session.source != null
                        && session.scope.equals(attitudeRequest.orElseThrow().scope())
                        && before.scope().equals(attitudeRequest.orElseThrow().scope())
                        && session.proposal == attitudeRequest.orElseThrow().proposal()
                        && session.connectionHandle == attitudeRequest.orElseThrow().connection()
                        && session.scopeHandle == attitudeRequest.orElseThrow().scopeHandle()
                        && session.world == attitudeRequest.orElseThrow().world()
                        && session.lastIncomingSequence == attitudeRequest.orElseThrow().inputSequence()
                        && owner.inputWorldTick == attitudeRequest.orElseThrow().receivedWorldTick()
                        && owner.travel.correctionRevision() == attitudeRequest.orElseThrow().correctionRevision()
                        && owner.controls.desiredBodyAttitude()
                            .filter(attitudeRequest.orElseThrow().rawDesiredQ()::equals).isPresent()
                        && !owner.nativeDelegated && !owner.resumeOffered && !owner.recoveryPending
                        && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror && !owner.recoveryTerminal
                        && !owner.recoveryNativeResume && !owner.nativeRestorePending
                        && !owner.nativeRestoreAwaitingAcknowledgement && !owner.nativeRestoreTerminalFailure
                        && !owner.nativeHandoffPrepared)
                    && publication.expectedOwnerSnapshot() == before
                    && publication.installedSnapshot() == installed
                    && session.outgoingSequence == publication.expectedOutgoingSequence()
                    && session.predictionGeneration == publication.predictionGeneration()
                    && owner.travel.ownerReference() == publication.ownerReference()
                    && owner.bodyReferenceReceipt == publication.bodyReferenceReceipt()
                    && owner.travel.cardinalReference().equals(publication.cardinalReference())
                    && owner.travel.mobilityState(installed).equals(publication.mobilityState())
                    && owner.nativeTeleportId.equals(publication.nativeTeleportId())
                    && owner.eyeProfilePublicationPending == publication.eyeProfilePublicationPending();
            if (!pureCommitIdentity) {
                failAppliedOwnerAdmission(session, owner, capturedCustody, new IllegalStateException(
                        "Applied terminal pure owner identity changed after final raw-motion admission"),
                        "applied terminal pure commit fence");
                return;
            }
            owner.snapshot = installed;
            retireCardinalPendingAfterFreeCommit(owner);
            owner.nativeCardinalMotion = continueMotion ? predecessorMotion.withSnapshot(installed) : null;
            if (eyeCompletion.isPresent()) {
                rebaseInstalledPhysicalResizeIntent(owner, before, installed, eyeCompletion.orElseThrow(),
                        session.player.eyeHeight);
            } else promoteTrustedResizeProposalAfterInstalledTick(owner, before, installed, session.player.eyeHeight);
            if (consumeAttitudeRequest && owner.pendingAttitudeRequest == attitudeRequest.orElseThrow())
                invalidateAttitudeRequest(owner);
            session.outgoingSequence = publication.nextOutgoingSequence();
            owner.lastStateTick = serverTick;
            owner.manualFailureReceiptPending = false;
            if (owner.eyeProfilePublicationPending == installed) owner.eyeProfilePublicationPending = null;
            outboundStates.add(publication.offer());
        } catch (RuntimeException publicationFailure) {
            failAppliedOwnerAdmission(session, owner, capturedCustody, publicationFailure,
                    "applied terminal publication");
        }
    }

    /** Commit/custody checks have already accepted this snapshot. A Cardinal resize token
     * cannot survive its source geometry, including a committed move whose tail failed. */
    private static void retireCardinalPendingAfterFreeCommit(Owned owner) {
        if (owner.snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
            owner.pendingCardinalPose = null;
    }

    private static void logFreeMovementFailure(Session session, Owned owner, PlayerPhysicalSnapshot accepted,
            long worldTick, PlayerPhysicsProtocol.Controls controls, boolean personalG, String pathDiagnostic,
            long failuresSinceLastLog, Optional<RuntimeException> cause) {
        long inputSequence = owner.latestAdmittedInput == null ? -1 : owner.latestAdmittedInput.sequence();
        String message = "ugmotion1 step deferred: scope=" + accepted.scope()
                + " revision=" + accepted.revision()
                + " worldTick=" + worldTick
                + " latestAdmittedInputSequence=" + inputSequence
                + " controls=" + controls
                + " mobility=" + owner.travel.mobilityState(accepted)
                + " personalG=" + personalG
                + " creative=" + session.player.interactionManager.isCreative()
                + " flying=" + session.player.capabilities.isFlying
                + " failuresSinceLastLog=" + failuresSinceLastLog
                + " path={" + pathDiagnostic + "}";
        if (cause.isPresent()) UnigravMod.LOGGER.warn(message, cause.orElseThrow());
        else UnigravMod.LOGGER.warn(message);
    }

    private static void logFreeMovementStop(Session session, Owned owner, PlayerPhysicalSnapshot accepted,
            long worldTick, PlayerPhysicsProtocol.Controls controls, boolean personalG,
            PlayerPhysicsStep.FreeMovementStopDiagnostic diagnostic, long stopsSinceLastLog) {
        long inputSequence = owner.latestAdmittedInput == null ? -1 : owner.latestAdmittedInput.sequence();
        String message = "ugmotion1 committed bounded movement stop: scope=" + accepted.scope()
                + " revision=" + accepted.revision() + " worldTick=" + worldTick
                + " latestAdmittedInputSequence=" + inputSequence + " controls=" + controls
                + " mobility=" + owner.travel.mobilityState(accepted) + " personalG=" + personalG
                + " creative=" + session.player.interactionManager.isCreative()
                + " flying=" + session.player.capabilities.isFlying + " path={" + diagnostic.detail() + "}";
        if (diagnostic.kind() == PlayerPhysicsStep.FreeMovementStopKind.FULLY_BLOCKED) {
            UnigravMod.LOGGER.debug(message + " fullyBlockedStopsSinceLastLog={}", stopsSinceLastLog);
        } else {
            UnigravMod.LOGGER.warn(message + " partialStopsSinceLastLog={}", stopsSinceLastLog);
        }
    }

    /** Low-rate causal snapshot for accepted controls; it never samples the actor, world, or input again. */
    private static void logServerControlProbe(Session session, Owned owner, PlayerPhysicalSnapshot accepted,
            long worldTick, PlayerPhysicsProtocol.Controls controls, Optional<GIntentObservation> gObservation,
            boolean creative, boolean capabilityFlying, boolean gActive, boolean nativeFlight, boolean gFlight,
            PlayerMobilityIntent.Tick mobilityTick, boolean freshInput, int detachThreshold,
            PlayerPhysicsStep.Result result, RuntimeException stepFailure,
            Optional<FreeOwnerTransaction.InstallationCustody> custody,
            Optional<FreeOwnerTransaction.TerminalMotionReceipt> terminalMotion,
            FreeOwnerTransaction transaction) {
        if (!UnigravMod.LOGGER.isInfoEnabled()) return;

        var mobility = owner.travel.mobility();
        if (owner.locomotionProbe.shouldEmit(accepted.scope(), worldTick, controls, capabilityFlying, gActive)) {
            UnigravMod.LOGGER.info("ug-locomotion-transition stage=server-step-result scope={} worldTick={}"
                            + " admittedInputSeq={} predictionInputState={} jump={} held={} sneak={} flying={} personalG={}"
                            + " freshInput={} translation={} before={} after={} disposition={} pendingPose={} proneTurnRejected={}",
                    accepted.scope(), worldTick,
                    owner.latestAdmittedInput == null ? -1 : owner.latestAdmittedInput.sequence(),
                    owner.predictionTimeline.diagnostics(), controls.jump(), controls.mobility().held(), controls.sneak(),
                    capabilityFlying, gActive, freshInput, controls.translation(), accepted,
                    serverControlResultSnapshot(result, accepted), serverControlDisposition(result, stepFailure),
                    owner.pendingCardinalPose != null, owner.travel.proneTransitionRejection());
        }
        var mobilityState = owner.travel.mobilityState(accepted);
        var input = controls.mobility();
        long admittedSequence = owner.latestAdmittedInput == null ? -1 : owner.latestAdmittedInput.sequence();
        GIntentObservation observedG = gObservation.orElse(null);
        String exceptionalOutcome = serverControlExceptionalOutcome(result, stepFailure);
        var stateKey = new ServerControlProbeKey(accepted.scope(),
                observedG == null ? null : observedG.source(),
                observedG == null ? -1 : observedG.sourceEpoch(),
                observedG == null ? -1 : observedG.revision(), observedG != null && observedG.active(),
                creative, mobility.mode(), mobility.hover(), mobilityState.effectiveMode(),
                mobilityState.hoverStatus(), mobilityState.reason(), nativeFlight, gFlight,
                input.focused(), result == null ? null : result.getClass(), exceptionalOutcome);
        boolean stateChanged = !Objects.equals(owner.lastObservedServerControlProbeKey, stateKey);
        if (stateChanged) owner.lastObservedServerControlProbeKey = stateKey;

        boolean shortJumpOrDetach = mobilityTick.shortJump() || mobilityTick.detachedNow();
        boolean unexpectedDisposition = stepFailure != null || result instanceof PlayerPhysicsStep.Unavailable
                || result instanceof PlayerPhysicsStep.CommittedFailure
                || result instanceof PlayerPhysicsStep.CommittedDeferred
                || result instanceof PlayerPhysicsStep.CommittedTailStop;
        updateServerControlJumpProbe(session, owner, accepted, worldTick, controls, admittedSequence,
                freshInput, result, stepFailure, mobilityTick);
        if (shortJumpOrDetach || unexpectedDisposition) {
            String disposition = serverControlDisposition(result, stepFailure);
            PlayerPhysicalSnapshot after = serverControlResultSnapshot(result, accepted);
            Vec3d sourceUp = accepted.placement().bodyUp();
            String event = "seq=" + admittedSequence + "/mobilitySeq=" + mobility.inputSequence()
                    + ",press=" + input.pressId() + ",held=" + input.held() + ",focused=" + input.focused()
                    + ",threshold=" + detachThreshold + ",jumpEdge=" + mobilityTick.shortJump()
                    + ",detachedNow=" + mobilityTick.detachedNow()
                    + ",ground=" + serverControlGround(accepted) + "->" + serverControlGround(after)
                    + ",resultSnapshotSource=" + serverControlResultSnapshotSource(result)
                    + ",acceptedBodyUp=" + sourceUp
                    + ",upVelocity=" + serverControlUpVelocity(accepted.worldVelocity(), sourceUp)
                    + "->" + serverControlUpVelocity(after.worldVelocity(), sourceUp)
                    + ",disposition=" + disposition;
            if (stepFailure != null) event += ",cause=" + stepFailure.getClass().getSimpleName()
                    + ":" + boundedControlText(stepFailure.getMessage(), 180);
            else {
                RuntimeException resultCause = serverControlCause(result);
                if (resultCause != null) event += ",cause=" + resultCause.getClass().getSimpleName()
                        + ":" + boundedControlText(resultCause.getMessage(), 180);
            }
            if (owner.pendingServerControlProbeEventCount == 0)
                owner.pendingServerControlProbeFirstEvent = boundedControlText(event, 720);
            owner.pendingServerControlProbeLastEvent = boundedControlText(event, 720);
            if (owner.pendingServerControlProbeEventCount < Long.MAX_VALUE)
                owner.pendingServerControlProbeEventCount++;
        }

        long sinceEmission = owner.lastServerControlProbeTick == Long.MIN_VALUE
                || worldTick < owner.lastServerControlProbeTick ? Long.MAX_VALUE
                : worldTick - owner.lastServerControlProbeTick;
        boolean hasPendingEvent = owner.pendingServerControlProbeEventCount != 0;
        boolean emit = owner.lastServerControlProbeTick == Long.MIN_VALUE
                || sinceEmission >= SERVER_CONTROL_PROBE_SUMMARY_INTERVAL_TICKS
                || sinceEmission >= SERVER_CONTROL_PROBE_MIN_INTERVAL_TICKS
                    && (stateChanged || hasPendingEvent);
        if (!emit) {
            if (stateChanged || shortJumpOrDetach || unexpectedDisposition) {
                if (owner.suppressedServerControlProbeCount < Long.MAX_VALUE)
                    owner.suppressedServerControlProbeCount++;
                owner.suppressedServerControlProbeOutcome = exceptionalOutcome != null
                        ? boundedControlText(exceptionalOutcome, 180)
                        : shortJumpOrDetach ? "jump-or-detach-edge" : "state-change";
            }
            return;
        }

        String disposition = serverControlDisposition(result, stepFailure);
        String request = input.request().map(value -> value.id() + ":" + value.mode()
                + ":" + value.hover()).orElse("none");
        String custodyIdentity = custody.map(value -> value.stage() + "/journal="
                + value.expectedJournal().revision() + "/placement@"
                + Integer.toHexString(System.identityHashCode(value.expectedPlacement())))
                .orElse("none");
        String terminalIdentity = terminalMotion.map(value -> Integer.toHexString(System.identityHashCode(value)))
                .orElse("none");
        PlayerPhysicalSnapshot after = serverControlResultSnapshot(result, accepted);
        long numericUnits = transaction == null ? -1 : transaction.numeric().units();
        long chbCalls = transaction == null ? -1 : transaction.queries().calls();
        CapsuleContactQueryBudget.Totals chb = transaction == null ? null : transaction.queries().work();
        long coalesced = owner.suppressedServerControlProbeCount;
        long pendingEvents = owner.pendingServerControlProbeEventCount;
        String firstEvent = owner.pendingServerControlProbeFirstEvent == null
                ? "none" : owner.pendingServerControlProbeFirstEvent;
        String lastEvent = owner.pendingServerControlProbeLastEvent == null
                ? "none" : owner.pendingServerControlProbeLastEvent;
        UnigravMod.LOGGER.info("ug-control-probe stage=server-step-result scope={} revision={} worldTick={} incomingSeq={} admittedInputSeq={}"
                        + " mobilitySeq={} gObservation={} gCreativeQualified={} mobility={} effective={} hoverStatus={}"
                        + " mobilityReason={} mobilityRequest={} press={} held={} focused={} freshInput={}"
                        + " detachHeldTicks={}/{} shortJump={} detachedNow={} creative={} capabilityFlying={} nativeFlight={} gFlight={}"
                        + " acceptedShape={} controlsTranslation={} controlsJump={} controlsSneak={} controlsSprint={} controlsBoost={}"
                        + " bodyQPresent={} bodyReferenceIntent={} disposition={} custody={} terminalReceipt={}"
                        + " ground={} resultSnapshotSource={} ugNumericUnits={} chbCalls={} chbExactAttempts={} chbExactOps={}"
                        + " chbCandidateUnits={} chbRefinementSteps={} coalescedObservations={}"
                        + " coalescedLastOutcome={} eventCount={} firstEvent={} lastEvent={}"
                        + " queryDiagnostics={} chbExecutedWork={} ugPostprocessing={} nativeQueryReservations={}"
                        + " ugNumericPeakBits={} predictionInputState={} equipmentFlight={} capabilityFlySpeed={}"
                        + " acceptedVelocityMps={} resultVelocityMps={}",
                accepted.scope(), accepted.revision(), worldTick, session.lastIncomingSequence, admittedSequence,
                mobility.inputSequence(), gObservation.map(observation -> observation.source() + "/key="
                        + observation.key() + "/epoch=" + observation.sourceEpoch() + "/revision="
                        + observation.revision() + "/active=" + observation.active()).orElse("unobserved"),
                creative && gActive, mobility.mode(), mobilityState.effectiveMode(), mobilityState.hoverStatus(),
                mobilityState.reason(), request, input.pressId(), input.held(), input.focused(), freshInput,
                mobility.detachHeldTicks(), detachThreshold, mobilityTick.shortJump(), mobilityTick.detachedNow(),
                creative, capabilityFlying, nativeFlight, gFlight, accepted.placement().shape(),
                controls.translation(), controls.jump(), controls.sneak(), controls.sprint(), controls.boost(),
                controls.desiredBodyAttitude().isPresent(), controls.bodyReferenceIntent(), disposition, custodyIdentity,
                terminalIdentity, serverControlGround(after), serverControlResultSnapshotSource(result),
                numericUnits, chbCalls,
                chb == null ? -1 : chb.exactAttempts(), chb == null ? -1 : chb.exactOps(),
                chb == null ? -1 : chb.totalCandidateUnits(), chb == null ? -1 : chb.refinementSteps(),
                coalesced, owner.suppressedServerControlProbeOutcome, pendingEvents, firstEvent, lastEvent,
                transaction == null ? "not-observed" : transaction.queries().diagnostics(), chb,
                transaction == null ? "not-observed" : transaction.resources().postprocessing().usage(),
                transaction == null ? -1 : transaction.resources().nativeAttempts(),
                transaction == null ? -1 : transaction.numeric().peakBits(), owner.predictionTimeline.diagnostics(),
                owner.travel.equipmentFlight(accepted, worldTick), session.player.capabilities.getFlySpeed(),
                accepted.worldVelocity(), after.worldVelocity());
        owner.lastServerControlProbeTick = worldTick;
        owner.suppressedServerControlProbeCount = 0;
        owner.suppressedServerControlProbeOutcome = "none";
        owner.pendingServerControlProbeEventCount = 0;
        owner.pendingServerControlProbeFirstEvent = null;
        owner.pendingServerControlProbeLastEvent = null;
    }

    private static String serverControlDisposition(PlayerPhysicsStep.Result result, RuntimeException failure) {
        if (failure != null) return "STEP_EXCEPTION";
        if (result == null) return "NO_RESULT";
        if (result instanceof PlayerPhysicsStep.Applied applied)
            return applied.freeMovementStopDiagnostic().map(diagnostic -> "APPLIED_" + diagnostic.kind())
                    .orElse("APPLIED");
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable)
            return "UNAVAILABLE:" + boundedControlText(unavailable.reason(), 180);
        if (result instanceof PlayerPhysicsStep.CommittedFailure failed)
            return "COMMITTED_FAILURE:" + failed.stage();
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred)
            return "COMMITTED_DEFERRED:" + boundedControlText(deferred.tail().reason(), 180);
        if (result instanceof PlayerPhysicsStep.CommittedTailStop stopped)
            return "COMMITTED_TAIL_STOP:nativeTransition=" + stopped.nativeTransition();
        return result.getClass().getSimpleName();
    }

    private static String serverControlExceptionalOutcome(PlayerPhysicsStep.Result result,
            RuntimeException failure) {
        if (failure != null) return "STEP_EXCEPTION:" + failure.getClass().getName();
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable)
            return "UNAVAILABLE:" + boundedControlText(unavailable.reason(), 180);
        if (result instanceof PlayerPhysicsStep.CommittedFailure failed)
            return "COMMITTED_FAILURE:" + failed.stage() + ":" + failed.cause().getClass().getName();
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred)
            return "COMMITTED_DEFERRED:" + boundedControlText(deferred.tail().reason(), 180);
        if (result instanceof PlayerPhysicsStep.CommittedTailStop stopped)
            return "COMMITTED_TAIL_STOP:nativeTransition=" + stopped.nativeTransition();
        if (result instanceof PlayerPhysicsStep.Applied applied)
            return applied.freeMovementStopDiagnostic().map(diagnostic -> "APPLIED_STOP:" + diagnostic.kind())
                    .orElse(null);
        return null;
    }

    private static RuntimeException serverControlCause(PlayerPhysicsStep.Result result) {
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable)
            return unavailable.cause().orElse(null);
        if (result instanceof PlayerPhysicsStep.CommittedFailure failure) return failure.cause();
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred)
            return deferred.tail().cause().orElse(null);
        return null;
    }

    private static PlayerPhysicalSnapshot serverControlResultSnapshot(PlayerPhysicsStep.Result result,
            PlayerPhysicalSnapshot fallback) {
        if (result instanceof PlayerPhysicsStep.Applied applied) return applied.snapshot();
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable)
            return unavailable.referenceCommitSnapshot().orElse(fallback);
        if (result instanceof PlayerPhysicsStep.CommittedFailure failure) return failure.snapshot();
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred) return deferred.snapshot();
        if (result instanceof PlayerPhysicsStep.CommittedTailStop stopped) return stopped.snapshot();
        return fallback;
    }

    private static String serverControlResultSnapshotSource(PlayerPhysicsStep.Result result) {
        if (result instanceof PlayerPhysicsStep.Unavailable unavailable)
            return unavailable.referenceCommitSnapshot().isPresent()
                    ? "reference-commit-snapshot" : "last-accepted-no-result-snapshot";
        return result == null ? "last-accepted-no-result" : "step-result-snapshot";
    }

    private static void logUnavailableStepFailure(Owned owner, PlayerPhysicsStep.Unavailable failure) {
        String reason = failure.reason();
        if (!reason.startsWith("Cardinal-to-Free arc proof failed:")) {
            owner.lastCardinalArcFailureKey = null;
            UnigravMod.LOGGER.warn("ugmotion1 step deferred: {}", reason, failure.cause().orElse(null));
            return;
        }
        String category = cardinalArcFailureCategory(reason);
        String causeType = failure.cause().map(cause -> cause.getClass().getName()).orElse("none");
        CardinalArcFailureKey key = new CardinalArcFailureKey(category, causeType);
        String rootCause = summarizeCardinalArcFailure(reason);
        if (!key.equals(owner.lastCardinalArcFailureKey)) {
            // Keep the first full reason and its original cause/stack for each stable
            // proof category and cause type. Request Q/enclosure details may change every
            // tick and do not create a new failure class by themselves.
            UnigravMod.LOGGER.warn("ugmotion1 step deferred: {}", reason, failure.cause().orElse(null));
            owner.lastCardinalArcFailureKey = key;
            owner.suppressedCardinalArcFailureCount = 0;
            owner.lastCardinalArcFailureSummary = rootCause;
            owner.lastCardinalArcFailureSummaryNanos = System.nanoTime();
            return;
        }
        if (owner.suppressedCardinalArcFailureCount < Long.MAX_VALUE)
            owner.suppressedCardinalArcFailureCount++;
        owner.lastCardinalArcFailureSummary = rootCause;
        long now = System.nanoTime();
        if (owner.lastCardinalArcFailureSummaryNanos == 0L
                || now - owner.lastCardinalArcFailureSummaryNanos >= FREE_MOVEMENT_FAILURE_LOG_INTERVAL_NANOS) {
            RuntimeException cause = failure.cause().orElse(null);
            UnigravMod.LOGGER.warn("ugmotion1 repeated Cardinal-to-Free arc proof failure: count={} category={} lastFailure={} causeType={} causeMessage={}",
                    owner.suppressedCardinalArcFailureCount, category, owner.lastCardinalArcFailureSummary,
                    causeType,
                    cause == null ? "none" : boundedControlText(cause.getMessage(), 180));
            owner.suppressedCardinalArcFailureCount = 0;
            owner.lastCardinalArcFailureSummaryNanos = now;
        }
    }

    private static String cardinalArcFailureCategory(String reason) {
        int categoryStart = "Cardinal-to-Free arc proof failed:".length();
        int categoryEnd = reason.indexOf(';', categoryStart);
        String category = (categoryEnd < 0 ? reason.substring(categoryStart)
                : reason.substring(categoryStart, categoryEnd)).trim();
        for (CapsuleArcValidator.Failure known : CapsuleArcValidator.Failure.values())
            if (known.name().equals(category)) return category;
        return "UNCLASSIFIED:" + boundedControlText(category, 96);
    }

    private static String summarizeCardinalArcFailure(String reason) {
        int contextStart = reason.indexOf("; scope=");
        String summary = contextStart < 0 ? reason : reason.substring(0, contextStart);
        return boundedControlText(summary, 240);
    }

    private static String serverControlGround(PlayerPhysicalSnapshot snapshot) {
        return snapshot.contacts().map(contacts -> Boolean.toString(contacts.onGround())).orElse("unknown");
    }

    private static String serverControlUpVelocity(Vec3d velocity, Vec3d up) {
        double value = velocity.x() * up.x() + velocity.y() * up.y() + velocity.z() * up.z();
        return Double.isFinite(value) ? Double.toString(value) : "nonfinite";
    }

    private static String boundedControlText(String value, int maximum) {
        if (value == null) return "none";
        String normalized = value.replace('\r', ' ').replace('\n', ' ');
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum) + "...";
    }

    private record ServerControlProbeKey(PlayerPhysicalSnapshot.Scope scope,
            GIntentObservation.Source gSource, long gEpoch, long gRevision, boolean gActive,
            boolean creative, PlayerMobilityIntent.Mode mobilityMode, PlayerMobilityIntent.Hover hover,
            PlayerMobilityIntent.EffectiveMode effectiveMode, PlayerMobilityIntent.HoverStatus hoverStatus,
            PlayerMobilityIntent.Reason mobilityReason, boolean nativeFlight, boolean gFlight,
            boolean focused, Class<?> resultType, String outcome) { }

    private record CardinalArcFailureKey(String category, String causeType) { }

    private static void updateServerControlJumpProbe(Session session, Owned owner, PlayerPhysicalSnapshot accepted,
            long worldTick, PlayerPhysicsProtocol.Controls controls, long admittedSequence, boolean freshInput,
            PlayerPhysicsStep.Result result, RuntimeException stepFailure,
            PlayerMobilityIntent.Tick mobilityTick) {
        PlayerPhysicalSnapshot after = serverControlResultSnapshot(result, accepted);
        var callbackOutcome = owner.travel.jumpOutcomeForRevision(accepted.revision());
        JumpFollowUpProbe active = owner.jumpFollowUpProbe;
        if (active != null) {
            if (!active.scope.equals(accepted.scope())) {
                finishServerControlJumpProbe(owner, active, "scope-changed");
            } else if (worldTick <= active.lastTick) {
                return;
            }
        }
        if (owner.jumpFollowUpProbe == null) {
            // Held auto-jumps have no new key edge. Observe the actual callback outcome as well.
            if (!mobilityTick.shortJump() && callbackOutcome != PlayerPhysicsProtocol.JumpOutcome.APPLIED) return;
            owner.jumpFollowUpProbe = new JumpFollowUpProbe(accepted.scope(), worldTick,
                    accepted.placement().center(), accepted.placement().bodyUp());
        }
        active = owner.jumpFollowUpProbe;
        active.samples.add(serverControlJumpSample(accepted, after, active, worldTick,
                serverControlDisposition(result, stepFailure), serverControlResultSnapshotSource(result))
                + ",inputSeq=" + admittedSequence + ",freshInput=" + freshInput + ",jumpHeld=" + controls.jump()
                + ",callbackOutcome=" + callbackOutcome + ",completedCooldown=" + owner.travel.predictionJumpCooldown()
                + ",nativeMotionBlocksPerTick=(" + session.player.motionX + "," + session.player.motionY
                + "," + session.player.motionZ + ")");
        active.lastTick = worldTick;
        boolean grounded = after.contacts().map(contacts -> contacts.onGround()).orElse(false);
        if (!grounded) active.observedAirborne = true;
        if (stepFailure != null || result instanceof PlayerPhysicsStep.Unavailable
                || result instanceof PlayerPhysicsStep.CommittedFailure
                || result instanceof PlayerPhysicsStep.CommittedDeferred
                || result instanceof PlayerPhysicsStep.CommittedTailStop)
            finishServerControlJumpProbe(owner, active, "step-did-not-apply");
        else if (grounded && (active.observedAirborne || !controls.jump()))
            finishServerControlJumpProbe(owner, active, active.observedAirborne ? "landed" : "released-before-launch");
        else if (active.samples.size() >= 32) {
            // Bound diagnostic storage, not physics or the duration of a held jump sequence.
            logServerControlJumpProbe(active, "continued-next-chunk");
            active.samples.clear();
        }
    }

    private static String serverControlJumpSample(PlayerPhysicalSnapshot accepted, PlayerPhysicalSnapshot result,
            JumpFollowUpProbe probe, long worldTick, String disposition, String snapshotSource) {
        Vec3d resultCenter = result.placement().center();
        Vec3d startCenter = probe.startCenter;
        Vec3d up = probe.sourceUp;
        double displacement = (resultCenter.x() - startCenter.x()) * up.x()
                + (resultCenter.y() - startCenter.y()) * up.y()
                + (resultCenter.z() - startCenter.z()) * up.z();
        return "tick=" + worldTick + ",acceptedRev=" + accepted.revision() + ",resultRev=" + result.revision()
                + ",ground=" + serverControlGround(accepted) + "->" + serverControlGround(result)
                + ",resultSnapshotSource=" + snapshotSource
                + ",upVelocity=" + serverControlUpVelocity(accepted.worldVelocity(), up)
                + "->" + serverControlUpVelocity(result.worldVelocity(), up)
                + ",upDisplacementFromJump=" + (Double.isFinite(displacement)
                        ? Double.toString(displacement) : "nonfinite")
                + ",disposition=" + boundedControlText(disposition, 180);
    }

    private static void finishServerControlJumpProbe(Owned owner, JumpFollowUpProbe probe, String reason) {
        if (owner.jumpFollowUpProbe != probe) return;
        logServerControlJumpProbe(probe, reason);
        owner.jumpFollowUpProbe = null;
    }

    private static void logServerControlJumpProbe(JumpFollowUpProbe probe, String reason) {
        UnigravMod.LOGGER.info("ug-control-jump-followup scope={} startTick={} startCenter={} sourceUp={}"
                        + " samples={} endReason={} trace={}",
                probe.scope, probe.startTick, probe.startCenter, probe.sourceUp, probe.samples.size(), reason,
                String.join(" || ", probe.samples));
    }

    private static final class JumpFollowUpProbe {
        private final PlayerPhysicalSnapshot.Scope scope;
        private final long startTick;
        private final Vec3d startCenter;
        private final Vec3d sourceUp;
        private final ArrayList<String> samples = new ArrayList<>(32);
        private long lastTick = Long.MIN_VALUE;
        private boolean observedAirborne;

        private JumpFollowUpProbe(PlayerPhysicalSnapshot.Scope scope, long startTick,
                Vec3d startCenter, Vec3d sourceUp) {
            this.scope = scope;
            this.startTick = startTick;
            this.startCenter = startCenter;
            this.sourceUp = sourceUp;
        }
    }

    private boolean applyPendingImpulse(Session session) {
        Owned owner = session.owner;
        Vec3 pending = owner.pendingImpulse;
        if (pending.x() == 0 && pending.y() == 0 && pending.z() == 0 || !matchesAcceptedPlacement(session)) return false;
        var previous = owner.snapshot;
        var velocity = previous.worldVelocity();
        Vec3 next = new Vec3(velocity.x(), velocity.y(), velocity.z()).add(pending);
        double speed = Math.hypot(Math.hypot(next.x(), next.y()), next.z());
        if (!Double.isFinite(speed)) throw new IllegalArgumentException("Impulse velocity overflow");
        double ceiling = owner.budget.ceilingMps();
        if (speed > ceiling) next = next.scale(ceiling / speed);
        var snapshot = new PlayerPhysicalSnapshot(previous.scope(), Math.incrementExact(previous.revision()),
                previous.placement(), new Vec3d(next.x(), next.y(), next.z()), previous.contacts());
        var predecessorMotion = owner.nativeCardinalMotion;
        boolean continueMotion = predecessorMotion != null && predecessorMotion.current(session.player, previous);
        if (!continueMotion && owner.nativeCardinalMotion == null) predecessorMotion = null;
        if (!matchesAcceptedPlacement(session) || !current(session) || session.owner != owner
                || owner.snapshot != previous || owner.nativeCardinalMotion != predecessorMotion) return false;
        // No movement admission, budget advance, position write or credit grant occurs here.
        owner.snapshot = snapshot;
        owner.nativeCardinalMotion = continueMotion ? predecessorMotion.withSnapshot(snapshot) : null;
        owner.pendingImpulse = Vec3.ZERO;
        session.player.motionX = next.x() * .05;
        session.player.motionY = next.y() * .05;
        session.player.motionZ = next.z() * .05;
        return true;
    }

    private static boolean matchesAcceptedPlacement(Session session) {
        return matchesPlacement(session.player, session.owner.snapshot.placement());
    }

    private static boolean matchesPlacement(EntityPlayerMP actor, PlayerBodyPlacement accepted) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(actor);
        return actual.position().equals(accepted.transport()) && actual.bounds().equals(accepted.bounds())
                && Float.floatToIntBits(actual.width()) == Float.floatToIntBits((float) accepted.shape().dimensions().width())
                && Float.floatToIntBits(actual.height()) == Float.floatToIntBits((float) accepted.shape().dimensions().height());
    }

    private boolean matchesAcceptedGeometry(Session session) {
        if (!matchesAcceptedPlacement(session)) return false;
        return matchesPhysicalGeometry(session.player, session.owner.snapshot);
    }

    private boolean commitReferenceGeometryIfInstalled(Session session, Owned owner,
                                                       PlayerPhysicalSnapshot previous) {
        PlayerPhysicalSnapshot referenceCommit = owner.travel.referenceCommitSnapshot();
        if (referenceCommit == null || !current(session) || session.owner != owner || owner.snapshot != previous
                || owner.teleportPending || !matchesPhysicalGeometry(session.player, referenceCommit)) return false;
        owner.snapshot = referenceCommit;
        refreshManualReceipt(owner);
        return true;
    }

    private boolean matchesPhysicalGeometry(EntityPlayerMP player, PlayerPhysicalSnapshot accepted) {
        var placement = accepted.placement();
        if (!matchesPhysicalAnchorGeometry(player, accepted)) return false;
        float eye = player.getEyeHeight();
        if (eye == placement.shape().dimensions().eyeHeight()) return true;
        PendingCardinalPose pending = pendingCardinalPose(player);
        return pending != null && pending.matchesSnapshot(player, accepted, eye);
    }

    /** Native pose flags may change effective eye height before updateSize, but cannot move the owner anchor. */
    private static boolean matchesPhysicalAnchorGeometry(EntityPlayerMP player, PlayerPhysicalSnapshot accepted) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(player);
        return matchesPhysicalAnchorGeometry(actual, accepted);
    }

    private static boolean matchesPhysicalAnchorGeometry(WorldCollisionCandidates.ActorGeometry actual,
            PlayerPhysicalSnapshot accepted) {
        var placement = accepted.placement();
        return actual.position().equals(placement.transport()) && actual.bounds().equals(placement.bounds())
                && Float.floatToIntBits(actual.width()) == Float.floatToIntBits((float) placement.shape().dimensions().width())
                && Float.floatToIntBits(actual.height()) == Float.floatToIntBits((float) placement.shape().dimensions().height());
    }

    /** Repeat the immutable accepted state, not a new movement, credit grant or physical revision. */
    private void publishAccepted(Session session, ArrayList<Offer> outboundStates) {
        if (session.owner.recoveryPending || session.owner.recoveryAwaitingApply
                || session.owner.recoveryAwaitingMirror || session.owner.recoveryTerminal) return;
        Owned owner = session.owner;
        var snapshot = owner.snapshot;
        // Native CP no longer reaches serverUpdateMovingPlayer for this owner. Preserve the existing
        // subscription lifecycle, but never enroll an actor that native login has not registered.
        var player = session.player;
        var entry = session.world.getPlayerChunkMap().getEntry((int) player.managedPosX >> 4, (int) player.managedPosZ >> 4);
        if (entry != null && entry.containsPlayer(player)) player.getServer().getPlayerList().serverUpdateMovingPlayer(player);
        if (!current(session) || session.owner != owner || owner.snapshot != snapshot
                || owner.teleportPending || PlayerKinematics.requiresNative(player) && !owner.holdsNativeGeometry()) return;
        var seed = owner.travel.predictionSeed(snapshot);
        long correction = owner.travel.correctionRevision();
        var publication = publishedPrediction(session, owner, seed, false);
        if (publication.isEmpty()) return;
        var published = publication.orElseThrow();
        if (!predictionPublicationCurrent(session, owner, published.identity())
                || owner.snapshot != snapshot || owner.travel.correctionRevision() != correction || owner.teleportPending) return;
        session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
        session.owner.lastStateTick = serverTick;
        owner.manualFailureReceiptPending = false;
        outboundStates.add(new Offer(session.player, new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,
                session.scope, session.outgoingSequence, session.proposal, Optional.empty(),
                Optional.of(session.owner.snapshot), Optional.empty(), session.owner.nativeTeleportId,
                Optional.ofNullable(owner.bodyReferenceReceipt), Optional.empty(),
                owner.travel.cardinalReference(), Optional.empty(), published.boundary(),
                Optional.of(seed), Optional.of(published.phase()),
                Optional.empty(), Optional.of(owner.travel.mobilityState(owner.snapshot)))));
        if (owner.eyeProfilePublicationPending == snapshot) owner.eyeProfilePublicationPending = null;
    }

    private record PreparedAppliedPublication(PlayerPhysicalSnapshot expectedOwnerSnapshot,
            PlayerPhysicalSnapshot installedSnapshot, long expectedOutgoingSequence, long nextOutgoingSequence,
            long predictionGeneration, PlayerBodyReference.OwnerReference ownerReference,
            PlayerPhysicsProtocol.BodyReferenceReceipt bodyReferenceReceipt,
            Optional<PlayerBodyReference.State> cardinalReference,
            PlayerMobilityIntent.State mobilityState, OptionalInt nativeTeleportId,
            PlayerPhysicalSnapshot eyeProfilePublicationPending, Offer offer) { }

    /** Runs every callback-capable STATE preparation before the terminal raw-motion check. */
    private Optional<PreparedAppliedPublication> prepareAppliedPublication(Session session, Owned owner,
            PlayerPhysicalSnapshot expectedOwnerSnapshot, PlayerPhysicalSnapshot installedSnapshot) {
        if (owner.recoveryPending || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror
                || owner.recoveryTerminal) return Optional.empty();
        var player = session.player;
        var entry = session.world.getPlayerChunkMap().getEntry((int) player.managedPosX >> 4,
                (int) player.managedPosZ >> 4);
        if (entry != null && entry.containsPlayer(player))
            player.getServer().getPlayerList().serverUpdateMovingPlayer(player);
        if (!current(session) || sessions.get(session.manager) != session || session.owner != owner
                || owner.snapshot != expectedOwnerSnapshot || owner.teleportPending
                || PlayerKinematics.requiresNative(player) && !owner.holdsNativeGeometry())
            return Optional.empty();

        CardinalPredictionSeed seed = owner.travel.predictionSeed(installedSnapshot);
        long correction = owner.travel.correctionRevision();
        Optional<PublishedPrediction> publication = publishedPrediction(session, owner, installedSnapshot,
                expectedOwnerSnapshot, seed, false);
        if (publication.isEmpty()) return Optional.empty();
        PublishedPrediction published = publication.orElseThrow();
        if (!predictionPublicationCurrent(session, owner, published.identity())
                || owner.snapshot != expectedOwnerSnapshot || owner.travel.correctionRevision() != correction
                || owner.teleportPending)
            return Optional.empty();

        long expectedSequence = session.outgoingSequence;
        long nextSequence = Math.incrementExact(expectedSequence);
        PlayerBodyReference.OwnerReference ownerReference = owner.travel.ownerReference();
        PlayerPhysicsProtocol.BodyReferenceReceipt bodyReferenceReceipt = owner.bodyReferenceReceipt;
        Optional<PlayerBodyReference.State> cardinalReference = owner.travel.cardinalReference();
        PlayerMobilityIntent.State mobilityState = owner.travel.mobilityState(installedSnapshot);
        OptionalInt nativeTeleportId = owner.nativeTeleportId;
        PlayerPhysicalSnapshot eyeProfilePublicationPending = owner.eyeProfilePublicationPending;
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,
                session.scope, nextSequence, session.proposal, Optional.empty(), Optional.of(installedSnapshot),
                Optional.empty(), nativeTeleportId, Optional.ofNullable(bodyReferenceReceipt),
                Optional.empty(), cardinalReference, Optional.empty(), published.boundary(),
                Optional.of(seed), Optional.of(published.phase()), Optional.empty(),
                Optional.of(mobilityState));
        return Optional.of(new PreparedAppliedPublication(expectedOwnerSnapshot, installedSnapshot,
                expectedSequence, nextSequence, published.identity().generation(), ownerReference,
                bodyReferenceReceipt, cardinalReference, mobilityState, nativeTeleportId,
                eyeProfilePublicationPending, new Offer(player, packet)));
    }

    /**
     * Raw server ingress can use only a physical scope already registered by the actual Session owner.
     */
    public void receiveRaw(PlayerPhysicsInboundConnections.RawInboundFrame raw, NetHandlerPlayServer handler) {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(handler, "handler");
        if (handler.player == null) return;
        NetworkManager manager = handler.netManager;
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        var connection = facade.findCurrentConnection(manager, handler, EnumPacketDirection.SERVERBOUND);
        if (connection.isEmpty()) return;

        Session capturedSession;
        PlayerPhysicsInboundConnections.ScopeHandle capturedScope;
        synchronized (this) {
            capturedSession = sessions.get(manager);
            if (capturedSession == null || capturedSession.handler != handler || !current(capturedSession)
                    || capturedSession.connectionHandle != connection.orElseThrow()
                    || capturedSession.scopeHandle == null) return;
            capturedScope = capturedSession.scopeHandle;
        }
        PlayerPhysicsInboundConnections.AdmissionResult admission = facade.admitRawFrame(
                connection.orElseThrow(), manager, handler, EnumPacketDirection.SERVERBOUND,
                raw, Optional.of(capturedScope));
        if (!admission.queued() || admission.newDrain().isEmpty()) return;

        PlayerPhysicsInboundConnections.DrainHandle exactDrain = admission.newDrain().orElseThrow();
        try {
            handler.player.getServer().addScheduledTask(() -> drainInbound(
                    exactDrain, connection.orElseThrow(), handler, capturedSession));
        } catch (Throwable schedulingFailure) {
            try {
                facade.drainSchedulingFailed(exactDrain, schedulingFailure);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != schedulingFailure) schedulingFailure.addSuppressed(cleanupFailure);
            }
            UnigravMod.LOGGER.error("ugmotion1 failed to schedule the exact server physics drain", schedulingFailure);
            PlayerPhysicsServer.<RuntimeException>rethrow(schedulingFailure);
        }
    }

    private void drainInbound(PlayerPhysicsInboundConnections.DrainHandle exactDrain,
            PlayerPhysicsInboundConnections.ConnectionHandle exactConnection,
            NetHandlerPlayServer exactHandler, Session capturedSession) {
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        while (true) {
            PlayerPhysicsInboundConnections.DecodeHandle exactDecode = null;
            try {
                Optional<PlayerPhysicsInboundConnections.DecodeHandle> next = facade.beginNextOrClose(exactDrain);
                if (next.isEmpty()) return;
                exactDecode = next.orElseThrow();
                Optional<PlayerPhysicsProtocol.Packet> decoded = facade.materializePacket(exactDecode);
                if (decoded.isEmpty()) {
                    facade.finishDecode(exactDecode, PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
                    continue;
                }
                boolean accepted = dispatch(capturedSession, decoded.orElseThrow());
                if (capturedSession.handler != exactHandler || capturedSession.connectionHandle != exactConnection)
                    accepted = false;
                facade.finishDecode(exactDecode, accepted
                        ? PlayerPhysicsInboundConnections.DecodeOutcome.PROOF_ACCEPTED
                        : PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
            } catch (Throwable originalFailure) {
                try {
                    facade.ownerDrainFailed(exactDrain, exactDecode, originalFailure);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
                }
                UnigravMod.LOGGER.error("ugmotion1 server physics drain failed for its captured connection task",
                        originalFailure);
                PlayerPhysicsServer.<RuntimeException>rethrow(originalFailure);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    /**
     * Legacy in-process test/adapter seam. Production IMessage registrations call {@link #receiveRaw}.
     */
    @Deprecated
    public void receive(PlayerPhysicsProtocol.Packet packet, NetHandlerPlayServer handler) {
        Session session;
        RemoteBoostBoundedDispatcher.Reservation<PlayerPhysicsProtocol.Packet> reservation;
        boolean overflow = false;
        synchronized (this) {
            if (packet == null || handler == null || packet.clientbound()) return;
            session = sessions.get(handler.netManager);
            if (session == null || session.handler != handler || !channelCurrent(handler.netManager)) return;
            reservation = session.dispatcher.reserve(packet, value -> dispatch(session, value));
            if (reservation == null && !session.overflowReported) {
                session.overflowReported = true;
                overflow = true;
            }
        }
        if (overflow)
            UnigravMod.LOGGER.warn("ugmotion1 server queue full; excess input dropped without granting movement");
        if (reservation != null) session.dispatcher.submit(reservation);
    }

    private synchronized boolean dispatch(Session session, PlayerPhysicsProtocol.Packet packet) {
        if (!session.world.isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics dispatch off server thread");
        if (!current(session)
                || !session.scope.equals(packet.scope()) || packet.offerSequence() != session.proposal
                || packet.sequence() <= session.lastIncomingSequence || session.source == null) return false;
        if (session.owner != null) reconcileMobilityControl(session, session.owner);
        if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK)
            return acceptNativeCorrectionAcknowledgement(session, packet);
        if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_ACK
                || packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK)
            return acceptNativeTransferAcknowledgement(session, packet);
        if (packet.kind() == PlayerPhysicsProtocol.Kind.RECOVER
                && packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.NativeTransferFailure failed)
            return acceptNativeTransferFailure(session, packet, failed);
        if (session.owner != null && session.owner.nativeTransfer != null
                && !session.owner.nativeTransfer.released) return false;
        if (packet.kind() == PlayerPhysicsProtocol.Kind.RECOVER) {
            Owned owner = session.owner;
            if (owner == null || owner.recoveryTerminal || owner.recoveryPending
                    || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror && !owner.recoveryOwnedSent
                    || serverTick - owner.lastRecoveryRequestTick < 20) return false;
            if (serverTick - owner.recoveryWindowTick >= 80) {
                owner.recoveryWindowTick = serverTick;
                owner.recoveryRequests = 0;
            }
            if (owner.recoveryRequests >= 3) return false;
            invalidateAttitudeRequest(owner);
            owner.recoveryRequests++;
            owner.lastRecoveryRequestTick = serverTick;
            owner.recoveryStartedTick = serverTick;
            owner.predictionTimeline.retire();
            owner.recoveryPending = true;
            owner.recoveryAwaitingMirror = false;
            owner.recoveryPacket = null;
            owner.controls = IDLE;
            owner.travel.mobility().release();
            session.lastIncomingSequence = packet.sequence();
            return true;
        }
        if (session.owner != null && !session.owner.nativeDelegated) {
            Owned recovering = session.owner;
            if (recovering.recoveryTerminal || recovering.recoveryPending) return false;
            if (recovering.recoveryAwaitingApply) {
                if (packet.kind() != PlayerPhysicsProtocol.Kind.APPLY) return false;
                session.lastIncomingSequence = packet.sequence();
                recovering.recoveryApplyReceived = true;
                return true;
            }
            if (recovering.recoveryAwaitingMirror) {
                if (packet.kind() != PlayerPhysicsProtocol.Kind.INPUT || !recovering.recoveryOwnedSent) return false;
                recovering.recoveryAwaitingMirror = false;
                recovering.nativeEnvironmentFailure = null;
                recovering.recoveryPacket = null;
                if (recovering.nativeRestoreAwaitingAcknowledgement)
                    recovering.lastRestoreStateWorldTick = Long.MIN_VALUE;
            }
            if (packet.kind() != PlayerPhysicsProtocol.Kind.INPUT) return false;
            if (session.owner != null && session.owner.nativeTransfer != null
                    && !session.owner.nativeTransfer.released) return false;
            Owned timingOwner = session.owner;
            var mobilityInput = packet.controls().orElseThrow().mobility();
            if (mobilityInput.request().isPresent()) {
                boolean eligible = personalGBackpackControl(session);
                if (!timingOwner.travel.mobility().accept(mobilityInput.request().orElseThrow(), eligible))
                    timingOwner.lastStateTick = Long.MIN_VALUE;
            }
            suspendChangedPredictionReference(session, timingOwner);
            boolean predictionAccepted=false;
            if (packet.predictionRequest().isPresent()) {
                timingOwner.predictionTimeline.request(predictionContext(session,timingOwner),packet.predictionRequest().orElseThrow());
            }
            if (packet.predictionInput().isEmpty()) {
                // In-flight ordinary INPUT predates the client learning this bootstrap. No prediction slot completed.
                if (!timingOwner.predictionTimeline.awaitingMetadata() && !timingOwner.predictionTimeline.suspended()
                        && !timingOwner.predictionTimeline.active()) timingOwner.predictionTimeline.retire();
            } else if (timingOwner.predictionTimeline.suspended()) {
                // An old prediction slot cannot revoke a current typed pause or its ordinary controls.
            } else if (!packet.predictionInput().orElseThrow().bootstrap().equals(timingOwner.predictionTimeline.bootstrap())) {
                // Stale prediction metadata does not revoke this owner's newer timeline or its ordinary controls.
            } else if (!predictionStable(session, timingOwner, packet.controls().orElseThrow())) {
                timingOwner.predictionTimeline.retire();
            } else {
                if (timingOwner.predictionTimeline.active() && !predictionFrameCurrent(timingOwner))
                    timingOwner.predictionTimeline.retire();
                // Metadata is optional prediction authority, never authority to discard valid current controls.
                predictionAccepted=timingOwner.predictionTimeline.accept(predictionContext(session, timingOwner), packet,
                        session.world.getTotalWorldTime());
                if (timingOwner.predictionShape == null || timingOwner.predictionCheckpoint == null
                        || !predictionFrameCurrent(timingOwner)) {
                    timingOwner.predictionShape = timingOwner.snapshot.placement().shape();
                    timingOwner.predictionAttitude = timingOwner.snapshot.placement().attitude();
                    timingOwner.predictionReference = timingOwner.travel.cardinalReference();
                }
            }
            session.lastIncomingSequence = packet.sequence();
            Owned owner = session.owner;
            invalidateAttitudeRequest(owner);
            long correctionAtInput = owner.travel.correctionRevision();
            PlayerPhysicalSnapshot.Scope inputScope = session.scope;
            long inputProposal = session.proposal;
            WorldServer inputWorld = session.world;
            PlayerPhysicsInboundConnections.ConnectionHandle inputConnection = session.connectionHandle;
            PlayerPhysicsInboundConnections.ScopeHandle inputScopeHandle = session.scopeHandle;
            owner.controls = packet.controls().orElseThrow();
            owner.latestAdmittedInput=packet; owner.latestAdmittedPredictionAccepted=predictionAccepted;
            owner.inputWorldTick = session.world.getTotalWorldTime();
            long receivedWorldTick = owner.inputWorldTick;
            var rawDesiredQ = owner.controls.desiredBodyAttitude();
            acceptBodyReferenceAcknowledgement(session, owner, packet.bodyReferenceAcknowledgement().orElse(null));
            applyBodyReferenceIntent(session, owner, owner.controls.bodyReferenceIntent());
            if (rawDesiredQ.isPresent() && current(session) && session.owner == owner
                    && session.scope.equals(inputScope) && session.proposal == inputProposal
                    && session.world == inputWorld && session.connectionHandle == inputConnection
                    && session.scopeHandle == inputScopeHandle && session.lastIncomingSequence == packet.sequence()
                    && session.world.getTotalWorldTime() == receivedWorldTick
                    && owner.travel.correctionRevision() == correctionAtInput) {
                owner.pendingAttitudeRequest = new PlayerAttitudeRequest(packet.sequence(), inputScope, inputProposal,
                        correctionAtInput, inputWorld, inputConnection, inputScopeHandle, receivedWorldTick,
                        rawDesiredQ.orElseThrow());
                currentAttitudeRequest(session, owner);
            }
            return true;
        }
        if (session.owner != null && (!session.owner.resumeOffered || PlayerKinematics.requiresNative(session.player))) return false;
        if (packet.kind() != PlayerPhysicsProtocol.Kind.APPLY) return false;
        Optional<GIntentObservation> source = intent.observe(session.player);
        if (source.isEmpty() || !source.orElseThrow().equals(session.source)) return false;
        // This is an untrusted pending message, NOT an accepted velocity, budget, or owner.
        session.lastIncomingSequence = packet.sequence();
        session.pending = packet;
        session.pendingTick = serverTick;
        return true;
    }

    private boolean sessionOwnerCurrent(Session session) {
        return sessions.get(session.manager) == session && channelCurrent(session.manager)
                && session.handler.player == session.player && session.player.world == session.world
                && session.player.dimension == session.scope.dimension()
                && session.player.getUniqueID().equals(session.scope.playerId())
                && actorCurrent(session.handler, session.manager);
    }

    private boolean current(Session session) {
        if (!sessionOwnerCurrent(session) || session.connectionHandle == null || session.scopeHandle == null)
            return false;
        return PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(session.manager, session.handler,
                EnumPacketDirection.SERVERBOUND).filter(handle -> handle == session.connectionHandle).isPresent();
    }

    /**
     * Return only the current untrusted input intent for same-package mode preparation and the bounded test bridge.
     * Invalid or expired samples are discarded as part of observing current state.
     */
    synchronized Optional<PlayerAttitudeRequest> pendingAttitudeRequest(NetHandlerPlayServer handler) {
        if (handler == null) return Optional.empty();
        Session session = sessions.get(handler.netManager);
        if (session == null || session.handler != handler || session.owner == null) return Optional.empty();
        return currentAttitudeRequest(session, session.owner);
    }

    private Optional<PlayerAttitudeRequest> currentAttitudeRequest(Session session, Owned owner) {
        PlayerAttitudeRequest request = owner.pendingAttitudeRequest;
        if (request == null) return Optional.empty();
        if (!isCurrentAttitudeRequest(session, owner, request, session.world.getTotalWorldTime())) {
            invalidateAttitudeRequest(owner);
            return Optional.empty();
        }
        return Optional.of(request);
    }

    private boolean isCurrentAttitudeRequest(Session session, Owned owner,
                                             PlayerAttitudeRequest request, long worldTick) {
        return current(session) && session.owner == owner && session.source != null
                && session.scope.equals(request.scope()) && owner.snapshot.scope().equals(request.scope())
                && session.proposal == request.proposal()
                && session.connectionHandle == request.connection()
                && session.scopeHandle == request.scopeHandle() && session.world == request.world()
                && session.lastIncomingSequence == request.inputSequence()
                && owner.inputWorldTick == request.receivedWorldTick()
                && owner.travel.correctionRevision() == request.correctionRevision()
                && owner.controls.desiredBodyAttitude().filter(request.rawDesiredQ()::equals).isPresent()
                && !owner.teleportPending && !owner.nativeDelegated && !owner.resumeOffered
                && !owner.recoveryPending && !owner.recoveryAwaitingApply && !owner.recoveryAwaitingMirror
                && !owner.recoveryTerminal && !owner.recoveryNativeResume
                && !owner.nativeRestorePending && !owner.nativeRestoreAwaitingAcknowledgement
                && !owner.nativeRestoreTerminalFailure && !owner.nativeHandoffPrepared
                && !PlayerKinematics.requiresNative(session.player)
                && inputAgeValid(worldTick, request.receivedWorldTick());
    }

    private static boolean inputAgeValid(long worldTick, long receivedWorldTick) {
        try {
            long age = Math.subtractExact(worldTick, receivedWorldTick);
            return age >= 0 && age <= INPUT_TIMEOUT_TICKS;
        } catch (ArithmeticException overflow) {
            // An unrepresentable age cannot identify a fresh owner input.
            return false;
        }
    }

    private static void invalidateAttitudeRequest(Owned owner) {
        owner.pendingAttitudeRequest = null;
    }

    /** A server scope is created only after the real Session is in the registry and its owner is rechecked. */
    private boolean registerSessionScope(Session session) {
        if (!sessionOwnerCurrent(session) || !actorCurrent(session.handler, session.manager)) return false;
        var facade = PlayerPhysicsInboundConnections.INSTANCE;
        var connection = facade.findCurrentConnection(session.manager, session.handler,
                EnumPacketDirection.SERVERBOUND);
        if (connection.isEmpty()) return false;
        var opened = facade.openScopeForOwner(connection.orElseThrow(), session.scope);
        if (opened.isEmpty()) return false;
        if (!sessionOwnerCurrent(session) || !actorCurrent(session.handler, session.manager)
                || facade.findCurrentConnection(session.manager, session.handler,
                EnumPacketDirection.SERVERBOUND).filter(handle -> handle == connection.orElseThrow()).isEmpty()) {
            facade.retireScope(opened.orElseThrow());
            return false;
        }
        session.connectionHandle = connection.orElseThrow();
        session.scopeHandle = opened.orElseThrow();
        return true;
    }

    private void acceptBodyReferenceAcknowledgement(Session session, Owned owner,
            PlayerPhysicsProtocol.BodyReferenceAcknowledgement acknowledgement) {
        var snapshot = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        var connection = session.connectionHandle;
        var scopeHandle = session.scopeHandle;
        long proposal = session.proposal;
        NativeRestoreStateIdentity identity = acknowledgement == null ? null
                : new NativeRestoreStateIdentity(session.scope, proposal,
                        acknowledgement.stateSequence(), acknowledgement.snapshotRevision());
        if (acknowledgement == null || !owner.nativeRestoreAwaitingAcknowledgement
                || owner.nativeRestoreTerminalFailure || owner.nativeRestoreAcknowledged
                || owner.nativeRestoreAckDeadlineWorldTick == Long.MIN_VALUE
                || session.world.getTotalWorldTime() >= owner.nativeRestoreAckDeadlineWorldTick
                || !owner.nativeRestoreSentStates.contains(identity)
                || owner.nativeRestoreRevision != acknowledgement.snapshotRevision()
                || owner.snapshot.revision() != acknowledgement.snapshotRevision()
                || !(owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                || shape.frame() != CardinalFrame.Y_POS || !matchesAcceptedGeometry(session)) return;
        if (!current(session) || sessions.get(session.manager) != session || session.owner != owner
                || owner.snapshot != snapshot || owner.travel.correctionRevision() != correction
                || session.connectionHandle != connection || session.scopeHandle != scopeHandle
                || session.proposal != proposal || session.player.connection != session.handler
                || session.player.world != session.world || session.player.isDead || owner.teleportPending
                || !owner.nativeRestoreAwaitingAcknowledgement || owner.nativeRestoreTerminalFailure
                || !owner.nativeRestoreSentStates.contains(identity)) return;
        owner.nativeRestoreAcknowledged = true;
    }

    private void applyBodyReferenceIntent(Session session, Owned owner,
            PlayerPhysicsProtocol.BodyReferenceIntent requested) {
        if (requested.action() == PlayerPhysicsProtocol.BodyReferenceAction.NONE) return;
        if (requested.requestId() < owner.lastBodyReferenceRequestId) return;
        if (requested.requestId() == owner.lastBodyReferenceRequestId) {
            if (!requested.equals(owner.lastBodyReferenceIntent)) {
                owner.bodyReferenceReceipt = receipt(owner, requested.requestId(),
                        PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                        PlayerPhysicsProtocol.BodyReferenceReason.REQUEST_ID_REUSED);
            }
            return;
        }

        owner.lastBodyReferenceRequestId = requested.requestId();
        owner.lastBodyReferenceIntent = requested;
        if (requested.action() == PlayerPhysicsProtocol.BodyReferenceAction.UNLOCK) {
            boolean needsSafeNativeRestore = PlayerKinematics.requiresNative(session.player)
                    && owner.holdsNativeGeometry();
            setManualReference(owner, null, PlayerBodyReference.Transition.MANUAL_CLEARED);
            if (needsSafeNativeRestore) {
                beginNativeRestore(session, owner, requested.requestId(),
                        PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF, true);
                owner.bodyReferenceReceipt = receipt(owner, requested.requestId(),
                        PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                        PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING);
            } else {
                owner.bodyReferenceReceipt = receipt(requested.requestId(),
                        PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, Optional.empty(), Optional.empty(),
                        Optional.empty());
                clearNativeRestore(owner);
            }
            return;
        }

        PlayerPhysicsProtocol.BodyReferenceReason rejection = null;
        var observed = intent.observe(session.player);
        if (!current(session) || session.owner != owner || owner.nativeDelegated
                || PlayerKinematics.requiresNative(session.player) || observed.isEmpty()
                || !observed.orElseThrow().active() || !session.player.interactionManager.isCreative()) {
            rejection = observed.filter(value -> !value.active()).isPresent()
                    ? PlayerPhysicsProtocol.BodyReferenceReason.G_OFF
                    : PlayerPhysicsProtocol.BodyReferenceReason.INELIGIBLE;
        } else if (owner.nativeRestorePending || owner.nativeRestoreAwaitingAcknowledgement
                || owner.nativeRestoreTerminalFailure || owner.teleportPending) {
            rejection = PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING;
        } else if (!(owner.snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal)
                && !(owner.snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            rejection = PlayerPhysicsProtocol.BodyReferenceReason.UNSUPPORTED_SHAPE;
        } else if (!matchesAcceptedGeometry(session)) {
            rejection = PlayerPhysicsProtocol.BodyReferenceReason.GEOMETRY_MISMATCH;
        } else {
            CardinalFrame requestedFrame = requested.frame().orElseThrow();
            BodyReferenceLock manual = new BodyReferenceLock(requested.requestId(), requestedFrame,
                    requested.tangentHeading().orElseThrow());
            if (owner.travel.ownerReference() instanceof PlayerBodyReference.FreeReference free)
                owner.travel.ownerReference(new PlayerBodyReference.FreeReference(free.manualIntent(),
                        free.pendingTarget().filter(target -> target.source() != PlayerBodyReference.Source.MANUAL),
                        free.previousEffective()));
            owner.travel.pendingManualIntent(Optional.of(new PlayerBodyReference.ManualIntent(
                    manual.requestId(), manual.frame(), manual.tangentHeading())));
            return;
        }
        owner.bodyReferenceReceipt = receipt(owner, requested.requestId(),
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, rejection);
    }

    /** Commit or reject only the newly requested manual preference after the real Step result. */
    private void settlePendingManualReference(Session session, Owned owner, PlayerPhysicalSnapshot before) {
        if (!current(session) || session.owner != owner) return;
        var failed = owner.snapshot == before ? owner.travel.consumePendingManualFailure(before)
                : Optional.<PlayerPhysicsStep.PendingManualFailure>empty();
        if (failed.isPresent() && owner.travel.ownerReference() instanceof PlayerBodyReference.FreeReference free) {
            var failure = failed.orElseThrow();
            owner.travel.pendingManualIntent(Optional.empty());
            var retainedTarget = free.pendingTarget().filter(target -> target.source() != PlayerBodyReference.Source.MANUAL
                    || target.requestId() != failure.request().requestId());
            owner.travel.ownerReference(new PlayerBodyReference.FreeReference(free.manualIntent(), retainedTarget,
                    free.previousEffective()));
            owner.bodyReferenceReceipt = rejectedPendingManualReceipt(failure, free.manualIntent());
            owner.manualFailureReceiptPending = true;
            return;
        }
        PlayerBodyReference.ManualIntent pending = owner.travel.pendingManualIntent().orElse(null);
        if (pending == null) return;
        // A saved/pending Cardinal request has no installed receipt while Free remains accepted.
        if (owner.travel.cardinalReference().isEmpty()) return;
        PlayerBodyReference.State state = owner.travel.bodyReference();
        if (state.manualIntent().filter(saved -> saved.requestId() == pending.requestId()).isPresent()) {
            owner.bodyReferenceLock = new BodyReferenceLock(pending.requestId(), pending.frame(),
                    pending.tangentHeading());
            owner.travel.pendingManualIntent(Optional.empty());
            refreshManualReceipt(owner);
            return;
        }
        if (state.transition() != PlayerBodyReference.Transition.MANUAL_ADOPTION_BLOCKED
                || state.pendingFrame().filter(pending.frame()::equals).isEmpty()) return;
        owner.travel.pendingManualIntent(Optional.empty());
        long savedRequestId = owner.bodyReferenceLock == null ? 0 : owner.bodyReferenceLock.requestId();
        owner.bodyReferenceReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(pending.requestId(),
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, savedRequestId,
                acceptedLockFrame(owner), acceptedLockHeading(owner), state.reason());
    }

    static PlayerPhysicsProtocol.BodyReferenceReceipt rejectedPendingManualReceipt(
            PlayerPhysicsStep.PendingManualFailure failure, Optional<PlayerBodyReference.ManualIntent> saved) {
        return new PlayerPhysicsProtocol.BodyReferenceReceipt(failure.request().requestId(),
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                saved.map(PlayerBodyReference.ManualIntent::requestId).orElse(0L),
                saved.map(PlayerBodyReference.ManualIntent::frame), saved.map(PlayerBodyReference.ManualIntent::tangentHeading),
                Optional.of(failure.reason()));
    }

    private static void clearManualForGOff(Owned owner) {
        PlayerBodyReference.ManualIntent pending = owner.travel.pendingManualIntent().orElse(null);
        BodyReferenceLock saved = owner.bodyReferenceLock;
        setManualReference(owner, null, PlayerBodyReference.Transition.MANUAL_CLEARED);
        if (pending != null) {
            owner.bodyReferenceReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(pending.requestId(),
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.G_OFF));
        } else if (saved != null) {
            owner.bodyReferenceReceipt = receipt(latestBodyReferenceRequestId(owner),
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, Optional.empty(), Optional.empty(),
                    PlayerPhysicsProtocol.BodyReferenceReason.G_OFF);
        }
    }

    private void beginNativeRestore(Session session, Owned owner, long requestId,
            PlayerPhysicsProtocol.BodyReferenceReason cause, boolean explicitUnlock) {
        long worldTick = session.world.getTotalWorldTime();
        long deadlineWorldTick = Math.addExact(worldTick, NATIVE_RESTORE_TIMEOUT_TICKS);
        PlayerBodyReference.OwnerReference previousReference = owner.travel.ownerReference();
        PlayerBodyReference.OwnerReference pendingReference = previousReference;
        if (previousReference instanceof PlayerBodyReference.CardinalReference cardinal) {
            PlayerBodyReference.State state = cardinal.state();
            pendingReference = new PlayerBodyReference.CardinalReference(new PlayerBodyReference.State(
                    state.manualIntent(), state.effective(), PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING,
                    Optional.empty(), Optional.empty()));
        }

        invalidateAttitudeRequest(owner);
        owner.nativeRestorePending = true;
        owner.nativeRestoreAwaitingAcknowledgement = false;
        owner.nativeRestoreAcknowledged = false;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreTerminalFailure = false;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreRequestId = requestId;
        owner.nativeRestoreExplicitUnlock = explicitUnlock;
        owner.nativeRestoreDeadlineWorldTick = deadlineWorldTick;
        owner.nativeRestoreNextAttemptWorldTick = worldTick;
        owner.lastRestoreStateWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreReason = cause;
        owner.travel.pendingManualIntent(Optional.empty());
        if (pendingReference != previousReference) owner.travel.ownerReference(pendingReference);
    }

    private static void clearNativeRestore(Owned owner) {
        owner.nativeRestorePending = false;
        owner.nativeRestoreAwaitingAcknowledgement = false;
        owner.nativeRestoreAcknowledged = false;
        owner.nativeRestoreTerminalFailure = false;
        owner.nativeHandoffPrepared = false;
        owner.nativeRestoreRequestId = 0;
        owner.nativeRestoreExplicitUnlock = false;
        owner.nativeRestoreRevision = -1;
        owner.nativeRestoreSentStates.clear();
        owner.nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreDeadlineWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreNextAttemptWorldTick = Long.MIN_VALUE;
        owner.lastRestoreStateWorldTick = Long.MIN_VALUE;
        owner.nativeRestoreReason = null;
    }

    private boolean channelCurrent(NetworkManager manager) {
        var capability = capabilities.get(manager);
        return capability != null && capability.connected && capability.registered && !capability.retired
                && manager.isChannelOpen();
    }

    private static boolean actorCurrent(NetHandlerPlayServer handler, NetworkManager manager) {
        var player = handler.player;
        return player != null && player.connection == handler && handler.netManager == manager
                && manager.getNetHandler() == handler && player.world instanceof WorldServer
                && DimensionManager.getWorld(player.dimension) == player.world
                && player.dimension == player.world.provider.getDimension()
                && !player.isDead && player.isEntityAlive();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        retirePlayer(event);
        if (event.player instanceof EntityPlayerMP player) {
            PlayerPosePersistence.retain(player, null);
            PlayerPosePersistence.bindLogin(player);
        }
    }

    @SubscribeEvent
    public void onClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof EntityPlayerMP player) PlayerPosePersistence.invalidate(player);
        if (event.getEntityPlayer() instanceof EntityPlayerMP player) PlayerPosePersistence.invalidate(player);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        retirePlayer(event);
        if (event.player instanceof EntityPlayerMP player) PlayerPosePersistence.invalidate(player);
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        retirePlayer(event);
        if (event.player instanceof EntityPlayerMP player) PlayerPosePersistence.invalidate(player);
    }

    @SubscribeEvent
    public synchronized void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.player == event.player) retire(session.manager, true);
        }
    }

    private synchronized void retirePlayer(PlayerEvent event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.player == event.player || session.scope.playerId().equals(event.player.getUniqueID()))
                retire(session.manager);
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldServer world) initializePoseWorldIdentity(world);
    }

    private static void initializePoseWorldIdentity(WorldServer world) {
        try {
            PlayerPoseWorldIdentity.initialize(world);
        } catch (RuntimeException failure) {
            UnigravMod.LOGGER.error("UG pose history unavailable: world identity initialization failed, dimension={}",
                    world.provider.getDimension(), failure);
        }
    }

    @SubscribeEvent
    public synchronized void onWorldUnload(WorldEvent.Unload event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.world == event.getWorld()) retire(session.manager);
        }
        if (event.getWorld() instanceof WorldServer world) PlayerPoseWorldIdentity.forget(world);
    }

    public synchronized void stop() {
        for (NetworkManager manager : sessions.keySet().toArray(NetworkManager[]::new)) retire(manager);
        capabilities.clear();
    }

    /** Save observation only; disconnected channels may still have an exact final committed actor. */
    synchronized PlayerPosePersistence.RetainedPose poseForSave(EntityPlayerMP player) {
        if (player.connection == null || !(player.world instanceof WorldServer world)) return null;
        if (!world.isCallingFromMinecraftThread())
            throw new IllegalStateException("Player pose save observation requires the world thread");
        if (NativePlayerMovement.INSTANCE.forceMutationActive(player)) return null;
        Session session = sessions.get(player.connection.netManager);
        if (session == null)
            return player instanceof PlayerPosePersistence.Carrier carrier ? carrier.unigrav$retainedSavePose() : null;
        if (session.player != player || session.handler != player.connection
                || session.world != world || session.scope.dimension() != player.dimension
                || !session.scope.playerId().equals(player.getUniqueID()) || player.isDead) return null;
        Owned owner = session.owner;
        if (owner == null || owner.nativeDelegated || owner.teleportPending || owner.nativeRestorePending
                || owner.recoveryPending || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror
                || owner.recoveryTerminal || owner.nativeEnvironmentFailure != null || owner.poseDepth != 0
                || nativeEnvironmentScope != null
                || owner.pendingCardinalPose != null || FreeOwnerTransaction.nativeInstallation(player) != null) return null;
        var snapshot = owner.snapshot;
        long correction = owner.travel.correctionRevision();
        if (!matchesPhysicalGeometry(player, snapshot) || sessions.get(session.manager) != session
                || session.owner != owner || owner.snapshot != snapshot
                || owner.travel.correctionRevision() != correction || owner.teleportPending
                || owner.pendingCardinalPose != null || owner.poseDepth != 0) return null;
        return new PlayerPosePersistence.RetainedPose(player.connection, world, snapshot);
    }

    private void retire(NetworkManager manager) {
        retire(manager, false);
    }

    private void retire(NetworkManager manager, boolean logoutSave) {
        Session retiring = sessions.get(manager);
        if (retiring != null) {
            PlayerPosePersistence.RetainedPose retained = null;
            try {
                if (logoutSave) retained = poseForSave(retiring.player);
            } catch (RuntimeException failure) {
                UnigravMod.LOGGER.error("UG pose-save observation failed before retirement: player={}, scope={}",
                        retiring.player.getUniqueID(), retiring.scope, failure);
            }
            PlayerPosePersistence.retain(retiring.player, retained);
        }
        Session old = sessions.remove(manager);
        if (old != null) {
            if (old.scopeHandle != null) {
                PlayerPhysicsInboundConnections.INSTANCE.retireScope(old.scopeHandle);
                old.scopeHandle = null;
            }
            old.dispatcher.invalidate();
            if (old.owner != null) {
                if (old.owner.jumpFollowUpProbe != null)
                    finishServerControlJumpProbe(old.owner, old.owner.jumpFollowUpProbe,
                            "owner-session-retired");
                old.owner.predictionTimeline.retire();
                invalidateAttitudeRequest(old.owner);
                old.owner.travel.invalidate();
                old.owner.freeResources.invalidate();
            }
            if (old.owner != null && !old.owner.nativeDelegated) restoreNativeGravity(old);
        }
    }

    private static void restoreNativeGravity(Session session) {
        if (!session.player.hasNoGravity()) session.owner.travel.nativeNoGravity = false;
        if (UnigravMod.entityBinder == null) session.player.setNoGravity(session.owner.travel.nativeNoGravity);
        else UnigravMod.entityBinder.restoreNativeGravity(session.player, session.owner.travel.nativeNoGravity);
    }

    /** A control receipt only: it cannot authorize installation or transfer velocity. */
    private static final class NativeCorrectionControl {
        private final NativeCorrectionCompletion completion;
        private final PlayerPhysicsProtocol.Packet packet;
        private boolean attempted, acknowledged;
        private long lastAttemptTick = Long.MIN_VALUE / 2;
        private RuntimeException sendFailure;
        private NativeCorrectionControl(NativeCorrectionCompletion completion, PlayerPhysicsProtocol.Packet packet) {
            this.completion = completion;
            this.packet = packet;
        }
    }

    private boolean nativeCorrectionControlIdentity(NativeCorrectionControl control) {
        NativeCorrectionCompletion completion = control.completion;
        return completion.owner.nativeCorrectionControl == control
                && completion.owner.nativeDelegated && nativeCompletionIdentity(completion, false);
    }

    private boolean nativeCorrectionControlCurrent(NativeCorrectionControl control) {
        if (!nativeCorrectionControlIdentity(control)) return false;
        boolean live = current(control.completion.session) && control.completion.session.player.isEntityAlive();
        return live && nativeCorrectionControlIdentity(control);
    }

    private void queueNativeCorrectionControl(NativeCorrectionControl control, ArrayList<Offer> states) {
        states.add(new Offer(control.completion.session.player, control.packet,
                control.completion.session, control.completion.owner));
    }

    private boolean sendNativeCorrectionControl(Offer offer) {
        NativeCorrectionControl control;
        synchronized (this) {
            Owned owner = offer.handoffOwner();
            control = owner == null ? null : owner.nativeCorrectionControl;
            if (control == null || control.packet != offer.packet()
                    || control.completion.session != offer.handoffSession()
                    || !nativeCorrectionControlCurrent(control)) return false;
            control.attempted = true;
            control.lastAttemptTick = serverTick;
        }
        try {
            outbound.accept(offer.player(), offer.packet());
            return true;
        } catch (RuntimeException failure) {
            synchronized (this) {
                if (nativeCorrectionControlIdentity(control)) control.sendFailure = failure;
            }
            UnigravMod.LOGGER.error("ugmotion1 native correction completion send uncertain for scope "
                    + control.completion.scope, failure);
            return false;
        }
    }

    private boolean acceptNativeCorrectionAcknowledgement(Session session, PlayerPhysicsProtocol.Packet packet) {
        Owned owner = session.owner;
        NativeCorrectionControl control = owner == null ? null : owner.nativeCorrectionControl;
        if (control == null || !control.attempted || !nativeCorrectionControlCurrent(control)
                || !(packet.nativeTransfer().orElse(null)
                instanceof PlayerPhysicsProtocol.NativeCorrectionAcknowledgement ack)) return false;
        var completed = (PlayerPhysicsProtocol.NativeCorrectionCompleted) control.packet.nativeTransfer().orElseThrow();
        if (ack.correctionIdentity() != completed.correctionIdentity()
                || ack.nativeTeleportId() != completed.nativeTeleportId()
                || ack.completedSequence() != control.packet.sequence()) return false;
        // Control ACK cannot acknowledge any restore STATE or perform physical writes.
        control.acknowledged = true;
        session.lastIncomingSequence = packet.sequence();
        return true;
    }

    /** Captured only by the real pending-cleared confirmation boundary. */
    private static final class NativeCorrectionCompletion {
        private final Session session;
        private final Owned owner;
        private final PlayerPhysicalSnapshot source;
        private final NetHandlerPlayServer handler;
        private final WorldServer world;
        private final PlayerPhysicsInboundConnections.ConnectionHandle connection;
        private final PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final long proposal, correction, deadlineTick;
        private final int teleportId;
        private long lastAttemptTick;
        private boolean reclassifyPending;
        private RuntimeException failure;
        private NativeCorrectionCompletion(Session session, Owned owner, int teleportId, long tick) {
            this.session = session; this.owner = owner; this.source = owner.snapshot;
            this.handler = session.handler; this.world = session.world;
            this.connection = session.connectionHandle; this.scopeHandle = session.scopeHandle;
            this.scope = session.scope; this.proposal = session.proposal;
            this.correction = owner.travel.correctionRevision(); this.teleportId = teleportId;
            this.lastAttemptTick = tick; this.deadlineTick = Math.addExact(tick, NATIVE_RESTORE_TIMEOUT_TICKS);
        }
    }

    private static final class NativeMotionOutbox {
        private final PlayerPhysicsProtocol.NativeMotion payload;
        private final PlayerPhysicsProtocol.Packet packet;
        private boolean attempted;
        private NativeMotionOutbox(PlayerPhysicsProtocol.NativeMotion payload, PlayerPhysicsProtocol.Packet packet) {
            this.payload = payload; this.packet = packet;
        }
    }

    /** Immutable base/lifetime plus constant-memory stop-and-wait delivery state. */
    private static final class NativeTransferPending {
        private final Session session;
        private final Owned owner;
        private final NetHandlerPlayServer handler;
        private final WorldServer world;
        private final PlayerPhysicsInboundConnections.ConnectionHandle connection;
        private final PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final long proposal;
        private final long correction;
        private final OptionalInt teleportId;
        private final PlayerPhysicalSnapshot restoreSnapshot;
        private final PlayerPhysicsProtocol.NativeHandoff base;
        private final PlayerPhysicsProtocol.Packet basePacket;
        private Vec3d serverVelocity;
        private boolean attempted;
        private boolean baseAcknowledged;
        private boolean released;
        private long motionSequence;
        private long lastAttemptTick = Long.MIN_VALUE / 2;
        private NativeMotionOutbox outbox;
        private RuntimeException sendFailure;
        private NativeTransferPending(Session session, Owned owner, PlayerPhysicsProtocol.NativeHandoff base,
                PlayerPhysicsProtocol.Packet packet) {
            this.session = session; this.owner = owner; handler = session.handler; world = session.world;
            connection = session.connectionHandle; scopeHandle = session.scopeHandle; scope = session.scope;
            proposal = session.proposal; correction = owner.travel.correctionRevision(); teleportId = owner.nativeTeleportId;
            restoreSnapshot = owner.snapshot; this.base = base; basePacket = packet; serverVelocity = base.baseVelocity();
        }
    }

    private record NativeRestoreStateIdentity(PlayerPhysicalSnapshot.Scope scope, long proposal,
                                              long sequence, long revision) { }

    private record NativeRestoreStateOffer(Session session, Owned owner, PlayerPhysicsProtocol.Packet packet, NativeRestoreStateIdentity identity) { }

    private record NativePresentationOffer(Session session, Owned owner, PlayerPhysicalSnapshot accepted, long proposal) { }

    private record Offer(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet,
                         Session handoffSession, Owned handoffOwner,
                         NativeRestoreStateOffer restoreStateOffer, NativePresentationOffer nativePresentationOffer) {
        private Offer(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet, Session session, Owned owner,
                      NativeRestoreStateOffer restore) {
            this(player, packet, session, owner, restore, null);
        }
        private Offer(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet) {
            this(player, packet, null, null, null);
        }
        private Offer(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet,
                      Session handoffSession, Owned handoffOwner) {
            this(player, packet, handoffSession, handoffOwner, null,
                    new NativePresentationOffer(handoffSession, handoffOwner, handoffOwner.snapshot, handoffSession.proposal));
        }
    }

    private record BodyReferenceLock(long requestId, CardinalFrame frame, Vec3d tangentHeading) { }

    private static final class Capability {
        private boolean connected;
        private boolean registered;
        private boolean retired;
    }

    /** Package-private native eye exception, minted only by mintNativeRestoreAuthority. */
    static final class NativeRestoreAuthority {
        private final PlayerPhysicsServer server;
        private final Session session;
        private final Owned owner;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerBodyReference.FreeReference sourceReference;
        private final WorldServer world;
        private final NetHandlerPlayServer handler;
        private final PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle;
        private final PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
        private final long proposal;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final long correction;
        private final long worldTick;
        private final long budgetTick;
        private final long requestId;
        private final boolean explicitUnlock;
        private final PlayerBodyReference.ManualIntent pendingManual;
        private final FreeTickResources resources;
        private final BoostMovementBudget budget;
        private final NativeFlags nativeFlags;
        private final PlayerBodyShape.Dimensions targetDimensions;
        private final int rawEyeBits;
        private final int effectiveEyeBits;
        private final WorldCollisionCandidates.ActorGeometry physicalAnchor;
        private FreeOwnerTransaction claimedTransaction;

        private NativeRestoreAuthority(PlayerPhysicsServer server, Session session, Owned owner,
                PlayerPhysicalSnapshot accepted, PlayerBodyReference.FreeReference sourceReference,
                WorldServer world, NetHandlerPlayServer handler,
                PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle,
                PlayerPhysicsInboundConnections.ScopeHandle scopeHandle, long proposal,
                PlayerPhysicalSnapshot.Scope scope, long correction, long worldTick, long budgetTick,
                long requestId, boolean explicitUnlock, PlayerBodyReference.ManualIntent pendingManual,
                FreeTickResources resources, BoostMovementBudget budget, NativeFlags nativeFlags,
                PlayerBodyShape.Dimensions targetDimensions, float rawEyeHeight, float effectiveEyeHeight,
                WorldCollisionCandidates.ActorGeometry physicalAnchor) {
            this.server = Objects.requireNonNull(server, "server");
            this.session = Objects.requireNonNull(session, "session");
            this.owner = Objects.requireNonNull(owner, "owner");
            this.accepted = Objects.requireNonNull(accepted, "accepted");
            this.sourceReference = Objects.requireNonNull(sourceReference, "sourceReference");
            this.world = Objects.requireNonNull(world, "world");
            this.handler = Objects.requireNonNull(handler, "handler");
            this.connectionHandle = Objects.requireNonNull(connectionHandle, "connectionHandle");
            this.scopeHandle = Objects.requireNonNull(scopeHandle, "scopeHandle");
            this.proposal = proposal;
            this.scope = Objects.requireNonNull(scope, "scope");
            this.correction = correction;
            this.worldTick = worldTick;
            this.budgetTick = budgetTick;
            this.requestId = requestId;
            this.explicitUnlock = explicitUnlock;
            this.pendingManual = pendingManual;
            this.resources = Objects.requireNonNull(resources, "resources");
            this.budget = Objects.requireNonNull(budget, "budget");
            this.nativeFlags = Objects.requireNonNull(nativeFlags, "nativeFlags");
            this.targetDimensions = Objects.requireNonNull(targetDimensions, "targetDimensions");
            rawEyeBits = Float.floatToIntBits(rawEyeHeight);
            effectiveEyeBits = Float.floatToIntBits(effectiveEyeHeight);
            this.physicalAnchor = Objects.requireNonNull(physicalAnchor, "physicalAnchor");
        }

        synchronized void claim(FreeOwnerTransaction transaction) {
            Objects.requireNonNull(transaction, "transaction");
            if (claimedTransaction != null || !transactionBindsExactOwner(transaction))
                throw new IllegalStateException("native restore authority was claimed by a different transaction");
            claimedTransaction = transaction;
            if (!matches(transaction))
                throw new IllegalStateException("native restore transaction lost its exact source authority");
        }

        /** Pure post-observation fence; native getters are deliberately kept outside this check. */
        boolean transactionOwnerFenceCurrent(FreeOwnerTransaction transaction,
                PlayerBodyReference.CardinalReference expectedInstalledReference) {
            if (transaction != null && (claimedTransaction != transaction
                    || !transactionBindsExactOwner(transaction))) return false;
            try {
                resources.requireCurrent(owner, world, scope, correction);
            } catch (IllegalStateException staleResources) {
                if ("Free tick resources lost their owner, world, scope or correction".equals(staleResources.getMessage()))
                    return false;
                throw staleResources;
            }
            Capability capability = server.capabilities.get(session.manager);
            boolean sourceReferenceCurrent = owner.travel.ownerReference() == sourceReference
                    && (transaction == null || !transaction.hasCommittedOwnerReference());
            boolean installedReferenceCurrent = transaction != null && expectedInstalledReference != null
                    && transaction.matchesCommittedOwnerReference(expectedInstalledReference);
            if (server.sessions.get(session.manager) != session || capability == null
                    || !capability.connected || !capability.registered || capability.retired
                    || session.owner != owner || session.handler != handler || session.player.connection != handler
                    || handler.player != session.player || session.world != world || session.player.world != world
                    || session.player.dimension != scope.dimension()
                    || DimensionManager.getWorld(scope.dimension()) != world
                    || session.connectionHandle != connectionHandle || session.scopeHandle != scopeHandle
                    || session.proposal != proposal || !session.scope.equals(scope) || !accepted.scope().equals(scope)
                    || owner.snapshot != accepted || owner.travel.pendingManualIntent().orElse(null) != pendingManual
                    || owner.travel.correctionRevision() != correction || owner.budget != budget
                    || owner.budgetTick != budgetTick || world.getTotalWorldTime() != worldTick
                    || owner.nativeRestoreRequestId != requestId || owner.nativeRestoreExplicitUnlock != explicitUnlock
                    || !owner.nativeRestorePending || owner.nativeRestoreAwaitingAcknowledgement
                    || owner.nativeRestoreTerminalFailure || owner.teleportPending || owner.nativeDelegated
                    || owner.recoveryPending || owner.recoveryAwaitingApply || owner.recoveryAwaitingMirror
                    || owner.recoveryTerminal || session.player.isDead
                    || !(sourceReferenceCurrent || installedReferenceCurrent)) return false;
            return true;
        }

        boolean matches(FreeOwnerTransaction transaction) {
            if (transaction == null || claimedTransaction != transaction || !server.current(session)
                    || !transactionOwnerFenceCurrent(transaction, null)) return false;
            EntityPlayerMP actor = session.player;
            float effectiveEye = actor.getEyeHeight();
            NativeFlags actualFlags = NativeFlags.read(actor);
            float rawEye = actor.eyeHeight;
            WorldCollisionCandidates.ActorGeometry actual = WorldCollisionCandidates.ActorGeometry.read(actor);
            boolean observationsMatch = nativeFlags.equals(actualFlags) && nativeFlags.requiresNative()
                    && Float.floatToIntBits(rawEye) == rawEyeBits
                    && Float.floatToIntBits(effectiveEye) == effectiveEyeBits
                    && physicalAnchor.equals(actual) && matchesPhysicalAnchorGeometry(actual, accepted)
                    && Float.floatToIntBits(actual.width()) == Float.floatToIntBits((float) targetDimensions.width())
                    && Float.floatToIntBits(actual.height()) == Float.floatToIntBits((float) targetDimensions.height())
                    && Float.floatToIntBits(effectiveEye)
                        == Float.floatToIntBits((float) targetDimensions.eyeHeight());
            resources.requireCurrent(owner, world, scope, correction);
            return observationsMatch && transactionOwnerFenceCurrent(transaction, null);
        }

        boolean allowsEffectiveEye(FreeOwnerTransaction transaction, int actualEyeBits) {
            return matches(transaction)
                    && actualEyeBits == Float.floatToIntBits((float) targetDimensions.eyeHeight());
        }

        boolean flagsCurrentAfterInstall(FreeOwnerTransaction transaction) {
            if (transaction == null || claimedTransaction != transaction || !server.current(session)
                    || !transactionOwnerFenceCurrent(transaction, transaction.committedOwnerReference()))
                return false;
            EntityPlayerMP actor = session.player;
            float effectiveEye = actor.getEyeHeight();
            NativeFlags actualFlags = NativeFlags.read(actor);
            int rawEye = Float.floatToIntBits(actor.eyeHeight);
            resources.requireCurrent(owner, world, scope, correction);
            return nativeFlags.equals(actualFlags)
                    && Float.floatToIntBits(effectiveEye) == Float.floatToIntBits((float) targetDimensions.eyeHeight())
                    && rawEye == rawEyeBits
                    && transactionOwnerFenceCurrent(transaction, transaction.committedOwnerReference());
        }

        boolean physicalAnchorMatches(FreeOwnerTransaction transaction) {
            return matches(transaction) && matchesPhysicalAnchorGeometry(physicalAnchor, accepted);
        }

        PlayerBodyShape.Dimensions targetDimensions() { return targetDimensions; }
        WorldCollisionCandidates.ActorGeometry physicalAnchor() { return physicalAnchor; }
        NetHandlerPlayServer handler() { return handler; }
        WorldServer world() { return world; }

        private boolean transactionBindsExactOwner(FreeOwnerTransaction transaction) {
            return transaction.matchesNativeRestoreOwner(this, session.player, accepted, owner.travel,
                    budget, owner, resources, world) && transaction.tick() == worldTick;
        }
    }

    private record NativeFlags(boolean riding, boolean sleeping, boolean spectator, boolean noClip) {
        private static NativeFlags read(EntityPlayerMP actor) {
            return new NativeFlags(actor.isRiding(), actor.isPlayerSleeping(), actor.isSpectator(), actor.noClip);
        }

        private boolean requiresNative() { return riding || sleeping || spectator || noClip; }
    }

    private static final class Session {
        private final NetHandlerPlayServer handler;
        private final NetworkManager manager;
        private final EntityPlayerMP player;
        private final WorldServer world;
        private final PlayerPhysicalSnapshot.Scope scope;
        private PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle;
        private PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
        private final RemoteBoostBoundedDispatcher<PlayerPhysicsProtocol.Packet> dispatcher;
        private GIntentObservation source;
        private long proposal;
        private long outgoingSequence;
        private long nativeHandoffSerial;
        private long predictionGeneration;
        private long lastIncomingSequence = -1;
        private long lastOfferTick;
        private PlayerPhysicsProtocol.Packet pending;
        private long pendingTick;
        private boolean overflowReported;
        private long poseInitializationAttemptTick = Long.MIN_VALUE;
        private String lastPoseInitializationFailure;
        private Owned owner;
        private FreeOwnerTransaction.InstallationCustody failedInstallationCustody;

        private Session(NetHandlerPlayServer handler, long epoch) {
            this.handler = handler;
            manager = handler.netManager;
            player = handler.player;
            world = player.getServerWorld();
            scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), UUID.randomUUID(), player.dimension, epoch);
            dispatcher = new RemoteBoostBoundedDispatcher<>(8, task -> player.getServer().addScheduledTask(task));
        }
    }

    private static final class Owned {
        private NativeCardinalMotionReceipt nativeCardinalMotion;
        private long nativeCardinalMotionGeneration;
        private FreeTickResources.Store freeResources = new FreeTickResources.Store();
        private RuntimeException nativeEnvironmentFailure;
        private RecoveryCorrectionAnchor recoveryCorrectionAnchor;
        private int recoveryCorrectionConsumedId = -1;
        private boolean recoveryObservationRequired;
        private boolean recoveryPending;
        private boolean recoveryAwaitingApply;
        private boolean recoveryAwaitingMirror;
        private boolean recoveryTerminal;
        private boolean recoveryApplyReceived;
        private boolean recoveryOwnedSent;
        private boolean recoveryNativeResume;
        private boolean recoveryRestoreActive;
        private int recoveryNativeSends;
        private long recoveryNativeLastSendTick;
        private PlayerPhysicsProtocol.Packet recoveryPacket;
        private long recoveryWindowTick = Long.MIN_VALUE / 2;
        private long lastRecoveryRequestTick = Long.MIN_VALUE / 2;
        private int recoveryRequests;
        private long recoveryStartedTick;
        private long recoveryLastSendTick = Long.MIN_VALUE / 2;
        private int recoverySends;
        private PlayerPhysicalSnapshot snapshot;
        private FreeOwnerTransaction.InstallationCustody failedInstallationCustody;
        private final BoostMovementBudget budget;
        private final long adoptedAtTick;
        private long budgetTick;
        private long lastWorldTick;
        private long lastStateTick;
        private String lastFailure;
        private CardinalArcFailureKey lastCardinalArcFailureKey;
        private long suppressedCardinalArcFailureCount;
        private long lastCardinalArcFailureSummaryNanos;
        private String lastCardinalArcFailureSummary = "none";
        private long freeMovementFailuresSinceLastLog;
        private long lastFreeMovementFailureLogNanos;
        private long freeMovementPartialStopsSinceLastLog;
        private long lastFreeMovementPartialStopLogNanos;
        private long freeMovementBlockedStopsSinceLastLog;
        private long lastFreeMovementBlockedStopLogNanos;
        private long freeAttitudeRejectionsSinceLastLog;
        private long lastFreeAttitudeRejectionLogNanos;
        private long lastServerControlProbeTick = Long.MIN_VALUE;
        private ServerControlProbeKey lastObservedServerControlProbeKey;
        private final LocomotionTransitionProbe locomotionProbe = new LocomotionTransitionProbe();
        private long suppressedServerControlProbeCount;
        private String suppressedServerControlProbeOutcome = "none";
        private long pendingServerControlProbeEventCount;
        private String pendingServerControlProbeFirstEvent;
        private String pendingServerControlProbeLastEvent;
        private JumpFollowUpProbe jumpFollowUpProbe;
        private final OwnedPredictionTimeline predictionTimeline = new OwnedPredictionTimeline();
        private PlayerPhysicsProtocol.Packet latestAdmittedInput;
        private boolean latestAdmittedPredictionAccepted;
        private long predictionOwnedSequence = -1;
        private PlayerPhysicalSnapshot predictionCheckpoint;
        private PlayerBodyShape predictionShape;
        private Rotation3d predictionAttitude;
        private Optional<PlayerBodyReference.State> predictionReference;
        private PlayerPhysicsProtocol.Controls controls = IDLE;
        private long inputWorldTick = Long.MIN_VALUE / 2;
        private PlayerAttitudeRequest pendingAttitudeRequest;
        private boolean boostCarrier;
        private OptionalInt nativeTeleportId = OptionalInt.empty();
        private boolean teleportPending;
        private boolean nativeDelegated;
        private boolean resumeOffered;
        private int poseDepth;
        private PoseContext poseContext;
        private PoseGrowth poseGrowth;
        private PendingCardinalPose pendingCardinalPose;
        private long pendingCardinalPoseAttemptTick = Long.MIN_VALUE;
        private NativeResizeProposal trustedFreeResizeProposal;
        private PlayerPhysicalSnapshot eyeProfilePublicationPending;
        private long delegationProposal;
        private PresentationRelease presentationRelease;
        private Vec3 pendingImpulse = Vec3.ZERO;
        private final PlayerPhysicsStep.TravelState travel = new PlayerPhysicsStep.TravelState();
        private boolean manualFailureReceiptPending;
        private long lastBodyReferenceRequestId;
        private PlayerPhysicsProtocol.BodyReferenceIntent lastBodyReferenceIntent;
        private PlayerPhysicsProtocol.BodyReferenceReceipt bodyReferenceReceipt;
        private BodyReferenceLock bodyReferenceLock;
        private boolean nativeRestorePending;
        private boolean nativeRestoreAwaitingAcknowledgement;
        private boolean nativeRestoreAcknowledged;
        private final LinkedHashSet<NativeRestoreStateIdentity> nativeRestoreSentStates = new LinkedHashSet<>();
        private boolean nativeRestoreTerminalFailure;
        private boolean nativeHandoffPrepared;
        private NativeTransferPending nativeTransfer;
        private PlayerPhysicsProtocol.NativeTransferFailure nativeTransferRecoveryFailure;
        private boolean nativeTransferRecoveryPending;
        private boolean nativeTransferRecoveryTerminal;
        private int nativeTransferRecoveryAttempts;
        private NativeCorrectionCompletion nativeCorrectionCompletion;
        private NativeCorrectionControl nativeCorrectionControl;
        private boolean nativeRestoreReturnToOwned;
        private boolean nativeRestoreExplicitUnlock;
        private long nativeRestoreRequestId;
        private long nativeRestoreRevision = -1;
        private long nativeRestoreDeadlineWorldTick = Long.MIN_VALUE;
        private long nativeRestoreAckDeadlineWorldTick = Long.MIN_VALUE;
        private long nativeRestoreNextAttemptWorldTick = Long.MIN_VALUE;
        private long lastRestoreStateWorldTick = Long.MIN_VALUE;
        private PlayerPhysicsProtocol.BodyReferenceReason nativeRestoreReason;

        private Owned(PlayerPhysicalSnapshot snapshot, BoostMovementBudget budget, long tick, long worldTick,
                      boolean nativeNoGravity, float initialYaw) {
            this(budget, tick, worldTick, nativeNoGravity);
            this.snapshot = snapshot;
            initializeReference(snapshot.placement(), initialYaw);
        }

        /** Private staging object: no snapshot or session owner exists until initialization commits. */
        private Owned(BoostMovementBudget budget, long tick, long worldTick, boolean nativeNoGravity) {
            this.budget = budget;
            adoptedAtTick = tick;
            travel.nativeNoGravity = nativeNoGravity;
            budgetTick = budget.snapshot().lastTick();
            lastWorldTick = worldTick;
            lastStateTick = tick;
        }

        private void initializeReference(PlayerBodyPlacement placement, float initialYaw) {
            if (placement.shape() instanceof PlayerBodyShape.Cardinal cardinal) {
                CardinalFrame initialFrame = cardinal.frame();
                travel.bodyReference(PlayerBodyReference.State.initial(initialFrame,
                        PlayerBodyReference.initialHeading(initialFrame, initialYaw)));
            } else {
                travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(), Optional.empty()));
            }
        }

        private boolean holdsNativeGeometry() {
            return snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    || snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape
                    && shape.frame() != CardinalFrame.Y_POS
                    || nativeRestorePending || nativeRestoreAwaitingAcknowledgement || nativeHandoffPrepared
                    || nativeTransfer != null && !nativeTransfer.released;
        }
    }
}
