package wi.unigrav.core.player;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Acknowledges consumed input slots, independently of world physics ticks. */
final class OwnedPredictionTimeline {
    // Retained wire payload capacity, not a limit on movement calculations.
    private static final int MAX_PENDING_INPUTS = 512;
    private record Pending(PlayerPhysicsProtocol.Packet packet, long receivedWorldTick, long jumpOriginTick) { }
    record Context(PlayerPhysicalSnapshot.Scope scope, long proposal, Object connection, Object scopeHandle,
                   Object owner, Object world, long correctionRevision) {
        Context {
            Objects.requireNonNull(scope); Objects.requireNonNull(connection); Objects.requireNonNull(owner); Objects.requireNonNull(world);
            if (proposal < 0 || correctionRevision < 0) throw new IllegalArgumentException("Invalid prediction owner identity");
        }
        boolean same(Context other) {
            return other != null && scope.equals(other.scope) && proposal == other.proposal && connection == other.connection
                    && scopeHandle == other.scopeHandle && owner == other.owner && world == other.world
                    && correctionRevision == other.correctionRevision;
        }
    }
    record Frozen(PlayerPhysicsProtocol.Packet packet, PlayerPhysicsProtocol.Controls controls,
                  long preSnapshotRevision, long startSlot, long jumpOriginClientTick,
                  boolean consumesInput) { }
    private Context context, bootstrapContext;
    private PlayerPhysicsProtocol.Packet first, latest;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private Pending held;
    private long retiredEpoch, slot, anchorSlot, preSnapshotRevision;
    private long edgeId, edgeOriginTick;
    private boolean anchored;
    private Frozen inFlight;
    private PlayerPhysicsProtocol.PredictionBoundary boundary;
    private long terminalEdge;
    private final java.util.ArrayDeque<PlayerPhysicsProtocol.JumpDecision> unpublishedJumps = new java.util.ArrayDeque<>();
    private PlayerPhysicsProtocol.JumpDecision publishedJump = PlayerPhysicsProtocol.JumpDecision.none();

    private PlayerPhysicsProtocol.PredictionBootstrap bootstrap;
    private boolean bootstrapRetired;
    private PlayerPhysicsProtocol.PredictionRequest suspension;
    private boolean rearmRequested;
    boolean request(Context current, PlayerPhysicsProtocol.PredictionRequest request) {
        if (bootstrap == null || bootstrapContext == null || !bootstrapContext.same(current)
                || !bootstrap.equals(request.bootstrap()) || bootstrap.correctionRevision() != current.correctionRevision()) return false;
        if (request.action() == PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND) {
            if (suspension != null) return suspension.origin()==request.origin() && suspension.requestId() == request.requestId()
                    && suspension.refusal().equals(request.refusal());
            if(request.origin()!=PlayerPhysicsProtocol.PredictionSuspensionOrigin.CLIENT_REFUSAL) return false;
            retire(); context=current; suspension=request; rearmRequested=false; return true;
        }
        if (suspension == null || suspension.origin()!=request.origin() || suspension.requestId()!=request.requestId()
                || !suspension.refusal().equals(request.refusal())) return false;
        rearmRequested=true; return true;
    }
    boolean suspended() { return suspension != null; }
    boolean anchored() { return anchored; }
    void suspendUnmapped(Context current,long actualInputSequence) {
        if(first == null || context == null || !context.same(current) || actualInputSequence<=0)
            throw new IllegalStateException("No current admitted input mapping to suspend");
        var request=new PlayerPhysicsProtocol.PredictionRequest(actualInputSequence,bootstrap,PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,
                Optional.empty(),PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS);
        retire();context=current;suspension=request;
    }
    private long mobilitySuspensionId;
    /** A mobility pause ends only the prediction mapping; physical custody and its bootstrap remain exact. */
    boolean suspendMobility(Context current) {
        return suspendServer(current, PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_MOBILITY);
    }
    boolean suspendReference(Context current) {
        return suspendServer(current, PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_REFERENCE);
    }
    private boolean suspendServer(Context current, PlayerPhysicsProtocol.PredictionSuspensionOrigin origin) {
        if (suspension != null) return context != null && context.same(current);
        if (bootstrap == null || bootstrapRetired || bootstrapContext == null || !bootstrapContext.same(current)
                || bootstrap.correctionRevision() != current.correctionRevision()) return false;
        long id = Math.incrementExact(mobilitySuspensionId);
        var paused = new PlayerPhysicsProtocol.PredictionRequest(id, bootstrap,
                PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND, Optional.empty(),
                origin);
        retire(); context = current; suspension = paused; mobilitySuspensionId = id;
        return true;
    }
    boolean rearmRequested() { return suspended() && rearmRequested; }

    void begin(Context current, PlayerPhysicsProtocol.PredictionBootstrap identity) {
        Objects.requireNonNull(current); Objects.requireNonNull(identity);
        if (identity.correctionRevision() != current.correctionRevision()
                || bootstrap != null && identity.generation() <= bootstrap.generation())
            throw new IllegalArgumentException("Prediction bootstrap did not advance its real correction lifetime");
        retire();
        context = current; bootstrapContext=current; bootstrap = identity; bootstrapRetired = false; suspension=null; rearmRequested=false;
    }
    boolean awaitingMetadata() { return bootstrap != null && !bootstrapRetired && first == null; }
    PlayerPhysicsProtocol.PredictionPhase phase(Context current, long revision, int jumpCooldown) {
        if (bootstrap == null) return new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                PlayerPhysicsProtocol.PredictionUnavailableReason.NOT_BEGUN);
        if (suspension != null && context.same(current)) return new PlayerPhysicsProtocol.PredictionSuspended(revision,bootstrap,suspension.requestId(),suspension.origin());
        if (bootstrapRetired) return new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                PlayerPhysicsProtocol.PredictionUnavailableReason.RETIRED);
        if (!context.same(current)) {
            retire();
            return new PlayerPhysicsProtocol.PredictionUnavailable(revision,
                    PlayerPhysicsProtocol.PredictionUnavailableReason.CONTEXT_CHANGED);
        }
        return boundary == null ? new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(bootstrap, revision, jumpCooldown)
                : new PlayerPhysicsProtocol.Anchored(bootstrap, revision);
    }
    Optional<PlayerPhysicsProtocol.PredictionBootstrap> bootstrap() { return Optional.ofNullable(bootstrap); }
    boolean accept(Context current, PlayerPhysicsProtocol.Packet packet, long receivedWorldTick) {
        Objects.requireNonNull(current); Objects.requireNonNull(packet);
        var metadata = packet.predictionInput();
        if (packet.kind() != PlayerPhysicsProtocol.Kind.INPUT || metadata.isEmpty()) return false;
        var sample = metadata.orElseThrow();
        if (bootstrap == null || bootstrapRetired || !sample.bootstrap().filter(bootstrap::equals).isPresent()) {
            return false;
        }
        if (!current.scope().equals(packet.scope()) || current.proposal() != packet.offerSequence()) return false;
        if (context == null || !context.same(current)) { retire(); return false; }
        if (pending.size() == MAX_PENDING_INPUTS) return false;
        if (first == null) {
            if (sample.epoch() <= retiredEpoch || sample.clientTick() != sample.firstClientTick()) return false;
            context = current; first = latest = packet;
        } else {
            var start = first.predictionInput().orElseThrow();
            var previous = latest.predictionInput().orElseThrow();
            if (sample.epoch() != start.epoch() || sample.firstClientTick() != start.firstClientTick()
                    || packet.sequence() <= latest.sequence() || previous.clientTick() == Long.MAX_VALUE
                    || sample.clientTick() != previous.clientTick() + 1
                    || sample.jumpEdgeId() != 0 && sample.jumpEdgeId() < edgeId) return false;
            latest = packet;
        }
        if (sample.jumpEdgeId() > edgeId) { edgeId = sample.jumpEdgeId(); edgeOriginTick = sample.clientTick(); }
        pending.addLast(new Pending(packet, receivedWorldTick,
                sample.jumpEdgeId() == edgeId ? edgeOriginTick : sample.clientTick()));
        return true;
    }
    Optional<Frozen> freeze(Context current, long revision, long worldTick, long timeout,
                            PlayerPhysicsProtocol.Controls neutral) {
        if (first == null) return Optional.empty();
        if (!context.same(current)) { retire(); return Optional.empty(); }
        if (inFlight != null) throw new IllegalStateException("Prediction slot already frozen");
        if (revision < 0 || timeout < 0) throw new IllegalArgumentException("Invalid slot freeze");
        boolean consumesInput = !pending.isEmpty();
        var chosen = consumesInput ? pending.getFirst() : Objects.requireNonNull(held, "held input");
        var controls = worldTick - chosen.receivedWorldTick() <= timeout
                ? chosen.packet().controls().orElseThrow() : neutral;
        // Empty input queues do not stop gravity/physics and do not acknowledge unsent future inputs.
        inFlight = new Frozen(chosen.packet(), controls, revision, slot, chosen.jumpOriginTick(), consumesInput);
        return Optional.of(inFlight);
    }
    Optional<PlayerPhysicsProtocol.PredictionBoundary> complete(Context current, Frozen frozen,
            PlayerPhysicsProtocol.StepOutcome outcome, int jumpCooldown) {
        return complete(current, frozen, outcome, jumpCooldown, PlayerPhysicsProtocol.JumpOutcome.NONE);
    }
    Optional<PlayerPhysicsProtocol.PredictionBoundary> complete(Context current, Frozen frozen,
            PlayerPhysicsProtocol.StepOutcome outcome, int jumpCooldown, PlayerPhysicsProtocol.JumpOutcome jump) {
        if (inFlight != frozen || first == null) throw new IllegalStateException("Prediction slot is not the frozen slot");
        if (!context.same(current)) { retire(); return Optional.empty(); }
        if (frozen.startSlot() != slot) throw new IllegalStateException("Prediction consumption changed while frozen");
        long next = frozen.consumesInput() ? Math.incrementExact(slot) : slot;
        if (frozen.consumesInput()
                && (pending.isEmpty() || pending.getFirst().packet() != frozen.packet()))
            throw new IllegalStateException("Frozen input lost its queue position");
        if (!anchored) { anchorSlot = slot; preSnapshotRevision = frozen.preSnapshotRevision(); }
        var initial = first.predictionInput().orElseThrow();
        var input = frozen.packet().predictionInput().orElseThrow();
        // A timeout neutral slot processes no wire jump. Its receipt expires the original edge.
        var decision = frozen.packet().controls().orElseThrow().jump() && !frozen.controls().jump()
                ? PlayerPhysicsProtocol.JumpOutcome.EXPIRED : jump;
        if (input.jumpEdgeId() > terminalEdge && decision != PlayerPhysicsProtocol.JumpOutcome.NONE) {
            if (unpublishedJumps.size() == PlayerPhysicsStep.cardinalMovementLimits().maxQueries()) {
                retire(); // Bound identity backlog to the same replay/step work policy; a new epoch is required.
                return Optional.empty();
            }
            unpublishedJumps.addLast(new PlayerPhysicsProtocol.JumpDecision(input.jumpEdgeId(),
                    frozen.jumpOriginClientTick(), decision));
            terminalEdge = input.jumpEdgeId();
        }
        long resolved = Math.addExact(Math.subtractExact(initial.firstClientTick(), 1),
                Math.subtractExact(next, anchorSlot));
        if (!unpublishedJumps.isEmpty() && unpublishedJumps.getFirst().clientTick() <= resolved)
            publishedJump = unpublishedJumps.removeFirst();
        var completed = new PlayerPhysicsProtocol.PredictionBoundary(initial.epoch(), 1, anchorSlot, next,
                initial.firstClientTick(), first.sequence(), preSnapshotRevision, frozen.packet().sequence(),
                jumpCooldown, publishedJump, outcome);
        if (frozen.consumesInput()) held = pending.removeFirst();
        slot = next; anchored = true; boundary = completed; inFlight = null;
        return Optional.of(completed);
    }
    Optional<PlayerPhysicsProtocol.PredictionBoundary> boundary(Context current) {
        if (context != null && !context.same(current)) retire();
        return Optional.ofNullable(boundary);
    }
    boolean hasInFlight() { return inFlight != null; }
    boolean active() { return first != null; }
    record Diagnostics(long completedInputSlots, long firstClientTick, long receivedClientTick,
                       long receivedSequence, long consumedSequence, int queuedInputs,
                       long frozenSequence, boolean frozenConsumesInput) { }
    Diagnostics diagnostics() {
        return new Diagnostics(slot, first == null ? -1 : first.predictionInput().orElseThrow().firstClientTick(),
                latest == null ? -1 : latest.predictionInput().orElseThrow().clientTick(),
                latest == null ? -1 : latest.sequence(), held == null ? -1 : held.packet().sequence(), pending.size(),
                inFlight == null ? -1 : inFlight.packet().sequence(), inFlight != null && inFlight.consumesInput());
    }
    void retire() {
        bootstrapRetired = true; suspension=null; rearmRequested=false;
        if (first != null) retiredEpoch = Math.max(retiredEpoch, first.predictionInput().orElseThrow().epoch());
        context = null; first = latest = null; boundary = null; inFlight = null;
        pending.clear(); held = null;
        slot = 0; anchored = false; edgeId = edgeOriginTick = terminalEdge = 0;
        unpublishedJumps.clear(); publishedJump = PlayerPhysicsProtocol.JumpDecision.none();
    }
}
