package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;

/** Exact support bounds of the actual stored endpoints/radius, on the caller's numeric ledger. */
final class CapsuleOutwardBounds {
    private CapsuleOutwardBounds() { }

    static Aabb of(Capsule capsule, StoredDyadic.Work work) {
        Objects.requireNonNull(capsule); Objects.requireNonNull(work);
        var first = capsule.centerline().start();
        var last = capsule.centerline().end();
        var radius = StoredDyadic.of(capsule.radius(), work);
        double[] low = new double[3], high = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            work.charge(2);
            double a = StoredRealizationLimits.component(first, axis);
            double b = StoredRealizationLimits.component(last, axis);
            low[axis] = StoredDyadic.of(Math.min(a, b), work).subtract(radius, work).roundDown(work);
            high[axis] = StoredDyadic.of(Math.max(a, b), work).add(radius, work).roundUp(work);
        }
        return new Aabb(new Vec3d(low[0], low[1], low[2]), new Vec3d(high[0], high[1], high[2]));
    }

    static boolean strictlySeparated(Aabb actualOutwardBounds, Aabb obstacle, StoredDyadic.Work work) {
        work.charge(6);
        return actualOutwardBounds.max().x() < obstacle.min().x() || obstacle.max().x() < actualOutwardBounds.min().x()
                || actualOutwardBounds.max().y() < obstacle.min().y() || obstacle.max().y() < actualOutwardBounds.min().y()
                || actualOutwardBounds.max().z() < obstacle.min().z() || obstacle.max().z() < actualOutwardBounds.min().z();
    }
}
