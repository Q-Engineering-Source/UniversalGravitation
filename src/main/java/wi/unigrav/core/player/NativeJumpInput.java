package wi.unigrav.core.player;

import net.minecraft.entity.EntityLivingBase;

/** Supplies the consumed input's heading to native jump without rewriting entity rotation or callbacks. */
public final class NativeJumpInput {
    private record Sample(EntityLivingBase actor, float yaw) { }
    private static final ThreadLocal<Sample> CURRENT = new ThreadLocal<>();

    private NativeJumpInput() { }

    public static void run(EntityLivingBase actor, float yaw, Runnable jump) {
        Sample previous = CURRENT.get();
        CURRENT.set(new Sample(actor, yaw));
        try {
            jump.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    public static float yaw(EntityLivingBase actor) {
        Sample sample = CURRENT.get();
        return sample != null && sample.actor() == actor ? sample.yaw() : actor.rotationYaw;
    }
}
