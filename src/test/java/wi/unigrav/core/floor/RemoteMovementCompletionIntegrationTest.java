package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.server.SPacketEntityVelocity;
import net.minecraft.world.GameType;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerMovementRouter;

/** Real MP and public protocol handlers; no private sessions, FML loop or socket. */
class RemoteMovementCompletionIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void negotiatedNativeMoveProducesOneCompletionWithoutResettingGOrMotion(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.nativeMove(2);
            var completion = setup.remote.finishMovement(setup.player.connection, false);
            assertTrue(completion.isPresent(), "only the completed, budget-committed native result is observable");
            assertEquals(10, setup.player.posX);
            assertEquals(3.0, setup.player.motionX);
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
            assertTrue(setup.remote.consumeCompletedMovement(setup.player.connection, completion.orElseThrow()));
            assertFalse(setup.remote.consumeCompletedMovement(setup.player.connection, completion.orElseThrow()));
            assertTrue(setup.remote.finishMovement(setup.player.connection, false).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateEndpointMutationRejectsAndClearsTicketWithoutRefund(boolean nonfinite, @TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.nativeMove(2);
            setup.player.posX = nonfinite ? Double.NaN : 12.0;
            assertTrue(setup.remote.finishMovement(setup.player.connection, false).isEmpty());
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
            // Restore the last accepted origin as a test input, not a production rollback.
            setup.player.setPosition(8, 65, 8);
            setup.nativeMove(3);
            assertEquals(9.5, setup.player.posX, "the original 3.5m bucket lost 2m, without getting stuck pending");
            assertTrue(setup.remote.finishMovement(setup.player.connection, false).isPresent());
        }
    }

    @Test
    void routerPublishesRemoteCenterOnlyAfterTheWholeNativePacket(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.routerMove(2, () -> assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(
                    setup.player.connection).isEmpty(), "tracker receipt is still provisional"));
            var samples = NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection)
                    .orElseThrow().samples();
            assertEquals(1, samples.size());
            assertEquals(10, samples.getFirst().center().x());
            assertEquals(65.89999997615814, samples.getFirst().center().y());
            assertEquals(3, setup.player.motionX);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"next_packet", "teleport", "rebase", "tracker_velocity", "unregister",
            "disconnect", "world_tick", "server_tick", "dimension", "shape", "motion", "stop", "mode_off"})
    void completedEvidenceCannotSurviveRelevantSourceChanges(String change, @TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.nativeMove(2);
            var completion = setup.remote.finishMovement(setup.player.connection, false).orElseThrow();
            switch (change) {
                case "next_packet" -> setup.remote.replacePacketForNativeMove(setup.player.connection,
                        new CPacketPlayer.Rotation(0, 0, false), new Vec3(10, 65, 8), false);
                case "teleport", "rebase" -> {
                    setup.remote.observeNativeTeleport(setup.player.connection, 7, new Vec3(10, 65, 8));
                    if (change.equals("rebase")) setup.remote.confirmNativeTeleport(setup.player.connection,
                            new CPacketConfirmTeleport(7), true);
                }
                case "tracker_velocity" -> setup.remote.afterTrackerVelocity(setup.player,
                        new SPacketEntityVelocity(setup.player));
                case "unregister" -> setup.remote.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(
                        setup.player.connection.netManager, Set.of(BoostProtocol.CHANNEL), "UNREGISTER",
                        Side.SERVER, INetHandlerPlayServer.class));
                case "disconnect" -> setup.remote.onConnectionClosed(new FMLNetworkEvent.ServerDisconnectionFromClientEvent(
                        setup.player.connection.netManager));
                case "world_tick" -> setup.fixture.world().setTotalWorldTime(1);
                case "server_tick" -> setup.remote.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
                case "dimension" -> setup.player.dimension = 1;
                case "shape" -> setup.player.setEntityBoundingBox(setup.player.getEntityBoundingBox()
                        .offset(Math.ulp(setup.player.posX), 0, 0));
                case "motion" -> setup.player.motionX = 4;
                case "stop" -> setup.remote.stop();
                case "mode_off" -> setup.remote.receive(BoostProtocol.modeRequest(setup.nonce, setup.epoch,
                        2, setup.player.dimension, false), setup.player.connection);
                default -> throw new AssertionError(change);
            }
            assertFalse(setup.remote.consumeCompletedMovement(setup.player.connection, completion));
            assertFalse(setup.remote.consumeCompletedMovement(setup.player.connection, completion));
        }
    }

    @Test
    void committedClippingStillRequiresCorrectionBeforeItCanBecomeAnObservation(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.nativeMove(4);
            var completion = setup.remote.finishMovement(setup.player.connection, false).orElseThrow();
            assertEquals(11.5, setup.player.posX);
            assertFalse(setup.remote.consumeCompletedMovement(setup.player.connection, completion));
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
        }
    }

    @Test
    void lateExceptionDoesNotPublishOrRefundOrSwitchGOff(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            var failure = new IllegalStateException("late native callback");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> setup.routerMove(2, () -> { throw failure; })));
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
            setup.player.setPosition(8, 65, 8);
            setup.nativeMove(3);
            assertEquals(9.5, setup.player.posX);
            assertTrue(setup.remote.finishMovement(setup.player.connection, false).isPresent());
        }
    }

    @Test
    void lateWorldTickCannotRelabelTheEarlierRemoteMovement(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.routerMove(2, () -> setup.fixture.world().setTotalWorldTime(1));
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
        }
    }

    @Test
    void actualNativeWallCollisionIsNotPublishedBeforeItsCorrection(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.fixture.put(new BlockPos(10, 65, 8), Blocks.STONE.getDefaultState());
            setup.fixture.put(new BlockPos(10, 66, 8), Blocks.STONE.getDefaultState());
            setup.routerMove(2, () -> { });
            assertTrue(setup.player.posX > 9 && setup.player.posX < 10, "sole native collision clips at the wall");
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
        }
    }

    @Test
    void velocityBarrierAfterTrackingCannotPublishAnObservation(@TempDir Path directory) {
        try (var setup = new Setup(directory)) {
            setup.routerMove(2, () -> setup.player.velocityChanged = true);
            assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
            assertEquals(10, setup.player.posX);
            assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
        }
    }

    @Test
    void remoteImpulseDuringNativeMoveCannotCertifyItsOldCompletion(@TempDir Path directory) {
        GravityEntityBinder oldBinder = UnigravMod.entityBinder;
        try (var setup = new Setup(directory)) {
            var binder = new GravityEntityBinder();
            UnigravMod.entityBinder = binder;
            try (var binding = binder.bind(setup.player, GravityMode.REPLACE, new BodyPhysics(100, 1))) {
                setup.nativeMove(2);
                assertTrue(setup.remote.addImpulse(setup.player, new Vec3(100, 0, 0)));
                var reply = setup.replies.getLast();
                assertEquals(BoostProtocol.Operation.ADD, reply.operation());
                assertEquals(3, setup.player.motionX, "Remote ADD does not write MP motion");
                assertTrue(setup.remote.finishMovement(setup.player.connection, false).isEmpty(),
                        "the old movement predates an external impulse");
                assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
                setup.nativeMove(3);
                assertEquals(11.5, setup.player.posX,
                        "continue from the real endpoint with 1.5m credit, never roll back to the old origin");
                assertTrue(setup.remote.finishMovement(setup.player.connection, false).isPresent());
            }
        } finally { UnigravMod.entityBinder = oldBinder; }
    }

    @Test
    void routerDropsImpulseCrossingObservationButKeepsActualMovementAndCredit(@TempDir Path directory) {
        GravityEntityBinder oldBinder = UnigravMod.entityBinder;
        try (var setup = new Setup(directory)) {
            var binder = new GravityEntityBinder();
            UnigravMod.entityBinder = binder;
            try (var binding = binder.bind(setup.player, GravityMode.REPLACE, new BodyPhysics(100, 1))) {
                setup.routerMove(2, () -> assertTrue(setup.remote.addImpulse(setup.player, new Vec3(100, 0, 0))));
                assertEquals(10, setup.player.posX);
                assertEquals(3, setup.player.motionX);
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
                assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
                setup.nativeMove(3);
                assertEquals(11.5, setup.player.posX, "the next move retains the committed origin and debit");
                assertTrue(setup.remote.finishMovement(setup.player.connection, false).isPresent());
            }
        } finally { UnigravMod.entityBinder = oldBinder; }
    }

    @Test
    void remoteImpulseBetweenPacketsDiscardsOldWindowWithoutResettingCredit(@TempDir Path directory) {
        GravityEntityBinder oldBinder = UnigravMod.entityBinder;
        try (var setup = new Setup(directory)) {
            var binder = new GravityEntityBinder();
            UnigravMod.entityBinder = binder;
            try (var registration = binder.bind(setup.player, GravityMode.ADDITIVE, new BodyPhysics(100, 0.1))) {
                setup.routerMove(2, () -> { });
                assertEquals(1, NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection)
                        .orElseThrow().samples().size());
                assertTrue(setup.remote.addImpulse(setup.player, new Vec3(100, 0, 0)));
                assertTrue(NativePlayerMovement.INSTANCE.acceptedCenters(setup.player.connection).isEmpty());
                assertEquals(10, setup.player.posX);
                assertEquals(3, setup.player.motionX);
                assertTrue(setup.remote.observePersonalG(setup.player).orElseThrow().active());
                setup.routerMove(3, () -> { });
                assertEquals(11.5, setup.player.posX, "the old bucket retained exactly its remaining 1.5m");
            }
        } finally { UnigravMod.entityBinder = oldBinder; }
    }

    private static final class Setup implements AutoCloseable {
        private final boolean oldEnabled = UnigravConfig.remoteCreativeBoost;
        private final BootWorldFixture fixture;
        private final EntityPlayerMP player;
        private final ArrayList<BoostProtocol.Packet> replies = new ArrayList<>();
        private final RemoteBoostServer remote = new RemoteBoostServer((target, packet) -> replies.add(packet));
        private EmbeddedChannel channel;
        private long nonce;
        private long epoch;
        private long frameSequence;
        private final PlayerMovementRouter router = new PlayerMovementRouter(remote);

        private Setup(Path directory) {
            fixture = BootWorldFixture.open(directory, new UUID(0, 951), true);
            boolean ready = false;
            try {
                UnigravConfig.remoteCreativeBoost = true;
                player = fixture.serverPlayer(new UUID(0, 952));
                player.interactionManager.setGameType(GameType.CREATIVE);
                player.setPosition(8, 65, 8);
                player.motionX = 3.0;
                channel = new EmbeddedChannel(player.connection.netManager);
                var manager = player.connection.netManager;
                remote.onConnection(new FMLNetworkEvent.ServerConnectionFromClientEvent(manager));
                remote.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(manager,
                        Set.of(BoostProtocol.CHANNEL), "REGISTER", Side.SERVER, INetHandlerPlayServer.class));
                remote.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
                var offer = replies.removeFirst();
                assertTrue(offer.flag());
                nonce = offer.nonce();
                remote.receive(BoostProtocol.hello(nonce, true), player.connection);
                var negotiated = replies.removeFirst();
                assertEquals(BoostProtocol.Mode.READY, negotiated.mode());
                remote.receive(BoostProtocol.modeRequest(nonce, negotiated.epoch(), 1,
                        player.dimension, true), player.connection);
                var active = replies.removeFirst();
                assertEquals(BoostProtocol.Mode.ACTIVE, active.mode());
                epoch = active.epoch();
                ready = true;
            } finally {
                if (!ready) close();
            }
        }

        private void nativeMove(double delta) {
            remote.receive(BoostProtocol.frame(nonce, epoch, ++frameSequence, player.dimension, 5, false),
                    player.connection);
            Vec3 start = new Vec3(player.posX, player.posY, player.posZ);
            var packet = remote.replacePacketForNativeMove(player.connection,
                    new CPacketPlayer.Position(start.x() + delta, start.y(), start.z(), false), start, false);
            player.move(MoverType.PLAYER, packet.getX(start.x()) - start.x(), 0, 0);
            remote.captureNativeResult(player.connection);
            assertTrue(remote.mayCommitNativeEndpoint(player.connection, false));
            Vec3 end = remote.finalNativePosition(player.connection, start);
            player.setPositionAndRotation(end.x(), end.y(), end.z(), 0, 0);
            remote.markMovementSuccess(player.connection);
        }

        private void routerMove(double delta, Runnable tail) {
            remote.receive(BoostProtocol.frame(nonce, epoch, ++frameSequence, player.dimension, 5, false),
                    player.connection);
            router.runPacket(player.connection, () -> {
                Vec3 start = new Vec3(player.posX, player.posY, player.posZ);
                var packet = router.begin(player.connection,
                        new CPacketPlayer.Position(start.x() + delta, start.y(), start.z(), false), start, false);
                double endX = packet.getX(start.x());
                router.move(player, MoverType.PLAYER, endX - start.x(), 0, 0);
                router.assignEndpoint(player, endX, start.y(), start.z(), 0, 0, false);
                router.beforeTracking(false);
                tail.run();
            }, () -> false);
        }

        @Override public void close() {
            try { remote.stop(); NativePlayerMovement.INSTANCE.stop(); }
            finally {
                try { if (channel != null) channel.finishAndReleaseAll(); }
                finally {
                    try { fixture.close(); }
                    finally { UnigravConfig.remoteCreativeBoost = oldEnabled; }
                }
            }
        }
    }
}
