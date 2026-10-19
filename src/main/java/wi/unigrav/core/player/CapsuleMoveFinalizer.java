package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.GravityEntityBinder;

/** One fixed-Q installation and actual-capsule trigger tail. Landing/support is a separate proof consumer. */
final class CapsuleMoveFinalizer {
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    sealed interface Preparation permits Ready, Unavailable { }
    record Unavailable(String reason, Optional<CapsuleEnvironmentObservation.Result> observation) implements Preparation {
        Unavailable(String reason) { this(reason, Optional.empty()); }
    }
    private CapsuleMoveFinalizer() { }

    static Preparation prepare(FreeOwnerTransaction transaction, CapsuleMovementResolver.Resolved movement) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(movement);
        if (!movement.matches(transaction)) return new Unavailable("capsule movement proof context changed");
        var observed = CapsuleEnvironmentObservation.observe(transaction, movement.end(), transaction.resources().environmentObservationWork());
        if (!(observed instanceof CapsuleEnvironmentObservation.Prepared environment))
            return new Unavailable("actual capsule trigger preparation unavailable", Optional.of(observed));
        return new Ready(transaction, movement, environment);
    }

    /** Consume only this invocation's certified contact before native environment callbacks. */
    static FreeOwnerTransaction.ResponseReadback applyCertifiedTranslationResponse(FreeOwnerTransaction transaction,
            CapsuleMovementResolver.Resolved movement,
            CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
            WorldCollisionCandidates.Snapshot installedCapture) {
        transaction.requireCurrent();
        if (!movement.belongsTo(transaction)
                || transaction.installedPlacement().orElse(null) != movement.end().placement())
            throw new IllegalStateException("contact response does not match the installed movement journal");
        var actor = transaction.actor();
        var response = translationResponse(movement, evidence, transaction, installedCapture);
        response.fence().requireCurrent();
        actor.motionX = response.motion().x();
        actor.motionY = response.motion().y();
        actor.motionZ = response.motion().z();
        actor.onGround = false;
        actor.collidedHorizontally = response.horizontal();
        actor.collidedVertically = response.vertical();
        actor.collided = response.horizontal() || response.vertical();
        response.fence().requireCurrent();
        Vec3d readback = motion(actor);
        if (!sameRaw(response.motion(), readback))
            throw new IllegalStateException("Cardinal terminal response lost its exact raw write/readback");
        return new FreeOwnerTransaction.ResponseReadback(response.fence(), readback,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                        actor.collidedVertically, actor.fallDistance, false)));
    }

    static FreeOwnerTransaction.ResponseReadback applyCardinalIdentityResponse(
            FreeOwnerTransaction transaction) {
        transaction.requireCurrent();
        FreeOwnerTransaction.ResponseFence fence = transaction.sealInstalledResponseFence();
        Entity actor = transaction.actor();
        Vec3d source = motion(actor);
        fence.requireCurrent();
        actor.motionX = source.x(); actor.motionY = source.y(); actor.motionZ = source.z();
        fence.requireCurrent();
        Vec3d readback = motion(actor);
        if (!sameRaw(source, readback))
            throw new IllegalStateException("Cardinal terminal identity response lost its raw readback");
        return new FreeOwnerTransaction.ResponseReadback(fence, readback);
    }
    /** Pure calculation only. Installing it requires the caller's exact movement/journal proof. */
    record TranslationResponse(Vec3d motion, boolean horizontal, boolean vertical,
                               FreeOwnerTransaction.ResponseFence fence) { }

    static TranslationResponse translationResponse(CapsuleMovementResolver.Resolved movement,
            CapsuleMovementResolver.Resolved.InstalledEvidence evidence, FreeOwnerTransaction transaction,
            WorldCollisionCandidates.Snapshot installedCapture) {
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(installedCapture, "installedCapture");
        var axes = movement.blockedAxes();
        PlayerBodyPlacement installed = transaction.installedPlacement().orElseThrow(() ->
                new IllegalStateException("contact response has no installed body journal"));
        if (movement.end().placement() == installed && evidence != null)
            throw new IllegalStateException("Free installed response must use the staged native-tail receipt chain");
        var constraints = actualTouchingConstraints(transaction, installed, installedCapture);
        var responseFence = transaction.sealInstalledResponseFence();
        Vec3d source = motion(transaction.actor());
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(), responseFence::requireCurrent);
        Vec3d projected = FreeMovementExactGeometry.projectToConeStored(arithmetic, source, constraints);
        return new TranslationResponse(projected,
                axes.contains(0) || axes.contains(2), axes.contains(1), responseFence);
    }

    static java.util.List<FreeMovementExactGeometry.Vector> actualTouchingConstraints(
            FreeOwnerTransaction transaction, PlayerBodyPlacement installed,
            WorldCollisionCandidates.Snapshot capture) {
        transaction.requireCurrent();
        PlayerPhysicalSnapshot journal = transaction.installedJournal().orElseThrow(() ->
                new IllegalStateException("actual-touch response has no installed snapshot"));
        if (transaction.installedPlacement().orElse(null) != installed)
            throw new IllegalStateException("actual-touch response lost its installed body capture");
        var context = capture.footprint().policy() == CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT
                ? capture.strictInstalledTargetContextFailure(journal, installed)
                : capture.capsuleContextFailure(journal, installed.bounds());
        if (context.isPresent()) {
            var unavailable = context.orElseThrow();
            RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                    "actual-touch response capture unavailable: " + unavailable.reason() + ": " + unavailable.detail()));
            throw new IllegalStateException("actual-touch response capture unavailable: "
                    + unavailable.reason() + ": " + unavailable.detail(), cause);
        }
        var arithmetic = new FreeMovementExactGeometry.Arithmetic(transaction.numeric(), transaction::requireCurrent);
        var constraints = new java.util.ArrayList<FreeMovementExactGeometry.Vector>();
        var pose = CapsulePlacementMath.pose(installed, transaction.numeric());
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            var support = FreeMovementExactGeometry.support(arithmetic, ordinal, pose.capsule().centerline(),
                    capture.boxes().get(ordinal), pose.capsule().radius());
            if (support.isPresent() && support.orElseThrow().exactlyTouching(arithmetic))
                constraints.add(support.orElseThrow().facet().normal());
        }
        transaction.requireCurrent();
        return java.util.List.copyOf(constraints);
    }

    static final class Ready implements Preparation {
        private final FreeOwnerTransaction transaction;
        private final CapsuleMovementResolver.Resolved movement;
        private final CapsuleEnvironmentObservation.Prepared environment;
        private final Vec3d motion;
        private boolean consumed;
        private PlayerPhysicalSnapshot installed;
        private Ready(FreeOwnerTransaction transaction, CapsuleMovementResolver.Resolved movement,
                      CapsuleEnvironmentObservation.Prepared environment) {
            this.transaction = transaction; this.movement = movement; this.environment = environment;
            motion = motion(transaction.actor());
        }
        Optional<PlayerPhysicalSnapshot> installation() { return Optional.ofNullable(installed); }
        CapsuleEnvironmentObservation.Prepared environment() { return environment; }

        PlayerPhysicalSnapshot commit() {
            if (consumed) throw new IllegalStateException("capsule finalizer already consumed");
            consumed = true;
            if (!movement.matches(transaction) || !environment.matches(transaction, movement.end())
                    || !motion.equals(motion(transaction.actor())))
                throw new IllegalStateException("capsule finalizer preparation became stale");
            // Native/Forge environment readers can change collision candidates in the same tick.
            // This is a fresh bounded observation, never a fabricated world mutation revision.
            var captureFailure = movement.recaptureFailure(transaction);
            if (captureFailure.isPresent()) {
                var failure = captureFailure.orElseThrow();
                throw new IllegalStateException("capsule collision recapture failed: " + failure.reason()
                        + ": " + failure.detail(), failure.cause().orElse(null));
            }
            if (!environment.matches(transaction, movement.end()))
                throw new IllegalStateException("capsule collision candidates changed before installation");
            Entity actor = transaction.actor();
            var placement = movement.end().placement();
            var bounds = placement.bounds();
            var transport = placement.transport();
            // Construct the immutable successor before either native setter can mutate the actor.
            // If a setter mutates and then throws, the transaction still retains the exact intended
            // journal and a separately captured post-failure native geometry observation.
            var finalEndpoint = movement.source() == null
                    ? CertifiedReferenceEndpoint.fromMovement(transaction, movement)
                    : CertifiedReferenceEndpoint.from(transaction,
                            CapsulePathSource.continueAfter(movement, transaction.numeric()));
            var prepared = transaction.prepareInstallation(placement, finalEndpoint);
            var movementEvidence = movement.sealForInstallation(transaction, prepared);
            transaction.beginInstallation(prepared);
            // Preserve the exact installed proxy/transport pair, including accepted rounding margins.
            try {
                actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
                actor.posX = transport.x(); actor.posY = transport.y(); actor.posZ = transport.z();
                transaction.recordInstalled(prepared);
                installed = prepared.journal();
            } catch (RuntimeException failure) {
                transaction.recordMutationFailure(prepared, failure);
                throw failure;
            }
            try {
                var installedTargetObservation = observeInstalledTarget(transaction, installed, movement.end());
                // The grant was reserved before proof work; commit only after the actual installed
                // capsule has passed a second native candidate query against its exact target bounds.
                transaction.commit(placement, movement.pathUpper());
                var installedEndpoint = transaction.installedFreeEndpoint();
                transaction.runNativeEnvironment(installed, () -> FreeNativeTail.run(installedEndpoint, movement,
                        movementEvidence, installedTargetObservation, () -> {
                    if (actor.isAddedToWorld() && !actor.world.isRemote) {
                        transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                        actor.world.updateEntityWithOptionalForce(actor, false);
                    }
                    transaction.requireCurrent();
                    CardinalBlockMotion.run(actor, placement.attitude(), () -> {
                        for (var cell : environment.cells()) {
                            if (cell.voxel().relation() != Relation.OVERLAPPING) continue;
                            requireEnvironment();
                            transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                            cell.state().getBlock().onEntityCollision(actor.world, cell.position(), cell.state(), actor);
                            transaction.requireCurrent();
                            transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                            actor.onInsideBlock(cell.state());
                            transaction.requireCurrent();
                        }
                    });
                    requireEnvironment();
                    transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                    CardinalMoveFinalizer.consumeFireEvents(transaction.actor(), environment.burning(), environment.wet());
                    transaction.requireCurrent();
                }));
                var finalTargetObservation = observeInstalledTarget(transaction, installed, movement.end());
                if (!installedTargetObservation.sameObservation(finalTargetObservation))
                    throw new IllegalStateException("installed Free target collision observation changed during callbacks");
                WorldCollisionCandidates.Snapshot responseBaseline = installedTargetObservation;
                transaction.stageTerminalResponse(provisional -> {
                    var terminalCapture = observeInstalledTarget(transaction, installed, movement.end());
                    if (!responseBaseline.sameObservation(terminalCapture))
                        throw new IllegalStateException("installed Free target collision observation changed before terminal response");
                    return FreeNativeTail.applyFinalResponse(installedEndpoint, movement,
                            movementEvidence, responseBaseline, terminalCapture);
                });
                return new PlayerPhysicalSnapshot(installed.scope(), installed.revision(), placement,
                        motion(actor).multiply(1 / GravityEntityBinder.TICK_SECONDS),
                        Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround,
                                actor.collidedHorizontally, actor.collidedVertically, actor.fallDistance, false)));
            } catch (FreeNativeTail.Stop stopped) {
                if (!stopped.belongsTo(transaction)) throw stopped;
                throw stopped;
            } catch (RuntimeException failure) {
                transaction.recordFailure(failure);
                throw failure;
            }
        }
        private void requireEnvironment() {
            if (!environment.matches(transaction, movement.end()))
                throw new IllegalStateException("capsule trigger geometry or states changed during callbacks");
        }

        static WorldCollisionCandidates.Snapshot observeInstalledTarget(FreeOwnerTransaction transaction,
                PlayerPhysicalSnapshot journal, CapsulePlacementMath.Pose pose) {
            transaction.requireCurrent();
            var captured = WorldCollisionCandidates.capture(transaction, pose.placement().bounds(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
            if (captured instanceof WorldCollisionCandidates.Unavailable unavailable) {
                RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                        "installed Free target observation unavailable: " + unavailable.reason() + ": "
                                + unavailable.detail()));
                throw cause;
            }
            var snapshot = (WorldCollisionCandidates.Snapshot) captured;
            var context = snapshot.capsuleContextFailure(journal, pose.placement().bounds());
            if (context.isPresent()) {
                var unavailable = context.orElseThrow();
                throw unavailable.cause().orElseGet(() -> new IllegalStateException(
                        "installed Free target observation lost geometry: " + unavailable.detail()));
            }
            for (var obstacle : snapshot.boxes()) {
                var fit = transaction.queries().queryReusingGeometry(transaction, pose.capsule(), obstacle, FreeOwnerTransaction.ACCURACY);
                if (fit.relation() == Relation.UNRESOLVED)
                    throw new IllegalStateException("installed Free target collision relation is unresolved: fit="
                            + fit + "; pose=" + pose + "; obstacle=" + obstacle + "; journal=" + journal
                            + "; accepted=" + transaction.accepted() + "; calls=" + transaction.queries().calls()
                            + "; terminal=" + transaction.queries().terminal() + "; totals=" + transaction.queries().work());
                if (fit.relation() == Relation.OVERLAPPING)
                    throw new IllegalStateException("installed Free target capsule overlaps a native collision candidate");
            }
            transaction.requireCurrent();
            return snapshot;
        }
    }

    private static Vec3d motion(Entity actor) { return new Vec3d(actor.motionX, actor.motionY, actor.motionZ); }

    private static boolean sameRaw(Vec3d first, Vec3d second) {
        return Double.doubleToRawLongBits(first.x()) == Double.doubleToRawLongBits(second.x())
                && Double.doubleToRawLongBits(first.y()) == Double.doubleToRawLongBits(second.y())
                && Double.doubleToRawLongBits(first.z()) == Double.doubleToRawLongBits(second.z());
    }
}
