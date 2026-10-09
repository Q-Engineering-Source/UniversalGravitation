package wi.unigrav.mixin;

import net.minecraft.entity.player.EntityPlayer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import wi.unigrav.UnigravMod;

/** The server keeps the actual getter; a client physical owner presents its accepted eye anchor. */
@Mixin(value = EntityPlayer.class, priority = 900)
abstract class EntityPlayerEyeMixin {
    @WrapMethod(method = {"getEyeHeight()F", "func_70047_e()F"}, remap = false, require = 1, allow = 1)
    private float unigrav$physicalEye(Operation<Float> original) {
        // Aqua's cancellable HEAD introduces an early return after RETURN injectors are prepared.
        // A whole-method boundary receives that native eye result too, without resampling recursively.
        float nativeEye = original.call();
        return UnigravMod.proxy == null ? nativeEye
                : UnigravMod.proxy.physicalEyeHeight((EntityPlayer) (Object) this, nativeEye);
    }
}
