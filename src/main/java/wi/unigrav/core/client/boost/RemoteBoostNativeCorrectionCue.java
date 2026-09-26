package wi.unigrav.core.client.boost;

import wi.unigrav.api.math.Vec3;

/** One-shot cue joining native pre-zero capture, ConfirmTeleport and its position echo. */
final class RemoteBoostNativeCorrectionCue {

    private Vec3 preZeroHint;
    private boolean confirmationSent;

    void capturePreZero(Vec3 finiteHint) {
        preZeroHint = finiteHint;
        confirmationSent = false;
    }

    void confirmSent() {
        if (preZeroHint != null) {
            confirmationSent = true;
        }
    }

    /** Returns the captured hint exactly once, only after the native confirm. */
    Vec3 consumeEchoHint() {
        if (!confirmationSent) {
            return null;
        }
        Vec3 result = preZeroHint;
        clearPending();
        return result;
    }

    void clearPending() {
        preZeroHint = null;
        confirmationSent = false;
    }

    void clear() {
        clearPending();
    }
}
