package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wi.unigrav.core.player.PlayerPosePersistence;

@Mixin(Entity.class)
abstract class PlayerPosePersistenceMixin implements PlayerPosePersistence.Carrier {
    @Unique private volatile PlayerPosePersistence.RetainedPose unigrav$retainedSavePose;

    @Override public PlayerPosePersistence.RetainedPose unigrav$retainedSavePose() {
        return unigrav$retainedSavePose;
    }
    @Override public void unigrav$retainedSavePose(PlayerPosePersistence.RetainedPose pose) {
        unigrav$retainedSavePose = pose;
    }

    // An away-and-back native mutation must not revive the last retired owner's pose.
    @Inject(method = {"setPosition(DDD)V", "setEntityBoundingBox(Lnet/minecraft/util/math/AxisAlignedBB;)V",
            "move(Lnet/minecraft/entity/MoverType;DDD)V", "setPositionAndRotation(DDDFF)V",
            "setLocationAndAngles(DDDFF)V", "addVelocity(DDD)V",
            "setSize(FF)V", "setRotation(FF)V", "setFlag(IZ)V",
            "readFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)V", "setDead()V"}, at = @At("HEAD"))
    private void unigrav$invalidateRetiredSavePose(CallbackInfo callback) {
        unigrav$retainedSavePose = null;
    }

    @WrapMethod(method = "writeToNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;")
    private NBTTagCompound unigrav$writePhysicalPose(NBTTagCompound nbt, Operation<NBTTagCompound> original) {
        NBTTagCompound result = original.call(nbt);
        if ((Object) this instanceof EntityPlayerMP player) PlayerPosePersistence.write(player, result);
        return result;
    }
}
