package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests camera-local acceleration and speed limiting for zero-gravity drift. */
class SixDofDriftMotionTest {
    @Test void sampledQuaternionCarriesRollWithTheSameForwardAndTranslationBasis() {
        var orientation = identityOrientation();
        orientation.applyDegrees(0, 0, 90);
        var sample = orientation.current();
        var components = sample.components();
        var q = new dev.crlhitbox.api.geometry.Rotation3d(components.x(), components.y(), components.z(), components.w());
        var frame = SixDofMovementFrame.fromCamera(sample);
        Vec3 translation = SixDofDriftMotion.worldIntent(frame, new SixDofDriftMotion.Intent(0, 1, 0));
        var controls = wi.unigrav.core.client.PlayerPhysicsInput.world(translation, sample.forward(),
                false, false, false, false, 5, java.util.Optional.of(q));
        assertEquals(frame.right(), new Vec3(controls.translation().x(), controls.translation().y(), controls.translation().z()));
        assertEquals(new dev.crlhitbox.api.geometry.Vec3d(0, 0, 1), controls.forward());
        assertTrue(controls.desiredBodyAttitude().isPresent());
        assertTrue(Math.abs(controls.desiredBodyAttitude().orElseThrow().z()) > .7,
                "same forward must not lose the independently sampled roll");
    }

    private static final double EPSILON = 1.0e-12;

    @Test
    void committedXReferenceFreezesPhysicalAndLegacyMovementFrame() {
        SixDofAlignmentInput alignment = new SixDofAlignmentInput();
        alignment.update(false, false, null);
        alignment.update(false, true, SixDofAlignmentInput.Reference.X_POS);
        SixDofAlignmentInput.Change committed = alignment.update(
                false, false, SixDofAlignmentInput.Reference.X_POS);
        assertTrue(committed.committed());
        assertTrue(alignment.enabled());

        SixDofCameraOrientation orientation = identityOrientation();
        SixDofMovementFrameLock frameLock = new SixDofMovementFrameLock();
        frameLock.apply(committed, alignment, orientation.current());
        SixDofMovementFrame capturedFrame = frameLock.current(
                alignment.effectiveAlignment(false), orientation.current());
        SixDofDriftMotion.Intent forwardIntent = new SixDofDriftMotion.Intent(1.0, 0.0, 0.0);
        SixDofDriftMotion.Intent upIntent = new SixDofDriftMotion.Intent(0.0, 0.0, 1.0);

        Vec3 physicalControlsTranslation = SixDofDriftMotion.worldIntent(capturedFrame, upIntent);
        Vec3 legacyDeltaVelocity = SixDofDriftMotion.deltaVelocity(capturedFrame, upIntent,
                Vec3.ZERO, 10.0, 0.0, 0.1);
        orientation.applyDegrees(90.0, 0.0, 0.0);
        SixDofMovementFrame stillLocked = frameLock.current(
                alignment.effectiveAlignment(false), orientation.current());
        Vec3 turnedViewForward = SixDofDriftMotion.worldIntent(stillLocked, forwardIntent);

        // X+ was committed while Q.forward was +Z: W stays projected +Z, while Space/Shift
        // must use the selected normal even as Q turns. Both production movement entries use
        // the same captured frame while presentation and boost look direction retain live Q.
        assertAll(
                () -> assertVectorEquals(new Vec3(1.0, 0.0, 0.0), physicalControlsTranslation),
                () -> assertVectorEquals(new Vec3(1.0, 0.0, 0.0), legacyDeltaVelocity),
                () -> assertVectorEquals(new Vec3(0.0, 0.0, 1.0), turnedViewForward));
    }

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

        assertVectorEquals(Vec3.ZERO, SixDofDriftMotion.deltaVelocity(
                (SixDofCameraOrientation.Snapshot) null, forward, Vec3.ZERO,
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
