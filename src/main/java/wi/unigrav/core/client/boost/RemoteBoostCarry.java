package wi.unigrav.core.client.boost;

import wi.unigrav.core.boost.RemoteBoostCorridor;

/**
 * Pure client-side carried-speed envelope. It is sampled once from the motion
 * tick, never from repeated STATE packets, so a packet burst cannot restart
 * the re-entry brake ramp.
 */
final class RemoteBoostCarry {

    private double carrierMps;
    private boolean restricted;

    double sample(double serverCeilingMps, double actualSpeedMps, boolean nowRestricted,
            double seconds) {
        if (!RemoteBoostCap.validRemoteCeiling(serverCeilingMps)
                || !Double.isFinite(actualSpeedMps) || actualSpeedMps < 0.0
                || !Double.isFinite(seconds) || seconds < 0.0) {
            throw new IllegalArgumentException("carrier sample must be finite and valid");
        }
        if (!nowRestricted) {
            restricted = false;
            carrierMps = serverCeilingMps;
            return carrierMps;
        }
        if (!restricted) {
            // Do not trim velocity on entry: this is the initial re-entry
            // carrier. A slow player retains a 70 m/s future envelope, unless
            // the server has already imposed a lower hard carrier.
            double entryFloor = Math.min(RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                    serverCeilingMps);
            carrierMps = Math.max(entryFloor,
                    Math.min(serverCeilingMps, actualSpeedMps));
            restricted = true;
            return carrierMps;
        }
        // Remaining restricted cannot raise the carrier, even if a later
        // STATE advertises a larger server ceiling.
        double locallyDecayed = RemoteBoostCorridor.decay(carrierMps,
                RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                RemoteBoostCorridor.REENTRY_DECELERATION_MPS2, seconds).endCeilingMps();
        // The server carrier may already represent this tick's same 840 m/s²
        // envelope. Applying it before our decay would subtract 42 m/s twice.
        carrierMps = Math.min(serverCeilingMps, locallyDecayed);
        return carrierMps;
    }

    double currentCeilingMps() {
        return carrierMps;
    }

    boolean restricted() {
        return restricted;
    }

    void clear() {
        carrierMps = 0.0;
        restricted = false;
    }
}
