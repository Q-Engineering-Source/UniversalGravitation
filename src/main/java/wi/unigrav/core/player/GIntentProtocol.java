package wi.unigrav.core.player;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

/** Independent G-intent negotiation only; these identities are NOT physical-owner scopes. */
public final class GIntentProtocol {
    public static final String CHANNEL = "ugphys1";
    public static final int VERSION = 1;
    public static final int PACKET_BYTES = 47;

    private GIntentProtocol() { }

    public enum Kind { OFFER, HELLO, INTENT, STATE }
    public enum State { UNKNOWN, OFF, ON }

    /** A fresh G synchronization lifetime, distinct from PlayerPhysicalSnapshot.Scope. */
    public record Key(UUID sessionId, UUID playerId, int dimension) {
        public Key {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    public record Packet(Kind kind, Key key, long sequence, State state) {
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
            if (sequence < 0 || ((kind == Kind.OFFER || kind == Kind.HELLO)
                    && (sequence != 0 || state != State.UNKNOWN))
                    || (kind == Kind.INTENT && (sequence == 0 || state == State.UNKNOWN))) {
                throw new IllegalArgumentException("Invalid G-intent packet fields");
            }
        }
    }

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        ByteBuffer bytes = ByteBuffer.allocate(PACKET_BYTES);
        bytes.put((byte) VERSION).put((byte) packet.kind().ordinal());
        putUuid(bytes, packet.key().sessionId());
        putUuid(bytes, packet.key().playerId());
        bytes.putInt(packet.key().dimension()).putLong(packet.sequence()).put((byte) packet.state().ordinal());
        return bytes.array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length != PACKET_BYTES) throw new IllegalArgumentException("G-intent length");
        ByteBuffer input = ByteBuffer.wrap(bytes);
        if (Byte.toUnsignedInt(input.get()) != VERSION) throw new IllegalArgumentException("G-intent version");
        int kind = Byte.toUnsignedInt(input.get());
        if (kind >= Kind.values().length) throw new IllegalArgumentException("G-intent kind");
        Key key = new Key(getUuid(input), getUuid(input), input.getInt());
        long sequence = input.getLong();
        int state = Byte.toUnsignedInt(input.get());
        if (state >= State.values().length) throw new IllegalArgumentException("G-intent state");
        return new Packet(Kind.values()[kind], key, sequence, State.values()[state]);
    }

    private static void putUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buffer) { return new UUID(buffer.getLong(), buffer.getLong()); }
}
