package wi.unigrav.mixin;

import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.entity.EntityPlayerSP;
import org.objectweb.asm.Opcodes;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import wi.unigrav.core.client.boost.RemoteBoostClient;
import wi.unigrav.core.client.PlayerPhysicsClient;

/**
 * Ordinary EntityPlayerSP reports use sendPacket; native PositionLook replies
 * bypass it with two direct NetworkManager calls. Both exact seams preserve
 * native packet order while the owned FRAME is paired immediately before the
 * resulting position sample.
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class NetHandlerPlayClientMixin {

    // Explosion visuals/block updates still execute. Its physical delta arrives once in the authoritative STATE.
    @Redirect(method = "handleExplosion", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;motionX:D", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$explosionVelocityX(EntityPlayerSP actor, double next) {
        PlayerPhysicsClient.runLegacyMotion(actor, () -> actor.motionX = next);
    }

    @Redirect(method = "handleExplosion", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;motionY:D", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$explosionVelocityY(EntityPlayerSP actor, double next) {
        PlayerPhysicsClient.runLegacyMotion(actor, () -> actor.motionY = next);
    }

    @Redirect(method = "handleExplosion", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;motionZ:D", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$explosionVelocityZ(EntityPlayerSP actor, double next) {
        PlayerPhysicsClient.runLegacyMotion(actor, () -> actor.motionZ = next);
    }

    /**
     * Must be after the thread hop: on Netty, PacketThreadUtil schedules then
     * throws; only the client-thread replay reaches this pre-motion-zero cue.
     */
    @Inject(method = "handlePlayerPosLook(Lnet/minecraft/network/play/server/SPacketPlayerPosLook;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketThreadUtil;"
                    + "checkThreadAndEnqueue(Lnet/minecraft/network/Packet;"
                    + "Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V",
                    shift = At.Shift.AFTER), require = 1)
    private void unigrav$captureNativeCorrection(SPacketPlayerPosLook packet, CallbackInfo callback) {
        RemoteBoostClient.captureNativePositionLook((NetHandlerPlayClient) (Object) this, packet);
    }

    /**
     * Vanilla sends ConfirmTeleport and then PositionRotation directly through
     * NetworkManager, bypassing this class's ordinary sendPacket hook. Route
     * just these two invokes through the same client reducer, then send the
     * unmodified native packet exactly once and in vanilla order.
     */
    @Redirect(method = "handlePlayerPosLook(Lnet/minecraft/network/play/server/SPacketPlayerPosLook;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkManager;"
                    + "sendPacket(Lnet/minecraft/network/Packet;)V"), require = 2)
    private void unigrav$pairNativeCorrection(NetworkManager manager, Packet<?> packet) {
        NetHandlerPlayClient handler = (NetHandlerPlayClient) (Object) this;
        manager.sendPacket(RemoteBoostClient.prepareOutgoingPacket(handler, packet));
    }

    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void unigrav$sendRemoteBoostFrame(Packet<?> packet, CallbackInfo callback) {
        NetHandlerPlayClient handler = (NetHandlerPlayClient) (Object) this;
        Packet<?> outbound = RemoteBoostClient.prepareOutgoingPacket(handler, packet);
        if (outbound != packet) {
            // Calling the manager directly avoids recursively re-entering this
            // hook; the FRAME was already sent immediately before this call.
            handler.getNetworkManager().sendPacket(outbound);
            callback.cancel();
        }
    }
}
