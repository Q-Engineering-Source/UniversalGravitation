package wi.unigrav.core;

import wi.unigrav.api.math.Vec3;

/** Shared force composition and END-phase motion update, without an Entity or World dependency. */
public final class BodyAccelerationStep {
    private static final double WATER_DENSITY_KG_M3 = 1000.0;

    private BodyAccelerationStep() {
    }

    public static Vec3 acceleration(Vec3 gravity, Vec3 magnetism, Vec3 appliedForce, BodyPhysics physics,
                                    boolean inWater, boolean swims, double waterSinkBias) {
        Vec3 result = gravity;
        if (appliedForce != null) result = result.add(appliedForce.scale(1.0 / physics.massKg()));
        if (inWater && physics.volumeM3() > 0.0 && swims) {
            double displacedRatio = WATER_DENSITY_KG_M3 * physics.volumeM3() / physics.massKg();
            result = result.add(gravity.scale(waterSinkBias - displacedRatio));
        }
        // Magnetic attraction is not displaced-fluid gravity and has no buoyancy multiplier.
        return magnetism.equals(Vec3.ZERO) ? result : result.add(magnetism);
    }

    static Vec3 motion(Vec3 initial, Vec3 acceleration, double maximumSpeed,
                       boolean compensateGroundFriction, double frictionRetention) {
        double dx = acceleration.x() * GravityEntityBinder.TICK_SECONDS / GravityEntityBinder.TICKS_PER_SECOND;
        double dy = acceleration.y() * GravityEntityBinder.TICK_SECONDS / GravityEntityBinder.TICKS_PER_SECOND;
        double dz = acceleration.z() * GravityEntityBinder.TICK_SECONDS / GravityEntityBinder.TICKS_PER_SECOND;
        Vec3 limited = limitMotion(new Vec3(initial.x() + dx, initial.y() + dy, initial.z() + dz), maximumSpeed);
        double x = limited.x();
        double y = limited.y();
        double z = limited.z();
        // Preserve the established binder order: limit the updated motion, then
        // compensate only this END-phase horizontal delta for missed vanilla friction.
        if (compensateGroundFriction) {
            x -= dx * (1.0 - frictionRetention);
            z -= dz * (1.0 - frictionRetention);
        }
        return new Vec3(x, y, z);
    }

    static Vec3 limitMotion(Vec3 motion, double maximumSpeed) {
        if (maximumSpeed <= 0.0) return motion;
        double limit = maximumSpeed / GravityEntityBinder.TICKS_PER_SECOND;
        double speed2 = motion.x() * motion.x() + motion.y() * motion.y() + motion.z() * motion.z();
        if (speed2 > limit * limit) {
            double scale = limit / Math.sqrt(speed2);
            return new Vec3(motion.x() * scale, motion.y() * scale, motion.z() * scale);
        }
        return motion;
    }
}
