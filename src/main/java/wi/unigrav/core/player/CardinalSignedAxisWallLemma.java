package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Literal Y_POS to X_NEG/Z_POS/Z_NEG mappings of the frozen two-component theorem.
 * Proof: ug-cardinal-wall-formal-20261015-r1/receipt-v1/PROOF.txt,
 * SHA256 5031B64FAE952ECB503A644E667A8D212F945E3A80185D1E62454D1746A850CF.
 * Development/runtime dependency identities and reduction receipt are the same
 * as CardinalTwoComponentWallLemma. No alternate quaternion signs are covered.
 */
final class CardinalSignedAxisWallLemma {
    private CardinalSignedAxisWallLemma() { }

    static final class Receipt {
        private final CapsuleArcEnclosure.Path path;
        private final CardinalRealizedArcRecipe recipe;
        private final StoredDyadic.Work work;
        private final int axis;
        private final StoredDyadic lower, upper, yLower, yUpper;
        private Receipt(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
                StoredDyadic.Work work, int axis, StoredDyadic lower, StoredDyadic upper,
                StoredDyadic yLower, StoredDyadic yUpper) {
            this.path = path; this.recipe = recipe; this.work = work; this.axis = axis;
            this.lower = lower; this.upper = upper; this.yLower = yLower; this.yUpper = yUpper;
        }
        boolean proves(CapsuleArcEnclosure.Path exactPath, CardinalRealizedArcRecipe exactRecipe,
                int requestedAxis, int side, double plane, double from, double to, StoredDyadic.Work same) {
            Objects.requireNonNull(same).charge(1);
            if (same != work || exactPath != path || exactRecipe != recipe
                    || !recipe.matches(recipe.start(), recipe.target(), same)
                    || (requestedAxis != axis && requestedAxis != 1) || (side != 1 && side != -1)
                    || !Double.isFinite(plane) || !Double.isFinite(from) || !Double.isFinite(to)
                    || from < 0 || from > to || to > 1) return false;
            var support = requestedAxis == 1 ? (side > 0 ? yLower : yUpper) : (side > 0 ? lower : upper);
            int comparison = support.compare(StoredDyadic.of(plane, same), same);
            return side > 0 ? comparison >= 0 : comparison <= 0;
        }
    }

    static Receipt prepare(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
            double[] selectedStart, double[] selectedTarget, StoredDyadic.Work same) {
        Objects.requireNonNull(same).charge(32);
        if (recipe == null || path == null || !recipe.matchesWork(same)
                || !path.matchesFixedWallInputs(recipe, selectedStart, selectedTarget, same)
                || !CardinalLiteralWallDomain.matches(path, recipe, true, same)
                || !(recipe.sourceContainment().source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != CardinalFrame.Y_POS
                || !StoredRealizationLimits.sameRaw(recipe.start().placement().attitude(), CardinalFrame.Y_POS.attitude()))
            return null;
        CardinalFrame literal = null;
        for (var frame : new CardinalFrame[] {CardinalFrame.X_NEG, CardinalFrame.Z_POS, CardinalFrame.Z_NEG}) {
            if (StoredRealizationLimits.sameRaw(recipe.target().attitude(), frame.attitude())) literal = frame;
        }
        if (literal == null) return null;
        var q0 = CardinalFrame.Y_POS.attitude();
        var q1 = literal.attitude();
        for (int i = 0; i < 4; i++) {
            same.charge(2);
            double start = i == 0 ? q0.x() : i == 1 ? q0.y() : i == 2 ? q0.z() : q0.w();
            double target = i == 0 ? q1.x() : i == 1 ? q1.y() : i == 2 ? q1.z() : q1.w();
            if (!StoredRealizationLimits.sameRaw(selectedStart[i], start)
                    || !StoredRealizationLimits.sameRaw(selectedTarget[i], target)) return null;
        }
        if (!StoredRealizationLimits.sameRaw(recipe.start().placement().center(), recipe.target().center())
                || !StoredRealizationLimits.sameRaw(recipe.start().capsule().radius(), recipe.target().capsule().radius()))
            return null;
        // Actual chain: s=RN(1-t), p=RN(t*k), W=RN(s+p)>0 and 0<=p<=W.
        // max-scaled input has one literal vector component +/-a, W=1,
        // a=RN(p/W) in [0,1]. The actual ctor yields b=RN(1/sqrt(RN(RN(a*a)+1)))
        // and x=RN(a*b). Canonical-zero basis reduction gives transverse
        // +/-RN(b*(2*x)), vertical RN(1-RN(x*(2*x))), and remaining coordinate 0.
        // Frozen entire-a theorem gives |transverse|<=nextUp(1), |vertical|<=1;
        // no monotonic t, sampled proof, alternative sign or third component is used.
        double cap = Math.nextUp(1.0);
        if (StoredDyadic.of(cap, same).subtract(StoredDyadic.one(same), same)
                .compare(StoredDyadic.of(0x1p-52, same), same) != 0)
            throw new IllegalStateException("binary64 signed-axis cap identity differs");
        int axis = literal == CardinalFrame.X_NEG ? 0 : 2;
        var center = recipe.start().placement().center();
        double c = StoredRealizationLimits.component(center, axis);
        same.charge(6);
        double product = cap * recipe.halfLength();
        double n = c - product, p = c + product;
        double yn = center.y() - recipe.halfLength(), yp = center.y() + recipe.halfLength();
        if (!Double.isFinite(product) || !Double.isFinite(n) || !Double.isFinite(p)
                || !Double.isFinite(yn) || !Double.isFinite(yp)) return null;
        var radius = StoredDyadic.of(recipe.start().capsule().radius(), same);
        var lower = support(n, radius, 1, same);
        var upper = support(p, radius, -1, same);
        var yLower = support(yn, radius, 1, same);
        var yUpper = support(yp, radius, -1, same);
        for (var geometry : new StoredCapsuleGeometry[] {recipe.start().placement().storedCapsuleGeometry(), recipe.target().storedCapsuleGeometry()}) {
            for (var endpoint : new dev.crlhitbox.api.geometry.Vec3d[] {geometry.negativeEndpoint(), geometry.positiveEndpoint()}) {
                double v = StoredRealizationLimits.component(endpoint, axis);
                lower = extremum(lower, support(v, radius, 1, same), 1, same);
                upper = extremum(upper, support(v, radius, -1, same), -1, same);
                yLower = extremum(yLower, support(endpoint.y(), radius, 1, same), 1, same);
                yUpper = extremum(yUpper, support(endpoint.y(), radius, -1, same), -1, same);
            }
        }
        return new Receipt(path, recipe, same, axis, lower, upper, yLower, yUpper);
    }

    private static StoredDyadic support(double endpoint, StoredDyadic radius, int side, StoredDyadic.Work same) {
        var value = StoredDyadic.of(endpoint, same);
        return side > 0 ? value.subtract(radius, same) : value.add(radius, same);
    }
    private static StoredDyadic extremum(StoredDyadic a, StoredDyadic b, int side, StoredDyadic.Work same) {
        int comparison = a.compare(b, same);
        return side > 0 ? (comparison <= 0 ? a : b) : (comparison >= 0 ? a : b);
    }
}
