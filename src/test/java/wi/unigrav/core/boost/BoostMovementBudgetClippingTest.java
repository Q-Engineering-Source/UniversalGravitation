package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class BoostMovementBudgetClippingTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void clipsThirtyMetresToOne490MetresPerSecondStepAndReservesItImmediately() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);

        BoostMovementBudget.Admission admission = budget.prepareClipped(1, 0, new Vec3(30, 0, 0));

        assertTrue(admission.accepted());
        assertEquals(24.5, admission.distance(), EPSILON);
        assertEquals(new Vec3(24.5, 0, 0), admission.destination());
        assertEquals(0.0, budget.snapshot().distanceCredit(), EPSILON);
        assertTrue(budget.commitNative(admission, admission.destination()));
    }

    @Test
    void distributesOneTickCreditAcrossTwoPacketsWithoutPacketMinting() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);

        BoostMovementBudget.Admission first = budget.prepareClipped(1, 0, new Vec3(21, 0, 0));
        assertEquals(21.0, first.distance(), EPSILON);
        assertTrue(budget.commitNative(first, first.destination()));

        BoostMovementBudget.Admission second = budget.prepareClipped(2, 0, new Vec3(42, 0, 0));
        assertEquals(3.5, second.distance(), EPSILON);
        assertEquals(new Vec3(24.5, 0, 0), second.destination());
        assertEquals(1.0e-4, budget.snapshot().roundingCredit(), EPSILON);
        assertTrue(budget.commitNative(second, second.destination()));

        BoostMovementBudget.Admission empty = budget.prepareClipped(3, 0, new Vec3(30, 0, 0));
        assertTrue(empty.accepted());
        assertEquals(0.0, empty.distance(), EPSILON);
        assertEquals(budget.position(), empty.destination());
    }

    @Test
    void clipsToCurrentDistanceCreditThroughRealTransactions() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission spent = budget.prepareClipped(1, 0, new Vec3(15.9, 0, 0));
        assertTrue(budget.commitNative(spent, spent.destination()));
        assertEquals(8.6, budget.snapshot().distanceCredit(), EPSILON);

        BoostMovementBudget.Admission clipped = budget.prepareClipped(2, 0, new Vec3(30, 0, 0));
        assertEquals(8.6, clipped.distance(), EPSILON);
        assertEquals(new Vec3(24.5, 0, 0), clipped.destination());
    }

    @Test
    void clipsByEuclideanNormRatherThanEachAxis() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission admission = budget.prepareClipped(1, 0, new Vec3(30, 40, 0));

        assertEquals(24.5, admission.distance(), EPSILON);
        assertEquals(14.7, admission.destination().x(), EPSILON);
        assertEquals(19.6, admission.destination().y(), EPSILON);
        assertEquals(24.5, admission.destination().norm(), EPSILON);
    }

    @Test
    void stationaryCandidatesDoNotMintTimeOrDistance() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        double initialCredit = budget.snapshot().distanceCredit();

        for (long sequence = 1; sequence <= 3; sequence++) {
            BoostMovementBudget.Admission zero = budget.prepareClipped(sequence, 0, Vec3.ZERO);
            assertEquals(0.0, zero.distance(), EPSILON);
            assertTrue(budget.commitNative(zero, zero.destination()));
        }

        assertEquals(initialCredit, budget.snapshot().distanceCredit(), EPSILON);
        assertEquals(0, budget.snapshot().lastTick());
    }

    @Test
    void nativeCollisionAndAbortNeverRefundReservedDistance() {
        BoostMovementBudget collision = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission first = collision.prepareClipped(1, 0, new Vec3(20, 0, 0));
        assertTrue(collision.commitNative(first, new Vec3(5, 0, 0)));
        assertEquals(new Vec3(5, 0, 0), collision.position());
        assertEquals(4.5, collision.snapshot().distanceCredit(), EPSILON);
        BoostMovementBudget.Admission afterCollision = collision.prepareClipped(2, 0, new Vec3(25, 0, 0));
        assertEquals(4.5, afterCollision.distance(), EPSILON);

        BoostMovementBudget aborted = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission reservation = aborted.prepareClipped(1, 0, new Vec3(20, 0, 0));
        aborted.abort(reservation);
        assertEquals(4.5, aborted.snapshot().distanceCredit(), EPSILON);
        BoostMovementBudget.Admission afterAbort = aborted.prepareClipped(2, 0, new Vec3(20, 0, 0));
        assertEquals(4.5, afterAbort.distance(), EPSILON);
    }

    @Test
    void ceilingChangesUseOldRateBeforeClampingAndRaisingDoesNotSeedCredit() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);

        budget.changeCeiling(0, 20);
        assertEquals(20, budget.ceilingMps(), EPSILON);
        assertEquals(2.0, budget.snapshot().distanceCredit(), EPSILON);

        BoostMovementBudget.Admission spend = budget.prepareClipped(1, 0, new Vec3(1, 0, 0));
        assertTrue(budget.commitNative(spend, spend.destination()));
        assertEquals(1.0, budget.snapshot().distanceCredit(), EPSILON);

        budget.changeCeiling(1, 490);
        assertEquals(490, budget.ceilingMps(), EPSILON);
        assertEquals(2.0, budget.snapshot().distanceCredit(), EPSILON);
        assertThrows(IllegalArgumentException.class, () -> budget.changeCeiling(0, 20));
    }

    @Test
    void nativeCommitAndRebaseRejectInvalidInputsWithoutResettingTheBudgetEpoch() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission reserved = budget.prepareClipped(1, 0, new Vec3(1, 0, 0));
        BoostMovementBudget.Admission forged = new BoostMovementBudget.Admission(1, Vec3.ZERO,
                new Vec3(1, 0, 0), 1, BoostMovementBudget.Failure.NONE);
        assertFalse(budget.commitNative(forged, Vec3.ZERO));
        assertFalse(budget.commitNative(reserved, new Vec3(Double.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> budget.rebase(new Vec3(Double.NaN, 0, 0), 0));
        assertTrue(budget.snapshot().pending());

        budget.rebase(new Vec3(10, 0, 0), 0);
        assertEquals(new Vec3(10, 0, 0), budget.position());
        assertFalse(budget.snapshot().pending());
        assertEquals(23.5, budget.snapshot().distanceCredit(), EPSILON);
        assertEquals(1, budget.lastSequence());
    }
}
