package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.PlayerPhysicsProtocol.BodyReferenceReason;

/** Same-owner capsule lineage and complete terminal native-box observation for Free-to-Cardinal. */
final class FreeReferenceModePrepared {
    static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(16_384, 16, 4_096, 4_096);

    sealed interface Preparation permits Prepared, FreeSuccessor, Unavailable { }
    sealed interface ReferenceReview permits ReferenceEvaluation, Unavailable { }
    record ReferenceEvaluation(PlayerPhysicsStep.FreeStandingProposal proposal,
                               FreeStandingObservation observation) implements ReferenceReview { }
    @FunctionalInterface
    interface ReferencePolicy { ReferenceReview observe(CertifiedReferenceEndpoint endpoint); }
    record FreeSuccessor(ModeTransitionPrepared.Prepared prepared, ReferenceEvaluation evaluation,
                         SourceObservation originalObservation,
                         PlayerBodyReference.FreeReference originalReference) implements Preparation { }
    static final class ReferenceAbsence {
        private final FreeOwnerTransaction transaction;
        private final SourceObservation original;
        private final ReferenceEvaluation endpoint;
        private final PlayerBodyReference.FreeReference reference;
        private ReferenceAbsence(FreeOwnerTransaction transaction, SourceObservation original,
                ReferenceEvaluation endpoint, PlayerBodyReference.FreeReference reference) {
            this.transaction = transaction; this.original = original; this.endpoint = endpoint; this.reference = reference;
        }
        boolean current(FreeOwnerTransaction exact) {
            exact.requireCurrent();
            boolean current = exact == transaction && endpoint.proposal().ownerReferenceCurrent(reference)
                    && original.matches(exact) && endpoint.observation().matches(exact)
                    && endpoint.proposal().ownerReferenceCurrent(reference);
            exact.requireCurrent();
            return current;
        }
        boolean recapture(FreeOwnerTransaction exact) {
            return current(exact) && original.recaptureMatches(exact) && endpoint.observation().recaptureMatches(exact)
                    && current(exact);
        }
        void requireSourcesAfterInstall(FreeOwnerTransaction exact) {
            if (exact != transaction) throw new IllegalStateException("foreign reference absence installation");
            if (original instanceof FreeStandingObservation standing) standing.requireSourcesCurrentAfterInstall();
            endpoint.observation().requireSourcesCurrentAfterInstall();
            if (!endpoint.proposal().ownerReferenceCurrent(reference))
                throw new IllegalStateException("Free absence owner changed during native tail");
            exact.requireCurrent();
        }
    }

    sealed interface CapturePlanning permits CapturePlan, Unavailable { }

    /** Only the ordinary standing capture and the exact server-minted native restore can certify a source. */
    sealed interface SourceObservation permits FreeStandingObservation, NativeRestoreObservation {
        boolean matches(FreeOwnerTransaction transaction);
        boolean recaptureMatches(FreeOwnerTransaction transaction);
    }

    record CapturePlan(Pose startPose, PlayerBodyPlacement resized,
                       PlayerBodyShape.Dimensions targetDimensions,
                       PlayerBodyPlacement previewMoved, CapsulePlacementMath.Translation movementTube,
                       PlayerBodyPlacement previewCardinal, PlayerBodyPlacement previewCorrection,
                       boolean needsArc, PlayerBodyPlacement previewTerminal, Aabb globalWindow)
            implements CapturePlanning { }

    record Unavailable(String detail, BodyReferenceReason reason, Optional<RuntimeException> cause)
            implements Preparation, CapturePlanning, ReferenceReview {
        Unavailable {
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(cause, "cause");
        }
    }

    record Selection(PlayerBodyReference.Effective effective,
                     Optional<PlayerBodyReference.ManualIntent> manualIntent,
                     PlayerBodyReference.CardinalReference installedReference, long requestId) {
        Selection(PlayerBodyReference.Effective effective,
                  Optional<PlayerBodyReference.ManualIntent> manualIntent,
                  PlayerBodyReference.State installedState, long requestId) {
            this(effective, manualIntent,
                    new PlayerBodyReference.CardinalReference(Objects.requireNonNull(installedState, "installedState")),
                    requestId);
        }

        Selection {
            Objects.requireNonNull(effective, "effective");
            Objects.requireNonNull(manualIntent, "manualIntent");
            Objects.requireNonNull(installedReference, "installedReference");
            PlayerBodyReference.State installedState = installedReference.state();
            if (requestId < 0 || effective != installedState.effective()
                    || !manualIntent.equals(installedState.manualIntent()))
                throw new IllegalArgumentException("Free reference selection is internally inconsistent");
            if (effective.source() == PlayerBodyReference.Source.MANUAL && manualIntent.isEmpty())
                throw new IllegalArgumentException("Manual Free target requires the actual saved intent");
        }

        PlayerBodyReference.State installedState() { return installedReference.state(); }
    }

    private sealed interface PhaseProof permits ArcProof, ResizeProof, TranslationProof, MovementProof { }
    private record ArcProof(CapsuleArcValidator.VerifiedArc value) implements PhaseProof { }
    private record ResizeProof(CapsuleResizeValidator.VerifiedResize value) implements PhaseProof { }
    private record TranslationProof(CapsuleTranslationValidator.VerifiedTranslation value) implements PhaseProof { }

    private record MovementProof(CapsuleMovementResolver.Resolved value) implements PhaseProof { }

    static final class Prepared implements Preparation {
        private final FreeOwnerTransaction transaction;
        private final SourceObservation sourceObservation;
        private final PlayerBodyReference.FreeReference sourceReference;
        private final PlayerBodyReference.ManualIntent pendingManual;
        private final ModeTransitionPrepared.TrustedResizeObservation trustedResize;
        private final Selection selection;
        private final PlayerPhysicalSnapshot source;
        private final PlayerBodyPlacement targetCapsule;
        private final PlayerBodyPlacement targetCardinal;
        private final WorldCollisionCandidates.Snapshot capture;
        private final Aabb globalWindow;
        private final CapsulePathSource lineage;
        private final List<PhaseProof> phases;
        private final NativeTargetFit.Observed nativeFit;
        private final double centerPathUpper;
        private final CapsuleMovementResolver.Resolved movement;
        private final ReferenceEvaluation endpointReference;
        private final ProneLocomotion.StandingProfile standingProfile;

        private Prepared(FreeOwnerTransaction transaction, SourceObservation sourceObservation,
                PlayerBodyReference.FreeReference sourceReference, PlayerBodyReference.ManualIntent pendingManual,
                ModeTransitionPrepared.TrustedResizeObservation trustedResize, Selection selection,
                PlayerPhysicalSnapshot source, PlayerBodyPlacement targetCapsule,
                PlayerBodyPlacement targetCardinal, WorldCollisionCandidates.Snapshot capture, Aabb globalWindow,
                CapsulePathSource lineage, List<PhaseProof> phases, NativeTargetFit.Observed nativeFit,
                double centerPathUpper, CapsuleMovementResolver.Resolved movement, ReferenceEvaluation endpointReference,
                ProneLocomotion.StandingProfile standingProfile) {
            this.transaction = transaction;
            this.sourceObservation = sourceObservation;
            this.sourceReference = sourceReference;
            this.pendingManual = pendingManual;
            this.trustedResize = trustedResize;
            this.selection = selection;
            this.source = source;
            this.targetCapsule = targetCapsule;
            this.targetCardinal = targetCardinal;
            this.capture = capture;
            this.globalWindow = globalWindow;
            this.lineage = lineage;
            this.phases = List.copyOf(phases);
            this.nativeFit = nativeFit;
            this.centerPathUpper = centerPathUpper;
            this.movement = movement;
            this.endpointReference = endpointReference;
            this.standingProfile = standingProfile;
        }

        FreeOwnerTransaction transaction() { return transaction; }
        Selection selection() { return selection; }
        PlayerBodyPlacement targetCardinal() { return targetCardinal; }
        NativeTargetFit.Observed nativeFit() { return nativeFit; }
        WorldCollisionCandidates.Snapshot capture() { return capture; }
        Aabb globalWindow() { return globalWindow; }
        double centerPathUpper() { return centerPathUpper; }
        boolean preReserved() { return movement != null; }
        CapsuleMovementResolver.Resolved movement() { return movement; }
        PlayerPhysicsStep.FreeStandingProposal standingProposal() {
            return endpointReference == null ? null : endpointReference.proposal();
        }
        void requireReferenceSourcesAfterInstall() {
            if (standingProfile != null && !standingProfile.current())
                throw new IllegalStateException("Installed prone standing profile changed during callbacks");
            if (trustedResize != null && !trustedResize.bodyProfileCurrent())
                throw new IllegalStateException("installed reference resize physical profile changed during callbacks");
            transaction.requireCurrent();
            if (sourceObservation instanceof FreeStandingObservation standing) standing.requireSourcesCurrentAfterInstall();
            if (endpointReference != null) endpointReference.observation().requireSourcesCurrentAfterInstall();
            transaction.requireCurrent();
        }

        /** The private immutable preparation certifies movement as a prefix of this
         * conversion, rather than pretending its endpoint is the installed Cardinal. */
        FreeOwnerTransaction.ResponseReadback applyInstalledMovementResponse(
                WorldCollisionCandidates.Snapshot installedCapture) {
            if (movement == null) return null;
            transaction.requireCurrent();
            if (transaction.accepted() != source || !movement.belongsTo(transaction)
                    || transaction.installedPlacement().orElse(null) != targetCardinal
                    || lineage.actualSource() != source || lineage.start().placement() != targetCapsule
                    || phases.stream().noneMatch(phase -> phase instanceof MovementProof prefix
                            && prefix.value() == movement))
                throw new IllegalStateException("Cardinal contact response lost its certified movement prefix or installed successor");
            var actor = transaction.actor();
            var response = CapsuleMoveFinalizer.translationResponse(movement, null, transaction,
                    installedCapture);
            response.fence().requireCurrent();
            actor.motionX = response.motion().x(); actor.motionY = response.motion().y(); actor.motionZ = response.motion().z();
            // As in CardinalMoveFinalizer this is the native world-axis mirror,
            // not a replacement for physical support/reference evidence.
            actor.onGround = response.vertical() && movement.requestedDelta().y() < 0;
            actor.collidedHorizontally = response.horizontal();
            actor.collidedVertically = response.vertical();
            actor.collided = response.horizontal() || response.vertical();
            response.fence().requireCurrent();
            Vec3d readback = new Vec3d(actor.motionX, actor.motionY, actor.motionZ);
            if (!sameRaw(response.motion(), readback))
                throw new IllegalStateException("Cardinal terminal translation lost its exact raw readback");
            return new FreeOwnerTransaction.ResponseReadback(response.fence(), readback,
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(actor.onGround, actor.collidedHorizontally,
                            actor.collidedVertically, actor.fallDistance, false)));
        }

        private static boolean sameRaw(Vec3d first, Vec3d second) {
            return Double.doubleToRawLongBits(first.x()) == Double.doubleToRawLongBits(second.x())
                    && Double.doubleToRawLongBits(first.y()) == Double.doubleToRawLongBits(second.y())
                    && Double.doubleToRawLongBits(first.z()) == Double.doubleToRawLongBits(second.z());
        }

        /** Freshly reobserves qualified equipment/material producers at the final owner gate. */
        boolean sourceObservationCurrentForInstall() {
            return sourceObservation.recaptureMatches(transaction)
                    && (endpointReference == null || endpointReference.proposal().current()
                        && endpointReference.observation().recaptureMatches(transaction));
        }

        boolean nativeRestoreFlagsCurrentAfterInstall() {
            return !(sourceObservation instanceof NativeRestoreObservation nativeRestore)
                    || nativeRestore.flagsCurrentAfterInstall(transaction);
        }

        boolean matches(FreeOwnerTransaction exactTransaction, PlayerPhysicsStep.TravelState travel) {
            if (transaction != exactTransaction || source != transaction.accepted()
                    || travel.ownerReference() != sourceReference
                    || !(travel.ownerReference() instanceof PlayerBodyReference.FreeReference)
                    || travel.pendingManualIntent().orElse(null) != pendingManual) return false;
            transaction.requireCurrent();
            boolean matches = (trustedResize == null || transaction.authorizesTrustedResize(trustedResize))
                    && (standingProfile == null || standingProfile.current()
                        && targetCardinal.shape().dimensions().equals(standingProfile.dimensions()))
                    && sourceObservation.matches(transaction) && lineage.valid() && lineage.current()
                    && (endpointReference == null || endpointReference.proposal().current()
                        && endpointReference.observation().matches(transaction))
                    && lineage.actualSource() == source && lineage.capture() == capture
                    && lineage.window() == globalWindow && lineage.work() == transaction.numeric()
                    && lineage.queries() == transaction.queries()
                    && lineage.completedSegments() == phases.size()
                    && lineage.start().placement() == targetCapsule && phasesCurrent()
                    && targetCardinal.shape() instanceof PlayerBodyShape.Cardinal cardinal
                    && cardinal.frame() == selection.effective().frame()
                    && targetCardinal.physicalFeet().isPresent()
                    && travel.ownerReference() == sourceReference
                    && travel.pendingManualIntent().orElse(null) == pendingManual
                    && (endpointReference == null || endpointReference.proposal().current());
            transaction.requireCurrent();
            return matches;
        }

        private boolean phasesCurrent() {
            for (PhaseProof phase : phases) {
                if (phase instanceof ArcProof arc) {
                    if (!arc.value().matchesBound(arc.value().source())) return false;
                } else if (phase instanceof ResizeProof resize) {
                    if (!resize.value().matchesBound(resize.value().source())) return false;
                } else if (phase instanceof TranslationProof translation) {
                    if (!translation.value().matchesBound(translation.value().source())) return false;
                } else if (phase instanceof MovementProof movement) {
                    if (!movement.value().matchesBound(movement.value().source())) return false;
                } else return false;
            }
            return true;
        }
    }

    private FreeReferenceModePrepared() { }

    static Preparation prepare(FreeOwnerTransaction transaction, SourceObservation sourceObservation,
            PlayerBodyReference.FreeReference sourceReference, PlayerBodyReference.ManualIntent pendingManual,
            ModeTransitionPrepared.TrustedResizeObservation trustedResize, Selection selection,
            Vec3d translationDelta) {
        return prepare(transaction, sourceObservation, sourceReference, pendingManual,
                trustedResize, selection, translationDelta, transaction.tick());
    }

    static Preparation prepare(FreeOwnerTransaction transaction, SourceObservation sourceObservation,
            PlayerBodyReference.FreeReference sourceReference, PlayerBodyReference.ManualIntent pendingManual,
            ModeTransitionPrepared.TrustedResizeObservation trustedResize, Selection selection,
            Vec3d translationDelta, long budgetTick) {
        return prepare(transaction, sourceObservation, sourceReference, pendingManual, trustedResize,
                selection, translationDelta, budgetTick, null, null);
    }

    static Preparation prepare(FreeOwnerTransaction transaction, SourceObservation sourceObservation,
            PlayerBodyReference.FreeReference sourceReference, PlayerBodyReference.ManualIntent pendingManual,
            ModeTransitionPrepared.TrustedResizeObservation trustedResize, Selection selection,
            Vec3d translationDelta, long budgetTick, ReferencePolicy referencePolicy,
            PlayerPhysicsStep.FreeStandingProposal originalProposal) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(sourceObservation, "sourceObservation");
        Objects.requireNonNull(sourceReference, "sourceReference");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(translationDelta, "translationDelta");
        try {
            transaction.requireCurrent();
            PlayerPhysicalSnapshot accepted = transaction.accepted();
            PlayerBodyPlacement acceptedPlacement = accepted.placement();
            if (!(acceptedPlacement.shape() instanceof PlayerBodyShape.FreeCapsule))
                return unavailable("Free reference requires the actual accepted FreeCapsule owner",
                        BodyReferenceReason.UNSUPPORTED_SHAPE);
            if (!sourceObservation.matches(transaction))
                return unavailable("Free reference source observation became stale", BodyReferenceReason.ACTOR_CHANGED);
            if (trustedResize != null && !transaction.authorizesTrustedResize(trustedResize))
                return unavailable("trusted Free resize is not bound to this actual owner proposal; "
                        + transaction.trustedResizeAuthorizationDiagnostic(), BodyReferenceReason.ACTOR_CHANGED);

            StoredDyadic.Work numeric = transaction.numeric();
            CardinalFrame frame = selection.effective().frame();
            PlayerBodyShape.Dimensions nativeProfile = null;
            if (sourceObservation instanceof NativeRestoreObservation nativeRestore) {
                nativeProfile = nativeRestore.targetDimensions(transaction);
                var acceptedDimensions = acceptedPlacement.shape().dimensions();
                if (Float.floatToIntBits((float) nativeProfile.width())
                            != Float.floatToIntBits((float) acceptedDimensions.width())
                        || Float.floatToIntBits((float) nativeProfile.height())
                            != Float.floatToIntBits((float) acceptedDimensions.height()))
                    return unavailable("native restore profile changed accepted Free collider dimensions",
                            BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
            }
            var standingProfile = trustedResize == null && nativeProfile == null
                    ? ProneLocomotion.standingProfile(transaction) : null;
            CapturePlanning planning = previewCapture(accepted, trustedResize, frame, translationDelta,
                    nativeProfile, standingProfile, numeric);
            if (planning instanceof Unavailable failure) return failure;
            CapturePlan plan = (CapturePlan) planning;
            Pose startPose = plan.startPose();
            PlayerBodyPlacement resized = plan.resized();
            PlayerBodyShape.Dimensions targetDimensions = plan.targetDimensions();
            CapsulePlacementMath.Translation movementTube = plan.movementTube();
            Aabb globalWindow = plan.globalWindow();
            var captured = WorldCollisionCandidates.capture(transaction, globalWindow,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
            if (captured instanceof WorldCollisionCandidates.Unavailable failure)
                return captureUnavailable(failure, "Free-to-Cardinal capsule capture");
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            transaction.requireCurrent();
            if (capture.capsuleContextFailure(accepted, globalWindow).isPresent())
                return unavailable("Free source capture does not match the exact terminal path window",
                        BodyReferenceReason.ACTOR_CHANGED);

            CapsulePathSource lineage = CapsulePathSource.actualFree(accepted, startPose, capture, globalWindow,
                    FreeOwnerTransaction.ACCURACY, transaction.queries(), numeric);
            var phases = new ArrayList<PhaseProof>(4);
            double centerPathUpper = 0;
            PlayerBodyPlacement current = acceptedPlacement;
            if (trustedResize != null) {
                var resizedResult = CapsuleResizeValidator.validate(lineage, resized);
                if (!(resizedResult instanceof CapsuleResizeValidator.VerifiedResize verifiedResize)) {
                    var failed = (CapsuleResizeValidator.Failed) resizedResult;
                    return resizeFailure(failed, "trusted Free profile resize");
                }
                phases.add(new ResizeProof(verifiedResize));
                double resizePath = CapsulePlacementMath.monotoneCenterTravelUpper(
                        current.center(), resized.center(), numeric);
                centerPathUpper = accumulatePath(centerPathUpper, resizePath, numeric);
                lineage = CapsulePathSource.continueAfter(verifiedResize, numeric);
                current = verifiedResize.target();
                if (!samePlacement(current, resized))
                    return unavailable("trusted Free resize replay changed its exact prepared target",
                            BodyReferenceReason.ACTOR_CHANGED);
            }
            CapsuleMovementResolver.Resolved movement = null;
            FreeCardinalCenterBound.Bound correctionBound = null;
            if (movementTube != null) {
                double movementAllowance = movementTube.requestedDisplacementUpper();
                correctionBound = FreeCardinalCenterBound.prepare(current, frame, targetDimensions,
                        movementAllowance, numeric);
                double reservedUpper = accumulatePath(centerPathUpper, movementAllowance, numeric);
                reservedUpper = accumulatePath(reservedUpper, correctionBound.correctionUpper(), numeric);
                var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick, reservedUpper);
                if (!admission.accepted())
                    return unavailable("Free-to-Cardinal complete path grant rejected: " + admission.failure(),
                            BodyReferenceReason.WORLD_UNAVAILABLE);
                var actualTube = CapsulePlacementMath.chargedTranslationTube(lineage.start(), translationDelta, numeric);
                var movedResult = CapsuleMovementResolver.resolveContinuationWithSlide(transaction, lineage,
                        actualTube, capture, centerPathUpper, movementAllowance);
                if (!(movedResult instanceof CapsuleMovementResolver.Resolved resolved)) {
                    var failure = (CapsuleMovementResolver.Inconclusive) movedResult;
                    return new Unavailable("Free owner-tick certified movement unavailable: " + failure.reason(),
                            BodyReferenceReason.PATH_BLOCKED, failure.cause());
                }
                movement = resolved;
                phases.add(new MovementProof(resolved));
                centerPathUpper = accumulatePath(centerPathUpper, resolved.pathUpper(), numeric);
                lineage = CapsulePathSource.continueAfter(resolved, numeric);
                current = resolved.end().placement();
                if (!correctionBound.containsMovementEnd(current.center()))
                    return unavailable("certified movement endpoint exceeds its correction-bound center interval",
                            BodyReferenceReason.INSTALL_REJECTED);
                // Legacy/native-restore entry points have no endpoint reference policy.
                // Their original exact geometry restriction remains in force.
                if (referencePolicy == null && current != acceptedPlacement)
                    return unavailable("endpoint reference unavailable: resolved query geometry differs from accepted source",
                            BodyReferenceReason.ACTOR_CHANGED);
            }

            ReferenceEvaluation endpointReference = null;
            if (referencePolicy != null) {
                var review = referencePolicy.observe(CertifiedReferenceEndpoint.from(transaction, lineage));
                if (review instanceof Unavailable failure) return failure;
                endpointReference = (ReferenceEvaluation) review;
                if (!endpointReference.proposal().current())
                    return unavailable("endpoint standing proposal changed", BodyReferenceReason.ACTOR_CHANGED);
                if (!endpointReference.proposal().preserves(originalProposal)) {
                    CapsuleResizeValidator.VerifiedResize resizeProof = phases.stream()
                            .filter(p -> p instanceof ResizeProof).map(p -> ((ResizeProof) p).value()).findFirst().orElse(null);
                    var successor = ModeTransitionPrepared.prepareReferenceSuccessor(transaction, trustedResize,
                            resizeProof, movement, lineage,
                            new ReferenceAbsence(transaction, sourceObservation, endpointReference, sourceReference));
                    if (successor instanceof ModeTransitionPrepared.Unavailable failed)
                        return new Unavailable(failed.reason(), BodyReferenceReason.WORLD_UNAVAILABLE, failed.cause());
                    return new FreeSuccessor((ModeTransitionPrepared.Prepared) successor, endpointReference,
                            sourceObservation, sourceReference);
                }
            }

            PlayerBodyPlacement targetCardinal = PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(
                    current, frame, targetDimensions, numeric);
            if (!contains(globalWindow, targetCardinal.bounds()))
                return unavailable("actual resolved Cardinal target exceeds its original capture window",
                        BodyReferenceReason.WORLD_UNAVAILABLE);

            PlayerBodyPlacement correction = current;
            if (!StoredRealizationLimits.sameRaw(current.center(), targetCardinal.center())) {
                var correctionResult = CapsuleTranslationValidator.validateToCenter(lineage, targetCardinal.center());
                if (!(correctionResult instanceof CapsuleTranslationValidator.VerifiedTranslation translation))
                    return translationFailure((CapsuleTranslationValidator.Failed) correctionResult,
                            "Free native-center preparation translation");
                if (correctionBound != null && translation.centerPathUpper() > correctionBound.correctionUpper())
                    return unavailable("actual native-center correction exceeds its stored-recipe interval proof",
                            BodyReferenceReason.INSTALL_REJECTED);
                phases.add(new TranslationProof(translation));
                centerPathUpper = accumulatePath(centerPathUpper, translation.centerPathUpper(), numeric);
                if (movement != null) transaction.requireReservedPath(centerPathUpper);
                lineage = CapsulePathSource.continueAfter(translation, numeric);
                correction = translation.target();
            }
            PlayerBodyPlacement terminal = correction;
            if (!correction.attitude().equals(frame.attitude())) {
                PlayerBodyPlacement arcTarget = new PlayerBodyPlacement(targetCardinal.center(), frame.attitude(),
                        current.shape(), numeric);
                var arcResult = CapsuleArcValidator.validate(lineage, arcTarget, correction.bodyUp());
                if (!(arcResult instanceof CapsuleArcValidator.VerifiedArc arc))
                    return arcFailure((CapsuleArcValidator.Failed) arcResult, "Free-to-Cardinal attitude arc");
                phases.add(new ArcProof(arc));
                lineage = CapsulePathSource.continueAfter(arc, numeric);
                terminal = arcTarget;
            }

            if (!contains(targetCardinal.bounds(), terminal.capsule().bounds()))
                return unavailable("validated terminal capsule is not contained by the exact native Cardinal box",
                        BodyReferenceReason.INSTALL_REJECTED);

            if (referencePolicy != null && endpointReference.observation().pose() != lineage.start()) {
                var review = referencePolicy.observe(CertifiedReferenceEndpoint.from(transaction, lineage));
                if (review instanceof Unavailable failure) return failure;
                var terminalReference = (ReferenceEvaluation) review;
                if (!terminalReference.proposal().preserves(originalProposal))
                    return unavailable("final Cardinal sensor pose no longer qualifies the selected reference",
                            BodyReferenceReason.ACTOR_CHANGED);
                endpointReference = terminalReference;
            }

            var targetFit = NativeTargetFit.prepare(transaction, targetCardinal, NativeTargetFit.SCAN);
            if (targetFit instanceof NativeTargetFit.Rejected rejected)
                return unavailable("native Cardinal target is occupied: " + rejected.reason(),
                        BodyReferenceReason.TARGET_OCCUPIED);
            if (!(targetFit instanceof NativeTargetFit.Observed observed)) {
                var unavailable = (NativeTargetFit.Unavailable) targetFit;
                return nativeUnavailable(unavailable);
            }
            transaction.requireCurrent();
            if (!sourceObservation.recaptureMatches(transaction) || !lineage.valid() || !lineage.current())
                return unavailable("Free-to-Cardinal path or source observation became stale before preparation completed",
                        BodyReferenceReason.ACTOR_CHANGED);
            return new Prepared(transaction, sourceObservation, sourceReference, pendingManual, trustedResize,
                    selection, accepted,
                    terminal, targetCardinal, capture, globalWindow, lineage, phases, observed, centerPathUpper, movement,
                    endpointReference, standingProfile);
        } catch (RuntimeException failure) {
            return new Unavailable("Free-to-Cardinal preparation failed at the owner boundary",
                    BodyReferenceReason.WORLD_UNAVAILABLE, Optional.of(failure));
        }
    }

    static CapturePlanning previewCapture(PlayerPhysicalSnapshot accepted,
            ModeTransitionPrepared.TrustedResizeObservation trustedResize, CardinalFrame frame,
            Vec3d translationDelta, StoredDyadic.Work numeric) {
        return previewCapture(accepted, trustedResize, frame, translationDelta, null, null, numeric);
    }

    private static CapturePlanning previewCapture(PlayerPhysicalSnapshot accepted,
            ModeTransitionPrepared.TrustedResizeObservation trustedResize, CardinalFrame frame,
            Vec3d translationDelta, PlayerBodyShape.Dimensions nativeProfile,
            ProneLocomotion.StandingProfile standingProfile,
            StoredDyadic.Work numeric) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(translationDelta, "translationDelta");
        Objects.requireNonNull(numeric, "numeric");
        if (trustedResize != null && nativeProfile != null)
            return unavailable("native restore cannot borrow ordinary trusted-resize authority",
                    BodyReferenceReason.ACTOR_CHANGED);
        PlayerBodyPlacement acceptedPlacement = accepted.placement();
        if (!(acceptedPlacement.shape() instanceof PlayerBodyShape.FreeCapsule freeShape))
            return unavailable("Free reference requires the actual accepted FreeCapsule owner",
                    BodyReferenceReason.UNSUPPORTED_SHAPE);

        Pose startPose = CapsulePlacementMath.pose(acceptedPlacement, numeric);
        var windows = new ArrayList<Aabb>(12);
        windows.add(acceptedPlacement.bounds());
        PlayerBodyPlacement resized = acceptedPlacement;
        if (trustedResize != null) {
            var dimensions = trustedResize.dimensions();
            var observed = trustedResize.observedGeometry();
            if (!observed.position().equals(acceptedPlacement.transport())
                    || Float.floatToIntBits(observed.width()) != Float.floatToIntBits((float) dimensions.width())
                    || Float.floatToIntBits(observed.height()) != Float.floatToIntBits((float) dimensions.height())
                    || !trustedResize.supportsPhysicalCapsule() || !trustedResize.bodyProfileCurrent())
                return unavailable("trusted Free resize does not match its captured native profile",
                        BodyReferenceReason.ACTOR_CHANGED);
            resized = ModeTransitionPrepared.trustedFreeResizeTarget(acceptedPlacement, trustedResize, numeric);
            var resizeCover = CapsuleResizeEnclosure.prepare(startPose, resized, numeric)
                    .encloseClosedInterval(0, 1, numeric);
            windows.add(ModeTransitionPrepared.allChildResizeCaptureBounds(resizeCover, numeric));
            windows.add(resized.bounds());
        }
        PlayerBodyShape.Dimensions targetDimensions = trustedResize != null
                ? trustedResize.dimensions() : nativeProfile != null ? nativeProfile
                : standingProfile != null ? standingProfile.dimensions() : freeShape.dimensions();
        if (nativeProfile != null
                && (!nativeProfile.supportsCapsule()
                    || Float.floatToIntBits((float) nativeProfile.width())
                        != Float.floatToIntBits((float) freeShape.dimensions().width())
                    || Float.floatToIntBits((float) nativeProfile.height())
                        != Float.floatToIntBits((float) freeShape.dimensions().height())))
            return unavailable("native restore profile differs from the exact accepted Free collider",
                    BodyReferenceReason.NATIVE_CORRECTION_UNCLASSIFIED);
        Pose resizedPose = CapsulePlacementMath.pose(resized, numeric);

        PlayerBodyPlacement previewMoved = resized;
        CapsulePlacementMath.Translation movementTube = null;
        if (!zero(translationDelta)) {
            movementTube = CapsulePlacementMath.chargedTranslationTube(resizedPose, translationDelta, numeric);
            Pose movedPose = CapsulePlacementMath.chargedFullTranslation(movementTube, numeric);
            previewMoved = movedPose.placement();
            windows.add(CapsuleMovementResolver.window(accepted, movementTube));
            windows.add(previewMoved.bounds());
        }

        PlayerBodyPlacement previewCardinal = PlayerBodyPlacement.cardinalFromAcceptedFreeDesiredFoot(
                previewMoved, frame, targetDimensions, numeric);
        Vec3d actualTargetCenter = previewCardinal.center();
        PlayerBodyPlacement previewCorrection = previewMoved;
        CapsulePlacementMath.Translation correctionTube = null;
        if (!StoredRealizationLimits.sameRaw(previewMoved.center(), actualTargetCenter)) {
            Vec3d centerDelta = roundedCenterDelta(previewMoved.center(), actualTargetCenter, numeric);
            correctionTube = CapsulePlacementMath.chargedTranslationTube(
                    CapsulePlacementMath.pose(previewMoved, numeric), centerDelta, numeric);
            Pose correctedEnd = CapsulePlacementMath.chargedFullTranslation(correctionTube, numeric);
            if (!StoredRealizationLimits.sameRaw(correctedEnd.placement().center(), actualTargetCenter))
                return unavailable("native Cardinal target center is not exactly realizable by Free translation",
                        BodyReferenceReason.INSTALL_REJECTED);
            previewCorrection = correctedEnd.placement();
            windows.add(CapsuleMovementResolver.window(accepted, correctionTube));
        }
        boolean needsArc = !previewCorrection.attitude().equals(frame.attitude());
        PlayerBodyPlacement previewTerminal = needsArc
                ? new PlayerBodyPlacement(actualTargetCenter, frame.attitude(),
                        resized.shape(), numeric)
                : previewCorrection;
        if (needsArc) {
            Pose correctionPose = CapsulePlacementMath.pose(previewCorrection, numeric);
            var arcPath = CapsuleArcEnclosure.prepare(correctionPose, previewTerminal,
                    previewCorrection.bodyUp(), numeric);
            windows.add(arcPath.allChildCaptureBounds(numeric));
        }
        windows.add(previewCorrection.bounds());
        windows.add(previewTerminal.bounds());
        if (!contains(previewCardinal.bounds(), previewTerminal.capsule().bounds()))
            return unavailable("actual native Cardinal box does not contain its terminal profile capsule",
                    BodyReferenceReason.INSTALL_REJECTED);
        windows.add(previewCardinal.bounds());
        if (movementTube != null) {
            double allowance = movementTube.requestedDisplacementUpper();
            var bound = FreeCardinalCenterBound.prepare(resized, frame, targetDimensions, allowance, numeric);
            Vec3d cLow = bound.centerMin(), cHigh = bound.centerMax();
            double correction = bound.correctionUpper();
            Vec3d tLow = outwardCenterDomain(cLow, -correction, false, numeric);
            Vec3d tHigh = outwardCenterDomain(cHigh, correction, true, numeric);
            windows.add(CapsulePlacementMath.translationCaptureBoundsOverCenters(resized,
                    cLow, cHigh, allowance, numeric));
            Aabb movedProxy = CapsulePlacementMath.translatedProxyCaptureBounds(resized,
                    cLow, cHigh, CapsuleMovementResolver.MAX_SEGMENTS, numeric);
            windows.add(movedProxy);
            windows.add(CapsulePlacementMath.translationCaptureBoundsOverCenters(resized,
                    cLow, cHigh, correction, numeric));
            // Correction applies once to the proxy produced by any of the bounded slide segments.
            windows.add(roundedProxyCorrectionBounds(movedProxy, correction, numeric));
            windows.add(new Aabb(bound.nativeBoundsLower(), bound.nativeBoundsUpper()));
            if (!resized.attitude().equals(frame.attitude())) {
                var domainTarget = new PlayerBodyPlacement(resized.center(), frame.attitude(),
                        resized.shape(), numeric);
                var domainArc = CapsuleArcEnclosure.prepare(resizedPose, domainTarget,
                        resized.bodyUp(), numeric);
                windows.add(domainArc.captureBoundsOverCenters(tLow, tHigh, numeric));
            }
        }
        Aabb globalWindow = union(windows);
        return new CapturePlan(startPose, resized, targetDimensions, previewMoved, movementTube,
                previewCardinal, previewCorrection, needsArc, previewTerminal, globalWindow);
    }

    private static Vec3d outwardCenterDomain(Vec3d center, double delta, boolean upper,
            StoredDyadic.Work work) {
        double[] values = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double value = axis == 0 ? center.x() : axis == 1 ? center.y() : center.z();
            var exact = StoredDyadic.of(value, work).add(StoredDyadic.of(delta, work), work);
            values[axis] = upper ? exact.roundUp(work) : exact.roundDown(work);
        }
        return new Vec3d(values[0], values[1], values[2]);
    }

    private static Aabb roundedProxyCorrectionBounds(Aabb proxy, double correction, StoredDyadic.Work work) {
        // RN(proxy +/- B) is monotone and includes every actual RN(proxy + RN(target-current)).
        work.charge(24);
        Vec3d low = proxy.min(), high = proxy.max();
        Vec3d a = new Vec3d(low.x() - correction, low.y() - correction, low.z() - correction);
        Vec3d b = new Vec3d(high.x() + correction, high.y() + correction, high.z() + correction);
        if (!Double.isFinite(a.x()) || !Double.isFinite(a.y()) || !Double.isFinite(a.z())
                || !Double.isFinite(b.x()) || !Double.isFinite(b.y()) || !Double.isFinite(b.z()))
            throw new IllegalArgumentException("nonfinite correction proxy domain");
        return new Aabb(a, b);
    }

    private static Vec3d roundedCenterDelta(Vec3d from, Vec3d to, StoredDyadic.Work numeric) {
        return new Vec3d(delta(from.x(), to.x(), numeric), delta(from.y(), to.y(), numeric),
                delta(from.z(), to.z(), numeric));
    }

    private static double delta(double from, double to, StoredDyadic.Work numeric) {
        return StoredDyadic.of(to, numeric).subtract(StoredDyadic.of(from, numeric), numeric)
                .roundNearest(numeric);
    }

    private static double accumulatePath(double accumulated, double phase, StoredDyadic.Work numeric) {
        numeric.charge(3);
        if (!Double.isFinite(accumulated) || accumulated < 0 || !Double.isFinite(phase) || phase < 0)
            throw new IllegalArgumentException("Free-to-Cardinal center path is not finite and non-negative");
        if (phase == 0) return accumulated;
        double upper = Math.nextUp(accumulated + phase);
        if (!Double.isFinite(upper)) throw new IllegalArgumentException("Free-to-Cardinal path overflow");
        return upper;
    }

    private static boolean samePlacement(PlayerBodyPlacement first, PlayerBodyPlacement second) {
        return first.shape().equals(second.shape()) && first.attitude().equals(second.attitude())
                && StoredRealizationLimits.sameRaw(first.center(), second.center())
                && StoredRealizationLimits.sameRaw(first.transport(), second.transport())
                && StoredRealizationLimits.sameRaw(first.bounds().min(), second.bounds().min())
                && StoredRealizationLimits.sameRaw(first.bounds().max(), second.bounds().max())
                && Objects.equals(first.storedCapsuleGeometry(), second.storedCapsuleGeometry());
    }

    private static boolean zero(Vec3d value) {
        return value.x() == 0 && value.y() == 0 && value.z() == 0;
    }

    private static Aabb union(List<Aabb> boxes) {
        if (boxes.isEmpty()) throw new IllegalArgumentException("Free-to-Cardinal window requires path geometry");
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Aabb box : boxes) {
            minX = Math.min(minX, box.min().x()); minY = Math.min(minY, box.min().y());
            minZ = Math.min(minZ, box.min().z()); maxX = Math.max(maxX, box.max().x());
            maxY = Math.max(maxY, box.max().y()); maxZ = Math.max(maxZ, box.max().z());
        }
        return new Aabb(new Vec3d(minX, minY, minZ), new Vec3d(maxX, maxY, maxZ));
    }

    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    private static Unavailable arcFailure(CapsuleArcValidator.Failed failure, String phase) {
        BodyReferenceReason reason = switch (failure.failure()) {
            case ACTUAL_OVERLAP, ARC_OVERLAP -> BodyReferenceReason.PATH_BLOCKED;
            case CONTEXT_CHANGED, INVALID_INPUT -> BodyReferenceReason.ACTOR_CHANGED;
            default -> BodyReferenceReason.WORLD_UNAVAILABLE;
        };
        Optional<RuntimeException> cause = failure.budgetCause() instanceof RuntimeException runtime
                ? Optional.of(runtime) : Optional.empty();
        return new Unavailable(phase + " failed: " + failure.failure(), reason, cause);
    }

    private static Unavailable translationFailure(CapsuleTranslationValidator.Failed failure, String phase) {
        BodyReferenceReason reason = switch (failure.failure()) {
            case ACTUAL_OVERLAP, PATH_NOT_CERTIFIED -> BodyReferenceReason.PATH_BLOCKED;
            case CONTEXT_CHANGED, INVALID_INPUT, TARGET_NOT_REALIZED -> BodyReferenceReason.ACTOR_CHANGED;
            default -> BodyReferenceReason.WORLD_UNAVAILABLE;
        };
        Optional<RuntimeException> cause = failure.budgetCause() instanceof RuntimeException runtime
                ? Optional.of(runtime) : Optional.empty();
        return new Unavailable(phase + " failed: " + failure.failure(), reason, cause);
    }

    private static Unavailable resizeFailure(CapsuleResizeValidator.Failed failure, String phase) {
        BodyReferenceReason reason = switch (failure.failure()) {
            case ACTUAL_OVERLAP, RESIZE_OVERLAP -> BodyReferenceReason.PATH_BLOCKED;
            case CONTEXT_CHANGED -> BodyReferenceReason.ACTOR_CHANGED;
            case INVALID_INPUT, PROFILE_NOT_CERTIFIED -> BodyReferenceReason.INSTALL_REJECTED;
            default -> BodyReferenceReason.WORLD_UNAVAILABLE;
        };
        Optional<RuntimeException> cause = failure.budgetCause() instanceof RuntimeException runtime
                ? Optional.of(runtime) : Optional.empty();
        return new Unavailable(phase + " failed: " + failure.failure(), reason, cause);
    }

    private static Unavailable captureUnavailable(WorldCollisionCandidates.Unavailable failure, String phase) {
        return new Unavailable(phase + " unavailable: " + failure.reason() + ": " + failure.detail(),
                BodyReferenceReason.WORLD_UNAVAILABLE, failure.cause());
    }

    private static Unavailable nativeUnavailable(NativeTargetFit.Unavailable failure) {
        BodyReferenceReason reason = switch (failure.reason()) {
            case ACCEPTED_SNAPSHOT_CHANGED, TARGET_CHANGED, FOREIGN_TRANSACTION -> BodyReferenceReason.ACTOR_CHANGED;
            default -> BodyReferenceReason.WORLD_UNAVAILABLE;
        };
        return new Unavailable("native Cardinal fit unavailable: " + failure.reason() + ": " + failure.detail(),
                reason, failure.cause());
    }

    private static Unavailable unavailable(String detail, BodyReferenceReason reason) {
        return new Unavailable(detail, reason, Optional.empty());
    }
}
