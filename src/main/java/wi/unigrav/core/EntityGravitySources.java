package wi.unigrav.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;

import wi.unigrav.Reference;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.math.Vec3;

/**
 * Extreme-mass entities as gravity sources: when a bound entity's mass
 * reaches {@link UnigravConfig#entityGravityThreshold} (set via
 * {@code /unigrav mass} or an explicit {@code bind(entity, mode, physics)}),
 * a softened point-mass {@link GravitySource} tracking the entity's centre
 * of mass is registered into the global field under
 * {@code unigrav:entity/<uuid>}, so nearby entities and items are pulled
 * toward it. Dropping below the threshold, unbinding, or entity death
 * unregisters the source.
 *
 * <p>&#956; uses the real gravitational constant (G = 6.674e-11 SI), so a
 * noticeable pull needs a huge mass: 1e12 kg gives &#956; &#8776; 66.7
 * m³/s² (&#8776; 2.7 m/s² at 5 m — strong enough to watch items orbit).
 *
 * <p>Self-gravity is excluded at the field-evaluation site
 * ({@link GravityEntityBinder} calls {@link #fieldAcceleration(Entity, Vec3, double)}
 * instead of the raw field): a body must not pull on itself, and the
 * softened field at its own (off-centre) evaluation point would otherwise
 * visibly counteract world gravity.
 */
public final class EntityGravitySources {

    /** Newtonian gravitational constant, m³/(kg·s²). */
    public static final double G_SI = 6.674e-11;

    /** entity → live field registration; accessed from server and client threads. */
    private static final Map<Entity, GravityField.Registration> ACTIVE = new ConcurrentHashMap<>();

    private EntityGravitySources() {
    }

    /**
     * Registers/replaces/removes the entity's gravity source according to
     * its current mass. Cheap no-op when nothing changes; called by the
     * binder on every bind and {@code setBodyPhysics}.
     */
    public static void update(Entity entity, BodyPhysics physics) {
        double mass = physics.massKg();
        if (mass >= UnigravConfig.entityGravityThreshold) {
            double mu = G_SI * mass;
            // Softening ~ body size: bounded pull for things overlapping the
            // entity, zero at its centre (see GravitySource.softeningLength).
            double softening = Math.max(entity.width, entity.height) * 0.5;
            GravityField.Registration registration = GravityFields.get().register(
                    Reference.MOD_ID, "entity/" + entity.getUniqueID(),
                    new EntitySource(entity, mu, softening));
            GravityField.Registration previous = ACTIVE.put(entity, registration);
            if (previous != null) {
                previous.close(); // no-op if it is the same id we just replaced
            }
            UnigravMod.LOGGER.info(
                    "[egrav] registered gravity source for {} (mass={} kg, mu={} m^3/s^2,"
                            + " softening={} m), active={}",
                    entity.getName(), String.format("%.4g", mass),
                    String.format("%.4g", mu), String.format("%.2f", softening),
                    ACTIVE.size());
        } else {
            remove(entity);
        }
    }

    /** Unregisters the entity's gravity source, if any. */
    public static void remove(Entity entity) {
        GravityField.Registration registration = ACTIVE.remove(entity);
        if (registration != null) {
            registration.close();
            UnigravMod.LOGGER.info("[egrav] removed gravity source of {} (mass now"
                    + " below threshold or unbound), active={}",
                    entity.getName(), ACTIVE.size());
        }
    }

    /** Number of live entity gravity sources (debug/diagnostic). */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /**
     * Field acceleration at {@code pos} with the entity's own gravity source
     * subtracted — the self-gravity exclusion used by the binder.
     */
    public static Vec3 fieldAcceleration(Entity entity, Vec3 pos, double time) {
        Vec3 total = GravityFields.get().accelerationAt(pos, time);
        GravityField.Registration registration = ACTIVE.get(entity);
        if (registration != null) {
            total = total.sub(registration.contribution().accelerationAt(pos, time));
        }
        return total;
    }

    /** Point-mass source pinned to a living entity's centre of mass. */
    private record EntitySource(Entity entity, double mu, double softening)
            implements GravitySource {
        @Override
        public double gravitationalParameter() {
            return mu;
        }

        @Override
        public Vec3 position(double time) {
            return new Vec3(entity.posX, entity.posY + entity.height * 0.5, entity.posZ);
        }

        @Override
        public double softeningLength() {
            return softening;
        }
    }
}
