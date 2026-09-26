package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for H boost direction selection and speed limiting. */
class SixDofBoostMotionTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    void authoritativeFreeCameraDirectionOverridesEulerFallback() {
        Vec3 quaternionForward = new Vec3(0.0, 1.0, 0.0);
        Vec3 staleEulerLook = new Vec3(0.0, 0.0, 1.0);

        Vec3 deltaVelocity = SixDofBoostMotion.deltaVelocity(
                quaternionForward, staleEulerLook, Vec3.ZERO,
                980.0, 490.0, 0.05);

        assertVectorEquals(new Vec3(0.0, 49.0, 0.0), deltaVelocity);
    }

    @Test
    void selectedSpeedLimitCannotOvershoot() {
        Vec3 direction = new Vec3(0.0, 0.0, 1.0);

        Vec3 nearLimit = SixDofBoostMotion.deltaVelocity(
                direction, Vec3.ZERO, new Vec3(0.0, 0.0, 39.9),
                80.0, 40.0, 0.05);
        Vec3 atLimit = SixDofBoostMotion.deltaVelocity(
                direction, Vec3.ZERO, new Vec3(0.0, 0.0, 40.0),
                80.0, 40.0, 0.05);

        assertVectorEquals(new Vec3(0.0, 0.0, 0.1), nearLimit);
        assertVectorEquals(Vec3.ZERO, atLimit);
    }

    @Test
    void highestGearStaysFiniteAndCannotOvershootAcrossRepeatedTicks() {
        Vec3 velocity = Vec3.ZERO;
        Vec3 direction = new Vec3(0.0, 0.0, 1.0);

        for (int tick = 0; tick < 20; tick++) {
            Vec3 delta = SixDofBoostMotion.deltaVelocity(
                    direction, Vec3.ZERO, velocity, 980.0, 490.0, 0.05);
            velocity = velocity.add(delta);
            assertTrue(Double.isFinite(velocity.x()));
            assertTrue(Double.isFinite(velocity.y()));
            assertTrue(Double.isFinite(velocity.z()));
            assertTrue(velocity.norm() <= 490.0);
        }

        assertVectorEquals(new Vec3(0.0, 0.0, 490.0), velocity);
    }

    @Test
    void downshiftingWhileReversingBrakesWithoutForcingAStop() {
        Vec3 delta = SixDofBoostMotion.deltaVelocity(
                new Vec3(0.0, 0.0, -1.0), Vec3.ZERO, new Vec3(0.0, 0.0, 490.0),
                80.0, 40.0, 0.05);

        assertVectorEquals(new Vec3(0.0, 0.0, -4.0), delta);
    }

    @Test
    void downshiftingFromGearNineToFiveKeepsOverspeedVelocityWithoutAddingThrust() {
        SixDofControlGears gears = new SixDofControlGears();
        gears.update(false, false, false, false);
        for (int step = 0; step < 10; step++) {
            gears.update(false, false, true, false);
            gears.update(false, false, false, false);
        }
        for (int step = 0; step < 4; step++) {
            gears.update(false, false, false, true);
            gears.update(false, false, false, false);
        }

        assertEquals(5, gears.boostGear());
        assertEquals(40.0, gears.boostSpeedLimitMps(), EPSILON);
        assertEquals(80.0, gears.boostAccelerationMps2(), EPSILON);
        Vec3 velocity = new Vec3(0.0, 0.0, 490.0);
        Vec3 delta = SixDofBoostMotion.deltaVelocity(
                new Vec3(0.0, 0.0, 1.0), Vec3.ZERO, velocity,
                gears.boostAccelerationMps2(), gears.boostSpeedLimitMps(), 0.05);

        assertVectorEquals(Vec3.ZERO, delta);
        assertVectorEquals(velocity, velocity.add(delta));
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
