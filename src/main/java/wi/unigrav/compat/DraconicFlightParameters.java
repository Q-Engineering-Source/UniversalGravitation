package wi.unigrav.compat;

import com.brandon3055.draconicevolution.DEConfig;
import com.brandon3055.draconicevolution.handlers.CustomArmorHandler;
import com.brandon3055.draconicevolution.items.tools.ToolStats;
import net.minecraft.entity.player.EntityPlayer;
import wi.unigrav.core.player.NativeFlightParameters;

/** Isolated compile-only DE ABI; its ArmorSummery also handles DA subclasses and Baubles. */
final class DraconicFlightParameters {
    private DraconicFlightParameters() { }

    static NativeFlightParameters sample(EntityPlayer player, NativeFlightParameters nativeParameters) {
        var summary = new CustomArmorHandler.ArmorSummery().getSummery(player);
        if (summary == null) return nativeParameters;
        String equipment = summary.armorStacks.stream().filter(stack -> !stack.isEmpty())
                .map(stack -> String.valueOf(stack.getItem().getRegistryName())).collect(java.util.stream.Collectors.joining(","));
        if (!DEConfig.enableFlight || !summary.flight[0])
            return new NativeFlightParameters(nativeParameters.flySpeed(), 0, false,
                    summary.jumpModifier * .135F, "draconicevolution/ICustomArmor/no-flight/" + equipment);
        return new NativeFlightParameters(
                0.05F + (0.05F * summary.flightSpeedModifier * (float) ToolStats.FLIGHT_SPEED_MODIFIER),
                summary.flightVModifier, summary.flight[2], summary.jumpModifier * .135F,
                "draconicevolution/ICustomArmor/" + equipment);
    }
}
