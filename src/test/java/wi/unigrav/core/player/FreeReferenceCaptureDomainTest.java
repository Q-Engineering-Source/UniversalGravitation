package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreeReferenceCaptureDomainTest {
    @Test void nonIdentityArcChildrenRemainInsideFixedCenterDomainUnderOriginalScanAndWorkLimits() {
        var dimensions = new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
        for (CardinalFrame targetFrame : new CardinalFrame[] {CardinalFrame.X_POS, CardinalFrame.Y_NEG}) {
            var work = new StoredDyadic.Work();
            var source = new PlayerBodyPlacement(new Vec3d(Math.nextDown(-16), 65.9, .1),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.FreeCapsule(dimensions), work);
            var target = new PlayerBodyPlacement(source.center(), targetFrame.attitude(), source.shape(), work);
            var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, source.bodyUp(), work);
            Vec3d low = source.center().add(new Vec3d(-.125, -.125, -.125));
            Vec3d high = source.center().add(new Vec3d(.125, .125, .125));
            Aabb fixed = path.captureBoundsOverCenters(low, high, work);
            assertDoesNotThrow(() -> CollisionQueryFootprint.plan(fixed, 2, FreeReferenceModePrepared.SCAN));
            for (Vec3d center : new Vec3d[] {low, high, source.center()}) {
                var actualSource = source.withTranslatedCenter(center);
                var actualTarget = new PlayerBodyPlacement(center, targetFrame.attitude(), source.shape(), work);
                var actual = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(actualSource, work), actualTarget,
                        actualSource.bodyUp(), work);
                for (double[] interval : new double[][] {{0, 1}, {0, .125}, {.25, .5}, {.75, 1}})
                    assertContains(fixed, actual.enclose(interval[0], interval[1], work).bounds());
            }
        }
    }

    @Test void translatedProxyDomainCoversFourActualRoundedSegmentsAcrossBinade() {
        var work = new StoredDyadic.Work();
        var dimensions = new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
        var source = new PlayerBodyPlacement(new Vec3d(Math.nextDown(-16), 65.9, .1),
                CardinalFrame.X_POS.attitude(), new PlayerBodyShape.FreeCapsule(dimensions), work);
        Vec3d low = source.center().add(new Vec3d(-.125, -.125, -.125));
        Vec3d high = source.center().add(new Vec3d(.125, .125, .125));
        Aabb fixed = CapsulePlacementMath.translatedProxyCaptureBounds(source, low, high,
                CapsuleMovementResolver.MAX_SEGMENTS, work);
        var current = source;
        for (Vec3d offset : new Vec3d[] {new Vec3d(.03, -.02, 0), new Vec3d(.03, -.02, .02),
                new Vec3d(.05, -.02, .02), new Vec3d(.05, -.04, .02)}) {
            current = current.withTranslatedCenter(source.center().add(offset));
            assertContains(fixed, current.bounds());
        }
        assertDoesNotThrow(() -> CollisionQueryFootprint.plan(fixed, 2, FreeReferenceModePrepared.SCAN));
    }

    @Test void signedAxisDomainPreservesZeroAxesAndCoversRoundedPrefixAndScaledResidualTubes() {
        var dimensions = new PlayerBodyShape.Dimensions((double).6F,(double)1.8F,(double)1.62F);
        for (double coordinate : new double[] {Math.nextDown(-16),Math.nextDown(16),-Double.MIN_NORMAL}) {
            for (double amount : new double[] {-10,2.95}) {
                var work = new StoredDyadic.Work();
                var source = new PlayerBodyPlacement(new Vec3d(coordinate,65.9,.1),CardinalFrame.Y_POS.attitude(),
                        new PlayerBodyShape.FreeCapsule(dimensions),work);
                var delta = new Vec3d(amount,0,0);
                double allowance = CapsulePlacementMath.chargedTranslationTube(CapsulePlacementMath.pose(source,work),delta,work).requestedDisplacementUpper();
                double lo = amount < 0 ? StoredDyadic.of(coordinate,work).subtract(StoredDyadic.of(allowance,work),work).roundDown(work) : coordinate;
                double hi = amount > 0 ? StoredDyadic.of(coordinate,work).add(StoredDyadic.of(allowance,work),work).roundUp(work) : coordinate;
                var low = new Vec3d(lo,source.center().y(),source.center().z());
                var high = new Vec3d(hi,source.center().y(),source.center().z());
                var fixed = CapsulePlacementMath.translationCaptureBoundsOverCenters(source,low,high,delta,work);
                var proxy = CapsulePlacementMath.translatedProxyCaptureBounds(source,low,high,delta,CapsuleMovementResolver.MAX_SEGMENTS,work);
                assertEquals(source.bounds().min().y(),proxy.min().y());
                assertEquals(source.bounds().max().z(),proxy.max().z());
                assertDoesNotThrow(() -> CollisionQueryFootprint.plan(proxy,2,FreeReferenceModePrepared.SCAN));
                for (double fraction : new double[] {0,.25,.5,1}) {
                    var current = source.withTranslatedCenter(source.center().add(delta.multiply(fraction)));
                    assertContains(proxy,current.bounds());
                    for (double scale : new double[] {0,.25,1}) {
                        var residual = delta.multiply((1-fraction)*scale);
                        var tube = CapsulePlacementMath.chargedTranslationTube(CapsulePlacementMath.pose(current,work),residual,work);
                        assertContains(fixed,tube.sweptBounds());
                    }
                }
            }
        }
    }

    private static void assertContains(Aabb outer, Aabb inner) {
        assertTrue(outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z(),
                () -> "fixed=" + outer + "; actual query=" + inner);
    }
}
