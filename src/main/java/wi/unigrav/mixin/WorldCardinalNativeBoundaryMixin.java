package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.IBlockAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalNativeBoundary;

/** Guards the existing native water algorithm without replacing its scan or callbacks. */
@Mixin(World.class)
abstract class WorldCardinalNativeBoundaryMixin {
    @Unique private static final ThreadLocal<ArrayDeque<unigrav$WaterCall>> unigrav$waterCalls =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final class unigrav$WaterCall {
        final World world;
        final CardinalNativeBoundary.Invocation invocation;
        BlockPos.PooledMutableBlockPos pooled;
        boolean released;
        unigrav$WaterCall(World world,Entity actor) {
            this.world=world;invocation=CardinalNativeBoundary.enter(actor,"World.handleMaterialAcceleration");
        }
    }
    @Unique private unigrav$WaterCall unigrav$currentWater() {
        var call=unigrav$waterCalls.get().peek();
        if(call==null || call.world!=(Object)this)throw new IllegalStateException("native water invocation missing");
        return call;
    }

    @WrapMethod(method="handleMaterialAcceleration(Lnet/minecraft/util/math/AxisAlignedBB;Lnet/minecraft/block/material/Material;Lnet/minecraft/entity/Entity;)Z",require=1,allow=1)
    private boolean unigrav$water(AxisAlignedBB bounds,Material material,Entity actor,Operation<Boolean> original) {
        var call=new unigrav$WaterCall((World)(Object)this,actor);
        var stack=unigrav$waterCalls.get();stack.push(call);
        Throwable primary=null;
        try {
            boolean result=original.call(bounds,material,actor);
            call.invocation.check();
            return result;
        } catch(RuntimeException|Error failure) {primary=failure;throw failure;}
        finally {
            try {
                if(call.invocation.active() && call.pooled!=null && !call.released) {
                    try {call.pooled.release();call.released=true;}
                    catch(RuntimeException|Error cleanup) {
                        if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;
                    }
                }
            } finally {
                try {call.invocation.close();}
                finally {stack.pop();if(stack.isEmpty())unigrav$waterCalls.remove();}
            }
        }
    }

    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;isAreaLoaded(IIIIIIZ)Z"),require=1,allow=1)
    private boolean unigrav$loaded(World world,int x1,int y1,int z1,int x2,int y2,int z2,boolean empty,Operation<Boolean> original) {
        return unigrav$currentWater().invocation.external("isAreaLoaded",()->original.call(world,x1,y1,z1,x2,y2,z2,empty));
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/util/math/BlockPos$PooledMutableBlockPos;retain()Lnet/minecraft/util/math/BlockPos$PooledMutableBlockPos;"),require=1,allow=1)
    private BlockPos.PooledMutableBlockPos unigrav$retain(Operation<BlockPos.PooledMutableBlockPos> original) {
        var call=unigrav$currentWater();call.invocation.check();
        var pooled=original.call();call.pooled=pooled;return pooled;
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/util/math/BlockPos$PooledMutableBlockPos;release()V"),require=1,allow=1)
    private void unigrav$release(BlockPos.PooledMutableBlockPos pooled,Operation<Void> original) {
        var call=unigrav$currentWater();original.call(pooled);
        if(call.pooled==pooled)call.released=true;
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;"),require=1,allow=1)
    private IBlockState unigrav$state(World world,BlockPos pos,Operation<IBlockState> original) {
        return unigrav$currentWater().invocation.external("getBlockState",()->original.call(world,pos));
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;isEntityInsideMaterial(Lnet/minecraft/world/IBlockAccess;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/entity/Entity;DLnet/minecraft/block/material/Material;Z)Ljava/lang/Boolean;"),require=1,allow=1)
    private Boolean unigrav$inside(Block block,IBlockAccess world,BlockPos pos,IBlockState state,Entity actor,double top,Material material,boolean testingHead,Operation<Boolean> original) {
        return unigrav$currentWater().invocation.external("isEntityInsideMaterial",()->original.call(block,world,pos,state,actor,top,material,testingHead));
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/block/Block;modifyAcceleration(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;"),require=2,allow=2)
    private Vec3d unigrav$acceleration(Block block,World world,BlockPos pos,Entity actor,Vec3d acceleration,Operation<Vec3d> original) {
        return unigrav$currentWater().invocation.external("modifyAcceleration",()->original.call(block,world,pos,actor,acceleration));
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="INVOKE",target="Lnet/minecraft/entity/Entity;isPushedByWater()Z"),require=1,allow=1)
    private boolean unigrav$pushed(Entity actor,Operation<Boolean> original) {
        return unigrav$currentWater().invocation.external("isPushedByWater",()->original.call(actor));
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="FIELD",target="Lnet/minecraft/entity/Entity;motionX:D",opcode=181),require=1,allow=1)
    private void unigrav$motionX(Entity actor,double value,Operation<Void> original) {
        unigrav$currentWater().invocation.check();original.call(actor,value);
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="FIELD",target="Lnet/minecraft/entity/Entity;motionY:D",opcode=181),require=1,allow=1)
    private void unigrav$motionY(Entity actor,double value,Operation<Void> original) {
        unigrav$currentWater().invocation.check();original.call(actor,value);
    }
    @WrapOperation(method="handleMaterialAcceleration",at=@At(value="FIELD",target="Lnet/minecraft/entity/Entity;motionZ:D",opcode=181),require=1,allow=1)
    private void unigrav$motionZ(Entity actor,double value,Operation<Void> original) {
        unigrav$currentWater().invocation.check();original.call(actor,value);
    }
}