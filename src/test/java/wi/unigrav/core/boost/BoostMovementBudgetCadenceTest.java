package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class BoostMovementBudgetCadenceTest {
    @Test
    void admitsTwoLowDistanceSamplesBatchedInOneCreditedServerTick() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 40, Vec3.ZERO);

        BoostMovementBudget.Admission first = budget.prepare(1, 40, new Vec3(2, 0, 0));
        assertTrue(first.accepted());
        assertTrue(budget.commit(first, first.destination()));

        BoostMovementBudget.Admission second = budget.prepare(2, 40, new Vec3(4, 0, 0));
        assertAcceptedWithDistanceCreditRemaining(budget, second);
        assertTrue(budget.commit(second, second.destination()));
        assertEquals(new Vec3(4, 0, 0), budget.position());
    }

    @Test
    void extraPacketsDoNotMintDistanceCreditWithinTheSameServerTick() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 40, Vec3.ZERO);

        BoostMovementBudget.Admission first = budget.prepare(1, 40, new Vec3(2, 0, 0));
        assertTrue(first.accepted());
        assertTrue(budget.commit(first, first.destination()));

        BoostMovementBudget.Admission second = budget.prepare(2, 40, new Vec3(4, 0, 0));
        assertAcceptedWithDistanceCreditRemaining(budget, second);
        assertTrue(budget.commit(second, second.destination()));

        BoostMovementBudget.Admission overBudget = budget.prepare(3, 40, new Vec3(25, 0, 0));
        assertEquals(BoostMovementBudget.Failure.CREDIT, overBudget.failure());
        assertEquals(BoostMovementBudget.DiagnosticFailure.DISTANCE_CREDIT,
                budget.snapshot().lastDiagnosticFailure());
    }

    private static void assertAcceptedWithDistanceCreditRemaining(BoostMovementBudget budget,
            BoostMovementBudget.Admission admission) {
        assertTrue(admission.accepted(), () -> "A second 2 m sample in the same tick must not be rejected "
                + "while 22.5 m distance credit remains; failure=" + admission.failure()
                + ", diagnostic=" + budget.snapshot().lastDiagnosticFailure());
    }
}
