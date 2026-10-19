package wi.unigrav.probe;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Client-only lifecycle entry for the isolated observation mod; it never alters game state. */
@Mod(modid = "ugacceptedprobe", name = "UG Accepted Consumer Probe", version = "1.0.0-wip",
        clientSideOnly = true, acceptableRemoteVersions = "*")
public final class AcceptedConsumerProbeMod {
    @Mod.EventHandler
    public void preInitialize(FMLPreInitializationEvent event) {
        ProbeHooks.initialize();
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) ProbeHooks.onProbeClientTick();
    }
}