package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for configurable six-DOF keyboard aggregation. */
class SixDofPhysicalControlsTest {

    @Test
    void rollAxisIsExclusiveAndPreservesLeftRightSigns() {
        assertEquals(1.0, SixDofPhysicalControls.resolve(
                true, false, false, false, false, false).rollAxis());
        assertEquals(-1.0, SixDofPhysicalControls.resolve(
                false, true, false, false, false, false).rollAxis());
        assertEquals(0.0, SixDofPhysicalControls.resolve(
                true, true, false, false, false, false).rollAxis());
    }

    @Test
    void rollAndBoostGearsRequireExclusivePressWithinGroup() {
        SixDofPhysicalControls.State both = SixDofPhysicalControls.resolve(
                false, false, true, true, true, true);

        assertFalse(both.rollGearUp());
        assertFalse(both.rollGearDown());
        assertFalse(both.boostGearUp());
        assertFalse(both.boostGearDown());
    }

    @Test
    void sampledBindingStatesMapToNamedGearActions() {
        SixDofPhysicalControls.State state = SixDofPhysicalControls.resolve(
                false, false, false, true, true, false);

        assertTrue(state.rollGearUp());
        assertFalse(state.rollGearDown());
        assertFalse(state.boostGearUp());
        assertTrue(state.boostGearDown());
    }

    @Test
    void activeGearChordSuppressesNewDirectRollDrive() {
        SixDofPhysicalControls.State state = SixDofPhysicalControls.resolve(
                true, false, false, true, false, false);

        assertEquals(0.0, state.rollAxis());
        assertTrue(state.rollGearUp());
    }
}
