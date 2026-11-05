package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Current-world adapter for the existing capsule solver. No history or server round trip. */
public final class LocalCapsuleMovement {
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private LocalCapsuleMovement() { }
    public record Result(PlayerBodyPlacement placement, Vec3d displacement, Vec3d velocity,
                         boolean blockedX, boolean blockedY, boolean blockedZ) { }

    public static Result move(EntityPlayer actor, PlayerPhysicalSnapshot current, Rotation3d attitude, Vec3d delta,
            Vec3d incomingVelocity,
            Function<AxisAlignedBB, List<AxisAlignedBB>> currentCollisions, Runnable requireClientCurrent) {
        var context = new Calculation(actor, current, currentCollisions, requireClientCurrent);
        var numeric = context.numeric();
        var start = current.placement();
        var pose = CapsulePlacementMath.pose(start, numeric);
        var target = new PlayerBodyPlacement(start.center(), attitude, start.shape(), numeric);
        var arc = CapsuleArcEnclosure.prepare(pose, target, start.bodyUp(), numeric);
        var targetTube = CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(target, numeric), delta, numeric);
        // A rejected rotation keeps the original attitude; its translation must be captured too.
        var originalTube = CapsulePlacementMath.translationTube(pose, delta, numeric);
        var window = union(arc.enclose(0, 1, numeric).bounds(),
                union(targetTube.sweptBounds(), originalTube.sweptBounds()));
        var captured = context.capture(window);
        if (captured instanceof WorldCollisionCandidates.Unavailable failure)
            throw new IllegalStateException("Local collision world unavailable: " + failure.reason(), failure.cause().orElse(null));
        var snapshot = (WorldCollisionCandidates.Snapshot) captured;
        var source = CapsulePathSource.actualFree(current, pose, snapshot, window,
                FreeOwnerTransaction.ACCURACY, context.queries(), numeric);
        if (!start.attitude().equals(attitude)) {
            var rotation = CapsuleArcValidator.validate(source, target, start.bodyUp());
            if (rotation instanceof CapsuleArcValidator.VerifiedArc verified)
                source = CapsulePathSource.continueAfter(verified, numeric);
        }
        var tube = CapsulePlacementMath.translationTube(source.start(), delta, numeric);
        var movement = CapsuleMovementResolver.resolveContinuationWithSlide(context, source, tube, snapshot, 0);
        if (!(movement instanceof CapsuleMovementResolver.Resolved resolved)) {
            var failure = (CapsuleMovementResolver.Inconclusive) movement;
            throw new IllegalStateException("Local capsule movement unavailable: " + failure.reason(), failure.cause().orElse(null));
        }
        context.requireCurrent();
        var end = resolved.end().placement();
        var actual = end.center().subtract(start.center());
        var endpointCapture = context.capture(end.bounds());
        if (endpointCapture instanceof WorldCollisionCandidates.Unavailable failure)
            throw new IllegalStateException("Local contact world unavailable: " + failure.reason(), failure.cause().orElse(null));
        var constraints = CapsuleMoveFinalizer.touchingConstraints(end,
                ((WorldCollisionCandidates.Snapshot) endpointCapture).boxes(), numeric, context::requireCurrent);
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(numeric, context::requireCurrent);
        var velocity = FreeMovementExactGeometry.projectToConeStored(arithmetic, incomingVelocity, constraints);
        context.requireCurrent();
        return new Result(end, actual, velocity,
                clipped(actual.x(), delta.x()), clipped(actual.y(), delta.y()), clipped(actual.z(), delta.z()));
    }
    private static boolean clipped(double actual, double requested) {
        return Math.abs(actual - requested) > Math.max(1e-7, 8 * Math.max(Math.ulp(actual), Math.ulp(requested)));
    }
    private static Aabb union(Aabb a, Aabb b) {
        return new Aabb(new Vec3d(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()), Math.min(a.min().z(), b.min().z())),
                new Vec3d(Math.max(a.max().x(), b.max().x()), Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }
    /** Calculation custody ends before installation; native callbacks keep their server transaction. */
    private static final class Calculation implements CapsuleMovementContext {
        private final EntityPlayer actor;
        private final World world;
        private final PlayerPhysicalSnapshot accepted;
        private final WorldCollisionCandidates.ActorGeometry original;
        private final long tick;
        private final StoredDyadic.Work numeric = new StoredDyadic.Work();
        private final CapsuleContactQueryBudget queries = new CapsuleContactQueryBudget(QueryLimits.recordingOnly(), 0);
        private final FreePostprocessingWork postprocessing = new FreePostprocessingWork();
        private final Function<AxisAlignedBB, List<AxisAlignedBB>> currentCollisions;
        private final Runnable requireClientCurrent;
        Calculation(EntityPlayer actor, PlayerPhysicalSnapshot accepted,
                Function<AxisAlignedBB, List<AxisAlignedBB>> currentCollisions, Runnable requireClientCurrent) {
            this.currentCollisions = Objects.requireNonNull(currentCollisions);
            this.requireClientCurrent = Objects.requireNonNull(requireClientCurrent);
            requireClientCurrent.run();
            this.actor = actor; this.accepted = accepted; world = actor.world;
            if (!world.isRemote) throw new IllegalArgumentException("Local movement requires the client world");
            original = WorldCollisionCandidates.ActorGeometry.read(actor); tick = world.getTotalWorldTime();
        }
        @Override public PlayerPhysicalSnapshot accepted() { return accepted; }
        @Override public StoredDyadic.Work numeric() { return numeric; }
        @Override public CapsuleContactQueryBudget queries() { return queries; }
        @Override public FreePostprocessingWork postprocessing() { return postprocessing; }
        @Override public void requireCurrent() {
            requireClientCurrent.run();
            if (actor.world != world || actor.isDead || world.getTotalWorldTime() != tick
                    || !original.equals(WorldCollisionCandidates.ActorGeometry.read(actor)))
                throw new IllegalStateException("Local capsule calculation lost its player/world");
        }
        @Override public void requireOwnerFence() { requireCurrent(); }
        @Override public void requireReservedPath(double upper) {
            requireCurrent();
            if (!fitsReservedPath(upper)) throw new IllegalStateException("Capsule route length is not finite and nonnegative");
        }
        @Override public boolean fitsReservedPath(double upper) { return Double.isFinite(upper) && upper >= 0; }
        @Override public double remainingPathGrant(double used) { requireReservedPath(used); // Local calculation has no server travel-credit grant. The solver requires a finite
            // geometric bound; use the representable domain, without throttling movement.
            return Double.MAX_VALUE; }
        @Override public boolean isPreInstallationPhase() { return true; }
        @Override public WorldCollisionCandidates.Capture capture(Aabb window) {
            requireCurrent();
            var capture = WorldCollisionCandidates.captureLocal(world, actor, window, SCAN, currentCollisions);
            requireCurrent();
            return capture;
        }
    }
}
