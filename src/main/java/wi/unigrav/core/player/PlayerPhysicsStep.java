package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.stats.StatList;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.ImpactDamageHandler;
import wi.unigrav.core.network.ImpactReportMessage;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.boost.BoostGearProfile;
import wi.unigrav.core.boost.MotionSpeedLimit;
import wi.unigrav.core.boost.RemoteBoostCorridor;

/** One owner-scheduled environment/collision transaction. Never grants a new movement budget. */
public final class PlayerPhysicsStep {
    /** Near-foot locomotion allowance in metres; this is not magnetic capture or ground contact. */
    private static final double ZERO_G_FOOT_REACH = 0.25;
    private static final double ZERO_G_WALK_SPEED = 0.25;
    private static final double ZERO_G_SPRINT_SPEED = 0.5;
    private static final double ZERO_G_WALK_ACCELERATION = 2.0;
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final CardinalMovementResolver.Limits MOVE =
            new CardinalMovementResolver.Limits(512, 16384, 100000);
    private final PlayerEnvironmentSampler environment;

    public sealed interface Result permits Applied, Unavailable, CommittedFailure, CommittedDeferred { }
    public record Applied(PlayerPhysicalSnapshot snapshot) implements Result { }
    public record Unavailable(String reason, Optional<RuntimeException> cause) implements Result { }
    /** The move and debit happened; remaining callbacks failed and must never be replayed. */
    public record CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause) implements Result { }
    /** A bounded tail query was unavailable, but its already installed movement is not undone. */
    public record CommittedDeferred(PlayerPhysicalSnapshot snapshot, Unavailable tail) implements Result { }
    /** Per-owner native jump cooldown and correction fence, never shared between players or sides. */
    public static final class TravelState {
        private int jumpTicks;
        private long nativeCorrectionRevision;
        boolean nativeNoGravity;
        private CardinalFrame pendingReference;
        private CardinalReorientation.Reason pendingReason;
        private boolean lastAdoptionGrounded;
        private long adoptions;

        long correctionRevision() { return nativeCorrectionRevision; }

        void invalidate() {
            nativeCorrectionRevision = Math.incrementExact(nativeCorrectionRevision);
        }

        /** The world already names the accepted reference; no transition is pending. */
        private void acceptedReference(CardinalFrame reference) {
            if (pendingReference == reference) { pendingReference = null; pendingReason = null; }
        }

        /** No standing constraint is present, so no target may be retained as pending. */
        private void withoutReference() {
            pendingReference = null;
            pendingReason = null;
        }

        private void adoptedReference(CardinalFrame reference, boolean onGround) {
            pendingReference = null;
            pendingReason = null;
            lastAdoptionGrounded = onGround;
            adoptions = Math.incrementExact(adoptions);
        }

        /** A rejected transition keeps the old state whole; the target stays explicit for retry. */
        private void deferredReference(CardinalFrame reference, CardinalReorientation.Reason reason) {
            pendingReference = reference;
            pendingReason = reason;
        }

        /** Explicit pending reorientation target, or empty when the accepted reference still stands. */
        public Optional<CardinalFrame> pendingReference() { return Optional.ofNullable(pendingReference); }

        public Optional<CardinalReorientation.Reason> pendingReason() { return Optional.ofNullable(pendingReason); }

        /** Support re-derived by the last successful reorientation, not inherited from the old frame. */
        public boolean lastAdoptionGrounded() { return lastAdoptionGrounded; }

        public long adoptions() { return adoptions; }
    }

    public PlayerPhysicsStep(PlayerEnvironmentSampler environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public Result step(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
                       PlayerPhysicsProtocol.Controls controls, BoostMovementBudget budget, long budgetTick,
                       boolean boostCarrier, TravelState travel) {
        var world = actor.getServerWorld();
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("Physics step off-thread");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal acceptedShape))
            return unavailable("unsupported physical frame");
        CardinalFrame frame = acceptedShape.frame();
        PlayerBodyShape.Dimensions dimensions = acceptedShape.dimensions();
        long correctionRevision = travel.nativeCorrectionRevision;
        var sampled = environment.sample(actor, accepted, personalZeroG);
        if (correctionRevision != travel.nativeCorrectionRevision) return unavailable("movement authority changed during sampling");
        if (sampled instanceof PlayerEnvironmentSampler.Unavailable failure)
            return new Unavailable("environment: " + failure.reason(), failure.cause());
        var sample = (PlayerEnvironmentSampler.Sample) sampled;
        long tick = world.getTotalWorldTime();
        Vec3 acceleration = sample.environmentAcceleration();
        Vec3d v = accepted.worldVelocity();
        double dt = GravityEntityBinder.TICK_SECONDS;
        budget.advance(budgetTick);
        boolean propulsion = sample.personalZeroG() && actor.interactionManager.isCreative();
        boolean flying = !propulsion && actor.capabilities.isFlying;
        boolean water = !propulsion && !flying && actor.isInWater();
        boolean lava = !propulsion && !flying && !water && actor.isInLava();
        boolean fluid = water || lava;
        boolean gliding = !propulsion && !fluid && actor.isElytraFlying();
        boolean nativeGravity = !sample.personalZeroG() && !travel.nativeNoGravity
                && sample.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty() && !flying;
        if (nativeGravity) acceleration = acceleration.add(new Vec3(0, fluid ? -8 : -32, 0));
        // The installed physical frame, not a fresh enum tie-break or the view, owns reference continuity.
        var standing = StandingConstraints.resolve(new StandingConstraints.State(StandingConstraints.BootPhase.NONE,
                        Optional.empty(), Optional.of(frame), Optional.empty(), -1),
                new StandingConstraints.Request(tick, false, sample.evidence()));
        if (standing.presence() == StandingConstraints.ConstraintPresence.PRESENT) {
            CardinalFrame requested = standing.targetReference().orElseThrow();
            if (requested == frame) {
                travel.acceptedReference(requested);
            } else {
                // A standing constraint now names a different physical up. Adopt the whole new state
                // atomically, or keep the previous one complete and retry against an explicit target.
                var reorientation = CardinalReorientation.adopt(world, actor, accepted, requested, budget, budgetTick);
                if (reorientation instanceof CardinalReorientation.Installed installed) {
                    accepted = installed.snapshot();
                    frame = installed.frame();
                    travel.adoptedReference(requested, installed.onGround());
                } else {
                    travel.deferredReference(requested, ((CardinalReorientation.Rejected) reorientation).reason());
                }
            }
        } else {
            travel.withoutReference();
        }
        // The corridor feet anchor may have changed with the frame; apply it once for this tick.
        if (boostCarrier) applyCorridor(budget, budgetTick, actor.posY);
        var requestedReference = standing.targetReference();
        boolean standingReference = nativeGravity
                || requestedReference.isPresent() && requestedReference.orElseThrow() == frame;
        boolean freeDrift = sample.bindingActive() && !propulsion && !flying && !fluid && !gliding && !nativeGravity
                && standing.presence() == StandingConstraints.ConstraintPresence.ABSENT && !actor.isOnLadder();
        boolean nearFootLocomotion = false;
        Vec3 velocity = new Vec3(v.x(), v.y(), v.z());
        if (!propulsion && !freeDrift) velocity = new Vec3(
                Math.abs(velocity.x() * dt) < .003D ? 0 : velocity.x(),
                Math.abs(velocity.y() * dt) < .003D ? 0 : velocity.y(),
                Math.abs(velocity.z() * dt) < .003D ? 0 : velocity.z());
        if (propulsion) velocity = velocity.add(acceleration.scale(dt));
        float friction = 0.91F;
        double flightVerticalMotion = 0;
        double glideHorizontalSpeed = Math.hypot(v.x() * dt, v.z() * dt);
        if (travel.jumpTicks > 0) travel.jumpTicks--;
        if (!controls.jump()) travel.jumpTicks = 0;
        if (!propulsion) {
            if (freeDrift) {
                // Read-only sweep: its endpoint is never installed and cannot grant onGround or jump authority.
                var footRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -ZERO_G_FOOT_REACH - 1.0e-7, 0)), MoverType.PLAYER, false, false, 0);
                var foot = WorldCollisionCandidates.resolveCardinal(world, actor, accepted, footRequest, SCAN, MOVE);
                if (foot.worldFailure().isPresent()) {
                    var failure = foot.worldFailure().orElseThrow();
                    return new Unavailable("zero-G foot: " + failure.reason(), failure.cause());
                }
                if (!(foot.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return unavailable("zero-G foot calculation incomplete");
                boolean withinReach = contact.flags().onGround()
                        && -contact.nativeResolvedLocalDelta().y() <= ZERO_G_FOOT_REACH;
                nearFootLocomotion = withinReach;
                if (withinReach) {
                    Vec3d input = frame.toLocal(controls.translation());
                    Vec3d localVelocity = frame.toLocal(new Vec3d(velocity.x(), velocity.y(), velocity.z()));
                    Vec3 tangent = new Vec3(localVelocity.x(), 0, localVelocity.z());
                    boolean sprinting = controls.sprint() && !controls.sneak()
                            && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6);
                    double targetSpeed = (sprinting ? ZERO_G_SPRINT_SPEED : ZERO_G_WALK_SPEED)
                            * (controls.sneak() ? 0.3 : 1);
                    Vec3 desired = new Vec3(input.x(), 0, input.z()).scale(targetSpeed);
                    Vec3 drive = desired.sub(tangent);
                    double change = drive.norm();
                    // Contact-limited friction may brake toward the selected gait, never snap existing speed.
                    if (change > ZERO_G_WALK_ACCELERATION * dt) drive = drive.scale(ZERO_G_WALK_ACCELERATION * dt / change);
                    Vec3d worldDrive = frame.toWorld(new Vec3d(drive.x(), drive.y(), drive.z()));
                    velocity = velocity.add(new Vec3(worldDrive.x(), worldDrive.y(), worldDrive.z()));
                }
                // A retained native flag is not sustained support in a region with no standing constraint.
                actor.onGround = false;
            }
            if (actor.onGround && standingReference) {
                var supportRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, actor.onGround, false, 0);
                var support = WorldCollisionCandidates.resolveCardinal(world, actor, accepted, supportRequest, SCAN, MOVE);
                if (support.worldFailure().isPresent()) {
                    var failure = support.worldFailure().orElseThrow();
                    return new Unavailable("support: " + failure.reason(), failure.cause());
                }
                if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return unavailable("support calculation incomplete");
                actor.onGround = contact.flags().onGround();
            }
            if (actor.onGround && !fluid) {
                BlockPos support = frame == CardinalFrame.Y_POS
                        ? new BlockPos(actor.posX, actor.getEntityBoundingBox().minY - 1, actor.posZ)
                        : CardinalMoveFinalizer.physicalSupport(frame, actor.getEntityBoundingBox());
                var state = world.getBlockState(support);
                friction *= state.getBlock().getSlipperiness(state, world, support, actor);
            }
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            actor.setSneaking(controls.sneak());
            actor.setSprinting(controls.sprint() && !controls.sneak()
                    && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6));
            if (flying) {
                // Native SP input precedes the EntityPlayer flight wrapper; opposite vertical keys cancel.
                if (controls.sneak()) actor.motionY -= actor.capabilities.getFlySpeed() * 3.0F;
                if (controls.jump()) actor.motionY += actor.capabilities.getFlySpeed() * 3.0F;
                flightVerticalMotion = actor.motionY;
                actor.fallDistance = 0;
            }
            float fluidAcceleration = 0.02F;
            if (fluid) {
                friction = water ? actor.getWaterSlowDown() : 0.5F;
                if (water) {
                    float depth = Math.min(3, EnchantmentHelper.getDepthStriderModifier(actor));
                    if (!actor.onGround) depth *= 0.5F;
                    if (depth > 0) {
                        friction += (0.54600006F - friction) * depth / 3.0F;
                        fluidAcceleration += (actor.getAIMoveSpeed() - fluidAcceleration) * depth / 3.0F;
                    }
                }
                if (controls.jump()) {
                    if (water) actor.handleJumpWater();
                    else actor.handleJumpLava();
                }
            }
            if (!gliding && (standingReference || fluid || flying)) {
                if (!flying && !fluid && controls.jump() && actor.onGround && travel.jumpTicks == 0) {
                    travel.jumpTicks = 10;
                    if (frame == CardinalFrame.Y_POS) actor.jump();
                    else jumpCardinal(actor, accepted.placement(), controls);
                    Vec3d transport = accepted.placement().transport();
                    if (correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor)
                            || actor.world != world || actor.isDead || world.getTotalWorldTime() != tick
                            || actor.posX != transport.x() || actor.posY != transport.y() || actor.posZ != transport.z())
                        return unavailable("jump callback changed actor placement/lifetime");
                }
                float coefficient = fluid ? fluidAcceleration : actor.onGround ? actor.getAIMoveSpeed() * (0.16277136F / (friction * friction * friction))
                        : flying ? actor.capabilities.getFlySpeed() * (actor.isSprinting() ? 2 : 1) : actor.jumpMovementFactor;
                float yaw = actor.rotationYaw * 0.017453292F;
                float sin = MathHelper.sin(yaw), cos = MathHelper.cos(yaw);
                double keyboardTravelGain = controls.keyboardTravelGain();
                if (frame == CardinalFrame.Y_POS) {
                    float worldX = (float) (controls.translation().x() * keyboardTravelGain);
                    float worldZ = (float) (controls.translation().z() * keyboardTravelGain);
                    float strafe = worldX * cos + worldZ * sin;
                    float forward = worldZ * cos - worldX * sin;
                    if (controls.sneak() && !flying) {
                        strafe = (float) (strafe * 0.3D);
                        forward = (float) (forward * 0.3D);
                    }
                    strafe *= 0.98F;
                    forward *= 0.98F;
                    actor.moveRelative(strafe, 0, forward, coefficient);
                } else {
                    Vec3d input = frame.toLocal(controls.translation());
                    float right = (float) (input.x() * keyboardTravelGain);
                    float forward = (float) (input.z() * keyboardTravelGain);
                    if (controls.sneak() && !flying) {
                        right = (float) (right * 0.3D);
                        forward = (float) (forward * 0.3D);
                    }
                    right *= 0.98F;
                    forward *= 0.98F;
                    float lengthSquared = right * right + forward * forward;
                    if (lengthSquared >= 1.0e-4F) {
                        float scale = coefficient / Math.max(1, MathHelper.sqrt(lengthSquared));
                        Vec3d drive = frame.toWorld(new Vec3d(right * scale, 0, forward * scale));
                        actor.motionX += drive.x();
                        actor.motionY += drive.y();
                        actor.motionZ += drive.z();
                    }
                }
            }
            if (!gliding && !fluid && actor.isOnLadder()) {
                actor.motionX = MathHelper.clamp(actor.motionX, -(double) 0.15F, (double) 0.15F);
                actor.motionZ = MathHelper.clamp(actor.motionZ, -(double) 0.15F, (double) 0.15F);
                actor.motionY = Math.max(actor.motionY, -0.15D);
                actor.fallDistance = 0;
                if (actor.isSneaking() && actor.motionY < 0) actor.motionY = 0;
            }
            velocity = new Vec3(actor.motionX / dt, actor.motionY / dt, actor.motionZ / dt);
            if (gliding) velocity = glide(actor, velocity, acceleration, dt);
        }
        if (propulsion) {
            double driftAcceleration = UnigravConfig.sixDofDriftAcceleration;
            double driftLimit = UnigravConfig.sixDofDriftSpeedLimit;
            if (!Double.isFinite(driftAcceleration) || driftAcceleration < 0 || !Double.isFinite(driftLimit) || driftLimit < 0)
                throw new IllegalArgumentException("Invalid drift tuning");
            Vec3d translation = controls.translation();
            Vec3 driftDelta = new Vec3(translation.x(), translation.y(), translation.z()).scale(driftAcceleration * dt);
            velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, driftDelta, driftLimit));
            if (controls.boost()) {
                double target = RemoteBoostCorridor.propulsionTarget(BoostGearProfile.speedMps(controls.boostGear()),
                        budget.ceilingMps(), RemoteBoostCorridor.isRestricted(actor.posY));
                Vec3d forward = controls.forward();
                Vec3 boostDelta = new Vec3(forward.x(), forward.y(), forward.z())
                        .scale(BoostGearProfile.accelerationMps2(controls.boostGear()) * dt);
                // A zero carrier is no permission to accelerate, unlike the configurable unlimited drift setting.
                if (target > 0) velocity = velocity.add(MotionSpeedLimit.deltaVelocity(velocity, boostDelta, target));
            }
        }
        double speed = Math.hypot(Math.hypot(velocity.x(), velocity.y()), velocity.z());
        if (!Double.isFinite(speed)) return unavailable("non-finite integrated velocity");
        if (speed > budget.ceilingMps()) velocity = velocity.scale(budget.ceilingMps() / speed);
        Vec3d start = accepted.placement().transport();
        var admission = budget.prepareClipped(Math.incrementExact(budget.lastSequence()), budgetTick,
                new Vec3(start.x(), start.y(), start.z()).add(velocity.scale(dt)));
        if (!admission.accepted()) return unavailable("budget: " + admission.failure());
        var boundary = new CommitBoundary(actor, accepted, budget, admission, travel, correctionRevision);
        try {
            Vec3 delta = admission.destination().sub(admission.start());
            var request = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                    new Vec3d(delta.x(), delta.y(), delta.z()), MoverType.PLAYER,
                    actor.onGround, actor.isSneaking(), actor.stepHeight);
            var observation = WorldCollisionCandidates.resolveCardinal(world, actor, accepted, request, SCAN, MOVE);
            if (observation.worldFailure().isPresent()) {
                var failure = observation.worldFailure().orElseThrow();
                return new Unavailable("collision world: " + failure.reason(), failure.cause());
            }
            if (!(observation.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved resolved))
                return unavailable("collision calculation incomplete");
            // The finalizer's native block callbacks must see this step's velocity, not stale MP motion.
            Vec3 oldMotion = new Vec3(actor.motionX, actor.motionY, actor.motionZ);
            actor.motionX = velocity.x() * dt;
            actor.motionY = velocity.y() * dt;
            actor.motionZ = velocity.z() * dt;
            var preparation = CardinalMoveFinalizer.preparePhysical(actor, request, resolved);
            if (!(preparation instanceof CardinalMoveFinalizer.Ready ready)) {
                actor.motionX = oldMotion.x(); actor.motionY = oldMotion.y(); actor.motionZ = oldMotion.z();
                return unavailable("movement tail unavailable");
            }
            boundary.finalizer = ready;
            if (!(ready.commit() instanceof CardinalMoveFinalizer.Applied applied))
                return unavailable("movement tail invalidated");
            if (correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor))
                return boundary.defer(unavailable("movement authority changed during movement tail"));
            if (actor.world != world || actor.isDead || world.getTotalWorldTime() != tick
                    || actor.connection == null || actor.connection.player != actor
                    || actor.dimension != accepted.scope().dimension()) return boundary.defer(unavailable("movement tail changed lifetime"));
            if (actor.width != dimensions.width() || actor.height != dimensions.height())
                return boundary.defer(unavailable("movement tail changed physical dimensions"));
            Vec3d position = applied.actualTransport();
            if (!budget.commitNative(admission, new Vec3(position.x(), position.y(), position.z())))
                throw new IllegalStateException("Physical movement lost its reserved budget ticket");
            boundary.budgetCommitted = true;
            if (boostCarrier) applyCorridor(budget, budgetTick, position.y());
            if (!actor.onGround && sample.bindingActive() && standingReference
                    && !propulsion && !flying && !fluid && !gliding && frame.toLocal(request.worldDelta()).y() == 0) {
                // A tangent-only move has no vertical collision flag. Re-observe its ACTUAL endpoint;
                // never inherit pre-move support across an edge or treat force cancellation as free flight.
                var installed = committedSnapshot(actor, accepted, applied.actualBounds(), position, false);
                var supportRequest = new CardinalMovementResolver.Request(frame, actor.getEntityBoundingBox(),
                        frame.toWorld(new Vec3d(0, -1.0e-4, 0)), MoverType.PLAYER, false, false, 0);
                var support = WorldCollisionCandidates.resolveCardinal(world, actor, installed, supportRequest, SCAN, MOVE);
                if (support.worldFailure().isPresent()) {
                    var failure = support.worldFailure().orElseThrow();
                    return boundary.defer(new Unavailable("endpoint support: " + failure.reason(), failure.cause()));
                }
                if (!(support.calculation().orElse(null) instanceof CardinalMovementResolver.Resolved contact))
                    return boundary.defer(unavailable("endpoint support calculation incomplete"));
                if (correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor))
                    return boundary.defer(unavailable("endpoint support changed movement authority"));
                // The sweep is a sensor only. A nonzero gap is not contact and its endpoint is never installed.
                actor.onGround = contact.flags().onGround() && contact.nativeResolvedLocalDelta().y() == 0;
            }
            boundary.consumingLanding = actor.onGround;
            if (frame == CardinalFrame.Y_POS) actor.handleFalling(position.y() - start.y(), actor.onGround);
            else {
                double fallStep = resolved.nativeResolvedLocalDelta().y();
                if (actor.onGround) {
                    if (actor.fallDistance > 0) {
                        Vec3d foot = accepted.placement().physicalFeet().orElseThrow()
                                .add(position.subtract(start)).subtract(frame.up().multiply(.2));
                        BlockPos support = new BlockPos(foot.x(), foot.y(), foot.z());
                        world.getBlockState(support).getBlock().onFallenUpon(world, support, actor, actor.fallDistance);
                        actor.fallDistance = 0;
                    }
                } else if (fallStep < 0) actor.fallDistance -= (float) fallStep;
            }
            if (correctionRevision != travel.nativeCorrectionRevision || !actor.isEntityAlive()
                    || PlayerKinematics.requiresNative(actor) || actor.world != world
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("fall callback changed physical authority"));
            var previousContacts = accepted.contacts();
            Vec3d impactVelocity = frame.toLocal(new Vec3d(velocity.x(), velocity.y(), velocity.z()));
            if (!previousContacts.map(PlayerPhysicalSnapshot.Contacts::onGround).orElse(false)
                    && actor.onGround && impactVelocity.y() < 0 && !actor.isInWater())
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_FALL, -impactVelocity.y());
            else if (actor.collidedVertically && impactVelocity.y() > 0)
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_CEILING, impactVelocity.y());
            if (!gliding && actor.collidedHorizontally
                    && !previousContacts.map(PlayerPhysicalSnapshot.Contacts::horizontal).orElse(false))
                ImpactDamageHandler.applyAuthoritativePlayerImpact(actor, ImpactReportMessage.TYPE_WALL,
                        Math.hypot(impactVelocity.x(), impactVelocity.z()));
            if (correctionRevision != travel.nativeCorrectionRevision || !actor.isEntityAlive()
                    || PlayerKinematics.requiresNative(actor) || actor.world != world
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("impact callback changed physical authority"));
            Vec3d actualVelocity = frame.toLocal(applied.observedMotionBlocksPerTick());
            Vec3d localAcceleration = frame.toLocal(new Vec3d(acceleration.x(), acceleration.y(), acceleration.z()));
            boolean drift = freeDrift || sample.personalZeroG() && actor.capabilities.isCreativeMode;
            double horizontalDrag = drift || gliding ? 1 : friction;
            double verticalDrag = drift || gliding ? 1 : water ? (double) 0.8F : lava ? 0.5D : 0.9800000190734863D;
            // Ordinary travel applies environmental acceleration after the one native movement, before drag.
            double forceScale = propulsion || gliding ? 0 : dt * dt;
            double verticalMotion = !propulsion && !gliding && !fluid && actor.collidedHorizontally && actor.isOnLadder()
                    ? 0.2D : actualVelocity.y();
            // Fluid travel damps first; acceleration already contains UG buoyancy and the one eligible native term.
            Vec3d postDrag = frame.toWorld(new Vec3d(
                    actualVelocity.x() * horizontalDrag + localAcceleration.x() * forceScale * (fluid ? 1 : horizontalDrag),
                    verticalMotion * verticalDrag + localAcceleration.y() * forceScale * (fluid ? 1 : verticalDrag),
                    actualVelocity.z() * horizontalDrag + localAcceleration.z() * forceScale * (fluid ? 1 : horizontalDrag)));
            actor.motionX = postDrag.x();
            actor.motionY = postDrag.y();
            actor.motionZ = postDrag.z();
            // EntityPlayer.travel restores the pre-move vertical flight velocity even after a collision.
            if (flying) actor.motionY = flightVerticalMotion * 0.6D;
            if (gliding) {
                if (actor.collidedHorizontally) {
                    float damage = (float) ((glideHorizontalSpeed - Math.hypot(actor.motionX, actor.motionZ)) * 10.0D - 3.0D);
                    if (damage > 0) {
                        actor.playSound(actor.getFallSound((int) damage), 1.0F, 1.0F);
                        actor.attackEntityFrom(DamageSource.FLY_INTO_WALL, damage);
                    }
                }
                if (actor.onGround) actor.clearElytraFlying();
            }
            if (flying) {
                actor.fallDistance = 0;
                actor.clearElytraFlying();
            }
            if (fluid && actor.collidedHorizontally) {
                var escapeBox = actor.getEntityBoundingBox().offset(actor.motionX,
                        actor.motionY + (double) 0.6F - actor.posY + start.y(), actor.motionZ);
                var escape = WorldCollisionCandidates.capture(world, actor, new Aabb(
                                new Vec3d(escapeBox.minX, escapeBox.minY, escapeBox.minZ),
                                new Vec3d(escapeBox.maxX, escapeBox.maxY, escapeBox.maxZ)),
                        CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, SCAN);
                if (escape instanceof WorldCollisionCandidates.Unavailable failure)
                    return boundary.defer(new Unavailable("fluid escape: " + failure.reason(), failure.cause()));
                // The bounded capture has checked all columns before the native material scan.
                if (((WorldCollisionCandidates.Snapshot) escape).boxes().isEmpty() && !world.containsAnyLiquid(escapeBox))
                    actor.motionY = (double) 0.3F;
            }
            double nativeSpeed = Math.hypot(Math.hypot(actor.motionX, actor.motionY), actor.motionZ);
            if (!Double.isFinite(nativeSpeed)) throw new IllegalStateException("Non-finite movement-tail velocity");
            double nativeLimit = budget.ceilingMps() * dt;
            if (nativeSpeed > nativeLimit) {
                double scale = nativeLimit / nativeSpeed;
                actor.motionX *= scale;
                actor.motionY *= scale;
                actor.motionZ *= scale;
            }
            actor.addMovementStat(position.x() - start.x(), position.y() - start.y(), position.z() - start.z());
            if (correctionRevision != travel.nativeCorrectionRevision || PlayerKinematics.requiresNative(actor)
                    || actor.world != world || actor.isDead
                    || actor.posX != position.x() || actor.posY != position.y() || actor.posZ != position.z()
                    || !actor.getEntityBoundingBox().equals(applied.actualBounds()))
                return boundary.defer(unavailable("movement statistics changed physical authority"));
            return new Applied(committedSnapshot(actor, accepted, applied.actualBounds(), applied.actualTransport(), nearFootLocomotion));
        } catch (RuntimeException failure) {
            try {
                var recovered = boundary.recover();
                if (recovered.isPresent()) return new CommittedFailure(recovered.orElseThrow(), failure);
            } catch (RuntimeException invalidSnapshot) {
                if (invalidSnapshot != failure) failure.addSuppressed(invalidSnapshot);
            }
            throw failure;
        } finally {
            // A consumed ticket is a no-op here; a failed query/tail retires it without refunding credit.
            budget.abort(admission);
        }
    }

    private static PlayerPhysicalSnapshot committedSnapshot(EntityPlayerMP actor,
            PlayerPhysicalSnapshot accepted, AxisAlignedBB box, Vec3d position, boolean nearFootLocomotion) {
        var oldShape = (PlayerBodyShape.Cardinal) accepted.placement().shape();
        // Preserve the actual collider and the current native eye profile, including sneak.
        var shape = new PlayerBodyShape.Cardinal(oldShape.frame(), new PlayerBodyShape.Dimensions(
                actor.width, actor.height, actor.getEyeHeight()));
        var placement = PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(box.minX, box.minY, box.minZ),
                new Vec3d(box.maxX, box.maxY, box.maxZ)), position, accepted.placement().attitude(), shape);
        double dt = GravityEntityBinder.TICK_SECONDS;
        return new PlayerPhysicalSnapshot(accepted.scope(), Math.incrementExact(accepted.revision()),
                placement, new Vec3d(actor.motionX / dt, actor.motionY / dt, actor.motionZ / dt),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                        actor.collidedVertically, actor.fallDistance, nearFootLocomotion)));
    }

    /** One admission's geometry/debit journal, independent of whether native callbacks return. */
    private static final class CommitBoundary {
        private final EntityPlayerMP actor;
        private final PlayerPhysicalSnapshot accepted;
        private final BoostMovementBudget budget;
        private final BoostMovementBudget.Admission admission;
        private final TravelState travel;
        private final long correctionRevision;
        private final World world;
        private final NetHandlerPlayServer connection;
        private final long tick;
        private CardinalMoveFinalizer.Ready finalizer;
        private boolean budgetCommitted;
        private boolean consumingLanding;

        private CommitBoundary(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, BoostMovementBudget budget,
                BoostMovementBudget.Admission admission, TravelState travel, long correctionRevision) {
            this.actor = actor;
            this.accepted = accepted;
            this.budget = budget;
            this.admission = admission;
            this.travel = travel;
            this.correctionRevision = correctionRevision;
            world = actor.world;
            connection = actor.connection;
            tick = world.getTotalWorldTime();
        }

        private Result defer(Unavailable failure) {
            var recovered = recover();
            return recovered.<Result>map(snapshot -> new CommittedDeferred(snapshot, failure)).orElse(failure);
        }

        private Optional<PlayerPhysicalSnapshot> recover() {
            var installation = finalizer == null ? Optional.<CardinalMoveFinalizer.Installation>empty()
                    : finalizer.installation();
            if (installation.isEmpty()) return Optional.empty();
            var installed = installation.orElseThrow();
            var dimensions = accepted.placement().shape().dimensions();
            // A record of geometry installation is not authority to adopt a callback's relocation.
            if (correctionRevision != travel.nativeCorrectionRevision || actor.world != world
                    || world.getTotalWorldTime() != tick || !actor.isEntityAlive()
                    || actor.connection != connection || connection == null || connection.player != actor
                    || actor.dimension != accepted.scope().dimension() || PlayerKinematics.requiresNative(actor)
                    || actor.width != dimensions.width() || actor.height != dimensions.height()
                    || actor.posX != installed.transport().x() || actor.posY != installed.transport().y()
                    || actor.posZ != installed.transport().z() || !actor.getEntityBoundingBox().equals(installed.bounds()))
                return Optional.empty();
            double speed = Math.hypot(Math.hypot(actor.motionX, actor.motionY), actor.motionZ);
            if (!Double.isFinite(speed)) return Optional.empty();
            if (!budgetCommitted) {
                var position = installed.transport();
                if (!budget.commitNative(admission, new Vec3(position.x(), position.y(), position.z())))
                    throw new IllegalStateException("Installed movement lost its reserved budget ticket");
                budgetCommitted = true;
            }
            double limit = budget.ceilingMps() * GravityEntityBinder.TICK_SECONDS;
            if (speed > limit) {
                double scale = limit / speed;
                actor.motionX *= scale; actor.motionY *= scale; actor.motionZ *= scale;
            }
            // This landing was attempted; never retry potentially irreversible damage callbacks.
            if (consumingLanding) actor.fallDistance = 0;
            return Optional.of(committedSnapshot(actor, accepted, installed.bounds(), installed.transport(), false));
        }
    }

    private static Unavailable unavailable(String reason) {
        return new Unavailable(reason, Optional.empty());
    }

    /** Native jump policy in the accepted physical frame; callbacks always observe WORLD motion. */
    private static void jumpCardinal(EntityPlayerMP actor, PlayerBodyPlacement placement,
                                     PlayerPhysicsProtocol.Controls controls) {
        CardinalFrame frame = ((PlayerBodyShape.Cardinal) placement.shape()).frame();
        Vec3d local = frame.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
        double launch = actor.getJumpUpwardsMotion();
        if (actor.isPotionActive(MobEffects.JUMP_BOOST))
            launch += (actor.getActivePotionEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1) * .1F;
        Vec3d motion = frame.toWorld(new Vec3d(local.x(), launch, local.z()));
        if (actor.isSprinting()) {
            Vec3d look = frame.toLocal(controls.forward());
            double length = Math.hypot(look.x(), look.z());
            Vec3d forward = length > 1.0e-7
                    ? frame.toWorld(new Vec3d(look.x() / length, 0, look.z() / length))
                    : placement.attitude().basisZ();
            motion = motion.add(forward.multiply((double) .2F));
        }
        actor.motionX = motion.x();
        actor.motionY = motion.y();
        actor.motionZ = motion.z();
        actor.isAirBorne = true;
        ForgeHooks.onLivingJump(actor);
        actor.addStat(StatList.JUMP);
        actor.addExhaustion(actor.isSprinting() ? .2F : .05F);
    }

    /** Native elytra aerodynamics in blocks/tick, with the one sampled environment replacing fixed -0.08. */
    private static Vec3 glide(EntityPlayerMP actor, Vec3 velocity, Vec3 acceleration, double dt) {
        double x = velocity.x() * dt, y = velocity.y() * dt, z = velocity.z() * dt;
        if (y > -0.5D) actor.fallDistance = 1.0F;
        var look = actor.getLookVec();
        float pitch = actor.rotationPitch * (float) (Math.PI / 180.0D);
        double lookHorizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double horizontal = Math.sqrt(x * x + z * z);
        float lift = MathHelper.cos(pitch);
        lift = (float) (lift * (lift * Math.min(1.0D, look.length() / 0.4D)));
        x += acceleration.x() * dt * dt;
        y += acceleration.y() * dt * dt + lift * 0.06D;
        z += acceleration.z() * dt * dt;
        if (y < 0 && lookHorizontal > 0) {
            double dive = y * -0.1D * lift;
            y += dive;
            x += look.x * dive / lookHorizontal;
            z += look.z * dive / lookHorizontal;
        }
        if (pitch < 0 && lookHorizontal > 0) {
            double climb = horizontal * -MathHelper.sin(pitch) * 0.04D;
            y += climb * 3.2D;
            x -= look.x * climb / lookHorizontal;
            z -= look.z * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (look.x / lookHorizontal * horizontal - x) * 0.1D;
            z += (look.z / lookHorizontal * horizontal - z) * 0.1D;
        }
        return new Vec3(x * (double) 0.99F / dt, y * (double) 0.98F / dt, z * (double) 0.99F / dt);
    }

    /** Preserve the transferred envelope and its existing credit; G-off does not erase reentry decay. */
    private static void applyCorridor(BoostMovementBudget budget, long tick, double feetY) {
        if (RemoteBoostCorridor.isRestricted(feetY)) {
            if (budget.ceilingMps() > RemoteBoostCorridor.RESTRICTED_CEILING_MPS && !budget.snapshot().decayActive())
                budget.beginDecay(tick, RemoteBoostCorridor.RESTRICTED_CEILING_MPS,
                        RemoteBoostCorridor.REENTRY_DECELERATION_MPS2);
        } else {
            double configured = UnigravConfig.remoteBoostMaximumSpeed;
            double safety = UnigravConfig.maxEntitySpeed;
            if (!Double.isFinite(configured) || configured < 0 || !Double.isFinite(safety) || safety < 0)
                throw new IllegalArgumentException("Invalid propulsion carrier configuration");
            double grant = Math.min(BoostGearProfile.maximumSpeedMps(), configured);
            if (safety > 0) grant = Math.min(grant, safety);
            if (budget.ceilingMps() != grant || budget.snapshot().decayActive()) budget.changeCeiling(tick, grant);
        }
    }
}
