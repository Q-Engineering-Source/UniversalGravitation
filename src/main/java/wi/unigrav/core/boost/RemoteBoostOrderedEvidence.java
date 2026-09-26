package wi.unigrav.core.boost;

import java.util.ArrayDeque;

/**
 * Bounded ordered FRAME/companion evidence. A stale, expired, or evicted FRAME becomes exactly
 * one DROP entry, so the next Position cannot consume a later FRAME out of order.
 */
final class RemoteBoostOrderedEvidence {
    record Frame(long sequence, long serverTick, int gear, boolean settled) {
    }

    record Entry(Frame frame, boolean drop) {
        static Entry frame(Frame frame) { return new Entry(frame, false); }
        static Entry dropped() { return new Entry(null, true); }
    }

    private final int capacity;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long droppedPrefix;

    RemoteBoostOrderedEvidence(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Non-positive evidence capacity");
        this.capacity = capacity;
    }

    /** @return true when the new entry fit; false means an older companion became a DROP prefix. */
    boolean append(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("Null evidence");
        if (queue.size() < capacity) {
            queue.addLast(entry);
            return true;
        }
        queue.removeFirst();
        if (droppedPrefix < Long.MAX_VALUE) droppedPrefix++;
        queue.addLast(entry);
        return false;
    }

    /** Consumes exactly one ordered companion; caller turns expired FRAME into one DROP. */
    Entry consume() {
        if (droppedPrefix > 0L) {
            droppedPrefix--;
            return Entry.dropped();
        }
        return queue.pollFirst();
    }

    static boolean expired(Entry entry, long serverTick, long maxAgeTicks) {
        if (serverTick < 0 || maxAgeTicks < 0) throw new IllegalArgumentException("Invalid time");
        return entry != null && !entry.drop()
                && serverTick - entry.frame().serverTick() > maxAgeTicks;
    }

    void clear() {
        queue.clear();
        droppedPrefix = 0L;
    }
}
