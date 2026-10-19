package wi.unigrav.core.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

class ClientInstallationRecoveryTaskTest {
    @Test void firstOwnedFailureCapturesExactRequestBeforeResetAndRetriesAt20TickIntervals(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            for (int tick = 1; tick <= 79; tick++) {
                var result = fixture.task.tick(true, true, sent::add);
                assertFalse(result.finished(), "sending is not installation success");
                assertEquals(Math.min(3, tick / 20), sent.size());
            }
            assertEquals(3, sent.size());
            sent.forEach(packet -> assertSame(fixture.request, packet));
            assertEquals(fixture.failed.scope(), fixture.request.scope());
            assertEquals(fixture.failed.offerSequence(), fixture.request.offerSequence());
            assertEquals(2, fixture.request.sequence(), "APPLY used sequence 1 before failure capture");
            var terminal = fixture.task.tick(true, true, sent::add);
            assertTrue(terminal.finished());
            assertTrue(terminal.exhausted());
            fixture.task.tick(true, true, sent::add);
            assertEquals(3, sent.size());
            assertTrue(fixture.fence.matchesActor(fixture.actor));
        }
    }

    @Test void failedSendRetainsExactCauseAndSuppressedThenRetriesSameIdentityWithinBound(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            var cause = new IllegalStateException("send unexpectedly failed", new IllegalArgumentException("root"));
            var cleanup = new IllegalStateException("secondary cleanup");
            cause.addSuppressed(cleanup);
            var tried = new ArrayList<PlayerPhysicsProtocol.Packet>();
            for (int tick = 1; tick <= 60; tick++) {
                var result = fixture.task.tick(true, true, packet -> { tried.add(packet); throw cause; });
                if (tick % 20 == 0) {
                    assertSame(cause, result.failure().orElseThrow());
                    assertSame(cleanup, result.failure().orElseThrow().getSuppressed()[0]);
                    assertEquals(tick == 60, result.finished());
                } else assertTrue(result.failure().isEmpty());
            }
            assertEquals(3, tried.size());
            tried.forEach(packet -> assertSame(fixture.request, packet));
            fixture.task.tick(true, true, tried::add);
            assertEquals(3, tried.size());
            assertTrue(fixture.fence.matchesActor(fixture.actor));
        }
    }

    @Test void exactActorWorldHandlerManagerAndConnectionChangesCancelBeforeSend(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            assertTrue(fixture.task.matches(fixture.actor, fixture.world.world(), fixture.handler, fixture.manager, fixture.connection));
            assertFalse(fixture.task.matches(fixture.world.serverPlayer(fixture.actor.getUniqueID()), fixture.world.world(),
                    fixture.handler, fixture.manager, fixture.connection));
            var otherManager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            var otherHandler = Fixture.handler(otherManager);
            assertFalse(fixture.task.matches(fixture.actor, fixture.world.world(), otherHandler, fixture.manager, fixture.connection));
            assertFalse(fixture.task.matches(fixture.actor, fixture.world.world(), fixture.handler, otherManager, fixture.connection));
            assertFalse(fixture.task.matches(fixture.actor, fixture.world.world(), fixture.handler, fixture.manager, null));
            assertFalse(fixture.task.matches(fixture.actor, null, fixture.handler, fixture.manager, fixture.connection));
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            assertTrue(fixture.task.tick(false, true, sent::add).finished());
            for (int tick = 0; tick < 90; tick++) fixture.task.tick(true, true, sent::add);
            assertTrue(sent.isEmpty());
            assertTrue(fixture.fence.matchesActor(fixture.actor));
        }
    }

    @Test void pausedTaskExpiresAt80TicksWithoutSending(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            for (int tick = 1; tick < 80; tick++) assertFalse(fixture.task.tick(true, false, sent::add).finished());
            assertTrue(fixture.task.tick(true, false, sent::add).exhausted());
            assertTrue(sent.isEmpty());
        }
    }

    @Test void resumedTaskDoesNotCatchUpMissedSendsInConsecutiveTicks(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            for (int tick = 1; tick <= 45; tick++) fixture.task.tick(true, false, sent::add);
            fixture.task.tick(true, true, sent::add);
            assertEquals(1, sent.size());
            for (int tick = 47; tick <= 65; tick++) fixture.task.tick(true, true, sent::add);
            assertEquals(1, sent.size());
            fixture.task.tick(true, true, sent::add);
            assertEquals(2, sent.size());
            for (int tick = 67; tick <= 80; tick++) fixture.task.tick(true, true, sent::add);
            assertEquals(2, sent.size());
        }
    }

    @Test void actualFmlRouteLookupFailureStaysInsideTypedRecoveryTaskWithoutFakeDelegate(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            for (int tick = 1; tick < 20; tick++) fixture.task.tick(true, true);
            var result = fixture.task.tick(true, true);
            assertTrue(result.failure().isPresent(), "this plain JUnit process has no normal FML client route");
            var actualFailure = result.failure().orElseThrow();
            actualFailure.printStackTrace();
            assertTrue(java.util.Arrays.stream(actualFailure.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().equals("net.minecraftforge.fml.common.FMLCommonHandler")),
                    "the original stack must identify the actual FML route lookup boundary");
            assertFalse(result.finished(), "same identity may retry only under the bounded idempotent contract");
            assertTrue(fixture.fence.matchesActor(fixture.actor));
        }
    }

    @Test void foreignOrMerelyEqualFailedPacketCannotMintRecoveryAndResetDoesNotMintNewIdentity(@TempDir Path path) {
        try (Fixture fixture = new Fixture(path)) {
            assertTrue(fixture.session.recoveryRequestForFailure(fixture.failed).isEmpty(), "reset invalidates failure minting");
            assertTrue(fixture.session.recoveryRequest().isEmpty());
            assertEquals(PlayerPhysicsProtocol.Kind.RECOVER, fixture.request.kind());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BootWorldFixture world;
        final EntityPlayerMP actor;
        final NetworkManager manager;
        final NetHandlerPlayClient handler;
        final EmbeddedChannel channel;
        final PlayerPhysicsInboundConnections.ConnectionHandle connection;
        final PlayerPhysicsClientSession session;
        final PlayerPhysicsProtocol.Packet failed;
        final PlayerPhysicsProtocol.Packet request;
        final BrokenActorInstallation fence;
        final ClientInstallationRecoveryTask task;

        Fixture(Path path) {
            world = BootWorldFixture.open(path, UUID.randomUUID(), false);
            actor = world.serverPlayer(UUID.randomUUID());
            manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            handler = handler(manager);
            manager.setNetHandler(handler);
            channel = new EmbeddedChannel(manager);
            connection = PlayerPhysicsInboundConnections.INSTANCE.bindClientConnected(
                    new FMLNetworkEvent.ClientConnectedToServerEvent(manager, "recovery-task-test")).orElseThrow();
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 7);
            session = new PlayerPhysicsClientSession(() -> {}, () -> {});
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope,
                    0, 1, Optional.empty(), Optional.empty(), Optional.empty())));
            session.prepare(new CPacketPlayer.Position(0, 70, 0, false), new Vec3d(0, 70, 0),
                    0, 0, false, Optional.empty(), packet -> {});
            var snapshot = new PlayerPhysicalSnapshot(scope, 1, new PlayerBodyPlacement(new Vec3d(0, 70, 0),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                    new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F))), new Vec3d(0, 0, 0));
            failed = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, scope, 1, 1,
                    Optional.empty(), Optional.of(snapshot), Optional.empty());
            var preparation = session.prepareInstallation(failed).prepared().orElseThrow();
            assertTrue(session.beginPreparedInstallation(preparation));
            fence = new BrokenActorInstallation(actor, world.world(), 1, UUID.randomUUID());
            fence.recordScope(manager, scope);
            assertTrue(session.failPreparedInstallation(preparation, new IllegalStateException("first OWNED install failed")));
            assertTrue(session.snapshot().isEmpty());
            request = session.recoveryRequestForFailure(failed).orElseThrow();
            var equalPacket = new PlayerPhysicsProtocol.Packet(failed.kind(), scope, 1, 1,
                    Optional.empty(), Optional.of(snapshot), Optional.empty());
            assertEquals(failed, equalPacket);
            assertTrue(session.recoveryRequestForFailure(equalPacket).isEmpty());
            assertSame(request, session.recoveryRequestForFailure(failed).orElseThrow());
            session.reset();
            task = new ClientInstallationRecoveryTask(actor, world.world(), handler, manager, connection, request);
        }

        static NetHandlerPlayClient handler(NetworkManager manager) {
            return new NetHandlerPlayClient((Minecraft) null, (GuiScreen) null, manager,
                    new GameProfile(UUID.randomUUID(), "recovery-client"));
        }

        public void close() { channel.finishAndReleaseAll(); world.close(); }
    }
}
