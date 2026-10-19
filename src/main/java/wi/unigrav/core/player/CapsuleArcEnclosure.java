package wi.unigrav.core.player;

import java.util.Objects;

import wi.unigrav.core.player.CapsulePlacementMath.Pose;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;

/** Actual binary64 short-arc recipe containment; never collision or movement permission. */
final class CapsuleArcEnclosure {
    private static final double NORM_ERROR = 0x1p-46;
    // Fixed observations include finite checks, quaternion norm, stored half-line/endpoints,
    // capsule/bounds and pose radius checks. Fewer than 256 fixed floating operations each.
    private static final int OBSERVATION_WORK = 256;
    // Four blend components (16), Rotation3d normalization/validation (<64), basisY
    // (<40), Hs/endpoints (9), Segment/Capsule checks/bounds (<128): below 512.
    private static final int REFERENCE_WORK = 512;
    // Capsule and outward Aabb fixed constructors/checks cost fewer than 256 operations.
    private static final int ENCLOSURE_WORK = 256;

    private CapsuleArcEnclosure() { }

    static Path prepare(CapsulePlacementMath.Pose actualStart, PlayerBodyPlacement actualTarget,
                        Vec3d preferredAxis, StoredDyadic.Work same) {
        return prepare(actualStart, actualTarget, preferredAxis, same, null);
    }

    static Path prepareFixed(CardinalRealizedArcRecipe recipe, Vec3d preferredAxis, StoredDyadic.Work same) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(same, "same");
        long before = same.units();
        same.charge(1);
        if (!recipe.matchesWork(same)) throw new IllegalArgumentException("fixed arc requires its source Work");
        Path path = prepare(recipe.start(), recipe.target(), preferredAxis, same, recipe);
        path.proveFixedDomain(same);
        path.specialWall = CardinalTwoComponentWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        path.yToXWall = CardinalYToXWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        path.xToYWall = CardinalXToYWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        path.zNegativeToYWall = CardinalZNegativeToYWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        path.signedAxisWall = CardinalSignedAxisWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        path.manualNominalWall = CardinalManualNominalWallLemma.prepare(path, recipe, path.q0, path.q1, same);
        // The fixed recipe certificate is part of this preparation, including failed
        // interval attempts charged before the successful proof. Publish the full delta.
        path.prepareWork = same.units() - before;
        return path;
    }

    private static Path prepare(Pose actualStart, PlayerBodyPlacement actualTarget,
            Vec3d preferredAxis, StoredDyadic.Work same, CardinalRealizedArcRecipe recipe) {
        Objects.requireNonNull(same, "same");
        long before = same.units();
        same.charge(1);
        Objects.requireNonNull(actualStart, "actualStart");
        Objects.requireNonNull(actualTarget, "actualTarget");
        Objects.requireNonNull(preferredAxis, "preferredAxis");
        var start = actualStart.placement();
        if (!(start.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !(actualTarget.shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("arc requires two actual FreeCapsule placements");
        }
        if (recipe == null && (start.storedCapsuleGeometry().relation().kind() == RealizationRelation.Kind.FIXED_HALF_LENGTH
                || actualTarget.storedCapsuleGeometry().relation().kind() == RealizationRelation.Kind.FIXED_HALF_LENGTH))
            throw new IllegalArgumentException("fixed-half-length endpoints require their explicit arc recipe");
        var dimensions = start.shape().geometryDimensions();
        if (!start.shape().equals(actualTarget.shape())
                || !StoredRealizationLimits.sameRaw(start.center(), actualTarget.center())
                || !StoredRealizationLimits.sameRaw(dimensions, actualTarget.shape().geometryDimensions())
                || !StoredRealizationLimits.sameRaw(start.capsule().radius(), actualTarget.capsule().radius())) {
            throw new IllegalArgumentException("arc requires the same raw center, dimensions and radius");
        }
        double radius = dimensions.radius(), h = recipe == null ? dimensions.centerlineHalfLength() : recipe.halfLength();
        if (!(radius > 0 && radius < 64 && h >= 0 && h < 64)
                || dimensions.height() > dimensions.width() && h == 0) {
            throw new IllegalArgumentException("arc dimensions outside finite consumer profile");
        }
        // Pose has no historical Work identity. Only these fresh observations are signed.
        same.charge(OBSERVATION_WORK);
        var observedStart = CapsulePlacementMath.pose(start, same);
        same.charge(OBSERVATION_WORK);
        var observedTarget = CapsulePlacementMath.pose(actualTarget, same);
        if (!sameCapsule(actualStart.capsule(), observedStart.capsule())) {
            throw new IllegalStateException("arc start observation changed actual capsule");
        }
        requireNorm(observedStart);
        requireNorm(observedTarget);
        StoredRealizationLimits.requirePoint(start.center(), "arc center");
        var first = geometry(start);
        var last = geometry(actualTarget);
        StoredRealizationLimits.requirePoint(first.negative, "arc start negative endpoint");
        StoredRealizationLimits.requirePoint(first.positive, "arc start positive endpoint");
        StoredRealizationLimits.requirePoint(last.negative, "arc target negative endpoint");
        StoredRealizationLimits.requirePoint(last.positive, "arc target positive endpoint");
        double[] q0 = components(start.attitude()), q1 = components(actualTarget.attitude());
        StoredDyadic[] d0 = dyadics(q0, same), d1 = dyadics(q1, same);
        int direction = dot(d0, d1, same).signum();
        if (direction == 0) {
            StoredDyadic[] relative = relativeVector(d0, d1, same);
            direction = dot(relative, dyadics(new double[] {
                    preferredAxis.x(), preferredAxis.y(), preferredAxis.z()}, same), same).signum();
            if (direction == 0) {
                for (StoredDyadic component : relative) {
                    if (component.signum() != 0) { direction = component.signum(); break; }
                }
                if (direction == 0) throw new IllegalStateException("orthogonal unit quaternions lost relative axis");
            }
        }
        if (direction < 0) {
            for (int i = 0; i < 4; i++) {
                q1[i] = -q1[i];
                d1[i] = d1[i].negate(same);
            }
        }
        // Exact invariants require actual endpoint correspondence, including any AxisPlane Hs.
        same.charge(REFERENCE_WORK);
        Vec3d nominal0 = start.attitude().basisY().multiply(h);
        Vec3d nominal1 = actualTarget.attitude().basisY().multiply(h);
        boolean sphere = dimensions.height() == dimensions.width() && h == 0
                && zero(first.halfLine) && zero(last.halfLine)
                && StoredRealizationLimits.sameRaw(first.negative, start.center())
                && StoredRealizationLimits.sameRaw(first.positive, start.center())
                && StoredRealizationLimits.sameRaw(last.negative, start.center())
                && StoredRealizationLimits.sameRaw(last.positive, start.center());
        Vec3d vertical = new Vec3d(0, h, 0);
        boolean pureY = q0[0] == 0 && q0[2] == 0 && q1[0] == 0 && q1[2] == 0
                && StoredRealizationLimits.sameRaw(nominal0, vertical)
                && StoredRealizationLimits.sameRaw(nominal1, vertical)
                && StoredRealizationLimits.sameRaw(first.halfLine, vertical)
                && StoredRealizationLimits.sameRaw(last.halfLine, vertical)
                && StoredRealizationLimits.sameRaw(first.negative, last.negative)
                && StoredRealizationLimits.sameRaw(first.positive, last.positive);
        return new Path(start.center(), radius, h, q0, q1, d0, d1, actualStart, actualTarget,
                first, last, sphere || pureY,
                bracketsCenter(start.center(), first.negative, first.positive, same)
                        & bracketsCenter(start.center(), last.negative, last.positive, same),
                same.identity(), same.units() - before, recipe);
    }

    /** Eligibility only: an override that does not bracket C keeps the original proof. */
    static boolean bracketsCenter(Vec3d center, Vec3d negative, Vec3d positive, StoredDyadic.Work same) {
        same.charge(12);
        boolean brackets = true;
        for (int axis = 0; axis < 3; axis++) {
            double c = component(center, axis), n = component(negative, axis), p = component(positive, axis);
            brackets &= Double.isFinite(c) && Double.isFinite(n) && Double.isFinite(p)
                    && Math.min(n, p) <= c && c <= Math.max(n, p);
        }
        return brackets;
    }
    static final class Path {
        private final CardinalRealizedArcRecipe recipe;
        private CardinalTwoComponentWallLemma.Receipt specialWall;
        private CardinalYToXWallLemma.Receipt yToXWall;
        private CardinalXToYWallLemma.Receipt xToYWall;
        private CardinalZNegativeToYWallLemma.Receipt zNegativeToYWall;
        private CardinalSignedAxisWallLemma.Receipt signedAxisWall;
        private CardinalManualNominalWallLemma.Receipt manualNominalWall;
        private final Vec3d center;
        private final double radius, halfLength;
        private final double[] q0, q1;
        private final StoredDyadic[] exactQ0, exactQ1;
        private final Pose actualStart;
        private final PlayerBodyPlacement actualTarget;
        private final Geometry start, target;
        private final boolean invariant;
        private final boolean fixedCenterBracket;
        private final Object workIdentity;
        // Set once more by the outer factory before a fixed Path can escape.
        private long prepareWork;

        private Path(Vec3d center, double radius, double halfLength, double[] q0, double[] q1,
                     StoredDyadic[] exactQ0, StoredDyadic[] exactQ1,
                     Pose actualStart, PlayerBodyPlacement actualTarget, Geometry start, Geometry target,
                     boolean invariant, boolean fixedCenterBracket, Object identity, long prepareWork, CardinalRealizedArcRecipe recipe) {
            this.recipe = recipe;
            this.center = center;
            this.radius = radius;
            this.halfLength = halfLength;
            this.q0 = q0.clone();
            this.q1 = q1.clone();
            this.exactQ0 = exactQ0.clone();
            this.exactQ1 = exactQ1.clone();
            this.actualStart = actualStart;
            this.actualTarget = actualTarget;
            this.start = start;
            this.target = target;
            this.invariant = invariant;
            this.fixedCenterBracket = fixedCenterBracket;
            workIdentity = identity;
            this.prepareWork = prepareWork;
        }

        long numericWorkDelta() { return prepareWork; }

        boolean preparedFor(Pose exactStart, PlayerBodyPlacement exactTarget, StoredDyadic.Work same) {
            Objects.requireNonNull(same, "same").charge(1);
            return same.identity() == workIdentity && actualStart == exactStart && actualTarget == exactTarget
                    && (recipe == null || recipe.matches(actualStart, actualTarget, same));
        }

        /** Mint eligibility uses the path's stored recipe and actual signed constructor inputs. */
        boolean matchesFixedWallInputs(CardinalRealizedArcRecipe exactRecipe,
                double[] selectedStart, double[] selectedTarget, StoredDyadic.Work same) {
            if (exactRecipe == null || recipe != exactRecipe
                    || !preparedFor(exactRecipe.start(), exactRecipe.target(), same)
                    || selectedStart.length != q0.length || selectedTarget.length != q1.length)
                return false;
            for (int i = 0; i < q0.length; i++) {
                if (!StoredRealizationLimits.sameRaw(selectedStart[i], q0[i])
                        || !StoredRealizationLimits.sameRaw(selectedTarget[i], q1[i])) return false;
            }
            return true;
        }
        /** Bounded interval proof over every binary64 parameter, not sampled orientations. */
        private void proveFixedDomain(StoredDyadic.Work same) {
            same.charge(1);
            double nominalScalar = actualStart.placement().shape().geometryDimensions().centerlineHalfLength();
            if (StoredRealizationLimits.sameRaw(halfLength, nominalScalar)) {
                // For every actual intermediate q, both recipes execute the same RN basisY(q)*h
                // expression with identical raw scalar, center and radius. Their component and
                // endpoint differences are exactly zero for the entire q domain, including ties,
                // subnormals and cancellation; no sampled orientation or independent error sum.
                // Path's q construction, finite checks and full collision enclosure remain intact.
                // Numeric recipe identity and normalization range are separate certificates.
                // NOMINAL needs no H1 scalar/profile proof, but its actual finite
                // constructor still receives the same all-q normalization bound.
                CardinalJointRnDomain.proveNormalizationBound(same);
                certifiedBasisBound = CardinalJointRnDomain.BASIS_BOUND;
                basisBoundCertified = true;
                fixedDomainProof = FixedDomainProof.NOMINAL_IDENTITY;
                return;
            }
            if (Double.doubleToRawLongBits(halfLength) == 0x3fe333331ffffffcL) {
                var dimensions = actualStart.placement().shape().geometryDimensions();
                if (!StoredRealizationLimits.sameRaw(dimensions.width(), (double) .6F)
                        || !StoredRealizationLimits.sameRaw(dimensions.height(), (double) 1.8F))
                    throw new CardinalRealizedArcRecipe.DomainException("joint RN native dimensions differ");
                var certificate = CardinalJointRnDomain.prove(center, dimensions.centerlineHalfLength(), halfLength, same);
                fixedDomainProof = FixedDomainProof.JOINT_RN;
                fixedDomainTailValues = certificate.enumeratedValues();
                fixedDomainMaximum = certificate.basisBound();
                certifiedBasisBound = certificate.basisBound();
                basisBoundCertified = true;
                return;
            }
            var pending = new java.util.ArrayDeque<double[]>();
            pending.add(new double[] {0, 1, 0});
            int regions = 0;
            double maximum = 0;
            var u = StoredDyadic.of(0x1p-53, same);
            var onePlusU = StoredDyadic.one(same).add(u, same);
            var eta = StoredDyadic.lambdaHalf(same);
            var nominal = StoredDyadic.of(actualStart.placement().shape().geometryDimensions().centerlineHalfLength(), same);
            var fixed = StoredDyadic.of(halfLength, same);
            var delta = nominal.subtract(fixed, same);
            // Exact RN multiplication bound minus the domain's u*|Hnom| term:
            // e-u*|Hnom| <= B*(delta + u*hReal + u^2*hNominal) + (2+u)*eta.
            // Domain retains (1+u)*2gap(C)+eta, so cancel one eta exactly.
            // B comes from the actual normalized/bodyUp operation intervals below.
            var coefficient = delta.add(u.multiply(fixed, same), same)
                    .add(u.multiply(u, same).multiply(nominal, same), same);
            while (!pending.isEmpty()) {
                same.charge(1);
                if (++regions > 255) throw new CardinalRealizedArcRecipe.DomainException("fixed arc interval region limit");
                double[] region = pending.removeFirst();
                Interval[] up = bodyUp(normalized(new Interval(region[0], region[1]), same), same);
                boolean sufficient = true;
                for (int axis = 0; axis < 3; axis++) {
                    double bound = up[axis].absUpper();
                    var residual = StoredDyadic.of(bound, same).multiply(coefficient, same)
                            .add(onePlusU.multiply(eta, same), same);
                    var floor = onePlusU.multiply(StoredDyadic.gap64(component(center, axis), same).scalePow2(1, same), same);
                    if (bound > 17.0 / 16.0 || residual.compare(floor, same) > 0) sufficient = false;
                }
                if (!sufficient) {
                    if (region[2] >= 8) throw new CardinalRealizedArcRecipe.DomainException("fixed arc component domain unproved");
                    double mid = region[0] + (region[1] - region[0]) * .5;
                    pending.addFirst(new double[] {mid, region[1], region[2] + 1});
                    pending.addFirst(new double[] {region[0], mid, region[2] + 1});
                } else {
                    for (Interval component : up) maximum = Math.max(maximum, component.absUpper());
                }
            }
            fixedDomainProof = FixedDomainProof.INTERVAL_BOUND;
            fixedDomainIntervalRegions = regions;
            fixedDomainMaximum = maximum;
        }

        enum FixedDomainProof { NOMINAL_IDENTITY, JOINT_RN, INTERVAL_BOUND }
        private FixedDomainProof fixedDomainProof;
        private boolean basisBoundCertified;
        private double certifiedBasisBound;
        FixedDomainProof fixedDomainProof() { return fixedDomainProof; }
        private int fixedDomainIntervalRegions;
        private int fixedDomainTailValues;
        private double fixedDomainMaximum;
        int fixedDomainIntervalRegions() { return fixedDomainIntervalRegions; }
        int fixedDomainTailValues() { return fixedDomainTailValues; }
        double fixedDomainMaximum() { return fixedDomainMaximum; }
        double realizedHalfLength() { return halfLength; }

        long specialWallWorkDelta() { return specialWall == null ? 0 : specialWall.numericWorkDelta(); }
        long yToXWallWorkDelta() { return yToXWall == null ? 0 : yToXWall.numericWorkDelta(); }
        long xToYWallWorkDelta() { return xToYWall == null ? 0 : xToYWall.numericWorkDelta(); }

        /** +1 means every capsule point is >= plane; -1 means every point is <= plane. */
        boolean provesAxisHalfSpace(int axis, int side, double plane, double from, double to,
                StoredDyadic.Work same) {
            return provesAxisHalfSpace(axis, side, plane, from, to, same, false);
        }

        /**
         * Generic arc receipts may use the actual Y-component producer bound, but only after
         * the outward interval proves nonnegative Y over each complete subregion. Other axes
         * retain the original interval proof unchanged.
         */
        boolean provesGenericAxisHalfSpace(int axis, int side, double plane, double from, double to,
                StoredDyadic.Work same) {
            return provesAxisHalfSpace(axis, side, plane, from, to, same, axis == 1);
        }

        private boolean provesAxisHalfSpace(int axis, int side, double plane, double from, double to,
                StoredDyadic.Work same, boolean allowSignedYProducerBound) {
            Objects.requireNonNull(same).charge(1);
            if (same.identity() != workIdentity) throw new IllegalArgumentException("halfspace requires path Work");
            if (axis < 0 || axis > 2 || (side != -1 && side != 1) || !Double.isFinite(plane)
                    || !Double.isFinite(from) || !Double.isFinite(to) || from < 0 || from > to || to > 1)
                return false;
            // Every interior endpoint pair is RN(C-H), RN(C+H) at this fixed C.
            // Monotonic RN brackets finite C for either sign of finite H. The
            // actual closed-domain overrides were checked once during prepare.
            // This disproves only this face, never collision or another face.
            same.charge(2);
            double fixedCoordinate = component(center, axis);
            if (fixedCenterBracket && (side > 0 ? fixedCoordinate < plane : fixedCoordinate > plane))
                return false;
            if (specialWall != null && specialWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            if (yToXWall != null && yToXWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            if (xToYWall != null && xToYWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            if (zNegativeToYWall != null && zNegativeToYWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            if (signedAxisWall != null && signedAxisWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            if (manualNominalWall != null && manualNominalWall.proves(this, recipe, axis, side, plane, from, to, same)) return true;
            // One actual closed-domain endpoint outside the requested halfspace disproves
            // that face. This is neither a collision verdict nor permission for another face.
            if (from == 0 && !actualEndpointWithinHalfSpace(start, axis, side, plane, same)) return false;
            if (to == 1 && !actualEndpointWithinHalfSpace(target, axis, side, plane, same)) return false;
            var pending = new java.util.ArrayDeque<double[]>();
            pending.add(new double[] {from, to, 0});
            int regions = 0;
            while (!pending.isEmpty()) {
                same.charge(1);
                if (++regions > 63) return false;
                double[] region = pending.removeFirst();
                Interval up = bodyUp(normalized(new Interval(region[0], region[1]), same), same)[axis];
                if (basisBoundCertified) {
                    // Intersect independent outward certificates before multiplying h.
                    // An empty intersection signals inconsistent internal proof data;
                    // never clamp both endpoints to manufacture a passing interval.
                    same.charge(4);
                    double lo = Math.max(up.lower, -certifiedBasisBound);
                    double hi = Math.min(up.upper, certifiedBasisBound);
                    if (lo > hi) throw new IllegalStateException("arc normalization certificates are disjoint");
                    up = new Interval(lo, hi);
                }
                boolean signedYDomain = allowSignedYProducerBound && axis == 1 && up.lower >= 0;
                if (signedYDomain) {
                    // For the actual rotateComponents(v=(0,1,0)) operation order, a finite
                    // normalized quaternion with a nonnegative outward Y lower bound also
                    // has exact producer upper bound 1. Intersect the two independent proofs;
                    // never substitute a quaternion norm idealization for this stored recipe.
                    same.charge(4);
                    up = up.intersect(0, 1);
                }
                Interval hs = up.multiply(Interval.point(halfLength), same);
                if (signedYDomain) {
                    // Multiplication's outward interval can step above h by one ULP. The
                    // signed producer proof independently establishes 0 <= RN(h*y) <= h.
                    same.charge(4);
                    hs = hs.intersect(0, halfLength);
                }
                double c = component(center, axis);
                // Java RN addition/subtraction is monotone. These actual rounded extrema contain
                // C +/- every H in the interval; no padded-ball or extra endpoint epsilon is used.
                same.charge(4);
                double low = Math.min(finite(c - hs.upper), finite(c + hs.lower));
                double high = Math.max(finite(c - hs.lower), finite(c + hs.upper));
                if (region[0] == 0) {
                    low = Math.min(low, Math.min(component(start.negative, axis), component(start.positive, axis)));
                    high = Math.max(high, Math.max(component(start.negative, axis), component(start.positive, axis)));
                }
                if (region[1] == 1) {
                    low = Math.min(low, Math.min(component(target.negative, axis), component(target.positive, axis)));
                    high = Math.max(high, Math.max(component(target.negative, axis), component(target.positive, axis)));
                }
                var support = StoredDyadic.of(side > 0 ? low : high, same);
                var r = StoredDyadic.of(radius, same);
                support = side > 0 ? support.subtract(r, same) : support.add(r, same);
                int comparison = support.compare(StoredDyadic.of(plane, same), same);
                if (side > 0 ? comparison < 0 : comparison > 0) {
                    if (region[2] >= 8 || region[0] == region[1]) return false;
                    double mid = region[0] + (region[1] - region[0]) * .5;
                    pending.addFirst(new double[] {mid, region[1], region[2] + 1});
                    pending.addFirst(new double[] {region[0], mid, region[2] + 1});
                }
            }
            return true;
        }

        private boolean actualEndpointWithinHalfSpace(Geometry actual, int axis, int side,
                double plane, StoredDyadic.Work same) {
            same.charge(2);
            double a = component(actual.negative, axis), b = component(actual.positive, axis);
            var endpoint = StoredDyadic.of(side > 0 ? Math.min(a, b) : Math.max(a, b), same);
            var actualRadius = StoredDyadic.of(actual.capsule.radius(), same);
            var support = side > 0 ? endpoint.subtract(actualRadius, same) : endpoint.add(actualRadius, same);
            int comparison = support.compare(StoredDyadic.of(plane, same), same);
            return side > 0 ? comparison >= 0 : comparison <= 0;
        }

        /**
         * Proves this exact signed Path stays above one Y plane for the requested interval.
         * The validator must bind that fact to its source, capture, ordinal and box before
         * it may omit the corresponding CHB enclosure query.
         */
        boolean provesYLowerHalfSpace(double plane, double from, double to, StoredDyadic.Work same) {
            Objects.requireNonNull(same, "same").charge(1);
            if (same.identity() != workIdentity) {
                throw new IllegalArgumentException("arc plane proof requires its prepare Work");
            }
            if (!Double.isFinite(plane) || !Double.isFinite(from) || !Double.isFinite(to)
                    || from < 0 || from > to || to > 1) return false;
            same.charge(32);
            if (!inYIdentityProofDomain()) return false;

            // Path.at uses these exact RN operations for every interior double parameter.
            double nominalNegativeY = finite(center.y() - halfLength);
            return nonnegativePlaneMargin(nominalNegativeY, radius, plane, same)
                    && nonnegativePlaneMargin(start.negative.y(), radius, plane, same)
                    && nonnegativePlaneMargin(start.positive.y(), radius, plane, same)
                    && nonnegativePlaneMargin(target.negative.y(), radius, plane, same)
                    && nonnegativePlaneMargin(target.positive.y(), radius, plane, same);
        }

        private boolean inYIdentityProofDomain() {
            return q0[0] == 0 && q0[1] == 0
                    && Math.abs(q0[2]) <= 13.0 / 32.0
                    && q0[3] >= 7.0 / 8.0 && q0[3] <= 1.0
                    && positiveZero(q1[0]) && positiveZero(q1[1]) && positiveZero(q1[2])
                    && Double.doubleToRawLongBits(q1[3]) == Double.doubleToRawLongBits(1.0);
        }

        Enclosure enclose(double a, double b, StoredDyadic.Work same) {
            Objects.requireNonNull(same, "same");
            long before = same.units();
            same.charge(1);
            if (same.identity() != workIdentity) throw new IllegalArgumentException("arc enclosure requires its prepare Work");
            if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || a > b || b > 1) {
                throw new IllegalArgumentException("arc interval must lie within [0,1]");
            }
            same.charge(REFERENCE_WORK);
            double mid = a + (b - a) * .5;
            Geometry reference = invariant ? start : at(mid);
            double epsilon = 0;
            if (!invariant && a != b) {
                Interval[] normalized = normalized(new Interval(a, b), same);
                Interval[] up = bodyUp(normalized, same);
                Interval[] negative = new Interval[3], positive = new Interval[3];
                for (int axis = 0; axis < 3; axis++) {
                    Interval hs = up[axis].multiply(Interval.point(halfLength), same);
                    Interval c = Interval.point(component(center, axis));
                    negative[axis] = c.subtract(hs, same);
                    positive[axis] = c.add(hs, same);
                    if (a == 0) {
                        negative[axis] = negative[axis].include(component(start.negative, axis));
                        positive[axis] = positive[axis].include(component(start.positive, axis));
                    }
                    if (b == 1) {
                        negative[axis] = negative[axis].include(component(target.negative, axis));
                        positive[axis] = positive[axis].include(component(target.positive, axis));
                    }
                }
                epsilon = Math.max(distanceUpper(negative, reference.negative, same),
                        distanceUpper(positive, reference.positive, same));
            }
            // For each physical endpoint, distance to its corresponding reference endpoint
            // is <= epsilon. The same bound holds for their segment convex combinations.
            // Adding the actual radius therefore gives containment of every actual capsule
            // and also the corresponding-endpoint convex connections. This does not assert
            // that those uninstalled connections satisfy any stored realization recipe,
            // collide safely, or carry owner permission. No endpoint residual bridge is used
            // as an alternative to the whole-operation interval calculation above.
            same.charge(ENCLOSURE_WORK);
            double paddedRadius = epsilon == 0 ? radius : up(radius + epsilon);
            Capsule padded = new Capsule(reference.capsule.centerline(), paddedRadius);
            Aabb bounds = outwardBounds(reference, paddedRadius);
            return new Enclosure(reference.capsule, padded, bounds, same.units() - before, workIdentity);
        }

        /** Actual recipe, not a nominal interpolation of stored endpoint positions. */
        private Geometry at(double t) {
            if (t == 0) return start;
            if (t == 1) return target;
            double s = 1 - t;
            Rotation3d q = new Rotation3d(s * q0[0] + t * q1[0], s * q0[1] + t * q1[1],
                    s * q0[2] + t * q1[2], s * q0[3] + t * q1[3]);
            Vec3d hs = q.basisY().multiply(halfLength);
            Vec3d negative = center.subtract(hs), positive = center.add(hs);
            return new Geometry(new Capsule(new Segment3d(negative, positive), radius), hs, negative, positive);
        }

        /** Fixed-center use of the same all-child domain proof; no scalar-root transfer. */
        Aabb allChildCaptureBounds(StoredDyadic.Work work) {
            return captureBoundsOverCenters(center, center, work);
        }

        /**
         * Covers every actual enclose(J).bounds() for this ordinary recipe, every closed
         * J within [0,1], and every stored C in [centerLow,centerHigh]. It does not assert
         * collision clearance or replace the validator's interval recipe.
         *
         * Lemma: nested normalized/bodyUp/multiply intervals are inclusion-isotone, so
         * each child half-line and each actual reference half-line lies in H below.
         * Stored 0/1 half-lines are explicitly included, also for nonnominal sources.
         * StoredCapsuleGeometry constructs both fast endpoints as RN(C +/- Hstored),
         * including AXIS_PLANE realizations: Hstored need not equal exact(endpoint-C).
         * The RN errors below therefore remain present for these actual fast endpoints.
         * A variable center domain retains its existing nominal-target prerequisite;
         * a fixed center also covers the actual nonnominal target supported by enclose.
         * For one child, C is shared: exact (C+sigma*h1)-(C+sigma*h2) has magnitude
         * <= width(H), independent of center-domain width. RN is not cancelled:
         * interval endpoints have RN plus nextDown/Up error <= 2U; references have
         * RN error <= U. U is a whole-domain ulp bound, not a preview-center ulp.
         * Thus actual endpoint-minus-reference magnitude <= width(H)+3U. Its own
         * distanceUpper subtraction incurs RN plus outward error, separately bounded
         * by 2V. The symmetric differences then use the identical ordered outward
         * square/add/sqrt extension as distanceUpper. Monotonicity gives EchildMax.
         * Reference world endpoints still retain C-domain width once. Expanding their
         * domain by up(radius+EchildMax) with outwardBounds' exact operation order
         * consequently contains every actual child padded bounds, not only its ideal
         * physical capsule. No child sampling is used to establish this lemma.
         */
        Aabb captureBoundsOverCenters(Vec3d centerLow, Vec3d centerHigh, StoredDyadic.Work work) {
            if (work.identity() != workIdentity || recipe != null)
                throw new IllegalArgumentException("center-domain capture requires the same ordinary arc Work");
            boolean fixedCenter = StoredRealizationLimits.sameRaw(centerLow, center)
                    && StoredRealizationLimits.sameRaw(centerHigh, center);
            var targetStored = actualTarget.storedCapsuleGeometry();
            if (!fixedCenter && (targetStored.relation().kind() != RealizationRelation.Kind.NOMINAL
                    || !StoredRealizationLimits.sameRaw(targetStored.storedHalfLine(), targetStored.nominalHalfLine())))
                throw new IllegalArgumentException("variable center-domain target realization is not the ordinary nominal recipe");
            work.charge(16); // Fixed domain validation and branch observations.
            Interval[] centers = new Interval[3];
            for (int axis=0; axis<3; axis++)
                centers[axis]=new Interval(component(centerLow,axis),component(centerHigh,axis));
            // enclose explicitly selects start and epsilon=0 for these exact invariants.
            // Preserve that semantic, including zero-radius-padding at a fixed center.
            if (invariant) {
                if (fixedCenter) {
                    work.charge(18); return outwardBounds(start,radius);
                }
                Interval[] negative=new Interval[3],positive=new Interval[3];
                for(int axis=0;axis<3;axis++) {
                    var hs=Interval.point(component(start.halfLine,axis));
                    negative[axis]=centers[axis].subtract(hs,work);
                    positive[axis]=centers[axis].add(hs,work);
                }
                return domainBounds(negative,positive,radius,work);
            }
            Interval[] upDomain=bodyUp(normalized(new Interval(0,1),work),work);
            Interval[] negative=new Interval[3],positive=new Interval[3];
            Interval squared=Interval.point(0);
            for(int axis=0;axis<3;axis++) {
                Interval hs=upDomain[axis].multiply(Interval.point(halfLength),work)
                        .include(component(start.halfLine,axis)).include(component(target.halfLine,axis));
                // These bounds include actual midpoint RN and the existing child interval
                // endpoint RN+nextDown/Up, including binades, cancellation and subnormals.
                StoredDyadic width=StoredDyadic.of(hs.upper,work).subtract(StoredDyadic.of(hs.lower,work),work);
                double magnitude=StoredDyadic.of(centers[axis].absUpper(),work)
                        .add(StoredDyadic.of(hs.absUpper(),work),work).roundUp(work);
                StoredDyadic unit=StoredDyadic.of(domainRoundingUnit(magnitude,work),work);
                StoredDyadic endpointDifference=width.add(unit.scalePow2(1,work),work).add(unit,work);
                double subtractionMagnitude=endpointDifference.roundUp(work);
                StoredDyadic subtractionUnit=StoredDyadic.of(domainRoundingUnit(subtractionMagnitude,work),work);
                double difference=endpointDifference.add(subtractionUnit.scalePow2(1,work),work).roundUp(work);
                Interval symmetricDifference=new Interval(-difference,difference);
                squared=squared.add(symmetricDifference.square(work),work);
                negative[axis]=centers[axis].subtract(hs,work);
                positive[axis]=centers[axis].add(hs,work);
            }
            double epsilon=squared.intersect(0,Double.MAX_VALUE).sqrt(work).upper;
            work.charge(2);
            double paddedRadius=epsilon==0 ? radius : up(radius+epsilon);
            return domainBounds(negative,positive,paddedRadius,work);
        }

        /** U covers RN error and each adjacent representable spacing over |x|<=magnitude.
         * nextUp also covers the larger spacing on a binade edge; ulp(0) is MIN_VALUE.
         * A full ulp (rather than half) safely includes gradual underflow and tie cases. */
        private static double domainRoundingUnit(double magnitude,StoredDyadic.Work work) {
            work.charge(2);
            return finite(Math.ulp(up(magnitude)));
        }
        private static Aabb domainBounds(Interval[] negative,Interval[] positive,double paddedRadius,
                StoredDyadic.Work work) {
            work.charge(18); // Per axis min/max, subtract/add and final outward steps.
            double[] low=new double[3],high=new double[3];
            for(int axis=0;axis<3;axis++) {
                low[axis]=down(Math.min(negative[axis].lower,positive[axis].lower)-paddedRadius);
                high[axis]=up(Math.max(negative[axis].upper,positive[axis].upper)+paddedRadius);
            }
            return new Aabb(new Vec3d(low[0],low[1],low[2]),new Vec3d(high[0],high[1],high[2]));
        }

        private Interval[] normalized(Interval t, StoredDyadic.Work work) {
            Interval s = Interval.point(1).subtract(t, work);
            Interval[] l = new Interval[4];
            for (int i = 0; i < 4; i++) {
                l[i] = s.multiply(Interval.point(q0[i]), work)
                        .add(t.multiply(Interval.point(q1[i]), work), work);
            }

            // The actual recipe keeps the common t parameter through all four components.
            // For each component, exact affine endpoint extrema plus this RN allowance
            // enclose the rounded s*q0 + t*q1 value; this tightens only the proof interval.
            StoredDyadic[][] affineEndpoints = affineEndpoints(t, work);
            StoredDyadic margin = StoredDyadic.of(0x1p-48, work);
            for (int i = 0; i < 4; i++) {
                StoredDyadic atLower = affineEndpoints[0][i];
                StoredDyadic atUpper = affineEndpoints[1][i];
                int endpointOrder = atLower.compare(atUpper, work);
                StoredDyadic minimum = endpointOrder <= 0 ? atLower : atUpper;
                StoredDyadic maximum = endpointOrder <= 0 ? atUpper : atLower;
                double tightLower = minimum.subtract(margin, work).roundDown(work);
                double tightUpper = maximum.add(margin, work).roundUp(work);
                double lower = Math.max(l[i].lower, tightLower);
                double upper = Math.min(l[i].upper, tightUpper);
                work.charge(1);
                if (lower > upper)
                    throw new IllegalStateException("exact-affine and rounded quaternion component enclosures are disjoint");
                l[i] = new Interval(lower, upper);
            }

            double maxLower = .25, maxUpper = 0;
            for (int i = 0; i < 4; i++) {
                maxLower = Math.max(maxLower, l[i].absLower());
                maxUpper = Math.max(maxUpper, l[i].absUpper());
            }
            /* With exact signed dot >= 0, ideal ||L||^2 >= (1-2^-46)/2 > .49.
             * Thus some ideal |Li| > .35. Let u=2^-53, eta=2^-46, lambda=MIN_VALUE.
             * |RN(1-t)-(1-t)| <= u+lambda; each multiply has |error| <=
             * u*(1+eta)*(1+u)+lambda. Addition has |error| <=
             * u*(2*(1+eta)*(1+u)+2*lambda)+lambda. Their sum plus the first
             * subtraction error times |q0i| is < 8u+8lambda < 2^-48.
             * Hence actual max |Li| > .35-2^-48 > .25, including subnormal terms.
             * This is a derived constructor denominator bound, not an epsilon test.
             */
            Interval maximum = new Interval(maxLower, Math.max(maxLower, maxUpper));
            Interval[] scaled = new Interval[4];
            for (int i = 0; i < 4; i++) scaled[i] = l[i].divide(maximum, work).intersect(-1, 1);
            Interval squares = scaled[0].square(work).add(scaled[1].square(work), work)
                    .add(scaled[2].square(work), work).add(scaled[3].square(work), work).intersect(1, 4);
            // One scaled component is exactly +/-1 (division by its own max).
            // Ordered sums of squares are therefore in [1,4], even after RN.
            Interval inverse = Interval.point(1).divide(squares.sqrt(work), work);
            for (int i = 0; i < 4; i++) scaled[i] = scaled[i].multiply(inverse, work).intersect(-1, 1);
            return scaled;
        }

        private StoredDyadic[][] affineEndpoints(Interval interval, StoredDyadic.Work work) {
            StoredDyadic[] lower = affineEndpoint(interval.lower, work);
            StoredDyadic[] upper = affineEndpoint(interval.upper, work);
            work.charge(1);
            if (lower != null && upper != null) return new StoredDyadic[][] {lower, upper};

            work.charge(2);
            boolean interpolateLower = lower == null;
            boolean interpolateUpper = upper == null;

            StoredDyadic lowerParameter = interpolateLower ? StoredDyadic.of(interval.lower, work) : null;
            StoredDyadic upperParameter = interpolateUpper ? StoredDyadic.of(interval.upper, work) : null;
            StoredDyadic[] delta = new StoredDyadic[4];
            for (int i = 0; i < 4; i++) delta[i] = exactQ1[i].subtract(exactQ0[i], work);
            if (interpolateLower) lower = affineValue(lowerParameter, delta, work);
            if (interpolateUpper) upper = affineValue(upperParameter, delta, work);
            return new StoredDyadic[][] {lower, upper};
        }

        private StoredDyadic[] affineEndpoint(double parameter, StoredDyadic.Work work) {
            work.charge(1);
            if (parameter == 0) return exactQ0;
            work.charge(1);
            if (parameter == 1) return exactQ1;
            return null;
        }

        private StoredDyadic[] affineValue(StoredDyadic parameter, StoredDyadic[] delta,
                                           StoredDyadic.Work work) {
            StoredDyadic[] values = new StoredDyadic[4];
            for (int i = 0; i < 4; i++)
                values[i] = exactQ0[i].add(delta[i].multiply(parameter, work), work);
            return values;
        }
    }

    private static boolean nonnegativePlaneMargin(double endpointY, double radius, double plane,
                                                   StoredDyadic.Work same) {
        return StoredDyadic.of(endpointY, same).subtract(StoredDyadic.of(radius, same), same)
                .subtract(StoredDyadic.of(plane, same), same).signum() >= 0;
    }

    private static boolean positiveZero(double value) {
        return Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(0.0);
    }

    static final class Enclosure {
        private final Capsule reference, padded;
        private final Aabb bounds;
        private final long numericWorkDelta;
        private final Object workIdentity;

        private Enclosure(Capsule reference, Capsule padded, Aabb bounds, long numericWorkDelta, Object identity) {
            this.reference = reference;
            this.padded = padded;
            this.bounds = bounds;
            this.numericWorkDelta = numericWorkDelta;
            workIdentity = identity;
        }
        Capsule reference() { return reference; }
        Capsule padded() { return padded; }
        Aabb bounds() { return bounds; }
        long numericWorkDelta() { return numericWorkDelta; }
        boolean uses(StoredDyadic.Work work) { return work != null && work.identity() == workIdentity; }
    }

    private record Geometry(Capsule capsule, Vec3d halfLine, Vec3d negative, Vec3d positive) { }

    private static Geometry geometry(PlayerBodyPlacement placement) {
        StoredCapsuleGeometry stored = placement.storedCapsuleGeometry();
        if (stored == null || !stored.matchesRecipe(placement.center(), placement.attitude(), placement.shape().geometryDimensions())) {
            throw new IllegalStateException("Free arc placement lost actual stored geometry");
        }
        return new Geometry(stored.capsule(), stored.storedHalfLine(), stored.negativeEndpoint(), stored.positiveEndpoint());
    }

    private static boolean sameCapsule(Capsule a, Capsule b) {
        return StoredRealizationLimits.sameRaw(a.radius(), b.radius())
                && StoredRealizationLimits.sameRaw(a.centerline().start(), b.centerline().start())
                && StoredRealizationLimits.sameRaw(a.centerline().end(), b.centerline().end());
    }

    private static void requireNorm(CapsulePlacementMath.Pose pose) {
        if (!(pose.quaternionNormSquaredLower() >= 1 - NORM_ERROR
                && pose.quaternionNormSquaredUpper() <= 1 + NORM_ERROR)) {
            throw new IllegalArgumentException("arc quaternion outside unit consumer profile");
        }
    }

    private static double[] components(Rotation3d q) { return new double[] {q.x(), q.y(), q.z(), q.w()}; }
    private static StoredDyadic[] dyadics(double[] values, StoredDyadic.Work work) {
        StoredDyadic[] result = new StoredDyadic[values.length];
        for (int i = 0; i < values.length; i++) result[i] = StoredDyadic.of(values[i], work);
        return result;
    }
    private static StoredDyadic dot(StoredDyadic[] a, StoredDyadic[] b, StoredDyadic.Work work) {
        StoredDyadic result = StoredDyadic.of(0, work);
        for (int i = 0; i < a.length; i++) result = result.add(a[i].multiply(b[i], work), work);
        return result;
    }
    private static StoredDyadic[] relativeVector(StoredDyadic[] a, StoredDyadic[] b, StoredDyadic.Work work) {
        return new StoredDyadic[] {
                b[0].multiply(a[3], work).subtract(b[3].multiply(a[0], work), work)
                        .subtract(b[1].multiply(a[2], work), work).add(b[2].multiply(a[1], work), work),
                b[1].multiply(a[3], work).subtract(b[3].multiply(a[1], work), work)
                        .add(b[0].multiply(a[2], work), work).subtract(b[2].multiply(a[0], work), work),
                b[2].multiply(a[3], work).subtract(b[3].multiply(a[2], work), work)
                        .subtract(b[0].multiply(a[1], work), work).add(b[1].multiply(a[0], work), work)};
    }

    /** Same operation order as actual 9CB rotateComponents(v=(0,1,0)); no ideal B/N shortcut. */
    private static Interval[] bodyUp(Interval[] q, StoredDyadic.Work work) {
        Interval zero = Interval.point(0), one = Interval.point(1), two = Interval.point(2);
        Interval x = q[0], y = q[1], z = q[2], w = q[3];
        Interval tx = y.multiply(zero, work).subtract(z.multiply(one, work), work).multiply(two, work);
        Interval ty = z.multiply(zero, work).subtract(x.multiply(zero, work), work).multiply(two, work);
        Interval tz = x.multiply(one, work).subtract(y.multiply(zero, work), work).multiply(two, work);
        // Canonical simultaneous sign flip flips t too, so every q*t product is invariant.
        // RN is sign symmetric; signed-zero differences disappear at Vec3d canonicalZero.
        return new Interval[] {
                zero.add(w.multiply(tx, work), work).add(y.multiply(tz, work), work).subtract(z.multiply(ty, work), work),
                one.add(w.multiply(ty, work), work).add(z.multiply(tx, work), work).subtract(x.multiply(tz, work), work),
                zero.add(w.multiply(tz, work), work).add(x.multiply(ty, work), work).subtract(y.multiply(tx, work), work)};
    }

    private static double distanceUpper(Interval[] endpoints, Vec3d reference, StoredDyadic.Work work) {
        Interval squared = Interval.point(0);
        for (int i = 0; i < 3; i++) {
            Interval difference = endpoints[i].subtract(Interval.point(component(reference, i)), work);
            squared = squared.add(difference.square(work), work);
        }
        return squared.intersect(0, Double.MAX_VALUE).sqrt(work).upper;
    }

    private static Aabb outwardBounds(Geometry geometry, double radius) {
        double[] min = new double[3], max = new double[3];
        for (int i = 0; i < 3; i++) {
            min[i] = down(Math.min(component(geometry.negative, i), component(geometry.positive, i)) - radius);
            max[i] = up(Math.max(component(geometry.negative, i), component(geometry.positive, i)) + radius);
        }
        return new Aabb(new Vec3d(min[0], min[1], min[2]), new Vec3d(max[0], max[1], max[2]));
    }
    private static double component(Vec3d v, int axis) { return StoredRealizationLimits.component(v, axis); }
    private static boolean zero(Vec3d v) { return v.x() == 0 && v.y() == 0 && v.z() == 0; }
    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("arc enclosure arithmetic outside finite profile");
        return value;
    }
    private static double down(double value) { return finite(Math.nextDown(finite(value))); }
    private static double up(double value) { return finite(Math.nextUp(finite(value))); }

    /** Outward ranges for the rounded result of each actual Java operation. */
    private record Interval(double lower, double upper) {
        Interval {
            finite(lower); finite(upper);
            if (lower > upper) throw new IllegalStateException("arc interval proof became empty");
        }
        static Interval point(double value) { return new Interval(value, value); }
        boolean is(double value) { return lower == value && upper == value; }
        Interval add(Interval b, StoredDyadic.Work work) {
            work.charge(1);
            if (is(0)) return b;
            if (b.is(0)) return this;
            return new Interval(down(lower + b.lower), up(upper + b.upper));
        }
        Interval subtract(Interval b, StoredDyadic.Work work) {
            work.charge(1);
            if (b.is(0)) return this;
            return new Interval(down(lower - b.upper), up(upper - b.lower));
        }
        Interval multiply(Interval b, StoredDyadic.Work work) {
            work.charge(4);
            if (is(0) || b.is(0)) return point(0);
            if (is(1)) return b;
            if (b.is(1)) return this;
            double a = finite(lower * b.lower), c = finite(lower * b.upper);
            double d = finite(upper * b.lower), e = finite(upper * b.upper);
            return new Interval(down(Math.min(Math.min(a, c), Math.min(d, e))),
                    up(Math.max(Math.max(a, c), Math.max(d, e))));
        }
        Interval divide(Interval b, StoredDyadic.Work work) {
            work.charge(4);
            if (b.lower <= 0) throw new IllegalStateException("arc division denominator is not positive");
            if (is(0)) return point(0);
            if (b.is(1)) return this;
            double a = finite(lower / b.lower), c = finite(lower / b.upper);
            double d = finite(upper / b.lower), e = finite(upper / b.upper);
            return new Interval(down(Math.min(Math.min(a, c), Math.min(d, e))),
                    up(Math.max(Math.max(a, c), Math.max(d, e))));
        }
        Interval square(StoredDyadic.Work work) {
            work.charge(2);
            if (is(0)) return point(0);
            double min = absLower(), max = absUpper();
            return new Interval(min == 0 ? 0 : Math.max(0, down(min * min)), up(max * max));
        }
        Interval sqrt(StoredDyadic.Work work) {
            work.charge(2);
            if (lower < 0) throw new IllegalStateException("arc square-root lower bound is negative");
            if (is(0)) return this;
            return new Interval(lower == 0 ? 0 : Math.max(0, down(Math.sqrt(lower))), up(Math.sqrt(upper)));
        }
        double absLower() { return lower <= 0 && upper >= 0 ? 0 : Math.min(Math.abs(lower), Math.abs(upper)); }
        double absUpper() { return Math.max(Math.abs(lower), Math.abs(upper)); }
        Interval include(double value) { return new Interval(Math.min(lower, value), Math.max(upper, value)); }
        Interval intersect(double min, double max) { return new Interval(Math.max(lower, min), Math.min(upper, max)); }
    }
}
