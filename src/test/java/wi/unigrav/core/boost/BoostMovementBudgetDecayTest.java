package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class BoostMovementBudgetDecayTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void enteringDecayAdvancesAtTheOldRateThenMintsTheLinearIntegral() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission spend = budget.prepareClipped(1, 0, new Vec3(10, 0, 0));
        assertTrue(budget.commitNative(spend, spend.destination()));
        assertEquals(14.5, budget.snapshot().distanceCredit(), EPSILON);

        budget.beginDecay(0, 70, 840);
        assertEquals(490, budget.ceilingMps(), EPSILON);
        assertEquals(490, budget.proposalCeilingMps(), EPSILON);
        budget.advance(1);

        assertEquals(448, budget.ceilingMps(), EPSILON);
        assertEquals(490, budget.proposalCeilingMps(), EPSILON);
        assertEquals(37.95, budget.snapshot().distanceCredit(), EPSILON);
        BoostMovementBudget.Admission clipped = budget.prepareClipped(2, 1, new Vec3(100, 0, 0));
        assertEquals(24.5, clipped.distance(), EPSILON);
        assertFalse(budget.commit(clipped, clipped.destination()));
        assertTrue(budget.commitNative(clipped, clipped.destination()));
    }

    @Test
    void strictPrepareUsesCurrentCarrierWhileClippingGetsOneTickPreviousCarrierGrace() {
        BoostMovementBudget strict = new BoostMovementBudget(490, 0, Vec3.ZERO);
        strict.beginDecay(0, 70, 840);
        strict.advance(1);
        assertEquals(BoostMovementBudget.Failure.STEP,
                strict.prepare(1, 1, new Vec3(24.5, 0, 0)).failure());

        BoostMovementBudget clipped = new BoostMovementBudget(490, 0, Vec3.ZERO);
        clipped.beginDecay(0, 70, 840);
        clipped.advance(1);
        BoostMovementBudget.Admission proposal = clipped.prepareClipped(1, 1, new Vec3(24.5, 0, 0));
        assertEquals(24.5, proposal.distance(), EPSILON);
    }

    @Test
    void floorRetainsOnlyItsImmediatelyPreviousStepCapThenUsesThreePointFiveMetres() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        budget.beginDecay(0, 70, 840);
        budget.advance(10);

        assertEquals(70, budget.ceilingMps(), EPSILON);
        assertEquals(112, budget.proposalCeilingMps(), EPSILON);
        assertFalse(budget.snapshot().decayActive());
        BoostMovementBudget.Admission arrivalTick = budget.prepareClipped(1, 10, new Vec3(100, 0, 0));
        assertEquals(5.6, arrivalTick.distance(), EPSILON);
        assertTrue(budget.commitNative(arrivalTick, arrivalTick.destination()));

        budget.advance(11);
        assertEquals(70, budget.proposalCeilingMps(), EPSILON);
        assertFalse(budget.snapshot().decayActive());
        BoostMovementBudget.Admission floorTick = budget.prepareClipped(2, 11, new Vec3(100, 0, 0));
        assertEquals(3.5, floorTick.distance(), EPSILON);
    }

    @Test
    void beginningAtOrBelowTheRequestedFloorIsAlreadyInactive() {
        BoostMovementBudget budget = new BoostMovementBudget(70, 0, Vec3.ZERO);
        budget.beginDecay(0, 70, 840);

        assertFalse(budget.snapshot().decayActive());
        assertEquals(70, budget.ceilingMps(), EPSILON);
        assertEquals(70, budget.proposalCeilingMps(), EPSILON);

        BoostMovementBudget belowRequestedFloor = new BoostMovementBudget(40, 0, Vec3.ZERO);
        belowRequestedFloor.beginDecay(0, 70, 840);
        assertFalse(belowRequestedFloor.snapshot().decayActive());
        assertEquals(40, belowRequestedFloor.ceilingMps(), EPSILON);
        assertEquals(40, belowRequestedFloor.proposalCeilingMps(), EPSILON);
    }

    @Test
    void longGapUsesOnlyTheRecentTwoTickDecaySegmentAndRepeatedTickDoesNotDoubleMint() {
        BoostMovementBudget longGap = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission initial = longGap.prepareClipped(1, 0, new Vec3(24.5, 0, 0));
        assertTrue(longGap.commitNative(initial, initial.destination()));
        longGap.beginDecay(0, 70, 840);
        longGap.advance(20);
        assertEquals(70, longGap.ceilingMps(), EPSILON);
        assertEquals(70, longGap.proposalCeilingMps(), EPSILON);
        assertEquals(7.0, longGap.snapshot().distanceCredit(), EPSILON);

        BoostMovementBudget repeated = new BoostMovementBudget(490, 0, Vec3.ZERO);
        BoostMovementBudget.Admission spend = repeated.prepareClipped(1, 0, new Vec3(10, 0, 0));
        assertTrue(repeated.commitNative(spend, spend.destination()));
        repeated.beginDecay(0, 70, 840);
        repeated.advance(1);
        double creditAfterFirstAdvance = repeated.snapshot().distanceCredit();
        repeated.advance(1);
        assertEquals(448, repeated.ceilingMps(), EPSILON);
        assertEquals(creditAfterFirstAdvance, repeated.snapshot().distanceCredit(), EPSILON);
    }

    @Test
    void ceilingChangeAfterDecayAdvancesOldScheduleWithoutGrantingRaisedCredit() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        budget.beginDecay(0, 70, 840);
        budget.advance(1);
        double creditBeforeExit = budget.snapshot().distanceCredit();

        budget.changeCeiling(1, 490);

        assertFalse(budget.snapshot().decayActive());
        assertEquals(490, budget.ceilingMps(), EPSILON);
        assertEquals(490, budget.proposalCeilingMps(), EPSILON);
        assertEquals(creditBeforeExit, budget.snapshot().distanceCredit(), EPSILON);
    }

    @Test
    void rebaseContinuesAStartedDecayWithoutSeedingCredit() {
        BoostMovementBudget budget = new BoostMovementBudget(490, 0, Vec3.ZERO);
        budget.beginDecay(0, 70, 840);
        budget.rebase(new Vec3(10, 0, 0), 1);

        assertEquals(448, budget.ceilingMps(), EPSILON);
        assertEquals(490, budget.proposalCeilingMps(), EPSILON);
        assertEquals(new Vec3(10, 0, 0), budget.position());
        assertEquals(44.8, budget.snapshot().distanceCredit(), EPSILON);
    }
}
