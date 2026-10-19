package wi.unigrav.core.player;

/** Exact normal-installed phase; historical path currentness must not be resurrected here. */
final class InstalledFreeEndpoint implements CapsuleObservationEndpoint {
    private final FreeOwnerTransaction.PreparedInstallation installation;
    private final FreeOwnerTransaction transaction;
    private final PlayerPhysicalSnapshot journal;
    private final CapsulePlacementMath.Pose pose;

    private InstalledFreeEndpoint(FreeOwnerTransaction.PreparedInstallation installation) {
        this.installation = installation;
        transaction = installation.transaction();
        journal = installation.journal();
        pose = installation.certifiedPose();
        requireCurrent();
    }
    static InstalledFreeEndpoint from(FreeOwnerTransaction.PreparedInstallation installation) {
        return new InstalledFreeEndpoint(installation);
    }
    void consumeMovementStatAttempt() {
        requireCurrent(); installation.consumeMovementStatAttempt();
    }
    public FreeOwnerTransaction transaction() { return transaction; }
    public PlayerPhysicalSnapshot owner() { return journal; }
    public CapsulePlacementMath.Pose pose() { return pose; }
    FreeOwnerTransaction.PreparedInstallation installationIdentity() { return installation; }
    public boolean current() {
        transaction.requireInstalledEndpoint(installation, journal, pose);
        return true;
    }
}
