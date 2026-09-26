package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;

/** Pure no-spam presentation tests. */
class RemoteBoostPresentationTest {

    @Test
    void modeAckIsShownOnceButIdenticalStateIsQuiet() {
        BoostProtocol.Packet active = state(BoostProtocol.Mode.ACTIVE,
                BoostProtocol.Operation.NONE, BoostProtocol.Reason.NONE);
        assertEquals(RemoteBoostPresentation.Kind.MODE,
                RemoteBoostPresentation.notice(BoostProtocol.Mode.READY, active).kind());
        assertNull(RemoteBoostPresentation.notice(BoostProtocol.Mode.ACTIVE, active));
    }

    @Test
    void reasonAndStopAlwaysRemainVisibleWithoutFrameNoise() {
        BoostProtocol.Packet denied = state(BoostProtocol.Mode.READY,
                BoostProtocol.Operation.NONE, BoostProtocol.Reason.UNSUPPORTED_STATE);
        assertEquals(RemoteBoostPresentation.Kind.REASON,
                RemoteBoostPresentation.notice(BoostProtocol.Mode.READY, denied).kind());
        BoostProtocol.Packet stop = state(BoostProtocol.Mode.READY,
                BoostProtocol.Operation.STOP, BoostProtocol.Reason.COLLISION);
        assertEquals(RemoteBoostPresentation.Kind.SAFETY_STOP,
                RemoteBoostPresentation.notice(BoostProtocol.Mode.ACTIVE, stop).kind());
    }

    private static BoostProtocol.Packet state(BoostProtocol.Mode mode,
            BoostProtocol.Operation operation, BoostProtocol.Reason reason) {
        return BoostProtocol.state(7L, 1L, 1L, 0, mode, 5, 490.0,
                operation, Vec3.ZERO, reason);
    }
}
