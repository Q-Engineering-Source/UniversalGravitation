package wi.unigrav.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.INetHandler;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import wi.unigrav.core.player.GIntentClientSession;
import wi.unigrav.core.player.GIntentNetwork;
import wi.unigrav.core.player.GIntentProtocol;

/** Explicit G synchronization for local and negotiated physics peers; never writes motion or camera state. */
public final class ClientGIntent {
    private static final ClientGIntent INSTANCE = new ClientGIntent();
    private boolean installed;
    private NetHandlerPlayClient connection;
    private EntityPlayerSP player;
    private World world;
    private int dimension;
    private GIntentClientSession session;
    private GIntentProtocol.Packet pendingOffer;
    private NetHandlerPlayClient offeringConnection;
    private boolean wasCreative;
    private int retryTicks;

    private ClientGIntent() { }

    public static void install() {
        if (!INSTANCE.installed) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
            INSTANCE.installed = true;
        }
    }

    public static void receive(GIntentProtocol.Packet packet, INetHandler handler) {
        if (!(handler instanceof NetHandlerPlayClient client)) return;
        Minecraft.getMinecraft().addScheduledTask(() -> INSTANCE.receiveOnClientThread(packet, client));
    }

    private void receiveOnClientThread(GIntentProtocol.Packet packet, NetHandlerPlayClient handler) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getConnection() != handler) return;
        if (packet.kind() == GIntentProtocol.Kind.OFFER) {
            pendingOffer = packet;
            offeringConnection = handler;
        } else if (connection == handler && player == mc.player && world == mc.world && session != null
                && player.dimension == dimension && session.key().playerId().equals(player.getUniqueID())) {
            session.receive(packet);
        }
    }

    /** Called after the existing G-key drain, never from an independent input hook. */
    public static void tick(boolean requestedOn) { INSTANCE.tickOnClientThread(requestedOn); }

    private void tickOnClientThread(boolean requestedOn) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.getConnection() == null) {
            clear();
            return;
        }
        if (connection != mc.getConnection() || player != mc.player || world != mc.world
                || dimension != mc.player.dimension
                || session != null && !session.key().playerId().equals(mc.player.getUniqueID())) {
            connection = mc.getConnection();
            player = mc.player;
            world = mc.world;
            dimension = player.dimension;
            session = null;
            retryTicks = 0;
            wasCreative = player.isCreative();
        }
        if (pendingOffer != null) {
            GIntentProtocol.Packet offer = pendingOffer;
            pendingOffer = null;
            if (offeringConnection == connection && offer.key().playerId().equals(player.getUniqueID())
                    && offer.key().dimension() == player.dimension) {
                if (session == null || !session.key().equals(offer.key())) session = new GIntentClientSession(offer.key());
                GIntentNetwork.sendToServer(session.hello());
            }
        }
        if (session == null) return;
        if (wasCreative != player.isCreative()) {
            wasCreative = player.isCreative();
            session.eligibilityChanged();
        }
        session.requestEffective(requestedOn, player.isCreative()).ifPresent(GIntentNetwork::sendToServer);
        if (++retryTicks >= 20) {
            retryTicks = 0;
            session.retry().ifPresent(GIntentNetwork::sendToServer);
        }
    }

    public static void reset() { INSTANCE.clear(); }

    public static boolean acknowledgedOn(EntityPlayerSP actor) {
        return acknowledgedIntent(actor).orElse(false);
    }

    /** Empty until the current actor, world, connection, and negotiated G state are all exact. */
    public static java.util.Optional<Boolean> acknowledgedIntent(EntityPlayerSP actor) {
        Minecraft mc = Minecraft.getMinecraft();
        if (actor != null && actor == INSTANCE.player && actor == mc.player && actor.world == INSTANCE.world
                && INSTANCE.connection == mc.getConnection() && actor.dimension == INSTANCE.dimension
                && INSTANCE.session != null) return INSTANCE.session.acknowledgedIntent();
        return java.util.Optional.empty();
    }

    private void clear() {
        connection = null;
        player = null;
        world = null;
        session = null;
        pendingOffer = null;
        offeringConnection = null;
        retryTicks = 0;
    }

    @SubscribeEvent public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote && event.getWorld() == world) clear();
    }

    @SubscribeEvent public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (connection != null && connection.getNetworkManager() == event.getManager()) clear();
        });
    }
}
