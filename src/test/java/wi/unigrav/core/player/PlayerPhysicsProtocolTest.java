package wi.unigrav.core.player;

import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsProtocolTest {
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), -1, 3);

    @Test void nativeEyeOutsideColliderRoundTripsWithoutExpandingCollisionGeometry() {
        var dimensions = new PlayerBodyShape.Dimensions(.5, .5, 1.5);
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions);
        var placement = PlayerBodyPlacement.fromTransport(new Vec3d(0, 2, 0), CardinalFrame.Y_POS.attitude(), shape);
        var state = new PlayerPhysicalSnapshot(SCOPE, 1, placement, new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
        var original = packet(PlayerPhysicsProtocol.Kind.STATE, Optional.empty(), Optional.of(state), Optional.empty());
        assertEquals(original, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(original)));
        assertEquals(2.5, placement.bounds().max().y());
        assertEquals(3.5, placement.eye().y());
        assertEquals(2.5, placement.capsule().bounds().max().y());
    }

    @Test void applyCarriesIdentityNotClientVelocity() {
        var packet = packet(PlayerPhysicsProtocol.Kind.APPLY, Optional.empty(), Optional.empty(), Optional.empty());
        byte[] bytes = PlayerPhysicsProtocol.encode(packet);
        assertEquals(packet, PlayerPhysicsProtocol.decode(bytes));
        assertThrows(IllegalArgumentException.class,
                () -> PlayerPhysicsProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(truncated));
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
                var decoded = PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(original));
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
        assertEquals(original, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(original)));
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
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(truncated));
        }
        byte[] invalidFlags = bytes.clone();
        invalidFlags[invalidFlags.length - 5] = 16;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(invalidFlags));
        byte[] invalidFall = bytes.clone();
        ByteBuffer.wrap(invalidFall).putFloat(invalidFall.length - 4, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(invalidFall));
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
        var decoded = PlayerPhysicsProtocol.decode(wire).snapshot().orElseThrow();
        assertTrue(decoded.contacts().orElseThrow().nearFootLocomotion());
        assertFalse(decoded.contacts().orElseThrow().onGround());
        wire[0] = 1;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(wire),
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
        assertEquals(input, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(input)));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(1, 1, 0), new Vec3d(0, 0, -1), 1));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, -1), 10));
        byte[] bytes = PlayerPhysicsProtocol.encode(input);
        bytes[0] = 99;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(bytes));
        bytes[0] = 1;
        bytes[1] = 99;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(bytes));
    }

    @Test void malformedTagsAndNonFiniteWireInputAreRejectedBeforeUse() {
        var input = packet(PlayerPhysicsProtocol.Kind.INPUT, Optional.empty(), Optional.empty(),
                Optional.of(controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, -1), 1)));
        byte[] badBoolean = PlayerPhysicsProtocol.encode(input);
        badBoolean[62] = 2;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(badBoolean));
        byte[] badFlags = PlayerPhysicsProtocol.encode(input);
        badFlags[badFlags.length - 2] = 16;
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(badFlags));
        byte[] nan = PlayerPhysicsProtocol.encode(input);
        java.nio.ByteBuffer.wrap(nan).putDouble(63, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(nan));
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsProtocol.decode(new byte[513]));
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

    private static PlayerPhysicsProtocol.Controls controls(Vec3d translation, Vec3d forward, int gear) {
        return new PlayerPhysicsProtocol.Controls(translation, forward, true, false, false, true, gear);
    }

    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicsProtocol.Kind kind,
            Optional<PlayerPhysicsProtocol.ApplicationId> application, Optional<PlayerPhysicalSnapshot> snapshot,
            Optional<PlayerPhysicsProtocol.Controls> controls) {
        return new PlayerPhysicsProtocol.Packet(kind, SCOPE, 1, 7, application, snapshot, controls);
    }
}
