package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

/**
 * Storage-backed source view for one loaded chunk generation.
 *
 * <p>The initial scan reads only existing {@link ExtendedBlockStorage} sections.
 * Later mutations reread only the immutable coordinates marked by the Chunk
 * observer. This class never requests, loads, or generates another chunk.</p>
 */
final class FloorChunkSources {

    private static final Comparator<Source> SOURCE_ORDER = Comparator
            .comparingInt(Source::x)
            .thenComparingInt(Source::y)
            .thenComparingInt(Source::z);

    private final int chunkX;
    private final int chunkZ;
    private final int baseX;
    private final int baseZ;
    private final Supplier<ExtendedBlockStorage[]> storage;
    private final FloorFieldProfile profile;
    private final Map<Position, Source> sources = new HashMap<>();

    private FloorChunkSources(int chunkX, int chunkZ, Supplier<ExtendedBlockStorage[]> storage,
                              FloorFieldProfile profile) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.baseX = Math.multiplyExact(chunkX, 16);
        this.baseZ = Math.multiplyExact(chunkZ, 16);
        this.storage = storage;
        this.profile = profile;
    }

    static FloorChunkSources scanExisting(int chunkX, int chunkZ,
                                          Supplier<ExtendedBlockStorage[]> storage,
                                          FloorFieldProfile profile) {
        if (storage == null) {
            throw new IllegalArgumentException("storage must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        FloorChunkSources scanner = new FloorChunkSources(chunkX, chunkZ, storage, profile);
        ExtendedBlockStorage[] sections = scanner.sections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ExtendedBlockStorage section = scanner.section(sections, sectionIndex);
            if (section == null || section.isEmpty()) {
                continue;
            }
            int sectionBaseY = section.getYLocation();
            for (int localY = 0; localY < 16; localY++) {
                int y = sectionBaseY + localY;
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = scanner.baseZ + localZ;
                    for (int localX = 0; localX < 16; localX++) {
                        int x = scanner.baseX + localX;
                        scanner.update(x, y, z, section.get(localX, localY, localZ));
                    }
                }
            }
        }
        return scanner;
    }

    /** Rereads the actual final storage state at one current-chunk position. */
    void reread(int x, int y, int z) {
        if (!belongsToChunk(x, y, z)) {
            return;
        }
        update(x, y, z, storedState(x, y, z));
    }

    List<Source> snapshot() {
        List<Source> snapshot = new ArrayList<>(sources.values());
        snapshot.sort(SOURCE_ORDER);
        return List.copyOf(snapshot);
    }

    private void update(int x, int y, int z, IBlockState state) {
        Position position = new Position(x, y, z);
        Source source = sourceAt(x, y, z, state);
        if (source == null) {
            sources.remove(position);
        } else {
            sources.put(position, source);
        }
    }

    private IBlockState storedState(int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return null;
        }
        ExtendedBlockStorage section = section(sections(), y >> 4);
        return section == null ? null : section.get(x & 15, y & 15, z & 15);
    }

    private ExtendedBlockStorage[] sections() {
        ExtendedBlockStorage[] sections = storage.get();
        if (sections == null || sections.length != 16) {
            throw new IllegalStateException("floor scan requires the current 16-section chunk storage");
        }
        return sections;
    }

    private ExtendedBlockStorage section(ExtendedBlockStorage[] sections, int index) {
        ExtendedBlockStorage section = sections[index];
        if (section != null && section.getYLocation() != index * 16) {
            throw new IllegalStateException("floor scan encountered a misplaced storage section");
        }
        return section;
    }

    private Source sourceAt(int x, int y, int z, IBlockState state) {
        if (state == null) {
            return null;
        }
        Block block = state.getBlock();
        Kind kind;
        if (block == FloorBlocks.GRAVITY_FLOOR) {
            kind = Kind.GRAVITY;
        } else if (block == FloorBlocks.MAGNETIC_FLOOR) {
            kind = Kind.MAGNETIC;
        } else {
            return null;
        }
        return new Source(x, y, z, face(state.getValue(DirectionalFloorBlock.FACING)), kind, profile);
    }

    private boolean belongsToChunk(int x, int y, int z) {
        return y >= 0 && y < 256 && Math.floorDiv(x, 16) == chunkX
                && Math.floorDiv(z, 16) == chunkZ;
    }

    private static Face face(EnumFacing facing) {
        return switch (facing) {
            case DOWN -> Face.DOWN;
            case UP -> Face.UP;
            case NORTH -> Face.NORTH;
            case SOUTH -> Face.SOUTH;
            case WEST -> Face.WEST;
            case EAST -> Face.EAST;
        };
    }

    private record Position(int x, int y, int z) {
    }
}
