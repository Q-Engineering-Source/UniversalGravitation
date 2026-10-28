package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Work;
import java.util.Objects;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.Optional;

/**
 * Transaction-local CHB work accounting. Production movement uses recording-only
 * queries; explicit bounded profiles remain available to callers that request one.
 * No result here authorizes world movement or ownership.
 */
final class CapsuleContactQueryBudget {
    enum Operation { CONTACT, TRANSLATION_BOUNDARY, ORIGINAL_PREFIX, ELONGATED_ORIGINAL_PREFIX, AXIS_EXIT_CANDIDATES, AXIS_EXIT_CHECK }
    record Observation(long call, Operation operation, String context, Capsule capsule, Aabb obstacle,
                       String outcome, String reason, Optional<Work> executedWork, boolean cacheHit) { }
    record Diagnostics(String mode, Map<Operation, Long> invocations, long geometryCacheHits,
                       Observation lastQuery, Observation lastUnresolvedQuery) { }
    record Totals(long exactAttempts, long exactOps, int peakExactBits,
                  long staticAttempts, long staticEvaluations, long staticCandidateUnits,
                  long castAttempts, long castQueries, long castEvaluations,
                  long castCandidateUnits, long totalCandidateUnits, long refinementSteps) { }

    static final class BudgetExhausted extends IllegalStateException {
        BudgetExhausted(long calls, Totals work) {
            super("Capsule contact transaction budget unavailable after calls=" + calls + " work=" + work);
        }
    }

    private final QueryLimits limits;
    private final int maxCalls;
    // Bounded by admitted calls, not a new resource allowance. No world/producer authority is stored.
    private final Map<GeometryQueryKey, ContactResult> resolvedGeometry = new HashMap<>();
    private FreeOwnerTransaction geometryOwner;
    private long calls;
    private final Map<Operation, Long> invocations = new EnumMap<>(Operation.class);
    private long geometryCacheHits;
    private Observation lastQuery;
    private Observation lastUnresolvedQuery;
    private boolean terminal;
    private Totals work = new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    CapsuleContactQueryBudget(QueryLimits limits, int maxCalls) {
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!limits.recordsOnly() && maxCalls <= 0) throw new IllegalArgumentException("maxCalls must be positive");
        this.maxCalls = maxCalls;
    }

    ContactResult query(Capsule capsule, Aabb obstacle, ContactAccuracy accuracy) {
        return query(capsule, obstacle, accuracy, "collision-or-support");
    }

    ContactResult query(Capsule capsule, Aabb obstacle, ContactAccuracy accuracy, String context) {
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(accuracy, "accuracy");
        Objects.requireNonNull(context, "context");
        QueryLimits remaining = enter(Operation.CONTACT);
        return executeQuery(capsule, obstacle, accuracy, remaining, context);
    }

    /** Reuse only immutable geometry; caller must still capture and validate world/owner evidence.
     * The returned result retains its original Work provenance. Ledger totals count actual CHB work,
     * not a second evaluation that did not occur; every access still consumes one admitted call. */
    ContactResult queryReusingGeometry(FreeOwnerTransaction transaction,
            Capsule capsule, Aabb obstacle, ContactAccuracy accuracy) {
        Objects.requireNonNull(transaction, "transaction").requireCurrent();
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(accuracy, "accuracy");
        QueryLimits remaining = enter(Operation.CONTACT);
        // The ledger survives transactions in a SharedFrame; proof reuse must not.
        if (geometryOwner != transaction) {
            resolvedGeometry.clear();
            geometryOwner = transaction;
        }
        var key = GeometryQueryKey.of(capsule, obstacle, accuracy);
        var cached = resolvedGeometry.get(key);
        if (cached != null) {
            geometryCacheHits++;
            observe(Operation.CONTACT, "transaction-geometry-reuse", capsule, obstacle,
                    cached.relation().name(), cached.reason().map(Enum::name).orElse("none"), null, true);
            if (!limits.recordsOnly() && calls >= maxCalls) terminal = true;
            return cached;
        }
        var result = executeQuery(capsule, obstacle, accuracy, remaining, "transaction-geometry");
        transaction.requireCurrent();
        if (result.relation() != CapsuleAabbContact3d.Relation.UNRESOLVED)
            resolvedGeometry.putIfAbsent(key, result);
        return result;
    }

    private ContactResult executeQuery(Capsule capsule, Aabb obstacle, ContactAccuracy accuracy,
            QueryLimits remaining, String context) {
        try {
            ContactResult result = CapsuleAabbContact3d.query(capsule, obstacle, accuracy, remaining);
            charge(result.work());
            observe(Operation.CONTACT, context, capsule, obstacle, result.relation().name(),
                    result.reason().map(Enum::name).orElse("none"), result.work(), false);
            return result;
        } catch (RuntimeException | Error failure) {
            // The API did not expose partial Work: continued use would silently refund unknown work.
            terminal = true;
            throw failure;
        }
    }

    CapsuleAabbContact3d.AxisExitResult axisExitCandidates(Capsule capsule, Aabb obstacle, ContactAccuracy accuracy) {
        QueryLimits remaining = enter(Operation.AXIS_EXIT_CANDIDATES);
        try {
            var result = CapsuleAabbContact3d.axisExitCandidates(capsule, obstacle, accuracy, remaining);
            charge(result.work());
            observe(Operation.AXIS_EXIT_CANDIDATES, "initial-overlap", capsule, obstacle,
                    result.kind().name(), result.reason().map(Enum::name).orElse("none"), result.work(), false);
            return result;
        } catch (RuntimeException | Error failure) { terminal = true; throw failure; }
    }

    CapsuleAabbContact3d.AxisExitCheck checkAxisExit(Capsule capsule, Aabb obstacle,
            CapsuleAabbContact3d.Face face, Capsule actualEnd, ContactAccuracy accuracy) {
        QueryLimits remaining = enter(Operation.AXIS_EXIT_CHECK);
        try {
            var result = CapsuleAabbContact3d.checkAxisExit(capsule, obstacle, face, actualEnd, accuracy, remaining);
            charge(result.work());
            observe(Operation.AXIS_EXIT_CHECK, "face=" + face, capsule, obstacle,
                    result.kind().name(), result.reason().map(Enum::name).orElse("none"), result.work(), false);
            return result;
        } catch (RuntimeException | Error failure) { terminal = true; throw failure; }
    }

    BoundaryResult refineTranslationBoundary(Capsule capsule, Vec3d delta, Aabb obstacle,
            BoundarySearchHint hint, StoredInstallCandidate installation, ContactAccuracy accuracy) {
        Objects.requireNonNull(capsule, "capsule");
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(accuracy, "accuracy");
        QueryLimits remaining = enter(Operation.TRANSLATION_BOUNDARY);
        try {
            BoundaryResult result = CapsuleAabbContact3d.refineTranslationBoundary(
                    capsule, delta, obstacle, hint, installation, accuracy, remaining);
            charge(result.work());
            observe(Operation.TRANSLATION_BOUNDARY, "delta=" + delta, capsule, obstacle,
                    result.kind().name(), result.reason().map(Enum::name).orElse("none"), result.work(), false);
            return result;
        } catch (RuntimeException | Error failure) {
            terminal = true;
            throw failure;
        }
    }

    CapsuleAabbContact3d.OriginalPrefixResult proveOriginalTranslationPrefix(Capsule start,
            Vec3d originalDelta, double fraction, Aabb obstacle, StoredInstallCandidate installation,
            ContactAccuracy accuracy) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(originalDelta, "originalDelta");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(accuracy, "accuracy");
        QueryLimits remaining = enter(Operation.ORIGINAL_PREFIX);
        try {
            var result = CapsuleAabbContact3d.proveOriginalTranslationPrefix(
                    start, originalDelta, fraction, obstacle, installation, accuracy, remaining);
            charge(result.work());
            observe(Operation.ORIGINAL_PREFIX, "delta=" + originalDelta + ",fraction=" + fraction,
                    start, obstacle, result.proof().isPresent() ? "PROVED" : "UNRESOLVED",
                    result.reason().map(Enum::name).orElse("none"), result.work(), false);
            if (result.reason().filter(reason -> reason == CapsuleAabbContact3d.Reason.EXACT_WORK_LIMIT).isPresent())
                terminal = true;
            return result;
        } catch (RuntimeException | Error failure) {
            terminal = true;
            throw failure;
        }
    }

    CapsuleAabbContact3d.ElongatedOriginalPrefixResult proveOriginalCapsuleTranslationPrefix(Capsule start,
            Vec3d originalDelta, double fraction, Aabb obstacle, StoredInstallCandidate installation,
            ContactAccuracy accuracy) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(originalDelta, "originalDelta");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(accuracy, "accuracy");
        QueryLimits remaining = enter(Operation.ELONGATED_ORIGINAL_PREFIX);
        try {
            var result = CapsuleAabbContact3d.proveOriginalCapsuleTranslationPrefix(
                    start, originalDelta, fraction, obstacle, installation, accuracy, remaining);
            charge(result.work());
            observe(Operation.ELONGATED_ORIGINAL_PREFIX, "delta=" + originalDelta + ",fraction=" + fraction,
                    start, obstacle, result.proof().isPresent() ? "PROVED" : "UNRESOLVED",
                    result.reason().map(Enum::name).orElse("none"), result.work(), false);
            if (result.reason().filter(reason -> reason == CapsuleAabbContact3d.Reason.EXACT_WORK_LIMIT).isPresent())
                terminal = true;
            if (result.proof().isPresent()) {
                var proof = result.proof().orElseThrow();
                if (proof.startCapsule() != start || proof.originalDelta() != originalDelta
                        || proof.obstacle() != obstacle || proof.candidate() != installation
                        || proof.accuracy() != accuracy || proof.limits() != remaining
                        || Double.doubleToRawLongBits(proof.endFraction()) != Double.doubleToRawLongBits(fraction)
                        || !proof.work().equals(result.work()))
                    throw new IllegalStateException("original body prefix lost its exact query inputs");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            terminal = true;
            throw failure;
        }
    }

    private QueryLimits enter(Operation operation) {
        calls = Math.incrementExact(calls);
        invocations.merge(operation, 1L, Math::addExact);
        if (limits.recordsOnly()) {
            if (terminal) throw new IllegalStateException("Contact query owner failed during an earlier invocation");
            return limits;
        }
        if (terminal || calls > maxCalls || depleted()) {
            terminal = true;
            throw new BudgetExhausted(calls, work);
        }
        return new QueryLimits(limits.maxExactBits(),
                (int) (limits.maxExactOps() - work.exactOps()),
                (int) (limits.maxStaticEvaluations() - work.staticEvaluations()),
                (int) (limits.maxCastQueries() - work.castQueries()),
                (int) (limits.maxCastEvaluations() - work.castEvaluations()),
                (int) (limits.maxCandidateUnits() - work.totalCandidateUnits()),
                (int) (limits.maxRefinementSteps() - work.refinementSteps()));
    }

    private void charge(Work next) {
        work = new Totals(work.exactAttempts() + next.exactAttempts(), work.exactOps() + next.exactOps(),
                Math.max(work.peakExactBits(), next.peakExactBits()),
                work.staticAttempts() + next.staticAttempts(), work.staticEvaluations() + next.staticEvaluations(),
                work.staticCandidateUnits() + next.staticCandidateUnits(), work.castAttempts() + next.castAttempts(),
                work.castQueries() + next.castQueries(), work.castEvaluations() + next.castEvaluations(),
                work.castCandidateUnits() + next.castCandidateUnits(),
                work.totalCandidateUnits() + next.totalCandidateUnits(), work.refinementSteps() + next.refinementSteps());
        if (!limits.recordsOnly() && (depleted() || calls >= maxCalls)) terminal = true;
    }

    private boolean depleted() {
        return work.exactOps() >= limits.maxExactOps()
                || work.staticEvaluations() >= limits.maxStaticEvaluations()
                || work.castQueries() >= limits.maxCastQueries()
                || work.castEvaluations() >= limits.maxCastEvaluations()
                || work.totalCandidateUnits() >= limits.maxCandidateUnits()
                || work.refinementSteps() >= limits.maxRefinementSteps();
    }

    private void observe(Operation operation, String context, Capsule capsule, Aabb obstacle,
            String outcome, String reason, Work executedWork, boolean cacheHit) {
        lastQuery = new Observation(calls, operation, context, capsule, obstacle, outcome, reason,
                Optional.ofNullable(executedWork), cacheHit);
        if ("UNRESOLVED".equals(outcome)) lastUnresolvedQuery = lastQuery;
    }

    Diagnostics diagnostics() {
        return new Diagnostics(limits.recordsOnly() ? "RECORDING_ONLY" : "EXPLICIT_BOUNDED",
                Map.copyOf(invocations), geometryCacheHits, lastQuery, lastUnresolvedQuery);
    }

    private record GeometryQueryKey(List<Long> bits) {
        static GeometryQueryKey of(Capsule capsule, Aabb box, ContactAccuracy accuracy) {
            var a = capsule.centerline().start(); var b = capsule.centerline().end();
            var low = box.min(); var high = box.max();
            return new GeometryQueryKey(List.of(raw(a.x()), raw(a.y()), raw(a.z()),
                    raw(b.x()), raw(b.y()), raw(b.z()), raw(capsule.radius()),
                    raw(low.x()), raw(low.y()), raw(low.z()), raw(high.x()), raw(high.y()), raw(high.z()),
                    raw(accuracy.maxClearanceWidthMeters()), raw(accuracy.maxWitnessErrorMeters()),
                    raw(accuracy.maxPathWidthMeters())));
        }
        private static long raw(double value) { return Double.doubleToRawLongBits(value); }
    }

    long calls() { return calls; }
    boolean terminal() { return terminal; }
    Totals work() { return work; }
}
