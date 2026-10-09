package wi.unigrav.core.player;

import java.util.Set;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerBodyPlacementTest {
    private static final Vec3d CENTER = new Vec3d(12.5, 40.25, -23.5);
    // Exact binary values make geometry assertions independent of native float profile rounding.
    private static final PlayerBodyShape.Dimensions DIMENSIONS = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);

    @Test
    void cardinalAcceptsLoggedSwimmingDimensionsWithoutInventingCapsuleGeometry() {
        var dimensions = new PlayerBodyShape.Dimensions(
                0.6000000238418579, 0.5999999642372131, 0.4000000059604645);
        var bounds = new Aabb(new Vec3d(-dimensions.width() / 2, 65, -dimensions.width() / 2),
                new Vec3d(dimensions.width() / 2, 65 + dimensions.height(), dimensions.width() / 2));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, new Vec3d(0, 65, 0),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        assertEquals(bounds, placement.bounds());
        assertEquals(65, placement.physicalFeet().orElseThrow().y());
        assertEquals(dimensions.height(), placement.shape().dimensions().height());
        assertThrows(IllegalArgumentException.class, () -> new PlayerBodyShape.FreeCapsule(dimensions));
        assertThrows(IllegalArgumentException.class, placement::capsule);
        assertThrows(IllegalArgumentException.class, () -> CapsulePlacementMath.pose(placement));
    }

    @Test
    void actualCardinalAnchorsUseAllSixRetainedFacesAndCapsuleStillNeedsFit() {
        var dimensions = new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F);
        var local = new Aabb(new Vec3d(-0.30000001192092896, 0.001, -0.30000001192092896),
                new Vec3d(0.30000001192092896, 1.800999952316284, 0.30000001192092896));
        for (CardinalFrame frame : CardinalFrame.values()) {
            Aabb box = frame.toWorld(local);
            Vec3d transport = new Vec3d(box.center().x(), box.min().y(), box.center().z());
            var actual = PlayerBodyPlacement.fromCardinalBounds(box, transport, frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, dimensions));
            assertEquals(box, actual.bounds());
            assertEquals(frame.toWorld(new Vec3d(0, 0.001, 0)), actual.physicalFeet().orElseThrow());
            assertEquals(frame.toWorld(new Vec3d(0, 1.6210000047683715, 0)), actual.eye());
            assertEquals(box.min().y(), actual.corridorY());
        }
        var sidewaysBox = CardinalFrame.X_POS.toWorld(local);
        var sideways = PlayerBodyPlacement.fromCardinalBounds(sidewaysBox,
                new Vec3d(sidewaysBox.center().x(), sidewaysBox.min().y(), sidewaysBox.center().z()),
                CardinalFrame.X_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.X_POS, dimensions));
        assertEquals(0.001, sideways.bounds().min().x());
        assertTrue(sideways.capsule().bounds().min().x() < sideways.bounds().min().x(),
                "nominal transition capsule is not automatically contained in solver-rounded cardinal bounds");
    }

    @Test
    void actualGeometryAndNativeTransportParticipateInValueIdentity() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, DIMENSIONS);
        var first = PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(-0.25, 0, -0.25),
                new Vec3d(0.25, 2, 0.25)), new Vec3d(0, 0, 0), Rotation3d.identity(), shape);
        var wider = PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(Math.nextDown(-0.25), 0, -0.25),
                new Vec3d(Math.nextUp(0.25), 2, 0.25)), new Vec3d(0, 0, 0), Rotation3d.identity(), shape);
        assertEquals(first.center(), wider.center());
        assertNotEquals(first, wider);
        assertEquals(2, Set.of(first, wider).size());
        var zeroEquivalent = PlayerBodyPlacement.fromCardinalBounds(first.bounds(), new Vec3d(-0.0, -0.0, -0.0),
                Rotation3d.identity(), shape);
        assertEquals(first, zeroEquivalent);
        assertEquals(first.hashCode(), zeroEquivalent.hashCode());

        // Native setPosition keeps supplied x; resetPositionToBB recomputes its midpoint.
        var nativeShape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(0.6F, 2, 1.75));
        var nativeBox = new Aabb(new Vec3d(0.1 - (double) 0.6F / 2, 0, -(double) 0.6F / 2),
                new Vec3d(0.1 + (double) 0.6F / 2, 2, (double) 0.6F / 2));
        var original = PlayerBodyPlacement.fromCardinalBounds(nativeBox, new Vec3d(0.1, 0, 0),
                Rotation3d.identity(), nativeShape);
        var reset = PlayerBodyPlacement.fromCardinalBounds(nativeBox,
                new Vec3d((nativeBox.min().x() + nativeBox.max().x()) / 2, 0, 0), Rotation3d.identity(), nativeShape);
        assertEquals(0.1, original.transport().x());
        assertNotEquals(original, reset);
    }

    @Test
    void malformedActualCardinalGeometryFailsWithoutInventingAPlacement() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, DIMENSIONS);
        var box = new Aabb(new Vec3d(-0.25, 0, -0.25), new Vec3d(0.25, 2, 0.25));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(0, 1, 0), Rotation3d.identity(), shape));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(1, 0, 0), Rotation3d.identity(), shape));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(0, 0, 0), CardinalFrame.X_POS.attitude(), shape));
        assertThrows(IllegalArgumentException.class, () -> PlayerBodyPlacement.fromCardinalBounds(
                new Aabb(new Vec3d(0, 0, 0), new Vec3d(0, 2, 1)), new Vec3d(0, 0, 0),
                Rotation3d.identity(), shape));
    }

    @Test
    void allSixCardinalBodiesHaveExactBoundsFeetAndEye() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            var body = cardinal(frame);
            Vec3d up = frame.up();
            Vec3d half = body.bounds().halfExtents();
            assertEquals(new Vec3d(0.25 + 0.75 * Math.abs(up.x()), 0.25 + 0.75 * Math.abs(up.y()),
                    0.25 + 0.75 * Math.abs(up.z())), half);
            assertEquals(CENTER, body.bounds().center());
            assertEquals(CENTER.subtract(up), body.physicalFeet().orElseThrow());
            assertEquals(CENTER.add(up.multiply(0.75)), body.eye());
            assertEquals(body.bounds(), body.capsule().bounds());
        }
    }

    @Test
    void tangentBodyHeadingDoesNotRotateTheCardinalCollisionBox() {
        var ordinary = cardinal(CardinalFrame.Y_POS);
        var heading = new PlayerBodyPlacement(CENTER, new Rotation3d(0, 1, 0, 1), ordinary.shape());
        assertEquals(ordinary.bounds(), heading.bounds());
        assertEquals(ordinary.eye(), heading.eye());
        assertNotEquals(ordinary.attitude(), heading.attitude());
        assertThrows(IllegalArgumentException.class, () -> new PlayerBodyPlacement(
                CENTER, CardinalFrame.X_POS.attitude(), ordinary.shape()));
    }

    @Test
    void arbitraryAttitudesCreateRealChbCapsulesAndNotStandingFeet() {
        Rotation3d[] orientations = {new Rotation3d(1, 2, 3, 4), new Rotation3d(-5, 2, 1, 3),
                CardinalFrame.Y_NEG.attitude(), Rotation3d.identity()};
        for (Rotation3d attitude : orientations) {
            var body = capsule(CENTER, attitude);
            var geometry = body.capsule();
            Vec3d halfLine = attitude.basisY().multiply(0.75);
            // CHB canonicalizes endpoint order; head/feet direction belongs to body attitude.
            assertEquals(Set.of(CENTER.subtract(halfLine), CENTER.add(halfLine)),
                    Set.of(geometry.centerline().start(), geometry.centerline().end()));
            assertEquals(0.25, geometry.radius());
            assertEquals(geometry.bounds(), body.bounds());
            assertEquals(CENTER.add(attitude.basisY().multiply(0.75)), body.eye());
            assertTrue(body.physicalFeet().isEmpty());
        }
    }

    @Test
    void oppositeBodyAxesHaveTheSameUndirectedCapsuleButDifferentEyes() {
        var upright = capsule(CENTER, Rotation3d.identity());
        var inverted = capsule(CENTER, CardinalFrame.Y_NEG.attitude());
        assertEquals(upright.capsule(), inverted.capsule());
        assertEquals(upright.transport(), inverted.transport());
        assertEquals(new Vec3d(0, 1, 0), upright.bodyUp());
        assertEquals(new Vec3d(0, -1, 0), inverted.bodyUp());
        assertEquals(CENTER.add(new Vec3d(0, 0.75, 0)), upright.eye());
        assertEquals(CENTER.add(new Vec3d(0, -0.75, 0)), inverted.eye());
        assertEquals(0, upright.translationTo(inverted).lengthSquared());
        // Equal endpoint geometry is not proof that the intervening 180-degree rotation fits.
    }

    @Test
    void transportRoundTripsWithTheMatchingShapeAtOrdinaryAndWorldBorderCoordinates() {
        for (Vec3d center : new Vec3d[]{CENTER, new Vec3d(-29_999_999, 257, 29_999_999), new Vec3d(0, -1, 0)}) {
            for (CardinalFrame frame : CardinalFrame.values()) {
                assertRoundTrip(new PlayerBodyPlacement(center, frame.attitude(),
                        new PlayerBodyShape.Cardinal(frame, DIMENSIONS)), 1e-8);
                assertRoundTrip(capsule(center, frame.attitude()), 1e-8);
            }
            assertRoundTrip(capsule(center, new Rotation3d(1, 2, 3, 4)), 1e-8);
        }
    }

    @Test
    void fixedCenterRotationChangesTransportButNeverClaimsTranslation() {
        var upright = capsule(CENTER, Rotation3d.identity());
        var sideways = capsule(CENTER, CardinalFrame.X_POS.attitude());
        assertEquals(0, upright.translationTo(sideways).lengthSquared());
        assertEquals(0.75, sideways.transport().y() - upright.transport().y(), 1e-12);
        var translated = capsule(CENTER.add(new Vec3d(1, 2, 3)), sideways.attitude());
        assertEquals(new Vec3d(1, 2, 3), sideways.translationTo(translated));
        // A different physical rotation is still unvalidated: this module only derives its placement.
    }

    @Test
    void corridorCoordinateRemainsProxyMinYEvenWhenPhysicalFeetAreElsewhere() {
        var upsideDown = cardinal(CardinalFrame.Y_NEG);
        assertEquals(39.25, upsideDown.corridorY());
        assertEquals(41.25, upsideDown.physicalFeet().orElseThrow().y());
        assertEquals(upsideDown.transport().y(), upsideDown.corridorY());
        var atBoundary = capsule(new Vec3d(0, 257, 0), Rotation3d.identity());
        assertEquals(256, atBoundary.corridorY());
    }

    @Test
    void sphereAndNativeFloatProfilesRemainFiniteAndUseSuppliedDimensions() {
        var sphere = new PlayerBodyPlacement(CENTER, new Rotation3d(1, 2, 3, 4),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.6F, 0.6F, 0.4F)));
        assertEquals(CENTER, sphere.capsule().centerline().start());
        assertEquals(CENTER, sphere.capsule().centerline().end());
        var crouch = new PlayerBodyPlacement(CENTER, CardinalFrame.Z_NEG.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG,
                        new PlayerBodyShape.Dimensions(0.6F, 1.65F, 1.62F - 0.08F)));
        assertEquals(1.65F, crouch.bounds().max().z() - crouch.bounds().min().z(), 1e-12);
        assertRoundTrip(crouch, 1e-12);
    }

    @Test
    void actualDerivedCapsuleHitsThinWallAtItsMiddleThroughPinnedPublicEntry() {
        var body = capsule(new Vec3d(0, 0, 0), Rotation3d.identity());
        var wall = new Aabb(new Vec3d(1, -0.125, -0.5), new Vec3d(1.125, 0.125, 0.5));
        var hit = CapsuleAabbCast3d.cast(body.capsule(), new Vec3d(2, 0, 0), wall);
        assertEquals(Kind.HIT_AFTER_CLEAR, hit.kind());
        assertEquals(0.375, hit.bandBracket().orElseThrow().insideContactBandFraction(), 1e-7);
        assertTrue(body.capsule().centerline().start().y() + 0.25 < wall.min().y());
        assertTrue(body.capsule().centerline().end().y() - 0.25 > wall.max().y());
    }

    @Test
    void invalidDimensionsAndAttitudesFailInsteadOfInventingAUsablePlayer() {
        double[][] bad = {{0, 2, 1}, {-1, 2, 1}, {0.5, 0, 1}, {0.5, -1, 1}, {0.5, 2, -1}, {0.5, 2, Double.POSITIVE_INFINITY},
                {Double.NaN, 2, 1}, {0.5, Double.POSITIVE_INFINITY, 1}, {0.5, 2, Double.NaN}, {Double.MIN_VALUE, 2, 1}};
        for (double[] input : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PlayerBodyShape.Dimensions(input[0], input[1], input[2]));
        }
        var shortWide = new PlayerBodyShape.Dimensions(3, 2, 1);
        assertFalse(shortWide.supportsCapsule());
        assertThrows(IllegalArgumentException.class, () -> new PlayerBodyShape.FreeCapsule(shortWide));
        assertThrows(IllegalArgumentException.class, () -> new Rotation3d(0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new PlayerBodyPlacement(null, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(DIMENSIONS)));
    }

    private static PlayerBodyPlacement cardinal(CardinalFrame frame) {
        return new PlayerBodyPlacement(CENTER, frame.attitude(), new PlayerBodyShape.Cardinal(frame, DIMENSIONS));
    }

    private static PlayerBodyPlacement capsule(Vec3d center, Rotation3d attitude) {
        return new PlayerBodyPlacement(center, attitude, new PlayerBodyShape.FreeCapsule(DIMENSIONS));
    }

    private static void assertRoundTrip(PlayerBodyPlacement body, double tolerance) {
        var decoded = PlayerBodyPlacement.fromTransport(body.transport(), body.attitude(), body.shape());
        assertEquals(body.center().x(), decoded.center().x(), tolerance);
        assertEquals(body.center().y(), decoded.center().y(), tolerance);
        assertEquals(body.center().z(), decoded.center().z(), tolerance);
        assertEquals(body.transport().y(), decoded.transport().y(), tolerance);
        assertEquals(body.shape(), decoded.shape());
        assertEquals(body.attitude(), decoded.attitude());
    }
}
