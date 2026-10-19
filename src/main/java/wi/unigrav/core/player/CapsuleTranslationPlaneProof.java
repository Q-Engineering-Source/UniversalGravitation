package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.Optional;

/** Whole fixed-half-line translation proof for an exact world-axis separator. */
final class CapsuleTranslationPlaneProof {
    private CapsuleTranslationPlaneProof() { }

    /**
     * Certifies the actual capsule reconstructed by atPrefix for every fraction
     * in [0,1]. IEEE round-to-nearest is monotone: an outward delta component
     * makes RN(f*d), RN(C+RN(f*d)), and RN(C(f)+/-fixedHs) nondecreasing (or
     * nonincreasing). Thus the actual initial endpoint separator is preserved.
     * This is not a certificate for changing Hs, Q, radius, or another tube.
     */
    static Optional<Certificate> certify(CapsulePlacementMath.Translation tube,
            Aabb obstacle, StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work");
        work.charge(1);
        Objects.requireNonNull(tube, "tube");
        Objects.requireNonNull(obstacle, "obstacle");
        if (!(tube.start().placement().shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("axis translation proof requires actual Free capsule");
        }
        var capsule = tube.start().capsule();
        StoredDyadic radius = StoredDyadic.of(capsule.radius(), work);
        for (int axis = 0; axis < 3; axis++) {
            double delta = component(tube.delta(), axis);
            double first = component(capsule.centerline().start(), axis);
            double second = component(capsule.centerline().end(), axis);
            if (delta >= 0) {
                StoredDyadic plane = StoredDyadic.of(component(obstacle.max(), axis), work)
                        .add(radius, work);
                if (StoredDyadic.of(Math.min(first, second), work).compare(plane, work) >= 0) {
                    return Optional.of(new Certificate(tube, obstacle, axis, 1));
                }
            }
            if (delta <= 0) {
                StoredDyadic plane = StoredDyadic.of(component(obstacle.min(), axis), work)
                        .subtract(radius, work);
                if (StoredDyadic.of(Math.max(first, second), work).compare(plane, work) <= 0) {
                    return Optional.of(new Certificate(tube, obstacle, axis, -1));
                }
            }
        }
        return Optional.empty();
    }

    /** Geometry only; caller still validates all other boxes, owner and final fit. */
    static final class Certificate {
        private final CapsulePlacementMath.Translation tube;
        private final Aabb obstacle;
        private final int axis;
        private final int outwardSign;

        private Certificate(CapsulePlacementMath.Translation tube, Aabb obstacle,
                int axis, int outwardSign) {
            this.tube = tube;
            this.obstacle = obstacle;
            this.axis = axis;
            this.outwardSign = outwardSign;
        }

        boolean matches(CapsulePlacementMath.Translation expectedTube, Aabb expectedObstacle) {
            return tube == expectedTube && obstacle.equals(expectedObstacle);
        }
        int axis() { return axis; }
        int outwardSign() { return outwardSign; }
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) { case 0 -> vector.x(); case 1 -> vector.y(); default -> vector.z(); };
    }
}
