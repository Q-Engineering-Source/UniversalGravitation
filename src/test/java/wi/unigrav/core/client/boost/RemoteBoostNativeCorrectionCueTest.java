package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

/** Pure cue tests for the native direct NetworkManager correction sequence. */
class RemoteBoostNativeCorrectionCueTest {

    @Test
    void onlyConfirmThenOneEchoConsumesThePreZeroHint() {
        RemoteBoostNativeCorrectionCue cue = new RemoteBoostNativeCorrectionCue();
        Vec3 beforeZero = new Vec3(300.0, -4.0, 12.0);
        cue.capturePreZero(beforeZero);
        assertNull(cue.consumeEchoHint());
        cue.confirmSent();
        assertEquals(beforeZero, cue.consumeEchoHint());
        assertNull(cue.consumeEchoHint());
    }

    @Test
    void newerCaptureAndExplicitClearCannotReuseAnOlderCorrection() {
        RemoteBoostNativeCorrectionCue cue = new RemoteBoostNativeCorrectionCue();
        cue.capturePreZero(new Vec3(40.0, 0.0, 0.0));
        cue.confirmSent();
        cue.capturePreZero(new Vec3(80.0, 0.0, 0.0));
        cue.confirmSent();
        assertEquals(new Vec3(80.0, 0.0, 0.0), cue.consumeEchoHint());
        cue.capturePreZero(new Vec3(20.0, 0.0, 0.0));
        cue.clearPending();
        assertNull(cue.consumeEchoHint());
    }
}
