package wi.unigrav.core.boost;

import java.nio.ByteBuffer;
import java.util.Objects;

import wi.unigrav.api.math.Vec3;

/** Fixed-size, versioned protocol; all velocity components on the wire are m/s. */
public final class BoostProtocol {
    // The layout is unchanged, but corridor flags and FRAME continuity hints
    // require a matching peer rather than silently running legacy semantics.
    public static final int VERSION = 2;
    public static final String CHANNEL = "ugboost1";
    public static final int PACKET_BYTES = 70;

    // These ordinal tables are wire contracts. Changing their order requires a new version.
    public enum Kind { OFFER, HELLO, STATE, MODE, FRAME }
    public enum Mode { UNAVAILABLE, READY, ACTIVE, CLOSING, SUSPENDED }
    public enum Operation { NONE, ADD, REPLACE, STOP }
    public enum Reason {
        NONE, DISABLED, INCOMPATIBLE, NOT_CREATIVE, UNSUPPORTED_STATE, UNLOADED_AREA,
        COLLISION, BUDGET, PROTOCOL, TIMEOUT, EXTERNAL_VELOCITY, TELEPORT, INVALID_INPUT
    }

    public record Packet(Kind kind, int version, long nonce, long epoch, long sequence,
            int dimension, Mode mode, int gear, double ceilingMps, Operation operation,
            Vec3 velocity, boolean flag, Reason reason) {
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(reason, "reason");
            if (version <= 0 || epoch < 0 || sequence < 0 || gear < 0 || gear > 255
                    || !Double.isFinite(ceilingMps) || ceilingMps < 0 || !finite(velocity)) {
                throw new IllegalArgumentException("Invalid remote boost packet fields");
            }
        }
    }

    private BoostProtocol() { }

    /**
     * Local application evidence, not a wire packet or server authority. The latest
     * accepted metadata STATE is distinct from the last successful velocity operation,
     * which may belong to an earlier epoch. Null means no velocity operation in this
     * connection lifetime, not an inferred zero velocity. The v2 encoding is unchanged.
     */
    public record StateApplication(Packet state, Packet lastVelocityOperation) {
        public StateApplication {
            Objects.requireNonNull(state, "state");
            if (state.kind() != Kind.STATE || state.version() != VERSION) {
                throw new IllegalArgumentException("Application requires a current-version STATE");
            }
            if (lastVelocityOperation != null && (lastVelocityOperation.kind() != Kind.STATE
                    || lastVelocityOperation.version() != VERSION
                    || lastVelocityOperation.operation() == Operation.NONE
                    || lastVelocityOperation.nonce() != state.nonce()
                    || lastVelocityOperation.dimension() != state.dimension()
                    || lastVelocityOperation.epoch() > state.epoch()
                    || (lastVelocityOperation.epoch() == state.epoch()
                    && lastVelocityOperation.sequence() > state.sequence()))) {
                throw new IllegalArgumentException("Velocity application identity/order mismatch");
            }
            if (state.operation() != Operation.NONE && !state.equals(lastVelocityOperation)) {
                throw new IllegalArgumentException("Current velocity STATE was not the applied operation");
            }
        }
    }

    public static Packet offer(long nonce, int dimension, double ceiling, boolean available) {
        return new Packet(Kind.OFFER, VERSION, nonce, 0, 0, dimension, Mode.UNAVAILABLE,
                BoostGearProfile.defaultGear(), ceiling, Operation.NONE, Vec3.ZERO, available,
                available ? Reason.NONE : Reason.DISABLED);
    }

    public static Packet hello(long nonce, boolean ready) {
        return new Packet(Kind.HELLO, VERSION, nonce, 0, 0, 0, Mode.UNAVAILABLE,
                0, 0, Operation.NONE, Vec3.ZERO, ready, Reason.NONE);
    }

    public static Packet modeRequest(long nonce, long epoch, long sequence,
            int dimension, boolean enabled) {
        return new Packet(Kind.MODE, VERSION, nonce, epoch, sequence, dimension,
                enabled ? Mode.ACTIVE : Mode.CLOSING, 0, 0, Operation.NONE, Vec3.ZERO,
                enabled, Reason.NONE);
    }

    public static Packet frame(long nonce, long epoch, long sequence, int dimension,
            int gear, boolean settled) {
        return frame(nonce, epoch, sequence, dimension, gear, settled, Vec3.ZERO);
    }

    /** A finite client velocity hint for continuity, not certified propulsion physics. */
    public static Packet frame(long nonce, long epoch, long sequence, int dimension,
            int gear, boolean settled, Vec3 velocityMps) {
        return new Packet(Kind.FRAME, VERSION, nonce, epoch, sequence, dimension,
                Mode.UNAVAILABLE, gear, 0, Operation.NONE, velocityMps, settled, Reason.NONE);
    }

    public static Packet state(long nonce, long epoch, long serial, int dimension,
            Mode mode, int gear, double ceiling, Operation operation, Vec3 velocity, Reason reason) {
        return state(nonce, epoch, serial, dimension, mode, gear, ceiling, operation, velocity, reason, false);
    }

    /** STATE.flag is the effective corridor/incident restriction; FRAME.flag remains settled. */
    public static Packet state(long nonce, long epoch, long serial, int dimension,
            Mode mode, int gear, double ceiling, Operation operation, Vec3 velocity, Reason reason,
            boolean restrictedCorridor) {
        return new Packet(Kind.STATE, VERSION, nonce, epoch, serial, dimension,
                mode, gear, ceiling, operation, velocity, restrictedCorridor, reason);
    }

    public static byte[] encode(Packet p) {
        Objects.requireNonNull(p, "packet");
        return ByteBuffer.allocate(PACKET_BYTES).put((byte) p.kind().ordinal())
                .putInt(p.version()).putLong(p.nonce()).putLong(p.epoch()).putLong(p.sequence())
                .putInt(p.dimension()).put((byte) p.mode().ordinal()).put((byte) p.gear())
                .putDouble(p.ceilingMps()).put((byte) p.operation().ordinal())
                .putDouble(p.velocity().x()).putDouble(p.velocity().y()).putDouble(p.velocity().z())
                .put((byte) (p.flag() ? 1 : 0)).put((byte) p.reason().ordinal()).array();
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length != PACKET_BYTES) {
            throw new IllegalArgumentException("Remote boost payload must be exactly 70 bytes");
        }
        ByteBuffer b = ByteBuffer.wrap(bytes);
        Kind kind = enumeration(Kind.values(), b.get());
        int version = b.getInt();
        long nonce = b.getLong(), epoch = b.getLong(), sequence = b.getLong();
        int dimension = b.getInt();
        Mode mode = enumeration(Mode.values(), b.get());
        int gear = Byte.toUnsignedInt(b.get());
        double ceiling = b.getDouble();
        Operation operation = enumeration(Operation.values(), b.get());
        Vec3 velocity = new Vec3(b.getDouble(), b.getDouble(), b.getDouble());
        int flag = Byte.toUnsignedInt(b.get());
        if (flag > 1) throw new IllegalArgumentException("Invalid boost boolean encoding");
        Reason reason = enumeration(Reason.values(), b.get());
        return new Packet(kind, version, nonce, epoch, sequence, dimension, mode, gear,
                ceiling, operation, velocity, flag == 1, reason);
    }

    private static <T> T enumeration(T[] values, byte encoded) {
        int index = Byte.toUnsignedInt(encoded);
        if (index >= values.length) throw new IllegalArgumentException("Unknown boost wire enum");
        return values[index];
    }

    public static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x()) && Double.isFinite(value.y())
                && Double.isFinite(value.z());
    }
}
