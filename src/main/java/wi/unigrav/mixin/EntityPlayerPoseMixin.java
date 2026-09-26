package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import wi.unigrav.UnigravMod;

/** Preserve native collision/pose callbacks and observe only their completed, anchored resize. */
@Mixin(EntityPlayer.class)
abstract class EntityPlayerPoseMixin {
    @WrapMethod(method = {"updateSize()V", "func_184808_cD()V"}, remap = false, require = 1, allow = 1)
    private void unigrav$nativePose(Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativePose((EntityPlayer) (Object) this, () -> original.call());
    }
}
