package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.IBlockAccess;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class FreeOwnerTransactionTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004271");

    @Test void invalidFrameCannotMintFreshResourcesAtTheNextWorldTick() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("invalid-next-tick"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var travel = new PlayerPhysicsStep.TravelState();
            var store = new FreeTickResources.Store();
            var resources = store.acquire(travel, fixture.world(), accepted.scope(), travel.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true, travel, resources)) {
                CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
            }
            long units = resources.numeric().units();
            assertTrue(units > 0);
            assertThrows(IllegalStateException.class, () -> resources.requireCurrent(travel, fixture.world(),
                    accepted.scope(), travel.correctionRevision() + 1));
            fixture.world().getWorldInfo().setWorldTotalTime(fixture.world().getTotalWorldTime() + 1);
            assertThrows(IllegalStateException.class, () -> store.acquire(travel, fixture.world(), accepted.scope(),
                    travel.correctionRevision()));
            assertThrows(IllegalStateException.class, () -> store.acquire(travel, fixture.world(), accepted.scope(),
                    travel.correctionRevision()));
            assertEquals(units, resources.numeric().units());
        }
    }

    @Test void correctionIsAStoreLifetimeFenceButHealthyNextTickCreatesItsOwnFrame() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("correction-next-tick"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var travel = new PlayerPhysicsStep.TravelState();
            var changed = new FreeTickResources.Store();
            var old = changed.acquire(travel, fixture.world(), accepted.scope(), travel.correctionRevision());
            old.numeric().charge(7);
            var healthy = new FreeTickResources.Store();
            var previous = healthy.acquire(travel, fixture.world(), accepted.scope(), travel.correctionRevision());
            previous.numeric().charge(11);
            fixture.world().getWorldInfo().setWorldTotalTime(fixture.world().getTotalWorldTime() + 1);
            assertThrows(IllegalStateException.class, () -> changed.acquire(travel, fixture.world(), accepted.scope(),
                    travel.correctionRevision() + 1));
            assertThrows(IllegalStateException.class, () -> changed.acquire(travel, fixture.world(), accepted.scope(),
                    travel.correctionRevision()));
            assertEquals(7, old.numeric().units());
            var next = healthy.acquire(travel, fixture.world(), accepted.scope(), travel.correctionRevision());
            assertNotSame(previous, next);
            assertEquals(0, next.numeric().units());
            assertEquals(0, next.queries().calls());
            assertEquals(11, previous.numeric().units());
            assertSame(next, healthy.acquire(travel, fixture.world(), accepted.scope(), travel.correctionRevision()));
        }
    }

    @Test void freeResizeProducerUsesStoredFootAndChargesItsThreeRoundingStepResult() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("stored-foot-resize"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            double sourceEyeHeight = (double) 1.62F;
            var sourceDimensions = new PlayerBodyShape.Dimensions(1.0, 2.0, sourceEyeHeight);
            var source = new PlayerBodyPlacement(new Vec3d(8.5, 3, 8.5), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(sourceDimensions));
            actor.width = (float) sourceDimensions.width();
            actor.height = (float) sourceDimensions.height();
            actor.eyeHeight = (float) sourceDimensions.eyeHeight();
            actor.setPosition(source.transport().x(), source.transport().y(), source.transport().z());
            actor.setEntityBoundingBox(new AxisAlignedBB(source.bounds().min().x(), source.bounds().min().y(),
                    source.bounds().min().z(), source.bounds().max().x(), source.bounds().max().y(),
                    source.bounds().max().z()));
            var scope = new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(), actor.dimension, 1);
            var accepted = new PlayerPhysicalSnapshot(scope, 7, source, new Vec3d(0, 0, 0));
            assertEquals(2, source.freePhysicalFoot().y());

            var targetDimensions = new PlayerBodyShape.Dimensions(0.6, 1.8, sourceEyeHeight);
            actor.width = .6F;
            actor.height = 1.8F;
            actor.eyeHeight = (float) targetDimensions.eyeHeight();
            double observedRadius = (double) .6F / 2;
            actor.setEntityBoundingBox(new AxisAlignedBB(actor.posX - observedRadius, actor.posY - 0.0, actor.posZ - observedRadius,
                    actor.posX + observedRadius, actor.posY + (double) 1.8F, actor.posZ + observedRadius));
            var nativeObservation = WorldCollisionCandidates.ActorGeometry.read(actor);

            actor.width = (float) sourceDimensions.width();
            actor.height = (float) sourceDimensions.height();
            actor.eyeHeight = (float) sourceDimensions.eyeHeight();
            actor.setEntityBoundingBox(new AxisAlignedBB(source.bounds().min().x(), source.bounds().min().y(),
                    source.bounds().min().z(), source.bounds().max().x(), source.bounds().max().y(),
                    source.bounds().max().z()));

            var trustedResize = new ModeTransitionPrepared.TrustedResizeObservation(targetDimensions, nativeObservation);
            var travel = new PlayerPhysicsStep.TravelState();
            var budget = ledger(accepted);
            var initialBudget = budget.snapshot();
            double perTick = initialBudget.ceilingMps() / 20.0;
            double availableThisTick = Math.min(2 * perTick, initialBudget.distanceCredit() + perTick);
            var resourcesStore = new FreeTickResources.Store();
            var resources = resourcesStore.acquire(travel, fixture.world(), scope, travel.correctionRevision());
            try (var transaction = new FreeOwnerTransaction(actor, accepted, travel, budget, () -> true,
                    travel, resources)) {
                var preparation = ModeTransitionPrepared.prepareFreeTick(transaction, null, trustedResize,
                        new Vec3d(0, 0, 0));
                var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class, preparation);
                var target = prepared.target();

                assertEquals(new Vec3d(8.5, 2.9, 8.5), target.center());
                assertEquals(new Vec3d(8.5, 1.9999999999999998, 8.5), target.freePhysicalFoot(),
                        "the new placement must derive its rounded foot from its stored lower endpoint and radius");
                var targetFoot = target.freePhysicalFoot();
                var up = target.bodyUp();
                var eyeHeight = target.shape().dimensions().eyeHeight();
                assertEquals(new Vec3d(targetFoot.x() + up.x() * eyeHeight,
                        targetFoot.y() + up.y() * eyeHeight, targetFoot.z() + up.z() * eyeHeight), target.eye(),
                        "the cached Free eye must be derived from the measured stored-endpoint foot");
                assertTrue(prepared.centerPathUpper() >= Math.abs(target.center().y() - source.center().y()));
                assertTrue(prepared.centerPathUpper() > 0,
                        "the center displacement caused by the resize must consume movement allowance");

                var installation = ModeTransitionFinalizer.install(prepared, 1);
                var applied = assertInstanceOf(ModeTransitionFinalizer.Applied.class, installation);
                assertSame(target, applied.snapshot().placement());
                assertEquals(Math.incrementExact(accepted.revision()), applied.snapshot().revision());
                assertEquals(target.eye(), applied.snapshot().placement().eye());
                double spent = availableThisTick - budget.snapshot().distanceCredit();
                assertTrue(spent + 1e-12 >= Math.abs(target.center().y() - source.center().y()),
                        "the single real owner reservation must debit the resize center change");
                assertFalse(budget.snapshot().pending());
            }
        }
    }

    @Test void approvedNativeRainObservationRetainsItsOwnLoadedChunkSideEffect() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("native-rain"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var chunk = fixture.world().getChunkProvider().loadedChunks.get(ChunkPos.asLong(0, 0));
            fixture.world().setRainStrength(1);
            chunk.unloadQueued = true;
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger(accepted), () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var observed = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                assertTrue(observed.wet());
                assertFalse(observed.inWater());
                assertFalse(chunk.unloadQueued);
            }
        }
    }

    @Test void pureCapsuleAndLiquidHeightReadsPreserveQueuedChunksAndNeverGenerateMissingChunks() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("pure-loaded"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var provider = fixture.world().getChunkProvider();
            var chunk = provider.loadedChunks.get(ChunkPos.asLong(0, 0));
            chunk.setBlockState(new BlockPos(8, 68, 8), Blocks.WATER.getDefaultState());
            chunk.unloadQueued = true;
            assertFalse(fixture.world().isRaining());
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger(accepted), () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var observed = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                assertTrue(observed.inWater());
                assertTrue(observed.matches(tx, pose));
                assertTrue(chunk.unloadQueued);
            }
            int count = provider.loadedChunks.size();
            assertNull(provider.loadedChunks.get(ChunkPos.asLong(256, 0)));
            var distant = accepted(actor, new Vec3d(4096.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            try (var tx = new FreeOwnerTransaction(actor, distant, new PlayerPhysicsStep.TravelState(), ledger(distant), () -> true)) {
                var pose = CapsulePlacementMath.pose(distant.placement(), tx.numeric());
                assertInstanceOf(CapsuleEnvironmentObservation.Unavailable.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                assertEquals(0, tx.queries().calls());
                assertNull(provider.loadedChunks.get(ChunkPos.asLong(256, 0)));
                assertEquals(count, provider.loadedChunks.size());
                assertTrue(chunk.unloadQueued);
            }
        }
    }

    @Test void actualNativeWaterUpdatesEligibilityButChargesFlowOnlyOncePerOwnedTick() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("native-water"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var travel = new PlayerPhysicsStep.TravelState();
            var owner = new Object();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(),
                    travel.correctionRevision());
            var forces = new ArrayList<Vec3>();
            var cell = new BlockPos(8, 68, 8);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            actor.firstUpdate = true;
            actor.motionX = .125;
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true, owner, resources)) {
                var nativeEnvironment = new FreeNativeEnvironment(owner, actor, accepted, resources,
                        tx::requireCurrent, Optional.of(forces::add));
                assertFalse(nativeEnvironment.waterMovement());
                chunk.setBlockState(cell, Blocks.FLOWING_WATER.getDefaultState());
                chunk.setBlockState(cell.east(), Blocks.FLOWING_WATER.getDefaultState()
                        .withProperty(BlockLiquid.LEVEL, 1));
                actor.fallDistance = 4;
                actor.fire = 20;
                chunk.unloadQueued = true;
                var mirror = FreeNativeEnvironment.mirror(owner, actor, accepted, resources, tx::requireCurrent,
                        FreeNativeEnvironment.serverLoadedChunks(fixture.world()));
                assertTrue(mirror.waterMovement());
                assertTrue(forces.isEmpty());
                assertEquals(.125, actor.motionX);
                assertTrue(chunk.unloadQueued);
                assertTrue(nativeEnvironment.waterMovement());
                assertFalse(chunk.unloadQueued); // The real native flow callback retains its documented side effect.
                assertTrue(actor.inWater);
                assertEquals(0, actor.fallDistance);
                assertEquals(0, actor.fire);
                assertEquals(1, forces.size());
                assertEquals(.28, forces.getFirst().x(), 1e-14);
                assertEquals(0, forces.getFirst().y(), 1e-14);
                assertEquals(0, forces.getFirst().z(), 1e-14);
                assertEquals(.125, actor.motionX);
                long attempts = resources.queries().work().staticAttempts();
                assertTrue(nativeEnvironment.waterMovement());
                assertEquals(1, forces.size());
                assertEquals(attempts, resources.queries().work().staticAttempts());
                chunk.setBlockState(cell, Blocks.AIR.getDefaultState());
                assertFalse(nativeEnvironment.waterMovement());
                assertFalse(actor.inWater);
                assertTrue(resources.queries().work().staticAttempts() > attempts);
                chunk.setBlockState(cell, Blocks.FLOWING_WATER.getDefaultState());
                assertTrue(nativeEnvironment.waterMovement());
                assertEquals(1, forces.size());
                chunk.setBlockState(cell, Blocks.LAVA.getDefaultState());
                assertSame(Blocks.OBSIDIAN, fixture.world().getBlockState(cell).getBlock());
                assertFalse(nativeEnvironment.inLava());
                chunk.setBlockState(cell.east(), Blocks.AIR.getDefaultState());
                chunk.setBlockState(cell, Blocks.LAVA.getDefaultState());
                assertSame(Material.LAVA, fixture.world().getBlockState(cell).getMaterial());
                assertTrue(nativeEnvironment.inLava());
                assertFalse(nativeEnvironment.waterMovement());
            }
        }
    }

    @Test void nativeEnvironmentAndLaterMovementShareTickWorkAfterTransactionClosure() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("tick-resources"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var travel = new PlayerPhysicsStep.TravelState();
            var owner = new Object();
            var store = new FreeTickResources.Store();
            var resources = store.acquire(owner, fixture.world(), accepted.scope(), travel.correctionRevision());
            long attempts;
            long numeric;
            try (var nativeRead = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true,
                    owner, resources)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), nativeRead.numeric());
                assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(nativeRead, pose));
                attempts = nativeRead.queries().work().staticAttempts();
                numeric = nativeRead.numeric().units();
                assertTrue(attempts > 0);
                assertTrue(resources.claimWaterForce());
            }
            var revision = new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision() + 1,
                    accepted.placement(), accepted.worldVelocity());
            assertSame(resources, store.acquire(owner, fixture.world(), revision.scope(), travel.correctionRevision()));
            try (var movement = new FreeOwnerTransaction(actor, revision, travel, ledger(revision), () -> true,
                    owner, resources)) {
                assertEquals(attempts, movement.queries().work().staticAttempts());
                assertEquals(numeric, movement.numeric().units());
                assertFalse(resources.claimWaterForce());
                var pose = CapsulePlacementMath.pose(revision.placement(), movement.numeric());
                assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(movement, pose));
                assertTrue(movement.queries().work().staticAttempts() > attempts);
            }
            travel.invalidate();
            assertThrows(IllegalStateException.class,
                    () -> store.acquire(owner, fixture.world(), revision.scope(), travel.correctionRevision()));
            assertThrows(IllegalStateException.class, resources::claimWaterForce);
            assertThrows(IllegalStateException.class,
                    () -> store.acquire(owner, fixture.world(), revision.scope(), travel.correctionRevision()));
            assertEquals(attempts * 2, resources.queries().work().staticAttempts());
            store.invalidate();
            fixture.world().getWorldInfo().setWorldTotalTime(fixture.world().getTotalWorldTime() + 1);
            assertThrows(IllegalStateException.class,
                    () -> store.acquire(owner, fixture.world(), revision.scope(), travel.correctionRevision()));
            assertEquals(attempts * 2, resources.queries().work().staticAttempts());
        }
    }

    @Test void installedFullQCallbackMapsMotionAndRestoresNestedScopeWithOriginalFailure() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("callback"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var other = fixture.serverPlayer(UUID.fromString("00000000-0000-0000-0000-000000004272"));
            var attitude = new Rotation3d(0, 0, 1, 2);
            var world = attitude.rotate(new Vec3d(1, -2, 3));
            actor.motionX = world.x(); actor.motionY = world.y(); actor.motionZ = world.z();
            var original = new IllegalStateException("native callback failure");
            CardinalBlockMotion.run(actor, attitude, () -> {
                assertEquals(-2, CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.Y), 1e-14);
                CardinalBlockMotion.write(actor, CardinalBlockMotion.Axis.Y, 4);
                assertEquals(4, CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.Y), 1e-14);
                assertFalse(CardinalBlockMotion.active(other));
                assertSame(original, assertThrows(IllegalStateException.class,
                        () -> CardinalBlockMotion.run(other, CardinalFrame.X_POS, () -> {
                            assertTrue(CardinalBlockMotion.active(other));
                            assertFalse(CardinalBlockMotion.active(actor));
                            throw original;
                        })));
                assertTrue(CardinalBlockMotion.active(actor));
                assertEquals(4, CardinalBlockMotion.read(actor, CardinalBlockMotion.Axis.Y), 1e-14);
            });
            assertFalse(CardinalBlockMotion.active(actor));
            var changed = attitude.rotate(new Vec3d(1, 4, 3));
            assertEquals(changed.x(), actor.motionX, 1e-14);
            assertEquals(changed.y(), actor.motionY, 1e-14);
            assertEquals(changed.z(), actor.motionZ, 1e-14);
                CardinalBlockMotion.run(actor, attitude, () -> Blocks.STONE.onLanded(fixture.world(), actor));
                var landed = attitude.inverseRotate(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
                assertEquals(1, landed.x(), 1e-14);
                assertEquals(0, landed.y(), 1e-14);
                assertEquals(3, landed.z(), 1e-14);
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> CardinalBlockMotion.run(actor, attitude, () -> { throw original; })));
            assertFalse(CardinalBlockMotion.active(actor));
        }
    }

    @Test void actualCapsuleFireObservationExcludesProxyCorner() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("fire-corner"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.6, 66.6, 8.6), new Rotation3d(0, 0, 1, 2), 1, 1);
            var cell = new BlockPos(9, 67, 9);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(cell, Blocks.FIRE.getDefaultState());
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger(accepted), () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var result = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                assertFalse(result.burning(), "a proxy corner cannot grant actual capsule fire overlap");
                assertTrue(result.cells().stream().anyMatch(c -> c.position().equals(cell)
                        && c.voxel().relation() == Relation.SEPARATED));
                assertTrue(tx.queries().work().staticAttempts() > 0);
                assertTrue(result.matches(tx, pose));
            }
        }
    }

    @Test void logicalDoubleDimensionsRetainActualCapsuleAndNativeFloatImage() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("native-float-image"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68, 8.5), new Rotation3d(0, 0, 1, 2), .6D, 1.8D);
            assertNotEquals(accepted.placement().shape().dimensions().width(), (double) actor.width);
            assertNotEquals(accepted.placement().shape().dimensions().height(), (double) actor.height);
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger(accepted), () -> true)) {
                tx.requireCurrent();
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                assertEquals(.3D, pose.capsule().radius());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(.125, 0, 0), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(accepted, tube),
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                        CapsuleMovementResolver.resolve(tx, tube, capture));
                var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(tx, movement));
                var installed = ready.commit();
                assertEquals(.6D, installed.placement().shape().dimensions().width());
                assertEquals(1.8D, installed.placement().shape().dimensions().height());
                assertEquals(.3D, installed.placement().capsule().radius());
                assertEquals(Float.floatToIntBits((float) .6D), Float.floatToIntBits(actor.width));
                tx.requireCurrent();
                actor.width = Math.nextUp(actor.width);
                assertThrows(IllegalStateException.class, tx::requireCurrent);
            }
        }
    }

    @Test void actualLiquidHeightAndAboveDependencyDoNotUseFullVoxelImmersion() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("fluid-height"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.95, 8.5), CardinalFrame.Y_POS.attitude(), .0625, .0625);
            var cell = new BlockPos(8, 68, 8);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(cell, Blocks.WATER.getDefaultState());
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger(accepted), () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var observation = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                var waterCell = observation.cells().stream().filter(c -> c.position().equals(cell)).findFirst().orElseThrow();
                assertEquals(Relation.OVERLAPPING, waterCell.voxel().relation());
                assertEquals(Relation.SEPARATED, waterCell.fluid().orElseThrow().relation());
                assertFalse(observation.inWater());
                assertTrue(observation.matches(tx, pose));
                assertTrue(observation.cells().stream().noneMatch(c -> c.position().equals(cell.up())));
                chunk.setBlockState(cell.up(), Blocks.WATER.getDefaultState());
                assertFalse(observation.matches(tx, pose), "native height dependency lies outside the capsule cell scan");
                var refreshed = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, pose));
                assertTrue(refreshed.inWater());
                assertTrue(tx.queries().work().staticAttempts() > observation.cells().size());
            }
        }
    }

    @Test void nativeTriggerFailureKeepsInstalledRevisionDebitAndOriginalCause() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("tail-failure"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var original = new IllegalStateException("actual native trigger failure");
            var block = new Block(Material.AIR) {
                @Override public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
                    return NULL_AABB;
                }
                @Override public void onEntityCollision(World world, BlockPos pos, IBlockState state, Entity entity) {
                    assertSame(actor, entity);
                    var active = FreeOwnerTransaction.nativeInstallation(actor);
                    assertNotNull(active, "the native tail must observe its actual installed journal");
                    assertSame(accepted, active.transaction().accepted());
                    assertEquals(accepted.revision() + 1, active.journal().revision());
                    assertEquals(active.journal().placement().transport(), new Vec3d(actor.posX, actor.posY, actor.posZ));
                    active.transaction().runNativeEnvironment(active.journal(),
                            () -> assertSame(active, FreeOwnerTransaction.nativeInstallation(actor)));
                    assertSame(active, FreeOwnerTransaction.nativeInstallation(actor));
                    throw original;
                }
            };
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(8, 68, 8), block.getDefaultState());
            var ledger = ledger(accepted);
            double initialCredit = ledger.snapshot().distanceCredit();
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger, () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(.125, 0, 0), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(accepted, tube),
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                        CapsuleMovementResolver.resolve(tx, tube, capture));
                var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(tx, movement));
                assertSame(original, assertThrows(IllegalStateException.class, ready::commit));
                assertNull(FreeOwnerTransaction.nativeInstallation(actor), "the failed tail restores the dynamic scope");
                var installed = ready.installation().orElseThrow();
                assertEquals(accepted.revision() + 1, installed.revision());
                assertEquals(movement.end().placement(), installed.placement());
                assertEquals(initialCredit - tube.requestedDisplacementUpper(), ledger.snapshot().distanceCredit());
                assertFalse(ledger.snapshot().pending());
                assertEquals(installed.placement().transport().x(), actor.posX);
                tx.requireCurrent();
                assertThrows(IllegalStateException.class, ready::commit);
                assertFalse(CardinalBlockMotion.active(actor));
            }
        }
    }

    @Test void setterFailureAfterSuperKeepsPartialInstallationCustodyAndOriginalCause() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("setter-after-super"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var original = new IllegalStateException("native bounding-box setter failed after mutation");
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(),
                    ledger(accepted), () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(.125, 0, 0), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(accepted, tube),
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                        CapsuleMovementResolver.resolve(tx, tube, capture));
                var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class,
                        CapsuleMoveFinalizer.prepare(tx, movement));
                BootWorldFixture.afterNextBoundingBox(actor, () -> { throw original; });

                assertSame(original, assertThrows(IllegalStateException.class, ready::commit));
                var custody = tx.installationCustody().orElseThrow();
                assertEquals(FreeOwnerTransaction.InstallationStage.PARTIAL, custody.stage());
                assertSame(original, custody.failure().orElseThrow());
                var observed = custody.observedGeometry().orElseThrow();
                assertEquals(movement.end().placement().bounds(), observed.bounds());
                assertEquals(accepted.placement().transport(), observed.position(),
                        "position assignment did not run after the bounding-box setter threw");
                assertEquals(accepted.revision() + 1, custody.expectedJournal().revision());
                assertNotEquals(accepted.placement().transport(), movement.end().placement().transport(),
                        "the expected target transport must differ from the old transport");
                assertNotEquals(accepted.placement().bounds(), observed.bounds(),
                        "the partial actor geometry must not be presented as the accepted owner placement");
                assertEquals(accepted.placement().transport().x(), actor.posX);
            }
        }
    }

    @Test void sameTickCollisionChangeOutsideEndpointTriggerCellsRejectsBeforeInstallation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("recapture"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5), CardinalFrame.Y_POS.attitude(), .25, .25);
            var ledger = new BoostMovementBudget(60, 0, vector(accepted.placement().transport()));
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger, () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(1, 0, 0), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(accepted, tube),
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                        CapsuleMovementResolver.resolve(tx, tube, capture));
                var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(tx, movement));
                assertTrue(movement.recaptureFailure(tx).isEmpty(), "the certified path must recapture before the world changes");
                long sameTick = fixture.world().getTotalWorldTime();
                fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                        .setBlockState(new BlockPos(8, 68, 8), Blocks.STONE.getDefaultState());
                assertEquals(sameTick, fixture.world().getTotalWorldTime());
                assertTrue(ready.environment().matches(tx, movement.end()), "endpoint trigger states alone miss a changed earlier path cell");
                var failure = assertThrows(IllegalStateException.class, ready::commit);
                assertTrue(failure.getMessage().startsWith("capsule collision recapture failed"));
                assertTrue(ready.installation().isEmpty());
                assertEquals(accepted.placement().transport().x(), actor.posX);
                tx.requireCurrent();
            }
            assertFalse(ledger.snapshot().pending());
        }
    }

    @Test void freeReferenceRetainsSavedManualWithoutInventingEffectiveCardinal() {
        var manual = new PlayerBodyReference.ManualIntent(4, CardinalFrame.Z_POS, new Vec3d(1, 0, 0));
        var free = new PlayerBodyReference.FreeReference(Optional.of(manual), Optional.empty());
        var travel = new PlayerPhysicsStep.TravelState();
        travel.ownerReference(free);
        assertEquals(Optional.of(manual), travel.ownerReference().manualIntent());
        assertTrue(travel.cardinalReference().isEmpty());
        assertThrows(IllegalStateException.class, travel::bodyReference);
    }

    @Test void constructionAndFailedPathKeepTheSameNumericWork() {
        var work = new StoredDyadic.Work();
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, 68, 8.5), new Rotation3d(0, 0, 1, 2),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(1, 2, .75)), work);
        long construction = work.units();
        assertTrue(construction > 0);
        var pose = CapsulePlacementMath.pose(placement, work);
        assertEquals(construction + pose.numericWorkDelta(), work.units());
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 2);
        long beforePath = work.units();
        assertThrows(NumericWorkExhaustedException.class,
                () -> CapsulePlacementMath.translationTube(pose, new Vec3d(.125, 0, 0), work));
        assertTrue(work.units() >= beforePath);
        assertTrue(work.units() <= StoredDyadic.MAX_WORK_UNITS);
        long beforeBadConstruction = work.units();
        assertThrows(RuntimeException.class, () -> new PlayerBodyPlacement(new Vec3d(1e9, 68, 8.5),
                placement.attitude(), placement.shape(), work));
        assertTrue(work.units() >= beforeBadConstruction);
    }

    @Test void actualWorldEmptyTranslationUsesOneLedgerReservationAndSharedNumericWork() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("empty"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var ledger = ledger(accepted);
            var travel = new PlayerPhysicsStep.TravelState();
            travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(), Optional.empty()));
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(.125, 0, 0), tx.numeric());
                var admission = tx.reserve(1, 0, tube.requestedDisplacementUpper());
                assertTrue(admission.accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(fixture.world(), actor,
                                CapsuleMovementResolver.window(accepted, tube),
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                assertTrue(capture.boxes().isEmpty());
                var rawResult = CapsuleMovementResolver.resolve(tx, tube, capture);
                var result = assertInstanceOf(CapsuleMovementResolver.Resolved.class, rawResult, rawResult::toString);
                assertEquals(1, result.fraction());
                assertEquals(accepted.placement().center().add(new Vec3d(.125, 0, 0)), result.end().placement().center());
                assertTrue(tx.numeric().units() > pose.numericWorkDelta());
                tx.commit(result.end().placement(), tube.requestedDisplacementUpper());
                assertFalse(ledger.snapshot().pending());
                var next = ledger.reserveCenterPath(2, 0, vector(result.end().placement().transport()),
                        vector(result.end().placement().center()), 0);
                assertTrue(next.accepted(), "committed ledger must retain the actual center and transport");
                ledger.abortCenterPath(next);
                tx.requireCurrent(); // The same transaction remains open for post-observation.
                assertThrows(IllegalStateException.class,
                        () -> tx.commit(result.end().placement(), tube.requestedDisplacementUpper()));
            }
        }
    }

    @Test void changedOwnerAndCorrectionRejectAndCloseDoesNotRefundDebit() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("fence"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var ledger = ledger(accepted);
            var travel = new PlayerPhysicsStep.TravelState();
            var current = new AtomicBoolean(true);
            double before = ledger.snapshot().distanceCredit();
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, current::get)) {
                assertTrue(tx.reserve(1, 0, .125).accepted());
                current.set(false);
                assertThrows(IllegalStateException.class, tx::requireCurrent);
            }
            assertEquals(before - .125, ledger.snapshot().distanceCredit());
            current.set(true);
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, current::get)) {
                travel.invalidate();
                assertThrows(IllegalStateException.class, tx::requireCurrent);
            }
        }
    }

    @Test void realWallBoundsTranslationAndRetainsBoundaryAndActualFinalQueries() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("wall"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var travel = new PlayerPhysicsStep.TravelState();
            var ledger = new BoostMovementBudget(60, 0, vector(accepted.placement().transport()));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            for (int y = 66; y <= 69; y++) chunk.setBlockState(new BlockPos(10, y, 8), Blocks.STONE.getDefaultState());
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(2, 0, 0), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(fixture.world(), actor,
                                CapsuleMovementResolver.window(accepted, tube),
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                assertFalse(capture.boxes().isEmpty());
                var rawResult = CapsuleMovementResolver.resolve(tx, tube, capture);
                var result = assertInstanceOf(CapsuleMovementResolver.Resolved.class, rawResult, rawResult::toString);
                assertTrue(result.fraction() > 0 && result.fraction() < 1);
                assertTrue(result.queries().stream().anyMatch(q -> q.boundary().filter(b -> b.firstObstructionBracket().isPresent()).isPresent()));
                assertEquals(capture.boxes().size(), result.queries().stream().filter(q -> q.actualFinal().isPresent()).count());
                assertTrue(tx.queries().work().castQueries() > 0 || tx.queries().work().refinementSteps() > 0);
            }
        }
    }

    @Test void positivePrefixAndWallTangentShareOneChargedPathAndOneInstallation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("positive-slide"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            for (int y = 66; y <= 69; y++) chunk.setBlockState(new BlockPos(10, y, 8), Blocks.STONE.getDefaultState());
            var ledger = new BoostMovementBudget(60, 0, vector(accepted.placement().transport()));
            double initialCredit = ledger.snapshot().distanceCredit();
            try (var tx = new FreeOwnerTransaction(actor, accepted, new PlayerPhysicsStep.TravelState(), ledger, () -> true)) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(2, 0, .125), tx.numeric());
                assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, CapsuleMovementResolver.window(accepted, tube),
                                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                var raw = CapsuleMovementResolver.resolveWithSlide(tx, tube, capture);
                var moved = assertInstanceOf(CapsuleMovementResolver.Resolved.class, raw, raw::toString);
                assertEquals(2, moved.segments().size());
                assertTrue(moved.segments().getFirst().fraction() > 0 && moved.segments().getFirst().fraction() < 1);
                assertEquals(1, moved.segments().getLast().fraction());
                assertEquals(Set.of(0), moved.blockedAxes());
                assertEquals(accepted.placement().center().z() + .125, moved.end().placement().center().z(), 1e-12);
                assertTrue(moved.pathUpper() <= tube.requestedDisplacementUpper());
                for (var segment : moved.segments()) {
                    var actual = moved.queries().stream().filter(q -> q.capture() == segment.capture()
                            && q.tube() == segment.tube() && q.actualFinal().isPresent()).toList();
                    assertEquals(segment.capture().boxes().size(), actual.size());
                    for (int ordinal = 0; ordinal < actual.size(); ordinal++) {
                        assertEquals(ordinal, actual.get(ordinal).ordinal());
                        assertEquals(segment.capture().boxes().get(ordinal), actual.get(ordinal).obstacle());
                    }
                }
                System.out.println("positive slide raw candidate counts=" + moved.segments().stream()
                        .map(s -> s.capture().boxes().size()).toList() + " actualFinal="
                        + moved.queries().stream().filter(q -> q.actualFinal().isPresent()).count()
                        + " chargedPathUpper=" + moved.pathUpper());
                assertTrue(moved.matches(tx));
                assertEquals(initialCredit - tube.requestedDisplacementUpper(), ledger.snapshot().distanceCredit());
                var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(tx, moved));
                var installed = ready.commit();
                assertEquals(accepted.revision() + 1, installed.revision());
                assertSame(moved.end().placement(), installed.placement());
                assertFalse(ledger.snapshot().pending());
                assertThrows(IllegalStateException.class, ready::commit);
            }
        }
    }

    @Test void actualFloorContactAllowsTangentAndOutwardButNeverInwardPlaneProof() throws Exception {
        for (var delta : new Vec3d[] { new Vec3d(.125, 0, 0), new Vec3d(0, .125, 0), new Vec3d(0, -.125, 0),
                new Vec3d(.125, -.125, 0) }) {
            try (var fixture = BootWorldFixture.open(temp.resolve("contact" + delta.y() + delta.x()), PLAYER, false)) {
                var actor = fixture.serverPlayer(PLAYER);
                var accepted = accepted(actor, new Vec3d(8.5, 66, 8.5), CardinalFrame.Y_POS.attitude());
                var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
                assertNotNull(chunk);
                chunk.setBlockState(new BlockPos(8, 64, 8), Blocks.STONE.getDefaultState());
                var travel = new PlayerPhysicsStep.TravelState();
                try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true)) {
                    var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                    var tube = CapsulePlacementMath.translationTube(pose, delta, tx.numeric());
                    assertTrue(tx.reserve(1, 0, tube.requestedDisplacementUpper()).accepted());
                    var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                            WorldCollisionCandidates.capture(fixture.world(), actor,
                                    CapsuleMovementResolver.window(accepted, tube),
                                    new CollisionQueryFootprint.Limits(16384, 16, 100, 100)));
                    assertFalse(capture.boxes().isEmpty());
                    var result = CapsuleMovementResolver.resolve(tx, tube, capture);
                    if (delta.y() < 0) {
                        var blocked = assertInstanceOf(CapsuleMovementResolver.Resolved.class, result, result::toString);
                        assertEquals(0, blocked.fraction());
                        assertSame(pose, blocked.end());
                        assertTrue(result.planes().isEmpty());
                        assertTrue(result.queries().stream().anyMatch(q -> q.boundary()
                                .filter(b -> b.kind() == BoundaryKind.BLOCKED_AT_START).isPresent()));
                        assertEquals(capture.boxes().size(), blocked.queries().stream().filter(q -> q.actualFinal().isPresent()).count());
                        if (delta.x() > 0) {
                            long callsBefore = tx.queries().work().staticAttempts();
                            var slid = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                                    CapsuleMovementResolver.resolveWithSlide(tx, tube, capture));
                            assertEquals(accepted.placement().center().add(new Vec3d(.125, 0, 0)), slid.end().placement().center());
                            assertTrue(tx.queries().work().staticAttempts() > callsBefore);
                            assertTrue(slid.queries().stream().anyMatch(q -> q.actualFinal().isPresent()));
                            assertFalse(slid.planes().isEmpty());
                        }
                    } else {
                        var resolved = assertInstanceOf(CapsuleMovementResolver.Resolved.class, result);
                        assertEquals(1, resolved.fraction());
                        assertEquals(capture.boxes().size(), resolved.planes().size());
                        assertTrue(resolved.planes().stream().allMatch(p -> p.certificate().matches(tube, p.obstacle())));
                        assertTrue(resolved.queries().stream().allMatch(q -> q.actualFinal().isPresent()));
                    }
                }
            }
        }
    }

    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor) {
        return accepted(actor, new Vec3d(8.5, 68, 8.5), new Rotation3d(0, 0, 1, 2));
    }
    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor, Vec3d center, Rotation3d q) {
        return accepted(actor, center, q, 1, 2);
    }
    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor, Vec3d center, Rotation3d q, double width, double height) {
        var placement = new PlayerBodyPlacement(center, q,
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(width, height, .75)));
        actor.width = (float) width; actor.height = (float) height;
        actor.eyeHeight = (float) placement.shape().dimensions().eyeHeight();
        var transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        var b = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(),
                b.max().x(), b.max().y(), b.max().z()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(), actor.dimension, 1),
                7, placement, new Vec3d(0, 0, 0));
    }
    private static BoostMovementBudget ledger(PlayerPhysicalSnapshot accepted) {
        return new BoostMovementBudget(10, 0, vector(accepted.placement().transport()));
    }
    private static Vec3 vector(Vec3d v) { return new Vec3(v.x(), v.y(), v.z()); }
}
