package wi.unigrav.core.client.camera;

import wi.unigrav.api.math.Vec3;

/** Pure boost-direction and delta-velocity policy shared by Euler fallback and FREE Q. */
public final class SixDofBoostMotion {

    private static final double MAX_DELTA_SECONDS = 0.1;

    private SixDofBoostMotion() {
    }

    /**
     * Returns this tick's boost delta-v in m/s. A finite, non-zero FREE camera
     * direction owns the boost; otherwise the vanilla Euler look is used.
     */
    public static Vec3 deltaVelocity(Vec3 authoritativeDirection, Vec3 fallbackDirection,
            Vec3 currentVelocityMps, double accelerationMps2,
            double speedLimitMps, double deltaSeconds) {
        if (currentVelocityMps == null
                || !isFinite(currentVelocityMps)
                || !Double.isFinite(accelerationMps2)
                || !Double.isFinite(speedLimitMps)
                || !Double.isFinite(deltaSeconds)
                || accelerationMps2 <= 0.0
                || speedLimitMps <= 0.0
                || deltaSeconds <= 0.0) {
            return Vec3.ZERO;
        }

        Vec3 direction = normalizedOrNull(authoritativeDirection);
        if (direction == null) {
            direction = normalizedOrNull(fallbackDirection);
        }
        if (direction == null) {
            return Vec3.ZERO;
        }
        double effectiveDelta = Math.min(deltaSeconds, MAX_DELTA_SECONDS);
        Vec3 rawDelta = direction.scale(accelerationMps2 * effectiveDelta);
        return SixDofDriftMotion.limitDeltaVelocity(
                currentVelocityMps, rawDelta, speedLimitMps);
    }

    private static Vec3 normalizedOrNull(Vec3 value) {
        if (value == null || !isFinite(value)) {
            return null;
        }
        double magnitude = value.norm();
        return Double.isFinite(magnitude) && magnitude > 0.0
                ? value.scale(1.0 / magnitude) : null;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x())
                && Double.isFinite(value.y())
                && Double.isFinite(value.z());
    }
}
