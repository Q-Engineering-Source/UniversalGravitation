package wi.unigrav.core;

import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import wi.unigrav.UnigravMod;

/** Server-thread only; measures cadence and work separately, independently of client FPS. */
public final class ServerMotionTimingDiagnostics {
    private final MotionTimingWindow window = new MotionTimingWindow();
    private long tickStart;
    private boolean active;
    private boolean inTick;

    public void start() { window.reset(); active = true; inTick = false; }
    public void stop() { active = false; inTick = false; window.reset(); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void begin(TickEvent.ServerTickEvent event) {
        if (!active || event.phase != TickEvent.Phase.START) return;
        tickStart = System.nanoTime();
        inTick = true;
        window.interval(MotionTimingWindow.Signal.SERVER_TICK, tickStart);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void end(TickEvent.ServerTickEvent event) {
        if (!active || !inTick || event.phase != TickEvent.Phase.END) return;
        long now = System.nanoTime();
        window.duration(MotionTimingWindow.Signal.SERVER_WORK, now - tickStart, now);
        inTick = false;
        window.poll(now).ifPresent(report -> UnigravMod.LOGGER.info("[ugmotion-timing] side=server {}", report));
    }
}
