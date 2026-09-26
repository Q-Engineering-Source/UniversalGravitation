package wi.unigrav.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.network.ImpactReportMessage;

/**
 * Client-side impact detection for the local player. Player movement is
 * client-authoritative: the exact pre-collision motion and the collision
 * flags ({@code onGround}, {@code collidedHorizontally},
 * {@code collidedVertically}) are only accurate here — the server-side
 * EntityPlayerMP mirrors position via packets but its collision state
 * cannot be sampled reliably at tick END (observed live: zero server-side
 * landing events for the player while mobs detected fine). Impacts are
 * reported to the server with an {@link ImpactReportMessage}, which applies
 * the same damage formula used for mobs.
 *
 * <p>Registered AFTER {@link ClientGravityTicker} (same bus, same phase),
 * so the motion stored at the end of this handler includes this tick's
 * gravity Δv — that is exactly the velocity carried into next tick's
 * vanilla {@code move()}, i.e. next tick's pre-collision speed.
 *
 * <p>Single player / LAN host only (the binder holds the client player
 * binding); on a dedicated server the client has no field state — impact
 * reporting is skipped together with the rest of client-side gravity.
 */
public final class ClientImpactDetector {

    /** Motion at the end of last tick (blocks/tick, post-Δv). */
    private double prevMotionX, prevMotionY, prevMotionZ;
    private boolean prevOnGround, prevCollidedHorizontally;
    private EntityPlayerSP lastPlayer;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (mc.world == null || player == null || !mc.isIntegratedServerRunning()
                || mc.isGamePaused()) {
            return;
        }
        if (PlayerPhysicsClient.drivesMotion(player)) {
            lastPlayer = player;
            snapshot(player);
            return;
        }
        if (player != lastPlayer) {
            // World join / respawn: a fresh player object — never carry
            // impact state across it (spawn fall damage is not a thing).
            lastPlayer = player;
            snapshot(player);
            return;
        }
        if (UnigravConfig.impactDamage && !player.isRiding()
                && UnigravMod.entityBinder.isBound(player)) {
            double safe = UnigravConfig.impactSafeSpeed;
            double vx = prevMotionX * GravityEntityBinder.TICKS_PER_SECOND;
            double vy = prevMotionY * GravityEntityBinder.TICKS_PER_SECOND;
            double vz = prevMotionZ * GravityEntityBinder.TICKS_PER_SECOND;
            if (!prevOnGround && player.onGround && vy < -safe && !player.isInWater()) {
                send(ImpactReportMessage.TYPE_FALL, -vy);
            } else if (player.collidedVertically && vy > safe) {
                send(ImpactReportMessage.TYPE_CEILING, vy);
            }
            if (player.collidedHorizontally && !prevCollidedHorizontally) {
                double horizontal = Math.hypot(vx, vz);
                if (horizontal > safe) {
                    send(ImpactReportMessage.TYPE_WALL, horizontal);
                }
            }
        }
        snapshot(player);
    }

    private void snapshot(EntityPlayerSP player) {
        prevMotionX = player.motionX;
        prevMotionY = player.motionY;
        prevMotionZ = player.motionZ;
        prevOnGround = player.onGround;
        prevCollidedHorizontally = player.collidedHorizontally;
    }

    private static void send(int type, double speed) {
        UnigravMod.NETWORK.sendToServer(new ImpactReportMessage(type, speed));
    }
}
