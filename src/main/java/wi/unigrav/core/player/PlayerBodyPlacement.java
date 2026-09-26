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

    /** Constructs a proposed shape from nominal dimensions; not a copy of an installed collider. */
    public PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape) {
        this(center, attitude, shape, null, null);
    }

    private PlayerBodyPlacement(Vec3d center, Rotation3d attitude, PlayerBodyShape shape,
                                Aabb actualBounds, Vec3d actualTransport) {
        this.center = Objects.requireNonNull(center, "center");
        this.attitude = Objects.requireNonNull(attitude, "attitude");
        this.shape = Objects.requireNonNull(shape, "shape");
        if (shape instanceof PlayerBodyShape.Cardinal cardinal) {
            Vec3d difference = attitude.basisY().subtract(cardinal.frame().up());
            if (Math.max(Math.abs(difference.x()), Math.max(Math.abs(difference.y()), Math.abs(difference.z())))
                    > CARDINAL_ATTITUDE_ERROR) {
                throw new IllegalArgumentException("cardinal body attitude must preserve the selected physical up");
            }
        }
        bounds = actualBounds == null ? nominalBounds() : actualBounds;
        transport = actualTransport == null ? new Vec3d(center.x(), bounds.min().y(), center.z()) : actualTransport;
    }

    public Vec3d center() { return center; }
    public Rotation3d attitude() { return attitude; }
    public PlayerBodyShape shape() { return shape; }

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
        return capsule().bounds();
    }

    /**
     * Builds the real CHB capsule from the nominal dimensions and body axis.
     * In cardinal mode this is a proposed transition shape, NOT the installed
     * collider. Solver-rounded cardinal bounds need not contain this capsule;
     * a transition must independently verify containment/fit and its whole path.
     */
    public Capsule capsule() {
        var dimensions = shape.dimensions();
        Vec3d halfLine = bodyUp().multiply(dimensions.centerlineHalfLength());
        return new Capsule(new Segment3d(center.subtract(halfLine), center.add(halfLine)), dimensions.radius());
    }

    /** Standing feet are undefined for unconstrained capsule geometry. */
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

    public Vec3d eye() {
        return shape instanceof PlayerBodyShape.Cardinal
                ? physicalFeet().orElseThrow().add(bodyUp().multiply(shape.dimensions().eyeHeight()))
                : center.add(bodyUp().multiply(shape.dimensions().eyeOffsetFromCenter()));
    }

    /** Compatibility mirror: native posX / proxy.minY / posZ, not physical feet. */
    public Vec3d transport() {
        return transport;
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
        return new PlayerBodyPlacement(actualBounds.center(), attitude, shape, actualBounds, actualTransport);
    }

    /** Same-shape proposal only. The snapshot validates scope/revision before calling this method. */
    PlayerBodyPlacement proposalFromTransport(Vec3d proposedTransport) {
        Objects.requireNonNull(proposedTransport, "proposedTransport");
        if (transport.equals(proposedTransport)) return this;
        if (!(shape instanceof PlayerBodyShape.Cardinal cardinal)) {
            return fromTransport(proposedTransport, attitude, shape);
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
                && shape.equals(that.shape) && bounds.equals(that.bounds) && transport.equals(that.transport);
    }

    @Override
    public int hashCode() {
        return Objects.hash(center, attitude, shape, bounds, transport);
    }

    @Override
    public String toString() {
        return "PlayerBodyPlacement[center=" + center + ", attitude=" + attitude + ", shape=" + shape
                + ", bounds=" + bounds + ", transport=" + transport + "]";
    }
}
