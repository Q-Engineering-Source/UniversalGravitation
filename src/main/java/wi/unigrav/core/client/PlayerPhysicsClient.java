package wi.unigrav.core.client;

import wi.unigrav.core.player.PlayerMobilityIntent;
import org.apache.logging.log4j.message.StringMapMessage;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.network.INetHandler;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.client.boost.RemoteBoostClient;
import wi.unigrav.core.player.ClientOwnerScopeBridge;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;
import wi.unigrav.core.player.PlayerPhysicsNetwork;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerBoostEligibility;
import wi.unigrav.core.player.FreeTickResources;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerKinematics;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.FreeNativeEnvironment;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.AquaCardinalPoseClearance;
import wi.unigrav.api.math.Vec3;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import wi.unigrav.core.player.CardinalFrame;

/** Client-thread physical negotiation and absolute state installation; OFFER/APPLY never install an owner. */
public final class PlayerPhysicsClient {
    private static final PlayerPhysicsClient INSTANCE = new PlayerPhysicsClient();
    private final ClientPhysicsTickQueue tickQueue = new ClientPhysicsTickQueue();
    private boolean installed;
    private volatile NetHandlerPlayClient connection;
    private volatile EntityPlayerSP player;
    private volatile World world;
    private volatile int dimension;
    private volatile PlayerPhysicsClientSession session;
    private final PlayerPhysicsClientPresentation presentation = new PlayerPhysicsClientPresentation();
    private boolean originalNoGravity;
    private boolean noGravityCaptured;
    private volatile PlayerPhysicsInboundConnections.ConnectionHandle connectionHandle;
    private volatile PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
    private volatile AcceptedOfferContext acceptedOfferContext;
    private SuccessfulInstallationStamp successfulInstallation;
    private EntityPlayer acceptedPresentationActor;
    private PlayerPhysicalSnapshot acceptedPresentationSnapshot;
    private final List<BrokenActorInstallation> brokenInstallations = new ArrayList<>();
    private long nextBrokenAttemptStamp;
    private ClientInstallationRecoveryTask recoveryTask;
    private ClientInstallationRecoveryTask nativeTransferRecoveryTask;
    private AcceptedOfferContext nativeTransferRecoveryOwner;
    private PlayerPhysicsProtocol.Packet nativeTransferRecoveryRequest;
    private ClientFreeEnvironmentOwner freeEnvironmentOwner;
    private ClientNativeEnvironmentScope nativeEnvironmentScope;
    private long nativeSubmissionTick;
    private long predictionEpoch;
    private ClientCardinalPredictionOwner prediction;
    private long lastEquipmentFlightProbeTick = Long.MIN_VALUE;
    private String lastEquipmentFlightProbeIdentity;
    private CorrectionPredictionRearm correctionPredictionRearm;
    private record CorrectionPredictionRearm(AcceptedOfferContext owner, Object scope,
            PlayerPhysicsClientSession.CorrectionPredictionIdentity identity) {
        boolean current(PlayerPhysicsClient client, AcceptedOfferContext context) {
            return owner == context && scope == client.scopeHandle && identity.current(context.session)
                    && client.acceptedOwnerLifetimeCurrent(context, Minecraft.getMinecraft());
        }
    }
    private long successfulInstallSerial;
    private final java.util.ArrayDeque<PredictionMutationContext> predictionMutations = new java.util.ArrayDeque<>();
    private PredictionRetirement pendingPredictionRetirement;
    private boolean drainingPredictionRetirement;
    private final java.util.ArrayDeque<CleanupWriter> cleanupWriters = new java.util.ArrayDeque<>();
    private static final class CleanupWriter {
        final AcceptedOfferContext owner;
        boolean possiblyPartial;
        CleanupWriter(AcceptedOfferContext owner) { this.owner = owner; }
    }

    private long predictionSuspensionRequestId;
    private PredictionSuspension predictionSuspension;
    static final class PredictionSuspension {
        final AcceptedOfferContext owner;
        final Optional<ClientCardinalPredictionOwner> previous;
        PlayerPhysicsProtocol.PredictionRequest suspend;
        PlayerPhysicsProtocol.Packet checkpoint;
        boolean acknowledged, rearmSent, jumpHeld;
        long lastProbeTick=Long.MIN_VALUE;
        PredictionSuspension(AcceptedOfferContext owner,ClientCardinalPredictionOwner previous,long id,
                PlayerPhysicsProtocol.PredictionRefusal refusal) {
            this.owner=owner; this.previous=Optional.of(previous);
            suspend=new PlayerPhysicsProtocol.PredictionRequest(id,previous.bootstrap(),PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,refusal);
        }
        PredictionSuspension(AcceptedOfferContext owner,ClientCardinalPredictionOwner previous,
                PlayerPhysicsProtocol.PredictionSuspended notification) {
            this.owner=owner;this.previous=Optional.of(previous);acknowledged=true;
            suspend=new PlayerPhysicsProtocol.PredictionRequest(notification.requestId(),notification.bootstrap(),
                    PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,Optional.empty(),notification.origin());
        }
        PredictionSuspension(AcceptedOfferContext owner, PlayerPhysicsProtocol.PredictionSuspended notification) {
            this.owner=owner; this.previous=Optional.empty(); acknowledged=true;
            suspend=new PlayerPhysicsProtocol.PredictionRequest(notification.requestId(),notification.bootstrap(),
                    PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,Optional.empty(),notification.origin());
        }
        void acceptReceipt(PlayerPhysicsProtocol.PredictionSuspended receipt) {
            if (!receipt.bootstrap().equals(suspend.bootstrap())) return;
            // A proved server STATE resolves the crossing of two suspension transactions for this exact mapping.
            // Keep the previous owner, cumulative ledger and held-button state; only replace the transaction identity.
            if (suspend.origin()==PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL
                    && receipt.origin()!=PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL) {
                suspend=new PlayerPhysicsProtocol.PredictionRequest(receipt.requestId(),receipt.bootstrap(),
                        PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,Optional.empty(),receipt.origin());
                rearmSent=false;lastProbeTick=Long.MIN_VALUE;
            }
            if (receipt.requestId()==suspend.requestId() && receipt.origin()==suspend.origin()) acknowledged=true;
        }
        PlayerPhysicsProtocol.PredictionRequest request() {
            return rearmSent ? new PlayerPhysicsProtocol.PredictionRequest(suspend.requestId(),suspend.bootstrap(),
                    PlayerPhysicsProtocol.PredictionRequestAction.REARM,suspend.refusal(),suspend.origin()) : suspend;
        }
    }

    private final class PredictionMutationContext {
        final AcceptedOfferContext owner;
        final PlayerPhysicsInboundConnections.ScopeHandle scope;
        final PlayerPhysicsClientSession.PredictionLifetime lifetime;
        final ClientCardinalPredictionOwner predictionOwner;
        final long successfulSerial;
        ClientCardinalPredictionOwner.Mutation mutation;
        PredictionMutationContext(AcceptedOfferContext owner, ClientCardinalPredictionOwner predictionOwner) {
            this.owner = owner; scope = scopeHandle; this.predictionOwner = predictionOwner;
            lifetime = owner.session.predictionLifetime(); successfulSerial = successfulInstallSerial;
        }
        boolean current() {
            return prediction == predictionOwner && scopeHandle == scope && successfulInstallSerial == successfulSerial
                    && lifetime.current(owner.session) && owner.session.predictionEligible()
                    && acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft()) && !isBrokenActor(owner.actor);
        }
    }
    /** Captured at prediction failure or correction BEGIN, never minted by an obsolete mutation. */
    private final class PredictionRetirement {
        final AcceptedOfferContext owner;
        final PlayerPhysicsInboundConnections.ScopeHandle scope;
        final PlayerPhysicsClientSession.PredictionLifetime lifetime;
        final long successfulSerial;
        final String reason;
        Throwable cause;
        PredictionRetirement(AcceptedOfferContext owner, PlayerPhysicsInboundConnections.ScopeHandle scope,
                PlayerPhysicsClientSession.PredictionLifetime lifetime, String reason, Throwable cause) {
            this.owner = owner; this.scope = scope; this.lifetime = lifetime;
            successfulSerial = successfulInstallSerial; this.reason = reason; this.cause = cause;
        }
        boolean identityCurrent() {
            return successfulInstallSerial == successfulSerial && lifetime.current(owner.session)
                    && scopeHandle == scope && acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft());
        }
    }

    private AcceptedOfferContext handoffLifetime;
    private ClientNativeHandoffLedger handoffLedger;


    /** Opaque context created only after the client Session accepts one exact materialized OFFER. */
    public static final class AcceptedOfferContext {
        private final PlayerPhysicsInboundConnections.DecodeHandle decode;
        private final PlayerPhysicsProtocol.Packet offer;
        private final PlayerPhysicsClientSession session;
        private final PlayerPhysicsInboundConnections.ConnectionHandle connection;
        private final NetworkManager manager;
        private final NetHandlerPlayClient handler;
        private final EntityPlayerSP actor;
        private final World world;
        private final long ownerGeneration;

        private AcceptedOfferContext(PlayerPhysicsInboundConnections.DecodeHandle decode,
                PlayerPhysicsProtocol.Packet offer, PlayerPhysicsClientSession session,
                PlayerPhysicsInboundConnections.ConnectionHandle connection,
                NetworkManager manager, NetHandlerPlayClient handler, EntityPlayerSP actor,
                World world, long ownerGeneration) {
            this.decode = decode;
            this.offer = offer;
            this.session = session;
            this.connection = connection;
            this.manager = manager;
            this.handler = handler;
            this.actor = actor;
            this.world = world;
            this.ownerGeneration = ownerGeneration;
        }
    }

    /** Reusable proof of the last complete client install, used only for an exact-state retransmission. */
    static final class SuccessfulInstallationStamp {
        private final Object acceptedOwnerContext;
        private final PlayerPhysicsClientSession session;
        private final Object sessionLifetime;
        private final EntityPlayer actor;
        private final World world;
        private final PlayerPhysicalSnapshot.Scope scope;
        private final long offerSequence;
        private final long stateSequence;
        private final PlayerPhysicalSnapshot snapshot;
        private final PlayerPhysicsClientMirror.InstalledProof mirrorProof;
        private final PlayerPhysicsClientPresentation presentation;

        private SuccessfulInstallationStamp(Object acceptedOwnerContext, PlayerPhysicsClientSession session,
                Object sessionLifetime, EntityPlayer actor, World world, PlayerPhysicsProtocol.Packet packet,
                PlayerPhysicalSnapshot acceptedSnapshot,
                PlayerPhysicsClientMirror.InstalledProof mirrorProof,
                PlayerPhysicsClientPresentation presentation) {
            this.acceptedOwnerContext = acceptedOwnerContext;
            this.session = session;
            this.sessionLifetime = sessionLifetime;
            this.actor = actor;
            this.world = world;
            this.scope = packet.scope();
            this.offerSequence = packet.offerSequence();
            this.stateSequence = packet.sequence();
            this.snapshot = acceptedSnapshot;
            this.mirrorProof = mirrorProof;
            this.presentation = presentation;
        }

        NativeRestoreInputLease nativeRestoreInputLease(Object exactOwnerContext,
                PlayerPhysicsClientSession exactSession, EntityPlayer exactActor, World exactWorld,
                BooleanSupplier ownerCurrent) {
            var backing = session.nativeRestoreInputLease();
            if (backing == null || acceptedOwnerContext != exactOwnerContext || session != exactSession
                    || actor != exactActor || world != exactWorld) return null;
            var lease = new NativeRestoreInputLease(this, backing, ownerCurrent);
            return lease.current() ? lease : null;
        }

        boolean matchesRetransmission(Object exactOwnerContext, PlayerPhysicsClientSession exactSession,
                PlayerPhysicsClientSession.PreparedInstallation prepared, PlayerPhysicsProtocol.Packet packet,
                EntityPlayer exactActor, World exactWorld, PlayerPhysicsClientPresentation exactPresentation,
                EntityPlayer currentPresentationActor, PlayerPhysicalSnapshot currentPresentationSnapshot) {
            PlayerPhysicalSnapshot candidate = packet.snapshot().orElse(null);
            return exactOwnerContext == acceptedOwnerContext && exactSession == session
                    && prepared != null && prepared.lifetimeIdentity() == sessionLifetime
                    && prepared.packetIdentity() == packet
                    && exactSession.installationCurrent(prepared)
                    && prepared.disposition() == PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION
                    && exactActor == actor && exactWorld == world && exactPresentation == presentation
                    && currentPresentationActor == actor && currentPresentationSnapshot == snapshot
                    && actor.world == world && packet.sequence() > stateSequence
                    && (packet.kind() == PlayerPhysicsProtocol.Kind.STATE
                    || packet.kind() == PlayerPhysicsProtocol.Kind.OWNED)
                    && packet.scope().equals(scope) && packet.offerSequence() == offerSequence
                    && candidate != null && candidate.equals(snapshot)
                    && prepared.snapshot().equals(snapshot)
                    && mirrorProof.matchesCurrent(actor, candidate);
        }

        SuccessfulInstallationStamp publishRetransmission(Object exactOwnerContext,
                PlayerPhysicsClientSession exactSession, PlayerPhysicsClientSession.PreparedInstallation prepared,
                PlayerPhysicsProtocol.Packet packet, EntityPlayer exactActor, World exactWorld,
                PlayerPhysicsClientPresentation exactPresentation, EntityPlayer currentPresentationActor,
                PlayerPhysicalSnapshot currentPresentationSnapshot, Runnable finishDecode) {
            if (!matchesRetransmission(exactOwnerContext, exactSession, prepared, packet, exactActor, exactWorld,
                    exactPresentation, currentPresentationActor, currentPresentationSnapshot)
                    || !exactSession.installationCurrent(prepared) || !exactSession.publishInstallation(prepared))
                return null;
            finishDecode.run();
            if (!mirrorProof.matchesCurrent(exactActor, packet.snapshot().orElseThrow())) {
                exactSession.revokeStateAcknowledgements();
                return null;
            }
            if (!exactSession.authorizePublishedStateAcknowledgement(prepared,
                    PlayerPhysicsClientSession.acknowledgementDomain(packet))) return null;
            return new SuccessfulInstallationStamp(acceptedOwnerContext, session, sessionLifetime, actor, world,
                    packet, snapshot, mirrorProof, presentation);
        }
    }

    /** Only retains a real restore install for ACK submission or the subsequent typed native base. */
    static final class NativeRestoreInputLease {
        private final SuccessfulInstallationStamp stamp;
        private final PlayerPhysicsClientSession.NativeRestoreInputLease backing;
        private final BooleanSupplier ownerCurrent;
        private NativeRestoreInputLease(SuccessfulInstallationStamp stamp,
                PlayerPhysicsClientSession.NativeRestoreInputLease backing, BooleanSupplier ownerCurrent) {
            this.stamp = stamp; this.backing = backing; this.ownerCurrent = ownerCurrent;
        }
        boolean pending() { return backing.pending(); }
        private boolean identityCurrent() {
            return backing.current() && backing.matchesPublished(stamp.sessionLifetime, stamp.snapshot,
                    stamp.stateSequence, stamp.offerSequence) && stamp.actor.world == stamp.world
                    && stamp.snapshot.scope().equals(stamp.scope);
        }
        boolean current() {
            if (!identityCurrent() || !ownerCurrent.getAsBoolean() || !identityCurrent()) return false;
            boolean matches = stamp.mirrorProof.matchesCurrent(stamp.actor, stamp.snapshot);
            return matches && identityCurrent() && ownerCurrent.getAsBoolean() && identityCurrent();
        }
    }

    /** Production-connected final boundary: actual Presentation and Session commits precede decode completion and ACK authority. */
    static SuccessfulInstallationStamp completeInstalledState(Object exactOwnerContext,
            PlayerPhysicsClientSession exactSession, PlayerPhysicsClientSession.PreparedInstallation prepared,
            PlayerPhysicsProtocol.Packet packet, EntityPlayer exactActor, World exactWorld,
            PlayerPhysicsClientMirror.InstalledProof mirrorProof,
            PlayerPhysicsClientPresentation exactPresentation,
            PlayerPhysicsClientPresentation.PreparedPresentation preparedPresentation,
            Runnable finishInstalledDecode) {
        PlayerPhysicalSnapshot snapshot = packet.snapshot().orElse(null);
        if (exactOwnerContext == null || exactSession == null || prepared == null
                || (packet.kind() != PlayerPhysicsProtocol.Kind.STATE && packet.kind() != PlayerPhysicsProtocol.Kind.OWNED)
                || prepared.disposition() != PlayerPhysicsClientSession.InstallationDisposition.INSTALL
                || prepared.packetIdentity() != packet
                || snapshot == null || prepared.snapshot() != snapshot
                || !exactSession.installationCurrent(prepared)
                || exactActor == null || exactWorld == null || exactActor.world != exactWorld
                || mirrorProof == null || !mirrorProof.matchesCurrent(exactActor, snapshot)
                || preparedPresentation == null
                || !preparedPresentation.isBoundTo(exactPresentation, exactActor, snapshot)) return null;
        if (!exactPresentation.commitPrepared(preparedPresentation)
                || !mirrorProof.matchesCurrent(exactActor, snapshot)
                || !exactSession.publishInstallation(prepared)) return null;
        finishInstalledDecode.run();
        if (!mirrorProof.matchesCurrent(exactActor, snapshot)) {
            exactSession.revokeStateAcknowledgements();
            return null;
        }
        if (!exactSession.authorizePublishedStateAcknowledgement(prepared,
                PlayerPhysicsClientSession.acknowledgementDomain(packet))) return null;
        return new SuccessfulInstallationStamp(exactOwnerContext, exactSession, prepared.lifetimeIdentity(),
                exactActor, exactWorld, packet, snapshot, mirrorProof, exactPresentation);
    }
    /** Minted only after the exact failed installation has been retired and cleaned. */
    private static final class RetiredInstallationFailure extends RuntimeException {
        private final PlayerPhysicsInboundConnections.DecodeHandle decode;
        private final AcceptedOfferContext context;
        private final long attemptStamp;
        private final PlayerPhysicsProtocol.Packet request;

        private RetiredInstallationFailure(Throwable cause, PlayerPhysicsInboundConnections.DecodeHandle decode,
                AcceptedOfferContext context, long attemptStamp, PlayerPhysicsProtocol.Packet request) {
            super("Exact client installation task failed after reliable retirement", cause);
            this.decode = decode;
            this.context = context;
            this.attemptStamp = attemptStamp;
            this.request = request;
        }
    }

    private PlayerPhysicsClient() {}

    public static void install() {
        if (!INSTANCE.installed) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            MinecraftForge.EVENT_BUS.register(ClientMotionTimingDiagnostics.INSTANCE);
            INSTANCE.installed = true;
        }
    }

    /** Netty ingress captures only the real handler/lifetime and bounded raw frame. */
    public static void receiveRaw(PlayerPhysicsInboundConnections.RawInboundFrame raw, INetHandler handler) {
        Objects.requireNonNull(raw, "raw");
        if (!(handler instanceof NetHandlerPlayClient client))
            throw new IllegalArgumentException("Client physics ingress has no exact NetHandlerPlayClient");
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        NetworkManager manager = Objects.requireNonNull(client.getNetworkManager(), "client.networkManager");
        Optional<PlayerPhysicsInboundConnections.ConnectionHandle> current = facade.findCurrentConnection(
                manager, client, EnumPacketDirection.CLIENTBOUND);
        if (current.isEmpty()) return;

        Optional<PlayerPhysicsInboundConnections.ScopeHandle> capturedScope;
        synchronized (manager) {
            capturedScope = INSTANCE.connectionHandle == current.orElseThrow()
                    && INSTANCE.connection == client ? Optional.ofNullable(INSTANCE.scopeHandle) : Optional.empty();
        }
        PlayerPhysicsInboundConnections.AdmissionResult admitted = facade.admitRawFrame(
                current.orElseThrow(), manager, client, EnumPacketDirection.CLIENTBOUND, raw, capturedScope);
        if (!admitted.queued() || admitted.newDrain().isEmpty()) return;

        PlayerPhysicsInboundConnections.DrainHandle exactDrain = admitted.newDrain().orElseThrow();
        try {
            INSTANCE.tickQueue.schedule(() -> INSTANCE.drainInbound(exactDrain, current.orElseThrow(), client));
        } catch (Throwable schedulingFailure) {
            try {
                facade.drainSchedulingFailed(exactDrain, schedulingFailure);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != schedulingFailure) schedulingFailure.addSuppressed(cleanupFailure);
            }
            UnigravMod.LOGGER.error("ugmotion1 failed to schedule the exact client physics drain", schedulingFailure);
            PlayerPhysicsClient.<RuntimeException>rethrow(schedulingFailure);
        }
    }

    /** Fixed read-only query used only by ClientOwnerScopeBridge; it cannot mint or return a scope. */
    public static boolean acceptedOfferCurrent(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            NetworkManager exactManager, NetHandlerPlayClient exactHandler,
            PlayerPhysicsProtocol.Packet exactOffer, AcceptedOfferContext context) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.isCallingFromMinecraftThread()
                && context != null && context == INSTANCE.acceptedOfferContext
                && context.decode == exactDecode && context.manager == exactManager
                && context.connection != null && PlayerPhysicsInboundConnections.INSTANCE
                .decodeBelongsToConnection(exactDecode, context.connection)
                && INSTANCE.connectionHandle == context.connection
                && context.handler == exactHandler && context.offer == exactOffer
                && context.session == INSTANCE.session && context.actor == INSTANCE.player
                && context.world == INSTANCE.world && context.ownerGeneration == context.session.ownerGeneration()
                && exactManager.getNetHandler() == exactHandler
                && exactHandler.getNetworkManager() == exactManager
                && mc.getConnection() == exactHandler && mc.player == context.actor && mc.world == context.world
                && PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(exactManager, exactHandler,
                EnumPacketDirection.CLIENTBOUND).filter(handle -> handle == context.connection).isPresent()
                && context.session.acceptedOfferCurrent(exactOffer);
    }

    private void drainInbound(PlayerPhysicsInboundConnections.DrainHandle exactDrain,
            PlayerPhysicsInboundConnections.ConnectionHandle exactConnection, NetHandlerPlayClient exactHandler) {
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        while (true) {
            PlayerPhysicsInboundConnections.DecodeHandle exactDecode = null;
            try {
                Optional<PlayerPhysicsInboundConnections.DecodeHandle> next = facade.beginNextOrClose(exactDrain);
                if (next.isEmpty()) return;
                exactDecode = next.orElseThrow();
                Optional<PlayerPhysicsProtocol.Packet> decoded = facade.materializePacket(exactDecode);
                if (decoded.isEmpty()) {
                    facade.finishDecode(exactDecode, PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
                    continue;
                }
                receiveMaterialized(exactDecode, exactConnection, exactHandler, decoded.orElseThrow());
            } catch (RetiredInstallationFailure retired) {
                Throwable originalFailure = retired.getCause();
                try {
                    facade.ownerDrainFailed(exactDrain, exactDecode, originalFailure);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
                    UnigravMod.LOGGER.error("ugmotion1 retired client installation task could not settle its drain; recovery was not started",
                            originalFailure);
                    PlayerPhysicsClient.<RuntimeException>rethrow(originalFailure);
                    return;
                }
                if (retired.decode != exactDecode || retired.context.connection != exactConnection
                        || retired.context.handler != exactHandler
                        || !isBrokenAttempt(retired.context.actor, retired.context.world, retired.attemptStamp)) {
                    originalFailure.addSuppressed(new IllegalStateException("Retired installation token lost its exact failed attempt fence"));
                    UnigravMod.LOGGER.error("ugmotion1 retired client installation task identity was invalid; recovery was not started",
                            originalFailure);
                    PlayerPhysicsClient.<RuntimeException>rethrow(originalFailure);
                    return;
                }
                try {
                    recoveryTask = new ClientInstallationRecoveryTask(retired.context.actor, retired.context.world,
                            retired.context.handler, retired.context.manager, exactConnection, retired.request);
                } catch (Throwable taskFailure) {
                    if (taskFailure != originalFailure) originalFailure.addSuppressed(taskFailure);
                    UnigravMod.LOGGER.error("ugmotion1 retired client installation task could not create its bounded recovery", originalFailure);
                    PlayerPhysicsClient.<RuntimeException>rethrow(originalFailure);
                    return;
                }
                UnigravMod.LOGGER.error("ugmotion1 client installation task failed; exact actor remains fenced; bounded RECOVER pending "
                                + retired.request.scope() + " proposal=" + retired.request.offerSequence()
                                + " sequence=" + retired.request.sequence(), originalFailure);
                return;
            } catch (Throwable originalFailure) {
                try {
                    facade.ownerDrainFailed(exactDrain, exactDecode, originalFailure);
                } catch (Throwable cleanupFailure) {
                    if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
                }
                UnigravMod.LOGGER.error("ugmotion1 client physics drain failed for its captured connection task",
                        originalFailure);
                PlayerPhysicsClient.<RuntimeException>rethrow(originalFailure);
                return;
            }
        }
    }

    private void receiveMaterialized(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ConnectionHandle exactConnection, NetHandlerPlayClient exactHandler,
            PlayerPhysicsProtocol.Packet packet) throws Throwable {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread())
            throw new IllegalStateException("Client physics materialization escaped the client tick owner");
        NetworkManager manager = exactHandler.getNetworkManager();
        if (manager == null || exactHandler != manager.getNetHandler()
                || !targetMatchesPacket(exactHandler, mc.player, mc.world, packet)) {
            rejectDecode(exactDecode);
            return;
        }
        if (packet.kind() == PlayerPhysicsProtocol.Kind.OFFER) {
            receiveAcceptedOffer(exactDecode, exactConnection, exactHandler, packet, mc, manager);
            return;
        }
        PlayerPhysicsClientSession receiving = session;
        AcceptedOfferContext ownerContext = acceptedOfferContext;
        PlayerPhysicsInboundConnections.ScopeHandle exactScope = scopeHandle;
        if (receiving == null || ownerContext == null || exactScope == null
                || ownerContext.session != receiving || ownerContext.handler != exactHandler
                || ownerContext.manager != manager || ownerContext.actor != mc.player || ownerContext.world != mc.world
                || connectionHandle != exactConnection || connection != exactHandler
                || !receiving.currentAcceptedOfferIdentity(ownerContext.offer)
                || !packet.scope().equals(ownerContext.offer.scope())) {
            rejectDecode(exactDecode);
            return;
        }
        if (packet.kind() == PlayerPhysicsProtocol.Kind.OWNED || packet.kind() == PlayerPhysicsProtocol.Kind.STATE) {
            if (packet.kind() == PlayerPhysicsProtocol.Kind.STATE)
                ClientMotionTimingDiagnostics.INSTANCE.queuedState(System.nanoTime());
            try {
                installTypedState(exactDecode, exactScope, exactHandler, receiving, ownerContext, packet, mc);
            } finally {
                if (packet.kind() == PlayerPhysicsProtocol.Kind.STATE && session == receiving)
                    ClientMotionTimingDiagnostics.INSTANCE.stateResult(receiving.lastStateDisposition());
            }
            return;
        }
        if ((packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE || packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_MOTION
                || packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE)
                && !isBrokenActor(mc.player)
                && isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving, ownerContext, packet, mc)) {
            EntityPlayerSP exactActor = mc.player;
            World exactWorld = mc.world;
            java.util.function.BooleanSupplier exactOwner = () -> session == receiving && acceptedOfferContext == ownerContext
                    && mc.player == exactActor && player == exactActor && mc.world == exactWorld && world == exactWorld
                    && connection == exactHandler && scopeHandle == exactScope
                    && isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving, ownerContext, packet, mc);
            java.util.function.BooleanSupplier aliveOwner = () -> exactOwner.getAsBoolean()
                    && exactActor.isEntityAlive() && exactOwner.getAsBoolean() && !exactActor.isDead;
            boolean accepted;
            try {
                accepted = packet.kind() == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE
                    ? receiving.receiveNativeCorrectionComplete(packet, aliveOwner)
                    : receiving.receiveNativeTransfer(packet, aliveOwner, (velocity, proof) -> {
                if (!proof.getAsBoolean()) return;
                exactActor.motionX = velocity.x() / 20.0;
                if (!proof.getAsBoolean()) return;
                exactActor.motionY = velocity.y() / 20.0;
                if (!proof.getAsBoolean()) return;
                exactActor.motionZ = velocity.z() / 20.0;
                if (!proof.getAsBoolean()) return;
            });
            } catch (RuntimeException | Error failure) {
                // Retain this exact scope/canonical owner: the server's explicitly correlated restore
                // must pass the normal Mirror/Presentation/decode/ACK transaction in this Session.
                try {
                    var feedback = receiving.recoveryRequest();
                    if (packet.kind() != PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE
                            && aliveOwner.getAsBoolean() && feedback.isPresent()
                            && receiving.nativeTransferFailureCurrent(feedback.orElseThrow())
                            && aliveOwner.getAsBoolean()) {
                        var request = feedback.orElseThrow();
                        nativeTransferRecoveryTask = new ClientInstallationRecoveryTask(exactActor, exactWorld,
                                exactHandler, manager, ownerContext.connection, request);
                        nativeTransferRecoveryOwner = ownerContext;
                        nativeTransferRecoveryRequest = request;
                        PlayerPhysicsInboundConnections.INSTANCE.finishDecode(exactDecode,
                                PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
                        UnigravMod.LOGGER.error("ugmotion1 native velocity installation failed; exact transfer recovery scheduled scope "
                                + packet.scope() + " sequence " + packet.sequence(), failure);
                        return;
                    }
                } catch (Throwable recoveryFailure) {
                    if (recoveryFailure != failure) failure.addSuppressed(recoveryFailure);
                }
                throw failure;
            }
            if (accepted && aliveOwner.getAsBoolean()) {
                var acknowledgement = receiving.nativeTransferAcknowledgement();
                if (acknowledgement.isPresent()) {
                    var exactAck = acknowledgement.orElseThrow();
                    PlayerPhysicsNetwork.sendToServer(exactAck);
                    if (session == receiving && acceptedOfferContext == ownerContext && mc.player == exactActor
                            && mc.world == exactWorld && !exactActor.isDead)
                        receiving.nativeTransferAcknowledgementSent(exactAck);
                }
            }
            PlayerPhysicsInboundConnections.INSTANCE.finishDecode(exactDecode, accepted
                    ? PlayerPhysicsInboundConnections.DecodeOutcome.PROOF_ACCEPTED
                    : PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
            return;
        }
        rejectDecode(exactDecode);
    }

    private void receiveAcceptedOffer(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ConnectionHandle exactConnection, NetHandlerPlayClient exactHandler,
            PlayerPhysicsProtocol.Packet packet, Minecraft mc, NetworkManager manager) throws Throwable {
        EntityPlayerSP actor = mc.player;
        World exactWorld = mc.world;
        if (actor == null || exactWorld == null || !mc.isCallingFromMinecraftThread()
                || !allowsOfferAfterInstallation(actor, manager, packet.scope())
                        && !allowsHealthyDelegatedResumeOffer(exactDecode, exactConnection, exactHandler,
                                packet, actor, exactWorld, mc, manager)) {
            rejectDecode(exactDecode);
            return;
        }
        if (packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.ServerEnvironmentRecovery
                && (!targets(exactHandler, actor, exactWorld) || session == null || acceptedOfferContext == null
                || connectionHandle != exactConnection || connection != exactHandler)) {
            rejectDecode(exactDecode);
            return;
        }
        if (!targets(exactHandler, actor, mc.world)) {
            clear();
            connection = exactHandler;
            player = actor;
            world = exactWorld;
            dimension = actor.dimension;
            connectionHandle = exactConnection;
            session = new PlayerPhysicsClientSession(
                    snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot),
                    () -> retireLegacy(actor, exactWorld),
                    () -> {
                        resetPresentation();
                        if (actor.world == exactWorld && !isBrokenActor(actor))
                            actor.setNoGravity(originalNoGravity);
                    });
        }

        PlayerPhysicsClientSession receiving = session;
        if (receiving == null) {
            rejectDecode(exactDecode);
            return;
        }
        PlayerPhysicsInboundConnections.ScopeHandle retiredScope;
        AcceptedOfferContext acceptedContext;
        ClientFreeEnvironmentOwner resumeOwner = null;
        PlayerPhysicsClientSession.NativeEnvironmentResume resume = null;
        synchronized (manager) {
            if (manager.getNetHandler() != exactHandler || connectionHandle != exactConnection
                    || connection != exactHandler || player != actor || world != exactWorld
                    || !PlayerPhysicsInboundConnections.INSTANCE.decodeCurrent(exactDecode)) {
                rejectDecode(exactDecode);
                return;
            }
            if (packet.nativeTransfer().orElse(null) instanceof PlayerPhysicsProtocol.ServerEnvironmentRecovery) {
                AcceptedOfferContext previous = acceptedOfferContext;
                boolean ownerCurrent = previous != null && previous.session == receiving
                        && acceptedOwnerLifetimeCurrent(previous, mc);
                boolean alive = ownerCurrent && actor.isEntityAlive();
                if (!alive || !acceptedOwnerLifetimeCurrent(previous, mc) || actor.isDead
                        || !PlayerPhysicsInboundConnections.INSTANCE.decodeCurrent(exactDecode)) {
                    rejectDecode(exactDecode);
                    return;
                }
                if (receiving.isCurrentEnvironmentRecoveryOffer(packet)) {
                    if (scopeHandle == null || !PlayerPhysicsInboundConnections.INSTANCE.scopeFromAcceptedOffer(
                            scopeHandle, previous.decode, previous.connection, previous.offer.scope())) {
                        rejectDecode(exactDecode);
                        return;
                    }
                    // The existing scope and successful installation remain authoritative on retransmission.
                    PlayerPhysicsInboundConnections.INSTANCE.finishDecode(exactDecode,
                            PlayerPhysicsInboundConnections.DecodeOutcome.PROOF_ACCEPTED);
                    return;
                }
            }
            var retainedEnvironment = freeEnvironmentOwner;
            var previousContext = acceptedOfferContext;
            if (retainedEnvironment != null && retainedEnvironment.session == receiving
                    && retainedEnvironment.actor == actor && retainedEnvironment.world == exactWorld
                    && retainedEnvironment.scope.equals(packet.scope()) && previousContext != null
                    && previousContext.session == receiving && previousContext.actor == actor
                    && previousContext.world == exactWorld && previousContext.connection == exactConnection
                    && previousContext.handler == exactHandler && previousContext.manager == manager
                    && scopeHandle != null && PlayerPhysicsInboundConnections.INSTANCE.scopeFromAcceptedOffer(
                            scopeHandle, previousContext.decode, exactConnection, previousContext.offer.scope())
                    && session == receiving && acceptedOfferContext == previousContext
                    && freeEnvironmentOwner == retainedEnvironment) {
                resume = retainedEnvironment.pendingResume == null
                        ? receiving.captureNativeEnvironmentResume(packet)
                        : retainedEnvironment.pendingResume.nextOffer(receiving, packet);
                if (resume != null) resumeOwner = retainedEnvironment;
            }
            if (!receiving.receive(packet)) {
                rejectDecode(exactDecode);
                return;
            }
            invalidateSuccessfulInstallation();
            retiredScope = scopeHandle;
            if (retiredScope != null) PlayerPhysicsInboundConnections.INSTANCE.retireScope(retiredScope);
            scopeHandle = null;
            acceptedOfferContext = acceptedContext = new AcceptedOfferContext(exactDecode, packet, receiving,
                    exactConnection, manager, exactHandler, actor, exactWorld, receiving.ownerGeneration());
        }

        Optional<PlayerPhysicsInboundConnections.ScopeHandle> opened =
                ClientOwnerScopeBridge.openAcceptedOfferScope(exactDecode, acceptedContext);
        if (opened.isEmpty()) {
            synchronized (manager) {
                if (acceptedOfferContext == acceptedContext && session == receiving
                        && receiving.ownerGeneration() == acceptedContext.ownerGeneration
                        && receiving.acceptedOfferCurrent(packet)) {
                    acceptedOfferContext = null;
                    receiving.reset();
                }
            }
            rejectDecode(exactDecode);
            return;
        }
        PlayerPhysicsInboundConnections.ScopeHandle newScope = opened.orElseThrow();
        synchronized (manager) {
            if (acceptedOfferContext != acceptedContext || session != receiving
                    || manager.getNetHandler() != exactHandler
                    || !acceptedOfferCurrent(exactDecode, manager, exactHandler, packet, acceptedContext)) {
                PlayerPhysicsInboundConnections.INSTANCE.retireScope(newScope);
                rejectDecode(exactDecode);
                return;
            }
            scopeHandle = newScope;
            if (resume != null) {
                if (freeEnvironmentOwner != resumeOwner || !resume.accepted(receiving))
                    throw new IllegalStateException("Delegated Free resource resume changed during scope opening");
                var transfer = resumeOwner.resources.prepareResume();
                if (freeEnvironmentOwner != resumeOwner || !resume.accepted(receiving)
                        || session != receiving || acceptedOfferContext != acceptedContext || scopeHandle != newScope
                        || player != actor || world != exactWorld || connection != exactHandler
                        || connectionHandle != exactConnection || mc.player != actor || mc.world != exactWorld)
                    throw new IllegalStateException("Delegated Free resource resume lost its accepted scope");
                resumeOwner.pendingCorrection = null;
                resumeOwner.correctionTransfer = null;
                resumeOwner.pendingResume = resume;
                resumeOwner.resumeTransfer = transfer;
                resumeOwner.resumeContext = acceptedContext;
                resumeOwner.resumeScope = newScope;
            }
            PlayerPhysicsInboundConnections.INSTANCE.finishAcceptedOffer(exactDecode, newScope, packet);
        }
    }

    private void installTypedState(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, NetHandlerPlayClient exactHandler,
            PlayerPhysicsClientSession receiving, AcceptedOfferContext ownerContext,
            PlayerPhysicsProtocol.Packet packet, Minecraft mc) throws Throwable {
        EntityPlayerSP exactActor = ownerContext.actor;
        World exactWorld = ownerContext.world;
        NetworkManager manager = ownerContext.manager;
        PlayerPhysicsClientSession.InstallationPreparation preparation = receiving.prepareInstallation(packet);
        if (preparation.disposition() == PlayerPhysicsClientSession.InstallationDisposition.REJECTED) {
            rejectDecode(exactDecode);
            return;
        }
        PlayerPhysicsClientSession.PreparedInstallation prepared = preparation.prepared().orElseThrow();
        if (preparation.disposition() == PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION) {
            // A stamp proves the CURRENT authority pose. Restore and prove that exact anchor before asking it for ACK.
            if (prediction != null && prediction.current(ownerContext, exactActor)) {
                synchronized (manager) {
                    if (!isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving, ownerContext, packet, mc)
                            || !receiving.installationCurrent(prepared) || !applyPredictionProjection(ownerContext, prediction, prediction.authority())) {
                        retirePrediction("Retransmission authority restoration rejected");
                        receiving.revokePreparedInstallation(prepared); rejectDecode(exactDecode); return;
                    }
                }
            }
            SuccessfulInstallationStamp installedStamp = successfulInstallation;
            boolean stampCurrent = installedStamp != null && installedStamp.matchesRetransmission(ownerContext,
                    receiving, prepared, packet, exactActor, exactWorld, presentation,
                    acceptedPresentationActor, acceptedPresentationSnapshot);
            if (!stampCurrent) {
                invalidateSuccessfulInstallation();
                receiving.revokePreparedInstallation(prepared);
                rejectDecode(exactDecode);
                return;
            }
            synchronized (manager) {
                if (isBrokenActor(exactActor) || receiving.installationFenceActive(exactActor)
                        || !isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving,
                        ownerContext, packet, mc) || !receiving.installationCurrent(prepared)) {
                    receiving.revokePreparedInstallation(prepared);
                    rejectDecode(exactDecode);
                    return;
                }
                SuccessfulInstallationStamp retransmitted = installedStamp.publishRetransmission(ownerContext,
                        receiving, prepared, packet, exactActor, exactWorld, presentation,
                        acceptedPresentationActor, acceptedPresentationSnapshot,
                        () -> PlayerPhysicsInboundConnections.INSTANCE.finishDecode(exactDecode,
                                PlayerPhysicsInboundConnections.DecodeOutcome.PROOF_ACCEPTED));
                if (retransmitted == null) {
                    if (session != receiving || acceptedOfferContext != ownerContext || scopeHandle != exactScope) {
                        receiving.revokeStateAcknowledgements();
                        if (!exactDecode.completed()) rejectDecode(exactDecode);
                        return;
                    }
                    invalidateSuccessfulInstallation();
                    long failedAttempt = markBroken(exactActor, exactWorld, manager, packet.scope());
                    retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                            prepared, packet, exactActor, exactWorld,
                            noGravityCaptured ? originalNoGravity : exactActor.hasNoGravity(), failedAttempt, null);
                    return;
                }
                successfulInstallation = retransmitted;
                successfulInstallSerial = Math.incrementExact(successfulInstallSerial);
                captureSuccessorPredictionQuarantine(ownerContext);
                if (receiving.predictionEligible()) acceptPrediction(ownerContext, packet, prepared);
            }
            return;
        }

        discardSuccessfulInstallationStamp();

        boolean restoreNoGravity = noGravityCaptured ? originalNoGravity : exactActor.hasNoGravity();
        PlayerPhysicalSnapshot snapshot = prepared.snapshot();
        PlayerPhysicsClientMirror.Preparation mirrorPreparation = PlayerPhysicsClientMirror.prepare(exactActor, snapshot);
        if (mirrorPreparation.prepared().isEmpty()) {
            ClientMotionTimingDiagnostics.INSTANCE.rejectedMirror(mirrorPreparation.rejection());
            receiving.revokePreparedInstallation(prepared);
            rejectDecode(exactDecode);
            return;
        }
        PlayerPhysicsClientMirror.PreparedMirror preparedMirror = mirrorPreparation.prepared().orElseThrow();
        PlayerPhysicsClientPresentation.PreparedPresentation preparedPresentation = presentation.prepare(exactActor, snapshot);
        if (!preparedMirror.isBoundTo(exactActor, snapshot)
                || !preparedPresentation.isBoundTo(presentation, exactActor, snapshot)) {
            receiving.revokePreparedInstallation(prepared);
            rejectDecode(exactDecode);
            return;
        }

        final long attemptStamp;
        synchronized (manager) {
            if (!isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, mc) || !receiving.installationCurrent(prepared)
                    || !preparedMirror.isBoundTo(exactActor, snapshot)
                    || !preparedPresentation.isBoundTo(presentation, exactActor, snapshot)
                    || !receiving.beginPreparedInstallation(prepared)) {
                receiving.revokePreparedInstallation(prepared);
                rejectDecode(exactDecode);
                return;
            }
            attemptStamp = markBroken(exactActor, exactWorld, manager, packet.scope());
        }

        boolean retirementStarted = false;
        try {
            PlayerPhysicsClientMirror.BoundsApplied boundsApplied = PlayerPhysicsClientMirror.commitPrepared(preparedMirror);
            if (!stillCurrentInstallation(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, prepared, exactActor, exactWorld, attemptStamp, mc)) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }
            PlayerPhysicsClientMirror.commitRemaining(boundsApplied);
            PlayerPhysicsClientMirror.InstalledProof installedMirrorProof =
                    PlayerPhysicsClientMirror.proveInstalled(boundsApplied).orElse(null);
            if (installedMirrorProof == null) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }
            if (!stillCurrentInstallation(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, prepared, exactActor, exactWorld, attemptStamp, mc)) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }

            boolean oldNoGravity = exactActor.hasNoGravity();
            Optional<Boolean> retiredNoGravity = RemoteBoostClient.retireForPhysics(exactActor);
            if (!stillCurrentInstallation(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, prepared, exactActor, exactWorld, attemptStamp, mc)) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }
            if (!noGravityCaptured) restoreNoGravity = retiredNoGravity.orElse(oldNoGravity);
            synchronized (manager) {
                if (!isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving,
                        ownerContext, packet, mc) || !receiving.installationCurrent(prepared)
                        || !isBrokenAttempt(exactActor, exactWorld, attemptStamp)) {
                    // The lock-protected revoke happens below; callbacks and actor cleanup stay outside it.
                } else if (!noGravityCaptured) {
                    originalNoGravity = restoreNoGravity;
                    noGravityCaptured = true;
                }
            }
            if (!stillCurrentInstallation(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, prepared, exactActor, exactWorld, attemptStamp, mc)) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }
            exactActor.setNoGravity(true);
            if (!stillCurrentInstallation(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, prepared, exactActor, exactWorld, attemptStamp, mc)) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }

            boolean installed = false;
            synchronized (manager) {
                if (isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving,
                        ownerContext, packet, mc) && receiving.installationCurrent(prepared)
                        && preparedMirror.isBoundTo(exactActor, snapshot)
                        && preparedPresentation.isBoundTo(presentation, exactActor, snapshot)
                        && isBrokenAttempt(exactActor, exactWorld, attemptStamp)
                        && installedMirrorProof.matchesCurrent(exactActor, snapshot)) {
                    SuccessfulInstallationStamp completed = completeInstalledState(ownerContext, receiving,
                            prepared, packet, exactActor, exactWorld, installedMirrorProof, presentation,
                            preparedPresentation, () -> {
                                PlayerPhysicsInboundConnections.INSTANCE.finishInstalledDecode(exactDecode, exactScope);
                            });
                    if (completed != null) {
                        var environmentOwner = freeEnvironmentOwner;
                        if (environmentOwner != null && environmentOwner.session == receiving
                                && environmentOwner.actor == exactActor && environmentOwner.world == exactWorld
                                && environmentOwner.scope.equals(snapshot.scope())
                                && environmentOwner.pendingCorrection != null) {
                            var completion = environmentOwner.pendingCorrection;
                            if (!completion.completed(receiving, prepared, packet) || session != receiving
                                    || acceptedOfferContext != ownerContext || scopeHandle != exactScope)
                                throw new IllegalStateException("Completed Free correction lost its installed provenance");
                            environmentOwner.resources.completeCorrection(environmentOwner.correctionTransfer,
                                    completion.correctionId(), () -> {
                                        if (freeEnvironmentOwner != environmentOwner || !completion.completed(receiving, prepared, packet)
                                                || session != receiving || acceptedOfferContext != ownerContext || scopeHandle != exactScope
                                    || player != exactActor || world != exactWorld || connection != exactHandler
                                    || connectionHandle != ownerContext.connection || mc.player != exactActor || mc.world != exactWorld)
                                            throw new IllegalStateException("Free correction changed across its completion clock");
                                    });
                            if (freeEnvironmentOwner != environmentOwner || !completion.completed(receiving, prepared, packet)
                                    || session != receiving || acceptedOfferContext != ownerContext || scopeHandle != exactScope
                                    || player != exactActor || world != exactWorld || connection != exactHandler
                                    || connectionHandle != ownerContext.connection || mc.player != exactActor || mc.world != exactWorld)
                                throw new IllegalStateException("Free correction changed during accounting completion");
                            environmentOwner.correction = completion.correctionId();
                            environmentOwner.pendingCorrection = null;
                            environmentOwner.correctionTransfer = null;
                        }
                        if (environmentOwner != null && environmentOwner.session == receiving
                                && environmentOwner.actor == exactActor && environmentOwner.world == exactWorld
                                && environmentOwner.scope.equals(packet.scope()) && environmentOwner.pendingResume != null) {
                            var resume = environmentOwner.pendingResume;
                            if (environmentOwner.resumeContext != ownerContext || environmentOwner.resumeScope != exactScope
                                    || !resume.completed(receiving, prepared, packet) || session != receiving
                                    || acceptedOfferContext != ownerContext || scopeHandle != exactScope)
                                throw new IllegalStateException("Free resources lost their matching delegated resume installation");
                            environmentOwner.resources.completeResume(environmentOwner.resumeTransfer, () -> {
                                if (freeEnvironmentOwner != environmentOwner || !resume.completed(receiving, prepared, packet)
                                        || environmentOwner.resumeContext != ownerContext || environmentOwner.resumeScope != exactScope
                                        || session != receiving || acceptedOfferContext != ownerContext || scopeHandle != exactScope
                                    || player != exactActor || world != exactWorld || connection != exactHandler
                                    || connectionHandle != ownerContext.connection || mc.player != exactActor || mc.world != exactWorld)
                                    throw new IllegalStateException("Free resume changed across its completion clock");
                            });
                            if (freeEnvironmentOwner != environmentOwner || !resume.completed(receiving, prepared, packet)
                                    || session != receiving || acceptedOfferContext != ownerContext || scopeHandle != exactScope
                                    || player != exactActor || world != exactWorld || connection != exactHandler
                                    || connectionHandle != ownerContext.connection || mc.player != exactActor || mc.world != exactWorld)
                                throw new IllegalStateException("Free resources changed during delegated resume completion");
                            environmentOwner.correction = -1;
                            environmentOwner.pendingResume = null;
                            environmentOwner.resumeTransfer = null;
                            environmentOwner.resumeContext = null;
                            environmentOwner.resumeScope = null;
                        }
                        clearBroken(exactActor);
                        successfulInstallation = completed;
                        successfulInstallSerial = Math.incrementExact(successfulInstallSerial);
                        acceptedPresentationActor = exactActor;
                        acceptedPresentationSnapshot = snapshot;
                        captureSuccessorPredictionQuarantine(ownerContext);
                        installed = true;
                    }
                }
            }
            if (installed && receiving.predictionEligible()) acceptPrediction(ownerContext, packet, prepared);
            if (!installed) {
                retirementStarted = true;
                retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, null);
                return;
            }
        } catch (Throwable originalFailure) {
            if (retirementStarted) throw originalFailure;
            Optional<PlayerPhysicsProtocol.Packet> request;
            try {
                request = retireFailedInstallation(exactDecode, exactScope, exactHandler, receiving, ownerContext,
                        prepared, packet, exactActor, exactWorld, restoreNoGravity, attemptStamp, originalFailure);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
                throw originalFailure;
            }
            if (request.isPresent() && isBrokenAttempt(exactActor, exactWorld, attemptStamp))
                throw new RetiredInstallationFailure(originalFailure, exactDecode, ownerContext,
                        attemptStamp, request.orElseThrow());
            throw originalFailure;
        }
        ClientMotionTimingDiagnostics.INSTANCE.appliedState(snapshot);
    }

    private boolean isCurrentScopedContext(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, NetHandlerPlayClient exactHandler,
            PlayerPhysicsClientSession receiving, AcceptedOfferContext ownerContext,
            PlayerPhysicsProtocol.Packet packet, Minecraft mc) {
        NetworkManager manager = ownerContext.manager;
        return mc.getConnection() == exactHandler && mc.player == ownerContext.actor
                && mc.world == ownerContext.world && ownerContext.actor.world == ownerContext.world
                && ownerContext.actor.getUniqueID().equals(ownerContext.offer.scope().playerId())
                && ownerContext.actor.dimension == ownerContext.offer.scope().dimension()
                && manager.getNetHandler() == exactHandler
                && exactHandler.getNetworkManager() == manager && connection == exactHandler
                && connectionHandle == ownerContext.connection && session == receiving && ownerContext.session == receiving
                && ownerContext.handler == exactHandler && ownerContext.actor == player
                && ownerContext.world == world && ownerContext.manager == manager
                && connectionHandle == ownerContext.connection
                && acceptedOfferContext == ownerContext && scopeHandle == exactScope
                && receiving.currentAcceptedOfferIdentity(ownerContext.offer)
                && ownerContext.offer.scope().equals(packet.scope())
                && PlayerPhysicsInboundConnections.INSTANCE.scopeFromAcceptedOffer(exactScope,
                ownerContext.decode, ownerContext.connection, ownerContext.offer.scope())
                && PlayerPhysicsInboundConnections.INSTANCE.isCurrentScopedDecode(exactDecode, exactScope, packet);
    }

    private boolean stillCurrentInstallation(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, NetHandlerPlayClient exactHandler,
            PlayerPhysicsClientSession receiving, AcceptedOfferContext ownerContext,
            PlayerPhysicsProtocol.Packet packet, PlayerPhysicsClientSession.PreparedInstallation prepared,
            EntityPlayerSP exactActor, World exactWorld, long attemptStamp, Minecraft mc) {
        NetworkManager manager = ownerContext.manager;
        synchronized (manager) {
            return isCurrentScopedContext(exactDecode, exactScope, exactHandler, receiving,
                    ownerContext, packet, mc) && receiving.installationCurrent(prepared)
                    && exactActor.world == exactWorld && isBrokenAttempt(exactActor, exactWorld, attemptStamp);
        }
    }

    private Optional<PlayerPhysicsProtocol.Packet> retireFailedInstallation(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, NetHandlerPlayClient exactHandler,
            PlayerPhysicsClientSession receiving, AcceptedOfferContext ownerContext,
            PlayerPhysicsClientSession.PreparedInstallation prepared, PlayerPhysicsProtocol.Packet exactFailed, EntityPlayerSP exactActor,
            World exactWorld, boolean restoreNoGravity, long attemptStamp, Throwable originalFailure) throws Throwable {
        NetworkManager manager = ownerContext.manager;
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        boolean exactOwnerRetired = false;
        boolean physicalCleanupComplete = false;
        Optional<PlayerPhysicsProtocol.Packet> request = Optional.empty();
        Throwable cleanupFailure = null;
        synchronized (manager) {
            exactOwnerRetired = session == receiving && acceptedOfferContext == ownerContext
                    && scopeHandle == exactScope && connectionHandle == ownerContext.connection;
            try {
                if (exactOwnerRetired) receiving.revokeStateAcknowledgements();
                if (originalFailure == null) receiving.revokePreparedInstallation(prepared);
                else {
                    if (receiving.failPreparedInstallation(prepared, originalFailure))
                        request = receiving.recoveryRequestForFailure(exactFailed);
                }
            } catch (Throwable failure) {
                cleanupFailure = combineFailure(cleanupFailure, failure);
            }
            // A completed exact task has already released its budget slot; retirement is not a second decode.
            try { if (!exactDecode.completed()) facade.expireDecode(exactDecode); }
            catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }
            try { facade.retireScope(exactScope); }
            catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }
            if (scopeHandle == exactScope) scopeHandle = null;
            if (acceptedOfferContext == ownerContext) acceptedOfferContext = null;
            if (connectionHandle == ownerContext.connection && connection == exactHandler) {
                connection = null;
                connectionHandle = null;
            }
        }
        if (originalFailure == null && !exactDecode.completed()) {
            try { rejectDecode(exactDecode); }
            catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }
        }
        if (exactOwnerRetired) {
            try {
                physicalCleanupComplete = cleanupRetiredPhysicalState(exactActor, exactWorld, receiving, ownerContext,
                        exactScope, restoreNoGravity, attemptStamp);
            } catch (Throwable failure) {
                cleanupFailure = combineFailure(cleanupFailure, failure);
            }
        }
        if (cleanupFailure != null) throw cleanupFailure;
        return exactOwnerRetired && physicalCleanupComplete ? request : Optional.empty();
    }

    private boolean cleanupRetiredPhysicalState(EntityPlayerSP exactActor, World exactWorld,
            PlayerPhysicsClientSession exactSession, AcceptedOfferContext exactContext,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, boolean restoreNoGravity,
            long attemptStamp) throws Throwable {
        return cleanupRetiredPhysicalState(exactActor, exactWorld, exactSession, exactContext, exactScope,
                restoreNoGravity, attemptStamp, () -> true);
    }

    private boolean cleanupRetiredPhysicalState(EntityPlayerSP exactActor, World exactWorld,
            PlayerPhysicsClientSession exactSession, AcceptedOfferContext exactContext,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, boolean restoreNoGravity,
            long attemptStamp, BooleanSupplier retirementCurrent) throws Throwable {
        CleanupWriter writer = new CleanupWriter(exactContext);
        cleanupWriters.addLast(writer);
        try {
            return cleanupRetiredPhysicalStateBody(exactActor, exactWorld, exactSession, exactContext, exactScope,
                    restoreNoGravity, attemptStamp, retirementCurrent, writer);
        } finally {
            if (cleanupWriters.removeLast() != writer) throw new IllegalStateException("Physical cleanup writer stack mismatch");
        }
    }

    private boolean cleanupRetiredPhysicalStateBody(EntityPlayerSP exactActor, World exactWorld,
            PlayerPhysicsClientSession exactSession, AcceptedOfferContext exactContext,
            PlayerPhysicsInboundConnections.ScopeHandle exactScope, boolean restoreNoGravity,
            long attemptStamp, BooleanSupplier retirementCurrent, CleanupWriter writer) throws Throwable {
        Minecraft mc = Minecraft.getMinecraft();
        NetworkManager manager = exactContext.manager;
        if (!retirementCurrent.getAsBoolean() || !mc.isCallingFromMinecraftThread() || mc.player != exactActor || mc.world != exactWorld
                || exactActor.world != exactWorld) return false;
        synchronized (manager) {
            if (!retirementCurrent.getAsBoolean() || !isBrokenAttempt(exactActor, exactWorld, attemptStamp)
                    || session != exactSession || acceptedOfferContext != null
                    || scopeHandle != null || connection != null || connectionHandle != null
                    || manager.getNetHandler() != exactContext.handler
                    || exactContext.handler.getNetworkManager() != manager
                    || mc.getConnection() != exactContext.handler)
                return false;
        }
        boolean resetComplete = false;
        Throwable cleanupFailure = null;
        writer.possiblyPartial = true;
        try { RemoteBoostClient.retireForPhysics(exactActor); }
        catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }

        boolean sameRetiredOwner;
        synchronized (manager) {
            sameRetiredOwner = retirementCurrent.getAsBoolean() && isBrokenAttempt(exactActor, exactWorld, attemptStamp)
                    && session == exactSession && acceptedOfferContext == null
                    && scopeHandle == null && connection == null && connectionHandle == null
                    && manager.getNetHandler() == exactContext.handler
                    && exactContext.handler.getNetworkManager() == manager
                    && mc.getConnection() == exactContext.handler
                    && mc.player == exactActor && mc.world == exactWorld && exactActor.world == exactWorld;
        }
        if (sameRetiredOwner) {
            try { exactActor.setNoGravity(restoreNoGravity); }
            catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }
            try {
                synchronized (manager) {
                    if (retirementCurrent.getAsBoolean() && isBrokenAttempt(exactActor, exactWorld, attemptStamp)
                            && session == exactSession && acceptedOfferContext == null && scopeHandle == null
                            && connection == null && connectionHandle == null
                            && manager.getNetHandler() == exactContext.handler
                            && exactContext.handler.getNetworkManager() == manager
                            && mc.getConnection() == exactContext.handler
                            && mc.player == exactActor && mc.world == exactWorld && exactActor.world == exactWorld) {
                        resetPresentation();
                        session.reset();
                        noGravityCaptured = false;
                        resetComplete = true;
                    }
                }
            } catch (Throwable failure) { cleanupFailure = combineFailure(cleanupFailure, failure); }
        }
        if (cleanupFailure != null) throw cleanupFailure;
        return resetComplete;
    }

    private static Throwable combineFailure(Throwable earlier, Throwable later) {
        if (earlier == null) return later;
        if (later != earlier) earlier.addSuppressed(later);
        return earlier;
    }

    private static boolean targetMatchesPacket(NetHandlerPlayClient handler, EntityPlayerSP actor,
            World currentWorld, PlayerPhysicsProtocol.Packet packet) {
        return actor != null && currentWorld != null && actor == Minecraft.getMinecraft().player
                && actor.world == currentWorld && handler == Minecraft.getMinecraft().getConnection()
                && actor.getUniqueID().equals(packet.scope().playerId())
                && actor.dimension == packet.scope().dimension();
    }

    private void retireLegacy(EntityPlayerSP exactActor, World exactWorld) {
        if (exactActor.world != exactWorld || isBrokenActor(exactActor)) return;
        if (!noGravityCaptured) { originalNoGravity = exactActor.hasNoGravity(); noGravityCaptured = true; }
        originalNoGravity = RemoteBoostClient.retireForPhysics(exactActor).orElse(originalNoGravity);
    }

    private void rejectDecode(PlayerPhysicsInboundConnections.DecodeHandle exactDecode) {
        PlayerPhysicsInboundConnections.INSTANCE.finishDecode(exactDecode,
                PlayerPhysicsInboundConnections.DecodeOutcome.REJECTED);
    }

    private boolean isBrokenActor(EntityPlayer actor) {
        synchronized (brokenInstallations) {
            for (int i = brokenInstallations.size() - 1; i >= 0; i--) {
                BrokenActorInstallation installation = brokenInstallations.get(i);
                if (installation.collected()) {
                    brokenInstallations.remove(i);
                    continue;
                }
                if (installation.matchesActor(actor)) return true;
            }
            return false;
        }
    }

    private long markBroken(EntityPlayer actor, World actualWorld, NetworkManager manager,
            PlayerPhysicalSnapshot.Scope scope) {
        synchronized (brokenInstallations) {
            for (int i = brokenInstallations.size() - 1; i >= 0; i--) {
                BrokenActorInstallation installation = brokenInstallations.get(i);
                if (installation.collected()) brokenInstallations.remove(i);
                else if (installation.matchesActorIdentity(actor)) {
                    long stamp = Math.incrementExact(nextBrokenAttemptStamp);
                    nextBrokenAttemptStamp = stamp;
                    installation.beginAttempt(actualWorld, stamp);
                    installation.recordScope(manager, scope);
                    return stamp;
                }
            }
            long stamp = Math.incrementExact(nextBrokenAttemptStamp);
            nextBrokenAttemptStamp = stamp;
            BrokenActorInstallation installation = new BrokenActorInstallation(actor, actualWorld, stamp, UUID.randomUUID());
            installation.recordScope(manager, scope);
            brokenInstallations.add(installation);
            return stamp;
        }
    }

    private boolean isBrokenAttempt(EntityPlayer actor, World actualWorld, long attemptStamp) {
        synchronized (brokenInstallations) {
            for (BrokenActorInstallation installation : brokenInstallations) {
                if (!installation.collected()
                        && installation.matchesAttempt(actor, actualWorld, attemptStamp)) return true;
            }
            return false;
        }
    }

    private void clearBroken(EntityPlayer actor) {
        synchronized (brokenInstallations) {
            brokenInstallations.removeIf(BrokenActorInstallation::collected);
            for (BrokenActorInstallation installation : brokenInstallations) {
                if (installation.matchesActorIdentity(actor)) installation.complete();
            }
        }
    }

    /** A live delegated owner may negotiate a new proposal without replaying its installed epoch. */
    private boolean allowsHealthyDelegatedResumeOffer(PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsInboundConnections.ConnectionHandle exactConnection, NetHandlerPlayClient exactHandler,
            PlayerPhysicsProtocol.Packet packet, EntityPlayerSP actor, World exactWorld, Minecraft mc,
            NetworkManager manager) {
        AcceptedOfferContext owner = acceptedOfferContext;
        PlayerPhysicsClientSession receiving = session;
        PlayerPhysicsInboundConnections.ScopeHandle retainedScope = scopeHandle;
        long serial = successfulInstallSerial;
        ClientFreeEnvironmentOwner retainedEnvironment = freeEnvironmentOwner;
        BooleanSupplier current = () -> owner != null && receiving != null && retainedScope != null
                && acceptedOfferContext == owner && session == receiving && scopeHandle == retainedScope
                && successfulInstallSerial == serial && owner.actor == actor && owner.world == exactWorld
                && owner.manager == manager && owner.handler == exactHandler && owner.connection == exactConnection
                && owner.session == receiving && acceptedOwnerLifetimeCurrent(owner, mc)
                && (receiving.allowsHealthyDelegatedResumeOffer(packet)
                    || retainedEnvironment != null && freeEnvironmentOwner == retainedEnvironment
                        && retainedEnvironment.session == receiving && retainedEnvironment.actor == actor
                        && retainedEnvironment.world == exactWorld && retainedEnvironment.scope.equals(packet.scope())
                        && retainedEnvironment.resumeContext == owner && retainedEnvironment.resumeScope == retainedScope
                        && retainedEnvironment.pendingResume != null
                        && retainedEnvironment.pendingResume.nextOffer(receiving, packet) != null)
                && PlayerPhysicsInboundConnections.INSTANCE.decodeCurrent(exactDecode)
                && PlayerPhysicsInboundConnections.INSTANCE.scopeFromAcceptedOffer(
                        retainedScope, owner.decode, exactConnection, owner.offer.scope());
        if (!current.getAsBoolean()) return false;
        boolean alive = actor.isEntityAlive();
        if (!alive || actor.isDead || !current.getAsBoolean()) return false;
        synchronized (brokenInstallations) {
            for (BrokenActorInstallation installation : brokenInstallations) {
                // An active partial write is never bypassed, even by an otherwise current owner.
                if (installation.matchesActor(actor)) return false;
            }
        }
        return current.getAsBoolean();
    }

    private boolean allowsOfferAfterInstallation(EntityPlayer actor, NetworkManager manager,
            PlayerPhysicalSnapshot.Scope scope) {
        synchronized (brokenInstallations) {
            brokenInstallations.removeIf(BrokenActorInstallation::collected);
            for (BrokenActorInstallation installation : brokenInstallations) {
                if (installation.matchesActorIdentity(actor) && !installation.allowsOffer(manager, scope)) return false;
            }
            return true;
        }
    }

    /** Fences both Session-owned failures and partial actor writes that outlive Session reset. */
    public static boolean isInstallationFenced(EntityPlayer actor) {
        if (actor == null) return false;
        if (INSTANCE.isBrokenActor(actor)) return true;
        if (INSTANCE.session != null && actor == INSTANCE.player && INSTANCE.session.predictionMutationQuarantined(actor)) return true;
        Minecraft mc = Minecraft.getMinecraft();
        if (actor != mc.player || INSTANCE.session == null) return false;
        AcceptedOfferContext context = INSTANCE.acceptedOfferContext;
        if (context != null && !INSTANCE.acceptedOwnerLifetimeCurrent(context, mc)) return true;
        return INSTANCE.session.installationFenceActive(actor);
    }

    /** The OFFER remains the custody root while protocol generations advance on accepted traffic. */
    private boolean acceptedOwnerLifetimeCurrent(AcceptedOfferContext context, Minecraft mc) {
        NetworkManager manager = context.manager;
        NetHandlerPlayClient handler = context.handler;
        return context == acceptedOfferContext && context.session == session
                && context.connection == connectionHandle && context.actor == player && context.world == world
                && context.actor == mc.player && context.world == mc.world && context.connection != null
                && connection == handler && handler.getNetworkManager() == manager
                && manager.getNetHandler() == handler && mc.getConnection() == handler
                && context.actor.world == context.world && context.actor.dimension == context.offer.scope().dimension()
                && context.actor.getUniqueID().equals(context.offer.scope().playerId())
                && context.session.currentAcceptedOfferIdentity(context.offer)
                && PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(manager, handler,
                EnumPacketDirection.CLIENTBOUND).filter(current -> current == context.connection).isPresent();
    }

    public static void runFreePosePublication(EntityPlayer actor, Runnable publisher) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(publisher, "publisher");
        if (isInstallationFenced(actor)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (actor == mc.player && INSTANCE.session != null) {
            INSTANCE.session.runFreePosePublication(actor, publisher);
            return;
        }
        publisher.run();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable failure) throws T {
        throw (T) failure;
    }

    public static boolean owns(EntityPlayer actor) {
        Minecraft mc = Minecraft.getMinecraft();
        return !isInstallationFenced(actor) && actor == mc.player
                && INSTANCE.targets(mc.getConnection(), mc.player, mc.world)
                && INSTANCE.session != null && INSTANCE.session.owns();
    }

    public static boolean drivesMotion(EntityPlayer actor) {
        return !isInstallationFenced(actor) && owns(actor) && INSTANCE.session.drivesMotion()
                && (!PlayerKinematics.requiresNative(actor) || INSTANCE.session.nativeMovementMustRemainGated());
    }

    public static Optional<PlayerMobilityIntent.State> mobilityState() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP actor = minecraft.player;
        if (isInstallationFenced(actor) || actor == null || !INSTANCE.targets(minecraft.getConnection(), actor, minecraft.world)
                || INSTANCE.session == null) return Optional.empty();
        return INSTANCE.session.mobilityState();
    }

    public static Optional<PlayerMobilityIntent.Request> pendingMobilityRequest() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP actor = minecraft.player;
        if (actor == null || !INSTANCE.targets(minecraft.getConnection(), actor, minecraft.world)
                || INSTANCE.session == null) return Optional.empty();
        return INSTANCE.session.pendingMobilityRequest();
    }

    /** GUI and command callers share the accepted owner, Creative, and confirmed-G gates. */
    public static boolean requestMobility(PlayerMobilityIntent.Mode mode, PlayerMobilityIntent.Hover hover) {
        Objects.requireNonNull(mode); Objects.requireNonNull(hover);
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread())
            throw new IllegalStateException("Mobility request off client thread");
        EntityPlayerSP actor = minecraft.player;
        if (actor == null || !actor.isCreative() || !drivesMotion(actor)
                || INSTANCE.session == null) return false;
        boolean requiresConfirmedG = mode == PlayerMobilityIntent.Mode.FLIGHT
                || hover == PlayerMobilityIntent.Hover.ON;
        if (requiresConfirmedG && !ClientGravityTicker.isPersonalZeroGActive(actor)) return false;
        return INSTANCE.session.requestMobility(mode, hover);
    }

    /** Explicit local seam for later camera/input wiring; pending requests never alter the accepted mirror. */
    public static OptionalLong requestBodyReferenceLock(CardinalFrame frame, Vec3d tangentHeading) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Body reference request off client thread");
        if (isInstallationFenced(mc.player) || !INSTANCE.targets(mc.getConnection(), mc.player, mc.world) || INSTANCE.session == null
                || !INSTANCE.session.drivesMotion()) return OptionalLong.empty();
        return OptionalLong.of(INSTANCE.session.requestBodyReferenceLock(frame, tangentHeading));
    }

    public static OptionalLong requestBodyReferenceUnlock() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Body reference request off client thread");
        if (isInstallationFenced(mc.player) || !INSTANCE.targets(mc.getConnection(), mc.player, mc.world) || INSTANCE.session == null
                || !INSTANCE.session.drivesMotion()) return OptionalLong.empty();
        return OptionalLong.of(INSTANCE.session.requestBodyReferenceUnlock());
    }

    public static Optional<PlayerPhysicsClientSession.BodyReferenceView> bodyReferenceStatus() {
        Minecraft mc = Minecraft.getMinecraft();
        if (isInstallationFenced(mc.player) || !INSTANCE.targets(mc.getConnection(), mc.player, mc.world) || INSTANCE.session == null)
            return Optional.empty();
        return Optional.of(INSTANCE.session.bodyReferenceStatus());
    }

    /** Effective frame/tangent from the same installed geometry exposed to local presentation. */
    public static Optional<PlayerBodyReference.Effective> acceptedEffectiveBodyReference(EntityPlayer actor) {
        var accepted = currentProvenPresentation(actor);
        if (accepted.isEmpty() || INSTANCE.session == null) return Optional.empty();
        // Physical controls always consume the server-accepted reference, never a predicted attitude.
        var authority = INSTANCE.session.presentationSnapshot();
        return authority.flatMap(INSTANCE.session::acceptedEffectiveBodyReference);
    }

    /** Accepted-only eye retains its exact authority geometry contract. */
    public static Optional<Vec3d> acceptedEyePosition(EntityPlayer actor, float partialTicks) {
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("partialTicks must be finite");
        var accepted = acceptedPresentation(actor);
        if (accepted.isEmpty() || INSTANCE.session == null) return Optional.empty();
        PlayerPhysicalSnapshot snapshot = accepted.orElseThrow();
        if (snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
            return Optional.ofNullable(INSTANCE.presentation.acceptedFreeEyePosition(actor, snapshot, partialTicks));
        return INSTANCE.session.acceptedEffectiveBodyReference(snapshot).map(reference ->
                INSTANCE.presentation.acceptedEyePosition(actor, snapshot, reference, partialTicks));
    }

    /** One current proven placement for camera, quaternion, footprint and render providers; never ACK authority. */
    public static Optional<PlayerPhysicalSnapshot> currentProvenPresentation(EntityPlayer actor) {
        var exact = INSTANCE.prediction;
        var context = INSTANCE.acceptedOfferContext;
        if (exact == null) return acceptedPresentation(actor);
        if (context == null || !INSTANCE.predictionMutations.isEmpty() || !INSTANCE.cleanupWriters.isEmpty()
                || !exact.current(context, actor) || INSTANCE.session == null
                || !INSTANCE.session.predictionEligible() || isInstallationFenced(actor)
                || PlayerKinematics.requiresNative(actor)
                || !INSTANCE.acceptedOwnerLifetimeCurrent(context, Minecraft.getMinecraft())
                || INSTANCE.session.presentationSnapshot().orElse(null) != exact.authority()) return Optional.empty();
        var projection = exact.predictedSnapshot();
        long serial = INSTANCE.successfulInstallSerial;
        if (!ClientCardinalPredictionOwner.matchesCompleteProjection(actor, projection,
                () -> INSTANCE.prediction == exact && INSTANCE.successfulInstallSerial == serial
                        && INSTANCE.session != null && INSTANCE.session.predictionEligible()
                        && INSTANCE.acceptedOwnerLifetimeCurrent(context, Minecraft.getMinecraft())
                        && INSTANCE.session.presentationSnapshot().orElse(null) == exact.authority())) return Optional.empty();
        // The native bounds getter may invoke third-party code; verify the complete identity again afterwards.
        return INSTANCE.prediction == exact && exact.current(context, actor) && INSTANCE.session.predictionEligible()
                && INSTANCE.acceptedOwnerLifetimeCurrent(context, Minecraft.getMinecraft())
                && INSTANCE.session.presentationSnapshot().orElse(null) == exact.authority()
                ? Optional.of(projection) : Optional.empty();
    }

    public static Optional<Vec3d> currentProvenEyePosition(EntityPlayer actor, float partialTicks) {
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("partialTicks must be finite");
        if (INSTANCE.prediction == null) return acceptedEyePosition(actor, partialTicks);
        var exact = INSTANCE.prediction;
        if (currentProvenPresentation(actor).isEmpty() || INSTANCE.session.acceptedEffectiveBodyReference(exact.authority()).isEmpty())
            return Optional.empty();
        var eye = exact.eye(partialTicks);
        return INSTANCE.prediction == exact && currentProvenPresentation(actor).isPresent() ? Optional.ofNullable(eye) : Optional.empty();
    }

    /** Current local cardinal mirror eligible for read-only model presentation; never installs state. */
    public static Optional<PlayerPhysicalSnapshot> acceptedPresentation(EntityPlayer actor) {
        Minecraft mc = Minecraft.getMinecraft();
        if (isInstallationFenced(actor) || !(actor instanceof EntityPlayerSP localPlayer)
                || actor != mc.player || !actor.isEntityAlive()
                || actor.world != mc.world || PlayerKinematics.requiresNative(actor)
                || !INSTANCE.targets(mc.getConnection(), localPlayer, mc.world) || INSTANCE.session == null) {
            return Optional.empty();
        }
        var accepted = INSTANCE.session.presentationSnapshot();
        if (accepted.isEmpty()) return Optional.empty();
        PlayerPhysicalSnapshot snapshot = accepted.orElseThrow();
        if (!snapshot.scope().playerId().equals(actor.getUniqueID())
                || snapshot.scope().dimension() != actor.dimension
                || !(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal)
                    && !(snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, snapshot)) {
            return Optional.empty();
        }
        return accepted;
    }

    /** Cardinal geometry is used only to shape Aqua's clearance query, never to install an owner. */
    public static AxisAlignedBB aquaPoseClearanceBox(EntityPlayer actor, AxisAlignedBB aquaCandidate) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(aquaCandidate, "aquaCandidate");
        PlayerPhysicalSnapshot accepted = acceptedPresentation(actor).orElse(null);
        return AquaCardinalPoseClearance.candidate(actor, accepted, aquaCandidate);
    }

    public static float physicalEyeHeight(EntityPlayer actor, float nativeHeight) {
        return !isInstallationFenced(actor) && drivesMotion(actor)
                ? INSTANCE.session.physicalEyeHeight(actor, nativeHeight) : nativeHeight;
    }

    private static final class ClientFreeEnvironmentOwner {
        private final PlayerPhysicsClientSession session;
        private final EntityPlayerSP actor;
        private final World world;
        private final PlayerPhysicalSnapshot.Scope scope;
        private long correction;
        private PlayerPhysicsClientSession.NativeEnvironmentCorrection pendingCorrection;
        private FreeTickResources.Store.NativeCorrectionTransfer correctionTransfer;
        private PlayerPhysicsClientSession.NativeEnvironmentResume pendingResume;
        private FreeTickResources.Store.NativeResumeTransfer resumeTransfer;
        private AcceptedOfferContext resumeContext;
        private PlayerPhysicsInboundConnections.ScopeHandle resumeScope;
        private final ClientFreeEnvironmentResources resources;
        private ClientFreeEnvironmentOwner(PlayerPhysicsClientSession session, EntityPlayerSP actor,
                PlayerPhysicalSnapshot accepted, long correction, LongSupplier simulationTicks) {
            this.session = session;
            this.actor = actor;
            world = actor.world;
            scope = accepted.scope();
            this.correction = correction;
            resources = new ClientFreeEnvironmentResources(this, world, scope, correction, simulationTicks);
        }
    }

    private final class ClientNativeEnvironmentScope {
        private final PlayerPhysicsClientSession exactSession;
        private final AcceptedOfferContext context;
        private final PlayerPhysicsInboundConnections.ScopeHandle scopeHandle;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsClientSession.NativeEnvironmentLifetime provenance;
        private final ClientFreeEnvironmentOwner owner;
        private FreeNativeEnvironment observer;
        private ClientNativeEnvironmentScope(EntityPlayerSP actor, PlayerPhysicalSnapshot accepted, Minecraft mc) {
            exactSession = session;
            context = acceptedOfferContext;
            scopeHandle = PlayerPhysicsClient.this.scopeHandle;
            this.accepted = accepted;
            provenance = exactSession.nativeEnvironmentLifetime(accepted);
            var previous = freeEnvironmentOwner;
            if (previous != null && (previous.session != exactSession || previous.actor != actor
                    || previous.world != actor.world || !previous.scope.equals(accepted.scope()))) {
                previous.resources.invalidate();
                freeEnvironmentOwner = null;
            }
            if (freeEnvironmentOwner == null)
                freeEnvironmentOwner = new ClientFreeEnvironmentOwner(exactSession, actor, accepted, provenance.correctionId(),
                        () -> nativeSubmissionTick);
            owner = freeEnvironmentOwner;
            try {
                check();
                var resources = owner.resources.acquire(owner.world, owner.scope, provenance.correctionId());
                if (!(owner.world.getChunkProvider() instanceof ChunkProviderClient provider))
                    throw new IllegalStateException("Free mirror has no verified client loaded-chunk provider");
                observer = FreeNativeEnvironment.mirror(accepted, actor, accepted, resources, this::check,
                        provider::getLoadedChunk);
            } catch (RuntimeException cause) { throw abort(cause); }
        }
        private void check() {
            Minecraft mc = Minecraft.getMinecraft();
            if (!mc.isCallingFromMinecraftThread() || session != exactSession || acceptedOfferContext != context
                    || PlayerPhysicsClient.this.scopeHandle != scopeHandle || context == null || scopeHandle == null
                    || !acceptedOwnerLifetimeCurrent(context, mc) || !provenance.current(exactSession)
                    || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(owner.actor, accepted))
                throw new IllegalStateException("Free mirror native environment lost its exact installed lifetime");
        }
        private void checkOperation() {
            try { check(); }
            catch (RuntimeException cause) { throw abort(cause); }
        }
        private boolean fluid(boolean water) {
            try {
                check();
                return water ? observer.waterMovement() : observer.inLava();
            } catch (RuntimeException cause) { throw abort(cause); }
        }
        private AbortClientNativeEnvironment abort(Throwable cause) {
            owner.resources.invalidate();
            return new AbortClientNativeEnvironment(this, exactSession.failNativeEnvironment(provenance, cause));
        }
    }

    private static final class AbortClientNativeEnvironment extends RuntimeException {
        private final ClientNativeEnvironmentScope scope;
        private final PlayerPhysicsClientSession.NativeEnvironmentFailure failure;
        private AbortClientNativeEnvironment(ClientNativeEnvironmentScope scope,
                PlayerPhysicsClientSession.NativeEnvironmentFailure failure) {
            super("Attributed client native environment aborted", failure);
            this.scope = scope;
            this.failure = failure;
        }
    }

    /** Only attributed fluid failures abort this native operation; unrelated original failures propagate. */
    public static void runNativeEnvironment(EntityPlayerSP actor, Runnable original) {
        Objects.requireNonNull(original);
        if (isInstallationFenced(actor)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (actor == mc.player && INSTANCE.session != null && INSTANCE.session.nativeEnvironmentMustPause(actor)) return;
        var accepted = nativeEnvironmentSnapshot(actor, mc);
        if (accepted.isEmpty() || !(accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            original.run();
            return;
        }
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Free environment off client thread");
        var previous = INSTANCE.nativeEnvironmentScope;
        if (previous != null && previous.owner.actor == actor) {
            previous.checkOperation();
            original.run();
            previous.checkOperation();
            return;
        }
        try {
            INSTANCE.nativeEnvironmentScope = INSTANCE.new ClientNativeEnvironmentScope(actor, accepted.orElseThrow(), mc);
            original.run();
            INSTANCE.nativeEnvironmentScope.checkOperation();
        } catch (AbortClientNativeEnvironment abort) {
            var scope = abort.scope;
            INSTANCE.retireNativeFailure(scope.exactSession, scope.context, scope.scopeHandle,
                    abort.failure.getCause(), () -> abort.failure.current(scope.exactSession),
                    "Free native environment", mc);
        } finally { INSTANCE.nativeEnvironmentScope = previous; }
    }

    public static boolean nativeWaterMovement(EntityPlayerSP actor, BooleanSupplier original) {
        return nativeFluid(actor, original, true);
    }
    public static boolean nativeInLava(EntityPlayerSP actor, BooleanSupplier original) {
        return nativeFluid(actor, original, false);
    }
    private static boolean nativeFluid(EntityPlayerSP actor, BooleanSupplier original, boolean water) {
        Objects.requireNonNull(original);
        if (isInstallationFenced(actor)) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (actor == mc.player && INSTANCE.session != null && INSTANCE.session.nativeEnvironmentMustPause(actor)) return false;
        var scope = INSTANCE.nativeEnvironmentScope;
        if (scope != null && scope.owner.actor == actor) return scope.fluid(water);
        var accepted = nativeEnvironmentSnapshot(actor, mc);
        if (accepted.isEmpty() || !(accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.FreeCapsule))
            return original.getAsBoolean();
        boolean[] result = {false};
        runNativeEnvironment(actor, () -> result[0] = INSTANCE.nativeEnvironmentScope.fluid(water));
        return result[0];
    }

    private static Optional<PlayerPhysicalSnapshot> nativeEnvironmentSnapshot(EntityPlayerSP actor, Minecraft mc) {
        if (actor != mc.player || !INSTANCE.targets(mc.getConnection(), actor, mc.world) || INSTANCE.session == null)
            return Optional.empty();
        return INSTANCE.session.nativeEnvironmentSnapshot(actor);
    }

    private int nativePoseDepth;
    private DeadPoseRetirement deadPoseRetirement;
    private record DeadPoseRetirement(AcceptedOfferContext owner,
            PlayerPhysicsInboundConnections.ScopeHandle scope,
            PlayerPhysicsClientSession.NativePoseLease lease, long successfulSerial) { }

    public static void runNativePose(EntityPlayer actor, Runnable original) {
        Minecraft mc = Minecraft.getMinecraft();
        if (isInstallationFenced(actor)) return;
        if (actor != mc.player || !INSTANCE.targets(mc.getConnection(), mc.player, mc.world) || INSTANCE.session == null) {
            original.run(); return;
        }
        PlayerPhysicsClientSession exactSession = INSTANCE.session;
        AcceptedOfferContext exactContext = INSTANCE.acceptedOfferContext;
        PlayerPhysicsInboundConnections.ScopeHandle exactScope = INSTANCE.scopeHandle;
        long serial = INSTANCE.successfulInstallSerial;
        var lease = exactSession.nativePoseLease(actor, () -> INSTANCE.session == exactSession
                && INSTANCE.acceptedOfferContext == exactContext && INSTANCE.scopeHandle == exactScope
                && INSTANCE.successfulInstallSerial == serial && exactContext != null
                && INSTANCE.acceptedOwnerLifetimeCurrent(exactContext, mc));
        Throwable failure = null;
        INSTANCE.nativePoseDepth++;
        try {
            exactSession.runNativePose(actor, original, lease);
        } catch (PlayerPhysicsClientSession.NativePoseRestorationFailure cause) {
            try { INSTANCE.retireNativePoseRestoration(exactSession, exactContext, exactScope, cause, mc); }
            catch (Throwable retirement) { failure = retirement; }
        } catch (Throwable cause) { failure = cause; }
        finally {
            INSTANCE.nativePoseDepth--;
            try {
                INSTANCE.deferDeadPoseRetirement(exactContext, exactScope, lease, serial);
                INSTANCE.drainDeadPoseRetirement();
            } catch (Throwable cleanup) { failure = combineFailure(failure, cleanup); }
        }
        if (failure != null) PlayerPhysicsClient.<RuntimeException>rethrow(failure);
    }

    private void deferDeadPoseRetirement(AcceptedOfferContext owner,
            PlayerPhysicsInboundConnections.ScopeHandle scope,
            PlayerPhysicsClientSession.NativePoseLease lease, long serial) {
        if (owner != null && scope != null && (owner.actor.isDead || !owner.actor.isEntityAlive())
                && lease.identityCurrent() && successfulInstallSerial == serial && !isBrokenActor(owner.actor))
            deadPoseRetirement = new DeadPoseRetirement(owner, scope, lease, serial);
    }

    /** Native callbacks can nest and tail-write after an inner callback returns. Retire only after all writers unwind. */
    private void drainDeadPoseRetirement() {
        if (nativePoseDepth != 0 || !predictionMutations.isEmpty() || !cleanupWriters.isEmpty()) return;
        DeadPoseRetirement task = deadPoseRetirement;
        deadPoseRetirement = null;
        if (task == null || !task.lease.identityCurrent() || successfulInstallSerial != task.successfulSerial
                || !(task.owner.actor.isDead || !task.owner.actor.isEntityAlive()) || isBrokenActor(task.owner.actor)) return;
        // The liveness getter above may reenter a respawn or a new installation.
        if (!task.lease.identityCurrent() || successfulInstallSerial != task.successfulSerial) return;
        var owner = task.owner;
        long stamp = markBroken(owner.actor, owner.world, owner.manager, owner.offer.scope());
        try {
            synchronized (owner.manager) {
                if (!task.lease.identityCurrent() || successfulInstallSerial != task.successfulSerial) return;
                PlayerPhysicsInboundConnections.INSTANCE.retireScope(task.scope);
                scopeHandle = null; acceptedOfferContext = null; connection = null; connectionHandle = null;
            }
            boolean cleaned = cleanupRetiredPhysicalState(owner.actor, owner.world, owner.session, owner, task.scope,
                    originalNoGravity, stamp, () -> task.lease.sessionCurrent() && successfulInstallSerial == task.successfulSerial);
            // A dead owner needs no RECOVER. A real partial/failed cleanup retains its existing fence.
            if (cleaned && successfulInstallSerial == task.successfulSerial && session == owner.session
                    && acceptedOfferContext == null && isBrokenAttempt(owner.actor, owner.world, stamp)) clearBroken(owner.actor);
        } catch (Throwable cause) {
            UnigravMod.LOGGER.error("ugmotion1 dead native-pose owner retirement failed scope " + owner.offer.scope(), cause);
            PlayerPhysicsClient.<RuntimeException>rethrow(cause);
        }
    }

    private void retireNativePoseRestoration(PlayerPhysicsClientSession exactSession,
            AcceptedOfferContext exactContext, PlayerPhysicsInboundConnections.ScopeHandle exactScope,
            PlayerPhysicsClientSession.NativePoseRestorationFailure failure, Minecraft mc) {
        retireNativeFailure(exactSession, exactContext, exactScope, failure.getCause(),
                () -> failure.current(exactSession), "native pose mirror restoration", mc);
    }

    private void retireNativeFailure(PlayerPhysicsClientSession exactSession,
            AcceptedOfferContext exactContext, PlayerPhysicsInboundConnections.ScopeHandle exactScope,
            Throwable cause, BooleanSupplier failureCurrent, String operation, Minecraft mc) {
        if (exactContext == null || exactScope == null || !failureCurrent.getAsBoolean()
                || session != exactSession || acceptedOfferContext != exactContext || scopeHandle != exactScope
                || !acceptedOwnerLifetimeCurrent(exactContext, mc)) {
            PlayerPhysicsClient.<RuntimeException>rethrow(cause);
            return;
        }
        long stamp = markBroken(exactContext.actor, exactContext.world, exactContext.manager, exactContext.offer.scope());
        Optional<PlayerPhysicsProtocol.Packet> request = Optional.empty();
        boolean retired = false;
        boolean recoveryStarted = false;
        try {
            synchronized (exactContext.manager) {
                if (failureCurrent.getAsBoolean() && session == exactSession
                        && acceptedOfferContext == exactContext && scopeHandle == exactScope
                        && acceptedOwnerLifetimeCurrent(exactContext, mc)) {
                    request = exactSession.recoveryRequest();
                    PlayerPhysicsInboundConnections.INSTANCE.retireScope(exactScope);
                    scopeHandle = null;
                    acceptedOfferContext = null;
                    connection = null;
                    connectionHandle = null;
                    retired = true;
                }
            }
            if (retired && cleanupRetiredPhysicalState(exactContext.actor, exactContext.world, exactSession,
                    exactContext, exactScope, originalNoGravity, stamp) && request.isPresent()) {
                recoveryTask = new ClientInstallationRecoveryTask(exactContext.actor, exactContext.world,
                        exactContext.handler, exactContext.manager, exactContext.connection, request.orElseThrow());
                recoveryStarted = true;
            }
        } catch (Throwable cleanup) {
            if (cleanup != cause) cause.addSuppressed(cleanup);
        }
        UnigravMod.LOGGER.error("ugmotion1 " + operation + " failed; exact actor remains fenced; bounded RECOVER "
                + (recoveryStarted ? "pending " : "not started ") + exactContext.offer.scope(), cause);
    }

    public static void runLegacyMotion(EntityPlayer actor, Runnable original) {
        Minecraft mc = Minecraft.getMinecraft();
        if (isInstallationFenced(actor)) return;
        if (actor == mc.player && INSTANCE.targets(mc.getConnection(), mc.player, mc.world) && INSTANCE.session != null)
            INSTANCE.session.runLegacyMotion(actor, original);
        else original.run();
    }

    /** Runs before the existing Remote FRAME hook for the very same native packet. */
    public static Packet<?> prepareOutgoing(NetHandlerPlayClient handler, Packet<?> packet) {
        if (!(packet instanceof CPacketPlayer movement)) return packet;
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) throw new IllegalStateException("Physics movement emission off client thread");
        EntityPlayerSP actor = mc.player;
        if (isInstallationFenced(actor)) {
            AcceptedOfferContext recovery = INSTANCE.acceptedOfferContext;
            if (INSTANCE.isBrokenActor(actor) && recovery != null
                    && INSTANCE.targets(handler, actor, mc.world)
                    && INSTANCE.allowsOfferAfterInstallation(actor, recovery.manager, recovery.offer.scope())
                    && INSTANCE.acceptedOwnerLifetimeCurrent(recovery, mc)) {
                // A retired installation cannot emit input or native position state. A current
                // new OFFER may negotiate APPLY; only its complete OWNED installation clears the fence.
                INSTANCE.session.prepare(movement,
                        new Vec3d(actor.posX, actor.getEntityBoundingBox().minY, actor.posZ),
                        actor.rotationYaw, actor.rotationPitch,
                        RemoteBoostClient.hasCustody(actor), RemoteBoostClient.observeStateApplication(actor),
                        PlayerPhysicsNetwork::sendToServer);
            }
            return packet;
        }
        if (!INSTANCE.targets(handler, mc.player, mc.world) || INSTANCE.session == null) return packet;
        return INSTANCE.session.prepare(movement,
                new Vec3d(actor.posX, actor.getEntityBoundingBox().minY, actor.posZ), actor.rotationYaw, actor.rotationPitch,
                RemoteBoostClient.hasCustody(actor), RemoteBoostClient.observeStateApplication(actor),
                PlayerPhysicsNetwork::sendToServer);
    }

    private boolean targets(NetHandlerPlayClient handler, EntityPlayerSP actor, World currentWorld) {
        if (actor == null || handler == null || connection != handler || player != actor || world != currentWorld
                || actor.dimension != dimension || Minecraft.getMinecraft().getConnection() != handler) return false;
        NetworkManager manager = handler.getNetworkManager();
        return manager != null && manager.getNetHandler() == handler && connectionHandle != null
                && PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(manager, handler,
                EnumPacketDirection.CLIENTBOUND).filter(current -> current == connectionHandle).isPresent();
    }

    /** Publish physical packets only at a simulation boundary, before native entity history is advanced. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beginClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            nativeSubmissionTick = Math.incrementExact(nativeSubmissionTick);
            if (!Minecraft.getMinecraft().isGamePaused()) {
                presentation.beginClientTick(nativeSubmissionTick);
                tickQueue.drain();
            }
        }
    }

    /** After native prevPos reset; retain accepted history and sample controls only once per client tick. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        drainPredictionRetirement();
        advanceRecovery(mc);
        advanceNativeHandoff(mc);
        if (isInstallationFenced(mc.player) || session == null) return;
        if (!targets(mc.getConnection(), mc.player, mc.world)) { clear(); return; }
        if (isInstallationFenced(player)) return;
        if (player.isDead || !player.isEntityAlive()) {
            var context = acceptedOfferContext;
            if (context != null) {
                long serial = successfulInstallSerial;
                var exactSession = session;
                var exactScope = scopeHandle;
                var lease = exactSession.nativePoseLease(player, () -> session == exactSession
                        && acceptedOfferContext == context && scopeHandle == exactScope
                        && successfulInstallSerial == serial && acceptedOwnerLifetimeCurrent(context, mc));
                deferDeadPoseRetirement(context, exactScope, lease, serial);
                drainDeadPoseRetirement();
            }
            return;
        }
        if (!session.drivesMotion() || mc.isGamePaused()) return;
        final var restoreSession = session;
        final var restoreContext = acceptedOfferContext;
        final var restoreStamp = successfulInstallation;
        final long restoreSerial = successfulInstallSerial;
        final var nativeRestoreInput = captureNativeRestoreInput(mc);
        if (session != restoreSession || acceptedOfferContext != restoreContext
                || successfulInstallation != restoreStamp || successfulInstallSerial != restoreSerial) return;
        if (PlayerKinematics.requiresNative(player)) {
            if (nativeRestoreInput == null) resetPresentation();
            else {
                // Visual/prediction retirement cannot revoke an installed restore ACK or its sent base proof.
                if (!resetNativeRestorePresentation(nativeRestoreInput, presentation,
                        () -> retirePrediction("Native restore awaiting transfer"))) return;
                if (!nativeRestoreInput.pending()) return;
            }
        }

        final var viewSession = session;
        final var viewContext = acceptedOfferContext;
        final long viewTick = nativeSubmissionTick;
        final var rawView = ClientBoostRouting.queryViewBasis(player);
        var nativeLook = rawView == null ? player.getLook(1.0F) : null;
        if (session != viewSession || acceptedOfferContext != viewContext || nativeSubmissionTick != viewTick
                || !acceptedOwnerLifetimeCurrent(viewContext, mc) || isInstallationFenced(player)) return;
        final Vec3 headForward = rawView == null ? new Vec3(nativeLook.x, nativeLook.y, nativeLook.z) : rawView.forward();
        final TangentViewSample tangent = nativeRestoreInput != null ? null
                : session.prepareTangentView(player, viewTick, headForward,
                    rawView == null ? player.rotationYaw : null);
        PlayerPhysicsProtocol.Controls controls = ClientBoostRouting.queryPhysical(player, tangent);
        if (session != viewSession || acceptedOfferContext != viewContext || nativeSubmissionTick != viewTick
                || !acceptedOwnerLifetimeCurrent(viewContext, mc) || isInstallationFenced(player)
                || tangent != null && !viewSession.canCommitTangentView(player, tangent)) {
            UnigravMod.LOGGER.warn("ugmotion1 INPUT end refused at controls custody tick {}",viewTick); return;
        }
        if (controls == null) {
            boolean active = mc.inGameHasFocus && mc.currentScreen == null;
            var keys = mc.gameSettings;
            double forward = active ? (ClientKeyBindingState.isDown(keys.keyBindForward) ? 1 : 0)
                    - (ClientKeyBindingState.isDown(keys.keyBindBack) ? 1 : 0) : 0;
            double strafe = active ? (ClientKeyBindingState.isDown(keys.keyBindLeft) ? 1 : 0)
                    - (ClientKeyBindingState.isDown(keys.keyBindRight) ? 1 : 0) : 0;
            controls = tangent != null ? PlayerPhysicsInput.keyboard(tangent, forward, strafe,
                    active && ClientKeyBindingState.isDown(keys.keyBindJump),
                    active && ClientKeyBindingState.isDown(keys.keyBindSneak), active && player.isSprinting(),
                    active && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY))
                    : rawView != null ? PlayerPhysicsInput.keyboard(rawView, forward, strafe,
                    active && ClientKeyBindingState.isDown(keys.keyBindJump),
                    active && ClientKeyBindingState.isDown(keys.keyBindSneak), active && player.isSprinting(),
                    active && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY))
                    : session.keyboardControls(player.rotationYaw, forward, strafe, headForward,
                    active && ClientKeyBindingState.isDown(keys.keyBindJump),
                    active && ClientKeyBindingState.isDown(keys.keyBindSneak), active && player.isSprinting(),
                    active && ClientKeyBindingState.isDown(ClientGravityTicker.BOOST_KEY),
                    acceptedEffectiveBodyReference(player));
        }
        boolean confirmedPersonalG = ClientGravityTicker.isPersonalZeroGActive(player);
        controls = PlayerBoostEligibility.normalize(controls, confirmedPersonalG, player.isCreative());
        boolean mobilityFocused = mc.inGameHasFocus && mc.currentScreen == null;
        controls = session.sampleMobility(controls,
                mobilityFocused && controls.jump(), mobilityFocused,
                player.isCreative() && confirmedPersonalG);
        if (controls.mobility().request().filter(request -> request.mode()
                == PlayerMobilityIntent.Mode.FLIGHT).isPresent() && prediction != null) {
            if (!suspendPrediction(acceptedOfferContext, prediction, ClientCardinalPredictionOwner.EndpointValidation.refused(
                    wi.unigrav.core.player.CardinalPredictionSeed.Reason.UNSUPPORTED_STATE,
                    PlayerPhysicsProtocol.PredictionRefusalStage.SAMPLE, "Explicit FLIGHT pauses walking prediction"))) return;
        }
        final PlayerPhysicsProtocol.Controls sampledControls = controls;
        final var exactPrediction = prediction;
        final var predictionContext = acceptedOfferContext;
        Optional<PlayerPhysicsProtocol.PredictionInput> predictionInput = Optional.empty();
        wi.unigrav.core.player.NativeFlightParameters sampledEquipmentFlight = null;
        if (exactPrediction != null && session.predictionEligible() && exactPrediction.current(acceptedOfferContext, player)) {
            var sampledState=exactPrediction.state();
            var sampled = CardinalPredictionEnvironmentSampler.sample(player, world, sampledState, controls,
                    exactPrediction.seed(), ClientGravityTicker.isPersonalZeroGActive(player));
            if (sampled instanceof CardinalPredictionEnvironmentSampler.Sampled ready) {
                sampledEquipmentFlight = ready.environment().equipmentFlight();
                var beforePrediction = exactPrediction.state();
                try {
                    if (!predictionOwnerIdentityCurrent(predictionContext, exactPrediction)) {
                        exactPrediction.retire("Prediction owner changed during sampling"); return;
                    }
                    predictionInput = exactPrediction.predict(nativeSubmissionTick, sampledControls, ready.environment(),
                            box -> predictionContext.world.getCollisionBoxes(predictionContext.actor, box),
                            endpoint -> predictionEndpoint(predictionContext.world, endpoint,
                                    () -> predictionOwnerIdentityCurrent(predictionContext, exactPrediction)));
                } catch (Throwable cause) {
                    if (predictionOwnerIdentityCurrent(predictionContext, exactPrediction))
                        recoverPrediction(predictionContext, "Prediction step failed", cause);
                    PlayerPhysicsClient.<RuntimeException>rethrow(cause);
                    return;
                }
                if (!predictionOwnerIdentityCurrent(predictionContext, exactPrediction)) {
                    exactPrediction.retire("Prediction owner changed during endpoint validation"); return;
                }
                if (predictionInput.isPresent()) {
                    if (!applyPredictionProjection(acceptedOfferContext, exactPrediction, exactPrediction.predictedSnapshot())) return;
                    presentation.predictedMotion(player, beforePrediction, exactPrediction.state());
                } else if (!exactPrediction.active()) {
                    if (exactPrediction.refusal().isPresent()) {
                        if (!suspendPrediction(predictionContext,exactPrediction,exactPrediction.refusal().orElseThrow())) return;
                    } else { recoverPrediction(predictionContext, exactPrediction.retiredReason(), null); return; }
                }
            } else {
                if (!predictionOwnerIdentityCurrent(predictionContext, exactPrediction)) {
                    exactPrediction.retire("Prediction owner changed during sampling"); return;
                }
                if (sampled instanceof CardinalPredictionEnvironmentSampler.Unavailable failure && failure.cause().isPresent()) {
                    var cause=failure.cause().orElseThrow();
                    recoverPrediction(predictionContext,"Prediction sampling failed",cause);
                    PlayerPhysicsClient.<RuntimeException>rethrow(cause); return;
                }
                var reason=sampled instanceof CardinalPredictionEnvironmentSampler.Unsupported failure ? failure.reason()
                        : ((CardinalPredictionEnvironmentSampler.Unavailable)sampled).reason();
                var support=sampled instanceof CardinalPredictionEnvironmentSampler.Unsupported failure ? failure.support()
                        : ((CardinalPredictionEnvironmentSampler.Unavailable)sampled).support();
                if (!suspendPrediction(predictionContext,exactPrediction,ClientCardinalPredictionOwner.EndpointValidation.refused(
                        reason,sampled instanceof CardinalPredictionEnvironmentSampler.Unsupported failure ? failure.stage()
                                : ((CardinalPredictionEnvironmentSampler.Unavailable)sampled).stage(),support).withGround(sampledState.contacts().onGround()))) return;

            }
        }
        if (!PlayerKinematics.requiresNative(player)) {
            if (session != viewSession || acceptedOfferContext != viewContext
                    || !acceptedOwnerLifetimeCurrent(viewContext, mc) || isInstallationFenced(player)) return;
            if (tangent != null) {
                if (!presentation.tickCardinal(player, tangent, () -> session == viewSession && nativeSubmissionTick == viewTick
                        && acceptedOfferContext == viewContext && acceptedOwnerLifetimeCurrent(viewContext, mc)
                        && !isInstallationFenced(player) && viewSession.canCommitTangentView(player, tangent)
                        && currentProvenPresentation(player).isPresent())) {
                    UnigravMod.LOGGER.warn("ugmotion1 INPUT end refused at cardinal presentation tick {}",viewTick); return;
                }
                if (!viewSession.commitTangentView(player, tangent)) {
                    UnigravMod.LOGGER.warn("ugmotion1 INPUT end refused at tangent commit tick {}",viewTick); return;
                }
            } else presentation.tick(player, rawView);
        }
        final long inputInstallSerial = successfulInstallSerial;
        final var inputCanonical = viewSession.presentationSnapshot().orElse(null);
        final var inputPhysical = viewSession.snapshot().orElse(null);
        final boolean correctionInput = viewSession.ordinaryCorrectionInputEligible();
        final var suspensionRequest = nativeRestoreInput == null ? preparePredictionRequest(viewContext,sampledControls)
                : Optional.<PlayerPhysicsProtocol.PredictionRequest>empty();
        // Rearm sampling invokes external getters. Never submit this END's controls through a successor install.
        if (session != viewSession || acceptedOfferContext != viewContext || nativeSubmissionTick != viewTick
                || successfulInstallSerial != inputInstallSerial
                || viewSession.snapshot().orElse(null) != inputPhysical
                || viewSession.ordinaryCorrectionInputEligible() != correctionInput
                || inputCanonical == null && !correctionInput
                || viewSession.presentationSnapshot().orElse(null) != inputCanonical
                || !acceptedOwnerLifetimeCurrent(viewContext, mc) || isInstallationFenced(player)
                || (nativeRestoreInput != null ? !nativeRestoreInput.current()
                    : !correctionInput && currentProvenPresentation(player).isEmpty()
                        || tangent != null && !viewSession.committedTangentViewCurrent(player,tangent))
                || session != viewSession || acceptedOfferContext != viewContext || nativeSubmissionTick != viewTick
                || successfulInstallSerial != inputInstallSerial || viewSession.presentationSnapshot().orElse(null) != inputCanonical
                || viewSession.snapshot().orElse(null) != inputPhysical
                || viewSession.ordinaryCorrectionInputEligible() != correctionInput) {
            UnigravMod.LOGGER.warn("ugmotion1 INPUT end refused after preparation tick {} canonicalPresent {} correctionInput {} tangentPresent {}",
                    viewTick,inputCanonical != null,correctionInput,tangent != null);
            return;
        }
        final var inputIdentity = predictionInput;
        final var equipmentSample = sampledEquipmentFlight;
        session.input(controls).map(packet -> suspensionRequest.isPresent() ? new PlayerPhysicsProtocol.Packet(
                packet.kind(),packet.scope(),packet.sequence(),packet.offerSequence(),packet.application(),packet.snapshot(),packet.controls(),
                packet.nativeTeleportId(),packet.bodyReferenceReceipt(),packet.bodyReferenceAcknowledgement(),packet.bodyReferenceState(),
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),suspensionRequest)
                : inputIdentity.isEmpty() ? packet : new PlayerPhysicsProtocol.Packet(
                packet.kind(), packet.scope(), packet.sequence(), packet.offerSequence(), packet.application(),
                packet.snapshot(), packet.controls(), packet.nativeTeleportId(), packet.bodyReferenceReceipt(),
                packet.bodyReferenceAcknowledgement(), packet.bodyReferenceState(), inputIdentity, Optional.empty(), Optional.empty())).ifPresent(packet -> {
            PlayerPhysicsNetwork.sendToServer(packet);
            if (equipmentSample != null) {
                String identity = packet.scope() + ":" + equipmentSample;
                if (!identity.equals(lastEquipmentFlightProbeIdentity) || lastEquipmentFlightProbeTick == Long.MIN_VALUE
                        || nativeSubmissionTick - lastEquipmentFlightProbeTick >= 20) {
                    UnigravMod.LOGGER.info("ug-equipment-flight stage=client-input-sent scope={} revision={} inputSeq={}"
                                    + " localTick={} predictionInput={} capabilityFlySpeed={} sample={} inputTranslation={}"
                                    + " jump={} sneak={} sprint={}", packet.scope(), inputPhysical == null ? -1 : inputPhysical.revision(),
                            packet.sequence(), nativeSubmissionTick, inputIdentity, player.capabilities.getFlySpeed(), equipmentSample,
                            sampledControls.translation(), sampledControls.jump(), sampledControls.sneak(), sampledControls.sprint());
                    lastEquipmentFlightProbeIdentity = identity;
                    lastEquipmentFlightProbeTick = nativeSubmissionTick;
                }
            }
            if (nativeRestoreInput != null) {
                if (session == viewSession && acceptedOfferContext == viewContext && nativeRestoreInput.current())
                    viewSession.inputSendSucceeded(packet);
            } else session.inputSendSucceeded(packet);
            if (inputIdentity.isPresent() && prediction == exactPrediction && exactPrediction.current(acceptedOfferContext, player)
                    && session.predictionEligible() && acceptedOwnerLifetimeCurrent(acceptedOfferContext, mc))
                exactPrediction.inputSendSucceeded(packet);
        });
    }

    static boolean resetNativeRestorePresentation(NativeRestoreInputLease exact,
            PlayerPhysicsClientPresentation presentation, Runnable retirePrediction) {
        if (!exact.current()) return false;
        retirePrediction.run();
        if (!exact.current()) return false;
        presentation.reset();
        return exact.current();
    }

    private NativeRestoreInputLease captureNativeRestoreInput(Minecraft mc) {
        var context = acceptedOfferContext;
        var exactSession = session;
        var stamp = successfulInstallation;
        long serial = successfulInstallSerial;
        long tick = nativeSubmissionTick;
        if (context == null || exactSession == null || stamp == null || !PlayerKinematics.requiresNative(player)) return null;
        BooleanSupplier pure = () -> session == exactSession && acceptedOfferContext == context
                && successfulInstallation == stamp && successfulInstallSerial == serial && nativeSubmissionTick == tick
                && acceptedOwnerLifetimeCurrent(context, mc) && !isInstallationFenced(player);
        BooleanSupplier current = () -> {
            if (!pure.getAsBoolean()) return false;
            boolean aliveNative = player.isEntityAlive() && !player.isDead && PlayerKinematics.requiresNative(player);
            return aliveNative && pure.getAsBoolean();
        };
        return stamp.nativeRestoreInputLease(context, exactSession, player, mc.world, current);
    }

    private boolean sameHandoffLifetime(AcceptedOfferContext left, AcceptedOfferContext right) {
        return left != null && right != null && left.session == right.session
                && left.actor == right.actor && left.world == right.world && left.handler == right.handler
                && left.manager == right.manager && left.connection == right.connection
                && left.offer.scope().equals(right.offer.scope());
    }

    private ClientNativeHandoffLedger handoffLedger(AcceptedOfferContext context) {
        if (!sameHandoffLifetime(handoffLifetime, context)) {
            handoffLifetime = context;
            handoffLedger = new ClientNativeHandoffLedger();
        }
        return handoffLedger;
    }

    /** Called only after the actual native submission returns, never from packet preparation. */
    public static void nativePositionSubmitted(NetHandlerPlayClient handler, Packet<?> packet) {
        if (!(packet instanceof CPacketPlayer.Position) && !(packet instanceof CPacketPlayer.PositionRotation)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) return;
        AcceptedOfferContext context = INSTANCE.acceptedOfferContext;
        if (context != null && context.handler == handler && !context.session.owns()
                && !isInstallationFenced(mc.player) && INSTANCE.acceptedOwnerLifetimeCurrent(context, mc)) {
            INSTANCE.handoffLedger(context).positioned(INSTANCE.nativeSubmissionTick);
        }
    }

    /** Initial handoff supplements ordinary native transport without installing or confirming an owner. */
    private void advanceNativeHandoff(Minecraft mc) {
        AcceptedOfferContext context = acceptedOfferContext;
        if (context == null) { handoffLifetime = null; handoffLedger = null; return; }
        ClientNativeHandoffLedger ledger = handoffLedger(context);
        if (!acceptedOwnerLifetimeCurrent(context, mc) || context.session.owns()
                || isInstallationFenced(mc.player)) { ledger.stop(); return; }
        EntityPlayerSP actor = context.actor;
        boolean eligible = actor.isEntityAlive() && !PlayerKinematics.requiresNative(actor)
                && (!RemoteBoostClient.hasCustody(actor) || RemoteBoostClient.observeStateApplication(actor).filter(application -> application.state().dimension() == context.offer.scope().dimension()).isPresent());
        long generation = context.session.ownerGeneration();
        if (ledger.advance(mc.isGamePaused(), eligible, nativeSubmissionTick)) {
            // Revalidate before reading send geometry. prepareOutgoing may advance this generation itself.
            if (context != acceptedOfferContext || generation != context.session.ownerGeneration()
                    || !acceptedOwnerLifetimeCurrent(context, mc) || isInstallationFenced(actor)) return;
            try {
                context.handler.sendPacket(new CPacketPlayer.Position(actor.posX,
                        actor.getEntityBoundingBox().minY, actor.posZ, actor.onGround));
                ledger.submitted();
            } catch (Throwable failure) {
                ledger.stop();
                UnigravMod.LOGGER.error("ugmotion1 initial native handoff submission failed; supplementation stopped scope "
                        + context.offer.scope() + " age " + ledger.age() + " submitted " + ledger.submissions(), failure);
            }
        }
        if (ledger.reportExhaustion()) UnigravMod.LOGGER.warn(
                "ugmotion1 initial native handoff supplementation exhausted scope " + context.offer.scope()
                        + " age " + ledger.age() + " submitted " + ledger.submissions());
    }

    /** The failed task has its own bounded lifetime; no ordinary physics writer advances here. */
    private void advanceRecovery(Minecraft mc) {
        advanceNativeTransferRecovery(mc);
        ClientInstallationRecoveryTask task = recoveryTask;
        if (task == null) return;
        NetHandlerPlayClient handler = mc.getConnection();
        NetworkManager manager = handler == null ? null : handler.getNetworkManager();
        var current = manager == null ? Optional.<PlayerPhysicsInboundConnections.ConnectionHandle>empty()
                : PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(manager, handler, EnumPacketDirection.CLIENTBOUND);
        boolean exactLifetime = task.matches(mc.player, mc.world, handler, manager, current.orElse(null))
                && manager.getNetHandler() == handler && isBrokenActor(mc.player);
        var result = task.tick(exactLifetime, !mc.isGamePaused());
        result.failure().ifPresent(failure -> UnigravMod.LOGGER.error(
                "ugmotion1 bounded client installation RECOVER send attempt " + task.attempts()
                        + " failed; actor remains fenced " + task.identity(), failure));
        if (result.finished()) {
            if (result.exhausted()) UnigravMod.LOGGER.warn(
                    "ugmotion1 bounded client installation RECOVER exhausted its attempts or lifetime; actor remains fenced "
                            + task.identity());
            recoveryTask = null;
        }
    }

    private void advanceNativeTransferRecovery(Minecraft mc) {
        var task = nativeTransferRecoveryTask;
        var owner = nativeTransferRecoveryOwner;
        var request = nativeTransferRecoveryRequest;
        if (task == null) return;
        BooleanSupplier pure = () -> owner != null && request != null && acceptedOfferContext == owner
                && session == owner.session && owner.session.nativeTransferFailureCurrent(request)
                && mc.player == owner.actor && player == owner.actor && mc.world == owner.world && world == owner.world
                && connection == owner.handler && connectionHandle == owner.connection
                && owner.actor.world == owner.world && owner.actor.dimension == owner.offer.scope().dimension()
                && !owner.actor.isDead;
        boolean exact = pure.getAsBoolean()
                && task.matches(owner.actor, owner.world, owner.handler, owner.manager, owner.connection)
                && acceptedOwnerLifetimeCurrent(owner, mc) && pure.getAsBoolean()
                && owner.actor.isEntityAlive() && pure.getAsBoolean()
                && acceptedOwnerLifetimeCurrent(owner, mc) && pure.getAsBoolean();
        var result = task.tick(exact, !mc.isGamePaused());
        result.failure().ifPresent(cause -> UnigravMod.LOGGER.error(
                "ugmotion1 exact native transfer recovery feedback send failed " + task.identity(), cause));
        if (nativeTransferRecoveryTask == task && result.finished()) {
            if (result.exhausted()) UnigravMod.LOGGER.warn(
                    "ugmotion1 native transfer recovery feedback exhausted; current actor remains gated " + task.identity());
            nativeTransferRecoveryTask = null; nativeTransferRecoveryOwner = null; nativeTransferRecoveryRequest = null;
        }
    }

    /** Opaque proof retained in a single post-thread-hop native method invocation. */
    public static final class NativeCorrectionApplication {
        private final NetHandlerPlayClient handler;
        private final SPacketPlayerPosLook packet;
        private final PlayerPhysicsClientSession session;
        private final AcceptedOfferContext owner;
        private final EntityPlayerSP actor;
        private final World world;
        private final Object scope;
        private final PlayerPhysicsClientSession.NativeCorrectionApplication application;

        private NativeCorrectionApplication(NetHandlerPlayClient handler,
                SPacketPlayerPosLook packet,
                PlayerPhysicsClientSession session, AcceptedOfferContext owner, EntityPlayerSP actor,
                World world, Object scope, PlayerPhysicsClientSession.NativeCorrectionApplication application) {
            this.handler = handler; this.packet = packet; this.session = session; this.owner = owner;
            this.actor = actor; this.world = world; this.scope = scope; this.application = application;
        }
        private boolean pureCurrent() {
            Minecraft mc = Minecraft.getMinecraft();
            return INSTANCE.session == session && INSTANCE.acceptedOfferContext == owner
                    && INSTANCE.connection == handler && INSTANCE.scopeHandle == scope
                    && INSTANCE.player == actor && mc.player == actor && INSTANCE.world == world && mc.world == world
                    && application.current() && !actor.isDead;
        }
        private boolean current() {
            return pureCurrent() && INSTANCE.acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft())
                    && pureCurrent() && actor.isEntityAlive() && pureCurrent();
        }
    }

    public static NativeCorrectionApplication captureNativeCorrectionApplication(NetHandlerPlayClient handler,
            SPacketPlayerPosLook packet, Runnable capture) {
        Minecraft mc = Minecraft.getMinecraft();
        var session = INSTANCE.session;
        var owner = INSTANCE.acceptedOfferContext;
        EntityPlayerSP actor = mc.player;
        World exactWorld = mc.world;
        var exactScope = INSTANCE.scopeHandle;
        var cue = session == null ? null : session.nativeCorrectionCue();
        // Preserve the original cue exactly once, but never adopt a successor created inside its cleanup.
        Objects.requireNonNull(capture, "capture").run();
        if (session == null || owner == null || actor == null || INSTANCE.session != session
                || INSTANCE.acceptedOfferContext != owner || INSTANCE.connection != handler
                || INSTANCE.player != actor || mc.player != actor || INSTANCE.world != exactWorld || mc.world != exactWorld
                || INSTANCE.scopeHandle != exactScope || !INSTANCE.acceptedOwnerLifetimeCurrent(owner, mc)
                || INSTANCE.session != session || INSTANCE.acceptedOfferContext != owner || mc.player != actor
                || mc.world != exactWorld || INSTANCE.scopeHandle != exactScope) return null;
        var application = session.prepareNativeCorrectionApplication(cue, packet.getTeleportId());
        if (application == null) return null;
        var proof = new NativeCorrectionApplication(handler, packet, session, owner, actor, exactWorld,
                exactScope, application);
        return proof.current() ? proof : null;
    }

    public static void nativeCorrectionPositionApplied(NativeCorrectionApplication proof,
            SPacketPlayerPosLook packet) {
        if (proof != null && proof.packet == packet && proof.current()) proof.application.positionApplied = true;
    }

    public static void nativeCorrectionReplySent(NativeCorrectionApplication proof, Packet<?> packet) {
        if (proof == null || !proof.current()) return;
        if (packet instanceof CPacketConfirmTeleport confirm
                && confirm.getTeleportId() == proof.application.teleportId) proof.application.confirmSent = true;
        else if (packet instanceof CPacketPlayer.PositionRotation) proof.application.positionSent = true;
    }

    public static void nativeCorrectionApplicationReturned(NativeCorrectionApplication proof,
            SPacketPlayerPosLook packet) {
        if (proof != null && proof.packet == packet && proof.current())
            proof.session.nativeCorrectionApplicationReturned(proof.application);
    }

    /** The native ID fences stale states without reopening the retired writer or discarding a lost OWNED. */
    public static void nativeCorrection(NetHandlerPlayClient handler, int teleportId) {
        if (INSTANCE.connection != handler || INSTANCE.session == null) return;
        AcceptedOfferContext owner = INSTANCE.acceptedOfferContext;
        Minecraft mc = Minecraft.getMinecraft();
        if (owner == null || !INSTANCE.acceptedOwnerLifetimeCurrent(owner, mc)) return;
        // Invalidate mutation eligibility before any cleanup that may invoke third-party callbacks.
        boolean normalCorrection = INSTANCE.session.predictionEligible()
                || INSTANCE.correctionPredictionRearm != null && INSTANCE.correctionPredictionRearm.current(INSTANCE, owner);
        var environmentOwner = INSTANCE.freeEnvironmentOwner;
        var correctingSession = INSTANCE.session;
        var correctingScope = INSTANCE.scopeHandle;
        INSTANCE.session.nativeCorrection(teleportId);
        if (environmentOwner != null && environmentOwner.session == correctingSession
                && environmentOwner.actor == owner.actor && environmentOwner.world == owner.world
                && environmentOwner.scope.equals(owner.offer.scope()) && environmentOwner.correction != teleportId
                && correctingSession.drivesMotion()) {
            var completion = correctingSession.captureNativeEnvironmentCorrection();
            var transfer = environmentOwner.resources.prepareCorrection(teleportId);
            if (INSTANCE.freeEnvironmentOwner != environmentOwner || INSTANCE.session != correctingSession
                    || INSTANCE.acceptedOfferContext != owner || INSTANCE.scopeHandle != correctingScope
                    || !completion.pending(correctingSession) || INSTANCE.player != owner.actor || INSTANCE.world != owner.world
                    || INSTANCE.connection != handler || INSTANCE.connectionHandle != owner.connection
                    || mc.player != owner.actor || mc.world != owner.world)
                throw new IllegalStateException("Free correction changed while capturing its resource lease");
            environmentOwner.pendingCorrection = completion;
            environmentOwner.correctionTransfer = transfer;
        }
        boolean partial = INSTANCE.predictionMutations.stream().anyMatch(context -> context.owner == owner
                && context.mutation != null && context.mutation.possiblyPartial());
        if (partial) {
            var token = INSTANCE.session.correctionLifetime();
            INSTANCE.session.quarantinePrediction(token);
            INSTANCE.pendingPredictionRetirement = INSTANCE.new PredictionRetirement(owner, INSTANCE.scopeHandle,
                    token, "Native correction interrupted a partial prediction mutation", null);
        }
        CorrectionPredictionRearm rearm = normalCorrection && !partial && INSTANCE.cleanupWriters.isEmpty()
                ? new CorrectionPredictionRearm(owner, INSTANCE.scopeHandle, INSTANCE.session.correctionPredictionIdentity()) : null;
        INSTANCE.resetPresentation();
        // Cleanup may reenter with another correction or owner; the outer boundary cannot overwrite its qualification.
        if (rearm != null && rearm.current(INSTANCE, owner)) INSTANCE.correctionPredictionRearm = rearm;
    }

    private boolean applyPredictionProjection(AcceptedOfferContext owner, ClientCardinalPredictionOwner exactPrediction,
            PlayerPhysicalSnapshot projection) {
        if (owner == null || prediction != exactPrediction || !owner.session.predictionEligible()
                || !acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft())) return false;
        PredictionMutationContext context = new PredictionMutationContext(owner, exactPrediction);
        context.mutation = new ClientCardinalPredictionOwner.Mutation(owner.actor, projection, context::current);
        predictionMutations.addLast(context);
        boolean applied;
        try {
            applied = context.mutation.apply();
            if (!applied) {
                if (context.current()) queuePredictionRetirement(context, "Prediction projection failed", context.mutation.failure());
                else if (pendingPredictionRetirement != null && pendingPredictionRetirement.owner == owner
                        && pendingPredictionRetirement.cause == null) pendingPredictionRetirement.cause = context.mutation.failure();
            }
        } finally {
            if (predictionMutations.removeLast() != context) throw new IllegalStateException("Prediction mutation stack mismatch");
            if (predictionMutations.isEmpty()) {
                drainPredictionRetirement();
                drainDeadPoseRetirement();
            }
        }
        if (context.mutation.failure() != null) PlayerPhysicsClient.<RuntimeException>rethrow(context.mutation.failure());
        return applied;
    }

    /** The successor grants its own quarantine authority before an older setter can resume. */
    private void captureSuccessorPredictionQuarantine(AcceptedOfferContext owner) {
        boolean interrupted = predictionMutations.stream().anyMatch(context -> context.owner.actor == owner.actor
                && context.owner.world == owner.world && context.owner.manager == owner.manager
                && context.successfulSerial < successfulInstallSerial
                && context.mutation != null && context.mutation.possiblyPartial());
        interrupted |= cleanupWriters.stream().anyMatch(writer -> writer.possiblyPartial
                && writer.owner.actor == owner.actor && writer.owner.world == owner.world && writer.owner.manager == owner.manager);
        if (!interrupted || !owner.session.predictionEligible()
                || !acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft())) return;
        var token = owner.session.predictionLifetime();
        owner.session.quarantinePrediction(token);
        pendingPredictionRetirement = new PredictionRetirement(owner, scopeHandle, token,
                "Successor installation interrupted by an older partial prediction setter", null);
        retirePrediction("Successor quarantined before older setter resumes");
    }

    private void queuePredictionRetirement(PredictionMutationContext context, String reason, Throwable cause) {
        if (!context.current()) return;
        context.owner.session.quarantinePrediction(context.lifetime);
        pendingPredictionRetirement = new PredictionRetirement(context.owner, context.scope, context.lifetime, reason, cause);
    }

    private void recoverPrediction(AcceptedOfferContext owner, String reason, Throwable cause) {
        if (owner == null || !owner.session.predictionEligible()
                || !acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft())) return;
        var token = owner.session.predictionLifetime();
        owner.session.quarantinePrediction(token);
        pendingPredictionRetirement = new PredictionRetirement(owner, scopeHandle, token, reason, cause);
        if (predictionMutations.isEmpty()) drainPredictionRetirement();
    }

    /** No partial setter can still run when physical cleanup begins. */
    private void drainPredictionRetirement() {
        if (!predictionMutations.isEmpty() || !cleanupWriters.isEmpty() || drainingPredictionRetirement) return;
        drainingPredictionRetirement = true;
        try {
            drainOnePredictionRetirement();
        } finally { drainingPredictionRetirement = false; }
    }

    private void drainOnePredictionRetirement() {
        PredictionRetirement retirement = pendingPredictionRetirement;
        pendingPredictionRetirement = null;
        if (retirement == null || !retirement.identityCurrent()) return;
        AcceptedOfferContext owner = retirement.owner;
        Optional<PlayerPhysicsProtocol.Packet> request = Optional.empty();
        long stamp = markBroken(owner.actor, owner.world, owner.manager, owner.offer.scope());
        Throwable cause = retirement.cause;
        try {
            synchronized (owner.manager) {
                if (!retirement.identityCurrent() || !owner.session.failPrediction(retirement.lifetime)) return;
                request = owner.session.recoveryRequest();
                PlayerPhysicsInboundConnections.INSTANCE.retireScope(retirement.scope);
                scopeHandle = null; acceptedOfferContext = null; connection = null; connectionHandle = null;
            }
            final var captured = retirement;
            boolean cleaned = cleanupRetiredPhysicalState(owner.actor, owner.world, owner.session, owner,
                    retirement.scope, originalNoGravity, stamp,
                    () -> successfulInstallSerial == captured.successfulSerial && captured.lifetime.current(owner.session));
            if (cleaned && request.isPresent()) recoveryTask = new ClientInstallationRecoveryTask(owner.actor, owner.world,
                    owner.handler, owner.manager, owner.connection, request.orElseThrow());
        } catch (Throwable failure) {
            cause = combineFailure(cause, failure);
        }
        if (cause != null) UnigravMod.LOGGER.error("ugmotion1 prediction mutation retired: " + retirement.reason
                + " scope " + owner.offer.scope(), cause);
        else UnigravMod.LOGGER.warn("ugmotion1 prediction rebase required: " + retirement.reason + " scope " + owner.offer.scope());
    }

    private void retirePrediction(String reason) {
        predictionSuspension=null;
        correctionPredictionRearm = null;
        if (prediction != null) prediction.retire(reason);
        prediction = null;
        presentation.retirePredictedMotion();
        if (session != null) session.predictedPoseRestoration(null);
    }

    /** Restore the current installed canonical snapshot, never the preceding prediction authority. */
    private boolean suspendPrediction(AcceptedOfferContext owner,ClientCardinalPredictionOwner exact,
            ClientCardinalPredictionOwner.EndpointValidation refusal) {
        return suspendPrediction(owner,exact,refusal,null);
    }
    private boolean suspendPrediction(AcceptedOfferContext owner,ClientCardinalPredictionOwner exact,
            ClientCardinalPredictionOwner.EndpointValidation refusal,PlayerPhysicsProtocol.PredictionSuspended notification) {
        if (!predictionMutations.isEmpty()) throw new IllegalStateException("Cannot suspend inside prediction mutation");
        if (!predictionOwnerIdentityCurrent(owner,exact)) return false;
        var canonical=owner.session.presentationSnapshot().orElseThrow();
        var lifetime=owner.session.predictionLifetime();
        long serial=successfulInstallSerial;
        if (!owner.session.completePredictionSuspension(lifetime,()->applyPredictionProjection(owner,exact,canonical))) return false;
        if (!predictionOwnerIdentityCurrent(owner,exact) || !lifetime.current(owner.session)
                || serial!=successfulInstallSerial || owner.session.presentationSnapshot().orElse(null)!=canonical) return false;
        if(notification==null) exact.refuse(refusal); else exact.retire("Server suspended prediction mapping: "+notification.origin());
        var suspended=notification==null ? new PredictionSuspension(owner,exact,Math.incrementExact(predictionSuspensionRequestId),refusal.refusal().orElseThrow())
                : new PredictionSuspension(owner,exact,notification);
        if(notification==null) predictionSuspensionRequestId=suspended.suspend.requestId();
        // No authority/ACK quarantine: the just-proven canonical installation remains the physical owner.
        owner.session.predictedPoseRestoration(null);
        presentation.retirePredictedMotion(); prediction=null; predictionSuspension=suspended;
        UnigravMod.LOGGER.warn("ugmotion1 prediction suspended scope {} proposal {} bootstrap {} epoch {} revision {} first {} last {} canonicalFrame {} canonicalGround {} refusalGround {} refusal {} support {}",
                canonical.scope(),owner.offer.offerSequence(),exact.bootstrap(),predictionEpoch,canonical.revision(),exact.firstTick(),exact.lastTick(),
                canonical.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal ? cardinal.frame() : "not-cardinal",
                canonical.contacts().map(PlayerPhysicalSnapshot.Contacts::onGround),
                refusal==null ? "NOT_OBSERVED" : refusal.refusalGround().map(Object::toString).orElse("NOT_OBSERVED"),suspended.suspend.refusal(),refusal==null ? "not-applicable" : refusal.support());
        return true;
    }

    private Optional<PlayerPhysicsProtocol.PredictionRequest> preparePredictionRequest(AcceptedOfferContext owner,
            PlayerPhysicsProtocol.Controls controls) {
        var suspended=predictionSuspension;
        if (suspended==null || suspended.owner!=owner || !acceptedOwnerLifetimeCurrent(owner,Minecraft.getMinecraft())
                || !owner.session.predictionEligible()) return Optional.empty();
        suspended.jumpHeld=controls.jump();
        if (suspended.acknowledged && !suspended.rearmSent && suspended.checkpoint!=null
                && (suspended.lastProbeTick==Long.MIN_VALUE || nativeSubmissionTick-suspended.lastProbeTick>=20)) {
            suspended.lastProbeTick=nativeSubmissionTick;
            var snapshot=owner.session.presentationSnapshot().orElseThrow();
            if (suspended.checkpoint.snapshot().filter(snapshot::equals).isPresent()
                    && suspended.checkpoint.predictionSeed().orElse(null) instanceof wi.unigrav.core.player.CardinalPredictionSeed.Ready ready) {
                var state=new CardinalPredictionStep.State(snapshot.placement(),snapshot.worldVelocity().multiply(.05),snapshot.contacts().orElseThrow(),0);
                var sampled=CardinalPredictionEnvironmentSampler.sample(owner.actor,owner.world,state,controls,ready,
                        ClientGravityTicker.isPersonalZeroGActive(owner.actor));
                if (sampled instanceof CardinalPredictionEnvironmentSampler.Unavailable failure && failure.cause().isPresent()) {
                    var cause=failure.cause().orElseThrow();
                    if (predictionSuspension==suspended && acceptedOwnerLifetimeCurrent(owner,Minecraft.getMinecraft()))
                        recoverPrediction(owner,"Prediction rearm sampling failed",cause);
                    PlayerPhysicsClient.<RuntimeException>rethrow(cause);
                }
                if (predictionSuspension!=suspended || !acceptedOwnerLifetimeCurrent(owner,Minecraft.getMinecraft())
                        || owner.session.presentationSnapshot().orElse(null)!=snapshot) return Optional.empty();
                if (sampled instanceof CardinalPredictionEnvironmentSampler.Sampled) suspended.rearmSent=true;
            }
        }
        return Optional.of(suspended.request());
    }

    private boolean predictionOwnerIdentityCurrent(AcceptedOfferContext owner, ClientCardinalPredictionOwner exact) {
        return owner != null && prediction == exact && acceptedOfferContext == owner && session == owner.session
                && acceptedOwnerLifetimeCurrent(owner, Minecraft.getMinecraft())
                && prediction == exact && acceptedOfferContext == owner && session == owner.session;
    }

    static ClientCardinalPredictionOwner.EndpointValidation predictionEndpoint(net.minecraft.world.World exactWorld,
            CardinalPredictionStep.State state, java.util.function.BooleanSupplier current) {
        if (!current.getAsBoolean())
            return ClientCardinalPredictionOwner.EndpointValidation.rejected("Prediction endpoint owner changed");
        if (!state.contacts().onGround()) return ClientCardinalPredictionOwner.EndpointValidation.allowed();
        var result = CardinalPredictionEnvironmentSampler.checkEndpoint(exactWorld, state);
        // Real callback failure remains primary even if the callback also changes the owner.
        if (result instanceof CardinalPredictionEnvironmentSampler.UnavailableSurface failure && failure.cause().isPresent())
            throw failure.cause().orElseThrow();
        if (!current.getAsBoolean())
            return ClientCardinalPredictionOwner.EndpointValidation.rejected("Prediction endpoint owner changed");
        if (result instanceof CardinalPredictionEnvironmentSampler.UnavailableSurface failure)
            return ClientCardinalPredictionOwner.EndpointValidation.refused(failure.reason(),PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT,failure.support()).withGround(state.contacts().onGround());
        if (result instanceof CardinalPredictionEnvironmentSampler.UnsupportedSurface failure)
            return ClientCardinalPredictionOwner.EndpointValidation.refused(failure.reason(),PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT,failure.support()).withGround(state.contacts().onGround());
        return ClientCardinalPredictionOwner.EndpointValidation.allowed();
    }

    private void acceptPrediction(AcceptedOfferContext context, PlayerPhysicsProtocol.Packet packet,
            PlayerPhysicsClientSession.PreparedInstallation published) {
        var snapshot = packet.snapshot().orElseThrow();
        if (prediction != null && !prediction.current(context, context.actor)) retirePrediction("Prediction owner changed");
        if (packet.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.PredictionSuspended notification
                && notification.origin()!=PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL
                && prediction!=null && prediction.bootstrap().equals(notification.bootstrap())) {
            if(!suspendPrediction(context,prediction,null,notification)) return;
        }
        if (prediction == null && predictionSuspension == null
                && packet.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.PredictionSuspended receipt
                && (receipt.origin()==PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_MOBILITY
                    || receipt.origin()==PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_REFERENCE)) {
            var receiving=session;
            var canonical=receiving.presentationSnapshot().orElse(null);
            long serial=successfulInstallSerial;
            var stamp=successfulInstallation;
            java.util.function.BooleanSupplier current=()-> session==receiving && receiving==context.session
                    && acceptedOfferContext==context && prediction==null && predictionSuspension==null
                    && successfulInstallSerial==serial && successfulInstallation==stamp
                    && receiving.publishedInstallationCurrent(published,packet,canonical)
                    && acceptedOwnerLifetimeCurrent(context,Minecraft.getMinecraft());
            java.util.function.BooleanSupplier live=()->{
                if(!current.getAsBoolean() || context.actor.isDead) return false;
                boolean alive=context.actor.isEntityAlive();
                return current.getAsBoolean() && !context.actor.isDead && alive;
            };
            if (!live.getAsBoolean() || canonical==null || !canonical.equals(snapshot)
                    || currentProvenPresentation(context.actor).orElse(null)!=canonical
                    || !live.getAsBoolean()) return;
            predictionSuspension=new PredictionSuspension(context,receipt);
        }
        if (predictionSuspension != null && predictionSuspension.owner == context) {
            var suspended=predictionSuspension;
            StringMapMessage adoptionCommit=null;
            if (packet.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.PredictionSuspended receipt) {
                if (receipt.origin()!=PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL
                        && suspended.suspend.origin()==PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL
                        && receipt.bootstrap().equals(suspended.suspend.bootstrap())) {
                    var receiving=session;
                    var canonical=receiving.presentationSnapshot().orElse(null);
                    var stamp=successfulInstallation;
                    long installedSerial=successfulInstallSerial;
                    var transaction=suspended.suspend;
                    var checkpoint=suspended.checkpoint;
                    boolean acknowledged=suspended.acknowledged, rearmSent=suspended.rearmSent, jumpHeld=suspended.jumpHeld;
                    long probeTick=suspended.lastProbeTick;
                    java.util.function.BooleanSupplier current=()-> session==receiving && receiving==context.session
                            && acceptedOfferContext==context && prediction==null && predictionSuspension==suspended
                            && successfulInstallation==stamp && successfulInstallSerial==installedSerial
                            && receiving.publishedInstallationCurrent(published,packet,canonical)
                            && suspended.suspend==transaction && suspended.checkpoint==checkpoint
                            && suspended.acknowledged==acknowledged && suspended.rearmSent==rearmSent
                            && suspended.jumpHeld==jumpHeld && suspended.lastProbeTick==probeTick
                            && acceptedOwnerLifetimeCurrent(context,Minecraft.getMinecraft());
                    java.util.function.BooleanSupplier live=()->{
                        if(!current.getAsBoolean() || context.actor.isDead) return false;
                        boolean alive=context.actor.isEntityAlive();
                        return current.getAsBoolean() && !context.actor.isDead && alive;
                    };
                    if (!live.getAsBoolean() || !canonical.equals(snapshot)
                            || currentProvenPresentation(context.actor).orElse(null)!=canonical
                            || !live.getAsBoolean()) return;
                    adoptionCommit=new StringMapMessage()
                            .with("event","prediction_suspension_adoption_commit")
                            .with("oldOrigin",transaction.origin().name()).with("oldRequestId",Long.toString(transaction.requestId()))
                            .with("newOrigin",receipt.origin().name()).with("newRequestId",Long.toString(receipt.requestId()))
                            .with("bootstrapGeneration",Long.toString(transaction.bootstrap().generation()))
                            .with("bootstrapCorrection",Long.toString(transaction.bootstrap().correctionRevision()))
                            .with("scope",canonical.scope().toString()).with("proposal",Long.toString(context.offer.offerSequence()))
                            .with("packetSequence",Long.toString(packet.sequence())).with("canonicalRevision",Long.toString(canonical.revision()))
                            .with("installationSerial",Long.toString(installedSerial));
                }
                suspended.acceptReceipt(receipt);
            }
            suspended.checkpoint=packet;
            if(adoptionCommit!=null) UnigravMod.LOGGER.debug(adoptionCommit);
            if (!(packet.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.AwaitingFirstResolvedSlot awaiting)
                    || !suspended.acknowledged || !suspended.rearmSent
                    || awaiting.bootstrap().correctionRevision()!=suspended.suspend.bootstrap().correctionRevision()
                    || awaiting.bootstrap().generation()<=suspended.suspend.bootstrap().generation()) return;
            if (!(packet.predictionSeed().orElse(null) instanceof wi.unigrav.core.player.CardinalPredictionSeed.Ready)) return;
            var next=new ClientCardinalPredictionOwner(context,context.actor,Math.incrementExact(predictionEpoch),packet);
            suspended.previous.ifPresent(previous -> next.inheritSuspendedInput(previous,suspended.jumpHeld));
            predictionEpoch=Math.incrementExact(predictionEpoch); prediction=next; predictionSuspension=null;
        }
        if (prediction == null) {
            boolean correctedState = packet.kind() == PlayerPhysicsProtocol.Kind.STATE
                    && correctionPredictionRearm != null && correctionPredictionRearm.current(this, context);
            if (packet.kind() != PlayerPhysicsProtocol.Kind.OWNED && !correctedState) return;
            if (!(packet.predictionSeed().orElse(null) instanceof wi.unigrav.core.player.CardinalPredictionSeed.Ready ready)) return;
            long nextEpoch = Math.incrementExact(predictionEpoch);
            if (!(packet.predictionPhase().orElse(null) instanceof PlayerPhysicsProtocol.AwaitingFirstResolvedSlot)) return;
            var nextPrediction = new ClientCardinalPredictionOwner(context, context.actor, nextEpoch, packet);
            if (correctedState && !correctionPredictionRearm.identity().consume(session)) return;
            correctionPredictionRearm = null;
            predictionEpoch = nextEpoch;
            prediction = nextPrediction;
        } else {
            final var reconciling = prediction;
            try {
                if (!reconciling.reconcile(packet, nativeSubmissionTick,
                        box -> context.world.getCollisionBoxes(context.actor, box),
                        endpoint -> predictionEndpoint(context.world, endpoint,
                                () -> predictionOwnerIdentityCurrent(context, reconciling)))) {
                    if (predictionOwnerIdentityCurrent(context, reconciling)) {
                        if (reconciling.refusal().isPresent()) {
                            var refusal=reconciling.refusal().orElseThrow();
                            suspendPrediction(context,reconciling,refusal.atStage(PlayerPhysicsProtocol.PredictionRefusalStage.REPLAY));
                        } else recoverPrediction(context, "Prediction requires rebase: " + reconciling.retiredReason(), null);
                    }
                    return;
                }
                if (!predictionOwnerIdentityCurrent(context, reconciling)) {
                    reconciling.retire("Prediction owner changed during reconciliation"); return;
                }
            } catch (Throwable cause) {
                if (predictionOwnerIdentityCurrent(context, reconciling))
                    recoverPrediction(context, "Prediction reconciliation failed", cause);
                PlayerPhysicsClient.<RuntimeException>rethrow(cause);
                return;
            }
        }
        prediction.bindAcceptedAuthority(session.presentationSnapshot().orElseThrow());
        var exactPrediction = prediction;
        session.predictedPoseRestoration(() -> applyPredictionProjection(context, exactPrediction, exactPrediction.predictedSnapshot()));
        applyPredictionProjection(context, exactPrediction, exactPrediction.predictedSnapshot());
    }

    private void invalidateSuccessfulInstallation() {
        retirePrediction("Authority installation invalidated");
        discardSuccessfulInstallationStamp();
        if (session != null) session.revokeStateAcknowledgements();
    }

    private void discardSuccessfulInstallationStamp() {
        successfulInstallation = null;
        acceptedPresentationActor = null;
        acceptedPresentationSnapshot = null;
    }

    private void resetPresentation() {
        invalidateSuccessfulInstallation();
        presentation.reset();
    }

    private void clear() {
        if (freeEnvironmentOwner != null) freeEnvironmentOwner.resources.invalidate();
        freeEnvironmentOwner = null;
        handoffLifetime = null;
        handoffLedger = null;
        PlayerPhysicsInboundConnections.ScopeHandle retiringScope = scopeHandle;
        scopeHandle = null;
        acceptedOfferContext = null;
        if (retiringScope != null) PlayerPhysicsInboundConnections.INSTANCE.retireScope(retiringScope);
        resetPresentation();
        if (session != null) {
            if (session.drivesMotion() && player != null && player.world == world && !isBrokenActor(player))
                player.setNoGravity(originalNoGravity);
            session.reset();
        }
        connection = null;
        connectionHandle = null;
        player = null;
        world = null;
        session = null;
        noGravityCaptured = false;
    }

    public static float bodyYawOffset(EntityPlayer actor, float partialTicks) {
        return !isInstallationFenced(actor) && drivesMotion(actor)
                ? INSTANCE.presentation.bodyYawOffset(actor, partialTicks) : 0;
    }

    public static Optional<TangentViewSample> currentTangentView(EntityPlayer actor) {
        return currentProvenPresentation(actor).isPresent() && INSTANCE.session != null
                ? INSTANCE.session.tangentView(actor) : Optional.empty();
    }

    public static Vec3 cardinalBodyForward(EntityPlayer actor, float partialTicks) {
        var current = currentProvenPresentation(actor);
        return current.isPresent() ? INSTANCE.presentation.bodyForward(actor, current.orElseThrow(), partialTicks) : null;
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


