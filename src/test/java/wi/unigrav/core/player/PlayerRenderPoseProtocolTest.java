package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.Arrays;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import org.junit.jupiter.api.Test;

class PlayerRenderPoseProtocolTest {
    @Test void selectedUieRuntimeDeclaresTheGeometryAndWorldRootOffsetAbi() {
        assertEquals(4, CameraApi.getApiVersion());
        assertEquals(11, FlightApi.getApiVersion());
        var attitude = new FlightAttitude(0, 0, 0, 1);
        var root = new FlightVector(-.9, .3, 0);
        var pose = FlightBodyPose.modelUpY(attitude, attitude, root);
        assertEquals(root.x, pose.rootWorldOffset.x);
        assertEquals(root.y, pose.rootWorldOffset.y);
        assertEquals(root.z, pose.rootWorldOffset.z);
    }
    private static final UUID CONNECTION = new UUID(11, 12);
    private static final UUID SUBJECT = new UUID(13, 14);
    private static final PlayerRenderPoseProtocol.Quaternion IDENTITY =
            new PlayerRenderPoseProtocol.Quaternion(0, 0, 0, 1);

    @Test void exactAcceptedGeometryRoundTripsEveryFrameAndRejectsOldOrPartialWire() {
        for (var frame : CardinalFrame.values()) {
            var placement = new PlayerBodyPlacement(new Vec3d(33, 88, 8), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(1.2, 3.6, 3.1)));
            var geometry = new PlayerRenderPoseProtocol.CardinalGeometry(new UUID(77, 88), 19,
                    placement.transport(), placement.bounds());
            var body = PlayerRenderPoseProtocol.Quaternion.fromUpForward(frame.up(), frame.attitude().basisZ());
            var packet = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, CONNECTION, 7, 0,
                    SUBJECT, 42, -7, 3, 5, 8, 0, PlayerRenderPoseProtocol.Mode.CARDINAL,
                    frame.ordinal(), body, body, geometry);
            var bytes = PlayerRenderPoseProtocol.encode(packet);
            assertEquals(245, bytes.length);
            assertEquals(packet, PlayerRenderPoseProtocol.decode(bytes));
            var expectedRoot = placement.physicalFeet().orElseThrow().subtract(placement.transport());
            var actualRoot = geometry.rootOffset(frame.ordinal());
            assertEquals(expectedRoot.x(), actualRoot.x(), 1e-10);
            assertEquals(expectedRoot.y(), actualRoot.y(), 1e-10);
            assertEquals(expectedRoot.z(), actualRoot.z(), 1e-10);
            assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(Arrays.copyOf(bytes, bytes.length - 1)));
            assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(Arrays.copyOf(bytes, bytes.length + 1)));
            bytes[0] = 1;
            assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(bytes));
        }
    }

    @Test void malformedGeometryCannotClaimAcceptedFrameOrTransport() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.CardinalGeometry(
                SUBJECT, 1, new Vec3d(0, 0, 0), new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 2, 1))));
        var badShape = new PlayerRenderPoseProtocol.CardinalGeometry(SUBJECT, 1, new Vec3d(.5, 0, 1),
                new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 2, 2)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.Packet(
                PlayerRenderPoseProtocol.Kind.POSE, CONNECTION, 1, 0, SUBJECT, 42, 0, 1, 1, 1, 1,
                PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Y_POS.ordinal(), IDENTITY, IDENTITY, badShape));
        var valid = new PlayerRenderPoseProtocol.CardinalGeometry(SUBJECT, 1, new Vec3d(.5, 0, .5),
                new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 2, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PlayerRenderPoseProtocol.Packet(
                PlayerRenderPoseProtocol.Kind.POSE, CONNECTION, 1, 0, SUBJECT, 42, 0, 1, 1, 1, 1,
                PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Y_NEG.ordinal(), IDENTITY, IDENTITY, valid));
    }

    @Test void everyPacketKindRoundTripsItsConnectionAndPresentationFences() {
        var free = PlayerRenderPoseProtocol.Packet.freePose(CONNECTION, 7, -7, 3, 8, 19, IDENTITY, IDENTITY);
        var lease = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 8, 19);
        var disabledLease = PlayerRenderPoseProtocol.Packet.freeLease(CONNECTION, 7, SUBJECT, 42, -7, 3, 0, 20);
        var welcome = PlayerRenderPoseProtocol.Packet.welcome(CONNECTION, 7, 2, SUBJECT, 42, -7, 3);
        var cardinal = PlayerRenderPoseProtocol.Packet.pose(CONNECTION, 7, SUBJECT, 42, -7, 3, 5, 8,
                19, PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Z_NEG.ordinal(), IDENTITY, IDENTITY);
        var remove = PlayerRenderPoseProtocol.Packet.remove(CONNECTION, 7, SUBJECT, 42, -7, 3, 5, 9, 19);
        var release = PlayerRenderPoseProtocol.Packet.presentationRelease(CONNECTION, 7, SUBJECT, 42, -7, 3, 5, 10, 20,
                new PlayerRenderPoseProtocol.PresentationRelease(new UUID(77, 88), 19, 2));
        byte[] releaseBytes = PlayerRenderPoseProtocol.encode(release);
        assertEquals(114, releaseBytes.length);
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(Arrays.copyOf(releaseBytes, 113)));
        assertThrows(IllegalArgumentException.class, () -> PlayerRenderPoseProtocol.decode(Arrays.copyOf(releaseBytes, 115)));
        for (var packet : List.of(PlayerRenderPoseProtocol.Packet.hello(CONNECTION, 2), welcome,
                lease, disabledLease, free, cardinal, remove, release)) {
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
