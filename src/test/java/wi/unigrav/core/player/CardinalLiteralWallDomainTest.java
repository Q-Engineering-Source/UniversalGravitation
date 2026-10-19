package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalLiteralWallDomainTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
    private static final CardinalFrame[][] PAIRS = {
        {CardinalFrame.Z_POS, CardinalFrame.Y_POS}, {CardinalFrame.Y_POS, CardinalFrame.X_POS},
        {CardinalFrame.X_POS, CardinalFrame.Y_POS}, {CardinalFrame.Y_POS, CardinalFrame.X_NEG},
        {CardinalFrame.Y_POS, CardinalFrame.Z_POS}, {CardinalFrame.Y_POS, CardinalFrame.Z_NEG}};

    @Test void actualDefaultFactorySixLiteralChainsRetainBothProvedDomains() {
        for (var pair : PAIRS) {
            for (double axial : new double[] {1.899999976158142, Math.nextDown(1.899999976158142),
                    Math.nextUp(1.899999976158142), 2.0, 4.0, Math.nextUp(4.0), 7.100000023841858, 8.599999964237213, 8.899999976158142, Math.nextUp(8.899999976158142), Math.nextDown(8.899999976158142), 8.5}) {
                var work = new StoredDyadic.Work();
                var center = center(pair[0], axial);
                var result = prepare(pair[0], pair[1], center, SIZE, work);
                System.out.println("FAMILY " + pair[0] + "->" + pair[1] + " C=" + center
                        + " result=" + (result instanceof CardinalRealizedArcRecipe.Prepared p ? p.path().fixedDomainProof() : result));
                if (axial == Math.nextUp(1.899999976158142)) {
                    assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_ENDPOINTS,
                            assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class, result).reason());
                    continue;
                }
                if (pair[0] == CardinalFrame.Y_POS && axial <= 2) {
                    assertEquals(CardinalRealizedArcRecipe.Reason.TARGET_CONTAINMENT,
                            assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class, result).reason());
                    continue;
                }
                var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result);
                if (axial == 4.0 || pair[0] != CardinalFrame.Y_POS && (axial <= 2 || axial == 8.5))
                    assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, prepared.path().fixedDomainProof());
                assertTrue(CardinalLiteralWallDomain.matches(prepared.path(), prepared.recipe(), true, work));
                assertReceipt(prepared, pair[0], pair[1], work);
            }
        }
    }

    @FunctionalInterface private interface Bound {
        boolean proves(CapsuleArcEnclosure.Path path, CardinalRealizedArcRecipe recipe, int axis,
                int side, double plane, double from, double to, StoredDyadic.Work work);
    }
    private static Bound receipt(CardinalRealizedArcRecipe.Prepared p, CardinalFrame from, CardinalFrame to,
            double[] q0, double[] q1, StoredDyadic.Work work) {
        if (from == CardinalFrame.Z_POS) {
            var r = CardinalTwoComponentWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            return r == null ? null : r::proves;
        }
        if (from == CardinalFrame.X_POS) {
            var r = CardinalXToYWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            return r == null ? null : r::proves;
        }
        if (to == CardinalFrame.X_POS) {
            var r = CardinalYToXWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            return r == null ? null : r::proves;
        }
        var r = CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
        return r == null ? null : r::proves;
    }

    @Test void nominalDynamicSupportsIncludeBothActualOverridesAndAdjacentPlanes() {
        for (var pair : PAIRS) {
            var work = new StoredDyadic.Work();
            var p = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                    prepare(pair[0], pair[1], center(pair[0], 4), SIZE, work));
            assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, p.path().fixedDomainProof());
            var bound = receipt(p, pair[0], pair[1], components(pair[0]), components(pair[1]), work);
            assertNotNull(bound);
            int axis = pair[0] == CardinalFrame.Z_POS ? 2
                    : pair[1] == CardinalFrame.Z_POS || pair[1] == CardinalFrame.Z_NEG ? 2 : 0;
            for (int testedAxis : pair[0] == CardinalFrame.Z_POS ? new int[] {axis} : new int[] {axis, 1}) {
                double c = StoredRealizationLimits.component(p.recipe().start().placement().center(), testedAxis);
                double product = (testedAxis == 1 ? 1 : Math.nextUp(1.0)) * p.recipe().halfLength();
                var first = p.recipe().start().placement().storedCapsuleGeometry();
                var last = p.recipe().target().storedCapsuleGeometry();
                double minimum = Math.min(c - product, c);
                for (var g : new StoredCapsuleGeometry[] {first, last}) {
                    minimum = Math.min(minimum, StoredRealizationLimits.component(g.negativeEndpoint(), testedAxis));
                    minimum = Math.min(minimum, StoredRealizationLimits.component(g.positiveEndpoint(), testedAxis));
                }
                var exact = StoredDyadic.of(minimum, work).subtract(StoredDyadic.of(first.radius(), work), work);
                double plane = minimum - first.radius();
                if (exact.compare(StoredDyadic.of(plane, work), work) < 0) plane = Math.nextDown(plane);
                for (double[] interval : new double[][] {{0, 0}, {1, 1}, {0, 1}, {.25, .75}}) {
                    assertTrue(bound.proves(p.path(), p.recipe(), testedAxis, 1, plane, interval[0], interval[1], work));
                    assertFalse(bound.proves(p.path(), p.recipe(), testedAxis, 1, Math.nextUp(plane), interval[0], interval[1], work));
                }
                assertTrue(bound.proves(p.path(), p.recipe(), testedAxis, 1, Math.nextDown(plane), 0, 1, work));
            }
        }
    }

    @Test void nominalMintRejectsForeignIdentityExtraComponentsAndNonNativeProfile() {
        for (var pair : PAIRS) {
            var work = new StoredDyadic.Work();
            var p = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                    prepare(pair[0], pair[1], center(pair[0], 4), SIZE, work));
            var q0 = components(pair[0]); var q1 = components(pair[1]);
            var bound = receipt(p, pair[0], pair[1], q0, q1, work);
            assertNotNull(bound);
            assertFalse(bound.proves(p.path(), p.recipe(), 0, 1, -100, 0, 1, new StoredDyadic.Work()));
            var other = CapsuleArcEnclosure.prepareFixed(p.recipe(), pair[0].up(), work);
            assertFalse(bound.proves(other, p.recipe(), 0, 1, -100, 0, 1, work));
            assertNull(receipt(new CardinalRealizedArcRecipe.Prepared(p.recipe(), other), pair[0], pair[1],
                    q0, new double[] {q1[0], Double.MIN_VALUE, q1[2], q1[3]}, work));
            assertNull(receipt(p, pair[0], pair[1], q0, q1, new StoredDyadic.Work()));
            var nonNative = new PlayerBodyShape.Dimensions(.6F, 1.8F, Math.nextUp((double) 1.62F));
            var nonNativeResult = prepare(pair[0], pair[1], center(pair[0], 4), nonNative, work);
            var changed = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, nonNativeResult);
            assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, changed.path().fixedDomainProof());
            assertNull(receipt(changed, pair[0], pair[1], q0, q1, work));
        }
    }

    @Test void nominalProofInternalExhaustionKeepsOriginalCauseAndNoBudgetReset() {
        var work = new StoredDyadic.Work();
        var p = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                prepare(CardinalFrame.Z_POS, CardinalFrame.Y_POS, center(CardinalFrame.Z_POS, 4), SIZE, work));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 32);
        var original = assertThrows(NumericWorkExhaustedException.class,
                () -> receipt(p, CardinalFrame.Z_POS, CardinalFrame.Y_POS,
                        components(CardinalFrame.Z_POS), components(CardinalFrame.Y_POS), work));
        assertNull(original.getCause());
        assertTrue(java.util.Arrays.stream(original.getStackTrace()).anyMatch(f ->
                f.getClassName().equals(CardinalTwoComponentWallLemma.class.getName())));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    private static void assertReceipt(CardinalRealizedArcRecipe.Prepared p, CardinalFrame source,
            CardinalFrame target, StoredDyadic.Work work) {
        var q0 = components(source); var q1 = components(target);
        if (source == CardinalFrame.Z_POS) {
            var receipt = CardinalTwoComponentWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            assertNotNull(receipt);
            assertTrue(receipt.proves(p.path(), p.recipe(), 2, 1, -100, 0, 1, work));
        } else if (source == CardinalFrame.X_POS) {
            var receipt = CardinalXToYWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            assertNotNull(receipt);
            assertTrue(receipt.proves(p.path(), p.recipe(), 0, 1, -100, 0, 1, work));
        } else if (target == CardinalFrame.X_POS) {
            var receipt = CardinalYToXWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            assertNotNull(receipt);
            assertTrue(receipt.proves(p.path(), p.recipe(), 0, 1, -100, 0, 1, work));
        } else {
            var receipt = CardinalSignedAxisWallLemma.prepare(p.path(), p.recipe(), q0, q1, work);
            assertNotNull(receipt);
            assertTrue(receipt.proves(p.path(), p.recipe(), target == CardinalFrame.X_NEG ? 0 : 2, 1, -100, 0, 1, work));
        }
    }

    private static Vec3d center(CardinalFrame frame, double axial) {
        return frame == CardinalFrame.Y_POS ? new Vec3d(40.5, axial, 88.89999997615814)
                : frame == CardinalFrame.X_POS ? new Vec3d(axial, 88.89999997615814, 40.5)
                : new Vec3d(40.5, 88.89999997615814, axial);
    }
    private static CardinalRealizedArcRecipe.Result prepare(CardinalFrame from, CardinalFrame to, Vec3d center,
            PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work work) {
        var original = new PlayerBodyPlacement(center, from.attitude(), new PlayerBodyShape.Cardinal(from, dimensions));
        // Keep the actual stored box and transport, never reconstruct accepted bounds.
        var placement = PlayerBodyPlacement.fromCardinalBounds(original.bounds(), original.transport(),
                from.attitude(), new PlayerBodyShape.Cardinal(from, dimensions));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 125),
                new UUID(0, 126), 0, 2), 7960, placement, new Vec3d(0, 0, 0));
        var source = CardinalContainedCapsuleSource.prepare(snapshot, dimensions, work);
        if (!(source instanceof CardinalContainedCapsuleSource.Prepared accepted))
            throw new AssertionError("factory rejected " + source);
        return CardinalRealizedArcRecipe.prepare(accepted.containment(), to, work);
    }
    private static double[] components(CardinalFrame frame) {
        var q = frame.attitude(); return new double[] {q.x(), q.y(), q.z(), q.w()};
    }
}
