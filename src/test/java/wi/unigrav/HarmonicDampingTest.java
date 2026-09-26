package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.HarmonicDamping;
import wi.unigrav.api.gravity.SphericalHarmonics;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the {@link HarmonicDamping} port and its effect on
 * {@link SphericalHarmonics}.
 *
 * <p>Semantics under test (confirmed against Principia's
 * physics/harmonic_damping*.hpp): &#963; = 1 <b>below</b> the inner
 * threshold (harmonics fully effective), a C&#185; cubic sigmoid between
 * inner and outer = 3·inner, and &#963; = 0 <b>at/above</b> the outer
 * threshold (harmonics faded out, pure point-mass field). In other words
 * the damping acts at <i>large</i> distance; it does not suppress the
 * harmonics near the surface.
 */
class HarmonicDampingTest {

    private static final double MU = 3.986e14;
    private static final double R_EARTH = 6.371e6;
    private static final double J2 = 1.08263e-3;

    @Test
    void sigmaShapeMatchesPrincipiaInvariant() {
        double s0 = 2.0 * R_EARTH;
        HarmonicDamping damping = new HarmonicDamping(s0);
        assertEquals(3.0 * s0, damping.outerThreshold(), 0.0);

        // Below/at the inner threshold: undamped.
        assertEquals(1.0, damping.sigma(0.5 * s0), 0.0);
        assertEquals(1.0, damping.sigma(s0), 1.0e-15);
        // At/above the outer threshold: fully damped.
        assertEquals(0.0, damping.sigma(3.0 * s0), 1.0e-15);
        assertEquals(0.0, damping.sigma(10.0 * s0), 0.0);
        // The cubic sigmoid passes through 1/2 at r = 2·s₀:
        // σ(2s₀) = 9/2 − 6 + 2 = 1/2.
        assertEquals(0.5, damping.sigma(2.0 * s0), 1.0e-15);
    }

    @Test
    void sigmaIsMonotonicAndSmoothAcrossTransition() {
        double s0 = 2.0 * R_EARTH;
        HarmonicDamping damping = new HarmonicDamping(s0);
        // Monotonically non-increasing across the transition.
        double previous = 1.0;
        for (int i = 1; i <= 200; i++) {
            double r = s0 + 2.0 * s0 * i / 200.0;
            double sigma = damping.sigma(r);
            assertTrue(sigma <= previous + 1.0e-15,
                    "sigma not monotonic at r=" + r + ": " + previous + " -> " + sigma);
            previous = sigma;
        }
        // C¹ continuity: the slope tends to 0 at both boundaries.
        double eps = 1.0e-3 * s0;
        double slopeInner = (damping.sigma(s0) - damping.sigma(s0 - eps)) / eps;
        double slopeOuter = (damping.sigma(3.0 * s0 + eps) - damping.sigma(3.0 * s0 - eps)) / eps;
        assertTrue(Math.abs(slopeInner) < 1.0e-6 / s0 * 1.0e3,
                "slope at inner threshold not ~0: " + slopeInner);
        assertTrue(Math.abs(slopeOuter) < 1.0e-6 / s0 * 1.0e3,
                "slope at outer threshold not ~0: " + slopeOuter);
    }

    private static SphericalHarmonics j2Model(HarmonicDamping damping) {
        double[][] c = new double[3][3];
        c[2][0] = -J2 / Math.sqrt(5.0);
        return new SphericalHarmonics(R_EARTH, c, new double[3][3], damping);
    }

    @Test
    void belowInnerThresholdFieldIsUndamped() {
        HarmonicDamping damping = new HarmonicDamping(2.0 * R_EARTH);
        SphericalHarmonics damped = j2Model(damping);
        SphericalHarmonics undamped = j2Model(null);
        Vec3 offset = new Vec3(0.9 * R_EARTH, 0.8 * R_EARTH, 0.5 * R_EARTH); // r ≈ 1.3 R
        Vec3 aDamped = damped.acceleration(offset, MU);
        Vec3 aUndamped = undamped.acceleration(offset, MU);
        // σ = 1 exactly, so the results must be bit-for-bit identical.
        assertEquals(0.0, aDamped.sub(aUndamped).norm(), 0.0);
    }

    @Test
    void aboveOuterThresholdFieldIsPurePointMass() {
        HarmonicDamping damping = new HarmonicDamping(2.0 * R_EARTH);
        SphericalHarmonics damped = j2Model(damping);
        Vec3 offset = new Vec3(5.0 * R_EARTH, 3.0 * R_EARTH, -4.0 * R_EARTH); // r ≈ 7.1 R > 6 R
        Vec3 expected = GravitySource.pointMassAcceleration(MU, offset);
        Vec3 actual = damped.acceleration(offset, MU);
        assertEquals(0.0, actual.sub(expected).norm(), 0.0);
        // Same for the potential: pure -μ/r.
        assertEquals(-MU / offset.norm(), damped.potential(offset, MU), 1.0e-12 * MU / offset.norm());
    }

    @Test
    void harmonicCorrectionFadesMonotonicallyAcrossTransition() {
        HarmonicDamping damping = new HarmonicDamping(2.0 * R_EARTH);
        SphericalHarmonics damped = j2Model(damping);
        Vec3 direction = new Vec3(0.3, -0.6, 0.7416198487095663).normalize();
        double previous = Double.MAX_VALUE;
        for (int i = 0; i <= 50; i++) {
            double r = 2.0 * R_EARTH + 4.0 * R_EARTH * i / 50.0; // inner .. outer
            Vec3 offset = direction.scale(r);
            Vec3 pm = GravitySource.pointMassAcceleration(MU, offset);
            double correction = damped.acceleration(offset, MU).sub(pm).norm() / pm.norm();
            assertTrue(correction <= previous + 1.0e-18,
                    "correction not fading monotonically at r=" + r
                            + ": " + previous + " -> " + correction);
            previous = correction;
        }
        // By the outer threshold the correction has fully vanished.
        assertEquals(0.0, previous, 0.0);
    }
}
