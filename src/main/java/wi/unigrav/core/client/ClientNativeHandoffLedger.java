package wi.unigrav.core.client;

/** One accepted scope's bounded opportunity to establish native finalized samples. */
final class ClientNativeHandoffLedger {
    private int age;
    private int submissions;
    private long positionedTick = Long.MIN_VALUE;
    private boolean terminal;
    private boolean exhaustionReported;

    boolean advance(boolean paused, boolean eligible, long tick) {
        if (terminal || paused) return false;
        if (++age >= 80 || submissions >= 12) { terminal = true; return false; }
        return eligible && positionedTick != tick;
    }
    void positioned(long tick) { positionedTick = tick; }
    void submitted() { if (++submissions >= 12) terminal = true; }
    void stop() { terminal = true; }
    boolean reportExhaustion() {
        if (!terminal || exhaustionReported || (age < 80 && submissions < 12)) return false;
        exhaustionReported = true;
        return true;
    }
    int age() { return age; }
    int submissions() { return submissions; }
}

