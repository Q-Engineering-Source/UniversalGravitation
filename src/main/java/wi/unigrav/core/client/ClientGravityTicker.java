package wi.unigrav.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.lwjgl.input.Keyboard;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.TickDriver;
import wi.unigrav.core.client.boost.RemoteBoostClient;
import wi.unigrav.core.client.camera.SixDofBoostMotion;

/**
 * Client-side companion of {@link GravityEntityBinder}: player movement is
 * client-authoritative (local prediction in {@code EntityPlayerSP}), so a
 * server-only &#916;v would be overwritten by the prediction and the player
 * would rubber-band. Each client tick (END) this applies the same field
 * &#916;v to the local player, keeping both sides consistent.
 *
 * <p>Only the local player is ticked client-side: every other entity is
 * server-authoritative and its client copy is just an interpolation mirror.
 *
 * <p>Scope: this runs only against the integrated server (single player /
 * LAN host), where client and server share the in-JVM
 * {@code GravityFields} singleton. On a remote (dedicated) server the client
 * has no field data, so client-side application is skipped and remote
 * players keep vanilla-gravity prediction drift — TODO: field-state sync
 * packet.
 */
public final class ClientGravityTicker {

    /**
     * Creative zero-G toggle (default G): cycles the local creative player
     * between world gravity and weightlessness. Registered by
     * {@code ClientProxy}.
     */
    public static final KeyBinding ZERO_G_KEY = new KeyBinding(
            "key.unigrav.zerog", Keyboard.KEY_G, "Universal Gravitation");

    /**
     * Hold-to-boost key (default left Control): each tick adds an impulse along the
     * look direction (pitch included) to the local player. Outside an active
     * 6-DOF provider it retains the legacy 40 m/s top gear — a debug aid for
     * testing collision damage (ramming mobs, wall/ceiling impacts). Registered by
     * {@code ClientProxy}.
     */
    public static final KeyBinding BOOST_KEY = new KeyBinding(
            "key.unigrav.boost", Keyboard.KEY_LCONTROL, "Universal Gravitation");

    /** Legacy non-6DOF boost acceleration, preserving the previous 4 m/s per tick. */
    private static final double LEGACY_BOOST_ACCELERATION_MPS2 = 80.0;
    /** Legacy non-6DOF boost speed limit in m/s. */
    private static final double LEGACY_BOOST_MAX_SPEED_MPS = 40.0;

    /** Personal zero-G state of the local player (creative mode only). */
    private static boolean zeroG;

    private final GravityEntityBinder binder;

    public ClientGravityTicker(GravityEntityBinder binder) {
        this.binder = binder;
    }

    /** Whether the local creative player is currently using personal zero-G. */
    public static boolean isPersonalZeroGActive(EntityPlayerSP player) {
        if (player == null || !player.isCreative()) {
            return false;
        }
        if (PlayerPhysicsClient.owns(player)) {
            boolean requested = Minecraft.getMinecraft().isIntegratedServerRunning()
                    ? zeroG : RemoteBoostClient.requestedPersonalZeroG();
            return requested && ClientGIntent.acknowledgedOn(player);
        }
        return Minecraft.getMinecraft().isIntegratedServerRunning()
                ? zeroG : RemoteBoostClient.isPersonalZeroGActive(player);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            ClientGIntent.reset();
            return;
        }
        if (!mc.isIntegratedServerRunning()) {
            RemoteBoostClient.tick(binder);
            ClientGIntent.tick(RemoteBoostClient.requestedPersonalZeroG());
            return;
        }
        // While paused (ESC menu), the world does not tick but ClientTickEvent
        // still fires: applying Δv here would accumulate velocity into a
        // frozen player and dump it all on unpause (observed live).
        if (mc.isGamePaused()) {
            return;
        }
        EntityPlayerSP player = mc.player;
        // G key: toggle personal zero-G (creative only). isPressed() drains
        // the click queue, so this also catches presses from this tick.
        while (ZERO_G_KEY.isPressed()) {
            zeroG = !zeroG;
            player.sendStatusMessage(new TextComponentString(
                    "UniGrav zero-G: " + (zeroG ? "ON (creative)" : "OFF")), true);
        }
        // Synchronize the explicit desire, but keep legacy prediction until a complete owner handoff.
        ClientGIntent.tick(zeroG);
        if (PlayerPhysicsClient.owns(player)) return;
        // EntityJoinWorldEvent is not reliable for the client world; sweep
        // explicitly (idempotent) and then apply this tick's Δv. The zero-G
        // toggle only takes effect in creative — survival players always
        // get world gravity (a survival float toggle would be a cheat).
        binder.sweepEntity(player);
        if (!isPersonalZeroGActive(player)) {
            binder.applyTick(player);
        }
        // Hold-to-boost: an active FREE provider owns direction and gear;
        // otherwise retain the legacy Euler-look fallback. The impulse is
        // mass-scaled so acceleration is independent of /unigrav mass.
        if (ClientKeyBindingState.isDown(BOOST_KEY)) {
            ClientBoostRouting.Plan plan = ClientBoostRouting.query(player);
            // An owned command chord blocks this request; it must not fall back to legacy boost.
            if (plan != null && plan.suppressed()) {
                return;
            }
            Vec3 authoritativeDirection = plan != null ? plan.direction() : null;
            Vec3 fallbackDirection = null;
            if (plan == null) {
                Vec3d look = player.getLook(1.0F);
                fallbackDirection = new Vec3(look.x, look.y, look.z);
            }
            double acceleration = plan != null
                    ? plan.accelerationMps2() : LEGACY_BOOST_ACCELERATION_MPS2;
            double speedLimit = plan != null
                    ? plan.speedLimitMps() : LEGACY_BOOST_MAX_SPEED_MPS;
            Vec3 velocity = new Vec3(player.motionX, player.motionY, player.motionZ)
                    .scale(GravityEntityBinder.TICKS_PER_SECOND);
            Vec3 deltaVelocity = SixDofBoostMotion.deltaVelocity(
                    authoritativeDirection, fallbackDirection, velocity,
                    acceleration, speedLimit, TickDriver.TICK_SECONDS);
            if (deltaVelocity.normSquared() != 0.0) {
                BodyPhysics physics = binder.bodyPhysicsOf(player);
                double mass = physics != null ? physics.massKg()
                        : BodyPhysics.estimate(player).massKg();
                binder.addImpulse(player, deltaVelocity.scale(mass));
            }
        }
    }
}
