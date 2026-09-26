package wi.unigrav.core.player;

import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

/** Synchronous equipment writes invalidate observations, not geometry, G intent or movement credit. */
public final class PlayerEquipmentMutation {
    private static final EntityEquipmentSlot[] SLOTS = EntityEquipmentSlot.values();

    private PlayerEquipmentMutation() { }

    /** Wrap each native write, rather than comparing only the final equipment at packet completion. */
    public static <T> T observe(EntityPlayer player, Supplier<T> write) {
        if (!(player instanceof EntityPlayerMP)) return write.get();
        ItemStack[] before = new ItemStack[SLOTS.length];
        for (int i = 0; i < SLOTS.length; i++)
            before[i] = player.getItemStackFromSlot(SLOTS[i]).copy();
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(player)) {
            try {
                return write.get();
            } finally {
                // Also invalidate a partially completed write that throws. Never undo the write.
                for (int i = 0; i < SLOTS.length; i++) {
                    if (!ItemStack.areItemStacksEqual(before[i], player.getItemStackFromSlot(SLOTS[i]))) {
                        mutation.changed();
                        break;
                    }
                }
            }
        }
    }
}
