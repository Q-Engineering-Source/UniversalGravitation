package wi.unigrav.core.client.camera;

import wi.unigrav.api.math.Vec3;

/** Pure activation policy for the optional zero-G camera adapter. */
final class SixDofCameraActivation {

    private SixDofCameraActivation() {
    }

    static boolean shouldActivate(boolean enabled, boolean integratedServer, boolean bound,
                                  boolean alive, boolean sleeping, boolean spectator,
                                  boolean creativeFlying, boolean debugCamera,
                                  boolean personalZeroG, double naturalThreshold,
                                  Vec3 acceleration) {
        return shouldActivate(enabled, integratedServer, false, bound, alive, sleeping,
                spectator, false, creativeFlying, debugCamera, personalZeroG,
                naturalThreshold, acceleration);
    }

    /**
     * Remote acknowledgement is explicit, never inferred from an empty client
     * gravity-field registry. CLOSING keeps only packet-pairing custody; it
     * must release the UG camera and input context as soon as local G is off.
     */
    static boolean shouldActivate(boolean enabled, boolean integratedServer, boolean remoteCustody,
                                  boolean bound, boolean alive, boolean sleeping, boolean spectator,
                                  boolean creativeFlying, boolean debugCamera,
                                  boolean personalZeroG, double naturalThreshold,
                                  Vec3 acceleration) {
        return shouldActivate(enabled, integratedServer, remoteCustody, bound, alive, sleeping,
                spectator, false, creativeFlying, debugCamera, personalZeroG,
                naturalThreshold, acceleration);
    }

    /**
     * Integrated Creative camera ownership is an explicit G-key policy, not a
     * natural-field inference. Non-Creative natural zero-G remains the
     * pre-existing path until a separate equipment contract exists.
     */
    static boolean shouldActivate(boolean enabled, boolean integratedServer, boolean remoteCustody,
                                  boolean bound, boolean alive, boolean sleeping, boolean spectator,
                                  boolean creative, boolean creativeFlying, boolean debugCamera,
                                  boolean personalZeroG, double naturalThreshold,
                                  Vec3 acceleration) {
        if (!enabled || !alive || sleeping || spectator || debugCamera) {
            return false;
        }
        if (!integratedServer) {
            return remoteCustody && personalZeroG;
        }
        if (!bound) {
            return false;
        }
        if (personalZeroG) {
            return true;
        }
        if (creative) {
            return false;
        }
        if (creativeFlying || acceleration == null
                || !Double.isFinite(naturalThreshold) || naturalThreshold < 0.0) {
            return false;
        }
        double normSquared = acceleration.normSquared();
        return Double.isFinite(normSquared)
                && Math.sqrt(normSquared) <= naturalThreshold;
    }
}
