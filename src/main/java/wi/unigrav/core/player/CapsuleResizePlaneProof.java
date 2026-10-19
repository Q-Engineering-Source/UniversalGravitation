package wi.unigrav.core.player;

import java.util.Objects;

/** A sufficient axis halfspace proof for the recipe/hull, never overlap or owner authority. */
final class CapsuleResizePlaneProof {
    private CapsuleResizePlaneProof() { }
    enum Reason { INTERVAL_LOWER_NEGATIVE, AXIS_THRESHOLD_NOT_CERTIFIED }
    sealed interface Result permits Certificate, Uncertain {
        StoredDyadic marginLower();
        long numericWorkDelta();
    }
    record Uncertain(Reason reason, StoredDyadic marginLower, long numericWorkDelta) implements Result { }
    static final class Certificate implements Result {
        private final CapsuleResizeEnclosure.Path path;
        private final int axis, sign;
        private final double plane, from, to;
        private final Object identity;
        private final CapsuleResizeEnclosure.Path.AxisMargin observation;
        private final long delta;
        private Certificate(CapsuleResizeEnclosure.Path path, int axis, int sign, double plane,
                double from, double to, StoredDyadic.Work work,
                CapsuleResizeEnclosure.Path.AxisMargin observation, long delta) {
            this.path = path;
            this.axis = axis;
            this.sign = sign;
            this.plane = plane;
            this.from = from;
            this.to = to;
            identity = work.identity();
            this.observation = observation;
            this.delta = delta;
        }
        boolean matches(CapsuleResizeEnclosure.Path exactPath, int exactAxis, int exactSign,
                double exactPlane, double exactFrom, double exactTo, StoredDyadic.Work sameWork) {
            return path == exactPath && axis == exactAxis && sign == exactSign
                    && Double.doubleToRawLongBits(plane) == Double.doubleToRawLongBits(exactPlane)
                    && Double.doubleToRawLongBits(from) == Double.doubleToRawLongBits(exactFrom)
                    && Double.doubleToRawLongBits(to) == Double.doubleToRawLongBits(exactTo)
                    && sameWork != null && identity == sameWork.identity() && observation.uses(sameWork);
        }
        public StoredDyadic marginLower() { return observation.lower(); }
        public long numericWorkDelta() { return delta; }
        CapsuleResizeEnclosure.Path.AxisMargin.Method method() { return observation.method(); }
        boolean exactAxis() { return observation.exactAxis(); }
        boolean includesActualStart() { return observation.actualStart(); }
        boolean includesActualTarget() { return observation.actualTarget(); }
    }
    static Result certify(CapsuleResizeEnclosure.Path path, int axis, int outwardSign, double supportPlane,
            double from, double to, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sameWork, "sameWork");
        long before = sameWork.units();
        sameWork.charge(1);
        var margin = path.axisMarginInterval(axis, outwardSign, supportPlane, from, to, sameWork);
        long delta = sameWork.units() - before;
        if (margin.lower().signum() >= 0) {
            return new Certificate(path, axis, outwardSign, supportPlane, from, to, sameWork, margin, delta);
        }
        // A negative lower enclosure is uncertainty, not a negative actual collision witness.
        return new Uncertain(margin.exactAxis() ? Reason.AXIS_THRESHOLD_NOT_CERTIFIED
                : Reason.INTERVAL_LOWER_NEGATIVE, margin.lower(), delta);
    }
}
