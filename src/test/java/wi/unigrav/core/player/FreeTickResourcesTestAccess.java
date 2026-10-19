package wi.unigrav.core.player;

/** Test-source bridge for cross-package assertions over real resource accounting and retirement. */
public final class FreeTickResourcesTestAccess {
    private FreeTickResourcesTestAccess() { }

    public static void chargeNumeric(FreeTickResources resources, long units) {
        resources.numeric().charge(units);
    }

    public static long numericUnits(FreeTickResources resources) {
        return resources.numeric().units();
    }

    public static void claimNativeAttempt(FreeTickResources resources) {
        resources.claimNativeAttempt();
    }
}
