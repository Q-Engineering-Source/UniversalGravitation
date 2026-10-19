package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual translation recipe regressions; the offset induction supplies the universal bound. */
class CapsuleProxyTelescopingTest {
    private static final PlayerBodyShape.Dimensions SIZE =
            new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);

    @Test void observed058ProfileNoLongerRepeatsCenterWidthAndZeroAxesKeepOriginalBits() {
        var source = placement(new Vec3d(8.5, 70.89999997615814, 24.5), Rotation3d.identity());
        var low = source.center();
        var high = new Vec3d(8.5, low.y(), 40.42000000000001);
        var delta = new Vec3d(0, 0, 15.92);
        var work = new StoredDyadic.Work();
        var proxy = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, delta, 4, work);
        assertTrue(proxy.min().z() > 24 && proxy.max().z() < 41, proxy::toString);
        assertEquals(Double.doubleToRawLongBits(source.bounds().min().x()), Double.doubleToRawLongBits(proxy.min().x()));
        assertEquals(Double.doubleToRawLongBits(source.bounds().max().x()), Double.doubleToRawLongBits(proxy.max().x()));
        assertEquals(Double.doubleToRawLongBits(source.bounds().min().y()), Double.doubleToRawLongBits(proxy.min().y()));
        assertEquals(Double.doubleToRawLongBits(source.bounds().max().y()), Double.doubleToRawLongBits(proxy.max().y()));
        PlayerBodyPlacement actual = source;
        for (double z : new double[] {28.48, 32.46, 36.44, 40.42}) {
            actual = actual.withTranslatedCenter(new Vec3d(low.x(), low.y(), z));
            assertContains(proxy, actual.bounds());
            assertContains(proxy, actual.capsule().bounds());
        }
    }

    @Test void allSegmentCountsAndIntermediateUnionInjectionsAreCoveredFromActualNonNominalProxy() {
        var original = placement(new Vec3d(.25, 65.125, -.75), new Rotation3d(.2, .3, .4, .5));
        var b = original.bounds();
        var margin = new Aabb(new Vec3d(Math.nextDown(b.min().x()), Math.nextDown(b.min().y()), Math.nextDown(b.min().z())),
                new Vec3d(Math.nextUp(b.max().x()), Math.nextUp(b.max().y()), Math.nextUp(b.max().z())));
        var source = PlayerBodyPlacement.fromAcceptedFreeGeometry(original.center(), original.attitude(),
                new PlayerBodyShape.FreeCapsule(SIZE), original.storedCapsuleGeometry(), margin,
                new Vec3d(original.transport().x(), margin.min().y(), original.transport().z()));
        Vec3d low = new Vec3d(-2, 63, -3), high = new Vec3d(2, 67, 3);
        Vec3d[] sequence = {high, low, new Vec3d(0, 65, 0), source.center()};
        for (int count = 0; count <= CapsuleMovementResolver.MAX_SEGMENTS; count++) {
            var work = new StoredDyadic.Work();
            var capture = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, count, work);
            if (count == 0) assertSame(source.bounds(), capture);
            var actual = source;
            assertContains(capture, actual.bounds());
            for (int i = 0; i < count; i++) {
                actual = actual.withTranslatedCenter(sequence[i]);
                assertTrue(StoredRealizationLimits.sameRaw(source.storedCapsuleGeometry().storedHalfLine(),
                        actual.storedCapsuleGeometry().storedHalfLine()));
                assertContains(capture, actual.bounds());
                assertContains(capture, actual.capsule().bounds());
            }
        }
    }

    @Test void binadeSubnormalAndCrossZeroCentersRetainActualRnChains() {
        Vec3d[][] domains = {
                {new Vec3d(Math.nextDown(64.0), Math.nextDown(128.0), Math.nextDown(1.0)),
                        new Vec3d(Math.nextUp(64.0), Math.nextUp(128.0), Math.nextUp(1.0))},
                {new Vec3d(-Double.MIN_VALUE, -Double.MIN_NORMAL, -1),
                        new Vec3d(Double.MIN_VALUE, Double.MIN_NORMAL, 1)}};
        for (Vec3d[] domain : domains) {
            Vec3d low = domain[0], high = domain[1];
            var source = placement(low, new Rotation3d(.15, .2, .3, .7));
            var work = new StoredDyadic.Work();
            var capture = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, 4, work);
            var actual = source;
            for (Vec3d c : new Vec3d[] {high, low, high, low}) {
                actual = actual.withTranslatedCenter(c);
                assertContains(capture, actual.bounds());
                assertContains(capture, actual.capsule().bounds());
            }
        }
    }

    @Test void signedMultiAxisDeletionAndFractionsKeepEveryRealProxyAndCapsule() {
        var source = placement(new Vec3d(8.5, 70.5, 24.5), Rotation3d.identity());
        Vec3d low = new Vec3d(6.5, 70.5, 24.5), high = new Vec3d(8.5, 72.5, 28.5);
        var work = new StoredDyadic.Work();
        var capture = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, new Vec3d(-2, 2, 4), 4, work);
        var actual = source;
        for (Vec3d c : new Vec3d[] {new Vec3d(8, 71, 25.5), new Vec3d(7, 71, 27.5),
                new Vec3d(7, 72, 28.5), new Vec3d(7, 72.5, 28.5)}) {
            actual = actual.withTranslatedCenter(c);
            assertContains(capture, actual.bounds());
            assertContains(capture, actual.capsule().bounds());
        }
    }

    @Test void bootstrapIsNotAnEarlyCoordinateGateAndRealDomainAndBudgetFailuresStayExplicit() {
        var source = placement(new Vec3d(0, 65, 0), Rotation3d.identity());
        Vec3d low = new Vec3d(-30000000, 65, 0), high = new Vec3d(30000000, 65, 0);
        var work = new StoredDyadic.Work();
        var capture = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, 4, work);
        assertTrue(capture.min().x() > -0x1p25 && capture.max().x() < 0x1p25);
        assertContains(capture, source.withTranslatedCenter(low).bounds());
        assertContains(capture, source.withTranslatedCenter(high).bounds());
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translatedProxyCaptureBounds(source,
                new Vec3d(1, 65, 0), new Vec3d(2, 65, 0), 4, work));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, 5, work));
        var exhausted = new StoredDyadic.Work();
        exhausted.charge(StoredDyadic.MAX_WORK_UNITS);
        assertThrows(NumericWorkExhaustedException.class,
                () -> CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high, 4, exhausted));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, exhausted.units());
    }

    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d attitude) {
        return new PlayerBodyPlacement(center, attitude, new PlayerBodyShape.FreeCapsule(SIZE));
    }
    private static void assertContains(Aabb outer, Aabb actual) {
        assertTrue(outer.min().x() <= actual.min().x() && outer.min().y() <= actual.min().y()
                && outer.min().z() <= actual.min().z() && outer.max().x() >= actual.max().x()
                && outer.max().y() >= actual.max().y() && outer.max().z() >= actual.max().z(),
                () -> "capture " + outer + " excludes actual translated/union proxy " + actual);
    }
}
