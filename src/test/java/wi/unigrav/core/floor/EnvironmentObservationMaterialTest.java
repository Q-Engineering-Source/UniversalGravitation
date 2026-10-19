package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityFurnace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.player.EnvironmentObservationWork;
import wi.unigrav.core.player.FreeTickResources;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentObservationMaterialTest {
    @TempDir Path temp;

    @Test void realQualifiedMaterialPathDispatchesNbtOnlyAfterMatchingStateAndPreservesCause() throws Exception {
        UUID player = new UUID(0, 9291);
        try (var fixture = BootWorldFixture.open(temp.resolve("materials"), player, false)) {
            var scope = new PlayerPhysicalSnapshot.Scope(player, new UUID(0, 9292), 0, 1);
            EnvironmentObservationWork work = new FreeTickResources.Store()
                    .acquire(this, fixture.world(), scope, 0).environmentObservationWork();
            var expected = new NBTTagCompound();
            expected.setInteger("BurnTime", 0);
            var rules = new MagneticBlockReader.RuleSet(1, List.of(new MagneticBlockReader.RuleBinding("furnace",
                    new MagneticBlockRule("minecraft:furnace", Map.of(), NbtPattern.compile(expected)))));
            var reads = new AtomicInteger();
            var furnace = new TileEntityFurnace();
            assertTrue(MagneticBlockReader.matchQualifiedStored(Blocks.IRON_BLOCK.getDefaultState(), () -> {
                reads.incrementAndGet(); return furnace.writeToNBT(new NBTTagCompound());
            }, rules, work).isEmpty());
            assertEquals(0, reads.get());
            long unmatched = work.usage().units();
            assertEquals(2, unmatched, "one actual material classification and state-filter dispatch");
            assertEquals("furnace", MagneticBlockReader.matchQualifiedStored(Blocks.FURNACE.getDefaultState(), () -> {
                reads.incrementAndGet(); return furnace.writeToNBT(new NBTTagCompound());
            }, rules, work).orElseThrow().selectionId());
            assertEquals(1, reads.get());
            assertEquals(unmatched + 5, work.usage().units(), "state filter, NBT supplier and selected-rule callback are actual attempts");
            long beforeFailure = work.usage().units();
            var failure = new IllegalStateException("actual material supplier failure");
            assertSame(failure, assertThrows(IllegalStateException.class, () ->
                    MagneticBlockReader.matchQualifiedStored(Blocks.FURNACE.getDefaultState(), () -> {
                        reads.incrementAndGet(); throw failure;
                    }, rules, work)));
            assertEquals(2, reads.get());
            assertEquals(beforeFailure + 3, work.usage().units(), "throwing NBT dispatch is retained and later rule callback is not attempted");
        }
    }
}
