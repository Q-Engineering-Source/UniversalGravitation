package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.BiConsumer;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
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
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.floor.MagneticResponseRules;

/**
 * Server-owned physics lifetime; negotiation itself never grants movement authority.
 */
public final class PlayerPhysicsServer {
    /** Dormant until the complete runtime is installed; shared only by common-side production hooks. */
    public static final PlayerPhysicsServer INSTANCE = new PlayerPhysicsServer(GIntentServer.INSTANCE, PlayerPhysicsNetwork::sendTo);
    private static final PlayerPhysicsProtocol.Controls IDLE = new PlayerPhysicsProtocol.Controls(
            new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5);
    private static final long INPUT_TIMEOUT_TICKS = 20;
    private final GIntentServer intent;
    private final BiConsumer<EntityPlayerMP, PlayerPhysicsProtocol.Packet> outbound;
    private final Map<NetworkManager, Capability> capabilities = new IdentityHashMap<>();
    private final Map<NetworkManager, Session> sessions = new IdentityHashMap<>();
    private long serverTick;
    private long worldEpoch;
    private boolean installed;
    private RemoteTransfer preparingRemote;
    private PlayerPhysicsStep movement;

    public PlayerPhysicsServer(GIntentServer intent, BiConsumer<EntityPlayerMP, PlayerPhysicsProtocol.Packet> outbound) {
        this.intent = Objects.requireNonNull(intent, "intent");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    public synchronized Optional<PlayerPhysicalSnapshot> accepted(NetHandlerPlayServer handler) {
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics read off-thread");
        Session session = sessions.get(handler.netManager);
        return session != null && session.handler == handler && session.player == handler.player
                && current(session) && session.owner != null
                ? Optional.of(session.owner.snapshot) : Optional.empty();
    }

    /** Protocol custody includes native delegation; use drivesMotion for competing movement writes. */
    public boolean owns(EntityPlayerMP player) {
        return player.connection != null && player.connection.player == player && accepted(player.connection).isPresent();
    }

    public synchronized boolean drivesMotion(EntityPlayerMP player) {
        return owns(player) && !sessions.get(player.connection.netManager).owner.nativeDelegated
                && !PlayerKinematics.requiresNative(player);
    }

    /** Observe the collision-checked native pose operation, never infer a resize from an arbitrary later write. */
    public synchronized void runNativePose(EntityPlayerMP player, Runnable original) {
        Objects.requireNonNull(original, "original");
        if (!drivesMotion(player)) { original.run(); return; }
        Session session = sessions.get(player.connection.netManager);
        Owned owner = session.owner;
        var previous = owner.snapshot;
        boolean anchored = !owner.teleportPending && matchesAcceptedPlacement(session);
        long correction = owner.travel.correctionRevision();
        original.run();
        // Callback relocation/ownership changes are handled by their own lifecycle, not this pose observer.
        if (!anchored || !current(session) || session.owner != owner || owner.snapshot != previous
                || owner.teleportPending || owner.travel.correctionRevision() != correction
                || PlayerKinematics.requiresNative(player)) return;
        var actual = WorldCollisionCandidates.ActorGeometry.read(player);
        if (!actual.position().equals(previous.placement().transport())) return;
        var shape = (PlayerBodyShape.Cardinal) previous.placement().shape();
        if (shape.frame() != CardinalFrame.Y_POS) return;
        var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), player.getEyeHeight());
        if (dimensions.equals(shape.dimensions()) && actual.bounds().equals(previous.placement().bounds())) return;
        var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                previous.placement().attitude(), new PlayerBodyShape.Cardinal(shape.frame(), dimensions));
        // The feet/transport anchor did not move: no movement credit is consumed, refunded or rebased.
        owner.snapshot = new PlayerPhysicalSnapshot(previous.scope(), Math.incrementExact(previous.revision()),
                placement, previous.worldVelocity(), previous.contacts());
    }

    /** Native setPlayerLocation already moved the actor and sent its correction before this hook. */
    public synchronized void observeNativeTeleport(NetHandlerPlayServer handler, int teleportId) {
        if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics correction off-thread");
        Session session = sessions.get(handler.netManager);
        if (session == null || !current(session)) return;
        if (teleportId < 0) throw new IllegalArgumentException("Negative native teleport ID");
        session.pending = null;
        if (session.owner == null) {
            session.lastOfferTick = serverTick - 20;
            return;
        }
        session.owner.nativeTeleportId = OptionalInt.of(teleportId);
        session.owner.teleportPending = true;
        session.owner.travel.invalidate();
    }

    /** Re-anchor only after vanilla consumed this exact confirmation. No new owner or budget is created. */
    public void confirmNativeTeleport(NetHandlerPlayServer handler, int teleportId, boolean pendingCleared) {
        var states = new ArrayList<Offer>();
        synchronized (this) {
            if (!handler.player.getServerWorld().isCallingFromMinecraftThread())
                throw new IllegalStateException("Physics confirmation off-thread");
            Session session = sessions.get(handler.netManager);
            if (session == null || !current(session) || session.owner == null) return;
            Owned owner = session.owner;
            if (!pendingCleared || !owner.teleportPending || owner.nativeTeleportId.orElse(-1) != teleportId) return;
            if (owner.nativeDelegated) {
                owner.budget.rebase(new Vec3(session.player.posX, session.player.posY, session.player.posZ), owner.budgetTick);
                owner.teleportPending = false;
                owner.lastWorldTick = session.world.getTotalWorldTime();
                publishNative(session, states);
            } else {
                var previous = owner.snapshot;
                var shape = (PlayerBodyShape.Cardinal) previous.placement().shape();
                if (shape.frame() != CardinalFrame.Y_POS)
                    throw new IllegalStateException("Native correction requires a Y+ physical frame");
                var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
                var dimensions = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), session.player.getEyeHeight());
                var placement = PlayerBodyPlacement.fromCardinalBounds(actual.bounds(), actual.position(),
                        previous.placement().attitude(), new PlayerBodyShape.Cardinal(shape.frame(), dimensions));
                var corrected = new PlayerPhysicalSnapshot(previous.scope(), Math.incrementExact(previous.revision()),
                        placement, previous.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(
                        session.player.onGround, session.player.collidedHorizontally, session.player.collidedVertically,
                        session.player.fallDistance)));
                owner.budget.rebase(new Vec3(actual.position().x(), actual.position().y(), actual.position().z()), owner.budgetTick);
                owner.snapshot = corrected;
                owner.teleportPending = false;
                owner.lastWorldTick = session.world.getTotalWorldTime();
                owner.lastFailure = null;
                publishAccepted(session, states);
            }
        }
        for (Offer state : states) outbound.accept(state.player(), state.packet());
    }

    /** Queues J/m for the next owner step; callbacks cannot overwrite an in-flight movement snapshot. */
    public synchronized boolean addImpulse(Entity entity, Vec3 impulseNewtonSeconds) {
        if (!(entity instanceof EntityPlayerMP player) || !drivesMotion(player)) return false;
        Objects.requireNonNull(impulseNewtonSeconds, "impulse");
        if (!finite(impulseNewtonSeconds)) throw new IllegalArgumentException("Non-finite impulse");
        var session = sessions.get(player.connection.netManager);
        var body = Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder").bodyPhysicsOf(player);
        double mass = body == null ? BodyPhysics.estimate(player).massKg() : body.massKg();
        if (!Double.isFinite(mass) || mass <= 0) throw new IllegalArgumentException("Invalid impulse mass");
        Vec3 pending = session.owner.pendingImpulse.add(impulseNewtonSeconds.scale(1 / mass));
        if (!finite(pending)) throw new IllegalArgumentException("Impulse accumulation overflow");
        session.owner.pendingImpulse = pending;
        return true;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(Math.hypot(Math.hypot(vector.x(), vector.y()), vector.z()));
    }

    /** Native velocity deltas are blocks/tick, not force or impulse in newton-seconds. */
    public synchronized boolean captureNativeVelocityDelta(Entity entity, Vec3 blocksPerTick) {
        if (!(entity instanceof EntityPlayerMP player) || !drivesMotion(player)) return false;
        Objects.requireNonNull(blocksPerTick, "native velocity delta");
        if (!finite(blocksPerTick)) throw new IllegalArgumentException("Non-finite native velocity delta");
        var owner = sessions.get(player.connection.netManager).owner;
        Vec3 pending = owner.pendingImpulse.add(blocksPerTick.scale(GravityEntityBinder.TICKS_PER_SECOND));
        if (!finite(pending)) throw new IllegalArgumentException("Native velocity accumulation overflow");
        owner.pendingImpulse = pending;
        return true;
    }

    /**
     * Router-only: both legacy finishers and all packet callbacks have already returned normally.
     */
    void afterLegacyCompletion(NetHandlerPlayServer handler, RemoteBoostServer remote,
                               RemoteBoostServer.CompletedMovement completion) {
        PlayerPhysicsProtocol.Packet owned;
        synchronized (this) {
            Session session = sessions.get(handler.netManager);
            if (session == null || !current(session) || (session.owner != null && !session.owner.nativeDelegated) || session.pending == null
                    || serverTick - session.pendingTick > 2) return;
            boolean resuming = session.owner != null;
            if (resuming && (!session.owner.resumeOffered || session.owner.teleportPending)) return;
            Optional<GIntentObservation> source = intent.observe(session.player);
            if (source.isEmpty() || !source.orElseThrow().equals(session.source)) return;
            boolean remoteSource = source.orElseThrow().source() == GIntentObservation.Source.DEDICATED_MODE
                    || session.pending.application().isPresent();
            if (!remoteSource && remote.hasMovementCustody(handler)) return;
            EntityPlayerMP actor = session.player;
            if (PlayerKinematics.requiresNative(actor) || actor.velocityChanged) return;
            // Keep the native observation/force-mutation fence through installation.
            synchronized (NativePlayerMovement.INSTANCE) {
                var centers = NativePlayerMovement.INSTANCE.acceptedCenters(handler);
                var receipt = NativePlayerMovement.INSTANCE.current(handler);
                if (centers.isEmpty() || receipt.isEmpty()) return;
                var window = centers.orElseThrow();
                var lastReceipt = receipt.orElseThrow();
                if (!window.scope().equals(lastReceipt.scope()) || window.delegateRevision() != lastReceipt.delegateRevision()
                        || window.samples().size() < 2) return;
                var samples = window.samples();
                var before = samples.get(samples.size() - 2);
                var last = samples.getLast();
                long ticks = last.serverTick() - before.serverTick();
                if (ticks < 1 || ticks > 2 || last.serverTick() != session.world.getTotalWorldTime()) return;
                double vx = (last.center().x() - before.center().x()) * 20.0 / ticks;
                double vy = (last.center().y() - before.center().y()) * 20.0 / ticks;
                double vz = (last.center().z() - before.center().z()) * 20.0 / ticks;
                if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)
                        || !Double.isFinite(Math.hypot(Math.hypot(vx, vy), vz))) return;
                var snapshot = new PlayerPhysicalSnapshot(session.scope,
                        resuming ? Math.incrementExact(session.owner.snapshot.revision()) : 0,
                        lastReceipt.placement(), new Vec3d(vx, vy, vz),
                        Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                                actor.collidedVertically, actor.fallDistance)));
                var application = session.pending.application();
                if (resuming) {
                    if (remoteSource || Math.hypot(Math.hypot(vx, vy), vz) > session.owner.budget.ceilingMps()) return;
                    Owned retained = session.owner;
                    var position = snapshot.placement().transport();
                    retained.budget.rebase(new Vec3(position.x(), position.y(), position.z()), retained.budgetTick);
                    retained.snapshot = snapshot;
                    retained.nativeDelegated = false;
                    retained.resumeOffered = false;
                    retained.nativeTeleportId = OptionalInt.empty();
                    retained.lastWorldTick = session.world.getTotalWorldTime();
                    retained.lastStateTick = serverTick;
                    retained.controls = IDLE;
                    retained.inputWorldTick = Long.MIN_VALUE / 2;
                } else if (remoteSource) {
                    var negotiated = intent.observeNegotiated(actor);
                    if (application.isEmpty() || negotiated.isEmpty()
                            || negotiated.orElseThrow().active() != source.orElseThrow().active()) return;
                    if (preparingRemote != null) throw new IllegalStateException("Nested physical handoff");
                    preparingRemote = new RemoteTransfer(remote, session, snapshot, negotiated.orElseThrow());
                    try {
                        if (!remote.transferCompletedMovement(handler, completion, application.orElseThrow(),
                                before.serverTick(), this)) return;
                    } finally {
                        preparingRemote = null;
                    }
                } else {
                    double limit = ordinaryCeiling();
                    if (Math.hypot(Math.hypot(vx, vy), vz) > limit) return;
                    Vec3d transport = snapshot.placement().transport();
                    var budget = new BoostMovementBudget(limit, serverTick, new Vec3(transport.x(), transport.y(), transport.z()));
                    session.owner = new Owned(snapshot, budget, serverTick, session.world.getTotalWorldTime(),
                            nativeNoGravity(actor, actor.hasNoGravity()));
                }
                session.pending = null;
                owned = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, session.scope,
                        Math.incrementExact(session.outgoingSequence), session.proposal, application,
                        Optional.of(snapshot), Optional.empty());
                session.outgoingSequence = owned.sequence();
                NativePlayerMovement.INSTANCE.invalidate(handler);
            }
        }
        // External effects are after authority installation. Failure must not reactivate legacy or reseed credit.
        handler.player.setNoGravity(true);
        outbound.accept(handler.player, owned);
    }

    /** Only callable inside the router's prepared transaction; never returns a budget to a caller. */
    public synchronized boolean installRemoteCompletion(RemoteBoostServer remote, NetHandlerPlayServer handler,
                                                        Optional<BoostMovementBudget> transferredBudget, double carriedCeiling,
                                                        boolean originalNoGravity) {
        RemoteTransfer transfer = preparingRemote;
        if (transfer == null || transfer.remote() != remote || transfer.session().handler != handler) return false;
        Session session = transfer.session();
        Vec3d velocity = transfer.snapshot().worldVelocity();
        Vec3d transport = transfer.snapshot().placement().transport();
        double ceiling = transferredBudget.isPresent() ? carriedCeiling : ordinaryCeiling();
        if (!current(session) || session.owner != null
                || Math.hypot(Math.hypot(velocity.x(), velocity.y()), velocity.z()) > ceiling) return false;
        BoostMovementBudget budget = transferredBudget.orElseGet(() -> new BoostMovementBudget(ceiling,
                serverTick, new Vec3(transport.x(), transport.y(), transport.z())));
        if (budget.snapshot().pending()
                || !budget.position().equals(new Vec3(transport.x(), transport.y(), transport.z()))) return false;
        session.owner = new Owned(transfer.snapshot(), budget, serverTick, session.world.getTotalWorldTime(),
                nativeNoGravity(session.player, originalNoGravity));
        session.owner.boostCarrier = transferredBudget.isPresent();
        session.source = transfer.negotiated();
        return true;
    }

    private record RemoteTransfer(RemoteBoostServer remote, Session session, PlayerPhysicalSnapshot snapshot,
                                  GIntentObservation negotiated) { }

    private static double ordinaryCeiling() {
        double configured = UnigravConfig.maxEntitySpeed;
        if (!Double.isFinite(configured) || configured < 0)
            throw new IllegalArgumentException("Invalid max_entity_speed");
        return configured == 0 ? 500 : Math.min(configured, 500);
    }

    private static boolean nativeNoGravity(EntityPlayerMP player, boolean observed) {
        return UnigravMod.entityBinder == null ? observed
                : UnigravMod.entityBinder.nativeNoGravityBeforeReplacement(player, observed);
    }

    /**
     * Do not install until the complete motion owner and retirement hooks are available.
     */
    public synchronized void install() {
        if (!installed) {
            MinecraftForge.EVENT_BUS.register(this);
            installed = true;
        }
    }

    @SubscribeEvent
    public synchronized void onConnection(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).connected = true;
    }

    @SubscribeEvent
    public synchronized void onRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if (event.getSide() != Side.SERVER || !event.getRegistrations().contains(PlayerPhysicsProtocol.CHANNEL)) return;
        var capability = capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability());
        if ("REGISTER".equals(event.getOperation())) capability.registered = true;
        else if ("UNREGISTER".equals(event.getOperation())) capability.retired = true;
    }

    @SubscribeEvent
    public synchronized void onDisconnect(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        capabilities.computeIfAbsent(event.getManager(), ignored -> new Capability()).retired = true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var offers = new ArrayList<Offer>();
        synchronized (this) {
            serverTick = Math.incrementExact(serverTick);
            for (NetworkManager manager : capabilities.keySet().toArray(NetworkManager[]::new)) {
                var capability = capabilities.get(manager);
                if (capability.retired || !manager.isChannelOpen()) {
                    retire(manager);
                    capabilities.remove(manager);
                    continue;
                }
                if (!channelCurrent(manager) || !(manager.getNetHandler() instanceof NetHandlerPlayServer handler))
                    continue;
                if (!handler.player.getServerWorld().isCallingFromMinecraftThread()) {
                    throw new IllegalStateException("Physics lifecycle off server thread");
                }
                Session session = sessions.get(manager);
                if (session != null && !current(session)) {
                    retire(manager);
                    session = null;
                }
                if (!actorCurrent(handler, manager)) continue;
                if (session != null && session.owner != null) {
                    if (handleNativeDelegation(session, offers)) continue;
                    advanceOwned(session, offers);
                    if (!session.owner.nativeDelegated && !session.owner.teleportPending
                            && serverTick - session.owner.lastStateTick >= 20 && matchesAcceptedPlacement(session))
                        publishAccepted(session, offers);
                    continue;
                }
                if (PlayerKinematics.requiresNative(handler.player)) continue;
                Optional<GIntentObservation> source = intent.observe(handler.player);
                if (source.isEmpty()) {
                    if (session != null) {
                        session.pending = null;
                        session.source = null;
                    }
                    continue;
                }
                if (session == null) {
                    worldEpoch = Math.incrementExact(worldEpoch);
                    session = new Session(handler, worldEpoch);
                    sessions.put(manager, session);
                }
                boolean sourceChanged = !source.orElseThrow().equals(session.source);
                if (sourceChanged) {
                    session.source = source.orElseThrow();
                    session.pending = null;
                }
                if (session.pending != null && serverTick - session.pendingTick > 2) session.pending = null;
                if (sourceChanged || session.proposal == 0
                        || session.pending == null && serverTick - session.lastOfferTick >= 20) {
                    session.proposal = Math.incrementExact(session.proposal);
                    session.lastOfferTick = serverTick;
                    var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, session.scope,
                            Math.incrementExact(session.outgoingSequence), session.proposal, Optional.empty(), Optional.empty(), Optional.empty());
                    session.outgoingSequence = packet.sequence();
                    offers.add(new Offer(session.player, packet));
                }
            }
        }
        // No external network callback may see a half-mutated registry or hold its monitor.
        for (Offer offer : offers) outbound.accept(offer.player(), offer.packet());
    }

    /** Preserve the owner/budget while vanilla handles a kinematic state, then negotiate a fresh cutover. */
    private boolean handleNativeDelegation(Session session, ArrayList<Offer> states) {
        Owned owner = session.owner;
        boolean nativeState = PlayerKinematics.requiresNative(session.player);
        if (!nativeState && !owner.nativeDelegated) return false;
        if (nativeState && (!owner.nativeDelegated || owner.resumeOffered)) {
            owner.delegationProposal = session.proposal;
            owner.nativeDelegated = true;
            owner.resumeOffered = false;
            session.pending = null;
            owner.controls = IDLE;
            restoreNativeGravity(session);
            NativePlayerMovement.INSTANCE.invalidate(session.handler);
            publishNative(session, states);
        } else if (serverTick - owner.lastStateTick >= 20) publishNative(session, states);
        long worldTick = session.world.getTotalWorldTime();
        if (worldTick > owner.lastWorldTick) {
            owner.lastWorldTick = worldTick;
            owner.budgetTick = Math.incrementExact(owner.budgetTick);
            owner.budget.advance(owner.budgetTick);
        }
        if (nativeState || owner.teleportPending) return true;
        var source = intent.observe(session.player);
        if (source.isEmpty()) { session.pending = null; return true; }
        boolean sourceChanged = !source.orElseThrow().equals(session.source);
        if (session.pending != null && serverTick - session.pendingTick > 2) session.pending = null;
        if (!owner.resumeOffered || sourceChanged || session.pending == null && serverTick - session.lastOfferTick >= 20) {
            session.pending = null;
            session.source = source.orElseThrow();
            session.proposal = Math.incrementExact(session.proposal);
            session.lastOfferTick = serverTick;
            owner.resumeOffered = true;
            session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
            states.add(new Offer(session.player, new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,
                    session.scope, session.outgoingSequence, session.proposal, Optional.empty(), Optional.empty(), Optional.empty())));
        }
        return true;
    }

    private void publishNative(Session session, ArrayList<Offer> states) {
        session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
        session.owner.lastStateTick = serverTick;
        states.add(new Offer(session.player, new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE,
                session.scope, session.outgoingSequence, session.owner.delegationProposal,
                Optional.empty(), Optional.empty(), Optional.empty())));
    }

    private void advanceOwned(Session session, ArrayList<Offer> outboundStates) {
        Owned owner = session.owner;
        if (owner.teleportPending) return;
        long worldTick = session.world.getTotalWorldTime();
        if (serverTick <= owner.adoptedAtTick || worldTick <= owner.lastWorldTick) return;
        owner.lastWorldTick = worldTick;
        owner.budgetTick = Math.incrementExact(owner.budgetTick);
        if (!session.player.hasNoGravity()) {
            // A removed REPLACE binding (or an explicit native re-enable) must not strand the sampler.
            owner.travel.nativeNoGravity = false;
            session.player.setNoGravity(true);
        }
        boolean impulseApplied = applyPendingImpulse(session);
        if (movement == null) movement = new PlayerPhysicsStep(new PlayerEnvironmentSampler(
                Objects.requireNonNull(UnigravMod.entityBinder, "player physics binder"), MagneticResponseRules.builtIn(), 0.5));
        var source = intent.observe(session.player);
        owner.boostCarrier |= source.map(GIntentObservation::active).orElse(false)
                && session.player.interactionManager.isCreative();
        var before = owner.snapshot;
        var result = movement.step(session.player, before, source.map(GIntentObservation::active),
                worldTick - owner.inputWorldTick <= INPUT_TIMEOUT_TICKS ? owner.controls : IDLE,
                owner.budget, owner.budgetTick, owner.boostCarrier, owner.travel);
        if (result instanceof PlayerPhysicsStep.CommittedFailure failure) {
            if (current(session) && session.owner == owner && owner.snapshot == before && !owner.teleportPending
                    && !PlayerKinematics.requiresNative(session.player)) {
                owner.snapshot = failure.snapshot();
            }
            // Preserve the original cause and fail-fast behavior, but not a fictional pre-move state.
            // Do not replay the failed tail or publish a normal-success STATE from this failed tick.
            throw failure.cause();
        }
        if (!current(session) || session.owner != owner) return;
        if (PlayerKinematics.requiresNative(session.player)) { handleNativeDelegation(session, outboundStates); return; }
        // Native corrections may be initiated by a world callback during this step.
        if (owner.teleportPending || owner.snapshot != before) return;
        if (result instanceof PlayerPhysicsStep.CommittedDeferred deferred) {
            owner.snapshot = deferred.snapshot();
            if (!deferred.tail().reason().equals(owner.lastFailure))
                UnigravMod.LOGGER.warn("ugmotion1 committed move with deferred tail: {}",
                        deferred.tail().reason(), deferred.tail().cause().orElse(null));
            owner.lastFailure = deferred.tail().reason();
            publishAccepted(session, outboundStates);
            return;
        }
        if (result instanceof PlayerPhysicsStep.Unavailable failure) {
            if (!failure.reason().equals(owner.lastFailure)) {
                UnigravMod.LOGGER.warn("ugmotion1 step deferred: {}", failure.reason(), failure.cause().orElse(null));
            }
            owner.lastFailure = failure.reason();
            if (impulseApplied && matchesAcceptedPlacement(session)) publishAccepted(session, outboundStates);
            return;
        }
        owner.snapshot = ((PlayerPhysicsStep.Applied) result).snapshot();
        owner.lastFailure = null;
        publishAccepted(session, outboundStates);
    }

    private boolean applyPendingImpulse(Session session) {
        Owned owner = session.owner;
        Vec3 pending = owner.pendingImpulse;
        if (pending.x() == 0 && pending.y() == 0 && pending.z() == 0 || !matchesAcceptedPlacement(session)) return false;
        var previous = owner.snapshot;
        var velocity = previous.worldVelocity();
        Vec3 next = new Vec3(velocity.x(), velocity.y(), velocity.z()).add(pending);
        double speed = Math.hypot(Math.hypot(next.x(), next.y()), next.z());
        if (!Double.isFinite(speed)) throw new IllegalArgumentException("Impulse velocity overflow");
        double ceiling = owner.budget.ceilingMps();
        if (speed > ceiling) next = next.scale(ceiling / speed);
        var snapshot = new PlayerPhysicalSnapshot(previous.scope(), Math.incrementExact(previous.revision()),
                previous.placement(), new Vec3d(next.x(), next.y(), next.z()), previous.contacts());
        // No movement admission, budget advance, position write or credit grant occurs here.
        owner.snapshot = snapshot;
        owner.pendingImpulse = Vec3.ZERO;
        session.player.motionX = next.x() * .05;
        session.player.motionY = next.y() * .05;
        session.player.motionZ = next.z() * .05;
        return true;
    }

    private static boolean matchesAcceptedPlacement(Session session) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(session.player);
        var accepted = session.owner.snapshot.placement();
        return actual.position().equals(accepted.transport()) && actual.bounds().equals(accepted.bounds())
                && actual.width() == accepted.shape().dimensions().width()
                && actual.height() == accepted.shape().dimensions().height();
    }

    /** Repeat the immutable accepted state, not a new movement, credit grant or physical revision. */
    private void publishAccepted(Session session, ArrayList<Offer> outboundStates) {
        Owned owner = session.owner;
        var snapshot = owner.snapshot;
        // Native CP no longer reaches serverUpdateMovingPlayer for this owner. Preserve the existing
        // subscription lifecycle, but never enroll an actor that native login has not registered.
        var player = session.player;
        var entry = session.world.getPlayerChunkMap().getEntry((int) player.managedPosX >> 4, (int) player.managedPosZ >> 4);
        if (entry != null && entry.containsPlayer(player)) player.getServer().getPlayerList().serverUpdateMovingPlayer(player);
        if (!current(session) || session.owner != owner || owner.snapshot != snapshot
                || owner.teleportPending || PlayerKinematics.requiresNative(player)) return;
        session.outgoingSequence = Math.incrementExact(session.outgoingSequence);
        session.owner.lastStateTick = serverTick;
        outboundStates.add(new Offer(session.player, new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,
                session.scope, session.outgoingSequence, session.proposal, Optional.empty(),
                Optional.of(session.owner.snapshot), Optional.empty(), session.owner.nativeTeleportId)));
    }

    /**
     * Netty ingress reserves bounded work; world and source checks happen only after the hop.
     */
    public void receive(PlayerPhysicsProtocol.Packet packet, NetHandlerPlayServer handler) {
        Session session;
        RemoteBoostBoundedDispatcher.Reservation<PlayerPhysicsProtocol.Packet> reservation;
        boolean overflow = false;
        synchronized (this) {
            if (packet == null || handler == null || packet.clientbound()) return;
            session = sessions.get(handler.netManager);
            if (session == null || session.handler != handler || !channelCurrent(handler.netManager)) return;
            reservation = session.dispatcher.reserve(packet, value -> dispatch(session, value));
            if (reservation == null && !session.overflowReported) {
                session.overflowReported = true;
                overflow = true;
            }
        }
        if (overflow)
            UnigravMod.LOGGER.warn("ugmotion1 server queue full; excess input dropped without granting movement");
        if (reservation != null) session.dispatcher.submit(reservation);
    }

    private synchronized void dispatch(Session session, PlayerPhysicsProtocol.Packet packet) {
        if (!session.world.isCallingFromMinecraftThread())
            throw new IllegalStateException("Physics dispatch off server thread");
        if (!current(session)
                || !session.scope.equals(packet.scope()) || packet.offerSequence() != session.proposal
                || packet.sequence() <= session.lastIncomingSequence || session.source == null) return;
        if (session.owner != null && !session.owner.nativeDelegated) {
            if (packet.kind() != PlayerPhysicsProtocol.Kind.INPUT) return;
            session.lastIncomingSequence = packet.sequence();
            session.owner.controls = packet.controls().orElseThrow();
            session.owner.inputWorldTick = session.world.getTotalWorldTime();
            return;
        }
        if (session.owner != null && (!session.owner.resumeOffered || PlayerKinematics.requiresNative(session.player))) return;
        if (packet.kind() != PlayerPhysicsProtocol.Kind.APPLY) return;
        Optional<GIntentObservation> source = intent.observe(session.player);
        if (source.isEmpty() || !source.orElseThrow().equals(session.source)) return;
        // This is an untrusted pending message, NOT an accepted velocity, budget, or owner.
        session.lastIncomingSequence = packet.sequence();
        session.pending = packet;
        session.pendingTick = serverTick;
    }

    private boolean current(Session session) {
        return sessions.get(session.manager) == session && channelCurrent(session.manager)
                && session.handler.player == session.player && session.player.world == session.world
                && session.player.dimension == session.scope.dimension()
                && session.player.getUniqueID().equals(session.scope.playerId())
                && actorCurrent(session.handler, session.manager);
    }

    private boolean channelCurrent(NetworkManager manager) {
        var capability = capabilities.get(manager);
        return capability != null && capability.connected && capability.registered && !capability.retired
                && manager.isChannelOpen();
    }

    private static boolean actorCurrent(NetHandlerPlayServer handler, NetworkManager manager) {
        var player = handler.player;
        return player != null && player.connection == handler && handler.netManager == manager
                && manager.getNetHandler() == handler && player.world instanceof WorldServer
                && DimensionManager.getWorld(player.dimension) == player.world
                && player.dimension == player.world.provider.getDimension()
                && !player.isDead && player.isEntityAlive();
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        retirePlayer(event);
    }

    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        retirePlayer(event);
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        retirePlayer(event);
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        retirePlayer(event);
    }

    private synchronized void retirePlayer(PlayerEvent event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.player == event.player || session.scope.playerId().equals(event.player.getUniqueID()))
                retire(session.manager);
        }
    }

    @SubscribeEvent
    public synchronized void onWorldUnload(WorldEvent.Unload event) {
        for (Session session : sessions.values().toArray(Session[]::new)) {
            if (session.world == event.getWorld()) retire(session.manager);
        }
    }

    public synchronized void stop() {
        for (NetworkManager manager : sessions.keySet().toArray(NetworkManager[]::new)) retire(manager);
        capabilities.clear();
    }

    private void retire(NetworkManager manager) {
        Session old = sessions.remove(manager);
        if (old != null) {
            old.dispatcher.invalidate();
            if (old.owner != null) old.owner.travel.invalidate();
            if (old.owner != null && !old.owner.nativeDelegated) restoreNativeGravity(old);
        }
    }

    private static void restoreNativeGravity(Session session) {
        if (!session.player.hasNoGravity()) session.owner.travel.nativeNoGravity = false;
        if (UnigravMod.entityBinder == null) session.player.setNoGravity(session.owner.travel.nativeNoGravity);
        else UnigravMod.entityBinder.restoreNativeGravity(session.player, session.owner.travel.nativeNoGravity);
    }

    private record Offer(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet) {
    }

    private static final class Capability {
        private boolean connected;
        private boolean registered;
        private boolean retired;
    }

    private static final class Session {
        private final NetHandlerPlayServer handler;
        private final NetworkManager manager;
        private final EntityPlayerMP player;
        private final WorldServer world;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final RemoteBoostBoundedDispatcher<PlayerPhysicsProtocol.Packet> dispatcher;
        private GIntentObservation source;
        private long proposal;
        private long outgoingSequence;
        private long lastIncomingSequence = -1;
        private long lastOfferTick;
        private PlayerPhysicsProtocol.Packet pending;
        private long pendingTick;
        private boolean overflowReported;
        private Owned owner;

        private Session(NetHandlerPlayServer handler, long epoch) {
            this.handler = handler;
            manager = handler.netManager;
            player = handler.player;
            world = player.getServerWorld();
            scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), UUID.randomUUID(), player.dimension, epoch);
            dispatcher = new RemoteBoostBoundedDispatcher<>(8, task -> player.getServer().addScheduledTask(task));
        }
    }

    private static final class Owned {
        private PlayerPhysicalSnapshot snapshot;
        private final BoostMovementBudget budget;
        private final long adoptedAtTick;
        private long budgetTick;
        private long lastWorldTick;
        private long lastStateTick;
        private String lastFailure;
        private PlayerPhysicsProtocol.Controls controls = IDLE;
        private long inputWorldTick = Long.MIN_VALUE / 2;
        private boolean boostCarrier;
        private OptionalInt nativeTeleportId = OptionalInt.empty();
        private boolean teleportPending;
        private boolean nativeDelegated;
        private boolean resumeOffered;
        private long delegationProposal;
        private Vec3 pendingImpulse = Vec3.ZERO;
        private final PlayerPhysicsStep.TravelState travel = new PlayerPhysicsStep.TravelState();

        private Owned(PlayerPhysicalSnapshot snapshot, BoostMovementBudget budget, long tick, long worldTick,
                      boolean nativeNoGravity) {
            this.snapshot = snapshot;
            this.budget = budget;
            adoptedAtTick = tick;
            travel.nativeNoGravity = nativeNoGravity;
            budgetTick = budget.snapshot().lastTick();
            lastWorldTick = worldTick;
            lastStateTick = tick;
        }
    }
}
