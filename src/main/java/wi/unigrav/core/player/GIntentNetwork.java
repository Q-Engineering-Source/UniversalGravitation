package wi.unigrav.core.player;

import java.util.Objects;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import wi.unigrav.UnigravMod;

/** Dedicated-safe transport; no client types and no change to ugboost1's 70-byte ABI. */
public final class GIntentNetwork {
    private static SimpleNetworkWrapper channel;
    private GIntentNetwork() { }

    public static void initialize() {
        if (channel != null) throw new IllegalStateException("G-intent network already initialized");
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(GIntentProtocol.CHANNEL);
        channel.registerMessage(Handler.class, Message.class, 0, Side.SERVER);
        channel.registerMessage(Handler.class, Message.class, 0, Side.CLIENT);
    }

    public static void sendTo(EntityPlayerMP player, GIntentProtocol.Packet packet) {
        channel.sendTo(new Message(packet), player);
    }

    public static void sendToServer(GIntentProtocol.Packet packet) { channel.sendToServer(new Message(packet)); }

    public static final class Message implements IMessage {
        private GIntentProtocol.Packet packet;
        public Message() { }
        public Message(GIntentProtocol.Packet packet) { this.packet = Objects.requireNonNull(packet, "packet"); }
        public GIntentProtocol.Packet packet() { return packet; }
        @Override public void fromBytes(ByteBuf buffer) {
            if (buffer.readableBytes() != GIntentProtocol.PACKET_BYTES) throw new IllegalArgumentException("G-intent length");
            byte[] bytes = new byte[GIntentProtocol.PACKET_BYTES];
            buffer.readBytes(bytes);
            packet = GIntentProtocol.decode(bytes);
        }
        @Override public void toBytes(ByteBuf buffer) { buffer.writeBytes(GIntentProtocol.encode(packet)); }
    }

    public static final class Handler implements IMessageHandler<Message, IMessage> {
        @Override public IMessage onMessage(Message message, MessageContext context) {
            if (context.side == Side.SERVER) GIntentServer.INSTANCE.receive(message.packet(), context.getServerHandler());
            else UnigravMod.proxy.receiveGIntent(message.packet(), context.netHandler);
            return null;
        }
    }
}
