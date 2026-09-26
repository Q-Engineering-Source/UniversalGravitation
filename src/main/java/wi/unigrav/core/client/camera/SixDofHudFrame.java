package wi.unigrav.core.client.camera;

import java.util.Objects;

/**
 * Immutable, renderer-neutral snapshot for the first FREE six-DOF HUD.
 *
 * <p>Angles are display angles in degrees; speed is metres per second and roll rates are
 * degrees per second. This snapshot deliberately says what the player requested or is
 * authorized to do, rather than claiming a resulting physics impulse.</p>
 */
record SixDofHudFrame(Attitude attitude, double speedMps, boolean driftAuthorized,
        ThrustInput thrustInput, Boost boost, Roll roll, Alignment alignment) {

    SixDofHudFrame {
        attitude = Objects.requireNonNull(attitude, "attitude");
        thrustInput = Objects.requireNonNull(thrustInput, "thrustInput");
        boost = Objects.requireNonNull(boost, "boost");
        roll = Objects.requireNonNull(roll, "roll");
        alignment = Objects.requireNonNull(alignment, "alignment");
        requireNonNegativeFinite(speedMps, "speedMps");
    }

    record Attitude(double pitchDegrees, double yawDegrees, double rollDegrees) {
        Attitude {
            requireFinite(pitchDegrees, "pitchDegrees");
            requireFinite(yawDegrees, "yawDegrees");
            requireFinite(rollDegrees, "rollDegrees");
        }
    }

    record Boost(int gear, int gearCount, double selectedSpeedMps, double targetSpeedMps,
            double carrierCeilingMps, boolean restrictedCorridor) {
        Boost {
            requireGear(gear, gearCount, "boost gear");
            requireNonNegativeFinite(selectedSpeedMps, "selectedSpeedMps");
            requireNonNegativeFinite(targetSpeedMps, "targetSpeedMps");
            requireNonNegativeFinite(carrierCeilingMps, "carrierCeilingMps");
        }

        /** Existing non-remote HUD callers have no distinct carried envelope. */
        Boost(int gear, int gearCount, double targetSpeedMps) {
            this(gear, gearCount, targetSpeedMps, targetSpeedMps, targetSpeedMps, false);
        }
    }

    record Roll(int gear, double maximumDps, double omegaDps, double inputAxis) {
        Roll {
            requireGear(gear, 5, "roll gear");
            requireNonNegativeFinite(maximumDps, "maximumDps");
            requireFinite(omegaDps, "omegaDps");
            requireFinite(inputAxis, "inputAxis");
            if (inputAxis < -1.0 || inputAxis > 1.0) {
                throw new IllegalArgumentException("inputAxis must be in [-1, 1]");
            }
        }
    }

    record Alignment(boolean enabled, SixDofAlignmentInput.Reference reference, boolean selecting,
            SixDofAlignmentInput.Reference previewReference) {
        Alignment {
            reference = Objects.requireNonNull(reference, "reference");
            if (!selecting && previewReference != null) {
                throw new IllegalArgumentException(
                        "previewReference is only valid while selecting");
            }
        }
    }

    enum ThrustInput {
        NONE,
        DRIFT,
        BOOST,
        BOOST_AND_DRIFT
    }

    private static void requireGear(int gear, int gearCount, String name) {
        if (gearCount < 1) {
            throw new IllegalArgumentException(name + " count must be positive");
        }
        if (gear < 1 || gear > gearCount) {
            throw new IllegalArgumentException(name + " must be in [1, " + gearCount + "]");
        }
    }

    private static void requireNonNegativeFinite(double value, String name) {
        requireFinite(value, name);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
