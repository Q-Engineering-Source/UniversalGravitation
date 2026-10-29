package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;

/** One owner invocation, including failed queries and already debited movement. */
final class FreeOwnerTransaction implements AutoCloseable {
    private static final ThreadLocal<NativeInstallation> NATIVE_INSTALLATION = new ThreadLocal<>();
    record NativeInstallation(FreeOwnerTransaction transaction, PlayerPhysicalSnapshot journal) { }
    private record InstalledEyeObservation(NativeInstallation installation, Object invocation,
            NativePoseEyeProducer.Profile producer, int rawEyeBits) { }
    record EyeMetadataCompletion(PlayerPhysicalSnapshot source, PlayerPhysicalSnapshot journal,
            PlayerBodyPlacement placement, NativePoseEyeProducer.Profile producer, int rawEyeBits) { }
    interface TerminalFence {
        FreeOwnerTransaction transaction();
        void requireCurrent();
    }
    record ResponseReadback(TerminalFence fence, Vec3d rawMotion,
            Optional<PlayerPhysicalSnapshot.Contacts> contacts) {
        ResponseReadback(TerminalFence fence, Vec3d rawMotion) {
            this(fence, rawMotion, Optional.empty());
        }
        ResponseReadback {
            Objects.requireNonNull(fence, "fence");
            Objects.requireNonNull(rawMotion, "rawMotion");
            Objects.requireNonNull(contacts, "contacts");
        }
    }
    @FunctionalInterface
    interface TerminalResponseAction {
        ResponseReadback complete(PlayerPhysicalSnapshot provisional);
    }
    static final class TerminalMotionReceipt {
        private final FreeOwnerTransaction transaction;
        private final EntityPlayerMP actor;
        private final PlayerPhysicalSnapshot snapshot;
        private final TerminalFence responseFence;
        private final PreparedInstallation installation;
        private final PlayerPhysicalSnapshot installedJournal;
        private final long rawX, rawY, rawZ;
        private final long worldX, worldY, worldZ;

        private TerminalMotionReceipt(FreeOwnerTransaction transaction, EntityPlayerMP actor,
                PlayerPhysicalSnapshot snapshot, ResponseReadback response) {
            this.transaction = transaction;
            this.actor = actor;
            this.snapshot = snapshot;
            responseFence = response.fence();
            installation = transaction.preparedInstallation;
            installedJournal = transaction.installedJournal;
            if (responseFence.transaction() != transaction)
                throw new IllegalStateException("terminal receipt lost its exact response transaction");
            if (installation != null && (installedJournal == null
                    || installedJournal.placement() != snapshot.placement()
                    || !(responseFence instanceof ResponseFence installedFence)
                    || !installedFence.belongsTo(transaction)))
                throw new IllegalStateException("installed terminal receipt lost its exact installation journal");
            if (installation == null && (installedJournal != null || !(responseFence instanceof CardinalTerminalFence)))
                throw new IllegalStateException("Cardinal terminal receipt lost its non-installation response identity");
            Vec3d rawMotion = response.rawMotion();
            rawX = Double.doubleToRawLongBits(rawMotion.x());
            rawY = Double.doubleToRawLongBits(rawMotion.y());
            rawZ = Double.doubleToRawLongBits(rawMotion.z());
            worldX = Double.doubleToRawLongBits(snapshot.worldVelocity().x());
            worldY = Double.doubleToRawLongBits(snapshot.worldVelocity().y());
            worldZ = Double.doubleToRawLongBits(snapshot.worldVelocity().z());
        }

        boolean matchesStepSnapshot(FreeOwnerTransaction exactTransaction,
                PlayerPhysicalSnapshot exactSnapshot) {
            return transaction == exactTransaction && snapshot == exactSnapshot
                    && exactTransaction.preparedInstallation == installation
                    && exactTransaction.installedJournal == installedJournal
                    && responseFence.transaction() == exactTransaction
                    && (installation == null || installedJournal.placement() == snapshot.placement())
                    && rawMatches(actor) && velocityMatches(exactSnapshot);
        }

        /** Pure final admission check; never calls entity, world, session, or provider methods. */
        boolean matchesPublishedMotion(EntityPlayerMP exactActor, PlayerPhysicalSnapshot candidate,
                EyeMetadataCompletion eyeCompletion) {
            return actor == exactActor && candidate.scope() == snapshot.scope()
                    && candidate.revision() == snapshot.revision()
                    && candidate.contacts().equals(snapshot.contacts())
                    && publishedPlacementMatches(candidate.placement(), eyeCompletion)
                    && velocityMatches(candidate) && rawMatches(exactActor);
        }

        private boolean publishedPlacementMatches(PlayerBodyPlacement candidate,
                EyeMetadataCompletion eyeCompletion) {
            if (candidate == snapshot.placement()) return true;
            if (eyeCompletion == null || eyeCompletion.source() != transaction.accepted
                    || eyeCompletion.journal() != installedJournal
                    || eyeCompletion.journal().placement() != snapshot.placement()
                    || eyeCompletion.placement() != candidate)
                return false;
            PlayerBodyPlacement original = snapshot.placement();
            if (original.center() != candidate.center() || original.attitude() != candidate.attitude()
                    || !original.bounds().equals(candidate.bounds())
                    || !original.transport().equals(candidate.transport()))
                return false;
            if (!(original.shape() instanceof PlayerBodyShape.FreeCapsule originalFree)
                    || !(candidate.shape() instanceof PlayerBodyShape.FreeCapsule candidateFree))
                return false;
            return StoredRealizationLimits.sameRaw(originalFree.dimensions().width(), candidateFree.dimensions().width())
                    && StoredRealizationLimits.sameRaw(originalFree.dimensions().height(), candidateFree.dimensions().height())
                    && originalFree.collisionProfile().equals(candidateFree.collisionProfile())
                    && original.capsule() == candidate.capsule()
                    && original.storedCapsuleGeometry().storedHalfLine()
                        == candidate.storedCapsuleGeometry().storedHalfLine();
        }

        private boolean velocityMatches(PlayerPhysicalSnapshot candidate) {
            return Double.doubleToRawLongBits(candidate.worldVelocity().x()) == worldX
                    && Double.doubleToRawLongBits(candidate.worldVelocity().y()) == worldY
                    && Double.doubleToRawLongBits(candidate.worldVelocity().z()) == worldZ;
        }

        private boolean rawMatches(EntityPlayerMP exactActor) {
            return Double.doubleToRawLongBits(exactActor.motionX) == rawX
                    && Double.doubleToRawLongBits(exactActor.motionY) == rawY
                    && Double.doubleToRawLongBits(exactActor.motionZ) == rawZ;
        }
    }
    /** One-shot proof that the closed Step result may be considered by its owning Server admission. */
    static final class ClosedStepAdmissionSeal {
        private final FreeOwnerTransaction transaction;
        private final TerminalMotionReceipt receipt;
        private final PlayerPhysicalSnapshot stepSnapshot;
        private boolean consumed;

        private ClosedStepAdmissionSeal(FreeOwnerTransaction transaction, TerminalMotionReceipt receipt,
                PlayerPhysicalSnapshot stepSnapshot) {
            this.transaction = transaction;
            this.receipt = receipt;
            this.stepSnapshot = stepSnapshot;
        }

        /** Consumes once; this validates the completed Step only and never authorizes another motion response. */
        boolean consume(EntityPlayerMP exactActor, PlayerPhysicalSnapshot candidate,
                EyeMetadataCompletion eyeCompletion) {
            if (consumed) throw new IllegalStateException("closed Step admission seal was already consumed");
            consumed = true;
            return transaction.closed && transaction.terminalMotionReceipt == receipt
                    && receipt.transaction == transaction && receipt.snapshot == stepSnapshot
                    && receipt.matchesStepSnapshot(transaction, stepSnapshot)
                    && receipt.matchesPublishedMotion(exactActor, candidate, eyeCompletion);
        }
    }
    private InstalledEyeObservation installedEyeObservation;
    private boolean eyeMetadataCompletionExported;
    private TerminalResponseAction terminalResponseAction;
    private boolean terminalResponseClaimed;
    private TerminalMotionReceipt terminalMotionReceipt;
    private boolean closedStepAdmissionSealExported;

    /** Lexical native callback write capability; never modifies the installed journal. */
    void recordInstalledEyeObservation(NativeInstallation exactInstallation,
            NativePoseEyeProducer.Profile producer, float rawEye) {
        requireOwnerFence();
        if (exactInstallation == null || NATIVE_INSTALLATION.get() != exactInstallation
                || exactInstallation.transaction() != this || exactInstallation.journal() != installedJournal
                || installed == null || installedJournal.placement() != installed
                || installationStage != InstallationStage.INSTALLED || originalFailure != null)
            throw new IllegalStateException("Native eye observation lost its exact installed callback");
        installedEyeObservation = new InstalledEyeObservation(exactInstallation, new Object(),
                Objects.requireNonNull(producer), Float.floatToIntBits(rawEye));
    }

    /** Read authority survives lexical native return, but only within this still-open exact transaction. */
    boolean acceptsInstalledEye(PlayerBodyPlacement exactPlacement, float effectiveEye) {
        var observation = installedEyeObservation;
        if (observation == null) return false;
        requireOwnerFence();
        if (closed || originalFailure != null || installed != exactPlacement
                || installedJournal == null || installedJournal.placement() != exactPlacement
                || observation.installation().transaction() != this
                || observation.installation().journal() != installedJournal
                || !(actor instanceof NativePoseEyeProducer producer)) return false;
        var currentProfile = producer.unigrav$nativePoseEyeProfile();
        requireOwnerFence();
        var physical = WorldCollisionCandidates.ActorGeometry.read(actor);
        requireOwnerFence();
        var dimensions = exactPlacement.shape().dimensions();
        return installedEyeObservation == observation && currentProfile.isPresent()
                && observation.producer().equals(currentProfile.orElseThrow())
                && Float.floatToIntBits(actor.eyeHeight) == observation.rawEyeBits()
                && physical.position().equals(exactPlacement.transport())
                && physical.bounds().equals(exactPlacement.bounds())
                && Float.floatToIntBits(actor.width) == Float.floatToIntBits((float) dimensions.width())
                && Float.floatToIntBits(actor.height) == Float.floatToIntBits((float) dimensions.height())
                && Float.floatToIntBits(effectiveEye)
                    == Float.floatToIntBits(observation.producer().effectiveEye());
    }

    boolean matchesEffectiveEye(PlayerBodyPlacement placement, float effectiveEye) {
        if (installedEyeObservation != null && placement == installed)
            return acceptsInstalledEye(placement, effectiveEye);
        return Float.floatToIntBits(effectiveEye)
                == Float.floatToIntBits((float) placement.shape().dimensions().eyeHeight());
    }

    Optional<EyeMetadataCompletion> exportEyeMetadataCompletion() {
        if (eyeMetadataCompletionExported)
            throw new IllegalStateException("Installed eye completion was already exported");
        if (installedEyeObservation == null || originalFailure != null) return Optional.empty();
        requireCurrent();
        var observation = installedEyeObservation;
        float effectiveEye = actor.getEyeHeight();
        if (!acceptsInstalledEye(installed, effectiveEye))
            throw new IllegalStateException("Installed eye completion lost its producer");
        var replacement = installed.withEyeMetadata(effectiveEye, numeric);
        requireCurrent();
        if (installedEyeObservation != observation)
            throw new IllegalStateException("Installed eye completion changed during export");
        eyeMetadataCompletionExported = true;
        return Optional.of(new EyeMetadataCompletion(accepted, installedJournal, replacement,
                observation.producer(), observation.rawEyeBits()));
    }

    record TrustedResizeAuthority(Object proposalIdentity, Object ownerIdentity,
            PlayerPhysicalSnapshot source, PlayerPhysicalSnapshot anchor, WorldServer proposalWorld,
            NetHandlerPlayServer proposalConnection, Object connectionHandle, Object scopeHandle,
            long sessionProposal, PlayerPhysicalSnapshot.Scope scope, long correctionRevision,
            long capturedWorldTick, ModeTransitionPrepared.TrustedResizeObservation observation,
            BooleanSupplier current) {
        private record Authorization(boolean allowed, String failedAt, String predicates) { }

        private static final List<String> PREDICATE_NAMES = List.of("owner", "source",
                "anchorPlacement", "anchorScope", "anchorRevision", "world", "connection",
                "actorConnection", "scope", "correction", "tickNotBefore", "tickWindow",
                "transport", "width", "height", "dimensions", "current");

        TrustedResizeAuthority {
            Objects.requireNonNull(proposalIdentity);
            Objects.requireNonNull(ownerIdentity);
            Objects.requireNonNull(source);
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(proposalWorld);
            Objects.requireNonNull(proposalConnection);
            Objects.requireNonNull(connectionHandle);
            Objects.requireNonNull(scopeHandle);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(observation);
            Objects.requireNonNull(current);
        }

        boolean authorizes(FreeOwnerTransaction transaction) {
            Authorization authorization = evaluate(transaction);
            transaction.latestTrustedResizeAuthorityPredicates = authorization.predicates();
            transaction.latestTrustedResizeAuthorityFailureStage = authorization.failedAt();
            return authorization.allowed();
        }

        private Authorization evaluate(FreeOwnerTransaction transaction) {
            var dimensions = observation.dimensions();
            var observed = observation.observedGeometry();
            Boolean[] checks = new Boolean[PREDICATE_NAMES.size()];
            checks[0] = ownerIdentity == transaction.resourceOwner;
            if (!checks[0]) return rejected("owner", checks);
            checks[1] = source == transaction.accepted;
            if (!checks[1]) return rejected("source", checks);
            checks[2] = anchor.placement() == source.placement();
            if (!checks[2]) return rejected("anchorPlacement", checks);
            checks[3] = anchor.scope().equals(source.scope());
            if (!checks[3]) return rejected("anchorScope", checks);
            checks[4] = anchor.revision() == source.revision();
            if (!checks[4]) return rejected("anchorRevision", checks);
            checks[5] = proposalWorld == transaction.world;
            if (!checks[5]) return rejected("world", checks);
            checks[6] = proposalConnection == transaction.connection;
            if (!checks[6]) return rejected("connection", checks);
            checks[7] = transaction.actor.connection == proposalConnection;
            if (!checks[7]) return rejected("actorConnection", checks);
            checks[8] = scope.equals(source.scope());
            if (!checks[8]) return rejected("scope", checks);
            checks[9] = correctionRevision == transaction.correction;
            if (!checks[9]) return rejected("correction", checks);
            checks[10] = transaction.tick >= capturedWorldTick;
            if (!checks[10]) return rejected("tickNotBefore", checks);
            checks[11] = transaction.tick - capturedWorldTick <= 1;
            if (!checks[11]) return rejected("tickWindow", checks);
            checks[12] = observed.position().equals(source.placement().transport());
            if (!checks[12]) return rejected("transport", checks);
            checks[13] = Float.floatToIntBits(observed.width())
                    == Float.floatToIntBits((float) dimensions.width());
            if (!checks[13]) return rejected("width", checks);
            checks[14] = Float.floatToIntBits(observed.height())
                    == Float.floatToIntBits((float) dimensions.height());
            if (!checks[14]) return rejected("height", checks);
            checks[15] = observation.supportsPhysicalCapsule() && observation.bodyProfileCurrent();
            if (!checks[15]) return rejected("dimensions", checks);
            checks[16] = current.getAsBoolean();
            return new Authorization(checks[16], checks[16] ? "none" : "current",
                    formatPredicates(checks));
        }

        private static Authorization rejected(String failedAt, Boolean[] checks) {
            return new Authorization(false, failedAt, formatPredicates(checks));
        }

        private static String formatPredicates(Boolean[] checks) {
            var formatted = new StringBuilder("authority{");
            for (int index = 0; index < PREDICATE_NAMES.size(); index++) {
                if (index > 0) formatted.append(',');
                formatted.append(PREDICATE_NAMES.get(index)).append('=');
                Boolean check = checks[index];
                formatted.append(check == null ? "NOT_EVALUATED" : check);
            }
            return formatted.append('}').toString();
        }
    }
    enum InstallationStage { NONE, PREPARED, MUTATING, INSTALLED, PARTIAL }
    record InstallationCustody(Object ownerIdentity, EntityPlayerMP actor, PlayerPhysicalSnapshot.Scope scope,
                               long correctionRevision, long worldTick, NetHandlerPlayServer connection,
                               PlayerBodyPlacement expectedPlacement, PlayerPhysicalSnapshot expectedJournal,
                               InstallationStage stage,
                               Optional<WorldCollisionCandidates.ActorGeometry> observedGeometry,
                               Optional<RuntimeException> failure) {
        InstallationCustody {
            Objects.requireNonNull(ownerIdentity);
            Objects.requireNonNull(actor);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(connection);
            Objects.requireNonNull(expectedPlacement);
            Objects.requireNonNull(expectedJournal);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(observedGeometry);
            Objects.requireNonNull(failure);
        }
    }
    static final class PreparedInstallation {
        private final FreeOwnerTransaction transaction;
        private CapsulePlacementMath.Pose certifiedPose;
        private boolean normalInstallation;
        private boolean movementStatAttempted;
        private final PlayerBodyPlacement placement;
        private final PlayerPhysicalSnapshot journal;
        private InstalledFreeEndpoint installedEndpoint;
        private PreparedInstallation(FreeOwnerTransaction transaction, PlayerBodyPlacement placement,
                                     PlayerPhysicalSnapshot journal) {
            this.transaction = transaction; this.placement = placement; this.journal = journal;
        }
        FreeOwnerTransaction transaction() { return transaction; }
        CapsulePlacementMath.Pose certifiedPose() { return certifiedPose; }
        InstalledFreeEndpoint installedEndpoint() {
            if (installedEndpoint == null) {
                installedEndpoint = InstalledFreeEndpoint.from(this);
            } else {
                // The native tail and terminal response share one installed-world generation.
                // Reusing this exact endpoint preserves its identity while rechecking live custody.
                installedEndpoint.requireCurrent();
            }
            return installedEndpoint;
        }
        PlayerBodyPlacement placement() { return placement; }
        PlayerPhysicalSnapshot journal() { return journal; }
        void consumeMovementStatAttempt() {
            transaction.requireInstalledEndpoint(this, journal, certifiedPose);
            if (movementStatAttempted) throw new IllegalStateException("installed journal movement statistics already attempted");
            movementStatAttempted = true;
        }
    }
    static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private final EntityPlayerMP actor;
    private final PlayerPhysicalSnapshot accepted;
    private final WorldServer world;
    private final long tick;
    private final PlayerPhysicsStep.TravelState travel;
    private final long correction;
    private final NetHandlerPlayServer connection;
    private final BooleanSupplier ownerCurrent;
    private final BoostMovementBudget ledger;
    private final CapsuleContactQueryBudget queries;
    private final StoredDyadic.Work numeric;
    private final FreePostprocessingWork postprocessing;
    private final FreeTickResources resources;
    private final Object resourceOwner;
    private final TrustedResizeAuthority trustedResizeAuthority;
    private String latestTrustedResizeAuthorityPredicates = "NOT_EVALUATED";
    private String latestTrustedResizeAuthorityFailureStage = "NOT_EVALUATED";
    private String latestTrustedResizeAuthorizationDiagnostic = "NOT_EVALUATED";
    private final PlayerPhysicsServer.NativeRestoreAuthority nativeRestoreAuthority;
    private final PlayerPhysicsServer.PendingCardinalPose pendingCardinalPose;
    private final PlayerPhysicsServer.NativeCardinalMotionReceipt nativeCardinalMotion;
    private BoostMovementBudget.CenterPathAdmission admission;
    private boolean settled;
    private boolean closed;
    private PlayerBodyPlacement installed;
    private PlayerPhysicalSnapshot installedJournal;
    private PlayerBodyReference.CardinalReference installedOwnerReference;
    private PlayerBodyReference.State installedOwnerState;
    private RuntimeException originalFailure;
    private PreparedInstallation preparedInstallation;
    private InstallationStage installationStage = InstallationStage.NONE;
    private Optional<WorldCollisionCandidates.ActorGeometry> mutationGeometry = Optional.empty();

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent) {
        this(actor, accepted, travel, ledger, ownerCurrent, travel,
                new FreeTickResources.Store().acquire(travel, actor.getServerWorld(), accepted.scope(),
                        travel.correctionRevision()));
    }

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent,
            Object resourceOwner, FreeTickResources resources) {
        this(actor, accepted, travel, ledger, ownerCurrent, resourceOwner, resources, null);
    }

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent,
            Object resourceOwner, FreeTickResources resources, TrustedResizeAuthority trustedResizeAuthority) {
        this(actor, accepted, travel, ledger, ownerCurrent, resourceOwner, resources, trustedResizeAuthority, null);
    }

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent,
            Object resourceOwner, FreeTickResources resources, TrustedResizeAuthority trustedResizeAuthority,
            PlayerPhysicsServer.NativeRestoreAuthority nativeRestoreAuthority) {
        this(actor, accepted, travel, ledger, ownerCurrent, resourceOwner, resources, trustedResizeAuthority,
                nativeRestoreAuthority, null);
    }

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent,
            Object resourceOwner, FreeTickResources resources, TrustedResizeAuthority trustedResizeAuthority,
            PlayerPhysicsServer.NativeRestoreAuthority nativeRestoreAuthority,
            PlayerPhysicsServer.PendingCardinalPose pendingCardinalPose) {
        this(actor, accepted, travel, ledger, ownerCurrent, resourceOwner, resources, trustedResizeAuthority,
                nativeRestoreAuthority, pendingCardinalPose, null);
    }

    FreeOwnerTransaction(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger, BooleanSupplier ownerCurrent,
            Object resourceOwner, FreeTickResources resources, TrustedResizeAuthority trustedResizeAuthority,
            PlayerPhysicsServer.NativeRestoreAuthority nativeRestoreAuthority,
            PlayerPhysicsServer.PendingCardinalPose pendingCardinalPose,
            PlayerPhysicsServer.NativeCardinalMotionReceipt nativeCardinalMotion) {
        this.actor = Objects.requireNonNull(actor);
        this.accepted = Objects.requireNonNull(accepted);
        this.travel = Objects.requireNonNull(travel);
        this.ledger = Objects.requireNonNull(ledger);
        this.ownerCurrent = Objects.requireNonNull(ownerCurrent);
        this.resourceOwner = Objects.requireNonNull(resourceOwner);
        this.resources = Objects.requireNonNull(resources);
        this.trustedResizeAuthority = trustedResizeAuthority;
        this.nativeRestoreAuthority = nativeRestoreAuthority;
        this.pendingCardinalPose = pendingCardinalPose;
        this.nativeCardinalMotion = nativeCardinalMotion;
        queries = resources.queries();
        numeric = resources.numeric();
        postprocessing = resources.postprocessing();
        world = actor.getServerWorld();
        tick = world.getTotalWorldTime();
        correction = travel.correctionRevision();
        connection = actor.connection;
        if (nativeRestoreAuthority != null) nativeRestoreAuthority.claim(this);
        requireCurrent();
    }

    boolean matchesNativeRestoreOwner(PlayerPhysicsServer.NativeRestoreAuthority authority,
            EntityPlayerMP expectedActor, PlayerPhysicalSnapshot expectedAccepted,
            PlayerPhysicsStep.TravelState expectedTravel, BoostMovementBudget expectedLedger,
            Object expectedResourceOwner, FreeTickResources expectedResources, WorldServer expectedWorld) {
        return nativeRestoreAuthority == authority && actor == expectedActor && accepted == expectedAccepted
                && travel == expectedTravel && ledger == expectedLedger && resourceOwner == expectedResourceOwner
                && resources == expectedResources && world == expectedWorld;
    }

    void requireCurrent() {
        if (!world.isCallingFromMinecraftThread()) throw new OwnerContextChangedException("Free transaction off-thread");
        requireOwnerFence();
        boolean actorAlive = actor.isEntityAlive();
        var actorId = actor.getUniqueID();
        requireOwnerFence();
        if (!actorAlive || !actorId.equals(accepted.scope().playerId()))
            throw new OwnerContextChangedException("Free movement actor lifetime or identity changed");
        boolean matchesGeometry = geometryMatches(installed == null ? accepted.placement() : installed);
        // Native eye/profile getters may synchronously re-enter correction, world, connection, or owner code.
        // Authority is only current if the exact transaction fences still hold after that observation.
        requireOwnerFence();
        if (!matchesGeometry)
            throw new OwnerContextChangedException("Free movement actor geometry changed outside its installation");
    }

    void requireOwnerFence() {
        resources.requireCurrent(resourceOwner, world, accepted.scope(), correction);
        if (closed || !ownerCurrent.getAsBoolean() || actor.world != world || actor.isDead
                || connection == null || actor.connection != connection || connection.player != actor
                || world.captureBlockSnapshots || world.restoringBlockSnapshots
                || actor.dimension != accepted.scope().dimension()
                || world.getTotalWorldTime() != tick || travel.correctionRevision() != correction)
            throw new OwnerContextChangedException("Free movement owner transaction changed");
    }

    /**
     * Seal all callback-capable owner checks before a native response reads motion. The returned fence
     * is deliberately limited to direct identity/state fields and the already acquired resource frame;
     * response projection, raw write, and readback cannot call session, entity, world, or collision hooks.
     */
    ResponseFence sealInstalledResponseFence() {
        return sealInstalledResponseFenceInternal(null);
    }

    private ResponseFence sealInstalledResponseFenceInternal(InstalledFreeEndpoint exactEndpoint) {
        requireCurrent();
        if (preparedInstallation == null || !preparedInstallation.normalInstallation || originalFailure != null
                || installationStage != InstallationStage.INSTALLED || !settled
                || installed == null || installedJournal == null || preparedInstallation.placement != installed
                || preparedInstallation.journal != installedJournal)
            throw new OwnerContextChangedException("installed response has no committed installation authority");
        if (exactEndpoint != null && (exactEndpoint.transaction() != this
                || exactEndpoint.installationIdentity() != preparedInstallation
                || exactEndpoint.owner() != installedJournal
                || exactEndpoint.pose() != preparedInstallation.certifiedPose))
            throw new OwnerContextChangedException("installed response endpoint differs from its committed installation");
        requireCurrent();
        return new ResponseFence(this, exactEndpoint);
    }

    ResponseFence sealInstalledResponseFence(InstalledFreeEndpoint exactEndpoint) {
        Objects.requireNonNull(exactEndpoint, "exactEndpoint");
        if (exactEndpoint.transaction() != this) throw new IllegalArgumentException(
                "installed response endpoint belongs to another transaction");
        exactEndpoint.requireCurrent();
        return sealInstalledResponseFenceInternal(exactEndpoint);
    }

    void stageTerminalResponse(TerminalResponseAction action) {
        Objects.requireNonNull(action, "action");
        requireCurrent();
        if (preparedInstallation == null || !preparedInstallation.normalInstallation || originalFailure != null
                || installationStage != InstallationStage.INSTALLED || !settled
                || terminalResponseAction != null || terminalResponseClaimed || terminalMotionReceipt != null)
            throw new IllegalStateException("installed terminal response cannot be staged for this transaction");
        terminalResponseAction = action;
    }

    /**
     * Completes the single final response after the Step's callbacks and post-actions. The action returns
     * the raw vector read back from its actual final response write; this method only seals it and builds
     * the immutable snapshot from that same value.
     */
    PlayerPhysicalSnapshot completeTerminalResponse(PlayerPhysicalSnapshot provisional) {
        Objects.requireNonNull(provisional, "provisional");
        if (terminalResponseClaimed || terminalMotionReceipt != null)
            throw new IllegalStateException("terminal motion response was already consumed");
        terminalResponseClaimed = true;
        if (!provisional.scope().equals(accepted.scope())
                || provisional.revision() != Math.incrementExact(accepted.revision()))
            throw new IllegalStateException("terminal response snapshot is not this transaction's exact successor");

        ResponseReadback response;
        if (terminalResponseAction != null) {
            TerminalResponseAction action = terminalResponseAction;
            terminalResponseAction = null;
            response = Objects.requireNonNull(action.complete(provisional),
                    "terminal response action returned no raw readback");
        } else {
            if (preparedInstallation != null || installationStage != InstallationStage.NONE
                    || !(provisional.placement().shape() instanceof PlayerBodyShape.Cardinal))
                throw new IllegalStateException("installed movement completed without its shape-specific terminal response");
            response = applyCardinalTerminalIdentityResponse(provisional);
        }

        TerminalFence fence = response.fence();
        if (fence.transaction() != this)
            throw new IllegalStateException("terminal response readback belongs to another transaction");
        fence.requireCurrent();
        Vec3d readback = response.rawMotion();
        if (!sameRawMotion(actor, readback))
            throw new IllegalStateException("terminal response raw motion changed after its final write/readback");
        Vec3d worldVelocity = readback.multiply(1 / GravityEntityBinder.TICK_SECONDS);
        PlayerPhysicalSnapshot completed = new PlayerPhysicalSnapshot(provisional.scope(), provisional.revision(),
                provisional.placement(), worldVelocity, response.contacts().isPresent()
                        ? response.contacts() : provisional.contacts());
        if (installed != null && (provisional.placement() != installed || installedJournal == null
                || completed.scope() != installedJournal.scope()
                || completed.revision() != installedJournal.revision()))
            throw new IllegalStateException("terminal response snapshot lost its committed installed journal");
        TerminalMotionReceipt receipt = new TerminalMotionReceipt(this, actor, completed, response);
        travel.rebindTerminalSnapshot(provisional, completed);
        terminalMotionReceipt = receipt;
        return completed;
    }

    Optional<TerminalMotionReceipt> terminalMotionReceipt() {
        return Optional.ofNullable(terminalMotionReceipt);
    }

    ClosedStepAdmissionSeal exportClosedStepAdmissionSeal(PlayerPhysicalSnapshot exactStepSnapshot) {
        Objects.requireNonNull(exactStepSnapshot, "exactStepSnapshot");
        if (!closed || closedStepAdmissionSealExported || terminalMotionReceipt == null
                || terminalMotionReceipt.snapshot != exactStepSnapshot
                || !terminalMotionReceipt.matchesStepSnapshot(this, exactStepSnapshot))
            throw new IllegalStateException("closed Step has no exact unconsumed terminal admission seal");
        closedStepAdmissionSealExported = true;
        return new ClosedStepAdmissionSeal(this, terminalMotionReceipt, exactStepSnapshot);
    }

    private ResponseReadback applyCardinalTerminalIdentityResponse(PlayerPhysicalSnapshot provisional) {
        requireOwnerFence();
        if (!(provisional.placement().shape() instanceof PlayerBodyShape.Cardinal)
                || !provisional.scope().equals(accepted.scope())
                || provisional.revision() != Math.incrementExact(accepted.revision()))
            throw new IllegalStateException("Cardinal terminal response lost its exact physical successor");
        boolean geometryMatches = geometryMatches(provisional.placement());
        boolean nativeRequired = PlayerKinematics.requiresNative(actor);
        requireOwnerFence();
        if (!geometryMatches || nativeRequired)
            throw new OwnerContextChangedException("Cardinal terminal response lost its installed native geometry authority");
        CardinalTerminalFence fence = new CardinalTerminalFence(this, provisional,
                travel.ownerReference(), resources.responseFrameIdentity());
        Vec3d source = motion(actor);
        fence.requireCurrent();
        actor.motionX = source.x(); actor.motionY = source.y(); actor.motionZ = source.z();
        fence.requireCurrent();
        Vec3d readback = motion(actor);
        if (!sameRaw(source, readback))
            throw new IllegalStateException("Cardinal terminal response write did not preserve its raw readback");
        return new ResponseReadback(fence, readback);
    }

    private static boolean sameRaw(Vec3d first, Vec3d second) {
        return Double.doubleToRawLongBits(first.x()) == Double.doubleToRawLongBits(second.x())
                && Double.doubleToRawLongBits(first.y()) == Double.doubleToRawLongBits(second.y())
                && Double.doubleToRawLongBits(first.z()) == Double.doubleToRawLongBits(second.z());
    }

    private static boolean sameRawMotion(EntityPlayerMP actor, Vec3d expected) {
        return Double.doubleToRawLongBits(actor.motionX) == Double.doubleToRawLongBits(expected.x())
                && Double.doubleToRawLongBits(actor.motionY) == Double.doubleToRawLongBits(expected.y())
                && Double.doubleToRawLongBits(actor.motionZ) == Double.doubleToRawLongBits(expected.z());
    }

    private static Vec3d motion(EntityPlayerMP actor) {
        return new Vec3d(actor.motionX, actor.motionY, actor.motionZ);
    }

    /** Pure post-source fence for one response chain. It must not be extended with callbacks. */
    static final class ResponseFence implements TerminalFence {
        private final FreeOwnerTransaction transaction;
        private final Thread ownerThread;
        private final EntityPlayerMP actor;
        private final WorldServer world;
        private final NetHandlerPlayServer connection;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsStep.TravelState travel;
        private final Object resourceOwner;
        private final FreeTickResources resources;
        private final Object resourceFrame;
        private final long tick;
        private final long correction;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final PreparedInstallation installation;
        private final PlayerBodyPlacement installed;
        private final PlayerPhysicalSnapshot journal;
        private final InstalledFreeEndpoint endpoint;
        private final CapsulePlacementMath.Pose pose;

        private ResponseFence(FreeOwnerTransaction transaction, InstalledFreeEndpoint endpoint) {
            this.transaction = transaction;
            ownerThread = Thread.currentThread();
            actor = transaction.actor;
            world = transaction.world;
            connection = transaction.connection;
            accepted = transaction.accepted;
            travel = transaction.travel;
            resourceOwner = transaction.resourceOwner;
            resources = transaction.resources;
            resourceFrame = resources.responseFrameIdentity();
            tick = transaction.tick;
            correction = transaction.correction;
            scope = accepted.scope();
            installation = transaction.preparedInstallation;
            installed = transaction.installed;
            journal = transaction.installedJournal;
            this.endpoint = endpoint;
            pose = endpoint == null ? null : endpoint.pose();
        }

        boolean belongsTo(FreeOwnerTransaction exactTransaction) { return transaction == exactTransaction; }
        @Override public FreeOwnerTransaction transaction() { return transaction; }
        boolean belongsTo(InstalledFreeEndpoint exactEndpoint) {
            return endpoint == exactEndpoint && endpoint != null && transaction == exactEndpoint.transaction();
        }

        public void requireCurrent() {
            FreeOwnerTransaction tx = transaction;
            if (Thread.currentThread() != ownerThread || tx.closed || tx.actor != actor || tx.world != world
                    || tx.connection != connection || tx.accepted != accepted || tx.travel != travel
                    || tx.resourceOwner != resourceOwner || tx.resources != resources || tx.tick != tick
                    || tx.correction != correction || accepted.scope() != scope
                    || tx.preparedInstallation != installation || installation == null
                    || !installation.normalInstallation || tx.installationStage != InstallationStage.INSTALLED
                    || tx.installed != installed || tx.installedJournal != journal || installed == null
                    || journal == null || journal.placement() != installed || installation.placement != installed
                    || installation.journal != journal || tx.originalFailure != null || !tx.settled
                    || (endpoint != null && (endpoint.transaction() != tx || endpoint.installationIdentity() != installation
                        || endpoint.owner() != journal || endpoint.pose() != pose))
                    || (pose != null && (installation.certifiedPose != pose || pose.placement() != installed))
                    || actor.world != world || actor.isDead || actor.dimension != scope.dimension()
                    || actor.connection != connection || connection == null || connection.player != actor
                    || world.captureBlockSnapshots || world.restoringBlockSnapshots
                    || travel.correctionRevision() != correction
                    || !resources.matchesResponseIdentity(resourceOwner, world, scope, correction, tick, resourceFrame))
                throw new OwnerContextChangedException("installed response owner identity changed after its motion source was read");
        }
    }

    /** Pure after-source fence for an ordinary Cardinal response without an installed shape transition. */
    private static final class CardinalTerminalFence implements TerminalFence {
        private final FreeOwnerTransaction transaction;
        private final Thread ownerThread;
        private final EntityPlayerMP actor;
        private final WorldServer world;
        private final NetHandlerPlayServer connection;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsStep.TravelState travel;
        private final Object resourceOwner;
        private final FreeTickResources resources;
        private final Object resourceFrame;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerBodyReference.OwnerReference ownerReference;
        private final long tick;
        private final long correction;

        private CardinalTerminalFence(FreeOwnerTransaction transaction, PlayerPhysicalSnapshot snapshot,
                PlayerBodyReference.OwnerReference ownerReference, Object resourceFrame) {
            this.transaction = transaction;
            ownerThread = Thread.currentThread();
            actor = transaction.actor;
            world = transaction.world;
            connection = transaction.connection;
            accepted = transaction.accepted;
            travel = transaction.travel;
            resourceOwner = transaction.resourceOwner;
            resources = transaction.resources;
            this.resourceFrame = resourceFrame;
            scope = accepted.scope();
            this.snapshot = snapshot;
            this.ownerReference = ownerReference;
            tick = transaction.tick;
            correction = transaction.correction;
        }

        @Override public FreeOwnerTransaction transaction() { return transaction; }

        @Override public void requireCurrent() {
            FreeOwnerTransaction tx = transaction;
            if (Thread.currentThread() != ownerThread || tx.closed || tx.actor != actor || tx.world != world
                    || tx.connection != connection || tx.accepted != accepted || tx.travel != travel
                    || tx.resourceOwner != resourceOwner || tx.resources != resources || tx.tick != tick
                    || tx.correction != correction || accepted.scope() != scope || tx.installed != null
                    || tx.preparedInstallation != null || tx.installationStage != InstallationStage.NONE
                    || tx.travel.ownerReference() != ownerReference || actor.world != world || actor.isDead
                    || actor.dimension != scope.dimension() || actor.connection != connection || connection == null
                    || connection.player != actor || world.captureBlockSnapshots || world.restoringBlockSnapshots
                    || travel.correctionRevision() != correction
                    || !resources.matchesResponseIdentity(resourceOwner, world, scope, correction, tick, resourceFrame)
                    || snapshot.scope() != scope || snapshot.revision() != Math.incrementExact(accepted.revision()))
                throw new OwnerContextChangedException("Cardinal terminal response identity changed after its motion source was read");
        }
    }

    BoostMovementBudget.CenterPathAdmission reserve(long sequence, long budgetTick, double pathUpper) {
        requireCurrent();
        if (admission != null) throw new IllegalStateException("Free center path already reserved");
        admission = ledger.reserveCenterPath(sequence, budgetTick, vector(accepted.placement().transport()),
                vector(accepted.placement().center()), pathUpper);
        return admission;
    }

    void commit(PlayerBodyPlacement installed, double pathUpper) {
        requireCurrent();
        if (settled || admission == null || !admission.accepted()
                || !ledger.commitCenterPath(admission, vector(installed.center()),
                        vector(installed.transport()), pathUpper))
            throw new IllegalStateException("Free center path ledger rejected installed geometry");
        settled = true;
    }

    void requireReservedPath(double pathUpper) {
        requireCurrent();
        if (!fitsReservedPath(pathUpper))
            throw new IllegalStateException("Free path exceeds its exact reserved center-path grant");
    }

    /** Candidate-route admission check; rejected optional proposals must not throw as if the transaction were terminal. */
    boolean fitsReservedPath(double pathUpper) {
        requireCurrent();
        if (!Double.isFinite(pathUpper) || pathUpper < 0)
            throw new IllegalArgumentException("invalid candidate center-path upper bound");
        return admission != null && admission.accepted() && pathUpper <= admission.grant();
    }

    double remainingPathGrant(double usedUpper) {
        requireReservedPath(usedUpper);
        if (usedUpper == admission.grant()) return 0;
        return Math.max(0, Math.nextDown(admission.grant() - usedUpper));
    }

    long nextLedgerSequence() { return Math.incrementExact(ledger.lastSequence()); }

    /** This invocation borrows lifetime provenance, never another Frame's collision proof. */
    boolean nativeCardinalMotionCurrent() {
        requireCurrent();
        boolean valid = nativeCardinalMotion != null && nativeCardinalMotion.current(actor, accepted);
        requireCurrent();
        return valid;
    }

    boolean matchesCardinalOwner(EntityPlayerMP exactActor, PlayerPhysicalSnapshot exactAccepted,
            BoostMovementBudget exactLedger) {
        return actor == exactActor && accepted == exactAccepted && ledger == exactLedger;
    }
    PlayerPhysicalSnapshot accepted() { return accepted; }
    InstalledFreeEndpoint installedFreeEndpoint() {
        if (preparedInstallation == null) throw new IllegalStateException("Free installation has not been prepared");
        return preparedInstallation.installedEndpoint();
    }

    boolean ownsPreparedInstallation(PreparedInstallation exact) {
        return preparedInstallation == exact && exact != null && exact.transaction() == this;
    }

    boolean isPreInstallationPhase() {
        return installationStage == InstallationStage.NONE || installationStage == InstallationStage.PREPARED;
    }

    Optional<PlayerBodyPlacement> installedPlacement() { return Optional.ofNullable(installed); }
    Optional<PlayerPhysicalSnapshot> installedJournal() { return Optional.ofNullable(installedJournal); }
    Optional<RuntimeException> originalFailure() { return Optional.ofNullable(originalFailure); }
    InstallationStage installationStage() { return installationStage; }

    /**
     * Publishes the already prepared typed successor only after this transaction installed its exact
     * Cardinal target. The identity is retained so later native callbacks cannot revert the owner
     * reference to the pre-install Free state and still pass the transaction's owner fence.
     */
    void installCommittedOwnerReference(PlayerBodyReference.CardinalReference exactReference) {
        Objects.requireNonNull(exactReference, "exactReference");
        PlayerBodyReference.State exactSuccessor = exactReference.state();
        if (installationStage != InstallationStage.INSTALLED || installed == null
                || !(installed.shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != exactSuccessor.effective().frame()
                || !installed.attitude().equals(cardinal.frame().attitude()))
            throw new IllegalStateException("typed owner successor does not match the committed Cardinal installation");
        if (installedOwnerReference != null && installedOwnerReference != exactReference)
            throw new IllegalStateException("Free installation already published a different typed owner successor");
        requireCurrent();
        travel.ownerReference(exactReference);
        installedOwnerReference = exactReference;
        installedOwnerState = exactSuccessor;
    }

    boolean hasCommittedOwnerReference() { return installedOwnerReference != null; }

    boolean matchesCommittedOwnerReference(PlayerBodyReference.CardinalReference exactReference) {
        return exactReference != null && installedOwnerReference == exactReference
                && installedOwnerState == exactReference.state() && travel.ownerReference() == exactReference
                && installed != null
                && installationStage == InstallationStage.INSTALLED
                && installed.shape() instanceof PlayerBodyShape.Cardinal cardinal
                && cardinal.frame() == exactReference.state().effective().frame()
                && installed.attitude().equals(cardinal.frame().attitude());
    }

    boolean matchesCommittedOwnerReference() {
        return installedOwnerReference != null && matchesCommittedOwnerReference(installedOwnerReference);
    }

    PlayerBodyReference.CardinalReference committedOwnerReference() { return installedOwnerReference; }

    Optional<InstallationCustody> installationCustody() {
        return preparedInstallation == null ? Optional.empty()
                : Optional.of(new InstallationCustody(resourceOwner, actor, accepted.scope(), correction, tick,
                        connection, preparedInstallation.placement, preparedInstallation.journal,
                        installationStage, mutationGeometry, Optional.ofNullable(originalFailure)));
    }
    CapsuleContactQueryBudget queries() { return queries; }
    StoredDyadic.Work numeric() { return numeric; }
    FreePostprocessingWork postprocessing() { return postprocessing; }
    WorldServer world() { return world; }
    EntityPlayerMP actor() { return actor; }
    long tick() { return tick; }

    FreeTickResources resources() { return resources; }

    static NativeInstallation nativeInstallation(EntityPlayerMP actor) {
        var current = NATIVE_INSTALLATION.get();
        return current != null && current.transaction.actor == actor ? current : null;
    }

    /** Only the installed journal may bridge native callbacks before the owner publishes its successor. */
    void runNativeEnvironment(PlayerPhysicalSnapshot journal, Runnable operation) {
        requireCurrent();
        if (installed == null || journal.placement() != installed || !journal.scope().equals(accepted.scope())
                || journal.revision() != Math.incrementExact(accepted.revision()))
            throw new IllegalStateException("Native environment requires the actual installed journal");
        var previous = NATIVE_INSTALLATION.get();
        if (previous != null && (previous.transaction != this || previous.journal != journal))
            throw new IllegalStateException("Native installation nested across transactions or journals");
        if (previous == null) NATIVE_INSTALLATION.set(new NativeInstallation(this, journal));
        boolean completed = false;
        try {
            if (UnigravMod.proxy == null) operation.run();
            else UnigravMod.proxy.runNativeEnvironment(actor, operation);
            requireCurrent();
            completed = true;
        } finally {
            if (!completed) installedEyeObservation = null;
            if (previous == null) NATIVE_INSTALLATION.remove();
            else NATIVE_INSTALLATION.set(previous);
        }
    }

    PreparedInstallation prepareInstallation(PlayerBodyPlacement placement) {
        requireCurrent();
        Objects.requireNonNull(placement, "placement");
        if (preparedInstallation != null || installationStage != InstallationStage.NONE)
            throw new IllegalStateException("Free geometry installation was already prepared");
        var journal = new PlayerPhysicalSnapshot(accepted.scope(), Math.incrementExact(accepted.revision()),
                placement, new Vec3d(actor.motionX, actor.motionY, actor.motionZ)
                        .multiply(1 / GravityEntityBinder.TICK_SECONDS),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                        actor.collidedVertically, actor.fallDistance, false)));
        preparedInstallation = new PreparedInstallation(this, placement, journal);
        installationStage = InstallationStage.PREPARED;
        return preparedInstallation;
    }

    PreparedInstallation prepareInstallation(PlayerBodyPlacement placement, CertifiedReferenceEndpoint endpoint) {
        Objects.requireNonNull(endpoint).requireCurrent();
        if (endpoint.transaction() != this || endpoint.owner() != accepted
                || endpoint.pose().placement() != placement
                || !(placement.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("final Free endpoint does not belong to this installation");
        var prepared = prepareInstallation(placement);
        endpoint.requireCurrent();
        prepared.certifiedPose = endpoint.pose();
        return prepared;
    }

    void requireInstalledEndpoint(PreparedInstallation prepared, PlayerPhysicalSnapshot journal,
            CapsulePlacementMath.Pose pose) {
        requirePrepared(prepared);
        requireCurrent();
        if (!prepared.normalInstallation || originalFailure != null
                || installationStage != InstallationStage.INSTALLED
                || installedJournal != journal || prepared.journal != journal
                || prepared.certifiedPose == null || prepared.certifiedPose != pose
                || installed != prepared.placement || journal.placement() != installed
                || pose.placement() != installed
                || !(installed.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !journal.scope().equals(accepted.scope())
                || journal.revision() != Math.incrementExact(accepted.revision()))
            throw new IllegalStateException("normal installed Free endpoint is unavailable");
        requireCurrent();
        if (!prepared.normalInstallation || originalFailure != null
                || installationStage != InstallationStage.INSTALLED
                || installedJournal != journal || prepared.journal != journal
                || prepared.certifiedPose == null || prepared.certifiedPose != pose
                || installed != prepared.placement || journal.placement() != installed
                || pose.placement() != installed
                || !(installed.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !journal.scope().equals(accepted.scope())
                || journal.revision() != Math.incrementExact(accepted.revision()))
            throw new IllegalStateException("normal installed Free endpoint is unavailable");
    }

    void beginInstallation(PreparedInstallation prepared) {
        requirePrepared(prepared);
        requireCurrent();
        if (installationStage != InstallationStage.PREPARED)
            throw new IllegalStateException("Free geometry installation mutation began in an invalid stage");
        installationStage = InstallationStage.MUTATING;
    }

    /** Called immediately after native setters return; journal construction already happened pre-mutation. */
    void recordInstalled(PreparedInstallation prepared) {
        requirePrepared(prepared);
        if (installationStage != InstallationStage.MUTATING)
            throw new IllegalStateException("Free geometry installation was not marked before mutation");
        installed = prepared.placement;
        installedJournal = prepared.journal;
        try {
            captureMutationGeometry();
            boolean matchesGeometry = geometryMatches(prepared.placement);
            requireOwnerFence();
            if (!matchesGeometry) {
                installationStage = InstallationStage.PARTIAL;
                throw new IllegalStateException("Free geometry setter returned with a mismatched native mirror");
            }
            installationStage = InstallationStage.INSTALLED;
            requireOwnerFence();
            prepared.normalInstallation = true;
        } catch (RuntimeException failure) {
            recordFailure(failure);
            if (installationStage != InstallationStage.INSTALLED) installationStage = InstallationStage.PARTIAL;
            throw failure;
        }
    }

    /** Retains actual post-setter geometry even when a native setter throws after mutating. */
    void recordMutationFailure(PreparedInstallation prepared, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        if (prepared != null && prepared.transaction == this) prepared.normalInstallation = false;
        if (prepared == null || prepared.transaction != this || preparedInstallation != prepared
                || installationStage != InstallationStage.MUTATING) {
            recordFailure(failure);
            return;
        }
        installed = prepared.placement;
        installedJournal = prepared.journal;
        try {
            captureMutationGeometry();
            if (geometryMatches(prepared.placement)) installationStage = InstallationStage.INSTALLED;
            else installationStage = InstallationStage.PARTIAL;
        } catch (RuntimeException observationFailure) {
            if (observationFailure != failure) failure.addSuppressed(observationFailure);
            installationStage = InstallationStage.PARTIAL;
        }
        recordFailure(failure);
    }

    private void captureMutationGeometry() {
        try { mutationGeometry = Optional.of(WorldCollisionCandidates.ActorGeometry.read(actor)); }
        catch (RuntimeException observationFailure) {
            mutationGeometry = Optional.empty();
            throw observationFailure;
        }
    }

    private void requirePrepared(PreparedInstallation prepared) {
        if (prepared == null || prepared.transaction != this || preparedInstallation != prepared)
            throw new IllegalArgumentException("Free installation belongs to a different owner transaction");
    }

    private void requireInstallOwnerCurrent() {
        if (closed || !ownerCurrent.getAsBoolean() || actor.world != world
                || actor.connection != connection || world.getTotalWorldTime() != tick
                || travel.correctionRevision() != correction)
            throw new IllegalStateException("Free geometry installation lost its owner lifetime or correction fence");
    }

    /** Preserve the first committed-tail failure while retaining cleanup evidence. */
    void recordFailure(RuntimeException cause) {
        Objects.requireNonNull(cause, "cause");
        if (originalFailure == null) originalFailure = cause;
        else if (originalFailure != cause) originalFailure.addSuppressed(cause);
        if (installationStage == InstallationStage.INSTALLED || installationStage == InstallationStage.PARTIAL) {
            try {
                captureMutationGeometry();
            } catch (RuntimeException observationFailure) {
                RuntimeException retained = originalFailure;
                if (observationFailure != retained) retained.addSuppressed(observationFailure);
            }
        }
    }

    private boolean geometryMatches(PlayerBodyPlacement placement) {
        var geometry = WorldCollisionCandidates.ActorGeometry.read(actor);
        var dimensions = placement.shape().dimensions();
        boolean physicalAnchorMatches = geometry.position().equals(placement.transport())
                && geometry.bounds().equals(placement.bounds())
                && Float.floatToIntBits(geometry.width()) == Float.floatToIntBits((float) dimensions.width())
                && Float.floatToIntBits(geometry.height()) == Float.floatToIntBits((float) dimensions.height());
        if (!physicalAnchorMatches) return false;
        int actualEye = Float.floatToIntBits(actor.getEyeHeight());
        if (pendingCardinalPose != null
                && !pendingCardinalPose.allowsInstallationEye(actor, accepted.scope(), placement, actualEye)
                && !(placement == installed
                    && pendingCardinalPose.allowsProneSuccessor(actor, accepted, placement, actualEye))) return false;
        if (installedEyeObservation != null && placement == installed)
            return acceptsInstalledEye(placement, Float.intBitsToFloat(actualEye));
        if (actualEye == Float.floatToIntBits((float) dimensions.eyeHeight())) return true;
        if (pendingCardinalPose != null
                && pendingCardinalPose.allowsEye(actor, accepted.scope(), placement, actualEye)) return true;
        PlayerPhysicsServer.NativeRestoreAuthority nativeAuthority = nativeRestoreAuthority;
        if (installed == null && placement == accepted.placement() && nativeAuthority != null
                && nativeAuthority.allowsEffectiveEye(this, actualEye)) return true;
        TrustedResizeAuthority authority = trustedResizeAuthority;
        return installed == null && placement == accepted.placement() && authority != null
                && authority.authorizes(this)
                && actualEye == Float.floatToIntBits((float) authority.observation().dimensions().eyeHeight());
    }

    /**
     * A sampler may accept the live eye captured by this transaction's exact trusted resize proposal
     * while it still owns the accepted physical anchor. This does not authorize any other geometry
     * or survive installation; requireCurrent keeps the owner, tick, connection and full anchor live.
     */
    boolean acceptsTrustedResizeObservedEye(float observedEyeHeight) {
        requireCurrent();
        TrustedResizeAuthority authority = trustedResizeAuthority;
        int observedEye = Float.floatToIntBits(observedEyeHeight);
        boolean acceptedEye = accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && installed == null && authority != null && authority.authorizes(this)
                && geometryMatches(accepted.placement())
                && observedEye == Float.floatToIntBits(actor.getEyeHeight())
                && observedEye == Float.floatToIntBits((float) authority.observation().dimensions().eyeHeight());
        requireOwnerFence();
        return acceptedEye;
    }

    boolean authorizesTrustedResize(ModeTransitionPrepared.TrustedResizeObservation observation) {
        requireCurrent();
        TrustedResizeAuthority authority = trustedResizeAuthority;
        latestTrustedResizeAuthorityPredicates = "NOT_EVALUATED";
        latestTrustedResizeAuthorityFailureStage = "NOT_EVALUATED";
        Boolean observationPresent = observation != null;
        Boolean authorityPresent = observationPresent ? authority != null : null;
        Boolean sameObservation = Boolean.TRUE.equals(authorityPresent)
                ? authority.observation() == observation : null;
        Boolean authorityAuthorized = Boolean.TRUE.equals(sameObservation) ? authority.authorizes(this) : null;
        Boolean actorGeometryMatchesAcceptedAnchor = Boolean.TRUE.equals(authorityAuthorized)
                ? WorldCollisionCandidates.ActorGeometry.read(actor).equals(expectedAcceptedAnchorGeometry()) : null;
        boolean authorized = Boolean.TRUE.equals(actorGeometryMatchesAcceptedAnchor);
        String stage = Boolean.FALSE.equals(observationPresent) ? "observation"
                : Boolean.FALSE.equals(authorityPresent) ? "authority"
                : Boolean.FALSE.equals(sameObservation) ? "observationIdentity"
                : Boolean.FALSE.equals(authorityAuthorized) ? "authority." + latestTrustedResizeAuthorityFailureStage
                : Boolean.FALSE.equals(actorGeometryMatchesAcceptedAnchor)
                        ? "actorGeometryMatchesAcceptedAnchor" : "authorized";
        latestTrustedResizeAuthorizationDiagnostic = "stage=" + stage
                + "; observationPresent=" + diagnosticValue(observationPresent)
                + "; authorityPresent=" + diagnosticValue(authorityPresent)
                + "; observationIdentity=" + diagnosticValue(sameObservation)
                + "; authorityAuthorizes=" + diagnosticValue(authorityAuthorized)
                + "; actorGeometryMatchesAcceptedAnchor=" + diagnosticValue(actorGeometryMatchesAcceptedAnchor)
                + "; authorityPredicates=" + latestTrustedResizeAuthorityPredicates;
        requireOwnerFence();
        return authorized;
    }

    private WorldCollisionCandidates.ActorGeometry expectedAcceptedAnchorGeometry() {
        var placement = accepted.placement();
        var dimensions = placement.shape().dimensions();
        return new WorldCollisionCandidates.ActorGeometry(accepted.scope().playerId(), accepted.scope().dimension(),
                placement.transport(), placement.bounds(), (float) dimensions.width(), (float) dimensions.height(),
                false);
    }

    String trustedResizeAuthorizationDiagnostic() {
        return latestTrustedResizeAuthorizationDiagnostic;
    }

    private static String diagnosticValue(Boolean value) {
        return value == null ? "NOT_EVALUATED" : value.toString();
    }

    PlayerPhysicsServer.PendingCardinalPose pendingCardinalPose() { return pendingCardinalPose; }

    PlayerBodyShape.Dimensions cardinalLiveDimensions() {
        requireCurrent();
        float observedEye = actor.getEyeHeight();
        requireOwnerFence();
        if (pendingCardinalPose != null) {
            boolean authorized = pendingCardinalPose.allowsEye(actor, accepted.scope(), accepted.placement(), Float.floatToIntBits(observedEye));
            requireOwnerFence();
            if (!authorized) throw new IllegalStateException("Pending Cardinal pose no longer authorizes live dimensions");
            return accepted.placement().shape().dimensions();
        }
        return new PlayerBodyShape.Dimensions(actor.width, actor.height, observedEye);
    }

    /** Set the raw vanilla field so the active native profile returns the requested eye exactly. */
    static void installEffectiveEyeHeight(EntityPlayerMP actor, double effectiveEyeHeight) {
        installEffectiveEyeHeight(actor, effectiveEyeHeight, () -> { });
    }

    /** Installs the eye under this transaction's lifetime fence after every native getter. */
    void installEffectiveEyeHeight(double effectiveEyeHeight) {
        PlayerBodyPlacement placement = preparedInstallation == null ? accepted.placement() : preparedInstallation.placement();
        if (pendingCardinalPose != null && placement.shape() instanceof PlayerBodyShape.FreeCapsule) {
            requireOwnerFence();
            int observedEye = Float.floatToIntBits(actor.getEyeHeight());
            requireOwnerFence();
            if (installationStage != InstallationStage.MUTATING || preparedInstallation == null
                    || observedEye != Float.floatToIntBits((float) effectiveEyeHeight)
                    || !pendingCardinalPose.allowsProneSuccessor(actor, accepted, placement, observedEye))
                throw new IllegalStateException("Pending pose cannot hand its native eye to this Free installation");
            requireOwnerFence();
            return;
        }
        if (accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule source && source.pronePose().isPresent()
                && placement.shape() instanceof PlayerBodyShape.Cardinal
                && actor instanceof NativePoseEyeProducer.OwnedStandingMirror mirror) {
            var profile = ProneLocomotion.standingProfileForInstallation(this);
            if (!profile.dimensions().equals(placement.shape().dimensions()))
                throw new IllegalStateException("Standing eye mirror does not match its proved full native target");
            var exactPrepared = preparedInstallation;
            var write = new NativePoseEyeProducer.StandingEyeWrite(actor, profile.input().standingRawEyeHeight(),
                    (float) effectiveEyeHeight, () -> {
                        requireOwnerFence();
                        if (installationStage != InstallationStage.MUTATING || preparedInstallation != exactPrepared
                                || exactPrepared == null || exactPrepared.placement() != placement || !profile.current())
                            throw new IllegalStateException("Standing eye mirror lost its exact owner mutation");
                    });
            mirror.unigrav$installStandingEye(write);
            requireOwnerFence();
            if (Float.floatToIntBits(actor.getEyeHeight()) != Float.floatToIntBits((float) effectiveEyeHeight))
                throw new IllegalStateException("Standing eye mirror did not round-trip exactly");
            requireOwnerFence();
            return;
        }
        installEffectiveEyeHeight(actor, effectiveEyeHeight, this::requireOwnerFence, pendingCardinalPose,
                accepted.scope(), placement);
    }

    private static void installEffectiveEyeHeight(EntityPlayerMP actor, double effectiveEyeHeight,
            Runnable afterGetter) {
        installEffectiveEyeHeight(actor, effectiveEyeHeight, afterGetter, null, null, null);
    }

    static void installEffectiveEyeHeight(EntityPlayerMP actor, double effectiveEyeHeight, Runnable afterGetter,
            PlayerPhysicsServer.PendingCardinalPose pending, PlayerPhysicalSnapshot.Scope scope,
            PlayerBodyPlacement placement) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(afterGetter, "afterGetter");
        float effective = (float) effectiveEyeHeight;
        // Native pose integrations can cache the effective eye independently of the raw field.
        // An already represented target needs no inverse write; preserve native raw recovery.
        float currentEffectiveEye = actor.getEyeHeight();
        afterGetter.run();
        if (pending != null) {
            boolean authorized = placement != null
                    && Float.floatToIntBits(effective) == Float.floatToIntBits((float) placement.shape().dimensions().eyeHeight())
                    && pending.allowsInstallationEye(actor, scope, placement, Float.floatToIntBits(currentEffectiveEye));
            afterGetter.run();
            if (!authorized) throw new IllegalStateException("Pending Cardinal pose no longer authorizes eye installation");
            // Both retained and desired groups already have a native target eye. Never invert it into raw.
            return;
        }
        if (Float.floatToIntBits(currentEffectiveEye) == Float.floatToIntBits(effective)) return;
        boolean sleeping = actor.isPlayerSleeping();
        afterGetter.run();
        if (sleeping) {
            // The native sleeping getter is fixed at 0.2F and ignores the raw base field.
        } else {
            boolean sneaking = actor.isSneaking();
            afterGetter.run();
            if (sneaking || actor.height == 1.65F) {
                // Cleanroom's actual getter applies this subtraction before its elytra/0.6F branch.
                actor.eyeHeight = rawEyeHeightForSubtractedProfile(effective);
            } else {
                boolean elytraFlying = actor.isElytraFlying();
                afterGetter.run();
                if (!elytraFlying && actor.height != 0.6F) actor.eyeHeight = effective;
                // The native elytra/0.6F getter supplies fixed 0.4F independently of the raw base.
            }
        }
        float observedEffectiveEye = actor.getEyeHeight();
        afterGetter.run();
        if (Float.floatToIntBits(observedEffectiveEye) != Float.floatToIntBits(effective))
            throw new IllegalStateException("native pose eye installation did not round-trip exactly"
                    + " (effective=" + effective + ", actual=" + observedEffectiveEye + ")");
    }

    private static float rawEyeHeightForSubtractedProfile(float effective) {
        float candidate = effective + 0.08F;
        if (candidate - 0.08F == effective) return candidate;
        float lower = candidate;
        float upper = candidate;
        for (int step = 0; step < 4; step++) {
            lower = Math.nextDown(lower);
            if (lower - 0.08F == effective) return lower;
            upper = Math.nextUp(upper);
            if (upper - 0.08F == effective) return upper;
        }
        throw new IllegalStateException("effective sneaking eye has no exact vanilla raw-field round trip: "
                + effective);
    }

    @Override public void close() {
        if (!closed && !settled && admission != null) ledger.abortCenterPath(admission);
        installedEyeObservation = null;
        closed = true;
    }

    private static Vec3 vector(Vec3d v) { return new Vec3(v.x(), v.y(), v.z()); }
}
