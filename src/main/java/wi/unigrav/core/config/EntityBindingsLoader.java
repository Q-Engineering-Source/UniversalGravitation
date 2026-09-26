package wi.unigrav.core.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.EntityList;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;

/**
 * Data-driven default entity bindings: reads
 * {@code config/unigrav/entities.json} at server start and registers one
 * predicate binding per entry with the {@link GravityEntityBinder}. A
 * missing file means "no rules" and is not an error; malformed entries are
 * logged and skipped individually.
 *
 * <p>Schema (single file, a JSON array):
 * <pre>
 * [
 *   { "entity": "minecraft:item",  "mode": "REPLACE"  },
 *   { "entity": "minecraft:arrow", "mode": "ADDITIVE" },
 *   { "entity": "minecraft:xp_orb", "mode": "VANILLA" },
 *   { "entity": "*",               "mode": "REPLACE"  }
 * ]
 * </pre>
 * {@code entity} is the entity registry name (1.12.2 {@code EntityList}
 * registry; unknown names are skipped with a warning), or {@code "*"} for
 * every entity. {@code mode} is a {@link GravityMode} name,
 * case-insensitive, default {@code ADDITIVE}; invalid values are skipped
 * with a warning. The pseudo-mode {@code "VANILLA"} registers no binding
 * but exempts the type from the global takeover (it keeps vanilla gravity);
 * exempted classes are exposed via {@link #vanillaExemptClasses()}.
 *
 * <p>Everything registered here is unbound by {@link #unloadAll()} at
 * server stop, which also restores the {@code noGravity} flag of entities
 * bound in REPLACE mode before the world is saved.
 */
public final class EntityBindingsLoader {

    /** File (relative to the game config dir) holding the binding rules. */
    public static final String BINDINGS_FILE = "unigrav/entities.json";

    private final List<GravityEntityBinder.Registration> registrations = new ArrayList<>();
    private final List<Class<? extends Entity>> vanillaExemptClasses = new ArrayList<>();

    /**
     * Entity classes exempted from the global takeover by {@code "VANILLA"}
     * rules; valid after {@link #loadAll}.
     */
    public List<Class<? extends Entity>> vanillaExemptClasses() {
        return vanillaExemptClasses;
    }

    /**
     * Loads the rules from {@code configDir/unigrav/entities.json} and
     * registers them. Returns the number of rules in effect.
     */
    public int loadAll(File configDir) {
        File file = new File(configDir, BINDINGS_FILE);
        if (!file.isFile()) {
            UnigravMod.LOGGER.info("No entity bindings file {} (no rules)", file);
            return 0;
        }
        JsonArray rules;
        try {
            rules = JsonParser.parseString(Files.readString(file.toPath())).getAsJsonArray();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            UnigravMod.LOGGER.error("Could not parse entity bindings file {}: {}", file, e.toString());
            return 0;
        }

        int loaded = 0;
        for (JsonElement element : rules) {
            try {
                JsonObject rule = element.getAsJsonObject();
                String entityName = rule.get("entity").getAsString();
                if (!EntityBindingRule.isValidEntityName(entityName)) {
                    UnigravMod.LOGGER.warn("Skipping entity binding rule with invalid name \"{}\"",
                            entityName);
                    continue;
                }
                String modeText = rule.has("mode") ? rule.get("mode").getAsString() : null;
                if (EntityBindingRule.isVanillaMode(modeText)) {
                    if (EntityBindingRule.WILDCARD.equals(entityName)) {
                        UnigravMod.LOGGER.warn(
                                "Skipping entity binding rule: \"*\" with VANILLA makes no sense");
                        continue;
                    }
                    Class<? extends Entity> exempt =
                            EntityList.getClass(new ResourceLocation(entityName));
                    if (exempt == null) {
                        UnigravMod.LOGGER.warn(
                                "Skipping entity binding rule: unknown entity \"{}\"", entityName);
                        continue;
                    }
                    vanillaExemptClasses.add(exempt);
                    UnigravMod.LOGGER.info(
                            "Exempted entity from global takeover (keeps vanilla gravity): {} ({})",
                            entityName, exempt.getSimpleName());
                    continue;
                }
                GravityMode mode = modeText != null
                        ? EntityBindingRule.parseMode(modeText)
                        : GravityMode.ADDITIVE;
                if (mode == null) {
                    UnigravMod.LOGGER.warn(
                            "Skipping entity binding rule for {}: invalid mode \"{}\"",
                            entityName, modeText);
                    continue;
                }
                if (EntityBindingRule.WILDCARD.equals(entityName)) {
                    registrations.add(UnigravMod.entityBinder.bindPredicate(e -> true, mode));
                    UnigravMod.LOGGER.info("Registered entity binding: * -> {}", mode);
                    loaded++;
                    continue;
                }
                Class<? extends Entity> entityClass =
                        EntityList.getClass(new ResourceLocation(entityName));
                if (entityClass == null) {
                    UnigravMod.LOGGER.warn(
                            "Skipping entity binding rule: unknown entity \"{}\"", entityName);
                    continue;
                }
                registrations.add(UnigravMod.entityBinder.bindPredicate(entityClass::isInstance, mode));
                UnigravMod.LOGGER.info("Registered entity binding: {} -> {} ({})",
                        entityName, mode, entityClass.getSimpleName());
                loaded++;
            } catch (IllegalStateException | ClassCastException | NullPointerException e) {
                UnigravMod.LOGGER.warn("Skipping malformed entity binding rule {}: {}",
                        element, e.toString());
            }
        }
        UnigravMod.LOGGER.info("Loaded {} entity binding rule(s) from {}", loaded, file);
        return loaded;
    }

    /** Unbinds everything {@link #loadAll} registered. */
    public void unloadAll() {
        for (GravityEntityBinder.Registration registration : registrations) {
            registration.close();
        }
        registrations.clear();
        vanillaExemptClasses.clear();
    }
}
