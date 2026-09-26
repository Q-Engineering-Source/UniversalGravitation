package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockSlime;
import net.minecraft.entity.Entity;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.CardinalBlockMotion;

/** Keep native bounce/sneak policy and tangent slowdown without rotating the stored entity velocity. */
@Mixin(BlockSlime.class)
abstract class BlockSlimePhysicsMixin {
    @WrapOperation(method = {"onLanded", "onEntityWalk"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionY:D", opcode = Opcodes.GETFIELD), require = 5, allow = 5)
    private double unigrav$readNormal(Entity actor, Operation<Double> original) {
        return CardinalBlockMotion.active(actor) ? CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.Y) : original.call(actor);
    }

    @WrapOperation(method = "onLanded", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionY:D", opcode = Opcodes.PUTFIELD), require = 2, allow = 2)
    private void unigrav$writeNormal(Entity actor, double value, Operation<Void> original) {
        if (CardinalBlockMotion.active(actor)) CardinalBlockMotion.write(actor, CardinalBlockMotion.Axis.Y, value);
        else original.call(actor, value);
    }

    @WrapOperation(method = "onEntityWalk", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionX:D", opcode = Opcodes.GETFIELD), require = 1, allow = 1)
    private double unigrav$readRight(Entity actor, Operation<Double> original) {
        return CardinalBlockMotion.active(actor) ? CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.X) : original.call(actor);
    }

    @WrapOperation(method = "onEntityWalk", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionX:D", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$writeRight(Entity actor, double value, Operation<Void> original) {
        if (CardinalBlockMotion.active(actor)) CardinalBlockMotion.write(actor, CardinalBlockMotion.Axis.X, value);
        else original.call(actor, value);
    }

    @WrapOperation(method = "onEntityWalk", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionZ:D", opcode = Opcodes.GETFIELD), require = 1, allow = 1)
    private double unigrav$readForward(Entity actor, Operation<Double> original) {
        return CardinalBlockMotion.active(actor) ? CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.Z) : original.call(actor);
    }

    @WrapOperation(method = "onEntityWalk", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;motionZ:D", opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$writeForward(Entity actor, double value, Operation<Void> original) {
        if (CardinalBlockMotion.active(actor)) CardinalBlockMotion.write(actor, CardinalBlockMotion.Axis.Z, value);
        else original.call(actor, value);
    }
}
