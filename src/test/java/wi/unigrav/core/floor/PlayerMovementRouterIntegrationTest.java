package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ReportedException;
import net.minecraft.crash.CrashReport;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.ThreadQuickExitException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerMovementRouter;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.CardinalMoveFinalizer;

class PlayerMovementRouterIntegrationTest {
    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void exceptionalPacketInvalidatesProvisionalReceiptAndAllowsNextPacket(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 491))) {
            var actor = fixture.serverPlayer(new UUID(0, 492));
            actor.setPosition(8.5, 65, 8.5);
            var router = new PlayerMovementRouter();
            var failure = new ReportedException(CrashReport.makeCrashReport(
                    new IllegalStateException("callback failed"), "packet tail regression"));
            assertSame(failure, assertThrows(ReportedException.class, () -> router.runPacket(actor.connection, () -> {
                router.begin(actor.connection, new CPacketPlayer.Position(8.75, 65, 8.5, false),
                        new Vec3(8.5, 65, 8.5), false);
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                router.assignEndpoint(actor, 8.75, 65, 8.5, 0, 0, false);
                router.beforeTracking(false);
                throw failure;
            }, () -> false)));
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
            assertEquals(8.75, actor.posX, "cleanup does not undo real movement or callbacks");
            router.runPacket(actor.connection, () -> {
                router.begin(actor.connection, new CPacketPlayer.Position(9, 65, 8.5, false),
                        new Vec3(8.75, 65, 8.5), false);
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                router.assignEndpoint(actor, 9, 65, 8.5, 0, 0, false);
                router.beforeTracking(false);
            }, () -> false);
            assertEquals(9, NativePlayerMovement.INSTANCE.current(actor.connection)
                    .orElseThrow().placement().transport().x());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void nativeOffThreadHandoffCannotCleanAnActiveServerPacket(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 501))) {
            var actor = fixture.serverPlayer(new UUID(0, 502));
            actor.setPosition(8.5, 65, 8.5);
            var router = new PlayerMovementRouter();
            router.runPacket(actor.connection, () -> {
                router.begin(actor.connection, new CPacketPlayer.Position(8.75, 65, 8.5, false),
                        new Vec3(8.5, 65, 8.5), false);
                var observed = new AtomicReference<Throwable>();
                Thread handoff = Thread.ofPlatform().name("ug-test-packet-handoff").daemon(true).unstarted(() -> {
                    try {
                        // Executes real PacketThreadUtil scheduling, but never drains the disposable task queue.
                        router.runPacket(actor.connection, () -> actor.connection.processPlayer(new CPacketPlayer(false)),
                                () -> { throw new AssertionError("off-thread cleanup must not read server state"); });
                    } catch (Throwable failure) {
                        observed.set(failure);
                    }
                });
                handoff.start();
                try {
                    assertTrue(handoff.join(Duration.ofSeconds(10)), "native handoff must be nonblocking");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertSame(ThreadQuickExitException.INSTANCE, observed.get());
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                router.assignEndpoint(actor, 8.75, 65, 8.5, 0, 0, false);
                router.beforeTracking(false);
            }, () -> false);
            assertEquals(8.75, NativePlayerMovement.INSTANCE.current(actor.connection)
                    .orElseThrow().placement().transport().x());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void cleanupFailureIsSuppressedAndDoesNotRetainRouter(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 511))) {
            var actor = fixture.serverPlayer(new UUID(0, 512));
            var router = new PlayerMovementRouter();
            var original = new AssertionError("original callback failure");
            try {
                assertSame(original, assertThrows(AssertionError.class, () -> router.runPacket(actor.connection, () -> {
                    router.begin(actor.connection, new CPacketPlayer(false), new Vec3(8, 65, 8), false);
                    // An invalid external lifecycle forces real native evidence cleanup to fail as well.
                    actor.world = null;
                    throw original;
                }, () -> false)));
                assertEquals(1, original.getSuppressed().length);
                assertInstanceOf(NullPointerException.class, original.getSuppressed()[0]);
            } finally {
                actor.world = fixture.world();
            }
            router.runPacket(actor.connection, () -> router.begin(actor.connection,
                    new CPacketPlayer(false), new Vec3(8, 65, 8), false), () -> false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void cardinalCancellationReturnsThroughTheSingleOuterFinisher(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 521))) {
            var actor = fixture.serverPlayer(new UUID(0, 522));
            var router = new PlayerMovementRouter();
            router.runPacket(actor.connection, () -> {
                router.beginCardinal(actor.connection, new CPacketPlayer.Position(9, 65, 8, false), false,
                        sidewaysReady(fixture, actor));
                router.invalidate(actor.connection);
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                assertTrue(router.mustCancel());
                // A Mixin cancellation exits the original body here without calling finish itself.
            }, () -> true);
            router.runPacket(actor.connection, () -> router.begin(actor.connection,
                    new CPacketPlayer(false), new Vec3(actor.posX, actor.posY, actor.posZ), false), () -> false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void cardinalErrorAfterCommitDoesNotUndoTheBoxOrLeakTransaction(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 531))) {
            var actor = fixture.serverPlayer(new UUID(0, 532));
            var ready = sidewaysReady(fixture, actor);
            var router = new PlayerMovementRouter();
            var original = new AssertionError("post-commit callback");
            assertSame(original, assertThrows(AssertionError.class, () -> router.runPacket(actor.connection, () -> {
                router.beginCardinal(actor.connection, new CPacketPlayer.Position(9, 65, 8, false), false, ready);
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                throw original;
            }, () -> false)));
            assertEquals(ready.outcome().collision().endBounds(), actor.getEntityBoundingBox());
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
            router.runPacket(actor.connection, () -> {}, () -> false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void finalizerStatusFailureAlsoInvalidatesTentativeSuccess(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 541))) {
            var actor = fixture.serverPlayer(new UUID(0, 542));
            actor.setPosition(8.5, 65, 8.5);
            var router = new PlayerMovementRouter();
            var original = new IllegalStateException("teleport state unavailable");
            assertSame(original, assertThrows(IllegalStateException.class, () -> router.runPacket(actor.connection, () -> {
                router.begin(actor.connection, new CPacketPlayer.Position(8.75, 65, 8.5, false),
                        new Vec3(8.5, 65, 8.5), false);
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                router.assignEndpoint(actor, 8.75, 65, 8.5, 0, 0, false);
                router.beforeTracking(false);
            }, () -> { throw original; })));
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
            router.runPacket(actor.connection, () -> {}, () -> false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void caughtNestedPacketAttemptCannotClearTheOuterPacket(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 551))) {
            var actor = fixture.serverPlayer(new UUID(0, 552));
            actor.setPosition(8.5, 65, 8.5);
            var router = new PlayerMovementRouter();
            router.runPacket(actor.connection, () -> {
                router.begin(actor.connection, new CPacketPlayer.Position(8.75, 65, 8.5, false),
                        new Vec3(8.5, 65, 8.5), false);
                assertThrows(IllegalStateException.class,
                        () -> router.runPacket(actor.connection, () -> {}, () -> false));
                router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
                router.assignEndpoint(actor, 8.75, 65, 8.5, 0, 0, false);
                router.beforeTracking(false);
            }, () -> false);
            assertEquals(8.75, NativePlayerMovement.INSTANCE.current(actor.connection)
                    .orElseThrow().placement().transport().x());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void cardinalEndpointKeepsResolvedBoxAndSkipsWorldYJumpAndFall(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 411))) {
            var actor = fixture.serverPlayer(new UUID(0, 412));
            actor.setEntityBoundingBox(new AxisAlignedBB(8, 64.7, 8.2, 9.8, 65.3, 8.8));
            actor.resetPositionToBB();
            actor.fallDistance = 7;
            actor.motionY = 0.125;
            fixture.put(new BlockPos(7, 65, 8), Blocks.STONE.getDefaultState());
            var request = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                    new Vec3d(-0.1, -0.1, 0), MoverType.PLAYER, false, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request, bounds -> fixture.world().getCollisionBoxes(actor, bounds),
                            new CardinalMovementResolver.Limits(512, 16384, 100000)));
            var ready = assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(actor, request, resolved));
            var router = new PlayerMovementRouter();
            var packet = new CPacketPlayer.PositionRotation(8.8, 64.6, 8.5, 370, 120, false);
            actor.onAddedToWorld();
            int before = fixture.positionUpdates(actor);
            assertSame(packet, router.beginCardinal(actor.connection, packet, false, ready));
            router.jump(actor);
            router.move(actor, MoverType.PLAYER, -0.1, -0.1, 0);
            assertFalse(router.mustCancel());
            router.assignEndpoint(actor, 8.8, 64.6, 8.5, 370, 120, false);
            router.beforeTracking(false);
            router.handleFalling(actor, -0.1, true);
            router.finish(actor.connection, false);
            assertEquals(resolved.endBounds(), actor.getEntityBoundingBox());
            assertEquals(before + 1, fixture.positionUpdates(actor));
            assertEquals(0.125, actor.motionY);
            assertEquals(7, actor.fallDistance);
            assertEquals(10, actor.rotationYaw);
            assertEquals(90, actor.rotationPitch);
            assertEquals(370, actor.prevRotationYaw);
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void nativePacketStillMovesAssignsAndPublishesNativeObservation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 401))) {
            var actor = fixture.serverPlayer(new UUID(0, 402));
            actor.setPosition(8.5, 65, 8.5);
            var router = new PlayerMovementRouter();
            var packet = new CPacketPlayer.PositionRotation(8.75, 65, 8.5, 370, 120, false);
            assertSame(packet, router.begin(actor.connection, packet, new Vec3(8.5, 65, 8.5), false));
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            assertFalse(router.mustCancel());
            router.assignEndpoint(actor, 8.75, 65, 8.5, 370, 120, false);
            router.beforeTracking(false);
            router.handleFalling(actor, 0, false);
            router.finish(actor.connection, false);
            assertEquals(8.75, actor.posX);
            assertEquals(10, actor.rotationYaw);
            assertEquals(90, actor.rotationPitch);
            assertEquals(370, actor.prevRotationYaw);
            assertEquals(8.75, NativePlayerMovement.INSTANCE.current(actor.connection)
                    .orElseThrow().placement().transport().x());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tick", "motion", "delta", "mover", "teleport"})
    void invalidPreparedMoveDoesNotFallbackOrRunNativeTail(String change, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 421))) {
            var actor = fixture.serverPlayer(new UUID(0, 422));
            var ready = sidewaysReady(fixture, actor);
            var box = actor.getEntityBoundingBox();
            var router = new PlayerMovementRouter();
            router.beginCardinal(actor.connection, new CPacketPlayer.Position(9, 65, 8, false), false, ready);
            PlayerMovementRouter.Failure expected;
            switch (change) {
                case "tick" -> {
                    fixture.world().setTotalWorldTime(1);
                    expected = PlayerMovementRouter.Failure.STALE_PREPARATION;
                }
                case "motion" -> {
                    actor.motionZ = 0.2;
                    expected = PlayerMovementRouter.Failure.STALE_PREPARATION;
                }
                case "teleport" -> {
                    router.invalidate(actor.connection);
                    expected = PlayerMovementRouter.Failure.TELEPORT;
                }
                default -> expected = PlayerMovementRouter.Failure.MOVE_MISMATCH;
            }
            router.move(actor, change.equals("mover") ? MoverType.SELF : MoverType.PLAYER,
                    change.equals("delta") ? 0.5 : 0.25, 0, 0);
            assertTrue(router.mustCancel());
            assertEquals(expected, router.failure());
            router.jump(actor);
            router.assignEndpoint(actor, 100, 100, 100, 50, 50, false);
            router.beforeTracking(false);
            router.handleFalling(actor, -100, true);
            assertSame(box, actor.getEntityBoundingBox());
            assertEquals(0, actor.rotationYaw);
            assertEquals(7, actor.fallDistance);
            router.finish(actor.connection, false);
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void teleportAfterCommitLatchesAbortWithoutRebuildingOrFallingBack(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 431))) {
            var actor = fixture.serverPlayer(new UUID(0, 432));
            var ready = sidewaysReady(fixture, actor);
            var router = new PlayerMovementRouter();
            router.beginCardinal(actor.connection, new CPacketPlayer.Position(9, 65, 8, false), false, ready);
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            var committed = actor.getEntityBoundingBox();
            router.invalidate(actor.connection);
            router.assignEndpoint(actor, 100, 100, 100, 50, 50, false);
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            assertSame(committed, actor.getEntityBoundingBox());
            assertTrue(router.mustCancel());
            assertEquals(PlayerMovementRouter.Failure.TELEPORT, router.failure());
            assertEquals(0, actor.rotationYaw);
            router.finish(actor.connection, true);
            // A completed packet cannot leak its cardinal branch into a later native packet.
            actor.setPosition(8.5, 65, 8.5);
            router.begin(actor.connection, new CPacketPlayer.Position(8.75, 65, 8.5, false),
                    new Vec3(8.5, 65, 8.5), false);
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            router.assignEndpoint(actor, 8.75, 65, 8.5, 0, 0, false);
            router.finish(actor.connection, false);
            assertEquals(8.75, actor.posX);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"tick", "box", "teleport"})
    void changedCardinalEndpointCannotBeTracked(String change, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 441))) {
            var actor = fixture.serverPlayer(new UUID(0, 442));
            var router = new PlayerMovementRouter();
            router.beginCardinal(actor.connection, new CPacketPlayer.Position(9, 65, 8, false), false,
                    sidewaysReady(fixture, actor));
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            router.assignEndpoint(actor, 9, 65, 8, 0, 0, false);
            if (change.equals("tick")) fixture.world().setTotalWorldTime(1);
            if (change.equals("box")) actor.setEntityBoundingBox(actor.getEntityBoundingBox().offset(0.1, 0, 0));
            router.beforeTracking(change.equals("teleport"));
            assertTrue(router.mustCancel());
            assertTrue(NativePlayerMovement.INSTANCE.current(actor.connection).isEmpty());
            router.finish(actor.connection, false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void foreignPreparationAndReplayAreProgrammerErrorsBeforeAnotherMove(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 451))) {
            var actor = fixture.serverPlayer(new UUID(0, 452));
            var foreign = fixture.serverPlayer(new UUID(0, 453));
            var ready = sidewaysReady(fixture, actor);
            var router = new PlayerMovementRouter();
            var packet = new CPacketPlayer.Position(9, 65, 8, false);
            assertThrows(IllegalArgumentException.class,
                    () -> router.beginCardinal(foreign.connection, packet, false, ready));
            router.beginCardinal(actor.connection, packet, false, ready);
            assertThrows(IllegalStateException.class,
                    () -> router.beginCardinal(actor.connection, packet, false, ready));
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            var box = actor.getEntityBoundingBox();
            assertThrows(IllegalStateException.class, () -> router.move(actor, MoverType.PLAYER, 0.25, 0, 0));
            assertSame(box, actor.getEntityBoundingBox());
            router.finish(actor.connection, false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void nativeJumpAndEarlyReturnKeepTheirExistingSemantics(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 461))) {
            var actor = fixture.serverPlayer(new UUID(0, 462));
            var router = new PlayerMovementRouter();
            // RETURN can run before the packet-entry anchor (riding/invalid/early packets).
            router.finish(actor.connection, false);
            router.begin(actor.connection, new CPacketPlayer(false), new Vec3(8, 65, 8), false);
            router.jump(actor);
            assertEquals((double) 0.42F, actor.motionY);
            router.finish(actor.connection, false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    private static CardinalMoveFinalizer.Ready sidewaysReady(BootWorldFixture fixture, EntityPlayerMP actor) {
        actor.setEntityBoundingBox(new AxisAlignedBB(8, 64.7, 8.2, 9.8, 65.3, 8.8));
        actor.resetPositionToBB();
        actor.fallDistance = 7;
        var request = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                new Vec3d(0.25, 0, 0), MoverType.PLAYER, false, false, 0);
        var result = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                CardinalMovementResolver.resolve(request, bounds -> fixture.world().getCollisionBoxes(actor, bounds),
                        new CardinalMovementResolver.Limits(512, 16384, 100000)));
        return assertInstanceOf(CardinalMoveFinalizer.Ready.class, CardinalMoveFinalizer.prepare(actor, request, result));
    }

    @Test
    void signedZeroEquivalentBoxRemainsTheSameEndpoint(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 471))) {
            var actor = fixture.serverPlayer(new UUID(0, 472));
            fixture.installColumn(-1, 0);
            actor.setEntityBoundingBox(new AxisAlignedBB(0.0, 64.7, 8.2, 1.8, 65.3, 8.8));
            actor.resetPositionToBB();
            var request = new CardinalMovementResolver.Request(CardinalFrame.X_POS, actor.getEntityBoundingBox(),
                    new Vec3d(0, 0, 0), MoverType.PLAYER, false, false, 0);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class,
                    CardinalMovementResolver.resolve(request, bounds -> fixture.world().getCollisionBoxes(actor, bounds),
                            new CardinalMovementResolver.Limits(512, 16384, 100000)));
            var ready = assertInstanceOf(CardinalMoveFinalizer.Ready.class,
                    CardinalMoveFinalizer.prepare(actor, request, resolved));
            var router = new PlayerMovementRouter();
            router.beginCardinal(actor.connection, new CPacketPlayer.Position(0.9, 64.7, 8.5, false), false, ready);
            router.move(actor, MoverType.PLAYER, 0, 0, 0);
            actor.setEntityBoundingBox(new AxisAlignedBB(-0.0, 64.7, 8.2, 1.8, 65.3, 8.8));
            router.assignEndpoint(actor, 0.9, 64.7, 8.5, 0, 0, false);
            assertFalse(router.mustCancel());
            router.finish(actor.connection, false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }

    @Test
    void samePlayerCannotTransferPreparedMoveAcrossConnections(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 481))) {
            var actor = fixture.serverPlayer(new UUID(0, 482));
            var oldConnection = actor.connection;
            var ready = sidewaysReady(fixture, actor);
            var router = new PlayerMovementRouter();
            var packet = new CPacketPlayer.Position(9, 65, 8, false);
            new NetHandlerPlayServer(actor.getServer(), new NetworkManager(EnumPacketDirection.SERVERBOUND), actor);
            var replacement = actor.connection;
            assertThrows(IllegalArgumentException.class,
                    () -> router.beginCardinal(oldConnection, packet, false, ready));
            actor.connection = oldConnection;
            router.beginCardinal(oldConnection, packet, false, ready);
            actor.connection = replacement;
            var box = actor.getEntityBoundingBox();
            router.move(actor, MoverType.PLAYER, 0.25, 0, 0);
            assertTrue(router.mustCancel());
            assertEquals(PlayerMovementRouter.Failure.ACTOR_CHANGED, router.failure());
            assertSame(box, actor.getEntityBoundingBox());
            router.finish(oldConnection, false);
        } finally {
            NativePlayerMovement.INSTANCE.stop();
        }
    }
}
