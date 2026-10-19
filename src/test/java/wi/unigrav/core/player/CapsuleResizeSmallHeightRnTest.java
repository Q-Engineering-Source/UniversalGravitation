package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actual recipe regressions, not a substitute for the continuous RN-cell lemma. */
class CapsuleResizeSmallHeightRnTest {
    private static final Rotation3d[] AXES = {
            new Rotation3d(.5, .5, -.5, .5), new Rotation3d(.5, -.5, .5, .5),
            Rotation3d.identity(), new Rotation3d(1, 0, 0, 0),
            new Rotation3d(.5, .5, .5, .5), new Rotation3d(.5, .5, -.5, -.5)};
    private static final CapsuleResizeEnclosure.Path.AxisMargin.Method METHOD =
            CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_SMALL_HEIGHT_RN_CELL;

    @Test void sixExactAxesAndGrowShrinkUseTheRestrictedContinuousLemma() {
        for (int i = 0; i < AXES.length; i++) for (boolean reverse : new boolean[] {false, true}) {
            int axis = i / 2, sign = i % 2 == 0 ? 1 : -1;
            assertEquals(sign, component(AXES[i].basisY(), axis));
            var work = new StoredDyadic.Work();
            var source = placement(axis, sign, AXES[i], .5, reverse ? .75 : 1, 69);
            var target = placement(axis, sign, AXES[i], .5, reverse ? 1 : .75, 69);
            var path = prepare(source, target, work);
            var proof = assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                    CapsuleResizePlaneProof.certify(path, axis, sign, sign * 69, 0, 1, work));
            assertEquals(METHOD, proof.method());
            assertEquals(0, proof.marginLower().signum());
            assertTrue(proof.includesActualStart() && proof.includesActualTarget());
            assertTrue(proof.matches(path, axis, sign, sign * 69, 0, 1, work));
            for (double t : new double[] {0, .125, .5, .875, 1})
                assertEquals(0, actualMargin(path.sampleLegal(t, work).geometry(), axis, sign, sign * 69, work).signum());
        }
    }

    @Test void bothCenterTieParitiesAndTheirNeighborsRoundTheLowerEndpointToEvenL() {
        var work = new StoredDyadic.Work();
        var path = prepare(placement(1, 1, Rotation3d.identity(), .5, .75, 69),
                placement(1, 1, Rotation3d.identity(), .5, 1, 69), work);
        assertEquals(METHOD, path.axisMarginInterval(1, 1, 69, 0, 1, work).method());
        // C*=69.375+.125t; these are respectively even/odd center cell ties.
        for (double tie : new double[] {0x1p-44, 3 * 0x1p-44}) {
            var exactCenter = StoredDyadic.of(69.375, work)
                    .add(StoredDyadic.of(.125, work).multiply(StoredDyadic.of(tie, work), work), work);
            assertFalse(exactCenter.isExactlyRepresentable(work), "the fixture must reach an actual RN tie");
            for (double t : new double[] {Math.nextDown(tie), tie, Math.nextUp(tie)}) {
                var sample = path.sampleLegal(t, work);
                assertEquals(Double.doubleToRawLongBits(69.25),
                        Double.doubleToRawLongBits(sample.geometry().negativeEndpoint().y()));
                assertEquals(0, actualMargin(sample.geometry(), 1, 1, 69, work).signum());
            }
        }
    }

    @Test void widthHeightFootAndApproximateAxisCannotEnterTheNewProfile() {
        Object[][] rejected = {
                {.5000000000000001, .75, 1.0, 69.0, Rotation3d.identity()},
                {.5, Math.nextDown(.75), 1.0, 69.0, Rotation3d.identity()},
                {.5, .75, Math.nextUp(1.0), 69.0, Rotation3d.identity()},
                {.5, .75, 1.0, Math.nextUp(69.0), Rotation3d.identity()},
                {.5, .75, 1.0, 63.0, Rotation3d.identity()},
                {.5, .75, 1.0, 69.0, new Rotation3d(1e-8, 0, 0, 1)}};
        for (Object[] tuple : rejected) {
            double width = (double) tuple[0], h0 = (double) tuple[1], h1 = (double) tuple[2], foot = (double) tuple[3];
            var q = (Rotation3d) tuple[4];
            var work = new StoredDyadic.Work();
            var path = prepare(placement(1, 1, q, width, h0, foot),
                    placement(1, 1, q, width, h1, foot), work);
            assertNotEquals(METHOD, path.axisMarginInterval(1, 1, foot, 0, 1, work).method());
        }
        var work = new StoredDyadic.Work();
        var source = placement(1, 1, Rotation3d.identity(), .5, .75, 69);
        var shifted = placement(1, 1, Rotation3d.identity(), .5, 1, Math.nextUp(69.0));
        assertNotEquals(METHOD, prepare(source, shifted, work).axisMarginInterval(1, 1, 69, 0, 1, work).method());
    }

    @Test void actualNonnominalEndpointIsMergedAndCannotBeHiddenByInteriorLemma() {
        var work = new StoredDyadic.Work();
        var nominal = placement(1, 1, Rotation3d.identity(), .5, 1, 69);
        var center = nominal.center();
        var dimensions = nominal.shape().dimensions();
        var limits = StoredRealizationLimits.derive(center, nominal.attitude(), dimensions, work);
        double actualPlane = Math.nextDown(69.0);
        Vec3d hs = new Vec3d(0, center.y() - (actualPlane + .25), 0);
        var relation = RealizationRelation.axisPlane(limits, 1, 1, actualPlane, center, hs, work);
        var geometry = StoredCapsuleGeometry.fromRecipe(center, nominal.attitude(), dimensions, hs, relation);
        var actual = PlayerBodyPlacement.fromAcceptedFreeGeometry(center, nominal.attitude(),
                new PlayerBodyShape.FreeCapsule(dimensions), geometry, geometry.capsule().bounds(),
                new Vec3d(center.x(), geometry.capsule().bounds().min().y(), center.z()));
        assertFalse(StoredRealizationLimits.sameRaw(hs, nominal.storedCapsuleGeometry().storedHalfLine()));
        assertTrue(actualMargin(geometry, 1, 1, 69, work).signum() < 0);
        var target = placement(1, 1, Rotation3d.identity(), .5, .75, 69);
        for (boolean reverse : new boolean[] {false, true}) {
            var path = prepare(reverse ? target : actual, reverse ? actual : target, work);
            var margin = path.axisMarginInterval(1, 1, 69, 0, 1, work);
            assertEquals(METHOD, margin.method());
            assertTrue(margin.lower().signum() < 0, "real endpoint margin must override the nominal interior bound");
            assertInstanceOf(CapsuleResizePlaneProof.Uncertain.class,
                    CapsuleResizePlaneProof.certify(path, 1, 1, 69, 0, 1, work));
            assertInstanceOf(CapsuleResizePlaneProof.Certificate.class,
                    CapsuleResizePlaneProof.certify(path, 1, 1, 69, .25, .75, work));
        }
    }

    @Test void sameWorkIdentityAndExhaustionRemainStrictAndLegacyDomainKeepsItsMethod() {
        var work = new StoredDyadic.Work();
        var path = prepare(placement(1, 1, Rotation3d.identity(), .5, 1, 69),
                placement(1, 1, Rotation3d.identity(), .5, .75, 69), work);
        var foreign = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> path.axisMarginInterval(1, 1, 69, 0, 1, foreign));
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        assertThrows(NumericWorkExhaustedException.class, () -> path.axisMarginInterval(1, 1, 69, 0, 1, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
        var legacyWork = new StoredDyadic.Work();
        var legacy = prepare(placement(1, 1, Rotation3d.identity(), (double) .6F, (double) 1.8F, 65),
                placement(1, 1, Rotation3d.identity(), (double) .6F, (double) 1.7F, 65), legacyWork);
        assertEquals(CapsuleResizeEnclosure.Path.AxisMargin.Method.AXIS_CORRELATED_RN_LATTICE,
                legacy.axisMarginInterval(1, 1, 65, 0, 1, legacyWork).method());
    }

    private static CapsuleResizeEnclosure.Path prepare(PlayerBodyPlacement source, PlayerBodyPlacement target,
            StoredDyadic.Work work) {
        return CapsuleResizeEnclosure.prepare(CapsulePlacementMath.pose(source, work), target, work);
    }
    private static PlayerBodyPlacement placement(int axis, int sign, Rotation3d q, double width, double height, double foot) {
        double c = sign * (foot + height / 2);
        Vec3d center = new Vec3d(axis == 0 ? c : 8.5, axis == 1 ? c : 8.5, axis == 2 ? c : 8.5);
        return new PlayerBodyPlacement(center, q,
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(width, height, .5)));
    }
    private static double component(Vec3d p, int axis) { return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z(); }
    private static StoredDyadic actualMargin(StoredCapsuleGeometry geometry, int axis, int sign, double plane,
            StoredDyadic.Work work) {
        var a = StoredDyadic.of(sign * component(geometry.negativeEndpoint(), axis), work);
        var b = StoredDyadic.of(sign * component(geometry.positiveEndpoint(), axis), work);
        return (a.compare(b, work) <= 0 ? a : b).subtract(StoredDyadic.of(sign * plane, work), work)
                .subtract(StoredDyadic.of(geometry.radius(), work), work);
    }
}
