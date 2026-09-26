package wi.unigrav.api.gravity;

import wi.unigrav.api.math.Vec3;

/**
 * A uniform (position- and time-independent) acceleration field: the same
 * acceleration vector everywhere, always. This is how "world gravity" — the
 * vanilla-like constant downward pull — is represented inside the field
 * system, turning gravity into a piece of configurable data.
 *
 * <p>Immutable. To change the world gravity, register a replacement under
 * the same field id (the field's replacement semantics make this cheap).
 *
 * <p>Units: acceleration in m/s².
 */
public record UniformGravityContribution(Vec3 acceleration) implements GravityContribution {

    public UniformGravityContribution {
        if (acceleration == null) {
            throw new IllegalArgumentException("acceleration must not be null");
        }
    }

    /** Creates a uniform field pulling straight down with the given strength in m/s². */
    public static UniformGravityContribution downward(double strength) {
        return new UniformGravityContribution(new Vec3(0.0, -strength, 0.0));
    }

    @Override
    public Vec3 accelerationAt(Vec3 pos, double time) {
        return acceleration;
    }
}
