package wi.unigrav.core.client;

import java.util.Objects;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.CardinalFrame;

/** Projects live view onto the installed physical tangent plane, without changing physical up. */
public record ViewRelativeMovementBasis(Vec3 forward, Vec3 right, Vec3 up) {
    private static final double POLAR_EPSILON = 1.0e-10;

    /** The fallback must belong to the same installed reference; it is used only at a view pole. */
    public static ViewRelativeMovementBasis sample(Vec3 physicalUp, Vec3 viewForward, Vec3 poleHeading) {
        Vec3 up = unit(physicalUp, "physicalUp");
        Vec3 view = unit(viewForward, "viewForward");
        Vec3 projected = view.sub(up.scale(view.dot(up)));
        if (projected.norm() <= POLAR_EPSILON) {
            Vec3 fallback = unit(poleHeading, "poleHeading");
            projected = fallback.sub(up.scale(fallback.dot(up)));
        }
        Vec3 forward = unit(projected, "tangent heading");
        return new ViewRelativeMovementBasis(forward, up.cross(forward), up);
    }

    /** Pure next-sample calculation. Only the owner may commit the resulting continuity. */
    public static ViewRelativeMovementBasis continuous(CardinalFrame frame, Vec3 viewForward, Vec3 acceptedHeading,
                                                       CardinalFrame previousFrame, Vec3 previousTangent) {
        Objects.requireNonNull(frame, "frame");
        Vec3 fallback = acceptedHeading;
        if (previousFrame != null && previousTangent != null)
            fallback = transport(previousFrame, frame, previousTangent);
        var up = frame.up();
        return sample(new Vec3(up.x(), up.y(), up.z()), viewForward, fallback);
    }

    /** Minimum up-vector rotation; an antipodal turn uses the old valid tangent as its real axis. */
    public static Vec3 transport(CardinalFrame from, CardinalFrame to, Vec3 tangent) {
        Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to");
        Vec3 value = unit(tangent, "previousTangent");
        var a = from.up(); var b = to.up();
        Vec3 oldUp = new Vec3(a.x(), a.y(), a.z());
        Vec3 nextUp = new Vec3(b.x(), b.y(), b.z());
        double cosine = oldUp.dot(nextUp);
        if (cosine == 1) return value;
        if (cosine == -1) return value; // Rotation by pi about value leaves this tangent unchanged.
        Vec3 axis = oldUp.cross(nextUp);
        return unit(value.scale(cosine).add(axis.cross(value))
                .add(axis.scale(axis.dot(value) / (1 + cosine))), "transported tangent");
    }

    private static Vec3 unit(Vec3 value, String name) {
        Objects.requireNonNull(value, name);
        double length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
        if (!Double.isFinite(length) || length == 0.0)
            throw new IllegalArgumentException(name + " must be finite and non-zero");
        return value.scale(1.0 / length);
    }
}
