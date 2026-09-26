package wi.unigrav.core.boost;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class BoostProtocolTest {
    @Test
    void corridorFlagAndFrameContinuityHintHaveSeparateKindSpecificSemantics() {
        Vec3 hint = new Vec3(280.0, -12.0, 0.5);
        var frame = BoostProtocol.frame(9, 4, 15, 0, 9, false, hint);
        var state = BoostProtocol.state(9, 4, 22, 0, BoostProtocol.Mode.ACTIVE, 9, 448,
                BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE, true);
        assertEquals(hint, BoostProtocol.decode(BoostProtocol.encode(frame)).velocity());
        assertFalse(frame.flag());
        assertTrue(BoostProtocol.decode(BoostProtocol.encode(state)).flag());
        assertEquals(448, state.ceilingMps());
        assertEquals(70, BoostProtocol.encode(state).length);
        assertEquals(2, state.version());
        assertFalse(BoostProtocol.state(9, 4, 22, 0, BoostProtocol.Mode.ACTIVE, 9, 490,
                BoostProtocol.Operation.NONE, Vec3.ZERO, BoostProtocol.Reason.NONE).flag());
    }

    @Test
    void fullPrecisionVelocityAndIdentityRoundTrip() {
        var packet = BoostProtocol.state(17, 4, 31, -1, BoostProtocol.Mode.ACTIVE, 9, 500,
                BoostProtocol.Operation.ADD, new Vec3(490, -160.125, 1.0e-7), BoostProtocol.Reason.NONE);
        byte[] bytes = BoostProtocol.encode(packet);
        assertEquals(70, bytes.length);
        assertEquals(2, bytes[0]);
        assertEquals(2, ByteBuffer.wrap(bytes).getInt(1));
        assertEquals(490.0, ByteBuffer.wrap(bytes).getDouble(44));
        assertEquals(packet, BoostProtocol.decode(bytes));
    }

    @Test
    void boundedCodecRejectsMalformedPackets() {
        byte[] bytes = BoostProtocol.encode(BoostProtocol.offer(1, 0, 490, true));
        assertThrows(IllegalArgumentException.class, () -> BoostProtocol.decode(new byte[69]));
        assertThrows(IllegalArgumentException.class, () -> BoostProtocol.decode(new byte[71]));
        bytes[0] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> BoostProtocol.decode(bytes));
        bytes[0] = 0;
        bytes[68] = 2;
        assertThrows(IllegalArgumentException.class, () -> BoostProtocol.decode(bytes));
        bytes[68] = 1;
        ByteBuffer.wrap(bytes).putDouble(44, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> BoostProtocol.decode(bytes));
    }

    @Test
    void messageFactoriesRoundTripAndOfferDoesNotGrantMovement() {
        var messages = new BoostProtocol.Packet[] {
                BoostProtocol.offer(123, 0, 490, true), BoostProtocol.hello(123, true),
                BoostProtocol.modeRequest(123, 2, 1, 0, true),
                BoostProtocol.frame(123, 2, 9, 0, 9, false)
        };
        for (var message : messages) assertEquals(message, BoostProtocol.decode(BoostProtocol.encode(message)));
        assertEquals(BoostProtocol.Mode.UNAVAILABLE, messages[0].mode());
        assertEquals(9, BoostGearProfile.count());
        assertEquals(5, BoostGearProfile.defaultGear());
        assertEquals(490, BoostGearProfile.maximumSpeedMps());
        assertEquals(980, BoostGearProfile.accelerationMps2(9));
        assertThrows(IllegalArgumentException.class, () -> BoostGearProfile.speedMps(0));
        assertThrows(IllegalArgumentException.class, () -> BoostGearProfile.speedMps(10));
    }
}
