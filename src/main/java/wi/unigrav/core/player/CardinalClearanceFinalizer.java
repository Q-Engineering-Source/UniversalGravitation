package wi.unigrav.core.player;

import java.util.Optional;
import net.minecraft.util.math.AxisAlignedBB;

/** The only mutation point for the verified composite path. A failed tail is never replayed. */
final class CardinalClearanceFinalizer {
    private CardinalClearanceFinalizer() { }

    static CardinalReorientation.Outcome install(CardinalClearancePrepared.Prepared prepared,
            long budgetTick, boolean nativeRestore) {
        var tx = prepared.transaction();
        var target = prepared.target();
        try {
            if (!prepared.current()) return rejected("clearance preparation expired");
            var observed = WorldCollisionCandidates.capture(tx, prepared.window(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, CardinalClearancePrepared.SCAN);
            if (observed instanceof WorldCollisionCandidates.Unavailable failure)
                return new CardinalReorientation.Rejected(CardinalReorientation.Reason.WORLD_UNAVAILABLE,
                        "clearance preinstall recapture " + failure.reason() + ": " + failure.detail(), failure.cause());
            var capture = (WorldCollisionCandidates.Snapshot) observed;
            if (!prepared.capture().sameObservation(capture) || !prepared.current())
                return rejected("clearance world observation changed before install");
            for (var box : capture.boxes()) {
                tx.numeric().charge(6);
                if (CardinalClearancePrepared.overlaps(box, target.bounds()))
                    return new CardinalReorientation.Rejected(CardinalReorientation.Reason.TARGET_OCCUPIED,
                            "clearance full target changed before install");
            }
            var admission = tx.reserve(tx.nextLedgerSequence(), budgetTick, prepared.centerPathUpper());
            if (!admission.accepted()) return rejected("clearance center-path reservation rejected: " + admission.failure());
            tx.requireReservedPath(prepared.centerPathUpper());
            if (!prepared.current()) return rejected("clearance proof expired after reservation");
            var actor = tx.actor();
            var journal = tx.prepareInstallation(target);
            tx.beginInstallation(journal);
            try {
                var bounds = target.bounds();
                actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z()));
                tx.requireOwnerFence();
                actor.posX = target.transport().x();
                actor.posY = target.transport().y();
                actor.posZ = target.transport().z();
                tx.recordInstalled(journal);
            } catch (RuntimeException failure) {
                tx.recordMutationFailure(journal, failure);
                throw failure;
            }
            tx.commit(target, prepared.centerPathUpper());
            // Old contacts are not an observation at the installed target.
            actor.onGround = false;
            actor.collidedHorizontally = false;
            actor.collidedVertically = false;
            var support = CardinalReorientation.observeSupport(tx.world(), actor, journal.journal(),
                    CardinalFrame.Y_POS, nativeRestore);
            if (support.grounded().isEmpty() && support.cause().isPresent()) {
                // The observation has already failed. A later authority fence must not replace its cause.
                var original = support.cause().orElseThrow();
                try { tx.requireCurrent(); }
                catch (RuntimeException changed) {
                    if (changed != original) original.addSuppressed(changed);
                }
                throw original;
            }
            tx.requireCurrent();
            if (support.grounded().isEmpty())
                throw new IllegalStateException("clearance installed support observation unavailable");
            boolean grounded = support.grounded().orElseThrow();
            actor.onGround = grounded;
            var contacts = new PlayerPhysicalSnapshot.Contacts(grounded, false, false, actor.fallDistance, false);
            var snapshot = new PlayerPhysicalSnapshot(journal.journal().scope(), journal.journal().revision(),
                    target, tx.accepted().worldVelocity(), Optional.of(contacts));
            tx.requireCurrent();
            tx.stageTerminalResponse(provisional ->
                    CapsuleMoveFinalizer.applyCardinalIdentityResponse(tx));
            return new CardinalReorientation.Recovered(snapshot, CardinalFrame.Y_POS, grounded);
        } catch (RuntimeException failure) {
            tx.recordFailure(failure);
            if (tx.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                    || tx.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL)
                return new CardinalReorientation.CommittedFailure(tx.installedJournal().orElseThrow(),
                        tx.originalFailure().orElse(failure), tx.installationCustody());
            return new CardinalReorientation.Rejected(CardinalReorientation.Reason.WORLD_UNAVAILABLE,
                    "clearance failed before native installation", Optional.of(failure));
        }
    }
    private static CardinalReorientation.Rejected rejected(String detail) {
        return new CardinalReorientation.Rejected(CardinalReorientation.Reason.ACTOR_CHANGED, detail);
    }
}
