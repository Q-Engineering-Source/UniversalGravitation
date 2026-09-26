package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalBlockMotion;

/** The native default landing stop applies to the current physical normal only inside UG's callback. */
@Mixin(Block.class)
abstract class BlockLandingPhysicsMixin {
    @WrapOperation(method = "onLanded", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;motionY:D",
            opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$normalStop(Entity actor, double value, Operation<Void> original) {
        if (CardinalBlockMotion.active(actor)) CardinalBlockMotion.write(actor, CardinalBlockMotion.Axis.Y, value);
        else original.call(actor, value);
    }
}
