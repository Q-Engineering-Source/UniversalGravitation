package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Production cap-selection tests shared by remote drift and session validation. */
class RemoteBoostCapTest {

    @Test
    void lowServerCeilingBeatsLocalDriftLimitOrLocalUnlimited() {
        assertEquals(5.0, RemoteBoostCap.driftLimit(5.0, 20.0));
        assertEquals(5.0, RemoteBoostCap.driftLimit(5.0, 0.0));
    }

    @Test
    void normalServerCeilingLeavesLowerLocalTwentyUnchanged() {
        assertEquals(20.0, RemoteBoostCap.driftLimit(500.0, 20.0));
    }

    @Test
    void malformedOrOversizedRemoteCeilingIsRejected() {
        assertFalse(RemoteBoostCap.validRemoteCeiling(0.0));
        assertFalse(RemoteBoostCap.validRemoteCeiling(500.000001));
        assertFalse(RemoteBoostCap.validRemoteCeiling(Double.NaN));
    }
}
