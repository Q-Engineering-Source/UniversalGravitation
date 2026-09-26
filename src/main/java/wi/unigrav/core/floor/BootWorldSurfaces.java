package wi.unigrav.core.floor;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/**
 * Loaded-only per-cell native collision observations for the bounded boot query profile.
 *
 * <p>The collector retains callback-time provenance rather than trying to recover it from
 * {@code World.getCollisionBoxes}. Every producer is classified through the public
 * {@link MagneticBlockReader} first and then invokes its stored state's actual
 * {@code addCollisionBoxToList} callback. This is an observation, not an atomic world or tile
 * entity snapshot: a successful per-cell magnetic read does not prove global callback/TE stability.
 * {@code getLoadedChunk} avoids generation but clears native unload-queue state; matching rules
 * may invoke real TileEntity serialization, whose arbitrary mod side effects are not treated as
 * nonloading or rolled back here.
 *
 * <p>Only the explicitly known callback profiles below are admitted. Unknown block callback
 * domains return {@link Unavailable}; they are never silently interpreted as no collision boxes.
 * The footprint planner, not this collector, must cover every verified transitive neighbour read
 * of an admitted callback; this class makes no fixed one-cell halo completeness claim.
 */
public final class BootWorldSurfaces {
    private BootWorldSurfaces() {
    }

    public enum Reason {
        ACTOR_SCOPE,
        DEFERRED_WORLD,
        MISSING_DEPENDENCY,
        CHUNK_NOT_READY,
        MAGNETIC_UNAVAILABLE,
        UNSUPPORTED_CALLBACK,
        CANDIDATE_LIMIT,
        MALFORMED_COLLIDER,
        CALLBACK_FAILED,
        CONTEXT_CHANGED
    }

    public sealed interface Capture permits Snapshot, Unavailable {
    }

    public sealed interface QualifiedCapture permits QualifiedSnapshot, Unavailable {
    }

    private sealed interface InternalCapture permits Captured, Unavailable {
    }

    /** Explicit failure, never an empty-success replacement for an unknown/partial observation. */
    public record Unavailable(Reason reason, Optional<BlockPos> cell, String detail,
                              Optional<RuntimeException> cause) implements Capture, QualifiedCapture, InternalCapture {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            cell = Objects.requireNonNull(cell, "cell").map(BlockPos::toImmutable);
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * One raw callback-produced box. {@code ordinal} is local to one producer callback,
     * and {@code rawBounds} is copied directly from that native AABB without reconstruction.
     */
    public record Primitive(BlockPos cell, IBlockState storedState, boolean magnetic, long magneticObservationTick,
                            int ordinal, Aabb rawBounds) {
        public Primitive {
            cell = Objects.requireNonNull(cell, "cell").toImmutable();
            Objects.requireNonNull(storedState, "storedState");
            if (magneticObservationTick < 0L || ordinal < 0) {
                throw new IllegalArgumentException("observation tick and callback ordinal must be nonnegative");
            }
            Objects.requireNonNull(rawBounds, "rawBounds");
        }
    }

    /**
     * A completed bounded observation. It does not certify fit, support, attachment, visibility,
     * complete mod callback coverage, a global revision, or an actor state commit.
     */
    public static final class Snapshot implements Capture {
        private final long captureTick;
        private final BootQueryFootprint footprint;
        private final List<Primitive> primitives;

        private Snapshot(long captureTick, BootQueryFootprint footprint, List<Primitive> primitives) {
            this.captureTick = captureTick;
            this.footprint = footprint;
            this.primitives = List.copyOf(primitives);
        }

        public long captureTick() { return captureTick; }
        public BootQueryFootprint footprint() { return footprint; }
        public List<Primitive> primitives() { return primitives; }
    }

    /** Qualified material decisions for exactly the magnetic primitives in one opaque snapshot. */
    public static final class QualifiedSnapshot implements QualifiedCapture {
        private final Snapshot snapshot;
        private final Map<Primitive, MagneticBlockReader.MaterialMatch> materialMatches;
        private final long rulesRevision;

        private QualifiedSnapshot(Snapshot snapshot, Map<Primitive, MagneticBlockReader.MaterialMatch> materialMatches,
                                  long rulesRevision) {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(materialMatches, "materialMatches");
            if (rulesRevision < 0L) throw new IllegalArgumentException("rulesRevision must be nonnegative");
            materialMatches = Map.copyOf(materialMatches);
            Map<BlockPos, MagneticBlockReader.MaterialMatch> perCell = new HashMap<>();
            for (Primitive primitive : snapshot.primitives()) {
                if (!primitive.magnetic()) {
                    if (materialMatches.containsKey(primitive)) {
                        throw new IllegalArgumentException("nonmagnetic primitive must not retain a material match");
                    }
                    continue;
                }
                MagneticBlockReader.MaterialMatch match = materialMatches.get(primitive);
                if (match == null) throw new IllegalArgumentException("every magnetic primitive requires its same-read material match");
                MagneticBlockReader.MaterialMatch previous = perCell.putIfAbsent(primitive.cell(), match);
                if (previous != null && !previous.equals(match)) {
                    throw new IllegalArgumentException("magnetic primitives from one cell must share one material match");
                }
            }
            if (materialMatches.size() != snapshot.primitives().stream().filter(Primitive::magnetic).count()) {
                throw new IllegalArgumentException("material match keys must be exactly this snapshot's magnetic primitives");
            }
            this.snapshot = snapshot;
            this.materialMatches = materialMatches;
            this.rulesRevision = rulesRevision;
        }

        public Snapshot snapshot() { return snapshot; }
        public Map<Primitive, MagneticBlockReader.MaterialMatch> materialMatches() { return materialMatches; }
        public long rulesRevision() { return rulesRevision; }
    }

    /**
     * Captures every actual native callback box for the planner's producer cells.
     *
     * <p>The planner owns producer/dependency/column extents and their primitive budgets. This
     * method neither expands that footprint nor asks the world to load missing chunks. The caller
     * remains responsible for binding the accepted snapshot's session/world epoch and revision,
     * which cannot be recovered from a live 1.12 entity.
     */
    public static Capture capture(WorldServer world, EntityPlayer actor, BootQueryFootprint footprint,
                                  List<MagneticBlockRule> rules) {
        List<MagneticBlockRule> ownedRules = List.copyOf(rules);
        InternalCapture captured = captureInternal(world, actor, footprint, cell -> {
            MagneticBlockReader.Result result = MagneticBlockReader.read(world, cell, ownedRules);
            if (result instanceof MagneticBlockReader.Unavailable unavailable) throw new MaterialUnavailable(cell, unavailable);
            return new CellMaterial((MagneticBlockReader.Observation) result, Optional.empty());
        });
        return captured instanceof Unavailable unavailable ? unavailable : ((Captured) captured).snapshot();
    }

    /** Same native observation as {@link #capture}, retaining only explicit rule-selection decisions. */
    public static QualifiedCapture captureQualified(WorldServer world, EntityPlayer actor, BootQueryFootprint footprint,
                                                    MagneticBlockReader.RuleSet ruleSet) {
        Objects.requireNonNull(ruleSet, "ruleSet");
        InternalCapture captured = captureInternal(world, actor, footprint, cell -> {
            MagneticBlockReader.QualifiedResult result = MagneticBlockReader.readQualified(world, cell, ruleSet);
            if (result instanceof MagneticBlockReader.Unavailable unavailable) throw new MaterialUnavailable(cell, unavailable);
            MagneticBlockReader.QualifiedObservation observation = (MagneticBlockReader.QualifiedObservation) result;
            return new CellMaterial(observation.observation(), observation.materialMatch());
        });
        if (captured instanceof Unavailable unavailable) return unavailable;
        Captured success = (Captured) captured;
        return new QualifiedSnapshot(success.snapshot(), success.materialMatches(), ruleSet.revision());
    }

    private static InternalCapture captureInternal(WorldServer world, EntityPlayer actor, BootQueryFootprint footprint,
                                                   CellReader reader) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(footprint, "footprint");
        Objects.requireNonNull(reader, "reader");
        if (!world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("boot surface capture requires the authoritative server thread");
        }
        Optional<Unavailable> actorFailure = actorFailure(world, actor, footprint.source());
        if (actorFailure.isPresent()) return actorFailure.orElseThrow();
        if (deferred(world)) return unavailable(Reason.DEFERRED_WORLD, null, "block snapshot capture/restore is active", null);

        try {
            long tick = world.getTotalWorldTime();
            List<Chunk> before = preflight(world, footprint);
            Dependencies dependencies = observeDependencies(world, footprint);
            List<Primitive> primitives = new ArrayList<>();
            Map<Primitive, MagneticBlockReader.MaterialMatch> materialMatches = new HashMap<>();
            for (BlockPos planned : footprint.producers()) {
                BlockPos cell = planned.toImmutable();
                CellMaterial material = reader.read(cell);
                MagneticBlockReader.Observation observation = material.observation();
                if (observation.storedState() != dependencies.stored(cell)) {
                    return unavailable(Reason.CONTEXT_CHANGED, cell,
                            "producer stored state changed after dependency-profile preflight", null);
                }
                try {
                    List<Primitive> produced = collectNativeCell(world, actor, observation, footprint.nativeBounds(),
                            remaining(footprint, primitives));
                    primitives.addAll(produced);
                    material.materialMatch().ifPresent(match -> produced.forEach(primitive -> materialMatches.put(primitive, match)));
                } catch (UnsupportedCallbackFailure unsupported) {
                    return unavailable(Reason.UNSUPPORTED_CALLBACK, cell, unsupported.getMessage(), null);
                } catch (CandidateLimitFailure overBudget) {
                    return unavailable(Reason.CANDIDATE_LIMIT, cell, overBudget.getMessage(), null);
                } catch (MalformedColliderFailure malformed) {
                    return unavailable(Reason.MALFORMED_COLLIDER, cell, malformed.getMessage(), malformed);
                } catch (RuntimeException callbackFailure) {
                    return unavailable(Reason.CALLBACK_FAILED, cell, "native block collision callback failed", callbackFailure);
                }
            }
            List<Chunk> after = preflight(world, footprint);
            if (world.getTotalWorldTime() != tick || !sameChunks(before, after) || !dependencies.sameStoredIdentities(world)
                    || deferred(world)
                    || actorFailure(world, actor, footprint.source()).isPresent()) {
                return unavailable(Reason.CONTEXT_CHANGED, null,
                        "actor, world tick, loaded columns, or snapshot-capture context changed during observation", null);
            }
            return new Captured(new Snapshot(tick, footprint, primitives), materialMatches);
        } catch (MaterialUnavailable unavailable) {
            return unavailable(Reason.MAGNETIC_UNAVAILABLE, unavailable.cell,
                    "magnetic classification is unavailable: " + unavailable.unavailable.reason(),
                    unavailable.unavailable.cause().orElse(null));
        } catch (ObservationFailure failure) {
            return unavailable(failure.reason, failure.cell, failure.getMessage(),
                    failure.getCause() instanceof RuntimeException runtime ? runtime : null);
        } catch (CandidateLimitFailure overBudget) {
            return unavailable(Reason.CANDIDATE_LIMIT, null, overBudget.getMessage(), null);
        }
    }

    @FunctionalInterface
    private interface CellReader { CellMaterial read(BlockPos cell); }

    private record CellMaterial(MagneticBlockReader.Observation observation,
                                Optional<MagneticBlockReader.MaterialMatch> materialMatch) {
        private CellMaterial { Objects.requireNonNull(observation, "observation"); Objects.requireNonNull(materialMatch, "materialMatch"); }
    }

    private record Captured(Snapshot snapshot, Map<Primitive, MagneticBlockReader.MaterialMatch> materialMatches)
            implements InternalCapture {
        private Captured { Objects.requireNonNull(snapshot, "snapshot"); materialMatches = Map.copyOf(materialMatches); }
    }

    private static final class MaterialUnavailable extends RuntimeException {
        private final BlockPos cell;
        private final MagneticBlockReader.Unavailable unavailable;
        private MaterialUnavailable(BlockPos cell, MagneticBlockReader.Unavailable unavailable) {
            this.cell = cell.toImmutable();
            this.unavailable = unavailable;
        }
    }

    /**
     * Stashes every planned callback dependency's exact stored-state identity.
     * This verifies only the listed-state object at these cells, not a global mutation revision,
     * tile-entity stability, or arbitrary callback purity.
     */
    private static Dependencies observeDependencies(WorldServer world, BootQueryFootprint footprint) {
        Map<BlockPos, IBlockState> states = new HashMap<>(footprint.dependencies().size());
        for (BlockPos planned : footprint.dependencies()) {
            BlockPos cell = planned.toImmutable();
            Chunk chunk = world.getChunkProvider().getLoadedChunk(cell.getX() >> 4, cell.getZ() >> 4);
            if (chunk == null || !chunk.isLoaded() || chunk.getWorld() != world) {
                throw new ObservationFailure(Reason.MISSING_DEPENDENCY, cell,
                        "planned dependency chunk disappeared before callback profiling", null);
            }
            IBlockState stored = Objects.requireNonNull(chunk.getBlockState(cell), "stored dependency state");
            if (profile(stored) == CallbackProfile.UNSUPPORTED) {
                throw new ObservationFailure(Reason.UNSUPPORTED_CALLBACK, cell,
                        "dependency callback domain is outside the known boot collector profile", null);
            }
            states.put(cell, stored);
        }
        return new Dependencies(states);
    }

    static Aabb copyRaw(AxisAlignedBB raw) {
        if (raw == null || !Double.isFinite(raw.minX) || !Double.isFinite(raw.minY) || !Double.isFinite(raw.minZ)
                || !Double.isFinite(raw.maxX) || !Double.isFinite(raw.maxY) || !Double.isFinite(raw.maxZ)
                || raw.minX > raw.maxX || raw.minY > raw.maxY || raw.minZ > raw.maxZ) {
            throw new IllegalArgumentException("native collision callback returned a malformed AABB");
        }
        return new Aabb(new Vec3d(raw.minX, raw.minY, raw.minZ), new Vec3d(raw.maxX, raw.maxY, raw.maxZ));
    }

    /**
     * Package-visible native callback seam. Production {@link #capture} uses this exact method;
     * tests may exercise mapped full/slab/stairs/fence/wall callback behavior without inventing
     * collision geometry. The caller must have already established its loaded-world scope.
     */
    static List<Primitive> collectNativeCell(World world, Entity actor, MagneticBlockReader.Observation observation,
                                             Aabb nativeBounds, int remainingBoxes) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(nativeBounds, "nativeBounds");
        if (remainingBoxes < 0) {
            throw new IllegalArgumentException("remainingBoxes must be nonnegative");
        }
        if (profile(observation.storedState()) == CallbackProfile.UNSUPPORTED) {
            throw new UnsupportedCallbackFailure("block callback domain is outside the known boot collector profile");
        }
        BoundedNativeBoxes nativeBoxes = new BoundedNativeBoxes(remainingBoxes);
        observation.storedState().addCollisionBoxToList(world, observation.position(), nativeBox(nativeBounds),
                nativeBoxes, actor, false);
        List<Primitive> primitives = new ArrayList<>(nativeBoxes.size());
        for (int ordinal = 0; ordinal < nativeBoxes.size(); ordinal++) {
            Aabb rawBounds;
            try {
                rawBounds = copyRaw(nativeBoxes.get(ordinal));
            } catch (IllegalArgumentException malformed) {
                throw new MalformedColliderFailure(malformed);
            }
            primitives.add(new Primitive(observation.position(), observation.storedState(), observation.magnetic(),
                    observation.worldTick(), ordinal, rawBounds));
        }
        return List.copyOf(primitives);
    }

    static CallbackProfile profile(IBlockState state) {
        Block block = Objects.requireNonNull(state, "state").getBlock();
        if (block == Blocks.AIR || block == Blocks.WATER || block == Blocks.FLOWING_WATER
                || block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) return CallbackProfile.EMPTY_OR_LOCAL;
        if (block == FloorBlocks.GRAVITY_FLOOR || block == FloorBlocks.MAGNETIC_FLOOR
                || block == Blocks.STONE_SLAB || block == Blocks.WOODEN_SLAB) return CallbackProfile.EMPTY_OR_LOCAL;
        if (block == Blocks.OAK_STAIRS || block == Blocks.OAK_FENCE || block == Blocks.COBBLESTONE_WALL) {
            return CallbackProfile.NEIGHBOR_HALO;
        }
        if (block == Blocks.STONE || block == Blocks.COBBLESTONE || block == Blocks.DIRT || block == Blocks.GRASS
                || block == Blocks.SAND || block == Blocks.GRAVEL || block == Blocks.IRON_BLOCK || block == Blocks.GOLD_BLOCK
                || block == Blocks.DIAMOND_BLOCK || block == Blocks.EMERALD_BLOCK) return CallbackProfile.EMPTY_OR_LOCAL;
        return CallbackProfile.UNSUPPORTED;
    }

    enum CallbackProfile {
        EMPTY_OR_LOCAL,
        NEIGHBOR_HALO,
        UNSUPPORTED
    }

    private static Optional<Unavailable> actorFailure(WorldServer world, EntityPlayer actor, BootSurfaceGeometry.Source source) {
        PlayerPhysicalSnapshot physical = source.physical();
        if (actor.world != world || actor.isDead || !physical.scope().playerId().equals(actor.getUniqueID())
                || physical.scope().dimension() != actor.dimension) {
            return Optional.of(unavailable(Reason.ACTOR_SCOPE, null,
                    "actor does not match the source snapshot player/world scope", null));
        }
        PlayerBodyPlacement placement = physical.placement();
        AxisAlignedBB box = actor.getEntityBoundingBox();
        if (!same(placement.bounds(), box) || actor.posX != placement.transport().x()
                || actor.posY != placement.transport().y() || actor.posZ != placement.transport().z()) {
            return Optional.of(unavailable(Reason.ACTOR_SCOPE, null,
                    "actor placement does not match the source snapshot placement", null));
        }
        return Optional.empty();
    }

    private static List<Chunk> preflight(WorldServer world, BootQueryFootprint footprint) {
        List<Chunk> chunks = new ArrayList<>(footprint.columns().size());
        for (BootQueryFootprint.Column column : footprint.columns()) {
            BlockPos probe = new BlockPos(column.x() * 16, 64, column.z() * 16);
            if (!world.isBlockLoaded(probe, true)) {
                throw new ObservationFailure(Reason.MISSING_DEPENDENCY, null, "a planned dependency column is not loaded", null);
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(column.x(), column.z());
            if (chunk == null) {
                throw new ObservationFailure(Reason.MISSING_DEPENDENCY, null, "loaded dependency column disappeared", null);
            }
            if (!chunk.isLoaded() || chunk.getWorld() != world) {
                throw new ObservationFailure(Reason.CHUNK_NOT_READY, null, "dependency chunk is not ready for native callbacks", null);
            }
            chunks.add(chunk);
        }
        return Collections.unmodifiableList(chunks);
    }

    private static boolean sameChunks(List<Chunk> before, List<Chunk> after) {
        if (before.size() != after.size()) return false;
        for (int index = 0; index < before.size(); index++) {
            if (before.get(index) != after.get(index)) return false;
        }
        return true;
    }

    private record Dependencies(Map<BlockPos, IBlockState> states) {
        private Dependencies {
            states = Map.copyOf(states);
        }

        private IBlockState stored(BlockPos cell) {
            IBlockState state = states.get(cell);
            if (state == null) throw new IllegalStateException("producer is outside its planned dependency cells");
            return state;
        }

        private boolean sameStoredIdentities(WorldServer world) {
            for (Map.Entry<BlockPos, IBlockState> entry : states.entrySet()) {
                BlockPos cell = entry.getKey();
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cell.getX() >> 4, cell.getZ() >> 4);
                if (chunk == null || !chunk.isLoaded() || chunk.getWorld() != world
                        || chunk.getBlockState(cell) != entry.getValue()) return false;
            }
            return true;
        }
    }

    private static int remaining(BootQueryFootprint footprint, List<Primitive> primitives) {
        int remaining = footprint.limits().maxCandidateBoxes() - primitives.size();
        if (remaining < 0) throw new CandidateLimitFailure("boot callback box budget is exhausted");
        return remaining;
    }

    private static AxisAlignedBB nativeBox(Aabb bounds) {
        return new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
    }

    private static boolean same(Aabb expected, AxisAlignedBB actual) {
        return expected.min().x() == actual.minX && expected.min().y() == actual.minY && expected.min().z() == actual.minZ
                && expected.max().x() == actual.maxX && expected.max().y() == actual.maxY && expected.max().z() == actual.maxZ;
    }

    private static boolean deferred(WorldServer world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }

    private static Unavailable unavailable(Reason reason, BlockPos cell, String detail, RuntimeException cause) {
        return new Unavailable(reason, Optional.ofNullable(cell), detail, Optional.ofNullable(cause));
    }

    private static final class ObservationFailure extends RuntimeException {
        private final Reason reason;
        private final BlockPos cell;

        private ObservationFailure(Reason reason, BlockPos cell, String detail, Throwable cause) {
            super(detail, cause);
            this.reason = reason;
            this.cell = cell;
        }
    }

    static final class UnsupportedCallbackFailure extends IllegalArgumentException {
        private UnsupportedCallbackFailure(String detail) {
            super(detail);
        }
    }

    static final class CandidateLimitFailure extends RuntimeException {
        private CandidateLimitFailure(String detail) {
            super(detail);
        }
    }

    static final class MalformedColliderFailure extends IllegalArgumentException {
        private MalformedColliderFailure(IllegalArgumentException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** A native callback cannot allocate an unbounded candidate list through this collector. */
    private static final class BoundedNativeBoxes extends AbstractList<AxisAlignedBB> {
        private final int limit;
        private final List<AxisAlignedBB> delegate = new ArrayList<>();

        private BoundedNativeBoxes(int limit) {
            this.limit = limit;
        }

        @Override
        public AxisAlignedBB get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public void add(int index, AxisAlignedBB element) {
            if (delegate.size() >= limit) {
                throw new CandidateLimitFailure("native callback exceeded boot candidate box limit");
            }
            delegate.add(index, element);
        }
    }
}
