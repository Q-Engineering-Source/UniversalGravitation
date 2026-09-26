package wi.unigrav.core.client.camera;

import java.util.Objects;

import wi.unigrav.api.math.Vec3;

/**
 * Stateful, pure-Java orientation for the six-degree-of-freedom camera.
 *
 * <p>The stored quaternion maps local camera vectors to world vectors. The
 * local basis is {@code +Z} forward, {@code +Y} up, and {@code +X} left
 * ({@code -X} is right). Control rates are degrees per second; elapsed time is
 * in seconds. This class deliberately has no Minecraft, Forge, UI, or OpenGL
 * dependency so its orientation contract can be tested independently.
 */
final class SixDofCameraOrientation {

    private static final double MAX_DELTA_SECONDS = 0.1;
    private static final double MAX_MOUSE_DEGREES = 45.0;

    private Quaternion orientation = Quaternion.IDENTITY;
    private boolean initialized;

    /**
     * Sets this orientation from Minecraft pitch and yaw angles, in degrees,
     * with zero roll. Positive pitch looks down and positive yaw looks left.
     * Non-finite values leave the current orientation unchanged.
     */
    void reset(double minecraftPitchDegrees, double minecraftYawDegrees) {
        if (!Double.isFinite(minecraftPitchDegrees) || !Double.isFinite(minecraftYawDegrees)) {
            return;
        }

        Quaternion yaw = Quaternion.aboutY(-Math.toRadians(minecraftYawDegrees));
        Quaternion pitch = Quaternion.aboutX(Math.toRadians(minecraftPitchDegrees));
        orientation = yaw.multiply(pitch).normalizedCanonical();
        initialized = true;
    }

    /**
     * Applies one local-space control update.
     *
     * <p>Each axis is clamped to {@code [-1, 1]}. Positive pitch rotates
     * nose-down around local {@code +X}; positive yaw rotates around local
     * {@code -Y} to match Minecraft yaw; positive roll rotates around local
     * {@code +Z}. All three inputs are combined into one axis-angle rotation.
     * Invalid axes, rate, or elapsed time are ignored. The elapsed time is
     * capped at 0.1 s to prevent a pause or frame hitch from causing a jump.
     */
    void apply(double pitchAxis, double yawAxis, double rollAxis,
            double deltaSeconds, double degreesPerSecond) {
        if (!initialized
                || !Double.isFinite(pitchAxis)
                || !Double.isFinite(yawAxis)
                || !Double.isFinite(rollAxis)
                || !Double.isFinite(deltaSeconds)
                || !Double.isFinite(degreesPerSecond)
                || deltaSeconds <= 0.0
                || degreesPerSecond < 0.0) {
            return;
        }

        double effectiveDelta = Math.min(deltaSeconds, MAX_DELTA_SECONDS);
        double localPitch = clampAxis(pitchAxis);
        double localYaw = -clampAxis(yawAxis);
        double localRoll = clampAxis(rollAxis);
        double localMagnitude = Math.hypot(Math.hypot(localPitch, localYaw), localRoll);
        if (localMagnitude == 0.0 || degreesPerSecond == 0.0) {
            return;
        }

        double angle = Math.toRadians(degreesPerSecond * effectiveDelta) * localMagnitude;
        if (!Double.isFinite(angle)) {
            return;
        }
        double inverseMagnitude = 1.0 / localMagnitude;
        Quaternion localDelta = Quaternion.axisAngle(
                localPitch * inverseMagnitude,
                localYaw * inverseMagnitude,
                localRoll * inverseMagnitude,
                angle);
        applyLocalDelta(localDelta);
    }

    /**
     * Applies a local-space rotation in degrees.
     *
     * <p>Positive pitch rotates nose-down around local {@code +X}; positive
     * yaw rotates around local {@code -Y} to match Minecraft yaw; positive
     * roll rotates around local {@code +Z}. Non-finite values and calls made
     * before initialization leave the current orientation unchanged.
     */
    void applyDegrees(double pitchDegrees, double yawDegrees, double rollDegrees) {
        if (!initialized
                || !Double.isFinite(pitchDegrees)
                || !Double.isFinite(yawDegrees)
                || !Double.isFinite(rollDegrees)) {
            return;
        }

        double localPitch = Math.toRadians(pitchDegrees);
        double localYaw = -Math.toRadians(yawDegrees);
        double localRoll = Math.toRadians(rollDegrees);
        double localMagnitude = Math.hypot(Math.hypot(localPitch, localYaw), localRoll);
        if (!Double.isFinite(localMagnitude) || localMagnitude == 0.0) {
            return;
        }
        double inverseMagnitude = 1.0 / localMagnitude;
        Quaternion localDelta = Quaternion.axisAngle(
                localPitch * inverseMagnitude,
                localYaw * inverseMagnitude,
                localRoll * inverseMagnitude,
                localMagnitude);
        applyLocalDelta(localDelta);
    }

    /**
     * Applies one raw mouse delta using Minecraft's sensitivity curve.
     *
     * <p>The signed, capped two-dimensional mouse vector becomes one local
     * axis-angle update: pitch rotates around local {@code +X}, while yaw
     * rotates around local {@code -Y}. The update is right-multiplied into the
     * complete current quaternion, so the axes remain camera-local through
     * vertical and inverted attitudes without any world-up correction or
     * Euler branch. Mouse Y retains the vanilla negative sign before this
     * mapping, and inverted mouse reverses only that original pitch sign. The
     * combined two-dimensional vector is capped at 45 degrees per sampled
     * frame without changing its direction.
     */
    void applyMouseDelta(int deltaX, int deltaY, double sensitivity, boolean invertMouse) {
        if (!initialized || !Double.isFinite(sensitivity)) {
            return;
        }

        double adjustedSensitivity = clampUnit(sensitivity);
        double multiplier = Math.pow(adjustedSensitivity * 0.6 + 0.2, 3.0) * 8.0 * 0.15;
        double yawDegrees = deltaX * multiplier;
        double pitchDegrees = (invertMouse ? deltaY : -deltaY) * multiplier;
        double mouseMagnitudeDegrees = Math.hypot(yawDegrees, pitchDegrees);
        if (!Double.isFinite(mouseMagnitudeDegrees) || mouseMagnitudeDegrees == 0.0) {
            return;
        }
        if (mouseMagnitudeDegrees > MAX_MOUSE_DEGREES) {
            double capScale = MAX_MOUSE_DEGREES / mouseMagnitudeDegrees;
            yawDegrees *= capScale;
            pitchDegrees *= capScale;
        }
        double yawRadians = Math.toRadians(yawDegrees);
        double pitchRadians = Math.toRadians(pitchDegrees);
        double angularMagnitude = Math.hypot(yawRadians, pitchRadians);
        if (!Double.isFinite(angularMagnitude) || angularMagnitude == 0.0) {
            return;
        }

        Quaternion localMouseDelta = Quaternion.axisAngle(
                pitchRadians / angularMagnitude,
                -yawRadians / angularMagnitude,
                0.0,
                angularMagnitude);
        applyLocalDelta(localMouseDelta);
    }

    /**
     * Applies a mouse update while enforcing the selected reference horizon.
     * This ON-only path leaves the FREE mouse path unchanged.
     */
    AlignedMouseResult applyAlignedMouseDelta(int deltaX, int deltaY,
            double sensitivity, boolean invertMouse, Vec3 normal) {
        if (!initialized || !Double.isFinite(sensitivity)) {
            return AlignedMouseResult.NONE;
        }

        Vec3 unitNormal = SixDofHorizonAlignment.unitNormal(normal);
        MouseDelta mouse = alignedMouseDelta(deltaX, deltaY, sensitivity, invertMouse);
        SixDofHorizonAlignment.Frame initialFrame = SixDofHorizonAlignment.frame(
                unitNormal, forward(), up());
        if (mouse.magnitudeRadians() == 0.0) {
            return new AlignedMouseResult(false,
                    Math.toDegrees(initialFrame.referencePitchRadians()));
        }

        Quaternion halfYaw = worldAxisRotation(unitNormal, -mouse.yawRadians() * 0.5);
        Quaternion intermediate = halfYaw.multiply(orientation).normalizedCanonical();
        SixDofHorizonAlignment.Frame intermediateFrame = SixDofHorizonAlignment.frame(
                unitNormal, rotate(intermediate, 0.0, 0.0, 1.0),
                rotate(intermediate, 0.0, 1.0, 0.0));
        double limitRadians = Math.toRadians(
                SixDofHorizonAlignment.Tuning.DEFAULT.hardPitchDegrees());
        double rawTarget = intermediateFrame.referencePitchRadians() - mouse.pitchRadians();
        double target = SixDofHorizonAlignment.protectedPitchTarget(
                intermediateFrame.referencePitchRadians(), mouse.pitchRadians(), limitRadians);
        Quaternion pitchCorrection = worldAxisRotation(intermediateFrame.left(),
                intermediateFrame.referencePitchRadians() - target);
        orientation = halfYaw.multiply(pitchCorrection).multiply(intermediate).normalizedCanonical();
        return new AlignedMouseResult(Math.abs(target - rawTarget) > 1.0e-12,
                Math.toDegrees(target));
    }

    HorizonResult alignHorizon(Vec3 normal, double deltaSeconds, boolean suppressBank) {
        return alignHorizon(normal, deltaSeconds, suppressBank, SixDofHorizonAlignment.Tuning.DEFAULT);
    }

    /** Applies bounded soft pitch return followed by optional forward-axis bank correction. */
    HorizonResult alignHorizon(Vec3 normal, double deltaSeconds, boolean suppressBank,
            SixDofHorizonAlignment.Tuning tuning) {
        if (!initialized) {
            return HorizonResult.NONE;
        }
        if (!Double.isFinite(deltaSeconds) || deltaSeconds < 0.0) {
            return HorizonResult.NONE;
        }

        SixDofHorizonAlignment.Tuning checkedTuning = Objects.requireNonNull(tuning, "tuning");
        Vec3 unitNormal = SixDofHorizonAlignment.unitNormal(normal);
        double effectiveDelta = Math.min(deltaSeconds, checkedTuning.maximumDeltaSeconds());
        SixDofHorizonAlignment.Frame initialFrame = SixDofHorizonAlignment.frame(
                unitNormal, forward(), up());
        double softPitchRadians = Math.toRadians(checkedTuning.softPitchDegrees());
        double hardPitchRadians = Math.toRadians(checkedTuning.hardPitchDegrees());
        double pitchOverflow = pitchOverflow(initialFrame.referencePitchRadians(), softPitchRadians);
        double pitchRotation = SixDofHorizonAlignment.boundedSoftPitchStepToZero(pitchOverflow,
                hardPitchRadians - softPitchRadians, effectiveDelta,
                checkedTuning.softPitchHalfLifeSeconds(),
                Math.toRadians(checkedTuning.maximumSoftPitchDegreesPerSecond()),
                checkedTuning.emergencyPitchHalfLifeSeconds(),
                Math.toRadians(checkedTuning.maximumEmergencyPitchDegreesPerSecond()));
        if (pitchRotation != 0.0) {
            applyWorldRotation(initialFrame.left(), pitchRotation);
        }

        SixDofHorizonAlignment.Frame bankFrame = SixDofHorizonAlignment.frame(
                unitNormal, forward(), up());
        boolean bankDefined = Math.abs(Math.cos(bankFrame.referencePitchRadians()))
                > 1.0e-10;
        double bankError = bankDefined ? SixDofHorizonAlignment.signedBankErrorRadians(
                forward(), up(), bankFrame.targetUp()) : 0.0;
        double bankRotation = 0.0;
        if (!suppressBank && bankDefined) {
            bankRotation = SixDofHorizonAlignment.boundedStepToZero(bankError,
                    effectiveDelta, checkedTuning.bankHalfLifeSeconds(),
                    Math.toRadians(checkedTuning.maximumAutoBankDegreesPerSecond()));
            if (bankRotation != 0.0) {
                applyWorldRotation(forward(), bankRotation);
            }
        }
        return new HorizonResult(Math.toDegrees(bankFrame.referencePitchRadians()),
                Math.toDegrees(bankError), degreesPerSecond(bankRotation, effectiveDelta),
                degreesPerSecond(pitchRotation, effectiveDelta),
                Math.abs(pitchOverflow - pitchRotation) > 1.0e-12);
    }

    /**
     * Applies a direct local-forward roll in degrees to the authoritative
     * quaternion.
     */
    void applyExplicitRollDegrees(double rollDegrees) {
        if (!initialized || !Double.isFinite(rollDegrees)) {
            return;
        }

        applyLocalDelta(Quaternion.aboutZ(Math.toRadians(rollDegrees)));
    }

    /** Restores the identity quaternion and marks this orientation uninitialized. */
    void clear() {
        orientation = Quaternion.IDENTITY;
        initialized = false;
    }

    boolean isInitialized() {
        return initialized;
    }

    /** Returns an immutable copy of the complete current orientation state. */
    Snapshot current() {
        return snapshot(orientation);
    }

    /** Returns the normalized orientation quaternion in its canonical hemisphere. */
    Components components() {
        return new Components(orientation.w(), orientation.x(), orientation.y(), orientation.z());
    }

    Vec3 forward() {
        return rotate(0.0, 0.0, 1.0);
    }

    Vec3 right() {
        return rotate(-1.0, 0.0, 0.0);
    }

    Vec3 up() {
        return rotate(0.0, 1.0, 0.0);
    }

    private void applyLocalDelta(Quaternion localDelta) {
        orientation = orientation.multiply(localDelta).normalizedCanonical();
    }

    private void applyWorldRotation(Vec3 unitAxis, double angleRadians) {
        orientation = worldAxisRotation(unitAxis, angleRadians).multiply(orientation).normalizedCanonical();
    }

    private static Quaternion worldAxisRotation(Vec3 unitAxis, double angleRadians) {
        return Quaternion.axisAngle(unitAxis.x(), unitAxis.y(), unitAxis.z(), angleRadians);
    }

    private static double pitchOverflow(double referencePitchRadians, double softPitchRadians) {
        if (referencePitchRadians > softPitchRadians) {
            return referencePitchRadians - softPitchRadians;
        }
        if (referencePitchRadians < -softPitchRadians) {
            return referencePitchRadians + softPitchRadians;
        }
        return 0.0;
    }

    private static double degreesPerSecond(double angleRadians, double deltaSeconds) {
        return deltaSeconds == 0.0 ? 0.0 : Math.toDegrees(angleRadians / deltaSeconds);
    }

    private static MouseDelta alignedMouseDelta(int deltaX, int deltaY,
            double sensitivity, boolean invertMouse) {
        double adjustedSensitivity = clampUnit(sensitivity);
        double multiplier = Math.pow(adjustedSensitivity * 0.6 + 0.2, 3.0) * 8.0 * 0.15;
        double yawDegrees = (double) deltaX * multiplier;
        double pitchDegrees = (invertMouse ? (double) deltaY : -(double) deltaY) * multiplier;
        double mouseMagnitudeDegrees = Math.hypot(yawDegrees, pitchDegrees);
        if (!Double.isFinite(mouseMagnitudeDegrees) || mouseMagnitudeDegrees == 0.0) {
            return MouseDelta.NONE;
        }
        if (mouseMagnitudeDegrees > MAX_MOUSE_DEGREES) {
            double capScale = MAX_MOUSE_DEGREES / mouseMagnitudeDegrees;
            yawDegrees *= capScale;
            pitchDegrees *= capScale;
        }
        double yawRadians = Math.toRadians(yawDegrees);
        double pitchRadians = Math.toRadians(pitchDegrees);
        double magnitudeRadians = Math.hypot(yawRadians, pitchRadians);
        return Double.isFinite(magnitudeRadians) ? new MouseDelta(yawRadians, pitchRadians,
                magnitudeRadians) : MouseDelta.NONE;
    }

    private Vec3 rotate(double vectorX, double vectorY, double vectorZ) {
        return rotate(orientation, vectorX, vectorY, vectorZ);
    }

    private Snapshot snapshot(Quaternion quaternion) {
        return new Snapshot(
                new Components(quaternion.w(), quaternion.x(), quaternion.y(), quaternion.z()),
                rotate(quaternion, 0.0, 0.0, 1.0),
                rotate(quaternion, -1.0, 0.0, 0.0),
                rotate(quaternion, 0.0, 1.0, 0.0));
    }

    private static Vec3 rotate(Quaternion orientation, double vectorX, double vectorY, double vectorZ) {
        // q * v * conjugate(q), expanded to avoid allocating a quaternion.
        double quaternionW = orientation.w();
        double quaternionX = orientation.x();
        double quaternionY = orientation.y();
        double quaternionZ = orientation.z();
        double twiceCrossX = 2.0 * (quaternionY * vectorZ - quaternionZ * vectorY);
        double twiceCrossY = 2.0 * (quaternionZ * vectorX - quaternionX * vectorZ);
        double twiceCrossZ = 2.0 * (quaternionX * vectorY - quaternionY * vectorX);
        return new Vec3(
                vectorX + quaternionW * twiceCrossX
                        + (quaternionY * twiceCrossZ - quaternionZ * twiceCrossY),
                vectorY + quaternionW * twiceCrossY
                        + (quaternionZ * twiceCrossX - quaternionX * twiceCrossZ),
                vectorZ + quaternionW * twiceCrossZ
                        + (quaternionX * twiceCrossY - quaternionY * twiceCrossX));
    }

    private static double clampAxis(double axis) {
        return Math.max(-1.0, Math.min(1.0, axis));
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    record Components(double w, double x, double y, double z) {
    }

    record Snapshot(Components components, Vec3 forward, Vec3 right, Vec3 up) {
    }

    record AlignedMouseResult(boolean pitchLimited, double referencePitchDegrees) {
        private static final AlignedMouseResult NONE = new AlignedMouseResult(false, Double.NaN);
    }

    record HorizonResult(double referencePitchDegrees, double bankErrorDegrees,
            double autoBankDegreesPerSecond, double acquireDegreesPerSecond,
            boolean acquiring) {
        static final HorizonResult NONE = new HorizonResult(Double.NaN, Double.NaN,
                0.0, 0.0, false);

        /** The current bounded return rate toward the soft pitch target. */
        double autoPitchDegreesPerSecond() {
            return acquireDegreesPerSecond;
        }
    }

    private record MouseDelta(double yawRadians, double pitchRadians, double magnitudeRadians) {
        private static final MouseDelta NONE = new MouseDelta(0.0, 0.0, 0.0);
    }

    private record Quaternion(double w, double x, double y, double z) {

        private static final Quaternion IDENTITY = new Quaternion(1.0, 0.0, 0.0, 0.0);

        private static Quaternion aboutX(double angle) {
            return axisAngle(1.0, 0.0, 0.0, angle);
        }

        private static Quaternion aboutY(double angle) {
            return axisAngle(0.0, 1.0, 0.0, angle);
        }

        private static Quaternion aboutZ(double angle) {
            return axisAngle(0.0, 0.0, 1.0, angle);
        }

        private static Quaternion axisAngle(double axisX, double axisY, double axisZ, double angle) {
            double halfAngle = angle * 0.5;
            double scale = Math.sin(halfAngle);
            return new Quaternion(
                    Math.cos(halfAngle), axisX * scale, axisY * scale, axisZ * scale);
        }

        private Quaternion multiply(Quaternion other) {
            return new Quaternion(
                    Math.fma(w, other.w(), -x * other.x() - y * other.y() - z * other.z()),
                    Math.fma(w, other.x(), Math.fma(x, other.w(), y * other.z() - z * other.y())),
                    Math.fma(w, other.y(), Math.fma(-x, other.z(), y * other.w() + z * other.x())),
                    Math.fma(w, other.z(), Math.fma(x, other.y(), -y * other.x() + z * other.w())));
        }

        private Quaternion normalizedCanonical() {
            double length = Math.hypot(Math.hypot(w, x), Math.hypot(y, z));
            if (!Double.isFinite(length) || length == 0.0) {
                return new Quaternion(1.0, 0.0, 0.0, 0.0);
            }
            double inverseLength = 1.0 / length;
            Quaternion normalized = new Quaternion(
                    w * inverseLength, x * inverseLength, y * inverseLength, z * inverseLength);
            return isCanonical(normalized) ? normalized
                    : new Quaternion(-normalized.w(), -normalized.x(), -normalized.y(), -normalized.z());
        }

        private static boolean isCanonical(Quaternion quaternion) {
            if (quaternion.w() != 0.0) {
                return quaternion.w() > 0.0;
            }
            if (quaternion.x() != 0.0) {
                return quaternion.x() > 0.0;
            }
            if (quaternion.y() != 0.0) {
                return quaternion.y() > 0.0;
            }
            return quaternion.z() >= 0.0;
        }
    }
}
