package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class RemoteBoostCloseWindowTest {
    @Test
    void onlyFiveConsecutiveSettledStationaryTicksClose() {
        RemoteBoostCloseWindow window = new RemoteBoostCloseWindow(5, 0.0025D);
        for (long tick = 1; tick < 5; tick++) assertFalse(window.observe(tick, Vec3.ZERO, true));
        assertTrue(window.observe(5, Vec3.ZERO, true));
    }

    @Test
    void movingOrEpochResetRestartsTheWindow() {
        RemoteBoostCloseWindow window = new RemoteBoostCloseWindow(5, 0.0025D);
        for (long tick = 1; tick <= 3; tick++) assertFalse(window.observe(tick, Vec3.ZERO, true));
        assertFalse(window.observe(4, new Vec3(24.5D, 0, 0), true));
        for (long tick = 5; tick < 8; tick++) assertFalse(window.observe(tick, new Vec3(24.5D, 0, 0), true));
        assertTrue(window.observe(8, new Vec3(24.5D, 0, 0), true));
        window.reset();
        assertFalse(window.observe(9, new Vec3(24.5D, 0, 0), true));
    }

    @Test
    void falseSettledAndTickGapsResetTheWindow() {
        RemoteBoostCloseWindow window = new RemoteBoostCloseWindow(5, 0.0025D);
        assertFalse(window.observe(1, Vec3.ZERO, true));
        assertFalse(window.observe(2, Vec3.ZERO, true));
        assertFalse(window.observe(3, Vec3.ZERO, false));
        assertFalse(window.observe(4, Vec3.ZERO, true));
        assertFalse(window.observe(6, Vec3.ZERO, true));
    }
}
