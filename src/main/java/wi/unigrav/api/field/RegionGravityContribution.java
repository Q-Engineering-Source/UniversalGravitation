package wi.unigrav.api.field;

import wi.unigrav.api.gravity.GravityContribution;
import wi.unigrav.api.math.Vec3;

/**
 * A gravity contribution bounded to a geometric region: zero outside the
 * shape, and inside either a fixed directional acceleration or a
 * centripetal one (constant magnitude, pointing at the shape's centre of
 * attraction — the sphere centre, the box centre, or the nearest point on
 * the cylinder axis).
 *
 * <p>Pure math, no Minecraft coupling: shapes are axis-aligned boxes,
 * spheres, and finite cylinders (axis segment + radius, flat caps).
 */
public record RegionGravityContribution(Shape shape, Mode mode)
        implements GravityContribution {

    @Override
    public Vec3 accelerationAt(Vec3 pos, double time) {
        return shape.contains(pos) ? mode.acceleration(shape, pos) : Vec3.ZERO;
    }

    /** A bounded volume in world space. */
    public interface Shape {
        /** Whether the point lies inside the volume. */
        boolean contains(Vec3 p);

        /**
         * Unit vector from p toward the shape's centre of attraction, or
         * {@link Vec3#ZERO} when p sits exactly on it.
         */
        Vec3 towardCenter(Vec3 p);
    }

    /** Axis-aligned box between two corners (component-wise min/max). */
    public record Box(Vec3 min, Vec3 max) implements Shape {

        /** Normalizes the corners so min ≤ max per axis. */
        public static Box between(Vec3 a, Vec3 b) {
            return new Box(
                    new Vec3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()),
                            Math.min(a.z(), b.z())),
                    new Vec3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()),
                            Math.max(a.z(), b.z())));
        }

        @Override
        public boolean contains(Vec3 p) {
            return p.x() >= min.x() && p.x() <= max.x()
                    && p.y() >= min.y() && p.y() <= max.y()
                    && p.z() >= min.z() && p.z() <= max.z();
        }

        @Override
        public Vec3 towardCenter(Vec3 p) {
            return unitToward(center(), p);
        }

        /** The box centre. */
        public Vec3 center() {
            return min.add(max).scale(0.5);
        }
    }

    /** Sphere around a centre point. */
    public record Sphere(Vec3 center, double radius) implements Shape {

        @Override
        public boolean contains(Vec3 p) {
            return p.sub(center).normSquared() <= radius * radius;
        }

        @Override
        public Vec3 towardCenter(Vec3 p) {
            return unitToward(center, p);
        }
    }

    /**
     * Finite cylinder: axis segment A→B with a radius; containment is the
     * distance to the segment (flat-ish caps via endpoint clamping).
     * Centripetal pull aims at the nearest axis point (radial), or at the
     * nearest end cap centre beyond the ends.
     */
    public record Cylinder(Vec3 axisA, Vec3 axisB, double radius) implements Shape {

        @Override
        public boolean contains(Vec3 p) {
            return p.sub(nearestAxisPoint(p)).normSquared() <= radius * radius;
        }

        @Override
        public Vec3 towardCenter(Vec3 p) {
            return unitToward(nearestAxisPoint(p), p);
        }

        /** The point of the axis segment closest to p (ends clamped). */
        public Vec3 nearestAxisPoint(Vec3 p) {
            Vec3 axis = axisB.sub(axisA);
            double lengthSquared = axis.normSquared();
            if (lengthSquared == 0.0) {
                return axisA; // degenerate: a sphere of the same radius
            }
            double t = p.sub(axisA).dot(axis) / lengthSquared;
            return axisA.add(axis.scale(Math.max(0.0, Math.min(1.0, t))));
        }
    }

    /** How the in-region acceleration is computed. */
    public interface Mode {
        Vec3 acceleration(Shape shape, Vec3 p);
    }

    /** Constant acceleration everywhere inside the region (m/s² vector). */
    public record Directional(Vec3 accel) implements Mode {
        @Override
        public Vec3 acceleration(Shape shape, Vec3 p) {
            return accel;
        }
    }

    /**
     * Constant-magnitude pull (m/s²) toward the shape's centre of
     * attraction — toward the axis for cylinders.
     */
    public record Centripetal(double strength) implements Mode {
        @Override
        public Vec3 acceleration(Shape shape, Vec3 p) {
            return shape.towardCenter(p).scale(strength);
        }
    }

    private static Vec3 unitToward(Vec3 target, Vec3 p) {
        Vec3 delta = target.sub(p);
        return delta.normSquared() == 0.0 ? Vec3.ZERO : delta.normalize();
    }
}
