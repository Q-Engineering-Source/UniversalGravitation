package wi.unigrav.core.player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyAccelerationStep;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.player.StandingConstraints.ConstraintSource;
import wi.unigrav.core.player.StandingConstraints.Evidence;
import wi.unigrav.core.player.StandingConstraints.FaceEvidence;
import wi.unigrav.core.player.WorldCollisionCandidates.ActorGeometry;

/**
 * Server-thread UG field/floor input to standing, in m/s². This neither moves the
 * player nor authenticates Personal G, physics lifetime, support or active boots.
 * The owner must append current boot/support observations before resolving the
 * complete environment. {@link Sample#environmentAcceleration()} composes the
 * currently sampled binder inputs only; it is not vanilla travel, drag, a
 * support proof, or capsule permission.
 */
public final class PlayerEnvironmentSampler {
    private final GravityEntityBinder binder;
    private final MagneticResponseRules materials;
    private final double naturalGravityThreshold;

    public enum Reason {
        PERSONAL_G_UNKNOWN, ACTOR_CHANGED, BINDING_CHANGED, WORLD_UNAVAILABLE,
        UNSUPPORTED_STATE, NATIVE_GRAVITY_UNRESOLVED, SOURCE_FAILED
    }
    public sealed interface Result permits Sample, Unavailable { }
    public record Unavailable(Reason reason, Optional<RuntimeException> cause) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cause, "cause");
        }
    }
    /**
     * Same-tick binder environment observation. {@code environmentAcceleration} already contains
     * background/floor gravity, passive magnetic response, persistent {@code F/m}, and applicable
     * water buoyancy. A future owner must not add
     * {@link StandingConstraints.ForceSample#combinedAcceleration()} as a whole again, because its
     * gravity and magnetic components would double-count this observation. Active boots and support
     * remain absent here and must be independently qualified and added by that owner.
     */
    public record Sample(PlayerPhysicalSnapshot physical, long worldTick, Evidence evidence,
                          Optional<GravityMode> bindingMode, boolean bindingActive, boolean personalZeroG,
                          double magneticResponse, Vec3 environmentAcceleration) implements Result {
        public Sample {
            Objects.requireNonNull(environmentAcceleration, "environmentAcceleration");
            requireFinite(environmentAcceleration, "environment acceleration");
        }
    }

    public PlayerEnvironmentSampler(GravityEntityBinder binder, MagneticResponseRules materials,
                                    double naturalGravityThreshold) {
        this.binder = Objects.requireNonNull(binder, "binder");
        this.materials = Objects.requireNonNull(materials, "materials");
        if (!Double.isFinite(naturalGravityThreshold) || naturalGravityThreshold < 0) {
            throw new IllegalArgumentException("natural gravity threshold must be finite and nonnegative");
        }
        this.naturalGravityThreshold = naturalGravityThreshold;
    }

    /**
     * The physics owner must authenticate supplied intent and accepted state in
     * its own lifetime. Empty intent is never OFF. Callback observations are
     * rechecked, not rolled back; tick/identity checks are not a world revision.
     */
    public Result sample(EntityPlayerMP player, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(personalZeroG, "personalZeroG");
        if (!(player.world instanceof WorldServer world)) return unavailable(Reason.WORLD_UNAVAILABLE);
        if (!world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("player environment sampling requires the server thread");
        }
        if (personalZeroG.isEmpty()) return unavailable(Reason.PERSONAL_G_UNKNOWN);
        if (unsupported(player)) return unavailable(Reason.UNSUPPORTED_STATE);
        try {
            var before = Observation.read(player, world);
            boolean swims = BodyPhysics.swims(player);
            double waterSinkBias = UnigravConfig.waterSinkBias;
            if (!before.matches(accepted) || player.connection == null || player.connection.player != player
                    || world.provider.getDimension() != player.dimension) return unavailable(Reason.ACTOR_CHANGED);
            boolean zeroG = personalZeroG.orElseThrow();
            // This is only a native suppression check, never a substitute for G intent.
            if (!before.noGravity() && !before.flying()) {
                return unavailable(Reason.NATIVE_GRAVITY_UNRESOLVED);
            }
            var binding = binder.sampleFieldBinding(player);
            if (!binding.isCurrent()) return unavailable(Reason.BINDING_CHANGED);
            boolean gravityApplies = binding.active() && !zeroG;
            double response = materials.playerResponse(before.boots(), before.creative(), zeroG);
            var position = before.geometry().position();
            Vec3 ambient = gravityApplies ? EntityGravitySources.fieldAcceleration(player,
                    new Vec3(position.x(), position.y(), position.z()), before.tick() * GravityEntityBinder.TICK_SECONDS)
                    : Vec3.ZERO;
            var center = before.geometry().bounds().center();
            var floors = FloorWorldFields.sampleForStanding(world,
                    new Vec3(center.x(), center.y(), center.z()), response);
            if (floors.isEmpty()) return unavailable(Reason.WORLD_UNAVAILABLE);
            Evidence evidence = new Evidence(StandingConstraints.Availability.AVAILABLE, ambient, Vec3.ZERO,
                    naturalReference(ambient), List.of(), Optional.empty()).withFloorSample(floors, gravityApplies);
            Vec3 environmentAcceleration = environmentAcceleration(binding, evidence, before.inWater(), swims,
                    waterSinkBias);
            if (!binding.isCurrent()) return unavailable(Reason.BINDING_CHANGED);
            if (player.world != world || !before.sameAs(Observation.read(player, world))
                    || player.connection.player != player || unsupported(player)
                    || world.captureBlockSnapshots || world.restoringBlockSnapshots) {
                return unavailable(Reason.ACTOR_CHANGED);
            }
            if (swims != BodyPhysics.swims(player) || Double.doubleToLongBits(waterSinkBias)
                    != Double.doubleToLongBits(UnigravConfig.waterSinkBias)) {
                return unavailable(Reason.SOURCE_FAILED);
            }
            return new Sample(accepted, before.tick(), evidence, binding.mode(), binding.active(), zeroG,
                    response, environmentAcceleration);
        } catch (RuntimeException cause) {
            // This boundary owns failures from arbitrary field/material/filter callbacks.
            return new Unavailable(Reason.SOURCE_FAILED, Optional.of(cause));
        }
    }

    /** Quantizes only the reference; never projects or clamps the physical acceleration. */
    private Map<CardinalFrame, FaceEvidence> naturalReference(Vec3 acceleration) {
        double magnitude = Math.hypot(Math.hypot(acceleration.x(), acceleration.y()), acceleration.z());
        if (!Double.isFinite(magnitude)) throw new IllegalArgumentException("non-finite background acceleration");
        if (magnitude <= naturalGravityThreshold) return Map.of();
        CardinalFrame frame = acceleration.y() < 0 ? CardinalFrame.Y_POS : CardinalFrame.Y_NEG;
        double strongest = Math.abs(acceleration.y());
        if (Math.abs(acceleration.x()) > strongest) {
            frame = acceleration.x() < 0 ? CardinalFrame.X_POS : CardinalFrame.X_NEG;
            strongest = Math.abs(acceleration.x());
        }
        if (Math.abs(acceleration.z()) > strongest) {
            frame = acceleration.z() < 0 ? CardinalFrame.Z_POS : CardinalFrame.Z_NEG;
        }
        return Map.of(frame, new FaceEvidence(magnitude, Set.of(ConstraintSource.NATURAL_BACKGROUND)));
    }

    /** Composes only the binder-owned inputs sampled above; it never writes Entity motion. */
    private static Vec3 environmentAcceleration(GravityEntityBinder.FieldBindingSample binding, Evidence evidence,
                                                boolean inWater, boolean swims, double waterSinkBias) {
        if (!binding.active()) {
            Vec3 passive = evidence.gravityAcceleration().add(evidence.magneticAcceleration());
            requireFinite(passive, "passive environment acceleration");
            return passive;
        }
        BodyPhysics physics = binding.physics().orElseThrow(
                () -> new IllegalStateException("active binding has no body physics"));
        requireFinite(physics);
        binding.appliedForce().ifPresent(force -> requireFinite(force, "applied force"));
        if (!Double.isFinite(waterSinkBias)) {
            throw new IllegalArgumentException("non-finite water sink bias");
        }
        Vec3 acceleration = BodyAccelerationStep.acceleration(evidence.gravityAcceleration(),
                evidence.magneticAcceleration(), binding.appliedForce().orElse(null), physics, inWater,
                swims, waterSinkBias);
        requireFinite(acceleration, "environment acceleration");
        return acceleration;
    }

    private static void requireFinite(BodyPhysics physics) {
        if (!Double.isFinite(physics.massKg()) || physics.massKg() <= 0.0
                || !Double.isFinite(physics.volumeM3()) || physics.volumeM3() < 0.0) {
            throw new IllegalArgumentException("invalid body physics");
        }
    }

    private static void requireFinite(Vec3 vector, String name) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException("non-finite " + name);
        }
    }

    private static boolean unsupported(EntityPlayerMP player) {
        return player.isDead || player.isSpectator() || player.noClip || player.isRiding() || player.isPlayerSleeping();
    }

    private static Unavailable unavailable(Reason reason) {
        return new Unavailable(reason, Optional.empty());
    }

    private record Observation(ActorGeometry geometry, NetHandlerPlayServer connection, long tick,
                                boolean noGravity, boolean creative, boolean flying, boolean inWater,
                                float eyeHeight, ItemStack boots) {
        static Observation read(EntityPlayerMP player, WorldServer world) {
            return new Observation(ActorGeometry.read(player), player.connection, world.getTotalWorldTime(),
                    player.hasNoGravity(), player.capabilities.isCreativeMode, player.capabilities.isFlying,
                    player.isInWater(), player.getEyeHeight(), player.getItemStackFromSlot(EntityEquipmentSlot.FEET).copy());
        }

        boolean matches(PlayerPhysicalSnapshot state) {
            var dimensions = state.placement().shape().dimensions();
            return geometry.id().equals(state.scope().playerId()) && geometry.dimension() == state.scope().dimension()
                    && geometry.position().equals(state.placement().transport())
                    && geometry.bounds().equals(state.placement().bounds())
                    && geometry.width() == dimensions.width() && geometry.height() == dimensions.height()
                    && eyeHeight == dimensions.eyeHeight();
        }

        boolean sameAs(Observation other) {
            return geometry.equals(other.geometry) && connection == other.connection && tick == other.tick
                    && noGravity == other.noGravity && creative == other.creative && flying == other.flying
                    && inWater == other.inWater && eyeHeight == other.eyeHeight
                    && ItemStack.areItemStacksEqual(boots, other.boots);
        }
    }
}
