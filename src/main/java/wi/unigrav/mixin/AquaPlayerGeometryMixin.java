package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import dev.crlhitbox.api.geometry.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wi.unigrav.core.player.NativePoseEyeProducer;
import wi.unigrav.core.player.NativeBodyProfileProducer;
import wi.unigrav.core.player.AquaCardinalPoseClearance;
import com.fuzs.aquaacrobatics.entity.Pose;
import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import java.util.Optional;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.UnigravMod;

/** Uses Aqua's verified merged methods; call-site injection must outrank their merge priority. */
@Mixin(value = EntityPlayer.class, priority = 1001)
abstract class AquaPlayerGeometryMixin implements NativePoseEyeProducer.NativeWriteCompletion, NativeBodyProfileProducer {
    @Shadow(remap = false) private float playerEyeHeight;
    @Shadow(remap = false) private float findEyeScaleFactor() { throw new AssertionError(); }
    @Shadow(remap = false) private void updateEyeHeight() { throw new AssertionError(); }
    @Unique private long unigrav$eyeProfileRevision;

    @Override public void unigrav$completeNativeEyeWrite() {
        // Aqua retains its raw-eye/previous-eye condition and normal cache producer.
        updateEyeHeight();
    }

    @Inject(method = "recalculateEyeHeight()V", at = @At("RETURN"), remap = false, require = 1, allow = 1)
    private void unigrav$eyeProfileRecalculated(CallbackInfo callback) {
        unigrav$eyeProfileRevision = Math.incrementExact(unigrav$eyeProfileRevision);
    }

    @Override public Optional<NativePoseEyeProducer.Profile> unigrav$nativePoseEyeProfile() {
        // These are Aqua's actual getter inputs, independent of restored UG collider dimensions.
        return Optional.of(new NativePoseEyeProducer.Profile(unigrav$eyeProfileRevision,
                playerEyeHeight, findEyeScaleFactor()));
    }

    @Override public Optional<NativeBodyProfileProducer.Profile> unigrav$nativeBodyProfile() {
        EntityPlayer actor = (EntityPlayer) (Object) this;
        // getSize applies Aqua's own T&B/Chiseled scaling once, without changing its current pose.
        var standing = ((IPlayerResizeable) actor).getSize(Pose.STANDING);
        return Optional.of(AquaCardinalPoseClearance.standingBodyProfile(actor, standing.width, standing.height));
    }

    @WrapMethod(method = "recalculateSize()V", remap = false, require = 1, allow = 1)
    private void unigrav$aquaResize(Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativePose((EntityPlayer) (Object) this, () -> original.call());
    }

    @WrapOperation(method = "recalculateSize(Lcom/fuzs/aquaacrobatics/entity/EntitySize;Lcom/fuzs/aquaacrobatics/entity/EntitySize;)V",
            remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/entity/player/EntityPlayer;move(Lnet/minecraft/entity/MoverType;DDD)V",
            remap = true), require = 1, allow = 1)
    private void unigrav$aquaGrowthMove(EntityPlayer actor, MoverType type, double x, double y, double z,
                                       Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call(actor, type, x, y, z);
        else UnigravMod.proxy.runPoseMove(actor, type, new Vec3d(x, y, z),
                bounded -> original.call(actor, type, bounded.x(), bounded.y(), bounded.z()));
    }

    @WrapOperation(method = "isPoseClear(Lcom/fuzs/aquaacrobatics/entity/Pose;)Z", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;getBoundingBox(Lcom/fuzs/aquaacrobatics/entity/Pose;)Lnet/minecraft/util/math/AxisAlignedBB;",
                    remap = false), require = 1, allow = 1)
    private AxisAlignedBB unigrav$aquaCardinalClearance(EntityPlayer actor, @Coerce Object candidatePose,
                                                        Operation<AxisAlignedBB> original) {
        AxisAlignedBB aquaCandidate = original.call(actor, candidatePose);
        if (UnigravMod.proxy == null) return aquaCandidate;
        return UnigravMod.proxy.aquaPoseClearanceBox(actor, aquaCandidate);
    }
}
