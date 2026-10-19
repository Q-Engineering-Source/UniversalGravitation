package wi.unigrav.core.floor;

import java.util.Objects;
import java.util.List;

import net.minecraft.block.BlockDirectional;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Common six-facing physical block for the built-in floor registrations. */
public class DirectionalFloorBlock extends BlockDirectional {

    /** Distinguishes the later gravity and magnetic runtime integrations. */
    public enum Kind {
        GRAVITY,
        MAGNETIC,
        DOUBLE_SIDED_GRAVITY
    }

    private final Kind kind;

    public DirectionalFloorBlock(Kind kind) {
        super(Material.IRON);
        this.kind = Objects.requireNonNull(kind, "kind");
        setDefaultState(initialState(blockState.getBaseState()));
        setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
        setHardness(5.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
    }

    protected IBlockState initialState(IBlockState baseState) {
        return baseState.withProperty(FACING, EnumFacing.UP);
    }

    public Kind kind() {
        return kind;
    }

    /** The designated outward face for single-sided floors. */
    List<EnumFacing> emissionFaces(IBlockState state) {
        Objects.requireNonNull(state, "state");
        if (state.getBlock() != this) {
            throw new IllegalArgumentException("floor state must belong to this block");
        }
        EnumFacing facing = state.getValue(FACING);
        return List.of(facing);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.byIndex(meta));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getIndex();
    }

    /**
     * The clicked placement face is the block's outward designated top face.
     */
    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, facing);
    }

    @Override
    public IBlockState withRotation(IBlockState state, Rotation rotation) {
        return state.withProperty(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public IBlockState withMirror(IBlockState state, Mirror mirror) {
        return withRotation(state, mirror.toRotation(state.getValue(FACING)));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, new IProperty<?>[] { FACING });
    }
}
