package wi.unigrav.core.client;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import wi.unigrav.core.client.PlayerPhysicsClientSession.StateDisposition;

/** Client-thread, constant-memory observations; position samples are never written back to the actor. */
public final class ClientMotionTrace {
    public record Report(Map<StateDisposition, Long> states, long maximumRevisionStep,
                         double maximumAcceptedStep, long frames, long zeroSpanFrames,
                         double maximumHistorySpan, double maximumRenderStep,
                         long movingZeroSpanFrames, double maximumMotionMps) {
        public Report { states = Collections.unmodifiableMap(new EnumMap<>(states)); }
        @Override public String toString() {
            var counts = new StringBuilder();
            states.forEach((kind, count) -> { if (count != 0) counts.append(kind).append('=').append(count).append(' '); });
            return String.format(Locale.ROOT,
                    "state[%s] revisionStepMax=%d acceptedStepMax=%.5fm renderHistory[frames=%d,zeroSpan=%d,spanMax=%.5fm,stepMax=%.5fm,movingZeroSpan=%d,motionMax=%.3fmps]",
                    counts.toString().trim(), maximumRevisionStep, maximumAcceptedStep, frames, zeroSpanFrames,
                    maximumHistorySpan, maximumRenderStep, movingZeroSpanFrames, maximumMotionMps);
        }
    }
    private final long[] states = new long[StateDisposition.values().length];
    private boolean applied, rendered;
    private long revision, maximumRevisionStep, frames, zeroSpanFrames, movingZeroSpanFrames;
    private double x, y, z, renderX, renderY, renderZ;
    private double maximumAcceptedStep, maximumHistorySpan, maximumRenderStep, maximumMotionMps;

    public void state(StateDisposition disposition) { states[disposition.ordinal()]++; }

    public void applied(long nextRevision, double nextX, double nextY, double nextZ) {
        if (applied) {
            maximumRevisionStep = Math.max(maximumRevisionStep, nextRevision - revision);
            maximumAcceptedStep = Math.max(maximumAcceptedStep, distance(nextX - x, nextY - y, nextZ - z));
        }
        applied = true;
        revision = nextRevision;
        x = nextX; y = nextY; z = nextZ;
    }

    /** Vanilla position-history interpolation, not a claim to sample UIE's final camera transform. */
    public void render(double previousX, double previousY, double previousZ,
                       double currentX, double currentY, double currentZ, float partial, double motionMps) {
        double span = distance(currentX - previousX, currentY - previousY, currentZ - previousZ);
        frames++;
        if (span == 0) zeroSpanFrames++;
        if (span == 0 && motionMps > .01) movingZeroSpanFrames++;
        maximumMotionMps = Math.max(maximumMotionMps, motionMps);
        maximumHistorySpan = Math.max(maximumHistorySpan, span);
        double nextX = previousX + (currentX - previousX) * partial;
        double nextY = previousY + (currentY - previousY) * partial;
        double nextZ = previousZ + (currentZ - previousZ) * partial;
        if (rendered) maximumRenderStep = Math.max(maximumRenderStep,
                distance(nextX - renderX, nextY - renderY, nextZ - renderZ));
        rendered = true;
        renderX = nextX; renderY = nextY; renderZ = nextZ;
    }

    public Report drain() {
        var counts = new EnumMap<StateDisposition, Long>(StateDisposition.class);
        for (var disposition : StateDisposition.values()) counts.put(disposition, states[disposition.ordinal()]);
        var report = new Report(counts, maximumRevisionStep, maximumAcceptedStep, frames, zeroSpanFrames,
                maximumHistorySpan, maximumRenderStep, movingZeroSpanFrames, maximumMotionMps);
        Arrays.fill(states, 0);
        maximumRevisionStep = frames = zeroSpanFrames = movingZeroSpanFrames = 0;
        maximumAcceptedStep = maximumHistorySpan = maximumRenderStep = maximumMotionMps = 0;
        return report;
    }
    public void reset() {
        Arrays.fill(states, 0);
        maximumRevisionStep = frames = zeroSpanFrames = movingZeroSpanFrames = 0;
        maximumAcceptedStep = maximumHistorySpan = maximumRenderStep = maximumMotionMps = 0;
        applied = rendered = false;
    }
    private static double distance(double x, double y, double z) { return Math.hypot(Math.hypot(x, y), z); }
}
