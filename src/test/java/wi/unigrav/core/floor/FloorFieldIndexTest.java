package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
                source(0, 0, 0, Face.UP, Kind.MAGNETIC, 16.0))));
        assertThrows(IllegalArgumentException.class, () -> index.replaceChunk(key, new Object(), List.of(
                source(16, 0, 0, Face.UP, Kind.GRAVITY, 8.0))));

        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void onePhysicalGravityBlockCanOwnBothOppositeEmitterFaces() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();

        assertDoesNotThrow(() -> index.replaceChunk(key, owner, List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 0, 0, Face.DOWN, Kind.GRAVITY, 8.0))));

        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        assertVector(new Vec3(0.0, 8.0, 0.0),
                index.sample(new Vec3(0.5, -1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void aDoubleSidedEmitterPairPullsTowardBothFacesForEveryOrientation() {
        FloorFieldProfile profile = new FloorFieldProfile(32.0, 8.0, 16.0);
        for (Face face : Face.values()) {
            FloorFieldIndex index = new FloorFieldIndex();
            index.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(
                    new Source(0, 0, 0, face, Kind.GRAVITY, profile),
                    new Source(0, 0, 0, opposite(face), Kind.GRAVITY, profile)));

            Vec3 outward = normal(face);
            Vec3 center = new Vec3(0.5, 0.5, 0.5);
            Sample firstSide = index.sample(center.add(outward.scale(1.5)), 0.0);
            assertVector(outward.scale(-32.0), firstSide.gravityAccel());
            assertEquals(Set.of(face), firstSide.effectiveGravityFaces());

            Sample secondSide = index.sample(center.sub(outward.scale(1.5)), 0.0);
            assertVector(outward.scale(32.0), secondSide.gravityAccel());
            assertEquals(Set.of(opposite(face)), secondSide.effectiveGravityFaces());
        }
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
    void tangentPresenceLookupSelectsOnlyOccupiedNormalsAndExactProfileGroups() {
        List<Source> sources = List.of(
                source(2, 0, 4, Face.UP, Kind.GRAVITY, 8.0, 8.0, 16.0),
                source(3, 0, 4, Face.UP, Kind.GRAVITY, 4.0, 8.0, 16.0),
                source(3, 1, 4, Face.UP, Kind.GRAVITY, 16.0, 8.0, 16.0),
                source(3, 3, 4, Face.UP, Kind.GRAVITY, 32.0, 8.0, 16.0));
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), sources);

        Vec3 occupied = new Vec3(3.5, 5.0, 4.5);
        Sample sample = index.sample(occupied, 0.0);
        assertEquals(scalarSample(sources, occupied, 0.0), sample);
        assertVector(new Vec3(0.0, -52.0, 0.0), sample.gravityAccel());
        var standing = index.sampleForStanding(occupied, 0.0);
        assertEquals(sample, standing.forces());
        assertEquals(Map.of(Face.UP, 52.0), standing.gravityStrengths());

        Vec3 hole = new Vec3(4.5, 5.0, 4.5);
        assertEquals(scalarSample(sources, hole, 0.0), index.sample(hole, 0.0));
        assertEquals(Vec3.ZERO, index.sample(hole, 0.0).gravityAccel());
        assertTrue(index.sampleForStanding(hole, 0.0).gravityStrengths().isEmpty());

        FloorFieldIndex.RetainedCounts counts = index.retainedCounts();
        assertEquals(1L, counts.tangentNormalLookupFaces());
        assertEquals(512L, counts.tangentNormalLookupPayloadBytes());
        assertEquals(1L, counts.routeBuckets());
        assertEquals(1L, counts.routeShards());
        assertEquals(Integer.BYTES, counts.routePageNormalPrimitivePayloadBytes());
        assertEquals(1L, counts.routeShardReferenceSlots());
    }

    @Test
    void singleNormalFaceSelectsExactProfileAndSkipsTangentHoles() {
        FloorFieldProfile firstProfile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldProfile secondProfile = new FloorFieldProfile(16.0, 4.0, 8.0);
        List<Source> sources = List.of(
                new Source(2, 0, 4, Face.UP, Kind.GRAVITY, firstProfile),
                new Source(3, 0, 4, Face.UP, Kind.GRAVITY, secondProfile));
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(new ChunkKey(0, 0), new Object(), sources);

        Vec3 first = new Vec3(2.5, 3.0, 4.5);
        Sample firstSample = index.sample(first, 0.0);
        assertVector(new Vec3(0.0, -firstProfile.strength(), 0.0), firstSample.gravityAccel());
        assertEquals(scalarSample(sources, first, 0.0), firstSample);

        Vec3 second = new Vec3(3.5, 3.0, 4.5);
        Sample secondSample = index.sample(second, 0.0);
        assertVector(new Vec3(0.0, -secondProfile.strength(), 0.0), secondSample.gravityAccel());
        assertEquals(scalarSample(sources, second, 0.0), secondSample);

        Vec3 tangentHole = new Vec3(4.5, 3.0, 4.5);
        assertEquals(scalarSample(sources, tangentHole, 0.0), index.sample(tangentHole, 0.0));
        assertEquals(Vec3.ZERO, index.sample(tangentHole, 0.0).gravityAccel());
        assertTrue(index.sampleForStanding(tangentHole, 0.0).gravityStrengths().isEmpty());
    }

    @Test
    void aggregatedHolesAndMixedProfilesMatchScalarAtEveryCell() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(-1, -1);
        Object owner = new Object();
        List<Source> sources = new ArrayList<>();
        FloorFieldProfile[] profiles = {
                new FloorFieldProfile(8.0, 2.0, 8.0),
                new FloorFieldProfile(16.0, 4.0, 12.0),
                new FloorFieldProfile(32.0, 8.0, 16.0),
                new FloorFieldProfile(48.0, 6.0, 20.0)
        };
        for (int z = -16; z < 0; z++) {
            for (int x = -16; x < 0; x++) {
                if (Math.floorMod(x + z, 4) != 0) {
                    sources.add(new Source(x, -16, z, Face.UP, Kind.GRAVITY,
                            profiles[Math.floorMod(x * 3 + z, profiles.length)]));
                }
                if (Math.floorMod(x - z, 5) == 0) {
                    sources.add(new Source(x, -8, z, Face.DOWN, Kind.GRAVITY,
                            profiles[Math.floorMod(x - z, profiles.length)]));
                }
                if (Math.floorMod(x + 2 * z, 7) == 0) {
                    sources.add(new Source(x, z, -8, Face.NORTH, Kind.MAGNETIC,
                            profiles[Math.floorMod(2 * x + z, profiles.length)]));
                }
                if (Math.floorMod(2 * x - z, 9) == 0) {
                    sources.add(new Source(-8, x, z, Face.WEST, Kind.GRAVITY,
                            profiles[Math.floorMod(x + z, profiles.length)]));
                }
            }
        }
        index.replaceChunk(key, owner, sources);

        double[] responses = {0.0, 0.25, 1.0, 2.0};
        double[] coordinates = {-17.0, -16.0, -15.5, -8.0, -1.0, -0.5, 0.0};
        for (double x : coordinates) {
            for (double y : coordinates) {
                for (double z : coordinates) {
                    Vec3 point = new Vec3(x, y, z);
                    for (double response : responses) {
                        Sample expected = scalarSample(sources, point, response);
                        Sample actual = index.sample(point, response);
                        assertEquals(expected.gravityAccel(), actual.gravityAccel(), point.toString());
                        assertEquals(expected.magneticAccel(), actual.magneticAccel(), point.toString());
                        assertEquals(expected.effectiveGravityFaces(), actual.effectiveGravityFaces(), point.toString());
                        assertEquals(expected.effectiveMagneticFaces(), actual.effectiveMagneticFaces(), point.toString());
                    }
                }
            }
        }
    }

    @Test
    void wallFacesWithSameNormalAndLocalBitKeepSectionHeightIsolation() {
        FloorFieldProfile lowerProfile = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorFieldProfile upperProfile = new FloorFieldProfile(32.0, 6.0, 20.0);
        for (Face face : List.of(Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST)) {
            Source lower;
            Source upper;
            Vec3 lowerPoint;
            Vec3 upperPoint;
            if (face == Face.NORTH || face == Face.SOUTH) {
                lower = new Source(-2, -1, -8, face, Kind.GRAVITY, lowerProfile);
                upper = new Source(-2, 15, -8, face, Kind.GRAVITY, upperProfile);
                double normalZ = face == Face.NORTH ? -10.0 : -5.0;
                lowerPoint = new Vec3(-1.5, -0.5, normalZ);
                upperPoint = new Vec3(-1.5, 15.5, normalZ);
            } else {
                lower = new Source(-8, -1, -2, face, Kind.GRAVITY, lowerProfile);
                upper = new Source(-8, 15, -2, face, Kind.GRAVITY, upperProfile);
                double normalX = face == Face.WEST ? -10.0 : -5.0;
                lowerPoint = new Vec3(normalX, -0.5, -1.5);
                upperPoint = new Vec3(normalX, 15.5, -1.5);
            }

            FloorFieldIndex index = new FloorFieldIndex();
            index.replaceChunk(new ChunkKey(-1, -1), new Object(), List.of(lower, upper));
            Vec3 lowerExpected = towardFace(face, lowerProfile.strengthAt(2.0));
            Vec3 upperExpected = towardFace(face, upperProfile.strengthAt(2.0));

            var lowerSample = index.sampleForStanding(lowerPoint, 0.0);
            assertEquals(lowerExpected, lowerSample.forces().gravityAccel(), face + " lower section");
            assertEquals(Map.of(face, lowerProfile.strengthAt(2.0)), lowerSample.gravityStrengths(),
                    face + " lower section score");
            var upperSample = index.sampleForStanding(upperPoint, 0.0);
            assertEquals(upperExpected, upperSample.forces().gravityAccel(), face + " upper section");
            assertEquals(Map.of(face, upperProfile.strengthAt(2.0)), upperSample.gravityStrengths(),
                    face + " upper section score");
        }
    }

    @Test
    void publicSourceReplacementSupportsNegativeAndIntegerExtremeYCoordinates() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 8.0);
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldIndex index = new FloorFieldIndex();
        Source minDown = new Source(0, Integer.MIN_VALUE, 0, Face.DOWN, Kind.GRAVITY, profile);
        Source maxUp = new Source(0, Integer.MAX_VALUE, 0, Face.UP, Kind.GRAVITY, profile);
        Source negativeSection = new Source(0, -17, 0, Face.UP, Kind.GRAVITY, profile);
        index.replaceChunk(key, owner, List.of(minDown, maxUp, negativeSection));

        Vec3 minInside = new Vec3(0.5, (double) Integer.MIN_VALUE - 2.0, 0.5);
        Vec3 maxInside = new Vec3(0.5, (double) Integer.MAX_VALUE + 1.0 + 2.0, 0.5);
        Vec3 minRangeEdge = new Vec3(0.5, (double) Integer.MIN_VALUE - 8.0, 0.5);
        Vec3 maxRangeEdge = new Vec3(0.5, (double) Integer.MAX_VALUE + 1.0 + 8.0, 0.5);
        assertEquals(new Vec3(0.0, 8.0, 0.0), index.sample(minInside, 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, -8.0, 0.0), index.sample(maxInside, 0.0).gravityAccel());
        assertEquals(Vec3.ZERO, index.sample(minRangeEdge, 0.0).gravityAccel());
        assertEquals(Vec3.ZERO, index.sample(maxRangeEdge, 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, -14.0, 0.5), 0.0).gravityAccel());

        index.replaceChunk(key, owner, List.of(new Source(0, -1, 0, Face.UP, Kind.GRAVITY, profile)));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, -14.0, 0.5), 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 2.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void publicSourcesAtHorizontalIntegerExtremesMatchScalarSampling() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 8.0);
        List<Source> sources = List.of(
                new Source(Integer.MIN_VALUE, 0, 0, Face.WEST, Kind.GRAVITY, profile),
                new Source(Integer.MAX_VALUE, 0, 0, Face.EAST, Kind.GRAVITY, profile),
                new Source(0, 0, Integer.MIN_VALUE, Face.NORTH, Kind.GRAVITY, profile),
                new Source(0, 0, Integer.MAX_VALUE, Face.SOUTH, Kind.GRAVITY, profile));
        FloorFieldIndex index = new FloorFieldIndex();
        for (Source source : sources) {
            ChunkKey key = new ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
            index.replaceChunk(key, new Object(), List.of(source));
        }

        double[] distances = {-0.25, 0.0, 2.0, 8.0, Math.nextDown(8.0), Math.nextUp(8.0)};
        for (Source source : sources) {
            for (double distance : distances) {
                Vec3 point = pointAtSourceNormal(source, distance);
                String label = source.face() + " at horizontal integer extreme, outward distance " + distance;
                Sample expected = scalarSample(sources, point, 0.0);
                assertEquals(expected, index.sample(point, 0.0), label);
                assertEquals(expected, index.sampleForStanding(point, 0.0).forces(), label + " standing");
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
    void preparedFaceDeltaCannotRestoreAnObsoleteGlobalMaximumRange() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey firstKey = new ChunkKey(0, 0);
        Object firstOwner = new Object();
        FloorFieldProfile shortRange = new FloorFieldProfile(8.0, 2.0, 8.0);
        index.replaceChunk(firstKey, firstOwner,
                List.of(new Source(0, 0, 0, Face.UP, Kind.GRAVITY, shortRange)));

        FloorChunkCoverage replacement = FloorChunkCoverage.compile(firstKey,
                List.of(new Source(0, 0, 0, Face.DOWN, Kind.GRAVITY, shortRange)));
        FloorFieldIndex.PreparedPublication preparedFaceDelta =
                index.prepareReplacement(firstKey, firstOwner, replacement);

        FloorFieldProfile widerRange = new FloorFieldProfile(32.0, 8.0, 16.0);
        index.replaceChunk(new ChunkKey(1, 0), new Object(),
                List.of(new Source(16, 0, 0, Face.UP, Kind.GRAVITY, widerRange)));

        boolean staleRejected = false;
        try {
            index.commitPrepared(preparedFaceDelta);
        } catch (IllegalStateException failure) {
            if (!failure.getMessage().contains("stale")) {
                throw failure;
            }
            staleRejected = true;
        }

        Vec3 remotePoint = new Vec3(16.5, 13.5, 0.5);
        Vec3 expectedRemoteField = new FloorFieldEmitter(16, 0, 0, Face.UP, widerRange)
                .accelerationAt(remotePoint);
        assertVector(expectedRemoteField, index.sample(remotePoint, 0.0).gravityAccel());
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());
        assertTrue(staleRejected, "the earlier publication must not overwrite the newer global range view");
    }

    @Test
    void localProfileDeltaRetainsUntouchedGroupsAndSections() {
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldProfile changedProfile = new FloorFieldProfile(8.0, 2.0, 6.0);
        FloorFieldProfile newProfile = new FloorFieldProfile(12.0, 3.0, 10.0);
        FloorFieldProfile sharedProfile = new FloorFieldProfile(16.0, 4.0, 8.0);
        FloorFieldProfile otherBucketProfile = new FloorFieldProfile(4.0, 1.0, 5.0);
        FloorChunkCoverage base = FloorChunkCoverage.compile(key, List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, changedProfile),
                new Source(1, 0, 0, Face.UP, Kind.GRAVITY, sharedProfile),
                new Source(2, 1, 0, Face.UP, Kind.GRAVITY, otherBucketProfile),
                new Source(3, 16, 0, Face.UP, Kind.GRAVITY, sharedProfile)));
        var sharedGroup = base.section(0).groups(Face.UP, 0).stream()
                .filter(group -> group.profile().equals(sharedProfile)).findFirst().orElseThrow();
        var otherBucketGroup = base.section(0).groups(Face.UP, 1).getFirst();
        FloorChunkCoverage.SectionCoverage untouchedSection = base.section(1);

        FloorChunkCoverage.Editor editor = FloorChunkCoverage.edit(base);
        assertTrue(editor.replaceCell(0, 0, 0,
                FloorChunkCoverage.singleDescriptor(Kind.GRAVITY, Face.UP), newProfile));
        FloorChunkCoverage next = editor.finish();
        List<FloorChunkCoverage.BucketKey> changedBuckets = editor.changedBuckets(next);
        assertEquals(List.of(new FloorChunkCoverage.BucketKey(0, Face.UP, 0)), changedBuckets);
        assertSame(sharedGroup, next.section(0).groups(Face.UP, 0).stream()
                .filter(group -> group.profile().equals(sharedProfile)).findFirst().orElseThrow());
        assertSame(otherBucketGroup, next.section(0).groups(Face.UP, 1).getFirst());
        assertSame(untouchedSection, next.section(1));

        FloorChunkSources.CoverageDelta delta = new FloorChunkSources.CoverageDelta(base, next,
                changedBuckets, Map.of(6.0, -1L, 10.0, 1L));
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunkCoverage(key, owner, base);
        FloorFieldIndex.PreparedPublication prepared = index.prepareDelta(key, owner, delta);
        index.commitPrepared(prepared);

        assertSame(next, index.coverageFor(key, owner));
        assertEquals(new FloorFieldEmitter(0, 0, 0, Face.UP, newProfile)
                        .accelerationAt(new Vec3(0.5, 1.5, 0.5)),
                index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
        assertEquals(new FloorFieldEmitter(1, 0, 0, Face.UP, sharedProfile)
                        .accelerationAt(new Vec3(1.5, 1.5, 0.5)),
                index.sample(new Vec3(1.5, 1.5, 0.5), 0.0).gravityAccel());
    }

    @Test
    void failedPrelinearizationRouteStageRollsBackAndCanRetryTheSamePublication() {
        AtomicBoolean failNext = new AtomicBoolean();
        IllegalStateException injectedFailure = new IllegalStateException("route stage rejected");
        FloorFieldIndex index = new FloorFieldIndex(() -> {
            if (failNext.compareAndSet(true, false)) {
                throw injectedFailure;
            }
        });
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorChunkCoverage up = FloorChunkCoverage.compile(key,
                List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        index.replaceChunkCoverage(key, owner, up);
        FloorChunkCoverage down = FloorChunkCoverage.compile(key,
                List.of(source(0, 0, 0, Face.DOWN, Kind.GRAVITY, 8.0)));
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, down);

        failNext.set(true);
        assertSame(injectedFailure, assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(prepared)));
        assertTrue(index.isCurrent(prepared), "a rolled-back publication remains retryable");
        assertSame(up, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.0, 0.5), 0.0).gravityAccel());

        index.commitPrepared(prepared);
        assertSame(down, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, 8.0, 0.0),
                index.sample(new Vec3(0.5, -1.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void failureAfterFirstRoutePatchRestoresTheOldChunkAndCanRetry() {
        AtomicBoolean failAfterFirstRoute = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("second route patch rejected");
        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.ROUTE_PATCH_STAGED
                    && ordinal == 1 && failAfterFirstRoute.compareAndSet(true, false)) {
                throw failure;
            }
        });
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorChunkCoverage oldCoverage = FloorChunkCoverage.compile(key, List.of(
                source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0),
                source(0, 0, 0, Face.DOWN, Kind.GRAVITY, 8.0)));
        index.replaceChunkCoverage(key, owner, oldCoverage);
        FloorChunkCoverage nextCoverage = FloorChunkCoverage.compile(key, List.of(
                source(0, 0, 0, Face.NORTH, Kind.GRAVITY, 8.0),
                source(0, 0, 0, Face.SOUTH, Kind.GRAVITY, 8.0)));
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, nextCoverage);

        failAfterFirstRoute.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> index.commitPrepared(prepared)));

        assertTrue(index.isCurrent(prepared), "partial route staging is fully restored and retryable");
        assertSame(oldCoverage, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
        assertVector(new Vec3(0.0, 8.0, 0.0),
                index.sample(new Vec3(0.5, -0.5, 0.5), 0.0).gravityAccel());

        index.commitPrepared(prepared);
        assertSame(nextCoverage, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, 0.0, 8.0),
                index.sample(new Vec3(0.5, 0.5, -0.5), 0.0).gravityAccel());
        assertVector(new Vec3(0.0, 0.0, -8.0),
                index.sample(new Vec3(0.5, 0.5, 1.5), 0.0).gravityAccel());
    }

    @Test
    void failureAfterRangeIncreaseRestoresRouteRangeAndOldSample() {
        AtomicBoolean failAfterRange = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("range staging rejected");
        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.RANGE_PATCH_STAGED
                    && ordinal == 1 && failAfterRange.compareAndSet(true, false)) {
                throw failure;
            }
        });
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldProfile shortRange = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorFieldProfile wideRange = new FloorFieldProfile(8.0, 2.0, 16.0);
        FloorChunkCoverage oldCoverage = FloorChunkCoverage.compile(key,
                List.of(new Source(0, 0, 0, Face.UP, Kind.GRAVITY, shortRange)));
        index.replaceChunkCoverage(key, owner, oldCoverage);
        FloorChunkCoverage nextCoverage = FloorChunkCoverage.compile(key, List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, wideRange),
                new Source(0, 0, 0, Face.NORTH, Kind.GRAVITY, wideRange)));
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, nextCoverage);

        failAfterRange.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> index.commitPrepared(prepared)));

        assertTrue(index.isCurrent(prepared), "the original range counts are restored before retry");
        assertSame(oldCoverage, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 10.0, 0.5), 0.0).gravityAccel(),
                "a failed range addition cannot extend the visible field");

        index.commitPrepared(prepared);
        assertSame(nextCoverage, index.coverageFor(key, owner));
        assertTrue(index.sample(new Vec3(0.5, 10.0, 0.5), 0.0).gravityAccel().y() < 0.0);
        assertVector(new Vec3(0.0, 0.0, 8.0),
                index.sample(new Vec3(0.5, 0.5, -0.5), 0.0).gravityAccel());
    }

    @Test
    void failureAfterFirstChunkDirectoryPutRemovesTheAttemptAndCanRetry() {
        AtomicBoolean failAfterPut = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("chunk directory insertion rejected");
        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.CHUNK_DIRECTORY_INSERTED
                    && failAfterPut.compareAndSet(true, false)) {
                throw failure;
            }
        });
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(key,
                List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, coverage);

        failAfterPut.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> index.commitPrepared(prepared)));

        assertTrue(index.isCurrent(prepared), "the attempted directory insertion was removed");
        assertNull(index.coverageFor(key, owner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());

        index.commitPrepared(prepared);
        assertSame(coverage, index.coverageFor(key, owner));
        assertVector(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
    }

    @Test
    void unprovableRangeRollbackInvalidatesTheIndexAndPreservesBothFailures() {
        AtomicBoolean reenterAndFail = new AtomicBoolean();
        AtomicReference<FloorFieldIndex> indexReference = new AtomicReference<>();
        Object reentrantOwner = new Object();
        IllegalStateException primaryFailure = new IllegalStateException("outer publication interrupted");
        FloorFieldIndex index = new FloorFieldIndex(() -> {
            if (reenterAndFail.compareAndSet(true, false)) {
                indexReference.get().replaceChunk(new ChunkKey(1, 0), reentrantOwner,
                        List.of(new Source(16, 0, 0, Face.UP, Kind.GRAVITY,
                                new FloorFieldProfile(32.0, 8.0, 16.0))));
                throw primaryFailure;
            }
        });
        indexReference.set(index);
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldProfile oldProfile = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorFieldProfile newProfile = new FloorFieldProfile(24.0, 6.0, 16.0);
        index.replaceChunk(key, owner,
                List.of(new Source(0, 0, 0, Face.UP, Kind.GRAVITY, oldProfile)));
        FloorChunkCoverage next = FloorChunkCoverage.compile(key,
                List.of(new Source(0, 0, 0, Face.UP, Kind.GRAVITY, newProfile)));
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, next);

        reenterAndFail.set(true);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(prepared));

        assertSame(primaryFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length,
                "unprovable rollback remains attached to the original publication failure");
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("floor range count"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> index.sample(new Vec3(0.5, 1.0, 0.5), 0.0));
        assertSame(primaryFailure, invalid.getCause(), "the unhealthy-index fence retains the primary cause");
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
    void clearFencesPreparedViewsAndCloseIsTerminal() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorChunkCoverage first = FloorChunkCoverage.compile(key,
                List.of(source(0, 0, 0, Face.UP, Kind.GRAVITY, 8.0)));
        FloorFieldIndex.PreparedPublication initial = index.prepareReplacement(key, owner, first);
        FloorFieldIndex.ChunkView oldView = initial.targetView();
        index.commitPrepared(initial);

        FloorChunkCoverage replacement = FloorChunkCoverage.compile(key,
                List.of(source(0, 0, 0, Face.DOWN, Kind.GRAVITY, 8.0)));
        FloorFieldIndex.PreparedPublication stale = index.prepareReplacement(key, owner, replacement);
        index.clear();
        assertFalse(index.isCurrent(stale), "clear invalidates a prepared publication by epoch and view identity");
        assertThrows(IllegalStateException.class, () -> index.commitPrepared(stale));

        FloorFieldIndex.PreparedPublication reload = index.prepareReplacement(key, owner, first);
        assertTrue(reload.targetView() != oldView, "same owner after clear receives a fresh shared view");
        index.commitPrepared(reload);

        FloorFieldIndex.ChunkView reloadedView = reload.targetView();
        assertTrue(index.removeChunk(key, owner));
        FloorFieldIndex.PreparedPublication sameOwnerReload = index.prepareReplacement(key, owner, first);
        assertTrue(sameOwnerReload.targetView() != reloadedView,
                "a same-owner reload after removal receives a new generation view");
        index.commitPrepared(sameOwnerReload);

        FloorFieldIndex.PreparedPublication beforeClose = index.prepareReplacement(key, owner, replacement);
        index.close();
        assertFalse(index.isCurrent(beforeClose));
        assertThrows(IllegalStateException.class, () -> index.commitPrepared(beforeClose));
        assertThrows(IllegalStateException.class, index::clear,
                "clear must not reopen a closed index");
        assertThrows(IllegalStateException.class,
                () -> index.prepareReplacement(key, owner, first));
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

    private static Source source(int x, int y, int z, Face face, Kind kind,
                                 double strength, double plateau, double range) {
        return new Source(x, y, z, face, kind,
                new FloorFieldProfile(strength, plateau, range));
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

    private static Vec3 pointAtSourceNormal(Source source, double outwardDistance) {
        double minX = (double) source.x();
        double minY = (double) source.y();
        double minZ = (double) source.z();
        return switch (source.face()) {
            case DOWN -> new Vec3(minX + 0.5, minY - outwardDistance, minZ + 0.5);
            case UP -> new Vec3(minX + 0.5, minY + 1.0 + outwardDistance, minZ + 0.5);
            case NORTH -> new Vec3(minX + 0.5, minY + 0.5, minZ - outwardDistance);
            case SOUTH -> new Vec3(minX + 0.5, minY + 0.5, minZ + 1.0 + outwardDistance);
            case WEST -> new Vec3(minX - outwardDistance, minY + 0.5, minZ + 0.5);
            case EAST -> new Vec3(minX + 1.0 + outwardDistance, minY + 0.5, minZ + 0.5);
        };
    }

    private static Face opposite(Face face) {
        return switch (face) {
            case DOWN -> Face.UP;
            case UP -> Face.DOWN;
            case NORTH -> Face.SOUTH;
            case SOUTH -> Face.NORTH;
            case WEST -> Face.EAST;
            case EAST -> Face.WEST;
        };
    }

    private static Vec3 normal(Face face) {
        return switch (face) {
            case DOWN -> new Vec3(0.0, -1.0, 0.0);
            case UP -> new Vec3(0.0, 1.0, 0.0);
            case NORTH -> new Vec3(0.0, 0.0, -1.0);
            case SOUTH -> new Vec3(0.0, 0.0, 1.0);
            case WEST -> new Vec3(-1.0, 0.0, 0.0);
            case EAST -> new Vec3(1.0, 0.0, 0.0);
        };
    }

    private static Vec3 towardFace(Face face, double strength) {
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

    private static Sample scalarSample(List<Source> sources, Vec3 point, double magneticResponse) {
        Vec3 gravity = Vec3.ZERO;
        Vec3 magnetic = Vec3.ZERO;
        java.util.EnumSet<Face> gravityFaces = java.util.EnumSet.noneOf(Face.class);
        java.util.EnumSet<Face> magneticFaces = java.util.EnumSet.noneOf(Face.class);
        for (Face face : Face.values()) {
            List<Source> ordered = sources.stream().filter(source -> source.face() == face)
                    .sorted(Comparator.comparingInt(source -> switch (face) {
                        case DOWN, UP -> source.y();
                        case NORTH, SOUTH -> source.z();
                        case WEST, EAST -> source.x();
                    })).toList();
            for (Source source : ordered) {
                Vec3 contribution = new FloorFieldEmitter(source.x(), source.y(), source.z(),
                        source.face(), source.profile()).accelerationAt(point);
                if (contribution.x() == 0.0 && contribution.y() == 0.0 && contribution.z() == 0.0) {
                    continue;
                }
                if (source.kind() == Kind.GRAVITY) {
                    gravity = new Vec3(gravity.x() + contribution.x(), gravity.y() + contribution.y(),
                            gravity.z() + contribution.z());
                    gravityFaces.add(face);
                } else if (magneticResponse > 0.0) {
                    magnetic = new Vec3(magnetic.x() + contribution.x() * magneticResponse,
                            magnetic.y() + contribution.y() * magneticResponse,
                            magnetic.z() + contribution.z() * magneticResponse);
                    magneticFaces.add(face);
                }
            }
        }
        return new Sample(gravity, magnetic, gravityFaces, magneticFaces);
    }

    private record EqualOwner(String id) {
    }
}
