package wi.unigrav.core.network;

import io.netty.buffer.ByteBuf;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import wi.unigrav.core.ImpactDamageHandler;

/**
 * Client → server impact report: the local player hit something at the
 * given speed (m/s). Player movement is client-authoritative, so only the
 * client knows the exact pre-collision motion and collision flags; the
 * server-side EntityPlayerMP's {@code onGround} could not be sampled
 * reliably at tick END (observed live: zero landing events logged for the
 * player while mobs logged fine). The server re-validates (bound, speed
 * range) before applying damage.
 *
 * <p>Trust note: a hacked client can report fake impacts. Accepted — the
 * player controls its own movement anyway; this only ever damages the
 * reporting player.
 */
public final class ImpactReportMessage implements IMessage {

    public static final int TYPE_FALL = 0;
    public static final int TYPE_WALL = 1;
    public static final int TYPE_CEILING = 2;

    private int type;
    private float speed;

    @SuppressWarnings("unused") // required by the network pipeline
    public ImpactReportMessage() {
    }

    public ImpactReportMessage(int type, double speed) {
        this.type = type;
        this.speed = (float) speed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(type);
        buf.writeFloat(speed);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        type = buf.readByte();
        speed = buf.readFloat();
    }

    public static final class Handler
            implements IMessageHandler<ImpactReportMessage, IMessage> {
        @Override
        public IMessage onMessage(ImpactReportMessage message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            int type = message.type;
            float speed = message.speed;
            // Hop to the server thread: the handler runs on a Netty thread.
            player.getServerWorld().addScheduledTask(
                    () -> ImpactDamageHandler.applyPlayerImpact(player, type, speed));
            return null;
        }
    }
}
