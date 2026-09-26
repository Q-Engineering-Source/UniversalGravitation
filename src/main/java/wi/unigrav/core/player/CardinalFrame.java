package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

/** Exact right-handed signed-permutation frames; local +Y is physical up. */
public enum CardinalFrame {
    Y_POS(new Rotation3d(0, 0, 0, 1)),
    Y_NEG(new Rotation3d(1, 0, 0, 0)),
    X_POS(new Rotation3d(0, 0, -1, 1)),
    X_NEG(new Rotation3d(0, 0, 1, 1)),
    Z_POS(new Rotation3d(1, 0, 0, 1)),
    Z_NEG(new Rotation3d(-1, 0, 0, 1));

    private final Rotation3d attitude;

    CardinalFrame(Rotation3d attitude) {
        this.attitude = attitude;
    }

    /** Converts local vector components to world components without quaternion round-off. */
    public Vec3d toWorld(Vec3d local) {
        Objects.requireNonNull(local, "local");
        return switch (this) {
            case Y_POS -> local;
            case Y_NEG -> new Vec3d(local.x(), -local.y(), -local.z());
            case X_POS -> new Vec3d(local.y(), -local.x(), local.z());
            case X_NEG -> new Vec3d(-local.y(), local.x(), local.z());
            case Z_POS -> new Vec3d(local.x(), -local.z(), local.y());
            case Z_NEG -> new Vec3d(local.x(), local.z(), -local.y());
        };
    }

    /** Converts world vector components to this exact local frame. */
    public Vec3d toLocal(Vec3d world) {
        Objects.requireNonNull(world, "world");
        return switch (this) {
            case Y_POS -> world;
            case Y_NEG -> new Vec3d(world.x(), -world.y(), -world.z());
            case X_POS -> new Vec3d(-world.y(), world.x(), world.z());
            case X_NEG -> new Vec3d(world.y(), -world.x(), world.z());
            case Z_POS -> new Vec3d(world.x(), world.z(), -world.y());
            case Z_NEG -> new Vec3d(world.x(), -world.z(), world.y());
        };
    }

    /** Transforms an AABB about the coordinate origin; no rotation sweep is implied. */
    public Aabb toWorld(Aabb local) {
        Vec3d first = toWorld(local.min());
        Vec3d second = toWorld(local.max());
        return new Aabb(first.min(second), first.max(second));
    }

    public Vec3d up() {
        return toWorld(new Vec3d(0, 1, 0));
    }

    /** Reference attitude only; cardinal body heading may additionally twist about up. */
    public Rotation3d attitude() {
        return attitude;
    }

    public static CardinalFrame fromFloorFace(Face face) {
        return switch (face) {
            case UP -> Y_POS;
            case DOWN -> Y_NEG;
            case EAST -> X_POS;
            case WEST -> X_NEG;
            case SOUTH -> Z_POS;
            case NORTH -> Z_NEG;
        };
    }
}
