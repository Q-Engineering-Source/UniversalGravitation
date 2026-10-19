package wi.unigrav.core.player;

/** Effective boost authority shared by input, physical integration and prediction admission. */
public final class PlayerBoostEligibility {
    private PlayerBoostEligibility() { }
    public static boolean propulsionAllowed(boolean personalZeroG, boolean creative) {
        return personalZeroG && creative;
    }
    public static boolean requested(boolean boost, boolean personalZeroG, boolean creative) {
        return boost && propulsionAllowed(personalZeroG, creative);
    }
    /** Retain the sampled intent fields; only an ineligible boost bit is normalized away. */
    public static PlayerPhysicsProtocol.Controls normalize(PlayerPhysicsProtocol.Controls controls,
            boolean personalZeroG, boolean creative) {
        boolean effective = requested(controls.boost(), personalZeroG, creative);
        if (effective == controls.boost()) return controls;
        return new PlayerPhysicsProtocol.Controls(controls.translation(), controls.forward(), controls.jump(),
                controls.sneak(), controls.sprint(), effective, controls.boostGear(), controls.keyboardTravelGain(),
                controls.bodyReferenceIntent(), controls.desiredBodyAttitude(), controls.mobility());
    }
}
