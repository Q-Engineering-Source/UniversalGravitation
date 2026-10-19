package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.MathHelper;

/** Pure dry-travel arithmetic. Motion is blocks/tick; acceleration is blocks/second squared. */
public final class CardinalTravelKernel {
    private CardinalTravelKernel() { }

    public record Input(CardinalFrame frame, Vec3d worldTranslation, double keyboardGain,
                        boolean sneak, boolean jump, float yawDegrees) { }

    public record Parameters(boolean grounded, float friction, float aiMoveSpeed,
                             float jumpMovementFactor, boolean flying, float flySpeed,
                             boolean sprinting) { }

    public static int tickJumpCooldown(int ticks, boolean jump) {
        return !jump ? 0 : ticks > 0 ? ticks - 1 : ticks;
    }

    public static boolean shouldJump(int ticks, boolean jump, boolean grounded, boolean flying) {
        return !flying && jump && grounded && ticks == 0;
    }

    public static double flightInput(double motionY, boolean sneak, boolean jump, float flySpeed) {
        if (sneak) motionY -= flySpeed * 3.0F;
        if (jump) motionY += flySpeed * 3.0F;
        return motionY;
    }

    /** Sample jump strength/potion effects outside this kernel; callbacks receive this world motion. */
    public static Vec3d jump(Vec3d motion, CardinalFrame frame, double launch,
                            boolean sprinting, Vec3d worldLook, Vec3d fallbackForward) {
        Vec3d local = frame.toLocal(motion);
        Vec3d result = frame.toWorld(new Vec3d(local.x(), launch, local.z()));
        if (sprinting) {
            Vec3d look = frame.toLocal(worldLook);
            double length = Math.hypot(look.x(), look.z());
            Vec3d forward = length > 1.0e-7
                    ? frame.toWorld(new Vec3d(look.x() / length, 0, look.z() / length)) : fallbackForward;
            result = result.add(forward.multiply((double) .2F));
        }
        return result;
    }

    public static float coefficient(Parameters p) {
        return p.grounded() ? p.aiMoveSpeed() * (0.16277136F / (p.friction() * p.friction() * p.friction()))
                : p.flying() ? p.flySpeed() * (p.sprinting() ? 2 : 1) : p.jumpMovementFactor();
    }

    /** Y+ mirrors patched native base arithmetic; callers retain any entity override boundary. */
    public static Vec3d accelerate(Vec3d motion, Input input, Parameters parameters) {
        return accelerate(motion, input, coefficient(parameters), parameters.flying());
    }

    /** Uses an already sampled coefficient without re-reading actor attributes or capabilities. */
    public static Vec3d accelerate(Vec3d motion, Input input, float coefficient, boolean flying) {
        float right, forward;
        float yaw = input.yawDegrees() * 0.017453292F;
        float sin = MathHelper.sin(yaw), cos = MathHelper.cos(yaw);
        if (input.frame() == CardinalFrame.Y_POS) {
            float x = (float) (input.worldTranslation().x() * input.keyboardGain());
            float z = (float) (input.worldTranslation().z() * input.keyboardGain());
            right = x * cos + z * sin;
            forward = z * cos - x * sin;
        } else {
            Vec3d local = input.frame().toLocal(input.worldTranslation());
            right = (float) (local.x() * input.keyboardGain());
            forward = (float) (local.z() * input.keyboardGain());
        }
        if (input.sneak() && !flying) {
            right = (float) (right * 0.3D);
            forward = (float) (forward * 0.3D);
        }
        right *= 0.98F;
        forward *= 0.98F;
        float lengthSquared = right * right + forward * forward;
        if (lengthSquared < 1.0e-4F) return motion;
        float scale = coefficient / Math.max(1, MathHelper.sqrt(lengthSquared));
        right *= scale;
        forward *= scale;
        Vec3d drive = input.frame() == CardinalFrame.Y_POS
                ? new Vec3d(right * cos - forward * sin, 0, forward * cos + right * sin)
                : input.frame().toWorld(new Vec3d(right, 0, forward));
        return motion.add(drive);
    }

    /**
     * Consumes collision-observed motion once, then applies sampled force and dry drag.
     * verticalMotion is the already sampled physical-up motion, including any native ladder adjustment.
     * The caller retains the native creative-flight vertical restoration after this result.
     */
    public static Vec3d finish(Vec3d observedMotion, CardinalFrame frame, Vec3d worldAcceleration,
                              double secondsPerTick, float friction, double verticalMotion) {
        Vec3d local = frame.toLocal(observedMotion);
        Vec3d force = frame.toLocal(worldAcceleration);
        double scale = secondsPerTick * secondsPerTick;
        double verticalDrag = 0.9800000190734863D;
        return frame.toWorld(new Vec3d(
                local.x() * friction + force.x() * scale * friction,
                verticalMotion * verticalDrag + force.y() * scale * verticalDrag,
                local.z() * friction + force.z() * scale * friction));
    }
}
