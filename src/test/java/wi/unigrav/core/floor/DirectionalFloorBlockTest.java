package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.block.material.Material;
import net.minecraft.init.Bootstrap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import wi.unigrav.Reference;

class DirectionalFloorBlockTest {

    @BeforeAll
    static void initializeVanillaRegistries() {
        Bootstrap.register();
    }

    @Test
    void defaultStateAndNormalMetadataPersistEveryOutwardFace() {
        DirectionalFloorBlock block = new DirectionalFloorBlock(DirectionalFloorBlock.Kind.GRAVITY);
        assertEquals(DirectionalFloorBlock.Kind.GRAVITY, block.kind());
        assertEquals(EnumFacing.UP, block.getDefaultState().getValue(DirectionalFloorBlock.FACING));

        for (EnumFacing facing : EnumFacing.values()) {
            IBlockState state = block.getStateFromMeta(facing.getIndex());
            assertEquals(facing, state.getValue(DirectionalFloorBlock.FACING));
            assertEquals(facing.getIndex(), block.getMetaFromState(state));
            assertEquals(state, block.getStateFromMeta(block.getMetaFromState(state)));
        }
    }

    @Test
    void placementUsesTheClickedFaceAsTheOutwardTop() {
        DirectionalFloorBlock block = new DirectionalFloorBlock(DirectionalFloorBlock.Kind.MAGNETIC);
        for (EnumFacing placementFace : EnumFacing.values()) {
            IBlockState placed = block.getStateForPlacement(null, null, placementFace,
                    0.5F, 0.5F, 0.5F, 0, null);
            assertEquals(placementFace, placed.getValue(DirectionalFloorBlock.FACING));
        }
    }

    @Test
    void rotationsAndMirrorsPreserveTheNormalStateContract() {
        DirectionalFloorBlock block = new DirectionalFloorBlock(DirectionalFloorBlock.Kind.GRAVITY);
        assertEquals(EnumFacing.EAST, facingAfter(block, EnumFacing.NORTH, Rotation.CLOCKWISE_90));
        assertEquals(EnumFacing.SOUTH, facingAfter(block, EnumFacing.EAST, Rotation.CLOCKWISE_90));
        assertEquals(EnumFacing.WEST, facingAfter(block, EnumFacing.SOUTH, Rotation.CLOCKWISE_90));
        assertEquals(EnumFacing.NORTH, facingAfter(block, EnumFacing.WEST, Rotation.CLOCKWISE_90));
        assertEquals(EnumFacing.UP, facingAfter(block, EnumFacing.UP, Rotation.CLOCKWISE_90));
        assertEquals(EnumFacing.DOWN, facingAfter(block, EnumFacing.DOWN, Rotation.CLOCKWISE_90));

        assertEquals(EnumFacing.SOUTH, mirror(block, EnumFacing.NORTH, Mirror.LEFT_RIGHT));
        assertEquals(EnumFacing.NORTH, mirror(block, EnumFacing.SOUTH, Mirror.LEFT_RIGHT));
        assertEquals(EnumFacing.WEST, mirror(block, EnumFacing.EAST, Mirror.FRONT_BACK));
        assertEquals(EnumFacing.EAST, mirror(block, EnumFacing.WEST, Mirror.FRONT_BACK));
    }

    @Test
    void blocksAreConcreteIronFullCubesAndCarryTheTwoStableIds() {
        DirectionalFloorBlock gravity = FloorBlocks.GRAVITY_FLOOR;
        DirectionalFloorBlock magnetic = FloorBlocks.MAGNETIC_FLOOR;
        assertEquals(DirectionalFloorBlock.Kind.GRAVITY, gravity.kind());
        assertEquals(DirectionalFloorBlock.Kind.MAGNETIC, magnetic.kind());
        assertEquals(Reference.MOD_ID + ":gravity_floor", gravity.getRegistryName().toString());
        assertEquals(Reference.MOD_ID + ":magnetic_floor", magnetic.getRegistryName().toString());
        assertEquals(Material.IRON, gravity.getMaterial(gravity.getDefaultState()));
        assertTrue(gravity.isFullCube(gravity.getDefaultState()));
        assertTrue(gravity.isOpaqueCube(gravity.getDefaultState()));
    }

    private static EnumFacing facingAfter(DirectionalFloorBlock block, EnumFacing facing, Rotation rotation) {
        return block.withRotation(block.getDefaultState().withProperty(DirectionalFloorBlock.FACING, facing), rotation)
                .getValue(DirectionalFloorBlock.FACING);
    }

    private static EnumFacing mirror(DirectionalFloorBlock block, EnumFacing facing, Mirror mirror) {
        return block.withMirror(block.getDefaultState().withProperty(DirectionalFloorBlock.FACING, facing), mirror)
                .getValue(DirectionalFloorBlock.FACING);
    }
}
