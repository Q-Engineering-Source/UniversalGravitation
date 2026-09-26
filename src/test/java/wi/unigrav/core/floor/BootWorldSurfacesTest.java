package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

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
                BootWorldSurfaces.profile(Blocks.OAK_FENCE.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.NEIGHBOR_HALO,
                BootWorldSurfaces.profile(Blocks.COBBLESTONE_WALL.getDefaultState()));
        assertEquals(BootWorldSurfaces.CallbackProfile.EMPTY_OR_LOCAL,
                BootWorldSurfaces.profile(FloorBlocks.MAGNETIC_FLOOR.getDefaultState()));
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
