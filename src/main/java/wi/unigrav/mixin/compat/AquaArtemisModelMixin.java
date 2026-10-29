package wi.unigrav.mixin.compat;

import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import neofontrender.addons.flight.PlayerBodyRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraftforge.client.event.RenderLivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.client.PlayerRenderPoseMirror;
import wi.unigrav.core.player.AquaCardinalPoseClearance;

/** Keeps the complete accepted model while leaving Aqua's attributes and limb animation intact. */
@Pseudo
@Mixin(targets = "com.fuzs.aquaacrobatics.integration.artemislib.AttachAttributesFix", remap = false)
abstract class AquaArtemisModelMixin {
    @Unique private double unigrav$modelHeight = Double.NaN;

    @WrapMethod(method = "onEntityRenderPre(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            remap = false, require = 1, allow = 1)
    private void unigrav$acceptedModelScale(RenderLivingEvent.Pre<?> event, Operation<Void> original) {
        double previous = unigrav$modelHeight;
        unigrav$modelHeight = Double.NaN;
        try {
            if (event.getEntity() instanceof AbstractClientPlayer player) {
                var context = PlayerBodyRenderScope.current(player);
                boolean scoped = context != null && context.active() && context.pose().geometry != null;
                boolean accepted = scoped && (player == Minecraft.getMinecraft().player
                        ? UnigravMod.proxy != null && UnigravMod.proxy.ownedPhysicalBody(player).isPresent()
                        : PlayerRenderPoseMirror.sample(player, event.getPartialRenderTick())
                            .filter(pose -> pose.geometry != null).isPresent());
                if (accepted) unigrav$modelHeight = AquaCardinalPoseClearance.modelHeightScale(player);
            }
            original.call(event);
        } finally {
            unigrav$modelHeight = previous;
        }
    }

    @WrapOperation(method = "onEntityRenderPre(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            remap = false, slice = @Slice(from = @At(value = "FIELD",
                target = "Lcom/artemis/artemislib/util/attributes/ArtemisLibAttributes;ENTITY_HEIGHT:Lnet/minecraft/entity/ai/attributes/IAttribute;",
                remap = false)),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/ai/attributes/IAttributeInstance;getAttributeValue()D",
                remap = true, ordinal = 0), require = 1, allow = 1)
    private double unigrav$fullHeight(IAttributeInstance instance, Operation<Double> original) {
        return Double.isFinite(unigrav$modelHeight) ? unigrav$modelHeight : original.call(instance);
    }

    @WrapOperation(method = "onEntityRenderPre(Lnet/minecraftforge/client/event/RenderLivingEvent$Pre;)V",
            remap = false, at = @At(value = "INVOKE",
                target = "Lcom/fuzs/aquaacrobatics/entity/player/IPlayerResizeable;getSwimAnimation(F)F",
                remap = false), require = 1, allow = 1)
    private float unigrav$alreadyFullHeight(IPlayerResizeable player, float partialTicks, Operation<Float> original) {
        // This return value only gates Aqua's local height compensation, not the model animation.
        return Double.isFinite(unigrav$modelHeight) ? 0 : original.call(player, partialTicks);
    }
}
