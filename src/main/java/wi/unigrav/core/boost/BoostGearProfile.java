package wi.unigrav.core.boost;

/** Shared, immutable boost tuning. Speeds are m/s and accelerations are m/s². */
public final class BoostGearProfile {
    private static final double[] SPEEDS = {11.453746, 16, 24, 32, 40, 80, 160, 320, 490};

    private BoostGearProfile() { }

    public static int count() { return SPEEDS.length; }

    public static int defaultGear() { return 5; }

    public static double maximumSpeedMps() { return SPEEDS[SPEEDS.length - 1]; }

    public static double speedMps(int gear) {
        if (gear < 1 || gear > count()) {
            throw new IllegalArgumentException("Boost gear must be in [1, " + count() + "]");
        }
        return SPEEDS[gear - 1];
    }

    public static double accelerationMps2(int gear) { return speedMps(gear) / 0.5; }
}
