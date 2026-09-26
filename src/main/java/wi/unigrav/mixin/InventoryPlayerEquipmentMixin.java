package wi.unigrav.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.PlayerEquipmentMutation;

/** Inventory/container writes bypass EntityPlayer.setItemStackToSlot. */
@Mixin(InventoryPlayer.class)
abstract class InventoryPlayerEquipmentMixin {
    @Shadow public EntityPlayer player;

    @WrapOperation(method = {"setInventorySlotContents", "removeStackFromSlot", "deleteStack", "readFromNBT"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/NonNullList;set(ILjava/lang/Object;)Ljava/lang/Object;"),
            require = 6, allow = 6)
    private Object unigrav$equipmentWrite(NonNullList<?> list, int index, Object stack,
                                        Operation<Object> original) {
        return PlayerEquipmentMutation.observe(player, () -> original.call(list, index, stack));
    }

    @WrapOperation(method = "decrStackSize", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/inventory/ItemStackHelper;getAndSplit(Ljava/util/List;II)Lnet/minecraft/item/ItemStack;"),
            require = 1, allow = 1)
    private ItemStack unigrav$equipmentSplit(List<ItemStack> list, int index, int count,
                                           Operation<ItemStack> original) {
        return PlayerEquipmentMutation.observe(player, () -> original.call(list, index, count));
    }

    @WrapOperation(method = "clear", at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V"),
            require = 1, allow = 1)
    private void unigrav$equipmentClear(List<?> list, Operation<Void> original) {
        PlayerEquipmentMutation.observe(player, () -> { original.call(list); return null; });
    }

    @WrapOperation(method = "readFromNBT", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/NonNullList;clear()V"), require = 3, allow = 3)
    private void unigrav$equipmentReload(NonNullList<?> list, Operation<Void> original) {
        PlayerEquipmentMutation.observe(player, () -> { original.call(list); return null; });
    }
}
