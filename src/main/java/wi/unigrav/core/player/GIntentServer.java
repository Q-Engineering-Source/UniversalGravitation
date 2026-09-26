package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.boost.RemoteBoostServer;

/** Exact-source G observation. Ordinary physics peers negotiate a separate, bounded intent lifetime. */
public final class GIntentServer {
    public static final GIntentServer INSTANCE = new GIntentServer(GIntentNetwork::sendTo);
    private final BiConsumer<EntityPlayerMP, GIntentProtocol.Packet> outbound;
    private final RemoteBoostServer remote;
    private final Map<NetworkManager, Capability> capabilities = new IdentityHashMap<>();
    private final Map<NetworkManager, Session> sessions = new IdentityHashMap<>();
    private boolean installed;
    private long serverTick;

    /** The transport is a system seam; session admission and dispatch always remain here. */
    public GIntentServer(BiConsumer<EntityPlayerMP, GIntentProtocol.Packet> outbound) {
        this(outbound, RemoteBoostServer.INSTANCE);
    }

    public GIntentServer(BiConsumer<EntityPlayerMP, GIntentProtocol.Packet> outbound, RemoteBoostServer remote) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    /** Game-thread read only. Missing source is UNKNOWN, never implicit OFF or permission. */
    public Optional<GIntentObservation> observe(EntityPlayerMP player) {
        if (!player.getServerWorld().isCallingFromMinecraftThread()) throw new IllegalStateException("G read off-thread");
        // A live legacy source wins until its explicit physical handoff. Never merge two intents.
        Optional<GIntentObservation> legacy = remote.observePersonalG(player);
        return legacy.isPresent() ? legacy : observeNegotiated(player);
    }

    /** Confirm the independent source before retiring the legacy MODE writer. */
    public Optional<GIntentObservation> observeNegotiated(EntityPlayerMP player) {
        if (!player.getServerWorld().isCallingFromMinecraftThread()) throw new IllegalStateException("G read off-thread");
        synchronized (this) {
            Session session = sessions.get(player.connection.netManager);
            if (session == null || session.player != player || !current(session)) return Optional.empty();
            return session.intent.observe(creativeEligible(player)).map(on -> new GIntentObservation(
                    session.intent.key(), capabilities.get(session.manager).local
                            ? GIntentObservation.Source.INTEGRATED_INTENT : GIntentObservation.Source.NEGOTIATED_INTENT, 0,
                    session.intent.revision(), on));
        }
    }

    /** Untrusted Netty ingress reserves bounded work; scheduling occurs outside this monitor. */
    public void receive(GIntentProtocol.Packet packet, NetHandlerPlayServer handler) {
        RemoteBoostBoundedDispatcher.Reservation<GIntentProtocol.Packet> reservation;
        Session session;
        boolean reportOverflow = false;
        synchronized (this) {
            if (packet == null || handler == null) return;
            session = sessions.get(handler.netManager);
            if (session == null || session.handler != handler || !channelCurrent(session.manager)) return;
            reservation = session.dispatcher.reserve(packet, value -> dispatch(session, value));
            if (reservation == null && !session.overflowReported) {
                session.overflowReported = true;
                reportOverflow = true;
            }
        }
        if (reportOverflow) UnigravMod.LOGGER.warn("ugphys1 G-intent queue full; excess packets dropped, intent unchanged");
        if (reservation != null) session.dispatcher.submit(reservation);
    }

    private void dispatch(Session session, GIntentProtocol.Packet packet) {
        if (!session.world.isCallingFromMinecraftThread()) throw new IllegalStateException("G dispatch off-thread");
        Optional<GIntentProtocol.Packet> response;
        synchronized (this) {
            if (!current(session)) return;
            response = session.intent.receive(packet, creativeEligible(session.player));
        }
        response.ifPresent(reply -> outbound.accept(session.player, reply));
    }

    @SubscribeEvent
    public synchronized void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER) return;
        boolean intent = event.getRegistrations().contains(GIntentProtocol.CHANNEL);
        boolean physics = event.getRegistrations().contains(PlayerPhysicsProtocol.CHANNEL);
        if (!intent && !physics) return;
        Capability capability = capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability());
        if ("REGISTER".equals(event.getOperation())) {
            if (intent) capability.registered = true;
            if (physics) capability.physicsRegistered = true;
        }
        else if ("UNREGISTER".equals(event.getOperation())) capability.retired = true;
    }

    @SubscribeEvent
    public synchronized void onConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        Capability capability = capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability());
        capability.connected = true;
        capability.local = event.isLocal();
    }

    @SubscribeEvent
    public synchronized void onDisconnect(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).retired = true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var offers = new ArrayList<Session>();
        synchronized (this) {
            serverTick++;
            for (NetworkManager manager : capabilities.keySet().toArray(NetworkManager[]::new)) {
                Capability capability = capabilities.get(manager);
                if (capability.retired || !manager.isChannelOpen()) {
                    retire(manager);
                    capabilities.remove(manager);
                    continue;
                }
                if (!capability.connected || !capability.registered || !sourceAllowed(capability)
                        || !(manager.getNetHandler() instanceof NetHandlerPlayServer handler)) continue;
                if (!handler.player.getServerWorld().isCallingFromMinecraftThread()) {
                    throw new IllegalStateException("G connection attachment off-thread");
                }
                Session existing = sessions.get(manager);
                if (existing != null && !current(existing)) { retire(manager); existing = null; }
                if (existing == null && actorCurrent(handler, manager)) {
                    Session fresh = new Session(handler);
                    fresh.lastOfferTick = serverTick;
                    sessions.put(manager, fresh);
                    offers.add(fresh);
                } else if (existing != null && !existing.intent.negotiated()
                        && serverTick - existing.lastOfferTick >= 20) {
                    existing.lastOfferTick = serverTick;
                    offers.add(existing);
                }
            }
        }
        // No game-thread scheduler or network submission while holding the registry monitor.
        for (Session session : offers) outbound.accept(session.player, new GIntentProtocol.Packet(
                GIntentProtocol.Kind.OFFER, session.intent.key(), 0, GIntentProtocol.State.UNKNOWN));
    }

    @SubscribeEvent public void onLogin(PlayerEvent.PlayerLoggedInEvent event) { retirePlayer(event); }
    @SubscribeEvent public void onRespawn(PlayerEvent.PlayerRespawnEvent event) { retirePlayer(event); }
    @SubscribeEvent public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) { retirePlayer(event); }
    @SubscribeEvent public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) { retirePlayer(event); }

    private synchronized void retirePlayer(PlayerEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) return;
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.player == player || session.intent.key().playerId().equals(player.getUniqueID())) retire(session.manager);
        }
    }

    @SubscribeEvent public synchronized void onWorldUnload(WorldEvent.Unload event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.world == event.getWorld()) retire(session.manager);
        }
    }

    public synchronized void stop() {
        for (Session session : sessions.values()) session.dispatcher.invalidate();
        sessions.clear();
        capabilities.clear();
    }

    private boolean current(Session session) {
        return sessions.get(session.manager) == session && channelCurrent(session.manager)
                && session.handler.player == session.player && session.player.world == session.world
                && session.player.dimension == session.intent.key().dimension()
                && session.player.getUniqueID().equals(session.intent.key().playerId())
                && actorCurrent(session.handler, session.manager);
    }

    private boolean channelCurrent(NetworkManager manager) {
        Capability capability = capabilities.get(manager);
        return capability != null && capability.connected && sourceAllowed(capability) && capability.registered
                && !capability.retired && manager.isChannelOpen();
    }

    private static boolean sourceAllowed(Capability capability) {
        return capability.local || capability.physicsRegistered;
    }

    private static boolean actorCurrent(NetHandlerPlayServer handler, NetworkManager manager) {
        EntityPlayerMP player = handler.player;
        return player != null && player.connection == handler && handler.netManager == manager
                && manager.getNetHandler() == handler && player.getServer() != null
                && player.world instanceof WorldServer
                && DimensionManager.getWorld(player.dimension) == player.world
                && player.dimension == player.world.provider.getDimension()
                && !player.isDead && player.isEntityAlive();
    }

    private static boolean creativeEligible(EntityPlayerMP player) {
        return player.isCreative() && !player.isSpectator() && !player.isDead && player.isEntityAlive();
    }

    private void retire(NetworkManager manager) {
        Session session = sessions.remove(manager);
        if (session != null) session.dispatcher.invalidate();
    }

    private static final class Capability {
        private boolean connected;
        private boolean registered;
        private boolean local;
        private boolean physicsRegistered;
        private boolean retired;
    }

    private static final class Session {
        private final NetHandlerPlayServer handler;
        private final NetworkManager manager;
        private final EntityPlayerMP player;
        private final WorldServer world;
        private final GIntentSession intent;
        private final RemoteBoostBoundedDispatcher<GIntentProtocol.Packet> dispatcher;
        private boolean overflowReported;
        private long lastOfferTick;

        private Session(NetHandlerPlayServer handler) {
            this.handler = handler;
            manager = handler.netManager;
            player = handler.player;
            world = player.getServerWorld();
            intent = new GIntentSession(new GIntentProtocol.Key(UUID.randomUUID(), player.getUniqueID(), player.dimension));
            MinecraftServer scheduler = player.getServer();
            dispatcher = new RemoteBoostBoundedDispatcher<>(8, task -> scheduler.addScheduledTask(task));
        }
    }
}
