package wi.unigrav.core.player;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.util.text.ITextComponent;
import org.junit.jupiter.api.Test;

import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.player.ConnectionDecodeBudget.RawFrame;
import wi.unigrav.core.player.FreeCapsulePlacementV1.Nominal;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawAabb;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawQuaternion;
import wi.unigrav.core.player.FreeCapsulePlacementV1.RawVec3;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionDecodeBudgetTest {
    @Test void nativeTransferKindsUseActualScopedLeasesAndDirectionalControlAdmission() {
        for (var kind : java.util.List.of(PlayerPhysicsProtocol.Kind.NATIVE, PlayerPhysicsProtocol.Kind.NATIVE_MOTION,
                PlayerPhysicsProtocol.Kind.NATIVE_ACK, PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK,
                PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE, PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK)) {
            PlayerPhysicsProtocol.NativeTransfer payload = switch(kind) {
                case NATIVE -> new PlayerPhysicsProtocol.NativeHandoff(1,0,new Vec3d(260,0,0));
                case NATIVE_MOTION -> new PlayerPhysicsProtocol.NativeMotion(1,0,1,new Vec3d(520,0,0));
                case NATIVE_ACK -> new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(1,0);
                case NATIVE_MOTION_ACK -> new PlayerPhysicsProtocol.NativeMotionAcknowledgement(1,0,1);
                case NATIVE_CORRECTION_COMPLETE -> new PlayerPhysicsProtocol.NativeCorrectionCompleted(2,73);
                case NATIVE_CORRECTION_ACK -> new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(2,73,1);
                default -> throw new AssertionError(kind);
            };
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(kind,SCOPE,1,1,payload);
            var direction = packet.clientbound() ? EnumPacketDirection.CLIENTBOUND : EnumPacketDirection.SERVERBOUND;
            var connection = connection(direction,ConnectionDecodeBudget.Limits.defaults(),new AtomicLong(0));
            var scope = connection.budget.openScope(SCOPE);
            var queued = queue(connection,scope,PlayerPhysicsProtocol.encode(packet));
            var drain = queued.newDrainPermit().orElseThrow();
            var lease = connection.budget.beginNext(drain).orElseThrow();
            assertEquals(packet,lease.materializePacket());
            connection.budget.finish(lease,ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
            connection.budget.finishDrain(drain);
            var wrong = connection(packet.clientbound() ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND,
                    ConnectionDecodeBudget.Limits.defaults(),new AtomicLong(0));
            assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,reserve(wrong,raw(PlayerPhysicsProtocol.encode(packet))).trafficClass());
        }
    }

    @Test void oversizedDecodeEnvelopeRejectsRealFreeRestoreBeforeNumericWork() {
        var fixture = freeFixture();
        var work = new StoredDyadic.Work();
        var budget = new PhysicsDecodeBudget(PlayerPhysicsProtocol.MAX_BYTES + 1, work);
        var failure = assertThrows(IllegalArgumentException.class, () -> PlayerPlacementCodec.restoreFreeV1(
                fixture.wire(), RawQuaternion.from(fixture.attitude()), SIZE,
                RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), budget));
        assertEquals("physical-state packet exceeds the bounded decode envelope", failure.getMessage());
        assertEquals(0, work.units());
        assertEquals(0, budget.rawFieldReads());
        assertEquals(0, budget.quaternionReconstructionCalls());
    }

    @Test void maximumLegalPredictionPacketMaterializesThroughActualConnectionLease() {
        var frame = CardinalFrame.X_POS;
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 9,
                new PlayerBodyPlacement(new Vec3d(2, 7, -3), frame.attitude(),
                        new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62))),
                new Vec3d(1, -2, 3), Optional.of(new PlayerPhysicalSnapshot.Contacts(true, true, true, 42, true)));
        var application = new PlayerPhysicsProtocol.ApplicationId(
                new PlayerPhysicsProtocol.OperationId(51, 8, 12, BoostProtocol.Operation.NONE),
                Optional.of(new PlayerPhysicsProtocol.OperationId(51, 8, 11, BoostProtocol.Operation.ADD)));
        var reference = new PlayerBodyReference.State(
                Optional.of(new PlayerBodyReference.ManualIntent(12, CardinalFrame.Z_POS, new Vec3d(0, 1, 0))),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT, frame, new Vec3d(0, 1, 0)),
                PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED, Optional.of(CardinalFrame.Z_POS),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED));
        var boundary = new PlayerPhysicsProtocol.PredictionBoundary(1, 1, 0, 1, 0, 1, 0, 1, 0,
                PlayerPhysicsProtocol.JumpDecision.none(), PlayerPhysicsProtocol.StepOutcome.APPLIED);
        var seed = new CardinalPredictionSeed.Ready(9, 987654, frame, new Vec3d(1, -32, .125),
                Optional.of(GravityMode.REPLACE), true, true, true, true, 80);
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, SCOPE, 100, 99,
                Optional.of(application), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.of(reference), Optional.empty(), Optional.of(boundary),
                Optional.of(seed), Optional.of(new PlayerPhysicsProtocol.Anchored(
                        new PlayerPhysicsProtocol.PredictionBootstrap(1, 0), 9)), Optional.empty(),
                Optional.of(new PlayerMobilityIntent.State(7, PlayerMobilityIntent.Mode.WALK,
                        PlayerMobilityIntent.Hover.ON, PlayerMobilityIntent.EffectiveMode.WALK,
                        PlayerMobilityIntent.HoverStatus.OFF, PlayerMobilityIntent.Reason.NONE, false, 0)));
        byte[] wire = PlayerPhysicsProtocol.encode(packet);
        assertEquals(542, wire.length, "canonical Cardinal prediction authority remains 542 bytes");
        var connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var scope = connection.budget.openScope(SCOPE);
        var queued = queue(connection, scope, wire);
        var drain = queued.newDrainPermit().orElseThrow();
        var lease = connection.budget.beginNext(drain).orElseThrow();
        assertEquals(packet, lease.materializePacket());
        connection.budget.finish(lease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
        var axis = axisFreeFixture();
        var original = PlayerPlacementCodec.restoreFreeV1(axis.wire(), RawQuaternion.from(axis.attitude()), SIZE,
                RawAabb.from(axis.proxy()), RawVec3.from(axis.transport()),
                new PhysicsDecodeBudget(PlayerPhysicsProtocol.MAX_BYTES, new StoredDyadic.Work()));
        var rawShort = new PlayerBodyShape.Dimensions(.6000000238418579, .5999999642372131, .4);
        var profiledShape = new PlayerBodyShape.FreeCapsule(rawShort,
                new PlayerBodyShape.CollisionProfile(SIZE.width(), SIZE.height()),
                Optional.of(new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1,
                        original.center(), original.eye(), original.attitude())));
        var profiledPlacement = PlayerBodyPlacement.fromAcceptedFreeGeometry(original.center(), original.attitude(),
                profiledShape, original.storedCapsuleGeometry().withEyeMetadata(profiledShape.geometryDimensions()),
                original.bounds(), original.transport(), new StoredDyadic.Work());
        var profiledSnapshot = new PlayerPhysicalSnapshot(SCOPE, 9, profiledPlacement,
                snapshot.worldVelocity(), snapshot.contacts());
        var maximum = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, SCOPE, 101, 99,
                Optional.of(application), Optional.of(profiledSnapshot), Optional.empty(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(boundary),
                Optional.of(new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_SHAPE)),
                Optional.of(new PlayerPhysicsProtocol.Anchored(new PlayerPhysicsProtocol.PredictionBootstrap(1, 0), 9)),
                Optional.empty(), Optional.of(new PlayerMobilityIntent.State(7, PlayerMobilityIntent.Mode.FLIGHT,
                        PlayerMobilityIntent.Hover.OFF, PlayerMobilityIntent.EffectiveMode.FREE,
                        PlayerMobilityIntent.HoverStatus.OFF, PlayerMobilityIntent.Reason.NONE, false, 0)));
        byte[] maximumWire = PlayerPhysicsProtocol.encode(maximum);
        assertEquals(629, maximumWire.length);
        assertEquals(PlayerPhysicsProtocol.MAX_BYTES, maximumWire.length);
        var maximumQueued = queue(connection, scope, maximumWire);
        var maximumDrain = maximumQueued.newDrainPermit().orElseThrow();
        var maximumLease = connection.budget.beginNext(maximumDrain).orElseThrow();
        assertEquals(maximum, maximumLease.materializePacket());
        connection.budget.finish(maximumLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(maximumDrain);
        var oversized = raw(Arrays.copyOf(maximumWire, PlayerPhysicsProtocol.MAX_BYTES + 1));
        assertEquals(PlayerPhysicsProtocol.MAX_BYTES, oversized.copiedBytes());
        var receipt = reserve(connection, oversized);
        assertTrue(receipt.admitted());
        assertThrows(IllegalArgumentException.class, () -> connection.budget.inspectFullFrame(receipt));
        assertEquals(0, connection.budget.snapshot().outstandingData());
    }

    @Test void inputLeasesShareWindowNumericWorkAcrossRawFailureAndExactQuaternionRestore() {
        var connection = connection(EnumPacketDirection.SERVERBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var scope = connection.budget.openScope(SCOPE);
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5, 1, PlayerPhysicsProtocol.BodyReferenceIntent.none(),
                Optional.of(new Rotation3d(.1, .2, .3, .4)));
        var expected = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 1, 1,
                Optional.empty(), Optional.empty(), Optional.of(controls));
        byte[] good = PlayerPhysicsProtocol.encode(expected);
        byte[] bad = good.clone();
        ByteBuffer.wrap(bad).putLong(63, Long.MIN_VALUE);
        var first = queue(connection, scope, bad);
        var second = queue(connection, scope, good);
        var drain = first.newDrainPermit().orElseThrow();
        var failedLease = connection.budget.beginNext(drain).orElseThrow();
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA, failedLease.ticket().trafficClass());
        assertTrue(failedLease.requiresNumericProof());
        var work = failedLease.numericWork();
        assertEquals(0, work.units());
        var cause = assertThrows(IllegalArgumentException.class, failedLease::materializePacket);
        assertEquals(128, work.units());
        connection.budget.failed(failedLease, ConnectionDecodeBudget.DecodeFailure.DECODE_REJECTED, cause);
        assertSame(cause, failedLease.failureCause().orElseThrow());
        var validLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(work, validLease.numericWork());
        assertEquals(expected, validLease.materializePacket());
        assertEquals(256, work.units());
        connection.budget.finish(validLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
    }
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 7);
    private static final PlayerBodyShape.Dimensions SIZE =
            new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75);

    @Test
    void realRawHeadersSelectSeparateArrivalAndBodyByteLedgers() {
        AtomicLong now = new AtomicLong(10);
        int offerBytes = frame(packet(PlayerPhysicsProtocol.Kind.OFFER)).actualBodyBytes();
        var limits = new ConnectionDecodeBudget.Limits(4, 4, 201, offerBytes,
                2, 2, ConnectionDecodeBudget.DEFAULT_QUEUED_TTL_NANOS);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND, limits, now);

        var data = raw(new byte[200]);
        var dataReceipt = connection.budget.reserveIngress(connection.manager, connection.handler, data);
        var control = frame(packet(PlayerPhysicsProtocol.Kind.OFFER));
        var controlReceipt = connection.budget.reserveIngress(connection.manager, connection.handler, control);

        assertTrue(dataReceipt.admitted());
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA, dataReceipt.trafficClass());
        assertTrue(controlReceipt.admitted());
        assertEquals(ConnectionDecodeBudget.TrafficClass.CONTROL, controlReceipt.trafficClass());
        assertSame(control, controlReceipt.frame(), "ingress keeps the exact bounded RawFrame identity");

        var firstSnapshot = connection.budget.snapshot();
        assertEquals(1, firstSnapshot.dataArrivals());
        assertEquals(200, firstSnapshot.dataBodyBytes());
        assertEquals(1, firstSnapshot.controlArrivals());
        assertEquals(offerBytes, firstSnapshot.controlBodyBytes(),
                "the two classes each own a separate byte ledger");

        var secondData = connection.budget.reserveIngress(connection.manager, connection.handler, raw(new byte[1]));
        assertTrue(secondData.admitted(), "DATA count remains below its independent limit");
        var thirdData = connection.budget.reserveIngress(connection.manager, connection.handler, raw(new byte[200]));
        assertFalse(thirdData.admitted());
        assertEquals(ConnectionDecodeBudget.IngressRejection.ARRIVAL_BYTES, thirdData.rejection());
        var secondControl = connection.budget.reserveIngress(connection.manager, connection.handler,
                frame(packet(PlayerPhysicsProtocol.Kind.OFFER)));
        assertFalse(secondControl.admitted());
        assertEquals(ConnectionDecodeBudget.IngressRejection.ARRIVAL_BYTES, secondControl.rejection());

        var charged = connection.budget.snapshot();
        assertEquals(3, charged.dataArrivals(), "quota-denied DATA still pays its arrival count");
        assertEquals(401, charged.dataBodyBytes(), "quota-denied DATA still pays its actual body bytes");
        assertEquals(2, charged.controlArrivals(), "CONTROL is not charged to DATA count");
        assertEquals(offerBytes * 2L, charged.controlBodyBytes(),
                "CONTROL is not charged to DATA bytes and is not refunded");
    }

    @Test
    void emptyShortUnknownOversizedAndWrongDirectionHeadersFallBackToData() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));

        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, raw(new byte[0])).trafficClass());
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, raw(new byte[]{(byte) PlayerPhysicsProtocol.VERSION})).trafficClass());
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, raw(new byte[]{99, 0})).trafficClass());
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, raw(new byte[]{(byte) PlayerPhysicsProtocol.VERSION, (byte) 127})).trafficClass());

        RawFrame shortOffer = raw(new byte[]{(byte) PlayerPhysicsProtocol.VERSION,
                (byte) PlayerPhysicsProtocol.Kind.OFFER.ordinal()});
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, shortOffer).trafficClass(),
                "a correctly directed CONTROL kind without its actual minimum frame body is short DATA");

        byte[] oversizedBytes = new byte[ConnectionDecodeBudget.MAX_FRAME_BYTES + 257];
        oversizedBytes[0] = (byte) PlayerPhysicsProtocol.VERSION;
        oversizedBytes[1] = (byte) PlayerPhysicsProtocol.Kind.OFFER.ordinal();
        RawFrame oversized = raw(oversizedBytes);
        assertEquals(ConnectionDecodeBudget.MAX_FRAME_BYTES, oversized.copiedBytes(),
                "oversized input copies at most the bounded parser envelope");
        assertEquals(oversizedBytes.length, oversized.actualBodyBytes());
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA, reserve(connection, oversized).trafficClass());

        RawFrame wrongDirection = frame(packet(PlayerPhysicsProtocol.Kind.APPLY));
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA,
                reserve(connection, wrongDirection).trafficClass());

        var totals = connection.budget.snapshot();
        assertEquals(7, totals.dataArrivals());
        assertEquals(0, totals.controlArrivals());
    }

    @Test
    void rawControlHeaderCannotReserveProtectedSlotUntilRealFullFrameDecodeSucceeds() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var scope = connection.budget.openScope(SCOPE);
        byte[] validOffer = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OFFER));
        RawFrame malformed = raw(Arrays.copyOf(validOffer, validOffer.length + 1));

        var ingress = connection.budget.reserveIngress(connection.handler, malformed);
        assertEquals(ConnectionDecodeBudget.TrafficClass.CONTROL, ingress.trafficClass());
        assertThrows(IllegalArgumentException.class,
                () -> connection.budget.inspectFullFrame(ingress),
                "the actual protocol parser rejects a trailing byte before queue reservation");
        assertEquals(1, connection.budget.snapshot().controlArrivals());
        assertEquals(malformed.actualBodyBytes(), connection.budget.snapshot().controlBodyBytes());
        assertEquals(0, connection.budget.snapshot().queuedControl(),
                "structural failure remains charged but never receives the protected queue slot");

        var application = new PlayerPhysicsProtocol.ApplicationId(
                new PlayerPhysicsProtocol.OperationId(51, 8, 12, BoostProtocol.Operation.NONE),
                Optional.empty());
        var applyWithApplication = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY,
                SCOPE, 1, 1, Optional.of(application), Optional.empty(), Optional.empty());
        byte[] wrongApplicationKind = PlayerPhysicsProtocol.encode(applyWithApplication);
        wrongApplicationKind[1] = (byte) PlayerPhysicsProtocol.Kind.OFFER.ordinal();
        RawFrame mismatched = raw(wrongApplicationKind);
        var mismatchedIngress = connection.budget.reserveIngress(connection.handler, mismatched);
        assertEquals(ConnectionDecodeBudget.TrafficClass.CONTROL, mismatchedIngress.trafficClass());
        assertThrows(IllegalArgumentException.class,
                () -> connection.budget.inspectFullFrame(mismatchedIngress),
                "a CONTROL header cannot hide an application payload that is invalid for OFFER");
        assertEquals(0, connection.budget.snapshot().queuedControl());
    }

    @Test
    void structurallyValidFreeFrameWaitsForItsLeaseBeforeRestoringAnInvalidProof() {
        FreeFixture fixture = axisFreeFixture();
        assertTrue(fixture.wire().proof() instanceof FreeCapsulePlacementV1.AxisPlane,
                "the test mutates a real axis-plane witness rather than inventing packet proof data");
        byte[] invalidProof = invalidAxisSupportWire(
                PlayerPhysicsProtocol.encode(freeStatePacket(SCOPE, fixture)));
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);
        RawFrame raw = raw(invalidProof);

        var ingress = connection.budget.reserveIngress(connection.handler, raw);
        assertTrue(ingress.admitted());
        var inspection = connection.budget.inspectFullFrame(ingress);
        assertEquals(PlayerPhysicsProtocol.Kind.STATE, inspection.kind());
        assertEquals(SCOPE, inspection.scope());
        assertEquals(invalidProof.length, inspection.encodedPacketBytes());
        assertTrue(inspection.hasFreePlacement());
        assertTrue(inspection.requiresNumericProof());
        assertFalse(inspection.noFreeControlRecovery());

        var reservation = connection.budget.reserveQueue(inspection, scope);
        assertTrue(reservation.queued(),
                "a structurally complete Free frame is admitted before its numeric proof is restored");
        ConnectionDecodeBudget.DrainPermit drain = reservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease lease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work work = lease.numericWork();
        assertEquals(0, work.units(), "inspection and queue admission perform no Free proof work");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                lease::materializePacket,
                "the active lease performs the real Free proof restore and rejects the corrupted witness");
        assertTrue(work.units() > 0, "the rejected proof keeps its real numeric charges");
        assertTrue(connection.budget.snapshot().activeDecode(),
                "materialization failure remains active until its caller records task exit");
        assertEquals(1, connection.budget.snapshot().outstandingData());
        assertThrows(IllegalStateException.class, lease::materializePacket,
                "a failed first attempt still consumes this lease's single materialization start");

        connection.budget.failed(lease, ConnectionDecodeBudget.DecodeFailure.DECODE_REJECTED, failure);
        assertSame(failure, lease.failureCause().orElseThrow());
        assertSame(failure, reservation.ticket().orElseThrow().failureCause().orElseThrow());
        connection.budget.finishDrain(drain);
    }

    @Test
    void realFreeLeaseMaterializationKeepsOneWindowWorkAcrossTwoFailuresAndSuccess() {
        FreeFixture fixture = axisFreeFixture();
        PlayerPhysicsProtocol.Packet expected = freeStatePacket(SCOPE, fixture);
        byte[] validWire = PlayerPhysicsProtocol.encode(expected);
        byte[] invalidSupport = invalidAxisSupportWire(validWire);
        byte[] invalidHalfLine = invalidHalfLineWire(validWire);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);

        var first = queue(connection, scope, invalidSupport);
        var second = queue(connection, scope, invalidHalfLine);
        var third = queue(connection, scope, validWire);
        assertTrue(first.queued());
        assertTrue(second.queued());
        assertTrue(third.queued());
        ConnectionDecodeBudget.DrainPermit drain = first.newDrainPermit().orElseThrow();

        ConnectionDecodeBudget.DecodeLease firstLease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work sharedWork = firstLease.numericWork();
        assertEquals(0, sharedWork.units());
        IllegalArgumentException firstFailure = assertThrows(IllegalArgumentException.class,
                firstLease::materializePacket);
        long afterFirstFailure = sharedWork.units();
        assertTrue(afterFirstFailure > 0);
        connection.budget.failed(firstLease, ConnectionDecodeBudget.DecodeFailure.DECODE_REJECTED, firstFailure);
        assertSame(firstFailure, firstLease.failureCause().orElseThrow());

        ConnectionDecodeBudget.DecodeLease secondLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(sharedWork, secondLease.numericWork(), "all leases in this window share the captured Work");
        IllegalArgumentException secondFailure = assertThrows(IllegalArgumentException.class,
                secondLease::materializePacket);
        long afterSecondFailure = sharedWork.units();
        assertTrue(afterSecondFailure > afterFirstFailure,
                "failed restores are cumulative and cannot refund work to the window");
        connection.budget.failed(secondLease, ConnectionDecodeBudget.DecodeFailure.DECODE_REJECTED, secondFailure);
        assertSame(secondFailure, secondLease.failureCause().orElseThrow());

        ConnectionDecodeBudget.DecodeLease validLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(sharedWork, validLease.numericWork());
        PlayerPhysicsProtocol.Packet restored = validLease.materializePacket();
        assertEquals(expected, restored, "the exact stored inspection reaches the real protocol materializer");
        assertTrue(sharedWork.units() > afterSecondFailure,
                "the valid restore continues charging the same window Work after both failures");
        connection.budget.finish(validLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
    }

    @Test
    void realLeaseMaterializationPropagatesTypedNumericExhaustionUnchanged() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);
        var reservation = queue(connection, scope,
                PlayerPhysicsProtocol.encode(freeStatePacket(SCOPE, axisFreeFixture())));
        ConnectionDecodeBudget.DrainPermit drain = reservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease lease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work work = lease.numericWork();
        work.charge(ConnectionDecodeBudget.NUMERIC_WORK_LIMIT - 180L);
        long beforeRestore = work.units();

        NumericWorkExhaustedException failure = assertThrows(NumericWorkExhaustedException.class,
                lease::materializePacket,
                "the real lease materializer must preserve numeric exhaustion from Free proof restoration");
        assertEquals(ConnectionDecodeBudget.NUMERIC_WORK_LIMIT, failure.limit());
        assertEquals(work.units(), failure.used());
        assertTrue(work.units() > beforeRestore,
                "the failed real restore keeps the work it consumed before exhaustion");
        connection.budget.failed(lease, ConnectionDecodeBudget.DecodeFailure.NUMERIC_WORK_EXHAUSTED, failure);
        assertSame(failure, lease.failureCause().orElseThrow());
        assertSame(failure, reservation.ticket().orElseThrow().failureCause().orElseThrow());
        connection.budget.finishDrain(drain);
    }

    @Test
    void cardinalFramesAndOfferApplyNativeRecoveryControlsNeedNoFreeProofWork() {
        TestConnection cardinalConnection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken cardinalScope = cardinalConnection.budget.openScope(SCOPE);
        PlayerPhysicsProtocol.Packet cardinal = statePacket();
        var cardinalIngress = cardinalConnection.budget.reserveIngress(
                cardinalConnection.handler, frame(cardinal));
        var cardinalInspection = cardinalConnection.budget.inspectFullFrame(cardinalIngress);
        assertFalse(cardinalInspection.hasFreePlacement(), "Cardinal is not a Free proof frame");
        assertFalse(cardinalInspection.requiresNumericProof());
        assertFalse(cardinalInspection.noFreeControlRecovery());
        var cardinalReservation = cardinalConnection.budget.reserveQueue(cardinalInspection, cardinalScope);
        var cardinalDrain = cardinalReservation.newDrainPermit().orElseThrow();
        var cardinalLease = cardinalConnection.budget.beginNext(cardinalDrain).orElseThrow();
        assertFalse(cardinalLease.requiresNumericProof());
        assertThrows(IllegalStateException.class, cardinalLease::numericWork);
        assertEquals(cardinal, cardinalLease.materializePacket());
        cardinalConnection.budget.finish(cardinalLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        cardinalConnection.budget.finishDrain(cardinalDrain);

        for (PlayerPhysicsProtocol.Kind kind : List.of(PlayerPhysicsProtocol.Kind.OFFER,
                PlayerPhysicsProtocol.Kind.APPLY, PlayerPhysicsProtocol.Kind.NATIVE, PlayerPhysicsProtocol.Kind.RECOVER)) {
            EnumPacketDirection direction = kind == PlayerPhysicsProtocol.Kind.APPLY || kind == PlayerPhysicsProtocol.Kind.RECOVER
                    ? EnumPacketDirection.SERVERBOUND : EnumPacketDirection.CLIENTBOUND;
            TestConnection connection = connection(direction,
                    ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
            ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);
            PlayerPhysicsProtocol.Packet control = packet(kind);
            var ingress = connection.budget.reserveIngress(connection.handler, frame(control));
            assertEquals(ConnectionDecodeBudget.TrafficClass.CONTROL, ingress.trafficClass());
            var inspection = connection.budget.inspectFullFrame(ingress);
            assertEquals(kind, inspection.kind());
            assertEquals(SCOPE, inspection.scope());
            assertTrue(inspection.noFreeControlRecovery());
            assertFalse(inspection.hasFreePlacement());
            assertFalse(inspection.requiresNumericProof());

            var reservation = connection.budget.reserveQueue(inspection, scope);
            assertTrue(reservation.queued());
            var drain = reservation.newDrainPermit().orElseThrow();
            var lease = connection.budget.beginNext(drain).orElseThrow();
            assertFalse(lease.requiresNumericProof());
            assertEquals(control, lease.materializePacket());
            connection.budget.finish(lease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
            connection.budget.finishDrain(drain);
        }
    }

    @Test
    void materializationStartRejectsForeignRetiredExpiredCompletedAndRepeatedLeases() {
        TestConnection owner = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken ownerScope = owner.budget.openScope(SCOPE);
        var ownerReservation = queue(owner, ownerScope, freeStatePacket());
        ConnectionDecodeBudget.DrainPermit ownerDrain = ownerReservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease ownerLease = owner.budget.beginNext(ownerDrain).orElseThrow();
        TestConnection foreign = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        assertThrows(IllegalStateException.class, () -> foreign.budget.materializePacket(ownerLease));
        assertFalse(ownerLease.completed(), "a foreign owner cannot consume the current lease start");
        assertEquals(PlayerPhysicsProtocol.Kind.STATE, ownerLease.materializePacket().kind());
        assertThrows(IllegalStateException.class, ownerLease::materializePacket,
                "one lease cannot begin materialization twice");
        owner.budget.finish(ownerLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        assertThrows(IllegalStateException.class, () -> owner.budget.materializePacket(ownerLease),
                "a completed lease cannot begin materialization again");
        owner.budget.finishDrain(ownerDrain);

        TestConnection retired = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken retiredScope = retired.budget.openScope(SCOPE);
        var retiredReservation = queue(retired, retiredScope, freeStatePacket());
        ConnectionDecodeBudget.DrainPermit retiredDrain = retiredReservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease retiredLease = retired.budget.beginNext(retiredDrain).orElseThrow();
        retired.budget.retireScope(retiredScope);
        IllegalStateException retiredFailure = assertThrows(IllegalStateException.class,
                retiredLease::materializePacket);
        assertEquals("decode lease cannot start after its lifetime fence", retiredFailure.getMessage());
        assertFalse(retiredLease.installable());
        assertTrue(retired.budget.snapshot().activeDecode(),
                "retirement fences a not-yet-started decode without releasing its active slot");
        retired.budget.finish(retiredLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        retired.budget.finishDrain(retiredDrain);
        assertFalse(retired.budget.snapshot().activeDecode());

        TestConnection expired = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken expiredScope = expired.budget.openScope(SCOPE);
        var expiredReservation = queue(expired, expiredScope, freeStatePacket());
        ConnectionDecodeBudget.DrainPermit expiredDrain = expiredReservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease expiredLease = expired.budget.beginNext(expiredDrain).orElseThrow();
        expired.budget.expire(expiredLease);
        assertThrows(IllegalStateException.class, expiredLease::materializePacket);
        assertTrue(expired.budget.snapshot().activeDecode(),
                "expiry does not release the active task before its caller reaches task exit");
        expired.budget.finish(expiredLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        expired.budget.finishDrain(expiredDrain);
    }

    @Test
    void snapshotLeaseWorkRejectsRealFreeRestoreCallerWithoutBlockingControlRecovery() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);

        RawFrame stateFrame = frame(freeStatePacket());
        var stateIngress = connection.budget.reserveIngress(connection.handler, stateFrame);
        var stateInspection = connection.budget.inspectFullFrame(stateIngress);
        assertTrue(stateInspection.hasFreePlacement());
        assertTrue(stateInspection.requiresNumericProof(),
                "only an inspected Free placement frame reserves proof work");
        var dataReservation = connection.budget.reserveQueue(stateInspection, scope);
        assertTrue(dataReservation.queued());
        assertSame(stateFrame, dataReservation.ticket().orElseThrow().frame());

        ConnectionDecodeBudget.DrainPermit drain = dataReservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease dataLease =
                connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work oldWindowWork = dataLease.numericWork();
        oldWindowWork.charge(ConnectionDecodeBudget.NUMERIC_WORK_LIMIT - 180L);
        long beforeRestore = oldWindowWork.units();

        // v6 has no Free wire packet. This is the real existing restoreFreeV1 caller
        // using the lease's Work; it does not claim to exercise Free wire admission.
        FreeFixture fixture = freeFixture();
        PhysicsDecodeBudget restoreBudget = new PhysicsDecodeBudget(
                ConnectionDecodeBudget.MAX_FRAME_BYTES, oldWindowWork);
        NumericWorkExhaustedException exhausted = assertThrows(NumericWorkExhaustedException.class,
                () -> PlayerPlacementCodec.restoreFreeV1(fixture.wire(), RawQuaternion.from(fixture.attitude()),
                        SIZE, RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), restoreBudget));
        assertEquals(ConnectionDecodeBudget.NUMERIC_WORK_LIMIT, exhausted.limit());
        assertEquals(oldWindowWork.units(), exhausted.used());
        assertTrue(oldWindowWork.units() > beforeRestore,
                "real Free placement restore retains the partial Work charged before exhaustion");
        assertThrows(NumericWorkExhaustedException.class,
                () -> oldWindowWork.charge(exhausted.requested()),
                "the failed proof charge remains exhausted until this Work leaves its window");
        assertEquals(1, restoreBudget.quaternionReconstructionCalls());
        connection.budget.failed(dataLease,
                ConnectionDecodeBudget.DecodeFailure.NUMERIC_WORK_EXHAUSTED, exhausted);
        assertSame(exhausted, dataLease.failureCause().orElseThrow());
        assertSame(exhausted, dataReservation.ticket().orElseThrow().failureCause().orElseThrow());

        RawFrame offerFrame = frame(packet(PlayerPhysicsProtocol.Kind.OFFER));
        var offerIngress = connection.budget.reserveIngress(connection.handler, offerFrame);
        assertEquals(ConnectionDecodeBudget.TrafficClass.CONTROL, offerIngress.trafficClass());
        var offerInspection = connection.budget.inspectFullFrame(offerIngress);
        assertTrue(offerInspection.noFreeControlRecovery());
        assertFalse(offerInspection.requiresNumericProof());
        var controlReservation = connection.budget.reserveQueue(offerInspection, scope);
        assertTrue(controlReservation.queued(),
                "exhausted snapshot Work does not globally disable a no-Free CONTROL recovery");
        assertTrue(controlReservation.newDrainPermit().isEmpty(),
                "the existing scheduled drain owns both ticket lifecycles");

        ConnectionDecodeBudget.DecodeLease controlLease = connection.budget.beginNext(drain).orElseThrow();
        assertFalse(controlLease.requiresNumericProof());
        assertThrows(IllegalStateException.class, controlLease::numericWork);
        assertTrue(controlLease.installable());
        connection.budget.finish(controlLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
        assertEquals(0, connection.budget.snapshot().queuedControl());
        assertFalse(connection.budget.snapshot().activeDecode());
    }

    @Test
    void halfOpenWindowAndMultiWindowJumpRotateOnlyFutureLeaseWork() {
        AtomicLong now = new AtomicLong(100);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), now);
        var scope = connection.budget.openScope(SCOPE);
        var firstReservation = queue(connection, scope, freeStatePacket());
        var drain = firstReservation.newDrainPermit().orElseThrow();
        var oldLease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work oldWork = oldLease.numericWork();
        oldWork.charge(17);
        long oldUnits = oldWork.units();

        now.set(100 + ConnectionDecodeBudget.WINDOW_NANOS - 1);
        assertFalse(connection.budget.rollWindow(), "the window is half-open at its one-second boundary");
        now.incrementAndGet();
        assertTrue(connection.budget.rollWindow(), "exactly one second starts a new window");
        assertEquals(1, connection.budget.snapshot().windowSequence());
        assertEquals(oldUnits, oldWork.units(), "rolling never resets work already held by a lease");

        now.set(8_000_000_100L);
        assertTrue(connection.budget.rollWindow());
        var afterJump = connection.budget.snapshot();
        assertEquals(2, afterJump.windowSequence(),
                "a multi-window jump installs one current window without banking skipped windows");
        assertEquals(now.get(), afterJump.windowStartedAtNanos());

        now.set(8_000_000_100L + ConnectionDecodeBudget.WINDOW_NANOS - 100L);
        var queuedAcrossWindows = queue(connection, scope, freeStatePacket());
        assertTrue(queuedAcrossWindows.queued());
        assertEquals(1, connection.budget.snapshot().queuedData());
        assertEquals(2, connection.budget.snapshot().outstandingData());
        now.set(8_000_000_100L + ConnectionDecodeBudget.WINDOW_NANOS);
        assertTrue(connection.budget.rollWindow(), "a queued ticket remains valid across its next window boundary");
        assertEquals(1, connection.budget.snapshot().queuedData(),
                "window rollover does not cancel or reset queue occupancy");
        assertEquals(2, connection.budget.snapshot().outstandingData(),
                "window rollover does not release queued or active outstanding slots");

        connection.budget.finish(oldLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        assertEquals(1, connection.budget.snapshot().outstandingData(),
                "only actual completion releases the old active ticket's outstanding slot");
        assertTrue(queuedAcrossWindows.newDrainPermit().isEmpty());
        var nextLease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work newWork = nextLease.numericWork();
        assertNotSame(oldWork, newWork);
        long newWindow = newWork.units();
        FreeFixture fixture = freeFixture();
        PhysicsDecodeBudget restoreBudget = new PhysicsDecodeBudget(
                ConnectionDecodeBudget.MAX_FRAME_BYTES, newWork);
        PlayerBodyPlacement restored = PlayerPlacementCodec.restoreFreeV1(
                fixture.wire(), RawQuaternion.from(fixture.attitude()), SIZE,
                RawAabb.from(fixture.proxy()), RawVec3.from(fixture.transport()), restoreBudget);
        assertTrue(restored.storedCapsuleGeometry().matchesRecipe(
                fixture.geometry().center(), fixture.attitude(), SIZE));
        assertEquals(fixture.geometry().capsule(), restored.capsule());
        assertTrue(newWork.units() > newWindow);
        assertEquals(oldUnits, oldWork.units(), "new leases cannot refund or reset an older window Work");
        connection.budget.finish(nextLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
        assertEquals(0, connection.budget.snapshot().outstandingData());
    }

    @Test
    void scopeRetirementCancelsQueuedOnceButLeavesActiveMathUntilItsExit() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var scope = connection.budget.openScope(SCOPE);
        var activeReservation = queue(connection, scope, freeStatePacket());
        var drain = activeReservation.newDrainPermit().orElseThrow();
        var queuedReservation = queue(connection, scope, statePacket());
        var activeLease = connection.budget.beginNext(drain).orElseThrow();
        var activeWork = activeLease.numericWork();
        activeWork.charge(7);
        long activeUnits = activeWork.units();

        connection.budget.retireScope(scope);
        connection.budget.retireScope(scope);
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                queuedReservation.ticket().orElseThrow().state());
        assertEquals(0, connection.budget.snapshot().queuedData());
        assertEquals(1, connection.budget.snapshot().outstandingData(),
                "retiring a scope cancels queued occupancy but keeps the active ticket outstanding");
        assertTrue(connection.budget.snapshot().activeDecode());
        assertFalse(activeLease.installable(),
                "scope retirement immediately removes installation permission");
        assertEquals(activeUnits, activeWork.units(),
                "queued cancellation does not reset or release active numeric work");

        assertTrue(connection.budget.beginNext(drain).isEmpty(),
                "the active math still occupies the single decoder slot");
        connection.budget.finish(activeLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        assertFalse(connection.budget.snapshot().activeDecode());
        assertEquals(0, connection.budget.snapshot().outstandingData());
        assertTrue(connection.budget.beginNext(drain).isEmpty(),
                "one scheduled drain consumes cancelled tickets as no-ops");
        connection.budget.finishDrain(drain);
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                queuedReservation.ticket().orElseThrow().state());

        PlayerPhysicalSnapshot.Scope nextWorld =
                new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 1, 8);
        var nextScope = connection.budget.openScope(nextWorld);
        var nextReservation = queue(connection, nextScope, freeStatePacket(nextWorld));
        assertTrue(nextReservation.queued());
        assertEquals(3, connection.budget.snapshot().dataArrivals(),
                "opening a new world scope preserves this connection's existing ingress ledger");
        assertEquals(activeUnits, activeWork.units(), "scope retirement does not clear prior Work");
        var nextDrain = nextReservation.newDrainPermit().orElseThrow();
        var nextLease = connection.budget.beginNext(nextDrain).orElseThrow();
        assertSame(activeWork, nextLease.numericWork(),
                "a new scope does not silently create a fresh numeric window Work");
        connection.budget.finish(nextLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        connection.budget.finishDrain(nextDrain);
    }

    @Test
    void actualOutstandingCapsIncludeActiveAndQueuedTicketsPerBucket() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var scope = connection.budget.openScope(SCOPE);
        List<ConnectionDecodeBudget.Ticket> tickets = new ArrayList<>();
        var firstData = queue(connection, scope, freeStatePacket());
        assertTrue(firstData.queued());
        ConnectionDecodeBudget.Ticket activeTicket = firstData.ticket().orElseThrow();
        ConnectionDecodeBudget.DrainPermit drain = firstData.newDrainPermit().orElseThrow();
        var activeLease = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work activeWork = activeLease.numericWork();
        activeWork.charge(13);

        for (int i = 1; i < ConnectionDecodeBudget.DEFAULT_OUTSTANDING_DATA; i++) {
            var reservation = queue(connection, scope, statePacket());
            assertTrue(reservation.queued());
            tickets.add(reservation.ticket().orElseThrow());
        }
        var overData = queue(connection, scope, statePacket());
        assertFalse(overData.queued());
        assertEquals(ConnectionDecodeBudget.QueueRejection.OUTSTANDING_LIMIT, overData.rejection());
        assertEquals(ConnectionDecodeBudget.TicketState.DECODING, activeTicket.state());
        assertTrue(connection.budget.beginNext(drain).isEmpty(),
                "a second lease cannot start while active math owns the decoder slot");

        for (int i = 0; i < ConnectionDecodeBudget.DEFAULT_OUTSTANDING_CONTROL; i++) {
            var reservation = queue(connection, scope, packet(PlayerPhysicsProtocol.Kind.OFFER));
            assertTrue(reservation.queued(), "CONTROL retains its own protected queue capacity");
            tickets.add(reservation.ticket().orElseThrow());
            assertTrue(reservation.newDrainPermit().isEmpty());
        }
        var overControl = queue(connection, scope, packet(PlayerPhysicsProtocol.Kind.OFFER));
        assertFalse(overControl.queued());
        assertEquals(ConnectionDecodeBudget.QueueRejection.OUTSTANDING_LIMIT, overControl.rejection());
        assertEquals(ConnectionDecodeBudget.DEFAULT_OUTSTANDING_DATA - 1,
                connection.budget.snapshot().queuedData());
        assertEquals(ConnectionDecodeBudget.DEFAULT_OUTSTANDING_CONTROL,
                connection.budget.snapshot().queuedControl());
        assertEquals(ConnectionDecodeBudget.DEFAULT_OUTSTANDING_DATA,
                connection.budget.snapshot().outstandingData(),
                "one active plus seven queued DATA tickets exhausts the shared DATA outstanding cap");
        assertEquals(ConnectionDecodeBudget.DEFAULT_OUTSTANDING_CONTROL,
                connection.budget.snapshot().outstandingControl(),
                "CONTROL has a separate outstanding cap while DATA math is active");

        RuntimeException schedulerFailure = new RuntimeException("scheduler rejected the drain");
        assertSame(schedulerFailure, connection.budget.drainFailed(drain, schedulerFailure));
        assertTrue(drain.finished());
        for (ConnectionDecodeBudget.Ticket ticket : tickets) {
            assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED, ticket.state());
        }
        assertEquals(ConnectionDecodeBudget.TicketState.DECODING, activeTicket.state(),
                "scheduler failure cancels queued tickets without fabricating active-task completion");
        assertFalse(activeLease.installable());
        assertEquals(0, connection.budget.snapshot().queuedData());
        assertEquals(0, connection.budget.snapshot().queuedControl());
        assertEquals(1, connection.budget.snapshot().outstandingData(),
                "active DATA outstanding capacity remains held until its real failure boundary");
        assertEquals(0, connection.budget.snapshot().outstandingControl());
        assertTrue(connection.budget.snapshot().activeDecode());
        assertEquals(9, connection.budget.snapshot().dataArrivals(),
                "capacity denial does not refund the DATA ingress cost");
        assertEquals(5, connection.budget.snapshot().controlArrivals(),
                "capacity denial does not refund the CONTROL ingress cost");
        RuntimeException activeFailure = new RuntimeException("active numeric worker failed");
        connection.budget.failed(activeLease, ConnectionDecodeBudget.DecodeFailure.UNEXPECTED, activeFailure);
        assertSame(activeFailure, activeLease.failureCause().orElseThrow());
        assertEquals(0, connection.budget.snapshot().outstandingData(),
                "the active ticket releases its outstanding slot only after recording its original failure");
        ConnectionDecodeBudget.DrainPermit failedDrain = drain;
        assertThrows(IllegalArgumentException.class, () -> connection.budget.finishDrain(failedDrain));
    }

    @Test
    void queuedTtlAndConnectionInvalidationRejectWorkWithoutRefundOrEarlyRelease() {
        AtomicLong now = new AtomicLong(0);
        var shortLimits = new ConnectionDecodeBudget.Limits(64, 64, 32_768, 32_768,
                8, 4, 10);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND, shortLimits, now);
        var scope = connection.budget.openScope(SCOPE);
        var staleReservation = queue(connection, scope, statePacket());
        var drain = staleReservation.newDrainPermit().orElseThrow();
        now.set(10);
        assertTrue(connection.budget.beginNext(drain).isEmpty());
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                staleReservation.ticket().orElseThrow().state());
        assertEquals(0, connection.budget.snapshot().queuedData());
        assertEquals(0, connection.budget.snapshot().outstandingData());
        connection.budget.finishDrain(drain);

        var expiringReservation = queue(connection, scope, freeStatePacket());
        var expiringDrain = expiringReservation.newDrainPermit().orElseThrow();
        var expiringLease = connection.budget.beginNext(expiringDrain).orElseThrow();
        StoredDyadic.Work expiringWork = expiringLease.numericWork();
        expiringWork.charge(9);
        connection.budget.expire(expiringLease);
        assertFalse(expiringLease.installable(), "an expired active lease loses install permission");
        assertTrue(connection.budget.snapshot().activeDecode(),
                "expiry does not release active math before the real task exits");
        assertEquals(1, connection.budget.snapshot().outstandingData());
        connection.budget.finish(expiringLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        connection.budget.finishDrain(expiringDrain);
        assertEquals(9, expiringWork.units(), "expiry preserves Work already charged by the active lease");
        assertEquals(0, connection.budget.snapshot().outstandingData());

        var activeReservation = queue(connection, scope, statePacket());
        var activeDrain = activeReservation.newDrainPermit().orElseThrow();
        var active = connection.budget.beginNext(activeDrain).orElseThrow();
        var other = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(now.get()));
        assertThrows(IllegalArgumentException.class, () -> connection.budget.invalidate(
                other.manager, connection.budget.lifetimeToken(),
                ConnectionDecodeBudget.InvalidationReason.DISCONNECT));
        assertThrows(IllegalArgumentException.class, () -> connection.budget.invalidate(
                connection.manager, other.budget.lifetimeToken(),
                ConnectionDecodeBudget.InvalidationReason.DISCONNECT));

        assertTrue(connection.budget.invalidate(connection.manager, connection.budget.lifetimeToken(),
                ConnectionDecodeBudget.InvalidationReason.DISCONNECT));
        assertFalse(active.installable());
        assertTrue(connection.budget.snapshot().activeDecode(),
                "disconnect fences installation immediately but leaves mathematical work active");
        assertEquals(1, connection.budget.snapshot().outstandingData());
        assertFalse(connection.budget.reserveIngress(connection.handler,
                frame(packet(PlayerPhysicsProtocol.Kind.STATE))).admitted());
        assertEquals(ConnectionDecodeBudget.IngressRejection.INVALIDATED,
                connection.budget.reserveIngress(connection.handler,
                        frame(packet(PlayerPhysicsProtocol.Kind.STATE))).rejection());
        connection.budget.finish(active, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        connection.budget.finishDrain(activeDrain);
        assertFalse(connection.budget.snapshot().activeDecode());
        assertEquals(0, connection.budget.snapshot().outstandingData());
        assertTrue(connection.budget.snapshot().invalidated());
    }

    @Test
    void rawFrameIdentityCannotBeDoubleChargedAndAReplacementHandlerIsRejected() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        RawFrame sameFrame = frame(packet(PlayerPhysicsProtocol.Kind.OFFER));
        var first = connection.budget.reserveIngress(connection.handler, sameFrame);
        assertTrue(first.admitted());
        assertThrows(IllegalStateException.class,
                () -> connection.budget.reserveIngress(connection.handler, sameFrame));

        TestConnection other = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        var wrongManager = connection.budget.reserveIngress(other.manager, connection.handler,
                frame(packet(PlayerPhysicsProtocol.Kind.OFFER)));
        assertFalse(wrongManager.admitted());
        assertEquals(ConnectionDecodeBudget.IngressRejection.WRONG_HANDLER, wrongManager.rejection());

        INetHandler replacement = handler();
        connection.manager.setNetHandler(replacement);
        var rejected = connection.budget.reserveIngress(connection.handler,
                frame(packet(PlayerPhysicsProtocol.Kind.OFFER)));
        assertFalse(rejected.admitted());
        assertEquals(ConnectionDecodeBudget.IngressRejection.WRONG_HANDLER, rejected.rejection());
        assertEquals(1, connection.budget.snapshot().controlArrivals());
    }

    @Test
    void limitsRejectInvalidOrWidenedValuesInsteadOfClamping() {
        var defaults = ConnectionDecodeBudget.Limits.defaults();
        assertEquals(64, defaults.dataArrivalsPerWindow());
        assertEquals(64, defaults.controlArrivalsPerWindow());
        assertEquals(32_768, defaults.dataBodyBytesPerWindow());
        assertEquals(32_768, defaults.controlBodyBytesPerWindow());
        assertEquals(8, defaults.outstandingData());
        assertEquals(4, defaults.outstandingControl());
        assertEquals(1_000_000_000L, defaults.queuedTtlNanos());
        assertEquals(1_000_000_000L, ConnectionDecodeBudget.WINDOW_NANOS);
        assertEquals(131_072L, ConnectionDecodeBudget.NUMERIC_WORK_LIMIT);
        assertDoesNotThrow(() -> new ConnectionDecodeBudget.Limits(1, 1, 1, 1, 1, 1, 1));

        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(0, 64, 100, 100, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(65, 64, 100, 100, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(64, 64, -1, 100, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(64, 64, 100, 100, 9, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(64, 64, 100, 100, 1, 4, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConnectionDecodeBudget.Limits(64, 64, 100, 100, 1, 4, 1_000_000_001L));
    }

    @Test
    void firstOfferQueuesAndMaterializesAgainstConnectionLifetimeWithoutAScope() {
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        PlayerPhysicalSnapshot.Scope offeredScope =
                new PlayerPhysicalSnapshot.Scope(new UUID(31, 32), new UUID(33, 34), 4, 91);
        PlayerPhysicsProtocol.Packet offer = new PlayerPhysicsProtocol.Packet(
                PlayerPhysicsProtocol.Kind.OFFER, offeredScope, 1, 1,
                Optional.empty(), Optional.empty(), Optional.empty());

        ConnectionDecodeBudget.FullFrameInspection inspection = inspect(connection, offer);
        assertEquals(PlayerPhysicsProtocol.Kind.OFFER, inspection.kind());
        assertTrue(inspection.noFreeControlRecovery());
        assertFalse(inspection.hasFreePlacement());

        var reservation = connection.budget.reserveConnectionOffer(inspection);
        assertTrue(reservation.queued());
        ConnectionDecodeBudget.Ticket ticket = reservation.ticket().orElseThrow();
        assertTrue(ticket.connectionBound());
        assertTrue(ticket.scopeToken().isEmpty(), "the first OFFER is not assigned a fabricated scope token");
        assertThrows(IllegalStateException.class, ticket::scope,
                "the scope accessor cannot expose a fake scope for a connection-lifetime ticket");
        assertSame(inspection.frame(), ticket.frame());
        assertEquals(1, connection.budget.snapshot().queuedControl());
        assertEquals(1, connection.budget.snapshot().outstandingControl());

        ConnectionDecodeBudget.DrainPermit drain = reservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease lease = connection.budget.beginNext(drain).orElseThrow();
        assertFalse(lease.requiresNumericProof());
        assertTrue(lease.installable());
        assertEquals(offer, lease.materializePacket());
        connection.budget.finish(lease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
    }

    @Test
    void connectionOfferRouteRejectsOtherControlsProofAndWrongDirections() {
        TestConnection clientbound = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.FullFrameInspection nativeInspection =
                inspect(clientbound, packet(PlayerPhysicsProtocol.Kind.NATIVE));
        assertThrows(IllegalArgumentException.class,
                () -> clientbound.budget.reserveConnectionOffer(nativeInspection),
                "NATIVE remains a CONTROL recovery packet but is not an OFFER");

        TestConnection serverbound = connection(EnumPacketDirection.SERVERBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.FullFrameInspection applyInspection =
                inspect(serverbound, packet(PlayerPhysicsProtocol.Kind.APPLY));
        assertThrows(IllegalArgumentException.class,
                () -> serverbound.budget.reserveConnectionOffer(applyInspection),
                "APPLY is direction-correct but cannot claim a clientbound connection OFFER target");

        byte[] offerBytes = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OFFER));
        RawFrame wrongDirectionFrame = raw(offerBytes);
        var wrongDirectionIngress = serverbound.budget.reserveIngress(serverbound.handler, wrongDirectionFrame);
        assertEquals(ConnectionDecodeBudget.TrafficClass.DATA, wrongDirectionIngress.trafficClass());
        assertThrows(IllegalArgumentException.class,
                () -> serverbound.budget.inspectFullFrame(wrongDirectionIngress),
                "an OFFER received in the wrong actual direction cannot reach queue admission");

        byte[] trailingOffer = Arrays.copyOf(offerBytes, offerBytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> inspect(clientbound, trailingOffer),
                "the real parser rejects a malformed/trailing-byte OFFER before admission");

        ConnectionDecodeBudget.FullFrameInspection freeInspection =
                inspect(clientbound, freeStatePacket());
        assertTrue(freeInspection.hasFreePlacement());
        assertThrows(IllegalArgumentException.class,
                () -> clientbound.budget.reserveConnectionOffer(freeInspection),
                "a valid Free placement frame is DATA and cannot use the OFFER connection target");
        assertEquals(0, clientbound.budget.snapshot().queuedControl());
        assertEquals(0, clientbound.budget.snapshot().outstandingControl());
        assertEquals(0, serverbound.budget.snapshot().queuedControl());
        assertEquals(0, serverbound.budget.snapshot().outstandingControl());
    }

    @Test
    void connectionOfferAdmissionRejectsForeignAndRepeatedInspections() {
        TestConnection owner = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        TestConnection foreign = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.FullFrameInspection inspection =
                inspect(owner, packet(PlayerPhysicsProtocol.Kind.OFFER));

        assertThrows(IllegalArgumentException.class,
                () -> foreign.budget.reserveConnectionOffer(inspection));
        assertEquals(0, owner.budget.snapshot().queuedControl(),
                "a foreign ledger cannot consume or queue the owner's inspection");
        var reservation = owner.budget.reserveConnectionOffer(inspection);
        assertTrue(reservation.queued());
        assertThrows(IllegalStateException.class,
                () -> owner.budget.reserveConnectionOffer(inspection),
                "one parser result cannot create multiple connection-lifetime tickets");
        assertEquals(1, owner.budget.snapshot().outstandingControl());

        ConnectionDecodeBudget.DrainPermit drain = reservation.newDrainPermit().orElseThrow();
        var lease = owner.budget.beginNext(drain).orElseThrow();
        assertEquals(PlayerPhysicsProtocol.Kind.OFFER, lease.materializePacket().kind());
        owner.budget.finish(lease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        owner.budget.finishDrain(drain);
    }

    @Test
    void connectionAndScopeTicketsShareControlCapsSingleDrainAndFifo() {
        var limits = new ConnectionDecodeBudget.Limits(64, 64, 32_768, 32_768,
                1, 2, ConnectionDecodeBudget.DEFAULT_QUEUED_TTL_NANOS);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND, limits, new AtomicLong(0));
        ConnectionDecodeBudget.ScopeToken scope = connection.budget.openScope(SCOPE);

        var data = queue(connection, scope, statePacket());
        ConnectionDecodeBudget.FullFrameInspection offerInspection =
                inspect(connection, packet(PlayerPhysicsProtocol.Kind.OFFER));
        var offer = connection.budget.reserveConnectionOffer(offerInspection);
        var nativeControl = queue(connection, scope, packet(PlayerPhysicsProtocol.Kind.NATIVE));
        var overControl = connection.budget.reserveConnectionOffer(
                inspect(connection, packet(PlayerPhysicsProtocol.Kind.OFFER)));

        assertTrue(data.queued());
        assertTrue(offer.queued());
        assertTrue(nativeControl.queued());
        assertFalse(overControl.queued());
        assertEquals(ConnectionDecodeBudget.QueueRejection.OUTSTANDING_LIMIT, overControl.rejection());
        assertEquals(2, connection.budget.snapshot().queuedControl());
        assertEquals(2, connection.budget.snapshot().outstandingControl());
        assertEquals(1, connection.budget.snapshot().outstandingData());

        ConnectionDecodeBudget.DrainPermit drain = data.newDrainPermit().orElseThrow();
        assertTrue(offer.newDrainPermit().isEmpty());
        assertTrue(nativeControl.newDrainPermit().isEmpty());
        ConnectionDecodeBudget.DecodeLease dataLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(data.ticket().orElseThrow(), dataLease.ticket(),
                "DATA queued first remains ahead of connection-bound CONTROL in the one FIFO");
        assertTrue(connection.budget.beginNext(drain).isEmpty(),
                "connection-bound admission does not create a second active decoder");
        connection.budget.finish(dataLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);

        ConnectionDecodeBudget.DecodeLease offerLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(offer.ticket().orElseThrow(), offerLease.ticket());
        assertTrue(offerLease.ticket().scopeToken().isEmpty());
        assertEquals(PlayerPhysicsProtocol.Kind.OFFER, offerLease.materializePacket().kind());
        connection.budget.finish(offerLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);

        ConnectionDecodeBudget.DecodeLease nativeLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(nativeControl.ticket().orElseThrow(), nativeLease.ticket());
        assertEquals(Optional.of(scope), nativeLease.ticket().scopeToken());
        assertEquals(PlayerPhysicsProtocol.Kind.NATIVE, nativeLease.materializePacket().kind());
        connection.budget.finish(nativeLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);
        assertEquals(0, connection.budget.snapshot().outstandingControl());
    }

    @Test
    void retiringScopeCancelsOnlyItsTicketsAndKeepsConnectionOfferAndWindowWork() {
        AtomicLong now = new AtomicLong(0);
        TestConnection connection = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), now);
        ConnectionDecodeBudget.ScopeToken oldScope = connection.budget.openScope(SCOPE);
        var activeReservation = queue(connection, oldScope, freeStatePacket());
        ConnectionDecodeBudget.DrainPermit drain = activeReservation.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease active = connection.budget.beginNext(drain).orElseThrow();
        StoredDyadic.Work work = active.numericWork();
        assertEquals(PlayerPhysicsProtocol.Kind.STATE, active.materializePacket().kind());
        assertTrue(work.units() > 0, "the test holds actual Free proof work before retiring the old scope");

        var queuedOldData = queue(connection, oldScope, statePacket());
        PlayerPhysicalSnapshot.Scope nextScopeValue = new PlayerPhysicalSnapshot.Scope(
                SCOPE.playerId(), SCOPE.sessionId(), SCOPE.dimension(), SCOPE.worldEpoch() + 1);
        PlayerPhysicsProtocol.Packet offerPacket = new PlayerPhysicsProtocol.Packet(
                PlayerPhysicsProtocol.Kind.OFFER, nextScopeValue, 2, 2,
                Optional.empty(), Optional.empty(), Optional.empty());
        var queuedOffer = connection.budget.reserveConnectionOffer(inspect(connection, offerPacket));
        assertTrue(queuedOldData.queued());
        assertTrue(queuedOffer.queued());

        connection.budget.retireScope(oldScope);
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                queuedOldData.ticket().orElseThrow().state());
        assertEquals(ConnectionDecodeBudget.TicketState.QUEUED,
                queuedOffer.ticket().orElseThrow().state(),
                "a world-scope retirement cannot cancel an OFFER bound to the connection lifetime");
        assertFalse(active.installable());
        long chargedBeforeExit = work.units();
        connection.budget.finish(active, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        assertEquals(chargedBeforeExit, work.units(), "retirement does not refund active proof work");

        ConnectionDecodeBudget.DecodeLease offerLease = connection.budget.beginNext(drain).orElseThrow();
        assertSame(queuedOffer.ticket().orElseThrow(), offerLease.ticket());
        assertTrue(offerLease.installable(), "the retired scope is not a fence for its connection OFFER");
        assertFalse(offerLease.requiresNumericProof());
        assertEquals(offerPacket, offerLease.materializePacket());
        connection.budget.finish(offerLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        connection.budget.finishDrain(drain);

        ConnectionDecodeBudget.ScopeToken nextScope = connection.budget.openScope(nextScopeValue);
        var nextData = queue(connection, nextScope, freeStatePacket(nextScopeValue));
        ConnectionDecodeBudget.DrainPermit nextDrain = nextData.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease nextLease = connection.budget.beginNext(nextDrain).orElseThrow();
        assertSame(work, nextLease.numericWork(),
                "opening the next accepted scope neither resets nor refunds the connection window Work");
        assertEquals(3, connection.budget.snapshot().dataArrivals());
        assertEquals(1, connection.budget.snapshot().controlArrivals());
        connection.budget.finish(nextLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        connection.budget.finishDrain(nextDrain);
    }

    @Test
    void connectionOfferLifetimeEnforcesTtlDisconnectAndReplacementBoundaries() {
        AtomicLong queuedClock = new AtomicLong(0);
        var shortLimits = new ConnectionDecodeBudget.Limits(64, 64, 32_768, 32_768,
                8, 4, 10);
        TestConnection queued = connection(EnumPacketDirection.CLIENTBOUND, shortLimits, queuedClock);
        var queuedOffer = queued.budget.reserveConnectionOffer(
                inspect(queued, packet(PlayerPhysicsProtocol.Kind.OFFER)));
        ConnectionDecodeBudget.DrainPermit queuedDrain = queuedOffer.newDrainPermit().orElseThrow();
        queuedClock.set(10);
        assertTrue(queued.budget.beginNext(queuedDrain).isEmpty());
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                queuedOffer.ticket().orElseThrow().state(),
                "connection-bound OFFER obeys the same queued TTL as scope tickets");
        queued.budget.finishDrain(queuedDrain);

        TestConnection expiring = connection(EnumPacketDirection.CLIENTBOUND, shortLimits, new AtomicLong(0));
        var expiringOffer = expiring.budget.reserveConnectionOffer(
                inspect(expiring, packet(PlayerPhysicsProtocol.Kind.OFFER)));
        ConnectionDecodeBudget.DrainPermit expiringDrain = expiringOffer.newDrainPermit().orElseThrow();
        ConnectionDecodeBudget.DecodeLease expiringLease = expiring.budget.beginNext(expiringDrain).orElseThrow();
        expiring.budget.expire(expiringLease);
        assertFalse(expiringLease.installable());
        assertTrue(expiring.budget.snapshot().activeDecode());
        assertEquals(1, expiring.budget.snapshot().outstandingControl());
        assertThrows(IllegalStateException.class, expiringLease::materializePacket,
                "an expired active connection target cannot start materialization");
        assertTrue(expiring.budget.snapshot().activeDecode(),
                "expiry revokes installation without releasing active work before task exit");
        expiring.budget.finish(expiringLease, ConnectionDecodeBudget.DecodeOutcome.REJECTED);
        expiring.budget.finishDrain(expiringDrain);

        TestConnection disconnected = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        ConnectionDecodeBudget.FullFrameInspection oldInspection =
                inspect(disconnected, packet(PlayerPhysicsProtocol.Kind.OFFER));
        var disconnectedOffer = disconnected.budget.reserveConnectionOffer(oldInspection);
        ConnectionDecodeBudget.DrainPermit disconnectedDrain =
                disconnectedOffer.newDrainPermit().orElseThrow();
        assertTrue(disconnected.budget.invalidate(disconnected.manager,
                disconnected.budget.lifetimeToken(), ConnectionDecodeBudget.InvalidationReason.DISCONNECT));
        assertEquals(ConnectionDecodeBudget.TicketState.CANCELLED,
                disconnectedOffer.ticket().orElseThrow().state(),
                "disconnect before the first scheduled drain cancels queued connection-target work");
        assertTrue(disconnected.budget.beginNext(disconnectedDrain).isEmpty());
        disconnected.budget.finishDrain(disconnectedDrain);

        TestConnection replacement = connection(EnumPacketDirection.CLIENTBOUND,
                ConnectionDecodeBudget.Limits.defaults(), new AtomicLong(0));
        assertThrows(IllegalArgumentException.class,
                () -> replacement.budget.reserveConnectionOffer(oldInspection),
                "a replacement connection cannot rebind the old lifetime or inspection");
        var replacementOffer = replacement.budget.reserveConnectionOffer(
                inspect(replacement, packet(PlayerPhysicsProtocol.Kind.OFFER)));
        assertTrue(replacementOffer.queued());
        assertNotSame(disconnectedOffer.ticket().orElseThrow(), replacementOffer.ticket().orElseThrow());
        assertTrue(replacementOffer.ticket().orElseThrow().connectionBound());
        assertTrue(replacementOffer.ticket().orElseThrow().scopeToken().isEmpty());
        ConnectionDecodeBudget.DrainPermit replacementDrain = replacementOffer.newDrainPermit().orElseThrow();
        var replacementLease = replacement.budget.beginNext(replacementDrain).orElseThrow();
        assertEquals(PlayerPhysicsProtocol.Kind.OFFER, replacementLease.materializePacket().kind());
        replacement.budget.finish(replacementLease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
        replacement.budget.finishDrain(replacementDrain);
    }

    private static ConnectionDecodeBudget.IngressReceipt reserve(TestConnection connection, RawFrame raw) {
        return connection.budget.reserveIngress(connection.manager, connection.handler, raw);
    }

    private static ConnectionDecodeBudget.FullFrameInspection inspect(
            TestConnection connection, PlayerPhysicsProtocol.Packet packet) {
        return inspect(connection, PlayerPhysicsProtocol.encode(packet));
    }

    private static ConnectionDecodeBudget.FullFrameInspection inspect(
            TestConnection connection, byte[] encodedFrame) {
        RawFrame raw = raw(encodedFrame);
        var ingress = connection.budget.reserveIngress(connection.handler, raw);
        assertTrue(ingress.admitted(), "fixture ingress is within the configured admission quota");
        return connection.budget.inspectFullFrame(ingress);
    }

    private static ConnectionDecodeBudget.QueueReservation queue(
            TestConnection connection, ConnectionDecodeBudget.ScopeToken scope,
            PlayerPhysicsProtocol.Packet packet) {
        RawFrame raw = frame(packet);
        var ingress = connection.budget.reserveIngress(connection.handler, raw);
        assertTrue(ingress.admitted(), "fixture ingress is within the configured admission quota");
        var inspection = connection.budget.inspectFullFrame(ingress);
        return connection.budget.reserveQueue(inspection, scope);
    }

    private static ConnectionDecodeBudget.QueueReservation queue(
            TestConnection connection, ConnectionDecodeBudget.ScopeToken scope, byte[] encodedFrame) {
        RawFrame raw = raw(encodedFrame);
        var ingress = connection.budget.reserveIngress(connection.handler, raw);
        assertTrue(ingress.admitted(), "fixture ingress is within the configured admission quota");
        var inspection = connection.budget.inspectFullFrame(ingress);
        return connection.budget.reserveQueue(inspection, scope);
    }

    private static TestConnection connection(EnumPacketDirection direction,
                                             ConnectionDecodeBudget.Limits limits,
                                             AtomicLong now) {
        NetworkManager manager = new NetworkManager(direction);
        INetHandler handler = handler();
        manager.setNetHandler(handler);
        ConnectionDecodeBudget budget = new ConnectionDecodeBudget(
                manager, handler, direction, limits, now::get);
        return new TestConnection(manager, handler, budget, now);
    }

    private static INetHandler handler() {
        return new INetHandler() {
            @Override public void onDisconnect(ITextComponent reason) { }
        };
    }

    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicsProtocol.Kind kind) {
        if (kind == PlayerPhysicsProtocol.Kind.STATE) return statePacket();
        if (kind == PlayerPhysicsProtocol.Kind.NATIVE) return PlayerPhysicsProtocol.nativeTransferPacket(kind,SCOPE,1,1,new PlayerPhysicsProtocol.NativeHandoff(1,0,new Vec3d(0,0,0)));
        return new PlayerPhysicsProtocol.Packet(kind, SCOPE, 1, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet statePacket() {
        return statePacket(SCOPE);
    }

    private static PlayerPhysicsProtocol.Packet statePacket(PlayerPhysicalSnapshot.Scope scope) {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE);
        var placement = new PlayerBodyPlacement(new Vec3d(2, 7, -3),
                CardinalFrame.Y_POS.attitude(), shape);
        var state = new PlayerPhysicalSnapshot(scope, 1, placement, new Vec3d(0, 0, 0));
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 1, 1,
                Optional.empty(), Optional.of(state), Optional.empty());
    }

    // C44 regression source only, NOT_RUN: real codec and lease materialization at 20 snapshots/s.
    @Test void twentyRotatedFreeSnapshotsMaterializeInTheSameNumericWindow() {
        var clock = new AtomicLong(0);
        var connection = connection(EnumPacketDirection.CLIENTBOUND, ConnectionDecodeBudget.Limits.defaults(), clock);
        var scope = connection.budget.openScope(SCOPE);
        var center = new Vec3d(31.037567794136528, 132.20407477274063, 105.91834824733665);
        var anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center,
                center.add(new Vec3d(0, .72, 0)), new Rotation3d(.15, .2, .3, .7));
        var shape = new PlayerBodyShape.FreeCapsule(SIZE,
                new PlayerBodyShape.CollisionProfile(SIZE.width(), SIZE.height()), Optional.of(anchor));
        var placement = new PlayerBodyPlacement(center, new Rotation3d(.6, -.1, .2, .7), shape);
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 19, placement, new Vec3d(0, 0, 0));
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 20, 17,
                Optional.empty(), Optional.of(snapshot), Optional.empty());
        StoredDyadic.Work shared = null;
        for (int tick = 0; tick < 20; tick++) {
            clock.set(tick * 50_000_000L);
            var reservation = queue(connection, scope, packet);
            var drain = reservation.newDrainPermit().orElseThrow();
            var lease = connection.budget.beginNext(drain).orElseThrow();
            if (shared == null) shared = lease.numericWork();
            assertSame(shared, lease.numericWork());
            assertEquals(PlayerPhysicsProtocol.Kind.STATE, lease.materializePacket().kind());
            connection.budget.finish(lease, ConnectionDecodeBudget.DecodeOutcome.PROOF_ACCEPTED);
            connection.budget.finishDrain(drain);
        }
        assertTrue(shared.units() > 0 && shared.units() < StoredDyadic.MAX_WORK_UNITS);
        assertEquals(0, connection.budget.snapshot().windowSequence());
        assertEquals(0, connection.budget.snapshot().outstandingData());
        assertFalse(connection.budget.snapshot().activeDecode());
    }

    private static PlayerPhysicsProtocol.Packet freeStatePacket() {
        return freeStatePacket(SCOPE);
    }

    private static PlayerPhysicsProtocol.Packet freeStatePacket(PlayerPhysicalSnapshot.Scope scope) {
        return freeStatePacket(scope, axisFreeFixture());
    }

    private static PlayerPhysicsProtocol.Packet freeStatePacket(
            PlayerPhysicalSnapshot.Scope scope, FreeFixture fixture) {
        var shape = new PlayerBodyShape.FreeCapsule(SIZE);
        PlayerBodyPlacement placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(
                fixture.geometry().center(), fixture.attitude(), shape,
                fixture.geometry(), fixture.proxy(), fixture.transport());
        var snapshot = new PlayerPhysicalSnapshot(scope, 19, placement, new Vec3d(1, -2, 3));
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 20, 17,
                Optional.empty(), Optional.of(snapshot), Optional.empty());
    }

    private static RawFrame frame(PlayerPhysicsProtocol.Packet packet) {
        return raw(PlayerPhysicsProtocol.encode(packet));
    }

    private static RawFrame raw(byte[] bytes) {
        ByteBuf body = Unpooled.wrappedBuffer(bytes);
        try {
            return ConnectionDecodeBudget.RawFrame.capture(body);
        } finally {
            body.release();
        }
    }

    private static FreeFixture freeFixture() {
        Rotation3d attitude = Rotation3d.identity();
        Vec3d center = new Vec3d(2.25, -3.5, 7.75);
        StoredDyadic.Work sourceWork = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(center, attitude, SIZE, sourceWork);
        RealizationRelation relation = RealizationRelation.nominal(limits);
        StoredCapsuleGeometry geometry = StoredCapsuleGeometry.fromRecipe(
                center, attitude, SIZE, limits.nominalHalfLine(), relation);
        Aabb proxy = expanded(geometry.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(center.x() + 0.125, proxy.min().y(), center.z() - 0.25);
        FreeCapsulePlacementV1 wire = new FreeCapsulePlacementV1(
                RawVec3.from(center), RawVec3.from(geometry.storedHalfLine()),
                RawVec3.from(relation.limits().centerReference()), new Nominal());
        return new FreeFixture(attitude, geometry, wire, proxy, transport);
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
        FreeCapsulePlacementV1 wire = PlayerPlacementCodec.exportFreeV1(placement);
        return new FreeFixture(attitude, translated, wire, proxy, transport);
    }

    private static byte[] invalidAxisSupportWire(byte[] validWire) {
        byte[] invalid = validWire.clone();
        int proofTag = freeSnapshotOffset() + 209;
        ByteBuffer.wrap(invalid).putLong(proofTag + 27,
                Double.doubleToRawLongBits(Math.nextUp(0.125)));
        return invalid;
    }

    private static byte[] invalidHalfLineWire(byte[] validWire) {
        byte[] invalid = validWire.clone();
        int halfLineY = freeSnapshotOffset() + 97;
        ByteBuffer wrapped = ByteBuffer.wrap(invalid);
        wrapped.putLong(halfLineY, Double.doubleToRawLongBits(
                Math.nextUp(Double.longBitsToDouble(wrapped.getLong(halfLineY)))));
        return invalid;
    }

    private static int freeSnapshotOffset() {
        // Version/kind, scope, sequence pair, application tag, native-correction tag.
        return 1 + 1 + 16 + 16 + 4 + 8 + 8 + 1 + 1;
    }

    private static Aabb expanded(Aabb bounds, double margin) {
        return new Aabb(bounds.min().subtract(new Vec3d(margin, margin, margin)),
                bounds.max().add(new Vec3d(margin, margin, margin)));
    }

    private record TestConnection(NetworkManager manager, INetHandler handler,
                                  ConnectionDecodeBudget budget, AtomicLong clock) { }
    private record FreeFixture(Rotation3d attitude, StoredCapsuleGeometry geometry,
                               FreeCapsulePlacementV1 wire, Aabb proxy, Vec3d transport) { }
}
