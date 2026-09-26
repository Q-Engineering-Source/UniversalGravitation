package wi.unigrav.core.client.camera;

/**
 * Stateful inertial input for direct local-forward camera roll.
 *
 * <p>The state is angular velocity, not a roll angle and not a decomposition
 * of the camera quaternion. Z/X supplies angular acceleration; releasing the
 * input retains a damped velocity, while the opposite input brakes through
 * zero before reversing. Returned angles are integrated by the caller around
 * the camera's current local {@code +Z} axis.
 */
final class SixDofRollDynamics {

    private static final double MAX_DELTA_SECONDS = 0.1;
    private static final double DEFAULT_ACCELERATION_DEGREES_PER_SECOND_SQUARED = 720.0;
    private static final double DEFAULT_MAX_SPEED_DEGREES_PER_SECOND = 180.0;
    private static final double DAMPING_PER_SECOND = 3.0;
    private static final double STOP_EPSILON_DEGREES_PER_SECOND = 0.01;

    private double angularVelocityDegreesPerSecond;

    /** Advances the inertial roll state and returns this sample's local-Z angle in degrees. */
    double advance(double inputAxis, double deltaSeconds) {
        return advance(inputAxis, deltaSeconds,
                DEFAULT_MAX_SPEED_DEGREES_PER_SECOND,
                DEFAULT_ACCELERATION_DEGREES_PER_SECOND_SQUARED);
    }

    /** Advances with the selected gear's maximum speed and acceleration. */
    double advance(double inputAxis, double deltaSeconds,
            double maximumSpeedDegreesPerSecond,
            double accelerationDegreesPerSecondSquared) {
        if (!Double.isFinite(deltaSeconds)
                || !Double.isFinite(maximumSpeedDegreesPerSecond)
                || !Double.isFinite(accelerationDegreesPerSecondSquared)
                || deltaSeconds <= 0.0
                || maximumSpeedDegreesPerSecond <= 0.0
                || accelerationDegreesPerSecondSquared <= 0.0) {
            return 0.0;
        }

        double effectiveDelta = Math.min(deltaSeconds, MAX_DELTA_SECONDS);
        double effectiveInput = Double.isFinite(inputAxis) ? clampAxis(inputAxis) : 0.0;
        double previousVelocity = angularVelocityDegreesPerSecond;
        double rotationDegrees;
        if (effectiveInput == 0.0) {
            double retention = Math.exp(-DAMPING_PER_SECOND * effectiveDelta);
            angularVelocityDegreesPerSecond = previousVelocity * retention;
            rotationDegrees = previousVelocity * (1.0 - retention) / DAMPING_PER_SECOND;
            if (Math.abs(angularVelocityDegreesPerSecond)
                    < STOP_EPSILON_DEGREES_PER_SECOND) {
                angularVelocityDegreesPerSecond = 0.0;
            }
        } else {
            double acceleration = effectiveInput
                    * accelerationDegreesPerSecondSquared;
            double acceleratedVelocity = previousVelocity + acceleration * effectiveDelta;
            angularVelocityDegreesPerSecond = Math.max(-maximumSpeedDegreesPerSecond,
                    Math.min(maximumSpeedDegreesPerSecond, acceleratedVelocity));
            double accelerationSeconds = Math.max(0.0, Math.min(effectiveDelta,
                    (angularVelocityDegreesPerSecond - previousVelocity) / acceleration));
            rotationDegrees = (previousVelocity + angularVelocityDegreesPerSecond)
                    * 0.5 * accelerationSeconds
                    + angularVelocityDegreesPerSecond * (effectiveDelta - accelerationSeconds);
        }
        return rotationDegrees;
    }

    double angularVelocityDegreesPerSecond() {
        return angularVelocityDegreesPerSecond;
    }

    static boolean isStopped(double angularVelocityDegreesPerSecond) {
        return Double.isFinite(angularVelocityDegreesPerSecond)
                && Math.abs(angularVelocityDegreesPerSecond)
                < STOP_EPSILON_DEGREES_PER_SECOND;
    }

    void clear() {
        angularVelocityDegreesPerSecond = 0.0;
    }

    private static double clampAxis(double inputAxis) {
        return Math.max(-1.0, Math.min(1.0, inputAxis));
    }
}
