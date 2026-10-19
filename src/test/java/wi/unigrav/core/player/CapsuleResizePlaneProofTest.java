package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleResizePlaneProofTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.5, 2, 1.5);
    private static final Rotation3d[] AXES = {
        new Rotation3d(.5, .5, -.5, .5), new Rotation3d(.5, -.5, .5, .5),
        Rotation3d.identity(), new Rotation3d(1, 0, 0, 0),
        new Rotation3d(.5, .5, .5, .5), new Rotation3d(.5, .5, -.5, -.5)
    };

    @Test void correlatedNativeShrinkCertifiesWithoutChangingRecipeAndRejectsOddTie() {
        for (double shift : new double[] {0, 0x1p-46}) {
            var work = new StoredDyadic.Work();
            double h0 = (double) 1.8F, h1 = (double) 1.7F, w = (double) .6F;
            var source = placement(new Vec3d(8.5, 65 + h0 / 2 + shift, 8.5), Rotation3d.identity(),
                    new PlayerBodyShape.Dimensions(w, h0, (double) 1.62F));
            var target = placement(new Vec3d(8.5, 65 + h1 / 2 + shift, 8.5), Rotation3d.identity(),
                    new PlayerBodyShape.Dimensions(w, h1, 1.5));
            var path = prepare(source, target, work);
            var result = CapsuleResizePlaneProof.certify(path, 1, 1, 65 + shift, 0, 1, work);
            if (shift == 0) {
                var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class, result);
                assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_CORRELATED_RN_LATTICE, proof.method());
                for (double t : new double[] {Math.nextDown(.5), .5, Math.nextUp(.5), 0x1p-16})
                    assertTrue(actualMargin(path.sampleLegal(t, work).geometry(), 1, 1, 65, work).signum() >= 0);
            } else if (result instanceof CapsuleResizePlaneProof.Certificate proof) {
                assertNotEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_CORRELATED_RN_LATTICE, proof.method());
            }
        }
    }

    @Test void sixActualRawAxesCertifyConstantProfileTouchAndOutwardTravel() {
        for (int index = 0; index < 6; index++) {
            int axis = index / 2, sign = index % 2 == 0 ? 1 : -1;
            var q = AXES[index];
            assertEquals(sign, component(q.basisY(), axis));
            for (int i = 0; i < 3; i++) if (i != axis) assertEquals(0, component(q.basisY(), i));
            var source = placement(onAxis(axis, sign), q, SIZE);
            var target = placement(onAxis(axis, sign * 1.25), q, SIZE);
            var work = new StoredDyadic.Work();
            var path = prepare(source, target, work);
            var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                    CapsuleResizePlaneProof.certify(path, axis, sign, 0, 0, 1, work));
            assertTrue(proof.exactAxis());
            assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_RN_THRESHOLD, proof.method());
            assertEquals(0, proof.marginLower().signum());
            assertTrue(proof.includesActualStart());
            assertTrue(proof.includesActualTarget());
            assertTrue(proof.matches(path, axis, sign, 0, 0, 1, work));
            crossCheck(path, axis, sign, 0, work);
        }
    }

    @Test void constantCenterShrinkAndEqualSphereAvoidBlanketNegativeEpsilon() {
        var work = new StoredDyadic.Work();
        var q = Rotation3d.identity();
        var source = placement(new Vec3d(0, 1, 0), q, SIZE);
        var target = placement(source.center(), q, new PlayerBodyShape.Dimensions(.5, 1, .3));
        var path = prepare(source, target, work);
        assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(path, 1, 1, 0, 0, 1, work));
        crossCheck(path, 1, 1, 0, work);
        var sphere = placement(new Vec3d(0, .5, 0), new Rotation3d(1, 2, 3, 4),
                new PlayerBodyShape.Dimensions(1, 1, 3));
        var spherePath = prepare(sphere, sphere, work);
        var sphereProof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(spherePath, 1, 1, 0, 0, 1, work));
        assertFalse(sphereProof.exactAxis());
        assertEquals(0, sphereProof.marginLower().signum());
        assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.RECIPE_INTERVAL, sphereProof.method());
        var nonrepresentable = placement(new Vec3d(0, 1.1, 0), q, SIZE);
        var nonrepresentablePath = prepare(nonrepresentable, nonrepresentable, work);
        var exactSum = StoredDyadic.of(.1, work).add(StoredDyadic.of(.25, work), work);
        assertFalse(exactSum.isExactlyRepresentable(work));
        var thresholdProof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(nonrepresentablePath, 1, 1, .1, 0, 1, work));
        assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_RN_THRESHOLD, thresholdProof.method());
        assertTrue(thresholdProof.marginLower().signum() > 0);

    }

    @Test void tiltAndTinyTiltUseActualEndpointIntervalsWithoutSnapping() {
        for (var q : new Rotation3d[] {new Rotation3d(1, 2, 3, 4), new Rotation3d(1e-8, 0, 0, 1)}) {
            var work = new StoredDyadic.Work();
            var source = placement(new Vec3d(0, 0, 0), q, SIZE);
            var target = placement(new Vec3d(.125, .25, .125), q, new PlayerBodyShape.Dimensions(.75, 2.5, 1.7));
            var path = prepare(source, target, work);
            var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                    CapsuleResizePlaneProof.certify(path, 1, 1, -10, 0, 1, work));
            assertFalse(proof.exactAxis());
            assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.RECIPE_INTERVAL, proof.method());
            assertSame(q, path.sampleLegal(.25, work).attitude());
            crossCheck(path, 1, 1, -10, work);
        }
    }

    @Test void nonnominalActualEndpointsAndCanonicalFlipAreIndividuallyIncluded() {
        var work = new StoredDyadic.Work();
        var q = Rotation3d.identity();
        var reference = new Vec3d(.25, 1.125, .75);
        var center = new Vec3d(.25, Math.nextUp(1.125), .75);
        var limits = StoredRealizationLimits.derive(reference, q, SIZE, work);
        var hs = new Vec3d(0, center.y() - .375, 0);
        var relation = RealizationRelation.axisPlane(limits, 1, 1, .125, center, hs, work);
        var stored = StoredCapsuleGeometry.fromRecipe(center, q, SIZE, hs, relation);
        var source = PlayerBodyPlacement.fromAcceptedFreeGeometry(center, q, new PlayerBodyShape.FreeCapsule(SIZE),
                stored, stored.capsule().bounds(), new Vec3d(center.x(), stored.capsule().bounds().min().y(), center.z()));
        var path = prepare(source, source, work);
        assertEquals(RealizationRelation.Kind.AXIS_PLANE, path.sampleLegal(0, work).geometry().relation().kind());
        assertEquals(RealizationRelation.Kind.NOMINAL, path.sampleLegal(.5, work).geometry().relation().kind());
        var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(path, 1, 1, .125, 0, 1, work));
        assertEquals(0, proof.marginLower().signum());
        assertTrue(proof.includesActualStart() && proof.includesActualTarget());
        var inverted = placement(new Vec3d(0, 1, 0), new Rotation3d(1, 0, 0, 0), SIZE);
        assertEquals(inverted.storedCapsuleGeometry().positiveEndpoint(), inverted.capsule().centerline().start());
        var invertedPath = prepare(inverted, inverted, work);
        assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(invertedPath, 1, 1, 0, 0, 1, work));
    }

    @Test void affineFootGrowThresholdIsStrictlySufficientAndNeverAnOverlapVerdict() {
        var work = new StoredDyadic.Work();
        var q = Rotation3d.identity();
        var source = placement(new Vec3d(0, 65, 0), q, SIZE);
        var target = placement(new Vec3d(0, 65.5, 0), q, new PlayerBodyShape.Dimensions(.5, 3, 2));
        var path = prepare(source, target, work);
        var sample = path.sampleLegal(.1, work);
        assertEquals(0, actualMargin(sample.geometry(), 1, 1, 64, work).signum());
        var p = StoredDyadic.of(64, work);
        var r = StoredDyadic.of(sample.dimensions().radius(), work);
        var l = p.add(r, work).roundUp(work);
        var t = StoredDyadic.of(l, work).add(StoredDyadic.of(sample.dimensions().centerlineHalfLength(), work), work).roundUp(work);
        assertTrue(StoredDyadic.of(sample.center().y(), work).compare(StoredDyadic.of(t, work), work) < 0);
        var uncertain = assertInstanceOf(CapsuleResizePlaneProof.Uncertain.class,
                CapsuleResizePlaneProof.certify(path, 1, 1, 64, 0, 1, work));
        assertEquals(CapsuleResizePlaneProof.Reason.AXIS_THRESHOLD_NOT_CERTIFIED, uncertain.reason());
        System.out.println("PLANE_THRESHOLD_LIMIT c=" + sample.center().y() + " h="
                + sample.dimensions().centerlineHalfLength() + " r=" + sample.dimensions().radius()
                + " endpoint=" + sample.geometry().negativeEndpoint().y() + " threshold=" + t);
        double shiftedPlane = Math.nextUp(64.0);
        var shiftedSource = placement(new Vec3d(0, Math.nextUp(65.0), 0), q, SIZE);
        var shiftedTarget = placement(new Vec3d(0, Math.nextUp(65.5), 0), q,
                new PlayerBodyShape.Dimensions(.5, 3, 2));
        var shiftedPath = prepare(shiftedSource, shiftedTarget, work);
        assertEquals(0, actualMargin(shiftedPath.sampleLegal(0, work).geometry(), 1, 1, shiftedPlane, work).signum());
        assertEquals(0, actualMargin(shiftedPath.sampleLegal(1, work).geometry(), 1, 1, shiftedPlane, work).signum());
        double roundingCellParameter = 3 * 0x1p-46;
        var interior = shiftedPath.sampleLegal(roundingCellParameter, work);
        var actualInteriorMargin = actualMargin(interior.geometry(), 1, 1, shiftedPlane, work);
        assertTrue(actualInteriorMargin.signum() < 0, "actual RN tie cells can penetrate despite safe endpoints");
        assertInstanceOf(CapsuleResizePlaneProof.Uncertain.class,
                CapsuleResizePlaneProof.certify(shiftedPath, 1, 1, shiftedPlane, 0, 1, work));
        System.out.println("PLANE_RN_CELL_NEGATIVE t=" + roundingCellParameter + " plane=" + shiftedPlane
                + " center=" + interior.center().y() + " endpoint=" + interior.geometry().negativeEndpoint().y()
                + " margin=" + actualInteriorMargin.roundNearest(work));
        var penetrating = placement(new Vec3d(0, .5, 0), q, SIZE);
        var penetratingPath = prepare(penetrating, penetrating, work);
        assertInstanceOf(CapsuleResizePlaneProof.Uncertain.class,
                CapsuleResizePlaneProof.certify(penetratingPath, 1, 1, 0, 0, 1, work));
    }

    @Test void certificateBindsRawPlaneIntervalPathAndSameWork() {
        var work = new StoredDyadic.Work();
        var source = placement(new Vec3d(0, 1, 0), Rotation3d.identity(), SIZE);
        var path = prepare(source, source, work);
        var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                CapsuleResizePlaneProof.certify(path, 1, 1, 0, 0, 1, work));
        assertFalse(proof.matches(path, 1, 1, -0.0, 0, 1, work));
        assertFalse(proof.matches(path, 1, -1, 0, 0, 1, work));
        assertFalse(proof.matches(path, 1, 1, 0, .25, 1, work));
        assertFalse(proof.matches(path, 1, 1, 0, 0, 1, new StoredDyadic.Work()));
        assertFalse(proof.matches(prepare(source, source, work), 1, 1, 0, 0, 1, work));
        var foreign = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> CapsuleResizePlaneProof.certify(path, 1, 1, 0, 0, 1, foreign));
        assertEquals(2, foreign.units());
        assertThrows(IllegalArgumentException.class, () -> CapsuleResizePlaneProof.certify(path, 3, 1, 0, 0, 1, work));
        assertThrows(IllegalArgumentException.class, () -> CapsuleResizePlaneProof.certify(path, 1, 1, Double.NaN, 0, 1, work));
    }

    @Test void partialExhaustionKeepsSpentWorkAndOriginalException() {
        var work = new StoredDyadic.Work();
        var source = placement(new Vec3d(0, 1, 0), Rotation3d.identity(), SIZE);
        var path = prepare(source, source, work);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 50);
        var original = assertThrows(NumericWorkExhaustedException.class,
                () -> CapsuleResizePlaneProof.certify(path, 1, 1, 0, 0, 1, work));
        assertTrue(work.units() > StoredDyadic.MAX_WORK_UNITS - 50);
        assertTrue(work.units() <= StoredDyadic.MAX_WORK_UNITS);
        assertEquals("charge", original.getStackTrace()[0].getMethodName());
    }

    private static CapsuleResizeEnclosure.Path prepare(PlayerBodyPlacement source, PlayerBodyPlacement target, StoredDyadic.Work work) {
        return CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
    }
    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d q, PlayerBodyShape.Dimensions dimensions) {
        return new PlayerBodyPlacement(center, q, new PlayerBodyShape.FreeCapsule(dimensions));
    }
    private static Vec3d onAxis(int axis, double value) {
        return new Vec3d(axis == 0 ? value : 0, axis == 1 ? value : 0, axis == 2 ? value : 0);
    }
    private static double component(Vec3d v, int axis) { return axis == 0 ? v.x() : axis == 1 ? v.y() : v.z(); }
    private static StoredDyadic actualMargin(StoredCapsuleGeometry geometry, int axis, int sign, double plane, StoredDyadic.Work work) {
        double a = sign * component(geometry.negativeEndpoint(), axis), b = sign * component(geometry.positiveEndpoint(), axis);
        return StoredDyadic.of(Math.min(a, b), work).subtract(StoredDyadic.of(sign * plane, work), work)
                .subtract(StoredDyadic.of(geometry.radius(), work), work);
    }
    /** Finite cross-checks only; the interval/RN monotonicity proof establishes continuity. */
    private static void crossCheck(CapsuleResizeEnclosure.Path path, int axis, int sign, double plane, StoredDyadic.Work work) {
        for (double t : new double[] {0, .125, .25, .5, .75, .875, 1})
            assertTrue(actualMargin(path.sampleLegal(t, work).geometry(), axis, sign, plane, work).signum() >= 0);
    }
}
