package wi.unigrav.api.gravity;

/**
 * Immutable specification of the damping of spherical-harmonic terms,
 * acting as a radial multiplier on the potential: V_damped(r) =
 * &#963;(&#8741;r&#8741;) · V(r).
 *
 * <p>Ported from Principia (MIT), {@code physics/harmonic_damping.hpp} and
 * {@code harmonic_damping_body.hpp} (notation per its
 * {@code documentation/Geopotential.pdf}). The semantics are those of
 * Principia, and they are worth stating precisely because they are easy to
 * get backwards:
 *
 * <ul>
 *   <li>for r &#8804; {@link #innerThreshold()}, &#963; = 1 — the harmonics
 *       are <b>fully effective</b> (undamped);
 *   <li>for inner &lt; r &lt; outer, &#963; is a cubic sigmoid going
 *       smoothly (C&#185;, i.e. &#963; and &#963;&#8242; are continuous at
 *       both ends) from 1 down to 0;
 *   <li>for r &#8805; {@link #outerThreshold()}, &#963; = 0 — the harmonics
 *       are faded out entirely and the field is a pure point mass.
 * </ul>
 *
 * So damping fades the harmonic terms out at <b>large</b> distance (in
 * Principia this bounds the series expansion when (R/r)&#8319; is
 * negligible); it does <b>not</b> suppress them near the surface. Like
 * Principia, the invariant {@code outer = 3 · inner} is fixed by the
 * constructor — the sigmoid coefficients are derived from the inner
 * threshold alone:
 * <pre>
 *   &#963;(r) = c&#8321;r + c&#8322;r&#178; + c&#8323;r&#179;,  with
 *   c&#8321; = 9/(4&#8201;s&#8320;), c&#8322; = &#8722;3/(2&#8201;s&#8320;&#178;), c&#8323; = 1/(4&#8201;s&#8320;&#179;),  s&#8320; = inner threshold
 * </pre>
 * which satisfies &#963;(s&#8320;) = 1, &#963;&#8242;(s&#8320;) = 0,
 * &#963;(3&#8201;s&#8320;) = 0, &#963;&#8242;(3&#8201;s&#8320;) = 0.
 *
 * <p>Principia's caller never evaluates beyond the outer threshold; this
 * port instead clamps &#963; = 0 (and &#963;&#8242; = 0) there so the
 * result is well-defined for all r. Units: thresholds in metres.
 */
public final class HarmonicDamping {

    private final double innerThreshold;
    private final double outerThreshold;
    private final double c1;
    private final double c2;
    private final double c3;

    /**
     * Creates a damping with the given inner threshold (m). The outer
     * threshold is fixed at 3 × inner, as in Principia.
     */
    public HarmonicDamping(double innerThreshold) {
        if (innerThreshold <= 0.0 || !Double.isFinite(innerThreshold)) {
            throw new IllegalArgumentException("innerThreshold must be positive and finite");
        }
        this.innerThreshold = innerThreshold;
        this.outerThreshold = 3.0 * innerThreshold;
        // Sigmoid coefficients transcribed from Principia (MIT),
        // physics/harmonic_damping_body.hpp.
        this.c1 = 9.0 / (4.0 * innerThreshold);
        this.c2 = -3.0 / (2.0 * innerThreshold * innerThreshold);
        this.c3 = 1.0 / (4.0 * innerThreshold * innerThreshold * innerThreshold);
    }

    /**
     * Convenience factory taking the inner threshold as a multiple of the
     * body's reference radius.
     */
    public static HarmonicDamping inReferenceRadii(double innerRadii, double referenceRadius) {
        return new HarmonicDamping(innerRadii * referenceRadius);
    }

    /** Below this radius (m), &#963; = 1 (harmonics undamped). */
    public double innerThreshold() {
        return innerThreshold;
    }

    /** At or above this radius (m), &#963; = 0 (pure point-mass field). */
    public double outerThreshold() {
        return outerThreshold;
    }

    /**
     * The damping factor &#963;(r): 1 below the inner threshold, 0 at or
     * above the outer threshold, C&#185; sigmoid in between.
     */
    public double sigma(double r) {
        if (r <= innerThreshold) {
            return 1.0;
        }
        if (r >= outerThreshold) {
            return 0.0;
        }
        return Math.fma(c3, r * r * r, Math.fma(c2, r * r, c1 * r));
    }

    /**
     * r · &#963;&#8242;(r), the combination in which the sigmoid derivative
     * enters &#8711;(&#963;&#156;) = (r&#963;&#8242;·&#156;/r +
 * &#156;&#8242;&#963;)&#8201;r&#770; (transcribed from Principia). Zero outside the
     * transition region.
     */
    double rTimesPrime(double r) {
        if (r <= innerThreshold || r >= outerThreshold) {
            return 0.0;
        }
        double r2 = r * r;
        return Math.fma(3.0 * c3, r2 * r, Math.fma(2.0 * c2, r2, c1 * r));
    }
}
