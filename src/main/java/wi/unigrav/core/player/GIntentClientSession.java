package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

/** Client-thread intent exchange; acknowledgements never toggle the local gameplay G key. */
public final class GIntentClientSession {
    private final GIntentProtocol.Key key;
    private boolean negotiated;
    private long acknowledged = -1;
    private long sequence;
    private GIntentProtocol.State state = GIntentProtocol.State.UNKNOWN;
    private GIntentProtocol.Packet pending;

    public GIntentClientSession(GIntentProtocol.Key key) { this.key = Objects.requireNonNull(key, "key"); }
    public GIntentProtocol.Key key() { return key; }

    public GIntentProtocol.Packet hello() {
        return new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, key, 0, GIntentProtocol.State.UNKNOWN);
    }

    public void receive(GIntentProtocol.Packet packet) {
        if (!key.equals(packet.key()) || packet.kind() != GIntentProtocol.Kind.STATE
                || packet.sequence() < acknowledged || packet.sequence() > sequence) return;
        negotiated = true;
        acknowledged = packet.sequence();
        state = packet.state();
    }

    public Optional<GIntentProtocol.Packet> request(boolean on) {
        if (!negotiated) return Optional.empty();
        GIntentProtocol.State wanted = on ? GIntentProtocol.State.ON : GIntentProtocol.State.OFF;
        if (pending != null && pending.state() == wanted) return Optional.empty();
        sequence = Math.incrementExact(sequence);
        pending = new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, key, sequence, wanted);
        return Optional.of(pending);
    }

    /** Publishes effective Creative G without rewriting the caller's persistent key choice. */
    public Optional<GIntentProtocol.Packet> requestEffective(boolean requestedOn, boolean creativeEligible) {
        return request(requestedOn && creativeEligible);
    }

    public Optional<GIntentProtocol.Packet> retry() {
        if (!negotiated) return Optional.of(hello());
        return pending != null && acknowledged < pending.sequence() ? Optional.of(pending) : Optional.empty();
    }

    /** An eligible transition can retry a previously denied ON without changing key intent. */
    public void eligibilityChanged() { pending = null; }

    public Optional<Boolean> acknowledgedIntent() {
        return state == GIntentProtocol.State.UNKNOWN ? Optional.empty() : Optional.of(state == GIntentProtocol.State.ON);
    }

    /** Read-only identity of the actual acknowledged STATE, not the pending local request. */
    public Optional<GIntentObservation> acknowledgedObservation(GIntentObservation.Source source) {
        return acknowledgedIntent().map(on -> new GIntentObservation(key, source, 0, acknowledged, on));
    }
}
