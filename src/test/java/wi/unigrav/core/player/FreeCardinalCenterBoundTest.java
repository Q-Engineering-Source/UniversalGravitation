package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreeCardinalCenterBoundTest {
    @Test void proneBellyAnchorAndNativeCenterCorrectionUseTheSameSupportPlane() {
        var dimensions = new PlayerBodyShape.Dimensions(.5, 2, 1.75);
        var center = new Vec3d(16, 4, -.0625);
        var q = CardinalFrame.Z_POS.attitude();
        for (var frame : CardinalFrame.values()) {
            var work = new StoredDyadic.Work();
            var shape = new PlayerBodyShape.FreeCapsule(dimensions,
                    new PlayerBodyShape.CollisionProfile(.5, 2), java.util.Optional.of(
                        new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center, center, q)),
                    java.util.Optional.of(new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.CRAWLING, frame)));
            var start = new PlayerBodyPlacement(center, q, shape);
            var bound = FreeCardinalCenterBound.prepare(start, frame, dimensions, .125, work);
            for (var offset : new Vec3d[] {new Vec3d(0, 0, 0), new Vec3d(.125, 0, 0),
                    new Vec3d(0, -.125, 0), new Vec3d(0, 0, .125)}) {
                var moved = start.withTranslatedCenter(center.add(offset));
                var foot = PlayerBodyPlacement.proneSupportFoot(moved, frame, work);
                var target = PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(moved, frame, dimensions, work);
                assertEquals(foot, target.physicalFeet().orElseThrow());
                assertTrue(CapsulePlacementMath.monotoneCenterTravelUpper(moved.center(), target.center(), work)
                        <= bound.correctionUpper(), () -> frame + " -> " + bound);
            }
        }
    }
    @Test void boundContainsActualDesiredFootRecipeForAllSignedFramesAndTranslatedStoredGeometry() {
        var dimensions = new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
        for (CardinalFrame sourceFrame : CardinalFrame.values()) {
            for (CardinalFrame targetFrame : CardinalFrame.values()) {
                for (double coordinate : new double[] {0, .1, -16, 64, Math.nextDown(128)}) {
                    var work = new StoredDyadic.Work();
                    var source = new PlayerBodyPlacement(new Vec3d(coordinate, 65.9, -.1),
                            sourceFrame.attitude(), new PlayerBodyShape.FreeCapsule(dimensions), work);
                    double allowance = .125;
                    var bound = FreeCardinalCenterBound.prepare(source, targetFrame, dimensions, allowance, work);
                    for (Vec3d offset : new Vec3d[] {new Vec3d(0, 0, 0), new Vec3d(.03, -.04, .02),
                            new Vec3d(-.03, .04, -.02)}) {
                        var endpoint = source.withTranslatedCenter(source.center().add(offset));
                        assertSame(source.storedCapsuleGeometry().relation(), endpoint.storedCapsuleGeometry().relation());
                        assertEquals(source.storedCapsuleGeometry().storedHalfLine(), endpoint.storedCapsuleGeometry().storedHalfLine());
                        assertTrue(bound.containsMovementEnd(endpoint.center()));
                        PlayerBodyPlacement target;
                        try {
                            target = PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(endpoint, targetFrame, dimensions, work);
                        } catch (RuntimeException failure) {
                            throw new AssertionError("actual desired-foot recipe rejected tuple: sourceFrame=" + sourceFrame
                                    + "; targetFrame=" + targetFrame + "; coordinate=" + coordinate + "; offset=" + offset
                                    + "; sourceCenter=" + source.center() + "; endpointCenter=" + endpoint.center()
                                    + "; actualStoredHalfLine=" + endpoint.storedCapsuleGeometry().storedHalfLine()
                                    + "; actualFoot=" + endpoint.freePhysicalFoot() + "; nativeDimensions=" + dimensions,
                                    failure);
                        }
                        double actual = CapsulePlacementMath.monotoneCenterTravelUpper(endpoint.center(), target.center(), work);
                        assertTrue(actual <= bound.correctionUpper(),
                                () -> sourceFrame + " -> " + targetFrame + " actual=" + actual + " bound=" + bound);
                    }
                }
            }
        }
    }

    @Test void nonAxisStoredRecipeAndDifferentNativeDimensionsCoverTheReachableIntervalEdges() {
        var sourceDimensions = new PlayerBodyShape.Dimensions(.5,2,1.75);
        var targetDimensions = new PlayerBodyShape.Dimensions((double).6F,(double)1.8F,(double)1.62F);
        for(CardinalFrame frame:CardinalFrame.values()) {
            var work = new StoredDyadic.Work();
            var source = new PlayerBodyPlacement(new Vec3d(Math.nextDown(16),-.0625,.03125),
                    new Rotation3d(.1,.2,.3,.9),
                    new PlayerBodyShape.FreeCapsule(sourceDimensions),work);
            double allowance=.125;
            var bound=FreeCardinalCenterBound.prepare(source,frame,targetDimensions,allowance,work);
            for(Vec3d offset:new Vec3d[]{new Vec3d(allowance,0,0),new Vec3d(-allowance,0,0),
                    new Vec3d(0,allowance,0),new Vec3d(0,-allowance,0),
                    new Vec3d(0,0,allowance),new Vec3d(0,0,-allowance)}) {
                var endpoint=source.withTranslatedCenter(source.center().add(offset));
                assertTrue(bound.containsMovementEnd(endpoint.center()));
                var target=PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(endpoint,frame,targetDimensions,work);
                var actual=CapsulePlacementMath.monotoneCenterTravelUpper(endpoint.center(),target.center(),work);
                assertTrue(actual<=bound.correctionUpper(),()->frame+" actual="+actual+" bound="+bound);
            }
        }
    }

    @Test void crossBinadeNativeBoundsStayInsideTheExactFourCandidateRecipe() {
        var work = new StoredDyadic.Work();
        var dimensions = new PlayerBodyShape.Dimensions((double).6F,(double)1.8F,(double)1.62F);
        var source = new PlayerBodyPlacement(new Vec3d(-16,65.9,-.1),CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(dimensions),work);
        var endpoint = source.withTranslatedCenter(source.center().add(new Vec3d(.03,-.04,.02)));
        var foot = endpoint.freePhysicalFoot();
        var target = PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(endpoint,CardinalFrame.Y_POS,dimensions,work);
        assertEquals(foot.x(),target.center().x(),"the existing closest candidate 2 preserves the actual desired foot");
        assertEquals(dimensions,target.shape().dimensions(),"world rounding never changes the native profile");
        var anchor = StoredDyadic.of(foot.x(),work);
        var radius = StoredDyadic.of(dimensions.radius(),work);
        double lower = anchor.subtract(radius,work).roundDown(work);
        double upper = anchor.add(radius,work).roundUp(work);
        assertTrue(target.bounds().min().x()<=lower && target.bounds().max().x()>=upper);
        var exactExtent = StoredDyadic.of(target.bounds().max().x(),work)
                .subtract(StoredDyadic.of(target.bounds().min().x(),work),work);
        var maximum = StoredDyadic.of(Math.nextUp(upper),work)
                .subtract(StoredDyadic.of(Math.nextDown(lower),work),work);
        assertTrue(exactExtent.compare(StoredDyadic.of(dimensions.width(),work),work)>=0);
        assertTrue(exactExtent.compare(maximum,work)<=0);
        assertTrue(target.bounds().max().x()-target.bounds().min().x()>Math.nextUp(Math.nextUp(dimensions.width())),
                "this actual ordinary coordinate tuple reproduces the formerly rejected width-ULP assumption");
    }

    @Test void alignedRecipeKeepsSignedCancellationInsteadOfChargingTheBodyHeight() {
        var dimensions = new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
        for (CardinalFrame frame : CardinalFrame.values()) {
            var work = new StoredDyadic.Work();
            var source = new PlayerBodyPlacement(new Vec3d(8.5, 65.9, 8.5), frame.attitude(),
                    new PlayerBodyShape.FreeCapsule(dimensions), work);
            var bound = FreeCardinalCenterBound.prepare(source, frame, dimensions, .08, work);
            assertTrue(bound.correctionUpper() < 1e-10, () -> frame + " " + bound);
            assertTrue(work.units() > 0);
        }
    }

    @Test void finiteRecipeAroundZeroIncludesCancellationAndTinyStoredCenters() {
        var dimensions=new PlayerBodyShape.Dimensions(.5,2,1.75);
        for(double coordinate:new double[]{0,Double.MIN_NORMAL,-Double.MIN_NORMAL}) {
            var work=new StoredDyadic.Work();
            var source=new PlayerBodyPlacement(new Vec3d(coordinate,coordinate,coordinate),
                    CardinalFrame.Y_POS.attitude(),new PlayerBodyShape.FreeCapsule(dimensions),work);
            var bound=FreeCardinalCenterBound.prepare(source,CardinalFrame.Y_POS,dimensions,0,work);
            var target=PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(source,CardinalFrame.Y_POS,dimensions,work);
            assertTrue(CapsulePlacementMath.monotoneCenterTravelUpper(source.center(),target.center(),work)<=bound.correctionUpper());
        }
    }

    @Test void invalidAllowanceAndExhaustedOwnerWorkFailWithoutAnotherNumericBudget() {
        var source = new PlayerBodyPlacement(new Vec3d(8.5, 65.9, 8.5), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5, 2, 1.75)));
        assertThrows(IllegalArgumentException.class, () -> FreeCardinalCenterBound.prepare(source,
                CardinalFrame.Y_POS, source.shape().dimensions(), Double.POSITIVE_INFINITY, new StoredDyadic.Work()));
        var work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        assertThrows(NumericWorkExhaustedException.class, () -> FreeCardinalCenterBound.prepare(source,
                CardinalFrame.Y_POS, source.shape().dimensions(), .08, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }
}
