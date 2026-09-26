package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MagneticBlockReaderTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void unconditionalMatchDoesNotSerializeEvenWhenAnotherMatchingRuleNeedsNbt() {
        AtomicInteger reads = new AtomicInteger();
        var nbt = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(new NBTTagCompound()));
        var unconditional = new MagneticBlockRule("minecraft:furnace", Map.of(), null);
        assertTrue(MagneticBlockReader.matchesStored(Blocks.FURNACE.getDefaultState(), () -> {
            reads.incrementAndGet();
            throw new AssertionError("unnecessary TE serialization");
        }, List.of(nbt, unconditional)));
        assertEquals(0, reads.get());
    }

    @Test
    void stateMismatchDoesNotRequestOrInventTileEntityData() {
        AtomicInteger reads = new AtomicInteger();
        var furnace = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(new NBTTagCompound()));
        assertFalse(MagneticBlockReader.matchesStored(Blocks.IRON_BLOCK.getDefaultState(), () -> {
            reads.incrementAndGet();
            return new NBTTagCompound();
        }, List.of(furnace)));
        assertEquals(0, reads.get());
    }

    @Test
    void multipleNbtRulesUseOneRealSerializationAndDoNotCoerceTypes() {
        TileEntityFurnace furnace = new TileEntityFurnace();
        // This mapped runtime writes BurnTime via setInteger, despite an i2s
        // conversion in its bytecode. The serialized tag is Int, not Short.
        assertEquals(3, furnace.writeToNBT(new NBTTagCompound()).getTag("BurnTime").getId());
        NBTTagCompound wrong = new NBTTagCompound();
        wrong.setShort("BurnTime", (short) 0);
        NBTTagCompound correct = new NBTTagCompound();
        correct.setInteger("BurnTime", 0);
        var first = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(wrong));
        var second = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(correct));
        AtomicInteger reads = new AtomicInteger();
        assertTrue(MagneticBlockReader.matchesStored(Blocks.FURNACE.getDefaultState(), () -> {
            reads.incrementAndGet();
            return furnace.writeToNBT(new NBTTagCompound());
        }, List.of(first, second)));
        assertEquals(1, reads.get());
        assertFalse(first.matches(Blocks.FURNACE.getDefaultState(), furnace.writeToNBT(new NBTTagCompound())));
    }

    @Test
    void absentNbtIsANonmatchAndSerializationFailurePreservesItsOriginalCause() {
        var rule = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(new NBTTagCompound()));
        assertFalse(MagneticBlockReader.matchesStored(Blocks.FURNACE.getDefaultState(), () -> null, List.of(rule)));
        IllegalStateException failure = new IllegalStateException("TE serializer failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> MagneticBlockReader.matchesStored(Blocks.FURNACE.getDefaultState(), () -> { throw failure; }, List.of(rule))));
    }

    @Test
    void qualifiedSelectionPrefersTheFirstStateRuleBeforeAnyNbtSerialization() {
        AtomicInteger reads = new AtomicInteger();
        var nbt = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(new NBTTagCompound()));
        var unconditional = new MagneticBlockRule("minecraft:furnace", Map.of(), null);
        var rules = rules(7, binding("nbt-first", nbt), binding("state-second", unconditional));
        var match = MagneticBlockReader.matchQualifiedStored(Blocks.FURNACE.getDefaultState(), () -> {
            reads.incrementAndGet();
            throw new AssertionError("state selection must not serialize a tile entity");
        }, rules).orElseThrow();
        assertEquals(new MagneticBlockReader.MaterialMatch("state-second", MagneticBlockReader.EvidenceKind.STORED_STATE), match);
        assertEquals(0, reads.get());
    }

    @Test
    void qualifiedNbtRulesForTheSameBlockUseOneReadAndKeepTheSelectedBindingId() {
        AtomicInteger reads = new AtomicInteger();
        NBTTagCompound expected = new NBTTagCompound();
        expected.setInteger("BurnTime", 0);
        NBTTagCompound wrong = new NBTTagCompound();
        wrong.setShort("BurnTime", (short) 0);
        var first = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(wrong));
        var second = new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(expected));
        var match = MagneticBlockReader.matchQualifiedStored(Blocks.FURNACE.getDefaultState(), () -> {
            reads.incrementAndGet();
            return expected;
        }, rules(8, binding("same-block-first", first), binding("same-block-second", second))).orElseThrow();
        assertEquals(new MagneticBlockReader.MaterialMatch("same-block-second",
                MagneticBlockReader.EvidenceKind.MATCHED_TILE_ENTITY_NBT), match);
        assertEquals(1, reads.get());
    }

    @Test
    void qualifiedRuleSetRequiresExplicitRevisionAndUniqueCallerIds() {
        var rule = new MagneticBlockRule("minecraft:iron_block", Map.of(), null);
        assertThrows(IllegalArgumentException.class, () -> rules(-1, binding("a", rule)));
        assertThrows(IllegalArgumentException.class, () -> rules(0, binding("duplicate", rule), binding("duplicate", rule)));
        assertThrows(IllegalArgumentException.class, () -> binding(" ", rule));
    }

    private static MagneticBlockReader.RuleBinding binding(String id, MagneticBlockRule rule) {
        return new MagneticBlockReader.RuleBinding(id, rule);
    }

    private static MagneticBlockReader.RuleSet rules(long revision, MagneticBlockReader.RuleBinding... bindings) {
        return new MagneticBlockReader.RuleSet(revision, List.of(bindings));
    }
}
