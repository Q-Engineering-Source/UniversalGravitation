package wi.unigrav.core.client.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import wi.unigrav.api.math.Vec3;

/** Formats the free full-quaternion six-DOF state for Forge's F3 text overlay. */
final class SixDofCameraDebugText {

    private static final String PREFIX = "[unigrav 6dof] ";

    private SixDofCameraDebugText() {
    }

    /**
     * Returns the debug lines for the authoritative free-camera quaternion.
     */
    static List<String> format(SixDofCameraOrientation.Snapshot visibleView,
            boolean invertMouse) {
        return format(visibleView, invertMouse, new RollState(false, 0.0, 0.0));
    }

    /** Returns FREE attitude lines plus the independent local-Z inertia state. */
    static List<String> format(SixDofCameraOrientation.Snapshot visibleView,
            boolean invertMouse, RollState rollState) {
        return format(visibleView, invertMouse, rollState, null);
    }

    /** Returns FREE attitude, roll inertia, and optionally selected control gears. */
    static List<String> format(SixDofCameraOrientation.Snapshot visibleView,
            boolean invertMouse, RollState rollState, GearState gearState) {
        return format(visibleView, invertMouse, rollState, gearState, null);
    }

    /** Adds optional horizon-alignment diagnostics without changing the legacy output contract. */
    static List<String> format(SixDofCameraOrientation.Snapshot visibleView,
            boolean invertMouse, RollState rollState, GearState gearState,
            AlignmentState alignmentState) {
        if (visibleView == null) {
            return List.of();
        }
        RollState effectiveRollState = rollState != null
                ? rollState : new RollState(false, 0.0, 0.0);

        SixDofCameraOrientation.Components quaternion = visibleView.components();
        Vec3 forward = visibleView.forward();
        Vec3 visibleLeft = visibleView.right().negate();
        Vec3 up = visibleView.up();
        Vec3 mouseXAxis = up.negate();
        Vec3 mouseYAxis = invertMouse ? visibleLeft : visibleLeft.negate();
        if (alignmentState != null && alignmentState.effective()) {
            SixDofHorizonAlignment.Frame frame = SixDofHorizonAlignment.frame(
                    alignmentState.reference().normal(), forward, up);
            Vec3 referenceLeft = frame.left();
            mouseXAxis = frame.normal().negate();
            mouseYAxis = invertMouse ? referenceLeft : referenceLeft.negate();
        }
        double mouseAxesDot = mouseXAxis.dot(mouseYAxis);
        double mouseAxesCrossArea = mouseXAxis.cross(mouseYAxis).norm();

        List<String> lines = new ArrayList<>((gearState != null ? 6 : 5)
                + (alignmentState != null ? 2 : 0));
        lines.add(String.format(Locale.ROOT,
                        PREFIX + "mode=%s q(wxyz)=(%.3f,%.3f,%.3f,%.3f)",
                        alignmentState != null && alignmentState.effective() ? "ALIGN" : "FREE",
                        compact(quaternion.w()), compact(quaternion.x()), compact(quaternion.y()),
                        compact(quaternion.z())));
        lines.add(String.format(Locale.ROOT,
                        PREFIX + "view +Z=%s +X=%s +Y=%s",
                        vector(forward), vector(visibleLeft), vector(up)));
        lines.add(String.format(Locale.ROOT,
                        PREFIX + "mouse +X axis=%s +Y axis=%s roll axis=%s",
                        vector(mouseXAxis), vector(mouseYAxis), vector(forward)));
        lines.add(String.format(Locale.ROOT,
                        PREFIX + "mouse axes dot=%.3f cross-area=%.3f",
                        compact(mouseAxesDot), compact(mouseAxesCrossArea)));
        lines.add(String.format(Locale.ROOT,
                        PREFIX + "roll owner=%s input=%.3f omega=%.2f°/s state=%s",
                        effectiveRollState.owned(), compact(effectiveRollState.inputAxis()),
                        compact(effectiveRollState.angularVelocityDegreesPerSecond()),
                        rollStateName(effectiveRollState)));
        if (gearState != null) {
            lines.add(String.format(Locale.ROOT,
                    PREFIX + "gears roll=%d/%d max=%.0f°/s accel=%.0f°/s² "
                            + "boost=%d/%d target=%.2fm/s accel=%.2fm/s²",
                    gearState.rollGear(), gearState.rollGearCount(),
                    gearState.rollMaximumSpeedDegreesPerSecond(),
                    gearState.rollAccelerationDegreesPerSecondSquared(), gearState.boostGear(),
                    gearState.boostGearCount(), gearState.boostSpeedLimitMps(),
                    gearState.boostAccelerationMps2()));
        }
        if (alignmentState != null) {
            lines.add(String.format(Locale.ROOT,
                    PREFIX + "align state=%s enabled=%s selected=%s preview=%s effective=%s pitchLimited=%s",
                    alignmentState.selecting() ? "SELECT" : "HOLD",
                    alignmentState.enabled(), alignmentState.reference().label(), previewLabel(alignmentState),
                    alignmentState.effective(), alignmentState.pitchLimited()));
            SixDofCameraOrientation.HorizonResult correction = alignmentState.correction();
            lines.add(String.format(Locale.ROOT,
                    PREFIX + "align autoBank=%.2f°/s autoPitch=%.2f°/s refPitch=%.2f° bankError=%.2f° acquiring=%s",
                    correctionValue(correction, SixDofCameraOrientation.HorizonResult::autoBankDegreesPerSecond),
                    correctionValue(correction, SixDofCameraOrientation.HorizonResult::autoPitchDegreesPerSecond),
                    correctionValue(correction, SixDofCameraOrientation.HorizonResult::referencePitchDegrees),
                    correctionValue(correction, SixDofCameraOrientation.HorizonResult::bankErrorDegrees),
                    correction != null && correction.acquiring()));
        }
        return List.copyOf(lines);
    }

    private static String previewLabel(AlignmentState alignmentState) {
        if (!alignmentState.selecting()) {
            return "NONE";
        }
        return alignmentState.previewReference() == null
                ? "NO_HIT" : alignmentState.previewReference().label();
    }

    private static double correctionValue(SixDofCameraOrientation.HorizonResult correction,
            ToDoubleFunction<SixDofCameraOrientation.HorizonResult> value) {
        if (correction == null) {
            return 0.0;
        }
        return compact(value.applyAsDouble(correction));
    }

    private static String rollStateName(RollState rollState) {
        if (!rollState.owned()) {
            return "UNOWNED";
        }
        if (Math.abs(rollState.inputAxis()) >= 0.0005) {
            return "DRIVE";
        }
        if (!SixDofRollDynamics.isStopped(
                rollState.angularVelocityDegreesPerSecond())) {
            return "COAST";
        }
        return "STOPPED";
    }

    private static String vector(Vec3 value) {
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)",
                compact(value.x()), compact(value.y()), compact(value.z()));
    }

    private static double compact(double value) {
        return Math.abs(value) < 0.0005 ? 0.0 : value;
    }

    record RollState(boolean owned, double inputAxis,
            double angularVelocityDegreesPerSecond) {
    }

    record GearState(int rollGear, int rollGearCount, double rollMaximumSpeedDegreesPerSecond,
            double rollAccelerationDegreesPerSecondSquared, int boostGear, int boostGearCount,
            double boostSpeedLimitMps, double boostAccelerationMps2) {
    }

    record AlignmentState(boolean enabled, boolean selecting,
            SixDofAlignmentInput.Reference reference,
            SixDofAlignmentInput.Reference previewReference, boolean effective,
            boolean pitchLimited, SixDofCameraOrientation.HorizonResult correction) {
        AlignmentState {
            reference = Objects.requireNonNull(reference, "reference");
        }
    }
}
