package wi.unigrav.api.gravity;

import wi.unigrav.api.math.Vec3;

/**
 * A source of gravitation: a massive body with a (possibly time-dependent)
 * inertial position and, optionally, a spherical-harmonic gravity field.
 *
 * <p>A source is also a {@link GravityContribution} — the general
 * superposition unit of a {@code GravityField} — whose acceleration is
 * derived from its mass distribution.
 *
 * <p>Unit conventions: gravitational parameter &#956; in m³/s², positions
 * in m, times in s, angular velocity in rad/s, angles in rad.
 */
public interface GravitySource extends GravityContribution {

    /** Gravitational parameter &#956; = G·M, in m³/s². */
    double gravitationalParameter();

    /** Position of the body's centre of mass in the inertial frame, in m. */
    Vec3 position(double time);

    /**
     * Spherical-harmonic model of the body's gravity field, or {@code null}
     * for a point mass. The harmonics are expressed in the body-fixed frame
     * whose polar axis is {@link #rotationAxis()}.
     */
    default SphericalHarmonics harmonics() {
        return null;
    }

    /** Rotation rate of the body-fixed frame about {@link #rotationAxis()}, rad/s. */
    default double angularVelocity() {
        return 0.0;
    }

    /** Rotation axis in the inertial frame (need not be normalized). */
    default Vec3 rotationAxis() {
        return new Vec3(0.0, 0.0, 1.0);
    }

    /** Rotation angle of the body-fixed frame at t = 0, in rad. */
    default double initialPhase() {
        return 0.0;
    }

    /**
     * Plummer softening length &#949; of the point-mass term, in m;
     * 0 (the default) is a pure point mass.
     *
     * <p>With &#949; &gt; 0 the point-mass acceleration becomes
     * &#8722;&#956;·r/(r²+&#949;²)^{3/2}, which removes the r &#8594; 0
     * singularity: the acceleration vanishes at the centre and is bounded
     * (peak &#8776; 0.385·&#956;/&#949;² at r = &#949;/&#8730;2). This is a
     * runtime safety device for game entities that can actually reach the
     * centre — without it a small offset produces enormous &#916;v and
     * vanilla {@code Entity.move} then sweeps a collision box hundreds of
     * thousands of blocks long, effectively hanging the tick loop (observed
     * live). Softening only applies to the point-mass term, not to
     * spherical harmonics (those have their own far-field damping).
     * A non-zero harmonic correction remains an exterior expansion: it may
     * diverge at the centre and is not made valid inside the reference radius
     * by this softening length.
     */
    default double softeningLength() {
        return 0.0;
    }

    /**
     * Total acceleration of a test particle at inertial position
     * {@code pos} (m) and time {@code time} (s), in m/s².
     *
     * <p>For a point mass this is &#8722;&#956; r&#770;/r² (softened by
     * {@link #softeningLength()} when non-zero). With harmonics,
     * the offset is rotated into the body-fixed frame (by
     * &#8722;({@link #initialPhase()} + {@link #angularVelocity()}·t) about
     * {@link #rotationAxis()}), the body-fixed acceleration is evaluated,
     * and the result is rotated back.
     */
    default Vec3 accelerationAt(Vec3 pos, double time) {
        Vec3 offset = pos.sub(position(time));
        SphericalHarmonics harmonics = harmonics();
        if (harmonics == null) {
            return pointMassAcceleration(gravitationalParameter(), offset, softeningLength());
        }
        double angle = initialPhase() + angularVelocity() * time;
        return harmonics.accelerationInertial(
                offset, gravitationalParameter(), rotationAxis(), angle, softeningLength());
    }

    /** Point-mass acceleration &#8722;&#956; r&#770;/r² for the given offset, in m/s². */
    static Vec3 pointMassAcceleration(double mu, Vec3 offset) {
        return pointMassAcceleration(mu, offset, 0.0);
    }

    /**
     * Softened point-mass acceleration
     * &#8722;&#956;·r/(r²+&#949;²)^{3/2}, in m/s². With {@code softening} = 0
     * this is exactly &#8722;&#956; r&#770;/r².
     */
    static Vec3 pointMassAcceleration(double mu, Vec3 offset, double softening) {
        double r2 = offset.normSquared() + softening * softening;
        double r = Math.sqrt(r2);
        return offset.scale(-mu / (r2 * r));
    }
}
