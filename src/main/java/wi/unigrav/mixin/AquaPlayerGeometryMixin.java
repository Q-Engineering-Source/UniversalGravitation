package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.MoverType;
import dev.crlhitbox.api.geometry.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.UnigravMod;

/** Uses Aqua's verified merged methods; call-site injection must outrank their merge priority. */
@Mixin(value = EntityPlayer.class, priority = 1001)
abstract class AquaPlayerGeometryMixin {
    @WrapMethod(method = "recalculateSize()V", remap = false, require = 1, allow = 1)
    private void unigrav$aquaResize(Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativePose((EntityPlayer) (Object) this, () -> original.call());
    }

    @WrapOperation(method = "recalculateSize(Lcom/fuzs/aquaacrobatics/entity/EntitySize;Lcom/fuzs/aquaacrobatics/entity/EntitySize;)V",
            remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;move(Lnet/minecraft/entity/MoverType;DDD)V",
            remap = true), require = 1, allow = 1)
    private void unigrav$aquaGrowthMove(EntityPlayer actor, MoverType type, double x, double y, double z,
                                       Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call(actor, type, x, y, z);
        else UnigravMod.proxy.runPoseMove(actor, type, new Vec3d(x, y, z),
                bounded -> original.call(actor, type, bounded.x(), bounded.y(), bounded.z()));
    }
}
