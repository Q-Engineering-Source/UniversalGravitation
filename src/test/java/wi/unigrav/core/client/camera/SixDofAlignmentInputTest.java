package wi.unigrav.core.client.camera;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for pure six-DOF alignment command state. */
class SixDofAlignmentInputTest {

    @Test
    void defaultsToDisabledWithPositiveYAxisReference() {
        SixDofAlignmentInput input = new SixDofAlignmentInput();

        assertFalse(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
    }

    @Test
    void selectHoldWithoutHitStillEntersTemporaryFreeMode() {
        SixDofAlignmentInput input = armedInput();

        assertChange(input.update(false, true, null),
                false, false, true, false);
        assertTrue(input.selecting());
        assertEquals(null, input.previewReference());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());

        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.Z_POS),
                false, false, true, false);
        assertTrue(input.selecting());
        assertEquals(SixDofAlignmentInput.Reference.Z_POS, input.previewReference());
    }

    @Test
    void disappearingTargetKeepsTemporaryFreeModeUntilRelease() {
        SixDofAlignmentInput input = armedInput();

        input.update(false, true, SixDofAlignmentInput.Reference.X_NEG);
        assertChange(input.update(false, true, null), false, false, true, false);
        assertTrue(input.selecting());
        assertEquals(null, input.previewReference());

        assertChange(input.update(false, false, null), false, false, true, false);
        assertFalse(input.selecting());
        assertFalse(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
    }

    @Test
    void validSelectReleaseCommitsTheCurrentReferenceAndEnablesAlignment() {
        SixDofAlignmentInput input = armedInput();

        input.update(false, true, SixDofAlignmentInput.Reference.X_NEG);
        assertChange(input.update(false, false, SixDofAlignmentInput.Reference.Z_POS),
                true, true, true, true);
        assertTrue(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Z_POS, input.reference());
        assertFalse(input.selecting());
        assertEquals(null, input.previewReference());
    }

    @Test
    void invalidSelectReleasePreservesThePersistentModeAndReference() {
        SixDofAlignmentInput input = armedInput();
        assertChange(input.update(true, false, null), true, false, false, false);
        assertTrue(input.enabled());
        input.update(false, false, null);
        input.update(false, true, SixDofAlignmentInput.Reference.X_POS);

        assertChange(input.update(false, false, null), false, false, true, false);
        assertTrue(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
        assertFalse(input.selecting());
    }

    @Test
    void toggleRisingEdgeCancelsSelectionAndTogglesPersistentMode() {
        SixDofAlignmentInput input = armedInput();
        input.update(false, true, SixDofAlignmentInput.Reference.X_POS);

        assertChange(input.update(true, false, SixDofAlignmentInput.Reference.Z_POS),
                true, false, true, false);
        assertTrue(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
        assertFalse(input.selecting());
    }

    @Test
    void simultaneousModifiersCancelSelectionAndRequireSelectorReleaseBeforeReuse() {
        SixDofAlignmentInput input = armedInput();
        input.update(false, true, SixDofAlignmentInput.Reference.X_POS);

        assertChange(input.update(true, true, SixDofAlignmentInput.Reference.Z_POS),
                false, false, true, false);
        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.Z_POS),
                false, false, false, false);
        assertFalse(input.selecting());
        assertFalse(input.enabled());

        assertChange(input.update(false, false, SixDofAlignmentInput.Reference.Z_POS),
                false, false, false, false);
        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.Z_POS),
                false, false, true, false);
        assertTrue(input.selecting());
    }

    @Test
    void activationAndFocusResumeLatchHeldSelectorsWithoutSelectionOrCommit() {
        SixDofAlignmentInput input = new SixDofAlignmentInput();

        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.X_NEG),
                false, false, false, false);
        assertFalse(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
        input.update(false, false, null);
        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.X_NEG),
                false, false, true, false);

        input.suspend();
        assertChange(input.update(false, true, SixDofAlignmentInput.Reference.Z_POS),
                false, false, false, false);
        assertFalse(input.selecting());
        assertChange(input.update(false, false, SixDofAlignmentInput.Reference.Z_POS),
                false, false, false, false);
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
    }

    @Test
    void disableStopsAlignmentWithoutDiscardingTheSelectedReference() {
        SixDofAlignmentInput input = armedInput();

        input.update(false, true, SixDofAlignmentInput.Reference.Y_NEG);
        input.update(false, false, SixDofAlignmentInput.Reference.Y_NEG);
        assertEquals(SixDofAlignmentInput.Reference.Y_NEG, input.reference());
        input.update(false, true, SixDofAlignmentInput.Reference.Y_NEG);
        assertTrue(input.selecting());

        input.disable();

        assertFalse(input.enabled());
        assertEquals(SixDofAlignmentInput.Reference.Y_NEG, input.reference());
        assertFalse(input.selecting());
        assertEquals(null, input.previewReference());
        assertChange(input.update(false, false, SixDofAlignmentInput.Reference.Y_NEG),
                false, false, false, false);
    }

    @Test
    void referencesHaveStableLabelsAndDistinctUnitNormals() {
        assertReference(SixDofAlignmentInput.Reference.Y_POS, "Y+", new Vec3(0.0, 1.0, 0.0));
        assertReference(SixDofAlignmentInput.Reference.Y_NEG, "Y-", new Vec3(0.0, -1.0, 0.0));
        assertReference(SixDofAlignmentInput.Reference.X_POS, "X+", new Vec3(1.0, 0.0, 0.0));
        assertReference(SixDofAlignmentInput.Reference.X_NEG, "X-", new Vec3(-1.0, 0.0, 0.0));
        assertReference(SixDofAlignmentInput.Reference.Z_POS, "Z+", new Vec3(0.0, 0.0, 1.0));
        assertReference(SixDofAlignmentInput.Reference.Z_NEG, "Z-", new Vec3(0.0, 0.0, -1.0));

        Set<Vec3> normals = new HashSet<>();
        for (SixDofAlignmentInput.Reference reference : SixDofAlignmentInput.Reference.values()) {
            normals.add(reference.normal());
        }
        assertEquals(6, normals.size());
    }

    @Test
    void earlyInputDefersReleaseUntilFreshPickingAndKeepsMouseFree() {
        SixDofAlignmentInput input = armedInput();
        input.updateBeforePicking(true, false);
        input.updateBeforePicking(false, false);
        assertTrue(input.effectiveAlignment(false));
        assertFalse(input.effectiveAlignment(true));
        input.updateBeforePicking(false, true);
        input.update(false, true, SixDofAlignmentInput.Reference.X_POS);

        assertFalse(input.updateBeforePicking(false, false).committed());
        assertTrue(input.selecting());
        assertFalse(input.effectiveAlignment(false));
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());

        assertTrue(input.update(false, false, SixDofAlignmentInput.Reference.Z_NEG).committed());
        assertEquals(SixDofAlignmentInput.Reference.Z_NEG, input.reference());
        assertTrue(input.effectiveAlignment(false));
    }

    @Test
    void masterToggleCanCancelAnAwaitingReleaseWithoutCommittingOldPreview() {
        SixDofAlignmentInput input = armedInput();
        input.update(false, true, SixDofAlignmentInput.Reference.X_POS);
        input.updateBeforePicking(false, false);
        input.updateBeforePicking(true, false);
        assertTrue(input.enabled());
        assertFalse(input.selecting());
        assertEquals(SixDofAlignmentInput.Reference.Y_POS, input.reference());
        assertFalse(input.update(false, false, SixDofAlignmentInput.Reference.X_POS).committed());
    }

    private static SixDofAlignmentInput armedInput() {
        SixDofAlignmentInput input = new SixDofAlignmentInput();
        assertChange(input.update(false, false, null), false, false, false, false);
        return input;
    }

    private static void assertChange(SixDofAlignmentInput.Change change,
            boolean enabledChanged, boolean referenceChanged, boolean selectionChanged,
            boolean committed) {
        assertEquals(enabledChanged, change.enabledChanged());
        assertEquals(referenceChanged, change.referenceChanged());
        assertEquals(selectionChanged, change.selectionChanged());
        assertEquals(committed, change.committed());
    }

    private static void assertReference(SixDofAlignmentInput.Reference reference,
            String label, Vec3 normal) {
        assertEquals(label, reference.label());
        assertEquals(normal, reference.normal());
        assertEquals(1.0, reference.normal().norm());
    }
}
