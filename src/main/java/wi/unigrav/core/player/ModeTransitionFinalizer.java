package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.GravityEntityBinder;

/** The sole installation and post-install consumer for a prepared mode transition. */
final class ModeTransitionFinalizer {
    sealed interface Result permits Applied, Unavailable, CommittedFailure, PlayerPhysicsStep.CommittedTailStop { }
    record Applied(PlayerPhysicalSnapshot snapshot) implements Result { }
    record Unavailable(String reason, Optional<RuntimeException> cause) implements Result { }
    record CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause,
                            Optional<FreeOwnerTransaction.InstallationCustody> custody) implements Result { }

    private ModeTransitionFinalizer() { }

    static Result install(ModeTransitionPrepared.Prepared prepared, long budgetTick) {
        Objects.requireNonNull(prepared);
        FreeOwnerTransaction transaction = prepared.transaction();
        ModeTransitionPrepared.Request request = prepared.request();
        try {
            if (!prepared.matches(transaction, request))
                return new Unavailable("mode preparation became stale before installation", Optional.empty());
            var recaptured = WorldCollisionCandidates.capture(transaction, prepared.globalWindow(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                    new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096), transaction.resources().environmentObservationWork());
            if (recaptured instanceof WorldCollisionCandidates.Unavailable unavailable)
                return captureUnavailable(unavailable, "pre-install mode recapture");
            var liveCapture = (WorldCollisionCandidates.Snapshot) recaptured;
            if (!prepared.capture().sameObservation(liveCapture) || !prepared.matches(transaction, request))
                return new Unavailable("mode path world capture changed before installation", Optional.empty());

            if (!prepared.preReserved()) {
                var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick, prepared.centerPathUpper());
                if (!admission.accepted())
                    return new Unavailable("mode center-path grant rejected: " + admission.failure(), Optional.empty());
            }
            if (prepared.movement() != null) {
                var failure = prepared.movement().recaptureFailure(transaction);
                if (failure.isPresent()) return captureUnavailable(failure.orElseThrow(), "pre-install movement recapture");
            }
            transaction.requireReservedPath(prepared.centerPathUpper());
            if (!prepared.matches(transaction, request))
                return new Unavailable("mode preparation changed after center-path reservation", Optional.empty());
            if (!prepared.environment().matches(transaction, prepared.targetPose()))
                return new Unavailable("target capsule environment changed before installation", Optional.empty());
            if (!prepared.referenceCurrentForInstall() || !prepared.matches(transaction, request))
                return new Unavailable("Free reference absence observation changed before installation", Optional.empty());

        EntityPlayerMP actor = transaction.actor();
            var target = prepared.target();
            var dimensions = target.shape().dimensions();
            var bounds = target.bounds();
            var transport = target.transport();
            var journal = target.shape() instanceof PlayerBodyShape.FreeCapsule
                    ? transaction.prepareInstallation(target,
                            CertifiedReferenceEndpoint.from(transaction, prepared.lineage()))
                    : transaction.prepareInstallation(target);
            var movementEvidence = prepared.movement() != null
                    && target.shape() instanceof PlayerBodyShape.FreeCapsule
                    ? prepared.movement().sealForInstallation(transaction, journal) : null;
            transaction.beginInstallation(journal);
            try {
                if (request instanceof MobilityTransitionRequest) {
                    // Mobility changes shape, not accepted momentum. Native motion may still lag
                    // an adopted snapshot, so bind this write to the journal and its owner fence.
                    var velocity = prepared.integratedVelocity() == null
                            ? prepared.source().worldVelocity() : prepared.integratedVelocity();
                    actor.motionX = velocity.x() * GravityEntityBinder.TICK_SECONDS;
                    actor.motionY = velocity.y() * GravityEntityBinder.TICK_SECONDS;
                    actor.motionZ = velocity.z() * GravityEntityBinder.TICK_SECONDS;
                    actor.onGround = false;
                    actor.collidedHorizontally = false;
                    actor.collidedVertically = false;
                }
                actor.width = (float) dimensions.width();
                actor.height = (float) dimensions.height();
                FreeOwnerTransaction.installEffectiveEyeHeight(actor, dimensions.eyeHeight());
                actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
                actor.posX = transport.x(); actor.posY = transport.y(); actor.posZ = transport.z();
                transaction.recordInstalled(journal);
            } catch (RuntimeException failure) {
                transaction.recordMutationFailure(journal, failure);
                throw failure;
            }

            var installedJournal = journal.journal();
            var targetObservation = CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                    transaction, installedJournal, prepared.targetPose());
            transaction.commit(target, prepared.centerPathUpper());
            if (target.shape() instanceof PlayerBodyShape.FreeCapsule) {
                var installedEndpoint = transaction.installedFreeEndpoint();
                transaction.runNativeEnvironment(installedJournal, () -> FreeNativeTail.run(installedEndpoint,
                        prepared.movement(), movementEvidence, targetObservation,
                        () -> runInstalledEnvironment(transaction, prepared, actor)));
            } else {
                if (prepared.movement() != null)
                    CapsuleMoveFinalizer.applyCertifiedTranslationResponse(transaction,
                            prepared.movement(), null, targetObservation);
                transaction.runNativeEnvironment(installedJournal, () -> runInstalledEnvironment(transaction, prepared, actor));
            }
            var finalObservation = CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                    transaction, installedJournal, prepared.targetPose());
            if (!targetObservation.sameObservation(finalObservation))
                throw new IllegalStateException("installed mode target world observation changed during callbacks");
            if (!prepared.environment().matches(transaction, prepared.targetPose()))
                throw new IllegalStateException("installed mode target environment changed during callbacks");
            transaction.requireCurrent();
            requireExactTargetGeometry(transaction, target);
            prepared.requireReferenceSourcesAfterInstall();
            transaction.requireCurrent();
            requireExactTargetGeometry(transaction, target);
            WorldCollisionCandidates.Snapshot responseBaseline = finalObservation;
            transaction.stageTerminalResponse(provisional -> {
                var terminalObservation = CapsuleMoveFinalizer.Ready.observeInstalledTarget(
                        transaction, installedJournal, prepared.targetPose());
                if (!responseBaseline.sameObservation(terminalObservation)
                        || !prepared.environment().matches(transaction, prepared.targetPose()))
                    throw new IllegalStateException("installed mode target changed before its terminal response");
                requireExactTargetGeometry(transaction, target);
                if (target.shape() instanceof PlayerBodyShape.FreeCapsule) {
                    var installedEndpoint = transaction.installedFreeEndpoint();
                    return FreeNativeTail.applyFinalResponse(installedEndpoint, prepared.movement(), movementEvidence,
                            targetObservation, terminalObservation);
                }
                if (prepared.movement() != null)
                    return CapsuleMoveFinalizer.applyCertifiedTranslationResponse(transaction,
                            prepared.movement(), null, terminalObservation);
                return CapsuleMoveFinalizer.applyCardinalIdentityResponse(transaction);
            });
            Vec3d velocity = new Vec3d(actor.motionX, actor.motionY, actor.motionZ)
                    .multiply(1 / GravityEntityBinder.TICK_SECONDS);
            var snapshot = new PlayerPhysicalSnapshot(installedJournal.scope(), installedJournal.revision(), target,
                    velocity, Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround,
                            actor.collidedHorizontally, actor.collidedVertically, actor.fallDistance, false)));
            return new Applied(snapshot);
        } catch (FreeNativeTail.Stop stopped) {
            if (!stopped.belongsTo(transaction)) throw stopped;
            return stopped.result();
        } catch (RuntimeException failure) {
            transaction.recordFailure(failure);
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED) {
                PlayerPhysicalSnapshot journal = transaction.installedJournal().orElseThrow();
                return new CommittedFailure(journal, transaction.originalFailure().orElse(failure),
                        transaction.installationCustody());
            }
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL)
                return new CommittedFailure(transaction.installedJournal().orElseThrow(),
                        transaction.originalFailure().orElse(failure), transaction.installationCustody());
            return new Unavailable("mode transition failed before native installation", Optional.of(failure));
        }
    }

    private static void runInstalledEnvironment(FreeOwnerTransaction transaction,
            ModeTransitionPrepared.Prepared prepared, Entity actor) {
        if (actor.isAddedToWorld() && !actor.world.isRemote) {
            transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
            actor.world.updateEntityWithOptionalForce(actor, false);
        }
        transaction.requireCurrent();
        CardinalBlockMotion.run(actor, prepared.target().attitude(), () -> {
            for (var cell : prepared.environment().cells()) {
                if (cell.voxel().relation() != Relation.OVERLAPPING) continue;
                if (!prepared.environment().matches(transaction, prepared.targetPose()))
                    throw new IllegalStateException("mode target environment changed before block callback");
                transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                cell.state().getBlock().onEntityCollision(actor.world, cell.position(), cell.state(), actor);
                transaction.requireCurrent();
                transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
                actor.onInsideBlock(cell.state());
                transaction.requireCurrent();
            }
        });
        if (!prepared.environment().matches(transaction, prepared.targetPose()))
            throw new IllegalStateException("mode target environment changed during block callbacks");
        transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
        CardinalMoveFinalizer.consumeFireEvents(transaction.actor(),
                prepared.environment().burning(), prepared.environment().wet());
        transaction.requireCurrent();
    }

    private static void requireExactTargetGeometry(FreeOwnerTransaction transaction, PlayerBodyPlacement target) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(transaction.actor());
        var dimensions = target.shape().dimensions();
        if (!actual.position().equals(target.transport()) || !actual.bounds().equals(target.bounds())
                || Float.floatToIntBits(actual.width()) != Float.floatToIntBits((float) dimensions.width())
                || Float.floatToIntBits(actual.height()) != Float.floatToIntBits((float) dimensions.height())
                || !transaction.matchesEffectiveEye(target, transaction.actor().getEyeHeight()))
            throw new IllegalStateException("mode installation lost its exact native target geometry fence");
    }

    private static Unavailable captureUnavailable(WorldCollisionCandidates.Unavailable unavailable, String stage) {
        RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                stage + " unavailable: " + unavailable.reason() + ": " + unavailable.detail()));
        return new Unavailable(stage + " unavailable: " + unavailable.reason() + ": " + unavailable.detail(),
                Optional.of(cause));
    }
}
