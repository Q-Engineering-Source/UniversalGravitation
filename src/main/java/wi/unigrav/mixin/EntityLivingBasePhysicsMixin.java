package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.objectweb.asm.Opcodes;
import wi.unigrav.UnigravMod;

/** Intercepts the virtual call, including EntityPlayer.travel's wrapper, only for installed physical owners. */
@Mixin(EntityLivingBase.class)
abstract class EntityLivingBasePhysicsMixin {
    // Native pre-travel damping and small-velocity zeroing are also writers, even when travel is skipped.
    @Redirect(method = "onLivingUpdate", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/EntityLivingBase;motionX:D", opcode = Opcodes.PUTFIELD), require = 2, allow = 2)
    private void unigrav$nativeMotionX(EntityLivingBase actor, double velocity) {
        unigrav$legacy(actor, () -> actor.motionX = velocity);
    }

    @Redirect(method = "onLivingUpdate", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/EntityLivingBase;motionY:D", opcode = Opcodes.PUTFIELD), require = 2, allow = 2)
    private void unigrav$nativeMotionY(EntityLivingBase actor, double velocity) {
        unigrav$legacy(actor, () -> actor.motionY = velocity);
    }

    @Redirect(method = "onLivingUpdate", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/EntityLivingBase;motionZ:D", opcode = Opcodes.PUTFIELD), require = 2, allow = 2)
    private void unigrav$nativeMotionZ(EntityLivingBase actor, double velocity) {
        unigrav$legacy(actor, () -> actor.motionZ = velocity);
    }

    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;travel(FFF)V"), require = 1, allow = 1)
    private void unigrav$ownedTravel(EntityLivingBase actor, float strafe, float vertical, float forward, Operation<Void> original) {
        unigrav$legacy(actor, () -> original.call(actor, strafe, vertical, forward));
    }

    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;jump()V"), require = 1, allow = 1)
    private void unigrav$ownedJump(EntityLivingBase actor, Operation<Void> original) {
        unigrav$legacy(actor, () -> original.call(actor));
    }

    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;handleJumpWater()V"), require = 1, allow = 1)
    private void unigrav$ownedWaterJump(EntityLivingBase actor, Operation<Void> original) {
        unigrav$legacy(actor, () -> original.call(actor));
    }

    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;handleJumpLava()V"), require = 1, allow = 1)
    private void unigrav$ownedLavaJump(EntityLivingBase actor, Operation<Void> original) {
        unigrav$legacy(actor, () -> original.call(actor));
    }

    @Unique private static void unigrav$legacy(EntityLivingBase actor, Runnable original) {
        // Coremod loading precedes FML's sided-proxy injection; no physical owner can exist in that phase.
        if (UnigravMod.proxy == null) original.run();
        else UnigravMod.proxy.runLegacyMotion(actor, original);
    }
}
