package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.AdmissionRefusal;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.AdmissionResult;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.ConnectionHandle;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.DecodeHandle;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.DecodeOutcome;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.DrainHandle;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.RawInboundFrame;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections.ScopeHandle;

class AdmissionTest {
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 7);
    private static final PlayerPhysicalSnapshot.Scope OTHER_SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 3), new UUID(0, 4), 1, 8);
    private static final PlayerBodyShape.Dimensions SIZE =
            new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75);

    @Test
    void captureCopiesBoundedReadableBytesWithoutAdvancingReaderIndex() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            PlayerPhysicsProtocol.Packet expected = statePacket(SCOPE);
            byte[] wire = PlayerPhysicsProtocol.encode(expected);
            ByteBuf body = Unpooled.wrappedBuffer(wire);
            try {
                int readerIndex = body.readerIndex();
                RawInboundFrame raw = facade.captureRawFrame(body);
                assertEquals(readerIndex, body.readerIndex());
                body.setByte(readerIndex, (byte) 0);

                AdmissionResult admitted = facade.admitRawFrame(connection, peer.manager, peer.handler,
                        EnumPacketDirection.CLIENTBOUND, raw, Optional.of(scope));
                assertTrue(admitted.queued());
                DrainHandle drain = admitted.newDrain().orElseThrow();
                DecodeHandle decode = facade.beginNextOrClose(drain).orElseThrow();
                assertEquals(expected, facade.materializePacket(decode).orElseThrow());
                assertTrue(facade.decodeCurrent(decode));
                facade.finishDecode(decode, DecodeOutcome.PROOF_ACCEPTED);
                assertFalse(facade.decodeCurrent(decode));
                assertTrue(facade.beginNextOrClose(drain).isEmpty());
            } finally {
                body.release();
            }
        }
    }

    @Test
    void onlyAConnectedExactHandlerAndDirectionCanChargeAnArrival() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer owner = ClientPeer.open(); ClientPeer unbound = ClientPeer.open()) {
            ConnectionHandle ownerConnection = facade.bindClientConnected(owner.connectedEvent()).orElseThrow();
            ScopeHandle ownerScope = facade.openScopeForOwner(ownerConnection, SCOPE).orElseThrow();
            ConnectionHandle foreignConnection = facade.bindClientConnected(unbound.connectedEvent()).orElseThrow();
            facade.invalidateConnection(foreignConnection,
                    PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT);

            AdmissionResult unboundResult = facade.admitRawFrame(ownerConnection, unbound.manager,
                    unbound.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(ownerScope));
            assertEquals(AdmissionRefusal.NO_CURRENT_CONNECTION, unboundResult.refusal());
            assertEquals(AdmissionRefusal.WRONG_HANDLER, facade.admitRawFrame(ownerConnection,
                    owner.manager, owner.newHandler(), EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(ownerScope)).refusal());
            assertEquals(AdmissionRefusal.WRONG_DIRECTION, facade.admitRawFrame(ownerConnection,
                    owner.manager, owner.handler, EnumPacketDirection.SERVERBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(ownerScope)).refusal());

            for (int i = 0; i < ConnectionDecodeBudget.DEFAULT_DATA_ARRIVALS; i++) {
                assertEquals(AdmissionRefusal.MISSING_SCOPE, facade.admitRawFrame(ownerConnection,
                        owner.manager, owner.handler, EnumPacketDirection.CLIENTBOUND,
                        raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.empty()).refusal());
            }
            assertEquals(AdmissionRefusal.ARRIVAL_COUNT, facade.admitRawFrame(ownerConnection,
                    owner.manager, owner.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, new byte[] {0, 0}), Optional.of(ownerScope)).refusal(),
                    "a denied arrival must be refused before malformed bytes reach the parser");
        }
    }

    @Test
    void retiredOfferIsConnectionBoundButApplyWithoutScopeIsRefused(@TempDir Path tempDir) throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle retired = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            facade.retireScope(retired);

            AdmissionResult offer = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND, raw(facade, PlayerPhysicsProtocol.encode(packet(
                            PlayerPhysicsProtocol.Kind.OFFER, SCOPE))), Optional.of(retired));
            assertTrue(offer.queued(), "clientbound OFFER uses the connection lifetime despite a retired scope");
            DrainHandle drain = offer.newDrain().orElseThrow();
            DecodeHandle decode = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER,
                    facade.materializePacket(decode).orElseThrow().kind());
            facade.finishDecode(decode, DecodeOutcome.PROOF_ACCEPTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }

        UUID playerId = UUID.randomUUID();
        try (BootWorldFixture fixture = BootWorldFixture.open(tempDir.resolve("server"), playerId, false)) {
            EntityPlayerMP player = fixture.serverPlayer(playerId);
            NetworkManager manager = player.connection.netManager;
            EmbeddedChannel channel = new EmbeddedChannel(manager);
            try {
                FMLNetworkEvent.ServerConnectionFromClientEvent connected =
                        new FMLNetworkEvent.ServerConnectionFromClientEvent(manager);
                ConnectionHandle connection = facade.bindServerConnected(connected).orElseThrow();
                AdmissionResult apply = facade.admitRawFrame(connection, manager, player.connection,
                        EnumPacketDirection.SERVERBOUND,
                        raw(facade, PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.APPLY, SCOPE))),
                        Optional.empty());
                assertEquals(AdmissionRefusal.MISSING_SCOPE, apply.refusal());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test
    void foreignAndRetiredScopesCannotQueueAnotherPacketScope() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer first = ClientPeer.open(); ClientPeer second = ClientPeer.open()) {
            ConnectionHandle firstConnection = facade.bindClientConnected(first.connectedEvent()).orElseThrow();
            ConnectionHandle secondConnection = facade.bindClientConnected(second.connectedEvent()).orElseThrow();
            ScopeHandle firstScope = facade.openScopeForOwner(firstConnection, SCOPE).orElseThrow();
            ScopeHandle secondScope = facade.openScopeForOwner(secondConnection, OTHER_SCOPE).orElseThrow();

            assertEquals(AdmissionRefusal.FOREIGN_CONNECTION, facade.admitRawFrame(secondConnection,
                    first.manager, first.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(firstScope)).refusal());
            assertEquals(AdmissionRefusal.FOREIGN_SCOPE, facade.admitRawFrame(firstConnection,
                    first.manager, first.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(OTHER_SCOPE))), Optional.of(secondScope)).refusal());
            assertEquals(AdmissionRefusal.PACKET_SCOPE_MISMATCH, facade.admitRawFrame(firstConnection,
                    first.manager, first.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(OTHER_SCOPE))), Optional.of(firstScope)).refusal());

            facade.retireScope(firstScope);
            assertEquals(AdmissionRefusal.RETIRED_SCOPE, facade.admitRawFrame(firstConnection,
                    first.manager, first.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(firstScope)).refusal());

            facade.invalidateConnection(firstConnection, PlayerPhysicsInboundConnections.InvalidationReason.DISCONNECT);
            assertTrue(facade.openScopeForOwner(firstConnection, SCOPE).isEmpty());
            assertEquals(AdmissionRefusal.NO_CURRENT_CONNECTION, facade.admitRawFrame(firstConnection,
                    first.manager, first.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(firstScope)).refusal());
        }
    }

    @Test
    void oneDrainOwnsAllArrivalsUntilAtomicCloseThenANewPermitIsRequired() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult first = admitState(facade, peer, connection, scope);
            AdmissionResult second = admitState(facade, peer, connection, scope);
            DrainHandle drain = first.newDrain().orElseThrow();
            assertTrue(second.queued());
            assertTrue(second.newDrain().isEmpty(), "an existing permit owns the second scheduling obligation");

            DecodeHandle firstDecode = facade.beginNextOrClose(drain).orElseThrow();
            assertThrows(IllegalStateException.class, () -> facade.beginNextOrClose(drain),
                    "a drain cannot advance while its exact lease is active");
            facade.materializePacket(firstDecode).orElseThrow();
            assertFalse(firstDecode.completed());
            facade.finishDecode(firstDecode, DecodeOutcome.REJECTED);
            assertTrue(firstDecode.completed());

            DecodeHandle secondDecode = facade.beginNextOrClose(drain).orElseThrow();
            facade.materializePacket(secondDecode).orElseThrow();
            assertTrue(firstDecode.completed(), "completion belongs to the old exact task, not the new active task");
            assertFalse(secondDecode.completed());
            assertThrows(IllegalStateException.class, () -> facade.expireDecode(firstDecode));
            assertThrows(IllegalStateException.class, () -> facade.finishDecode(firstDecode, DecodeOutcome.REJECTED));
            assertFalse(secondDecode.completed());
            facade.finishDecode(secondDecode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty(), "empty and close are one budget operation");
            assertThrows(IllegalArgumentException.class, () -> facade.beginNextOrClose(drain));

            AdmissionResult afterClose = admitState(facade, peer, connection, scope);
            DrainHandle nextDrain = afterClose.newDrain().orElseThrow();
            assertNotSame(drain, nextDrain);
            DecodeHandle nextDecode = facade.beginNextOrClose(nextDrain).orElseThrow();
            facade.materializePacket(nextDecode).orElseThrow();
            facade.finishDecode(nextDecode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(nextDrain).isEmpty());
        }
    }

    @Test
    void expirationAfterMaterializationRevokesTheCurrentFence() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult admitted = admitState(facade, peer, connection, scope);
            DrainHandle drain = admitted.newDrain().orElseThrow();
            DecodeHandle decode = facade.beginNextOrClose(drain).orElseThrow();
            facade.materializePacket(decode).orElseThrow();
            assertTrue(facade.decodeCurrent(decode));

            facade.expireDecode(decode);
            assertFalse(facade.decodeCurrent(decode));
            assertThrows(IllegalStateException.class,
                    () -> facade.finishDecode(decode, DecodeOutcome.PROOF_ACCEPTED));
            facade.finishDecode(decode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }
    }

    @Test
    void proofFailureKeepsItsOriginalStackAndClosesItsExactLeaseWithoutRefund() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            byte[] invalidProof = invalidAxisSupportWire(PlayerPhysicsProtocol.encode(freeStatePacket(SCOPE)));
            AdmissionResult admitted = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND, raw(facade, invalidProof), Optional.of(scope));
            DrainHandle drain = admitted.newDrain().orElseThrow();
            DecodeHandle decode = facade.beginNextOrClose(drain).orElseThrow();
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> facade.materializePacket(decode));
            assertTrue(failure.getStackTrace().length > 0);
            assertFalse(facade.decodeCurrent(decode));
            assertTrue(facade.beginNextOrClose(drain).isEmpty(), "failed materialization ended the exact lease");

            AdmissionResult next = admitState(facade, peer, connection, scope);
            DrainHandle nextDrain = next.newDrain().orElseThrow();
            assertNotSame(drain, nextDrain);
            for (int i = 0; i < ConnectionDecodeBudget.DEFAULT_DATA_ARRIVALS - 2; i++) {
                assertEquals(AdmissionRefusal.MISSING_SCOPE, facade.admitRawFrame(connection,
                        peer.manager, peer.handler, EnumPacketDirection.CLIENTBOUND,
                        raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.empty()).refusal());
            }
            assertEquals(AdmissionRefusal.ARRIVAL_COUNT, facade.admitRawFrame(connection,
                    peer.manager, peer.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, new byte[] {0, 0}), Optional.of(scope)).refusal(),
                    "materialization failure releases outstanding work but never refunds arrival bytes/count");
            assertSame(failure, facade.drainSchedulingFailed(nextDrain, failure));
        }
    }

    @Test
    void schedulerFailureReturnsTheSameCauseClosesThePermitAndDoesNotRefundArrival() {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult first = admitState(facade, peer, connection, scope);
            DrainHandle failedDrain = first.newDrain().orElseThrow();
            RuntimeException cause = new RuntimeException("scheduler rejected drain");
            assertSame(cause, facade.drainSchedulingFailed(failedDrain, cause));
            assertThrows(IllegalArgumentException.class, () -> facade.beginNextOrClose(failedDrain));

            AdmissionResult next = admitState(facade, peer, connection, scope);
            DrainHandle nextDrain = next.newDrain().orElseThrow();
            assertNotSame(failedDrain, nextDrain);
            for (int i = 0; i < ConnectionDecodeBudget.DEFAULT_DATA_ARRIVALS - 2; i++) {
                assertEquals(AdmissionRefusal.MISSING_SCOPE, facade.admitRawFrame(connection,
                        peer.manager, peer.handler, EnumPacketDirection.CLIENTBOUND,
                        raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.empty()).refusal());
            }
            assertEquals(AdmissionRefusal.ARRIVAL_COUNT, facade.admitRawFrame(connection,
                    peer.manager, peer.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, new byte[] {0, 0}), Optional.of(scope)).refusal());
            RuntimeException secondCause = new RuntimeException("cleanup pending next drain");
            assertSame(secondCause, facade.drainSchedulingFailed(nextDrain, secondCause));
        }
    }

    @Test
    void ownerFailureClosesOnlyItsExactDrainAndCancelsItsQueuedLeases() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer owner = ClientPeer.open(); ClientPeer unrelated = ClientPeer.open()) {
            ConnectionHandle ownerConnection = facade.bindClientConnected(owner.connectedEvent()).orElseThrow();
            ScopeHandle ownerScope = facade.openScopeForOwner(ownerConnection, SCOPE).orElseThrow();
            ConnectionHandle unrelatedConnection = facade.bindClientConnected(unrelated.connectedEvent()).orElseThrow();
            ScopeHandle unrelatedScope = facade.openScopeForOwner(unrelatedConnection, OTHER_SCOPE).orElseThrow();

            AdmissionResult active = admitState(facade, owner, ownerConnection, ownerScope);
            AdmissionResult queued = admitState(facade, owner, ownerConnection, ownerScope);
            DrainHandle failedDrain = active.newDrain().orElseThrow();
            assertTrue(queued.queued());
            assertTrue(queued.newDrain().isEmpty());
            DecodeHandle activeDecode = facade.beginNextOrClose(failedDrain).orElseThrow();
            assertTrue(facade.materializePacket(activeDecode).isPresent());

            AdmissionResult unrelatedWork = facade.admitRawFrame(unrelatedConnection, unrelated.manager,
                    unrelated.handler, EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(OTHER_SCOPE))),
                    Optional.of(unrelatedScope));
            DrainHandle unrelatedDrain = unrelatedWork.newDrain().orElseThrow();
            IllegalStateException originalCause = new IllegalStateException("owner failed after materialization");
            assertTrue(originalCause.getStackTrace().length > 0);
            assertSame(originalCause, facade.ownerDrainFailed(failedDrain, activeDecode, originalCause));
            assertFalse(facade.decodeCurrent(activeDecode));
            assertThrows(IllegalArgumentException.class, () -> facade.beginNextOrClose(failedDrain));

            AdmissionResult afterFailure = admitState(facade, owner, ownerConnection, ownerScope);
            DrainHandle replacementDrain = afterFailure.newDrain().orElseThrow();
            assertNotSame(failedDrain, replacementDrain);
            DecodeHandle replacementDecode = facade.beginNextOrClose(replacementDrain).orElseThrow();
            assertTrue(facade.materializePacket(replacementDecode).isPresent());
            facade.finishDecode(replacementDecode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(replacementDrain).isEmpty());

            DecodeHandle unrelatedDecode = facade.beginNextOrClose(unrelatedDrain).orElseThrow();
            assertTrue(facade.materializePacket(unrelatedDecode).isPresent());
            facade.finishDecode(unrelatedDecode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(unrelatedDrain).isEmpty());
        }
    }

    @Test
    void foreignFacadeCannotAdvanceOrMaterializeAnotherFacadesHandles() throws Throwable {
        PlayerPhysicsInboundConnections owner = new PlayerPhysicsInboundConnections();
        PlayerPhysicsInboundConnections foreign = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle ownerConnection = owner.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ConnectionHandle foreignConnection = foreign.bindClientConnected(peer.connectedEvent()).orElseThrow();
            assertNotSame(ownerConnection, foreignConnection);

            ScopeHandle ownerScope = owner.openScopeForOwner(ownerConnection, SCOPE).orElseThrow();
            AdmissionResult admitted = admitState(owner, peer, ownerConnection, ownerScope);
            DrainHandle ownerDrain = admitted.newDrain().orElseThrow();
            DecodeHandle ownerDecode = owner.beginNextOrClose(ownerDrain).orElseThrow();

            assertThrows(IllegalArgumentException.class,
                    () -> foreign.beginNextOrClose(ownerDrain));
            assertThrows(IllegalArgumentException.class,
                    () -> foreign.materializePacket(ownerDecode));
            owner.materializePacket(ownerDecode).orElseThrow();
            assertTrue(owner.decodeCurrent(ownerDecode), "foreign use must not consume the owner's active lease");

            owner.finishDecode(ownerDecode, DecodeOutcome.REJECTED);
            assertTrue(owner.beginNextOrClose(ownerDrain).isEmpty());

            ScopeHandle foreignScope = foreign.openScopeForOwner(foreignConnection, SCOPE).orElseThrow();
            AdmissionResult foreignAdmission = admitState(foreign, peer, foreignConnection, foreignScope);
            DrainHandle foreignDrain = foreignAdmission.newDrain().orElseThrow();
            DecodeHandle foreignDecode = foreign.beginNextOrClose(foreignDrain).orElseThrow();
            foreign.materializePacket(foreignDecode).orElseThrow();
            foreign.finishDecode(foreignDecode, DecodeOutcome.REJECTED);
            assertTrue(foreign.beginNextOrClose(foreignDrain).isEmpty());
        }
    }

    @Test
    void rawFrameAlreadyBoundToOneEntryCannotBeReboundToAnother() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer first = ClientPeer.open(); ClientPeer second = ClientPeer.open()) {
            ConnectionHandle firstConnection = facade.bindClientConnected(first.connectedEvent()).orElseThrow();
            ConnectionHandle secondConnection = facade.bindClientConnected(second.connectedEvent()).orElseThrow();
            ScopeHandle firstScope = facade.openScopeForOwner(firstConnection, SCOPE).orElseThrow();
            ScopeHandle secondScope = facade.openScopeForOwner(secondConnection, SCOPE).orElseThrow();
            RawInboundFrame firstEntryFrame = raw(facade,
                    PlayerPhysicsProtocol.encode(statePacket(SCOPE)));

            AdmissionResult firstAdmission = facade.admitRawFrame(firstConnection, first.manager, first.handler,
                    EnumPacketDirection.CLIENTBOUND, firstEntryFrame, Optional.of(firstScope));
            DrainHandle drain = firstAdmission.newDrain().orElseThrow();
            assertEquals(AdmissionRefusal.RAW_FRAME_BOUND_TO_DIFFERENT_ENTRY,
                    facade.admitRawFrame(secondConnection, second.manager, second.handler,
                            EnumPacketDirection.CLIENTBOUND, firstEntryFrame, Optional.of(secondScope)).refusal());

            DecodeHandle decode = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.STATE,
                    facade.materializePacket(decode).orElseThrow().kind());
            facade.finishDecode(decode, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }
    }

    @Test
    void completedDecodeCannotBeReusedAndDoesNotDamageTheNextQueuedTicket() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult first = admitState(facade, peer, connection, scope);
            AdmissionResult second = admitState(facade, peer, connection, scope);
            DrainHandle drain = first.newDrain().orElseThrow();
            assertTrue(second.queued());
            assertTrue(second.newDrain().isEmpty());

            DecodeHandle completed = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.STATE,
                    facade.materializePacket(completed).orElseThrow().kind());
            facade.finishDecode(completed, DecodeOutcome.REJECTED);
            assertFalse(facade.decodeCurrent(completed));
            assertTrue(facade.materializePacket(completed).isEmpty(),
                    "a completed lease cannot materialize again");
            assertThrows(IllegalStateException.class,
                    () -> facade.finishDecode(completed, DecodeOutcome.REJECTED));

            DecodeHandle next = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.STATE,
                    facade.materializePacket(next).orElseThrow().kind(),
                    "rejecting reuse must leave the next queued ticket available");
            facade.finishDecode(next, DecodeOutcome.REJECTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }
    }

    @Test
    void actualNetworkManagerHandlerReplacementInvalidatesOldHandlesAndAdmission() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle oldConnection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(oldConnection, SCOPE).orElseThrow();
            DrainHandle oldDrain = admitState(facade, peer, oldConnection, scope).newDrain().orElseThrow();
            DecodeHandle oldDecode = facade.beginNextOrClose(oldDrain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.STATE,
                    facade.materializePacket(oldDecode).orElseThrow().kind());
            assertTrue(facade.decodeCurrent(oldDecode));

            NetHandlerPlayClient replacement = peer.newHandler();
            synchronized (peer.manager) {
                facade.beforeActualHandlerAssignment(peer.manager, peer.handler, replacement);
                peer.manager.setNetHandler(replacement);
            }
            assertSame(replacement, peer.manager.getNetHandler());
            assertFalse(facade.decodeCurrent(oldDecode), "the old decode fence ends at handler replacement");
            assertThrows(IllegalStateException.class,
                    () -> facade.finishDecode(oldDecode, DecodeOutcome.PROOF_ACCEPTED));
            facade.finishDecode(oldDecode, DecodeOutcome.REJECTED);

            assertTrue(facade.beginNextOrClose(oldDrain).isEmpty(),
                    "the invalidated old drain may close but cannot resurrect its ticket");
            assertThrows(IllegalArgumentException.class,
                    () -> facade.beginNextOrClose(oldDrain), "a closed stale drain cannot be reused");
            assertTrue(facade.findCurrentConnection(peer.manager, replacement,
                    EnumPacketDirection.CLIENTBOUND).isEmpty());
            assertTrue(facade.bindClientConnected(peer.connectedEvent()).isEmpty(),
                    "a terminal manager cannot create a new connection entry");
            assertEquals(AdmissionRefusal.NO_CURRENT_CONNECTION,
                    facade.admitRawFrame(oldConnection, peer.manager, replacement,
                            EnumPacketDirection.CLIENTBOUND,
                            raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(scope)).refusal());
        }
    }

    @Test
    void queuedClientboundOffersSurviveRetiredScopeUnderSingleDrain() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle scope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            byte[] offerWire = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE));
            AdmissionResult first = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND, raw(facade, offerWire), Optional.of(scope));
            AdmissionResult second = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND, raw(facade, offerWire), Optional.of(scope));
            DrainHandle drain = first.newDrain().orElseThrow();
            assertTrue(first.queued());
            assertTrue(second.queued());
            assertTrue(second.newDrain().isEmpty(), "both offers share one scheduled drain obligation");

            facade.retireScope(scope);

            DecodeHandle firstOffer = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER,
                    facade.materializePacket(firstOffer).orElseThrow().kind());
            facade.finishDecode(firstOffer, DecodeOutcome.PROOF_ACCEPTED);

            DecodeHandle secondOffer = facade.beginNextOrClose(drain).orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.OFFER,
                    facade.materializePacket(secondOffer).orElseThrow().kind());
            facade.finishDecode(secondOffer, DecodeOutcome.PROOF_ACCEPTED);
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }
    }

    @Test
    void exactMaterializedOfferCreatesItsOwnScopeProvenanceAndOnlyThatScopeCanFinishState() throws Throwable {
        PlayerPhysicsInboundConnections facade = new PlayerPhysicsInboundConnections();
        try (ClientPeer peer = ClientPeer.open()) {
            ConnectionHandle connection = facade.bindClientConnected(peer.connectedEvent()).orElseThrow();
            ScopeHandle retiredScope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult offerAdmission = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE))),
                    Optional.of(retiredScope));
            facade.retireScope(retiredScope);

            DrainHandle drain = offerAdmission.newDrain().orElseThrow();
            DecodeHandle offerDecode = facade.beginNextOrClose(drain).orElseThrow();
            PlayerPhysicsProtocol.Packet exactOffer = facade.materializePacket(offerDecode).orElseThrow();
            assertTrue(facade.openAcceptedOfferScopeForOwner(offerDecode, OTHER_SCOPE).isEmpty(),
                    "an OFFER cannot mint a scope for a different packet scope");

            ScopeHandle acceptedScope = facade.openAcceptedOfferScopeForOwner(offerDecode, SCOPE).orElseThrow();
            assertTrue(facade.scopeFromAcceptedOffer(acceptedScope, offerDecode, connection, SCOPE));
            assertTrue(facade.openAcceptedOfferScopeForOwner(offerDecode, SCOPE).isEmpty(),
                    "one materialized OFFER can create only one exact scope");
            facade.finishAcceptedOffer(offerDecode, acceptedScope, exactOffer);

            ScopeHandle decoyScope = facade.openScopeForOwner(connection, SCOPE).orElseThrow();
            AdmissionResult stateAdmission = facade.admitRawFrame(connection, peer.manager, peer.handler,
                    EnumPacketDirection.CLIENTBOUND,
                    raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))), Optional.of(acceptedScope));
            assertTrue(stateAdmission.queued());
            DecodeHandle stateDecode = facade.beginNextOrClose(drain).orElseThrow();
            PlayerPhysicsProtocol.Packet state = facade.materializePacket(stateDecode).orElseThrow();
            assertTrue(facade.isCurrentScopeDecode(stateDecode, acceptedScope, state));
            assertThrows(IllegalArgumentException.class,
                    () -> facade.finishInstalledDecode(stateDecode, decoyScope),
                    "equal packet scope values do not substitute for the exact captured token");
            facade.finishInstalledDecode(stateDecode, acceptedScope);
            assertFalse(facade.decodeCurrent(stateDecode));
            assertTrue(facade.beginNextOrClose(drain).isEmpty());
        }
    }

    private static AdmissionResult admitState(PlayerPhysicsInboundConnections facade, ClientPeer peer,
            ConnectionHandle connection, ScopeHandle scope) {
        return facade.admitRawFrame(connection, peer.manager, peer.handler,
                EnumPacketDirection.CLIENTBOUND, raw(facade, PlayerPhysicsProtocol.encode(statePacket(SCOPE))),
                Optional.of(scope));
    }

    private static RawInboundFrame raw(PlayerPhysicsInboundConnections facade, byte[] bytes) {
        ByteBuf body = Unpooled.wrappedBuffer(bytes);
        try {
            return facade.captureRawFrame(body);
        } finally {
            body.release();
        }
    }

    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicsProtocol.Kind kind,
            PlayerPhysicalSnapshot.Scope scope) {
        return new PlayerPhysicsProtocol.Packet(kind, scope, 1, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet statePacket(PlayerPhysicalSnapshot.Scope scope) {
        PlayerBodyPlacement placement = new PlayerBodyPlacement(new Vec3d(2, 7, -3),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        PlayerPhysicalSnapshot snapshot = new PlayerPhysicalSnapshot(scope, 1, placement, new Vec3d(0, 0, 0));
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 1, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet freeStatePacket(PlayerPhysicalSnapshot.Scope scope) {
        FreeFixture fixture = axisFreeFixture();
        PlayerBodyPlacement placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(fixture.geometry.center(),
                fixture.attitude, new PlayerBodyShape.FreeCapsule(SIZE), fixture.geometry,
                fixture.proxy, fixture.transport);
        PlayerPhysicalSnapshot snapshot = new PlayerPhysicalSnapshot(scope, 19, placement, new Vec3d(1, -2, 3));
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 20, 17,
                Optional.empty(), Optional.of(snapshot), Optional.empty());
    }

    private static byte[] invalidAxisSupportWire(byte[] validWire) {
        byte[] invalid = validWire.clone();
        int proofTag = freeSnapshotOffset() + 209;
        ByteBuffer.wrap(invalid).putLong(proofTag + 27,
                Double.doubleToRawLongBits(Math.nextUp(0.125)));
        return invalid;
    }

    private static int freeSnapshotOffset() {
        return 1 + 1 + 16 + 16 + 4 + 8 + 8 + 1 + 1;
    }

    private static FreeFixture axisFreeFixture() {
        Rotation3d attitude = Rotation3d.identity();
        Vec3d reference = new Vec3d(Math.nextDown(0x1.0p24), 1.125, 0.0);
        Vec3d realized = new Vec3d(reference.x(), Math.nextUp(reference.y()), reference.z());
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(reference, attitude, SIZE, sourceWork);
        Vec3d halfLine = new Vec3d(limits.nominalHalfLine().x(), realized.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(limits, 1, 1, 0.125,
                realized, halfLine, sourceWork);
        StoredCapsuleGeometry original = StoredCapsuleGeometry.fromRecipe(
                realized, attitude, SIZE, halfLine, relation);
        Vec3d current = new Vec3d(0x1.0p24 + 0.125, realized.y(), realized.z());
        StoredCapsuleGeometry translated = original.withTranslatedCenter(current);
        Aabb proxy = expanded(translated.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(current.x(), proxy.min().y(), current.z());
        PlayerBodyPlacement placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(current, attitude,
                new PlayerBodyShape.FreeCapsule(SIZE), translated, proxy, transport);
        return new FreeFixture(attitude, translated, proxy, transport, placement);
    }

    private static Aabb expanded(Aabb bounds, double margin) {
        return new Aabb(bounds.min().subtract(new Vec3d(margin, margin, margin)),
                bounds.max().add(new Vec3d(margin, margin, margin)));
    }

    private record FreeFixture(Rotation3d attitude, StoredCapsuleGeometry geometry,
                               Aabb proxy, Vec3d transport, PlayerBodyPlacement placement) { }

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
                    new GameProfile(UUID.randomUUID(), "admission-client"));
        }

        private FMLNetworkEvent.ClientConnectedToServerEvent connectedEvent() {
            return new FMLNetworkEvent.ClientConnectedToServerEvent(manager, "admission-test");
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }
}
