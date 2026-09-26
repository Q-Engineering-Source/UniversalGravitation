package wi.unigrav.core.client.boost;

import java.util.Optional;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Test transport peer exercising the actual client application state machine. */
public final class RemoteApplicationFixture {
    private Vec3 velocity = Vec3.ZERO;
    private final RemoteBoostSession session = new RemoteBoostSession(packet -> {
        velocity = switch (packet.operation()) {
            case NONE -> velocity;
            case ADD -> velocity.add(packet.velocity());
            case REPLACE -> packet.velocity();
            case STOP -> Vec3.ZERO;
        };
        return true;
    });

    public void receive(BoostProtocol.Packet packet) {
        if (packet.kind() == BoostProtocol.Kind.OFFER) session.acceptOffer(packet);
        else if (packet.kind() == BoostProtocol.Kind.STATE) session.acceptState(packet, packet.dimension());
    }

    public Optional<PlayerPhysicsProtocol.ApplicationId> application() {
        return session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from);
    }
}
