package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;

/** Geometry calculation custody shared by local movement and server installation transactions. */
interface CapsuleMovementContext {
    PlayerPhysicalSnapshot accepted();
    StoredDyadic.Work numeric();
    CapsuleContactQueryBudget queries();
    FreePostprocessingWork postprocessing();
    void requireCurrent();
    void requireOwnerFence();
    void requireReservedPath(double pathUpper);
    boolean fitsReservedPath(double pathUpper);
    double remainingPathGrant(double usedUpper);
    boolean isPreInstallationPhase();
    WorldCollisionCandidates.Capture capture(Aabb window);
}
