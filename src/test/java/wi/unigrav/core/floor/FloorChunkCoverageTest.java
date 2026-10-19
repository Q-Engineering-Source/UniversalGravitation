package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import wi.unigrav.core.floor.FloorChunkCoverage.PlaneGroup;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorChunkCoverageTest {

    @Test
    void densePlaneStoresOneMaskWithoutChangingEmitterCount() {
        FloorFieldProfile profile = new FloorFieldProfile(32.0, 8.0, 16.0);
        List<Source> sources = new ArrayList<>(256);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                sources.add(new Source(x, 32, z, Face.UP, Kind.GRAVITY, profile));
            }
        }

        FloorChunkCoverage coverage = FloorChunkCoverage.compile(new ChunkKey(0, 0), sources);
        FloorChunkCoverage.Stats stats = coverage.stats();
        assertEquals(256L, stats.physicalBlockCount());
        assertEquals(256L, stats.emitterCount());
        assertEquals(1L, stats.groupCount());
        assertEquals(1L, stats.nonemptySectionCount());
        assertEquals(32L, stats.maskPayloadBytes());
        assertEquals(0L, stats.tangentNormalLookupFaceCount());
        assertEquals(0L, stats.tangentNormalLookupPayloadBytes());
        assertEquals(null, coverage.section(1));
        List<PlaneGroup> groups = coverage.section(2).groups(Face.UP, 0);
        assertEquals(1, groups.size());
        assertEquals(256, groups.getFirst().cells().cardinality());
        assertEquals(1, coverage.section(2).normalPresence(Face.UP));
        assertThrows(UnsupportedOperationException.class, groups::clear);
    }

    @Test
    void doubleSidedCellsCountOneBlockAndTwoEmitters() {
        FloorFieldProfile profile = new FloorFieldProfile(32.0, 8.0, 16.0);
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(new ChunkKey(0, 0), List.of(
                new Source(3, 4, 5, Face.DOWN, Kind.GRAVITY, profile),
                new Source(3, 4, 5, Face.UP, Kind.GRAVITY, profile)));

        assertEquals(new FloorChunkCoverage.Stats(1, 2, 2, 1, 32, 0, 0), coverage.stats());
        assertTrue(coverage.hasEmitterAt(3, 4, 5, Face.DOWN));
        assertTrue(coverage.hasEmitterAt(3, 4, 5, Face.UP));
        assertFalse(coverage.hasEmitterAt(3, 4, 5, Face.NORTH));
        assertFalse(coverage.hasEmitterAt(19, 4, 5, Face.UP));
    }

    @Test
    void negativePageCoordinatesAndSectionBordersKeepExactOwnership() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(new ChunkKey(-1, -1), List.of(
                new Source(-1, -17, -16, Face.NORTH, Kind.GRAVITY, profile),
                new Source(-1, -16, -16, Face.NORTH, Kind.GRAVITY, profile)));

        assertEquals(2, coverage.sectionCount());
        assertTrue(coverage.hasEmitterAt(-1, -17, -16, Face.NORTH));
        assertTrue(coverage.hasEmitterAt(-1, -16, -16, Face.NORTH));
        assertFalse(coverage.hasEmitterAt(-1, -15, -16, Face.NORTH));
        assertEquals(1, coverage.section(-2).groups(Face.NORTH, 0).getFirst().cells().cardinality());
        assertEquals(1, coverage.section(-1).groups(Face.NORTH, 0).getFirst().cells().cardinality());
    }

    @Test
    void duplicateSameFaceCellRejectsEvenWhenGroupDescriptorDiffers() {
        FloorFieldProfile first = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorFieldProfile second = new FloorFieldProfile(16.0, 4.0, 12.0);
        assertThrows(IllegalArgumentException.class, () -> FloorChunkCoverage.compile(new ChunkKey(0, 0), List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, first),
                new Source(0, 0, 0, Face.UP, Kind.MAGNETIC, second))));
    }

    @Test
    void signedZeroProfilesRemainDistinctGroups() {
        FloorFieldProfile negativeZero = new FloorFieldProfile(-0.0, 0.0, 8.0);
        FloorFieldProfile positiveZero = new FloorFieldProfile(0.0, 0.0, 8.0);
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(new ChunkKey(0, 0), List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, negativeZero),
                new Source(1, 0, 0, Face.UP, Kind.GRAVITY, positiveZero)));

        List<PlaneGroup> groups = coverage.section(0).groups(Face.UP, 0);
        assertEquals(2, groups.size());
        assertEquals(2L, coverage.stats().groupCount());
        assertTrue(groups.get(0).profile().strength() == 0.0);
        assertTrue(groups.get(1).profile().strength() == 0.0);
        assertEquals(2, groups.stream().mapToLong(group -> group.cells().cardinality()).sum());
    }

    @Test
    void tangentLookupTracksMultiNormalMasksThroughRemoveAndReaddTransitions() {
        FloorFieldProfile profile = new FloorFieldProfile(8.0, 2.0, 8.0);
        FloorChunkCoverage base = FloorChunkCoverage.compile(new ChunkKey(0, 0), List.of(
                new Source(0, 0, 0, Face.UP, Kind.GRAVITY, profile),
                new Source(1, 3, 0, Face.UP, Kind.GRAVITY, profile),
                new Source(15, 15, 0, Face.UP, Kind.GRAVITY, profile)));

        FloorChunkCoverage.SectionCoverage section = base.section(0);
        assertEquals(1, section.normalPresenceAtBit(Face.UP, 0));
        assertEquals(1 << 3, section.normalPresenceAtBit(Face.UP, 1));
        assertEquals(1 << 15, section.normalPresenceAtBit(Face.UP, 15));
        assertEquals(0, section.normalPresenceAtBit(Face.UP, 2));
        assertEquals(1, section.tangentNormalLookupFaceCount());
        assertEquals(512, section.tangentNormalLookupPayloadBytes());
        assertEquals(profile, section.groupAt(Face.UP, 15, 15).profile());

        FloorChunkCoverage.Editor removeMiddle = FloorChunkCoverage.edit(base);
        assertTrue(removeMiddle.replaceCell(1, 3, 0, 0, profile));
        FloorChunkCoverage afterMiddleRemoval = removeMiddle.finish();
        FloorChunkCoverage.SectionCoverage afterMiddle = afterMiddleRemoval.section(0);
        assertEquals(1, afterMiddle.normalPresenceAtBit(Face.UP, 0));
        assertEquals(0, afterMiddle.normalPresenceAtBit(Face.UP, 1));
        assertEquals(1 << 15, afterMiddle.normalPresenceAtBit(Face.UP, 15));

        FloorChunkCoverage.Editor removeHighest = FloorChunkCoverage.edit(afterMiddleRemoval);
        assertTrue(removeHighest.replaceCell(15, 15, 0, 0, profile));
        FloorChunkCoverage singleNormal = removeHighest.finish();
        assertEquals(1, singleNormal.section(0).normalPresenceAtBit(Face.UP, 0));
        assertEquals(0L, singleNormal.stats().tangentNormalLookupFaceCount());
        assertEquals(1, singleNormal.section(0).normalPresence(Face.UP));

        FloorChunkCoverage.Editor addHighest = FloorChunkCoverage.edit(singleNormal);
        assertTrue(addHighest.replaceCell(2, 14, 0,
                FloorChunkCoverage.singleDescriptor(Kind.GRAVITY, Face.UP), profile));
        FloorChunkCoverage multiNormalAgain = addHighest.finish();
        FloorChunkCoverage.SectionCoverage restored = multiNormalAgain.section(0);
        assertEquals(1 << 15, base.section(0).normalPresenceAtBit(Face.UP, 15));
        assertEquals(1, restored.normalPresenceAtBit(Face.UP, 0));
        assertEquals(1 << 14, restored.normalPresenceAtBit(Face.UP, 2));
        assertEquals(0, restored.normalPresenceAtBit(Face.UP, 15));
        assertEquals(0, restored.normalPresenceAtBit(Face.UP, 1));
        assertEquals(1L, restored.tangentNormalLookupFaceCount());
        assertEquals(512L, restored.tangentNormalLookupPayloadBytes());
    }
}
