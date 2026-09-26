package wi.unigrav.core.boost;

import wi.unigrav.api.math.Vec3;

/** Pure close-window reducer used by the live CLOSING session. */
final class RemoteBoostCloseWindow {
    private final int requiredTicks;
    private final double thresholdSquared;
    private Vec3 anchor;
    private long lastTick = Long.MIN_VALUE;
    private int consecutive;

    RemoteBoostCloseWindow(int requiredTicks, double thresholdSquared) {
        if (requiredTicks <= 0 || !Double.isFinite(thresholdSquared) || thresholdSquared < 0) {
            throw new IllegalArgumentException("Invalid close window");
        }
        this.requiredTicks = requiredTicks;
        this.thresholdSquared = thresholdSquared;
    }

    boolean observe(long tick, Vec3 position, boolean settled) {
        if (!settled || position == null || tick != lastTick + 1L) {
            reset();
            if (!settled || position == null) return false;
        }
        if (anchor == null || position.sub(anchor).normSquared() > thresholdSquared) {
            anchor = position;
            consecutive = 1;
        } else {
            consecutive++;
        }
        lastTick = tick;
        return consecutive >= requiredTicks;
    }

    void reset() {
        anchor = null;
        lastTick = Long.MIN_VALUE;
        consecutive = 0;
    }
}
