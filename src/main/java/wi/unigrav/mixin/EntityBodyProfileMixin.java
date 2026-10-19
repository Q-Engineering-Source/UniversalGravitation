package wi.unigrav.mixin;

import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import wi.unigrav.core.player.NativeBodyProfileProducer;

/** Capture vanilla's actual constructor fields before virtual entity initialization can change pose. */
@Mixin(Entity.class)
abstract class EntityBodyProfileMixin implements NativeBodyProfileProducer {
    @Shadow public float width;
    @Shadow public float height;
    // No field initializer: a subclass constructor must not overwrite the constructor observation.
    @Unique private float unigrav$constructedBodyDiameter;
    @Unique private float unigrav$constructedBodyLength;

    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At(value = "FIELD",
            target = "Lnet/minecraft/entity/Entity;height:F", opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER, unsafe = true), require = 1, allow = 1)
    private void unigrav$captureConstructedBody(World world, CallbackInfo callback) {
        unigrav$constructedBodyDiameter = width;
        unigrav$constructedBodyLength = height;
    }

    @Override public Optional<NativeBodyProfileProducer.Profile> unigrav$nativeBodyProfile() {
        return Optional.of(new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR,
                unigrav$constructedBodyDiameter, unigrav$constructedBodyLength,
                unigrav$constructedBodyDiameter, unigrav$constructedBodyLength));
    }
}
