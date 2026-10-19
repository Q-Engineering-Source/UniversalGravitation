package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.UUID;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalRealizedArcRecipeTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);

    @Test void exactRuntimeSourceAndTargetObtainWholeIntervalDomainCertificate() {
        var work = new StoredDyadic.Work();
        var prepared = source(work);
        var outcome = CardinalRealizedArcRecipe.prepare(prepared.containment(), CardinalFrame.Y_POS, work);
        var result = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class, outcome, () -> outcome.toString());
        var recipe = result.recipe();
        var path = result.path();
        assertEquals(0x1.333331ffffffcp-1, path.realizedHalfLength());
        assertTrue(path.fixedDomainIntervalRegions() > 0 || path.fixedDomainTailValues() == 17);
        assertTrue(path.fixedDomainMaximum() <= 17.0 / 16.0);
        System.out.println("nominal h=" + SIZE.centerlineHalfLength() + "; h*=" + recipe.halfLength()
                + "; delta=" + StoredDyadic.of(SIZE.centerlineHalfLength(), work)
                .subtract(StoredDyadic.of(recipe.halfLength(), work), work).roundUp(work)
                + "; intervalRegions=" + path.fixedDomainIntervalRegions() + "; tailValues=" + path.fixedDomainTailValues() + "; maxB=" + path.fixedDomainMaximum()
                + "; Work=" + work.units());
        assertTrue(path.preparedFor(recipe.start(), recipe.target(), work));
        assertFalse(path.preparedFor(recipe.start(), recipe.target(), new StoredDyadic.Work()));
        assertEquals(RealizationRelation.Kind.FIXED_HALF_LENGTH,
                recipe.start().placement().storedCapsuleGeometry().relation().kind());
        assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.exportFreeV1(recipe.target()));
        assertThrows(IllegalArgumentException.class,
                () -> CapsuleArcEnclosure.prepareFixed(recipe, CardinalFrame.Z_POS.up(), new StoredDyadic.Work()));
        assertThrows(IllegalArgumentException.class, () -> CapsuleArcEnclosure.prepare(
                recipe.start(), recipe.target(), CardinalFrame.Z_POS.up(), work));
        long beforeReprepare = work.units();
        var reproved = CapsuleArcEnclosure.prepareFixed(recipe, CardinalFrame.Z_POS.up(), work);
        long afterReprepare = work.units();
        assertEquals(afterReprepare - beforeReprepare, reproved.numericWorkDelta(),
                "fixed preparation delta includes the complete joint RN certificate");
        assertTrue(reproved.numericWorkDelta() > 6877, "the joint certificate is charged inside preparation");
        var enclosure = path.enclose(0, 1, work);
        assertNotNull(enclosure);
        assertEquals(SIZE, recipe.target().shape().dimensions());
        assertEquals(SIZE.radius(), recipe.target().capsule().radius());
    }

    @Test void actualStoredNlerpPenetrationMustRejectWallHalfspace() {
        var work = new StoredDyadic.Work();
        var initial = source(work);
        var result = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                CardinalRealizedArcRecipe.prepareCandidate(initial.containment(), CardinalFrame.Y_POS,
                        0x1.333331ffffffdp-1, work));
        boolean wall = result.path().provesAxisHalfSpace(2, 1, 1, 0, 1, work);
        System.out.println("actualWallWholeInterval=" + wall + "; Work=" + work.units());
        double t = 0x1.cp-42, complement = 1 - t;
        var q0 = CardinalFrame.Z_POS.attitude();
        var q1 = CardinalFrame.Y_POS.attitude();
        var q = new Rotation3d(complement * q0.x() + t * q1.x(), complement * q0.y() + t * q1.y(),
                complement * q0.z() + t * q1.z(), complement * q0.w() + t * q1.w());
        var hs = q.basisY().multiply(result.recipe().halfLength());
        double negativeZ = result.recipe().start().placement().center().subtract(hs).z();
        var support = StoredDyadic.of(negativeZ, work).subtract(StoredDyadic.of(SIZE.radius(), work), work);
        System.out.println("real counterexample t=" + Double.toHexString(t) + "; basis=" + q.basisY()
                + "; negativeZ=" + Double.toHexString(negativeZ) + "; support=" + support.roundDown(work));
        assertTrue(support.compare(StoredDyadic.of(1, work), work) < 0);
        assertFalse(wall, "actual interior penetration must not receive a halfspace certificate");
    }

    @Test void h1OldCounterexampleIsSafeButWholeWallStillRequiresCertificate() {
        var work = new StoredDyadic.Work();
        var initial = source(work);
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                CardinalRealizedArcRecipe.prepare(initial.containment(), CardinalFrame.Y_POS, work));
        double t = 0x1.cp-42, complement = 1 - t;
        var q0 = CardinalFrame.Z_POS.attitude();
        var q1 = CardinalFrame.Y_POS.attitude();
        var q = new Rotation3d(complement * q0.x() + t * q1.x(), complement * q0.y() + t * q1.y(),
                complement * q0.z() + t * q1.z(), complement * q0.w() + t * q1.w());
        var half = q.basisY().multiply(prepared.recipe().halfLength());
        double negativeZ = prepared.recipe().start().placement().center().subtract(half).z();
        var support = StoredDyadic.of(negativeZ, work).subtract(StoredDyadic.of(SIZE.radius(), work), work);
        assertTrue(support.compare(StoredDyadic.of(1, work), work) >= 0);
        boolean wall = prepared.path().provesAxisHalfSpace(2, 1, 1, 0, 1, work);
        System.out.println("h1 old counterexample support=" + support.roundDown(work)
                + "; wholeWall=" + wall + "; Work=" + work.units());
        assertTrue(wall, "h1 single-point safety cannot replace a whole closed-interval wall proof");
        assertTrue(prepared.path().specialWallWorkDelta() > 0);
        assertTrue(prepared.path().provesAxisHalfSpace(2, 1, 1, 0, 0, work));
        assertTrue(prepared.path().provesAxisHalfSpace(2, 1, 1, 1, 1, work));
        assertTrue(prepared.path().provesAxisHalfSpace(2, 1, 1, .25, .75, work));
    }

    @Test void nextDownScalarKeepsSourceEndpointsButOldUniformBoundCannotCertifyIt() {
        var work = new StoredDyadic.Work();
        var initial = source(work);
        var stored = initial.placement().storedCapsuleGeometry();
        double h0 = Math.abs(stored.storedHalfLine().z()), h1 = Math.nextDown(h0);
        var limits = StoredRealizationLimits.derive(initial.placement().center(),
                CardinalFrame.Z_POS.attitude(), SIZE, work);
        var relation = RealizationRelation.fixedHalfLength(limits, h1, work);
        var half = relation.storedHalfLine();
        assertTrue(StoredRealizationLimits.sameRaw(initial.placement().center().subtract(half), stored.negativeEndpoint()));
        assertTrue(StoredRealizationLimits.sameRaw(initial.placement().center().add(half), stored.positiveEndpoint()));
        var targetLimits = StoredRealizationLimits.derive(initial.placement().center(), CardinalFrame.Y_POS.attitude(), SIZE, work);
        assertNotNull(RealizationRelation.fixedHalfLength(targetLimits, h1, work));
        var u = StoredDyadic.of(0x1p-53, work);
        var nominal = StoredDyadic.of(SIZE.centerlineHalfLength(), work);
        var fixed = StoredDyadic.of(h1, work);
        var delta = nominal.subtract(fixed, work);
        var coefficient = delta.add(u.multiply(fixed, work), work)
                .add(u.multiply(u, work).multiply(nominal, work), work);
        var onePlusU = StoredDyadic.one(work).add(u, work);
        var leftAtUnitB = coefficient.add(onePlusU.multiply(StoredDyadic.lambdaHalf(work), work), work);
        var right = onePlusU.multiply(StoredDyadic.gap64(initial.placement().center().z(), work).scalePow2(1, work), work);
        System.out.println("h1=" + Double.toHexString(h1) + "; exactDelta=" + delta.roundUp(work)
                + "; oldUniformLeftEvenAtB1=" + leftAtUnitB.roundUp(work) + "; right=" + right.roundUp(work));
        assertTrue(leftAtUnitB.compare(right, work) > 0, "old triangle bound is insufficient, not a physical counterexample");
    }

    @Test void signedAxisProofRejectsAnIntrudingPlaneAndForeignWork() {
        var work = new StoredDyadic.Work();
        var initial = source(work);
        var result = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                CardinalRealizedArcRecipe.prepare(initial.containment(), CardinalFrame.Y_POS, work));
        assertFalse(result.path().provesAxisHalfSpace(2, 1, 1.01, 0, 1, work));
        assertTrue(result.path().provesAxisHalfSpace(0, 1, 40, 0, 1, work));
        assertTrue(result.path().provesAxisHalfSpace(0, -1, 41, 0, 1, work));
        assertThrows(IllegalArgumentException.class, () -> result.path().provesAxisHalfSpace(
                2, 1, 1, 0, 1, new StoredDyadic.Work()));
    }

    @Test void foreignSourceWorkIsTypedRejectedAndExhaustionRetainsCause() {
        var work = new StoredDyadic.Work();
        var prepared = source(work);
        assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_IDENTITY,
                assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                        CardinalRealizedArcRecipe.prepare(prepared.containment(), CardinalFrame.Y_POS,
                                new StoredDyadic.Work())).reason());
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        var failure = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                CardinalRealizedArcRecipe.prepare(prepared.containment(), CardinalFrame.Y_POS, work));
        assertEquals(CardinalRealizedArcRecipe.Reason.NUMERIC_BUDGET, failure.reason());
        assertInstanceOf(NumericWorkExhaustedException.class, failure.cause().orElseThrow());
    }

    @Test void proposalInternalExhaustionRetainsNumericBudgetReasonAndOriginalStack() {
        var work = new StoredDyadic.Work();
        var initial = source(work);
        // prepareCandidate's entry charge succeeds; the next charge is inside the
        // real source proposal's StoredRealizationLimits.derive, not an entry guard.
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 1);
        var outcome = CardinalRealizedArcRecipe.prepareCandidate(initial.containment(), CardinalFrame.Y_POS,
                0x1.333331ffffffcp-1, work);
        var unavailable = assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class, outcome);
        assertEquals(CardinalRealizedArcRecipe.Reason.NUMERIC_BUDGET, unavailable.reason());
        var cause = assertInstanceOf(NumericWorkExhaustedException.class, unavailable.cause().orElseThrow());
        assertNull(cause.getCause(), "the original numeric exception must not be wrapped or replaced");
        assertTrue(Arrays.stream(cause.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(StoredRealizationLimits.class.getName()) && frame.getMethodName().equals("derive")));
        assertTrue(Arrays.stream(cause.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals(CardinalRealizedArcRecipe.class.getName()) && frame.getMethodName().equals("proposal")));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void defaultEntryUsesNominalIdentityForNonNativeSourceAndRejectsForeignWork() {
        var work = new StoredDyadic.Work();
        var dimensions = new PlayerBodyShape.Dimensions(.75, 1.75, 1.5);
        var placement = new PlayerBodyPlacement(new Vec3d(40, 88, 2),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 295),
                new UUID(0, 296), 0, 2), 7960, placement, new Vec3d(0, 0, 0));
        var source = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, dimensions, work));
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS, work));
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, prepared.path().fixedDomainProof());
        assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_IDENTITY,
                assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,
                        CardinalRealizedArcRecipe.prepare(source.containment(), CardinalFrame.Y_POS,
                                new StoredDyadic.Work())).reason());
    }
    @Test void fixedRelationRejectsEnlargementAndRecordsExactScalarIdentity() {
        var work = new StoredDyadic.Work();
        var center = new Vec3d(40.5, 88.89999997615814, 1.8999999761581416);
        var limits = StoredRealizationLimits.derive(center, CardinalFrame.Y_POS.attitude(), SIZE, work);
        var relation = RealizationRelation.fixedHalfLength(limits, .5999999642372128, work);
        assertEquals(.5999999642372128, relation.fixedHalfLength());
        assertNotEquals(relation, RealizationRelation.nominal(limits));
        assertNotEquals(relation, RealizationRelation.fixedHalfLength(limits, Math.nextUp(.5999999642372128), work));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.fixedHalfLength(limits,
                Math.nextUp(SIZE.centerlineHalfLength()), work));
        assertThrows(IllegalArgumentException.class,
                () -> RealizationRelation.fixedHalfLength(limits, .5, work));
    }

    private static CardinalContainedCapsuleSource.Prepared source(StoredDyadic.Work work) {
        var box = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1),
                new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833));
        var placement = PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(40.5, 88.59999996423721, 1.8999999761581416), CardinalFrame.Z_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, SIZE));
        var snapshot = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 195),
                new UUID(0, 196), 0, 2), 7960, placement, new Vec3d(0, 0, 0));
        return assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(snapshot, SIZE, work));
    }
}
