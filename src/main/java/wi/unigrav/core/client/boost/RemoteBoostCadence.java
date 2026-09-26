package wi.unigrav.core.client.boost;

/** Pure once-per-client-world-tick fallback cadence for owned position samples. */
final class RemoteBoostCadence {

    private RemoteBoostCadence() {
    }

    static boolean needsFallback(Marker lastEmission, Marker current) {
        return current != null && !current.equals(lastEmission);
    }

    record Marker(int entityId, int ticksExisted, long epoch) {
    }
}
