package wi.unigrav.core.player;

import java.util.Objects;

/** Independent simulated server receiver window; every frame consumes the same numeric Work. */
public final class NativeTransferWireTestSupport {
    private final StoredDyadic.Work work = new StoredDyadic.Work();

    public PlayerPhysicsProtocol.Packet serverboundRoundTrip(PlayerPhysicsProtocol.Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (packet.clientbound()) throw new IllegalArgumentException("Expected serverbound native peer packet");
        byte[] bytes = PlayerPhysicsProtocol.encode(packet);
        return PlayerPhysicsProtocol.decode(bytes, new PhysicsDecodeBudget(bytes.length, work));
    }
}
