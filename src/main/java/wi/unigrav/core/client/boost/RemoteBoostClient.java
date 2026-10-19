package wi.unigrav.core.client.boost;

import java.util.Optional;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.TickDriver;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.BoostNetwork;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.boost.RemoteBoostCorridor;
import wi.unigrav.core.client.ClientBoostRouting;
import wi.unigrav.core.client.ClientGravityTicker;
import wi.unigrav.core.client.ClientKeyBindingState;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.client.camera.SixDofBoostMotion;

/**
 * Client half of the Creative-only remote boost experiment. The server owns
 * permission, finite movement admission and native movement reconciliation.
 * This client never fabricates remote field state; it owns only the
 * acknowledged local no-gravity presentation and Q-forward prediction needed
 * to submit ordinary vanilla movement packets.
 */
public final class RemoteBoostClient {

    private static final double SETTLED_SPEED_MPS = 0.05;
    private static final Logger LOGGER = LogManager.getLogger();
    private static final RemoteBoostClient INSTANCE = new RemoteBoostClient();

    private final RemoteBoostSession session;
    private final RemoteBoostCarry carry = new RemoteBoostCarry();
    private boolean installed;
    private NetHandlerPlayClient connection;
    private EntityPlayerSP owner;
    private World ownerWorld;
    private boolean ownedNoGravity;
    private boolean noGravityCaptured;
    private boolean noGravityBeforeOwnership;
    private RemoteBoostCadence.Marker lastEmission;
    private Vec3 lastFiniteVelocityHint = Vec3.ZERO;
    private final RemoteBoostNativeCorrectionCue nativeCorrectionCue =
            new RemoteBoostNativeCorrectionCue();
    private int lastReasonNoticeTick = Integer.MIN_VALUE;
    private BoostProtocol.Reason lastReasonNotice;

    private RemoteBoostClient() {
        session = new RemoteBoostSession(packet -> applyOperation(owner, packet));
    }

    /** Installs idempotent lifecycle cleanup for the singleton client session. */
    public static synchronized void install() {
        if (!INSTANCE.installed) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            INSTANCE.installed = true;
        }
    }

    /**
     * Schedules a received common boost packet on the client game thread and
     * rejects a stale handler, connection, epoch, serial or world identity.
     */
    public static void receive(BoostProtocol.Packet packet, INetHandler handler) {
        if (!(handler instanceof NetHandlerPlayClient clientHandler) || packet == null) {
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> INSTANCE.receiveOnClientThread(packet, clientHandler));
    }

    /** Runs remote G/boost controls from the existing ClientTick END owner. */
    public static void tick(GravityEntityBinder binder) {
        INSTANCE.tickOnClientThread(binder);
    }

    /** Server-acknowledged and locally requested Creative personal zero-G only. */
    public static boolean isPersonalZeroGActive(EntityPlayerSP player) {
        return player != null && player.isCreative() && INSTANCE.targets(player)
                && INSTANCE.session.personalZeroG();
    }

    /** ACTIVE and voluntary-off CLOSING retain packet-pairing custody. */
    public static boolean hasCustody(EntityPlayerSP player) {
        return player != null && INSTANCE.targets(player) && INSTANCE.session.hasCustody();
    }

    /** Persistent key intent after the existing key drain, not a movement/propulsion grant. */
    public static boolean requestedPersonalZeroG() { return INSTANCE.session.requestedIntent(); }

    /** Records an explicit client choice; only a current server STATE can confirm it. */
    public static boolean requestPersonalZeroG(boolean enabled) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Remote G intent requested off client thread");
        }
        EntityPlayerSP player = minecraft.player;
        if (minecraft.world == null || player == null || player.world != minecraft.world
                || !player.isCreative()) return false;
        if (!enabled) INSTANCE.nativeCorrectionCue.clearPending();
        BoostProtocol.Packet request = INSTANCE.session.requestMode(enabled, player.dimension);
        if (request != null) {
            if (enabled) INSTANCE.captureNoGravity(player);
            BoostNetwork.sendToServer(request);
        }
        player.sendStatusMessage(new TextComponentTranslation(enabled
                ? "message.unigrav.remote_boost.requested_on"
                : "message.unigrav.remote_boost.requested_off"), true);
        return true;
    }

    /** Exact server-confirmed mode for the current actor; READY is confirmed OFF. */
    public static Optional<Boolean> acknowledgedPersonalZeroG(EntityPlayerSP player) {
        if (player == null || !INSTANCE.targets(player)) return Optional.empty();
        return switch (INSTANCE.session.mode()) {
            case ACTIVE -> Optional.of(true);
            case READY -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** Same-client-thread transfer: retire old writes without restoring noGravity underneath the new owner. */
    public static Optional<Boolean> retireForPhysics(EntityPlayerSP actor) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread()) throw new IllegalStateException("Physics retirement off client thread");
        if (!INSTANCE.targets(actor)) return Optional.empty();
        Optional<Boolean> original = INSTANCE.noGravityCaptured ? Optional.of(INSTANCE.noGravityBeforeOwnership) : Optional.empty();
        INSTANCE.session.retireForPhysics();
        INSTANCE.ownedNoGravity = false;
        INSTANCE.noGravityCaptured = false;
        INSTANCE.carry.clear();
        INSTANCE.lastEmission = null;
        INSTANCE.nativeCorrectionCue.clear();
        return original;
    }

    /** Actual client-thread application evidence for the current actor/connection only. */
    public static Optional<BoostProtocol.StateApplication> observeStateApplication(EntityPlayerSP player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Boost application observation off client thread");
        }
        return INSTANCE.targets(player) ? INSTANCE.session.applicationReceipt() : Optional.empty();
    }

    /** Current local effective carried ceiling in m/s, or zero without remote custody. */
    public static double currentCeilingMps(EntityPlayerSP player) {
        return hasCustody(player) ? INSTANCE.carry.currentCeilingMps() : 0.0;
    }

    /** Effective propulsion target; the selected gear itself is never rewritten. */
    public static double propulsionTargetMps(EntityPlayerSP player, double selectedMps) {
        return hasCustody(player) ? INSTANCE.propulsionTarget(player, selectedMps) : selectedMps;
    }

    /** Current client-visible corridor classification (local feet-Y or fresh server STATE). */
    public static boolean inRestrictedCorridor(EntityPlayerSP player) {
        return hasCustody(player) && INSTANCE.effectiveRestricted(player);
    }

    /**
     * Called at the head of the exact runtime NetHandlerPlayClient.sendPacket
     * seam. A FRAME is issued immediately before, and on the same connection
     * as, every owned position-bearing vanilla player sample.
     */
    public static Packet<?> prepareOutgoingPacket(NetHandlerPlayClient handler, Packet<?> packet) {
        return INSTANCE.prepareOutgoing(handler, PlayerPhysicsClient.prepareOutgoing(handler, packet));
    }

    /**
     * Existing client Mixin seam: invoked on the client thread after
     * PacketThreadUtil's hop and before native absolute PositionLook clears
     * motion. Relative corrections do not clear motion and need no cue.
     */
    public static void captureNativePositionLook(NetHandlerPlayClient handler,
            SPacketPlayerPosLook packet) {
        PlayerPhysicsClient.nativeCorrection(handler, packet.getTeleportId());
        INSTANCE.captureNativePositionLookOnClientThread(handler, packet);
    }

    private void receiveOnClientThread(BoostProtocol.Packet packet, NetHandlerPlayClient handler) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isIntegratedServerRunning() || minecraft.getConnection() != handler) {
            return;
        }
        EntityPlayerSP player = minecraft.player;
        if (PlayerPhysicsClient.owns(player)) return;
        if (packet.kind() == BoostProtocol.Kind.OFFER) {
            if (owner != null && (owner != player || ownerWorld != minecraft.world || connection != handler)) {
                clearOwned();
            }
            if (player == null || minecraft.world == null || !uiHooksReady()
                    || packet.dimension() != player.dimension || !session.acceptOffer(packet)) {
                return;
            }
            connection = handler;
            owner = player;
            ownerWorld = minecraft.world;
            BoostNetwork.sendToServer(BoostProtocol.hello(packet.nonce(), true));
            return;
        }
        BoostProtocol.Mode previousMode = session.mode();
        if (connection != handler || !targets(player)) {
            return;
        }
        boolean freshIdentity = session.matchesCurrentFreshState(packet, player.dimension);
        if (!session.acceptState(packet, player.dimension)) {
            if (freshIdentity
                    && (packet.mode() == BoostProtocol.Mode.ACTIVE
                    || packet.mode() == BoostProtocol.Mode.CLOSING)
                    && !RemoteBoostCap.validRemoteCeiling(packet.ceilingMps())) {
                containInvalidMotion(player, packet);
            }
            return;
        }
        updateOwnedNoGravity(player);
        showStateNotice(player, RemoteBoostPresentation.notice(previousMode, packet), packet);
        sendEligibleModeRequest(player);
    }

    private void tickOnClientThread(GravityEntityBinder binder) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.isIntegratedServerRunning()) {
            return;
        }
        if (owner != null && (minecraft.player != owner || minecraft.world != ownerWorld)) {
            clearOwned();
        }
        if (minecraft.world == null || minecraft.player == null) return;
        EntityPlayerSP player = minecraft.player;
        if (PlayerPhysicsClient.owns(player)) {
            return;
        }
        if (!targets(player)) {
            return;
        }
        if (minecraft.isGamePaused()) {
            return;
        }
        updateOwnedNoGravity(player);
        sendEligibleModeRequest(player);
        if (!applyEffectiveCarrier(player)) {
            return;
        }
        if (isPersonalZeroGActive(player) && minecraft.currentScreen == null
                && minecraft.inGameHasFocus && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY)) {
            ClientBoostRouting.Plan plan = ClientBoostRouting.query(player);
            // Remote boost never falls back to Euler look or a second thrust source.
            if (plan != null && !plan.suppressed() && validGear(plan.gear())) {
                Vec3 velocity = new Vec3(player.motionX, player.motionY, player.motionZ)
                        .scale(GravityEntityBinder.TICKS_PER_SECOND);
                Vec3 deltaVelocity = SixDofBoostMotion.deltaVelocity(plan.direction(), null, velocity,
                        plan.accelerationMps2(), propulsionTarget(player, plan.speedLimitMps()),
                        TickDriver.TICK_SECONDS);
                if (deltaVelocity.normSquared() != 0.0) {
                    BodyPhysics physics = binder.bodyPhysicsOf(player);
                    double mass = physics != null ? physics.massKg() : BodyPhysics.estimate(player).massKg();
                    binder.addImpulse(player, deltaVelocity.scale(mass));
                }
            }
        }
        emitFallbackPositionSample(player);
    }

    private Packet<?> prepareOutgoing(NetHandlerPlayClient handler, Packet<?> packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (minecraft.isIntegratedServerRunning() || handler != connection || !targets(player)) {
            return packet;
        }
        if (!session.hasCustody()) {
            return packet;
        }
        if (packet instanceof CPacketConfirmTeleport) {
            nativeCorrectionCue.confirmSent();
            return packet;
        }
        if (!(packet instanceof CPacketPlayer playerPacket)) {
            return packet;
        }
        ClientBoostRouting.Plan plan = ClientBoostRouting.query(player);
        // FRAME is a custody/coasting acknowledgement, not a thrust request:
        // retain the last acknowledged gear if a UIE provider is temporarily
        // inactive while the server is closing the session.
        int gear = plan != null && validGear(plan.gear()) ? plan.gear() : session.gear();
        double speedMps = Math.sqrt(player.motionX * player.motionX + player.motionY * player.motionY
                + player.motionZ * player.motionZ) * GravityEntityBinder.TICKS_PER_SECOND;
        boolean settled = !ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY)
                && Double.isFinite(speedMps) && speedMps <= SETTLED_SPEED_MPS;
        Vec3 continuityHint = nativeCorrectionCue.consumeEchoHint();
        BoostProtocol.Packet frame = session.nextFrame(player.dimension, gear, settled,
                continuityHint != null ? continuityHint : finiteMotionHint(player));
        if (frame != null) {
            BoostNetwork.sendToServer(frame);
            lastEmission = marker(player);
        }
        if (RemoteBoostPacketPlan.action(isPositionBearing(playerPacket))
                == RemoteBoostPacketPlan.Action.KEEP) {
            return packet;
        }
        // Preserve a rotation-only packet's explicit camera angles while
        // making its current player position available to the server budget.
        if (packet instanceof CPacketPlayer.Rotation) {
            return new CPacketPlayer.PositionRotation(player.posX,
                    player.getEntityBoundingBox().minY, player.posZ,
                    playerPacket.getYaw(player.rotationYaw),
                    playerPacket.getPitch(player.rotationPitch), playerPacket.isOnGround());
        }
        return new CPacketPlayer.Position(player.posX,
                player.getEntityBoundingBox().minY, player.posZ, playerPacket.isOnGround());
    }

    private boolean applyOperation(EntityPlayerSP player, BoostProtocol.Packet packet) {
        // The protocol carries full-precision m/s. Convert only at this
        // EntityPlayerSP motion seam, whose storage unit is blocks/tick.
        switch (packet.operation()) {
            case ADD -> {
                if (!setMotionMps(player, RemoteBoostVelocity.clamp(
                        motionMps(player).add(packet.velocity()), session.ceilingMps(),
                        UnigravConfig.maxEntitySpeed))) {
                    containInvalidMotion(player, packet);
                    return false;
                }
            }
            case REPLACE -> {
                if (!setMotionMps(player, RemoteBoostVelocity.clamp(packet.velocity(),
                        session.ceilingMps(), UnigravConfig.maxEntitySpeed))) {
                    containInvalidMotion(player, packet);
                    return false;
                }
                nativeCorrectionCue.clearPending();
            }
            case STOP -> {
                player.motionX = 0.0;
                player.motionY = 0.0;
                player.motionZ = 0.0;
            }
            case NONE -> {
                // The server's state/permission acknowledgement needs no velocity mutation.
            }
        }
        return true;
    }

    private void updateOwnedNoGravity(EntityPlayerSP player) {
        boolean shouldOwn = session.personalZeroG();
        if (shouldOwn && !ownedNoGravity) {
            captureNoGravity(player);
            player.setNoGravity(true);
            ownedNoGravity = true;
        } else if (!shouldOwn && noGravityCaptured) {
            if (ownedNoGravity) {
                owner.setNoGravity(noGravityBeforeOwnership);
            }
            ownedNoGravity = false;
            noGravityCaptured = false;
        }
    }

    private void captureNativePositionLookOnClientThread(NetHandlerPlayClient handler,
            SPacketPlayerPosLook packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = minecraft.player;
        if (packet == null || minecraft.isIntegratedServerRunning() || handler != connection
                || !targets(player) || !session.hasCustody() || !hasAbsolutePosition(packet)) {
            return;
        }
        Vec3 preZeroMotion = motionMps(player);
        if (!BoostProtocol.finite(preZeroMotion)) {
            return;
        }
        // Refresh the normal continuity cache from the actual value at the
        // only valid point before vanilla clears absolute-correction motion.
        lastFiniteVelocityHint = preZeroMotion;
        nativeCorrectionCue.capturePreZero(preZeroMotion);
    }

    private static boolean hasAbsolutePosition(SPacketPlayerPosLook packet) {
        return !packet.getFlags().contains(SPacketPlayerPosLook.EnumFlags.X)
                || !packet.getFlags().contains(SPacketPlayerPosLook.EnumFlags.Y)
                || !packet.getFlags().contains(SPacketPlayerPosLook.EnumFlags.Z);
    }

    private boolean targets(EntityPlayerSP player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return player != null && player == owner && player == minecraft.player
                && ownerWorld != null && minecraft.world == ownerWorld && player.world == ownerWorld
                && connection != null
                && minecraft.getConnection() == connection
                && session.targetsDimension(player.dimension);
    }

    private static boolean uiHooksReady() {
        return UnigravConfig.sixDofCamera && ClientBoostRouting.hasProvider();
    }

    private static boolean validGear(int gear) {
        return gear >= 1 && gear <= 9;
    }

    /** One END-tick carrier sample; STATE handling must never restart this ramp. */
    private boolean applyEffectiveCarrier(EntityPlayerSP player) {
        if (!session.hasCustody()) {
            return true;
        }
        Vec3 velocity = motionMps(player);
        if (!BoostProtocol.finite(velocity)) {
            containInvalidMotion(player, null);
            return false;
        }
        double carrier = carry.sample(session.ceilingMps(), velocity.norm(),
                effectiveRestricted(player), TickDriver.TICK_SECONDS);
        if (!setMotionMps(player, RemoteBoostVelocity.clamp(velocity, carrier,
                UnigravConfig.maxEntitySpeed))) {
            containInvalidMotion(player, null);
            return false;
        }
        return true;
    }

    /** Exact cached 1.12.2 packet shapes; CPacketPlayer exposes no isMoving accessor. */
    private static boolean isPositionBearing(CPacketPlayer packet) {
        return packet instanceof CPacketPlayer.Position
                || packet instanceof CPacketPlayer.PositionRotation;
    }

    private void emitFallbackPositionSample(EntityPlayerSP player) {
        if (!session.hasCustody() || !RemoteBoostCadence.needsFallback(lastEmission, marker(player))) {
            return;
        }
        connection.sendPacket(new CPacketPlayer.Position(player.posX,
                player.getEntityBoundingBox().minY, player.posZ, player.onGround));
    }

    private RemoteBoostCadence.Marker marker(EntityPlayerSP player) {
        return new RemoteBoostCadence.Marker(player.getEntityId(), player.ticksExisted, session.epoch());
    }

    private static Vec3 motionMps(EntityPlayerSP player) {
        return new Vec3(player.motionX, player.motionY, player.motionZ)
                .scale(GravityEntityBinder.TICKS_PER_SECOND);
    }

    private static boolean setMotionMps(EntityPlayerSP player, Vec3 velocityMps) {
        if (velocityMps == null) {
            return false;
        }
        Vec3 velocityPerTick = velocityMps.scale(1.0 / GravityEntityBinder.TICKS_PER_SECOND);
        player.motionX = velocityPerTick.x();
        player.motionY = velocityPerTick.y();
        player.motionZ = velocityPerTick.z();
        return true;
    }

    private boolean effectiveRestricted(EntityPlayerSP player) {
        return session.restrictedCorridor()
                || RemoteBoostCorridor.isRestricted(player.getEntityBoundingBox().minY);
    }

    private double propulsionTarget(EntityPlayerSP player, double selectedMps) {
        double carrier = carry.currentCeilingMps();
        if (!Double.isFinite(carrier) || carrier <= 0.0 || !Double.isFinite(selectedMps)
                || selectedMps < 0.0) {
            return 0.0;
        }
        return RemoteBoostCorridor.propulsionTarget(selectedMps, carrier, effectiveRestricted(player));
    }

    private void sendEligibleModeRequest(EntityPlayerSP player) {
        BoostProtocol.Packet request = session.nextEligibleModeRequest(player.dimension);
        if (request != null) {
            BoostNetwork.sendToServer(request);
        }
    }

    private void captureNoGravity(EntityPlayerSP player) {
        if (!noGravityCaptured) {
            noGravityBeforeOwnership = player.hasNoGravity();
            noGravityCaptured = true;
        }
    }

    /** Local corruption containment never changes the user's G intent or transport ownership. */
    private void containInvalidMotion(EntityPlayerSP player, BoostProtocol.Packet packet) {
        session.invalidateApplication();
        Vec3 current = motionMps(player);
        Vec3 finite = BoostProtocol.finite(current) ? current : Vec3.ZERO;
        Vec3 bounded = RemoteBoostVelocity.clamp(finite,
                RemoteBoostCorridor.RESTRICTED_CEILING_MPS, UnigravConfig.maxEntitySpeed);
        if (!setMotionMps(player, bounded)) {
            player.motionX = 0.0;
            player.motionY = 0.0;
            player.motionZ = 0.0;
        }
        if (packet != null) {
            showStateNotice(player, RemoteBoostPresentation.Notice.reason(
                    BoostProtocol.Reason.INVALID_INPUT), packet);
        } else {
            player.sendStatusMessage(new TextComponentTranslation(
                    "message.unigrav.remote_boost.reason",
                    new TextComponentTranslation("message.unigrav.remote_boost.reason.invalid_input")), true);
        }
    }

    private void showUnavailable(EntityPlayerSP player) {
        player.sendStatusMessage(new TextComponentTranslation(
                "message.unigrav.remote_boost.unavailable"), true);
    }

    private void showStateNotice(EntityPlayerSP player, RemoteBoostPresentation.Notice notice,
            BoostProtocol.Packet packet) {
        if (notice == null) {
            return;
        }
        if (notice.kind() == RemoteBoostPresentation.Kind.REASON
                && notice.reason() == lastReasonNotice
                && player.ticksExisted - lastReasonNoticeTick < 10) {
            return;
        }
        if (notice.kind() == RemoteBoostPresentation.Kind.REASON) {
            lastReasonNotice = notice.reason();
            lastReasonNoticeTick = player.ticksExisted;
        }
        TextComponentTranslation reason = new TextComponentTranslation(reasonKey(notice.reason()));
        TextComponentTranslation message = switch (notice.kind()) {
            case MODE -> new TextComponentTranslation("message.unigrav.remote_boost.mode."
                    + notice.mode().name().toLowerCase(java.util.Locale.ROOT));
            case REASON -> new TextComponentTranslation("message.unigrav.remote_boost.reason", reason);
            case SAFETY_STOP -> new TextComponentTranslation("message.unigrav.remote_boost.safety_stop", reason);
        };
        player.sendStatusMessage(message, true);
        BoostProtocol.Reason loggedReason = notice.kind() == RemoteBoostPresentation.Kind.MODE
                ? packet.reason() : notice.reason();
        if (notice.kind() == RemoteBoostPresentation.Kind.SAFETY_STOP
                || notice.kind() == RemoteBoostPresentation.Kind.REASON) {
            LOGGER.warn("Remote boost state nonce={} epoch={} serial={} mode={} reason={} ceilingMps={}",
                    packet.nonce(), packet.epoch(), packet.sequence(), packet.mode(), loggedReason,
                    packet.ceilingMps());
        } else {
            LOGGER.info("Remote boost state nonce={} epoch={} serial={} mode={} reason={} ceilingMps={}",
                    packet.nonce(), packet.epoch(), packet.sequence(), packet.mode(), loggedReason,
                    packet.ceilingMps());
        }
    }

    private static String reasonKey(BoostProtocol.Reason reason) {
        return "message.unigrav.remote_boost.reason."
                + reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    private void clearOwned() {
        if (owner != null && owner.world == ownerWorld && ownedNoGravity) {
            owner.setNoGravity(noGravityBeforeOwnership);
        }
        ownedNoGravity = false;
        noGravityCaptured = false;
        connection = null;
        owner = null;
        ownerWorld = null;
        lastEmission = null;
        lastFiniteVelocityHint = Vec3.ZERO;
        nativeCorrectionCue.clear();
        lastReasonNotice = null;
        lastReasonNoticeTick = Integer.MIN_VALUE;
        carry.clear();
        session.reset();
    }

    private Vec3 finiteMotionHint(EntityPlayerSP player) {
        Vec3 motion = motionMps(player);
        if (BoostProtocol.finite(motion)) {
            lastFiniteVelocityHint = motion;
        }
        return lastFiniteVelocityHint;
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (!(event.getHandler() instanceof NetHandlerPlayClient expected)) {
            return;
        }
        NetworkManager manager = event.getManager();
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (connection == expected && expected.getNetworkManager() == manager) {
                clearOwned();
            }
        });
    }

    @SubscribeEvent
    public void onClientWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote && event.getWorld() == ownerWorld) {
            clearOwned();
        }
    }
}
