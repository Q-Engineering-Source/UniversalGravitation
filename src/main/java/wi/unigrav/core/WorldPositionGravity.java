package wi.unigrav.core;

import java.util.Objects;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorDecision;
import wi.unigrav.core.floor.FloorWorldFields;

/** Authoritative gravity at a finite world position (metres), independent of actor policy.
 * Time is the specified world's total tick times 0.05 seconds. Ordinary floor gravity and
 * ambient contributions use the same position. Magnetic response is deliberately zero.
 */
public final class WorldPositionGravity {
    public sealed interface Result permits Available, Unavailable, Failed {}
    public record Available(Vec3 acceleration, Vec3 position, double timeSeconds, Fence fence)
            implements Result {
        public Available { Objects.requireNonNull(acceleration); Objects.requireNonNull(fence); }
        public boolean isCurrent() { return fence.isCurrent(); }
    }
    public enum Reason { WORLD_UNAVAILABLE, AMBIENT_CHANGED, FLOOR_UNAVAILABLE, DECISION_CHANGED }
    public record Unavailable(Reason reason) implements Result {}
    public record Failed(Throwable cause) implements Result {
        public Failed { Objects.requireNonNull(cause, "cause"); }
    }
    public static final class Fence {
        private final WorldServer world;
        private final long tick;
        private final AmbientWorldFields.Decision ambient;
        private final FloorDecision floor;
        private Fence(WorldServer world, long tick, AmbientWorldFields.Decision ambient, FloorDecision floor) {
            this.world = world; this.tick = tick; this.ambient = ambient; this.floor = floor;
        }
        public long ambientRevision() { return ambient.worldRevision(); }
        public long sharedRevision() { return ambient.sharedRevision(); }
        public long floorConfigurationRevision() { return floor.configurationRevision(); }
        public boolean isCurrent() {
            // The floor check can call world ownership hooks; recheck the ambient owner afterward.
            return world.getTotalWorldTime() == tick && ambient.isCurrent() && floor.isCurrent()
                    && ambient.isCurrent() && world.getTotalWorldTime() == tick;
        }
    }
    private WorldPositionGravity() {}
    public static Result sample(World world, Vec3 position) {
        Objects.requireNonNull(world, "world");
        requireFinite(position, "position");
        try {
            if (!(world instanceof WorldServer serverWorld) || world.isRemote)
                return new Unavailable(Reason.WORLD_UNAVAILABLE);
            long tick = world.getTotalWorldTime();
            AmbientWorldFields.Decision ambient = AmbientWorldFields.capture(world);
            if (ambient == null) return new Unavailable(Reason.WORLD_UNAVAILABLE);
            FloorDecision floor = FloorWorldFields.captureDecision(world).orElse(null);
            if (floor == null) return new Unavailable(Reason.FLOOR_UNAVAILABLE);
            Fence fence = new Fence(serverWorld, tick, ambient, floor);
            if (!fence.isCurrent()) return new Unavailable(Reason.DECISION_CHANGED);
            double time = tick * 0.05;
            Vec3 environment = ambient.accelerationAt(position, time);
            requireFinite(environment, "ambient acceleration");
            if (!ambient.isCurrent()) return new Unavailable(Reason.AMBIENT_CHANGED);
            var floorSample = floor.sample(position, 0.0);
            if (floorSample.isEmpty()) return new Unavailable(Reason.FLOOR_UNAVAILABLE);
            Vec3 acceleration = environment.add(floorSample.get().gravityAccel());
            requireFinite(acceleration, "combined acceleration");
            if (!fence.isCurrent()) return new Unavailable(Reason.DECISION_CHANGED);
            return new Available(acceleration, position, time, fence);
        } catch (RuntimeException failure) {
            return new Failed(failure);
        }
    }
    private static void requireFinite(Vec3 value, String label) {
        if (value == null || !Double.isFinite(value.x()) || !Double.isFinite(value.y())
                || !Double.isFinite(value.z())) throw new IllegalArgumentException(label + " must be finite");
    }
}
