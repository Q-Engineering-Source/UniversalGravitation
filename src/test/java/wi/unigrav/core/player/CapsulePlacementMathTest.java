package wi.unigrav.core.player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;
import org.junit.jupiter.api.Test;

import static dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class CapsulePlacementMathTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final PlayerBodyShape.Dimensions EXACT_SIZE = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
    private static final List<Rotation3d> ROTATIONS = List.of(Rotation3d.identity(),
            new Rotation3d(1, 0, 0, 0), new Rotation3d(1, 2, 3, 4), new Rotation3d(-3, 5, 2, 1),
            new Rotation3d(Double.MIN_VALUE, 0, 0, Double.MIN_VALUE),
            new Rotation3d(Double.MAX_VALUE, Double.MIN_VALUE, -1, Double.MAX_VALUE),
            new Rotation3d(-0.0, 1, -0.0, Double.MIN_NORMAL));

    @Test
    void actualQuaternionNormAndEndpointRadiiHaveIndependentExactBounds() {
        for (Vec3d center : List.of(ZERO, new Vec3d(12.3, 257.2, -18.7), new Vec3d(30_000_000, 200, -30_000_000))) {
            for (Rotation3d rotation : ROTATIONS) {
                for (Rotation3d q : List.of(rotation, rotation.inverse(), rotation.inverse().inverse())) {
                    var pose = CapsulePlacementMath.pose(free(center, q, EXACT_SIZE));
                    BigDecimal exactNorm = sq(q.x()).add(sq(q.y())).add(sq(q.z())).add(sq(q.w()));
                    assertLessOrEqual(exact(pose.quaternionNormSquaredLower()), exactNorm);
                    assertLessOrEqual(exactNorm, exact(pose.quaternionNormSquaredUpper()));
                    assertPoseRadius(pose);
                    assertEquals(pose.placement().capsule(), pose.capsule());
                }
            }
        }
    }

    @Test
    void exactCardinalFramesNativeFloatProfilesAndSpheresRemainUsable() {
        var profiles = List.of(new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F),
                new PlayerBodyShape.Dimensions(0.6F, 1.65F, 1.54F),
                new PlayerBodyShape.Dimensions(0.6F, 0.6F, 0.4F),
                new PlayerBodyShape.Dimensions(2 * Double.MIN_VALUE, 2 * Double.MIN_VALUE, 0));
        for (var size : profiles) {
            for (CardinalFrame frame : CardinalFrame.values()) {
                var placement = new PlayerBodyPlacement(new Vec3d(30_000_000, 16, -30_000_000), frame.attitude(),
                        new PlayerBodyShape.Cardinal(frame, size));
                var pose = CapsulePlacementMath.pose(placement);
                assertPoseRadius(pose);
                assertEquals(placement, pose.placement());
            }
        }
        var ordinary = CapsulePlacementMath.pose(free(new Vec3d(30_000_000, 0, -30_000_000),
                ROTATIONS.get(2), new PlayerBodyShape.Dimensions(0.6, 1.8, 1.62)));
        assertTrue(ordinary.orientationRadius() < 0.900000007);
    }

    @Test
    void allOrientationEnvelopeContainsActualConstructedCapsulesAndItsBroadBounds() {
        Vec3d center = new Vec3d(29_999_999.7, -256.1, -16_777_216.3);
        var envelope = CapsulePlacementMath.rotationEnvelope(center, 0.75, 0.25);
        for (Rotation3d q : ROTATIONS) {
            var pose = CapsulePlacementMath.pose(free(center, q, EXACT_SIZE));
            assertTrue(pose.actualRadius() <= envelope.queryCapsule().radius());
            assertCapsuleInsideBounds(pose.capsule(), envelope.bounds());
        }
        assertCapsuleInsideBounds(envelope.queryCapsule(), envelope.bounds());
    }

    @Test
    void equalOppositeEndGeometryDoesNotApproveAnObstructedRotation() {
        var upright = CapsulePlacementMath.pose(free(ZERO, Rotation3d.identity(), EXACT_SIZE));
        var inverted = CapsulePlacementMath.pose(free(ZERO, new Rotation3d(1, 0, 0, 0), EXACT_SIZE));
        var sideWall = box(0.75, -0.125, -0.125, 0.875, 0.125, 0.125);
        assertEquals(upright.capsule(), inverted.capsule());
        assertEquals(CLEAR_SEPARATED, CapsuleAabbCast3d.cast(upright.capsule(), ZERO, sideWall).kind());
        var middle = CapsulePlacementMath.pose(free(ZERO, CardinalFrame.X_POS.attitude(), EXACT_SIZE));
        assertEquals(INITIAL_OVERLAP, CapsuleAabbCast3d.cast(middle.capsule(), ZERO, sideWall).kind());
        var envelope = CapsulePlacementMath.rotationEnvelope(ZERO, 0.75, 0.25);
        assertEquals(INITIAL_OVERLAP, CapsuleAabbCast3d.cast(envelope.queryCapsule(), ZERO, sideWall).kind());
        assertEquals(0, CapsulePlacementMath.displacementUpper(upright, inverted));
    }

    @Test
    void exactBinaryRationalResidualsAndEndpointLinearFamilyFitTranslationTube() {
        Vec3d[] centers = {ZERO, new Vec3d(30_000_000.1, 257.3, -30_000_000.1),
                new Vec3d(Math.nextDown(0x1.0p24), Math.nextUp(-0x1.0p24), 0.1)};
        Vec3d[] deltas = {ZERO, new Vec3d(2, 0, 0), new Vec3d(-0.1, 0.3, -1.7),
                new Vec3d(Double.MIN_VALUE, 0, 0), new Vec3d(0x1.0p-28, -0x1.0p-27, 0x1.0p-30)};
        double[] fractions = {0, Double.MIN_VALUE, 0.1, Math.nextDown(0.5), 0.5, Math.nextDown(1.0), 1};
        for (Vec3d center : centers) {
            for (Rotation3d q : ROTATIONS) {
                var start = CapsulePlacementMath.pose(free(center, q, EXACT_SIZE));
                for (Vec3d delta : deltas) {
                    var tube = CapsulePlacementMath.translationTube(start, delta);
                    for (double fraction : fractions) {
                        var end = assertDoesNotThrow(() -> CapsulePlacementMath.atPrefix(tube, fraction),
                                () -> "center=" + center + " q=" + q + " delta=" + delta + " f=" + fraction
                                        + " mu=" + tube.roundingRadius());
                        assertResidualAndLinearFamily(start, end, delta, fraction, tube.roundingRadius());
                        assertCapsuleInsideBounds(end.capsule(), tube.sweptBounds());
                        assertTrue(CapsulePlacementMath.displacementUpper(start, end) <= tube.requestedDisplacementUpper());
                    }
                }
            }
        }
    }

    @Test
    void nearOnePrefixAtOriginDoesNotMistakeLooseResidualArithmeticForAnEscape() {
        var start = CapsulePlacementMath.pose(free(ZERO, new Rotation3d(1, 2, 3, 4), EXACT_SIZE));
        Vec3d delta = new Vec3d(2, 0, 0);
        double fraction = Math.nextDown(1.0);
        var tube = CapsulePlacementMath.translationTube(start, delta);
        var actualEnd = CapsulePlacementMath.pose(free(delta.multiply(fraction), start.placement().attitude(), EXACT_SIZE));
        // This exact assertion precedes the production cross-check that previously failed.
        assertResidualAndLinearFamily(start, actualEnd, delta, fraction, tube.roundingRadius());
        assertEquals(actualEnd.placement(), CapsulePlacementMath.atPrefix(tube, fraction).placement());
    }

    @Test
    void deterministicConstructorProbesSupplementRatherThanReplaceExactOracles() {
        Random random = new Random(0x524F554E44494E47L);
        for (int index = 0; index < 150; index++) {
            var q = new Rotation3d(random.nextDouble(-1, 1), random.nextDouble(-1, 1),
                    random.nextDouble(-1, 1), random.nextDouble(-1, 1));
            Vec3d center = new Vec3d(random.nextDouble(-30_000_000, 30_000_000),
                    random.nextDouble(-512, 512), random.nextDouble(-30_000_000, 30_000_000));
            var start = CapsulePlacementMath.pose(free(center, q, EXACT_SIZE));
            Vec3d delta = new Vec3d(random.nextDouble(-2, 2), random.nextDouble(-2, 2), random.nextDouble(-2, 2));
            var tube = CapsulePlacementMath.translationTube(start, delta);
            double fraction = random.nextDouble();
            var end = CapsulePlacementMath.atPrefix(tube, fraction);
            assertPoseRadius(start);
            assertPoseRadius(end);
            assertResidualAndLinearFamily(start, end, delta, fraction, tube.roundingRadius());
        }
    }

    @Test
    void ordinaryTwoMetreThinWallUsesPaddedChbPrefixAndActualFinalGeometry() {
        var start = CapsulePlacementMath.pose(free(ZERO, Rotation3d.identity(), EXACT_SIZE));
        var tube = CapsulePlacementMath.translationTube(start, new Vec3d(2, 0, 0));
        var wall = box(1, -0.125, -0.5, 1.125, 0.125, 0.5);
        var hit = CapsuleAabbCast3d.cast(tube.queryCapsule(), tube.delta(), wall);
        assertEquals(HIT_AFTER_CLEAR, hit.kind());
        double fraction = hit.safePrefixEnd().orElseThrow();
        assertTrue(fraction > 0 && fraction < 0.375);
        var end = CapsulePlacementMath.atPrefix(tube, fraction);
        assertEquals(CLEAR_SEPARATED, CapsuleAabbCast3d.cast(end.capsule(), ZERO, wall).kind());
        assertTrue(tube.sweptBounds().min().x() < wall.min().x());
        assertTrue(tube.sweptBounds().max().x() > wall.max().x());
        assertEquals(2, tube.requestedDisplacementUpper(), 1e-14);
    }

    @Test
    void roundedFinalEndpointReallyDiffersFromTheUnpaddedIdealPath() {
        var start = CapsulePlacementMath.pose(free(new Vec3d(30_000_000, 0, 0),
                Rotation3d.identity(), EXACT_SIZE));
        Vec3d delta = new Vec3d(0.1, 0, 0);
        var tube = CapsulePlacementMath.translationTube(start, delta);
        var end = CapsulePlacementMath.atPrefix(tube, 1);
        BigDecimal exactActualX = exact(end.capsule().centerline().start().x());
        BigDecimal idealX = exact(start.capsule().centerline().start().x()).add(exact(delta.x()));
        // The binary64 addition rounded toward the obstacle side by about 1.49e-9 m.
        assertTrue(exactActualX.compareTo(idealX) > 0);
        assertTrue(tube.roundingRadius() > 0);
        assertTrue(tube.roundingRadius() < 2e-8);
        assertLessOrEqual(exactActualX.subtract(idealX), exact(tube.roundingRadius()));
    }

    @Test
    void unchangedNumericalPoseDoesNotFabricateMotionOrCollisionApproval() {
        var start = CapsulePlacementMath.pose(free(new Vec3d(30_000_000, 0, 0), Rotation3d.identity(), EXACT_SIZE));
        var zero = CapsulePlacementMath.translationTube(start, ZERO);
        assertEquals(0, zero.roundingRadius());
        assertEquals(0, zero.requestedDisplacementUpper());
        assertSame(start, CapsulePlacementMath.atPrefix(zero, 0));
        assertSame(start, CapsulePlacementMath.atPrefix(zero, 1));
        var tiny = CapsulePlacementMath.translationTube(start, new Vec3d(Double.MIN_VALUE, 0, 0));
        assertSame(start, CapsulePlacementMath.atPrefix(tiny, 1));
        var overlap = box(29_999_999, -1, -1, 30_000_001, 1, 1);
        assertEquals(INITIAL_OVERLAP, CapsuleAabbCast3d.cast(zero.queryCapsule(), ZERO, overlap).kind());
    }

    @Test
    void capsuleEnvelopeDoesNotIncludeCardinalAabbCorners() {
        var size = new PlayerBodyShape.Dimensions(2, 2, 1);
        var body = new PlayerBodyPlacement(ZERO, Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, size));
        var envelope = CapsulePlacementMath.rotationEnvelope(ZERO, 0, 1);
        var corner = box(0.9, 0.9, 0.9, 0.95, 0.95, 0.95);
        assertTrue(body.bounds().max().x() > corner.min().x());
        assertTrue(body.bounds().max().y() > corner.min().y());
        assertTrue(body.bounds().max().z() > corner.min().z());
        assertEquals(CLEAR_SEPARATED, CapsuleAabbCast3d.cast(envelope.queryCapsule(), ZERO, corner).kind());
    }

    @Test
    void actualRadiusPassingDoesNotAuthorizePaddingOutsideTheChbRadiusDomain() {
        double radius = 64 - 0x1.0p-27;
        var sphere = new PlayerBodyShape.Dimensions(radius * 2, radius * 2, radius);
        var start = CapsulePlacementMath.pose(free(new Vec3d(30_000_000, 30_000_000, 30_000_000),
                Rotation3d.identity(), sphere));
        assertTrue(start.orientationRadius() < 64);
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translationTube(start, new Vec3d(2, 0, 0)));
    }

    @Test
    void strictDomainAndUnrepresentableConstructionFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.pose(free(
                new Vec3d(0x1.0p25, 0, 0), Rotation3d.identity(), EXACT_SIZE)));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.rotationEnvelope(ZERO, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.rotationEnvelope(ZERO, 64, 0.25));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.rotationEnvelope(ZERO, Double.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.rotationEnvelope(ZERO, Double.NaN, 1));
        var lostHalf = new PlayerBodyShape.Dimensions(2 * Double.MIN_VALUE, 3 * Double.MIN_VALUE, 0);
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.pose(free(ZERO, Rotation3d.identity(), lostHalf)));
        var start = CapsulePlacementMath.pose(free(ZERO, Rotation3d.identity(), EXACT_SIZE));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translationTube(start, new Vec3d(64, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translationTube(start, new Vec3d(50, 50, 0)));
        var nearLimit = CapsulePlacementMath.pose(free(new Vec3d(0x1.0p25 - 1, 0, 0), Rotation3d.identity(), EXACT_SIZE));
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.translationTube(nearLimit, new Vec3d(2, 0, 0)));
        var tube = CapsulePlacementMath.translationTube(start, new Vec3d(2, 0, 0));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -Double.MIN_VALUE, Math.nextUp(1.0)}) {
            assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.atPrefix(tube, bad));
        }
    }

    private static PlayerBodyPlacement free(Vec3d center, Rotation3d rotation, PlayerBodyShape.Dimensions dimensions) {
        return new PlayerBodyPlacement(center, rotation, new PlayerBodyShape.FreeCapsule(dimensions));
    }

    private static void assertPoseRadius(CapsulePlacementMath.Pose pose) {
        assertTrue(pose.actualRadius() <= pose.orientationRadius());
        BigDecimal available = exact(pose.actualRadius()).subtract(exact(pose.capsule().radius()));
        for (Vec3d endpoint : endpoints(pose.capsule())) {
            assertLessOrEqual(exactDistanceSquared(endpoint, pose.placement().center()), available.multiply(available));
        }
    }

    private static void assertResidualAndLinearFamily(CapsulePlacementMath.Pose start, CapsulePlacementMath.Pose end,
                                                      Vec3d delta, double fraction, double mu) {
        // Pair by the directed physical +/- half-line, never lexicographic CHB endpoint order.
        Vec3d half = start.placement().bodyUp().multiply(start.placement().shape().dimensions().centerlineHalfLength());
        for (double sign : new double[]{-1, 1}) {
            Vec3d e0 = start.placement().center().add(half.multiply(sign));
            Vec3d e1 = end.placement().center().add(half.multiply(sign));
            BigDecimal sum = BigDecimal.ZERO;
            for (int axis = 0; axis < 3; axis++) {
                BigDecimal residual = exact(component(e1, axis)).subtract(exact(component(e0, axis)))
                        .subtract(exact(fraction).multiply(exact(component(delta, axis))));
                sum = sum.add(residual.multiply(residual));
            }
            assertLessOrEqual(sum, sq(mu));
            // Affine endpoint error is s*residual, so its exact norm has no interior maximum.
            for (double s : new double[]{0, 0.25, 0.5, 1}) {
                assertLessOrEqual(sum.multiply(sq(s)), sq(mu));
            }
        }
    }

    private static void assertCapsuleInsideBounds(Capsule capsule, Aabb bounds) {
        for (Vec3d endpoint : endpoints(capsule)) {
            for (int axis = 0; axis < 3; axis++) {
                assertLessOrEqual(exact(component(bounds.min(), axis)), exact(component(endpoint, axis)).subtract(exact(capsule.radius())));
                assertLessOrEqual(exact(component(endpoint, axis)).add(exact(capsule.radius())), exact(component(bounds.max(), axis)));
            }
        }
    }

    private static List<Vec3d> endpoints(Capsule capsule) {
        return List.of(capsule.centerline().start(), capsule.centerline().end());
    }

    private static BigDecimal exactDistanceSquared(Vec3d a, Vec3d b) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int axis = 0; axis < 3; axis++) {
            BigDecimal d = exact(component(a, axis)).subtract(exact(component(b, axis)));
            sum = sum.add(d.multiply(d));
        }
        return sum;
    }

    private static double component(Vec3d v, int axis) {
        return switch (axis) { case 0 -> v.x(); case 1 -> v.y(); default -> v.z(); };
    }

    private static BigDecimal exact(double value) { return new BigDecimal(value); }
    private static BigDecimal sq(double value) { return exact(value).multiply(exact(value)); }

    private static void assertLessOrEqual(BigDecimal actual, BigDecimal upper) {
        assertTrue(actual.compareTo(upper) <= 0, () -> actual + " exceeds exact bound " + upper);
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
}
