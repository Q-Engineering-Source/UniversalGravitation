package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.RetainedCounts;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorRouteRankSampleTest {

    private static final FloorFieldProfile PROFILE = new FloorFieldProfile(1.0, 0.0, 0.25);

    @Test
    void compactRankTracksWidthsOneThroughSixtyFourAndPromotesAtSixtyFive() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        int firstSlice = -32;

        for (int width : new int[] {1, 2, 63, 64, 65}) {
            int lastSlice = firstSlice + width - 1;
            List<Source> sources = new ArrayList<>(width);
            for (int slice = firstSlice; slice <= lastSlice; slice++) {
                sources.add(upSource(slice));
            }
            publishPrepared(index, key, owner, sources);

            RetainedCounts counts = index.retainedCounts();
            assertEquals(1L, counts.routeBuckets(), "one tangent page at width " + width);
            assertEquals(8L, counts.routePagePresencePrimitivePayloadBytes());
            assertEquals(1L, counts.compactRoutePages() + counts.wideRoutePages());
            assertEquals(width <= Long.SIZE ? 1L : 0L, counts.compactRoutePages());
            assertEquals(width > Long.SIZE ? 1L : 0L, counts.wideRoutePages());
            assertEquals(width * Integer.BYTES, counts.routePageNormalPrimitivePayloadBytes());

            assertSourceSample(index, upSource(firstSlice), upPoint(firstSlice), "first rank at width " + width);
            assertSourceSample(index, upSource(lastSlice), upPoint(lastSlice), "last rank at width " + width);
        }

        FloorFieldIndex.PreparedPublication removal = index.prepareRemoval(key, owner);
        assertTrue(removal != null, "a published chunk must prepare for removal");
        assertNull(index.commitPrepared(removal));
        RetainedCounts removed = index.retainedCounts();
        assertEquals(0L, removed.routeBuckets());
        assertEquals(0L, removed.routePagePresencePrimitivePayloadBytes());
        assertEquals(0L, removed.compactRoutePages());
        assertEquals(0L, removed.wideRoutePages());
    }

    @Test
    void compactBitSixtyThreeAndWideFallbackPreserveNegativeHoles() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();

        publishPrepared(index, key, owner, List.of(upSource(-32), upSource(31)));
        RetainedCounts compact = index.retainedCounts();
        assertEquals(1L, compact.compactRoutePages());
        assertEquals(0L, compact.wideRoutePages());
        assertSourceSample(index, upSource(-32), upPoint(-32), "negative first bit");
        assertEmptySample(index, upPoint(0), "compact interior hole");
        assertSourceSample(index, upSource(31), upPoint(31), "compact bit 63");

        publishPrepared(index, key, owner, List.of(upSource(-32), upSource(32)));
        RetainedCounts wide = index.retainedCounts();
        assertEquals(0L, wide.compactRoutePages());
        assertEquals(1L, wide.wideRoutePages());
        assertEmptySample(index, upPoint(0), "wide interior hole");
        assertSourceSample(index, upSource(32), upPoint(32), "wide binary-search endpoint");

        publishPrepared(index, key, owner, List.of(upSource(-32), upSource(31)));
        assertEquals(1L, index.retainedCounts().compactRoutePages(), "replacement demotes back to compact");
        assertEmptySample(index, upPoint(32), "removed endpoint stays absent");
    }

    @Test
    void routePagesRemainIsolatedAndCountTheSharedPrimitivePerPage() {
        FloorFieldIndex index = new FloorFieldIndex();
        Source left = upSource(0, 0, PROFILE);
        Source right = upSource(0, 16, new FloorFieldProfile(2.0, 0.0, 0.25));
        installByChunk(index, List.of(left, right));

        assertSourceSample(index, left, new Vec3(0.5, 2.125, 0.5), "first tangent page");
        assertSourceSample(index, right, new Vec3(16.5, 2.125, 0.5), "second tangent page");

        RetainedCounts counts = index.retainedCounts();
        assertEquals(2L, counts.routeBuckets());
        assertEquals(2L, counts.compactRoutePages());
        assertEquals(0L, counts.wideRoutePages());
        assertEquals(counts.routeBuckets(), counts.compactRoutePages() + counts.wideRoutePages());
        assertEquals(8L * counts.routeBuckets(), counts.routePagePresencePrimitivePayloadBytes());
    }

    @Test
    void signedFacePagesUseCompactAndWideRanksAtNegativeAndExtremeCoordinates() {
        for (Face face : Face.values()) {
            FloorFieldIndex index = new FloorFieldIndex();
            int[] slices = {-2, 0, 3};
            List<Source> sources = new ArrayList<>(slices.length);
            for (int slice : slices) {
                sources.add(sourceAtSlice(face, slice, PROFILE));
            }
            installByChunk(index, sources);

            for (int slice : slices) {
                Source source = sourceAtSlice(face, slice, PROFILE);
                assertSourceSample(index, source, pointAtSlice(face, slice), face + " slice " + slice);
            }
        }

        FloorFieldIndex extremes = new FloorFieldIndex();
        Source minimum = new Source(0, Integer.MIN_VALUE + 1, 0, Face.UP, Kind.GRAVITY, PROFILE);
        Source maximum = new Source(0, Integer.MAX_VALUE - 14, 0, Face.UP, Kind.GRAVITY, PROFILE);
        extremes.replaceChunk(new ChunkKey(0, 0), new Object(), List.of(minimum, maximum));
        assertSourceSample(extremes, minimum,
                new Vec3(0.5, (double) minimum.y() + 1.125, 0.5), "minimum normal coordinate");
        assertSourceSample(extremes, maximum,
                new Vec3(0.5, (double) maximum.y() + 1.125, 0.5), "maximum normal coordinate");
        RetainedCounts counts = extremes.retainedCounts();
        assertEquals(0L, counts.compactRoutePages());
        assertEquals(1L, counts.wideRoutePages());
    }

    private static Source upSource(int slice) {
        return upSource(slice, 0, PROFILE);
    }

    private static Source upSource(int slice, int x, FloorFieldProfile profile) {
        int y = Math.addExact(Math.multiplyExact(slice, 16), 1);
        return new Source(x, y, 0, Face.UP, Kind.GRAVITY, profile);
    }

    private static Source sourceAtSlice(Face face, int slice, FloorFieldProfile profile) {
        int normal = Math.addExact(Math.multiplyExact(slice, 16), 1);
        return switch (face) {
            case DOWN, UP -> new Source(0, normal, 0, face, Kind.GRAVITY, profile);
            case NORTH, SOUTH -> new Source(0, 0, normal, face, Kind.GRAVITY, profile);
            case WEST, EAST -> new Source(normal, 0, 0, face, Kind.GRAVITY, profile);
        };
    }

    private static Vec3 pointAtSlice(Face face, int slice) {
        double base = (double) slice * 16.0;
        return switch (face) {
            case UP -> new Vec3(0.5, base + 2.125, 0.5);
            case DOWN -> new Vec3(0.5, base + 0.875, 0.5);
            case SOUTH -> new Vec3(0.5, 0.5, base + 2.125);
            case NORTH -> new Vec3(0.5, 0.5, base + 0.875);
            case EAST -> new Vec3(base + 2.125, 0.5, 0.5);
            case WEST -> new Vec3(base + 0.875, 0.5, 0.5);
        };
    }

    private static Vec3 upPoint(int slice) {
        return new Vec3(0.5, (double) slice * 16.0 + 2.125, 0.5);
    }

    private static void assertSourceSample(FloorFieldIndex index, Source source, Vec3 point, String label) {
        Vec3 expected = new FloorFieldEmitter(source.x(), source.y(), source.z(),
                source.face(), source.profile()).accelerationAt(point);
        Sample sample = index.sample(point, 0.0);
        assertVectorRawBits(expected, sample.gravityAccel(), label + " sample");
        assertEquals(Set.of(source.face()), sample.effectiveGravityFaces(), label + " sample effective face");

        var standing = index.sampleForStanding(point, 0.0);
        assertEquals(sample, standing.forces(), label + " standing force sample");
        assertVectorRawBits(expected, standing.forces().gravityAccel(), label + " standing force vector");
        double strength = Math.max(Math.abs(expected.x()), Math.max(Math.abs(expected.y()), Math.abs(expected.z())));
        assertEquals(source.profile().strengthAt(0.125), strength, label + " scalar emitter strength");
        assertEquals(Map.of(source.face(), strength), standing.gravityStrengths(), label + " standing effective strength");
        assertTrue(standing.magneticStrengths().isEmpty(), label + " no magnetic sources");
    }

    private static void assertEmptySample(FloorFieldIndex index, Vec3 point, String label) {
        Sample sample = index.sample(point, 0.0);
        assertVectorRawBits(Vec3.ZERO, sample.gravityAccel(), label + " sample");
        assertTrue(sample.effectiveGravityFaces().isEmpty(), label + " no effective gravity faces");
        var standing = index.sampleForStanding(point, 0.0);
        assertEquals(sample, standing.forces(), label + " standing force sample");
        assertVectorRawBits(Vec3.ZERO, standing.forces().gravityAccel(), label + " standing force vector");
        assertTrue(standing.gravityStrengths().isEmpty(), label + " no gravity sources");
        assertTrue(standing.magneticStrengths().isEmpty(), label + " no magnetic sources");
        assertTrue(sample.effectiveMagneticFaces().isEmpty(), label + " no effective magnetic faces");
    }

    private static void assertVectorRawBits(Vec3 expected, Vec3 actual, String label) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()), label + " x");
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()), label + " y");
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()), label + " z");
    }

    private static void installByChunk(FloorFieldIndex index, List<Source> sources) {
        Map<ChunkKey, List<Source>> byChunk = new LinkedHashMap<>();
        for (Source source : sources) {
            ChunkKey key = new ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(source);
        }
        byChunk.forEach((key, chunkSources) -> index.replaceChunk(key, new Object(), List.copyOf(chunkSources)));
    }

    private static void publishPrepared(FloorFieldIndex index, ChunkKey key, Object owner,
                                        List<Source> sources) {
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(key, sources);
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner, coverage);
        assertTrue(index.isCurrent(prepared), "newly prepared route publication must be current");
        index.commitPrepared(prepared);
    }
}
