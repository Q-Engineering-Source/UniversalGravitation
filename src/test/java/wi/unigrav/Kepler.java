package wi.unigrav;

import wi.unigrav.api.math.Vec3;

/**
 * Analytic two-body (Keplerian) propagator used as ground truth in tests.
 * Computes classical elements from an initial state and propagates by
 * solving Kepler's equation with Newton iterations.
 */
final class Kepler {

    final double mu;
    final double a;
    final double e;
    /** Perifocal basis: P toward periapsis, Q 90° ahead in the orbital plane. */
    final Vec3 p;
    final Vec3 q;
    private final double meanMotion;
    private final double m0; // mean anomaly at the reference epoch

    private Kepler(double mu, double a, double e, Vec3 p, Vec3 q, double m0) {
        this.mu = mu;
        this.a = a;
        this.e = e;
        this.p = p;
        this.q = q;
        this.meanMotion = Math.sqrt(mu / (a * a * a));
        this.m0 = m0;
    }

    static Kepler fromState(double mu, Vec3 r, Vec3 v) {
        double rNorm = r.norm();
        double v2 = v.normSquared();
        Vec3 h = r.cross(v);
        Vec3 w = h.normalize();
        // Eccentricity vector: ((v² - μ/r) r - (r·v) v) / μ.
        Vec3 eVec = r.scale(v2 - mu / rNorm).sub(v.scale(r.dot(v))).scale(1.0 / mu);
        double e = eVec.norm();
        double a = 1.0 / (2.0 / rNorm - v2 / mu);
        Vec3 p = eVec.scale(1.0 / e);
        Vec3 q = w.cross(p);
        double cosE = (1.0 - rNorm / a) / e;
        double sinE = r.dot(q) / (a * Math.sqrt(1.0 - e * e));
        double e0 = Math.atan2(sinE, cosE);
        double m0 = e0 - e * Math.sin(e0);
        return new Kepler(mu, a, e, p, q, m0);
    }

    /** Orbital period in seconds. */
    double period() {
        return 2.0 * Math.PI / meanMotion;
    }

    private static double solveKepler(double m, double e) {
        double ecc = m; // good enough starter for e <= 0.5 with Newton
        for (int i = 0; i < 50; i++) {
            double f = ecc - e * Math.sin(ecc) - m;
            double fp = 1.0 - e * Math.cos(ecc);
            double delta = f / fp;
            ecc -= delta;
            if (Math.abs(delta) < 1.0e-15) {
                break;
            }
        }
        return ecc;
    }

    Vec3 positionAt(double dt) {
        double ecc = solveKepler(m0 + meanMotion * dt, e);
        double x = a * (Math.cos(ecc) - e);
        double y = a * Math.sqrt(1.0 - e * e) * Math.sin(ecc);
        return p.scale(x).add(q.scale(y));
    }

    Vec3 velocityAt(double dt) {
        double ecc = solveKepler(m0 + meanMotion * dt, e);
        double factor = a * meanMotion / (1.0 - e * Math.cos(ecc));
        double vx = -factor * Math.sin(ecc);
        double vy = factor * Math.sqrt(1.0 - e * e) * Math.cos(ecc);
        return p.scale(vx).add(q.scale(vy));
    }
}
