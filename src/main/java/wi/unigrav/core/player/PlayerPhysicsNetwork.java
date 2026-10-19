package wi.unigrav.core.player;

import java.util.Objects;
import java.util.function.BiConsumer;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import wi.unigrav.UnigravMod;

/** Dedicated-safe wire boundary for the independently negotiated physics lifetime. */
public final class PlayerPhysicsNetwork {
    private static SimpleNetworkWrapper channel;
    private PlayerPhysicsNetwork() {}

    /** Called only when the full server owner and both retirement paths are ready. */
    public static void initialize(BiConsumer<PlayerPhysicsInboundConnections.RawInboundFrame, NetHandlerPlayServer> server) {
        Objects.requireNonNull(server, "server");
        if (channel != null) throw new IllegalStateException("Physics network already initialized");
        var created = NetworkRegistry.INSTANCE.newSimpleChannel(PlayerPhysicsProtocol.CHANNEL);
        created.registerMessage((Message message, MessageContext context) -> {
            server.accept(message.rawFrame(), context.getServerHandler());
            return null;
        }, Message.class, 0, Side.SERVER);
        created.registerMessage((Message message, MessageContext context) -> {
            UnigravMod.proxy.receiveRawPlayerPhysics(message.rawFrame(), context.netHandler);
            return null;
        }, Message.class, 0, Side.CLIENT);
        channel = created;
    }

    public static void sendToServer(PlayerPhysicsProtocol.Packet packet) {
        if (packet.clientbound()) throw new IllegalArgumentException("Wrong physics send direction");
        requireChannel().sendToServer(new Message(packet));
    }
    public static void sendTo(EntityPlayerMP player, PlayerPhysicsProtocol.Packet packet) {
        if (!packet.clientbound()) throw new IllegalArgumentException("Wrong physics send direction");
        requireChannel().sendTo(new Message(packet), player);
    }
    private static SimpleNetworkWrapper requireChannel() {
        if (channel == null) throw new IllegalStateException("Physics network is not activated");
        return channel;
    }
    public static final class Message implements IMessage {
        private PlayerPhysicsProtocol.Packet packet;
        private PlayerPhysicsInboundConnections.RawInboundFrame rawFrame;
        public Message() {}
        public Message(PlayerPhysicsProtocol.Packet packet) { this.packet = Objects.requireNonNull(packet, "packet"); }
        public PlayerPhysicsProtocol.Packet packet() {
            if (packet == null) throw new IllegalStateException("Inbound physics messages carry a raw frame, not a decoded packet");
            return packet;
        }
        public PlayerPhysicsInboundConnections.RawInboundFrame rawFrame() {
            if (rawFrame == null) throw new IllegalStateException("No captured raw physics frame");
            return rawFrame;
        }
        @Override public void fromBytes(ByteBuf buffer) {
            packet = null;
            int actualBodyBytes = buffer.readableBytes();
            rawFrame = PlayerPhysicsInboundConnections.INSTANCE.captureRawFrame(buffer);
            buffer.skipBytes(actualBodyBytes);
        }
        @Override public void toBytes(ByteBuf buffer) { buffer.writeBytes(PlayerPhysicsProtocol.encode(packet())); }
    }
}
