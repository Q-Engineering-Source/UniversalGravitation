package wi.unigrav.core.player;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.GravityMode;

/** Independent physical-owner messages; positions are metres and velocities m/s. */
public final class PlayerPhysicsProtocol {
    public static final String CHANNEL = "ugmotion1";
    // V21 preserves the wire shape but serverSlot now counts consumed INPUT slots, not world ticks.
    public static final int VERSION = 22;
    // The V11 maximum includes a boundary; its Anchored phase adds present(1), tag(1), revision(8), bootstrap(16).
    // V14 combines the V13 request marker with accepted mobility state (present byte + 22 bytes).
    // V15 adds one canonical transfer-presence byte. V16 reuses that union for TP completion controls.
    // Transfer/control-only frames are shorter than the maximal STATE; the actual maximum fixture verifies this cap.
    // V22 adds kind/reference-frame bytes to the profiled Free body for swim/crawl semantics.
    public static final int MAX_BYTES = 631;
    private static final int CARDINAL_PLACEMENT_TAG = 0;
    private static final int FREE_CAPSULE_PLACEMENT_TAG = 1;
    private static final int PROFILED_FREE_CAPSULE_PLACEMENT_TAG = 2;
    private static final int PRONE_FREE_CAPSULE_PLACEMENT_TAG = 3;
    private PlayerPhysicsProtocol() {}
    public enum Kind { OFFER, APPLY, OWNED, INPUT, STATE, NATIVE, RECOVER, NATIVE_MOTION, NATIVE_ACK, NATIVE_MOTION_ACK, NATIVE_CORRECTION_COMPLETE, NATIVE_CORRECTION_ACK }
    public record OperationId(long nonce, long epoch, long serial, BoostProtocol.Operation operation) {
        public OperationId {
            Objects.requireNonNull(operation, "operation");
            if (epoch < 0 || serial < 0) throw new IllegalArgumentException("Negative operation identity");
        }
        static OperationId from(BoostProtocol.Packet packet) {
            return new OperationId(packet.nonce(), packet.epoch(), packet.sequence(), packet.operation());
        }
    }
    public record ApplicationId(OperationId state, Optional<OperationId> lastVelocityOperation) {
        public ApplicationId {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(lastVelocityOperation, "lastVelocityOperation");
            lastVelocityOperation.ifPresent(last -> {
                if (last.nonce() != state.nonce() || last.operation() == BoostProtocol.Operation.NONE
                        || last.epoch() > state.epoch()
                        || (last.epoch() == state.epoch() && last.serial() > state.serial())) {
                    throw new IllegalArgumentException("Applied operation identity/order mismatch");
                }
            });
            if (state.operation() != BoostProtocol.Operation.NONE
                    && !lastVelocityOperation.equals(Optional.of(state))) {
                throw new IllegalArgumentException("Current operation has not been applied");
            }
        }
        public static ApplicationId from(BoostProtocol.StateApplication receipt) {
            Objects.requireNonNull(receipt, "receipt");
            return new ApplicationId(OperationId.from(receipt.state()),
                    Optional.ofNullable(receipt.lastVelocityOperation()).map(OperationId::from));
        }
    }
    /** Translation and camera forward are WORLD vectors; the client samples both from the same UIE frame. */
    public record Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                           boolean sprint, boolean boost, int boostGear, double keyboardTravelGain,
                           BodyReferenceIntent bodyReferenceIntent, Optional<Rotation3d> desiredBodyAttitude,
                           PlayerMobilityIntent.Input mobility) {
        public Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                        boolean sprint, boolean boost, int boostGear, double keyboardTravelGain,
                        BodyReferenceIntent bodyReferenceIntent, Optional<Rotation3d> desiredBodyAttitude) {
            this(translation, forward, jump, sneak, sprint, boost, boostGear, keyboardTravelGain,
                    bodyReferenceIntent, desiredBodyAttitude, PlayerMobilityIntent.Input.idle());
        }
        public Controls withMobility(PlayerMobilityIntent.Input input) {
            return new Controls(translation, forward, jump, sneak, sprint, boost, boostGear,
                    keyboardTravelGain, bodyReferenceIntent, desiredBodyAttitude, input);
        }
        public Controls withJump(boolean nextJump) {
            return new Controls(translation, forward, nextJump, sneak, sprint, boost, boostGear,
                    keyboardTravelGain, bodyReferenceIntent, desiredBodyAttitude, mobility);
        }
        public Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                        boolean sprint, boolean boost, int boostGear, double keyboardTravelGain,
                        BodyReferenceIntent bodyReferenceIntent) {
            this(translation, forward, jump, sneak, sprint, boost, boostGear, keyboardTravelGain,
                    bodyReferenceIntent, Optional.empty());
        }
        /** Existing world/analog callers carry no keyboard-only travel magnitude. */
        public Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                        boolean sprint, boolean boost, int boostGear) {
            this(translation, forward, jump, sneak, sprint, boost, boostGear, 1.0,
                    BodyReferenceIntent.none());
        }

        public Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                        boolean sprint, boolean boost, int boostGear, double keyboardTravelGain) {
            this(translation, forward, jump, sneak, sprint, boost, boostGear, keyboardTravelGain,
                    BodyReferenceIntent.none());
        }

        public Controls {
            requireFinite(translation);
            requireFinite(forward);
            Objects.requireNonNull(bodyReferenceIntent, "bodyReferenceIntent");
            Objects.requireNonNull(desiredBodyAttitude, "desiredBodyAttitude");
            Objects.requireNonNull(mobility, "mobility");
            double magnitude = length(translation);
            if (magnitude > 1.0 + 1e-12 || Math.abs(length(forward) - 1) > 1e-12
                    || boostGear < 1 || boostGear > 9
                    || !Double.isFinite(keyboardTravelGain) || keyboardTravelGain < 1.0
                    || keyboardTravelGain > Math.sqrt(2.0)
                    || keyboardTravelGain > 1.0 && Math.abs(magnitude - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Invalid physical controls");
            }
        }
    }

    public enum BodyReferenceAction { NONE, LOCK, UNLOCK }
    public enum BodyReferenceDecision { LOCKED, DEFERRED, UNLOCKED, REJECTED }
    public enum BodyReferenceReason {
        INELIGIBLE,
        GEOMETRY_MISMATCH,
        UNSUPPORTED_SHAPE,
        ACTOR_CHANGED,
        TARGET_OCCUPIED,
        PATH_BLOCKED,
        WORLD_UNAVAILABLE,
        INSTALL_REJECTED,
        STALE_REQUEST,
        REQUEST_ID_REUSED,
        G_OFF,
        NATIVE_HANDOFF,
        NATIVE_RESTORE_PENDING,
        RESTORE_DEADLINE,
        NATIVE_CORRECTION_UNCLASSIFIED
    }

    /** A one-shot user request carried with input; retries keep the same request id and payload. */
    public record BodyReferenceIntent(long requestId, BodyReferenceAction action,
                                      Optional<CardinalFrame> frame, Optional<Vec3d> tangentHeading) {
        public BodyReferenceIntent {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(tangentHeading, "tangentHeading");
            if (action == BodyReferenceAction.NONE) {
                if (requestId != 0 || frame.isPresent() || tangentHeading.isPresent())
                    throw new IllegalArgumentException("NONE body reference intent has no request payload");
            } else if (requestId <= 0) {
                throw new IllegalArgumentException("Body reference request id must be positive");
            }
            if (action == BodyReferenceAction.LOCK) {
                CardinalFrame target = frame.orElseThrow(() -> new IllegalArgumentException("LOCK requires a frame"));
                Vec3d heading = tangentHeading.orElseThrow(() -> new IllegalArgumentException("LOCK requires a tangent heading"));
                requireFinite(heading);
                double magnitude = length(heading);
                if (Math.abs(magnitude - 1.0) > 1e-9
                        || Math.abs(heading.x() * target.up().x() + heading.y() * target.up().y()
                        + heading.z() * target.up().z()) > 1e-9) {
                    throw new IllegalArgumentException("Body reference heading must be a unit tangent of its frame");
                }
            } else if (frame.isPresent() || tangentHeading.isPresent()) {
                throw new IllegalArgumentException("Only LOCK carries a frame and tangent heading");
            }
        }

        public static BodyReferenceIntent none() {
            return new BodyReferenceIntent(0, BodyReferenceAction.NONE, Optional.empty(), Optional.empty());
        }
        public static BodyReferenceIntent lock(long requestId, CardinalFrame frame, Vec3d tangentHeading) {
            return new BodyReferenceIntent(requestId, BodyReferenceAction.LOCK,
                    Optional.of(frame), Optional.of(tangentHeading));
        }
        public static BodyReferenceIntent unlock(long requestId) {
            return new BodyReferenceIntent(requestId, BodyReferenceAction.UNLOCK, Optional.empty(), Optional.empty());
        }
    }

    /** Client proof that the STATE geometry and its decision receipt were applied together. */
    public record BodyReferenceAcknowledgement(long stateSequence, long snapshotRevision) {
        public BodyReferenceAcknowledgement {
            if (stateSequence <= 0 || snapshotRevision < 0)
                throw new IllegalArgumentException("Invalid body reference acknowledgement");
        }
    }

    /** Last request decision and the saved manual preference, independent of effective geometry. */
    public record BodyReferenceReceipt(long requestId, BodyReferenceDecision decision, long acceptedLockRequestId,
                                      Optional<CardinalFrame> acceptedFrame,
                                      Optional<Vec3d> tangentHeading,
                                      Optional<BodyReferenceReason> reason) {
        public BodyReferenceReceipt {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(acceptedFrame, "acceptedFrame");
            Objects.requireNonNull(tangentHeading, "tangentHeading");
            Objects.requireNonNull(reason, "reason");
            if (requestId <= 0 || acceptedLockRequestId < 0
                    || acceptedFrame.isPresent() != tangentHeading.isPresent()
                    || acceptedFrame.isPresent() != (acceptedLockRequestId > 0))
                throw new IllegalArgumentException("Invalid body reference receipt identity or state");
            if (decision == BodyReferenceDecision.LOCKED) {
                if (acceptedFrame.isEmpty() || acceptedLockRequestId != requestId || reason.isPresent())
                    throw new IllegalArgumentException("LOCKED receipt must identify the request that installed its saved frame");
            } else if (decision == BodyReferenceDecision.DEFERRED) {
                if (acceptedFrame.isEmpty() || acceptedLockRequestId == 0)
                    throw new IllegalArgumentException("DEFERRED receipt requires an independently identified saved manual frame");
            } else if (decision == BodyReferenceDecision.UNLOCKED) {
                if (acceptedFrame.isPresent() || acceptedLockRequestId != 0)
                    throw new IllegalArgumentException("UNLOCKED receipt cannot retain a lock");
            } else if (reason.isEmpty()) {
                throw new IllegalArgumentException("REJECTED receipt requires a reason");
            }
            tangentHeading.ifPresent(heading -> {
                requireFinite(heading);
                if (Math.abs(length(heading) - 1.0) > 1e-9
                        || Math.abs(heading.x() * acceptedFrame.orElseThrow().up().x()
                        + heading.y() * acceptedFrame.orElseThrow().up().y()
                        + heading.z() * acceptedFrame.orElseThrow().up().z()) > 1e-9) {
                    throw new IllegalArgumentException("Accepted heading must be a unit tangent of its frame");
                }
            });
        }
    }

    /** Sample identity is independent of packet sequence and server simulation slots. */
    public record PredictionBootstrap(long generation, long correctionRevision) {
        public PredictionBootstrap {
            if (generation <= 0 || correctionRevision < 0) throw new IllegalArgumentException("Invalid prediction bootstrap");
        }
    }
    public enum PredictionRequestAction { SUSPEND, REARM }
    public enum PredictionSuspensionOrigin { CLIENT_REFUSAL, SERVER_UNMAPPED_CONTROLS, SERVER_MOBILITY, SERVER_REFERENCE }
    public enum PredictionRefusalStage { SAMPLE, PREFRICTION, ENDPOINT, REPLAY }
    public record PredictionRefusal(CardinalPredictionSeed.Reason reason, PredictionRefusalStage stage) {
        public PredictionRefusal { Objects.requireNonNull(reason); Objects.requireNonNull(stage); }
    }
    /** The same transaction id binds SUSPEND acknowledgement and its sole REARM. Scope/proposal are the enclosing INPUT. */
    public record PredictionRequest(long requestId, PredictionBootstrap bootstrap, PredictionRequestAction action,
                                    Optional<PredictionRefusal> refusal, PredictionSuspensionOrigin origin) {
        public PredictionRequest(long requestId,PredictionBootstrap bootstrap,PredictionRequestAction action,PredictionRefusal refusal) {
            this(requestId,bootstrap,action,Optional.of(refusal),PredictionSuspensionOrigin.CLIENT_REFUSAL);
        }
        public PredictionRequest {
            if (requestId <= 0) throw new IllegalArgumentException("Invalid prediction request id");
            Objects.requireNonNull(bootstrap); Objects.requireNonNull(action); Objects.requireNonNull(refusal); Objects.requireNonNull(origin);
            if (refusal.isPresent() != (origin==PredictionSuspensionOrigin.CLIENT_REFUSAL)) throw new IllegalArgumentException("Suspension origin/refusal mismatch");
        }
    }
    public record PredictionSuspended(long snapshotRevision, PredictionBootstrap bootstrap, long requestId, PredictionSuspensionOrigin origin)
            implements PredictionPhase {
        public PredictionSuspended(long snapshotRevision,PredictionBootstrap bootstrap,long requestId) {
            this(snapshotRevision,bootstrap,requestId,PredictionSuspensionOrigin.CLIENT_REFUSAL);
        }
        public PredictionSuspended {
            Objects.requireNonNull(bootstrap); Objects.requireNonNull(origin);
            if (snapshotRevision < 0 || requestId <= 0) throw new IllegalArgumentException("Invalid suspension receipt");
        }
    }
    public sealed interface PredictionPhase permits AwaitingFirstResolvedSlot, Anchored, PredictionUnavailable, PredictionSuspended {
        long snapshotRevision();
    }
    public record AwaitingFirstResolvedSlot(PredictionBootstrap bootstrap, long snapshotRevision, int jumpCooldown)
            implements PredictionPhase {
        public AwaitingFirstResolvedSlot {
            Objects.requireNonNull(bootstrap);
            if (snapshotRevision < 0 || jumpCooldown < 0) throw new IllegalArgumentException("Invalid awaiting checkpoint");
        }
    }
    /** The single boundary body remains Packet.predictionBoundary; this phase only binds its bootstrap. */
    public record Anchored(PredictionBootstrap bootstrap, long snapshotRevision) implements PredictionPhase {
        public Anchored {
            Objects.requireNonNull(bootstrap);
            if (snapshotRevision < 0) throw new IllegalArgumentException("Invalid anchored checkpoint");
        }
    }
    public enum PredictionUnavailableReason { NOT_BEGUN, RETIRED, CONTEXT_CHANGED, CHECKPOINT_CHANGED, INELIGIBLE }
    public record PredictionUnavailable(long snapshotRevision, PredictionUnavailableReason reason) implements PredictionPhase {
        public PredictionUnavailable {
            Objects.requireNonNull(reason);
            if (snapshotRevision < 0) throw new IllegalArgumentException("Invalid unavailable checkpoint");
        }
    }
    public record PredictionInput(long epoch, long firstClientTick, long clientTick, long jumpEdgeId,
                                  Optional<PredictionBootstrap> bootstrap) {
        public PredictionInput(long epoch, long firstClientTick, long clientTick, long jumpEdgeId) {
            this(epoch, firstClientTick, clientTick, jumpEdgeId, Optional.empty());
        }
        public PredictionInput {
            Objects.requireNonNull(bootstrap, "bootstrap");
            if (epoch <= 0 || firstClientTick < 0 || clientTick < firstClientTick || jumpEdgeId < 0)
                throw new IllegalArgumentException("Invalid prediction input identity");
        }
    }
    public enum JumpOutcome { NONE, APPLIED, REJECTED, EXPIRED }
    public enum StepOutcome { APPLIED, DEFERRED }
    public record JumpDecision(long edgeId, long clientTick, JumpOutcome outcome) {
        public JumpDecision {
            Objects.requireNonNull(outcome, "outcome");
            if (edgeId < 0 || clientTick < 0 || (outcome == JumpOutcome.NONE ? edgeId != 0 || clientTick != 0 : edgeId == 0))
                throw new IllegalArgumentException("Invalid jump decision");
        }
        public static JumpDecision none() { return new JumpDecision(0, 0, JumpOutcome.NONE); }
    }
    /** Checkpoint after server physics; serverSlot counts consumed contiguous client INPUTs only. */
    public record PredictionBoundary(long epoch, long mappingGeneration, long anchorServerSlot,
                                     long serverSlot, long firstClientTick, long firstInputSequence,
                                     long preSnapshotRevision, long heldInputSequence, int jumpCooldown,
                                     JumpDecision jumpDecision, StepOutcome stepOutcome) {
        public PredictionBoundary {
            Objects.requireNonNull(jumpDecision, "jumpDecision");
            Objects.requireNonNull(stepOutcome, "stepOutcome");
            if (epoch <= 0 || mappingGeneration <= 0 || anchorServerSlot < 0 || serverSlot <= anchorServerSlot
                    || firstClientTick < 0 || firstInputSequence < 0 || preSnapshotRevision < 0
                    || heldInputSequence < firstInputSequence || jumpCooldown < 0)
                throw new IllegalArgumentException("Invalid prediction boundary");
            long resolved;
            try { resolved = Math.addExact(firstClientTick, Math.subtractExact(serverSlot, anchorServerSlot) - 1); }
            catch (ArithmeticException cause) { throw new IllegalArgumentException("Prediction boundary overflow", cause); }
            if (jumpDecision.outcome() != JumpOutcome.NONE && jumpDecision.clientTick() > resolved)
                throw new IllegalArgumentException("Jump decision exceeds resolved timeline");
        }
    }

    public sealed interface NativeTransfer permits NativeHandoff, NativeMotion,
            NativeHandoffAcknowledgement, NativeMotionAcknowledgement, NativeCorrectionCompleted, NativeCorrectionAcknowledgement, NativeTransferFailure, NativeTransferRecovery, ServerEnvironmentRecovery { }
    /** A server-origin environment failure may replace only this exact prior custody root. */
    public record ServerEnvironmentRecovery(PlayerPhysicalSnapshot.Scope previousScope, long previousProposal,
                                            OptionalInt previousNativeTeleportId) implements NativeTransfer {
        public ServerEnvironmentRecovery {
            Objects.requireNonNull(previousScope, "previous scope");
            Objects.requireNonNull(previousNativeTeleportId, "previous native teleport identity");
            if (previousProposal <= 0 || previousNativeTeleportId.isPresent() && previousNativeTeleportId.getAsInt() < 0)
                throw new IllegalArgumentException("Environment recovery predecessor identity");
        }
    }

    public record NativeHandoff(long handoffId, long restoreRevision, Vec3d baseVelocity) implements NativeTransfer {
        public NativeHandoff { validateNativeIdentity(handoffId, restoreRevision); requireFinite(baseVelocity); }
    }
    public record NativeMotion(long handoffId, long restoreRevision, long motionSequence,
                               Vec3d absoluteVelocity) implements NativeTransfer {
        public NativeMotion { validateNativeIdentity(handoffId, restoreRevision); if (motionSequence <= 0) throw new IllegalArgumentException("Native motion sequence"); requireFinite(absoluteVelocity); }
    }
    public record NativeHandoffAcknowledgement(long handoffId, long restoreRevision) implements NativeTransfer {
        public NativeHandoffAcknowledgement { validateNativeIdentity(handoffId, restoreRevision); }
    }
    public record NativeMotionAcknowledgement(long handoffId, long restoreRevision, long motionSequence) implements NativeTransfer {
        public NativeMotionAcknowledgement { validateNativeIdentity(handoffId, restoreRevision); if (motionSequence <= 0) throw new IllegalArgumentException("Native motion acknowledgement sequence"); }
    }
    public record NativeCorrectionCompleted(long correctionIdentity, int nativeTeleportId) implements NativeTransfer {
        public NativeCorrectionCompleted { validateNativeCorrection(correctionIdentity, nativeTeleportId); }
    }
    public record NativeCorrectionAcknowledgement(long correctionIdentity, int nativeTeleportId,
            long completedSequence) implements NativeTransfer {
        public NativeCorrectionAcknowledgement {
            validateNativeCorrection(correctionIdentity, nativeTeleportId);
            if (completedSequence < 0) throw new IllegalArgumentException("Native correction sequence");
        }
    }
    /** Identity only: neither a failed installation nor its feedback supplies physical state. */
    public record NativeTransferFailure(long handoffId, long restoreRevision, long failedSequence,
            long motionSequence) implements NativeTransfer {
        public NativeTransferFailure {
            validateNativeIdentity(handoffId, restoreRevision);
            if (failedSequence < 0 || motionSequence < 0) throw new IllegalArgumentException("Native failure identity");
        }
    }
    /** Only a dedicated recovery STATE may echo the exact failed transfer attempt. */
    public record NativeTransferRecovery(NativeTransferFailure failed) implements NativeTransfer {
        public NativeTransferRecovery { Objects.requireNonNull(failed, "failed"); }
    }
    private static void validateNativeCorrection(long identity, int teleportId) {
        if (identity <= 0 || teleportId < 0) throw new IllegalArgumentException("Native correction identity");
    }
    private static void validateNativeIdentity(long id, long revision) {
        if (id <= 0 || revision < 0) throw new IllegalArgumentException("Native transfer identity");
    }
    private static void validateNativeTransfer(Kind kind, Optional<NativeTransfer> transfer) {
        boolean required = kind == Kind.NATIVE || kind == Kind.NATIVE_MOTION || kind == Kind.NATIVE_ACK || kind == Kind.NATIVE_MOTION_ACK || kind == Kind.NATIVE_CORRECTION_COMPLETE || kind == Kind.NATIVE_CORRECTION_ACK;
        if (required && transfer.isEmpty() || !required && kind != Kind.RECOVER && kind != Kind.STATE && kind != Kind.OFFER && transfer.isPresent()) throw new IllegalArgumentException("Native transfer payload presence");
        if (transfer.isPresent()) {
            NativeTransfer value = transfer.orElseThrow();
            boolean valid = switch (kind) {
                case OFFER -> value instanceof ServerEnvironmentRecovery;
                case NATIVE -> value instanceof NativeHandoff;
                case RECOVER -> value instanceof NativeTransferFailure;
                case STATE -> value instanceof NativeTransferRecovery;
                case NATIVE_MOTION -> value instanceof NativeMotion;
                case NATIVE_ACK -> value instanceof NativeHandoffAcknowledgement;
                case NATIVE_MOTION_ACK -> value instanceof NativeMotionAcknowledgement;
                case NATIVE_CORRECTION_COMPLETE -> value instanceof NativeCorrectionCompleted;
                case NATIVE_CORRECTION_ACK -> value instanceof NativeCorrectionAcknowledgement;
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Native transfer payload kind");
        }
    }
    private static void validateEnvironmentRecovery(PlayerPhysicalSnapshot.Scope scope, Optional<NativeTransfer> transfer) {
        if (transfer.orElse(null) instanceof ServerEnvironmentRecovery recovery) {
            var previous = recovery.previousScope();
            if (!scope.playerId().equals(previous.playerId()) || scope.dimension() != previous.dimension()
                    || scope.equals(previous) || scope.worldEpoch() <= previous.worldEpoch())
                throw new IllegalArgumentException("Environment recovery must replace its same-player world epoch");
        }
    }

    /** A rejected request receipt is history; an explicit pending reference remains restoration purpose. */
    public static boolean nativeRestorePurpose(Packet packet) {
        return packet.bodyReferenceReceipt()
                .filter(receipt -> receipt.decision() != BodyReferenceDecision.REJECTED)
                .flatMap(BodyReferenceReceipt::reason)
                .filter(reason -> reason == BodyReferenceReason.NATIVE_HANDOFF
                        || reason == BodyReferenceReason.NATIVE_RESTORE_PENDING).isPresent()
                || packet.bodyReferenceState().filter(state -> state.transition()
                == PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING).isPresent();
    }

    public static Packet nativeTransferPacket(Kind kind, PlayerPhysicalSnapshot.Scope scope,
            long sequence, long proposal, NativeTransfer transfer) {
        return new Packet(kind, scope, sequence, proposal, Optional.empty(), Optional.empty(), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(transfer));
    }

    public record Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence,
                         long offerSequence, Optional<ApplicationId> application,
                         Optional<PlayerPhysicalSnapshot> snapshot, Optional<Controls> controls,
                         OptionalInt nativeTeleportId,
                          Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                          Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                          Optional<PlayerBodyReference.State> bodyReferenceState,
                          Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary,
                          Optional<CardinalPredictionSeed> predictionSeed, Optional<PredictionPhase> predictionPhase,
                          Optional<PredictionRequest> predictionRequest, Optional<PlayerMobilityIntent.State> mobilityState,
                          Optional<NativeTransfer> nativeTransfer) {
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot, Optional<Controls> controls,
                OptionalInt nativeTeleportId, Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement, Optional<PlayerBodyReference.State> bodyReferenceState,
                Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary,
                Optional<CardinalPredictionSeed> predictionSeed, Optional<PredictionPhase> predictionPhase,
                Optional<PredictionRequest> predictionRequest, Optional<PlayerMobilityIntent.State> mobilityState) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState, predictionInput,
                    predictionBoundary, predictionSeed, predictionPhase, predictionRequest, mobilityState, Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                      Optional<PlayerBodyReference.State> bodyReferenceState,
                      Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary,
                      Optional<CardinalPredictionSeed> predictionSeed, Optional<PredictionPhase> predictionPhase,
                      Optional<PredictionRequest> predictionRequest) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState, predictionInput,
                    predictionBoundary, predictionSeed, predictionPhase, predictionRequest, Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                      Optional<PlayerBodyReference.State> bodyReferenceState,
                      Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary,
                      Optional<CardinalPredictionSeed> predictionSeed, Optional<PredictionPhase> predictionPhase) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState, predictionInput,
                    predictionBoundary, predictionSeed, predictionPhase, Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                      Optional<PlayerBodyReference.State> bodyReferenceState,
                      Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary,
                      Optional<CardinalPredictionSeed> predictionSeed) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState, predictionInput,
                    predictionBoundary, predictionSeed, Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                      Optional<PlayerBodyReference.State> bodyReferenceState,
                      Optional<PredictionInput> predictionInput, Optional<PredictionBoundary> predictionBoundary) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState,
                    predictionInput, predictionBoundary, Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement,
                      Optional<PlayerBodyReference.State> bodyReferenceState) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, bodyReferenceState, Optional.empty(), Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot, Optional<Controls> controls) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
                      Optional<Controls> controls, OptionalInt nativeTeleportId,
                      Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                      Optional<BodyReferenceAcknowledgement> bodyReferenceAcknowledgement) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId,
                    bodyReferenceReceipt, bodyReferenceAcknowledgement, Optional.empty());
        }
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(controls, "controls");
            Objects.requireNonNull(nativeTeleportId, "nativeTeleportId");
            Objects.requireNonNull(bodyReferenceReceipt, "bodyReferenceReceipt");
            Objects.requireNonNull(bodyReferenceAcknowledgement, "bodyReferenceAcknowledgement");
            Objects.requireNonNull(bodyReferenceState, "bodyReferenceState");
            Objects.requireNonNull(predictionInput, "predictionInput");
            Objects.requireNonNull(predictionBoundary, "predictionBoundary");
            Objects.requireNonNull(predictionSeed, "predictionSeed");
            Objects.requireNonNull(predictionPhase, "predictionPhase");
            Objects.requireNonNull(predictionRequest, "predictionRequest");
            validatePredictionRequest(kind, predictionInput, predictionRequest);
            Objects.requireNonNull(nativeTransfer, "nativeTransfer");
            validateNativeTransfer(kind, nativeTransfer);
            validateEnvironmentRecovery(scope, nativeTransfer);
            if (nativeTransfer.orElse(null) instanceof NativeTransferRecovery) {
                if (!(snapshot.orElseThrow().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                        || cardinal.frame() != CardinalFrame.Y_POS || application.isPresent()
                        || bodyReferenceReceipt.isEmpty()
                        || bodyReferenceReceipt.orElseThrow().decision() != BodyReferenceDecision.UNLOCKED
                        || bodyReferenceReceipt.orElseThrow().acceptedFrame().isPresent()
                        || bodyReferenceReceipt.orElseThrow().tangentHeading().isPresent()
                        || bodyReferenceReceipt.orElseThrow().reason().orElse(null) != BodyReferenceReason.NATIVE_HANDOFF
                        || predictionBoundary.isPresent()
                        || !(predictionPhase.orElse(null) instanceof PredictionUnavailable))
                    throw new IllegalArgumentException("Native recovery STATE coherence");
            }
            Objects.requireNonNull(mobilityState, "mobilityState");
            if (mobilityState.isPresent() && kind != Kind.OWNED && kind != Kind.STATE)
                throw new IllegalArgumentException("Mobility state does not match packet kind");
            validatePredictionPhase(kind, snapshot.map(PlayerPhysicalSnapshot::revision), predictionSeed, predictionBoundary, predictionPhase);
            if (predictionSeed.isPresent() && kind != Kind.OWNED && kind != Kind.STATE)
                throw new IllegalArgumentException("Prediction seed does not match packet kind");
            validatePredictionKind(kind, predictionInput, predictionBoundary);
            if (nativeTeleportId.isPresent() && (kind != Kind.STATE || nativeTeleportId.getAsInt() < 0))
                throw new IllegalArgumentException("Invalid native correction identity");
            if (sequence < 0 || offerSequence < 0) throw new IllegalArgumentException("Negative sequence");
            boolean state = kind == Kind.OWNED || kind == Kind.STATE;
            if (snapshot.isPresent() != state || controls.isPresent() != (kind == Kind.INPUT)
                    || (application.isPresent() && kind != Kind.APPLY && kind != Kind.OWNED)
                    || bodyReferenceReceipt.isPresent() && kind != Kind.STATE
                    || bodyReferenceState.isPresent() && kind != Kind.OWNED && kind != Kind.STATE
                    || bodyReferenceAcknowledgement.isPresent() && kind != Kind.INPUT) {
                throw new IllegalArgumentException("Payload does not match packet kind");
            }
            snapshot.ifPresent(value -> {
                if (!scope.equals(value.scope())) throw new IllegalArgumentException("Snapshot scope mismatch");
                if (!(value.placement().shape() instanceof PlayerBodyShape.Cardinal)
                        && !(value.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
                    throw new IllegalArgumentException("Unsupported physical-state placement shape");
                }
                requireFinite(value.worldVelocity());
            });
            mobilityState.ifPresent(value -> {
                boolean cardinal = snapshot.orElseThrow().placement().shape() instanceof PlayerBodyShape.Cardinal;
                if (cardinal != (value.effectiveMode() == PlayerMobilityIntent.EffectiveMode.WALK))
                    throw new IllegalArgumentException("Mobility effective mode does not match installed geometry");
            });
            predictionSeed.ifPresent(seed -> validatePredictionSeed(seed, snapshot.orElseThrow()));
            bodyReferenceState.ifPresent(reference -> {
                var placement = snapshot.orElseThrow().placement();
                if (!(placement.shape() instanceof PlayerBodyShape.Cardinal cardinal)
                        || cardinal.frame() != reference.effective().frame()) {
                    throw new IllegalArgumentException("Effective body reference does not match accepted geometry");
                }
            });
        }
        public boolean clientbound() { return kind == Kind.OFFER || kind == Kind.OWNED || kind == Kind.STATE || kind == Kind.NATIVE || kind == Kind.NATIVE_MOTION || kind == Kind.NATIVE_CORRECTION_COMPLETE; }
    }

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        ByteBuffer out = ByteBuffer.allocate(MAX_BYTES);
        out.put((byte) VERSION).put((byte) packet.kind().ordinal());
        putUuid(out, packet.scope().playerId());
        putUuid(out, packet.scope().sessionId());
        out.putInt(packet.scope().dimension()).putLong(packet.scope().worldEpoch());
        out.putLong(packet.sequence()).putLong(packet.offerSequence());
        out.put((byte) (packet.application().isPresent() ? 1 : 0));
        packet.application().ifPresent(application -> {
            putOperation(out, application.state());
            out.put((byte) (application.lastVelocityOperation().isPresent() ? 1 : 0));
            application.lastVelocityOperation().ifPresent(last -> putOperation(out, last));
        });
        packet.snapshot().ifPresent(snapshot -> {
            out.put((byte) (packet.nativeTeleportId().isPresent() ? 1 : 0));
            packet.nativeTeleportId().ifPresent(out::putInt);
            putSnapshot(out, snapshot);
        });
        packet.controls().ifPresent(controls -> {
            putVector(out, controls.translation());
            putVector(out, controls.forward());
            out.put((byte) ((controls.jump() ? 1 : 0) | (controls.sneak() ? 2 : 0)
                    | (controls.sprint() ? 4 : 0) | (controls.boost() ? 8 : 0)));
            out.put((byte) controls.boostGear());
            out.putDouble(controls.keyboardTravelGain());
            putBodyReferenceIntent(out, controls.bodyReferenceIntent());
            out.put((byte) (controls.desiredBodyAttitude().isPresent() ? 1 : 0));
            controls.desiredBodyAttitude().ifPresent(q -> putRawQuaternion(out,
                    FreeCapsulePlacementV1.RawQuaternion.from(q)));
            putMobilityInput(out, controls.mobility());
        });
        if (packet.kind() == Kind.STATE) {
            out.put((byte) (packet.bodyReferenceReceipt().isPresent() ? 1 : 0));
            packet.bodyReferenceReceipt().ifPresent(receipt -> putBodyReferenceReceipt(out, receipt));
        }
        if (packet.kind() == Kind.OWNED || packet.kind() == Kind.STATE) {
            out.put((byte) (packet.bodyReferenceState().isPresent() ? 1 : 0));
            packet.bodyReferenceState().ifPresent(state -> putBodyReferenceState(out, state));
        }
        if (packet.kind() == Kind.INPUT) {
            out.put((byte) (packet.bodyReferenceAcknowledgement().isPresent() ? 1 : 0));
            packet.bodyReferenceAcknowledgement().ifPresent(acknowledgement -> out
                    .putLong(acknowledgement.stateSequence()).putLong(acknowledgement.snapshotRevision()));
        }
        out.put((byte) (packet.predictionInput().isPresent() ? 1 : 0));
        packet.predictionInput().ifPresent(value -> out.putLong(value.epoch()).putLong(value.firstClientTick())
                .putLong(value.clientTick()).putLong(value.jumpEdgeId()));
        packet.predictionInput().ifPresent(value -> {
            out.put((byte) (value.bootstrap().isPresent() ? 1 : 0));
            value.bootstrap().ifPresent(bootstrap -> putPredictionBootstrap(out, bootstrap));
        });
        out.put((byte) (packet.predictionBoundary().isPresent() ? 1 : 0));
        packet.predictionBoundary().ifPresent(value -> putPredictionBoundary(out, value));
        out.put((byte) (packet.predictionSeed().isPresent() ? 1 : 0));
        packet.predictionSeed().ifPresent(value -> putPredictionSeed(out, value));
        out.put((byte) (packet.predictionPhase().isPresent() ? 1 : 0));
        packet.predictionPhase().ifPresent(value -> putPredictionPhase(out, value));
        out.put((byte) (packet.predictionRequest().isPresent() ? 1 : 0));
        packet.predictionRequest().ifPresent(value -> {
            out.putLong(value.requestId()); putPredictionBootstrap(out, value.bootstrap());
            out.put((byte)value.action().ordinal()).put((byte)value.origin().ordinal()).put((byte)(value.refusal().isPresent()?1:0));
            value.refusal().ifPresent(refusal->out.put((byte)refusal.reason().ordinal()).put((byte)refusal.stage().ordinal()));
        });
        if (packet.kind() == Kind.OWNED || packet.kind() == Kind.STATE) {
            out.put((byte) (packet.mobilityState().isPresent() ? 1 : 0));
            packet.mobilityState().ifPresent(value -> putMobilityState(out, value));
        }
        out.put((byte) (packet.nativeTransfer().isPresent() ? 1 : 0));
        packet.nativeTransfer().ifPresent(value -> putNativeTransfer(out, value));
        return Arrays.copyOf(out.array(), out.position());
    }

    private sealed interface SnapshotFrame permits CardinalSnapshotFrame, FreeCapsuleSnapshotFrame {
        PlayerPhysicalSnapshot materialize(PlayerPhysicalSnapshot.Scope scope, PhysicsDecodeBudget budget);
    }

    private record CardinalSnapshotFrame(PlayerPhysicalSnapshot snapshot) implements SnapshotFrame {
        @Override
        public PlayerPhysicalSnapshot materialize(PlayerPhysicalSnapshot.Scope scope, PhysicsDecodeBudget budget) {
            return snapshot;
        }
    }

    private record FreeCapsuleSnapshotFrame(long revision, long widthBits, long heightBits, long eyeHeightBits,
                                             FreeCapsulePlacementV1.RawQuaternion attitude,
                                             FreeCapsulePlacementV1 placement,
                                             FreeCapsulePlacementV1.RawAabb proxy,
                                             FreeCapsulePlacementV1.RawVec3 transport,
                                             FreeCapsulePlacementV1.RawVec3 worldVelocity,
                                             Optional<PlayerPhysicalSnapshot.Contacts> contacts)
            implements SnapshotFrame {
        @Override
        public PlayerPhysicalSnapshot materialize(PlayerPhysicalSnapshot.Scope scope, PhysicsDecodeBudget budget) {
            if (budget == null) {
                throw new IllegalArgumentException("Free placement decode requires a caller-owned decode budget");
            }
            var dimensions = new PlayerBodyShape.Dimensions(Double.longBitsToDouble(widthBits),
                    Double.longBitsToDouble(heightBits), Double.longBitsToDouble(eyeHeightBits));
            PlayerBodyPlacement restoredPlacement = PlayerPlacementCodec.restoreFreeV1(
                    placement, attitude, dimensions, proxy, transport, budget);
            Vec3d velocity = new Vec3d(Double.longBitsToDouble(worldVelocity.xBits()),
                    Double.longBitsToDouble(worldVelocity.yBits()), Double.longBitsToDouble(worldVelocity.zBits()));
            return new PlayerPhysicalSnapshot(scope, revision, restoredPlacement, velocity, contacts);
        }
    }

    private record RawBodyReferenceIntent(long requestId, BodyReferenceAction action,
                                          Optional<CardinalFrame> frame,
                                          Optional<FreeCapsulePlacementV1.RawVec3> heading) {
        BodyReferenceIntent materialize() {
            return new BodyReferenceIntent(requestId, action, frame, heading.map(h -> h.toVector("input tangent")));
        }
    }

    /** Input remains raw until its connection-owned lease charges all fields and exact Q work. */
    private record RawInputFrame(FreeCapsulePlacementV1.RawVec3 translation,
                                 FreeCapsulePlacementV1.RawVec3 forward, int flags, int gear,
                                 long gainBits, RawBodyReferenceIntent intent,
                                 Optional<FreeCapsulePlacementV1.RawQuaternion> attitude, PlayerMobilityIntent.Input mobility) {
        Controls materialize(PhysicsDecodeBudget budget, boolean acknowledgementPresent) {
            if (budget == null) throw new IllegalArgumentException("Physical input decode requires a caller-owned decode budget");
            budget.beginInputRestore();
            budget.chargeRawFieldReads(13 + (intent.action() == BodyReferenceAction.LOCK ? 4 : 0)
                    + (attitude.isPresent() ? 4 : 0) + (acknowledgementPresent ? 2 : 0)
                    + 3 + (mobility.request().isPresent() ? 3 : 0));
            Optional<Rotation3d> desired = attitude.map(raw -> {
                budget.reserveQuaternionReconstruction();
                Rotation3d restored = raw.reconstructExact();
                if (!raw.exactlyMatches(restored))
                    throw new IllegalArgumentException("input quaternion reconstruction changed raw representation bits");
                return restored;
            });
            return new Controls(translation.toVector("input translation"), forward.toVector("input forward"),
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                    gear, Double.longBitsToDouble(gainBits), intent.materialize(), desired, mobility);
        }
    }

    private record RawAcknowledgement(long stateSequence, long snapshotRevision) {
        BodyReferenceAcknowledgement materialize() {
            return new BodyReferenceAcknowledgement(stateSequence, snapshotRevision);
        }
    }

    private record ParsedPacket(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence,
                                long offerSequence, Optional<ApplicationId> application,
                                Optional<SnapshotFrame> snapshot, Optional<RawInputFrame> controls,
                                OptionalInt nativeTeleportId, Optional<BodyReferenceReceipt> bodyReferenceReceipt,
                                Optional<RawAcknowledgement> bodyReferenceAcknowledgement,
                                Optional<PlayerBodyReference.State> bodyReferenceState, Optional<PredictionInput> predictionInput,
                                Optional<PredictionBoundary> predictionBoundary,
                                Optional<CardinalPredictionSeed> predictionSeed, Optional<PredictionPhase> predictionPhase,
                                Optional<PredictionRequest> predictionRequest, Optional<PlayerMobilityIntent.State> mobilityState, Optional<NativeTransfer> nativeTransfer, int encodedPacketBytes) {
        private void validateBeforeProofRestore() {
            validatePredictionRequest(kind, predictionInput, predictionRequest);
            validatePredictionKind(kind, predictionInput, predictionBoundary);
            validatePredictionPhase(kind, snapshot.map(frame -> frame instanceof CardinalSnapshotFrame cardinal
                    ? cardinal.snapshot().revision() : ((FreeCapsuleSnapshotFrame) frame).revision()),
                    predictionSeed, predictionBoundary, predictionPhase);
            if (predictionSeed.isPresent()) {
                if (kind != Kind.OWNED && kind != Kind.STATE)
                    throw new IllegalArgumentException("Prediction seed does not match packet kind");
                if (predictionSeed.orElseThrow() instanceof CardinalPredictionSeed.Ready ready) {
                    if (!(snapshot.orElseThrow() instanceof CardinalSnapshotFrame cardinal))
                        throw new IllegalArgumentException("Ready prediction seed requires Cardinal geometry");
                    validatePredictionSeed(ready, cardinal.snapshot());
                }
            }
            if (sequence < 0 || offerSequence < 0) throw new IllegalArgumentException("Negative sequence");
            if (application.isPresent() && kind != Kind.APPLY && kind != Kind.OWNED) {
                throw new IllegalArgumentException("Application payload does not match packet kind");
            }
            if (nativeTeleportId.isPresent() && (kind != Kind.STATE || nativeTeleportId.getAsInt() < 0)) {
                throw new IllegalArgumentException("Invalid native correction identity");
            }
            boolean state = kind == Kind.OWNED || kind == Kind.STATE;
            if (snapshot.isPresent() != state || controls.isPresent() != (kind == Kind.INPUT)
                    || bodyReferenceReceipt.isPresent() && kind != Kind.STATE
                    || bodyReferenceState.isPresent() && !state
                    || bodyReferenceAcknowledgement.isPresent() && kind != Kind.INPUT) {
                throw new IllegalArgumentException("Payload does not match packet kind");
            }
            snapshot.ifPresent(frame -> {
                if (frame instanceof FreeCapsuleSnapshotFrame free) {
                    if (bodyReferenceState.isPresent()) {
                        throw new IllegalArgumentException("Free placement cannot carry cardinal body-reference state");
                    }
                    if (free.revision() < 0 || !isFinite(free.worldVelocity())) {
                        throw new IllegalArgumentException("Invalid Free physical snapshot metadata");
                    }
                } else if (frame instanceof CardinalSnapshotFrame cardinal) {
                    requireFinite(cardinal.snapshot().worldVelocity());
                    bodyReferenceState.ifPresent(reference -> {
                        var placement = cardinal.snapshot().placement();
                        if (!(placement.shape() instanceof PlayerBodyShape.Cardinal cardinalShape)
                                || cardinalShape.frame() != reference.effective().frame()) {
                            throw new IllegalArgumentException("Effective body reference does not match accepted geometry");
                        }
                    });
                }
            });
        }

        private Packet materialize(PhysicsDecodeBudget budget) {
            if (budget != null && budget.encodedPacketBytes() != encodedPacketBytes) {
                throw new IllegalArgumentException("Decode budget packet length does not match frame length");
            }
            Optional<PlayerPhysicalSnapshot> restoredSnapshot = snapshot.map(frame -> frame.materialize(scope, budget));
            Optional<Controls> restoredControls = controls.map(input -> input.materialize(budget,
                    bodyReferenceAcknowledgement.isPresent()));
            return new Packet(kind, scope, sequence, offerSequence, application, restoredSnapshot, restoredControls,
                    nativeTeleportId, bodyReferenceReceipt, bodyReferenceAcknowledgement.map(RawAcknowledgement::materialize), bodyReferenceState, predictionInput, predictionBoundary, predictionSeed, predictionPhase, predictionRequest, mobilityState, nativeTransfer);
        }
    }

    /** A fully parsed frame that has not yet restored any Free placement proof. */
    static final class InspectedFrame {
        private final ParsedPacket parsed;

        private InspectedFrame(ParsedPacket parsed) {
            this.parsed = Objects.requireNonNull(parsed, "parsed");
        }

        Kind kind() { return parsed.kind(); }
        PlayerPhysicalSnapshot.Scope scope() { return parsed.scope(); }
        int encodedPacketBytes() { return parsed.encodedPacketBytes(); }

        boolean hasFreePlacement() {
            return parsed.snapshot().filter(FreeCapsuleSnapshotFrame.class::isInstance).isPresent();
        }
        boolean requiresNumericWork() { return hasFreePlacement() || parsed.controls().isPresent(); }

        boolean noFreeRecoveryControl() {
            return switch (parsed.kind()) {
                case OFFER, APPLY, NATIVE, RECOVER, NATIVE_MOTION, NATIVE_ACK, NATIVE_MOTION_ACK, NATIVE_CORRECTION_COMPLETE, NATIVE_CORRECTION_ACK -> parsed.snapshot().isEmpty() && parsed.controls().isEmpty();
                default -> false;
            };
        }

        Packet materialize(PhysicsDecodeBudget budget) {
            return parsed.materialize(Objects.requireNonNull(budget, "budget"));
        }

        Packet materializeWithoutFreeProof() {
            if (requiresNumericWork()) {
                throw new IllegalArgumentException(hasFreePlacement()
                        ? "Free placement decode requires a caller-owned decode budget"
                        : "Physical input decode requires a caller-owned decode budget");
            }
            return parsed.materialize(null);
        }
    }

    static InspectedFrame inspectFrame(byte[] bytes) {
        return new InspectedFrame(parseFrame(bytes));
    }

    public static Packet decode(byte[] bytes) {
        return inspectFrame(bytes).materializeWithoutFreeProof();
    }

    /** Decodes a Free placement only with the caller's per-frame view over shared numeric Work. */
    static Packet decode(byte[] bytes, PhysicsDecodeBudget budget) {
        Objects.requireNonNull(budget, "budget");
        return inspectFrame(bytes).materialize(budget);
    }

    private static ParsedPacket parseFrame(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Physics packet length");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        try {
            if (Byte.toUnsignedInt(in.get()) != VERSION) throw new IllegalArgumentException("Physics version");
            Kind kind = enumValue(Kind.values(), in.get());
            var scope = new PlayerPhysicalSnapshot.Scope(getUuid(in), getUuid(in), in.getInt(), in.getLong());
            long sequence = in.getLong();
            long offerSequence = in.getLong();
            Optional<ApplicationId> application = Optional.empty();
            if (getBoolean(in)) {
                var state = getOperation(in);
                application = Optional.of(new ApplicationId(state,
                        getBoolean(in) ? Optional.of(getOperation(in)) : Optional.empty()));
            }
            OptionalInt nativeTeleportId = OptionalInt.empty();
            Optional<SnapshotFrame> snapshot = Optional.empty();
            if (kind == Kind.OWNED || kind == Kind.STATE) {
                if (getBoolean(in)) nativeTeleportId = OptionalInt.of(in.getInt());
                snapshot = Optional.of(getSnapshotFrame(in, scope));
            }
            Optional<RawInputFrame> controls = Optional.empty();
            if (kind == Kind.INPUT) {
                var translation = getRawVector(in);
                var forward = getRawVector(in);
                int flags = Byte.toUnsignedInt(in.get());
                if (flags > 15) throw new IllegalArgumentException("Unknown control flags");
                int gear = Byte.toUnsignedInt(in.get());
                long gainBits = in.getLong();
                long requestId = in.getLong();
                BodyReferenceAction action = enumValue(BodyReferenceAction.values(), in.get());
                Optional<CardinalFrame> frame = Optional.empty();
                Optional<FreeCapsulePlacementV1.RawVec3> heading = Optional.empty();
                if (action == BodyReferenceAction.LOCK) {
                    frame = Optional.of(enumValue(CardinalFrame.values(), in.get()));
                    heading = Optional.of(getRawVector(in));
                }
                var intent = new RawBodyReferenceIntent(requestId, action, frame, heading);
                var attitude = getBoolean(in) ? Optional.of(getRawQuaternion(in))
                        : Optional.<FreeCapsulePlacementV1.RawQuaternion>empty();
                var mobility = getMobilityInput(in);
                controls = Optional.of(new RawInputFrame(translation, forward, flags, gear, gainBits, intent, attitude, mobility));
            }
            Optional<BodyReferenceReceipt> receipt = Optional.empty();
            if (kind == Kind.STATE && getBoolean(in)) receipt = Optional.of(getBodyReferenceReceipt(in));
            Optional<PlayerBodyReference.State> referenceState = Optional.empty();
            if ((kind == Kind.OWNED || kind == Kind.STATE) && getBoolean(in))
                referenceState = Optional.of(getBodyReferenceState(in));
            Optional<RawAcknowledgement> acknowledgement = Optional.empty();
            if (kind == Kind.INPUT && getBoolean(in))
                acknowledgement = Optional.of(new RawAcknowledgement(in.getLong(), in.getLong()));
            Optional<PredictionInput> predictionInput = getBoolean(in)
                    ? Optional.of(getPredictionInput(in)) : Optional.empty();
            Optional<PredictionBoundary> predictionBoundary = getBoolean(in)
                    ? Optional.of(getPredictionBoundary(in)) : Optional.empty();
            Optional<CardinalPredictionSeed> predictionSeed = getBoolean(in)
                    ? Optional.of(getPredictionSeed(in)) : Optional.empty();
            Optional<PredictionPhase> predictionPhase = getBoolean(in) ? Optional.of(getPredictionPhase(in)) : Optional.empty();
            Optional<PredictionRequest> predictionRequest = Optional.empty();
            if (getBoolean(in)) {
                long id=in.getLong(); var bootstrap=getPredictionBootstrap(in);
                int action=Byte.toUnsignedInt(in.get()),origin=Byte.toUnsignedInt(in.get());
                if (action>=PredictionRequestAction.values().length || origin>=PredictionSuspensionOrigin.values().length)
                    throw new IllegalArgumentException("Unknown prediction request tag");
                Optional<PredictionRefusal> refusal=Optional.empty();
                if(getBoolean(in)) {
                    int reason=Byte.toUnsignedInt(in.get()),stage=Byte.toUnsignedInt(in.get());
                    if(reason>=CardinalPredictionSeed.Reason.values().length || stage>=PredictionRefusalStage.values().length)
                        throw new IllegalArgumentException("Unknown refusal tag");
                    refusal=Optional.of(new PredictionRefusal(CardinalPredictionSeed.Reason.values()[reason],PredictionRefusalStage.values()[stage]));
                }
                predictionRequest=Optional.of(new PredictionRequest(id,bootstrap,PredictionRequestAction.values()[action],refusal,PredictionSuspensionOrigin.values()[origin]));
            }
            Optional<PlayerMobilityIntent.State> mobilityState = (kind == Kind.OWNED || kind == Kind.STATE) && getBoolean(in)
                    ? Optional.of(getMobilityState(in)) : Optional.empty();
            Optional<NativeTransfer> nativeTransfer = getBoolean(in) ? Optional.of(getNativeTransfer(in, kind)) : Optional.empty();
            validateNativeTransfer(kind, nativeTransfer);
            validateEnvironmentRecovery(scope, nativeTransfer);
            if (in.hasRemaining()) throw new IllegalArgumentException("Trailing physics bytes");
            ParsedPacket parsed = new ParsedPacket(kind, scope, sequence, offerSequence, application, snapshot,
                    controls, nativeTeleportId, receipt, acknowledgement, referenceState, predictionInput, predictionBoundary, predictionSeed, predictionPhase, predictionRequest, mobilityState, nativeTransfer, bytes.length);
            parsed.validateBeforeProofRestore();
            return parsed;
        } catch (BufferUnderflowException cause) {
            throw new IllegalArgumentException("Truncated physics packet", cause);
        }
    }

    private static void putNativeTransfer(ByteBuffer out, NativeTransfer value) {
        if (value instanceof ServerEnvironmentRecovery recovery) {
            var scope = recovery.previousScope();
            putUuid(out, scope.playerId()); putUuid(out, scope.sessionId());
            out.putInt(scope.dimension()).putLong(scope.worldEpoch()).putLong(recovery.previousProposal());
            out.put((byte) (recovery.previousNativeTeleportId().isPresent() ? 1 : 0));
            recovery.previousNativeTeleportId().ifPresent(out::putInt);
        } else if (value instanceof NativeTransferFailure failed) {
            out.putLong(failed.handoffId()).putLong(failed.restoreRevision()).putLong(failed.failedSequence()).putLong(failed.motionSequence());
        } else if (value instanceof NativeTransferRecovery recovery) {
            putNativeTransfer(out, recovery.failed());
        } else if (value instanceof NativeCorrectionCompleted completed) {
            out.putLong(completed.correctionIdentity()).putInt(completed.nativeTeleportId());
        } else if (value instanceof NativeCorrectionAcknowledgement ack) {
            out.putLong(ack.correctionIdentity()).putInt(ack.nativeTeleportId()).putLong(ack.completedSequence());
        } else if (value instanceof NativeHandoff handoff) {
            out.putLong(handoff.handoffId()).putLong(handoff.restoreRevision()); putVector(out, handoff.baseVelocity());
        } else if (value instanceof NativeMotion motion) {
            out.putLong(motion.handoffId()).putLong(motion.restoreRevision()).putLong(motion.motionSequence()); putVector(out, motion.absoluteVelocity());
        } else if (value instanceof NativeHandoffAcknowledgement ack) {
            out.putLong(ack.handoffId()).putLong(ack.restoreRevision());
        } else if (value instanceof NativeMotionAcknowledgement ack) {
            out.putLong(ack.handoffId()).putLong(ack.restoreRevision()).putLong(ack.motionSequence());
        }
    }
    private static NativeTransfer getNativeTransfer(ByteBuffer in, Kind kind) {
        if (kind == Kind.OFFER) {
            var previous = new PlayerPhysicalSnapshot.Scope(getUuid(in), getUuid(in), in.getInt(), in.getLong());
            long proposal = in.getLong();
            var teleport = getBoolean(in) ? OptionalInt.of(in.getInt()) : OptionalInt.empty();
            return new ServerEnvironmentRecovery(previous, proposal, teleport);
        }
        if (kind == Kind.RECOVER || kind == Kind.STATE) {
            var failed = new NativeTransferFailure(in.getLong(), in.getLong(), in.getLong(), in.getLong());
            return kind == Kind.RECOVER ? failed : new NativeTransferRecovery(failed);
        }
        if (kind == Kind.NATIVE_CORRECTION_COMPLETE) return new NativeCorrectionCompleted(in.getLong(), in.getInt());
        if (kind == Kind.NATIVE_CORRECTION_ACK) return new NativeCorrectionAcknowledgement(in.getLong(), in.getInt(), in.getLong());
        long id = in.getLong(), revision = in.getLong();
        return switch (kind) {
            case NATIVE -> new NativeHandoff(id, revision, getVector(in));
            case NATIVE_MOTION -> new NativeMotion(id, revision, in.getLong(), getVector(in));
            case NATIVE_ACK -> new NativeHandoffAcknowledgement(id, revision);
            case NATIVE_MOTION_ACK -> new NativeMotionAcknowledgement(id, revision, in.getLong());
            default -> throw new IllegalArgumentException("Unexpected native transfer payload");
        };
    }

    private static void putMobilityInput(ByteBuffer out, PlayerMobilityIntent.Input input) {
        out.put((byte) (input.request().isPresent() ? 1 : 0));
        input.request().ifPresent(request -> out.putLong(request.id()).put((byte) request.mode().ordinal())
                .put((byte) request.hover().ordinal()));
        out.putLong(input.pressId()).put((byte) (input.held() ? 1 : 0)).put((byte) (input.focused() ? 1 : 0));
    }

    private static PlayerMobilityIntent.Input getMobilityInput(ByteBuffer in) {
        Optional<PlayerMobilityIntent.Request> request = getBoolean(in)
                ? Optional.of(new PlayerMobilityIntent.Request(in.getLong(),
                        enumValue(PlayerMobilityIntent.Mode.values(), in.get()),
                        enumValue(PlayerMobilityIntent.Hover.values(), in.get()))) : Optional.empty();
        return new PlayerMobilityIntent.Input(request, in.getLong(), getBoolean(in), getBoolean(in));
    }

    private static void putMobilityState(ByteBuffer out, PlayerMobilityIntent.State state) {
        out.putLong(state.acceptedRequest()).put((byte) state.mode().ordinal()).put((byte) state.hover().ordinal())
                .put((byte) state.effectiveMode().ordinal()).put((byte) state.hoverStatus().ordinal())
                .put((byte) state.reason().ordinal()).put((byte) (state.detached() ? 1 : 0)).putLong(state.detachPress());
    }

    private static PlayerMobilityIntent.State getMobilityState(ByteBuffer in) {
        return new PlayerMobilityIntent.State(in.getLong(), enumValue(PlayerMobilityIntent.Mode.values(), in.get()),
                enumValue(PlayerMobilityIntent.Hover.values(), in.get()), enumValue(PlayerMobilityIntent.EffectiveMode.values(), in.get()),
                enumValue(PlayerMobilityIntent.HoverStatus.values(), in.get()), enumValue(PlayerMobilityIntent.Reason.values(), in.get()),
                getBoolean(in), in.getLong());
    }

    private static PredictionInput getPredictionInput(ByteBuffer in) {
        long epoch = in.getLong(), first = in.getLong(), tick = in.getLong(), edge = in.getLong();
        return new PredictionInput(epoch, first, tick, edge,
                getBoolean(in) ? Optional.of(getPredictionBootstrap(in)) : Optional.empty());
    }
    private static void putPredictionBootstrap(ByteBuffer out, PredictionBootstrap value) {
        out.putLong(value.generation()).putLong(value.correctionRevision());
    }
    private static PredictionBootstrap getPredictionBootstrap(ByteBuffer in) {
        return new PredictionBootstrap(in.getLong(), in.getLong());
    }
    private static void putPredictionPhase(ByteBuffer out, PredictionPhase value) {
        if (value instanceof AwaitingFirstResolvedSlot awaiting) {
            out.put((byte) 0).putLong(awaiting.snapshotRevision());
            putPredictionBootstrap(out, awaiting.bootstrap()); out.putInt(awaiting.jumpCooldown());
        } else if (value instanceof Anchored anchored) {
            out.put((byte) 1).putLong(anchored.snapshotRevision()); putPredictionBootstrap(out, anchored.bootstrap());
        } else if (value instanceof PredictionSuspended suspended) {
            out.put((byte)3).putLong(suspended.snapshotRevision()); putPredictionBootstrap(out,suspended.bootstrap()); out.putLong(suspended.requestId()).put((byte)suspended.origin().ordinal());
        } else if (value instanceof PredictionUnavailable unavailable) {
            out.put((byte) 2).putLong(unavailable.snapshotRevision()).put((byte) unavailable.reason().ordinal());
        }
    }
    private static PredictionPhase getPredictionPhase(ByteBuffer in) {
        int type = Byte.toUnsignedInt(in.get()); long revision = in.getLong();
        return switch (type) {
            case 0 -> new AwaitingFirstResolvedSlot(getPredictionBootstrap(in), revision, in.getInt());
            case 1 -> new Anchored(getPredictionBootstrap(in), revision);
            case 2 -> new PredictionUnavailable(revision, getPredictionUnavailableReason(in));
            case 3 -> {
                var bootstrap=getPredictionBootstrap(in);long id=in.getLong();int origin=Byte.toUnsignedInt(in.get());
                if(origin>=PredictionSuspensionOrigin.values().length) throw new IllegalArgumentException("Unknown suspension origin");
                yield new PredictionSuspended(revision,bootstrap,id,PredictionSuspensionOrigin.values()[origin]);
            }
            default -> throw new IllegalArgumentException("Unknown prediction phase");
        };
    }
    private static PredictionUnavailableReason getPredictionUnavailableReason(ByteBuffer in) {
        int ordinal = Byte.toUnsignedInt(in.get());
        if (ordinal >= PredictionUnavailableReason.values().length) throw new IllegalArgumentException("Unknown prediction unavailable reason");
        return PredictionUnavailableReason.values()[ordinal];
    }
    private static void validatePredictionPhase(Kind kind, Optional<Long> revision, Optional<CardinalPredictionSeed> seed,
            Optional<PredictionBoundary> boundary, Optional<PredictionPhase> phase) {
        if (phase.isEmpty()) return; // Legacy/local fixtures never grant prediction without a typed phase.
        if (kind != Kind.OWNED && kind != Kind.STATE || revision.isEmpty()
                || phase.orElseThrow().snapshotRevision() != revision.orElseThrow())
            throw new IllegalArgumentException("Prediction phase snapshot mismatch");
        var value = phase.orElseThrow();
        if (value instanceof PredictionUnavailable || value instanceof PredictionSuspended) {
            if (boundary.isPresent()) throw new IllegalArgumentException("Unavailable phase carries a boundary");
        } else {
            if (value instanceof AwaitingFirstResolvedSlot && !(seed.orElse(null) instanceof CardinalPredictionSeed.Ready)
                    || value instanceof Anchored && seed.isEmpty())
                throw new IllegalArgumentException("Awaiting requires Ready; anchored requires its actual observed seed");
            if ((value instanceof Anchored) != boundary.isPresent())
                throw new IllegalArgumentException("Prediction phase/boundary mismatch");
        }
    }

    private static void validatePredictionRequest(Kind kind, Optional<PredictionInput> input, Optional<PredictionRequest> request) {
        if (request.isPresent() && (kind != Kind.INPUT || input.isPresent()))
            throw new IllegalArgumentException("Prediction request requires ordinary INPUT");
    }

    private static void validatePredictionSeed(CardinalPredictionSeed seed, PlayerPhysicalSnapshot snapshot) {
        if (seed instanceof CardinalPredictionSeed.Ready ready
                && (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                    || ready.snapshotRevision() != snapshot.revision() || ready.frame() != cardinal.frame()))
            throw new IllegalArgumentException("Prediction seed does not match accepted snapshot revision/frame");
    }

    private static void putPredictionSeed(ByteBuffer out, CardinalPredictionSeed seed) {
        if (seed instanceof CardinalPredictionSeed.Ready ready) {
            out.put((byte) 0).putLong(ready.snapshotRevision()).putLong(ready.worldTick())
                    .put((byte) ready.frame().ordinal());
            putVector(out, ready.environmentAcceleration());
            out.put((byte) (ready.bindingMode().isPresent() ? 1 : 0));
            ready.bindingMode().ifPresent(mode -> out.put((byte) mode.ordinal()));
            out.put((byte) ((ready.bindingActive() ? 1 : 0) | (ready.personalZeroG() ? 2 : 0)
                    | (ready.savedNativeNoGravity() ? 4 : 0) | (ready.standingReference() ? 8 : 0)))
                    .putDouble(ready.ceilingMps());
        } else if (seed instanceof CardinalPredictionSeed.Unsupported unsupported) {
            out.put((byte) 1).put((byte) unsupported.reason().ordinal());
        } else if (seed instanceof CardinalPredictionSeed.Unavailable unavailable) {
            // The original cause belongs to the server diagnostic boundary, never the wire.
            out.put((byte) 2).put((byte) unavailable.reason().ordinal());
        } else throw new IllegalArgumentException("Unknown prediction seed status");
    }

    private static CardinalPredictionSeed getPredictionSeed(ByteBuffer in) {
        int status = Byte.toUnsignedInt(in.get());
        if (status == 1) return new CardinalPredictionSeed.Unsupported(enumValue(CardinalPredictionSeed.Reason.values(), in.get()));
        if (status == 2) return new CardinalPredictionSeed.Unavailable(enumValue(CardinalPredictionSeed.Reason.values(), in.get()), Optional.empty());
        if (status != 0) throw new IllegalArgumentException("Unknown prediction seed status");
        long revision = in.getLong(), worldTick = in.getLong();
        var frame = enumValue(CardinalFrame.values(), in.get());
        var acceleration = getVector(in);
        Optional<GravityMode> mode = getBoolean(in) ? Optional.of(enumValue(GravityMode.values(), in.get())) : Optional.empty();
        int flags = Byte.toUnsignedInt(in.get());
        if (flags > 15) throw new IllegalArgumentException("Unknown prediction seed flags");
        return new CardinalPredictionSeed.Ready(revision, worldTick, frame, acceleration, mode,
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, in.getDouble());
    }

    private static void validatePredictionKind(Kind kind, Optional<PredictionInput> input,
                                               Optional<PredictionBoundary> boundary) {
        if (input.isPresent() && kind != Kind.INPUT || boundary.isPresent() && kind != Kind.OWNED && kind != Kind.STATE)
            throw new IllegalArgumentException("Prediction payload does not match packet kind");
    }
    private static void putPredictionBoundary(ByteBuffer out, PredictionBoundary value) {
        out.putLong(value.epoch()).putLong(value.mappingGeneration()).putLong(value.anchorServerSlot())
                .putLong(value.serverSlot()).putLong(value.firstClientTick()).putLong(value.firstInputSequence())
                .putLong(value.preSnapshotRevision()).putLong(value.heldInputSequence()).putInt(value.jumpCooldown())
                .putLong(value.jumpDecision().edgeId()).putLong(value.jumpDecision().clientTick())
                .put((byte) value.jumpDecision().outcome().ordinal()).put((byte) value.stepOutcome().ordinal());
    }
    private static PredictionBoundary getPredictionBoundary(ByteBuffer in) {
        long epoch = in.getLong(), generation = in.getLong(), anchor = in.getLong(), slot = in.getLong();
        long first = in.getLong(), firstSequence = in.getLong(), revision = in.getLong(), held = in.getLong();
        int cooldown = in.getInt();
        var jump = new JumpDecision(in.getLong(), in.getLong(), enumValue(JumpOutcome.values(), in.get()));
        return new PredictionBoundary(epoch, generation, anchor, slot, first, firstSequence, revision, held,
                cooldown, jump, enumValue(StepOutcome.values(), in.get()));
    }

    private static void putBodyReferenceIntent(ByteBuffer out, BodyReferenceIntent intent) {
        out.putLong(intent.requestId()).put((byte) intent.action().ordinal());
        if (intent.action() == BodyReferenceAction.LOCK) {
            out.put((byte) intent.frame().orElseThrow().ordinal());
            putVector(out, intent.tangentHeading().orElseThrow());
        }
    }

    private static BodyReferenceIntent getBodyReferenceIntent(ByteBuffer in) {
        long requestId = in.getLong();
        BodyReferenceAction action = enumValue(BodyReferenceAction.values(), in.get());
        return switch (action) {
            case NONE -> new BodyReferenceIntent(requestId, action, Optional.empty(), Optional.empty());
            case LOCK -> BodyReferenceIntent.lock(requestId,
                    enumValue(CardinalFrame.values(), in.get()), getVector(in));
            case UNLOCK -> BodyReferenceIntent.unlock(requestId);
        };
    }

    private static void putBodyReferenceReceipt(ByteBuffer out, BodyReferenceReceipt receipt) {
        out.putLong(receipt.requestId()).put((byte) receipt.decision().ordinal())
                .putLong(receipt.acceptedLockRequestId());
        out.put((byte) (receipt.acceptedFrame().isPresent() ? 1 : 0));
        receipt.acceptedFrame().ifPresent(frame -> out.put((byte) frame.ordinal())
                .putDouble(receipt.tangentHeading().orElseThrow().x())
                .putDouble(receipt.tangentHeading().orElseThrow().y())
                .putDouble(receipt.tangentHeading().orElseThrow().z()));
        out.put((byte) (receipt.reason().isPresent() ? 1 : 0));
        receipt.reason().ifPresent(reason -> out.put((byte) reason.ordinal()));
    }

    private static BodyReferenceReceipt getBodyReferenceReceipt(ByteBuffer in) {
        long requestId = in.getLong();
        BodyReferenceDecision decision = enumValue(BodyReferenceDecision.values(), in.get());
        long acceptedLockRequestId = in.getLong();
        Optional<CardinalFrame> frame = Optional.empty();
        Optional<Vec3d> heading = Optional.empty();
        if (getBoolean(in)) {
            frame = Optional.of(enumValue(CardinalFrame.values(), in.get()));
            heading = Optional.of(getVector(in));
        }
        Optional<BodyReferenceReason> reason = getBoolean(in)
                ? Optional.of(enumValue(BodyReferenceReason.values(), in.get())) : Optional.empty();
        return new BodyReferenceReceipt(requestId, decision, acceptedLockRequestId, frame, heading, reason);
    }

    private static void putBodyReferenceState(ByteBuffer out, PlayerBodyReference.State state) {
        out.put((byte) (state.manualIntent().isPresent() ? 1 : 0));
        state.manualIntent().ifPresent(manual -> out.putLong(manual.requestId())
                .put((byte) manual.frame().ordinal()).putDouble(manual.tangentHeading().x())
                .putDouble(manual.tangentHeading().y()).putDouble(manual.tangentHeading().z()));
        out.put((byte) state.effective().source().ordinal()).put((byte) state.effective().frame().ordinal());
        putVector(out, state.effective().tangentHeading());
        out.put((byte) state.transition().ordinal());
        out.put((byte) (state.pendingFrame().isPresent() ? 1 : 0));
        state.pendingFrame().ifPresent(frame -> out.put((byte) frame.ordinal()));
        out.put((byte) (state.reason().isPresent() ? 1 : 0));
        state.reason().ifPresent(reason -> out.put((byte) reason.ordinal()));
    }

    private static PlayerBodyReference.State getBodyReferenceState(ByteBuffer in) {
        Optional<PlayerBodyReference.ManualIntent> manual = Optional.empty();
        if (getBoolean(in)) manual = Optional.of(new PlayerBodyReference.ManualIntent(in.getLong(),
                enumValue(CardinalFrame.values(), in.get()), getVector(in)));
        var source = enumValue(PlayerBodyReference.Source.values(), in.get());
        var effective = new PlayerBodyReference.Effective(source, enumValue(CardinalFrame.values(), in.get()), getVector(in));
        var transition = enumValue(PlayerBodyReference.Transition.values(), in.get());
        Optional<CardinalFrame> pendingFrame = getBoolean(in)
                ? Optional.of(enumValue(CardinalFrame.values(), in.get())) : Optional.empty();
        Optional<BodyReferenceReason> reason = getBoolean(in)
                ? Optional.of(enumValue(BodyReferenceReason.values(), in.get())) : Optional.empty();
        return new PlayerBodyReference.State(manual, effective, transition, pendingFrame, reason);
    }

    private static void putSnapshot(ByteBuffer out, PlayerPhysicalSnapshot snapshot) {
        out.putLong(snapshot.revision());
        PlayerBodyPlacement placement = snapshot.placement();
        if (placement.shape() instanceof PlayerBodyShape.Cardinal cardinal) {
            out.put((byte) CARDINAL_PLACEMENT_TAG).put((byte) cardinal.frame().ordinal());
            var dimensions = cardinal.dimensions();
            out.putDouble(dimensions.width()).putDouble(dimensions.height()).putDouble(dimensions.eyeHeight());
            Rotation3d q = placement.attitude();
            out.putDouble(q.x()).putDouble(q.y()).putDouble(q.z()).putDouble(q.w());
            putVector(out, placement.bounds().min());
            putVector(out, placement.bounds().max());
            putVector(out, placement.transport());
        } else if (placement.shape() instanceof PlayerBodyShape.FreeCapsule) {
            var free = (PlayerBodyShape.FreeCapsule) placement.shape();
            out.put((byte) (free.pronePose().isPresent() ? PRONE_FREE_CAPSULE_PLACEMENT_TAG
                    : free.eyeAnchor().isPresent() ? PROFILED_FREE_CAPSULE_PLACEMENT_TAG : FREE_CAPSULE_PLACEMENT_TAG));
            PlayerBodyShape.Dimensions dimensions = placement.shape().dimensions();
            out.putDouble(dimensions.width()).putDouble(dimensions.height()).putDouble(dimensions.eyeHeight());
            putRawQuaternion(out, FreeCapsulePlacementV1.RawQuaternion.from(placement.attitude()));
            FreeCapsulePlacementV1 proof = PlayerPlacementCodec.exportFreeV1(placement);
            putRawVector(out, proof.currentCenter());
            putRawVector(out, proof.storedHalfLine());
            putRawVector(out, proof.centerReference());
            putRawAabb(out, FreeCapsulePlacementV1.RawAabb.from(placement.bounds()));
            putRawVector(out, FreeCapsulePlacementV1.RawVec3.from(placement.transport()));
            if (proof.proof() instanceof FreeCapsulePlacementV1.Nominal) {
                out.put((byte) FreeCapsulePlacementV1.NOMINAL_TAG);
            } else if (proof.proof() instanceof FreeCapsulePlacementV1.AxisPlane axis) {
                out.put((byte) FreeCapsulePlacementV1.AXIS_PLANE_TAG);
                putRawVector(out, axis.realizedCenter());
                out.put((byte) axis.modifiedAxis()).put((byte) axis.outwardSign())
                        .putLong(axis.supportCoordinateBits());
            } else {
                throw new IllegalArgumentException("Unsupported FreeCapsule realization proof variant");
            }
            proof.bodyProfile().ifPresent(body -> {
                out.putLong(body.diameterBits()).putLong(body.lengthBits()).put((byte) body.eyeRecipeVersion());
                putRawVector(out, body.eyeCenter()); putRawVector(out, body.worldEye());
                putRawQuaternion(out, body.eyeAttitude());
                body.pronePose().ifPresent(pose -> out.put((byte) pose.kind().ordinal())
                        .put((byte) pose.referenceFrame().ordinal()));
            });
        } else {
            throw new IllegalArgumentException("Unsupported physical-state placement shape");
        }
        putVector(out, snapshot.worldVelocity());
        out.put((byte) (snapshot.contacts().isPresent() ? 1 : 0));
        snapshot.contacts().ifPresent(contact -> {
            out.put((byte) ((contact.onGround() ? 1 : 0) | (contact.horizontal() ? 2 : 0)
                    | (contact.vertical() ? 4 : 0) | (contact.nearFootLocomotion() ? 8 : 0)));
            out.putFloat(contact.fallDistance());
        });
    }

    private static SnapshotFrame getSnapshotFrame(ByteBuffer in, PlayerPhysicalSnapshot.Scope scope) {
        long revision = in.getLong();
        int placementTag = Byte.toUnsignedInt(in.get());
        if (placementTag == CARDINAL_PLACEMENT_TAG) {
            var frame = enumValue(CardinalFrame.values(), in.get());
            var dimensions = new PlayerBodyShape.Dimensions(in.getDouble(), in.getDouble(), in.getDouble());
            var rotation = new Rotation3d(in.getDouble(), in.getDouble(), in.getDouble(), in.getDouble());
            var bounds = new Aabb(getVector(in), getVector(in));
            var transport = getVector(in);
            var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, transport, rotation,
                    new PlayerBodyShape.Cardinal(frame, dimensions));
            Vec3d velocity = getVector(in);
            Optional<PlayerPhysicalSnapshot.Contacts> contacts = getContacts(in);
            return new CardinalSnapshotFrame(new PlayerPhysicalSnapshot(scope, revision, placement, velocity, contacts));
        }
        if (placementTag != FREE_CAPSULE_PLACEMENT_TAG && placementTag != PROFILED_FREE_CAPSULE_PLACEMENT_TAG
                && placementTag != PRONE_FREE_CAPSULE_PLACEMENT_TAG) {
            throw new IllegalArgumentException("Unknown physical-state placement tag");
        }
        long widthBits = in.getLong();
        long heightBits = in.getLong();
        long eyeHeightBits = in.getLong();
        var attitude = getRawQuaternion(in);
        var currentCenter = getRawVector(in);
        var storedHalfLine = getRawVector(in);
        var centerReference = getRawVector(in);
        var proxy = getRawAabb(in);
        var transport = getRawVector(in);
        int proofTag = Byte.toUnsignedInt(in.get());
        FreeCapsulePlacementV1.Proof proof;
        if (proofTag == FreeCapsulePlacementV1.NOMINAL_TAG) {
            proof = new FreeCapsulePlacementV1.Nominal();
        } else if (proofTag == FreeCapsulePlacementV1.AXIS_PLANE_TAG) {
            var realizedCenter = getRawVector(in);
            int modifiedAxis = Byte.toUnsignedInt(in.get());
            int outwardSign = in.get();
            long supportCoordinateBits = in.getLong();
            if (modifiedAxis > 2) throw new IllegalArgumentException("Invalid FreeCapsule proof axis");
            if (outwardSign != -1 && outwardSign != 1) {
                throw new IllegalArgumentException("Invalid FreeCapsule proof outward sign");
            }
            proof = new FreeCapsulePlacementV1.AxisPlane(realizedCenter, modifiedAxis, outwardSign,
                    supportCoordinateBits);
        } else {
            throw new IllegalArgumentException("Unknown FreeCapsule realization proof tag");
        }
        Optional<FreeCapsulePlacementV1.BodyProfile> body = Optional.empty();
        if (placementTag == PROFILED_FREE_CAPSULE_PLACEMENT_TAG || placementTag == PRONE_FREE_CAPSULE_PLACEMENT_TAG) {
            long diameter = in.getLong(), length = in.getLong();
            int recipe = Byte.toUnsignedInt(in.get());
            var eyeCenter = getRawVector(in);
            var worldEye = getRawVector(in);
            var eyeAttitude = getRawQuaternion(in);
            Optional<PlayerBodyShape.PronePose> prone = Optional.empty();
            if (placementTag == PRONE_FREE_CAPSULE_PLACEMENT_TAG) {
                int kind = Byte.toUnsignedInt(in.get()), frame = Byte.toUnsignedInt(in.get());
                if (kind >= PlayerBodyShape.PronePose.Kind.values().length || frame >= CardinalFrame.values().length)
                    throw new IllegalArgumentException("Unknown prone body kind/reference frame");
                prone = Optional.of(new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.values()[kind],
                        CardinalFrame.values()[frame]));
            }
            body = Optional.of(new FreeCapsulePlacementV1.BodyProfile(diameter, length, recipe,
                    eyeCenter, worldEye, eyeAttitude, prone));
        }
        var placement = new FreeCapsulePlacementV1(currentCenter, storedHalfLine, centerReference, proof, body);
        var worldVelocity = getRawVector(in);
        Optional<PlayerPhysicalSnapshot.Contacts> contacts = getContacts(in);
        return new FreeCapsuleSnapshotFrame(revision, widthBits, heightBits, eyeHeightBits, attitude,
                placement, proxy, transport, worldVelocity, contacts);
    }

    private static Optional<PlayerPhysicalSnapshot.Contacts> getContacts(ByteBuffer in) {
        if (!getBoolean(in)) return Optional.empty();
        int flags = Byte.toUnsignedInt(in.get());
        if (flags > 15) throw new IllegalArgumentException("Unknown contact flags");
        return Optional.of(new PlayerPhysicalSnapshot.Contacts((flags & 1) != 0,
                (flags & 2) != 0, (flags & 4) != 0, in.getFloat(), (flags & 8) != 0));
    }

    private static void putRawVector(ByteBuffer out, FreeCapsulePlacementV1.RawVec3 vector) {
        out.putLong(vector.xBits()).putLong(vector.yBits()).putLong(vector.zBits());
    }
    private static FreeCapsulePlacementV1.RawVec3 getRawVector(ByteBuffer in) {
        return new FreeCapsulePlacementV1.RawVec3(in.getLong(), in.getLong(), in.getLong());
    }
    private static void putRawQuaternion(ByteBuffer out, FreeCapsulePlacementV1.RawQuaternion quaternion) {
        out.putLong(quaternion.xBits()).putLong(quaternion.yBits()).putLong(quaternion.zBits())
                .putLong(quaternion.wBits());
    }
    private static FreeCapsulePlacementV1.RawQuaternion getRawQuaternion(ByteBuffer in) {
        return new FreeCapsulePlacementV1.RawQuaternion(in.getLong(), in.getLong(), in.getLong(), in.getLong());
    }
    private static void putRawAabb(ByteBuffer out, FreeCapsulePlacementV1.RawAabb bounds) {
        putRawVector(out, bounds.min());
        putRawVector(out, bounds.max());
    }
    private static FreeCapsulePlacementV1.RawAabb getRawAabb(ByteBuffer in) {
        return new FreeCapsulePlacementV1.RawAabb(getRawVector(in), getRawVector(in));
    }

    private static boolean isFinite(FreeCapsulePlacementV1.RawVec3 vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    private static void putOperation(ByteBuffer out, OperationId value) {
        out.putLong(value.nonce()).putLong(value.epoch()).putLong(value.serial()).put((byte) value.operation().ordinal());
    }
    private static OperationId getOperation(ByteBuffer in) {
        return new OperationId(in.getLong(), in.getLong(), in.getLong(), enumValue(BoostProtocol.Operation.values(), in.get()));
    }
    private static boolean getBoolean(ByteBuffer in) {
        byte value = in.get();
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid boolean tag");
        return value == 1;
    }
    private static <E> E enumValue(E[] values, byte encoded) {
        int index = Byte.toUnsignedInt(encoded);
        if (index >= values.length) throw new IllegalArgumentException("Invalid enum tag");
        return values[index];
    }
    private static void putUuid(ByteBuffer out, UUID value) { out.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer in) { return new UUID(in.getLong(), in.getLong()); }
    private static void putVector(ByteBuffer out, Vec3d v) { out.putDouble(v.x()).putDouble(v.y()).putDouble(v.z()); }
    private static Vec3d getVector(ByteBuffer in) { return new Vec3d(in.getDouble(), in.getDouble(), in.getDouble()); }
    private static double length(Vec3d v) { return Math.hypot(Math.hypot(v.x(), v.y()), v.z()); }
    private static void requireFinite(Vec3d v) {
        Objects.requireNonNull(v, "vector");
        if (!Double.isFinite(v.x()) || !Double.isFinite(v.y()) || !Double.isFinite(v.z())) {
            throw new IllegalArgumentException("Non-finite vector");
        }
    }
}
