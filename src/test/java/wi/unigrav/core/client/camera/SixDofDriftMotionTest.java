package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests camera-local acceleration and speed limiting for zero-gravity drift. */
class SixDofDriftMotionTest {

    private static final double EPSILON = 1.0e-12;

    @Test void physicalInputCarriesTheSameRolledWorldVectorWithoutApplyingAnImpulse() {
        var orientation = identityOrientation();
        orientation.applyDegrees(0, 0, 90);
        assertVectorEquals(new Vec3(-0.5, 0, 0), SixDofDriftMotion.worldIntent(orientation.current(),
                new SixDofDriftMotion.Intent(0, 0, 0.5)));
        double component = 1.0 / Math.sqrt(3);
        assertVectorEquals(new Vec3(-component, -component, component), SixDofDriftMotion.worldIntent(orientation.current(),
                new SixDofDriftMotion.Intent(1, 1, 1)));
    }

    @Test
    void identityAttitudeMapsForwardRightAndUpToTheDocumentedWorldAxes() {
        SixDofCameraOrientation orientation = identityOrientation();
        SixDofCameraOrientation.Snapshot attitude = orientation.current();

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), SixDofDriftMotion.deltaVelocity(
                attitude, new SixDofDriftMotion.Intent(1.0, 0.0, 0.0), Vec3.ZERO,
                10.0, 0.0, 0.1));
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), SixDofDriftMotion.deltaVelocity(
                attitude, new SixDofDriftMotion.Intent(0.0, 1.0, 0.0), Vec3.ZERO,
                10.0, 0.0, 0.1));
        assertVectorEquals(new Vec3(0.0, 1.0, 0.0), SixDofDriftMotion.deltaVelocity(
                attitude, new SixDofDriftMotion.Intent(0.0, 0.0, 1.0), Vec3.ZERO,
                10.0, 0.0, 0.1));
    }

    @Test
    void rollRotatesTheLocalRightAndUpTranslationAxes() {
        SixDofCameraOrientation orientation = identityOrientation();
        orientation.applyDegrees(0.0, 0.0, 90.0);
        SixDofCameraOrientation.Snapshot attitude = orientation.current();

        assertVectorEquals(new Vec3(0.0, -1.0, 0.0), SixDofDriftMotion.deltaVelocity(
                attitude, new SixDofDriftMotion.Intent(0.0, 1.0, 0.0), Vec3.ZERO,
                10.0, 0.0, 0.1));
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), SixDofDriftMotion.deltaVelocity(
                attitude, new SixDofDriftMotion.Intent(0.0, 0.0, 1.0), Vec3.ZERO,
                10.0, 0.0, 0.1));
    }

    @Test
    void diagonalIntentHasTheSameAccelerationMagnitudeAsOneAxisIntent() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();

        Vec3 delta = SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(1.0, 1.0, 1.0), Vec3.ZERO, 10.0, 0.0, 0.1);

        double component = 1.0 / Math.sqrt(3.0);
        assertVectorEquals(new Vec3(-component, component, component), delta);
        assertEquals(1.0, delta.norm(), EPSILON);
    }

    @Test
    void partialAnalogIntentPreservesItsThrustMagnitudeBelowTheNormalizationThreshold() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();

        Vec3 delta = SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(0.2, 0.0, 0.0), Vec3.ZERO, 10.0, 0.0, 0.1);

        assertVectorEquals(new Vec3(0.0, 0.0, 0.2), delta);
    }

    @Test
    void noEffectiveIntentAndNonFiniteIntentAxesProduceNoDeltaVelocity() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();

        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(0.0, 0.0, 0.0), Vec3.ZERO, 10.0, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(Double.NaN, Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY), Vec3.ZERO, 10.0, 0.0, 0.1));
    }

    @Test
    void invalidInputsAndConfigurationNeverProduceNonFiniteAcceleration() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();
        SixDofDriftMotion.Intent forward = new SixDofDriftMotion.Intent(1.0, 0.0, 0.0);

        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(null, forward, Vec3.ZERO,
                10.0, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, null, Vec3.ZERO,
                10.0, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward, null,
                10.0, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward,
                new Vec3(Double.NaN, 0.0, 0.0), 10.0, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward, Vec3.ZERO,
                Double.NaN, 0.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward, Vec3.ZERO,
                10.0, -1.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward, Vec3.ZERO,
                10.0, 0.0, Double.POSITIVE_INFINITY));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward, Vec3.ZERO,
                10.0, 0.0, 0.0));
    }

    @Test
    void speedLimitStopsAtBoundaryAndBlocksOutwardOrTangentialInput() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();
        SixDofDriftMotion.Intent forward = new SixDofDriftMotion.Intent(1.0, 0.0, 0.0);

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), SixDofDriftMotion.deltaVelocity(
                attitude, forward, new Vec3(0.0, 0.0, 4.0), 30.0, 5.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude, forward,
                new Vec3(0.0, 0.0, 5.0), 10.0, 5.0, 0.1));
        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 5.0),
                10.0, 5.0, 0.1));
    }

    @Test
    void speedLimitAllowsBrakingButNeverLetsOneStepAcceleratePastItsMinimumSpeed() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();
        SixDofDriftMotion.Intent backward = new SixDofDriftMotion.Intent(-1.0, 0.0, 0.0);

        assertVectorEquals(new Vec3(0.0, 0.0, -1.0), SixDofDriftMotion.deltaVelocity(
                attitude, backward, new Vec3(0.0, 0.0, 6.0), 10.0, 5.0, 0.1));
        assertVectorEquals(new Vec3(0.0, 0.0, -5.0), SixDofDriftMotion.deltaVelocity(
                attitude, backward, new Vec3(0.0, 0.0, 5.0), 100.0, 5.0, 0.1));
    }

    @Test
    void diagonalInputAtTheLimitMustReduceTotalSpeedDespiteItsTangentialComponent() {
        SixDofCameraOrientation.Snapshot attitude = identityOrientation().current();
        Vec3 current = new Vec3(0.0, 0.0, 5.0);

        Vec3 delta = SixDofDriftMotion.deltaVelocity(attitude,
                new SixDofDriftMotion.Intent(-1.0, 1.0, 0.0), current,
                10.0, 5.0, 0.1);

        assertVectorEquals(new Vec3(-Math.sqrt(0.5), 0.0, -Math.sqrt(0.5)), delta);
        assertTrue(current.add(delta).norm() < current.norm());
    }

    private static SixDofCameraOrientation identityOrientation() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        return orientation;
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
