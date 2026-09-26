package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class BoostMovementBudgetTest {
    @Test
    void acceptsOne490MetrePerSecondStepButNotFortyNineMetreJump() {
        var budget = new BoostMovementBudget(490, 10, Vec3.ZERO);
        var admission = budget.prepare(1, 10, new Vec3(24.5, 0, 0));
        assertTrue(admission.accepted());
        assertTrue(budget.commit(admission, admission.destination()));
        assertEquals(new Vec3(24.5, 0, 0), budget.position());
        assertEquals(BoostMovementBudget.Failure.STEP,
                budget.prepare(2, 10, new Vec3(73.5, 0, 0)).failure());
        assertEquals(BoostMovementBudget.Failure.CREDIT,
                budget.prepare(3, 10, new Vec3(48.6, 0, 0)).failure());
    }

    @Test
    void allowsTwoFullDistanceStepsWhenTwoTicksOfCreditWereActuallyAccrued() {
        var budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        var first = budget.prepare(1, 2, new Vec3(24.5, 0, 0));
        assertTrue(budget.commit(first, first.destination()));
        assertEquals(2401, budget.vanillaAllowanceSquared(Vec3.ZERO), 0.002);
        var second = budget.prepare(2, 2, new Vec3(49, 0, 0));
        assertTrue(budget.commit(second, second.destination()));
        assertEquals(BoostMovementBudget.Failure.CREDIT,
                budget.prepare(3, 2, new Vec3(49.0002, 0, 0)).failure());
    }

    @Test
    void checksNormRatherThanEachAxisAndRejectsNonFiniteOrReplayedInputs() {
        var budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        assertEquals(BoostMovementBudget.Failure.STEP,
                budget.prepare(1, 0, new Vec3(24, 24, 0)).failure());
        assertEquals(BoostMovementBudget.Failure.SEQUENCE,
                budget.prepare(1, 1, Vec3.ZERO).failure());
        assertEquals(BoostMovementBudget.Failure.INVALID_INPUT,
                budget.prepare(2, 1, new Vec3(Double.NaN, 0, 0)).failure());
        assertEquals(BoostMovementBudget.Failure.TIME, budget.prepare(2, 0, Vec3.ZERO).failure());
    }

    @Test
    void strictCollisionDoesNotCommitPositionAndExactTicketIdentityIsRequired() {
        var budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        var pending = budget.prepare(1, 0, new Vec3(24.5, 0, 0));
        assertFalse(budget.commit(pending, new Vec3(5, 0, 0)));
        assertEquals(Vec3.ZERO, budget.position());
        budget.abort(pending);
        var next = budget.prepare(2, 0, new Vec3(5, 0, 0));
        var forged = new BoostMovementBudget.Admission(2, Vec3.ZERO, new Vec3(5, 0, 0),
                5, BoostMovementBudget.Failure.NONE);
        assertFalse(budget.commit(forged, new Vec3(5, 0, 0)));
        assertTrue(budget.commit(next, new Vec3(5, 0, 0)));
        assertFalse(budget.commit(next, new Vec3(5, 0, 0)));
    }

    @Test
    void roundingAllowanceIsFiniteAndNeverRefilled() {
        var budget = new BoostMovementBudget(20, 0, Vec3.ZERO);
        boolean exhausted = false;
        for (int tick = 0; tick < 100; tick++) {
            Vec3 proposed = budget.position().add(new Vec3(1.000009, 0, 0));
            var admission = budget.prepare(tick + 1L, tick, proposed);
            if (!admission.accepted()) {
                exhausted = true;
                break;
            }
            assertTrue(budget.commit(admission, proposed));
        }
        assertTrue(exhausted, "No new numerical-distance grant per tick or packet");
    }

    @Test
    void validatesCeilingsAndStrictCommitCannotConsumeAClippedTicket() {
        var budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        var clipped = budget.prepareClipped(1, 0, new Vec3(1, 0, 0));
        assertFalse(budget.commit(clipped, clipped.destination()));
        assertTrue(budget.commitNative(clipped, clipped.destination()));
        assertThrows(IllegalArgumentException.class, () -> budget.setCeiling(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new BoostMovementBudget(501, 0, Vec3.ZERO));
    }
}
