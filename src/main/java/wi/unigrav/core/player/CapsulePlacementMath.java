package wi.unigrav.core.player;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.NormalEstimate;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.NormalSource;

/**
 * UG's binary64 construction envelopes for the frozen CHB revision-1 path.
 *
 * <p>All lengths are metres. These results certify containment, not collision,
 * support, movement permission or a world revision. The caller must cast the
 * padded shape against every candidate, retain CHB's result semantics, validate
 * the final pose and commit once. A plain final-pose query cannot replace the
 * swept tube. See the R4 derivation in docs/progress/ug-chb.
 *
 * <p>Uses the existing placement operation order and no FMA. Unsupported input
 * or non-finite enclosures throw IllegalArgumentException; a violated derived
 * enclosure throws IllegalStateException. Neither is permission to move.
 */
public final class CapsulePlacementMath {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final double U = 0x1.0p-53;
    private static final double LAMBDA = Double.MIN_VALUE;
    private static final double ETA_Q = 0x1.0p-46;
    private static final double COORDINATE_LIMIT = 0x1.0p25;
    private static final double Q_MAX_SQUARED = 1 + ETA_Q;
    private static final double BETA_XZ = addUp(mulUp(mulUp(4, Q_MAX_SQUARED), gamma(3)), 6 * LAMBDA);
    private static final double BETA_Y = addUp(mulUp(addUp(1, mulUp(4, Q_MAX_SQUARED)), gamma(4)), 8 * LAMBDA);
    private static final double AXIS_NORM_UPPER = addUp(addUp(1, 2 * ETA_Q), normUpper(BETA_XZ, BETA_Y, BETA_XZ));
    private static final MathContext SQRT_LOWER = new MathContext(34, RoundingMode.FLOOR);
    private static final MathContext SQRT_UPPER = new MathContext(34, RoundingMode.CEILING);

    private CapsulePlacementMath() {
    }

    /** Certifies the capsule actually stored by the placement using a bounded local numeric budget. */
    public static Pose pose(PlayerBodyPlacement placement) {
        return pose(placement, new StoredDyadic.Work());
    }

    /** Shared numeric-work entry for the owning movement transaction. */
    static Pose pose(PlayerBodyPlacement placement, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(numericWork, "numericWork");
        long workStart = numericWork.units();
        var dimensions = placement.shape().geometryDimensions();
        double r = dimensions.radius();
        double h = dimensions.centerlineHalfLength();
        if (dimensions.height() > dimensions.width() && h == 0) {
            throw new IllegalArgumentException("positive centerline half-length is unrepresentable");
        }
        Interval qNorm = quaternionNorm(placement.attitude());
        StoredCapsuleGeometry stored = placement.storedCapsuleGeometry();
        Vec3d halfLine = stored == null ? placement.bodyUp().multiply(h) : stored.storedHalfLine();
        Vec3d negative = stored == null ? placement.center().subtract(halfLine) : stored.negativeEndpoint();
        Vec3d positive = stored == null ? placement.center().add(halfLine) : stored.positiveEndpoint();
        Capsule actual = placement.capsule();
        requireCapsuleDomain(actual);
        double rho = orientationRadius(placement.center(), h, r, halfLine, numericWork);
        double actualRho = addUp(r, Math.max(distanceUpper(negative, placement.center()),
                distanceUpper(positive, placement.center())));
        if (actualRho > rho) {
            throw new IllegalStateException("actual capsule escaped its stored-realization envelope");
        }
        return new Pose(placement, actual, halfLine, negative, positive, qNorm, rho, actualRho,
                numericWork.units() - workStart, numericWork.peakBits());
    }

    /** New translation-validation path; the fixed recipe is prepaid without changing legacy pose callers. */
    static Pose chargedTranslationPose(PlayerBodyPlacement placement, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(numericWork, "numericWork").charge(1536);
        return pose(placement, numericWork);
    }

    /** New translation-validation path; the fixed recipe is prepaid even for a zero/raw-no-op delta. */
    static Translation chargedTranslationTube(Pose start, Vec3d delta, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(numericWork, "numericWork").charge(2048);
        return translationTube(start, delta, numericWork);
    }

    /** Reconstructs and checks the complete requested fraction under the new-mode fixed charge. */
    static Pose chargedFullTranslation(Translation tube, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(numericWork, "numericWork").charge(4096);
        return atPrefix(tube, 1.0, numericWork);
    }

    /**
     * Certifies the existing all-orientation profile over every interpolated logical center.
     * The component-wise max is a numeric monotonicity bound, not a synthetic placement.
     */
    static void requireWholeTranslationProfile(Translation tube, Pose actualEnd,
            StoredDyadic.Work numericWork) {
        Objects.requireNonNull(tube, "tube");
        Objects.requireNonNull(actualEnd, "actualEnd");
        Objects.requireNonNull(numericWork, "numericWork").charge(1024);
        Pose start = tube.start;
        Vec3d first = start.placement.center();
        Vec3d last = actualEnd.placement.center();
        Vec3d centerBound = new Vec3d(
                Math.max(Math.abs(first.x()), Math.abs(last.x())),
                Math.max(Math.abs(first.y()), Math.abs(last.y())),
                Math.max(Math.abs(first.z()), Math.abs(last.z())));
        var dimensions = start.placement.shape().geometryDimensions();
        orientationRadius(centerBound, dimensions.centerlineHalfLength(), dimensions.radius(),
                start.halfLine, numericWork, true);
    }

    /** Exact L1 variation bound for this axis-monotone rounded-center recipe. */
    static double monotoneCenterTravelUpper(Vec3d actualStart, Vec3d actualEnd,
            StoredDyadic.Work numericWork) {
        Objects.requireNonNull(actualStart, "actualStart");
        Objects.requireNonNull(actualEnd, "actualEnd");
        Objects.requireNonNull(numericWork, "numericWork").charge(64);
        StoredDyadic dx = StoredDyadic.of(actualEnd.x(), numericWork)
                .subtract(StoredDyadic.of(actualStart.x(), numericWork), numericWork).abs(numericWork);
        StoredDyadic dy = StoredDyadic.of(actualEnd.y(), numericWork)
                .subtract(StoredDyadic.of(actualStart.y(), numericWork), numericWork).abs(numericWork);
        StoredDyadic dz = StoredDyadic.of(actualEnd.z(), numericWork)
                .subtract(StoredDyadic.of(actualStart.z(), numericWork), numericWork).abs(numericWork);
        return dx.add(dy, numericWork).add(dz, numericWork).roundUp(numericWork);
    }

    private static void requireTranslationProfilePoint(Vec3d center) {
        double maximum = Math.max(Math.abs(center.x()), Math.max(Math.abs(center.y()), Math.abs(center.z())));
        if (!Double.isFinite(maximum) || maximum >= COORDINATE_LIMIT) {
            throw new TranslationProfileNotCertifiedException("translation center leaves strict coordinate profile");
        }
    }

    static final class TranslationProfileNotCertifiedException extends IllegalArgumentException {
        TranslationProfileNotCertifiedException(String message) { super(message); }
    }

    /**
     * Legacy all-orientation envelope for callers that only have declared maxima.
     * The start vector has the declared length; accepted placements should use
     * the overload below so their actual stored half-line participates.
     */
    public static RotationEnvelope rotationEnvelope(Vec3d center, double halfLengthMaximum, double radiusMaximum) {
        Objects.requireNonNull(center, "center");
        Vec3d declaredStart = new Vec3d(halfLengthMaximum, 0, 0);
        return rotationEnvelope(center, halfLengthMaximum, radiusMaximum,
                declaredStart, new StoredDyadic.Work());
    }

    /** All accepted orientations at one center, rooted in the actual stored placement. */
    public static RotationEnvelope rotationEnvelope(PlayerBodyPlacement acceptedStart,
                                                    double halfLengthMaximum, double radiusMaximum) {
        return rotationEnvelope(acceptedStart, halfLengthMaximum, radiusMaximum, new StoredDyadic.Work());
    }

    /** Shared numeric-work entry for the owning movement transaction. */
    static RotationEnvelope rotationEnvelope(PlayerBodyPlacement acceptedStart,
                                             double halfLengthMaximum, double radiusMaximum,
                                             StoredDyadic.Work numericWork) {
        Objects.requireNonNull(acceptedStart, "acceptedStart");
        Objects.requireNonNull(numericWork, "numericWork");
        if (!(acceptedStart.shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("accepted-start rotation envelope requires a FreeCapsule placement");
        }
        var dimensions = acceptedStart.shape().geometryDimensions();
        if (halfLengthMaximum < dimensions.centerlineHalfLength() || radiusMaximum < dimensions.radius()) {
            throw new IllegalArgumentException("rotation envelope maxima do not contain the accepted dimensions");
        }
        return rotationEnvelope(acceptedStart.center(), halfLengthMaximum, radiusMaximum,
                acceptedStart.storedCapsuleGeometry().storedHalfLine(), numericWork);
    }

    private static RotationEnvelope rotationEnvelope(Vec3d center, double halfLengthMaximum, double radiusMaximum,
                                                     Vec3d actualStartHalfLine,
                                                     StoredDyadic.Work numericWork) {
        long workStart = numericWork.units();
        double rho = orientationRadius(center, halfLengthMaximum, radiusMaximum, actualStartHalfLine, numericWork);
        Capsule envelope = new Capsule(new Segment3d(center, center), rho);
        requireCapsuleDomain(envelope);
        return new RotationEnvelope(envelope, sweptBounds(envelope, ZERO),
                numericWork.units() - workStart, numericWork.peakBits());
    }
    /**
     * Prefix-independent padding for the whole requested translation. Pair-local
     * CHB eligibility (including the actual obstacle and recentered domain) must
     * still be checked by CHB; this validates only the shape/global domain.
     */
    public static Translation translationTube(Pose start, Vec3d requestedDelta) {
        return translationTube(start, requestedDelta, new StoredDyadic.Work());
    }

    /** Shared numeric-work entry for the owning movement transaction. */
    static Translation translationTube(Pose start, Vec3d requestedDelta, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(requestedDelta, "requestedDelta");
        Objects.requireNonNull(numericWork, "numericWork");
        long workStart = numericWork.units();
        requireLess(normUpper(requestedDelta.x(), requestedDelta.y(), requestedDelta.z()), 64, "delta norm");
        double mu = translationRoundingRadius(start.placement.center(), start.halfLine, requestedDelta, numericWork);
        Capsule padded = new Capsule(start.capsule.centerline(), addUp(start.capsule.radius(), mu));
        requireCapsuleDomain(padded);
        requireTranslatedEndpoints(padded, requestedDelta);
        Vec3d requestedCenter = start.placement.center().add(requestedDelta);
        double reserveUpper = centerDistanceUpper(requestedCenter, start.placement.center(), numericWork);
        return new Translation(start, requestedDelta, padded, sweptBounds(padded, requestedDelta), mu, reserveUpper,
                numericWork.units() - workStart, numericWork.peakBits());
    }
    /**
     * Reconstructs and checks numerical containment only. The supplied fraction
     * must come from the caller's padded casts, not unpadded contact fractions.
     * Endpoint-linear occupancy is contained by the padded CHB path through f;
     * the caller must still validate final collision, world revision and budget.
     */
    public static Pose atPrefix(Translation tube, double fraction) {
        return atPrefix(tube, fraction, new StoredDyadic.Work());
    }

    /** Shared numeric-work entry for the owning movement transaction. */
    static Pose atPrefix(Translation tube, double fraction, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(tube, "tube");
        Objects.requireNonNull(numericWork, "numericWork");
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("prefix must be finite and within [0,1]");
        }
        Pose start = tube.start;
        if (fraction == 0) return start;
        Vec3d deltaHat = tube.delta.multiply(fraction);
        Vec3d centerHat = start.placement.center().add(deltaHat);
        if (StoredRealizationLimits.sameRaw(centerHat, start.placement.center())) return start;
        Pose end = pose(start.placement.withTranslatedCenter(centerHat, numericWork), numericWork);
        double residual = Math.max(endpointResidual(start.negative, end.negative, tube.delta, fraction),
                endpointResidual(start.positive, end.positive, tube.delta, fraction));
        if (residual > tube.roundingRadius
                && !exactResidualWithinTube(start, end, tube.delta, fraction, tube.roundingRadius, numericWork)) {
            throw new IllegalStateException("reconstructed endpoint path escaped translation padding");
        }
        if (centerDistanceUpper(centerHat, start.placement.center(), numericWork) > tube.requestedDisplacementUpper) {
            throw new IllegalStateException("reconstructed center exceeded the requested displacement bound");
        }
        return end;
    }
    /** Outward world-center distance; callers reserve before any collision work. */
    public static double displacementUpper(Pose from, Pose to) {
        return displacementUpper(from, to, new StoredDyadic.Work());
    }

    static double displacementUpper(Pose from, Pose to, StoredDyadic.Work numericWork) {
        return centerDistanceUpper(from.placement.center(), to.placement.center(), numericWork);
    }

    /** Exact stored axis travel need not spend an extra ULP of a finite movement grant. */
    private static double centerDistanceUpper(Vec3d from, Vec3d to, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(numericWork, "numericWork");
        boolean x = from.x() != to.x(), y = from.y() != to.y(), z = from.z() != to.z();
        int axes = (x ? 1 : 0) + (y ? 1 : 0) + (z ? 1 : 0);
        if (axes == 0) return 0;
        if (axes != 1) return distanceUpper(from, to);
        double a = x ? from.x() : y ? from.y() : from.z();
        double b = x ? to.x() : y ? to.y() : to.z();
        return StoredDyadic.of(a, numericWork).subtract(StoredDyadic.of(b, numericWork), numericWork)
                .abs(numericWork).roundUp(numericWork);
    }

    /**
     * R4's inherited-contact predicate for one box and one fixed-shape tube.
     * The caller must supply that box's actual initial CLEAR query/scale/normal,
     * and separately require a compatible final actual zero-cast. A direction
     * estimate alone is not a separator or a movement permission.
     * Only G at the start must lie in the band; outward detachment may leave it.
     */
    public static boolean preservesInitialContact(Translation tube, Pose end, Aabb obstacle,
                                                   NormalEstimate normal, double contactBand) {
        Objects.requireNonNull(tube, "tube");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(obstacle, "obstacle");
        Objects.requireNonNull(normal, "normal");
        if (!Double.isFinite(contactBand) || contactBand <= 0) {
            throw new IllegalArgumentException("contact band must be finite and positive");
        }
        Pose start = tube.start;
        if (normal.source() != NormalSource.CURRENT_WITNESS || normal.sourceFraction() != 0
                || !start.placement.shape().equals(end.placement.shape())
                || !start.placement.attitude().equals(end.placement.attitude())
                || start.capsule.radius() != end.capsule.radius()
                || !sameStoredHalfLineAndRelation(start.placement, end.placement)) return false;
        Vec3d n = normal.direction();
        BigDecimal normSquared = exactDot(n, n);
        BigDecimal normLower = normSquared.sqrt(SQRT_LOWER);
        BigDecimal normUpper = normSquared.sqrt(SQRT_UPPER);
        if (normLower.multiply(normLower).compareTo(normSquared) > 0
                || normUpper.multiply(normUpper).compareTo(normSquared) < 0) {
            throw new IllegalStateException("normal square root failed its exact enclosure check");
        }
        BigDecimal endpointProjection = exactDot(n, start.negative).min(exactDot(n, start.positive));
        BigDecimal support = BigDecimal.ZERO;
        for (int axis = 0; axis < 3; axis++) {
            double direction = component(n, axis);
            double coordinate = component(direction >= 0 ? obstacle.max() : obstacle.min(), axis);
            support = support.add(new BigDecimal(direction).multiply(new BigDecimal(coordinate)));
        }
        BigDecimal projectedGap = endpointProjection.subtract(support);
        BigDecimal radius = new BigDecimal(start.capsule.radius());
        BigDecimal band = new BigDecimal(contactBand);
        BigDecimal gapLower = projectedGap.subtract(radius.multiply(normUpper));
        BigDecimal gapUpper = projectedGap.subtract(radius.multiply(normLower));
        return gapLower.compareTo(band.negate()) >= 0 && gapUpper.compareTo(band) <= 0
                && exactDisplacementProjection(n, start.negative, end.negative).signum() >= 0
                && exactDisplacementProjection(n, start.positive, end.positive).signum() >= 0;
    }

    private static boolean sameStoredHalfLineAndRelation(PlayerBodyPlacement before, PlayerBodyPlacement after) {
        StoredCapsuleGeometry left = before.storedCapsuleGeometry();
        StoredCapsuleGeometry right = after.storedCapsuleGeometry();
        if (left == null || right == null) return left == right;
        return StoredRealizationLimits.sameRaw(left.storedHalfLine(), right.storedHalfLine())
                && left.relation().equals(right.relation());
    }

    private static BigDecimal exactDot(Vec3d a, Vec3d b) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int axis = 0; axis < 3; axis++) {
            sum = sum.add(new BigDecimal(component(a, axis)).multiply(new BigDecimal(component(b, axis))));
        }
        return sum;
    }

    private static BigDecimal exactDisplacementProjection(Vec3d normal, Vec3d before, Vec3d after) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int axis = 0; axis < 3; axis++) {
            BigDecimal difference = new BigDecimal(component(after, axis)).subtract(new BigDecimal(component(before, axis)));
            sum = sum.add(new BigDecimal(component(normal, axis)).multiply(difference));
        }
        return sum;
    }

    public static final class Pose {
        private final PlayerBodyPlacement placement;
        private final Capsule capsule;
        private final Vec3d halfLine;
        private final Vec3d negative;
        private final Vec3d positive;
        private final Interval quaternionNormSquared;
        private final double orientationRadius;
        private final double actualRadius;
        private final long numericWorkDelta;
        private final int peakDyadicBits;

        private Pose(PlayerBodyPlacement placement, Capsule capsule, Vec3d halfLine, Vec3d negative, Vec3d positive,
                     Interval quaternionNormSquared, double orientationRadius, double actualRadius,
                     long numericWorkDelta, int peakDyadicBits) {
            this.placement = placement;
            this.capsule = capsule;
            this.halfLine = halfLine;
            this.negative = negative;
            this.positive = positive;
            this.quaternionNormSquared = quaternionNormSquared;
            this.orientationRadius = orientationRadius;
            this.actualRadius = actualRadius;
            this.numericWorkDelta = numericWorkDelta;
            this.peakDyadicBits = peakDyadicBits;
        }

        public PlayerBodyPlacement placement() { return placement; }
        public Capsule capsule() { return capsule; }
        Vec3d storedHalfLine() { return halfLine; }
        public double quaternionNormSquaredLower() { return quaternionNormSquared.lower; }
        public double quaternionNormSquaredUpper() { return quaternionNormSquared.upper; }
        public double orientationRadius() { return orientationRadius; }
        public double actualRadius() { return actualRadius; }
        public long numericWorkDelta() { return numericWorkDelta; }
        public int peakDyadicBits() { return peakDyadicBits; }
    }
    public static final class Translation {
        private final Pose start;
        private final Vec3d delta;
        private final Capsule queryCapsule;
        private final Aabb sweptBounds;
        private final double roundingRadius;
        private final double requestedDisplacementUpper;
        private final long numericWorkDelta;
        private final int peakDyadicBits;

        private Translation(Pose start, Vec3d delta, Capsule queryCapsule, Aabb sweptBounds,
                            double roundingRadius, double requestedDisplacementUpper,
                            long numericWorkDelta, int peakDyadicBits) {
            this.start = start;
            this.delta = delta;
            this.queryCapsule = queryCapsule;
            this.sweptBounds = sweptBounds;
            this.roundingRadius = roundingRadius;
            this.requestedDisplacementUpper = requestedDisplacementUpper;
            this.numericWorkDelta = numericWorkDelta;
            this.peakDyadicBits = peakDyadicBits;
        }

        public Pose start() { return start; }
        public Vec3d delta() { return delta; }
        public Capsule queryCapsule() { return queryCapsule; }
        public Aabb sweptBounds() { return sweptBounds; }
        public double roundingRadius() { return roundingRadius; }
        public double requestedDisplacementUpper() { return requestedDisplacementUpper; }
        public long numericWorkDelta() { return numericWorkDelta; }
        public int peakDyadicBits() { return peakDyadicBits; }
    }
    public static final class RotationEnvelope {
        private final Capsule queryCapsule;
        private final Aabb bounds;
        private final long numericWorkDelta;
        private final int peakDyadicBits;

        private RotationEnvelope(Capsule queryCapsule, Aabb bounds, long numericWorkDelta, int peakDyadicBits) {
            this.queryCapsule = queryCapsule;
            this.bounds = bounds;
            this.numericWorkDelta = numericWorkDelta;
            this.peakDyadicBits = peakDyadicBits;
        }

        public Capsule queryCapsule() { return queryCapsule; }
        public Aabb bounds() { return bounds; }
        public long numericWorkDelta() { return numericWorkDelta; }
        public int peakDyadicBits() { return peakDyadicBits; }
    }
    private static Interval quaternionNorm(Rotation3d q) {
        Interval sum = new Interval(0, 0);
        for (double component : new double[]{q.x(), q.y(), q.z(), q.w()}) {
            double magnitude = Math.abs(component);
            sum = new Interval(addDown(sum.lower, Math.max(0, mulDown(magnitude, magnitude))),
                    addUp(sum.upper, mulUp(magnitude, magnitude)));
        }
        if (sum.lower < 1 - ETA_Q || sum.upper > 1 + ETA_Q) {
            throw new IllegalArgumentException("quaternion norm outside the R4 construction profile");
        }
        return sum;
    }

    private static double orientationRadius(Vec3d center, double h, double r,
                                             Vec3d actualStartHalfLine, StoredDyadic.Work work) {
        return orientationRadius(center, h, r, actualStartHalfLine, work, false);
    }

    private static double orientationRadius(Vec3d center, double h, double r,
                                             Vec3d actualStartHalfLine, StoredDyadic.Work work,
                                             boolean translationProfile) {
        if (!Double.isFinite(h) || h < 0 || !Double.isFinite(r) || r <= 0) {
            throw new IllegalArgumentException("finite dimensions require h >= 0 and r > 0");
        }
        if (translationProfile) requireTranslationProfilePoint(center);
        else requirePointDomain(center);
        double l0 = 0;
        double maxRealizationBound = 0;
        if (h != 0) {
            double factor = addUp(1, divideUp(U, addDown(1, -U)));
            l0 = addUp(mulUp(mulUp(factor, h), AXIS_NORM_UPPER), mulUp(sqrtUp(3), LAMBDA));
            StoredDyadic exactU = StoredDyadic.of(U, work);
            StoredDyadic onePlusU = StoredDyadic.one(work).add(exactU, work);
            StoredDyadic lambdaHalf = StoredDyadic.lambdaHalf(work);
            for (int axis = 0; axis < 3; axis++) {
                double wUpper = addUp(Math.abs(component(center, axis)), l0);
                StoredDyadic gap = StoredDyadic.gap64(wUpper, work);
                StoredDyadic exactBound = onePlusU.multiply(gap.scalePow2(1, work), work)
                        .add(exactU.multiply(StoredDyadic.of(l0, work), work), work)
                        .add(lambdaHalf, work);
                maxRealizationBound = Math.max(maxRealizationBound, exactBound.roundUp(work));
            }
        }
        double target = h == 0 ? 0 : addUp(l0, maxRealizationBound);
        double storedStart = normUpper(actualStartHalfLine.x(), actualStartHalfLine.y(), actualStartHalfLine.z());
        double storedHalfLineMaximum = Math.max(storedStart, target);
        double xError = endpointError(center.x(), storedHalfLineMaximum, work);
        double yError = endpointError(center.y(), storedHalfLineMaximum, work);
        double zError = endpointError(center.z(), storedHalfLineMaximum, work);
        double rho = addUp(addUp(r, storedHalfLineMaximum), normUpper(xError, yError, zError));
        if (translationProfile) {
            if (!(rho < 64)) {
                throw new TranslationProfileNotCertifiedException(
                        "translation profile radius outside strict consumer profile");
            }
        } else {
            requireLess(rho, 64, "all-orientation radius");
        }
        return rho;
    }

    private static double endpointError(double center, double halfLineMaximum, StoredDyadic.Work work) {
        StoredDyadic u = StoredDyadic.of(U, work);
        return u.multiply(StoredDyadic.of(Math.abs(center), work)
                        .add(StoredDyadic.of(halfLineMaximum, work), work), work)
                .add(StoredDyadic.lambdaHalf(work), work).roundUp(work);
    }
    private static double translationRoundingRadius(Vec3d center, Vec3d halfLine,
                                                    Vec3d delta, StoredDyadic.Work work) {
        return translationRoundingRadiusOverMagnitudes(
                new Vec3d(Math.abs(center.x()), Math.abs(center.y()), Math.abs(center.z())),
                new Vec3d(Math.abs(halfLine.x()), Math.abs(halfLine.y()), Math.abs(halfLine.z())),
                new Vec3d(Math.abs(delta.x()), Math.abs(delta.y()), Math.abs(delta.z())), work);
    }

    /** Monotone form of the identical exact error recipe, for a bounded query domain. */
    private static double translationRoundingRadiusOverMagnitudes(Vec3d centerMagnitudeBound,
            Vec3d halfLineMagnitudeBound, Vec3d deltaMagnitudeBound, StoredDyadic.Work work) {
        StoredDyadic exactU = StoredDyadic.of(U, work);
        StoredDyadic lambdaHalf = StoredDyadic.lambdaHalf(work);
        double[] mu = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double d = component(deltaMagnitudeBound, axis);
            if (d == 0.0) {
                mu[axis] = 0;
                continue;
            }
            StoredDyadic centerMagnitude = StoredDyadic.of(component(centerMagnitudeBound, axis), work);
            StoredDyadic deltaMagnitude = StoredDyadic.of(d, work);
            StoredDyadic halfLineMagnitude = StoredDyadic.of(component(halfLineMagnitudeBound, axis), work);
            StoredDyadic m = exactU.multiply(deltaMagnitude, work).add(lambdaHalf, work);
            StoredDyadic c = exactU.multiply(centerMagnitude.add(deltaMagnitude, work).add(m, work), work)
                    .add(lambdaHalf, work);
            StoredDyadic e0 = exactU.multiply(centerMagnitude.add(halfLineMagnitude, work), work)
                    .add(lambdaHalf, work);
            StoredDyadic e1 = exactU.multiply(centerMagnitude.add(deltaMagnitude, work).add(m, work)
                            .add(c, work).add(halfLineMagnitude, work), work)
                    .add(lambdaHalf, work);
            mu[axis] = m.add(c, work).add(e0, work).add(e1, work).roundUp(work);
        }
        return normUpper(mu[0], mu[1], mu[2]);
    }

    /** Covers every translation tube starting in C with |delta_i| bounded by the supplied path allowance.
     * This is query-window arithmetic, not a synthetic placement or a movement certificate. */
    static Aabb translationCaptureBoundsOverCenters(PlayerBodyPlacement profile, Vec3d centerLow,
            Vec3d centerHigh, double deltaMagnitude, StoredDyadic.Work work) {
        Objects.requireNonNull(profile); Objects.requireNonNull(centerLow); Objects.requireNonNull(centerHigh);
        Objects.requireNonNull(work).charge(256); // Three axes: bounded scalar domain/rounding/endpoint observations.
        if (!Double.isFinite(deltaMagnitude) || deltaMagnitude < 0)
            throw new IllegalArgumentException("translation capture domain requires a finite nonnegative delta bound");
        Vec3d hs = profile.storedCapsuleGeometry().storedHalfLine();
        double[] cm = new double[3], hm = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double low = component(centerLow, axis), high = component(centerHigh, axis);
            if (!Double.isFinite(low) || !Double.isFinite(high) || low > high)
                throw new IllegalArgumentException("unordered translation center domain");
            cm[axis] = Math.max(Math.abs(low), Math.abs(high));
            hm[axis] = Math.abs(component(hs, axis));
        }
        double mu = translationRoundingRadiusOverMagnitudes(new Vec3d(cm[0], cm[1], cm[2]),
                new Vec3d(hm[0], hm[1], hm[2]),
                new Vec3d(deltaMagnitude, deltaMagnitude, deltaMagnitude), work);
        double radius = addUp(profile.capsule().radius(), mu);
        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            // RN is monotone. These are the actual C +/- stored-H endpoint recipe extrema.
            double a = finite(component(centerLow, axis) - hm[axis]);
            double b = finite(component(centerHigh, axis) + hm[axis]);
            lower[axis] = addDown(Math.min(a, addDown(a, -deltaMagnitude)), -radius);
            upper[axis] = addUp(Math.max(b, addUp(b, deltaMagnitude)), radius);
            requireLess(Math.max(Math.abs(lower[axis]), Math.abs(upper[axis])), COORDINATE_LIMIT,
                    "translation capture endpoint domain");
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]), new Vec3d(upper[0], upper[1], upper[2]));
    }
    /** Signed residual domain: blocked-axis deletion and positive scaling preserve each initial sign. */
    static Aabb translationCaptureBoundsOverCenters(PlayerBodyPlacement profile, Vec3d centerLow,
            Vec3d centerHigh, Vec3d initialDelta, StoredDyadic.Work work) {
        Objects.requireNonNull(profile); Objects.requireNonNull(initialDelta);
        Objects.requireNonNull(work).charge(256);
        Vec3d hs = profile.storedCapsuleGeometry().storedHalfLine();
        double[] cm = new double[3], hm = new double[3], dm = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double low = component(centerLow, axis), high = component(centerHigh, axis);
            double d = component(initialDelta, axis);
            if (!Double.isFinite(low) || !Double.isFinite(high) || low > high || !Double.isFinite(d))
                throw new IllegalArgumentException("unordered signed translation domain");
            cm[axis] = Math.max(Math.abs(low), Math.abs(high));
            hm[axis] = Math.abs(component(hs, axis)); dm[axis] = Math.abs(d);
        }
        double mu = translationRoundingRadiusOverMagnitudes(new Vec3d(cm[0],cm[1],cm[2]),
                new Vec3d(hm[0],hm[1],hm[2]),new Vec3d(dm[0],dm[1],dm[2]),work);
        double radius = addUp(profile.capsule().radius(),mu);
        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double a = finite(component(centerLow,axis)-hm[axis]);
            double b = finite(component(centerHigh,axis)+hm[axis]);
            double d = component(initialDelta,axis);
            lower[axis] = addDown(Math.min(a,addDown(a,Math.min(0,d))),-radius);
            upper[axis] = addUp(Math.max(b,addUp(b,Math.max(0,d))),radius);
            requireLess(Math.max(Math.abs(lower[axis]),Math.abs(upper[axis])),COORDINATE_LIMIT,
                    "signed translation capture endpoint domain");
        }
        return new Aabb(new Vec3d(lower[0],lower[1],lower[2]),new Vec3d(upper[0],upper[1],upper[2]));
    }

    /** Encloses the actual proxy recipe after the resolver's finite sequence of rounded translations.
     * Each delta is RN(nextCenter - previousCenter), with both centers in the certified domain.
     * The union with the actual capsule is retained at every step, as in withTranslatedCenter. */
    /**
     * Capture-only induction for the actual fixed-Q/Hs/radius translation chain.
     * All C_i are stored values in this domain, C_0 is the actual profile center.
     * withTranslatedCenter executes d_i=RN(C_i-C_previous), RN(bound+d_i), then
     * unions the newly rounded capsule bounds. In exact offset coordinates the
     * center differences telescope: translatedOffset=oldOffset+eSub+eAdd.
     * Every union injection is covered by capsuleOffset below, including both RN
     * endpoint and RN radius operations. Induction therefore gives
     * min(initialOffset,capsuleOffsetLo)-N*(rhoSub+rhoAdd), and symmetric upper.
     *
     * A finite arithmetic bootstrap bounds ALL intermediate bound+delta operands;
     * it is used only to bound RN error, never as the native capture or as an early
     * COORDINATE_LIMIT gate. ULPs cover full-domain binades and gradual underflow,
     * not just one sampled center. Final offsets/accumulation are exact dyadics,
     * with one directed conversion back to world coordinates. No net displacement
     * substitutes for path length or collision/grant validation.
     */
    static Aabb translatedProxyCaptureBounds(PlayerBodyPlacement profile, Vec3d centerLow,
            Vec3d centerHigh, int segments, StoredDyadic.Work work) {
        if (segments < 0 || segments > CapsuleMovementResolver.MAX_SEGMENTS)
            throw new IllegalArgumentException("proxy domain exceeds the actual resolver segment ceiling");
        Objects.requireNonNull(work).charge(16);
        Objects.requireNonNull(profile); Objects.requireNonNull(centerLow); Objects.requireNonNull(centerHigh);
        if (!(profile.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("proxy translation requires its actual Free profile");
        for (int axis = 0; axis < 3; axis++) {
            work.charge(5);
            double low = component(centerLow, axis), high = component(centerHigh, axis);
            double c0 = component(profile.center(), axis);
            if (!Double.isFinite(low) || !Double.isFinite(high) || low > high || c0 < low || c0 > high)
                throw new IllegalArgumentException("proxy center domain must include the actual chain origin");
        }
        if (segments == 0) return profile.bounds();
        double[] lower = new double[3], upper = new double[3];
        Vec3d hs = profile.storedCapsuleGeometry().storedHalfLine();
        var radius = StoredDyadic.of(profile.capsule().radius(), work);
        for (int axis = 0; axis < 3; axis++) {
            work.charge(12); // Immutable component observations, min/max and finite checks.
            var low = StoredDyadic.of(component(centerLow, axis), work);
            var high = StoredDyadic.of(component(centerHigh, axis), work);
            var c0 = StoredDyadic.of(component(profile.center(), axis), work);
            var half = StoredDyadic.of(Math.abs(component(hs, axis)), work);
            var centerMagnitude = StoredDyadic.of(Math.max(Math.abs(component(centerLow, axis)),
                    Math.abs(component(centerHigh, axis))), work);
            var endpointMagnitude = centerMagnitude.add(half, work);
            var endpointError = proxyRoundingUnit(endpointMagnitude, work);
            // RN(C +/- Hs) magnitude <= directed-up exact magnitude; CHB Capsule
            // bounds then perform one RN minEndpoint-radius / maxEndpoint+radius.
            var roundedEndpointMagnitude = StoredDyadic.of(endpointMagnitude.roundUp(work), work);
            var capsuleOperandMagnitude = roundedEndpointMagnitude.add(radius, work);
            var capsuleError = proxyRoundingUnit(capsuleOperandMagnitude, work);
            var capsuleMagnitude = StoredDyadic.of(capsuleOperandMagnitude.roundUp(work), work);
            var capsuleOffset = half.add(radius, work).add(endpointError, work).add(capsuleError, work);
            var initialLow = StoredDyadic.of(component(profile.bounds().min(), axis), work).subtract(c0, work);
            var initialHigh = StoredDyadic.of(component(profile.bounds().max(), axis), work).subtract(c0, work);
            var injectedLow = capsuleOffset.negate(work);
            var offsetLow = initialLow.compare(injectedLow, work) < 0 ? initialLow : injectedLow;
            var offsetHigh = initialHigh.compare(capsuleOffset, work) > 0 ? initialHigh : capsuleOffset;

            var width = high.subtract(low, work);
            var subtractionError = proxyRoundingUnit(width, work);
            var roundedDeltaMagnitude = StoredDyadic.of(width.roundUp(work), work);
            double initialMagnitude = Math.max(Math.abs(component(profile.bounds().min(), axis)),
                    Math.abs(component(profile.bounds().max(), axis)));
            var bootstrap = StoredDyadic.of(Math.max(initialMagnitude, capsuleMagnitude.roundUp(work)), work);
            for (int segment = 0; segment < segments; segment++) {
                var operand = bootstrap.add(roundedDeltaMagnitude, work);
                var rounded = StoredDyadic.of(operand.roundUp(work), work);
                bootstrap = rounded.compare(capsuleMagnitude, work) > 0 ? rounded : capsuleMagnitude;
            }
            // One additional delta includes each addition operand, not just its RN result.
            var additionError = proxyRoundingUnit(bootstrap.add(roundedDeltaMagnitude, work), work);
            var accumulated = subtractionError.add(additionError, work)
                    .multiply(StoredDyadic.of(segments, work), work);
            lower[axis] = low.add(offsetLow, work).subtract(accumulated, work).roundDown(work);
            upper[axis] = high.add(offsetHigh, work).add(accumulated, work).roundUp(work);
            requireLess(Math.max(Math.abs(lower[axis]), Math.abs(upper[axis])), COORDINATE_LIMIT,
                    "translated proxy domain");
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]), new Vec3d(upper[0], upper[1], upper[2]));
    }

    /** Full ULP bounds RN at every magnitude up to this exact nonnegative bound.
     * nextUp covers the larger side of a binade; MIN_VALUE covers subnormal ties. */
    private static StoredDyadic proxyRoundingUnit(StoredDyadic magnitude, StoredDyadic.Work work) {
        work.charge(2);
        double bound = finite(Math.nextUp(magnitude.roundUp(work)));
        return StoredDyadic.of(finite(Math.ulp(bound)), work);
    }

    /** Zero initial components cannot move under this resolver's deletion/scaling operations. */
    static Aabb translatedProxyCaptureBounds(PlayerBodyPlacement profile, Vec3d centerLow,
            Vec3d centerHigh, Vec3d initialDelta, int segments, StoredDyadic.Work work) {
        var general = translatedProxyCaptureBounds(profile,centerLow,centerHigh,segments,work);
        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double d = component(initialDelta,axis);
            if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite signed proxy delta");
            boolean fixed = d == 0;
            if (fixed && (component(centerLow,axis) != component(profile.center(),axis)
                    || component(centerHigh,axis) != component(profile.center(),axis)))
                throw new IllegalArgumentException("zero-axis proxy domain changed its stored center");
            lower[axis] = component(fixed ? profile.bounds().min() : general.min(),axis);
            upper[axis] = component(fixed ? profile.bounds().max() : general.max(),axis);
        }
        return new Aabb(new Vec3d(lower[0],lower[1],lower[2]),new Vec3d(upper[0],upper[1],upper[2]));
    }

    private static double endpointResidual(Vec3d start, Vec3d end, Vec3d delta, double fraction) {
        return normUpper(residualAxis(start.x(), end.x(), delta.x(), fraction),
                residualAxis(start.y(), end.y(), delta.y(), fraction),
                residualAxis(start.z(), end.z(), delta.z(), fraction));
    }

    private static double residualAxis(double start, double end, double delta, double fraction) {
        Interval difference = difference(end, start);
        return Math.max(Math.abs(addDown(difference.lower, -mulUp(delta, fraction))),
                Math.abs(addUp(difference.upper, -mulDown(delta, fraction))));
    }

    /**
     * An outward residual interval may be wider than the construction allowance
     * even when the actual residual fits (notably near a binade boundary).
     * Refine that inconclusive comparison with a bounded exact dyadic square sum;
     * never enlarge the allowance. All arithmetic charges the caller-owned Work.
     */
    private static boolean exactResidualWithinTube(Pose start, Pose end, Vec3d delta,
                                                   double fraction, double allowance,
                                                   StoredDyadic.Work work) {
        StoredDyadic exactFraction = StoredDyadic.of(fraction, work);
        StoredDyadic bound = StoredDyadic.of(allowance, work);
        StoredDyadic boundSquared = bound.multiply(bound, work);
        Vec3d[] before = {start.negative, start.positive};
        Vec3d[] after = {end.negative, end.positive};
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            StoredDyadic sum = StoredDyadic.zero();
            for (int axis = 0; axis < 3; axis++) {
                StoredDyadic displacement = exactFraction.multiply(
                        StoredDyadic.of(component(delta, axis), work), work);
                StoredDyadic residual = StoredDyadic.of(component(after[endpoint], axis), work)
                        .subtract(StoredDyadic.of(component(before[endpoint], axis), work), work)
                        .subtract(displacement, work);
                sum = sum.add(residual.multiply(residual, work), work);
            }
            if (sum.compare(boundSquared, work) > 0) return false;
        }
        return true;
    }
    private static void requireCapsuleDomain(Capsule capsule) {
        requirePointDomain(capsule.centerline().start());
        requirePointDomain(capsule.centerline().end());
        requireLess(capsule.radius(), 64, "capsule radius");
        requireLess(distanceUpper(capsule.centerline().start(), capsule.centerline().end()), 128, "centerline length");
    }

    private static void requirePointDomain(Vec3d point) {
        requireLess(Math.max(Math.abs(point.x()), Math.max(Math.abs(point.y()), Math.abs(point.z()))),
                COORDINATE_LIMIT, "endpoint coordinate");
    }

    private static void requireTranslatedEndpoints(Capsule capsule, Vec3d delta) {
        for (Vec3d endpoint : new Vec3d[]{capsule.centerline().start(), capsule.centerline().end()}) {
            for (int axis = 0; axis < 3; axis++) {
                double a = component(endpoint, axis);
                double d = component(delta, axis);
                requireLess(Math.max(Math.abs(addDown(a, d)), Math.abs(addUp(a, d))),
                        COORDINATE_LIMIT, "translated endpoint coordinate");
            }
        }
    }

    private static Aabb sweptBounds(Capsule capsule, Vec3d delta) {
        Vec3d start = capsule.centerline().start();
        Vec3d end = capsule.centerline().end();
        double[] lower = new double[3];
        double[] upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double a = component(start, axis);
            double b = component(end, axis);
            double d = component(delta, axis);
            lower[axis] = addDown(Math.min(Math.min(a, b), Math.min(addDown(a, d), addDown(b, d))), -capsule.radius());
            upper[axis] = addUp(Math.max(Math.max(a, b), Math.max(addUp(a, d), addUp(b, d))), capsule.radius());
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]), new Vec3d(upper[0], upper[1], upper[2]));
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) { case 0 -> vector.x(); case 1 -> vector.y(); default -> vector.z(); };
    }

    private static double distanceUpper(Vec3d a, Vec3d b) {
        return normUpper(difference(a.x(), b.x()).magnitude(), difference(a.y(), b.y()).magnitude(),
                difference(a.z(), b.z()).magnitude());
    }

    private record Interval(double lower, double upper) {
        double magnitude() { return Math.max(Math.abs(lower), Math.abs(upper)); }
    }

    private static Interval difference(double a, double b) {
        if (a == b) return new Interval(0, 0);
        return new Interval(addDown(a, -b), addUp(a, -b));
    }

    /** Scaling avoids underflowing tiny squares and overflowing world-coordinate squares. */
    private static double normUpper(double x, double y, double z) {
        double largest = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        if (largest == 0) return 0;
        double sx = divideUp(Math.abs(x), largest);
        double sy = divideUp(Math.abs(y), largest);
        double sz = divideUp(Math.abs(z), largest);
        return mulUp(largest, sqrtUp(addUp(addUp(mulUp(sx, sx), mulUp(sy, sy)), mulUp(sz, sz))));
    }

    private static double gamma(int operations) {
        double numerator = operations * U;
        return divideUp(numerator, addDown(1, -numerator));
    }

    private static double addUp(double a, double b) {
        if (a == 0) return finite(b);
        if (b == 0) return finite(a);
        return finite(Math.nextUp(finite(a + b)));
    }

    private static double addDown(double a, double b) {
        if (a == 0) return finite(b);
        if (b == 0) return finite(a);
        return finite(Math.nextDown(finite(a + b)));
    }

    private static double mulUp(double a, double b) {
        if (a == 0 || b == 0) return 0;
        if (a == 1) return finite(b);
        if (b == 1) return finite(a);
        return finite(Math.nextUp(finite(a * b)));
    }

    private static double mulDown(double a, double b) {
        if (a == 0 || b == 0) return 0;
        if (a == 1) return finite(b);
        if (b == 1) return finite(a);
        return finite(Math.nextDown(finite(a * b)));
    }

    private static double divideUp(double a, double b) {
        if (a == 0) return 0;
        if (a == b) return 1;
        return finite(Math.nextUp(finite(a / b)));
    }

    private static double sqrtUp(double a) {
        if (a == 0 || a == 1) return a;
        return finite(Math.nextUp(Math.sqrt(a)));
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite construction enclosure");
        return value == 0 ? 0 : value;
    }

    private static void requireLess(double value, double limit, String label) {
        if (!Double.isFinite(value) || value < 0 || value >= limit) {
            throw new IllegalArgumentException(label + " outside strict consumer profile");
        }
    }
}
