package wi.unigrav.core.client.camera;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.MotionSpeedLimit;

/** Pure camera-local acceleration and speed-limiting policy for zero-G drift. */
final class SixDofDriftMotion {

    private static final double MAX_DELTA_SECONDS = 0.1;

    private SixDofDriftMotion() {
    }

    static Vec3 worldIntent(SixDofCameraOrientation.Snapshot attitude, Intent intent) {
        if (!isValid(attitude, intent, Vec3.ZERO, 0, 0, 0.05)) return Vec3.ZERO;
        return worldIntent(SixDofMovementFrame.fromCamera(attitude), intent);
    }

    static Vec3 worldIntent(SixDofMovementFrame movementFrame, Intent intent) {
        if (!isValid(movementFrame, intent, Vec3.ZERO, 0, 0, 0.05)) return Vec3.ZERO;
        double x = Math.fma(intent.forward(), movementFrame.forward().x(),
                Math.fma(intent.right(), movementFrame.right().x(), intent.up() * movementFrame.up().x()));
        double y = Math.fma(intent.forward(), movementFrame.forward().y(),
                Math.fma(intent.right(), movementFrame.right().y(), intent.up() * movementFrame.up().y()));
        double z = Math.fma(intent.forward(), movementFrame.forward().z(),
                Math.fma(intent.right(), movementFrame.right().z(), intent.up() * movementFrame.up().z()));
        double length = Math.hypot(Math.hypot(x, y), z);
        if (!Double.isFinite(length)) return Vec3.ZERO;
        double scale = length > 1 ? 1 / length : 1;
        return new Vec3(x * scale, y * scale, z * scale);
    }

    /**
     * A net local-space thrust intent where positive axes mean forward, right,
     * and up respectively. Each component is limited to {@code [-1, 1]}.
     */
    record Intent(double forward, double right, double up) {

        Intent {
            forward = sanitizeAxis(forward);
            right = sanitizeAxis(right);
            up = sanitizeAxis(up);
        }
    }

    /**
     * Calculates the finite world-space velocity delta for one drift update.
     *
     * <p>Velocity is measured in m/s, acceleration in m/s², and elapsed time
     * in seconds. A zero speed limit means unlimited speed. At or above a
     * nonzero limit, input may only reduce total speed and stops at the first
     * minimum-speed point instead of reversing into a new acceleration.
     */
    static Vec3 deltaVelocity(SixDofCameraOrientation.Snapshot attitude, Intent intent,
            Vec3 currentVelocityMps, double accelerationMps2, double speedLimitMps,
            double deltaSeconds) {
        if (!isValid(attitude, intent, currentVelocityMps, accelerationMps2, speedLimitMps,
                deltaSeconds)) {
            return Vec3.ZERO;
        }

        return deltaVelocity(SixDofMovementFrame.fromCamera(attitude), intent,
                currentVelocityMps, accelerationMps2, speedLimitMps, deltaSeconds);
    }

    static Vec3 deltaVelocity(SixDofMovementFrame movementFrame, Intent intent,
            Vec3 currentVelocityMps, double accelerationMps2, double speedLimitMps,
            double deltaSeconds) {
        if (!isValid(movementFrame, intent, currentVelocityMps, accelerationMps2, speedLimitMps,
                deltaSeconds)) {
            return Vec3.ZERO;
        }

        double effectiveDelta = Math.min(deltaSeconds, MAX_DELTA_SECONDS);
        double magnitude = accelerationMps2 * effectiveDelta;
        if (!Double.isFinite(magnitude) || magnitude == 0.0) {
            return Vec3.ZERO;
        }
        Vec3 rawDelta = worldIntent(movementFrame, intent).scale(magnitude);
        if (!isFinite(rawDelta) || speedLimitMps == 0.0) {
            return isFinite(rawDelta) ? rawDelta : Vec3.ZERO;
        }
        return limitDeltaVelocity(currentVelocityMps, rawDelta, speedLimitMps);
    }

    static Vec3 limitDeltaVelocity(Vec3 currentVelocity, Vec3 rawDelta, double speedLimit) {
        return MotionSpeedLimit.deltaVelocity(currentVelocity, rawDelta, speedLimit);
    }

    private static boolean isValid(SixDofCameraOrientation.Snapshot attitude, Intent intent,
            Vec3 currentVelocity, double acceleration, double speedLimit, double deltaSeconds) {
        return attitude != null
                && intent != null
                && currentVelocity != null
                && attitude.components() != null
                && isFinite(attitude.components())
                && isFinite(attitude.forward())
                && isFinite(attitude.right())
                && isFinite(attitude.up())
                && isFinite(currentVelocity)
                && Double.isFinite(acceleration)
                && acceleration >= 0.0
                && Double.isFinite(speedLimit)
                && speedLimit >= 0.0
                && Double.isFinite(deltaSeconds)
                && deltaSeconds > 0.0;
    }

    private static boolean isValid(SixDofMovementFrame movementFrame, Intent intent,
            Vec3 currentVelocity, double acceleration, double speedLimit, double deltaSeconds) {
        return movementFrame != null
                && intent != null
                && currentVelocity != null
                && isFinite(movementFrame.forward())
                && isFinite(movementFrame.right())
                && isFinite(movementFrame.up())
                && isFinite(currentVelocity)
                && Double.isFinite(acceleration)
                && acceleration >= 0.0
                && Double.isFinite(speedLimit)
                && speedLimit >= 0.0
                && Double.isFinite(deltaSeconds)
                && deltaSeconds > 0.0;
    }

    private static boolean isFinite(SixDofCameraOrientation.Components components) {
        return Double.isFinite(components.w())
                && Double.isFinite(components.x())
                && Double.isFinite(components.y())
                && Double.isFinite(components.z());
    }

    private static boolean isFinite(Vec3 vector) {
        return vector != null
                && Double.isFinite(vector.x())
                && Double.isFinite(vector.y())
                && Double.isFinite(vector.z());
    }

    private static double sanitizeAxis(double axis) {
        if (!Double.isFinite(axis)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, axis));
    }
}
