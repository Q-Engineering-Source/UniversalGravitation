package wi.unigrav;

import org.junit.jupiter.api.Test;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.config.EntityBindingRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBindingRuleTest {

    @Test
    void parseMode_isCaseInsensitive() {
        assertEquals(GravityMode.REPLACE, EntityBindingRule.parseMode("REPLACE"));
        assertEquals(GravityMode.REPLACE, EntityBindingRule.parseMode("replace"));
        assertEquals(GravityMode.ADDITIVE, EntityBindingRule.parseMode("Additive"));
    }

    @Test
    void parseMode_rejectsInvalid() {
        assertNull(EntityBindingRule.parseMode(null));
        assertNull(EntityBindingRule.parseMode("YEET"));
        assertNull(EntityBindingRule.parseMode(""));
        // The pseudo-mode is not a GravityMode; the loader handles it.
        assertNull(EntityBindingRule.parseMode("VANILLA"));
    }

    @Test
    void vanillaMode_isCaseInsensitivePseudoMode() {
        assertTrue(EntityBindingRule.isVanillaMode("VANILLA"));
        assertTrue(EntityBindingRule.isVanillaMode("vanilla"));
        assertFalse(EntityBindingRule.isVanillaMode(null));
        assertFalse(EntityBindingRule.isVanillaMode("REPLACE"));
    }

    @Test
    void entityName_validation() {
        assertTrue(EntityBindingRule.isValidEntityName("minecraft:item"));
        assertTrue(EntityBindingRule.isValidEntityName("item"));
        assertTrue(EntityBindingRule.isValidEntityName("some_mod:entity.name-1"));
        assertTrue(EntityBindingRule.isValidEntityName("*"));

        assertFalse(EntityBindingRule.isValidEntityName(null));
        assertFalse(EntityBindingRule.isValidEntityName(""));
        assertFalse(EntityBindingRule.isValidEntityName("Minecraft:Item"));
        assertFalse(EntityBindingRule.isValidEntityName("bad name"));
        assertFalse(EntityBindingRule.isValidEntityName(":item"));
        assertFalse(EntityBindingRule.isValidEntityName("minecraft:"));
    }
}
