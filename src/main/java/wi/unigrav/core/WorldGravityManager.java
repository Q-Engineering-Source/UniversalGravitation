package wi.unigrav.core;

import net.minecraft.server.MinecraftServer;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.event.WorldGravityChangeEvent;

/**
 * Owns the per-world uniform gravity contribution: on each server-world load the
 * persisted {@link WorldGravityData} is read (first-time worlds are seeded
 * from {@link UnigravConfig#worldGravityStrength}) and registered into their
 * world field under {@code unigrav:world}; on unload it is unregistered.
 *
 * <p>All changes go through {@link #setWorldGravity(Vec3, Boolean)}, the
 * unified entry point that persists the new value, swaps the registration,
 * and fires {@link WorldGravityChangeEvent.Pre} (cancelable/mutable) and
 * {@link WorldGravityChangeEvent.Post}.
 */
public final class WorldGravityManager {

    /** Field id of the world gravity contribution: {@code unigrav:world}. */
    public static final String NAMESPACE = "unigrav";
    public static final String PATH = "world";

    private WorldGravityData data;
    private WorldServer owner;
    private final Map<WorldServer, WorldGravityManager> worlds = new IdentityHashMap<>();

    private WorldGravityManager primary() {
        for (var entry : worlds.entrySet()) {
            if (entry.getKey().provider.getDimension() == 0) return entry.getValue();
        }
        return null;
    }
    public void stop(MinecraftServer server) {
        var iterator = worlds.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().getMinecraftServer() == server) {
                entry.getValue().unload();
                iterator.remove();
            }
        }
    }

    private GravityField.Registration registration;

    /** Subscribes this manager to the Forge event bus. */
    public void registerOnEventBus() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote || !(world instanceof WorldServer serverWorld)) return;
        if (worlds.containsKey(serverWorld)) return;
        AmbientWorldFields.prepare(serverWorld);
        WorldGravityManager context = new WorldGravityManager();
        context.owner = serverWorld;
        try {
            context.load();
            worlds.put(serverWorld, context);
        } catch (RuntimeException | Error failure) {
            try { context.unload(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        WorldGravityManager context = worlds.remove(event.getWorld());
        if (context != null) context.unload();
    }


    private void load() {
        data = WorldGravityData.get(owner);
        if (!data.isInitialized()) {
            // First load of this world: seed from the config default.
            data.setAcceleration(
                    UniformGravityContribution.downward(UnigravConfig.worldGravityStrength)
                            .acceleration());
            data.setEnabled(true);
            data.setInitialized(true);
            UnigravMod.LOGGER.info(
                    "Initialized default world gravity {} m/s^2 (strength {} from config)",
                    data.getAcceleration(), UnigravConfig.worldGravityStrength);
        }
        applyRegistration();
        UnigravMod.LOGGER.info("Registered world gravity {} m/s^2 (enabled={}) as {}:{}",
                data.getAcceleration(), data.isEnabled(), NAMESPACE, PATH);
    }
    private void unload() {
        if (registration != null) {
            registration.close();
            registration = null;
        }
        data = null;
    }

    /** Whether a world is currently loaded and its gravity is registered. */
    private boolean localIsLoaded() {
        return data != null;
    }

    /** The persisted world gravity acceleration, in m/s²; null when no world is loaded. */
    private Vec3 localGetAcceleration() {
        return data != null ? data.getAcceleration() : null;
    }

    private boolean localIsEnabled() {
        return data != null && data.isEnabled();
    }

    /**
     * Unified world-gravity change entry point.
     *
     * @param newAcceleration the requested acceleration (m/s²), or null to keep
     * @param newEnabled      the requested enabled flag, or null to keep
     * @return false when no world is loaded or a {@link WorldGravityChangeEvent.Pre}
     *         listener vetoed the change
     */
    private boolean localSetWorldGravity(Vec3 newAcceleration, Boolean newEnabled) {
        if (data == null) {
            return false;
        }
        Vec3 target = newAcceleration != null ? newAcceleration : data.getAcceleration();
        boolean targetEnabled = newEnabled != null ? newEnabled : data.isEnabled();
        WorldGravityChangeEvent.Pre pre = new WorldGravityChangeEvent.Pre(
                data.getAcceleration(), data.isEnabled(), target, targetEnabled);
        if (MinecraftForge.EVENT_BUS.post(pre)) {
            UnigravMod.LOGGER.info("World gravity change vetoed by a Pre listener");
            return false;
        }
        Vec3 previous = data.getAcceleration();
        boolean wasEnabled = data.isEnabled();
        data.setAcceleration(pre.getNewAcceleration());
        data.setEnabled(pre.isNewEnabled());
        applyRegistration();
        UnigravMod.LOGGER.info("World gravity changed to {} m/s^2 (enabled={})",
                pre.getNewAcceleration(), pre.isNewEnabled());
        MinecraftForge.EVENT_BUS.post(new WorldGravityChangeEvent.Post(
                previous, wasEnabled, pre.getNewAcceleration(), pre.isNewEnabled()));
        return true;
    }

    /** Resets to the config default: straight down at the configured strength, enabled. */
    private boolean localResetToDefault() {
        return localSetWorldGravity(
                UniformGravityContribution.downward(UnigravConfig.worldGravityStrength)
                        .acceleration(),
                Boolean.TRUE);
    }

    private void applyRegistration() {
        // A disabled field is a zero field: the registration stays so that
        // /unigrav accel and downstream reads see an explicit value.
        Vec3 effective = data.isEnabled() ? data.getAcceleration() : Vec3.ZERO;
        registration = AmbientWorldFields.field(owner).registerContribution(
                NAMESPACE, PATH, new UniformGravityContribution(effective));
    }
    public boolean isLoaded() {
        WorldGravityManager context = primary();
        return context == null ? false : context.localIsLoaded();
    }
    public boolean isLoaded(WorldServer world) {
        WorldGravityManager context = worlds.get(world);
        return context == null ? false : context.localIsLoaded();
    }
    public Vec3 getAcceleration() {
        WorldGravityManager context = primary();
        return context == null ? null : context.localGetAcceleration();
    }
    public Vec3 getAcceleration(WorldServer world) {
        WorldGravityManager context = worlds.get(world);
        return context == null ? null : context.localGetAcceleration();
    }
    public boolean isEnabled() {
        WorldGravityManager context = primary();
        return context == null ? false : context.localIsEnabled();
    }
    public boolean isEnabled(WorldServer world) {
        WorldGravityManager context = worlds.get(world);
        return context == null ? false : context.localIsEnabled();
    }
    public boolean setWorldGravity(Vec3 newAcceleration, Boolean newEnabled) {
        WorldGravityManager context = primary();
        return context == null ? false : context.localSetWorldGravity(newAcceleration, newEnabled);
    }
    public boolean setWorldGravity(WorldServer world, Vec3 newAcceleration, Boolean newEnabled) {
        WorldGravityManager context = worlds.get(world);
        return context == null ? false : context.localSetWorldGravity(newAcceleration, newEnabled);
    }
    public boolean resetToDefault() {
        WorldGravityManager context = primary();
        return context == null ? false : context.localResetToDefault();
    }
    public boolean resetToDefault(WorldServer world) {
        WorldGravityManager context = worlds.get(world);
        return context == null ? false : context.localResetToDefault();
    }
}
