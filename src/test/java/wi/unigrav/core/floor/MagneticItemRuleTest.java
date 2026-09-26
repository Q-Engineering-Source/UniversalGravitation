package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MagneticItemRuleTest {

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void matchesOnlyTheExactNamespacedRegistryIdAndOptionalMetadata() {
        MagneticItemRule anyDyeMetadata = new MagneticItemRule("minecraft:dye", null, null, 0.5);
        assertTrue(anyDyeMetadata.matches(new ItemStack(Items.DYE, 1, 4)));
        assertTrue(anyDyeMetadata.matches(new ItemStack(Items.DYE, 1, 1)));

        MagneticItemRule exactDyeMetadata = new MagneticItemRule("minecraft:dye", 4, null, 0.5);
        assertTrue(exactDyeMetadata.matches(new ItemStack(Items.DYE, 1, 4)));
        assertFalse(exactDyeMetadata.matches(new ItemStack(Items.DYE, 1, 1)));

        ItemStack renamedGold = new ItemStack(Items.GOLD_INGOT);
        renamedGold.setStackDisplayName("minecraft:iron_ingot");
        assertFalse(new MagneticItemRule("minecraft:iron_ingot", null, null, 1.0)
                .matches(renamedGold));
        assertFalse(anyDyeMetadata.matches(ItemStack.EMPTY));
    }

    @Test
    void delegatesOptionalNbtMatchingWithoutTreatingAbsenceAsAMatch() {
        NBTTagCompound requirement = new NBTTagCompound();
        requirement.setString("magnet", "active");
        MagneticItemRule rule = new MagneticItemRule("minecraft:iron_ingot", null,
                NbtPattern.compile(requirement), 2.0);

        ItemStack matching = new ItemStack(Items.IRON_INGOT);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("magnet", "active");
        tag.setInteger("extra", 3);
        matching.setTagCompound(tag);
        assertTrue(rule.matches(matching));
        assertFalse(rule.matches(new ItemStack(Items.IRON_INGOT)));
    }

    @Test
    void rejectsBareNumericWildcardAndNoncanonicalIdsAndInvalidResponseScalars() {
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("iron_ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("265", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:*", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("Minecraft:iron_ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule(":iron_ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:iron ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:iron#ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:iron:ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("mine/craft:iron_ingot", null, null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:iron_ingot", null, null, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new MagneticItemRule("minecraft:iron_ingot", null, null,
                        Double.NaN));

        MagneticItemRule rule = new MagneticItemRule("minecraft:iron_ingot", null, null, 0.0);
        assertEquals("minecraft:iron_ingot", rule.registryId());
        assertEquals(0.0, rule.responseScalar());
    }
}
