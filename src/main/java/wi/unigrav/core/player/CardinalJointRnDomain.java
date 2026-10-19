package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;

/** Narrow 9CB binary64 recipe proof, not a collision or path-clearance certificate. */
final class CardinalJointRnDomain {
    static final double BASIS_BOUND = 1 + 0x1p-48;
    private static final long NOMINAL_BITS = 0x3fe3333320000000L;
    private static final long REALIZED_BITS = 0x3fe333331ffffffcL;
    record Certificate(double basisBound, int enumeratedValues, Object workIdentity) { }
    private CardinalJointRnDomain() { }

    static Certificate prove(Vec3d center, double h, double realized, StoredDyadic.Work work) {
        work.charge(1);
        if (Double.doubleToRawLongBits(h) != NOMINAL_BITS
                || Double.doubleToRawLongBits(realized) != REALIZED_BITS)
            throw new CardinalRealizedArcRecipe.DomainException("joint RN scalar profile does not match");
        var u = StoredDyadic.of(0x1p-53, work);
        var g = u;
        var one = StoredDyadic.one(work);
        var onePlusU = one.add(u, work);
        var delta = StoredDyadic.of(h, work).subtract(StoredDyadic.of(realized, work), work);
        if (delta.compare(g.scalePow2(2, work), work) != 0 || !(realized >= .5 && h < 1))
            throw new CardinalRealizedArcRecipe.DomainException("joint RN grid profile does not match");
        for (int axis = 0; axis < 3; axis++) {
            double c = StoredRealizationLimits.component(center, axis);
            StoredRealizationLimits.requirePoint(center, "joint RN center");
            if (StoredDyadic.gap64(c, work).compare(g.scalePow2(1, work), work) < 0)
                throw new CardinalRealizedArcRecipe.DomainException("joint RN coordinate grid is too fine");
            // |v| <= 1/2: the existing exact multiplication bound after cancellation
            // of u*|Hnom| is below the coordinate-only part of the original domain.
            var coefficient = delta.add(u.multiply(StoredDyadic.of(realized, work), work), work)
                    .add(u.multiply(u, work).multiply(StoredDyadic.of(h, work), work), work);
            var residual = coefficient.scalePow2(-1, work)
                    .add(onePlusU.multiply(StoredDyadic.lambdaHalf(work), work), work);
            var base = onePlusU.multiply(StoredDyadic.gap64(c, work).scalePow2(1, work), work);
            if (residual.compare(base, work) > 0)
                throw new CardinalRealizedArcRecipe.DomainException("joint RN small-component proof failed");
        }
        proveNormalizationBound(work);
        /* 1/2 <= |v| <= 1: x=|v|h is in [.25,1), x*=|v|hReal >= x-delta.
         * On either side of .5, delta is an even 8/4 grid displacement, so RN ties
         * retain parity and RN(x)-RN(x-delta)=delta. Across .5, RN errors give
         * delta+3g/4; both rounded outputs are on the g/2 grid, tightening the
         * difference to delta+g/2. If RN(x)<.5, its deficit below .5 is at least
         * g/2 and tightens this to delta; otherwise u*|Hnom| >= g/2 exactly.
         * Thus original (1+u)*2gap(C)+u*|Hnom|+eta covers the difference.
         * No generic integer-translation lemma is used (odd ties can expand).
         */
        int values = 0;
        for (double v = 1; v <= BASIS_BOUND; v = Math.nextUp(v)) {
            work.charge(1);
            for (int axis = 0; axis < 3; axis++) {
                if (!componentFits(StoredRealizationLimits.component(center, axis), v, h, realized, work))
                    throw new CardinalRealizedArcRecipe.DomainException("joint RN tail component exceeds original domain");
            }
            values++;
        }
        if (values != 17) throw new IllegalStateException("joint RN tail grid changed");
        // Negative v changes only sign; abs difference and symmetric C +/- H domain agree.
        return new Certificate(BASIS_BOUND, values, work.identity());
    }

    static boolean componentFits(double center, double v, double h, double realized, StoredDyadic.Work work) {
        work.charge(4);
        if (!Double.isFinite(v) || Math.abs(v) > BASIS_BOUND) return false;
        double nominal = v * h, actual = v * realized;
        var difference = StoredDyadic.of(nominal, work).subtract(StoredDyadic.of(actual, work), work).abs(work);
        var gap = StoredDyadic.gap64(center, work);
        for (double value : new double[] {nominal, center - nominal, center + nominal}) {
            var candidate = StoredDyadic.gap64(value, work);
            if (candidate.compare(gap, work) > 0) gap = candidate;
        }
        var u = StoredDyadic.of(0x1p-53, work);
        var bound = StoredDyadic.one(work).add(u, work).multiply(gap.scalePow2(1, work), work)
                .add(u.multiply(StoredDyadic.of(Math.abs(nominal), work), work), work)
                .add(StoredDyadic.lambdaHalf(work), work);
        return difference.compare(bound, work) <= 0;
    }

    /** Exact numerator/denominator cross-products; there is no rounded rational division. */
    static void proveNormalizationBound(StoredDyadic.Work work) {
        var one = StoredDyadic.one(work);
        var u = StoredDyadic.of(0x1p-53, work);
        var up = one.add(u, work);
        var down = one.subtract(u, work);
        var up2 = up.multiply(up, work);
        var up4 = up2.multiply(up2, work);
        var down2 = down.multiply(down, work);
        var down4 = down2.multiply(down2, work);
        var u2 = u.multiply(u, work);
        var u4 = u2.multiply(u2, work);
        // Actual finite 9CB ctor: |scaled_i|<=1 and one is exactly +/-1;
        // rounded sum T is in [1,4], eta<=u^2. sqrt/divide normalization gives
        // A>=T*d, d=(1-u)^4-8u^2(1+u)^4. rotateComponents has three rounded
        // operations per term, gamma3=3u/(1-3u). These are operation bounds
        // over the entire finite ctor domain, not endpoint requireNorm reuse.
        var d = down4.subtract(u2.multiply(up4, work).scalePow2(3, work), work);
        if (d.signum() <= 0) throw new CardinalRealizedArcRecipe.DomainException("normalization denominator unproved");
        var denominator = down2.multiply(d, work);
        var extra = up.multiply(u2, work).scalePow2(4, work).add(u4.scalePow2(2, work), work);
        var numerator = up4.add(extra.multiply(denominator, work), work);
        // Smax=numerator/denominator; Bcalc=2Smax-1 + gamma3(1+2Smax)+8u^2(1+u)^3.
        var threeU = u.add(u, work).add(u, work);
        var gammaDenominator = one.subtract(threeU, work);
        var totalDenominator = denominator.multiply(gammaDenominator, work);
        var tail = u2.multiply(up2.multiply(up, work), work).scalePow2(3, work);
        var totalNumerator = numerator.scalePow2(1, work).subtract(denominator, work)
                .multiply(gammaDenominator, work)
                .add(threeU.multiply(denominator.add(numerator.scalePow2(1, work), work), work), work)
                .add(tail.multiply(totalDenominator, work), work);
        if (totalNumerator.compare(StoredDyadic.of(BASIS_BOUND, work).multiply(totalDenominator, work), work) > 0)
            throw new CardinalRealizedArcRecipe.DomainException("actual normalization basis bound unproved");
    }
}
