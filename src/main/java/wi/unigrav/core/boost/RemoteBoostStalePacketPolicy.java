package wi.unigrav.core.boost;

/** Pure stale-epoch decision used before live MODE/FRAME state mutation. */
final class RemoteBoostStalePacketPolicy {
    private RemoteBoostStalePacketPolicy() {
    }

    static boolean drop(long packetEpoch, long sessionEpoch, BoostProtocol.Kind kind) {
        return (kind == BoostProtocol.Kind.MODE || kind == BoostProtocol.Kind.FRAME)
                && packetEpoch < sessionEpoch;
    }
}
