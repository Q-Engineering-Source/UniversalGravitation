package wi.unigrav.core.boost;

import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.server.SPacketEntityVelocity;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.player.GIntentObservation;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsServer;

/**
 * Dedicated-server authority for bounded Creative remote boost.
 *
 * <p>G intent is owned exclusively by MODE. Packet, budget, loaded-column, native-collision and
 * tracker incidents restrict the carrier and/or correct movement, but do not revoke Personal G
 * for an otherwise eligible actor. Native {@link EntityPlayerMP#move} is still the one collision
 * pass; this class merely replaces an admitted CPacket destination before its native locals are
 * captured and records the resulting native endpoint.
 */
public final class RemoteBoostServer {
    public static final RemoteBoostServer INSTANCE = new RemoteBoostServer(BoostNetwork::sendTo);

    private static final int FRAME_TIMEOUT_TICKS = 20;
    private static final int STATE_HEARTBEAT_TICKS = 20;
    private static final int MAX_INBOUND_PACKETS_PER_CONNECTION = 8;
    private static final double NATIVE_QUERY_MARGIN = 1.0D;
    private static final double NATIVE_ENVELOPE_MARGIN = 1.0D;

    private final Map<NetHandlerPlayServer, Session> sessions = new IdentityHashMap<>();
    private final Map<NetworkManager, Capability> capabilities = new IdentityHashMap<>();
    private final BiConsumer<EntityPlayerMP, BoostProtocol.Packet> outbound;
    private boolean installed;
    private long serverTick;

    /** Transport-only system seam; actual negotiation, movement and budget remain in this module. */
    public RemoteBoostServer(BiConsumer<EntityPlayerMP, BoostProtocol.Packet> outbound) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    /** Process shutdown only; this is not a gameplay G-off transition. */
    public synchronized void stop() {
        for (Session session : sessions.values()) restoreNoGravity(session);
        sessions.clear();
        capabilities.clear();
    }

    /** Called by the common wire adapter only after bounded decoding. */
    public void receive(BoostProtocol.Packet packet, NetHandlerPlayServer handler) {
        RemoteBoostBoundedDispatcher.Reservation<BoostProtocol.Packet> reservation;
        Session session;
        synchronized (this) {
            if (packet == null || handler == null) return;
            session = sessions.get(handler);
            Capability capability = capabilities.get(handler.netManager);
            if (session == null || capability == null || !capability.registered
                    || capability.disconnectPending || capability.unregistrationPending) return;
            reservation = session.dispatcher.reserve(packet, value -> dispatchScheduled(session, value));
            if (reservation == null) {
                session.dispatchOverflow = true;
                return;
            }
        }
        // Never submit while holding the server or dispatcher monitor: Netty and future-task
        // queues otherwise create the known watchdog lock cycle.
        session.dispatcher.submit(reservation);
    }

    /** Pure routing guard; includes ACTIVE/CLOSING (also during resync), without preparing or refunding credit. */
    public synchronized boolean hasMovementCustody(NetHandlerPlayServer handler) {
        Session session = sessions.get(handler);
        return session != null && (session.hasCustody() || session.pendingMovement != null);
    }

    /** MODE remains the sole dedicated G writer; this exact-source read does not transfer custody. */
    public synchronized Optional<GIntentObservation> observePersonalG(EntityPlayerMP player) {
        if (!player.getServerWorld().isCallingFromMinecraftThread()) throw new IllegalStateException("G read off-thread");
        Session session = sessionFor(player);
        if (session == null || session.physicsRetired || !session.negotiated || session.manager != player.connection.netManager
                || session.manager.getNetHandler() != session.handler || !session.manager.isChannelOpen()
                || !currentSessionValid(session)) return Optional.empty();
        Capability capability = capabilities.get(session.manager);
        if (capability == null || !capability.registered || capability.disconnectPending
                || capability.unregistrationPending) return Optional.empty();
        if (session.mode != BoostProtocol.Mode.ACTIVE && session.mode != BoostProtocol.Mode.READY
                && session.mode != BoostProtocol.Mode.CLOSING) return Optional.empty();
        var key = new GIntentProtocol.Key(new UUID(0, session.nonce), player.getUniqueID(), player.dimension);
        return Optional.of(new GIntentObservation(key, GIntentObservation.Source.DEDICATED_MODE,
                session.epoch, session.lastControlSequence, session.mode == BoostProtocol.Mode.ACTIVE));
    }

    /** Converts a UG-owned impulse to the negotiated ADD channel exactly once. */
    public synchronized boolean addImpulse(Entity entity, Vec3 impulseNewtonSeconds) {
        if (!(entity instanceof EntityPlayerMP player) || !finite(impulseNewtonSeconds)) return false;
        Session session = sessionFor(player);
        if (session == null || !session.hasCustody()) return false;
        BodyPhysics physics = UnigravMod.entityBinder.bodyPhysicsOf(player);
        double mass = physics != null ? physics.massKg() : BodyPhysics.estimate(player).massKg();
        if (!Double.isFinite(mass) || mass <= 0.0D) {
            restrict(session, BoostProtocol.Reason.EXTERNAL_VELOCITY);
            return true;
        }
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(player)) {
            mutation.changed();
            // ADD changes future client velocity, not this packet's valid native endpoint.
            // Commit the original budget position, but never certify that interval for handoff.
            if (session.pendingMovement != null) session.pendingMovement.observationDirty = true;
            sendState(session, BoostProtocol.Operation.ADD,
                    clampVelocity(impulseNewtonSeconds.scale(1.0D / mass), policyVelocityCeiling(session)),
                    BoostProtocol.Reason.NONE);
        }
        return true;
    }

    /**
     * Mixin entry at the first native targetPos read, after thread-hop/invalid-packet checks and
     * before native X/Y/Z locals. It returns the original packet unless the actor has a bounded
     * one-pass native admission.
     */
    public synchronized CPacketPlayer replacePacketForNativeMove(NetHandlerPlayServer handler,
            CPacketPlayer packet, Vec3 firstGood, boolean nativeTeleportPending) {
        Session session = sessions.get(handler);
        if (session != null) session.movementRevision = Math.incrementExact(session.movementRevision);
        if (session == null || !session.hasCustody() || !positionBearing(packet)) return packet;
        if (!currentSessionValid(session)) {
            releaseAuthority(session, BoostProtocol.Reason.UNSUPPORTED_STATE);
            return packet;
        }
        if (nativeTeleportPending || session.phase == RemoteBoostServerPhase.Value.RESYNC) {
            return quarantine(packet, session.player);
        }
        if (session.pendingMovement != null) {
            restrict(session, BoostProtocol.Reason.PROTOCOL);
            return quarantine(packet, session.player);
        }

        RemoteBoostOrderedEvidence.Entry evidence = nextEvidence(session);
        if (evidence != null && evidence.drop()) {
            return quarantine(packet, session.player);
        }
        RemoteBoostOrderedEvidence.Frame frame = evidence == null ? null : evidence.frame();
        if (session.requireFrameBeforeMovement && frame == null) {
            return quarantine(packet, session.player);
        }
        if (session.phase == RemoteBoostServerPhase.Value.ARMING) {
            if (!RemoteBoostServerPhase.maySeed(session.phase, frame != null, true)) return packet;
            seedBudget(session);
        }
        if (session.budget == null) return packet;
        boolean paired = frame != null;
        if (!paired) restrict(session, BoostProtocol.Reason.TIMEOUT);
        else {
            session.lastConsumedFrameSequence = frame.sequence();
            session.lastValidatedGear = frame.gear();
            session.settledFrame = frame.settled();
        }

        Vec3 raw = packetPosition(packet, firstGood);
        BoostMovementBudget.Admission admission = session.budget.prepareClipped(++session.budgetSequence,
                serverTick, raw);
        if (!admission.accepted()) {
            restrict(session, BoostProtocol.Reason.BUDGET);
            return quarantine(packet, session.player);
        }

        RemoteBoostLoadedPath.Result clipped;
        boolean queryFailed = false;
        try {
            clipped = clipLoadedPath(session.player, admission.start(), admission.destination());
        } catch (IllegalArgumentException failure) {
            // A malformed/unrepresentable query must not become a world lookup. The reservation
            // remains spent by contract; retain G and submit a stationary native proposal.
            restrict(session, BoostProtocol.Reason.UNLOADED_AREA);
            clipped = new RemoteBoostLoadedPath.Result(admission.start(), 0.0D,
                    java.util.OptionalLong.empty());
            queryFailed = true;
        }
        if (!clipped.fullyLoaded()) restrict(session, BoostProtocol.Reason.UNLOADED_AREA);
        session.pendingMovement = new PendingMovement(admission, firstGood, raw, clipped.endpoint(),
                paired, clipped.fullyLoaded() && !queryFailed, session, serverTick);
        return RemoteBoostNativePacketAdapter.replacePosition(packet, clipped.endpoint());
    }

    /** Mixin entry replacing only vanilla's normal finite 100-point movement allowance. */
    public synchronized float nativeThreshold(NetHandlerPlayServer handler, int packetCount,
            float vanillaThreshold) {
        Session session = sessions.get(handler);
        if (session == null || session.pendingMovement == null || packetCount <= 0) {
            return vanillaThreshold;
        }
        double allowance = session.budget.vanillaAllowanceSquared(session.pendingMovement.firstGood);
        if (!Double.isFinite(allowance) || allowance < 0.0D || allowance > 1_000_000.0D) {
            restrict(session, BoostProtocol.Reason.BUDGET);
            return vanillaThreshold;
        }
        return (float) Math.max(vanillaThreshold, allowance / packetCount);
    }

    /** Captures the endpoint after the sole original EntityPlayerMP.move invocation. */
    public synchronized void captureNativeResult(NetHandlerPlayServer handler) {
        Session session = sessions.get(handler);
        if (session != null && session.pendingMovement != null) {
            session.pendingMovement.nativePosition = positionOf(session.player);
        }
    }

    /** Rejects only a non-finite/lifecycle/arbitrarily-far native callback endpoint. */
    public synchronized boolean mayCommitNativeEndpoint(NetHandlerPlayServer handler,
            boolean nativeTeleportPending) {
        Session session = sessions.get(handler);
        if (session == null || session.pendingMovement == null) return true;
        PendingMovement pending = session.pendingMovement;
        if (nativeTeleportPending || !sameWorld(session, pending) || !finite(pending.nativePosition)
                || !insideNativeEnvelope(pending)) {
            session.budget.abort(pending.admission);
            session.pendingMovement = null;
            restrict(session, nativeTeleportPending ? BoostProtocol.Reason.TELEPORT
                    : BoostProtocol.Reason.COLLISION);
            return false;
        }
        return true;
    }

    /** The final native assignment always receives the native endpoint, never raw client XYZ. */
    public synchronized Vec3 finalNativePosition(NetHandlerPlayServer handler, Vec3 requested) {
        Session session = sessions.get(handler);
        return session != null && session.pendingMovement != null && finite(session.pendingMovement.nativePosition)
                ? session.pendingMovement.nativePosition : requested;
    }

    /** Mixin entry immediately before the final vanilla tracker update. */
    public synchronized void markMovementSuccess(NetHandlerPlayServer handler) {
        Session session = sessions.get(handler);
        if (session != null && session.pendingMovement != null) session.pendingMovement.successMarked = true;
    }

    /** Retires even tentatively successful movement after an exceptional packet exit; never refunds or switches G off. */
    public synchronized void abortMovement(NetHandlerPlayServer handler) {
        Session session = sessions.get(handler);
        if (session != null) session.movementRevision = Math.incrementExact(session.movementRevision);
        if (session == null || session.pendingMovement == null) return;
        session.pendingMovement.successMarked = false;
        finishMovement(handler, false);
    }

    /** Releases an uncommitted ticket and commits a validated native result without refunds. */
    public synchronized Optional<CompletedMovement> finishMovement(NetHandlerPlayServer handler, boolean nativeTeleportPending) {
        Session session = sessions.get(handler);
        if (session == null || session.pendingMovement == null) return Optional.empty();
        PendingMovement pending = session.pendingMovement;
        session.pendingMovement = null;
        if (!pending.successMarked || nativeTeleportPending || !sameWorld(session, pending)
                || !completionSourceCurrent(session) || session.budget != pending.budget
                || session.epoch != pending.epoch || session.movementRevision != pending.movementRevision
                || serverTick != pending.serverTick || pending.world.getTotalWorldTime() != pending.worldTick) {
            pending.budget.abort(pending.admission);
            restrict(session, nativeTeleportPending ? BoostProtocol.Reason.TELEPORT
                    : BoostProtocol.Reason.COLLISION);
            return Optional.empty();
        }
        Vec3 actual = positionOf(session.player);
        if (!finite(actual) || !finite(pending.nativePosition) || !insideNativeEnvelope(pending)
                || !samePosition(actual, pending.nativePosition)
                || !session.budget.commitNative(pending.admission, actual)) {
            // A failed final callback must retire the reserved ticket, without refunding it.
            session.budget.abort(pending.admission);
            restrict(session, BoostProtocol.Reason.COLLISION);
            return Optional.empty();
        }
        applyCorridor(session, actual.y());
        if (pending.paired && pending.fullyLoaded && same(pending.rawDestination, pending.candidate)
                && same(actual, pending.candidate) && !session.restricted
                && session.incidentRestricted) {
            session.incidentRestricted = false;
            // A recovered outside-band pair restores only the future carrier. changeCeiling first
            // advances at the old rate and never grants an extra high-speed credit.
            session.budget.changeCeiling(serverTick, grantCeiling());
        }
        if (!same(actual, pending.rawDestination) || !same(actual, pending.candidate)) {
            session.queuedCorrection = actual;
        }
        return pending.observationDirty ? Optional.empty()
                : Optional.of(new CompletedMovement(this, session, completionStamp(session)));
    }

    /** Opaque proof of one old budget commit. It exposes no budget, lease, or physical authority. */
    public static final class CompletedMovement {
        private final RemoteBoostServer issuer;
        private final Session session;
        private final CompletionStamp stamp;
        private boolean consumed;
        private boolean accepted;

        private CompletedMovement(RemoteBoostServer issuer, Session session, CompletionStamp stamp) {
            this.issuer = issuer;
            this.session = session;
            this.stamp = stamp;
        }
    }

    /** Router consumes once after both finishers; a committed correction is not a clean observation. */
    public synchronized boolean consumeCompletedMovement(NetHandlerPlayServer handler, CompletedMovement completion) {
        if (completion == null || completion.issuer != this || completion.consumed) return false;
        if (!(completion.stamp.world() instanceof WorldServer world) || !world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Remote completion consumption requires the server thread");
        }
        completion.consumed = true;
        Session session = completion.session;
        completion.accepted = sessions.get(handler) == session && session.handler == handler && completionSourceCurrent(session)
                && session.pendingMovement == null && session.budget != null
                && !session.budget.snapshot().pending() && session.phase == RemoteBoostServerPhase.Value.ACTIVE
                && !session.incidentRestricted && session.queuedCorrection == null
                && !session.requireFrameBeforeMovement && !session.player.velocityChanged
                && completion.stamp.equals(completionStamp(session));
        if (completion.accepted) {
            double ceiling = Math.max(session.budget.ceilingMps(), session.budget.proposalCeilingMps());
            long tick = completion.stamp.worldTick();
            if (!session.carriers.isEmpty() && session.carriers.getLast().budget() != session.budget) session.carriers.clear();
            if (!session.carriers.isEmpty() && session.carriers.getLast().tick() == tick) {
                ceiling = Math.max(ceiling, session.carriers.removeLast().ceiling());
            }
            session.carriers.addLast(new CarrierSample(tick, session.budget, ceiling));
            while (session.carriers.size() > 3) session.carriers.removeFirst();
        }
        return completion.accepted;
    }

    /** Same-thread sealed tail: validate the private session, install its budget, retire the old writer. */
    public synchronized boolean transferCompletedMovement(NetHandlerPlayServer handler, CompletedMovement completion,
            PlayerPhysicsProtocol.ApplicationId application, long previousWorldTick, PlayerPhysicsServer destination) {
        Session session = sessions.get(handler);
        if (session == null || session.physicsRetired || !session.negotiated) return false;
        if (!(session.player.world instanceof WorldServer world) || !world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Physical transfer requires the server thread");
        }
        Capability capability = capabilities.get(session.manager);
        if (capability == null || !capability.registered || capability.disconnectPending || capability.unregistrationPending
                || !session.manager.isChannelOpen() || session.manager.getNetHandler() != handler
                || session.player != handler.player || session.player.connection != handler
                || session.player.isDead || DimensionManager.getWorld(session.player.dimension) != world
                || session.publicationBroken || !application.equals(session.publishedApplication)) return false;
        if (!session.hasCustody()) {
            // A negotiated READY peer owns no budget, but must retire before a fresh ordinary budget exists.
            if (completion != null || session.mode != BoostProtocol.Mode.READY || session.budget != null
                    || session.pendingMovement != null || !destination.installRemoteCompletion(this, handler,
                            Optional.empty(), 0, session.player.hasNoGravity())) return false;
            session.physicsRetired = true;
            session.evidence.clear();
            return true;
        }
        if (completion == null || completion.issuer != this || completion.session != session
                || !completion.consumed || !completion.accepted) return false;
        if (sessions.get(handler) != session || !completionSourceCurrent(session)
                || session.budget == null || session.pendingMovement != null || session.budget.snapshot().pending()
                || !completion.stamp.equals(completionStamp(session)) || session.publicationBroken
                || !application.equals(session.publishedApplication)) return false;
        long ticks = world.getTotalWorldTime() - previousWorldTick;
        if (ticks < 1 || ticks > 2) return false;
        boolean hasPrevious = false;
        double ceiling = 0;
        for (CarrierSample sample : session.carriers) {
            if (sample.budget() != session.budget || sample.tick() < previousWorldTick) continue;
            hasPrevious |= sample.tick() == previousWorldTick;
            ceiling = Math.max(ceiling, sample.ceiling());
        }
        if (!hasPrevious || !destination.installRemoteCompletion(this, handler, Optional.of(session.budget), ceiling,
                session.noGravityOwned ? session.originalNoGravity : session.player.hasNoGravity())) return false;
        // No transport or user callbacks between installing the new owner and retiring this one.
        session.physicsRetired = true;
        session.budget = null;
        session.noGravityOwned = false;
        session.evidence.clear();
        session.carriers.clear();
        completion.accepted = false;
        return true;
    }

    private record CarrierSample(long tick, BoostMovementBudget budget, double ceiling) { }

    private boolean completionSourceCurrent(Session session) {
        Capability capability = capabilities.get(session.manager);
        return sessions.get(session.handler) == session && session.negotiated && session.hasCustody()
                && capability != null && capability.registered && !capability.disconnectPending
                && !capability.unregistrationPending && session.manager.isChannelOpen()
                && session.manager.getNetHandler() == session.handler && currentSessionValid(session)
                && session.player.world instanceof WorldServer
                && DimensionManager.getWorld(session.player.dimension) == session.player.world;
    }

    private CompletionStamp completionStamp(Session session) {
        return new CompletionStamp(serverTick, session.player.world.getTotalWorldTime(), session.epoch,
                session.movementRevision, session.serial, session.lastFrameSequence, session.lastControlSequence,
                session.player.world, session.player.dimension, session.budget, session.budget.snapshot(),
                positionOf(session.player), session.player.getEntityBoundingBox(),
                new Vec3(session.player.motionX, session.player.motionY, session.player.motionZ));
    }

    // Read-only equality stamp only: never used to recreate, refill or transfer a budget.
    private record CompletionStamp(long serverTick, long worldTick, long epoch, long movementRevision,
                                   long serial, long frameSequence, long controlSequence, World world,
                                   int dimension, BoostMovementBudget budget, BoostMovementBudget.Snapshot budgetState,
                                   Vec3 position, AxisAlignedBB bounds, Vec3 observedNativeMotion) { }

    private static boolean samePosition(Vec3 first, Vec3 second) {
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z();
    }

    /** Called at setPlayerLocation tail so native correction IDs, including retransmits, are real. */
    public synchronized void observeNativeTeleport(NetHandlerPlayServer handler, int teleportId,
            Vec3 target) {
        Session session = sessions.get(handler);
        if (session == null || !session.hasCustody() || !finite(target)) return;
        session.movementRevision = Math.incrementExact(session.movementRevision);
        session.expectedTeleportId = teleportId;
        session.phase = RemoteBoostServerPhase.Value.RESYNC;
        session.evidence.clear();
        session.requireFrameBeforeMovement = true;
        session.resyncTarget = target;
    }

    /** Called after vanilla ConfirmTeleport; only a matching consumed native ID ends RESYNC. */
    public synchronized void confirmNativeTeleport(NetHandlerPlayServer handler,
            CPacketConfirmTeleport packet, boolean nativePendingCleared) {
        Session session = sessions.get(handler);
        if (session == null || packet == null || !session.hasCustody()) return;
        boolean matching = session.expectedTeleportId == packet.getTeleportId() && nativePendingCleared;
        session.phase = RemoteBoostServerPhase.afterMatchingNativeConfirm(session.phase, matching,
                session.budget != null);
        if (matching) {
            session.movementRevision = Math.incrementExact(session.movementRevision);
            if (session.budget != null) session.budget.rebase(positionOf(session.player), serverTick);
            session.evidence.clear();
            session.requireFrameBeforeMovement = true;
            session.resyncTarget = null;
        }
    }

    /** Tracker redirect calls this only after original vanilla velocity delivery. */
    public synchronized void afterTrackerVelocity(Entity entity, SPacketEntityVelocity packet) {
        if (!(entity instanceof EntityPlayerMP player)) return;
        Session session = sessionFor(player);
        if (session == null || !session.hasCustody()) return;
        restrict(session, BoostProtocol.Reason.EXTERNAL_VELOCITY);
    }

    @SubscribeEvent
    public synchronized void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER) return;
        NetworkManager manager = event.getManager();
        if ("REGISTER".equals(event.getOperation())
                && event.getRegistrations().contains(BoostProtocol.CHANNEL)) {
            capabilities.computeIfAbsent(manager, ignored -> new Capability()).registered = true;
        } else if ("UNREGISTER".equals(event.getOperation())
                && event.getRegistrations().contains(BoostProtocol.CHANNEL)) {
            capabilities.computeIfAbsent(manager, ignored -> new Capability()).unregistrationPending = true;
        }
    }

    @SubscribeEvent
    public synchronized void onConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).connectionPending = true;
    }

    @SubscribeEvent
    public synchronized void onConnectionClosed(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).disconnectPending = true;
    }

    @SubscribeEvent
    public synchronized void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            attachRegisteredConnections();
            for (Session session : sessions.values()) {
                if (session.dispatchOverflow) {
                    session.dispatchOverflow = false;
                    restrict(session, BoostProtocol.Reason.PROTOCOL);
                }
            }
            return;
        }
        if (event.phase != TickEvent.Phase.END) return;
        serverTick++;
        sessions.entrySet().removeIf(entry -> tickSession(entry.getValue()));
    }

    public synchronized String diagnostics() {
        long negotiated = sessions.values().stream().filter(session -> session.negotiated).count();
        long custody = sessions.values().stream().filter(Session::hasCustody).count();
        String gears = sessions.values().stream().map(session -> session.player.getName() + ":"
                + session.lastValidatedGear).collect(Collectors.joining(","));
        return "ugboost: configured=" + UnigravConfig.remoteCreativeBoost + ", hardCeiling="
                + grantCeiling() + "m/s, registeredPeers=" + sessions.size() + ", negotiatedPeers="
                + negotiated + ", custody=" + custody + ", lastValidatedGear=[" + gears
                + "], serverTick=" + serverTick;
    }

    private boolean tickSession(Session session) {
        if (!session.handler.netManager.isChannelOpen()) {
            restoreNoGravity(session);
            return true;
        }
        if (session.physicsRetired) return false;
        if (session.hasCustody() && !currentSessionValid(session)) {
            releaseAuthority(session, BoostProtocol.Reason.UNSUPPORTED_STATE);
        }
        if (session.hasCustody() && session.phase == RemoteBoostServerPhase.Value.ACTIVE
                && session.mode == BoostProtocol.Mode.ACTIVE
                && serverTick - session.lastFrameTick > FRAME_TIMEOUT_TICKS) {
            restrict(session, BoostProtocol.Reason.TIMEOUT);
        }
        if (session.hasCustody() && session.budget != null) {
            session.budget.advance(serverTick);
            applyCorridor(session, positionOf(session.player).y());
        }
        if (session.queuedCorrection != null && session.hasCustody()
                && session.phase != RemoteBoostServerPhase.Value.RESYNC) {
            Vec3 correction = session.queuedCorrection;
            session.queuedCorrection = null;
            session.handler.setPlayerLocation(correction.x(), correction.y(), correction.z(), 0.0F,
                    0.0F, EnumSet.of(SPacketPlayerPosLook.EnumFlags.X_ROT,
                            SPacketPlayerPosLook.EnumFlags.Y_ROT));
            // setPlayerLocation tail records the native teleport ID and changes only the internal phase.
            sendState(session, BoostProtocol.Operation.REPLACE, continuityVelocity(session),
                    session.pendingStateReason);
            session.pendingStateReason = BoostProtocol.Reason.NONE;
        } else if (publishState(session, session.pendingStateReason,
                session.pendingStateReason != BoostProtocol.Reason.NONE)) {
            session.pendingStateReason = BoostProtocol.Reason.NONE;
        }
        if (session.mode == BoostProtocol.Mode.CLOSING
                && session.closeWindow.observe(serverTick, positionOf(session.player),
                        session.settledFrame)) {
            completeNormalClose(session);
        }
        return false;
    }

    private void acceptHello(Session session, BoostProtocol.Packet packet) {
        switch (RemoteBoostHandshake.classify(session.negotiated, session.nonce, packet)) {
            case DROP, DUPLICATE -> { return; }
            case NEGOTIATE -> {
                session.negotiated = true;
                session.mode = BoostProtocol.Mode.READY;
                sendState(session, BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE);
            }
        }
    }

    private void attachRegisteredConnections() {
        for (NetworkManager manager : capabilities.keySet().toArray(NetworkManager[]::new)) {
            Capability capability = capabilities.get(manager);
            if (capability == null) continue;
            if (!manager.isChannelOpen() || capability.disconnectPending || capability.unregistrationPending) {
                retireManager(manager);
                capabilities.remove(manager);
            } else if (capability.connectionPending
                    && manager.getNetHandler() instanceof NetHandlerPlayServer handler) {
                attach(manager, handler);
            }
        }
    }

    private void attach(NetworkManager manager, NetHandlerPlayServer handler) {
        Session existing = sessions.get(handler);
        if (existing != null && existing.player == handler.player && existing.manager == manager) {
            if (!existing.offerSent) {
                existing.offerSent = true;
                sendOffer(existing);
            }
            return;
        }
        retireManager(manager);
        MinecraftServer scheduler = handler.player.getServer();
        if (scheduler == null) return;
        Session fresh = new Session(handler, manager, task -> scheduler.addScheduledTask(task));
        fresh.offerSent = true;
        sessions.put(handler, fresh);
        sendOffer(fresh);
    }

    private void retireManager(NetworkManager manager) {
        sessions.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (session.manager != manager) return false;
            session.dispatcher.invalidate();
            restoreNoGravity(session);
            return true;
        });
    }

    private synchronized void dispatchScheduled(Session captured, BoostProtocol.Packet packet) {
        Capability capability = capabilities.get(captured.manager);
        if (sessions.get(captured.handler) != captured || capability == null || !capability.registered
                || capability.disconnectPending || capability.unregistrationPending) return;
        receiveOnServerThread(captured, packet);
    }

    private void receiveOnServerThread(Session session, BoostProtocol.Packet packet) {
        if (!isDedicated(session.handler) || session.physicsRetired) return;
        if (packet.kind() == BoostProtocol.Kind.HELLO) {
            acceptHello(session, packet);
            return;
        }
        if (!session.negotiated || packet.version() != BoostProtocol.VERSION || packet.nonce() != session.nonce) {
            restrict(session, BoostProtocol.Reason.PROTOCOL);
            return;
        }
        if (RemoteBoostStalePacketPolicy.drop(packet.epoch(), session.epoch, packet.kind())) {
            if (packet.kind() == BoostProtocol.Kind.FRAME
                    && session.phase != RemoteBoostServerPhase.Value.RESYNC) {
                appendEvidence(session, RemoteBoostOrderedEvidence.Entry.dropped());
            }
            return;
        }
        switch (packet.kind()) {
            case MODE -> acceptMode(session, packet);
            case FRAME -> acceptFrame(session, packet);
            default -> restrict(session, BoostProtocol.Reason.PROTOCOL);
        }
    }

    private void acceptMode(Session session, BoostProtocol.Packet packet) {
        if (packet.dimension() != session.player.dimension || packet.sequence() <= session.lastControlSequence) {
            restrict(session, BoostProtocol.Reason.PROTOCOL);
            return;
        }
        session.lastControlSequence = packet.sequence();
        if (!packet.flag()) {
            if (session.mode == BoostProtocol.Mode.ACTIVE) {
                session.mode = BoostProtocol.Mode.CLOSING;
                restoreNoGravity(session);
                session.settledFrame = false;
                session.closeWindow.reset();
                sendState(session, BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE);
            }
            return;
        }
        if (session.mode == BoostProtocol.Mode.CLOSING) {
            session.mode = BoostProtocol.Mode.ACTIVE;
            session.originalNoGravity = session.player.hasNoGravity();
            session.player.setNoGravity(true);
            session.noGravityOwned = true;
            sendState(session, BoostProtocol.Operation.NONE, continuityVelocity(session),
                    BoostProtocol.Reason.NONE);
            return;
        }
        if (session.mode != BoostProtocol.Mode.READY || packet.epoch() != session.epoch) {
            restrict(session, BoostProtocol.Reason.PROTOCOL);
            return;
        }
        RemoteBoostDomain.Verdict eligibility = activationVerdict(session);
        if (eligibility.reason() != BoostProtocol.Reason.NONE) {
            sendState(session, BoostProtocol.Operation.NONE, Vec3.ZERO, eligibility.reason());
            return;
        }
        session.epoch++;
        session.mode = BoostProtocol.Mode.ACTIVE;
        session.phase = RemoteBoostServerPhase.Value.ARMING;
        session.budget = null; // no retroactive time credit before the first current FRAME + CP pair
        session.budgetSequence = 0L;
        session.evidence.clear();
        session.lastFrameSequence = 0L;
        session.lastConsumedFrameSequence = 0L;
        session.lastFrameTick = serverTick;
        session.lastValidatedGear = BoostGearProfile.defaultGear();
        session.requireFrameBeforeMovement = false;
        session.incidentRestricted = false;
        session.restricted = RemoteBoostCorridor.isRestricted(session.player.posY);
        session.activationWorld = session.player.world;
        session.activationDimension = session.player.dimension;
        session.originalNoGravity = session.player.hasNoGravity();
        session.player.setNoGravity(true);
        session.noGravityOwned = true;
        sendState(session, BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE);
    }

    private void acceptFrame(Session session, BoostProtocol.Packet packet) {
        if (!session.hasCustody() || packet.epoch() != session.epoch
                || packet.dimension() != session.player.dimension || packet.sequence() <= session.lastFrameSequence
                || packet.gear() < 1 || packet.gear() > BoostGearProfile.count()) {
            restrict(session, BoostProtocol.Reason.PROTOCOL);
            return;
        }
        session.lastFrameSequence = packet.sequence();
        session.lastFrameTick = serverTick;
        session.lastValidatedGear = packet.gear();
        if (finite(packet.velocity())) session.latestVelocityHint = packet.velocity();
        if (session.phase == RemoteBoostServerPhase.Value.RESYNC) return;
        appendEvidence(session, RemoteBoostOrderedEvidence.Entry.frame(
                new RemoteBoostOrderedEvidence.Frame(packet.sequence(), serverTick, packet.gear(),
                        packet.flag())));
    }

    private RemoteBoostOrderedEvidence.Entry nextEvidence(Session session) {
        RemoteBoostOrderedEvidence.Entry evidence = session.evidence.consume();
        if (evidence == null || evidence.drop()) return evidence;
        if (RemoteBoostOrderedEvidence.expired(evidence, serverTick, 2L)) {
            restrict(session, BoostProtocol.Reason.TIMEOUT);
            return RemoteBoostOrderedEvidence.Entry.dropped();
        }
        if (session.requireFrameBeforeMovement) session.requireFrameBeforeMovement = false;
        return evidence;
    }

    /** Keeps at most eight ordered FRAME/DROP companions without letting a loss shift the FIFO. */
    private void appendEvidence(Session session, RemoteBoostOrderedEvidence.Entry next) {
        if (!session.evidence.append(next)) restrict(session, BoostProtocol.Reason.PROTOCOL);
    }

    private void seedBudget(Session session) {
        Vec3 actual = positionOf(session.player);
        // First seed is deliberately 70 m/s even outside the temporary band. The immediately
        // following corridor transition may raise only the future ceiling, never mint a windfall.
        session.budget = new BoostMovementBudget(Math.min(RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                grantCeiling()), serverTick, actual);
        session.phase = RemoteBoostServerPhase.Value.ACTIVE;
        session.restricted = RemoteBoostCorridor.isRestricted(actual.y());
        if (!session.restricted) session.budget.changeCeiling(serverTick, grantCeiling());
    }

    private void applyCorridor(Session session, double feetY) {
        if (session.budget == null || !Double.isFinite(feetY)) return;
        boolean nowRestricted = RemoteBoostCorridor.isRestricted(feetY);
        if (nowRestricted && !session.restricted) {
            session.restricted = true;
            if (session.budget.ceilingMps() > RemoteBoostCorridor.RESTRICTED_CEILING_MPS
                    && !session.budget.snapshot().decayActive()) {
                session.budget.beginDecay(serverTick, RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                        RemoteBoostCorridor.REENTRY_DECELERATION_MPS2);
            }
        } else if (!nowRestricted && session.restricted) {
            session.restricted = false;
            if (!session.incidentRestricted) {
                session.budget.changeCeiling(serverTick, grantCeiling());
            }
        }
    }

    /** An ordinary incident is a 70m/s restriction, never a G or epoch transition. */
    private void restrict(Session session, BoostProtocol.Reason reason) {
        if (session == null || !session.hasCustody()) return;
        session.movementRevision = Math.incrementExact(session.movementRevision);
        session.incidentRestricted = true;
        if (session.budget != null && session.budget.ceilingMps()
                > RemoteBoostCorridor.RESTRICTED_CEILING_MPS
                && !session.budget.snapshot().decayActive()) {
            session.budget.beginDecay(serverTick, RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                    RemoteBoostCorridor.REENTRY_DECELERATION_MPS2);
        }
        if (session.restrictionLogGate.tryAcquire(serverTick)) {
            UnigravMod.LOGGER.warn("ugboost restricted player={} epoch={} reason={} phase={} tick={} suppressed={}",
                    session.player.getUniqueID(), session.epoch, reason, session.phase, serverTick,
                    session.restrictionLogGate.takeSuppressed());
        }
        session.pendingStateReason = reason;
    }

    /** Death, disconnection and loss of Creative eligibility alone release physics authority. */
    private void releaseAuthority(Session session, BoostProtocol.Reason reason) {
        if (session == null) return;
        if (session.pendingMovement != null) {
            if (session.budget != null) session.budget.abort(session.pendingMovement.admission);
            session.pendingMovement = null;
        }
        restoreNoGravity(session);
        session.mode = BoostProtocol.Mode.READY;
        session.phase = RemoteBoostServerPhase.Value.ARMING;
        session.budget = null;
        session.evidence.clear();
        session.closeWindow.reset();
        session.queuedCorrection = null;
        session.epoch++;
        sendState(session, BoostProtocol.Operation.STOP, Vec3.ZERO, reason);
    }

    /** A voluntary, settled G-off is the sole non-terminal transition back to READY. */
    private void completeNormalClose(Session session) {
        restoreNoGravity(session);
        session.mode = BoostProtocol.Mode.READY;
        session.phase = RemoteBoostServerPhase.Value.ARMING;
        session.budget = null;
        session.evidence.clear();
        session.closeWindow.reset();
        session.epoch++;
        sendState(session, BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE);
    }

    private RemoteBoostDomain.Verdict activationVerdict(Session session) {
        if (!UnigravConfig.remoteCreativeBoost) {
            return new RemoteBoostDomain.Verdict(BoostProtocol.Reason.DISABLED,
                    RemoteBoostDomain.Phase.FLAGS, RemoteBoostDomain.Subreason.CONFIG_DISABLED);
        }
        if (!isDedicated(session.handler)) {
            return new RemoteBoostDomain.Verdict(BoostProtocol.Reason.UNSUPPORTED_STATE,
                    RemoteBoostDomain.Phase.FLAGS, RemoteBoostDomain.Subreason.NON_DEDICATED);
        }
        return RemoteBoostDomain.current(session.player);
    }

    private boolean currentSessionValid(Session session) {
        return session != null && session.player.connection == session.handler && !session.player.isDead
                && UnigravConfig.remoteCreativeBoost && isDedicated(session.handler)
                && RemoteBoostDomain.current(session.player).reason() == BoostProtocol.Reason.NONE
                && !lifecycleChanged(session);
    }

    private static boolean lifecycleChanged(Session session) {
        return session.handler.player != session.player || session.player.connection != session.handler
                || session.player.isDead || session.activationWorld != null
                && (session.player.world != session.activationWorld
                        || session.player.dimension != session.activationDimension);
    }

    private RemoteBoostLoadedPath.Result clipLoadedPath(EntityPlayerMP player, Vec3 start,
            Vec3 candidate) {
        AxisAlignedBB query = player.getEntityBoundingBox().grow(NATIVE_QUERY_MARGIN);
        RemoteBoostLoadedPath.XzBounds bounds = new RemoteBoostLoadedPath.XzBounds(query.minX,
                query.maxX, query.minZ, query.maxZ);
        return RemoteBoostLoadedPath.clip(start, candidate, bounds, key -> player.world.isBlockLoaded(
                new BlockPos((int) (key >> 32) << 4, 0, (int) key << 4), false));
    }

    private static boolean insideNativeEnvelope(PendingMovement pending) {
        Vec3 nativePosition = pending.nativePosition;
        double minX = Math.min(pending.start.x(), pending.candidate.x()) - NATIVE_ENVELOPE_MARGIN;
        double maxX = Math.max(pending.start.x(), pending.candidate.x()) + NATIVE_ENVELOPE_MARGIN;
        double minY = Math.min(pending.start.y(), pending.candidate.y()) - NATIVE_ENVELOPE_MARGIN;
        double maxY = Math.max(pending.start.y(), pending.candidate.y()) + NATIVE_ENVELOPE_MARGIN;
        double minZ = Math.min(pending.start.z(), pending.candidate.z()) - NATIVE_ENVELOPE_MARGIN;
        double maxZ = Math.max(pending.start.z(), pending.candidate.z()) + NATIVE_ENVELOPE_MARGIN;
        return nativePosition.x() >= minX && nativePosition.x() <= maxX
                && nativePosition.y() >= minY && nativePosition.y() <= maxY
                && nativePosition.z() >= minZ && nativePosition.z() <= maxZ;
    }

    private void sendOffer(Session session) {
        boolean available = UnigravConfig.remoteCreativeBoost && isDedicated(session.handler);
        outbound.accept(session.player, BoostProtocol.offer(session.nonce, session.player.dimension,
                grantCeiling(), available));
    }

    private void sendState(Session session, BoostProtocol.Operation operation, Vec3 velocity,
            BoostProtocol.Reason reason) {
        double ceiling = carrier(session);
        boolean restricted = clientRestricted(session);
        var packet = BoostProtocol.state(session.nonce, session.epoch,
                ++session.serial, session.player.dimension, session.mode, session.lastValidatedGear, ceiling,
                operation, clampVelocity(velocity, ceiling), reason, restricted);
        var identity = new PlayerPhysicsProtocol.OperationId(packet.nonce(), packet.epoch(), packet.sequence(), operation);
        // A failed operation cannot be certified by a later NONE heartbeat.
        boolean wasBroken = session.publicationBroken;
        if (operation != BoostProtocol.Operation.NONE) session.publicationBroken = true;
        outbound.accept(session.player, packet);
        if (operation != BoostProtocol.Operation.NONE) session.lastPublishedOperation = identity;
        session.publishedApplication = new PlayerPhysicsProtocol.ApplicationId(identity,
                Optional.ofNullable(session.lastPublishedOperation));
        session.publicationBroken = wasBroken && operation != BoostProtocol.Operation.REPLACE
                && operation != BoostProtocol.Operation.STOP;
        session.lastPublishedCarrier = ceiling;
        session.lastPublishedRestricted = restricted;
        session.lastStatePublishTick = serverTick;
    }

    /** At most one passive carrier publication per tick, with a bounded active heartbeat. */
    private boolean publishState(Session session, BoostProtocol.Reason reason, boolean force) {
        if (session == null || !session.hasCustody()) return false;
        double ceiling = carrier(session);
        boolean restricted = clientRestricted(session);
        boolean changed = Double.doubleToLongBits(ceiling)
                != Double.doubleToLongBits(session.lastPublishedCarrier)
                || restricted != session.lastPublishedRestricted;
        boolean heartbeat = serverTick - session.lastStatePublishTick >= STATE_HEARTBEAT_TICKS;
        if ((force && session.lastRestrictionNoticeTick != serverTick) || changed || heartbeat) {
            sendState(session, BoostProtocol.Operation.NONE, continuityVelocity(session), reason);
            if (force) session.lastRestrictionNoticeTick = serverTick;
            return true;
        }
        return false;
    }

    private Vec3 continuityVelocity(Session session) {
        return finite(session.latestVelocityHint) ? session.latestVelocityHint : Vec3.ZERO;
    }

    private static Vec3 clampVelocity(Vec3 velocity, double maximum) {
        if (!finite(velocity) || maximum <= 0.0D) return Vec3.ZERO;
        double norm = velocity.norm();
        return norm > maximum && norm > 0.0D ? velocity.scale(maximum / norm) : velocity;
    }

    private Session sessionFor(EntityPlayerMP player) {
        Session session = sessions.get(player.connection);
        return session != null && session.player == player ? session : null;
    }

    private static boolean positionBearing(CPacketPlayer packet) {
        return packet instanceof CPacketPlayer.Position || packet instanceof CPacketPlayer.PositionRotation;
    }

    private static Vec3 packetPosition(CPacketPlayer packet, Vec3 fallback) {
        return new Vec3(packet.getX(fallback.x()), packet.getY(fallback.y()), packet.getZ(fallback.z()));
    }

    private static boolean isDedicated(NetHandlerPlayServer handler) {
        MinecraftServer server = handler.player.getServer();
        return server != null && server.isDedicatedServer();
    }

    private static Vec3 positionOf(EntityPlayerMP player) {
        return new Vec3(player.posX, player.posY, player.posZ);
    }

    private static CPacketPlayer quarantine(CPacketPlayer packet, EntityPlayerMP player) {
        return RemoteBoostNativePacketAdapter.replacePosition(packet, positionOf(player));
    }

    private static boolean sameWorld(Session session, PendingMovement pending) {
        return session.player.world == pending.world && session.player.dimension == pending.dimension;
    }

    private static boolean finite(Vec3 value) {
        return BoostProtocol.finite(value);
    }

    private static boolean same(Vec3 first, Vec3 second) {
        return first.sub(second).norm() <= BoostMovementBudget.NATIVE_POSITION_TOLERANCE;
    }

    private static double grantCeiling() {
        double configured = Math.min(495.0D, Math.max(0.0D, UnigravConfig.remoteBoostMaximumSpeed));
        if (UnigravConfig.maxEntitySpeed > 0.0D) configured = Math.min(configured,
                UnigravConfig.maxEntitySpeed);
        return Math.min(BoostGearProfile.maximumSpeedMps(), configured);
    }

    private static double carrier(Session session) {
        return session.budget != null ? session.budget.ceilingMps() : Math.min(
                RemoteBoostCorridor.RESTRICTED_CEILING_MPS, grantCeiling());
    }

    /** ADD follows the present policy, which remains 70 inside the height band during decay. */
    private static double policyVelocityCeiling(Session session) {
        return RemoteBoostCorridor.propulsionTarget(carrier(session), carrier(session),
                clientRestricted(session));
    }

    private static boolean clientRestricted(Session session) {
        return session.restricted || session.incidentRestricted;
    }

    private static void restoreNoGravity(Session session) {
        if (session.noGravityOwned) {
            session.player.setNoGravity(session.originalNoGravity);
            session.noGravityOwned = false;
        }
    }

    private static final class Session {
        private final NetHandlerPlayServer handler;
        private final NetworkManager manager;
        private final EntityPlayerMP player;
        private final long nonce = ThreadLocalRandom.current().nextLong();
        private final RemoteBoostBoundedDispatcher<BoostProtocol.Packet> dispatcher;
        private final RemoteBoostOrderedEvidence evidence = new RemoteBoostOrderedEvidence(
                MAX_INBOUND_PACKETS_PER_CONNECTION);
        private final RemoteBoostIncidentLogGate restrictionLogGate = new RemoteBoostIncidentLogGate(20);
        private boolean offerSent;
        private boolean dispatchOverflow;
        private boolean negotiated;
        private boolean physicsRetired;
        private boolean publicationBroken;
        private PlayerPhysicsProtocol.ApplicationId publishedApplication;
        private PlayerPhysicsProtocol.OperationId lastPublishedOperation;
        private final ArrayDeque<CarrierSample> carriers = new ArrayDeque<>();
        private long epoch;
        private long serial;
        private long lastFrameSequence;
        private long lastConsumedFrameSequence;
        private long lastControlSequence;
        private long budgetSequence;
        private long movementRevision;
        private long lastFrameTick;
        private int lastValidatedGear = BoostGearProfile.defaultGear();
        private BoostProtocol.Mode mode = BoostProtocol.Mode.UNAVAILABLE;
        private RemoteBoostServerPhase.Value phase = RemoteBoostServerPhase.Value.ARMING;
        private BoostMovementBudget budget;
        private PendingMovement pendingMovement;
        private boolean originalNoGravity;
        private boolean noGravityOwned;
        private boolean settledFrame;
        private final RemoteBoostCloseWindow closeWindow = new RemoteBoostCloseWindow(5, 0.0025D);
        private boolean restricted;
        private boolean incidentRestricted;
        private boolean requireFrameBeforeMovement;
        private double lastPublishedCarrier = Double.NaN;
        private boolean lastPublishedRestricted;
        private long lastStatePublishTick = Long.MIN_VALUE;
        private long lastRestrictionNoticeTick = Long.MIN_VALUE;
        private BoostProtocol.Reason pendingStateReason = BoostProtocol.Reason.NONE;
        private int expectedTeleportId = Integer.MIN_VALUE;
        private Vec3 resyncTarget;
        private Vec3 queuedCorrection;
        private Vec3 latestVelocityHint = Vec3.ZERO;
        private World activationWorld;
        private int activationDimension;

        private Session(NetHandlerPlayServer handler, NetworkManager manager,
                RemoteBoostBoundedDispatcher.Scheduler scheduler) {
            this.handler = handler;
            this.manager = manager;
            this.player = handler.player;
            this.dispatcher = new RemoteBoostBoundedDispatcher<>(MAX_INBOUND_PACKETS_PER_CONNECTION,
                    scheduler);
        }

        private boolean hasCustody() {
            return !physicsRetired && (mode == BoostProtocol.Mode.ACTIVE || mode == BoostProtocol.Mode.CLOSING);
        }
    }

    private static final class PendingMovement {
        private final BoostMovementBudget.Admission admission;
        private final Vec3 firstGood;
        private final Vec3 start;
        private final Vec3 rawDestination;
        private final Vec3 candidate;
        private final boolean paired;
        private final boolean fullyLoaded;
        private final World world;
        private final int dimension;
        private final BoostMovementBudget budget;
        private final long epoch;
        private final long movementRevision;
        private final long serverTick;
        private final long worldTick;
        private Vec3 nativePosition;
        private boolean successMarked;
        private boolean observationDirty;

        private PendingMovement(BoostMovementBudget.Admission admission, Vec3 firstGood,
                Vec3 rawDestination, Vec3 candidate, boolean paired, boolean fullyLoaded,
                Session session, long serverTick) {
            this.admission = admission;
            this.firstGood = firstGood;
            this.start = admission.start();
            this.rawDestination = rawDestination;
            this.candidate = candidate;
            this.paired = paired;
            this.fullyLoaded = fullyLoaded;
            this.world = session.player.world;
            this.dimension = session.player.dimension;
            this.budget = session.budget;
            this.epoch = session.epoch;
            this.movementRevision = session.movementRevision;
            this.serverTick = serverTick;
            this.worldTick = world.getTotalWorldTime();
        }
    }


    /** Netty-safe per-manager capability state; it contains no world/player references. */
    private static final class Capability {
        private boolean registered;
        private boolean connectionPending;
        private boolean unregistrationPending;
        private boolean disconnectPending;
    }
}
