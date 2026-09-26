package wi.unigrav.api.sim;

import wi.unigrav.api.math.Vec3;

/**
 * Immutable state of a body under integration.
 *
 * <p>Units: mass in kg, position in m, velocity in m/s, time in s. A mass
 * of exactly 0 marks a massless probe (a test particle that feels gravity
 * but exerts none).
 */
public record BodyState(double mass, Vec3 position, Vec3 velocity, double time) {

    public BodyState {
        if (mass < 0.0) {
            throw new IllegalArgumentException("mass must be non-negative");
        }
    }

    public boolean isProbe() {
        return mass == 0.0;
    }

    public BodyState at(Vec3 newPosition, Vec3 newVelocity, double newTime) {
        return new BodyState(mass, newPosition, newVelocity, newTime);
    }
}
