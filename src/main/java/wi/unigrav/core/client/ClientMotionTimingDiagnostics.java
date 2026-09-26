package wi.unigrav.core.client;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.MotionTimingWindow;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** All counters are updated on the client thread; Netty only supplies an immutable arrival timestamp. */
public final class ClientMotionTimingDiagnostics {
    public static final ClientMotionTimingDiagnostics INSTANCE = new ClientMotionTimingDiagnostics();
    private final MotionTimingWindow window = new MotionTimingWindow();
    private final ClientMotionTrace trace = new ClientMotionTrace();
    private final long[] mirrorRejected = new long[PlayerPhysicsClientMirror.Rejection.values().length];
    private PlayerPhysicalSnapshot.Scope scope;
    private EntityPlayerSP actor;
    private long revision;
    private ClientMotionTimingDiagnostics() { }

    private boolean ready() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.isGamePaused() || mc.currentScreen != null) {
            actor = null;
            window.reset();
            resetDetail();
            return false;
        }
        if (actor != mc.player) {
            actor = mc.player;
            revision = 0;
            window.reset();
            resetDetail();
            UnigravMod.LOGGER.info("[ugmotion-timing] side=client start player={} dim={} window=5s periodic state-gap-threshold=75ms",
                    actor.getUniqueID(), actor.dimension);
        }
        return true;
    }

    @SubscribeEvent public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ready()) return;
        window.interval(MotionTimingWindow.Signal.FRAME, System.nanoTime());
        trace.render(actor.prevPosX, actor.prevPosY, actor.prevPosZ,
                actor.posX, actor.posY, actor.posZ, event.renderTickTime,
                Math.hypot(Math.hypot(actor.motionX, actor.motionY), actor.motionZ) * 20);
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ready()) return;
        long now = System.nanoTime();
        window.interval(MotionTimingWindow.Signal.CLIENT_TICK, now);
        window.poll(now, true).ifPresent(report -> {
            StringBuilder rejects = new StringBuilder();
            for (var reason : PlayerPhysicsClientMirror.Rejection.values()) {
                long count = mirrorRejected[reason.ordinal()];
                if (count != 0) rejects.append(reason).append('=').append(count).append(' ');
            }
            UnigravMod.LOGGER.info(
                "[ugmotion-timing] side=client player={} dim={} scope={} revision={} y={} vyMps={} owned={} {} {} mirrorRejected[{}]",
                actor.getUniqueID(), actor.dimension, scope, revision, actor.posY, actor.motionY * 20,
                PlayerPhysicsClient.drivesMotion(actor), report, trace.drain(), rejects.toString().trim());
            Arrays.fill(mirrorRejected, 0);
        });
    }

    public void queuedState(long receivedAt) {
        if (!ready()) return;
        long now = System.nanoTime();
        window.interval(MotionTimingWindow.Signal.STATE_RECEIVE, receivedAt);
        window.duration(MotionTimingWindow.Signal.STATE_QUEUE, now - receivedAt, now);
    }

    public void appliedState(PlayerPhysicalSnapshot state) {
        if (!ready()) return;
        if (scope != null && !scope.equals(state.scope())) { resetDetail(); window.reset(); }
        scope = state.scope();
        revision = state.revision();
        var position = state.placement().transport();
        trace.applied(revision, position.x(), position.y(), position.z());
        window.interval(MotionTimingWindow.Signal.STATE_APPLY, System.nanoTime());
    }
    public void stateResult(PlayerPhysicsClientSession.StateDisposition disposition) {
        if (ready()) trace.state(disposition);
    }
    public void rejectedMirror(PlayerPhysicsClientMirror.Rejection reason) {
        if (ready()) mirrorRejected[reason.ordinal()]++;
    }
    private void resetDetail() {
        trace.reset(); scope = null; Arrays.fill(mirrorRejected, 0);
    }
}
