package wi.unigrav.core.client.boost;

import wi.unigrav.api.math.Vec3;

/** Pure finite vector clamp for received full-precision remote velocity in m/s. */
final class RemoteBoostVelocity {

    private RemoteBoostVelocity() {
    }

    /**
     * Returns a norm-clamped candidate, or {@code null} for invalid input.
     * A non-positive global limit explicitly means disabled; the negotiated
     * remote ceiling remains finite and mandatory.
     */
    static Vec3 clamp(Vec3 candidateMps, double remoteCeilingMps, double globalLimitMps) {
        if (!finite(candidateMps) || !Double.isFinite(remoteCeilingMps)
                || remoteCeilingMps <= 0.0 || !Double.isFinite(globalLimitMps)) {
            return null;
        }
        double ceiling = globalLimitMps > 0.0
                ? Math.min(remoteCeilingMps, globalLimitMps) : remoteCeilingMps;
        if (!Double.isFinite(ceiling) || ceiling <= 0.0) {
            return null;
        }
        double norm = candidateMps.norm();
        if (!Double.isFinite(norm)) {
            return null;
        }
        return norm > ceiling ? candidateMps.scale(ceiling / norm) : candidateMps;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x())
                && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
