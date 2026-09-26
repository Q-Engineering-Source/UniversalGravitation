package wi.unigrav.core;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import wi.unigrav.api.field.RegionGravityContribution;
import wi.unigrav.api.field.RegionGravityContribution.Box;
import wi.unigrav.api.field.RegionGravityContribution.Centripetal;
import wi.unigrav.api.field.RegionGravityContribution.Cylinder;
import wi.unigrav.api.field.RegionGravityContribution.Directional;
import wi.unigrav.api.field.RegionGravityContribution.Mode;
import wi.unigrav.api.field.RegionGravityContribution.Shape;
import wi.unigrav.api.field.RegionGravityContribution.Sphere;
import wi.unigrav.api.math.Vec3;

/**
 * Per-world persisted gravity regions: name → contribution (shape + mode).
 * Stored in the overworld's map storage; like everything else in the
 * current field model the regions are dimension-agnostic (same coordinates
 * in every dimension).
 */
public final class RegionGravityData extends WorldSavedData {

    public static final String DATA_NAME = "unigrav_region_gravity";

    private final Map<String, RegionGravityContribution> regions = new LinkedHashMap<>();
    /** Auto-name counter ("r1", "r2", …); persisted so names never collide. */
    private int nextId = 1;

    public RegionGravityData() {
        super(DATA_NAME);
    }

    @SuppressWarnings("unused") // required by MapStorage's load path
    public RegionGravityData(String name) {
        super(name);
    }

    /** Returns the data attached to the overworld's map storage. */
    public static RegionGravityData get(WorldServer overworld) {
        MapStorage storage = overworld.getMapStorage();
        RegionGravityData data = (RegionGravityData) storage.getOrLoadData(
                RegionGravityData.class, DATA_NAME);
        if (data == null) {
            data = new RegionGravityData();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /** All regions as an immutable snapshot (insertion order). */
    public Map<String, RegionGravityContribution> snapshot() {
        return Map.copyOf(regions);
    }

    /** Allocates a fresh auto name. */
    public String nextName() {
        String name;
        do {
            name = "r" + nextId++;
        } while (regions.containsKey(name));
        markDirty();
        return name;
    }

    /** Adds or replaces a region. */
    public void put(String name, RegionGravityContribution region) {
        regions.put(name, region);
        markDirty();
    }

    /** Removes a region; false when it did not exist. */
    public boolean remove(String name) {
        if (regions.remove(name) == null) {
            return false;
        }
        markDirty();
        return true;
    }

    /** Removes every region; returns how many there were. */
    public int clear() {
        int count = regions.size();
        regions.clear();
        markDirty();
        return count;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        regions.clear();
        nextId = nbt.getInteger("nextId");
        if (nextId <= 0) {
            nextId = 1;
        }
        NBTTagList list = nbt.getTagList("regions", 10); // 10 = compound
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            regions.put(entry.getString("name"), readRegion(entry));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setInteger("nextId", nextId);
        NBTTagList list = new NBTTagList();
        regions.forEach((name, region) -> {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("name", name);
            writeRegion(entry, region);
            list.appendTag(entry);
        });
        nbt.setTag("regions", list);
        return nbt;
    }

    private static RegionGravityContribution readRegion(NBTTagCompound entry) {
        Shape shape = switch (entry.getString("shape")) {
            case "sphere" -> new Sphere(readVec(entry, "c"), entry.getDouble("r"));
            case "cylinder" -> new Cylinder(readVec(entry, "a"), readVec(entry, "b"),
                    entry.getDouble("r"));
            default -> new Box(readVec(entry, "min"), readVec(entry, "max"));
        };
        Mode mode = entry.getString("mode").equals("center")
                ? new Centripetal(entry.getDouble("strength"))
                : new Directional(readVec(entry, "g"));
        return new RegionGravityContribution(shape, mode);
    }

    private static void writeRegion(NBTTagCompound entry, RegionGravityContribution region) {
        switch (region.shape()) {
            case Sphere sphere -> {
                entry.setString("shape", "sphere");
                writeVec(entry, "c", sphere.center());
                entry.setDouble("r", sphere.radius());
            }
            case Cylinder cylinder -> {
                entry.setString("shape", "cylinder");
                writeVec(entry, "a", cylinder.axisA());
                writeVec(entry, "b", cylinder.axisB());
                entry.setDouble("r", cylinder.radius());
            }
            case Box box -> {
                entry.setString("shape", "box");
                writeVec(entry, "min", box.min());
                writeVec(entry, "max", box.max());
            }
            default -> throw new IllegalArgumentException(
                    "unknown shape " + region.shape());
        }
        switch (region.mode()) {
            case Centripetal centripetal -> {
                entry.setString("mode", "center");
                entry.setDouble("strength", centripetal.strength());
            }
            case Directional directional -> {
                entry.setString("mode", "dir");
                writeVec(entry, "g", directional.accel());
            }
            default -> throw new IllegalArgumentException("unknown mode " + region.mode());
        }
    }

    private static Vec3 readVec(NBTTagCompound entry, String prefix) {
        return new Vec3(entry.getDouble(prefix + "x"), entry.getDouble(prefix + "y"),
                entry.getDouble(prefix + "z"));
    }

    private static void writeVec(NBTTagCompound entry, String prefix, Vec3 v) {
        entry.setDouble(prefix + "x", v.x());
        entry.setDouble(prefix + "y", v.y());
        entry.setDouble(prefix + "z", v.z());
    }
}
