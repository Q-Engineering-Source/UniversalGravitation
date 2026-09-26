package wi.unigrav.core;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import wi.unigrav.Reference;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.math.Vec3;

/**
 * Owns the chunk gravity overrides: on overworld load the persisted
 * {@link ChunkGravityData} columns are registered into the global field as
 * {@code unigrav:chunk/<x>/<z>} contributions (each applying inside its
 * 16×16 column at any height); on unload they are all unregistered.
 * Changes go through {@link #set}, which persists and swaps the
 * registration.
 */
public final class ChunkGravityManager {

    private ChunkGravityData data;
    /** chunk column key → live registration. */
    private final Map<Long, GravityField.Registration> registrations = new HashMap<>();

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
        data = ChunkGravityData.get((WorldServer) world);
        data.snapshot().forEach((key, accel) -> registrations.put(key,
                registerContribution((int) (long) key, (int) (key >> 32), accel)));
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

    /** Whether a world is loaded and chunk overrides can be changed. */
    public boolean isLoaded() {
        return data != null;
    }

    /** The override for a chunk column, or null. */
    public Vec3 get(int chunkX, int chunkZ) {
        return data != null ? data.get(chunkX, chunkZ) : null;
    }

    /** Sets (null clears) one chunk column's gravity override. */
    public boolean set(int chunkX, int chunkZ, Vec3 acceleration) {
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

    private static GravityField.Registration registerContribution(
            int chunkX, int chunkZ, Vec3 accel) {
        return GravityFields.get().registerContribution(Reference.MOD_ID,
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
}
