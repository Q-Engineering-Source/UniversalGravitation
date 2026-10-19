package wi.unigrav.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootQueryFootprint;
import wi.unigrav.core.floor.BootSurfaceCompiler;
import wi.unigrav.core.floor.BootSurfaceQuery;
import wi.unigrav.core.floor.FloorFieldProfile;
import wi.unigrav.core.floor.FloorProfileCatalog;
import wi.unigrav.core.floor.MagneticBlockReader;
import wi.unigrav.core.floor.MagneticBlockRule;
import wi.unigrav.core.floor.MagneticItemRule;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.floor.NbtPattern;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/** Complete parse/compile boundary for {@code config/unigrav/floors.json}. */
public final class FloorConfigLoader {
    public static final String FILE_NAME = "unigrav/floors.json";
    private static final String DEFAULT_RESOURCE = "/config/unigrav/floors.json";
    private static final FloorFieldProfile DEFAULT_FLOOR = new FloorFieldProfile(32.0, 8.0, 16.0);
    private static final BootQueryFootprint.Limits DEFAULT_CAPTURE_LIMITS =
            new BootQueryFootprint.Limits(1024, 8192, 16, 2048);
    private static final BootSurfaceCompiler.Limits DEFAULT_COMPILER_LIMITS =
            new BootSurfaceCompiler.Limits(1024, 6144, 128, 131072, 4_000_000, 4096);
    private static final BootSurfaceQuery.Limits DEFAULT_QUERY_LIMITS =
            new BootSurfaceQuery.Limits(4096, 1_048_576);

    public record CompiledFloorConfig(long revision, FloorProfileCatalog floorProfiles,
                                      ActiveBootRules.Snapshot bootRules,
                                      MagneticResponseRules passiveResponses) {
        public CompiledFloorConfig {
            if (revision < 0L) throw new IllegalArgumentException("revision must be nonnegative");
            java.util.Objects.requireNonNull(floorProfiles, "floorProfiles");
            java.util.Objects.requireNonNull(bootRules, "bootRules");
            java.util.Objects.requireNonNull(passiveResponses, "passiveResponses");
            if (bootRules.revision() != revision) throw new IllegalArgumentException("boot revision must match config revision");
        }
    }

    private FloorConfigLoader() {
    }

    /** Copies the packaged example only when absent; it never replaces administrator data. */
    public static Path ensureDefault(Path configDirectory) {
        Path file = configDirectory.resolve(FILE_NAME);
        if (Files.exists(file)) return file;
        try (InputStream input = FloorConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (input == null) throw new IOException("packaged floor configuration is missing: " + DEFAULT_RESOURCE);
            byte[] bytes = input.readAllBytes();
            Files.createDirectories(file.getParent());
            try {
                Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException concurrentCreate) {
                return file;
            }
            return file;
        } catch (IOException failure) {
            throw new FloorConfigException(file, "$", "default-create",
                    "could not create the default floor configuration", failure);
        }
    }

    /** Reads and parses the complete UTF-8 file without mutating the active generation. */
    public static FloorConfigSpec parse(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException("root must be a JSON object");
            return new FloorConfigSpec(file, json);
        } catch (IOException | JsonParseException | IllegalStateException | IllegalArgumentException failure) {
            throw failure(file, "$", "parse", "could not parse the complete floor configuration", failure);
        }
    }

    /** Resolves registry-backed rules and all bounded values before any runtime owner can publish them. */
    public static CompiledFloorConfig compile(FloorConfigSpec spec, long revision) {
        java.util.Objects.requireNonNull(spec, "spec");
        if (revision < 0L) throw failure(spec.path(), "revision", "compile", "revision must be nonnegative", null);
        JsonObject root;
        try {
            root = JsonParser.parseString(spec.json()).getAsJsonObject();
        } catch (RuntimeException cause) {
            throw failure(spec.path(), "$", "compile", "parsed document no longer has a JSON object root", cause);
        }
        try {
            int schemaVersion = requiredInt(root, "schemaVersion", "$" );
            if (schemaVersion != 1) throw new IllegalArgumentException("unsupported schemaVersion " + schemaVersion);

            Map<String, FloorFieldProfile> floorProfiles = parseFloorProfiles(root, spec.path());
            String defaultFloorProfile = optionalString(root, "defaultFloorProfile", "standard", "$");
            FloorFieldProfile fallback = requiredMapValue(floorProfiles, defaultFloorProfile,
                    "defaultFloorProfile", spec.path());
            FloorProfileCatalog catalog = parseFloorBindings(root, floorProfiles, fallback, spec.path());

            Map<String, BootTier> bootTiers = parseBootTiers(root, floorProfiles, spec.path());
            String defaultBootTier = requiredString(root, "defaultBootTier", "$");
            BootTier passiveDefaultTier = requiredMapValue(bootTiers, defaultBootTier, "defaultBootTier", spec.path());
            List<ActiveBootRules.EquipmentRule> equipment = parseEquipment(root, bootTiers, spec.path());
            ActiveMaterials activeMaterials = parseActiveMaterials(root, bootTiers, spec.path());
            Set<String> materialOverrides = activeMaterials.explicitTierBySelector().keySet();
            Map<String, BootTier> effectiveDomain = new HashMap<>();
            for (ActiveBootRules.EquipmentRule rule : equipment) {
                effectiveDomain.put(rule.profileId(), requiredMapValue(bootTiers, rule.profileId(), "activeBoots.profile", spec.path()));
            }
            for (MagneticBlockReader.RuleBinding binding : activeMaterials.rules()) {
                if (bootTiers.containsKey(binding.selectionId())) {
                    throw failure(spec.path(), "activeMaterials", "compile",
                            "material selector ID collides with a boot tier ID: " + binding.selectionId(), null);
                }
                String overrideTier = activeMaterials.explicitTierBySelector().get(binding.selectionId());
                BootTier tier = overrideTier != null
                        ? requiredMapValue(bootTiers, overrideTier, "activeMaterials.tier", spec.path())
                        : passiveDefaultTier;
                effectiveDomain.put(binding.selectionId(), tier);
            }
            var limits = parseLimits(root, spec.path());
            var materialRules = new MagneticBlockReader.RuleSet(revision, activeMaterials.rules());
            var bootRules = new ActiveBootRules.Snapshot(revision, equipment, materialRules, effectiveDomain,
                    limits.capture(), limits.compiler(), limits.query(), materialOverrides);
            MagneticResponseRules passive = parsePassive(root, spec.path());
            return new CompiledFloorConfig(revision, catalog, bootRules, passive);
        } catch (FloorConfigException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw failure(spec.path(), "$", "compile", "invalid floor configuration", failure);
        }
    }

    private static Map<String, FloorFieldProfile> parseFloorProfiles(JsonObject root, Path file) {
        JsonObject entries = requiredObject(root, "profiles", "profiles", file);
        Map<String, FloorFieldProfile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String field = "profiles." + entry.getKey();
            try {
                JsonObject value = entry.getValue().getAsJsonObject();
                profiles.put(entry.getKey(), new FloorFieldProfile(requiredDouble(value, "strength", field),
                        requiredDouble(value, "plateau", field), requiredDouble(value, "range", field)));
            } catch (RuntimeException cause) {
                throw failure(file, field, "compile", "invalid floor field profile", cause);
            }
        }
        if (profiles.isEmpty()) throw failure(file, "profiles", "compile", "at least one floor profile is required", null);
        return Map.copyOf(profiles);
    }

    private static FloorProfileCatalog parseFloorBindings(JsonObject root, Map<String, FloorFieldProfile> profiles,
                                                           FloorFieldProfile fallback, Path file) {
        JsonArray rows = optionalArray(root, "floorBindings", "floorBindings", file);
        List<FloorProfileCatalog.Binding> bindings = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            String field = "floorBindings[" + index + "]";
            try {
                JsonObject row = rows.get(index).getAsJsonObject();
                String blockId = requiredString(row, "block", field);
                Map<String, String> state = stringMap(row.get("state"), field + ".state");
                Integer metadata = optionalBlockMetadata(row, "metadata", field);
                String profileId = requiredString(row, "profile", field);
                if (!profiles.containsKey(profileId)) throw new IllegalArgumentException("unknown profile reference " + profileId);
                MagneticBlockRule selector = new MagneticBlockRule(blockId, state, null);
                bindings.add(new FloorProfileCatalog.Binding(selector, metadata, profileId));
            } catch (RuntimeException cause) {
                throw failure(file, field, "compile", "invalid floor source binding", cause);
            }
        }
        try {
            return new FloorProfileCatalog(profiles, bindings, fallback);
        } catch (RuntimeException cause) {
            throw failure(file, "floorBindings", "compile", "invalid ordered floor source catalog", cause);
        }
    }

    private static Map<String, BootTier> parseBootTiers(JsonObject root, Map<String, FloorFieldProfile> profiles,
                                                        Path file) {
        JsonObject entries = requiredObject(root, "bootTiers", "bootTiers", file);
        Map<String, BootTier> tiers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String field = "bootTiers." + entry.getKey();
            try {
                JsonObject row = entry.getValue().getAsJsonObject();
                String floorProfile = requiredString(row, "floorProfile", field);
                FloorFieldProfile profile = requiredMapValue(profiles, floorProfile, field + ".floorProfile", file);
                double reach = requiredDouble(row, "captureReachMetres", field);
                long protection = requiredLong(row, "jumpProtectionTicks", field);
                tiers.put(entry.getKey(), new BootTier(profile, reach, protection));
            } catch (RuntimeException cause) {
                throw failure(file, field, "compile", "invalid active-boot tier", cause);
            }
        }
        if (tiers.isEmpty()) throw failure(file, "bootTiers", "compile", "at least one active-boot tier is required", null);
        return Map.copyOf(tiers);
    }

    private static List<ActiveBootRules.EquipmentRule> parseEquipment(JsonObject root, Map<String, BootTier> tiers,
                                                                       Path file) {
        JsonArray rows = requiredArray(root, "activeBoots", "activeBoots", file);
        List<ActiveBootRules.EquipmentRule> rules = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            String field = "activeBoots[" + index + "]";
            try {
                JsonObject row = rows.get(index).getAsJsonObject();
                String slot = optionalString(row, "slot", "feet", field);
                if (!"feet".equals(slot)) throw new IllegalArgumentException("active boots are restricted to slot feet");
                String itemId = requiredString(row, "item", field);
                requireRegisteredItem(itemId);
                String tier = requiredString(row, "tier", field);
                if (!tiers.containsKey(tier)) throw new IllegalArgumentException("unknown boot tier " + tier);
                rules.add(new ActiveBootRules.EquipmentRule(EntityEquipmentSlot.FEET, itemId, tier,
                        optionalMetadata(row, "metadata", field), nbtPattern(row, "nbtSnbt", field)));
            } catch (RuntimeException cause) {
                if (cause instanceof FloorConfigException configFailure) throw configFailure;
                throw failure(file, field, "compile", "invalid ordered active-boot selector", cause);
            }
        }
        if (rules.isEmpty()) throw failure(file, "activeBoots", "compile", "at least one active FEET selector is required", null);
        return List.copyOf(rules);
    }

    private record ActiveMaterials(List<MagneticBlockReader.RuleBinding> rules, Map<String, String> explicitTierBySelector) {
        private ActiveMaterials {
            rules = List.copyOf(rules);
            explicitTierBySelector = Map.copyOf(explicitTierBySelector);
        }
    }

    private static ActiveMaterials parseActiveMaterials(JsonObject root, Map<String, BootTier> tiers, Path file) {
        JsonArray rows = requiredArray(root, "activeMaterials", "activeMaterials", file);
        List<MagneticBlockReader.RuleBinding> rules = new ArrayList<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            String field = "activeMaterials[" + index + "]";
            try {
                JsonObject row = rows.get(index).getAsJsonObject();
                String id = requiredString(row, "id", field);
                String blockId = requiredString(row, "block", field);
                Map<String, String> state = stringMap(row.get("state"), field + ".state");
                Integer metadata = optionalBlockMetadata(row, "metadata", field);
                NbtPattern tilePattern = nbtPattern(row, "nbtSnbt", field);
                String tier = optionalString(row, "tier", null, field);
                if (tier != null && !tiers.containsKey(tier)) throw new IllegalArgumentException("unknown material tier " + tier);
                rules.add(new MagneticBlockReader.RuleBinding(id,
                        new MagneticBlockRule(blockId, state, metadata, tilePattern)));
                if (tier != null) {
                    overrides.put(id, tier);
                }
            } catch (RuntimeException cause) {
                if (cause instanceof FloorConfigException configFailure) throw configFailure;
                throw failure(file, field, "compile", "invalid ordered active-material selector", cause);
            }
        }
        if (rules.isEmpty()) throw failure(file, "activeMaterials", "compile", "at least one active material selector is required", null);
        return new ActiveMaterials(rules, overrides);
    }

    private static MagneticResponseRules parsePassive(JsonObject root, Path file) {
        JsonObject passive = requiredObject(root, "passive", "passive", file);
        return new MagneticResponseRules(parsePassiveItems(requiredArray(passive, "droppedItems", "passive.droppedItems", file),
                "passive.droppedItems", file), parsePassiveItems(requiredArray(passive, "playerBoots", "passive.playerBoots", file),
                "passive.playerBoots", file));
    }

    private static List<MagneticItemRule> parsePassiveItems(JsonArray rows, String fieldRoot, Path file) {
        List<MagneticItemRule> rules = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            String field = fieldRoot + "[" + index + "]";
            try {
                JsonObject row = rows.get(index).getAsJsonObject();
                String itemId = requiredString(row, "item", field);
                requireRegisteredItem(itemId);
                rules.add(new MagneticItemRule(itemId, optionalMetadata(row, "metadata", field),
                        nbtPattern(row, "nbtSnbt", field), requiredDouble(row, "response", field)));
            } catch (RuntimeException cause) {
                if (cause instanceof FloorConfigException configFailure) throw configFailure;
                throw failure(file, field, "compile", "invalid ordered passive-item selector", cause);
            }
        }
        return List.copyOf(rules);
    }

    private record Limits(BootQueryFootprint.Limits capture, BootSurfaceCompiler.Limits compiler,
                          BootSurfaceQuery.Limits query) {
    }

    private static Limits parseLimits(JsonObject root, Path file) {
        JsonObject limits = root.has("limits") ? object(root.get("limits"), "limits", file) : new JsonObject();
        JsonObject capture = optionalObject(limits, "capture", "limits.capture", file);
        JsonObject compiler = optionalObject(limits, "compiler", "limits.compiler", file);
        JsonObject query = optionalObject(limits, "query", "limits.query", file);
        try {
            return new Limits(new BootQueryFootprint.Limits(
                    optionalInt(capture, "maxProducerCells", 1024, "limits.capture"),
                    optionalInt(capture, "maxDependencyCells", 8192, "limits.capture"),
                    optionalInt(capture, "maxChunkColumns", 16, "limits.capture"),
                    optionalInt(capture, "maxCandidateBoxes", 2048, "limits.capture")),
                    new BootSurfaceCompiler.Limits(
                            optionalInt(compiler, "maxPrimitives", 1024, "limits.compiler"),
                            optionalInt(compiler, "maxRawFaces", 6144, "limits.compiler"),
                            optionalInt(compiler, "maxCutsPerAxis", 128, "limits.compiler"),
                            optionalInt(compiler, "maxTiles", 131072, "limits.compiler"),
                            optionalInt(compiler, "maxCoverageChecks", 4_000_000, "limits.compiler"),
                            optionalInt(compiler, "maxFragments", 4096, "limits.compiler")),
                    new BootSurfaceQuery.Limits(optionalInt(query, "maxFragments", 4096, "limits.query"),
                            optionalInt(query, "maxVisibilityBoxTests", 1_048_576, "limits.query")));
        } catch (RuntimeException cause) {
            throw failure(file, "limits", "compile", "invalid bounded boot work limits", cause);
        }
    }

    private static NbtPattern nbtPattern(JsonObject row, String member, String field) {
        JsonElement element = row.get(member);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + "." + member + " must be an SNBT string or null");
        }
        String snbt = element.getAsString();
        try {
            NBTBase parsed = JsonToNBT.getTagFromJson(snbt);
            if (!(parsed instanceof NBTTagCompound compound)) {
                throw new IllegalArgumentException(field + "." + member + " must have a compound root");
            }
            return NbtPattern.compile(compound);
        } catch (NBTException | RuntimeException cause) {
            throw new IllegalArgumentException("could not compile native SNBT at " + field + "." + member, cause);
        }
    }

    private static void requireRegisteredItem(String itemId) {
        ResourceLocation id = exactId(itemId);
        if (!Item.REGISTRY.containsKey(id) || Item.REGISTRY.getObject(id) == null) {
            throw new IllegalArgumentException("unknown item registry ID " + id);
        }
    }

    private static ResourceLocation exactId(String text) {
        if (text == null || text.indexOf(':') <= 0 || text.indexOf(':') == text.length() - 1) {
            throw new IllegalArgumentException("registry ID must be exact namespace:path");
        }
        ResourceLocation id = new ResourceLocation(text);
        if (!id.toString().equals(text)) throw new IllegalArgumentException("registry ID is not canonical: " + text);
        return id;
    }

    private static Map<String, String> stringMap(JsonElement element, String field) {
        if (element == null) return Map.of();
        if (!element.isJsonObject()) throw new IllegalArgumentException(field + " must be an object of stored-state properties");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException(field + "." + entry.getKey() + " must be a canonical property string");
            }
            result.put(entry.getKey(), value.getAsString());
        }
        return Map.copyOf(result);
    }

    private static Integer optionalMetadata(JsonObject row, String member, String field) {
        if (!row.has(member) || row.get(member).isJsonNull()) return null;
        int value = exactInt(row.get(member), field + "." + member);
        if (value < 0) throw new IllegalArgumentException(field + "." + member + " must be nonnegative");
        return value;
    }

    private static Integer optionalBlockMetadata(JsonObject row, String member, String field) {
        Integer metadata = optionalMetadata(row, member, field);
        if (metadata != null && metadata > 15) {
            throw new IllegalArgumentException(field + "." + member + " must fit the block metadata range [0, 15]");
        }
        return metadata;
    }

    private static JsonObject requiredObject(JsonObject parent, String member, String field, Path file) {
        if (!parent.has(member)) throw failure(file, field, "compile", "required object is missing", null);
        return object(parent.get(member), field, file);
    }

    private static JsonObject optionalObject(JsonObject parent, String member, String field, Path file) {
        return parent.has(member) ? object(parent.get(member), field, file) : new JsonObject();
    }

    private static JsonObject object(JsonElement element, String field, Path file) {
        if (element == null || !element.isJsonObject()) throw failure(file, field, "compile", "expected an object", null);
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject parent, String member, String field, Path file) {
        if (!parent.has(member) || !parent.get(member).isJsonArray()) {
            throw failure(file, field, "compile", "required array is missing or has the wrong type", null);
        }
        return parent.getAsJsonArray(member);
    }

    private static JsonArray optionalArray(JsonObject parent, String member, String field, Path file) {
        if (!parent.has(member)) return new JsonArray();
        if (!parent.get(member).isJsonArray()) throw failure(file, field, "compile", "expected an array", null);
        return parent.getAsJsonArray(member);
    }

    private static String requiredString(JsonObject object, String member, String field) {
        JsonElement value = object.get(member);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + "." + member + " must be a string");
        }
        String result = value.getAsString();
        if (result.isBlank()) throw new IllegalArgumentException(field + "." + member + " must not be blank");
        return result;
    }

    private static String optionalString(JsonObject object, String member, String defaultValue, String field) {
        return object.has(member) ? requiredString(object, member, field) : defaultValue;
    }

    private static int requiredInt(JsonObject object, String member, String field) {
        JsonElement element = object.get(member);
        if (element == null) throw new IllegalArgumentException(field + "." + member + " is required");
        return exactInt(element, field + "." + member);
    }

    private static int optionalInt(JsonObject object, String member, int defaultValue, String field) {
        return object.has(member) ? exactInt(object.get(member), field + "." + member) : defaultValue;
    }

    private static int exactInt(JsonElement element, String field) {
        try {
            if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(field + " must be a JSON integer");
            }
            return new BigDecimal(element.getAsString()).intValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException(field + " must be an exact 32-bit integer", failure);
        }
    }

    private static long requiredLong(JsonObject object, String member, String field) {
        JsonElement element = object.get(member);
        if (element == null) throw new IllegalArgumentException(field + "." + member + " is required");
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException(field + "." + member + " must be a JSON integer");
            }
            long value = new BigDecimal(element.getAsString()).longValueExact();
            if (value <= 0L) throw new IllegalArgumentException(field + "." + member + " must be positive");
            return value;
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException(field + "." + member + " must be a positive exact integer", failure);
        }
    }

    private static double requiredDouble(JsonObject object, String member, String field) {
        JsonElement element = object.get(member);
        if (element == null) throw new IllegalArgumentException(field + "." + member + " is required");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + "." + member + " must be a JSON number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + "." + member + " must be finite");
        return value;
    }

    private static <T> T requiredMapValue(Map<String, T> values, String key, String field, Path file) {
        T value = values.get(key);
        if (value == null) throw failure(file, field, "compile", "unknown reference " + key, null);
        return value;
    }

    private static FloorConfigException failure(Path file, String field, String stage, String message, Throwable cause) {
        return new FloorConfigException(file, field, stage, message + " [" + field + "]", cause);
    }
}
