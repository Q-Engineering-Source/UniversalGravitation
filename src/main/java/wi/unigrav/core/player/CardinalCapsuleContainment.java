package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;

/**
 * A geometric start for a virtual Cardinal-to-capsule conversion, never an accepted Free owner.
 * The owner must establish that the actual source box is safe and current, reserve every phase's
 * center travel, and validate the remaining path before its single final installation.
 */
final class CardinalCapsuleContainment {
    private CardinalCapsuleContainment() { }

    sealed interface Result permits Contained, Outside {
        Aabb outwardCapsuleBounds();
        long numericWork();
    }

    record Outside(Aabb outwardCapsuleBounds, long numericWork) implements Result { }

    static final class Contained implements Result {
        private final PlayerPhysicalSnapshot source;
        private final PlayerBodyPlacement virtual;
        private final StoredDyadic.Work work;
        private final Aabb enclosure;
        private final long units;

        private Contained(PlayerPhysicalSnapshot source, PlayerBodyPlacement virtual,
                StoredDyadic.Work work, Aabb enclosure, long units) {
            this.source = source;
            this.virtual = virtual;
            this.work = work;
            this.enclosure = enclosure;
            this.units = units;
        }

        boolean matches(PlayerPhysicalSnapshot exactSource, PlayerBodyPlacement exactVirtual,
                StoredDyadic.Work sameWork) {
            return source == exactSource && virtual == exactVirtual && work == sameWork;
        }
        PlayerPhysicalSnapshot source() { return source; }
        PlayerBodyPlacement virtualPlacement() { return virtual; }
        public Aabb outwardCapsuleBounds() { return enclosure; }
        public long numericWork() { return units; }
    }

    static Result prepare(PlayerPhysicalSnapshot actualSource, PlayerBodyPlacement virtual,
            StoredDyadic.Work sameWork) {
        Objects.requireNonNull(actualSource, "actualSource");
        Objects.requireNonNull(virtual, "virtual");
        Objects.requireNonNull(sameWork, "sameWork");
        long before = sameWork.units();
        sameWork.charge(1);
        if (!(actualSource.placement().shape() instanceof PlayerBodyShape.Cardinal)
                || !(virtual.shape() instanceof PlayerBodyShape.FreeCapsule)) {
            throw new IllegalArgumentException("containment requires an actual Cardinal source and virtual capsule");
        }
        // Revalidate the actual stored recipe against this invocation's numeric ledger.
        CapsulePlacementMath.pose(virtual, sameWork);
        var geometry = virtual.storedCapsuleGeometry();
        Vec3d negative = geometry.negativeEndpoint();
        Vec3d positive = geometry.positiveEndpoint();
        var radius = StoredDyadic.of(geometry.radius(), sameWork);
        double[] low = new double[3];
        double[] high = new double[3];
        double[] a = {negative.x(), negative.y(), negative.z()};
        double[] b = {positive.x(), positive.y(), positive.z()};
        for (int axis = 0; axis < 3; axis++) {
            sameWork.charge(2);
            low[axis] = StoredDyadic.of(Math.min(a[axis], b[axis]), sameWork)
                    .subtract(radius, sameWork).roundDown(sameWork);
            high[axis] = StoredDyadic.of(Math.max(a[axis], b[axis]), sameWork)
                    .add(radius, sameWork).roundUp(sameWork);
        }
        sameWork.charge(15);
        var enclosure = new Aabb(new Vec3d(low[0], low[1], low[2]), new Vec3d(high[0], high[1], high[2]));
        long units = sameWork.units() - before;
        // A convex capsule whose outward support box is in the source convex AABB is contained.
        // This is a construction containment proof, not a second world collision narrow phase.
        var box = actualSource.placement().bounds();
        return box.contains(enclosure.min()) && box.contains(enclosure.max())
                ? new Contained(actualSource, virtual, sameWork, enclosure, units)
                : new Outside(enclosure, units);
    }
}
