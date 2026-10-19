package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Finite transcription regressions; the Path interval lemma supplies the full-domain proof. */
class CapsuleArcCommonCenterDomainTest {
    private static final PlayerBodyShape.Dimensions SIZE =
            new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
    private static final Vec3d AXIS = new Vec3d(1, 0, 0);
    private static final double[][] CHILDREN = {{0, 1}, {0, .5}, {.5, 1}, {.25, .75},
            {0, 0}, {1, 1}, {.499, .501}};

    @Test void fixedCenterUsesTheSameDomainIncludingActualPaddedChildren() {
        Vec3d c = new Vec3d(.25, 65.50000001192093, .75);
        var q0 = new Rotation3d(0, 0, .3826834323650898, .9238795325112867);
        var q1 = Rotation3d.identity();
        var work = new StoredDyadic.Work();
        var path = path(placement(c, q0, SIZE), placement(c, q1, SIZE), work);
        var fixed = path.allChildCaptureBounds(work);
        assertEquals(fixed, path.captureBoundsOverCenters(c, c, work));
        for (double[] child : CHILDREN) assertContains(fixed, path.enclose(child[0], child[1], work).bounds());
    }

    @Test void fullCorrectionCenterDomainCoversActualTranslatedPathsAndAllSixTargets() {
        Vec3d low = new Vec3d(-2.172792148576537, 62.82720785142346, -2.172792148576537);
        Vec3d high = new Vec3d(2.172792148576537, 67.17279214857654, 2.172792148576537);
        var q0 = new Rotation3d(.15, .2, .3, .7);
        Rotation3d[] targets = {Rotation3d.identity(), new Rotation3d(1, 0, 0, 0),
                new Rotation3d(0, 0, 1, 1), new Rotation3d(0, 0, -1, 1),
                new Rotation3d(1, 0, 0, 1), new Rotation3d(-1, 0, 0, 1)};
        for (Rotation3d q1 : targets) {
            var work = new StoredDyadic.Work();
            var source = placement(new Vec3d(0, 65, 0), q0, SIZE);
            Aabb domain = path(source, placement(source.center(), q1, SIZE), work)
                    .captureBoundsOverCenters(low, high, work);
            for (int mask = 0; mask < 8; mask++) {
                Vec3d c = new Vec3d((mask & 1) == 0 ? low.x() : high.x(),
                        (mask & 2) == 0 ? low.y() : high.y(), (mask & 4) == 0 ? low.z() : high.z());
                var childWork = new StoredDyadic.Work();
                var childPath = path(source.withTranslatedCenter(c), placement(c, q1, SIZE), childWork);
                for (double[] child : CHILDREN)
                    assertContains(domain, childPath.enclose(child[0], child[1], childWork).bounds());
            }
        }
    }

    @Test void binadeAndSubnormalQuaternionComponentsRetainOutwardBounds() {
        for (double scale : new double[] {0x1p-10, 1, 0x1p20}) {
            Vec3d low = new Vec3d(Math.nextDown(scale), -Math.nextUp(scale), 0);
            Vec3d high = new Vec3d(Math.nextUp(scale), -Math.nextDown(scale), Double.MIN_VALUE);
            var q0 = new Rotation3d(Double.MIN_VALUE, .2, .3, .9);
            var q1 = new Rotation3d(.4, -Double.MIN_VALUE, -.1, .8);
            var work = new StoredDyadic.Work();
            var source = placement(low, q0, SIZE);
            Aabb domain = path(source, placement(low, q1, SIZE), work)
                    .captureBoundsOverCenters(low, high, work);
            for (Vec3d c : new Vec3d[] {low, high}) {
                var childWork = new StoredDyadic.Work();
                var childPath = path(source.withTranslatedCenter(c), placement(c, q1, SIZE), childWork);
                for (double[] child : CHILDREN)
                    assertContains(domain, childPath.enclose(child[0], child[1], childWork).bounds());
            }
        }
    }

    @Test void nonnominalStoredFastEndpointIsIncludedWithoutNominalReconstruction() {
        var dimensions = new PlayerBodyShape.Dimensions(.5, 2, 1);
        Vec3d c = new Vec3d(.25, Math.nextUp(1.125), .75);
        var work = new StoredDyadic.Work();
        var q = Rotation3d.identity();
        var limits = StoredRealizationLimits.derive(new Vec3d(c.x(), 1.125, c.z()), q, dimensions, work);
        Vec3d hs = new Vec3d(0, c.y() - .375, 0);
        var relation = RealizationRelation.axisPlane(limits, 1, 1, .125, c, hs, work);
        var stored = StoredCapsuleGeometry.fromRecipe(c, q, dimensions, hs, relation);
        var source = PlayerBodyPlacement.fromAcceptedFreeGeometry(c, q, new PlayerBodyShape.FreeCapsule(dimensions),
                stored, stored.capsule().bounds(), new Vec3d(c.x(), stored.capsule().bounds().min().y(), c.z()));
        var target = placement(c, q, dimensions);
        assertFalse(StoredRealizationLimits.sameRaw(hs, target.storedCapsuleGeometry().storedHalfLine()));
        var path = path(source, target, work);
        Aabb domain = path.allChildCaptureBounds(work);
        for (double[] child : CHILDREN) assertContains(domain, path.enclose(child[0], child[1], work).bounds());
        assertEquals(source.capsule(), path.enclose(0, 0, work).reference());
        Vec3d low = new Vec3d(c.x() - .125, c.y() - .125, c.z() - .125);
        Vec3d high = new Vec3d(c.x() + .125, c.y() + .125, c.z() + .125);
        Aabb translatedDomain = path.captureBoundsOverCenters(low, high, work);
        for (Vec3d translated : new Vec3d[] {low, high}) {
            var translatedWork = new StoredDyadic.Work();
            var translatedPath = path(source.withTranslatedCenter(translated),
                    target.withTranslatedCenter(translated), translatedWork);
            for (double[] child : CHILDREN)
                assertContains(translatedDomain,
                        translatedPath.enclose(child[0], child[1], translatedWork).bounds());
        }
        var reverse = path(target, source, work);
        Aabb reverseDomain = reverse.allChildCaptureBounds(work);
        for (double[] child : CHILDREN)
            assertContains(reverseDomain, reverse.enclose(child[0], child[1], work).bounds());
        assertEquals(source.capsule(), reverse.enclose(1, 1, work).reference());
    }

    @Test void exactInvariantsKeepZeroPaddingAndWorkIdentityAndExhaustionRemainStrict() {
        Vec3d c = new Vec3d(.25, 1.125, .75);
        var work = new StoredDyadic.Work();
        var path = path(placement(c, Rotation3d.identity(), SIZE),
                placement(c, new Rotation3d(0, .8, 0, .6), SIZE), work);
        assertEquals(path.enclose(0, 1, work).bounds(), path.allChildCaptureBounds(work));
        var sphereSize = new PlayerBodyShape.Dimensions(.5, .5, .25);
        var sphereWork = new StoredDyadic.Work();
        var sphere = path(placement(c, new Rotation3d(.2, .3, .4, .5), sphereSize),
                placement(c, new Rotation3d(-.4, .2, .6, .3), sphereSize), sphereWork);
        assertEquals(sphere.enclose(0, 1, sphereWork).bounds(), sphere.allChildCaptureBounds(sphereWork));
        var foreign = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> path.allChildCaptureBounds(foreign));
        assertEquals(0, foreign.units(), "foreign work cannot replace or refund the path work");
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        assertThrows(NumericWorkExhaustedException.class, () -> path.allChildCaptureBounds(work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    private static CapsuleArcEnclosure.Path path(PlayerBodyPlacement source, PlayerBodyPlacement target,
            StoredDyadic.Work work) {
        return CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, AXIS, work);
    }
    private static PlayerBodyPlacement placement(Vec3d c, Rotation3d q, PlayerBodyShape.Dimensions dimensions) {
        return new PlayerBodyPlacement(c, q, new PlayerBodyShape.FreeCapsule(dimensions));
    }
    private static void assertContains(Aabb outer, Aabb inner) {
        assertTrue(outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z(),
                () -> "all-child domain " + outer + " excludes actual padded child " + inner);
    }
}
