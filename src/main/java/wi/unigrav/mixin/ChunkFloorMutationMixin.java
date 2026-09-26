package wi.unigrav.mixin;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import wi.unigrav.core.floor.FloorWorldFields;

/**
 * Observes every ordinary Chunk block-state mutation return without changing
 * its result. The world adapter later rereads final storage after snapshot or
 * restore work has ended; neither the requested state nor this return value is
 * treated as authoritative block state.
 */
@Mixin(Chunk.class)
abstract class ChunkFloorMutationMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Lnet/minecraft/block/state/IBlockState;",
            at = @At("RETURN"))
    private void ugfloor$markFinalStateDirty(BlockPos position, IBlockState requestedState,
                                              CallbackInfoReturnable<IBlockState> callback) {
        FloorWorldFields.markDirty((Chunk) (Object) this, position);
    }
}
