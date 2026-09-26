package wi.unigrav.core.client.boost;

/** Pure packet-shape policy for an already-owned remote movement sample. */
final class RemoteBoostPacketPlan {

    private RemoteBoostPacketPlan() {
    }

    /** Exactly one position-bearing player packet is required for each logical sample. */
    static Action action(boolean positionBearing) {
        return positionBearing ? Action.KEEP : Action.REPLACE_WITH_POSITION;
    }

    enum Action {
        KEEP,
        REPLACE_WITH_POSITION
    }
}
