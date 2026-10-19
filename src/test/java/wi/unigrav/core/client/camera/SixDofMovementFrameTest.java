package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.PlayerPhysicsInput;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SixDofMovementFrameTest {

    private static final double EPSILON = 1.0e-12;

    @Test void acceptedUpWithLiveViewUsesTheSameBasisAsKeyboardInput() {
        for (var frame : CardinalFrame.values()) {
            var reference = new PlayerBodyReference.Effective(
                    PlayerBodyReference.Source.MANUAL, frame,
                    PlayerBodyReference.initialHeading(frame, 0));
            SixDofCameraOrientation orientation = identityOrientation();
            for (double yaw : new double[] {0, 45, 90, 180, 270}) {
                orientation.reset(0, yaw);
                var sample = orientation.current();
                var movement = SixDofMovementFrame.fromView(reference, sample);
                var input = PlayerPhysicsInput.keyboard(reference, 1, 0, sample.forward(),
                        false, false, false, false);
                assertControlVectorEquals(movement.forward(), input.translation());
                var up = frame.up();
                assertVectorEquals(new Vec3(up.x(), up.y(), up.z()), movement.up());
            }
        }
    }
    @Test
    void enabledAlignmentWithoutItsCapturedFrameFailsAtTheMovementBoundary() {
        SixDofAlignmentInput input = new SixDofAlignmentInput();
        SixDofMovementFrameLock frameLock = new SixDofMovementFrameLock();
        SixDofCameraOrientation orientation = identityOrientation();
        input.update(false, false, null);
        input.update(true, false, null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> frameLock.current(input.effectiveAlignment(false), orientation.current()));
        assertEquals("Enabled horizon alignment has no committed movement frame", failure.getMessage());
    }

    @Test
    void movementFrameEnforcesFiniteUnitOrthogonalAxes() {
        assertThrows(IllegalArgumentException.class, () -> new SixDofMovementFrame(
                new Vec3(0, 0, 2), new Vec3(-1, 0, 0), new Vec3(0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SixDofMovementFrame(
                new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SixDofMovementFrame(
                new Vec3(Double.NaN, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 1, 0)));
    }

    @Test
    void cameraSessionInvalidatesWhenEitherPlayerOrWorldIdentityChanges() {
        SixDofCameraSession session = new SixDofCameraSession();
        Object player = new Object();
        Object world = new Object();
        Object replacementWorld = new Object();
        Object replacementPlayer = new Object();

        assertTrue(session.update(player, world));
        assertFalse(session.update(player, world));
        assertTrue(session.update(player, replacementWorld));
        assertFalse(session.unloadWorld(world));
        assertFalse(session.update(player, replacementWorld));
        assertTrue(session.update(replacementPlayer, replacementWorld));
        assertTrue(session.unloadWorld(replacementWorld));
        assertTrue(session.update(replacementPlayer, replacementWorld),
                "loading the same object after unload starts a fresh camera session");
    }

    @Test
    void allSixCommittedNormalsMapWASDSpaceAndShiftToTheCapturedWorldFrame() {
        for (ExpectedFrame expected : expectedFrames()) {
            SixDofCameraOrientation orientation = identityOrientation();
            AlignmentState state = commitSelection(expected.reference(), orientation);
            SixDofMovementFrame frame = current(state, orientation);

            assertVectorEquals(expected.forward(), SixDofDriftMotion.worldIntent(frame, intent(1, 0, 0)));
            assertVectorEquals(expected.forward().scale(-1), SixDofDriftMotion.worldIntent(frame, intent(-1, 0, 0)));
            assertVectorEquals(expected.right(), SixDofDriftMotion.worldIntent(frame, intent(0, 1, 0)));
            assertVectorEquals(expected.right().scale(-1), SixDofDriftMotion.worldIntent(frame, intent(0, -1, 0)));
            assertVectorEquals(expected.normal(), SixDofDriftMotion.worldIntent(frame, intent(0, 0, 1)));
            assertVectorEquals(expected.normal().scale(-1), SixDofDriftMotion.worldIntent(frame, intent(0, 0, -1)));
            assertVectorEquals(expected.normal(), frame.up());

            Vec3 space = SixDofDriftMotion.worldIntent(frame, intent(0, 0, 1));
            var controls = PlayerPhysicsInput.world(space, orientation.forward(),
                    true, false, false, false, 1);
            assertControlVectorEquals(expected.normal(), controls.translation());
            assertControlVectorEquals(orientation.forward(), controls.forward());
            assertVectorEquals(expected.normal(), SixDofDriftMotion.deltaVelocity(frame,
                    intent(0, 0, 1), Vec3.ZERO, 10.0, 0.0, 0.1));

            orientation.applyDegrees(27.0, 61.0, 34.0);
            assertNotEquals(frame, SixDofMovementFrame.fromCamera(orientation.current()));
            assertSame(frame, current(state, orientation),
                    "the locked axes must outlive pitch, yaw, and roll changes to Q");
        }
    }

    @Test
    void polarCaptureUsesTheExistingHorizonFallbackForEveryCardinalNormal() {
        for (PolarFrame polar : polarFrames()) {
            SixDofCameraOrientation orientation = new SixDofCameraOrientation();
            orientation.reset(polar.pitchDegrees(), polar.yawDegrees());
            assertVectorEquals(polar.reference().normal(), orientation.forward());

            AlignmentState state = commitSelection(polar.reference(), orientation);
            SixDofMovementFrame frame = current(state, orientation);

            assertVectorEquals(polar.expectedProjectedForward(), frame.forward(),
                    "polar projected forward for " + polar.reference().label());
            assertVectorEquals(polar.reference().normal(), frame.up());
            assertVectorEquals(frame.forward().scale(-1), SixDofDriftMotion.worldIntent(frame, intent(-1, 0, 0)));
        }
    }

    @Test
    void committingTheSameNormalAgainCapturesTheNewHeading() {
        SixDofCameraOrientation orientation = identityOrientation();
        AlignmentState state = commitSelection(SixDofAlignmentInput.Reference.Y_POS, orientation);
        SixDofMovementFrame first = current(state, orientation);
        assertVectorEquals(new Vec3(0, 0, 1), first.forward());

        orientation.applyDegrees(0.0, 90.0, 0.0);
        SixDofAlignmentInput.Change selecting = state.input().updateBeforePicking(false, true);
        state.frameLock().apply(selecting, state.input(), orientation.current());
        SixDofAlignmentInput.Change committed = state.input().update(false, false,
                SixDofAlignmentInput.Reference.Y_POS);
        assertTrue(committed.committed());
        assertFalse(committed.referenceChanged());
        state.frameLock().apply(committed, state.input(), orientation.current());

        SixDofMovementFrame recaptured = current(state, orientation);
        assertNotEquals(first, recaptured);
        assertVectorEquals(new Vec3(-1, 0, 0), recaptured.forward());
        assertVectorEquals(new Vec3(0, 1, 0), recaptured.up());
    }

    @Test
    void ctrlHToggleClearsTheLockAndCapturesTheCurrentViewWhenReenabled() {
        SixDofAlignmentInput input = new SixDofAlignmentInput();
        SixDofMovementFrameLock frameLock = new SixDofMovementFrameLock();
        SixDofCameraOrientation orientation = identityOrientation();
        input.update(false, false, null);

        SixDofAlignmentInput.Change enabled = input.update(true, false, null);
        assertTrue(input.enabled());
        frameLock.apply(enabled, input, orientation.current());
        SixDofMovementFrame initial = current(frameLock, input, orientation, false);
        assertVectorEquals(new Vec3(0, 1, 0), initial.up());

        input.update(false, false, null);
        SixDofAlignmentInput.Change disabled = input.update(true, false, null);
        assertTrue(disabled.enabledChanged());
        assertFalse(input.enabled());
        frameLock.apply(disabled, input, orientation.current());
        orientation.applyDegrees(35.0, 90.0, 17.0);
        assertEquals(SixDofMovementFrame.fromCamera(orientation.current()),
                current(frameLock, input, orientation, false));

        input.update(false, false, null);
        SixDofAlignmentInput.Change reenabled = input.update(true, false, null);
        assertTrue(reenabled.enabledChanged());
        assertTrue(input.enabled());
        frameLock.apply(reenabled, input, orientation.current());
        SixDofMovementFrame recaptured = current(frameLock, input, orientation, false);
        assertVectorEquals(new Vec3(0, 1, 0), recaptured.up());
        assertEquals(0.0, recaptured.forward().dot(recaptured.up()), EPSILON);
        assertNotEquals(orientation.forward(), recaptured.forward(),
                "alignment freezes the projected movement heading while Q remains live");
    }

    @Test
    void selectionCancelAndSuspendKeepTheCommittedFrameUntilFreshCommitOrClear() {
        SixDofCameraOrientation orientation = identityOrientation();
        AlignmentState state = commitSelection(SixDofAlignmentInput.Reference.Y_POS, orientation);
        SixDofMovementFrame original = current(state, orientation);

        SixDofAlignmentInput.Change selecting = state.input().updateBeforePicking(false, true);
        state.frameLock().apply(selecting, state.input(), orientation.current());
        assertFalse(state.input().effectiveAlignment(false));
        orientation.applyDegrees(0.0, 90.0, 0.0);
        assertEquals(SixDofMovementFrame.fromCamera(orientation.current()),
                state.frameLock().current(state.input().effectiveAlignment(true), orientation.current()),
                "physically held Alt+H temporarily returns movement to the live view");
        SixDofAlignmentInput.Change noHit = state.input().update(false, false, null);
        assertFalse(noHit.committed());
        state.frameLock().apply(noHit, state.input(), orientation.current());
        assertTrue(state.input().effectiveAlignment(false));
        assertSame(original, current(state, orientation));

        SixDofAlignmentInput.Change selectingAgain = state.input().updateBeforePicking(false, true);
        state.frameLock().apply(selectingAgain, state.input(), orientation.current());
        SixDofAlignmentInput.Change freshPickCommit = state.input().update(false, false,
                SixDofAlignmentInput.Reference.X_POS);
        assertTrue(freshPickCommit.committed());
        state.frameLock().apply(freshPickCommit, state.input(), orientation.current());
        SixDofMovementFrame selected = current(state, orientation);
        assertVectorEquals(new Vec3(1, 0, 0), selected.up());
        assertNotEquals(original, selected);

        state.input().suspend();
        assertSame(selected, current(state, orientation));
        SixDofAlignmentInput.Change rearmed = state.input().update(false, false, null);
        state.frameLock().apply(rearmed, state.input(), orientation.current());
        assertSame(selected, current(state, orientation));

        state.input().disable();
        state.frameLock().clear();
        assertEquals(SixDofMovementFrame.fromCamera(orientation.current()),
                current(state, orientation));
    }

    private static AlignmentState commitSelection(SixDofAlignmentInput.Reference reference,
            SixDofCameraOrientation orientation) {
        SixDofAlignmentInput input = new SixDofAlignmentInput();
        SixDofMovementFrameLock frameLock = new SixDofMovementFrameLock();
        input.update(false, false, null);
        SixDofAlignmentInput.Change selecting = input.updateBeforePicking(false, true);
        frameLock.apply(selecting, input, orientation.current());
        SixDofAlignmentInput.Change committed = input.update(false, false, reference);
        assertTrue(committed.committed());
        frameLock.apply(committed, input, orientation.current());
        return new AlignmentState(input, frameLock);
    }

    private static ExpectedFrame[] expectedFrames() {
        return new ExpectedFrame[]{
                new ExpectedFrame(SixDofAlignmentInput.Reference.Y_POS,
                        new Vec3(0, 0, 1), new Vec3(-1, 0, 0), new Vec3(0, 1, 0)),
                new ExpectedFrame(SixDofAlignmentInput.Reference.Y_NEG,
                        new Vec3(0, 0, 1), new Vec3(1, 0, 0), new Vec3(0, -1, 0)),
                new ExpectedFrame(SixDofAlignmentInput.Reference.X_POS,
                        new Vec3(0, 0, 1), new Vec3(0, 1, 0), new Vec3(1, 0, 0)),
                new ExpectedFrame(SixDofAlignmentInput.Reference.X_NEG,
                        new Vec3(0, 0, 1), new Vec3(0, -1, 0), new Vec3(-1, 0, 0)),
                new ExpectedFrame(SixDofAlignmentInput.Reference.Z_POS,
                        new Vec3(0, -1, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1)),
                new ExpectedFrame(SixDofAlignmentInput.Reference.Z_NEG,
                        new Vec3(0, 1, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, -1))
        };
    }

    private static PolarFrame[] polarFrames() {
        return new PolarFrame[]{
                new PolarFrame(SixDofAlignmentInput.Reference.Y_POS, -90.0, 0.0,
                        new Vec3(0, 0, 1)),
                new PolarFrame(SixDofAlignmentInput.Reference.Y_NEG, 90.0, 0.0,
                        new Vec3(0, 0, -1)),
                new PolarFrame(SixDofAlignmentInput.Reference.X_POS, 0.0, -90.0,
                        new Vec3(0, -1, 0)),
                new PolarFrame(SixDofAlignmentInput.Reference.X_NEG, 0.0, 90.0,
                        new Vec3(0, -1, 0)),
                new PolarFrame(SixDofAlignmentInput.Reference.Z_POS, 0.0, 0.0,
                        new Vec3(0, -1, 0)),
                new PolarFrame(SixDofAlignmentInput.Reference.Z_NEG, 0.0, 180.0,
                        new Vec3(0, -1, 0))
        };
    }

    private static SixDofCameraOrientation identityOrientation() {
        SixDofCameraOrientation orientation = new SixDofCameraOrientation();
        orientation.reset(0.0, 0.0);
        return orientation;
    }

    private static SixDofDriftMotion.Intent intent(double forward, double right, double up) {
        return new SixDofDriftMotion.Intent(forward, right, up);
    }

    private static SixDofMovementFrame current(AlignmentState state, SixDofCameraOrientation orientation) {
        return current(state.frameLock(), state.input(), orientation, false);
    }

    private static SixDofMovementFrame current(SixDofMovementFrameLock frameLock,
            SixDofAlignmentInput input, SixDofCameraOrientation orientation, boolean selectChordHeld) {
        return frameLock.current(input.effectiveAlignment(selectChordHeld), orientation.current());
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual, String message) {
        assertEquals(expected.x(), actual.x(), EPSILON, message + " x");
        assertEquals(expected.y(), actual.y(), EPSILON, message + " y");
        assertEquals(expected.z(), actual.z(), EPSILON, message + " z");
    }

    private static void assertControlVectorEquals(Vec3 expected, dev.crlhitbox.api.geometry.Vec3d actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private record AlignmentState(SixDofAlignmentInput input, SixDofMovementFrameLock frameLock) {
    }

    private record ExpectedFrame(SixDofAlignmentInput.Reference reference,
            Vec3 forward, Vec3 right, Vec3 normal) {
    }

    private record PolarFrame(SixDofAlignmentInput.Reference reference,
            double pitchDegrees, double yawDegrees, Vec3 expectedProjectedForward) {
    }
}
