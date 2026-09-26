package wi.unigrav.core;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.entity.Entity;

/**
 * Class-keyed species data whose nearest registered superclass applies to a
 * subclass. This is deliberately one catalog so related species properties
 * cannot resolve through different inheritance paths.
 */
final class SpeciesPhysicsCatalog {

    record Entry(BodyPhysics.SpeciesPreset preset, double magneticResponse) {

        Entry {
            if (!Double.isFinite(magneticResponse) || magneticResponse < 0.0) {
                throw new IllegalArgumentException(
                        "magnetic response must be finite and non-negative");
            }
        }
    }

    private final Map<Class<? extends Entity>, Entry> entries = new IdentityHashMap<>();

    void replace(Class<? extends Entity> type, BodyPhysics.SpeciesPreset preset,
                 double magneticResponse) {
        entries.put(type, new Entry(preset, magneticResponse));
    }

    Entry nearest(Class<? extends Entity> type) {
        for (Class<?> current = type;
                current != null && Entity.class.isAssignableFrom(current);
                current = current.getSuperclass()) {
            Entry entry = entries.get(current);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }
}
