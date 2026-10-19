package wi.unigrav.core.player;

import wi.unigrav.core.UnigravConfig;

/** Immutable per-sample policy inputs for player field eligibility. */
final class PlayerPhysicsPolicy {
    record ConfigSnapshot(boolean personalGSuppressesFloorGravity,
                          boolean personalGSuppressesFloorReference,
                          double waterSinkBias) { }

    record FloorEligibility(boolean force, boolean reference) { }

    private PlayerPhysicsPolicy() { }

    static ConfigSnapshot capture() {
        return new ConfigSnapshot(UnigravConfig.personalGSuppressesFloorGravity,
                UnigravConfig.personalGSuppressesFloorReference, UnigravConfig.waterSinkBias);
    }

    static FloorEligibility floorEligibility(ConfigSnapshot policy, boolean personalZeroG,
                                             boolean physicsActorEligible) {
        if (!physicsActorEligible) return new FloorEligibility(false, false);
        return new FloorEligibility(!personalZeroG || !policy.personalGSuppressesFloorGravity(),
                !personalZeroG || !policy.personalGSuppressesFloorReference());
    }
}
