package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundEvent;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalNativeBoundary;
import wi.unigrav.core.player.InstalledFreeFall;

@Mixin(Entity.class)
abstract class EntityCardinalNativeBoundaryMixin {
    @Unique private static final ThreadLocal<ArrayDeque<CardinalNativeBoundary.Invocation>> unigrav$cardinalCalls =
            ThreadLocal.withInitial(ArrayDeque::new);
    @Unique private CardinalNativeBoundary.Invocation unigrav$currentCardinal() {
        return unigrav$cardinalCalls.get().getFirst();
    }
    @WrapMethod(method="handleWaterMovement()Z",require=1,allow=1)
    private boolean unigrav$guardWater(Operation<Boolean> original) {
        var call=CardinalNativeBoundary.enter((Entity)(Object)this,"Entity.handleWaterMovement");
        var stack=unigrav$cardinalCalls.get();stack.push(call);
        try {boolean result=original.call();call.check();return result;}
        finally {try {call.close();} finally {stack.pop();if(stack.isEmpty())unigrav$cardinalCalls.remove();}}
    }
    @WrapMethod(method="updateFallState(DZLnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;)V",require=1,allow=1)
    private void unigrav$guardFall(double y,boolean ground,IBlockState state,BlockPos pos,Operation<Void> original) {
        var call=CardinalNativeBoundary.enter((Entity)(Object)this,"Entity.updateFallState");
        var stack=unigrav$cardinalCalls.get();stack.push(call);
        try {original.call(y,ground,state,pos);call.check();}
        finally {try {call.close();} finally {stack.pop();if(stack.isEmpty())unigrav$cardinalCalls.remove();}}
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/block/state/IBlockState;getBlock()Lnet/minecraft/block/Block;"),require=1,allow=1)
    private Block unigrav$fallBlock(IBlockState state,Operation<Block> original) {
        Block result=unigrav$currentCardinal().external("fall getBlock",()->original.call(state));
        InstalledFreeFall.checkpoint((Entity)(Object)this);return result;
    }
    @WrapOperation(method="updateFallState",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;onFallenUpon(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/Entity;F)V"),require=1,allow=1)
    private void unigrav$fallen(Block block,World world,BlockPos pos,Entity actor,float distance,Operation<Void> original) {
        unigrav$currentCardinal().external("onFallenUpon",()->InstalledFreeFall.runFallenUpon(
                actor,distance,()->original.call(block,world,pos,actor,distance)));
        InstalledFreeFall.checkpoint(actor);
    }
    @WrapOperation(method={"updateFallState","handleWaterMovement"},at=@At(value="FIELD",target="Lnet/minecraft/entity/Entity;fallDistance:F",opcode=181),require=3,allow=3)
    private void unigrav$fallDistance(Entity actor,float value,Operation<Void> original) {
        unigrav$currentCardinal().check();InstalledFreeFall.checkpoint(actor);original.call(actor,value);
    }
    @WrapOperation(method="handleWaterMovement",at=@At(value="FIELD",target="Lnet/minecraft/entity/Entity;inWater:Z",opcode=181),require=3,allow=3)
    private void unigrav$inWater(Entity actor,boolean value,Operation<Void> original) {
        unigrav$currentCardinal().check();original.call(actor,value);
    }
    @WrapOperation(method="handleWaterMovement",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;doWaterSplashEffect()V"),require=1,allow=1)
    private void unigrav$splash(Entity actor,Operation<Void> original) {
        unigrav$currentCardinal().internal("doWaterSplashEffect",()->original.call(actor));
    }
    @WrapOperation(method="handleWaterMovement",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;extinguish()V"),require=1,allow=1)
    private void unigrav$extinguish(Entity actor,Operation<Void> original) {
        unigrav$currentCardinal().external("extinguish",()->original.call(actor));
    }
    @WrapOperation(method="handleWaterMovement",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;getRidingEntity()Lnet/minecraft/entity/Entity;"),require=1,allow=1)
    private Entity unigrav$riding(Entity actor,Operation<Entity> original) {
        return unigrav$currentCardinal().external("getRidingEntity",()->original.call(actor));
    }
    @WrapMethod(method="doWaterSplashEffect()V",require=1,allow=1)
    private void unigrav$guardSplash(Operation<Void> original) {
        var call=CardinalNativeBoundary.enter((Entity)(Object)this,"Entity.doWaterSplashEffect");
        var stack=unigrav$cardinalCalls.get();stack.push(call);
        try {original.call();call.check();}
        finally {try {call.close();} finally {stack.pop();if(stack.isEmpty())unigrav$cardinalCalls.remove();}}
    }
    @WrapOperation(method="doWaterSplashEffect",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V"),require=2,allow=2)
    private void unigrav$splashParticle(World world,EnumParticleTypes type,double x,double y,double z,double vx,double vy,double vz,int[] args,Operation<Void> original) {
        unigrav$currentCardinal().external("splash spawnParticle",()->original.call(world,type,x,y,z,vx,vy,vz,args));
    }
    @WrapOperation(method="doWaterSplashEffect",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;playSound(Lnet/minecraft/util/SoundEvent;FF)V"),require=1,allow=1)
    private void unigrav$splashSound(Entity actor,SoundEvent sound,float volume,float pitch,Operation<Void> original) {
        unigrav$currentCardinal().external("splash playSound",()->original.call(actor,sound,volume,pitch));
    }
    @WrapOperation(method="doWaterSplashEffect",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;getSplashSound()Lnet/minecraft/util/SoundEvent;"),require=1,allow=1)
    private SoundEvent unigrav$splashSoundType(Entity actor,Operation<SoundEvent> original) {
        return unigrav$currentCardinal().external("getSplashSound",()->original.call(actor));
    }
    @WrapOperation(method="doWaterSplashEffect",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;getControllingPassenger()Lnet/minecraft/entity/Entity;"),require=2,allow=2)
    private Entity unigrav$splashPassenger(Entity actor,Operation<Entity> original) {
        return unigrav$currentCardinal().external("getControllingPassenger",()->original.call(actor));
    }
    @WrapOperation(method="doWaterSplashEffect",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;isBeingRidden()Z"),require=1,allow=1)
    private boolean unigrav$splashRidden(Entity actor,Operation<Boolean> original) {
        return unigrav$currentCardinal().external("isBeingRidden",()->original.call(actor));
    }}