package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure fallback-sample cadence tests. */
class RemoteBoostCadenceTest {

    @Test
    void missingPacketForCurrentTickNeedsExactlyOneFallback() {
        RemoteBoostCadence.Marker current = new RemoteBoostCadence.Marker(12, 400, 3L);
        assertTrue(RemoteBoostCadence.needsFallback(null, current));
        assertFalse(RemoteBoostCadence.needsFallback(current, current));
    }

    @Test
    void epochOrEntityReplacementCannotReuseEarlierEmission() {
        RemoteBoostCadence.Marker prior = new RemoteBoostCadence.Marker(12, 400, 3L);
        assertTrue(RemoteBoostCadence.needsFallback(prior,
                new RemoteBoostCadence.Marker(12, 400, 4L)));
        assertTrue(RemoteBoostCadence.needsFallback(prior,
                new RemoteBoostCadence.Marker(13, 400, 3L)));
        assertTrue(RemoteBoostCadence.needsFallback(prior,
                new RemoteBoostCadence.Marker(12, 401, 3L)));
    }
}
