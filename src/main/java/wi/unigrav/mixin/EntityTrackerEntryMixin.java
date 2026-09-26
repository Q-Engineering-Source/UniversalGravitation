package wi.unigrav.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.SPacketEntityVelocity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import wi.unigrav.core.boost.RemoteBoostServer;

/**
 * Observes the exact velocityChanged tracker branch. Vanilla delivery happens
 * first; the remote session then limits only its high-speed carrier.
 */
@Mixin(EntityTrackerEntry.class)
abstract class EntityTrackerEntryMixin {

    @Shadow @Final private Entity trackedEntity;

    @Redirect(method = "updatePlayerList", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityTrackerEntry;sendToTrackingAndSelf(Lnet/minecraft/network/Packet;)V"))
    private void ugboost$redirectOwnedVelocity(EntityTrackerEntry tracker, Packet<?> packet) {
        tracker.sendToTrackingAndSelf(packet);
        if (packet instanceof SPacketEntityVelocity velocity) {
            RemoteBoostServer.INSTANCE.afterTrackerVelocity(trackedEntity, velocity);
        }
    }
}
