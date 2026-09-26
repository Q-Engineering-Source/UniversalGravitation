package wi.unigrav.core;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import wi.unigrav.api.math.Vec3;

/**
 * Per-world persisted chunk gravity overrides: chunk column (x, z) →
 * acceleration vector (m/s²), spanning all heights. Stored in the
 * overworld's map storage; like everything else in the current field model
 * the overrides are dimension-agnostic (same x/z column in every dimension).
 */
public final class ChunkGravityData extends WorldSavedData {

    public static final String DATA_NAME = "unigrav_chunk_gravity";

    /** ChunkPos-long key → acceleration. */
    private final Map<Long, Vec3> columns = new HashMap<>();

    public ChunkGravityData() {
        super(DATA_NAME);
    }

    @SuppressWarnings("unused") // required by MapStorage's load path
    public ChunkGravityData(String name) {
        super(name);
    }

    /** Returns the data attached to the overworld's map storage. */
    public static ChunkGravityData get(WorldServer overworld) {
        MapStorage storage = overworld.getMapStorage();
        ChunkGravityData data = (ChunkGravityData) storage.getOrLoadData(
                ChunkGravityData.class, DATA_NAME);
        if (data == null) {
            data = new ChunkGravityData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    private static long key(int chunkX, int chunkZ) {
        return chunkX & 0xFFFFFFFFL | ((long) chunkZ & 0xFFFFFFFFL) << 32;
    }

    /** All overrides as an immutable snapshot. */
    public Map<Long, Vec3> snapshot() {
        return Map.copyOf(columns);
    }

    /** The override for a chunk column, or null. */
    public Vec3 get(int chunkX, int chunkZ) {
        return columns.get(key(chunkX, chunkZ));
    }

    /** Sets (or clears, with null) one chunk column's override. */
    public void set(int chunkX, int chunkZ, Vec3 acceleration) {
        if (acceleration == null) {
            columns.remove(key(chunkX, chunkZ));
        } else {
            columns.put(key(chunkX, chunkZ), acceleration);
        }
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        columns.clear();
        NBTTagList list = nbt.getTagList("columns", 10); // 10 = compound
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            columns.put(key(entry.getInteger("cx"), entry.getInteger("cz")),
                    new Vec3(entry.getDouble("ax"), entry.getDouble("ay"),
                            entry.getDouble("az")));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        columns.forEach((key, accel) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("cx", (int) (long) key);
            entry.setInteger("cz", (int) (key >> 32));
            entry.setDouble("ax", accel.x());
            entry.setDouble("ay", accel.y());
            entry.setDouble("az", accel.z());
            list.appendTag(entry);
        });
        nbt.setTag("columns", list);
        return nbt;
    }
}
