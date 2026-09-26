package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.SphericalHarmonics;
import wi.unigrav.api.integrator.SymplecticIntegrator;
import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spherical-harmonic geopotential tests.
 *
 * <p>Convention under test (matching Principia): stored C/S coefficients
 * are fully-normalized (geodesy 4&#960;), C&#8339;&#8322;&#8320; =
 * &#8722;J&#8322;/&#8730;5, evaluation uses unnormalized associated
 * Legendre functions times the normalization factor N(n,m).
 */
class SphericalHarmonicsTest {

    private static final double MU = 3.986e14;
    private static final double R_EARTH = 6.371e6;
    private static final double J2 = 1.08263e-3;

    @Test
    void normalizationFactorMatchesPrincipiaTable() {
        // Row n = 2 of Principia's legendre_normalization_factor.mathematica.h:
        // sqrt(5), sqrt(5/3)·... transcribed below to full printed precision.
        assertEquals(2.2360679774997896964, SphericalHarmonics.normalizationFactor(2, 0), 1.0e-14);
        assertEquals(1.2909944487358056284, SphericalHarmonics.normalizationFactor(2, 1), 1.0e-14);
        assertEquals(0.6454972243679028142, SphericalHarmonics.normalizationFactor(2, 2), 1.0e-14);
        assertEquals(1.0, SphericalHarmonics.normalizationFactor(0, 0), 0.0);
        assertEquals(1.7320508075688772935, SphericalHarmonics.normalizationFactor(1, 0), 1.0e-14);
    }

    @Test
    void degreeZeroEqualsPointMass() {
        // No harmonic terms at all (degrees 0/1 carry none by convention).
        SphericalHarmonics harmonics =
                new SphericalHarmonics(R_EARTH, new double[1][1], new double[1][1]);
        Vec3 offset = new Vec3(5.0e6, 3.0e6, -4.0e6);
        Vec3 expected = GravitySource.pointMassAcceleration(MU, offset);
        Vec3 actual = harmonics.acceleration(offset, MU);
        assertEquals(0.0, actual.sub(expected).norm() / expected.norm(), 1.0e-15);
    }

    @Test
    void allZeroCoefficientsEqualPointMass() {
        SphericalHarmonics harmonics =
                new SphericalHarmonics(R_EARTH, new double[5][5], new double[5][5]);
        Vec3 offset = new Vec3(-2.0e6, 6.0e6, 1.5e6);
        Vec3 expected = GravitySource.pointMassAcceleration(MU, offset);
        Vec3 actual = harmonics.acceleration(offset, MU);
        assertEquals(0.0, actual.sub(expected).norm() / expected.norm(), 1.0e-15);
    }

    @Test
    void j2CorrectionDecaysQuadraticallyWithDistance() {
        SphericalHarmonics harmonics = SphericalHarmonics.withJ2(R_EARTH, J2);
        // Far-field: the J2 correction scales as (R/r)² relative to the
        // point-mass term and must vanish as r → ∞.
        Vec3 direction = new Vec3(0.3, -0.6, 0.7416198487095663).normalize();
        double r1 = 1.0e9;
        double r2 = 1.0e10;
        Vec3 pm1 = GravitySource.pointMassAcceleration(MU, direction.scale(r1));
        Vec3 pm2 = GravitySource.pointMassAcceleration(MU, direction.scale(r2));
        double corr1 = harmonics.acceleration(direction.scale(r1), MU).sub(pm1).norm() / pm1.norm();
        double corr2 = harmonics.acceleration(direction.scale(r2), MU).sub(pm2).norm() / pm2.norm();
        System.out.printf("[J2 far field] rel correction at %.0e m: %.3e, at %.0e m: %.3e%n",
                r1, corr1, r2, corr2);
        // Expected magnitude: J2·(R/r)² ~ 1e-3·(6.37e-3)² ≈ 4e-8 at 1e9 m.
        assertTrue(corr1 > 1.0e-9 && corr1 < 1.0e-6, "unexpected J2 magnitude: " + corr1);
        // Quadratic decay: 10× farther → ~100× smaller.
        assertEquals(100.0, corr1 / corr2, 1.0);
    }

    @Test
    void j2CausesRegressionOfAscendingNode() {
        // Circular inclined orbit; J2 torque makes the orbital plane
        // precess. Analytic secular rate (e.g. Vallado):
        //   dΩ/dt = -3/2 · n · J2 · (R/a)² · cos i   (retrograde for i < 90°).
        double a = 7.0e6;
        double inclination = Math.toRadians(45.0);
        double n = Math.sqrt(MU / (a * a * a));
        double period = 2.0 * Math.PI / n;

        double v = Math.sqrt(MU / a);
        Vec3 r0 = new Vec3(a, 0.0, 0.0);
        Vec3 v0 = new Vec3(0.0, v * Math.cos(inclination), v * Math.sin(inclination));

        SphericalHarmonics harmonics = SphericalHarmonics.withJ2(R_EARTH, J2);
        SymplecticIntegrator integrator =
                new SymplecticIntegrator(SymplecticMethods.BLANES_MOAN_2002_S6, 30.0);

        int periods = 200;
        double tEnd = periods * period;
        SymplecticIntegrator.State end = integrator.advance(
                new SymplecticIntegrator.State(r0, v0, 0.0), tEnd,
                (pos, t) -> harmonics.acceleration(pos, MU));

        // Ascending node direction n̂ = ẑ × h; Ω = atan2(h_x, -h_y).
        Vec3 hEnd = end.position().cross(end.velocity());
        double raan = Math.atan2(hEnd.x(), -hEnd.y());

        double analyticRate = -1.5 * n * J2 * (R_EARTH / a) * (R_EARTH / a) * Math.cos(inclination);
        double analyticDrift = analyticRate * tEnd;
        System.out.printf("[J2 RAAN, %d periods] measured %.6f rad, analytic %.6f rad%n",
                periods, raan, analyticDrift);

        // Regression: the node drifts backwards for a prograde orbit.
        assertTrue(raan < 0.0, "expected nodal regression, got " + raan);
        // Order-of-magnitude + 2% band: osculating J2 effects wiggle the
        // node at the ~J2(R/a)² ~ 1e-3 rad level, negligible against the
        // ~1 rad secular drift accumulated here.
        assertEquals(analyticDrift, raan, Math.abs(analyticDrift) * 0.02);
    }
}
