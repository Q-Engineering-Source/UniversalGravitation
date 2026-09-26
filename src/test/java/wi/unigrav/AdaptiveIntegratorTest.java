package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.integrator.AccelerationFunction;
import wi.unigrav.api.integrator.AdaptiveIntegrator;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dormand-Prince 5(4) adaptive integrator against the analytic two-body
 * solution (Kepler's equation).
 *
 * <p>Physical expectation: with a tolerance of 1e-9 the local error per
 * step is kept at roughly tol·(state scale); the global error after one
 * orbit should be within a few orders of magnitude of that — we assert a
 * comfortably loose bound and print the measured value.
 */
class AdaptiveIntegratorTest {

    private static final double MU = 3.986e14;
    private static final double A = 7.0e6;

    private static AccelerationFunction pointMass(double mu) {
        return (pos, t) -> {
            double r2 = pos.normSquared();
            double r = Math.sqrt(r2);
            return pos.scale(-mu / (r2 * r));
        };
    }

    @Test
    void dormandPrinceMatchesKeplerSolution() {
        double rp = A * 0.5;
        double vp = Math.sqrt(MU * 1.5 / rp);
        Vec3 r0 = new Vec3(rp, 0.0, 0.0);
        Vec3 v0 = new Vec3(0.0, vp, 0.0);
        Kepler truth = Kepler.fromState(MU, r0, v0);
        double period = truth.period();

        AdaptiveIntegrator integrator = new AdaptiveIntegrator(1.0e-9, 1.0e-9);
        AccelerationFunction accel = pointMass(MU);

        // Compare at a generic phase (a third of a period) and after a full
        // period.
        for (double fraction : new double[] {1.0 / 3.0, 1.0}) {
            double t = fraction * period;
            AdaptiveIntegrator.Result result = integrator.integrate(
                    new AdaptiveIntegrator.State(r0, v0, 0.0), t, accel);
            double posErr = result.state().position().sub(truth.positionAt(t)).norm();
            double velErr = result.state().velocity().sub(truth.velocityAt(t)).norm();
            System.out.printf("[DP5 tol=1e-9, t=%.2f T] pos err %.3e m, vel err %.3e m/s, "
                            + "accepted %d, rejected %d%n",
                    fraction, posErr, velErr, result.acceptedSteps(), result.rejectedSteps());
            // Loose physical bound: tol=1e-9 with a 7e6 m orbit scale gives
            // ~mm per step; even with accumulation over an orbit we expect
            // well under 100 m.
            assertTrue(posErr < 100.0, "position error too large: " + posErr);
            assertTrue(velErr < 1.0e-1, "velocity error too large: " + velErr);
            assertTrue(result.acceptedSteps() > 0);
        }
    }
}
