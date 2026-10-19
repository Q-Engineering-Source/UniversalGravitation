package wi.unigrav.core.floor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.world.WorldServer;
import wi.unigrav.core.config.FloorConfigLoader.CompiledFloorConfig;

/** Immutable authoritative root for one compiled floor/boot/passive configuration revision. */
public final class ActiveFloorGeneration {
    private final ServerLifetime owner;
    private final CompiledFloorConfig config;
    private final Map<WorldServer, FloorWorldFields.WorldFields> worlds;

    ActiveFloorGeneration(ServerLifetime owner, CompiledFloorConfig config,
                          Map<WorldServer, FloorWorldFields.WorldFields> worlds) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(worlds, "worlds");
        IdentityHashMap<WorldServer, FloorWorldFields.WorldFields> copy = new IdentityHashMap<>();
        worlds.forEach((world, fields) -> copy.put(Objects.requireNonNull(world, "world"),
                Objects.requireNonNull(fields, "world fields")));
        this.worlds = Collections.unmodifiableMap(copy);
    }

    public ServerLifetime owner() {
        return owner;
    }

    public long configurationRevision() {
        return config.revision();
    }

    public ActiveBootRules.Snapshot bootRules() {
        return config.bootRules();
    }

    public MagneticResponseRules passiveResponses() {
        return config.passiveResponses();
    }

    public FloorProfileCatalog profiles() {
        return config.floorProfiles();
    }

    public Map<WorldServer, FloorWorldFields.WorldFields> worlds() {
        return worlds;
    }

    FloorWorldFields.WorldFields fields(WorldServer world) {
        return worlds.get(world);
    }

    CompiledFloorConfig compiledConfig() {
        return config;
    }

    ActiveFloorGeneration withWorld(WorldServer world, FloorWorldFields.WorldFields fields) {
        IdentityHashMap<WorldServer, FloorWorldFields.WorldFields> copy = new IdentityHashMap<>(worlds);
        copy.put(world, fields);
        return new ActiveFloorGeneration(owner, config, copy);
    }

    ActiveFloorGeneration withoutWorld(WorldServer world) {
        IdentityHashMap<WorldServer, FloorWorldFields.WorldFields> copy = new IdentityHashMap<>(worlds);
        copy.remove(world);
        return new ActiveFloorGeneration(owner, config, copy);
    }

    void retire() {
        RuntimeException runtimeFailure = null;
        Error errorFailure = null;
        for (FloorWorldFields.WorldFields fields : worlds.values()) {
            try {
                fields.close();
            } catch (RuntimeException failure) {
                if (runtimeFailure == null) runtimeFailure = failure;
                else runtimeFailure.addSuppressed(failure);
            } catch (Error failure) {
                if (errorFailure == null) errorFailure = failure;
                else errorFailure.addSuppressed(failure);
            }
        }
        if (errorFailure != null) {
            if (runtimeFailure != null) errorFailure.addSuppressed(runtimeFailure);
            throw errorFailure;
        }
        if (runtimeFailure != null) throw runtimeFailure;
    }
}
