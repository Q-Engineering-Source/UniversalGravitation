package wi.unigrav.core;

import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityMooshroom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeciesPhysicsCatalogTest {

    @Test
    void nearestMassRowWinsEvenWhenItsMagneticResponseIsZero() {
        SpeciesPhysicsCatalog catalog = new SpeciesPhysicsCatalog();
        BodyPhysics.SpeciesPreset cow = new BodyPhysics.SpeciesPreset(
                500.0, 0.15, true, true, false);
        BodyPhysics.SpeciesPreset mooshroom = new BodyPhysics.SpeciesPreset(
                650.0, 0.2, false, false, true);
        catalog.replace(EntityCow.class, cow, 1.0);
        assertSame(cow, catalog.nearest(EntityMooshroom.class).preset());
        assertEquals(1.0, catalog.nearest(EntityMooshroom.class).magneticResponse());
        catalog.replace(EntityMooshroom.class, mooshroom, 0.0);

        SpeciesPhysicsCatalog.Entry entry = catalog.nearest(EntityMooshroom.class);
        assertSame(mooshroom, entry.preset());
        assertEquals(650.0, entry.preset().massKg());
        assertEquals(0.2, entry.preset().relVariance());
        assertFalse(entry.preset().swims());
        assertFalse(entry.preset().affectedByGravity());
        assertTrue(entry.preset().zeroGPathing());
        assertEquals(0.0, entry.magneticResponse());
    }

    @Test
    void replacementResetsMassFlagsAndMagneticResponseTogether() {
        SpeciesPhysicsCatalog catalog = new SpeciesPhysicsCatalog();
        BodyPhysics.SpeciesPreset original = new BodyPhysics.SpeciesPreset(
                500.0, 0.15, true, true, false);
        BodyPhysics.SpeciesPreset replacement = new BodyPhysics.SpeciesPreset(
                4166.9, 0.05, false, true, false);
        catalog.replace(EntityCow.class, original, 1.0);
        catalog.replace(EntityCow.class, replacement, 0.0);

        SpeciesPhysicsCatalog.Entry entry = catalog.nearest(EntityCow.class);
        assertSame(replacement, entry.preset());
        assertEquals(4166.9, entry.preset().massKg());
        assertEquals(0.05, entry.preset().relVariance());
        assertFalse(entry.preset().swims());
        assertTrue(entry.preset().affectedByGravity());
        assertFalse(entry.preset().zeroGPathing());
        assertEquals(0.0, entry.magneticResponse());
    }

    @Test
    void noRegisteredSuperclassReturnsNull() {
        SpeciesPhysicsCatalog catalog = new SpeciesPhysicsCatalog();

        assertNull(catalog.nearest(EntityCow.class));
    }

    @Test
    void rejectsNonFiniteAndNegativeMagneticResponse() {
        SpeciesPhysicsCatalog catalog = new SpeciesPhysicsCatalog();
        BodyPhysics.SpeciesPreset preset = new BodyPhysics.SpeciesPreset(
                500.0, 0.15, true, true, false);

        assertThrows(IllegalArgumentException.class,
                () -> catalog.replace(EntityCow.class, preset, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.replace(EntityCow.class, preset, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> catalog.replace(EntityCow.class, preset, -0.1));
    }
}
