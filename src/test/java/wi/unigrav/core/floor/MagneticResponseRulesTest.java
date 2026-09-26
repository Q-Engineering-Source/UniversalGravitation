package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MagneticResponseRulesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void builtInCoreMaterialsAreExplicitAndStackCountDoesNotMultiplyResponse() {
        var rules = MagneticResponseRules.builtIn();
        assertEquals(1.0, rules.droppedItemResponse(new ItemStack(Items.IRON_INGOT, 64)));
        assertEquals(1.0, rules.droppedItemResponse(new ItemStack(Items.IRON_NUGGET)));
        assertEquals(1.0, rules.droppedItemResponse(new ItemStack(Blocks.IRON_BLOCK)));
        assertEquals(1.0, rules.droppedItemResponse(new ItemStack(Items.IRON_BOOTS)));
        assertEquals(0.0, rules.droppedItemResponse(new ItemStack(Items.GOLD_INGOT)));
        assertEquals(0.0, rules.droppedItemResponse(ItemStack.EMPTY));
    }

    @Test
    void onlyCreativePersonalGShieldsBootResponse() {
        var rules = MagneticResponseRules.builtIn();
        var boots = new ItemStack(Items.IRON_BOOTS);
        assertEquals(0.0, rules.playerResponse(boots, true, true));
        assertEquals(1.0, rules.playerResponse(boots, true, false));
        assertEquals(1.0, rules.playerResponse(boots, false, true));
        assertEquals(1.0, rules.playerResponse(boots, false, false));
        assertEquals(0.0, rules.playerResponse(new ItemStack(Items.LEATHER_BOOTS), false, false));
        assertEquals(0.0, rules.playerResponse(ItemStack.EMPTY, false, true));
    }

    @Test
    void matchingRulesUseMaximumMaterialResponseWhileFloorVectorsRemainSeparate() {
        var condition = new NBTTagCompound();
        condition.setInteger("magneticTier", 2);
        var rules = new MagneticResponseRules(List.of(
                new MagneticItemRule("minecraft:iron_ingot", null, null, 0.5),
                new MagneticItemRule("minecraft:iron_ingot", null, NbtPattern.compile(condition), 2.0)), List.of());
        var item = new ItemStack(Items.IRON_INGOT);
        assertEquals(0.5, rules.droppedItemResponse(item));
        item.setTagCompound(condition.copy());
        assertEquals(2.0, rules.droppedItemResponse(item));
        item.getTagCompound().setByte("magneticTier", (byte) 2);
        assertEquals(0.5, rules.droppedItemResponse(item));
        assertEquals(0.0, rules.playerResponse(item, false, false));
    }

    @Test
    void catalogsOwnTheirListsAndDoNotReinterpretDropRulesAsWornBoots() {
        var source = new ArrayList<MagneticItemRule>();
        source.add(new MagneticItemRule("minecraft:iron_ingot", null, null, 1.0));
        var rules = new MagneticResponseRules(source, List.of());
        source.clear();
        assertEquals(1.0, rules.droppedItemResponse(new ItemStack(Items.IRON_INGOT)));
        assertEquals(0.0, rules.playerResponse(new ItemStack(Items.IRON_INGOT), false, false));
        assertThrows(NullPointerException.class, () -> rules.response(null, false));
    }
}
