package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.gravity.BodyGravitySource;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plummer softening of the point-mass term: a = -mu·r/(r²+eps²)^{3/2}.
 * With eps = 0 the softened formula must be bit-identical to the pure
 * point mass; with eps &gt; 0 the acceleration vanishes at the centre, is
 * bounded (peak at r = eps/√2, magnitude ≈ 0.385·mu/eps²), and converges
 * to the unsoftened value far away.
 */
class SofteningTest {

    private static final double MU = 3.986e14;

    @Test
    void zeroSofteningMatchesPurePointMass() {
        Vec3 offset = new Vec3(1.0e6, -2.0e6, 3.0e6);
        Vec3 pure = GravitySource.pointMassAcceleration(MU, offset);
        Vec3 softened = GravitySource.pointMassAcceleration(MU, offset, 0.0);
        assertEquals(pure.x(), softened.x(), 0.0);
        assertEquals(pure.y(), softened.y(), 0.0);
        assertEquals(pure.z(), softened.z(), 0.0);
    }

    @Test
    void accelerationVanishesAtCentre() {
        Vec3 a = GravitySource.pointMassAcceleration(MU, Vec3.ZERO, 50.0);
        assertEquals(0.0, a.norm(), 0.0);
    }

    @Test
    void accelerationIsBoundedWithPeakAtEpsOverSqrt2() {
        double eps = 50.0;
        // |a|(r) = mu·r/(r²+eps²)^{3/2}; d|a|/dr = 0 at r = eps/√2, with
        // |a|max = mu/eps² · (1/√2) / (3/2)^{3/2} ≈ 0.385·mu/eps².
        double peakR = eps / Math.sqrt(2.0);
        double expectedPeak = MU / (eps * eps) * (1.0 / Math.sqrt(2.0)) / Math.pow(1.5, 1.5);
        double peak = GravitySource.pointMassAcceleration(MU, new Vec3(peakR, 0.0, 0.0), eps).norm();
        assertEquals(expectedPeak, peak, expectedPeak * 1e-12);
        // Nowhere across the range is the acceleration larger than the peak.
        for (double r = 0.0; r <= 20.0 * eps; r += eps / 7.0) {
            double a = GravitySource.pointMassAcceleration(MU, new Vec3(r, 0.0, 0.0), eps).norm();
            assertTrue(a <= peak * (1.0 + 1e-12),
                    "acceleration " + a + " exceeds peak " + peak + " at r=" + r);
        }
    }

    @Test
    void softenedConvergesToPurePointMassFarAway() {
        double eps = 50.0;
        Vec3 far = new Vec3(0.0, 1.0e9, 0.0);
        Vec3 pure = GravitySource.pointMassAcceleration(MU, far);
        Vec3 softened = GravitySource.pointMassAcceleration(MU, far, eps);
        // Relative deviation scales as (3/2)(eps/r)² — utterly negligible here.
        assertEquals(pure.norm(), softened.norm(), pure.norm() * 1e-12);
    }

    @Test
    void bodyGravitySourceCarriesSoftening() {
        BodyGravitySource pure = BodyGravitySource.fixed(MU, Vec3.ZERO);
        assertEquals(0.0, pure.softeningLength(), 0.0);
        BodyGravitySource soft = pure.withSoftening(50.0);
        assertEquals(50.0, soft.softeningLength(), 0.0);
        // At the centre the softened source yields zero; the pure one diverges.
        assertEquals(0.0, soft.accelerationAt(Vec3.ZERO, 0.0).norm(), 0.0);
        // withSoftening preserves the rest of the body (checked via far field).
        Vec3 far = new Vec3(1.0e9, 0.0, 0.0);
        assertEquals(pure.accelerationAt(far, 0.0).norm(),
                soft.accelerationAt(far, 0.0).norm(),
                pure.accelerationAt(far, 0.0).norm() * 1e-12);
    }
}
