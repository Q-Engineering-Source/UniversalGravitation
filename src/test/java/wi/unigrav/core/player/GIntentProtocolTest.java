package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class GIntentProtocolTest {
    private static final GIntentProtocol.Packet ON = new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT,
            new GIntentProtocol.Key(new UUID(1, 2), new UUID(3, 4), -1), 9, GIntentProtocol.State.ON);

    @Test
    void independentWireHasTheSpecifiedBigEndianLayout() {
        byte[] bytes = GIntentProtocol.encode(ON);
        assertEquals(47, bytes.length);
        assertEquals("0102" + "00000000000000010000000000000002" + "00000000000000030000000000000004"
                + "ffffffff" + "0000000000000009" + "02", HexFormat.of().formatHex(bytes));
        assertEquals(ON, GIntentProtocol.decode(bytes));
    }

    @Test
    void actualForgeAdapterRoundTripsAndRejectsTrailingPayload() {
        var buffer = Unpooled.buffer();
        try {
            new GIntentNetwork.Message(ON).toBytes(buffer);
            var decoded = new GIntentNetwork.Message();
            decoded.fromBytes(buffer);
            assertEquals(ON, decoded.packet());
            buffer.clear();
            buffer.writeBytes(GIntentProtocol.encode(ON)).writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> decoded.fromBytes(buffer));
        } finally { buffer.release(); }
    }

    @Test
    void malformedLengthVersionTagsAndSequenceFailAtDecode() {
        byte[] bytes = GIntentProtocol.encode(ON);
        for (int size : new int[] {0, 1, 46, 48, 70}) {
            assertThrows(IllegalArgumentException.class, () -> GIntentProtocol.decode(Arrays.copyOf(bytes, size)));
        }
        for (int index : new int[] {0, 1, 46}) {
            byte[] invalid = bytes.clone();
            invalid[index] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> GIntentProtocol.decode(invalid));
        }
        ByteBuffer.wrap(bytes).putLong(38, -1);
        assertThrows(IllegalArgumentException.class, () -> GIntentProtocol.decode(bytes));
    }

    @Test
    void onlyExplicitPositiveSequenceCanCarryAnIntent() {
        var key = ON.key();
        assertThrows(IllegalArgumentException.class, () -> new GIntentProtocol.Packet(
                GIntentProtocol.Kind.INTENT, key, 0, GIntentProtocol.State.ON));
        assertThrows(IllegalArgumentException.class, () -> new GIntentProtocol.Packet(
                GIntentProtocol.Kind.INTENT, key, 1, GIntentProtocol.State.UNKNOWN));
        assertThrows(IllegalArgumentException.class, () -> new GIntentProtocol.Packet(
                GIntentProtocol.Kind.HELLO, key, 0, GIntentProtocol.State.ON));
    }
}
