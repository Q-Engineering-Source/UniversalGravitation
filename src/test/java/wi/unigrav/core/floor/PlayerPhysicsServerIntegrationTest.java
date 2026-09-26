package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import dev.crlhitbox.api.geometry.Vec3d;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.stats.StatList;
import wi.unigrav.core.ImpactDamageHandler;
import wi.unigrav.core.network.ImpactReportMessage;
import net.minecraft.init.Blocks;
import net.minecraft.init.MobEffects;
import net.minecraft.init.Enchantments;
import net.minecraft.potion.PotionEffect;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.GIntentServer;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsServer;
import wi.unigrav.core.player.PlayerMovementRouter;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.client.boost.RemoteApplicationFixture;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.client.PlayerPhysicsClientMirror;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.proxy.CommonProxy;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.api.field.GravityFields;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsServerIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @CsvSource({"0,false,true", "0.125,false,true", "0.25,false,true", "0.25,true,true",
            "0.25001,false,false", "0.3,true,false"})
    void zeroGravityFootReachDrivesWithoutPullingOrGrantingGround(double gap, boolean sprint, boolean reachable,
                                                               @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            for (int z = 7; z <= 12; z++) fixture.world.put(new BlockPos(8, 64, z), Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65 + gap, 8.5);
            var owned = fixture.adoptOrdinary();
            for (int tick = 0; tick < 25; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1),
                        new Vec3d(0, 0, 1), false, false, sprint, false, 5));
                fixture.tick();
                assertEquals(65 + gap, fixture.player.posY, 1e-9, "reach is not attraction or snapping");
                assertFalse(fixture.player.onGround, "near-foot drive must not forge native ground contact");
                assertTrue(fixture.player.motionZ * 20 <= (sprint ? 0.500001 : 0.250001), "selected low-speed cap");
            }
            if (reachable) {
                assertTrue(fixture.player.posZ > 8.7, "actual near-foot surface must enable slow travel");
                assertEquals(sprint ? 0.5 : 0.25, fixture.player.motionZ * 20, 1e-6, "walk and sprint reach their distinct caps");
            }
            else assertEquals(8.5, fixture.player.posZ, 1e-9, "no propulsion beyond reach");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void zeroGravityRemovedFloorStopsDriveButPreservesDrift(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var cell = new BlockPos(8, 64, 8);
            fixture.world.put(cell, Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.2, 8.5);
            var owned = fixture.adoptOrdinary();
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1),
                    new Vec3d(0, 0, 1), false, false, false, false, 5));
            fixture.tick();
            double previous = fixture.player.posZ;
            double drift = fixture.player.motionZ;
            assertTrue(drift > 0);
            fixture.world.put(cell, Blocks.AIR.getDefaultState());
            for (int tick = 0; tick < 5; tick++) {
                fixture.input(owned, tick + 4, new PlayerPhysicsProtocol.Controls(new Vec3d(1, 0, 0),
                        new Vec3d(1, 0, 0), false, false, true, false, 5));
                fixture.tick();
                assertEquals(8.5, fixture.player.posX, 1e-9, "airborne input cannot redirect inertia");
                assertEquals(previous + drift, fixture.player.posZ, 1e-9);
                assertEquals(drift, fixture.player.motionZ, 1e-9, "leaving support must not erase or damp drift");
                previous = fixture.player.posZ;
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void zeroGravitySprintReleaseBrakesBackToWalkingSpeed(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            for (int z = 7; z <= 12; z++) fixture.world.put(new BlockPos(8, 64, z), Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.2, 8.5);
            var owned = fixture.adoptOrdinary();
            var mirror = fixture.world.serverPlayer(fixture.player.getUniqueID());
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(mirror, wire(owned).snapshot().orElseThrow());
            for (int tick = 0; tick < 20; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1),
                        new Vec3d(0, 0, 1), false, false, tick < 10, false, 5));
                fixture.tick();
                presentation.install(mirror, wire(fixture.physicsReplies.getLast()).snapshot().orElseThrow());
                presentation.tick(mirror);
                if (tick == 9) assertEquals(.5, fixture.player.motionZ * 20, 1e-6);
            }
            assertEquals(.25, fixture.player.motionZ * 20, 1e-6);
            var contact = fixture.physics.accepted(fixture.player.connection).orElseThrow().contacts().orElseThrow();
            assertTrue(contact.nearFootLocomotion());
            assertFalse(contact.onGround());
            assertTrue(mirror.limbSwingAmount > 0 && mirror.distanceWalkedModified > 0);
            assertTrue(mirror.cameraYaw > 0);
            assertFalse(mirror.onGround);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void retiringNativeDelegationDoesNotRestoreAnObsoleteGravityFlag(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            fixture.player.noClip = true;
            fixture.tick();
            assertFalse(fixture.physics.drivesMotion(fixture.player));
            fixture.player.setNoGravity(true); // A later native-side owner changed its own policy.
            fixture.physics.stop();
            assertTrue(fixture.player.hasNoGravity(), "retired physical owner must not overwrite the native owner");
        }
    }

    @Test void failedFinalizerLandingCannotStrandAlreadyInstalledGeometry(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var failure = new IllegalStateException("one-shot finalizer landing failure");
            var fired = new AtomicBoolean();
            var floor = new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity actor) {
                    super.onLanded(world, actor);
                    if (fired.compareAndSet(false, true)) throw failure;
                }
            };
            fixture.world.put(new BlockPos(8, 64, 8), floor.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.adoptOrdinary();
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(0, -1, 0)));
            assertSame(failure, assertThrows(IllegalStateException.class, fixture::tick));
            assertTrue(fired.get());
            assertEquals(65, fixture.player.posY, 0);
            fixture.tick();
            assertEquals(fixture.player.posY,
                    fixture.physics.accepted(fixture.player.connection).orElseThrow().placement().transport().y(), 0,
                    "finalizer callback failure cannot leave installed geometry outside the owner's accepted state");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void kinematicTransitionInsideLandingStopsTheOldStepsRemainingForce(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var landed = new AtomicBoolean();
            var floor = new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity actor) {
                    super.onLanded(world, actor);
                    landed.set(true);
                    actor.noClip = true;
                }
            };
            fixture.world.put(new BlockPos(8, 64, 8), floor.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.adoptOrdinary();
            double mass = UnigravMod.entityBinder.bodyPhysicsOf(fixture.player).massKg();
            UnigravMod.entityBinder.setForce(fixture.player, new Vec3(0, -32 * mass, 0));
            fixture.physics.addImpulse(fixture.player, new Vec3(0, -20 * mass, 0));
            fixture.tick();
            assertTrue(landed.get());
            assertTrue(fixture.physics.owns(fixture.player));
            assertFalse(fixture.physics.drivesMotion(fixture.player));
            assertEquals(PlayerPhysicsProtocol.Kind.NATIVE, fixture.physicsReplies.getLast().kind());
            assertEquals(0, fixture.player.motionY, "old environment force cannot run after native takes over");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"riding", "spectator", "noclip", "sleeping"})
    void kinematicStateDelegatesNativeThenResumesWithTheOriginalBudget(String mode, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        double previousLimit = UnigravConfig.maxEntitySpeed;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.maxEntitySpeed = 37;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            var original = fixture.adoptOrdinary();
            if (mode.equals("spectator")) fixture.player.interactionManager.setGameType(GameType.SPECTATOR);
            else if (mode.equals("noclip")) fixture.player.noClip = true;
            else if (mode.equals("sleeping")) {
                fixture.world.world().setWorldTime(18000);
                fixture.world.world().setSkylightSubtracted(15);
                var bed = new BlockPos(8, 65, 8);
                fixture.world.put(bed, Blocks.BED.getDefaultState());
                assertEquals(EntityPlayer.SleepResult.OK, fixture.player.trySleep(bed));
            }
            else {
                var mount = new EntityPig(fixture.world.world());
                mount.setPosition(8.5, 65, 8.5);
                assertTrue(fixture.player.startRiding(mount, true));
            }
            fixture.tick();
            assertTrue(fixture.physics.owns(fixture.player), "retain the physical lifetime during native delegation");
            assertFalse(fixture.physics.drivesMotion(fixture.player));
            assertEquals(PlayerPhysicsProtocol.Kind.NATIVE, fixture.physicsReplies.getLast().kind());
            var proxy = new CommonProxy(fixture.physics);
            proxy.runLegacyMotion(fixture.player, fixture.player::jump);
            assertTrue(fixture.player.motionY > .4, "the native path must actually execute");
            if (mode.equals("noclip")) {
                int id = fixture.teleport(10, 65, 8);
                fixture.player.connection.processConfirmTeleport(new CPacketConfirmTeleport(id));
                fixture.physics.confirmNativeTeleport(fixture.player.connection, id, true);
                assertEquals(PlayerPhysicsProtocol.Kind.NATIVE, fixture.physicsReplies.getLast().kind());
                assertFalse(fixture.physics.drivesMotion(fixture.player));
            }
            if (fixture.player.isPlayerSleeping()) fixture.player.wakeUpPlayer(true, true, false);
            fixture.player.dismountRidingEntity();
            fixture.player.interactionManager.setGameType(GameType.SURVIVAL);
            fixture.player.noClip = false;
            fixture.player.capabilities.isFlying = false;
            UnigravConfig.maxEntitySpeed = 500; // A fresh budget would incorrectly replace the original 37 ceiling.
            fixture.tick();
            var offer = fixture.physicsReplies.stream().filter(p -> p.kind() == PlayerPhysicsProtocol.Kind.OFFER).reduce((a, b) -> b).orElseThrow();
            assertTrue(offer.offerSequence() > original.offerSequence());
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 100), fixture.player.connection);
            fixture.move(0);
            assertFalse(fixture.physics.drivesMotion(fixture.player), "one native centre is not a velocity interval");
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 101), fixture.player.connection);
            fixture.move(0);
            assertTrue(fixture.physics.drivesMotion(fixture.player));
            assertEquals(original.scope(), fixture.physics.accepted(fixture.player.connection).orElseThrow().scope());
            UnigravMod.entityBinder.setBodyPhysics(fixture.player, new BodyPhysics(100, .1));
            UnigravMod.entityBinder.setForce(fixture.player, new Vec3(10_000_000, 0, 0));
            fixture.tick();
            assertEquals(37, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-8);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.maxEntitySpeed = previousLimit;
        }
    }

    @Test void spectatorRoundTripPreservesConfirmedPersonalGIntent(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.adoptRemote();
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
            fixture.player.interactionManager.setGameType(GameType.SPECTATOR);
            fixture.intent.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            fixture.tick();
            assertTrue(fixture.physics.owns(fixture.player));
            assertFalse(fixture.physics.drivesMotion(fixture.player));
            assertTrue(fixture.intent.observe(fixture.player).isEmpty(), "ineligible ON remains UNKNOWN, never an inferred OFF");
            fixture.player.interactionManager.setGameType(GameType.CREATIVE);
            fixture.player.noClip = false;
            fixture.intent.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active(), "no new G-key packet was sent");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void samePositionTeleportDuringJumpStillInvalidatesTheStep(boolean nativeState, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        var current = new AtomicReference<Fixture>();
        var jumped = new AtomicBoolean();
        try (var fixture = new Fixture(directory, false, false, actor -> {
                 jumped.set(true);
                 if (nativeState) actor.noClip = true;
                 else current.get().teleport(8.5, 65, 8.5);
             });
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            current.set(fixture);
            fixture.player.setPosition(8.5, 65, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            var owned = fixture.adoptOrdinary();
            fixture.player.onGround = true;
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, nativeState ? 1 : 0), new Vec3d(0, 0, 1),
                    true, false, false, false, 5));
            fixture.tick();
            assertTrue(jumped.get(), "execute native super.jump before the reentrant correction");
            assertEquals(65, fixture.player.posY, "equal endpoint does not make the interrupted jump current");
            if (nativeState) assertEquals(0, fixture.player.motionZ, "old input must stop at a native-state transition");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void teleportInsideLandingDoesNotApplyTheOldStepsRemainingForce(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var landed = new AtomicBoolean();
            var teleportFloor = new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity actor) {
                    super.onLanded(world, actor);
                    landed.set(true);
                    fixture.teleport(12, 65, 8);
                }
            };
            fixture.world.put(new BlockPos(8, 64, 8), teleportFloor.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.adoptOrdinary();
            double mass = UnigravMod.entityBinder.bodyPhysicsOf(fixture.player).massKg();
            UnigravMod.entityBinder.setForce(fixture.player, new Vec3(0, -32 * mass, 0));
            fixture.physics.addImpulse(fixture.player, new Vec3(0, -20 * mass, 0));
            fixture.tick();
            assertTrue(landed.get(), "execute the real block landing callback");
            assertEquals(12, fixture.player.posX);
            assertEquals(0, fixture.player.motionY, "old step must not write drag/environment after a native correction");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void nativeUpdateCannotReplaceOwnedPlacementOrAnInFlightTeleport(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            var box = fixture.player.getEntityBoundingBox();
            double x = fixture.player.posX;
            fixture.router.reanchorNativeUpdate(fixture.player, 12, 65, 8, 20, 30);
            assertEquals(x, fixture.player.posX);
            assertSame(box, fixture.player.getEntityBoundingBox(), "native wrapper must not rebuild the accepted collider");
            fixture.teleport(12, 65, 8);
            fixture.router.reanchorNativeUpdate(fixture.player, x, 65, 8, 20, 30);
            assertEquals(12, fixture.player.posX, "do not roll back a real correction issued during native update");
            var unowned = fixture.world.serverPlayer(new UUID(0, 998));
            fixture.router.reanchorNativeUpdate(unowned, 9, 68, 10, 20, 30);
            assertEquals(9, unowned.posX);
            assertEquals(68, unowned.posY);
            assertEquals(20, unowned.rotationYaw);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeTeleportReanchorsSameOwnerAndPreservesQueuedImpulse(boolean failedPublication, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            double mass = UnigravMod.entityBinder.bodyPhysicsOf(fixture.player).massKg();
            fixture.physics.addImpulse(fixture.player, new Vec3(mass * 2, 0, 0));
            int id = fixture.teleport(12, 65, 8);
            fixture.physicsReplies.clear();
            for (int i = 0; i < 25; i++) fixture.tick();
            assertEquals(12, fixture.player.posX, "pending native confirmation suspends integration");
            assertTrue(fixture.physicsReplies.isEmpty(), "periodic recovery must not send pre-teleport placement");
            fixture.physics.confirmNativeTeleport(fixture.player.connection, id + 1, true);
            fixture.physics.confirmNativeTeleport(fixture.player.connection, id, false);
            assertEquals(before, fixture.physics.accepted(fixture.player.connection).orElseThrow());
            fixture.player.connection.processConfirmTeleport(new CPacketConfirmTeleport(id));
            fixture.failState = failedPublication;
            if (failedPublication) assertSame(fixture.sendFailure, assertThrows(IllegalStateException.class,
                    () -> fixture.physics.confirmNativeTeleport(fixture.player.connection, id, true)));
            else fixture.physics.confirmNativeTeleport(fixture.player.connection, id, true);
            fixture.failState = false;
            var corrected = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(12, corrected.placement().transport().x());
            assertEquals(before.scope(), corrected.scope());
            assertTrue(corrected.revision() > before.revision());
            fixture.physics.confirmNativeTeleport(fixture.player.connection, id, true);
            assertEquals(corrected, fixture.physics.accepted(fixture.player.connection).orElseThrow(), "duplicate confirmation is not another rebase");
            fixture.tick();
            assertEquals(id, fixture.physicsReplies.getLast().nativeTeleportId().orElseThrow());
            assertEquals(12.1, fixture.player.posX, 1e-6);
            assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedImpulseChangesAcceptedVelocityOnceWithoutNativeMotionAuthority(boolean failedPublication, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            var owned = fixture.adoptOrdinary();
            double mass = UnigravMod.entityBinder.bodyPhysicsOf(fixture.player).massKg();
            fixture.player.motionX = 100; // Native MP velocity must not become the starting authority.
            assertTrue(fixture.physics.addImpulse(fixture.player, new Vec3(mass * 2, 0, 0)));
            fixture.failState = failedPublication;
            if (failedPublication) assertSame(fixture.sendFailure, assertThrows(IllegalStateException.class, fixture::tick));
            else fixture.tick();
            fixture.failState = false;
            var first = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(owned.scope(), first.scope());
            assertEquals(owned.snapshot().orElseThrow().placement().transport().x() + .1,
                    fixture.player.posX, 1e-6);
            assertEquals(2, first.worldVelocity().x(), 1e-6, "UG pure zero-G retains inertial speed");
            double x = fixture.player.posX;
            fixture.tick();
            assertEquals(x + first.worldVelocity().x() * .05, fixture.player.posX, 1e-6,
                    "next tick must not apply the same impulse again");
            assertFalse(fixture.physics.addImpulse(fixture.world.serverPlayer(new UUID(0, 999)), Vec3.ZERO));
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void impulseDuringEnvironmentCallbackSurvivesTheCurrentStep(boolean nativeDelta, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            double x = fixture.player.posX;
            double mass = UnigravMod.entityBinder.bodyPhysicsOf(fixture.player).massKg();
            var fired = new AtomicBoolean();
            try (var source = GravityFields.get().registerContribution("unigrav_test", "impulse_callback", (pos, time) -> {
                if (fired.compareAndSet(false, true)) {
                    if (nativeDelta) assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(.1, 0, 0)));
                    else assertTrue(fixture.physics.addImpulse(fixture.player, new Vec3(mass * 2, 0, 0)));
                }
                return Vec3.ZERO;
            })) {
                fixture.tick();
                assertTrue(fired.get());
                assertEquals(x, fixture.player.posX, "callback cannot mutate the current step's accepted velocity");
                fixture.tick();
                assertEquals(x + .1, fixture.player.posX, 1e-6);
                var snapshot = fixture.physics.accepted(fixture.player.connection).orElseThrow();
                assertThrows(IllegalArgumentException.class,
                        () -> fixture.physics.addImpulse(fixture.player, new Vec3(Double.NaN, 0, 0)));
                assertEquals(snapshot, fixture.physics.accepted(fixture.player.connection).orElseThrow());
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void ownedPlayerSkipsWholeNativeTravelAndAllJumpCallsWhileOtherActorsRemainNative(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            var proxy = new CommonProxy(fixture.physics);
            fixture.adoptOrdinary();
            double y = fixture.player.posY;
            fixture.player.capabilities.isFlying = true;
            fixture.player.motionY = .2;
            proxy.runLegacyMotion(fixture.player, () -> fixture.player.travel(0, 0, 1));
            proxy.runLegacyMotion(fixture.player, fixture.player::jump);
            proxy.runLegacyMotion(fixture.player, fixture.player::handleJumpWater);
            proxy.runLegacyMotion(fixture.player, fixture.player::handleJumpLava);
            assertEquals(y, fixture.player.posY);
            assertEquals(.2, fixture.player.motionY, "EntityPlayer flight wrapper must not run either");
            var other = fixture.world.serverPlayer(new UUID(0, 995));
            other.setPosition(8, 65, 8);
            proxy.runLegacyMotion(other, other::jump);
            assertTrue(other.motionY > .4, "unowned player keeps native jump");
            var pig = new EntityPig(fixture.world.world());
            pig.setPosition(8, 65, 8);
            proxy.runLegacyMotion(pig, () -> pig.travel(0, 0, 1));
            assertTrue(pig.posZ > 8, "non-player travel must remain native");
        }
    }

    @Test void ownedMovementPacketKeepsLookButCannotClaimPositionOrGround(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            var bounds = fixture.player.getEntityBoundingBox();
            boolean ground = fixture.player.onGround;
            var packet = new CPacketPlayer.PositionRotation(900, 900, 900, 123, -32, !ground);
            fixture.router.runPacket(fixture.player.connection, () -> {
                assertTrue(fixture.router.handleOwnedPacket(fixture.player.connection, packet, false));
            }, () -> false);
            assertEquals(123, fixture.player.rotationYaw);
            assertEquals(-32, fixture.player.rotationPitch);
            assertEquals(before.placement().transport().x(), fixture.player.posX);
            assertEquals(before.placement().transport().y(), fixture.player.posY);
            assertEquals(bounds, fixture.player.getEntityBoundingBox());
            assertEquals(ground, fixture.player.onGround);
            assertEquals(before, fixture.physics.accepted(fixture.player.connection).orElseThrow());
        }
    }

    @Test void ownedPacketStillRunsNativeInvalidPacketDisconnect(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.player.getServer().getPlayerList().setPlayerManager(
                    new WorldServer[]{fixture.world.world()});
            fixture.adoptOrdinary();
            fixture.router.runPacket(fixture.player.connection, () -> fixture.player.connection.processPlayer(
                    new CPacketPlayer.Rotation(Float.NaN, 0, false)), () -> false);
            fixture.channel.runPendingTasks();
            assertFalse(fixture.channel.isOpen(), "ownership must not bypass native invalid-packet handling");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedPacketPreservesNativeTeleportWaitAndEndExit(boolean teleport, @TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            if (teleport) fixture.player.connection.setPlayerLocation(fixture.player.posX, fixture.player.posY,
                    fixture.player.posZ, 0, 0);
            else fixture.player.queuedEndExit = true;
            var packet = new CPacketPlayer.PositionRotation(900, 900, 900, 123, -32, true);
            fixture.router.runPacket(fixture.player.connection, () -> {
                if (teleport) assertFalse(fixture.router.handleOwnedPacket(fixture.player.connection, packet, true));
                fixture.player.connection.processPlayer(packet);
            }, () -> teleport);
            assertEquals(0, fixture.player.rotationYaw);
            assertEquals(before.placement().transport().x(), fixture.player.posX);
            assertEquals(before.placement().transport().y(), fixture.player.posY);
            assertEquals(before, fixture.physics.accepted(fixture.player.connection).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualHandoffAndFollowingForceInstallAcrossIndependentClientState(boolean loseOwned, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var mirror = fixture.world.serverPlayer(fixture.player.getUniqueID());
            mirror.setPosition(8.5, 67, 8.5);
            var client = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(mirror, snapshot),
                    () -> { }, () -> mirror.setNoGravity(false));
            fixture.confirmOff();
            fixture.tick();
            client.receive(wire(fixture.physicsReplies.removeFirst()));
            for (int sample = 0; sample < 2; sample++) {
                client.prepare(new CPacketPlayer.Position(mirror.posX, mirror.posY, mirror.posZ, false),
                        new Vec3d(mirror.posX, mirror.posY, mirror.posZ), 0, 0, false, Optional.empty(),
                        packet -> fixture.physics.receive(wire(packet), fixture.player.connection));
                fixture.move(0);
                if (sample == 0) fixture.tick();
            }
            assertTrue(fixture.physics.owns(fixture.player));
            var owned = fixture.physicsReplies.getLast();
            if (!loseOwned) assertTrue(client.receive(wire(owned)));
            assertEquals(!loseOwned, client.owns());
            fixture.tick();
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            assertTrue(client.owns());
            assertEquals(-0.0784, mirror.motionY, 1e-8);
            client.input(new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 5)).ifPresent(packet -> fixture.physics.receive(wire(packet), fixture.player.connection));
            fixture.tick();
            var state = wire(fixture.physicsReplies.getLast());
            assertTrue(client.receive(state));
            assertEquals(66.9216, mirror.posY, 1e-7);
            assertEquals(fixture.player.posY, mirror.posY);
            fixture.move(1);
            assertEquals(8.5, fixture.player.posX, "in-flight old position packet cannot move the new owner");
            assertFalse(client.receive(wire(owned)));
            assertFalse(client.receive(state));
            assertEquals(66.9216, mirror.posY, 1e-7, "late state cannot reapply the initial placement");
            for (int tick = 0; tick < 20; tick++) {
                fixture.tick();
                assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
                assertEquals(fixture.player.onGround, mirror.onGround);
                assertEquals(fixture.player.collidedVertically, mirror.collidedVertically);
                assertEquals(fixture.player.fallDistance, mirror.fallDistance);
            }
            assertTrue(mirror.onGround, "real floor landing must reach the independent mirror");
            assertEquals(65, mirror.posY, 1e-7);
            var beforeCorrection = wire(fixture.physicsReplies.getLast());
            int id = fixture.teleport(12, 65, 8);
            client.nativeCorrection(id);
            fixture.player.connection.processConfirmTeleport(new CPacketConfirmTeleport(id));
            fixture.physics.confirmNativeTeleport(fixture.player.connection, id, true);
            assertFalse(client.receive(beforeCorrection));
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            assertEquals(12, mirror.posX);
            assertEquals(owned.scope(), client.snapshot().orElseThrow().scope());
            fixture.tick();
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            assertEquals(fixture.player.posX, mirror.posX);
            assertEquals(fixture.player.posY, mirror.posY);
            fixture.player.noClip = true;
            fixture.tick();
            if (loseOwned) {
                fixture.physicsReplies.clear(); // Also exercise a dropped first NATIVE notification.
                for (int tick = 0; tick < 20; tick++) fixture.tick();
            }
            assertEquals(PlayerPhysicsProtocol.Kind.NATIVE, fixture.physicsReplies.getLast().kind());
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            assertTrue(client.owns());
            assertFalse(client.drivesMotion());
            fixture.player.noClip = false;
            fixture.tick();
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER, fixture.physicsReplies.getLast().kind());
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            for (int sample = 0; sample < 2; sample++) {
                client.prepare(new CPacketPlayer.Position(fixture.player.posX, fixture.player.posY, fixture.player.posZ, false),
                        new Vec3d(fixture.player.posX, fixture.player.posY, fixture.player.posZ), 0, 0, false, Optional.empty(),
                        packet -> fixture.physics.receive(wire(packet), fixture.player.connection));
                fixture.move(0);
                if (sample == 0) fixture.tick();
            }
            assertEquals(PlayerPhysicsProtocol.Kind.OWNED, fixture.physicsReplies.getLast().kind());
            assertTrue(client.receive(wire(fixture.physicsReplies.getLast())));
            assertTrue(client.drivesMotion());
            assertEquals(owned.scope(), client.snapshot().orElseThrow().scope());
            assertEquals(fixture.player.posX, mirror.posX);
            assertEquals(fixture.player.posY, mirror.posY);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    private static PlayerPhysicsProtocol.Packet wire(PlayerPhysicsProtocol.Packet packet) {
        return PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(packet));
    }

    @Test void lostOwnedResynchronizesEvenWhenWorldSamplingCannotAdvance(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var owned = fixture.adoptOrdinary();
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            fixture.physicsReplies.clear(); // The peer did not receive its first OWNED.
            FloorWorldFields.clearAll();
            for (int tick = 0; tick < 20; tick++) fixture.tick();
            assertEquals(1, fixture.physicsReplies.size(), "bounded retry must not depend on a successful force step");
            var recovery = fixture.physicsReplies.getFirst();
            assertEquals(PlayerPhysicsProtocol.Kind.STATE, recovery.kind());
            assertEquals(owned.scope(), recovery.scope());
            assertEquals(accepted, recovery.snapshot().orElseThrow());
            assertEquals(accepted, fixture.physics.accepted(fixture.player.connection).orElseThrow());
            assertTrue(fixture.physics.owns(fixture.player));
            assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void unavailableFluidEscapeRetainsItsAlreadyCommittedMovement(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var armed = new AtomicBoolean();
            var queried = new AtomicBoolean();
            var floor = new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity actor) {
                    super.onLanded(world, actor);
                    armed.set(true);
                }
            };
            var wall = new Block(Material.ROCK) {
                @Override public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos,
                        AxisAlignedBB bounds, List<AxisAlignedBB> boxes, Entity actor, boolean actualState) {
                    if (armed.get() && queried.compareAndSet(false, true))
                        throw new IllegalStateException("one-shot fluid escape query failure");
                    super.addCollisionBoxToList(state, world, pos, bounds, boxes, actor, actualState);
                }
            };
            fixture.world.put(new BlockPos(8, 64, 8), floor.getDefaultState());
            for (int y = 65; y <= 68; y++) {
                fixture.world.put(new BlockPos(8, y, 8), Blocks.WATER.getDefaultState());
                fixture.world.put(new BlockPos(9, y, 8), wall.getDefaultState());
            }
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.2, 8.5);
            fixture.player.handleWaterMovement();
            assertTrue(fixture.player.isInWater());
            fixture.adoptOrdinary();
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(1, -1, 0)));
            fixture.tick();
            assertTrue(armed.get(), "real landing must precede the escape query");
            assertTrue(queried.get(), "the real collision-query callback must fail");
            assertEquals(65, fixture.player.posY, 0);
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(fixture.player.posY, accepted.placement().transport().y(), 0);
            assertEquals(fixture.player.posX, accepted.placement().transport().x(), 0);
            assertEquals(PlayerPhysicsProtocol.Kind.STATE, fixture.physicsReplies.getLast().kind());
            assertEquals(accepted, fixture.physicsReplies.getLast().snapshot().orElseThrow());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void waterWallEscapeTransitionsBackToAirLikeNative(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        double previousBias = UnigravConfig.waterSinkBias;
        EmbeddedChannel nativeChannel = null;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.waterSinkBias = 0.25;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 65.2, 8.68);
            UnigravMod.entityBinder.setBodyPhysics(fixture.player, new BodyPhysics(1000, 1));
            for (int x = 6; x <= 11; x++) for (int z = 6; z <= 12; z++) {
                fixture.world.put(new BlockPos(x, 64, z), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
                if (z <= 8) fixture.world.put(new BlockPos(x, 65, z), Blocks.WATER.getDefaultState());
                if (z == 9) fixture.world.put(new BlockPos(x, 65, z), Blocks.STONE.getDefaultState());
            }
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 994));
            nativeChannel = new EmbeddedChannel(nativePlayer.connection.netManager);
            nativePlayer.setPosition(8.5, 65.2, 8.68);
            nativePlayer.setNoGravity(false);
            fixture.player.handleWaterMovement();
            var owned = fixture.adoptOrdinary();
            boolean leftWater = false;
            for (int tick = 0; tick < 12; tick++) {
                fixture.player.handleWaterMovement();
                nativePlayer.handleWaterMovement();
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1),
                        new Vec3d(0, 0, 1), true, false, false, false, 5));
                if (nativePlayer.isInWater()) nativePlayer.handleJumpWater();
                else if (nativePlayer.onGround) nativePlayer.jump();
                nativePlayer.travel(0, 0, 0.98F);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "escape Y at tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7, "escape Z at tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8, "escape velocity at tick " + tick);
                leftWater |= !nativePlayer.isInWater();
            }
            assertTrue(leftWater, "fixture must actually leave water");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.waterSinkBias = previousBias;
            if (nativeChannel != null) nativeChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false", "false,false,true", "true,false,true"})
    void fluidTravelConsumesEnvironmentOnceAndMatchesNativeInput(boolean jump, boolean depthStrider, boolean lava, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        double previousBias = UnigravConfig.waterSinkBias;
        EmbeddedChannel nativeChannel = null;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.waterSinkBias = 0.25;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            UnigravMod.entityBinder.setBodyPhysics(fixture.player, new BodyPhysics(1000, 1));
            for (int x = 6; x <= 11; x++) for (int z = 6; z <= 12; z++) {
                fixture.world.put(new BlockPos(x, 64, z), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
                for (int y = 65; y <= 71; y++) fixture.world.put(new BlockPos(x, y, z),
                        (lava ? Blocks.LAVA : Blocks.WATER).getDefaultState());
            }
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 993));
            nativeChannel = new EmbeddedChannel(nativePlayer.connection.netManager);
            nativePlayer.setPosition(8.5, 67, 8.5);
            nativePlayer.setNoGravity(lava);
            if (depthStrider) {
                ItemStack boots = new ItemStack(Items.LEATHER_BOOTS);
                boots.addEnchantment(Enchantments.DEPTH_STRIDER, 3);
                fixture.player.setItemStackToSlot(EntityEquipmentSlot.FEET, boots.copy());
                nativePlayer.setItemStackToSlot(EntityEquipmentSlot.FEET, boots.copy());
            }
            fixture.player.handleWaterMovement();
            nativePlayer.handleWaterMovement();
            assertTrue(lava ? fixture.player.isInLava() : fixture.player.isInWater());
            var owned = fixture.adoptOrdinary();
            for (int tick = 0; tick < 8; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1),
                        new Vec3d(0, 0, 1), jump, false, false, false, 5));
                if (jump) {
                    if (lava) nativePlayer.handleJumpLava();
                    else nativePlayer.handleJumpWater();
                }
                nativePlayer.travel(0, 0, 0.98F);
                // UG has no lava buoyancy law: prescribe the floor's 32m/s² once after native lava drag.
                if (lava) nativePlayer.motionY -= 0.08D;
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "fluid Y at tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7, "fluid Z at tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8);
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.waterSinkBias = previousBias;
            if (nativeChannel != null) nativeChannel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void ladderSlideSneakAndWallClimbMatchNativeTravel(boolean sneak, boolean climb, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            for (int y = 65; y <= 73; y++) {
                fixture.world.put(new BlockPos(8, y, 8), Blocks.LADDER.getDefaultState());
                if (climb) fixture.world.put(new BlockPos(8, y, 9), Blocks.STONE.getDefaultState());
            }
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 992));
            nativePlayer.setPosition(8.5, 67, 8.5);
            nativePlayer.setNoGravity(false);
            var owned = fixture.adoptOrdinary();
            assertTrue(fixture.player.isOnLadder());
            for (int tick = 0; tick < 12; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, climb ? 1 : 0),
                        new Vec3d(0, 0, 1), false, sneak, false, false, 5));
                nativePlayer.setSneaking(sneak);
                nativePlayer.travel(0, 0, climb ? 0.98F : 0);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "ladder Y at tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7, "ladder Z at tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8);
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void postMovementForceCannotPublishVelocityAboveTheSafetyBudget(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        double previousLimit = UnigravConfig.maxEntitySpeed;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.maxEntitySpeed = 100;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8, 67, 8);
            UnigravMod.entityBinder.setBodyPhysics(fixture.player, new BodyPhysics(100, 0.1));
            UnigravMod.entityBinder.setForce(fixture.player, new Vec3(10_000_000, 0, 0));
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.adoptOrdinary();
            fixture.tick();
            assertEquals(100, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            assertEquals(8, fixture.player.posX);
            fixture.tick();
            assertEquals(13, fixture.player.posX, 1e-10);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.maxEntitySpeed = previousLimit;
        }
    }

    @Test void airborneGroundFlagCannotGrantJumpWithoutActualSupport(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var owned = fixture.adoptOrdinary();
            fixture.player.onGround = true;
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    true, false, false, false, 5));
            fixture.tick();
            assertEquals(67, fixture.player.posY);
            assertTrue(fixture.player.motionY < 0);
            assertFalse(fixture.player.onGround);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true"})
    void normalGravityFallAndWalkingMatchNativeTravel(boolean sprint, boolean sneak, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            for (int x = 6; x <= 10; x++) for (int z = 6; z <= 12; z++)
                fixture.world.put(new BlockPos(x, 64, z), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 990));
            nativePlayer.setPosition(8.5, 67, 8.5);
            nativePlayer.setNoGravity(false);
            var owned = fixture.adoptOrdinary();
            for (int tick = 0; tick < 16; tick++) {
                boolean walking = tick >= 10;
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, walking ? 1 : 0),
                        new Vec3d(0, 0, 1), false, walking && sneak, walking && sprint, false, 5));
                nativePlayer.setSprinting(walking && sprint);
                nativePlayer.setSneaking(walking && sneak);
                nativePlayer.travel(0, 0, walking ? (sneak ? 0.98F * 0.3F : 0.98F) : 0);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "native vertical phase at tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-6, "native walking at tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8);
                assertEquals(nativePlayer.motionZ, fixture.player.motionZ, 1e-7);
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,false", "false,false,true"})
    void creativeFlightRetainsNativeTravelWithoutGravityReference(boolean jump, boolean sneak, boolean sprint,
                                                                  @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.interactionManager.setGameType(GameType.CREATIVE);
            fixture.player.capabilities.isFlying = true;
            fixture.player.setPosition(8.5, 67, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 996));
            nativePlayer.interactionManager.setGameType(GameType.CREATIVE);
            nativePlayer.capabilities.isFlying = true;
            nativePlayer.setNoGravity(true);
            nativePlayer.setPosition(8.5, 67, 8.5);
            var owned = fixture.adoptOrdinary();
            for (int tick = 0; tick < 10; tick++) {
                boolean driving = tick < 5;
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, driving ? 1 : 0),
                        new Vec3d(0, 0, 1), driving && jump, driving && sneak, driving && sprint, false, 5));
                nativePlayer.setSneaking(driving && sneak);
                nativePlayer.setSprinting(driving && sprint);
                // The native SP input stage supplies vertical flight impulse before EntityPlayer.travel.
                if (driving && sneak) nativePlayer.motionY -= nativePlayer.capabilities.getFlySpeed() * 3.0F;
                if (driving && jump) nativePlayer.motionY += nativePlayer.capabilities.getFlySpeed() * 3.0F;
                nativePlayer.travel(0, 0, driving ? 0.98F : 0);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "flight vertical tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7, "flight horizontal tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8);
                assertEquals(nativePlayer.motionZ, fixture.player.motionZ, 1e-8);
                assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualNativePoseChangeDoesNotFreezeOwnedPhysics(boolean gliding, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            if (gliding) fixture.player.setElytraFlying();
            else fixture.player.setSneaking(true);
            new CommonProxy(fixture.physics).runNativePose(fixture.player,
                    () -> BootWorldFixture.updateNativePose(fixture.player));
            assertEquals(gliding ? 0.6F : 1.65F, fixture.player.height);
            assertEquals(70, fixture.player.posY, 0, "native pose retains feet anchor");
            fixture.tick();
            var after = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertTrue(after.revision() > before.revision(), "legal pose must not freeze the physical lifetime");
            assertEquals(fixture.player.height, after.placement().shape().dimensions().height());
            assertEquals(before.scope(), after.scope());
            fixture.player.clearElytraFlying();
            fixture.player.setSneaking(false);
            new CommonProxy(fixture.physics).runNativePose(fixture.player,
                    () -> BootWorldFixture.updateNativePose(fixture.player));
            assertEquals(1.8F, fixture.player.height);
            fixture.tick();
            var standing = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertTrue(standing.revision() > after.revision());
            assertEquals((double) 1.8F, standing.placement().shape().dimensions().height());
            assertEquals(before.scope(), standing.scope());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sneakingElytraPosePreservesNativeEyeAndContinuesMoving(boolean blockedExpansion, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            var proxy = new CommonProxy(fixture.physics);
            fixture.player.setElytraFlying();
            fixture.player.setSneaking(true);
            proxy.runNativePose(fixture.player, () -> BootWorldFixture.updateNativePose(fixture.player));
            assertEquals(0.6F, fixture.player.height);
            assertTrue(fixture.player.getEyeHeight() > fixture.player.height,
                    "native sneak eye takes precedence over the elytra eye branch");
            var gliding = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(fixture.player.getEyeHeight(), gliding.placement().shape().dimensions().eyeHeight());
            assertEquals(70 + fixture.player.getEyeHeight(), gliding.placement().eye().y(), 1e-6);
            if (blockedExpansion) fixture.world.put(new BlockPos(8, 71, 8), Blocks.STONE.getDefaultState());
            fixture.player.clearElytraFlying();
            proxy.runNativePose(fixture.player, () -> BootWorldFixture.updateNativePose(fixture.player));
            assertEquals(blockedExpansion ? 0.6F : 1.65F, fixture.player.height);
            fixture.tick();
            var after = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertTrue(after.revision() > gliding.revision(), "pose synchronization must not stall the owner");
            assertEquals(before.scope(), after.scope());
            assertEquals(fixture.player.getEyeHeight(), after.placement().shape().dimensions().eyeHeight());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void alreadySneakingGliderCanHandOffNativeMovement(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.player.setElytraFlying();
            fixture.player.setSneaking(true);
            BootWorldFixture.updateNativePose(fixture.player);
            assertEquals(.6F, fixture.player.height);
            fixture.adoptOrdinary();
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(fixture.player.getEyeHeight(), accepted.placement().shape().dimensions().eyeHeight());
            fixture.tick();
            assertTrue(fixture.physics.accepted(fixture.player.connection).orElseThrow().revision() > accepted.revision());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void nativePoseKeepsTheSmallColliderWhenStandingWouldIntersectCeiling(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.adoptOrdinary();
            var proxy = new CommonProxy(fixture.physics);
            fixture.player.setElytraFlying();
            proxy.runNativePose(fixture.player, () -> BootWorldFixture.updateNativePose(fixture.player));
            fixture.world.put(new BlockPos(8, 71, 8), Blocks.STONE.getDefaultState());
            fixture.player.clearElytraFlying();
            proxy.runNativePose(fixture.player, () -> BootWorldFixture.updateNativePose(fixture.player));
            assertEquals(0.6F, fixture.player.height, "native collision check must veto expansion");
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            fixture.tick();
            var after = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertTrue(after.revision() > before.revision());
            assertEquals((double) 0.6F, after.placement().shape().dimensions().height());
            assertTrue(fixture.player.getEntityBoundingBox().maxY < 71);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void poseBoundaryDoesNotAdoptUnrelatedPositionWrites(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            new CommonProxy(fixture.physics).runNativePose(fixture.player,
                    () -> fixture.player.setPosition(fixture.player.posX + 1, fixture.player.posY, fixture.player.posZ));
            assertSame(before, fixture.physics.accepted(fixture.player.connection).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(floats = {-30, 0, 45})
    void elytraUsesOneEnvironmentForceAndNativeAerodynamics(float pitch, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.rotationPitch = pitch;
            fixture.player.setElytraFlying();
            for (int x = 6; x <= 10; x++) for (int z = 6; z <= 12; z++)
                fixture.world.put(new BlockPos(x, 64, z), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 997));
            nativePlayer.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            fixture.player.setItemStackToSlot(EntityEquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
            nativePlayer.setPosition(8.5, 70, 8.5);
            nativePlayer.rotationPitch = pitch;
            nativePlayer.setElytraFlying();
            var owned = fixture.adoptOrdinary();
            // The adoption fixture's final PositionRotation carrier uses neutral look angles.
            fixture.player.rotationPitch = pitch;
            for (int tick = 0; tick < 10; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                        new Vec3d(0, 0, 1), false, false, false, false, 5));
                nativePlayer.onLivingUpdate();
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "glide vertical tick " + tick);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7, "glide horizontal tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8);
                assertEquals(nativePlayer.motionZ, fixture.player.motionZ, 1e-8);
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void elytraWallCollisionUsesNativeDamageAndNoSecondMove(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        EmbeddedChannel nativeChannel = null;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setElytraFlying();
            for (int x = 6; x <= 10; x++) for (int z = 6; z <= 12; z++)
                fixture.world.put(new BlockPos(x, 64, z), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            for (int y = 65; y <= 73; y++) fixture.world.put(new BlockPos(8, y, 10), Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 998));
            nativeChannel = new EmbeddedChannel(nativePlayer.connection.netManager);
            // Fresh MP instances have sixty native respawn-invulnerability ticks.
            for (int tick = 0; tick < 60; tick++) {
                nativePlayer.onUpdate();
                fixture.player.onUpdate();
            }
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setElytraFlying();
            nativePlayer.setPosition(8.5, 70, 9);
            nativePlayer.motionZ = 0.5D;
            nativePlayer.setElytraFlying();
            fixture.adoptOrdinary(0.5D);
            for (int tick = 0; tick < 4; tick++) {
                nativePlayer.travel(0, 0, 0);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7);
                assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-7);
                assertEquals(nativePlayer.getHealth(), fixture.player.getHealth(), 1e-5);
            }
            assertTrue(nativePlayer.getHealth() < nativePlayer.getMaxHealth(), "reference must exercise wall impact damage");
        } finally {
            if (nativeChannel != null) nativeChannel.finishAndReleaseAll();
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groundedJumpRetainsNativeOneGTrajectory(boolean potion, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 65, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 991));
            nativePlayer.setPosition(8.5, 65, 8.5);
            nativePlayer.setNoGravity(false);
            if (potion) {
                nativePlayer.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, 200, 0));
                fixture.player.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, 200, 0));
            }
            var owned = fixture.adoptOrdinary();
            for (int i = 0; i < 3; i++) { nativePlayer.travel(0, 0, 0); fixture.tick(); }
            assertTrue(nativePlayer.onGround);
            assertTrue(fixture.player.onGround);
            nativePlayer.jump();
            for (int tick = 0; tick < 25; tick++) {
                fixture.input(owned, tick + 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                        new Vec3d(0, 0, 1), tick == 0, false, false, false, 5));
                nativePlayer.travel(0, 0, 0);
                fixture.tick();
                assertEquals(nativePlayer.posY, fixture.player.posY, 1e-7, "native jump position at tick " + tick);
                assertEquals(nativePlayer.motionY, fixture.player.motionY, 1e-8, "native jump velocity at tick " + tick);
            }
            assertTrue(fixture.player.onGround);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void ownedBoostUsesIntentOncePerTickAndReleaseKeepsInertia(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory, true);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var owned = fixture.adoptRemote();
            var boost = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, true, 5);
            for (long sequence = 3; sequence <= 12; sequence++) fixture.input(owned, sequence, boost);
            assertEquals(8, fixture.player.posZ, "packet count supplies no simulation time");
            fixture.tick();
            assertEquals(8.2, fixture.player.posZ, 1e-10);
            assertEquals(4, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().z(), 1e-10);
            fixture.input(owned, 13, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 5));
            fixture.input(owned, 12, boost); // A replay cannot undo release.
            fixture.tick();
            assertEquals(8.4, fixture.player.posZ, 1e-10);
            assertEquals(4, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().z(), 1e-10);
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void expiredInputStopsThrustButPreservesDriftAndG(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory, true);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var owned = fixture.adoptRemote();
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0.1, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 5));
            for (int i = 0; i < 20; i++) fixture.tick();
            assertEquals(0.8, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            fixture.tick();
            fixture.tick();
            assertEquals(0.8, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            assertEquals(8.5, fixture.player.posX, 1e-10);
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void topGearWithinHeightBandCannotAcceleratePastSeventy(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory, true);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var owned = fixture.adoptRemote();
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 1, 0),
                    false, false, false, true, 9));
            fixture.tick();
            fixture.tick();
            fixture.tick();
            assertEquals(70, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().y(), 1e-10);
            assertEquals(74.45, fixture.player.posY, 1e-10);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void ownedReentryDecaysOriginalCarrierWithoutExitingG(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory, true);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.player.setPosition(8, 270, 8);
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(0, -10, 0);
            assertEquals(-200, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().y(), 1e-10);
            fixture.tick(); // Cross into the band at Y250.
            assertEquals(250, fixture.player.posY, 1e-10);
            for (int i = 0; i < 10; i++) fixture.tick();
            assertEquals(-70, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().y(), 1e-10);
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
            assertTrue(fixture.physics.owns(fixture.player));
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void survivalInputCannotGrantFreeVerticalPropulsion(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8, 65, 8);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(0);
            fixture.input(fixture.physicsReplies.getLast(), 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 1, 0),
                    new Vec3d(0, 1, 0), false, false, false, true, 9));
            fixture.tick();
            assertEquals(65, fixture.player.posY);
            assertEquals(0, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().y());
            assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void explicitGOffRevokesThrustWithoutRetiringPhysicalOwner(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory, true);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            var owned = fixture.adoptRemote();
            fixture.input(owned, 3, new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, true, 5));
            fixture.tick();
            assertEquals(4, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().z(), 1e-10);
            fixture.intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, fixture.gOffer.key(), 2,
                    GIntentProtocol.State.OFF), fixture.player.connection);
            fixture.tick();
            assertTrue(fixture.physics.owns(fixture.player));
            assertEquals(8.4, fixture.player.posZ, 1e-10);
            assertEquals(4, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().z(), 1e-6,
                    "G OFF removes thrust, not inertia in an otherwise weightless region");
            assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void adoptedPlayerActuallyFallsTowardFloorStartingOnNextTick(@TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(0);
            assertEquals(67, fixture.player.posY, "handoff does not add an integration step");
            fixture.physics.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            assertEquals(67, fixture.player.posY, "repeated START in the handoff world tick must not integrate");
            fixture.tick();
            assertEquals(67, fixture.player.posY, 1e-10, "native travel applies gravity after first movement");
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(-1.568, accepted.worldVelocity().y(), 1e-7, "native air drag follows the one movement");
            assertEquals(1, accepted.revision());
            assertEquals(PlayerPhysicsProtocol.Kind.STATE, fixture.physicsReplies.getLast().kind());
            for (int i = 0; i < 10; i++) fixture.move(1);
            assertEquals(67, fixture.player.posY, 1e-10);
            assertEquals(8.5, fixture.player.posX, 1e-10);
            for (int i = 0; i < 12; i++) fixture.tick();
            assertEquals(65, fixture.player.posY, 1e-10, "the same collision path lands on the actual floor");
            assertTrue(fixture.player.onGround);
            assertEquals(-1.568, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().y(), 1e-7,
                    "native grounded state retains next tick's downward gravity velocity");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void playerMagneticResponseChangesActualDisplacement(boolean ironBoots, @TempDir Path directory) {
        GravityEntityBinder previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 67, 8.5);
            if (ironBoots) fixture.player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunkProvider().loadedChunks.get(0L)));
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.tick();
            assertEquals(ironBoots ? 66.9216 : 67, fixture.player.posY, 1e-7);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void remoteCompletedPacketTransfersAuthorityWithoutChangingG(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty());
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(2);
            var snapshot = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(40, snapshot.worldVelocity().x(), 1e-10);
            assertEquals(10, fixture.player.posX);
            assertFalse(fixture.remote.hasMovementCustody(fixture.player.connection));
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
            assertEquals(fixture.peer.application(), fixture.physicsReplies.getLast().application());
            fixture.move(1);
            assertEquals(10, fixture.player.posX);
            fixture.remote.receive(BoostProtocol.modeRequest(fixture.nonce, fixture.epoch, 100, 0, true), fixture.player.connection);
            assertFalse(fixture.remote.hasMovementCustody(fixture.player.connection), "late MODE cannot resurrect retired writer");
        }
    }

    @Test void remoteHandoffPacketBurstCannotSpendAnotherTickOfMovement(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(2);
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(10, fixture.player.posX);
            assertFalse(fixture.remote.hasMovementCustody(fixture.player.connection));
            for (int burst = 0; burst < 10; burst++) {
                fixture.applyRemote(offer, 2);
                fixture.move(3.5);
                fixture.physics.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
                assertEquals(10, fixture.player.posX, 0, "no same-world-tick movement after the 2m legacy step");
                assertEquals(accepted, fixture.physics.accepted(fixture.player.connection).orElseThrow());
            }
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, 37})
    void ordinaryAdmissionHonorsDisabledOrPositiveConfiguredCap(double configured, @TempDir Path directory) {
        double previous = UnigravConfig.maxEntitySpeed;
        UnigravConfig.maxEntitySpeed = configured;
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8, 65, 8);
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(2); // 40 m/s exceeds 37; config0 disables the configuration cap, not motion.
            assertEquals(configured == 0, fixture.physics.owns(fixture.player));
            assertEquals(10, fixture.player.posX, 0, "admission never rolls back the real completed native move");
            if (configured == 0)
                assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
        } finally { UnigravConfig.maxEntitySpeed = previous; }
    }

    @ParameterizedTest
    @ValueSource(doubles = {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void invalidOrdinaryCapFailsWithoutInstallingOwnership(double configured, @TempDir Path directory) {
        double previous = UnigravConfig.maxEntitySpeed;
        UnigravConfig.maxEntitySpeed = configured;
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8, 65, 8);
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            assertThrows(IllegalArgumentException.class, () -> fixture.move(2));
            assertFalse(fixture.physics.owns(fixture.player));
            assertEquals(10, fixture.player.posX, 0);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(fixture.player.connection).isEmpty());
        } finally { UnigravConfig.maxEntitySpeed = previous; }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedFallCallbackCannotLeaveTheOwnerPermanentlyAtPreMovePlacement(boolean damageBeforeFailure,
                                                                          @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        boolean previousImpact = UnigravConfig.impactDamage;
        UnigravConfig.impactDamage = false;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var failure = new IllegalStateException("one-shot fall callback failure");
            var fired = new AtomicBoolean();
            var floor = new Block(Material.ROCK) {
                @Override public void onFallenUpon(World world, BlockPos pos, Entity actor, float distance) {
                    if (fired.compareAndSet(false, true)) {
                        if (damageBeforeFailure) super.onFallenUpon(world, pos, actor, distance);
                        throw failure;
                    }
                    throw new AssertionError("consumed landing callback was replayed");
                }
            };
            for (int tick = 0; tick < 60; tick++) fixture.player.onUpdate();
            fixture.world.put(new BlockPos(8, 64, 8), floor.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.adoptOrdinary();
            fixture.player.fallDistance = 8;
            fixture.physicsReplies.clear();
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(0, -1, 0)));
            assertSame(failure, assertThrows(IllegalStateException.class, fixture::tick));
            assertTrue(fired.get());
            assertEquals(65, fixture.player.posY, 0, "collision movement already happened before the callback");
            assertEquals(65, fixture.physics.accepted(fixture.player.connection).orElseThrow().placement().transport().y());
            assertEquals(0, fixture.player.fallDistance, 0, "consume the interrupted landing, not its side effects twice");
            assertEquals(damageBeforeFailure ? 15 : 20, fixture.player.getHealth(), 1e-5);
            assertTrue(fixture.physicsReplies.isEmpty(), "failed tick must not emit a normal-success STATE");
            var committed = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            fixture.physics.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            assertEquals(committed, fixture.physics.accepted(fixture.player.connection).orElseThrow(),
                    "retrying the same world tick cannot redo the committed step");
            fixture.player.hurtResistantTime = 0;
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(.1, -.01, 0)));
            fixture.tick();
            assertTrue(fixture.physics.owns(fixture.player));
            // Resting on the zero-G floor now applies 2 m/s² tangential friction: (2 - .1) * .05.
            assertEquals(8.595, fixture.player.posX, 1e-8, "next tick can move from the committed position");
            assertEquals(fixture.player.posY,
                    fixture.physics.accepted(fixture.player.connection).orElseThrow().placement().transport().y(), 0,
                    "a recoverable one-shot tail failure must not strand the owner at its old placement");
            assertEquals(damageBeforeFailure ? 15 : 20, fixture.player.getHealth(), 1e-5);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.impactDamage = previousImpact;
        }
    }

    @ParameterizedTest
    @CsvSource({"fall,teleport", "fall,retire", "land,teleport", "land,retire", "walk,teleport", "walk,retire"})
    void failedFallCallbackCannotRestoreSupersededAuthority(String stage, String change, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            var failure = new IllegalStateException("fall callback changed authority then failed");
            int[] teleportId = {-1};
            var floor = new Block(Material.ROCK) {
                private void changeAuthorityAndFail() {
                    if (change.equals("teleport")) teleportId[0] = fixture.teleport(12, 70, 8);
                    else fixture.physics.stop();
                    throw failure;
                }
                @Override public void onFallenUpon(World world, BlockPos pos, Entity actor, float distance) {
                    if (stage.equals("fall")) changeAuthorityAndFail();
                    super.onFallenUpon(world, pos, actor, distance);
                }
                @Override public void onLanded(World world, Entity actor) {
                    super.onLanded(world, actor);
                    if (stage.equals("land")) changeAuthorityAndFail();
                }
                @Override public void onEntityWalk(World world, BlockPos pos, Entity actor) {
                    super.onEntityWalk(world, pos, actor);
                    if (stage.equals("walk")) changeAuthorityAndFail();
                }
            };
            fixture.world.put(new BlockPos(8, 64, 8), floor.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.adoptOrdinary();
            fixture.player.fallDistance = 8;
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(0, -1, 0)));
            assertSame(failure, assertThrows(IllegalStateException.class, fixture::tick));
            if (change.equals("retire")) {
                assertFalse(fixture.physics.owns(fixture.player));
                assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty());
            } else {
                assertEquals(70, fixture.player.posY, 0);
                fixture.tick();
                assertEquals(70, fixture.player.posY, 0);
                fixture.player.connection.processConfirmTeleport(new CPacketConfirmTeleport(teleportId[0]));
                fixture.physics.confirmNativeTeleport(fixture.player.connection, teleportId[0], true);
                assertEquals(70, fixture.physics.accepted(fixture.player.connection).orElseThrow().placement().transport().y());
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void negotiatedRemoteReadyPeerCanEnterOrdinaryPhysics(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true, false)) {
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(2);
            assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            assertFalse(fixture.intent.observe(fixture.player).orElseThrow().active());
            fixture.remote.receive(BoostProtocol.modeRequest(fixture.nonce, fixture.epoch, 100, 0, true), fixture.player.connection);
            assertFalse(fixture.remote.hasMovementCustody(fixture.player.connection));
        }
    }

    @Test void remoteFailedOwnedSendCannotRestoreItsLegacyWriter(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.failOwned = true;
            assertSame(fixture.sendFailure, assertThrows(IllegalStateException.class, () -> fixture.move(2)));
            assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            assertFalse(fixture.remote.hasMovementCustody(fixture.player.connection));
            fixture.move(2);
            assertEquals(10, fixture.player.posX);
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
        }
    }

    @Test void aFailedNonePublicationDoesNotPoisonLaterAppliedState(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.failRemoteState = true;
            fixture.remote.receive(BoostProtocol.modeRequest(fixture.nonce, fixture.epoch, 2, 0, false), fixture.player.connection);
            assertEquals(1, fixture.failedRemoteSends, "Minecraft scheduler captures the thrown transport failure in its future");
            fixture.failRemoteState = false;
            fixture.remote.receive(BoostProtocol.modeRequest(fixture.nonce, fixture.epoch, 3, 0, true), fixture.player.connection);
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(2);
            assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
        }
    }

    @Test void staleAppliedIdentityCannotTransferRemoteAuthority(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.move(0);
            fixture.tick();
            var applied = fixture.peer.application().orElseThrow();
            var stale = new PlayerPhysicsProtocol.ApplicationId(new PlayerPhysicsProtocol.OperationId(
                    applied.state().nonce(), applied.state().epoch(), 0, BoostProtocol.Operation.NONE), Optional.empty());
            fixture.physics.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY,
                    offer.scope(), 1, offer.offerSequence(), Optional.of(stale), Optional.empty(), Optional.empty()), fixture.player.connection);
            fixture.move(2);
            assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty());
            assertTrue(fixture.remote.hasMovementCustody(fixture.player.connection));
            assertEquals(10, fixture.player.posX, "completed legacy movement is not rolled back");
        }
    }

    @Test void gSourceMismatchRefusesTransferWithoutChangingEitherIntent(@TempDir Path directory) {
        try (var fixture = new Fixture(directory, true)) {
            fixture.intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, fixture.gOffer.key(), 2,
                    GIntentProtocol.State.OFF), fixture.player.connection);
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.applyRemote(offer, 1);
            fixture.move(0);
            fixture.tick();
            fixture.applyRemote(offer, 2);
            fixture.move(2);
            assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty());
            assertTrue(fixture.intent.observe(fixture.player).orElseThrow().active());
            assertFalse(fixture.intent.observeNegotiated(fixture.player).orElseThrow().active());
        }
    }

    @Test void offersRequireAnExplicitGSourceAndNeverWritePlayerMotion(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.player.motionX = 2;
            fixture.tick();
            assertTrue(fixture.physicsReplies.isEmpty());
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER, offer.kind());
            assertEquals(fixture.player.getUniqueID(), offer.scope().playerId());
            assertEquals(2, fixture.player.motionX);
            assertTrue(offer.snapshot().isEmpty());
            assertNotEquals(fixture.gOffer.key().sessionId(), offer.scope().sessionId());
        }
    }

    @Test void ordinaryOwnershipAdoptsCompletedCentresNotNativeMotion(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8, 65, 8);
            fixture.player.motionX = 123;
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty(), "one sample is not zero velocity");
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(2);
            var accepted = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            assertEquals(40, accepted.worldVelocity().x(), 1e-10);
            assertEquals(10, accepted.placement().center().x(), 1e-10);
            assertEquals(offer.scope(), accepted.scope());
            assertEquals(PlayerPhysicsProtocol.Kind.OWNED, fixture.physicsReplies.getLast().kind());
            assertEquals(10, fixture.player.posX, "handoff tick must not integrate another move");
            fixture.move(1);
            assertEquals(10, fixture.player.posX, "late legacy packet must not move the newly owned actor");
        }
    }

    @Test void ordinaryTwoTickIntervalHasTheSameSecantSpeed(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8, 65, 8);
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(4);
            assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
        }
    }

    @Test void failedOwnedSendKeepsInstalledAuthorityAndDoesNotRepeatLegacyMove(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8, 65, 8);
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.failOwned = true;
            assertSame(fixture.sendFailure, assertThrows(IllegalStateException.class, () -> fixture.move(2)));
            assertEquals(40, fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x(), 1e-10);
            fixture.move(2);
            assertEquals(10, fixture.player.posX);
            assertEquals(false, fixture.intent.observe(fixture.player).orElseThrow().active());
        }
    }

    @Test void expiredCentresCannotBeReplacedWithAZeroVelocityAdoption(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.move(0);
            for (int i = 0; i < 3; i++) fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 2), fixture.player.connection);
            fixture.move(1);
            assertTrue(fixture.physics.accepted(fixture.player.connection).isEmpty());
            assertTrue(fixture.physicsReplies.isEmpty());
        }
    }

    @Test void currentApplySuppressesRetryOnlyForItsBoundedWindow(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            for (int i = 0; i < 19; i++) fixture.tick();
            fixture.physics.receive(apply(offer.scope(), offer.offerSequence(), 1), fixture.player.connection);
            fixture.tick();
            assertTrue(fixture.physicsReplies.isEmpty(), "fresh APPLY is pending, not an OWNED reply");
            for (int i = 0; i < 3; i++) fixture.tick();
            assertEquals(1, fixture.physicsReplies.size());
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER, fixture.physicsReplies.getFirst().kind());
        }
    }

    @Test void foreignApplyCannotSuppressCurrentProposalAndUnregisterRetiresIt(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            var foreign = new PlayerPhysicalSnapshot.Scope(offer.scope().playerId(), new UUID(0, 999), 0, offer.scope().worldEpoch());
            fixture.physics.receive(apply(foreign, offer.offerSequence(), 1), fixture.player.connection);
            for (int i = 0; i < 20; i++) fixture.tick();
            assertEquals(1, fixture.physicsReplies.size());
            fixture.physicsReplies.clear();
            fixture.physics.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(fixture.player.connection.netManager,
                    Set.of(PlayerPhysicsProtocol.CHANNEL), "UNREGISTER", Side.SERVER, INetHandlerPlayServer.class));
            for (int i = 0; i < 25; i++) fixture.tick();
            assertTrue(fixture.physicsReplies.isEmpty());
        }
    }

    @Test void respawnReplacesThePhysicalScopeWithoutReusingTheGSession(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.confirmOff();
            fixture.tick();
            var old = fixture.physicsReplies.removeFirst();
            fixture.physics.onRespawn(new PlayerEvent.PlayerRespawnEvent(fixture.player, false));
            fixture.tick();
            var fresh = fixture.physicsReplies.removeFirst();
            assertNotEquals(old.scope(), fresh.scope());
            assertTrue(fresh.scope().worldEpoch() > old.scope().worldEpoch());
            assertNotEquals(fixture.gOffer.key().sessionId(), fresh.scope().sessionId());
            fixture.physics.receive(apply(old.scope(), old.offerSequence(), 100), fixture.player.connection);
            for (int i = 0; i < 20; i++) fixture.tick();
            assertEquals(1, fixture.physicsReplies.size(), "old lifetime cannot consume current proposal retry");
        }
    }

    @Test void replayedApplyCannotExtendItsPendingLifetime(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.confirmOff();
            fixture.tick();
            var offer = fixture.physicsReplies.removeFirst();
            var apply = apply(offer.scope(), offer.offerSequence(), 1);
            fixture.physics.receive(apply, fixture.player.connection);
            for (int i = 0; i < 20; i++) {
                fixture.physics.receive(apply, fixture.player.connection);
                fixture.tick();
            }
            assertEquals(1, fixture.physicsReplies.size());
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER, fixture.physicsReplies.getFirst().kind());
        }
    }

    @Test void clientLocalMotionWritesFollowAppliedOwnershipAndNativeDelegation(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            var owned = fixture.adoptOrdinary();
            var mirror = fixture.world.player(fixture.player.getUniqueID());
            var client = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(mirror, snapshot),
                    () -> {}, () -> {});
            client.runLegacyMotion(mirror, () -> mirror.motionY += .15);
            assertEquals(.15, mirror.motionY, 0, "negotiation does not block native flight");
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,
                    owned.scope(), 0, owned.offerSequence(), Optional.empty(), Optional.empty(), Optional.empty())));
            client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), packet -> {});
            assertTrue(client.receive(owned));
            double acceptedY = mirror.motionY;
            client.runLegacyMotion(mirror, () -> mirror.motionY += .15);
            assertEquals(acceptedY, mirror.motionY, 0, "SP flight must not add another vertical input");
            client.runLegacyMotion(mirror, () -> { mirror.motionX = -.1; mirror.motionZ = .1; });
            assertEquals(0, mirror.motionX, 0, "native push-out must not replace accepted velocity");
            assertEquals(0, mirror.motionZ, 0);
            var other = fixture.world.player(new UUID(0, 773));
            client.runLegacyMotion(other, () -> other.motionY = .15);
            assertEquals(.15, other.motionY, 0, "the local lifetime cannot suppress another actor");
            mirror.noClip = true;
            client.runLegacyMotion(mirror, () -> mirror.motionY += .15);
            assertEquals(acceptedY + .15, mirror.motionY, 0, "immediate native state permits native input");
            mirror.noClip = false;
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE,
                    owned.scope(), owned.sequence() + 1, owned.offerSequence(),
                    Optional.empty(), Optional.empty(), Optional.empty())));
            client.runLegacyMotion(mirror, () -> mirror.motionY += .15);
            assertEquals(acceptedY + .3, mirror.motionY, 1e-12, "custody alone must not block native movement");
        }
    }

    @Test void nativeVelocityDeltaEntersTheOwnedStepExactlyOnceWithoutAdoptingRawMotion(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            double x = fixture.player.posX;
            fixture.player.motionX = 100; // A stale native carrier is never the new physical velocity.
            var delta = new Vec3(.1, 0, 0);
            if (!fixture.physics.captureNativeVelocityDelta(fixture.player, delta))
                fixture.player.addVelocity(delta.x(), delta.y(), delta.z());
            fixture.tick();
            assertEquals(x + .1, fixture.player.posX, 1e-6);
            double acceptedVelocity = fixture.physics.accepted(fixture.player.connection).orElseThrow().worldVelocity().x();
            x = fixture.player.posX;
            fixture.tick();
            assertEquals(x + acceptedVelocity * .05, fixture.player.posX, 1e-6, "external delta must not replay");
            fixture.player.noClip = true;
            assertFalse(fixture.physics.captureNativeVelocityDelta(fixture.player, delta),
                    "native delegation must retain its own velocity application");
            assertFalse(fixture.physics.addImpulse(fixture.player, new Vec3(10, 0, 0)),
                    "native delegation must not defer J/m until physical resumption");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNBOUND", "ADDITIVE", "REPLACE", "NATIVE_NO_GRAVITY"})
    void ordinaryOwnershipPreservesNativeGravityPolicy(String mode, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var field = GravityFields.get().registerContribution("unigrav_test", "native_policy", (pos, time) -> new Vec3(0, -16, 0));
             var binding = mode.equals("ADDITIVE") || mode.equals("REPLACE")
                     ? UnigravMod.entityBinder.bind(fixture.player, GravityMode.valueOf(mode)) : null) {
            fixture.player.setPosition(8.5, 70, 8.5);
            if (mode.equals("NATIVE_NO_GRAVITY")) fixture.player.setNoGravity(true);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            fixture.tick();
            double acceleration = switch (mode) {
                case "UNBOUND" -> -32;
                case "ADDITIVE" -> -48;
                case "REPLACE" -> -16;
                default -> 0;
            };
            assertEquals(acceleration * .0025 * (double) .98F, fixture.player.motionY, 1e-8,
                    "native gravity must be retained or replaced exactly once");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removingReplacementWhileOwnedRestoresTheNativeGravityPolicy(boolean originallyDisabled, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setNoGravity(originallyDisabled);
            var registration = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            var before = fixture.physics.accepted(fixture.player.connection).orElseThrow();
            registration.close();
            fixture.tick();
            assertTrue(fixture.physics.accepted(fixture.player.connection).orElseThrow().revision() > before.revision());
            assertEquals(originallyDisabled ? 0 : -.08 * (double) .98F, fixture.player.motionY, 1e-8);
            fixture.physics.stop();
            assertEquals(originallyDisabled, fixture.player.hasNoGravity(), "do not resurrect a removed replacement flag");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void replacementAddedWhileOwnedRetainsItsGravityFlagAfterRetirement(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            try (var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
                fixture.physics.stop();
                assertTrue(fixture.player.hasNoGravity(), "active replacement still owns gravity suppression");
            }
            assertFalse(fixture.player.hasNoGravity(), "later unbind restores the pre-owner native policy");
        } finally {
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {.001, .004})
    void ordinarySmallVelocityMatchesTheActualNativeLivingUpdate(double motion, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setNoGravity(true);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary(motion);
            var nativePlayer = fixture.world.serverPlayer(new UUID(0, 882));
            nativePlayer.setPosition(fixture.player.posX, fixture.player.posY, fixture.player.posZ);
            nativePlayer.setNoGravity(true);
            nativePlayer.motionZ = motion;
            nativePlayer.onLivingUpdate();
            fixture.tick();
            assertEquals(nativePlayer.posZ, fixture.player.posZ, 1e-8);
            assertEquals(nativePlayer.motionZ, fixture.player.motionZ, 1e-8);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void ownedMovementUpdatesNativeStatisticsOnceAndOldPacketsCannotAddDistance(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setNoGravity(true);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary(.4);
            int before = fixture.player.getStatFile().readStat(StatList.FLY_ONE_CM);
            fixture.tick();
            assertEquals(before + 40, fixture.player.getStatFile().readStat(StatList.FLY_ONE_CM));
            fixture.move(4);
            assertEquals(before + 40, fixture.player.getStatFile().readStat(StatList.FLY_ONE_CM),
                    "retired CP cannot account a second movement");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test void retiringOwnerDuringEnvironmentCallbackCannotMoveOrPublishTheOldLifetime(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            fixture.player.setPosition(8.5, 70, 8.5);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary(.4);
            double before = fixture.player.posZ;
            fixture.physicsReplies.clear();
            var fired = new AtomicBoolean();
            try (var source = GravityFields.get().registerContribution("unigrav_test", "retire_during_sample", (pos, time) -> {
                if (fired.compareAndSet(false, true)) fixture.physics.stop();
                return Vec3.ZERO;
            })) {
                fixture.tick();
                assertTrue(fired.get());
                assertFalse(fixture.physics.owns(fixture.player));
                assertEquals(before, fixture.player.posZ, 0, "retired owner must not complete the sampled step");
                assertTrue(fixture.physicsReplies.isEmpty(), "retired scope must not emit STATE");
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedLandingUsesNativeOrConfiguredImpactDamage(boolean velocityDamage, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        boolean previousImpact = UnigravConfig.impactDamage;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.impactDamage = velocityDamage;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            for (int tick = 0; tick < 60; tick++) fixture.player.onUpdate();
            fixture.player.setPosition(8.5, 65.5, 8.5);
            fixture.world.put(new BlockPos(8, 64, 8), Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            fixture.player.fallDistance = 8;
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player, new Vec3(0, -1, 0)));
            fixture.tick();
            assertTrue(fixture.player.onGround);
            assertEquals(65, fixture.player.posY, 0);
            assertEquals(velocityDamage ? 20 - .021 * (400 - 196) : 15, fixture.player.getHealth(), 1e-4);
            assertEquals(0, fixture.player.fallDistance, 0);
            if (velocityDamage) {
                float health = fixture.player.getHealth();
                var reports = new ImpactDamageHandler(fixture.physics);
                fixture.player.hurtResistantTime = 0;
                reports.applyReportedPlayerImpact(fixture.player, ImpactReportMessage.TYPE_FALL, 20);
                assertEquals(health, fixture.player.getHealth(), 0, "old client report must not damage an owned actor twice");
                fixture.physics.stop();
                reports.applyReportedPlayerImpact(fixture.player, ImpactReportMessage.TYPE_FALL, 20);
                assertTrue(fixture.player.getHealth() < health, "legacy reports remain available without physical ownership");
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.impactDamage = previousImpact;
        }
    }

    @Test void anotherPlayerCannotBorrowAnOwnedConnection(@TempDir Path directory) {
        try (var fixture = new Fixture(directory)) {
            fixture.adoptOrdinary();
            var other = fixture.world.serverPlayer(new UUID(0, 889));
            var original = other.connection;
            try {
                other.connection = fixture.player.connection;
                assertFalse(fixture.physics.owns(other), "connection identity alone is not actor ownership");
            } finally {
                other.connection = original;
            }
            var primary = fixture.player.connection;
            try {
                var alias = new NetHandlerPlayServer(fixture.world.world().getMinecraftServer(), primary.netManager, other);
                primary.netManager.setNetHandler(primary);
                assertTrue(fixture.physics.accepted(alias).isEmpty(), "shared manager cannot authenticate another handler");
                assertFalse(fixture.physics.owns(other));
                assertTrue(fixture.physics.owns(fixture.player));
            } finally {
                primary.netManager.setNetHandler(primary);
                other.connection = original;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedWallAndCeilingUseTheCommittedCollisionSpeed(boolean ceiling, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        boolean previousImpact = UnigravConfig.impactDamage;
        UnigravMod.entityBinder = new GravityEntityBinder();
        UnigravConfig.impactDamage = true;
        try (var fixture = new Fixture(directory);
             var binding = UnigravMod.entityBinder.bind(fixture.player, GravityMode.REPLACE)) {
            for (int tick = 0; tick < 60; tick++) fixture.player.onUpdate();
            fixture.player.setPosition(8.5, 65.5, 8.5);
            if (ceiling) fixture.world.put(new BlockPos(8, 68, 8), Blocks.STONE.getDefaultState());
            else for (int y = 65; y <= 67; y++) fixture.world.put(new BlockPos(9, y, 8), Blocks.STONE.getDefaultState());
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 0)));
            fixture.adoptOrdinary();
            assertTrue(fixture.physics.captureNativeVelocityDelta(fixture.player,
                    ceiling ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)));
            fixture.tick();
            assertTrue(ceiling ? fixture.player.collidedVertically : fixture.player.collidedHorizontally);
            assertFalse(fixture.player.onGround);
            assertEquals(20 - .021 * (400 - 196), fixture.player.getHealth(), 1e-4);
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            UnigravConfig.impactDamage = previousImpact;
        }
    }

    @Test void ownedMovementAdvancesRealPlayerChunkSubscriptions(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
        try (var fixture = new Fixture(directory)) {
            for (int x = -3; x <= 3; x++) for (int z = -2; z <= 5; z++) fixture.world.installColumn(x, z);
            fixture.player.setPosition(8.5, 70, 8.5);
            fixture.player.setNoGravity(true);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 1)));
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world.world().getChunk(0, 2)));
            fixture.adoptOrdinary(16);
            var chunks = fixture.world.world().getPlayerChunkMap();
            chunks.setPlayerViewRadius(3);
            chunks.addPlayer(fixture.player);
            try {
                assertEquals(24.5, fixture.player.managedPosZ, 0);
                var oldEdge = chunks.getEntry(0, -2);
                assertTrue(oldEdge.containsPlayer(fixture.player));
                fixture.tick();
                assertEquals(40.5, fixture.player.posZ, 1e-8);
                assertEquals(fixture.player.posZ, fixture.player.managedPosZ, 0,
                        "accepted movement must advance native tracking, not just the entity position");
                assertFalse(oldEdge.containsPlayer(fixture.player));
                assertTrue(chunks.getEntry(0, 5).containsPlayer(fixture.player));
                int correction = fixture.teleport(8.5, 70, 24.5);
                fixture.player.connection.processConfirmTeleport(new CPacketConfirmTeleport(correction));
                fixture.physics.confirmNativeTeleport(fixture.player.connection, correction, true);
                assertEquals(24.5, fixture.player.managedPosZ, 0, "confirmed correction also refreshes tracking");
                assertTrue(chunks.getEntry(0, -2).containsPlayer(fixture.player));
            } finally {
                chunks.removePlayer(fixture.player);
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    private static PlayerPhysicsProtocol.Packet apply(PlayerPhysicalSnapshot.Scope scope, long proposal, long sequence) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, scope, sequence, proposal,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static final class Fixture implements AutoCloseable {
        private final BootWorldFixture world;
        private final EntityPlayerMP player;
        private final EmbeddedChannel channel;
        private final ArrayList<GIntentProtocol.Packet> gReplies = new ArrayList<>();
        private final ArrayList<PlayerPhysicsProtocol.Packet> physicsReplies = new ArrayList<>();
        private final ArrayList<BoostProtocol.Packet> remoteReplies = new ArrayList<>();
        private final RemoteApplicationFixture peer = new RemoteApplicationFixture();
        private final IllegalStateException sendFailure = new IllegalStateException("transport unavailable");
        private boolean failRemoteState;
        private int failedRemoteSends;
        private final RemoteBoostServer remote = new RemoteBoostServer((actor, packet) -> {
            if (failRemoteState && packet.kind() == BoostProtocol.Kind.STATE) {
                failedRemoteSends++;
                throw sendFailure;
            }
            remoteReplies.add(packet);
            peer.receive(packet);
        });
        private final GIntentServer intent = new GIntentServer((actor, packet) -> gReplies.add(packet), remote);
        private boolean failOwned;
        private boolean failState;
        private final PlayerPhysicsServer physics = new PlayerPhysicsServer(intent, (actor, packet) -> {
            if (failOwned && packet.kind() == PlayerPhysicsProtocol.Kind.OWNED) throw sendFailure;
            if (failState && packet.kind() == PlayerPhysicsProtocol.Kind.STATE) throw sendFailure;
            physicsReplies.add(packet);
        });
        private final PlayerMovementRouter router = new PlayerMovementRouter(remote, physics);
        private GIntentProtocol.Packet gOffer;
        private final boolean remoteEnabled;
        private final boolean oldEnabled = UnigravConfig.remoteCreativeBoost;
        private long nonce;
        private long epoch;
        private long frameSequence;

        private Fixture(Path directory) {
            this(directory, false);
        }

        private Fixture(Path directory, boolean remoteEnabled) {
            this(directory, remoteEnabled, remoteEnabled);
        }

        private Fixture(Path directory, boolean remoteEnabled, boolean active) {
            this(directory, remoteEnabled, active, null);
        }

        private Fixture(Path directory, boolean remoteEnabled, boolean active, Consumer<EntityPlayerMP> afterJump) {
            this.remoteEnabled = remoteEnabled;
            world = BootWorldFixture.open(directory, new UUID(0, 971), true);
            player = world.serverPlayer(new UUID(0, 972), afterJump);
            if (remoteEnabled) {
                UnigravConfig.remoteCreativeBoost = true;
                player.interactionManager.setGameType(GameType.CREATIVE);
                player.setPosition(8, 65, 8);
            }
            channel = new EmbeddedChannel(player.connection.netManager);
            var connected = new FMLNetworkEvent.ServerConnectionFromClientEvent(player.connection.netManager);
            var registered = new FMLNetworkEvent.CustomPacketRegistrationEvent<>(player.connection.netManager,
                    Set.of(GIntentProtocol.CHANNEL, PlayerPhysicsProtocol.CHANNEL), "REGISTER", Side.SERVER, INetHandlerPlayServer.class);
            intent.onConnection(connected);
            physics.onConnection(connected);
            intent.onRegistration(registered);
            physics.onRegistration(registered);
            intent.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
            gOffer = gReplies.removeFirst();
            if (remoteEnabled) {
                remote.onConnection(connected);
                remote.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(player.connection.netManager,
                        Set.of(BoostProtocol.CHANNEL), "REGISTER", Side.SERVER, INetHandlerPlayServer.class));
                remote.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
                nonce = remoteReplies.getLast().nonce();
                remote.receive(BoostProtocol.hello(nonce, true), player.connection);
                if (active) remote.receive(BoostProtocol.modeRequest(nonce, remoteReplies.getLast().epoch(), 1, 0, true), player.connection);
                epoch = remoteReplies.getLast().epoch();
                intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, gOffer.key(), 0,
                        GIntentProtocol.State.UNKNOWN), player.connection);
                intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, gOffer.key(), 1,
                        active ? GIntentProtocol.State.ON : GIntentProtocol.State.OFF), player.connection);
            }
        }

        private void applyRemote(PlayerPhysicsProtocol.Packet offer, long sequence) {
            physics.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, offer.scope(), sequence,
                    offer.offerSequence(), peer.application(), Optional.empty(), Optional.empty()), player.connection);
        }

        private PlayerPhysicsProtocol.Packet adoptRemote() {
            tick();
            var offer = physicsReplies.removeFirst();
            applyRemote(offer, 1);
            move(0);
            tick();
            applyRemote(offer, 2);
            move(0);
            assertTrue(physics.owns(player));
            return physicsReplies.getLast();
        }

        private PlayerPhysicsProtocol.Packet adoptOrdinary() {
            return adoptOrdinary(0);
        }

        private PlayerPhysicsProtocol.Packet adoptOrdinary(double finalZDisplacement) {
            confirmOff();
            tick();
            var offer = physicsReplies.removeFirst();
            physics.receive(apply(offer.scope(), offer.offerSequence(), 1), player.connection);
            move(0);
            tick();
            physics.receive(apply(offer.scope(), offer.offerSequence(), 2), player.connection);
            move(0, 0, finalZDisplacement);
            assertTrue(physics.owns(player));
            return physicsReplies.getLast();
        }

        private void input(PlayerPhysicsProtocol.Packet owned, long sequence, PlayerPhysicsProtocol.Controls controls) {
            physics.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, owned.scope(), sequence,
                    owned.offerSequence(), Optional.empty(), Optional.empty(), Optional.of(controls)), player.connection);
        }

        private void confirmOff() {
            intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, gOffer.key(), 0,
                    GIntentProtocol.State.UNKNOWN), player.connection);
            intent.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, gOffer.key(), 1,
                    GIntentProtocol.State.OFF), player.connection);
            assertEquals(false, intent.observe(player).orElseThrow().active());
        }
        private void tick() {
            if (remoteEnabled) remote.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
            world.world().setTotalWorldTime(world.world().getTotalWorldTime() + 1);
            physics.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
        }

        private int teleport(double x, double y, double z) {
            player.connection.setPlayerLocation(x, y, z, 0, 0);
            channel.runPendingTasks();
            SPacketPlayerPosLook correction = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null)
                if (outbound instanceof SPacketPlayerPosLook packet) correction = packet;
            assertNotNull(correction, "use the actual native correction ID");
            physics.observeNativeTeleport(player.connection, correction.getTeleportId());
            return correction.getTeleportId();
        }

        private void move(double x) {
            move(x, 0, 0);
        }

        private void move(double x, double y, double z) {
            if (remoteEnabled && remote.hasMovementCustody(player.connection))
                remote.receive(BoostProtocol.frame(nonce, epoch, ++frameSequence, 0, 5, false), player.connection);
            router.runPacket(player.connection, () -> {
                Vec3 start = new Vec3(player.posX, player.posY, player.posZ);
                var incoming = new CPacketPlayer.Position(start.x() + x, start.y() + y, start.z() + z, false);
                if (router.handleOwnedPacket(player.connection, incoming, false)) return;
                var packet = router.begin(player.connection, incoming, start, false);
                router.move(player, MoverType.PLAYER, packet.getX(start.x()) - start.x(),
                        packet.getY(start.y()) - start.y(), packet.getZ(start.z()) - start.z());
                router.assignEndpoint(player, player.posX, player.posY, player.posZ, 0, 0, false);
                router.beforeTracking(false);
            }, () -> false);
        }
        @Override public void close() {
            try { physics.stop(); intent.stop(); remote.stop(); NativePlayerMovement.INSTANCE.stop(); }
            finally { try { channel.finishAndReleaseAll(); } finally { world.close(); UnigravConfig.remoteCreativeBoost = oldEnabled; } }
        }
    }
}
