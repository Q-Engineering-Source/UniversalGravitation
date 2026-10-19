package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.player.PlayerPhysicsProtocol.BodyReferenceReason;

/** Single Free-to-Cardinal installation, center-path reservation, and actual Cardinal environment tail. */
final class FreeReferenceModeFinalizer {
    private static final CollisionQueryFootprint.Limits SCAN = FreeReferenceModePrepared.SCAN;

    sealed interface Result permits Applied, Unavailable, CommittedFailure { }
    record Applied(PlayerPhysicalSnapshot snapshot) implements Result { }
    record Unavailable(String detail, BodyReferenceReason reason, Optional<RuntimeException> cause)
            implements Result { }
    record CommittedFailure(PlayerPhysicalSnapshot snapshot, RuntimeException cause,
                            Optional<FreeOwnerTransaction.InstallationCustody> custody) implements Result { }

    private FreeReferenceModeFinalizer() { }

    static Result install(FreeReferenceModePrepared.Prepared prepared,
            PlayerPhysicsStep.TravelState travel, long budgetTick) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(travel, "travel");
        FreeOwnerTransaction transaction = prepared.transaction();
        PlayerBodyPlacement target = prepared.targetCardinal();
        try {
            if (!prepared.matches(transaction, travel))
                return unavailable("Free-to-Cardinal preparation became stale before installation",
                        BodyReferenceReason.ACTOR_CHANGED);

            var recapturedPath = WorldCollisionCandidates.capture(transaction, prepared.globalWindow(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
            if (recapturedPath instanceof WorldCollisionCandidates.Unavailable failure)
                return captureUnavailable(failure, "Free-to-Cardinal path recapture");
            var livePath = (WorldCollisionCandidates.Snapshot) recapturedPath;
            if (!prepared.capture().sameObservation(livePath)
                    || livePath.capsuleContextFailure(transaction.accepted(), prepared.globalWindow()).isPresent()
                    || !prepared.matches(transaction, travel))
                return unavailable("Free-to-Cardinal path observation changed before installation",
                        BodyReferenceReason.ACTOR_CHANGED);

            NativeTargetFit.Result recapturedFit = prepared.nativeFit().recapture(transaction, target);
            if (recapturedFit instanceof NativeTargetFit.Rejected rejected)
                return unavailable("native Cardinal target is occupied: " + rejected.reason(),
                        BodyReferenceReason.TARGET_OCCUPIED);
            if (!(recapturedFit instanceof NativeTargetFit.Observed observedFit)) {
                var failure = (NativeTargetFit.Unavailable) recapturedFit;
                return nativeUnavailable(failure);
            }
            transaction.requireCurrent();
            if (!prepared.matches(transaction, travel))
                return unavailable("Free-to-Cardinal proof changed after native fit recapture",
                        BodyReferenceReason.ACTOR_CHANGED);

            if (!prepared.preReserved()) {
                var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick,
                        prepared.centerPathUpper());
                if (!admission.accepted())
                    return unavailable("Free-to-Cardinal center-path grant rejected: " + admission.failure(),
                            BodyReferenceReason.WORLD_UNAVAILABLE);
            }
            if (prepared.movement() != null) {
                var failure = prepared.movement().recaptureFailure(transaction);
                if (failure.isPresent())
                    return captureUnavailable(failure.orElseThrow(), "Free-to-Cardinal movement-prefix recapture");
            }
            transaction.requireReservedPath(prepared.centerPathUpper());
            if (!prepared.sourceObservationCurrentForInstall() || !prepared.matches(transaction, travel))
                return unavailable("Free-to-Cardinal proof changed after center-path reservation",
                        BodyReferenceReason.ACTOR_CHANGED);

            EntityPlayerMP actor = transaction.actor();
            var dimensions = target.shape().dimensions();
            Aabb bounds = target.bounds();
            Vec3d transport = target.transport();
            var journal = transaction.prepareInstallation(target);
            transaction.beginInstallation(journal);
            try {
                actor.width = (float) dimensions.width();
                actor.height = (float) dimensions.height();
                transaction.installEffectiveEyeHeight(dimensions.eyeHeight());
                actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
                transaction.requireOwnerFence();
                actor.posX = transport.x();
                actor.posY = transport.y();
                actor.posZ = transport.z();
                transaction.recordInstalled(journal);
            } catch (RuntimeException failure) {
                transaction.recordMutationFailure(journal, failure);
                throw failure;
            }

            PlayerPhysicalSnapshot installedJournal = journal.journal();
            transaction.commit(target, prepared.centerPathUpper());
            // The native geometry and its sole successor are now authoritative. Keep the
            // owner-reference paired with that journal even if the environment tail fails.
            transaction.installCommittedOwnerReference(prepared.selection().installedReference());
            WorldCollisionCandidates.Snapshot beforeTail = observeInstalledTarget(transaction, installedJournal, target);
            prepared.applyInstalledMovementResponse(beforeTail);
            transaction.runNativeEnvironment(installedJournal, () -> runCardinalEnvironment(transaction, target, actor));
            WorldCollisionCandidates.Snapshot afterTail = observeInstalledTarget(transaction, installedJournal, target);
            if (!beforeTail.sameNativeObservation(afterTail))
                throw new IllegalStateException("installed Cardinal collision observation changed during callbacks");
            requireExactTargetGeometry(transaction, target);
            transaction.requireCurrent();
            if (!transaction.matchesCommittedOwnerReference(prepared.selection().installedReference()))
                throw new IllegalStateException("installed Cardinal target lost its exact typed owner successor");
            if (!prepared.nativeRestoreFlagsCurrentAfterInstall())
                throw new IllegalStateException("native mode flags changed during the committed Free restore tail");
            // Native flag getters may re-enter a correction or owner callback. Recheck the exact
            // installed owner reference after those live observations before reporting success.
            transaction.requireCurrent();

            prepared.requireReferenceSourcesAfterInstall();
            requireExactTargetGeometry(transaction, target);
            transaction.requireCurrent();
            WorldCollisionCandidates.Snapshot responseBaseline = afterTail;
            transaction.stageTerminalResponse(provisional -> {
                WorldCollisionCandidates.Snapshot terminalCapture =
                        observeInstalledTarget(transaction, installedJournal, target);
                if (!responseBaseline.sameNativeObservation(terminalCapture))
                    throw new IllegalStateException("installed Cardinal collision observation changed before terminal response");
                requireExactTargetGeometry(transaction, target);
                if (!transaction.matchesCommittedOwnerReference(prepared.selection().installedReference())
                        || !prepared.nativeRestoreFlagsCurrentAfterInstall())
                    throw new IllegalStateException("installed Cardinal terminal response lost its owner or native flags");
                prepared.requireReferenceSourcesAfterInstall();
                requireExactTargetGeometry(transaction, target);
                transaction.requireCurrent();
                FreeOwnerTransaction.ResponseReadback response =
                        prepared.applyInstalledMovementResponse(terminalCapture);
                return response == null
                        ? CapsuleMoveFinalizer.applyCardinalIdentityResponse(transaction) : response;
            });
            Vec3d velocity = new Vec3d(actor.motionX, actor.motionY, actor.motionZ)
                    .multiply(1 / GravityEntityBinder.TICK_SECONDS);
            var contacts = new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                    actor.collidedVertically, actor.fallDistance, false);
            PlayerPhysicalSnapshot snapshot = new PlayerPhysicalSnapshot(installedJournal.scope(),
                    installedJournal.revision(), target, velocity, Optional.of(contacts));
            return new Applied(snapshot);
        } catch (RuntimeException failure) {
            transaction.recordFailure(failure);
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED)
                return new CommittedFailure(transaction.installedJournal().orElseThrow(),
                        transaction.originalFailure().orElse(failure), transaction.installationCustody());
            if (transaction.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL)
                return new CommittedFailure(transaction.installedJournal().orElseThrow(),
                        transaction.originalFailure().orElse(failure), transaction.installationCustody());
            return new Unavailable("Free-to-Cardinal finalization failed before native installation",
                    BodyReferenceReason.WORLD_UNAVAILABLE, Optional.of(failure));
        }
    }

    private static WorldCollisionCandidates.Snapshot observeInstalledTarget(FreeOwnerTransaction transaction,
            PlayerPhysicalSnapshot journal, PlayerBodyPlacement target) {
        transaction.requireCurrent();
        var captured = WorldCollisionCandidates.capture(transaction, target.bounds(),
                CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT, SCAN, transaction.resources().environmentObservationWork());
        if (captured instanceof WorldCollisionCandidates.Unavailable unavailable) {
            RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                    "installed Cardinal box observation unavailable: " + unavailable.reason() + ": "
                            + unavailable.detail()));
            throw cause;
        }
        var snapshot = (WorldCollisionCandidates.Snapshot) captured;
        if (snapshot.captureTick() != transaction.tick()
                || !snapshot.footprint().requestedBounds().equals(target.bounds())
                || !snapshot.footprint().nativeBounds().equals(target.bounds())
                || !snapshot.actorId().equals(journal.scope().playerId()))
            throw new IllegalStateException("installed Cardinal observation lost its exact target window or actor identity");
        requireExactTargetGeometry(transaction, target);
        if (!snapshot.boxes().isEmpty())
            throw new IllegalStateException("installed Cardinal AABB is no longer clear against native collision boxes");
        transaction.requireCurrent();
        return snapshot;
    }

    private static void runCardinalEnvironment(FreeOwnerTransaction transaction,
            PlayerBodyPlacement target, EntityPlayerMP actor) {
        if (actor.isAddedToWorld() && !actor.world.isRemote) {
            transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
            actor.world.updateEntityWithOptionalForce(actor, false);
        }
        transaction.requireCurrent();
        transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
        CardinalBlockMotion.run(actor, target.attitude(), actor::doBlockCollisions);
        transaction.requireCurrent();
        requireExactTargetGeometry(transaction, target);
        boolean burning = actor.world.isFlammableWithin(actor.getEntityBoundingBox().shrink(0.001));
        boolean wet = actor.isWet();
        transaction.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.DISPATCH);
        CardinalMoveFinalizer.consumeFireEvents(actor, burning, wet);
        transaction.requireCurrent();
    }

    private static void requireExactTargetGeometry(FreeOwnerTransaction transaction, PlayerBodyPlacement target) {
        if (!hasExactTargetGeometry(transaction, target))
            throw new IllegalStateException("Free-to-Cardinal installation lost its exact native target geometry");
    }

    private static boolean hasExactTargetGeometry(FreeOwnerTransaction transaction, PlayerBodyPlacement target) {
        var actual = WorldCollisionCandidates.ActorGeometry.read(transaction.actor());
        var dimensions = target.shape().dimensions();
        return actual.position().equals(target.transport()) && actual.bounds().equals(target.bounds())
                && Float.floatToIntBits(actual.width()) == Float.floatToIntBits((float) dimensions.width())
                && Float.floatToIntBits(actual.height()) == Float.floatToIntBits((float) dimensions.height())
                && transaction.actor().getEyeHeight() == dimensions.eyeHeight();
    }

    private static Unavailable captureUnavailable(WorldCollisionCandidates.Unavailable unavailable, String stage) {
        return new Unavailable(stage + " unavailable: " + unavailable.reason() + ": " + unavailable.detail(),
                BodyReferenceReason.WORLD_UNAVAILABLE, unavailable.cause());
    }

    private static Unavailable nativeUnavailable(NativeTargetFit.Unavailable unavailable) {
        BodyReferenceReason reason = switch (unavailable.reason()) {
            case ACCEPTED_SNAPSHOT_CHANGED, TARGET_CHANGED, FOREIGN_TRANSACTION -> BodyReferenceReason.ACTOR_CHANGED;
            case TARGET_OCCUPIED -> BodyReferenceReason.TARGET_OCCUPIED;
            default -> BodyReferenceReason.WORLD_UNAVAILABLE;
        };
        return new Unavailable("native Cardinal target recapture unavailable: " + unavailable.reason() + ": "
                + unavailable.detail(), reason, unavailable.cause());
    }

    private static Unavailable unavailable(String detail, BodyReferenceReason reason) {
        return new Unavailable(detail, reason, Optional.empty());
    }
}
