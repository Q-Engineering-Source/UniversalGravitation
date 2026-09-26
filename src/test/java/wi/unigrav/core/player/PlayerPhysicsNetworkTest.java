package wi.unigrav.core.player;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsNetworkTest {
    @Test void messageDecodesTheExactPayloadAndLeavesNoTrailingBytes() {
        var scope = new PlayerPhysicalSnapshot.Scope(new UUID(1, 2), new UUID(3, 4), 0, 1);
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, scope, 1, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
        var bytes = Unpooled.buffer();
        try {
            new PlayerPhysicsNetwork.Message(packet).toBytes(bytes);
            var decoded = new PlayerPhysicsNetwork.Message();
            decoded.fromBytes(bytes);
            assertEquals(packet, decoded.packet());
            assertEquals(0, bytes.readableBytes());
        } finally { bytes.release(); }
    }

    @Test void oversizedMessageIsRejectedBeforeReadingOrAllocatingPayload() {
        var bytes = Unpooled.buffer(513).writeZero(513);
        try {
            var message = new PlayerPhysicsNetwork.Message();
            assertThrows(IllegalArgumentException.class, () -> message.fromBytes(bytes));
            assertEquals(0, bytes.readerIndex());
            assertThrows(IllegalStateException.class, message::packet);
        } finally { bytes.release(); }
    }
}
