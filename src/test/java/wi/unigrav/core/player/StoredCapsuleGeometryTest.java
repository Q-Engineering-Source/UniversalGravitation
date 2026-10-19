package wi.unigrav.core.player;

import java.util.Set;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredCapsuleGeometryTest {
    private static final PlayerBodyShape.Dimensions CARDINAL_SIZE =
            new PlayerBodyShape.Dimensions(0.5, 2.0, 1.0);

    @Test
    void trustedEyeMetadataCopyRetainsEveryStoredPhysicalMemberAndStrictRecipe() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            int axis = nonZeroAxis(frame.up());
            int sign = component(frame.up(), axis) > 0 ? 1 : -1;
            var realized = realize(frame.attitude(), CARDINAL_SIZE, axis, sign, .125, Double.NaN);
            var original = realized.geometry();
            var placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(realized.center(), frame.attitude(),
                    new PlayerBodyShape.FreeCapsule(CARDINAL_SIZE), original, original.capsule().bounds(),
                    new Vec3d(realized.center().x(), original.capsule().bounds().min().y(), realized.center().z()));
            long beforeEyeWork = realized.numeric().units();
            var changed = placement.withEyeMetadata(1.62F, realized.numeric());
            assertTrue(realized.numeric().units() > beforeEyeWork);
            assertTrue(changed.storedCapsuleGeometry().relation().limits().usesWork(realized.numeric()));
            var geometry = changed.storedCapsuleGeometry();
            assertSame(placement.center(), changed.center());
            assertSame(placement.attitude(), changed.attitude());
            assertSame(placement.bounds(), changed.bounds());
            assertSame(placement.transport(), changed.transport());
            assertSame(placement.freePhysicalFoot(), changed.freePhysicalFoot());
            assertSame(original.capsule(), geometry.capsule());
            assertSame(original.negativeEndpoint(), geometry.negativeEndpoint());
            assertSame(original.positiveEndpoint(), geometry.positiveEndpoint());
            assertSame(original.storedHalfLine(), geometry.storedHalfLine());
            assertSame(original.nominalHalfLine(), geometry.nominalHalfLine());
            assertEquals(Double.doubleToRawLongBits(original.radius()), Double.doubleToRawLongBits(geometry.radius()));
            assertEquals(original.relation().kind(), geometry.relation().kind());
            assertEquals(original.relation().modifiedAxis(), geometry.relation().modifiedAxis());
            assertEquals(original.relation().outwardSign(), geometry.relation().outwardSign());
            assertEquals(Double.doubleToRawLongBits(original.relation().negativeEndpointPlane()),
                    Double.doubleToRawLongBits(geometry.relation().negativeEndpointPlane()));
            assertEquals(1.62F, changed.shape().dimensions().eyeHeight());
            assertNotEquals(placement.eye(), changed.eye());
            assertTrue(geometry.matchesRecipe(changed.center(), changed.attitude(), changed.shape().dimensions()));
            assertFalse(geometry.matchesRecipe(changed.center(), changed.attitude(), CARDINAL_SIZE),
                    "strict matching still includes the independent eye metadata");
            assertThrows(IllegalArgumentException.class, () -> original.withEyeMetadata(
                    new PlayerBodyShape.Dimensions(Math.nextUp(CARDINAL_SIZE.width()), CARDINAL_SIZE.height(), 1.62F)));
        }
    }

    @Test
    void axisPlaneRealizationRetainsThePhysicalNegativeEndpointForAllAxesAndSigns() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            int axis = nonZeroAxis(frame.up());
            int sigma = component(frame.up(), axis) > 0 ? 1 : -1;
            AxisRealization realized = realize(frame.attitude(), CARDINAL_SIZE, axis, sigma, 0.125, Double.NaN);
            StoredCapsuleGeometry geometry = realized.geometry();
            assertEquals(0.125 + sigma * CARDINAL_SIZE.radius(),
                    component(geometry.negativeEndpoint(), axis));
            Vec3d physicalNegative = geometry.negativeEndpoint();
            Vec3d physicalPositive = geometry.positiveEndpoint();
            int physicalEndpointOrder = compareLexicographically(physicalNegative, physicalPositive);
            assertNotEquals(0, physicalEndpointOrder, "a non-spherical test capsule has distinct endpoints");
            boolean negativeEndpointIsCanonicalStart = physicalEndpointOrder < 0;
            Vec3d expectedCanonicalStart = negativeEndpointIsCanonicalStart ? physicalNegative : physicalPositive;
            Vec3d expectedCanonicalEnd = negativeEndpointIsCanonicalStart ? physicalPositive : physicalNegative;
            assertRawVectorEquals(expectedCanonicalStart, geometry.capsule().centerline().start());
            assertRawVectorEquals(expectedCanonicalEnd, geometry.capsule().centerline().end());
            assertEquals(negativeEndpointIsCanonicalStart, geometry.negativeEndpointCanonicalStart());
            double expectedNegativeEndpointParameter = negativeEndpointIsCanonicalStart ? 0.0 : 1.0;
            assertEquals(Double.doubleToRawLongBits(expectedNegativeEndpointParameter),
                    Double.doubleToRawLongBits(geometry.negativeEndpointCanonicalParameter()),
                    "axis=" + axis + ", sigma=" + sigma);
            assertTrue(geometry.capsule().bounds().min().x() < geometry.capsule().bounds().max().x());
            assertTrue(geometry.capsule().bounds().min().y() < geometry.capsule().bounds().max().y());
            assertTrue(geometry.capsule().bounds().min().z() < geometry.capsule().bounds().max().z());
            var placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(realized.center(), frame.attitude(),
                    new PlayerBodyShape.FreeCapsule(CARDINAL_SIZE), geometry, geometry.capsule().bounds(),
                    new Vec3d(realized.center().x(), geometry.capsule().bounds().min().y(), realized.center().z()));
            assertEquals(geometry.capsule(), CapsulePlacementMath.pose(placement).capsule());
        }
    }

    @Test
    void axisPlaneStoresCandidateCenterAgainstTheOriginalReferenceBand() {
        var dimensions = CARDINAL_SIZE;
        var attitude = Rotation3d.identity();
        Vec3d reference = new Vec3d(0.25, 1.125, 0.75);
        Vec3d candidate = new Vec3d(0.25, Math.nextUp(reference.y()), 0.75);
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(reference, attitude, dimensions, work);
        Vec3d halfLine = new Vec3d(limits.nominalHalfLine().x(), candidate.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, candidate, halfLine, work);
        StoredCapsuleGeometry geometry = StoredCapsuleGeometry.fromRecipe(
                candidate, attitude, dimensions, halfLine, relation);
        assertEquals(reference, relation.limits().centerReference());
        assertEquals(candidate, geometry.center());
        assertEquals(0.375, geometry.negativeEndpoint().y());
        long beforeTangentialFailure = work.units();
        Vec3d tangentiallyChangedCenter = new Vec3d(candidate.x(), candidate.y(), Math.nextUp(candidate.z()));
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, tangentiallyChangedCenter, halfLine, work));
        assertTrue(work.units() > beforeTangentialFailure);
        var placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(candidate, attitude,
                new PlayerBodyShape.FreeCapsule(dimensions), geometry, geometry.capsule().bounds(),
                new Vec3d(candidate.x(), geometry.capsule().bounds().min().y(), candidate.z()));
        assertEquals(geometry.capsule(), CapsulePlacementMath.pose(placement).capsule());
    }

    @Test
    void exactNominalFootTouchRemainsTheOnlyStoredRelationForAnUnchangedHalfLine() {
        Vec3d center = new Vec3d(0.25, 1.125, -0.5);
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(
                center, Rotation3d.identity(), CARDINAL_SIZE, work);
        Vec3d nominal = limits.nominalHalfLine();
        RealizationRelation nominalRelation = RealizationRelation.nominal(limits);
        StoredCapsuleGeometry geometry = StoredCapsuleGeometry.fromRecipe(
                center, Rotation3d.identity(), CARDINAL_SIZE, nominal, nominalRelation);
        assertEquals(0.375, geometry.negativeEndpoint().y());
        assertEquals(RealizationRelation.Kind.NOMINAL, geometry.relation().kind());
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, center, nominal, work));
        assertTrue(work.units() > limits.deriveWorkDelta());
    }

    @Test
    void acceptedFortyFiveDegreeCapsuleKeepsActualIdentityProxyTransportAndEyeAnchor() {
        var dimensions = new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F);
        var attitude = new Rotation3d(0, 0, 0.3826834323650898, 0.9238795325112867);
        assertRawVectorEquals(new Vec3d(Double.longBitsToDouble(0xBFE6A09E667F3BCDL),
                Double.longBitsToDouble(0x3FE6A09E667F3BCCL), Double.longBitsToDouble(0L)), attitude.basisY());
        Vec3d center = new Vec3d(0, Double.longBitsToDouble(0x3fe72d2bcfebf80aL), 0);
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, dimensions, work);
        assertEquals(0x3fdb27245fd7f013L,
                Double.doubleToRawLongBits(limits.nominalHalfLine().y()));
        Vec3d storedHalfLine = new Vec3d(limits.nominalHalfLine().x(),
                Double.longBitsToDouble(0x3fdb27245fd7f014L), limits.nominalHalfLine().z());
        assertRawVectorEquals(new Vec3d(Double.longBitsToDouble(0xBFDB27245FD7F015L),
                Double.longBitsToDouble(0x3FDB27245FD7F014L), Double.longBitsToDouble(0L)), storedHalfLine);
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, 1, 1, 0, center, storedHalfLine, work);
        StoredCapsuleGeometry geometry = StoredCapsuleGeometry.fromRecipe(
                center, attitude, dimensions, storedHalfLine, relation);

        assertRawVectorEquals(new Vec3d(Double.longBitsToDouble(0x3FDB27245FD7F015L),
                Double.longBitsToDouble(0x3FD3333340000000L), Double.longBitsToDouble(0L)),
                geometry.negativeEndpoint());

        assertEquals(0x3fdb27245fd7f014L,
                Double.doubleToRawLongBits(geometry.storedHalfLine().y()));
        assertEquals(0x3fd3333340000000L,
                Double.doubleToRawLongBits(geometry.negativeEndpoint().y()));
        assertFalse(geometry.negativeEndpointCanonicalStart(),
                "the canonical segment start is the physical head for this 45 degree capsule");
        assertEquals(1.0, geometry.negativeEndpointCanonicalParameter());

        Aabb actualProxy = expanded(geometry.capsule().bounds(), 0.125);
        Vec3d actualTransport = new Vec3d(center.x() + 0.25, actualProxy.min().y(), center.z() - 0.25);
        var accepted = PlayerBodyPlacement.fromAcceptedFreeGeometry(center, attitude,
                new PlayerBodyShape.FreeCapsule(dimensions), geometry, actualProxy, actualTransport);
        var nominal = new PlayerBodyPlacement(center, attitude, new PlayerBodyShape.FreeCapsule(dimensions));
        assertEquals(geometry.capsule(), accepted.capsule());
        assertEquals(actualProxy, accepted.bounds());
        assertEquals(actualTransport, accepted.transport());
        assertRawVectorEquals(new Vec3d(Double.longBitsToDouble(0x3FE45D5B53324343L),
                Double.longBitsToDouble(0x3FB67E83E5CDA63FL), Double.longBitsToDouble(0L)),
                accepted.freePhysicalFoot());
        assertRawVectorEquals(new Vec3d(Double.longBitsToDouble(0xBFE04AAF87DBE7BAL),
                Double.longBitsToDouble(0x3FF3BBEDABE3EFE2L), Double.longBitsToDouble(0L)), accepted.eye());
        assertEquals(0, accepted.translationTo(nominal).lengthSquared());
        assertNotEquals(nominal, accepted);
        assertEquals(2, Set.of(nominal, accepted).size());

        StoredDyadic.Work poseWork = new StoredDyadic.Work();
        var pose = CapsulePlacementMath.pose(accepted, poseWork);
        assertEquals(geometry.capsule(), pose.capsule());
        assertEquals(geometry.storedHalfLine(), poseHalfLine(pose));
        assertTrue(pose.numericWorkDelta() > 0);
        assertEquals(pose.numericWorkDelta(), poseWork.units());
        var envelope = CapsulePlacementMath.rotationEnvelope(accepted,
                dimensions.centerlineHalfLength(), dimensions.radius());
        assertTrue(pose.actualRadius() <= envelope.queryCapsule().radius());
        assertTrue(envelope.numericWorkDelta() > 0);
    }

    @Test
    void generatedBoundAtThirtyMillionPermitsMoreThanOneHalfLineUlpAndRejectsOutOfBandCenters() {
        var dimensions = new PlayerBodyShape.Dimensions(0.5, 2.0, 1.0);
        double supportCoordinate = 29_999_999.0;
        double nominalFoot = supportCoordinate + dimensions.radius();
        double center = Math.nextUp(30_000_000.0);
        AxisRealization realized = realize(Rotation3d.identity(), dimensions, 1, 1,
                supportCoordinate, center);
        double nominal = realized.limits().nominalHalfLine().y();
        double actual = realized.geometry().storedHalfLine().y();
        assertTrue(actual - nominal > Math.nextUp(nominal) - nominal,
                "the endpoint-derived finite bound permits the observed multi-ULP stored change");
        StoredDyadic.Work compareWork = new StoredDyadic.Work();
        StoredDyadic exactDifference = StoredDyadic.of(actual, compareWork)
                .subtract(StoredDyadic.of(nominal, compareWork), compareWork).abs(compareWork);
        assertTrue(exactDifference.compare(realized.limits().halfLineBound(1), compareWork) <= 0);
        assertEquals(nominalFoot, realized.geometry().negativeEndpoint().y());

        Vec3d badCenter = new Vec3d(0, 30_000_000.0 + 4.0 * Math.ulp(30_000_000.0), 0);
        StoredDyadic.Work headWork = new StoredDyadic.Work();
        Vec3d headSideCenter = new Vec3d(0, Math.nextDown(30_000_000.0), 0);
        StoredRealizationLimits headLimits = StoredRealizationLimits.derive(
                headSideCenter, Rotation3d.identity(), dimensions, headWork);
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                headLimits, 1, 1, supportCoordinate, headSideCenter, headLimits.nominalHalfLine(), headWork));
        StoredDyadic.Work badWork = new StoredDyadic.Work();
        StoredRealizationLimits badLimits = StoredRealizationLimits.derive(
                badCenter, Rotation3d.identity(), dimensions, badWork);
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                badLimits, 1, 1, supportCoordinate, badCenter, badLimits.nominalHalfLine(), badWork));
        assertTrue(badWork.units() > badLimits.deriveWorkDelta(),
                "a rejected exact proof attempt remains visible in the same caller-owned budget");
    }

    @Test
    void fixedHalfLinePrefixTranslationKeepsOriginalProofAcrossBinadeEvenWhenNewBoundShrinks() {
        var dimensions = new PlayerBodyShape.Dimensions(0.5, 2.0, 1.0);
        double boundary = 0x1.0p24;
        double supportCoordinate = boundary - 1.0 - 0x1.0p-29;
        double acceptedCenter = Math.nextUp(boundary);
        AxisRealization initial = realize(Rotation3d.identity(), dimensions, 1, 1,
                supportCoordinate, acceptedCenter);
        // Keep the center and both stored endpoints below 2^24 so the new local ULP bound shrinks.
        Vec3d translatedCenter = new Vec3d(0, boundary - 1.0, 0);
        StoredDyadic.Work lowerWork = new StoredDyadic.Work();
        StoredRealizationLimits lowerLimits = StoredRealizationLimits.derive(
                translatedCenter, Rotation3d.identity(), dimensions, lowerWork);
        StoredDyadic.Work comparison = new StoredDyadic.Work();
        StoredDyadic storedDelta = StoredDyadic.of(initial.geometry().storedHalfLine().y(), comparison)
                .subtract(StoredDyadic.of(initial.limits().nominalHalfLine().y(), comparison), comparison).abs(comparison);
        assertTrue(storedDelta.compare(lowerLimits.halfLineBound(1), comparison) > 0,
                "the translated recipe must retain a valid old proof even though a fresh local bound is smaller");

        var accepted = PlayerBodyPlacement.fromAcceptedFreeGeometry(initial.center(), Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(dimensions), initial.geometry(),
                initial.geometry().capsule().bounds(),
                new Vec3d(initial.center().x(), initial.geometry().capsule().bounds().min().y(), initial.center().z()));
        Vec3d delta = translatedCenter.subtract(accepted.center());
        var tube = CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(accepted), delta);
        var translatedPose = CapsulePlacementMath.atPrefix(tube, 1);
        StoredCapsuleGeometry translatedGeometry = translatedPose.placement().storedCapsuleGeometry();
        assertEquals(initial.geometry().relation(), translatedGeometry.relation());
        assertEquals(initial.geometry().storedHalfLine(), translatedGeometry.storedHalfLine());
        assertEquals(translatedCenter, translatedGeometry.center());
        assertEquals(translatedGeometry.capsule(), translatedPose.capsule());
    }

    @Test
    void exactDyadicKeepsLambdaHalfAndSharedProofWorkChargesFailures() {
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredDyadic lambdaHalf = StoredDyadic.lambdaHalf(work);
        assertFalse(lambdaHalf.isExactlyRepresentable(work));
        assertEquals(0.0, lambdaHalf.roundDown(work));
        assertEquals(Double.MIN_VALUE, lambdaHalf.roundUp(work));

        var dimensions = CARDINAL_SIZE;
        var attitude = Rotation3d.identity();
        Vec3d center = new Vec3d(0, 1.125, 0);
        StoredDyadic.Work shared = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, dimensions, shared);
        long afterDerive = shared.units();
        Vec3d validHalfLine = new Vec3d(limits.nominalHalfLine().x(), 0.625, limits.nominalHalfLine().z());
        Vec3d wrongOtherAxis = new Vec3d(Math.nextUp(limits.nominalHalfLine().x()),
                validHalfLine.y(), limits.nominalHalfLine().z());
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, center, wrongOtherAxis, shared));
        assertTrue(shared.units() > afterDerive);

        StoredDyadic.Work wrongBudget = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                limits, 1, 1, 0.125, center, validHalfLine, wrongBudget));
        assertTrue(wrongBudget.units() > 0);
    }

    @Test
    void sphereAndStrictUnrepresentableDomainsPreserveRawNominalZerosAndFailClosed() {
        var sphereDimensions = new PlayerBodyShape.Dimensions(
                2 * Double.MIN_VALUE, 2 * Double.MIN_VALUE, 0);
        var attitude = new Rotation3d(-0.0, 1, -0.0, Double.MIN_NORMAL);
        Vec3d center = new Vec3d(0, 0, 0);
        var sphere = new PlayerBodyPlacement(center, attitude, new PlayerBodyShape.FreeCapsule(sphereDimensions));
        Vec3d nominal = sphere.storedCapsuleGeometry().nominalHalfLine();
        Vec3d expectedNominal = attitude.basisY().multiply(0);
        assertEquals(Double.doubleToRawLongBits(expectedNominal.x()), Double.doubleToRawLongBits(nominal.x()));
        assertEquals(Double.doubleToRawLongBits(expectedNominal.y()), Double.doubleToRawLongBits(nominal.y()));
        assertEquals(Double.doubleToRawLongBits(expectedNominal.z()), Double.doubleToRawLongBits(nominal.z()));
        assertEquals(center, sphere.capsule().centerline().start());
        assertEquals(center, sphere.capsule().centerline().end());
        assertThrows(IllegalArgumentException.class, () -> StoredRealizationLimits.derive(
                new Vec3d(0x1.0p25, 0, 0), Rotation3d.identity(), CARDINAL_SIZE));
        StoredDyadic.Work invalidDeriveWork = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> StoredRealizationLimits.derive(
                new Vec3d(0x1.0p25, 0, 0), Rotation3d.identity(), CARDINAL_SIZE, invalidDeriveWork));
        assertTrue(invalidDeriveWork.units() > 0,
                "a failed shared derive leaves its work visible to the caller");
        assertThrows(IllegalArgumentException.class, () -> StoredRealizationLimits.derive(
                new Vec3d(0, 0, 0), Rotation3d.identity(), new PlayerBodyShape.Dimensions(2, 1, 1)));
        StoredDyadic.Work zeroAxisWork = new StoredDyadic.Work();
        Vec3d axisCenter = new Vec3d(0.125, 1.125, 0.25);
        StoredRealizationLimits zeroAxisLimits = StoredRealizationLimits.derive(
                axisCenter, Rotation3d.identity(), CARDINAL_SIZE, zeroAxisWork);
        Vec3d changedZeroAxis = new Vec3d(Double.MIN_VALUE,
                zeroAxisLimits.nominalHalfLine().y(), zeroAxisLimits.nominalHalfLine().z());
        assertThrows(IllegalArgumentException.class, () -> RealizationRelation.axisPlane(
                zeroAxisLimits, 0, 1, -0.125, axisCenter, changedZeroAxis, zeroAxisWork));
        assertTrue(zeroAxisWork.units() > zeroAxisLimits.deriveWorkDelta());
    }

    private static AxisRealization realize(Rotation3d attitude, PlayerBodyShape.Dimensions dimensions,
                                           int axis, int sigma, double supportCoordinate, double centerAxis) {
        StoredRealizationLimits nominal = StoredRealizationLimits.derive(
                new Vec3d(0.25, -0.5, 0.75), attitude, dimensions);
        double plane = supportCoordinate + sigma * dimensions.radius();
        double nominalAxis = component(nominal.nominalHalfLine(), axis);
        double centerStar = plane + nominalAxis;
        if (Double.isNaN(centerAxis)) {
            centerAxis = Math.nextAfter(centerStar, sigma > 0
                    ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
        }
        Vec3d center = withComponent(new Vec3d(0.25, -0.5, 0.75), axis, centerAxis);
        Vec3d halfLine = withComponent(nominal.nominalHalfLine(), axis,
                component(center, axis) - plane);
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, dimensions, work);
        RealizationRelation relation = RealizationRelation.axisPlane(
                limits, axis, sigma, supportCoordinate, center, halfLine, work);
        return new AxisRealization(center, centerStar, limits,
                StoredCapsuleGeometry.fromRecipe(center, attitude, dimensions, halfLine, relation), work);
    }

    private static int nonZeroAxis(Vec3d vector) {
        for (int axis = 0; axis < 3; axis++) if (component(vector, axis) != 0) return axis;
        throw new AssertionError("cardinal frame has no up axis");
    }

    private static int compareLexicographically(Vec3d first, Vec3d second) {
        int comparison = Double.compare(first.x(), second.x());
        if (comparison != 0) return comparison;
        comparison = Double.compare(first.y(), second.y());
        return comparison != 0 ? comparison : Double.compare(first.z(), second.z());
    }

    private static void assertRawVectorEquals(Vec3d expected, Vec3d actual) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()));
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()));
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()));
    }

    private static Aabb expanded(Aabb bounds, double amount) {
        Vec3d delta = new Vec3d(amount, amount, amount);
        return new Aabb(bounds.min().subtract(delta), bounds.max().add(delta));
    }

    private static Vec3d withComponent(Vec3d vector, int axis, double value) {
        return switch (axis) {
            case 0 -> new Vec3d(value, vector.y(), vector.z());
            case 1 -> new Vec3d(vector.x(), value, vector.z());
            case 2 -> new Vec3d(vector.x(), vector.y(), value);
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2");
        };
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            case 2 -> vector.z();
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2");
        };
    }

    private static Vec3d poseHalfLine(CapsulePlacementMath.Pose pose) {
        return pose.placement().storedCapsuleGeometry().storedHalfLine();
    }

    private record AxisRealization(Vec3d center, double centerStar, StoredRealizationLimits limits,
                                   StoredCapsuleGeometry geometry, StoredDyadic.Work numeric) {
    }
}
