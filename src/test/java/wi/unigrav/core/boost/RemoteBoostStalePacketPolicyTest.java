package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteBoostStalePacketPolicyTest {
    @Test
    void dropsClosedEpochFramesInReadyAndFreshActiveEpochs() {
        assertTrue(RemoteBoostStalePacketPolicy.drop(4L, 5L, BoostProtocol.Kind.FRAME));
        assertTrue(RemoteBoostStalePacketPolicy.drop(4L, 6L, BoostProtocol.Kind.FRAME));
        assertTrue(RemoteBoostStalePacketPolicy.drop(4L, 5L, BoostProtocol.Kind.MODE));
        assertFalse(RemoteBoostStalePacketPolicy.drop(5L, 5L, BoostProtocol.Kind.FRAME));
        assertFalse(RemoteBoostStalePacketPolicy.drop(6L, 5L, BoostProtocol.Kind.FRAME));
    }
}
