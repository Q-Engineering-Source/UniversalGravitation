package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalYToXWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void actualTwoObstacleSeparatingPlanesHaveWholePathBounds() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.X_POS);
        var wall = new Aabb(new Vec3d(7, 65, 8), new Vec3d(8, 66, 9));
        var floor = new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 65, 9));
        boolean wallProof = prepared.path().provesAxisHalfSpace(0, 1, wall.max().x(), 0, 1, work);
        boolean floorProof = prepared.path().provesAxisHalfSpace(1, 1, floor.max().y(), 0, 1, work);
        assertTrue(prepared.path().yToXWallWorkDelta() > 0);
        assertAll(() -> assertTrue(wallProof, "whole actual capsule X>=8 separates the wall"),
                () -> assertTrue(floorProof, "whole actual capsule Y>=65 separates the floor"));
    }

    @Test void exactTouchClosedIntervalsAndPreparationWorkIncludeTheNewProof() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.X_POS);
        var center = prepared.recipe().start().placement().center();
        double product = Math.nextUp(1.0) * prepared.recipe().halfLength();
        double xNegative = center.x() - product;
        double yNegative = center.y() - prepared.recipe().halfLength();
        var radius = StoredDyadic.of(prepared.recipe().start().capsule().radius(), work);
        assertEquals(0, StoredDyadic.of(xNegative, work).subtract(radius, work).compare(StoredDyadic.of(8, work), work));
        assertEquals(0, StoredDyadic.of(yNegative, work).subtract(radius, work).compare(StoredDyadic.of(65, work), work));
        for (double[] interval : new double[][] {{0, 0}, {1, 1}, {0, 1}, {.25, .75}}) {
            assertTrue(prepared.path().provesAxisHalfSpace(0, 1, 8, interval[0], interval[1], work));
            assertTrue(prepared.path().provesAxisHalfSpace(1, 1, 65, interval[0], interval[1], work));
        }
        long before = work.units();
        var path = CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Y_POS.up(), work);
        assertEquals(work.units() - before, path.numericWorkDelta());
        assertTrue(path.yToXWallWorkDelta() > 0);
        System.out.println("Y_TO_X fixedPreparation=" + path.numericWorkDelta() + " adapter=" + path.yToXWallWorkDelta());
    }

    @Test void intrudingPlanesWrongAxisAndForeignWorkNeverReceiveTheBound() {
        var work = new StoredDyadic.Work();
        var path = prepare(work, CardinalFrame.X_POS).path();
        assertFalse(path.provesAxisHalfSpace(0, 1, Math.nextUp(8.0), 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(1, 1, Math.nextUp(65.0), 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(0, -1, 8, 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(2, 1, 9, 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(0, 1, 8, -.1, 1, work));
        assertThrows(IllegalArgumentException.class,
                () -> path.provesAxisHalfSpace(0, 1, 8, 0, 1, new StoredDyadic.Work()));
    }

    @Test void initialMintAndReceiptAreBoundToActualPathRecipeAndSignedComponents() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.X_POS);
        var q0 = components(CardinalFrame.Y_POS);
        var q1 = components(CardinalFrame.X_POS);
        var receipt = CardinalYToXWallLemma.prepare(prepared.path(), prepared.recipe(), q0, q1, work);
        assertNotNull(receipt);
        var wrong = prepare(work, CardinalFrame.Z_POS);
        assertEquals(0, wrong.path().yToXWallWorkDelta());
        assertNull(CardinalYToXWallLemma.prepare(wrong.path(), prepared.recipe(), q0, q1, work));
        assertFalse(receipt.proves(wrong.path(), prepared.recipe(), 0, 1, 8, 0, 1, work));
        assertFalse(receipt.proves(prepared.path(), wrong.recipe(), 0, 1, 8, 0, 1, work));
        assertFalse(receipt.proves(prepared.path(), prepared.recipe(), 0, 1, 8, 0, 1, new StoredDyadic.Work()));
        q1[2] = Math.nextDown(q1[2]);
        assertNull(CardinalYToXWallLemma.prepare(prepared.path(), prepared.recipe(), q0, q1, work));
    }

    @Test void proofInternalExhaustionPreservesTheOriginalNumericCause() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.X_POS);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var original = assertThrows(NumericWorkExhaustedException.class,
                () -> CardinalYToXWallLemma.prepare(prepared.path(), prepared.recipe(),
                        components(CardinalFrame.Y_POS), components(CardinalFrame.X_POS), work));
        assertNull(original.getCause());
        assertTrue(Arrays.stream(original.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(CardinalYToXWallLemma.class.getName())));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void bytecodeReductionRegressionIncludesTheKnownCapWitness() {
        // These examples check the operation mapping. The whole-domain proof is the
        // frozen theorem plus the constructor/basis mapping, not this finite list.
        for (double a : new double[] {0, Double.MIN_VALUE, .5, 1 - 0x1p-24,
                Double.longBitsToDouble(0x3feffffff56ac061L), 1}) {
            double p = a * a;
            double b = 1 / Math.sqrt(p + 1);
            double x = a * b;
            double reduced = b * (2 * x);
            var actual = new Rotation3d(0, 0, -a, 1).basisY();
            assertEquals(Double.doubleToRawLongBits(reduced), Double.doubleToRawLongBits(actual.x()));
            assertTrue(actual.x() >= 0 && actual.x() <= Math.nextUp(1.0));
            assertTrue(actual.y() >= -1 && actual.y() <= 1);
            assertEquals(0, actual.z());
        }
    }

    private static double[] components(CardinalFrame frame) {
        var q = frame.attitude();
        return new double[] {q.x(), q.y(), q.z(), q.w()};
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work, CardinalFrame target) {
        var bounds = new Aabb(new Vec3d(8.599999964237213, 65, 8.199999988079071),
                new Vec3d(9.199999988079071, 66.79999995231628, 8.800000011920929));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, new Vec3d(8.899999976158142, 65, 8.5),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 895),
                new UUID(0, 896), 0, 2), 9960, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), target, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
}