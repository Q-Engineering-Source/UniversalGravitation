package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.integrator.AccelerationFunction;
import wi.unigrav.api.integrator.SymplecticIntegrator;
import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Positional accuracy of the symplectic integrators: a periodic orbit must
 * return to its starting point after one revolution (period-regression
 * error), and {@link SymplecticIntegrator#advance} must land exactly on the
 * requested final time.
 */
class SymplecticAccuracyTest {

    private static final double MU = 3.986e14;
    private static final double A = 7.0e6;
    private static final double PERIOD = 2.0 * Math.PI * Math.sqrt(A * A * A / MU);

    private static AccelerationFunction pointMass(double mu) {
        return (pos, t) -> {
            double r2 = pos.normSquared();
            double r = Math.sqrt(r2);
            return pos.scale(-mu / (r2 * r));
        };
    }

    @Test
    void ellipticOrbitReturnsToStartAfterOnePeriod() {
        // Eccentricity 0.5 ellipse, periapsis start.
        double rp = A * 0.5;
        double vp = Math.sqrt(MU * 1.5 / rp);
        Vec3 r0 = new Vec3(rp, 0.0, 0.0);
        Vec3 v0 = new Vec3(0.0, vp, 0.0);

        int steps = 500;
        SymplecticIntegrator integrator =
                new SymplecticIntegrator(SymplecticMethods.BLANES_MOAN_2002_S6, PERIOD / steps);
        SymplecticIntegrator.State end = integrator.advance(
                new SymplecticIntegrator.State(r0, v0, 0.0), PERIOD, pointMass(MU));

        double posErr = end.position().sub(r0).norm() / A;
        double velErr = end.velocity().sub(v0).norm() / v0.norm();
        System.out.printf("[S6 one period, %d steps] return error: pos %.3e (rel), vel %.3e (rel)%n",
                steps, posErr, velErr);
        // For a symmetric orbit the time-symmetric S6 has a very small
        // period-regression error at 500 steps/orbit.
        assertTrue(posErr < 1.0e-6, "position regression too large: " + posErr);
        assertTrue(velErr < 1.0e-6, "velocity regression too large: " + velErr);
    }

    @Test
    void advanceLandsExactlyOnFinalTime() {
        Vec3 r0 = new Vec3(A, 0.0, 0.0);
        Vec3 v0 = new Vec3(0.0, Math.sqrt(MU / A), 0.0);
        SymplecticIntegrator integrator =
                new SymplecticIntegrator(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0);
        // Not a multiple of the step size: forces a shortened final step.
        double tFinal = 1.234567e4;
        SymplecticIntegrator.State end = integrator.advance(
                new SymplecticIntegrator.State(r0, v0, 0.0), tFinal, pointMass(MU));
        assertEquals(tFinal, end.time(), 0.0);
    }
}
