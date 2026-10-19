package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.Kind;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.UnresolvedReason;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.CapsulePlacementMath.Translation;

/**
 * One fixed-attitude translation against a complete, immutable candidate list.
 *
 * <p>This module owns padded fraction aggregation and final geometry checks, not
 * the Minecraft world, candidate discovery, sliding retries or state commits.
 * The caller must collect every candidate for tube.sweptBounds(), preserve its
 * world revision, reserve displacement before calling and never refund on a
 * limited/rejected result. Native movement must not solve this result again.
 */
public final class CapsuleTranslationResolver {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);

    private CapsuleTranslationResolver() {
    }

    public record Limits(int maxCandidates, int maxQueries) {
        public Limits {
            if (maxCandidates < 1 || maxCandidates > 4096 || maxQueries < 1 || maxQueries > 16384) {
                throw new IllegalArgumentException("query limits exceed the bounded consumer profile");
            }
        }
    }

    public enum Disposition { FULL, LIMITED, UNCHANGED, REJECTED }
    public enum Stage { PADDED_PATH, ACTUAL_INITIAL_PATH, ACTUAL_FINAL_POSE }
    public enum FailureKind { CANDIDATE_LIMIT, QUERY_LIMIT, UNSUPPORTED_DOMAIN, NO_USABLE_PATH, FINAL_RELATION, CONTACT_PROOF }

    /** Ordinal and actual box are preserved within this candidate snapshot. */
    public record QueryEvidence(int candidateIndex, Aabb obstacle, Stage stage, CapsuleAabbCastResult3d query) {
    }

    public record Failure(FailureKind kind, int candidateIndex, Optional<IllegalArgumentException> cause) {
    }

    /** Constructible only by this resolver; still not a world/budget commit receipt. */
    public static final class Result {
        private final Translation request;
        private final Pose end;
        private final double fraction;
        private final Disposition disposition;
        private final Optional<Failure> failure;
        private final List<QueryEvidence> evidence;

        private Result(Translation request, Pose end, double fraction, Disposition disposition,
                       Failure failure, List<QueryEvidence> evidence) {
            this.request = request;
            this.end = end;
            this.fraction = fraction;
            this.disposition = disposition;
            this.failure = Optional.ofNullable(failure);
            this.evidence = List.copyOf(evidence);
        }

        public Translation request() { return request; }
        public Pose end() { return end; }
        public double fraction() { return fraction; }
        public Disposition disposition() { return disposition; }
        public Optional<Failure> failure() { return failure; }
        public List<QueryEvidence> evidence() { return evidence; }
        public int queriesUsed() { return evidence.size(); }
    }

    public static Result resolve(Translation tube, List<Aabb> candidates, Limits limits) {
        Objects.requireNonNull(tube, "tube");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(limits, "limits");
        if (!(tube.start().placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("cardinal AABB movement cannot use its inscribed capsule as the collider");
        }
        Attempt attempt = new Attempt(tube, limits);
        if (candidates.size() > limits.maxCandidates()) return attempt.reject(FailureKind.CANDIDATE_LIMIT, -1);
        // Validate/copy only after the finite-size guard. No caller mutation during casts.
        List<Aabb> snapshot = List.copyOf(candidates);
        try {
            return attempt.resolve(snapshot);
        } catch (QueryLimitReached exhausted) {
            return attempt.reject(FailureKind.QUERY_LIMIT, exhausted.candidateIndex);
        }
    }

    private static final class Attempt {
        private final Translation tube;
        private final Limits limits;
        private final List<QueryEvidence> evidence = new ArrayList<>();

        private Attempt(Translation tube, Limits limits) {
            this.tube = tube;
            this.limits = limits;
        }

        private Result resolve(List<Aabb> snapshot) {
            List<InitialEvidence> initials = new ArrayList<>(snapshot.size());
            double fraction = 1;
            for (int index = 0; index < snapshot.size(); index++) {
                Aabb box = snapshot.get(index);
                var padded = query(index, box, Stage.PADDED_PATH, tube.queryCapsule(), tube.delta());
                if (unsupported(padded)) return reject(FailureKind.UNSUPPORTED_DOMAIN, index);
                CapsuleAabbCastResult3d actualInitial = null;
                boolean requiresContactProof = false;
                double limit;
                if (padded.safePrefixEnd().isPresent()) {
                    limit = padded.safePrefixEnd().getAsDouble();
                } else if (initialClear(padded)) {
                    limit = 1;
                } else {
                    // The first consumer slice refines only explicit padding
                    // overlap. Ambiguity/inward/near-band are not contact permits.
                    if (padded.kind() != Kind.INITIAL_OVERLAP) return reject(FailureKind.NO_USABLE_PATH, index);
                    actualInitial = query(index, box, Stage.ACTUAL_INITIAL_PATH, tube.start().capsule(), tube.delta());
                    if (unsupported(actualInitial)) return reject(FailureKind.UNSUPPORTED_DOMAIN, index);
                    if (!usableInitialContact(actualInitial)) return reject(FailureKind.NO_USABLE_PATH, index);
                    requiresContactProof = true;
                    limit = 1;
                }
                fraction = Math.min(fraction, limit);
                if (fraction == 0) return reject(FailureKind.NO_USABLE_PATH, index);
                initials.add(new InitialEvidence(actualInitial, requiresContactProof));
            }
            Pose end;
            try {
                end = CapsulePlacementMath.atPrefix(tube, fraction);
            } catch (IllegalArgumentException unsupportedConstruction) {
                return new Result(tube, tube.start(), 0, Disposition.REJECTED,
                        new Failure(FailureKind.UNSUPPORTED_DOMAIN, -1, Optional.of(unsupportedConstruction)), evidence);
            }
            for (int index = 0; index < snapshot.size(); index++) {
                Aabb box = snapshot.get(index);
                var finalQuery = query(index, box, Stage.ACTUAL_FINAL_POSE, end.capsule(), ZERO);
                if (unsupported(finalQuery)) return reject(FailureKind.UNSUPPORTED_DOMAIN, index);
                if (finalQuery.kind() != Kind.CLEAR_SEPARATED && !initialClear(finalQuery)) {
                    return reject(FailureKind.FINAL_RELATION, index);
                }
                InitialEvidence initial = initials.get(index);
                if (initial.requiresContactProof || initialClear(finalQuery)) {
                    var actualInitial = initial.query;
                    if (actualInitial == null) {
                        actualInitial = query(index, box, Stage.ACTUAL_INITIAL_PATH, tube.start().capsule(), tube.delta());
                    }
                    if (unsupported(actualInitial)) return reject(FailureKind.UNSUPPORTED_DOMAIN, index);
                    if (!usableInitialContact(actualInitial)
                            || !CapsulePlacementMath.preservesInitialContact(tube, end, box,
                            actualInitial.normalEstimate().orElseThrow(), actualInitial.scale().orElseThrow().contactBandWidth())) {
                        return reject(FailureKind.CONTACT_PROOF, index);
                    }
                }
            }
            Disposition disposition = end == tube.start() ? Disposition.UNCHANGED
                    : fraction == 1 ? Disposition.FULL : Disposition.LIMITED;
            return new Result(tube, end, fraction, disposition, null, evidence);
        }

        private CapsuleAabbCastResult3d query(int index, Aabb box, Stage stage, Capsule capsule, Vec3d delta) {
            var result = CapsuleAabbCast3d.cast(capsule, delta, box);
            evidence.add(new QueryEvidence(index, box, stage, result));
            return result;
        }

        private Result reject(FailureKind kind, int index) {
            return new Result(tube, tube.start(), 0, Disposition.REJECTED,
                    new Failure(kind, index, Optional.empty()), evidence);
        }
    }

    private record InitialEvidence(CapsuleAabbCastResult3d query, boolean requiresContactProof) {
    }

    private static boolean initialClear(CapsuleAabbCastResult3d query) {
        return query.kind() == Kind.CLEAR_INITIAL_OUTWARD || query.kind() == Kind.CLEAR_INITIAL_TANGENT;
    }

    private static boolean usableInitialContact(CapsuleAabbCastResult3d query) {
        return initialClear(query) && query.normalEstimate().isPresent() && query.scale().isPresent();
    }

    private static boolean unsupported(CapsuleAabbCastResult3d query) {
        return query.reason().orElse(null) == UnresolvedReason.UNSUPPORTED_DOMAIN;
    }

    private static final class QueryLimitReached extends RuntimeException {
        private final int candidateIndex;

        private QueryLimitReached(int candidateIndex) {
            super("capsule query budget exhausted");
            this.candidateIndex = candidateIndex;
        }
    }
}
