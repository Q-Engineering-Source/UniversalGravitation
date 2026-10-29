package wi.unigrav.core.player;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.stats.StatList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeHooks;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.compat.EquipmentFlightSampling;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.ImpactDamageHandler;
import wi.unigrav.core.network.ImpactReportMessage;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.boost.BoostGearProfile;
import wi.unigrav.core.boost.MotionSpeedLimit;
import wi.unigrav.core.boost.RemoteBoostCorridor;
import wi.unigrav.core.floor.BootStandingEvidence;
import wi.unigrav.core.floor.BootSurfaceQuery;

/** One owner-scheduled environment/collision transaction. Never grants a new movement budget. */
public final class PlayerPhysicsStep {
    /** Near-foot locomotion allowance in metres; this is not magnetic capture or ground contact. */
    private static final double ZERO_G_FOOT_REACH = 0.25;
    private static final double ZERO_G_WALK_SPEED = 0.25;
    private static final double ZERO_G_SPRINT_SPEED = 0.5;
    private static final double ZERO_G_WALK_ACCELERATION = 2.0;
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final CardinalMovementResolver.Limits MOVE =
            new CardinalMovementResolver.Limits(512, 16384, 100000);
    /** Shared bounded Cardinal collision work policy for server and local prediction. */
    public static CardinalMovementResolver.Limits cardinalMovementLimits() { return MOVE; }

    private final PlayerEnvironmentSampler environment;

    public sealed interface Result permits Applied, Unavailable, CommittedFailure, CommittedDeferred, CommittedTailStop { }
    enum ReferencePriority { ENVIRONMENT, MANUAL }
    record ReferencePriorityAttitude(TravelState owner, PlayerAttitudeRequest request,
                                     PlayerPhysicalSnapshot accepted, long worldTick, ReferencePriority reason) {
        ReferencePriorityAttitude {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(accepted, "accepted");
        }
    }
    /** Terminal observation for this newly proposed manual request, never a saved preference retry. */
    record PendingManualFailure(TravelState owner, PlayerPhysicalSnapshot accepted,
                                PlayerBodyReference.ManualIntent request, long correctionRevision,
                                BooleanSupplier ownerCurrent, PlayerPhysicsProtocol.BodyReferenceReason reason,
                                Optional<RuntimeException> cause) { }

    record CommittedTailStop(PlayerPhysicalSnapshot snapshot,
            Optional<FreeOwnerTransaction.InstallationCustody> custody, boolean nativeTransition)
            implements Result, ModeTransitionFinalizer.Result { }
    public enum FreeMovementStopKind { FULLY_BLOCKED, PARTIAL }
    public record FreeMovementStopDiagnostic(FreeMovementStopKind kind, String detail) {
        public FreeMovementStopDiagnostic {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }
    public record Applied(PlayerPhysicalSnapshot snapshot,
                          Optional<FreeMovementStopDiagnostic> freeMovementStopDiagnostic,
                          Optional<String> rejectedAttitudeDiagnostic) implements Result {
        public Applied(PlayerPhysicalSnapshot snapshot) { this(snapshot, Optional.empty()); }
        public Applied(PlayerPhysicalSnapshot snapshot, Optional<FreeMovementStopDiagnostic> freeMovementStopDiagnostic) {
            this(snapshot, freeMovementStopDiagnostic, Optional.empty());
        }
        public Applied {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(freeMovementStopDiagnostic, "freeMovementStopDiagnostic");
            Objects.requireNonNull(rejectedAttitudeDiagnostic, "rejectedAttitudeDiagnostic");
        }
    }
    public record Unavailable(String reason, Optional<RuntimeException> cause,
                              Optional<PlayerPhysicalSnapshot> referenceCommitSnapshot,
                              Optional<String> freeMovementDiagnostic) implements Result {
        public Unavailable(String reason, Optional<RuntimeException> cause) {
            this(reason, cause, Optional.empty(), Optional.empty());
        }
        public Unavailable(String reason, Optional<RuntimeException> cause,
                           Optional<PlayerPhysicalSnapshot> referenceCommitSnapshot) {
            this(reason, cause, referenceCommitSnapshot, Optional.empty());
        }
    }
    /** The move and debit happened; remaining callbacks failed and must never be replayed. */
    public enum CommittedFailureStage { OWNER_TAIL, CLEARANCE }
    public record CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause,
                                   Optional<FreeOwnerTransaction.InstallationCustody> custody,
                                   CommittedFailureStage stage) implements Result {
        public CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause,
                Optional<FreeOwnerTransaction.InstallationCustody> custody) {
            this(snapshot, cause, custody, CommittedFailureStage.OWNER_TAIL);
        }
        public CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause) {
            this(snapshot, cause, Optional.empty());
        }
        public CommittedFailure {
            Objects.requireNonNull(snapshot); Objects.requireNonNull(cause); Objects.requireNonNull(custody); Objects.requireNonNull(stage);
        }
    }
    /** A known failed endpoint read, distinct from an arbitrary committed callback failure. */
    public static final class PostCommitObservationUnavailable extends IllegalStateException {
        private final PlayerEnvironmentSampler.Reason reason;
        PostCommitObservationUnavailable(PlayerEnvironmentSampler.Unavailable unavailable) {
            super("post-commit environment observation unavailable: " + unavailable.reason(),
                    unavailable.cause().orElse(null));
            reason = unavailable.reason();
        }
        public PlayerEnvironmentSampler.Reason reason() { return reason; }
    }
    /** A bounded tail query was unavailable, but its already installed movement is not undone. */
    public record CommittedDeferred(PlayerPhysicalSnapshot snapshot, Unavailable tail) implements Result { }
    /** Per-owner native jump cooldown and correction fence, never shared between players or sides. */
    public static final class TravelState {
        private NativeFlightParameters equipmentFlight;
        private PlayerPhysicalSnapshot equipmentFlightSource;
        private long equipmentFlightTick;
        NativeFlightParameters equipmentFlight(PlayerPhysicalSnapshot source, long tick) {
            return equipmentFlightSource == source && equipmentFlightTick == tick ? equipmentFlight : null;
        }
        private void recordEquipmentFlight(PlayerPhysicalSnapshot source, long tick, NativeFlightParameters parameters) {
            equipmentFlightSource = source;
            equipmentFlightTick = tick;
            equipmentFlight = parameters;
        }
        private int jumpTicks;
        private final PlayerMobilityIntent.Owner mobility = new PlayerMobilityIntent.Owner();
        private PlayerMobilityIntent.HoverStatus hoverStatus = PlayerMobilityIntent.HoverStatus.OFF;
        private PlayerMobilityIntent.Reason hoverReason = PlayerMobilityIntent.Reason.NONE;
        PlayerMobilityIntent.Owner mobility() { return mobility; }
        PlayerMobilityIntent.State mobilityState(PlayerPhysicalSnapshot snapshot) {
            boolean cardinal = snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal;
            return mobility.state(cardinal ? PlayerMobilityIntent.EffectiveMode.WALK : PlayerMobilityIntent.EffectiveMode.FREE,
                    cardinal ? PlayerMobilityIntent.HoverStatus.OFF : hoverStatus,
                    cardinal ? PlayerMobilityIntent.Reason.NONE : hoverReason);
        }
        private long nativeCorrectionRevision;
        private BooleanSupplier ownerCurrent = () -> true;
        boolean nativeNoGravity;
        private CardinalFrame pendingReference;
        private CardinalReorientation.Reason pendingReason;
        private boolean lastAdoptionGrounded;
        private long adoptions;
        private PlayerBodyReference.OwnerReference ownerReference = new PlayerBodyReference.CardinalReference(
                PlayerBodyReference.State.initial(CardinalFrame.Y_POS, new Vec3d(1, 0, 0)));
        private boolean bodyReferenceInitialized;
        private PlayerPhysicalSnapshot referenceCommitSnapshot;
        private long referenceCommitCorrectionRevision;
        private BooleanSupplier referenceCommitOwnerCurrent = () -> false;
        private StandingConstraints.State standingState = StandingConstraints.State.initial();
        private boolean previousJump;
        private boolean protectedBootSourceInvalidated;
        private PlayerBodyReference.ManualIntent pendingManualIntent;
        private ReferencePriorityAttitude referencePriorityAttitude;
        private PendingManualFailure pendingManualFailure;
        private PlayerPhysicalSnapshot retainedCardinalSnapshot;
        boolean retainedCardinal(PlayerPhysicalSnapshot snapshot) { return retainedCardinalSnapshot == snapshot; }
        private PlayerPhysicalSnapshot predictionSnapshot;
        private CardinalPredictionSeed predictionSeed;
        private PlayerPhysicalSnapshot jumpSource;
        private PlayerPhysicsProtocol.JumpOutcome jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.NONE;

        CardinalPredictionSeed predictionSeed(PlayerPhysicalSnapshot snapshot) {
            if (predictionSnapshot == snapshot && predictionSeed != null) return predictionSeed;
            return snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    ? new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_SHAPE)
                    : new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, Optional.empty());
        }
        void predictionSeed(PlayerPhysicalSnapshot snapshot, CardinalPredictionSeed seed) {
            predictionSnapshot = Objects.requireNonNull(snapshot);
            predictionSeed = Objects.requireNonNull(seed);
        }
        void rebindTerminalSnapshot(PlayerPhysicalSnapshot provisional, PlayerPhysicalSnapshot completed) {
            Objects.requireNonNull(provisional, "provisional");
            Objects.requireNonNull(completed, "completed");
            if (completed.scope() != provisional.scope() || completed.revision() != provisional.revision()
                    || completed.placement() != provisional.placement())
                throw new IllegalStateException("terminal response changed the sampled physical snapshot identity");
            if (predictionSnapshot == provisional) predictionSnapshot = completed;
            if (retainedCardinalSnapshot == provisional) retainedCardinalSnapshot = completed;
        }
        PlayerPhysicsProtocol.JumpOutcome jumpOutcomeForRevision(long revision) {
            return jumpSource != null && jumpSource.revision() == revision ? jumpOutcome : PlayerPhysicsProtocol.JumpOutcome.NONE;
        }

        long correctionRevision() { return nativeCorrectionRevision; }
        /** Cooldown at the completed owner step; publishing it does not consume a jump edge. */
        int predictionJumpCooldown() { return jumpTicks; }
        void clearReferencePriorityAttitude() { referencePriorityAttitude = null; }
        void referencePriorityAttitude(ReferencePriorityAttitude marker) {
            referencePriorityAttitude = Objects.requireNonNull(marker, "marker");
        }
        Optional<ReferencePriorityAttitude> consumeReferencePriorityAttitude() {
            ReferencePriorityAttitude marker = referencePriorityAttitude;
            referencePriorityAttitude = null;
            return Optional.ofNullable(marker);
        }

        void invalidate() {
            mobility.correction();
            predictionSnapshot = null; predictionSeed = null; jumpSource = null;
            jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.NONE;
            pendingManualFailure = null;
            nativeCorrectionRevision = Math.incrementExact(nativeCorrectionRevision);
        }

        /** The world already names the accepted reference; no transition is pending. */
        private void acceptedReference(CardinalFrame reference) {
            if (pendingReference == reference) { pendingReference = null; pendingReason = null; }
        }

        private void fixedReference() {
            pendingReference = null;
            pendingReason = null;
        }

        /** No standing constraint is present, so no target may be retained as pending. */
        private void withoutReference() {
            pendingReference = null;
            pendingReason = null;
        }

        private void adoptedReference(CardinalFrame reference, boolean onGround) {
            pendingReference = null;
            pendingReason = null;
            lastAdoptionGrounded = onGround;
            adoptions = Math.incrementExact(adoptions);
        }

        /** A rejected transition keeps the old state whole; the target stays explicit for retry. */
        private void deferredReference(CardinalFrame reference, CardinalReorientation.Reason reason) {
            pendingReference = reference;
            pendingReason = reason;
        }

        /** Explicit pending reorientation target, or empty when the accepted reference still stands. */
        public Optional<CardinalFrame> pendingReference() { return Optional.ofNullable(pendingReference); }

        public Optional<CardinalReorientation.Reason> pendingReason() { return Optional.ofNullable(pendingReason); }

        /** Support re-derived by the last successful reorientation, not inherited from the old frame. */
        public boolean lastAdoptionGrounded() { return lastAdoptionGrounded; }

        public long adoptions() { return adoptions; }

        /** The resolver's current effective axis and any blocked retry target, without mutation. */
        public PlayerBodyReference.OwnerReference ownerReference() { return ownerReference; }
        void ownerReference(PlayerBodyReference.OwnerReference next) {
            ownerReference = Objects.requireNonNull(next, "ownerReference");
            bodyReferenceInitialized = true;
        }
        public Optional<PlayerBodyReference.State> cardinalReference() { return ownerReference.cardinalState(); }
        public PlayerBodyReference.State bodyReference() {
            return cardinalReference().orElseThrow(() -> new IllegalStateException("Free body has no effective Cardinal reference"));
        }
        void bodyReference(PlayerBodyReference.State next) {
            ownerReference(new PlayerBodyReference.CardinalReference(Objects.requireNonNull(next, "bodyReference")));
        }
        boolean bodyReferenceInitialized() { return bodyReferenceInitialized; }
        PlayerPhysicalSnapshot referenceCommitSnapshot() {
            PlayerPhysicalSnapshot snapshot = referenceCommitSnapshot;
            if (snapshot == null) return null;
            if (referenceCommitCorrectionRevision != nativeCorrectionRevision
                    || !referenceCommitOwnerCurrent.getAsBoolean()) {
                clearReferenceCommitSnapshot();
                return null;
            }
            return snapshot;
        }
        private void referenceCommitSnapshot(PlayerPhysicalSnapshot snapshot, long correctionRevision,
                                             BooleanSupplier ownerCurrent) {
            referenceCommitSnapshot = Objects.requireNonNull(snapshot, "snapshot");
            referenceCommitCorrectionRevision = correctionRevision;
            referenceCommitOwnerCurrent = Objects.requireNonNull(ownerCurrent, "ownerCurrent");
        }
        void clearReferenceCommitSnapshot() {
            referenceCommitSnapshot = null;
            referenceCommitOwnerCurrent = () -> false;
        }
        private StandingConstraints.State standingState() { return standingState; }
        private void standingState(StandingConstraints.State next) { standingState = Objects.requireNonNull(next, "standingState"); }
        private boolean takeJumpEdge(boolean jump, boolean confirmedAttachedSupport) {
            boolean edge = jump && !previousJump;
            previousJump = jump;
            return edge && confirmedAttachedSupport;
        }

        private boolean protectedBootSourceInvalidated() { return protectedBootSourceInvalidated; }
        private void protectedBootSourceInvalidated(boolean invalidated) {
            protectedBootSourceInvalidated = invalidated;
        }
        Optional<PlayerBodyReference.ManualIntent> pendingManualIntent() {
            return Optional.ofNullable(pendingManualIntent);
        }
        void pendingManualIntent(Optional<PlayerBodyReference.ManualIntent> pending) {
            var next = Objects.requireNonNull(pending, "pending").orElse(null);
            if (next != pendingManualIntent) pendingManualFailure = null;
            pendingManualIntent = next;
        }
        Optional<PendingManualFailure> consumePendingManualFailure(PlayerPhysicalSnapshot accepted) {
            var failure = pendingManualFailure;
            pendingManualFailure = null;
            if (failure == null || failure.owner() != this || failure.accepted() != accepted
                    || failure.request() != pendingManualIntent || failure.correctionRevision() != nativeCorrectionRevision
                    || !failure.ownerCurrent().getAsBoolean()) return Optional.empty();
            return Optional.of(failure);
        }
    }

    public PlayerPhysicsStep(PlayerEnvironmentSampler environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** The server supplies its exact Session/Owned/connection fence for this one invocation. */
    Result stepOwned(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                     PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                     boolean boostCarrier, TravelState travel, BooleanSupplier ownerCurrent) {
        return stepOwned(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, ownerCurrent, null);
    }

    Result stepOwned(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                     PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                     boolean boostCarrier, TravelState travel, BooleanSupplier ownerCurrent,
                     FreeOwnerTransaction free) {
        return stepOwned(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, ownerCurrent, free, Optional.empty());
    }

    Result stepOwned(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                     PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                     boolean boostCarrier, TravelState travel, BooleanSupplier ownerCurrent,
                     FreeOwnerTransaction free, Optional<PlayerAttitudeRequest> attitudeRequest) {
        return stepOwned(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, ownerCurrent, free, attitudeRequest, Optional.empty());
    }

    /**
     * A camera LOOK quaternion is not a body-rotation request while the server still owns
     * the same accepted Cardinal reference in WALK. A missing reference or explicit FLIGHT
     * keeps the existing Cardinal-to-Free body-attitude path available.
     */
    static boolean cardinalWalkHeadLookOnly(PlayerPhysicalSnapshot accepted, TravelState travel) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(travel, "travel");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || travel.mobility().mode() != PlayerMobilityIntent.Mode.WALK) return false;
        return travel.ownerReference().cardinalState()
                .filter(state -> state.effective().frame() == cardinal.frame()).isPresent();
    }

    Result stepOwned(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                     PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                     boolean boostCarrier, TravelState travel, BooleanSupplier ownerCurrent,
                     FreeOwnerTransaction free, Optional<PlayerAttitudeRequest> attitudeRequest,
                     Optional<ModeTransitionPrepared.TrustedResizeObservation> trustedResize) {
        Objects.requireNonNull(ownerCurrent, "ownerCurrent");
        Objects.requireNonNull(attitudeRequest, "attitudeRequest");
        Objects.requireNonNull(trustedResize, "trustedResize");
        travel.retainedCardinalSnapshot = null;
        travel.clearReferencePriorityAttitude();
        travel.predictionSeed(accepted, new CardinalPredictionSeed.Unavailable(
                CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, Optional.empty()));
        travel.jumpSource = accepted;
        travel.jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.NONE;
        Optional<PlayerAttitudeRequest> stepAttitudeRequest = cardinalWalkHeadLookOnly(accepted, travel)
                ? Optional.empty() : attitudeRequest;
        BooleanSupplier previous = travel.ownerCurrent;
        travel.ownerCurrent = ownerCurrent;
        try {
            if (!ownerCurrent.getAsBoolean()) return unavailable("movement owner transaction changed");
            if (free == null || free.accepted() != accepted)
                return unavailable("owner did not provide its shared movement transaction");
            free.requireCurrent();
            if (accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule) {
                return finishTerminalResponse(finishFreeCarrier(stepFreeOwned(actor, accepted, personalZeroG, controls, budget, budgetTick,
                        boostCarrier, travel, free, stepAttitudeRequest, trustedResize), free, budget, budgetTick, boostCarrier), free);
            }
            if (trustedResize.isPresent()) return unavailable("trusted Free resize proposal no longer has a Free owner");
            if (travel.mobility.blocksCapture()) {
                if (free.pendingCardinalPose() != null)
                    return unavailable("PENDING_NATIVE_POSE: settle native dimensions before mobility conversion");
                var request = MobilityTransitionRequest.capture(free, travel);
                var preparation = ModeTransitionPrepared.prepareCardinalToFree(free, request, budgetTick);
                if (preparation instanceof ModeTransitionPrepared.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                var installed = ModeTransitionFinalizer.install((ModeTransitionPrepared.Prepared) preparation, budgetTick);
                if (installed instanceof ModeTransitionFinalizer.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                if (installed instanceof CommittedTailStop stopped) return stopped;
                if (installed instanceof ModeTransitionFinalizer.CommittedFailure failure)
                    return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
                var applied = (ModeTransitionFinalizer.Applied) installed;
                var prior = travel.bodyReference();
                travel.ownerReference(new PlayerBodyReference.FreeReference(prior.manualIntent(), Optional.empty(),
                        Optional.of(prior.effective())));
                travel.standingState(StandingConstraints.State.initial());
                return finishTerminalResponse(new Applied(applied.snapshot()), free);
            }
            if (stepAttitudeRequest.isPresent()) {
                var sampled = environment.sample(actor, accepted, personalZeroG,
                        free == null ? null : free.pendingCardinalPose(),
                        free == null ? null : free.resources().environmentObservationWork());
                if (!ownerCurrent.getAsBoolean())
                    return unavailable("movement authority changed during Q environment qualification");
                if (sampled instanceof PlayerEnvironmentSampler.Unavailable failure)
                    return new Unavailable("Q environment qualification: " + failure.reason(), failure.cause());
                var sample = (PlayerEnvironmentSampler.Sample) sampled;
                long tick = actor.getServerWorld().getTotalWorldTime();
                if (sample.physical() != accepted || sample.worldTick() != tick)
                    return unavailable("Q environment qualification no longer matches its accepted owner tick");

                // This probe is read-only and does not claim the input edge. The resolver's only
                // jump-dependent target is the already-confirmed attached boot's surface frame;
                // without that attached case, jumpEdge does not change target selection. The normal
                // step below receives the same sample and resolves the actual edge exactly once.
                PlayerBodyShape.Cardinal acceptedShape =
                        (PlayerBodyShape.Cardinal) accepted.placement().shape();
                StandingInput standingInput = normalizeStandingState(travel.standingState(),
                        travel.protectedBootSourceInvalidated(), sample.evidence(), tick, acceptedShape.frame());
                var qualification = StandingConstraints.resolve(standingInput.state(),
                        new StandingConstraints.Request(tick, false, sample.evidence()));
                if (qualification.presence() == StandingConstraints.ConstraintPresence.UNAVAILABLE)
                    return unavailable("standing evidence unavailable during Q environment qualification");
                boolean environmentWins = qualification.presence() == StandingConstraints.ConstraintPresence.PRESENT
                        && qualification.targetReference()
                        .filter(target -> hasEnvironmentReference(sample.evidence(), target)).isPresent();
                boolean manualWins = travel.pendingManualIntent().isPresent()
                        || travel.ownerReference().manualIntent().isPresent();
                if (environmentWins || manualWins) {
                    // The same authoritative sample and current saved/pending preference govern both
                    // transition directions. A mouse Q cannot retire a live standing reference.
                    Result result = stepWithSample(actor, accepted, personalZeroG, controls, budget, budgetTick,
                            boostCarrier, travel, sample, free);
                    if (result instanceof Applied)
                        travel.referencePriorityAttitude(new ReferencePriorityAttitude(
                                travel, stepAttitudeRequest.orElseThrow(), accepted, tick,
                                environmentWins ? ReferencePriority.ENVIRONMENT : ReferencePriority.MANUAL));
                    return finishTerminalResponse(result, free);
                }
                if (free != null && free.pendingCardinalPose() != null)
                    return unavailable("PENDING_NATIVE_POSE: settle native Cardinal dimensions before Free preparation");
                var prepared = ModeTransitionPrepared.prepareCardinalToFree(free,
                        stepAttitudeRequest.orElseThrow(), budgetTick);
                if (prepared instanceof ModeTransitionPrepared.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                var installed = ModeTransitionFinalizer.install(
                        (ModeTransitionPrepared.Prepared) prepared, budgetTick);
                if (installed instanceof ModeTransitionFinalizer.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                if (installed instanceof CommittedTailStop stopped) return stopped;
                if (installed instanceof ModeTransitionFinalizer.CommittedFailure failure)
                    return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
                var applied = (ModeTransitionFinalizer.Applied) installed;
                PlayerBodyReference.State previousReference = travel.bodyReference();
                travel.ownerReference(new PlayerBodyReference.FreeReference(
                        previousReference.manualIntent(), Optional.empty(),
                        Optional.of(previousReference.effective())));
                return finishTerminalResponse(new Applied(applied.snapshot()), free);
            }
            return finishTerminalResponse(finishFreeCarrier(stepWithSample(actor, accepted, personalZeroG, controls, budget, budgetTick,
                    boostCarrier, travel, null, free), free, budget, budgetTick, boostCarrier), free);
        } finally {
            travel.ownerCurrent = previous;
        }
    }

    private Result stepFreeOwned(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            Optional<Boolean> personalZeroG, PlayerPhysicsProtocol.Controls controls,
            BoostMovementBudget budget, long budgetTick, boolean boostCarrier, TravelState travel,
            FreeOwnerTransaction transaction, Optional<PlayerAttitudeRequest> attitudeRequest,
            Optional<ModeTransitionPrepared.TrustedResizeObservation> trustedResize) {

        budget.advance(budgetTick);
        var recovery = CapsuleDepenetrationPrepared.prepare(transaction);
        if (recovery.isPresent()) {
            if (recovery.orElseThrow() instanceof ModeTransitionPrepared.Unavailable failed)
                return new Unavailable(failed.reason(), failed.cause());
            var installed = ModeTransitionFinalizer.install((ModeTransitionPrepared.Prepared) recovery.orElseThrow(), budgetTick);
            if (installed instanceof ModeTransitionFinalizer.Unavailable failed)
                return new Unavailable(failed.reason(), failed.cause());
            if (installed instanceof CommittedTailStop stopped) return stopped;
            if (installed instanceof ModeTransitionFinalizer.CommittedFailure failed)
                return new CommittedFailure(failed.snapshot(), failed.cause(), failed.custody());
            return new Applied(((ModeTransitionFinalizer.Applied) installed).snapshot());
        }
        Optional<Boolean> zeroG = personalZeroG.isPresent() ? personalZeroG : Optional.of(false);
        var sampled = environment.sampleFree(transaction, zeroG, SCAN);
        if (sampled instanceof PlayerEnvironmentSampler.FreeUnavailable failure)
            return new Unavailable("Free environment: " + failure.reason(), failure.cause());
        var sample = (PlayerEnvironmentSampler.FreeSample) sampled;
        transaction.requireCurrent();
        sample.observation().requireForceCurrent();
        boolean water = sample.observation().environment().inWater();
        var proneChoice = trustedResize.isPresent() ? ProneLocomotion.Choice.NONE
                : ProneLocomotion.choose(transaction, travel, controls, water);
        ModeTransitionPrepared.Request movementRequest = proneChoice.request()
                .<ModeTransitionPrepared.Request>map(request -> request).orElse(attitudeRequest.orElse(null));
        var pronePose = ((PlayerBodyShape.FreeCapsule) accepted.placement().shape()).pronePose();
        budget.advance(budgetTick);
        if (boostCarrier) applyCorridor(budget, budgetTick, accepted.placement().transport().y());
        boolean propulsion = PlayerBoostEligibility.propulsionAllowed(sample.personalZeroG(),
                actor.interactionManager.isCreative())
                && travel.mobility().mode() == PlayerMobilityIntent.Mode.FLIGHT;
        boolean nativeFlight = !sample.personalZeroG() && actor.capabilities.isFlying;
        boolean flying = !propulsion && nativeFlight;
        boolean nativeGravity = !sample.personalZeroG() && !travel.nativeNoGravity
                && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty() && !flying;
        Vec3 acceleration = travel.mobility.blocksCapture() ? sample.detachedAcceleration() : sample.environmentAcceleration();
        if (nativeGravity) acceleration = acceleration.add(new Vec3(0, water ? -8 : -32, 0));
        Vec3d acceptedVelocity = accepted.worldVelocity();
        Vec3 velocity = new Vec3(acceptedVelocity.x(), acceptedVelocity.y(), acceptedVelocity.z());
        double dt = GravityEntityBinder.TICK_SECONDS;
        sample.observation().requireForceCurrent();
        if (!propulsion && !flying) velocity = velocity.add(acceleration.scale(dt));
        NativeFlightMotion flightMotion = flying ? nativeFlightMotion(actor, velocity, controls, transaction, travel) : null;
        if (flightMotion != null) velocity = flightMotion.velocity();
        Vec3 rawAcceleration = sample.rawFields().gravityAcceleration().add(sample.rawFields().magneticAcceleration());
        boolean rawNativeGravity = !travel.nativeNoGravity
                && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty()
                && !actor.capabilities.isFlying;
        if (rawNativeGravity) rawAcceleration = rawAcceleration.add(new Vec3(0, -32, 0));
        PropulsionMotion propulsionMotion = propulsion ? freePropulsionMotion(velocity, controls, budget, actor.posY, dt) : null;
        if (propulsionMotion != null) velocity = propulsionMotion.velocity();
        ProneLocomotion.WaterMotion waterMotion = water && !propulsion && !flying
                && !travel.mobility.blocksCapture()
                ? ProneLocomotion.waterMotion(transaction, controls, velocity,
                        ProneLocomotion.waterUp(accepted.placement())) : null;
        if (waterMotion != null) velocity = waterMotion.velocity();
        // Unattached Survival has no propulsion authority. Translation input cannot create thrust;
        // equipment-backed propulsion remains deferred, independently of raw force or personal G.

        if (!(travel.ownerReference() instanceof PlayerBodyReference.FreeReference freeReference))
            return unavailable("accepted Free geometry has no Free owner reference record");
        StandingConstraints.Evidence referenceEvidence;
        try {
            referenceEvidence = freeReferenceEvidence(sample, transaction, nativeGravity);
        } catch (RuntimeException failure) {
            return new Unavailable("Free-to-Cardinal qualified boot/support evidence failed at its owner boundary",
                    Optional.of(failure));
        }
        boolean hasSource = !sample.rawFields().faceEvidence().isEmpty()
                || referenceEvidence.bootCandidates().stream().anyMatch(StandingConstraints.BootCandidate::isEffective);
        boolean nearFootActiveDrive = false;
        boolean nearFootPermitted = false;
        if (!propulsion && !flying && !travel.mobility.blocksCapture() && !water) {
            boolean sprinting = controls.sprint() && !controls.sneak()
                    && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6);
            transaction.requireCurrent();
            try {
                if (pronePose.isPresent()) {
                    var supportUp = ProneLocomotion.supportUp(accepted.placement());
                    if (ProneLocomotion.bellySupported(transaction, sample.observation(), supportUp)) {
                        double target = actor.getAIMoveSpeed() / dt * .3;
                        transaction.requireCurrent();
                        var gait = FreeNearFootDrive.gait(velocity, controls.translation(), supportUp,
                                target, ZERO_G_WALK_ACCELERATION * dt);
                        velocity = gait.velocity();
                        nearFootActiveDrive = gait.activeDrive();
                    }
                } else if (FreeNearFootDrive.permitted(transaction, sample.observation())) {
                    nearFootPermitted = true;
                    double target = (sprinting ? ZERO_G_SPRINT_SPEED : ZERO_G_WALK_SPEED)
                            * (controls.sneak() ? .3 : 1);
                    var gait = FreeNearFootDrive.gait(velocity, controls.translation(), accepted.placement().bodyUp(),
                            target, ZERO_G_WALK_ACCELERATION * dt);
                    velocity = gait.velocity();
                    nearFootActiveDrive = gait.activeDrive();
                }
            } catch (RuntimeException failure) {
                return new Unavailable("Free near-foot gait observation failed", Optional.of(failure));
            }
            transaction.requireCurrent();
        }
        double braking = UnigravConfig.hoverBrakingBlocksPerTickSquared / (dt * dt);
        var hover = PlayerMobilityIntent.hover(velocity, propulsion ? Vec3.ZERO : acceleration, rawAcceleration,
                hasSource, travel.mobility.hover(), propulsion, braking, dt,
                nearFootActiveDrive || flying && nativeFlightDrive(controls)
                        || propulsionMotion != null && propulsionMotion.activeDrive());
        travel.hoverStatus = hover.status(); travel.hoverReason = hover.reason();
        velocity = hover.velocity();
        double speed = Math.hypot(Math.hypot(velocity.x(), velocity.y()), velocity.z());
        if (!Double.isFinite(speed)) return unavailable("non-finite Free integrated velocity");
        if (speed > budget.ceilingMps()) velocity = velocity.scale(budget.ceilingMps() / speed);
        if (flightMotion != null) flightMotion = new NativeFlightMotion(velocity, velocity.y() * dt);
        Vec3d delta = new Vec3d(velocity.x() * dt, velocity.y() * dt, velocity.z() * dt);
        Optional<FreeReferenceModePrepared.Selection> reverseSelection;
        FreeStandingProposal standingProposal;
        try {
            standingProposal = travel.mobility.blocksCapture() || proneChoice.blocksStanding() ? null
                    : prepareFreeStanding(freeReference, travel, referenceEvidence, attitudeRequest, actor, transaction.tick());
            reverseSelection = standingProposal == null ? Optional.empty() : standingProposal.selection();
        } catch (RuntimeException failure) {
            return new Unavailable("Free-to-Cardinal reference selection failed at the owner boundary",
                    Optional.of(failure));
        }
        if (reverseSelection.isPresent()) {
            FreeReferenceModePrepared.Selection selection = reverseSelection.orElseThrow();
            double oldMotionX = actor.motionX, oldMotionY = actor.motionY, oldMotionZ = actor.motionZ;
            boolean oldOnGround = actor.onGround;
            boolean oldCollidedHorizontally = actor.collidedHorizontally;
            boolean oldCollidedVertically = actor.collidedVertically;
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            actor.onGround = false;
            actor.collidedHorizontally = false;
            actor.collidedVertically = false;
            FreeReferenceModePrepared.Preparation prepared;
            try {
                prepared = FreeReferenceModePrepared.prepare(transaction, sample.observation(), freeReference,
                        travel.pendingManualIntent().orElse(null), trustedResize.orElse(null), selection, delta, budgetTick,
                        endpoint -> {
                            var result = sample.observeReference(endpoint, nativeGravity);
                            if (result instanceof PlayerEnvironmentSampler.FreeUnavailable failed)
                                return new FreeReferenceModePrepared.Unavailable("endpoint reference unavailable: "
                                        + failed.reason(), PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE, failed.cause());
                            var reference = (PlayerEnvironmentSampler.EndpointReference) result;
                            var observation = reference.observation();
                            var measurements = observation.boots().measurements();
                            var support = observation.actualSupport(measurements.orElse(null));
                            var evidence = measurements.isPresent()
                                    ? BootStandingEvidence.withEndpointBootQueryAndSupport(
                                            reference.fields(), measurements.orElseThrow(), support, accepted,
                                            transaction.tick(), true, transaction.postprocessing()::charge)
                                    : BootStandingEvidence.withNativeSupport(reference.fields(), support);
                            return new FreeReferenceModePrepared.ReferenceEvaluation(
                                    prepareFreeStanding(freeReference, travel, evidence, attitudeRequest, actor,
                                            transaction.tick()), observation);
                        }, standingProposal);
            } catch (RuntimeException failure) {
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable("Free-to-Cardinal preparation threw at its owner boundary",
                        Optional.of(failure));
            }
            if (prepared instanceof FreeReferenceModePrepared.Unavailable failure) {
                retainFreePendingTarget(travel, freeReference, selection, failure.reason());
                recordPendingManualFailure(travel, accepted, freeReference, selection, failure.reason(), failure.cause());
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable(failure.detail(), failure.cause());
            }
            if (prepared instanceof FreeReferenceModePrepared.FreeSuccessor successor) {
                try {
                    if (travel.ownerReference() != successor.originalReference()
                            || !successor.evaluation().proposal().current()
                            || !successor.originalObservation().recaptureMatches(transaction)
                            || !successor.evaluation().observation().recaptureMatches(transaction)) {
                        restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                                oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                        return unavailable("Free reference absence successor observation changed");
                    }
                    var freeInstalled = ModeTransitionFinalizer.install(successor.prepared(), budgetTick);
                    if (freeInstalled instanceof ModeTransitionFinalizer.Unavailable failure) {
                        restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                                oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                        return new Unavailable(failure.reason(), failure.cause());
                    }
                    if (freeInstalled instanceof CommittedTailStop stopped) return stopped;
                    if (freeInstalled instanceof ModeTransitionFinalizer.CommittedFailure failure)
                        return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
                    var snapshot = ((ModeTransitionFinalizer.Applied) freeInstalled).snapshot();
                    try {
                        successor.evaluation().proposal().publish(transaction);
                    } catch (RuntimeException failure) {
                        return new CommittedFailure(snapshot, failure, transaction.installationCustody());
                    }
                    return new Applied(snapshot);
                } catch (RuntimeException failure) {
                    transaction.recordFailure(failure);
                    if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                            || transaction.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL)
                        return new CommittedFailure(transaction.installedJournal().orElseThrow(),
                                transaction.originalFailure().orElse(failure), transaction.installationCustody());
                    restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                            oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                    return new Unavailable("Free reference absence finalization failed before native installation", Optional.of(failure));
                }
            }
            var installed = FreeReferenceModeFinalizer.install(
                    (FreeReferenceModePrepared.Prepared) prepared, travel, budgetTick);
            if (installed instanceof FreeReferenceModeFinalizer.Unavailable failure) {
                retainFreePendingTarget(travel, freeReference, selection, failure.reason());
                recordPendingManualFailure(travel, accepted, freeReference, selection, failure.reason(), failure.cause());
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable(failure.detail(), failure.cause());
            }
            if (installed instanceof FreeReferenceModeFinalizer.CommittedFailure failure)
                return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
            if ((selection.effective().source() == PlayerBodyReference.Source.ENVIRONMENT
                    || selection.effective().source() == PlayerBodyReference.Source.MANUAL)
                    && attitudeRequest.isPresent())
                travel.referencePriorityAttitude(new ReferencePriorityAttitude(
                        travel, attitudeRequest.orElseThrow(), accepted, transaction.tick(),
                        selection.effective().source() == PlayerBodyReference.Source.ENVIRONMENT
                                ? ReferencePriority.ENVIRONMENT : ReferencePriority.MANUAL));
            var endpoint = ((FreeReferenceModeFinalizer.Applied) installed).snapshot();
            try {
                // The owner still publishes the old Free source until this step returns. Native fluid
                // predicates must use the exact installed journal, while seed evidence uses the final endpoint.
                var endpointSeed = new CardinalPredictionSeed[1];
                transaction.runNativeEnvironment(transaction.installedJournal().orElseThrow(), () -> {
                    endpointSeed[0] = observePredictionSeed(actor, endpoint, zeroG, travel,
                            budget.ceilingMps(), transaction.resources(), transaction::requireCurrent,
                            transaction.pendingCardinalPose());
                    if (endpointSeed[0] instanceof CardinalPredictionSeed.Unavailable failed
                            && failed.reason() == CardinalPredictionSeed.Reason.SAMPLING_FAILED && failed.cause().isPresent())
                        throw failed.cause().orElseThrow();
                });
                if (endpointSeed[0] == null)
                    throw new IllegalStateException("Installed prediction sampling did not execute");
                travel.predictionSeed(endpoint, endpointSeed[0]);
                ((FreeReferenceModePrepared.Prepared) prepared).requireReferenceSourcesAfterInstall();
                ((FreeReferenceModePrepared.Prepared) prepared).standingProposal().publish(transaction);
            } catch (RuntimeException failure) {
                transaction.recordFailure(failure);
                return new CommittedFailure(endpoint, transaction.originalFailure().orElse(failure),
                        transaction.installationCustody());
            }
            return new Applied(endpoint);
        }

        if (movementRequest != null || trustedResize.isPresent()) {
            double oldMotionX = actor.motionX, oldMotionY = actor.motionY, oldMotionZ = actor.motionZ;
            boolean oldOnGround = actor.onGround;
            boolean oldCollidedHorizontally = actor.collidedHorizontally;
            boolean oldCollidedVertically = actor.collidedVertically;
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            actor.onGround = false;
            actor.collidedHorizontally = false;
            actor.collidedVertically = false;
            var prepared = ModeTransitionPrepared.prepareFreeTick(transaction,
                    movementRequest, trustedResize.orElse(null), delta, budgetTick);
            if (prepared instanceof ModeTransitionPrepared.Unavailable failure) {
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable(failure.reason(), failure.cause());
            }
            var retained = prepared instanceof ModeTransitionPrepared.RetainedAttitude rejection ? rejection : null;
            var installable = retained == null ? (ModeTransitionPrepared.Prepared) prepared : retained.prepared();
            var installed = ModeTransitionFinalizer.install(installable, budgetTick);
            if (installed instanceof ModeTransitionFinalizer.Unavailable failure) {
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable(failure.reason(), failure.cause());
            }
            if (installed instanceof CommittedTailStop stopped) return stopped;
            if (installed instanceof ModeTransitionFinalizer.CommittedFailure failure)
                return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
            var applied = (ModeTransitionFinalizer.Applied) installed;
            if (standingProposal != null) {
                try { standingProposal.publish(transaction); }
                catch (RuntimeException failure) {
                    return new CommittedFailure(applied.snapshot(), failure, transaction.installationCustody());
                }
            }
            try {
                var completed = flightMotion == null ? applied.snapshot()
                        : finishNativeFlight(transaction, applied.snapshot(), flightMotion, acceleration);
                if (waterMotion != null) completed = finishWater(transaction, completed, waterMotion);
                return new Applied(completed,
                        Optional.empty(), retained == null ? Optional.empty() : Optional.of(retained.rejection()));
            } catch (RuntimeException failure) {
                return new CommittedFailure(applied.snapshot(), failure, transaction.installationCustody());
            }
        }
        CapsulePlacementMath.Pose startPose = CapsulePlacementMath.pose(accepted.placement(), transaction.numeric());
        CapsulePlacementMath.Translation tube = CapsulePlacementMath.translationTube(startPose, delta, transaction.numeric());
        double requestedPath = tube.requestedDisplacementUpper();
        var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick, requestedPath);
        if (!admission.accepted()) return unavailable("Free center-path grant rejected: " + admission.failure());
        if (requestedPath > admission.grant()) {
            double scale = requestedPath == 0 ? 0 : Math.nextDown(admission.grant() / requestedPath);
            tube = CapsulePlacementMath.translationTube(startPose,
                    admission.grant() == 0 ? new Vec3d(0,0,0) : delta.multiply(Math.max(0,scale)), transaction.numeric());
            if (tube.requestedDisplacementUpper() > admission.grant())
                return unavailable("rounded initial Free tube exceeds center-path grant: requested="
                        + requestedPath + "; grant=" + admission.grant());
        }
        transaction.requireReservedPath(tube.requestedDisplacementUpper());
        var window = CapsuleMovementResolver.window(accepted, tube);
        var captured = WorldCollisionCandidates.capture(transaction, window,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
        if (captured instanceof WorldCollisionCandidates.Unavailable failure)
            return new Unavailable("Free collision capture: " + failure.reason() + ": " + failure.detail(), failure.cause());
        var resolvedResult = CapsuleMovementResolver.resolveWithSlide(transaction, tube,
                (WorldCollisionCandidates.Snapshot) captured);
        if (!(resolvedResult instanceof CapsuleMovementResolver.Resolved resolved)) {
            var failure = (CapsuleMovementResolver.Inconclusive) resolvedResult;
            var failedQuery = failure.limitingQuery().orElse(null);
            String boundaryKind = "NONE";
            String boundaryReason = "NONE";
            String prefixEndFraction = "NONE";
            String sourcePrefixEndFraction = "NONE";
            String obstructionBracket = "NONE";
            String installation = "NONE";
            if (failedQuery != null && failedQuery.boundary().isPresent()) {
                var boundary = failedQuery.boundary().orElseThrow();
                boundaryKind = boundary.kind().name();
                boundaryReason = boundary.reason().map(Enum::name).orElse("NONE");
                prefixEndFraction = boundary.prefix().map(prefix -> Double.toString(prefix.endFraction())).orElse("NONE");
                sourcePrefixEndFraction = failedQuery.sourcePrefixEndFraction()
                        .map(value -> Double.toString(value)).orElse("NONE");
                obstructionBracket = boundary.firstObstructionBracket()
                        .map(bracket -> bracket.lower() + ".." + bracket.upper()).orElse("NONE");
                installation = boundary.installation().isPresent() ? "PRESENT" : "ABSENT";
            }
            String obstacle = failedQuery == null ? "NONE"
                    : "min=" + vectorText(failedQuery.obstacle().min())
                    + ";max=" + vectorText(failedQuery.obstacle().max());
            String actualRadius = failedQuery == null ? "NONE"
                    : Double.toString(failedQuery.tube().start().capsule().radius());
            String queryRadius = failedQuery == null ? "NONE"
                    : Double.toString(failedQuery.tube().queryCapsule().radius());
            var failureTube = failedQuery == null ? tube : failedQuery.tube();
            var observationTube = failedQuery == null ? tube : failedQuery.observationTube();
            String resolverReason = failure.reason().replace('\r', ' ').replace('\n', ' ');
            if (resolverReason.length() > 160) resolverReason = resolverReason.substring(0, 160) + "...";
            String diagnostic = "stage=free-movement-resolve;resolverReason=" + resolverReason
                    + ";ordinal=" + failure.ordinal() + ";boundaryKind=" + boundaryKind
                    + ";boundaryReason=" + boundaryReason + ";prefixEndFraction=" + prefixEndFraction
                    + ";sourcePrefixEndFraction=" + sourcePrefixEndFraction
                    + ";firstObstructionBracket=" + obstructionBracket + ";installation=" + installation
                    + ";actualRadius=" + actualRadius + ";queryRadius=" + queryRadius + ";obstacle=" + obstacle
                    + ";start=" + vectorText(failureTube.start().placement().center())
                    + ";delta=" + vectorText(failureTube.delta())
                    + ";observationStart=" + vectorText(observationTube.start().placement().center())
                    + ";observationDelta=" + vectorText(observationTube.delta())
                    + ";observationToSourceFractionScale="
                    + (failedQuery == null ? "NONE" : Double.toString(failedQuery.sourceParameterScale()))
                    + ";resolverQueries=" + failure.queries().size()
                    + ";certifiedPlanes=" + failure.planes().size()
                    + ";chbCalls=" + transaction.queries().calls()
                    + ";numericUnits=" + transaction.numeric().units();
            return new Unavailable("Free movement path unresolved: " + failure.reason(), failure.cause(),
                    Optional.empty(), Optional.of(diagnostic));
        }
        if (!resolved.matches(transaction)) return unavailable("Free movement proof became stale");

        double oldMotionX = actor.motionX, oldMotionY = actor.motionY, oldMotionZ = actor.motionZ;
        boolean oldOnGround = actor.onGround;
        boolean oldCollidedHorizontally = actor.collidedHorizontally;
        boolean oldCollidedVertically = actor.collidedVertically;
        actor.motionX = velocity.x() * dt;
        actor.motionY = velocity.y() * dt;
        actor.motionZ = velocity.z() * dt;
        actor.onGround = false;
        actor.collidedHorizontally = false;
        actor.collidedVertically = false;
        CapsuleMoveFinalizer.Preparation preparation;
        try {
            preparation = CapsuleMoveFinalizer.prepare(transaction, resolved);
        } catch (RuntimeException failure) {
            restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                    oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
            return new Unavailable("Free movement finalizer preparation failed", Optional.of(failure));
        }
        if (!(preparation instanceof CapsuleMoveFinalizer.Ready ready)) {
            restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                    oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
            return unavailable("Free movement tail unavailable");
        }
        try {
            PlayerPhysicalSnapshot installed = ready.commit();
            transaction.requireCurrent();
            if (!travel.ownerCurrent.getAsBoolean() || PlayerKinematics.requiresNative(actor))
                throw new IllegalStateException("Free movement owner changed after installation");
            if (flightMotion != null) installed = finishNativeFlight(transaction, installed, flightMotion, acceleration);
            if (waterMotion != null) installed = finishWater(transaction, installed, waterMotion);
            if (standingProposal != null) standingProposal.publish(transaction);
            Optional<FreeMovementStopDiagnostic> movementStopDiagnostic =
                    resolved.stop().map(PlayerPhysicsStep::movementStopDiagnostic);
            Optional<PlayerPhysicalSnapshot.Contacts> contacts = flightMotion == null
                    ? Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                            actor.collidedVertically, actor.fallDistance, nearFootPermitted))
                    : installed.contacts();
            return new Applied(new PlayerPhysicalSnapshot(installed.scope(), installed.revision(),
                    installed.placement(), installed.worldVelocity(), contacts), movementStopDiagnostic);
        } catch (FreeNativeTail.Stop stopped) {
            if (!stopped.belongsTo(transaction)) throw stopped;
            return stopped.result();
        } catch (RuntimeException failure) {
            transaction.recordFailure(failure);
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.NONE
                    || transaction.installationStage() == FreeOwnerTransaction.InstallationStage.PREPARED) {
                restoreFreePreinstallActorState(actor, oldMotionX, oldMotionY, oldMotionZ,
                        oldOnGround, oldCollidedHorizontally, oldCollidedVertically);
                return new Unavailable("Free movement failed before native mutation", Optional.of(failure));
            }
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                    || transaction.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL)
                return new CommittedFailure(transaction.installedJournal().orElseThrow(),
                        transaction.originalFailure().orElse(failure), transaction.installationCustody());
            return new Unavailable("Free movement failed before native installation", Optional.of(failure));
        }
    }

    private static Result finishFreeCarrier(Result result, FreeOwnerTransaction transaction,
            BoostMovementBudget budget, long tick, boolean carrier) {
        if (!carrier || !(result instanceof Applied applied)
                || !(applied.snapshot().placement().shape() instanceof PlayerBodyShape.FreeCapsule)) return result;
        try {
            transaction.requireCurrent();
            applyCorridor(budget, tick, applied.snapshot().placement().transport().y());
            transaction.requireCurrent();
            return result;
        } catch (RuntimeException failure) {
            return new CommittedFailure(applied.snapshot(), failure, transaction.installationCustody());
        }
    }

    private static Result finishTerminalResponse(Result result, FreeOwnerTransaction transaction) {
        if (!(result instanceof Applied applied)) return result;
        try {
            PlayerPhysicalSnapshot completed = transaction.completeTerminalResponse(applied.snapshot());
            return new Applied(completed, applied.freeMovementStopDiagnostic(), applied.rejectedAttitudeDiagnostic());
        } catch (RuntimeException failure) {
            transaction.recordFailure(failure);
            return new CommittedFailure(applied.snapshot(),
                    transaction.originalFailure().orElse(failure), transaction.installationCustody());
        }
    }

    private record PropulsionMotion(Vec3 velocity, boolean activeDrive) { }
    /** Shared accepted-velocity propulsion arithmetic for an existing Free owner and its first conversion tick. */
    private static PropulsionMotion freePropulsionMotion(Vec3 velocity, PlayerPhysicsProtocol.Controls controls,
            BoostMovementBudget budget, double feetY, double seconds) {
        double acceleration = UnigravConfig.sixDofDriftAcceleration;
        double limit = UnigravConfig.sixDofDriftSpeedLimit;
        if (!Double.isFinite(acceleration) || acceleration < 0 || !Double.isFinite(limit) || limit < 0)
            throw new IllegalArgumentException("Invalid Free drift tuning");
        Vec3d input = controls.translation();
        Vec3 drive = new Vec3(input.x(), input.y(), input.z());
        velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, drive.scale(acceleration * seconds), limit));
        boolean active = acceleration > 0 && drive.normSquared() > 0;
        if (controls.boost()) {
            double target = RemoteBoostCorridor.propulsionTarget(BoostGearProfile.speedMps(controls.boostGear()),
                    budget.ceilingMps(), RemoteBoostCorridor.isRestricted(feetY));
            if (target > 0) {
                Vec3d forward = controls.forward();
                Vec3 delta = new Vec3(forward.x(), forward.y(), forward.z())
                        .scale(BoostGearProfile.accelerationMps2(controls.boostGear()) * seconds);
                velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, delta, target));
                active = true;
            }
        }
        return new PropulsionMotion(velocity, active);
    }

    private record NativeFlightMotion(Vec3 velocity, double verticalBlocksPerTick) { }
    private static Vec3 cappedConversionVelocity(Vec3 velocity, double ceiling) {
        double speed = velocity.norm();
        if (!Double.isFinite(speed)) throw new IllegalStateException("non-finite converted velocity");
        return speed > ceiling ? velocity.scale(ceiling / speed) : velocity;
    }

    private static boolean nativeFlightDrive(PlayerPhysicsProtocol.Controls controls) {
        return controls.translation().x() != 0 || controls.translation().z() != 0 || controls.jump() || controls.sneak();
    }
    /** Native flight uses view-mapped world-XZ input and world-Y lift, independently of physical body Q. */
    private static NativeFlightMotion nativeFlightMotion(EntityPlayerMP actor, Vec3 acceptedVelocity,
            PlayerPhysicsProtocol.Controls controls, FreeOwnerTransaction transaction, TravelState travel) {
        transaction.requireCurrent();
        // Sneak is an input to the native kernel. Actual pose/profile flags belong to the
        // native-pose/TrustedResizeProposal transaction, not this movement arithmetic.
        actor.setSprinting(controls.sprint() && !controls.sneak()
                && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6));
        transaction.requireCurrent();
        double dt = GravityEntityBinder.TICK_SECONDS;
        var flight = EquipmentFlightSampling.sample(actor, controls);
        transaction.requireCurrent();
        travel.recordEquipmentFlight(transaction.accepted(), transaction.tick(), flight);
        Vec3d equippedMotion = flight.beforeTravel(new Vec3d(acceptedVelocity.x() * dt,
                acceptedVelocity.y() * dt, acceptedVelocity.z() * dt), actor.onGround, controls);
        Vec3 tickVelocity = new Vec3(Math.abs(equippedMotion.x()) < .003D ? 0 : equippedMotion.x(),
                Math.abs(equippedMotion.y()) < .003D ? 0 : equippedMotion.y(),
                Math.abs(equippedMotion.z()) < .003D ? 0 : equippedMotion.z());
        double vertical = CardinalTravelKernel.flightInput(tickVelocity.y(),
                controls.sneak(), controls.jump(), flight.flySpeed());
        float coefficient = flight.flySpeed() * (actor.isSprinting() ? 2 : 1);
        transaction.requireCurrent();
        var driven = CardinalTravelKernel.accelerate(new Vec3d(tickVelocity.x(), vertical, tickVelocity.z()), new CardinalTravelKernel.Input(CardinalFrame.Y_POS,
                controls.translation(), controls.keyboardTravelGain(), controls.sneak(), controls.jump(), actor.rotationYaw),
                coefficient, true);
        transaction.requireCurrent();
        return new NativeFlightMotion(new Vec3(driven.x() / dt, driven.y() / dt, driven.z() / dt), vertical);
    }
    private static PlayerPhysicalSnapshot finishNativeFlight(FreeOwnerTransaction transaction,
            PlayerPhysicalSnapshot installed, NativeFlightMotion flight, Vec3 acceleration) {
        transaction.requireCurrent();
        if (transaction.installedPlacement().orElse(null) != installed.placement())
            throw new IllegalStateException("native Free flight lost its exact installed journal");
        var actor = transaction.actor();
        var post = CardinalTravelKernel.finish(new Vec3d(actor.motionX, actor.motionY, actor.motionZ),
                CardinalFrame.Y_POS, new Vec3d(acceleration.x(), acceleration.y(), acceleration.z()),
                GravityEntityBinder.TICK_SECONDS, .91F, actor.motionY);
        transaction.requireCurrent();
        actor.motionX = post.x(); actor.motionY = flight.verticalBlocksPerTick() * .6D; actor.motionZ = post.z();
        actor.fallDistance = 0;
        actor.clearElytraFlying();
        transaction.requireCurrent();
        return new PlayerPhysicalSnapshot(installed.scope(), installed.revision(), installed.placement(),
                new Vec3d(actor.motionX, actor.motionY, actor.motionZ).multiply(1 / GravityEntityBinder.TICK_SECONDS),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, actor.collidedHorizontally,
                        actor.collidedVertically, actor.fallDistance, false)));
    }

    private static PlayerPhysicalSnapshot finishWater(FreeOwnerTransaction transaction,
            PlayerPhysicalSnapshot installed, ProneLocomotion.WaterMotion water) {
        transaction.requireCurrent();
        if (transaction.installedPlacement().orElse(null) != installed.placement())
            throw new IllegalStateException("Free water drag lost its exact installed journal");
        var actor = transaction.actor();
        double dt = GravityEntityBinder.TICK_SECONDS;
        var post = ProneLocomotion.dampWater(new Vec3(actor.motionX / dt, actor.motionY / dt, actor.motionZ / dt), water);
        transaction.numeric().charge(30);
        actor.motionX = post.x() * dt; actor.motionY = post.y() * dt; actor.motionZ = post.z() * dt;
        transaction.requireCurrent();
        return new PlayerPhysicalSnapshot(installed.scope(), installed.revision(), installed.placement(),
                new Vec3d(post.x(), post.y(), post.z()), installed.contacts());
    }

    private static void restoreFreePreinstallActorState(EntityPlayerMP actor,
            double motionX, double motionY, double motionZ, boolean onGround,
            boolean collidedHorizontally, boolean collidedVertically) {
        actor.motionX = motionX; actor.motionY = motionY; actor.motionZ = motionZ;
        actor.onGround = onGround;
        actor.collidedHorizontally = collidedHorizontally;
        actor.collidedVertically = collidedVertically;
    }

    public Result step(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel) {
        return stepWithSample(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, null, null);
    }

    private Result stepWithSample(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel, PlayerEnvironmentSampler.Sample prefetchedSample, FreeOwnerTransaction free) {
        Objects.requireNonNull(travel, "travel");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal shape))
            return unavailable("unsupported physical frame");
        PlayerBodyReference.State reference = travel.bodyReference();
        if (!travel.bodyReferenceInitialized()) {
            reference = PlayerBodyReference.State.initial(shape.frame(),
                    PlayerBodyReference.initialHeading(shape.frame(), actor.rotationYaw));
            travel.bodyReference(reference);
        } else if (reference.effective().frame() != shape.frame()) {
            throw new IllegalStateException("effective body reference differs from accepted physical geometry");
        }
        return stepInternal(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, reference, prefetchedSample, free);
    }

    /** Compatibility seam for callers that still provide a body lock directly. */
    public Result step(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel, Optional<CardinalFrame> lockedReference) {
        Objects.requireNonNull(lockedReference, "lockedReference");
        if (lockedReference.isEmpty())
            return step(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier, travel);
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal acceptedShape))
            return unavailable("unsupported physical frame");
        if (!travel.bodyReferenceInitialized())
            travel.bodyReference(PlayerBodyReference.State.initial(acceptedShape.frame(),
                    PlayerBodyReference.initialHeading(acceptedShape.frame(), actor.rotationYaw)));
        PlayerBodyReference.State saved = travel.bodyReference();
        if (lockedReference.isPresent()) {
            CardinalFrame locked = lockedReference.orElseThrow();
            if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal shape) || shape.frame() != locked)
                throw new IllegalStateException("accepted body reference lock differs from physical geometry");
            long requestId = saved.manualIntent().map(PlayerBodyReference.ManualIntent::requestId).orElse(1L);
            Vec3d heading = saved.effective().frame() == locked
                    ? saved.effective().tangentHeading() : PlayerBodyReference.tangentOrFallback(new Vec3d(1, 0, 0), locked);
            var manual = new PlayerBodyReference.ManualIntent(requestId, locked, heading);
            saved = new PlayerBodyReference.State(Optional.of(manual),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL, locked, heading),
                    PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        }
        return stepInternal(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, saved, null, null);
    }

    private Result stepInternal(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel, PlayerBodyReference.State referenceState,
                       PlayerEnvironmentSampler.Sample prefetchedSample, FreeOwnerTransaction free) {
        travel.clearReferenceCommitSnapshot();
        Result result = stepSampled(actor, accepted, personalZeroG, controls, budget, budgetTick, boostCarrier,
                travel, referenceState, prefetchedSample, free);
        PlayerPhysicalSnapshot referenceCommitSnapshot = travel.referenceCommitSnapshot();
        if (result instanceof Unavailable unavailable && referenceCommitSnapshot != null) {
            return new Unavailable(unavailable.reason(), unavailable.cause(),
                    Optional.of(referenceCommitSnapshot));
        }
        return result;
    }

    private Result stepSampled(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel, PlayerBodyReference.State referenceState,
                       PlayerEnvironmentSampler.Sample prefetchedSample, FreeOwnerTransaction free) {
        var world = actor.getServerWorld();
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("Physics step off-thread");
        Objects.requireNonNull(referenceState, "referenceState");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal acceptedShape))
            return unavailable("unsupported physical frame");
        long stepSuccessorRevision = Math.incrementExact(accepted.revision());
        CardinalFrame frame = acceptedShape.frame();
        if (referenceState.effective().frame() != frame)
            throw new IllegalStateException("effective body reference differs from accepted physical geometry");
        PlayerBodyShape.Dimensions dimensions = acceptedShape.dimensions();
        long correctionRevision = travel.nativeCorrectionRevision;
        var sampled = prefetchedSample == null ? environment.sample(actor, accepted, personalZeroG,
                free == null ? null : free.pendingCardinalPose(),
                free == null ? null : free.resources().environmentObservationWork()) : prefetchedSample;
        if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision) return unavailable("movement authority changed during sampling");
        if (sampled instanceof PlayerEnvironmentSampler.Unavailable failure)
            return new Unavailable("environment: " + failure.reason(), failure.cause());
        var sample = (PlayerEnvironmentSampler.Sample) sampled;
        long tick = world.getTotalWorldTime();
        if (sample.physical() != accepted || sample.worldTick() != tick)
            return unavailable("environment sample no longer matches the accepted owner tick");
        sample.requireCurrent();
        Vec3 acceleration = sample.environmentAcceleration();
        Vec3d v = accepted.worldVelocity();
        double dt = GravityEntityBinder.TICK_SECONDS;
        budget.advance(budgetTick);
        boolean propulsion = PlayerBoostEligibility.propulsionAllowed(sample.personalZeroG(),
                actor.interactionManager.isCreative())
                && travel.mobility().mode() == PlayerMobilityIntent.Mode.FLIGHT;
        boolean nativeFlight = !sample.personalZeroG() && actor.capabilities.isFlying;
        boolean flying = !propulsion && nativeFlight;
        boolean water = !propulsion && !flying && actor.isInWater();
        boolean lava = !propulsion && !flying && !water && actor.isInLava();
        boolean fluid = water || lava;
        boolean gliding = !propulsion && !fluid && actor.isElytraFlying();
        boolean nativeGravity = !sample.personalZeroG() && !travel.nativeNoGravity
                && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty() && !flying;
        if (nativeGravity) acceleration = acceleration.add(new Vec3(0, fluid ? -8 : -32, 0));
        if (free != null) {
            var prone = ProneLocomotion.choose(free, travel, controls, water);
            if (prone.request().isPresent()) {
                sample.requireCurrent();
                var velocity = new Vec3(v.x(), v.y(), v.z()).add(acceleration.scale(dt));
                var waterMotion = water ? ProneLocomotion.waterMotion(free, controls, velocity, frame) : null;
                if (waterMotion != null) velocity = waterMotion.velocity();
                velocity = cappedConversionVelocity(velocity, budget.ceilingMps());
                var prepared = ModeTransitionPrepared.prepareCardinalToFree(free, prone.request().orElseThrow(),
                        new Vec3d(velocity.x(), velocity.y(), velocity.z()), budgetTick);
                if (prepared instanceof ModeTransitionPrepared.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                var installed = ModeTransitionFinalizer.install((ModeTransitionPrepared.Prepared) prepared, budgetTick);
                if (installed instanceof ModeTransitionFinalizer.Unavailable failure)
                    return new Unavailable(failure.reason(), failure.cause());
                if (installed instanceof CommittedTailStop stopped) return stopped;
                if (installed instanceof ModeTransitionFinalizer.CommittedFailure failure)
                    return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
                var applied = (ModeTransitionFinalizer.Applied) installed;
                travel.ownerReference(new PlayerBodyReference.FreeReference(referenceState.manualIntent(),
                        Optional.empty(), Optional.of(referenceState.effective())));
                travel.standingState(StandingConstraints.State.initial());
                try {
                    return new Applied(waterMotion == null ? applied.snapshot() : finishWater(free, applied.snapshot(), waterMotion));
                } catch (RuntimeException failure) {
                    return new CommittedFailure(applied.snapshot(), failure, free.installationCustody());
                }
            }
        }
        // The installed physical frame, not a fresh enum tie-break or the view, owns reference continuity.
        StandingConstraints.State standingInput = currentStandingState(travel, sample.evidence(), tick, frame);
        if (!standingInput.equals(travel.standingState())) travel.standingState(standingInput);
        SupportProbe attachedSupport = confirmsAttachedSupport(actor, accepted, frame, standingInput,
                sample.evidence(), world, free);
        if (attachedSupport.unavailableReason().isPresent())
            return new Unavailable("attached boot support: " + attachedSupport.unavailableReason().orElseThrow(),
                    attachedSupport.cause());
        // Accept this step's force once before reference/movement callbacks. Later normal changes
        // cannot invalidate the historical value; a new endpoint seed needs a fresh valid observation.
        sample.requireCurrent();
        var standing = StandingConstraints.resolve(standingInput,
                new StandingConstraints.Request(tick,
                        travel.takeJumpEdge(controls.jump(), attachedSupport.confirmed() && !propulsion
                                && !flying && !fluid && !gliding), sample.evidence()));
        Vec3 rawFieldAcceleration = sample.rawFields().gravityAcceleration().add(sample.rawFields().magneticAcceleration());
        boolean rawNative = !travel.nativeNoGravity
                && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty() && !flying;
        // Creative flight suppresses gravity for travel; it does not remove the
        // native body's standing reference or request a Free-capsule conversion.
        boolean noReferenceZeroGravity = !flying && !rawNative && rawFieldAcceleration.normSquared() == 0
                && sample.rawFields().faceEvidence().isEmpty()
                && standing.presence() == StandingConstraints.ConstraintPresence.ABSENT
                && standing.targetReference().isEmpty() && travel.pendingManualIntent().isEmpty()
                && referenceState.manualIntent().isEmpty() && !fluid && !gliding;
        if (noReferenceZeroGravity && free != null && free.pendingCardinalPose() == null) {
            var request = MobilityTransitionRequest.zeroReference(free, travel, () -> {
                sample.requireCurrent();
                return noReferenceZeroGravity && travel.pendingManualIntent().isEmpty()
                        && travel.ownerReference().manualIntent().isEmpty();
            });
            double seconds = GravityEntityBinder.TICK_SECONDS;
            if (boostCarrier) applyCorridor(budget, budgetTick, accepted.placement().transport().y());
            Vec3 integrated = new Vec3(accepted.worldVelocity().x(), accepted.worldVelocity().y(), accepted.worldVelocity().z())
                    .add(flying || propulsion ? Vec3.ZERO : acceleration.scale(seconds));
            PropulsionMotion propulsionMotion = propulsion
                    ? freePropulsionMotion(integrated, controls, budget, actor.posY, seconds) : null;
            if (propulsionMotion != null) integrated = propulsionMotion.velocity();
            NativeFlightMotion flight = flying ? nativeFlightMotion(actor, integrated, controls, free, travel) : null;
            if (flight != null) integrated = flight.velocity();
            // All external getters precede the contained-root collision observation. Both
            // possible controller results are pure and their movement windows are captured together.
            boolean gaitEligible = !propulsion && !flying && !travel.mobility.blocksCapture();
            boolean sprinting = gaitEligible && controls.sprint() && !controls.sneak()
                    && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6);
            free.requireCurrent();
            ModeTransitionPrepared.CardinalSource prelude;
            try {
                prelude = ModeTransitionPrepared.prepareCardinalSource(free, request, budgetTick);
            } catch (RuntimeException failure) {
                return new Unavailable("Cardinal near-foot prelude failed", Optional.of(failure));
            }
            double braking = UnigravConfig.hoverBrakingBlocksPerTickSquared / (seconds * seconds);
            boolean nativeDrive = flying && nativeFlightDrive(controls)
                    || propulsionMotion != null && propulsionMotion.activeDrive();
            var hover = PlayerMobilityIntent.hover(integrated, propulsion ? Vec3.ZERO : acceleration,
                    rawFieldAcceleration, false, travel.mobility.hover(), propulsion, braking, seconds, nativeDrive);
            Vec3 convertedVelocity = cappedConversionVelocity(hover.velocity(), budget.ceilingMps());
            Vec3d ordinaryCandidate = new Vec3d(convertedVelocity.x(), convertedVelocity.y(), convertedVelocity.z());
            PlayerMobilityIntent.HoverResult gaitHover = null;
            Vec3d gaitCandidate = null;
            if (gaitEligible) {
                double target = (sprinting ? ZERO_G_SPRINT_SPEED : ZERO_G_WALK_SPEED)
                        * (controls.sneak() ? .3 : 1);
                var gait = FreeNearFootDrive.gait(integrated, controls.translation(), prelude.bodyUp(),
                        target, ZERO_G_WALK_ACCELERATION * seconds);
                gaitHover = PlayerMobilityIntent.hover(gait.velocity(), acceleration, rawFieldAcceleration,
                        false, travel.mobility.hover(), false, braking, seconds, gait.activeDrive());
                var gaitVelocity = cappedConversionVelocity(gaitHover.velocity(), budget.ceilingMps());
                gaitCandidate = new Vec3d(gaitVelocity.x(), gaitVelocity.y(), gaitVelocity.z());
            }
            var prepared = ModeTransitionPrepared.prepareCardinalToFree(prelude, ordinaryCandidate, gaitCandidate, budgetTick);
            if (prepared instanceof ModeTransitionPrepared.Unavailable failure)
                return new Unavailable(failure.reason(), failure.cause());
            if (prepared instanceof ModeTransitionPrepared.Occupied occupied) {
                // Full-body adoption was refused before reserve/installation. Retain the actual
                // Cardinal shape/reference and move only the already integrated ordinary candidate.
                // No speculative near-foot gait or Free proof is used by the Cardinal solver.
                occupied.requireRetainedCardinalCurrent(free);
                if (!free.nativeCardinalMotionCurrent())
                    return new Unavailable("full Free target occupied without native Cardinal motion provenance",
                            Optional.empty());
                sample.requireCurrent();
                occupied.requireRetainedCardinalCurrent(free);
                travel.retainedCardinalSnapshot = accepted;
                var retained = moveCardinal(actor, accepted, controls, budget, budgetTick, boostCarrier, travel, free,
                        world, tick, correctionRevision, stepSuccessorRevision, sample, standing, frame,
                        convertedVelocity, acceleration, .91F, convertedVelocity.y() * seconds, 0,
                        true, false, false, false, propulsion, flying, false, false, false, occupied.proof());
                if (retained instanceof CommittedDeferred deferred)
                    travel.retainedCardinalSnapshot = deferred.snapshot();
                else if (retained instanceof CommittedFailure failed)
                    travel.retainedCardinalSnapshot = failed.snapshot();
                else if (retained instanceof CommittedTailStop stopped)
                    travel.retainedCardinalSnapshot = stopped.snapshot();
                if (retained instanceof Applied applied) {
                    travel.retainedCardinalSnapshot = applied.snapshot();
                    travel.hoverStatus = hover.status();
                    travel.hoverReason = hover.reason();
                }
                return retained;
            }
            var ready = (ModeTransitionPrepared.Prepared) prepared;
            boolean nearFootPermitted = gaitCandidate != null && ready.integratedVelocity() == gaitCandidate;
            var chosenHover = nearFootPermitted ? gaitHover : hover;
            if (flight != null) flight = new NativeFlightMotion(convertedVelocity, convertedVelocity.y() * seconds);
            var installed = ModeTransitionFinalizer.install(ready, budgetTick);
            if (installed instanceof ModeTransitionFinalizer.Unavailable failure)
                return new Unavailable(failure.reason(), failure.cause());
            if (installed instanceof CommittedTailStop stopped) return stopped;
            if (installed instanceof ModeTransitionFinalizer.CommittedFailure failure)
                return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody());
            var applied = (ModeTransitionFinalizer.Applied) installed;
            travel.ownerReference(new PlayerBodyReference.FreeReference(referenceState.manualIntent(), Optional.empty(),
                    Optional.of(referenceState.effective())));
            travel.standingState(StandingConstraints.State.initial());
            try {
                var completed = flight == null ? applied.snapshot()
                        : finishNativeFlight(free, applied.snapshot(), flight, acceleration);
                travel.hoverStatus = chosenHover.status();
                travel.hoverReason = chosenHover.reason();
                Optional<PlayerPhysicalSnapshot.Contacts> finalContacts = flight != null
                        ? completed.contacts()
                        : completed.contacts().map(contact -> new PlayerPhysicalSnapshot.Contacts(
                                contact.onGround(), contact.horizontal(), contact.vertical(),
                                contact.fallDistance(), nearFootPermitted));
                return new Applied(new PlayerPhysicalSnapshot(completed.scope(), completed.revision(),
                        completed.placement(), completed.worldVelocity(), finalContacts));
            } catch (RuntimeException failure) {
                return new CommittedFailure(applied.snapshot(), failure, free.installationCustody());
            }
        }
        Vec3 bootAcceleration = standing.authoritativeForce()
                .map(StandingConstraints.ForceSample::bootAcceleration).orElse(Vec3.ZERO);
        acceleration = acceleration.add(bootAcceleration);
        Optional<CardinalFrame> environmentReference = standing.targetReference()
                .filter(target -> hasEnvironmentReference(sample.evidence(), target));
        Optional<CardinalFrame> naturalReference = standing.targetReference().filter(target -> environmentReference.isEmpty());
        PlayerBodyReference.ManualIntent pendingManual = travel.pendingManualIntent().orElse(null);
        if (environmentReference.isPresent() && pendingManual != null) {
            // A live environmental reference accepts the new manual preference as deferred without
            // allowing it to take the physical axis from the environment.
            referenceState = referenceState.withManual(Optional.of(pendingManual),
                    PlayerBodyReference.Transition.MANUAL_DEFERRED);
        }
        boolean referenceAdopted = false;
        PlayerBodyReference.Source desiredSource;
        CardinalFrame requested;
        if (environmentReference.isPresent()) {
            desiredSource = PlayerBodyReference.Source.ENVIRONMENT;
            requested = environmentReference.orElseThrow();
        } else if (pendingManual != null) {
            desiredSource = PlayerBodyReference.Source.MANUAL;
            requested = pendingManual.frame();
        } else if (referenceState.manualIntent().isPresent()) {
            desiredSource = PlayerBodyReference.Source.MANUAL;
            requested = referenceState.manualIntent().orElseThrow().frame();
        } else if (naturalReference.isPresent()) {
            desiredSource = PlayerBodyReference.Source.NATURAL;
            requested = naturalReference.orElseThrow();
        } else {
            desiredSource = PlayerBodyReference.Source.SAFE_NATIVE;
            requested = CardinalFrame.Y_POS;
        }
        boolean environmentOverridesManual = desiredSource == PlayerBodyReference.Source.ENVIRONMENT
                && referenceState.manualIntent().isPresent();
        boolean manualWasDeferred = referenceState.transition() == PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE
                || referenceState.transition() == PlayerBodyReference.Transition.MANUAL_DEFERRED;
        Vec3d requestedHeading = desiredSource == PlayerBodyReference.Source.MANUAL
                ? pendingManual != null ? pendingManual.tangentHeading()
                : referenceState.manualIntent().orElseThrow().tangentHeading()
                : requested == referenceState.effective().frame()
                        ? referenceState.effective().tangentHeading()
                        : PlayerBodyReference.transportHeading(referenceState.effective().frame(), requested,
                                referenceState.effective().tangentHeading());
        var effective = new PlayerBodyReference.Effective(desiredSource, requested, requestedHeading);
        if (desiredSource == PlayerBodyReference.Source.ENVIRONMENT && referenceState.transition()
                == PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED
                && referenceState.pendingFrame().filter(requested::equals).isPresent()) {
            referenceState = referenceState.blocked(PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED,
                    requested, referenceState.reason().orElseThrow());
        }
        if (requested == frame) {
            if (desiredSource == PlayerBodyReference.Source.MANUAL && pendingManual != null) {
                referenceState = referenceState.withManual(Optional.of(pendingManual),
                        PlayerBodyReference.Transition.MANUAL_DEFERRED);
            }
            if (!effective.equals(referenceState.effective())) {
                PlayerBodyReference.Transition transition = switch (desiredSource) {
                    case ENVIRONMENT -> environmentOverridesManual
                            ? PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE : PlayerBodyReference.Transition.STABLE;
                    case MANUAL -> manualWasDeferred ? PlayerBodyReference.Transition.MANUAL_RESUMED
                            : PlayerBodyReference.Transition.STABLE;
                    case NATURAL, SAFE_NATIVE -> referenceState.manualIntent().isEmpty()
                            && desiredSource == PlayerBodyReference.Source.SAFE_NATIVE
                            ? PlayerBodyReference.Transition.MANUAL_CLEARED : PlayerBodyReference.Transition.STABLE;
                };
                referenceState = referenceState.accepted(effective, transition);
            } else if (environmentOverridesManual) {
                referenceState = referenceState.accepted(effective, PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE);
            } else if (referenceState.pendingFrame().isPresent()) {
                referenceState = referenceState.accepted(effective, PlayerBodyReference.Transition.STABLE);
            } else if (referenceState.transition() != PlayerBodyReference.Transition.STABLE) {
                referenceState = referenceState.accepted(effective, PlayerBodyReference.Transition.STABLE);
            }
            travel.bodyReference(referenceState);
        } else {
            var reorientation = free == null
                    ? CardinalReorientation.adopt(world, actor, accepted, requested, budget, budgetTick,
                            () -> travel.ownerCurrent.getAsBoolean() && correctionRevision == travel.nativeCorrectionRevision)
                    : CardinalReorientation.adopt(free, requested, budget, budgetTick);
            if (reorientation instanceof CardinalReorientation.CommittedFailure failure)
                return new CommittedFailure(failure.snapshot(), failure.cause(), failure.custody(), CommittedFailureStage.CLEARANCE);
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision)
                return unavailable("movement authority changed during reference reorientation");
            boolean recoveredTick = reorientation instanceof CardinalReorientation.Recovered;
            if (reorientation instanceof CardinalReorientation.Installed || recoveredTick) {
                var installedSnapshot = recoveredTick ? ((CardinalReorientation.Recovered) reorientation).snapshot()
                        : ((CardinalReorientation.Installed) reorientation).snapshot();
                var installedFrame = recoveredTick ? ((CardinalReorientation.Recovered) reorientation).frame()
                        : ((CardinalReorientation.Installed) reorientation).frame();
                boolean installedGrounded = recoveredTick ? ((CardinalReorientation.Recovered) reorientation).onGround()
                        : ((CardinalReorientation.Installed) reorientation).onGround();
                accepted = installedSnapshot;
                frame = installedFrame;
                referenceAdopted = true;
                travel.referenceCommitSnapshot(installedSnapshot, correctionRevision, travel.ownerCurrent);
                if (desiredSource == PlayerBodyReference.Source.MANUAL && pendingManual != null) {
                    referenceState = referenceState.withManual(Optional.of(pendingManual),
                            PlayerBodyReference.Transition.MANUAL_DEFERRED);
                }
                PlayerBodyReference.Transition transition = switch (desiredSource) {
                    case ENVIRONMENT -> environmentOverridesManual
                            ? PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE : PlayerBodyReference.Transition.STABLE;
                    case MANUAL -> manualWasDeferred ? PlayerBodyReference.Transition.MANUAL_RESUMED
                            : PlayerBodyReference.Transition.STABLE;
                    case NATURAL -> PlayerBodyReference.Transition.STABLE;
                    case SAFE_NATIVE -> PlayerBodyReference.Transition.MANUAL_CLEARED;
                };
                referenceState = referenceState.accepted(effective, transition);
                travel.adoptedReference(requested, installedGrounded);
                travel.bodyReference(referenceState);
                if (recoveredTick) {
                    if (standing.presence() == StandingConstraints.ConstraintPresence.PRESENT && requested == frame)
                        travel.acceptedReference(standing.targetReference().orElseThrow());
                    else if (standing.presence() == StandingConstraints.ConstraintPresence.ABSENT && requested == CardinalFrame.Y_POS)
                        travel.withoutReference();
                    return new Applied(accepted);
                }
            } else {
                var rejection = (CardinalReorientation.Rejected) reorientation;
                if (rejection.cause().isPresent())
                    return new Unavailable("reference reorientation: " + rejection.detail(), rejection.cause());
                var reason = bodyReferenceReason(rejection.reason());
                PlayerBodyReference.Transition transition = switch (desiredSource) {
                    case ENVIRONMENT -> PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED;
                    case MANUAL -> PlayerBodyReference.Transition.MANUAL_ADOPTION_BLOCKED;
                    case NATURAL -> PlayerBodyReference.Transition.NATURAL_ADOPTION_BLOCKED;
                    case SAFE_NATIVE -> PlayerBodyReference.Transition.SAFE_NATIVE_ADOPTION_BLOCKED;
                };
                referenceState = referenceState.blocked(transition, requested, reason);
                travel.bodyReference(referenceState);
            }
        }
        if (standing.presence() == StandingConstraints.ConstraintPresence.PRESENT && requested == frame)
            travel.acceptedReference(standing.targetReference().orElseThrow());
        else if (standing.presence() == StandingConstraints.ConstraintPresence.ABSENT && requested == CardinalFrame.Y_POS)
            travel.withoutReference();
        // The corridor feet anchor may have changed with the frame; apply it once for this tick.
        if (boostCarrier) applyCorridor(budget, budgetTick, actor.posY);
        Optional<CardinalFrame> requestedReference = desiredSource == PlayerBodyReference.Source.SAFE_NATIVE
                ? Optional.empty() : Optional.of(requested);
        boolean standingReference = nativeGravity
                || requestedReference.isPresent() && requestedReference.orElseThrow() == frame;
        boolean freeDrift = sample.bindingActive() && !propulsion && !flying && !fluid && !gliding && !nativeGravity
                && standing.presence() == StandingConstraints.ConstraintPresence.ABSENT
                && desiredSource == PlayerBodyReference.Source.SAFE_NATIVE && !actor.isOnLadder();
        boolean nearFootLocomotion = false;
        Vec3 velocity = new Vec3(v.x(), v.y(), v.z());
        var equipmentFlight = flying ? EquipmentFlightSampling.sample(actor, controls) : null;
        if (free != null) free.requireCurrent();
        travel.recordEquipmentFlight(accepted, tick, equipmentFlight);
        var flightTickMotion = flying ? equipmentFlight.beforeTravel(
                new Vec3d(velocity.x() * dt, velocity.y() * dt, velocity.z() * dt), actor.onGround, controls) : null;
        if (!propulsion && !freeDrift) velocity = new Vec3(
                Math.abs(velocity.x() * dt) < .003D ? 0 : velocity.x(),
                Math.abs(velocity.y() * dt) < .003D ? 0 : velocity.y(),
                Math.abs(velocity.z() * dt) < .003D ? 0 : velocity.z());
        if (propulsion) velocity = velocity.add(acceleration.scale(dt));
        float friction = 0.91F;
        double flightVerticalMotion = 0;
        double glideHorizontalSpeed = Math.hypot(v.x() * dt, v.z() * dt);
        travel.jumpTicks = CardinalTravelKernel.tickJumpCooldown(travel.jumpTicks, controls.jump());
        if (controls.jump() && !propulsion && !flying && !fluid && !gliding)
            travel.jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.REJECTED;
        if (!propulsion) {
            if (freeDrift) {
                // Read-only sweep: its endpoint is never installed and cannot grant onGround or jump authority.
                var footRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -ZERO_G_FOOT_REACH - 1.0e-7, 0)), MoverType.PLAYER, false, false, 0);
                var foot = resolveCardinal(world, actor, accepted, footRequest, free);
                if (foot.worldFailure().isPresent()) {
                    var failure = foot.worldFailure().orElseThrow();
                    return new Unavailable("zero-G foot: " + failure.reason(), failure.cause());
                }
                if (!(foot.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return unavailable("zero-G foot calculation incomplete");
                boolean withinReach = contact.flags().onGround()
                        && -contact.nativeResolvedLocalDelta().y() <= ZERO_G_FOOT_REACH;
                nearFootLocomotion = withinReach;
                if (withinReach) {
                    Vec3d input = frame.toLocal(controls.translation());
                    Vec3d localVelocity = frame.toLocal(new Vec3d(velocity.x(), velocity.y(), velocity.z()));
                    Vec3 tangent = new Vec3(localVelocity.x(), 0, localVelocity.z());
                    boolean sprinting = controls.sprint() && !controls.sneak()
                            && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6);
                    double targetSpeed = (sprinting ? ZERO_G_SPRINT_SPEED : ZERO_G_WALK_SPEED)
                            * (controls.sneak() ? 0.3 : 1);
                    Vec3 desired = new Vec3(input.x(), 0, input.z()).scale(targetSpeed);
                    Vec3 drive = desired.sub(tangent);
                    double change = drive.norm();
                    // Contact-limited friction may brake toward the selected gait, never snap existing speed.
                    if (change > ZERO_G_WALK_ACCELERATION * dt) drive = drive.scale(ZERO_G_WALK_ACCELERATION * dt / change);
                    Vec3d worldDrive = frame.toWorld(new Vec3d(drive.x(), drive.y(), drive.z()));
                    velocity = velocity.add(new Vec3(worldDrive.x(), worldDrive.y(), worldDrive.z()));
                }
                // A retained native flag is not sustained support in a region with no standing constraint.
                actor.onGround = false;
            }
            // Native jumping consumes the preceding committed movement's contact. A support
            // observation at the new horizontal endpoint must not erase that input opportunity
            // when the previous movement has just crossed a descending step edge.
            boolean jumpGrounded = standingReference && accepted.contacts()
                    .map(PlayerPhysicalSnapshot.Contacts::onGround).orElse(false);
            if (actor.onGround && standingReference) {
                var supportRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, actor.onGround, false, 0);
                var support = resolveCardinal(world, actor, accepted, supportRequest, free);
                if (support.worldFailure().isPresent()) {
                    var failure = support.worldFailure().orElseThrow();
                    return new Unavailable("support: " + failure.reason(), failure.cause());
                }
                if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return unavailable("support calculation incomplete");
                actor.onGround = contact.flags().onGround();
            }
            if (actor.onGround && !fluid) {
                if(free==null) {
                    BlockPos support=frame==CardinalFrame.Y_POS
                            ?new BlockPos(actor.posX,actor.getEntityBoundingBox().minY-1,actor.posZ)
                            :CardinalMoveFinalizer.physicalSupport(frame,actor.getEntityBoundingBox());
                    var state=world.getBlockState(support);
                    friction*=state.getBlock().getSlipperiness(state,world,support,actor);
                } else {
                    var connection=actor.connection;
                    var startBounds=actor.getEntityBoundingBox();
                    double startX=actor.posX,startY=actor.posY,startZ=actor.posZ;
                    final PlayerPhysicalSnapshot materialSnapshot=accepted;
                    Runnable proof=()->requireCardinalOwnerIdentity(actor,materialSnapshot,travel,correctionRevision,world,tick,connection);
                    Object materialToken=new Object();
                    try(var scope=CardinalNativeBoundary.open(materialToken,actor,
                            free.resources().environmentObservationWork(),()->{
                                proof.run();
                                if(!actor.getEntityBoundingBox().equals(startBounds) || actor.posX!=startX || actor.posY!=startY || actor.posZ!=startZ)
                                    throw new CardinalNativeBoundary.AuthorityLost("support callback changed accepted geometry");
                            })) {
                        CardinalNativeBoundary.charge(actor,"support coordinate",EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                        BlockPos support=frame==CardinalFrame.Y_POS
                                ?new BlockPos(actor.posX,actor.getEntityBoundingBox().minY-1,actor.posZ)
                                :CardinalMoveFinalizer.physicalSupport(frame,actor.getEntityBoundingBox());
                        var state=CardinalNativeBoundary.dispatch(actor,"support getBlockState",()->world.getBlockState(support));
                        friction*=CardinalNativeBoundary.dispatch(actor,"getSlipperiness",
                                ()->state.getBlock().getSlipperiness(state,world,support,actor));
                    } catch(CardinalNativeBoundary.Abort abort) {
                        if(!abort.belongsTo(materialToken) || abort.kind()!=CardinalNativeBoundary.AbortKind.AUTHORITY_LOST)throw abort;
                        return unavailable("material callback changed movement authority");
                    }
                }
            }
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            applyOwnedPoseFlags(actor, free, controls.sneak(), false);
            actor.setSprinting(controls.sprint() && !controls.sneak()
                    && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6));
            if (flying) {
                actor.motionX = Math.abs(flightTickMotion.x()) < .003D ? 0 : flightTickMotion.x();
                actor.motionY = Math.abs(flightTickMotion.y()) < .003D ? 0 : flightTickMotion.y();
                actor.motionZ = Math.abs(flightTickMotion.z()) < .003D ? 0 : flightTickMotion.z();
                // Native SP input precedes the EntityPlayer flight wrapper; opposite vertical keys cancel.
                actor.motionY = CardinalTravelKernel.flightInput(actor.motionY, controls.sneak(),
                        controls.jump(), equipmentFlight.flySpeed());
                flightVerticalMotion = actor.motionY;
                actor.fallDistance = 0;
            }
            float fluidAcceleration = 0.02F;
            if (fluid) {
                friction = water ? actor.getWaterSlowDown() : 0.5F;
                if (water) {
                    float depth = Math.min(3, EnchantmentHelper.getDepthStriderModifier(actor));
                    if (!actor.onGround) depth *= 0.5F;
                    if (depth > 0) {
                        friction += (0.54600006F - friction) * depth / 3.0F;
                        fluidAcceleration += (actor.getAIMoveSpeed() - fluidAcceleration) * depth / 3.0F;
                    }
                }
                if (controls.jump()) {
                    if (water) actor.handleJumpWater();
                    else actor.handleJumpLava();
                }
            }
            if (!gliding && (standingReference || fluid || flying)) {
                if (!flying && !fluid && controls.jump())
                    travel.jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.REJECTED;
                if (!flying && !fluid && controls.jump() && jumpGrounded && travel.jumpTicks == 0) {
                    travel.jumpTicks = 10;
                    final var jumpPlacement = accepted.placement();
                    final var jumpFrame = frame;
                    EquipmentFlightSampling.withInput(actor, controls, () -> {
                        if (jumpFrame == CardinalFrame.Y_POS) actor.jump();
                        else jumpCardinal(actor, jumpPlacement, controls);
                        return null;
                    });
                    Vec3d transport = accepted.placement().transport();
                    if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor)
                            || actor.world != world || actor.isDead || world.getTotalWorldTime() != tick
                            || actor.posX != transport.x() || actor.posY != transport.y() || actor.posZ != transport.z())
                        return unavailable("jump callback changed actor placement/lifetime");
                    travel.jumpOutcome = PlayerPhysicsProtocol.JumpOutcome.APPLIED;
                }
                float coefficient = fluid ? fluidAcceleration : actor.onGround ? actor.getAIMoveSpeed() * (0.16277136F / (friction * friction * friction))
                        : flying ? equipmentFlight.flySpeed() * (actor.isSprinting() ? 2 : 1) : actor.jumpMovementFactor;
                float yaw = actor.rotationYaw * 0.017453292F;
                float sin = MathHelper.sin(yaw), cos = MathHelper.cos(yaw);
                double keyboardTravelGain = controls.keyboardTravelGain();
                if (frame == CardinalFrame.Y_POS) {
                    float worldX = (float) (controls.translation().x() * keyboardTravelGain);
                    float worldZ = (float) (controls.translation().z() * keyboardTravelGain);
                    float strafe = worldX * cos + worldZ * sin;
                    float forward = worldZ * cos - worldX * sin;
                    if (controls.sneak() && !flying) {
                        strafe = (float) (strafe * 0.3D);
                        forward = (float) (forward * 0.3D);
                    }
                    strafe *= 0.98F;
                    forward *= 0.98F;
                    actor.moveRelative(strafe, 0, forward, coefficient);
                } else if (!fluid) {
                    var input = new CardinalTravelKernel.Input(frame, controls.translation(),
                            keyboardTravelGain, controls.sneak(), controls.jump(), actor.rotationYaw);
                    Vec3d driven = CardinalTravelKernel.accelerate(
                            new Vec3d(actor.motionX, actor.motionY, actor.motionZ), input, coefficient, flying);
                    actor.motionX = driven.x();
                    actor.motionY = driven.y();
                    actor.motionZ = driven.z();
                } else {
                    Vec3d input = frame.toLocal(controls.translation());
                    float right = (float) (input.x() * keyboardTravelGain);
                    float forward = (float) (input.z() * keyboardTravelGain);
                    if (controls.sneak() && !flying) {
                        right = (float) (right * 0.3D);
                        forward = (float) (forward * 0.3D);
                    }
                    right *= 0.98F;
                    forward *= 0.98F;
                    float lengthSquared = right * right + forward * forward;
                    if (lengthSquared >= 1.0e-4F) {
                        float scale = coefficient / Math.max(1, MathHelper.sqrt(lengthSquared));
                        Vec3d drive = frame.toWorld(new Vec3d(right * scale, 0, forward * scale));
                        actor.motionX += drive.x();
                        actor.motionY += drive.y();
                        actor.motionZ += drive.z();
                    }
                }
            }
            if (!gliding && !fluid && actor.isOnLadder()) {
                actor.motionX = MathHelper.clamp(actor.motionX, -(double) 0.15F, (double) 0.15F);
                actor.motionZ = MathHelper.clamp(actor.motionZ, -(double) 0.15F, (double) 0.15F);
                actor.motionY = Math.max(actor.motionY, -0.15D);
                actor.fallDistance = 0;
                if (actor.isSneaking() && actor.motionY < 0) actor.motionY = 0;
            }
            velocity = new Vec3(actor.motionX / dt, actor.motionY / dt, actor.motionZ / dt);
            if (gliding) velocity = glide(actor, velocity, acceleration, dt);
        }
        if (propulsion) {
            double driftAcceleration = UnigravConfig.sixDofDriftAcceleration;
            double driftLimit = UnigravConfig.sixDofDriftSpeedLimit;
            if (!Double.isFinite(driftAcceleration) || driftAcceleration < 0 || !Double.isFinite(driftLimit) || driftLimit < 0)
                throw new IllegalArgumentException("Invalid drift tuning");
            Vec3d translation = controls.translation();
            Vec3 driftDelta = new Vec3(translation.x(), translation.y(), translation.z()).scale(driftAcceleration * dt);
            velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, driftDelta, driftLimit));
            if (controls.boost()) {
                double target = RemoteBoostCorridor.propulsionTarget(BoostGearProfile.speedMps(controls.boostGear()),
                        budget.ceilingMps(), RemoteBoostCorridor.isRestricted(actor.posY));
                Vec3d forward = controls.forward();
                Vec3 boostDelta = new Vec3(forward.x(), forward.y(), forward.z())
                        .scale(BoostGearProfile.accelerationMps2(controls.boostGear()) * dt);
                // A zero carrier is no permission to accelerate, unlike the configurable unlimited drift setting.
                if (target > 0) velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, boostDelta, target));
            }
        }
        return moveCardinal(actor, accepted, controls, budget, budgetTick, boostCarrier, travel, free,
                world, tick, correctionRevision, stepSuccessorRevision, sample, standing, frame,
                velocity, acceleration, friction, flightVerticalMotion, glideHorizontalSpeed,
                freeDrift, standingReference, nearFootLocomotion, referenceAdopted,
                propulsion, flying, water, lava, gliding, null);
    }

    /** One native collision/tail transaction; preintegrated candidates must not integrate again. */
    private Result moveCardinal(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
            boolean boostCarrier, TravelState travel, FreeOwnerTransaction free,
            WorldServer world, long tick, long correctionRevision, long stepSuccessorRevision,
            PlayerEnvironmentSampler.Sample sample, StandingConstraints.Resolution standing, CardinalFrame frame,
            Vec3 velocity, Vec3 acceleration, float friction, double flightVerticalMotion, double glideHorizontalSpeed,
            boolean freeDrift, boolean standingReference, boolean nearFootLocomotion, boolean referenceAdopted,
            boolean propulsion, boolean flying, boolean water, boolean lava, boolean gliding,
            NativeCardinalBodyProfile.TargetOccupied retainedSource) {
        double dt = GravityEntityBinder.TICK_SECONDS;
        boolean preintegrated = retainedSource != null;
        boolean fluid = water || lava;
        var dimensions = accepted.placement().shape().dimensions();
        double speed = Math.hypot(Math.hypot(velocity.x(), velocity.y()), velocity.z());
        if (!Double.isFinite(speed)) return unavailable("non-finite integrated velocity");
        if (speed > budget.ceilingMps()) velocity = velocity.scale(budget.ceilingMps() / speed);
        Vec3d start = accepted.placement().transport();
        var admission = budget.prepareClipped(Math.incrementExact(budget.lastSequence()), budgetTick,
                new Vec3(start.x(), start.y(), start.z()).add(velocity.scale(dt)));
        if (!admission.accepted()) return unavailable("budget: " + admission.failure());
        var boundary = new CommitBoundary(actor, accepted, budget, admission, travel, correctionRevision,
                stepSuccessorRevision, standing, frame, free == null ? null : free.pendingCardinalPose());
        try {
            Vec3 delta = admission.destination().sub(admission.start());
            var request = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                    new Vec3d(delta.x(), delta.y(), delta.z()), MoverType.PLAYER,
                    actor.onGround, actor.isSneaking(), actor.stepHeight);
            var observation = resolveCardinal(world, actor, accepted, request, free);
            if (observation.worldFailure().isPresent()) {
                var failure = observation.worldFailure().orElseThrow();
                return new Unavailable("collision world: " + failure.reason(), failure.cause());
            }
            if (!(observation.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved resolved))
                return unavailable("collision calculation incomplete");
            if (retainedSource != null) {
                free.requireCurrent();
                retainedSource.requireCardinalTailProfileCurrent(free);
                free.requireCurrent();
            }
            // The finalizer's native block callbacks must see this step's velocity, not stale MP motion.
            Vec3 oldMotion = new Vec3(actor.motionX, actor.motionY, actor.motionZ);
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            var preparation = free==null?CardinalMoveFinalizer.preparePhysical(actor,request,resolved)
                    :CardinalMoveFinalizer.preparePhysical(actor,request,resolved,
                    free.resources().environmentObservationWork(),boundary::requireOwnerIdentity,boundary);
            if (!(preparation instanceof CardinalMoveFinalizer.Ready ready)) {
                actor.motionX = oldMotion.x(); actor.motionY = oldMotion.y(); actor.motionZ = oldMotion.z();
                return unavailable("movement tail unavailable");
            }
            boundary.finalizer = ready;
            if (!(ready.commit(travel::clearReferenceCommitSnapshot) instanceof CardinalMoveFinalizer.Applied applied))
                return boundary.defer(unavailable("movement tail invalidated"));
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor))
                return boundary.defer(unavailable("movement authority changed during movement tail"));
            if (actor.world != world || actor.isDead || world.getTotalWorldTime() != tick
                    || actor.connection == null || actor.connection.player != actor
                    || actor.dimension != accepted.scope().dimension()) return boundary.defer(unavailable("movement tail changed lifetime"));
            if (actor.width != dimensions.width() || actor.height != dimensions.height())
                return boundary.defer(unavailable("movement tail changed physical dimensions"));
            if (retainedSource != null) retainedSource.requireCardinalTailProfileCurrent(free);
            Vec3d position = applied.actualTransport();
            if (!budget.commitNative(admission, new Vec3(position.x(), position.y(), position.z())))
                throw new IllegalStateException("Physical movement lost its reserved budget ticket");
            boundary.budgetCommitted = true;
            if (boostCarrier) applyCorridor(budget, budgetTick, position.y());
            if (!actor.onGround && sample.bindingActive() && standingReference
                    && !propulsion && !flying && !fluid && !gliding && frame.toLocal(request.worldDelta()).y() == 0) {
                // A tangent-only move has no vertical collision flag. Re-observe its ACTUAL endpoint;
                // never inherit pre-move support across an edge or treat force cancellation as free flight.
                var installed = committedSnapshot(actor, accepted, applied.actualBounds(), position, false,
                        stepSuccessorRevision, free == null ? null : free.pendingCardinalPose());
                var supportRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, false, false, 0);
                var support = resolveCardinal(world, actor, installed, supportRequest, free);
                if (support.worldFailure().isPresent()) {
                    var failure = support.worldFailure().orElseThrow();
                    return boundary.defer(new Unavailable("endpoint support: " + failure.reason(), failure.cause()));
                }
                if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return boundary.defer(unavailable("endpoint support calculation incomplete"));
                if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor))
                    return boundary.defer(unavailable("endpoint support changed movement authority"));
                // The sweep is a sensor only. A nonzero gap is not contact and its endpoint is never installed.
                actor.onGround = contact.flags().onGround() && contact.nativeResolvedLocalDelta().y() == 0;
            }
            boundary.consumingLanding=actor.onGround;
            try(var nativeScope=free==null?null:CardinalNativeBoundary.open(boundary,actor,
                    free.resources().environmentObservationWork(),boundary::requireInstalledPhase)) {
                if(frame==CardinalFrame.Y_POS) {
                    CardinalNativeBoundary.charge(actor,"handleFalling entry",EnvironmentObservationWork.Unit.DISPATCH);
                    actor.handleFalling(position.y()-start.y(),actor.onGround);
                    CardinalNativeBoundary.check(actor,"handleFalling return");
                } else {
                    double fallStep=resolved.nativeResolvedLocalDelta().y();
                    if(actor.onGround) {
                        if(actor.fallDistance>0) {
                            CardinalNativeBoundary.charge(actor,"landing support coordinate",EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                            Vec3d foot=accepted.placement().physicalFeet().orElseThrow()
                                    .add(position.subtract(start)).subtract(frame.up().multiply(.2));
                            BlockPos support=new BlockPos(foot.x(),foot.y(),foot.z());
                            var state=CardinalNativeBoundary.dispatch(actor,"landing getBlockState",()->world.getBlockState(support));
                            CardinalNativeBoundary.dispatch(actor,"onFallenUpon",()->state.getBlock().onFallenUpon(world,support,actor,actor.fallDistance));
                            CardinalNativeBoundary.check(actor,"landing fallDistance write");actor.fallDistance=0;
                        }
                    } else if(fallStep<0) {
                        CardinalNativeBoundary.check(actor,"fallDistance write");actor.fallDistance-=(float)fallStep;
                    }
                }
                if(nativeScope!=null)nativeScope.check("landing return");
            }
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || !actor.isEntityAlive()
                    || PlayerKinematics.requiresNative(actor) || actor.world != world
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("fall callback changed physical authority"));
            if (retainedSource != null) retainedSource.requireCardinalTailProfileCurrent(free);
            var previousContacts = accepted.contacts();
            Vec3d impactVelocity = frame.toLocal(new Vec3d(velocity.x(), velocity.y(), velocity.z()));
            if (!previousContacts.map(PlayerPhysicalSnapshot.Contacts::onGround).orElse(false)
                    && actor.onGround && impactVelocity.y() < 0 && !actor.isInWater())
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_FALL, -impactVelocity.y());
            else if (actor.collidedVertically && impactVelocity.y() > 0)
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_CEILING, impactVelocity.y());
            if (!gliding && actor.collidedHorizontally
                    && !previousContacts.map(PlayerPhysicalSnapshot.Contacts::horizontal).orElse(false))
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_WALL,
                        Math.hypot(impactVelocity.x(), impactVelocity.z()));
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || !actor.isEntityAlive()
                    || PlayerKinematics.requiresNative(actor) || actor.world != world
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("impact callback changed physical authority"));
            if (retainedSource != null) retainedSource.requireCardinalTailProfileCurrent(free);
            Vec3d actualVelocity = frame.toLocal(applied.observedMotionBlocksPerTick());
            Vec3d localAcceleration = frame.toLocal(new Vec3d(acceleration.x(), acceleration.y(), acceleration.z()));
            boolean drift = freeDrift || sample.personalZeroG() && actor.capabilities.isCreativeMode;
            double horizontalDrag = drift || gliding ? 1 : friction;
            double verticalDrag = drift || gliding ? 1 : water ? (double) 0.8F : lava ? 0.5D : 0.9800000190734863D;
            // Ordinary travel applies environmental acceleration after the one native movement, before drag.
            double forceScale = propulsion || gliding ? 0 : dt * dt;
            double verticalMotion = !propulsion && !gliding && !fluid && actor.collidedHorizontally && actor.isOnLadder()
                    ? 0.2D : actualVelocity.y();
            Vec3d postDrag;
            if (preintegrated && !flying) {
                postDrag = applied.observedMotionBlocksPerTick();
            } else if (preintegrated) {
                // Match the existing Free native-flight tail, including its one horizontal drag.
                postDrag = CardinalTravelKernel.finish(applied.observedMotionBlocksPerTick(), CardinalFrame.Y_POS,
                        new Vec3d(acceleration.x(), acceleration.y(), acceleration.z()), dt, .91F, actor.motionY);
            } else if (!propulsion && !gliding && !fluid && !drift) {
                postDrag = CardinalTravelKernel.finish(applied.observedMotionBlocksPerTick(), frame,
                        new Vec3d(acceleration.x(), acceleration.y(), acceleration.z()), dt, friction,
                        verticalMotion);
            } else {
                // Fluid travel damps first; acceleration contains UG buoyancy and the eligible native term.
                postDrag = frame.toWorld(new Vec3d(
                        actualVelocity.x() * horizontalDrag + localAcceleration.x() * forceScale * (fluid ? 1 : horizontalDrag),
                        verticalMotion * verticalDrag + localAcceleration.y() * forceScale * (fluid ? 1 : verticalDrag),
                        actualVelocity.z() * horizontalDrag + localAcceleration.z() * forceScale * (fluid ? 1 : horizontalDrag)));
            }
            actor.motionX = postDrag.x();
            actor.motionY = postDrag.y();
            actor.motionZ = postDrag.z();
            // EntityPlayer.travel restores the pre-move vertical flight velocity even after a collision.
            if (flying) actor.motionY = flightVerticalMotion * 0.6D;
            if (gliding) {
                if (actor.collidedHorizontally) {
                    float damage = (float) ((glideHorizontalSpeed - Math.hypot(actor.motionX, actor.motionZ)) * 10.0D - 3.0D);
                    if (damage > 0) {
                        actor.playSound(actor.getFallSound((int) damage), 1.0F, 1.0F);
                        actor.attackEntityFrom(DamageSource.FLY_INTO_WALL, damage);
                    }
                }
                if (actor.onGround) applyOwnedPoseFlags(actor, free, actor.isSneaking(), true);
            }
            if (flying) {
                actor.fallDistance = 0;
                applyOwnedPoseFlags(actor, free, actor.isSneaking(), true);
            }
            if(fluid && actor.collidedHorizontally) {
                try(var nativeScope=free==null?null:CardinalNativeBoundary.open(boundary,actor,
                        free.resources().environmentObservationWork(),boundary::requireInstalledPhase)) {
                    var escapeBox=actor.getEntityBoundingBox().offset(actor.motionX,
                            actor.motionY+(double).6F-actor.posY+start.y(),actor.motionZ);
                    var escapeBounds=new Aabb(new Vec3d(escapeBox.minX,escapeBox.minY,escapeBox.minZ),
                            new Vec3d(escapeBox.maxX,escapeBox.maxY,escapeBox.maxZ));
                    var escape=free==null
                            ?WorldCollisionCandidates.capture(world,actor,escapeBounds,CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT,SCAN)
                            :WorldCollisionCandidates.capture(world,actor,escapeBounds,CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT,SCAN,
                                    free.resources().environmentObservationWork());
                    CardinalNativeBoundary.check(actor,"fluid collision capture return");
                    if(escape instanceof WorldCollisionCandidates.Unavailable failure)
                        return boundary.defer(new Unavailable("fluid escape: "+failure.reason(),failure.cause()));
                    if(((WorldCollisionCandidates.Snapshot)escape).boxes().isEmpty()
                            && !CardinalNativeBoundary.dispatch(actor,"containsAnyLiquid",()->world.containsAnyLiquid(escapeBox))) {
                        CardinalNativeBoundary.check(actor,"fluid escape motion write");actor.motionY=(double).3F;
                    }
                    if(nativeScope!=null)nativeScope.check("fluid escape return");
                }
            }
            double nativeSpeed = Math.hypot(Math.hypot(actor.motionX, actor.motionY), actor.motionZ);
            if (!Double.isFinite(nativeSpeed)) throw new IllegalStateException("Non-finite movement-tail velocity");
            double nativeLimit = budget.ceilingMps() * dt;
            if (nativeSpeed > nativeLimit) {
                double scale = nativeLimit / nativeSpeed;
                actor.motionX *= scale;
                actor.motionY *= scale;
                actor.motionZ *= scale;
            }
            actor.addMovementStat(position.x() - start.x(), position.y() - start.y(), position.z() - start.z());
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor)
                    || actor.world != world || actor.isDead
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("movement statistics changed physical authority"));
            PlayerPhysicalSnapshot installedSnapshot = committedSnapshot(actor, accepted, applied.actualBounds(),
                    applied.actualTransport(), nearFootLocomotion, stepSuccessorRevision, free == null ? null : free.pendingCardinalPose());
            boolean placementChanged = referenceAdopted
                    || !accepted.placement().transport().equals(installedSnapshot.placement().transport())
                    || !accepted.placement().bounds().equals(installedSnapshot.placement().bounds());
            if (placementChanged) {
                Optional<CommittedFailure> observationFailure = refreshCommittedStanding(actor, installedSnapshot,
                        sample.personalZeroG(), travel, standing, frame, tick, correctionRevision,
                        free == null ? null : free.pendingCardinalPose(), free, budget.ceilingMps());
                if (observationFailure.isPresent()) return observationFailure.orElseThrow();
            } else {
                acceptStandingResolution(travel, standing, sample.evidence(), frame, actor.onGround);
                travel.predictionSeed(installedSnapshot, predictionSeedFromSample(actor, installedSnapshot, sample,
                        travel, budget.ceilingMps()));
            }
            if (retainedSource != null) retainedSource.requireCardinalTailProfileCurrent(free);
            return new Applied(installedSnapshot);
        } catch (RuntimeException failure) {
            if(failure instanceof CardinalNativeBoundary.Abort abort) {
                if(!abort.belongsTo(boundary))throw abort;
                if(abort.kind()==CardinalNativeBoundary.AbortKind.AUTHORITY_LOST)
                    return boundary.defer(unavailable("native callback changed movement authority"));
            }
            try {
                var recovered = boundary.recover();
                if (recovered.isPresent()) {
                    clearCommittedSupport(travel, standing.nextState(), frame);
                    return new CommittedFailure(recovered.orElseThrow(), failure);
                }
            } catch (RuntimeException invalidSnapshot) {
                if (invalidSnapshot != failure) failure.addSuppressed(invalidSnapshot);
            }
            throw failure;
        } finally {
            // A consumed ticket is a no-op here; a failed query/tail retires it without refunding credit.
            budget.abort(admission);
        }
    }

    private static void applyOwnedPoseFlags(EntityPlayerMP actor, FreeOwnerTransaction transaction,
            boolean sneaking, boolean clearGliding) {
        var pending = transaction == null ? null : transaction.pendingCardinalPose();
        if (pending != null) pending.applyOwnedFlags(actor, sneaking, clearGliding);
        else {
            actor.setSneaking(sneaking);
            if (clearGliding) actor.clearElytraFlying();
        }
    }

    private static PlayerPhysicalSnapshot committedSnapshot(EntityPlayerMP actor,
            PlayerPhysicalSnapshot accepted, AxisAlignedBB box, Vec3d position, boolean nearFootLocomotion,
            long revision, PlayerPhysicsServer.PendingCardinalPose pending) {
        var oldShape = (PlayerBodyShape.Cardinal) accepted.placement().shape();
        // A blocked native pose's target eye belongs to its request, not this retained collider.
        float observedEye = actor.getEyeHeight();
        var dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, observedEye);
        if (pending != null) {
            if (!pending.allowsEye(actor, accepted.scope(), accepted.placement(), Float.floatToIntBits(observedEye))
                    || actor.width != oldShape.dimensions().width() || actor.height != oldShape.dimensions().height())
                throw new IllegalStateException("Pending native pose changed during Cardinal endpoint observation: "
                        + pending.diagnostic(actor));
            dimensions = oldShape.dimensions();
        }
        var shape = new PlayerBodyShape.Cardinal(oldShape.frame(), dimensions);
        var placement = PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(box.minX, box.minY, box.minZ),
                new Vec3d(box.maxX, box.maxY, box.maxZ)), position, accepted.placement().attitude(), shape);
        double dt = GravityEntityBinder.TICK_SECONDS;
        var snapshot = new PlayerPhysicalSnapshot(accepted.scope(), revision,
                placement, new Vec3d(actor.motionX / dt, actor.motionY / dt, actor.motionZ / dt),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                        actor.collidedVertically, actor.fallDistance, nearFootLocomotion)));
        if (pending != null && !pending.matchesSnapshot(actor, snapshot, observedEye))
            throw new IllegalStateException("Pending native pose lost its exact Cardinal endpoint anchor");
        return snapshot;
    }

    private static void requireCardinalOwnerIdentity(EntityPlayerMP actor,PlayerPhysicalSnapshot accepted,
            TravelState travel,long correction,World world,long tick,NetHandlerPlayServer connection) {
        var dimensions=accepted.placement().shape().dimensions();
        if(!travel.ownerCurrent.getAsBoolean() || correction!=travel.nativeCorrectionRevision
                || actor.world!=world || world.getTotalWorldTime()!=tick || actor.isDead
                || actor.connection!=connection || connection==null || connection.player!=actor
                || actor.dimension!=accepted.scope().dimension()
                || actor.width!=dimensions.width() || actor.height!=dimensions.height())
            throw new CardinalNativeBoundary.AuthorityLost("Cardinal operation owner lifetime changed");
    }
    /** One admission's geometry/debit journal, independent of whether native callbacks return. */
    private static final class CommitBoundary {
        private final EntityPlayerMP actor;
        private final PlayerPhysicalSnapshot accepted;
        private final BoostMovementBudget budget;
        private final BoostMovementBudget.Admission admission;
        private final TravelState travel;
        private final long correctionRevision;
        private final long stepSuccessorRevision;
        private final StandingConstraints.Resolution standing;
        private final CardinalFrame acceptedFrame;
        private final PlayerPhysicsServer.PendingCardinalPose pending;
        private final World world;
        private final NetHandlerPlayServer connection;
        private final long tick;
        private CardinalMoveFinalizer.Ready finalizer;
        private boolean budgetCommitted;
        private boolean consumingLanding;

        private CommitBoundary(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, BoostMovementBudget budget,
                BoostMovementBudget.Admission admission, TravelState travel, long correctionRevision,
                long stepSuccessorRevision, StandingConstraints.Resolution standing, CardinalFrame acceptedFrame,
                PlayerPhysicsServer.PendingCardinalPose pending) {
            this.actor = actor;
            this.accepted = accepted;
            this.budget = budget;
            this.admission = admission;
            this.travel = travel;
            this.correctionRevision = correctionRevision;
            this.stepSuccessorRevision = stepSuccessorRevision;
            this.standing = standing;
            this.acceptedFrame = acceptedFrame;
            this.pending = pending;
            world = actor.world;
            connection = actor.connection;
            tick = world.getTotalWorldTime();
        }

        private void requireInstalledPhase() {
            requireOwnerIdentity();
            var journal=finalizer==null?Optional.<CardinalMoveFinalizer.Installation>empty():finalizer.installation();
            if(journal.isEmpty())throw new CardinalNativeBoundary.AuthorityLost("Cardinal endpoint journal missing");
            var installed=journal.orElseThrow();
            if(actor.posX!=installed.transport().x() || actor.posY!=installed.transport().y()
                    || actor.posZ!=installed.transport().z() || !actor.getEntityBoundingBox().equals(installed.bounds()))
                throw new CardinalNativeBoundary.AuthorityLost("Cardinal endpoint geometry changed");
        }
        private void requireOwnerIdentity() {
            requireCardinalOwnerIdentity(actor,accepted,travel,correctionRevision,world,tick,connection);
            if(pending!=null && !pending.current())
                throw new CardinalNativeBoundary.AuthorityLost("pending Cardinal pose changed");
        }
        private Result defer(Unavailable failure) {
            var recovered = recover();
            recovered.ifPresent(snapshot -> clearCommittedSupport(travel, standing.nextState(), acceptedFrame));
            return recovered.<Result>map(snapshot -> new CommittedDeferred(snapshot, failure)).orElse(failure);
        }

        private Optional<PlayerPhysicalSnapshot> recover() {
            var installation = finalizer == null ? Optional.<CardinalMoveFinalizer.Installation>empty()
                    : finalizer.installation();
            if (installation.isEmpty()) return Optional.empty();
            var installed = installation.orElseThrow();
            var dimensions = accepted.placement().shape().dimensions();
            // A record of geometry installation is not authority to adopt a callback's relocation.
            if (!travel.ownerCurrent.getAsBoolean() || correctionRevision != travel.nativeCorrectionRevision || actor.world != world
                    || world.getTotalWorldTime() != tick || !actor.isEntityAlive()
                    || actor.connection != connection || connection == null || connection.player != actor
                    || actor.dimension != accepted.scope().dimension() || PlayerKinematics.requiresNative(actor)
                    || actor.width != dimensions.width() || actor.height != dimensions.height()
                    || actor.posX != installed.transport().x() || actor.posY != installed.transport().y()
                    || actor.posZ != installed.transport().z() || !actor.getEntityBoundingBox().equals(installed.bounds()))
                return Optional.empty();
            if (pending != null) {
                float observedEye = actor.getEyeHeight();
                if (!pending.allowsEye(actor, accepted.scope(), accepted.placement(), Float.floatToIntBits(observedEye)))
                    return Optional.empty();
            }
            // Native predicates and profile getters above may reenter. Fence again before accounting or motion writes.
            if (actor.world != world || actor.connection != connection || actor.isDead
                    || world.getTotalWorldTime() != tick || correctionRevision != travel.nativeCorrectionRevision
                    || actor.posX != installed.transport().x() || actor.posY != installed.transport().y()
                    || actor.posZ != installed.transport().z() || !actor.getEntityBoundingBox().equals(installed.bounds())
                    || !travel.ownerCurrent.getAsBoolean() || pending != null && !pending.current())
                return Optional.empty();
            double speed = Math.hypot(Math.hypot(actor.motionX, actor.motionY), actor.motionZ);
            if (!Double.isFinite(speed)) return Optional.empty();
            if (!budgetCommitted) {
                var position = installed.transport();
                if (!budget.commitNative(admission, new Vec3(position.x(), position.y(), position.z())))
                    throw new IllegalStateException("Installed movement lost its reserved budget ticket");
                budgetCommitted = true;
            }
            double limit = budget.ceilingMps() * GravityEntityBinder.TICK_SECONDS;
            if (speed > limit) {
                double scale = limit / speed;
                actor.motionX *= scale; actor.motionY *= scale; actor.motionZ *= scale;
            }
            // This landing was attempted; never retry potentially irreversible damage callbacks.
            if (consumingLanding) actor.fallDistance = 0;
            return Optional.of(committedSnapshot(actor, accepted, installed.bounds(), installed.transport(), false,
                    stepSuccessorRevision, pending));
        }
    }

    private static Unavailable unavailable(String reason) {
        return new Unavailable(reason, Optional.empty());
    }

    private static String vectorText(Vec3d value) {
        return "(" + value.x() + "," + value.y() + "," + value.z() + ")";
    }

    private static FreeMovementStopDiagnostic movementStopDiagnostic(CapsuleMovementResolver.Stop stop) {
        var query = stop.limitingQuery();
        var boundary = query.boundary().orElse(null);
        String prefixFraction = boundary == null ? "NONE"
                : boundary.prefix().map(prefix -> Double.toString(prefix.endFraction())).orElse("NONE");
        String detail = "stage=free-movement-" + (stop.fullyBlocked() ? "blocked" : "partial")
                + ";reason=" + stop.reason().replace('\r', ' ').replace('\n', ' ')
                + ";ordinal=" + query.ordinal()
                + ";boundaryKind=" + (boundary == null ? "NONE" : boundary.kind().name())
                + ";observationPrefixEndFraction=" + prefixFraction
                + ";sourcePrefixEndFraction=" + query.sourcePrefixEndFraction()
                        .map(value -> Double.toString(value)).orElse("NONE")
                + ";observationToSourceFractionScale=" + query.sourceParameterScale()
                + ";sourceStart=" + vectorText(query.tube().start().placement().center())
                + ";sourceDelta=" + vectorText(query.tube().delta())
                + ";observationStart=" + vectorText(query.observationTube().start().placement().center())
                + ";observationDelta=" + vectorText(query.observationTube().delta())
                + ";obstacle=min=" + vectorText(query.obstacle().min())
                + ";max=" + vectorText(query.obstacle().max());
        return new FreeMovementStopDiagnostic(stop.fullyBlocked()
                ? FreeMovementStopKind.FULLY_BLOCKED : FreeMovementStopKind.PARTIAL, detail);
    }

    CardinalPredictionSeed observePredictionSeed(EntityPlayerMP actor, PlayerPhysicalSnapshot snapshot,
            Optional<Boolean> personalZeroG, TravelState travel, double ceilingMps, FreeTickResources resources,
            Runnable ownerCurrent, PlayerPhysicsServer.PendingCardinalPose pending) {
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal))
            return new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_SHAPE);
        try {
            ownerCurrent.run();
            var observed = environment.sample(actor, snapshot, personalZeroG, pending,
                    resources.environmentObservationWork());
            ownerCurrent.run();
            if (observed instanceof PlayerEnvironmentSampler.Unavailable failure)
                return new CardinalPredictionSeed.Unavailable(
                        failure.reason() == PlayerEnvironmentSampler.Reason.SOURCE_FAILED
                                ? CardinalPredictionSeed.Reason.SAMPLING_FAILED : CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE,
                        failure.cause());
            return predictionSeedFromSample(actor, snapshot, (PlayerEnvironmentSampler.Sample) observed, travel, ceilingMps);
        } catch (RuntimeException cause) {
            return new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.SAMPLING_FAILED, Optional.of(cause));
        }
    }

    private static CardinalPredictionSeed predictionSeedFromSample(EntityPlayerMP actor,
            PlayerPhysicalSnapshot snapshot, PlayerEnvironmentSampler.Sample sample, TravelState travel, double ceilingMps) {
        try {
            if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
                return new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_SHAPE);
            if (actor.isInWater() || actor.isInLava() || actor.isElytraFlying() || PlayerKinematics.requiresNative(actor))
                return new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE);
            var state = travel.standingState();
            if (sample.qualifiedBootEquipment() || state.bootPhase() != StandingConstraints.BootPhase.NONE || state.bootMemory().isPresent()
                    || !sample.evidence().bootCandidates().isEmpty())
                return new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.ACTIVE_BOOTS);
            if (!sample.physical().placement().equals(snapshot.placement())
                    || !sample.physical().scope().equals(snapshot.scope())
                    || sample.worldTick() != actor.world.getTotalWorldTime())
                return new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.ACTOR_CHANGED, Optional.empty());
            // No edge is consumed and this resolution never commits standing state.
            var standing = StandingConstraints.resolve(state, new StandingConstraints.Request(
                    sample.worldTick(), false, sample.evidence()));
            if (standing.presence() == StandingConstraints.ConstraintPresence.UNAVAILABLE)
                return new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, Optional.empty());
            // Mirror the actual dry-step reference priority without committing resolver state.
            Optional<CardinalFrame> reference = standing.targetReference()
                    .filter(target -> hasEnvironmentReference(sample.evidence(), target));
            if (reference.isEmpty()) reference = travel.pendingManualIntent().map(PlayerBodyReference.ManualIntent::frame);
            if (reference.isEmpty()) reference = travel.cardinalReference()
                    .flatMap(PlayerBodyReference.State::manualIntent).map(PlayerBodyReference.ManualIntent::frame);
            if (reference.isEmpty()) reference = standing.targetReference();
            boolean nativeGravity = !sample.personalZeroG() && !travel.nativeNoGravity
                    && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty()
                    && !actor.capabilities.isFlying;
            boolean standingReference = nativeGravity || reference.filter(frame -> frame == cardinal.frame()).isPresent();
            sample.requireCurrent();
            Vec3 force = sample.environmentAcceleration();
            return new CardinalPredictionSeed.Ready(snapshot.revision(), sample.worldTick(), cardinal.frame(),
                    new Vec3d(force.x(), force.y(), force.z()), sample.bindingMode(), sample.bindingActive(),
                    sample.personalZeroG(), travel.nativeNoGravity,
                    standingReference, ceilingMps);
        } catch (RuntimeException cause) {
            return new CardinalPredictionSeed.Unavailable(CardinalPredictionSeed.Reason.SAMPLING_FAILED, Optional.of(cause));
        }
    }

    /** A committed position change must replace pre-move support with a fresh endpoint observation. */
    private Optional<CommittedFailure> refreshCommittedStanding(EntityPlayerMP actor,
            PlayerPhysicalSnapshot installed, boolean personalZeroG, TravelState travel,
            StandingConstraints.Resolution preMove, CardinalFrame acceptedFrame, long worldTick,
            long correctionRevision, PlayerPhysicsServer.PendingCardinalPose pending, FreeOwnerTransaction transaction, double ceilingMps) {
        try {
            travel.standingState(preMove.nextState());
            var observed = environment.sample(actor, installed, Optional.of(personalZeroG), pending,
                    transaction == null ? null : transaction.resources().environmentObservationWork());
            if (observed instanceof PlayerEnvironmentSampler.Unavailable unavailable) {
                clearCommittedSupport(travel, preMove.nextState(), acceptedFrame);
                return Optional.of(new CommittedFailure(installed,
                        new PostCommitObservationUnavailable(unavailable)));
            }
            var sample = (PlayerEnvironmentSampler.Sample) observed;
            if (sample.worldTick() != worldTick || actor.world.getTotalWorldTime() != worldTick
                    || correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor)) {
                clearCommittedSupport(travel, preMove.nextState(), acceptedFrame);
                return Optional.of(new CommittedFailure(installed, new IllegalStateException(
                        "post-commit observation crossed the owner tick or physical authority fence")));
            }
            if (travel.mobility.blocksCapture()) {
                clearCommittedSupport(travel, preMove.nextState(), acceptedFrame);
                return Optional.empty();
            }
            StandingConstraints.State postInput = currentStandingState(travel, sample.evidence(), worldTick, acceptedFrame);
            travel.standingState(postInput);
            StandingConstraints.Resolution postMove = StandingConstraints.resolve(postInput,
                    new StandingConstraints.Request(worldTick, false, sample.evidence()));
            if (postMove.presence() == StandingConstraints.ConstraintPresence.UNAVAILABLE) {
                clearCommittedSupport(travel, postInput, acceptedFrame);
                return Optional.of(new CommittedFailure(installed, new IllegalStateException(
                        "post-commit standing resolution was unavailable")));
            }
            acceptStandingResolution(travel, postMove, sample.evidence(), acceptedFrame,
                    installed.contacts().map(PlayerPhysicalSnapshot.Contacts::onGround).orElse(false));
            travel.predictionSeed(installed, predictionSeedFromSample(actor, installed, sample, travel,
                    ceilingMps));
            return Optional.empty();
        } catch (RuntimeException failure) {
            clearCommittedSupport(travel, preMove.nextState(), acceptedFrame);
            return Optional.of(new CommittedFailure(installed,
                    new IllegalStateException("post-commit environment refresh threw", failure)));
        }
    }

    /** A failed endpoint read may retain boot memory, but never its pre-move support fact. */
    private static void clearCommittedSupport(TravelState travel, StandingConstraints.State state,
                                              CardinalFrame acceptedFrame) {
        travel.standingState(new StandingConstraints.State(state.bootPhase(), state.bootMemory(),
                Optional.of(acceptedFrame), Optional.empty(), state.protectionUntilServerTick()));
    }

    /** Uses the already sampled, source-separated evidence; never infers ownership from the force sum. */
    private static boolean hasEnvironmentReference(StandingConstraints.Evidence evidence, CardinalFrame frame) {
        return evidence.floorGravityStrengths().strength(frame) > 0.0
                || evidence.floorMagneticStrengths().strength(frame) > 0.0
                || evidence.bootCandidates().stream().anyMatch(candidate -> candidate.isEffective()
                && candidate.surface().frame() == frame);
    }

    private static StandingConstraints.Evidence freeReferenceEvidence(
            PlayerEnvironmentSampler.FreeSample sample, FreeOwnerTransaction transaction, boolean nativeGravity) {
        FreeStandingObservation observation = sample.observation();
        Optional<BootSurfaceQuery.Measurements> measurements = observation.boots().measurements();
        Optional<StandingConstraints.Surface> actualSupport = observation.actualSupport(measurements.orElse(null));
        if (measurements.isPresent())
            return BootStandingEvidence.withBootQueryAndSupport(sample.referenceFields(nativeGravity), measurements.orElseThrow(),
                    actualSupport, transaction.accepted(), transaction.tick(), true,
                    transaction.postprocessing()::charge);
        return BootStandingEvidence.withNativeSupport(sample.referenceFields(nativeGravity), actualSupport);
    }

    /** Current qualification identities, independent of historical boot memory and distance scores. */
    record FreeStandingQualification(Map<CardinalFrame, Set<StandingConstraints.ConstraintSource>> faces,
                                     Set<StandingConstraints.BootMemory> boots) {
        FreeStandingQualification {
            faces = Map.copyOf(faces);
            boots = Set.copyOf(boots);
        }
        static FreeStandingQualification from(StandingConstraints.Evidence evidence) {
            return new FreeStandingQualification(evidence.faceEvidence().entrySet().stream()
                    .filter(entry -> entry.getValue().preSumReferenceScore() > 0)
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().sources())),
                    evidence.bootCandidates().stream().filter(StandingConstraints.BootCandidate::isEffective)
                            .map(candidate -> new StandingConstraints.BootMemory(candidate.surface(), candidate.tier()))
                            .collect(Collectors.toUnmodifiableSet()));
        }
    }

    static final class FreeStandingProposal {
        private final TravelState travel;
        private final StandingConstraints.State sourceState;
        private final boolean sourceProtected;
        private final StandingConstraints.Resolution resolution;
        private final boolean nextProtected;
        private final FreeStandingQualification qualification;
        private final Optional<FreeReferenceModePrepared.Selection> selection;

        private FreeStandingProposal(TravelState travel, StandingConstraints.State sourceState, boolean sourceProtected,
                StandingConstraints.Resolution resolution, boolean nextProtected,
                FreeStandingQualification qualification, Optional<FreeReferenceModePrepared.Selection> selection) {
            this.travel = travel; this.sourceState = sourceState; this.sourceProtected = sourceProtected;
            this.resolution = resolution; this.nextProtected = nextProtected; this.selection = selection;
            this.qualification = Objects.requireNonNull(qualification, "qualification");
        }
        Optional<FreeReferenceModePrepared.Selection> selection() { return selection; }
        boolean current() {
            return travel.standingState() == sourceState && travel.protectedBootSourceInvalidated() == sourceProtected
                    && travel.ownerCurrent.getAsBoolean() && travel.standingState() == sourceState
                    && travel.protectedBootSourceInvalidated() == sourceProtected;
        }
        boolean ownerReferenceCurrent(PlayerBodyReference.FreeReference reference) {
            return travel.ownerReference() == reference && current() && travel.ownerReference() == reference;
        }
        boolean preserves(FreeStandingProposal previous) {
            if (previous == null || travel != previous.travel || sourceState != previous.sourceState
                    || sourceProtected != previous.sourceProtected || selection.isEmpty() || previous.selection.isEmpty()) return false;
            var now = selection.orElseThrow(); var before = previous.selection.orElseThrow();
            return now.effective().source() == before.effective().source()
                    && now.effective().frame() == before.effective().frame()
                    && qualification.equals(previous.qualification);
        }
        void publish(FreeOwnerTransaction transaction) {
            transaction.requireCurrent();
            if (!current()) throw new IllegalStateException("Free standing proposal changed before successful publication");
            transaction.requireCurrent();
            travel.protectedBootSourceInvalidated(nextProtected);
            travel.standingState(resolution.nextState());
        }
    }

    private static FreeStandingProposal prepareFreeStanding(
            PlayerBodyReference.FreeReference free, TravelState travel, StandingConstraints.Evidence evidence,
            Optional<PlayerAttitudeRequest> attitudeRequest, EntityPlayerMP actor, long tick) {
        Objects.requireNonNull(free, "free");
        Objects.requireNonNull(travel, "travel");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(attitudeRequest, "attitudeRequest");
        if (evidence.availability() != StandingConstraints.Availability.AVAILABLE)
            throw new IllegalStateException("Free owner reference evidence is unavailable");

        CardinalFrame previousFrame = free.previousEffective().map(PlayerBodyReference.Effective::frame)
                .or(() -> free.pendingTarget().map(PlayerBodyReference.PendingCardinalTarget::frame))
                .or(() -> free.manualIntent().map(PlayerBodyReference.ManualIntent::frame))
                .orElse(CardinalFrame.Y_POS);
        StandingInput input = normalizeStandingState(travel.standingState(),
                travel.protectedBootSourceInvalidated(), evidence, tick, previousFrame);
        var sourceState = travel.standingState();
        boolean sourceProtected = travel.protectedBootSourceInvalidated();
        StandingConstraints.Resolution standing = StandingConstraints.resolve(input.state(),
                new StandingConstraints.Request(tick, false, evidence));
        if (standing.presence() == StandingConstraints.ConstraintPresence.UNAVAILABLE)
            throw new IllegalStateException("Free owner standing reference resolution is unavailable");

        Optional<CardinalFrame> environmentFrame = standing.targetReference()
                .filter(frame -> hasEnvironmentReference(evidence, frame));
        PlayerBodyReference.ManualIntent savedManual = travel.pendingManualIntent()
                .orElseGet(() -> free.manualIntent().orElse(null));
        PlayerBodyReference.Source source;
        CardinalFrame frame;
        if (environmentFrame.isPresent()) {
            source = PlayerBodyReference.Source.ENVIRONMENT;
            frame = environmentFrame.orElseThrow();
        } else if (savedManual != null) {
            source = PlayerBodyReference.Source.MANUAL;
            frame = savedManual.frame();
        } else if (attitudeRequest.isPresent()) {
            // Q controls attitude about the accepted physical center, even when axis-aligned.
            // Native restoration has its own authority; Q alone cannot create a standing reference.
            return new FreeStandingProposal(travel, sourceState, sourceProtected, standing,
                    input.protectedBootSourceInvalidated(), FreeStandingQualification.from(evidence), Optional.empty());
        } else if (standing.targetReference().isPresent()) {
            source = PlayerBodyReference.Source.NATURAL;
            frame = standing.targetReference().orElseThrow();
        } else {
            // Historical frame hints are not current authority to install Cardinal geometry.
            return new FreeStandingProposal(travel, sourceState, sourceProtected, standing,
                    input.protectedBootSourceInvalidated(), FreeStandingQualification.from(evidence), Optional.empty());
        }

        PlayerBodyReference.Effective previous = free.previousEffective().orElse(null);
        Vec3d heading = source == PlayerBodyReference.Source.MANUAL
                ? savedManual.tangentHeading()
                : previous == null ? PlayerBodyReference.initialHeading(frame, actor.rotationYaw)
                : previous.frame() == frame ? previous.tangentHeading()
                : PlayerBodyReference.transportHeading(previous.frame(), frame, previous.tangentHeading());
        var effective = new PlayerBodyReference.Effective(source, frame, heading);
        Optional<PlayerBodyReference.ManualIntent> manual = Optional.ofNullable(savedManual);
        PlayerBodyReference.Transition transition = switch (source) {
            case ENVIRONMENT -> manual.isPresent()
                    ? PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE : PlayerBodyReference.Transition.STABLE;
            case MANUAL -> previous != null && previous.source() == PlayerBodyReference.Source.ENVIRONMENT
                    ? PlayerBodyReference.Transition.MANUAL_RESUMED : PlayerBodyReference.Transition.STABLE;
            case NATURAL -> PlayerBodyReference.Transition.STABLE;
            case SAFE_NATIVE -> PlayerBodyReference.Transition.MANUAL_CLEARED;
        };
        var installed = new PlayerBodyReference.State(manual, effective, transition, Optional.empty(), Optional.empty());
        long requestId = source == PlayerBodyReference.Source.MANUAL ? savedManual.requestId()
                : attitudeRequest.map(PlayerAttitudeRequest::inputSequence)
                        .orElseGet(() -> free.pendingTarget().map(PlayerBodyReference.PendingCardinalTarget::requestId)
                                .orElse(0L));
        return new FreeStandingProposal(travel, sourceState, sourceProtected, standing,
                input.protectedBootSourceInvalidated(), FreeStandingQualification.from(evidence),
                Optional.of(new FreeReferenceModePrepared.Selection(effective, manual, installed, requestId)));
    }

    private static void recordPendingManualFailure(TravelState travel, PlayerPhysicalSnapshot accepted,
            PlayerBodyReference.FreeReference source, FreeReferenceModePrepared.Selection selection,
            PlayerPhysicsProtocol.BodyReferenceReason reason, Optional<RuntimeException> cause) {
        var request = travel.pendingManualIntent().orElse(null);
        if (request == null || selection.effective().source() != PlayerBodyReference.Source.MANUAL
                || selection.requestId() != request.requestId()
                || source.manualIntent().filter(saved -> saved.requestId() == request.requestId()).isPresent()
                || !travel.ownerCurrent.getAsBoolean()) return;
        var terminalReason = cause.isEmpty() && (reason == PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED
                || reason == PlayerPhysicsProtocol.BodyReferenceReason.PATH_BLOCKED)
                ? reason : PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE;
        travel.pendingManualFailure = new PendingManualFailure(travel, accepted, request,
                travel.nativeCorrectionRevision, travel.ownerCurrent, terminalReason, cause);
    }

    private static void retainFreePendingTarget(TravelState travel, PlayerBodyReference.FreeReference source,
            FreeReferenceModePrepared.Selection selection, PlayerPhysicsProtocol.BodyReferenceReason reason) {
        if (travel.ownerReference() != source) return;
        var effective = selection.effective();
        var pending = new PlayerBodyReference.PendingCardinalTarget(selection.requestId(), effective.source(),
                effective.frame(), effective.tangentHeading(), reason);
        travel.ownerReference(new PlayerBodyReference.FreeReference(source.manualIntent(), Optional.of(pending),
                source.previousEffective()));
    }

    private static void acceptStandingResolution(TravelState travel, StandingConstraints.Resolution resolution,
                                                 StandingConstraints.Evidence evidence,
                                                 CardinalFrame acceptedFrame, boolean onGround) {
        if (resolution.presence() == StandingConstraints.ConstraintPresence.UNAVAILABLE) return;
        StandingConstraints.State next = resolution.nextState();
        if (next.bootPhase() == StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED) {
            if (next.bootMemory().isPresent()
                    && !hasEffectiveBoot(evidence, next.bootMemory().orElseThrow().surface()))
                travel.protectedBootSourceInvalidated(true);
        } else {
            travel.protectedBootSourceInvalidated(false);
        }
        if (next.bootPhase() != StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED
                && onGround && next.bootMemory().isPresent() && next.actualSupport().isPresent()
                && hasEffectiveBoot(evidence, next.bootMemory().orElseThrow().surface())
                && next.bootMemory().orElseThrow().surface().equals(next.actualSupport().orElseThrow())
                && next.bootMemory().orElseThrow().surface().frame() == acceptedFrame) {
            travel.standingState(StandingConstraints.State.attached(next.bootMemory().orElseThrow(),
                    acceptedFrame, next.actualSupport().orElseThrow()));
            return;
        }
        travel.standingState(new StandingConstraints.State(next.bootPhase(), next.bootMemory(),
                Optional.of(acceptedFrame), next.actualSupport(), next.protectionUntilServerTick()));
    }

    private record StandingInput(StandingConstraints.State state, boolean protectedBootSourceInvalidated) { }

    private static StandingConstraints.State currentStandingState(TravelState travel,
            StandingConstraints.Evidence evidence, long tick, CardinalFrame acceptedFrame) {
        StandingInput input = normalizeStandingState(travel.standingState(), travel.protectedBootSourceInvalidated(),
                evidence, tick, acceptedFrame);
        travel.protectedBootSourceInvalidated(input.protectedBootSourceInvalidated());
        return input.state();
    }

    /** Pure normalization is shared by Q qualification and the real step; only the real step commits its flag. */
    private static StandingInput normalizeStandingState(StandingConstraints.State previous,
            boolean protectedBootSourceInvalidated, StandingConstraints.Evidence evidence,
            long tick, CardinalFrame acceptedFrame) {
        if (evidence.availability() != StandingConstraints.Availability.AVAILABLE
                || evidence.bootCandidates().stream().anyMatch(StandingConstraints.BootCandidate::hasUnresolvedStrength))
            return new StandingInput(previous, protectedBootSourceInvalidated);
        if (previous.bootPhase() == StandingConstraints.BootPhase.JUMP_RELEASE_PROTECTED) {
            boolean invalidated = protectedBootSourceInvalidated
                    || previous.bootMemory().filter(memory -> hasEffectiveBoot(evidence, memory.surface())).isEmpty();
            if (tick < previous.protectionUntilServerTick()) return new StandingInput(previous, invalidated);
            if (!invalidated) return new StandingInput(previous, false);
            return new StandingInput(clearedStandingState(previous, evidence, acceptedFrame), false);
        }
        if (previous.bootMemory().isPresent()
                && !hasEffectiveBoot(evidence, previous.bootMemory().orElseThrow().surface()))
            return new StandingInput(clearedStandingState(previous, evidence, acceptedFrame),
                    protectedBootSourceInvalidated);
        if (previous.bootPhase() == StandingConstraints.BootPhase.ATTACHED
                && (!previous.acceptedReference().filter(frame -> frame == acceptedFrame).isPresent()
                || !previous.actualSupport().equals(previous.bootMemory().map(StandingConstraints.BootMemory::surface))
                || !evidence.actualSupport().equals(previous.bootMemory().map(StandingConstraints.BootMemory::surface))))
            return new StandingInput(clearedStandingState(previous, evidence, acceptedFrame),
                    protectedBootSourceInvalidated);
        return new StandingInput(previous, protectedBootSourceInvalidated);
    }

    private static StandingConstraints.State clearedStandingState(StandingConstraints.State previous,
            StandingConstraints.Evidence evidence, CardinalFrame acceptedFrame) {
        return new StandingConstraints.State(StandingConstraints.BootPhase.NONE, Optional.empty(),
                Optional.of(acceptedFrame), evidence.actualSupport(), -1L);
    }

    private static boolean hasEffectiveBoot(StandingConstraints.Evidence evidence, StandingConstraints.Surface surface) {
        return evidence.bootCandidates().stream().anyMatch(candidate -> candidate.isEffective()
                && candidate.surface().equals(surface));
    }

    private record SupportProbe(boolean confirmed, Optional<String> unavailableReason,
                                Optional<RuntimeException> cause) {
        private SupportProbe {
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            Objects.requireNonNull(cause, "cause");
            if (confirmed && unavailableReason.isPresent())
                throw new IllegalArgumentException("confirmed support cannot also be unavailable");
        }

        private static SupportProbe unconfirmed() { return new SupportProbe(false, Optional.empty(), Optional.empty()); }
    }

    private static WorldCollisionCandidates.CardinalObservation resolveCardinal(WorldServer world,
            EntityPlayerMP actor, PlayerPhysicalSnapshot snapshot, CardinalMovementResolver.Request request,
            FreeOwnerTransaction transaction) {
        return transaction == null ? WorldCollisionCandidates.resolveCardinal(world, actor, snapshot, request, SCAN, MOVE)
                : WorldCollisionCandidates.resolveCardinal(world, actor, snapshot, request, SCAN, MOVE,
                        transaction.resources().environmentObservationWork());
    }

    private static SupportProbe confirmsAttachedSupport(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            CardinalFrame acceptedFrame, StandingConstraints.State state, StandingConstraints.Evidence evidence,
            WorldServer world, FreeOwnerTransaction ownerTransaction) {
        if (state.bootPhase() != StandingConstraints.BootPhase.ATTACHED || state.bootMemory().isEmpty()
                || !actor.onGround || accepted.contacts().filter(PlayerPhysicalSnapshot.Contacts::onGround).isEmpty())
            return SupportProbe.unconfirmed();
        StandingConstraints.Surface surface = state.bootMemory().orElseThrow().surface();
        if (surface.frame() != acceptedFrame
                || !state.acceptedReference().filter(frame -> frame == acceptedFrame).isPresent()
                || !state.actualSupport().filter(surface::equals).isPresent()
                || !evidence.actualSupport().filter(surface::equals).isPresent()
                || !hasEffectiveBoot(evidence, surface)) return SupportProbe.unconfirmed();
        var supportRequest = new CardinalMovementResolver.Request(acceptedFrame, actor.getEntityBoundingBox(),
                acceptedFrame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, true, false, 0);
        var support = resolveCardinal(world, actor, accepted, supportRequest, ownerTransaction);
        if (support.worldFailure().isPresent()) {
            var failure = support.worldFailure().orElseThrow();
            return new SupportProbe(false, Optional.of(failure.reason().name()), failure.cause());
        }
        if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
            return new SupportProbe(false, Optional.of("calculation incomplete"), Optional.empty());
        return new SupportProbe(contact.flags().onGround() && contact.nativeResolvedLocalDelta().y() == 0.0,
                Optional.empty(), Optional.empty());
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

    /** Native jump policy in the accepted physical frame; callbacks always observe WORLD motion. */
    private static void jumpCardinal(EntityPlayerMP actor, PlayerBodyPlacement placement,
                                     PlayerPhysicsProtocol.Controls controls) {
        CardinalFrame frame = ((PlayerBodyShape.Cardinal) placement.shape()).frame();
        double launch = actor.getJumpUpwardsMotion();
        if (actor.isPotionActive(MobEffects.JUMP_BOOST))
            launch += (actor.getActivePotionEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1) * .1F;
        Vec3d motion = CardinalTravelKernel.jump(new Vec3d(actor.motionX, actor.motionY, actor.motionZ),
                frame, launch, actor.isSprinting(), controls.forward(), placement.attitude().basisZ());
        actor.motionX = motion.x();
        actor.motionY = motion.y();
        actor.motionZ = motion.z();
        actor.isAirBorne = true;
        ForgeHooks.onLivingJump(actor);
        actor.addStat(StatList.JUMP);
        actor.addExhaustion(actor.isSprinting() ? .2F : .05F);
    }

    /** Native elytra aerodynamics in blocks/tick, with the one sampled environment replacing fixed -0.08. */
    private static Vec3 glide(EntityPlayerMP actor, Vec3 velocity, Vec3 acceleration, double dt) {
        double x = velocity.x() * dt, y = velocity.y() * dt, z = velocity.z() * dt;
        if (y > -0.5D) actor.fallDistance = 1.0F;
        var look = actor.getLookVec();
        float pitch = actor.rotationPitch * (float) (Math.PI / 180.0D);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double horizontal = Math.sqrt(x * x + z * z);
        float lift = MathHelper.cos(pitch);
        lift = (float) (lift * (lift * Math.min(1.0D, look.length() / 0.4D)));
        x += acceleration.x() * dt * dt;
        y += acceleration.y() * dt * dt + lift * 0.06D;
        z += acceleration.z() * dt * dt;
        if (y < 0 && lookHorizontal > 0) {
            double dive = y * -0.1D * lift;
            y += dive;
            x += look.x * dive / lookHorizontal;
            z += look.z * dive / lookHorizontal;
        }
        if (pitch < 0 && lookHorizontal > 0) {
            double climb = horizontal * -MathHelper.sin(pitch) * 0.04D;
            y += climb * 3.2D;
            x -= look.x * climb / lookHorizontal;
            z -= look.z * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (look.x / lookHorizontal * horizontal - x) * 0.1D;
            z += (look.z / lookHorizontal * horizontal - z) * 0.1D;
        }
        return new Vec3(x * (double) 0.99F / dt, y * (double) 0.98F / dt, z * (double) 0.99F / dt);
    }

    /** Preserve the transferred envelope and its existing credit; G-off does not erase reentry decay. */
    private static void applyCorridor(BoostMovementBudget budget, long tick, double feetY) {
        if (RemoteBoostCorridor.isRestricted(feetY)) {
            if (budget.ceilingMps() > RemoteBoostCorridor.RESTRICTED_CEILING_MPS && !budget.snapshot().decayActive())
                budget.beginDecay(tick, RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                        RemoteBoostCorridor.REENTRY_DECELERATION_MPS2);
        } else {
            double configured = UnigravConfig.remoteBoostMaximumSpeed;
            double safety = UnigravConfig.maxEntitySpeed;
            if (!Double.isFinite(configured) || configured < 0 || !Double.isFinite(safety) || safety < 0)
                throw new IllegalArgumentException("Invalid propulsion carrier configuration");
            double grant = Math.min(BoostGearProfile.maximumSpeedMps(), configured);
            if (safety > 0) grant = Math.min(grant, safety);
            if (budget.ceilingMps() != grant || budget.snapshot().decayActive()) budget.changeCeiling(tick, grant);
        }
    }
}
