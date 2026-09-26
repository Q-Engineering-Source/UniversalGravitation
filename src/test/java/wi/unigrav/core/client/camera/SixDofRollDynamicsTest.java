package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the inertial local-forward roll input used by Z/X. */
class SixDofRollDynamicsTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    void releasingRollKeepsRotatingAndSlowsDown() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();
        for (int tick = 0; tick < 20; tick++) {
            dynamics.advance(1.0, 0.05);
        }
        double heldVelocity = dynamics.angularVelocityDegreesPerSecond();

        double releasedDelta = dynamics.advance(0.0, 0.05);

        assertTrue(releasedDelta > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() < heldVelocity);
    }

    @Test
    void nonFiniteInputBehavesLikeAReleasedKeyWithoutFreezingInertia() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();
        for (int tick = 0; tick < 20; tick++) {
            dynamics.advance(1.0, 0.05);
        }
        double heldVelocity = dynamics.angularVelocityDegreesPerSecond();

        double delta = dynamics.advance(Double.NaN, 0.05);

        assertTrue(delta > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() < heldVelocity);
    }

    @Test
    void reverseInputBrakesThroughZeroBeforeReversing() {
        SixDofRollDynamics dynamics = atPositiveMaximumSpeed();
        double previousVelocity = dynamics.angularVelocityDegreesPerSecond();

        double firstReverseDelta = dynamics.advance(-1.0, 0.05);

        assertTrue(firstReverseDelta > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() > 0.0);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() < previousVelocity);

        boolean producedNegativeRotation = false;
        for (int tick = 0; tick < 10; tick++) {
            double delta = dynamics.advance(-1.0, 0.05);
            if (delta < 0.0) {
                producedNegativeRotation = true;
                break;
            }
        }

        assertTrue(producedNegativeRotation);
        assertTrue(dynamics.angularVelocityDegreesPerSecond() < 0.0);
    }

    @Test
    void coastingStopsAfterAFiniteAdditionalRotation() {
        SixDofRollDynamics dynamics = atPositiveMaximumSpeed();
        double coastDegrees = 0.0;

        for (int tick = 0; tick < 200; tick++) {
            coastDegrees += dynamics.advance(0.0, 0.05);
        }

        assertEquals(0.0, dynamics.angularVelocityDegreesPerSecond(), EPSILON);
        assertTrue(coastDegrees > 50.0);
        assertTrue(coastDegrees < 70.0);
    }

    @Test
    void oneCoastStepUsesTheExactExponentialIntegral() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();
        dynamics.advance(1.0, 0.05);
        double retention = Math.exp(-3.0 * 0.05);

        double coastDegrees = dynamics.advance(0.0, 0.05);

        assertEquals(36.0 * retention,
                dynamics.angularVelocityDegreesPerSecond(), EPSILON);
        assertEquals(36.0 * (1.0 - retention) / 3.0, coastDegrees, EPSILON);
    }

    @Test
    void speedInputAndDeltaTimeAreBounded() {
        SixDofRollDynamics clampedAxis = new SixDofRollDynamics();
        SixDofRollDynamics unitAxis = new SixDofRollDynamics();
        SixDofRollDynamics clampedDelta = new SixDofRollDynamics();
        SixDofRollDynamics ordinaryDelta = new SixDofRollDynamics();

        double clampedAxisDegrees = clampedAxis.advance(10.0, 0.05);
        double unitAxisDegrees = unitAxis.advance(1.0, 0.05);
        double clampedDeltaDegrees = clampedDelta.advance(1.0, 10.0);
        double ordinaryDeltaDegrees = ordinaryDelta.advance(1.0, 0.1);

        assertEquals(unitAxisDegrees, clampedAxisDegrees, EPSILON);
        assertEquals(unitAxis.angularVelocityDegreesPerSecond(),
                clampedAxis.angularVelocityDegreesPerSecond(), EPSILON);
        assertEquals(ordinaryDeltaDegrees, clampedDeltaDegrees, EPSILON);
        assertEquals(ordinaryDelta.angularVelocityDegreesPerSecond(),
                clampedDelta.angularVelocityDegreesPerSecond(), EPSILON);

        SixDofRollDynamics maximum = atPositiveMaximumSpeed();
        assertEquals(180.0, maximum.angularVelocityDegreesPerSecond(), EPSILON);
    }

    @Test
    void drivenInputUsesDeclaredAccelerationWithoutPassiveDamping() {
        SixDofRollDynamics fullInput = new SixDofRollDynamics();
        for (int tick = 0; tick < 5; tick++) {
            fullInput.advance(1.0, 0.05);
        }

        SixDofRollDynamics partialInput = new SixDofRollDynamics();
        partialInput.advance(0.1, 0.05);
        partialInput.advance(0.1, 0.05);

        assertEquals(180.0, fullInput.angularVelocityDegreesPerSecond(), EPSILON);
        assertEquals(7.2, partialInput.angularVelocityDegreesPerSecond(), EPSILON);
    }

    @Test
    void selectedGearControlsAccelerationAndMaximumSpeed() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();

        for (int tick = 0; tick < 5; tick++) {
            dynamics.advance(1.0, 0.05, 90.0, 360.0);
        }

        assertEquals(90.0, dynamics.angularVelocityDegreesPerSecond(), EPSILON);
        assertTrue(dynamics.advance(1.0, 0.05, 90.0, 360.0) > 0.0);
        assertEquals(90.0, dynamics.angularVelocityDegreesPerSecond(), EPSILON);
    }

    @Test
    void clearAndInvalidTimeCannotLeakHiddenRotation() {
        SixDofRollDynamics dynamics = atPositiveMaximumSpeed();

        assertEquals(0.0, dynamics.advance(1.0, Double.NaN), EPSILON);
        assertEquals(180.0, dynamics.angularVelocityDegreesPerSecond(), EPSILON);
        dynamics.clear();

        assertEquals(0.0, dynamics.angularVelocityDegreesPerSecond(), EPSILON);
        assertEquals(0.0, dynamics.advance(0.0, 0.05), EPSILON);
    }

    @Test
    void rollDeltaUsesTheCurrentForwardAtAnArbitraryAttitude() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(37.0, -23.0);
        Vec3 forwardBeforeRoll = orientation.forward();
        Vec3 rightBeforeRoll = orientation.right();

        double rollDegrees = dynamics.advance(1.0, 0.05);
        orientation.applyExplicitRollDegrees(rollDegrees);

        assertVectorEquals(forwardBeforeRoll, orientation.forward());
        assertTrue(rightBeforeRoll.sub(orientation.right()).norm() > EPSILON);
    }

    private static SixDofRollDynamics atPositiveMaximumSpeed() {
        SixDofRollDynamics dynamics = new SixDofRollDynamics();
        for (int tick = 0; tick < 20; tick++) {
            dynamics.advance(1.0, 0.05);
        }
        return dynamics;
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
