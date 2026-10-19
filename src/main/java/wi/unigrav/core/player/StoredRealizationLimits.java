package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Finite one-axis stored-half-line domain derived from the current binary64
 * placement recipe. Its bounds are representation proofs, never shape or path
 * permission.
 */
final class StoredRealizationLimits {
    private static final double U = 0x1.0p-53;
    private static final double COORDINATE_LIMIT = 0x1.0p25;
    private static final double RADIUS_LIMIT = 64;
    private static final double HALF_LINE_LIMIT = 64;

    private final Vec3d centerReference;
    private final Rotation3d attitude;
    private final PlayerBodyShape.Dimensions dimensions;
    private final double radius;
    private final double halfLength;
    private final Vec3d nominalHalfLine;
    private final StoredDyadic[] gaps;
    private final StoredDyadic[] halfLineBounds;
    private final long deriveWorkDelta;
    private final int peakDyadicBits;
    private final Object workIdentity;

    private StoredRealizationLimits(Vec3d centerReference, Rotation3d attitude,
                                    PlayerBodyShape.Dimensions dimensions,
                                    double radius, double halfLength, Vec3d nominalHalfLine,
                                    StoredDyadic[] gaps, StoredDyadic[] halfLineBounds,
                                    long deriveWorkDelta, StoredDyadic.Work work) {
        this.centerReference = centerReference;
        this.attitude = attitude;
        this.dimensions = dimensions;
        this.radius = radius;
        this.halfLength = halfLength;
        this.nominalHalfLine = nominalHalfLine;
        this.gaps = gaps;
        this.halfLineBounds = halfLineBounds;
        this.deriveWorkDelta = deriveWorkDelta;
        peakDyadicBits = work.peakBits();
        workIdentity = work.identity();
    }

    /** Rebinds presentation metadata while retaining the exact original physical proof and Work. */
    private StoredRealizationLimits(StoredRealizationLimits source, PlayerBodyShape.Dimensions replacement) {
        if (!sameRaw(source.dimensions.width(), replacement.width())
                || !sameRaw(source.dimensions.height(), replacement.height()))
            throw new IllegalArgumentException("Eye metadata cannot change physical dimensions");
        centerReference = source.centerReference;
        attitude = source.attitude;
        dimensions = replacement;
        radius = source.radius;
        halfLength = source.halfLength;
        nominalHalfLine = source.nominalHalfLine;
        gaps = source.gaps;
        halfLineBounds = source.halfLineBounds;
        deriveWorkDelta = source.deriveWorkDelta;
        peakDyadicBits = source.peakDyadicBits;
        workIdentity = source.workIdentity;
    }

    StoredRealizationLimits withEyeMetadata(PlayerBodyShape.Dimensions replacement) {
        return new StoredRealizationLimits(this, replacement);
    }

    static StoredRealizationLimits derive(Vec3d center, Rotation3d attitude,
                                          PlayerBodyShape.Dimensions dimensions) {
        return derive(center, attitude, dimensions, new StoredDyadic.Work());
    }

    static StoredRealizationLimits derive(Vec3d center, Rotation3d attitude,
                                          PlayerBodyShape.Dimensions dimensions,
                                          StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work");
        long workStart = work.units();
        work.charge(1);
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(attitude, "attitude");
        Objects.requireNonNull(dimensions, "dimensions");
        double radius = dimensions.radius();
        double halfLength = dimensions.centerlineHalfLength();
        if (!(radius > 0 && radius < RADIUS_LIMIT)
                || !(halfLength >= 0 && halfLength < HALF_LINE_LIMIT)
                || (dimensions.height() > dimensions.width() && halfLength == 0)) {
            throw new IllegalArgumentException("capsule dimensions outside stored realization profile");
        }
        requirePoint(center, "center");
        Vec3d nominalHalfLine = attitude.basisY().multiply(halfLength);
        Vec3d nominalNegative = center.subtract(nominalHalfLine);
        Vec3d nominalPositive = center.add(nominalHalfLine);
        requirePoint(nominalNegative, "nominal negative endpoint");
        requirePoint(nominalPositive, "nominal positive endpoint");

        StoredDyadic[] gaps = new StoredDyadic[3];
        StoredDyadic[] bounds = new StoredDyadic[3];
        StoredDyadic u = StoredDyadic.of(U, work);
        StoredDyadic onePlusU = StoredDyadic.one(work).add(u, work);
        StoredDyadic lambdaHalf = StoredDyadic.lambdaHalf(work);
        for (int axis = 0; axis < 3; axis++) {
            double c = component(center, axis);
            double h = component(nominalHalfLine, axis);
            double n = component(nominalNegative, axis);
            double p = component(nominalPositive, axis);
            StoredDyadic gap = max(
                    StoredDyadic.gap64(c, work),
                    StoredDyadic.gap64(h, work),
                    StoredDyadic.gap64(n, work),
                    StoredDyadic.gap64(p, work), work);
            StoredDyadic twiceGap = gap.scalePow2(1, work);
            StoredDyadic coordinateTerm = u.multiply(StoredDyadic.of(Math.abs(h), work), work);
            StoredDyadic bound = onePlusU.multiply(twiceGap, work)
                    .add(coordinateTerm, work)
                    .add(lambdaHalf, work);
            gaps[axis] = gap;
            bounds[axis] = bound;
        }
        return new StoredRealizationLimits(center, attitude, dimensions, radius, halfLength,
                nominalHalfLine, gaps, bounds, work.units() - workStart, work);
    }

    Vec3d centerReference() { return centerReference; }
    Rotation3d attitude() { return attitude; }
    PlayerBodyShape.Dimensions dimensions() { return dimensions; }
    double radius() { return radius; }
    double halfLength() { return halfLength; }
    Vec3d nominalHalfLine() { return nominalHalfLine; }
    /** Work spent by this successful derive call, not the Work object cumulative total. */
    long deriveWorkDelta() { return deriveWorkDelta; }
    boolean usesWork(StoredDyadic.Work work) { return work != null && workIdentity == work.identity(); }
    int peakDyadicBits() { return peakDyadicBits; }

    StoredDyadic gap(int axis) {
        return gaps[checkAxis(axis)];
    }

    StoredDyadic halfLineBound(int axis) {
        return halfLineBounds[checkAxis(axis)];
    }

    boolean matchesRecipe(Vec3d center, Rotation3d q, PlayerBodyShape.Dimensions size) {
        return sameRaw(centerReference, center) && sameRaw(attitude, q) && sameRaw(dimensions, size);
    }

    boolean matchesOrientationAndDimensions(Rotation3d q, PlayerBodyShape.Dimensions size) {
        return sameRaw(attitude, q) && sameRaw(dimensions, size);
    }

    boolean sameProof(StoredRealizationLimits other) {
        if (other == null || !matchesRecipe(other.centerReference, other.attitude, other.dimensions)
                || !sameRaw(nominalHalfLine, other.nominalHalfLine)
                || !sameRaw(radius, other.radius) || !sameRaw(halfLength, other.halfLength)) return false;
        for (int axis = 0; axis < 3; axis++) {
            if (!gaps[axis].equals(other.gaps[axis]) || !halfLineBounds[axis].equals(other.halfLineBounds[axis])) {
                return false;
            }
        }
        return true;
    }

    int proofHash() {
        return Objects.hash(raw(centerReference.x()), raw(centerReference.y()), raw(centerReference.z()),
                raw(attitude.x()), raw(attitude.y()), raw(attitude.z()), raw(attitude.w()),
                raw(dimensions.width()), raw(dimensions.height()), raw(dimensions.eyeHeight()),
                raw(radius), raw(halfLength), raw(nominalHalfLine.x()), raw(nominalHalfLine.y()),
                raw(nominalHalfLine.z()), java.util.Arrays.hashCode(gaps),
                java.util.Arrays.hashCode(halfLineBounds));
    }

    private static long raw(double value) {
        return Double.doubleToRawLongBits(value);
    }
    static boolean sameRaw(Vec3d a, Vec3d b) {
        return a != null && b != null
                && sameRaw(a.x(), b.x()) && sameRaw(a.y(), b.y()) && sameRaw(a.z(), b.z());
    }

    static boolean sameRaw(Rotation3d a, Rotation3d b) {
        return a != null && b != null
                && sameRaw(a.x(), b.x()) && sameRaw(a.y(), b.y())
                && sameRaw(a.z(), b.z()) && sameRaw(a.w(), b.w());
    }

    static boolean sameRaw(PlayerBodyShape.Dimensions a, PlayerBodyShape.Dimensions b) {
        return a != null && b != null
                && sameRaw(a.width(), b.width()) && sameRaw(a.height(), b.height())
                && sameRaw(a.eyeHeight(), b.eyeHeight());
    }

    static boolean sameRaw(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }

    static double component(Vec3d vector, int axis) {
        return switch (checkAxis(axis)) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }

    static void requirePoint(Vec3d point, String label) {
        for (int axis = 0; axis < 3; axis++) {
            double coordinate = component(point, axis);
            if (!Double.isFinite(coordinate) || Math.abs(coordinate) >= COORDINATE_LIMIT) {
                throw new IllegalArgumentException(label + " outside stored realization coordinate profile");
            }
        }
    }

    private static StoredDyadic max(StoredDyadic a, StoredDyadic b,
                                    StoredDyadic c, StoredDyadic d, StoredDyadic.Work work) {
        StoredDyadic max = a;
        if (b.compare(max, work) > 0) max = b;
        if (c.compare(max, work) > 0) max = c;
        if (d.compare(max, work) > 0) max = d;
        return max;
    }

    private static int checkAxis(int axis) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis must be 0, 1 or 2");
        return axis;
    }
}