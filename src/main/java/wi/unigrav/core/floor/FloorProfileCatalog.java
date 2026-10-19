package wi.unigrav.core.floor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.block.state.IBlockState;

/** Immutable ordered stored-state selector catalog shared by initial and dirty scans. */
public final class FloorProfileCatalog {
    public record Binding(MagneticBlockRule stateSelector, Integer metadata, String profileId) {
        public Binding {
            Objects.requireNonNull(stateSelector, "stateSelector");
            if (stateSelector.requiresTileEntityNbt()) {
                throw new IllegalArgumentException("floor source selectors cannot require tile entity NBT");
            }
            if (metadata != null && metadata < 0) throw new IllegalArgumentException("metadata must be nonnegative");
            if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("profileId must not be blank");
        }
    }

    public record ResolvedCell(int descriptor, FloorFieldProfile profile) {
        public ResolvedCell {
            if (descriptor == 0) throw new IllegalArgumentException("resolved floor cell must contain a source descriptor");
            Objects.requireNonNull(profile, "profile");
        }
    }

    private static final FloorFieldProfile BUILT_IN = new FloorFieldProfile(32.0, 8.0, 16.0);
    private final Map<String, FloorFieldProfile> profiles;
    private final List<Binding> bindings;
    private final FloorFieldProfile fallback;

    public FloorProfileCatalog(Map<String, FloorFieldProfile> profiles, List<Binding> bindings,
                               FloorFieldProfile fallback) {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(bindings, "bindings");
        this.profiles = Map.copyOf(profiles);
        this.bindings = List.copyOf(bindings);
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        for (Binding binding : this.bindings) {
            Objects.requireNonNull(binding, "binding");
            if (!this.profiles.containsKey(binding.profileId())) {
                throw new IllegalArgumentException("unknown floor profile reference: " + binding.profileId());
            }
            if (!isSupportedFloorId(binding.stateSelector().registryId())) {
                throw new IllegalArgumentException("floor source selector must target a registered UG floor block: "
                        + binding.stateSelector().registryId());
            }
        }
    }

    /** Keeps legacy unit fixtures on the documented built-in profile without consulting block registration. */
    public static FloorProfileCatalog fixed(FloorFieldProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new FloorProfileCatalog(Map.of("default", profile), List.of(), profile);
    }

    public static FloorProfileCatalog defaults() {
        return fixed(BUILT_IN);
    }

    public Map<String, FloorFieldProfile> profiles() {
        return profiles;
    }

    public List<Binding> bindings() {
        return bindings;
    }

    FloorFieldProfile fallbackProfile() {
        return fallback;
    }

    public Optional<ResolvedCell> resolveStored(IBlockState state) {
        int descriptor = FloorChunkSources.descriptorAt(state);
        if (descriptor == 0) return Optional.empty();
        String registryId = state.getBlock().getRegistryName() == null
                ? "" : state.getBlock().getRegistryName().toString();
        int metadata = state.getBlock().getMetaFromState(state);
        for (Binding binding : bindings) {
            if (binding.stateSelector().registryId().equals(registryId)
                    && (binding.metadata() == null || binding.metadata().intValue() == metadata)
                    && binding.stateSelector().matchesState(state)) {
                return Optional.of(new ResolvedCell(descriptor, profiles.get(binding.profileId())));
            }
        }
        return Optional.of(new ResolvedCell(descriptor, fallback));
    }

    private static boolean isSupportedFloorId(String registryId) {
        return "unigrav:gravity_floor".equals(registryId)
                || "unigrav:magnetic_floor".equals(registryId)
                || "unigrav:double_sided_gravity_floor".equals(registryId);
    }
}
