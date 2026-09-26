package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.Entity;

/** Scoped component access for native block callbacks; entity storage always stays in world space. */
public final class CardinalBlockMotion {
    private CardinalBlockMotion() { }
    private record Context(Entity actor, CardinalFrame frame) { }
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    public enum Axis { X, Y, Z }
    public static void run(Entity actor, CardinalFrame frame, Runnable callback) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(callback, "callback");
        Context previous = CURRENT.get();
        CURRENT.set(new Context(actor, frame));
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
    private static CardinalFrame frame(Entity actor) {
        Context current = CURRENT.get();
        return current != null && current.actor() == actor ? current.frame() : CardinalFrame.Y_POS;
    }
    public static double read(Entity actor, Axis axis) {
        Vec3d local = frame(actor).toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
        return switch (axis) { case X -> local.x(); case Y -> local.y(); case Z -> local.z(); };
    }
    public static void write(Entity actor, Axis axis, double value) {
        CardinalFrame frame = frame(actor);
        Vec3d local = frame.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
        Vec3d changed = switch (axis) {
            case X -> new Vec3d(value, local.y(), local.z());
            case Y -> new Vec3d(local.x(), value, local.z());
            case Z -> new Vec3d(local.x(), local.y(), value);
        };
        Vec3d world = frame.toWorld(changed);
        actor.motionX = world.x(); actor.motionY = world.y(); actor.motionZ = world.z();
    }
}
