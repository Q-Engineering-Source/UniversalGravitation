package wi.unigrav.core.client;

import wi.unigrav.core.player.PlayerMobilityIntent;

import net.minecraft.world.World;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.entity.player.EntityPlayer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;

/** Client-thread installation state; application success, not reception, establishes ownership. */
public final class PlayerPhysicsClientSession {
    private TangentViewSample tangentView;
    private long tangentViewSequence;

    /** Read-only custody of a completed install, distinct from prediction and ACK authority. */
    static final class TangentViewCustody {
        private final PlayerPhysicsClientSession owner;
        private final Object lifetime, world;
        private final EntityPlayer actor;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerPhysicsProtocol.Packet packet;
        private final PreparedInstallation installation;
        private final long correction;
        private final PlayerBodyReference.Effective reference;
        private TangentViewCustody(PlayerPhysicsClientSession owner, EntityPlayer actor,
                                   PlayerBodyReference.Effective reference) {
            this.owner = owner; this.actor = actor; world = actor.world;
            lifetime = owner.lifetime; snapshot = owner.snapshot;
            packet = owner.acceptedInstallationPacket; correction = owner.correctionGeneration;
            installation = owner.lastPublishedInstallation;
            this.reference = reference;
        }
        boolean sameLifetime(PlayerPhysicsClientSession session) {
            return owner == session && session.lifetime == lifetime && actor.world == world
                    && session.correctionGeneration == correction && session.snapshot != null
                    && session.snapshot.scope().equals(snapshot.scope());
        }
        boolean current(PlayerPhysicsClientSession session) {
            return sameLifetime(session) && session.snapshot == snapshot
                    && session.acceptedInstallationPacket == packet
                    && session.lastPublishedInstallation == installation && session.drivesMotion()
                    && session.presentationSnapshot().filter(s -> s == snapshot).isPresent()
                    && session.acceptedEffectiveBodyReference(snapshot).filter(reference::equals).isPresent();
        }
    }

    /** Pure preparation: rejected or reentrant callers cannot advance pole continuity or sequence. */
    TangentViewSample prepareTangentView(EntityPlayer actor, long tick, Vec3 headForward, Float vanillaYaw) {
        var accepted = presentationSnapshot();
        if (accepted.isEmpty() || !actor.getUniqueID().equals(accepted.orElseThrow().scope().playerId())
                || actor.dimension != accepted.orElseThrow().scope().dimension()) return null;
        var reference = acceptedEffectiveBodyReference(accepted.orElseThrow());
        if (reference.isEmpty()) return null;
        var prior = tangentView != null && tangentView.sameLifetime(this, actor) ? tangentView : null;
        return TangentViewSample.prepare(new TangentViewCustody(this, actor, reference.orElseThrow()), actor,
                reference.orElseThrow(), Math.incrementExact(tangentViewSequence), tick, headForward, vanillaYaw, prior);
    }

    boolean commitTangentView(EntityPlayer actor, TangentViewSample prepared) {
        if (!canCommitTangentView(actor, prepared)) return false;
        tangentView = prepared; tangentViewSequence = prepared.viewSequence();
        return true;
    }

    boolean committedTangentViewCurrent(EntityPlayer actor,TangentViewSample prepared) {
        return prepared != null && tangentView == prepared && prepared.current(this,actor);
    }
    boolean canCommitTangentView(EntityPlayer actor, TangentViewSample prepared) {
        return !(prepared == null || !prepared.current(this, actor)
                || prepared.viewSequence() != Math.incrementExact(tangentViewSequence)
                || tangentView != null && tangentView.sameLifetime(this, actor)
                    && prepared.clientTick() <= tangentView.clientTick());
    }

    Optional<TangentViewSample> tangentView(EntityPlayer actor) {
        return tangentView != null && tangentView.current(this, actor) ? Optional.of(tangentView) : Optional.empty();
    }
    public enum BodyReferenceStatus { NONE, PENDING, LOCKED, DEFERRED, UNLOCKED, REJECTED }
    public record AcceptedBodyReference(long requestId, CardinalFrame frame, Vec3d tangentHeading) {
        public AcceptedBodyReference {
            if (requestId <= 0) throw new IllegalArgumentException("Accepted body reference needs a request id");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(tangentHeading, "tangentHeading");
        }
    }
    public record BodyReferenceView(BodyReferenceStatus status, long requestId,
                                    Optional<PlayerPhysicsProtocol.BodyReferenceIntent> pendingRequest,
                                    Optional<AcceptedBodyReference> acceptedLock,
                                    Optional<PlayerPhysicsProtocol.BodyReferenceReason> reason) {
        public BodyReferenceView {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(pendingRequest, "pendingRequest");
            Objects.requireNonNull(acceptedLock, "acceptedLock");
            Objects.requireNonNull(reason, "reason");
        }
        private static BodyReferenceView none() {
            return new BodyReferenceView(BodyReferenceStatus.NONE, 0, Optional.empty(), Optional.empty(), Optional.empty());
        }
        private static BodyReferenceView pending(PlayerPhysicsProtocol.BodyReferenceIntent intent,
                                                 PlayerPhysicsProtocol.BodyReferenceReceipt previous) {
            return new BodyReferenceView(BodyReferenceStatus.PENDING, intent.requestId(), Optional.of(intent),
                    acceptedLock(previous), previous != null && previous.requestId() == intent.requestId()
                    ? previous.reason() : Optional.empty());
        }
        private static BodyReferenceView receipt(PlayerPhysicsProtocol.BodyReferenceReceipt receipt) {
            BodyReferenceStatus status = switch (receipt.decision()) {
                case LOCKED -> BodyReferenceStatus.LOCKED;
                case DEFERRED -> BodyReferenceStatus.DEFERRED;
                case UNLOCKED -> BodyReferenceStatus.UNLOCKED;
                case REJECTED -> BodyReferenceStatus.REJECTED;
            };
            return new BodyReferenceView(status, receipt.requestId(), Optional.empty(),
                    acceptedLock(receipt), receipt.reason());
        }
        private static Optional<AcceptedBodyReference> acceptedLock(PlayerPhysicsProtocol.BodyReferenceReceipt receipt) {
            return receipt == null || receipt.acceptedFrame().isEmpty() ? Optional.empty()
                    : Optional.of(new AcceptedBodyReference(receipt.acceptedLockRequestId(),
                    receipt.acceptedFrame().orElseThrow(), receipt.tangentHeading().orElseThrow()));
        }
    }

    /** Diagnostic observation only; never consulted to authorize a packet or movement. */
    public enum StateDisposition {
        NOT_STATE, CONTEXT_MISMATCH, AWAITING_OFFER, APPLIED, RETRANSMISSION, PROPOSAL_MISMATCH, STALE_SEQUENCE,
        NATIVE_DELEGATED, TELEPORT_MISMATCH, REVISION_CONFLICT, ATTEMPT_FENCED,
        INSTALL_REJECTED, LIFETIME_CHANGED, APPLICATION_THREW, NATIVE_ENVIRONMENT_THREW, PREDICTION_FAILED
    }
    public enum InstallationDisposition { REJECTED, RETRANSMISSION, INSTALL }
    enum AcknowledgementDomain { ORDINARY_MODE, NATIVE_RESTORE }

    /** Result of side-effect-free packet classification. A rejected result has no token. */
    public static final class InstallationPreparation {
        private final InstallationDisposition disposition;
        private final StateDisposition rejection;
        private final PreparedInstallation prepared;

        private InstallationPreparation(InstallationDisposition disposition, StateDisposition rejection,
                                        PreparedInstallation prepared) {
            this.disposition = disposition;
            this.rejection = rejection;
            this.prepared = prepared;
        }

        public InstallationDisposition disposition() { return disposition; }
        public Optional<StateDisposition> rejection() { return Optional.ofNullable(rejection); }
        public Optional<PreparedInstallation> prepared() { return Optional.ofNullable(prepared); }
    }

    /** Opaque single-session token containing every value needed by the final no-callback publish. */
    public static final class PreparedInstallation {
        private final PlayerPhysicsClientSession session;
        private final Object lifetime;
        private final long protocolGeneration;
        private final long publishGeneration;
        private final PlayerPhysicsProtocol.Packet packet;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerPhysicalSnapshot expectedSnapshot;
        private final ExpectedInstallationState expectedState;
        private final InstallationDisposition disposition;
        private final boolean replacingAcceptedCustody;
        private final PlayerPhysicsProtocol.BodyReferenceReceipt nextBodyReferenceReceipt;
        private final PlayerPhysicsProtocol.BodyReferenceIntent nextPendingBodyReferenceIntent;
        private final PlayerBodyReference.State nextBodyReferenceState;
        private final boolean nativeRestoreAcknowledgementCandidate;
        private boolean begun;
        private boolean revoked;
        private boolean acknowledgementAuthorized;

        private PreparedInstallation(PlayerPhysicsClientSession session, Object lifetime, long protocolGeneration,
                                     long publishGeneration, PlayerPhysicsProtocol.Packet packet,
                                     PlayerPhysicalSnapshot snapshot, PlayerPhysicalSnapshot expectedSnapshot,
                                     ExpectedInstallationState expectedState, InstallationDisposition disposition,
                                     boolean replacingAcceptedCustody, BodyReferenceUpdate bodyReferenceUpdate) {
            this.session = session;
            this.lifetime = lifetime;
            this.protocolGeneration = protocolGeneration;
            this.publishGeneration = publishGeneration;
            this.packet = packet;
            this.snapshot = snapshot;
            this.expectedSnapshot = expectedSnapshot;
            this.expectedState = expectedState;
            this.disposition = disposition;
            this.replacingAcceptedCustody = replacingAcceptedCustody;
            this.nextBodyReferenceReceipt = bodyReferenceUpdate.receipt();
            this.nextPendingBodyReferenceIntent = bodyReferenceUpdate.pendingIntent();
            this.nextBodyReferenceState = bodyReferenceUpdate.state();
            this.nativeRestoreAcknowledgementCandidate = bodyReferenceUpdate.nativeRestoreAcknowledgementCandidate();
        }

        /** Immutable candidate geometry the authorized owner may install before publication. */
        public PlayerPhysicalSnapshot snapshot() { return snapshot; }

        InstallationDisposition disposition() { return disposition; }
        PlayerPhysicsProtocol.Packet packetIdentity() { return packet; }
        Object lifetimeIdentity() { return lifetime; }
    }

    private record BodyReferenceUpdate(PlayerPhysicsProtocol.BodyReferenceReceipt receipt,
                                        PlayerPhysicsProtocol.BodyReferenceIntent pendingIntent,
                                        PlayerBodyReference.State state,
                                        boolean nativeRestoreAcknowledgementCandidate) { }

    private record ExpectedInstallationState(PlayerPhysicsClientOffer offer,
                                             PlayerPhysicsProtocol.Packet submitted,
                                             PlayerPhysicsProtocol.Packet offered,
                                             PlayerPhysicsProtocol.Packet attempted,
                                             PlayerPhysicsProtocol.Packet failedInstallationPacket,
                                             PlayerPhysicalSnapshot snapshot,
                                             PlayerPhysicalSnapshot.Scope custodyScope,
                                             long incomingSequence,
                                             long outgoingSequence,
                                             boolean installationPending,
                                             boolean installationFailed,
                                             boolean nativeDelegated,
                                             long delegationProposal,
                                             OptionalInt nativeTeleportId,
                                             boolean nativeCorrectionPending,
                                             long nextBodyReferenceRequestId,
                                             PlayerPhysicsProtocol.BodyReferenceIntent pendingBodyReferenceIntent,
                                             PlayerPhysicsProtocol.BodyReferenceReceipt bodyReferenceReceipt,
                                             PlayerBodyReference.State bodyReferenceState,
                                              PlayerPhysicsProtocol.BodyReferenceAcknowledgement ordinaryModeAcknowledgement,
                                              PlayerPhysicsProtocol.BodyReferenceAcknowledgement nativeRestoreAcknowledgement) {
        private boolean matches(PlayerPhysicsClientSession current, PreparedInstallation prepared) {
            if (current.offer != offer || current.submitted != submitted || current.offered != offered
                    || current.snapshot != snapshot || current.custodyScope != custodyScope
                    || current.incomingSequence != incomingSequence || current.outgoingSequence != outgoingSequence
                    || current.nativeDelegated != nativeDelegated
                    || current.delegationProposal != delegationProposal
                    || !current.nativeTeleportId.equals(nativeTeleportId)
                    || current.nativeCorrectionPending != nativeCorrectionPending
                    || current.nextBodyReferenceRequestId != nextBodyReferenceRequestId
                    || current.pendingBodyReferenceIntent != pendingBodyReferenceIntent
                    || current.bodyReferenceReceipt != bodyReferenceReceipt
                    || current.bodyReferenceState != bodyReferenceState
                    || current.ordinaryModeAcknowledgement != ordinaryModeAcknowledgement
                    || current.nativeRestoreAcknowledgement != nativeRestoreAcknowledgement) return false;
            if (prepared.disposition == InstallationDisposition.INSTALL && prepared.begun) {
                return current.activeAttempt == prepared && current.applying
                        && current.attempted == prepared.packet
                        && current.failedInstallationPacket == failedInstallationPacket
                        && current.installationPending == prepared.replacingAcceptedCustody
                        && current.installationFailed == installationFailed;
            }
            return current.activeAttempt == null && !current.applying && current.attempted == attempted
                    && current.failedInstallationPacket == failedInstallationPacket
                    && current.installationPending == installationPending
                    && current.installationFailed == installationFailed;
        }
    }

    private StateDisposition lastStateDisposition = StateDisposition.NOT_STATE;
    public StateDisposition lastStateDisposition() { return lastStateDisposition; }
    private boolean observed(StateDisposition disposition, boolean accepted) {
        lastStateDisposition = disposition;
        return accepted;
    }
    /** Local native writers are retired only after actual application, and resume during native delegation. */
    public void runLegacyMotion(EntityPlayer actor, Runnable original) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(original, "original");
        if (holdsNativeMovement(actor)) return;
        original.run();
    }

    /** Healthy pose callbacks update mod state before accepted geometry is restored. */
    private BooleanSupplier predictedPoseRestoration;

    void predictedPoseRestoration(BooleanSupplier restoration) {
        predictedPoseRestoration = restoration;
    }

    /** One invocation's custody, captured before any third-party callback. It grants no new write authority. */
    static final class NativePoseLease {
        private final PlayerPhysicsClientSession session;
        private final EntityPlayer actor;
        private final World world;
        private final Object lifetime;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsProtocol.Packet packet;
        private final OptionalInt correction;
        private final long correctionGeneration;
        private final PreparedInstallation installation;
        private final BooleanSupplier restorer;
        private final BooleanSupplier ownerCurrent;
        private NativePoseLease(PlayerPhysicsClientSession session, EntityPlayer actor,
                BooleanSupplier ownerCurrent) {
            this.session = session; this.actor = actor; world = actor.world;
            lifetime = session.lifetime; accepted = session.snapshot; packet = session.acceptedInstallationPacket;
            correction = session.nativeTeleportId; correctionGeneration = session.correctionGeneration;
            installation = session.lastPublishedInstallation; restorer = session.predictedPoseRestoration;
            this.ownerCurrent = ownerCurrent;
        }
        boolean sessionCurrent() {
            return session.lifetime == lifetime && session.snapshot == accepted
                    && session.acceptedInstallationPacket == packet && session.nativeTeleportId.equals(correction)
                    && session.correctionGeneration == correctionGeneration
                    && session.lastPublishedInstallation == installation && session.predictedPoseRestoration == restorer;
        }
        boolean identityCurrent() {
            return actor.world == world && accepted != null
                    && actor.getUniqueID().equals(accepted.scope().playerId())
                    && actor.dimension == accepted.scope().dimension() && ownerCurrent.getAsBoolean() && sessionCurrent();
        }
        boolean mayRestore() {
            // isEntityAlive can consult mod state. Recheck the exact owner after that getter too.
            boolean eligible = !actor.isDead && actor.isEntityAlive() && identityCurrent()
                    && !session.nativeCorrectionPending && !session.mutationQuarantine
                    && session.holdsNativeMovement(actor);
            return eligible && identityCurrent() && !actor.isDead;
        }
    }
    NativePoseLease nativePoseLease(EntityPlayer actor, BooleanSupplier ownerCurrent) {
        return new NativePoseLease(this, Objects.requireNonNull(actor), Objects.requireNonNull(ownerCurrent));
    }
    public void runNativePose(EntityPlayer actor, Runnable original) {
        runNativePose(actor, original, nativePoseLease(actor, () -> true));
    }
    void runNativePose(EntityPlayer actor, Runnable original, NativePoseLease lease) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(lease, "lease");
        if (lease.session != this || lease.actor != actor) throw new IllegalArgumentException("Foreign native pose lease");
        if (installationFenceActive(actor)) return;
        if (!drivesMotion() || !actor.getUniqueID().equals(snapshot.scope().playerId())
                || actor.dimension != snapshot.scope().dimension()
                || PlayerKinematics.requiresNative(actor) && !nativeMovementMustRemainGated()) {
            original.run();
            return;
        }
        Throwable failure = null;
        try { original.run(); }
        catch (Throwable cause) { failure = cause; }
        try {
            if (lease.mayRestore()) {
                boolean restored = lease.restorer != null ? lease.restorer.getAsBoolean()
                        : install == null ? PlayerPhysicsClientMirror.install(actor, lease.accepted) : install.test(lease.accepted);
                // A false return can end its own lease by performing real retirement. It is not
                // a rejection by an owner that no longer exists, and must not mint failure authority.
                if (lease.mayRestore() && !restored)
                    throw new IllegalStateException("Accepted native pose mirror restoration was rejected");
            }
        } catch (Throwable cleanup) {
            if (failure == null) failure = cleanup;
            else if (failure != cleanup) failure.addSuppressed(cleanup);
            if (lease.identityCurrent() && lease.packet != null) {
                installationFailed = true;
                failedInstallationPacket = lease.packet;
                attempted = lease.packet;
                lastStateDisposition = StateDisposition.APPLICATION_THREW;
                throw new NativePoseRestorationFailure(failure, lease.lifetime, lease.accepted, lease.correction);
            }
        }
        if (failure != null) PlayerPhysicsClientSession.<RuntimeException>rethrow(failure);
    }

    /** Only an actual accepted mirror restore failure can mint this retirement token. */
    static final class NativePoseRestorationFailure extends RuntimeException {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot accepted;
        private final OptionalInt correction;
        private NativePoseRestorationFailure(Throwable cause, Object lifetime,
                PlayerPhysicalSnapshot accepted, OptionalInt correction) {
            super("Accepted native pose mirror restoration failed", cause);
            this.lifetime = lifetime;
            this.accepted = accepted;
            this.correction = correction;
        }
        boolean current(PlayerPhysicsClientSession session) {
            return session.lifetime == lifetime && session.snapshot == accepted
                    && session.nativeTeleportId.equals(correction) && session.installationFenceActive();
        }
    }
    /** Installed mirror provenance; a geometry revision does not create a new resource lifetime. */
    static final class NativeEnvironmentLifetime {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsProtocol.Packet packet;
        private final OptionalInt correction;
        private NativeEnvironmentLifetime(PlayerPhysicsClientSession session, PlayerPhysicalSnapshot accepted) {
            lifetime = session.lifetime;
            this.accepted = accepted;
            packet = session.acceptedInstallationPacket;
            correction = session.nativeTeleportId;
        }
        long correctionId() { return correction.orElse(-1); }
        boolean current(PlayerPhysicsClientSession session) {
            return session.lifetime == lifetime && session.snapshot == accepted
                    && session.acceptedInstallationPacket == packet && session.nativeTeleportId.equals(correction)
                    && session.presentationSnapshot().filter(s -> s == accepted).isPresent();
        }
    }

    /** Geometry eligibility is checked by the environment boundary, never used to discard Free custody. */
    Optional<PlayerPhysicalSnapshot> nativeEnvironmentSnapshot(EntityPlayer actor) {
        return presentationSnapshot().filter(s -> s.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && actor.getUniqueID().equals(s.scope().playerId()) && actor.dimension == s.scope().dimension());
    }

    /** A retained Free mirror awaits its actual correction state before any native environment operation. */
    boolean nativeEnvironmentMustPause(EntityPlayer actor) {
        return snapshot != null && !nativeDelegated
                && snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && actor.getUniqueID().equals(snapshot.scope().playerId()) && actor.dimension == snapshot.scope().dimension()
                && (nativeCorrectionPending || applying || installationFenceActive());
    }

    NativeEnvironmentLifetime nativeEnvironmentLifetime(PlayerPhysicalSnapshot accepted) {
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || snapshot != accepted || acceptedInstallationPacket == null
                || presentationSnapshot().filter(s -> s == accepted).isEmpty())
            throw new IllegalStateException("No current installed Free mirror for native environment");
        return new NativeEnvironmentLifetime(this, accepted);
    }

    /** Evidence for one actual pending owned correction, consumed only by a matching installation. */
    static final class NativeEnvironmentCorrection {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final OptionalInt correction;
        private final long generation;
        private NativeEnvironmentCorrection(PlayerPhysicsClientSession session) {
            lifetime = session.lifetime; scope = session.snapshot.scope();
            correction = session.nativeTeleportId; generation = session.correctionGeneration;
        }
        long correctionId() { return correction.orElseThrow(); }
        boolean pending(PlayerPhysicsClientSession session) {
            return session.lifetime == lifetime && session.correctionGeneration == generation
                    && session.nativeTeleportId.equals(correction) && session.nativeCorrectionPending
                    && session.snapshot != null && scope.equals(session.snapshot.scope())
                    && !session.nativeDelegated && !session.installationFailed && !session.installationFenceActive();
        }

        boolean completed(PlayerPhysicsClientSession session, PreparedInstallation prepared, PlayerPhysicsProtocol.Packet packet) {
            return session.lifetime == lifetime && session.correctionGeneration == generation
                    && session.nativeTeleportId.equals(correction) && packet.nativeTeleportId().equals(correction)
                    && !session.nativeCorrectionPending && !session.installationFenceActive()
                    && prepared != null && prepared.session == session && !prepared.revoked
                    && prepared.disposition == InstallationDisposition.INSTALL
                    && session.lastPublishedInstallation == prepared && session.acceptedInstallationPacket == packet
                    && prepared.packet == packet && session.snapshot == prepared.snapshot
                    && scope.equals(session.snapshot.scope()) && !session.nativeDelegated;
        }
    }

    NativeEnvironmentCorrection captureNativeEnvironmentCorrection() {
        if (!nativeCorrectionPending || nativeTeleportId.isEmpty() || snapshot == null || nativeDelegated
                || installationFailed)
            throw new IllegalStateException("No pending Free correction to capture");
        return new NativeEnvironmentCorrection(this);
    }

    /** Captures the old delegation before receiveOffer clears its proposal and correction state. */
    static final class NativeEnvironmentResume {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot previous;
        private final PlayerPhysicsProtocol.Packet offer;
        private final long generation, correctionGeneration;
        private NativeEnvironmentResume(PlayerPhysicsClientSession session, PlayerPhysicsProtocol.Packet offer) {
            lifetime = session.lifetime; previous = session.snapshot; this.offer = offer;
            generation = session.protocolGeneration; correctionGeneration = session.correctionGeneration;
        }
        private NativeEnvironmentResume(NativeEnvironmentResume prior, PlayerPhysicsClientSession session,
                PlayerPhysicsProtocol.Packet next) {
            lifetime = prior.lifetime; previous = prior.previous; offer = next;
            generation = session.protocolGeneration; correctionGeneration = prior.correctionGeneration;
        }
        NativeEnvironmentResume nextOffer(PlayerPhysicsClientSession session, PlayerPhysicsProtocol.Packet next) {
            boolean same = offer.equals(next);
            if (session.lifetime != lifetime || session.correctionGeneration != correctionGeneration
                    || session.snapshot != previous || !session.nativeDelegated || session.offered != offer
                    || session.applying || session.installationFailed || session.installationFenceActive()
                    || session.nativeCorrectionPending || session.nativeTransferFailure != null
                    || session.mutationQuarantine || session.activeAttempt != null
                    || next.kind() != PlayerPhysicsProtocol.Kind.OFFER || !next.scope().equals(offer.scope())
                    || !same && (next.offerSequence() <= offer.offerSequence() || next.sequence() <= session.incomingSequence)
                    || session.submitted != null && (!session.submitted.scope().equals(offer.scope())
                        || session.submitted.offerSequence() != offer.offerSequence())) return null;
            return new NativeEnvironmentResume(this, session, next);
        }
        boolean accepted(PlayerPhysicsClientSession session) {
            return session.lifetime == lifetime && session.correctionGeneration == correctionGeneration
                    && session.protocolGeneration == generation + 1 && session.offered == offer
                    && session.snapshot == previous && session.nativeDelegated
                    && (session.submitted == null || session.submitted.scope().equals(offer.scope())
                        && session.submitted.offerSequence() == offer.offerSequence())
                    && session.nativeTeleportId.isEmpty() && !session.nativeCorrectionPending
                    && !session.installationFenceActive() && !session.installationFailed;
        }
        boolean completed(PlayerPhysicsClientSession session, PreparedInstallation prepared,
                PlayerPhysicsProtocol.Packet packet) {
            return session.lifetime == lifetime && session.correctionGeneration == correctionGeneration
                    && session.offered == offer && session.submitted != null
                    && session.submitted.kind() == PlayerPhysicsProtocol.Kind.APPLY
                    && session.submitted.scope().equals(offer.scope())
                    && session.submitted.offerSequence() == offer.offerSequence()
                    && packet.kind() == PlayerPhysicsProtocol.Kind.OWNED && packet.scope().equals(offer.scope())
                    && packet.offerSequence() == offer.offerSequence() && packet.nativeTeleportId().isEmpty()
                    && session.nativeTeleportId.isEmpty() && !session.nativeCorrectionPending
                    && !session.nativeDelegated && !session.installationFenceActive() && !session.installationFailed
                    && prepared != null && prepared.session == session && prepared.lifetime == lifetime
                    && prepared.expectedSnapshot == previous && prepared.expectedState.submitted() == session.submitted
                    && prepared.expectedState.offered() == offer && !prepared.revoked
                    && prepared.disposition == InstallationDisposition.INSTALL && prepared.packet == packet
                    && session.lastPublishedInstallation == prepared && session.acceptedInstallationPacket == packet
                    && session.snapshot == prepared.snapshot;
        }
    }

    NativeEnvironmentResume captureNativeEnvironmentResume(PlayerPhysicsProtocol.Packet offer) {
        return allowsHealthyDelegatedResumeOffer(offer) ? new NativeEnvironmentResume(this, offer) : null;
    }

    /** This token attributes fluid observation/callback failure, never a mirror setter failure. */
    static final class NativeEnvironmentFailure extends RuntimeException {
        private final NativeEnvironmentLifetime provenance;
        private NativeEnvironmentFailure(Throwable cause, NativeEnvironmentLifetime provenance) {
            super("Installed Free native environment failed", cause);
            this.provenance = provenance;
        }
        boolean current(PlayerPhysicsClientSession session) {
            return session.lifetime == provenance.lifetime && session.snapshot == provenance.accepted
                    && session.acceptedInstallationPacket == provenance.packet
                    && session.nativeTeleportId.equals(provenance.correction) && session.installationFenceActive();
        }
    }

    NativeEnvironmentFailure failNativeEnvironment(NativeEnvironmentLifetime provenance, Throwable cause) {
        Objects.requireNonNull(cause);
        if (!provenance.current(this)) {
            PlayerPhysicsClientSession.<RuntimeException>rethrow(cause);
            throw new AssertionError("unreachable");
        }
        installationFailed = true;
        failedInstallationPacket = provenance.packet;
        attempted = provenance.packet;
        lastStateDisposition = StateDisposition.NATIVE_ENVIRONMENT_THREW;
        return new NativeEnvironmentFailure(cause, provenance);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T { throw (T) failure; }
    private final Predicate<PlayerPhysicalSnapshot> install;
    private final Runnable retireLegacy;
    private final Runnable enterNative;
    private PlayerPhysicsClientOffer offer = new PlayerPhysicsClientOffer();
    private PlayerPhysicsProtocol.Packet submitted;
    private PlayerPhysicsProtocol.Packet offered;
    private long environmentRecoveryCorrectionGeneration = -1;
    private PlayerPhysicsProtocol.Packet attempted;
    private PlayerPhysicsProtocol.Packet failedInstallationPacket;
    private PlayerPhysicsProtocol.Packet acceptedInstallationPacket;
    private PreparedInstallation activeAttempt;
    private PlayerPhysicalSnapshot snapshot;
    private Object lifetime = new Object();
    private long protocolGeneration;
    private long incomingSequence = -1;
    private long outgoingSequence;
    private boolean applying;
    private boolean installationPending;
    private boolean installationFailed;
    private boolean nativeDelegated;
    private boolean retired;
    private PlayerPhysicalSnapshot.Scope custodyScope;
    private long delegationProposal;
    private OptionalInt nativeTeleportId = OptionalInt.empty();
    private boolean nativeCorrectionPending;
    private long correctionGeneration;
    private boolean mutationQuarantine;

    /** Identity captured at the responsible boundary; it grants no installation or ACK authority. */
    static final class PredictionLifetime {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerPhysicsProtocol.Packet acceptedPacket;
        private final OptionalInt teleportId;
        private final long correctionGeneration;
        private PredictionLifetime(PlayerPhysicsClientSession session) {
            lifetime = session.lifetime; snapshot = session.snapshot; acceptedPacket = session.acceptedInstallationPacket;
            teleportId = session.nativeTeleportId; correctionGeneration = session.correctionGeneration;
        }
        boolean current(PlayerPhysicsClientSession session) {
            return session.lifetime == lifetime && session.snapshot == snapshot
                    && session.acceptedInstallationPacket == acceptedPacket
                    && session.nativeTeleportId.equals(teleportId) && session.correctionGeneration == correctionGeneration;
        }
    }
    /** A normal correction may rearm once after a later proven STATE, including Unavailable then Ready.
     * This identity deliberately does not retain the preceding packet or geometry and grants no write/ACK authority. */
    static final class CorrectionPredictionIdentity {
        private final Object lifetime;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final OptionalInt teleportId;
        private final long correctionGeneration;
        private final PlayerPhysicsProtocol.Packet offered, submitted;
        private CorrectionPredictionIdentity(PlayerPhysicsClientSession session) {
            lifetime = session.lifetime; scope = session.snapshot.scope();
            teleportId = session.nativeTeleportId; correctionGeneration = session.correctionGeneration;
            offered = session.offered; submitted = session.submitted;
        }
        boolean current(PlayerPhysicsClientSession session) {
            return session.correctionPredictionIdentity == this && session.lifetime == lifetime
                    && session.snapshot != null && session.snapshot.scope().equals(scope)
                    && session.nativeTeleportId.equals(teleportId) && session.correctionGeneration == correctionGeneration
                    && session.offered == offered && session.submitted == submitted
                    && session.normalCorrectionInstallationCurrent();
        }
        boolean consume(PlayerPhysicsClientSession session) {
            if (!current(session) || !session.predictionEligible()) return false;
            session.correctionPredictionIdentity = null;
            return true;
        }
    }
    private CorrectionPredictionIdentity correctionPredictionIdentity;
    CorrectionPredictionIdentity correctionPredictionIdentity() {
        if (!nativeCorrectionPending || snapshot == null || acceptedInstallationPacket == null
                || !normalCorrectionInstallationCurrent())
            throw new IllegalStateException("No normal accepted correction identity");
        correctionPredictionIdentity = new CorrectionPredictionIdentity(this);
        return correctionPredictionIdentity;
    }
    // retired records the once-only legacy callback, not current UG installation eligibility.
    private boolean normalCorrectionInstallationCurrent() {
        return snapshot != null && acceptedInstallationPacket != null && offered != null && submitted != null
                && !nativeDelegated && !applying && !installationFenceActive()
                && !installationFailed && !mutationQuarantine
                && snapshot.scope().equals(custodyScope) && offered.scope().equals(custodyScope)
                && submitted.scope().equals(custodyScope) && acceptedInstallationPacket.scope().equals(custodyScope)
                && offered.offerSequence() == submitted.offerSequence()
                && acceptedInstallationPacket.offerSequence() == submitted.offerSequence();
    }
    boolean predictionMutationQuarantined(EntityPlayer actor) {
        return mutationQuarantine && snapshot != null && actor.getUniqueID().equals(snapshot.scope().playerId())
                && actor.dimension == snapshot.scope().dimension();
    }
    /** Native correction retains physical INPUT custody without a presentation proof. */
    boolean ordinaryCorrectionInputEligible() {
        return nativeCorrectionPending && drivesMotion() && !mutationQuarantine && !applying;
    }
    boolean predictionEligible() {
        return nativeTransferFailure == null && drivesMotion() && !nativeCorrectionPending && !mutationQuarantine && !applying;
    }
    PredictionLifetime predictionLifetime() {
        if (!predictionEligible() || acceptedInstallationPacket == null)
            throw new IllegalStateException("No eligible accepted prediction lifetime");
        return new PredictionLifetime(this);
    }
    PredictionLifetime correctionLifetime() {
        if (!nativeCorrectionPending || acceptedInstallationPacket == null || snapshot == null)
            throw new IllegalStateException("No current correction lifetime");
        return new PredictionLifetime(this);
    }
    void quarantinePrediction(PredictionLifetime token) {
        if (token.current(this)) { correctionPredictionIdentity = null; mutationQuarantine = true; revokeStateAcknowledgements(); }
    }
    /** Normal eligibility refusal retains accepted custody and its ACK; the callback proves this exact canonical install. */
    boolean completePredictionSuspension(PredictionLifetime token, BooleanSupplier restoreCanonical) {
        if (!token.current(this) || !predictionEligible()) return false;
        if (!restoreCanonical.getAsBoolean() || !token.current(this) || !predictionEligible()) return false;
        predictedPoseRestoration = null;
        return true;
    }
    boolean failPrediction(PredictionLifetime token) {
        if (!token.current(this) || token.acceptedPacket == null || applying || activeAttempt != null) return false;
        mutationQuarantine = true; installationFailed = true;
        failedInstallationPacket = token.acceptedPacket; attempted = token.acceptedPacket;
        lastStateDisposition = StateDisposition.PREDICTION_FAILED;
        revokeStateAcknowledgements();
        return true;
    }

    private long nextBodyReferenceRequestId;
    private PlayerPhysicsProtocol.BodyReferenceIntent pendingBodyReferenceIntent;
    private PlayerPhysicsProtocol.BodyReferenceReceipt bodyReferenceReceipt;
    private PlayerBodyReference.State bodyReferenceState;
    private long mobilityPressId;
    private boolean mobilityHeld;
    private boolean mobilityRequiresRelease;
    private PlayerMobilityIntent.Request pendingMobilityRequest;
    /** Local request high-water survives owner/world reset so rejected ids are never reused. */
    private long mobilityRequestSequence;

    public Optional<PlayerMobilityIntent.State> mobilityState() {
        return acceptedInstallationPacket == null ? Optional.empty() : acceptedInstallationPacket.mobilityState();
    }
    public boolean requestMobility(PlayerMobilityIntent.Mode mode,
            PlayerMobilityIntent.Hover hover) {
        Objects.requireNonNull(mode); Objects.requireNonNull(hover);
        if (!predictionEligible()) return false;
        long accepted = mobilityState().map(PlayerMobilityIntent.State::acceptedRequest).orElse(0L);
        long prior = Math.max(accepted, mobilityRequestSequence);
        pendingMobilityRequest = new PlayerMobilityIntent.Request(Math.incrementExact(prior), mode, hover);
        mobilityRequestSequence = pendingMobilityRequest.id();
        return true;
    }
    public Optional<PlayerMobilityIntent.Request> pendingMobilityRequest() {
        return Optional.ofNullable(pendingMobilityRequest);
    }
    /** Called once by the END input writer, including focus loss/release. */
    PlayerPhysicsProtocol.Controls sampleMobility(PlayerPhysicsProtocol.Controls controls, boolean held,
            boolean focused, boolean controlEligible) {
        if (!held) mobilityRequiresRelease = false;
        boolean blocked = mobilityRequiresRelease && held;
        boolean pressed = focused && held && !blocked && !mobilityHeld;
        if (pressed) mobilityPressId = Math.incrementExact(mobilityPressId);
        mobilityHeld = focused && held;
        var accepted = mobilityState().orElse(null);
        if (!controlEligible) pendingMobilityRequest = null;
        if (pendingMobilityRequest != null && accepted != null) {
            boolean acceptedRequest = accepted.acceptedRequest() == pendingMobilityRequest.id()
                    && accepted.mode() == pendingMobilityRequest.mode() && accepted.hover() == pendingMobilityRequest.hover();
            boolean supersededRequest = accepted.acceptedRequest() > pendingMobilityRequest.id();
            boolean revokedAcceptedRequest = accepted.acceptedRequest() == pendingMobilityRequest.id()
                    && !acceptedRequest && accepted.reason() == PlayerMobilityIntent.Reason.CONTROL_UNAVAILABLE;
            if (acceptedRequest || supersededRequest || revokedAcceptedRequest) {
                pendingMobilityRequest = null;
            }
        }
        var input = new PlayerMobilityIntent.Input(Optional.ofNullable(pendingMobilityRequest),
                mobilityPressId, mobilityHeld && !blocked, focused);
        return controls.withMobility(input).withJump(controls.jump() && pressed);
    }
    private PlayerPhysicsProtocol.BodyReferenceAcknowledgement ordinaryModeAcknowledgement;
    private PlayerPhysicsProtocol.BodyReferenceAcknowledgement nativeRestoreAcknowledgement;
    private PreparedInstallation lastPublishedInstallation;

    public PlayerPhysicsClientSession(Predicate<PlayerPhysicalSnapshot> install, Runnable retireLegacy, Runnable enterNative) {
        this.install = Objects.requireNonNull(install, "install");
        this.retireLegacy = Objects.requireNonNull(retireLegacy, "retireLegacy");
        this.enterNative = Objects.requireNonNull(enterNative, "enterNative");
    }

    /** Production owners use the prepared installation API; no Predicate installer is installed. */
    public PlayerPhysicsClientSession(Runnable retireLegacy, Runnable enterNative) {
        this.install = null;
        this.retireLegacy = Objects.requireNonNull(retireLegacy, "retireLegacy");
        this.enterNative = Objects.requireNonNull(enterNative, "enterNative");
    }

    /** Pure query used by the client-only bridge after Session.receive accepted this exact OFFER. */
    public boolean acceptedOfferCurrent(PlayerPhysicsProtocol.Packet exactPacket) {
        return exactPacket != null && exactPacket.kind() == PlayerPhysicsProtocol.Kind.OFFER
                && offered == exactPacket && offer.isCurrentOffer(exactPacket)
                && !applying && activeAttempt == null;
    }

    boolean currentAcceptedOfferIdentity(PlayerPhysicsProtocol.Packet exactPacket) {
        return exactPacket != null && exactPacket.kind() == PlayerPhysicsProtocol.Kind.OFFER
                && offered == exactPacket && offer.isCurrentOffer(exactPacket);
    }

    /** Monotonic session owner generation captured by an accepted OFFER context. */
    public long ownerGeneration() {
        return protocolGeneration;
    }

    /** Purely validate and precompute the complete state update for one OWNED/STATE packet. */
    public InstallationPreparation prepareInstallation(PlayerPhysicsProtocol.Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (applying || activeAttempt != null) return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
        if (packet.kind() != PlayerPhysicsProtocol.Kind.OWNED && packet.kind() != PlayerPhysicsProtocol.Kind.STATE)
            return rejectedPreparation(StateDisposition.NOT_STATE);
        if (submitted == null || !submitted.scope().equals(packet.scope())
                || submitted.offerSequence() != packet.offerSequence())
            return rejectedPreparation(StateDisposition.PROPOSAL_MISMATCH);
        if (packet.sequence() <= incomingSequence) return rejectedPreparation(StateDisposition.STALE_SEQUENCE);
        boolean transferRecovery = nativeTransferFailure != null
                && packet.nativeTransfer().filter(value -> value instanceof PlayerPhysicsProtocol.NativeTransferRecovery recovery
                    && recovery.failed().equals(nativeTransferFailure)).isPresent()
                && isNativeRestorePurpose(packet) && snapshot != null
                && packet.snapshot().orElseThrow().revision() > snapshot.revision();
        if (packet.nativeTransfer().isPresent() && !transferRecovery
                && !(nativeTransferRecoveryRestore && packet.nativeTransfer().orElseThrow()
                    instanceof PlayerPhysicsProtocol.NativeTransferRecovery recovery
                    && recovery.failed().equals(nativeTransferFailure)))
            return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
        if (nativeTransferFailure != null && !transferRecovery && !nativeTransferRecoveryRestore)
            return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
        if (nativeTransferFailure != null && packet.nativeTransfer().isEmpty())
            return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
        if (!transferRecovery && nativeDelegated && submitted.scope().equals(custodyScope)
                && submitted.offerSequence() <= delegationProposal)
            return rejectedPreparation(StateDisposition.NATIVE_DELEGATED);
        if (!nativeTeleportId.equals(packet.nativeTeleportId()))
            return rejectedPreparation(StateDisposition.TELEPORT_MISMATCH);

        PlayerPhysicalSnapshot next = packet.snapshot().orElseThrow();
        if (installationFenceActive()) {
            boolean exactRetry = packet.equals(failedInstallationPacket);
            boolean newerRevision = failedInstallationPacket != null
                    && packet.sequence() > failedInstallationPacket.sequence()
                    && next.revision() > failedInstallationPacket.snapshot().orElseThrow().revision();
            if (!exactRetry && !newerRevision) return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
        }

        InstallationDisposition disposition;
        if (snapshot != null && next.revision() <= snapshot.revision()) {
            if (!snapshot.equals(next)) return rejectedPreparation(StateDisposition.REVISION_CONFLICT);
            disposition = InstallationDisposition.RETRANSMISSION;
        } else {
            if (attempted != null && (packet.sequence() < attempted.sequence()
                    || packet.sequence() == attempted.sequence() && !packet.equals(attempted)))
                return rejectedPreparation(StateDisposition.ATTEMPT_FENCED);
            disposition = InstallationDisposition.INSTALL;
        }

        long publishGeneration = Math.incrementExact(protocolGeneration);
        var bodyReferenceUpdate = prepareBodyReferenceUpdate(packet, next);
        var expectedState = captureExpectedInstallationState();
        var prepared = new PreparedInstallation(this, lifetime, protocolGeneration, publishGeneration, packet,
                next, snapshot, expectedState, disposition, snapshot != null, bodyReferenceUpdate);
        return new InstallationPreparation(disposition, null, prepared);
    }

    /** Pure stale/ownership query for a preparation produced by this exact session. */
    public boolean installationCurrent(PreparedInstallation prepared) {
        return prepared != null && !prepared.revoked && prepared.session == this && prepared.lifetime == lifetime
                && prepared.protocolGeneration == protocolGeneration && prepared.expectedSnapshot == snapshot
                && prepared.expectedState.matches(this, prepared);
    }

    /** Register the accepted owner's attempt before its caller writes native actor state. */
    public boolean beginPreparedInstallation(PreparedInstallation prepared) {
        if (prepared == null || prepared.disposition != InstallationDisposition.INSTALL
                || prepared.begun || !installationCurrent(prepared)) return false;
        prepared.begun = true;
        lastPublishedInstallation = null;
        activeAttempt = prepared;
        attempted = prepared.packet;
        applying = true;
        if (prepared.replacingAcceptedCustody) installationPending = true;
        lastStateDisposition = StateDisposition.APPLICATION_THREW;
        return true;
    }

    /** End a failed owner attempt and retain the failed-replacement fence without publishing state. */
    public boolean failPreparedInstallation(PreparedInstallation prepared, Throwable originalFailure) {
        Objects.requireNonNull(originalFailure, "originalFailure");
        try {
            return finishPreparedFailure(prepared, StateDisposition.APPLICATION_THREW);
        } catch (ArithmeticException cleanupFailure) {
            if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
            return false;
        }
    }

    /** Retire an abandoned or rejected attempt; this does not claim that native state was restored. */
    public boolean revokePreparedInstallation(PreparedInstallation prepared) {
        if (prepared != null && prepared.begun)
            return finishPreparedFailure(prepared, StateDisposition.INSTALL_REJECTED);
        if (!installationCurrent(prepared)
                || prepared.disposition != InstallationDisposition.INSTALL
                && prepared.disposition != InstallationDisposition.RETRANSMISSION) return false;
        // Initial installations and retransmissions have not displaced accepted custody, so their
        // abandoned tokens remain local. A rejected replacement ends the previous STATE's ACK
        // qualification even when actor mutation never began; other preparations must re-check it.
        long nextGeneration = prepared.disposition == InstallationDisposition.INSTALL
                && prepared.replacingAcceptedCustody ? Math.incrementExact(protocolGeneration) : protocolGeneration;
        prepared.revoked = true;
        if (nextGeneration != protocolGeneration) {
            clearStateAcknowledgements();
            lastPublishedInstallation = null;
            protocolGeneration = nextGeneration;
            lastStateDisposition = StateDisposition.INSTALL_REJECTED;
        }
        return true;
    }

    private boolean finishPreparedFailure(PreparedInstallation prepared, StateDisposition disposition) {
        if (prepared == null || prepared.session != this || prepared.lifetime != lifetime
                || !prepared.begun || activeAttempt != prepared || !applying) return false;
        long nextGeneration = Math.incrementExact(protocolGeneration);
        if (prepared.replacingAcceptedCustody) {
            installationPending = false;
            installationFailed = true;
            failedInstallationPacket = prepared.packet;
        }
        clearStateAcknowledgements();
        lastPublishedInstallation = null;
        activeAttempt = null;
        applying = false;
        protocolGeneration = nextGeneration;
        lastStateDisposition = disposition;
        return true;
    }

    /** Publication identity, deliberately independent of the consumed pre-publication generation. */
    boolean publishedInstallationCurrent(PreparedInstallation prepared, PlayerPhysicsProtocol.Packet packet,
                                         PlayerPhysicalSnapshot canonical) {
        return prepared != null && prepared.session == this && prepared.lifetime == lifetime && !prepared.revoked
                && prepared.packet == packet && acceptedInstallationPacket == packet
                && lastPublishedInstallation == prepared && snapshot == canonical
                && canonical != null && canonical.equals(prepared.snapshot)
                && predictionEligible();
    }

    /** Publish only precomputed fields after an owner has accepted the native install. */
    public boolean publishInstallation(PreparedInstallation prepared) {
        if (!installationCurrent(prepared)) return false;
        boolean installation = prepared.disposition == InstallationDisposition.INSTALL;
        if (installation ? !prepared.begun || activeAttempt != prepared || !applying
                : prepared.disposition != InstallationDisposition.RETRANSMISSION || prepared.begun
                || activeAttempt != null || applying) return false;

        incomingSequence = prepared.packet.sequence();
        acceptedInstallationPacket = prepared.packet;
        bodyReferenceReceipt = prepared.nextBodyReferenceReceipt;
        pendingBodyReferenceIntent = prepared.nextPendingBodyReferenceIntent;
        bodyReferenceState = prepared.nextBodyReferenceState;
        clearStateAcknowledgements();
        lastPublishedInstallation = prepared;
        if (prepared.snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule) tangentView = null;
        if (installation) {
            if (prepared.packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.NativeTransferRecovery) {
                activeNativeHandoff = null; lastNativeMotion = null; pendingNativeAcknowledgement = null;
                nativeTransferRecoveryRestore = true;
            }
            snapshot = prepared.snapshot;
            custodyScope = prepared.snapshot.scope();
            nativeDelegated = false;
            nativeCorrectionPending = false;
            mutationQuarantine = false;
            installationPending = false;
            installationFailed = false;
            failedInstallationPacket = null;
            activeAttempt = null;
            applying = false;
            lastStateDisposition = StateDisposition.APPLIED;
        } else {
            lastStateDisposition = StateDisposition.RETRANSMISSION;
        }
        protocolGeneration = prepared.publishGeneration;
        return true;
    }

    private InstallationPreparation rejectedPreparation(StateDisposition rejection) {
        return new InstallationPreparation(InstallationDisposition.REJECTED, rejection, null);
    }

    private BodyReferenceUpdate prepareBodyReferenceUpdate(PlayerPhysicsProtocol.Packet packet,
                                                           PlayerPhysicalSnapshot acceptedSnapshot) {
        var nextReceipt = bodyReferenceReceipt;
        var nextPendingIntent = pendingBodyReferenceIntent;
        boolean nativeRestoreAcknowledgementCandidate = false;
        PlayerPhysicsProtocol.BodyReferenceReceipt receipt = packet.bodyReferenceReceipt().orElse(null);
        if (receipt != null && (bodyReferenceReceipt == null || receipt.requestId() >= bodyReferenceReceipt.requestId())) {
            nextReceipt = receipt;
            boolean restoreStillPending = pendingBodyReferenceIntent != null
                    && pendingBodyReferenceIntent.requestId() == receipt.requestId()
                    && pendingBodyReferenceIntent.action() == PlayerPhysicsProtocol.BodyReferenceAction.UNLOCK
                    && receipt.decision() == PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED
                    && receipt.reason().filter(reason -> reason != PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE
                    && reason != PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED
                    && reason != PlayerPhysicsProtocol.BodyReferenceReason.REQUEST_ID_REUSED).isPresent();
            if (pendingBodyReferenceIntent != null && pendingBodyReferenceIntent.requestId() == receipt.requestId()
                    && !restoreStillPending) nextPendingIntent = null;
        }
        // A rejected manual request is historical evidence, not restoration authority. A real
        // pending reference still identifies restoration when that rejection replaces its receipt.
        nativeRestoreAcknowledgementCandidate = isNativeRestorePurpose(packet)
                && acceptedSnapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape
                && shape.frame() == CardinalFrame.Y_POS;
        PlayerBodyReference.State nextState = acceptedSnapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                ? null : packet.bodyReferenceState().orElse(bodyReferenceState);
        return new BodyReferenceUpdate(nextReceipt, nextPendingIntent, nextState,
                nativeRestoreAcknowledgementCandidate);
    }

    private ExpectedInstallationState captureExpectedInstallationState() {
        return new ExpectedInstallationState(offer, submitted, offered, attempted, failedInstallationPacket,
                snapshot, custodyScope, incomingSequence, outgoingSequence, installationPending, installationFailed,
                nativeDelegated, delegationProposal, nativeTeleportId, nativeCorrectionPending,
                nextBodyReferenceRequestId, pendingBodyReferenceIntent, bodyReferenceReceipt, bodyReferenceState,
                ordinaryModeAcknowledgement, nativeRestoreAcknowledgement);
    }

    /** Pure proposal qualification; actual Client additionally proves its live connection/context and history. */
    boolean allowsHealthyDelegatedResumeOffer(PlayerPhysicsProtocol.Packet packet) {
        return packet != null && packet.kind() == PlayerPhysicsProtocol.Kind.OFFER
                && snapshot != null && acceptedInstallationPacket != null && offered != null && submitted != null
                && nativeDelegated && retired && custodyScope != null && snapshot.scope().equals(custodyScope)
                && offered.scope().equals(custodyScope) && submitted.scope().equals(custodyScope)
                && packet.scope().equals(custodyScope) && submitted.offerSequence() == delegationProposal
                && packet.offerSequence() > offered.offerSequence() && packet.offerSequence() > delegationProposal
                && packet.sequence() > incomingSequence && !nativeCorrectionPending && !applying
                && activeAttempt == null && !installationFailed && !mutationQuarantine
                && !installationFenceActive() && nativeTransferFailure == null;
    }

    private boolean canAcceptOffer(PlayerPhysicsProtocol.Packet packet) {
        if (packet.kind() != PlayerPhysicsProtocol.Kind.OFFER) return false;
        if (offered == null) return true;
        if (!offered.scope().playerId().equals(packet.scope().playerId())
                || offered.scope().dimension() != packet.scope().dimension()) return false;
        if (offered.scope().equals(packet.scope())) return packet.offerSequence() >= offered.offerSequence();
        return packet.scope().worldEpoch() > offered.scope().worldEpoch();
    }

    public boolean receive(PlayerPhysicsProtocol.Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (applying) throw new IllegalStateException("Reentrant physical state installation");
        lastStateDisposition = StateDisposition.NOT_STATE;
        if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE) return receiveNative(packet);
        if (packet.kind() == PlayerPhysicsProtocol.Kind.OFFER) return receiveOffer(packet);

        if (install == null && (packet.kind() == PlayerPhysicsProtocol.Kind.OWNED
                || packet.kind() == PlayerPhysicsProtocol.Kind.STATE)) {
            return observed(StateDisposition.ATTEMPT_FENCED, false);
        }

        InstallationPreparation preparation = prepareInstallation(packet);
        if (preparation.disposition() == InstallationDisposition.REJECTED)
            return observed(preparation.rejection().orElse(StateDisposition.ATTEMPT_FENCED), false);
        PreparedInstallation prepared = preparation.prepared().orElseThrow();
        if (preparation.disposition() == InstallationDisposition.RETRANSMISSION)
            return publishInstallation(prepared) || observed(staleDisposition(prepared), false);
        if (!beginPreparedInstallation(prepared)) return observed(staleDisposition(prepared), false);

        boolean applicationAccepted;
        try {
            applicationAccepted = install.test(prepared.snapshot);
        } catch (RuntimeException | Error failure) {
            failPreparedInstallation(prepared, failure);
            throw failure;
        }
        if (!installationCurrent(prepared)) {
            revokePreparedInstallation(prepared);
            return observed(staleDisposition(prepared), false);
        }
        if (!applicationAccepted) {
            revokePreparedInstallation(prepared);
            return observed(StateDisposition.INSTALL_REJECTED, false);
        }

        boolean retiringLegacy = !retired;
        if (retiringLegacy) {
            try {
                retireLegacy.run();
            } catch (RuntimeException | Error failure) {
                failPreparedInstallation(prepared, failure);
                throw failure;
            }
            if (prepared.lifetime == lifetime) retired = true;
        }
        if (!installationCurrent(prepared)) {
            revokePreparedInstallation(prepared);
            return observed(staleDisposition(prepared), false);
        }
        if (!publishInstallation(prepared)) {
            revokePreparedInstallation(prepared);
            return observed(staleDisposition(prepared), false);
        }
        return true;
    }

    private StateDisposition staleDisposition(PreparedInstallation prepared) {
        return prepared.lifetime == lifetime ? StateDisposition.ATTEMPT_FENCED : StateDisposition.LIFETIME_CHANGED;
    }

    /** A retransmitted accepted recovery root is observation-only, including after OWNED installs. */
    boolean isCurrentEnvironmentRecoveryOffer(PlayerPhysicsProtocol.Packet packet) {
        return packet != null && packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.ServerEnvironmentRecovery
                && offered != null && offered.equals(packet) && offer.isCurrentOffer(offered)
                && environmentRecoveryCorrectionGeneration == correctionGeneration && !nativeCorrectionPending;
    }

    private boolean acceptsEnvironmentRecovery(PlayerPhysicsProtocol.Packet packet,
            PlayerPhysicsProtocol.ServerEnvironmentRecovery recovery) {
        return snapshot != null && offered != null && !nativeDelegated && !nativeCorrectionPending
                && !installationFenceActive() && custodyScope != null && custodyScope.equals(snapshot.scope())
                && snapshot.scope().equals(offered.scope()) && snapshot.scope().equals(recovery.previousScope())
                && offered.offerSequence() == recovery.previousProposal()
                && nativeTeleportId.equals(recovery.previousNativeTeleportId())
                && packet.scope().worldEpoch() > snapshot.scope().worldEpoch();
    }

    private boolean receiveOffer(PlayerPhysicsProtocol.Packet packet) {
        if (isCurrentEnvironmentRecoveryOffer(packet)) return true;
        boolean environmentRecovery = packet.nativeTransfer().orElse(null)
                instanceof PlayerPhysicsProtocol.ServerEnvironmentRecovery recovery
                && acceptsEnvironmentRecovery(packet, recovery);
        if (packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.ServerEnvironmentRecovery
                && !environmentRecovery) return false;
        // A same-scope offer would replace the proposal needed to retry a partially applied
        // absolute state. A later world epoch is still allowed to establish a new lifetime.
        if (installationFenceActive() && snapshot.scope().equals(packet.scope())) return false;
        if (!canAcceptOffer(packet)) return false;
        // Vanilla's confirmed correction can start a later server recovery epoch while the old
        // owner still retains custody. Only that fenced owner may negotiate a replacement;
        // healthy owners and same-epoch proposal changes must keep their accepted state.
        boolean correctionRecovery = nativeCorrectionPending && snapshot != null && offered != null
                && snapshot.scope().equals(offered.scope()) && !snapshot.scope().equals(packet.scope())
                && packet.scope().worldEpoch() > snapshot.scope().worldEpoch();
        if (drivesMotion() && !correctionRecovery && !environmentRecovery) return false;

        clearNativeTransfer();
        lastNativeCorrectionIdentity = 0;
        boolean newScope = offered != null && !offered.scope().equals(packet.scope());
        long nextGeneration = Math.incrementExact(protocolGeneration);
        boolean newProposal = offered == null || !offered.scope().equals(packet.scope())
                || offered.offerSequence() != packet.offerSequence();
        PlayerPhysicsProtocol.BodyReferenceReceipt staleReceipt = null;
        if (newProposal && pendingBodyReferenceIntent != null) {
            var prior = bodyReferenceReceipt;
            staleReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(nextBodyReferenceRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                    prior == null ? 0 : prior.acceptedLockRequestId(),
                    prior == null ? Optional.empty() : prior.acceptedFrame(),
                    prior == null ? Optional.empty() : prior.tangentHeading(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.STALE_REQUEST));
        }
        if (!offer.accept(packet)) return false;
        clearStateAcknowledgements();
        lastPublishedInstallation = null;
        if (newProposal) {
            if (newScope) {
                incomingSequence = -1;
                outgoingSequence = 0;
                recoveryRequest = null;
            }
            submitted = null;
            attempted = null;
            nativeTeleportId = OptionalInt.empty();
            nativeCorrectionPending = false;
            bodyReferenceState = null;
            if (pendingBodyReferenceIntent != null) {
                pendingBodyReferenceIntent = null;
                bodyReferenceReceipt = staleReceipt;
            }
        }
        offered = packet;
        environmentRecoveryCorrectionGeneration = environmentRecovery ? correctionGeneration : -1;
        if (snapshot != null && !snapshot.scope().equals(packet.scope())) {
            snapshot = null;
            custodyScope = null;
            acceptedInstallationPacket = null;
            nativeDelegated = false;
            delegationProposal = 0;
            clearInstallationFence();
            pendingBodyReferenceIntent = null;
            bodyReferenceReceipt = null;
            bodyReferenceState = null;
            nextBodyReferenceRequestId = 0;
        }
        protocolGeneration = nextGeneration;
        return true;
    }

    /** Read-only authority retained from a complete native restore install, never a movement grant. */
    static final class NativeRestoreInputLease {
        private final PlayerPhysicsClientSession session;
        private final Object lifetime;
        private final PreparedInstallation installation;
        private final PlayerPhysicalSnapshot canonical;
        private final PlayerPhysicsProtocol.BodyReferenceAcknowledgement acknowledgement;
        private final boolean pending;
        private NativeRestoreInputLease(PlayerPhysicsClientSession session, PreparedInstallation installation,
                PlayerPhysicsProtocol.BodyReferenceAcknowledgement acknowledgement, boolean pending) {
            this.session = session; this.lifetime = session.lifetime; this.installation = installation;
            this.canonical = session.snapshot; this.acknowledgement = acknowledgement; this.pending = pending;
        }
        boolean pending() { return pending; }
        boolean matchesPublished(Object exactLifetime, PlayerPhysicalSnapshot exactSnapshot,
                long sequence, long proposal) {
            return lifetime == exactLifetime && canonical == exactSnapshot
                    && installation.packet.sequence() == sequence && installation.packet.offerSequence() == proposal;
        }
        boolean current() {
            return session.lifetime == lifetime && session.snapshot == canonical
                    && session.nativeRestoreCanonical == canonical && canonical != null
                    && installation.session == session && installation.lifetime == lifetime && !installation.revoked
                    && installation.acknowledgementAuthorized && installation == session.lastPublishedInstallation
                    && installation.packet == session.acceptedInstallationPacket
                    && installation.snapshot.equals(canonical) && isNativeRestorePurpose(installation.packet)
                    && session.drivesMotion() && !session.nativeCorrectionPending && !session.applying
                    && !session.mutationQuarantine && !session.installationFenceActive()
                    && (pending ? session.pendingNativeRestoreInstallation == installation
                        && session.nativeRestoreAcknowledgement == acknowledgement
                        && acknowledgement != null && acknowledgement.stateSequence() == installation.packet.sequence()
                        && acknowledgement.snapshotRevision() == canonical.revision()
                        : session.sentNativeRestoreInstallation == installation
                            && session.pendingNativeRestoreInstallation == null && session.nativeRestoreAcknowledgement == null);
        }
    }

    NativeRestoreInputLease nativeRestoreInputLease() {
        var prepared = pendingNativeRestoreInstallation != null ? pendingNativeRestoreInstallation : sentNativeRestoreInstallation;
        if (prepared == null) return null;
        var lease = new NativeRestoreInputLease(this, prepared, nativeRestoreAcknowledgement,
                pendingNativeRestoreInstallation != null);
        return lease.current() ? lease : null;
    }

    // Constant-memory stop-and-wait: only the last motion receipt is retransmittable.
    private PreparedInstallation sentNativeRestoreInstallation;
    private PreparedInstallation pendingNativeRestoreInstallation;
    private PlayerPhysicalSnapshot nativeRestoreCanonical;
    private PlayerPhysicsProtocol.NativeTransferFailure nativeTransferFailure;
    private boolean nativeTransferRecoveryRestore;
    private PlayerPhysicsProtocol.NativeHandoff activeNativeHandoff;
    private PlayerPhysicsProtocol.NativeMotion lastNativeMotion;
    private PlayerPhysicsProtocol.Packet pendingNativeAcknowledgement;

    private NativeCorrectionApplication nativeCorrectionApplication;
    private PlayerPhysicsProtocol.Packet acceptedNativeCorrection;
    private long lastNativeCorrectionIdentity;

    /** A single actual native invocation; it grants no motion or installation authority. */
    static final class NativeCorrectionApplication {
        final PlayerPhysicsClientSession session;
        final Object lifetime;
        final long correction;
        final PlayerPhysicalSnapshot snapshot;
        final PlayerPhysicsProtocol.Packet submitted;
        final int teleportId;
        boolean positionApplied, confirmSent, positionSent, applied;

        NativeCorrectionApplication(PlayerPhysicsClientSession session, int teleportId) {
            this.session = session; lifetime = session.lifetime; correction = session.correctionGeneration;
            snapshot = session.snapshot; submitted = session.submitted; this.teleportId = teleportId;
        }
        boolean current() {
            return session.lifetime == lifetime && session.correctionGeneration == correction
                    && session.snapshot == snapshot && session.submitted == submitted
                    && session.nativeCorrectionApplication == this && session.nativeDelegated
                    && session.nativeTeleportId.orElse(-1) == teleportId && !session.installationFailed
                    && !session.mutationQuarantine && !session.applying && !session.installationFenceActive();
        }
    }

    static final class NativeCorrectionCue {
        final Object lifetime;
        final PlayerPhysicalSnapshot snapshot;
        final PlayerPhysicsProtocol.Packet submitted;
        final long correction;
        NativeCorrectionCue(PlayerPhysicsClientSession session) {
            lifetime = session.lifetime; snapshot = session.snapshot; submitted = session.submitted;
            correction = session.correctionGeneration;
        }
    }
    NativeCorrectionCue nativeCorrectionCue() { return nativeDelegated ? new NativeCorrectionCue(this) : null; }

    NativeCorrectionApplication prepareNativeCorrectionApplication(NativeCorrectionCue cue, int teleportId) {
        if (cue == null || cue.lifetime != lifetime || cue.snapshot != snapshot || cue.submitted != submitted
                || cue.correction == Long.MAX_VALUE || correctionGeneration != cue.correction + 1) return null;
        return prepareNativeCorrectionApplication(teleportId);
    }

    NativeCorrectionApplication prepareNativeCorrectionApplication(int teleportId) {
        if (!nativeDelegated || !nativeCorrectionPending || snapshot == null || submitted == null
                || nativeTeleportId.orElse(-1) != teleportId || applying || installationFenceActive()
                || installationFailed || mutationQuarantine) return null;
        var proof = new NativeCorrectionApplication(this, teleportId);
        nativeCorrectionApplication = proof;
        return proof;
    }

    void nativeCorrectionApplicationReturned(NativeCorrectionApplication proof) {
        if (proof != null && proof.current() && nativeCorrectionPending
                && proof.positionApplied && proof.confirmSent && proof.positionSent) proof.applied = true;
    }

    /** Clears only the TP fence after the retained, actual native APPLY returned successfully. */
    public boolean receiveNativeCorrectionComplete(PlayerPhysicsProtocol.Packet packet, BooleanSupplier ownerCurrent) {
        Objects.requireNonNull(packet, "packet"); Objects.requireNonNull(ownerCurrent, "ownerCurrent");
        if (packet.kind() != PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE
                || !(packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.NativeCorrectionCompleted completed)) return false;
        var proof = nativeCorrectionApplication;
        if (proof == null || !proof.current() || !proof.applied || completed.nativeTeleportId() != proof.teleportId
                || !packet.scope().equals(proof.snapshot.scope()) || !packet.scope().equals(custodyScope)
                || packet.offerSequence() != delegationProposal || packet.offerSequence() != proof.submitted.offerSequence()) return false;
        long generation = protocolGeneration;
        var previous = acceptedNativeCorrection;
        BooleanSupplier pure = () -> proof.current() && protocolGeneration == generation && acceptedNativeCorrection == previous;
        if (!pure.getAsBoolean() || !ownerCurrent.getAsBoolean() || !pure.getAsBoolean()) return false;
        if (previous != null) {
            if (!previous.equals(packet)) return false;
        } else {
            if (!nativeCorrectionPending || packet.sequence() <= incomingSequence
                    || completed.correctionIdentity() <= lastNativeCorrectionIdentity) return false;
            long nextGeneration = Math.incrementExact(generation);
            nativeCorrectionPending = false;
            acceptedNativeCorrection = packet;
            lastNativeCorrectionIdentity = completed.correctionIdentity();
            incomingSequence = packet.sequence(); protocolGeneration = nextGeneration;
        }
        queueNativeAcknowledgement(packet, new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(
                completed.correctionIdentity(), completed.nativeTeleportId(), packet.sequence()));
        return true;
    }

    private void clearNativeTransfer() {
        nativeCorrectionApplication = null; acceptedNativeCorrection = null;
        sentNativeRestoreInstallation = null;
        nativeRestoreCanonical = null; pendingNativeRestoreInstallation = null;
        activeNativeHandoff = null; lastNativeMotion = null; pendingNativeAcknowledgement = null;
        nativeTransferFailure = null; nativeTransferRecoveryRestore = false;
    }

    private boolean receiveNative(PlayerPhysicsProtocol.Packet packet) {
        throw new IllegalStateException("Native transfer requires the exact client velocity installation boundary");
    }

    /** The caller retains the exact actor/connection/world; the session retains its own immutable lifetime proof. */
    public boolean receiveNativeTransfer(PlayerPhysicsProtocol.Packet packet,
            BooleanSupplier ownerCurrent, BiConsumer<Vec3d, BooleanSupplier> installVelocity) {
        Objects.requireNonNull(packet, "packet"); Objects.requireNonNull(ownerCurrent, "ownerCurrent");
        Objects.requireNonNull(installVelocity, "installVelocity");
        if (applying || installationFenceActive() || nativeCorrectionPending || snapshot == null || submitted == null
                || !packet.scope().equals(snapshot.scope()) || !packet.scope().equals(submitted.scope())
                || packet.offerSequence() != submitted.offerSequence()) return false;
        Object capturedLifetime = lifetime;
        long generation = protocolGeneration, correction = correctionGeneration;
        PlayerPhysicalSnapshot capturedSnapshot = snapshot;
        PlayerPhysicsProtocol.Packet capturedInstallation = acceptedInstallationPacket;
        var capturedHandoff = activeNativeHandoff;
        var capturedMotion = lastNativeMotion;
        BooleanSupplier pure = () -> lifetime == capturedLifetime && protocolGeneration == generation
                && correctionGeneration == correction && snapshot == capturedSnapshot
                && acceptedInstallationPacket == capturedInstallation && activeNativeHandoff == capturedHandoff
                && lastNativeMotion == capturedMotion && !nativeCorrectionPending;
        BooleanSupplier current = () -> pure.getAsBoolean() && ownerCurrent.getAsBoolean() && pure.getAsBoolean();
        if (!current.getAsBoolean()) return false;
        var payload = packet.nativeTransfer().orElseThrow();
        Vec3d velocity;
        boolean first;
        if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE && payload instanceof PlayerPhysicsProtocol.NativeHandoff handoff) {
            if (capturedHandoff != null) {
                if (!nativeDelegated || !capturedHandoff.equals(handoff)) return false;
                if (!current.getAsBoolean()) return false;
                queueNativeAcknowledgement(packet, new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(handoff.handoffId(), handoff.restoreRevision()));
                return true;
            }
            var restore = sentNativeRestoreInstallation;
            if (restore == null || restore.lifetime != lifetime || restore.session != this
                    || nativeRestoreCanonical != capturedSnapshot || !restore.snapshot.equals(capturedSnapshot) || restore.packet != capturedInstallation
                    || restore.snapshot.revision() != handoff.restoreRevision()
                    || !isNativeRestorePurpose(restore.packet) || nativeDelegated
                    || !nativeTransferRecoveryRestore && nativeMovementMustRemainGated()) return false;
            first = true; velocity = handoff.baseVelocity();
        } else if (packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_MOTION && payload instanceof PlayerPhysicsProtocol.NativeMotion motion) {
            if (!nativeDelegated || capturedHandoff == null || motion.handoffId() != capturedHandoff.handoffId()
                    || motion.restoreRevision() != capturedHandoff.restoreRevision()) return false;
            long last = capturedMotion == null ? 0 : capturedMotion.motionSequence();
            if (motion.motionSequence() == last && capturedMotion != null) {
                if (!motion.equals(capturedMotion) || !current.getAsBoolean()) return false;
                queueNativeAcknowledgement(packet, new PlayerPhysicsProtocol.NativeMotionAcknowledgement(motion.handoffId(), motion.restoreRevision(), last));
                return true;
            }
            if (last == Long.MAX_VALUE || motion.motionSequence() != last + 1) return false;
            first = false; velocity = motion.absoluteVelocity();
        } else return false;
        if (packet.sequence() <= incomingSequence || !current.getAsBoolean()) return false;
        applying = true;
        try {
            if (first) {
                if (!retired) { retired = true; retireLegacy.run(); if (!current.getAsBoolean()) return false; }
                enterNative.run(); if (!current.getAsBoolean()) return false;
            }
            // The concrete actor setter must check this same proof before/after every component write.
            installVelocity.accept(velocity, current);
            if (!current.getAsBoolean()) return false;
            if (first) {
                activeNativeHandoff = (PlayerPhysicsProtocol.NativeHandoff) payload;
                nativeDelegated = true; retired = true; custodyScope = packet.scope(); delegationProposal = packet.offerSequence();
                clearStateAcknowledgements(); lastPublishedInstallation = null;
            } else lastNativeMotion = (PlayerPhysicsProtocol.NativeMotion) payload;
            nativeTransferFailure = null; nativeTransferRecoveryRestore = false; recoveryRequest = null;
            incomingSequence = packet.sequence(); protocolGeneration = Math.incrementExact(generation);
            var acknowledgement = first
                    ? (PlayerPhysicsProtocol.NativeTransfer) new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(activeNativeHandoff.handoffId(), activeNativeHandoff.restoreRevision())
                    : new PlayerPhysicsProtocol.NativeMotionAcknowledgement(lastNativeMotion.handoffId(), lastNativeMotion.restoreRevision(), lastNativeMotion.motionSequence());
            queueNativeAcknowledgement(packet, acknowledgement);
            return true;
        } catch (RuntimeException | Error failure) {
            // An uncertain external installation cannot return custody to the old UG writer.
            if (pure.getAsBoolean()) {
                installationFailed = true;
                failedInstallationPacket = capturedInstallation;
                attempted = capturedInstallation;
                lastStateDisposition = StateDisposition.APPLICATION_THREW;
                nativeTransferFailure = payload instanceof PlayerPhysicsProtocol.NativeHandoff base
                        ? new PlayerPhysicsProtocol.NativeTransferFailure(base.handoffId(), base.restoreRevision(), packet.sequence(), 0)
                        : new PlayerPhysicsProtocol.NativeTransferFailure(((PlayerPhysicsProtocol.NativeMotion) payload).handoffId(),
                            ((PlayerPhysicsProtocol.NativeMotion) payload).restoreRevision(), packet.sequence(),
                            ((PlayerPhysicsProtocol.NativeMotion) payload).motionSequence());
                nativeTransferRecoveryRestore = false;
                recoveryRequest = null;
                pendingNativeAcknowledgement = null;
            }
            throw failure;
        } finally {
            // A reentrant reset/new owner owns its applying state.
            if (lifetime == capturedLifetime) applying = false;
        }
    }

    private void queueNativeAcknowledgement(PlayerPhysicsProtocol.Packet received, PlayerPhysicsProtocol.NativeTransfer payload) {
        long sequence = Math.incrementExact(outgoingSequence);
        var kind = payload instanceof PlayerPhysicsProtocol.NativeHandoffAcknowledgement
                ? PlayerPhysicsProtocol.Kind.NATIVE_ACK
                : payload instanceof PlayerPhysicsProtocol.NativeCorrectionAcknowledgement
                        ? PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK : PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK;
        pendingNativeAcknowledgement = PlayerPhysicsProtocol.nativeTransferPacket(kind, received.scope(), sequence, received.offerSequence(), payload);
        outgoingSequence = sequence;
    }
    public Optional<PlayerPhysicsProtocol.Packet> nativeTransferAcknowledgement() { return Optional.ofNullable(pendingNativeAcknowledgement); }
    public void nativeTransferAcknowledgementSent(PlayerPhysicsProtocol.Packet exact) {
        if (pendingNativeAcknowledgement == exact) pendingNativeAcknowledgement = null;
    }

    /** Protocol custody survives native delegation so retired UG writers cannot restart. */
    public boolean owns() { return snapshot != null || nativeDelegated; }
    public boolean drivesMotion() { return snapshot != null && !nativeDelegated && !installationFenceActive(); }
    /** Read-only observation for callers that must preserve a broken accepted installation's fence. */
    public boolean installationFenceActive() {
        return snapshot != null && (installationPending || installationFailed);
    }

    /** The failed attempt supplies identity only; recovery never supplies physical state or credit. */
    private PlayerPhysicsProtocol.Packet recoveryRequest;

    public Optional<PlayerPhysicsProtocol.Packet> recoveryRequest() {
        if (nativeTransferFailure != null && !nativeTransferRecoveryRestore && submitted != null && !applying) {
            if (recoveryRequest == null) {
                long next = Math.incrementExact(outgoingSequence);
                recoveryRequest = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.RECOVER,
                        submitted.scope(), next, submitted.offerSequence(), nativeTransferFailure);
                outgoingSequence = next;
            }
            return Optional.of(recoveryRequest);
        }
        if (!installationFenceActive() || failedInstallationPacket == null) return Optional.empty();
        return recoveryRequestForFailure(failedInstallationPacket);
    }

    boolean nativeTransferFailureCurrent(PlayerPhysicsProtocol.Packet exactRequest) {
        return nativeTransferFailure != null && !nativeTransferRecoveryRestore
                && exactRequest == recoveryRequest && installationFailed && !nativeCorrectionPending
                && submitted != null && submitted.scope().equals(exactRequest.scope())
                && submitted.offerSequence() == exactRequest.offerSequence();
    }

    /** Capture identity before facade cleanup resets Session; the caller proves its actual actor fence. */
    Optional<PlayerPhysicsProtocol.Packet> recoveryRequestForFailure(PlayerPhysicsProtocol.Packet exactFailed) {
        if (exactFailed == null || applying || activeAttempt != null || attempted != exactFailed
                || (lastStateDisposition != StateDisposition.APPLICATION_THREW
                    && lastStateDisposition != StateDisposition.NATIVE_ENVIRONMENT_THREW
                    && lastStateDisposition != StateDisposition.PREDICTION_FAILED) || submitted == null
                || offered == null || !submitted.scope().equals(exactFailed.scope())
                || submitted.offerSequence() != exactFailed.offerSequence()
                || !offered.scope().equals(exactFailed.scope())
                || offered.offerSequence() != exactFailed.offerSequence()) return Optional.empty();
        if (recoveryRequest != null && recoveryRequest.scope().equals(exactFailed.scope())
                && recoveryRequest.offerSequence() == exactFailed.offerSequence())
            return Optional.of(recoveryRequest);
        long next = Math.incrementExact(outgoingSequence);
        var failed = exactFailed;
        var request = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.RECOVER,
                failed.scope(), next, failed.offerSequence(), Optional.empty(), Optional.empty(), Optional.empty());
        outgoingSequence = next;
        recoveryRequest = request;
        return Optional.of(request);
    }
    /** Actor-scoped form of the installation fence; foreign actors retain their ordinary path. */
    public boolean installationFenceActive(EntityPlayer actor) {
        Objects.requireNonNull(actor, "actor");
        return installationFenceActive() && actor.getUniqueID().equals(snapshot.scope().playerId())
                && actor.dimension == snapshot.scope().dimension();
    }
    /** Do not publish a local free pose from geometry changed by a failed accepted installation. */
    public void runFreePosePublication(EntityPlayer actor, Runnable publisher) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(publisher, "publisher");
        if (installationFenceActive(actor)) return;
        publisher.run();
    }
    /** Snapshot eligible for read-only model presentation, separate from retained custody. */
    public Optional<PlayerPhysicalSnapshot> presentationSnapshot() {
        return snapshot != null && !nativeDelegated && !installationFenceActive() && !applying && !nativeCorrectionPending
                ? Optional.of(snapshot) : Optional.empty();
    }
    public float physicalEyeHeight(EntityPlayer actor, float nativeHeight) {
        return drivesMotion() && actor.getUniqueID().equals(snapshot.scope().playerId())
                && actor.dimension == snapshot.scope().dimension()
                && (!PlayerKinematics.requiresNative(actor) || nativeMovementMustRemainGated())
                ? (float) snapshot.placement().shape().dimensions().eyeHeight() : nativeHeight;
    }

    private boolean holdsNativeMovement(EntityPlayer actor) {
        if (installationFenceActive(actor)) return true;
        return drivesMotion() && actor.getUniqueID().equals(snapshot.scope().playerId())
                && actor.dimension == snapshot.scope().dimension()
                && (!PlayerKinematics.requiresNative(actor) || nativeMovementMustRemainGated());
    }
    /** Keep custody while vanilla applies its correction; only states for that exact native ID may follow. */
    public void nativeCorrection(int teleportId) {
        if (teleportId < 0) throw new IllegalArgumentException("Negative native teleport ID");
        clearNativeTransfer();
        long nextGeneration = Math.incrementExact(protocolGeneration);
        PlayerPhysicsProtocol.BodyReferenceReceipt staleReceipt = null;
        if (pendingBodyReferenceIntent != null
                && (bodyReferenceReceipt == null || bodyReferenceReceipt.acceptedFrame().isEmpty())) {
            staleReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(nextBodyReferenceRequestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.STALE_REQUEST));
        }
        correctionPredictionIdentity = null;
        correctionGeneration = Math.incrementExact(correctionGeneration);
        nativeTeleportId = OptionalInt.of(teleportId);
        nativeCorrectionPending = true;
        mobilityRequiresRelease |= mobilityHeld;
        mobilityHeld = false;
        if (pendingBodyReferenceIntent != null) {
            pendingBodyReferenceIntent = null;
            if (staleReceipt != null) bodyReferenceReceipt = staleReceipt;
        }
        clearStateAcknowledgements();
        lastPublishedInstallation = null;
        protocolGeneration = nextGeneration;
    }
    public Optional<PlayerPhysicalSnapshot> snapshot() { return Optional.ofNullable(snapshot); }
    public BodyReferenceView bodyReferenceStatus() {
        if (pendingBodyReferenceIntent != null)
            return BodyReferenceView.pending(pendingBodyReferenceIntent, bodyReferenceReceipt);
        return bodyReferenceReceipt == null ? BodyReferenceView.none() : BodyReferenceView.receipt(bodyReferenceReceipt);
    }
    /** Last effective state installed with a valid OWNED/STATE packet, independent of request receipt IDs. */
    public Optional<PlayerBodyReference.State> bodyReferenceState() { return Optional.ofNullable(bodyReferenceState); }
    /** Effective reference fenced to this installed cardinal geometry and current correction lifetime. */
    public Optional<PlayerBodyReference.Effective> acceptedEffectiveBodyReference(PlayerPhysicalSnapshot acceptedGeometry) {
        Objects.requireNonNull(acceptedGeometry, "acceptedGeometry");
        var accepted = presentationSnapshot();
        if (accepted.isEmpty() || accepted.orElseThrow() != acceptedGeometry || bodyReferenceState == null
                || !(accepted.orElseThrow().placement().shape() instanceof wi.unigrav.core.player.PlayerBodyShape.Cardinal shape)
                || shape.frame() != bodyReferenceState.effective().frame()) return Optional.empty();
        return Optional.of(bodyReferenceState.effective());
    }
    /** Whether native movement must remain stopped until a matching safe-geometry STATE is accepted. */
    public boolean nativeMovementMustRemainGated() {
        if (nativeTransferFailure != null) return true;
        if (installationFenceActive()) return true;
        if (snapshot != null && !nativeDelegated
                && snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule) return true;
        BodyReferenceView state = bodyReferenceStatus();
        return bodyReferenceState != null && bodyReferenceState.effective().frame() != CardinalFrame.Y_POS
                || bodyReferenceState == null && bodyReferenceReceipt != null
                && bodyReferenceReceipt.acceptedFrame().filter(frame -> frame != CardinalFrame.Y_POS).isPresent()
                || state.reason().filter(reason -> reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING
                || reason == PlayerPhysicsProtocol.BodyReferenceReason.RESTORE_DEADLINE
                || reason == PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED).isPresent();
    }
    public long requestBodyReferenceLock(CardinalFrame frame, Vec3d tangentHeading) {
        requireRequestable();
        long requestId = Math.incrementExact(nextBodyReferenceRequestId);
        long nextGeneration = Math.incrementExact(protocolGeneration);
        var intent = PlayerPhysicsProtocol.BodyReferenceIntent.lock(requestId, frame, tangentHeading);
        nextBodyReferenceRequestId = requestId;
        pendingBodyReferenceIntent = intent;
        protocolGeneration = nextGeneration;
        return requestId;
    }
    public long requestBodyReferenceUnlock() {
        requireRequestable();
        long requestId = Math.incrementExact(nextBodyReferenceRequestId);
        long nextGeneration = Math.incrementExact(protocolGeneration);
        var intent = PlayerPhysicsProtocol.BodyReferenceIntent.unlock(requestId);
        nextBodyReferenceRequestId = requestId;
        pendingBodyReferenceIntent = intent;
        protocolGeneration = nextGeneration;
        return requestId;
    }
    private void requireRequestable() {
        if (!drivesMotion() || snapshot == null)
            throw new IllegalStateException("Body reference request requires an active physical owner");
    }
    private void clearInstallationFence() {
        installationPending = false;
        installationFailed = false;
        failedInstallationPacket = null;
    }
    public void reset() {
        clearNativeTransfer();
        lastNativeCorrectionIdentity = 0;
        long nextGeneration = Math.incrementExact(protocolGeneration);
        Object nextLifetime = new Object();
        PlayerPhysicsClientOffer nextOffer = new PlayerPhysicsClientOffer();
        predictedPoseRestoration = null;
        mutationQuarantine = false;
        lifetime = nextLifetime;
        protocolGeneration = nextGeneration;
        offer = nextOffer;
        submitted = null;
        offered = null;
        attempted = null;
        activeAttempt = null;
        applying = false;
        clearInstallationFence();
        snapshot = null;
        acceptedInstallationPacket = null;
        custodyScope = null;
        delegationProposal = 0;
        nativeDelegated = false;
        retired = false;
        incomingSequence = -1;
        outgoingSequence = 0;
        nativeTeleportId = OptionalInt.empty();
        nativeCorrectionPending = false;
        nextBodyReferenceRequestId = 0;
        pendingBodyReferenceIntent = null;
        bodyReferenceReceipt = null;
        bodyReferenceState = null;
        mobilityRequiresRelease |= mobilityHeld;
        mobilityHeld = false; pendingMobilityRequest = null;
        clearStateAcknowledgements();
        lastPublishedInstallation = null;
    }
    public CPacketPlayer prepare(CPacketPlayer packet, Vec3d transport, float yaw, float pitch,
                                boolean remoteCustody, Optional<BoostProtocol.StateApplication> application,
                                Consumer<PlayerPhysicsProtocol.Packet> outbound) {
        if (drivesMotion() || installationFenceActive()) return packet;
        // Delegation completes this proposal. Native correction replies must not restart its
        // APPLY negotiation or replace the submitted identity retained by the actual RETURN proof.
        // A genuinely accepted higher proposal still follows the ordinary resume negotiation.
        if (nativeDelegated && offered != null && offered.scope().equals(custodyScope)
                && offered.offerSequence() <= delegationProposal) return packet;
        Object expectedLifetime = lifetime;
        long expectedGeneration = protocolGeneration;
        return offer.prepare(packet, transport, yaw, pitch, remoteCustody, application, apply -> {
            long nextOutgoingSequence = Math.incrementExact(outgoingSequence);
            long nextGeneration = Math.incrementExact(protocolGeneration);
            var sequenced = new PlayerPhysicsProtocol.Packet(apply.kind(), apply.scope(), nextOutgoingSequence,
                    apply.offerSequence(), apply.application(), apply.snapshot(), apply.controls());
            outbound.accept(sequenced);
            if (lifetime == expectedLifetime && protocolGeneration == expectedGeneration) {
                submitted = sequenced;
                outgoingSequence = nextOutgoingSequence;
                protocolGeneration = nextGeneration;
            }
        });
    }

    public Optional<PlayerPhysicsProtocol.Packet> input(PlayerPhysicsProtocol.Controls controls) {
        if (!drivesMotion() || mutationQuarantine) return Optional.empty();
        long nextOutgoingSequence = Math.incrementExact(outgoingSequence);
        long nextGeneration = Math.incrementExact(protocolGeneration);
        var intent = pendingBodyReferenceIntent == null
                ? PlayerPhysicsProtocol.BodyReferenceIntent.none() : pendingBodyReferenceIntent;
        var outboundControls = new PlayerPhysicsProtocol.Controls(controls.translation(), controls.forward(),
                controls.jump(), controls.sneak(), controls.sprint(), controls.boost(), controls.boostGear(),
                controls.keyboardTravelGain(), intent, controls.desiredBodyAttitude(), controls.mobility());
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, snapshot.scope(),
                nextOutgoingSequence, submitted.offerSequence(), Optional.empty(), Optional.empty(), Optional.of(outboundControls),
                OptionalInt.empty(), Optional.empty(), Optional.ofNullable(inputAcknowledgement()));
        outgoingSequence = nextOutgoingSequence;
        protocolGeneration = nextGeneration;
        return Optional.of(packet);
    }

    private PlayerPhysicsProtocol.BodyReferenceAcknowledgement inputAcknowledgement() {
        return nativeRestoreAcknowledgement != null ? nativeRestoreAcknowledgement : ordinaryModeAcknowledgement;
    }

    /** Called only after the exact INPUT packet returns normally from the real network submission boundary. */
    void inputSendSucceeded(PlayerPhysicsProtocol.Packet input) {
        if (input == null || input.kind() != PlayerPhysicsProtocol.Kind.INPUT) return;
        PlayerPhysicsProtocol.BodyReferenceAcknowledgement sent = input.bodyReferenceAcknowledgement().orElse(null);
        if (sent == null) return;
        if (nativeRestoreAcknowledgement == sent) {
            if (pendingNativeRestoreInstallation != null && nativeRestoreCanonical == snapshot && pendingNativeRestoreInstallation.snapshot.equals(snapshot)
                    && pendingNativeRestoreInstallation.packet == acceptedInstallationPacket
                    && pendingNativeRestoreInstallation.lifetime == lifetime) sentNativeRestoreInstallation = pendingNativeRestoreInstallation;
            pendingNativeRestoreInstallation = null;
            nativeRestoreAcknowledgement = null;
        }
        if (ordinaryModeAcknowledgement == sent) ordinaryModeAcknowledgement = null;
    }

    /** Package-private: only the real Client complete-install path may call this after all commits and decode finish. */
    boolean authorizePublishedStateAcknowledgement(PreparedInstallation prepared, AcknowledgementDomain domain) {
        Objects.requireNonNull(domain, "domain");
        if (prepared == null || prepared.session != this || prepared.lifetime != lifetime
                || lastPublishedInstallation != prepared || prepared.acknowledgementAuthorized
                || acceptedInstallationPacket != prepared.packet || incomingSequence != prepared.packet.sequence()
                || snapshot == null || !snapshot.equals(prepared.snapshot) || applying || installationFenceActive())
            return false;
        boolean nativePurpose = isNativeRestorePurpose(prepared.packet);
        if (domain == AcknowledgementDomain.ORDINARY_MODE && nativePurpose) return false;
        if (domain == AcknowledgementDomain.NATIVE_RESTORE
                && (!nativePurpose || !prepared.nativeRestoreAcknowledgementCandidate)) return false;
        var acknowledgement = new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(
                prepared.packet.sequence(), prepared.snapshot.revision());
        if (domain == AcknowledgementDomain.NATIVE_RESTORE) {
            ordinaryModeAcknowledgement = null;
            nativeRestoreAcknowledgement = acknowledgement;
            pendingNativeRestoreInstallation = prepared;
            nativeRestoreCanonical = snapshot;
        } else {
            nativeRestoreAcknowledgement = null;
            ordinaryModeAcknowledgement = acknowledgement;
        }
        prepared.acknowledgementAuthorized = true;
        return true;
    }

    static AcknowledgementDomain acknowledgementDomain(PlayerPhysicsProtocol.Packet packet) {
        return isNativeRestorePurpose(packet) ? AcknowledgementDomain.NATIVE_RESTORE
                : AcknowledgementDomain.ORDINARY_MODE;
    }

    private static boolean isNativeRestorePurpose(PlayerPhysicsProtocol.Packet packet) {
        return PlayerPhysicsProtocol.nativeRestorePurpose(packet);
    }

    void revokeStateAcknowledgements() {
        clearStateAcknowledgements();
        lastPublishedInstallation = null;
    }

    private void clearStateAcknowledgements() {
        ordinaryModeAcknowledgement = null;
        nativeRestoreAcknowledgement = null;
        pendingNativeRestoreInstallation = null;
        sentNativeRestoreInstallation = null;
        nativeRestoreCanonical = null;
    }

    /** Actual vanilla-key consumer; accepted-frame mapping is applied here once installed. */
    public PlayerPhysicsProtocol.Controls keyboardControls(float yaw, double forward, double strafe, Vec3 look,
                                                           boolean jump, boolean sneak, boolean sprint, boolean boost,
                                                           Optional<PlayerBodyReference.Effective> acceptedReference) {
        Objects.requireNonNull(acceptedReference, "acceptedReference");
        var installed = presentationSnapshot().flatMap(this::acceptedEffectiveBodyReference);
        if (acceptedReference.isPresent() && acceptedReference.equals(installed)
                && (acceptedReference.orElseThrow().frame() != CardinalFrame.Y_POS
                    || acceptedReference.orElseThrow().source() == PlayerBodyReference.Source.MANUAL
                    || acceptedReference.orElseThrow().source() == PlayerBodyReference.Source.ENVIRONMENT)) {
            return PlayerPhysicsInput.keyboard(acceptedReference.orElseThrow(), forward, strafe, look,
                    jump, sneak, sprint, boost);
        }
        return PlayerPhysicsInput.keyboard(yaw, forward, strafe, look, jump, sneak, sprint, boost);
    }

}
