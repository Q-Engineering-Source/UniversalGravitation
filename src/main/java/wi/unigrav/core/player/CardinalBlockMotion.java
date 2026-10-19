package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import net.minecraft.entity.Entity;

/** Scoped component access for native block callbacks; entity storage always stays in world space. */
public final class CardinalBlockMotion {
    private CardinalBlockMotion() { }
    private record Context(Entity actor, CardinalFrame frame, Rotation3d attitude) {
        Vec3d toLocal(Vec3d world) { return frame != null ? frame.toLocal(world) : attitude.inverseRotate(world); }
        Vec3d toWorld(Vec3d local) { return frame != null ? frame.toWorld(local) : attitude.rotate(local); }
    }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    public enum Axis { X, Y, Z }
    public static void run(Entity actor, CardinalFrame frame, Runnable callback) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(callback, "callback");
        run(new Context(actor, frame, null), callback);
    }
    /** The installed physical attitude maps callback components; this does not grant landing authority. */
    public static void run(Entity actor, Rotation3d attitude, Runnable callback) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(attitude, "attitude");
        Objects.requireNonNull(callback, "callback");
        run(new Context(actor, null, attitude), callback);
    }
    private static void run(Context context, Runnable callback) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try {
            callback.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
    public static boolean active(Entity actor) {
        Context current = CURRENT.get();
        return current != null && current.actor() == actor;
    }
    private static Context context(Entity actor) {
        Context current = CURRENT.get();
        return current != null && current.actor() == actor ? current : new Context(actor, CardinalFrame.Y_POS, null);
    }
    public static double read(Entity actor, Axis axis) {
        Vec3d local = context(actor).toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
        return switch (axis) { case X -> local.x(); case Y -> local.y(); case Z -> local.z(); };
    }
    public static void write(Entity actor, Axis axis, double value) {
        Context context = context(actor);
        Vec3d local = context.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
        Vec3d changed = switch (axis) {
            case X -> new Vec3d(value, local.y(), local.z());
            case Y -> new Vec3d(local.x(), value, local.z());
            case Z -> new Vec3d(local.x(), local.y(), value);
        };
        Vec3d world = context.toWorld(changed);
        actor.motionX = world.x(); actor.motionY = world.y(); actor.motionZ = world.z();
    }
}
