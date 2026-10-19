package wi.unigrav.core.player;

/** Internal geometric observation boundary; it grants neither movement nor installation. */
sealed interface CapsuleObservationEndpoint permits CertifiedReferenceEndpoint, InstalledFreeEndpoint {
    FreeOwnerTransaction transaction();
    PlayerPhysicalSnapshot owner();
    CapsulePlacementMath.Pose pose();
    boolean current();
    default void requireCurrent() {
        if (!current()) throw new IllegalStateException("capsule observation endpoint became stale");
    }
}
