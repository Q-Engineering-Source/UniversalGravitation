package wi.unigrav.core.client.camera;

import java.util.Objects;

import wi.unigrav.api.math.Vec3;

/** A bounded long measurement ray, still delegated to the native picker for every segment. */
final class SixDofGroundPickingRay {
    static final double MAX_DISTANCE_METERS = 256.0D;
    private static final double SEGMENT_METERS = 64.0D;

    private SixDofGroundPickingRay() {
    }

    /**
     * Returns the first non-null hit in near-to-far order. The callback must return null for MISS.
     *
     * <p>Cleanroom 1.12.2 World.rayTraceBlocks checks the start cell, then performs at most 201
     * DDA steps. A 64 m unit-direction segment stays well below that budget even on a diagonal
     * (the sum of its absolute axis displacements is at most 64 * sqrt(3)). Splitting avoids
     * exhausting the native budget without replacing
     * native block-shape picking or acquiring another camera sample.
     */
    static <T> T firstHit(Vec3 origin, Vec3 direction, Raycast<T> raycast) {
        Objects.requireNonNull(raycast, "raycast");
        if (!finite(origin) || !finite(direction)) {
            return null;
        }
        double norm = direction.norm();
        if (!Double.isFinite(norm) || norm <= 0.0D) {
            return null;
        }
        Vec3 unitDirection = direction.scale(1.0D / norm);
        for (double offset = 0.0D; offset < MAX_DISTANCE_METERS; offset += SEGMENT_METERS) {
            Vec3 start = origin.add(unitDirection.scale(offset));
            double length = Math.min(SEGMENT_METERS, MAX_DISTANCE_METERS - offset);
            T hit = raycast.cast(start, unitDirection, length);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x())
                && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }

    @FunctionalInterface
    interface Raycast<T> {
        T cast(Vec3 origin, Vec3 unitDirection, double distanceMeters);
    }
}
