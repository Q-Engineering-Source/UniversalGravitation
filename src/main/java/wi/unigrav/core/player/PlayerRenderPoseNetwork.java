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

/** Dedicated-safe wire boundary for read-only player render poses. */
public final class PlayerRenderPoseNetwork {
    private static SimpleNetworkWrapper channel;

    private PlayerRenderPoseNetwork() { }

    public static void initialize(BiConsumer<PlayerRenderPoseProtocol.Packet, NetHandlerPlayServer> server) {
        Objects.requireNonNull(server, "server");
        if (channel != null) throw new IllegalStateException("Player render pose network already initialized");
        var created = NetworkRegistry.INSTANCE.newSimpleChannel(PlayerRenderPoseProtocol.CHANNEL);
        created.registerMessage((Message message, MessageContext context) -> {
            if (!message.packet().serverbound()) throw new IllegalArgumentException("Clientbound render pose on server");
            server.accept(message.packet(), context.getServerHandler());
            return null;
        }, Message.class, 0, Side.SERVER);
        created.registerMessage((Message message, MessageContext context) -> {
            if (message.packet().serverbound()) throw new IllegalArgumentException("Serverbound render pose on client");
            UnigravMod.proxy.receivePlayerRenderPose(message.packet(), context.netHandler);
            return null;
        }, Message.class, 0, Side.CLIENT);
        channel = created;
    }

    public static void sendToServer(PlayerRenderPoseProtocol.Packet packet) {
        if (!packet.serverbound()) throw new IllegalArgumentException("Wrong render pose send direction");
        requireChannel().sendToServer(new Message(packet));
    }

    public static void sendTo(EntityPlayerMP player, PlayerRenderPoseProtocol.Packet packet) {
        if (packet.serverbound()) throw new IllegalArgumentException("Wrong render pose send direction");
        requireChannel().sendTo(new Message(packet), player);
    }

    private static SimpleNetworkWrapper requireChannel() {
        if (channel == null) throw new IllegalStateException("Player render pose network is not activated");
        return channel;
    }

    public static final class Message implements IMessage {
        private PlayerRenderPoseProtocol.Packet packet;

        public Message() { }
        public Message(PlayerRenderPoseProtocol.Packet packet) { this.packet = Objects.requireNonNull(packet, "packet"); }

        public PlayerRenderPoseProtocol.Packet packet() {
            if (packet == null) throw new IllegalStateException("No decoded render pose message");
            return packet;
        }

        @Override public void fromBytes(ByteBuf buffer) {
            packet = null;
            int size = buffer.readableBytes();
            if (size > PlayerRenderPoseProtocol.MAX_BYTES) throw new IllegalArgumentException("Render pose packet length");
            byte[] bytes = new byte[size];
            buffer.readBytes(bytes);
            packet = PlayerRenderPoseProtocol.decode(bytes);
        }

        @Override public void toBytes(ByteBuf buffer) {
            buffer.writeBytes(PlayerRenderPoseProtocol.encode(packet()));
        }
    }
}
