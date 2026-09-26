package wi.unigrav.core.collision;

import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;

/** Loader versions alone cannot distinguish incompatible same-version CHB candidates. */
public final class ChbDependency {
    private ChbDependency() {
    }

    /** Fails before UG installs gameplay hooks; this is never an optional integration. */
    public static void requireSupportedContract() {
        final int revision;
        try {
            revision = CapsuleAabbCast3d.contractRevision();
        } catch (LinkageError cause) {
            throw new IllegalStateException("Universal Gravitation requires CHB-Lib capsule contract revision 1; "
                    + "install the matching UG-CHB-CAPSULE-V1 runtime artifact on both sides", cause);
        }
        if (revision != 1) {
            throw new IllegalStateException("Universal Gravitation requires CHB-Lib capsule contract revision 1, "
                    + "but loaded revision " + revision);
        }
    }
}
