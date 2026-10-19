package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Separate mapping of the frozen two-component theorem to canonical Y_POS -> X_POS.
 * Proof input: ug-cardinal-wall-formal-20261015-r1/receipt-v1/PROOF.txt,
 * SHA256 5031B64FAE952ECB503A644E667A8D212F945E3A80185D1E62454D1746A850CF.
 * Development whole JAR: 9CB032EF6DD69EE33C4C79EAE8708A28523880BBD25285CE81AD450988324B8F.
 * Runtime whole JAR: EEE9F1C5323C743A1D898BE305834825E8F68E53837C4A0DFB97B93AEEABE284.
 * Runtime bytecode/reduction receipt SHA256:
 * D9907F73BE67F74C60E115777528227D42FCA9A42250C3990F30A0F9A726F5C0.
 * This certifies two whole-capsule lower supports, never obstacle or installation authority.
 */
final class CardinalYToXWallLemma {
    private CardinalYToXWallLemma() { }

    static final class Receipt {
        private final CapsuleArcEnclosure.Path path;
        private final CardinalRealizedArcRecipe recipe;
        private final StoredDyadic.Work work;
        private final StoredDyadic xLower, yLower;
        private final long numericWork;

        private Receipt(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
                StoredDyadic.Work work, StoredDyadic xLower, StoredDyadic yLower, long numericWork) {
            this.path = path; this.recipe = recipe; this.work = work;
            this.xLower = xLower; this.yLower = yLower; this.numericWork = numericWork;
        }
        long numericWorkDelta() { return numericWork; }
        boolean proves(CapsuleArcEnclosure.Path exactPath, CardinalRealizedArcRecipe exactRecipe,
                int axis, int side, double plane, double from, double to, StoredDyadic.Work same) {
            Objects.requireNonNull(same).charge(1);
            if (path != exactPath || recipe != exactRecipe || work != same
                    || !recipe.matches(recipe.start(), recipe.target(), same)
                    || (axis != 0 && axis != 1) || side != 1 || !Double.isFinite(plane)
                    || !Double.isFinite(from) || !Double.isFinite(to) || from < 0 || from > to || to > 1)
                return false;
            return (axis == 0 ? xLower : yLower).compare(StoredDyadic.of(plane, same), same) >= 0;
        }
    }

    static Receipt prepare(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
            double[] selectedStart, double[] selectedTarget, StoredDyadic.Work same) {
        Objects.requireNonNull(same);
        long before = same.units();
        same.charge(32);
        if (recipe == null || path == null || !recipe.matchesWork(same)
                || !path.matchesFixedWallInputs(recipe, selectedStart, selectedTarget, same)
                || !(recipe.sourceContainment().source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != CardinalFrame.Y_POS
                || !StoredRealizationLimits.sameRaw(recipe.start().placement().attitude(), CardinalFrame.Y_POS.attitude())
                || !StoredRealizationLimits.sameRaw(recipe.target().attitude(), CardinalFrame.X_POS.attitude())
                || !CardinalLiteralWallDomain.matches(path, recipe, true, same))
            return null;
        var q0 = CardinalFrame.Y_POS.attitude();
        var q1 = CardinalFrame.X_POS.attitude();
        for (int i = 0; i < 4; i++) {
            double start = i == 0 ? q0.x() : i == 1 ? q0.y() : i == 2 ? q0.z() : q0.w();
            double target = i == 0 ? q1.x() : i == 1 ? q1.y() : i == 2 ? q1.z() : q1.w();
            if (!StoredRealizationLimits.sameRaw(selectedStart[i], start)
                    || !StoredRealizationLimits.sameRaw(selectedTarget[i], target)) return null;
        }
        if (!(q1.z() < 0) || q1.w() != -q1.z()
                || !StoredRealizationLimits.sameRaw(recipe.start().placement().center(), recipe.target().center())
                || !StoredRealizationLimits.sameRaw(recipe.start().capsule().radius(), recipe.target().capsule().radius()))
            return null;

        // Actual Path.at: Z=RN(-t*k), W=RN(RN(1-t)+RN(t*k)). Monotonic RN gives
        // 0<=abs(Z)<=W and W>0 on the closed domain. In the actual 9CB constructor,
        // a=RN(abs(Z)/W) is a stored value in [0,1], and scaled coordinates are
        // (0,0,-a,1). The square/add/sqrt/reciprocal chain is exactly the frozen
        // chain: p=RN(a*a), A=RN(p+1), b=RN(1/RN(sqrt(A))), x=RN(a*b).
        // qz=-x, qw=b. Signed symmetry and exact zero additions map actual basisY
        // to upX=RN(b*(2*x)), upY=RN(1+RN((-x)*(2*x))), upZ=0.
        // The frozen full-domain theorem gives 0<=upX<=nextUp(1), not a t sample.
        // A>=1 implies 0<b<=1, so 0<=x<=1 by RN monotonicity. Hence -1<=upY<=1.
        // No FMA, alternate quaternion signs, extra components or revisions are covered.
        double cap = Math.nextUp(1.0);
        var exactCap = StoredDyadic.of(cap, same);
        if (exactCap.subtract(StoredDyadic.one(same), same)
                .compare(StoredDyadic.of(0x1p-52, same), same) != 0)
            throw new IllegalStateException("binary64 two-component cap identity differs");
        same.charge(4);
        var center = recipe.start().placement().center();
        double xProduct = cap * recipe.halfLength();
        double xNegative = center.x() - xProduct;
        double yNegative = center.y() - recipe.halfLength();
        if (!Double.isFinite(xProduct) || !Double.isFinite(xNegative) || !Double.isFinite(yNegative)) return null;
        var radius = StoredDyadic.of(recipe.start().capsule().radius(), same);
        // upX>=0 bounds the positive X endpoint by Cx. For signed upY both physical
        // endpoints are >=RN(Cy-h). These are separate RN operations, never FMA.
        var xLower = minimum(support(xNegative, radius, same), support(center.x(), radius, same), same);
        var yLower = support(yNegative, radius, same);
        var first = recipe.start().placement().storedCapsuleGeometry();
        var last = recipe.target().storedCapsuleGeometry();
        // Actual t=0/1 overrides remain in the minimum, bound by their exact stored
        // source/target certificates rather than inferred from the interior formula.
        for (var geometry : new StoredCapsuleGeometry[] {first, last}) {
            xLower = minimum(xLower, support(geometry.negativeEndpoint().x(), radius, same), same);
            xLower = minimum(xLower, support(geometry.positiveEndpoint().x(), radius, same), same);
            yLower = minimum(yLower, support(geometry.negativeEndpoint().y(), radius, same), same);
            yLower = minimum(yLower, support(geometry.positiveEndpoint().y(), radius, same), same);
        }
        return new Receipt(path, recipe, same, xLower, yLower, same.units() - before);
    }

    private static StoredDyadic support(double endpoint, StoredDyadic radius, StoredDyadic.Work work) {
        return StoredDyadic.of(endpoint, work).subtract(radius, work);
    }
    private static StoredDyadic minimum(StoredDyadic a, StoredDyadic b, StoredDyadic.Work work) {
        return a.compare(b, work) <= 0 ? a : b;
    }
}
