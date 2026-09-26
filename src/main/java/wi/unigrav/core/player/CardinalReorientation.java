package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;

/**
 * One owner-scheduled cardinal standing-reference change (a body reorientation).
 *
 * <p>A reorientation is a physical state change, not a camera or model rotation: the
 * accepted collider, the native transport mirror, the selected standing reference and
 * the authoritative snapshot must change together, in one server-thread transaction.
 *
 * <p>The body centre is preserved exactly, so a reorientation never grants displacement
 * credit. Only the transport mirror changes, because the collider's world extents change
 * with the frame; the one movement budget is rebased onto that new anchor in the same
 * tick and therefore mints nothing.
 *
 * <p>Every world query is read-only and runs before the single install step, so a rejected
 * change leaves the whole previous state intact and no half-installed frame/bounds pair can
 * be observed. The rotation path is validated with the smallest axis-aligned envelope that
 * contains the swept capsule; that envelope is conservative for the swept solid but exact as
 * a bounding box, so a genuinely reachable stand near ordinary floors still reorients.
 */
public final class CardinalReorientation {
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final CardinalMovementResolver.Limits MOVE =
            new CardinalMovementResolver.Limits(512, 16384, 100000);
    /** Read-only contact reach for the post-install support re-query; not a movement step. */
    private static final double SUPPORT_PROBE_LOCAL_DELTA = -1.0e-4;

    public enum Reason {
        SAME_FRAME,
        UNSUPPORTED_SHAPE,
        ACTOR_CHANGED,
        TARGET_OCCUPIED,
        PATH_BLOCKED,
        WORLD_UNAVAILABLE,
        INSTALL_REJECTED
    }

    public sealed interface Outcome permits Installed, Rejected { }

    /**
     * The whole new physical state. {@code onGround} is re-derived from a real zero-gap
     * contact at the installed geometry; no previous support is inherited.
     */
    public record Installed(PlayerPhysicalSnapshot snapshot, CardinalFrame frame, boolean onGround)
            implements Outcome { }

    public record Rejected(Reason reason, String detail) implements Outcome { }

    private CardinalReorientation() { }

    /**
     * Attempts to adopt {@code target} as the standing reference for this actor.
     *
     * <p>Caller keeps ownership of the session, the world epoch and the single movement
     * budget. This method never creates a budget, never refunds credit and never publishes
     * a packet; it returns the exact state the caller must commit.
     */
    public static Outcome adopt(WorldServer world, EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
                                CardinalFrame target, BoostMovementBudget budget, long budgetTick) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(budget, "budget");
        if (!world.isCallingFromMinecraftThread())
            throw new IllegalStateException("cardinal reorientation requires the server thread");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal shape))
            return new Rejected(Reason.UNSUPPORTED_SHAPE, "only an installed cardinal body may reorient");
        if (shape.frame() == target)
            return new Rejected(Reason.SAME_FRAME, "target already is the accepted standing reference");
        // Native callbacks may legitimately have relocated or killed the actor; never force a frame onto that.
        if (actor.world != world || actor.isDead || actor.noClip || actor.isRiding() || actor.isPlayerSleeping()
                || actor.isSpectator() || ((Entity) actor).isInWeb)
            return new Rejected(Reason.ACTOR_CHANGED, "native actor state forbids a physical reorientation");
        PlayerBodyShape.Dimensions dimensions = shape.dimensions();
        if (actor.width != dimensions.width() || actor.height != dimensions.height())
            return new Rejected(Reason.ACTOR_CHANGED, "native dimensions differ from the accepted shape");
        // Same canonical CHB comparison the collision entry guard uses, not a rebuilt nominal box.
        if (!toChb(actor.getEntityBoundingBox()).equals(accepted.placement().bounds())
                || !position(actor).equals(accepted.placement().transport()))
            return new Rejected(Reason.ACTOR_CHANGED, "native actor differs from the accepted placement");
        Vec3d center = accepted.placement().center();
        var targetShape = new PlayerBodyShape.Cardinal(target, dimensions);
        // Same centre, same dimensions: only the frame changes. This is a transition
        // proposal; it authorizes nothing until the world queries below accept it.
        var proposed = new PlayerBodyPlacement(center, target.attitude(), targetShape);
        Aabb targetBounds = proposed.bounds();
        Aabb pathEnvelope = rotationSweepEnvelope(center, shape.frame().up(), target.up(),
                dimensions.height(), dimensions.width());

        // Target occupancy first: a more specific diagnosis than a blocked path.
        var occupied = occupancy(world, actor, targetBounds);
        if (occupied.isPresent()) {
            CardinalReorientation.Rejected rejection = occupied.orElseThrow();
            return rejection.reason() == Reason.WORLD_UNAVAILABLE
                    ? rejection : new Rejected(Reason.TARGET_OCCUPIED, rejection.detail());
        }
        // The whole swept rotation is inside its own bounding box, which also contains the target box.
        var blocked = occupancy(world, actor, pathEnvelope);
        if (blocked.isPresent()) {
            CardinalReorientation.Rejected rejection = blocked.orElseThrow();
            return rejection.reason() == Reason.WORLD_UNAVAILABLE
                    ? rejection : new Rejected(Reason.PATH_BLOCKED, rejection.detail());
        }

        Geometry saved = Geometry.of(actor);
        if (!install(actor, targetBounds))
            return new Rejected(Reason.INSTALL_REJECTED, "native collider refused the proposed cardinal bounds");
        var installed = PlayerBodyPlacement.fromCardinalBounds(
                toChb(actor.getEntityBoundingBox()), position(actor), target.attitude(), targetShape);
        var installedSnapshot = new PlayerPhysicalSnapshot(accepted.scope(),
                Math.incrementExact(accepted.revision()), installed, accepted.worldVelocity(), accepted.contacts());
        Optional<Boolean> support = probeSupport(world, actor, installedSnapshot, target);
        if (support.isEmpty()) {
            // Fail closed on the ground fact: restore the exact previous geometry, never a half frame.
            saved.restore(actor);
            return new Rejected(Reason.WORLD_UNAVAILABLE, "reorientation support could not be re-derived");
        }
        boolean onGround = support.orElseThrow();
        var contacts = accepted.contacts();
        // This tick performed no movement: keep the previous collision/fall facts and only re-derive support.
        var snapshot = new PlayerPhysicalSnapshot(accepted.scope(), Math.incrementExact(accepted.revision()),
                installed, accepted.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(onGround,
                contacts.map(PlayerPhysicalSnapshot.Contacts::horizontal).orElse(false),
                contacts.map(PlayerPhysicalSnapshot.Contacts::vertical).orElse(false),
                contacts.map(PlayerPhysicalSnapshot.Contacts::fallDistance).orElse(0.0F),
                contacts.map(PlayerPhysicalSnapshot.Contacts::nearFootLocomotion).orElse(false))));
        // Anchor rebase, not displacement: the centre did not move, so no credit may be minted or consumed.
        Vec3d transport = installed.transport();
        budget.rebase(new Vec3(transport.x(), transport.y(), transport.z()), budgetTick);
        return new Installed(snapshot, target, onGround);
    }

    /**
     * Smallest axis-aligned box containing the capsule swept while its long axis rotates
     * from {@code fromUp} to {@code toUp} about {@code center}.
     *
     * <p>The capsule is a segment Minkowski-summed with a ball, so the swept solid is the
     * swept centreline inflated by the capsule radius. For each world axis the centreline
     * support is {@code R * max |up(theta) . e|}, solved exactly rather than sampled.
     *
     * <p>Expressed as {@code amplitude * height/2 + (1 - amplitude) * width/2} so that the
     * extent along a fully swept axis is bit-identical to the accepted collider's own half
     * height. The feet face must stay exactly tangent to the surface the body already stands
     * on; a one-ulp overshoot would make ordinary floors reject every reorientation.
     */
    static Aabb rotationSweepEnvelope(Vec3d center, Vec3d fromUp, Vec3d toUp, double height, double width) {
        return rotationSweepEnvelope(center, fromUp, toUp, height, width, null);
    }

    /**
     * Same envelope, but with the standing axis taken verbatim from the accepted collider.
     *
     * <p>In the world the accepted centre is itself derived as {@code (minY + maxY) / 2} from the
     * collider a real solver left behind, so recomputing {@code centre - height/2} can land one ulp
     * on either side of the face the body actually rests on. Landing below it would make the floor
     * the body is standing on intersect the envelope and reject every reorientation, so the standing
     * axis is copied from the accepted collider instead of reconstructed. That axis sweeps through
     * its own start orientation, so its swept extent is exactly the collider's extent there and no
     * coverage is lost.
     *
     * <p>Deliberate trade-off: the capsule's own rounded exterior length
     * ({@code centerlineHalfLength + radius}) is mathematically {@code height/2} but is one ulp
     * larger in binary64 at the real player profile. Sizing from it would put the envelope one
     * ulp below the feet plane and break the tangency above, so the envelope is sized from the
     * accepted collider instead and under-covers the capsule's rounded bounding box by that one
     * ulp (~1e-16 m, versus 1/16-block collision granularity). Certified capsule geometry stays
     * with CHB; this envelope is a broad-phase occupancy bound, not a penetration proof.
     */
    static Aabb rotationSweepEnvelope(Vec3d center, Vec3d fromUp, Vec3d toUp, double height, double width,
                                      Aabb standingBounds) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(fromUp, "fromUp");
        Objects.requireNonNull(toUp, "toUp");
        if (!(width > 0) || !(height >= width) || !Double.isFinite(height) || !Double.isFinite(width))
            throw new IllegalArgumentException("invalid capsule sweep extents");
        double halfHeight = height / 2;
        double radius = width / 2;
        Vec3d start = unit(fromUp);
        Vec3d end = unit(toUp);
        double sweep = Math.acos(clamp(start.dot(end), -1.0, 1.0));
        Vec3d axis = rotationAxis(start, end);
        Vec3d tangent = axis.cross(start);
        int standingAxis = signedAxisIndex(start);
        double[] lower = new double[3];
        double[] upper = new double[3];
        for (int index = 0; index < 3; index++) {
            if (standingBounds != null && index == standingAxis) {
                lower[index] = component(standingBounds.min(), index);
                upper[index] = component(standingBounds.max(), index);
                continue;
            }
            double amplitude = arcSupport(component(start, index), component(tangent, index), sweep);
            double extent = amplitude * halfHeight + (1 - amplitude) * radius;
            double middle = component(center, index);
            lower[index] = middle - extent;
            upper[index] = middle + extent;
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]), new Vec3d(upper[0], upper[1], upper[2]));
    }

    /** Index of the single nonzero component of a signed cardinal axis. */
    private static int signedAxisIndex(Vec3d unit) {
        if (unit.x() != 0) return 0;
        if (unit.y() != 0) return 1;
        return 2;
    }

    /**
     * Rotation axis of the shortest path between two signed cardinal axes. A 180 degree
     * flip has no unique axis; a fixed perpendicular axis keeps the envelope deterministic.
     */
    private static Vec3d rotationAxis(Vec3d start, Vec3d end) {
        Vec3d cross = start.cross(end);
        if (cross.lengthSquared() > 1.0e-18) return unit(cross);
        double[] components = { Math.abs(start.x()), Math.abs(start.y()), Math.abs(start.z()) };
        int smallest = 0;
        for (int index = 1; index < 3; index++) if (components[index] < components[smallest]) smallest = index;
        return switch (smallest) {
            case 0 -> new Vec3d(1, 0, 0);
            case 1 -> new Vec3d(0, 1, 0);
            default -> new Vec3d(0, 0, 1);
        };
    }

    private static Vec3d unit(Vec3d vector) {
        double lengthSquared = vector.lengthSquared();
        if (!(lengthSquared > 0) || !Double.isFinite(lengthSquared))
            throw new IllegalArgumentException("a cardinal up axis must be a finite nonzero vector");
        return vector.multiply(1.0 / Math.sqrt(lengthSquared));
    }

    /** {@code max |a cos t + b sin t|} for {@code t} in {@code [0, sweep]}. */
    private static double arcSupport(double along, double across, double sweep) {
        double amplitude = Math.hypot(along, across);
        if (amplitude == 0.0) return 0.0;
        double atEnd = Math.abs(along * Math.cos(sweep) + across * Math.sin(sweep));
        double base = Math.abs(along);
        double phase = Math.atan2(across, along);
        // cos has unit magnitude at phase + k*pi; fold that into one period.
        double folded = phase - Math.PI * Math.floor(phase / Math.PI);
        return folded <= sweep + 1.0e-12 ? amplitude : Math.max(base, atEnd);
    }

    private static Optional<Rejected> occupancy(WorldServer world, Entity actor, Aabb requested) {
        var captured = WorldCollisionCandidates.capture(world, actor, requested,
                CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, SCAN);
        if (captured instanceof WorldCollisionCandidates.Unavailable failure)
            return Optional.of(new Rejected(Reason.WORLD_UNAVAILABLE,
                    failure.reason() + ": " + failure.detail()));
        if (!((WorldCollisionCandidates.Snapshot) captured).boxes().isEmpty())
            return Optional.of(new Rejected(Reason.TARGET_OCCUPIED, "world geometry intersects the requested space"));
        return Optional.empty();
    }

    /**
     * Read-only zero-gap contact probe in the selected frame, using the same strict native
     * query and the same rule as the movement tail: a nonzero gap is not support.
     */
    private static Optional<Boolean> probeSupport(WorldServer world, EntityPlayerMP actor,
                                                  PlayerPhysicalSnapshot installed, CardinalFrame frame) {
        var request = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                frame.toWorld(new Vec3d(0, SUPPORT_PROBE_LOCAL_DELTA, 0)), MoverType.PLAYER, false, false, 0);
        var support = WorldCollisionCandidates.resolveCardinal(world, actor, installed, request, SCAN, MOVE);
        if (support.worldFailure().isPresent()) return Optional.empty();
        if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
            return Optional.empty();
        return Optional.of(contact.flags().onGround() && contact.nativeResolvedLocalDelta().y() == 0);
    }

    private static boolean install(EntityPlayerMP actor, Aabb bounds) {
        AxisAlignedBB installed = toNative(bounds);
        actor.setEntityBoundingBox(installed);
        if (!sameBox(actor.getEntityBoundingBox(), installed)) return false;
        // Explicit mirror instead of setPosition: a nominal rebuild would discard the physical box.
        var reset = new Vec3d((boundMin(installed, 0) + boundMax(installed, 0)) / 2,
                boundMin(installed, 1), (boundMin(installed, 2) + boundMax(installed, 2)) / 2);
        actor.posX = reset.x();
        actor.posY = reset.y();
        actor.posZ = reset.z();
        return true;
    }

    /** CHB geometry is the physical truth; only the native collider is built from it here. */
    private static AxisAlignedBB toNative(Aabb bounds) {
        return new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
    }

    private static Aabb toChb(AxisAlignedBB bounds) {
        return new Aabb(new Vec3d(bounds.minX, bounds.minY, bounds.minZ),
                new Vec3d(bounds.maxX, bounds.maxY, bounds.maxZ));
    }

    private static double boundMin(AxisAlignedBB box, int axis) {
        return switch (axis) {
            case 0 -> box.minX;
            case 1 -> box.minY;
            default -> box.minZ;
        };
    }

    private static double boundMax(AxisAlignedBB box, int axis) {
        return switch (axis) {
            case 0 -> box.maxX;
            case 1 -> box.maxY;
            default -> box.maxZ;
        };
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }

    private static Vec3d position(EntityPlayerMP actor) {
        return new Vec3d(actor.posX, actor.posY, actor.posZ);
    }

    private static boolean sameBox(AxisAlignedBB first, AxisAlignedBB second) {
        return first.minX == second.minX && first.minY == second.minY && first.minZ == second.minZ
                && first.maxX == second.maxX && first.maxY == second.maxY && first.maxZ == second.maxZ;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return value < minimum ? minimum : Math.min(value, maximum);
    }

    /** Exact native geometry before an install, so a rejected transition restores it verbatim. */
    private record Geometry(AxisAlignedBB bounds, double x, double y, double z) {
        private static Geometry of(EntityPlayerMP actor) {
            return new Geometry(actor.getEntityBoundingBox(), actor.posX, actor.posY, actor.posZ);
        }

        private void restore(EntityPlayerMP actor) {
            actor.setEntityBoundingBox(bounds);
            actor.posX = x;
            actor.posY = y;
            actor.posZ = z;
            if (!sameBox(actor.getEntityBoundingBox(), bounds) || actor.posX != x || actor.posY != y || actor.posZ != z)
                throw new IllegalStateException("cardinal reorientation could not restore the rejected geometry");
        }
    }
}
