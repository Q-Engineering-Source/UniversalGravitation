package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalNominalSourceRecipeTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void actualNativeRestoreNominalSourceUsesOriginalScalar() {
        var work = new StoredDyadic.Work();
        var source = source(work);
        assertEquals(RealizationRelation.Kind.NOMINAL, source.placement().storedCapsuleGeometry().relation().kind());
        assertNotEquals(Double.doubleToRawLongBits(SIZE.centerlineHalfLength()),
                Double.doubleToRawLongBits(Math.abs(source.placement().storedCapsuleGeometry().storedHalfLine().x())));
        var outcome = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work);
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, outcome, () -> outcome.toString());
        assertEquals(Double.doubleToRawLongBits(SIZE.centerlineHalfLength()),
                Double.doubleToRawLongBits(prepared.recipe().halfLength()));
        assertTrue(prepared.path().preparedFor(prepared.recipe().start(), prepared.recipe().target(), work));
        assertEquals(0, prepared.path().specialWallWorkDelta());
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, prepared.path().fixedDomainProof());
        assertEquals(0, prepared.path().fixedDomainTailValues());
        assertEquals(0, prepared.path().fixedDomainIntervalRegions());
        var geometry = source.placement().storedCapsuleGeometry();
        assertTrue(StoredRealizationLimits.sameRaw(new Vec3d(7.400000035762787, 65.89999997615814, 8), geometry.negativeEndpoint()));
        assertTrue(StoredRealizationLimits.sameRaw(new Vec3d(8.599999964237213, 65.89999997615814, 8), geometry.positiveEndpoint()));
    }

    @Test void actualAutomaticYToXRetainsSourceApplicableH1WithoutClaimingOwnerBudgetPass() {
        var work = new StoredDyadic.Work();
        var bounds = new Aabb(new Vec3d(8.599999964237213, 65, 8.199999988079071),
                new Vec3d(9.199999988079071, 66.79999995231628, 8.800000011920929));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, new Vec3d(8.899999976158142, 65, 8.5),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var source = source(placement, work);
        assertEquals(RealizationRelation.Kind.NOMINAL, source.placement().storedCapsuleGeometry().relation().kind());
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.X_POS, work);
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN, prepared.path().fixedDomainProof());
        long before = work.units();
        var reproof = CapsuleArcEnclosure.prepareFixed(prepared.recipe(), CardinalFrame.Y_POS.up(), work);
        assertEquals(work.units() - before, reproof.numericWorkDelta());
        assertEquals(17, reproof.fixedDomainTailValues());
        assertEquals(0, reproof.specialWallWorkDelta());
        System.out.println("automatic-source recipe totalWork=" + work.units() + " fixedPreparation=" + reproof.numericWorkDelta());
    }

    @Test void nominalSourceNearOriginIsNotSubjectToTheH1CenterGapProof() {
        var work = new StoredDyadic.Work();
        var source = source(new PlayerBodyPlacement(new Vec3d(0, 65.89999997615814, 8),
                CardinalFrame.X_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.X_POS, SIZE)), work);
        var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work);
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, prepared.path().fixedDomainProof());
        assertThrows(CardinalRealizedArcRecipe.DomainException.class, () -> CardinalJointRnDomain.prove(
                source.placement().center(), SIZE.centerlineHalfLength(), 0x1.333331ffffffcp-1, work));
    }

    @Test void applicableH1TargetDomainFailureMustNotFallbackToNominal() {
        var work = new StoredDyadic.Work();
        var source = source(new PlayerBodyPlacement(new Vec3d(0, 65.89999997615814, 8),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE)), work);
        var selected = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.X_POS, work));
        assertEquals(CardinalRealizedArcRecipe.Reason.TARGET_DOMAIN, selected.reason());
        var failedPath = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work));
        assertEquals(CardinalRealizedArcRecipe.Reason.PATH_DOMAIN, failedPath.reason());
        var nominalSameAxis = CardinalRealizedArcRecipe.prepareCandidate(source.containment(), CardinalFrame.Y_POS,
                SIZE.centerlineHalfLength(), work);
        assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, nominalSameAxis,
                "nominal would pass, but selected H1 path failure cannot retry it");
        var nominal = CardinalRealizedArcRecipe.prepareCandidate(source.containment(), CardinalFrame.X_POS,
                SIZE.centerlineHalfLength(), work);
        assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, nominal,
                "nominal would succeed, but the default source-selected H1 must not retry it");
    }
    @Test void exhaustionDuringSourceEligibilityIsFailureAndNeverNominalFallback() {
        var work = new StoredDyadic.Work();
        var source = source(work);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 1);
        var result = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work));
        assertEquals(CardinalRealizedArcRecipe.Reason.NUMERIC_BUDGET, result.reason());
        var original = assertInstanceOf(NumericWorkExhaustedException.class, result.cause().orElseThrow());
        assertNull(original.getCause());
        assertTrue(java.util.Arrays.stream(original.getStackTrace()).anyMatch(frame ->
                frame.getMethodName().equals("prepareH1Source")));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }
    @Test void nominalSourceStillRejectsForeignWorkAndH1EndpointChanges() {
        var work = new StoredDyadic.Work();
        var source = source(work);
        assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_IDENTITY,
                assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                        CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS,
                                new StoredDyadic.Work())).reason());
        assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_ENDPOINTS,
                assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                        CardinalRealizedArcRecipe.prepareCandidate(source.containment(), CardinalFrame.Y_POS,
                                0x1.333331ffffffcp-1, work)).reason());
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        var exhausted = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work));
        assertEquals(CardinalRealizedArcRecipe.Reason.NUMERIC_BUDGET, exhausted.reason());
        assertInstanceOf(NumericWorkExhaustedException.class, exhausted.cause().orElseThrow());
    }

    @Test void translatedDefaultSourceSelectionProvesOriginalWallWithoutTargetBasedChoice() {
        for (double shift : new double[] {1, -4, 8}) {
            var work = new StoredDyadic.Work();
            var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1 + shift),
                    new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833 + shift));
            var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                    new Vec3d(40.5, 88.59999996423721, 1.8999999761581416 + shift),
                    CardinalFrame.Z_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, SIZE));
            var source = source(placement, work);
            var result = CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work);
            var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, result, () -> result.toString());
            boolean wall = prepared.path().provesAxisHalfSpace(2, 1, 1 + shift, 0, 1, work);
            assertTrue(wall, "translated default source selection must retain the wall proof");
            System.out.println("TRANSLATED_SOURCE shift=" + shift + " relation="
                    + source.placement().storedCapsuleGeometry().relation().kind() + " scalar="
                    + Double.toHexString(prepared.recipe().halfLength()) + " wholeWall=" + wall
                    + " special=" + prepared.path().specialWallWorkDelta() + " work=" + work.units());
        }
    }
    private static CardinalContainedCapsuleSource.Prepared source(PlayerBodyPlacement placement, StoredDyadic.Work work) {
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 795),
                new UUID(0, 796), 0, 2), 8960, placement, new Vec3d(0, 0, 0));
        return assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, placement.shape().dimensions(), work));
    }
    private static CardinalContainedCapsuleSource.Prepared source(StoredDyadic.Work work) {
        var bounds = new Aabb(new Vec3d(7.100000023841858, 65.59999996423721, 7.699999988079071),
                new Vec3d(8.899999976158142, 66.19999998807907, 8.300000011920929));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, new Vec3d(8, 65.59999996423721, 8),
                CardinalFrame.X_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.X_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 795),
                new UUID(0, 796), 0, 2), 8960, placement, new Vec3d(0, 0, 0));
        return assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
    }
}