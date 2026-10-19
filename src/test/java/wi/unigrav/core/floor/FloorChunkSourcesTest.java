package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorChunkSourcesTest {
    private static final FloorFieldProfile PROFILE = new FloorFieldProfile(32.0, 8.0, 16.0);

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void realStorageScanReadsBothKindsAllFacesAndActualWorldCoordinates() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[1] = new ExtendedBlockStorage(16, true);
        for (EnumFacing facing : EnumFacing.values()) {
            sections[1].set(facing.getIndex(), 2, 3,
                    FloorBlocks.GRAVITY_FLOOR.getDefaultState().withProperty(DirectionalFloorBlock.FACING, facing));
            sections[1].set(facing.getIndex(), 4, 3,
                    FloorBlocks.MAGNETIC_FLOOR.getDefaultState().withProperty(DirectionalFloorBlock.FACING, facing));
        }
        sections[1].set(12, 2, 3, Blocks.STONE.getDefaultState());
        List<Source> sources = FloorChunkSources.scanExisting(2, -1, () -> sections, PROFILE).snapshot();
        assertEquals(12, sources.size());
        for (EnumFacing facing : EnumFacing.values()) {
            Face face = Face.valueOf(facing.name());
            assertTrue(sources.contains(new Source(32 + facing.getIndex(), 18, -13, face, Kind.GRAVITY, PROFILE)));
            assertTrue(sources.contains(new Source(32 + facing.getIndex(), 20, -13, face, Kind.MAGNETIC, PROFILE)));
        }
        assertThrows(UnsupportedOperationException.class, sources::clear);
    }

    @Test
    void doubleSidedGravityStorageScanEmitsBothOppositeFacesForEveryOrientation() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[1] = new ExtendedBlockStorage(16, true);
        for (EnumFacing facing : EnumFacing.values()) {
            sections[1].set(facing.getIndex(), 2, 3,
                    FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                            .withProperty(DoubleSidedGravityFloorBlock.AXIS, facing.getAxis()));
        }

        List<Source> sources = FloorChunkSources.scanExisting(2, -1, () -> sections, PROFILE).snapshot();

        assertEquals(12, sources.size());
        for (EnumFacing facing : EnumFacing.values()) {
            int x = 32 + facing.getIndex();
            assertTrue(sources.contains(new Source(x, 18, -13, Face.valueOf(facing.name()), Kind.GRAVITY, PROFILE)));
            assertTrue(sources.contains(new Source(x, 18, -13,
                    Face.valueOf(facing.getOpposite().name()), Kind.GRAVITY, PROFILE)));
        }
    }

    @Test
    void rereadTracksRemovalRotationAndReplacedStorageArrayWithoutAWorldLookup() {
        AtomicReference<ExtendedBlockStorage[]> storage = new AtomicReference<>(new ExtendedBlockStorage[16]);
        FloorChunkSources scanner = FloorChunkSources.scanExisting(-1, 1, storage::get, PROFILE);
        assertTrue(scanner.snapshot().isEmpty());
        ExtendedBlockStorage[] replacement = new ExtendedBlockStorage[16];
        replacement[0] = new ExtendedBlockStorage(0, false);
        storage.set(replacement);
        replacement[0].set(15, 3, 2, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        scanner.reread(-1, 3, 18);
        List<Source> beforeRotation = scanner.snapshot();
        assertEquals(List.of(new Source(-1, 3, 18, Face.UP, Kind.GRAVITY, PROFILE)), beforeRotation);

        replacement[0].set(15, 3, 2, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.WEST));
        scanner.reread(-1, 3, 18);
        assertEquals(Face.WEST, scanner.snapshot().getFirst().face());
        assertEquals(Face.UP, beforeRotation.getFirst().face());
        replacement[0].set(15, 3, 2, Blocks.AIR.getDefaultState());
        scanner.reread(-1, 3, 18);
        assertTrue(scanner.snapshot().isEmpty());
    }

    @Test
    void rereadReplacesBothDoubleSidedEmittersTogetherAcrossRotationAndRemoval() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState());
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> sections, PROFILE);

        List<Source> original = scanner.snapshot();
        assertEquals(List.of(Face.DOWN, Face.UP), original.stream().map(Source::face).toList());

        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                .withProperty(DoubleSidedGravityFloorBlock.AXIS, EnumFacing.WEST.getAxis()));
        scanner.reread(3, 4, 5);
        assertEquals(List.of(Face.WEST, Face.EAST), scanner.snapshot().stream().map(Source::face).toList());
        assertEquals(List.of(Face.DOWN, Face.UP), original.stream().map(Source::face).toList());

        sections[0].set(3, 4, 5, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.SOUTH));
        scanner.reread(3, 4, 5);
        assertEquals(List.of(new Source(3, 4, 5, Face.SOUTH, Kind.GRAVITY, PROFILE)), scanner.snapshot());
        sections[0].set(3, 4, 5, Blocks.AIR.getDefaultState());
        scanner.reread(3, 4, 5);
        assertTrue(scanner.snapshot().isEmpty());
    }

    @Test
    void equivalentDoubleSidedFaceSetRereadKeepsTheCommittedCoverage() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                .withProperty(DoubleSidedGravityFloorBlock.AXIS, EnumFacing.UP.getAxis()));
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> sections, PROFILE);
        FloorChunkCoverage original = scanner.committedCoverage();

        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                .withProperty(DoubleSidedGravityFloorBlock.AXIS, EnumFacing.DOWN.getAxis()));
        scanner.reread(3, 4, 5);

        assertSame(original, scanner.committedCoverage(), "same final emitter faces are a true NoChange");
        assertEquals(List.of(Face.DOWN, Face.UP), scanner.snapshot().stream().map(Source::face).toList());
    }

    @Test
    void storageScanAndEditorStatsCountDistinctOppositeFaceMaskObjects() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                .withProperty(DoubleSidedGravityFloorBlock.AXIS, EnumFacing.UP.getAxis()));
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> sections, PROFILE);

        assertOppositeFaceMasksAreDistinct(scanner.committedCoverage(), Face.UP, Face.DOWN, 4);

        sections[0].set(3, 4, 5, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()
                .withProperty(DoubleSidedGravityFloorBlock.AXIS, EnumFacing.NORTH.getAxis()));
        scanner.reread(3, 4, 5);

        assertOppositeFaceMasksAreDistinct(scanner.committedCoverage(), Face.NORTH, Face.SOUTH, 5);
    }

    private static void assertOppositeFaceMasksAreDistinct(FloorChunkCoverage coverage,
                                                            Face first, Face second, int localNormal) {
        var firstMask = coverage.section(0).groups(first, localNormal).getFirst().cells();
        var secondMask = coverage.section(0).groups(second, localNormal).getFirst().cells();
        assertNotSame(firstMask, secondMask, "opposite source faces own distinct stored mask objects");
        assertEquals(64L, coverage.stats().maskPayloadBytes(),
                "payload accounting counts the four primitive words in each stored mask object");
    }

    @Test
    void attachedScannerRejectsStandaloneCacheMutation() {
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0,
                () -> new ExtendedBlockStorage[16], PROFILE);
        var key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldIndex index = new FloorFieldIndex();
        FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, owner,
                scanner.initialCoverage());
        scanner.attachPublishedCoverage(owner, prepared.targetView());
        index.commitPrepared(prepared);

        assertThrows(IllegalStateException.class, () -> scanner.reread(0, 0, 0));
    }

    @Test
    void attachedScannerReadsTheSameViewPublishedByAnIndexDelta() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> sections, PROFILE);
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();

        FloorFieldIndex.PreparedPublication initial = index.prepareReplacement(key, owner,
                scanner.initialCoverage());
        FloorFieldIndex.ChunkView view = initial.targetView();
        scanner.attachPublishedCoverage(owner, view);
        index.commitPrepared(initial);
        FloorChunkCoverage oldCoverage = scanner.committedCoverage();

        sections[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
        FloorWorldFields.DirtyBank dirty = new FloorWorldFields.DirtyBank();
        dirty.add(0);
        FloorChunkSources.Refresh refresh = scanner.prepareReread(owner, dirty);
        FloorChunkSources.CoverageDelta delta = (FloorChunkSources.CoverageDelta) refresh;
        scanner.validateCommit(owner, refresh);
        FloorFieldIndex.PreparedPublication update = index.prepareDelta(key, owner, delta);

        assertSame(view, update.targetView(), "an in-generation edit retains the shared view identity");
        assertSame(oldCoverage, index.coverageFor(key, owner), "prepare does not publish the candidate coverage");
        index.commitPrepared(update);

        FloorChunkCoverage published = index.coverageFor(key, owner);
        assertSame(published, scanner.committedCoverage(),
                "scanner and point sampler observe the same published coverage pointer");
        assertNotSame(oldCoverage, published);
        assertTrue(oldCoverage.hasEmitterAt(0, 0, 0, Face.UP));
        assertFalse(oldCoverage.hasEmitterAt(0, 0, 0, Face.EAST));
        assertFalse(published.hasEmitterAt(0, 0, 0, Face.UP));
        assertTrue(published.hasEmitterAt(0, 0, 0, Face.EAST));

        Object replacementOwner = new Object();
        index.replaceChunk(key, replacementOwner,
                List.of(new Source(0, 0, 0, Face.DOWN, Kind.GRAVITY, PROFILE)));
        assertThrows(IllegalStateException.class, scanner::committedCoverage,
                "replacing the generation invalidates the old scanner attachment");
    }

    @Test
    void wrongChunkPositionsDoNotReadStorageAndMalformedStorageFailsExplicitly() {
        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0,
                () -> new ExtendedBlockStorage[16], PROFILE);
        scanner.reread(16, 0, 0);
        scanner.reread(0, -1, 0);
        scanner.reread(0, 256, 0);
        assertTrue(scanner.snapshot().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> FloorChunkSources.scanExisting(0, 0, () -> null, PROFILE));
        assertThrows(IllegalStateException.class,
                () -> FloorChunkSources.scanExisting(0, 0, () -> new ExtendedBlockStorage[1], PROFILE));
        ExtendedBlockStorage[] invalid = new ExtendedBlockStorage[16];
        invalid[0] = new ExtendedBlockStorage(16, false);
        assertThrows(IllegalStateException.class,
                () -> FloorChunkSources.scanExisting(0, 0, () -> invalid, PROFILE));
    }
}
