package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import java.util.Arrays;
import dev.crlhitbox.api.geometry.Rotation3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalManualNominalWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void exactManualZNegativeToXPositiveTouchHasWholePathCertificate() {
        var work = new StoredDyadic.Work();
        var p = prepare(work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, p.path().fixedDomainProof());
        long before = work.units();
        boolean whole = p.path().provesAxisHalfSpace(0, 1, 9, 0, 1, work);
        long delta = work.units() - before;
        System.out.println("MANUAL_NOMINAL whole=" + whole + " work=" + delta);
        assertTrue(whole);
        assertTrue(delta < 1000);
    }
    @Test void dynamicRawSupportAndBothActualOverridesIncludeClosedEndpoints() {
        var work = new StoredDyadic.Work();
        var p = prepare(work);
        var receipt = mint(p, work);
        assertNotNull(receipt);
        assertTrue(receipt.numericWorkDelta() > 0);
        double product = Math.nextUp(1.0) * p.recipe().halfLength();
        double negative = p.recipe().start().placement().center().x() - product;
        assertEquals(0x3fe3333320000001L, Double.doubleToRawLongBits(product));
        assertEquals(Double.doubleToRawLongBits(Double.valueOf("0x1.299999a000000p+3")), Double.doubleToRawLongBits(negative));
        var radius = StoredDyadic.of(p.recipe().start().capsule().radius(), work);
        assertEquals(0, StoredDyadic.of(negative, work).subtract(radius, work).compare(StoredDyadic.of(9, work), work));
        for (var geometry : new StoredCapsuleGeometry[] {p.recipe().start().placement().storedCapsuleGeometry(), p.recipe().target().storedCapsuleGeometry()}) {
            for (var endpoint : new Vec3d[] {geometry.negativeEndpoint(), geometry.positiveEndpoint()})
                assertTrue(StoredDyadic.of(endpoint.x(), work).subtract(radius, work).compare(StoredDyadic.of(9, work), work) >= 0);
        }
        for (double[] interval : new double[][] {{0, 0}, {1, 1}, {0, 1}, {.25, .75}}) {
            assertTrue(receipt.proves(p.path(), p.recipe(), 0, 1, 9, interval[0], interval[1], work));
            assertTrue(p.path().provesAxisHalfSpace(0, 1, Math.nextDown(9.0), interval[0], interval[1], work));
        }
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 1, Math.nextUp(9.0), 0, 1, work));
        assertFalse(p.path().provesAxisHalfSpace(0, 1, Math.nextUp(9.0), 0, 1, work));
        long before = work.units();
        var reproof = CapsuleArcEnclosure.prepareFixed(p.recipe(), CardinalFrame.Y_POS.up(), work);
        assertEquals(work.units() - before, reproof.numericWorkDelta());
    }

    @Test void initialMintRejectsForeignPathWorkLiteralAndNativeDimensionDomain() {
        var work = new StoredDyadic.Work();
        var p = prepare(work);
        var receipt = mint(p, work);
        var other = prepare(work);
        assertNull(CardinalManualNominalWallLemma.prepare(other.path(), p.recipe(), q0(), q1(), work));
        assertFalse(receipt.proves(other.path(), p.recipe(), 0, 1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), other.recipe(), 0, 1, 9, 0, 1, work));
        assertNull(CardinalManualNominalWallLemma.prepare(p.path(), p.recipe(), q0(), q1(), new StoredDyadic.Work()));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 1, 9, 0, 1, new StoredDyadic.Work()));
        var changed = q0(); changed[0] = -changed[0];
        assertNull(CardinalManualNominalWallLemma.prepare(p.path(), p.recipe(), changed, q1(), work));
        changed = q1(); changed[2] = Math.nextDown(changed[2]);
        assertNull(CardinalManualNominalWallLemma.prepare(p.path(), p.recipe(), q0(), changed, work));
        var nonNative = prepareGeneric(new PlayerBodyPlacement(new Vec3d(9.899999976158142, 64.69999997615814, 8.5),
                CardinalFrame.Z_NEG.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG,
                new PlayerBodyShape.Dimensions(.6F, 1.8F, Math.nextUp((double)1.62F)))), work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, nonNative.path().fixedDomainProof());
        assertNull(mint(nonNative, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 1, 1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, -1, 9, 0, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 1, 9, -.1, 1, work));
        assertFalse(receipt.proves(p.path(), p.recipe(), 0, 1, Double.NaN, 0, 1, work));
    }

    @Test void proofInternalExhaustionKeepsOriginalExceptionAndStack() {
        var work = new StoredDyadic.Work();
        var p = prepare(work);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var failure = assertThrows(NumericWorkExhaustedException.class, () -> mint(p, work));
        assertNull(failure.getCause());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(f -> f.getClassName().equals(CardinalManualNominalWallLemma.class.getName())));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void actualThreeComponentReductionIsDominatedIncludingZeroBoundaries() {
        // These values regress the bytecode mapping; the all-t certificate uses
        // RN monotonicity and the frozen full-a theorem, not this finite list.
        double k = Double.longBitsToDouble(0x3fe6a09e667f3bccL);
        for (double t : new double[] {0, Double.MIN_VALUE, 0x1.cp-42, .25, .5, .9375, Math.nextDown(1.0), 1}) {
            double s = 1-t, a = k*s, d = k*t, w = a+d;
            assertTrue(w > 0 && w >= a && w >= d);
            double u = a/w, v = d/w, p = u*u, q = v*v;
            double n = (p+q)+1, n0 = q+1;
            double b = 1/Math.sqrt(n), b0 = 1/Math.sqrt(n0);
            double z = v*b, z0 = v*b0;
            assertTrue(n >= n0 && b <= b0 && z <= z0);
            double reduced = b*(2*z), dominating = b0*(2*z0);
            var actual = new Rotation3d(s*(-k)+t*0.0, 0, s*0.0+t*(-k), w).basisY();
            assertTrue(actual.x() == reduced);
            assertTrue(actual.x() >= 0 && actual.x() <= dominating && dominating <= Math.nextUp(1.0));
        }
    }

    private static CardinalManualNominalWallLemma.Receipt mint(CardinalRealizedArcRecipe.Prepared p, StoredDyadic.Work work) {
        return CardinalManualNominalWallLemma.prepare(p.path(), p.recipe(), q0(), q1(), work);
    }
    private static double[] q0() { double k = Double.longBitsToDouble(0x3fe6a09e667f3bccL); return new double[] {-k, 0, 0, k}; }
    private static double[] q1() { double k = Double.longBitsToDouble(0x3fe6a09e667f3bccL); return new double[] {0, 0, -k, k}; }
    private static CardinalRealizedArcRecipe.Prepared prepareGeneric(PlayerBodyPlacement placement, StoredDyadic.Work work) {
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 753), new UUID(0, 754), 0, 2),
                8192, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, placement.shape().dimensions(), work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.X_POS, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
    private static CardinalRealizedArcRecipe.Prepared prepare(StoredDyadic.Work work) {
        var bounds = new Aabb(new Vec3d(9.599999964237213, 64.39999996423721, 7.600000023841858),
                new Vec3d(10.199999988079071, 64.99999998807907, 9.399999976158142));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(9.899999976158142, 64.39999996423721, 8.5), CardinalFrame.Z_NEG.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 751), new UUID(0, 752), 0, 2),
                8192, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class, CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.X_POS, work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
    }
}