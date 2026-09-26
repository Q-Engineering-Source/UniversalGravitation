package wi.unigrav.core.boost;

import wi.unigrav.api.math.Vec3;

/** Common-side spherical limiter shared by local and authoritative propulsion. */
public final class MotionSpeedLimit {
    private MotionSpeedLimit() { }

    public static Vec3 deltaVelocity(Vec3 current, Vec3 delta, double limit) {
        if (!BoostProtocol.finite(current) || !BoostProtocol.finite(delta)
                || !Double.isFinite(limit) || limit < 0) return Vec3.ZERO;
        if (limit == 0) return delta;
        double speed = Math.hypot(Math.hypot(current.x(), current.y()), current.z());
        Vec3 next = current.add(delta);
        double candidate = Math.hypot(Math.hypot(next.x(), next.y()), next.z());
        if (!Double.isFinite(speed) || !Double.isFinite(candidate)) return Vec3.ZERO;
        double a = delta.normSquared();
        double dot = current.dot(delta);
        if (speed >= limit) {
            if (!Double.isFinite(dot) || !Double.isFinite(a) || dot >= 0 || a == 0) return Vec3.ZERO;
            return scaled(delta, Math.min(1, -dot / a));
        }
        if (candidate <= limit) return delta;
        double b = 2 * dot;
        double c = current.normSquared() - limit * limit;
        double discriminant = Math.fma(b, b, -4 * a * c);
        if (!Double.isFinite(discriminant) || discriminant < 0 || a == 0) return Vec3.ZERO;
        return scaled(delta, (-b + Math.sqrt(discriminant)) / (2 * a));
    }

    private static Vec3 scaled(Vec3 delta, double fraction) {
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return Vec3.ZERO;
        Vec3 result = delta.scale(fraction);
        return BoostProtocol.finite(result) ? result : Vec3.ZERO;
    }
}
