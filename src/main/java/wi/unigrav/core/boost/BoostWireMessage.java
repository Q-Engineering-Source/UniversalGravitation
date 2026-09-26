package wi.unigrav.core.boost;

import java.util.Objects;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/** Forge adapter for the fixed-size pure codec. */
public final class BoostWireMessage implements IMessage {
    private BoostProtocol.Packet packet;

    public BoostWireMessage() { }

    public BoostWireMessage(BoostProtocol.Packet packet) {
        this.packet = Objects.requireNonNull(packet, "packet");
    }

    public BoostProtocol.Packet packet() { return packet; }

    @Override
    public void fromBytes(ByteBuf buffer) {
        if (buffer.readableBytes() != BoostProtocol.PACKET_BYTES) {
            throw new IllegalArgumentException("Unexpected boost message length");
        }
        byte[] bytes = new byte[BoostProtocol.PACKET_BYTES];
        buffer.readBytes(bytes);
        packet = BoostProtocol.decode(bytes);
    }

    @Override
    public void toBytes(ByteBuf buffer) { buffer.writeBytes(BoostProtocol.encode(packet)); }
}
