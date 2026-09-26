package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockWall;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/** Real mapped block callbacks against a finite in-memory read fixture, not a running server. */
class BootNativeCollisionCallbacksTest {
    private static final BootQueryFootprint.Limits LIMITS = new BootQueryFootprint.Limits(1024, 4096, 64, 512);
    private static final BlockPos CELL = new BlockPos(8, 64, 8);

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void fullAndSlabUseActualStateGeometryAtTheCellOffset() {
        var world = new ReadFixture(plan());
        var full = new MagneticBlockReader.Observation(CELL, Blocks.IRON_BLOCK.getDefaultState(), true, 17);
        assertEquals(new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 65, 9)),
                BootWorldSurfaces.collectNativeCell(world, null, full, wholeCell(CELL), 1).getFirst().rawBounds());
        var slab = Blocks.STONE_SLAB.getDefaultState();
        var bottom = new MagneticBlockReader.Observation(CELL, slab.withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM), true, 17);
        var top = new MagneticBlockReader.Observation(CELL, slab.withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP), true, 17);
        assertEquals(new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 64.5, 9)),
                BootWorldSurfaces.collectNativeCell(world, null, bottom, wholeCell(CELL), 1).getFirst().rawBounds());
        assertEquals(new Aabb(new Vec3d(8, 64.5, 8), new Vec3d(9, 65, 9)),
                BootWorldSurfaces.collectNativeCell(world, null, top, wholeCell(CELL), 1).getFirst().rawBounds());
    }

    @Test
    void exactCandidateCapAllowsSubsequentEmptyCellsButNotAnExtraBox() {
        var world = new ReadFixture(plan());
        var solid = new MagneticBlockReader.Observation(CELL, Blocks.STONE.getDefaultState(), false, 17);
        int remaining = 1 - BootWorldSurfaces.collectNativeCell(world, null, solid, wholeCell(CELL), 1).size();
        var air = new MagneticBlockReader.Observation(CELL, Blocks.AIR.getDefaultState(), false, 17);
        assertTrue(BootWorldSurfaces.collectNativeCell(world, null, air, wholeCell(CELL), remaining).isEmpty());
        var outsideQuery = wholeCell(CELL.add(20, 0, 0));
        assertTrue(BootWorldSurfaces.collectNativeCell(world, null, solid, outsideQuery, remaining).isEmpty());
        assertThrows(BootWorldSurfaces.CandidateLimitFailure.class,
                () -> BootWorldSurfaces.collectNativeCell(world, null, solid, wholeCell(CELL), remaining));
    }

    @Test
    void rejectedSubclassCannotRunItsCallbackEvenThroughTheProductionCellSeam() {
        var world = new ReadFixture(plan());
        var untrusted = new BlockWall(Blocks.COBBLESTONE) {
            @Override
            public void addCollisionBoxToList(IBlockState state, World callbackWorld, BlockPos pos,
                                              AxisAlignedBB query, List<AxisAlignedBB> boxes, Entity actor,
                                              boolean actualState) {
                throw new AssertionError("untrusted callback ran before rejection");
            }
        };
        var observation = new MagneticBlockReader.Observation(CELL, untrusted.getDefaultState(), true, 17);
        assertThrows(BootWorldSurfaces.UnsupportedCallbackFailure.class,
                () -> BootWorldSurfaces.collectNativeCell(world, null, observation, wholeCell(CELL), 16));
    }

    @Test
    void stairsRetainActualMultipleBoxesAndCallbackLocalProvenance() {
        var plan = plan();
        var world = new ReadFixture(plan);
        var state = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST)
                .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.BOTTOM);
        world.states.put(CELL, state);
        var observation = new MagneticBlockReader.Observation(CELL, state, true, 17);
        var boxes = BootWorldSurfaces.collectNativeCell(world, null, observation, wholeCell(CELL), 2);
        assertEquals(2, boxes.size());
        assertEquals(new Aabb(new Vec3d(8, 64, 8), new Vec3d(9, 64.5, 9)), boxes.getFirst().rawBounds());
        for (int i = 0; i < boxes.size(); i++) {
            var box = boxes.get(i);
            assertEquals(CELL, box.cell());
            assertSame(state, box.storedState());
            assertEquals(i, box.ordinal());
            assertEquals(17, box.magneticObservationTick());
            assertTrue(box.magnetic());
        }
        assertTrue(world.reads.contains(CELL.east()));
        assertTrue(world.reads.contains(CELL.west()));
        assertThrows(UnsupportedOperationException.class, boxes::clear);
        assertThrows(BootWorldSurfaces.CandidateLimitFailure.class,
                () -> BootWorldSurfaces.collectNativeCell(world, null, observation, wholeCell(CELL), 1));
    }

    @Test
    void actualStairCallbackBoxesCompileToExposedTreadsWithoutTheirBuriedInterface() {
        var world = new ReadFixture(plan());
        var state = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST)
                .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.BOTTOM);
        world.states.put(CELL, state);
        var boxes = BootWorldSurfaces.collectNativeCell(world, null,
                new MagneticBlockReader.Observation(CELL, state, true, 17), wholeCell(CELL), 2);
        assertEquals(2, boxes.size());
        var qualification = new BootSurfaceCompiler.Qualification("fixture:stairs",
                new BootTier(new FloorFieldProfile(32, 0.5, 2), 1.5, 5));
        var limits = new BootSurfaceCompiler.Limits(16, 96, 32, 1024, 65536, 128);
        var compiled = assertInstanceOf(BootSurfaceCompiler.SurfaceSet.class, BootSurfaceCompiler.compile(boxes,
                Map.of(boxes.get(0), qualification, boxes.get(1), qualification), limits));
        var treads = compiled.fragments().stream().filter(f -> f.geometry().outward() == CardinalFrame.Y_POS).toList();
        assertEquals(2, treads.size());
        assertEquals(List.of(64.5, 65.0), treads.stream().map(f -> f.geometry().bounds().min().y()).toList());
        assertEquals(1.0, treads.stream().mapToDouble(f -> {
            var bounds = f.geometry().bounds();
            return (bounds.max().x() - bounds.min().x()) * (bounds.max().z() - bounds.min().z());
        }).sum());
        assertSame(boxes.getFirst(), compiled.observations().getFirst());
        assertTrue(compiled.fragments().stream().allMatch(f -> f.qualification().equals(qualification)));
    }

    @Test
    void connectedNativeGeometryIsNotReplacedByAFullCube() {
        var world = new ReadFixture(plan());
        world.states.put(CELL.east(), Blocks.STONE.getDefaultState());
        var fence = new MagneticBlockReader.Observation(CELL, Blocks.OAK_FENCE.getDefaultState(), false, 17);
        var boxes = BootWorldSurfaces.collectNativeCell(world, null, fence, wholeCell(CELL), 2);
        assertEquals(2, boxes.size());
        for (var box : boxes) {
            assertEquals(65.5, box.rawBounds().max().y());
            assertTrue(box.rawBounds().min().z() > CELL.getZ());
            assertTrue(box.rawBounds().max().z() < CELL.getZ() + 1);
        }
        var wall = new MagneticBlockReader.Observation(CELL, Blocks.COBBLESTONE_WALL.getDefaultState(), false, 17);
        var wallBoxes = BootWorldSurfaces.collectNativeCell(world, null, wall, wholeCell(CELL), 1);
        assertEquals(1, wallBoxes.size());
        assertEquals(65.5, wallBoxes.getFirst().rawBounds().max().y());
        assertTrue(wallBoxes.getFirst().rawBounds().min().z() > CELL.getZ());
    }

    @ParameterizedTest
    @EnumSource(value = EnumFacing.class, names = {"NORTH", "SOUTH", "WEST", "EAST"})
    void plannedDependenciesCoverNeighborStairsFaceShapeReads(EnumFacing direction) {
        var plan = plan();
        var world = new ReadFixture(plan);
        int edge = plan.producers().stream().mapToInt(p -> coordinate(p, direction))
                .max().orElseThrow();
        BlockPos producer = plan.producers().stream()
                .filter(p -> coordinate(p, direction) == edge && p.getY() == CELL.getY())
                .filter(p -> direction.getAxis() == EnumFacing.Axis.X ? p.getZ() == CELL.getZ() : p.getX() == CELL.getX())
                .findFirst().orElseThrow();
        var stair = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, direction);
        world.states.put(producer.offset(direction), stair);
        for (IBlockState state : List.of(Blocks.OAK_FENCE.getDefaultState(), Blocks.COBBLESTONE_WALL.getDefaultState())) {
            world.reads.clear();
            BootWorldSurfaces.collectNativeCell(world, null,
                    new MagneticBlockReader.Observation(producer, state, false, 17), plan.nativeBounds(), 8);
            // Native fence/wall asks the adjacent stair for its face shape;
            // that stair resolves its own neighbours, two cells from producer.
            assertTrue(world.reads.contains(producer.offset(direction, 2)));
            assertTrue(new HashSet<>(plan.dependencies()).containsAll(world.reads));
        }
    }

    private static int coordinate(BlockPos pos, EnumFacing direction) {
        return pos.getX() * direction.getXOffset() + pos.getZ() * direction.getZOffset();
    }

    private static Aabb wholeCell(BlockPos pos) {
        return new Aabb(new Vec3d(pos.getX() - 0.1, pos.getY() - 0.1, pos.getZ() - 0.1),
                new Vec3d(pos.getX() + 1.1, pos.getY() + 1.6, pos.getZ() + 1.1));
    }

    private static BootQueryFootprint plan() {
        var up = CardinalFrame.Y_POS;
        var body = new PlayerBodyShape.Cardinal(up, new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F));
        var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 3),
                4, new PlayerBodyPlacement(new Vec3d(8.5, 65.5, 8.5), up.attitude(), body), new Vec3d(0, 0, 0));
        return BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(physical), 1.5, LIMITS);
    }

    private static final class ReadFixture extends World {
        private final Set<BlockPos> allowed;
        private final Set<BlockPos> reads = new HashSet<>();
        private final Map<BlockPos, IBlockState> states = new HashMap<>();

        private ReadFixture(BootQueryFootprint footprint) {
            super(new SaveHandlerMP(), new WorldInfo(new NBTTagCompound()), new WorldProviderSurface(), new Profiler(), false);
            allowed = Set.copyOf(footprint.dependencies());
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            BlockPos cell = pos.toImmutable();
            if (!allowed.contains(cell)) throw new AssertionError("native callback escaped planned dependency: " + cell);
            reads.add(cell);
            return states.getOrDefault(cell, Blocks.AIR.getDefaultState());
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            throw new AssertionError("callback fixture must not load or generate chunks");
        }

        @Override
        protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
            throw new AssertionError("callback fixture uses only its explicit finite cell domain");
        }
    }
}
