package wi.unigrav.api.field;

/**
 * Holder for the global {@link GravityField} singleton used by the mod at
 * runtime. Pure API code should prefer an explicit {@link GravityField}
 * instance; the singleton exists for the Minecraft-facing layer where a
 * process-wide default is convenient.
 */
public final class GravityFields {

    private static final GravityField GLOBAL = new GravityField();

    public static GravityField get() {
        return GLOBAL;
    }

    private GravityFields() {
    }
}
