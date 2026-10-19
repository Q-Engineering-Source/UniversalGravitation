package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Exact sign certificate for one degree-at-most-three arc separator numerator.
 * A certificate says only that this supplied polynomial is nonnegative. Its
 * caller must prove the positive denominator and containment of every actual
 * stored endpoint before using it as a collision separator.
 */
final class CapsuleArcPlanePolynomial {
    private final StoredDyadic[] coefficients;

    CapsuleArcPlanePolynomial(StoredDyadic constant, StoredDyadic linear,
            StoredDyadic quadratic, StoredDyadic cubic) {
        coefficients = new StoredDyadic[] {
                Objects.requireNonNull(constant, "constant"),
                Objects.requireNonNull(linear, "linear"),
                Objects.requireNonNull(quadratic, "quadratic"),
                Objects.requireNonNull(cubic, "cubic")};
    }

    /** Negative Bernstein coefficients mean inconclusive, not physical collision. */
    boolean certifiesNonnegative(double from, double to, StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        if (!Double.isFinite(from) || !Double.isFinite(to)
                || from < 0 || from > to || to > 1) {
            throw new IllegalArgumentException("arc interval must lie within [0,1]");
        }
        StoredDyadic a = StoredDyadic.of(from, work);
        StoredDyadic span = StoredDyadic.of(to, work).subtract(a, work);
        StoredDyadic a2 = a.multiply(a, work);
        StoredDyadic three = StoredDyadic.of(3, work);
        StoredDyadic c0 = coefficients[0];
        StoredDyadic c1 = coefficients[1];
        StoredDyadic c2 = coefficients[2];
        StoredDyadic c3 = coefficients[3];
        // p(a + span*u) = d0 + d1*u + d2*u^2 + d3*u^3.
        StoredDyadic d0 = c3.multiply(a, work).add(c2, work)
                .multiply(a, work).add(c1, work).multiply(a, work).add(c0, work);
        StoredDyadic d1 = c1.add(c2.multiply(a, work).scalePow2(1, work), work)
                .add(c3.multiply(a2, work).multiply(three, work), work).multiply(span, work);
        StoredDyadic span2 = span.multiply(span, work);
        StoredDyadic d2 = c2.add(c3.multiply(a, work).multiply(three, work), work)
                .multiply(span2, work);
        StoredDyadic d3 = c3.multiply(span2, work).multiply(span, work);
        // Three times the cubic Bernstein coefficients avoids division by 3.
        StoredDyadic b0 = d0.multiply(three, work);
        StoredDyadic b1 = b0.add(d1, work);
        StoredDyadic b2 = b0.add(d1.scalePow2(1, work), work).add(d2, work);
        StoredDyadic b3 = d0.add(d1, work).add(d2, work).add(d3, work)
                .multiply(three, work);
        return b0.signum() >= 0 && b1.signum() >= 0
                && b2.signum() >= 0 && b3.signum() >= 0;
    }
}
