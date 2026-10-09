package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import neofontrender.addons.api.flight.FlightAttitude;
import org.junit.jupiter.api.Test;

class PlayerRenderPoseProtocolTest {
    private static final UUID CONNECTION = new UUID(11, 12);
    private static final UUID SUBJECT = new UUID(13, 14);
    private static final PlayerRenderPoseProtocol.Quaternion IDENTITY =
            new PlayerRenderPoseProtocol.Quaternion(0, 0, 0, 1);

    @Test void everyPacketKindRoundTripsItsConnectionAndPresentationFences() {
        var free = PlayerRenderPoseProtocol.Packet.freePose(CONNECTION, 7, -7, 3, 8, 19, IDENTITY, IDENTITY);
        var lease = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 8, 19);
        var disabledLease = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 0, 20);
        var welcome = PlayerRenderPoseProtocol.Packet.welcome(CONNECTION, 7, 2, SUBJECT, 42, -7, 3);
        var cardinal = PlayerRenderPoseProtocol.Packet.pose(CONNECTION, 7, SUBJECT, 42, -7, 3, 5, 8,
                19, PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Z_NEG.ordinal(), IDENTITY, IDENTITY);
        var remove = PlayerRenderPoseProtocol.Packet.remove(CONNECTION, 7, SUBJECT, 42, -7, 3, 5, 9, 19);
        for (var packet : List.of(PlayerRenderPoseProtocol.Packet.hello(CONNECTION, 2), welcome,
                lease, disabledLease, free, cardinal, remove)) {
            assertEquals(packet, PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(packet)));
        }
    }

    @Test void helloAndWelcomeRequireTheExactPositiveClientWorldRequestGeneration() {
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.Packet.hello(CONNECTION, 0));
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.Packet.welcome(
                CONNECTION, 7, 0, SUBJECT, 42, -7, 3));
        var hello = PlayerRenderPoseProtocol.Packet.hello(CONNECTION, 41);
        var welcome = PlayerRenderPoseProtocol.Packet.welcome(CONNECTION, 12, 41, SUBJECT, 42, -7, 3);
        assertEquals(41, PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(hello))
                .worldRequestGeneration());
        assertEquals(41, PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(welcome))
                .worldRequestGeneration());
    }

    @Test void freeLeaseRequiresEitherDisabledNullModeOrPositiveFreeMode() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.Packet(
                PlayerRenderPoseProtocol.Kind.FREE_LEASE, CONNECTION, 7, 0, SUBJECT, 42, -7, 3,
                0, 20, 0, PlayerRenderPoseProtocol.Mode.CARDINAL, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.Packet(
                PlayerRenderPoseProtocol.Kind.FREE_LEASE, CONNECTION, 7, 0, SUBJECT, 42, -7, 3,
                9, 20, 0, null, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.Packet(
                PlayerRenderPoseProtocol.Kind.FREE_LEASE, CONNECTION, 7, 0, SUBJECT, 42, -7, 3,
                9, 20, 0, PlayerRenderPoseProtocol.Mode.CARDINAL, -1, null, null));

        var disabled = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 0, 20);
        var enabled = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 9, 20);
        assertNull(disabled.mode());
        assertEquals(PlayerRenderPoseProtocol.Mode.FREE, enabled.mode());
        assertEquals(disabled, PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(disabled)));
        assertEquals(enabled, PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(enabled)));
    }

    @Test void unitQuaternionIsValidatedBeforeAnyNormalizationCanHideBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlayerRenderPoseProtocol.Quaternion(0, 0, 0, 2));
        byte[] encoded = PlayerRenderPoseProtocol.encode(
                PlayerRenderPoseProtocol.Packet.freePose(CONNECTION, 1, -7, 3, 9, 1, IDENTITY, IDENTITY));
        ByteBuffer.wrap(encoded).putDouble(70, 2.0);
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(encoded));
        ByteBuffer.wrap(encoded).putDouble(54, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(encoded));
    }

    @Test void quaternionBasisMatchesTheActualUieFlightAttitudeForAllCardinalFrames() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            var quaternion = PlayerRenderPoseProtocol.Quaternion.fromUpForward(
                    frame.up(), frame.attitude().basisZ());
            var attitude = new FlightAttitude(quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
            assertVector(frame.up(), attitude.up(), frame.name() + " model up");
            assertVector(frame.attitude().basisZ(), attitude.forward(), frame.name() + " forward");
        }
    }

    private static void assertVector(dev.crlhitbox.api.geometry.Vec3d expected,
                                     neofontrender.addons.api.flight.FlightVector actual, String label) {
        assertEquals(expected.x(), actual.x, 1.0e-10, label + " X");
        assertEquals(expected.y(), actual.y, 1.0e-10, label + " Y");
        assertEquals(expected.z(), actual.z, 1.0e-10, label + " Z");
    }
}
