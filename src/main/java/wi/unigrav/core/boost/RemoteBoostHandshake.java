package wi.unigrav.core.boost;

/** Pure HELLO admission reducer used by the live per-connection server session. */
final class RemoteBoostHandshake {

    enum Decision { DROP, NEGOTIATE, DUPLICATE }

    private RemoteBoostHandshake() {
    }

    static Decision classify(boolean alreadyNegotiated, long expectedNonce,
            BoostProtocol.Packet packet) {
        if (packet == null || packet.kind() != BoostProtocol.Kind.HELLO
                || packet.version() != BoostProtocol.VERSION || packet.nonce() != expectedNonce
                || !packet.flag()) {
            return Decision.DROP;
        }
        return alreadyNegotiated ? Decision.DUPLICATE : Decision.NEGOTIATE;
    }
}
