package wi.unigrav.core.floor;

/**
 * The distance profile for one floor-field emitter.
 *
 * <p>Strength is an acceleration in m/s². It remains constant from the
 * emitting face through the inclusive plateau distance, then decreases
 * linearly to zero at the exclusive emitter range.
 */
public record FloorFieldProfile(double strength, double plateau, double range) {

    public FloorFieldProfile {
        requireFinite(strength, "strength");
        requireFinite(plateau, "plateau");
        requireFinite(range, "range");
        if (strength < 0.0) {
            throw new IllegalArgumentException("strength must be nonnegative");
        }
        if (plateau < 0.0 || plateau >= range) {
            throw new IllegalArgumentException("require 0 <= plateau < range");
        }
    }

    /**
     * Returns the acceleration magnitude at an outward distance in metres.
     *
     * <p>The plateau endpoint is included. The range endpoint and every
     * greater distance have zero strength.
     */
    public double strengthAt(double outwardDistance) {
        requireFinite(outwardDistance, "outwardDistance");
        if (outwardDistance < 0.0) {
            throw new IllegalArgumentException("outwardDistance must be nonnegative");
        }
        if (outwardDistance <= plateau) {
            return strength;
        }
        if (outwardDistance >= range) {
            return 0.0;
        }
        return strength * ((range - outwardDistance) / (range - plateau));
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
