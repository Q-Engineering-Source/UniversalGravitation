package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.integrator.AccelerationFunction;
import wi.unigrav.api.integrator.SymplecticIntegrator;
import wi.unigrav.api.integrator.SymplecticMethod;
import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-term Kepler energy / angular-momentum conservation of the symplectic
 * integrators.
 *
 * <p>Physical expectation: a symplectic integrator conserves a shadow
 * Hamiltonian, so the energy error stays bounded and its drift grows only
 * linearly in time (no quadratic random-walk growth); the angular momentum
 * is conserved to round-off because the scheme is time-symmetric and the
 * force is central. Higher-order methods have dramatically smaller error
 * constants at the same step size, so BlanesMoan2002S6 must beat
 * Störmer-Verlet by orders of magnitude.
 */
class SymplecticConservationTest {

    /** Earth's gravitational parameter, m³/s². */
    private static final double MU = 3.986e14;
    /** Semi-major axis, m (roughly a low Earth orbit). */
    private static final double A = 7.0e6;

    private static final double PERIOD = 2.0 * Math.PI * Math.sqrt(A * A * A / MU);

    private static AccelerationFunction pointMass(double mu) {
        return (pos, t) -> {
            double r2 = pos.normSquared();
            double r = Math.sqrt(r2);
            return pos.scale(-mu / (r2 * r));
        };
    }

    private static double specificEnergy(Vec3 r, Vec3 v) {
        return 0.5 * v.normSquared() - MU / r.norm();
    }

    /** Result of a long integration: worst relative drifts over the run. */
    private record Drift(double energy, double angularMomentum) {
    }

    private static Drift integrate(SymplecticMethod method, double step,
                                   Vec3 r0, Vec3 v0, double periods) {
        SymplecticIntegrator integrator = new SymplecticIntegrator(method, step);
        AccelerationFunction accel = pointMass(MU);
        double e0 = specificEnergy(r0, v0);
        double l0 = r0.cross(v0).norm();
        double maxDE = 0.0;
        double maxDL = 0.0;
        SymplecticIntegrator.State state = new SymplecticIntegrator.State(r0, v0, 0.0);
        // Check the invariants every quarter period.
        int checks = (int) (periods * 4);
        for (int i = 1; i <= checks; i++) {
            double t = i * periods * PERIOD / checks;
            state = integrator.advance(state, t, accel);
            double de = Math.abs((specificEnergy(state.position(), state.velocity()) - e0) / e0);
            double dl = Math.abs((state.position().cross(state.velocity()).norm() - l0) / l0);
            maxDE = Math.max(maxDE, de);
            maxDL = Math.max(maxDL, dl);
        }
        return new Drift(maxDE, maxDL);
    }

    private static Vec3[] circularOrbit() {
        return new Vec3[] {new Vec3(A, 0.0, 0.0), new Vec3(0.0, Math.sqrt(MU / A), 0.0)};
    }

    /** Eccentricity 0.5 ellipse, starting at periapsis on the +x axis. */
    private static Vec3[] ellipticOrbit() {
        double rp = A * 0.5;
        double vp = Math.sqrt(MU * (1.0 + 0.5) / rp);
        return new Vec3[] {new Vec3(rp, 0.0, 0.0), new Vec3(0.0, vp, 0.0)};
    }

    @Test
    void blanesMoanS6CircularOrbitConservesInvariants() {
        Vec3[] ic = circularOrbit();
        Drift drift = integrate(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0, ic[0], ic[1], 120);
        System.out.printf("[S6 circular] 120 periods: rel energy drift %.3e, rel L drift %.3e%n",
                drift.energy(), drift.angularMomentum());
        // Symplectic schemes show a bounded, linearly growing energy drift;
        // S6 at ~97 steps/orbit should sit far below 1e-7.
        assertTrue(drift.energy() < 1.0e-7, "energy drift too large: " + drift.energy());
        assertTrue(drift.angularMomentum() < 1.0e-9,
                "angular momentum drift too large: " + drift.angularMomentum());
    }

    @Test
    void blanesMoanS6EllipticOrbitConservesInvariants() {
        Vec3[] ic = ellipticOrbit();
        Drift drift = integrate(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0, ic[0], ic[1], 120);
        System.out.printf("[S6 e=0.5] 120 periods: rel energy drift %.3e, rel L drift %.3e%n",
                drift.energy(), drift.angularMomentum());
        // The eccentric case stresses the integrator much more (at 60 s
        // steps the periapsis pass spans ~0.2 rad/step), so the oscillating
        // energy error sits around 1e-6 and grows only slowly (measured
        // 3.7e-6 after 40 periods, 6.5e-6 after 120 — sub-linear, the
        // bounded shadow-Hamiltonian oscillation expected of a symplectic
        // method). The angular momentum stays at round-off (measured 3e-14).
        assertTrue(drift.energy() < 1.0e-4, "energy drift too large: " + drift.energy());
        assertTrue(drift.angularMomentum() < 1.0e-9,
                "angular momentum drift too large: " + drift.angularMomentum());
    }

    @Test
    void blanesMoanS6BeatsStormerVerletAtSameStep() {
        Vec3[] ic = ellipticOrbit();
        Drift s6 = integrate(SymplecticMethods.BLANES_MOAN_2002_S6, 60.0, ic[0], ic[1], 40);
        Drift sv = integrate(SymplecticMethods.STORMER_VERLET, 60.0, ic[0], ic[1], 40);
        System.out.printf("[e=0.5, 40 periods] S6 energy drift %.3e vs Störmer-Verlet %.3e%n",
                s6.energy(), sv.energy());
        // At the same step size the 4th-order S6 (tiny error constant) must
        // outperform the 2nd-order leapfrog by orders of magnitude.
        assertTrue(sv.energy() > 20.0 * s6.energy(),
                "expected S6 to beat Störmer-Verlet: S6=" + s6.energy() + " SV=" + sv.energy());
        // Sanity: both are symplectic, so even the weaker method's error
        // stays bounded — but on an e=0.5 orbit at 60 s steps leapfrog's
        // oscillation is large (measured 1.3e-2 after 40 periods).
        assertTrue(sv.energy() < 5.0e-2, "Störmer-Verlet energy not bounded: " + sv.energy());
    }

    @Test
    void mclachlanS4ConservesEnergy() {
        Vec3[] ic = ellipticOrbit();
        Drift drift = integrate(SymplecticMethods.MCLACHLAN_1995_S4, 60.0, ic[0], ic[1], 40);
        System.out.printf("[McLachlan S4 e=0.5] 40 periods: rel energy drift %.3e%n", drift.energy());
        // Guard against transcription errors of the S4 coefficient table:
        // a wrong digit would wreck conservation entirely.
        assertTrue(drift.energy() < 1.0e-4, "S4 energy drift too large: " + drift.energy());
    }
}
