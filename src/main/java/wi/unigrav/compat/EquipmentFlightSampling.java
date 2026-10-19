package wi.unigrav.compat;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;
import wi.unigrav.core.player.NativeFlightParameters;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import java.util.function.Supplier;
import net.minecraft.item.ItemStack;

/** Optional DE family adapter; no client speed report grants server movement authority. */
public final class EquipmentFlightSampling {
    private EquipmentFlightSampling() { }
    private record Input(EntityPlayer actor, boolean sprint) { }
    private static final ThreadLocal<Input> SPRINT_SAMPLE = new ThreadLocal<>();

    /** Only the verified DE flight/jump getters consult this context; other DE callbacks are unchanged. */
    public static boolean sprintKey(EntityPlayer actor, boolean original) {
        Input sample = SPRINT_SAMPLE.get();
        return sample == null || sample.actor() != actor ? original : sample.sprint();
    }

    public static NativeFlightParameters sample(EntityPlayer player, PlayerPhysicsProtocol.Controls controls) {
        var nativeParameters = new NativeFlightParameters(player.capabilities.getFlySpeed(), 0, false, 0, "capabilities");
        if (!Loader.isModLoaded("draconicevolution")) return nativeParameters;
        var world = player.world;
        long tick = world.getTotalWorldTime();
        var bounds = player.getEntityBoundingBox();
        double x = player.posX, y = player.posY, z = player.posZ;
        boolean flying = player.capabilities.isFlying;
        var equipment = player.inventory.armorInventory.stream().map(ItemStack::copy).toList();
        var result = withInput(player, controls, () -> draconic(player, nativeParameters));
        if (player.world != world || player.isDead || world.getTotalWorldTime() != tick
                || player.posX != x || player.posY != y || player.posZ != z
                || !player.getEntityBoundingBox().equals(bounds) || player.capabilities.isFlying != flying)
            throw new IllegalStateException("DE flight sampling changed actor lifetime/placement/capability");
        for (int i = 0; i < equipment.size(); i++)
            if (!ItemStack.areItemStacksEqual(equipment.get(i), player.inventory.armorInventory.get(i)))
                throw new IllegalStateException("DE flight sampling changed armor slot " + i);
        return result;
    }

    public static <T> T withInput(EntityPlayer player, PlayerPhysicsProtocol.Controls controls, Supplier<T> operation) {
        Input previous = SPRINT_SAMPLE.get();
        // This is the accepted input slot, on either side. A server proxy has no local keyboard.
        SPRINT_SAMPLE.set(new Input(player, controls.sprint()));
        try {
            return operation.get();
        } finally {
            if (previous == null) SPRINT_SAMPLE.remove(); else SPRINT_SAMPLE.set(previous);
        }
    }

    @Optional.Method(modid = "draconicevolution")
    private static NativeFlightParameters draconic(EntityPlayer player, NativeFlightParameters nativeParameters) {
        return DraconicFlightParameters.sample(player, nativeParameters);
    }
}
