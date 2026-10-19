package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Immutable physical geometry at a canonical world centre (metres).
 *
 * <p>Construction does not authorize a move or validate a shape transition. The
 * movement owner must validate the whole path and commit placement with its
 * physical revision. Neither the vanilla transport anchor nor the render eye
 * can replace {@link #center()} when reserving displacement.
 */
public final class PlayerBodyPlacement {
    private static final double CARDINAL_ATTITUDE_ERROR = 1e-12;
    private final Vec3d center;
    private final Rotation3d attitude;
    private final PlayerBodyShape shape;
    private final Aabb bounds;
    private final Vec3d transport;
    private final StoredCapsuleGeometry storedCapsuleGeometry;
    private final Vec3d freePhysicalFoot;
    private final Vec3d freeEye;

    /** Constructs a proposed shape from nominal dimensions; not a copy of an installed collider. */
    public PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape) {
        this(center, attitude, shape, null, null, null);
    }

    /** Owner-local construction charges realization work to the whole movement transaction. */
    PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape, StoredDyadic.Work numericWork) {
        this(center, attitude, shape, null, null, null, Objects.requireNonNull(numericWork, "numericWork"));
    }

    private PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape,
                                Aabb actualBounds, Vec3d actualTransport,
                                StoredCapsuleGeometry actualCapsuleGeometry) {
        this(center, attitude, shape, actualBounds, actualTransport, actualCapsuleGeometry, new StoredDyadic.Work());
    }

    private PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape,
                                Aabb actualBounds, Vec3d actualTransport,
                                StoredCapsuleGeometry actualCapsuleGeometry, StoredDyadic.Work numericWork) {
        this.center = Objects.requireNonNull(center, "center");
        this.attitude = Objects.requireNonNull(attitude, "attitude");
        this.shape = Objects.requireNonNull(shape, "shape");
        if (shape instanceof PlayerBodyShape.Cardinal cardinal) {
            Vec3d difference = attitude.basisY().subtract(cardinal.frame().up());
            if (Math.max(Math.abs(difference.x()), Math.max(Math.abs(difference.y()), Math.abs(difference.z())))
                    > CARDINAL_ATTITUDE_ERROR) {
                throw new IllegalArgumentException("cardinal body attitude must preserve the selected physical up");
            }
            if (actualCapsuleGeometry != null) {
                throw new IllegalArgumentException("cardinal placements cannot carry FreeCapsule geometry");
            }
            storedCapsuleGeometry = null;
        } else if (shape instanceof PlayerBodyShape.FreeCapsule free) {
            if (actualCapsuleGeometry == null) {
                StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, free.geometryDimensions(), numericWork);
                RealizationRelation nominal = RealizationRelation.nominal(limits);
                storedCapsuleGeometry = StoredCapsuleGeometry.fromRecipe(
                        center, attitude, free.geometryDimensions(), limits.nominalHalfLine(), nominal);
            } else {
                if (!actualCapsuleGeometry.matchesRecipe(center, attitude, free.geometryDimensions())) {
                    throw new IllegalArgumentException("accepted FreeCapsule geometry does not match placement recipe");
                }
                storedCapsuleGeometry = actualCapsuleGeometry;
            }
        } else {
            throw new IllegalArgumentException("unsupported player body shape");
        }
        bounds = actualBounds == null ? nominalBounds() : actualBounds;
        transport = actualTransport == null
                ? new Vec3d(center.x(), bounds.min().y(), center.z()) : actualTransport;
        if (shape instanceof PlayerBodyShape.FreeCapsule free) {
            var arithmetic = BootQueryArithmetic.fromSameWork(numericWork);
            freePhysicalFoot = physicalFoot(this, arithmetic);
            if (free.eyeAnchor().isPresent()) freeEye = free.eyeAnchor().orElseThrow().at(center, attitude, arithmetic);
            else {
                if (free.geometryDimensions() != free.dimensions())
                    throw new IllegalArgumentException("Independent physical profile requires its exact eye anchor");
                freeEye = eyeFromPhysicalFoot(freePhysicalFoot, attitude.basisY(), free.dimensions().eyeHeight(), arithmetic);
            }
        } else {
            freePhysicalFoot = null;
            freeEye = null;
        }
    }
    /** Pure metadata copy; callers still need trusted eye provenance and the owner commit fence. */
    private PlayerBodyPlacement(PlayerBodyPlacement source, double eyeHeight, StoredDyadic.Work numericWork) {
        if (!(source.shape instanceof PlayerBodyShape.FreeCapsule free))
            throw new IllegalArgumentException("Eye metadata copy requires an accepted Free capsule");
        var old = free.dimensions();
        var replacement = new PlayerBodyShape.Dimensions(old.width(), old.height(), eyeHeight);
        center = source.center;
        attitude = source.attitude;
        var arithmetic = BootQueryArithmetic.fromSameWork(Objects.requireNonNull(numericWork, "numericWork"));
        Optional<FreeEyeAnchor> replacementAnchor = free.eyeAnchor().map(anchor -> {
            var difference = arithmetic.subtract(arithmetic.exact(eyeHeight), arithmetic.exact(old.eyeHeight()));
            var up = attitude.basisY();
            var previousEye = source.freeEye;
            var observedEye = new Vec3d(
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(previousEye.x()), arithmetic.multiply(arithmetic.exact(up.x()), difference))),
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(previousEye.y()), arithmetic.multiply(arithmetic.exact(up.y()), difference))),
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(previousEye.z()), arithmetic.multiply(arithmetic.exact(up.z()), difference))));
            return new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center, observedEye, attitude);
        });
        shape = new PlayerBodyShape.FreeCapsule(replacement, free.collisionProfile(), replacementAnchor);
        bounds = source.bounds;
        transport = source.transport;
        storedCapsuleGeometry = source.storedCapsuleGeometry.withEyeMetadata(shape.geometryDimensions());
        freePhysicalFoot = source.freePhysicalFoot;
        freeEye = replacementAnchor.isPresent() ? replacementAnchor.orElseThrow().at(center, attitude, arithmetic)
                : eyeFromPhysicalFoot(freePhysicalFoot, attitude.basisY(), eyeHeight, arithmetic);
        if (!StoredRealizationLimits.sameRaw(old.width(), replacement.width())
                || !StoredRealizationLimits.sameRaw(old.height(), replacement.height())
                || storedCapsuleGeometry.capsule() != source.storedCapsuleGeometry.capsule()
                || !storedCapsuleGeometry.matchesRecipe(center, attitude, shape.geometryDimensions()))
            throw new IllegalStateException("Eye metadata changed accepted physical geometry");
    }

    PlayerBodyPlacement withEyeMetadata(double eyeHeight, StoredDyadic.Work numericWork) {
        return new PlayerBodyPlacement(this, eyeHeight, numericWork);
    }

    public Vec3d center() { return center; }
    public Rotation3d attitude() { return attitude; }
    public PlayerBodyShape shape() { return shape; }
    StoredCapsuleGeometry storedCapsuleGeometry() { return storedCapsuleGeometry; }

    /** Exact cardinal up, or the accepted quaternion axis for a free capsule. */
    public Vec3d bodyUp() {
        return shape instanceof PlayerBodyShape.Cardinal cardinal ? cardinal.frame().up() : attitude.basisY();
    }

    /** Actual cardinal collider or capsule broad-phase proxy, never a second capsule solver. */
    public Aabb bounds() {
        return bounds;
    }

    private Aabb nominalBounds() {
        if (shape instanceof PlayerBodyShape.Cardinal cardinal) {
            var dimensions = cardinal.dimensions();
            Vec3d signed = cardinal.frame().toWorld(new Vec3d(
                    dimensions.width() / 2, dimensions.height() / 2, dimensions.width() / 2));
            Vec3d half = new Vec3d(Math.abs(signed.x()), Math.abs(signed.y()), Math.abs(signed.z()));
            return new Aabb(center.subtract(half), center.add(half));
        }
        return storedCapsuleGeometry.capsule().bounds();
    }

    /**
     * Builds the real CHB capsule from the nominal dimensions and body axis.
     * In cardinal mode this is a proposed transition shape, NOT the installed
     * collider. Solver-rounded cardinal bounds need not contain this capsule;
     * a transition must independently verify containment/fit and its whole path.
     */
    public Capsule capsule() {
        if (shape instanceof PlayerBodyShape.FreeCapsule) return storedCapsuleGeometry.capsule();
        var dimensions = shape.dimensions();
        Vec3d halfLine = bodyUp().multiply(dimensions.centerlineHalfLength());
        return new Capsule(new Segment3d(center.subtract(halfLine), center.add(halfLine)), dimensions.radius());
    }
    public Optional<Vec3d> physicalFeet() {
        if (!(shape instanceof PlayerBodyShape.Cardinal cardinal)) return Optional.empty();
        return Optional.of(switch (cardinal.frame()) {
            case Y_POS -> new Vec3d(center.x(), bounds.min().y(), center.z());
            case Y_NEG -> new Vec3d(center.x(), bounds.max().y(), center.z());
            case X_POS -> new Vec3d(bounds.min().x(), center.y(), center.z());
            case X_NEG -> new Vec3d(bounds.max().x(), center.y(), center.z());
            case Z_POS -> new Vec3d(center.x(), center.y(), bounds.min().z());
            case Z_NEG -> new Vec3d(center.x(), center.y(), bounds.max().z());
        });
    }

    /** Rounded physical foot point from this accepted Free capsule's actual stored endpoint and radius. */
    Vec3d freePhysicalFoot() {
        if (freePhysicalFoot == null) throw new IllegalStateException("physical Free foot requires a FreeCapsule placement");
        return freePhysicalFoot;
    }

    public Vec3d eye() {
        return shape instanceof PlayerBodyShape.Cardinal
                ? physicalFeet().orElseThrow().add(bodyUp().multiply(shape.dimensions().eyeHeight()))
                : freeEye;
    }

    /** Reproduces the owner standing recipe with a caller-supplied shared numeric capability. */
    static Vec3d physicalFoot(PlayerBodyPlacement placement, BootQueryArithmetic arithmetic) {
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(arithmetic, "arithmetic");
        if (!(placement.shape instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("stored-endpoint physical foot requires a FreeCapsule placement");
        var lower = placement.storedCapsuleGeometry.negativeEndpoint();
        var up = placement.bodyUp();
        var radius = arithmetic.exact(placement.storedCapsuleGeometry.radius());
        return new Vec3d(
                arithmetic.roundNearest(arithmetic.subtract(arithmetic.exact(lower.x()),
                        arithmetic.multiply(arithmetic.exact(up.x()), radius))),
                arithmetic.roundNearest(arithmetic.subtract(arithmetic.exact(lower.y()),
                        arithmetic.multiply(arithmetic.exact(up.y()), radius))),
                arithmetic.roundNearest(arithmetic.subtract(arithmetic.exact(lower.z()),
                        arithmetic.multiply(arithmetic.exact(up.z()), radius))));
    }

    private static Vec3d eyeFromPhysicalFoot(Vec3d physicalFoot, Vec3d up, double eyeHeight,
                                              BootQueryArithmetic arithmetic) {
        var exactEyeHeight = arithmetic.exact(eyeHeight);
        return new Vec3d(
                arithmetic.roundNearest(arithmetic.add(arithmetic.exact(physicalFoot.x()),
                        arithmetic.multiply(arithmetic.exact(up.x()), exactEyeHeight))),
                arithmetic.roundNearest(arithmetic.add(arithmetic.exact(physicalFoot.y()),
                        arithmetic.multiply(arithmetic.exact(up.y()), exactEyeHeight))),
                arithmetic.roundNearest(arithmetic.add(arithmetic.exact(physicalFoot.z()),
                        arithmetic.multiply(arithmetic.exact(up.z()), exactEyeHeight))));
    }

    /** Compatibility mirror: native posX / proxy.minY / posZ, not physical feet. */
    public Vec3d transport() {
        return transport;
    }

    /** Resize proposal about the accepted foot face; collision and provenance remain with the owner. */
    public PlayerBodyPlacement proposeDimensionsAtFeet(PlayerBodyShape.Dimensions dimensions) {
        if (!(shape instanceof PlayerBodyShape.Cardinal cardinal))
            throw new IllegalStateException("foot-anchored resize requires a cardinal body");
        Vec3d feet = physicalFeet().orElseThrow();
        double r = dimensions.width() / 2;
        double h = dimensions.height();
        Aabb box = switch (cardinal.frame()) {
            case Y_POS -> new Aabb(new Vec3d(feet.x() - r, feet.y(), feet.z() - r),
                    new Vec3d(feet.x() + r, feet.y() + h, feet.z() + r));
            case Y_NEG -> new Aabb(new Vec3d(feet.x() - r, feet.y() - h, feet.z() - r),
                    new Vec3d(feet.x() + r, feet.y(), feet.z() + r));
            case X_POS -> new Aabb(new Vec3d(feet.x(), feet.y() - r, feet.z() - r),
                    new Vec3d(feet.x() + h, feet.y() + r, feet.z() + r));
            case X_NEG -> new Aabb(new Vec3d(feet.x() - h, feet.y() - r, feet.z() - r),
                    new Vec3d(feet.x(), feet.y() + r, feet.z() + r));
            case Z_POS -> new Aabb(new Vec3d(feet.x() - r, feet.y() - r, feet.z()),
                    new Vec3d(feet.x() + r, feet.y() + r, feet.z() + h));
            case Z_NEG -> new Aabb(new Vec3d(feet.x() - r, feet.y() - r, feet.z() - h),
                    new Vec3d(feet.x() + r, feet.y() + r, feet.z()));
        };
        Vec3d mirror = new Vec3d(box.center().x(), box.min().y(), box.center().z());
        return fromCardinalBounds(box, mirror, attitude, new PlayerBodyShape.Cardinal(cardinal.frame(), dimensions));
    }

    /** Existing 70/490 speed corridor uses accepted proxy-minY, not centre/eye/support Y. */
    public double corridorY() {
        return bounds().min().y();
    }

    /**
     * Decodes a transport mirror using the already selected shape/attitude.
     * The network owner must first verify the matching physical revision.
     * Binary64 round trips are approximate, especially at world-border coordinates.
     */
    public static PlayerBodyPlacement fromTransport(Vec3d transport, Rotation3d attitude, PlayerBodyShape shape) {
        var origin = new PlayerBodyPlacement(new Vec3d(0, 0, 0), attitude, shape);
        return new PlayerBodyPlacement(new Vec3d(transport.x(), transport.y() - origin.bounds().min().y(), transport.z()),
                attitude, shape);
    }

    /**
     * Retains owner-validated cardinal geometry, including all solver rounding.
     * The transport mirror is retained separately: native setPosition and
     * resetPositionToBB do not always round to the same tangent center.
     * This checks representability/anchor consistency, not the dimensions' live
     * provenance, world validity, collision, support or movement authority.
     */
    public static PlayerBodyPlacement fromCardinalBounds(Aabb actualBounds, Vec3d actualTransport,
                                                         Rotation3d attitude, PlayerBodyShape.Cardinal shape) {
        Objects.requireNonNull(actualBounds, "actualBounds");
        Objects.requireNonNull(actualTransport, "actualTransport");
        Objects.requireNonNull(shape, "shape");
        Vec3d min = actualBounds.min();
        Vec3d max = actualBounds.max();
        if (!(min.x() < max.x() && min.y() < max.y() && min.z() < max.z())) {
            throw new IllegalArgumentException("installed cardinal bounds must have positive extents");
        }
        if (actualTransport.y() != min.y() || actualTransport.x() < min.x() || actualTransport.x() > max.x()
                || actualTransport.z() < min.z() || actualTransport.z() > max.z()) {
            throw new IllegalArgumentException("cardinal transport must mirror minY and lie inside tangent extents");
        }
        return new PlayerBodyPlacement(actualBounds.center(), attitude, shape, actualBounds, actualTransport, null);
    }

    /** A proposed stored recipe, without accepted-state, collision, or installation authority. */
    static PlayerBodyPlacement proposedFromStoredGeometry(Vec3d center, Rotation3d attitude,
            PlayerBodyShape.FreeCapsule shape, StoredCapsuleGeometry geometry, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(geometry); Objects.requireNonNull(sameWork);
        if (!geometry.relation().limits().usesWork(sameWork))
            throw new IllegalArgumentException("proposed stored recipe requires the same numeric Work");
        return new PlayerBodyPlacement(center, attitude, shape, null, null, geometry, sameWork);
    }

    /**
     * Retains owner-validated FreeCapsule geometry and native proxy mirrors.
     * The numeric relation is not collision, support, path, or commit permission.
     */
    static PlayerBodyPlacement fromAcceptedFreeGeometry(
            Vec3d center, Rotation3d attitude, PlayerBodyShape.FreeCapsule shape,
            StoredCapsuleGeometry actualGeometry, Aabb actualProxy, Vec3d actualTransport) {
        return fromAcceptedFreeGeometry(center, attitude, shape, actualGeometry, actualProxy, actualTransport,
                new StoredDyadic.Work());
    }

    static PlayerBodyPlacement fromAcceptedFreeGeometry(
            Vec3d center, Rotation3d attitude, PlayerBodyShape.FreeCapsule shape,
            StoredCapsuleGeometry actualGeometry, Aabb actualProxy, Vec3d actualTransport,
            StoredDyadic.Work numericWork) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(attitude, "attitude");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(actualGeometry, "actualGeometry");
        Objects.requireNonNull(actualProxy, "actualProxy");
        Objects.requireNonNull(actualTransport, "actualTransport");
        if (!actualGeometry.matchesRecipe(center, attitude, shape.geometryDimensions())) {
            throw new IllegalArgumentException("accepted FreeCapsule geometry does not match placement recipe");
        }
        Vec3d min = actualProxy.min();
        Vec3d max = actualProxy.max();
        StoredRealizationLimits.requirePoint(min, "accepted FreeCapsule proxy minimum");
        StoredRealizationLimits.requirePoint(max, "accepted FreeCapsule proxy maximum");
        if (!(min.x() < max.x() && min.y() < max.y() && min.z() < max.z())) {
            throw new IllegalArgumentException("accepted FreeCapsule proxy must have positive finite extents");
        }
        StoredRealizationLimits.requirePoint(actualTransport, "accepted FreeCapsule transport");
        Aabb capsuleBounds = actualGeometry.capsule().bounds();
        if (min.x() > capsuleBounds.min().x() || min.y() > capsuleBounds.min().y()
                || min.z() > capsuleBounds.min().z() || max.x() < capsuleBounds.max().x()
                || max.y() < capsuleBounds.max().y() || max.z() < capsuleBounds.max().z()) {
            throw new IllegalArgumentException("accepted FreeCapsule proxy does not contain all actual capsule bounds");
        }
        if (!StoredRealizationLimits.sameRaw(actualTransport.y(), min.y())
                || actualTransport.x() < min.x() || actualTransport.x() > max.x()
                || actualTransport.z() < min.z() || actualTransport.z() > max.z()) {
            throw new IllegalArgumentException("accepted FreeCapsule transport must mirror proxy minY and lie inside it");
        }
        return new PlayerBodyPlacement(center, attitude, shape, actualProxy, actualTransport, actualGeometry, numericWork);
    }

    /**
     * Treats the accepted Free capsule's stored physical foot as a desired anchor and builds a
     * bounded outward native-width Cardinal box. Its actual AABB midpoint and selected native
     * profile define the realizable target; the owner must prove and charge any resulting center
     * correction as a real Free translation before the attitude arc.
     */
    static PlayerBodyPlacement cardinalFromAcceptedFreeDesiredFoot(PlayerBodyPlacement acceptedFree,
            CardinalFrame frame, PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work numeric) {
        Objects.requireNonNull(acceptedFree, "acceptedFree");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(numeric, "numeric");
        if (!(acceptedFree.shape instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("Cardinal desired foot must come from the accepted Free capsule");
        BootQueryArithmetic arithmetic = BootQueryArithmetic.fromSameWork(numeric);
        Vec3d foot = physicalFoot(acceptedFree, arithmetic);
        BootQueryArithmetic.Number[] low = new BootQueryArithmetic.Number[3];
        BootQueryArithmetic.Number[] high = new BootQueryArithmetic.Number[3];
        for (int axis = 0; axis < 3; axis++) {
            numeric.charge(2);
            var anchor = arithmetic.exact(component(foot, axis));
            var radius = arithmetic.exact(dimensions.radius());
            low[axis] = arithmetic.exact(arithmetic.roundDown(arithmetic.subtract(anchor, radius)));
            high[axis] = arithmetic.exact(arithmetic.roundUp(arithmetic.add(anchor, radius)));
        }
        int normalAxis = switch (frame) {
            case X_POS, X_NEG -> 0;
            case Y_POS, Y_NEG -> 1;
            case Z_POS, Z_NEG -> 2;
        };
        boolean positive = switch (frame) {
            case X_POS, Y_POS, Z_POS -> true;
            case X_NEG, Y_NEG, Z_NEG -> false;
        };
        numeric.charge(2);
        var anchor = arithmetic.exact(component(foot, normalAxis));
        var extent = arithmetic.exact(dimensions.height());
        if (positive) {
            low[normalAxis] = anchor;
            high[normalAxis] = arithmetic.exact(arithmetic.roundUp(arithmetic.add(anchor, extent)));
        } else {
            low[normalAxis] = arithmetic.exact(arithmetic.roundDown(arithmetic.subtract(anchor, extent)));
            high[normalAxis] = anchor;
        }
        double[] minimum = {arithmetic.roundDown(low[0]), arithmetic.roundDown(low[1]),
                arithmetic.roundDown(low[2])};
        double[] maximum = {arithmetic.roundUp(high[0]), arithmetic.roundUp(high[1]),
                arithmetic.roundUp(high[2])};
        for (int axis = 0; axis < 3; axis++) {
            if (axis == normalAxis) continue;
            double[] centered = preserveNativeTangentCenter(minimum[axis], maximum[axis],
                    component(foot, axis), dimensions.width(), numeric);
            minimum[axis] = centered[0];
            maximum[axis] = centered[1];
        }
        Vec3d minimumPoint = new Vec3d(minimum[0], minimum[1], minimum[2]);
        Vec3d maximumPoint = new Vec3d(maximum[0], maximum[1], maximum[2]);
        Aabb exactBounds = new Aabb(minimumPoint, maximumPoint);
        Vec3d center = exactBounds.center();
        Vec3d transport = new Vec3d(center.x(), minimumPoint.y(), center.z());
        PlayerBodyPlacement target = fromCardinalBounds(exactBounds, transport, frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, dimensions));
        return target;
    }

    /**
     * Selects a local pair of outward native-width bounds whose actual CHB midpoint is the
     * closest representable midpoint to the requested foot. Native-width addition can round
     * asymmetrically (for example near x=0.1). Only the original pair and its immediate outward
     * neighbors are considered; the selected pair must enclose the original outward interval and
     * its exact extent must lie between the native width and the exact outermost-neighbor recipe
     * extent. This admits coordinate rounding across binades without authorizing arbitrary wider boxes.
     * The installed native width remains the exact float-derived profile value. Any remaining
     * midpoint difference is owner movement.
     */
    private static double[] preserveNativeTangentCenter(double minimum, double maximum,
            double foot, double nativeWidth, StoredDyadic.Work numeric) {
        double lowerNeighbor = Math.nextDown(minimum);
        double upperNeighbor = Math.nextUp(maximum);
        numeric.charge(2);
        double[][] candidates = {
                {minimum, maximum},
                {lowerNeighbor, maximum},
                {minimum, upperNeighbor},
                {lowerNeighbor, upperNeighbor}
        };
        double[] closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        if (!Double.isFinite(lowerNeighbor) || !Double.isFinite(upperNeighbor))
            throw new IllegalArgumentException("native Cardinal tangent neighbors are non-finite");
        // Width ULPs cannot bound world-coordinate directed rounding (e.g. a tangent
        // interval straddling -16). Bound only this finite four-candidate recipe.
        var width = StoredDyadic.of(nativeWidth, numeric);
        var radius = StoredDyadic.of(nativeWidth / 2, numeric);
        if (radius.scalePow2(1, numeric).compare(width, numeric) != 0)
            throw new IllegalArgumentException("native Cardinal tangent radius does not reproduce its exact width");
        var anchor = StoredDyadic.of(foot, numeric);
        if (!StoredRealizationLimits.sameRaw(minimum, anchor.subtract(radius, numeric).roundDown(numeric))
                || !StoredRealizationLimits.sameRaw(maximum, anchor.add(radius, numeric).roundUp(numeric)))
            throw new IllegalArgumentException("native Cardinal tangent bounds differ from their directed foot recipe");
        var maximumOutwardExtent = StoredDyadic.of(upperNeighbor, numeric)
                .subtract(StoredDyadic.of(lowerNeighbor, numeric), numeric);
        if (maximumOutwardExtent.compare(width, numeric) < 0)
            throw new IllegalArgumentException("native Cardinal tangent recipe does not enclose its exact width");
        for (double[] candidate : candidates) {
            numeric.charge(5);
            double extent = candidate[1] - candidate[0];
            if (!Double.isFinite(extent) || extent <= 0
                    || candidate[0] > minimum || candidate[1] < maximum) continue;
            var exactExtent = StoredDyadic.of(candidate[1], numeric)
                    .subtract(StoredDyadic.of(candidate[0], numeric), numeric);
            if (exactExtent.compare(width, numeric) < 0
                    || exactExtent.compare(maximumOutwardExtent, numeric) > 0) continue;
            double actualCenter = (candidate[0] + candidate[1]) / 2.0;
            double distance = Math.abs(actualCenter - foot);
            if (!Double.isFinite(distance)) throw new IllegalArgumentException(
                    "native Cardinal desired-anchor correction is non-finite");
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        if (closest == null) throw new IllegalArgumentException(
                "native Cardinal width has no bounded outward realization at the accepted Free desired foot");
        return closest;
    }

    private static double component(Vec3d value, int axis) {
        return switch (axis) {
            case 0 -> value.x();
            case 1 -> value.y();
            case 2 -> value.z();
            default -> throw new IllegalArgumentException("axis must be 0..2");
        };
    }

    /** Fixed-Q and fixed-size geometric translation; this does not authorize the path. */
    PlayerBodyPlacement withTranslatedCenter(Vec3d translatedCenter) {
        return withTranslatedCenter(translatedCenter, new StoredDyadic.Work());
    }

    PlayerBodyPlacement withTranslatedCenter(Vec3d translatedCenter, StoredDyadic.Work numericWork) {
        Objects.requireNonNull(translatedCenter, "translatedCenter");
        if (StoredRealizationLimits.sameRaw(center, translatedCenter)) return this;
        Vec3d delta = translatedCenter.subtract(center);
        Aabb translatedBounds = new Aabb(bounds.min().add(delta), bounds.max().add(delta));
        Vec3d translatedTransport = transport.add(delta);
        if (shape instanceof PlayerBodyShape.FreeCapsule) {
            StoredCapsuleGeometry geometry = storedCapsuleGeometry.withTranslatedCenter(translatedCenter);
            // Keep the owner's translated margin while covering the newly rounded stored geometry.
            Aabb translatedProxy = unionBounds(translatedBounds, geometry.capsule().bounds());
            // Native FreeCapsule transport mirrors the final proxy bottom exactly.
            Vec3d freeTransport = new Vec3d(translatedTransport.x(), translatedProxy.min().y(),
                    translatedTransport.z());
            return new PlayerBodyPlacement(translatedCenter, attitude, shape,
                    translatedProxy, freeTransport, geometry, numericWork);
        }
        return new PlayerBodyPlacement(translatedCenter, attitude, shape, translatedBounds, translatedTransport, null);
    }

    private static Aabb unionBounds(Aabb first, Aabb second) {
        return new Aabb(new Vec3d(Math.min(first.min().x(), second.min().x()),
                        Math.min(first.min().y(), second.min().y()),
                        Math.min(first.min().z(), second.min().z())),
                new Vec3d(Math.max(first.max().x(), second.max().x()),
                        Math.max(first.max().y(), second.max().y()),
                        Math.max(first.max().z(), second.max().z())));
    }

    /** Same-shape proposal only. The snapshot validates scope/revision before calling this method. */
    PlayerBodyPlacement proposalFromTransport(Vec3d proposedTransport) {
        Objects.requireNonNull(proposedTransport, "proposedTransport");
        if (transport.equals(proposedTransport)) return this;
        if (!(shape instanceof PlayerBodyShape.Cardinal cardinal)) {
            Vec3d delta = proposedTransport.subtract(transport);
            PlayerBodyPlacement translated = withTranslatedCenter(center.add(delta));
            Vec3d finalTransport = new Vec3d(proposedTransport.x(), translated.bounds.min().y(),
                    proposedTransport.z());
            return new PlayerBodyPlacement(translated.center, translated.attitude, translated.shape,
                    translated.bounds, finalTransport, translated.storedCapsuleGeometry);
        }
        Vec3d offset = proposedTransport.subtract(transport);
        Aabb proposedBounds = new Aabb(bounds.min().add(offset), bounds.max().add(offset));
        // This is the mirror a native reset would emit for the proposed box;
        // rounding may differ from the incoming request. Admission stays with
        // the movement owner, and centerDelta remains a center difference, not
        // an interchangeable native AABB offset or new movement credit.
        Vec3d resetTransport = new Vec3d((proposedBounds.min().x() + proposedBounds.max().x()) / 2,
                proposedBounds.min().y(), (proposedBounds.min().z() + proposedBounds.max().z()) / 2);
        return fromCardinalBounds(proposedBounds, resetTransport, attitude, cardinal);
    }

    /** Centre translation only; body rotation and transport-offset changes are not displacement credit. */
    public Vec3d translationTo(PlayerBodyPlacement target) {
        return target.center.subtract(center);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayerBodyPlacement that && center.equals(that.center) && attitude.equals(that.attitude)
                && shape.equals(that.shape) && Objects.equals(storedCapsuleGeometry, that.storedCapsuleGeometry)
                && bounds.equals(that.bounds) && transport.equals(that.transport);
    }

    @Override
    public int hashCode() {
        return Objects.hash(center, attitude, shape, storedCapsuleGeometry, bounds, transport);
    }

    @Override
    public String toString() {
        return "PlayerBodyPlacement[center=" + center + ", attitude=" + attitude + ", shape=" + shape
                + ", storedCapsuleGeometry=" + storedCapsuleGeometry + ", bounds=" + bounds
                + ", transport=" + transport + "]";
    }
}
