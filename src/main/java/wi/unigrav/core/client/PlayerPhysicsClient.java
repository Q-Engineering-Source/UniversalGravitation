package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.client.boost.RemoteBoostClient;
import wi.unigrav.core.player.PlayerPhysicsNetwork;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.api.math.Vec3;

/** Client-thread physical negotiation and absolute state installation; OFFER/APPLY never install an owner. */
public final class PlayerPhysicsClient {
    private static final PlayerPhysicsClient INSTANCE = new PlayerPhysicsClient();
    private final ClientPhysicsTickQueue tickQueue = new ClientPhysicsTickQueue();
    private final RemoteBoostBoundedDispatcher<Inbound> inbound = new RemoteBoostBoundedDispatcher<>(8,
            tickQueue::schedule);
    private boolean installed;
    private NetHandlerPlayClient connection;
    private EntityPlayerSP player;
    private World world;
    private int dimension;
    private PlayerPhysicsClientSession session;
    private final PlayerPhysicsClientPresentation presentation = new PlayerPhysicsClientPresentation();
    private boolean originalNoGravity;
    private boolean noGravityCaptured;

    private record Inbound(PlayerPhysicsProtocol.Packet packet, NetHandlerPlayClient handler, long receivedAt) {}
    private PlayerPhysicsClient() {}

    public static void install() {
        if (!INSTANCE.installed) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            MinecraftForge.EVENT_BUS.register(ClientMotionTimingDiagnostics.INSTANCE);
            INSTANCE.installed = true;
        }
    }

    public static void receive(PlayerPhysicsProtocol.Packet packet, INetHandler handler) {
        if (!(handler instanceof NetHandlerPlayClient client)) return;
        var reservation = INSTANCE.inbound.reserve(new Inbound(packet, client, System.nanoTime()), INSTANCE::receiveOnClientThread);
        if (reservation == null) {
            UnigravMod.LOGGER.warn("ugmotion1 client queue full; packet dropped without changing physical ownership");
            return;
        }
        INSTANCE.inbound.submit(reservation);
    }

    private void receiveOnClientThread(Inbound incoming) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getConnection() != incoming.handler() || mc.player == null || mc.world == null
                || !mc.player.getUniqueID().equals(incoming.packet().scope().playerId())
                || mc.player.dimension != incoming.packet().scope().dimension()) {
            if (incoming.packet().kind() == PlayerPhysicsProtocol.Kind.STATE)
                ClientMotionTimingDiagnostics.INSTANCE.stateResult(PlayerPhysicsClientSession.StateDisposition.CONTEXT_MISMATCH);
            return;
        }
        if (!targets(incoming.handler(), mc.player, mc.world)) {
            if (incoming.packet().kind() != PlayerPhysicsProtocol.Kind.OFFER) {
                if (incoming.packet().kind() == PlayerPhysicsProtocol.Kind.STATE)
                    ClientMotionTimingDiagnostics.INSTANCE.stateResult(PlayerPhysicsClientSession.StateDisposition.AWAITING_OFFER);
                return;
            }
            clear();
            connection = incoming.handler();
            player = mc.player;
            world = mc.world;
            dimension = player.dimension;
            session = new PlayerPhysicsClientSession(this::applySnapshot, this::retireLegacy,
                    () -> { presentation.reset(); player.setNoGravity(originalNoGravity); });
        }
        if (incoming.packet().kind() == PlayerPhysicsProtocol.Kind.STATE)
            ClientMotionTimingDiagnostics.INSTANCE.queuedState(incoming.receivedAt());
        var receiving = session;
        try {
            receiving.receive(incoming.packet());
        } finally {
            if (incoming.packet().kind() == PlayerPhysicsProtocol.Kind.STATE && session == receiving)
                ClientMotionTimingDiagnostics.INSTANCE.stateResult(receiving.lastStateDisposition());
        }
    }

    private boolean applySnapshot(PlayerPhysicalSnapshot snapshot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!targets(mc.getConnection(), mc.player, mc.world)) return false;
        boolean previousNoGravity = player.hasNoGravity();
        if (!presentation.install(player, snapshot)) {
            ClientMotionTimingDiagnostics.INSTANCE.rejectedMirror(PlayerPhysicsClientMirror.rejection(player, snapshot));
            return false;
        }
        ClientMotionTimingDiagnostics.INSTANCE.appliedState(snapshot);
        if (!noGravityCaptured) { originalNoGravity = previousNoGravity; noGravityCaptured = true; }
        player.setNoGravity(true);
        return targets(mc.getConnection(), mc.player, mc.world);
    }

    private void retireLegacy() {
        if (!noGravityCaptured) { originalNoGravity = player.hasNoGravity(); noGravityCaptured = true; }
        originalNoGravity = RemoteBoostClient.retireForPhysics(player).orElse(originalNoGravity);
    }

    public static boolean owns(EntityPlayer actor) {
        Minecraft mc = Minecraft.getMinecraft();
        return actor == mc.player && INSTANCE.targets(mc.getConnection(), mc.player, mc.world)
                && INSTANCE.session != null && INSTANCE.session.owns();
    }

    public static boolean drivesMotion(EntityPlayer actor) {
        return owns(actor) && INSTANCE.session.drivesMotion() && !PlayerKinematics.requiresNative(actor);
    }

    public static void runLegacyMotion(EntityPlayer actor, Runnable original) {
        Minecraft mc = Minecraft.getMinecraft();
        if (actor == mc.player && INSTANCE.targets(mc.getConnection(), mc.player, mc.world) && INSTANCE.session != null)
            INSTANCE.session.runLegacyMotion(actor, original);
        else original.run();
    }

    /** Runs before the existing Remote FRAME hook for the very same native packet. */
    public static Packet<?> prepareOutgoing(NetHandlerPlayClient handler, Packet<?> packet) {
        if (!(packet instanceof CPacketPlayer movement)) return packet;
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Physics movement emission off client thread");
        if (!INSTANCE.targets(handler, mc.player, mc.world) || INSTANCE.session == null) return packet;
        EntityPlayerSP actor = mc.player;
        return INSTANCE.session.prepare(movement,
                new Vec3d(actor.posX, actor.getEntityBoundingBox().minY, actor.posZ), actor.rotationYaw, actor.rotationPitch,
                RemoteBoostClient.hasCustody(actor), RemoteBoostClient.observeStateApplication(actor),
                PlayerPhysicsNetwork::sendToServer);
    }

    private boolean targets(NetHandlerPlayClient handler, EntityPlayerSP actor, World currentWorld) {
        return actor != null && connection == handler && player == actor && world == currentWorld
                && actor.dimension == dimension && Minecraft.getMinecraft().getConnection() == handler;
    }

    /** Publish physical packets only at a simulation boundary, before native entity history is advanced. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beginClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !Minecraft.getMinecraft().isGamePaused()) tickQueue.drain();
    }

    /** After native prevPos reset; retain accepted history and sample controls only once per client tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!targets(mc.getConnection(), mc.player, mc.world)) { clear(); return; }
        if (!session.drivesMotion() || mc.isGamePaused()) return;
        presentation.tick(player, ClientBoostRouting.queryViewBasis(player));
        PlayerPhysicsProtocol.Controls controls = ClientBoostRouting.queryPhysical(player);
        if (controls == null) {
            boolean active = mc.inGameHasFocus && mc.currentScreen == null;
            var keys = mc.gameSettings;
            double forward = active ? (ClientKeyBindingState.isDown(keys.keyBindForward) ? 1 : 0)
                    - (ClientKeyBindingState.isDown(keys.keyBindBack) ? 1 : 0) : 0;
            double strafe = active ? (ClientKeyBindingState.isDown(keys.keyBindLeft) ? 1 : 0)
                    - (ClientKeyBindingState.isDown(keys.keyBindRight) ? 1 : 0) : 0;
            var look = player.getLook(1.0F);
            controls = PlayerPhysicsInput.keyboard(player.rotationYaw, forward, strafe, new Vec3(look.x, look.y, look.z),
                    active && ClientKeyBindingState.isDown(keys.keyBindJump),
                    active && ClientKeyBindingState.isDown(keys.keyBindSneak), active && player.isSprinting(),
                    active && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY));
        }
        session.input(controls).ifPresent(PlayerPhysicsNetwork::sendToServer);
    }

    /** The native ID fences stale states without reopening the retired writer or discarding a lost OWNED. */
    public static void nativeCorrection(NetHandlerPlayClient handler, int teleportId) {
        if (INSTANCE.connection == handler && INSTANCE.session != null) {
            INSTANCE.presentation.reset();
            INSTANCE.session.nativeCorrection(teleportId);
        }
    }

    private void clear() {
        presentation.reset();
        inbound.invalidate();
        if (session != null) {
            if (session.drivesMotion() && player != null && player.world == world) player.setNoGravity(originalNoGravity);
            session.reset();
        }
        connection = null;
        player = null;
        world = null;
        session = null;
        noGravityCaptured = false;
    }

    public static float bodyYawOffset(EntityPlayer actor, float partialTicks) {
        return drivesMotion(actor) ? INSTANCE.presentation.bodyYawOffset(actor, partialTicks) : 0;
    }

    @SubscribeEvent public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote && event.getWorld() == world) clear();
    }
    @SubscribeEvent public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (connection != null && connection.getNetworkManager() == event.getManager()) clear();
        });
    }
}
