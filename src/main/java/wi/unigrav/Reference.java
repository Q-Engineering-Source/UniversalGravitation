package wi.unigrav;

/**
 * Mod identity constants. Keep in sync with gradle.properties
 * (mod_id / mod_name / mod_version); hardcoded because Blossom does not
 * substitute placeholders into java sources in this build setup.
 */
public final class Reference {

    public static final String MOD_ID = "unigrav";
    public static final String MOD_NAME = "Universal Gravitation";
    public static final String VERSION = "0.1.15";

    private Reference() {
    }
}
