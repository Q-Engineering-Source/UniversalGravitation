package wi.unigrav.api.math;

/**
 * Immutable three-dimensional double vector.
 *
 * <p>Unit conventions used throughout the API: positions in metres (in
 * Minecraft terms 1 block = 1 m), velocities in m/s, accelerations in m/s²,
 * times in seconds, angles in radians. This class itself is unit-agnostic;
 * the unit follows from the context in which the vector is used.
 */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public Vec3 add(Vec3 other) {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    public Vec3 sub(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    public Vec3 scale(double factor) {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    public double dot(Vec3 other) {
        return Math.fma(x, other.x, Math.fma(y, other.y, z * other.z));
    }

    public Vec3 cross(Vec3 other) {
        return new Vec3(
                Math.fma(y, other.z, -z * other.y),
                Math.fma(z, other.x, -x * other.z),
                Math.fma(x, other.y, -y * other.x));
    }

    public double normSquared() {
        return Math.fma(x, x, Math.fma(y, y, z * z));
    }

    public double norm() {
        return Math.sqrt(normSquared());
    }

    /**
     * Returns the unit vector in the same direction. The result is NaN for
     * the zero vector.
     */
    public Vec3 normalize() {
        return scale(1.0 / norm());
    }

    /**
     * Linear interpolation between this vector ({@code t = 0}) and
     * {@code other} ({@code t = 1}).
     */
    public Vec3 lerp(Vec3 other, double t) {
        return new Vec3(
                Math.fma(other.x - x, t, x),
                Math.fma(other.y - y, t, y),
                Math.fma(other.z - z, t, z));
    }
}
