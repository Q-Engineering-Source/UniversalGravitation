package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import wi.unigrav.UnigravMod;

/** Fluid method entry points and the base lifecycle operation that owns their abort boundary. */
@Mixin(Entity.class)
abstract class EntityNativeEnvironmentMixin {
    @WrapMethod(method = "onEntityUpdate()V", require = 1, allow = 1)
    private void unigrav$nativeEnvironment(Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativeEnvironment((Entity) (Object) this, () -> original.call());
    }

    @WrapMethod(method = "handleWaterMovement()Z", require = 1, allow = 1)
    private boolean unigrav$waterMovement(Operation<Boolean> original) {
        return UnigravMod.proxy == null ? original.call()
                : UnigravMod.proxy.nativeWaterMovement((Entity) (Object) this, () -> original.call());
    }

    @WrapMethod(method = "isInLava()Z", require = 1, allow = 1)
    private boolean unigrav$inLava(Operation<Boolean> original) {
        return UnigravMod.proxy == null ? original.call()
                : UnigravMod.proxy.nativeInLava((Entity) (Object) this, () -> original.call());
    }
}
