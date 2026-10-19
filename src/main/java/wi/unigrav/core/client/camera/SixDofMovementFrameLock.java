package wi.unigrav.core.client.camera;

import java.util.Objects;

/** Owns the committed movement basis while leaving camera orientation independently live. */
final class SixDofMovementFrameLock {

    private SixDofMovementFrame committedFrame;

    void apply(SixDofAlignmentInput.Change change, SixDofAlignmentInput alignmentInput,
            SixDofCameraOrientation.Snapshot attitude) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(alignmentInput, "alignmentInput");
        if (change.committed() || (change.enabledChanged() && alignmentInput.enabled())) {
            committedFrame = SixDofMovementFrame.locked(alignmentInput.reference(), attitude);
        } else if (change.enabledChanged() && !alignmentInput.enabled()) {
            committedFrame = null;
        }
    }

    SixDofMovementFrame current(boolean effectiveAlignment,
            SixDofCameraOrientation.Snapshot attitude) {
        if (!effectiveAlignment) {
            return SixDofMovementFrame.fromCamera(attitude);
        }
        if (committedFrame == null) {
            throw new IllegalStateException("Enabled horizon alignment has no committed movement frame");
        }
        return committedFrame;
    }

    void clear() {
        committedFrame = null;
    }
}
