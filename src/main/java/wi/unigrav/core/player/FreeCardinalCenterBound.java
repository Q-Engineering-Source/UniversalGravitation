package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;

/** Signed stored-recipe bound for the desired-foot native-center correction.
 * It is neither a path grant nor a reference/source eligibility observation. */
final class FreeCardinalCenterBound {
    record Bound(double correctionUpper, Vec3d centerMin, Vec3d centerMax,
                 Vec3d nativeBoundsLower, Vec3d nativeBoundsUpper) {
        boolean containsMovementEnd(Vec3d center) {
            return center.x() >= centerMin.x() && center.x() <= centerMax.x()
                    && center.y() >= centerMin.y() && center.y() <= centerMax.y()
                    && center.z() >= centerMin.z() && center.z() <= centerMax.z();
        }
    }

    private record Interval(StoredDyadic lo, StoredDyadic hi) {
        Interval add(Interval other, StoredDyadic.Work work) {
            return new Interval(lo.add(other.lo, work), hi.add(other.hi, work));
        }
        Interval add(StoredDyadic value, StoredDyadic.Work work) {
            return new Interval(lo.add(value, work), hi.add(value, work));
        }
        Interval half(StoredDyadic.Work work) {
            return new Interval(lo.scalePow2(-1, work), hi.scalePow2(-1, work));
        }
        Interval widen(StoredDyadic error, StoredDyadic.Work work) {
            return new Interval(lo.subtract(error, work), hi.add(error, work));
        }
    }

    private FreeCardinalCenterBound() { }

    static Bound prepare(PlayerBodyPlacement start, CardinalFrame frame,
            PlayerBodyShape.Dimensions target, double movementAllowance, StoredDyadic.Work work) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(work, "work");
        if (!(start.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !Double.isFinite(movementAllowance) || movementAllowance < 0)
            throw new IllegalArgumentException("native-center bound requires an actual Free movement profile");
        var radius = StoredDyadic.of(start.storedCapsuleGeometry().radius(), work);
        var targetRadius = StoredDyadic.of(target.radius(), work);
        var height = StoredDyadic.of(target.height(), work);
        var allowance = StoredDyadic.of(movementAllowance, work);
        boolean prone = ((PlayerBodyShape.FreeCapsule) start.shape()).pronePose().isPresent();
        double[] min = new double[3], max = new double[3];
        double[] nativeLow = new double[3], nativeHigh = new double[3];
        StoredDyadic total = StoredDyadic.zero();
        for (int axis = 0; axis < 3; axis++) {
            var center = StoredDyadic.of(component(start.center(), axis), work);
            var centers = new Interval(center.subtract(allowance, work), center.add(allowance, work));
            min[axis] = centers.lo.roundDown(work);
            max[axis] = centers.hi.roundUp(work);
            // negativeEndpoint is binary64 center - *stored* half-line, not nominal Q*h.
            var halfLine = StoredDyadic.of(component(start.storedCapsuleGeometry().storedHalfLine(), axis), work);
            double normal = component(frame.up(), axis);
            // Prone standing uses the belly support plane: tangential coordinates stay at
            // the centre, and the normal anchor uses the lower actual endpoint plus radius.
            // Match PlayerBodyPlacement.proneSupportFoot, including endpoint rounding.
            var endpointOffset = prone
                    ? normal == 0 ? StoredDyadic.zero()
                        : halfLine.abs(work).multiply(StoredDyadic.of(-normal, work), work)
                    : halfLine.negate(work);
            var negativeExact = centers.add(endpointOffset, work);
            var negativeError = roundingGap(negativeExact, work);
            var negative = negativeExact.widen(negativeError, work);
            var upRadius = StoredDyadic.of(prone ? normal : component(start.bodyUp(), axis), work).multiply(radius, work);
            var footExact = negative.add(upRadius.negate(work), work);
            var footError = roundingGap(footExact, work);
            var feet = footExact.widen(footError, work);
            var s = new Interval(endpointOffset.subtract(upRadius, work),
                    endpointOffset.subtract(upRadius, work))
                    .widen(negativeError.add(footError, work), work);

            Interval lowError, highError;
            StoredDyadic idealOffset;
            Interval low, high;
            if (normal > 0) {
                lowError = zero();
                highError = outward(feet.add(height, work), true, work);
                low = feet;
                high = feet.add(height, work).add(highError, work);
                idealOffset = height.scalePow2(-1, work);
            } else if (normal < 0) {
                lowError = outward(feet.add(height.negate(work), work), false, work);
                highError = zero();
                low = feet.add(height.negate(work), work).add(lowError, work);
                high = feet;
                idealOffset = height.scalePow2(-1, work).negate(work);
            } else {
                var exactLow = feet.add(targetRadius.negate(work), work);
                var exactHigh = feet.add(targetRadius, work);
                // preserveNativeTangentCenter can select any of four candidates: each
                // directed bound and at most one outward adjacent binary64 value.
                var lowGap = roundingGap(exactLow, work);
                var highGap = roundingGap(exactHigh, work);
                var roundedLow = exactLow.add(new Interval(lowGap.negate(work), StoredDyadic.zero()), work);
                var roundedHigh = exactHigh.add(new Interval(StoredDyadic.zero(), highGap), work);
                lowError = new Interval(lowGap.add(roundingGap(roundedLow, work), work).negate(work), StoredDyadic.zero());
                highError = new Interval(StoredDyadic.zero(), highGap.add(roundingGap(roundedHigh, work), work));
                low = exactLow.add(lowError, work);
                high = exactHigh.add(highError, work);
                idealOffset = StoredDyadic.zero();
            }
            nativeLow[axis] = low.lo.roundDown(work);
            nativeHigh[axis] = high.hi.roundUp(work);
            // Exact dependency-JAR Aabb.midpoint: RN(low+high)/2 (finite sum).
            // Refuse overflow here rather than claiming a bound for its alternate recipe.
            var sum = low.add(high, work);
            var sumError = roundingGap(sum, work);
            var roundedSum = sum.widen(sumError, work);
            var divideError = roundingGap(roundedSum.half(work), work);
            var midpointError = sumError.scalePow2(-1, work).add(divideError, work);
            var t = lowError.add(highError, work).half(work).add(idealOffset, work)
                    .widen(midpointError, work);
            // Retain signed S+T cancellation; do not sum absolute body dimensions.
            var correction = s.add(t, work);
            var lower = correction.lo.abs(work);
            var upper = correction.hi.abs(work);
            total = total.add(lower.compare(upper, work) >= 0 ? lower : upper, work);
        }
        return new Bound(total.roundUp(work), new Vec3d(min[0], min[1], min[2]),
                new Vec3d(max[0], max[1], max[2]), new Vec3d(nativeLow[0], nativeLow[1], nativeLow[2]),
                new Vec3d(nativeHigh[0], nativeHigh[1], nativeHigh[2]));
    }

    private static Interval zero() {
        return new Interval(StoredDyadic.zero(), StoredDyadic.zero());
    }

    private static Interval outward(Interval exact, boolean upward, StoredDyadic.Work work) {
        var gap = roundingGap(exact, work);
        return upward ? new Interval(StoredDyadic.zero(), gap)
                : new Interval(gap.negate(work), StoredDyadic.zero());
    }

    /** A full ULP bounds nearest/directed rounding over the complete operand interval,
     * including binade boundaries and subnormals. All interval algebra shares owner work. */
    private static StoredDyadic roundingGap(Interval interval, StoredDyadic.Work work) {
        var low = interval.lo.abs(work);
        var high = interval.hi.abs(work);
        double magnitude = (low.compare(high, work) >= 0 ? low : high).roundUp(work);
        if (!Double.isFinite(magnitude) || !Double.isFinite(Math.nextUp(magnitude)))
            throw new IllegalArgumentException("native-center interval exceeds the finite midpoint profile");
        return StoredDyadic.of(Math.ulp(magnitude), work);
    }

    private static double component(Vec3d value, int axis) {
        return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
    }
}

