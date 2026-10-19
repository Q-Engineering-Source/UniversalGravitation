package wi.unigrav.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.player.NativePoseEyeProducer;

/** Artemis 1.0.6 writes native geometry directly; Aqua's subclass calls these exact super methods. */
@Pseudo
@Mixin(targets = "com.artemis.artemislib.util.AttachAttributes", remap = false)
abstract class ArtemisPlayerGeometryMixin {
    @WrapMethod(method = "onPlayerTick(Lnet/minecraftforge/fml/common/gameevent/TickEvent$PlayerTickEvent;)V",
            remap = false, require = 1, allow = 1)
    private void unigrav$playerResize(TickEvent.PlayerTickEvent event, Operation<Void> original) {
        if (UnigravMod.proxy == null) original.call(event);
        else UnigravMod.proxy.runNativePose(event.player, () -> {
            float rawEye = event.player.eyeHeight;
            original.call(event);
            if (Float.floatToRawIntBits(rawEye) != Float.floatToRawIntBits(event.player.eyeHeight)
                    && event.player instanceof NativePoseEyeProducer.NativeWriteCompletion producer)
                producer.unigrav$completeNativeEyeWrite();
        });
    }

    @WrapMethod(method = "onLivingUpdate(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V",
            remap = false, require = 1, allow = 1)
    private void unigrav$livingResize(LivingEvent.LivingUpdateEvent event, Operation<Void> original) {
        if (UnigravMod.proxy == null || !(event.getEntityLiving() instanceof EntityPlayer player)) original.call(event);
        else UnigravMod.proxy.runNativePose(player, () -> {
            float rawEye = player.eyeHeight;
            original.call(event);
            if (Float.floatToRawIntBits(rawEye) != Float.floatToRawIntBits(player.eyeHeight)
                    && player instanceof NativePoseEyeProducer.NativeWriteCompletion producer)
                producer.unigrav$completeNativeEyeWrite();
        });
    }
}
