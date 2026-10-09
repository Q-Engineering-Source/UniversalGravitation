package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.player.NativePlayerMovement;
import static org.junit.jupiter.api.Assertions.*;

class EstimatedBindingEligibilityTest {
    @BeforeAll static void bootstrap() {
        Bootstrap.register();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroSizeHelperIsNotAutomaticallyBound(boolean nativeNoGravity, @TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10701));
             var registration = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var helper = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            helper.width = helper.height = 0;
            helper.setNoGravity(nativeNoGravity);
            int sources = EntityGravitySources.activeCount();
            assertDoesNotThrow(() -> binder.onEntityJoinWorld(
                    new EntityJoinWorldEvent(helper, world.world())));
            assertFalse(binder.isBound(helper));
            assertEquals(nativeNoGravity, helper.hasNoGravity());
            assertEquals(sources, EntityGravitySources.activeCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void estimatedBodyBecomingZeroReleasesSourceAndCanRebind(boolean nativeNoGravity, @TempDir Path directory) {
        var binder = new GravityEntityBinder();
        double oldThreshold = UnigravConfig.entityGravityThreshold;
        UnigravConfig.entityGravityThreshold = 1;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10702));
             var registration = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            item.setNoGravity(nativeNoGravity);
            int sources = EntityGravitySources.activeCount();
            float width = item.width, height = item.height;
            binder.sweepEntity(item);
            binder.setForce(item, new Vec3(10, 0, 0));
            assertTrue(binder.isBound(item));
            assertEquals(sources + 1, EntityGravitySources.activeCount());
            item.width = item.height = 0;
            assertNull(binder.bodyPhysicsOf(item));
            assertFalse(binder.isBound(item));
            assertEquals(nativeNoGravity, item.hasNoGravity());
            assertEquals(sources, EntityGravitySources.activeCount());
            assertTrue(binder.sampleFieldBinding(item).appliedForce().isEmpty());
            item.width = width;
            item.height = height;
            binder.sweepEntity(item);
            assertTrue(binder.isBound(item));
            assertEquals(sources + 1, EntityGravitySources.activeCount());
            binder.releaseWorld(world.world());
            assertEquals(nativeNoGravity, item.hasNoGravity());
            assertEquals(sources, EntityGravitySources.activeCount());
        } finally { UnigravConfig.entityGravityThreshold = oldThreshold; }
    }

    @Test void explicitPhysicsSurvivesZeroVisualSize(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10703))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            var physics = new BodyPhysics(80, 0);
            try (var registration = binder.bind(item, GravityMode.REPLACE, physics)) {
                item.width = item.height = 0;
                assertEquals(physics, binder.bodyPhysicsOf(item));
                assertTrue(binder.isBound(item));
                assertTrue(item.hasNoGravity());
            }
            assertFalse(item.hasNoGravity());
        }
    }

    @Test void zeroSizeWithDeclaredPresetKeepsItsExplicitMass(@TempDir Path directory) {
        BodyPhysics.registerPreset(PresetItem.class, 5, 0, true, true, false);
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10704));
             var registration = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var item = new PresetItem(world.world());
            item.width = item.height = 0;
            binder.sweepEntity(item);
            assertEquals(new BodyPhysics(5, 0), binder.bodyPhysicsOf(item));
            assertTrue(binder.isBound(item));
        }
    }

    @Test void nonFiniteSizeFailsBeforeBindingMutation(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10705));
             var registration = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            item.width = Float.NaN;
            assertThrows(IllegalArgumentException.class, () -> binder.sweepEntity(item));
            assertFalse(binder.isBound(item));
            assertFalse(item.hasNoGravity());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tickReleasesZeroEstimatedBodyBeforeApplyingForce(boolean serverTick, @TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10706));
             var registration = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()})) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            item.addedToChunk = true;
            binder.sweepEntity(item);
            binder.setForce(item, new Vec3(10, 0, 0));
            double beforeMotion = item.motionX;
            item.width = item.height = 0;
            if (serverTick) binder.tickServerWorlds(new WorldServer[]{world.world()});
            else binder.applyTick(item);
            assertFalse(binder.isBound(item));
            assertFalse(item.hasNoGravity());
            assertEquals(beforeMotion, item.motionX, "release must not apply the old force or erase native momentum");
            assertTrue(binder.sampleFieldBinding(item).appliedForce().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"NaN,0", "Infinity,0", "1,NaN", "1,Infinity"})
    void explicitNonFinitePhysicsIsRejected(double mass, double volume) {
        assertThrows(IllegalArgumentException.class, () -> new BodyPhysics(mass, volume));
    }

    @Test void explicitReplacementOutlivesTheAutomaticRegistration(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10707))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            var automatic = binder.bindPredicate(entity -> true, GravityMode.REPLACE, null, new WorldServer[]{world.world()});
            try (automatic) {
                binder.sweepEntity(item);
                try (var explicit = binder.bind(item, GravityMode.REPLACE, new BodyPhysics(80, 0))) {
                    item.width = item.height = 0;
                    automatic.close();
                    assertTrue(binder.isBound(item), "old automatic policy cannot release explicit replacement");
                    assertEquals(new BodyPhysics(80, 0), binder.bodyPhysicsOf(item));
                }
                assertFalse(item.hasNoGravity(), "replacement carries forward the binder's original restoration duty");
            }
        }
    }

    @Test void automaticCloseCannotRemoveAnExplicitReplacementCapturedByItsOldSnapshot(@TempDir Path directory)
            throws InterruptedException {
        var binder = new GravityEntityBinder();
        double oldThreshold = UnigravConfig.entityGravityThreshold;
        UnigravConfig.entityGravityThreshold = 1;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10708))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            int sources = EntityGravitySources.activeCount();
            var automatic = binder.bindPredicate(entity -> entity == item, GravityMode.REPLACE, null,
                    new WorldServer[]{world.world()});
            binder.sweepEntity(item);
            var force = new Vec3(10, 0, 0);
            binder.setForce(item, force);
            assertTrue(binder.isBound(item));
            assertEquals(sources + 1, EntityGravitySources.activeCount());
            assertEquals(Optional.of(force), binder.sampleFieldBinding(item).appliedForce());

            var closeFailure = new AtomicReference<Throwable>();
            var closer = new Thread(() -> {
                try {
                    automatic.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            }, "automatic-binding-close");
            closer.setDaemon(true);
            GravityEntityBinder.Registration explicit = null;
            boolean closeCapturedOldBinding = false;
            try {
                synchronized (NativePlayerMovement.INSTANCE) {
                    closer.start();
                    closeCapturedOldBinding = awaitBlockAtForceMutation(closer);
                    if (closeCapturedOldBinding) {
                        explicit = binder.bind(item, GravityMode.REPLACE, new BodyPhysics(80, 0));
                    }
                }
                closer.join(5000);
                assertTrue(closeCapturedOldBinding,
                        "close must release bindings before it waits at the native force-mutation boundary");
                assertFalse(closer.isAlive(), "registration close must finish after the native boundary is released");
                if (closeFailure.get() != null) fail("automatic registration close failed", closeFailure.get());
                assertNotNull(explicit);
                assertTrue(binder.isBound(item), "closing the old auto-binding must preserve its explicit replacement");
                assertEquals(new BodyPhysics(80, 0), binder.bodyPhysicsOf(item));
                assertEquals(sources + 1, EntityGravitySources.activeCount(), "replacement source must remain registered");
                assertEquals(Optional.of(force), binder.sampleFieldBinding(item).appliedForce(),
                        "replacement must retain the entity's persistent force");
                assertTrue(item.hasNoGravity(), "replacement must retain the old binding's restoration duty");

                explicit.close();
                explicit = null;
                assertFalse(item.hasNoGravity(), "the replacement must still restore the native gravity flag when closed");
                assertEquals(sources, EntityGravitySources.activeCount());
            } finally {
                if (closer.isAlive()) closer.join(5000);
                if (explicit != null) explicit.close();
                automatic.close();
            }
        } finally {
            UnigravConfig.entityGravityThreshold = oldThreshold;
        }
    }

    @Test void automaticCloseReleasesARefreshedBindingFromTheSameRegistration(@TempDir Path directory)
            throws InterruptedException {
        var binder = new GravityEntityBinder();
        double oldThreshold = UnigravConfig.entityGravityThreshold;
        UnigravConfig.entityGravityThreshold = 1;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10710))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            int sources = EntityGravitySources.activeCount();
            var automatic = binder.bindPredicate(entity -> entity == item, GravityMode.REPLACE, null,
                    new WorldServer[]{world.world()});
            binder.sweepEntity(item);
            BodyPhysics original = binder.bodyPhysicsOf(item);
            var force = new Vec3(10, 0, 0);
            binder.setForce(item, force);

            var closeFailure = new AtomicReference<Throwable>();
            var closer = new Thread(() -> {
                try {
                    automatic.close();
                } catch (Throwable failure) {
                    closeFailure.set(failure);
                }
            }, "automatic-binding-close-after-refresh");
            closer.setDaemon(true);
            boolean closeCapturedOldBinding = false;
            BodyPhysics refreshed = null;
            try {
                synchronized (NativePlayerMovement.INSTANCE) {
                    closer.start();
                    closeCapturedOldBinding = awaitBlockAtForceMutation(closer);
                    if (closeCapturedOldBinding) {
                        item.width += 0.125F;
                        refreshed = binder.bodyPhysicsOf(item);
                    }
                }
                closer.join(5000);
                assertTrue(closeCapturedOldBinding,
                        "close must release bindings before waiting at the native force-mutation boundary");
                assertFalse(closer.isAlive(), "registration close must finish after the native boundary is released");
                if (closeFailure.get() != null) fail("automatic registration close failed", closeFailure.get());
                assertNotEquals(original, refreshed, "bodyPhysicsOf must exercise the real estimated refresh seam");
                assertFalse(binder.isBound(item), "close must retire a refreshed binding with the same registration owner");
                assertNull(binder.bodyPhysicsOf(item));
                assertEquals(sources, EntityGravitySources.activeCount());
                assertTrue(binder.sampleFieldBinding(item).appliedForce().isEmpty());
                assertFalse(item.hasNoGravity());
            } finally {
                if (closer.isAlive()) closer.join(5000);
                automatic.close();
            }
        } finally {
            UnigravConfig.entityGravityThreshold = oldThreshold;
        }
    }

    @Test void retiredEstimatedBindHandleCannotCloseANewerExplicitBinding(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        double oldThreshold = UnigravConfig.entityGravityThreshold;
        UnigravConfig.entityGravityThreshold = 1;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10712))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            int sources = EntityGravitySources.activeCount();
            var first = binder.bind(item, GravityMode.REPLACE);
            float width = item.width;
            float height = item.height;
            item.width = item.height = 0;
            assertNull(binder.bodyPhysicsOf(item), "zero-size estimated binding must retire before rebinding");
            assertFalse(binder.isBound(item));
            assertFalse(item.hasNoGravity());

            item.width = width;
            item.height = height;
            var second = binder.bind(item, GravityMode.REPLACE, new BodyPhysics(80, 0));
            var force = new Vec3(10, 0, 0);
            binder.setForce(item, force);
            first.close();

            assertTrue(binder.isBound(item), "a retired registration handle cannot close a later binding");
            assertEquals(new BodyPhysics(80, 0), binder.bodyPhysicsOf(item));
            assertEquals(sources + 1, EntityGravitySources.activeCount());
            assertEquals(Optional.of(force), binder.sampleFieldBinding(item).appliedForce());
            assertTrue(item.hasNoGravity());

            second.close();
            assertFalse(binder.isBound(item));
            assertEquals(sources, EntityGravitySources.activeCount());
            assertTrue(binder.sampleFieldBinding(item).appliedForce().isEmpty());
            assertFalse(item.hasNoGravity());
        } finally {
            UnigravConfig.entityGravityThreshold = oldThreshold;
        }
    }

    @Test void bindingHandleStillClosesItsLineageAfterSetBodyPhysics(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        double oldThreshold = UnigravConfig.entityGravityThreshold;
        UnigravConfig.entityGravityThreshold = 1;
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10713))) {
            var item = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            int sources = EntityGravitySources.activeCount();
            var registration = binder.bind(item, GravityMode.REPLACE);
            binder.setBodyPhysics(item, new BodyPhysics(80, 0));
            assertEquals(new BodyPhysics(80, 0), binder.bodyPhysicsOf(item));

            registration.close();
            assertFalse(binder.isBound(item), "same-lineage setBodyPhysics update remains owned by its handle");
            assertEquals(sources, EntityGravitySources.activeCount());
            assertFalse(item.hasNoGravity());
        } finally {
            UnigravConfig.entityGravityThreshold = oldThreshold;
        }
    }

    @Test void predicateCannotCreateAnOrphanBindingAfterClosingItself(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10709))) {
            var helper = new EntityItem(world.world(), 8, 65, 8, new ItemStack(Items.IRON_INGOT));
            var registrationHandle = new AtomicReference<GravityEntityBinder.Registration>();
            int sources = EntityGravitySources.activeCount();
            var registration = binder.bindPredicate(entity -> {
                if (entity == helper) registrationHandle.get().close();
                return entity == helper;
            }, GravityMode.REPLACE, null, new WorldServer[0]);
            registrationHandle.set(registration);

            assertDoesNotThrow(() -> binder.onEntityJoinWorld(new EntityJoinWorldEvent(helper, world.world())));
            assertFalse(binder.isBound(helper), "a predicate that closed itself cannot install a retired policy");
            assertNull(binder.bodyPhysicsOf(helper));
            assertFalse(helper.hasNoGravity());
            assertEquals(sources, EntityGravitySources.activeCount());
            assertTrue(binder.sampleFieldBinding(helper).appliedForce().isEmpty());
        }
    }

    private static boolean awaitBlockAtForceMutation(Thread thread) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (frame.getClassName().equals(NativePlayerMovement.class.getName())
                            && frame.getMethodName().equals("beginForceMutation")) {
                        return true;
                    }
                }
            }
            Thread.yield();
        }
        return false;
    }

    private static final class PresetItem extends EntityItem {
        PresetItem(World world) { super(world, 8, 65, 8, new ItemStack(Items.IRON_INGOT)); }
    }
}
