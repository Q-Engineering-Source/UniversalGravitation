package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalNativeBoundary;
import wi.unigrav.core.player.InstalledFreeFall;

@Mixin(EntityLivingBase.class)
abstract class LivingCardinalNativeBoundaryMixin {
    @Unique private static final ThreadLocal<ArrayDeque<CardinalNativeBoundary.Invocation>> unigrav$landingCalls =
            ThreadLocal.withInitial(ArrayDeque::new);
    @Unique private CardinalNativeBoundary.Invocation unigrav$currentLanding() {
        return unigrav$landingCalls.get().getFirst();
    }
    @WrapMethod(method="updateFallState(DZLnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;)V",require=1,allow=1)
    private void unigrav$landing(double y,boolean ground,IBlockState state,BlockPos pos,Operation<Void> original) {
        var call=CardinalNativeBoundary.enter((Entity)(Object)this,"EntityLivingBase.updateFallState");
        var stack=unigrav$landingCalls.get();stack.push(call);
        try {original.call(y,ground,state,pos);call.check();}
        finally {try{call.close();}finally{stack.pop();if(stack.isEmpty())unigrav$landingCalls.remove();}}
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/block/state/IBlockState;getBlock()Lnet/minecraft/block/Block;"),require=2,allow=2)
    private Block unigrav$fallBlock(IBlockState state,Operation<Block> original) {
        Block result=unigrav$currentLanding().external("fall getBlock",()->original.call(state));
        InstalledFreeFall.checkpoint((Entity)(Object)this);return result;
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;isAir(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;)Z"),require=1,allow=1)
    private boolean unigrav$air(Block block,IBlockState state,IBlockAccess world,BlockPos pos,Operation<Boolean> original) {
        boolean result=unigrav$currentLanding().external("isAir",()->original.call(block,state,world,pos));
        InstalledFreeFall.checkpoint((Entity)(Object)this);return result;
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;addLandingEffects(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/world/WorldServer;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/entity/EntityLivingBase;I)Z"),require=1,allow=1)
    private boolean unigrav$effects(Block block,IBlockState state,WorldServer world,BlockPos pos,IBlockState source,EntityLivingBase actor,int count,Operation<Boolean> original) {
        boolean result=unigrav$currentLanding().external("addLandingEffects",()->original.call(block,state,world,pos,source,actor,count));
        InstalledFreeFall.checkpoint(actor);return result;
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/world/WorldServer;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDIDDDD[I)V"),require=1,allow=1)
    private void unigrav$particles(WorldServer world,EnumParticleTypes type,double x,double y,double z,int count,double xOffset,double yOffset,double zOffset,double speed,int[] args,Operation<Void> original) {
        InstalledFreeFall.checkpoint((Entity)(Object)this);
        unigrav$currentLanding().external("spawnParticle",()->original.call(world,type,x,y,z,count,xOffset,yOffset,zOffset,speed,args));
        InstalledFreeFall.checkpoint((Entity)(Object)this);
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/entity/EntityLivingBase;isInWater()Z"),require=1,allow=1)
    private boolean unigrav$wet(EntityLivingBase actor,Operation<Boolean> original) {
        boolean result=unigrav$currentLanding().external("isInWater",()->original.call(actor));
        InstalledFreeFall.checkpoint(actor);return result;
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/entity/EntityLivingBase;handleWaterMovement()Z"),require=1,allow=1)
    private boolean unigrav$water(EntityLivingBase actor,Operation<Boolean> original) {
        boolean result=unigrav$currentLanding().internal("handleWaterMovement",()->original.call(actor));
        InstalledFreeFall.checkpoint(actor);return result;
    }}