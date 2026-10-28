package wi.unigrav.core.client.camera;

import wi.unigrav.api.math.Vec3;
import java.util.Objects;
import wi.unigrav.core.player.CardinalFrame;

/** Pure state for persistent alignment and temporary upright-reference selection. */
final class SixDofAlignmentInput {

    private boolean enabled;
    private Reference reference = Reference.Y_POS;
    private boolean armed;
    private boolean previousToggleHeld;
    private boolean previousSelectHeld;
    private boolean selectionRequiresRelease;
    private boolean selecting;
    private Reference previewReference;

    /** Samples edges early, but defers a release commit until the render-frame fresh pick. */
    Change updateBeforePicking(boolean toggleHeld, boolean selectHeld) {
        return update(toggleHeld, selectHeld || (!toggleHeld && selecting), previewReference);
    }

    boolean effectiveAlignment(boolean selectChordPhysicallyHeld) {
        return enabled && !selecting && !selectChordPhysicallyHeld;
    }

    Change update(boolean toggleHeld, boolean selectHeld, Reference hoveredReference) {
        if (!armed) {
            armed = true;
            selectionRequiresRelease = selectHeld;
            latch(toggleHeld, selectHeld);
            return Change.NONE;
        }

        boolean previousEnabled = enabled;
        Reference previousReference = reference;
        boolean wasSelecting = selecting;
        Reference previousPreview = previewReference;
        boolean committed = false;
        if (toggleHeld && selectHeld) {
            cancelSelection();
            selectionRequiresRelease = true;
            latch(toggleHeld, selectHeld);
            return change(previousEnabled, previousReference, wasSelecting, previousPreview, false);
        }

        boolean togglePressed = toggleHeld && !previousToggleHeld;
        boolean selectPressed = selectHeld && !previousSelectHeld;
        if (togglePressed) {
            cancelSelection();
            selectionRequiresRelease = false;
            enabled = !enabled;
        } else if (!selectHeld) {
            boolean commitAllowed = previousSelectHeld && wasSelecting
                    && !selectionRequiresRelease;
            if (commitAllowed && hoveredReference != null) {
                reference = hoveredReference;
                enabled = true;
                committed = true;
            }
            cancelSelection();
            selectionRequiresRelease = false;
        } else if (!selectionRequiresRelease && (selectPressed || selecting)) {
            selecting = true;
            previewReference = hoveredReference;
        } else {
            cancelSelection();
        }
        latch(toggleHeld, selectHeld);
        return change(previousEnabled, previousReference, wasSelecting, previousPreview, committed);
    }

    void suspend() {
        armed = false;
        selectionRequiresRelease = true;
        cancelSelection();
    }

    /** GUI face selection commits through the same state and downstream request as an Alt+H release. */
    Change selectReference(Reference selected) {
        boolean previousEnabled = enabled;
        Reference previousReference = reference;
        boolean wasSelecting = selecting;
        Reference previousPreview = previewReference;
        reference = Objects.requireNonNull(selected);
        enabled = true;
        cancelSelection();
        return change(previousEnabled, previousReference, wasSelecting, previousPreview, true);
    }

    Change toggleEnabled() {
        boolean previousEnabled = enabled;
        boolean wasSelecting = selecting;
        Reference previousPreview = previewReference;
        enabled = !enabled;
        cancelSelection();
        return change(previousEnabled, reference, wasSelecting, previousPreview, enabled);
    }

    void disable() {
        enabled = false;
        selectionRequiresRelease = previousSelectHeld;
        cancelSelection();
    }

    boolean enabled() {
        return enabled;
    }

    Reference reference() {
        return reference;
    }

    boolean selecting() {
        return selecting;
    }

    Reference previewReference() {
        return previewReference;
    }

    private Change change(boolean previousEnabled, Reference previousReference,
            boolean wasSelecting, Reference previousPreview, boolean committed) {
        return new Change(enabled != previousEnabled, reference != previousReference,
                selecting != wasSelecting || previewReference != previousPreview, committed);
    }

    private void cancelSelection() {
        selecting = false;
        previewReference = null;
    }

    private void latch(boolean toggleHeld, boolean selectHeld) {
        previousToggleHeld = toggleHeld;
        previousSelectHeld = selectHeld;
    }

    enum Reference {
        Y_POS("Y+", new Vec3(0.0, 1.0, 0.0)),
        Y_NEG("Y-", new Vec3(0.0, -1.0, 0.0)),
        X_POS("X+", new Vec3(1.0, 0.0, 0.0)),
        X_NEG("X-", new Vec3(-1.0, 0.0, 0.0)),
        Z_POS("Z+", new Vec3(0.0, 0.0, 1.0)),
        Z_NEG("Z-", new Vec3(0.0, 0.0, -1.0));

        private final String label;
        private final Vec3 normal;

        Reference(String label, Vec3 normal) {
            this.label = label;
            this.normal = normal;
        }

        String label() {
            return label;
        }

        Vec3 normal() {
            return normal;
        }

        CardinalFrame cardinalFrame() {
            return switch (this) {
                case Y_POS -> CardinalFrame.Y_POS;
                case Y_NEG -> CardinalFrame.Y_NEG;
                case X_POS -> CardinalFrame.X_POS;
                case X_NEG -> CardinalFrame.X_NEG;
                case Z_POS -> CardinalFrame.Z_POS;
                case Z_NEG -> CardinalFrame.Z_NEG;
            };
        }

    }

    record Change(boolean enabledChanged, boolean referenceChanged,
            boolean selectionChanged, boolean committed) {
        private static final Change NONE = new Change(false, false, false, false);
    }
}
