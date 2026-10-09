package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.UUID;
import knok.entity.ISeat;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import static org.junit.jupiter.api.Assertions.*;

class KnokSeatBindingIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void actualSeatCanJoinCarryPlayerAndReleaseWithoutAnEstimatedBinding(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10711));
             var policy = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var seat = new ISeat.Seat(world.world(), new Vec3d(8, 65, 8));
            assertEquals(0, seat.width);
            assertEquals(0, seat.height);
            int sources = EntityGravitySources.activeCount();
            assertDoesNotThrow(() -> binder.onEntityJoinWorld(new EntityJoinWorldEvent(seat, world.world())));
            assertFalse(binder.isBound(seat));
            assertFalse(seat.hasNoGravity());
            world.actor().setPosition(8, 65, 8);
            assertTrue(world.actor().startRiding(seat, true));
            assertSame(seat, world.actor().getRidingEntity());
            installDismountFootprint(world);
            world.actor().dismountRidingEntity();
            assertFalse(world.actor().isRiding());
            assertTrue(seat.getPassengers().isEmpty());
            seat.onUpdate(); // The actual helper retires when its chair/passenger is absent.
            assertTrue(seat.isDead);
            assertFalse(binder.isBound(seat));
            assertEquals(sources, EntityGravitySources.activeCount());
        }
    }

    private static void installDismountFootprint(BootWorldFixture world) {
        // Vanilla searches the passenger's current/adjacent dismount positions and
        // checks both collision space and support at y-1. This seat is at (8,65,8),
        // so the reachable search footprint stays in chunk column (0,0).
        world.installColumn(0, 0);
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                world.put(new BlockPos(x, 64, z), Blocks.STONE.getDefaultState());
            }
        }
    }
}
