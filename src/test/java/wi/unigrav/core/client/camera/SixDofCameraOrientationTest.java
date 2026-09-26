package wi.unigrav.core.client.camera;

import java.util.Random;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the pure local-space FREE orientation contract used by the camera adapter. */
class SixDofCameraOrientationTest {

    private static final double EPSILON = 1.0e-12;
    private static final double VECTOR_EPSILON = 2.0e-12;

    @Test
    void vanillaBasisAtZeroPitchAndYaw() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        assertTrue(orientation.isInitialized());
        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), orientation.forward());
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), orientation.right());
        assertVectorEquals(new Vec3(0.0, 1.0, 0.0), orientation.up());
        assertEquals(1.0, orientation.current().components().w(), EPSILON);
    }

    @Test
    void minecraftYawAndPitchSignsArePreserved() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();

        orientation.reset(0.0, 90.0);
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), orientation.forward());

        orientation.reset(90.0, 0.0);
        assertVectorEquals(new Vec3(0.0, -1.0, 0.0), orientation.forward());

        orientation.reset(0.0, 0.0);
        orientation.apply(0.0, 1.0, 0.0, 0.1, 900.0);
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), orientation.forward());

        orientation.reset(0.0, 0.0);
        orientation.apply(1.0, 0.0, 0.0, 0.1, 900.0);
        assertVectorEquals(new Vec3(0.0, -1.0, 0.0), orientation.forward());
    }

    @Test
    void rollInputIsOnlyALocalForwardRotationAtAnArbitraryAttitude() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(31.0, -47.0);
        SixDofCameraOrientation.Snapshot before = orientation.current();
        Basis expected = applyLocalVector(before, 0.0, 0.0, 90.0);

        orientation.applyExplicitRollDegrees(90.0);

        assertVectorEquals(before.forward(), orientation.forward());
        assertBasisEquals(expected, orientation.current());
        SixDofCameraOrientation.Snapshot afterRoll = orientation.current();
        Basis expectedAfterMouse = applyLocalVector(afterRoll, 1.2, 1.5, 0.0);

        orientation.applyMouseDelta(10, -8, 0.5, false);

        assertBasisEquals(expectedAfterMouse, orientation.current());
    }

    @Test
    void combinedRotationsRemainUnitOrthogonalAndHanded() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(-31.0, 47.0);
        orientation.apply(0.7, -0.8, 1.0, 0.08, 245.0);

        assertOrthonormal(orientation.current());
    }

    @Test
    void repeatedFullTurnsRemainStableAndCanonical() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        for (int turn = 0; turn < 100; turn++) {
            for (int step = 0; step < 10; step++) {
                orientation.apply(0.0, 0.0, 1.0, 0.1, 360.0);
            }
        }

        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), orientation.forward());
        assertVectorEquals(new Vec3(-1.0, 0.0, 0.0), orientation.right());
        assertVectorEquals(new Vec3(0.0, 1.0, 0.0), orientation.up());
        assertTrue(orientation.components().w() > 0.0);
    }

    @Test
    void invalidInputCannotCreateNanAndAxesAndDeltaAreBounded() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        SixDofCameraOrientation.Snapshot initial = orientation.current();

        orientation.apply(Double.NaN, 0.0, 0.0, 0.05, 90.0);
        orientation.apply(0.0, Double.POSITIVE_INFINITY, 0.0, 0.05, 90.0);
        orientation.apply(0.0, 0.0, 0.0, Double.NaN, 90.0);
        orientation.apply(0.0, 0.0, 0.0, 0.05, Double.NEGATIVE_INFINITY);
        orientation.apply(0.0, 0.0, 0.0, 0.05, -1.0);
        orientation.reset(Double.NaN, 0.0);

        assertSnapshotEquals(initial, orientation.current());
        orientation.apply(2.0, 0.0, 0.0, 0.05, 90.0);
        assertVectorEquals(new Vec3(0.0, -Math.sin(Math.toRadians(4.5)),
                Math.cos(Math.toRadians(4.5))), orientation.forward());
        assertFinite(orientation.current());
    }

    @Test
    void deltaClampClearAndResetAreObservable() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        orientation.apply(1.0, 0.0, 0.0, 10.0, 900.0);
        assertVectorEquals(new Vec3(0.0, -1.0, 0.0), orientation.forward());

        orientation.clear();
        assertFalse(orientation.isInitialized());
        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), orientation.current().forward());
        orientation.apply(0.0, 1.0, 0.0, 0.1, 90.0);
        assertVectorEquals(new Vec3(0.0, 0.0, 1.0), orientation.forward());

        orientation.reset(0.0, -90.0);
        assertTrue(orientation.isInitialized());
        assertVectorEquals(new Vec3(1.0, 0.0, 0.0), orientation.forward());
    }

    @Test
    void mouseDeltasUseVanillaSensitivityCurveAndMinecraftAxisSigns() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        orientation.applyMouseDelta(10, 0, 0.5, false);
        double yawRadians = Math.toRadians(1.5);
        assertVectorEquals(new Vec3(-Math.sin(yawRadians), 0.0, Math.cos(yawRadians)),
                orientation.forward());

        orientation.reset(0.0, 0.0);
        orientation.applyMouseDelta(0, 10, 0.5, false);
        double pitchRadians = Math.toRadians(1.5);
        assertVectorEquals(new Vec3(0.0, Math.sin(pitchRadians), Math.cos(pitchRadians)),
                orientation.forward());
    }

    @Test
    void horizontalMouseYawUsesTheCurrentLocalUpAfterPitch() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(45.0, 0.0);
        SixDofCameraOrientation.Snapshot before = orientation.current();
        Basis expected = applyLocalVector(before, 0.0, 1.5, 0.0);

        orientation.applyMouseDelta(10, 0, 0.5, false);

        assertBasisEquals(expected, orientation.current());
        assertTrue(Math.abs(orientation.right().y()) > 0.01);
    }

    @Test
    void largeRolledMouseVectorMatchesOneLocalQuaternionWithoutRelevel() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(37.0, -23.0);
        orientation.applyExplicitRollDegrees(41.0);
        SixDofCameraOrientation.Snapshot before = orientation.current();
        Basis expected = applyLocalVector(before, 27.0, 36.0, 0.0);

        orientation.applyMouseDelta(400, -300, 1.0, false);

        assertBasisEquals(expected, orientation.current());
        assertEquals(45.0, orientationDistanceDegrees(before, orientation.current()), 1.0e-9);
    }

    @Test
    void invertMouseChangesOnlyTheLocalPitchComponent() {
        SixDofCameraOrientation normal = initializedRolledCamera();
        SixDofCameraOrientation inverted = initializedRolledCamera();
        SixDofCameraOrientation oppositePitch = initializedRolledCamera();
        SixDofCameraOrientation.Snapshot before = normal.current();
        Basis positivePitch = applyLocalVector(before, 27.0, 36.0, 0.0);
        Basis negativePitch = applyLocalVector(before, -27.0, 36.0, 0.0);

        normal.applyMouseDelta(400, -300, 1.0, false);
        inverted.applyMouseDelta(400, 300, 1.0, true);
        oppositePitch.applyMouseDelta(400, 300, 1.0, false);

        assertBasisEquals(positivePitch, normal.current());
        assertBasisEquals(positivePitch, inverted.current());
        assertBasisEquals(negativePitch, oppositePitch.current());
        assertTrue(orientationDistanceDegrees(normal.current(), oppositePitch.current()) > 1.0);
    }

    @Test
    void freeMousePitchCrossesBothEulerPolesAsOneContinuousLocalRotation() {
        SixDofCameraOrientation positive = new SixDofCameraOrientation();
        positive.reset(89.0, 0.0);
        SixDofCameraOrientation.Snapshot positiveBefore = positive.current();
        Basis positiveExpected = applyLocalVector(positiveBefore, 1.5, 0.0, 0.0);

        positive.applyMouseDelta(0, -10, 0.5, false);

        assertBasisEquals(positiveExpected, positive.current());
        assertFinite(positive.current());

        SixDofCameraOrientation negative = new SixDofCameraOrientation();
        negative.reset(-89.0, 0.0);
        SixDofCameraOrientation.Snapshot negativeBefore = negative.current();
        Basis negativeExpected = applyLocalVector(negativeBefore, -1.5, 0.0, 0.0);

        negative.applyMouseDelta(0, 10, 0.5, false);

        assertBasisEquals(negativeExpected, negative.current());
        assertFinite(negative.current());
    }

    @Test
    void rolledMouseAxesRemainCameraLocalAtQuarterRoll() {
        SixDofCameraOrientation horizontal = new SixDofCameraOrientation();
        horizontal.reset(0.0, 0.0);
        horizontal.applyExplicitRollDegrees(90.0);
        SixDofCameraOrientation.Snapshot horizontalBefore = horizontal.current();
        Basis horizontalExpected = applyLocalVector(horizontalBefore, 0.0, 1.5, 0.0);

        horizontal.applyMouseDelta(10, 0, 0.5, false);

        assertBasisEquals(horizontalExpected, horizontal.current());

        SixDofCameraOrientation vertical = new SixDofCameraOrientation();
        vertical.reset(0.0, 0.0);
        vertical.applyExplicitRollDegrees(90.0);
        SixDofCameraOrientation.Snapshot verticalBefore = vertical.current();
        Basis verticalExpected = applyLocalVector(verticalBefore, 1.5, 0.0, 0.0);

        vertical.applyMouseDelta(0, -10, 0.5, false);

        assertBasisEquals(verticalExpected, vertical.current());
    }

    @Test
    void upsideDownMouseUsesTheVisibleLocalAxesWithoutHemisphereReflection() {
        SixDofCameraOrientation horizontal = new SixDofCameraOrientation();
        horizontal.reset(180.0, 0.0);
        SixDofCameraOrientation.Snapshot horizontalBefore = horizontal.current();
        Basis horizontalExpected = applyLocalVector(horizontalBefore, 0.0, 1.5, 0.0);

        horizontal.applyMouseDelta(10, 0, 0.5, false);

        assertBasisEquals(horizontalExpected, horizontal.current());

        SixDofCameraOrientation vertical = new SixDofCameraOrientation();
        vertical.reset(180.0, 0.0);
        SixDofCameraOrientation.Snapshot verticalBefore = vertical.current();
        Basis verticalExpected = applyLocalVector(verticalBefore, 1.5, 0.0, 0.0);

        vertical.applyMouseDelta(0, -10, 0.5, false);

        assertBasisEquals(verticalExpected, vertical.current());
    }

    @Test
    void genericLocalRotationNeverDisablesSubsequentFreeMouseInput() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(20.0, -30.0);
        orientation.applyDegrees(7.0, 11.0, 13.0);
        SixDofCameraOrientation.Snapshot beforeMouse = orientation.current();
        Basis expected = applyLocalVector(beforeMouse, 1.2, 1.5, 0.0);

        orientation.applyMouseDelta(10, -8, 0.5, false);

        assertBasisEquals(expected, orientation.current());
    }

    @Test
    void genericDegreesAndMouseUseTheSameLocalQuaternionContract() {
        SixDofCameraOrientation mouse = initializedRolledCamera();
        SixDofCameraOrientation degrees = initializedRolledCamera();

        mouse.applyMouseDelta(10, -8, 0.5, false);
        degrees.applyDegrees(1.2, 1.5, 0.0);

        assertSnapshotEquals(degrees.current(), mouse.current());
    }

    @Test
    void randomMouseSequenceMatchesTheIndependentLocalRotationOracle() {
        Random random = new Random(0x4652454543414dL);
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(73.0, -119.0);
        orientation.applyExplicitRollDegrees(67.0);

        for (int step = 0; step < 1_000; step++) {
            int deltaX = random.nextInt(801) - 400;
            int deltaY = random.nextInt(801) - 400;
            double sensitivity = random.nextDouble();
            boolean invertMouse = random.nextBoolean();
            SixDofCameraOrientation.Snapshot before = orientation.current();
            MouseAngles angles = mouseAngles(deltaX, deltaY, sensitivity, invertMouse);
            Basis expected = applyLocalVector(before,
                    angles.pitchDegrees(), angles.yawDegrees(), 0.0);

            orientation.applyMouseDelta(deltaX, deltaY, sensitivity, invertMouse);

            assertBasisEquals(expected, orientation.current());
            assertOrthonormal(orientation.current());
        }
    }

    @Test
    void invalidMouseInputPreservesFiniteStateAndSensitivityIsClamped() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.applyDegrees(10.0, 20.0, 30.0);
        assertFalse(orientation.isInitialized());

        orientation.reset(0.0, 0.0);
        SixDofCameraOrientation.Snapshot initial = orientation.current();
        orientation.applyDegrees(Double.NaN, 0.0, 0.0);
        orientation.applyDegrees(0.0, Double.POSITIVE_INFINITY, 0.0);
        orientation.applyMouseDelta(1, 1, Double.NaN, false);
        orientation.applyMouseDelta(1, 1, Double.NEGATIVE_INFINITY, true);

        assertSnapshotEquals(initial, orientation.current());

        orientation.applyMouseDelta(100, 0, -1.0, false);
        double minimumSensitivityYaw = Math.toRadians(0.96);
        assertVectorEquals(new Vec3(-Math.sin(minimumSensitivityYaw), 0.0,
                Math.cos(minimumSensitivityYaw)), orientation.forward());
        assertFinite(orientation.current());
    }

    private static SixDofCameraOrientation initializedRolledCamera() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(37.0, -23.0);
        orientation.applyExplicitRollDegrees(41.0);
        return orientation;
    }

    private static MouseAngles mouseAngles(int deltaX, int deltaY,
            double sensitivity, boolean invertMouse) {
        double adjustedSensitivity = Math.max(0.0, Math.min(1.0, sensitivity));
        double multiplier = Math.pow(adjustedSensitivity * 0.6 + 0.2, 3.0) * 8.0 * 0.15;
        double yawDegrees = deltaX * multiplier;
        double pitchDegrees = (invertMouse ? deltaY : -deltaY) * multiplier;
        double magnitude = Math.hypot(yawDegrees, pitchDegrees);
        if (magnitude > 45.0) {
            double scale = 45.0 / magnitude;
            yawDegrees *= scale;
            pitchDegrees *= scale;
        }
        return new MouseAngles(pitchDegrees, yawDegrees);
    }

    private static Basis applyLocalVector(SixDofCameraOrientation.Snapshot before,
            double pitchDegrees, double yawDegrees, double rollDegrees) {
        Vec3 rotationVector = before.right().negate().scale(Math.toRadians(pitchDegrees))
                .add(before.up().scale(-Math.toRadians(yawDegrees)))
                .add(before.forward().scale(Math.toRadians(rollDegrees)));
        double angle = rotationVector.norm();
        if (angle == 0.0) {
            return new Basis(before.forward(), before.right(), before.up());
        }
        Vec3 axis = rotationVector.scale(1.0 / angle);
        return new Basis(
                rotateAroundUnitAxis(before.forward(), axis, angle),
                rotateAroundUnitAxis(before.right(), axis, angle),
                rotateAroundUnitAxis(before.up(), axis, angle));
    }

    private static void assertSnapshotEquals(SixDofCameraOrientation.Snapshot expected,
            SixDofCameraOrientation.Snapshot actual) {
        assertComponentsEquals(expected.components(), actual.components());
        assertBasisEquals(new Basis(expected.forward(), expected.right(), expected.up()), actual);
    }

    private static void assertComponentsEquals(SixDofCameraOrientation.Components expected,
            SixDofCameraOrientation.Components actual) {
        assertEquals(expected.w(), actual.w(), EPSILON);
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private static void assertBasisEquals(Basis expected,
            SixDofCameraOrientation.Snapshot actual) {
        assertVectorEquals(expected.forward(), actual.forward());
        assertVectorEquals(expected.right(), actual.right());
        assertVectorEquals(expected.up(), actual.up());
    }

    private static void assertOrthonormal(SixDofCameraOrientation.Snapshot snapshot) {
        Vec3 forward = snapshot.forward();
        Vec3 right = snapshot.right();
        Vec3 up = snapshot.up();
        SixDofCameraOrientation.Components components = snapshot.components();
        double quaternionNormSquared = components.w() * components.w()
                + components.x() * components.x()
                + components.y() * components.y()
                + components.z() * components.z();

        assertEquals(1.0, quaternionNormSquared, EPSILON);
        assertEquals(1.0, forward.norm(), EPSILON);
        assertEquals(1.0, right.norm(), EPSILON);
        assertEquals(1.0, up.norm(), EPSILON);
        assertEquals(0.0, forward.dot(right), EPSILON);
        assertEquals(0.0, forward.dot(up), EPSILON);
        assertEquals(0.0, right.dot(up), EPSILON);
        assertVectorEquals(right, forward.cross(up));
    }

    private static void assertFinite(SixDofCameraOrientation.Snapshot snapshot) {
        assertTrue(Double.isFinite(snapshot.components().w()));
        assertTrue(Double.isFinite(snapshot.components().x()));
        assertTrue(Double.isFinite(snapshot.components().y()));
        assertTrue(Double.isFinite(snapshot.components().z()));
        assertTrue(Double.isFinite(snapshot.forward().x()));
        assertTrue(Double.isFinite(snapshot.forward().y()));
        assertTrue(Double.isFinite(snapshot.forward().z()));
    }

    private static Vec3 rotateAroundUnitAxis(Vec3 vector, Vec3 unitAxis, double angleRadians) {
        double cosine = Math.cos(angleRadians);
        double sine = Math.sin(angleRadians);
        return vector.scale(cosine)
                .add(unitAxis.cross(vector).scale(sine))
                .add(unitAxis.scale(unitAxis.dot(vector) * (1.0 - cosine)));
    }

    private static double orientationDistanceDegrees(SixDofCameraOrientation.Snapshot first,
            SixDofCameraOrientation.Snapshot second) {
        double trace = first.forward().dot(second.forward())
                + first.right().dot(second.right())
                + first.up().dot(second.up());
        double cosine = Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5));
        return Math.toDegrees(Math.acos(cosine));
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), VECTOR_EPSILON);
        assertEquals(expected.y(), actual.y(), VECTOR_EPSILON);
        assertEquals(expected.z(), actual.z(), VECTOR_EPSILON);
    }

    private record Basis(Vec3 forward, Vec3 right, Vec3 up) {
    }

    private record MouseAngles(double pitchDegrees, double yawDegrees) {
    }
}
