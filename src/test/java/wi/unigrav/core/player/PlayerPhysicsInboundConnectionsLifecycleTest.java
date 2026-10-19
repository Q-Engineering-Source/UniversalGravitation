package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.INetHandlerPlayServer;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;

final class PlayerPhysicsInboundConnectionsLifecycleTest {
    @Test
    void clientConnectBindsBeforeSessionAndDuplicateKeepsTheSameHandle() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            FMLNetworkEvent.ClientConnectedToServerEvent event = peer.connectedEvent();

            PlayerPhysicsInboundConnections.ConnectionHandle first =
                    facade.bindClientConnected(event).orElseThrow();
            PlayerPhysicsInboundConnections.ConnectionHandle duplicate =
                    facade.bindClientConnected(event).orElseThrow();

            assertSame(first, duplicate, "duplicate connect must preserve the connection lifetime");
            assertSame(first, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow());
            assertTrue(facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.SERVERBOUND).isEmpty(),
                    "the client event fixes the receiving direction to CLIENTBOUND");
        }
    }

    @Test
    void lookupOfAnUnboundManagerDoesNotCreateAConnection() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            assertTrue(facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            assertTrue(facade.bindClientConnected(peer.connectedEvent()).isPresent(),
                    "only the subsequent real connect event may create the entry");
        }
    }

    @Test
    void staleCapturedConnectEventCannotPoisonHealthyOrUnboundManagers() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer boundPeer = ClientPeer.open(); ClientPeer unboundPeer = ClientPeer.open()) {
            PlayerPhysicsInboundConnections.ConnectionHandle existing =
                    facade.bindClientConnected(boundPeer.connectedEvent()).orElseThrow();

            NetHandlerPlayClient staleForBound = boundPeer.newHandler();
            boundPeer.manager.setNetHandler(staleForBound);
            FMLNetworkEvent.ClientConnectedToServerEvent capturedStaleForBound = boundPeer.connectedEvent();
            boundPeer.manager.setNetHandler(boundPeer.handler);

            assertTrue(facade.bindClientConnected(capturedStaleForBound).isEmpty());
            assertSame(existing, facade.findCurrentConnection(boundPeer.manager, boundPeer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow(),
                    "a stale event cannot terminate an entry whose actual manager binding is healthy");
            assertSame(existing, facade.bindClientConnected(boundPeer.connectedEvent()).orElseThrow());

            NetHandlerPlayClient staleForUnbound = unboundPeer.newHandler();
            unboundPeer.manager.setNetHandler(staleForUnbound);
            FMLNetworkEvent.ClientConnectedToServerEvent capturedStaleForUnbound = unboundPeer.connectedEvent();
            unboundPeer.manager.setNetHandler(unboundPeer.handler);

            assertTrue(facade.bindClientConnected(capturedStaleForUnbound).isEmpty());
            assertTrue(facade.bindClientConnected(unboundPeer.connectedEvent()).isPresent(),
                    "a stale event cannot leave a tombstone on an unbound manager");
        }
    }

    @Test
    void channelUnregisterRevokesOnlyTheExactLiveBindingAndCannotBeRenewed() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer unboundPeer = ClientPeer.open(); ClientPeer peer = ClientPeer.open()) {
            facade.onCustomPacketRegistration(registrationEvent(unboundPeer, "UNREGISTER"));
            assertTrue(facade.bindClientConnected(unboundPeer.connectedEvent()).isPresent(),
                    "an unregister event without an existing binding cannot poison the manager");

            FMLNetworkEvent.ClientConnectedToServerEvent connected = peer.connectedEvent();
            PlayerPhysicsInboundConnections.ConnectionHandle handle =
                    facade.bindClientConnected(connected).orElseThrow();

            facade.onCustomPacketRegistration(registrationEvent(peer, "REGISTER"));
            assertSame(handle, facade.bindClientConnected(connected).orElseThrow(),
                    "REGISTER does not replace or renew the current handle");

            facade.onCustomPacketRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(peer.manager,
                    Set.of(PlayerPhysicsProtocol.CHANNEL), "UNREGISTER", Side.SERVER,
                    INetHandlerPlayClient.class));
            assertSame(handle, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow(),
                    "a server-side registration event cannot revoke a clientbound binding");

            NetHandlerPlayClient capturedWrongHandler = peer.newHandler();
            peer.manager.setNetHandler(capturedWrongHandler);
            FMLNetworkEvent.CustomPacketRegistrationEvent<INetHandlerPlayClient> staleUnregister =
                    registrationEvent(peer, "UNREGISTER");
            peer.manager.setNetHandler(peer.handler);
            facade.onCustomPacketRegistration(staleUnregister);
            assertSame(handle, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow(),
                    "an unregister captured for a different handler cannot revoke the restored live binding");

            facade.onCustomPacketRegistration(registrationEvent(peer, "UNREGISTER"));

            assertTrue(peer.manager.isChannelOpen(),
                    "protocol unregister revokes budget permission without claiming the physical channel closed");
            assertTrue(facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            facade.onCustomPacketRegistration(registrationEvent(peer, "REGISTER"));
            assertTrue(facade.bindClientConnected(connected).isEmpty(),
                    "REGISTER and duplicate connect cannot revive an unregistered lifetime");
            assertFalse(facade.invalidateConnection(handle,
                    PlayerPhysicsInboundConnections.InvalidationReason.CHANNEL_UNREGISTERED));
        }
    }

    @Test
    void wrongHandlerLookupDoesNotRenewAndActualHandlerReplacementIsTerminal() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            PlayerPhysicsInboundConnections.ConnectionHandle first =
                    facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            NetHandlerPlayClient replacement = peer.newHandler();

            assertTrue(facade.findCurrentConnection(peer.manager, replacement,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            assertSame(first, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow(),
                    "a caller-supplied wrong handler must not disturb the live binding");

            peer.manager.setNetHandler(replacement);
            assertTrue(facade.findCurrentConnection(peer.manager, replacement,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            assertTrue(facade.bindClientConnected(peer.connectedEvent()).isEmpty(),
                    "a replaced handler must not receive a new lifetime on the same manager");
        }
    }

    @Test
    void handlerReplacementHookRequiresTheActualManagerMonitor() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            NetHandlerPlayClient replacement = peer.newHandler();

            assertThrows(IllegalStateException.class,
                    () -> facade.beforeActualHandlerAssignment(peer.manager, peer.handler, replacement));
        }
    }

    @Test
    void handlerReplacementHookRejectsAStaleExpectedPreviousIdentity() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            PlayerPhysicsInboundConnections.ConnectionHandle handle =
                    facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            NetHandlerPlayClient stalePrevious = peer.newHandler();
            NetHandlerPlayClient next = peer.newHandler();

            synchronized (peer.manager) {
                assertThrows(IllegalStateException.class,
                        () -> facade.beforeActualHandlerAssignment(peer.manager, stalePrevious, next));
            }

            assertSame(handle, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow(),
                    "a stale replacement request cannot revoke the actual current binding");
        }
    }

    @Test
    void assigningTheSameHandlerDoesNotRevokeItsLifetime() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            PlayerPhysicsInboundConnections.ConnectionHandle handle =
                    facade.bindClientConnected(peer.connectedEvent()).orElseThrow();

            synchronized (peer.manager) {
                facade.beforeActualHandlerAssignment(peer.manager, peer.handler, peer.handler);
                // The setter is not Mixin-transformed in this source-only test; this exercises only the hook seam.
                peer.manager.setNetHandler(peer.handler);
            }

            assertSame(handle, facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow());
        }
    }

    @Test
    void differentHandlerRevokesBeforeAssignmentAndDoesNotCreateAnUnboundLedger() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer boundPeer = ClientPeer.open(); ClientPeer unboundPeer = ClientPeer.open()) {
            PlayerPhysicsInboundConnections.ConnectionHandle oldHandle =
                    facade.bindClientConnected(boundPeer.connectedEvent()).orElseThrow();
            NetHandlerPlayClient replacement = boundPeer.newHandler();

            synchronized (boundPeer.manager) {
                facade.beforeActualHandlerAssignment(boundPeer.manager, boundPeer.handler, replacement);
                assertTrue(facade.findCurrentConnection(boundPeer.manager, boundPeer.handler,
                        EnumPacketDirection.CLIENTBOUND).isEmpty(),
                        "the old lifetime is revoked before the packet listener assignment");
                assertFalse(facade.invalidateConnection(oldHandle,
                        PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT));
                // The setter is not Mixin-transformed in this source-only test; this exercises only the hook seam.
                boundPeer.manager.setNetHandler(replacement);
            }
            assertTrue(facade.bindClientConnected(boundPeer.connectedEvent()).isEmpty(),
                    "a replaced manager cannot acquire a second lifetime");

            NetHandlerPlayClient unboundReplacement = unboundPeer.newHandler();
            synchronized (unboundPeer.manager) {
                facade.beforeActualHandlerAssignment(unboundPeer.manager,
                        unboundPeer.handler, unboundReplacement);
                // A replacement without a live entry must not install a terminal record.
                unboundPeer.manager.setNetHandler(unboundReplacement);
            }
            assertTrue(facade.findCurrentConnection(unboundPeer.manager, unboundReplacement,
                    EnumPacketDirection.CLIENTBOUND).isEmpty(),
                    "replacement alone cannot create a connection ledger");
            assertTrue(facade.bindClientConnected(unboundPeer.connectedEvent()).isPresent(),
                    "the first real connect for the installed handler may create the only ledger");
        }
    }

    @Test
    void staleConnectCapturedBeforeReplacementWaitsForTheManagerAndCannotCreateAnOldLedger()
            throws InterruptedException, ExecutionException, TimeoutException {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            FMLNetworkEvent.ClientConnectedToServerEvent staleConnect = peer.connectedEvent();
            NetHandlerPlayClient replacement = peer.newHandler();
            CountDownLatch hookCompleted = new CountDownLatch(1);
            CountDownLatch allowAssignment = new CountDownLatch(1);
            CountDownLatch bindStarted = new CountDownLatch(1);
            AtomicReference<Thread> bindThread = new AtomicReference<>();
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<?> replacing = workers.submit(() -> {
                    synchronized (peer.manager) {
                        facade.beforeActualHandlerAssignment(peer.manager, peer.handler, replacement);
                        hookCompleted.countDown();
                        try {
                            if (!allowAssignment.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("timed out waiting to finish the handler assignment");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("handler replacement worker was interrupted", interrupted);
                        }
                        // This source-only test calls the lifecycle hook directly; the Redirect is not applied here.
                        peer.manager.setNetHandler(replacement);
                    }
                });
                assertTrue(hookCompleted.await(5, TimeUnit.SECONDS));

                Future<Optional<PlayerPhysicsInboundConnections.ConnectionHandle>> staleBinding = workers.submit(() -> {
                    bindThread.set(Thread.currentThread());
                    bindStarted.countDown();
                    return facade.bindClientConnected(staleConnect);
                });
                assertTrue(bindStarted.await(5, TimeUnit.SECONDS));
                assertTrue(awaitBlockedOnManager(bindThread.get()),
                        "binding waits for the actual NetworkManager monitor");

                allowAssignment.countDown();
                replacing.get(5, TimeUnit.SECONDS);
                assertTrue(staleBinding.get(5, TimeUnit.SECONDS).isEmpty(),
                        "a connect carrying the old handler cannot create a ledger after replacement");
                assertTrue(facade.bindClientConnected(peer.connectedEvent()).isPresent(),
                        "replacement without an earlier ledger leaves the current real connect eligible");
            } finally {
                allowAssignment.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void disconnectRevokesTheBindingBeforeAnySessionExists() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            FMLNetworkEvent.ClientConnectedToServerEvent connected = peer.connectedEvent();
            PlayerPhysicsInboundConnections.ConnectionHandle handle =
                    facade.bindClientConnected(connected).orElseThrow();

            facade.onClientDisconnected(new FMLNetworkEvent.ClientDisconnectionFromServerEvent(peer.manager));

            assertTrue(facade.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            assertFalse(facade.invalidateConnection(handle,
                    PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT),
                    "a terminal handle cannot invalidate again or renew itself");
            assertTrue(facade.bindClientConnected(connected).isEmpty(),
                    "disconnect is terminal even before a Session is installed");
        }
    }

    @Test
    void foreignHandleCannotInvalidateAnotherFacadeEntry() {
        PlayerPhysicsInboundConnections owner = new PlayerPhysicsInboundConnections();
        PlayerPhysicsInboundConnections foreign = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            FMLNetworkEvent.ClientConnectedToServerEvent event = peer.connectedEvent();
            PlayerPhysicsInboundConnections.ConnectionHandle ownerHandle =
                    owner.bindClientConnected(event).orElseThrow();
            PlayerPhysicsInboundConnections.ConnectionHandle foreignHandle =
                    foreign.bindClientConnected(event).orElseThrow();
            assertNotSame(ownerHandle, foreignHandle);

            assertFalse(owner.invalidateConnection(foreignHandle,
                    PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT));
            assertSame(ownerHandle, owner.findCurrentConnection(peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND).orElseThrow());
        }
    }

    @Test
    void serverConnectRequiresTheActualPlayerConnectionAndFixesServerboundDirection(@TempDir Path tempDir) {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        UUID playerId = UUID.randomUUID();
        try (BootWorldFixture fixture = BootWorldFixture.open(tempDir.resolve("server"), playerId, false)) {
            EntityPlayerMP player = fixture.serverPlayer(playerId);
            NetHandlerPlayServer handler = player.connection;
            NetworkManager manager = handler.netManager;
            EmbeddedChannel channel = new EmbeddedChannel(manager);
            try {
                FMLNetworkEvent.ServerConnectionFromClientEvent event =
                        new FMLNetworkEvent.ServerConnectionFromClientEvent(manager);
                PlayerPhysicsInboundConnections.ConnectionHandle handle =
                        facade.bindServerConnected(event).orElseThrow();

                assertSame(handle, facade.findCurrentConnection(manager, handler,
                        EnumPacketDirection.SERVERBOUND).orElseThrow());
                assertTrue(facade.findCurrentConnection(manager, handler,
                        EnumPacketDirection.CLIENTBOUND).isEmpty(),
                        "the server event fixes the receiving direction to SERVERBOUND");

                PlayerPhysicsInboundConnections.ConnectionHandle foreign =
                        new PlayerPhysicsInboundConnections().bindServerConnected(event).orElseThrow();
                assertFalse(facade.invalidateConnection(foreign,
                        PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT));
                assertSame(handle, facade.findCurrentConnection(manager, handler,
                        EnumPacketDirection.SERVERBOUND).orElseThrow());

                facade.onCustomPacketRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(manager,
                        Set.of(PlayerPhysicsProtocol.CHANNEL), "UNREGISTER", Side.SERVER,
                        INetHandlerPlayServer.class));
                assertTrue(manager.isChannelOpen(),
                        "server-side protocol unregistration leaves the physical connection channel open");
                assertTrue(facade.findCurrentConnection(manager, handler,
                        EnumPacketDirection.SERVERBOUND).isEmpty());
                facade.onCustomPacketRegistration(new FMLNetworkEvent.CustomPacketRegistrationEvent<>(manager,
                        Set.of(PlayerPhysicsProtocol.CHANNEL), "REGISTER", Side.SERVER,
                        INetHandlerPlayServer.class));
                assertTrue(facade.bindServerConnected(new FMLNetworkEvent.ServerConnectionFromClientEvent(manager)).isEmpty(),
                        "server-side REGISTER and duplicate connect cannot renew an unregistered lifetime");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void serverConnectRejectsAHandlerThatIsNotInstalledOnItsActualPlayer(@TempDir Path tempDir) {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        UUID playerId = UUID.randomUUID();
        try (BootWorldFixture fixture = BootWorldFixture.open(tempDir.resolve("server"), playerId, false)) {
            EntityPlayerMP player = fixture.serverPlayer(playerId);
            NetHandlerPlayServer handler = player.connection;
            NetworkManager manager = handler.netManager;
            EmbeddedChannel channel = new EmbeddedChannel(manager);
            try {
                player.connection = null;
                FMLNetworkEvent.ServerConnectionFromClientEvent event =
                        new FMLNetworkEvent.ServerConnectionFromClientEvent(manager);

                assertTrue(facade.bindServerConnected(event).isEmpty(),
                        "a current manager handler without the actual player binding is not a connection lifetime");

                player.connection = handler;
                assertTrue(facade.bindServerConnected(new FMLNetworkEvent.ServerConnectionFromClientEvent(manager)).isPresent(),
                        "a rejected unbound event must not leave a terminal record behind");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    private static FMLNetworkEvent.CustomPacketRegistrationEvent<INetHandlerPlayClient> registrationEvent(
            ClientPeer peer, String operation) {
        return new FMLNetworkEvent.CustomPacketRegistrationEvent<>(peer.manager,
                Set.of(PlayerPhysicsProtocol.CHANNEL), operation, Side.CLIENT, INetHandlerPlayClient.class);
    }

    private static boolean awaitBlockedOnManager(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) return true;
            Thread.sleep(1L);
        }
        return thread.getState() == Thread.State.BLOCKED;
    }

    private static final class ClientPeer implements AutoCloseable {
        private final NetworkManager manager;
        private final NetHandlerPlayClient handler;
        private final EmbeddedChannel channel;

        private ClientPeer(NetworkManager manager, NetHandlerPlayClient handler, EmbeddedChannel channel) {
            this.manager = manager;
            this.handler = handler;
            this.channel = channel;
        }

        private static ClientPeer open() {
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient handler = newHandler(manager);
            manager.setNetHandler(handler);
            return new ClientPeer(manager, handler, new EmbeddedChannel(manager));
        }

        private NetHandlerPlayClient newHandler() {
            return newHandler(manager);
        }

        private static NetHandlerPlayClient newHandler(NetworkManager manager) {
            return new NetHandlerPlayClient((Minecraft) null, (GuiScreen) null, manager,
                    new GameProfile(UUID.randomUUID(), "lifecycle-client"));
        }

        private FMLNetworkEvent.ClientConnectedToServerEvent connectedEvent() {
            return new FMLNetworkEvent.ClientConnectedToServerEvent(manager, "lifecycle-test");
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }
}

