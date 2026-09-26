package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Behavior tests for Alt-chord roll and boost gear selection. */
class SixDofControlGearsTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void boostGearsAppendTheNewLevelsWithoutChangingTheOriginalFive() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);

        double[] expectedLimits = {11.453746, 16.0, 24.0, 32.0, 40.0, 80.0, 160.0, 320.0, 490.0};
        double[] expectedAccelerations = {22.907492, 32.0, 48.0, 64.0, 80.0, 160.0, 320.0,
                640.0, 980.0};
        assertBoostPlan(gears, 5, expectedLimits, expectedAccelerations);
        for (int gear = 4; gear >= 1; gear--) {
            gears.update(false, false, false, true);
            gears.update(false, false, false, false);
            assertBoostPlan(gears, gear, expectedLimits, expectedAccelerations);
        }
        for (int gear = 2; gear <= 9; gear++) {
            gears.update(false, false, true, false);
            gears.update(false, false, false, false);
            assertBoostPlan(gears, gear, expectedLimits, expectedAccelerations);
        }
        assertEquals(9, gears.boostGearCount());
        assertEquals(5, gears.rollGearCount());
    }

    @Test
    void boostGearBoundsClampAtTheNewNinthGear() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);

        for (int step = 0; step < 10; step++) {
            gears.update(false, false, true, false);
            gears.update(false, false, false, false);
        }

        assertEquals(9, gears.boostGear());
        assertEquals(490.0, gears.boostSpeedLimitMps(), EPSILON);
        assertEquals(980.0, gears.boostAccelerationMps2(), EPSILON);

        for (int step = 0; step < 10; step++) {
            gears.update(false, false, false, true);
            gears.update(false, false, false, false);
        }

        assertEquals(1, gears.boostGear());
        assertEquals(11.453746, gears.boostSpeedLimitMps(), EPSILON);
        assertEquals(22.907492, gears.boostAccelerationMps2(), EPSILON);
    }

    @Test
    void defaultsPreserveCurrentRollAndLegacyBoostMaximums() {
        SixDofControlGears gears = new SixDofControlGears();

        assertEquals(3, gears.rollGear());
        assertEquals(180.0, gears.rollMaximumSpeedDegreesPerSecond(), EPSILON);
        assertEquals(720.0, gears.rollAccelerationDegreesPerSecondSquared(), EPSILON);
        assertEquals(5, gears.boostGear());
        assertEquals(40.0, gears.boostSpeedLimitMps(), EPSILON);
        assertEquals(80.0, gears.boostAccelerationMps2(), EPSILON);
    }

    @Test
    void heldChordChangesOnlyOnceUntilReleasedAndPressedAgain() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);

        gears.update(true, false, false, false);
        gears.update(true, false, false, false);
        gears.update(true, false, false, false);

        assertEquals(4, gears.rollGear());
        gears.update(false, false, false, false);
        gears.update(true, false, false, false);
        assertEquals(5, gears.rollGear());
    }

    @Test
    void oppositeGearChordsTogetherAreANoOpAndBoundsAreClamped() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);

        gears.update(true, true, true, true);

        assertEquals(3, gears.rollGear());
        assertEquals(5, gears.boostGear());
        gears.update(false, false, false, false);
        for (int step = 0; step < 10; step++) {
            gears.update(false, true, false, true);
            gears.update(false, false, false, false);
        }
        assertEquals(1, gears.rollGear());
        assertEquals(1, gears.boostGear());
    }

    @Test
    void pressingTheOppositeChordWhileOneIsHeldDoesNotChangeGear() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);
        gears.update(true, false, false, false);
        assertEquals(4, gears.rollGear());

        gears.update(true, true, false, false);

        assertEquals(4, gears.rollGear());
    }

    @Test
    void suspendPreventsHeldKeysFromChangingGearOnReactivation() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);
        gears.suspend();

        gears.update(true, false, true, false);

        assertEquals(3, gears.rollGear());
        assertEquals(5, gears.boostGear());
        gears.update(false, false, false, false);
        gears.update(false, true, false, true);
        assertEquals(2, gears.rollGear());
        assertEquals(4, gears.boostGear());
    }

    private static void assertBoostPlan(SixDofControlGears gears, int expectedGear,
            double[] expectedLimits, double[] expectedAccelerations) {
        int index = expectedGear - 1;
        assertEquals(expectedGear, gears.boostGear());
        assertEquals(expectedLimits[index], gears.boostSpeedLimitMps(), EPSILON);
        assertEquals(expectedAccelerations[index], gears.boostAccelerationMps2(), EPSILON);
    }
}
