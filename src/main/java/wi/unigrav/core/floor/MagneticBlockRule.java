package wi.unigrav.core.floor;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import net.minecraft.block.Block;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Compiled exact block ID, listed BlockState properties and optional real TE NBT.
 *
 * <p>Compile after the block registry is ready, and recompile if that registry
 * changes. Unknown IDs/properties/values fail fast, never falling back to AIR.
 * Matching only classifies material; it does not create a floor field, a collision
 * surface, attachment, or actor eligibility. The world owner supplies the selected
 * stored/evaluated state and, if required, actual TileEntity serialization.
 */
public final class MagneticBlockRule {
    private final String registryId;
    private final Block block;
    private final Map<String, String> stateProperties;
    private final Map<IProperty<?>, Comparable<?>> requiredValues;
    private final NbtPattern tileEntityPattern;

    public MagneticBlockRule(String registryId, Map<String, String> stateProperties, NbtPattern tileEntityPattern) {
        ResourceLocation location = ExactRegistryId.parse(registryId);
        if (!Block.REGISTRY.containsKey(location)) {
            throw new IllegalArgumentException("unknown magnetic block registry ID: " + location);
        }
        this.registryId = location.toString();
        this.block = Objects.requireNonNull(Block.REGISTRY.getObject(location), "registered block");
        this.stateProperties = Map.copyOf(stateProperties);
        this.tileEntityPattern = tileEntityPattern;
        Map<String, IProperty<?>> available = new HashMap<>();
        for (IProperty<?> property : block.getDefaultState().getPropertyKeys()) {
            available.put(property.getName(), property);
        }
        Map<IProperty<?>, Comparable<?>> required = new HashMap<>();
        for (Map.Entry<String, String> entry : this.stateProperties.entrySet()) {
            IProperty<?> property = available.get(entry.getKey());
            if (property == null) {
                throw new IllegalArgumentException("unknown property " + entry.getKey() + " for " + this.registryId);
            }
            required.put(property, parseValue(property, entry.getValue()));
        }
        requiredValues = Map.copyOf(required);
    }

    public String registryId() {
        return registryId;
    }

    public Map<String, String> stateProperties() {
        return stateProperties;
    }

    /** Allows a world reader to avoid serializing TE data for an unconditional rule. */
    public boolean requiresTileEntityNbt() {
        return tileEntityPattern != null;
    }

    /** No World/Chunk/TE access: only the supplied immutable BlockState is inspected. */
    public boolean matchesState(IBlockState state) {
        if (state == null || state.getBlock() != block) {
            return false;
        }
        for (Map.Entry<IProperty<?>, Comparable<?>> required : requiredValues.entrySet()) {
            if (!required.getValue().equals(state.getProperties().get(required.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Null NBT means no serialized TE exists; even an empty required pattern must not match it. */
    public boolean matches(IBlockState state, NBTTagCompound tileEntityNbt) {
        return matchesState(state) && (tileEntityPattern == null || tileEntityPattern.matches(tileEntityNbt));
    }

    private static <T extends Comparable<T>> T parseValue(IProperty<T> property, String value) {
        var parsed = property.parseValue(value);
        if (!parsed.isPresent() || !property.getName(parsed.get()).equals(value)) {
            throw new IllegalArgumentException("invalid canonical value " + value + " for property " + property.getName());
        }
        return parsed.get();
    }
}
