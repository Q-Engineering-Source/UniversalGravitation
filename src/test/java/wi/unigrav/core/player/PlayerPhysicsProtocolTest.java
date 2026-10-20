package wi.unigrav.core.player;

import wi.unigrav.Reference;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.GravityMode;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsProtocolTest {
    @Test void serverEnvironmentRecoveryOfferHasExactBoundedWireAndStrictOriginKind() {
        var successor = new PlayerPhysicalSnapshot.Scope(SCOPE.playerId(), new UUID(71, 72),
                SCOPE.dimension(), SCOPE.worldEpoch() + 1);
        var receipt = new PlayerPhysicsProtocol.ServerEnvironmentRecovery(SCOPE, 3, OptionalInt.of(27));
        var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER, successor, 1, 1, receipt);
        byte[] encoded = PlayerPhysicsProtocol.encode(packet);
        assertEquals(126, encoded.length, "the real maximum environment OFFER includes its optional TP identity");
        assertTrue(encoded.length <= PlayerPhysicsProtocol.MAX_BYTES);
        assertEquals(packet, decodeForTest(encoded));
        var withoutTp = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER, successor, 1, 1,
                new PlayerPhysicsProtocol.ServerEnvironmentRecovery(SCOPE, 3, OptionalInt.empty()));
        assertEquals(122, PlayerPhysicsProtocol.encode(withoutTp).length);
        assertEquals(withoutTp, decodeForTest(PlayerPhysicsProtocol.encode(withoutTp)));
        byte[] previousVersion = encoded.clone(); previousVersion[0] = 17;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(previousVersion));
        for (var kind : PlayerPhysicsProtocol.Kind.values()) {
            if (kind == PlayerPhysicsProtocol.Kind.OFFER) continue;
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(kind, successor, 1, 1, receipt));
            byte[] wrongKind = encoded.clone(); wrongKind[1] = (byte) kind.ordinal();
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(wrongKind));
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(
                PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 1, 1, receipt));
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(new byte[PlayerPhysicsProtocol.MAX_BYTES + 1]));
    }

    @Test void nativeFailureFeedbackAndRecoveryEchoUseStrictVersionedIdentityOnlyPayloads() {
        var failed = new PlayerPhysicsProtocol.NativeTransferFailure(91, 4, 5, 0);
        var request = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.RECOVER, SCOPE, 8, 1, failed);
        var wire = PlayerPhysicsProtocol.encode(request);
        assertEquals(20, Byte.toUnsignedInt(wire[0]));
        assertEquals(request, decodeForTest(wire));
        var old = wire.clone(); old[0] = 16;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(old));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(
                PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 8, 1, failed));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(
                PlayerPhysicsProtocol.Kind.RECOVER, SCOPE, 8, 1, new PlayerPhysicsProtocol.NativeTransferRecovery(failed)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeTransferFailure(0, 4, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeTransferFailure(91, 4, 5, -1));
        var original = seededPacket(CardinalFrame.Y_POS, new CardinalPredictionSeed.Unavailable(
                CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, Optional.empty()), false);
        var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        var snapshot = original.snapshot().orElseThrow();
        var recovery = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 9, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.of(receipt),
                Optional.empty(), original.bodyReferenceState(), Optional.empty(), Optional.empty(), original.predictionSeed(),
                Optional.of(new PlayerPhysicsProtocol.PredictionUnavailable(snapshot.revision(),
                        PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE)), Optional.empty(), Optional.empty(),
                Optional.of(new PlayerPhysicsProtocol.NativeTransferRecovery(failed)));
        assertEquals(recovery, decodeForTest(PlayerPhysicsProtocol.encode(recovery)));
        assertTrue(PlayerPhysicsProtocol.encode(recovery).length < PlayerPhysicsProtocol.MAX_BYTES);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(new byte[PlayerPhysicsProtocol.MAX_BYTES + 1]));
    }

    @Test void nativeTransferHasExactDoublePayloadAndStrictKinds() {
        var velocity = new Vec3d(260.125, -Math.nextUp(260.0), Double.MIN_NORMAL);
        var payloads = java.util.List.of(
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE, new PlayerPhysicsProtocol.NativeHandoff(9, 17, velocity)),
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE_MOTION, new PlayerPhysicsProtocol.NativeMotion(9, 17, 1, velocity)),
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE_ACK, new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(9, 17)),
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE_MOTION_ACK, new PlayerPhysicsProtocol.NativeMotionAcknowledgement(9, 17, 1)),
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE, new PlayerPhysicsProtocol.NativeCorrectionCompleted(19, 73)),
                new NativeCase(PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK, new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(19, 73, 7)));
        for (var entry : payloads) {
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(entry.kind(), SCOPE, 7, 3, entry.payload());
            byte[] encoded = PlayerPhysicsProtocol.encode(packet);
            assertEquals(packet, PlayerPhysicsProtocol.decode(encoded));
            assertTrue(encoded.length < PlayerPhysicsProtocol.MAX_BYTES);
            for (int version : new int[]{14, 15}) {
                byte[] old = encoded.clone(); old[0] = (byte) version;
                assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(old));
            }
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeHandoff(0, 0, velocity));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeMotion(1, 0, 0, velocity));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeHandoff(1, -1, velocity));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeHandoff(1, 0, new Vec3d(Double.NaN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.INPUT,
                SCOPE, 1, 1, new PlayerPhysicsProtocol.NativeHandoff(1, 0, velocity)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE,
                SCOPE, 1, 1, Optional.empty(), Optional.empty(), Optional.empty()));
    }
    @Test void nativeCorrectionControlCannotCarrySnapshotOrVelocityAuthority() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeCorrectionCompleted(0, 73));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeCorrectionCompleted(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(1, 73, -1));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(
                PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 9, 3, new PlayerPhysicsProtocol.NativeCorrectionCompleted(1, 73)));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.nativeTransferPacket(
                PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE, SCOPE, 9, 3,
                new PlayerPhysicsProtocol.NativeHandoff(1, 1, new Vec3d(260, 0, 0))));
        var completed = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE,
                SCOPE, 9, 3, new PlayerPhysicsProtocol.NativeCorrectionCompleted(Long.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(completed.clientbound());
        assertTrue(completed.snapshot().isEmpty());
        assertTrue(completed.controls().isEmpty());
        assertEquals(completed, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(completed)));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(new byte[PlayerPhysicsProtocol.MAX_BYTES + 1]));
    }
    private record NativeCase(PlayerPhysicsProtocol.Kind kind, PlayerPhysicsProtocol.NativeTransfer payload) { }

    @Test void versionNineInputRequiresPresenceByteAndCallerOwnedNumericWork() {
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(),
                Optional.of(controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), 5)));
        byte[] bytes = PlayerPhysicsProtocol.encode(input);
        assertEquals(149, bytes.length);
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(bytes));
    }
    @Test void fullQuaternionInputKeepsRollRawBitsAndExactLengthsWithSharedWork() {
        var q = new Rotation3d(.2, .3, .4, .5);
        var shared = new StoredDyadic.Work();
        for (boolean lock : new boolean[] {false, true}) for (boolean ack : new boolean[] {false, true}) {
            var intent = lock ? PlayerPhysicsProtocol.BodyReferenceIntent.lock(4, CardinalFrame.X_POS, new Vec3d(0, 1, 0))
                    : PlayerPhysicsProtocol.BodyReferenceIntent.none();
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 5, 1, intent, Optional.of(q));
            var input = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 1, 7,
                    Optional.empty(), Optional.empty(), Optional.of(controls), OptionalInt.empty(), Optional.empty(),
                    ack ? Optional.of(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(1, 1)) : Optional.empty());
            byte[] bytes = PlayerPhysicsProtocol.encode(input);
            assertEquals(181 + (lock ? 25 : 0) + (ack ? 16 : 0), bytes.length);
            var budget = new PhysicsDecodeBudget(bytes.length, shared);
            long before = shared.units();
            var inspected = PlayerPhysicsProtocol.inspectFrame(bytes);
            assertTrue(inspected.requiresNumericWork());
            assertFalse(inspected.hasFreePlacement());
            assertEquals(before, shared.units(), "inspection does not reconstruct or normalize input Q");
            var decoded = inspected.materialize(budget);
            assertEquals(input, decoded);
            var restored = decoded.controls().orElseThrow().desiredBodyAttitude().orElseThrow();
            assertTrue(FreeCapsulePlacementV1.RawQuaternion.from(q).exactlyMatches(restored));
            int fields = 20 + (lock ? 4 : 0) + (ack ? 2 : 0);
            assertEquals(fields, budget.rawFieldReads());
            assertEquals(fields + 108, shared.units() - before);
            assertThrows(IllegalStateException.class, () -> inspected.materialize(budget));
        }
        assertTrue(controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), 5).desiredBodyAttitude().isEmpty());
    }

    @Test void inputQuaternionFailureKeepsRawFieldsAndVerificationWorkCharged() {
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5, 1, PlayerPhysicsProtocol.BodyReferenceIntent.none(),
                Optional.of(Rotation3d.identity()));
        byte[] valid = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.INPUT,
                Optional.empty(), Optional.empty(), Optional.of(controls)));
        var shared = new StoredDyadic.Work();
        for (long bad : new long[] {Double.doubleToRawLongBits(Double.NaN), Long.MIN_VALUE,
                Double.doubleToRawLongBits(2.0)}) {
            byte[] wire = valid.clone();
            ByteBuffer.wrap(wire).putLong(131, bad);
            var inspected = PlayerPhysicsProtocol.inspectFrame(wire);
            long before = shared.units();
            var budget = new PhysicsDecodeBudget(wire.length, shared);
            assertThrows(IllegalArgumentException.class, () -> inspected.materialize(budget));
            assertEquals(128, shared.units() - before);
        }
        byte[] negativeSign = valid.clone();
        ByteBuffer.wrap(negativeSign).putDouble(155, -1.0);
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysicsProtocol.decode(negativeSign, new PhysicsDecodeBudget(negativeSign.length, shared)));
        byte[] old = valid.clone(); old[0] = 8;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(old));
        byte[] tag = valid.clone(); tag[130] = 2;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(tag));
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(truncated));
        }
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(Arrays.copyOf(valid, valid.length + 1)));
    }
    @Test void invalidInputAcknowledgementIsRejectedOnlyAfterCallerWorkIsCharged() {
        var input = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 1, 7,
                Optional.empty(), Optional.empty(), Optional.of(controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), 5)),
                OptionalInt.empty(), Optional.empty(), Optional.of(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(1, 1)));
        byte[] wire = PlayerPhysicsProtocol.encode(input);
        ByteBuffer.wrap(wire).putLong(wire.length - 21, -1);
        var shared = new StoredDyadic.Work();
        var inspected = PlayerPhysicsProtocol.inspectFrame(wire);
        assertEquals(0, shared.units());
        var budget = new PhysicsDecodeBudget(wire.length, shared);
        assertThrows(IllegalArgumentException.class, () -> inspected.materialize(budget));
        assertEquals(18, budget.rawFieldReads());
        assertEquals(18, shared.units());
    }
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), -1, 3);

    @Test void recoverIsVersionNineHeaderOnlyServerboundAndRejectsVersionEight() {
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.RECOVER, SCOPE, 9, 4,
                Optional.empty(), Optional.empty(), Optional.empty());
        byte[] encoded = PlayerPhysicsProtocol.encode(packet);
        assertEquals(PlayerPhysicsProtocol.VERSION, encoded[0]);
        assertFalse(packet.clientbound());
        assertEquals(packet, decodeForTest(encoded));
        assertTrue(PlayerPhysicsProtocol.inspectFrame(encoded).noFreeRecoveryControl());
        var apply = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, SCOPE, 9, 4,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(PlayerPhysicsProtocol.encode(apply).length, encoded.length);
        encoded[0] = 8;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(encoded));
    }
    @Test void loggedSwimmingDimensionsRoundTripAndOldVersionIsRejected() {
        var dimensions = new PlayerBodyShape.Dimensions(.6000000238418579, .5999999642372131, .4000000059604645);
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions);
        var placement = PlayerBodyPlacement.fromTransport(new Vec3d(8, 65, 9), CardinalFrame.Y_POS.attitude(), shape);
        var state = new PlayerPhysicalSnapshot(SCOPE, 2, placement, new Vec3d(1, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
        var original = packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.of(state), Optional.empty());
        byte[] bytes = PlayerPhysicsProtocol.encode(original);
        assertEquals(original, decodeForTest(bytes));
        assertEquals(placement.bounds(), decodeForTest(bytes).snapshot().orElseThrow().placement().bounds());
        bytes[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(bytes));
    }

    @Test void nativeEyeOutsideColliderRoundTripsWithoutExpandingCollisionGeometry() {
        var dimensions = new PlayerBodyShape.Dimensions(.5, .5, 1.5);
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions);
        var placement = PlayerBodyPlacement.fromTransport(new Vec3d(0, 2, 0), CardinalFrame.Y_POS.attitude(), shape);
        var state = new PlayerPhysicalSnapshot(SCOPE, 1, placement, new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
        var original = packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.of(state), Optional.empty());
        assertEquals(original, decodeForTest(PlayerPhysicsProtocol.encode(original)));
        assertEquals(2.5, placement.bounds().max().y());
        assertEquals(3.5, placement.eye().y());
        assertEquals(2.5, placement.capsule().bounds().max().y());
    }

    @Test void applyCarriesIdentityNotClientVelocity() {
        var packet = packet(PlayerPhysicsProtocol.Kind.APPLY, Optional.empty(), Optional.empty(), Optional.empty());
        byte[] bytes = PlayerPhysicsProtocol.encode(packet);
        assertEquals(packet, decodeForTest(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> decodeForTest(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(truncated));
        }
    }

    @Test void preservesActualBoundsAndTransportForEachCardinalFrame() {
        for (var frame : CardinalFrame.values()) {
            var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F));
            var nominal = new PlayerBodyPlacement(new Vec3d(2, 7, -3), frame.attitude(), shape);
            var box = new Aabb(nominal.bounds().min(), nominal.bounds().max().add(new Vec3d(1e-14, 0, 0)));
            var placement = PlayerBodyPlacement.fromCardinalBounds(box, nominal.transport(), frame.attitude(), shape);
            var snapshot = new PlayerPhysicalSnapshot(SCOPE, 9, placement, new Vec3d(40, -2, 0),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 12.75F)));
            for (var kind : new PlayerPhysicsProtocol.Kind[]{PlayerPhysicsProtocol.Kind.OWNED, PlayerPhysicsProtocol.Kind.STATE}) {
                var original = packet(kind, Optional.empty(), Optional.of(snapshot), Optional.empty());
                var decoded = decodeForTest(PlayerPhysicsProtocol.encode(original));
                assertEquals(snapshot, decoded.snapshot().orElseThrow());
                assertEquals(box, decoded.snapshot().orElseThrow().placement().bounds());
                assertEquals(nominal.transport(), decoded.snapshot().orElseThrow().placement().transport());
            }
        }
    }

    @Test void applicationIdentityRetainsAnEarlierAppliedOperation() {
        var state = new PlayerPhysicsProtocol.OperationId(5, 7, 12, BoostProtocol.Operation.NONE);
        var operation = new PlayerPhysicsProtocol.OperationId(5, 6, 20, BoostProtocol.Operation.ADD);
        var application = new PlayerPhysicsProtocol.ApplicationId(state, Optional.of(operation));
        var original = packet(PlayerPhysicsProtocol.Kind.APPLY, Optional.of(application), Optional.empty(), Optional.empty());
        assertEquals(original, decodeForTest(PlayerPhysicsProtocol.encode(original)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.ApplicationId(state,
                Optional.of(new PlayerPhysicsProtocol.OperationId(6, 6, 20, BoostProtocol.Operation.ADD))));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.ApplicationId(state,
                Optional.of(new PlayerPhysicsProtocol.OperationId(5, 7, 13, BoostProtocol.Operation.ADD))));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.ApplicationId(operation, Optional.empty()));
    }

    @Test void rejectsMalformedContactPayloadWithoutInventingGround() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var state = new PlayerPhysicalSnapshot(SCOPE, 1, new PlayerBodyPlacement(new Vec3d(0, 2, 0),
                CardinalFrame.Y_POS.attitude(), shape), new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
        byte[] bytes = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.STATE,
                Optional.empty(), Optional.of(state), Optional.empty()));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(truncated));
        }
        byte[] invalidFlags = bytes.clone();
        ContactOffsets offsets = contactOffsets(bytes);
        invalidFlags[offsets.flags()] = 16;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(invalidFlags));
        byte[] invalidFall = bytes.clone();
        ByteBuffer.wrap(invalidFall).putFloat(offsets.fall(), Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(invalidFall));
    }

    @Test void geometrySuccessorCannotCarryAnOldContactObservation() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var placement = new PlayerBodyPlacement(new Vec3d(0, 2, 0), CardinalFrame.Y_POS.attitude(), shape);
        var state = new PlayerPhysicalSnapshot(SCOPE, 1, placement, new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 0)));
        assertTrue(state.successor(placement, new Vec3d(0, 0, 0)).contacts().isEmpty());
    }

    @Test void nearFootPresentationRoundTripsWithoutClaimingGround() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var state = new PlayerPhysicalSnapshot(SCOPE, 1, new PlayerBodyPlacement(new Vec3d(0, 2, 0),
                CardinalFrame.Y_POS.attitude(), shape), new Vec3d(0, 0, .25),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0, true)));
        var wire = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.STATE,
                Optional.empty(), Optional.of(state), Optional.empty()));
        var decoded = decodeForTest(wire).snapshot().orElseThrow();
        assertTrue(decoded.contacts().orElseThrow().nearFootLocomotion());
        assertFalse(decoded.contacts().orElseThrow().onGround());
        wire[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(wire),
                "v1 cannot silently discard the v2 presentation distinction");
    }

    @Test void rejectsPayloadInWrongKindAndScope() {
        var controls = controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, -1), 5);
        assertThrows(IllegalArgumentException.class,
                () -> packet(PlayerPhysicsProtocol.Kind.OFFER, Optional.empty(), Optional.empty(), Optional.of(controls)));
        assertThrows(IllegalArgumentException.class,
                () -> packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.empty(), Optional.empty()));
        var placement = new PlayerBodyPlacement(new Vec3d(0, 2, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62)));
        var foreign = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 9),
                SCOPE.sessionId(), SCOPE.dimension(), SCOPE.worldEpoch()), 1, placement, new Vec3d(0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.of(foreign), Optional.empty()));
    }

    @Test void validatesInputMagnitudeDirectionGearAndWireVersion() {
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(),
                Optional.of(controls(new Vec3d(.3, .4, 0), new Vec3d(0, 0, -1), 9)));
        assertEquals(input, decodeForTest(PlayerPhysicsProtocol.encode(input)));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(1, 1, 0), new Vec3d(0, 0, -1), 1));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, -1), 10));
        byte[] bytes = PlayerPhysicsProtocol.encode(input);
        bytes[0] = 99;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(bytes));
        bytes[0] = 1;
        bytes[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(bytes));
    }

    @Test void keyboardTravelGainRoundTripsAndRejectsInvalidCrossFieldOrLegacyWire() {
        double diagonal = Math.sqrt(2.0);
        var translation = new Vec3d(1 / diagonal, 0, 1 / diagonal);
        var keyboard = new PlayerPhysicsProtocol.Controls(translation, new Vec3d(0, 0, 1),
                false, false, false, false, 5, diagonal);
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(), Optional.of(keyboard));
        byte[] bytes = PlayerPhysicsProtocol.encode(input);
        var decoded = decodeForTest(bytes);
        assertEquals(input, decoded);
        assertEquals(diagonal, decoded.controls().orElseThrow().keyboardTravelGain(), 0);

        assertEquals(1.0, new PlayerPhysicsProtocol.Controls(translation, new Vec3d(0, 0, 1),
                false, false, false, false, 5).keyboardTravelGain(), 0,
                "the source-compatible constructor retains world-vector speed");
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Controls(
                translation, new Vec3d(0, 0, 1), false, false, false, false, 5, 0.999));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Controls(
                translation, new Vec3d(0, 0, 1), false, false, false, false, 5, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Controls(
                translation, new Vec3d(0, 0, 1), false, false, false, false, 5, diagonal + 1e-9));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Controls(
                new Vec3d(.9, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5, diagonal));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Controls(
                new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5, diagonal));

        byte[] invalidGain = bytes.clone();
        ByteBuffer.wrap(invalidGain).putDouble(113, Double.POSITIVE_INFINITY);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(invalidGain));
        bytes[0] = 3;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(bytes),
                "the v3 INPUT wire does not silently discard the new travel magnitude");
    }

    @Test void protocolSevenCarriesIndependentManualAndEffectiveBodyReferenceState() {
        var intent = PlayerPhysicsProtocol.BodyReferenceIntent.lock(12, CardinalFrame.X_POS,
                new Vec3d(0, 1, 0));
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5, 1, intent);
        var acknowledgement = new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(31, 9);
        var input = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 32, 7,
                Optional.empty(), Optional.empty(), Optional.of(controls), OptionalInt.empty(),
                Optional.empty(), Optional.of(acknowledgement));
        assertEquals(PlayerPhysicsProtocol.VERSION, Byte.toUnsignedInt(PlayerPhysicsProtocol.encode(input)[0]));
        assertEquals(input, decodeForTest(PlayerPhysicsProtocol.encode(input)));
        assertEquals(PlayerPhysicsProtocol.BodyReferenceAction.NONE,
                new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                        false, false, false, false, 5).bodyReferenceIntent().action(),
                "source-compatible controls do not invent a lock request");

        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                new PlayerBodyShape.Dimensions(.6, 1.8, 1.62));
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 9,
                new PlayerBodyPlacement(new Vec3d(2, 7, -3), CardinalFrame.X_POS.attitude(), shape),
                new Vec3d(0, 0, 0));
        var rejected = new PlayerPhysicsProtocol.BodyReferenceReceipt(13,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, 12, Optional.of(CardinalFrame.Z_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED));
        var savedManual = new PlayerBodyReference.ManualIntent(12, CardinalFrame.Z_POS, new Vec3d(0, 1, 0));
        var effectiveEnvironment = new PlayerBodyReference.State(Optional.of(savedManual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT, CardinalFrame.X_POS,
                        new Vec3d(0, 1, 0)), PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE,
                Optional.empty(), Optional.empty());
        var state = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 33, 7,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(),
                Optional.of(rejected), Optional.empty(), Optional.of(effectiveEnvironment));
        assertEquals(state, decodeForTest(PlayerPhysicsProtocol.encode(state)),
                "a rejected receipt and environment-owned geometry must retain the original manual preference independently");
        assertEquals(CardinalFrame.Z_POS, state.bodyReferenceState().orElseThrow().manualIntent().orElseThrow().frame());
        assertEquals(CardinalFrame.X_POS, state.bodyReferenceState().orElseThrow().effective().frame());
        var safeHandoffReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(13,
                PlayerPhysicsProtocol.BodyReferenceDecision.DEFERRED, 12, Optional.of(CardinalFrame.Z_POS),
                Optional.of(new Vec3d(0, 1, 0)),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        var safeState = new PlayerBodyReference.State(Optional.of(savedManual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE, CardinalFrame.Y_POS,
                        new Vec3d(0, 0, 1)), PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING,
                Optional.empty(), Optional.empty());
        var handoffPacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 34, 7,
                Optional.empty(), Optional.of(new PlayerPhysicalSnapshot(SCOPE, 10,
                new PlayerBodyPlacement(new Vec3d(2, 7, -3), CardinalFrame.Y_POS.attitude(),
                        new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                                new PlayerBodyShape.Dimensions(.6, 1.8, 1.62))), new Vec3d(0, 0, 0))),
                Optional.empty(), OptionalInt.empty(), Optional.of(safeHandoffReceipt), Optional.empty(),
                Optional.of(safeState));
        assertEquals(handoffPacket, decodeForTest(PlayerPhysicsProtocol.encode(handoffPacket)),
                "a safe handoff can acknowledge the latest request while retaining an older saved manual preference");
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.BodyReferenceReceipt(14,
                PlayerPhysicsProtocol.BodyReferenceDecision.LOCKED, 13, Optional.of(CardinalFrame.X_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.empty()),
                "LOCKED receipt identity is the accepted lock request itself");
    }

    @Test void protocolSevenRejectsMalformedBodyIntentAndAcknowledgementFields() {
        var intent = PlayerPhysicsProtocol.BodyReferenceIntent.lock(4, CardinalFrame.X_POS,
                new Vec3d(0, 1, 0));
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5, 1, intent);
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 8, 7,
                Optional.empty(), Optional.empty(), Optional.of(controls), OptionalInt.empty(),
                Optional.empty(), Optional.empty());
        byte[] wire = PlayerPhysicsProtocol.encode(packet);
        for (int length = 0; length < wire.length; length++) {
            byte[] truncated = Arrays.copyOf(wire, length);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(truncated));
        }
        byte[] badAction = wire.clone();
        badAction[129] = 99;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(badAction));
        byte[] badHeading = wire.clone();
        ByteBuffer.wrap(badHeading).putDouble(131, 1.0);
        ByteBuffer.wrap(badHeading).putDouble(139, 0.0);
        ByteBuffer.wrap(badHeading).putDouble(147, 0.0);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(badHeading));
        byte[] badAckTag = wire.clone();
        badAckTag[badAckTag.length - 6] = 2;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(badAckTag));
    }

    @Test void antipodalReferenceTransferUsesTheAcceptedTangentAsItsRotationAxis() {
        Vec3d acceptedTangent = new Vec3d(0, 0, 1);
        assertEquals(acceptedTangent, PlayerBodyReference.transportHeading(
                CardinalFrame.Y_POS, CardinalFrame.Y_NEG, acceptedTangent));
        assertEquals(acceptedTangent, PlayerBodyReference.transportHeading(
                CardinalFrame.Y_POS, CardinalFrame.Y_NEG,
                PlayerBodyReference.transportHeading(CardinalFrame.Y_POS, CardinalFrame.Y_NEG, acceptedTangent)),
                "the 180-degree tie must be deterministic and must not consult current head yaw");
    }

    @Test void malformedTagsAndNonFiniteWireInputAreRejectedBeforeUse() {
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(),
                Optional.of(controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, -1), 1)));
        byte[] badBoolean = PlayerPhysicsProtocol.encode(input);
        badBoolean[62] = 2;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(badBoolean));
        byte[] badFlags = PlayerPhysicsProtocol.encode(input);
        badFlags[111] = 16;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(badFlags));
        byte[] nan = PlayerPhysicsProtocol.encode(input);
        java.nio.ByteBuffer.wrap(nan).putDouble(63, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(nan));
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(new byte[PlayerPhysicsProtocol.MAX_BYTES + 1]));
    }

    @Test void mapsOnlyActualApplicationIdentityAndPreservesWireDirection() {
        var add = BoostProtocol.state(17, 2, 3, -1, BoostProtocol.Mode.ACTIVE, 5, 70,
                BoostProtocol.Operation.ADD, new Vec3(2, 0, 0), BoostProtocol.Reason.NONE);
        var id = PlayerPhysicsProtocol.ApplicationId.from(new BoostProtocol.StateApplication(add, add));
        assertEquals(new PlayerPhysicsProtocol.OperationId(17, 2, 3, BoostProtocol.Operation.ADD), id.state());
        assertEquals(Optional.of(id.state()), id.lastVelocityOperation());
        assertFalse(packet(PlayerPhysicsProtocol.Kind.APPLY, Optional.of(id), Optional.empty(), Optional.empty()).clientbound());
        assertTrue(packet(PlayerPhysicsProtocol.Kind.OFFER, Optional.empty(), Optional.empty(), Optional.empty()).clientbound());
    }

    @Test
    void freeNominalAndAxisProofsRoundTripThroughTheProtocolWithoutReclassifyingCenters() {
        for (PlayerBodyPlacement placement : new PlayerBodyPlacement[]{translatedNominalPlacement(), axisPlacement()}) {
            var snapshot = new PlayerPhysicalSnapshot(SCOPE, 17, placement, new Vec3d(3.25, -4.5, 8.75),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 12.5F, true)));
            var original = packet(PlayerPhysicsProtocol.Kind.OWNED, Optional.empty(), Optional.of(snapshot), Optional.empty());
            byte[] wire = PlayerPhysicsProtocol.encode(original);
            assertEquals(PlayerPhysicsProtocol.VERSION, Byte.toUnsignedInt(wire[0]));

            IllegalArgumentException noBudget = assertThrows(IllegalArgumentException.class,
                    () -> decodeForTest(wire),
                    "the legacy budgetless overload must never invent fresh Free decode work");
            assertEquals("Free placement decode requires a caller-owned decode budget", noBudget.getMessage());
            StoredDyadic.Work callerWork = new StoredDyadic.Work();
            var decoded = decodeForTest(wire,
                    new PhysicsDecodeBudget(wire.length, callerWork));
            var actual = decoded.snapshot().orElseThrow();
            PlayerBodyPlacement restored = actual.placement();

            assertEquals(snapshot.revision(), actual.revision());
            assertEquals(snapshot.scope(), actual.scope());
            assertRawVector(placement.center(), restored.center());
            assertEquals(Double.doubleToRawLongBits(placement.attitude().x()),
                    Double.doubleToRawLongBits(restored.attitude().x()));
            assertEquals(Double.doubleToRawLongBits(placement.attitude().y()),
                    Double.doubleToRawLongBits(restored.attitude().y()));
            assertEquals(Double.doubleToRawLongBits(placement.attitude().z()),
                    Double.doubleToRawLongBits(restored.attitude().z()));
            assertEquals(Double.doubleToRawLongBits(placement.attitude().w()),
                    Double.doubleToRawLongBits(restored.attitude().w()));
            assertEquals(placement.shape(), restored.shape());
            assertEquals(placement.bounds(), restored.bounds());
            assertRawVector(placement.transport(), restored.transport());
            assertEquals(snapshot.worldVelocity(), actual.worldVelocity());
            assertEquals(snapshot.contacts(), actual.contacts());
            assertEquals(PlayerPlacementCodec.exportFreeV1(placement), PlayerPlacementCodec.exportFreeV1(restored),
                    "the original H_s, C_ref and proof witness survive the actual export/decode/export path");
            assertTrue(callerWork.units() > 0);
        }
    }

    @Test
    void largestLegalOwnedFreeAxisPacketIsExactlyThreeHundredNinetyBytes() {
        PlayerBodyPlacement placement = axisPlacement();
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 99, placement, new Vec3d(1, -2, 3),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true, true, true, 42.0F, true)));
        var application = new PlayerPhysicsProtocol.ApplicationId(
                new PlayerPhysicsProtocol.OperationId(51, 8, 12, BoostProtocol.Operation.NONE),
                Optional.of(new PlayerPhysicsProtocol.OperationId(51, 8, 11, BoostProtocol.Operation.ADD)));
        var largest = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, SCOPE, 100, 99,
                Optional.of(application), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        byte[] wire = PlayerPhysicsProtocol.encode(largest);
        assertEquals(397, wire.length, "this is a real maximum-axis-proof/application-identity Owned frame");
        var boundary = new PlayerPhysicsProtocol.PredictionBoundary(1, 1, 0, 1, 0, 1, 0, 1, 0,
                PlayerPhysicsProtocol.JumpDecision.none(), PlayerPhysicsProtocol.StepOutcome.APPLIED);
        var withTiming = new PlayerPhysicsProtocol.Packet(largest.kind(), largest.scope(), largest.sequence(),
                largest.offerSequence(), largest.application(), largest.snapshot(), largest.controls(), largest.nativeTeleportId(),
                largest.bodyReferenceReceipt(), largest.bodyReferenceAcknowledgement(), largest.bodyReferenceState(),
                Optional.empty(), Optional.of(boundary));
        byte[] maximum = PlayerPhysicsProtocol.encode(withTiming);
        assertEquals(483, maximum.length, "Free without a seed is not the largest legal Ready packet");
        assertEquals(withTiming, PlayerPhysicsProtocol.decode(maximum, new PhysicsDecodeBudget(maximum.length, new StoredDyadic.Work())));

        var nativeRaw = new PlayerBodyShape.Dimensions(.6000000238418579, .5999999642372131, .4);
        var physical = placement.shape().dimensions();
        var profileShape = new PlayerBodyShape.FreeCapsule(nativeRaw,
                new PlayerBodyShape.CollisionProfile(physical.width(), physical.height()),
                Optional.of(new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1,
                        placement.center(), placement.eye(), placement.attitude())));
        var profiledPlacement = PlayerBodyPlacement.fromAcceptedFreeGeometry(placement.center(), placement.attitude(),
                profileShape, placement.storedCapsuleGeometry().withEyeMetadata(profileShape.geometryDimensions()),
                placement.bounds(), placement.transport(), new StoredDyadic.Work());
        var profiledSnapshot = new PlayerPhysicalSnapshot(snapshot.scope(), snapshot.revision(), profiledPlacement,
                snapshot.worldVelocity(), snapshot.contacts());
        var profiled = new PlayerPhysicsProtocol.Packet(withTiming.kind(), withTiming.scope(), withTiming.sequence(),
                withTiming.offerSequence(), withTiming.application(), Optional.of(profiledSnapshot), withTiming.controls(),
                withTiming.nativeTeleportId(), withTiming.bodyReferenceReceipt(), withTiming.bodyReferenceAcknowledgement(),
                withTiming.bodyReferenceState(), Optional.empty(), Optional.of(boundary));
        byte[] fullProfile = PlayerPhysicsProtocol.encode(profiled);
        assertEquals(580, fullProfile.length, "actual full axis proof, application, boundary and eye-profile payload");
        assertTrue(fullProfile.length <= PlayerPhysicsProtocol.MAX_BYTES);
        assertEquals(profiled, decodeForTest(fullProfile, new PhysicsDecodeBudget(fullProfile.length, new StoredDyadic.Work())));
        var maximal = new PlayerPhysicsProtocol.Packet(profiled.kind(),profiled.scope(),profiled.sequence(),
                profiled.offerSequence(),profiled.application(),profiled.snapshot(),profiled.controls(),
                profiled.nativeTeleportId(),profiled.bodyReferenceReceipt(),profiled.bodyReferenceAcknowledgement(),
                profiled.bodyReferenceState(),Optional.empty(),profiled.predictionBoundary(),
                Optional.of(new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_SHAPE)),
                Optional.of(new PlayerPhysicsProtocol.Anchored(new PlayerPhysicsProtocol.PredictionBootstrap(7,13),
                        snapshot.revision())),Optional.empty(),
                Optional.of(new PlayerMobilityIntent.State(15,PlayerMobilityIntent.Mode.FLIGHT,
                        PlayerMobilityIntent.Hover.OFF,PlayerMobilityIntent.EffectiveMode.FREE,
                        PlayerMobilityIntent.HoverStatus.OFF,PlayerMobilityIntent.Reason.NONE,false,0)));
        byte[] maximalWire = PlayerPhysicsProtocol.encode(maximal);
        assertEquals(629,maximalWire.length,"all legal profiled Free authority fields coexist without narrowing the old contract");
        assertEquals(PlayerPhysicsProtocol.MAX_BYTES,maximalWire.length);
        assertEquals(maximal,decodeForTest(maximalWire, new PhysicsDecodeBudget(maximalWire.length, new StoredDyadic.Work())));
        byte[] oldV19 = maximalWire.clone(); oldV19[0] = 19;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(oldV19));
        byte[] oversized = java.util.Arrays.copyOf(fullProfile, PlayerPhysicsProtocol.MAX_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(oversized));

        assertTrue(wire.length <= PlayerPhysicsProtocol.MAX_BYTES);
        var restored = decodeForTest(wire,
                new PhysicsDecodeBudget(wire.length, new StoredDyadic.Work()));
        assertEquals(largest, restored);
    }

    @Test
    void largestLegalFreeStatePacketRoundTripsAtThreeHundredEightyNineBytes() {
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 101, axisPlacement(), new Vec3d(1, -2, 3),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true, true, true, 42.0F, true)));
        var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(12,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, 11,
                Optional.of(CardinalFrame.Y_POS), Optional.of(new Vec3d(0, 0, 1)),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.PATH_BLOCKED));
        var largest = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 102, 99,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.of(42),
                Optional.of(receipt), Optional.empty(), Optional.empty());

        byte[] wire = PlayerPhysicsProtocol.encode(largest);
        assertEquals(396, wire.length, "native correction, full receipt, Free proof and contacts coexist legally");
        assertTrue(wire.length <= PlayerPhysicsProtocol.MAX_BYTES);
        StoredDyadic.Work callerWork = new StoredDyadic.Work();
        var restored = decodeForTest(wire, new PhysicsDecodeBudget(wire.length, callerWork));
        assertEquals(largest, restored);
        assertEquals(OptionalInt.of(42), restored.nativeTeleportId());
        assertEquals(Optional.of(receipt), restored.bodyReferenceReceipt());
        assertEquals(snapshot.contacts(), restored.snapshot().orElseThrow().contacts());
        assertTrue(callerWork.units() > 0);
    }

    @Test
    void malformedFreeFramesNeverEnterTheProofRestoreBoundary() {
        byte[] valid = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OWNED,
                Optional.empty(), Optional.of(new PlayerPhysicalSnapshot(SCOPE, 4, axisPlacement(),
                        new Vec3d(0.25, 0.5, 0.75), Optional.of(
                        new PlayerPhysicalSnapshot.Contacts(false, false, false, 0.0F)))), Optional.empty()));
        int snapshot = freeSnapshotOffset();
        int proofTag = snapshot + 209;

        byte[] wrongVersion = valid.clone();
        wrongVersion[0] = 6;
        byte[] wrongKind = valid.clone();
        wrongKind[1] = (byte) PlayerPhysicsProtocol.Kind.OFFER.ordinal();
        byte[] unknownKind = valid.clone();
        unknownKind[1] = 99;
        byte[] wrongShape = valid.clone();
        wrongShape[snapshot + 8] = 9;
        byte[] unknownProof = valid.clone();
        unknownProof[proofTag] = 9;
        byte[] invalidAxis = valid.clone();
        invalidAxis[proofTag + 25] = 3;
        byte[] invalidSign = valid.clone();
        invalidSign[proofTag + 26] = 0;
        byte[] badLateContactTag = valid.clone();
        badLateContactTag[contactOffsets(valid).tag()] = 2;
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

        for (byte[] malformed : new byte[][]{wrongVersion, wrongKind, unknownKind, wrongShape, unknownProof,
                invalidAxis, invalidSign, badLateContactTag, trailing}) {
            assertNoFreeRestore(malformed);
        }
        for (int length = 0; length < valid.length; length++) {
            assertNoFreeRestore(Arrays.copyOf(valid, length));
        }
        assertNoFreeRestore(new byte[0]);
        StoredDyadic.Work mismatchedBudgetWork = new StoredDyadic.Work();
        PhysicsDecodeBudget mismatchedBudget = new PhysicsDecodeBudget(valid.length + 1, mismatchedBudgetWork);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(valid, mismatchedBudget));
        assertEquals(0, mismatchedBudget.restoreAttempts());
        assertEquals(0, mismatchedBudget.rawFieldReads());
        assertEquals(0, mismatchedBudget.quaternionReconstructionCalls());
        assertEquals(0, mismatchedBudgetWork.units());
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Packet(
                PlayerPhysicsProtocol.Kind.OWNED, SCOPE, 1, 1, Optional.empty(),
                Optional.of(new PlayerPhysicalSnapshot(SCOPE, 1, axisPlacement(), new Vec3d(0, 0, 0))),
                Optional.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new PlayerBodyReference.State(Optional.empty(),
                        new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                                CardinalFrame.Y_POS, new Vec3d(0, 0, 1)),
                        PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty()))),
                "Free placement cannot claim the cardinal-only effective body-reference state");
    }

    @Test
    void inspectionSeparatesFreeStructureFromProofRestoration() {
        byte[] valid = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OWNED,
                Optional.empty(), Optional.of(new PlayerPhysicalSnapshot(SCOPE, 5, axisPlacement(),
                        new Vec3d(0, 0, 0))), Optional.empty()));
        int proofTag = freeSnapshotOffset() + 209;
        byte[] invalidSupport = valid.clone();
        ByteBuffer.wrap(invalidSupport).putLong(proofTag + 27,
                Double.doubleToRawLongBits(Math.nextUp(0.125)));

        var inspected = PlayerPhysicsProtocol.inspectFrame(invalidSupport);
        assertEquals(PlayerPhysicsProtocol.Kind.OWNED, inspected.kind());
        assertEquals(SCOPE, inspected.scope());
        assertEquals(invalidSupport.length, inspected.encodedPacketBytes());
        assertTrue(inspected.hasFreePlacement(), "the Free marker is derived from the parsed frame subtype");
        assertFalse(inspected.noFreeRecoveryControl());

        StoredDyadic.Work work = new StoredDyadic.Work();
        assertEquals(0, work.units(), "structural inspection must not attempt proof restoration");
        assertThrows(IllegalArgumentException.class, () -> inspected.materializeWithoutFreeProof());
        assertEquals(0, work.units(), "the budgetless path must not create replacement Work");
        PhysicsDecodeBudget budget = new PhysicsDecodeBudget(invalidSupport.length, work);
        assertThrows(IllegalArgumentException.class, () -> inspected.materialize(budget));
        assertEquals(1, budget.restoreAttempts());
        assertTrue(work.units() > 0, "the real invalid proof is rejected after charged restoration begins");
    }

    @Test
    void inspectionRejectsMalformedFreeStructureBeforeReturningAFrame() {
        byte[] valid = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OWNED,
                Optional.empty(), Optional.of(new PlayerPhysicalSnapshot(SCOPE, 6, axisPlacement(),
                        new Vec3d(0, 0, 0))), Optional.empty()));
        int proofTag = freeSnapshotOffset() + 209;
        byte[] invalidAxis = valid.clone();
        invalidAxis[proofTag + 25] = 3;
        byte[] invalidSign = valid.clone();
        invalidSign[proofTag + 26] = 0;
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);

        for (byte[] malformed : new byte[][]{invalidAxis, invalidSign, truncated, trailing}) {
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.inspectFrame(malformed));
        }
    }

    @Test
    void recoveryControlClassificationRequiresACompleteValidFrame() {
        var application = new PlayerPhysicsProtocol.ApplicationId(
                new PlayerPhysicsProtocol.OperationId(51, 8, 12, BoostProtocol.Operation.NONE), Optional.empty());
        var offer = packet(PlayerPhysicsProtocol.Kind.OFFER, Optional.empty(), Optional.empty(), Optional.empty());
        var apply = packet(PlayerPhysicsProtocol.Kind.APPLY, Optional.of(application), Optional.empty(), Optional.empty());
        var nativeControl = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                SCOPE,1,1,new PlayerPhysicsProtocol.NativeHandoff(1,0,new Vec3d(0,0,0)));

        for (var control : new PlayerPhysicsProtocol.Packet[]{offer, apply, nativeControl}) {
            var inspected = PlayerPhysicsProtocol.inspectFrame(PlayerPhysicsProtocol.encode(control));
            assertEquals(control.kind(), inspected.kind());
            assertFalse(inspected.hasFreePlacement());
            assertTrue(inspected.noFreeRecoveryControl());
            assertEquals(control, inspected.materializeWithoutFreeProof());
        }

        byte[] fakeTrailingControl = Arrays.copyOf(PlayerPhysicsProtocol.encode(offer),
                PlayerPhysicsProtocol.encode(offer).length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysicsProtocol.inspectFrame(fakeTrailingControl));

        byte[] wrongApplicationKind = PlayerPhysicsProtocol.encode(apply);
        wrongApplicationKind[1] = (byte) PlayerPhysicsProtocol.Kind.OFFER.ordinal();
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysicsProtocol.inspectFrame(wrongApplicationKind));
    }

    @Test
    void inspectedCardinalFrameIsStableAfterTheInputBytesChange() {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F));
        var placement = PlayerBodyPlacement.fromTransport(new Vec3d(8, 65, 9), CardinalFrame.Y_POS.attitude(), shape);
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 9, placement, new Vec3d(1, 2, 3));
        var original = packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.of(snapshot), Optional.empty());
        byte[] encoded = PlayerPhysicsProtocol.encode(original);
        assertEquals(original, decodeForTest(encoded));
        var inspected = PlayerPhysicsProtocol.inspectFrame(encoded);
        assertFalse(inspected.hasFreePlacement(), "Cardinal snapshots do not count as Free placements");
        assertFalse(inspected.noFreeRecoveryControl());

        Arrays.fill(encoded, (byte) 0);
        assertEquals(original, inspected.materializeWithoutFreeProof());
    }

    @Test
    void inspectedProofFailuresKeepCallerWorkAcrossLaterRestoration() {
        var expected = packet(PlayerPhysicsProtocol.Kind.OWNED, Optional.empty(),
                Optional.of(new PlayerPhysicalSnapshot(SCOPE, 7, axisPlacement(), new Vec3d(0, 0, 0))), Optional.empty());
        byte[] valid = PlayerPhysicsProtocol.encode(expected);
        int snapshot = freeSnapshotOffset();
        int proofTag = snapshot + 209;
        StoredDyadic.Work sharedWork = new StoredDyadic.Work();

        byte[] invalidSupport = valid.clone();
        ByteBuffer.wrap(invalidSupport).putLong(proofTag + 27,
                Double.doubleToRawLongBits(Math.nextUp(0.125)));
        var first = PlayerPhysicsProtocol.inspectFrame(invalidSupport);
        assertTrue(first.hasFreePlacement());
        assertEquals(0, sharedWork.units());
        PhysicsDecodeBudget firstBudget = new PhysicsDecodeBudget(invalidSupport.length, sharedWork);
        assertThrows(IllegalArgumentException.class, () -> first.materialize(firstBudget));
        long afterFirstFailure = sharedWork.units();
        assertTrue(afterFirstFailure > 0);
        assertEquals(1, firstBudget.restoreAttempts());

        byte[] invalidHalfLine = valid.clone();
        int halfLineY = snapshot + 97;
        ByteBuffer second = ByteBuffer.wrap(invalidHalfLine);
        second.putLong(halfLineY, Double.doubleToRawLongBits(
                Math.nextUp(Double.longBitsToDouble(second.getLong(halfLineY)))));
        var secondInspection = PlayerPhysicsProtocol.inspectFrame(invalidHalfLine);
        PhysicsDecodeBudget secondBudget = new PhysicsDecodeBudget(invalidHalfLine.length, sharedWork);
        assertThrows(IllegalArgumentException.class, () -> secondInspection.materialize(secondBudget));
        long afterSecondFailure = sharedWork.units();
        assertTrue(afterSecondFailure > afterFirstFailure);
        assertEquals(1, secondBudget.restoreAttempts());

        var validInspection = PlayerPhysicsProtocol.inspectFrame(valid);
        PhysicsDecodeBudget successBudget = new PhysicsDecodeBudget(valid.length, sharedWork);
        assertEquals(expected, validInspection.materialize(successBudget));
        assertTrue(sharedWork.units() > afterSecondFailure);
        assertEquals(1, successBudget.restoreAttempts());
    }

    @Test
    void completeInvalidFreeProofChargesAndKeepsCallerWorkAcrossPacketAttempts() {
        byte[] valid = PlayerPhysicsProtocol.encode(packet(PlayerPhysicsProtocol.Kind.OWNED,
                Optional.empty(), Optional.of(new PlayerPhysicalSnapshot(SCOPE, 5, axisPlacement(),
                        new Vec3d(0, 0, 0))), Optional.empty()));
        int snapshot = freeSnapshotOffset();
        int proofTag = snapshot + 209;
        StoredDyadic.Work sharedWork = new StoredDyadic.Work();

        byte[] invalidSupport = valid.clone();
        ByteBuffer first = ByteBuffer.wrap(invalidSupport);
        first.putLong(proofTag + 27, Double.doubleToRawLongBits(Math.nextUp(0.125)));
        PhysicsDecodeBudget firstBudget = new PhysicsDecodeBudget(invalidSupport.length, sharedWork);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(invalidSupport, firstBudget));
        long afterFirstFailure = sharedWork.units();
        assertTrue(afterFirstFailure > 0, "proof validation happens after charged raw parsing and exact-Q reservation");
        assertEquals(1, firstBudget.restoreAttempts());
        assertTrue(firstBudget.rawFieldReads() > 0);
        assertEquals(1, firstBudget.quaternionReconstructionCalls());
        assertEquals(108, firstBudget.reservedQuaternionVerificationSlots());

        byte[] invalidHalfLine = valid.clone();
        int halfLineY = snapshot + 97;
        ByteBuffer second = ByteBuffer.wrap(invalidHalfLine);
        double storedY = Double.longBitsToDouble(second.getLong(halfLineY));
        second.putLong(halfLineY, Double.doubleToRawLongBits(Math.nextUp(storedY)));
        PhysicsDecodeBudget secondBudget = new PhysicsDecodeBudget(invalidHalfLine.length, sharedWork);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(invalidHalfLine, secondBudget));
        assertTrue(sharedWork.units() > afterFirstFailure, "a later failed packet charges the same Work object");
        assertEquals(1, secondBudget.restoreAttempts());
        assertTrue(secondBudget.rawFieldReads() > 0);
        assertEquals(1, secondBudget.quaternionReconstructionCalls());
        assertEquals(108, secondBudget.reservedQuaternionVerificationSlots());
        long afterSecondFailure = sharedWork.units();
        assertTrue(afterSecondFailure > afterFirstFailure);

        PhysicsDecodeBudget successBudget = new PhysicsDecodeBudget(valid.length, sharedWork);
        assertEquals(packet(PlayerPhysicsProtocol.Kind.OWNED, Optional.empty(), Optional.of(
                new PlayerPhysicalSnapshot(SCOPE, 5, axisPlacement(), new Vec3d(0, 0, 0))), Optional.empty()),
                decodeForTest(valid, successBudget));
        assertTrue(sharedWork.units() > afterSecondFailure, "the valid third frame shares work with earlier failures");
        assertEquals(1, successBudget.restoreAttempts());
        assertTrue(successBudget.rawFieldReads() > 0);
        assertEquals(1, successBudget.quaternionReconstructionCalls());
        assertEquals(108, successBudget.reservedQuaternionVerificationSlots());
    }

    @Test void mobilityIntentAndAcceptedEffectiveStateUseRealV14WireAndStrictTags() {
        var inputControls = controls(new Vec3d(0,0,0), new Vec3d(0,0,1), 5).withMobility(
                new PlayerMobilityIntent.Input(Optional.of(new PlayerMobilityIntent.Request(9,
                        PlayerMobilityIntent.Mode.FLIGHT, PlayerMobilityIntent.Hover.OFF)), 7, true, true));
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(), Optional.of(inputControls));
        byte[] inputWire = PlayerPhysicsProtocol.encode(input);
        assertEquals(20, Byte.toUnsignedInt(inputWire[0]));
        assertEquals(input, decodeForTest(inputWire));
        // Request-present/id/mode/hover/press/held/focused precede the seven absent INPUT tail tags.
        int mobility = inputWire.length - 28;
        for (int offset : new int[]{0,9,10,19,20}) {
            byte[] malformed = inputWire.clone(); malformed[mobility + offset] = 127;
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(malformed));
        }
        byte[] negativePress = inputWire.clone();
        ByteBuffer.wrap(negativePress).putLong(mobility + 11, -1);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(negativePress));
        for (var mode : PlayerMobilityIntent.Mode.values()) for (var hover : PlayerMobilityIntent.Hover.values()) {
            var base = seededPacket(CardinalFrame.Y_POS,
                    new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE), false);
            var accepted = new PlayerMobilityIntent.State(9, mode, hover, PlayerMobilityIntent.EffectiveMode.WALK,
                    PlayerMobilityIntent.HoverStatus.REJECTED, PlayerMobilityIntent.Reason.CONTROL_UNAVAILABLE, mode == PlayerMobilityIntent.Mode.FLIGHT,
                    mode == PlayerMobilityIntent.Mode.FLIGHT ? 7 : 0);
            var response = new PlayerPhysicsProtocol.Packet(base.kind(),base.scope(),base.sequence(),base.offerSequence(),
                    base.application(),base.snapshot(),base.controls(),base.nativeTeleportId(),base.bodyReferenceReceipt(),
                    base.bodyReferenceAcknowledgement(),base.bodyReferenceState(),base.predictionInput(),base.predictionBoundary(),
                    base.predictionSeed(),base.predictionPhase(),base.predictionRequest(),Optional.of(accepted));
            byte[] wire = PlayerPhysicsProtocol.encode(response);
            assertEquals(response, decodeForTest(wire));
            for (int fromEnd : new int[]{15,14,13,12,11,10}) {
                byte[] malformed = wire.clone(); malformed[wire.length-fromEnd] = 127;
                assertThrows(IllegalArgumentException.class, () -> decodeForTest(malformed));
            }
            byte[] legacy = wire.clone(); legacy[0] = 12;
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(legacy));
        }
    }
    @Test void predictionSeedReadyRoundTripsEveryFrameAndBindingPolicy() {
        for (var frame : CardinalFrame.values()) for (Optional<GravityMode> typedMode :
                java.util.List.of(Optional.<GravityMode>empty(), Optional.of(GravityMode.REPLACE), Optional.of(GravityMode.ADDITIVE))) {
            for (boolean active : new boolean[] {false, true}) {
                if (active && typedMode.isEmpty()) continue;
                var ready = new CardinalPredictionSeed.Ready(9, 987654, frame,
                        new Vec3d(-0.0, -32, .125), typedMode, active, true, true, true, 80);
                var packet = seededPacket(frame, ready, false);
                assertEquals(packet, decodeForTest(PlayerPhysicsProtocol.encode(packet)));
            }
        }
    }

    @Test void mobilityAndPauseRearmRequestsShareOneV14FrameWithoutLosingEitherPayload() {
        var bootstrap = new PlayerPhysicsProtocol.PredictionBootstrap(8, 9);
        var mobility = new PlayerMobilityIntent.Input(Optional.of(new PlayerMobilityIntent.Request(15,
                PlayerMobilityIntent.Mode.FLIGHT, PlayerMobilityIntent.Hover.OFF)), 7, true, true);
        var controls = controls(new Vec3d(0,0,0), new Vec3d(0,0,1), 5).withMobility(mobility);
        for (var action : PlayerPhysicsProtocol.PredictionRequestAction.values()) {
            var request = new PlayerPhysicsProtocol.PredictionRequest(12, bootstrap, action,
                    new PlayerPhysicsProtocol.PredictionRefusal(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,
                            PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT));
            var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 10, 7,
                    Optional.empty(), Optional.empty(), Optional.of(controls), OptionalInt.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(request), Optional.empty());
            byte[] wire = PlayerPhysicsProtocol.encode(packet);
            assertEquals(20, Byte.toUnsignedInt(wire[0]));
            var decoded = decodeForTest(wire);
            assertEquals(packet, decoded);
            assertEquals(mobility, decoded.controls().orElseThrow().mobility());
            assertEquals(request, decoded.predictionRequest().orElseThrow());
            byte[] previousVersion = wire.clone(); previousVersion[0] = 13;
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(previousVersion));
        }
    }

    @Test void typedSuspensionRequestAndReceiptAreCanonicalAndBounded() {
        var bootstrap=new PlayerPhysicsProtocol.PredictionBootstrap(8,9);
        var refusal=new PlayerPhysicsProtocol.PredictionRefusal(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,
                PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT);
        for(var action:PlayerPhysicsProtocol.PredictionRequestAction.values()) {
            var request=new PlayerPhysicsProtocol.PredictionRequest(12,bootstrap,action,refusal);
            var input=new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT,SCOPE,10,7,Optional.empty(),Optional.empty(),
                    Optional.of(controls(new Vec3d(0,0,0),new Vec3d(0,0,1),5)),OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                    Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(request));
            var bytes=PlayerPhysicsProtocol.encode(input); assertEquals(input,decodeForTest(bytes));
            byte[] bad=bytes.clone();bad[bad.length-2]=127;assertThrows(IllegalArgumentException.class,()->decodeForTest(bad));
            assertTrue(bytes.length<PlayerPhysicsProtocol.MAX_BYTES);
            assertThrows(IllegalArgumentException.class,()->new PlayerPhysicsProtocol.PredictionRequest(0,bootstrap,action,refusal));
        }
        var seed=new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT);
        var template=seededPacket(CardinalFrame.X_POS,seed,false);
        var receipt=new PlayerPhysicsProtocol.Packet(template.kind(),template.scope(),template.sequence(),template.offerSequence(),template.application(),
                template.snapshot(),template.controls(),template.nativeTeleportId(),template.bodyReferenceReceipt(),template.bodyReferenceAcknowledgement(),
                template.bodyReferenceState(),Optional.empty(),Optional.empty(),template.predictionSeed(),
                Optional.of(new PlayerPhysicsProtocol.PredictionSuspended(9,bootstrap,12)), Optional.empty(),
                Optional.of(new PlayerMobilityIntent.State(15,PlayerMobilityIntent.Mode.FLIGHT,PlayerMobilityIntent.Hover.OFF,
                        PlayerMobilityIntent.EffectiveMode.WALK,PlayerMobilityIntent.HoverStatus.REJECTED,
                        PlayerMobilityIntent.Reason.CONTROL_UNAVAILABLE,false,0)));
        assertEquals(receipt,decodeForTest(PlayerPhysicsProtocol.encode(receipt)));
        var old=PlayerPhysicsProtocol.encode(receipt);old[0]=12;assertThrows(IllegalArgumentException.class,()->decodeForTest(old));
    }

    @Test void predictionSeedReasonsRoundTripWithoutLeakingInternalCause() {
        for (var reason : CardinalPredictionSeed.Reason.values()) {
            var unsupported = seededPacket(CardinalFrame.X_POS, new CardinalPredictionSeed.Unsupported(reason), false);
            assertEquals(unsupported, decodeForTest(PlayerPhysicsProtocol.encode(unsupported)));
            var internal = new CardinalPredictionSeed.Unavailable(reason,
                    Optional.of(new IllegalStateException("private diagnostic must stay server side")));
            var sanitized = new CardinalPredictionSeed.Unavailable(reason, Optional.empty());
            byte[] wire = PlayerPhysicsProtocol.encode(seededPacket(CardinalFrame.X_POS, internal, false));
            assertArrayEquals(PlayerPhysicsProtocol.encode(seededPacket(CardinalFrame.X_POS, sanitized, false)), wire);
            assertEquals(sanitized, decodeForTest(wire).predictionSeed().orElseThrow());
        }
    }

    @Test void predictionSeedMaximumLegalPacketAndMalformedWireAreBounded() {
        var ready = new CardinalPredictionSeed.Ready(9, 987654, CardinalFrame.X_POS,
                new Vec3d(1, -32, .125), Optional.of(GravityMode.REPLACE), true, true, true, true, 80);
        var packet = seededPacket(CardinalFrame.X_POS, ready, true);
        byte[] wire = PlayerPhysicsProtocol.encode(packet);
        assertEquals(542, wire.length);
        assertTrue(wire.length <= PlayerPhysicsProtocol.MAX_BYTES);
        assertEquals(packet, decodeForTest(wire));
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(Arrays.copyOf(wire, wire.length + 1)));
        for (int length = 0; length < wire.length; length++) {
            byte[] truncated = Arrays.copyOf(wire, length);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(truncated));
        }
        int start = wire.length - 2 - 54 - 26 - 23;
        for (int[] mutation : new int[][] {{0,2},{1,3},{18,127},{43,2},{44,127},{45,16}}) {
            byte[] bad = wire.clone(); bad[start + mutation[0]] = (byte) mutation[1];
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(bad));
        }
        for (int offset : new int[] {2,10}) {
            byte[] bad = wire.clone(); ByteBuffer.wrap(bad).putLong(start + offset, -1);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(bad));
        }
        for (int offset : new int[] {19,27,35,46}) {
            byte[] bad = wire.clone(); ByteBuffer.wrap(bad).putDouble(start + offset, Double.NaN);
            assertThrows(IllegalArgumentException.class, () -> decodeForTest(bad));
        }
        byte[] old = wire.clone(); old[0] = 10;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(old));
        byte[] wrongRevision = wire.clone(); ByteBuffer.wrap(wrongRevision).putLong(start + 2, 10);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(wrongRevision));
        byte[] wrongFrame = wire.clone(); wrongFrame[start + 18] = (byte) CardinalFrame.Y_POS.ordinal();
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(wrongFrame));
        byte[] reason = PlayerPhysicsProtocol.encode(seededPacket(CardinalFrame.X_POS,
                new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE), false));
        reason[reason.length - 4] = 127;
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(reason));
    }

    @Test void predictionSeedRequiresOwnedSnapshotAndMatchingCardinalIdentity() {
        var ready = new CardinalPredictionSeed.Ready(9, 1, CardinalFrame.X_POS,
                new Vec3d(0, -32, 0), Optional.empty(), false, false, false, true, 80);
        assertThrows(IllegalArgumentException.class, () -> seededPacket(CardinalFrame.Y_POS, ready, false));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Packet(
                PlayerPhysicsProtocol.Kind.INPUT, SCOPE, 1, 7, Optional.empty(), Optional.empty(),
                Optional.of(controls(new Vec3d(0,0,0), new Vec3d(0,0,1), 5)), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(ready)));
        var free = new PlayerPhysicalSnapshot(SCOPE, 9, axisPlacement(), new Vec3d(0,0,0));
        assertThrows(IllegalArgumentException.class, () -> new PlayerPhysicsProtocol.Packet(
                PlayerPhysicsProtocol.Kind.OWNED, SCOPE, 1, 7, Optional.empty(), Optional.of(free), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(ready)));
    }

    private static PlayerPhysicsProtocol.Packet seededPacket(CardinalFrame frame, CardinalPredictionSeed seed,
                                                               boolean maximum) {
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, 9,
                new PlayerBodyPlacement(new Vec3d(2,7,-3), frame.attitude(),
                        new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6,1.8,1.62))),
                new Vec3d(1,-2,3), Optional.of(new PlayerPhysicalSnapshot.Contacts(true,true,true,42,true)));
        var application = new PlayerPhysicsProtocol.ApplicationId(
                new PlayerPhysicsProtocol.OperationId(51,8,12,BoostProtocol.Operation.NONE),
                Optional.of(new PlayerPhysicsProtocol.OperationId(51,8,11,BoostProtocol.Operation.ADD)));
        var reference = new PlayerBodyReference.State(
                Optional.of(new PlayerBodyReference.ManualIntent(12, CardinalFrame.Z_POS,new Vec3d(0,1,0))),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,frame,
                        frame == CardinalFrame.Y_POS || frame == CardinalFrame.Y_NEG ? new Vec3d(0,0,1) : new Vec3d(0,1,0)),
                PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED, Optional.of(CardinalFrame.Z_POS),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED));
        var boundary = new PlayerPhysicsProtocol.PredictionBoundary(1,1,0,1,0,1,0,1,0,
                PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.APPLIED);
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED,SCOPE,100,99,
                maximum ? Optional.of(application) : Optional.empty(),Optional.of(snapshot),Optional.empty(),
                OptionalInt.empty(),Optional.empty(),Optional.empty(),maximum ? Optional.of(reference) : Optional.empty(),
                Optional.empty(),maximum ? Optional.of(boundary) : Optional.empty(),Optional.of(seed), maximum
                ? Optional.of(new PlayerPhysicsProtocol.Anchored(new PlayerPhysicsProtocol.PredictionBootstrap(1,0),snapshot.revision()))
                : Optional.empty(), Optional.empty(), maximum ? Optional.of(new PlayerMobilityIntent.State(7,
                        PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.ON,
                        PlayerMobilityIntent.EffectiveMode.WALK, PlayerMobilityIntent.HoverStatus.OFF,
                        PlayerMobilityIntent.Reason.NONE, false, 0)) : Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet phased(PlayerPhysicsProtocol.Packet packet,
            PlayerPhysicsProtocol.PredictionPhase phase, Optional<PlayerPhysicsProtocol.PredictionBoundary> boundary) {
        return new PlayerPhysicsProtocol.Packet(packet.kind(), packet.scope(), packet.sequence(), packet.offerSequence(),
                packet.application(), packet.snapshot(), packet.controls(), packet.nativeTeleportId(), packet.bodyReferenceReceipt(),
                packet.bodyReferenceAcknowledgement(), packet.bodyReferenceState(), packet.predictionInput(), boundary,
                packet.predictionSeed(), Optional.of(phase), packet.predictionRequest(), packet.mobilityState());
    }
    @Test void referenceSuspensionUsesV20AndRetainsTheCanonicalCardinalEnvelope() {
        assertEquals("0.1.8",Reference.VERSION);
        assertEquals(629,PlayerPhysicsProtocol.MAX_BYTES);
        var ready=new CardinalPredictionSeed.Ready(9,123,CardinalFrame.Y_POS,new Vec3d(0,-32,0),
                Optional.empty(),false,false,true,true,80);
        var base=seededPacket(CardinalFrame.Y_POS,ready,false);
        var paused=phased(base,new PlayerPhysicsProtocol.PredictionSuspended(9,
                new PlayerPhysicsProtocol.PredictionBootstrap(7,13),3,
                PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_REFERENCE),Optional.empty());
        var wire=PlayerPhysicsProtocol.encode(paused);
        assertEquals(20,Byte.toUnsignedInt(wire[0]));assertTrue(wire.length<=542);
        assertEquals(paused,decodeForTest(wire));
        var old=wire.clone();old[0]=18;
        assertThrows(IllegalArgumentException.class,()->decodeForTest(old));
        assertThrows(IllegalArgumentException.class,()->decodeForTest(new byte[PlayerPhysicsProtocol.MAX_BYTES+1]));
    }

    @Test void typedPredictionPhasesRoundTripAndCannotDisagreeWithTheirSingleBoundary() {
        var ready = new CardinalPredictionSeed.Ready(9, 123, CardinalFrame.Y_POS, new Vec3d(0,-32,0),
                Optional.empty(),false,false,true,true,80);
        var base = seededPacket(CardinalFrame.Y_POS, ready, false);
        var bootstrap = new PlayerPhysicsProtocol.PredictionBootstrap(7,13);
        var awaiting = new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(bootstrap,9,8);
        var waitingPacket = phased(base, awaiting, Optional.empty());
        assertEquals(waitingPacket, decodeForTest(PlayerPhysicsProtocol.encode(waitingPacket)));
        var boundary = new PlayerPhysicsProtocol.PredictionBoundary(3,1,0,1,92,10,8,10,7,
                PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.DEFERRED);
        var anchored = phased(base,new PlayerPhysicsProtocol.Anchored(bootstrap,9),Optional.of(boundary));
        assertEquals(anchored, decodeForTest(PlayerPhysicsProtocol.encode(anchored)));
        for (var reason : PlayerPhysicsProtocol.PredictionUnavailableReason.values()) {
            var unavailable = phased(base,new PlayerPhysicsProtocol.PredictionUnavailable(9,reason),Optional.empty());
            assertEquals(unavailable,decodeForTest(PlayerPhysicsProtocol.encode(unavailable)));
        }
        assertThrows(IllegalArgumentException.class,()->phased(base,awaiting,Optional.of(boundary)));
        assertThrows(IllegalArgumentException.class,()->phased(base,new PlayerPhysicsProtocol.Anchored(bootstrap,9),Optional.empty()));
        assertThrows(IllegalArgumentException.class,()->phased(base,
                new PlayerPhysicsProtocol.PredictionUnavailable(9,PlayerPhysicsProtocol.PredictionUnavailableReason.RETIRED),Optional.of(boundary)));
        assertThrows(IllegalArgumentException.class,()->phased(base,
                new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(bootstrap,10,0),Optional.empty()));
        var unsupported = seededPacket(CardinalFrame.Y_POS,new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE),false);
        assertThrows(IllegalArgumentException.class,()->phased(unsupported,awaiting,Optional.empty()));
        var deferredSeed = seededPacket(CardinalFrame.Y_POS,new CardinalPredictionSeed.Unavailable(
                CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE,Optional.empty()),false);
        var deferredPacket = phased(deferredSeed,new PlayerPhysicsProtocol.Anchored(bootstrap,9),Optional.of(boundary));
        assertEquals(deferredPacket,decodeForTest(PlayerPhysicsProtocol.encode(deferredPacket)));
        byte[] malformed = PlayerPhysicsProtocol.encode(waitingPacket);
        malformed[malformed.length-30] = 99; // phase tag follows its presence byte
        assertThrows(IllegalArgumentException.class,()->decodeForTest(malformed));
    }
    @Test void predictionInputEchoRoundTripsActualServerBootstrap() {
        var identity = new PlayerPhysicsProtocol.PredictionInput(3,92,92,1,
                Optional.of(new PlayerPhysicsProtocol.PredictionBootstrap(7,13)));
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT,SCOPE,10,1,Optional.empty(),
                Optional.empty(),Optional.of(controls(new Vec3d(0,0,0),new Vec3d(0,0,1),1)),OptionalInt.empty(),
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(identity),Optional.empty(),Optional.empty());
        assertEquals(packet,decodeForTest(PlayerPhysicsProtocol.encode(packet)));
        assertTrue(PlayerPhysicsProtocol.encode(packet).length <= PlayerPhysicsProtocol.MAX_BYTES);
    }

    private static void assertNoFreeRestore(byte[] malformed) {
        StoredDyadic.Work work = new StoredDyadic.Work();
        PhysicsDecodeBudget budget = new PhysicsDecodeBudget(malformed.length, work);
        assertThrows(IllegalArgumentException.class, () -> decodeForTest(malformed, budget));
        assertEquals(0, budget.restoreAttempts());
        assertEquals(0, budget.rawFieldReads());
        assertEquals(0, budget.quaternionReconstructionCalls());
        assertEquals(0, budget.reservedQuaternionVerificationSlots());
        assertEquals(0, work.units());
        assertEquals(0, work.peakBits());
    }

    private record ContactOffsets(int tag, int flags, int fall) { }

    /** Walk the actual fixture wire structure; tail marker count is not a contact offset. */
    private static ContactOffsets contactOffsets(byte[] encoded) {
        ByteBuffer reader = ByteBuffer.wrap(encoded);
        assertEquals(PlayerPhysicsProtocol.VERSION, Byte.toUnsignedInt(reader.get()));
        int kind = Byte.toUnsignedInt(reader.get());
        assertTrue(kind == PlayerPhysicsProtocol.Kind.OWNED.ordinal()
                || kind == PlayerPhysicsProtocol.Kind.STATE.ordinal());
        reader.getLong(); reader.getLong(); // player UUID
        reader.getLong(); reader.getLong(); // session UUID
        reader.getInt(); reader.getLong(); // dimension and world epoch
        reader.getLong(); reader.getLong(); // wire and offer sequence
        assertEquals(0, reader.get(), "these fixtures have no application operation");
        assertEquals(0, reader.get(), "these fixtures have no native teleport id");
        reader.getLong(); // physical revision
        int placement = Byte.toUnsignedInt(reader.get());
        if (placement == 0) {
            reader.get(); // cardinal frame
            for (int i = 0; i < 16; i++) reader.getDouble(); // dimensions, Q, bounds, transport
        } else {
            assertEquals(1, placement);
            for (int i = 0; i < 25; i++) reader.getDouble(); // dimensions, Q, centers, half line, proxy, transport
            int proof = Byte.toUnsignedInt(reader.get());
            if (proof == FreeCapsulePlacementV1.AXIS_PLANE_TAG) {
                for (int i = 0; i < 3; i++) reader.getDouble();
                reader.get(); reader.get(); reader.getLong();
            } else assertEquals(FreeCapsulePlacementV1.NOMINAL_TAG, proof);
        }
        for (int i = 0; i < 3; i++) reader.getDouble(); // world velocity
        int tag = reader.position();
        assertEquals(1, reader.get(), "fixture contacts must actually be present");
        int flags = reader.position();
        assertTrue((Byte.toUnsignedInt(reader.get()) & ~15) == 0);
        int fall = reader.position();
        assertTrue(Float.isFinite(reader.getFloat()));
        return new ContactOffsets(tag, flags, fall);
    }

    private static int freeSnapshotOffset() {
        // Version/kind, scope, sequence pair, application tag, native-correction tag.
        return 1 + 1 + 16 + 16 + 4 + 8 + 8 + 8 + 1 + 1;
    }

    private static PlayerBodyPlacement translatedNominalPlacement() {
        PlayerBodyPlacement origin = new PlayerBodyPlacement(new Vec3d(2.25, -3.5, 7.75),
                new Rotation3d(0.1, 0.2, 0.3, 0.9),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75)));
        return origin.withTranslatedCenter(new Vec3d(0x1.0p24 + 0.125, -3.25, 8.0));
    }

    private static PlayerBodyPlacement axisPlacement() {
        var dimensions = new PlayerBodyShape.Dimensions(0.5, 2.0, 1.75);
        Rotation3d attitude = Rotation3d.identity();
        Vec3d reference = new Vec3d(Math.nextDown(0x1.0p24), 1.125, 0.0);
        Vec3d realized = new Vec3d(reference.x(), Math.nextUp(reference.y()), reference.z());
        StoredDyadic.Work work = new StoredDyadic.Work();
        StoredRealizationLimits limits = StoredRealizationLimits.derive(reference, attitude, dimensions, work);
        Vec3d halfLine = new Vec3d(limits.nominalHalfLine().x(), realized.y() - 0.375,
                limits.nominalHalfLine().z());
        RealizationRelation relation = RealizationRelation.axisPlane(limits, 1, 1, 0.125,
                realized, halfLine, work);
        StoredCapsuleGeometry original = StoredCapsuleGeometry.fromRecipe(realized, attitude, dimensions,
                halfLine, relation);
        Vec3d current = new Vec3d(0x1.0p24 + 0.125, realized.y(), realized.z());
        StoredCapsuleGeometry translated = original.withTranslatedCenter(current);
        Aabb proxy = expanded(translated.capsule().bounds(), 0.125);
        Vec3d transport = new Vec3d(current.x(), proxy.min().y(), current.z());
        return PlayerBodyPlacement.fromAcceptedFreeGeometry(current, attitude,
                new PlayerBodyShape.FreeCapsule(dimensions), translated, proxy, transport);
    }

    private static Aabb expanded(Aabb bounds, double margin) {
        Vec3d extent = new Vec3d(margin, margin, margin);
        return new Aabb(bounds.min().subtract(extent), bounds.max().add(extent));
    }

    private static void assertRawVector(Vec3d expected, Vec3d actual) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()));
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()));
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()));
    }

    private static PlayerPhysicsProtocol.Packet decodeForTest(byte[] bytes, PhysicsDecodeBudget budget) {
        return PlayerPhysicsProtocol.decode(bytes, budget);
    }

    private static PlayerPhysicsProtocol.Packet decodeForTest(byte[] bytes) {
        if (bytes.length > 1 && Byte.toUnsignedInt(bytes[1]) == PlayerPhysicsProtocol.Kind.INPUT.ordinal())
            return PlayerPhysicsProtocol.decode(bytes, new PhysicsDecodeBudget(bytes.length, new StoredDyadic.Work()));
        return PlayerPhysicsProtocol.decode(bytes);
    }
    private static PlayerPhysicsProtocol.Controls controls(Vec3d translation, Vec3d forward, int gear) {
        return new PlayerPhysicsProtocol.Controls(translation, forward, true, false, false, true, gear);
    }

    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicsProtocol.Kind kind,
            Optional<PlayerPhysicsProtocol.ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
            Optional<PlayerPhysicsProtocol.Controls> controls) {
        return new PlayerPhysicsProtocol.Packet(kind, SCOPE, 1, 7, application, snapshot, controls);
    }
}
