package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalContainedCapsuleSourceTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.5, 2, 1.5);

    @Test void finalRuntimeSampleKeepsNominalRecipeUnchanged() {
        var size = new PlayerBodyShape.Dimensions(.6000000238418579, 1.7999999523162842, 1.6200000047683716);
        var source = snapshot(PlayerBodyPlacement.fromTransport(new Vec3d(34.01196263193376, 91.59340279684535, 8.093212564301698),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, size)));
        var work = new StoredDyadic.Work();
        var result = prepared(source, size, work);
        assertEquals(RealizationRelation.Kind.NOMINAL, result.placement().storedCapsuleGeometry().relation().kind());
        assertEquals(source.placement().center(), result.placement().center());
        assertEquals(size, result.placement().shape().dimensions());
        assertEquals(source.placement().bounds(), result.containment().outwardCapsuleBounds());
    }

    @Test void sixAxisInwardRoundingGetsLargestLegalStoredHalfLine() {
        for (var frame : CardinalFrame.values()) {
            var work = new StoredDyadic.Work();
            var source = tangentRoom(insetSource(frame, new Vec3d(0, 0, 0), frame.up().y() != 0 ? 2 : 3), frame);
            var result = prepared(source, SIZE, work);
            var stored = result.placement().storedCapsuleGeometry();
            assertEquals(RealizationRelation.Kind.CONTAINED_CARDINAL, stored.relation().kind());
            assertSame(result.placement(), result.pose().placement());
            assertTrue(result.containment().matches(source, result.placement(), work));
            assertFalse(result.containment().matches(snapshot(source.placement()), result.placement(), work));
            assertFalse(result.containment().matches(source, result.placement(), new StoredDyadic.Work()));
            assertEquals(source.placement().center(), result.placement().center());
            assertEquals(SIZE.radius(), stored.radius());
            int axis = stored.relation().modifiedAxis();
            double value = StoredRealizationLimits.component(stored.storedHalfLine(), axis);
            assertTrue(Math.abs(value) < SIZE.centerlineHalfLength());
            var difference = StoredDyadic.of(value, work).subtract(
                    StoredDyadic.of(StoredRealizationLimits.component(stored.nominalHalfLine(), axis), work), work).abs(work);
            assertTrue(difference.compare(stored.relation().limits().halfLineBound(axis), work) <= 0);
            double next = Math.copySign(Math.nextUp(Math.abs(value)), value);
            var larger = replace(stored.storedHalfLine(), axis, next);
            var relation = RealizationRelation.containedCardinal(stored.relation().limits(), larger, axis, work);
            var proposed = PlayerBodyPlacement.proposedFromStoredGeometry(stored.center(), stored.attitude(),
                    new PlayerBodyShape.FreeCapsule(SIZE), StoredCapsuleGeometry.fromRecipe(stored.center(),
                            stored.attitude(), SIZE, larger, relation), work);
            assertInstanceOf(CardinalCapsuleContainment.Outside.class, CardinalCapsuleContainment.prepare(source, proposed, work));
            assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.exportFreeV1(result.placement()));
        }
    }

    @Test void fixedNonAxisQuaternionResidualCannotBeSilentlyZeroed() {
        for (var frame : new CardinalFrame[] {CardinalFrame.X_POS, CardinalFrame.X_NEG, CardinalFrame.Z_POS, CardinalFrame.Z_NEG}) {
            var source = insetSource(frame, new Vec3d(0, 0, 0), 1);
            var result = assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                    CardinalContainedCapsuleSource.prepare(source, SIZE, new StoredDyadic.Work()));
            assertEquals(CardinalContainedCapsuleSource.Reason.TANGENT_UNREPRESENTABLE, result.reason());
        }
    }

    @Test void negativeCoordinatesAndBinadesPreserveExactSourceCenter() {
        for (double y : new double[] {-128, -64, -.5, 0, .5, 64, 128}) {
            var source = insetSource(CardinalFrame.Y_POS, new Vec3d(0, y, 0), 1);
            var work = new StoredDyadic.Work();
            var result = prepared(source, SIZE, work);
            assertEquals(source.placement().center(), result.placement().center());
            assertTrue(result.containment().matches(source, result.placement(), work));
        }
    }

    @Test void actualNoSolutionReturnsTypedReasonWithoutShrinkingDimensions() {
        var source = insetSource(CardinalFrame.Y_POS, new Vec3d(0, 0, 0), 100);
        var result = assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                CardinalContainedCapsuleSource.prepare(source, SIZE, new StoredDyadic.Work()));
        assertEquals(CardinalContainedCapsuleSource.Reason.NO_REPRESENTABLE_CONTAINED_SOURCE, result.reason());
        assertEquals(SIZE, source.placement().shape().dimensions());
    }

    @Test void tangentLessThanDiameterIsTypedUnrepresentable() {
        var source = snapshot(PlayerBodyPlacement.fromCardinalBounds(
                new Aabb(new Vec3d(-.24, -1, -.25), new Vec3d(.24, 1, .25)), new Vec3d(0, -1, 0),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE)));
        assertEquals(CardinalContainedCapsuleSource.Reason.TANGENT_UNREPRESENTABLE,
                assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                        CardinalContainedCapsuleSource.prepare(source, SIZE, new StoredDyadic.Work())).reason());
    }

    @Test void sphereOnlyUsesNominalAndNeverCorrectsHalfLine() {
        var sphere = new PlayerBodyShape.Dimensions(.5, .5, .25);
        var source = snapshot(new PlayerBodyPlacement(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, sphere)));
        assertEquals(RealizationRelation.Kind.NOMINAL, prepared(source, sphere, new StoredDyadic.Work())
                .placement().storedCapsuleGeometry().relation().kind());
        var small = snapshot(PlayerBodyPlacement.fromCardinalBounds(
                new Aabb(new Vec3d(-.25, -.24, -.25), new Vec3d(.25, .24, .25)), new Vec3d(0, -.24, 0),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, sphere)));
        assertEquals(CardinalContainedCapsuleSource.Reason.NO_REPRESENTABLE_CONTAINED_SOURCE,
                assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                        CardinalContainedCapsuleSource.prepare(small, sphere, new StoredDyadic.Work())).reason());
    }

    @Test void dimensionChangesAreNotImplicitResizeAuthority() {
        var source = insetSource(CardinalFrame.Y_POS, new Vec3d(0, 0, 0), 1);
        assertEquals(CardinalContainedCapsuleSource.Reason.DIMENSIONS_CHANGED,
                assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                        CardinalContainedCapsuleSource.prepare(source, new PlayerBodyShape.Dimensions(.5, 1, .8),
                                new StoredDyadic.Work())).reason());
    }

    @Test void relationAndProposalRejectWorkRecipeAndNonAxisSubstitution() {
        var work = new StoredDyadic.Work();
        var result = prepared(insetSource(CardinalFrame.Y_POS, new Vec3d(0, 0, 0), 1), SIZE, work);
        var stored = result.placement().storedCapsuleGeometry();
        var limits = stored.relation().limits();
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                stored.storedHalfLine(), 1, new StoredDyadic.Work()));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                new Vec3d(Double.MIN_VALUE, stored.storedHalfLine().y(), 0), 1, work));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                new Vec3d(0, 0, 0), 1, work));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                new Vec3d(0, -.75, 0), 1, work));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                new Vec3d(0, Math.nextUp(.75), 0), 1, work));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.proposedFromStoredGeometry(
                stored.center(), stored.attitude(), new PlayerBodyShape.FreeCapsule(SIZE), stored, new StoredDyadic.Work()));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.proposedFromStoredGeometry(
                new Vec3d(0, 1, 0), stored.attitude(), new PlayerBodyShape.FreeCapsule(SIZE), stored, work));
    }

    @Test void exactDomainBoundaryIsAcceptedAndNextOutsideIsRejected() {
        var work = new StoredDyadic.Work();
        var limits = StoredRealizationLimits.derive(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(), SIZE, work);
        double lower = StoredDyadic.of(.75, work).subtract(limits.halfLineBound(1), work).roundUp(work);
        assertEquals(RealizationRelation.Kind.CONTAINED_CARDINAL,
                RealizationRelation.containedCardinal(limits, new Vec3d(0, lower, 0), 1, work).kind());
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.containedCardinal(limits,
                new Vec3d(0, Math.nextDown(lower), 0), 1, work));
    }

    @Test void exhaustedCallerBudgetKeepsOriginalCauseAndDoesNotReplaceLedger() {
        var work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        var result = assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class,
                CardinalContainedCapsuleSource.prepare(insetSource(CardinalFrame.Y_POS, new Vec3d(0, 0, 0), 1), SIZE, work));
        assertEquals(CardinalContainedCapsuleSource.Reason.NUMERIC_BUDGET, result.reason());
        assertInstanceOf(NumericWorkExhaustedException.class, result.cause().orElseThrow());
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    private static CardinalContainedCapsuleSource.Prepared prepared(PlayerPhysicalSnapshot source,
            PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work work) {
        var result = CardinalContainedCapsuleSource.prepare(source, dimensions, work);
        return assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class, result,
                () -> source.placement().toString() + "; result=" + result);
    }

    private static PlayerPhysicalSnapshot insetSource(CardinalFrame frame, Vec3d center, int ulps) {
        var nominal = new PlayerBodyPlacement(center, frame.attitude(), new PlayerBodyShape.Cardinal(frame, SIZE));
        int axis = frame.up().x() != 0 ? 0 : frame.up().y() != 0 ? 1 : 2;
        double lower = StoredRealizationLimits.component(nominal.bounds().min(), axis);
        double upper = StoredRealizationLimits.component(nominal.bounds().max(), axis);
        for (int i = 0; i < ulps; i++) { lower = Math.nextUp(lower); upper = Math.nextDown(upper); }
        var box = new Aabb(replace(nominal.bounds().min(), axis, lower), replace(nominal.bounds().max(), axis, upper));
        return snapshot(PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(box.center().x(), box.min().y(), box.center().z()), frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, SIZE)));
    }

    private static PlayerPhysicalSnapshot tangentRoom(PlayerPhysicalSnapshot source, CardinalFrame frame) {
        int axis = frame.up().x() != 0 ? 0 : frame.up().y() != 0 ? 1 : 2;
        Vec3d min = source.placement().bounds().min(), max = source.placement().bounds().max();
        // This distinct actual box supplies real tangent room; it does not alter the nominal
        // dimensions or zero the fixed Q residual. The exact-width box is tested as unavailable.
        for (int component = 0; component < 3; component++) {
            if (component == axis) continue;
            double low = StoredRealizationLimits.component(min, component);
            double high = StoredRealizationLimits.component(max, component);
            for (int i = 0; i < 16; i++) { low = Math.nextDown(low); high = Math.nextUp(high); }
            min = replace(min, component, low); max = replace(max, component, high);
        }
        var box = new Aabb(min, max);
        return snapshot(PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(box.center().x(), box.min().y(), box.center().z()), frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, SIZE)));
    }

    private static Vec3d replace(Vec3d value, int axis, double component) {
        return new Vec3d(axis == 0 ? component : value.x(), axis == 1 ? component : value.y(), axis == 2 ? component : value.z());
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerBodyPlacement placement) {
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 4531), new UUID(0, 4532), 0, 1),
                1283, placement, new Vec3d(0, 0, 0));
    }
}
