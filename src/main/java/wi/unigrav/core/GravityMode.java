package wi.unigrav.core;

/**
 * How a bound entity's vanilla gravity interacts with the gravity field.
 */
public enum GravityMode {
    /**
     * The field acceleration fully replaces vanilla gravity: while bound,
     * the entity's {@code noGravity} flag is set (and restored on unbind).
     * In 1.12.2 every gravity-applying entity class
     * ({@code Entity} base pull, {@code EntityItem}'s 0.04,
     * {@code EntityThrowable}'s gravity velocity, {@code EntityLivingBase}'s
     * 0.08/0.02 pulls) guards it with {@code hasNoGravity()}, so suppression
     * is exact. Caveat for living entities: AI, swimming and knockback still
     * move them as usual — only the gravity term is removed.
     */
    REPLACE,
    /** The field acceleration is added on top of vanilla gravity. */
    ADDITIVE
}
