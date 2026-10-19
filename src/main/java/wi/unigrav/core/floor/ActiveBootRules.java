package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Optional;

import net.minecraft.block.Block;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import wi.unigrav.core.floor.MagneticBlockReader.RuleBinding;
import wi.unigrav.core.floor.MagneticBlockReader.RuleSet;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/** Server-owned immutable active-boot equipment, material, tier, and work-limit snapshot. */
public final class ActiveBootRules {
    public static final String CORE_PROFILE_ID = "core";
    public static final String CORE_IRON_BLOCK_SELECTOR = "core_iron_block";
    public static final String CORE_MAGNETIC_FLOOR_SELECTOR = "core_unigrav_magnetic_floor";

    public enum IneligibleReason { EMPTY_FEET, NO_MATCH, CREATIVE_PERSONAL_G }
    public enum UnavailableReason { UNREGISTERED_ITEM, INCOMPLETE_RULE_DOMAIN, UNSUPPORTED_SELECTOR }

    public sealed interface Qualification permits QualifiedBoot, Ineligible, QualificationUnavailable { }

    public record QualifiedBoot(String profileId, BootTier tier, String itemRegistryId)
            implements Qualification {
        public QualifiedBoot {
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId must not be blank");
            Objects.requireNonNull(tier, "tier");
            requireNamespacedId(itemRegistryId, "itemRegistryId");
        }
    }

    public record Ineligible(IneligibleReason reason) implements Qualification {
        public Ineligible { Objects.requireNonNull(reason, "reason"); }
    }

    public record QualificationUnavailable(UnavailableReason reason, String detail,
                                           Optional<RuntimeException> cause)
            implements Qualification {
        public QualificationUnavailable(UnavailableReason reason, String detail) {
            this(reason, detail, Optional.empty());
        }

        public QualificationUnavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** One ordered real FEET item selector. Null metadata/NBT means an explicit wildcard. */
    public record EquipmentRule(EntityEquipmentSlot slot, String itemRegistryId, String profileId,
                                Integer metadata, NbtPattern itemTagPattern) {
        public EquipmentRule {
            Objects.requireNonNull(slot, "slot");
            requireNamespacedId(itemRegistryId, "itemRegistryId");
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId must not be blank");
            if (metadata != null && metadata < 0) throw new IllegalArgumentException("metadata must be nonnegative");
        }

        /** Source-compatible constructor for the former explicit all-metadata/any-NBT core selector. */
        public EquipmentRule(EntityEquipmentSlot slot, String itemRegistryId, String profileId,
                             boolean allMetadata, boolean anyNbt) {
            this(slot, itemRegistryId, profileId, null, null);
            if (!allMetadata || !anyNbt) {
                throw new IllegalArgumentException("legacy selector flags only represent all-metadata/any-NBT rules");
            }
        }

        public boolean allMetadata() {
            return metadata == null;
        }

        public boolean anyNbt() {
            return itemTagPattern == null;
        }

    }

    /**
     * Immutable complete rule domain for one effective revision. Every material selector and
     * equipment profile must have a tier; extra tiers are rejected so query reach cannot be
     * accidentally under-covered.
     */
    public record Snapshot(long revision, List<EquipmentRule> equipmentRules, RuleSet materialRules,
                           Map<String, BootTier> tiers, BootQueryFootprint.Limits captureLimits,
                           BootSurfaceCompiler.Limits compilerLimits, BootSurfaceQuery.Limits queryLimits,
                           Set<String> explicitMaterialTierOverrides) {
        public Snapshot(long revision, List<EquipmentRule> equipmentRules, RuleSet materialRules,
                        Map<String, BootTier> tiers, BootQueryFootprint.Limits captureLimits,
                        BootSurfaceCompiler.Limits compilerLimits, BootSurfaceQuery.Limits queryLimits) {
            this(revision, equipmentRules, materialRules, tiers, captureLimits, compilerLimits, queryLimits, Set.of());
        }

        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
            Objects.requireNonNull(equipmentRules, "equipmentRules");
            equipmentRules = List.copyOf(equipmentRules);
            Objects.requireNonNull(materialRules, "materialRules");
            Objects.requireNonNull(tiers, "tiers");
            tiers = Map.copyOf(tiers);
            Objects.requireNonNull(captureLimits, "captureLimits");
            Objects.requireNonNull(compilerLimits, "compilerLimits");
            Objects.requireNonNull(queryLimits, "queryLimits");
            Objects.requireNonNull(explicitMaterialTierOverrides, "explicitMaterialTierOverrides");
            explicitMaterialTierOverrides = Set.copyOf(explicitMaterialTierOverrides);
            if (materialRules.revision() != revision) {
                throw new IllegalArgumentException("material rule revision must match the active boot snapshot");
            }
            if (equipmentRules.isEmpty() || materialRules.bindings().isEmpty()) {
                throw new IllegalArgumentException("active boot rule domains must not be empty");
            }
            Set<String> domain = new HashSet<>();
            for (RuleBinding binding : materialRules.bindings()) domain.add(binding.selectionId());
            Set<String> equipmentProfiles = new HashSet<>();
            for (EquipmentRule rule : equipmentRules) {
                Objects.requireNonNull(rule, "equipment rule");
                if (rule.slot() != EntityEquipmentSlot.FEET) {
                    throw new IllegalArgumentException("active boot rules are restricted to the FEET slot");
                }
                domain.add(rule.profileId());
                equipmentProfiles.add(rule.profileId());
            }
            if (equipmentProfiles.isEmpty() || !tiers.keySet().equals(domain)) {
                throw new IllegalArgumentException("effective tier map must exactly cover equipment and material selector domains");
            }
            Set<String> materialIds = new HashSet<>();
            for (RuleBinding binding : materialRules.bindings()) materialIds.add(binding.selectionId());
            if (!materialIds.containsAll(explicitMaterialTierOverrides)) {
                throw new IllegalArgumentException("material tier overrides must reference configured material selectors");
            }
            for (BootTier tier : tiers.values()) Objects.requireNonNull(tier, "effective tier");
        }

        public double maximumCaptureReachMetres() {
            double maximum = 0.0;
            for (BootTier tier : tiers.values()) maximum = Math.max(maximum, tier.captureReachMetres());
            if (!(maximum > 0.0) || !Double.isFinite(maximum)) {
                throw new IllegalStateException("active boot snapshot has no finite positive capture domain");
            }
            return maximum;
        }

        /** Resolves every material selector from the actually equipped tier, unless JSON bound an override. */
        public Map<String, BootTier> effectiveTiers(QualifiedBoot qualifiedBoot) {
            Objects.requireNonNull(qualifiedBoot, "qualifiedBoot");
            if (!Objects.equals(tiers.get(qualifiedBoot.profileId()), qualifiedBoot.tier())) {
                throw new IllegalArgumentException("qualified boot profile is outside this snapshot");
            }
            Map<String, BootTier> effective = new LinkedHashMap<>();
            for (RuleBinding binding : materialRules.bindings()) {
                String selectorId = binding.selectionId();
                BootTier tier = explicitMaterialTierOverrides.contains(selectorId)
                        ? tiers.get(selectorId) : qualifiedBoot.tier();
                if (tier == null) throw new IllegalStateException("material selector has no effective boot tier: " + selectorId);
                effective.put(selectorId, tier);
            }
            return Map.copyOf(effective);
        }

        public double maximumCaptureReachMetres(QualifiedBoot qualifiedBoot) {
            double maximum = 0.0;
            for (BootTier tier : effectiveTiers(qualifiedBoot).values()) {
                maximum = Math.max(maximum, tier.captureReachMetres());
            }
            if (!(maximum > 0.0) || !Double.isFinite(maximum)) {
                throw new IllegalStateException("qualified boot has no finite positive capture domain");
            }
            return maximum;
        }
    }

    private volatile Snapshot current;

    public ActiveBootRules(Snapshot initial) {
        current = Objects.requireNonNull(initial, "initial");
    }

    /** Lazy because block/item registries are not populated when common-side owner singletons load. */
    public static ActiveBootRules core() {
        return CoreHolder.INSTANCE;
    }

    public Snapshot snapshot() {
        return current;
    }

    public boolean isCurrent(Snapshot snapshot) {
        return current == Objects.requireNonNull(snapshot, "snapshot");
    }

    /** Installs one already compiled immutable revision; callers must build it after registry readiness. */
    public synchronized void install(Snapshot next) {
        Objects.requireNonNull(next, "next");
        if (next.revision() <= current.revision()) {
            throw new IllegalArgumentException("active boot rule revision must advance monotonically");
        }
        current = next;
    }

    /** Classifies the exact FEET stack and actor mode; never reads equipment outside the supplied stack. */
    public Qualification qualify(ItemStack feet, boolean creative, boolean personalZeroG, Snapshot snapshot) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!isCurrent(snapshot)) {
            return new QualificationUnavailable(UnavailableReason.INCOMPLETE_RULE_DOMAIN,
                    "active boot rules changed before equipment qualification");
        }
        return qualifySnapshot(feet, creative, personalZeroG, snapshot);
    }

    /** Snapshot-only qualification used by one immutable ActiveFloorGeneration decision. */
    public static Qualification qualifySnapshot(ItemStack feet, boolean creative, boolean personalZeroG,
                                                Snapshot snapshot) {
        return qualifySnapshotInternal(feet, creative, personalZeroG, snapshot, null);
    }

    public static Qualification qualifySnapshot(ItemStack feet, boolean creative, boolean personalZeroG,
            Snapshot snapshot, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return qualifySnapshotInternal(feet, creative, personalZeroG, snapshot, work);
    }

    private static Qualification qualifySnapshotInternal(ItemStack feet, boolean creative, boolean personalZeroG,
            Snapshot snapshot, EnvironmentObservationWork work) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(snapshot, "snapshot");
        if (feet.isEmpty()) return new Ineligible(IneligibleReason.EMPTY_FEET);
        Item item = feet.getItem();
        ResourceLocation registryId = item == null ? null : Item.REGISTRY.getNameForObject(item);
        if (registryId == null || Item.REGISTRY.getObject(registryId) != item) {
            return new QualificationUnavailable(UnavailableReason.UNREGISTERED_ITEM,
                    "FEET stack item has no exact registered item ID");
        }
        net.minecraft.nbt.NBTTagCompound itemTag = null;
        boolean itemTagRead = false;
        for (EquipmentRule rule : snapshot.equipmentRules()) {
            if (work != null) work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            if (rule.slot() != EntityEquipmentSlot.FEET) {
                return new QualificationUnavailable(UnavailableReason.UNSUPPORTED_SELECTOR,
                        "active boot equipment selector escaped FEET");
            }
            if (rule.itemRegistryId().equals(registryId.toString())) {
                if (rule.metadata() != null && rule.metadata().intValue() != feet.getMetadata()) continue;
                boolean matches;
                try {
                    if (rule.itemTagPattern() == null) {
                        matches = true;
                    } else {
                        if (!itemTagRead) {
                            itemTag = feet.getTagCompound();
                            itemTagRead = true;
                        }
                        if (work != null) work.charge(EnvironmentObservationWork.Unit.DISPATCH);
                        matches = rule.itemTagPattern().matches(itemTag);
                    }
                } catch (RuntimeException failure) {
                    return new QualificationUnavailable(UnavailableReason.INCOMPLETE_RULE_DOMAIN,
                            "active FEET selector could not inspect its native item tag",
                            Optional.of(failure));
                }
                if (!matches) continue;
                if (creative && personalZeroG) return new Ineligible(IneligibleReason.CREATIVE_PERSONAL_G);
                BootTier tier = snapshot.tiers().get(rule.profileId());
                if (tier == null) {
                    return new QualificationUnavailable(UnavailableReason.INCOMPLETE_RULE_DOMAIN,
                            "qualified equipment profile has no effective tier");
                }
                return new QualifiedBoot(rule.profileId(), tier, registryId.toString());
            }
        }
        return new Ineligible(IneligibleReason.NO_MATCH);
    }

    private static Snapshot coreSnapshot() {
        long revision = 1L;
        BootTier coreTier = new BootTier(new FloorFieldProfile(32.0, 0.25, 1.5), 1.5, 6);
        String magneticFloorId = Objects.requireNonNull(FloorBlocks.MAGNETIC_FLOOR.getRegistryName(),
                "UG magnetic floor registry ID").toString();
        if (!"unigrav:magnetic_floor".equals(magneticFloorId)) {
            throw new IllegalStateException("registered UG magnetic floor ID changed: " + magneticFloorId);
        }
        var materialRules = new RuleSet(revision, List.of(
                new RuleBinding(CORE_IRON_BLOCK_SELECTOR,
                        new MagneticBlockRule("minecraft:iron_block", Map.of(), null)),
                new RuleBinding(CORE_MAGNETIC_FLOOR_SELECTOR,
                        new MagneticBlockRule(magneticFloorId, Map.of(), null))));
        var equipment = List.of(new EquipmentRule(EntityEquipmentSlot.FEET, "minecraft:iron_boots",
                CORE_PROFILE_ID, true, true));
        var tiers = Map.of(CORE_PROFILE_ID, coreTier, CORE_IRON_BLOCK_SELECTOR, coreTier,
                CORE_MAGNETIC_FLOOR_SELECTOR, coreTier);
        return new Snapshot(revision, equipment, materialRules, tiers,
                new BootQueryFootprint.Limits(1024, 8192, 16, 2048),
                new BootSurfaceCompiler.Limits(1024, 6144, 128, 131072, 4_000_000, 4096),
                new BootSurfaceQuery.Limits(4096, 1_048_576));
    }

    private static String requireNamespacedId(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf(':') <= 0 || value.indexOf(':') == value.length() - 1) {
            throw new IllegalArgumentException(name + " must be an exact namespaced ID");
        }
        return value;
    }

    private static final class CoreHolder {
        private static final ActiveBootRules INSTANCE = new ActiveBootRules(coreSnapshot());
    }
}
