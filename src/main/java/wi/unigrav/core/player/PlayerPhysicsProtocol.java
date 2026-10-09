package wi.unigrav.core.player;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.boost.BoostProtocol;

/** Independent physical-owner messages; positions are metres and velocities m/s. */
public final class PlayerPhysicsProtocol {
    public static final String CHANNEL = "ugmotion1";
    public static final int VERSION = 4;
    public static final int MAX_BYTES = 512;
    private PlayerPhysicsProtocol() {}
    public enum Kind { OFFER, APPLY, OWNED, INPUT, STATE, NATIVE }
    public record OperationId(long nonce, long epoch, long serial, BoostProtocol.Operation operation) {
        public OperationId {
            Objects.requireNonNull(operation, "operation");
            if (epoch < 0 || serial < 0) throw new IllegalArgumentException("Negative operation identity");
        }
        static OperationId from(BoostProtocol.Packet packet) {
            return new OperationId(packet.nonce(), packet.epoch(), packet.sequence(), packet.operation());
        }
    }
    public record ApplicationId(OperationId state, Optional<OperationId> lastVelocityOperation) {
        public ApplicationId {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(lastVelocityOperation, "lastVelocityOperation");
            lastVelocityOperation.ifPresent(last -> {
                if (last.nonce() != state.nonce() || last.operation() == BoostProtocol.Operation.NONE
                        || last.epoch() > state.epoch()
                        || (last.epoch() == state.epoch() && last.serial() > state.serial())) {
                    throw new IllegalArgumentException("Applied operation identity/order mismatch");
                }
            });
            if (state.operation() != BoostProtocol.Operation.NONE
                    && !lastVelocityOperation.equals(Optional.of(state))) {
                throw new IllegalArgumentException("Current operation has not been applied");
            }
        }
        public static ApplicationId from(BoostProtocol.StateApplication receipt) {
            Objects.requireNonNull(receipt, "receipt");
            return new ApplicationId(OperationId.from(receipt.state()),
                    Optional.ofNullable(receipt.lastVelocityOperation()).map(OperationId::from));
        }
    }
    /** Translation and camera forward are WORLD vectors; the client samples both from the same UIE frame. */
    public record Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                           boolean sprint, boolean boost, int boostGear, double keyboardTravelGain) {
        /** Existing world/analog callers carry no keyboard-only travel magnitude. */
        public Controls(Vec3d translation, Vec3d forward, boolean jump, boolean sneak,
                        boolean sprint, boolean boost, int boostGear) {
            this(translation, forward, jump, sneak, sprint, boost, boostGear, 1.0);
        }

        public Controls {
            requireFinite(translation);
            requireFinite(forward);
            double magnitude = length(translation);
            if (magnitude > 1.0 + 1e-12 || Math.abs(length(forward) - 1) > 1e-12
                    || boostGear < 1 || boostGear > 9
                    || !Double.isFinite(keyboardTravelGain) || keyboardTravelGain < 1.0
                    || keyboardTravelGain > Math.sqrt(2.0)
                    || keyboardTravelGain > 1.0 && Math.abs(magnitude - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Invalid physical controls");
            }
        }
    }
    public record Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence,
                         long offerSequence, Optional<ApplicationId> application,
                         Optional<PlayerPhysicalSnapshot> snapshot, Optional<Controls> controls,
                         OptionalInt nativeTeleportId) {
        public Packet(Kind kind, PlayerPhysicalSnapshot.Scope scope, long sequence, long offerSequence,
                      Optional<ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot, Optional<Controls> controls) {
            this(kind, scope, sequence, offerSequence, application, snapshot, controls, OptionalInt.empty());
        }
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(controls, "controls");
            Objects.requireNonNull(nativeTeleportId, "nativeTeleportId");
            if (nativeTeleportId.isPresent() && (kind != Kind.STATE || nativeTeleportId.getAsInt() < 0))
                throw new IllegalArgumentException("Invalid native correction identity");
            if (sequence < 0 || offerSequence < 0) throw new IllegalArgumentException("Negative sequence");
            boolean state = kind == Kind.OWNED || kind == Kind.STATE;
            if (snapshot.isPresent() != state || controls.isPresent() != (kind == Kind.INPUT)
                    || (application.isPresent() && kind != Kind.APPLY && kind != Kind.OWNED)) {
                throw new IllegalArgumentException("Payload does not match packet kind");
            }
            snapshot.ifPresent(value -> {
                if (!scope.equals(value.scope())) throw new IllegalArgumentException("Snapshot scope mismatch");
                if (!(value.placement().shape() instanceof PlayerBodyShape.Cardinal)) {
                    throw new IllegalArgumentException("Version 1 requires cardinal placement");
                }
                requireFinite(value.worldVelocity());
            });
        }
        public boolean clientbound() { return kind == Kind.OFFER || kind == Kind.OWNED || kind == Kind.STATE || kind == Kind.NATIVE; }
    }

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        ByteBuffer out = ByteBuffer.allocate(MAX_BYTES);
        out.put((byte) VERSION).put((byte) packet.kind().ordinal());
        putUuid(out, packet.scope().playerId());
        putUuid(out, packet.scope().sessionId());
        out.putInt(packet.scope().dimension()).putLong(packet.scope().worldEpoch());
        out.putLong(packet.sequence()).putLong(packet.offerSequence());
        out.put((byte) (packet.application().isPresent() ? 1 : 0));
        packet.application().ifPresent(application -> {
            putOperation(out, application.state());
            out.put((byte) (application.lastVelocityOperation().isPresent() ? 1 : 0));
            application.lastVelocityOperation().ifPresent(last -> putOperation(out, last));
        });
        packet.snapshot().ifPresent(snapshot -> {
            out.put((byte) (packet.nativeTeleportId().isPresent() ? 1 : 0));
            packet.nativeTeleportId().ifPresent(out::putInt);
            putSnapshot(out, snapshot);
        });
        packet.controls().ifPresent(controls -> {
            putVector(out, controls.translation());
            putVector(out, controls.forward());
            out.put((byte) ((controls.jump() ? 1 : 0) | (controls.sneak() ? 2 : 0)
                    | (controls.sprint() ? 4 : 0) | (controls.boost() ? 8 : 0)));
            out.put((byte) controls.boostGear());
            out.putDouble(controls.keyboardTravelGain());
        });
        return Arrays.copyOf(out.array(), out.position());
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Physics packet length");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        try {
            if (Byte.toUnsignedInt(in.get()) != VERSION) throw new IllegalArgumentException("Physics version");
            Kind kind = enumValue(Kind.values(), in.get());
            var scope = new PlayerPhysicalSnapshot.Scope(getUuid(in), getUuid(in), in.getInt(), in.getLong());
            long sequence = in.getLong();
            long offerSequence = in.getLong();
            Optional<ApplicationId> application = Optional.empty();
            if (getBoolean(in)) {
                var state = getOperation(in);
                application = Optional.of(new ApplicationId(state,
                        getBoolean(in) ? Optional.of(getOperation(in)) : Optional.empty()));
            }
            OptionalInt nativeTeleportId = OptionalInt.empty();
            Optional<PlayerPhysicalSnapshot> snapshot = Optional.empty();
            if (kind == Kind.OWNED || kind == Kind.STATE) {
                if (getBoolean(in)) nativeTeleportId = OptionalInt.of(in.getInt());
                snapshot = Optional.of(getSnapshot(in, scope));
            }
            Optional<Controls> controls = Optional.empty();
            if (kind == Kind.INPUT) {
                Vec3d translation = getVector(in);
                Vec3d forward = getVector(in);
                int flags = Byte.toUnsignedInt(in.get());
                if (flags > 15) throw new IllegalArgumentException("Unknown control flags");
                controls = Optional.of(new Controls(translation, forward, (flags & 1) != 0, (flags & 2) != 0,
                        (flags & 4) != 0, (flags & 8) != 0, Byte.toUnsignedInt(in.get()), in.getDouble()));
            }
            if (in.hasRemaining()) throw new IllegalArgumentException("Trailing physics bytes");
            return new Packet(kind, scope, sequence, offerSequence, application, snapshot, controls, nativeTeleportId);
        } catch (BufferUnderflowException cause) {
            throw new IllegalArgumentException("Truncated physics packet", cause);
        }
    }

    private static void putSnapshot(ByteBuffer out, PlayerPhysicalSnapshot snapshot) {
        out.putLong(snapshot.revision());
        PlayerBodyPlacement placement = snapshot.placement();
        var cardinal = (PlayerBodyShape.Cardinal) placement.shape();
        out.put((byte) cardinal.frame().ordinal());
        var dimensions = cardinal.dimensions();
        out.putDouble(dimensions.width()).putDouble(dimensions.height()).putDouble(dimensions.eyeHeight());
        Rotation3d q = placement.attitude();
        out.putDouble(q.x()).putDouble(q.y()).putDouble(q.z()).putDouble(q.w());
        putVector(out, placement.bounds().min());
        putVector(out, placement.bounds().max());
        putVector(out, placement.transport());
        putVector(out, snapshot.worldVelocity());
        out.put((byte) (snapshot.contacts().isPresent() ? 1 : 0));
        snapshot.contacts().ifPresent(contact -> {
            out.put((byte) ((contact.onGround() ? 1 : 0) | (contact.horizontal() ? 2 : 0)
                    | (contact.vertical() ? 4 : 0) | (contact.nearFootLocomotion() ? 8 : 0)));
            out.putFloat(contact.fallDistance());
        });
    }

    private static PlayerPhysicalSnapshot getSnapshot(ByteBuffer in, PlayerPhysicalSnapshot.Scope scope) {
        long revision = in.getLong();
        var frame = enumValue(CardinalFrame.values(), in.get());
        var dimensions = new PlayerBodyShape.Dimensions(in.getDouble(), in.getDouble(), in.getDouble());
        var rotation = new Rotation3d(in.getDouble(), in.getDouble(), in.getDouble(), in.getDouble());
        var bounds = new Aabb(getVector(in), getVector(in));
        var transport = getVector(in);
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, transport, rotation,
                new PlayerBodyShape.Cardinal(frame, dimensions));
        Vec3d velocity = getVector(in);
        Optional<PlayerPhysicalSnapshot.Contacts> contacts = Optional.empty();
        if (getBoolean(in)) {
            int flags = Byte.toUnsignedInt(in.get());
            if (flags > 15) throw new IllegalArgumentException("Unknown contact flags");
            contacts = Optional.of(new PlayerPhysicalSnapshot.Contacts((flags & 1) != 0,
                    (flags & 2) != 0, (flags & 4) != 0, in.getFloat(), (flags & 8) != 0));
        }
        return new PlayerPhysicalSnapshot(scope, revision, placement, velocity, contacts);
    }

    private static void putOperation(ByteBuffer out, OperationId value) {
        out.putLong(value.nonce()).putLong(value.epoch()).putLong(value.serial()).put((byte) value.operation().ordinal());
    }
    private static OperationId getOperation(ByteBuffer in) {
        return new OperationId(in.getLong(), in.getLong(), in.getLong(), enumValue(BoostProtocol.Operation.values(), in.get()));
    }
    private static boolean getBoolean(ByteBuffer in) {
        byte value = in.get();
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid boolean tag");
        return value == 1;
    }
    private static <E> E enumValue(E[] values, byte encoded) {
        int index = Byte.toUnsignedInt(encoded);
        if (index >= values.length) throw new IllegalArgumentException("Invalid enum tag");
        return values[index];
    }
    private static void putUuid(ByteBuffer out, UUID value) { out.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer in) { return new UUID(in.getLong(), in.getLong()); }
    private static void putVector(ByteBuffer out, Vec3d v) { out.putDouble(v.x()).putDouble(v.y()).putDouble(v.z()); }
    private static Vec3d getVector(ByteBuffer in) { return new Vec3d(in.getDouble(), in.getDouble(), in.getDouble()); }
    private static double length(Vec3d v) { return Math.hypot(Math.hypot(v.x(), v.y()), v.z()); }
    private static void requireFinite(Vec3d v) {
        Objects.requireNonNull(v, "vector");
        if (!Double.isFinite(v.x()) || !Double.isFinite(v.y()) || !Double.isFinite(v.z())) {
            throw new IllegalArgumentException("Non-finite vector");
        }
    }
}
