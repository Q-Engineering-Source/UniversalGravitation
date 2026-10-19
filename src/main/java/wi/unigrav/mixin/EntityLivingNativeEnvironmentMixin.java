package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import wi.unigrav.UnigravMod;

/** The Living lifecycle includes fluid conditions after the nested base lifecycle has returned. */
@Mixin(EntityLivingBase.class)
abstract class EntityLivingNativeEnvironmentMixin {
    @WrapMethod(method = "onUpdate()V", require = 1, allow = 1)
    private void unigrav$nativeEnvironment(Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativeEnvironment((EntityLivingBase) (Object) this, () -> original.call());
    }
}
