package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.UUID;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleArcCenterHalfSpaceTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void interiorImpossibleFaceHasConstantCost() {
        var work = new StoredDyadic.Work();
        var path = prepare(work).path();
        long before = work.units();
        assertFalse(path.provesAxisHalfSpace(1, 1, 90, .25, .75, work));
        long cost = work.units() - before;
        System.out.println("CENTER_INTERIOR_FACE units=" + cost);
        assertTrue(cost <= 64, "center witness must avoid recursion, units=" + cost);
    }
    @Test void bothSidesNegativeCenterAndHalfLineUseExactOrdering() {
        var work = new StoredDyadic.Work();
        var center = new Vec3d(-3.25, -8.125, -.75);
        var start = new PlayerBodyPlacement(center, new Rotation3d(1, 0, 0, 0),
                new PlayerBodyShape.FreeCapsule(SIZE));
        assertTrue(start.storedCapsuleGeometry().storedHalfLine().y() < 0);
        var target = new PlayerBodyPlacement(center, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(SIZE));
        var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, work), target,
                new Vec3d(0, 0, 1), work);
        for (int axis = 0; axis < 3; axis++) {
            double c = StoredRealizationLimits.component(center, axis);
            for (double[] interval : new double[][] {{0, 1}, {.125, .875}}) {
                long before = work.units();
                assertFalse(path.provesAxisHalfSpace(axis, 1, Math.nextUp(c), interval[0], interval[1], work));
                assertEquals(3, work.units() - before);
                before = work.units();
                assertFalse(path.provesAxisHalfSpace(axis, -1, Math.nextDown(c), interval[0], interval[1], work));
                assertEquals(3, work.units() - before);
            }
        }
        long before = work.units();
        assertFalse(path.provesAxisHalfSpace(0, 1, center.x(), .25, .75, work));
        assertTrue(work.units() - before > 3, "center equality must retain the original proof");
    }

    @Test void overrideEligibilityRejectsNonBracketingPointsWithoutChangingGeometry() {
        var work = new StoredDyadic.Work();
        var c = new Vec3d(-3.25, -8.125, -.75);
        assertTrue(CapsuleArcEnclosure.bracketsCenter(c,
                new Vec3d(-4, -9, -1), new Vec3d(-2, -7, 0), work));
        assertTrue(CapsuleArcEnclosure.bracketsCenter(c,
                new Vec3d(-2, -7, 0), new Vec3d(-4, -9, -1), work));
        assertTrue(CapsuleArcEnclosure.bracketsCenter(c, c, c, work));
        assertFalse(CapsuleArcEnclosure.bracketsCenter(c,
                new Vec3d(Math.nextUp(c.x()), -9, -1), new Vec3d(-2, -7, 0), work));
        assertFalse(CapsuleArcEnclosure.bracketsCenter(c,
                new Vec3d(-4, -9, -1), new Vec3d(Math.nextDown(c.x()), -7, 0), work));
        // Normal production geometry constructs endpoints as C +/- H; the predicate
        // rejects these synthetic ineligible override points without minting a Path.
    }

    @Test void identityValidationAndBudgetPrecedeTheFastDisproof() {
        var work = new StoredDyadic.Work();
        var path = prepare(work).path();
        assertThrows(IllegalArgumentException.class,
                () -> path.provesAxisHalfSpace(1, 1, 90, .25, .75, new StoredDyadic.Work()));
        assertFalse(path.provesAxisHalfSpace(3, 1, 90, .25, .75, work));
        assertFalse(path.provesAxisHalfSpace(1, 0, 90, .25, .75, work));
        assertFalse(path.provesAxisHalfSpace(1, 1, Double.NaN, .25, .75, work));
        assertFalse(path.provesAxisHalfSpace(1, 1, 90, .75, .25, work));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 1);
        var failure = assertThrows(NumericWorkExhaustedException.class,
                () -> path.provesAxisHalfSpace(1, 1, 90, .25, .75, work));
        assertNull(failure.getCause());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(f -> f.getMethodName().equals("provesAxisHalfSpace")));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work) {
        var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721 - .4, 1),
                new Vec3d(40.80000001192093, 89.19999998807907 - .4, 2.7999999523162833));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(40.5, 88.59999996423721 - .4, 1.8999999761581416),
                CardinalFrame.Z_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 995),
                new UUID(0, 996), 0, 2), 10960, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
}