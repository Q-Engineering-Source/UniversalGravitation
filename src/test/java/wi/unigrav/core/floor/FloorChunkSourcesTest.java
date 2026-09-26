package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
