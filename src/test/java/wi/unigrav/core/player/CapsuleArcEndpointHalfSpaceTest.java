package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleArcEndpointHalfSpaceTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void oldClearCornerImpossibleFaceIsDisprovedWithEndpointCost() {
        var work = new StoredDyadic.Work();
        var path = prepare(work).path();
        // The old z1 iron-bars corner occupies Y in [89,90]. The source already
        // violates Y>=90, so that one separating face cannot cover this path.
        // This is not a collision verdict or the whole-corner-clear assertion.
        long before = work.units();
        assertFalse(path.provesAxisHalfSpace(1, 1, 90, 0, 1, work));
        long cost = work.units() - before;
        System.out.println("OLD_Z1_IMPOSSIBLE_FACE units=" + cost);
        assertTrue(cost <= 64, "actual endpoint witness must avoid recursive work, units=" + cost);
    }

    @Test void bothSidesAndBothActualOverridesDisproveOnlyTheirContainingIntervals() {
        var work = new StoredDyadic.Work();
        var path = prepare(work).path();
        long before = work.units();
        assertFalse(path.provesAxisHalfSpace(2, 1, 1.5, 0, 1, work));
        assertTrue(work.units() - before <= 64);
        before = work.units();
        assertFalse(path.provesAxisHalfSpace(1, 1, 88, 0, 1, work));
        assertTrue(work.units() - before <= 64);
        before = work.units();
        assertFalse(path.provesAxisHalfSpace(0, -1, 40.7, 0, 1, work));
        assertTrue(work.units() - before <= 64);
        // The excluded endpoint must not disprove an actually safe subinterval.
        assertTrue(path.provesAxisHalfSpace(2, 1, 1.5, .99, 1, work));
        assertTrue(path.provesAxisHalfSpace(1, 1, 88, 0, .01, work));
        assertTrue(path.provesAxisHalfSpace(2, 1, 1, 0, 1, work), "existing exact Z touch proof remains valid");
    }

    @Test void anExactActualPointTouchIsNotClassifiedOutside() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work);
        var actual = prepared.recipe().start().placement().storedCapsuleGeometry();
        var radius = StoredDyadic.of(actual.radius(), work);
        var exactHigh = StoredDyadic.of(Math.max(actual.negativeEndpoint().x(), actual.positiveEndpoint().x()), work)
                .add(radius, work);
        double plane = exactHigh.roundNearest(work);
        assertEquals(0, exactHigh.compare(StoredDyadic.of(plane, work), work));
        assertTrue(prepared.path().provesAxisHalfSpace(0, -1, plane, 0, 0, work));
        assertFalse(prepared.path().provesAxisHalfSpace(0, -1, Math.nextDown(plane), 0, 0, work));
    }

    @Test void foreignWorkAndInternalNumericExhaustionRemainExplicitFailures() {
        var work = new StoredDyadic.Work();
        var path = prepare(work).path();
        assertThrows(IllegalArgumentException.class,
                () -> path.provesAxisHalfSpace(1, 1, 90, 0, 1, new StoredDyadic.Work()));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 4);
        var original = assertThrows(NumericWorkExhaustedException.class,
                () -> path.provesAxisHalfSpace(1, 1, 88.49999997615814, 0, 1, work));
        assertNull(original.getCause());
        assertTrue(Arrays.stream(original.getStackTrace()).anyMatch(frame ->
                frame.getMethodName().equals("actualEndpointWithinHalfSpace")));
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