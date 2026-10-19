package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PlayerPhysicsNetworkTest {
    @Test
    void outboundEncodingIsPreservedAndInboundMessageOnlyCapturesRawBody() {
        var scope = new PlayerPhysicalSnapshot.Scope(new UUID(1, 2), new UUID(3, 4), 0, 1);
        var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, scope, 1, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
        byte[] expectedWire = PlayerPhysicsProtocol.encode(packet);
        ByteBuf outbound = Unpooled.buffer();
        ByteBuf inbound = Unpooled.wrappedBuffer(expectedWire);
        try {
            new PlayerPhysicsNetwork.Message(packet).toBytes(outbound);
            assertArrayEquals(expectedWire, ByteBufUtil.getBytes(outbound));

            var decoded = new PlayerPhysicsNetwork.Message();
            decoded.fromBytes(inbound);
            assertNotNull(decoded.rawFrame());
            assertEquals(inbound.writerIndex(), inbound.readerIndex());
            assertThrows(IllegalStateException.class, decoded::packet);
        } finally {
            outbound.release();
            inbound.release();
        }
    }

    @Test
    void malformedAndOversizedBodiesAreCapturedForOwnerAdmissionWithoutParsing() {
        for (byte[] body : new byte[][] { { 0 }, new byte[PlayerPhysicsProtocol.MAX_BYTES + 1] }) {
            ByteBuf inbound = Unpooled.wrappedBuffer(body);
            try {
                var message = new PlayerPhysicsNetwork.Message();
                message.fromBytes(inbound);
                assertNotNull(message.rawFrame());
                assertEquals(inbound.writerIndex(), inbound.readerIndex());
                assertThrows(IllegalStateException.class, message::packet);
            } finally {
                inbound.release();
            }
        }
    }
}
