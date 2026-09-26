package wi.unigrav.core.player;

import java.util.Objects;

/** Source observation from an exact live actor read; not a physical snapshot or propulsion permit. */
public record GIntentObservation(GIntentProtocol.Key key, Source source, long sourceEpoch,
                                 long revision, boolean active) {
    public enum Source { DEDICATED_MODE, INTEGRATED_INTENT, NEGOTIATED_INTENT }
    public GIntentObservation {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(source, "source");
        if (sourceEpoch < 0 || revision < 0) throw new IllegalArgumentException("Negative G observation revision");
    }
}
