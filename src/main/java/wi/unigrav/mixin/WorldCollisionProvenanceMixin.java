package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.NativeCollisionProvenance;

/** Observe the existing collision invocation; do not replace its scan, geometry or Forge event. */
@Mixin(World.class)
abstract class WorldCollisionProvenanceMixin {
    @WrapMethod(method = "getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;", require = 1, allow = 1)
    private List<AxisAlignedBB> unigrav$nativeCollision(Entity actor, AxisAlignedBB query, Operation<List<AxisAlignedBB>> original) {
        if (!NativeCollisionProvenance.scoped()) return original.call(actor, query);
        try (var call = NativeCollisionProvenance.enterNative((World)(Object)this, actor, query)) {
            var result = original.call(actor, query); call.returned(result); return result;
        }
    }
    @WrapMethod(method = "getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;ZLjava/util/List;)Z", require = 1, allow = 1)
    private boolean unigrav$collector(Entity actor, AxisAlignedBB query, boolean testing, List<AxisAlignedBB> list, Operation<Boolean> original) {
        if (!NativeCollisionProvenance.scoped()) return original.call(actor, query, testing, list);
        try (var call = NativeCollisionProvenance.enterCollector((World)(Object)this, actor, query, list)) {
            return original.call(actor, query, testing, list);
        }
    }
    @WrapOperation(method = "getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;ZLjava/util/List;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"), require = 1, allow = 1)
    private IBlockState unigrav$collisionRead(World world, BlockPos position, Operation<IBlockState> original) {
        return NativeCollisionProvenance.read(world, position, () -> original.call(world, position));
    }
    @WrapOperation(method = "getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;ZLjava/util/List;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/block/state/IBlockState;addCollisionBoxToList(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/AxisAlignedBB;Ljava/util/List;Lnet/minecraft/entity/Entity;Z)V"), require = 1, allow = 1)
    private void unigrav$collisionProducer(IBlockState state, World world, BlockPos position, AxisAlignedBB query,
            List<AxisAlignedBB> list, Entity actor, boolean actualState, Operation<Void> original) {
        NativeCollisionProvenance.produce(state, world, position, query, list, actor, actualState,
                observed -> original.call(state, world, position, query, observed, actor, actualState));
    }
}
