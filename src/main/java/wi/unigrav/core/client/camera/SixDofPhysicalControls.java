package wi.unigrav.core.client.camera;

/** Pure semantic reduction after configurable key bindings are physically sampled. */
final class SixDofPhysicalControls {

    private SixDofPhysicalControls() {
    }

    static State resolve(boolean rollLeftHeld, boolean rollRightHeld,
            boolean rollGearDownHeld, boolean rollGearUpHeld,
            boolean boostGearDownHeld, boolean boostGearUpHeld) {
        double rollAxis = 0.0;
        boolean gearChordHeld = rollGearDownHeld || rollGearUpHeld
                || boostGearDownHeld || boostGearUpHeld;
        if (!gearChordHeld && rollLeftHeld != rollRightHeld) {
            rollAxis = rollLeftHeld ? 1.0 : -1.0;
        }
        return new State(
                rollAxis,
                rollGearUpHeld && !rollGearDownHeld,
                rollGearDownHeld && !rollGearUpHeld,
                boostGearUpHeld && !boostGearDownHeld,
                boostGearDownHeld && !boostGearUpHeld);
    }

    record State(double rollAxis, boolean rollGearUp, boolean rollGearDown,
            boolean boostGearUp, boolean boostGearDown) {
    }
}
