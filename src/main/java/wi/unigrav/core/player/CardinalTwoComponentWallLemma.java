package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Special 9CB two-component constructor theorem, never general collision permission.
 * Frozen proof: ug-cardinal-wall-formal-20261015-r1/receipt-v1/PROOF.txt,
 * SHA256 5031b64fae952ecb503a644e667a8d212f945e3a80185d1e62454d1746a850cf.
 * It proves RN(qw*(2*qx)) <= nextUp(1) for the actual finite 9CB ctor
 * with nonnegative X<=W, Y=Z=0. Its outer analytic domain and exhaustive
 * 536870913-value inner domain overlap; this is not an arbitrary-q cache.
 * Development whole-JAR SHA256: 9CB032EF6DD69EE33C4C79EAE8708A28523880BBD25285CE81AD450988324B8F.
 * Runtime whole-JAR SHA256: EEE9F1C5323C743A1D898BE305834825E8F68E53837C4A0DFB97B93AEEABE284.
 * Runtime applicability: ug-cardinal-wall-formal-20261015-r1/runtime-release-crosscheck-r1/receipt.json,
 * SHA256 D9907F73BE67F74C60E115777528227D42FCA9A42250C3990F30A0F9A726F5C0.
 * That receipt binds matching normalized full Rotation3d/Vec3d opcode/reference streams
 * and the actual runtime JAR's exhaustive 536870913-value inner domain with no raw
 * reduction mismatch. It extends only this special two-component theorem to that
 * runtime dependency; it grants no owner, WorldServer or source-exit acceptance.
 */
final class CardinalTwoComponentWallLemma {
    private CardinalTwoComponentWallLemma() { }

    static final class Receipt {
        private final CapsuleArcEnclosure.Path path;
        private final CardinalRealizedArcRecipe recipe;
        private final CapsulePlacementMath.Pose start;
        private final PlayerBodyPlacement target;
        private final StoredDyadic.Work work;
        private final StoredDyadic lowerSupport;
        private final long numericWork;
        private Receipt(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
                CapsulePlacementMath.Pose start, PlayerBodyPlacement target, StoredDyadic.Work work,
                StoredDyadic lowerSupport, long numericWork) {
            this.path = path; this.recipe = recipe; this.start = start; this.target = target;
            this.work = work; this.lowerSupport = lowerSupport; this.numericWork = numericWork;
        }
        long numericWorkDelta() { return numericWork; }
        boolean proves(CapsuleArcEnclosure.Path exactPath, CardinalRealizedArcRecipe exactRecipe,
                int axis, int side, double plane, double from, double to, StoredDyadic.Work same) {
            Objects.requireNonNull(same).charge(1);
            if (same != work || exactPath != path || exactRecipe != recipe
                    || !recipe.matches(start, target, same)
                    || axis != 2 || side != 1 || !Double.isFinite(plane)
                    || !Double.isFinite(from) || !Double.isFinite(to) || from < 0 || from > to || to > 1)
                return false;
            return lowerSupport.compare(StoredDyadic.of(plane, same), same) >= 0;
        }
    }

    static Receipt prepare(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe,
            double[] selectedStart, double[] selectedTarget, StoredDyadic.Work same) {
        Objects.requireNonNull(same);
        long before = same.units();
        same.charge(32);
        if (recipe == null || !recipe.matchesWork(same) || path == null
                || !path.matchesFixedWallInputs(recipe, selectedStart, selectedTarget, same)) return null;
        var start = recipe.start();
        var target = recipe.target();
        if (!recipe.matches(start, target, same)
                || !(recipe.sourceContainment().source().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != CardinalFrame.Z_POS
                || !StoredRealizationLimits.sameRaw(start.placement().attitude(), CardinalFrame.Z_POS.attitude())
                || !StoredRealizationLimits.sameRaw(target.attitude(), CardinalFrame.Y_POS.attitude())
                || !CardinalLiteralWallDomain.matches(path, recipe, false, same))
            return null;
        var q0 = CardinalFrame.Z_POS.attitude();
        // These are the exact signed components consumed by Path.at, after its
        // hemisphere selection. X=RN((1-t)*q0.x) <= W=RN((1-t)*q0.w+t), W>0.
        // Thus the ctor's a=RN(X/W) is a stored value in [0,1], including endpoints.
        if (selectedStart.length != 4 || selectedTarget.length != 4
                || !StoredRealizationLimits.sameRaw(selectedStart[0], q0.x())
                || !StoredRealizationLimits.sameRaw(selectedStart[1], q0.y())
                || !StoredRealizationLimits.sameRaw(selectedStart[2], q0.z())
                || !StoredRealizationLimits.sameRaw(selectedStart[3], q0.w())
                || selectedStart[0] != selectedStart[3] || !(selectedStart[0] > 0)
                || !positiveZero(selectedTarget[0]) || !positiveZero(selectedTarget[1]) || !positiveZero(selectedTarget[2])
                || !StoredRealizationLimits.sameRaw(selectedTarget[3], 1.0))
            return null;
        if (!StoredRealizationLimits.sameRaw(start.placement().center(), target.center())
                || !StoredRealizationLimits.sameRaw(start.capsule().radius(), target.capsule().radius()))
            return null;
        // Actual RN operations are monotone. The theorem caps upZ, while upZ>=0
        // bounds the other endpoint from below by C_z. This is not a padded ball.
        same.charge(4);
        double mMax = Math.nextUp(1.0) * recipe.halfLength();
        double negativeLower = start.placement().center().z() - mMax;
        // Preserve the two actual binary64 RN operations; never fuse into FMA.
        if (!Double.isFinite(mMax) || !Double.isFinite(negativeLower)) return null;
        double positiveLower = start.placement().center().z();
        var radius = StoredDyadic.of(start.capsule().radius(), same);
        var lower = support(negativeLower, radius, same);
        lower = minimum(lower, support(positiveLower, radius, same), same);
        // Path.at(0/1) returns actual stored geometry. The root/target contained
        // certificates bind these exact proposals, and their exact supports are
        // separately included rather than inferred from an interior recipe.
        var first = start.placement().storedCapsuleGeometry();
        var last = target.storedCapsuleGeometry();
        for (double endpoint : new double[] {first.negativeEndpoint().z(), first.positiveEndpoint().z(),
                last.negativeEndpoint().z(), last.positiveEndpoint().z()})
            lower = minimum(lower, support(endpoint, radius, same), same);
        return new Receipt(path, recipe, start, target, same, lower, same.units() - before);
    }
    private static boolean positiveZero(double value) { return Double.doubleToRawLongBits(value) == 0; }
    private static StoredDyadic support(double endpoint, StoredDyadic radius, StoredDyadic.Work work) {
        return StoredDyadic.of(endpoint, work).subtract(radius, work);
    }
    private static StoredDyadic minimum(StoredDyadic a, StoredDyadic b, StoredDyadic.Work work) {
        return a.compare(b, work) <= 0 ? a : b;
    }
}
