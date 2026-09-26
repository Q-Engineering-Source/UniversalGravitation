package wi.unigrav.mixin;

import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.player.PlayerMovementRouter;
import wi.unigrav.core.player.PlayerPhysicsServer;

/**
 * Makes one already-admitted packet visible to every subsequent native X/Y/Z read. The target
 * anchor is after PacketThreadUtil and invalid-coordinate handling, but before processPlayer's
 * movement locals, so no world logic is run on Netty and raw XYZ cannot leak into native move.
 */
@Mixin(NetHandlerPlayServer.class)
abstract class NetHandlerPlayServerMixin {
    @Shadow public EntityPlayerMP player;
    @Shadow private double firstGoodX;
    @Shadow private double firstGoodY;
    @Shadow private double firstGoodZ;
    @Shadow private int movePacketCounter;
    @Shadow private int lastMovePacketCounter;
    @Shadow private int networkTickCount;
    @Shadow private int lastPositionUpdate;
    @Shadow private Vec3d targetPos;
    @Shadow private int teleportId;
    @Unique private final PlayerMovementRouter unigrav$movement = new PlayerMovementRouter();

    @Redirect(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;setPositionAndRotation(DDDFF)V"), require = 1, allow = 1)
    private void unigrav$preservePhysicalUpdateAnchor(EntityPlayerMP actor, double x, double y, double z, float yaw, float pitch) {
        unigrav$movement.reanchorNativeUpdate(actor, x, y, z, yaw, pitch);
    }

    // Both pinned names are explicit: do not depend on remapping an Extras selector.
    @WrapMethod(method = {
            "processPlayer(Lnet/minecraft/network/play/client/CPacketPlayer;)V",
            "func_147347_a(Lnet/minecraft/network/play/client/CPacketPlayer;)V"
    }, remap = false, require = 1, allow = 1)
    private void unigrav$packetLifetime(CPacketPlayer packet, Operation<Void> original) {
        unigrav$movement.runPacket((NetHandlerPlayServer) (Object) this,
                () -> original.call(packet), () -> targetPos != null);
    }

    @ModifyVariable(method = "processPlayer", argsOnly = true, at = @At(value = "FIELD",
            target = "Lnet/minecraft/network/NetHandlerPlayServer;targetPos:Lnet/minecraft/util/math/Vec3d;",
            ordinal = 0))
    private CPacketPlayer ugboost$boundNativePacket(CPacketPlayer packet) {
        NetHandlerPlayServer handler = (NetHandlerPlayServer) (Object) this;
        return unigrav$movement.begin(handler, packet,
                new Vec3(firstGoodX, firstGoodY, firstGoodZ), targetPos != null);
    }

    @Inject(method = "processPlayer", at = @At(value = "FIELD",
            target = "Lnet/minecraft/network/NetHandlerPlayServer;targetPos:Lnet/minecraft/util/math/Vec3d;",
            opcode = Opcodes.GETFIELD, ordinal = 0), cancellable = true, require = 1)
    private void unigrav$ownedPacketAfterNativeValidation(CPacketPlayer packet, CallbackInfo callback) {
        if (unigrav$movement.handleOwnedPacket((NetHandlerPlayServer) (Object) this, packet, targetPos != null)) {
            lastPositionUpdate = networkTickCount;
            callback.cancel();
        }
    }

    @ModifyConstant(method = "processPlayer", constant = @Constant(floatValue = 100.0F))
    private float ugboost$replaceAdmittedNormalAllowance(float vanillaThreshold) {
        int packets = movePacketCounter - lastMovePacketCounter;
        if (packets > 5) packets = 1;
        return RemoteBoostServer.INSTANCE.nativeThreshold((NetHandlerPlayServer) (Object) this,
                packets, vanillaThreshold);
    }

    @Redirect(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;jump()V"))
    private void unigrav$routeJump(EntityPlayerMP actor) {
        unigrav$movement.jump(actor);
    }

    @Redirect(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;move(Lnet/minecraft/entity/MoverType;DDD)V"))
    private void unigrav$routeMove(EntityPlayerMP actor, MoverType type, double x, double y, double z) {
        unigrav$movement.move(actor, type, x, y, z);
    }

    @Inject(method = "processPlayer", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;onGround:Z", opcode = Opcodes.PUTFIELD, ordinal = 0),
            cancellable = true)
    private void unigrav$cancelFailedMove(CPacketPlayer packet, CallbackInfo callback) {
        unigrav$cancelIfNecessary(callback);
    }

    @Redirect(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;setPositionAndRotation(DDDFF)V", ordinal = 1))
    private void unigrav$routeEndpoint(EntityPlayerMP actor, double x, double y, double z, float yaw, float pitch) {
        unigrav$movement.assignEndpoint(actor, x, y, z, yaw, pitch, targetPos != null);
    }

    @Inject(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;addMovementStat(DDD)V"),
            cancellable = true)
    private void unigrav$cancelFailedEndpoint(CPacketPlayer packet, CallbackInfo callback) {
        unigrav$cancelIfNecessary(callback);
    }

    @Unique
    private void unigrav$cancelIfNecessary(CallbackInfo callback) {
        if (!unigrav$movement.mustCancel()) return;
        callback.cancel();
    }

    @Inject(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerList;serverUpdateMovingPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
            shift = At.Shift.BEFORE), cancellable = true)
    private void ugboost$markAcceptedNativeMove(CPacketPlayer packet, CallbackInfo callback) {
        unigrav$movement.beforeTracking(targetPos != null);
        unigrav$cancelIfNecessary(callback);
    }

    @Redirect(method = "processPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayerMP;handleFalling(DZ)V"))
    private void unigrav$routeFalling(EntityPlayerMP actor, double distance, boolean onGround) {
        unigrav$movement.handleFalling(actor, distance, onGround);
    }

    @Inject(method = "processConfirmTeleport", at = @At("RETURN"))
    private void ugboost$confirmNativeTeleport(CPacketConfirmTeleport packet, CallbackInfo callback) {
        RemoteBoostServer.INSTANCE.confirmNativeTeleport((NetHandlerPlayServer) (Object) this,
                packet, targetPos == null);
        PlayerPhysicsServer.INSTANCE.confirmNativeTeleport((NetHandlerPlayServer) (Object) this,
                packet.getTeleportId(), targetPos == null);
    }

    @Inject(method = "setPlayerLocation(DDDFFLjava/util/Set;)V", at = @At("TAIL"))
    private void ugboost$observeNativeTeleport(double x, double y, double z, float yaw, float pitch,
            Set<?> relative, CallbackInfo callback) {
        unigrav$movement.invalidate((NetHandlerPlayServer) (Object) this);
        Vec3 actualTarget = targetPos != null ? new Vec3(targetPos.x, targetPos.y, targetPos.z)
                : new Vec3(player.posX, player.posY, player.posZ);
        RemoteBoostServer.INSTANCE.observeNativeTeleport((NetHandlerPlayServer) (Object) this,
                teleportId, actualTarget);
        PlayerPhysicsServer.INSTANCE.observeNativeTeleport((NetHandlerPlayServer) (Object) this,
                teleportId);
    }
}
