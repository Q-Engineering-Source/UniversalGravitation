package wi.unigrav.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.world.WorldEvent;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.network.ImpactReportMessage;
import wi.unigrav.core.player.PlayerPhysicsServer;

/**
 * Velocity-based impact damage, replacing vanilla fall damage for bound
 * living entities.
 *
 * <p>Vanilla fall damage counts <em>distance</em> fallen, so under a low-G
 * field an arbitrarily slow descent over 4+ blocks still deals full damage
 * (observed live), and under sideways/upward gravity no damage happens at
 * all. This handler instead measures the velocity carried into the tick on
 * which a collision occurred:
 *
 * <ul>
 *   <li>landing (onGround rising edge) → {@link DamageSource#FALL}, speed
 *       = downward velocity;
 *   <li>ceiling (collidedVertically while moving up, e.g. pulled onto a
 *       ceiling by an inverted field) → {@link DamageSource#FLY_INTO_WALL};
 *   <li>wall (collidedHorizontally rising edge) → FLY_INTO_WALL, speed =
 *       horizontal velocity;
 *   <li>entity-entity ramming (overlapping boxes, high relative speed) →
 *       both sides take scale·v_rel² weighted by the <em>other</em> side's
 *       mass share — a heavy truck-like entity wrecks a light one.
 * </ul>
 *
 * <p>Damage points = scale·(v² − v_safe²); the defaults (v_safe 14 m/s,
 * scale 0.021) reproduce vanilla numbers at the usual falls: 4 blocks
 * ≈ 1 pt, 10 blocks ≈ 7 pt, 20+ blocks lethal. Vanilla i-frames
 * (hurtResistantTime) provide the per-entity cooldown, so a mob sliding
 * along a wall is not shredded every tick.
 *
 * <p>Velocity estimate: non-player entities use their authoritative
 * server-side motion. Physically owned players are settled by the committed
 * server movement step. Legacy players retain client impact reports; those
 * reports are rejected while server physics drives the actor. Tick sampling
 * still uses player position deltas for entity-ramming relative speeds.
 */
public final class ImpactDamageHandler {
    public static final ImpactDamageHandler INSTANCE = new ImpactDamageHandler(PlayerPhysicsServer.INSTANCE);
    private final PlayerPhysicsServer playerPhysics;
    private final ImpactDiagnosticWindow diagnostics = new ImpactDiagnosticWindow();
    private MinecraftServer diagnosticServer;
    private long diagnosticWindowStartedNanos;

    public ImpactDamageHandler() { this(PlayerPhysicsServer.INSTANCE); }

    public ImpactDamageHandler(PlayerPhysicsServer playerPhysics) {
        this.playerPhysics = Objects.requireNonNull(playerPhysics, "player physics");
    }

    /** Per-entity state from the previous tick. */
    private static final class Tracked {
        double x, y, z;
        double vx, vy, vz; // velocity estimate (m/s) carried into the move
        boolean onGround, collidedHorizontally;
        long lastSeen;
        int ticksExisted;
        boolean initialized;
    }

    /** Server thread only — no synchronization needed. */
    private final Map<Entity, Tracked> tracked = new IdentityHashMap<>();

    /** Cancels vanilla distance-based fall damage for bound entities. */
    @SubscribeEvent
    public void onLivingFall(LivingFallEvent event) {
        if (UnigravConfig.impactDamage
                && UnigravMod.entityBinder.isBound(event.getEntityLiving())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        if (diagnosticServer != server) {
            diagnostics.reset();
            diagnosticServer = server;
            diagnosticWindowStartedNanos = System.nanoTime();
        }
        for (WorldServer world : server.worlds) {
            for (Entity entity : new ArrayList<>(world.loadedEntityList)) {
                // Players are NOT in loadedEntityList (they live in
                // playerEntities) — skip the type here in case and handle
                // them explicitly below (observed live: the local player
                // silently missed all impact checks).
                if (entity instanceof EntityLivingBase living && !entity.isDead
                        && !(entity instanceof EntityPlayer)) {
                    tick(world, living);
                }
            }
            for (EntityPlayer player : world.playerEntities) {
                if (!player.isDead) {
                    tick(world, player);
                }
            }
        }
        if (server.worlds.length > 0
                && server.worlds[0].getTotalWorldTime() % 200 == 0) {
            purge(server.worlds[0].getTotalWorldTime());
        }
        long now = System.nanoTime();
        if (now - diagnosticWindowStartedNanos >= 5_000_000_000L) {
            flushDiagnostics(now - diagnosticWindowStartedNanos);
            diagnosticWindowStartedNanos = now;
        }
    }

    /** A world/server transition discards a partial aggregate instead of mixing lifecycles. */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            diagnostics.reset();
            diagnosticWindowStartedNanos = System.nanoTime();
        }
    }

    private void tick(WorldServer world, EntityLivingBase living) {
        Tracked t = tracked.computeIfAbsent(living, e -> new Tracked());
        long worldTime = world.getTotalWorldTime();
        if (!t.initialized) {
            snapshot(living, t);
            t.lastSeen = worldTime;
            t.vx = t.vy = t.vz = 0.0;
            t.initialized = true;
            return;
        }
        boolean bound = UnigravMod.entityBinder.isBound(living);
        boolean player = living instanceof EntityPlayer;
        // Owned player impacts are settled at the exact movement commit; legacy
        // player impacts retain client reports. Neither is inferred again from
        // potentially stale END-phase MP flags. This tracker handles ramming.
        if (bound && UnigravConfig.impactDamage && !living.isRiding() && !player) {
            checkImpacts(world, living, t);
        }
        if (bound && UnigravConfig.collisionDamage) {
            checkRamming(world, living, t);
        }
        // Store the velocity this entity will carry into next tick's move.
        if (living instanceof EntityPlayer) {
            // Player motion is client-authoritative: estimate from the
            // server-side position delta of this tick.
            t.vx = (living.posX - t.x) / GravityEntityBinder.TICK_SECONDS;
            t.vy = (living.posY - t.y) / GravityEntityBinder.TICK_SECONDS;
            t.vz = (living.posZ - t.z) / GravityEntityBinder.TICK_SECONDS;
        } else {
            t.vx = living.motionX * GravityEntityBinder.TICKS_PER_SECOND;
            t.vy = living.motionY * GravityEntityBinder.TICKS_PER_SECOND;
            t.vz = living.motionZ * GravityEntityBinder.TICKS_PER_SECOND;
        }
        snapshot(living, t);
        t.lastSeen = worldTime;
    }

    /**
     * Applies a client-reported player impact (see {@code
     * wi.unigrav.core.network.ImpactReportMessage}). Re-validates before
     * damaging: the player must be bound, alive, not riding, and the
     * reported speed inside [safe speed, max entity speed].
     */
    public static void applyPlayerImpact(EntityPlayerMP player, int type, double speed) {
        INSTANCE.applyReportedPlayerImpact(player, type, speed);
    }

    /** The legacy report never supplies authority for an already server-driven actor. */
    public void applyReportedPlayerImpact(EntityPlayerMP player, int type, double speed) {
        if (playerPhysics.drivesMotion(player)) return;
        applyAuthoritativePlayerImpact(player, type, speed);
    }

    /** Called by the committed collision step with its pre-collision velocity, not by packet input. */
    public static void applyAuthoritativePlayerImpact(EntityPlayerMP player, int type, double speed) {
        if (!UnigravConfig.impactDamage || player.isDead || player.isRiding()
                || !UnigravMod.entityBinder.isBound(player)) {
            return;
        }
        double safe = UnigravConfig.impactSafeSpeed;
        double max = UnigravConfig.maxEntitySpeed > 0.0
                ? UnigravConfig.maxEntitySpeed : 500.0;
        if (speed < safe || speed > max) {
            return;
        }
        String damageType = type == ImpactReportMessage.TYPE_FALL ? "fall" : "flyIntoWall";
        UnigravMod.LOGGER.info("[impact] player impact: type={}, v={} m/s",
                damageType, String.format("%.1f", speed));
        hurt(player, impactSource(damageType), speed);
    }

    private void checkImpacts(WorldServer world, EntityLivingBase living, Tracked t) {
        double safe = UnigravConfig.impactSafeSpeed;
        // Landing: downward speed carried into the tick we touched ground.
        if (!t.onGround && living.onGround && t.vy < -safe && !living.isInWater()) {
            hurtTracked(world, living, impactSource("fall"), -t.vy, 1.0, t);
        } else if (living.collidedVertically && t.vy > safe) {
            // Slammed into a ceiling (inverted field, high jump, zero-G drift).
            hurtTracked(world, living, impactSource("flyIntoWall"), t.vy, 1.0, t);
        }
        if (living.collidedHorizontally && !t.collidedHorizontally) {
            double horizontal = Math.hypot(t.vx, t.vz);
            if (horizontal > safe) {
                hurtTracked(world, living, impactSource("flyIntoWall"), horizontal, 1.0, t);
            }
        }
    }

    private void checkRamming(WorldServer world, EntityLivingBase living, Tracked t) {
        double minSpeed = UnigravConfig.collisionMinSpeed;
        // Swept box: grow by the path travelled this tick (t.v* is the
        // pre-move velocity), so a fast rammer cannot tunnel through a
        // target between ticks (2 blocks/tick at 40 m/s).
        AxisAlignedBB box = living.getEntityBoundingBox().grow(0.3)
                .expand(-t.vx * GravityEntityBinder.TICK_SECONDS,
                        -t.vy * GravityEntityBinder.TICK_SECONDS,
                        -t.vz * GravityEntityBinder.TICK_SECONDS);
        List<Entity> nearby = world.getEntitiesWithinAABBExcludingEntity(living, box);
        for (Entity other : nearby) {
            if (other.isDead || other == living.getRidingEntity()
                    || living.isRidingSameEntity(other)
                    // Process each unordered pair once.
                    || System.identityHashCode(other) < System.identityHashCode(living)) {
                continue;
            }
            Tracked o = tracked.get(other);
            double ovx = o != null ? o.vx : other.motionX * GravityEntityBinder.TICKS_PER_SECOND;
            double ovy = o != null ? o.vy : other.motionY * GravityEntityBinder.TICKS_PER_SECOND;
            double ovz = o != null ? o.vz : other.motionZ * GravityEntityBinder.TICKS_PER_SECOND;
            // Symmetric sweep: the target's own travelled path counts too,
            // otherwise a fast rammer meets a thin stationary box and can
            // still tunnel through it.
            AxisAlignedBB otherBox = other.getEntityBoundingBox().grow(0.3)
                    .expand(-ovx * GravityEntityBinder.TICK_SECONDS,
                            -ovy * GravityEntityBinder.TICK_SECONDS,
                            -ovz * GravityEntityBinder.TICK_SECONDS);
            if (!box.intersects(otherBox)) {
                continue;
            }
            double relX = t.vx - ovx, relY = t.vy - ovy, relZ = t.vz - ovz;
            double relSpeed = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
            if (relSpeed <= minSpeed) {
                continue;
            }
            double mA = massOf(living);
            double mB = massOf(other);
            // Each side's damage scales with the OTHER side's mass share.
            hurtTracked(world, living, ramSource(other), relSpeed, mB / (mA + mB), t);
            if (other instanceof EntityLivingBase otherLiving) {
                hurtTracked(world, otherLiving, ramSource(living), relSpeed, mA / (mA + mB), o);
            }
        }
    }

    private static DamageSource impactSource(String vanillaDamageType) {
        // Fresh instance: the shared DamageSource.FALL etc. must not be
        // mutated. Vanilla damage-type id keeps the vanilla death messages.
        DamageSource source = new DamageSource(vanillaDamageType);
        if (UnigravConfig.impactDamageCreative) {
            source.setDamageAllowedInCreativeMode();
        }
        return source;
    }

    private static DamageSource ramSource(Entity rammer) {
        DamageSource source;
        if (rammer instanceof EntityPlayer player) {
            source = DamageSource.causePlayerDamage(player);
        } else if (rammer instanceof EntityLivingBase living) {
            source = DamageSource.causeMobDamage(living);
        } else {
            source = impactSource("generic");
        }
        if (UnigravConfig.impactDamageCreative
                && source.damageType != null && !"generic".equals(source.damageType)) {
            source.setDamageAllowedInCreativeMode();
        }
        return source;
    }

    private static double massOf(Entity entity) {
        BodyPhysics physics = UnigravMod.entityBinder.bodyPhysicsOf(entity);
        return physics != null ? physics.massKg() : BodyPhysics.estimate(entity).massKg();
    }

    /** Impact damage: scale·(v² − v_safe²), matching vanilla fall numbers. */
    private static void hurt(EntityLivingBase living, DamageSource source, double speed) {
        hurt(living, source, speed, 1.0);
    }

    private static void hurt(EntityLivingBase living, DamageSource source,
                             double speed, double massShare) {
        double safe = UnigravConfig.impactSafeSpeed;
        float points = (float) (UnigravConfig.impactDamageScale * massShare
                * (speed * speed - safe * safe));
        if (points >= 0.5F) {
            UnigravMod.LOGGER.info("[impact] {} takes {} pts from {} (v={} m/s, share={})",
                    living.getName(), String.format("%.1f", points), source.damageType,
                    String.format("%.1f", speed), String.format("%.2f", massShare));
            living.attackEntityFrom(source, points);
        }
    }

    /**
     * Preserves the existing threshold and exactly one attack call while
     * recording its boolean result after the call. This is deliberately
     * separate from the player-report path, whose client diagnostics are owned
     * elsewhere.
     */
    private void hurtTracked(WorldServer world, EntityLivingBase living, DamageSource source,
                             double speed, double massShare, Tracked trackedSample) {
        double safe = UnigravConfig.impactSafeSpeed;
        float points = (float) (UnigravConfig.impactDamageScale * massShare
                * (speed * speed - safe * safe));
        if (points >= 0.5F) {
            ImpactDiagnosticWindow.Observation observation = impactObservation(world, living, trackedSample, speed);
            boolean accepted = living.attackEntityFrom(source, points);
            diagnostics.record(observation.withAttackAccepted(accepted));
        }
    }

    private ImpactDiagnosticWindow.Observation impactObservation(WorldServer world, EntityLivingBase living,
                                                                  Tracked trackedSample, double impactSpeed) {
        // Diagnostics must not perturb damage handling, including an invalid
        // pre-existing entity motion value. Such values are recorded unknown.
        // Player tracker velocity is position-derived, not a cached raw motion observation.
        boolean cachedKnown = !(living instanceof EntityPlayer) && trackedSample != null
                && finiteMagnitude(trackedSample.vx, trackedSample.vy, trackedSample.vz);
        double cached = cachedKnown ? magnitude(trackedSample.vx, trackedSample.vy, trackedSample.vz) : 0.0;
        boolean tickKnown = trackedSample != null;
        // This is a world-tick displacement sample, not an entity-update
        // velocity claim. A consecutive world timestamp makes a zero delta
        // meaningful even when ticksExisted stalled; tick progress is reported
        // independently below.
        boolean deltaKnown = tickKnown
                && trackedSample.lastSeen == world.getTotalWorldTime() - 1
                && finiteMagnitude(living.posX - trackedSample.x, living.posY - trackedSample.y,
                        living.posZ - trackedSample.z);
        double positionDelta = deltaKnown
                ? magnitude(living.posX - trackedSample.x, living.posY - trackedSample.y,
                        living.posZ - trackedSample.z) / GravityEntityBinder.TICK_SECONDS : 0.0;
        boolean impactKnown = Double.isFinite(impactSpeed) && impactSpeed >= 0.0;
        String type = EntityList.getKey(living) == null ? living.getClass().getName()
                : EntityList.getKey(living).toString();
        return new ImpactDiagnosticWindow.Observation(
                new ImpactDiagnosticWindow.Identity(living.getUniqueID().toString(), living.getEntityId(), type,
                        living.dimension, living.posX, living.posY, living.posZ),
                impactKnown, impactSpeed, cachedKnown, cached, deltaKnown, positionDelta, tickKnown,
                tickKnown ? trackedSample.ticksExisted : 0, living.ticksExisted,
                living.onGround, living.collidedHorizontally, living.collidedVertically, false);
    }

    private void flushDiagnostics(long elapsedNanos) {
        diagnostics.drain().ifPresent(report -> {
            ImpactDiagnosticWindow.Peak peak = report.peak();
            UnigravMod.LOGGER.info("[impact] aggregate windowMs={} scope={} requested={} accepted={} rejected={} "
                            + "impactMps[n={},max={},unknown={}] cachedMotionMps[n={},max={},unknown={}] "
                            + "positionDeltaMps[n={},max={},unknown={}] ticks[advance={},stall={},discontinuous={},unknown={}] "
                            + "flags[ground={},horizontal={},vertical={}] peak[uuid={},id={},type={},dim={},pos=({}, {}, {}),"
                            + "impactMps={},impactKnown={},cachedMps={},cachedKnown={},deltaMps={},deltaKnown={},tickKnown={}]",
                    elapsedNanos / 1_000_000L, report.scope(), report.requestedHits(), report.acceptedHits(), report.rejectedHits(),
                    report.impactSpeed().count(), report.impactSpeed().maximumMps(), report.impactSpeedUnknown(),
                    report.cachedMotion().count(), report.cachedMotion().maximumMps(), report.cachedMotionUnknown(),
                    report.positionDelta().count(), report.positionDelta().maximumMps(), report.positionDeltaUnknown(),
                    report.ticksAdvanced(), report.ticksStalled(), report.ticksDiscontinuous(), report.ticksUnknown(),
                    report.onGround(), report.collidedHorizontally(), report.collidedVertically(),
                    peak.identity().uuid(), peak.identity().entityId(), peak.identity().type(), peak.identity().dimension(),
                    peak.identity().x(), peak.identity().y(), peak.identity().z(), peak.impactSpeedMps(),
                    peak.impactSpeedKnown(), peak.cachedMotionMps(), peak.cachedMotionKnown(), peak.positionDeltaMps(),
                    peak.positionDeltaKnown(), peak.tickProgressKnown());
        });
    }

    private static boolean finiteMagnitude(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Double.isFinite(magnitude(x, y, z));
    }

    private static double magnitude(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static void snapshot(EntityLivingBase living, Tracked t) {
        t.x = living.posX;
        t.y = living.posY;
        t.z = living.posZ;
        t.onGround = living.onGround;
        t.collidedHorizontally = living.collidedHorizontally;
        t.ticksExisted = living.ticksExisted;
    }

    /** Drops entries for dead or long-unseen entities. */
    private void purge(long worldTime) {
        Iterator<Map.Entry<Entity, Tracked>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Entity, Tracked> entry = it.next();
            if (entry.getKey().isDead || worldTime - entry.getValue().lastSeen > 200) {
                it.remove();
            }
        }
    }
}
