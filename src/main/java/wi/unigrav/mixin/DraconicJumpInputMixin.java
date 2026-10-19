package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import wi.unigrav.compat.EquipmentFlightSampling;

@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.items.armor.WyvernArmor", remap = false)
abstract class DraconicJumpInputMixin {
    @WrapOperation(method = "getJumpModifier", remap = false,
            at = @At(value = "INVOKE", target = "Lcom/brandon3055/brandonscore/CommonProxy;isSprintKeyDown()Z",
                    remap = false), require = 1, allow = 1)
    private boolean unigrav$sampledSprint(@Coerce Object proxy, Operation<Boolean> original,
                                          ItemStack stack, EntityPlayer player) {
        return EquipmentFlightSampling.sprintKey(player, original.call(proxy));
    }
}
