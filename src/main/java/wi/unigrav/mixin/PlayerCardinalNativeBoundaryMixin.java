package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalNativeBoundary;

@Mixin(EntityPlayerMP.class)
abstract class PlayerCardinalNativeBoundaryMixin {
    @Unique private static final ThreadLocal<ArrayDeque<CardinalNativeBoundary.Invocation>> unigrav$fallCalls=
            ThreadLocal.withInitial(ArrayDeque::new);
    @WrapMethod(method="handleFalling(DZ)V",require=1,allow=1)
    private void unigrav$fall(double delta,boolean grounded,Operation<Void> original) {
        var call=CardinalNativeBoundary.enter((Entity)(Object)this,"EntityPlayerMP.handleFalling");
        var stack=unigrav$fallCalls.get();stack.push(call);
        try {original.call(delta,grounded);call.check();}
        finally {try {call.close();} finally {stack.pop();if(stack.isEmpty())unigrav$fallCalls.remove();}}
    }
    @WrapOperation(method="handleFalling",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"),require=2,allow=2)
    private IBlockState unigrav$state(World world,BlockPos pos,Operation<IBlockState> original) {
        return unigrav$fallCalls.get().getFirst().external("getBlockState",()->original.call(world,pos));
    }
    @WrapOperation(method="handleFalling",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;isAir(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)Z"),require=1,allow=1)
    private boolean unigrav$air(Block block,IBlockState state,IBlockAccess world,BlockPos pos,Operation<Boolean> original) {
        return unigrav$fallCalls.get().getFirst().external("isAir",()->original.call(block,state,world,pos));
    }
}