package wi.unigrav.core;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.event.WorldGravityChangeEvent;

/**
 * Owns the per-world uniform gravity contribution: on overworld load the
 * persisted {@link WorldGravityData} is read (first-time worlds are seeded
 * from {@link UnigravConfig#worldGravityStrength}) and registered into the
 * global field under {@code unigrav:world}; on unload it is unregistered.
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
    private GravityField.Registration registration;

    /** Subscribes this manager to the Forge event bus. */
    public void registerOnEventBus() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote || world.provider.getDimension() != 0) {
            return;
        }
        data = WorldGravityData.get((WorldServer) world);
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

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote || world.provider.getDimension() != 0) {
            return;
        }
        if (registration != null) {
            registration.close();
            registration = null;
        }
        data = null;
    }

    /** Whether a world is currently loaded and its gravity is registered. */
    public boolean isLoaded() {
        return data != null;
    }

    /** The persisted world gravity acceleration, in m/s²; null when no world is loaded. */
    public Vec3 getAcceleration() {
        return data != null ? data.getAcceleration() : null;
    }

    public boolean isEnabled() {
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
    public boolean setWorldGravity(Vec3 newAcceleration, Boolean newEnabled) {
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
    public boolean resetToDefault() {
        return setWorldGravity(
                UniformGravityContribution.downward(UnigravConfig.worldGravityStrength)
                        .acceleration(),
                Boolean.TRUE);
    }

    private void applyRegistration() {
        // A disabled field is a zero field: the registration stays so that
        // /unigrav accel and downstream reads see an explicit value.
        Vec3 effective = data.isEnabled() ? data.getAcceleration() : Vec3.ZERO;
        registration = GravityFields.get().registerContribution(
                NAMESPACE, PATH, new UniformGravityContribution(effective));
    }
}
