package wi.unigrav.api.gravity;

import wi.unigrav.api.math.Vec3;

/**
 * Immutable spherical-harmonic model of a body's gravity field, evaluated in
 * the body-fixed frame.
 *
 * <p><b>Normalization convention.</b> Following Principia
 * ({@code physics/geopotential_body.hpp}, notation per its
 * {@code documentation/Geopotential.pdf}), the stored coefficients C&#8339;
 * and S&#8339; are the geodesy <b>fully-normalized</b> (4&#960;)
 * coefficients — the convention used by EGM96/EGM2008 and most published
 * gravity models. Internally the evaluation uses <i>unnormalized</i>
 * associated Legendre functions P&#8339;&#8342; and multiplies each term by
 * the factor
 * <pre>
 *   N(n,m) = sqrt( (2 - &#948;(m,0)) · (2n + 1) · (n-m)! / (n+m)! )
 * </pre>
 * (Principia's {@code numerics/legendre_normalization_factor.mathematica.h}),
 * which converts a normalized coefficient to an unnormalized one. As a
 * sanity anchor: the fully-normalized C&#8339;&#8322;&#8320; relates to the
 * usual J&#8322; by C&#8339;&#8322;&#8320; = &#8722;J&#8322;/&#8730;5, and
 * degrees 0 and 1 carry no harmonic terms (the origin is the body's centre
 * of mass); C&#8339;&#8322;&#8321;/S&#8339;&#8322;&#8321; are assumed zero.
 *
 * <p>Units: reference radius and positions in m, &#956; in m³/s²,
 * accelerations in m/s², angles in rad.
 *
 * <p>The evaluation loop is a Java port of Principia's templated
 * {@code DegreeNOrderM}/{@code DegreeNAllOrders} recursion
 * (physics/geopotential_body.hpp), including its half-angle recurrence for
 * cos(m&#955;)/sin(m&#955;) and (R/r)&#8319;, which reduces error
 * accumulation. An optional {@link HarmonicDamping} (ported from Principia's
 * physics/harmonic_damping*.hpp) fades the harmonic terms out at large
 * distance; when none is configured the full series up to
 * {@link #maxDegree()} is always evaluated. Principia additionally picks
 * per-degree thresholds automatically from a tolerance and damps sectoral
 * terms separately; that threshold-selection machinery is not ported (TODO)
 * — here a single explicit damping applies to all degrees.
 */
public final class SphericalHarmonics {

    private final double referenceRadius;
    private final int maxDegree;
    /** Fully-normalized cosine coefficients, c[n][m] with 0 &#8804; m &#8804; n &#8804; maxDegree. */
    private final double[][] cNorm;
    /** Fully-normalized sine coefficients, same layout. */
    private final double[][] sNorm;
    /** Optional radial damping of the harmonic terms, {@code null} = disabled. */
    private final HarmonicDamping damping;
    /** Whether any coefficient that participates in the degree >= 2 expansion is non-zero. */
    private final boolean hasHarmonicTerms;

    /**
     * Creates a model from square coefficient arrays of side
     * {@code maxDegree + 1}; only the lower triangle (m &#8804; n) is used.
     * No damping.
     */
    public SphericalHarmonics(double referenceRadius, double[][] cNorm, double[][] sNorm) {
        this(referenceRadius, cNorm, sNorm, null);
    }

    /**
     * Creates a model from square coefficient arrays of side
     * {@code maxDegree + 1}; only the lower triangle (m &#8804; n) is used.
     *
     * @param damping optional radial damping of the harmonic terms, or
     *                {@code null} to disable damping
     */
    public SphericalHarmonics(double referenceRadius, double[][] cNorm, double[][] sNorm,
                              HarmonicDamping damping) {
        if (referenceRadius <= 0.0) {
            throw new IllegalArgumentException("referenceRadius must be positive");
        }
        int n = cNorm.length - 1;
        if (n < 0 || sNorm.length != cNorm.length) {
            throw new IllegalArgumentException("coefficient arrays must be square and equally sized");
        }
        for (int i = 0; i <= n; i++) {
            if (cNorm[i].length != cNorm.length || sNorm[i].length != cNorm.length) {
                throw new IllegalArgumentException("coefficient arrays must be square");
            }
        }
        this.referenceRadius = referenceRadius;
        this.maxDegree = n;
        this.damping = damping;
        this.cNorm = new double[cNorm.length][];
        this.sNorm = new double[sNorm.length][];
        boolean hasTerms = false;
        for (int i = 0; i <= n; i++) {
            this.cNorm[i] = cNorm[i].clone();
            this.sNorm[i] = sNorm[i].clone();
            if (i >= 2) {
                for (int m = 0; m <= i; m++) {
                    if (i == 2 && m == 1) {
                        continue;
                    }
                    hasTerms |= cNorm[i][m] != 0.0 || sNorm[i][m] != 0.0;
                }
            }
        }
        this.hasHarmonicTerms = hasTerms;
    }

    /**
     * Convenience factory for a body with only J&#8322; (e.g. an oblate
     * planet): C&#8339;&#8322;&#8320; = &#8722;J&#8322;/&#8730;5 under the
     * fully-normalized convention.
     */
    public static SphericalHarmonics withJ2(double referenceRadius, double j2) {
        double[][] c = new double[3][3];
        c[2][0] = -j2 / Math.sqrt(5.0);
        return new SphericalHarmonics(referenceRadius, c, new double[3][3]);
    }

    public double referenceRadius() {
        return referenceRadius;
    }

    public int maxDegree() {
        return maxDegree;
    }

    /** The configured radial damping, or {@code null} if damping is disabled. */
    public HarmonicDamping damping() {
        return damping;
    }

    /** Fully-normalized C&#8339;(n,m); 0 for m &gt; n. */
    public double cosCoefficient(int n, int m) {
        return m > n ? 0.0 : cNorm[n][m];
    }

    /** Fully-normalized S&#8339;(n,m); 0 for m &gt; n. */
    public double sinCoefficient(int n, int m) {
        return m > n ? 0.0 : sNorm[n][m];
    }

    /**
     * Total acceleration (point mass + harmonics) at a body-fixed offset,
     * in m/s².
     */
    public Vec3 acceleration(Vec3 bodyFixedOffset, double mu) {
        return acceleration(bodyFixedOffset, mu, 0.0);
    }

    /**
     * Total acceleration with Plummer softening applied only to the
     * point-mass term, in m/s². The harmonic correction is unchanged.
     *
     * <p>This model is an exterior spherical-harmonic expansion. Softening
     * the monopole does not regularize the correction at the centre or make
     * the field valid inside {@link #referenceRadius()}.
     */
    public Vec3 acceleration(Vec3 bodyFixedOffset, double mu, double softening) {
        Vec3 pointMass = GravitySource.pointMassAcceleration(mu, bodyFixedOffset, softening);
        if (!hasHarmonicTerms) {
            if (pointMass.x() == 0.0 && pointMass.y() == 0.0 && pointMass.z() == 0.0) {
                return Vec3.ZERO;
            }
            return pointMass;
        }
        return pointMass.add(evaluate(bodyFixedOffset).reducedAcceleration.scale(mu));
    }

    /**
     * Total specific potential energy (point mass + harmonics) at a
     * body-fixed offset, in m²/s²: &#8722;&#956;/r plus the harmonic
     * correction.
     */
    public double potential(Vec3 bodyFixedOffset, double mu) {
        return potential(bodyFixedOffset, mu, 0.0);
    }

    /**
     * Total specific potential energy with Plummer softening applied only
     * to the point-mass term, in m²/s². Its monopole is
     * &#8722;&#956;/&#8730;(r²+&#949;²); the harmonic correction is unchanged.
     *
     * <p>This model is an exterior spherical-harmonic expansion. Softening
     * the monopole does not regularize a non-zero correction at the centre
     * or make the field valid inside {@link #referenceRadius()}.
     */
    public double potential(Vec3 bodyFixedOffset, double mu, double softening) {
        double pointMass = -mu / Math.sqrt(
                bodyFixedOffset.normSquared() + softening * softening);
        if (!hasHarmonicTerms) {
            return pointMass;
        }
        return pointMass + mu * evaluate(bodyFixedOffset).reducedPotential;
    }

    /**
     * Total acceleration for an offset given in the <i>inertial</i> frame:
     * the offset is rotated by {@code -angle} about {@code axis} into the
     * body-fixed frame, the acceleration is evaluated, and the result is
     * rotated back. {@code axis} is the body's rotation axis in inertial
     * coordinates; {@code angle} is the current rotation angle in rad.
     */
    public Vec3 accelerationInertial(Vec3 inertialOffset, double mu, Vec3 axis, double angle) {
        return accelerationInertial(inertialOffset, mu, axis, angle, 0.0);
    }

    /**
     * Inertial-frame total acceleration with Plummer softening applied only
     * to the point-mass term. Rotation and harmonic damping are unchanged.
     */
    public Vec3 accelerationInertial(Vec3 inertialOffset, double mu, Vec3 axis, double angle,
                                     double softening) {
        if (angle == 0.0) {
            return acceleration(inertialOffset, mu, softening);
        }
        Vec3 bodyFixed = rotate(inertialOffset, axis, -angle);
        return rotate(acceleration(bodyFixed, mu, softening), axis, angle);
    }

    /** Rodrigues' rotation formula; {@code angle} in rad. */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        Vec3 k = axis.normalize();
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return v.scale(cos)
                .add(k.cross(v).scale(sin))
                .add(k.scale(k.dot(v) * (1.0 - cos)));
    }

    /** Result of one field evaluation, per unit of &#956;. */
    private record Evaluation(Vec3 reducedAcceleration, double reducedPotential) {
    }

    /**
     * Evaluates the harmonic (non-point-mass) field at a body-fixed offset.
     * Ported from Principia (MIT), physics/geopotential_body.hpp. Without a
     * configured {@link HarmonicDamping}, &#963; = 1 everywhere, which turns
     * &#963;&#156;/r into &#156;/r and &#8711;&#963;&#156; into
     * &#156;&#8242;·r&#770;. With damping, the radial quantities become
     * &#963;&#156;/r and (r&#963;&#8242;·&#156;/r + &#156;&#8242;&#963;)·r&#770;
     * exactly as in Principia's
     * {@code HarmonicDamping::ComputeDampedRadialQuantities}.
     */
    private Evaluation evaluate(Vec3 r) {
        int n2 = maxDegree;
        double r2 = r.normSquared();
        double rNorm = Math.sqrt(r2);
        double oneOverR = 1.0 / rNorm;
        double oneOverR3 = oneOverR / r2;
        Vec3 rHat = r.scale(oneOverR);

        // Radial damping factor and r·σ′ (both trivial when undamped).
        double sigma = 1.0;
        double rSigmaPrime = 0.0;
        if (damping != null) {
            sigma = damping.sigma(rNorm);
            if (sigma == 0.0) {
                // Beyond the outer threshold the field is a pure point mass.
                return new Evaluation(Vec3.ZERO, 0.0);
            }
            rSigmaPrime = damping.rTimesPrime(rNorm);
        }

        double x = r.x();
        double y = r.y();
        double z = r.z();
        double rEquatorial = Math.hypot(x, y);
        double sinB = z * oneOverR;       // sin(latitude)
        double cosB = rEquatorial * oneOverR;

        double cosL = 1.0;
        double sinL = 0.0;
        if (rEquatorial > 0.0) {
            cosL = x / rEquatorial;
            sinL = y / rEquatorial;
        }

        // Unit vectors along increasing latitude and longitude.
        Vec3 gradBVector = new Vec3(-sinB * cosL, -sinB * sinL, cosB);
        Vec3 gradLVector = new Vec3(-sinL, cosL, 0.0);

        int size = Math.max(n2 + 2, 3);
        double[] rmOverR = new double[size];     // (R/r)^n / r, index 0 unused
        double[] cosML = new double[size];       // cos(m·λ), index 0 unused
        double[] sinML = new double[size];       // sin(m·λ), index 0 unused
        double[] cosBm = new double[size];       // cos^m(β)
        double[][] p = new double[size][size];   // unnormalized P_n,m(sin β)

        rmOverR[1] = referenceRadius * oneOverR3;
        cosML[1] = cosL;
        sinML[1] = sinL;
        cosBm[0] = 1.0;
        cosBm[1] = cosB;
        p[0][0] = 1.0;
        p[1][0] = sinB;
        p[1][1] = 1.0;

        double accRadial = 0.0;
        double accLatitudinal = 0.0;
        double accLongitudinal = 0.0;
        double potential = 0.0;

        for (int n = 2; n <= n2; n++) {
            // (R/r)^n / r, from the values at ~n/2 to reduce error
            // accumulation (transcribed from Principia).
            if (n % 2 == 0) {
                int h = n / 2;
                rmOverR[n] = rmOverR[h] * rmOverR[h] * r2;
            } else {
                int h1 = n / 2;
                int h2 = n - h1;
                rmOverR[n] = rmOverR[h1] * rmOverR[h2] * r2;
            }

            for (int m = 0; m <= n; m++) {
                if (m == n) {
                    // cos(m·λ)/sin(m·λ) and cos^m(β) from the values at ~m/2
                    // (transcribed from Principia).
                    if (m % 2 == 0) {
                        int h = m / 2;
                        sinML[m] = 2.0 * sinML[h] * cosML[h];
                        cosML[m] = (cosML[h] + sinML[h]) * (cosML[h] - sinML[h]);
                        cosBm[m] = cosBm[h] * cosBm[h];
                    } else {
                        int h1 = m / 2;
                        int h2 = m - h1;
                        sinML[m] = sinML[h1] * cosML[h2] + cosML[h1] * sinML[h2];
                        cosML[m] = cosML[h1] * cosML[h2] - sinML[h1] * sinML[h2];
                        cosBm[m] = cosBm[h1] * cosBm[h2];
                    }
                }

                if (m == 0) {
                    // Zonal Legendre recurrence.
                    p[n][0] = ((2 * n - 1) * sinB * p[n - 1][0] - (n - 1) * p[n - 2][0]) / n;
                }
                if (m < n) {
                    // Associated Legendre recurrence for p[n][m+1].
                    if (m == n - 1) {
                        p[n][m + 1] = (2 * n - 1.0) * (m + 1) * p[n - 1][m] / n;
                    } else if (m == n - 2) {
                        p[n][m + 1] = (2 * n - 1.0)
                                * (sinB * p[n - 1][m + 1] + (m + 1) * p[n - 1][m]) / n;
                    } else {
                        p[n][m + 1] = ((2 * n - 1.0)
                                * (sinB * p[n - 1][m + 1] + (m + 1) * p[n - 1][m])
                                - (n - 1.0) * p[n - 2][m + 1]) / n;
                    }
                }

                // C̄21/S̄21 are assumed zero (origin on the figure axis).
                if (n == 2 && m == 1) {
                    continue;
                }

                double cnm = cNorm[n][m];
                double snm = sNorm[n][m];
                if (cnm == 0.0 && snm == 0.0) {
                    continue;
                }
                double norm = normalizationFactor(n, m);

                double bNm = cosBm[m] * p[n][m];
                double lNm = m == 0 ? cnm : cnm * cosML[m] + snm * sinML[m];
                double rmOverRn = rmOverR[n];
                // ℜʹ = dℜ/dr with ℜ = (R/r)^n.
                double rmPrime = -(n + 1.0) * rmOverRn;
                // Damped radial quantities (σ = 1, r·σ′ = 0 when undamped):
                //   σℜ/r = σ · ℜ/r
                //   ∇σℜ  = (r·σ′ · ℜ/r + ℜ′·σ) r̂
                double sigmaRmOverRn = sigma * rmOverRn;
                double gradSigmaRm = Math.fma(rSigmaPrime, rmOverRn, rmPrime * sigma);

                potential += -norm * (sigmaRmOverRn * rNorm) * bNm * lNm;

                // ∂(cos^m β · P_n,m(sin β))/∂β, with the m = 0, cos β = 0
                // singularity removed (transcribed from Principia).
                double gradBPolynomials = 0.0;
                if (m < n) {
                    gradBPolynomials = cosB * cosBm[m] * p[n][m + 1];
                }
                if (m > 0) {
                    gradBPolynomials -= m * sinB * cosBm[m - 1] * p[n][m];
                }

                accRadial += norm * bNm * lNm * gradSigmaRm;
                accLatitudinal += norm * sigmaRmOverRn * lNm * gradBPolynomials;
                if (m > 0) {
                    // 𝔅/cos β · ∂𝔏/∂λ, with the cos β = 0 singularity removed.
                    accLongitudinal += norm * sigmaRmOverRn * cosBm[m - 1] * p[n][m]
                            * m * (snm * cosML[m] - cnm * sinML[m]);
                }
            }
        }

        Vec3 reducedAcceleration = rHat.scale(accRadial)
                .add(gradBVector.scale(accLatitudinal))
                .add(gradLVector.scale(accLongitudinal));
        return new Evaluation(reducedAcceleration, potential);
    }

    /**
     * Converts a fully-normalized coefficient to an unnormalized one:
     * N(n,m) = sqrt((2 - &#948;(m,0))·(2n+1)·(n-m)!/(n+m)!). Same convention
     * as Principia's legendre_normalization_factor.mathematica.h.
     */
    public static double normalizationFactor(int n, int m) {
        double factorialRatio = 1.0; // (n+m)! / (n-m)!
        for (int k = n - m + 1; k <= n + m; k++) {
            factorialRatio *= k;
        }
        return Math.sqrt((m == 0 ? 1.0 : 2.0) * (2 * n + 1) / factorialRatio);
    }
}
