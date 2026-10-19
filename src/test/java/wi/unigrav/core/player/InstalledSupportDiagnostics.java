package wi.unigrav.core.player;

/** Reads only retained geometry/query evidence; no world getter, query, Work or current callback. */
final class InstalledSupportDiagnostics {
    private InstalledSupportDiagnostics() { }
    static String describe(InstalledFreeEndpoint endpoint, CapsuleSupportObservation.Complete complete,
            WorldCollisionCandidates.Snapshot capture) {
        var placement = endpoint.pose().placement();
        var geometry = placement.storedCapsuleGeometry();
        var foot = complete.foot();
        return "installedSupportDiagnostics{pose=" + placement
                + ", center=" + placement.center() + ", up=" + placement.bodyUp()
                + ", negative=" + geometry.negativeEndpoint() + ", positive=" + geometry.positiveEndpoint()
                + ", radius=" + geometry.radius() + ", bounds=" + placement.bounds()
                + ", expectedFixtureFloorTop=69.0, footSphere=" + foot.footSphere()
                + ", reach=" + foot.reach() + ", exactWindow=" + foot.window()
                + ", nativeOrdinals=" + capture.boxes()
                + ", fullBodyActualQueries=" + foot.fullObservation().queries()
                + ", footActualQueries(classification/contact/witness/direction/boundaries)=" + foot.queries()
                + ", slopeEvaluations=" + complete.evaluations()
                + ", supports=" + complete.supports() + "}";
    }
}
