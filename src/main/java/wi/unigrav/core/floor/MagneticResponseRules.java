package wi.unigrav.core.floor;

import java.util.List;
import java.util.Objects;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

import wi.unigrav.core.BodyPhysics;

/** Actor material response, independent of gravity binding and floor-vector composition. */
public final class MagneticResponseRules {
    private static final MagneticResponseRules BUILT_IN = new MagneticResponseRules(
            List.of(rule("minecraft:iron_ingot"), rule("minecraft:iron_nugget"),
                    rule("minecraft:iron_block"), rule("minecraft:iron_boots")),
            List.of(rule("minecraft:iron_boots")));

    private final List<MagneticItemRule> droppedItems;
    private final List<MagneticItemRule> playerBoots;

    public MagneticResponseRules(List<MagneticItemRule> droppedItems, List<MagneticItemRule> playerBoots) {
        this.droppedItems = List.copyOf(droppedItems);
        this.playerBoots = List.copyOf(playerBoots);
    }

    /** Minimal core-test defaults; JSON configuration and full preset catalogs follow core acceptance. */
    public static MagneticResponseRules builtIn() {
        return BUILT_IN;
    }

    /** The caller supplies actual personal-G intent; this rule never reads camera state or changes G. */
    public double response(Entity entity, boolean personalZeroG) {
        Objects.requireNonNull(entity, "entity");
        if (entity instanceof EntityPlayer player) {
            return playerResponse(player.getItemStackFromSlot(EntityEquipmentSlot.FEET),
                    player.capabilities.isCreativeMode, personalZeroG);
        }
        if (entity instanceof EntityItem item) {
            return droppedItemResponse(item.getItem());
        }
        return BodyPhysics.magneticResponse(entity);
    }

    /** Stack count is not extra material response: duplicate rules select the maximum, not a sum. */
    public double droppedItemResponse(ItemStack stack) {
        return maximumResponse(droppedItems, stack);
    }

    /** Creative shields magnetism only while personal G is active; Survival receives no such shield. */
    public double playerResponse(ItemStack boots, boolean creative, boolean personalZeroG) {
        return creative && personalZeroG ? 0.0 : maximumResponse(playerBoots, boots);
    }

    private static double maximumResponse(List<MagneticItemRule> rules, ItemStack stack) {
        double response = 0.0;
        for (MagneticItemRule rule : rules) {
            if (rule.matches(stack)) response = Math.max(response, rule.responseScalar());
        }
        return response;
    }

    private static MagneticItemRule rule(String id) {
        return new MagneticItemRule(id, null, null, 1.0);
    }
}
