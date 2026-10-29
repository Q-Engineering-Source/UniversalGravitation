package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wi.unigrav.core.player.PlayerPosePersistence;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(Entity.class)
abstract class PlayerPosePersistenceMixin implements PlayerPosePersistence.Carrier {
    @Unique private volatile PlayerPosePersistence.RetainedPose unigrav$retainedSavePose;
    @Unique private volatile PlayerPosePersistence.LoadedPose unigrav$loadedPose;
    @Unique private final AtomicLong unigrav$poseHistoryGeneration = new AtomicLong();
    @Unique private final AtomicLong unigrav$poseExternalGeneration = new AtomicLong();

    @Override public PlayerPosePersistence.LoadedPose unigrav$loadedPose() { return unigrav$loadedPose; }
    @Override public void unigrav$loadedPose(PlayerPosePersistence.LoadedPose pose) { unigrav$loadedPose = pose; }
    @Override public long unigrav$poseHistoryGeneration() { return unigrav$poseHistoryGeneration.get(); }
    @Override public long unigrav$poseExternalGeneration() { return unigrav$poseExternalGeneration.get(); }
    @Override public void unigrav$invalidateExternalPoseHistory() {
        if (unigrav$poseExternalGeneration != null) unigrav$poseExternalGeneration.updateAndGet(Math::incrementExact);
        unigrav$invalidatePoseHistory();
    }
    @Override public void unigrav$invalidatePoseHistory() {
        // The Entity constructor may call setters before injected field initialization. No loaded history exists then.
        if (unigrav$poseHistoryGeneration != null) unigrav$poseHistoryGeneration.updateAndGet(Math::incrementExact);
        unigrav$loadedPose = null;
        unigrav$retainedSavePose = null;
    }

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

    @Inject(method = "setPosition(DDD)V", at = @At("HEAD"))
    private void unigrav$observeLoadPosition(double x, double y, double z, CallbackInfo callback) {
        if ((Object) this instanceof EntityPlayerMP player) PlayerPosePersistence.positionChanged(player, x, y, z);
    }

    @Inject(method = {"setPositionAndRotation(DDDFF)V", "setLocationAndAngles(DDDFF)V"}, at = @At("HEAD"))
    private void unigrav$observeLoadPositionAndRotation(double x, double y, double z,
            float yaw, float pitch, CallbackInfo callback) {
        if ((Object) this instanceof EntityPlayerMP player
                && (player.posX != x || player.posY != y || player.posZ != z)) PlayerPosePersistence.invalidate(player);
    }

    @WrapMethod(method = "move(Lnet/minecraft/entity/MoverType;DDD)V")
    private void unigrav$observeLoadMovement(MoverType type, double x, double y, double z, Operation<Void> original) {
        if (!((Object) this instanceof EntityPlayerMP player)) { original.call(type, x, y, z); return; }
        double beforeX = player.posX, beforeY = player.posY, beforeZ = player.posZ;
        boolean complete = false;
        try { original.call(type, x, y, z); complete = true; }
        finally {
            // Grounded gravity requests a nonzero move even when collision leaves the player at the same position.
            if (!complete || beforeX != player.posX || beforeY != player.posY || beforeZ != player.posZ)
                PlayerPosePersistence.invalidate(player);
        }
    }

    @Inject(method = "addVelocity(DDD)V", at = @At("HEAD"))
    private void unigrav$observeLoadImpulse(double x, double y, double z, CallbackInfo callback) {
        if ((Object) this instanceof EntityPlayerMP player && (x != 0 || y != 0 || z != 0)) PlayerPosePersistence.invalidate(player);
    }

    @Inject(method = "setDead()V", at = @At("HEAD"))
    private void unigrav$invalidateLoadedLife(CallbackInfo callback) {
        if ((Object) this instanceof EntityPlayerMP player) PlayerPosePersistence.invalidate(player);
    }

    @WrapMethod(method = "readFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)V")
    private void unigrav$readPhysicalPose(NBTTagCompound nbt, Operation<Void> original) {
        if ((Object) this instanceof EntityPlayerMP player)
            PlayerPosePersistence.readCycle(player, nbt, () -> original.call(nbt));
        else original.call(nbt);
    }

    @WrapOperation(method = "readFromNBT", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/Entity;setPosition(DDD)V"), require = 2, allow = 2)
    private void unigrav$nativeLoadPosition(Entity actor, double x, double y, double z, Operation<Void> original) {
        if (actor instanceof EntityPlayerMP player)
            PlayerPosePersistence.nativeLoadPosition(player, x, y, z, () -> original.call(actor, x, y, z));
        else original.call(actor, x, y, z);
    }

    @WrapMethod(method = "writeToNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;")
    private NBTTagCompound unigrav$writePhysicalPose(NBTTagCompound nbt, Operation<NBTTagCompound> original) {
        NBTTagCompound result = original.call(nbt);
        if ((Object) this instanceof EntityPlayerMP player) PlayerPosePersistence.write(player, result);
        return result;
    }
}
