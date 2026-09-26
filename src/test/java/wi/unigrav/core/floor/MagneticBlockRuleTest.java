package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.BlockStairs;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MagneticBlockRuleTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void compilesExactNamesWithoutNumericOrUnknownAirFallback() {
        var rule = new MagneticBlockRule("minecraft:iron_block", Map.of(), null);
        assertEquals("minecraft:iron_block", rule.registryId());
        assertTrue(rule.matches(Blocks.IRON_BLOCK.getDefaultState(), null));
        assertFalse(rule.matches(Blocks.GOLD_BLOCK.getDefaultState(), null));
        assertFalse(rule.matches(Blocks.AIR.getDefaultState(), null));
        assertFalse(rule.matches(null, null));
        for (String id : new String[] {"42", "iron_block", "Minecraft:iron_block", "minecraft:*",
                "minecraft:iron block", "mine/craft:iron_block", "minecraft:iron:block", "minecraft:",
                ":iron_block", "minecraft:ug_missing_block_for_test"}) {
            assertThrows(IllegalArgumentException.class, () -> new MagneticBlockRule(id, Map.of(), null), id);
        }
    }

    @Test
    void listedPropertiesMatchExactlyWithoutTreatingUnspecifiedPropertiesAsRestrictions() {
        var rule = new MagneticBlockRule("minecraft:oak_stairs", Map.of("facing", "east", "half", "top"), null);
        var state = Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST)
                .withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.TOP);
        assertTrue(rule.matches(state, null));
        assertTrue(rule.matches(state.withProperty(BlockStairs.SHAPE, BlockStairs.EnumShape.INNER_LEFT), null));
        assertFalse(rule.matches(state.withProperty(BlockStairs.FACING, EnumFacing.WEST), null));
        assertFalse(rule.matches(state.withProperty(BlockStairs.HALF, BlockStairs.EnumHalf.BOTTOM), null));
        var shape = new MagneticBlockRule("minecraft:oak_stairs", Map.of("shape", "inner_left"), null);
        assertFalse(shape.matches(state, null));
        assertTrue(shape.matches(state.withProperty(BlockStairs.SHAPE, BlockStairs.EnumShape.INNER_LEFT), null));
    }

    @Test
    void unknownPropertiesAndInvalidValuesFailAtCompilation() {
        for (Map<String, String> selector : List.of(Map.of("missing", "east"),
                Map.of("facing", "up"), Map.of("facing", "EAST"), Map.of("half", "upper"),
                Map.of("half", " top"), Map.of("", "top"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MagneticBlockRule("minecraft:oak_stairs", selector, null));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticBlockRule("minecraft:iron_block", Map.of("facing", "east"), null));
    }

    @Test
    void callerMutationCannotChangeCompiledSelectors() {
        Map<String, String> selectors = new HashMap<>();
        selectors.put("facing", "east");
        var rule = new MagneticBlockRule("minecraft:oak_stairs", selectors, null);
        selectors.put("facing", "west");
        assertEquals(Map.of("facing", "east"), rule.stateProperties());
        assertThrows(UnsupportedOperationException.class, () -> rule.stateProperties().put("half", "top"));
        assertTrue(rule.matchesState(Blocks.OAK_STAIRS.getDefaultState().withProperty(BlockStairs.FACING, EnumFacing.EAST)));
    }

    @Test
    void realTileEntitySerializationRetainsCompoundListArrayAndNativeTagTypes() {
        TileEntityFurnace furnace = new TileEntityFurnace();
        ItemStack item = new ItemStack(Items.IRON_INGOT);
        NBTTagCompound itemTag = new NBTTagCompound();
        itemTag.setByteArray("magnetic_mask", new byte[] {1, 2});
        itemTag.setIntArray("magnetic_poles", new int[] {3, 4});
        item.setTagCompound(itemTag);
        furnace.setInventorySlotContents(0, item);
        NBTTagCompound serialized = furnace.writeToNBT(new NBTTagCompound());
        NBTTagCompound required = new NBTTagCompound();
        required.setTag("Items", serialized.getTag("Items").copy());
        var rule = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(required));
        assertTrue(rule.requiresTileEntityNbt());
        assertTrue(rule.matches(Blocks.FURNACE.getDefaultState(), serialized));
        assertFalse(rule.matches(Blocks.FURNACE.getDefaultState(), null));
        assertFalse(rule.matches(Blocks.IRON_BLOCK.getDefaultState(), serialized));
        furnace.setInventorySlotContents(0, new ItemStack(Items.GOLD_INGOT));
        assertFalse(rule.matches(Blocks.FURNACE.getDefaultState(), furnace.writeToNBT(new NBTTagCompound())));
        assertTrue(rule.matches(Blocks.FURNACE.getDefaultState(), serialized));
    }

    @Test
    void emptyNbtRequirementIsNotFabricatedForBlocksWithoutTileEntities() {
        var rule = new MagneticBlockRule("minecraft:iron_block", Map.of(), NbtPattern.compile(new NBTTagCompound()));
        assertTrue(rule.matchesState(Blocks.IRON_BLOCK.getDefaultState()));
        assertFalse(rule.matches(Blocks.IRON_BLOCK.getDefaultState(), null));
    }
}
