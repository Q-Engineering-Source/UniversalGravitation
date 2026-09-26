package wi.unigrav.core.floor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

/**
 * Server-thread material classification of one existing loaded block cell.
 *
 * <p>This reads stored listed BlockState properties, not getActualState or item
 * metadata. It never requests chunk generation or TE creation/cleanup. Native
 * getLoadedChunk clears unloadQueued, and virtual TE serialization may have mod
 * side effects; the post-check is an observation check, not an atomic world lock.
 * No result proves a collision surface, free capture path, attachment or support.
 */
public final class MagneticBlockReader {
    private MagneticBlockReader() {
    }

    public enum UnavailableReason {
        DEFERRED,
        UNLOADED,
        CHANGED,
        MISSING_TILE_ENTITY,
        INVALID_TILE_ENTITY,
        CALLBACK_FAILED
    }

    public enum EvidenceKind {
        STORED_STATE,
        MATCHED_TILE_ENTITY_NBT
    }

    /** Explicit caller-owned selector identity; it is never inferred from a block registry ID. */
    public record RuleBinding(String selectionId, MagneticBlockRule rule) {
        public RuleBinding {
            if (selectionId == null || selectionId.isBlank()) {
                throw new IllegalArgumentException("selectionId must not be blank");
            }
            Objects.requireNonNull(rule, "rule");
        }
    }

    /** Immutable ordered selector set with an explicit caller-supplied revision. */
    public record RuleSet(long revision, List<RuleBinding> bindings) {
        public RuleSet {
            if (revision < 0L) {
                throw new IllegalArgumentException("rules revision must be nonnegative");
            }
            Objects.requireNonNull(bindings, "bindings");
            bindings = List.copyOf(bindings);
            Set<String> ids = new HashSet<>();
            for (RuleBinding binding : bindings) {
                RuleBinding nonNullBinding = Objects.requireNonNull(binding, "rule binding");
                if (!ids.add(nonNullBinding.selectionId())) {
                    throw new IllegalArgumentException("rule selection IDs must be unique");
                }
            }
        }
    }

    /** Immutable material decision only; no NBT, TileEntity, rule object, or inferred ID is retained. */
    public record MaterialMatch(String selectionId, EvidenceKind evidenceKind) {
        public MaterialMatch {
            if (selectionId == null || selectionId.isBlank()) {
                throw new IllegalArgumentException("selectionId must not be blank");
            }
            Objects.requireNonNull(evidenceKind, "evidenceKind");
        }
    }

    public sealed interface Result permits Observation, Unavailable {
    }

    public sealed interface QualifiedResult permits QualifiedObservation, Unavailable {
    }

    private sealed interface InternalResult permits InternalObservation, Unavailable {
    }

    /** Stored state and material eligibility at this cell's observation time, not a reusable authorization. */
    public record Observation(BlockPos position, IBlockState storedState, boolean magnetic, long worldTick) implements Result {
        public Observation {
            position = Objects.requireNonNull(position, "position").toImmutable();
            Objects.requireNonNull(storedState, "storedState");
        }
    }

    /** Same cell observation as {@link Observation}, with its optional exact selector decision. */
    public record QualifiedObservation(Observation observation, Optional<MaterialMatch> materialMatch)
            implements QualifiedResult {
        public QualifiedObservation {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(materialMatch, "materialMatch");
            if (observation.magnetic() != materialMatch.isPresent()) {
                throw new IllegalArgumentException("qualified material match must agree with legacy magnetic result");
            }
        }
    }

    public record Unavailable(UnavailableReason reason, Optional<RuntimeException> cause)
            implements Result, QualifiedResult, InternalResult {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** Requires a real block-height cell and fully compiled rules; never treats an unavailable read as nonmagnetic. */
    public static Result read(WorldServer world, BlockPos position, List<MagneticBlockRule> rules) {
        List<MagneticBlockRule> ownedRules = List.copyOf(rules);
        InternalResult result = readInternal(world, position,
                (state, tileNbt) -> new Selection(matchesStored(state, tileNbt, ownedRules), Optional.empty()));
        if (result instanceof Unavailable unavailable) return unavailable;
        return ((InternalObservation) result).observation();
    }

    /**
     * Reads through the same loaded-state/TE/post-check path as {@link #read}, retaining the
     * explicit ordered selector that matched. No default selector or rule-set revision is inferred.
     */
    public static QualifiedResult readQualified(WorldServer world, BlockPos position, RuleSet ruleSet) {
        Objects.requireNonNull(ruleSet, "ruleSet");
        InternalResult result = readInternal(world, position,
                (state, tileNbt) -> {
                    Optional<MaterialMatch> match = matchQualifiedStored(state, tileNbt, ruleSet);
                    return new Selection(match.isPresent(), match);
                });
        if (result instanceof Unavailable unavailable) return unavailable;
        InternalObservation observation = (InternalObservation) result;
        return new QualifiedObservation(observation.observation(), observation.materialMatch());
    }

    private static InternalResult readInternal(WorldServer world, BlockPos position, Selector selector) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(selector, "selector");
        if (world.isRemote || !world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("magnetic block reads require the authoritative server thread");
        }
        if (position.getY() < 0 || position.getY() >= 256) {
            throw new IllegalArgumentException("magnetic block read requires a cell in the native build height");
        }
        BlockPos cell = position.toImmutable();
        if (deferred(world)) return unavailable(UnavailableReason.DEFERRED);
        Chunk chunk = loadedChunk(world, cell);
        if (chunk == null) return unavailable(UnavailableReason.UNLOADED);
        long tick = world.getTotalWorldTime();
        IBlockState state = chunk.getBlockState(cell);
        CellRead read = new CellRead(world, chunk, cell, state);
        try {
            Selection selection = selector.select(state, read::tileNbt);
            if (deferred(world)) return unavailable(UnavailableReason.DEFERRED);
            if (world.getTotalWorldTime() != tick || loadedChunk(world, cell) != chunk
                    || chunk.getBlockState(cell) != state || !read.sameTile()) {
                return unavailable(UnavailableReason.CHANGED);
            }
            Observation observation = new Observation(cell, state, selection.magnetic(), tick);
            return new InternalObservation(observation, selection.materialMatch());
        } catch (CellUnavailable failure) {
            return unavailable(failure.reason);
        } catch (RuntimeException failure) {
            return new Unavailable(UnavailableReason.CALLBACK_FAILED, Optional.of(failure));
        }
    }

    /** Production matching seam: serializes at most once, and only for a matching state that needs NBT. */
    static boolean matchesStored(IBlockState state, Supplier<NBTTagCompound> tileNbt, List<MagneticBlockRule> rules) {
        boolean needsNbt = false;
        for (MagneticBlockRule rule : rules) {
            if (!rule.matchesState(state)) continue;
            if (!rule.requiresTileEntityNbt()) return true;
            needsNbt = true;
        }
        if (!needsNbt) return false;
        NBTTagCompound actualNbt = tileNbt.get();
        for (MagneticBlockRule rule : rules) {
            if (rule.matches(state, actualNbt)) return true;
        }
        return false;
    }

    /** Shared qualified selector seam; world reads call the same selection path after their loaded-state checks. */
    static Optional<MaterialMatch> matchQualifiedStored(IBlockState state, Supplier<NBTTagCompound> tileNbt,
                                                        RuleSet ruleSet) {
        Objects.requireNonNull(ruleSet, "ruleSet");
        return selectQualified(state, tileNbt, ruleSet.bindings()).materialMatch();
    }

    private static Selection selectQualified(IBlockState state, Supplier<NBTTagCompound> tileNbt,
                                             List<RuleBinding> bindings) {
        boolean needsNbt = false;
        for (RuleBinding binding : bindings) {
            MagneticBlockRule rule = binding.rule();
            if (!rule.matchesState(state)) continue;
            if (!rule.requiresTileEntityNbt()) {
                return new Selection(true, Optional.of(new MaterialMatch(binding.selectionId(), EvidenceKind.STORED_STATE)));
            }
            needsNbt = true;
        }
        if (!needsNbt) return Selection.NO_MATCH;
        NBTTagCompound actualNbt = tileNbt.get();
        for (RuleBinding binding : bindings) {
            if (binding.rule().matches(state, actualNbt)) {
                return new Selection(true, Optional.of(new MaterialMatch(binding.selectionId(),
                        EvidenceKind.MATCHED_TILE_ENTITY_NBT)));
            }
        }
        return Selection.NO_MATCH;
    }

    @FunctionalInterface
    private interface Selector {
        Selection select(IBlockState state, Supplier<NBTTagCompound> tileNbt);
    }

    private record Selection(boolean magnetic, Optional<MaterialMatch> materialMatch) {
        private static final Selection NO_MATCH = new Selection(false, Optional.empty());

        private Selection {
            Objects.requireNonNull(materialMatch, "materialMatch");
        }
    }

    private record InternalObservation(Observation observation, Optional<MaterialMatch> materialMatch) implements InternalResult {
        private InternalObservation {
            Objects.requireNonNull(observation, "observation");
            Objects.requireNonNull(materialMatch, "materialMatch");
        }
    }

    private static boolean deferred(WorldServer world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }

    private static Chunk loadedChunk(WorldServer world, BlockPos position) {
        if (!world.isBlockLoaded(position, true)) return null;
        Chunk chunk = world.getChunkProvider().getLoadedChunk(position.getX() >> 4, position.getZ() >> 4);
        return chunk != null && chunk.isLoaded() && chunk.getWorld() == world ? chunk : null;
    }

    private static Unavailable unavailable(UnavailableReason reason) {
        return new Unavailable(reason, Optional.empty());
    }

    private static final class CellRead {
        private final WorldServer world;
        private final Chunk chunk;
        private final BlockPos position;
        private final IBlockState state;
        private boolean tileRead;
        private TileEntity tile;

        private CellRead(WorldServer world, Chunk chunk, BlockPos position, IBlockState state) {
            this.world = world;
            this.chunk = chunk;
            this.position = position;
            this.state = state;
        }

        private NBTTagCompound tileNbt() {
            if (!state.getBlock().hasTileEntity(state)) return null;
            tileRead = true;
            // CHECK also removes invalid entries. Only read the actual backing
            // map; do not create, queue, remove or repair another module's TE.
            tile = chunk.getTileEntityMap().get(position);
            if (tile == null) {
                throw new CellUnavailable(UnavailableReason.MISSING_TILE_ENTITY);
            }
            if (!validTile()) throw new CellUnavailable(UnavailableReason.INVALID_TILE_ENTITY);
            return Objects.requireNonNull(tile.writeToNBT(new NBTTagCompound()), "TileEntity serialization result");
        }

        private boolean validTile() {
            return !tile.isInvalid() && tile.getWorld() == world && position.equals(tile.getPos());
        }

        private boolean sameTile() {
            return !tileRead || chunk.getTileEntityMap().get(position) == tile && (tile == null || validTile());
        }
    }

    private static final class CellUnavailable extends RuntimeException {
        private final UnavailableReason reason;

        private CellUnavailable(UnavailableReason reason) {
            super(reason.name());
            this.reason = reason;
        }
    }
}
