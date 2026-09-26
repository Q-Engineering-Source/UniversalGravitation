package wi.unigrav.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registers the remote-boost and common floor-mutation Mixin configuration
 * while LaunchWrapper is still loading vanilla and Forge classes. Cleanroom
 * supplies MixinBooter; this coremod contains no transformers of its own.
 */
@IFMLLoadingPlugin.Name("UnigravMixinLoader")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(2000)
public final class UnigravMixinLoader implements IFMLLoadingPlugin, IEarlyMixinLoader {

    private static final String MIXIN_CONFIG = "mixins.unigrav.remote_boost.json";

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList(MIXIN_CONFIG);
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
