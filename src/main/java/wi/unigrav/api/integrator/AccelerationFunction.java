package wi.unigrav.api.integrator;

import wi.unigrav.api.math.Vec3;

/**
 * Acceleration of a test particle as a function of position and time.
 *
 * <p>Units: position in m, time in s, returned acceleration in m/s².
 */
@FunctionalInterface
public interface AccelerationFunction {

    Vec3 at(Vec3 position, double time);
}
