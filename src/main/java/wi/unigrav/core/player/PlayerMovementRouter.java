package wi.unigrav.core.player;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.RemoteBoostServer;

/**
 * Per-handler synchronous packet routing, with exactly one native or prepared-cardinal move.
 * Not a physical owner or authorization source. Live entry currently selects native only:
 * cardinal activation still requires owner, protocol, local travel and native correction policy.
 * Packet onGround remains a native compatibility bit, never cardinal support authority.
 */
public final class PlayerMovementRouter {
    private final RemoteBoostServer remote;
    private final PlayerPhysicsServer physics;
    private RemoteBoostServer.CompletedMovement remoteCompletion;
    private NetHandlerPlayServer handler;
    private EntityPlayerMP actor;
    private boolean cancelled;
    private CardinalMoveFinalizer.Ready cardinal;
    private CardinalMoveFinalizer.Applied applied;
    private World world;
    private long tick;
    private boolean moved;
    private boolean assigned;
    private float width;
    private float height;
    private Failure failure = Failure.NONE;
    private boolean packetRunning;
    private boolean remoteCustodyObserved;

    public enum Failure { NONE, TELEPORT, COMPETING_OWNER, MOVE_MISMATCH, STALE_PREPARATION, ACTOR_CHANGED }

    public PlayerMovementRouter() { this(RemoteBoostServer.INSTANCE, PlayerPhysicsServer.INSTANCE); }

    /** Uses the actual Remote module; callers cannot replace the movement solver with a callback. */
    public PlayerMovementRouter(RemoteBoostServer remote) { this(remote, null); }

    public PlayerMovementRouter(RemoteBoostServer remote, PlayerPhysicsServer physics) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.physics = physics;
    }

    public void reanchorNativeUpdate(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch) {
        // The owner already installed its exact collider. Rebuilding it from firstGood would also undo
        // a native teleport issued inside onUpdateEntity. Unknown tail divergence is not rolled back here.
        if (physics != null && physics.drivesMotion(player)) return;
        player.setPositionAndRotation(x, y, z, yaw, pitch);
    }

    /** Called only after native thread/invalid-packet/end-exit checks, before the movement branch. */
    public boolean handleOwnedPacket(NetHandlerPlayServer connection, CPacketPlayer packet, boolean teleportPending) {
        if (physics == null || !physics.drivesMotion(connection.player) || teleportPending) return false;
        if (!packetRunning) throw new IllegalStateException("Owned packet outside packet lifetime");
        float yaw = packet.getYaw(connection.player.rotationYaw);
        float pitch = packet.getPitch(connection.player.rotationPitch);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new IllegalArgumentException("Native invalid-packet gate was bypassed");
        // setPositionAndRotation would rebuild the physical box. The old CP owns neither position nor support.
        connection.player.setRotation(yaw, pitch);
        return true;
    }

    /**
     * Whole-method lifetime, including exceptional exits after provisional tracking confirmation.
     * Off-thread calls must reach native PacketThreadUtil unchanged: its quick exit is a normal
     * handoff, not a failed server transaction, and must not touch another thread's active route.
     */
    public void runPacket(NetHandlerPlayServer connection, Runnable body, BooleanSupplier teleportPending) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(teleportPending, "teleportPending");
        if (!connection.player.getServerWorld().isCallingFromMinecraftThread()) {
            body.run();
            return;
        }
        boolean ownedAtStart = physics != null && physics.drivesMotion(connection.player);
        if (packetRunning || handler != null) throw new IllegalStateException("nested player packet lifetime");
        packetRunning = true;
        try {
            body.run();
            if (ownedAtStart) return;
            boolean pending = teleportPending.getAsBoolean();
            finishDelegates(connection, pending);
            completeObservation(connection, pending);
        } catch (RuntimeException | Error original) {
            try {
                // A late callback may have failed after beforeTracking marked tentative success.
                // Never certify that move; abort remains charged and never changes G intent.
                runBoth(() -> {
                    if (!ownedAtStart && cardinal == null) remote.abortMovement(connection);
                }, () -> { if (!ownedAtStart) NativePlayerMovement.INSTANCE.invalidate(connection); });
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != original) original.addSuppressed(cleanup);
            }
            throw original;
        } finally {
            clearTransaction();
            packetRunning = false;
        }
    }

    /** Trusted caller only; a Ready token proves neither physical ownership nor permission. */
    public CPacketPlayer beginCardinal(NetHandlerPlayServer connection, CPacketPlayer packet,
                                       boolean teleportPending, CardinalMoveFinalizer.Ready ready) {
        Objects.requireNonNull(ready, "ready");
        if (!ready.belongsTo(connection.player)
                || !(packet instanceof CPacketPlayer.Position || packet instanceof CPacketPlayer.PositionRotation)) {
            throw new IllegalArgumentException("cardinal move requires its actor and a position-bearing packet");
        }
        start(connection);
        cardinal = ready;
        NativePlayerMovement.INSTANCE.invalidate(connection);
        // This pure query must precede any RemoteBoost prepare/precharge. Never steal its custody.
        if (remote.hasMovementCustody(connection)) cancel(Failure.COMPETING_OWNER);
        else if (teleportPending) cancel(Failure.TELEPORT);
        return packet;
    }

    public CPacketPlayer begin(NetHandlerPlayServer connection, CPacketPlayer packet, Vec3 firstGood,
                               boolean teleportPending) {
        if (physics != null && physics.drivesMotion(connection.player)) return packet;
        start(connection);
        CPacketPlayer bounded = remote.replacePacketForNativeMove(
                connection, packet, firstGood, teleportPending);
        NativePlayerMovement.INSTANCE.begin(connection, teleportPending);
        return bounded;
    }

    public void move(EntityPlayerMP player, MoverType type, double x, double y, double z) {
        requireActor(player);
        if (cancelled) return;
        if (moved) throw new IllegalStateException("packet move already consumed");
        moved = true;
        if (cardinal != null) {
            var request = cardinal.outcome().request();
            if (type != request.moverType() || x != request.worldDelta().x()
                    || y != request.worldDelta().y() || z != request.worldDelta().z()) {
                cancel(Failure.MOVE_MISMATCH);
                return;
            }
            switch (cardinal.commit()) {
                case CardinalMoveFinalizer.Unavailable ignored -> cancel(Failure.STALE_PREPARATION);
                case CardinalMoveFinalizer.Applied result -> {
                    applied = result;
                    validateCardinal(false);
                }
            }
            return;
        }
        player.move(type, x, y, z);
        remote.captureNativeResult(handler);
        NativePlayerMovement.INSTANCE.captureNativeMove(handler);
    }

    public void jump(EntityPlayerMP player) {
        requireActor(player);
        if (!cancelled && cardinal == null) player.jump();
    }

    public void assignEndpoint(EntityPlayerMP player, double x, double y, double z, float yaw, float pitch,
                               boolean teleportPending) {
        requireActor(player);
        if (cancelled) return;
        if (!moved || assigned) throw new IllegalStateException("endpoint requires one unassigned move");
        assigned = true;
        if (cardinal != null) {
            validateCardinal(teleportPending);
            if (cancelled) return;
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                throw new IllegalArgumentException("nonfinite packet orientation");
            }
            // Native angular semantics only; no setPosition, getChunk or prevPos rewrite.
            // Physical previous position belongs to the future owner, not a client endpoint.
            float boundedPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
            player.prevRotationYaw = yaw;
            player.prevRotationPitch = boundedPitch;
            ((Entity) player).setRotation(yaw, boundedPitch);
            return;
        }
        if (!remote.mayCommitNativeEndpoint(handler, teleportPending)) {
            NativePlayerMovement.INSTANCE.invalidate(handler);
            cancelled = true;
            return;
        }
        Vec3 position = remote.finalNativePosition(handler, new Vec3(x, y, z));
        player.setPositionAndRotation(position.x(), position.y(), position.z(), yaw, pitch);
    }

    public boolean mustCancel() { return cancelled; }
    public Failure failure() { return failure; }

    public void beforeTracking(boolean teleportPending) {
        if (cancelled) return;
        if (cardinal != null) {
            if (!assigned) throw new IllegalStateException("tracking precedes endpoint assignment");
            validateCardinal(teleportPending);
            return;
        }
        remote.markMovementSuccess(handler);
        NativePlayerMovement.INSTANCE.commitBeforeTracking(handler, teleportPending);
    }

    public void handleFalling(EntityPlayerMP player, double distance, boolean onGround) {
        requireActor(player);
        if (!cancelled && cardinal == null) player.handleFalling(distance, onGround);
    }

    public void invalidate(NetHandlerPlayServer connection) {
        NativePlayerMovement.INSTANCE.invalidate(connection);
        if (handler == connection && cardinal != null) cancel(Failure.TELEPORT);
    }

    /** Explicit completion for trusted direct callers; live packets are finished only by runPacket. */
    public void finish(NetHandlerPlayServer connection, boolean teleportPending) {
        try {
            finishDelegates(connection, teleportPending);
            completeObservation(connection, teleportPending);
        } finally {
            clearTransaction();
        }
    }

    private void completeObservation(NetHandlerPlayServer connection, boolean teleportPending) {
        // Remote budget completion and the native geometry receipt are separate evidence.
        boolean remoteObserved = remoteCustodyObserved || remote.hasMovementCustody(connection)
                || remoteCompletion != null;
        if (remoteObserved && !remote.consumeCompletedMovement(connection, remoteCompletion)) {
            NativePlayerMovement.INSTANCE.discardCenters(connection);
            return;
        }
        if (cardinal == null && !cancelled && moved && assigned) {
            if (connection.player != actor || actor.connection != connection || actor.world != world
                    || world.getTotalWorldTime() != tick) {
                NativePlayerMovement.INSTANCE.invalidate(connection);
                return;
            }
            NativePlayerMovement.INSTANCE.completePacket(connection, teleportPending);
            if (physics != null) physics.afterLegacyCompletion(connection, remote, remoteCompletion);
        }
    }

    private void finishDelegates(NetHandlerPlayServer connection, boolean teleportPending) {
        if (handler != null && handler != connection) throw new IllegalArgumentException("foreign connection");
        runBoth(() -> {
            if (cardinal == null) remoteCompletion = remote.finishMovement(connection, teleportPending).orElse(null);
        }, () -> NativePlayerMovement.INSTANCE.finish(connection));
    }

    /** Run the second cleanup even if the first fails, retaining the first cause rather than replacing it. */
    private static void runBoth(Runnable first, Runnable second) {
        try {
            first.run();
        } catch (RuntimeException | Error original) {
            try {
                second.run();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != original) original.addSuppressed(cleanup);
            }
            throw original;
        }
        second.run();
    }

    private void clearTransaction() {
        handler = null;
        actor = null;
        cancelled = false;
        cardinal = null;
        applied = null;
        world = null;
        moved = false;
        assigned = false;
        failure = Failure.NONE;
        remoteCustodyObserved = false;
        remoteCompletion = null;
    }

    private void start(NetHandlerPlayServer connection) {
        if (handler != null) throw new IllegalStateException("overlapping player movement packets");
        Objects.requireNonNull(connection, "connection");
        EntityPlayerMP player = Objects.requireNonNull(connection.player, "player");
        if (player.connection != connection) throw new IllegalArgumentException("player has another connection");
        if (!player.getServerWorld().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("player packet routing requires the server thread");
        }
        handler = connection;
        actor = player;
        world = player.world;
        tick = world.getTotalWorldTime();
        width = player.width;
        height = player.height;
        remoteCustodyObserved = remote.hasMovementCustody(connection);
    }

    private void cancel(Failure reason) {
        if (!cancelled) failure = reason;
        cancelled = true;
        NativePlayerMovement.INSTANCE.invalidate(handler);
    }

    private void validateCardinal(boolean teleportPending) {
        if (cancelled) return;
        if (teleportPending) { cancel(Failure.TELEPORT); return; }
        if (handler.player != actor || actor.connection != handler || actor.world != world
                || world.getTotalWorldTime() != tick
                || actor.isDead || actor.noClip || actor.isRiding() || actor.isPlayerSleeping()
                || actor.isSpectator() || actor.width != width || actor.height != height
                || applied == null || !sameBox(actor.getEntityBoundingBox(), applied.outcome().collision().endBounds())
                || actor.posX != applied.outcome().endTransport().x()
                || actor.posY != applied.outcome().endTransport().y()
                || actor.posZ != applied.outcome().endTransport().z()) {
            cancel(Failure.ACTOR_CHANGED);
        }
    }

    private static boolean sameBox(AxisAlignedBB first, AxisAlignedBB second) {
        return first.minX == second.minX && first.minY == second.minY && first.minZ == second.minZ
                && first.maxX == second.maxX && first.maxY == second.maxY && first.maxZ == second.maxZ;
    }

    private void requireActor(EntityPlayerMP player) {
        if (handler == null || player != actor) {
            throw new IllegalStateException("movement callback has no matching packet actor");
        }
        if (handler.player != actor || actor.connection != handler) cancel(Failure.ACTOR_CHANGED);
    }
}
