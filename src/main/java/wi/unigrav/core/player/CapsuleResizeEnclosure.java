package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

/** Fixed-raw-Q resize recipe and virtual transition containment, never owner permission. */
final class CapsuleResizeEnclosure {
    // Pose observation (<256 fixed FP operations), basis/profile/checks (<128):
    // prepaid separately from all existing pose/derive/exact-dyadic arithmetic.
    private static final int OBSERVATION_WORK = 512;
    // Dimensions, derive's fixed FP recipe, nominal relation, stored geometry,
    // Segment/Capsule validation and bounds together have <512 fixed operations.
    private static final int SAMPLE_WORK = 512;
    private static final int COVER_WORK = 256;
    private static final double COORDINATE_LIMIT = 0x1p25;

    private CapsuleResizeEnclosure() { }
    enum ProfileReason { RADIUS_OR_HALF_LENGTH, POSITIVE_HALF_LENGTH, ENDPOINT_COORDINATE }
    static final class ProfileNotCertifiedException extends IllegalArgumentException {
        private final ProfileReason reason;
        private ProfileNotCertifiedException(ProfileReason reason, String message) {
            super(message);
            this.reason = reason;
        }
        ProfileReason reason() { return reason; }
    }


    static Path prepare(CapsulePlacementMath.Pose actualStart, PlayerBodyPlacement actualTarget,
                        StoredDyadic.Work same) {
        Objects.requireNonNull(same, "same");
        long before = same.units();
        same.charge(1);
        Objects.requireNonNull(actualStart, "actualStart");
        Objects.requireNonNull(actualTarget, "actualTarget");
        PlayerBodyPlacement source = actualStart.placement();
        if (!(source.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !(actualTarget.shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("resize requires actual FreeCapsule endpoints");
        }
        if (!StoredRealizationLimits.sameRaw(source.attitude(), actualTarget.attitude())) {
            throw new IllegalArgumentException("resize requires the same raw quaternion, without normalization");
        }
        same.charge(OBSERVATION_WORK);
        var observedSource = CapsulePlacementMath.pose(source, same);
        same.charge(OBSERVATION_WORK);
        CapsulePlacementMath.pose(actualTarget, same);
        if (!sameCapsule(actualStart.capsule(), observedSource.capsule())) {
            throw new IllegalStateException("resize source observation changed actual geometry");
        }
        StoredCapsuleGeometry start = actualGeometry(source), target = actualGeometry(actualTarget);
        double[] initial = parameters(source), terminal = parameters(actualTarget);
        StoredDyadic[] values = new StoredDyadic[6], differences = new StoredDyadic[6];
        for (int i = 0; i < 6; i++) {
            values[i] = StoredDyadic.of(initial[i], same);
            differences[i] = StoredDyadic.of(terminal[i], same).subtract(values[i], same);
        }
        same.charge(SAMPLE_WORK);
        Vec3d actualUp = source.attitude().basisY();
        Path path = new Path(source.attitude(), actualUp, source.center(), actualTarget.center(),
                source.shape().geometryDimensions(), actualTarget.shape().geometryDimensions(), start, target,
                values, differences, same.identity(), 0);
        // Endpoint legality alone does not prove all intermediate RN recipes legal.
        // This check signs the finite profile of the entire recipe image, not collision.
        path.checkProfile(path.ranges(0, 1, same), same);
        return new Path(source.attitude(), actualUp, source.center(), actualTarget.center(),
                source.shape().geometryDimensions(), actualTarget.shape().geometryDimensions(), start, target,
                values, differences, same.identity(), same.units() - before);
    }

    enum SampleKind { ACTUAL_START, NOMINAL_INTERIOR, ACTUAL_TARGET }

    static final class LegalRecipeSample {
        private final SampleKind kind;
        private final Vec3d center;
        private final Rotation3d attitude;
        private final PlayerBodyShape.Dimensions dimensions;
        private final StoredCapsuleGeometry geometry;
        private final Object workIdentity;
        private final long numericWorkDelta;

        private LegalRecipeSample(SampleKind kind, Vec3d center, Rotation3d attitude,
                                  PlayerBodyShape.Dimensions dimensions, StoredCapsuleGeometry geometry,
                                  Object identity, long delta) {
            this.kind = kind;
            this.center = center;
            this.attitude = attitude;
            this.dimensions = dimensions;
            this.geometry = geometry;
            workIdentity = identity;
            numericWorkDelta = delta;
        }
        SampleKind kind() { return kind; }
        Vec3d center() { return center; }
        Rotation3d attitude() { return attitude; }
        PlayerBodyShape.Dimensions dimensions() { return dimensions; }
        StoredCapsuleGeometry geometry() { return geometry; }
        Capsule capsule() { return geometry.capsule(); }
        long numericWorkDelta() { return numericWorkDelta; }
        boolean uses(StoredDyadic.Work work) { return work != null && work.identity() == workIdentity; }
    }

    static final class Path {
        private final Rotation3d attitude;
        private final Vec3d actualUp, startCenter, targetCenter;
        private final PlayerBodyShape.Dimensions startDimensions, targetDimensions;
        private final StoredCapsuleGeometry start, target;
        private final StoredDyadic[] values, differences;
        private final Object workIdentity;
        private final long prepareWork;

        private Path(Rotation3d attitude, Vec3d actualUp, Vec3d startCenter, Vec3d targetCenter,
                     PlayerBodyShape.Dimensions startDimensions, PlayerBodyShape.Dimensions targetDimensions,
                     StoredCapsuleGeometry start, StoredCapsuleGeometry target, StoredDyadic[] values,
                     StoredDyadic[] differences, Object identity, long prepareWork) {
            this.attitude = attitude;
            this.actualUp = actualUp;
            this.startCenter = startCenter;
            this.targetCenter = targetCenter;
            this.startDimensions = startDimensions;
            this.targetDimensions = targetDimensions;
            this.start = start;
            this.target = target;
            this.values = values.clone();
            this.differences = differences.clone();
            workIdentity = identity;
            this.prepareWork = prepareWork;
        }
        long numericWorkDelta() { return prepareWork; }

        LegalRecipeSample sampleLegal(double t, StoredDyadic.Work same) {
            long before = checkWork(same);
            checkInterval(t, t);
            if (t == 0) return new LegalRecipeSample(SampleKind.ACTUAL_START, startCenter, attitude,
                    startDimensions, start, workIdentity, same.units() - before);
            if (t == 1) return new LegalRecipeSample(SampleKind.ACTUAL_TARGET, targetCenter, attitude,
                    targetDimensions, target, workIdentity, same.units() - before);
            StoredDyadic parameter = StoredDyadic.of(t, same);
            double[] sample = new double[6];
            for (int i = 0; i < 6; i++) sample[i] = affine(i, parameter, same).roundNearest(same);
            same.charge(SAMPLE_WORK);
            Vec3d center = new Vec3d(sample[0], sample[1], sample[2]);
            var dimensions = new PlayerBodyShape.Dimensions(sample[3], sample[4], sample[5]);
            // Real derive is charged to the original transaction; no placement/new Work.
            var limits = StoredRealizationLimits.derive(center, attitude, dimensions, same);
            var geometry = StoredCapsuleGeometry.fromRecipe(center, attitude, dimensions,
                    limits.nominalHalfLine(), RealizationRelation.nominal(limits));
            return new LegalRecipeSample(SampleKind.NOMINAL_INTERIOR, center, attitude, dimensions,
                    geometry, workIdentity, same.units() - before);
        }

        TransitionCover encloseClosedInterval(double a, double b, StoredDyadic.Work same) {
            long before = checkWork(same);
            checkInterval(a, b);
            same.charge(3); // midpoint's subtract/multiply/add, before any partial sample failure
            LegalRecipeSample reference = sampleLegal(a + (b - a) * .5, same);
            double epsilon = 0, maximumRadius = reference.capsule().radius();
            if (a != b) {
                Range[] ranges = ranges(a, b, same);
                Range h = checkProfile(ranges, same);
                maximumRadius = ranges[3].half(same).upper;
                Range[] negative = new Range[3], positive = new Range[3];
                for (int axis = 0; axis < 3; axis++) {
                    Range hs = h.multiply(component(actualUp, axis), same);
                    negative[axis] = ranges[axis].subtract(hs, same);
                    positive[axis] = ranges[axis].add(hs, same);
                    if (a == 0) {
                        negative[axis] = negative[axis].include(component(start.negativeEndpoint(), axis));
                        positive[axis] = positive[axis].include(component(start.positiveEndpoint(), axis));
                        maximumRadius = Math.max(maximumRadius, start.radius());
                    }
                    if (b == 1) {
                        negative[axis] = negative[axis].include(component(target.negativeEndpoint(), axis));
                        positive[axis] = positive[axis].include(component(target.positiveEndpoint(), axis));
                        maximumRadius = Math.max(maximumRadius, target.radius());
                    }
                }
                epsilon = Math.max(distanceUpper(negative, reference.geometry.negativeEndpoint(), same),
                        distanceUpper(positive, reference.geometry.positiveEndpoint(), same));
            }
            same.charge(COVER_WORK);
            double radius = epsilon == 0 ? maximumRadius : up(maximumRadius + epsilon);
            Capsule padded = new Capsule(reference.capsule().centerline(), radius);
            // Balls of epsilon at corresponding physical endpoints are convex. Their
            // paired segment convex combinations, plus radii <= maximumRadius, contain
            // every actual recipe image and the entire closed transition hull, including
            // RN jumps and non-nominal accepted endpoint transitions. This hull has no
            // Relation and is not installed geometry or permission. No sampled bridges.
            Aabb bounds = outwardBounds(reference.geometry, radius);
            return new TransitionCover(reference, padded, bounds, same.units() - before, workIdentity);
        }

        /** Sufficient numeric halfspace view over this sole recipe and its closed transition hull. */
        AxisMargin axisMarginInterval(int axis, int sign, double plane, double a, double b,
                                      StoredDyadic.Work same) {
            long before = checkWork(same);
            checkInterval(a, b);
            same.charge(64); // <=64 fixed finite/sign/axis tests, component reads and sign operations
            if (axis < 0 || axis > 2 || (sign != -1 && sign != 1) || !Double.isFinite(plane)) {
                throw new IllegalArgumentException("finite axis plane requires axis0..2 and sign+-1");
            }
            boolean exactAxis = Math.abs(component(actualUp, axis)) == 1;
            for (int i = 0; i < 3; i++) if (i != axis) exactAxis &= component(actualUp, i) == 0;
            StoredDyadic lower;
            AxisMargin.Method method;
            if (a == b) {
                var sample = sampleLegal(a, same);
                lower = geometryMargin(sample.geometry(), axis, sign, plane, same);
                method = AxisMargin.Method.ACTUAL_POINT;
            } else {
                var all = ranges(a, b, same);
                Range h = checkProfile(all, same), radius = all[3].half(same);
                Range hs = h.multiply(component(actualUp, axis), same);
                Range negative = all[axis].subtract(hs, same), positive = all[axis].add(hs, same);
                double signedMinimum = sign == 1 ? Math.min(negative.lower, positive.lower)
                        : -Math.max(negative.upper, positive.upper);
                same.charge(6);
                StoredDyadic p = signedPlane(plane, sign, same);
                var r = StoredDyadic.of(radius.upper, same);
                lower = StoredDyadic.of(signedMinimum, same).subtract(p, same).subtract(r, same);
                method = AxisMargin.Method.RECIPE_INTERVAL;
                if (exactAxis) {
                    // This is a whole-interval sufficient threshold, not a sampled fit.
                    // h>=0; RN monotonicity puts BOTH signed endpoints above L.
                    double l = p.add(r, same).roundUp(same);
                    double t = StoredDyadic.of(l, same).add(StoredDyadic.of(h.upper, same), same).roundUp(same);
                    same.charge(3);
                    double cMin = sign == 1 ? all[axis].lower : -all[axis].upper;
                    if (StoredDyadic.of(cMin, same).compare(StoredDyadic.of(t, same), same) >= 0) {
                        lower = StoredDyadic.of(l, same).subtract(p, same).subtract(r, same);
                        method = AxisMargin.Method.AXIS_RN_THRESHOLD;
                    }
                }
                if (exactAxis) {
                    var correlated = correlatedAxisMargin(axis, sign, plane, same);
                    if (correlated != null) {
                        lower = correlated;
                        method = AxisMargin.Method.AXIS_CORRELATED_RN_LATTICE;
                    }
                }
                if (exactAxis) {
                    var correlated = smallHeightAxisMargin(axis, sign, plane, same);
                    if (correlated != null) {
                        lower = correlated;
                        method = AxisMargin.Method.AXIS_SMALL_HEIGHT_RN_CELL;
                    }
                }
                // Actual nonnominal endpoint margins are paired with THEIR radii,
                // not with an unrelated interval radius extremum.
                if (a == 0) lower = minimum(lower, geometryMargin(start, axis, sign, plane, same), same);
                if (b == 1) lower = minimum(lower, geometryMargin(target, axis, sign, plane, same), same);
            }
            return new AxisMargin(lower, method, exactAxis, a == 0, b == 1,
                    workIdentity, same.units() - before);
        }

        /** Restricted fixed-binade lattice lemma; failure of a premise leaves the general proof unchanged. */
        private StoredDyadic correlatedAxisMargin(int axis, int sign, double plane, StoredDyadic.Work same) {
            same.charge(12);
            if (Double.doubleToRawLongBits(startDimensions.width())
                    != Double.doubleToRawLongBits(targetDimensions.width())) return null;
            var w = values[3];
            var r = w.scalePow2(-1, same);
            if (r.signum() <= 0 || !r.isExactlyRepresentable(same)
                    || !onIntegerGrid(w, 52, same)) return null;
            var h0 = values[4];
            var h1 = h0.add(differences[4], same);
            var one = StoredDyadic.of(1, same);
            var two = StoredDyadic.of(2, same);
            for (var h : new StoredDyadic[] { h0, h1 }) {
                var gap = h.subtract(w, same);
                if (h.compare(one, same) < 0 || h.compare(two, same) > 0
                        || gap.compare(one, same) < 0 || gap.compare(two, same) > 0) return null;
            }
            var c0 = sign == 1 ? values[axis] : values[axis].negate(same);
            var rawC1 = values[axis].add(differences[axis], same);
            var c1 = sign == 1 ? rawC1 : rawC1.negate(same);
            var f = c0.subtract(h0.scalePow2(-1, same), same);
            if (f.compare(c1.subtract(h1.scalePow2(-1, same), same), same) != 0
                    || !onIntegerGrid(f, 46, same)) return null;
            var g = StoredDyadic.of(0x1p-46, same);
            var halfG = g.scalePow2(-1, same);
            var low = StoredDyadic.of(64, same);
            var high = StoredDyadic.of(128, same);
            for (var c : new StoredDyadic[] { c0, c1 }) {
                if (c.subtract(halfG, same).compare(low, same) <= 0
                        || c.add(halfG, same).compare(high, same) >= 0) return null;
            }
            var l = f.add(r, same);
            if (!l.isExactlyRepresentable(same)) return null;
            double storedL = l.roundNearest(same);
            same.charge(4);
            if (!Double.isFinite(storedL) || (Double.doubleToRawLongBits(storedL) & 1L) != 0
                    || l.subtract(StoredDyadic.of(Math.nextDown(storedL), same), same).compare(g, same) != 0
                    || StoredDyadic.of(Math.nextUp(storedL), same).subtract(l, same).compare(g, same) != 0)
                return null;
            // Hhat-W and /2 are exact in this profile. The q-grid center-cell boundaries
            // constrain RN(center-h) to the two half-G ties around even L, both rounding to L.
            return f.subtract(signedPlane(plane, sign, same), same);
        }

        /**
         * Independent restricted RN-cell lemma; the older H and H-W in [1,2]
         * lattice branch remains unchanged. W=.5, H* in [.75,1], and exact affine
         * signed C-star minus H-star divided by 2 = 69 imply a constant actual nominal lower endpoint 69.25.
         *
         * Put G=2^-46, Chat=69+kG, Hhat=RN(H*). Center RN places H-star divided by 2 in the
         * closed cell [kG-G/2,kG+G/2]. Its doubled boundaries 2kG +/- G are
         * representable throughout this H domain. RN monotonicity therefore leaves
         * Hhat/2 in the same closed cell, retaining the center/height correlation.
         * Sterbenz makes Hhat-.5 exact and division by two is exact, so the actual
         * nominal half-length is h=Hhat/2-.25, without another gap rounding error.
         * Thus Chat-h lies in [L-G/2,L+G/2], L=69.25. L has an even significand
         * and both adjacent spacings G: both ties and the interior RN to L. The
         * other endpoint is no lower by h>=0 and RN monotonicity. Subtracting the
         * exact radius .25 yields 69. Actual nonnominal fast endpoints are NOT
         * covered by this lemma; axisMarginInterval still merges their real margins.
         */
        private StoredDyadic smallHeightAxisMargin(int axis, int sign, double plane, StoredDyadic.Work same) {
            same.charge(12); // Width/profile reads and fixed branch observations.
            if (Double.doubleToRawLongBits(startDimensions.width()) != Double.doubleToRawLongBits(.5)
                    || Double.doubleToRawLongBits(targetDimensions.width()) != Double.doubleToRawLongBits(.5))
                return null;
            var h0 = values[4];
            var h1 = h0.add(differences[4], same);
            var minHeight = StoredDyadic.of(.75, same);
            var maxHeight = StoredDyadic.of(1, same);
            for (var height : new StoredDyadic[] { h0, h1 })
                if (height.compare(minHeight, same) < 0 || height.compare(maxHeight, same) > 0) return null;
            var c0 = sign == 1 ? values[axis] : values[axis].negate(same);
            var rawC1 = values[axis].add(differences[axis], same);
            var c1 = sign == 1 ? rawC1 : rawC1.negate(same);
            var foot = StoredDyadic.of(69, same);
            if (c0.subtract(h0.scalePow2(-1, same), same).compare(foot, same) != 0
                    || c1.subtract(h1.scalePow2(-1, same), same).compare(foot, same) != 0) return null;
            var g = StoredDyadic.of(0x1p-46, same);
            var halfG = g.scalePow2(-1, same);
            var low = StoredDyadic.of(64, same);
            var high = StoredDyadic.of(128, same);
            for (var center : new StoredDyadic[] { c0, c1 })
                if (center.subtract(halfG, same).compare(low, same) <= 0
                        || center.add(halfG, same).compare(high, same) >= 0) return null;
            var l = foot.add(StoredDyadic.of(.25, same), same);
            if (!l.isExactlyRepresentable(same)) return null;
            double storedL = l.roundNearest(same);
            same.charge(4);
            if (!Double.isFinite(storedL) || (Double.doubleToRawLongBits(storedL) & 1L) != 0
                    || l.subtract(StoredDyadic.of(Math.nextDown(storedL), same), same).compare(g, same) != 0
                    || StoredDyadic.of(Math.nextUp(storedL), same).subtract(l, same).compare(g, same) != 0)
                return null;
            return foot.subtract(signedPlane(plane, sign, same), same);
        }

        private static boolean onIntegerGrid(StoredDyadic value, int inversePower, StoredDyadic.Work same) {
            var scaled = value.scalePow2(inversePower, same);
            if (!scaled.isExactlyRepresentable(same)) return false;
            double exact = scaled.roundNearest(same);
            same.charge(1);
            return Double.isFinite(exact) && exact == Math.rint(exact);
        }

        private static StoredDyadic signedPlane(double plane, int sign, StoredDyadic.Work work) {
            var value = StoredDyadic.of(plane, work);
            return sign == 1 ? value : value.negate(work);
        }
        private static StoredDyadic minimum(StoredDyadic a, StoredDyadic b, StoredDyadic.Work work) {
            return a.compare(b, work) <= 0 ? a : b;
        }
        private static StoredDyadic geometryMargin(StoredCapsuleGeometry geometry, int axis,
                int sign, double plane, StoredDyadic.Work work) {
            var p = signedPlane(plane, sign, work);
            var r = StoredDyadic.of(geometry.radius(), work);
            var negative = signedPlane(component(geometry.negativeEndpoint(), axis), sign, work);
            var positive = signedPlane(component(geometry.positiveEndpoint(), axis), sign, work);
            return minimum(negative, positive, work).subtract(p, work).subtract(r, work);
        }

        static final class AxisMargin {
            enum Method { ACTUAL_POINT, RECIPE_INTERVAL, AXIS_RN_THRESHOLD, AXIS_CORRELATED_RN_LATTICE, AXIS_SMALL_HEIGHT_RN_CELL }
            private final StoredDyadic lower;
            private final Method method;
            private final boolean exactAxis, actualStart, actualTarget;
            private final Object identity;
            private final long delta;
            private AxisMargin(StoredDyadic lower, Method method, boolean exactAxis, boolean actualStart,
                    boolean actualTarget, Object identity, long delta) {
                this.lower = lower;
                this.method = method;
                this.exactAxis = exactAxis;
                this.actualStart = actualStart;
                this.actualTarget = actualTarget;
                this.identity = identity;
                this.delta = delta;
            }
            StoredDyadic lower() { return lower; }
            Method method() { return method; }
            boolean exactAxis() { return exactAxis; }
            boolean actualStart() { return actualStart; }
            boolean actualTarget() { return actualTarget; }
            long numericWorkDelta() { return delta; }
            boolean uses(StoredDyadic.Work work) { return work != null && work.identity() == identity; }
        }

        private long checkWork(StoredDyadic.Work same) {
            Objects.requireNonNull(same, "same");
            long before = same.units();
            same.charge(1);
            if (same.identity() != workIdentity) throw new IllegalArgumentException("resize requires its prepare Work");
            return before;
        }
        private StoredDyadic affine(int index, StoredDyadic t, StoredDyadic.Work work) {
            return values[index].add(differences[index].multiply(t, work), work);
        }

        private Range[] ranges(double a, double b, StoredDyadic.Work work) {
            StoredDyadic da = StoredDyadic.of(a, work), db = StoredDyadic.of(b, work);
            Range[] result = new Range[6];
            for (int i = 0; i < 6; i++) {
                double first = affine(i, da, work).roundNearest(work);
                double last = affine(i, db, work).roundNearest(work);
                result[i] = new Range(Math.min(first, last), Math.max(first, last));
            }
            // Preserve correlation of H*-W*, which is affine. Independent Hmax-Wmin
            // can falsely exceed h's profile on a perfectly ordinary correlated growth.
            StoredDyadic gap0 = values[4].subtract(values[3], work);
            StoredDyadic gapDelta = differences[4].subtract(differences[3], work);
            StoredDyadic ga = gap0.add(gapDelta.multiply(da, work), work);
            StoredDyadic gb = gap0.add(gapDelta.multiply(db, work), work);
            StoredDyadic lo = ga.compare(gb, work) <= 0 ? ga : gb;
            StoredDyadic hi = ga.compare(gb, work) >= 0 ? ga : gb;
            StoredDyadic fieldError = roundingError(result[3].upper, work)
                    .add(roundingError(result[4].upper, work), work);
            Range coupled = new Range(Math.max(0, lo.subtract(fieldError, work).roundDown(work)),
                    hi.add(fieldError, work).roundUp(work));
            Range independent = result[4].subtract(result[3], work);
            // RN monotonicity: representable outward bounds for real H-W also bound
            // RN(H-W). Equal affine H/W profiles yield exactly equal actual fields.
            result[4] = sameHeightAndWidth() ? Range.point(0)
                    : independent.intersect(coupled).nonnegative();
            return result;
        }

        private boolean sameHeightAndWidth() {
            return values[3].equals(values[4]) && differences[3].equals(differences[4]);
        }

        /** ranges[4] is the correlated actual RN subtraction, not the height field. */
        private Range checkProfile(Range[] ranges, StoredDyadic.Work work) {
            Range radius = ranges[3].half(work), halfLength = ranges[4].half(work);
            if (!(radius.lower > 0 && radius.upper < 64 && halfLength.upper < 64)) {
                throw new ProfileNotCertifiedException(ProfileReason.RADIUS_OR_HALF_LENGTH, "RESIZE_PROFILE_NOT_CERTIFIED: radius or half-length finite domain");
            }
            // Any positive difference between doubles >=2*MIN_NORMAL is >=2*MIN_VALUE;
            // halving it cannot disappear. For smaller profiles require a positive
            // gap bound >=2*MIN_VALUE, or the exactly equal affine sphere profile.
            if (!sameHeightAndWidth() && ranges[3].lower < 0x1p-1021
                    && ranges[4].lower < 2 * Double.MIN_VALUE) {
                throw new ProfileNotCertifiedException(ProfileReason.POSITIVE_HALF_LENGTH, "RESIZE_PROFILE_NOT_CERTIFIED: internal positive half-length representability");
            }
            for (int axis = 0; axis < 3; axis++) {
                Range hs = halfLength.multiply(component(actualUp, axis), work);
                requirePointRange(ranges[axis].subtract(hs, work));
                requirePointRange(ranges[axis].add(hs, work));
            }
            return halfLength;
        }
    }

    /** No Relation, placement or accepted/owner assertion: this is only virtual collision coverage. */
    static final class TransitionCover {
        private final LegalRecipeSample reference;
        private final Capsule padded;
        private final Aabb bounds;
        private final long numericWorkDelta;
        private final Object workIdentity;
        private TransitionCover(LegalRecipeSample reference, Capsule padded, Aabb bounds, long delta, Object identity) {
            this.reference = reference;
            this.padded = padded;
            this.bounds = bounds;
            numericWorkDelta = delta;
            workIdentity = identity;
        }
        Capsule reference() { return reference.capsule(); }
        LegalRecipeSample referenceSample() { return reference; }
        Capsule padded() { return padded; }
        Aabb bounds() { return bounds; }
        long numericWorkDelta() { return numericWorkDelta; }
        boolean uses(StoredDyadic.Work work) { return work != null && work.identity() == workIdentity; }
    }

    private static StoredDyadic roundingError(double maximum, StoredDyadic.Work work) {
        work.charge(1);
        // Exact half of ulp includes the half-MIN_VALUE case, with no underflow to zero.
        return StoredDyadic.of(Math.ulp(maximum), work).scalePow2(-1, work);
    }
    private static StoredCapsuleGeometry actualGeometry(PlayerBodyPlacement placement) {
        var geometry = placement.storedCapsuleGeometry();
        if (geometry == null || !geometry.matchesRecipe(placement.center(), placement.attitude(), placement.shape().geometryDimensions())) {
            throw new IllegalStateException("resize actual stored geometry identity mismatch");
        }
        return geometry;
    }
    private static boolean sameCapsule(Capsule a, Capsule b) {
        return StoredRealizationLimits.sameRaw(a.radius(), b.radius())
                && StoredRealizationLimits.sameRaw(a.centerline().start(), b.centerline().start())
                && StoredRealizationLimits.sameRaw(a.centerline().end(), b.centerline().end());
    }
    private static double[] parameters(PlayerBodyPlacement placement) {
        var c = placement.center();
        var d = placement.shape().geometryDimensions();
        return new double[] {c.x(), c.y(), c.z(), d.width(), d.height(), d.eyeHeight()};
    }
    private static void checkInterval(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || a > b || b > 1) {
            throw new IllegalArgumentException("resize interval must lie within [0,1]");
        }
    }
    private static void requirePointRange(Range range) {
        if (Math.max(Math.abs(range.lower), Math.abs(range.upper)) >= COORDINATE_LIMIT) {
            throw new ProfileNotCertifiedException(ProfileReason.ENDPOINT_COORDINATE, "RESIZE_PROFILE_NOT_CERTIFIED: internal endpoint coordinate domain");
        }
    }
    private static double distanceUpper(Range[] endpoints, Vec3d reference, StoredDyadic.Work work) {
        double sum = 0;
        for (int axis = 0; axis < 3; axis++) {
            work.charge(5);
            double lo = down(endpoints[axis].lower - component(reference, axis));
            double hi = up(endpoints[axis].upper - component(reference, axis));
            double magnitude = Math.max(Math.abs(lo), Math.abs(hi));
            double squared = magnitude == 0 ? 0 : up(magnitude * magnitude);
            sum = sum == 0 ? squared : up(sum + squared);
        }
        work.charge(1);
        return sum == 0 ? 0 : up(Math.sqrt(sum));
    }
    private static Aabb outwardBounds(StoredCapsuleGeometry geometry, double radius) {
        double[] min = new double[3], max = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            min[axis] = down(Math.min(component(geometry.negativeEndpoint(), axis), component(geometry.positiveEndpoint(), axis)) - radius);
            max[axis] = up(Math.max(component(geometry.negativeEndpoint(), axis), component(geometry.positiveEndpoint(), axis)) + radius);
        }
        return new Aabb(new Vec3d(min[0], min[1], min[2]), new Vec3d(max[0], max[1], max[2]));
    }
    private static double component(Vec3d v, int axis) { return StoredRealizationLimits.component(v, axis); }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("resize arithmetic outside finite profile");
        return value;
    }
    private static double down(double value) { return finite(Math.nextDown(finite(value))); }
    private static double up(double value) { return finite(Math.nextUp(finite(value))); }

    /** Closed ranges of actual RN operation images; monotonicity includes every RN jump. */
    private record Range(double lower, double upper) {
        Range {
            finite(lower); finite(upper);
            if (lower > upper) throw new IllegalStateException("resize range certificate became empty");
        }
        static Range point(double value) { return new Range(value, value); }
        Range add(Range b, StoredDyadic.Work work) {
            work.charge(2);
            return new Range(lower + b.lower, upper + b.upper);
        }
        Range subtract(Range b, StoredDyadic.Work work) {
            work.charge(2);
            return new Range(lower - b.upper, upper - b.lower);
        }
        Range multiply(double actualScalar, StoredDyadic.Work work) {
            work.charge(2);
            double a = lower * actualScalar, b = upper * actualScalar;
            return new Range(Math.min(a, b), Math.max(a, b));
        }
        Range half(StoredDyadic.Work work) {
            work.charge(2);
            return new Range(lower / 2, upper / 2);
        }
        Range include(double value) { return new Range(Math.min(lower, value), Math.max(upper, value)); }
        Range nonnegative() { return new Range(Math.max(0, lower), upper); }
        Range intersect(Range b) { return new Range(Math.max(lower, b.lower), Math.min(upper, b.upper)); }
    }
}
