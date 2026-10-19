package wi.unigrav.core.floor;

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
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

/**
 * Storage-backed compressed source view for one loaded chunk generation.
 *
 * <p>The initial scan reads only existing storage sections. Later mutations
 * reread only the final states at detached dirty-bit positions. This class
 * never requests, loads, or generates another chunk.</p>
 */
final class FloorChunkSources {

    private static final Comparator<Source> SOURCE_ORDER = Comparator
            .comparingInt(Source::x)
            .thenComparingInt(Source::y)
            .thenComparingInt(Source::z)
            .thenComparingInt(source -> source.face().ordinal());

    private final int chunkX;
    private final int chunkZ;
    private final int baseX;
    private final int baseZ;
    private final Supplier<ExtendedBlockStorage[]> storage;
    private final FloorProfileCatalog profiles;
    private FloorChunkCoverage coverage;
    private Object attachmentOwner;
    private FloorFieldIndex.ChunkView publishedView;
    private boolean attachmentRetired;

    private FloorChunkSources(int chunkX, int chunkZ, Supplier<ExtendedBlockStorage[]> storage,
                              FloorProfileCatalog profiles) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.baseX = Math.multiplyExact(chunkX, 16);
        this.baseZ = Math.multiplyExact(chunkZ, 16);
        this.storage = storage;
        this.profiles = java.util.Objects.requireNonNull(profiles, "profiles");
    }

    static FloorChunkSources scanExisting(int chunkX, int chunkZ,
                                          Supplier<ExtendedBlockStorage[]> storage,
                                          FloorFieldProfile profile) {
        return scanExisting(chunkX, chunkZ, storage, FloorProfileCatalog.fixed(profile));
    }

    static FloorChunkSources scanExisting(int chunkX, int chunkZ,
                                          Supplier<ExtendedBlockStorage[]> storage,
                                          FloorProfileCatalog profiles) {
        if (storage == null) throw new IllegalArgumentException("storage must not be null");
        if (profiles == null) throw new IllegalArgumentException("profiles must not be null");
        FloorChunkSources scanner = new FloorChunkSources(chunkX, chunkZ, storage, profiles);
        ExtendedBlockStorage[] sections = scanner.sections();
        FloorChunkCoverage.Builder builder = FloorChunkCoverage.builder(scanner.key());
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
                        profiles.resolveStored(section.get(localX, localY, localZ))
                                .ifPresent(cell -> builder.addCell(x, y, z, cell.descriptor(), cell.profile()));
                    }
                }
            }
        }
        scanner.coverage = builder.build();
        return scanner;
    }

    FloorChunkCoverage initialCoverage() {
        if (attachmentRetired) {
            throw new IllegalStateException("retired floor scanner has no initial generation coverage");
        }
        return requireCoverage();
    }

    FloorChunkCoverage committedCoverage() {
        if (attachmentRetired) {
            throw new IllegalStateException("retired floor scanner has no current generation coverage");
        }
        if (attachmentOwner != null) {
            if (publishedView == null) {
                throw new IllegalStateException("attached floor scanner has no shared chunk view");
            }
            return publishedView.currentCoverage(attachmentOwner);
        }
        return requireCoverage();
    }

    void validateCommit(Object generationOwner, Refresh refresh) {
        requireCurrentAttachment(generationOwner);
        if (refresh instanceof CoverageDelta delta && delta.base() != committedCoverage()) {
            throw new IllegalStateException("floor scanner coverage changed before commit");
        }
    }

    void attachPublishedCoverage(Object generationOwner, FloorFieldIndex.ChunkView view) {
        if (generationOwner == null) {
            throw new IllegalArgumentException("generation owner must not be null");
        }
        if (view == null) {
            throw new IllegalArgumentException("published chunk view must not be null");
        }
        if (attachmentOwner != null || attachmentRetired) {
            throw new IllegalStateException("floor scanner is already attached to a published generation");
        }
        requireCoverage();
        attachmentOwner = generationOwner;
        publishedView = view;
        coverage = null;
    }

    void retirePublishedCoverage(Object generationOwner) {
        requireCurrentAttachment(generationOwner);
        attachmentOwner = null;
        publishedView = null;
        attachmentRetired = true;
    }

    Refresh prepareReread(Object generationOwner, FloorWorldFields.DirtyBank dirty) {
        requireCurrentAttachment(generationOwner);
        if (dirty == null || dirty.isEmpty()) {
            return NoChange.INSTANCE;
        }
        FloorChunkCoverage current = committedCoverage();
        FloorChunkCoverage.Editor edit = null;
        ExtendedBlockStorage[] sections = sections();
        for (int wordIndex = 0; wordIndex < dirty.wordCount(); wordIndex++) {
            long word = dirty.wordAt(wordIndex);
            while (word != 0L) {
                int bitInWord = Long.numberOfTrailingZeros(word);
                word &= word - 1;
                int encoded = wordIndex * Long.SIZE + bitInWord;
                int y = encoded >>> 8;
                int local = encoded & 255;
                int localZ = local >>> 4;
                int localX = local & 15;
                int x = baseX + localX;
                int z = baseZ + localZ;
                var resolved = profiles.resolveStored(storedState(sections, x, y, z));
                int descriptor = resolved.map(FloorProfileCatalog.ResolvedCell::descriptor).orElse(0);
                FloorFieldProfile profile = resolved.map(FloorProfileCatalog.ResolvedCell::profile)
                        .orElse(profiles.fallbackProfile());
                if (edit == null && current.matchesCell(x, y, z, descriptor, profile)) {
                    continue;
                }
                if (edit == null) {
                    edit = FloorChunkCoverage.edit(current);
                }
                edit.replaceCell(x, y, z, descriptor, profile);
            }
        }
        if (edit == null || !edit.changed()) {
            return NoChange.INSTANCE;
        }
        FloorChunkCoverage next = edit.finish();
        List<FloorChunkCoverage.BucketKey> changedBuckets = edit.changedBuckets(next);
        if (changedBuckets.isEmpty()) {
            return NoChange.INSTANCE;
        }
        Map<Double, Long> rangeDeltas = new HashMap<>();
        mergeRangeDeltas(rangeDeltas, current.rangeCounts(changedBuckets), -1);
        mergeRangeDeltas(rangeDeltas, next.rangeCounts(changedBuckets), 1);
        return new CoverageDelta(current, next, changedBuckets, rangeDeltas);
    }

    private static void mergeRangeDeltas(Map<Double, Long> target, Map<Double, Long> counts, int sign) {
        for (Map.Entry<Double, Long> entry : counts.entrySet()) {
            long delta = sign > 0 ? entry.getValue() : Math.negateExact(entry.getValue());
            long next = Math.addExact(target.getOrDefault(entry.getKey(), 0L), delta);
            if (next == 0L) {
                target.remove(entry.getKey());
            } else {
                target.put(entry.getKey(), next);
            }
        }
    }

    /** Standalone compatibility mutation; a world-attached scanner must use WorldFields publication. */
    void reread(int x, int y, int z) {
        if (attachmentOwner != null || attachmentRetired) {
            throw new IllegalStateException("attached floor scanner must refresh through its WorldFields owner");
        }
        if (!belongsToChunk(x, y, z)) {
            return;
        }
        FloorChunkCoverage current = committedCoverage();
        FloorChunkCoverage.Editor edit = FloorChunkCoverage.edit(current);
        var resolved = profiles.resolveStored(storedState(sections(), x, y, z));
        edit.replaceCell(x, y, z, resolved.map(FloorProfileCatalog.ResolvedCell::descriptor).orElse(0),
                resolved.map(FloorProfileCatalog.ResolvedCell::profile).orElse(profiles.fallbackProfile()));
        coverage = edit.finish();
    }

    List<Source> snapshot() {
        if (attachmentRetired) {
            throw new IllegalStateException("retired floor scanner has no current generation snapshot");
        }
        List<Source> snapshot = new java.util.ArrayList<>(committedCoverage().sources());
        snapshot.sort(SOURCE_ORDER);
        return List.copyOf(snapshot);
    }

    private FloorChunkCoverage requireCoverage() {
        if (coverage == null) {
            throw new IllegalStateException("floor coverage has not been scanned");
        }
        return coverage;
    }

    private void requireCurrentAttachment(Object generationOwner) {
        if (attachmentRetired || attachmentOwner == null || attachmentOwner != generationOwner) {
            throw new IllegalStateException("floor scanner is not attached to the current chunk generation");
        }
    }

    private IBlockState storedState(ExtendedBlockStorage[] sections, int x, int y, int z) {
        if (y < 0 || y >= 256) {
            return null;
        }
        ExtendedBlockStorage section = section(sections, y >> 4);
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

    private boolean belongsToChunk(int x, int y, int z) {
        return y >= 0 && y < 256 && Math.floorDiv(x, 16) == chunkX
                && Math.floorDiv(z, 16) == chunkZ;
    }

    private ChunkKey key() {
        return new ChunkKey(chunkX, chunkZ);
    }

    static int descriptorAt(IBlockState state) {
        if (state == null) {
            return 0;
        }
        Block block = state.getBlock();
        EnumFacing facing;
        if (block == FloorBlocks.GRAVITY_FLOOR) {
            facing = state.getValue(DirectionalFloorBlock.FACING);
            return FloorChunkCoverage.singleDescriptor(Kind.GRAVITY, face(facing));
        }
        if (block == FloorBlocks.MAGNETIC_FLOOR) {
            facing = state.getValue(DirectionalFloorBlock.FACING);
            return FloorChunkCoverage.singleDescriptor(Kind.MAGNETIC, face(facing));
        }
        if (block == FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR) {
            List<EnumFacing> faces = FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.emissionFaces(state);
            return FloorChunkCoverage.doubleGravityDescriptor(face(faces.get(0)), face(faces.get(1)));
        }
        return 0;
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

    sealed interface Refresh permits NoChange, CoverageDelta {
    }

    enum NoChange implements Refresh {
        INSTANCE
    }

    record CoverageDelta(FloorChunkCoverage base, FloorChunkCoverage next,
                         List<FloorChunkCoverage.BucketKey> changedBuckets,
                         Map<Double, Long> rangeDeltas) implements Refresh {
        CoverageDelta {
            if (base == null || next == null || base == next) {
                throw new IllegalArgumentException("coverage delta requires distinct non-null snapshots");
            }
            if (changedBuckets == null || changedBuckets.isEmpty() || rangeDeltas == null) {
                throw new IllegalArgumentException("coverage delta requires its local changed bucket set");
            }
            changedBuckets = List.copyOf(changedBuckets);
            rangeDeltas = Map.copyOf(rangeDeltas);
        }
    }
}
