package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleContactQueryBudgetTest {
    private static final Aabb BOX = new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1));
    private static final Aabb OTHER = new Aabb(new Vec3d(0, 0, 2), new Vec3d(1, 1, 3));
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits LIMITS = new QueryLimits(8192, 20000, 128, 32, 2336, 40000, 64);
    private static final Capsule START = point(-2, 0.5, 0.5, 0.25);

    @Test void twoActualObstaclesAccumulateEveryCounterAndPeakIsMaximum() {
        var budget = new CapsuleContactQueryBudget(LIMITS, 8);
        var first = budget.query(START, BOX, ACCURACY);
        var second = budget.query(START, OTHER, ACCURACY);
        assertEquals(Relation.SEPARATED, first.relation());
        assertEquals(Relation.SEPARATED, second.relation());
        assertTrue(first.work().peakExactBits() > 0);
        assertTrue(second.work().peakExactBits() > 0);
        assertTotals(first.work(), second.work(), budget.work());
        assertEquals(2, budget.calls());
        assertFalse(budget.terminal());
    }

    @Test void measuredExactCostExhaustsSecondActualQueryWithoutPerObstacleReset() {
        Work measured = CapsuleAabbContact3d.query(START, BOX, ACCURACY, LIMITS).work();
        int cap = measured.exactOps() * 2 - 1;
        assertTrue(cap > measured.exactOps());
        QueryLimits shared = exactCap(cap);
        var budget = new CapsuleContactQueryBudget(shared, 8);
        var first = budget.query(START, BOX, ACCURACY);
        assertEquals(Relation.SEPARATED, first.relation());
        var second = budget.query(START, BOX, ACCURACY);
        assertEquals(Relation.UNRESOLVED, second.relation(), second::toString);
        assertEquals(Reason.EXACT_WORK_LIMIT, second.reason().orElseThrow());
        assertTrue(second.work().exactAttempts() > 0, "second query entered actual CHB API");
        System.out.println("UG_CONTACT_SHARED_EXACT measured=" + measured + " cap=" + cap
                + " first=" + first.work() + " second=" + second.work() + " total=" + budget.work());
        assertTotals(first.work(), second.work(), budget.work());
        assertTrue(budget.terminal());
        var before = budget.work();
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                () -> budget.query(START, BOX, ACCURACY));
        assertEquals(before, budget.work(), "third call performs no CHB query or refund");
        assertEquals(3, budget.calls(), "denied entry is counted as an attempt");
        assertEquals(Relation.SEPARATED,
                new CapsuleContactQueryBudget(shared, 8).query(START, BOX, ACCURACY).relation());
    }

    @Test void unresolvedExactWorkIsChargedAndDoesNotAcquireAReceipt() {
        var budget = new CapsuleContactQueryBudget(exactCap(1), 8);
        var failed = budget.query(START, BOX, ACCURACY);
        assertEquals(Relation.UNRESOLVED, failed.relation());
        assertEquals(Reason.EXACT_WORK_LIMIT, failed.reason().orElseThrow());
        assertTrue(failed.work().exactAttempts() > failed.work().exactOps());
        assertEquals(failed.work().exactOps(), budget.work().exactOps());
        assertEquals(failed.work().exactAttempts(), budget.work().exactAttempts());
        assertTrue(budget.terminal());
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                () -> budget.query(START, OTHER, ACCURACY));
        assertEquals(failed.work().exactOps(), budget.work().exactOps());
    }

    @Test void queryAndBoundaryShareAllWorkAndPreserveActualStoredInstallationIdentity() {
        var budget = new CapsuleContactQueryBudget(LIMITS, 8);
        var contact = budget.query(START, OTHER, ACCURACY);
        Vec3d away = new Vec3d(-1, 0, 0);
        var candidate = candidate(START, away);
        var boundary = budget.refineTranslationBoundary(START, away, BOX,
                new BoundarySearchHint(0, 1), candidate, ACCURACY);
        assertEquals(BoundaryKind.SEPARATED_FULL, boundary.kind(), boundary::toString);
        var installed = boundary.installation().orElseThrow();
        assertSame(candidate.actualStoredCapsule(), installed.storedCapsule());
        assertEquals(candidate.acceptedStoredCenter(), installed.storedCenter());
        assertEquals(candidate.acceptedHalfLine(), installed.halfLine());
        assertEquals(candidate.fraction(), installed.fraction());
        assertEquals(Relation.SEPARATED, installed.storedContact().relation());
        System.out.println("UG_CONTACT_QUERY_BOUNDARY query=" + contact.work()
                + " boundary=" + boundary.work() + " total=" + budget.work());
        assertTotals(contact.work(), boundary.work(), budget.work());
        assertEquals(2, budget.calls());
    }

    @Test void queryCostAlsoReducesActualBoundaryBudget() {
        var contact = CapsuleAabbContact3d.query(START, BOX, ACCURACY, LIMITS);
        Vec3d away = new Vec3d(-1, 0, 0);
        var candidate = candidate(START, away);
        var full = CapsuleAabbContact3d.refineTranslationBoundary(START, away, BOX,
                new BoundarySearchHint(0, 1), candidate, ACCURACY, LIMITS);
        assertEquals(BoundaryKind.SEPARATED_FULL, full.kind(), full::toString);
        var shared = exactCap(contact.work().exactOps() + full.work().exactOps() - 1);
        var budget = new CapsuleContactQueryBudget(shared, 8);
        var first = budget.query(START, BOX, ACCURACY);
        var second = budget.refineTranslationBoundary(START, away, BOX,
                new BoundarySearchHint(0, 1), candidate, ACCURACY);
        assertEquals(BoundaryKind.UNRESOLVED, second.kind(), second::toString);
        assertEquals(Reason.EXACT_WORK_LIMIT, second.reason().orElseThrow());
        assertTrue(second.installation().isEmpty());
        assertTotals(first.work(), second.work(), budget.work());
        assertEquals(BoundaryKind.SEPARATED_FULL,
                new CapsuleContactQueryBudget(shared, 8).refineTranslationBoundary(START, away, BOX,
                        new BoundarySearchHint(0, 1), candidate, ACCURACY).kind());
    }

    @Test void deniedStaticAndRefinementAttemptsRemainCapPlusOne() {
        Vec3d away = new Vec3d(-1, 0, 0);
        var budget = new CapsuleContactQueryBudget(new QueryLimits(8192, 20000, 1, 32, 2336, 40000, 64), 8);
        var failed = budget.refineTranslationBoundary(START, away, BOX,
                new BoundarySearchHint(0, 1), candidate(START, away), ACCURACY);
        assertEquals(Reason.EXACT_WORK_LIMIT, failed.reason().orElseThrow());
        assertEquals(2, failed.work().staticEvaluations());
        assertEquals(2, budget.work().staticEvaluations());
        assertEquals(failed.work().staticAttempts(), budget.work().staticAttempts());
        assertTrue(budget.terminal());
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                () -> budget.query(START, BOX, ACCURACY));

        Capsule touching = point(-7.0 / 8, 1.0 / 8, 0.5, 5.0 / 8);
        Vec3d inward = new Vec3d(9.0 / 8, -1.5, 0);
        var installation = candidate(touching, inward);
        var refined = new CapsuleContactQueryBudget(new QueryLimits(8192, 20000, 128, 32, 2336, 40000, 1), 8);
        var denied = refined.refineTranslationBoundary(touching, inward, BOX,
                new BoundarySearchHint(0, 1), installation, ACCURACY);
        assertEquals(Reason.EXACT_WORK_LIMIT, denied.reason().orElseThrow());
        assertEquals(2, denied.work().refinementSteps());
        System.out.println("UG_CONTACT_DENIED static=" + failed.work() + " refinement=" + denied.work());
        assertEquals(2, refined.work().refinementSteps());
        assertTrue(refined.terminal());
    }

    @Test void unsupportedZeroWorkStillConsumesFiniteCallLimit() {
        var budget = new CapsuleContactQueryBudget(LIMITS, 2);
        Capsule unsupported = point(0x1.0p26, 0, 0, 1);
        for (int i = 0; i < 2; i++) {
            var result = budget.query(unsupported, BOX, ACCURACY);
            assertEquals(Relation.UNRESOLVED, result.relation());
            assertEquals(Reason.UNSUPPORTED_DOMAIN, result.reason().orElseThrow());
            assertEquals(0, result.work().exactOps());
        }
        assertTrue(budget.terminal());
        assertEquals(0, budget.work().exactOps());
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                () -> budget.query(unsupported, BOX, ACCURACY));
        assertEquals(3, budget.calls());
    }

    @Test void invalidNullInputIsRejectedBeforeAnyExecutionOrCallCharge() {
        var budget = new CapsuleContactQueryBudget(LIMITS, 2);
        assertThrows(NullPointerException.class, () -> budget.query(null, BOX, ACCURACY));
        assertThrows(NullPointerException.class, () -> budget.query(START, null, ACCURACY));
        assertThrows(NullPointerException.class, () -> budget.query(START, BOX, null));
        assertThrows(NullPointerException.class, () -> budget.refineTranslationBoundary(START,
                new Vec3d(-1, 0, 0), BOX, new BoundarySearchHint(0, 1), null, ACCURACY));
        assertEquals(0, budget.calls());
        assertFalse(budget.terminal());
        assertEquals(0, budget.work().exactAttempts());
        assertThrows(IllegalArgumentException.class, () -> new CapsuleContactQueryBudget(LIMITS, 0));
        assertThrows(NullPointerException.class, () -> new CapsuleContactQueryBudget(null, 2));
    }

    private static void assertTotals(Work a, Work b, CapsuleContactQueryBudget.Totals total) {
        assertEquals((long) a.exactAttempts() + b.exactAttempts(), total.exactAttempts());
        assertEquals((long) a.exactOps() + b.exactOps(), total.exactOps());
        assertEquals(Math.max(a.peakExactBits(), b.peakExactBits()), total.peakExactBits());
        assertEquals((long) a.staticAttempts() + b.staticAttempts(), total.staticAttempts());
        assertEquals((long) a.staticEvaluations() + b.staticEvaluations(), total.staticEvaluations());
        assertEquals((long) a.staticCandidateUnits() + b.staticCandidateUnits(), total.staticCandidateUnits());
        assertEquals((long) a.castAttempts() + b.castAttempts(), total.castAttempts());
        assertEquals((long) a.castQueries() + b.castQueries(), total.castQueries());
        assertEquals((long) a.castEvaluations() + b.castEvaluations(), total.castEvaluations());
        assertEquals((long) a.castCandidateUnits() + b.castCandidateUnits(), total.castCandidateUnits());
        assertEquals((long) a.totalCandidateUnits() + b.totalCandidateUnits(), total.totalCandidateUnits());
        assertEquals((long) a.refinementSteps() + b.refinementSteps(), total.refinementSteps());
    }
    private static QueryLimits exactCap(int cap) {
        return new QueryLimits(8192, cap, 128, 32, 2336, 40000, 64);
    }
    private static StoredInstallCandidate candidate(Capsule start, Vec3d delta) {
        Vec3d center = start.centerline().start(), stored = center.add(delta);
        return new StoredInstallCandidate(1, center, stored, new Vec3d(0, 0, 0),
                point(stored.x(), stored.y(), stored.z(), start.radius()));
    }
    private static Capsule point(double x, double y, double z, double radius) {
        Vec3d p = new Vec3d(x, y, z);
        return new Capsule(new Segment3d(p, p), radius);
    }
}

