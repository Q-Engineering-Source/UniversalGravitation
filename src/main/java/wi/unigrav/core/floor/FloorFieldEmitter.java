package wi.unigrav.core.floor;

import wi.unigrav.api.math.Vec3;

/**
 * One oriented, block-sized floor-field emitter.
 *
 * <p>The designated {@link Face} is the block's outward face. This emitter
 * samples exactly one half-open, 1 x 1 column projected from that face:
 * outward distance is {@code [0, range)}, and both tangent world coordinates
 * are {@code [blockCoordinate, blockCoordinate + 1)}. The half-open tangent
 * footprint gives one owner to a shared edge of adjacent floor blocks.
 *
 * <p>This is pure point-sampling math. It performs neither world discovery nor
 * occlusion, actor filtering, field registration, or acceleration aggregation.
 */
public final class FloorFieldEmitter {

    /** The six possible outward faces of a floor block. */
    public enum Face {
        DOWN,
        UP,
        NORTH,
        SOUTH,
        WEST,
        EAST
    }

    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final Face face;
    private final FloorFieldProfile profile;

    public FloorFieldEmitter(int blockX, int blockY, int blockZ, Face face,
                             FloorFieldProfile profile) {
        if (face == null) {
            throw new IllegalArgumentException("face must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        this.blockX = blockX;
        this.blockY = blockY;
        this.blockZ = blockZ;
        this.face = face;
        this.profile = profile;
    }

    /**
     * Samples the acceleration emitted at a finite world position, in m/s².
     *
     * <p>The returned vector points back toward the designated outward face.
     * Positions outside this emitter's column contribute {@link Vec3#ZERO}.
     */
    public Vec3 accelerationAt(Vec3 point) {
        if (point == null) {
            throw new IllegalArgumentException("point must not be null");
        }
        requireFinite(point.x(), "point.x");
        requireFinite(point.y(), "point.y");
        requireFinite(point.z(), "point.z");

        // Convert before adding one: int max + 1 must never wrap at MAX_VALUE.
        double minX = (double) blockX;
        double minY = (double) blockY;
        double minZ = (double) blockZ;
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;

        return switch (face) {
            case DOWN -> sample(point.x(), minX, maxX, point.z(), minZ, maxZ,
                    minY - point.y(), Face.DOWN);
            case UP -> sample(point.x(), minX, maxX, point.z(), minZ, maxZ,
                    point.y() - maxY, Face.UP);
            case NORTH -> sample(point.x(), minX, maxX, point.y(), minY, maxY,
                    minZ - point.z(), Face.NORTH);
            case SOUTH -> sample(point.x(), minX, maxX, point.y(), minY, maxY,
                    point.z() - maxZ, Face.SOUTH);
            case WEST -> sample(point.y(), minY, maxY, point.z(), minZ, maxZ,
                    minX - point.x(), Face.WEST);
            case EAST -> sample(point.y(), minY, maxY, point.z(), minZ, maxZ,
                    point.x() - maxX, Face.EAST);
        };
    }

    private Vec3 sample(double tangentOne, double tangentOneMin, double tangentOneMax,
                        double tangentTwo, double tangentTwoMin, double tangentTwoMax,
                        double outwardDistance, Face sampledFace) {
        if (tangentOne < tangentOneMin || tangentOne >= tangentOneMax
                || tangentTwo < tangentTwoMin || tangentTwo >= tangentTwoMax
                || outwardDistance < 0.0 || outwardDistance >= profile.range()) {
            return Vec3.ZERO;
        }

        double strength = profile.strengthAt(outwardDistance);
        if (strength == 0.0) {
            return Vec3.ZERO;
        }
        return switch (sampledFace) {
            case DOWN -> new Vec3(0.0, strength, 0.0);
            case UP -> new Vec3(0.0, -strength, 0.0);
            case NORTH -> new Vec3(0.0, 0.0, strength);
            case SOUTH -> new Vec3(0.0, 0.0, -strength);
            case WEST -> new Vec3(strength, 0.0, 0.0);
            case EAST -> new Vec3(-strength, 0.0, 0.0);
        };
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
