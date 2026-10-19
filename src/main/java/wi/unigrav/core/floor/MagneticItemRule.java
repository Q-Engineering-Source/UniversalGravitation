package wi.unigrav.core.floor;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

/**
 * Immutable exact-registry rule for one magnetic item response.
 *
 * <p>A null metadata selector matches every metadata value. A null NBT pattern
 * accepts both tagged and untagged stacks. This class intentionally performs no
 * display-name, material, wildcard, or numeric-registry-ID matching.
 */
public final class MagneticItemRule {

    private final String registryId;
    private final String registryNamespace;
    private final String registryPath;
    private final Integer metadata;
    private final NbtPattern nbtPattern;
    private final double responseScalar;

    public MagneticItemRule(String registryId, Integer metadata, NbtPattern nbtPattern,
                            double responseScalar) {
        ResourceLocation location = ExactRegistryId.parse(registryId);
        this.registryNamespace = location.getNamespace();
        this.registryPath = location.getPath();
        this.registryId = registryNamespace + ":" + registryPath;
        this.metadata = metadata;
        this.nbtPattern = nbtPattern;
        if (!Double.isFinite(responseScalar) || responseScalar < 0.0) {
            throw new IllegalArgumentException("responseScalar must be finite and nonnegative");
        }
        this.responseScalar = responseScalar;
    }

    public String registryId() {
        return registryId;
    }

    /** Returns the exact metadata selector, or null when every metadata value is accepted. */
    public Integer metadata() {
        return metadata;
    }

    /** Returns the compiled optional NBT predicate, or null when no NBT is required. */
    public NbtPattern nbtPattern() {
        return nbtPattern;
    }

    public double responseScalar() {
        return responseScalar;
    }

    /** Returns whether a nonempty stack satisfies this exact item, metadata, and NBT rule. */
    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!matchesItemAndMetadata(stack, stack.getItem().getRegistryName(), stack.getMetadata())) return false;
        return nbtPattern == null || matchesTag(stack.getTagCompound());
    }

    boolean matchesItemAndMetadata(ItemStack stack, ResourceLocation itemName, int stackMetadata) {
        return stack != null && !stack.isEmpty() && itemName != null
                && registryNamespace.equals(itemName.getNamespace())
                && registryPath.equals(itemName.getPath())
                && (metadata == null || metadata.intValue() == stackMetadata);
    }

    boolean requiresNbt() {
        return nbtPattern != null;
    }

    boolean matchesTag(NBTTagCompound actualTag) {
        return nbtPattern == null || nbtPattern.matches(actualTag);
    }

}
