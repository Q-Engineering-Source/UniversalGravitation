package wi.unigrav.core.boost;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import wi.unigrav.UnigravMod;

/** Negotiated channel isolated from the historical unigrav impact discriminator table. */
public final class BoostNetwork {
    private static SimpleNetworkWrapper channel;

    private BoostNetwork() { }

    public static void initialize() {
        if (channel != null) throw new IllegalStateException("Boost network already initialized");
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(BoostProtocol.CHANNEL);
        channel.registerMessage(Handler.class, BoostWireMessage.class, 0, Side.SERVER);
        channel.registerMessage(Handler.class, BoostWireMessage.class, 0, Side.CLIENT);
    }

    public static void sendTo(EntityPlayerMP player, BoostProtocol.Packet packet) {
        channel.sendTo(new BoostWireMessage(packet), player);
    }

    public static void sendToServer(BoostProtocol.Packet packet) {
        channel.sendToServer(new BoostWireMessage(packet));
    }

    public static final class Handler implements IMessageHandler<BoostWireMessage, IMessage> {
        @Override
        public IMessage onMessage(BoostWireMessage message, MessageContext context) {
            if (context.side == Side.SERVER) {
                RemoteBoostServer.INSTANCE.receive(message.packet(), context.getServerHandler());
            } else {
                UnigravMod.proxy.receiveRemoteBoost(message.packet(), context.netHandler);
            }
            return null;
        }
    }
}
