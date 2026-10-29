package wi.unigrav.core.player;

import java.util.UUID;
import java.util.IdentityHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldSavedData;

/** Persisted dimension identity; a seed or a runtime world epoch cannot identify a saved pose. */
public final class PlayerPoseWorldIdentity extends WorldSavedData {
    private static final String NAME = "unigrav_player_pose_world";
    private static final IdentityHashMap<WorldServer, UUID> READY = new IdentityHashMap<>();
    private UUID identity;

    public PlayerPoseWorldIdentity(String name) { super(name); }

    static UUID get(WorldServer world) {
        requireThread(world);
        UUID identity = READY.get(world);
        if (identity == null) throw new IllegalStateException("UG player pose world identity is not ready");
        return identity;
    }

    static void forget(WorldServer world) { READY.remove(world); }

    private static void requireThread(WorldServer world) {
        if (!world.isCallingFromMinecraftThread())
            throw new IllegalStateException("Player pose identity requires the server world thread");
    }

    static void initialize(WorldServer world) {
        requireThread(world);
        if (READY.containsKey(world)) return;
        String key = NAME + "_dimension_" + world.provider.getDimension();
        var storage = world.getMapStorage();
        var data = (PlayerPoseWorldIdentity) storage.getOrLoadData(PlayerPoseWorldIdentity.class, key);
        if (data == null) {
            var file = world.getSaveHandler().getMapFileFromName(key);
            if (file != null && file.exists())
                throw new IllegalStateException("Cannot load existing UG player pose world identity: " + key);
            data = new PlayerPoseWorldIdentity(key);
            data.identity = UUID.randomUUID();
            storage.setData(key, data);
            data.markDirty();
        }
        if (data.identity == null)
            throw new IllegalArgumentException("Invalid persisted UG player pose world identity: " + key);
        READY.put(world, data.identity);
    }

    @Override public void readFromNBT(NBTTagCompound nbt) {
        identity = nbt.getInteger("schema") == 1 && nbt.hasUniqueId("identity")
                ? nbt.getUniqueId("identity") : null;
    }

    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        if (identity == null) throw new IllegalStateException("Uninitialized UG player pose world identity");
        nbt.setInteger("schema", 1);
        nbt.setUniqueId("identity", identity);
        return nbt;
    }
}
