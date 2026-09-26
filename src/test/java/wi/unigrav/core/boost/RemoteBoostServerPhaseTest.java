package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Locks the packet-pairing phase contract independently of Minecraft runtime classes. */
class RemoteBoostServerPhaseTest {
    @Test
    void armingPreGrantPositionCannotSeedWithoutCurrentFrame() {
        assertFalse(RemoteBoostServerPhase.maySeed(RemoteBoostServerPhase.Value.ARMING, false, true));
        assertFalse(RemoteBoostServerPhase.maySeed(RemoteBoostServerPhase.Value.ARMING, true, false));
        assertTrue(RemoteBoostServerPhase.maySeed(RemoteBoostServerPhase.Value.ARMING, true, true));
    }

    @Test
    void matchingNativeConfirmTransitionsOnlyResync() {
        assertTrue(RemoteBoostServerPhase.afterMatchingNativeConfirm(
                RemoteBoostServerPhase.Value.RESYNC, true, true) == RemoteBoostServerPhase.Value.ACTIVE);
        assertTrue(RemoteBoostServerPhase.afterMatchingNativeConfirm(
                RemoteBoostServerPhase.Value.RESYNC, false, true) == RemoteBoostServerPhase.Value.RESYNC);
        assertTrue(RemoteBoostServerPhase.afterMatchingNativeConfirm(
                RemoteBoostServerPhase.Value.ACTIVE, true, true) == RemoteBoostServerPhase.Value.ACTIVE);
        assertTrue(RemoteBoostServerPhase.afterMatchingNativeConfirm(
                RemoteBoostServerPhase.Value.RESYNC, true, false)
                == RemoteBoostServerPhase.Value.ARMING);
    }
}
