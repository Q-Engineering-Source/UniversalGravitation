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
    }
}
