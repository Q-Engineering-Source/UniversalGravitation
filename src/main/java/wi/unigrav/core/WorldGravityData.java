package wi.unigrav.core;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import wi.unigrav.api.math.Vec3;

/**
 * Per-world persisted world-gravity setting: the acceleration vector (m/s²)
 * of the uniform gravity contribution registered for this world, plus an
 * enabled flag. Stored in the server map storage under a dimension-specific key; dimension zero
 * retains its original key and serialized format.
 *
 * <p>{@code initialized} distinguishes "new world, seed from config" from
 * "world with a deliberate setting" so that later config changes never
 * clobber an operator's in-game changes.
 */
public final class WorldGravityData extends WorldSavedData {

    public static final String DATA_NAME = "unigrav_world_gravity";

    private boolean initialized;
    private boolean enabled = true;
    private double ax;
    private double ay = -32.0;
    private double az;

    public WorldGravityData() {
        super(DATA_NAME);
    }

    @SuppressWarnings("unused") // required by MapStorage's load path
    public WorldGravityData(String name) {
        super(name);
    }

    /** Returns this dimension's persisted data; field ownership remains the actual World object. */
    public static WorldGravityData get(WorldServer overworld) {
        MapStorage storage = overworld.getMapStorage();
        String key = overworld.provider.getDimension() == 0 ? DATA_NAME
                : DATA_NAME + "_dimension_" + overworld.provider.getDimension();
        WorldGravityData data = (WorldGravityData) storage.getOrLoadData(
                WorldGravityData.class, key);
        if (data == null) {
            data = new WorldGravityData(key);
            storage.setData(key, data);
        }
        return data;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
        markDirty();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        markDirty();
    }

    /** The world gravity acceleration, in m/s². */
    public Vec3 getAcceleration() {
        return new Vec3(ax, ay, az);
    }

    public void setAcceleration(Vec3 acceleration) {
        ax = acceleration.x();
        ay = acceleration.y();
        az = acceleration.z();
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        initialized = nbt.getBoolean("initialized");
        enabled = nbt.getBoolean("enabled");
        ax = nbt.getDouble("ax");
        ay = nbt.getDouble("ay");
        az = nbt.getDouble("az");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setBoolean("initialized", initialized);
        nbt.setBoolean("enabled", enabled);
        nbt.setDouble("ax", ax);
        nbt.setDouble("ay", ay);
        nbt.setDouble("az", az);
        return nbt;
    }
}
