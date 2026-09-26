package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMoveFinalizer;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CollisionQueryFootprint;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.WorldCollisionCandidates;

class CardinalMoveFinalizerIntegrationTest {
    private static final CardinalMovementResolver.Limits LIMITS =
            new CardinalMovementResolver.Limits(512, 16384, 100000);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void physicalTailCallsActualFloorAndCountsLocalWalking(CardinalFrame frame, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10131))) {
            var actor = fixture.serverPlayer(new UUID(0, 10132));
            int[] calls = {0, 0};
            var support = new BlockPos(8, 64, 8);
            var floor = new Block(Material.ROCK) {
                @Override public void onLanded(World world, Entity entity) {
                    calls[0]++;
                    assertEquals(fixture.world(), world);
                    assertEquals(actor, entity);
                    // The test-only mapped-class bridge executes the original native method with its scoped field hook.
                    super.onLanded(world, entity);
                }
                @Override public void onEntityWalk(World world, BlockPos cell, Entity entity) {
                    calls[1]++;
                    assertEquals(support, cell, "callback must receive the physical floor cell, not world-underfoot air");
                    assertEquals(actor, entity);
                }
            };
            fixture.put(support, floor.getDefaultState());
            var shape = new PlayerBodyShape.Cardinal(frame,
                    new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight()));
            var placement = new PlayerBodyPlacement(new Vec3d(8.5, 64.5, 8.5)
                    .add(frame.up().multiply(.5 + actor.height / 2.0)), frame.attitude(), shape);
            var bounds = placement.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                    bounds.max().x(), bounds.max().y(), bounds.max().z()));
            actor.resetPositionToBB();
            actor.onGround = true;
            actor.stepHeight = 0;
            Vec3d delta = frame.toWorld(new Vec3d(.1, -.08, .1));
            actor.motionX = delta.x(); actor.motionY = delta.y(); actor.motionZ = delta.z();
            var request = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(), delta,
                    MoverType.PLAYER, true, false, 0);
            var result = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request, query -> fixture.world().getCollisionBoxes(actor, query), LIMITS));
            actor.onGround = false;
            assertInstanceOf(CardinalMoveFinalizer.Unavailable.class,
                    CardinalMoveFinalizer.preparePhysical(actor, request, result),
                    "physical preparation must reject support flags changed after the collision calculation");
            actor.onGround = true;
            assertInstanceOf(CardinalMoveFinalizer.Applied.class, assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.preparePhysical(actor, request, result)).commit());
            assertEquals(1, calls[0], "native landing callback runs once");
            assertEquals(1, calls[1], "actual support gets native walking callback");
            assertEquals(.0848528137, actor.distanceWalkedModified, 1e-7);
            assertEquals(.0848528137, actor.distanceWalkedOnStepModified, 1e-7);
            var localMotion = frame.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
            assertEquals(.1, localMotion.x(), 1e-12);
            assertEquals(0, localMotion.y(), 1e-12);
            assertEquals(.1, localMotion.z(), 1e-12);
        }
    }

    @Test
    void consecutiveSidewaysMovesAndBootCaptureKeepActualRoundedCollider(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 601))) {
            var actor = fixture.serverPlayer(new UUID(0, 602));
            fixture.installColumn(-1, 0);
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                    new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight()));
            double halfWidth = (double) actor.width / 2;
            actor.setEntityBoundingBox(new AxisAlignedBB(0, 65 - halfWidth, 8.5 - halfWidth,
                    actor.height, 65 + halfWidth, 8.5 + halfWidth));
            actor.resetPositionToBB();
            var initial = PlayerBodyPlacement.fromTransport(new Vec3d(actor.posX, actor.posY, actor.posZ),
                    CardinalFrame.X_POS.attitude(), shape);
            var state = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                    new UUID(0, 603), 0, 1), 0, initial, new Vec3d(2, 0, 0));
            var scanLimits = new CollisionQueryFootprint.Limits(4096, 64, 512, 4096);
            for (double expectedFeet : new double[] {0.1, 0.2}) {
                var request = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                        new Vec3d(0.1, 0, 0), MoverType.PLAYER, false, false, 0);
                var observed = WorldCollisionCandidates.resolveCardinal(fixture.world(), actor, state, request,
                        scanLimits, LIMITS);
                assertTrue(observed.worldFailure().isEmpty(), () -> observed.worldFailure().toString());
                var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class, observed.calculation().orElseThrow());
                var applied = assertInstanceOf(CardinalMoveFinalizer.Applied.class,
                        assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                                CardinalMoveFinalizer.prepare(actor, request, resolved)).commit());
                AxisAlignedBB actual = applied.actualBounds();
                Aabb actualBox = new Aabb(new Vec3d(actual.minX, actual.minY, actual.minZ),
                        new Vec3d(actual.maxX, actual.maxY, actual.maxZ));
                var nextPlacement = PlayerBodyPlacement.fromCardinalBounds(actualBox, applied.actualTransport(),
                        CardinalFrame.X_POS.attitude(), shape);
                state = state.successor(nextPlacement, state.worldVelocity());
                assertEquals(actualBox, state.placement().bounds(), "do not reconstruct a solved box from its center");
                assertEquals(expectedFeet, state.placement().physicalFeet().orElseThrow().x());
                assertEquals(applied.actualTransport(), state.transportSample().position());
                var source = BootSurfaceGeometry.fromAccepted(state);
                var footprint = BootQueryFootprint.plan(source, 1.5,
                        new BootQueryFootprint.Limits(1024, 4096, 64, 512));
                assertInstanceOf(BootWorldSurfaces.Snapshot.class,
                        BootWorldSurfaces.capture(fixture.world(), actor, footprint, List.of()));
            }
            assertEquals(2, state.revision());
            assertEquals(new Vec3d(2, 0, 0), state.worldVelocity(), "no native MP motion promotion");
            AxisAlignedBB before = actor.getEntityBoundingBox();
            actor.setEntityBoundingBox(new AxisAlignedBB(Math.nextUp(before.minX), before.minY, before.minZ,
                    before.maxX, before.maxY, before.maxZ));
            var staleRequest = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                    new Vec3d(0.1, 0, 0), MoverType.PLAYER, false, false, 0);
            var stale = WorldCollisionCandidates.resolveCardinal(fixture.world(), actor, state, staleRequest,
                    scanLimits, LIMITS);
            assertEquals(WorldCollisionCandidates.Reason.CONTEXT_CHANGED, stale.worldFailure().orElseThrow().reason());
            assertTrue(stale.calculation().isEmpty(), "one-ulp real mutation must still fail before movement");
            var footprint = BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(state), 1.5,
                    new BootQueryFootprint.Limits(1024, 4096, 64, 512));
            var staleBoot = assertInstanceOf(BootWorldSurfaces.Unavailable.class,
                    BootWorldSurfaces.capture(fixture.world(), actor, footprint, List.of()));
            assertEquals(BootWorldSurfaces.Reason.ACTOR_SCOPE, staleBoot.reason());
        }
    }

    @Test
    void sidewaysGeometryCommitKeepsPhysicalGroundSeparateAndDoesNotReplay(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 311))) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 312));
            actor.setEntityBoundingBox(new AxisAlignedBB(8, 64.7, 8.2, 9.8, 65.3, 8.8));
            actor.resetPositionToBB();
            actor.onGround = false;
            actor.motionX = -0.1;
            actor.motionY = -0.1;
            actor.fallDistance = 7;
            fixture.put(new BlockPos(7, 65, 8), Blocks.STONE.getDefaultState());
            fixture.put(new BlockPos(9, 65, 8), Blocks.WEB.getDefaultState());
            var request = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                    new Vec3d(-0.1, -0.1, 0), MoverType.PLAYER, false, false, 0);
            var result = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(actor, bounds), LIMITS));
            actor.onAddedToWorld();
            int before = fixture.positionUpdates(actor);

            var ready = assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(actor, request, result));
            var applied = assertInstanceOf(CardinalMoveFinalizer.Applied.class, ready.commit());

            assertEquals(result.endBounds(), actor.getEntityBoundingBox());
            assertEquals(result.endBounds().minY, actor.posY);
            assertEquals(before + 1, fixture.positionUpdates(actor));
            assertTrue(applied.outcome().collision().flags().onGround());
            assertFalse(actor.onGround);
            assertTrue(actor.collidedHorizontally);
            assertFalse(actor.collidedVertically);
            assertEquals(-0.1, actor.motionX);
            assertEquals(-0.1, actor.motionY);
            assertEquals(0, actor.fallDistance, "native setInWeb resets fall distance; do not undo its real effect");
            assertTrue(((Entity) actor).isInWeb, "real inside-block callback runs at the installed end box");
            assertEquals(CardinalMoveFinalizer.Tail.CARDINAL_GEOMETRY_ONLY, applied.outcome().tail());
            assertThrows(IllegalStateException.class, ready::commit);
            assertEquals(before + 1, fixture.positionUpdates(actor));
        }
    }

    @ParameterizedTest
    @EnumSource(value = CardinalFrame.class, names = "Y_POS", mode = EnumSource.Mode.EXCLUDE)
    void allNonYFramesInstallOneBoxWithoutWorldVerticalStandingSemantics(CardinalFrame frame, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 351))) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 352));
            Aabb localBody = new Aabb(new Vec3d(0.2, 0, 0.2), new Vec3d(0.8, 1.8, 0.8));
            Aabb body = frame.toWorld(localBody);
            actor.setEntityBoundingBox(new AxisAlignedBB(body.min().x() + 8, body.min().y() + 65, body.min().z() + 8,
                    body.max().x() + 8, body.max().y() + 65, body.max().z() + 8));
            actor.resetPositionToBB();
            actor.onGround = false;
            actor.fallDistance = 7;
            Aabb floor = frame.toWorld(new Aabb(new Vec3d(0, -1, 0), new Vec3d(1, 0, 1)));
            fixture.put(new BlockPos(floor.min().x() + 8, floor.min().y() + 65, floor.min().z() + 8),
                    Blocks.STONE.getDefaultState());
            var request = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                    frame.toWorld(new Vec3d(0.1, -0.1, 0.1)), MoverType.PLAYER, false, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(actor, bounds), LIMITS));
            actor.onAddedToWorld();
            int before = fixture.positionUpdates(actor);
            var result = assertInstanceOf(CardinalMoveFinalizer.Applied.class,
                    assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                            CardinalMoveFinalizer.prepare(actor, request, resolved)).commit());
            assertEquals(resolved.endBounds(), result.actualBounds());
            assertTrue(resolved.flags().onGround());
            assertFalse(actor.onGround);
            assertEquals(frame == CardinalFrame.Y_NEG, actor.collidedVertically);
            assertEquals(frame != CardinalFrame.Y_NEG, actor.collidedHorizontally);
            assertEquals(7, actor.fallDistance);
            assertEquals(before + 1, fixture.positionUpdates(actor));
        }
    }

    @Test
    void nativeRoundedStartAndQueuedUnloadAreNotRepairedByPreparation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 361))) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 362));
            fixture.installColumn(-1, 0);
            actor.setPosition(0.1, 65, 8.5);
            actor.stepHeight = 0;
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, actor.getEntityBoundingBox(),
                    new Vec3d(0, 0, 0), MoverType.SELF, actor.onGround, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(actor, bounds), LIMITS));
            var chunk = fixture.world().getChunkProvider().loadedChunks.get(ChunkPos.asLong(0, 0));
            chunk.unloadQueued = true;
            assertInstanceOf(CardinalMoveFinalizer.Ready.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
            assertTrue(chunk.unloadQueued);
            assertEquals(0.1, actor.posX);
            assertEquals(request.worldBounds(), actor.getEntityBoundingBox());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"step", "slime", "bed", "web", "fire"})
    void nativeSpecialSurfaceTailParity(String surface, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 321))) {
            EntityPlayer nativeActor = fixture.actor();
            EntityPlayer finalized = fixture.player(new UUID(0, 322));
            fixture.serverPlayer(new UUID(0, 323)); // Install the real column, without starting a server.
            for (EntityPlayer actor : new EntityPlayer[] {nativeActor, finalized}) {
                actor.setPosition(8.5, 65, 8.5);
                actor.onGround = true;
                actor.stepHeight = surface.equals("step") ? 0.6F : 0;
                actor.motionX = 0.1;
                actor.motionY = -0.8;
                actor.motionZ = 0.1;
                actor.fallDistance = 2;
            }
            for (int x = 7; x <= 10; x++) for (int z = 7; z <= 10; z++) {
                fixture.put(new BlockPos(x, 64, z), Blocks.STONE.getDefaultState());
            }
            switch (surface) {
                case "step" -> fixture.put(new BlockPos(9, 65, 8), Blocks.STONE_SLAB.getDefaultState());
                case "slime" -> fixture.put(new BlockPos(8, 64, 8), Blocks.SLIME_BLOCK.getDefaultState());
                case "bed" -> fixture.put(new BlockPos(8, 64, 8), Blocks.BED.getDefaultState());
                case "web" -> fixture.put(new BlockPos(8, 65, 8), Blocks.WEB.getDefaultState());
                case "fire" -> fixture.put(new BlockPos(8, 65, 8), Blocks.FIRE.getDefaultState());
                default -> throw new AssertionError(surface);
            }
            Vec3d delta = surface.equals("step") ? new Vec3d(1, -0.08, 0.1) : new Vec3d(0.1, -0.8, 0.1);
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, finalized.getEntityBoundingBox(),
                    delta, MoverType.SELF, true, false, finalized.stepHeight);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(finalized, bounds), LIMITS));
            nativeActor.move(MoverType.SELF, delta.x(), delta.y(), delta.z());
            assertInstanceOf(CardinalMoveFinalizer.Applied.class, assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(finalized, request, resolved)).commit());
            assertEquals(nativeActor.getEntityBoundingBox(), finalized.getEntityBoundingBox());
            assertEquals(nativeActor.getPositionVector(), finalized.getPositionVector());
            assertEquals(nativeActor.onGround, finalized.onGround);
            assertEquals(nativeActor.collidedVertically, finalized.collidedVertically);
            assertEquals(nativeActor.motionX, finalized.motionX);
            assertEquals(nativeActor.motionY, finalized.motionY);
            assertEquals(nativeActor.motionZ, finalized.motionZ);
            assertEquals(nativeActor.fallDistance, finalized.fallDistance);
            assertEquals(nativeActor.distanceWalkedModified, finalized.distanceWalkedModified);
            assertEquals(nativeActor.distanceWalkedOnStepModified, finalized.distanceWalkedOnStepModified);
            assertEquals(((Entity) nativeActor).isInWeb, ((Entity) finalized).isInWeb);
            assertEquals(((Entity) nativeActor).fire, ((Entity) finalized).fire);
            assertEquals(nativeActor.getHealth(), finalized.getHealth());
            if (surface.equals("step")) {
                assertTrue(finalized.getEntityBoundingBox().minY > request.worldBounds().minY);
                assertTrue(resolved.nativeResolvedLocalDelta().y() < 0);
            }
        }
    }

    @Test
    void guardsRejectWebChangedInputAndMissingWorldBeforeMutation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 331))) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 332));
            actor.setPosition(8.5, 65, 8.5);
            actor.stepHeight = 0;
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, actor.getEntityBoundingBox(),
                    new Vec3d(0.1, 0, 0), MoverType.SELF, actor.onGround, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(actor, bounds), LIMITS));
            actor.setInWeb();
            assertInstanceOf(CardinalMoveFinalizer.Unavailable.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
            ((Entity) actor).isInWeb = false;
            actor.stepHeight = 0.6F;
            assertInstanceOf(CardinalMoveFinalizer.Unavailable.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
            actor.stepHeight = 0;
            var ready = assertInstanceOf(CardinalMoveFinalizer.Ready.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
            actor.setSneaking(true);
            assertInstanceOf(CardinalMoveFinalizer.Unavailable.class, ready.commit());
            assertEquals(request.worldBounds(), actor.getEntityBoundingBox());
            actor.setSneaking(false);
            var beforeUnload = assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(actor, request, resolved));
            fixture.removeColumn(0, 0);
            assertEquals(CardinalMoveFinalizer.Reason.WORLD_UNAVAILABLE,
                    assertInstanceOf(CardinalMoveFinalizer.Unavailable.class, beforeUnload.commit()).reason());
            assertEquals(request.worldBounds(), actor.getEntityBoundingBox());
        }
    }

    @Test
    void signedZeroIsEquivalentButOneUlpAndForeignRequestAreNot(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 341))) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 342));
            actor.stepHeight = 0;
            actor.setEntityBoundingBox(new AxisAlignedBB(0.0, 65, 8, 1, 67, 9));
            actor.resetPositionToBB();
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS,
                    new AxisAlignedBB(-0.0, 65, 8, 1, 67, 9), new Vec3d(0, 0, 0), MoverType.SELF, actor.onGround, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(actor, bounds), LIMITS));
            var ready = assertInstanceOf(CardinalMoveFinalizer.Ready.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
            assertInstanceOf(CardinalMoveFinalizer.Applied.class, ready.commit());
            var foreign = new CardinalMovementResolver.Request(request.frame(), request.worldBounds(), request.worldDelta(),
                    request.moverType(), request.onGround(), request.sneaking(), request.stepHeight());
            assertThrows(IllegalArgumentException.class, () -> CardinalMoveFinalizer.prepare(actor, foreign, resolved));
            actor.setEntityBoundingBox(new AxisAlignedBB(0, 65, 8, Math.nextUp(1.0), 67, 9));
            assertInstanceOf(CardinalMoveFinalizer.Unavailable.class, CardinalMoveFinalizer.prepare(actor, request, resolved));
        }
    }

    @Test
    void nativeFloorWallTailMatchesPositionFlagsVelocityAndWalking(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 301))) {
            EntityPlayer nativeActor = fixture.actor();
            EntityPlayer finalized = fixture.serverPlayer(new UUID(0, 302));
            for (EntityPlayer actor : new EntityPlayer[] {nativeActor, finalized}) {
                actor.setPosition(8.5, 65, 8.5);
                actor.onGround = true;
                actor.stepHeight = 0;
                actor.motionX = 1;
                actor.motionY = -0.08;
                actor.motionZ = 0.1;
            }
            for (int x = 7; x <= 10; x++) {
                for (int z = 7; z <= 10; z++) fixture.put(new BlockPos(x, 64, z), Blocks.STONE.getDefaultState());
            }
            fixture.put(new BlockPos(9, 65, 8), Blocks.STONE.getDefaultState());
            Vec3d delta = new Vec3d(1, -0.08, 0.1);
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS,
                    finalized.getEntityBoundingBox(), delta, MoverType.SELF, true, false, 0);
            var result = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request,
                            bounds -> fixture.world().getCollisionBoxes(finalized, bounds), LIMITS));
            nativeActor.move(MoverType.SELF, delta.x(), delta.y(), delta.z());

            assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(finalized, request, result)).commit();

            assertEquals(nativeActor.getEntityBoundingBox(), finalized.getEntityBoundingBox());
            assertEquals(nativeActor.getPositionVector(), finalized.getPositionVector());
            assertEquals(nativeActor.onGround, finalized.onGround);
            assertEquals(nativeActor.collided, finalized.collided);
            assertEquals(nativeActor.collidedHorizontally, finalized.collidedHorizontally);
            assertEquals(nativeActor.collidedVertically, finalized.collidedVertically);
            assertEquals(nativeActor.motionX, finalized.motionX);
            assertEquals(nativeActor.motionY, finalized.motionY);
            assertEquals(nativeActor.motionZ, finalized.motionZ);
            assertEquals(nativeActor.distanceWalkedModified, finalized.distanceWalkedModified);
            assertEquals(nativeActor.distanceWalkedOnStepModified, finalized.distanceWalkedOnStepModified);
        }
    }
}
