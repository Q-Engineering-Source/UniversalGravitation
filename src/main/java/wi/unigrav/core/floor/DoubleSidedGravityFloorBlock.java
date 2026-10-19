package wi.unigrav.core.floor;

import java.util.List;
import java.util.Objects;

import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Solid floor with two attracting faces selected by an unoriented axis. */
public final class DoubleSidedGravityFloorBlock extends DirectionalFloorBlock {
    public static final PropertyEnum<EnumFacing.Axis> AXIS =
            PropertyEnum.create("axis", EnumFacing.Axis.class);

    public DoubleSidedGravityFloorBlock() {
        super(Kind.DOUBLE_SIDED_GRAVITY);
    }

    // Block calls this before the parent constructor assigns kind.
    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, AXIS);
    }

    @Override
    protected IBlockState initialState(IBlockState baseState) {
        return baseState.withProperty(AXIS, EnumFacing.Axis.Y);
    }

    @Override
    List<EnumFacing> emissionFaces(IBlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.getBlock() != this) {
            throw new IllegalArgumentException("floor state must belong to this block");
        }
        return switch (state.getValue(AXIS)) {
            case X -> List.of(EnumFacing.WEST, EnumFacing.EAST);
            case Y -> List.of(EnumFacing.DOWN, EnumFacing.UP);
            case Z -> List.of(EnumFacing.NORTH, EnumFacing.SOUTH);
        };
    }

    /** Decode every legacy face metadata; canonical writes use the negative face. */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(AXIS, EnumFacing.byIndex(meta).getAxis());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return emissionFaces(state).getFirst().getIndex();
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer) {
        return getDefaultState().withProperty(AXIS, facing.getAxis());
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rotation) {
        return state.withProperty(AXIS, rotation.rotate(emissionFaces(state).getFirst()).getAxis());
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirror) {
        Objects.requireNonNull(mirror, "mirror");
        emissionFaces(state);
        return state;
    }
}
