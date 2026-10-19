package wi.unigrav.core.player;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import wi.unigrav.core.client.PlayerPhysicsClient;

/** Client-only adapter from one accepted OFFER decode to its exact physical owner scope. */
@SideOnly(Side.CLIENT)
public final class ClientOwnerScopeBridge {
    private ClientOwnerScopeBridge() { }

    public static Optional<PlayerPhysicsInboundConnections.ScopeHandle> openAcceptedOfferScope(
            PlayerPhysicsInboundConnections.DecodeHandle exactDecode,
            PlayerPhysicsClient.AcceptedOfferContext context) {
        PlayerPhysicsInboundConnections facade = PlayerPhysicsInboundConnections.INSTANCE;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("Accepted OFFER scope must be opened on the actual client owner thread");
        Optional<PlayerPhysicsProtocol.Packet> materialized = facade.exactMaterializedPacket(exactDecode);
        if (materialized.isEmpty()) return Optional.empty();
        PlayerPhysicsProtocol.Packet exactOffer = materialized.orElseThrow();
        NetworkManager manager = facade.exactManager(exactDecode);
        if (!(facade.exactHandler(exactDecode) instanceof NetHandlerPlayClient clientHandler))
            return Optional.empty();

        synchronized (manager) {
            if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()
                    || manager.getNetHandler() != clientHandler
                    || !PlayerPhysicsClient.acceptedOfferCurrent(
                    exactDecode, manager, clientHandler, exactOffer, context)) return Optional.empty();
            Optional<PlayerPhysicsInboundConnections.ScopeHandle> opened =
                    facade.openAcceptedOfferScopeForOwner(exactDecode, exactOffer.scope());
            if (opened.isEmpty()) return Optional.empty();
            if (!PlayerPhysicsClient.acceptedOfferCurrent(
                    exactDecode, manager, clientHandler, exactOffer, context)) {
                facade.retireScope(opened.orElseThrow());
                return Optional.empty();
            }
            return opened;
        }
    }
}
