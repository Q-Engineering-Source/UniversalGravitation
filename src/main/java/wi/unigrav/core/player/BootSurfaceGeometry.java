package wi.unigrav.core.player;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Accepted-body boot sensors and distances to finite, axis-aligned surface patches (metres).
 *
 * <p>This module does not find blocks, test visibility, prove body fit, or attach a player.
 * The caller supplies its accepted physical snapshot and a real surface patch. A positive
 * range result is only a geometric prerequisite; it is not permission to change that snapshot.
 */
public final class BootSurfaceGeometry {
    private BootSurfaceGeometry() {
    }

    public enum SourceKind { CARDINAL_FOOT_RECTANGLE, FREE_LOWER_BALL }

    /** Minimum signed source projection relative to the face plane, not a finite-solid penetration test. */
    public enum MinimumSide { FRONT, ON_PLANE, BEHIND_PLANE }

    public enum Range { IN_RANGE, OUT_OF_RANGE }

    /**
     * A closed rectangle with positive tangent extents and exactly zero normal extent.
     * Its outward frame supplies the surface normal; it does not select the player's frame.
     */
    public record FaceRectangle(Aabb bounds, CardinalFrame outward) {
        public FaceRectangle {
            Objects.requireNonNull(bounds, "bounds");
            Objects.requireNonNull(outward, "outward");
            int normal = normalAxis(outward);
            for (int axis = 0; axis < 3; axis++) {
                double low = component(bounds.min(), axis);
                double high = component(bounds.max(), axis);
                if (axis == normal ? low != high : low >= high) {
                    throw new IllegalArgumentException("surface requires zero normal extent and positive tangent extents");
                }
            }
        }
    }

    /**
     * Derived only through {@link #fromAccepted}. The retained snapshot identifies the source's
     * scope/revision, but construction does not prove that a live owner has installed it.
     */
    public static final class Source {
        private final PlayerPhysicalSnapshot physical;
        private final SourceKind kind;
        private final Aabb anchorBounds;
        private final double radius;
        private final BootQueryArithmetic arithmetic;
        private final CertifiedReferenceEndpoint endpoint;

        private Source(PlayerPhysicalSnapshot physical, SourceKind kind, Aabb anchorBounds, double radius) {
            this(physical, kind, anchorBounds, radius, null);
        }
        private Source(PlayerPhysicalSnapshot physical, SourceKind kind, Aabb anchorBounds, double radius,
                       BootQueryArithmetic arithmetic) {
            this(physical, kind, anchorBounds, radius, arithmetic, null);
        }
        private Source(PlayerPhysicalSnapshot physical, SourceKind kind, Aabb anchorBounds, double radius,
                       BootQueryArithmetic arithmetic, CertifiedReferenceEndpoint endpoint) {
            this.arithmetic = arithmetic;
            this.physical = physical;
            this.kind = kind;
            this.anchorBounds = anchorBounds;
            this.radius = radius;
            this.endpoint = endpoint;
        }

        public PlayerPhysicalSnapshot physical() { return physical; }
        public SourceKind kind() { return kind; }

        /** Full foot rectangle, or the lower ball's centre as a point AABB; not the whole body proxy. */
        public Aabb anchorBounds() { return anchorBounds; }
        public double radius() { return radius; }
        public boolean hasArithmetic() { return arithmetic != null; }
        public boolean usesArithmetic(BootQueryArithmetic expected) { return arithmetic != null && arithmetic == expected; }
        public boolean isAcceptedSensor() { return endpoint == null; }
        public boolean queryCurrent() { return endpoint == null || endpoint.current(); }
        /** Endpoint sensor permission does not replace the physical owner's live placement. */
        public boolean endpointCurrentFor(PlayerPhysicalSnapshot exactOwner) {
            return endpoint != null && physical == exactOwner && endpoint.owner() == exactOwner && endpoint.current();
        }

        /** Outward-rounded bounds of this sensor expanded by reach; does not load a world. */
        public Aabb queryBounds(double reachMetres) {
            if (arithmetic != null) return queryBounds(reachMetres, arithmetic);
            requireReach(reachMetres);
            BigDecimal expansion = exact(radius).add(exact(reachMetres));
            double[] low = new double[3];
            double[] high = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = directed(exact(component(anchorBounds.min(), axis)).subtract(expansion), false);
                high[axis] = directed(exact(component(anchorBounds.max(), axis)).add(expansion), true);
            }
            return new Aabb(vector(low), vector(high));
        }

        /** Only a source explicitly bound before footprint planning can use this receipt. */
        public Aabb queryBounds(double reachMetres, BootQueryArithmetic sameArithmetic) {
            requireArithmetic(this, sameArithmetic);
            requireReach(reachMetres);
            var expansion = sameArithmetic.add(sameArithmetic.exact(radius), sameArithmetic.exact(reachMetres));
            double[] low = new double[3], high = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                low[axis] = sameArithmetic.roundDown(sameArithmetic.subtract(
                        sameArithmetic.exact(component(anchorBounds.min(), axis)), expansion));
                high[axis] = sameArithmetic.roundUp(sameArithmetic.add(
                        sameArithmetic.exact(component(anchorBounds.max(), axis)), expansion));
            }
            return new Aabb(vector(low), vector(high));
        }
    }

    /** Rechecks a retained query's producer dependencies after this exact transaction installed.
     * The original sensor remains query geometry only; this lease supplies no installation authority. */
    public static final class InstalledObservationLease {
        private final FreeOwnerTransaction transaction;
        private final Source source;
        private final PlayerPhysicalSnapshot journal;
        private final PlayerBodyPlacement placement;
        private InstalledObservationLease(FreeOwnerTransaction transaction, Source source) {
            this.transaction = transaction; this.source = source;
            transaction.requireCurrent();
            journal = transaction.installedJournal().orElseThrow();
            placement = transaction.installedPlacement().orElseThrow();
            if (transaction.installationStage() != FreeOwnerTransaction.InstallationStage.INSTALLED
                    || source.physical() != transaction.accepted() || journal.placement() != placement)
                throw new IllegalStateException("post-install boot observation has no exact installed owner");
            requireCurrent(transaction.world(), transaction.actor(), source);
        }
        public void requireCurrent(WorldServer world, EntityPlayer actor, Source exactSource) {
            if (world != transaction.world() || actor != transaction.actor() || exactSource != source)
                throw new IllegalStateException("foreign post-install boot observation context");
            transaction.requireCurrent();
            if (transaction.installationStage() != FreeOwnerTransaction.InstallationStage.INSTALLED
                    || transaction.installedJournal().orElse(null) != journal
                    || transaction.installedPlacement().orElse(null) != placement || journal.placement() != placement)
                throw new IllegalStateException("post-install boot observation owner changed");
            transaction.requireCurrent();
        }
    }

    static InstalledObservationLease installedObservation(FreeOwnerTransaction transaction, Source source) {
        return new InstalledObservationLease(transaction, source);
    }

    /** Inclusive enclosing interval; an interval touching zero does not assert exact physical contact. */
    public record Gap(double lowerMetres, double upperMetres) {
        public Gap {
            if (!Double.isFinite(lowerMetres) || !Double.isFinite(upperMetres)
                    || lowerMetres < 0 || upperMetres < lowerMetres) {
                throw new IllegalArgumentException("gap bounds must be finite, ordered and nonnegative");
            }
        }
    }

    public sealed interface Result permits Proximity, Unavailable {
    }

    /**
     * Closest anchors are on the foot rectangle and patch, or at the lower ball's centre
     * and its closest patch point. For a ball the source anchor is NOT a surface witness:
     * visibility code must account for {@code source.radius()}, not mistake it for physical feet.
     * Pull direction is a binary64 unit-vector approximation; range uses exact squared values.
     */
    public record Proximity(Source source, FaceRectangle face, Gap gap, Range range,
                            MinimumSide minimumSide, Vec3d sourceAnchor, Vec3d surfaceAnchor,
                            Vec3d pullDirection) implements Result {
        public Proximity {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(gap, "gap");
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(minimumSide, "minimumSide");
            Objects.requireNonNull(sourceAnchor, "sourceAnchor");
            Objects.requireNonNull(surfaceAnchor, "surfaceAnchor");
            Objects.requireNonNull(pullDirection, "pullDirection");
        }
    }

    /** A numeric representation failure must not be interpreted as no magnetic surface. */
    public record Unavailable(ArithmeticException cause) implements Result {
        public Unavailable { Objects.requireNonNull(cause, "cause"); }
    }

    public static Source fromAccepted(PlayerPhysicalSnapshot physical) {
        Objects.requireNonNull(physical, "physical");
        PlayerBodyPlacement placement = physical.placement();
        if (placement.shape() instanceof PlayerBodyShape.Cardinal cardinal) {
            Aabb actual = placement.bounds();
            int axis = normalAxis(cardinal.frame());
            boolean upPositive = component(cardinal.frame().up(), axis) > 0;
            double feet = component(upPositive ? actual.min() : actual.max(), axis);
            double[] low = components(actual.min());
            double[] high = components(actual.max());
            low[axis] = feet;
            high[axis] = feet;
            Aabb rectangle = new Aabb(vector(low), vector(high));
            // Reject unrepresentable tangent extents instead of silently turning a foot into a point.
            new FaceRectangle(rectangle, cardinal.frame());
            return new Source(physical, SourceKind.CARDINAL_FOOT_RECTANGLE, rectangle, 0.0);
        }
        var dimensions = placement.shape().dimensions();
        // CHB canonicalizes endpoint order. Body up, not Segment3d.start(), defines the lower endpoint.
        Vec3d lower = placement.storedCapsuleGeometry().negativeEndpoint();
        return new Source(physical, SourceKind.FREE_LOWER_BALL, new Aabb(lower, lower), placement.capsule().radius());
    }

    /** Binds sensor calculations, not historical construction work or live owner authority. */
    public static Source fromAccepted(PlayerPhysicalSnapshot physical, BootQueryArithmetic sameArithmetic) {
        Objects.requireNonNull(physical); Objects.requireNonNull(sameArithmetic);
        var placement = physical.placement();
        if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("shared boot source requires actual FreeCapsule");
        Vec3d negative = placement.storedCapsuleGeometry().negativeEndpoint();
        return new Source(physical, SourceKind.FREE_LOWER_BALL, new Aabb(negative, negative),
                placement.capsule().radius(), sameArithmetic);
    }

    static Source fromEndpoint(CertifiedReferenceEndpoint endpoint, BootQueryArithmetic sameArithmetic) {
        Objects.requireNonNull(endpoint).requireCurrent();
        Objects.requireNonNull(sameArithmetic);
        if (sameArithmetic != endpoint.transaction().resources().environmentObservationWork().arithmetic())
            throw new IllegalArgumentException("endpoint sensor requires its owner observation arithmetic");
        var placement = endpoint.pose().placement();
        if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("endpoint boot source requires certified Free capsule");
        Vec3d negative = placement.storedCapsuleGeometry().negativeEndpoint();
        return new Source(endpoint.owner(), SourceKind.FREE_LOWER_BALL, new Aabb(negative, negative),
                placement.capsule().radius(), sameArithmetic, endpoint);
    }
    private static void requireArithmetic(Source source, BootQueryArithmetic arithmetic) {
        Objects.requireNonNull(arithmetic);
        if (!source.usesArithmetic(arithmetic)) throw new IllegalArgumentException("boot source arithmetic differs");
    }

    /** Shared physical range, gap and pull direction. Resource/profile failures propagate unchanged. */
    public static Result measure(Source source, FaceRectangle face, double reachMetres,
                                 BootQueryArithmetic arithmetic) {
        Objects.requireNonNull(source); Objects.requireNonNull(face);
        requireArithmetic(source, arithmetic); requireReach(reachMetres);
        double[] from = new double[3], to = new double[3];
        BootQueryArithmetic.Number[] delta = new BootQueryArithmetic.Number[3];
        var square = arithmetic.exact(0);
        for (int axis = 0; axis < 3; axis++) {
            double sourceLow = component(source.anchorBounds.min(), axis), sourceHigh = component(source.anchorBounds.max(), axis);
            double faceLow = component(face.bounds().min(), axis), faceHigh = component(face.bounds().max(), axis);
            if (arithmetic.compare(arithmetic.exact(sourceHigh), arithmetic.exact(faceLow)) < 0) {
                from[axis] = sourceHigh; to[axis] = faceLow;
            } else if (arithmetic.compare(arithmetic.exact(faceHigh), arithmetic.exact(sourceLow)) < 0) {
                from[axis] = sourceLow; to[axis] = faceHigh;
            } else {
                from[axis] = arithmetic.compare(arithmetic.exact(sourceLow), arithmetic.exact(faceLow)) >= 0 ? sourceLow : faceLow;
                to[axis] = from[axis];
            }
            delta[axis] = arithmetic.subtract(arithmetic.exact(to[axis]), arithmetic.exact(from[axis]));
            square = arithmetic.add(square, arithmetic.multiply(delta[axis], delta[axis]));
        }
        var radius = arithmetic.exact(source.radius);
        var reachRadius = arithmetic.add(radius, arithmetic.exact(reachMetres));
        Range range = arithmetic.compare(square, arithmetic.multiply(reachRadius, reachRadius)) <= 0 ? Range.IN_RANGE : Range.OUT_OF_RANGE;
        Gap gap; Vec3d direction;
        if (arithmetic.compare(square, arithmetic.multiply(radius, radius)) <= 0) {
            gap = new Gap(0, 0);
            arithmetic.chargeOppositeFaceDirection();
            direction = face.outward().up().multiply(-1);
        } else {
            var distance = arithmetic.sqrt(square);
            var low = arithmetic.subtract(arithmetic.exact(distance.lower()), radius);
            var high = arithmetic.subtract(arithmetic.exact(distance.upper()), radius);
            gap = new Gap(arithmetic.sign(low) < 0 ? 0 : arithmetic.roundDown(low),
                    arithmetic.sign(high) < 0 ? 0 : arithmetic.roundUp(high));
            var normalized = arithmetic.normalize(delta[0], delta[1], delta[2]);
            direction = new Vec3d(normalized.x(), normalized.y(), normalized.z());
        }
        int axis = normalAxis(face.outward());
        boolean positive = component(face.outward().up(), axis) > 0;
        var point = arithmetic.exact(component(positive ? source.anchorBounds.min() : source.anchorBounds.max(), axis));
        var plane = arithmetic.exact(component(face.bounds().min(), axis));
        int side = arithmetic.sign(arithmetic.subtract(positive ? arithmetic.subtract(point, plane)
                : arithmetic.subtract(plane, point), radius));
        return new Proximity(source, face, gap, range, side > 0 ? MinimumSide.FRONT : side < 0 ? MinimumSide.BEHIND_PLANE : MinimumSide.ON_PLANE,
                vector(from), vector(to), direction);
    }
    /** Finite set distance only. Face exposure, visibility, eligibility and accepted fit remain separate. */
    public static Result measure(Source source, FaceRectangle face, double reachMetres) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(face, "face");
        if (source.arithmetic != null) return measure(source, face, reachMetres, source.arithmetic);
        requireReach(reachMetres);
        try {
            double[] from = new double[3];
            double[] to = new double[3];
            BigDecimal[] delta = new BigDecimal[3];
            BigDecimal square = BigDecimal.ZERO;
            for (int axis = 0; axis < 3; axis++) {
                double sourceLow = component(source.anchorBounds.min(), axis);
                double sourceHigh = component(source.anchorBounds.max(), axis);
                double faceLow = component(face.bounds().min(), axis);
                double faceHigh = component(face.bounds().max(), axis);
                if (sourceHigh < faceLow) {
                    from[axis] = sourceHigh;
                    to[axis] = faceLow;
                } else if (faceHigh < sourceLow) {
                    from[axis] = sourceLow;
                    to[axis] = faceHigh;
                } else {
                    // Deterministic closest witnesses in a non-unique overlap interval.
                    from[axis] = Math.max(sourceLow, faceLow);
                    to[axis] = from[axis];
                }
                delta[axis] = exact(to[axis]).subtract(exact(from[axis]));
                square = square.add(delta[axis].multiply(delta[axis]));
            }
            BigDecimal radius = exact(source.radius);
            BigDecimal reachRadius = radius.add(exact(reachMetres));
            Range range = square.compareTo(reachRadius.multiply(reachRadius)) <= 0 ? Range.IN_RANGE : Range.OUT_OF_RANGE;
            boolean zeroGap = square.compareTo(radius.multiply(radius)) <= 0;
            Gap gap;
            Vec3d direction;
            if (zeroGap) {
                gap = new Gap(0.0, 0.0);
                direction = face.outward().up().multiply(-1);
            } else {
                double[] distance = sqrtBounds(square);
                gap = new Gap(directed(exact(distance[0]).subtract(radius).max(BigDecimal.ZERO), false),
                        directed(exact(distance[1]).subtract(radius).max(BigDecimal.ZERO), true));
                // Scale before normalizing, so neither squared-length overflow nor tiny-gap underflow matters.
                BigDecimal scale = delta[0].abs().max(delta[1].abs()).max(delta[2].abs());
                double x = delta[0].divide(scale, MathContext.DECIMAL128).doubleValue();
                double y = delta[1].divide(scale, MathContext.DECIMAL128).doubleValue();
                double z = delta[2].divide(scale, MathContext.DECIMAL128).doubleValue();
                double norm = Math.hypot(Math.hypot(x, y), z);
                direction = new Vec3d(x / norm, y / norm, z / norm);
            }
            return new Proximity(source, face, gap, range, minimumSide(source, face), vector(from), vector(to), direction);
        } catch (ArithmeticException cause) {
            return new Unavailable(cause);
        }
    }

    private static MinimumSide minimumSide(Source source, FaceRectangle face) {
        int axis = normalAxis(face.outward());
        boolean positive = component(face.outward().up(), axis) > 0;
        BigDecimal point = exact(component(positive ? source.anchorBounds.min() : source.anchorBounds.max(), axis));
        BigDecimal plane = exact(component(face.bounds().min(), axis));
        int sign = (positive ? point.subtract(plane) : plane.subtract(point)).subtract(exact(source.radius)).signum();
        return sign > 0 ? MinimumSide.FRONT : sign < 0 ? MinimumSide.BEHIND_PLANE : MinimumSide.ON_PLANE;
    }

    /** A decimal estimate is never trusted without exact-square checks of both binary64 endpoints. */
    private static double[] sqrtBounds(BigDecimal square) {
        double estimate = square.sqrt(MathContext.DECIMAL128).doubleValue();
        if (!Double.isFinite(estimate)) throw new ArithmeticException("distance is not finite in binary64");
        for (int step = 0; step < 4; step++) {
            int comparison = exact(estimate).multiply(exact(estimate)).compareTo(square);
            if (comparison == 0) return new double[] {estimate, estimate};
            double adjacent = comparison < 0 ? Math.nextUp(estimate) : Math.nextDown(estimate);
            if (adjacent < 0 || !Double.isFinite(adjacent)) break;
            int adjacentComparison = exact(adjacent).multiply(exact(adjacent)).compareTo(square);
            if (comparison < 0 && adjacentComparison >= 0) return new double[] {estimate, adjacent};
            if (comparison > 0 && adjacentComparison <= 0) return new double[] {adjacent, estimate};
            estimate = adjacent;
        }
        throw new ArithmeticException("cannot enclose distance with verified binary64 endpoints");
    }

    private static double directed(BigDecimal value, boolean upper) {
        double result = value.doubleValue();
        if (!Double.isFinite(result)) throw new ArithmeticException("bound is not finite in binary64");
        int comparison = exact(result).compareTo(value);
        if (upper && comparison < 0) result = Math.nextUp(result);
        if (!upper && comparison > 0) result = Math.nextDown(result);
        if (!Double.isFinite(result)) throw new ArithmeticException("outward bound is not finite in binary64");
        return result;
    }

    private static BigDecimal exact(double value) { return new BigDecimal(value); }
    private static double[] components(Vec3d value) { return new double[] {value.x(), value.y(), value.z()}; }
    private static Vec3d vector(double[] value) { return new Vec3d(value[0], value[1], value[2]); }
    private static double component(Vec3d value, int axis) {
        return switch (axis) { case 0 -> value.x(); case 1 -> value.y(); default -> value.z(); };
    }
    private static int normalAxis(CardinalFrame frame) {
        return switch (frame) { case X_POS, X_NEG -> 0; case Y_POS, Y_NEG -> 1; case Z_POS, Z_NEG -> 2; };
    }
    private static void requireReach(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("reach must be finite and nonnegative");
    }
}
