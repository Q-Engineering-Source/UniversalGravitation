package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalSignedAxisWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
    private static final CardinalFrame[] LITERALS = {CardinalFrame.X_NEG, CardinalFrame.Z_POS, CardinalFrame.Z_NEG};

    @Test void threeActualTouchPlanesAndFloorHaveWholeClosedIntervalCertificates() {
        for (var target : LITERALS) {
            var work = new StoredDyadic.Work();
            var p = prepare(target, work);
            int axis = target == CardinalFrame.X_NEG ? 0 : 2;
            int side = target == CardinalFrame.Z_POS ? 1 : -1;
            var q0 = components(CardinalFrame.Y_POS);
            var q1 = components(target);
            var receipt = CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            assertNotNull(receipt);
            assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN, p.path().fixedDomainProof());
            var center = p.recipe().start().placement().center();
            double product = Math.nextUp(1.0) * p.recipe().halfLength();
            double v = StoredRealizationLimits.component(center, axis);
            double endpoint = side > 0 ? v - product : v + product;
            var radius = StoredDyadic.of(p.recipe().start().capsule().radius(), work);
            var exact = StoredDyadic.of(endpoint, work);
            exact = side > 0 ? exact.subtract(radius, work) : exact.add(radius, work);
            assertEquals(0, exact.compare(StoredDyadic.of(8, work), work));
            for (double[] interval : new double[][] {{0, 0}, {1, 1}, {0, 1}, {.125, .875}}) {
                assertTrue(p.path().provesAxisHalfSpace(axis, side, 8, interval[0], interval[1], work));
                assertTrue(p.path().provesAxisHalfSpace(1, 1, 65, interval[0], interval[1], work));
            }
            double intruding = side > 0 ? Math.nextUp(8.0) : Math.nextDown(8.0);
            assertFalse(receipt.proves(p.path(), p.recipe(), axis, side, intruding, 0, 1, work));
            assertFalse(p.path().provesAxisHalfSpace(axis, side, intruding, 0, 1, work));
            assertFalse(receipt.proves(p.path(), p.recipe(), 1, 1, Math.nextUp(65.0), 0, 1, work));
            long before = work.units();
            var again = CapsuleArcEnclosure.prepareFixed(p.recipe(), CardinalFrame.Y_POS.up(), work);
            assertEquals(work.units() - before, again.numericWorkDelta());
        }
    }

    @Test void firstMintCannotBorrowAnotherPathRecipeOrQuaternion() {
        var work = new StoredDyadic.Work();
        var p = prepare(CardinalFrame.X_NEG, work);
        var wrong = prepare(CardinalFrame.Z_POS, work);
        var q0 = components(CardinalFrame.Y_POS);
        var q1 = components(CardinalFrame.X_NEG);
        assertNull(CardinalSignedAxisWallLemma.prepare(wrong.path(), p.recipe(), q0, q1, work));
        var receipt = CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
        assertNotNull(receipt);
        assertFalse(receipt.proves(wrong.path(), p.recipe(), 0, -1, 8, 0, 1, work));
        assertFalse(receipt.proves(p.path(), wrong.recipe(), 0, -1, 8, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, -1, 8, 0, 1, new StoredDyadic.Work()));
        assertFalse(receipt.proves(p.path(), p.recipe(), 2, -1, 8, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 0, 8, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, -1, 8, -.1, 1, work));
        q1[2] = -q1[2];
        assertNull(CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), q0, q1, work));
    }

    @Test void proofBudgetFailureRemainsTheOriginalException() {
        var work = new StoredDyadic.Work();
        var p = prepare(CardinalFrame.Z_NEG, work);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var failure = assertThrows(NumericWorkExhaustedException.class, () ->
                CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), components(CardinalFrame.Y_POS), components(CardinalFrame.Z_NEG), work));
        assertNull(failure.getCause());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(f -> f.getClassName().equals(CardinalSignedAxisWallLemma.class.getName())));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void literalRuntimeReductionChecksIncludeSignedAndZeroBoundaries() {
        // Regression of the operation mapping only; the full-domain bound is the
        // frozen constructor theorem, not this finite list of stored parameters.
        for (var frame : LITERALS) for (double t : new double[] {0, Double.MIN_VALUE, .25, .5, Math.nextDown(1.0), 1}) {
            var q = frame.attitude();
            double s = 1 - t, p = t * q.w(), w = s + p;
            assertTrue(w > 0 && p <= w);
            double a = p / w, b = 1 / Math.sqrt(a * a + 1), x = a * b;
            double transverse = b * (2 * x), vertical = 1 - x * (2 * x);
            var actual = new Rotation3d(t * q.x(), t * q.y(), t * q.z(), w).basisY();
            double signed = frame == CardinalFrame.Z_POS ? transverse : -transverse;
            double component = frame == CardinalFrame.X_NEG ? actual.x() : actual.z();
            assertTrue(component == signed, "numeric equality includes canonical-zero reduction");
            assertTrue(actual.y() == vertical);
            assertTrue(Math.abs(component) <= Math.nextUp(1.0));
        }
    }

    private static CardinalRealizedArcRecipe.Prepared prepare(CardinalFrame target, StoredDyadic.Work work) {
        var center = target == CardinalFrame.X_NEG ? new Vec3d(7.100000023841858, 65.89999997615814, 8.5)
                : new Vec3d(8.5, 65.89999997615814, target == CardinalFrame.Z_POS ? 8.899999976158142 : 7.100000023841858);
        var placement = new PlayerBodyPlacement(center, CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 741), new UUID(0, 742), 0, 2),
                4096, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class, CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), target, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
    private static double[] components(CardinalFrame frame) {
        var q = frame.attitude(); return new double[] {q.x(), q.y(), q.z(), q.w()};
    }
}