package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.boost.RemoteBoostServer;

/** Server-authorized, read-only poses sent only to real entity-tracker observers. */
public final class PlayerRenderPoseTracker {
    public static final PlayerRenderPoseTracker INSTANCE = new PlayerRenderPoseTracker(
            PlayerPhysicsServer.INSTANCE, GIntentServer.INSTANCE, RemoteBoostServer.INSTANCE,
            PlayerRenderPoseNetwork::sendTo);

    private static final long FREE_REPORT_TTL_TICKS = 3;
    private static final long FREE_REPORT_INTERVAL_TICKS = 1;
    private static final long POSE_HEARTBEAT_TICKS = 10;
    private static final int MAX_PENDING_REPORTS = 8;

    private final PlayerPhysicsServer physics;
    private final GIntentServer intent;
    private final RemoteBoostServer remote;
    private final BiConsumer<EntityPlayerMP, PlayerRenderPoseProtocol.Packet> outbound;
    private final Map<NetworkManager, Link> links = new IdentityHashMap<>();
    private final Map<NetworkManager, Map<UUID, Pair>> pairs = new IdentityHashMap<>();
    private final Map<WorldServer, Long> worldEpochs = new IdentityHashMap<>();
    private final ConcurrentLinkedQueue<Connection> nettyRetirements = new ConcurrentLinkedQueue<>();
    private long nextWorldEpoch;
    private long nextViewerRealm;
    private long nextFreeLease;
    private long nextPresentationLifetime;
    private boolean installed;

    public PlayerRenderPoseTracker(PlayerPhysicsServer physics, GIntentServer intent,
                                   RemoteBoostServer remote,
                                   BiConsumer<EntityPlayerMP, PlayerRenderPoseProtocol.Packet> outbound) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.intent = Objects.requireNonNull(intent, "intent");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    /** Netty ingress only reserves bounded work; world, G and physics are read on the real server thread. */
    public void receive(PlayerRenderPoseProtocol.Packet packet, NetHandlerPlayServer handler) {
        if (packet == null || handler == null || !packet.serverbound()) return;
        NetworkManager manager = handler.netManager;
        Link link;
        synchronized (this) {
            link = links.get(manager);
            if (link == null || !link.connected || !link.registered || link.retired
                    || manager.getNetHandler() != handler || !manager.isChannelOpen()) return;
        }
        if (handler.player != null && handler.player.getServerWorld().isCallingFromMinecraftThread()) {
            dispatch(link, handler, packet);
            return;
        }
        RemoteBoostBoundedDispatcher<PlayerRenderPoseProtocol.Packet> dispatcher = link.dispatcher(handler);
        var reservation = dispatcher.reserve(packet, value -> dispatch(link, handler, value));
        if (reservation == null) {
            if (!link.overflowReported) {
                link.overflowReported = true;
                UnigravMod.LOGGER.warn("ugpose1 render queue full; excess reports dropped without changing presentation");
            }
            return;
        }
        dispatcher.submit(reservation);
    }

    private void dispatch(Link link, NetHandlerPlayServer handler, PlayerRenderPoseProtocol.Packet packet) {
        if (!(handler.player != null && handler.player.getServerWorld().isCallingFromMinecraftThread()))
            throw new IllegalStateException("Render pose dispatch off server thread");
        if (link.retired || !link.connected || !link.registered || !link.manager.isChannelOpen()
                || link.manager.getNetHandler() != handler || !actorCurrent(handler, link.manager)) return;
        Connection connection = resolve(link, handler);
        if (connection == null || !current(connection)) return;
        if (packet.kind() == PlayerRenderPoseProtocol.Kind.HELLO) {
            try {
                acceptHello(connection, packet);
            } catch (RuntimeException failure) {
                failSubject(connection, "HELLO/world-request handling", failure);
            }
            return;
        }
        try {
            acceptFreePose(connection, packet);
        } catch (RuntimeException failure) {
            failSubject(connection, "FREE_POSE handling", failure);
        }
    }

    private void acceptHello(Connection connection, PlayerRenderPoseProtocol.Packet packet) {
        Link link = connection.link;
        UUID incoming = packet.connectionToken();
        if (!current(connection)) return;
        if (link.clientToken == null) link.clientToken = incoming;
        else if (!link.clientToken.equals(incoming)) return;
        long request = packet.worldRequestGeneration();
        if (request < link.highestRequestGeneration) return;
        if (request == link.highestRequestGeneration) {
            if (link.current != connection || !connection.hello || connection.worldRequestGeneration != request) return;
            sendWelcome(connection);
            if (!refreshFreeLease(connection)) return;
            seedTracked(connection);
            return;
        }
        retireViewerContext(connection);
        connection.clientToken = link.clientToken;
        connection.hello = true;
        connection.worldRequestGeneration = request;
        connection.viewerRealm = nextViewerRealm = Math.incrementExact(nextViewerRealm);
        link.highestRequestGeneration = request;
        sendWelcome(connection);
        if (!refreshFreeLease(connection)) return;
        seedTracked(connection);
    }

    private void retireViewerContext(Connection connection) {
        revokeFreeLease(connection, false);
        pairs.remove(connection.link.manager);
    }

    private void acceptFreePose(Connection connection, PlayerRenderPoseProtocol.Packet packet) {
        FreeLease lease = connection.lease;
        if (!connection.hello || !connection.clientToken.equals(packet.connectionToken())
                || packet.viewerRealm() != connection.viewerRealm || lease == null
                || packet.lifetime() != lease.id || packet.worldEpoch() != lease.worldEpoch
                || packet.dimension() != lease.dimension || !current(connection)) return;
        Optional<PermitSignature> currentPermit;
        try {
            currentPermit = permitted(connection);
        } catch (RuntimeException failure) {
            failSubject(connection, "free-pose report authorization", failure);
            return;
        }
        connection.lastPresentationFailure = null;
        if (currentPermit.isEmpty() || !lease.signature.equals(currentPermit.orElseThrow())) {
            refreshFreeLease(connection);
            return;
        }
        long tick = connection.world.getTotalWorldTime();
        if (packet.sequence() <= lease.lastReportSequence
                || lease.lastReportTick != Long.MIN_VALUE
                && tick - lease.lastReportTick < FREE_REPORT_INTERVAL_TICKS) return;
        // The protocol constructor has already rejected non-finite and non-unit quaternions.
        lease.lastReportSequence = packet.sequence();
        lease.lastReportTick = tick;
        lease.sample = new FreeSample(packet.body(), packet.head(), packet.sequence(), tick, lease.id);
    }

    /** Reconciles actual tracker membership and leases for a server world; also used by the real world-tick hook. */
    public void reconcileWorld(WorldServer world) {
        Objects.requireNonNull(world, "world");
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("Render tracking off server thread");
        drainNettyRetirements();
        long tick = world.getTotalWorldTime();
        var actors = new ArrayList<EntityPlayerMP>();
        for (EntityPlayer player : world.playerEntities) {
            if (player instanceof EntityPlayerMP serverPlayer) actors.add(serverPlayer);
        }
        Set<NetworkManager> seenManagers = new HashSet<>();
        for (EntityPlayerMP subject : actors) {
            NetHandlerPlayServer handler = subject.connection;
            if (handler == null) continue;
            Link link = link(handler.netManager);
            if (link == null || !link.connected || !link.registered || link.retired
                    || handler.netManager.getNetHandler() != handler || !actorCurrent(handler, handler.netManager)) continue;
            seenManagers.add(handler.netManager);
            Connection connection = resolve(link, handler);
            if (connection == null || !current(connection)) continue;
            if (connection.hello && connection.clientToken != null) {
                try {
                    if (!refreshFreeLease(connection)) reconcileSubject(connection, null, tick);
                    else reconcileSubject(connection, poseState(connection, tick), tick);
                } catch (RuntimeException failure) {
                    failSubject(connection, "presentation reconciliation", failure);
                }
            }
        }
        for (Link link : snapshotLinks()) {
            Connection connection = link.current;
            if (connection != null && connection.world == world && !seenManagers.contains(link.manager)) {
                retireConnection(connection, true);
                link.current = null;
            }
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer world) reconcileWorld(world);
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP viewer && event.getTarget() instanceof EntityPlayerMP subject) {
            Link viewerLink = link(viewer.connection == null ? null : viewer.connection.netManager);
            if (viewerLink == null || viewer.connection == null) return;
            Connection viewerConnection = resolve(viewerLink, viewer.connection);
            if (viewerConnection == null || !viewerConnection.hello) return;
            Link ownerLink = link(subject.connection == null ? null : subject.connection.netManager);
            if (ownerLink == null || subject.connection == null) return;
            Connection owner = resolve(ownerLink, subject.connection);
            if (owner == null) return;
            try {
                if (!refreshFreeLease(owner)) return;
                reconcilePair(viewerLink, subject, poseState(owner, subject.world.getTotalWorldTime()),
                        subject.world.getTotalWorldTime());
            } catch (RuntimeException failure) {
                failSubject(owner, "tracking-start presentation", failure);
            }
        }
    }

    @SubscribeEvent
    public void onStopTracking(PlayerEvent.StopTracking event) {
        if (event.getEntityPlayer() instanceof EntityPlayerMP viewer && event.getTarget() instanceof EntityPlayerMP subject) {
            Link link = link(viewer.connection == null ? null : viewer.connection.netManager);
            if (link == null || link.current == null || !link.current.hello) return;
            if (!isTracking(subject, viewer)) removePair(link, subject.getUniqueID(), subject, true);
        }
    }

    @SubscribeEvent public void onLogin(PlayerLoggedInEvent event) { retirePlayerId(event.player.getUniqueID()); }
    @SubscribeEvent public void onRespawn(PlayerRespawnEvent event) { retirePlayerId(event.player.getUniqueID()); }
    @SubscribeEvent public void onDimensionChange(PlayerChangedDimensionEvent event) { retirePlayerId(event.player.getUniqueID()); }
    @SubscribeEvent public void onLogout(PlayerLoggedOutEvent event) { retirePlayerId(event.player.getUniqueID()); }

    @SubscribeEvent
    public void onConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        synchronized (this) {
            Link link = links.computeIfAbsent(event.getManager(), Link::new);
            link.connected = true;
            link.retired = false;
        }
    }

    @SubscribeEvent
    public void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER || !event.getRegistrations().contains(PlayerRenderPoseProtocol.CHANNEL)) return;
        synchronized (this) {
            Link link = links.computeIfAbsent(event.getManager(), Link::new);
            if ("REGISTER".equals(event.getOperation())) {
                link.registered = true;
                link.retired = false;
            } else if ("UNREGISTER".equals(event.getOperation())) {
                link.registered = false;
                link.retired = true;
                if (link.dispatcher != null) link.dispatcher.invalidate();
            }
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        Link link;
        synchronized (this) {
            link = links.remove(event.getManager());
            if (link != null) {
                link.retired = true;
                link.connected = false;
                if (link.dispatcher != null) link.dispatcher.invalidate();
            }
        }
        if (link != null && link.current != null) nettyRetirements.add(link.current);
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!(event.getWorld() instanceof WorldServer world)) return;
        for (Link link : snapshotLinks()) {
            if (link.current != null && link.current.world == world) {
                retireConnection(link.current, true);
                link.current = null;
            }
        }
        worldEpochs.remove(world);
    }

    public void stop() {
        drainNettyRetirements();
        for (Link link : snapshotLinks()) {
            link.retired = true;
            if (link.dispatcher != null) link.dispatcher.invalidate();
            if (link.current != null) retireConnection(link.current, false);
        }
        synchronized (this) {
            links.clear();
            pairs.clear();
            worldEpochs.clear();
            nettyRetirements.clear();
            installed = false;
        }
    }

    private void drainNettyRetirements() {
        for (Connection connection; (connection = nettyRetirements.poll()) != null; ) {
            if (connection.link.current != connection) continue;
            retireConnection(connection, true);
            connection.link.current = null;
        }
    }

    private boolean refreshFreeLease(Connection connection) {
        if (!connection.hello || connection.clientToken == null || !current(connection)) return true;
        Optional<PermitSignature> signature;
        try {
            signature = permitted(connection);
        } catch (RuntimeException failure) {
            failSubject(connection, "free-pose permission evaluation", failure);
            return false;
        }
        connection.lastPresentationFailure = null;
        if (signature.isEmpty()) {
            revokeFreeLease(connection);
            return true;
        }
        if (connection.lease != null && connection.lease.signature.equals(signature.orElseThrow())) return true;
        revokeFreeLease(connection);
        long leaseId = nextFreeLease = Math.incrementExact(nextFreeLease);
        long worldEpoch = epoch(connection.world);
        connection.lease = new FreeLease(leaseId, worldEpoch, connection.world.provider.getDimension(),
                signature.orElseThrow());
        connection.leaseControlSequence = Math.incrementExact(connection.leaseControlSequence);
        outbound.accept(connection.player, PlayerRenderPoseProtocol.Packet.freeLease(connection.clientToken,
                connection.viewerRealm, connection.player.getUniqueID(), connection.player.getEntityId(),
                connection.world.provider.getDimension(), worldEpoch, leaseId, connection.leaseControlSequence));
        return true;
    }

    private void revokeFreeLease(Connection connection) {
        revokeFreeLease(connection, true);
    }

    private void revokeFreeLease(Connection connection, boolean send) {
        if (connection.lease == null) return;
        connection.lease = null;
        connection.leaseControlSequence = Math.incrementExact(connection.leaseControlSequence);
        if (send && connection.hello && current(connection)) {
            outbound.accept(connection.player, PlayerRenderPoseProtocol.Packet.freeLease(connection.clientToken,
                    connection.viewerRealm, connection.player.getUniqueID(), connection.player.getEntityId(),
                    connection.world.provider.getDimension(), epoch(connection.world), 0,
                    connection.leaseControlSequence));
        }
    }

    private Optional<PermitSignature> permitted(Connection connection) {
        EntityPlayerMP player = connection.player;
        if (!UnigravConfig.sixDofCamera || !current(connection) || !player.isEntityAlive()
                || player.isPlayerSleeping() || player.isSpectator() || PlayerKinematics.requiresNative(player)) {
            return Optional.empty();
        }
        AcceptedPresentation acceptedPresentation = acceptedPresentation(connection).orElse(null);
        if (acceptedPresentation != null) {
            PlayerPhysicalSnapshot.Scope scope = acceptedPresentation.snapshot().scope();
            if (!current(connection) || !presentationScopeCurrent(connection, acceptedPresentation.snapshot()))
                return Optional.empty();
            return Optional.of(new PermitSignature(player.getUniqueID(), connection.world,
                    connection.world.provider.getDimension(), epoch(connection.world), false, false, false,
                    null, null, 0, 0, false, scope.sessionId(), scope.worldEpoch(), acceptedPresentation.kind()));
        }

        Optional<GIntentObservation> observed = intent.observe(player);
        boolean personalZeroG = observed.filter(GIntentObservation::active).isPresent();
        boolean integrated = !player.getServer().isDedicatedServer();
        GravityEntityBinder binder = UnigravMod.entityBinder;
        boolean bound = binder != null && binder.isBound(player);
        boolean custody = physics.owns(player) || remote.hasMovementCustody(connection.handler);
        if (integrated) {
            if (!bound) return Optional.empty();
            if (!personalZeroG) {
                if (player.isCreative() || player.capabilities.isFlying) return Optional.empty();
                double threshold = UnigravConfig.sixDofCameraGravityThreshold;
                if (!Double.isFinite(threshold) || threshold < 0) return Optional.empty();
                Vec3 acceleration = EntityGravitySources.fieldAcceleration(player,
                        new Vec3(player.posX, player.posY, player.posZ),
                        connection.world.getTotalWorldTime() * GravityEntityBinder.TICK_SECONDS);
                double normSquared = acceleration.normSquared();
                if (!Double.isFinite(normSquared) || Math.sqrt(normSquared) > threshold) return Optional.empty();
                if (!current(connection) || UnigravMod.entityBinder != binder
                        || binder == null || !binder.isBound(player)
                        || !Objects.equals(observed.orElse(null), intent.observe(player).orElse(null))) {
                    return Optional.empty();
                }
            }
        } else if (!custody || !personalZeroG) return Optional.empty();

        Optional<PlayerPhysicalSnapshot> physical = physics.accepted(connection.handler);
        UUID physicalScope = physical.map(snapshot -> snapshot.scope().sessionId()).orElse(null);
        long physicalEpoch = physical.map(snapshot -> snapshot.scope().worldEpoch()).orElse(0L);
        GIntentObservation source = observed.orElse(null);
        PermitSignature signature = new PermitSignature(player.getUniqueID(), connection.world,
                connection.world.provider.getDimension(), epoch(connection.world), integrated, bound, custody,
                source == null ? null : source.key().sessionId(),
                source == null ? null : source.source(), source == null ? 0 : source.sourceEpoch(),
                source == null ? 0 : source.revision(), source != null && source.active(), physicalScope, physicalEpoch,
                PresentationKind.LEGACY);
        return Optional.of(signature);
    }

    /** Exact render admission from the physics owner; neither accepted() nor G custody alone grants this lease. */
    private Optional<AcceptedPresentation> acceptedPresentation(Connection connection) {
        if (!current(connection)) return Optional.empty();
        PlayerPhysicsServer.PresentationBodyReference cardinal =
                physics.presentationBodyReference(connection.handler).orElse(null);
        if (cardinal != null) {
            PlayerPhysicalSnapshot snapshot = cardinal.snapshot();
            if (current(connection) && presentationScopeCurrent(connection, snapshot))
                return Optional.of(new AcceptedPresentation(PresentationKind.CARDINAL_HEAD, snapshot,
                        cardinal.acceptedLock()));
        }
        PlayerPhysicsServer.FreePresentationSnapshot free =
                physics.presentationFreeSnapshot(connection.handler).orElse(null);
        if (free != null) {
            PlayerPhysicalSnapshot snapshot = free.snapshot();
            if (current(connection) && presentationScopeCurrent(connection, snapshot))
                return Optional.of(new AcceptedPresentation(PresentationKind.FREE_OWNER, snapshot, null));
        }
        return Optional.empty();
    }

    private static boolean presentationScopeCurrent(Connection connection, PlayerPhysicalSnapshot snapshot) {
        PlayerPhysicalSnapshot.Scope scope = snapshot.scope();
        return scope.playerId().equals(connection.player.getUniqueID())
                && scope.dimension() == connection.world.provider.getDimension();
    }

    private PoseState poseState(Connection connection, long tick) {
        return poseState(connection, connection.player, tick);
    }

    private PoseState poseState(Connection connection, EntityPlayerMP subject, long tick) {
        if (connection == null || connection.player != subject || !current(connection)) return null;
        AcceptedPresentation admitted = acceptedPresentation(connection).orElse(null);
        PlayerRenderPoseProtocol.Quaternion head = currentLeaseHead(connection, tick, admitted);
        if (admitted != null && admitted.kind() == PresentationKind.CARDINAL_HEAD) {
            PlayerPhysicsServer.AcceptedBodyReference acceptedLock = admitted.cardinalReference();
            PlayerPhysicalSnapshot accepted = admitted.snapshot();
            PlayerBodyShape.Cardinal cardinal = (PlayerBodyShape.Cardinal) accepted.placement().shape();
            PlayerRenderPoseProtocol.Quaternion body = acceptedBody(subject, acceptedLock);
            if (head == null) {
                head = fallbackCardinalHead(subject, cardinal, acceptedLock);
            }
            return new PoseState(PlayerRenderPoseProtocol.Mode.CARDINAL, acceptedLock.frame().ordinal(),
                    accepted.revision(), accepted.scope().sessionId(), 0, epoch(connection.world),
                    connection.world.provider.getDimension(), body, head,
                    new PlayerRenderPoseProtocol.CardinalGeometry(accepted.scope().sessionId(),
                            accepted.scope().worldEpoch(), accepted.placement().transport(), accepted.placement().bounds()));
        }

        // accepted() is used only to prevent a stale/temporarily inadmissible Cardinal owner from
        // falling through to the legacy FREE client-body branch. It never grants a report lease.
        PlayerPhysicalSnapshot accepted = physics.accepted(connection.handler).orElse(null);
        if (accepted != null && accepted.placement().shape() instanceof PlayerBodyShape.Cardinal) return null;
        if (connection.lease != null && connection.lease.signature.kind() == PresentationKind.CARDINAL_HEAD) return null;
        if (connection.lease != null && connection.lease.signature.kind() == PresentationKind.FREE_OWNER
                && (admitted == null || admitted.kind() != PresentationKind.FREE_OWNER
                || !samePresentationScope(connection.lease.signature, admitted))) return null;

        FreeSample sample = currentLeaseSample(connection, tick);
        if (sample == null) return null;
        // Preserve the existing Free body-report semantics; the owner-qualified lease adds the
        // full head quaternion but does not replace the established Free body authority.
        return new PoseState(PlayerRenderPoseProtocol.Mode.FREE, -1, 0, null,
                connection.lease.id, epoch(connection.world), connection.world.provider.getDimension(),
                sample.body(), sample.head(), null);
    }

    private static PlayerRenderPoseProtocol.Quaternion fallbackCardinalHead(EntityPlayerMP subject,
            PlayerBodyShape.Cardinal cardinal, PlayerPhysicsServer.AcceptedBodyReference acceptedLock) {
        Vec3d up = acceptedLock.frame().up();
        Vec3d fallback = usesFrozenHeading(acceptedLock)
                ? acceptedLock.tangentHeading() : nativeBodyHeading(subject, up);
        if (fallback == null) fallback = acceptedLock.frame().attitude().basisZ();
        net.minecraft.util.math.Vec3d minecraftLook = subject.getLook(1.0F);
        Vec3d look = new Vec3d(minecraftLook.x, minecraftLook.y, minecraftLook.z);
        Vec3d headForward = normalized(look);
        if (headForward == null) headForward = fallback;
        Vec3d headUp = tangent(up, headForward);
        if (headUp == null) headUp = tangent(fallback, headForward);
        if (headUp == null) headUp = cardinal.frame().attitude().basisZ();
        return PlayerRenderPoseProtocol.Quaternion.fromUpForward(headUp, headForward);
    }

    private FreeSample currentLeaseSample(Connection connection, long tick) {
        FreeLease lease = connection.lease;
        if (lease == null || lease.sample == null || lease.id <= 0 || lease.sample.leaseId() != lease.id
                || lease.worldEpoch != epoch(connection.world)
                || lease.dimension != connection.world.provider.getDimension()
                || tick - lease.sample.tick() > FREE_REPORT_TTL_TICKS) return null;
        return lease.sample;
    }

    private PlayerRenderPoseProtocol.Quaternion currentLeaseHead(Connection connection, long tick,
            AcceptedPresentation admitted) {
        FreeSample sample = currentLeaseSample(connection, tick);
        if (sample == null) return null;
        PermitSignature signature = connection.lease.signature;
        if (signature.kind() == PresentationKind.CARDINAL_HEAD) {
            return admitted != null && admitted.kind() == PresentationKind.CARDINAL_HEAD
                    && samePresentationScope(signature, admitted) ? sample.head() : null;
        }
        if (signature.kind() == PresentationKind.FREE_OWNER) {
            return admitted != null && admitted.kind() == PresentationKind.FREE_OWNER
                    && samePresentationScope(signature, admitted) ? sample.head() : null;
        }
        if (admitted != null && !samePresentationScope(signature, admitted)) return null;
        return sample.head();
    }

    private static boolean samePresentationScope(PermitSignature signature, AcceptedPresentation admitted) {
        PlayerPhysicalSnapshot.Scope scope = admitted.snapshot().scope();
        return (signature.kind() == PresentationKind.LEGACY || signature.kind() == admitted.kind())
                && Objects.equals(signature.physicsScope(), scope.sessionId())
                && signature.physicsEpoch() == scope.worldEpoch();
    }

    private static PlayerRenderPoseProtocol.Quaternion acceptedBody(EntityPlayerMP subject,
            PlayerPhysicsServer.AcceptedBodyReference acceptedLock) {
        Vec3d heading = usesFrozenHeading(acceptedLock)
                ? acceptedLock.tangentHeading() : nativeBodyHeading(subject, acceptedLock.frame().up());
        if (heading == null) heading = acceptedLock.frame().attitude().basisZ();
        return PlayerRenderPoseProtocol.Quaternion.fromUpForward(acceptedLock.frame().up(), heading);
    }

    private static boolean usesFrozenHeading(PlayerPhysicsServer.AcceptedBodyReference reference) {
        return reference.source() == PlayerBodyReference.Source.MANUAL
                || reference.source() == PlayerBodyReference.Source.ENVIRONMENT;
    }

    private static Vec3d nativeBodyHeading(EntityPlayerMP subject, Vec3d up) {
        float bodyYaw = Float.isFinite(subject.renderYawOffset) ? subject.renderYawOffset : subject.rotationYaw;
        if (!Float.isFinite(bodyYaw)) return null;
        return tangent(new Vec3d(-Math.sin(Math.toRadians(bodyYaw)), 0,
                Math.cos(Math.toRadians(bodyYaw))), up);
    }

    private void failSubject(Connection connection, String operation, RuntimeException failure) {
        long failedLease = connection.lease == null ? 0 : connection.lease.id;
        try {
            revokeFreeLease(connection);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
        for (Link viewer : snapshotLinks()) {
            try {
                removePair(viewer, connection.player.getUniqueID(), connection.player, true);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        if (connection.lastPresentationFailure != failure) {
            connection.lastPresentationFailure = failure;
            UnigravMod.LOGGER.error("ugpose1 {} failed for player={} dimension={} worldEpoch={} viewerRealm={} requestGeneration={} freeLease={}",
                    operation, connection.player.getUniqueID(), connection.world.provider.getDimension(),
                    epoch(connection.world), connection.viewerRealm, connection.worldRequestGeneration,
                    failedLease, failure);
        }
    }

    private void reconcileSubject(Connection owner, PoseState state, long tick) {
        EntityPlayerMP subject = owner.player;
        Set<EntityPlayerMP> actual = new HashSet<>();
        WorldServer subjectWorld = subject.world instanceof WorldServer serverWorld ? serverWorld : null;
        if (subjectWorld == null) return;
        for (EntityPlayer candidate : subjectWorld.getEntityTracker().getTrackingPlayers(subject)) {
            if (candidate instanceof EntityPlayerMP viewer && viewer != subject) actual.add(viewer);
        }
        for (EntityPlayerMP viewer : actual) {
            Link viewerLink = link(viewer.connection == null ? null : viewer.connection.netManager);
            if (viewerLink == null || viewerLink.current == null || !viewerLink.current.hello
                    || !current(viewerLink.current) || viewerLink.current.world != subject.world) continue;
            reconcilePair(viewerLink, subject, state, tick);
        }
        for (Link viewerLink : snapshotLinks()) {
            Map<UUID, Pair> bySubject = pairs.get(viewerLink.manager);
            if (bySubject == null) continue;
            Pair pair = bySubject.get(subject.getUniqueID());
            if (pair != null && (!actual.contains(pair.viewer.player)
                    || !isTracking(pair.subject, pair.viewer.player)))
                removePair(viewerLink, subject.getUniqueID(), pair.subject, true);
        }
    }

    private void seedTracked(Connection viewer) {
        if (!current(viewer) || !viewer.hello) return;
        for (EntityPlayer player : viewer.world.playerEntities) {
            if (player instanceof EntityPlayerMP subject && subject != viewer.player
                    && isTracking(subject, viewer.player)) {
                Link ownerLink = link(subject.connection == null ? null : subject.connection.netManager);
                if (ownerLink == null || ownerLink.current == null || !current(ownerLink.current)) continue;
                if (!refreshFreeLease(ownerLink.current)) continue;
                long tick = subject.world.getTotalWorldTime();
                reconcilePair(viewer.link, subject, poseState(ownerLink.current, tick), tick);
            }
        }
    }

    private void reconcilePair(Link viewerLink, EntityPlayerMP subject, PoseState state, long tick) {
        if (viewerLink == null || viewerLink.current == null || !viewerLink.current.hello
                || !current(viewerLink.current) || !isTracking(subject, viewerLink.current.player)) return;
        Connection viewer = viewerLink.current;
        Map<UUID, Pair> bySubject = pairs.computeIfAbsent(viewerLink.manager, ignored -> new HashMap<>());
        UUID subjectId = subject.getUniqueID();
        Pair pair = bySubject.get(subjectId);
        PlayerPhysicsServer.PresentationRelease release = physics.presentationRelease(subject.connection).orElse(null);
        if (release != null && pair != null && pair.subject == subject && pair.state != null
                && pair.state.geometry != null && pair.state.physicsScope.equals(release.scope().sessionId())
                && pair.state.geometry.physicsWorldEpoch() == release.scope().worldEpoch()
                && release.revision() >= pair.state.physicalRevision) {
            if (!pair.released) {
                pair.sequence = Math.incrementExact(pair.sequence);
                outbound.accept(viewer.player, PlayerRenderPoseProtocol.Packet.presentationRelease(viewer.clientToken,
                        viewer.viewerRealm, subjectId, pair.entityId, pair.state.dimension, pair.state.worldEpoch,
                        pair.lifetime, pair.sequence, release.revision(),
                        new PlayerRenderPoseProtocol.PresentationRelease(release.scope().sessionId(),
                                release.scope().worldEpoch(), release.generation())));
                pair.released = true;
                pair.suspended = true;
            }
            return;
        }
        if (state == null) {
            if (pair != null && !pair.suspended) {
                sendRemoval(pair);
                pair.suspended = true;
            }
            return;
        }
        if (pair != null && (pair.suspended || !pair.sameContext(subject, state))) {
            removePair(viewerLink, subjectId, pair.subject, true);
            pair = null;
            bySubject = pairs.computeIfAbsent(viewerLink.manager, ignored -> new HashMap<>());
        }
        if (pair == null) {
            long lifetime = nextPresentationLifetime = Math.incrementExact(nextPresentationLifetime);
            pair = new Pair(viewer, subject, lifetime);
            bySubject.put(subjectId, pair);
        }
        if (!pair.changed(state) && tick - pair.lastSentTick < POSE_HEARTBEAT_TICKS) return;
        pair.sequence = Math.incrementExact(pair.sequence);
        pair.state = state;
        pair.lastSentTick = tick;
        outbound.accept(viewer.player, new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE,
                viewer.clientToken, viewer.viewerRealm, 0,
                subjectId, subject.getEntityId(), state.dimension, state.worldEpoch, pair.lifetime, pair.sequence,
                state.physicalRevision, state.mode, state.cardinalFrame, state.body, state.head, state.geometry));
    }

    private void removePair(Link link, UUID subjectId, EntityPlayerMP expected, boolean send) {
        Map<UUID, Pair> bySubject = pairs.get(link.manager);
        if (bySubject == null) return;
        Pair pair = bySubject.get(subjectId);
        if (pair == null || expected != null && pair.subject != expected) return;
        bySubject.remove(subjectId);
        if (bySubject.isEmpty()) pairs.remove(link.manager);
        if (!send || pair.suspended) return;
        sendRemoval(pair);
    }

    private void sendRemoval(Pair pair) {
        if (pair.state == null || !pair.viewer.hello || !current(pair.viewer)) return;
        pair.sequence = Math.incrementExact(pair.sequence);
        PoseState state = pair.state;
        outbound.accept(pair.viewer.player, PlayerRenderPoseProtocol.Packet.remove(pair.viewer.clientToken,
                pair.viewer.viewerRealm, pair.subject.getUniqueID(), pair.entityId, state.dimension,
                state.worldEpoch, pair.lifetime, pair.sequence, state.physicalRevision));
    }

    private void sendWelcome(Connection connection) {
        if (connection.clientToken == null || !connection.hello || connection.viewerRealm <= 0
                || connection.worldRequestGeneration <= 0 || !current(connection)) return;
        outbound.accept(connection.player, PlayerRenderPoseProtocol.Packet.welcome(connection.clientToken,
                connection.viewerRealm, connection.worldRequestGeneration,
                connection.player.getUniqueID(), connection.player.getEntityId(),
                connection.world.provider.getDimension(), epoch(connection.world)));
    }

    private Connection resolve(Link link, NetHandlerPlayServer handler) {
        if (!actorCurrent(handler, link.manager)) return null;
        Connection old = link.current;
        if (old != null && old.handler == handler && old.player == handler.player && old.world == handler.player.world)
            return old;
        if (old != null) retireConnection(old, true);
        Connection fresh = new Connection(link, handler, handler.player, (WorldServer) handler.player.world, 0);
        link.current = fresh;
        return fresh;
    }

    private void retireConnection(Connection connection, boolean send) {
        revokeFreeLease(connection, send);
        if (connection.link.dispatcher != null) connection.link.dispatcher.invalidate();
        for (Link viewer : snapshotLinks()) {
            Map<UUID, Pair> bySubject = pairs.get(viewer.manager);
            if (bySubject == null) continue;
            for (Pair pair : new ArrayList<>(bySubject.values())) {
                if (pair.subject == connection.player)
                    removePair(viewer, pair.subject.getUniqueID(), pair.subject, send);
            }
        }
        Map<UUID, Pair> asViewer = pairs.get(connection.link.manager);
        if (asViewer != null) {
            for (Pair pair : new ArrayList<>(asViewer.values())) {
                if (pair.viewer == connection) {
                    removePair(connection.link, pair.subject.getUniqueID(), pair.subject, send);
                }
            }
        }
        connection.lease = null;
    }

    private void retirePlayerId(UUID playerId) {
        for (Link link : snapshotLinks()) {
            Connection connection = link.current;
            if (connection != null && connection.player.getUniqueID().equals(playerId)) {
                retireConnection(connection, true);
                link.current = null;
            }
            Map<UUID, Pair> bySubject = pairs.get(link.manager);
            if (bySubject != null) removePair(link, playerId, null, true);
        }
    }

    private long epoch(WorldServer world) {
        return worldEpochs.computeIfAbsent(world, ignored -> nextWorldEpoch = Math.incrementExact(nextWorldEpoch));
    }

    private Link link(NetworkManager manager) {
        if (manager == null) return null;
        synchronized (this) { return links.get(manager); }
    }

    private ArrayList<Link> snapshotLinks() {
        synchronized (this) { return new ArrayList<>(links.values()); }
    }

    private static boolean current(Connection connection) {
        return !connection.link.retired && connection.link.connected && connection.link.registered
                && connection.handler.netManager == connection.link.manager
                && connection.link.manager.getNetHandler() == connection.handler
                && connection.player.connection == connection.handler && connection.handler.player == connection.player
                && connection.player.world == connection.world && connection.player.dimension == connection.world.provider.getDimension()
                && DimensionManager.getWorld(connection.player.dimension) == connection.world
                && !connection.player.isDead && connection.player.isEntityAlive();
    }

    private static boolean actorCurrent(NetHandlerPlayServer handler, NetworkManager manager) {
        EntityPlayerMP player = handler.player;
        return player != null && player.connection == handler && handler.netManager == manager
                && manager.getNetHandler() == handler && manager.isChannelOpen() && player.getServer() != null
                && player.world instanceof WorldServer
                && DimensionManager.getWorld(player.dimension) == player.world
                && player.dimension == player.world.provider.getDimension()
                && !player.isDead && player.isEntityAlive();
    }

    private static boolean isTracking(EntityPlayerMP subject, EntityPlayerMP viewer) {
        return subject != viewer && subject.world instanceof WorldServer world
                && subject.world == viewer.world && world.getEntityTracker().getTrackingPlayers(subject).contains(viewer);
    }

    private static Vec3d tangent(Vec3d value, Vec3d normal) {
        Vec3d unitNormal = normalized(normal);
        if (unitNormal == null) return null;
        return normalized(value.subtract(unitNormal.multiply(value.dot(unitNormal))));
    }

    private static Vec3d normalized(Vec3d value) {
        double lengthSquared = value.dot(value);
        if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-12) return null;
        return value.multiply(1.0 / Math.sqrt(lengthSquared));
    }

    private static final class Link {
        private final NetworkManager manager;
        private volatile boolean connected;
        private volatile boolean registered;
        private volatile boolean retired;
        private boolean overflowReported;
        private UUID clientToken;
        private long highestRequestGeneration;
        private volatile Connection current;
        private RemoteBoostBoundedDispatcher<PlayerRenderPoseProtocol.Packet> dispatcher;

        private Link(NetworkManager manager) { this.manager = manager; }

        private synchronized RemoteBoostBoundedDispatcher<PlayerRenderPoseProtocol.Packet> dispatcher(NetHandlerPlayServer handler) {
            if (dispatcher == null) {
                MinecraftServer server = handler.player.getServer();
                dispatcher = new RemoteBoostBoundedDispatcher<>(MAX_PENDING_REPORTS, server::addScheduledTask);
            }
            return dispatcher;
        }

        private Connection currentFor(EntityPlayerMP player) {
            return current != null && current.player == player ? current : null;
        }
    }

    private static final class Connection {
        private final Link link;
        private final NetHandlerPlayServer handler;
        private final EntityPlayerMP player;
        private final WorldServer world;
        private long viewerRealm;
        private long worldRequestGeneration;
        private RuntimeException lastPresentationFailure;
        private UUID clientToken;
        private boolean hello;
        private long leaseControlSequence;
        private FreeLease lease;

        private Connection(Link link, NetHandlerPlayServer handler, EntityPlayerMP player,
                           WorldServer world, long viewerRealm) {
            this.link = link;
            this.handler = handler;
            this.player = player;
            this.world = world;
            this.viewerRealm = viewerRealm;
        }
    }

    private static final class FreeLease {
        private final long id;
        private final long worldEpoch;
        private final int dimension;
        private final PermitSignature signature;
        private long lastReportSequence;
        private long lastReportTick = Long.MIN_VALUE;
        private FreeSample sample;

        private FreeLease(long id, long worldEpoch, int dimension, PermitSignature signature) {
            this.id = id;
            this.worldEpoch = worldEpoch;
            this.dimension = dimension;
            this.signature = signature;
        }
    }

    private record FreeSample(PlayerRenderPoseProtocol.Quaternion body, PlayerRenderPoseProtocol.Quaternion head,
                              long sequence, long tick, long leaseId) { }

    private enum PresentationKind { LEGACY, CARDINAL_HEAD, FREE_OWNER }

    private record AcceptedPresentation(PresentationKind kind, PlayerPhysicalSnapshot snapshot,
                                        PlayerPhysicsServer.AcceptedBodyReference cardinalReference) { }

    private record PermitSignature(UUID playerId, WorldServer world, int dimension, long worldEpoch,
                                   boolean integrated, boolean bound, boolean custody, UUID gSession,
                                   GIntentObservation.Source gSource, long gSourceEpoch, long gRevision,
                                   boolean gActive, UUID physicsScope, long physicsEpoch, PresentationKind kind) { }

    private record PoseState(PlayerRenderPoseProtocol.Mode mode, int cardinalFrame, long physicalRevision,
                             UUID physicsScope, long freeLease, long worldEpoch, int dimension,
                             PlayerRenderPoseProtocol.Quaternion body, PlayerRenderPoseProtocol.Quaternion head,
                             PlayerRenderPoseProtocol.CardinalGeometry geometry) { }

    private static final class Pair {
        private final Connection viewer;
        private final EntityPlayerMP subject;
        private final long lifetime;
        private final int entityId;
        private long sequence;
        private long lastSentTick = Long.MIN_VALUE;
        private PoseState state;
        private boolean suspended;
        private boolean released;

        private Pair(Connection viewer, EntityPlayerMP subject, long lifetime) {
            this.viewer = viewer;
            this.subject = subject;
            this.lifetime = lifetime;
            entityId = subject.getEntityId();
        }

        private boolean sameContext(EntityPlayerMP candidate, PoseState next) {
            return subject == candidate && entityId == candidate.getEntityId()
                    && subject.world == candidate.world && state != null
                    && state.mode == next.mode && state.cardinalFrame == next.cardinalFrame
                    && Objects.equals(state.physicsScope, next.physicsScope) && state.freeLease == next.freeLease
                    && (state.geometry == null && next.geometry == null
                    || state.geometry != null && next.geometry != null
                    && state.geometry.physicsWorldEpoch() == next.geometry.physicsWorldEpoch())
                    && state.dimension == next.dimension && state.worldEpoch == next.worldEpoch;
        }

        private boolean changed(PoseState next) {
            if (state == null) return true;
            return state.physicalRevision != next.physicalRevision
                    || !Objects.equals(state.geometry, next.geometry)
                    || !sameRotation(state.body, next.body) || !sameRotation(state.head, next.head);
        }

        private static boolean sameRotation(PlayerRenderPoseProtocol.Quaternion first,
                                            PlayerRenderPoseProtocol.Quaternion second) {
            return Math.abs(first.dot(second)) >= 1.0 - 1.0e-10;
        }
    }
}
