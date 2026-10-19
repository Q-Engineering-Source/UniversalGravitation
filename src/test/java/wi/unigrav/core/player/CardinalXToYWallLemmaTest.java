package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalXToYWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void exactR24ReverseTouchHasWholeClosedIntervalSupportIncludingOverrides() {
        var work = new StoredDyadic.Work();
        var p = prepare(work, CardinalFrame.Y_POS);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN, p.path().fixedDomainProof());
        assertEquals(0x3fe333331ffffffcL, Double.doubleToRawLongBits(p.recipe().halfLength()));
        var center = p.recipe().start().placement().center();
        double product = Math.nextUp(1.0) * p.recipe().halfLength();
        double negative = center.x() - product;
        var radius = StoredDyadic.of(p.recipe().start().capsule().radius(), work);
        assertEquals(0, StoredDyadic.of(negative, work).subtract(radius, work).compare(StoredDyadic.of(9, work), work));
        for (var geometry : new StoredCapsuleGeometry[] {p.recipe().start().placement().storedCapsuleGeometry(), p.recipe().target().storedCapsuleGeometry()}) {
            assertTrue(StoredDyadic.of(Math.min(geometry.negativeEndpoint().x(), geometry.positiveEndpoint().x()), work)
                    .subtract(radius, work).compare(StoredDyadic.of(9, work), work) >= 0);
        }
        for (double[] interval : new double[][] {{0, 0}, {1, 1}, {0, 1}, {.25, .75}})
            assertTrue(p.path().provesAxisHalfSpace(0, 1, 9, interval[0], interval[1], work));
        assertTrue(p.path().xToYWallWorkDelta() > 0);
        assertFalse(p.path().provesAxisHalfSpace(0, 1, Math.nextUp(9.0), 0, 1, work));
        long before = work.units();
        var reproof = CapsuleArcEnclosure.prepareFixed(p.recipe(), CardinalFrame.Y_POS.up(), work);
        assertEquals(work.units() - before, reproof.numericWorkDelta());
        assertTrue(reproof.xToYWallWorkDelta() > 0);
    }

    @Test void firstMintRequiresThisPathRecipeSignedInputsAndWork() {
        var work = new StoredDyadic.Work();
        var p = prepare(work, CardinalFrame.Y_POS);
        var q0 = components(CardinalFrame.X_POS);
        var q1 = components(CardinalFrame.Y_POS);
        var receipt = CardinalXToYWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
        assertNotNull(receipt);
        var wrong = prepare(work, CardinalFrame.Z_POS);
        assertEquals(0, wrong.path().xToYWallWorkDelta());
        assertNull(CardinalXToYWallLemma.prepare(wrong.path(), p.recipe(), q0, q1, work));
        assertFalse(receipt.proves(wrong.path(), p.recipe(), 0, 1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), wrong.recipe(), 0, 1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 1, 9, 0, 1, new StoredDyadic.Work()));
        q0[2] = Math.nextDown(q0[2]);
        assertNull(CardinalXToYWallLemma.prepare(p.path(), p.recipe(), q0, q1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 2, 1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, -1, 9, 0, 1, work));
    }

    @Test void proofInternalBudgetExhaustionRetainsOriginalCauseAndStack() {
        var work = new StoredDyadic.Work();
        var p = prepare(work, CardinalFrame.Y_POS);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var failure = assertThrows(NumericWorkExhaustedException.class,
                () -> CardinalXToYWallLemma.prepare(p.path(), p.recipe(), components(CardinalFrame.X_POS), components(CardinalFrame.Y_POS), work));
        assertNull(failure.getCause());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(f -> f.getClassName().equals(CardinalXToYWallLemma.class.getName())));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void actualReverseOperationMappingChecksBoundaryAndZeroCasesWithoutClaimingSamplingProof() {
        double k = CardinalFrame.X_POS.attitude().w();
        for (double t : new double[] {0, Double.MIN_VALUE, 0x1.cp-42, .25, .5, Math.nextDown(1.0), 1}) {
            double s = 1 - t, p = k * s;
            double z = (-k) * s + t * 0.0, w = p + t;
            assertTrue(w > 0 && Math.abs(z) <= w);
            double a = p / w;
            var actual = new Rotation3d(0, 0, z, w).basisY();
            var reduced = new Rotation3d(0, 0, -a, 1).basisY();
            assertEquals(Double.doubleToRawLongBits(actual.x()), Double.doubleToRawLongBits(reduced.x()));
            assertEquals(Double.doubleToRawLongBits(actual.y()), Double.doubleToRawLongBits(reduced.y()));
            assertTrue(actual.x() >= 0 && actual.x() <= Math.nextUp(1.0));
        }
    }

    private static double[] components(CardinalFrame frame) {
        var q = frame.attitude(); return new double[] {q.x(), q.y(), q.z(), q.w()};
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work, CardinalFrame target) {
        var bounds = new Aabb(new Vec3d(9, 64.39999996423721, 8.199999988079071),
                new Vec3d(10.799999952316284, 64.99999998807907, 8.800000011920929));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(9.899999976158142, 64.39999996423721, 8.5), CardinalFrame.X_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.X_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 731), new UUID(0, 732), 0, 2),
                2048, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class, CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), target, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
}