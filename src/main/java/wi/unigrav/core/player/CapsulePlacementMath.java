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
    private static final double GAMMA_1 = gamma(1);
    private static final double Q_MAX_SQUARED = 1 + ETA_Q;
    private static final double BETA_XZ = addUp(mulUp(mulUp(4, Q_MAX_SQUARED), gamma(3)), 6 * LAMBDA);
    private static final double BETA_Y = addUp(mulUp(addUp(1, mulUp(4, Q_MAX_SQUARED)), gamma(4)), 8 * LAMBDA);
    private static final double AXIS_NORM_UPPER = addUp(addUp(1, 2 * ETA_Q), normUpper(BETA_XZ, BETA_Y, BETA_XZ));
    private static final MathContext SQRT_LOWER = new MathContext(34, RoundingMode.FLOOR);
    private static final MathContext SQRT_UPPER = new MathContext(34, RoundingMode.CEILING);

    private CapsulePlacementMath() {
    }

    /** Certifies the capsule actually constructed by the existing placement. */
    public static Pose pose(PlayerBodyPlacement placement) {
        Objects.requireNonNull(placement, "placement");
        var dimensions = placement.shape().dimensions();
        double r = dimensions.radius();
        double h = dimensions.centerlineHalfLength();
        if (dimensions.height() > dimensions.width() && h == 0) {
            throw new IllegalArgumentException("positive centerline half-length is unrepresentable");
        }
        Interval qNorm = quaternionNorm(placement.attitude());
        Vec3d halfLine = placement.bodyUp().multiply(h);
        Vec3d negative = placement.center().subtract(halfLine);
        Vec3d positive = placement.center().add(halfLine);
        Capsule actual = placement.capsule();
        requireCapsuleDomain(actual);
        double rho = orientationRadius(placement.center(), h, r);
        double actualRho = addUp(r, Math.max(distanceUpper(negative, placement.center()),
                distanceUpper(positive, placement.center())));
        if (actualRho > rho) {
            throw new IllegalStateException("actual capsule escaped its construction envelope");
        }
        return new Pose(placement, actual, halfLine, negative, positive, qNorm, rho, actualRho);
    }

    /**
     * All accepted orientations at one center, with declared maximum stored h/r.
     * Each sample must stay within these maxima and pass pose(). General obstacle
     * approval requires a zero-delta CLEAR_SEPARATED cast of this envelope.
     * Cardinal AABB corners and target fit are separate obligations.
     */
    public static RotationEnvelope rotationEnvelope(Vec3d center, double halfLengthMaximum, double radiusMaximum) {
        Objects.requireNonNull(center, "center");
        double rho = orientationRadius(center, halfLengthMaximum, radiusMaximum);
        Capsule envelope = new Capsule(new Segment3d(center, center), rho);
        requireCapsuleDomain(envelope);
        return new RotationEnvelope(envelope, sweptBounds(envelope, ZERO));
    }

    /**
     * Prefix-independent padding for the whole requested translation. Pair-local
     * CHB eligibility (including the actual obstacle and recentered domain) must
     * still be checked by CHB; this validates only the shape/global domain.
     */
    public static Translation translationTube(Pose start, Vec3d requestedDelta) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(requestedDelta, "requestedDelta");
        requireLess(normUpper(requestedDelta.x(), requestedDelta.y(), requestedDelta.z()), 64, "delta norm");
        double mu = 0;
        if (!requestedDelta.equals(ZERO)) {
            Vec3d c = start.placement.center();
            Vec3d h = start.halfLine;
            mu = normUpper(translationError(c.x(), h.x(), requestedDelta.x()),
                    translationError(c.y(), h.y(), requestedDelta.y()),
                    translationError(c.z(), h.z(), requestedDelta.z()));
        }
        Capsule padded = new Capsule(start.capsule.centerline(), addUp(start.capsule.radius(), mu));
        requireCapsuleDomain(padded);
        requireTranslatedEndpoints(padded, requestedDelta);
        Vec3d requestedCenter = start.placement.center().add(requestedDelta);
        double reserveUpper = distanceUpper(requestedCenter, start.placement.center());
        return new Translation(start, requestedDelta, padded, sweptBounds(padded, requestedDelta), mu, reserveUpper);
    }

    /**
     * Reconstructs and checks numerical containment only. The supplied fraction
     * must come from the caller's padded casts, not unpadded contact fractions.
     * Endpoint-linear occupancy is contained by the padded CHB path through f;
     * the caller must still validate final collision, world revision and budget.
     */
    public static Pose atPrefix(Translation tube, double fraction) {
        Objects.requireNonNull(tube, "tube");
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
            throw new IllegalArgumentException("prefix must be finite and within [0,1]");
        }
        Pose start = tube.start;
        if (fraction == 0) return start;
        Vec3d deltaHat = tube.delta.multiply(fraction);
        Vec3d centerHat = start.placement.center().add(deltaHat);
        if (centerHat.equals(start.placement.center())) return start;
        Pose end = pose(new PlayerBodyPlacement(centerHat, start.placement.attitude(), start.placement.shape()));
        double residual = Math.max(endpointResidual(start.negative, end.negative, tube.delta, fraction),
                endpointResidual(start.positive, end.positive, tube.delta, fraction));
        if (residual > tube.roundingRadius && !exactResidualWithinTube(start, end, tube.delta, fraction, tube.roundingRadius)) {
            throw new IllegalStateException("reconstructed endpoint path escaped translation padding");
        }
        if (distanceUpper(centerHat, start.placement.center()) > tube.requestedDisplacementUpper) {
            throw new IllegalStateException("reconstructed center exceeded the requested displacement bound");
        }
        return end;
    }

    /** Outward world-center distance; callers reserve before any collision work. */
    public static double displacementUpper(Pose from, Pose to) {
        return distanceUpper(from.placement.center(), to.placement.center());
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
                || start.capsule.radius() != end.capsule.radius()) return false;
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

        private Pose(PlayerBodyPlacement placement, Capsule capsule, Vec3d halfLine, Vec3d negative, Vec3d positive,
                     Interval quaternionNormSquared, double orientationRadius, double actualRadius) {
            this.placement = placement;
            this.capsule = capsule;
            this.halfLine = halfLine;
            this.negative = negative;
            this.positive = positive;
            this.quaternionNormSquared = quaternionNormSquared;
            this.orientationRadius = orientationRadius;
            this.actualRadius = actualRadius;
        }

        public PlayerBodyPlacement placement() { return placement; }
        public Capsule capsule() { return capsule; }
        public double quaternionNormSquaredLower() { return quaternionNormSquared.lower; }
        public double quaternionNormSquaredUpper() { return quaternionNormSquared.upper; }
        public double orientationRadius() { return orientationRadius; }
        public double actualRadius() { return actualRadius; }
    }

    public static final class Translation {
        private final Pose start;
        private final Vec3d delta;
        private final Capsule queryCapsule;
        private final Aabb sweptBounds;
        private final double roundingRadius;
        private final double requestedDisplacementUpper;

        private Translation(Pose start, Vec3d delta, Capsule queryCapsule, Aabb sweptBounds,
                            double roundingRadius, double requestedDisplacementUpper) {
            this.start = start;
            this.delta = delta;
            this.queryCapsule = queryCapsule;
            this.sweptBounds = sweptBounds;
            this.roundingRadius = roundingRadius;
            this.requestedDisplacementUpper = requestedDisplacementUpper;
        }

        public Pose start() { return start; }
        public Vec3d delta() { return delta; }
        public Capsule queryCapsule() { return queryCapsule; }
        public Aabb sweptBounds() { return sweptBounds; }
        public double roundingRadius() { return roundingRadius; }
        public double requestedDisplacementUpper() { return requestedDisplacementUpper; }
    }

    public static final class RotationEnvelope {
        private final Capsule queryCapsule;
        private final Aabb bounds;

        private RotationEnvelope(Capsule queryCapsule, Aabb bounds) {
            this.queryCapsule = queryCapsule;
            this.bounds = bounds;
        }

        public Capsule queryCapsule() { return queryCapsule; }
        public Aabb bounds() { return bounds; }
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

    private static double orientationRadius(Vec3d center, double h, double r) {
        if (!Double.isFinite(h) || h < 0 || !Double.isFinite(r) || r <= 0) {
            throw new IllegalArgumentException("finite dimensions require h >= 0 and r > 0");
        }
        requirePointDomain(center);
        double idealHalf = mulUp(h, AXIS_NORM_UPPER);
        double halfUpper = addUp(addUp(idealHalf, mulUp(GAMMA_1, idealHalf)), mulUp(sqrtUp(3), LAMBDA));
        double endpointError = normUpper(roundingError(center.x(), halfUpper),
                roundingError(center.y(), halfUpper), roundingError(center.z(), halfUpper));
        double rho = addUp(addUp(r, halfUpper), endpointError);
        requireLess(rho, 64, "all-orientation radius");
        return rho;
    }

    private static double roundingError(double center, double offsetUpper) {
        return addUp(mulUp(GAMMA_1, addUp(Math.abs(center), offsetUpper)), LAMBDA);
    }

    private static double translationError(double center, double halfLine, double delta) {
        double m = addUp(mulUp(GAMMA_1, Math.abs(delta)), LAMBDA);
        double deltaUpper = addUp(Math.abs(delta), m);
        double c = addUp(m, roundingError(center, deltaUpper));
        double b0 = roundingError(center, Math.abs(halfLine));
        double b1 = roundingError(center, addUp(addUp(deltaUpper, c), Math.abs(halfLine)));
        return addUp(addUp(c, b0), b1);
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
     * Refine that inconclusive comparison exactly; never enlarge the allowance.
     * All inputs are bounded binary64, so the finite decimal expansions and the
     * six squared terms have bounded size. The usual successful fast path does
     * not allocate BigDecimals. No division, sqrt or decimal-string rounding.
     */
    private static boolean exactResidualWithinTube(Pose start, Pose end, Vec3d delta, double fraction, double allowance) {
        BigDecimal f = new BigDecimal(fraction);
        BigDecimal bound = new BigDecimal(allowance);
        BigDecimal boundSquared = bound.multiply(bound);
        Vec3d[] before = {start.negative, start.positive};
        Vec3d[] after = {end.negative, end.positive};
        for (int endpoint = 0; endpoint < 2; endpoint++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int axis = 0; axis < 3; axis++) {
                BigDecimal residual = new BigDecimal(component(after[endpoint], axis))
                        .subtract(new BigDecimal(component(before[endpoint], axis)))
                        .subtract(f.multiply(new BigDecimal(component(delta, axis))));
                sum = sum.add(residual.multiply(residual));
            }
            if (sum.compareTo(boundSquared) > 0) return false;
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
