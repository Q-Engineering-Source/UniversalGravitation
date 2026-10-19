package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import wi.unigrav.compat.EquipmentFlightSampling;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;

/** Both inherited DE/DA flight getters use the same sampled UG input on client and server. */
@Pseudo
@Mixin(targets = "com.brandon3055.draconicevolution.items.armor.DraconicArmor", remap = false)
abstract class DraconicFlightInputMixin {
    @WrapOperation(method = {"getFlightSpeedModifier", "getFlightVModifier"}, remap = false,
            at = @At(value = "INVOKE", target = "Lcom/brandon3055/brandonscore/CommonProxy;isSprintKeyDown()Z",
                    remap = false), require = 2, allow = 2)
    private boolean unigrav$sampledSprint(@Coerce Object proxy, Operation<Boolean> original,
                                          ItemStack stack, EntityPlayer player) {
        return EquipmentFlightSampling.sprintKey(player, original.call(proxy));
    }
}
