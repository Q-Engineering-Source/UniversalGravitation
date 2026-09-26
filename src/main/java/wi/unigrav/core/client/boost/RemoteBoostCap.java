package wi.unigrav.core.client.boost;

/** Pure selection of a remote server ceiling and an optional local drift cap. */
final class RemoteBoostCap {

    private static final double MAX_REMOTE_CEILING_MPS = 500.0;

    private RemoteBoostCap() {
    }

    static boolean validRemoteCeiling(double ceilingMps) {
        return Double.isFinite(ceilingMps) && ceilingMps > 0.0
                && ceilingMps <= MAX_REMOTE_CEILING_MPS;
    }

    /** A non-positive local setting is unlimited locally, never on the server. */
    static double driftLimit(double remoteCeilingMps, double localLimitMps) {
        if (!validRemoteCeiling(remoteCeilingMps) || !Double.isFinite(localLimitMps)) {
            return Double.NaN;
        }
        return localLimitMps > 0.0 ? Math.min(remoteCeilingMps, localLimitMps)
                : remoteCeilingMps;
    }
}
