package wi.unigrav.core.player;

import java.math.BigDecimal;

import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleResizeEnclosureTest {
    private static final Vec3d CENTER = new Vec3d(.25, 1.125, .75);
    private static final PlayerBodyShape.Dimensions SMALL = new PlayerBodyShape.Dimensions(.5000000000000001, 2, 1);

    @Test void growShrinkCenterAndEyeUseExactAffineLastRnWithLogicalDoubleIdentity() {
        var q = Rotation3d.identity();
        var larger = new PlayerBodyShape.Dimensions(.9000000000000001, 2.75, 3);
        var source = placement(CENTER, q, SMALL);
        var target = placement(new Vec3d(.75, 2.25, -.25), q, larger);
        var work = new StoredDyadic.Work();
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        assertSame(source.storedCapsuleGeometry(), path.sampleLegal(0, work).geometry());
        assertSame(target.storedCapsuleGeometry(), path.sampleLegal(1, work).geometry());
        assertEquals(CapsuleResizeEnclosure.SampleKind.ACTUAL_START, path.sampleLegal(0, work).kind());
        assertEquals(CapsuleResizeEnclosure.SampleKind.ACTUAL_TARGET, path.sampleLegal(1, work).kind());
        assertEquals(Double.doubleToRawLongBits(larger.width()),
                Double.doubleToRawLongBits(path.sampleLegal(1, work).dimensions().width()));
        assertNotEquals((double)(float)larger.width(), path.sampleLegal(1, work).dimensions().width());
        var mid = path.sampleLegal(.5, work);
        assertEquals(CapsuleResizeEnclosure.SampleKind.NOMINAL_INTERIOR, mid.kind());
        assertEquals(RealizationRelation.Kind.NOMINAL, mid.geometry().relation().kind());
        assertSame(q, mid.attitude());
        assertEquals(expectedCenter(source, target, .5), mid.center());
        assertEquals(expectedDimensions(source, target, .5), mid.dimensions());
        var cover = path.encloseClosedInterval(.25, .75, work);
        assertEquals(mid.capsule(), cover.reference());
        assertTrue(cover.uses(work));
        crossCheck(path, source, target, .25, .75, work);
        var shrinkWork = new StoredDyadic.Work();
        var shrink = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(target, shrinkWork), source, shrinkWork);
        crossCheck(shrink, target, source, 0, 1, shrinkWork);
        System.out.println("RESIZE_WORK prepare=" + path.numericWorkDelta() + " interval=" + cover.numericWorkDelta()
                + " cumulative=" + work.units());
    }

    @Test void coupledHeightWidthGapGivesTighterCoverInsideTheExistingFinitePoseProfile() {
        var source = placement(CENTER, Rotation3d.identity(), new PlayerBodyShape.Dimensions(.5, 2, 1));
        var target = placement(CENTER, source.attitude(), new PlayerBodyShape.Dimensions(60, 120, 150));
        assertTrue((120 - .5) / 2 > 30, "independent Hmax-Wmin is wider than actual maximum h=30");
        var work = new StoredDyadic.Work();
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        var sample = path.sampleLegal(.5, work);
        assertTrue(sample.dimensions().height() >= sample.dimensions().width());
        assertTrue(sample.dimensions().centerlineHalfLength() < 64);
        assertEquals(expectedDimensions(source, target, .5), sample.dimensions());
        var cover = path.encloseClosedInterval(0, 1, work);
        assertTrue(cover.padded().radius() < 50, "correlated gap avoids the independent interval's >74 radius");
        assertCovered(cover, source.capsule());
        assertCovered(cover, target.capsule());
    }

    @Test void equalHeightWidthSphereAndIndependentEyeStayLegalThroughout() {
        var q = new Rotation3d(.2, .3, .4, .5);
        var source = placement(CENTER, q, new PlayerBodyShape.Dimensions(.5, .5, 4));
        var target = placement(new Vec3d(.75, 2, 1), q, new PlayerBodyShape.Dimensions(1.25, 1.25, .1));
        var work = new StoredDyadic.Work();
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        for (double t : new double[] {.125, .5, .875}) {
            var sample = path.sampleLegal(t, work);
            assertEquals(sample.dimensions().width(), sample.dimensions().height());
            assertEquals(0, sample.dimensions().centerlineHalfLength());
            assertEquals(sample.center(), sample.geometry().negativeEndpoint());
            assertEquals(sample.center(), sample.geometry().positiveEndpoint());
        }
        crossCheck(path, source, target, 0, 1, work);
    }

    @Test void tiltedFixedRawQuaternionUsesActualBasisAndNeverRenormalizes() {
        var q = new Rotation3d(.2, .3, .4, .5);
        var source = placement(CENTER, q, SMALL);
        var target = placement(new Vec3d(.1, 1.3, .9), q, new PlayerBodyShape.Dimensions(.8, 2.3, .6));
        var work = new StoredDyadic.Work();
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        var sample = path.sampleLegal(.375, work);
        assertSame(q, sample.attitude());
        assertTrue(StoredRealizationLimits.sameRaw(q, sample.geometry().attitude()));
        Vec3d hs = q.basisY().multiply(sample.dimensions().centerlineHalfLength());
        assertEquals(hs, sample.geometry().storedHalfLine());
        assertEquals(sample.center().subtract(hs), sample.geometry().negativeEndpoint());
        assertEquals(sample.center().add(hs), sample.geometry().positiveEndpoint());
        crossCheck(path, source, target, .2, .6, work);
    }

    @Test void actualAxisPlaneEndpointsAndCanonicalPhysicalDirectionRemainDistinctFromInteriorNominal() {
        var dimensions0 = new PlayerBodyShape.Dimensions(.5, 2, 1);
        var dimensions1 = new PlayerBodyShape.Dimensions(.5, 1.5, .75);
        var work = new StoredDyadic.Work();
        var source = axisPlane(1.125, dimensions0, work);
        var target = axisPlane(.875, dimensions1, work);
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        assertEquals(RealizationRelation.Kind.AXIS_PLANE, path.sampleLegal(0, work).geometry().relation().kind());
        assertEquals(RealizationRelation.Kind.AXIS_PLANE, path.sampleLegal(1, work).geometry().relation().kind());
        var mid = path.sampleLegal(.5, work);
        assertEquals(RealizationRelation.Kind.NOMINAL, mid.geometry().relation().kind());
        assertEquals(expectedDimensions(source, target, .5), mid.dimensions());
        var cover = path.encloseClosedInterval(0, 1, work);
        assertCovered(cover, source.capsule());
        assertCovered(cover, target.capsule());
        assertCovered(cover, mid.capsule());
        var inverted = new Rotation3d(1, 0, 0, 0);
        var first = placement(CENTER, inverted, dimensions0);
        var last = placement(CENTER, inverted, dimensions1);
        assertEquals(first.storedCapsuleGeometry().positiveEndpoint(), first.capsule().centerline().start());
        var invertedWork = new StoredDyadic.Work();
        var invertedPath = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(first, invertedWork), last, invertedWork);
        crossCheck(invertedPath, first, last, 0, 1, invertedWork);
    }

    @Test void internalUnrepresentableHalfLengthIsNotSilentlyClassifiedAsSphere() {
        double boundary = 0x1p-1021;
        var sourceDimensions = new PlayerBodyShape.Dimensions(Math.nextDown(Math.nextDown(boundary)), boundary, 0);
        var targetDimensions = new PlayerBodyShape.Dimensions(boundary, Math.nextUp(boundary), 0);
        assertTrue(sourceDimensions.centerlineHalfLength() > 0);
        assertTrue(targetDimensions.centerlineHalfLength() > 0);
        var source = placement(CENTER, Rotation3d.identity(), sourceDimensions);
        var target = placement(CENTER, source.attitude(), targetDimensions);
        var actualMid = expectedDimensions(source, target, .5);
        assertTrue(actualMid.height() > actualMid.width());
        assertEquals(0, actualMid.centerlineHalfLength());
        var work = new StoredDyadic.Work();
        var cause = assertThrows(IllegalArgumentException.class,
                () -> StoredRealizationLimits.derive(CENTER, source.attitude(), actualMid, work));
        assertTrue(cause.getMessage().contains("outside stored realization profile"));
        var failure = assertThrows(CapsuleResizeEnclosure.ProfileNotCertifiedException.class,
                () -> CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work));
        assertEquals(CapsuleResizeEnclosure.ProfileReason.POSITIVE_HALF_LENGTH, failure.reason());
        assertTrue(failure.getMessage().contains("PROFILE_NOT_CERTIFIED"));
        assertTrue(work.units() > 0);
    }

    @Test void foreignWorkQuaternionDriftAndPartialExhaustionFailWithoutFreshBudget() {
        var source = placement(CENTER, Rotation3d.identity(), SMALL);
        var target = placement(CENTER, source.attitude(), new PlayerBodyShape.Dimensions(.7, 2.5, .9));
        var work = new StoredDyadic.Work();
        var path = CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
        var foreign = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> path.sampleLegal(.5, foreign));
        assertThrows(IllegalArgumentException.class, () -> path.encloseClosedInterval(0, 1, foreign));
        assertEquals(2, foreign.units());
        assertThrows(IllegalArgumentException.class, () -> path.sampleLegal(Double.NaN, work));
        assertThrows(IllegalArgumentException.class, () -> path.encloseClosedInterval(.75, .25, work));
        assertThrows(IllegalArgumentException.class, () -> CapsuleResizeEnclosure.prepare(
                CapsulePlacementMath.pose(source, work), placement(CENTER, new Rotation3d(.1, 0, 0, 1), SMALL), work));
        long before = work.units();
        work.charge(StoredDyadic.MAX_WORK_UNITS - before - 25);
        var original = assertThrows(NumericWorkExhaustedException.class, () -> path.sampleLegal(.5, work));
        assertNotNull(original.getStackTrace());
        assertTrue(work.units() > StoredDyadic.MAX_WORK_UNITS - 25, "partial failure retains successful prior charges");
        assertTrue(work.units() <= StoredDyadic.MAX_WORK_UNITS);
    }

    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d q, PlayerBodyShape.Dimensions dimensions) {
        // Fixture geometry exists before the measured transaction. prepare observes
        // both actual endpoints with the supplied same Work; no hidden fixture fee claim.
        return new PlayerBodyPlacement(center, q, new PlayerBodyShape.FreeCapsule(dimensions));
    }
    private static PlayerBodyPlacement axisPlane(double referenceY, PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work work) {
        var q = Rotation3d.identity();
        var reference = new Vec3d(.25, referenceY, .75);
        var center = new Vec3d(.25, Math.nextUp(referenceY), .75);
        var limits = StoredRealizationLimits.derive(reference, q, dimensions, work);
        var hs = new Vec3d(0, center.y() - .375, 0);
        var relation = RealizationRelation.axisPlane(limits, 1, 1, .125, center, hs, work);
        var stored = StoredCapsuleGeometry.fromRecipe(center, q, dimensions, hs, relation);
        return PlayerBodyPlacement.fromAcceptedFreeGeometry(center, q, new PlayerBodyShape.FreeCapsule(dimensions),
                stored, stored.capsule().bounds(), new Vec3d(center.x(), stored.capsule().bounds().min().y(), center.z()));
    }
    private static double affine(double a, double b, double t) {
        BigDecimal exactT = new BigDecimal(t);
        return new BigDecimal(a).add(new BigDecimal(b).subtract(new BigDecimal(a)).multiply(exactT)).doubleValue();
    }
    private static Vec3d expectedCenter(PlayerBodyPlacement a, PlayerBodyPlacement b, double t) {
        return new Vec3d(affine(a.center().x(), b.center().x(), t), affine(a.center().y(), b.center().y(), t),
                affine(a.center().z(), b.center().z(), t));
    }
    private static PlayerBodyShape.Dimensions expectedDimensions(PlayerBodyPlacement a, PlayerBodyPlacement b, double t) {
        var x = a.shape().dimensions(); var y = b.shape().dimensions();
        return new PlayerBodyShape.Dimensions(affine(x.width(), y.width(), t), affine(x.height(), y.height(), t),
                affine(x.eyeHeight(), y.eyeHeight(), t));
    }
    private static void crossCheck(CapsuleResizeEnclosure.Path path, PlayerBodyPlacement a, PlayerBodyPlacement b,
                                   double lo, double hi, StoredDyadic.Work work) {
        var cover = path.encloseClosedInterval(lo, hi, work);
        // Finite points cross-check actual construction only. Monotone affine/RN
        // ranges + corresponding endpoint balls certify the whole closed hull.
        for (int i = 0; i <= 8; i++) {
            double t = lo + (hi - lo) * (i / 8.0);
            var sample = path.sampleLegal(t, work);
            assertEquals(expectedCenter(a, b, t), sample.center());
            assertEquals(expectedDimensions(a, b, t), sample.dimensions());
            assertTrue(sample.dimensions().height() >= sample.dimensions().width());
            assertCovered(cover, sample.capsule());
        }
    }
    private static void assertCovered(CapsuleResizeEnclosure.TransitionCover cover, Capsule actual) {
        BigDecimal extra = new BigDecimal(cover.padded().radius()).subtract(new BigDecimal(actual.radius()));
        assertTrue(extra.signum() >= 0);
        for (Vec3d endpoint : new Vec3d[] {actual.centerline().start(), actual.centerline().end()}) {
            BigDecimal closest = null;
            for (Vec3d reference : new Vec3d[] {cover.reference().centerline().start(), cover.reference().centerline().end()}) {
                BigDecimal squared = BigDecimal.ZERO;
                for (int axis = 0; axis < 3; axis++) {
                    var difference = new BigDecimal(component(endpoint, axis)).subtract(new BigDecimal(component(reference, axis)));
                    squared = squared.add(difference.multiply(difference));
                }
                if (closest == null || squared.compareTo(closest) < 0) closest = squared;
            }
            assertTrue(closest.compareTo(extra.multiply(extra)) <= 0, "actual endpoint escaped closed corresponding segment cover");
            for (int axis = 0; axis < 3; axis++) {
                var coordinate = new BigDecimal(component(endpoint, axis));
                var radius = new BigDecimal(actual.radius());
                assertTrue(new BigDecimal(component(cover.bounds().min(), axis)).compareTo(coordinate.subtract(radius)) <= 0);
                assertTrue(new BigDecimal(component(cover.bounds().max(), axis)).compareTo(coordinate.add(radius)) >= 0);
            }
        }
    }
    private static double component(Vec3d vector, int axis) { return StoredRealizationLimits.component(vector, axis); }
}
