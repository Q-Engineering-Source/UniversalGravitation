package wi.unigrav.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Constant-memory, monotonic timing counters. No normal-frame logging or retained sample lists. */
public final class MotionTimingWindow {
    public enum Signal {
        FRAME(50), CLIENT_TICK(100), STATE_RECEIVE(75), STATE_APPLY(75), STATE_QUEUE(100), SERVER_TICK(100), SERVER_WORK(50);
        private final long thresholdNs;
        Signal(long milliseconds) { thresholdNs = milliseconds * 1_000_000; }
    }

    public record Sample(long count, double maximumMs, double meanMs, long outliers) { }
    public record Report(Map<Signal, Sample> samples) {
        public Report { samples = Collections.unmodifiableMap(new EnumMap<>(samples)); }
        @Override public String toString() {
            StringBuilder text = new StringBuilder();
            samples.forEach((signal, sample) -> {
                if (sample.count() != 0) text.append(String.format(Locale.ROOT,
                        "%s[n=%d,max=%.1fms,mean=%.1fms,spikes=%d] ", signal,
                        sample.count(), sample.maximumMs(), sample.meanMs(), sample.outliers()));
            });
            return text.toString().trim();
        }
    }

    private final long[] last = new long[Signal.values().length];
    private final boolean[] seen = new boolean[last.length];
    private final long[] count = new long[last.length];
    private final long[] sum = new long[last.length];
    private final long[] maximum = new long[last.length];
    private final long[] outliers = new long[last.length];
    private boolean started;
    private long windowStart;

    public void interval(Signal signal, long now) {
        start(now);
        int index = signal.ordinal();
        if (seen[index]) duration(signal, now - last[index], now);
        seen[index] = true;
        last[index] = now;
    }

    public void duration(Signal signal, long elapsed, long now) {
        if (elapsed < 0) throw new IllegalArgumentException("negative monotonic duration");
        start(now);
        int index = signal.ordinal();
        count[index]++;
        sum[index] += elapsed;
        maximum[index] = Math.max(maximum[index], elapsed);
        if (elapsed > signal.thresholdNs) outliers[index]++;
    }

    public Optional<Report> poll(long now) {
        return poll(now, false);
    }

    /** Periodic normal summaries are opt-in; server timing keeps its anomaly-only policy. */
    public Optional<Report> poll(long now, boolean includeNormal) {
        if (!started || now - windowStart < 5_000_000_000L) return Optional.empty();
        boolean anomalous = false;
        for (long spikes : outliers) anomalous |= spikes > 0;
        Report report = null;
        if (anomalous || includeNormal) {
            var values = new EnumMap<Signal, Sample>(Signal.class);
            for (Signal signal : Signal.values()) {
                int i = signal.ordinal();
                values.put(signal, new Sample(count[i], maximum[i] / 1e6,
                        count[i] == 0 ? 0 : sum[i] / (double) count[i] / 1e6, outliers[i]));
            }
            report = new Report(values);
        }
        Arrays.fill(count, 0);
        Arrays.fill(sum, 0);
        Arrays.fill(maximum, 0);
        Arrays.fill(outliers, 0);
        windowStart = now;
        return Optional.ofNullable(report);
    }

    private void start(long now) {
        if (!started) { started = true; windowStart = now; }
    }

    public void reset() {
        started = false;
        Arrays.fill(seen, false);
        Arrays.fill(count, 0);
        Arrays.fill(sum, 0);
        Arrays.fill(maximum, 0);
        Arrays.fill(outliers, 0);
    }
}
