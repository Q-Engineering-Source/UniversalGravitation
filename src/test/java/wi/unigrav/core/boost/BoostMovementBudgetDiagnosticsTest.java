package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class BoostMovementBudgetDiagnosticsTest {
    @Test
    void prePrepareSnapshotRetainsTheRealElapsedGapAfterRefill() {
        BoostMovementBudget budget = new BoostMovementBudget(20, 2, Vec3.ZERO);
        long prePrepareTick = budget.snapshot().lastTick();
        assertEquals(BoostMovementBudget.Failure.STEP,
                budget.prepare(1, 7, new Vec3(2, 0, 0)).failure());
        assertEquals(5, 7 - prePrepareTick);
        assertEquals(7, budget.snapshot().lastTick());
        assertEquals(BoostMovementBudget.DiagnosticFailure.STEP,
                budget.snapshot().lastDiagnosticFailure());
    }

    @Test
    void snapshotDistinguishesDistanceCreditAndSingleStepRejectionWithoutPacketCredit() {
        BoostMovementBudget spent = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission first = spent.prepare(1, 0, new Vec3(4, 0, 0));
        assertTrue(spent.commit(first, first.destination()));
        assertEquals(BoostMovementBudget.Failure.CREDIT,
                spent.prepare(2, 0, new Vec3(25, 0, 0)).failure());
        assertEquals(BoostMovementBudget.DiagnosticFailure.DISTANCE_CREDIT,
                spent.snapshot().lastDiagnosticFailure());

        BoostMovementBudget stepRejected = new BoostMovementBudget(490, 0, Vec3.ZERO);
        assertEquals(BoostMovementBudget.Failure.STEP,
                stepRejected.prepare(1, 0, new Vec3(24.6, 0, 0)).failure());
        assertEquals(BoostMovementBudget.DiagnosticFailure.STEP,
                stepRejected.snapshot().lastDiagnosticFailure());

        BoostMovementBudget roundedDistance = new BoostMovementBudget(20, 0, Vec3.ZERO);
        boolean exhausted = false;
        for (int tick = 0; tick < 100; tick++) {
            Vec3 proposed = roundedDistance.position().add(new Vec3(1.000009, 0, 0));
            BoostMovementBudget.Admission next = roundedDistance.prepare(tick + 1L, tick, proposed);
            if (!next.accepted()) {
                assertEquals(BoostMovementBudget.Failure.CREDIT, next.failure());
                exhausted = true;
                break;
            }
            assertTrue(roundedDistance.commit(next, proposed));
        }
        assertTrue(exhausted);
        assertEquals(BoostMovementBudget.DiagnosticFailure.DISTANCE_CREDIT,
                roundedDistance.snapshot().lastDiagnosticFailure());
    }
}
