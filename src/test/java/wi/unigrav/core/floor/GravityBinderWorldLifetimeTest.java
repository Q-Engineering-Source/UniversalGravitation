package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class GravityBinderWorldLifetimeTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void laterServerThreadCannotSampleAnEntityFromThePreviousWorld(@TempDir Path directory) throws Exception {
        var binder = new GravityEntityBinder();
        GravityEntityBinder.Registration binding;
        WorldServer previousWorld;
        try (var old = BootWorldFixture.open(directory, new UUID(0, 9601))) {
            previousWorld = old.world();
            var item = new EntityItem(old.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            item.addedToChunk = true;
            binding = binder.bind(item, GravityMode.REPLACE);
        }
        try (binding; var nextThread = Executors.newSingleThreadExecutor()) {
            // Even an obsolete published entry cannot authorize another server thread to sample its floor index.
            var tick = nextThread.submit(() -> binder.tickServerWorlds(new WorldServer[] { previousWorld }));
            assertDoesNotThrow(() -> tick.get(), "old world bindings must not be sampled by a new server thread");
        }
    }

    @Test void worldUnloadReleasesExplicitBindingsForcesAndOnlyThatWorld(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9604))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            try (var binding = binder.bind(item, GravityMode.REPLACE)) {
                binder.setForce(item, new Vec3(1, 2, 3));
                assertTrue(item.hasNoGravity());
                binder.onWorldUnload(new WorldEvent.Unload(world.world()));
                assertFalse(binder.isBound(item));
                assertFalse(item.hasNoGravity(), "release restores the original flag before saving");
                assertTrue(binder.sampleFieldBinding(item).appliedForce().isEmpty());
                assertEquals(0, binder.boundCount());
            }
        }
    }

    @Test void currentPublishedWorldStillReceivesForceAndUnpublishedWorldDoesNot(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9609))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            item.addedToChunk = true;
            try (var binding = binder.bind(item, GravityMode.REPLACE, new BodyPhysics(100, .1))) {
                item.motionX = item.motionY = item.motionZ = 0;
                binder.setForce(item, new Vec3(100, 0, 0));
                binder.tickServerWorlds(new WorldServer[0]);
                assertEquals(0, item.motionX);
                binder.tickServerWorlds(new WorldServer[] { world.world() });
                assertEquals(.0025, item.motionX, 1e-12, "1 m/s^2 * .05 s / 20 = .0025 blocks/tick");
                binder.releaseWorld(world.world());
                assertFalse(binder.isBound(item));
                assertFalse(item.hasNoGravity());
            }
        }
    }

    @Test void massBindingWithoutAHandleIsReleasedWithoutTouchingTheNextWorld(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        WorldServer oldWorld;
        EntityItem oldItem;
        try (var old = BootWorldFixture.open(directory.resolve("old"), new UUID(0, 9613))) {
            oldWorld = old.world();
            oldItem = new EntityItem(oldWorld, 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            binder.setBodyPhysics(oldItem, new BodyPhysics(100, .1));
            binder.setForce(oldItem, new Vec3(10, 0, 0));
        }
        try (var next = BootWorldFixture.open(directory.resolve("next"), new UUID(0, 9614))) {
            var nextItem = new EntityItem(next.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            nextItem.setNoGravity(true);
            binder.setBodyPhysics(nextItem, new BodyPhysics(200, .2));
            binder.setForce(nextItem, new Vec3(20, 0, 0));
            try {
                binder.onWorldUnload(new WorldEvent.Unload(oldWorld));
                assertFalse(binder.isBound(oldItem));
                assertFalse(oldItem.hasNoGravity());
                assertTrue(binder.sampleFieldBinding(oldItem).appliedForce().isEmpty());
                assertTrue(binder.isBound(nextItem));
                assertEquals(new Vec3(20, 0, 0), binder.sampleFieldBinding(nextItem).appliedForce().orElseThrow());
                binder.releaseWorld(next.world());
                assertTrue(nextItem.hasNoGravity(), "preexisting external noGravity is not ours to clear");
                assertEquals(0, binder.boundCount());
            } finally {
                binder.releaseWorld(oldWorld);
                binder.releaseWorld(next.world());
            }
        }
    }
}
