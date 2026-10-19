package wi.unigrav.core.player;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;

/** Read-only tracked presentation, including accepted Cardinal pick geometry; never movement authority. */
public final class PlayerRenderPoseProtocol {
    public static final String CHANNEL = "ugpose1";
    public static final int VERSION = 2;
    public static final int MAX_BYTES = 256;
    private static final double UNIT_QUATERNION_ERROR = 1.0e-6;

    private PlayerRenderPoseProtocol() { }

    public enum Kind { HELLO, WELCOME, FREE_LEASE, FREE_POSE, POSE, REMOVE, PRESENTATION_RELEASE }
    public enum Mode { CARDINAL, FREE }

    /** Exact accepted box and its compatibility coordinate, bound to the packet's physical revision. */
    public record CardinalGeometry(UUID physicsSession, long physicsWorldEpoch, Vec3d transport, Aabb bounds) {
        public CardinalGeometry {
            Objects.requireNonNull(physicsSession, "physicsSession");
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(bounds, "bounds");
            if (physicsWorldEpoch < 0) throw new IllegalArgumentException("Negative physics world epoch");
            for (double value : new double[] {transport.x(), transport.y(), transport.z(),
                    bounds.min().x(), bounds.min().y(), bounds.min().z(),
                    bounds.max().x(), bounds.max().y(), bounds.max().z()})
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite tracked geometry");
            if (!(bounds.max().x() > bounds.min().x() && bounds.max().y() > bounds.min().y()
                    && bounds.max().z() > bounds.min().z())) throw new IllegalArgumentException("Empty tracked geometry");
            if (!near(transport.x(), (bounds.min().x() + bounds.max().x()) / 2)
                    || !near(transport.y(), bounds.min().y())
                    || !near(transport.z(), (bounds.min().z() + bounds.max().z()) / 2))
                throw new IllegalArgumentException("Tracked box does not match its accepted transport");
        }

        void validateFrame(int frame) {
            Vec3d up = CardinalFrame.values()[frame].up();
            double x = bounds.max().x() - bounds.min().x();
            double y = bounds.max().y() - bounds.min().y();
            double z = bounds.max().z() - bounds.min().z();
            double first = up.x() != 0 ? y : x;
            double second = up.z() != 0 ? y : z;
            if (!near(first, second)) throw new IllegalArgumentException("Cardinal transverse dimensions differ");
            new PlayerBodyShape.Dimensions(first, Math.abs(up.x()) * x + Math.abs(up.y()) * y + Math.abs(up.z()) * z, 0);
        }

        public Vec3d rootOffset(int frame) {
            Vec3d up = CardinalFrame.values()[frame].up();
            Vec3d min = bounds.min(), max = bounds.max();
            double height = Math.abs(up.x()) * (max.x() - min.x())
                    + Math.abs(up.y()) * (max.y() - min.y()) + Math.abs(up.z()) * (max.z() - min.z());
            return new Vec3d((min.x() + max.x()) / 2, (min.y() + max.y()) / 2,
                    (min.z() + max.z()) / 2).subtract(up.multiply(height / 2)).subtract(transport);
        }

        private static boolean near(double a, double b) { return Math.abs(a - b) <= 1.0e-6; }
    }

    /** Presentation-only release; this is never a grant of movement custody. */
    public record PresentationRelease(UUID physicsSession, long physicsWorldEpoch, long generation) {
        public PresentationRelease {
            Objects.requireNonNull(physicsSession, "physicsSession");
            if (physicsWorldEpoch < 0 || generation < 0) throw new IllegalArgumentException("Negative release identity");
        }
    }

    /** Wire quaternion. Unit length is checked on construction; it is never normalized before validation. */
    public record Quaternion(double x, double y, double z, double w) {
        public Quaternion {
            double normSquared = x * x + y * y + z * z + w * w;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(w)
                    || !Double.isFinite(normSquared)
                    || Math.abs(normSquared - 1.0) > UNIT_QUATERNION_ERROR) {
                throw new IllegalArgumentException("Render pose quaternion must be finite and unit length");
            }
        }

        public double dot(Quaternion other) {
            return x * other.x + y * other.y + z * other.z + w * other.w;
        }

        /** Convert a right-handed model-up basis into the UIE FlightAttitude quaternion convention. */
        public static Quaternion fromUpForward(Vec3d requestedUp, Vec3d requestedForward) {
            Vec3d up = normalized(requestedUp);
            Vec3d forward = normalized(project(requestedForward, up));
            Vec3d cameraRight = up.cross(forward);
            double m00 = cameraRight.x(), m01 = up.x(), m02 = forward.x();
            double m10 = cameraRight.y(), m11 = up.y(), m12 = forward.y();
            double m20 = cameraRight.z(), m21 = up.z(), m22 = forward.z();
            double x, y, z, w;
            double trace = m00 + m11 + m22;
            if (trace > 0.0) {
                double s = Math.sqrt(trace + 1.0) * 2.0;
                w = 0.25 * s;
                x = (m21 - m12) / s;
                y = (m02 - m20) / s;
                z = (m10 - m01) / s;
            } else if (m00 > m11 && m00 > m22) {
                double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
                w = (m21 - m12) / s;
                x = 0.25 * s;
                y = (m01 + m10) / s;
                z = (m02 + m20) / s;
            } else if (m11 > m22) {
                double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
                w = (m02 - m20) / s;
                x = (m01 + m10) / s;
                y = 0.25 * s;
                z = (m12 + m21) / s;
            } else {
                double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
                w = (m10 - m01) / s;
                x = (m02 + m20) / s;
                y = (m12 + m21) / s;
                z = 0.25 * s;
            }
            return new Quaternion(x, y, z, w);
        }

        private static Vec3d project(Vec3d value, Vec3d normal) {
            return value.subtract(normal.multiply(value.dot(normal)));
        }

        private static Vec3d normalized(Vec3d value) {
            Objects.requireNonNull(value, "basis vector");
            double lengthSquared = value.dot(value);
            if (!Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-12)
                throw new IllegalArgumentException("Render pose basis is degenerate");
            return value.multiply(1.0 / Math.sqrt(lengthSquared));
        }
    }

    /**
     * HELLO/FREE_POSE are clientbound-to-serverbound requests. POSE/REMOVE are
     * server-authorized render-cache updates bound to one viewer connection.
     */
    public record Packet(Kind kind, UUID connectionToken, long viewerRealm, long worldRequestGeneration,
                         UUID playerId, int entityId, int dimension,
                         long worldEpoch, long lifetime, long sequence, long physicalRevision,
                         Mode mode, int cardinalFrame, Quaternion body, Quaternion head, CardinalGeometry geometry, PresentationRelease release) {
        public Packet(Kind kind, UUID connectionToken, long viewerRealm, long worldRequestGeneration,
                      UUID playerId, int entityId, int dimension, long worldEpoch, long lifetime,
                      long sequence, long physicalRevision, Mode mode, int cardinalFrame, Quaternion body,
                      Quaternion head, CardinalGeometry geometry) {
            this(kind, connectionToken, viewerRealm, worldRequestGeneration, playerId, entityId, dimension,
                    worldEpoch, lifetime, sequence, physicalRevision, mode, cardinalFrame, body, head, geometry, null);
        }
        public Packet(Kind kind, UUID connectionToken, long viewerRealm, long worldRequestGeneration,
                      UUID playerId, int entityId, int dimension, long worldEpoch, long lifetime,
                      long sequence, long physicalRevision, Mode mode, int cardinalFrame, Quaternion body, Quaternion head) {
            this(kind, connectionToken, viewerRealm, worldRequestGeneration, playerId, entityId, dimension,
                    worldEpoch, lifetime, sequence, physicalRevision, mode, cardinalFrame, body, head, null);
        }
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(connectionToken, "connectionToken");
            if (viewerRealm < 0 || worldRequestGeneration < 0 || sequence < 0 || worldEpoch < 0
                    || lifetime < 0 || physicalRevision < 0)
                throw new IllegalArgumentException("Negative render pose fence");
            switch (kind) {
                case HELLO -> {
                    if (viewerRealm != 0 || worldRequestGeneration <= 0 || playerId != null || entityId != -1 || dimension != 0 || worldEpoch != 0
                            || lifetime != 0 || sequence != 0 || physicalRevision != 0 || mode != null
                            || cardinalFrame != -1 || body != null || head != null)
                        throw new IllegalArgumentException("Invalid render pose HELLO");
                }
                case WELCOME -> {
                    if (viewerRealm <= 0 || worldRequestGeneration <= 0 || playerId == null || entityId < 0 || lifetime != 0 || sequence != 0
                            || physicalRevision != 0 || mode != null || cardinalFrame != -1 || body != null || head != null)
                        throw new IllegalArgumentException("Invalid render pose world welcome");
                }
                case FREE_LEASE -> {
                    boolean enabled = lifetime > 0;
                    if (viewerRealm <= 0 || worldRequestGeneration != 0 || playerId == null || entityId < 0 || sequence <= 0 || physicalRevision != 0
                            || (enabled ? mode != Mode.FREE : mode != null)
                            || cardinalFrame != -1 || body != null || head != null)
                        throw new IllegalArgumentException("Invalid free render pose lease");
                }
                case FREE_POSE -> {
                    if (viewerRealm <= 0 || worldRequestGeneration != 0 || playerId != null || entityId != -1 || lifetime <= 0 || sequence <= 0
                            || physicalRevision != 0 || mode != Mode.FREE
                            || cardinalFrame != -1 || body == null || head == null)
                        throw new IllegalArgumentException("Invalid free render pose report");
                }
                case POSE -> {
                    if (viewerRealm <= 0 || worldRequestGeneration != 0 || playerId == null || entityId < 0 || lifetime <= 0 || sequence <= 0 || mode == null
                            || body == null || head == null
                            || mode == Mode.CARDINAL && (cardinalFrame < 0 || cardinalFrame >= CardinalFrame.values().length)
                            || mode == Mode.FREE && cardinalFrame != -1) {
                        throw new IllegalArgumentException("Invalid render pose update");
                    }
                }
                case REMOVE, PRESENTATION_RELEASE -> {
                    if (viewerRealm <= 0 || worldRequestGeneration != 0 || playerId == null || entityId < 0 || lifetime <= 0 || sequence <= 0 || mode != null
                            || cardinalFrame != -1 || body != null || head != null)
                        throw new IllegalArgumentException("Invalid render pose removal");
                }
            }
            if ((kind == Kind.PRESENTATION_RELEASE) != (release != null))
                throw new IllegalArgumentException("Release identity requires PRESENTATION_RELEASE");
            if (geometry != null) {
                if (kind != Kind.POSE || mode != Mode.CARDINAL)
                    throw new IllegalArgumentException("Geometry requires an accepted Cardinal POSE revision");
                geometry.validateFrame(cardinalFrame);
                Vec3d up = CardinalFrame.values()[cardinalFrame].up();
                if (!CardinalGeometry.near(2 * (body.x() * body.y() - body.z() * body.w()), up.x())
                        || !CardinalGeometry.near(1 - 2 * (body.x() * body.x() + body.z() * body.z()), up.y())
                        || !CardinalGeometry.near(2 * (body.y() * body.z() + body.x() * body.w()), up.z()))
                    throw new IllegalArgumentException("Tracked body quaternion does not match its Cardinal frame");
            }
        }

        public static Packet hello(UUID connectionToken, long worldRequestGeneration) {
            return new Packet(Kind.HELLO, connectionToken, 0, worldRequestGeneration, null, -1, 0, 0, 0, 0, 0,
                    null, -1, null, null);
        }

        public static Packet freePose(UUID connectionToken, long viewerRealm, int dimension, long worldEpoch, long lease,
                                      long sequence, Quaternion body, Quaternion head) {
            return new Packet(Kind.FREE_POSE, connectionToken, viewerRealm, 0, null, -1, dimension, worldEpoch, lease, sequence, 0,
                    Mode.FREE, -1, body, head);
        }

        public static Packet welcome(UUID connectionToken, long viewerRealm, long worldRequestGeneration,
                                     UUID playerId, int entityId,
                                     int dimension, long worldEpoch) {
            return new Packet(Kind.WELCOME, connectionToken, viewerRealm, worldRequestGeneration, playerId, entityId, dimension,
                    worldEpoch, 0, 0, 0, null, -1, null, null);
        }

        public static Packet freeLease(UUID connectionToken, long viewerRealm, UUID playerId, int entityId, int dimension,
                                       long worldEpoch, long lease, long sequence) {
            return new Packet(Kind.FREE_LEASE, connectionToken, viewerRealm, 0, playerId, entityId, dimension, worldEpoch,
                    lease, sequence, 0, lease > 0 ? Mode.FREE : null, -1, null, null);
        }

        public static Packet pose(UUID connectionToken, long viewerRealm, UUID playerId, int entityId, int dimension,
                                  long worldEpoch, long lifetime, long sequence, long physicalRevision,
                                  Mode mode, int cardinalFrame, Quaternion body, Quaternion head) {
            return new Packet(Kind.POSE, connectionToken, viewerRealm, 0, playerId, entityId, dimension, worldEpoch,
                    lifetime, sequence, physicalRevision, mode, cardinalFrame, body, head);
        }

        public static Packet remove(UUID connectionToken, long viewerRealm, UUID playerId, int entityId, int dimension,
                                    long worldEpoch, long lifetime, long sequence, long physicalRevision) {
            return new Packet(Kind.REMOVE, connectionToken, viewerRealm, 0, playerId, entityId, dimension, worldEpoch,
                    lifetime, sequence, physicalRevision, null, -1, null, null);
        }

        public static Packet presentationRelease(UUID token, long realm, UUID player, int entity, int dimension,
                                           long epoch, long lifetime, long sequence, long revision, PresentationRelease release) {
            return new Packet(Kind.PRESENTATION_RELEASE, token, realm, 0, player, entity, dimension, epoch,
                    lifetime, sequence, revision, null, -1, null, null, null, release);
        }

        public boolean serverbound() { return kind == Kind.HELLO || kind == Kind.FREE_POSE; }
    }

    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        ByteBuffer out = ByteBuffer.allocate(MAX_BYTES);
        out.put((byte) VERSION).put((byte) packet.kind().ordinal());
        putUuid(out, packet.connectionToken());
        out.putLong(packet.viewerRealm());
        if (packet.kind() == Kind.HELLO) {
            out.putLong(packet.worldRequestGeneration());
            return finish(out);
        }
        if (packet.kind() == Kind.FREE_POSE) {
            out.putInt(packet.dimension()).putLong(packet.worldEpoch()).putLong(packet.lifetime()).putLong(packet.sequence());
            putQuaternion(out, packet.body());
            putQuaternion(out, packet.head());
            return finish(out);
        }
        putUuid(out, packet.playerId());
        if (packet.kind() == Kind.WELCOME) out.putLong(packet.worldRequestGeneration());
        out.putInt(packet.entityId()).putInt(packet.dimension()).putLong(packet.worldEpoch())
                .putLong(packet.lifetime()).putLong(packet.sequence()).putLong(packet.physicalRevision());
        if (packet.kind() == Kind.PRESENTATION_RELEASE) {
            putUuid(out, packet.release().physicsSession());
            out.putLong(packet.release().physicsWorldEpoch()).putLong(packet.release().generation());
            return finish(out);
        }
        if (packet.kind() == Kind.WELCOME || packet.kind() == Kind.REMOVE || packet.kind() == Kind.FREE_LEASE)
            return finish(out);
        out.put((byte) packet.mode().ordinal()).put((byte) packet.cardinalFrame());
        putQuaternion(out, packet.body());
        putQuaternion(out, packet.head());
        out.put((byte) (packet.geometry() == null ? 0 : 1));
        if (packet.geometry() != null) {
            var geometry = packet.geometry();
            putUuid(out, geometry.physicsSession());
            out.putLong(geometry.physicsWorldEpoch());
            putVector(out, geometry.transport());
            putVector(out, geometry.bounds().min());
            putVector(out, geometry.bounds().max());
        }
        return finish(out);
    }

    public static Packet decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Render pose packet length");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        try {
            if (Byte.toUnsignedInt(in.get()) != VERSION) throw new IllegalArgumentException("Render pose version");
            Kind kind = enumValue(Kind.values(), in.get());
            UUID connectionToken = getUuid(in);
            long viewerRealm = in.getLong();
            Packet packet;
            if (kind == Kind.HELLO) {
                if (viewerRealm != 0) throw new IllegalArgumentException("Invalid HELLO realm");
                packet = Packet.hello(connectionToken, in.getLong());
            }
            else if (kind == Kind.FREE_POSE) {
                packet = Packet.freePose(connectionToken, viewerRealm, in.getInt(), in.getLong(), in.getLong(), in.getLong(),
                        getQuaternion(in), getQuaternion(in));
            } else {
                UUID player = getUuid(in);
                long worldRequestGeneration = kind == Kind.WELCOME ? in.getLong() : 0;
                int entityId = in.getInt();
                int dimension = in.getInt();
                long worldEpoch = in.getLong();
                long lifetime = in.getLong();
                long sequence = in.getLong();
                long revision = in.getLong();
                if (kind == Kind.WELCOME) packet = Packet.welcome(connectionToken, viewerRealm, worldRequestGeneration,
                        player, entityId, dimension, worldEpoch);
                else if (kind == Kind.FREE_LEASE) packet = Packet.freeLease(connectionToken, viewerRealm, player, entityId, dimension,
                        worldEpoch, lifetime, sequence);
                else if (kind == Kind.PRESENTATION_RELEASE) packet = Packet.presentationRelease(connectionToken, viewerRealm,
                        player, entityId, dimension, worldEpoch, lifetime, sequence, revision,
                        new PresentationRelease(getUuid(in), in.getLong(), in.getLong()));
                else if (kind == Kind.REMOVE) packet = Packet.remove(connectionToken, viewerRealm, player, entityId, dimension,
                        worldEpoch, lifetime, sequence, revision);
                else {
                    Mode mode = enumValue(Mode.values(), in.get());
                    int frame = in.get();
                    Quaternion body = getQuaternion(in), head = getQuaternion(in);
                    int tag = Byte.toUnsignedInt(in.get());
                    if (tag > 1) throw new IllegalArgumentException("Unknown tracked geometry tag");
                    CardinalGeometry geometry = tag == 0 ? null : new CardinalGeometry(getUuid(in), in.getLong(),
                            getVector(in), new Aabb(getVector(in), getVector(in)));
                    packet = new Packet(kind, connectionToken, viewerRealm, 0, player, entityId, dimension,
                            worldEpoch, lifetime, sequence, revision, mode, frame, body, head, geometry);
                }
            }
            if (in.hasRemaining()) throw new IllegalArgumentException("Trailing render pose bytes");
            return packet;
        } catch (BufferUnderflowException cause) {
            throw new IllegalArgumentException("Truncated render pose packet", cause);
        }
    }

    private static byte[] finish(ByteBuffer buffer) {
        if (buffer.position() > MAX_BYTES) throw new IllegalArgumentException("Render pose packet length");
        byte[] bytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(bytes);
        return bytes;
    }

    private static void putVector(ByteBuffer out, Vec3d value) {
        out.putDouble(value.x()).putDouble(value.y()).putDouble(value.z());
    }

    private static Vec3d getVector(ByteBuffer in) { return new Vec3d(in.getDouble(), in.getDouble(), in.getDouble()); }

    private static void putQuaternion(ByteBuffer out, Quaternion value) {
        Objects.requireNonNull(value, "quaternion");
        out.putDouble(value.x()).putDouble(value.y()).putDouble(value.z()).putDouble(value.w());
    }

    private static Quaternion getQuaternion(ByteBuffer in) {
        return new Quaternion(in.getDouble(), in.getDouble(), in.getDouble(), in.getDouble());
    }

    private static void putUuid(ByteBuffer out, UUID value) {
        Objects.requireNonNull(value, "uuid");
        out.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer in) { return new UUID(in.getLong(), in.getLong()); }

    private static <T> T enumValue(T[] values, byte ordinal) {
        int index = Byte.toUnsignedInt(ordinal);
        if (index >= values.length) throw new IllegalArgumentException("Unknown render pose enum");
        return values[index];
    }
}
