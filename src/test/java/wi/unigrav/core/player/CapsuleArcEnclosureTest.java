package wi.unigrav.core.player;

import java.math.BigDecimal;

import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleArcEnclosureTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.5, 2, 1);
    private static final Vec3d CENTER = new Vec3d(.25, 1.125, .75);

    @Test void proneSemanticsCanChangeWithoutChangingTheCertifiedArcCollider() {
        var q = CardinalFrame.Z_POS.attitude();
        var anchor = java.util.Optional.of(new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, CENTER, CENTER, q));
        var profile = new PlayerBodyShape.CollisionProfile(SIZE.width(), SIZE.height());
        var crawl = new PlayerBodyShape.FreeCapsule(SIZE, profile, anchor, java.util.Optional.of(
                new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.CRAWLING, CardinalFrame.Y_POS)));
        var swim = new PlayerBodyShape.FreeCapsule(SIZE, profile, anchor, java.util.Optional.of(
                new PlayerBodyShape.PronePose(PlayerBodyShape.PronePose.Kind.SWIMMING, CardinalFrame.Y_POS)));
        var start = new PlayerBodyPlacement(CENTER, q, crawl);
        var target = new PlayerBodyPlacement(CENTER, q, swim);
        var work = new StoredDyadic.Work();
        var arc = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, work), target, new Vec3d(0, 1, 0), work);
        assertEquals(start.capsule(), arc.enclose(0, 1, work).reference());
        var changed = new PlayerBodyPlacement(CENTER, q, new PlayerBodyShape.FreeCapsule(SIZE,
                new PlayerBodyShape.CollisionProfile(SIZE.width(), SIZE.height() + .25), anchor, swim.pronePose()));
        assertThrows(IllegalArgumentException.class, () -> CapsuleArcEnclosure.prepare(
                CapsulePlacementMath.pose(start, work), changed, new Vec3d(0, 1, 0), work));
    }

    @Test void midpointIsActualRecipeAndIntervalCrossChecksAreNotTheContinuousCertificate() {
        var q0 = new Rotation3d(.15, .2, .3, .7);
        var q1 = new Rotation3d(.6, -.1, .2, .7);
        var start = placement(CENTER, q0, SIZE);
        var target = placement(CENTER, q1, SIZE);
        var work = new StoredDyadic.Work();
        var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, work), target, new Vec3d(1, 0, 0), work);
        long prepared = work.units();
        var wide = path.enclose(0, 1, work);
        assertEquals(recipe(CENTER, q0, q1, .5, SIZE), wide.reference());
        assertTrue(wide.uses(work));
        assertEquals(work.units() - prepared, wide.numericWorkDelta());
        assertTrue(wide.padded().radius() > wide.reference().radius());
        // These actual API samples detect transcription mistakes; the directed operation
        // intervals in production, not this finite set, certify the whole interval.
        for (int i = 0; i <= 64; i++) {
            Capsule actual = i == 0 ? start.capsule() : i == 64 ? target.capsule()
                    : recipe(CENTER, q0, q1, i / 64.0, SIZE);
            assertCovered(wide, actual);
        }
        var narrow = path.enclose(.49, .51, work);
        assertEquals(recipe(CENTER, q0, q1, .5, SIZE), narrow.reference());
        assertTrue(narrow.padded().radius() < wide.padded().radius());
        var point = path.enclose(.5, .5, work);
        assertEquals(point.reference(), point.padded());
        assertEquals(recipe(CENTER, q0, q1, .5, SIZE), point.reference());
        assertTrue(path.numericWorkDelta() > 512);
        assertTrue(wide.numericWorkDelta() < 1100, "one enclosure must leave shared budget for owner queries/refinement");
        System.out.println("ARC_WORK prepare=" + path.numericWorkDelta() + " whole=" + wide.numericWorkDelta()
                + " narrow=" + narrow.numericWorkDelta() + " point=" + point.numericWorkDelta()
                + " cumulative=" + work.units());
    }

    @Test void negativeDotUsesSignedShortArcThroughTheActualCanonicalFlip() {
        var q0 = new Rotation3d(.8, 0, 0, .6);
        var q1 = new Rotation3d(-.8, 0, 0, .6);
        var work = new StoredDyadic.Work();
        var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(placement(CENTER, q0, SIZE), work),
                placement(CENTER, q1, SIZE), new Vec3d(1, 0, 0), work);
        // q1 is canonical but exact dot is negative; signed endpoint has negative w.
        Rotation3d mid = new Rotation3d(.5 * q0.x() - .5 * q1.x(), 0, 0, .5 * q0.w() - .5 * q1.w());
        assertEquals(capsule(CENTER, mid, SIZE), path.enclose(0, 1, work).reference());
        for (double t : new double[] {.25, .5, .75}) {
            Rotation3d actual = new Rotation3d((1-t)*q0.x()-t*q1.x(), 0, 0, (1-t)*q0.w()-t*q1.w());
            assertCovered(path.enclose(t-.01, t+.01, work), capsule(CENTER, actual, SIZE));
        }
    }

    @Test void exactHalfTurnTieUsesWorldPreferredAxisThenLexicographicDirection() {
        var q0 = Rotation3d.identity();
        var q1 = new Rotation3d(1, 0, 0, 0);
        var start = placement(CENTER, q0, SIZE);
        var target = placement(CENTER, q1, SIZE);
        var plusWork = new StoredDyadic.Work();
        var plus = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, plusWork), target,
                new Vec3d(1, 0, 0), plusWork);
        var minusWork = new StoredDyadic.Work();
        var minus = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, minusWork), target,
                new Vec3d(-1, 0, 0), minusWork);
        var lexWork = new StoredDyadic.Work();
        var lex = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, lexWork), target,
                new Vec3d(0, 1, 0), lexWork);
        assertEquals(capsule(CENTER, new Rotation3d(.5, 0, 0, .5), SIZE), plus.enclose(0, 1, plusWork).reference());
        assertEquals(capsule(CENTER, new Rotation3d(-.5, 0, 0, .5), SIZE), minus.enclose(0, 1, minusWork).reference());
        assertEquals(plus.enclose(.25, .75, plusWork).reference(), lex.enclose(.25, .75, lexWork).reference());
        assertNotEquals(plus.enclose(.25, .25, plusWork).reference(), minus.enclose(.25, .25, minusWork).reference());

        // Relative axis is world-space q1*conjugate(q0) = (+1,0,0), not raw q1's
        // (+0,+0,+1). Preferred -X must select the negative relative direction.
        var worldWork = new StoredDyadic.Work();
        var world = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(
                        placement(CENTER, new Rotation3d(0, 1, 0, 0), SIZE), worldWork),
                placement(CENTER, new Rotation3d(0, 0, 1, 0), SIZE), new Vec3d(-1, 0, 0), worldWork);
        assertEquals(capsule(CENTER, new Rotation3d(0, .75, -.25, 0), SIZE),
                world.enclose(.25, .25, worldWork).reference());
        var tinyWork = new StoredDyadic.Work();
        var tiny = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, tinyWork),
                placement(CENTER, new Rotation3d(1, 0, 0, Double.MIN_VALUE), SIZE),
                new Vec3d(-1, 0, 0), tinyWork);
        assertEquals(capsule(CENTER, new Rotation3d(.25, 0, 0, .75), SIZE),
                tiny.enclose(.25, .25, tinyWork).reference(), "exact positive subnormal dot is never a tie");
    }

    @Test void sphereAndNominalPureYAxisAreExactGeometricInvariants() {
        var sphereSize = new PlayerBodyShape.Dimensions(.5, .5, .25);
        var q0 = new Rotation3d(.2, .3, .4, .5);
        var q1 = new Rotation3d(-.4, .2, .6, .3);
        var sphereWork = new StoredDyadic.Work();
        var sphere = placement(CENTER, q0, sphereSize);
        var spherePath = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(sphere, sphereWork),
                placement(CENTER, q1, sphereSize), new Vec3d(0, 0, 1), sphereWork);
        assertEquals(sphere.capsule(), spherePath.enclose(0, 1, sphereWork).reference());
        assertEquals(sphere.capsule(), spherePath.enclose(0, 1, sphereWork).padded());
        var work = new StoredDyadic.Work();
        var vertical = placement(CENTER, Rotation3d.identity(), SIZE);
        var yPath = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(vertical, work),
                placement(CENTER, new Rotation3d(0, .8, 0, .6), SIZE), new Vec3d(0, 1, 0), work);
        assertEquals(vertical.capsule(), yPath.enclose(0, 1, work).padded());
        assertEquals(vertical.capsule(), yPath.enclose(.3, .7, work).reference());
    }

    @Test void differentActualAxisPlaneHalfLinesRemainAcceptedAndTheirEndpointsAreContained() {
        var work = new StoredDyadic.Work();
        // Both are legal recipes at this center, with distinct actual stored Hs.
        // A reference binade supplies the real realization band; no fake geometry.
        var center = new Vec3d(.25, Math.nextUp(1.125), .75);
        var nominal = placement(center, Rotation3d.identity(), SIZE);
        var axisPlane = axisPlane(center, work);
        assertFalse(StoredRealizationLimits.sameRaw(axisPlane.storedCapsuleGeometry().storedHalfLine(),
                nominal.storedCapsuleGeometry().storedHalfLine()));
        var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(axisPlane, work), nominal,
                new Vec3d(0, 1, 0), work);
        var whole = path.enclose(0, 1, work);
        assertCovered(whole, axisPlane.capsule());
        assertCovered(whole, nominal.capsule());
        assertEquals(nominal.capsule(), whole.reference());
        assertTrue(whole.padded().radius() > nominal.capsule().radius(),
                "non-nominal stored Hs must not enter the pure-Y exact invariant");
        assertEquals(axisPlane.capsule(), path.enclose(0, 0, work).reference());
        assertEquals(nominal.capsule(), path.enclose(1, 1, work).reference());
        var reverse = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(nominal, work), axisPlane,
                new Vec3d(0, 1, 0), work);
        assertCovered(reverse.enclose(0, 1, work), axisPlane.capsule());
    }

    @Test void sharedWorkIdentityProfileAndExhaustionAreExplicitFailures() {
        var start = placement(CENTER, Rotation3d.identity(), SIZE);
        var target = placement(CENTER, new Rotation3d(.4, 0, 0, .9), SIZE);
        var work = new StoredDyadic.Work();
        var path = CapsuleArcEnclosure.prepare(CapsulePlacementMath.pose(start, work), target, new Vec3d(1, 0, 0), work);
        var foreign = new StoredDyadic.Work();
        assertThrows(IllegalArgumentException.class, () -> path.enclose(0, 1, foreign));
        assertEquals(1, foreign.units());
        assertThrows(IllegalArgumentException.class, () -> path.enclose(-.1, 1, work));
        assertThrows(IllegalArgumentException.class, () -> path.enclose(.8, .2, work));
        assertThrows(IllegalArgumentException.class, () -> path.enclose(0, Double.NaN, work));
        assertThrows(IllegalArgumentException.class, () -> CapsuleArcEnclosure.prepare(
                CapsulePlacementMath.pose(start, work), placement(new Vec3d(.5, 1.125, .75), target.attitude(), SIZE),
                new Vec3d(1, 0, 0), work));
        var cardinal = new PlayerBodyPlacement(CENTER, Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        assertThrows(IllegalArgumentException.class, () -> CapsuleArcEnclosure.prepare(
                CapsulePlacementMath.pose(cardinal, work), target, new Vec3d(1, 0, 0), work));
        long remaining = StoredDyadic.MAX_WORK_UNITS - work.units();
        work.charge(remaining - 1);
        assertThrows(NumericWorkExhaustedException.class, () -> path.enclose(0, 1, work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    @Test void preparationRejectsDimensionDriftAndChargesFailedTargetObservation() {
        var work = new StoredDyadic.Work();
        var start = placement(CENTER, Rotation3d.identity(), SIZE);
        var pose = CapsulePlacementMath.pose(start, work);
        var altered = placement(CENTER, Rotation3d.identity(), new PlayerBodyShape.Dimensions(.5, 2, .9));
        assertThrows(IllegalArgumentException.class, () -> CapsuleArcEnclosure.prepare(pose, altered, new Vec3d(0, 0, 0), work));
        var exhausted = new StoredDyadic.Work();
        exhausted.charge(StoredDyadic.MAX_WORK_UNITS - 1);
        assertThrows(NumericWorkExhaustedException.class, () -> CapsuleArcEnclosure.prepare(pose,
                placement(CENTER, new Rotation3d(.2, .3, .4, .5), SIZE), new Vec3d(0, 0, 0), exhausted));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, exhausted.units());
    }

    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d q, PlayerBodyShape.Dimensions dimensions) {
        // Fixture construction predates the observed transaction. prepare explicitly
        // re-observes the actual endpoint objects with its caller-owned Work.
        return new PlayerBodyPlacement(center, q, new PlayerBodyShape.FreeCapsule(dimensions));
    }

    private static PlayerBodyPlacement axisPlane(Vec3d center, StoredDyadic.Work work) {
        var q = Rotation3d.identity();
        var limits = StoredRealizationLimits.derive(new Vec3d(center.x(), 1.125, center.z()), q, SIZE, work);
        Vec3d hs = new Vec3d(0, center.y() - .375, 0);
        var relation = RealizationRelation.axisPlane(limits, 1, 1, .125, center, hs, work);
        var stored = StoredCapsuleGeometry.fromRecipe(center, q, SIZE, hs, relation);
        return PlayerBodyPlacement.fromAcceptedFreeGeometry(center, q, new PlayerBodyShape.FreeCapsule(SIZE),
                stored, stored.capsule().bounds(), new Vec3d(center.x(), stored.capsule().bounds().min().y(), center.z()));
    }

    private static Capsule recipe(Vec3d center, Rotation3d q0, Rotation3d q1, double t, PlayerBodyShape.Dimensions size) {
        double s = 1-t;
        return capsule(center, new Rotation3d(s*q0.x()+t*q1.x(), s*q0.y()+t*q1.y(),
                s*q0.z()+t*q1.z(), s*q0.w()+t*q1.w()), size);
    }
    private static Capsule capsule(Vec3d center, Rotation3d q, PlayerBodyShape.Dimensions size) {
        Vec3d hs = q.basisY().multiply(size.centerlineHalfLength());
        return new Capsule(new Segment3d(center.subtract(hs), center.add(hs)), size.radius());
    }

    private static void assertCovered(CapsuleArcEnclosure.Enclosure enclosure, Capsule actual) {
        // Exact decimal arithmetic compares squared endpoint displacement against
        // the actual padded radius increment; no epsilon weakens the assertion.
        Vec3d[] references = {enclosure.reference().centerline().start(), enclosure.reference().centerline().end()};
        for (Vec3d endpoint : new Vec3d[] {actual.centerline().start(), actual.centerline().end()}) {
            BigDecimal best = null;
            for (Vec3d reference : references) {
                BigDecimal squared = BigDecimal.ZERO;
                for (int axis = 0; axis < 3; axis++) {
                    var difference = new BigDecimal(component(endpoint, axis)).subtract(new BigDecimal(component(reference, axis)));
                    squared = squared.add(difference.multiply(difference));
                }
                if (best == null || squared.compareTo(best) < 0) best = squared;
            }
            BigDecimal extra = new BigDecimal(enclosure.padded().radius()).subtract(new BigDecimal(actual.radius()));
            assertTrue(best.compareTo(extra.multiply(extra)) <= 0, "endpoint escaped padded corresponding-segment bound");
            for (int axis = 0; axis < 3; axis++) {
                BigDecimal coordinate = new BigDecimal(component(endpoint, axis));
                BigDecimal r = new BigDecimal(actual.radius());
                assertTrue(new BigDecimal(component(enclosure.bounds().min(), axis)).compareTo(coordinate.subtract(r)) <= 0);
                assertTrue(new BigDecimal(component(enclosure.bounds().max(), axis)).compareTo(coordinate.add(r)) >= 0);
            }
        }
    }
    private static double component(Vec3d v, int axis) { return StoredRealizationLimits.component(v, axis); }
}
