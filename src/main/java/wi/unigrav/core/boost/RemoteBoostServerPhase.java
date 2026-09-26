package wi.unigrav.core.boost;

/** Pure authority phase decisions kept separate from the wire ACTIVE mode. */
final class RemoteBoostServerPhase {
    enum Value { ARMING, ACTIVE, RESYNC }

    private RemoteBoostServerPhase() {
    }

    static boolean maySeed(Value phase, boolean hasCurrentFrame, boolean positionBearing) {
        return phase == Value.ARMING && hasCurrentFrame && positionBearing;
    }

    static Value afterMatchingNativeConfirm(Value phase, boolean matching, boolean hasBudget) {
        if (phase != Value.RESYNC || !matching) return phase;
        return hasBudget ? Value.ACTIVE : Value.ARMING;
    }

}
