package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Numeric provenance for a nominal or one-axis plane realization.
 * A relation does not certify a world path, collision, support, or installation.
 */
final class RealizationRelation {
    enum Kind { NOMINAL, AXIS_PLANE, CONTAINED_CARDINAL, FIXED_HALF_LENGTH }
    private static final int VERSION = 1;

    private final double fixedHalfLength;
    private final int version;
    private final Kind kind;
    private final StoredRealizationLimits limits;
    private final Vec3d realizedCenter;
    private final Vec3d storedHalfLine;
    private final int modifiedAxis;
    private final int outwardSign;
    private final double supportCoordinate;
    private final double negativeEndpointPlane;
    private final long proofWorkDelta;
    private final int peakDyadicBits;

    private RealizationRelation(Kind kind, StoredRealizationLimits limits, Vec3d realizedCenter, Vec3d storedHalfLine,
                                int modifiedAxis, int outwardSign, double supportCoordinate,
                                double negativeEndpointPlane, long proofWorkDelta,
                                int additionalPeakBits) {
        this(kind, limits, realizedCenter, storedHalfLine, modifiedAxis, outwardSign,
                supportCoordinate, negativeEndpointPlane, proofWorkDelta, additionalPeakBits, Double.NaN);
    }

    private RealizationRelation(Kind kind, StoredRealizationLimits limits, Vec3d realizedCenter, Vec3d storedHalfLine,
            int modifiedAxis, int outwardSign, double supportCoordinate, double negativeEndpointPlane,
            long proofWorkDelta, int additionalPeakBits, double fixedHalfLength) {
        this.fixedHalfLength = fixedHalfLength;
        version = VERSION;
        this.kind = kind;
        this.limits = limits;
        this.realizedCenter = Objects.requireNonNull(realizedCenter, "realizedCenter");
        this.storedHalfLine = storedHalfLine;
        this.modifiedAxis = modifiedAxis;
        this.outwardSign = outwardSign;
        this.supportCoordinate = supportCoordinate;
        this.negativeEndpointPlane = negativeEndpointPlane;
        this.proofWorkDelta = proofWorkDelta;
        peakDyadicBits = Math.max(limits.peakDyadicBits(), additionalPeakBits);
        if (proofWorkDelta < 0) {
            throw new IllegalArgumentException("stored-capsule dyadic work cannot be negative");
        }
    }

    /** Same realization and proof; only the independent eye metadata is rebound. */
    RealizationRelation withEyeMetadata(PlayerBodyShape.Dimensions replacement) {
        return new RealizationRelation(kind, limits.withEyeMetadata(replacement), realizedCenter,
                storedHalfLine, modifiedAxis, outwardSign, supportCoordinate, negativeEndpointPlane,
                proofWorkDelta, peakDyadicBits, fixedHalfLength);
    }

    static RealizationRelation nominal(StoredRealizationLimits limits) {
        Objects.requireNonNull(limits, "limits");
        return new RealizationRelation(Kind.NOMINAL, limits, limits.centerReference(), limits.nominalHalfLine(),
                -1, 0, Double.NaN, Double.NaN, 0, limits.peakDyadicBits());
    }

    /**
     * Proves the exact numeric conditions for one axis-plane realization.
     * The caller remains responsible for authorized-path membership and every
     * obstacle check before this geometry can be accepted or installed.
     */
    static RealizationRelation axisPlane(StoredRealizationLimits limits, int axis, int outwardSign,
                                         double supportCoordinate, Vec3d candidateCenter,
                                         Vec3d candidateHalfLine, StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work");
        long workStart = work.units();
        work.charge(1);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(candidateCenter, "candidateCenter");
        Objects.requireNonNull(candidateHalfLine, "candidateHalfLine");
        if (!limits.usesWork(work)) {
            throw new IllegalArgumentException("axis-plane proof must share the limits numeric-work budget");
        }
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis must be 0, 1 or 2");
        if (outwardSign != -1 && outwardSign != 1) throw new IllegalArgumentException("outward sign must be -1 or 1");
        if (!Double.isFinite(supportCoordinate)) throw new IllegalArgumentException("support coordinate must be finite");
        StoredRealizationLimits.requirePoint(candidateCenter, "candidate center");
        for (int component = 0; component < 3; component++) {
            if (component != axis && !StoredRealizationLimits.sameRaw(
                    StoredRealizationLimits.component(candidateCenter, component),
                    StoredRealizationLimits.component(limits.centerReference(), component))) {
                throw new IllegalArgumentException("axis-plane realization changed a non-axis center component");
            }
        }
        if (limits.halfLength() == 0) {
            throw new IllegalArgumentException("sphere cannot use an axis-plane half-line correction");
        }

        StoredDyadic exactRadius = StoredDyadic.of(limits.radius(), work);
        StoredDyadic signedRadius = outwardSign > 0 ? exactRadius : exactRadius.negate(work);
        StoredDyadic exactPlane = StoredDyadic.of(supportCoordinate, work).add(signedRadius, work);
        if (!exactPlane.isExactlyRepresentable(work)) {
            throw new IllegalArgumentException("CONTACT_ENDPOINT_UNREPRESENTABLE");
        }
        double plane = exactPlane.roundNearest(work);
        StoredDyadic exactCenter = StoredDyadic.of(StoredRealizationLimits.component(candidateCenter, axis), work);
        StoredDyadic nominalAxis = StoredDyadic.of(StoredRealizationLimits.component(limits.nominalHalfLine(), axis), work);
        StoredDyadic centerAtNominalFoot = exactPlane.add(nominalAxis, work);
        StoredDyadic offset = exactCenter.subtract(centerAtNominalFoot, work);
        if (outwardSign < 0) offset = offset.negate(work);
        StoredDyadic zero = StoredDyadic.zero();
        StoredDyadic maximumOffset = limits.gap(axis).scalePow2(1, work);
        if (offset.compare(zero, work) < 0 || offset.compare(maximumOffset, work) > 0) {
            throw new IllegalArgumentException("candidate center outside one-axis realization reference band");
        }

        double expectedAxisHalfLine = exactCenter.subtract(exactPlane, work).roundNearest(work);
        for (int component = 0; component < 3; component++) {
            double actual = StoredRealizationLimits.component(candidateHalfLine, component);
            double nominal = StoredRealizationLimits.component(limits.nominalHalfLine(), component);
            if (component == axis) {
                if (!StoredRealizationLimits.sameRaw(actual, expectedAxisHalfLine)) {
                    throw new IllegalArgumentException("axis half-line is not RN(C-plane)");
                }
            } else if (!StoredRealizationLimits.sameRaw(actual, nominal)) {
                throw new IllegalArgumentException("axis-plane realization changed a second half-line component");
            }
        }
        double nominalAxisValue = StoredRealizationLimits.component(limits.nominalHalfLine(), axis);
        double actualAxisValue = StoredRealizationLimits.component(candidateHalfLine, axis);
        if (StoredRealizationLimits.sameRaw(nominalAxisValue, actualAxisValue)) {
            throw new IllegalArgumentException("axis-plane relation must change exactly one half-line component");
        }
        if (nominalAxisValue == 0.0) {
            throw new IllegalArgumentException("zero nominal axis cannot be the modified realization axis");
        }
        StoredDyadic difference = StoredDyadic.of(actualAxisValue, work)
                .subtract(StoredDyadic.of(nominalAxisValue, work), work).abs(work);
        if (difference.compare(limits.halfLineBound(axis), work) > 0) {
            throw new IllegalArgumentException("axis half-line exceeds its exact stored realization bound");
        }
        if (outwardSign * actualAxisValue < 0) {
            throw new IllegalArgumentException("support plane lies on the physical head side");
        }
        double actualNegativeEndpoint = StoredRealizationLimits.component(candidateCenter, axis) - actualAxisValue;
        if (!StoredRealizationLimits.sameRaw(actualNegativeEndpoint, plane)) {
            throw new IllegalArgumentException("physical negative endpoint does not equal support plane plus radius");
        }
        return new RealizationRelation(Kind.AXIS_PLANE, limits, candidateCenter, candidateHalfLine,
                axis, outwardSign, supportCoordinate, plane, work.units() - workStart, work.peakBits());
    }

    /** Numeric recipe only; the exact source containment certificate remains independently required. */
    static RealizationRelation containedCardinal(StoredRealizationLimits limits, Vec3d halfLine,
            int axis, StoredDyadic.Work work) {
        Objects.requireNonNull(limits); Objects.requireNonNull(halfLine); Objects.requireNonNull(work);
        long before = work.units();
        work.charge(1);
        if (!limits.usesWork(work)) throw new IllegalArgumentException("contained recipe requires the same Work");
        if (axis < 0 || axis > 2 || limits.halfLength() == 0)
            throw new IllegalArgumentException("contained correction requires a non-spherical Cardinal axis");
        for (int component = 0; component < 3; component++) {
            work.charge(1);
            double nominal = StoredRealizationLimits.component(limits.nominalHalfLine(), component);
            double actual = StoredRealizationLimits.component(halfLine, component);
            if (component != axis) {
                if (!StoredRealizationLimits.sameRaw(actual, nominal))
                    throw new IllegalArgumentException("contained recipe changed a non-axis half-line");
            } else {
                if (!Double.isFinite(actual) || nominal == 0 || actual == 0
                        || Math.copySign(1, actual) != Math.copySign(1, nominal)
                        || Math.abs(actual) > Math.abs(nominal))
                    throw new IllegalArgumentException("contained recipe changed sign, collapsed, or enlarged the half-line");
                var difference = StoredDyadic.of(actual, work).subtract(StoredDyadic.of(nominal, work), work).abs(work);
                if (difference.compare(limits.halfLineBound(axis), work) > 0)
                    throw new IllegalArgumentException("contained half-line exceeds its exact realization domain");
            }
        }
        return new RealizationRelation(Kind.CONTAINED_CARDINAL, limits, limits.centerReference(), halfLine,
                axis, 0, Double.NaN, Double.NaN, work.units() - before, work.peakBits());
    }

    /** Explicit fixed-scalar recipe; this is numeric provenance, never path permission. */
    static RealizationRelation fixedHalfLength(StoredRealizationLimits limits, double scalar,
            StoredDyadic.Work work) {
        Objects.requireNonNull(limits); Objects.requireNonNull(work);
        long before = work.units();
        work.charge(1);
        if (!limits.usesWork(work) || !Double.isFinite(scalar) || scalar <= 0 || scalar > limits.halfLength())
            throw new IllegalArgumentException("fixed half-length outside the same-work scalar profile");
        Vec3d half = limits.attitude().basisY().multiply(scalar);
        for (int axis = 0; axis < 3; axis++) {
            var difference = StoredDyadic.of(StoredRealizationLimits.component(half, axis), work)
                    .subtract(StoredDyadic.of(StoredRealizationLimits.component(limits.nominalHalfLine(), axis), work), work).abs(work);
            if (difference.compare(limits.halfLineBound(axis), work) > 0)
                throw new IllegalArgumentException("fixed half-length exceeds a component realization domain");
        }
        return new RealizationRelation(Kind.FIXED_HALF_LENGTH, limits, limits.centerReference(), half,
                -1, 0, Double.NaN, Double.NaN, work.units() - before, work.peakBits(), scalar);
    }

    double fixedHalfLength() { return fixedHalfLength; }

    Kind kind() { return kind; }
    StoredRealizationLimits limits() { return limits; }
    Vec3d realizedCenter() { return realizedCenter; }
    Vec3d nominalHalfLine() { return limits.nominalHalfLine(); }
    Vec3d storedHalfLine() { return storedHalfLine; }
    int modifiedAxis() { return modifiedAxis; }
    int outwardSign() { return outwardSign; }
    double supportCoordinate() { return supportCoordinate; }
    double negativeEndpointPlane() { return negativeEndpointPlane; }
    /** Work spent by this successful axis-plane proof invocation only. */
    long proofWorkDelta() { return proofWorkDelta; }
    int peakDyadicBits() { return peakDyadicBits; }

    boolean matchesInitialRecipe(Vec3d center, Rotation3d attitude,
                                 PlayerBodyShape.Dimensions dimensions, Vec3d halfLine, double radius) {
        return StoredRealizationLimits.sameRaw(center, realizedCenter)
                && limits.matchesOrientationAndDimensions(attitude, dimensions)
                && StoredRealizationLimits.sameRaw(radius, limits.radius())
                && StoredRealizationLimits.sameRaw(halfLine, storedHalfLine);
    }

    boolean matchesFixedRecipe(Rotation3d attitude, PlayerBodyShape.Dimensions dimensions, double radius) {
        return limits.matchesOrientationAndDimensions(attitude, dimensions)
                && StoredRealizationLimits.sameRaw(radius, limits.radius());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RealizationRelation that)) return false;
        return version == that.version
                && kind == that.kind
                && StoredRealizationLimits.sameRaw(fixedHalfLength, that.fixedHalfLength)
                && limits.sameProof(that.limits)
                && StoredRealizationLimits.sameRaw(realizedCenter, that.realizedCenter)
                && StoredRealizationLimits.sameRaw(storedHalfLine, that.storedHalfLine)
                && modifiedAxis == that.modifiedAxis
                && outwardSign == that.outwardSign
                && StoredRealizationLimits.sameRaw(supportCoordinate, that.supportCoordinate)
                && StoredRealizationLimits.sameRaw(negativeEndpointPlane, that.negativeEndpointPlane);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, kind, raw(fixedHalfLength), limits.proofHash(), raw(realizedCenter.x()), raw(realizedCenter.y()),
                raw(realizedCenter.z()), raw(storedHalfLine.x()), raw(storedHalfLine.y()),
                raw(storedHalfLine.z()), modifiedAxis, outwardSign, raw(supportCoordinate), raw(negativeEndpointPlane));
    }

    private static long raw(double value) {
        return Double.doubleToRawLongBits(value);
    }
}
