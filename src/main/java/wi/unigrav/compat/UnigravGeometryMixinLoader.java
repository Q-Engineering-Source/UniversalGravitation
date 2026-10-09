package wi.unigrav.compat;

import java.util.List;
import zone.rong.mixinbooter.ILateMixinLoader;

/** Plain mod targets are selected after Forge adds their jars, outside the excluded Mixin package. */
public final class UnigravGeometryMixinLoader implements ILateMixinLoader {
    @Override public List<String> getMixinConfigs() {
        return List.of("mixins.unigrav.geometry_compat.json");
    }
}
