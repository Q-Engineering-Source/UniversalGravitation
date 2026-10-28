package wi.unigrav.core.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import dev.crlhitbox.api.geometry.Vec3d;

import neofontrender.addons.api.camera.CameraEntityPickGeometry;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetHandler;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerRenderPoseNetwork;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;

/** Client-only, render-cache mirror for server-authorized remote player presentation. */
public final class PlayerRenderPoseMirror {
    public static final PlayerRenderPoseMirror INSTANCE = new PlayerRenderPoseMirror();

    private static final int MAX_PENDING_INBOUND = 256;
    private static final int HELLO_RETRY_TICKS = 40;

    private final ClientPhysicsTickQueue tickQueue = new ClientPhysicsTickQueue();
    private final RemoteBoostBoundedDispatcher<Inbound> inbound = new RemoteBoostBoundedDispatcher<>(
            MAX_PENDING_INBOUND, tickQueue::schedule);
    private boolean installed;
    private NetHandlerPlayClient connection;
    private WorldClient world;
    private EntityPlayerSP localPlayer;
    private UUID localPlayerId;
    private int localEntityId = -1;
    private int dimension;
    private UUID connectionToken;
    private long worldRequestGeneration;
    private long lastHelloTick = Long.MIN_VALUE;
    private long clientTick;
    private boolean awaitingContext;
    private boolean awaitingWelcome = true;
    private final WelcomeGate welcomeGate = new WelcomeGate();
    private boolean transitionPending;
    private World unloadedWorld;
    private boolean overflowReported;
    private final FreePoseReportState freeReports = new FreePoseReportState();
    private PoseCache cache;

    private record Inbound(PlayerRenderPoseProtocol.Packet packet, NetHandlerPlayClient handler) { }

    private PlayerRenderPoseMirror() { }

    public synchronized void install() {
        if (installed) return;
        MinecraftForge.EVENT_BUS.register(this);
        installed = true;
    }

    /** Network-thread entry. All Minecraft identity and world checks run on the client tick thread. */
    public static void receive(PlayerRenderPoseProtocol.Packet packet, INetHandler handler) {
        Objects.requireNonNull(packet, "packet");
        if (!(handler instanceof NetHandlerPlayClient client)) return;
        INSTANCE.enqueue(packet, client);
    }

    /** Reports only a current server-issued FREE lease; this method never changes local motion or camera state. */
    public static void reportFreePose(PlayerRenderPoseProtocol.Quaternion body,
                                      PlayerRenderPoseProtocol.Quaternion head) {
        INSTANCE.reportFreePoseOnClientThread(body, head);
    }

    /** Read-only remote render sample. Local players continue through their existing UIE presentation provider. */
    public static Optional<FlightBodyPose> sample(AbstractClientPlayer player, float partialTicks) {
        return INSTANCE.sampleOnRenderThread(player, partialTicks);
    }

    /** One immutable geometry snapshot per pick; known stale Cardinal entries explicitly suppress native boxes. */
    public static List<CameraEntityPickGeometry> pickGeometry(World actualWorld, Entity excluded, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        var mirror = INSTANCE;
        mirror.requireClientThread("tracked pick geometry");
        if (actualWorld != mc.world || actualWorld != mirror.world || !mirror.currentClientContext(mc)
                || mirror.cache == null || mirror.welcomeGate.activeRealm() <= 0) return List.of();
        return mirror.cache.pickGeometry(actualWorld, mirror.localPlayer, excluded, partialTicks, mirror.clientTick);
    }

    private synchronized void enqueue(PlayerRenderPoseProtocol.Packet packet, NetHandlerPlayClient handler) {
        var reservation = inbound.reserve(new Inbound(packet, handler), this::receiveOnClientThread);
        if (reservation == null) {
            if (!overflowReported) {
                overflowReported = true;
                UnigravMod.LOGGER.warn("ugpose1 client queue full; excess render packets dropped");
            }
            return;
        }
        inbound.submit(reservation);
    }

    private void receiveOnClientThread(Inbound incoming) {
        requireClientThread("render-pose packet delivery");
        try {
            receiveOnClientThreadChecked(incoming);
        } catch (RuntimeException | Error failure) {
            UnigravMod.LOGGER.error("ugpose1 client render-pose receive failed kind={}",
                    incoming.packet().kind(), failure);
            if (world != null) {
                try {
                    beginWorldTransition(world);
                    unloadedWorldSeenNull = true;
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    private void receiveOnClientThreadChecked(Inbound incoming) {
        Minecraft mc = Minecraft.getMinecraft();
        synchronizeContext(mc);
        if (!currentClientContext(mc) || incoming.handler() != connection) return;
        PlayerRenderPoseProtocol.Packet packet = incoming.packet();
        if (packet.serverbound() || !Objects.equals(packet.connectionToken(), connectionToken)) return;
        if (packet.kind() == PlayerRenderPoseProtocol.Kind.WELCOME) {
            acceptWelcome(packet);
            return;
        }
        if (!welcomeGate.acceptsScoped(packet, connection, world, localPlayer, connectionToken,
                worldRequestGeneration, dimension)) return;
        if (packet.kind() == PlayerRenderPoseProtocol.Kind.FREE_LEASE) {
            acceptFreeLease(packet);
        } else if ((packet.kind() == PlayerRenderPoseProtocol.Kind.POSE
                || packet.kind() == PlayerRenderPoseProtocol.Kind.REMOVE
                || packet.kind() == PlayerRenderPoseProtocol.Kind.PRESENTATION_RELEASE) && cache != null) {
            cache.receive(packet, world, localPlayer, clientTick);
        }
    }

    private void acceptWelcome(PlayerRenderPoseProtocol.Packet packet) {
        WelcomeGate.WelcomeResult result = welcomeGate.accept(packet, connection, world, localPlayer,
                connectionToken, worldRequestGeneration, dimension);
        if (result == WelcomeGate.WelcomeResult.REJECTED) return;
        if (result == WelcomeGate.WelcomeResult.DUPLICATE) return;
        awaitingWelcome = false;
        freeReports.clear();
        cache = new PoseCache();
        cache.bind(world, localPlayer, connectionToken, welcomeGate.activeRealm(), dimension,
                welcomeGate.worldEpoch());
    }

    private void acceptFreeLease(PlayerRenderPoseProtocol.Packet packet) {
        freeReports.acceptLease(packet, connection, world, localPlayer, connectionToken,
                worldRequestGeneration, dimension, welcomeGate);
    }

    private void reportFreePoseOnClientThread(PlayerRenderPoseProtocol.Quaternion body,
                                              PlayerRenderPoseProtocol.Quaternion head) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(head, "head");
        requireClientThread("free render-pose report");
        Minecraft mc = Minecraft.getMinecraft();
        synchronizeContext(mc);
        if (!currentClientContext(mc)) return;
        freeReports.prepare(body, head, connection, world, localPlayer, connectionToken,
                worldRequestGeneration, dimension, clientTick, welcomeGate).ifPresent(packet -> {
                    try {
                        PlayerRenderPoseNetwork.sendToServer(packet);
                    } catch (RuntimeException | Error failure) {
                        UnigravMod.LOGGER.error("ugpose1 FREE_POSE send failed realm={} lease={} sequence={}",
                                packet.viewerRealm(), packet.lifetime(), packet.sequence(), failure);
                        throw failure;
                    }
                });
    }

    private Optional<FlightBodyPose> sampleOnRenderThread(AbstractClientPlayer rendered, float partialTicks) {
        Objects.requireNonNull(rendered, "rendered");
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("partialTicks must be finite");
        requireClientThread("remote render-pose sample");
        Minecraft mc = Minecraft.getMinecraft();
        if (!(rendered.world instanceof WorldClient currentWorld) || rendered == mc.player
                || currentWorld != mc.world || !currentClientContext(mc) || welcomeGate.activeRealm() <= 0 || cache == null
                || !rendered.isEntityAlive() || rendered.isDead || PlayerKinematics.requiresNative(rendered)) {
            return Optional.empty();
        }
        Optional<SampledPresentation> sampled = cache.presentation(rendered, currentWorld, localPlayer,
                Math.max(0.0F, Math.min(1.0F, partialTicks)), clientTick);
        if (sampled.isEmpty()) return Optional.empty();
        SampledPresentation presentation = sampled.orElseThrow();
        SampledPose pose = presentation.pose();
        if (presentation.geometry() != null) {
            var offset = presentation.geometry().rootOffset();
            return Optional.of(FlightBodyPose.modelUpY(attitude(pose.body()), attitude(pose.head()),
                    new FlightVector(offset.x(), offset.y(), offset.z()))
                    .withAxialShadowReference(presentation.geometry().axisUp()));
        }
        return Optional.of(FlightBodyPose.modelUpY(attitude(pose.body()), attitude(pose.head())).withFreeShadowReference());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            clientTick = Math.incrementExact(clientTick);
            tickQueue.drain();
            Minecraft mc = Minecraft.getMinecraft();
            synchronizeContext(mc);
            if (currentClientContext(mc)) {
                sendHelloIfDue();
                if (cache != null) cache.expire(world, localPlayer, clientTick);
            }
        } else if (event.phase == TickEvent.Phase.END) {
            synchronizeContext(Minecraft.getMinecraft());
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote && event.getWorld() == world) {
            requireClientThread("client world unload");
            beginWorldTransition(world);
            unloadedWorld = world;
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Runnable retire = () -> {
            if (connection == null || connection.getNetworkManager() != event.getManager()) return;
            disconnectingManager = event.getManager();
            resetConnectionState();
        };
        if (mc.isCallingFromMinecraftThread()) retire.run();
        else mc.addScheduledTask(retire);
    }

    private net.minecraft.network.NetworkManager disconnectingManager;

    private void synchronizeContext(Minecraft mc) {
        NetHandlerPlayClient observedConnection = mc.getConnection();
        if (observedConnection == null) {
            if (connection != null) resetConnectionState();
            return;
        }
        if (disconnectingManager != null) {
            if (observedConnection.getNetworkManager() == disconnectingManager) return;
            disconnectingManager = null;
        }
        if (observedConnection != connection) startConnection(observedConnection);

        World observedWorld = mc.world;
        EntityPlayerSP observedPlayer = mc.player;
        if (!(observedWorld instanceof WorldClient clientWorld) || observedPlayer == null) {
            if (world != null && !transitionPending) beginWorldTransition(world);
            if (observedWorld == null) unloadedWorldSeenNull = true;
            return;
        }
        int observedDimension = observedPlayer.dimension;
        if (observedDimension != clientWorld.provider.getDimension()) return;
        if (transitionPending && clientWorld == unloadedWorld && !unloadedWorldSeenNull) return;

        boolean firstContext = world == null || localPlayer == null;
        boolean changed = firstContext || clientWorld != world || observedPlayer != localPlayer
                || observedDimension != dimension || !observedPlayer.getUniqueID().equals(localPlayerId)
                || observedPlayer.getEntityId() != localEntityId;
        if (!changed && !transitionPending) return;

        clearWorldPresentation();
        world = clientWorld;
        localPlayer = observedPlayer;
        localPlayerId = observedPlayer.getUniqueID();
        localEntityId = observedPlayer.getEntityId();
        dimension = observedDimension;
        worldRequestGeneration = welcomeGate.bind(connection, world, localPlayer, dimension);
        transitionPending = false;
        unloadedWorld = null;
        unloadedWorldSeenNull = false;
        awaitingContext = false;
        lastHelloTick = Long.MIN_VALUE;
        sendHelloIfDue();
    }

    private boolean unloadedWorldSeenNull;

    private void startConnection(NetHandlerPlayClient next) {
        inbound.invalidate();
        clearWorldPresentation();
        connection = next;
        world = null;
        localPlayer = null;
        localPlayerId = null;
        localEntityId = -1;
        connectionToken = UUID.randomUUID();
        worldRequestGeneration = 0;
        welcomeGate.beginConnection(next, connectionToken);
        transitionPending = false;
        unloadedWorld = null;
        unloadedWorldSeenNull = false;
        awaitingContext = true;
    }

    private void resetConnectionState() {
        inbound.invalidate();
        clearWorldPresentation();
        connection = null;
        world = null;
        localPlayer = null;
        localPlayerId = null;
        localEntityId = -1;
        connectionToken = null;
        worldRequestGeneration = 0;
        welcomeGate.reset();
        transitionPending = false;
        unloadedWorld = null;
        unloadedWorldSeenNull = false;
        awaitingContext = true;
    }

    private void beginWorldTransition(World previousWorld) {
        if (!transitionPending) {
            transitionPending = true;
            unloadedWorld = previousWorld;
            unloadedWorldSeenNull = false;
            clearWorldPresentation();
        }
        awaitingContext = true;
    }

    private void clearWorldPresentation() {
        welcomeGate.retireContext();
        freeReports.clear();
        cache = null;
        awaitingWelcome = true;
    }

    private void sendHelloIfDue() {
        if (connection == null || connectionToken == null || world == null || localPlayer == null
                || worldRequestGeneration <= 0 || awaitingContext || !awaitingWelcome) return;
        if (lastHelloTick != Long.MIN_VALUE && clientTick - lastHelloTick < HELLO_RETRY_TICKS) return;
        try {
            PlayerRenderPoseNetwork.sendToServer(PlayerRenderPoseProtocol.Packet.hello(connectionToken,
                    worldRequestGeneration));
        } catch (RuntimeException | Error failure) {
            UnigravMod.LOGGER.error("ugpose1 HELLO send failed generation={}", worldRequestGeneration, failure);
            throw failure;
        }
        lastHelloTick = clientTick;
    }

    private boolean currentClientContext(Minecraft mc) {
        return connection != null && connection == mc.getConnection() && world != null && world == mc.world
                && localPlayer != null && localPlayer == mc.player && localPlayer.world == world
                && localPlayer.dimension == dimension && world.provider.getDimension() == dimension
                && localPlayer.getEntityId() == localEntityId && localPlayer.getUniqueID().equals(localPlayerId)
                && connection.getNetworkManager().getNetHandler() == connection;
    }

    private static void requireClientThread(String operation) {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException(operation + " must run on the Minecraft client thread");
    }

    private static FlightAttitude attitude(PlayerRenderPoseProtocol.Quaternion value) {
        return new FlightAttitude(value.x(), value.y(), value.z(), value.w());
    }

    /** Connection/world-generation and welcome fence shared by the live receiver and headless tests. */
    static final class WelcomeGate {
        enum WelcomeResult { REJECTED, DUPLICATE, ACCEPTED }

        private Object handler;
        private World world;
        private EntityPlayer localPlayer;
        private UUID localPlayerId;
        private int localEntityId = -1;
        private int dimension;
        private UUID token;
        private long generation;
        private long realmHighWater;
        private long activeRealm;
        private long worldEpoch;

        void beginConnection(Object nextHandler, UUID nextToken) {
            Objects.requireNonNull(nextHandler, "handler");
            Objects.requireNonNull(nextToken, "connectionToken");
            reset();
            handler = nextHandler;
            token = nextToken;
        }

        long bind(Object actualHandler, World actualWorld, EntityPlayer actualLocalPlayer, int actualDimension) {
            Objects.requireNonNull(actualHandler, "handler");
            Objects.requireNonNull(actualWorld, "world");
            Objects.requireNonNull(actualLocalPlayer, "localPlayer");
            if (handler != actualHandler || token == null || actualLocalPlayer.world != actualWorld
                    || actualLocalPlayer.dimension != actualDimension
                    || actualWorld.provider.getDimension() != actualDimension)
                throw new IllegalArgumentException("Invalid render-pose welcome context");
            boolean sameContext = world == actualWorld && localPlayer == actualLocalPlayer
                    && localPlayerId != null && localPlayerId.equals(actualLocalPlayer.getUniqueID())
                    && localEntityId == actualLocalPlayer.getEntityId() && dimension == actualDimension;
            if (generation == 0) generation = 1;
            else if (!sameContext) generation = Math.incrementExact(generation);
            world = actualWorld;
            localPlayer = actualLocalPlayer;
            localPlayerId = actualLocalPlayer.getUniqueID();
            localEntityId = actualLocalPlayer.getEntityId();
            dimension = actualDimension;
            activeRealm = 0;
            worldEpoch = 0;
            return generation;
        }

        void retireContext() {
            world = null;
            localPlayer = null;
            localPlayerId = null;
            localEntityId = -1;
            dimension = 0;
            activeRealm = 0;
            worldEpoch = 0;
        }

        void reset() {
            handler = null;
            token = null;
            generation = 0;
            realmHighWater = 0;
            retireContext();
        }

        WelcomeResult accept(PlayerRenderPoseProtocol.Packet packet, Object actualHandler, World actualWorld,
                             EntityPlayer actualLocalPlayer, UUID actualToken, long currentGeneration,
                             int actualDimension) {
            if (packet == null || packet.kind() != PlayerRenderPoseProtocol.Kind.WELCOME
                    || !contextMatches(actualHandler, actualWorld, actualLocalPlayer, actualToken,
                    currentGeneration, actualDimension)
                    || packet.worldRequestGeneration() != generation
                    || !packet.connectionToken().equals(token)
                    || !packet.playerId().equals(localPlayerId) || packet.entityId() != localEntityId
                    || packet.dimension() != dimension) return WelcomeResult.REJECTED;
            if (packet.viewerRealm() == activeRealm && activeRealm > 0 && packet.worldEpoch() == worldEpoch)
                return WelcomeResult.DUPLICATE;
            if (packet.viewerRealm() <= realmHighWater) return WelcomeResult.REJECTED;
            realmHighWater = packet.viewerRealm();
            activeRealm = packet.viewerRealm();
            worldEpoch = packet.worldEpoch();
            return WelcomeResult.ACCEPTED;
        }

        boolean acceptsScoped(PlayerRenderPoseProtocol.Packet packet, Object actualHandler, World actualWorld,
                              EntityPlayer actualLocalPlayer, UUID actualToken, long currentGeneration,
                              int actualDimension) {
            return packet != null && contextMatches(actualHandler, actualWorld, actualLocalPlayer, actualToken,
                    currentGeneration, actualDimension) && activeRealm > 0
                    && packet.connectionToken().equals(token) && packet.viewerRealm() == activeRealm
                    && packet.worldEpoch() == worldEpoch && packet.dimension() == dimension;
        }

        boolean isCurrentContext(Object actualHandler, World actualWorld, EntityPlayer actualLocalPlayer,
                                 UUID actualToken, long currentGeneration, int actualDimension) {
            return contextMatches(actualHandler, actualWorld, actualLocalPlayer, actualToken,
                    currentGeneration, actualDimension);
        }

        long activeRealm() { return activeRealm; }
        long worldEpoch() { return worldEpoch; }
        long generation() { return generation; }
        long realmHighWater() { return realmHighWater; }

        private boolean contextMatches(Object actualHandler, World actualWorld, EntityPlayer actualLocalPlayer,
                                       UUID actualToken, long currentGeneration, int actualDimension) {
            return handler != null && handler == actualHandler && world != null && world == actualWorld
                    && localPlayer != null && localPlayer == actualLocalPlayer && token != null
                    && token.equals(actualToken) && generation == currentGeneration
                    && dimension == actualDimension && localPlayer.world == world
                    && localPlayer.getUniqueID().equals(localPlayerId)
                    && localPlayer.getEntityId() == localEntityId && localPlayer.dimension == dimension
                    && world.provider.getDimension() == dimension;
        }
    }

    /** Exact active-lease and per-client-tick rate fence for read-only FREE pose reports. */
    static final class FreePoseReportState {
        private long controlSequence;
        private long lease;
        private long poseSequence;
        private long lastReportTick = Long.MIN_VALUE;

        void clear() {
            controlSequence = 0;
            lease = 0;
            poseSequence = 0;
            lastReportTick = Long.MIN_VALUE;
        }

        boolean acceptLease(PlayerRenderPoseProtocol.Packet packet, Object handler, World world,
                            EntityPlayer localPlayer, UUID token, long generation, int dimension,
                            WelcomeGate gate) {
            if (packet == null || packet.kind() != PlayerRenderPoseProtocol.Kind.FREE_LEASE
                    || !gate.acceptsScoped(packet, handler, world, localPlayer, token, generation, dimension)
                    || localPlayer == null || !packet.playerId().equals(localPlayer.getUniqueID())
                    || packet.entityId() != localPlayer.getEntityId() || packet.sequence() <= controlSequence)
                return false;
            controlSequence = packet.sequence();
            lease = packet.lifetime();
            poseSequence = 0;
            lastReportTick = Long.MIN_VALUE;
            return true;
        }

        Optional<PlayerRenderPoseProtocol.Packet> prepare(PlayerRenderPoseProtocol.Quaternion body,
                                                           PlayerRenderPoseProtocol.Quaternion head,
                                                           Object handler, World world, EntityPlayer localPlayer,
                                                           UUID token, long generation, int dimension, long tick,
                                                           WelcomeGate gate) {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(head, "head");
            if (lease <= 0 || tick == lastReportTick || localPlayer == null
                    || !gate.isCurrentContext(handler, world, localPlayer, token, generation, dimension)
                    || gate.activeRealm() <= 0 || !localPlayer.isEntityAlive() || localPlayer.isDead
                    || PlayerKinematics.requiresNative(localPlayer)) return Optional.empty();
            poseSequence = Math.incrementExact(poseSequence);
            lastReportTick = tick;
            return Optional.of(PlayerRenderPoseProtocol.Packet.freePose(token, gate.activeRealm(), dimension,
                    gate.worldEpoch(), lease, poseSequence, body, head));
        }
    }

    record SampledPose(PlayerRenderPoseProtocol.Quaternion body,
                       PlayerRenderPoseProtocol.Quaternion head) { }
    record SampledGeometry(AxisAlignedBB bounds, Vec3d rootOffset, FlightVector axisUp) { }
    record SampledPresentation(SampledPose pose, SampledGeometry geometry) { }

    /** Shared production cache seam; the outer mirror supplies the actual current client identities. */
    static final class PoseCache {
        static final int MAX_ACTIVE = 2048;
        static final int MAX_PENDING = 256;
        static final int MAX_FENCES = 4096;
        static final long PENDING_TTL_TICKS = 20;
        static final long ACTIVE_TTL_TICKS = 40;

        private final Map<UUID, Entry> entries = new HashMap<>();
        private final LinkedHashMap<UUID, Fence> fences = new LinkedHashMap<>(64, .75F, true);
        private boolean fenceCapacityReported;
        private boolean entryCapacityReported;
        private World boundWorld;
        private EntityPlayer boundLocalPlayer;
        private UUID boundLocalId;
        private int boundLocalEntityId;
        private UUID boundToken;
        private long boundRealm;
        private int boundDimension;
        private long boundWorldEpoch;

        void bind(World world, EntityPlayer localPlayer, UUID token, long realm, int dimension, long worldEpoch) {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(localPlayer, "localPlayer");
            Objects.requireNonNull(token, "token");
            if (realm <= 0 || worldEpoch < 0 || localPlayer.world != world
                    || localPlayer.dimension != dimension || world.provider.getDimension() != dimension)
                throw new IllegalArgumentException("Invalid render-pose cache context");
            clear();
            boundWorld = world;
            boundLocalPlayer = localPlayer;
            boundLocalId = localPlayer.getUniqueID();
            boundLocalEntityId = localPlayer.getEntityId();
            boundToken = token;
            boundRealm = realm;
            boundDimension = dimension;
            boundWorldEpoch = worldEpoch;
        }

        void clear() {
            entries.clear();
            fences.clear();
            fenceCapacityReported = false;
            entryCapacityReported = false;
            boundWorld = null;
            boundLocalPlayer = null;
            boundLocalId = null;
            boundLocalEntityId = -1;
            boundToken = null;
            boundRealm = 0;
            boundDimension = 0;
            boundWorldEpoch = 0;
        }

        boolean receive(PlayerRenderPoseProtocol.Packet packet, World actualWorld,
                        EntityPlayer actualLocalPlayer, long tick) {
            Objects.requireNonNull(packet, "packet");
            if (!contextMatches(actualWorld, actualLocalPlayer) || packet.serverbound()
                    || (packet.kind() != PlayerRenderPoseProtocol.Kind.POSE
                    && packet.kind() != PlayerRenderPoseProtocol.Kind.REMOVE
                    && packet.kind() != PlayerRenderPoseProtocol.Kind.PRESENTATION_RELEASE)
                    || !boundToken.equals(packet.connectionToken()) || packet.viewerRealm() != boundRealm
                    || packet.dimension() != boundDimension || packet.worldEpoch() != boundWorldEpoch
                    || packet.playerId().equals(boundLocalId) || packet.entityId() == boundLocalEntityId) return false;

            Fence priorFence = fences.get(packet.playerId());
            Entry priorEntry = entries.get(packet.playerId());
            if (priorEntry != null && packet.lifetime() < priorEntry.lifetime) return false;
            if (priorFence == null && fences.size() >= MAX_FENCES) {
                if (!fenceCapacityReported) {
                    fenceCapacityReported = true;
                    UnigravMod.LOGGER.warn("ugpose1 client subject fence capacity reached; unknown subjects are rejected");
                }
                return false;
            }
            if (!newerThanFence(packet, priorFence)) return false;
            if (priorFence != null && priorFence.lifetime == packet.lifetime()
                    && priorFence.physicsSession != null && packet.geometry() != null
                    && (!priorFence.physicsSession.equals(packet.geometry().physicsSession())
                    || priorFence.physicsWorldEpoch != packet.geometry().physicsWorldEpoch())) return false;
            if (priorEntry != null && packet.lifetime() == priorEntry.lifetime) {
                if (packet.entityId() != priorEntry.entityId || packet.sequence() <= priorEntry.sequence
                        || packet.physicalRevision() < priorEntry.physicalRevision) return false;
                if (priorEntry.physicsSession != null && packet.geometry() != null
                        && (!priorEntry.physicsSession.equals(packet.geometry().physicsSession())
                        || priorEntry.physicsWorldEpoch != packet.geometry().physicsWorldEpoch())) return false;
                if (priorEntry.geometry != null && packet.geometry() != null
                        && packet.physicalRevision() == priorEntry.physicalRevision
                        && !priorEntry.geometry.equals(packet.geometry())) return false;
            }

            if (packet.kind() == PlayerRenderPoseProtocol.Kind.PRESENTATION_RELEASE) {
                var release = packet.release();
                Entity occupant = actualWorld.getEntityByID(packet.entityId());
                if (priorFence == null || priorFence.lifetime != packet.lifetime()
                        || priorFence.physicsSession == null
                        || !priorFence.physicsSession.equals(release.physicsSession())
                        || priorFence.physicsWorldEpoch != release.physicsWorldEpoch()
                        || priorFence.entityIdentity != null && priorFence.entityIdentity != occupant
                        || occupant != null && (!(occupant instanceof EntityPlayer player)
                        || !player.getUniqueID().equals(packet.playerId()))) return false;
                advanceFence(packet, true, occupant instanceof EntityPlayer player ? player : null);
                priorFence.cardinal = false;
                entries.remove(packet.playerId());
                return true;
            }

            if (packet.kind() == PlayerRenderPoseProtocol.Kind.REMOVE) {
                Entity occupant = actualWorld.getEntityByID(packet.entityId());
                EntityPlayer identity = occupant instanceof EntityPlayer player
                        && player.getUniqueID().equals(packet.playerId()) ? player : null;
                advanceFence(packet, true, identity);
                if (priorEntry != null && (packet.lifetime() > priorEntry.lifetime
                        || packet.lifetime() == priorEntry.lifetime && packet.sequence() > priorEntry.sequence))
                    entries.remove(packet.playerId());
                return true;
            }

            Entity occupant = actualWorld.getEntityByID(packet.entityId());
            if (occupant != null && (!(occupant instanceof EntityPlayer entityPlayer)
                    || !entityPlayer.getUniqueID().equals(packet.playerId()))) {
                entries.remove(packet.playerId());
                return false;
            }
            EntityPlayer identity = occupant instanceof EntityPlayer entityPlayer ? entityPlayer : null;
            if (priorFence != null && packet.lifetime() == priorFence.lifetime) {
                if (packet.entityId() != priorFence.entityId
                        || priorFence.entityIdentity != null && priorFence.entityIdentity != identity) {
                    entries.remove(packet.playerId());
                    return false;
                }
            }
            if (priorEntry != null && packet.lifetime() == priorEntry.lifetime
                    && priorEntry.entityIdentity != null && priorEntry.entityIdentity != identity) {
                entries.remove(packet.playerId());
                return false;
            }
            if (identity == null && priorFence != null && packet.lifetime() == priorFence.lifetime
                    && priorFence.entityIdentity != null) return false;
            if (priorEntry == null && !hasCapacity(occupant == null)) {
                if (!entryCapacityReported) {
                    entryCapacityReported = true;
                    UnigravMod.LOGGER.warn("ugpose1 client presentation capacity reached; new subjects are rejected");
                }
                return false;
            }
            advanceFence(packet, false, identity);
            if (occupant == null) {
                if (samePoseContext(priorEntry, packet)
                        && sameRotation(priorEntry.body, packet.body())
                        && sameRotation(priorEntry.head, packet.head())) {
                    priorEntry.refresh(packet, tick);
                    return true;
                }
                long since = priorEntry != null && priorEntry.lifetime == packet.lifetime()
                        && priorEntry.pendingSinceTick != Long.MIN_VALUE ? priorEntry.pendingSinceTick : tick;
                entries.put(packet.playerId(), Entry.pending(packet, since, tick));
                return true;
            }
            EntityPlayer entityPlayer = (EntityPlayer) occupant;
            if (!eligibleRemote(entityPlayer, actualWorld, actualLocalPlayer)) {
                entries.remove(packet.playerId());
                return true;
            }
            if (samePoseContext(priorEntry, packet)
                    && sameRotation(priorEntry.body, packet.body())
                    && sameRotation(priorEntry.head, packet.head())) {
                priorEntry.refresh(packet, tick);
                return true;
            }
            entries.put(packet.playerId(), Entry.from(packet, entityPlayer, priorEntry, tick));
            return true;
        }

        private static boolean samePoseContext(Entry entry, PlayerRenderPoseProtocol.Packet packet) {
            return entry != null && entry.lifetime == packet.lifetime() && entry.entityId == packet.entityId()
                    && entry.dimension == packet.dimension() && entry.worldEpoch == packet.worldEpoch()
                    && entry.mode == packet.mode() && entry.cardinalFrame == packet.cardinalFrame();
        }

        Optional<SampledGeometry> geometry(EntityPlayer entity, World actualWorld, EntityPlayer localPlayer,
                                           float partialTicks, long tick) {
            return presentation(entity, actualWorld, localPlayer, partialTicks, tick)
                    .map(SampledPresentation::geometry);
        }

        Optional<SampledPresentation> presentation(EntityPlayer entity, World actualWorld, EntityPlayer localPlayer,
                                                   float partialTicks, long tick) {
            var pose = sample(entity, actualWorld, localPlayer, partialTicks, tick);
            if (pose.isEmpty()) return Optional.empty();
            Entry entry = entries.get(entity.getUniqueID());
            // A known Cardinal pose without its accepted anchor cannot claim a valid body root.
            // Returning no render override delegates native rendering; pick remains explicitly suppressed.
            if (entry.mode == PlayerRenderPoseProtocol.Mode.CARDINAL && entry.geometry == null) return Optional.empty();
            SampledGeometry geometry = entry.mode != PlayerRenderPoseProtocol.Mode.CARDINAL || entry.geometry == null
                    ? null : sampleGeometry(entry.geometry, entry.cardinalFrame,
                    entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ,
                    entity.posX, entity.posY, entity.posZ, partialTicks);
            return Optional.of(new SampledPresentation(pose.orElseThrow(), geometry));
        }

        static SampledGeometry sampleGeometry(PlayerRenderPoseProtocol.CardinalGeometry geometry, int frame,
                double previousX, double previousY, double previousZ, double x, double y, double z, float partialTicks) {
            if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("Non-finite pick interpolation");
            double alpha = Math.max(0, Math.min(1, partialTicks));
            var transport = geometry.transport();
            double dx = previousX + (x - previousX) * alpha - transport.x();
            double dy = previousY + (y - previousY) * alpha - transport.y();
            double dz = previousZ + (z - previousZ) * alpha - transport.z();
            var min = geometry.bounds().min();
            var max = geometry.bounds().max();
            return new SampledGeometry(new AxisAlignedBB(min.x() + dx, min.y() + dy, min.z() + dz,
                    max.x() + dx, max.y() + dy, max.z() + dz), geometry.rootOffset(frame),
                    new FlightVector(CardinalFrame.values()[frame].up().x(), CardinalFrame.values()[frame].up().y(),
                            CardinalFrame.values()[frame].up().z()));
        }

        List<CameraEntityPickGeometry> pickGeometry(World actualWorld, EntityPlayer localPlayer,
                Entity excluded, float partialTicks, long tick) {
            if (!contextMatches(actualWorld, localPlayer)) return List.of();
            var result = new ArrayList<CameraEntityPickGeometry>();
            // Pending admission below refreshes the access-order fence map. Iterate its bounded snapshot.
            for (var item : new ArrayList<>(fences.entrySet())) {
                Fence fence = item.getValue();
                EntityPlayer entity = fence.entityIdentity;
                if (entity == null && actualWorld.getEntityByID(fence.entityId) instanceof EntityPlayer arrived
                        && arrived.getUniqueID().equals(item.getKey())) entity = arrived;
                if (!fence.cardinal || entity == null || entity == excluded
                        || entity == localPlayer || entity.world != actualWorld || !entity.isEntityAlive() || entity.isDead
                        || actualWorld.getEntityByID(fence.entityId) != entity) continue;
                var geometry = geometry(entity, actualWorld, localPlayer, partialTicks, tick);
                result.add(geometry.isPresent() ? new CameraEntityPickGeometry(entity, geometry.orElseThrow().bounds())
                        : CameraEntityPickGeometry.suppressed(entity));
            }
            return List.copyOf(result);
        }

        Optional<SampledPose> sample(EntityPlayer rendered, World actualWorld, EntityPlayer actualLocalPlayer,
                                     float partialTicks, long tick) {
            if (rendered == null || !contextMatches(actualWorld, actualLocalPlayer)
                    || !eligibleRemote(rendered, actualWorld, actualLocalPlayer)) return Optional.empty();
            Entry entry = entries.get(rendered.getUniqueID());
            if (entry == null || entry.entityId != rendered.getEntityId() || entry.dimension != boundDimension
                    || entry.worldEpoch != boundWorldEpoch || entry.lifetime <= 0
                    || tick - entry.lastSeenTick > ACTIVE_TTL_TICKS
                    || actualWorld.getEntityByID(entry.entityId) != rendered) return Optional.empty();
            if (entry.entityIdentity == null) {
                if (entry.pendingSinceTick == Long.MIN_VALUE || tick - entry.pendingSinceTick > PENDING_TTL_TICKS)
                    return Optional.empty();
                Fence fence = fences.get(entry.playerId);
                if (fence == null || fence.lifetime != entry.lifetime || fence.entityId != entry.entityId
                        || fence.entityIdentity != null && fence.entityIdentity != rendered) return Optional.empty();
                fence.entityIdentity = rendered;
                entry.entityIdentity = rendered;
                entry.pendingSinceTick = Long.MIN_VALUE;
            } else if (entry.entityIdentity != rendered) {
                return Optional.empty();
            }
            return Optional.of(entry.sample(Math.max(0.0F, Math.min(1.0F, partialTicks)), tick));
        }

        void expire(World actualWorld, EntityPlayer actualLocalPlayer, long tick) {
            if (!contextMatches(actualWorld, actualLocalPlayer)) return;
            Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Entry> item = iterator.next();
                Entry entry = item.getValue();
                Entity occupant = actualWorld.getEntityByID(entry.entityId);
                boolean identityChanged = entry.entityIdentity != null && occupant != entry.entityIdentity
                        || occupant != null && (!(occupant instanceof EntityPlayer player)
                        || !player.getUniqueID().equals(item.getKey()));
                boolean expired = tick - entry.lastSeenTick > ACTIVE_TTL_TICKS
                        || entry.pendingSinceTick != Long.MIN_VALUE
                        && tick - entry.pendingSinceTick > PENDING_TTL_TICKS;
                if (identityChanged || expired) iterator.remove();
            }
        }

        private boolean contextMatches(World actualWorld, EntityPlayer actualLocalPlayer) {
            return boundWorld != null && actualWorld == boundWorld && actualLocalPlayer == boundLocalPlayer
                    && actualLocalPlayer != null && actualLocalPlayer.world == boundWorld
                    && actualLocalPlayer.getUniqueID().equals(boundLocalId)
                    && actualLocalPlayer.getEntityId() == boundLocalEntityId
                    && actualLocalPlayer.dimension == boundDimension
                    && boundWorld.provider.getDimension() == boundDimension;
        }

        private boolean hasCapacity(boolean pending) {
            if (entries.size() >= MAX_ACTIVE) return false;
            if (!pending) return true;
            int pendingCount = 0;
            for (Entry entry : entries.values()) {
                if (entry.pendingSinceTick != Long.MIN_VALUE) pendingCount++;
            }
            return pendingCount < MAX_PENDING;
        }

        private static boolean eligibleRemote(EntityPlayer player, World actualWorld, EntityPlayer localPlayer) {
            return player != localPlayer && player.world == actualWorld && player.isEntityAlive() && !player.isDead
                    && !PlayerKinematics.requiresNative(player);
        }

        private boolean newerThanFence(PlayerRenderPoseProtocol.Packet packet, Fence fence) {
            if (fence == null) return true;
            if (packet.lifetime() < fence.lifetime) return false;
            if (packet.lifetime() > fence.lifetime) return true;
            if (packet.entityId() != fence.entityId) return false;
            if (packet.kind() == PlayerRenderPoseProtocol.Kind.POSE && fence.removed) return false;
            return packet.sequence() > fence.sequence && packet.physicalRevision() >= fence.physicalRevision;
        }

        private void advanceFence(PlayerRenderPoseProtocol.Packet packet, boolean removed, EntityPlayer identity) {
            Fence prior = fences.get(packet.playerId());
            if (prior == null || packet.lifetime() > prior.lifetime) {
                Fence created = new Fence(packet.lifetime(), packet.sequence(),
                        packet.physicalRevision(), packet.entityId(), removed, identity,
                        packet.mode() == PlayerRenderPoseProtocol.Mode.CARDINAL);
                if (packet.geometry() != null) {
                    created.physicsSession = packet.geometry().physicsSession();
                    created.physicsWorldEpoch = packet.geometry().physicsWorldEpoch();
                }
                fences.put(packet.playerId(), created);
            } else {
                prior.sequence = packet.sequence();
                prior.physicalRevision = packet.physicalRevision();
                prior.entityId = packet.entityId();
                prior.removed = removed;
                if (!removed) prior.cardinal = packet.mode() == PlayerRenderPoseProtocol.Mode.CARDINAL;
                if (packet.geometry() != null) {
                    prior.physicsSession = packet.geometry().physicsSession();
                    prior.physicsWorldEpoch = packet.geometry().physicsWorldEpoch();
                }
                if (prior.entityIdentity == null) prior.entityIdentity = identity;
                fences.get(packet.playerId()); // refresh access order
            }
        }

        private static final class Fence {
            private final long lifetime;
            private long sequence;
            private long physicalRevision;
            private int entityId;
            private boolean removed;
            private EntityPlayer entityIdentity;
            private boolean cardinal;
            private UUID physicsSession;
            private long physicsWorldEpoch;
            private Fence(long lifetime, long sequence, long physicalRevision, int entityId, boolean removed,
                          EntityPlayer entityIdentity, boolean cardinal) {
                this.lifetime = lifetime;
                this.sequence = sequence;
                this.physicalRevision = physicalRevision;
                this.entityId = entityId;
                this.removed = removed;
                this.entityIdentity = entityIdentity;
                this.cardinal = cardinal;
            }
        }

        private static final class Entry {
            private final UUID playerId;
            private final long lifetime;
            private final int entityId;
            private final int dimension;
            private final long worldEpoch;
            private long sequence;
            private long physicalRevision;
            private PlayerRenderPoseProtocol.Mode mode;
            private int cardinalFrame;
            private PlayerRenderPoseProtocol.Quaternion body;
            private PlayerRenderPoseProtocol.Quaternion head;
            private PlayerRenderPoseProtocol.Quaternion previousBody;
            private PlayerRenderPoseProtocol.Quaternion previousHead;
            private PlayerRenderPoseProtocol.CardinalGeometry geometry;
            private UUID physicsSession;
            private long physicsWorldEpoch;
            private long transitionTick;
            private long lastSeenTick;
            private long pendingSinceTick;
            private EntityPlayer entityIdentity;

            private Entry(PlayerRenderPoseProtocol.Packet packet, long pendingSinceTick, long tick,
                          EntityPlayer entityIdentity) {
                playerId = packet.playerId();
                lifetime = packet.lifetime();
                entityId = packet.entityId();
                dimension = packet.dimension();
                worldEpoch = packet.worldEpoch();
                sequence = packet.sequence();
                physicalRevision = packet.physicalRevision();
                mode = packet.mode();
                cardinalFrame = packet.cardinalFrame();
                body = packet.body();
                head = packet.head();
                geometry = packet.geometry();
                if (geometry != null) {
                    physicsSession = geometry.physicsSession();
                    physicsWorldEpoch = geometry.physicsWorldEpoch();
                }
                transitionTick = tick;
                lastSeenTick = tick;
                this.pendingSinceTick = pendingSinceTick;
                this.entityIdentity = entityIdentity;
            }

            private static Entry pending(PlayerRenderPoseProtocol.Packet packet, long since, long tick) {
                return new Entry(packet, since, tick, null);
            }

            private static Entry from(PlayerRenderPoseProtocol.Packet packet, EntityPlayer entity,
                                      Entry previous, long tick) {
                Entry entry = new Entry(packet, Long.MIN_VALUE, tick, entity);
                if (previous != null && previous.lifetime == packet.lifetime() && entry.physicsSession == null) {
                    entry.physicsSession = previous.physicsSession;
                    entry.physicsWorldEpoch = previous.physicsWorldEpoch;
                }
                if (previous != null && previous.lifetime == packet.lifetime()
                        && previous.entityId == packet.entityId() && previous.entityIdentity == entity
                        && previous.mode == packet.mode() && previous.cardinalFrame == packet.cardinalFrame()
                        && (!sameRotation(previous.body, packet.body()) || !sameRotation(previous.head, packet.head()))) {
                    entry.previousBody = previous.body;
                    entry.previousHead = previous.head;
                }
                return entry;
            }

            private SampledPose sample(float partialTicks, long tick) {
                if (previousBody == null || previousHead == null) return new SampledPose(body, head);
                double alpha = Math.max(0.0, Math.min(1.0, tick - transitionTick + partialTicks));
                if (alpha >= 1.0) return new SampledPose(body, head);
                return new SampledPose(slerp(previousBody, body, alpha), slerp(previousHead, head, alpha));
            }

            private void refresh(PlayerRenderPoseProtocol.Packet packet, long tick) {
                sequence = packet.sequence();
                physicalRevision = packet.physicalRevision();
                geometry = packet.geometry();
                if (geometry != null) {
                    physicsSession = geometry.physicsSession();
                    physicsWorldEpoch = geometry.physicsWorldEpoch();
                }
                lastSeenTick = tick;
            }
        }

        private static boolean sameRotation(PlayerRenderPoseProtocol.Quaternion first,
                                            PlayerRenderPoseProtocol.Quaternion second) {
            return Math.abs(first.dot(second)) >= 1.0 - 1.0e-12;
        }

        private static PlayerRenderPoseProtocol.Quaternion slerp(PlayerRenderPoseProtocol.Quaternion first,
                                                                  PlayerRenderPoseProtocol.Quaternion second,
                                                                  double alpha) {
            double dot = first.dot(second);
            double bx = second.x(), by = second.y(), bz = second.z(), bw = second.w();
            if (dot < 0.0) {
                dot = -dot;
                bx = -bx; by = -by; bz = -bz; bw = -bw;
            }
            dot = Math.min(1.0, Math.max(-1.0, dot));
            double left;
            double right;
            if (dot > .9995) {
                left = 1.0 - alpha;
                right = alpha;
            } else {
                double angle = Math.acos(dot);
                double inverseSin = 1.0 / Math.sin(angle);
                left = Math.sin((1.0 - alpha) * angle) * inverseSin;
                right = Math.sin(alpha * angle) * inverseSin;
            }
            double x = first.x() * left + bx * right;
            double y = first.y() * left + by * right;
            double z = first.z() * left + bz * right;
            double w = first.w() * left + bw * right;
            double norm = Math.sqrt(x * x + y * y + z * z + w * w);
            return new PlayerRenderPoseProtocol.Quaternion(x / norm, y / norm, z / norm, w / norm);
        }
    }
}
