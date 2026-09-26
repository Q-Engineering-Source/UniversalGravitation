package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraft.world.GameType;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.GIntentServer;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Real MP + unconnected Netty LocalChannel; direct event delivery, no FML/server loop or socket. */
class GIntentServerIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void realLocalCapabilityNegotiatesExplicitOffAndUnregisterImmediatelyInvalidates(@TempDir Path directory)
            throws Exception {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 901))) {
            var player = fixture.serverPlayer(new UUID(0, 902));
            var replies = new ArrayList<GIntentProtocol.Packet>();
            var server = new GIntentServer((target, packet) -> replies.add(GIntentProtocol.decode(GIntentProtocol.encode(packet))));
            try (var link = new LocalLink(player.connection.netManager)) {
                assertTrue(player.connection.netManager.isLocalChannel());
                assertTrue(server.observe(player).isEmpty());
                server.onConnection(new FMLNetworkEvent.ServerConnectionFromClientEvent(player.connection.netManager));
                tick(server);
                assertTrue(replies.isEmpty(), "a connection without ugphys1 registration receives no offer");
                registration(server, player.connection.netManager, "REGISTER");
                tick(server);
                var offer = replies.removeFirst();
                assertEquals(GIntentProtocol.Kind.OFFER, offer.kind());
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, offer.key(), 0,
                        GIntentProtocol.State.UNKNOWN), player.connection);
                assertTrue(server.observe(player).isEmpty());
                assertEquals(GIntentProtocol.State.UNKNOWN, replies.removeFirst().state());
                player.motionX = 2.5;
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 1,
                        GIntentProtocol.State.OFF), player.connection);
                assertEquals(false, server.observe(player).orElseThrow().active());
                assertEquals(1, server.observe(player).orElseThrow().revision());
                assertEquals(2.5, player.motionX, "G synchronization does not integrate or reset motion");
                replies.clear();
                registration(server, player.connection.netManager, "UNREGISTER");
                assertTrue(server.observe(player).isEmpty(), "do not wait for the next START teardown");
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 2,
                        GIntentProtocol.State.OFF), player.connection);
                assertTrue(replies.isEmpty());
            } finally { server.stop(); }
        }
    }

    @Test
    void nonLocalMemoryTransportDoesNotGetWritableIntegratedIntent(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 911))) {
            var player = fixture.serverPlayer(new UUID(0, 912));
            var replies = new ArrayList<GIntentProtocol.Packet>();
            var server = new GIntentServer((target, packet) -> replies.add(packet));
            var channel = new EmbeddedChannel(player.connection.netManager);
            try {
                server.onConnection(new FMLNetworkEvent.ServerConnectionFromClientEvent(player.connection.netManager));
                registration(server, player.connection.netManager, "REGISTER");
                tick(server);
                assertTrue(replies.isEmpty());
                assertTrue(server.observe(player).isEmpty());
            } finally {
                server.stop();
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void dedicatedOrdinaryPlayerRequiresPhysicsRegistrationAndExplicitOff(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 931), true)) {
            var player = fixture.serverPlayer(new UUID(0, 932));
            player.interactionManager.setGameType(GameType.SURVIVAL);
            var replies = new ArrayList<GIntentProtocol.Packet>();
            var server = new GIntentServer((target, packet) -> replies.add(packet));
            var channel = new EmbeddedChannel(player.connection.netManager);
            try {
                server.onConnection(new FMLNetworkEvent.ServerConnectionFromClientEvent(player.connection.netManager));
                registration(server, player.connection.netManager, "REGISTER");
                tick(server);
                assertTrue(replies.isEmpty());
                server.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(player.connection.netManager,
                        Set.of(PlayerPhysicsProtocol.CHANNEL), "REGISTER", Side.SERVER, INetHandlerPlayServer.class));
                tick(server);
                assertEquals(1, replies.size(), "ordinary physics peer gets its own G-source negotiation");
                var offer = replies.removeFirst();
                assertTrue(server.observe(player).isEmpty());
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, offer.key(), 0,
                        GIntentProtocol.State.UNKNOWN), player.connection);
                assertTrue(server.observe(player).isEmpty(), "HELLO cannot infer OFF");
                replies.clear();
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 1,
                        GIntentProtocol.State.OFF), player.connection);
                assertEquals(false, server.observe(player).orElseThrow().active());
                assertEquals(GIntentProtocol.State.OFF, replies.removeFirst().state());
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 2,
                        GIntentProtocol.State.ON), player.connection);
                assertEquals(false, server.observe(player).orElseThrow().active(), "Survival does not gain personal zero-G");
                assertTrue(replies.isEmpty());
                server.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(player.connection.netManager,
                        Set.of(PlayerPhysicsProtocol.CHANNEL), "UNREGISTER", Side.SERVER, INetHandlerPlayServer.class));
                assertTrue(server.observe(player).isEmpty(), "physics withdrawal immediately invalidates negotiated source");
            } finally { server.stop(); channel.finishAndReleaseAll(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualGameModeControlsOnAdmissionAndRespawnRetiresTheSource(boolean creative, @TempDir Path directory)
            throws Exception {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 921))) {
            var player = fixture.serverPlayer(new UUID(0, 922));
            // Use the real game-mode setter before attaching the unconnected transport.
            player.interactionManager.setGameType(creative ? GameType.CREATIVE : GameType.SURVIVAL);
            assertEquals(creative, player.isCreative());
            var replies = new ArrayList<GIntentProtocol.Packet>();
            var server = new GIntentServer((target, packet) -> replies.add(packet));
            try (var link = new LocalLink(player.connection.netManager)) {
                server.onConnection(new FMLNetworkEvent.ServerConnectionFromClientEvent(player.connection.netManager));
                registration(server, player.connection.netManager, "REGISTER");
                tick(server);
                var offer = replies.removeFirst();
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, offer.key(), 0,
                        GIntentProtocol.State.UNKNOWN), player.connection);
                replies.clear();
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 1,
                        GIntentProtocol.State.ON), player.connection);
                if (creative) {
                    assertEquals(true, server.observe(player).orElseThrow().active());
                    assertEquals(GIntentProtocol.State.ON, replies.removeFirst().state());
                } else {
                    assertTrue(server.observe(player).isEmpty());
                    assertTrue(replies.isEmpty());
                }
                server.onRespawn(new PlayerEvent.PlayerRespawnEvent(player, false));
                assertTrue(server.observe(player).isEmpty());
                tick(server);
                var freshOffer = replies.removeFirst();
                assertNotEquals(offer.key(), freshOffer.key());
                server.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT, offer.key(), 2,
                        GIntentProtocol.State.OFF), player.connection);
                assertTrue(replies.isEmpty());
                assertTrue(server.observe(player).isEmpty());
            } finally { server.stop(); }
        }
    }

    private static void registration(GIntentServer server, NetworkManager manager, String operation) {
        server.onRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(manager,
                Set.of(GIntentProtocol.CHANNEL), operation, Side.SERVER, INetHandlerPlayServer.class));
    }

    private static void tick(GIntentServer server) {
        server.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
    }

    private static final class LocalLink implements AutoCloseable {
        private final DefaultEventLoop loop = new DefaultEventLoop();
        private final LocalChannel channel = new LocalChannel();

        private LocalLink(NetworkManager manager) throws Exception {
            boolean ready = false;
            try {
                loop.register(channel).sync();
                channel.pipeline().addLast("native-manager", manager);
                loop.submit(() -> channel.pipeline().fireChannelActive()).sync();
                ready = true;
            } finally {
                if (!ready) {
                    if (channel.isRegistered()) channel.close().syncUninterruptibly();
                    loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
                }
            }
        }

        @Override public void close() {
            try { channel.close().syncUninterruptibly(); }
            finally { loop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly(); }
        }
    }
}
