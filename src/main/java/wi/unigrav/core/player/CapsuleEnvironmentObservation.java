package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

/** Actual-capsule voxel triggers and actual fluid volumes, independent of the native proxy box. */
final class CapsuleEnvironmentObservation {
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    record Cell(BlockPos position, IBlockState state, ContactResult voxel,
                Optional<ContactResult> fluid, boolean burning) { }
    private record Dependency(BlockPos position, IBlockState state) { }
    sealed interface Result permits Prepared, Unavailable { List<Cell> cells(); }
    record Unavailable(String reason, List<Cell> cells, Optional<RuntimeException> cause) implements Result {
        Unavailable { cells = List.copyOf(cells); }
    }
    static final class Prepared implements Result {
        private final Object sourceIdentity;
        private final World world;
        private final Runnable requireCurrent;
        private final LoadedBlockAccess blocks;
        private final CapsulePlacementMath.Pose pose;
        private final List<Cell> cells;
        private final List<Dependency> dependencies;
        private final boolean inWater;
        private final boolean inLava;
        private final boolean raining;
        private Prepared(Object sourceIdentity, World world, Runnable requireCurrent, LoadedBlockAccess blocks,
                         CapsulePlacementMath.Pose pose,
                         List<Cell> cells, List<Dependency> dependencies, boolean inWater, boolean inLava, boolean raining) {
            this.sourceIdentity = sourceIdentity; this.world = world; this.requireCurrent = requireCurrent; this.pose = pose; this.cells = List.copyOf(cells);
            this.blocks = blocks;
            this.dependencies = List.copyOf(dependencies);
            this.inWater = inWater; this.inLava = inLava; this.raining = raining;
        }
        public List<Cell> cells() { return cells; }
        boolean inWater() { return inWater; }
        boolean inLava() { return inLava; }
        boolean wet() { return inWater || raining; }
        boolean burning() { return cells.stream().anyMatch(Cell::burning); }
        boolean matches(Object exactSource, CapsulePlacementMath.Pose exactPose) {
            if (sourceIdentity != exactSource || pose != exactPose) return false;
            requireCurrent.run();
            for (Cell cell : cells) {
                charge(blocks.work, EnvironmentObservationWork.Unit.COMPARISON);
                if (!cell.state().equals(blocks.getBlockState(cell.position()))) return false;
            }
            for (Dependency dependency : dependencies) {
                charge(blocks.work, EnvironmentObservationWork.Unit.COMPARISON);
                if (!dependency.state().equals(blocks.getBlockState(dependency.position()))) return false;
            }
            requireCurrent.run();
            return true;
        }
    }

    private CapsuleEnvironmentObservation() { }

    static Result observe(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose pose) {
        Objects.requireNonNull(transaction);
        return observe(transaction, transaction.actor(), pose, transaction.queries(), transaction::requireCurrent,
                FreeNativeEnvironment.serverLoadedChunks(transaction.world()));
    }

    static Result observe(FreeOwnerTransaction transaction, CapsulePlacementMath.Pose pose,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(transaction).requireCurrent();
        Objects.requireNonNull(work).requireCurrent(transaction.actor());
        if (transaction.resources().environmentObservationWork() != work)
            throw new IllegalArgumentException("foreign observation work");
        return observe(transaction, transaction.actor(), pose, transaction.queries(), () -> {
            transaction.requireCurrent(); work.requireCurrent(transaction.actor());
        }, FreeNativeEnvironment.serverLoadedChunks(transaction.world()), work);
    }

    /** Geometry and environment observation only; caller retains owner and movement authority. */
    static Result observe(Object sourceIdentity, Entity actor, CapsulePlacementMath.Pose pose,
                          CapsuleContactQueryBudget queries, Runnable requireCurrent,
                          FreeNativeEnvironment.LoadedChunks loadedChunks) {
        return observe(sourceIdentity, actor, pose, queries, requireCurrent, loadedChunks, null);
    }

    static Result observe(Object sourceIdentity, Entity actor, CapsulePlacementMath.Pose pose,
            CapsuleContactQueryBudget queries, Runnable requireCurrent,
            FreeNativeEnvironment.LoadedChunks loadedChunks, EnvironmentObservationWork work) {
        Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(actor); Objects.requireNonNull(pose);
        Objects.requireNonNull(queries); Objects.requireNonNull(requireCurrent);
        World world = Objects.requireNonNull(actor.world);
        var blocks = new LoadedBlockAccess(Objects.requireNonNull(loadedChunks), work);
        requireCurrent.run();
        var bounds = pose.capsule().bounds();
        var footprint = work == null ? CollisionQueryFootprint.plan(bounds, 0, SCAN)
                : CollisionQueryFootprint.plan(bounds, 0, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, work);
        for (var column : footprint.columns()) {
            charge(work, EnvironmentObservationWork.Unit.DISPATCH);
            var chunk = loadedChunks.find(column.x(), column.z());
            if (chunk == null || !chunk.isLoaded())
                return new Unavailable("actual capsule trigger scan lost a loaded column", List.of(), Optional.empty());
        }
        var cells = new ArrayList<Cell>();
        var dependencies = new ArrayList<Dependency>();
        boolean water = false, lava = false;
        int minX = coordinate(bounds.min().x(), false, work), maxX = coordinate(bounds.max().x(), true, work);
        int minY = coordinate(bounds.min().y(), false, work), maxY = coordinate(bounds.max().y(), true, work);
        int minZ = coordinate(bounds.min().z(), false, work), maxZ = coordinate(bounds.max().z(), true, work);
        try {
            for (int x = minX; x < maxX; x++) for (int y = minY; y < maxY; y++) for (int z = minZ; z < maxZ; z++) {
                requireCurrent.run();
                charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                var position = new BlockPos(x, y, z);
                IBlockState state = blocks.getBlockState(position);
                charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                var voxel = new Aabb(new Vec3d(x, y, z), new Vec3d(x + 1, y + 1, z + 1));
                ContactResult contact = queries.query(pose.capsule(), voxel, FreeOwnerTransaction.ACCURACY,
                        "environment-voxel position=" + position);
                charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                if (contact.relation() == Relation.UNRESOLVED) {
                    cells.add(new Cell(position, state, contact, Optional.empty(), false));
                    return new Unavailable("actual capsule voxel relation unresolved: position=" + position
                            + " reason=" + contact.reason() + " work=" + contact.work(), cells, Optional.empty());
                }
                Optional<ContactResult> fluid = Optional.empty();
                boolean burning = false;
                charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                if (contact.relation() == Relation.OVERLAPPING) {
                    var block = state.getBlock();
                    burning = block == Blocks.FIRE || block == Blocks.FLOWING_LAVA || block == Blocks.LAVA
                            || burning(block, world, position, work);
                    charge(work, EnvironmentObservationWork.Unit.DISPATCH);
                    Material material = state.getMaterial();
                    charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                    if (material == Material.WATER || material == Material.LAVA) {
                        charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                        if (!(block instanceof BlockLiquid)) {
                            cells.add(new Cell(position, state, contact, Optional.empty(), burning));
                            return new Unavailable("fluid has no actual bounded volume contract", cells, Optional.empty());
                        }
                        // Native liquid height reads the voxel above even when it lies outside this capsule's bounds.
                        charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                        BlockPos above = position.up();
                        dependencies.add(new Dependency(above, blocks.getBlockState(above)));
                        charge(work, EnvironmentObservationWork.Unit.DISPATCH);
                        double height = BlockLiquid.getBlockLiquidHeight(state, blocks, position);
                        charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                        if (!Double.isFinite(height) || height <= 0 || height > 1)
                            throw new IllegalStateException("Native fluid height outside its voxel");
                        charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                        var volume = new Aabb(voxel.min(), new Vec3d(x + 1, y + height, z + 1));
                        ContactResult immersion = queries.query(pose.capsule(), volume, FreeOwnerTransaction.ACCURACY,
                                "environment-fluid-volume position=" + position + ",height=" + height);
                        fluid = Optional.of(immersion);
                        charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                        if (immersion.relation() == Relation.UNRESOLVED) {
                            cells.add(new Cell(position, state, contact, fluid, burning));
                            return new Unavailable("actual fluid-volume relation unresolved: position=" + position
                                    + " reason=" + immersion.reason() + " work=" + immersion.work(), cells, Optional.empty());
                        }
                        charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                        if (immersion.relation() == Relation.OVERLAPPING) {
                            water |= material == Material.WATER;
                            lava |= material == Material.LAVA;
                        }
                    }
                }
                cells.add(new Cell(position, state, contact, fluid, burning));
            }
        } catch (CapsuleContactQueryBudget.BudgetExhausted failure) {
            return new Unavailable("actual capsule trigger query budget exhausted", cells, Optional.of(failure));
        } catch (LoadedReadUnavailable failure) {
            return new Unavailable("actual capsule loaded-state read unavailable", cells, Optional.of(failure));
        }
        // Keep the native rain API, with the accepted physical body points rather than proxy anchors.
        var stored = pose.placement().storedCapsuleGeometry();
        var negative = stored != null ? stored.negativeEndpoint() : pose.capsule().centerline().start();
        var positive = stored != null ? stored.positiveEndpoint() : pose.capsule().centerline().end();
        boolean raining = raining(world, negative, work)
                || raining(world, positive, work)
                || raining(world, pose.placement().center(), work);
        requireCurrent.run();
        var prepared = new Prepared(sourceIdentity, world, requireCurrent, blocks, pose, cells, dependencies, water, lava, raining);
        return prepared.matches(sourceIdentity, pose) ? prepared
                : new Unavailable("actual trigger states changed during observation", cells, Optional.empty());
    }

    private static int coordinate(double value, boolean upper, EnvironmentObservationWork work) {
        charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        return (int) (upper ? Math.ceil(value) : Math.floor(value));
    }
    private static void charge(EnvironmentObservationWork work, EnvironmentObservationWork.Unit unit) {
        if (work != null) work.charge(unit);
    }
    private static boolean burning(net.minecraft.block.Block block, World world, BlockPos position,
            EnvironmentObservationWork work) {
        charge(work, EnvironmentObservationWork.Unit.DISPATCH);
        return block.isBurning(world, position);
    }
    private static boolean raining(World world, Vec3d position, EnvironmentObservationWork work) {
        charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        BlockPos point = point(position);
        charge(work, EnvironmentObservationWork.Unit.DISPATCH);
        return world.isRainingAt(point);
    }

    private static BlockPos point(Vec3d p) { return new BlockPos(p.x(), p.y(), p.z()); }

    /** The verified native liquid-height entry reads only block state; other reads are explicitly unsupported. */
    private static final class LoadedBlockAccess implements IBlockAccess {
        private final FreeNativeEnvironment.LoadedChunks chunks;
        private final EnvironmentObservationWork work;
        LoadedBlockAccess(FreeNativeEnvironment.LoadedChunks chunks, EnvironmentObservationWork work) {
            this.chunks = chunks; this.work = work;
        }
        @Override public IBlockState getBlockState(BlockPos position) {
            charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            if (position.getY() < 0 || position.getY() >= 256) return Blocks.AIR.getDefaultState();
            charge(work, EnvironmentObservationWork.Unit.DISPATCH);
            var chunk = chunks.find(position.getX() >> 4, position.getZ() >> 4);
            if (chunk == null || !chunk.isLoaded()) throw new LoadedReadUnavailable("block state lost loaded chunk");
            charge(work, EnvironmentObservationWork.Unit.DISPATCH);
            return chunk.getBlockState(position);
        }
        @Override public TileEntity getTileEntity(BlockPos position) { throw unavailable("tile entity"); }
        @Override public int getCombinedLight(BlockPos position, int light) { throw unavailable("combined light"); }
        @Override public boolean isAirBlock(BlockPos position) { throw unavailable("air predicate"); }
        @Override public Biome getBiome(BlockPos position) { throw unavailable("biome"); }
        @Override public int getStrongPower(BlockPos position, EnumFacing side) { throw unavailable("strong power"); }
        @Override public WorldType getWorldType() { throw unavailable("world type"); }
        @Override public boolean isSideSolid(BlockPos position, EnumFacing side, boolean fallback) {
            throw unavailable("side-solid predicate");
        }
        private LoadedReadUnavailable unavailable(String read) {
            return new LoadedReadUnavailable("liquid-height loaded-state adapter does not provide " + read);
        }
    }
    private static final class LoadedReadUnavailable extends RuntimeException {
        LoadedReadUnavailable(String detail) { super(detail); }
    }
}
