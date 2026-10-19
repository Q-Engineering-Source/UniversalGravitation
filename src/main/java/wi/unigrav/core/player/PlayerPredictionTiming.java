package wi.unigrav.core.player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Contiguous consumed-input slots; world physics may advance without consuming another input. */
public final class PlayerPredictionTiming {
    private PlayerPredictionTiming() {}
    public static long resolvedClientTick(PlayerPhysicsProtocol.PredictionBoundary boundary) {
        Objects.requireNonNull(boundary, "boundary");
        return Math.addExact(boundary.firstClientTick(),
                Math.subtractExact(boundary.serverSlot(), boundary.anchorServerSlot()) - 1);
    }
    public static void validateInput(PlayerPhysicsProtocol.PredictionInput input, long epoch, long firstClientTick) {
        if (input.epoch() != epoch || input.firstClientTick() != firstClientTick)
            throw new IllegalArgumentException("Prediction epoch/start mismatch");
    }
    /** Server first freeze consumes the exact first packet, even if newer levels arrived. */
    public static final class FirstInputLatch {
        private PlayerPhysicsProtocol.Packet first, latest;
        private boolean consumed, retired;
        public void accept(PlayerPhysicsProtocol.Packet packet) {
            if (retired) throw new IllegalStateException("Prediction lifetime retired");
            if (packet.kind() != PlayerPhysicsProtocol.Kind.INPUT) throw new IllegalArgumentException("Not INPUT");
            var input = packet.predictionInput().orElseThrow();
            if (first == null) {
                if (input.clientTick() != input.firstClientTick())
                    throw new IllegalArgumentException("First prediction input must identify first slot");
                first = latest = packet;
                return;
            }
            validateInput(input, first.predictionInput().orElseThrow().epoch(), first.predictionInput().orElseThrow().firstClientTick());
            if (!packet.scope().equals(first.scope()) || packet.offerSequence() != first.offerSequence())
                throw new IllegalArgumentException("Prediction scope/proposal mismatch");
            if (packet.sequence() < latest.sequence()) throw new IllegalArgumentException("Stale input sequence");
            if (packet.sequence() == latest.sequence()) {
                if (!packet.equals(latest)) throw new IllegalArgumentException("Input sequence reused with different payload");
                return;
            }
            if (input.clientTick() <= latest.predictionInput().orElseThrow().clientTick())
                throw new IllegalArgumentException("Input slots must increase");
            latest = packet;
        }
        public Optional<PlayerPhysicsProtocol.Packet> freeze() {
            if (retired) throw new IllegalStateException("Prediction lifetime retired");
            return Optional.ofNullable(consumed ? latest : first);
        }
        public void firstSlotResolved() {
            if (first == null || retired) throw new IllegalStateException("No active first input");
            consumed = true;
        }
        public void retire() { retired = true; first = latest = null; }
    }
    /** Terminal receipts may be retransmitted; a resolved old edge never becomes replay input. */
    public static final class JumpTerminals {
        private final long epoch;
        private final int capacity;
        private final java.util.LinkedHashMap<Long, PlayerPhysicsProtocol.JumpDecision> decisions = new java.util.LinkedHashMap<>();
        private boolean retired;
        public JumpTerminals(long epoch, int capacity) {
            if (epoch <= 0 || capacity <= 0) throw new IllegalArgumentException("Invalid jump receipt bounds");
            this.epoch = epoch; this.capacity = capacity;
        }
        public boolean record(long epoch, PlayerPhysicsProtocol.JumpDecision decision) {
            checkEpoch(epoch);
            Objects.requireNonNull(decision, "decision");
            if (decision.outcome() == PlayerPhysicsProtocol.JumpOutcome.NONE) return false;
            var existing = decisions.get(decision.edgeId());
            if (existing != null) {
                if (!existing.equals(decision)) throw new IllegalArgumentException("Conflicting jump terminal receipt");
                return false;
            }
            if (decisions.size() == capacity) throw new IllegalStateException("Jump terminal history capacity exceeded");
            decisions.put(decision.edgeId(), decision);
            return true;
        }
        public boolean mayReplay(PlayerPhysicsProtocol.PredictionInput input, long resolvedClientTick) {
            checkEpoch(input.epoch());
            return input.jumpEdgeId() > 0
                    && input.clientTick() > resolvedClientTick && !decisions.containsKey(input.jumpEdgeId());
        }
        private void checkEpoch(long value) {
            if (retired) throw new IllegalStateException("Prediction lifetime retired");
            if (value != epoch) throw new IllegalArgumentException("Jump epoch mismatch");
        }
        public void retire() { retired = true; decisions.clear(); }
    }

    public record Slot<T>(long clientTick, T state) {
        public Slot { if (clientTick < 0) throw new IllegalArgumentException("Negative slot"); Objects.requireNonNull(state, "state"); }
    }
    public sealed interface Replay<T> permits Ready, NeedsRebase {}
    public record Ready<T>(List<Slot<T>> slots) implements Replay<T> {
        public Ready { slots = List.copyOf(slots); }
    }
    public record NeedsRebase<T>(String reason) implements Replay<T> {}
    public static final class History<T> {
        private final long epoch, firstClientTick;
        private final int capacity, maxReplaySteps;
        private final ArrayDeque<Slot<T>> slots = new ArrayDeque<>();
        private long head;
        private boolean retired;
        private PlayerPhysicsProtocol.PredictionBoundary lastBoundary;
        public History(long epoch, long firstClientTick, int capacity, int maxReplaySteps) {
            if (epoch <= 0 || firstClientTick < 0 || capacity <= 0 || maxReplaySteps < 0 || maxReplaySteps > capacity)
                throw new IllegalArgumentException("Invalid prediction history bounds");
            this.epoch = epoch; this.firstClientTick = firstClientTick; this.capacity = capacity;
            this.maxReplaySteps = maxReplaySteps; head = firstClientTick - 1;
        }
        public void append(long clientTick, T state) {
            if (retired) throw new IllegalStateException("Prediction lifetime retired");
            if (head == Long.MAX_VALUE || clientTick != head + 1)
                throw new IllegalArgumentException("Prediction slots must be contiguous UG slots");
            slots.addLast(new Slot<>(clientTick, state)); head = clientTick;
            if (slots.size() > capacity) slots.removeFirst();
        }
        /** The server explicitly proves zero completed slots; no mapping is invented or advanced here. */
        public Replay<T> beforeFirstBoundary() {
            if (retired) return new NeedsRebase<>("Prediction lifetime retired");
            if (lastBoundary != null) return new NeedsRebase<>("Prediction mapping already established");
            long count = head - firstClientTick + 1;
            if (count > maxReplaySteps) return new NeedsRebase<>("Replay work limit exceeded");
            if (count > 0 && (slots.isEmpty() || slots.peekFirst().clientTick() != firstClientTick
                    || slots.size() != count)) return new NeedsRebase<>("Prediction history missing");
            return new Ready<>(new ArrayList<>(slots));
        }
        public Replay<T> after(PlayerPhysicsProtocol.PredictionBoundary boundary) {
            Objects.requireNonNull(boundary, "boundary");
            if (retired) return new NeedsRebase<>("Prediction lifetime retired");
            if (boundary.epoch() != epoch || boundary.firstClientTick() != firstClientTick)
                return new NeedsRebase<>("Prediction epoch/start mismatch");
            if (lastBoundary != null) {
                if (boundary.mappingGeneration() != lastBoundary.mappingGeneration()
                        || boundary.anchorServerSlot() != lastBoundary.anchorServerSlot()
                        || boundary.firstInputSequence() != lastBoundary.firstInputSequence()
                        || boundary.preSnapshotRevision() != lastBoundary.preSnapshotRevision())
                    return new NeedsRebase<>("Prediction mapping identity changed");
                if (boundary.serverSlot() < lastBoundary.serverSlot()) return new NeedsRebase<>("Stale boundary");
                if (boundary.heldInputSequence() < lastBoundary.heldInputSequence()
                        || boundary.serverSlot() == lastBoundary.serverSlot()
                        && boundary.heldInputSequence() != lastBoundary.heldInputSequence())
                    throw new IllegalArgumentException("Conflicting consumed input acknowledgement");
                var previousJump = lastBoundary.jumpDecision();
                var jump = boundary.jumpDecision();
                if (jump.edgeId() < previousJump.edgeId()
                        || jump.edgeId() == previousJump.edgeId() && !jump.equals(previousJump))
                    throw new IllegalArgumentException("Conflicting terminal jump acknowledgement");
                // A newer physical STATE can retain this input acknowledgement while cooldown,
                // step outcome or a jump terminal changes. Packet sequence/revision owns freshness.
            }
            long resolved = resolvedClientTick(boundary);
            if (resolved > head) return new NeedsRebase<>("Server boundary exceeds local head");
            long count = head - resolved;
            if (count > maxReplaySteps) return new NeedsRebase<>("Replay work limit exceeded");
            if (count > 0 && (slots.isEmpty() || slots.peekFirst().clientTick() > resolved + 1))
                return new NeedsRebase<>("Prediction history missing");
            var replay = new ArrayList<Slot<T>>();
            for (var slot : slots) if (slot.clientTick() > resolved) replay.add(slot);
            lastBoundary = boundary;
            while (!slots.isEmpty() && slots.peekFirst().clientTick() <= resolved) slots.removeFirst();
            return new Ready<>(replay);
        }
        public void retire() { retired = true; slots.clear(); }
    }
}
