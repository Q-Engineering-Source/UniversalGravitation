package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalTwoComponentWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void eligibleWholeWallProofIsBoundToRecipePathWorkPlaneAndClosedInterval() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.Y_POS);
        var path = prepared.path();
        assertTrue(path.specialWallWorkDelta() > 0);
        assertTrue(path.provesAxisHalfSpace(2, 1, 1, 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(2, 1, Math.nextUp(1.0), 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(2, -1, 1, 0, 1, work));
        assertFalse(path.provesAxisHalfSpace(2, 1, 1, -.1, 1, work));
        assertFalse(path.provesAxisHalfSpace(2, 1, 1, 0, Math.nextUp(1.0), work));
        assertThrows(IllegalArgumentException.class,
                () -> path.provesAxisHalfSpace(2, 1, 1, 0, 1, new StoredDyadic.Work()));
        long before = work.units();
        var reproved = CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Z_POS.up(), work);
        assertEquals(work.units() - before, reproved.numericWorkDelta());
        assertTrue(reproved.specialWallWorkDelta() > 0);
    }

    @Test void receiptCannotTransferToAnotherPathRecipeOrSelectedConstructorInput() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.Y_POS);
        var q0 = CardinalFrame.Z_POS.attitude();
        double[] first = {q0.x(), q0.y(), q0.z(), q0.w()};
        double[] last = {0, 0, 0, 1};
        var receipt = CardinalTwoComponentWallLemma.prepare(prepared.path(), prepared.recipe(), first, last, work);
        assertNotNull(receipt);
        assertTrue(receipt.proves(prepared.path(), prepared.recipe(), 2, 1, 1, 0, 1, work));
        var anotherPath = CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Z_POS.up(), work);
        assertFalse(receipt.proves(anotherPath, prepared.recipe(), 2, 1, 1, 0, 1, work));
        var unrelatedPath = prepare(work, CardinalFrame.X_POS).path();
        assertNull(CardinalTwoComponentWallLemma.prepare(unrelatedPath, prepared.recipe(), first, last, work));        var anotherRecipe = prepare(work, CardinalFrame.Y_POS).recipe();
        assertFalse(receipt.proves(prepared.path(), anotherRecipe, 2, 1, 1, 0, 1, work));
        first[0] = Math.nextUp(first[0]);
        assertNull(CardinalTwoComponentWallLemma.prepare(prepared.path(), prepared.recipe(), first, last, work));
    }
    @Test void translatedActualSourcesRetainTouchBoundaryWithoutAbsoluteCenterProfile() {
        for (double shift : new double[] {1, -4, 8}) {
            var work = new StoredDyadic.Work();
            var prepared = prepare(work, CardinalFrame.Y_POS, shift);
            var path = prepared.path();
            assertTrue(path.specialWallWorkDelta() > 0);
            double center = prepared.recipe().start().placement().center().z();
            double mMax = Math.nextUp(1.0) * prepared.recipe().halfLength();
            double lowerEndpoint = center - mMax;
            double radius = prepared.recipe().start().capsule().radius();
            double plane = lowerEndpoint - radius;
            var exact = StoredDyadic.of(lowerEndpoint, work).subtract(StoredDyadic.of(radius, work), work);
            if (exact.compare(StoredDyadic.of(plane, work), work) < 0) plane = Math.nextDown(plane);
            assertTrue(path.provesAxisHalfSpace(2, 1, plane, 0, 1, work), "shift=" + shift);
            assertFalse(path.provesAxisHalfSpace(2, 1, Math.nextUp(plane), 0, 1, work), "shift=" + shift);
        }
    }
    @Test void inwardCenterAndRadiusProfileChangesCannotReuseWallPermission() {
        var work = new StoredDyadic.Work();
        var inward = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                prepareOutcome(work, CardinalFrame.Y_POS, -Math.ulp(1.8999999761581416)));
        assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_ENDPOINTS, inward.reason());
        var source = prepare(work, CardinalFrame.Y_POS).recipe().sourceContainment().source();
        var enlarged = new PlayerBodyShape.Dimensions(Math.nextUp((double) .6F), (double) 1.8F, (double) 1.62F);
        var mismatch = assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                CardinalContainedCapsuleSource.prepare(source, enlarged, work));
        assertEquals(CardinalContainedCapsuleSource.Reason.DIMENSIONS_CHANGED, mismatch.reason());
        assertThrows(CardinalRealizedArcRecipe.DomainException.class,
                () -> CardinalJointRnDomain.prove(new Vec3d(40.5, 88.89999997615814, 0),
                        SIZE.centerlineHalfLength(), 0x1.333331ffffffcp-1, work));
    }
    @Test void anotherTargetDoesNotReceiveTheSpecialWallLemma() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.X_POS);
        assertEquals(0, prepared.path().specialWallWorkDelta());
    }

    @Test void exhaustionInsideWallProofPreservesTheOriginalNumericException() {
        var work = new StoredDyadic.Work();
        var prepared = prepare(work, CardinalFrame.Y_POS);
        var q0 = CardinalFrame.Z_POS.attitude();
        var q1 = CardinalFrame.Y_POS.attitude();
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var cause = assertThrows(NumericWorkExhaustedException.class,
                () -> CardinalTwoComponentWallLemma.prepare(prepared.path(), prepared.recipe(),
                        new double[] {q0.x(), q0.y(), q0.z(), q0.w()},
                        new double[] {q1.x(), q1.y(), q1.z(), q1.w()}, work));
        assertTrue(java.util.Arrays.stream(cause.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(CardinalTwoComponentWallLemma.class.getName())));
        assertNull(cause.getCause());
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work, CardinalFrame target) {
        return prepare(work, target, 0);
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work, CardinalFrame target, double shift) {
        var outcome = prepareOutcome(work, target, shift);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, outcome, () -> outcome.toString());
    }
    private static CardinalRealizedArcRecipe.Result prepareOutcome(StoredDyadic.Work work, CardinalFrame target, double shift) {
        var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1 + shift),
                new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833 + shift));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(40.5, 88.59999996423721, 1.8999999761581416 + shift), CardinalFrame.Z_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 195),
                new UUID(0, 196), 0, 2), 7960, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var outcome = CardinalRealizedArcRecipe.prepare(source.containment(), target, work);
        return outcome;
    }
}
