package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

/** Server-thread G reducer; eligibility does not rewrite the accepted key intent. */
public final class GIntentSession {
    private final GIntentProtocol.Key key;
    private boolean negotiated;
    private long sequence;
    private GIntentProtocol.State state = GIntentProtocol.State.UNKNOWN;

    public GIntentSession(GIntentProtocol.Key key) { this.key = Objects.requireNonNull(key, "key"); }

    public GIntentProtocol.Key key() { return key; }
    public boolean negotiated() { return negotiated; }

    public Optional<GIntentProtocol.Packet> receive(GIntentProtocol.Packet packet, boolean creativeEligible) {
        Objects.requireNonNull(packet, "packet");
        if (!key.equals(packet.key())) return Optional.empty();
        if (packet.kind() == GIntentProtocol.Kind.HELLO) {
            negotiated = true;
            return Optional.of(acknowledgement(creativeEligible));
        }
        if (!negotiated || packet.kind() != GIntentProtocol.Kind.INTENT || packet.sequence() < sequence
                || packet.state() == GIntentProtocol.State.ON && !creativeEligible) return Optional.empty();
        if (packet.sequence() == sequence) {
            return packet.state() == state ? Optional.of(acknowledgement(creativeEligible)) : Optional.empty();
        }
        sequence = packet.sequence();
        state = packet.state();
        return Optional.of(acknowledgement(creativeEligible));
    }

    public Optional<Boolean> observe(boolean creativeEligible) {
        if (!negotiated || state == GIntentProtocol.State.UNKNOWN
                || state == GIntentProtocol.State.ON && !creativeEligible) return Optional.empty();
        return Optional.of(state == GIntentProtocol.State.ON);
    }

    public long revision() { return sequence; }

    private GIntentProtocol.Packet acknowledgement(boolean creativeEligible) {
        GIntentProtocol.State visible = observe(creativeEligible)
                .map(on -> on ? GIntentProtocol.State.ON : GIntentProtocol.State.OFF)
                .orElse(GIntentProtocol.State.UNKNOWN);
        return new GIntentProtocol.Packet(GIntentProtocol.Kind.STATE, key, sequence, visible);
    }
}
