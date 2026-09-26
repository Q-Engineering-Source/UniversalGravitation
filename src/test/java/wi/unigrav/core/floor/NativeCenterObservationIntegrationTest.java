package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.CPacketPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerMovementRouter;
import wi.unigrav.core.player.PlayerEquipmentMutation;

class NativeCenterObservationIntegrationTest {
    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void completedNativeMovementProducesAcceptedCenterObservations(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 801))) {
            var actor = fixture.serverPlayer(new UUID(0, 802));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            for (int tick = 0; tick < 3; tick++) {
                fixture.world().setTotalWorldTime(100 + tick);
                move(router, actor, 2);
            }
            var window = NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).orElseThrow();
            assertEquals(3, window.samples().size());
            assertEquals(100, window.samples().getFirst().serverTick());
            assertEquals(10, window.samples().getFirst().center().x());
            assertEquals(14, window.samples().getLast().center().x());
            assertEquals(65.89999997615814, window.samples().getLast().center().y());
            assertEquals(14, actor.posX, "observing does not move the actor again");
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void sameTickBurstReplacesTheLatestCenterWithoutAddingTime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 811))) {
            var actor = fixture.serverPlayer(new UUID(0, 812));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            fixture.world().setTotalWorldTime(100);
            move(router, actor, 2);
            var first = NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).orElseThrow();
            for (int burst = 0; burst < 4; burst++) move(router, actor, 0.25);
            var burst = NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).orElseThrow();
            assertEquals(1, burst.samples().size());
            assertEquals(11, burst.samples().getLast().center().x());
            assertEquals(10, first.samples().getLast().center().x(), "published snapshots are immutable");
            assertThrows(UnsupportedOperationException.class, () -> burst.samples().clear());
            fixture.world().setTotalWorldTime(101);
            move(router, actor, 2);
            assertEquals(2, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 2, 3.5})
    void stationaryAndFortyOrSeventyMpsCentersDoNotUseMpMotion(double delta, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 821))) {
            fixture.installColumn(1, 0); // The 70 m/s case crosses x=16; never invoke world generation.
            var actor = fixture.serverPlayer(new UUID(0, 822));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            for (int tick = 0; tick < 3; tick++) {
                fixture.world().setTotalWorldTime(100 + tick);
                actor.motionX = 1000 + tick;
                move(router, actor, delta);
            }
            var samples = NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).orElseThrow().samples();
            assertEquals(delta, samples.getLast().center().x() - samples.get(1).center().x());
            assertEquals(1, samples.getLast().serverTick() - samples.get(1).serverTick());
            assertEquals(1002, actor.motionX, "observation never replaces motion with derived velocity");
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void provisionalTrackingReceiptIsNotACompletedSampleAndLateFailureDiscardsWindow(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 831))) {
            var actor = fixture.serverPlayer(new UUID(0, 832));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            assertThrows(IllegalStateException.class, () -> router.runPacket(actor.connection, () -> {
                moveBody(router, actor, 0.25);
                assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isPresent());
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
                throw new IllegalStateException("late native callback");
            }, () -> false));
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            assertEquals(8.5, actor.posX, "do not undo a real movement after callback failure");
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void lateTeleportBarrierDiscardsReceiptAndWindow(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 841))) {
            var actor = fixture.serverPlayer(new UUID(0, 842));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            router.runPacket(actor.connection, () -> moveBody(router, actor, 0.25), () -> true);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void tickChangedByLateCallbackCannotRelabelAnEarlierMovement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 881))) {
            var actor = fixture.serverPlayer(new UUID(0, 882));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            fixture.world().setTotalWorldTime(100);
            router.runPacket(actor.connection, () -> {
                moveBody(router, actor, 0.25);
                fixture.world().setTotalWorldTime(101);
            }, () -> false);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void unknownExternalVelocityEventStartsANewObservationWindow(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 851))) {
            var actor = fixture.serverPlayer(new UUID(0, 852));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            actor.velocityChanged = true;
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            move(router, actor, 0.25);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            actor.velocityChanged = false;
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @ParameterizedTest
    @ValueSource(longs = {99, 103})
    void backwardOrMissingTicksCannotBridgeTheObservationGap(long nextTick, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 861))) {
            var actor = fixture.serverPlayer(new UUID(0, 862));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            fixture.world().setTotalWorldTime(100);
            move(router, actor, 0.25);
            fixture.world().setTotalWorldTime(nextTick);
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void persistentForceChangeCannotReuseOldCenterWindow(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 891))) {
            var actor = fixture.serverPlayer(new UUID(0, 892));
            actor.setPosition(8, 65, 8);
            var binder = new GravityEntityBinder();
            try (var registration = binder.bind(actor, GravityMode.ADDITIVE, new BodyPhysics(100, 0.1))) {
                var router = new PlayerMovementRouter();
                move(router, actor, 0.25);
                assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                        .orElseThrow().samples().size());
                binder.setForce(actor, new Vec3(100, 0, 0));
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty(),
                        "a changed persistent force must not leave the pre-change interval eligible");
                assertEquals(8.25, actor.posX);
                assertEquals(0, actor.motionX, "setting force is not an impulse or a velocity reset");
                move(router, actor, 0.25);
                assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                        .orElseThrow().samples().size(), "a new completed packet starts a fresh window");
            }
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void equipmentAbaInsidePacketCannotReviveItsObservation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 931))) {
            var actor = fixture.serverPlayer(new UUID(0, 932));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            router.runPacket(actor.connection, () -> {
                moveBody(router, actor, 0.25);
                actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                actor.setItemStackToSlot(EntityEquipmentSlot.FEET, ItemStack.EMPTY);
            }, () -> false);
            assertTrue(actor.getItemStackFromSlot(EntityEquipmentSlot.FEET).isEmpty());
            assertEquals(8.5, actor.posX);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty(),
                    "restoring equipment must not certify the intervening movement interval");
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"set", "remove", "split", "delete", "clear", "reload"})
    void inventoryEquipmentAbaInvalidatesOnlyItsActor(String operation, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 941))) {
            var actor = fixture.serverPlayer(new UUID(0, 942));
            var other = fixture.serverPlayer(new UUID(0, 943));
            actor.setPosition(8, 65, 8);
            other.setPosition(8, 65, 9);
            actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            move(new PlayerMovementRouter(), other, 0.25);
            router.runPacket(actor.connection, () -> {
                moveBody(router, actor, 0.25);
                // Headless bridge for the same boundary injected around native inventory writes.
                PlayerEquipmentMutation.observe(actor, () -> {
                    switch (operation) {
                        case "set" -> actor.inventory.setInventorySlotContents(36, ItemStack.EMPTY);
                        case "remove" -> actor.inventory.removeStackFromSlot(36);
                        case "split" -> actor.inventory.decrStackSize(36, 1);
                        case "delete" -> actor.inventory.deleteStack(actor.getItemStackFromSlot(EntityEquipmentSlot.FEET));
                        case "clear" -> actor.inventory.clear();
                        case "reload" -> actor.inventory.readFromNBT(new NBTTagList());
                        default -> throw new AssertionError(operation);
                    }
                    return null;
                });
                actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            }, () -> false);
            assertEquals(Items.IRON_BOOTS, actor.getItemStackFromSlot(EntityEquipmentSlot.FEET).getItem());
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(other.connection)
                    .orElseThrow().samples().size());
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size(), "a later clean packet can start a new window");
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void equivalentEquipmentAndUnequippedInventoryWritesKeepObservations(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 951))) {
            var actor = fixture.serverPlayer(new UUID(0, 952));
            actor.setPosition(8, 65, 8);
            actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            actor.setItemStackToSlot(EntityEquipmentSlot.FEET,
                    actor.getItemStackFromSlot(EntityEquipmentSlot.FEET).copy());
            PlayerEquipmentMutation.observe(actor, () -> {
                actor.inventory.setInventorySlotContents(10, new ItemStack(Items.IRON_BOOTS));
                return null;
            });
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void partialEquipmentWriteFailureStillInvalidatesObservation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 961))) {
            var actor = fixture.serverPlayer(new UUID(0, 962));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            var failure = new IllegalStateException("after equipment write");
            var thrown = assertThrows(IllegalStateException.class, () -> PlayerEquipmentMutation.observe(actor, () -> {
                actor.inventory.setInventorySlotContents(36, new ItemStack(Items.IRON_BOOTS));
                throw failure;
            }));
            assertTrue(thrown == failure, "preserve the original failure");
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size(), "failed write must not leak an open mutation scope");
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void completedForceMutationInsidePacketCannotReviveItsObservation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 901))) {
            var actor = fixture.serverPlayer(new UUID(0, 902));
            actor.setPosition(8, 65, 8);
            var binder = new GravityEntityBinder();
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            router.runPacket(actor.connection, () -> {
                moveBody(router, actor, 0.25);
                binder.setForce(actor, new Vec3(100, 0, 0));
                binder.setForce(actor, Vec3.ZERO);
                assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isPresent(),
                        "force changes do not retire a valid geometry-only receipt");
            }, () -> false);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty(),
                    "finishing the mutation and restoring the force do not make this packet clean");
            assertEquals(8.5, actor.posX);
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace_force", "remove_force", "remove_null", "mass", "rebind", "unbind", "impulse_aba"})
    void actorLocalMutationsCannotReuseTheWindowOrInvalidateAnotherPlayer(String operation, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 911))) {
            var actor = fixture.serverPlayer(new UUID(0, 912));
            var other = fixture.serverPlayer(new UUID(0, 913));
            actor.setPosition(8, 65, 8);
            other.setPosition(8, 65, 9);
            var binder = new GravityEntityBinder();
            try (var registration = binder.bind(actor, GravityMode.ADDITIVE, new BodyPhysics(100, 0.1))) {
                binder.setForce(actor, new Vec3(100, 0, 0));
                var router = new PlayerMovementRouter();
                move(router, actor, 0.25);
                move(new PlayerMovementRouter(), other, 0.25);
                switch (operation) {
                    case "replace_force" -> binder.setForce(actor, new Vec3(200, 0, 0));
                    case "remove_force" -> binder.setForce(actor, Vec3.ZERO);
                    case "remove_null" -> binder.setForce(actor, null);
                    case "mass" -> binder.setBodyPhysics(actor, new BodyPhysics(200, 0.1));
                    case "rebind" -> binder.bind(actor, GravityMode.ADDITIVE, new BodyPhysics(200, 0.1));
                    case "unbind" -> registration.close();
                    case "impulse_aba" -> {
                        binder.addImpulse(actor, new Vec3(100, 0, 0));
                        binder.addImpulse(actor, new Vec3(-100, 0, 0));
                        actor.velocityChanged = false; // Native tracking may consume this transient flag.
                    }
                    default -> throw new AssertionError(operation);
                }
                assertEquals(0, actor.motionX);
                assertEquals(8.25, actor.posX);
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty(), operation);
                assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(other.connection)
                        .orElseThrow().samples().size(), "another actor's observations stay intact");
                move(router, actor, 0.25);
                assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                        .orElseThrow().samples().size());
            }
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameForceAndReadOnlyMassDoNotStarveCleanWindows(boolean forcePresent, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 921))) {
            var actor = fixture.serverPlayer(new UUID(0, 922));
            actor.setPosition(8, 65, 8);
            var binder = new GravityEntityBinder();
            try (var registration = binder.bind(actor, GravityMode.ADDITIVE)) {
                if (forcePresent) binder.setForce(actor, new Vec3(100, 0, 0));
                var router = new PlayerMovementRouter();
                for (int tick = 0; tick < 3; tick++) {
                    fixture.world().setTotalWorldTime(100 + tick);
                    move(router, actor, 0.25);
                    binder.setForce(actor, forcePresent ? new Vec3(100, 0, 0) : Vec3.ZERO);
                    if (!forcePresent) binder.setForce(actor, null);
                    binder.bodyPhysicsOf(actor);
                }
                assertEquals(3, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                        .orElseThrow().samples().size());
            }
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void foreignThreadSetForceInvalidatesOnlyObservationWithoutAccessingWorld(@TempDir Path directory) throws Exception {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 931))) {
            var actor = fixture.serverPlayer(new UUID(0, 932));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            var binder = new GravityEntityBinder();
            CompletableFuture.runAsync(() -> binder.setForce(actor, new Vec3(100, 0, 0))).get(10, TimeUnit.SECONDS);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            assertEquals(8.25, actor.posX);
            assertEquals(0, actor.motionX);
            binder.setForce(actor, Vec3.ZERO);
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void nestedMutationAndRetiredLifetimeCannotPublishUntilAllMarkersClose(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 941))) {
            var actor = fixture.serverPlayer(new UUID(0, 942));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            var outer = NativePlayerMovement.INSTANCE.beginForceMutation(actor);
            try (outer) {
                try (var inner = NativePlayerMovement.INSTANCE.beginForceMutation(actor)) {
                    inner.changed();
                }
                NativePlayerMovement.INSTANCE.stop();
                move(router, actor, 0.25);
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty(),
                        "retiring a lifetime cannot forget an operation still running on the same actor");
            }
            outer.close(); // Repeated close must not release a different marker's depth.
            move(router, actor, 0.25);
            assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection)
                    .orElseThrow().samples().size());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    @Test
    void absoluteInvalidationAndOneUlpGeometryChangeDiscardObservations(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 871))) {
            var actor = fixture.serverPlayer(new UUID(0, 872));
            actor.setPosition(8, 65, 8);
            var router = new PlayerMovementRouter();
            move(router, actor, 0.25);
            router.invalidate(actor.connection);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
            move(router, actor, 0.25);
            actor.setEntityBoundingBox(actor.getEntityBoundingBox().offset(Math.ulp(actor.posX), 0, 0));
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(actor.connection).isEmpty());
        } finally { NativePlayerMovement.INSTANCE.stop(); }
    }

    private static void move(PlayerMovementRouter router, EntityPlayerMP actor, double delta) {
        router.runPacket(actor.connection, () -> moveBody(router, actor, delta), () -> false);
    }

    private static void moveBody(PlayerMovementRouter router, EntityPlayerMP actor, double delta) {
        double endX = actor.posX + delta;
        double y = actor.posY;
        double z = actor.posZ;
        router.begin(actor.connection, new CPacketPlayer.Position(endX, y, z, false),
                new Vec3(actor.posX, y, z), false);
        router.move(actor, MoverType.PLAYER, delta, 0, 0);
        router.assignEndpoint(actor, endX, y, z, 0, 0, false);
        router.beforeTracking(false);
    }
}
