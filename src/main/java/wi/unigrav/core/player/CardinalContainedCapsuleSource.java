package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.Optional;

/** Constructs a virtual source, never movement, path, collision, or accepted-state authority. */
final class CardinalContainedCapsuleSource {
    enum Reason {
        INVALID_SOURCE, DIMENSIONS_CHANGED, TANGENT_UNREPRESENTABLE,
        HALFLINE_DOMAIN_EMPTY, NO_REPRESENTABLE_CONTAINED_SOURCE, NUMERIC_BUDGET
    }
    sealed interface Result permits Prepared, Unavailable { }
    record Prepared(PlayerBodyPlacement placement, CapsulePlacementMath.Pose pose,
            CardinalCapsuleContainment.Contained containment) implements Result { }
    record Unavailable(Reason reason, String diagnostics, Optional<NumericWorkExhaustedException> cause)
            implements Result { }

    private CardinalContainedCapsuleSource() { }

    static Result prepare(PlayerPhysicalSnapshot accepted, PlayerBodyShape.Dimensions liveDimensions,
            StoredDyadic.Work sameWork) {
        Objects.requireNonNull(accepted); Objects.requireNonNull(liveDimensions); Objects.requireNonNull(sameWork);
        try {
            sameWork.charge(1);
            if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
                return unavailable(Reason.INVALID_SOURCE, "source is not accepted Cardinal geometry");
            if (!StoredRealizationLimits.sameRaw(cardinal.dimensions(), liveDimensions))
                return unavailable(Reason.DIMENSIONS_CHANGED, "accepted=" + cardinal.dimensions() + "; live=" + liveDimensions);
            if (!liveDimensions.supportsCapsule())
                return unavailable(Reason.INVALID_SOURCE, "dimensions do not support a capsule");
            var center = accepted.placement().center();
            var attitude = cardinal.frame().attitude();
            var shape = new PlayerBodyShape.FreeCapsule(cardinal.dimensions());
            var nominal = new PlayerBodyPlacement(center, attitude, shape, sameWork);
            var containment = CardinalCapsuleContainment.prepare(accepted, nominal, sameWork);
            if (containment instanceof CardinalCapsuleContainment.Contained contained)
                return prepared(nominal, contained, sameWork);

            var limits = nominal.storedCapsuleGeometry().relation().limits();
            if (limits.halfLength() == 0)
                return unavailable(Reason.NO_REPRESENTABLE_CONTAINED_SOURCE, "sphere has no half-line correction");
            var up = cardinal.frame().up();
            int axis = up.x() != 0 ? 0 : up.y() != 0 ? 1 : 2;
            double[] lower = new double[3], upper = new double[3];
            var radius = StoredDyadic.of(limits.radius(), sameWork);
            var bounds = accepted.placement().bounds();
            for (int component = 0; component < 3; component++) {
                sameWork.charge(1);
                lower[component] = StoredDyadic.of(StoredRealizationLimits.component(bounds.min(), component), sameWork)
                        .add(radius, sameWork).roundUp(sameWork);
                upper[component] = StoredDyadic.of(StoredRealizationLimits.component(bounds.max(), component), sameWork)
                        .subtract(radius, sameWork).roundDown(sameWork);
                if (lower[component] > upper[component])
                    return unavailable(component == axis ? Reason.NO_REPRESENTABLE_CONTAINED_SOURCE
                            : Reason.TANGENT_UNREPRESENTABLE, "empty eroded endpoint interval at axis=" + component);
                if (component != axis && !endpointsInside(center, limits.nominalHalfLine(), component,
                        lower[component], upper[component], sameWork))
                    return unavailable(Reason.TANGENT_UNREPRESENTABLE, "fixed tangent endpoints outside eroded box at axis=" + component);
            }

            double nominalAxis = StoredRealizationLimits.component(limits.nominalHalfLine(), axis);
            var nominalMagnitude = StoredDyadic.of(Math.abs(nominalAxis), sameWork);
            var exactLower = nominalMagnitude.subtract(limits.halfLineBound(axis), sameWork);
            double minimum = exactLower.compare(StoredDyadic.zero(), sameWork) <= 0
                    ? Double.MIN_VALUE : exactLower.roundUp(sameWork);
            double maximum = Math.abs(nominalAxis);
            if (!(minimum > 0 && minimum <= maximum))
                return unavailable(Reason.HALFLINE_DOMAIN_EMPTY, "no positive representable half-line in realization domain");
            double sign = Math.copySign(1, nominalAxis);
            if (!axisInside(center, limits.nominalHalfLine(), axis, sign * minimum, lower[axis], upper[axis], sameWork))
                return unavailable(Reason.NO_REPRESENTABLE_CONTAINED_SOURCE, "minimum domain half-line does not fit");

            long low = Double.doubleToRawLongBits(minimum), high = Double.doubleToRawLongBits(maximum);
            if (!axisInside(center, limits.nominalHalfLine(), axis, sign * maximum, lower[axis], upper[axis], sameWork)) {
                // Positive finite binary64 encodings are ordered. The true rounded C +/- Hs
                // predicate is monotone as |Hs| shrinks; at most 63 bisections cover this domain.
                for (int iteration = 0; high - low > 1 && iteration < 63; iteration++) {
                    sameWork.charge(1);
                    long middle = low + (high - low) / 2;
                    if (axisInside(center, limits.nominalHalfLine(), axis, sign * Double.longBitsToDouble(middle),
                            lower[axis], upper[axis], sameWork)) low = middle;
                    else high = middle;
                }
            } else low = high;
            var halfLine = replace(limits.nominalHalfLine(), axis, sign * Double.longBitsToDouble(low));
            var relation = RealizationRelation.containedCardinal(limits, halfLine, axis, sameWork);
            var stored = StoredCapsuleGeometry.fromRecipe(center, attitude, shape.dimensions(), halfLine, relation);
            var placement = PlayerBodyPlacement.proposedFromStoredGeometry(center, attitude, shape, stored, sameWork);
            var finalContainment = CardinalCapsuleContainment.prepare(accepted, placement, sameWork);
            if (!(finalContainment instanceof CardinalCapsuleContainment.Contained contained))
                return unavailable(Reason.NO_REPRESENTABLE_CONTAINED_SOURCE, "final exact stored containment rejected candidate");
            return prepared(placement, contained, sameWork);
        } catch (NumericWorkExhaustedException exhausted) {
            return new Unavailable(Reason.NUMERIC_BUDGET, "caller numeric work exhausted", Optional.of(exhausted));
        }
    }

    private static Prepared prepared(PlayerBodyPlacement placement, CardinalCapsuleContainment.Contained containment,
            StoredDyadic.Work work) {
        return new Prepared(placement, CapsulePlacementMath.pose(placement, work), containment);
    }

    private static boolean axisInside(Vec3d center, Vec3d nominal, int axis, double half,
            double lower, double upper, StoredDyadic.Work work) {
        return endpointsInside(center, replace(nominal, axis, half), axis, lower, upper, work);
    }

    private static boolean endpointsInside(Vec3d center, Vec3d half, int axis,
            double lower, double upper, StoredDyadic.Work work) {
        work.charge(8);
        double c = StoredRealizationLimits.component(center, axis);
        double h = StoredRealizationLimits.component(half, axis);
        double negative = c - h, positive = c + h;
        return negative >= lower && negative <= upper && positive >= lower && positive <= upper;
    }

    private static Vec3d replace(Vec3d value, int axis, double component) {
        return new Vec3d(axis == 0 ? component : value.x(), axis == 1 ? component : value.y(), axis == 2 ? component : value.z());
    }

    private static Unavailable unavailable(Reason reason, String diagnostics) {
        return new Unavailable(reason, diagnostics, Optional.empty());
    }
}
