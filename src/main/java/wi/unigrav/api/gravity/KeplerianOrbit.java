package wi.unigrav.api.gravity;

import wi.unigrav.api.math.Vec3;

/**
 * Immutable Keplerian (two-body, conic) orbit: position and velocity of a
 * body relative to the orbit's focus as an analytic function of time.
 *
 * <p>Elements: semi-major axis {@code a} (m), eccentricity {@code e}
 * (0 &#8804; e &lt; 1), inclination {@code i}, right ascension of the
 * ascending node &#937;, argument of periapsis &#969;, mean anomaly at
 * epoch M&#8320; (all angles in rad), epoch t&#8320; (s), and the central
 * gravitational parameter &#956; (m³/s²) that sets the mean motion
 * n = &#8730;(&#956;/a³).
 *
 * <p>The perifocal position a·(cos&#8201;E &#8722; e, &#8730;(1&#8722;e²)·sin&#8201;E)
 * is rotated into the inertial frame by R&#8327;(&#937;)·R&#8321;(i)·R&#8327;(&#969;).
 * The eccentric anomaly E is obtained from Kepler's equation
 * E &#8722; e·sin&#8201;E = M by Newton iteration (tolerance 1e-12 rad).
 */
public final class KeplerianOrbit {

    /** Convergence tolerance of the eccentric-anomaly Newton solve, rad. */
    public static final double ECCENTRIC_ANOMALY_TOLERANCE = 1.0e-12;

    private final double semiMajorAxis;
    private final double eccentricity;
    private final double inclination;
    private final double raan;
    private final double argPeriapsis;
    private final double meanAnomalyAtEpoch;
    private final double epoch;
    private final double centralMu;

    private final double meanMotion;
    private final double sqrtOneMinusE2;
    // Rows of the perifocal → inertial rotation R_z(Ω)·R_x(i)·R_z(ω),
    // acting on (x_p, y_p).
    private final double rotXX;
    private final double rotXY;
    private final double rotYX;
    private final double rotYY;
    private final double rotZX;
    private final double rotZY;

    public KeplerianOrbit(double semiMajorAxis, double eccentricity, double inclination,
                          double raan, double argPeriapsis, double meanAnomalyAtEpoch,
                          double epoch, double centralMu) {
        if (semiMajorAxis <= 0.0) {
            throw new IllegalArgumentException("semiMajorAxis must be positive");
        }
        if (eccentricity < 0.0 || eccentricity >= 1.0) {
            throw new IllegalArgumentException("only closed orbits: 0 <= eccentricity < 1");
        }
        if (centralMu <= 0.0) {
            throw new IllegalArgumentException("centralMu must be positive");
        }
        this.semiMajorAxis = semiMajorAxis;
        this.eccentricity = eccentricity;
        this.inclination = inclination;
        this.raan = raan;
        this.argPeriapsis = argPeriapsis;
        this.meanAnomalyAtEpoch = meanAnomalyAtEpoch;
        this.epoch = epoch;
        this.centralMu = centralMu;
        this.meanMotion = Math.sqrt(centralMu / (semiMajorAxis * semiMajorAxis * semiMajorAxis));
        this.sqrtOneMinusE2 = Math.sqrt(1.0 - eccentricity * eccentricity);
        double cosO = Math.cos(raan);
        double sinO = Math.sin(raan);
        double cosI = Math.cos(inclination);
        double sinI = Math.sin(inclination);
        double cosW = Math.cos(argPeriapsis);
        double sinW = Math.sin(argPeriapsis);
        this.rotXX = cosO * cosW - sinO * sinW * cosI;
        this.rotXY = -cosO * sinW - sinO * cosW * cosI;
        this.rotYX = sinO * cosW + cosO * sinW * cosI;
        this.rotYY = -sinO * sinW + cosO * cosW * cosI;
        this.rotZX = sinW * sinI;
        this.rotZY = cosW * sinI;
    }

    /** Circular equatorial orbit of the given radius (m), zero phase at t = 0. */
    public static KeplerianOrbit circular(double radius, double centralMu) {
        return new KeplerianOrbit(radius, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, centralMu);
    }

    public double semiMajorAxis() {
        return semiMajorAxis;
    }

    public double eccentricity() {
        return eccentricity;
    }

    public double centralMu() {
        return centralMu;
    }

    public double meanMotion() {
        return meanMotion;
    }

    /** Orbital period 2π/n, in seconds. */
    public double period() {
        return 2.0 * Math.PI / meanMotion;
    }

    /** Mean anomaly at time t (s), in rad (not normalized). */
    public double meanAnomalyAt(double t) {
        return meanAnomalyAtEpoch + meanMotion * (t - epoch);
    }

    /** Position relative to the orbit's focus at time t (s), in m. */
    public Vec3 positionAt(double t) {
        double ecc = eccentricAnomaly(meanAnomalyAt(t));
        double xp = semiMajorAxis * (Math.cos(ecc) - eccentricity);
        double yp = semiMajorAxis * sqrtOneMinusE2 * Math.sin(ecc);
        return rotate(xp, yp);
    }

    /** Velocity relative to the orbit's focus at time t (s), in m/s. */
    public Vec3 velocityAt(double t) {
        double ecc = eccentricAnomaly(meanAnomalyAt(t));
        double factor = semiMajorAxis * meanMotion / (1.0 - eccentricity * Math.cos(ecc));
        return rotate(-factor * Math.sin(ecc), factor * sqrtOneMinusE2 * Math.cos(ecc));
    }

    private Vec3 rotate(double xp, double yp) {
        return new Vec3(
                Math.fma(rotXX, xp, rotXY * yp),
                Math.fma(rotYX, xp, rotYY * yp),
                Math.fma(rotZX, xp, rotZY * yp));
    }

    /**
     * Solves Kepler's equation E &#8722; e·sin&#8201;E = M for E by Newton
     * iteration, to {@link #ECCENTRIC_ANOMALY_TOLERANCE}. M is first
     * normalized to [&#8722;&#960;, &#960;].
     */
    double eccentricAnomaly(double meanAnomaly) {
        double m = meanAnomaly - 2.0 * Math.PI * Math.rint(meanAnomaly / (2.0 * Math.PI));
        double ecc = eccentricity < 0.8 ? m : Math.copySign(Math.PI, m);
        for (int i = 0; i < 50; i++) {
            double delta = (ecc - eccentricity * Math.sin(ecc) - m)
                    / (1.0 - eccentricity * Math.cos(ecc));
            ecc -= delta;
            if (Math.abs(delta) < ECCENTRIC_ANOMALY_TOLERANCE) {
                return ecc;
            }
        }
        throw new ArithmeticException(
                "Kepler's equation did not converge for M=" + meanAnomaly + ", e=" + eccentricity);
    }
}
