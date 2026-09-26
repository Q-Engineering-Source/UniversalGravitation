package wi.unigrav.api.gravity;

import wi.unigrav.api.math.Vec3;

/**
 * Any contribution to the acceleration field: the common superposition unit
 * registered in a {@code GravityField}. Unlike a {@link GravitySource} (a
 * massive body with position and gravitational parameter), a contribution
 * needs no physical embodiment — it is simply "an acceleration this system
 * adds at a given position and time".
 *
 * <p>Units: positions in m, times in s, acceleration in m/s².
 */
public interface GravityContribution {

    /**
     * Acceleration added to a test particle at inertial position
     * {@code pos} (m) and time {@code time} (s), in m/s².
     */
    Vec3 accelerationAt(Vec3 pos, double time);
}
