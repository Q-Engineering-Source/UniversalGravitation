package wi.unigrav.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerPhysicsServer;

/** Preserve native additive velocity as a single pending physical delta, including entity pushes. */
@Mixin(Entity.class)
abstract class EntityVelocityPhysicsMixin {
    @Inject(method = "addVelocity(DDD)V", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void unigrav$nativeVelocity(double x, double y, double z, CallbackInfo callback) {
        Entity actor = (Entity) (Object) this;
        if (PlayerPhysicsServer.INSTANCE.captureNativeVelocityDelta(actor, new Vec3(x, y, z))) {
            actor.isAirBorne = true; // The remaining native addVelocity side effect.
            callback.cancel();
        }
    }
}
