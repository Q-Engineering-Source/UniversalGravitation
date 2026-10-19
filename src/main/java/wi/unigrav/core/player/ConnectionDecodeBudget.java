package wi.unigrav.core.player;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;

/**
 * Per-connection, per-receive-direction admission and decode-work ledger.
 *
 * <p>This is a core state machine only. The current {@code Message.fromBytes}
 * path decodes before a connection handler is available, so production owners
 * must still connect this ledger at their real ingress and lifecycle boundaries.</p>
 */
final class ConnectionDecodeBudget {
    static final long WINDOW_NANOS = 1_000_000_000L;
    static final long NUMERIC_WORK_LIMIT = StoredDyadic.MAX_WORK_UNITS;
    static final int MAX_FRAME_BYTES = PlayerPhysicsProtocol.MAX_BYTES;

    static final int DEFAULT_DATA_ARRIVALS = 64;
    static final int DEFAULT_CONTROL_ARRIVALS = 64;
    static final int DEFAULT_DATA_BODY_BYTES = 32_768;
    static final int DEFAULT_CONTROL_BODY_BYTES = 32_768;
    static final int DEFAULT_OUTSTANDING_DATA = 8;
    static final int DEFAULT_OUTSTANDING_CONTROL = 4;
    static final long DEFAULT_QUEUED_TTL_NANOS = 1_000_000_000L;
    private static final EnumMap<PlayerPhysicsProtocol.Kind, Integer> MIN_CONTROL_LENGTHS =
            minimumControlLengths();

    enum TrafficClass { DATA, CONTROL }
    enum IngressRejection { NONE, WRONG_HANDLER, INVALIDATED, ARRIVAL_COUNT, ARRIVAL_BYTES }
    enum QueueRejection { NONE, INGRESS_DENIED, INVALIDATED, RETIRED_SCOPE, OUTSTANDING_LIMIT }
    enum TicketState { QUEUED, DECODING, COMPLETED, CANCELLED }
    enum DecodeOutcome { PROOF_ACCEPTED, REJECTED }
    enum DecodeFailure { NUMERIC_WORK_EXHAUSTED, DECODE_REJECTED, UNEXPECTED }
    enum InvalidationReason { DISCONNECT, REPLACED, CHANNEL_CLOSED }

    /**
     * Limits can tighten the frozen contract, never widen it. Window and
     * numeric-work limits intentionally have no configuration fields.
     */
    record Limits(int dataArrivalsPerWindow,
                  int controlArrivalsPerWindow,
                  int dataBodyBytesPerWindow,
                  int controlBodyBytesPerWindow,
                  int outstandingData,
                  int outstandingControl,
                  long queuedTtlNanos) {
        Limits {
            requireRange(dataArrivalsPerWindow, 1, DEFAULT_DATA_ARRIVALS, "dataArrivalsPerWindow");
            requireRange(controlArrivalsPerWindow, 1, DEFAULT_CONTROL_ARRIVALS, "controlArrivalsPerWindow");
            requireRange(dataBodyBytesPerWindow, 1, DEFAULT_DATA_BODY_BYTES, "dataBodyBytesPerWindow");
            requireRange(controlBodyBytesPerWindow, 1, DEFAULT_CONTROL_BODY_BYTES, "controlBodyBytesPerWindow");
            requireRange(outstandingData, 1, DEFAULT_OUTSTANDING_DATA, "outstandingData");
            requireRange(outstandingControl, 1, DEFAULT_OUTSTANDING_CONTROL, "outstandingControl");
            if (queuedTtlNanos < 1 || queuedTtlNanos > DEFAULT_QUEUED_TTL_NANOS) {
                throw new IllegalArgumentException("queuedTtlNanos must be within 1.."
                        + DEFAULT_QUEUED_TTL_NANOS);
            }
        }

        static Limits defaults() {
            return new Limits(DEFAULT_DATA_ARRIVALS, DEFAULT_CONTROL_ARRIVALS,
                    DEFAULT_DATA_BODY_BYTES, DEFAULT_CONTROL_BODY_BYTES,
                    DEFAULT_OUTSTANDING_DATA, DEFAULT_OUTSTANDING_CONTROL,
                    DEFAULT_QUEUED_TTL_NANOS);
        }

        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be within " + minimum + ".." + maximum);
            }
        }
    }

    /** queued counts cover pending tickets; outstanding counts also include the active lease. */
    record Snapshot(long windowStartedAtNanos,
                    long windowSequence,
                    long dataArrivals,
                    long dataBodyBytes,
                    long controlArrivals,
                    long controlBodyBytes,
                    int queuedData,
                    int queuedControl,
                    int outstandingData,
                    int outstandingControl,
                    boolean activeDecode,
                    boolean drainOutstanding,
                    boolean invalidated) { }

    record QueueReservation(boolean queued,
                            QueueRejection rejection,
                            Optional<Ticket> ticket,
                            Optional<DrainPermit> newDrainPermit) {
        QueueReservation {
            Objects.requireNonNull(rejection, "rejection");
            Objects.requireNonNull(ticket, "ticket");
            Objects.requireNonNull(newDrainPermit, "newDrainPermit");
            if (queued != ticket.isPresent() || queued != (rejection == QueueRejection.NONE)) {
                throw new IllegalArgumentException("Queue reservation result is inconsistent");
            }
        }
    }

    /** Actual bounded copy of one caller-owned network body. */
    static final class RawFrame {
        private final int actualBodyBytes;
        private final byte[] boundedBytes;
        private ConnectionDecodeBudget claimedBy;

        private RawFrame(int actualBodyBytes, byte[] boundedBytes) {
            this.actualBodyBytes = actualBodyBytes;
            this.boundedBytes = boundedBytes;
        }

        static RawFrame capture(ByteBuf body) {
            Objects.requireNonNull(body, "body");
            int readable = body.readableBytes();
            int copied = Math.min(readable, MAX_FRAME_BYTES);
            byte[] bytes = new byte[copied];
            if (copied != 0) body.getBytes(body.readerIndex(), bytes);
            return new RawFrame(readable, bytes);
        }

        int actualBodyBytes() { return actualBodyBytes; }
        int copiedBytes() { return boundedBytes.length; }

        private int unsignedByteAt(int offset) {
            if (offset < 0 || offset >= boundedBytes.length) {
                throw new IndexOutOfBoundsException("raw frame header is not present");
            }
            return Byte.toUnsignedInt(boundedBytes[offset]);
        }

        private byte[] parserBytes() {
            if (actualBodyBytes > MAX_FRAME_BYTES) {
                throw new IllegalArgumentException("UG frame exceeds the bounded decode envelope");
            }
            return boundedBytes.clone();
        }

        private synchronized boolean claim(ConnectionDecodeBudget owner) {
            if (claimedBy != null) return false;
            claimedBy = owner;
            return true;
        }
    }

    /** Opaque connection-local identity; a token cannot be supplied to another ledger. */
    static final class LifetimeToken {
        private LifetimeToken() { }
    }

    /** Opaque scope generation. Packet input cannot create or revive one. */
    static final class ScopeToken {
        private final ConnectionDecodeBudget owner;
        private final PlayerPhysicalSnapshot.Scope scope;
        private boolean retired;

        private ScopeToken(ConnectionDecodeBudget owner, PlayerPhysicalSnapshot.Scope scope) {
            this.owner = owner;
            this.scope = scope;
        }

        PlayerPhysicalSnapshot.Scope scope() { return scope; }
        boolean retired() { synchronized (owner) { return retired; } }
    }

    private sealed interface TicketTarget permits ScopeBoundTarget, ConnectionBoundTarget { }

    private record ScopeBoundTarget(ScopeToken scope) implements TicketTarget {
        private ScopeBoundTarget {
            Objects.requireNonNull(scope, "scope");
        }
    }

    private record ConnectionBoundTarget(LifetimeToken lifetime) implements TicketTarget {
        private ConnectionBoundTarget {
            Objects.requireNonNull(lifetime, "lifetime");
        }
    }

    static final class IngressReceipt {
        private final ConnectionDecodeBudget owner;
        private final RawFrame frame;
        private final TrafficClass trafficClass;
        private final boolean admitted;
        private final IngressRejection rejection;
        private final long arrivalWindow;
        private boolean inspected;

        private IngressReceipt(ConnectionDecodeBudget owner, RawFrame frame,
                               TrafficClass trafficClass, boolean admitted,
                               IngressRejection rejection, long arrivalWindow) {
            this.owner = owner;
            this.frame = frame;
            this.trafficClass = trafficClass;
            this.admitted = admitted;
            this.rejection = rejection;
            this.arrivalWindow = arrivalWindow;
        }

        RawFrame frame() { return frame; }
        TrafficClass trafficClass() { return trafficClass; }
        boolean admitted() { return admitted; }
        IngressRejection rejection() { return rejection; }
        long arrivalWindow() { return arrivalWindow; }
    }

    /**
     * Unforgeable output of the full protocol parser before Free proof restoration.
     * It carries immutable structural metadata; materialization remains lease-owned.
     */
    static final class FullFrameInspection {
        private final ConnectionDecodeBudget owner;
        private final IngressReceipt ingress;
        private final PlayerPhysicsProtocol.InspectedFrame inspectedFrame;
        private boolean consumed;

        private FullFrameInspection(ConnectionDecodeBudget owner, IngressReceipt ingress,
                                    PlayerPhysicsProtocol.InspectedFrame inspectedFrame) {
            this.owner = owner;
            this.ingress = ingress;
            this.inspectedFrame = Objects.requireNonNull(inspectedFrame, "inspectedFrame");
        }

        PlayerPhysicsProtocol.Kind kind() { return inspectedFrame.kind(); }
        PlayerPhysicalSnapshot.Scope scope() { return inspectedFrame.scope(); }
        int encodedPacketBytes() { return inspectedFrame.encodedPacketBytes(); }
        RawFrame frame() { return ingress.frame; }
        boolean noFreeControlRecovery() { return inspectedFrame.noFreeRecoveryControl(); }
        boolean hasFreePlacement() { return inspectedFrame.hasFreePlacement(); }
        boolean requiresNumericProof() { return inspectedFrame.requiresNumericWork(); }
    }

    static final class Ticket {
        private final ConnectionDecodeBudget owner;
        private final long sequence;
        private final IngressReceipt ingress;
        private final TicketTarget target;
        private final long queuedAtNanos;
        private final FullFrameInspection inspection;
        private TicketState state = TicketState.QUEUED;
        private DecodeOutcome outcome;
        private DecodeFailure failure;
        private Throwable failureCause;

        private Ticket(ConnectionDecodeBudget owner, long sequence,
                       IngressReceipt ingress, TicketTarget target,
                       long queuedAtNanos, FullFrameInspection inspection) {
            this.owner = owner;
            this.sequence = sequence;
            this.ingress = ingress;
            this.target = target;
            this.queuedAtNanos = queuedAtNanos;
            this.inspection = inspection;
        }

        long sequence() { return sequence; }
        RawFrame frame() { return ingress.frame; }
        TrafficClass trafficClass() { return ingress.trafficClass; }
        ScopeToken scope() {
            return scopeToken().orElseThrow(
                    () -> new IllegalStateException("connection-bound ticket has no world scope"));
        }
        Optional<ScopeToken> scopeToken() {
            return target instanceof ScopeBoundTarget scoped
                    ? Optional.of(scoped.scope()) : Optional.empty();
        }
        boolean connectionBound() { return target instanceof ConnectionBoundTarget; }
        TicketState state() { synchronized (owner) { return state; } }
        Optional<DecodeOutcome> outcome() { synchronized (owner) { return Optional.ofNullable(outcome); } }
        Optional<DecodeFailure> failure() { synchronized (owner) { return Optional.ofNullable(failure); } }
        Optional<Throwable> failureCause() { synchronized (owner) { return Optional.ofNullable(failureCause); } }
    }

    /** One scheduler obligation; cancelled ticket no-ops still drain through it. */
    static final class DrainPermit {
        private final ConnectionDecodeBudget owner;
        private final long sequence;
        private boolean finished;

        private DrainPermit(ConnectionDecodeBudget owner, long sequence) {
            this.owner = owner;
            this.sequence = sequence;
        }

        long sequence() { return sequence; }
        boolean finished() { synchronized (owner) { return finished; } }
    }

    static final class DecodeLease {
        private final ConnectionDecodeBudget owner;
        private final Ticket ticket;
        private final long startedAtNanos;
        private final long workWindow;
        private final StoredDyadic.Work numericWork;
        private boolean materializationStarted;
        private boolean installationExpired;
        private boolean completed;
        private boolean installationCommitted;
        private DecodeOutcome outcome;
        private DecodeFailure failure;
        private Throwable failureCause;

        private DecodeLease(ConnectionDecodeBudget owner, Ticket ticket,
                            long startedAtNanos, long workWindow,
                            StoredDyadic.Work numericWork) {
            this.owner = owner;
            this.ticket = ticket;
            this.startedAtNanos = startedAtNanos;
            this.workWindow = workWindow;
            this.numericWork = numericWork;
        }

        Ticket ticket() { return ticket; }
        long startedAtNanos() { return startedAtNanos; }
        long workWindow() { return workWindow; }
        boolean requiresNumericProof() { return numericWork != null; }
        PlayerPhysicsProtocol.Packet materializePacket() { return owner.materializePacket(this); }
        StoredDyadic.Work numericWork() {
            if (numericWork == null) {
                throw new IllegalStateException("this inspected frame requires no numeric proof work");
            }
            return numericWork;
        }
        boolean installable() { return owner.installable(this); }
        boolean completed() { synchronized (owner) { return completed; } }
        Optional<DecodeOutcome> outcome() { synchronized (owner) { return Optional.ofNullable(outcome); } }
        Optional<DecodeFailure> failure() { synchronized (owner) { return Optional.ofNullable(failure); } }
        Optional<Throwable> failureCause() { synchronized (owner) { return Optional.ofNullable(failureCause); } }
    }

    private final NetworkManager networkManager;
    private final INetHandler ownerHandler;
    private final EnumPacketDirection receivingDirection;
    private final Limits limits;
    private final LongSupplier clock;
    private final LifetimeToken lifetime = new LifetimeToken();
    private final ArrayDeque<Ticket> pending = new ArrayDeque<>();

    private long windowStartedAtNanos;
    private long windowSequence;
    private long dataArrivals;
    private long dataBodyBytes;
    private long controlArrivals;
    private long controlBodyBytes;
    private int queuedData;
    private int queuedControl;
    private int outstandingData;
    private int outstandingControl;
    private long ticketSequence;
    private long drainSequence;
    private boolean invalidated;
    private InvalidationReason invalidationReason;
    private DrainPermit drainPermit;
    private DecodeLease active;
    private StoredDyadic.Work windowWork = StoredDyadic.Work.boundedIngress(NUMERIC_WORK_LIMIT);

    ConnectionDecodeBudget(NetworkManager networkManager, INetHandler ownerHandler,
                           EnumPacketDirection receivingDirection, Limits limits,
                           LongSupplier clock) {
        this.networkManager = Objects.requireNonNull(networkManager, "networkManager");
        this.ownerHandler = Objects.requireNonNull(ownerHandler, "ownerHandler");
        this.receivingDirection = Objects.requireNonNull(receivingDirection, "receivingDirection");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (networkManager.getNetHandler() != ownerHandler) {
            throw new IllegalArgumentException("NetworkManager is not bound to the supplied INetHandler");
        }
        windowStartedAtNanos = clock.getAsLong();
    }

    NetworkManager networkManager() { return networkManager; }
    EnumPacketDirection receivingDirection() { return receivingDirection; }
    LifetimeToken lifetimeToken() { return lifetime; }
    synchronized InvalidationReason invalidationReason() { return invalidationReason; }

    synchronized ScopeToken openScope(PlayerPhysicalSnapshot.Scope scope) {
        Objects.requireNonNull(scope, "scope");
        if (invalidated) throw new IllegalStateException("connection decode lifetime is invalidated");
        return new ScopeToken(this, scope);
    }

    /**
     * Charges actual UG body bytes once before any parse/proof. Each bucket's
     * count and bytes are independent; failed or denied arrivals stay charged.
     */
    IngressReceipt reserveIngress(INetHandler handler, RawFrame frame) {
        return reserveIngress(networkManager, handler, frame);
    }

    synchronized IngressReceipt reserveIngress(NetworkManager actualManager, INetHandler handler, RawFrame frame) {
        Objects.requireNonNull(actualManager, "actualManager");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(frame, "frame");
        if (actualManager != networkManager || handler != ownerHandler
                || networkManager.getNetHandler() != ownerHandler) {
            return new IngressReceipt(this, frame, TrafficClass.DATA, false,
                    IngressRejection.WRONG_HANDLER, windowSequence);
        }
        if (invalidated) {
            return new IngressReceipt(this, frame, TrafficClass.DATA, false,
                    IngressRejection.INVALIDATED, windowSequence);
        }
        if (!frame.claim(this)) {
            throw new IllegalStateException("a RawFrame identity can be charged only once");
        }

        rollWindowAt(clock.getAsLong());
        TrafficClass trafficClass = classify(frame);
        long arrivalWindow = windowSequence;
        if (trafficClass == TrafficClass.DATA) {
            dataArrivals = saturatingAdd(dataArrivals, 1);
            dataBodyBytes = saturatingAdd(dataBodyBytes, frame.actualBodyBytes);
            IngressRejection rejection = dataArrivals > limits.dataArrivalsPerWindow()
                    ? IngressRejection.ARRIVAL_COUNT
                    : dataBodyBytes > limits.dataBodyBytesPerWindow()
                            ? IngressRejection.ARRIVAL_BYTES : IngressRejection.NONE;
            return new IngressReceipt(this, frame, trafficClass,
                    rejection == IngressRejection.NONE, rejection, arrivalWindow);
        }
        controlArrivals = saturatingAdd(controlArrivals, 1);
        controlBodyBytes = saturatingAdd(controlBodyBytes, frame.actualBodyBytes);
        IngressRejection rejection = controlArrivals > limits.controlArrivalsPerWindow()
                ? IngressRejection.ARRIVAL_COUNT
                : controlBodyBytes > limits.controlBodyBytesPerWindow()
                        ? IngressRejection.ARRIVAL_BYTES : IngressRejection.NONE;
        return new IngressReceipt(this, frame, trafficClass,
                rejection == IngressRejection.NONE, rejection, arrivalWindow);
    }

    /**
     * Runs the real full-frame protocol parser once. CONTROL is privileged
     * only when the complete frame is valid, correctly directed, and one of
     * OFFER/APPLY/NATIVE, whose protocol payload has no placement snapshot.
     */
    FullFrameInspection inspectFullFrame(IngressReceipt receipt) {
        synchronized (this) {
            requireOwned(receipt);
            if (!receipt.admitted) throw new IllegalStateException("quota-denied ingress cannot be inspected");
            if (receipt.inspected) throw new IllegalStateException("an ingress can be fully inspected only once");
            receipt.inspected = true;
        }
        byte[] bytes = receipt.frame.parserBytes();
        PlayerPhysicsProtocol.InspectedFrame inspectedFrame = PlayerPhysicsProtocol.inspectFrame(bytes);
        if (!directionMatches(inspectedFrame.kind(), receivingDirection)) {
            throw new IllegalArgumentException("UG packet kind does not match its actual receive direction");
        }
        if (inspectedFrame.kind().ordinal() != receipt.frame.unsignedByteAt(1)
                || Byte.toUnsignedInt(bytes[0]) != PlayerPhysicsProtocol.VERSION) {
            throw new IllegalArgumentException("decoded UG packet identity differs from its raw header");
        }
        boolean recoveryControl = inspectedFrame.noFreeRecoveryControl();
        if (receipt.trafficClass == TrafficClass.CONTROL && !recoveryControl) {
            throw new IllegalArgumentException("CONTROL header did not decode as a no-Free recovery packet");
        }
        return new FullFrameInspection(this, receipt, inspectedFrame);
    }

    /** Reserves both a queue-resident count and a queued-plus-active outstanding slot. */
    synchronized QueueReservation reserveQueue(FullFrameInspection inspection, ScopeToken scope) {
        Objects.requireNonNull(inspection, "inspection");
        requireOwned(scope);
        requireOwned(inspection);
        if (inspection.consumed) throw new IllegalStateException("a full-frame inspection is one-use");
        inspection.consumed = true;
        if (!inspection.ingress.admitted) {
            return denied(QueueRejection.INGRESS_DENIED);
        }
        if (invalidated) return denied(QueueRejection.INVALIDATED);
        if (scope.retired) return denied(QueueRejection.RETIRED_SCOPE);
        if (!scope.scope.equals(inspection.scope())) {
            throw new IllegalArgumentException("decoded packet scope does not match its live connection scope");
        }
        return enqueueInspectedTicket(inspection, new ScopeBoundTarget(scope));
    }

    /**
     * Queues the first clientbound OFFER against this connection's lifetime,
     * before any world/session scope token exists. The owner still decides
     * whether an accepted OFFER opens a scope after applying its session rules.
     */
    synchronized QueueReservation reserveConnectionOffer(FullFrameInspection inspection) {
        Objects.requireNonNull(inspection, "inspection");
        requireOwned(inspection);
        if (inspection.consumed) throw new IllegalStateException("a full-frame inspection is one-use");
        inspection.consumed = true;
        if (!inspection.ingress.admitted) {
            return denied(QueueRejection.INGRESS_DENIED);
        }
        if (invalidated) return denied(QueueRejection.INVALIDATED);
        if (receivingDirection != EnumPacketDirection.CLIENTBOUND
                || inspection.kind() != PlayerPhysicsProtocol.Kind.OFFER
                || inspection.ingress.trafficClass != TrafficClass.CONTROL
                || !inspection.noFreeControlRecovery()
                || inspection.hasFreePlacement()) {
            throw new IllegalArgumentException(
                    "connection-bound queue admission requires a complete no-Free clientbound OFFER");
        }
        return enqueueInspectedTicket(inspection, new ConnectionBoundTarget(lifetime));
    }

    private QueueReservation enqueueInspectedTicket(FullFrameInspection inspection, TicketTarget target) {
        if (!ticketTargetIsCurrent(target)) {
            return denied(target instanceof ScopeBoundTarget
                    ? QueueRejection.RETIRED_SCOPE : QueueRejection.INVALIDATED);
        }
        if (inspection.ingress.trafficClass == TrafficClass.CONTROL) {
            if (!inspection.noFreeControlRecovery() || inspection.hasFreePlacement()) {
                throw new IllegalArgumentException("raw or proof-bearing frame cannot take a CONTROL queue slot");
            }
        } else if (inspection.noFreeControlRecovery()) {
            throw new IllegalArgumentException("a CONTROL recovery packet was not admitted to its CONTROL ledger");
        }

        boolean control = inspection.ingress.trafficClass == TrafficClass.CONTROL;
        int outstanding = control ? outstandingControl : outstandingData;
        int limit = control ? limits.outstandingControl() : limits.outstandingData();
        if (outstanding >= limit) return denied(QueueRejection.OUTSTANDING_LIMIT);

        long now = clock.getAsLong();
        Ticket ticket = new Ticket(this, ++ticketSequence, inspection.ingress, target, now, inspection);
        pending.addLast(ticket);
        if (control) {
            queuedControl++;
            outstandingControl++;
        } else {
            queuedData++;
            outstandingData++;
        }
        DrainPermit created = null;
        if (drainPermit == null) {
            drainPermit = new DrainPermit(this, ++drainSequence);
            created = drainPermit;
        }
        return new QueueReservation(true, QueueRejection.NONE, Optional.of(ticket),
                Optional.ofNullable(created));
    }

    /** Starts at most one live decode and captures the current window Work. */
    synchronized Optional<DecodeLease> beginNext(DrainPermit permit) {
        requireCurrentDrain(permit);
        if (active != null) return Optional.empty();
        return takeNextLocked();
    }

    /**
     * Starts the next live decode, or atomically closes this drain when no
     * queued ticket remains. Enqueue uses the same owner lock, so an arrival
     * is either taken here or observes a closed permit and creates a new one.
     */
    synchronized Optional<DecodeLease> beginNextOrClose(DrainPermit permit) {
        requireCurrentDrain(permit);
        if (active != null) {
            throw new IllegalStateException("cannot advance a drain while a decode lease is active");
        }
        Optional<DecodeLease> next = takeNextLocked();
        if (next.isEmpty()) {
            permit.finished = true;
            drainPermit = null;
        }
        return next;
    }

    /** Shared FIFO, window-roll, and queued-ticket cancellation path under the owner lock. */
    private Optional<DecodeLease> takeNextLocked() {
        long now = clock.getAsLong();
        rollWindowAt(now);
        while (!pending.isEmpty()) {
            Ticket ticket = pending.removeFirst();
            if (ticket.state == TicketState.CANCELLED) continue;
            if (ticket.state != TicketState.QUEUED) {
                throw new IllegalStateException("pending ticket has an invalid state");
            }
            if (!ticketTargetIsCurrent(ticket.target)
                    || elapsedAtLeast(now, ticket.queuedAtNanos, limits.queuedTtlNanos())) {
                cancelQueued(ticket);
                continue;
            }
            releaseQueueSlot(ticket);
            ticket.state = TicketState.DECODING;
            StoredDyadic.Work work = ticket.inspection.requiresNumericProof() ? windowWork : null;
            active = new DecodeLease(this, ticket, now, windowSequence, work);
            return Optional.of(active);
        }
        return Optional.empty();
    }

    /**
     * Materializes the already-inspected frame exactly once for its active lease.
     * The owner lock protects only the start fence; proof work runs outside it so
     * retirement and expiry can revoke installation without interrupting math.
     */
    PlayerPhysicsProtocol.Packet materializePacket(DecodeLease lease) {
        FullFrameInspection inspection;
        StoredDyadic.Work work;
        synchronized (this) {
            requireActive(lease);
            if (lease.materializationStarted) {
                throw new IllegalStateException("decode lease materialization has already started");
            }
            if (lease.installationExpired || !ticketTargetIsCurrent(lease.ticket.target)) {
                throw new IllegalStateException("decode lease cannot start after its lifetime fence");
            }
            lease.materializationStarted = true;
            inspection = lease.ticket.inspection;
            work = lease.numericWork;
        }

        if (inspection.requiresNumericProof()) {
            if (work == null) {
                throw new IllegalStateException("Numeric decode lease is missing its captured Work");
            }
            return inspection.inspectedFrame.materialize(
                    new PhysicsDecodeBudget(inspection.encodedPacketBytes(), work));
        }
        if (work != null) {
            throw new IllegalStateException("non-numeric lease unexpectedly owns numeric Work");
        }
        return inspection.inspectedFrame.materializeWithoutFreeProof();
    }

    /**
     * Cancels only still-queued work in this scope. Its scheduled drain must
     * still consume the cancelled ticket; active numeric work is left intact.
     */
    synchronized void retireScope(ScopeToken scope) {
        requireOwned(scope);
        if (scope.retired) return;
        scope.retired = true;
        for (Ticket ticket : pending) {
            if (ticket.target instanceof ScopeBoundTarget scoped && scoped.scope() == scope) {
                cancelQueued(ticket);
            }
        }
    }

    /** Makes all future ingress/install impossible; active math exits normally. */
    synchronized boolean invalidate(NetworkManager expectedManager, LifetimeToken expectedLifetime,
                      InvalidationReason reason) {
        if (expectedManager != networkManager || expectedLifetime != lifetime) {
            throw new IllegalArgumentException("connection invalidation identity mismatch");
        }
        Objects.requireNonNull(reason, "reason");
        if (invalidated) return false;
        invalidated = true;
        invalidationReason = reason;
        for (Ticket ticket : pending) cancelQueued(ticket);
        return true;
    }

    /** Timeout revokes installation immediately without releasing active work. */
    synchronized void expire(DecodeLease lease) {
        requireActive(lease);
        lease.installationExpired = true;
    }

    synchronized boolean installable(DecodeLease lease) {
        // This is only the connection/scope lifetime fence, not proof or geometry authority.
        return lease != null && lease.owner == this && active == lease
                && !lease.completed && !lease.installationExpired
                && ticketTargetIsCurrent(lease.ticket.target)
                && lease.failure == null && !lease.installationCommitted;
    }

    /** Called only after the actual decode/proof task has reached its exit. */
    synchronized void finish(DecodeLease lease, DecodeOutcome outcome) {
        requireActive(lease);
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == DecodeOutcome.PROOF_ACCEPTED
                && !lease.installationCommitted && !installable(lease)) {
            throw new IllegalStateException("stale or retired decode lease cannot complete as installable");
        }
        releaseOutstandingSlot(lease.ticket);
        lease.outcome = outcome;
        lease.completed = true;
        lease.ticket.outcome = outcome;
        lease.ticket.state = TicketState.COMPLETED;
        active = null;
    }

    /**
     * Records the original failure object and releases active state at the
     * actual failing-task boundary. The caller remains responsible for
     * propagating or reporting that same throwable.
     */
    synchronized void failed(DecodeLease lease, DecodeFailure failure, Throwable cause) {
        requireActive(lease);
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(cause, "cause");
        if (failure == DecodeFailure.NUMERIC_WORK_EXHAUSTED
                && !(cause instanceof NumericWorkExhaustedException)) {
            throw new IllegalArgumentException("numeric work failure requires its typed exhaustion cause");
        }
        releaseOutstandingSlot(lease.ticket);
        lease.failure = failure;
        lease.failureCause = cause;
        lease.installationExpired = true;
        lease.completed = true;
        lease.ticket.failure = failure;
        lease.ticket.failureCause = cause;
        lease.ticket.state = TicketState.COMPLETED;
        active = null;
    }

    synchronized void markInstallationCommitted(DecodeLease lease) {
        requireActive(lease);
        if (!installable(lease)) throw new IllegalStateException("stale decode lease cannot install");
        lease.installationCommitted = true;
    }

    synchronized boolean rollWindow() {
        return rollWindowAt(clock.getAsLong());
    }

    synchronized Snapshot snapshot() {
        rollWindowAt(clock.getAsLong());
        return new Snapshot(windowStartedAtNanos, windowSequence, dataArrivals, dataBodyBytes,
                controlArrivals, controlBodyBytes, queuedData, queuedControl,
                outstandingData, outstandingControl,
                active != null, drainPermit != null, invalidated);
    }

    private TrafficClass classify(RawFrame frame) {
        if (frame.actualBodyBytes > MAX_FRAME_BYTES || frame.boundedBytes.length < 2) {
            return TrafficClass.DATA;
        }
        if (frame.unsignedByteAt(0) != PlayerPhysicsProtocol.VERSION) return TrafficClass.DATA;
        int kindOrdinal = frame.unsignedByteAt(1);
        PlayerPhysicsProtocol.Kind[] kinds = PlayerPhysicsProtocol.Kind.values();
        if (kindOrdinal >= kinds.length) return TrafficClass.DATA;
        PlayerPhysicsProtocol.Kind kind = kinds[kindOrdinal];
        if (!isProtectedControlKind(kind) || !directionMatches(kind, receivingDirection)) {
            return TrafficClass.DATA;
        }
        if (frame.actualBodyBytes < MIN_CONTROL_LENGTHS.get(kind)) return TrafficClass.DATA;
        return TrafficClass.CONTROL;
    }

    private static EnumMap<PlayerPhysicsProtocol.Kind, Integer> minimumControlLengths() {
        var lengths = new EnumMap<PlayerPhysicsProtocol.Kind, Integer>(PlayerPhysicsProtocol.Kind.class);
        for (PlayerPhysicsProtocol.Kind kind : PlayerPhysicsProtocol.Kind.values()) {
            if (!isProtectedControlKind(kind)) continue;
            PlayerPhysicsProtocol.Packet minimal = new PlayerPhysicsProtocol.Packet((kind == PlayerPhysicsProtocol.Kind.NATIVE || kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION || kind == PlayerPhysicsProtocol.Kind.NATIVE_ACK || kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK || kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE || kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK) ? PlayerPhysicsProtocol.Kind.RECOVER : kind,
                    new PlayerPhysicalSnapshot.Scope(new UUID(0, 0), new UUID(0, 1), 0, 0),
                    0, 0, Optional.empty(), Optional.empty(), Optional.empty());
            if (kind == PlayerPhysicsProtocol.Kind.NATIVE) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeHandoff(1, 0, new Vec3d(0,0,0)));
            else if (kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeMotion(1, 0, 1, new Vec3d(0,0,0)));
            else if (kind == PlayerPhysicsProtocol.Kind.NATIVE_ACK) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(1,0));
            else if (kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeMotionAcknowledgement(1,0,1));
            else if (kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeCorrectionCompleted(1, 0));
            else if (kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK) minimal = PlayerPhysicsProtocol.nativeTransferPacket(kind,
                    minimal.scope(), 0, 0, new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(1, 0, 0));
            lengths.put(kind, PlayerPhysicsProtocol.encode(minimal).length);
        }
        return lengths;
    }

    private static boolean isProtectedControlKind(PlayerPhysicsProtocol.Kind kind) {
        return kind == PlayerPhysicsProtocol.Kind.OFFER
                || kind == PlayerPhysicsProtocol.Kind.APPLY
                || kind == PlayerPhysicsProtocol.Kind.NATIVE
                || kind == PlayerPhysicsProtocol.Kind.RECOVER
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_ACK
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK;
    }

    private static boolean directionMatches(PlayerPhysicsProtocol.Kind kind,
                                            EnumPacketDirection receivingDirection) {
        boolean packetClientbound = kind == PlayerPhysicsProtocol.Kind.OFFER
                || kind == PlayerPhysicsProtocol.Kind.OWNED
                || kind == PlayerPhysicsProtocol.Kind.STATE
                || kind == PlayerPhysicsProtocol.Kind.NATIVE
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_MOTION
                || kind == PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE;
        return packetClientbound == (receivingDirection == EnumPacketDirection.CLIENTBOUND);
    }

    private boolean rollWindowAt(long now) {
        long elapsed = now - windowStartedAtNanos;
        if (elapsed < WINDOW_NANOS) return false;
        windowStartedAtNanos = now;
        windowSequence = saturatingAdd(windowSequence, 1);
        dataArrivals = 0;
        dataBodyBytes = 0;
        controlArrivals = 0;
        controlBodyBytes = 0;
        windowWork = StoredDyadic.Work.boundedIngress(NUMERIC_WORK_LIMIT);
        return true;
    }

    private static boolean elapsedAtLeast(long now, long start, long duration) {
        long elapsed = now - start;
        return elapsed >= duration;
    }

    private static long saturatingAdd(long left, long right) {
        if (right < 0) throw new IllegalArgumentException("saturating increment must be non-negative");
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private void releaseQueueSlot(Ticket ticket) {
        if (ticket.ingress.trafficClass == TrafficClass.CONTROL) {
            if (queuedControl <= 0) throw new IllegalStateException("CONTROL queue accounting underflow");
            queuedControl--;
        } else {
            if (queuedData <= 0) throw new IllegalStateException("DATA queue accounting underflow");
            queuedData--;
        }
    }

    private boolean cancelQueued(Ticket ticket) {
        if (ticket.state != TicketState.QUEUED) return false;
        releaseQueueSlot(ticket);
        releaseOutstandingSlot(ticket);
        ticket.state = TicketState.CANCELLED;
        return true;
    }

    private void releaseOutstandingSlot(Ticket ticket) {
        if (ticket.ingress.trafficClass == TrafficClass.CONTROL) {
            if (outstandingControl <= 0) throw new IllegalStateException("CONTROL outstanding accounting underflow");
            outstandingControl--;
        } else {
            if (outstandingData <= 0) throw new IllegalStateException("DATA outstanding accounting underflow");
            outstandingData--;
        }
    }

    private QueueReservation denied(QueueRejection rejection) {
        return new QueueReservation(false, rejection, Optional.empty(), Optional.empty());
    }

    private void requireOwned(IngressReceipt receipt) {
        if (receipt == null || receipt.owner != this) {
            throw new IllegalArgumentException("ingress receipt belongs to another connection");
        }
    }

    private void requireOwned(ScopeToken scope) {
        if (scope == null || scope.owner != this) {
            throw new IllegalArgumentException("scope token belongs to another connection");
        }
    }

    private void requireOwned(FullFrameInspection inspection) {
        if (inspection == null || inspection.owner != this || inspection.ingress.owner != this) {
            throw new IllegalArgumentException("full-frame inspection belongs to another connection");
        }
    }

    /** The one current-target predicate shared by queue start, materialization, and installation. */
    private boolean ticketTargetIsCurrent(TicketTarget target) {
        if (invalidated) return false;
        if (target instanceof ScopeBoundTarget scoped) {
            ScopeToken scope = scoped.scope();
            return scope.owner == this && !scope.retired;
        }
        if (target instanceof ConnectionBoundTarget connection) {
            return connection.lifetime() == lifetime;
        }
        throw new IllegalStateException("ticket has an unknown lifetime target");
    }

    private void requireCurrentDrain(DrainPermit permit) {
        if (permit == null || permit.owner != this || permit != drainPermit || permit.finished) {
            throw new IllegalArgumentException("drain permit is not current for this connection");
        }
    }

    private void requireActive(DecodeLease lease) {
        if (lease == null || lease.owner != this || active != lease || lease.completed) {
            throw new IllegalStateException("decode lease is not the active connection task");
        }
    }

    synchronized void finishDrain(DrainPermit permit) {
        requireCurrentDrain(permit);
        if (!pending.isEmpty()) {
            throw new IllegalStateException("scheduled drain cannot finish while tickets remain");
        }
        permit.finished = true;
        drainPermit = null;
    }

    /**
     * Cancels the queue when the scheduler fails before its drain can run.
     * The caller must propagate or report the returned original throwable.
     */
    synchronized Throwable drainFailed(DrainPermit permit, Throwable cause) {
        requireCurrentDrain(permit);
        Objects.requireNonNull(cause, "cause");
        for (Ticket ticket : pending) cancelQueued(ticket);
        pending.clear();
        permit.finished = true;
        drainPermit = null;
        if (active != null) active.installationExpired = true;
        return cause;
    }

    /** Fails only the lease captured by this scheduled drain, then closes that drain and its queue. */
    synchronized Throwable ownerDrainFailed(DrainPermit permit, DecodeLease expectedActive,
            DecodeFailure failure, Throwable cause) {
        requireCurrentDrain(permit);
        Objects.requireNonNull(cause, "cause");
        if (expectedActive == null) {
            if (active != null) throw new IllegalStateException("failed drain omitted its active decode lease");
        } else {
            if (expectedActive.owner != this) throw new IllegalArgumentException("decode lease belongs to another connection");
            if (active == expectedActive) {
                failed(expectedActive, Objects.requireNonNull(failure, "failure"), cause);
            } else if (active != null || !expectedActive.completed) {
                throw new IllegalStateException("failed drain no longer owns the expected decode lease");
            }
        }
        for (Ticket ticket : pending) cancelQueued(ticket);
        pending.clear();
        permit.finished = true;
        drainPermit = null;
        if (active != null) throw new IllegalStateException("failed drain left an active decode lease");
        return cause;
    }
}
