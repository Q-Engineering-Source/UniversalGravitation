package wi.unigrav.core.client.camera;

import wi.unigrav.core.boost.BoostGearProfile;

/** Pure client-session gear selection for local roll and hold-to-boost. */
final class SixDofControlGears {

    private static final double[] ROLL_SPEEDS_DEGREES_PER_SECOND = {
            45.0, 90.0, 180.0, 270.0, 360.0
    };
    private static final double ROLL_SECONDS_TO_MAXIMUM = 0.25;

    private int rollGearIndex = 2;
    private int boostGearIndex = BoostGearProfile.defaultGear() - 1;
    private boolean armed;
    private boolean previousRollUp;
    private boolean previousRollDown;
    private boolean previousBoostUp;
    private boolean previousBoostDown;

    Change update(boolean rollUp, boolean rollDown, boolean boostUp, boolean boostDown) {
        if (!armed) {
            armed = true;
            latch(rollUp, rollDown, boostUp, boostDown);
            return Change.NONE;
        }

        boolean rollUpPressed = rollUp && !previousRollUp;
        boolean rollDownPressed = rollDown && !previousRollDown;
        boolean boostUpPressed = boostUp && !previousBoostUp;
        boolean boostDownPressed = boostDown && !previousBoostDown;
        int previousRollGear = rollGearIndex;
        int previousBoostGear = boostGearIndex;
        if (!(rollUp && rollDown) && rollUpPressed != rollDownPressed) {
            rollGearIndex = clampIndex(rollGearIndex + (rollUpPressed ? 1 : -1),
                    ROLL_SPEEDS_DEGREES_PER_SECOND.length);
        }
        if (!(boostUp && boostDown) && boostUpPressed != boostDownPressed) {
            boostGearIndex = clampIndex(boostGearIndex + (boostUpPressed ? 1 : -1),
                    BoostGearProfile.count());
        }
        latch(rollUp, rollDown, boostUp, boostDown);
        return new Change(rollGearIndex != previousRollGear,
                boostGearIndex != previousBoostGear);
    }

    void suspend() {
        armed = false;
        latch(false, false, false, false);
    }

    int rollGear() {
        return rollGearIndex + 1;
    }

    int boostGear() {
        return boostGearIndex + 1;
    }

    int rollGearCount() {
        return ROLL_SPEEDS_DEGREES_PER_SECOND.length;
    }

    int boostGearCount() {
        return BoostGearProfile.count();
    }

    double rollMaximumSpeedDegreesPerSecond() {
        return ROLL_SPEEDS_DEGREES_PER_SECOND[rollGearIndex];
    }

    double rollAccelerationDegreesPerSecondSquared() {
        return rollMaximumSpeedDegreesPerSecond() / ROLL_SECONDS_TO_MAXIMUM;
    }

    double boostSpeedLimitMps() {
        return BoostGearProfile.speedMps(boostGear());
    }

    double boostAccelerationMps2() {
        return BoostGearProfile.accelerationMps2(boostGear());
    }

    private void latch(boolean rollUp, boolean rollDown,
            boolean boostUp, boolean boostDown) {
        previousRollUp = rollUp;
        previousRollDown = rollDown;
        previousBoostUp = boostUp;
        previousBoostDown = boostDown;
    }

    private static int clampIndex(int index, int length) {
        return Math.max(0, Math.min(length - 1, index));
    }

    record Change(boolean rollChanged, boolean boostChanged) {
        private static final Change NONE = new Change(false, false);
    }
}
