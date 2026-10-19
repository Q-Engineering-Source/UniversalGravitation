package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Literal native Z_NEG -> X_POS nominal-recipe X support, never installation authority.
 * Dominated by the frozen two-component theorem in
 * ug-cardinal-wall-formal-20261015-r1/receipt-v1/PROOF.txt,
 * SHA256 5031B64FAE952ECB503A644E667A8D212F945E3A80185D1E62454D1746A850CF.
 * Dependency and runtime reduction identities are those of CardinalTwoComponentWallLemma.
 */
final class CardinalManualNominalWallLemma {
    private CardinalManualNominalWallLemma() { }

    static final class Receipt {
        private final CapsuleArcEnclosure.Path path;
        private final CardinalRealizedArcRecipe recipe;
        private final StoredDyadic.Work work;
        private final StoredDyadic lower;
        private final long numericWork;
        private Receipt(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
                StoredDyadic.Work work, StoredDyadic lower, long numericWork) {
            this.path = path; this.recipe = recipe; this.work = work;
            this.lower = lower; this.numericWork = numericWork;
        }
        long numericWorkDelta() { return numericWork; }
        boolean proves(CapsuleArcEnclosure.Path exactPath, CardinalRealizedArcRecipe exactRecipe,
                int axis, int side, double plane, double from, double to, StoredDyadic.Work same) {
            Objects.requireNonNull(same).charge(1);
            if (path != exactPath || recipe != exactRecipe || work != same
                    || !recipe.matches(recipe.start(), recipe.target(), same)
                    || axis != 0 || side != 1 || !Double.isFinite(plane)
                    || !Double.isFinite(from) || !Double.isFinite(to) || from < 0 || from > to || to > 1)
                return false;
            return lower.compare(StoredDyadic.of(plane, same), same) >= 0;
        }
    }

    static Receipt prepare(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
            double[] selectedStart, double[] selectedTarget, StoredDyadic.Work same) {
        Objects.requireNonNull(same);
        long before = same.units();
        same.charge(32);
        if (recipe == null || path == null || !recipe.matchesWork(same)
                || !path.matchesFixedWallInputs(recipe, selectedStart, selectedTarget, same)
                || path.fixedDomainProof() != CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY
                || !(recipe.sourceContainment().source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != CardinalFrame.Z_NEG
                || !StoredRealizationLimits.sameRaw(recipe.start().placement().attitude(), CardinalFrame.Z_NEG.attitude())
                || !StoredRealizationLimits.sameRaw(recipe.target().attitude(), CardinalFrame.X_POS.attitude())) return null;
        var dimensions = recipe.start().placement().shape().dimensions();
        if (!StoredRealizationLimits.sameRaw(dimensions.width(), (double) .6F)
                || !StoredRealizationLimits.sameRaw(dimensions.height(), (double) 1.8F)
                || !StoredRealizationLimits.sameRaw(dimensions.eyeHeight(), (double) 1.62F)
                || !StoredRealizationLimits.sameRaw(recipe.halfLength(), dimensions.centerlineHalfLength())
                || !StoredRealizationLimits.sameRaw(recipe.start().placement().center(), recipe.target().center())
                || !StoredRealizationLimits.sameRaw(recipe.start().capsule().radius(), recipe.target().capsule().radius()))
            return null;
        double k = Double.longBitsToDouble(0x3fe6a09e667f3bccL);
        double[] q0 = {-k, 0, 0, k}, q1 = {0, 0, -k, k};
        for (int i = 0; i < 4; i++) {
            same.charge(2);
            if (!StoredRealizationLimits.sameRaw(selectedStart[i], q0[i])
                    || !StoredRealizationLimits.sameRaw(selectedTarget[i], q1[i])) return null;
        }
        // Actual nlerp: s=RN(1-t), A=RN(k*s), D=RN(k*t), W=RN(A+D).
        // W>=max(A,D)>0: one of t,s is >=1/2, hence one product >=RN(k/2)>0.
        // The actual ctor's max-scaled input is (-u,0,-v,1), 0<=u,v<=1;
        // zero signs follow its canonicalZero, not an alternate-q assumption.
        // With P=RN(u*u), Q=RN(v*v), its ordered sum is
        // N=RN(RN(P+Q)+1)>=N0=RN(Q+1). Monotonic sqrt/reciprocal gives
        // b=RN(1/RN(sqrt(N)))<=b0, z=RN(v*b)<=z0=RN(v*b0).
        // qy=0 eliminates the other basis-X term. Exact finite multiplication
        // by two and monotonic RN give upX=RN(b*(2*z))<=RN(b0*(2*z0))
        // <=nextUp(1), the frozen full-domain two-component theorem.
        // No t sampling, new enumeration, t monotonicity or other signed arc is used.
        double cap = Math.nextUp(1.0);
        if (StoredDyadic.of(cap, same).subtract(StoredDyadic.one(same), same)
                .compare(StoredDyadic.of(0x1p-52, same), same) != 0)
            throw new IllegalStateException("binary64 manual nominal cap identity differs");
        same.charge(3);
        double cx = recipe.start().placement().center().x();
        double product = cap * recipe.halfLength();
        double negative = cx - product;
        if (!Double.isFinite(product) || !Double.isFinite(negative)) return null;
        var radius = StoredDyadic.of(recipe.start().capsule().radius(), same);
        // Separate RN multiply/subtract; exact radius subtraction. Since upX>=0,
        // the positive interior endpoint is >=Cx. Keep both actual overrides too.
        var lower = minimum(support(negative, radius, same), support(cx, radius, same), same);
        for (var geometry : new StoredCapsuleGeometry[] {recipe.start().placement().storedCapsuleGeometry(), recipe.target().storedCapsuleGeometry()}) {
            lower = minimum(lower, support(geometry.negativeEndpoint().x(), radius, same), same);
            lower = minimum(lower, support(geometry.positiveEndpoint().x(), radius, same), same);
        }
        return new Receipt(path, recipe, same, lower, same.units() - before);
    }

    private static StoredDyadic support(double endpoint, StoredDyadic radius, StoredDyadic.Work same) {
        return StoredDyadic.of(endpoint, same).subtract(radius, same);
    }
    private static StoredDyadic minimum(StoredDyadic a, StoredDyadic b, StoredDyadic.Work same) {
        return a.compare(b, same) <= 0 ? a : b;
    }
}
