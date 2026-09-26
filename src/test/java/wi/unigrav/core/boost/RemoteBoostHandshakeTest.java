package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

/** Covers the production HELLO reducer without a fake server/player fixture. */
class RemoteBoostHandshakeTest {

    private static final long NONCE = 41L;

    @Test
    void admitsOnlyTheFirstValidHelloAndMakesRetransmissionIdempotent() {
        BoostProtocol.Packet hello = BoostProtocol.hello(NONCE, true);

        assertEquals(RemoteBoostHandshake.Decision.NEGOTIATE,
                RemoteBoostHandshake.classify(false, NONCE, hello));
        assertEquals(RemoteBoostHandshake.Decision.DUPLICATE,
                RemoteBoostHandshake.classify(true, NONCE, hello));
    }

    @Test
    void dropsStaleNonceWrongVersionAndUnreadyHello() {
        assertEquals(RemoteBoostHandshake.Decision.DROP,
                RemoteBoostHandshake.classify(true, NONCE, BoostProtocol.hello(NONCE + 1L, true)));
        assertEquals(RemoteBoostHandshake.Decision.DROP,
                RemoteBoostHandshake.classify(true, NONCE,
                        new BoostProtocol.Packet(BoostProtocol.Kind.HELLO, 1, NONCE, 0L, 0L,
                                0, BoostProtocol.Mode.UNAVAILABLE, 0, 0.0D,
                                BoostProtocol.Operation.NONE, Vec3.ZERO,
                                true, BoostProtocol.Reason.NONE)));
        assertEquals(RemoteBoostHandshake.Decision.DROP,
                RemoteBoostHandshake.classify(false, NONCE, BoostProtocol.hello(NONCE, false)));
    }
}
