package wi.unigrav.core;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.field.RegionGravityContribution;

/**
 * Owns the gravity regions: on overworld load the persisted
 * {@link RegionGravityData} regions are registered into the global field as
 * {@code unigrav:region/<name>} contributions; on unload they are all
 * unregistered. Changes go through {@link #add}/{@link #remove}/
 * {@link #clear}, which persist and swap the registration. Regions stack
 * additively with world and chunk gravity.
 */
public final class RegionGravityManager {

    private RegionGravityData data;
    /** region name → live registration. */
    private final Map<String, GravityField.Registration> registrations =
            new LinkedHashMap<>();

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
        data = RegionGravityData.get((WorldServer) world);
        data.snapshot().forEach((name, region) ->
                registrations.put(name, registerContribution(name, region)));
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world.isRemote || world.provider.getDimension() != 0) {
            return;
        }
        registrations.values().forEach(GravityField.Registration::close);
        registrations.clear();
        data = null;
    }

    /** Whether a world is loaded and regions can be changed. */
    public boolean isLoaded() {
        return data != null;
    }

    /** All regions as an immutable snapshot (insertion order). */
    public Map<String, RegionGravityContribution> regions() {
        return data != null ? data.snapshot() : Map.of();
    }

    /**
     * Adds a region under a fresh auto name ("r1", "r2", …); returns the
     * name, or null when no world is loaded.
     */
    public String add(RegionGravityContribution region) {
        if (data == null) {
            return null;
        }
        String name = data.nextName();
        data.put(name, region);
        registrations.put(name, registerContribution(name, region));
        return name;
    }

    /** Removes a region by name; false when it did not exist. */
    public boolean remove(String name) {
        if (data == null || !data.remove(name)) {
            return false;
        }
        GravityField.Registration old = registrations.remove(name);
        if (old != null) {
            old.close();
        }
        return true;
    }

    /** Removes every region; returns how many there were (-1 when unloaded). */
    public int clear() {
        if (data == null) {
            return -1;
        }
        int count = data.clear();
        registrations.values().forEach(GravityField.Registration::close);
        registrations.clear();
        return count;
    }

    private static GravityField.Registration registerContribution(
            String name, RegionGravityContribution region) {
        return GravityFields.get().registerContribution(Reference.MOD_ID,
                "region/" + name, region);
    }
}
