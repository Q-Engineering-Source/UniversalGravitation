package wi.unigrav.core;

import java.util.Objects;

import wi.unigrav.core.player.EnvironmentObservationWork;

import wi.unigrav.api.gravity.GravityContribution;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.world.World;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.Entity;

import wi.unigrav.Reference;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.field.GravityField;
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
            GravityField.Registration registration = AmbientWorldFields.field(entity.world).register(
                    Reference.MOD_ID, "entity/" + entity.getUniqueID(),
                    new EntitySource(entity, entity.world, mu, softening));
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
        return fieldAccelerationInternal(entity, pos, time, null);
    }

    public static Vec3 fieldAcceleration(Entity entity, Vec3 pos, double time,
            EnvironmentObservationWork work) {
        return observeFieldAcceleration(entity, pos, time, work).acceleration();
    }

    /** One actual force evaluation, retaining the producer/current evidence for later callbacks. */
    public static FieldAccelerationObservation observeFieldAcceleration(Entity entity, Vec3 pos, double time,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(entity);
        Objects.requireNonNull(pos, "pos");
        World world = entity.world;
        AmbientWorldFields.Decision decision = AmbientWorldFields.capture(world, work);
        if (decision == null) throw new IllegalStateException("ambient gravity world is unavailable");
        GravityField.Registration self = ACTIVE.get(entity);
        Vec3 total = decision.accelerationAt(pos, time, self == null ? null : self.contribution());
        var observation = new FieldAccelerationObservation(entity, world, self, decision, pos, time, total, work);
        if (!observation.isCurrent()) throw new IllegalStateException("ambient gravity changed during actor sampling");
        return observation;
    }

    /** Historical values remain readable; freshness is established only by isCurrent at consumption. */
    public static final class FieldAccelerationObservation {
        private final Entity entity;
        private final World world;
        private final GravityField.Registration self;
        private final AmbientWorldFields.Decision decision;
        private final Vec3 position;
        private final double time;
        private final Vec3 acceleration;
        private final EnvironmentObservationWork work;

        private FieldAccelerationObservation(Entity entity, World world, GravityField.Registration self,
                AmbientWorldFields.Decision decision, Vec3 position, double time, Vec3 acceleration,
                EnvironmentObservationWork work) {
            this.entity = entity; this.world = world; this.self = self; this.decision = decision;
            this.position = position; this.time = time; this.acceleration = acceleration; this.work = work;
        }

        public Vec3 position() { return position; }
        public double time() { return time; }
        public Vec3 acceleration() { return acceleration; }

        /** Rechecks source identity without evaluating any force producer a second time. */
        public boolean isCurrent() {
            work.requireCurrent(entity);
            work.charge(EnvironmentObservationWork.Unit.COMPARISON);
            if (entity.world != world || entity.isDead || ACTIVE.get(entity) != self) return false;
            boolean current = decision.isCurrent();
            work.requireCurrent(entity);
            work.charge(EnvironmentObservationWork.Unit.COMPARISON);
            return current && entity.world == world && !entity.isDead && ACTIVE.get(entity) == self;
        }
    }

    private static Vec3 fieldAccelerationInternal(Entity entity, Vec3 pos, double time,
            EnvironmentObservationWork work) {
        // Exclude self by identity before evaluation: subtraction invokes a stateful source twice.
        AmbientWorldFields.Decision decision = work == null ? AmbientWorldFields.capture(entity.world)
                : AmbientWorldFields.capture(entity.world, work);
        if (decision == null) throw new IllegalStateException("ambient gravity world is unavailable");
        GravityField.Registration registration = ACTIVE.get(entity);
        Vec3 total = decision.accelerationAt(pos, time,
                registration == null ? null : registration.contribution());
        if (!decision.isCurrent()) throw new IllegalStateException("ambient gravity changed during actor sampling");
        return total;
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinWorldEvent event) {
        migrate(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        migrate(event.player);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        remove(event.getEntityLiving());
    }

    private static void migrate(Entity entity) {
        GravityField.Registration old = ACTIVE.get(entity);
        if (old != null && old.contribution() instanceof EntitySource source && source.world() != entity.world) {
            GravityField.Registration replacement = AmbientWorldFields.field(entity.world).register(
                    Reference.MOD_ID, "entity/" + entity.getUniqueID(),
                    new EntitySource(entity, entity.world, source.mu(), source.softening()));
            ACTIVE.put(entity, replacement);
            old.close();
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        ACTIVE.forEach((entity, registration) -> {
            if (registration.contribution() instanceof EntitySource source && source.world() == event.getWorld())
                remove(entity);
        });
    }

    private record EntityObservation(Entity entity, World world,
                                     double x, double y, double z, float height,
                                     double mu, double softening, boolean dead) implements GravitySource {
        @Override public double gravitationalParameter() { return mu; }
        @Override public Vec3 position(double time) { return new Vec3(x, y + height * 0.5, z); }
        @Override public double softeningLength() { return softening; }
    }

    /** Point-mass source pinned to a living entity's centre of mass. */
    private record EntitySource(Entity entity, World world, double mu, double softening)
            implements GravitySource, AmbientWorldFields.SnapshotContribution {
        @Override public GravitySource snapshot() {
            return new EntityObservation(entity, world, entity.posX, entity.posY, entity.posZ,
                    entity.height, mu, softening, entity.isDead);
        }
        @Override public boolean isCurrent(GravityContribution snapshot) {
            EntityObservation observation = (EntityObservation) snapshot;
            return entity.world == world && entity.posX == observation.x() && entity.posY == observation.y()
                    && entity.posZ == observation.z() && entity.height == observation.height()
                    && !entity.isDead && !observation.dead();
        }
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
