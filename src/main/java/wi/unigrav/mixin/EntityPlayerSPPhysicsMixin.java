package wi.unigrav.mixin;

import net.minecraft.client.entity.EntityPlayerSP;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.client.PlayerPhysicsClient;

/** Retire local velocity writers outside travel, without suppressing native input/camera/lifecycle updates. */
@Mixin(EntityPlayerSP.class)
abstract class EntityPlayerSPPhysicsMixin {
    @Redirect(method = "onLivingUpdate", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/entity/EntityPlayerSP;motionY:D", opcode = Opcodes.PUTFIELD),
            require = 2, allow = 2)
    private void unigrav$nativeFlightVelocity(EntityPlayerSP actor, double velocity) {
        if (UnigravMod.proxy == null) actor.motionY = velocity;
        else UnigravMod.proxy.runLegacyMotion(actor, () -> actor.motionY = velocity);
    }

    @Inject(method = "pushOutOfBlocks(DDD)Z", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void unigrav$ownedPushOut(double x, double y, double z, CallbackInfoReturnable<Boolean> callback) {
        if (PlayerPhysicsClient.drivesMotion((EntityPlayerSP) (Object) this)) callback.setReturnValue(false);
    }
}
