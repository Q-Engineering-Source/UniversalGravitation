package wi.unigrav.core.floor;

import java.util.Objects;

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

/**
 * Common six-facing physical block for the built-in floor registrations.
 *
 * <p>The {@linkplain #kind() kind} is declarative only in this milestone. It
 * deliberately does not provide a field, magnetic filtering, or a player
 * collider; those require their own server-authoritative runtime paths.</p>
 */
public final class DirectionalFloorBlock extends BlockDirectional {

    /** Distinguishes the later gravity and magnetic runtime integrations. */
    public enum Kind {
        GRAVITY,
        MAGNETIC
    }

    private final Kind kind;

    public DirectionalFloorBlock(Kind kind) {
        super(Material.IRON);
        this.kind = Objects.requireNonNull(kind, "kind");
        setDefaultState(blockState.getBaseState().withProperty(FACING, EnumFacing.UP));
        setCreativeTab(CreativeTabs.BUILDING_BLOCKS);
        setHardness(5.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
    }

    public Kind kind() {
        return kind;
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
