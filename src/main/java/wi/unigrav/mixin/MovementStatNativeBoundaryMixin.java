package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayDeque;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.stats.StatBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.InstalledMovementStat;

/** Exact native callsites; no-token calls retain the original native body and arguments. */
@Mixin(EntityPlayer.class)
abstract class MovementStatNativeBoundaryMixin {
    @Unique private static final ThreadLocal<ArrayDeque<InstalledMovementStat.NativeCall>> unigrav$statCalls =
            ThreadLocal.withInitial(ArrayDeque::new);
    @WrapMethod(method="addMovementStat(DDD)V",require=1,allow=1)
    private void unigrav$movement(double x,double y,double z,Operation<Void> original) {
        try (var call=InstalledMovementStat.enter((EntityPlayer)(Object)this)) {
            var stack=unigrav$statCalls.get(); stack.push(call);
            try { original.call(x,y,z); call.check(); }
            finally { stack.pop(); if(stack.isEmpty())unigrav$statCalls.remove(); }
        }
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isRiding()Z"),require=1,allow=1)
    private boolean unigrav$isRiding(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isRiding",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isInWater()Z"),require=1,allow=1)
    private boolean unigrav$isInWater(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isInWater",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isOnLadder()Z"),require=1,allow=1)
    private boolean unigrav$isOnLadder(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isOnLadder",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isSprinting()Z"),require=1,allow=1)
    private boolean unigrav$isSprinting(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isSprinting",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isSneaking()Z"),require=1,allow=1)
    private boolean unigrav$isSneaking(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isSneaking",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isElytraFlying()Z"),require=1,allow=1)
    private boolean unigrav$isElytraFlying(EntityPlayer actor,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isElytraFlying",()->original.call(actor));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;isInsideOfMaterial(Lnet/minecraft/block/material/Material;)Z"),require=1,allow=1)
    private boolean unigrav$inside(EntityPlayer actor,Material material,Operation<Boolean> original) {
        return unigrav$statCalls.get().getFirst().external("isInsideOfMaterial",()->original.call(actor,material));
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;addStat(Lnet/minecraft/stats/StatBase;I)V"),require=8,allow=8)
    private void unigrav$stat(EntityPlayer actor,StatBase stat,int value,Operation<Void> original) {
        unigrav$statCalls.get().getFirst().external("addStat",()->{original.call(actor,stat,value);});
    }
    @WrapOperation(method="addMovementStat(DDD)V",at=@At(value="INVOKE",target="Lnet/minecraft/entity/player/EntityPlayer;addExhaustion(F)V"),require=5,allow=5)
    private void unigrav$exhaustion(EntityPlayer actor,float value,Operation<Void> original) {
        unigrav$statCalls.get().getFirst().external("addExhaustion",()->{original.call(actor,value);});
    }
}
