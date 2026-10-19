package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.BlockWall;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootWorldSurfacesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void rawNativeBoxesRemainExactAndMayRemainZeroThickness() {
        AxisAlignedBB raw = new AxisAlignedBB(1.0, -2.0, 3.0, 1.0, 4.0, 5.0);
        var copied = BootWorldSurfaces.copyRaw(raw);
        assertEquals(1.0, copied.min().x());
        assertEquals(1.0, copied.max().x());
        assertEquals(-2.0, copied.min().y());
        assertEquals(5.0, copied.max().z());
    }

    @Test
    void malformedRawNativeBoxesFailInsteadOfBecomingInventedSolids() {
        assertThrows(IllegalArgumentException.class, () -> BootWorldSurfaces.copyRaw(null));
        assertThrows(IllegalArgumentException.class, () -> BootWorldSurfaces.copyRaw(
                new AxisAlignedBB(Double.NaN, 0, 0, 1, 1, 1)));
    }

    @Test
    void knownNativeProfilesCoverBuiltInFullSlabStairsFenceWallAndUgFloor() {
        assertEquals(BootWorldSurfaces.CallbackProfile.EMPTY_OR_LOCAL,
                BootWorldSurfaces.profile(Blocks.STONE.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.EMPTY_OR_LOCAL,
                BootWorldSurfaces.profile(Blocks.STONE_SLAB.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.NEIGHBOR_HALO,
                BootWorldSurfaces.profile(Blocks.OAK_STAIRS.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.NEIGHBOR_HALO,
                BootWorldSurfaces.profile(Blocks.STONE_STAIRS.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.NEIGHBOR_HALO,
                BootWorldSurfaces.profile(Blocks.OAK_FENCE.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.NEIGHBOR_HALO,
                BootWorldSurfaces.profile(Blocks.COBBLESTONE_WALL.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.EMPTY_OR_LOCAL,
                BootWorldSurfaces.profile(FloorBlocks.MAGNETIC_FLOOR.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.EMPTY_OR_LOCAL,
                BootWorldSurfaces.profile(FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState()));
    }

    @Test
    void unknownCallbackDomainIsExplicitlyUnsupported() {
        assertEquals(BootWorldSurfaces.CallbackProfile.UNSUPPORTED,
                BootWorldSurfaces.profile(Blocks.TORCH.getDefaultState()));
    }

    @Test
    void customSubclassThatCouldOverrideNativeCollisionIsRejectedBeforeCallback() {
        assertEquals(BootWorldSurfaces.CallbackProfile.UNSUPPORTED,
                BootWorldSurfaces.profile(new UntrustedWall().getDefaultState()));
    }

    @Test
    void doubleSidedFloorRunsTheActualNativeFullBlockCollisionCallback(@TempDir Path tempDir) {
        try (BootWorldFixture fixture = BootWorldFixture.open(tempDir, UUID.randomUUID())) {
            BlockPos cell = new BlockPos(5, 70, 5);
            IBlockState state = FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getDefaultState();
            Aabb bounds = new Aabb(new Vec3d(5.0, 70.0, 5.0), new Vec3d(6.0, 71.0, 6.0));

            List<BootWorldSurfaces.Primitive> actual = BootWorldSurfaces.collectNativeCell(
                    fixture.world(), fixture.actor(),
                    new MagneticBlockReader.Observation(cell, state, false, fixture.world().getTotalWorldTime()),
                    bounds, 4);

            assertEquals(1, actual.size());
            assertEquals(cell, actual.getFirst().cell());
            assertEquals(0, actual.getFirst().ordinal());
            assertEquals(5.0, actual.getFirst().rawBounds().min().x());
            assertEquals(70.0, actual.getFirst().rawBounds().min().y());
            assertEquals(5.0, actual.getFirst().rawBounds().min().z());
            assertEquals(6.0, actual.getFirst().rawBounds().max().x());
            assertEquals(71.0, actual.getFirst().rawBounds().max().y());
            assertEquals(6.0, actual.getFirst().rawBounds().max().z());
        }
    }

    private static final class UntrustedWall extends BlockWall {
        private UntrustedWall() {
            super(Blocks.COBBLESTONE);
        }

        @Override
        public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos, AxisAlignedBB query,
                                          List<AxisAlignedBB> boxes, Entity actor, boolean actualState) {
            throw new AssertionError("collector must reject this callback before invocation");
        }
    }
}
