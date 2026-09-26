package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the ON-only reference geometry and bounded correction flows. */
class SixDofHorizonAlignmentTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void allSixReferenceNormalsProduceFiniteOrthonormalReferenceFrames() {
        for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
            Vec3 normal = reference.normal();
            Vec3 forward = normal.cross(new Vec3(1.0, 2.0, 3.0));
            if (forward.norm() < 0.1) {
                forward = normal.cross(new Vec3(0.0, 0.0, 1.0));
            }
            forward = forward.normalize();
            Vec3 up = forward.cross(normal).normalize();

            SixDofHorizonAlignment.Frame frame = SixDofHorizonAlignment.frame(normal, forward, up);

            assertEquals(0.0, frame.referencePitchRadians(), EPSILON);
            assertEquals(1.0, frame.left().norm(), EPSILON);
            assertEquals(1.0, frame.targetUp().norm(), EPSILON);
            assertEquals(0.0, frame.left().dot(frame.targetUp()), EPSILON);
            assertEquals(0.0, frame.targetUp().dot(forward), EPSILON);
        }
    }

    @Test
    void bothReferencePolesAndNearPoleUseTheCurrentUpProjectionWithoutNan() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        Vec3 currentUp = new Vec3(0.0, 0.0, 1.0);

        SixDofHorizonAlignment.Frame positive = SixDofHorizonAlignment.frame(
                normal, normal, currentUp);
        SixDofHorizonAlignment.Frame negative = SixDofHorizonAlignment.frame(
                normal, normal.negate(), currentUp);
        Vec3 nearForward = new Vec3(1.0e-12, Math.sqrt(1.0 - 1.0e-24), 0.0);
        SixDofHorizonAlignment.Frame near = SixDofHorizonAlignment.frame(
                normal, nearForward, currentUp);

        assertVectorEquals(currentUp, positive.targetUp());
        assertVectorEquals(currentUp, negative.targetUp());
        assertTrue(Double.isFinite(near.referencePitchRadians()));
        assertTrue(Double.isFinite(near.left().x()));
        assertTrue(Double.isFinite(near.targetUp().z()));
        assertEquals(1.0, near.targetUp().norm(), EPSILON);
    }

    @Test
    void signedBankErrorUsesBothQuarterTurnsAndPositivePiForTheAntipodalTie() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);

        orientation.applyExplicitRollDegrees(90.0);
        assertEquals(-90.0, orientation.alignHorizon(new Vec3(0.0, 1.0, 0.0), 0.0, false)
                .bankErrorDegrees(), EPSILON);

        orientation.reset(0.0, 0.0);
        orientation.applyExplicitRollDegrees(-90.0);
        assertEquals(90.0, orientation.alignHorizon(new Vec3(0.0, 1.0, 0.0), 0.0, false)
                .bankErrorDegrees(), EPSILON);

        orientation.reset(0.0, 0.0);
        orientation.applyExplicitRollDegrees(180.0);
        assertEquals(180.0, orientation.alignHorizon(new Vec3(0.0, 1.0, 0.0), 0.0, false)
                .bankErrorDegrees(), EPSILON);
    }

    @Test
    void alignedMouseStopsAnEightyDegreeAimFromCrossingToOneHundredTwentyFiveDegrees() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(-80.0, 0.0);

        SixDofCameraOrientation.AlignedMouseResult result = orientation.applyAlignedMouseDelta(
                0, 10_000, 1.0, false, new Vec3(0.0, 1.0, 0.0));

        assertTrue(result.pitchLimited());
        assertEquals(89.0, result.referencePitchDegrees(), EPSILON);
        assertTrue(referencePitchDegrees(orientation, new Vec3(0.0, 1.0, 0.0)) <= 89.0 + EPSILON);
    }

    @Test
    void alignedMouseAllowsTheSoftBufferAtFullGainButHardGuardStillStopsThePole() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);

        SixDofCameraOrientation eightyFive = new SixDofCameraOrientation();
        eightyFive.reset(-85.0, 0.0);
        SixDofCameraOrientation.AlignedMouseResult eightyFiveResult = eightyFive.applyAlignedMouseDelta(
                0, 20, 0.5, false, normal);
        assertFalse(eightyFiveResult.pitchLimited());
        assertEquals(88.0, referencePitchDegrees(eightyFive, normal), EPSILON);

        SixDofCameraOrientation eightySeven = new SixDofCameraOrientation();
        eightySeven.reset(-87.0, 0.0);
        SixDofCameraOrientation.AlignedMouseResult eightySevenResult = eightySeven.applyAlignedMouseDelta(
                0, 10, 0.5, false, normal);
        assertFalse(eightySevenResult.pitchLimited());
        assertEquals(88.5, referencePitchDegrees(eightySeven, normal), EPSILON);

        SixDofCameraOrientation hard = new SixDofCameraOrientation();
        hard.reset(-88.0, 0.0);
        assertTrue(hard.applyAlignedMouseDelta(0, 10_000, 1.0, false, normal).pitchLimited());
        assertEquals(89.0, referencePitchDegrees(hard, normal), EPSILON);
    }

    @Test
    void inwardMouseEscapesTheSoftBufferAndYawKeepsItsFullUnclampedGain() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation inward = new SixDofCameraOrientation();
        inward.reset(-88.0, 0.0);
        assertFalse(inward.applyAlignedMouseDelta(0, -20, 0.5, false, normal).pitchLimited());
        assertEquals(85.0, referencePitchDegrees(inward, normal), EPSILON);

        SixDofCameraOrientation diagonal = new SixDofCameraOrientation();
        diagonal.reset(-87.0, 0.0);
        Vec3 beforeHeading = SixDofHorizonAlignment.frame(normal,
                diagonal.forward(), diagonal.up()).left().cross(normal);
        MouseAngles mouse = mouseAngles(20, 10, 0.5, false);
        assertFalse(diagonal.applyAlignedMouseDelta(20, 10, 0.5, false, normal).pitchLimited());
        assertEquals(88.5, referencePitchDegrees(diagonal, normal), EPSILON);
        Vec3 actualHeading = SixDofHorizonAlignment.frame(normal,
                diagonal.forward(), diagonal.up()).left().cross(normal);
        assertVectorEquals(rotateAroundAxis(beforeHeading, normal, -mouse.yawRadians()), actualHeading);
    }

    @Test
    void softPitchReturnTargetsEightySixWithoutRecenteringNormalPitch() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation normalPitch = new SixDofCameraOrientation();
        normalPitch.reset(-85.0, 0.0);
        SixDofCameraOrientation.Snapshot before = normalPitch.current();
        SixDofCameraOrientation.HorizonResult noReturn = normalPitch.alignHorizon(normal, 0.10, false);
        assertSnapshotEquals(before, normalPitch.current());
        assertEquals(0.0, noReturn.acquireDegreesPerSecond(), EPSILON);

        SixDofCameraOrientation overflow = new SixDofCameraOrientation();
        overflow.reset(-88.0, 0.0);
        double previous = 88.0;
        for (int step = 0; step < 5; step++) {
            overflow.alignHorizon(normal, 0.05, true);
            double current = referencePitchDegrees(overflow, normal);
            assertTrue(current < previous);
            assertTrue(current > 86.0);
            previous = current;
        }
        assertEquals(87.0, previous, EPSILON);
    }

    @Test
    void defaultEmergencyReturnCrossesIntoTheSoftProfileWithinTheSameTick() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation pole = new SixDofCameraOrientation();
        pole.reset(-90.0, 0.0);
        SixDofCameraOrientation.HorizonResult first = pole.alignHorizon(normal, 0.05, true);
        assertEquals(88.998, referencePitchDegrees(pole, normal), 0.002);
        assertTrue(first.acquireDegreesPerSecond() > 0.0);

        for (double inheritedPitch : new double[] {89.1, 89.9, 90.0, -89.1, -89.9, -90.0}) {
            for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
                SixDofCameraOrientation orientation = orientationForNormal(reference);
                orientation.applyDegrees(-inheritedPitch, 0.0, 0.0);
                orientation.applyExplicitRollDegrees(37.0);
                double beforeMagnitude = Math.abs(referencePitchDegrees(orientation, reference.normal()));
                orientation.alignHorizon(reference.normal(), 0.05, true);
                double afterMagnitude = Math.abs(referencePitchDegrees(orientation, reference.normal()));
                assertTrue(afterMagnitude < beforeMagnitude);
                assertUnitAndFinite(orientation.current());
            }
        }
    }

    @Test
    void softPitchScalarFlowAgreesAcrossSubdivisionsIncludingTheProfileCrossing() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation single = overSoftPitchCamera(89.9);
        SixDofCameraOrientation halves = overSoftPitchCamera(89.9);
        SixDofCameraOrientation tenths = overSoftPitchCamera(89.9);

        single.alignHorizon(normal, 0.10, true);
        for (int step = 0; step < 2; step++) {
            halves.alignHorizon(normal, 0.05, true);
        }
        for (int step = 0; step < 10; step++) {
            tenths.alignHorizon(normal, 0.01, true);
        }
        assertSnapshotEquals(single.current(), halves.current());
        assertSnapshotEquals(single.current(), tenths.current());
    }

    @Test
    void tinyPitchStepsAndTuningValidationRemainFinite() {
        double tiny = SixDofHorizonAlignment.boundedSoftPitchStepToZero(
                Math.toRadians(2.0), Math.toRadians(3.0), 1.0e-12,
                0.25, Math.toRadians(30.0), 0.12, Math.toRadians(90.0));
        assertTrue(Double.isFinite(tiny));
        assertTrue(tiny > 0.0);

        assertThrows(IllegalArgumentException.class, () -> new SixDofHorizonAlignment.Tuning(
                86.0, 86.0, 0.20, 180.0, 0.25, 30.0, 0.12, 90.0, 0.10));
        assertThrows(IllegalArgumentException.class, () -> new SixDofHorizonAlignment.Tuning(
                86.0, 89.0, 0.20, 180.0, 0.0, 30.0, 0.12, 90.0, 0.10));
        assertThrows(IllegalArgumentException.class, () -> new SixDofHorizonAlignment.Tuning(
                86.0, 90.0, 0.20, 180.0, 0.25, 30.0, 0.12, 90.0, 0.10));
    }

    @Test
    void diagonalMouseCapAndUnboundedYawKeepTheReferencePitchProtected() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);

        orientation.applyAlignedMouseDelta(400, -300, 1.0, false, normal);
        assertTrue(Math.abs(referencePitchDegrees(orientation, normal)) <= 89.0 + EPSILON);
        for (int step = 0; step < 1_000; step++) {
            orientation.applyAlignedMouseDelta(Integer.MAX_VALUE, 0, 1.0, false, normal);
        }

        assertTrue(Math.abs(referencePitchDegrees(orientation, normal)) <= 89.0 + EPSILON);
        assertUnitAndFinite(orientation.current());
    }

    @Test
    void alignedMouseMatchesAnIndependentReferenceFrameOracleForAllSixNormals() {
        for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
            Vec3 normal = reference.normal();
            SixDofCameraOrientation orientation = orientationForNormal(reference);
            orientation.applyDegrees(12.0, 17.0, 31.0);
            SixDofCameraOrientation.Snapshot before = orientation.current();
            MouseAngles mouse = mouseAngles(10, -8, 0.5, false);
            Basis expected = alignedOracle(before, normal, mouse);

            SixDofCameraOrientation.AlignedMouseResult result = orientation.applyAlignedMouseDelta(
                    10, -8, 0.5, false, normal);

            assertFalse(result.pitchLimited());
            assertVectorEquals(expected.forward(), orientation.forward());
            assertVectorEquals(expected.up(), orientation.up());
        }
    }

    @Test
    void pitchClampDoesNotDiscardYawAndRepeatedYawDoesNotLockHeading() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation clamped = new SixDofCameraOrientation();
        clamped.reset(-80.0, 0.0);
        SixDofHorizonAlignment.Frame beforeClamp = SixDofHorizonAlignment.frame(normal,
                clamped.forward(), clamped.up());
        Vec3 headingBeforeClamp = beforeClamp.left().cross(normal);
        MouseAngles diagonal = mouseAngles(5_000, 10_000, 1.0, false);

        assertTrue(clamped.applyAlignedMouseDelta(5_000, 10_000, 1.0, false, normal)
                .pitchLimited());
        Vec3 expectedHeading = rotateAroundAxis(headingBeforeClamp, normal, -diagonal.yawRadians());
        Vec3 actualHeading = SixDofHorizonAlignment.frame(normal,
                clamped.forward(), clamped.up()).left().cross(normal);
        assertVectorEquals(expectedHeading, actualHeading);

        SixDofCameraOrientation yawOnly = new SixDofCameraOrientation();
        yawOnly.reset(0.0, 0.0);
        Vec3 initialHeading = new Vec3(0.0, 0.0, 1.0);
        for (int step = 0; step < 1_001; step++) {
            yawOnly.applyAlignedMouseDelta(Integer.MAX_VALUE, 0, 1.0, false, normal);
        }
        assertVectorEquals(rotateAroundAxis(initialHeading, normal,
                -1_001.0 * Math.toRadians(45.0)), yawOnly.forward());
    }

    @Test
    void realAlignmentMovesAllReferencePolesTowardTheProtectiveBandWithoutFlippingNormal() {
        for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
            Vec3 normal = reference.normal();
            for (double pitchDegrees : new double[] {-90.0, 90.0}) {
                SixDofCameraOrientation orientation = orientationForNormal(reference);
                orientation.applyDegrees(pitchDegrees, 0.0, 0.0);
                double normalSign = Math.signum(orientation.forward().dot(normal));
                double beforePitch = Math.abs(referencePitchDegrees(orientation, normal));

                SixDofCameraOrientation.HorizonResult first = orientation.alignHorizon(
                        normal, 0.01, false);
                double afterPitch = Math.abs(referencePitchDegrees(orientation, normal));

                assertTrue(afterPitch < beforePitch);
                assertTrue(beforePitch - afterPitch <= 1.0 + EPSILON);
                assertTrue(Math.abs(first.acquireDegreesPerSecond()) <= 90.0 + EPSILON);
                assertEquals(normalSign, Math.signum(orientation.forward().dot(normal)));
                for (int step = 0; step < 20; step++) {
                    orientation.alignHorizon(normal, 0.01, false);
                    assertUnitAndFinite(orientation.current());
                }
            }
        }

        SixDofCameraOrientation nearPole = new SixDofCameraOrientation();
        nearPole.reset(-90.0 + Math.toDegrees(1.0e-12), 0.0);
        nearPole.alignHorizon(new Vec3(0.0, 1.0, 0.0), 0.01, false);
        assertUnitAndFinite(nearPole.current());
    }

    @Test
    void correctionRatesAreCappedAndExactFlowsAgreeWithDeltaSubdivision() {
        SixDofHorizonAlignment.Tuning tuning = new SixDofHorizonAlignment.Tuning(
                1.0, 0.20, 90.0, 0.12, 45.0, 0.10);
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        SixDofCameraOrientation single = rolledAndOverPoleCamera();
        SixDofCameraOrientation subdivided = rolledAndOverPoleCamera();

        SixDofCameraOrientation.HorizonResult singleResult = single.alignHorizon(normal, 0.10, false, tuning);
        for (int step = 0; step < 10; step++) {
            subdivided.alignHorizon(normal, 0.01, false, tuning);
        }

        assertTrue(Math.abs(singleResult.autoBankDegreesPerSecond()) <= 90.0 + EPSILON);
        assertTrue(Math.abs(singleResult.acquireDegreesPerSecond()) <= 45.0 + EPSILON);
        assertSnapshotEquals(single.current(), subdivided.current());
    }

    @Test
    void zeroDeltaDoesNotSnapAndSuppressingBankPreservesManualRoll() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        orientation.applyExplicitRollDegrees(45.0);
        SixDofCameraOrientation.Snapshot before = orientation.current();
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);

        SixDofCameraOrientation.HorizonResult zero = orientation.alignHorizon(normal, 0.0, false);
        assertSnapshotEquals(before, orientation.current());
        assertEquals(0.0, zero.autoBankDegreesPerSecond(), EPSILON);

        SixDofCameraOrientation.HorizonResult suppressed = orientation.alignHorizon(normal, 0.10, true);
        assertSnapshotEquals(before, orientation.current());
        assertEquals(0.0, suppressed.autoBankDegreesPerSecond(), EPSILON);
        assertEquals(-45.0, suppressed.bankErrorDegrees(), EPSILON);
    }

    @Test
    void automaticBankConvergesWithoutChangingForwardAndSuppressedBankStillAcquires() {
        Vec3 normal = new Vec3(0.0, 1.0, 0.0);
        for (double rollDegrees : new double[] {90.0, 180.0}) {
            SixDofCameraOrientation orientation = new SixDofCameraOrientation();
            orientation.reset(0.0, 0.0);
            orientation.applyExplicitRollDegrees(rollDegrees);
            Vec3 forward = orientation.forward();
            double previousError = Double.POSITIVE_INFINITY;
            for (int step = 0; step < 100; step++) {
                SixDofCameraOrientation.HorizonResult result = orientation.alignHorizon(normal, 0.05, false);
                assertTrue(Math.abs(result.bankErrorDegrees()) <= previousError + EPSILON);
                assertVectorEquals(forward, orientation.forward());
                previousError = Math.abs(result.bankErrorDegrees());
            }
            // Independent closed-form oracle: the initial capped-rate interval is not
            // exponential time. In particular, a 180-degree bank still exceeds 1e-5
            // degrees after five seconds with the specified 180 deg/s and 0.20 s tuning.
            double transitionDegrees = 180.0 * 0.20 / Math.log(2.0);
            double linearSeconds = (rollDegrees - transitionDegrees) / 180.0;
            double expectedRemaining = transitionDegrees * Math.pow(0.5,
                    (5.0 - linearSeconds) / 0.20);
            assertEquals(expectedRemaining, Math.abs(orientation.alignHorizon(normal, 0.0, false)
                    .bankErrorDegrees()), EPSILON);
        }

        SixDofCameraOrientation suppressed = new SixDofCameraOrientation();
        suppressed.reset(-89.5, 0.0);
        suppressed.applyExplicitRollDegrees(45.0);
        double beforePitch = Math.abs(referencePitchDegrees(suppressed, normal));
        SixDofCameraOrientation.HorizonResult result = suppressed.alignHorizon(normal, 0.10, true);
        assertEquals(0.0, result.autoBankDegreesPerSecond(), EPSILON);
        assertTrue(Math.abs(result.acquireDegreesPerSecond()) > 0.0);
        assertTrue(Math.abs(referencePitchDegrees(suppressed, normal)) < beforePitch);
    }

    private static SixDofCameraOrientation rolledAndOverPoleCamera() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(-89.5, 0.0);
        orientation.applyExplicitRollDegrees(180.0);
        return orientation;
    }

    private static SixDofCameraOrientation overSoftPitchCamera(double pitchDegrees) {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(-pitchDegrees, 0.0);
        return orientation;
    }

    private static SixDofCameraOrientation orientationForNormal(
            SixDofAlignmentInput.Reference reference) {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        switch (reference) {
            case Y_POS -> orientation.reset(0.0, 0.0);
            case Y_NEG -> {
                orientation.reset(0.0, 0.0);
                orientation.applyExplicitRollDegrees(180.0);
            }
            case X_POS -> {
                orientation.reset(0.0, 0.0);
                orientation.applyExplicitRollDegrees(-90.0);
            }
            case X_NEG -> {
                orientation.reset(0.0, 0.0);
                orientation.applyExplicitRollDegrees(90.0);
            }
            case Z_POS -> orientation.reset(90.0, 0.0);
            case Z_NEG -> orientation.reset(-90.0, 0.0);
        }
        return orientation;
    }

    private static Basis alignedOracle(SixDofCameraOrientation.Snapshot before,
            Vec3 normal, MouseAngles mouse) {
        SixDofHorizonAlignment.Frame beforeFrame = SixDofHorizonAlignment.frame(
                normal, before.forward(), before.up());
        Vec3 heading = beforeFrame.left().cross(normal);
        Vec3 yawedHeading = rotateAroundAxis(heading, normal, -mouse.yawRadians());
        double limit = Math.toRadians(89.0);
        double targetPitch = SixDofHorizonAlignment.protectedPitchTarget(
                beforeFrame.referencePitchRadians(), mouse.pitchRadians(), limit);
        Vec3 forward = yawedHeading.scale(Math.cos(targetPitch))
                .add(normal.scale(Math.sin(targetPitch)));
        SixDofHorizonAlignment.Frame targetFrame = SixDofHorizonAlignment.frame(
                normal, forward, before.up());
        double bankError = SixDofHorizonAlignment.signedBankErrorRadians(
                before.forward(), before.up(), beforeFrame.targetUp());
        Vec3 up = rotateAroundAxis(targetFrame.targetUp(), forward, -bankError);
        return new Basis(forward, up);
    }

    private static MouseAngles mouseAngles(int deltaX, int deltaY,
            double sensitivity, boolean invertMouse) {
        double multiplier = Math.pow(sensitivity * 0.6 + 0.2, 3.0) * 8.0 * 0.15;
        double yawDegrees = (double) deltaX * multiplier;
        double pitchDegrees = (invertMouse ? (double) deltaY : -(double) deltaY) * multiplier;
        double magnitude = Math.hypot(yawDegrees, pitchDegrees);
        if (magnitude > 45.0) {
            double scale = 45.0 / magnitude;
            yawDegrees *= scale;
            pitchDegrees *= scale;
        }
        return new MouseAngles(Math.toRadians(yawDegrees), Math.toRadians(pitchDegrees));
    }

    private static Vec3 rotateAroundAxis(Vec3 vector, Vec3 unitAxis, double angleRadians) {
        double cosine = Math.cos(angleRadians);
        double sine = Math.sin(angleRadians);
        return vector.scale(cosine)
                .add(unitAxis.cross(vector).scale(sine))
                .add(unitAxis.scale(unitAxis.dot(vector) * (1.0 - cosine)));
    }

    private static double referencePitchDegrees(SixDofCameraOrientation orientation, Vec3 normal) {
        return Math.toDegrees(SixDofHorizonAlignment.frame(normal,
                orientation.forward(), orientation.up()).referencePitchRadians());
    }

    private static void assertUnitAndFinite(SixDofCameraOrientation.Snapshot snapshot) {
        assertTrue(Double.isFinite(snapshot.forward().x()));
        assertTrue(Double.isFinite(snapshot.up().y()));
        assertEquals(1.0, snapshot.forward().norm(), EPSILON);
        assertEquals(1.0, snapshot.up().norm(), EPSILON);
        assertEquals(0.0, snapshot.forward().dot(snapshot.up()), EPSILON);
    }

    private static void assertSnapshotEquals(SixDofCameraOrientation.Snapshot expected,
            SixDofCameraOrientation.Snapshot actual) {
        assertVectorEquals(expected.forward(), actual.forward());
        assertVectorEquals(expected.right(), actual.right());
        assertVectorEquals(expected.up(), actual.up());
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private record Basis(Vec3 forward, Vec3 up) {
    }

    private record MouseAngles(double yawRadians, double pitchRadians) {
    }
}
