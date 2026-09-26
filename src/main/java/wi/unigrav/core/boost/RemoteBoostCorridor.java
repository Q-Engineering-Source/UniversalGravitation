package wi.unigrav.core.boost;

/** Pure height-corridor and linear carrier-decay math shared by remote boost owners. */
public final class RemoteBoostCorridor {
    public static final double RESTRICTED_CEILING_MPS = 70.0D;
    public static final double REENTRY_DECELERATION_MPS2 = 840.0D;

    private static final double MIN_RESTRICTED_FEET_Y = 0.0D;
    private static final double MAX_RESTRICTED_FEET_Y = 256.0D;

    private RemoteBoostCorridor() { }

    /** The restricted height band is inclusive and deliberately independent of terrain state. */
    public static boolean isRestricted(double feetY) {
        finite(feetY, "feetY");
        return feetY >= MIN_RESTRICTED_FEET_Y && feetY <= MAX_RESTRICTED_FEET_Y;
    }

    /**
     * Returns the selected speed bounded by the carried ceiling and, when restricted, the 70 m/s
     * corridor ceiling. It never raises a selected or carried value.
     */
    public static double propulsionTarget(double selectedMps, double carriedCeilingMps,
            boolean restricted) {
        speed(selectedMps, "selectedMps");
        speed(carriedCeilingMps, "carriedCeilingMps");
        return Math.min(selectedMps, restricted
                ? Math.min(carriedCeilingMps, RESTRICTED_CEILING_MPS) : carriedCeilingMps);
    }

    /**
     * Integrates {@code max(effectiveFloor, startCeiling - deceleration * t)} for
     * {@code seconds}. If the supplied floor is above the start, the effective floor is the
     * start itself so a decay request cannot increase the carrier or velocity.
     */
    public static Decay decay(double startCeilingMps, double floorMps, double decelerationMps2,
            double seconds) {
        speed(startCeilingMps, "startCeilingMps");
        speed(floorMps, "floorMps");
        finite(decelerationMps2, "decelerationMps2");
        finite(seconds, "seconds");
        if (decelerationMps2 <= 0.0D || seconds < 0.0D) {
            throw new IllegalArgumentException("decelerationMps2 must be positive and seconds non-negative");
        }
        double effectiveFloor = Math.min(floorMps, startCeilingMps);
        double timeToFloor = (startCeilingMps - effectiveFloor) / decelerationMps2;
        if (seconds <= timeToFloor) {
            double end = startCeilingMps - decelerationMps2 * seconds;
            return checkedDecay(end, (startCeilingMps + end) * 0.5D * seconds);
        }
        double fallingDistance = (startCeilingMps + effectiveFloor) * 0.5D * timeToFloor;
        return checkedDecay(effectiveFloor,
                fallingDistance + effectiveFloor * (seconds - timeToFloor));
    }

    public record Decay(double endCeilingMps, double distanceMeters) { }

    private static void speed(double value, String name) {
        finite(value, name);
        if (value < 0.0D || value > 500.0D) {
            throw new IllegalArgumentException(name + " must be in [0, 500] m/s");
        }
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static Decay checkedDecay(double endCeilingMps, double distanceMeters) {
        if (!Double.isFinite(endCeilingMps) || !Double.isFinite(distanceMeters)) {
            throw new IllegalArgumentException("Decay result is not finite");
        }
        return new Decay(endCeilingMps, distanceMeters);
    }
}
