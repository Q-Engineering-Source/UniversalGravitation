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
import wi.unigrav.core.player.ProneBodyProducer;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.AquaCardinalPoseClearance;
import com.fuzs.aquaacrobatics.entity.Pose;
import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import java.util.Optional;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.UnigravMod;
import net.minecraft.world.World;
import com.mojang.authlib.GameProfile;

/** Uses Aqua's verified merged methods; call-site injection must outrank their merge priority. */
@Mixin(value = EntityPlayer.class, priority = 1001)
abstract class AquaPlayerGeometryMixin implements NativePoseEyeProducer.OwnedStandingMirror, NativeBodyProfileProducer,
        ProneBodyProducer {
    @Shadow(remap = false) private float playerEyeHeight;
    @Shadow(remap = false) private float previousEyeHeight;
    @Shadow(remap = false) private float findEyeScaleFactor() { throw new AssertionError(); }
    @Shadow(remap = false) private void updateEyeHeight() { throw new AssertionError(); }
    @Unique private long unigrav$eyeProfileRevision;
    @Unique private float unigrav$constructedEyeHeight;
    @Unique private float unigrav$constructedBodyHeight;
    @Unique private float unigrav$standingEyeMirror = Float.NaN;

    @Inject(method = "<init>(Lnet/minecraft/world/World;Lcom/mojang/authlib/GameProfile;)V",
            at = @At("RETURN"), require = 1, allow = 1)
    private void unigrav$captureStandingEyeSource(World world, GameProfile profile, CallbackInfo callback) {
        EntityPlayer actor = (EntityPlayer) (Object) this;
        unigrav$constructedEyeHeight = actor.eyeHeight;
        unigrav$constructedBodyHeight = actor.height;
    }

    @Unique private Optional<PlayerPhysicalSnapshot> unigrav$ownedBody() {
        return UnigravMod.proxy == null ? Optional.empty()
                : UnigravMod.proxy.ownedPhysicalBody((EntityPlayer) (Object) this);
    }

    @Unique private Optional<PlayerPhysicalSnapshot> unigrav$ownedProneBody() {
        return unigrav$ownedBody().filter(snapshot -> snapshot.placement().shape()
                instanceof PlayerBodyShape.FreeCapsule free && free.pronePose().isPresent());
    }

    @Override public Optional<ProneBodyProducer.Input> unigrav$proneBodyInput() {
        if (!(unigrav$constructedBodyHeight > 0)) return Optional.empty();
        var body = unigrav$nativeBodyProfile();
        if (body.isEmpty()) return Optional.empty();
        var aqua = (IPlayerResizeable) this;
        // Aqua's eye scale is an independent producer. A physical height factor cannot
        // substitute for it; the constructor raw eye is independent of the short-pose cache.
        float standingEye = unigrav$constructedEyeHeight * findEyeScaleFactor();
        return Optional.of(new ProneBodyProducer.Input(standingEye, unigrav$constructedEyeHeight,
                aqua.isForcingCrawling(), aqua.getEyesInWaterPlayer()));
    }

    @Override public void unigrav$installStandingEye(NativePoseEyeProducer.StandingEyeWrite write) {
        EntityPlayer actor = (EntityPlayer) (Object) this;
        write.requireCurrent(actor);
        actor.eyeHeight = write.rawEye();
        previousEyeHeight = actor.eyeHeight;
        unigrav$standingEyeMirror = write.effectiveEye();
        unigrav$eyeProfileRevision = Math.incrementExact(unigrav$eyeProfileRevision);
        write.requireCurrent(actor);
    }

    @Override public void unigrav$completeNativeEyeWrite() {
        // Aqua retains its raw-eye/previous-eye condition and normal cache producer.
        updateEyeHeight();
    }

    @Inject(method = "recalculateEyeHeight()V", at = @At("RETURN"), remap = false, require = 1, allow = 1)
    private void unigrav$eyeProfileRecalculated(CallbackInfo callback) {
        unigrav$eyeProfileRevision = Math.incrementExact(unigrav$eyeProfileRevision);
    }

    @Override public Optional<NativePoseEyeProducer.Profile> unigrav$nativePoseEyeProfile() {
        if (Float.isFinite(unigrav$standingEyeMirror) && unigrav$ownedBody().isPresent())
            return Optional.of(new NativePoseEyeProducer.Profile(unigrav$eyeProfileRevision, unigrav$standingEyeMirror, 1));
        var prone = unigrav$ownedProneBody();
        if (prone.isPresent()) return Optional.of(new NativePoseEyeProducer.Profile(unigrav$eyeProfileRevision,
                (float) prone.orElseThrow().placement().shape().dimensions().eyeHeight(), 1));
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
        if (unigrav$ownedProneBody().isPresent()) return;
        if (UnigravMod.proxy == null) original.call();
        else UnigravMod.proxy.runNativePose((EntityPlayer) (Object) this, () -> original.call());
    }

    @WrapMethod(method = "getEyeHeight()F", require = 1, allow = 1)
    private float unigrav$proneEye(Operation<Float> original) {
        if (Float.isFinite(unigrav$standingEyeMirror)) {
            if (unigrav$ownedBody().isPresent()) return unigrav$standingEyeMirror;
            unigrav$standingEyeMirror = Float.NaN;
        }
        var prone = unigrav$ownedProneBody();
        return prone.isPresent() ? (float) prone.orElseThrow().placement().shape().dimensions().eyeHeight() : original.call();
    }

    @WrapMethod(method = "recalculateEyeHeight()V", remap = false, require = 1, allow = 1)
    private void unigrav$proneEyeCache(Operation<Void> original) {
        var prone = unigrav$ownedProneBody();
        if (prone.isEmpty()) {
            unigrav$standingEyeMirror = Float.NaN;
            original.call(); return;
        }
        playerEyeHeight = (float) prone.orElseThrow().placement().shape().dimensions().eyeHeight();
        previousEyeHeight = ((EntityPlayer) (Object) this).eyeHeight;
        unigrav$eyeProfileRevision = Math.incrementExact(unigrav$eyeProfileRevision);
    }

    @WrapMethod(method = "updatePose()V", remap = false, require = 1, allow = 1)
    private void unigrav$mirrorAcceptedPronePose(Operation<Void> original) {
        if (unigrav$ownedProneBody().isEmpty()) { original.call(); return; }
        ((IPlayerResizeable) this).setPose(Pose.SWIMMING);
    }

    @WrapMethod(method = "setPose(Lcom/fuzs/aquaacrobatics/entity/Pose;)V", remap = false, require = 1, allow = 1)
    private void unigrav$admitSwimMirrorOnly(Pose pose, Operation<Void> original) {
        if (pose == Pose.SWIMMING && unigrav$ownedBody().isPresent() && unigrav$ownedProneBody().isEmpty()) return;
        original.call(pose);
    }

    @WrapMethod(method = "updateSwimming()V", remap = false, require = 1, allow = 1)
    private void unigrav$mirrorAcceptedSwimming(Operation<Void> original) {
        var accepted = unigrav$ownedBody();
        if (accepted.isEmpty()) { original.call(); return; }
        boolean swimming = accepted.orElseThrow().placement().shape() instanceof PlayerBodyShape.FreeCapsule free
                && free.pronePose().filter(pose -> pose.kind() == PlayerBodyShape.PronePose.Kind.SWIMMING).isPresent();
        ((IPlayerResizeable) this).setSwimming(swimming);
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
