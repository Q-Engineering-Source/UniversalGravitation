package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFence;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.crash.CrashReport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Installs one resolved box and executes its movement tail once. Not a movement owner or force integrator.
 * Y+ native semantics are kept separate from later physical-frame callbacks. Non-Y+ currently performs
 * only exact geometry/world-index/world-axis flags/inside-block callbacks, NOT a standing/travel tail.
 * No second move/setPosition. Callers still own force, accepted state, world validity and packet routing.
 */
public final class CardinalMoveFinalizer {
    private CardinalMoveFinalizer() { }

    public enum Reason { ACTOR_CHANGED, WORLD_UNAVAILABLE }
    public enum Tail { NATIVE_Y_PLUS, CARDINAL_GEOMETRY_ONLY, CARDINAL_PHYSICAL }
    public sealed interface Preparation permits Ready, Unavailable { }
    public sealed interface CommitResult permits Applied, Unavailable { }
    public record Unavailable(Reason reason, String detail) implements Preparation, CommitResult { }

    /** Boxes retain actual solver rounding; they are not rebuilt from a rounded center. */
    public record Outcome(CardinalMovementResolver.Request request, CardinalMovementResolver.Resolved collision,
                          Vec3d startTransport, Vec3d endTransport, Tail tail) { }

    /** Tail observation only: native callbacks may legitimately relocate or kill the actor. */
    public record Applied(Outcome outcome, AxisAlignedBB actualBounds, Vec3d actualTransport,
                          Vec3d observedMotionBlocksPerTick) implements CommitResult { }

    /** Geometry installed before native tail callbacks; does not certify that the tail completed. */
    record Installation(AxisAlignedBB bounds, Vec3d transport) { }

    public static Preparation prepare(EntityPlayer actor, CardinalMovementResolver.Request request,
                                      CardinalMovementResolver.Resolved resolved) {
        return prepare(actor, request, resolved, false);
    }

    /** Owner-only physical movement: contact flags are relative to the accepted standing frame. */
    public static Preparation preparePhysical(EntityPlayer actor, CardinalMovementResolver.Request request,
                                              CardinalMovementResolver.Resolved resolved) {
        return prepare(actor, request, resolved, true);
    }

    private static Preparation prepare(EntityPlayer actor, CardinalMovementResolver.Request request,
                                       CardinalMovementResolver.Resolved resolved, boolean physical) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resolved, "resolved");
        requireThread(actor.world);
        if (resolved.request() != request) throw new IllegalArgumentException("result belongs to another move request");
        if (actor.isDead || actor.noClip || actor.isRiding() || actor.isPlayerSleeping() || actor.isSpectator()
                || ((Entity) actor).isInWeb || !sameBox(actor.getEntityBoundingBox(), request.worldBounds())
                || (physical || request.frame() == CardinalFrame.Y_POS) && (actor.onGround != request.onGround()
                    || actor.isSneaking() != request.sneaking() || actor.stepHeight != request.stepHeight())) {
            return new Unavailable(Reason.ACTOR_CHANGED, "native actor differs from resolved move start");
        }
        if (!loadedTail(actor.world, resolved.endBounds())) {
            return new Unavailable(Reason.WORLD_UNAVAILABLE, "movement tail requires loaded columns");
        }
        return new Ready(actor, new Outcome(request, resolved, position(actor), transport(resolved.endBounds()),
                request.frame() == CardinalFrame.Y_POS ? Tail.NATIVE_Y_PLUS
                        : physical ? Tail.CARDINAL_PHYSICAL : Tail.CARDINAL_GEOMETRY_ONLY));
    }

    /** One-shot synchronous transaction; preparation does not authorize movement or support. */
    public static final class Ready implements Preparation {
        private final EntityPlayer actor;
        private final World world;
        private final long tick;
        private final Outcome outcome;
        private final Vec3d motion;
        private final float width;
        private final float height;
        private final boolean onGround;
        private final boolean sneaking;
        private final float stepHeight;
        private boolean consumed;
        private Installation installation;

        private Ready(EntityPlayer actor, Outcome outcome) {
            this.actor = actor;
            this.world = actor.world;
            this.tick = world.getTotalWorldTime();
            this.outcome = outcome;
            this.motion = motion(actor);
            this.width = actor.width;
            this.height = actor.height;
            this.onGround = actor.onGround;
            this.sneaking = actor.isSneaking();
            this.stepHeight = actor.stepHeight;
        }

        public Outcome outcome() { return outcome; }

        boolean belongsTo(EntityPlayer candidate) { return actor == candidate; }

        Optional<Installation> installation() { return Optional.ofNullable(installation); }

        private void recordInstallation() {
            // World-index callbacks during resetPositionToBB must not certify a foreign relocation.
            if (sameBox(actor.getEntityBoundingBox(), outcome.collision().endBounds())
                    && position(actor).equals(outcome.endTransport()))
                installation = new Installation(actor.getEntityBoundingBox(), position(actor));
        }

        /** Stale/replayed preparations fail before mutation, never replaying irreversible callbacks. */
        public CommitResult commit() {
            requireThread(world);
            if (consumed) throw new IllegalStateException("movement tail already consumed");
            consumed = true;
            if (actor.world != world || world.getTotalWorldTime() != tick || actor.isDead || actor.noClip
                    || actor.isRiding() || actor.isPlayerSleeping() || actor.isSpectator() || ((Entity) actor).isInWeb
                    || width != actor.width || height != actor.height
                    || !sameBox(actor.getEntityBoundingBox(), outcome.request().worldBounds())
                    || !position(actor).equals(outcome.startTransport()) || !motion(actor).equals(motion)
                    || actor.onGround != onGround || actor.isSneaking() != sneaking || actor.stepHeight != stepHeight) {
                return new Unavailable(Reason.ACTOR_CHANGED, "movement tail preparation became stale before commit");
            }
            if (!loadedTail(world, outcome.collision().endBounds())) {
                return new Unavailable(Reason.WORLD_UNAVAILABLE, "movement tail lost loaded columns before commit");
            }
            if (outcome.tail() == Tail.NATIVE_Y_PLUS) finishNative(actor, outcome, this);
            else if (outcome.tail() == Tail.CARDINAL_PHYSICAL) finishPhysical(actor, outcome, this);
            else finishGeometry(actor, outcome, this);
            return new Applied(outcome, actor.getEntityBoundingBox(), position(actor), motion(actor));
        }
    }

    private static void finishPhysical(EntityPlayer actor, Outcome outcome, Ready transaction) {
        var collision = outcome.collision();
        actor.setEntityBoundingBox(collision.endBounds());
        actor.resetPositionToBB();
        var flags = collision.flags();
        actor.onGround = flags.onGround();
        actor.collidedHorizontally = flags.x() || flags.z();
        actor.collidedVertically = flags.y();
        actor.collided = actor.collidedHorizontally || actor.collidedVertically;
        transaction.recordInstallation();
        var local = outcome.request().frame().toLocal(motion(actor));
        var worldMotion = outcome.request().frame().toWorld(new Vec3d(
                flags.x() ? 0 : local.x(), local.y(), flags.z() ? 0 : local.z()));
        actor.motionX = worldMotion.x();
        actor.motionY = worldMotion.y();
        actor.motionZ = worldMotion.z();
        BlockPos support = physicalSupport(outcome.request().frame(), collision.endBounds());
        IBlockState state = actor.world.getBlockState(support);
        CardinalBlockMotion.run(actor, outcome.request().frame(), () -> {
            if (flags.y()) state.getBlock().onLanded(actor.world, actor);
            finishWalking(actor, outcome, state, support);
        });
        finishWorldTail(actor);
    }

    private static void finishGeometry(Entity actor, Outcome outcome, Ready transaction) {
        var collision = outcome.collision();
        actor.setEntityBoundingBox(collision.endBounds());
        actor.resetPositionToBB();
        var flags = collision.flags();
        Vec3d worldBlocked = outcome.request().frame().toWorld(new Vec3d(flags.x() ? 1 : 0,
                flags.y() ? 1 : 0, flags.z() ? 1 : 0));
        actor.collidedHorizontally = worldBlocked.x() != 0 || worldBlocked.z() != 0;
        actor.collidedVertically = worldBlocked.y() != 0;
        actor.collided = actor.collidedHorizontally || actor.collidedVertically;
        // This native field is a world-axis compatibility mirror, not physical support authority.
        actor.onGround = actor.collidedVertically
                && outcome.request().frame().toWorld(collision.postSneakLocalDelta()).y() < 0;
        transaction.recordInstallation();
        insideBlocks(actor);
    }

    private static void finishNative(EntityPlayer player, Outcome outcome, Ready transaction) {
        Entity actor = player; // AT exposes base tail methods while retaining virtual dispatch.
        var collision = outcome.collision();
        actor.setEntityBoundingBox(collision.endBounds());
        actor.resetPositionToBB(); // Also updates an added server entity in the world, exactly once.
        actor.collidedHorizontally = collision.flags().x() || collision.flags().z();
        actor.collidedVertically = collision.flags().y();
        actor.onGround = collision.flags().onGround();
        actor.collided = actor.collidedHorizontally || actor.collidedVertically;
        transaction.recordInstallation();

        BlockPos support = new BlockPos(actor.posX, actor.posY - (double) 0.2F, actor.posZ);
        IBlockState state = actor.world.getBlockState(support);
        if (state.getMaterial() == Material.AIR) {
            BlockPos below = support.down();
            IBlockState candidate = actor.world.getBlockState(below);
            Block block = candidate.getBlock();
            if (block instanceof BlockFence || block instanceof BlockWall || block instanceof BlockFenceGate) {
                support = below;
                state = candidate;
            }
        }
        // MP's empty override is intentional; packet handleFalling remains a separate owner seam.
        actor.updateFallState(collision.nativeResolvedLocalDelta().y(), actor.onGround, state, support);
        if (collision.flags().x()) actor.motionX = 0;
        if (collision.flags().z()) actor.motionZ = 0;
        Block block = state.getBlock();
        if (collision.flags().y()) block.onLanded(actor.world, actor);

        finishWalking(player, outcome, state, support);
        finishWorldTail(player);
    }

    private static void finishWalking(EntityPlayer player, Outcome outcome, IBlockState state, BlockPos support) {
        Entity actor = player;
        Block block = state.getBlock();
        if (actor.canTriggerWalking() && !(actor.onGround && actor.isSneaking()) && !actor.isRiding()) {
            Vec3d local = outcome.request().frame().toLocal(position(actor).subtract(outcome.startTransport()));
            double dx = local.x();
            double dy = block == Blocks.LADDER ? local.y() : 0;
            double dz = local.z();
            if (actor.onGround) block.onEntityWalk(actor.world, support, actor);
            actor.distanceWalkedModified = (float) (actor.distanceWalkedModified + MathHelper.sqrt(dx * dx + dz * dz) * 0.6D);
            actor.distanceWalkedOnStepModified = (float) (actor.distanceWalkedOnStepModified
                    + MathHelper.sqrt(dx * dx + dy * dy + dz * dz) * 0.6D);
            if (actor.distanceWalkedOnStepModified > actor.nextStepDistance && state.getMaterial() != Material.AIR) {
                actor.nextStepDistance = (int) actor.distanceWalkedOnStepModified + 1;
                if (actor.isInWater()) {
                    Entity sounding = actor.isBeingRidden() && actor.getControllingPassenger() != null
                            ? actor.getControllingPassenger() : actor;
                    float volume = MathHelper.sqrt(sounding.motionX * sounding.motionX * (double) 0.2F
                            + sounding.motionY * sounding.motionY + sounding.motionZ * sounding.motionZ * (double) 0.2F)
                            * (sounding == actor ? 0.35F : 0.4F);
                    actor.playSound(actor.getSwimSound(), Math.min(1, volume),
                            1 + (player.getRNG().nextFloat() - player.getRNG().nextFloat()) * 0.4F);
                } else {
                    actor.playStepSound(support, block);
                }
            } else if (actor.distanceWalkedOnStepModified > actor.nextFlap && actor.makeFlySound()
                    && state.getMaterial() == Material.AIR) {
                actor.nextFlap = actor.playFlySound(actor.distanceWalkedOnStepModified);
            }
        }
    }

    private static void finishWorldTail(EntityPlayer player) {
        Entity actor = player;
        insideBlocks(actor);
        boolean wet = actor.isWet();
        if (actor.world.isFlammableWithin(actor.getEntityBoundingBox().shrink(0.001))) {
            actor.dealFireDamage(1);
            if (!wet && ++actor.fire == 0) actor.setFire(8);
        } else if (actor.fire <= 0) {
            actor.fire = -actor.getFireImmuneTicks();
        }
        if (wet && actor.isBurning()) {
            actor.playSound(SoundEvents.ENTITY_GENERIC_EXTINGUISH_FIRE, 0.7F,
                    1.6F + (player.getRNG().nextFloat() - player.getRNG().nextFloat()) * 0.4F);
            actor.fire = -actor.getFireImmuneTicks();
        }
    }

    /** Native block callback anchor, measured from the actual collider's physical foot face. */
    static BlockPos physicalSupport(CardinalFrame frame, AxisAlignedBB box) {
        Vec3d up = frame.up();
        double halfHeight = (Math.abs(up.x()) * (box.maxX - box.minX)
                + Math.abs(up.y()) * (box.maxY - box.minY) + Math.abs(up.z()) * (box.maxZ - box.minZ)) / 2;
        Vec3d inside = new Vec3d((box.minX + box.maxX) / 2, (box.minY + box.maxY) / 2, (box.minZ + box.maxZ) / 2)
                .subtract(up.multiply(halfHeight + (double) .2F));
        return new BlockPos(inside.x(), inside.y(), inside.z());
    }

    private static void insideBlocks(Entity actor) {
        try {
            actor.doBlockCollisions();
        } catch (Throwable failure) {
            CrashReport report = CrashReport.makeCrashReport(failure, "Checking entity block collision");
            actor.addEntityCrashInfo(report.makeCategory("Entity being checked for collision"));
            throw new ReportedException(report);
        }
    }

    private static boolean sameBox(AxisAlignedBB first, AxisAlignedBB second) {
        return first.minX == second.minX && first.minY == second.minY && first.minZ == second.minZ
                && first.maxX == second.maxX && first.maxY == second.maxY && first.maxZ == second.maxZ;
    }

    private static boolean loadedTail(World world, AxisAlignedBB end) {
        if (!(world instanceof WorldServer server) || world.captureBlockSnapshots || world.restoringBlockSnapshots) return false;
        int minX = MathHelper.floor(end.minX) >> 4;
        int maxX = MathHelper.floor(end.maxX) >> 4;
        int minZ = MathHelper.floor(end.minZ) >> 4;
        int maxZ = MathHelper.floor(end.maxZ) >> 4;
        if ((long) maxX - minX > 64 || (long) maxZ - minZ > 64) return false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            // getLoadedChunk cancels unloadQueued; a preflight must not retain chunks as a side effect.
            Chunk chunk = server.getChunkProvider().loadedChunks.get(ChunkPos.asLong(x, z));
            if (chunk == null || !chunk.isLoaded()) return false;
        }
        return true;
    }

    private static Vec3d transport(AxisAlignedBB box) {
        return new Vec3d((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2);
    }
    private static Vec3d position(Entity actor) { return new Vec3d(actor.posX, actor.posY, actor.posZ); }
    private static Vec3d motion(Entity actor) { return new Vec3d(actor.motionX, actor.motionY, actor.motionZ); }

    private static void requireThread(World world) {
        if (!(world instanceof WorldServer server) || !server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("cardinal finalizer requires its real server thread");
        }
    }
}
