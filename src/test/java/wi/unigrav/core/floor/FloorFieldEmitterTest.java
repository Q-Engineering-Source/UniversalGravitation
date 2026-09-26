package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

class FloorFieldEmitterTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    void everyFacePullsBackTowardItsDesignatedFace() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);

        assertVector(new Vec3(0.0, 8.0, 0.0),
                emitter(Face.DOWN, profile).accelerationAt(new Vec3(10.5, 20.0, 30.5)));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                emitter(Face.UP, profile).accelerationAt(new Vec3(10.5, 21.0, 30.5)));
        assertVector(new Vec3(0.0, 0.0, 8.0),
                emitter(Face.NORTH, profile).accelerationAt(new Vec3(10.5, 20.5, 30.0)));
        assertVector(new Vec3(0.0, 0.0, -8.0),
                emitter(Face.SOUTH, profile).accelerationAt(new Vec3(10.5, 20.5, 31.0)));
        assertVector(new Vec3(8.0, 0.0, 0.0),
                emitter(Face.WEST, profile).accelerationAt(new Vec3(10.0, 20.5, 30.5)));
        assertVector(new Vec3(-8.0, 0.0, 0.0),
                emitter(Face.EAST, profile).accelerationAt(new Vec3(11.0, 20.5, 30.5)));
    }

    @Test
    void everyFaceUsesTheSameNormalAndHalfOpenColumnContract() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);

        for (Face face : Face.values()) {
            FloorFieldEmitter emitter = emitter(face, profile);
            assertEquals(Vec3.ZERO, emitter.accelerationAt(normalPoint(face, -0.25)),
                    face + " must exclude the block interior behind its face");
            assertVector(expectedAcceleration(face, 8.0), emitter.accelerationAt(normalPoint(face, 2.0)));
            assertVector(expectedAcceleration(face, 4.0), emitter.accelerationAt(normalPoint(face, 4.0)));
            assertEquals(Vec3.ZERO, emitter.accelerationAt(normalPoint(face, 6.0)),
                    face + " range endpoint must be exclusive");

            assertVector(expectedAcceleration(face, 8.0),
                    emitter.accelerationAt(tangentPoint(face, false, tangentMinimum(face, false))));
            assertEquals(Vec3.ZERO,
                    emitter.accelerationAt(tangentPoint(face, false, tangentMinimum(face, false) + 1.0)),
                    face + " first tangent upper edge must be excluded");
            assertVector(expectedAcceleration(face, 8.0),
                    emitter.accelerationAt(tangentPoint(face, true, tangentMinimum(face, true))));
            assertEquals(Vec3.ZERO,
                    emitter.accelerationAt(tangentPoint(face, true, tangentMinimum(face, true) + 1.0)),
                    face + " second tangent upper edge must be excluded");
        }
    }

    @Test
    void profileHasInclusivePlateauAndLinearFalloffToExclusiveRange() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldEmitter emitter = emitter(Face.UP, profile);

        assertEquals(8.0, profile.strengthAt(0.0), EPSILON);
        assertEquals(8.0, profile.strengthAt(2.0), EPSILON);
        assertEquals(4.0, profile.strengthAt(4.0), EPSILON);
        assertEquals(0.0, profile.strengthAt(6.0), EPSILON);
        assertVector(new Vec3(0.0, -8.0, 0.0),
                emitter.accelerationAt(new Vec3(10.5, 23.0, 30.5)));
        assertVector(new Vec3(0.0, -4.0, 0.0),
                emitter.accelerationAt(new Vec3(10.5, 25.0, 30.5)));
        assertEquals(Vec3.ZERO, emitter.accelerationAt(new Vec3(10.5, 27.0, 30.5)));
    }

    @Test
    void faceSurfaceIsIncludedButBlockInteriorAndTangentUpperEdgesAreExcluded() {
        FloorFieldEmitter emitter = new FloorFieldEmitter(4, 5, 6, Face.UP,
                new FloorFieldProfile(8.0, 2.0, 6.0));

        assertVector(new Vec3(0.0, -8.0, 0.0),
                emitter.accelerationAt(new Vec3(4.0, 6.0, 6.0)));
        assertEquals(Vec3.ZERO, emitter.accelerationAt(new Vec3(4.5, 5.999, 6.5)));
        assertEquals(Vec3.ZERO, emitter.accelerationAt(new Vec3(5.0, 6.0, 6.5)));
        assertEquals(Vec3.ZERO, emitter.accelerationAt(new Vec3(4.5, 6.0, 7.0)));
    }

    @Test
    void adjacentTangentTilesGiveTheSharedEdgeOneOwner() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldEmitter left = new FloorFieldEmitter(0, 0, 0, Face.UP, profile);
        FloorFieldEmitter right = new FloorFieldEmitter(1, 0, 0, Face.UP, profile);
        Vec3 pointOnSharedEdge = new Vec3(1.0, 1.0, 0.5);

        assertEquals(Vec3.ZERO, left.accelerationAt(pointOnSharedEdge));
        assertVector(new Vec3(0.0, -8.0, 0.0), right.accelerationAt(pointOnSharedEdge));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                left.accelerationAt(pointOnSharedEdge).add(right.accelerationAt(pointOnSharedEdge)));
    }

    @Test
    void negativeCoordinatesAndIntegerExtremesDoNotWrapColumnBounds() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldEmitter negative = new FloorFieldEmitter(-3, -4, -5, Face.UP, profile);
        assertVector(new Vec3(0.0, -8.0, 0.0),
                negative.accelerationAt(new Vec3(-3.0, -3.0, -5.0)));

        FloorFieldEmitter maximum = new FloorFieldEmitter(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Face.UP, profile);
        Vec3 maximumSurface = new Vec3(Integer.MAX_VALUE, (double) Integer.MAX_VALUE + 1.0,
                Integer.MIN_VALUE);
        assertVector(new Vec3(0.0, -8.0, 0.0), maximum.accelerationAt(maximumSurface));
        assertEquals(Vec3.ZERO, maximum.accelerationAt(new Vec3(
                (double) Integer.MAX_VALUE + 1.0, (double) Integer.MAX_VALUE + 1.0,
                Integer.MIN_VALUE)));

        FloorFieldEmitter minimum = new FloorFieldEmitter(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MIN_VALUE, Face.WEST, profile);
        assertVector(new Vec3(8.0, 0.0, 0.0), minimum.accelerationAt(new Vec3(
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE)));
    }

    @Test
    void invalidProfilesAndQueriesFailFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new FloorFieldProfile(Double.NaN, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FloorFieldProfile(Double.POSITIVE_INFINITY, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new FloorFieldProfile(-1.0, 0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new FloorFieldProfile(1.0, -0.1, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new FloorFieldProfile(1.0, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new FloorFieldProfile(1.0, 0.0, Double.POSITIVE_INFINITY));

        FloorFieldProfile profile = new FloorFieldProfile(1.0, 0.0, 1.0);
        assertThrows(IllegalArgumentException.class, () -> profile.strengthAt(-0.1));
        assertThrows(IllegalArgumentException.class, () -> profile.strengthAt(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new FloorFieldEmitter(0, 0, 0, null, profile));
        assertThrows(IllegalArgumentException.class,
                () -> new FloorFieldEmitter(0, 0, 0, Face.UP, null));

        FloorFieldEmitter emitter = emitter(Face.UP, profile);
        assertThrows(IllegalArgumentException.class, () -> emitter.accelerationAt(null));
        assertThrows(IllegalArgumentException.class,
                () -> emitter.accelerationAt(new Vec3(Double.NaN, 1.0, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> emitter.accelerationAt(new Vec3(0.5, Double.NEGATIVE_INFINITY, 0.5)));
    }

    @Test
    void zeroStrengthIsAZeroContributionAndEmittersCanComposeAtAPoint() {
        FloorFieldEmitter zero = new FloorFieldEmitter(0, 0, 0, Face.UP,
                new FloorFieldProfile(0.0, 0.0, 1.0));
        assertEquals(Vec3.ZERO, zero.accelerationAt(new Vec3(0.5, 1.0, 0.5)));

        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldEmitter up = new FloorFieldEmitter(0, 0, 0, Face.UP, profile);
        FloorFieldEmitter north = new FloorFieldEmitter(0, 1, 1, Face.NORTH, profile);
        Vec3 intersection = new Vec3(0.5, 1.0, 0.5);
        assertVector(new Vec3(0.0, -8.0, 8.0),
                up.accelerationAt(intersection).add(north.accelerationAt(intersection)));

        FloorFieldEmitter down = new FloorFieldEmitter(0, 1, 0, Face.DOWN, profile);
        assertEquals(Vec3.ZERO, up.accelerationAt(new Vec3(0.5, 1.0, 0.5))
                .add(down.accelerationAt(new Vec3(0.5, 1.0, 0.5))));
    }

    private static FloorFieldEmitter emitter(Face face, FloorFieldProfile profile) {
        return new FloorFieldEmitter(10, 20, 30, face, profile);
    }

    private static Vec3 normalPoint(Face face, double outwardDistance) {
        return switch (face) {
            case DOWN -> new Vec3(10.5, 20.0 - outwardDistance, 30.5);
            case UP -> new Vec3(10.5, 21.0 + outwardDistance, 30.5);
            case NORTH -> new Vec3(10.5, 20.5, 30.0 - outwardDistance);
            case SOUTH -> new Vec3(10.5, 20.5, 31.0 + outwardDistance);
            case WEST -> new Vec3(10.0 - outwardDistance, 20.5, 30.5);
            case EAST -> new Vec3(11.0 + outwardDistance, 20.5, 30.5);
        };
    }

    private static Vec3 tangentPoint(Face face, boolean secondTangent, double coordinate) {
        Vec3 point = normalPoint(face, 0.0);
        return switch (face) {
            case DOWN, UP -> secondTangent
                    ? new Vec3(point.x(), point.y(), coordinate)
                    : new Vec3(coordinate, point.y(), point.z());
            case NORTH, SOUTH -> secondTangent
                    ? new Vec3(point.x(), coordinate, point.z())
                    : new Vec3(coordinate, point.y(), point.z());
            case WEST, EAST -> secondTangent
                    ? new Vec3(point.x(), point.y(), coordinate)
                    : new Vec3(point.x(), coordinate, point.z());
        };
    }

    private static double tangentMinimum(Face face, boolean secondTangent) {
        return switch (face) {
            case DOWN, UP -> secondTangent ? 30.0 : 10.0;
            case NORTH, SOUTH -> secondTangent ? 20.0 : 10.0;
            case WEST, EAST -> secondTangent ? 30.0 : 20.0;
        };
    }

    private static Vec3 expectedAcceleration(Face face, double strength) {
        return switch (face) {
            case DOWN -> new Vec3(0.0, strength, 0.0);
            case UP -> new Vec3(0.0, -strength, 0.0);
            case NORTH -> new Vec3(0.0, 0.0, strength);
            case SOUTH -> new Vec3(0.0, 0.0, -strength);
            case WEST -> new Vec3(strength, 0.0, 0.0);
            case EAST -> new Vec3(-strength, 0.0, 0.0);
        };
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
