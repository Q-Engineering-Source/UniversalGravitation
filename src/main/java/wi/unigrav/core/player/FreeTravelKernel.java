package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.MathHelper;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostGearProfile;
import wi.unigrav.core.boost.MotionSpeedLimit;
import wi.unigrav.core.boost.RemoteBoostCorridor;

/** Shared movement arithmetic. World sampling, collision and installation belong to the caller. */
public final class FreeTravelKernel {
    private FreeTravelKernel() { }
    public record Propulsion(Vec3 velocity, boolean activeDrive) { }
    public record Flight(Vec3 velocity, double verticalBlocksPerTick) { }

    public static Propulsion propel(Vec3 velocity, PlayerPhysicsProtocol.Controls controls,
            double driftAcceleration, double driftLimit, double ceilingMps, double feetY, double seconds) {
        if (!Double.isFinite(driftAcceleration) || driftAcceleration < 0
                || !Double.isFinite(driftLimit) || driftLimit < 0
                || !Double.isFinite(ceilingMps) || ceilingMps <= 0
                || !Double.isFinite(seconds) || seconds <= 0)
            throw new IllegalArgumentException("Invalid propulsion parameters");
        Vec3d input = controls.translation();
        Vec3 drive = new Vec3(input.x(), input.y(), input.z());
        velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, drive.scale(driftAcceleration * seconds), driftLimit));
        boolean active = driftAcceleration > 0 && drive.normSquared() > 0;
        if (controls.boost()) {
            double target = RemoteBoostCorridor.propulsionTarget(BoostGearProfile.speedMps(controls.boostGear()),
                    ceilingMps, RemoteBoostCorridor.isRestricted(feetY));
            if (target > 0) {
                Vec3d forward = controls.forward();
                Vec3 delta = new Vec3(forward.x(), forward.y(), forward.z())
                        .scale(BoostGearProfile.accelerationMps2(controls.boostGear()) * seconds);
                velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, delta, target));
                active = true;
            }
        }
        return new Propulsion(velocity, active);
    }

    /** Native flight uses world XZ steering and world Y lift, independent of body attitude. */
    public static Flight fly(NativeFlightParameters equipment, Vec3 acceptedVelocity,
            PlayerPhysicsProtocol.Controls controls, boolean onGround, boolean sprinting, float yawDegrees, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) throw new IllegalArgumentException("Invalid flight interval");
        var equipped = equipment.beforeTravel(new Vec3d(acceptedVelocity.x() * seconds,
                acceptedVelocity.y() * seconds, acceptedVelocity.z() * seconds), onGround, controls);
        var motion = new Vec3d(Math.abs(equipped.x()) < .003D ? 0 : equipped.x(),
                Math.abs(equipped.y()) < .003D ? 0 : equipped.y(),
                Math.abs(equipped.z()) < .003D ? 0 : equipped.z());
        double vertical = CardinalTravelKernel.flightInput(motion.y(), controls.sneak(), controls.jump(), equipment.flySpeed());
        float coefficient = equipment.flySpeed() * (sprinting ? 2 : 1);
        var driven = CardinalTravelKernel.accelerate(new Vec3d(motion.x(), vertical, motion.z()),
                new CardinalTravelKernel.Input(CardinalFrame.Y_POS, controls.translation(), controls.keyboardTravelGain(),
                        controls.sneak(), controls.jump(), yawDegrees), coefficient, true);
        return new Flight(new Vec3(driven.x() / seconds, driven.y() / seconds, driven.z() / seconds), vertical);
    }

    public static Vec3d finishFlight(Vec3d collisionMotion, Vec3d acceleration, double seconds, double verticalBlocksPerTick) {
        var post = CardinalTravelKernel.finish(collisionMotion, CardinalFrame.Y_POS, acceleration,
                seconds, .91F, collisionMotion.y());
        return new Vec3d(post.x(), verticalBlocksPerTick * .6D, post.z());
    }
    public static Vec3 glide(Vec3 velocity, Vec3 acceleration, net.minecraft.util.math.Vec3d look, float pitchDegrees, double dt) {
        double x = velocity.x() * dt, y = velocity.y() * dt, z = velocity.z() * dt;
        float pitch = pitchDegrees * (float) (Math.PI / 180.0D);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double horizontal = Math.sqrt(x * x + z * z);
        float lift = MathHelper.cos(pitch);
        lift = (float) (lift * (lift * Math.min(1.0D, look.length() / 0.4D)));
        x += acceleration.x() * dt * dt;
        y += acceleration.y() * dt * dt + lift * 0.06D;
        z += acceleration.z() * dt * dt;
        if (y < 0 && lookHorizontal > 0) {
            double dive = y * -0.1D * lift;
            y += dive;
            x += look.x * dive / lookHorizontal;
            z += look.z * dive / lookHorizontal;
        }
        if (pitch < 0 && lookHorizontal > 0) {
            double climb = horizontal * -MathHelper.sin(pitch) * 0.04D;
            y += climb * 3.2D;
            x -= look.x * climb / lookHorizontal;
            z -= look.z * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (look.x / lookHorizontal * horizontal - x) * 0.1D;
            z += (look.z / lookHorizontal * horizontal - z) * 0.1D;
        }
        return new Vec3(x * (double) 0.99F / dt, y * (double) 0.98F / dt, z * (double) 0.99F / dt);
    }

}
