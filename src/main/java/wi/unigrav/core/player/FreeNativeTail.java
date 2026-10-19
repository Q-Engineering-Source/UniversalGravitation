package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;

/** Installed-stage native consumers. The caller alone installs, commits and runs the world/trigger tail. */
final class FreeNativeTail {
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private FreeNativeTail() { }

    static void run(InstalledFreeEndpoint endpoint, CapsuleMovementResolver.Resolved movement,
            CapsuleMovementResolver.Resolved.InstalledEvidence movementEvidence,
            WorldCollisionCandidates.Snapshot installedBaseline, Runnable worldTail) {
        Objects.requireNonNull(endpoint); Objects.requireNonNull(worldTail);
        endpoint.requireCurrent();
        FreeOwnerTransaction tx = endpoint.transaction();
        if (movement != null && (movementEvidence == null || !movement.belongsTo(tx)
                || !movementEvidence.matches(endpoint)))
            throw new IllegalArgumentException("installed native tail received unsealed movement evidence");
        if (installedBaseline == null)
            throw new IllegalArgumentException("installed native tail has no exact installed collision baseline");
        var installedContext = installedBaseline.capsuleContextFailure(
                endpoint.owner(), endpoint.pose().placement().bounds());
        if (installedContext.isPresent()) throw contextFailure(installedContext.orElseThrow(),
                "installed native-tail baseline");
        // The phase proof deliberately does not capture collisions or inspect legitimate callback motion.
        var scope = CardinalNativeBoundary.open(tx, tx.actor(), tx.resources().environmentObservationWork(),
                endpoint::requireCurrent);
        try (scope) {
            try {
            var installedGeneration = movementEvidence == null ? null : movementEvidence.bindInstalled(endpoint);
            if (!installedBaseline.sameObservation(CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                    tx, endpoint.owner(), endpoint.pose())))
                throw new IllegalStateException("installed target changed between installation and native-tail entry");
            var observation = Observation.capture(endpoint);
            var landing = landing(endpoint, movement, observation);
            var producer = landing.flatMap(event -> observation.capture.blockProducer(endpoint,
                    observation.complete, event.support.ordinal()));
            // Static certified support establishes geometry ground without manufacturing a landing event.
            Optional<NativeCollisionProvenance.Entry> walkingProducer = Optional.empty();
            for (var value : observation.complete.supports()) {
                walkingProducer = observation.capture.blockProducer(endpoint, observation.complete, value.ordinal());
                if (walkingProducer.isPresent()) break;
            }
            boolean grounded = !observation.complete.supports().isEmpty();
            setContacts(endpoint,movement,grounded);
            // EntityPlayerMP overrides updateFallState with an empty protected method.
            // Actual MP fall consumption requires the separate inherited-native bridge.
            var responseCapture = CapsuleMoveFinalizer.Ready.observeInstalledTarget(tx, endpoint.owner(), endpoint.pose());
            if (!installedBaseline.sameObservation(responseCapture))
                throw new IllegalStateException("installed body changed before G0 response capture completed");
            installedGeneration = movementEvidence == null ? null : movementEvidence.refreshInstalled(endpoint);
            var g0Constraints = responseConstraints(endpoint, movement, movementEvidence,
                    installedGeneration, responseCapture);
            var g0Fence = tx.sealInstalledResponseFence(endpoint);
            var incoming = motion(tx.actor());
            Vec3d geometricResponse = projectVelocity(endpoint, movement, movementEvidence,
                    installedGeneration, g0Constraints, incoming, g0Fence);
            ActualResponseReceipt g0 = applyResponse(endpoint, movement, ActualResponseReceipt.Stage.G0,
                    incoming, geometricResponse, g0Fence);
            if (!g0.matches(endpoint, movement, ActualResponseReceipt.Stage.G0, incoming, geometricResponse))
                throw new IllegalStateException("Free G0 response write did not preserve its exact raw readback");
            var historicalAxis = InstalledFreeImpact.sampleHistoricalAxisContacts(
                    endpoint, movement, movementEvidence, incoming, g0);
            var impact = InstalledFreeImpact.prepare(endpoint, movement, movementEvidence,
                    incoming, historicalAxis, g0,
                    producer.isPresent(), grounded);
            if (producer.isPresent()) {
                var selected = producer.orElseThrow();
                observation.refresh();
                var block = CardinalNativeBoundary.dispatch(tx.actor(), "landing getBlock", selected.state()::getBlock);
                observation.refresh();
                CardinalBlockMotion.run(tx.actor(), endpoint.pose().placement().attitude(), () -> {
                    endpoint.requireCurrent();
                    var l0Fence = tx.sealInstalledResponseFence(endpoint);
                    Vec3d landingInput = landingInput(endpoint, incoming, g0.readback());
                    if (!sameRaw(motion(tx.actor()), g0.readback()))
                        throw new IllegalStateException("Free landing callback changed velocity before the L0 input");
                    ActualResponseReceipt l0 = applyResponse(endpoint, movement, ActualResponseReceipt.Stage.L0,
                            g0.readback(), landingInput, l0Fence);
                    if (!l0.matches(endpoint, movement, ActualResponseReceipt.Stage.L0,
                            g0.readback(), landingInput))
                        throw new IllegalStateException("Free L0 landing input lost its exact raw readback");
                    CardinalNativeBoundary.dispatch(tx.actor(), "Free onLanded", () ->
                            block.onLanded(tx.world(), tx.actor()));
                });
                observation.refresh();
                var landedCapture = CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                        tx, endpoint.owner(), endpoint.pose());
                if (!responseCapture.sameObservation(landedCapture))
                    throw new IllegalStateException("installed body collision observation changed during landing callback");
                var g1Generation = movementEvidence == null ? null : movementEvidence.refreshInstalled(endpoint);
                var g1Constraints = responseConstraints(endpoint, movement, movementEvidence,
                        g1Generation, landedCapture);
                var g1Fence = tx.sealInstalledResponseFence(endpoint);
                Vec3d landedVelocity = motion(tx.actor());
                Vec3d g1Velocity = projectVelocity(endpoint, movement, movementEvidence,
                        g1Generation, g1Constraints, landedVelocity, g1Fence);
                ActualResponseReceipt g1 = applyResponse(endpoint, movement, ActualResponseReceipt.Stage.G1,
                        landedVelocity, g1Velocity, g1Fence);
                if (!g1.matches(endpoint, movement, ActualResponseReceipt.Stage.G1, landedVelocity, g1Velocity))
                    throw new IllegalStateException("Free G1 post-landing response write lost its exact raw readback");
            }
            // Walking requires a real BLOCK producer but does not require a newly occurring landing.
            if (walkingProducer.isPresent()) {
                var selected = walkingProducer.orElseThrow();
                Vec3d delta = actualDelta(movement);
                var calls = new FreshCalls(observation);
                observation.refresh();
                NativeWalkingEvents.finish(tx.actor(), () -> endpoint.pose().placement().attitude().inverseRotate(delta),
                        selected.state(), selected.cell(), calls);
                observation.refresh();
            }
            endpoint.requireCurrent();
            worldTail.run();
            endpoint.requireCurrent();
            observation.refresh();
            InstalledFreeFall.consume(endpoint,movement,grounded,walkingProducer,observation::refresh);
            endpoint.requireCurrent();
            observation.refresh();
            impact.consume(() -> {
                observation.refresh();
                if (movementEvidence != null) movementEvidence.refreshInstalled(endpoint);
                endpoint.requireCurrent();
            });
            if (movement != null) {
                InstalledMovementStat.consume(endpoint, actualDelta(movement));
                endpoint.requireCurrent();
                observation.refresh();
            }
            scope.check("Free native tail completed");
            } catch (RuntimeException failure) {
                if (scope.isOwnAuthorityFailure(failure) && tx.originalFailure().isEmpty())
                    throw new Stop(endpoint, false);
                throw failure;
            }
        }
    }

    /** Internal, unforgeable completion signal for one installed native-tail operation. */
    static final class Stop extends RuntimeException {
        private final InstalledFreeEndpoint endpoint;
        private final boolean nativeTransition;
        private Stop(InstalledFreeEndpoint endpoint, boolean nativeTransition) {
            super("installed native tail stopped at its authority boundary", null, false, false);
            this.endpoint = endpoint; this.nativeTransition = nativeTransition;
        }
        boolean belongsTo(FreeOwnerTransaction transaction) { return endpoint.transaction() == transaction; }
        PlayerPhysicsStep.CommittedTailStop result() {
            var tx = endpoint.transaction();
            var journal = endpoint.owner();
            if (nativeTransition) {
                var responseFence = tx.sealInstalledResponseFence(endpoint);
                var actor = tx.actor();
                Vec3d observedVelocity = new Vec3d(actor.motionX, actor.motionY, actor.motionZ)
                        .multiply(1 / wi.unigrav.core.GravityEntityBinder.TICK_SECONDS);
                responseFence.requireCurrent();
                journal = new PlayerPhysicalSnapshot(journal.scope(), journal.revision(), journal.placement(),
                        observedVelocity);
                responseFence.requireCurrent();
            }
            return new PlayerPhysicsStep.CommittedTailStop(journal, tx.installationCustody(), nativeTransition);
        }
    }

    private record Landing(CapsuleMovementResolver.ClipEvent event, CapsuleSupportObservation.Support support) { }
    private static Optional<Landing> landing(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement, Observation observation) {
        // A later arc/resize, even one returning equal geometry, cannot reuse an earlier translation event.
        if (movement == null || endpoint.pose() != movement.end()) return Optional.empty();
        var work = endpoint.transaction().resources().environmentObservationWork();
        for (var support : observation.complete.supports()) {
            for (var event : movement.clipEvents()) {
                work.charge(EnvironmentObservationWork.Unit.COMPARISON);
                if (!event.matchesFinal(endpoint, observation.complete, observation.capture, support)) continue;
                // For geometry-only colliders a matching actual raw box suffices. BLOCK callbacks need
                // independently captured producer descriptions; an equal replacement box gives no authority.
                var finalEntry = observation.capture.blockProducer(endpoint, observation.complete, support.ordinal());
                if (finalEntry.isPresent()) {
                    var historical = event.limiting().capture().provenance();
                    if (historical.isEmpty()) continue;
                    var entries = historical.orElseThrow().entries();
                    if (event.limiting().ordinal() >= entries.size()
                            || !entries.get(event.limiting().ordinal()).sameBlockDescription(finalEntry.orElseThrow(), work)) continue;
                }
                endpoint.requireCurrent();
                return Optional.of(new Landing(event, support));
            }
        }
        return Optional.empty();
    }

    static final class ActualResponseReceipt {
        enum Stage { G0, L0, G1, G2 }
        private final InstalledFreeEndpoint endpoint;
        private final CapsuleMovementResolver.Resolved movement;
        private final Stage stage;
        private final Vec3d source, requested, readback;
        private ActualResponseReceipt(InstalledFreeEndpoint endpoint,
                CapsuleMovementResolver.Resolved movement, Stage stage, Vec3d source,
                Vec3d requested, Vec3d readback) {
            this.endpoint = Objects.requireNonNull(endpoint); this.movement = movement;
            this.stage = Objects.requireNonNull(stage); this.source = Objects.requireNonNull(source);
            this.requested = Objects.requireNonNull(requested); this.readback = Objects.requireNonNull(readback);
        }
        boolean matches(InstalledFreeEndpoint exactEndpoint, CapsuleMovementResolver.Resolved exactMovement,
                Stage exactStage, Vec3d exactSource, Vec3d exactRequested) {
            return endpoint == exactEndpoint && movement == exactMovement
                    && stage == exactStage
                    && sameRaw(source, exactSource) && sameRaw(requested, exactRequested)
                    && sameRaw(readback, requested);
        }
        Stage stage() { return stage; }
        Vec3d requested() { return requested; }
        Vec3d readback() { return readback; }
    }

    private static ActualResponseReceipt applyResponse(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement, ActualResponseReceipt.Stage stage,
            Vec3d source, Vec3d requested, FreeOwnerTransaction.ResponseFence fence) {
        requireResponseFence(endpoint, fence);
        Entity actor = endpoint.transaction().actor();
        if (!sameRaw(motion(actor), source))
            throw new IllegalStateException("Free " + stage + " source velocity changed before response installation");
        actor.motionX = requested.x(); actor.motionY = requested.y(); actor.motionZ = requested.z();
        requireResponseFence(endpoint, fence);
        Vec3d readback = motion(actor);
        if (!sameRaw(readback, requested))
            throw new IllegalStateException("Free " + stage + " response readback differs from the exact requested vector");
        return new ActualResponseReceipt(endpoint, movement, stage, source, requested, readback);
    }

    private static List<FreeMovementExactGeometry.Vector> responseConstraints(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement, CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
            CapsuleMovementResolver.Resolved.InstalledGeneration generation,
            WorldCollisionCandidates.Snapshot capture) {
        endpoint.requireCurrent();
        var installedContext = capture.capsuleContextFailure(endpoint.owner(), endpoint.pose().placement().bounds());
        if (installedContext.isPresent()) throw contextFailure(installedContext.orElseThrow(),
                "Free velocity response body capture");
        if (movement != null) {
            if (!movement.belongsTo(endpoint.transaction()) || movement.end() != endpoint.pose() || evidence == null
                    || generation == null || !evidence.matches(endpoint))
                throw new IllegalStateException("Free velocity response lost its installed movement identity");
            return evidence.responseConstraints(generation, endpoint.pose(), capture);
        }
        var tx = endpoint.transaction();
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(tx.numeric(), tx::requireOwnerFence);
        var constraints = new java.util.ArrayList<FreeMovementExactGeometry.Vector>();
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            var obstacle = capture.boxes().get(ordinal);
            var support = FreeMovementExactGeometry.support(arithmetic, ordinal,
                    endpoint.pose().capsule().centerline(), obstacle, endpoint.pose().capsule().radius());
            if (support.isPresent() && support.orElseThrow().exactlyTouching(arithmetic))
                constraints.add(support.orElseThrow().facet().normal());
        }
        endpoint.requireCurrent();
        return List.copyOf(constraints);
    }

    private static Vec3d projectVelocity(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement, CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
            CapsuleMovementResolver.Resolved.InstalledGeneration generation,
            List<FreeMovementExactGeometry.Vector> constraints, Vec3d source,
            FreeOwnerTransaction.ResponseFence fence) {
        requireResponseFence(endpoint, fence);
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(endpoint.transaction().numeric(),
                fence::requireCurrent);
        if (movement != null)
            return evidence.projectResponse(generation, constraints, source, fence);
        return FreeMovementExactGeometry.projectToConeStored(arithmetic, source, constraints);
    }

    private static Vec3d landingInput(InstalledFreeEndpoint endpoint, Vec3d originalIncoming, Vec3d g0Tangent) {
        var attitude = endpoint.pose().placement().attitude();
        Vec3d originalLocal = attitude.inverseRotate(originalIncoming);
        Vec3d tangentLocal = attitude.inverseRotate(g0Tangent);
        return attitude.rotate(new Vec3d(tangentLocal.x(), originalLocal.y(), tangentLocal.z()));
    }

    static FreeOwnerTransaction.ResponseReadback applyFinalResponse(InstalledFreeEndpoint endpoint,
            CapsuleMovementResolver.Resolved movement, CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
            WorldCollisionCandidates.Snapshot installedBaseline,
            WorldCollisionCandidates.Snapshot finalCapture) {
        endpoint.requireCurrent();
        if (installedBaseline == null || movement != null && evidence == null)
            throw new IllegalArgumentException("Free G2 response is missing its installed baseline or sealed movement");
        if (!installedBaseline.sameObservation(finalCapture))
            throw new IllegalStateException("installed target collision evidence changed before G2");
        var generation = movement == null ? null : evidence.refreshInstalled(endpoint);
        var lastCapture = CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                endpoint.transaction(), endpoint.owner(), endpoint.pose());
        if (!finalCapture.sameObservation(lastCapture))
            throw new IllegalStateException("installed target collision evidence changed during G2 route refresh");
        var constraints = responseConstraints(endpoint, movement, evidence, generation, lastCapture);
        var responseFence = endpoint.transaction().sealInstalledResponseFence(endpoint);
        Vec3d source = motion(endpoint.transaction().actor());
        Vec3d response = projectVelocity(endpoint, movement, evidence, generation, constraints, source, responseFence);
        ActualResponseReceipt g2 = applyResponse(endpoint, movement, ActualResponseReceipt.Stage.G2,
                source, response, responseFence);
        if (!g2.matches(endpoint, movement, ActualResponseReceipt.Stage.G2, source, response))
            throw new IllegalStateException("Free G2 final response lost its exact raw readback");
        return new FreeOwnerTransaction.ResponseReadback(responseFence, g2.readback());
    }

    private static void requireResponseFence(InstalledFreeEndpoint endpoint,
            FreeOwnerTransaction.ResponseFence fence) {
        if (fence == null || !fence.belongsTo(endpoint))
            throw new IllegalStateException("Free response lost its sealed installed owner fence");
        fence.requireCurrent();
    }

    private static boolean sameRaw(Vec3d first, Vec3d second) {
        return Double.doubleToRawLongBits(first.x()) == Double.doubleToRawLongBits(second.x())
                && Double.doubleToRawLongBits(first.y()) == Double.doubleToRawLongBits(second.y())
                && Double.doubleToRawLongBits(first.z()) == Double.doubleToRawLongBits(second.z());
    }

    private static RuntimeException contextFailure(WorldCollisionCandidates.Unavailable unavailable, String stage) {
        RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                stage + " unavailable: " + unavailable.reason() + ": " + unavailable.detail()));
        return new IllegalStateException(stage + " unavailable: " + unavailable.reason() + ": "
                + unavailable.detail(), cause);
    }

    private static Vec3d motion(Entity actor) { return new Vec3d(actor.motionX, actor.motionY, actor.motionZ); }

    private static void setContacts(InstalledFreeEndpoint endpoint, CapsuleMovementResolver.Resolved movement, boolean grounded) {
        endpoint.requireCurrent();
        boolean horizontal=false,vertical=false;
        if(movement!=null) {
            var axes=movement.blockedAxes(); horizontal=axes.contains(0)||axes.contains(2);vertical=axes.contains(1);
            for(var event:movement.clipEvents()) {
                if(endpoint.pose()!=movement.end())break;
                if(event.axis()==1)vertical=true;else horizontal=true;
            }
        }
        endpoint.requireCurrent();
        var actor=endpoint.transaction().actor();actor.onGround=grounded;
        actor.collidedHorizontally=horizontal;actor.collidedVertically=vertical;actor.collided=horizontal||vertical;
    }

    /** Translation displacement only: no virtual arc/resize/conversion correction enters native gait/fall. */
    static Vec3d actualDelta(CapsuleMovementResolver.Resolved movement) {
        if (movement == null) return new Vec3d(0, 0, 0);
        Vec3d result = new Vec3d(0, 0, 0);
        for (var segment : movement.segments()) result = result.add(segment.end().placement().transport()
                .subtract(segment.tube().start().placement().transport()));
        return result;
    }

    private static final class Observation {
        private final InstalledFreeEndpoint endpoint;
        private final WorldCollisionCandidates.Snapshot capture;
        private final CapsuleSupportObservation.Complete complete;
        private Observation(InstalledFreeEndpoint endpoint, WorldCollisionCandidates.Snapshot capture,
                            CapsuleSupportObservation.Complete complete) {
            this.endpoint = endpoint; this.capture = capture; this.complete = complete;
        }
        static Observation capture(InstalledFreeEndpoint endpoint) {
            endpoint.requireCurrent();
            var tx = endpoint.transaction();
            var window = CapsuleFootContacts.window(endpoint, 0);
            var result = WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                    SCAN, tx.resources().environmentObservationWork());
            if (result instanceof WorldCollisionCandidates.Unavailable unavailable)
                throw unavailable.cause().orElseGet(() -> new IllegalStateException("installed foot capture unavailable: "
                        + unavailable.reason() + ": " + unavailable.detail()));
            var captured = (WorldCollisionCandidates.Snapshot) result;
            var support = CapsuleSupportObservation.observe(endpoint, captured, window, 0, FreeOwnerTransaction.ACCURACY);
            if (!(support instanceof CapsuleSupportObservation.Complete complete))
                throw supportFailure(support);
            endpoint.requireCurrent();
            return new Observation(endpoint, captured, complete);
        }
        private static RuntimeException supportFailure(CapsuleSupportObservation.Result result) {
            Throwable cause = null;
            if (result instanceof CapsuleSupportObservation.Failed failed) {
                if (failed.foot().orElse(null) instanceof CapsuleFootContacts.Failed foot) {
                    cause = foot.budgetFailure().orElse(null);
                    if (cause == null && foot.contextFailure().isPresent())
                        cause = foot.contextFailure().orElseThrow().cause().orElse(null);
                    if (cause == null && foot.fullObservation() instanceof CapsuleContactCollector.Failed body) {
                        cause = body.budgetFailure().orElse(null);
                        if (cause == null && body.contextFailure().isPresent())
                            cause = body.contextFailure().orElseThrow().cause().orElse(null);
                    }
                }
                if (cause == null) {
                    for (var evaluation : failed.evaluations()) {
                        if (evaluation.slope() instanceof CapsuleSupportSlope.Uncertain uncertain
                                && uncertain.cause().isPresent()) { cause = uncertain.cause().orElseThrow(); break; }
                    }
                }
            }
            return new IllegalStateException("installed foot support observation failed: " + result, cause);
        }
        void refresh() {
            endpoint.requireCurrent();
            if (PlayerKinematics.requiresNative(endpoint.transaction().actor())
                    && endpoint.transaction().originalFailure().isEmpty()) {
                endpoint.requireCurrent();
                throw new Stop(endpoint, true);
            }
            var tx = endpoint.transaction();
            var result = WorldCollisionCandidates.capture(tx, complete.foot().window(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, tx.resources().environmentObservationWork());
            if (result instanceof WorldCollisionCandidates.Unavailable unavailable)
                throw unavailable.cause().orElseGet(() -> new IllegalStateException("installed foot recapture unavailable: "
                        + unavailable.reason() + ": " + unavailable.detail()));
            var observed = (WorldCollisionCandidates.Snapshot) result;
            if (!capture.sameObservation(observed) || !complete.matches(endpoint.owner(), endpoint.pose(), capture,
                    complete.foot().window(), tx.queries(), tx.numeric()))
                throw new IllegalStateException("installed foot geometry or producer changed during native consumption");
            endpoint.requireCurrent();
        }
    }

    private static final class FreshCalls extends NativeWalkingEvents.Calls {
        private final Observation observation;
        private FreshCalls(Observation observation) { this.observation = observation; }
        @Override <T> T dispatch(Entity actor, String stage, Supplier<T> action) {
            observation.refresh();
            T result = CardinalNativeBoundary.dispatch(actor, stage, action);
            observation.refresh();
            return result;
        }
        @Override void dispatch(Entity actor, String stage, Runnable action) {
            dispatch(actor, stage, () -> { action.run(); return null; });
        }
        @Override void check(Entity actor, String stage) {
            observation.refresh(); CardinalNativeBoundary.check(actor, stage);
        }
        @Override Block block(Entity actor, IBlockState state) { return dispatch(actor, "support getBlock", state::getBlock); }
        @Override Material material(Entity actor, IBlockState state) { return dispatch(actor, "support material", state::getMaterial); }
        @Override float nextFloat(Entity actor, Random random) { return dispatch(actor, "walking RNG", () -> { return random.nextFloat(); }); }
    }
}
