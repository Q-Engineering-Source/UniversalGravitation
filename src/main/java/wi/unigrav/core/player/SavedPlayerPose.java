package wi.unigrav.core.player;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.nbt.NBTTagCompound;

/** Bounded historical data only. In particular this type cannot produce an accepted placement or fit proof. */
record SavedPlayerPose(UUID player, UUID world, int dimension, Kind kind, Optional<CardinalFrame> reference,
        Vec3d center, Vec3d transport, Aabb bounds, Rotation3d attitude, Vec3d eye,
        PlayerBodyShape.Dimensions nativeDimensions, Optional<PlayerBodyShape.CollisionProfile> physical,
        Optional<Vec3d> storedHalfLine) {
    enum Kind { CARDINAL, FREE, CRAWLING, SWIMMING }
    private static final Set<String> FIELDS = Set.of("schema", "playerMost", "playerLeast", "worldMost", "worldLeast",
            "dimension", "kind", "reference", "center", "transport", "boundsMin", "boundsMax", "attitude", "eye",
            "nativeWidth", "nativeHeight", "nativeEye", "diameter", "length", "storedHalfLine");

    static SavedPlayerPose read(NBTTagCompound tag) {
        if (tag.getSize() > FIELDS.size() || !FIELDS.containsAll(tag.getKeySet()))
            throw new IllegalArgumentException("Unknown or oversized UG pose history fields");
        require(tag, "schema", 3);
        if (tag.getInteger("schema") != 1) throw new IllegalArgumentException("Unsupported UG pose history schema");
        for (String key : new String[]{"playerMost", "playerLeast", "worldMost", "worldLeast"}) require(tag, key, 4);
        require(tag, "dimension", 3); require(tag, "kind", 8);
        if (tag.getString("kind").length() > 8) throw new IllegalArgumentException("Oversized pose history kind");
        Kind kind = Kind.valueOf(tag.getString("kind"));
        Optional<CardinalFrame> reference = Optional.empty();
        if (kind != Kind.FREE && (kind == Kind.CARDINAL || tag.hasKey("reference"))) {
            require(tag, "reference", 8);
            if (tag.getString("reference").length() > 5) throw new IllegalArgumentException("Oversized pose history reference");
            reference = Optional.of(CardinalFrame.valueOf(tag.getString("reference")));
        } else if (tag.hasKey("reference")) {
            throw new IllegalArgumentException("Ordinary Free history cannot claim a Cardinal reference");
        }
        Vec3d center = vector(tag, "center"), transport = vector(tag, "transport"), eye = vector(tag, "eye");
        Vec3d min = vector(tag, "boundsMin"), max = vector(tag, "boundsMax");
        if (!(min.x() < max.x() && min.y() < max.y() && min.z() < max.z()))
            throw new IllegalArgumentException("Pose history bounds must have positive extents");
        Aabb bounds = new Aabb(min, max);
        require(tag, "attitude", 10);
        NBTTagCompound q = tag.getCompoundTag("attitude");
        if (q.getSize() != 4) throw new IllegalArgumentException("Pose history quaternion requires four scalars");
        Rotation3d rotation = Rotation3d.reconstructExact(scalar(q, "x"), scalar(q, "y"), scalar(q, "z"), scalar(q, "w"));
        var dimensions = new PlayerBodyShape.Dimensions(scalar(tag, "nativeWidth"),
                scalar(tag, "nativeHeight"), scalar(tag, "nativeEye"));
        if (!StoredRealizationLimits.sameRaw(transport.y(), min.y()) || transport.x() < min.x() || transport.x() > max.x()
                || transport.z() < min.z() || transport.z() > max.z())
            throw new IllegalArgumentException("Pose history center/transport/bounds disagree");
        Optional<PlayerBodyShape.CollisionProfile> physical = Optional.empty();
        Optional<Vec3d> halfLine = Optional.empty();
        if (kind == Kind.CARDINAL) {
            if (tag.hasKey("diameter") || tag.hasKey("length") || tag.hasKey("storedHalfLine"))
                throw new IllegalArgumentException("Cardinal history cannot contain a Free body profile");
            CardinalFrame frame = reference.orElseThrow();
            Vec3d up = rotation.basisY().subtract(frame.up());
            if (Math.max(Math.abs(up.x()), Math.max(Math.abs(up.y()), Math.abs(up.z()))) > 1e-12)
                throw new IllegalArgumentException("Cardinal history attitude disagrees with its frame");
            if (!StoredRealizationLimits.sameRaw(center, bounds.center()))
                throw new IllegalArgumentException("Cardinal history bounds do not describe its physical center");
        } else {
            var profile = new PlayerBodyShape.CollisionProfile(scalar(tag, "diameter"), scalar(tag, "length"));
            Vec3d half = vector(tag, "storedHalfLine");
            var capsule = new Capsule(new Segment3d(center.subtract(half), center.add(half)), profile.diameter() / 2);
            Aabb capsuleBounds = capsule.bounds();
            if (min.x() > capsuleBounds.min().x() || min.y() > capsuleBounds.min().y() || min.z() > capsuleBounds.min().z()
                    || max.x() < capsuleBounds.max().x() || max.y() < capsuleBounds.max().y() || max.z() < capsuleBounds.max().z())
                throw new IllegalArgumentException("Free history proxy does not contain its actual endpoints and radius");
            physical = Optional.of(profile); halfLine = Optional.of(half);
        }
        return new SavedPlayerPose(tag.getUniqueId("player"), tag.getUniqueId("world"), tag.getInteger("dimension"),
                kind, reference, center, transport, bounds, rotation, eye, dimensions, physical, halfLine);
    }

    private static Vec3d vector(NBTTagCompound tag, String key) {
        require(tag, key, 10);
        var value = tag.getCompoundTag(key);
        if (value.getSize() != 3) throw new IllegalArgumentException("Pose history vector requires three scalars: " + key);
        Vec3d vector = new Vec3d(scalar(value, "x"), scalar(value, "y"), scalar(value, "z"));
        StoredRealizationLimits.requirePoint(vector, "saved pose " + key);
        return vector;
    }

    private static double scalar(NBTTagCompound tag, String key) {
        require(tag, key, 6);
        double value = tag.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite pose history scalar: " + key);
        return value;
    }

    private static void require(NBTTagCompound tag, String key, int type) {
        if (!tag.hasKey(key, type)) throw new IllegalArgumentException("Invalid or missing pose history field: " + key);
    }
}
