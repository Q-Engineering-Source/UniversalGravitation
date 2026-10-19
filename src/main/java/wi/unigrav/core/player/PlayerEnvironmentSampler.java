package wi.unigrav.core.player;

import java.util.EnumMap;
import java.util.EnumSet;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;

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
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.FloorDecision;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.floor.PlayerBootObservation;
import wi.unigrav.core.player.StandingConstraints.ConstraintSource;
import wi.unigrav.core.player.StandingConstraints.Evidence;
import wi.unigrav.core.player.StandingConstraints.FaceEvidence;
import wi.unigrav.core.player.WorldCollisionCandidates.ActorGeometry;

/**
 * Server-thread UG field/floor input to standing, in m/s虏. This neither moves the
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
    private final ActiveBootRules activeBootRules;

    public enum Reason {
        PERSONAL_G_UNKNOWN, ACTOR_CHANGED, BINDING_CHANGED, WORLD_UNAVAILABLE,
        UNSUPPORTED_STATE, NATIVE_GRAVITY_UNRESOLVED, CONFIG_CHANGED, SOURCE_FAILED,
        ACTIVE_BOOT_UNAVAILABLE
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
                          double magneticResponse, Vec3 environmentAcceleration, Optional<OwnerObservation> ownerObservation, Evidence rawFields) implements Result {
        public Sample(PlayerPhysicalSnapshot physical, long worldTick, Evidence evidence, Optional<GravityMode> bindingMode,
                boolean bindingActive, boolean personalZeroG, double magneticResponse, Vec3 environmentAcceleration,
                Optional<OwnerObservation> ownerObservation) {
            this(physical, worldTick, evidence, bindingMode, bindingActive, personalZeroG, magneticResponse,
                    environmentAcceleration, ownerObservation, evidence);
        }
        public Sample(PlayerPhysicalSnapshot physical, long worldTick, Evidence evidence, Optional<GravityMode> bindingMode,
                boolean bindingActive, boolean personalZeroG, double magneticResponse, Vec3 environmentAcceleration) {
            this(physical, worldTick, evidence, bindingMode, bindingActive, personalZeroG, magneticResponse,
                    environmentAcceleration, Optional.empty());
        }
        void requireCurrent() { ownerObservation.ifPresent(OwnerObservation::requireCurrent); }
        boolean qualifiedBootEquipment() { return ownerObservation.map(value -> value.qualifiedBoot).orElse(false); }
        public Sample {
            Objects.requireNonNull(rawFields, "rawFields");
            Objects.requireNonNull(environmentAcceleration, "environmentAcceleration");
            requireFinite(environmentAcceleration, "environment acceleration");
        }
    }

    /** A same-owner observation receipt; only this sampler can create it. */
    public static final class OwnerObservation {
        private final Runnable current;
        private final boolean qualifiedBoot;
        private OwnerObservation(Runnable current, boolean qualifiedBoot) {
            this.current = current; this.qualifiedBoot = qualifiedBoot;
        }
        void requireCurrent() { current.run(); }
    }

    sealed interface FreeResult permits FreeSample, FreeUnavailable { }
    record FreeUnavailable(Reason reason, Object evidence, Optional<RuntimeException> cause) implements FreeResult, ReferenceResult { }
    sealed interface ReferenceResult permits EndpointReference, FreeUnavailable { }
    record EndpointReference(Evidence fields, FreeStandingObservation observation) implements ReferenceResult { }
    @FunctionalInterface
    private interface ReferenceObserver {
        ReferenceResult observe(CertifiedReferenceEndpoint endpoint, boolean nativeGravity);
    }
    /** Independent Free observations; six-axis reference scores never stand in for arbitrary-up support. */
    static final class FreeSample implements FreeResult {
        private final FreeStandingObservation observation;
        private final Evidence fields;
        private final Optional<GravityMode> mode;
        private final boolean active, zeroG;
        private final double response;
        private final Vec3 acceleration;
        private final Vec3 detachedAcceleration;
        private final Evidence rawFields;
        private final Evidence nativeReferenceFields;
        private final ReferenceObserver referenceObserver;
        private FreeSample(FreeStandingObservation observation, Evidence fields, Optional<GravityMode> mode,
                           boolean active, boolean zeroG, double response, Vec3 acceleration, Vec3 detachedAcceleration,
                           Evidence rawFields, Evidence nativeReferenceFields, ReferenceObserver referenceObserver) {
            this.observation = observation; this.fields = fields; this.mode = mode; this.active = active;
            this.zeroG = zeroG; this.response = response; this.acceleration = acceleration;
            this.rawFields = Objects.requireNonNull(rawFields);
            this.nativeReferenceFields = Objects.requireNonNull(nativeReferenceFields);
            this.detachedAcceleration = Objects.requireNonNull(detachedAcceleration);
            this.referenceObserver = Objects.requireNonNull(referenceObserver);
        }
        FreeStandingObservation observation() { return observation; }
        Evidence fields() { return fields; }
        Evidence referenceFields(boolean nativeGravity) { return nativeGravity ? nativeReferenceFields : fields; }
        Optional<GravityMode> bindingMode() { return mode; }
        boolean bindingActive() { return active; }
        boolean personalZeroG() { return zeroG; }
        double magneticResponse() { return response; }
        Vec3 environmentAcceleration() { return acceleration; }
        Vec3 detachedAcceleration() { return detachedAcceleration; }
        Evidence rawFields() { return rawFields; }
        ReferenceResult observeReference(CertifiedReferenceEndpoint endpoint, boolean nativeGravity) {
            return referenceObserver.observe(endpoint, nativeGravity);
        }
    }

    /** Same-owner capsule/boot work; this neither chooses a reference nor installs or integrates motion. */
    FreeResult sampleFree(FreeOwnerTransaction tx, Optional<Boolean> personalZeroG,
                          CollisionQueryFootprint.Limits scanLimits) {
        Objects.requireNonNull(tx); Objects.requireNonNull(personalZeroG); Objects.requireNonNull(scanLimits);
        if (personalZeroG.isEmpty()) return new FreeUnavailable(Reason.PERSONAL_G_UNKNOWN, null, Optional.empty());
        // Retain the sampler's entry rules before the floor capture or binder filter can call user code.
        var samplerRulesAtEntry = activeBootRules == null ? null : activeBootRules.snapshot();
        try {
            tx.requireCurrent();
            var work = tx.resources().environmentObservationWork();
            var accepted = tx.accepted(); var player = tx.actor(); var world = tx.world();
            if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule) || unsupported(player))
                return new FreeUnavailable(Reason.UNSUPPORTED_STATE, accepted, Optional.empty());
            var before = Observation.read(player, world);
            if (!before.matchesFree(accepted, tx)) return new FreeUnavailable(Reason.ACTOR_CHANGED, before, Optional.empty());
            if (!before.noGravity() && !before.flying())
                return new FreeUnavailable(Reason.NATIVE_GRAVITY_UNRESOLVED, before, Optional.empty());
            boolean zeroG = personalZeroG.orElseThrow(); boolean swims = BodyPhysics.swims(player);
            var policy = PlayerPhysicsPolicy.capture();
            // Pin the selected world owner before evaluating a binder tick filter. Floor capture has its own
            // bounded lifecycle/mutation fences; its callback-capable refresh is not treated as a pure read.
            var decision = FloorWorldFields.captureDecision(world).orElse(null);
            if (decision == null || !decision.isCurrent()) return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, decision, Optional.empty());
            // Keep the operation-entry revision for the whole sample; capture and later callbacks may reload it.
            var localRules = decision.isLocalCompatibilityOwner() ? activeBootRules : null;
            var rules = localRules == null ? decision.bootRules() : samplerRulesAtEntry;
            java.util.function.BooleanSupplier current = localRules == null ? decision::isCurrent
                    : () -> localRules.isCurrent(rules);
            var staleContext = currentFreeContextFailure(decision, localRules, rules);
            if (staleContext != null) return staleContext;
            var binding = binder.sampleFieldBinding(player, work);
            if (!binding.isCurrent()) return new FreeUnavailable(Reason.BINDING_CHANGED, binding, Optional.empty());
            staleContext = currentFreeContextFailure(decision, localRules, rules);
            if (staleContext != null) return staleContext;
            var responses = decision.isLocalCompatibilityOwner() ? materials : decision.passiveResponses();
            double response = responses.playerResponse(before.boots(), before.creative(), zeroG);
            var center = accepted.placement().center(); var point = new Vec3(center.x(), center.y(), center.z());
            var rawField = binding.active() ? EntityGravitySources.observeFieldAcceleration(player, point,
                    before.tick() * GravityEntityBinder.TICK_SECONDS, work) : null;
            Vec3 rawAmbient = rawField == null ? Vec3.ZERO : rawField.acceleration();
            Vec3 ambient = zeroG ? Vec3.ZERO : rawAmbient;
            var eligibility = PlayerPhysicsPolicy.floorEligibility(policy, zeroG, true);
            // Observe once with the uncompensated physical response. Personal compensation is derived below.
            double rawResponse = responses.playerResponse(before.boots(), before.creative(), false);
            var floors = decision.sampleForStanding(point, rawResponse, work);
            if (floors.isEmpty()) return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, decision, Optional.empty());
            Evidence rawFields = new Evidence(StandingConstraints.Availability.AVAILABLE, rawAmbient, Vec3.ZERO,
                    naturalReference(rawAmbient), List.of(), Optional.empty()).withFloorSample(floors, true, true);
            Evidence fields = before.creative() && zeroG
                    ? compensatedFloorEvidence(ambient, floors.orElseThrow(), eligibility)
                    : new Evidence(StandingConstraints.Availability.AVAILABLE, ambient, Vec3.ZERO,
                            naturalReference(ambient), List.of(), Optional.empty())
                            .withFloorSample(floors, eligibility.force(), eligibility.reference());
            var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
            var water = CapsuleEnvironmentObservation.observe(tx, pose, work);
            if (water instanceof CapsuleEnvironmentObservation.Unavailable unavailable)
                return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, unavailable, unavailable.cause());
            var environment = (CapsuleEnvironmentObservation.Prepared) water;
            var window = CapsuleFootContacts.window(accepted, .25);
            var captured = WorldCollisionCandidates.capture(tx, window,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, scanLimits, work);
            if (captured instanceof WorldCollisionCandidates.Unavailable unavailable)
                return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, unavailable, unavailable.cause());
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            var supported = CapsuleSupportObservation.observe(accepted, pose, capture, window, .25,
                    FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
            if (supported instanceof CapsuleSupportObservation.Failed failure)
                return new FreeUnavailable(Reason.ACTIVE_BOOT_UNAVAILABLE, failure, supportFailureCause(failure));
            var support = (CapsuleSupportObservation.Complete) supported;
            var arithmetic = work.arithmetic();
            var boot = FreeBootObservation.observe(tx, before.boots(), before.creative(), zeroG, rules, current, work);
            if (boot instanceof FreeBootObservation.Failed failure)
                return new FreeUnavailable(Reason.ACTIVE_BOOT_UNAVAILABLE, failure, failure.cause());
            Vec3 acceleration = environmentAcceleration(binding, fields, environment.inWater(), swims, policy.waterSinkBias());
            Runnable sourcesCurrent = () -> {
                tx.requireCurrent();
                if (!binding.isCurrent()) throw new IllegalStateException("Free binding changed during environment observation");
                if (!decision.isCurrent() || !current.getAsBoolean() || !policy.equals(PlayerPhysicsPolicy.capture()))
                    throw new IllegalStateException("Free floor, boot rules or policy changed during environment observation");
                if (before.creative() != player.capabilities.isCreativeMode
                        || !ItemStack.areItemStacksEqual(before.boots(), player.getItemStackFromSlot(EntityEquipmentSlot.FEET))
                        || swims != BodyPhysics.swims(player))
                    throw new IllegalStateException("Free equipment or physics source changed during environment observation");
                if (!binding.isCurrent() || !decision.isCurrent() || !current.getAsBoolean()
                        || rawField != null && !rawField.isCurrent())
                    throw new IllegalStateException("Free field source changed during environment observation");
                work.requireCurrent();
                tx.requireCurrent();
            };
            Runnable baseline = () -> {
                sourcesCurrent.run();
                if (!before.sameAs(Observation.read(player, world)) || !before.matchesFree(accepted, tx)
                        || unsupported(player))
                    throw new IllegalStateException("Free actor geometry changed during environment observation");
                sourcesCurrent.run();
            };
            var observation = FreeStandingObservation.fromComplete(tx, pose, capture, window, scanLimits,
                    support, environment, (FreeBootObservation.Observed) boot, baseline, sourcesCurrent, arithmetic);
            if (!observation.recaptureMatches(tx))
                return new FreeUnavailable(Reason.ACTOR_CHANGED, observation, Optional.empty());
            // Detach suppresses floor/boot attraction, while real ambient/native/body forces remain.
            // Derive it from this same bounded observation, never by resampling a source.
            Vec3 detachedAcceleration = environmentAcceleration(binding, new Evidence(
                    StandingConstraints.Availability.AVAILABLE, ambient, Vec3.ZERO,
                    naturalReference(ambient), List.of(), Optional.empty()), environment.inWater(), swims, policy.waterSinkBias());
            // Reuse this bounded observation; the Step selects native eligibility. This
            // alternate reference evidence never integrates native gravity a second time.
            Vec3 nativeAmbient = ambient.add(new Vec3(0, -32, 0));
            Evidence nativeReferenceFields = new Evidence(StandingConstraints.Availability.AVAILABLE,
                    nativeAmbient, Vec3.ZERO, naturalReference(nativeAmbient), List.of(), Optional.empty())
                    .withFloorSample(floors, eligibility.force(), eligibility.reference());
            return new FreeSample(observation, fields, binding.mode(), binding.active(), zeroG, response,
                    acceleration, detachedAcceleration, rawFields, nativeReferenceFields, (endpoint, nativeGravity) -> {
                try {
                    if (endpoint.transaction() != tx) throw new IllegalArgumentException("foreign endpoint reference transaction");
                    baseline.run(); endpoint.requireCurrent();
                    var endpointCenter = endpoint.pose().placement().center();
                    var endpointPoint = new Vec3(endpointCenter.x(), endpointCenter.y(), endpointCenter.z());
                    // Reference only: no acceleration integration, binding force or water sampling.
                    var endpointField = binding.active() ? EntityGravitySources.observeFieldAcceleration(player,
                            endpointPoint, before.tick() * GravityEntityBinder.TICK_SECONDS, work) : null;
                    Vec3 endpointAmbient = zeroG || endpointField == null ? Vec3.ZERO : endpointField.acceleration();
                    var endpointFloors = decision.sampleForStanding(endpointPoint, rawResponse, work);
                    if (endpointFloors.isEmpty()) return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, decision, Optional.empty());
                    Evidence endpointFields = before.creative() && zeroG
                            ? compensatedFloorEvidence(endpointAmbient, endpointFloors.orElseThrow(), eligibility)
                            : new Evidence(StandingConstraints.Availability.AVAILABLE, endpointAmbient, Vec3.ZERO,
                                    naturalReference(endpointAmbient), List.of(), Optional.empty())
                                    .withFloorSample(endpointFloors, eligibility.force(), eligibility.reference());
                    if (nativeGravity) {
                        Vec3 nativeEndpointAmbient = endpointAmbient.add(new Vec3(0, -32, 0));
                        endpointFields = new Evidence(StandingConstraints.Availability.AVAILABLE,
                                nativeEndpointAmbient, Vec3.ZERO, naturalReference(nativeEndpointAmbient),
                                List.of(), Optional.empty()).withFloorSample(endpointFloors,
                                        eligibility.force(), eligibility.reference());
                    }
                    var endpointWindow = CapsuleFootContacts.window(endpoint, .25);
                    var endpointCaptured = WorldCollisionCandidates.capture(tx, endpointWindow,
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, scanLimits, work);
                    if (endpointCaptured instanceof WorldCollisionCandidates.Unavailable unavailable)
                        return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, unavailable, unavailable.cause());
                    var endpointCapture = (WorldCollisionCandidates.Snapshot) endpointCaptured;
                    var endpointSupported = CapsuleSupportObservation.observe(endpoint, endpointCapture,
                            endpointWindow, .25, FreeOwnerTransaction.ACCURACY);
                    if (endpointSupported instanceof CapsuleSupportObservation.Failed failed)
                        return new FreeUnavailable(Reason.ACTIVE_BOOT_UNAVAILABLE, failed, supportFailureCause(failed));
                    var endpointBoot = FreeBootObservation.observe(endpoint, before.boots(), before.creative(), zeroG,
                            rules, current, work);
                    if (endpointBoot instanceof FreeBootObservation.Failed failed)
                        return new FreeUnavailable(Reason.ACTIVE_BOOT_UNAVAILABLE, failed, failed.cause());
                    Runnable endpointBaseline = () -> {
                        baseline.run(); endpoint.requireCurrent();
                        if (endpointField != null && !endpointField.isCurrent())
                            throw new IllegalStateException("endpoint reference field source changed");
                        work.requireCurrent(); endpoint.requireCurrent();
                    };
                    Runnable endpointSources = () -> {
                        sourcesCurrent.run();
                        if (endpointField != null && !endpointField.isCurrent())
                            throw new IllegalStateException("endpoint reference field source changed after installation");
                        sourcesCurrent.run();
                    };
                    var endpointObservation = FreeStandingObservation.fromEndpoint(endpoint, endpointCapture,
                            endpointWindow, scanLimits, (CapsuleSupportObservation.Complete) endpointSupported,
                            observation, (FreeBootObservation.Observed) endpointBoot, endpointBaseline, endpointSources, arithmetic);
                    if (!endpointObservation.recaptureMatches(tx))
                        return new FreeUnavailable(Reason.ACTOR_CHANGED, endpointObservation, Optional.empty());
                    return new EndpointReference(endpointFields, endpointObservation);
                } catch (RuntimeException cause) {
                    return new FreeUnavailable(Reason.SOURCE_FAILED, endpoint, Optional.of(cause));
                }
            });
        } catch (RuntimeException cause) {
            return new FreeUnavailable(Reason.SOURCE_FAILED, null, Optional.of(cause));
        }
    }

    private static FreeUnavailable currentFreeContextFailure(FloorDecision decision, ActiveBootRules localRules,
                                                               ActiveBootRules.Snapshot rules) {
        if (!decision.isCurrent()) return new FreeUnavailable(Reason.WORLD_UNAVAILABLE, decision, Optional.empty());
        if (localRules != null && !localRules.isCurrent(rules)) {
            var failure = new FreeBootObservation.Failed("context", rules, Optional.empty());
            return new FreeUnavailable(Reason.ACTIVE_BOOT_UNAVAILABLE, failure, Optional.empty());
        }
        return null;
    }

    private static Optional<RuntimeException> supportFailureCause(CapsuleSupportObservation.Failed failure) {
        for (var evaluation : failure.evaluations()) {
            if (evaluation.slope() instanceof CapsuleSupportSlope.Uncertain uncertain && uncertain.cause().isPresent())
                return Optional.of(uncertain.cause().orElseThrow());
        }
        if (failure.foot().orElse(null) instanceof CapsuleFootContacts.Failed foot) {
            if (foot.budgetFailure().isPresent()) return Optional.of(foot.budgetFailure().orElseThrow());
            if (foot.fullObservation() instanceof CapsuleContactCollector.Failed full && full.budgetFailure().isPresent())
                return Optional.of(full.budgetFailure().orElseThrow());
        }
        return Optional.empty();
    }

    public PlayerEnvironmentSampler(GravityEntityBinder binder, MagneticResponseRules materials,
                                    double naturalGravityThreshold) {
        this(binder, materials, naturalGravityThreshold, null);
    }

    /** Production constructor: owns the complete active FEET/material/tier observation path. */
    public PlayerEnvironmentSampler(GravityEntityBinder binder, MagneticResponseRules materials,
                                    double naturalGravityThreshold, ActiveBootRules activeBootRules) {
        this.binder = Objects.requireNonNull(binder, "binder");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.activeBootRules = activeBootRules;
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
        return sample(player, accepted, personalZeroG, null);
    }

    Result sample(EntityPlayerMP player, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
            PlayerPhysicsServer.PendingCardinalPose pending) {
        return sample(player, accepted, personalZeroG, pending, null);
    }

    Result sample(EntityPlayerMP player, PlayerPhysicalSnapshot accepted, Optional<Boolean> personalZeroG,
            PlayerPhysicsServer.PendingCardinalPose pending, EnvironmentObservationWork work) {
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
            if (work != null) work.requireCurrent(world, accepted.scope());
            ActiveBootRules.Snapshot capturedRules = activeBootRules == null ? null : activeBootRules.snapshot();
            var before = Observation.read(player, world);
            boolean swims = BodyPhysics.swims(player);
            boolean physicsActorEligible = (before.matches(accepted)
                    || pending != null && pending.matchesSnapshot(player, accepted, before.eyeHeight()))
                    && player.connection != null
                    && player.connection.player == player && world.provider.getDimension() == player.dimension;
            if (!physicsActorEligible) return unavailable(Reason.ACTOR_CHANGED);
            PlayerPhysicsPolicy.ConfigSnapshot policySnapshot = PlayerPhysicsPolicy.capture();
            double waterSinkBias = policySnapshot.waterSinkBias();
            boolean zeroG = personalZeroG.orElseThrow();
            // This is only a native suppression check, never a substitute for G intent.
            if (!before.noGravity() && !before.flying()) {
                return unavailable(Reason.NATIVE_GRAVITY_UNRESOLVED);
            }
            var binding = work == null ? binder.sampleFieldBinding(player) : binder.sampleFieldBinding(player, work);
            if (!binding.isCurrent()) return unavailable(Reason.BINDING_CHANGED);
            // Ambient-field eligibility keeps its established binding and Personal G semantics.
            boolean gravityApplies = binding.active() && !zeroG;
            var floorEligibility = PlayerPhysicsPolicy.floorEligibility(policySnapshot, zeroG, physicsActorEligible);
            FloorDecision floorDecision = FloorWorldFields.captureDecision(world).orElse(null);
            if (floorDecision == null || !floorDecision.isCurrent()) return unavailable(Reason.WORLD_UNAVAILABLE);
            MagneticResponseRules responseRules = floorDecision.isLocalCompatibilityOwner()
                    ? materials : floorDecision.passiveResponses();
            double response = responseRules.playerResponse(before.boots(), before.creative(), zeroG);
            var position = before.geometry().position();
            Vec3 fieldPoint = new Vec3(position.x(), position.y(), position.z());
            var rawField = binding.active() && work != null ? EntityGravitySources.observeFieldAcceleration(player,
                    fieldPoint, before.tick() * GravityEntityBinder.TICK_SECONDS, work) : null;
            Vec3 rawAmbient = !binding.active() ? Vec3.ZERO : rawField != null ? rawField.acceleration()
                    : EntityGravitySources.fieldAcceleration(player, fieldPoint,
                            before.tick() * GravityEntityBinder.TICK_SECONDS);
            Vec3 ambient = gravityApplies ? rawAmbient : Vec3.ZERO;
            var center = before.geometry().bounds().center();
            Vec3 floorPoint = new Vec3(center.x(), center.y(), center.z());
            double rawResponse = responseRules.playerResponse(before.boots(), before.creative(), false);
            var floors = work == null ? floorDecision.sampleForStanding(floorPoint, rawResponse)
                    : floorDecision.sampleForStanding(floorPoint, rawResponse, work);
            if (floors.isEmpty()) return unavailable(Reason.WORLD_UNAVAILABLE);
            Evidence rawFields = new Evidence(StandingConstraints.Availability.AVAILABLE, rawAmbient, Vec3.ZERO,
                    naturalReference(rawAmbient), List.of(), Optional.empty()).withFloorSample(floors, true, true);
            Evidence evidence = before.creative() && zeroG
                    ? compensatedFloorEvidence(ambient, floors.orElseThrow(), floorEligibility)
                    : new Evidence(StandingConstraints.Availability.AVAILABLE, ambient, Vec3.ZERO,
                            naturalReference(ambient), List.of(), Optional.empty()).withFloorSample(floors,
                            floorEligibility.force(), floorEligibility.reference());
            boolean qualifiedBoot = false;
            if (activeBootRules != null) {
                var bootObservation = floorDecision.isLocalCompatibilityOwner()
                        ? work == null ? PlayerBootObservation.observe(player, accepted, zeroG, before.boots(),
                                before.creative(), activeBootRules, evidence, pending)
                            : PlayerBootObservation.observe(player, accepted, zeroG, before.boots(),
                                before.creative(), activeBootRules, evidence, pending, work)
                        : work == null ? PlayerBootObservation.observe(player, accepted, zeroG, before.boots(),
                                before.creative(), floorDecision, evidence, pending)
                            : PlayerBootObservation.observe(player, accepted, zeroG, before.boots(),
                                before.creative(), floorDecision, evidence, pending, work);
                if (bootObservation instanceof PlayerBootObservation.Unavailable unavailable) {
                    if (unavailable.reason() == PlayerBootObservation.Reason.NATIVE_QUERY_FAILED)
                        return new Unavailable(Reason.SOURCE_FAILED, unavailable.cause());
                    return new Unavailable(Reason.ACTIVE_BOOT_UNAVAILABLE,
                            Optional.of(new IllegalStateException(unavailable.reason() + ": " + unavailable.detail(),
                                    unavailable.cause().orElse(null))));
                }
                var boots = (PlayerBootObservation.Observed) bootObservation;
                evidence = boots.evidence();
                qualifiedBoot = boots.qualification() instanceof ActiveBootRules.QualifiedBoot;
            }
            Vec3 environmentAcceleration = environmentAcceleration(binding, evidence, before.inWater(), swims,
                    waterSinkBias);
            if (!binding.isCurrent()) return unavailable(Reason.BINDING_CHANGED);
            if (player.world != world || !before.sameAs(Observation.read(player, world))
                    || player.connection == null || player.connection.player != player || unsupported(player)
                    || world.captureBlockSnapshots || world.restoringBlockSnapshots) {
                return unavailable(Reason.ACTOR_CHANGED);
            }
            if (swims != BodyPhysics.swims(player)) return unavailable(Reason.SOURCE_FAILED);
            if (!policySnapshot.equals(PlayerPhysicsPolicy.capture())) return unavailable(Reason.CONFIG_CHANGED);
            if (!floorDecision.isCurrent()) return unavailable(Reason.CONFIG_CHANGED);
            if (pending != null && !pending.matchesSnapshot(player, accepted, before.eyeHeight()))
                return unavailable(Reason.ACTOR_CHANGED);
            Runnable current = () -> {
                if (work != null) work.requireCurrent(world, accepted.scope());
                if (player.world != world || !before.sameAs(Observation.read(player, world))
                        || player.connection == null || player.connection.player != player || unsupported(player)
                        || pending != null && !pending.matchesSnapshot(player, accepted, before.eyeHeight()))
                    throw new IllegalStateException("Cardinal environment actor changed before consumption");
                if (!binding.isCurrent() || !floorDecision.isCurrent()
                        || !policySnapshot.equals(PlayerPhysicsPolicy.capture())
                        || capturedRules != null && !activeBootRules.isCurrent(capturedRules)
                        || rawField != null && !rawField.isCurrent())
                    throw new IllegalStateException("Cardinal environment source changed before consumption");
                if (work != null) work.requireCurrent();
            };
            current.run();
            return new Sample(accepted, before.tick(), evidence, binding.mode(), binding.active(), zeroG,
                    response, environmentAcceleration, work == null ? Optional.empty()
                            : Optional.of(new OwnerObservation(current, qualifiedBoot)), rawFields);
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

    /** Derive compensation from the one raw traversal; floor policy remains independent of personal G. */
    private Evidence compensatedFloorEvidence(Vec3 ambient,
            StandingSample floors,
            PlayerPhysicsPolicy.FloorEligibility eligibility) {
        var faces = new EnumMap<CardinalFrame, StandingConstraints.FaceEvidence>(CardinalFrame.class);
        faces.putAll(naturalReference(ambient));
        if (eligibility.reference()) floors.gravityStrengths().forEach((face, strength) -> {
            if (strength <= 0) return;
            CardinalFrame frame = CardinalFrame.fromFloorFace(face);
            var previous = faces.get(frame);
            var sources = EnumSet.of(StandingConstraints.ConstraintSource.FLOOR);
            if (previous != null) sources.addAll(previous.sources());
            faces.put(frame, new StandingConstraints.FaceEvidence(strength
                    + (previous == null ? 0 : previous.preSumReferenceScore()), sources));
        });
        return new Evidence(StandingConstraints.Availability.AVAILABLE,
                eligibility.force() ? ambient.add(floors.forces().gravityAccel()) : ambient, Vec3.ZERO,
                faces, List.of(), Optional.empty(), eligibility.reference()
                        ? new StandingConstraints.FloorStrengths6(
                                floors.gravityStrengths().getOrDefault(Face.UP, 0.0),
                                floors.gravityStrengths().getOrDefault(Face.DOWN, 0.0),
                                floors.gravityStrengths().getOrDefault(Face.EAST, 0.0),
                                floors.gravityStrengths().getOrDefault(Face.WEST, 0.0),
                                floors.gravityStrengths().getOrDefault(Face.SOUTH, 0.0),
                                floors.gravityStrengths().getOrDefault(Face.NORTH, 0.0))
                        : StandingConstraints.FloorStrengths6.zero(), StandingConstraints.FloorStrengths6.zero());
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

        boolean matchesFree(PlayerPhysicalSnapshot state, FreeOwnerTransaction tx) {
            var dimensions = state.placement().shape().dimensions();
            boolean samePhysicalAnchor = geometry.id().equals(state.scope().playerId())
                    && geometry.dimension() == state.scope().dimension()
                    && geometry.position().equals(state.placement().transport()) && geometry.bounds().equals(state.placement().bounds())
                    && Float.floatToIntBits((float) geometry.width()) == Float.floatToIntBits((float) dimensions.width())
                    && Float.floatToIntBits((float) geometry.height()) == Float.floatToIntBits((float) dimensions.height());
            if (!samePhysicalAnchor) return false;
            return Float.floatToIntBits(eyeHeight) == Float.floatToIntBits((float) dimensions.eyeHeight())
                    || tx.acceptsTrustedResizeObservedEye(eyeHeight);
        }

        boolean sameAs(Observation other) {
            return geometry.equals(other.geometry) && connection == other.connection && tick == other.tick
                    && noGravity == other.noGravity && creative == other.creative && flying == other.flying
                    && inWater == other.inWater && eyeHeight == other.eyeHeight
                    && ItemStack.areItemStacksEqual(boots, other.boots);
        }
    }
}
