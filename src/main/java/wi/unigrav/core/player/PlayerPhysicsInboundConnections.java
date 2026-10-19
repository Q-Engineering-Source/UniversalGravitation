package wi.unigrav.core.player;

import io.netty.buffer.ByteBuf;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import wi.unigrav.Reference;

/**
 * Owns the connection-local decode budget from the real Forge connect event through disconnect.
 * Lifecycle lock order is NetworkManager monitor, this registry monitor, then the entry budget.
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class PlayerPhysicsInboundConnections {
    public static final PlayerPhysicsInboundConnections INSTANCE = new PlayerPhysicsInboundConnections();

    /** Reasons exposed by the facade without exposing the budget's package-private reason type. */
    public enum InvalidationReason {
        DISCONNECT, REPLACED, CHANNEL_CLOSED, CHANNEL_UNREGISTERED
    }

    /** A normal, typed refusal from the raw ingress and queue boundary. */
    public enum AdmissionRefusal {
        NONE,
        NO_CURRENT_CONNECTION,
        FOREIGN_CONNECTION,
        STALE_CONNECTION,
        WRONG_HANDLER,
        WRONG_DIRECTION,
        CHANNEL_CLOSED,
        FOREIGN_RAW_FRAME,
        RAW_FRAME_BOUND_TO_DIFFERENT_ENTRY,
        INVALIDATED,
        ARRIVAL_COUNT,
        ARRIVAL_BYTES,
        MISSING_SCOPE,
        FOREIGN_SCOPE,
        RETIRED_SCOPE,
        PACKET_SCOPE_MISMATCH,
        OUTSTANDING_LIMIT
    }

    /** Caller-visible decode completion states, independent of internal budget types. */
    public enum DecodeOutcome { PROOF_ACCEPTED, REJECTED }

    /** Result of one real raw-frame admission attempt. */
    public record AdmissionResult(boolean queued, AdmissionRefusal refusal,
                                   Optional<DrainHandle> newDrain) {
        public AdmissionResult {
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(newDrain, "newDrain");
            if (queued != (refusal == AdmissionRefusal.NONE) || (!queued && newDrain.isPresent())) {
                throw new IllegalArgumentException("admission result is inconsistent");
            }
        }
    }

    private final Map<NetworkManager, Entry> liveEntries = new IdentityHashMap<>();
    private final ReferenceQueue<NetworkManager> collectedManagers = new ReferenceQueue<>();
    private final Map<WeakIdentityKey, TerminalRecord> terminalManagers = new HashMap<>();

    PlayerPhysicsInboundConnections() { }

    /** The client event is the only source of a CLIENTBOUND lifetime. */
    @SideOnly(Side.CLIENT)
    public Optional<ConnectionHandle> bindClientConnected(
            FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Objects.requireNonNull(event, "event");
        NetworkManager manager = Objects.requireNonNull(event.getManager(), "event.manager");
        INetHandlerPlayClient handler = Objects.requireNonNull(event.getHandler(), "event.handler");
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (event.getHandlerType() != INetHandlerPlayClient.class || manager.getNetHandler() != handler) {
                    retireOnlyIfCurrentEntryIsTerminal(manager);
                    return Optional.empty();
                }
                return bind(manager, handler, EnumPacketDirection.CLIENTBOUND);
            }
        }
    }

    /** The server event is the only source of a SERVERBOUND lifetime. */
    public Optional<ConnectionHandle> bindServerConnected(
            FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        Objects.requireNonNull(event, "event");
        NetworkManager manager = Objects.requireNonNull(event.getManager(), "event.manager");
        INetHandlerPlayServer eventHandler = Objects.requireNonNull(event.getHandler(), "event.handler");
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (event.getHandlerType() != INetHandlerPlayServer.class
                        || !(eventHandler instanceof NetHandlerPlayServer handler)
                        || manager.getNetHandler() != handler) {
                    retireOnlyIfCurrentEntryIsTerminal(manager);
                    return Optional.empty();
                }
                return bind(manager, handler, EnumPacketDirection.SERVERBOUND);
            }
        }
    }

    /** Lookup never creates or renews a connection lifetime. */
    public Optional<ConnectionHandle> findCurrentConnection(
            NetworkManager actualManager, INetHandler exactHandler,
            EnumPacketDirection receivingDirection) {
        Objects.requireNonNull(actualManager, "actualManager");
        Objects.requireNonNull(exactHandler, "exactHandler");
        Objects.requireNonNull(receivingDirection, "receivingDirection");
        synchronized (actualManager) {
            synchronized (this) {
                drainCollectedManagers();
                Entry entry = liveEntries.get(actualManager);
                if (entry == null) return Optional.empty();

                InvalidationReason terminalReason = currentTerminalReason(actualManager, entry);
                if (terminalReason != null) {
                    terminate(actualManager, entry, terminalReason);
                    return Optional.empty();
                }
                if (entry.handler != exactHandler || entry.receivingDirection != receivingDirection) {
                    return Optional.empty();
                }
                return Optional.of(entry.handle);
            }
        }
    }

    /**
     * Captures the real readable body without advancing its reader index. The copied envelope is
     * bounded by RawFrame.capture; callers cannot report their own byte count.
     */
    public RawInboundFrame captureRawFrame(ByteBuf actualBody) {
        return new RawInboundFrame(this, ConnectionDecodeBudget.RawFrame.capture(actualBody));
    }

    /**
     * Charges one actual arrival against the captured connection before parsing. Full-frame
     * inspection runs outside the manager and registry monitors; queue admission then rechecks
     * the same Entry identity instead of resolving a replacement lifetime.
     */
    public AdmissionResult admitRawFrame(ConnectionHandle expectedConnection,
            NetworkManager actualManager, INetHandler exactHandler,
            EnumPacketDirection actualDirection, RawInboundFrame raw,
            Optional<ScopeHandle> scope) {
        Objects.requireNonNull(expectedConnection, "expectedConnection");
        Objects.requireNonNull(actualManager, "actualManager");
        Objects.requireNonNull(exactHandler, "exactHandler");
        Objects.requireNonNull(actualDirection, "actualDirection");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(scope, "scope");

        Entry capturedEntry;
        ConnectionDecodeBudget.IngressReceipt receipt;
        synchronized (actualManager) {
            synchronized (this) {
                drainCollectedManagers();
                capturedEntry = liveEntries.get(actualManager);
                if (capturedEntry == null) return refused(AdmissionRefusal.NO_CURRENT_CONNECTION);
                AdmissionRefusal live = currentEntryRefusal(actualManager, capturedEntry,
                        expectedConnection, exactHandler, actualDirection);
                if (live != AdmissionRefusal.NONE) return refused(live);
                if (raw.owner != this || raw.identityToken == null) {
                    return refused(AdmissionRefusal.FOREIGN_RAW_FRAME);
                }
                if (raw.exactEntry != null && raw.exactEntry != capturedEntry) {
                    return refused(AdmissionRefusal.RAW_FRAME_BOUND_TO_DIFFERENT_ENTRY);
                }

                receipt = capturedEntry.budget.reserveIngress(actualManager, exactHandler, raw.frame);
                if (receipt.rejection() != ConnectionDecodeBudget.IngressRejection.WRONG_HANDLER
                        && receipt.rejection() != ConnectionDecodeBudget.IngressRejection.INVALIDATED) {
                    raw.exactEntry = capturedEntry;
                }
                if (!receipt.admitted()) return refused(mapIngressRejection(receipt.rejection()));
            }
        }

        // Parsing is deliberately outside all shared lifecycle monitors. Exceptions remain errors
        // at this boundary and are never rewritten as a typed queue refusal.
        ConnectionDecodeBudget.FullFrameInspection inspection =
                capturedEntry.budget.inspectFullFrame(receipt);

        synchronized (actualManager) {
            synchronized (this) {
                drainCollectedManagers();
                AdmissionRefusal live = currentEntryRefusal(actualManager, capturedEntry,
                        expectedConnection, exactHandler, actualDirection);
                if (live != AdmissionRefusal.NONE) return refused(live);

                ConnectionDecodeBudget.QueueReservation reservation;
                if (actualDirection == EnumPacketDirection.CLIENTBOUND
                        && inspection.kind() == PlayerPhysicsProtocol.Kind.OFFER) {
                    // A first clientbound OFFER is connection-bound even if a caller supplied an
                    // already-retired world scope; its session owner decides whether to open one.
                    reservation = capturedEntry.budget.reserveConnectionOffer(inspection);
                } else {
                    if (scope.isEmpty()) return refused(AdmissionRefusal.MISSING_SCOPE);
                    ScopeHandle suppliedScope = scope.orElseThrow();
                    if (suppliedScope.owner != this || suppliedScope.entry != capturedEntry) {
                        return refused(AdmissionRefusal.FOREIGN_SCOPE);
                    }
                    if (suppliedScope.token.retired()) return refused(AdmissionRefusal.RETIRED_SCOPE);
                    if (!suppliedScope.token.scope().equals(inspection.scope())) {
                        return refused(AdmissionRefusal.PACKET_SCOPE_MISMATCH);
                    }
                    reservation = capturedEntry.budget.reserveQueue(inspection, suppliedScope.token);
                }
                return queueResult(this, capturedEntry, reservation);
            }
        }
    }

    /** Registers a non-authoritative scope for an exact current owner; package-private for owner code/tests. */
    Optional<ScopeHandle> openScopeForOwner(ConnectionHandle expectedConnection,
            PlayerPhysicalSnapshot.Scope actualScope) {
        Objects.requireNonNull(expectedConnection, "expectedConnection");
        Objects.requireNonNull(actualScope, "actualScope");
        NetworkManager manager = expectedConnection.manager.get();
        if (manager == null) return Optional.empty();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                Entry entry = liveEntries.get(manager);
                if (entry == null || entry.handle != expectedConnection) return Optional.empty();
                InvalidationReason terminalReason = currentTerminalReason(manager, entry);
                if (terminalReason != null) {
                    terminate(manager, entry, terminalReason);
                    return Optional.empty();
                }
                ConnectionDecodeBudget.ScopeToken token = entry.budget.openScope(actualScope);
                return Optional.of(new ScopeHandle(this, entry, token, new Object()));
            }
        }
    }

    /** Starts one task, or atomically closes this exact scheduled drain when no ticket remains. */
    public Optional<DecodeHandle> beginNextOrClose(DrainHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (liveEntries.get(manager) != entry && entry.budget.invalidationReason() == null) {
                    throw new IllegalStateException("drain entry is neither current nor terminal");
                }
                Optional<ConnectionDecodeBudget.DecodeLease> lease =
                        entry.budget.beginNextOrClose(handle.permit);
                return lease.map(value -> new DecodeHandle(this, entry, handle.connection,
                        value, new Object()));
            }
        }
    }

    /**
     * Materializes one already-inspected packet. Shared lifecycle locks are released before the
     * real proof/materializer runs; the post-proof fence refuses a stale result.
     */
    public Optional<PlayerPhysicsProtocol.Packet> materializePacket(DecodeHandle handle) throws Throwable {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (currentDecodeRefusal(manager, handle) != AdmissionRefusal.NONE
                        || !handle.lease.installable()) {
                    return Optional.empty();
                }
            }
        }

        PlayerPhysicsProtocol.Packet packet;
        try {
            packet = handle.lease.materializePacket();
        } catch (Throwable originalFailure) {
            try {
                failDecode(handle, originalFailure);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
            }
            throw originalFailure;
        }

        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (currentDecodeRefusal(manager, handle) != AdmissionRefusal.NONE
                        || !handle.lease.installable()) {
                    return Optional.empty();
                }
                handle.materializedKind = packet.kind();
                handle.materializedPacket = packet;
                handle.materialized = true;
                return Optional.of(packet);
            }
        }
    }

    NetworkManager exactManager(DecodeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        return handle.entry.budget.networkManager();
    }

    INetHandler exactHandler(DecodeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        return handle.entry.handler;
    }

    public boolean decodeBelongsToConnection(DecodeHandle decode, ConnectionHandle connection) {
        Objects.requireNonNull(decode, "decode");
        Objects.requireNonNull(connection, "connection");
        requireOwned(decode);
        return decode.connection == connection && decode.entry.handle == connection;
    }

    Optional<PlayerPhysicsProtocol.Packet> exactMaterializedPacket(DecodeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        return handle.materialized && handle.materializedPacket != null
                ? Optional.of(handle.materializedPacket) : Optional.empty();
    }

    /** Opens one new physical scope only for the exact clientbound OFFER just accepted by its owner. */
    Optional<ScopeHandle> openAcceptedOfferScopeForOwner(DecodeHandle exactDecode,
            PlayerPhysicalSnapshot.Scope actualScope) {
        Objects.requireNonNull(exactDecode, "exactDecode");
        Objects.requireNonNull(actualScope, "actualScope");
        requireOwned(exactDecode);
        Entry entry = exactDecode.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (liveEntries.get(manager) != entry
                        || currentDecodeRefusal(manager, exactDecode) != AdmissionRefusal.NONE
                        || !exactDecode.materialized || !exactDecode.lease.installable()) return Optional.empty();
                ConnectionDecodeBudget.Ticket ticket = exactDecode.lease.ticket();
                PlayerPhysicsProtocol.Packet offer = exactDecode.materializedPacket;
                if (!ticket.connectionBound() || exactDecode.materializedKind != PlayerPhysicsProtocol.Kind.OFFER
                        || offer == null || offer.kind() != PlayerPhysicsProtocol.Kind.OFFER
                        || !offer.scope().equals(actualScope) || exactDecode.acceptedOfferScopeOpened) {
                    return Optional.empty();
                }
                ConnectionDecodeBudget.ScopeToken token = entry.budget.openScope(actualScope);
                exactDecode.acceptedOfferScopeOpened = true;
                return Optional.of(new ScopeHandle(this, entry, token, new Object(), exactDecode.identityToken));
            }
        }
    }

    /** Completes an OFFER only when its accepted scope carries this exact decode's provenance. */
    public void finishAcceptedOffer(DecodeHandle exactDecode, ScopeHandle exactScope,
            PlayerPhysicsProtocol.Packet exactOffer) {
        Objects.requireNonNull(exactDecode, "exactDecode");
        Objects.requireNonNull(exactScope, "exactScope");
        Objects.requireNonNull(exactOffer, "exactOffer");
        requireOwned(exactDecode);
        requireOwned(exactScope);
        Entry entry = exactDecode.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (liveEntries.get(manager) != entry
                        || currentDecodeRefusal(manager, exactDecode) != AdmissionRefusal.NONE
                        || exactScope.entry != entry
                        || exactScope.createdFromOfferDecodeIdentity != exactDecode.identityToken
                        || !exactDecode.materialized || !exactDecode.acceptedOfferScopeOpened
                        || exactDecode.materializedPacket != exactOffer
                        || exactDecode.materializedKind != PlayerPhysicsProtocol.Kind.OFFER
                        || exactOffer.kind() != PlayerPhysicsProtocol.Kind.OFFER
                        || !exactScope.token.scope().equals(exactOffer.scope())
                        || !exactDecode.lease.ticket().connectionBound()
                        || !exactDecode.lease.installable()) {
                    throw new IllegalStateException("accepted OFFER scope does not match its exact current decode");
                }
                synchronized (entry.budget) {
                    if (!exactDecode.lease.installable()) {
                        throw new IllegalStateException("accepted OFFER decode is no longer installable");
                    }
                    entry.budget.finish(exactDecode.lease,
                            ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
                }
            }
        }
    }

    /** The current lifetime fence for a fully materialized packet, not an installation permit. */
    public boolean decodeCurrent(DecodeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                return handle.materialized
                        && currentDecodeRefusal(manager, handle) == AdmissionRefusal.NONE
                        && handle.lease.installable();
            }
        }
    }

    /** Revokes future installation for this exact active task without releasing its work early. */
    public void expireDecode(DecodeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                entry.budget.expire(handle.lease);
            }
        }
    }

    /** Checks the exact captured scope token for one current materialized non-OFFER decode. */
    public boolean isCurrentScopedDecode(DecodeHandle exactDecode, ScopeHandle exactScope,
            PlayerPhysicsProtocol.Packet exactPacket) {
        Objects.requireNonNull(exactDecode, "exactDecode");
        Objects.requireNonNull(exactScope, "exactScope");
        Objects.requireNonNull(exactPacket, "exactPacket");
        requireOwned(exactDecode);
        requireOwned(exactScope);
        Entry entry = exactDecode.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (liveEntries.get(manager) != entry || exactScope.entry != entry
                        || exactScope.createdFromOfferDecodeIdentity == null
                        || exactScope.token.retired()
                        || currentDecodeRefusal(manager, exactDecode) != AdmissionRefusal.NONE
                        || !exactDecode.materialized || exactDecode.materializedPacket != exactPacket
                        || exactPacket.kind() == PlayerPhysicsProtocol.Kind.OFFER
                        || exactDecode.materializedKind != exactPacket.kind()
                        || !exactDecode.lease.installable()) return false;
                ConnectionDecodeBudget.Ticket ticket = exactDecode.lease.ticket();
                Optional<ConnectionDecodeBudget.ScopeToken> captured = ticket.scopeToken();
                return !ticket.connectionBound() && captured.isPresent()
                        && captured.orElseThrow() == exactScope.token
                        && exactScope.token.scope().equals(exactPacket.scope());
            }
        }
    }

    /** Scope-bound state installation is narrower than the general scoped native/result path. */
    public boolean isCurrentScopeDecode(DecodeHandle exactDecode, ScopeHandle exactScope,
            PlayerPhysicsProtocol.Packet exactPacket) {
        return exactPacket != null
                && (exactPacket.kind() == PlayerPhysicsProtocol.Kind.OWNED
                || exactPacket.kind() == PlayerPhysicsProtocol.Kind.STATE)
                && isCurrentScopedDecode(exactDecode, exactScope, exactPacket);
    }

    /** Confirms a physical scope was opened by the exact accepted OFFER decode for this owner. */
    public boolean scopeFromAcceptedOffer(ScopeHandle exactScope, DecodeHandle offerDecode,
            ConnectionHandle exactConnection, PlayerPhysicalSnapshot.Scope exactOfferScope) {
        Objects.requireNonNull(exactScope, "exactScope");
        Objects.requireNonNull(offerDecode, "offerDecode");
        Objects.requireNonNull(exactConnection, "exactConnection");
        Objects.requireNonNull(exactOfferScope, "exactOfferScope");
        requireOwned(exactScope);
        requireOwned(offerDecode);
        Entry entry = offerDecode.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                return liveEntries.get(manager) == entry && entry.handle == exactConnection
                        && exactScope.entry == entry && exactScope.owner == this
                        && exactScope.createdFromOfferDecodeIdentity == offerDecode.identityToken
                        && !exactScope.token.retired()
                        && exactScope.token.scope().equals(exactOfferScope)
                        && currentTerminalReason(manager, entry) == null;
            }
        }
    }

    /**
     * Records caller-reported successful native, presentation, and session installation for an
     * exact scope-bound OWNED or STATE packet. This method does not perform that installation.
     */
    public void finishInstalledDecode(DecodeHandle exactDecode, ScopeHandle exactScope) {
        Objects.requireNonNull(exactDecode, "exactDecode");
        Objects.requireNonNull(exactScope, "exactScope");
        requireOwned(exactDecode);
        requireOwned(exactScope);
        Entry entry = exactDecode.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (currentDecodeRefusal(manager, exactDecode) != AdmissionRefusal.NONE) {
                    throw new IllegalStateException("decode entry is no longer current");
                }
                if (exactScope.entry != entry) {
                    throw new IllegalArgumentException("scope handle belongs to a different connection entry");
                }
                if (!exactDecode.materialized) {
                    throw new IllegalStateException("decode must be materialized before installation can finish");
                }
                ConnectionDecodeBudget.Ticket ticket = exactDecode.lease.ticket();
                if (ticket.connectionBound()) {
                    throw new IllegalStateException("connection-bound OFFER cannot finish as installed");
                }
                PlayerPhysicsProtocol.Kind kind = exactDecode.materializedKind;
                if (kind == null) {
                    throw new IllegalStateException("materialized decode has no packet kind identity");
                }
                if (kind != PlayerPhysicsProtocol.Kind.OWNED && kind != PlayerPhysicsProtocol.Kind.STATE) {
                    throw new IllegalStateException("only OWNED or STATE packets can finish as installed");
                }
                Optional<ConnectionDecodeBudget.ScopeToken> ticketScope = ticket.scopeToken();
                if (ticketScope.isEmpty()) {
                    throw new IllegalStateException("scope-bound installation has no captured ticket scope");
                }
                if (ticketScope.orElseThrow() != exactScope.token) {
                    throw new IllegalArgumentException("scope handle does not match the captured ticket scope");
                }
                if (!exactDecode.lease.installable()) {
                    throw new IllegalStateException("decode lease is no longer installable");
                }
                synchronized (entry.budget) {
                    if (!exactDecode.lease.installable()) {
                        throw new IllegalStateException("decode lease is no longer installable");
                    }
                    entry.budget.markInstallationCommitted(exactDecode.lease);
                    entry.budget.finish(exactDecode.lease,
                            ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
                }
            }
        }
    }

    /** Completes the exact task after the owner's real decode/proof path has exited. */
    public void finishDecode(DecodeHandle handle, DecodeOutcome outcome) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(outcome, "outcome");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                if (outcome == DecodeOutcome.PROOF_ACCEPTED
                        && (!handle.materialized
                        || currentDecodeRefusal(manager, handle) != AdmissionRefusal.NONE
                        || !handle.lease.installable())) {
                    throw new IllegalStateException("stale or unmaterialized decode cannot finish as accepted");
                }
                entry.budget.finish(handle.lease, outcome == DecodeOutcome.PROOF_ACCEPTED
                        ? ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED
                        : ConnectionDecodeBudget.DecodeOutcome.REJECTED);
            }
        }
    }

    /** Records and returns the same original task failure after closing its exact active lease. */
    public Throwable failDecode(DecodeHandle handle, Throwable originalCause) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(originalCause, "originalCause");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                entry.budget.failed(handle.lease, decodeFailure(originalCause), originalCause);
            }
        }
        return originalCause;
    }

    /** Cancels the exact scheduled queue and returns the identical scheduler cause to its caller. */
    public Throwable drainSchedulingFailed(DrainHandle handle, Throwable originalCause) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(originalCause, "originalCause");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                return entry.budget.drainFailed(handle.permit, originalCause);
            }
        }
    }

    /** Fails the exact owner task and closes only its captured drain and queued leases. */
    public Throwable ownerDrainFailed(DrainHandle exactDrain, DecodeHandle exactActiveDecode,
            Throwable originalCause) {
        Objects.requireNonNull(exactDrain, "exactDrain");
        Objects.requireNonNull(originalCause, "originalCause");
        requireOwned(exactDrain);
        if (exactActiveDecode != null) {
            requireOwned(exactActiveDecode);
            if (exactActiveDecode.entry != exactDrain.entry)
                throw new IllegalArgumentException("failed decode does not belong to the captured drain connection");
        }
        Entry entry = exactDrain.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                return entry.budget.ownerDrainFailed(exactDrain.permit,
                        exactActiveDecode == null ? null : exactActiveDecode.lease,
                        exactActiveDecode == null ? null : decodeFailure(originalCause), originalCause);
            }
        }
    }

    /** Cancels only queued work captured by this exact scope; an active lease remains owned by its task. */
    public void retireScope(ScopeHandle handle) {
        Objects.requireNonNull(handle, "handle");
        requireOwned(handle);
        Entry entry = handle.entry;
        NetworkManager manager = entry.budget.networkManager();
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                entry.budget.retireScope(handle.token);
            }
        }
    }

    private AdmissionRefusal currentEntryRefusal(NetworkManager manager, Entry entry,
            ConnectionHandle expectedConnection, INetHandler exactHandler,
            EnumPacketDirection actualDirection) {
        if (liveEntries.get(manager) != entry) return AdmissionRefusal.STALE_CONNECTION;
        if (entry.handle != expectedConnection) return AdmissionRefusal.FOREIGN_CONNECTION;
        InvalidationReason terminalReason = currentTerminalReason(manager, entry);
        if (terminalReason != null) {
            terminate(manager, entry, terminalReason);
            return mapTerminalReason(terminalReason);
        }
        if (entry.handler != exactHandler) return AdmissionRefusal.WRONG_HANDLER;
        if (entry.receivingDirection != actualDirection || manager.getDirection() != actualDirection) {
            return AdmissionRefusal.WRONG_DIRECTION;
        }
        return AdmissionRefusal.NONE;
    }

    private AdmissionRefusal currentDecodeRefusal(NetworkManager manager, DecodeHandle handle) {
        return currentEntryRefusal(manager, handle.entry, handle.connection,
                handle.entry.handler, handle.entry.receivingDirection);
    }

    private static AdmissionRefusal mapTerminalReason(InvalidationReason reason) {
        return switch (reason) {
            case CHANNEL_CLOSED -> AdmissionRefusal.CHANNEL_CLOSED;
            case REPLACED, DISCONNECT, CHANNEL_UNREGISTERED -> AdmissionRefusal.STALE_CONNECTION;
        };
    }

    private static AdmissionRefusal mapIngressRejection(ConnectionDecodeBudget.IngressRejection rejection) {
        return switch (rejection) {
            case WRONG_HANDLER -> AdmissionRefusal.WRONG_HANDLER;
            case INVALIDATED -> AdmissionRefusal.INVALIDATED;
            case ARRIVAL_COUNT -> AdmissionRefusal.ARRIVAL_COUNT;
            case ARRIVAL_BYTES -> AdmissionRefusal.ARRIVAL_BYTES;
            case NONE -> throw new IllegalStateException("admitted ingress cannot map to a refusal");
        };
    }

    private static AdmissionResult queueResult(PlayerPhysicsInboundConnections owner, Entry entry,
            ConnectionDecodeBudget.QueueReservation reservation) {
        if (!reservation.queued()) {
            AdmissionRefusal refusal = switch (reservation.rejection()) {
                case INVALIDATED -> AdmissionRefusal.INVALIDATED;
                case RETIRED_SCOPE -> AdmissionRefusal.RETIRED_SCOPE;
                case OUTSTANDING_LIMIT -> AdmissionRefusal.OUTSTANDING_LIMIT;
                case INGRESS_DENIED -> throw new IllegalStateException(
                        "an admitted receipt was rejected as unadmitted during queue admission");
                case NONE -> throw new IllegalStateException("queue rejection has no refusal reason");
            };
            return refused(refusal);
        }
        return new AdmissionResult(true, AdmissionRefusal.NONE,
                reservation.newDrainPermit().map(permit -> new DrainHandle(
                        owner, entry, entry.handle, permit, new Object())));
    }

    private static AdmissionResult refused(AdmissionRefusal refusal) {
        if (refusal == AdmissionRefusal.NONE) throw new IllegalArgumentException("refusal must be non-NONE");
        return new AdmissionResult(false, refusal, Optional.empty());
    }

    private void requireOwned(ScopeHandle handle) {
        if (handle.owner != this || handle.entry == null || handle.token == null || handle.identityToken == null) {
            throw new IllegalArgumentException("scope handle belongs to another inbound connection facade");
        }
    }

    private void requireOwned(DrainHandle handle) {
        if (handle.owner != this || handle.entry == null || handle.permit == null || handle.identityToken == null) {
            throw new IllegalArgumentException("drain handle belongs to another inbound connection facade");
        }
    }

    private void requireOwned(DecodeHandle handle) {
        if (handle.owner != this || handle.entry == null || handle.lease == null || handle.identityToken == null) {
            throw new IllegalArgumentException("decode handle belongs to another inbound connection facade");
        }
    }

    private static ConnectionDecodeBudget.DecodeFailure decodeFailure(Throwable cause) {
        if (cause instanceof NumericWorkExhaustedException) {
            return ConnectionDecodeBudget.DecodeFailure.NUMERIC_WORK_EXHAUSTED;
        }
        if (cause instanceof IllegalArgumentException) return ConnectionDecodeBudget.DecodeFailure.DECODE_REJECTED;
        return ConnectionDecodeBudget.DecodeFailure.UNEXPECTED;
    }

    /** Revocation is bound to the exact handle object and lifetime token returned by this facade. */
    public boolean invalidateConnection(ConnectionHandle expectedHandle,
            InvalidationReason reason) {
        Objects.requireNonNull(expectedHandle, "expectedHandle");
        Objects.requireNonNull(reason, "reason");
        NetworkManager manager = expectedHandle.manager.get();
        if (manager == null) return false;
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                Entry entry = liveEntries.get(manager);
                if (entry == null || entry.handle != expectedHandle
                        || entry.handle.lifetimeIdentity != expectedHandle.lifetimeIdentity) {
                    return false;
                }
                terminate(manager, entry, reason);
                return true;
            }
        }
    }

    /** Called immediately before NetworkManager installs its next packet listener. */
    public void beforeActualHandlerAssignment(NetworkManager manager,
            INetHandler expectedPrevious, INetHandler next) {
        Objects.requireNonNull(manager, "manager");
        if (!Thread.holdsLock(manager)) {
            throw new IllegalStateException("NetworkManager monitor must be held before handler replacement");
        }
        Objects.requireNonNull(next, "next");
        if (manager.getNetHandler() != expectedPrevious) {
            throw new IllegalStateException("NetworkManager packet listener changed before replacement");
        }
        if (expectedPrevious == next) return;

        synchronized (this) {
            drainCollectedManagers();
            Entry entry = liveEntries.get(manager);
            if (entry != null) terminate(manager, entry, InvalidationReason.REPLACED);
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void onClientConnectedEvent(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        INSTANCE.bindClientConnected(event);
    }

    @SubscribeEvent
    public static void onServerConnectedEvent(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        INSTANCE.bindServerConnected(event);
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public static void onClientDisconnectedEvent(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        INSTANCE.onClientDisconnected(event);
    }

    @SubscribeEvent
    public static void onServerDisconnectedEvent(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        INSTANCE.onServerDisconnected(event);
    }

    @SubscribeEvent
    public static void onCustomPacketRegistrationEvent(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        INSTANCE.onCustomPacketRegistration(event);
    }

    @SideOnly(Side.CLIENT)
    void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Objects.requireNonNull(event, "event");
        NetworkManager manager = Objects.requireNonNull(event.getManager(), "event.manager");
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                retireManager(manager, InvalidationReason.DISCONNECT);
            }
        }
    }

    void onServerDisconnected(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        Objects.requireNonNull(event, "event");
        NetworkManager manager = Objects.requireNonNull(event.getManager(), "event.manager");
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                retireManager(manager, InvalidationReason.DISCONNECT);
            }
        }
    }

    void onCustomPacketRegistration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        Objects.requireNonNull(event, "event");
        if (!"UNREGISTER".equals(event.getOperation())
                || !event.getRegistrations().contains(PlayerPhysicsProtocol.CHANNEL)) {
            // Irrelevant registration events only drain weak-map cleanup; they never inspect or wait on a manager.
            synchronized (this) {
                drainCollectedManagers();
            }
            return;
        }

        NetworkManager manager = Objects.requireNonNull(event.getManager(), "event.manager");
        synchronized (manager) {
            synchronized (this) {
                drainCollectedManagers();
                Entry entry = liveEntries.get(manager);
                if (entry == null) return;

                EnumPacketDirection eventDirection;
                if (event.getSide() == Side.CLIENT
                        && event.getHandlerType() == INetHandlerPlayClient.class
                        && event.getHandler() instanceof INetHandlerPlayClient) {
                    eventDirection = EnumPacketDirection.CLIENTBOUND;
                } else if (event.getSide() == Side.SERVER
                        && event.getHandlerType() == INetHandlerPlayServer.class
                        && event.getHandler() instanceof INetHandlerPlayServer) {
                    eventDirection = EnumPacketDirection.SERVERBOUND;
                } else {
                    return;
                }

                if (entry.handler != event.getHandler() || entry.receivingDirection != eventDirection) return;

                InvalidationReason terminalReason = currentTerminalReason(manager, entry);
                terminate(manager, entry, terminalReason == null
                        ? InvalidationReason.CHANNEL_UNREGISTERED : terminalReason);
            }
        }
    }

    private Optional<ConnectionHandle> bind(NetworkManager manager, INetHandler handler,
            EnumPacketDirection receivingDirection) {
        Entry current = liveEntries.get(manager);
        if (current != null) {
            InvalidationReason terminalReason = currentTerminalReason(manager, current);
            if (terminalReason != null) {
                terminate(manager, current, terminalReason);
                return Optional.empty();
            }
            if (current.handler != handler || current.receivingDirection != receivingDirection) {
                return Optional.empty();
            }
            return Optional.of(current.handle);
        }
        if (hasTerminalRecord(manager)) return Optional.empty();

        InvalidationReason invalidReason = newBindingFailureReason(
                manager, handler, receivingDirection);
        if (invalidReason != null) return Optional.empty();

        ConnectionDecodeBudget budget = new ConnectionDecodeBudget(manager, handler, receivingDirection,
                ConnectionDecodeBudget.Limits.defaults(), System::nanoTime);
        ConnectionHandle handle = new ConnectionHandle(manager, new Object());
        Entry created = new Entry(handler, receivingDirection, budget, handle);

        InvalidationReason racedInvalidReason = currentTerminalReason(manager, created);
        if (racedInvalidReason != null) {
            budget.invalidate(manager, budget.lifetimeToken(), budgetReason(racedInvalidReason));
            rememberTerminal(manager, racedInvalidReason, handle.lifetimeIdentity);
            return Optional.empty();
        }
        liveEntries.put(manager, created);
        return Optional.of(handle);
    }

    private InvalidationReason newBindingFailureReason(NetworkManager manager,
            INetHandler handler, EnumPacketDirection receivingDirection) {
        if (!manager.isChannelOpen()) return InvalidationReason.CHANNEL_CLOSED;
        if (manager.getNetHandler() != handler) return InvalidationReason.REPLACED;
        if (manager.getDirection() != receivingDirection) return InvalidationReason.REPLACED;
        if (handler instanceof NetHandlerPlayServer serverHandler
                && !serverConnectionIsCurrent(manager, serverHandler)) {
            return InvalidationReason.REPLACED;
        }
        return null;
    }

    private InvalidationReason currentTerminalReason(NetworkManager manager, Entry entry) {
        if (!manager.isChannelOpen()) return InvalidationReason.CHANNEL_CLOSED;
        if (manager.getNetHandler() != entry.handler || manager.getDirection() != entry.receivingDirection) {
            return InvalidationReason.REPLACED;
        }
        if (entry.handler instanceof NetHandlerPlayServer serverHandler
                && !serverConnectionIsCurrent(manager, serverHandler)) {
            return InvalidationReason.REPLACED;
        }
        return null;
    }

    private static boolean serverConnectionIsCurrent(NetworkManager manager, NetHandlerPlayServer handler) {
        return handler.netManager == manager && handler.player != null && handler.player.connection == handler;
    }

    private void retireOnlyIfCurrentEntryIsTerminal(NetworkManager manager) {
        Entry entry = liveEntries.get(manager);
        if (entry == null) return;
        InvalidationReason reason = currentTerminalReason(manager, entry);
        if (reason != null) terminate(manager, entry, reason);
    }

    private void retireManager(NetworkManager manager, InvalidationReason reason) {
        Entry entry = liveEntries.get(manager);
        if (entry != null) {
            terminate(manager, entry, reason);
        } else if (!hasTerminalRecord(manager)) {
            rememberTerminal(manager, reason, new Object());
        }
    }

    private void terminate(NetworkManager manager, Entry entry,
            InvalidationReason reason) {
        entry.budget.invalidate(manager, entry.budget.lifetimeToken(), budgetReason(reason));
        if (liveEntries.get(manager) == entry) liveEntries.remove(manager);
        rememberTerminal(manager, reason, entry.handle.lifetimeIdentity);
    }

    private static ConnectionDecodeBudget.InvalidationReason budgetReason(InvalidationReason reason) {
        return switch (reason) {
            case DISCONNECT -> ConnectionDecodeBudget.InvalidationReason.DISCONNECT;
            case CHANNEL_UNREGISTERED -> {
                // Protocol unregistration withdraws this budget's permission; it does not mean Netty closed.
                yield ConnectionDecodeBudget.InvalidationReason.DISCONNECT;
            }
            case REPLACED -> ConnectionDecodeBudget.InvalidationReason.REPLACED;
            case CHANNEL_CLOSED -> ConnectionDecodeBudget.InvalidationReason.CHANNEL_CLOSED;
        };
    }

    private boolean hasTerminalRecord(NetworkManager manager) {
        return terminalManagers.containsKey(new WeakIdentityKey(manager));
    }

    private void rememberTerminal(NetworkManager manager,
            InvalidationReason reason, Object lifetimeIdentity) {
        WeakIdentityKey lookup = new WeakIdentityKey(manager);
        if (!terminalManagers.containsKey(lookup)) {
            terminalManagers.put(new WeakIdentityKey(manager, collectedManagers),
                    new TerminalRecord(reason, lifetimeIdentity));
        }
    }

    private void drainCollectedManagers() {
        // Queue cleanup only removes weak-key map entries; it never acquires a manager monitor.
        java.lang.ref.Reference<? extends NetworkManager> collected;
        while ((collected = collectedManagers.poll()) != null) terminalManagers.remove(collected);
    }

    /** An opaque lifetime identity that cannot keep a disconnected manager alive. */
    public static final class ConnectionHandle {
        private final WeakReference<NetworkManager> manager;
        private final Object lifetimeIdentity;

        private ConnectionHandle(NetworkManager manager, Object lifetimeIdentity) {
            this.manager = new WeakReference<>(manager);
            this.lifetimeIdentity = lifetimeIdentity;
        }
    }

    /** Immutable raw bounded body; admission binds it once to the exact charged Entry. */
    public static final class RawInboundFrame {
        private final PlayerPhysicsInboundConnections owner;
        private final ConnectionDecodeBudget.RawFrame frame;
        private final Object identityToken;
        private volatile Entry exactEntry;

        private RawInboundFrame(PlayerPhysicsInboundConnections owner,
                ConnectionDecodeBudget.RawFrame frame) {
            this.owner = owner;
            this.frame = frame;
            this.identityToken = new Object();
        }
    }

    /** Opaque connection-local scope token; it grants no packet or installation authority. */
    public static final class ScopeHandle {
        private final PlayerPhysicsInboundConnections owner;
        private final Entry entry;
        private final ConnectionDecodeBudget.ScopeToken token;
        private final Object identityToken;
        private final Object createdFromOfferDecodeIdentity;

        private ScopeHandle(PlayerPhysicsInboundConnections owner, Entry entry,
                ConnectionDecodeBudget.ScopeToken token, Object identityToken) {
            this(owner, entry, token, identityToken, null);
        }

        private ScopeHandle(PlayerPhysicsInboundConnections owner, Entry entry,
                ConnectionDecodeBudget.ScopeToken token, Object identityToken,
                Object createdFromOfferDecodeIdentity) {
            this.owner = owner;
            this.entry = entry;
            this.token = token;
            this.identityToken = identityToken;
            this.createdFromOfferDecodeIdentity = createdFromOfferDecodeIdentity;
        }
    }

    /** Opaque one-scheduling-obligation drain permit. */
    public static final class DrainHandle {
        private final PlayerPhysicsInboundConnections owner;
        private final Entry entry;
        private final ConnectionHandle connection;
        private final ConnectionDecodeBudget.DrainPermit permit;
        private final Object identityToken;

        private DrainHandle(PlayerPhysicsInboundConnections owner, Entry entry,
                ConnectionHandle connection, ConnectionDecodeBudget.DrainPermit permit,
                Object identityToken) {
            this.owner = owner;
            this.entry = entry;
            this.connection = connection;
            this.permit = permit;
            this.identityToken = identityToken;
        }
    }

    /** Opaque single active decode lease; numeric work and the lease itself never escape. */
    public static final class DecodeHandle {
        private final PlayerPhysicsInboundConnections owner;
        private final Entry entry;
        private final ConnectionHandle connection;
        private final ConnectionDecodeBudget.DecodeLease lease;
        private final Object identityToken;
        private volatile boolean materialized;
        private volatile PlayerPhysicsProtocol.Kind materializedKind;
        private volatile PlayerPhysicsProtocol.Packet materializedPacket;
        private boolean acceptedOfferScopeOpened;

        /** Exact task completion only; it does not prove successful installation or current custody. */
        public boolean completed() { return lease.completed(); }

        private DecodeHandle(PlayerPhysicsInboundConnections owner, Entry entry,
                ConnectionHandle connection, ConnectionDecodeBudget.DecodeLease lease,
                Object identityToken) {
            this.owner = owner;
            this.entry = entry;
            this.connection = connection;
            this.lease = lease;
            this.identityToken = identityToken;
        }
    }

    private static final class Entry {
        private final INetHandler handler;
        private final EnumPacketDirection receivingDirection;
        private final ConnectionDecodeBudget budget;
        private final ConnectionHandle handle;

        private Entry(INetHandler handler, EnumPacketDirection receivingDirection,
                ConnectionDecodeBudget budget, ConnectionHandle handle) {
            this.handler = handler;
            this.receivingDirection = receivingDirection;
            this.budget = budget;
            this.handle = handle;
        }
    }

    private static final class TerminalRecord {
        private final InvalidationReason reason;
        private final Object lifetimeIdentity;

        private TerminalRecord(InvalidationReason reason, Object lifetimeIdentity) {
            this.reason = reason;
            this.lifetimeIdentity = lifetimeIdentity;
        }
    }

    private static final class WeakIdentityKey extends WeakReference<NetworkManager> {
        private final int identityHash;

        private WeakIdentityKey(NetworkManager manager) {
            super(Objects.requireNonNull(manager, "manager"));
            identityHash = System.identityHashCode(manager);
        }

        private WeakIdentityKey(NetworkManager manager, ReferenceQueue<NetworkManager> queue) {
            super(Objects.requireNonNull(manager, "manager"), queue);
            identityHash = System.identityHashCode(manager);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WeakIdentityKey that)) return false;
            NetworkManager manager = get();
            return manager != null && manager == that.get();
        }
    }
}
