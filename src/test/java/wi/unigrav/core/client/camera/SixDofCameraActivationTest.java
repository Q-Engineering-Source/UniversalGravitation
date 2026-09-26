package wi.unigrav.core.client.camera;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for the camera's client-authority and zero-G activation policy. */
class SixDofCameraActivationTest {

    @Test
    void personalZeroGOverridesCreativeFlightAndFieldStrength() {
        assertTrue(active(true, true, new Vec3(0.0, -32.0, 0.0)));
    }

    @Test
    void naturalZeroGUsesInclusiveThresholdButNormalGravityPassesThrough() {
        assertTrue(active(false, false, Vec3.ZERO));
        assertTrue(active(false, false, new Vec3(0.0, -0.5, 0.0)));
        assertFalse(active(false, false, new Vec3(0.0, -0.500001, 0.0)));
        assertFalse(active(false, false, new Vec3(0.0, -32.0, 0.0)));
    }

    @Test
    void naturalZeroGDoesNotTakeOverVanillaCreativeFlight() {
        assertFalse(active(false, true, Vec3.ZERO));
    }

    @Test
    void integratedCreativeRequiresExplicitPersonalGEvenInNaturalZeroG() {
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, false, true, true, false, false,
                true, false, false, false, 0.5, Vec3.ZERO));
        assertTrue(SixDofCameraActivation.shouldActivate(
                true, true, false, true, true, false, false,
                true, false, false, true, 0.5, new Vec3(0.0, -32.0, 0.0)));
        assertTrue(SixDofCameraActivation.shouldActivate(
                true, true, false, true, true, false, false,
                false, false, false, false, 0.5, Vec3.ZERO));
    }

    @Test
    void authorityAndPlayerSafetyGatesFailClosed() {
        Vec3 zero = Vec3.ZERO;
        assertFalse(SixDofCameraActivation.shouldActivate(
                false, true, true, true, false, false, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, false, true, true, false, false, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, false, true, false, false, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, true, false, false, false, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, true, true, true, false, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, true, true, false, true, false, false,
                true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, true, true, true, false, false, false, true,
                true, 0.5, zero));
    }

    @Test
    void remoteCameraRequiresExplicitServerCustodyNotAnEmptyField() {
        Vec3 zero = Vec3.ZERO;
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, false, false, false, true, false, false, false,
                false, true, 0.5, zero));
        assertFalse(SixDofCameraActivation.shouldActivate(
                true, false, true, false, true, false, false, false,
                false, false, 0.5, new Vec3(0.0, -32.0, 0.0)));
        assertTrue(SixDofCameraActivation.shouldActivate(
                true, false, true, false, true, false, false, false,
                false, true, 0.5, new Vec3(0.0, -32.0, 0.0)));
    }

    @Test
    void invalidThresholdOrAccelerationCannotActivate() {
        assertFalse(policy(false, false, Double.NaN, Vec3.ZERO));
        assertFalse(policy(false, false, 0.5,
                new Vec3(Double.NaN, 0.0, 0.0)));
        assertFalse(policy(false, false, 0.5, null));
    }

    private static boolean active(boolean personalZeroG, boolean creativeFlying,
                                  Vec3 acceleration) {
        return policy(personalZeroG, creativeFlying, 0.5, acceleration);
    }

    private static boolean policy(boolean personalZeroG, boolean creativeFlying,
                                  double threshold, Vec3 acceleration) {
        return SixDofCameraActivation.shouldActivate(
                true, true, true, true, false, false, creativeFlying, false,
                personalZeroG, threshold, acceleration);
    }
}
