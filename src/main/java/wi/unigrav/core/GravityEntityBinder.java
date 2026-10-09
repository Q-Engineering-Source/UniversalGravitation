package wi.unigrav.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.floor.FloorFieldIndex;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerPhysicsServer;

/**
 * Entity binding layer: applies the global {@link GravityFields} gravity
 * field to registered Minecraft entities.
 *
 * <p>Two registration forms, both returning an idempotent
 * {@link Registration} handle:
 * <ul>
 *   <li>{@link #bind(Entity, GravityMode)} — bind one specific entity;
 *   <li>{@link #bindPredicate(Predicate, GravityMode)} — automatically bind
 *       every entity matching the predicate (checked on
 *       {@link EntityJoinWorldEvent} and swept over already-loaded entities
 *       at registration time).
 * </ul>
 *
 * <p>Each server tick (END phase) every bound, alive, chunk-loaded
 * server-side entity gets &#916;v = a·dt added to its motion (dt =
 * {@value #TICK_SECONDS} s), evaluated at the entity's current position,
 * and {@code velocityChanged} is set. Position integration (and collision)
 * is left to vanilla {@code Entity.move}. Dead entities are unbound
 * automatically; entities in unloaded chunks are skipped. Client-world
 * entities are skipped here; the client-side ticker
 * ({@code wi.unigrav.core.client.ClientGravityTicker}) applies the same
 * &#916;v to the local player, whose movement is client-authoritative.
 * Bindings may carry a per-tick activity filter (e.g. suppressing the
 * &#916;v for flying players). Note the &#916;v lands one tick after the
 * entity's own update — a half-step offset that is harmless for long-term
 * integration.
 *
 * <p><b>Thread safety.</b> In single player the server tick (server thread)
 * and the client tick (client thread) run concurrently and both touch the
 * binding state. All structural access to {@code bindings} is guarded by
 * {@code synchronized (bindings)}; the server tick iterates a snapshot copy
 * taken under the lock and defers dead-entity removal until after the loop,
 * so a client-side {@link #sweepEntity} binding the local player mid-tick
 * can never invalidate the iteration (this was a live
 * ConcurrentModificationException crash). {@code predicates} is a
 * {@link CopyOnWriteArrayList} (rare writes, frequent concurrent reads).
 *
 * <p>Dimension note: the field is currently dimension-agnostic — the
 * entity's in-world coordinates are used as field coordinates regardless of
 * dimension. Downstream celestial mods that need per-dimension fields
 * should define their sources accordingly (a future per-dimension field
 * registry is out of scope for now).
 */
public final class GravityEntityBinder {

    /** Duration of one server tick, in seconds. */
    public static final double TICK_SECONDS = 0.05;

    /**
     * Ticks per second. Entity {@code motion} is in blocks/tick, NOT m/s:
     * 1 block/tick = 20 m/s. Field accelerations (m/s²) must be scaled by
     * {@code TICK_SECONDS / TICKS_PER_SECOND} before being added to motion —
     * forgetting this makes gravity 20× too strong (observed live: players
     * glued to the ground, terminal fall velocity in the hundreds of m/s).
     */
    public static final double TICKS_PER_SECOND = 20.0;

    /**
     * Vanilla player/living-entity gravity in m/s² (0.08 blocks/tick²) —
     * the definition of 1G. Lives here rather than in {@link UnigravConfig}
     * because Forge's {@code @Config} sync chokes on static finals.
     */
    public static final double VANILLA_PLAYER_GRAVITY = 32.0;

    /** Handle to a registration; {@link #close()} unbinds, idempotently. */
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** Stable identity retained by estimates/physics updates, replaced by each new bind. */
    private static final class BindingLineage { }

    private static final class Binding {
        final GravityMode mode;
        final BindingLineage lineage;
        boolean restoreNoGravity;
        /** Per-tick activity filter, or null for "always active". */
        final Predicate<Entity> tickFilter;
        /** Mass/volume used for non-gravitational forces and buoyancy. */
        final BodyPhysics physics;
        /**
         * True when {@link #physics} came from {@link BodyPhysics#estimate}
         * (auto/formula) rather than an explicit bind or setBodyPhysics:
         * estimated physics is periodically refreshed so state-dependent
         * estimates (sheared sheep, grown slime) track the entity.
         */
        final boolean estimated;
        /** Predicate registration that created this binding, or null for explicit binds. */
        PredicateRegistration owner;
        /** Incremented under {@code synchronized (bindings)} for each real persistent-force change. */
        long forceRevision;

        Binding(GravityMode mode, boolean restoreNoGravity, Predicate<Entity> tickFilter,
                BodyPhysics physics, boolean estimated) {
            this(mode, restoreNoGravity, tickFilter, physics, estimated, new BindingLineage());
        }

        Binding(GravityMode mode, boolean restoreNoGravity, Predicate<Entity> tickFilter,
                BodyPhysics physics, boolean estimated, BindingLineage lineage) {
            this.mode = mode;
            this.lineage = lineage;
            this.restoreNoGravity = restoreNoGravity;
            this.tickFilter = tickFilter;
            this.physics = physics;
            this.estimated = estimated;
        }
    }

    private record RegistrationBinding(Entity entity, BindingLineage lineage) { }

    private final class PredicateRegistration implements Registration {
        final Predicate<Entity> predicate;
        final GravityMode mode;
        final Predicate<Entity> tickFilter;
        /** Guarded by {@code synchronized (bindings)}. */
        final List<Entity> autoBound = new ArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        PredicateRegistration(Predicate<Entity> predicate, GravityMode mode,
                              Predicate<Entity> tickFilter) {
            this.predicate = predicate;
            this.mode = mode;
            this.tickFilter = tickFilter;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                predicates.remove(this);
                List<RegistrationBinding> toUnbind = new ArrayList<>();
                synchronized (bindings) {
                    for (Entity entity : autoBound) {
                        Binding binding = bindings.get(entity);
                        if (binding != null && binding.owner == this) {
                            toUnbind.add(new RegistrationBinding(entity, binding.lineage));
                        }
                    }
                    autoBound.clear();
                }
                for (RegistrationBinding entry : toUnbind) {
                    unbindLineage(entry.entity(), entry.lineage(), this);
                }
            }
        }
    }

    /** Guarded by {@code synchronized (bindings)}; never iterated without a snapshot. */
    private final Map<Entity, Binding> bindings = new IdentityHashMap<>();
    /** Persistent external forces (N) per entity; guarded by {@code synchronized (bindings)}. */
    private final Map<Entity, Vec3> appliedForces = new IdentityHashMap<>();
    private final List<PredicateRegistration> predicates = new CopyOnWriteArrayList<>();

    /** Subscribes this binder to the Forge event bus. */
    public void registerOnEventBus() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /** Binds one entity to the gravity field, with estimated mass/volume. */    public Registration bind(Entity entity, GravityMode mode) {
        BindingLineage lineage;
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                mutation.changed();
                doBind(entity, mode, null, BodyPhysics.estimate(entity), true);
                lineage = bindings.get(entity).lineage;
            }
        }
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    unbindLineage(entity, lineage, null);
                }
            }
        };
    }

    /** Binds one entity to the gravity field, with explicit mass/volume. */
    public Registration bind(Entity entity, GravityMode mode, BodyPhysics physics) {
        BindingLineage lineage;
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                mutation.changed();
                doBind(entity, mode, null, physics, false);
                lineage = bindings.get(entity).lineage;
            }
        }
        return new Registration() {
            private final AtomicBoolean closed = new AtomicBoolean();

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    unbindLineage(entity, lineage, null);
                }
            }
        };
    }

    /**
     * Automatically binds every entity matching {@code predicate}: entities
     * loaded at registration time are swept immediately, later spawns are
     * caught via {@link EntityJoinWorldEvent}. Closing the registration
     * unbinds all entities it auto-bound.
     */
    public Registration bindPredicate(Predicate<Entity> predicate, GravityMode mode) {
        return bindPredicate(predicate, mode, null);
    }

    /**
     * Like {@link #bindPredicate(Predicate, GravityMode)}, but with a
     * per-tick activity filter: bound entities for which
     * {@code tickFilter} returns false on a given tick receive no binding-owned
     * gravity/force delta that tick (the binding and REPLACE's noGravity stay in
     * place). Independent floor magnetism is governed by actor eligibility.
     */
    public Registration bindPredicate(Predicate<Entity> predicate, GravityMode mode,
                                      Predicate<Entity> tickFilter) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return bindPredicate(predicate, mode, tickFilter, server == null ? new WorldServer[0] : server.worlds);
    }

    /** Registers the same automatic policy against an explicitly supplied current world set. */
    public Registration bindPredicate(Predicate<Entity> predicate, GravityMode mode,
                                      Predicate<Entity> tickFilter, WorldServer[] loadedWorlds) {
        PredicateRegistration registration =
                new PredicateRegistration(predicate, mode, tickFilter);
        predicates.add(registration);
        for (WorldServer world : loadedWorlds) {
            // Copy: binding during iteration would not modify the list,
            // but entity lists are not guaranteed stable while iterating.
            for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
                tryBindWith(registration, entity);
            }
        }
        return registration;
    }

    /** Number of currently bound entities (debug/diagnostic). */
    public int boundCount() {
        synchronized (bindings) {
            return bindings.size();
        }
    }

    /** Whether the entity is currently bound to the gravity field. */
    public boolean isBound(Entity entity) {
        synchronized (bindings) {
            return bindings.containsKey(entity);
        }
    }

    /**
     * Observes the current binding and evaluates its activity filter once, outside
     * the map monitor. No acceleration, mass refresh, source update or flag write
     * is performed here. A callback can change the actor/binding or throw: callers
     * must check {@link FieldBindingSample#isCurrent()} and their actor context.
     */
    public FieldBindingSample sampleFieldBinding(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Binding binding;
        Vec3 appliedForce;
        long forceRevision;
        synchronized (bindings) {
            binding = bindings.get(entity);
            appliedForce = appliedForces.get(entity);
            forceRevision = binding == null ? 0 : binding.forceRevision;
        }
        boolean active = binding != null && !entity.isDead
                && (binding.tickFilter == null || binding.tickFilter.test(entity));
        return new FieldBindingSample(entity, binding, appliedForce, forceRevision, active);
    }

    /** Same-call observation, not a lease or a permission to apply movement. */
    public final class FieldBindingSample {
        private final Entity entity;
        private final Binding binding;
        private final Vec3 appliedForce;
        private final long forceRevision;
        private final boolean active;

        private FieldBindingSample(Entity entity, Binding binding, Vec3 appliedForce, long forceRevision,
                                   boolean active) {
            this.entity = entity;
            this.binding = binding;
            this.appliedForce = appliedForce;
            this.forceRevision = forceRevision;
            this.active = active;
        }

        public Optional<GravityMode> mode() {
            return binding == null ? Optional.empty() : Optional.of(binding.mode);
        }

        public boolean active() { return active; }

        /** Bound physics captured with the observed binding, without an estimate refresh. */
        public Optional<BodyPhysics> physics() {
            return binding == null ? Optional.empty() : Optional.of(binding.physics);
        }

        /** Persistent force captured with the observed binding, absent when no force was set. */
        public Optional<Vec3> appliedForce() {
            return Optional.ofNullable(appliedForce);
        }

        /** Replacing/removing a binding or any real persistent-force mutation invalidates this observation. */
        public boolean isCurrent() {
            synchronized (bindings) {
                return bindings.get(entity) == binding
                        && (binding == null || binding.forceRevision == forceRevision);
            }
        }
    }

    /**
     * Runs all predicate registrations over one entity (idempotent: already
     * bound entities are skipped). Used by the client-side ticker, where
     * entities appear without a reliable join event.
     */
    public void sweepEntity(Entity entity) {
        for (PredicateRegistration registration : predicates) {
            tryBindWith(registration, entity);
        }
    }

    /**
     * Applies this tick's &#916;v to one bound entity, honoring its tick
     * filter; no-op when the entity is not bound. Used by the client-side
     * ticker for the local player (see ClientGravityTicker).
     */
    public void applyTick(Entity entity) {
        Binding binding;
        synchronized (bindings) {
            binding = bindings.get(entity);
        }
        if (binding != null && binding.estimated && !BodyPhysics.canEstimate(entity)) {
            unbind(entity, binding);
            return;
        }
        if (binding != null && !entity.isDead
                && (binding.tickFilter == null || binding.tickFilter.test(entity))) {
            applyDeltaV(entity, binding);
        }
    }

    /**
     * Sets a persistent external force (N) on a bound entity — e.g. engine
     * thrust. Applied every tick as a = F/m until changed; pass
     * {@link Vec3#ZERO} (or the entity gets unbound) to stop. Unlike
     * gravity, forces are divided by the entity's mass.
     */
    public void setForce(Entity entity, Vec3 newtons) {
        Vec3 normalized = newtons == null || newtons.equals(Vec3.ZERO) ? null : newtons;
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                Vec3 current = appliedForces.get(entity);
                if (Objects.equals(current, normalized)) {
                    return;
                }
                Binding binding = bindings.get(entity);
                long nextForceRevision = binding == null ? 0 : Math.incrementExact(binding.forceRevision);
                mutation.changed();
                if (normalized == null) {
                    appliedForces.remove(entity);
                } else {
                    appliedForces.put(entity, normalized);
                }
                if (binding != null) {
                    binding.forceRevision = nextForceRevision;
                }
            }
        }
    }

    /**
     * Applies an instantaneous impulse (N·s) to an entity: &#916;v = J/m.
     * Works for unbound entities too (mass is then estimated).
     */
    public void addImpulse(Entity entity, Vec3 impulseNewtonSeconds) {
        if (PlayerPhysicsServer.INSTANCE.addImpulse(entity, impulseNewtonSeconds)) {
            return;
        }
        if (RemoteBoostServer.INSTANCE.addImpulse(entity, impulseNewtonSeconds)) {
            return;
        }
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            Binding binding;
            synchronized (bindings) {
                binding = bindings.get(entity);
            }
            double mass = binding != null
                    ? binding.physics.massKg() : BodyPhysics.estimate(entity).massKg();
            // (m/s) → blocks/tick
            double scale = 1.0 / (mass * TICKS_PER_SECOND);
            mutation.changed();
            entity.motionX += impulseNewtonSeconds.x() * scale;
            entity.motionY += impulseNewtonSeconds.y() * scale;
            entity.motionZ += impulseNewtonSeconds.z() * scale;
            clampMotionToConfiguredMaximum(entity);
            entity.velocityChanged = true;
        }
    }

    /**
     * Replaces the {@link BodyPhysics} (mass/volume) of a bound entity,
     * preserving its mode, tick filter and registration ownership. Unbound
     * entities are REPLACE-bound on the spot (e.g. {@code /unigrav mass}
     * targeting a takeover-exempt entity).
     */
    public void setBodyPhysics(Entity entity, BodyPhysics physics) {
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                Binding old = bindings.get(entity);
                mutation.changed();
                if (old == null) {
                    doBind(entity, GravityMode.REPLACE, null, physics, false);
                    return;
                }
                Binding updated = new Binding(old.mode, old.restoreNoGravity,
                        old.tickFilter, physics, false, old.lineage);
                updated.owner = old.owner;
                bindings.put(entity, updated);
                EntityGravitySources.update(entity, physics);
            }
        }
    }

    /**
     * Recomputes the physics of an estimated binding (auto/formula mass)
     * and swaps it in when the estimate changed — e.g. a sheep being
     * sheared. Explicitly set physics ({@link #setBodyPhysics}, bind with
     * explicit values) is never refreshed. Also refreshes the entity's
     * gravity-source registration if it has one.
     *
     * @return the new binding when the estimate changed, null otherwise
     */
    private Binding refreshIfEstimated(Entity entity) {
        Binding observed;
        BodyPhysics fresh;
        synchronized (bindings) {
            observed = bindings.get(entity);
            if (observed == null || !observed.estimated) {
                return null;
            }
            fresh = BodyPhysics.canEstimate(entity) ? BodyPhysics.estimate(entity) : null;
            if (fresh != null && fresh.equals(observed.physics)) {
                return null;
            }
        }
        if (fresh == null) {
            // Acquire the mutation boundary outside the binding lock, and release only the
            // exact estimated entry observed above. A concurrent explicit replacement survives.
            unbind(entity, observed);
            return null;
        }
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                Binding binding = bindings.get(entity);
                if (binding != observed || !binding.estimated || fresh.equals(binding.physics)) {
                    return null;
                }
                mutation.changed();
                Binding updated = new Binding(binding.mode, binding.restoreNoGravity,
                        binding.tickFilter, fresh, true, binding.lineage);
                updated.owner = binding.owner;
                bindings.put(entity, updated);
                EntityGravitySources.update(entity, fresh);
                return updated;
            }
        }
    }

    /** Current {@link BodyPhysics} of an entity, or null when unbound. */
    public BodyPhysics bodyPhysicsOf(Entity entity) {
        // Refresh-on-read keeps estimated masses (e.g. a sheared sheep)
        // current on both sides — client-world entities are not
        // server-ticked, but their labels are drawn from this.
        Binding refreshed = refreshIfEstimated(entity);
        if (refreshed != null) {
            return refreshed.physics;
        }
        synchronized (bindings) {
            Binding binding = bindings.get(entity);
            return binding != null ? binding.physics : null;
        }
    }

    /** Peel off only this binder's REPLACE flag when a movement owner captures the prior native policy. */
    public boolean nativeNoGravityBeforeReplacement(Entity entity, boolean observedNoGravity) {
        synchronized (bindings) {
            Binding binding = bindings.get(entity);
            return observedNoGravity && (binding == null || !binding.restoreNoGravity);
        }
    }

    /** Return the physical owner's temporary flag to the native policy or the still-live REPLACE binding. */
    public void restoreNativeGravity(Entity entity, boolean nativeNoGravity) {
        synchronized (bindings) {
            Binding binding = bindings.get(entity);
            if (binding != null && binding.mode == GravityMode.REPLACE) {
                // This binding may have been created while the physical owner already held the flag.
                // Transfer restoration duty, so a later unbind does not leave permanent noGravity behind.
                binding.restoreNoGravity = !nativeNoGravity;
                entity.setNoGravity(true);
            } else entity.setNoGravity(nativeNoGravity);
        }
    }

    private void tryBindWith(PredicateRegistration registration, Entity entity) {
        // The predicate runs outside the lock (arbitrary user code); the
        // contains-check and the structural put are atomic under the lock.
        if (entity.isDead || registration.closed.get() || !registration.predicate.test(entity)
                || registration.closed.get()) {
            return;
        }
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                if (registration.closed.get() || bindings.containsKey(entity)) {
                    return;
                }
                if (!BodyPhysics.canEstimate(entity)) return;
                BodyPhysics physics = BodyPhysics.estimate(entity);
                mutation.changed();
                doBind(entity, registration.mode, registration, physics, true);
                registration.autoBound.add(entity);
            }
        }
    }

    /** Caller must hold {@code synchronized (bindings)}. */
    private void doBind(Entity entity, GravityMode mode, PredicateRegistration owner,
                        BodyPhysics physics, boolean estimated) {
        Binding previous = bindings.get(entity);
        boolean restore = previous != null && previous.restoreNoGravity;
        if (previous != null && previous.owner != null && previous.owner != owner) {
            previous.owner.autoBound.remove(entity);
        }
        if (mode == GravityMode.REPLACE && !entity.hasNoGravity()) {
            entity.setNoGravity(true);
            restore = true;
        } else if (mode != GravityMode.REPLACE && restore) {
            if (!entity.isDead) entity.setNoGravity(false);
            restore = false;
        }
        Binding binding = new Binding(mode, restore,
                owner != null ? owner.tickFilter : null, physics, estimated);
        binding.owner = owner;
        bindings.put(entity, binding);
        EntityGravitySources.update(entity, physics);
    }

    private void unbind(Entity entity) {
        unbind(entity, null, null, false);
    }

    private void unbind(Entity entity, Binding expected) {
        unbind(entity, expected, null, true);
    }

    private void unbindLineage(Entity entity, BindingLineage expectedLineage, PredicateRegistration expectedOwner) {
        unbind(entity, null, expectedLineage, expectedOwner, true);
    }

    private void unbind(Entity entity, Binding expected, BindingLineage expectedLineage, boolean requireBindingMatch) {
        unbind(entity, expected, expectedLineage, null, requireBindingMatch);
    }

    private void unbind(Entity entity, Binding expected, BindingLineage expectedLineage,
                        PredicateRegistration expectedOwner, boolean requireBindingMatch) {
        Binding binding;
        try (var mutation = NativePlayerMovement.INSTANCE.beginForceMutation(entity)) {
            synchronized (bindings) {
                binding = bindings.get(entity);
                if (requireBindingMatch && (binding == null
                        || expected != null && binding != expected
                        || expectedLineage != null && binding.lineage != expectedLineage
                        || expectedOwner != null && binding.owner != expectedOwner)) return;
                if (binding != null || appliedForces.containsKey(entity)) {
                    mutation.changed();
                }
                bindings.remove(entity);
                appliedForces.remove(entity);
                if (binding != null && binding.owner != null) binding.owner.autoBound.remove(entity);
            }
            EntityGravitySources.remove(entity);
            if (binding != null) {
                if (binding.restoreNoGravity && !entity.isDead) {
                    entity.setNoGravity(false);
                }
            }
        }
    }

    private Optional<Vec3> applyDeltaV(Entity entity, Binding binding) {
        double time = entity.world.getTotalWorldTime() * TICK_SECONDS;
        Vec3 fieldAccel = EntityGravitySources.fieldAcceleration(entity,
                new Vec3(entity.posX, entity.posY, entity.posZ), time);
        Vec3 magneticAccel = Vec3.ZERO;
        boolean environmentReady = true;
        if (!entity.world.isRemote && !(entity instanceof EntityPlayer)) {
            Optional<FloorFieldIndex.Sample> floor = sampleFloors(entity,
                    MagneticResponseRules.builtIn().response(entity, false));
            environmentReady = floor.isPresent();
            if (floor.isPresent()) {
                fieldAccel = fieldAccel.add(floor.get().gravityAccel());
                magneticAccel = floor.get().magneticAccel();
            }
        }
        Vec3 force;
        synchronized (bindings) {
            force = appliedForces.get(entity);
        }
        Vec3 accel = BodyAccelerationStep.acceleration(fieldAccel, magneticAccel, force,
                binding.physics, entity.isInWater(), BodyPhysics.swims(entity), UnigravConfig.waterSinkBias);
        applyAcceleration(entity, accel);
        // Deferred discovery is not zero gravity. Preserve the previous AI/environment
        // decision while retaining the existing background/external-force integration.
        return environmentReady ? Optional.of(fieldAccel.add(magneticAccel)) : Optional.empty();
    }

    private static Optional<FloorFieldIndex.Sample> sampleFloors(Entity entity, double response) {
        var box = entity.getEntityBoundingBox();
        Vec3 center = new Vec3(box.minX + (box.maxX - box.minX) * 0.5,
                box.minY + (box.maxY - box.minY) * 0.5, box.minZ + (box.maxZ - box.minZ) * 0.5);
        return FloorWorldFields.sample(entity.world, center, response);
    }

    private static void applyAcceleration(Entity entity, Vec3 acceleration) {
        boolean grounded = UnigravConfig.groundFriction && entity.onGround;
        double friction = grounded ? entity.world.getBlockState(new BlockPos(entity).down())
                .getBlock().slipperiness * 0.9100000262260437 : 1.0;
        Vec3 motion = BodyAccelerationStep.motion(new Vec3(entity.motionX, entity.motionY, entity.motionZ),
                acceleration, UnigravConfig.maxEntitySpeed, grounded, friction);
        entity.motionX = motion.x();
        entity.motionY = motion.y();
        entity.motionZ = motion.z();
        entity.velocityChanged = true;
    }

    /**
     * Applies the process-wide final motion safety limit after every UG-owned
     * acceleration or impulse. Centralizing this prevents debug boost, free
     * drift, and future propulsion from bypassing gravity's collision-sweep
     * protection.
     */
    private static void clampMotionToConfiguredMaximum(Entity entity) {
        Vec3 motion = BodyAccelerationStep.limitMotion(new Vec3(entity.motionX, entity.motionY, entity.motionZ),
                UnigravConfig.maxEntitySpeed);
        entity.motionX = motion.x();
        entity.motionY = motion.y();
        entity.motionZ = motion.z();
    }

    /** Field accel (m/s²) below which an airborne entity counts as weightless. */
    private static final double ZERO_G_EPSILON = 0.5;

    /**
     * Zero-G pathfinding pause ({@code zerog_pause_ai} config): a floating
     * living entity (weightless field, airborne, not in water) whose species
     * cannot path in zero-G gets its navigator path cleared every tick — the
     * ground navigator would otherwise spin it in place trying to walk on
     * air. Only pathfinding is paused: look/idle AI (head tracking) keeps
     * running and knockback still drifts the body, so the mob stays lively.
     * Stateless: nothing to restore when gravity returns.
     */
    private void handleZeroGaI(Entity entity, Vec3 fieldAccel) {
        if (!UnigravConfig.zeroGPauseAi
                || !(entity instanceof EntityLiving living)) {
            return;
        }
        boolean weightless = fieldAccel.norm() < ZERO_G_EPSILON
                && !entity.onGround && !entity.isInWater();
        if (weightless && !BodyPhysics.pathsInZeroG(entity)) {
            // Cancel both the path and any pending move order — the latter
            // outlives clearPath() and would keep steering the body toward
            // the last wander target (the spinning).
            living.getNavigator().clearPath();
            living.getMoveHelper().setMoveTo(entity.posX, entity.posY,
                    entity.posZ, 0.0);
        }
    }

    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinWorldEvent event) {
        sweepEntity(event.getEntity());
    }

    /** Explicit registrations and /mass bindings have no predicate handle to close at shutdown. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        releaseWorld(event.getWorld());
    }

    /** Called before the owning server saves its worlds, and again on unload (idempotent). */
    public void releaseWorld(World world) {
        List<Entity> retired;
        synchronized (bindings) {
            Set<Entity> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
            candidates.addAll(bindings.keySet());
            candidates.addAll(appliedForces.keySet());
            retired = candidates.stream().filter(entity -> entity.world == world).toList();
        }
        for (Entity entity : retired) unbind(entity);
    }

    // EntityJoinWorldEvent never fires for players, and players are not in
    // World.loadedEntityList (they live in World.playerEntities) — observed
    // live: the server-side EntityPlayerMP stayed unbound, so impact damage
    // and noGravity consistency silently skipped the local player. Bind
    // players explicitly on login/respawn/dimension change (idempotent).
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        sweepEntity(event.player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        sweepEntity(event.player);
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sweepEntity(event.player);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null) tickServerWorlds(server.worlds);
    }

    /** Applies one server tick only to the current server's published, thread-owned worlds. */
    public void tickServerWorlds(WorldServer[] worlds) {
        Set<WorldServer> currentWorlds = Collections.newSetFromMap(new IdentityHashMap<>());
        for (WorldServer world : worlds) {
            if (world != null && world.isCallingFromMinecraftThread()) currentWorlds.add(world);
        }
        // Iterate a snapshot: the client thread (ClientGravityTicker) and
        // join events bind entities concurrently, which would otherwise
        // invalidate a live IdentityHashMap iterator.
        List<Map.Entry<Entity, Binding>> snapshot;
        synchronized (bindings) {
            snapshot = new ArrayList<>(bindings.entrySet());
        }
        Set<Entity> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Entity> dead = null;
        for (Map.Entry<Entity, Binding> entry : snapshot) {
            Entity entity = entry.getKey();
            if (entity.isDead) {
                if (dead == null) {
                    dead = new ArrayList<>();
                }
                dead.add(entity);
                continue;
            }
            if (entity.world.isRemote) {
                continue; // client-side entities are ticked by ClientGravityTicker
            }
            // Integrated servers reuse this binder, not their world or thread. Never cross that lifetime.
            if (!(entity.world instanceof WorldServer ownerWorld) || !currentWorlds.contains(ownerWorld)) {
                continue;
            }
            if (entry.getValue().estimated && !BodyPhysics.canEstimate(entity)) {
                refreshIfEstimated(entity);
                handled.add(entity);
                continue;
            }
            if (entity instanceof EntityPlayerMP) {
                // Player movement is client-authoritative: the server never
                // integrates player position from motion, so a server-side
                // Δv would just accumulate in EntityPlayerMP.motion and be
                // pushed back to the client via velocity packets as an
                // ever-growing absolute velocity (observed live as runaway
                // gravity). The local player's Δv is applied client-side by
                // ClientGravityTicker; the server-side binding still keeps
                // noGravity for consistency.
                continue;
            }
            if (!entity.addedToChunk) {
                continue; // chunk unloaded: skip, keep the binding
            }
            Binding binding = entry.getValue();
            if (binding.tickFilter != null && !binding.tickFilter.test(entity)) {
                continue;
            }
            // Estimated (non-explicit) physics tracks entity state changes
            // like shearing a sheep; staggered so each entity refreshes
            // every 16 ticks. Explicit /unigrav mass values are untouched.
            if (binding.estimated
                    && ((entity.ticksExisted + entity.getEntityId()) & 15) == 0) {
                Binding refreshed = refreshIfEstimated(entity);
                if (refreshed != null) {
                    binding = refreshed;
                }
            }
            Optional<Vec3> fieldAccel = applyDeltaV(entity, binding);
            handled.add(entity);
            fieldAccel.ifPresent(acceleration -> handleZeroGaI(entity, acceleration));
        }
        // Deferred structural removal of dead entities, after the loop.
        if (dead != null) {
            synchronized (bindings) {
                for (Entity entity : dead) {
                    Binding binding = bindings.remove(entity);
                    if (binding != null && binding.owner != null) {
                        binding.owner.autoBound.remove(entity);
                    }
                    EntityGravitySources.remove(entity);
                }
            }
        }
        applyIndependentFloorMagnetism(handled, currentWorlds);
    }

    /** Gravity exemptions/filters do not create material immunity; apply each floor delta once. */
    private void applyIndependentFloorMagnetism(Set<Entity> alreadyHandled, Set<WorldServer> worlds) {
        for (WorldServer world : worlds) {
            for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
                if (alreadyHandled.contains(entity) || entity.isDead || !entity.addedToChunk
                        || entity.world != world || entity instanceof EntityPlayer) continue;
                double response = MagneticResponseRules.builtIn().response(entity, false);
                if (response == 0.0) continue;
                sampleFloors(entity, response).ifPresent(sample -> {
                    // This path does not take over gravity, noGravity, bindings or AI.
                    if (!sample.magneticAccel().equals(Vec3.ZERO)) applyAcceleration(entity, sample.magneticAccel());
                });
            }
        }
    }
}
