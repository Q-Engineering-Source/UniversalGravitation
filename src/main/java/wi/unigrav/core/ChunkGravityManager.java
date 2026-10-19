package wi.unigrav.core;

import net.minecraft.server.MinecraftServer;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.math.Vec3;

/**
 * Owns the chunk gravity overrides: on each server-world load the persisted
 * {@link ChunkGravityData} columns are registered into their world field as
 * {@code unigrav:chunk/<x>/<z>} contributions (each applying inside its
 * 16×16 column at any height); on unload they are all unregistered.
 * Changes go through {@link #set}, which persists and swaps the
 * registration.
 */
public final class ChunkGravityManager {

    private ChunkGravityData data;
    private WorldServer owner;
    private final Map<WorldServer, ChunkGravityManager> worlds = new IdentityHashMap<>();

    private ChunkGravityManager primary() {
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

    /** chunk column key → live registration. */
    private final Map<Long, GravityField.Registration> registrations = new HashMap<>();

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
        ChunkGravityManager context = new ChunkGravityManager();
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
        ChunkGravityManager context = worlds.remove(event.getWorld());
        if (context != null) context.unload();
    }


    private void load() {
        data = ChunkGravityData.get(owner);
        data.snapshot().forEach((key, accel) -> registrations.put(key,
                registerContribution((int) (long) key, (int) (key >> 32), accel)));
    }
    private void unload() {
        registrations.values().forEach(GravityField.Registration::close);
        registrations.clear();
        data = null;
    }

    /** Whether a world is loaded and chunk overrides can be changed. */
    private boolean localIsLoaded() {
        return data != null;
    }

    /** The override for a chunk column, or null. */
    private Vec3 localGet(int chunkX, int chunkZ) {
        return data != null ? data.get(chunkX, chunkZ) : null;
    }

    /** Sets (null clears) one chunk column's gravity override. */
    private boolean localSet(int chunkX, int chunkZ, Vec3 acceleration) {
        if (data == null) {
            return false;
        }
        data.set(chunkX, chunkZ, acceleration);
        long key = chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
        GravityField.Registration old = registrations.remove(key);
        if (old != null) {
            old.close();
        }
        if (acceleration != null) {
            registrations.put(key, registerContribution(chunkX, chunkZ, acceleration));
        }
        return true;
    }

    private GravityField.Registration registerContribution(
            int chunkX, int chunkZ, Vec3 accel) {
        return AmbientWorldFields.field(owner).registerContribution(Reference.MOD_ID,
                "chunk/" + chunkX + "/" + chunkZ,
                new ChunkGravityContribution(chunkX, chunkZ, accel));
    }

    /** Acceleration applied inside one 16×16 chunk column (any height). */
    public record ChunkGravityContribution(int chunkX, int chunkZ, Vec3 acceleration)
            implements GravityContribution {
        @Override
        public Vec3 accelerationAt(Vec3 pos, double time) {
            boolean inside = (int) Math.floor(pos.x()) >> 4 == chunkX
                    && (int) Math.floor(pos.z()) >> 4 == chunkZ;
            return inside ? acceleration : Vec3.ZERO;
        }
    }
    public boolean isLoaded() {
        ChunkGravityManager context = primary();
        return context == null ? false : context.localIsLoaded();
    }
    public boolean isLoaded(WorldServer world) {
        ChunkGravityManager context = worlds.get(world);
        return context == null ? false : context.localIsLoaded();
    }
    public Vec3 get(int chunkX, int chunkZ) {
        ChunkGravityManager context = primary();
        return context == null ? null : context.localGet(chunkX, chunkZ);
    }
    public Vec3 get(WorldServer world, int chunkX, int chunkZ) {
        ChunkGravityManager context = worlds.get(world);
        return context == null ? null : context.localGet(chunkX, chunkZ);
    }
    public boolean set(int chunkX, int chunkZ, Vec3 acceleration) {
        ChunkGravityManager context = primary();
        return context == null ? false : context.localSet(chunkX, chunkZ, acceleration);
    }
    public boolean set(WorldServer world, int chunkX, int chunkZ, Vec3 acceleration) {
        ChunkGravityManager context = worlds.get(world);
        return context == null ? false : context.localSet(chunkX, chunkZ, acceleration);
    }
}
