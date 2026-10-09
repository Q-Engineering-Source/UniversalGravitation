package wi.unigrav.core.client.boost;

import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.PlayerPhysicsInput;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsInputTest {
    @Test void keyboardUsesYawPlaneAndBoundsDiagonalWhileNormalizingLook() {
        var input = PlayerPhysicsInput.keyboard(90, 1, 1, new Vec3(0, .3, .4), true, false, true, true);
        assertEquals(-1 / Math.sqrt(2), input.translation().x(), 1e-7);
        assertEquals(0, input.translation().y());
        assertEquals(1 / Math.sqrt(2), input.translation().z(), 1e-7);
        assertEquals(.6, input.forward().y(), 1e-12);
        assertEquals(.8, input.forward().z(), 1e-12);
        assertTrue(input.jump());
        assertTrue(input.sprint());
        assertTrue(input.boost());
        assertEquals(5, input.boostGear());
        assertEquals(Math.sqrt(2.0), input.keyboardTravelGain(), 1e-15,
                "keyboard direction retains the native diagonal magnitude separately");
    }

    @Test void keyboardTravelGainTracksRawMagnitudeAndWorldInputKeepsUnitGain() {
        var cardinal = PlayerPhysicsInput.keyboard(37, 0, -1, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(1.0, cardinal.keyboardTravelGain(), 0);
        var partial = PlayerPhysicsInput.keyboard(0, .6, .8, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(1.0, partial.keyboardTravelGain(), 0,
                "sub-unit key axes keep native analog speed rather than being amplified");
        var world = PlayerPhysicsInput.world(new Vec3(.6, 0, .8), new Vec3(0, 0, 1),
                false, false, false, false, 5);
        assertEquals(1.0, world.keyboardTravelGain(), 0,
                "world and camera-relative callers carry no keyboard-only magnitude");
    }

    @Test void keyboardRejectsNonFiniteAndOutOfRangeRawAxes() {
        for (double[] axes : new double[][]{
                {Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}, {1.000001, 0}, {-1.1, 0}
        }) {
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsInput.keyboard(0,
                    axes[0], axes[1], new Vec3(0, 0, 1), false, false, false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsInput.keyboard(Float.NaN,
                0, 0, new Vec3(0, 0, 1), false, false, false, false));
    }
}
