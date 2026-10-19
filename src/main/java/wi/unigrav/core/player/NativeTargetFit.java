package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import wi.unigrav.core.player.CollisionQueryFootprint.Limits;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;

/** Preinstall native observation only; an empty observation grants no installation or fit permission. */
final class NativeTargetFit {
    static final Limits SCAN = new Limits(16_384, 16, 4_096, 4_096);

    private NativeTargetFit() {
    }

    sealed interface Result permits Observed, Rejected, Unavailable {
    }

    enum Reason {
        TARGET_OCCUPIED, TARGET_NOT_CARDINAL, SOURCE_NOT_FREE_CAPSULE,
        FOREIGN_TRANSACTION, ACCEPTED_SNAPSHOT_CHANGED, TARGET_CHANGED,
        ACTOR_SCOPE, DEFERRED_WORLD, QUERY_FOOTPRINT, MISSING_CHUNK, CHUNK_NOT_READY,
        ROOT_ENTITY_LIMIT, CANDIDATE_LIMIT, CONTEXT_CHANGED, MALFORMED_COLLIDER,
        NATIVE_QUERY_FAILED, NATIVE_WORK_EXHAUSTED, NATIVE_ATTEMPT_LIMIT
    }

    record Rejected(Reason reason, WorldCollisionCandidates.Snapshot observation) implements Result {
        Rejected {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(observation, "observation");
        }
    }

    record Unavailable(Reason reason, String detail,
                       Optional<CollisionQueryFootprint.Column> column,
                       Optional<RuntimeException> cause) implements Result {
        Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** Private construction and exact object bindings keep observations from becoming caller-minted authority. */
    static final class Observed implements Result {
        private final FreeOwnerTransaction transaction;
        private final PlayerPhysicalSnapshot accepted;
        private final long acceptedRevision;
        private final PlayerBodyPlacement targetPlacement;
        private final Limits limits;
        private final WorldCollisionCandidates.Snapshot snapshot;

        private Observed(FreeOwnerTransaction transaction, PlayerPhysicalSnapshot accepted,
                         PlayerBodyPlacement targetPlacement, Limits limits,
                         WorldCollisionCandidates.Snapshot snapshot) {
            this.transaction = transaction;
            this.accepted = accepted;
            this.acceptedRevision = accepted.revision();
            this.targetPlacement = targetPlacement;
            this.limits = limits;
            this.snapshot = snapshot;
        }

        WorldCollisionCandidates.Snapshot snapshot() {
            return snapshot;
        }

        /** Repeats the actual strict query with the same transaction and exact proposed placement. */
        Result recapture(FreeOwnerTransaction candidateTransaction, PlayerBodyPlacement exactTargetPlacement) {
            Objects.requireNonNull(candidateTransaction, "candidateTransaction");
            Objects.requireNonNull(exactTargetPlacement, "exactTargetPlacement");
            if (candidateTransaction != transaction) {
                return unavailable(Reason.FOREIGN_TRANSACTION,
                        "native target observation belongs to a different transaction");
            }
            if (candidateTransaction.accepted() != accepted
                    || candidateTransaction.accepted().revision() != acceptedRevision) {
                return unavailable(Reason.ACCEPTED_SNAPSHOT_CHANGED,
                        "native target observation no longer has its exact accepted source snapshot");
            }
            if (exactTargetPlacement != targetPlacement) {
                return unavailable(Reason.TARGET_CHANGED,
                        "native target observation belongs to a different placement object");
            }
            return observe(candidateTransaction, accepted, targetPlacement, limits, snapshot);
        }
    }

    static Result prepare(FreeOwnerTransaction transaction, PlayerBodyPlacement exactTargetPlacement, Limits limits) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(exactTargetPlacement, "exactTargetPlacement");
        Objects.requireNonNull(limits, "limits");
        try {
            transaction.requireCurrent();
        } catch (OwnerContextChangedException invalid) {
            return unavailable(Reason.CONTEXT_CHANGED,
                    "Free owner transaction is not current before native target observation", invalid);
        } catch (RuntimeException invalid) {
            return unavailable(Reason.NATIVE_QUERY_FAILED,
                    "Free owner transaction is not current before native target observation", invalid);
        }
        PlayerPhysicalSnapshot accepted = transaction.accepted();
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            return unavailable(Reason.SOURCE_NOT_FREE_CAPSULE,
                    "native target observation requires an accepted FreeCapsule source");
        }
        if (!(exactTargetPlacement.shape() instanceof PlayerBodyShape.Cardinal)) {
            return unavailable(Reason.TARGET_NOT_CARDINAL,
                    "native target observation requires an exact Cardinal placement");
        }
        return observe(transaction, accepted, exactTargetPlacement, limits, null);
    }

    private static Result observe(FreeOwnerTransaction transaction, PlayerPhysicalSnapshot accepted,
                                  PlayerBodyPlacement exactTargetPlacement, Limits limits,
                                  WorldCollisionCandidates.Snapshot previous) {
        WorldCollisionCandidates.Capture capture = WorldCollisionCandidates.capture(
                transaction, exactTargetPlacement.bounds(), QueryPolicy.NATIVE_STRICT, limits);
        if (capture instanceof WorldCollisionCandidates.Unavailable unavailable) {
            return unavailable(unavailable);
        }
        WorldCollisionCandidates.Snapshot snapshot = (WorldCollisionCandidates.Snapshot) capture;
        Optional<WorldCollisionCandidates.Unavailable> contextFailure =
                snapshot.strictNativeContextFailure(accepted, exactTargetPlacement.bounds());
        if (contextFailure.isPresent()) return unavailable(contextFailure.orElseThrow());
        if (!snapshot.boxes().isEmpty()) return new Rejected(Reason.TARGET_OCCUPIED, snapshot);
        if (previous != null && !previous.sameNativeObservation(snapshot)) {
            return unavailable(Reason.CONTEXT_CHANGED,
                    "strict target observation changed during exact-placement recapture");
        }
        try {
            transaction.requireCurrent();
        } catch (OwnerContextChangedException invalid) {
            return unavailable(Reason.CONTEXT_CHANGED,
                    "Free owner transaction changed after native target observation", invalid);
        } catch (RuntimeException invalid) {
            return unavailable(Reason.NATIVE_QUERY_FAILED,
                    "Free owner transaction changed after native target observation", invalid);
        }
        return new Observed(transaction, accepted, exactTargetPlacement, limits, snapshot);
    }

    private static Unavailable unavailable(WorldCollisionCandidates.Unavailable failure) {
        return new Unavailable(map(failure.reason()), failure.detail(), failure.column(), failure.cause());
    }

    private static Unavailable unavailable(Reason reason, String detail) {
        return new Unavailable(reason, detail, Optional.empty(), Optional.empty());
    }

    private static Unavailable unavailable(Reason reason, String detail, RuntimeException cause) {
        return new Unavailable(reason, detail, Optional.empty(), Optional.of(cause));
    }

    private static Reason map(WorldCollisionCandidates.Reason reason) {
        return switch (reason) {
            case ACTOR_SCOPE -> Reason.ACTOR_SCOPE;
            case DEFERRED_WORLD -> Reason.DEFERRED_WORLD;
            case QUERY_FOOTPRINT -> Reason.QUERY_FOOTPRINT;
            case MISSING_CHUNK -> Reason.MISSING_CHUNK;
            case CHUNK_NOT_READY -> Reason.CHUNK_NOT_READY;
            case ROOT_ENTITY_LIMIT -> Reason.ROOT_ENTITY_LIMIT;
            case CANDIDATE_LIMIT -> Reason.CANDIDATE_LIMIT;
            case CONTEXT_CHANGED -> Reason.CONTEXT_CHANGED;
            case MALFORMED_COLLIDER -> Reason.MALFORMED_COLLIDER;
            case NATIVE_QUERY_FAILED -> Reason.NATIVE_QUERY_FAILED;
            case NATIVE_WORK_EXHAUSTED -> Reason.NATIVE_WORK_EXHAUSTED;
            case NATIVE_ATTEMPT_LIMIT -> Reason.NATIVE_ATTEMPT_LIMIT;
        };
    }
}
