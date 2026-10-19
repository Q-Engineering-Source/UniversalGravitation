package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual CHB062 calls through the owner's shared ledger; no UG geometry proof. */
class FreeNearFootOriginalPrefixTest {
    @TempDir Path directory;
    private static final Vec3d CENTER = new Vec3d(8.300000035762787, 66.89999997615814, 8.9);
    private static final Vec3d DELTA = new Vec3d(-.24999999999999994, -5.551115123125783E-17, 0);
    private static final Aabb BOX = new Aabb(new Vec3d(7, 66, 8), new Vec3d(8, 67, 9));
    private static final double RADIUS = (double) .3f, T = 9.53674330617105E-8;
    private static final QueryLimits LIMITS = new QueryLimits(8192, 100000, 256, 128, 9344, 150000, 128);
    private static final Vec3d ZERO = new Vec3d(0, 0, 0), UP = new Vec3d(1, 0, 0);
    private static Capsule sphere(Vec3d p) { return new Capsule(new Segment3d(p, p), RADIUS); }
    private InstalledFreeEndpointTest.Scene owner(String name) throws Exception {
        var fixture = new InstalledFreeEndpointTest(); fixture.directory = directory;
        return fixture.scene(false, name);
    }
    private static BigDecimal margin(double t) {
        return new BigDecimal(CENTER.x()).add(new BigDecimal(DELTA.x()).multiply(new BigDecimal(t)))
                .subtract(new BigDecimal(BOX.max().x()).add(new BigDecimal(RADIUS)));
    }
    @Test void sameStoredTouchRejectsOriginalPenetrationAndProvesOnlyNextDownPrefix() throws Exception {
        double safe = Math.nextDown(T);
        var at = CENTER.add(DELTA.multiply(safe));
        assertEquals(at, CENTER.add(DELTA.multiply(T)));
        assertEquals(at, CENTER.add(DELTA.multiply(Math.nextUp(T))));
        assertTrue(margin(safe).signum() > 0);
        assertTrue(margin(T).signum() < 0);
        assertTrue(margin(Math.nextUp(T)).signum() < 0);
        for (double rejected : new double[]{T, Math.nextUp(T)}) try (var scene = owner("reject-" + rejected)) {
            assertFalse(FreeNearFootDrive.exactSubpath(CENTER, DELTA, rejected,
                    DELTA.multiply(rejected), at, scene.tx().numeric()));
            var failure = assertThrows(FreeNearFootDrive.ProofInsufficient.class,
                    () -> FreeNearFootDrive.proveCandidate(scene.tx(), sphere(CENTER), DELTA, rejected, BOX, UP));
            assertTrue(failure.getMessage().contains("NO_PREFIX_CERTIFICATE"));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scene.tx().installationStage());
        }
        try (var scene = owner("safe-original-prefix")) {
            long before = scene.tx().queries().calls();
            var proof = FreeNearFootDrive.proveCandidate(scene.tx(), sphere(CENTER), DELTA, safe, BOX, UP);
            var result = proof.originalPrefix().orElseThrow();
            var receipt = result.proof().orElseThrow();
            assertSame(DELTA, receipt.originalDelta()); assertSame(BOX, receipt.obstacle());
            assertEquals(safe, receipt.endFraction()); assertEquals(safe, receipt.prefix().endFraction());
            assertEquals(safe, receipt.installation().fraction());
            assertSame(receipt.candidate().actualStoredCapsule(), receipt.installation().storedCapsule());
            assertEquals(at, receipt.installation().storedCenter());
            assertEquals(Relation.TOUCHING, receipt.installation().storedContact().relation());
            assertTrue(receipt.installation().storedContact().witness().isPresent());
            assertEquals(before + 2, scene.tx().queries().calls());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scene.tx().installationStage());
            scene.tx().requireCurrent();
        }
    }
    @Test void failedAndSuccessfulOriginalProofBothChargeTheSameLedger() {
        var budget = new CapsuleContactQueryBudget(LIMITS, 8);
        var at = CENTER.add(DELTA.multiply(T)); var actual = sphere(at); var start = sphere(CENTER);
        var rejected = budget.proveOriginalTranslationPrefix(start, DELTA, T, BOX,
                new StoredInstallCandidate(T, CENTER, at, ZERO, actual), FreeOwnerTransaction.ACCURACY);
        assertEquals(Reason.NO_PREFIX_CERTIFICATE, rejected.reason().orElseThrow());
        var before = budget.work();
        double safe = Math.nextDown(T);
        var accepted = budget.proveOriginalTranslationPrefix(start, DELTA, safe, BOX,
                new StoredInstallCandidate(safe, CENTER, at, ZERO, actual), FreeOwnerTransaction.ACCURACY);
        assertTrue(accepted.proof().isPresent(), accepted::toString);
        assertEquals(2, budget.calls());
        assertEquals(rejected.work().exactOps(), before.exactOps());
        var next = accepted.work();
        assertEquals(new CapsuleContactQueryBudget.Totals(
                before.exactAttempts() + next.exactAttempts(), before.exactOps() + next.exactOps(),
                Math.max(before.peakExactBits(), next.peakExactBits()),
                before.staticAttempts() + next.staticAttempts(), before.staticEvaluations() + next.staticEvaluations(),
                before.staticCandidateUnits() + next.staticCandidateUnits(), before.castAttempts() + next.castAttempts(),
                before.castQueries() + next.castQueries(), before.castEvaluations() + next.castEvaluations(),
                before.castCandidateUnits() + next.castCandidateUnits(),
                before.totalCandidateUnits() + next.totalCandidateUnits(),
                before.refinementSteps() + next.refinementSteps()), budget.work());
    }
    @Test void originalProofExactExhaustionIsTerminalAndCannotRefundOrTryAnotherCandidate() {
        var limits = new QueryLimits(8192, 1, 256, 128, 9344, 150000, 128);
        var budget = new CapsuleContactQueryBudget(limits, 8);
        var at = CENTER.add(DELTA.multiply(T));
        var candidate = new StoredInstallCandidate(T, CENTER, at, ZERO, sphere(at));
        var result = budget.proveOriginalTranslationPrefix(sphere(CENTER), DELTA, T, BOX,
                candidate, FreeOwnerTransaction.ACCURACY);
        assertEquals(Reason.EXACT_WORK_LIMIT, result.reason().orElseThrow());
        assertTrue(budget.terminal()); var spent = budget.work();
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                () -> budget.proveOriginalTranslationPrefix(sphere(CENTER), DELTA, T, BOX,
                        candidate, FreeOwnerTransaction.ACCURACY));
        assertEquals(spent, budget.work()); assertEquals(2, budget.calls());
    }
}
