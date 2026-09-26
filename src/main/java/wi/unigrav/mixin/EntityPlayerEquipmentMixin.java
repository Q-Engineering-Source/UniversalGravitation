package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.PlayerEquipmentMutation;

@Mixin(EntityPlayer.class)
abstract class EntityPlayerEquipmentMixin {
    @WrapOperation(method = "setItemStackToSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/NonNullList;set(ILjava/lang/Object;)Ljava/lang/Object;"),
            require = 3, allow = 3)
    private Object unigrav$equipmentWrite(NonNullList<?> list, int index, Object stack,
                                        Operation<Object> original) {
        return PlayerEquipmentMutation.observe((EntityPlayer) (Object) this,
                () -> original.call(list, index, stack));
    }
}
