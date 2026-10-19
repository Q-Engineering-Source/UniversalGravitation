package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BootSurfaceGeometryTest {
    private static final PlayerBodyShape.Dimensions BODY = new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75);
    private static final PlayerBodyShape.Dimensions SPHERE = new PlayerBodyShape.Dimensions(0.5, 0.5, 0.4);
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void sourceUsesTheWholeAcceptedCardinalFootRectangle(CardinalFrame frame) {
        var snapshot = snapshot(new PlayerBodyPlacement(ZERO, frame.attitude(), new PlayerBodyShape.Cardinal(frame, BODY)));
        var source = BootSurfaceGeometry.fromAccepted(snapshot);
        Aabb expected = frame.toWorld(box(-0.25, -1.0, -0.25, 0.25, -1.0, 0.25));
        assertSame(snapshot, source.physical());
        assertEquals(BootSurfaceGeometry.SourceKind.CARDINAL_FOOT_RECTANGLE, source.kind());
        assertEquals(expected, source.anchorBounds());
        assertEquals(0.0, source.radius());
        assertEquals(new Aabb(expected.min().subtract(new Vec3d(1.5, 1.5, 1.5)),
                expected.max().add(new Vec3d(1.5, 1.5, 1.5))), source.queryBounds(1.5));
        assertFalse(source.anchorBounds().equals(snapshot.placement().bounds()));
    }

    @Test
    void freeSourceUsesTheLowerEndpointNotCanonicalSegmentStartOrWholeProxy() {
        for (Rotation3d attitude : new Rotation3d[] {Rotation3d.identity(), CardinalFrame.Y_NEG.attitude(),
                CardinalFrame.X_POS.attitude(), new Rotation3d(1, 2, 3, 4)}) {
            var placement = new PlayerBodyPlacement(new Vec3d(3, 4, 5), attitude, new PlayerBodyShape.FreeCapsule(BODY));
            var source = BootSurfaceGeometry.fromAccepted(snapshot(placement));
            Vec3d expected = placement.center().subtract(placement.bodyUp().multiply(0.75));
            assertEquals(BootSurfaceGeometry.SourceKind.FREE_LOWER_BALL, source.kind());
            assertEquals(new Aabb(expected, expected), source.anchorBounds());
            assertEquals(0.25, source.radius());
            assertTrue(placement.physicalFeet().isEmpty());
            assertFalse(source.queryBounds(0).equals(placement.bounds()));
        }
    }

    @Test
    void sphereStillHasAnEligibleSensorWithoutCreatingFeetOrChangingAttitude() {
        var attitude = new Rotation3d(1, -2, 3, 4);
        var physical = snapshot(new PlayerBodyPlacement(ZERO, attitude, new PlayerBodyShape.FreeCapsule(SPHERE)));
        var source = BootSurfaceGeometry.fromAccepted(physical);
        var result = measured(source, face(box(-1, -1.75, -1, 1, -1.75, 1), CardinalFrame.Y_POS), 1.5);
        assertEquals(new Aabb(ZERO, ZERO), source.anchorBounds());
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, result.range());
        assertEquals(new BootSurfaceGeometry.Gap(1.5, 1.5), result.gap());
        assertEquals(new Vec3d(0, -1, 0), result.pullDirection());
        assertSame(physical, result.source().physical());
        assertEquals(attitude, physical.placement().attitude());
        assertTrue(physical.placement().physicalFeet().isEmpty());
    }

    @Test
    void uniformScalingKeepsTheCapsuleAspectRatio() {
        var small = new PlayerBodyShape.Dimensions(BODY.width() / 8, BODY.height() / 8, BODY.eyeHeight() / 8);
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                ZERO, Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(small))));
        assertEquals(BODY.height() / BODY.width(), small.height() / small.width());
        assertTrue(small.centerlineHalfLength() > 0);
        assertEquals(new Vec3d(0, -0.09375, 0), source.anchorBounds().min());
        assertEquals(0.03125, source.radius());
    }

    @Test
    void partialFootEdgeCoverageDoesNotBecomeAFeetCentreRay() {
        var source = cardinalAtFeetOrigin(CardinalFrame.Y_POS);
        var result = measured(source, face(box(0.2, -1, -0.5, 0.8, -1, 0.5), CardinalFrame.Y_POS), 1);
        assertEquals(new BootSurfaceGeometry.Gap(1, 1), result.gap());
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, result.range());
        assertEquals(new Vec3d(0.2, 0, -0.25), result.sourceAnchor());
        assertEquals(new Vec3d(0.2, -1, -0.25), result.surfaceAnchor());
        assertEquals(new Vec3d(0, -1, 0), result.pullDirection());
        assertEquals(BootSurfaceGeometry.MinimumSide.FRONT, result.minimumSide());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void finitePatchDistanceIncludesTangentialGapAndExactReachBoundary(CardinalFrame frame) {
        var source = cardinalAtFeetOrigin(frame);
        var target = face(frame.toWorld(box(3.25, -4, -0.25, 4.25, -4, 0.25)), frame);
        var result = measured(source, target, 5);
        assertEquals(new BootSurfaceGeometry.Gap(5, 5), result.gap());
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, result.range());
        assertEquals(BootSurfaceGeometry.Range.OUT_OF_RANGE, measured(source, target, Math.nextDown(5.0)).range());
        // The overlapping tangent interval has many closest pairs. The world-axis
        // tie-break need not rotate a particular local-axis endpoint into itself.
        assertWithin(result.sourceAnchor(), source.anchorBounds());
        assertWithin(result.surfaceAnchor(), target.bounds());
        assertEquals(frame.toWorld(new Vec3d(3, -4, 0)), result.surfaceAnchor().subtract(result.sourceAnchor()));
        assertVectorNear(frame.toWorld(new Vec3d(0.6, -0.8, 0)), result.pullDirection());
    }

    @Test
    void sphereReachDoesNotAdmitTheNextRepresentableDistance() {
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                ZERO, Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(SPHERE))));
        double outside = -Math.nextUp(1.75);
        var result = measured(source, face(box(-1, outside, -1, 1, outside, 1), CardinalFrame.Y_POS), 1.5);
        assertEquals(BootSurfaceGeometry.Range.OUT_OF_RANGE, result.range());
        assertTrue(result.gap().lowerMetres() > 1.5);
        assertEquals(new BootSurfaceGeometry.Gap(1.5, 1.5), measured(source,
                face(box(-1, -1.75, -1, 1, -1.75, 1), CardinalFrame.Y_POS), 1.5).gap());
    }

    @Test
    void exactContactUsesInwardNormalAndDoesNotProveActualSupport() {
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                new Vec3d(0, 0.25, 0), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(SPHERE))));
        var result = measured(source, face(box(-1, 0, -1, 1, 0, 1), CardinalFrame.Y_POS), 0);
        assertEquals(new BootSurfaceGeometry.Gap(0, 0), result.gap());
        assertEquals(BootSurfaceGeometry.MinimumSide.ON_PLANE, result.minimumSide());
        assertEquals(new Vec3d(0, -1, 0), result.pullDirection());
        // This ball anchor is intentionally the centre, not a fabricated physical foot or visibility witness.
        assertEquals(new Vec3d(0, 0.25, 0), result.sourceAnchor());
        assertEquals(ZERO, result.surfaceAnchor());
        assertTrue(source.physical().placement().physicalFeet().isEmpty());
    }

    @Test
    void faceSideNeverMasqueradesAsWholeSolidPenetration() {
        var source = cardinalAtFeetOrigin(CardinalFrame.Y_POS);
        var top = measured(source, face(box(-1, -1, -1, 1, -1, 1), CardinalFrame.Y_POS), 2);
        var back = measured(source, face(box(1, -1, -1, 1, 0, 1), CardinalFrame.X_POS), 2);
        assertEquals(BootSurfaceGeometry.MinimumSide.FRONT, top.minimumSide());
        assertEquals(BootSurfaceGeometry.MinimumSide.BEHIND_PLANE, back.minimumSide());
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, back.range());
        assertEquals(new BootSurfaceGeometry.Gap(0.75, 0.75), back.gap());
    }

    @Test
    void nearZeroPositiveGapIsNotFlattenedToExactContactOrExpandedByAnEpsilon() {
        var sphere = new PlayerBodyShape.Dimensions(2, 2, 1);
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                ZERO, Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(sphere))));
        var target = face(box(1, Double.MIN_VALUE, 0, 1, Double.MIN_NORMAL, 1), CardinalFrame.X_NEG);
        // sqrt(1 + MIN_VALUE²) - 1 is positive but below the smallest binary64 gap.
        var result = measured(source, target, 0);
        assertEquals(BootSurfaceGeometry.Range.OUT_OF_RANGE, result.range());
        assertEquals(0.0, result.gap().lowerMetres());
        assertTrue(result.gap().upperMetres() > 0);
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, measured(source, target, Double.MIN_VALUE).range());
    }

    @Test
    void irrationalDistanceBoundsEncloseTheExactSquaredGap() {
        var source = cardinalAtFeetOrigin(CardinalFrame.Y_POS);
        var result = measured(source, face(box(1.25, -1, -1, 2, -1, 1), CardinalFrame.Y_POS), 2);
        assertTrue(result.gap().lowerMetres() < result.gap().upperMetres());
        assertTrue(square(result.gap().lowerMetres()).compareTo(BigDecimal.valueOf(2)) <= 0);
        assertTrue(square(result.gap().upperMetres()).compareTo(BigDecimal.valueOf(2)) >= 0);
    }

    @Test
    void worldBorderQueryBoundsRoundOutwardForTheActualBallNotItsProxy() {
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                new Vec3d(29_999_999, 256.25, -29_999_999), new Rotation3d(1, 2, 3, 4),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F)))));
        Aabb query = source.queryBounds(1.5);
        double[] anchor = values(source.anchorBounds().min());
        double[] low = values(query.min());
        double[] high = values(query.max());
        BigDecimal extension = new BigDecimal(source.radius()).add(new BigDecimal(1.5));
        for (int axis = 0; axis < 3; axis++) {
            assertTrue(new BigDecimal(low[axis]).compareTo(new BigDecimal(anchor[axis]).subtract(extension)) <= 0);
            assertTrue(new BigDecimal(high[axis]).compareTo(new BigDecimal(anchor[axis]).add(extension)) >= 0);
        }
    }

    @Test
    void subnormalBallAndGapRemainRepresentableWithoutSquaringUnderflow() {
        double unit = Double.MIN_VALUE;
        var tiny = new PlayerBodyShape.Dimensions(unit * 2, unit * 2, unit);
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                ZERO, Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(tiny))));
        var target = face(box(-unit, -unit * 3, -unit, unit, -unit * 3, unit), CardinalFrame.Y_POS);
        var result = measured(source, target, unit * 2);
        assertEquals(BootSurfaceGeometry.Range.IN_RANGE, result.range());
        assertEquals(new BootSurfaceGeometry.Gap(unit * 2, unit * 2), result.gap());
        assertEquals(new Vec3d(0, -1, 0), result.pullDirection());
    }

    @Test
    void unrepresentableDistanceIsExplicitlyUnavailableRatherThanNoCandidate() {
        var source = BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(
                new Vec3d(0, 0, 0), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(SPHERE))));
        var target = face(box(Double.MAX_VALUE, Double.MAX_VALUE / 2, Double.MAX_VALUE / 2,
                Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE), CardinalFrame.X_NEG);
        var unavailable = assertInstanceOf(BootSurfaceGeometry.Unavailable.class,
                BootSurfaceGeometry.measure(source, target, 1.5));
        assertEquals("distance is not finite in binary64", unavailable.cause().getMessage());
    }

    @Test
    void invalidGeometryAndReachFailFast() {
        assertThrows(IllegalArgumentException.class, () -> face(box(0, 0, 0, 1, 1, 1), CardinalFrame.Y_POS));
        assertThrows(IllegalArgumentException.class, () -> face(box(0, 0, 0, 0, 0, 1), CardinalFrame.Y_POS));
        var source = cardinalAtFeetOrigin(CardinalFrame.Y_POS);
        var target = face(box(-1, -1, -1, 1, -1, 1), CardinalFrame.Y_POS);
        assertThrows(IllegalArgumentException.class, () -> BootSurfaceGeometry.measure(source, target, -1));
        assertThrows(IllegalArgumentException.class, () -> source.queryBounds(Double.NaN));
        assertThrows(NullPointerException.class, () -> BootSurfaceGeometry.fromAccepted(null));
    }

    private static BootSurfaceGeometry.Source cardinalAtFeetOrigin(CardinalFrame frame) {
        return BootSurfaceGeometry.fromAccepted(snapshot(new PlayerBodyPlacement(frame.up(), frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, BODY))));
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerBodyPlacement body) {
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 3),
                4, body, ZERO);
    }

    private static BootSurfaceGeometry.Proximity measured(BootSurfaceGeometry.Source source,
                                                          BootSurfaceGeometry.FaceRectangle face, double reach) {
        return assertInstanceOf(BootSurfaceGeometry.Proximity.class, BootSurfaceGeometry.measure(source, face, reach));
    }

    private static BootSurfaceGeometry.FaceRectangle face(Aabb bounds, CardinalFrame frame) {
        return new BootSurfaceGeometry.FaceRectangle(bounds, frame);
    }
    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
    private static BigDecimal square(double value) { return new BigDecimal(value).pow(2); }
    private static double[] values(Vec3d v) { return new double[] {v.x(), v.y(), v.z()}; }
    private static void assertWithin(Vec3d point, Aabb bounds) {
        double[] p = values(point);
        double[] low = values(bounds.min());
        double[] high = values(bounds.max());
        for (int axis = 0; axis < 3; axis++) assertTrue(p[axis] >= low[axis] && p[axis] <= high[axis]);
    }
    private static void assertVectorNear(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1e-15);
        assertEquals(expected.y(), actual.y(), 1e-15);
        assertEquals(expected.z(), actual.z(), 1e-15);
    }
}
