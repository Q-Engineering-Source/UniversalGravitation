package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.gravity.BodyGravitySource;
import wi.unigrav.api.gravity.KeplerianOrbit;
import wi.unigrav.api.integrator.SymplecticIntegrator;
import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the analytic {@link KeplerianOrbit} position/velocity evaluator.
 */
class KeplerianOrbitTest {

    private static final double MU = 3.986e14;
    private static final double A = 7.0e6;

    @Test
    void circularOrbitRadiusAndPeriod() {
        KeplerianOrbit orbit = KeplerianOrbit.circular(A, MU);
        double expectedPeriod = 2.0 * Math.PI * Math.sqrt(A * A * A / MU);
        assertEquals(expectedPeriod, orbit.period(), 1.0e-9 * expectedPeriod);

        // Radius stays exactly a for all phases; after a quarter period the
        // body has moved 90° around the focus.
        Vec3 p0 = orbit.positionAt(0.0);
        assertEquals(A, p0.norm(), 1.0e-9 * A);
        for (double fraction : new double[] {0.13, 0.25, 0.5, 0.77, 1.0}) {
            double t = fraction * orbit.period();
            assertEquals(A, orbit.positionAt(t).norm(), 1.0e-9 * A);
        }
        Vec3 quarter = orbit.positionAt(orbit.period() / 4.0);
        assertEquals(0.0, quarter.dot(p0) / (A * A), 1.0e-12);
        // Circular speed is constant and equals sqrt(μ/a).
        assertEquals(Math.sqrt(MU / A), orbit.velocityAt(0.37 * orbit.period()).norm(),
                1.0e-9 * Math.sqrt(MU / A));
    }

    @Test
    void ellipticOrbitMatchesNumericalIntegration() {
        // e = 0.5 ellipse; compare the analytic evaluator against the
        // symplectic integrator started from the same epoch state.
        KeplerianOrbit orbit = new KeplerianOrbit(A, 0.5,
                Math.toRadians(23.0), Math.toRadians(40.0), Math.toRadians(15.0),
                0.0, 0.0, MU);
        Vec3 r0 = orbit.positionAt(0.0);
        Vec3 v0 = orbit.velocityAt(0.0);
        // Epoch is at periapsis (M0 = 0): radius = a(1-e).
        assertEquals(A * 0.5, r0.norm(), 1.0e-9 * A);

        SymplecticIntegrator integrator =
                new SymplecticIntegrator(SymplecticMethods.BLANES_MOAN_2002_S6, 30.0);
        SymplecticIntegrator.State state = new SymplecticIntegrator.State(r0, v0, 0.0);
        double period = orbit.period();

        double maxRelErr = 0.0;
        for (int i = 1; i <= 20; i++) {
            double t = i * period / 4.0; // five periods, quarter-period checks
            state = integrator.advance(state, t,
                    (pos, time) -> {
                        double r2 = pos.normSquared();
                        double r = Math.sqrt(r2);
                        return pos.scale(-MU / (r2 * r));
                    });
            double relErr = state.position().sub(orbit.positionAt(t)).norm() / A;
            maxRelErr = Math.max(maxRelErr, relErr);
        }
        System.out.printf("[KeplerianOrbit e=0.5 vs S6, 5 periods] max rel position deviation %.3e%n",
                maxRelErr);
        // Both sides describe the same two-body orbit; the deviation is the
        // integrator's phase error, far below 1e-4 at 30 s steps.
        assertTrue(maxRelErr < 1.0e-4, "analytic vs integrated deviation too large: " + maxRelErr);
    }

    @Test
    void orbitingSourceComposesCenterAndOrbit() {
        KeplerianOrbit orbit = KeplerianOrbit.circular(50.0, 1.0e5);
        Vec3 center = new Vec3(0.0, 300.0, 0.0);
        BodyGravitySource moon = BodyGravitySource.orbiting(1.0, orbit, center);
        // The moon is always 50 m from the centre.
        for (double t : new double[] {0.0, 1.3, 4.2, 7.7}) {
            assertEquals(50.0, moon.position(t).sub(center).norm(), 1.0e-9);
        }
        // A centre supplied as a function (e.g. a moving source) is honoured.
        BodyGravitySource moving = BodyGravitySource.orbiting(1.0, orbit,
                t -> center.add(new Vec3(t, 0.0, 0.0)));
        assertEquals(center.add(new Vec3(2.0, 0.0, 0.0)).add(orbit.positionAt(2.0)),
                moving.position(2.0));
    }
}
