package wi.unigrav.api.gravity;

import java.util.function.Function;

import wi.unigrav.api.math.Vec3;

/**
 * Simple immutable {@link GravitySource}: a body with a gravitational
 * parameter and an arbitrary analytic position function, optionally with
 * spherical harmonics and spin.
 *
 * <p>Factories cover the two common cases: a body fixed at an inertial
 * position, and a body on a {@link KeplerianOrbit} around either a fixed
 * point or another (moving) centre whose position is supplied as a
 * function — e.g. a registry lookup resolving another source by id.
 *
 * <p>Units: &#956; in m³/s², positions in m, times in s, spin rate in
 * rad/s, angles in rad.
 */
public final class BodyGravitySource implements GravitySource {

    private final double mu;
    private final Function<Double, Vec3> positionAt;
    private final SphericalHarmonics harmonics;
    private final double angularVelocity;
    private final Vec3 rotationAxis;
    private final double initialPhase;
    private final double softening;

    private BodyGravitySource(double mu, Function<Double, Vec3> positionAt,
                              SphericalHarmonics harmonics,
                              double angularVelocity, Vec3 rotationAxis, double initialPhase,
                              double softening) {
        if (mu < 0.0) {
            throw new IllegalArgumentException("mu must be non-negative");
        }
        if (softening < 0.0) {
            throw new IllegalArgumentException("softening must be non-negative");
        }
        this.mu = mu;
        this.positionAt = positionAt;
        this.harmonics = harmonics;
        this.angularVelocity = angularVelocity;
        this.rotationAxis = rotationAxis;
        this.initialPhase = initialPhase;
        this.softening = softening;
    }

    /** A body fixed at an inertial position (m). */
    public static BodyGravitySource fixed(double mu, Vec3 position) {
        return new BodyGravitySource(mu, t -> position, null, 0.0, new Vec3(0.0, 0.0, 1.0), 0.0, 0.0);
    }

    /** A body on a Keplerian orbit around a fixed centre (m). */
    public static BodyGravitySource orbiting(double mu, KeplerianOrbit orbit, Vec3 center) {
        return orbiting(mu, orbit, t -> center);
    }

    /**
     * A body on a Keplerian orbit around a centre whose inertial position
     * is given by {@code centerPosition} (e.g. another moving source).
     */
    public static BodyGravitySource orbiting(double mu, KeplerianOrbit orbit,
                                             Function<Double, Vec3> centerPosition) {
        return new BodyGravitySource(mu, t -> centerPosition.apply(t).add(orbit.positionAt(t)),
                null, 0.0, new Vec3(0.0, 0.0, 1.0), 0.0, 0.0);
    }

    /** Returns a copy with the given spherical-harmonic field model. */
    public BodyGravitySource withHarmonics(SphericalHarmonics harmonics) {
        return new BodyGravitySource(mu, positionAt, harmonics,
                angularVelocity, rotationAxis, initialPhase, softening);
    }

    /** Returns a copy with the given spin (rad/s about {@code axis}, phase in rad at t = 0). */
    public BodyGravitySource withSpin(double rateRadPerSec, Vec3 axis, double phaseRad) {
        return new BodyGravitySource(mu, positionAt, harmonics,
                rateRadPerSec, axis, phaseRad, softening);
    }

    /** Returns a copy with the given Plummer softening length (m); 0 is a pure point mass. */
    public BodyGravitySource withSoftening(double softening) {
        return new BodyGravitySource(mu, positionAt, harmonics,
                angularVelocity, rotationAxis, initialPhase, softening);
    }

    @Override
    public double gravitationalParameter() {
        return mu;
    }

    @Override
    public Vec3 position(double time) {
        return positionAt.apply(time);
    }

    @Override
    public SphericalHarmonics harmonics() {
        return harmonics;
    }

    @Override
    public double angularVelocity() {
        return angularVelocity;
    }

    @Override
    public Vec3 rotationAxis() {
        return rotationAxis;
    }

    @Override
    public double initialPhase() {
        return initialPhase;
    }

    @Override
    public double softeningLength() {
        return softening;
    }
}
