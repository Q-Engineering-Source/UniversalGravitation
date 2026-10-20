package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.compat.EquipmentFlightSampling;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMoveFinalizer;
import wi.unigrav.core.player.CardinalPredictionSeed;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerBoostEligibility;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import java.util.Objects;
import java.util.Optional;

import static wi.unigrav.core.player.CardinalPredictionSeed.Reason;

/** Samples one new local slot. Replay retains the returned values and never reruns this adapter. */
public final class CardinalPredictionEnvironmentSampler {
    private CardinalPredictionEnvironmentSampler() { }
    public sealed interface Result permits Sampled, Unsupported, Unavailable { }
    public record Sampled(CardinalPredictionStep.Environment environment) implements Result { }
    public record Unsupported(Reason reason, String support, PlayerPhysicsProtocol.PredictionRefusalStage stage) implements Result {
        public Unsupported(Reason reason) { this(reason,"not-applicable",PlayerPhysicsProtocol.PredictionRefusalStage.SAMPLE); }
        public Unsupported(Reason reason,String support) { this(reason,support,PlayerPhysicsProtocol.PredictionRefusalStage.PREFRICTION); }
    }
    public record Unavailable(Reason reason, Optional<RuntimeException> cause, String support, PlayerPhysicsProtocol.PredictionRefusalStage stage) implements Result {
        public Unavailable(Reason reason, Optional<RuntimeException> cause) { this(reason,cause,"NOT_OBSERVED",PlayerPhysicsProtocol.PredictionRefusalStage.SAMPLE); }
        public Unavailable(Reason reason,Optional<RuntimeException> cause,String support) { this(reason,cause,support,PlayerPhysicsProtocol.PredictionRefusalStage.PREFRICTION); }
    }
    public sealed interface SurfaceResult permits NormalSurface, UnsupportedSurface, UnavailableSurface { }
    public record NormalSurface(BlockPos position, IBlockState state) implements SurfaceResult { }
    public record UnsupportedSurface(Reason reason, String support) implements SurfaceResult {
        public UnsupportedSurface(Reason reason) { this(reason,"NOT_OBSERVED"); }
    }
    public record UnavailableSurface(Reason reason, Optional<RuntimeException> cause, String support) implements SurfaceResult {
        public UnavailableSurface(Reason reason,Optional<RuntimeException> cause) { this(reason,cause,"NOT_OBSERVED"); }
    }

    public static Result sample(EntityPlayerSP actor, World world, CardinalPredictionStep.State state,
                                PlayerPhysicsProtocol.Controls controls, CardinalPredictionSeed.Ready seed,
                                boolean currentPersonalZeroG) {
        return samplePlayer(actor, world, state, controls, seed, currentPersonalZeroG);
    }

    /** Shared real-entity adapter, also usable by the existing native World fixture. */
    public static Result samplePlayer(EntityPlayer actor, World world, CardinalPredictionStep.State state,
                                      PlayerPhysicsProtocol.Controls controls, CardinalPredictionSeed.Ready seed,
                                      boolean currentPersonalZeroG) {
        Objects.requireNonNull(actor); Objects.requireNonNull(world); Objects.requireNonNull(state);
        Objects.requireNonNull(controls); Objects.requireNonNull(seed);
        try {
            if (actor.world != world || actor.isDead) return new Unavailable(Reason.ACTOR_CHANGED, Optional.empty());
            if (!(state.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                    || cardinal.frame() != seed.frame()) return new Unsupported(Reason.UNSUPPORTED_SHAPE);
            if (currentPersonalZeroG != seed.personalZeroG()) return new Unsupported(Reason.PERSONAL_G_MISMATCH,
                    "sampledPersonalZeroG=" + currentPersonalZeroG + ",seedPersonalZeroG=" + seed.personalZeroG()
                            + ",seedSnapshotRevision=" + seed.snapshotRevision() + ",seedWorldTick=" + seed.worldTick(),
                    PlayerPhysicsProtocol.PredictionRefusalStage.SAMPLE);
            // Ready is issued only after the server excludes qualified magnetic boots and
            // their active contact memory. Ordinary armor in FEET is not a magnetic boot.
            var bounds = box(state.placement().bounds());
            if (!world.isAreaLoaded(new BlockPos(bounds.minX, bounds.minY, bounds.minZ),
                    new BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ)))
                return new Unavailable(Reason.WORLD_UNAVAILABLE, Optional.empty());
            if (actor.noClip || actor.isRiding() || actor.isSpectator() || actor.isInWater() || actor.isInLava()
                    || actor.isElytraFlying() || actor.isOnLadder()
                    || PlayerBoostEligibility.propulsionAllowed(currentPersonalZeroG, actor.capabilities.isCreativeMode))
                return new Unsupported(Reason.UNSUPPORTED_STATE);
            boolean flying = actor.capabilities.isFlying;
            float friction = .91F;
            if (state.contacts().onGround()) {
                BlockPos position = cardinal.frame() == CardinalFrame.Y_POS
                        ? new BlockPos(bounds.minX + (bounds.maxX - bounds.minX) * .5,
                                bounds.minY - 1, bounds.minZ + (bounds.maxZ - bounds.minZ) * .5)
                        : CardinalMoveFinalizer.physicalSupport(cardinal.frame(), bounds);
                var surface = surface(world, position);
                if (surface instanceof UnavailableSurface failure) return new Unavailable(failure.reason(), failure.cause(),failure.support());
                if (surface instanceof UnsupportedSurface failure) return new Unsupported(failure.reason(),failure.support());
                IBlockState block = ((NormalSurface) surface).state();
                friction *= block.getBlock().getSlipperiness(block, world, position, actor);
            }
            boolean sprint = controls.sprint() && !controls.sneak()
                    && (actor.capabilities.isCreativeMode || actor.getFoodStats().getFoodLevel() > 6);
            actor.setSprinting(sprint);
            var equipmentFlight = EquipmentFlightSampling.sample(actor, controls);
            float aiMoveSpeed = actor.getAIMoveSpeed();
            double launch = actor.getJumpUpwardsMotion();
            if (actor.isPotionActive(MobEffects.JUMP_BOOST))
                launch += (actor.getActivePotionEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1) * .1F;
            Vec3d impulse;
            if (cardinal.frame() == CardinalFrame.Y_POS) {
                float yaw = actor.rotationYaw * (float) (Math.PI / 180.0D);
                impulse = new Vec3d(-MathHelper.sin(yaw) * .2F, 0, MathHelper.cos(yaw) * .2F);
            } else {
                Vec3d look = cardinal.frame().toLocal(controls.forward());
                double length = Math.hypot(look.x(), look.z());
                Vec3d forward = length > 1.0e-7
                        ? cardinal.frame().toWorld(new Vec3d(look.x() / length, 0, look.z() / length))
                        : state.placement().attitude().basisZ();
                impulse = forward.multiply((double) .2F);
            }
            Vec3d acceleration = seed.environmentAcceleration();
            if (!seed.personalZeroG() && !seed.savedNativeNoGravity()
                    && seed.bindingMode().filter(mode -> mode == GravityMode.REPLACE).isEmpty() && !flying)
                acceleration = acceleration.add(new Vec3d(0, -GravityEntityBinder.VANILLA_PLAYER_GRAVITY, 0));
            if (actor.world != world || actor.isDead) return new Unavailable(Reason.ACTOR_CHANGED, Optional.empty());
            return new Sampled(new CardinalPredictionStep.Environment(acceleration, friction, aiMoveSpeed,
                    actor.jumpMovementFactor, flying, equipmentFlight.flySpeed(), launch, impulse,
                    actor.stepHeight, seed.ceilingMps(), seed.standingReference(), sprint, actor.rotationYaw,
                    seed.bindingActive(), equipmentFlight));
        } catch (RuntimeException cause) {
            return new Unavailable(Reason.SAMPLING_FAILED, Optional.of(cause));
        }
    }

    /** Check each resolved grounded endpoint before installing a prediction; old support is insufficient. */
    public static SurfaceResult checkEndpoint(World world, CardinalPredictionStep.State state) {
        Objects.requireNonNull(world); Objects.requireNonNull(state);
        if (!(state.placement().shape() instanceof PlayerBodyShape.Cardinal shape))
            return new UnsupportedSurface(Reason.UNSUPPORTED_SHAPE);
        return surface(world, CardinalMoveFinalizer.physicalSupport(shape.frame(), box(state.placement().bounds())));
    }

    private static SurfaceResult surface(World world, BlockPos position) {
        try {
            if (!world.isBlockLoaded(position)) return new UnavailableSurface(Reason.WORLD_UNAVAILABLE, Optional.empty(),position.toImmutable()+" state=NOT_OBSERVED");
            IBlockState state = world.getBlockState(position);
            return new NormalSurface(position.toImmutable(), state);
        } catch (RuntimeException cause) {
            return new UnavailableSurface(Reason.SAMPLING_FAILED, Optional.of(cause));
        }
    }

    private static AxisAlignedBB box(Aabb bounds) {
        return new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
    }
}
