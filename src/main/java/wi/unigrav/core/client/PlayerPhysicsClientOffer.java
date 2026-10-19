package wi.unigrav.core.client;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Pending negotiation only: emits before legacy movement, never installs an owner. */
public final class PlayerPhysicsClientOffer {
    private PlayerPhysicsProtocol.Packet offer;
    private long sequence;

    public boolean accept(PlayerPhysicsProtocol.Packet incoming) {
        Objects.requireNonNull(incoming, "offer");
        if (incoming.kind() != PlayerPhysicsProtocol.Kind.OFFER) return false;
        if (offer != null) {
            if (!offer.scope().playerId().equals(incoming.scope().playerId())
                    || offer.scope().dimension() != incoming.scope().dimension()) return false;
            if (offer.scope().equals(incoming.scope())) {
                if (incoming.offerSequence() < offer.offerSequence()) return false;
            } else {
                if (incoming.scope().worldEpoch() <= offer.scope().worldEpoch()) return false;
                sequence = 0;
            }
        }
        offer = incoming;
        return true;
    }

    boolean isCurrentOffer(PlayerPhysicsProtocol.Packet exactOffer) {
        return offer == exactOffer;
    }

    public CPacketPlayer prepare(CPacketPlayer packet, Vec3d transport, float yaw, float pitch,
            boolean remoteCustody, Optional<BoostProtocol.StateApplication> application,
            Consumer<PlayerPhysicsProtocol.Packet> outbound) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(application, "application");
        if (offer == null || remoteCustody && application.isEmpty()) return packet;
        if (application.isPresent() && application.orElseThrow().state().dimension() != offer.scope().dimension()) {
            return packet;
        }
        if (sequence == Long.MAX_VALUE) throw new IllegalStateException("Physics outgoing sequence exhausted");
        CPacketPlayer positioned = packet;
        if (!(packet instanceof CPacketPlayer.Position) && !(packet instanceof CPacketPlayer.PositionRotation)) {
            positioned = packet instanceof CPacketPlayer.Rotation
                    ? new CPacketPlayer.PositionRotation(transport.x(), transport.y(), transport.z(),
                            packet.getYaw(yaw), packet.getPitch(pitch), packet.isOnGround())
                    : new CPacketPlayer.Position(transport.x(), transport.y(), transport.z(), packet.isOnGround());
        }
        // Reserve before transmission: a failing transport must not reuse an emitted sequence.
        var apply = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.APPLY, offer.scope(),
                ++sequence, offer.offerSequence(), application.map(PlayerPhysicsProtocol.ApplicationId::from),
                Optional.empty(), Optional.empty());
        outbound.accept(apply);
        return positioned;
    }
}
