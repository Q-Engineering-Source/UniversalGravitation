package wi.unigrav.core;

import net.minecraft.server.MinecraftServer;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.RegionGravityContribution;

/**
 * Owns the gravity regions: on each server-world load the persisted
 * {@link RegionGravityData} regions are registered into their world field as
 * {@code unigrav:region/<name>} contributions; on unload they are all
 * unregistered. Changes go through {@link #add}/{@link #remove}/
 * {@link #clear}, which persist and swap the registration. Regions stack
 * additively with world and chunk gravity.
 */
public final class RegionGravityManager {

    private RegionGravityData data;
    private WorldServer owner;
    private final Map<WorldServer, RegionGravityManager> worlds = new IdentityHashMap<>();

    private RegionGravityManager primary() {
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
        if (world.isRemote || !(world instanceof WorldServer serverWorld)) return;
        if (worlds.containsKey(serverWorld)) return;
        AmbientWorldFields.prepare(serverWorld);
        RegionGravityManager context = new RegionGravityManager();
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
        RegionGravityManager context = worlds.remove(event.getWorld());
        if (context != null) context.unload();
    }


    private void load() {
        data = RegionGravityData.get(owner);
        data.snapshot().forEach((name, region) ->
                registrations.put(name, registerContribution(name, region)));
    }
    private void unload() {
        registrations.values().forEach(GravityField.Registration::close);
        registrations.clear();
        data = null;
    }

    /** Whether a world is loaded and regions can be changed. */
    private boolean localIsLoaded() {
        return data != null;
    }

    /** All regions as an immutable snapshot (insertion order). */
    private Map<String, RegionGravityContribution> localRegions() {
        return data != null ? data.snapshot() : Map.of();
    }

    /**
     * Adds a region under a fresh auto name ("r1", "r2", …); returns the
     * name, or null when no world is loaded.
     */
    private String localAdd(RegionGravityContribution region) {
        if (data == null) {
            return null;
        }
        String name = data.nextName();
        data.put(name, region);
        registrations.put(name, registerContribution(name, region));
        return name;
    }

    /** Removes a region by name; false when it did not exist. */
    private boolean localRemove(String name) {
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
    private int localClear() {
        if (data == null) {
            return -1;
        }
        int count = data.clear();
        registrations.values().forEach(GravityField.Registration::close);
        registrations.clear();
        return count;
    }

    private GravityField.Registration registerContribution(
            String name, RegionGravityContribution region) {
        return AmbientWorldFields.field(owner).registerContribution(Reference.MOD_ID,
                "region/" + name, region);
    }
    public boolean isLoaded() {
        RegionGravityManager context = primary();
        return context == null ? false : context.localIsLoaded();
    }
    public boolean isLoaded(WorldServer world) {
        RegionGravityManager context = worlds.get(world);
        return context == null ? false : context.localIsLoaded();
    }
    public Map<String, RegionGravityContribution> regions() {
        RegionGravityManager context = primary();
        return context == null ? Map.of() : context.localRegions();
    }
    public Map<String, RegionGravityContribution> regions(WorldServer world) {
        RegionGravityManager context = worlds.get(world);
        return context == null ? Map.of() : context.localRegions();
    }
    public String add(RegionGravityContribution region) {
        RegionGravityManager context = primary();
        return context == null ? null : context.localAdd(region);
    }
    public String add(WorldServer world, RegionGravityContribution region) {
        RegionGravityManager context = worlds.get(world);
        return context == null ? null : context.localAdd(region);
    }
    public boolean remove(String name) {
        RegionGravityManager context = primary();
        return context == null ? false : context.localRemove(name);
    }
    public boolean remove(WorldServer world, String name) {
        RegionGravityManager context = worlds.get(world);
        return context == null ? false : context.localRemove(name);
    }
    public int clear() {
        RegionGravityManager context = primary();
        return context == null ? -1 : context.localClear();
    }
    public int clear(WorldServer world) {
        RegionGravityManager context = worlds.get(world);
        return context == null ? -1 : context.localClear();
    }
}
