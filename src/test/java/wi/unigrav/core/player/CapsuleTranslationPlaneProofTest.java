package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleTranslationPlaneProofTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
    private static final Aabb FLOOR = box(-4, -1, -4, 4, 0, 4);

    @Test
    void exactFloorContactAllowsTangentialAndOutwardMovement() {
        for (Vec3d delta : new Vec3d[] {new Vec3d(0.5, 0, 0.25), new Vec3d(0.5, 0.25, 0)}) {
            var work = new StoredDyadic.Work();
            var tube = tube(new Vec3d(0, 1, 0), delta, work);
            var proof = CapsuleTranslationPlaneProof.certify(tube, FLOOR, work).orElseThrow();
            assertEquals(1, proof.axis());
            assertEquals(1, proof.outwardSign());
            assertTrue(proof.matches(tube, FLOOR));
            for (double fraction : new double[] {0, Double.MIN_VALUE, 0.125, 0.5, Math.nextDown(1), 1}) {
                var actual = CapsulePlacementMath.atPrefix(tube, fraction, work).capsule();
                assertTrue(Math.min(actual.centerline().start().y(), actual.centerline().end().y())
                        >= FLOOR.max().y() + actual.radius());
            }
        }
    }

    @Test
    void inwardAndInitiallyPenetratingPathsAreNotCertified() {
        var work = new StoredDyadic.Work();
        assertTrue(CapsuleTranslationPlaneProof.certify(tube(new Vec3d(0, 1, 0),
                new Vec3d(0, -0.125, 0), work), FLOOR, work).isEmpty());
        assertTrue(CapsuleTranslationPlaneProof.certify(tube(new Vec3d(0, Math.nextDown(1.0), 0),
                new Vec3d(0.5, 0, 0), work), FLOOR, work).isEmpty());
    }

    @Test
    void reversedPlaneAndAxisDirectionsAreSupported() {
        var work = new StoredDyadic.Work();
        var ceiling = box(-4, 2, -4, 4, 3, 4);
        var tube = tube(new Vec3d(0, 1, 0), new Vec3d(0.5, -0.125, 0), work);
        var proof = CapsuleTranslationPlaneProof.certify(tube, ceiling, work).orElseThrow();
        assertEquals(1, proof.axis());
        assertEquals(-1, proof.outwardSign());
        var wall = box(0.25, -4, -4, 1, 4, 4);
        var wallTube = tube(new Vec3d(0, 1, 0), new Vec3d(-0.125, 0.5, 0), work);
        assertEquals(0, CapsuleTranslationPlaneProof.certify(wallTube, wall, work).orElseThrow().axis());
    }

    @Test
    void unrepresentableExactPlaneSumIsNotRoundedIntoPermission() {
        var work = new StoredDyadic.Work();
        // Exact box.maxY + r is greater than the rounded endpoint 1/4.
        var obstacle = box(-4, -1, -4, 4, Double.MIN_VALUE, 4);
        assertTrue(CapsuleTranslationPlaneProof.certify(tube(new Vec3d(0, 1, 0),
                new Vec3d(0.5, 0, 0), work), obstacle, work).isEmpty());
    }

    @Test
    void certificatesAreBoundToTheExactTubeAndBoxAndWorkIsShared() {
        var work = new StoredDyadic.Work();
        var tube = tube(new Vec3d(0, 1, 0), new Vec3d(0.5, 0, 0), work);
        long before = work.units();
        var proof = CapsuleTranslationPlaneProof.certify(tube, FLOOR, work).orElseThrow();
        assertTrue(work.units() > before);
        assertFalse(proof.matches(tube(new Vec3d(0, 1, 0), new Vec3d(0.5, 0, 0), work), FLOOR));
        assertFalse(proof.matches(tube, box(-4, -1, -4, 4, 0.125, 4)));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        assertThrows(NumericWorkExhaustedException.class,
                () -> CapsuleTranslationPlaneProof.certify(tube, FLOOR, work));
    }

    private static CapsulePlacementMath.Translation tube(Vec3d center, Vec3d delta, StoredDyadic.Work work) {
        var placement = new PlayerBodyPlacement(center, Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(SIZE));
        return CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(placement, work), delta, work);
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
}
