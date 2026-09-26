package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorFieldIndexTest {

    private static final double EPSILON = 1.0e-12;

    @Test
    void separateIndexInstancesKeepWorldsIsolated() {
        FloorFieldIndex firstWorld = new FloorFieldIndex();
        FloorFieldIndex secondWorld = new FloorFieldIndex();
        Source source = source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0);
        firstWorld.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(source));

        assertVector(new Vec3(0.0, -8.0, 0.0),
                firstWorld.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        assertEquals(Vec3.ZERO,
                secondWorld.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void replacementAndIdentityMatchedRemovalProtectNewerChunkGenerations() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        EqualOwner oldOwner = new EqualOwner("old");
        EqualOwner newOwner = new EqualOwner("new");
        index.replaceChunk(key, oldOwner, List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        index.replaceChunk(key, newOwner, List.of(source(0, 0, 0, Face.EAST, Kind.GRAVITY, 8.0)));

        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        assertVector(new Vec3(-8.0, 0.0, 0.0),
                index.sample(new Vec3(1.0, 0.5, 0.5), 0.0).gravityAccel());
        assertFalse(index.removeChunk(key, oldOwner));
        assertTrue(index.removeChunk(key, newOwner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(1.0, 0.5, 0.5), 0.0).gravityAccel());
    }

    @Test
    void equalButDistinctOwnersCannotRemoveTheCurrentGeneration() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        EqualOwner current = new EqualOwner("same-value");
        EqualOwner equalButStale = new EqualOwner("same-value");
        assertEquals(current, equalButStale);
        index.replaceChunk(key, current, List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));

        assertFalse(index.removeChunk(key, equalButStale));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        assertTrue(index.removeChunk(key, current));
    }

    @Test
    void invalidReplacementBatchLeavesTheExistingChunkUntouched() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        index.replaceChunk(key, owner, List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));

        assertThrows(IllegalArgumentException.class, () -> index.replaceChunk(key, new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 0, 0, Face.DOWN, Kind.GRAVITY, 8.0))));
        assertThrows(IllegalArgumentException.class, () -> index.replaceChunk(key, new Object(), List.of(
                source(16, 0, 0, Face.UP, Kind.GRAVITY, 8.0))));

        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void halfOpenEdgesAndNegativeChunkCoordinatesSelectOnlyTheirOwner() {
        FloorFieldIndex index = new FloorFieldIndex();
        Object owner = new Object();
        index.replaceChunk(new ChunkKey(0, 0), owner, List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(1, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        Sample sharedEdge = index.sample(new Vec3(1.0, 1.0, 0.5), 0.0);
        assertVector(new Vec3(0.0, -8.0, 0.0), sharedEdge.gravityAccel());
        assertEquals(Set.of(Face.UP), sharedEdge.effectiveGravityFaces());

        Source negative = source(-1, 0, -1, Face.UP, Kind.GRAVITY, 8.0);
        index.replaceChunk(new ChunkKey(-1, -1), new Object(), List.of(negative));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(-1.0, 1.0, -1.0), 0.0).gravityAccel());
    }

    @Test
    void sameOpposingAndDiagonalContributionsSumWithoutLosingEffectiveFaces() {
        FloorFieldIndex sameDirection = new FloorFieldIndex();
        sameDirection.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 1, 0, Face.UP, Kind.GRAVITY, 8.0)));
        assertVector(new Vec3(0.0, -16.0, 0.0),
                sameDirection.sample(new Vec3(0.5, 2.0, 0.5), 0.0).gravityAccel());

        FloorFieldIndex opposing = new FloorFieldIndex();
        opposing.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 1, 0, Face.DOWN, Kind.GRAVITY, 8.0)));
        Sample cancellation = opposing.sample(new Vec3(0.5, 1.0, 0.5), 0.0);
        assertEquals(Vec3.ZERO, cancellation.gravityAccel());
        assertEquals(Set.of(Face.DOWN, Face.UP), cancellation.effectiveGravityFaces());

        FloorFieldIndex diagonal = new FloorFieldIndex();
        diagonal.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 1, 1, Face.NORTH, Kind.GRAVITY, 8.0)));
        Sample diagonalSample = diagonal.sample(new Vec3(0.5, 1.0, 0.5), 0.0);
        assertVector(new Vec3(0.0, -8.0, 8.0), diagonalSample.gravityAccel());
        assertEquals(Set.of(Face.UP, Face.NORTH), diagonalSample.effectiveGravityFaces());
    }

    @Test
    void magneticResponseFiltersAndScalesOnlyMagneticSources() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.MAGNETIC, 4.0)));

        Sample ignored = index.sample(new Vec3(0.5, 1.0, 0.5), 0.0);
        assertEquals(Vec3.ZERO, ignored.magneticAccel());
        assertTrue(ignored.effectiveMagneticFaces().isEmpty());
        Sample doubled = index.sample(new Vec3(0.5, 1.0, 0.5), 2.0);
        assertVector(new Vec3(0.0, -8.0, 0.0), doubled.magneticAccel());
        assertEquals(Set.of(Face.UP), doubled.effectiveMagneticFaces());
        assertEquals(Vec3.ZERO, doubled.gravityAccel());
    }

    @Test
    void orderingIsStableAcrossSourceListOrderAndChunkReplacement() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Source lower = source(0, 0, 0, Face.UP, Kind.GRAVITY, 1.0);
        Source upper = source(0, 1, 0, Face.UP, Kind.GRAVITY, 1.0e16);
        index.replaceChunk(key, new Object(), List.of(upper, lower));
        Vec3 first = index.sample(new Vec3(0.5, 2.0, 0.5), 0.0).gravityAccel();

        index.replaceChunk(key, new Object(), List.of(lower, upper));
        Vec3 second = index.sample(new Vec3(0.5, 2.0, 0.5), 0.0).gravityAccel();
        assertEquals(first, second);
        assertVector(new Vec3(0.0, -1.0e16, 0.0), second);
    }

    @Test
    void allSixIndexedFacesMatchDirectEmitterAtProfileEdges() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 6.0);
        for (Face face : Face.values()) {
            Source source = new Source(10, 20, 30, face, Kind.GRAVITY, profile);
            FloorFieldIndex index = new FloorFieldIndex();
            index.replaceChunk(new ChunkKey(0, 1), new Object(), List.of(source));
            FloorFieldEmitter direct = new FloorFieldEmitter(10, 20, 30, face, profile);

            for (double distance : new double[] {-0.25, 0.0, 2.0, 4.0, 6.0}) {
                Vec3 point = normalPoint(face, distance);
                assertEquals(direct.accelerationAt(point), index.sample(point, 0.0).gravityAccel(),
                        face + " at outward distance " + distance);
                var standing = index.sampleForStanding(point, 0.0);
                assertEquals(index.sample(point, 0.0), standing.forces());
                double expectedStrength = distance < 0.0 ? 0.0 : profile.strengthAt(distance);
                assertEquals(expectedStrength == 0.0 ? Map.of() : Map.of(face, expectedStrength),
                        standing.gravityStrengths());
                assertTrue(standing.magneticStrengths().isEmpty());
            }
        }
    }

    @Test
    void separateChunkInsertionOrderDoesNotChangeTheirSamples() {
        Source left = source(15, 0, 0, Face.UP, Kind.GRAVITY, 8.0);
        Source right = source(16, 0, 0, Face.UP, Kind.GRAVITY, 8.0);
        FloorFieldIndex leftThenRight = new FloorFieldIndex();
        leftThenRight.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(left));
        leftThenRight.replaceChunk(new ChunkKey(1, 0), new Object(), List.of(right));
        FloorFieldIndex rightThenLeft = new FloorFieldIndex();
        rightThenLeft.replaceChunk(new ChunkKey(1, 0), new Object(), List.of(right));
        rightThenLeft.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(left));

        assertEquals(leftThenRight.sample(new Vec3(15.5, 1.0, 0.5), 0.0),
                rightThenLeft.sample(new Vec3(15.5, 1.0, 0.5), 0.0));
        assertEquals(leftThenRight.sample(new Vec3(16.0, 1.0, 0.5), 0.0),
                rightThenLeft.sample(new Vec3(16.0, 1.0, 0.5), 0.0));
    }

    @Test
    void replacementAndRemovalShrinkTheActiveRangeWithoutRetainingOldEntries() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object wideOwner = new Object();
        Source wide = new Source(0, 0, 0, Face.UP, Kind.GRAVITY,
                new FloorFieldProfile(8.0, 2.0, 20.0));
        index.replaceChunk(key, wideOwner, List.of(wide));
        assertTrue(index.sample(new Vec3(0.5, 20.0, 0.5), 0.0).gravityAccel().y() < 0.0);

        Object shortOwner = new Object();
        index.replaceChunk(key, shortOwner, List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 20.0, 0.5), 0.0).gravityAccel());
        assertTrue(index.removeChunk(key, shortOwner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void removalClearAndInputFailuresAreExplicit() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        index.replaceChunk(key, owner, List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        assertTrue(index.removeChunk(key, owner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        index.replaceChunk(key, new Object(), List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        index.clear();
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());

        assertThrows(IllegalArgumentException.class, () -> index.sample(null, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> index.sample(new Vec3(Double.NaN, 0.0, 0.0), 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> index.sample(Vec3.ZERO, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> index.sample(Vec3.ZERO, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> index.replaceChunk(null, new Object(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> index.replaceChunk(key, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Source(0, 0, 0, null, Kind.GRAVITY, profile(1.0)));
    }

    @Test
    void sampledFaceSetsAreIndependentImmutableCopies() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        Sample sample = index.sample(new Vec3(0.5, 1.0, 0.5), 0.0);

        assertThrows(UnsupportedOperationException.class,
                () -> sample.effectiveGravityFaces().add(Face.DOWN));
        assertEquals(Set.of(Face.UP), sample.effectiveGravityFaces());
    }

    @Test
    void overflowInAdditionOrMagneticScalingFailsInsteadOfReturningNonFiniteOutput() {
        FloorFieldIndex summed = new FloorFieldIndex();
        FloorFieldProfile enormous = new FloorFieldProfile(Double.MAX_VALUE, 2.0, 3.0);
        summed.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, enormous),
                new Source(0, 1, 0, Face.UP, Kind.GRAVITY, enormous)));
        assertThrows(IllegalStateException.class,
                () -> summed.sample(new Vec3(0.5, 2.0, 0.5), 0.0));

        FloorFieldIndex scaled = new FloorFieldIndex();
        scaled.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                new Source(0, 0, 0, Face.UP, Kind.MAGNETIC, enormous)));
        assertThrows(IllegalStateException.class,
                () -> scaled.sample(new Vec3(0.5, 1.0, 0.5), 2.0));
    }

    @Test
    void standingStrengthsAddBeforeOpposingAndDiagonalCancellation() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 4.0),
                source(0, 1, 0, Face.UP, Kind.GRAVITY, 4.0),
                source(0, 3, 0, Face.DOWN, Kind.GRAVITY, 8.0),
                source(1, 2, 0, Face.WEST, Kind.MAGNETIC, 3.0)));
        Vec3 point = new Vec3(0.5, 2.0, 0.5);
        var standing = index.sampleForStanding(point, 2.0);
        assertEquals(index.sample(point, 2.0), standing.forces());
        assertEquals(Vec3.ZERO, standing.forces().gravityAccel());
        assertEquals(Map.of(Face.UP, 8.0, Face.DOWN, 8.0), standing.gravityStrengths());
        assertEquals(new Vec3(6.0, 0.0, 0.0), standing.forces().magneticAccel());
        assertEquals(Map.of(Face.WEST, 6.0), standing.magneticStrengths());
        var ignored = index.sampleForStanding(point, 0.0);
        assertEquals(standing.gravityStrengths(), ignored.gravityStrengths());
        assertTrue(ignored.magneticStrengths().isEmpty());
        assertTrue(ignored.forces().effectiveMagneticFaces().isEmpty());
    }

    @Test
    void magneticUnderflowDoesNotTurnLegacyFacePresenceIntoAnInventedStrength() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.MAGNETIC, Double.MIN_VALUE)));
        Vec3 point = new Vec3(0.5, 1.0, 0.5);
        var underflow = index.sampleForStanding(point, 0.5);
        assertEquals(index.sample(point, 0.5), underflow.forces());
        assertEquals(Vec3.ZERO, underflow.forces().magneticAccel());
        assertEquals(Set.of(Face.UP), underflow.forces().effectiveMagneticFaces());
        assertTrue(underflow.magneticStrengths().isEmpty());
        assertEquals(Map.of(Face.UP, Double.MIN_VALUE), index.sampleForStanding(point, 1.0).magneticStrengths());
    }

    @Test
    void standingMapsRetainTheirObservationAndRejectMutation() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 1, 0, Face.UP, Kind.MAGNETIC, 4.0)));
        var standing = index.sampleForStanding(new Vec3(0.5, 2.0, 0.5), 2.0);
        assertThrows(UnsupportedOperationException.class, () -> standing.gravityStrengths().put(Face.DOWN, 1.0));
        assertThrows(UnsupportedOperationException.class, () -> standing.magneticStrengths().entrySet().iterator().next().setValue(1.0));
        index.clear();
        assertEquals(Map.of(Face.UP, 8.0), standing.gravityStrengths());
        assertEquals(Map.of(Face.UP, 8.0), standing.magneticStrengths());
        var empty = index.sampleForStanding(Vec3.ZERO, 1.0);
        assertEquals(index.sample(Vec3.ZERO, 1.0), empty.forces());
        assertTrue(empty.gravityStrengths().isEmpty());
        assertTrue(empty.magneticStrengths().isEmpty());
    }

    @Test
    void standingQueryKeepsHalfOpenFootprintsNegativeChunksAndSourceOrder() {
        FloorFieldIndex index = new FloorFieldIndex();
        Source small = source(-1, 0, -1, Face.UP, Kind.GRAVITY, 1.0);
        Source large = source(-1, 1, -1, Face.UP, Kind.GRAVITY, 1.0e16);
        ChunkKey key = new ChunkKey(-1, -1);
        index.replaceChunk(key, new Object(), List.of(large, small));
        Vec3 point = new Vec3(-1.0, 2.0, -1.0);
        var first = index.sampleForStanding(point, 0.0);
        assertEquals(index.sample(point, 0.0), first.forces());
        assertEquals(Map.of(Face.UP, 1.0e16), first.gravityStrengths());
        assertTrue(index.sampleForStanding(new Vec3(0.0, 2.0, -1.0), 0.0).gravityStrengths().isEmpty());
        index.replaceChunk(key, new Object(), List.of(small, large));
        assertEquals(first.forces(), index.sampleForStanding(point, 0.0).forces());
        assertEquals(first.gravityStrengths(), index.sampleForStanding(point, 0.0).gravityStrengths());
    }

    @Test
    void standingScoreOverflowIsRejectedEvenWhenNetForceRemainsFinite() {
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 3, 0, Face.DOWN, Kind.GRAVITY, Double.MAX_VALUE),
                source(0, 0, 0, Face.UP, Kind.GRAVITY, Double.MAX_VALUE),
                source(0, 1, 0, Face.UP, Kind.GRAVITY, Double.MAX_VALUE)));
        Vec3 point = new Vec3(0.5, 2.0, 0.5);
        // DOWN is added first, so the legacy vector accumulator never overflows.
        assertEquals(new Vec3(0.0, -Double.MAX_VALUE, 0.0), index.sample(point, 0.0).gravityAccel());
        assertThrows(IllegalStateException.class, () -> index.sampleForStanding(point, 0.0));
    }

    @Test
    void standingQueryRejectsInvalidInputsAndMagneticScalingOverflow() {
        FloorFieldIndex index = new FloorFieldIndex();
        assertThrows(IllegalArgumentException.class, () -> index.sampleForStanding(null, 0.0));
        assertThrows(IllegalArgumentException.class, () -> index.sampleForStanding(new Vec3(0.0, Double.NaN, 0.0), 0.0));
        for (double response : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> index.sampleForStanding(Vec3.ZERO, response));
        }
        index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                source(0, 0, 0, Face.UP, Kind.MAGNETIC, Double.MAX_VALUE)));
        assertThrows(IllegalStateException.class, () -> index.sampleForStanding(new Vec3(0.5, 1.0, 0.5), 2.0));
    }

    private static Source source(int x, int y, int z, Face face, Kind kind, double strength) {
        return new Source(x, y, z, face, kind, profile(strength));
    }

    private static FloorFieldProfile profile(double strength) {
        return new FloorFieldProfile(strength, 2.0, 6.0);
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

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private record EqualOwner(String id) {
    }
}
