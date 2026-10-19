package wi.unigrav.core.player;

/** Bounded readback of actual outbound frames, sharing numeric work across one fixture. */
public final class RawFixturePacketDecoder {
    private final StoredDyadic.Work numericWork = new StoredDyadic.Work();

    public PlayerPhysicsProtocol.Packet decode(byte[] bytes) {
        return PlayerPhysicsProtocol.decode(bytes, new PhysicsDecodeBudget(bytes.length, numericWork));
    }
}