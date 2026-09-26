package wi.unigrav.core.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.BodyPhysics;

/**
 * Data-driven mob mass presets: reads {@code config/unigrav/mass_presets.json}
 * at preInit and registers each entry with {@link BodyPhysics#registerPreset}.
 * On first run the built-in defaults are written out so the file doubles as
 * documentation; editing it changes masses on the next game start (deleting
 * an entry removes that preset, unknown registry names are logged and
 * skipped).
 *
 * <p>Schema:
 * <pre>
 * {
 *   "minecraft:sheep": {
 *     "mass": 120.0,            // base mass, kg (required)
 *     "variance": 0.15,         // per-individual ±fraction (optional, default 0.1)
 *     "swims": true,            // false = sinks at full gravity, no buoyancy
 *                               // (optional, default true)
 *     "gravity": true,          // false = excluded from the gravity takeover,
 *                               // keeps vanilla movement (optional, default true)
 *     "zeroGPathing": false,    // true = can navigate while weightless
 *                               // (flyers), exempt from the zero-G AI pause
 *                               // (optional, default false)
 *     "shearedMass": 110.0,     // sheep only: mass after shearing (optional)
 *     "shearedVariance": 0.1    // sheep only (optional, default 0.1)
 *   },
 *   ...
 * }
 * </pre>
 *
 * <p>Subclasses inherit the nearest registered ancestor's preset (e.g.
 * Mooshroom needs no entry of its own), so modded creatures descending
 * from a vanilla class are covered too.
 */
public final class MassPresetsLoader {

    /** File (relative to the game config dir) holding the preset table. */
    public static final String FILE_NAME = "unigrav/mass_presets.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Built-in defaults: registry name → preset spec. */
    private static final Map<String, Spec> DEFAULTS = new LinkedHashMap<>();

    /** One default table row. */
    private record Spec(double mass, double variance, boolean swims, boolean gravity,
                        boolean zeroGPathing, double magneticResponse) {
    }

    private static void def(String registryName, double massKg, double relVariance) {
        def(registryName, massKg, relVariance, true, true, false, 0.0);
    }

    private static void def(String registryName, double massKg, double relVariance,
                            boolean swims, boolean gravity, boolean zeroGPathing) {
        def(registryName, massKg, relVariance, swims, gravity, zeroGPathing, 0.0);
    }

    private static void def(String registryName, double massKg, double relVariance,
                            boolean swims, boolean gravity, boolean zeroGPathing,
                            double magneticResponse) {
        DEFAULTS.put(registryName,
                new Spec(massKg, relVariance, swims, gravity, zeroGPathing,
                        magneticResponse));
    }

    static {
        // --- Passive / neutral ---
        def("minecraft:bat", 0.05, 0.15, true, false, true); // vanilla flyer
        def("minecraft:chicken", 2.0, 0.2);
        def("minecraft:parrot", 0.1, 0.15);
        def("minecraft:rabbit", 2.0, 0.25);
        def("minecraft:ocelot", 10.0, 0.2);
        def("minecraft:wolf", 40.0, 0.2);
        def("minecraft:pig", 150.0, 0.2);
        def("minecraft:sheep", 120.0, 0.15); // shearedMass written separately
        def("minecraft:villager", 130.0, 0.15);
        def("minecraft:squid", 50.0, 0.2);
        def("minecraft:cow", 500.0, 0.15); // mooshroom inherits
        def("minecraft:polar_bear", 400.0, 0.2);
        def("minecraft:horse", 500.0, 0.15);
        def("minecraft:donkey", 300.0, 0.15);
        def("minecraft:mule", 400.0, 0.15);
        def("minecraft:llama", 250.0, 0.15);
        def("minecraft:skeleton_horse", 200.0, 0.1); // bones only
        def("minecraft:zombie_horse", 200.0, 0.1);
        // --- Hostile ---
        def("minecraft:zombie", 100.0, 0.15); // husk inherits
        def("minecraft:zombie_villager", 130.0, 0.15);
        def("minecraft:zombie_pigman", 120.0, 0.15);
        def("minecraft:skeleton", 30.0, 0.1); // stray inherits
        def("minecraft:wither_skeleton", 45.0, 0.1); // taller
        def("minecraft:creeper", 40.0, 0.15); // plant-like gunpowder body
        def("minecraft:spider", 60.0, 0.25);
        def("minecraft:cave_spider", 10.0, 0.25);
        def("minecraft:enderman", 80.0, 0.1); // tall but thin
        def("minecraft:witch", 120.0, 0.15);
        def("minecraft:silverfish", 1.0, 0.3);
        def("minecraft:endermite", 1.0, 0.3);
        def("minecraft:guardian", 150.0, 0.15);
        def("minecraft:elder_guardian", 600.0, 0.15);
        def("minecraft:shulker", 80.0, 0.1, true, false, true); // static
        def("minecraft:vex", 10.0, 0.2, true, false, true); // vanilla flyer
        def("minecraft:vindication_illager", 130.0, 0.15); // vindicator
        def("minecraft:evocation_illager", 120.0, 0.15); // evoker
        def("minecraft:illusion_illager", 120.0, 0.15);
        def("minecraft:blaze", 40.0, 0.15, true, false, true); // vanilla flyer
        def("minecraft:ghast", 800.0, 0.2, true, false, true); // vanilla flyer
        def("minecraft:snowman", 400.0, 0.15); // ~2 m³ of packed snow
        def("minecraft:giant", 2000.0, 0.1); // scaled-up zombie
        // Iron density 7874 × (1.4² × 2.7 × 0.1) m³ ≈ 4.2 t, cast to spec;
        // sinks like a stone (vanilla golems walk along riverbeds).
        def("minecraft:villager_golem", 4166.9, 0.05, false, true, false, 1.0); // iron golem
        // --- Bosses ---
        def("minecraft:wither", 300.0, 0.1, true, false, true); // vanilla flyer
        def("minecraft:ender_dragon", 8000.0, 0.1, true, false, true); // vanilla flyer
    }

    /** Default sheared-sheep mass, written into the generated file. */
    private static final double DEFAULT_SHEARED_MASS = 110.0;
    private static final double DEFAULT_SHEARED_VARIANCE = 0.1;

    private MassPresetsLoader() {
    }

    /**
     * Loads {@code config/unigrav/mass_presets.json}, generating it from the
     * built-in defaults first when absent. Returns the number of presets
     * applied.
     */
    public static int load(File configDir) {
        File file = new File(configDir, FILE_NAME);
        if (!file.exists()) {
            writeDefaults(file);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file.toPath()))
                    .getAsJsonObject();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            UnigravMod.LOGGER.error("Could not read mass presets from {}: {}",
                    file, e.toString());
            return 0;
        }
        int loaded = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                Class<? extends Entity> type =
                        EntityList.getClass(new ResourceLocation(entry.getKey()));
                if (type == null) {
                    UnigravMod.LOGGER.warn("Mass preset {}: unknown entity registry name",
                            entry.getKey());
                    continue;
                }
                JsonObject spec = entry.getValue().getAsJsonObject();
                // Missing keys fall back to the built-in default for that
                // species (then to generic defaults), so files written by
                // older versions pick up new flags without regeneration.
                Spec fallback = DEFAULTS.get(entry.getKey());
                double mass = spec.get("mass").getAsDouble();
                double variance = spec.has("variance")
                        ? spec.get("variance").getAsDouble()
                        : fallback != null ? fallback.variance() : 0.1;
                boolean swims = spec.has("swims")
                        ? spec.get("swims").getAsBoolean()
                        : fallback == null || fallback.swims();
                boolean gravity = spec.has("gravity")
                        ? spec.get("gravity").getAsBoolean()
                        : fallback == null || fallback.gravity();
                boolean zeroGPathing = spec.has("zeroGPathing")
                        ? spec.get("zeroGPathing").getAsBoolean()
                        : fallback != null && fallback.zeroGPathing();
                double magneticResponse = fallback != null ? fallback.magneticResponse() : 0.0;
                BodyPhysics.registerPreset(type, mass, variance, swims, gravity,
                        zeroGPathing, magneticResponse);
                if (spec.has("shearedMass")) {
                    BodyPhysics.setShearedSheepMass(spec.get("shearedMass").getAsDouble(),
                            spec.has("shearedVariance")
                                    ? spec.get("shearedVariance").getAsDouble() : 0.1);
                }
                loaded++;
            } catch (JsonParseException | IllegalStateException | NullPointerException e) {
                UnigravMod.LOGGER.error("Skipping mass preset {}: {}",
                        entry.getKey(), e.toString());
            }
        }
        UnigravMod.LOGGER.info("Loaded {} mass preset(s) from {}", loaded, file);
        return loaded;
    }

    /** Writes the built-in table as the initial JSON file. */
    private static void writeDefaults(File file) {
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            UnigravMod.LOGGER.warn("Could not create config dir for {}", file);
            return;
        }
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Spec> entry : DEFAULTS.entrySet()) {
            Spec spec = entry.getValue();
            JsonObject json = new JsonObject();
            json.addProperty("mass", spec.mass());
            json.addProperty("variance", spec.variance());
            json.addProperty("swims", spec.swims());
            json.addProperty("gravity", spec.gravity());
            json.addProperty("zeroGPathing", spec.zeroGPathing());
            if (entry.getKey().equals("minecraft:sheep")) {
                json.addProperty("shearedMass", DEFAULT_SHEARED_MASS);
                json.addProperty("shearedVariance", DEFAULT_SHEARED_VARIANCE);
            }
            root.add(entry.getKey(), json);
        }
        try {
            Files.writeString(file.toPath(), GSON.toJson(root) + "\n");
            UnigravMod.LOGGER.info("Wrote default mass presets to {}", file);
        } catch (IOException e) {
            UnigravMod.LOGGER.error("Could not write default mass presets to {}: {}",
                    file, e.toString());
        }
    }
}
