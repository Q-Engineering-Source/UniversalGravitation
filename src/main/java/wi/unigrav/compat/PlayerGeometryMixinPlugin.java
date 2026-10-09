package wi.unigrav.compat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import wi.unigrav.UnigravMod;

/** Optional geometry hooks never load the third-party class during common-side Mixin selection. */
public final class PlayerGeometryMixinPlugin implements IMixinConfigPlugin {
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith(".AquaPlayerGeometryMixin"))
            return supports("com/fuzs/aquaacrobatics/core/mixin/EntityPlayerMixin.class", Set.of("recalculateSize()V",
                    "recalculateSize(Lcom/fuzs/aquaacrobatics/entity/EntitySize;Lcom/fuzs/aquaacrobatics/entity/EntitySize;)V"));
        if (mixinClassName.endsWith(".ArtemisPlayerGeometryMixin"))
            return supports("com/artemis/artemislib/util/AttachAttributes.class", Set.of(
                    "onPlayerTick(Lnet/minecraftforge/fml/common/gameevent/TickEvent$PlayerTickEvent;)V",
                    "onLivingUpdate(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingUpdateEvent;)V"));
        return true;
    }

    private boolean supports(String resource, Set<String> required) {
        try (InputStream source = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (source == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(source).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            Set<String> methods = new HashSet<>();
            node.methods.forEach(method -> methods.add(method.name + method.desc));
            if (methods.containsAll(required)) return true;
            UnigravMod.LOGGER.warn("UG geometry adapter unsupported ABI: {} requires {}", resource, required);
            return false;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot inspect optional geometry ABI " + resource, failure);
        }
    }

    @Override public void onLoad(String mixinPackage) { }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
