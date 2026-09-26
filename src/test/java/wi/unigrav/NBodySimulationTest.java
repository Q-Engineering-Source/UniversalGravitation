package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.api.sim.BodyState;
import wi.unigrav.api.sim.NBodySimulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link NBodySimulation}'s probe coupling: probes may feel only
 * the sim-internal massive bodies, an external {@link GravityField}, or the
 * superposition of both.
 */
class NBodySimulationTest {

    private static final double MU = 3.986e14;
    private static final double A = 7.0e6;
    private static final double PERIOD = 2.0 * Math.PI * Math.sqrt(A * A * A / MU);

    private static GravitySource staticPointMass(double mu, Vec3 position) {
        return new GravitySource() {
            @Override
            public double gravitationalParameter() {
                return mu;
            }

            @Override
            public Vec3 position(double time) {
                return position;
            }
        };
    }

    /** Circular-orbit probe state (massless) at radius a around the origin, in the xy plane. */
    private static BodyState circularProbe(double a, double mu) {
        return new BodyState(0.0,
                new Vec3(a, 0.0, 0.0),
                new Vec3(0.0, Math.sqrt(mu / a), 0.0),
                0.0);
    }

    private static double specificEnergy(double mu, BodyState s) {
        return 0.5 * s.velocity().normSquared() - mu / s.position().norm();
    }

    @Test
    void externalFieldProbeConservesEnergy() {
        GravityField field = new GravityField();
        field.register("test", "earth", staticPointMass(MU, Vec3.ZERO));

        NBodySimulation sim = new NBodySimulation(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0);
        sim.addProbe(circularProbe(A, MU), field);

        BodyState start = sim.probes().get(0);
        double e0 = specificEnergy(MU, start);
        double l0 = start.position().cross(start.velocity()).norm();

        sim.advanceTo(120.0 * PERIOD);
        BodyState end = sim.probes().get(0);
        double energyDrift = Math.abs((specificEnergy(MU, end) - e0) / e0);
        double lDrift = Math.abs((end.position().cross(end.velocity()).norm() - l0) / l0);
        System.out.printf("[external-field probe, 120 periods] rel energy drift %.3e, rel L drift %.3e%n",
                energyDrift, lDrift);
        // Same integrator and orbit as the circular Kepler test: round-off-level drift.
        assertTrue(energyDrift < 1.0e-9, "energy drift too large: " + energyDrift);
        assertTrue(lDrift < 1.0e-9, "angular momentum drift too large: " + lDrift);
    }

    @Test
    void internalAndExternalProbesMixInOneSimulation() {
        // Massive body with the same effective μ as the external source,
        // both sitting at the origin. The internal-only probe orbits at
        // speed sqrt(μ/a); the external-field probe additionally feels the
        // sim-internal body, so it orbits the superposed 2μ field — this
        // exercises all three code paths (internal, external, superposition).
        double mass = MU / NBodySimulation.GRAVITATIONAL_CONSTANT;
        GravityField field = new GravityField();
        field.register("test", "earth", staticPointMass(MU, Vec3.ZERO));

        NBodySimulation sim = new NBodySimulation(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0);
        sim.addBody(new BodyState(mass, Vec3.ZERO, Vec3.ZERO, 0.0));
        sim.addProbe(circularProbe(A, MU));                 // internal only: feels μ
        sim.addProbe(circularProbe(A, 2.0 * MU), field);    // internal + external: feels 2μ

        double e1 = specificEnergy(MU, sim.probes().get(0));
        double e2 = specificEnergy(2.0 * MU, sim.probes().get(1));

        sim.advanceTo(40.0 * PERIOD);

        double drift1 = Math.abs((specificEnergy(MU, sim.probes().get(0)) - e1) / e1);
        double drift2 = Math.abs((specificEnergy(2.0 * MU, sim.probes().get(1)) - e2) / e2);
        System.out.printf("[mixed probes, 40 periods] internal drift %.3e, superposed drift %.3e%n",
                drift1, drift2);
        assertTrue(drift1 < 1.0e-9, "internal probe energy drift too large: " + drift1);
        assertTrue(drift2 < 1.0e-9, "superposed probe energy drift too large: " + drift2);
    }
}
