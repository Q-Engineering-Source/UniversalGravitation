package wi.unigrav.core.boost;

/** Bounds repeated activation diagnostics without changing permission decisions. */
final class RemoteBoostIncidentLogGate {
    private final long intervalTicks;
    private boolean emitted;
    private long lastEmissionTick;
    private long suppressed;

    RemoteBoostIncidentLogGate(long intervalTicks) {
        if (intervalTicks <= 0) throw new IllegalArgumentException("Non-positive log interval");
        this.intervalTicks = intervalTicks;
    }

    boolean tryAcquire(long tick) {
        if (tick < 0 || emitted && tick < lastEmissionTick) {
            throw new IllegalArgumentException("Non-monotonic diagnostic tick");
        }
        if (!emitted || tick - lastEmissionTick >= intervalTicks) {
            emitted = true;
            lastEmissionTick = tick;
            return true;
        }
        if (suppressed < Long.MAX_VALUE) suppressed++;
        return false;
    }

    long takeSuppressed() {
        long count = suppressed;
        suppressed = 0;
        return count;
    }
}
