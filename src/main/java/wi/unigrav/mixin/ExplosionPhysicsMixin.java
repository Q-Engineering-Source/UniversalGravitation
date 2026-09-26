package wi.unigrav.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.world.Explosion;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerPhysicsServer;

/** Capture only the three native additive writes; retain damage, resistance, visibility and explosion effects. */
@Mixin(Explosion.class)
abstract class ExplosionPhysicsMixin {
    @Redirect(method = "doExplosionA", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;motionX:D",
            opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$impulseX(Entity actor, double next) {
        if (!PlayerPhysicsServer.INSTANCE.captureNativeVelocityDelta(actor, new Vec3(next - actor.motionX, 0, 0)))
            actor.motionX = next;
    }

    @Redirect(method = "doExplosionA", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;motionY:D",
            opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$impulseY(Entity actor, double next) {
        if (!PlayerPhysicsServer.INSTANCE.captureNativeVelocityDelta(actor, new Vec3(0, next - actor.motionY, 0)))
            actor.motionY = next;
    }

    @Redirect(method = "doExplosionA", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;motionZ:D",
            opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$impulseZ(Entity actor, double next) {
        if (!PlayerPhysicsServer.INSTANCE.captureNativeVelocityDelta(actor, new Vec3(0, 0, next - actor.motionZ)))
            actor.motionZ = next;
    }
}
