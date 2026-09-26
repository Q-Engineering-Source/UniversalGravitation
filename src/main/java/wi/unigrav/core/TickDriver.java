package wi.unigrav.core;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.sim.NBodySimulation;

/**
 * Thin Minecraft-facing driver: advances a {@link NBodySimulation} by one
 * server tick ({@value #TICK_SECONDS} s) per {@link TickEvent.ServerTickEvent}
 * END phase. Entity binding is deliberately out of scope for now; this class
 * only owns the simulation clock and a convenience registration entry point
 * into the global {@link GravityField}.
 */
public final class TickDriver {

    /** Duration of one server tick, in seconds. */
    public static final double TICK_SECONDS = 0.05;

    private final NBodySimulation simulation;

    public TickDriver(NBodySimulation simulation) {
        this.simulation = simulation;
    }

    public NBodySimulation simulation() {
        return simulation;
    }

    /** Subscribes this driver to the Forge event bus. */
    public void registerOnEventBus() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Convenience entry point: register a gravity source into the global field. */
    public static GravityField.Registration registerSource(
            String namespace, String path, GravitySource source) {
        return GravityFields.get().register(namespace, path, source);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            simulation.advanceTo(simulation.time() + TICK_SECONDS);
        }
    }
}
