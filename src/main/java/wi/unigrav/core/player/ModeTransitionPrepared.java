package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.entity.player.EntityPlayerMP;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.UnigravMod;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/** Private, same-owner proof lineage for one requested Cardinal-to-Free conversion. */
final class ModeTransitionPrepared {
    private static final Marker FREE_RESIZE_TRACE = MarkerManager.getMarker("UG_FREE_RESIZE_TRACE");
    static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    // The helper below has 36 explicit binary64 arithmetic/rounding/min/max operations, 52 finite
    // checks, and 11 other scalar/canonical/AABB-order comparisons. Its two loops each run exactly
    // three times, with fixed endpoint and six-component arrays; there is no input-sized work. The
    // 256 same-Work charge covers those 99 enumerated operations/checks plus bounded array, guard,
    // component-selection and loop work.
    private static final int ARC_CAPTURE_WINDOW_WORK = 256;
    private static final int RESIZE_CAPTURE_WINDOW_WORK = 256;

    sealed interface Request permits PlayerAttitudeRequest, MobilityTransitionRequest, ProneTransitionRequest {
        Rotation3d rawDesiredQ();
    }

    sealed interface Preparation permits Prepared, Unavailable, Occupied, RetainedAttitude { }
    /** A rejected input rotation remains pending; only the separately proved motion can commit. */
    record RetainedAttitude(Prepared prepared, String rejection) implements Preparation {
        RetainedAttitude {
            Objects.requireNonNull(prepared); Objects.requireNonNull(rejection);
            if (!(prepared.request() instanceof PlayerAttitudeRequest || prepared.request() instanceof ProneTransitionRequest)
                    || !sameRawAttitude(prepared.source().placement().attitude(), prepared.target().attitude())
                    || prepared.phases().stream().anyMatch(phase -> phase instanceof ArcPhase))
                throw new IllegalArgumentException("retained attitude must preserve its exact accepted orientation");
        }
    }
    record Unavailable(String reason, Optional<RuntimeException> cause) implements Preparation {
        Unavailable { Objects.requireNonNull(reason); Objects.requireNonNull(cause); }
    }
    record Occupied(CardinalSource source, NativeCardinalBodyProfile.TargetOccupied proof) implements Preparation {
        void requireRetainedCardinalCurrent(FreeOwnerTransaction transaction) {
            source.requireCurrent();
            proof.requireRetainedCardinalCurrent(transaction);
            source.requireCurrent();
        }
    }
    sealed interface PhaseProof permits ArcPhase, ResizePhase, TranslationPhase, MovementPhase, RecoveryPhase { }
    record RecoveryPhase(CapsuleDepenetrationPrepared.Verified proof) implements PhaseProof { }
    record ArcPhase(CapsuleArcValidator.VerifiedArc proof) implements PhaseProof { }
    record ResizePhase(CapsuleResizeValidator.VerifiedResize proof) implements PhaseProof { }
    record MovementPhase(CapsuleMovementResolver.Resolved proof) implements PhaseProof { }
    record TranslationPhase(CapsuleTranslationValidator.VerifiedTranslation proof) implements PhaseProof { }
    private record CardinalToFreeGeometry(PlayerBodyPlacement initialPlacement,
            PlayerBodyPlacement targetPlacement, CapsulePlacementMath.Pose initialPose,
            CapsulePlacementMath.Pose targetPose, Aabb globalWindow,
            WorldCollisionCandidates.ActorGeometry actorGeometry,
            CardinalContainedCapsuleSource.Prepared containedSource,
            NativeCardinalBodyProfile.Observation nativeProfile) {
        CardinalCapsuleContainment.Contained containment() { return containedSource == null ? null : containedSource.containment(); }
    }
    /** Pure geometry prelude, not collision, gait, movement or installation authority. */
    static final class CardinalSource {
        private final FreeOwnerTransaction transaction;
        private final Request request;
        private final CardinalToFreeGeometry geometry;
        private final long budgetTick;
        private CardinalSource(FreeOwnerTransaction transaction, Request request, CardinalToFreeGeometry geometry, long budgetTick) {
            this.transaction = transaction; this.request = request; this.geometry = geometry; this.budgetTick = budgetTick;
        }
        Vec3d bodyUp() { requireCurrent(); return geometry.initialPlacement().bodyUp(); }
        private void requireCurrent() {
            transaction.requireCurrent();
            if (!isCurrentRequest(request)
                    || geometry.initialPose().placement() != geometry.initialPlacement()
                    || (geometry.nativeProfile() == null
                        ? !geometry.containment().matches(transaction.accepted(), geometry.initialPlacement(), transaction.numeric())
                        : !geometry.nativeProfile().matches(transaction.accepted())))
                throw new IllegalStateException("Cardinal-to-Free geometry prelude lost its exact source");
            transaction.requireCurrent();
        }
    }

    static CardinalSource prepareCardinalSource(FreeOwnerTransaction transaction, Request request, long budgetTick) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(request);
        transaction.requireCurrent();
        if (!(transaction.accepted().placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
            throw new IllegalArgumentException("Cardinal-to-Free prelude requires actual accepted Cardinal geometry");
        if (!isCurrentRequest(request)) throw new IllegalStateException("Cardinal-to-Free prelude request is stale");
        var geometry = request instanceof ProneTransitionRequest prone
                ? proneCardinalGeometry(transaction, cardinal, prone)
                : cardinalToFreeGeometry(transaction.accepted(), transaction.actor(), cardinal,
                        request.rawDesiredQ(), transaction.numeric());
        transaction.requireCurrent();
        var source = new CardinalSource(transaction, request, geometry, budgetTick);
        source.requireCurrent();
        return source;
    }

    record TrustedResizeObservation(PlayerBodyShape.Dimensions dimensions,
            WorldCollisionCandidates.ActorGeometry observedGeometry,
            Optional<NativeCardinalBodyProfile.Value> bodyProfile) {
        TrustedResizeObservation(PlayerBodyShape.Dimensions dimensions,
                WorldCollisionCandidates.ActorGeometry observedGeometry) {
            this(dimensions, observedGeometry, Optional.empty());
        }
        boolean supportsPhysicalCapsule() { return bodyProfile.isPresent() || dimensions.supportsCapsule(); }
        boolean bodyProfileCurrent() { return bodyProfile.isEmpty() || bodyProfile.orElseThrow().current(); }
        TrustedResizeObservation {
            Objects.requireNonNull(dimensions);
            Objects.requireNonNull(observedGeometry);
            Objects.requireNonNull(bodyProfile);
        }
    }
    private record FreeTickPlan(CapsulePlacementMath.Pose startPose,
            PlayerBodyPlacement arcTarget, PlayerBodyPlacement resizeStart, PlayerBodyPlacement resizeTarget,
            Vec3d translationDelta, CapsulePlacementMath.Translation translationTube, Aabb globalWindow) { }

    static final class Prepared implements Preparation {
        private final FreeOwnerTransaction transaction;
        private final Request request;
        private final TrustedResizeObservation trustedNativeResize;
        private final PlayerPhysicalSnapshot source;
        private final PlayerBodyPlacement target;
        private final CapsulePlacementMath.Pose targetPose;
        private final WorldCollisionCandidates.Snapshot capture;
        private final Aabb globalWindow;
        private final CapsulePathSource lineage;
        private final List<PhaseProof> phases;
        private final CapsuleEnvironmentObservation.Prepared environment;
        private final double centerPathUpper;
        private final boolean referenceSuccessor;
        private final Vec3d integratedVelocity;
        private final FreeReferenceModePrepared.ReferenceAbsence referenceAbsence;

        private Prepared(FreeOwnerTransaction transaction, Request request,
                         TrustedResizeObservation trustedNativeResize,
                         PlayerPhysicalSnapshot source, PlayerBodyPlacement target,
                         CapsulePlacementMath.Pose targetPose, WorldCollisionCandidates.Snapshot capture,
                         Aabb globalWindow, CapsulePathSource lineage, List<PhaseProof> phases,
                         CapsuleEnvironmentObservation.Prepared environment, double centerPathUpper) {
            this(transaction, request, trustedNativeResize, source, target, targetPose, capture, globalWindow,
                    lineage, phases, environment, centerPathUpper, null);
        }
        private Prepared(FreeOwnerTransaction transaction, Request request,
                         TrustedResizeObservation trustedNativeResize, PlayerPhysicalSnapshot source,
                         PlayerBodyPlacement target, CapsulePlacementMath.Pose targetPose,
                         WorldCollisionCandidates.Snapshot capture, Aabb globalWindow, CapsulePathSource lineage,
                         List<PhaseProof> phases, CapsuleEnvironmentObservation.Prepared environment,
                         double centerPathUpper, FreeReferenceModePrepared.ReferenceAbsence referenceAbsence) {
            this(transaction, request, trustedNativeResize, source, target, targetPose, capture, globalWindow,
                    lineage, phases, environment, centerPathUpper, referenceAbsence, null);
        }
        private Prepared(FreeOwnerTransaction transaction, Request request,
                         TrustedResizeObservation trustedNativeResize, PlayerPhysicalSnapshot source,
                         PlayerBodyPlacement target, CapsulePlacementMath.Pose targetPose,
                         WorldCollisionCandidates.Snapshot capture, Aabb globalWindow, CapsulePathSource lineage,
                         List<PhaseProof> phases, CapsuleEnvironmentObservation.Prepared environment,
                         double centerPathUpper, FreeReferenceModePrepared.ReferenceAbsence referenceAbsence,
                         Vec3d integratedVelocity) {
            this.integratedVelocity = integratedVelocity;
            this.transaction = transaction; this.request = request; this.trustedNativeResize = trustedNativeResize;
            this.source = source; this.target = target;
            this.targetPose = targetPose; this.capture = capture; this.globalWindow = globalWindow;
            this.lineage = lineage; this.phases = List.copyOf(phases); this.environment = environment;
            this.centerPathUpper = centerPathUpper;
            this.referenceAbsence = referenceAbsence;
            this.referenceSuccessor = referenceAbsence != null;
        }

        FreeOwnerTransaction transaction() { return transaction; }
        Request request() { return request; }
        PlayerPhysicalSnapshot source() { return source; }
        PlayerBodyPlacement target() { return target; }
        CapsulePlacementMath.Pose targetPose() { return targetPose; }
        WorldCollisionCandidates.Snapshot capture() { return capture; }
        Aabb globalWindow() { return globalWindow; }
        CapsulePathSource lineage() { return lineage; }
        List<PhaseProof> phases() { return phases; }
        CapsuleEnvironmentObservation.Prepared environment() { return environment; }
        double centerPathUpper() { return centerPathUpper; }
        CapsuleMovementResolver.Resolved movement() {
            return phases.stream().filter(p -> p instanceof MovementPhase).map(p -> ((MovementPhase) p).proof())
                    .findFirst().orElse(null);
        }
        Vec3d integratedVelocity() { return integratedVelocity; }
        boolean preReserved() { return movement() != null; }
        boolean recovery() { return phases.size() == 1 && phases.getFirst() instanceof RecoveryPhase; }
        boolean referenceCurrentForInstall() {
            return referenceAbsence == null || referenceAbsence.recapture(transaction);
        }
        void requireReferenceSourcesAfterInstall() {
            lineage.requireBodyProfileCurrentAfterInstall();
            if (trustedNativeResize != null && !trustedNativeResize.bodyProfileCurrent())
                throw new IllegalStateException("installed resize physical profile changed during callbacks");
            transaction.requireCurrent();
            if (referenceAbsence != null) referenceAbsence.requireSourcesAfterInstall(transaction);
        }

        boolean matches(FreeOwnerTransaction exactTransaction, Request exactRequest) {
            if (transaction != exactTransaction || request != exactRequest || source != transaction.accepted()) return false;
            transaction.requireCurrent();
            if (exactRequest != null && !isCurrentRequest(exactRequest)) return false;
            if (trustedNativeResize != null && !matchesTrustedNativeResize()) return false;
            if (exactRequest == null && trustedNativeResize == null && !referenceSuccessor && !recovery()) return false;
            if (referenceSuccessor && (request != null || !(source.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                    || phases.stream().anyMatch(p -> !(p instanceof ResizePhase) && !(p instanceof MovementPhase)))) return false;
            if (referenceAbsence != null && !referenceAbsence.current(transaction)) return false;
            if (!lineage.valid() || !lineage.current()
                    || lineage.actualSource() != source || lineage.capture() != capture
                    || lineage.window() != globalWindow || lineage.work() != transaction.numeric()
                    || lineage.queries() != transaction.queries() || !environment.matches(transaction, targetPose))
                return false;
            boolean matches = phasesMatchLineage()
                    && (referenceAbsence == null || referenceAbsence.current(transaction));
            transaction.requireCurrent();
            return matches;
        }

        private boolean matchesTrustedNativeResize() {
            if (trustedNativeResize == null || !(source.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                    || !(target.shape() instanceof PlayerBodyShape.FreeCapsule)) return false;
            var dimensions = trustedNativeResize.dimensions();
            var observed = trustedNativeResize.observedGeometry();
            return trustedNativeResize.bodyProfileCurrent() && target.shape().dimensions().equals(dimensions)
                    && Float.floatToIntBits(observed.width()) == Float.floatToIntBits((float) dimensions.width())
                    && Float.floatToIntBits(observed.height()) == Float.floatToIntBits((float) dimensions.height())
                    && observed.position().equals(source.placement().transport());
        }

        private boolean phasesMatchLineage() {
            if (phases.isEmpty() && !referenceSuccessor || lineage.completedSegments() != phases.size()) return false;
            CapsulePathSource node = lineage;
            for (int index = phases.size() - 1; index >= 0; index--) {
                PhaseProof phase = phases.get(index);
                if (phase instanceof RecoveryPhase recovery) {
                    if (recovery.proof() == null || !recovery.proof().matchesBound(recovery.proof().source())
                            || recovery.proof().end() != node.start()) return false;
                    node = recovery.proof().source();
                } else if (phase instanceof ArcPhase arc) {
                    if (arc.proof() == null || !arc.proof().matchesBound(arc.proof().source())
                            || arc.proof().target() != node.start().placement()) return false;
                    node = arc.proof().source();
                } else if (phase instanceof ResizePhase resize) {
                    if (resize.proof() == null || !resize.proof().matchesBound(resize.proof().source())
                            || resize.proof().target() != node.start().placement()) return false;
                    node = resize.proof().source();
                } else if (phase instanceof MovementPhase movement) {
                    if (movement.proof() == null || !movement.proof().matchesBound(movement.proof().source())
                            || movement.proof().end() != node.start()) return false;
                    node = movement.proof().source();
                } else if (phase instanceof TranslationPhase translation) {
                    if (translation.proof() == null || !translation.proof().matchesBound(translation.proof().source())
                            || translation.proof().target() != node.start().placement()) return false;
                    node = translation.proof().source();
                } else return false;
                if (node.completedSegments() != index || node.actualSource() != source
                        || node.capture() != capture || node.window() != globalWindow
                        || node.work() != transaction.numeric() || node.queries() != transaction.queries()) return false;
            }
            if (node.completedSegments() != 0) return false;
            PlayerBodyPlacement root = node.start().placement();
            // A Cardinal-to-Free root starts from the certified virtual Free capsule, while
            // lineage.valid() above binds that root to the exact accepted Cardinal source,
            // containment certificate, capture, and numeric work context. Do not require the
            // virtual placement to be the actor's actual Cardinal placement.
            return root == source.placement()
                    || source.placement().shape() instanceof PlayerBodyShape.Cardinal
                    && root.shape() instanceof PlayerBodyShape.FreeCapsule;
        }
    }

    private ModeTransitionPrepared() { }

    /** Retains an already resolved path; never reserves, integrates or resolves movement again. */
    static Preparation prepareReferenceSuccessor(FreeOwnerTransaction transaction,
            TrustedResizeObservation resize, CapsuleResizeValidator.VerifiedResize resizeProof,
            CapsuleMovementResolver.Resolved movement, CapsulePathSource endpoint,
            FreeReferenceModePrepared.ReferenceAbsence referenceAbsence) {
        Objects.requireNonNull(referenceAbsence);
        transaction.requireCurrent();
        if (endpoint.actualSource() != transaction.accepted() || endpoint.work() != transaction.numeric()
                || endpoint.queries() != transaction.queries() || !endpoint.valid() || !endpoint.current())
            return new Unavailable("Free reference successor lineage changed", Optional.empty());
        var phases = new ArrayList<PhaseProof>(2);
        double path = 0;
        if (resize != null) {
            if (resizeProof == null || !transaction.authorizesTrustedResize(resize)
                    || resizeProof.source().completedSegments() != 0
                    || resizeProof.source().actualSource() != transaction.accepted()
                    || !resizeProof.matchesBound(resizeProof.source())
                    || !resizeProof.target().shape().dimensions().equals(resize.dimensions()))
                return new Unavailable("Free reference successor resize proof changed", Optional.empty());
            phases.add(new ResizePhase(resizeProof));
            path = CapsulePlacementMath.monotoneCenterTravelUpper(
                    resizeProof.source().start().placement().center(), resizeProof.target().center(), transaction.numeric());
        } else if (resizeProof != null) return new Unavailable("unowned reference successor resize", Optional.empty());
        if (movement != null) {
            if (movement.end() != endpoint.start() || !movement.matchesBound(movement.source())
                    || movement.source().completedSegments() != phases.size())
                return new Unavailable("Free reference successor movement proof changed", Optional.empty());
            phases.add(new MovementPhase(movement));
            path = StoredDyadic.of(path, transaction.numeric()).add(
                    StoredDyadic.of(movement.pathUpper(), transaction.numeric()), transaction.numeric()).roundUp(transaction.numeric());
        }
        if (endpoint.completedSegments() != phases.size())
            return new Unavailable("Free reference successor has no complete certified prefix", Optional.empty());
        if (movement != null) transaction.requireReservedPath(path);
        var environment = CapsuleEnvironmentObservation.observe(transaction, endpoint.start(),
                transaction.resources().environmentObservationWork());
        if (environment instanceof CapsuleEnvironmentObservation.Unavailable unavailable)
            return new Unavailable("Free reference successor environment unavailable: " + unavailable.reason(), unavailable.cause());
        var prepared = new Prepared(transaction, null, resize, transaction.accepted(), endpoint.start().placement(),
                endpoint.start(), endpoint.capture(), endpoint.window(), endpoint, phases,
                (CapsuleEnvironmentObservation.Prepared) environment, path, referenceAbsence);
        return prepared.matches(transaction, null) ? prepared
                : new Unavailable("Free reference successor proof changed", Optional.empty());
    }

    static Preparation prepareCardinalToFree(FreeOwnerTransaction transaction,
                                              Request request, long budgetTick) {
        return prepareCardinalToFree(transaction, request, null, budgetTick);
    }

    static Preparation prepareCardinalToFree(FreeOwnerTransaction transaction, Request request, Vec3d integratedVelocity, long budgetTick) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(request);
        try {
            return prepareCardinalToFree(prepareCardinalSource(transaction, request, budgetTick), integratedVelocity, null, budgetTick);
        } catch (RuntimeException failure) {
            return new Unavailable("Cardinal-to-Free preparation failed at its owner boundary", Optional.of(failure));
        }
    }

    static Preparation prepareCardinalToFree(CardinalSource prelude, Vec3d integratedVelocity, Vec3d gaitVelocity, long budgetTick) {
        Objects.requireNonNull(prelude);
        var transaction = prelude.transaction;
        var request = prelude.request;
        try {
            prelude.requireCurrent();
            if (prelude.budgetTick != budgetTick)
                return new Unavailable("Cardinal-to-Free owner budget clock changed after its prelude", Optional.empty());
            PlayerPhysicalSnapshot accepted = transaction.accepted();
            if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
                return new Unavailable("Cardinal-to-Free preparation requires the actual accepted Cardinal owner", Optional.empty());
            if (!isCurrentRequest(request))
                return new Unavailable("attitude request is stale before mode preparation", Optional.empty());

            // All live profile getters ran in the prelude, before any collision observation.
            var geometry = prelude.geometry;
            transaction.requireCurrent();
            var initialPlacement = geometry.initialPlacement();
            var targetPlacement = geometry.targetPlacement();
            var initialPose = geometry.initialPose();
            var targetPose = geometry.targetPose();
            var globalWindow = geometry.globalWindow();
            if (gaitVelocity != null && integratedVelocity == null)
                throw new IllegalArgumentException("Cardinal gait requires the ordinary velocity candidate");
            if (gaitVelocity != null)
                globalWindow = union(globalWindow, CapsuleFootContacts.requiredWindow(initialPose, .25));
            var velocities = integratedVelocity == null ? List.<Vec3d>of()
                    : gaitVelocity == null ? List.of(integratedVelocity) : List.of(integratedVelocity, gaitVelocity);
            for (var candidateVelocity : velocities) {
                var delta = candidateVelocity.multiply(GravityEntityBinder.TICK_SECONDS);
                var candidateTube = CapsulePlacementMath.chargedTranslationTube(targetPose, delta, transaction.numeric());
                double allowance = candidateTube.requestedDisplacementUpper();
                double[] lo = new double[3], hi = new double[3];
                double[] c = {targetPlacement.center().x(), targetPlacement.center().y(), targetPlacement.center().z()};
                for (int i = 0; i < 3; i++) {
                    double d = i == 0 ? delta.x() : i == 1 ? delta.y() : delta.z();
                    lo[i] = d < 0 ? StoredDyadic.of(c[i], transaction.numeric()).subtract(StoredDyadic.of(allowance, transaction.numeric()), transaction.numeric()).roundDown(transaction.numeric()) : c[i];
                    hi[i] = d > 0 ? StoredDyadic.of(c[i], transaction.numeric()).add(StoredDyadic.of(allowance, transaction.numeric()), transaction.numeric()).roundUp(transaction.numeric()) : c[i];
                }
                var low = new Vec3d(lo[0], lo[1], lo[2]);
                var high = new Vec3d(hi[0], hi[1], hi[2]);
                globalWindow = union(globalWindow, candidateTube.sweptBounds(),
                        CapsulePlacementMath.translationCaptureBoundsOverCenters(targetPlacement, low, high, delta, transaction.numeric()),
                        CapsulePlacementMath.translatedProxyCaptureBounds(targetPlacement, low, high, delta, CapsuleMovementResolver.MAX_SEGMENTS, transaction.numeric()));
            }

            var captured = WorldCollisionCandidates.capture(transaction, globalWindow,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
            if (captured instanceof WorldCollisionCandidates.Unavailable unavailable)
                return captureUnavailable(unavailable);
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            transaction.requireCurrent();
            if (capture.capsuleContextFailure(accepted, globalWindow).isPresent())
                return new Unavailable("Cardinal source/global mode capture does not match the exact owner window", Optional.empty());

            var contained = geometry.containment();
            CapsulePathSource source;
            NativeCardinalBodyProfile.Fit nativeFit = null;
            if (geometry.nativeProfile() == null) {
                if (initialPose.placement() != initialPlacement
                        || !contained.matches(accepted, initialPlacement, transaction.numeric()))
                    return new Unavailable("Cardinal-to-Free contained source identity changed", Optional.empty());
                source = CapsulePathSource.containedCardinal(contained, initialPose, capture, globalWindow,
                        FreeOwnerTransaction.ACCURACY, transaction.queries(), transaction.numeric());
            } else {
                var fit = NativeCardinalBodyProfile.certify(transaction, geometry.nativeProfile(), initialPose, capture, globalWindow);
                if (fit instanceof NativeCardinalBodyProfile.TargetOccupied occupied) {
                    // Only the sampled zero-reference strategy has already integrated this tick.
                    // Other entry points must retain their original rejection, without a second solver.
                    if (integratedVelocity != null && request instanceof MobilityTransitionRequest)
                        return new Occupied(prelude, occupied);
                    return new Unavailable("first-Free full physical target occupied", Optional.empty());
                }
                nativeFit = (NativeCardinalBodyProfile.Fit) fit;
                source = CapsulePathSource.nativeProfileCardinal(transaction,
                        nativeFit, initialPose, capture, globalWindow);
            }
            if (gaitVelocity != null) {
                var root = nativeFit == null
                        ? CertifiedReferenceEndpoint.containedRootObservation(transaction, geometry.containedSource(), source)
                        : CertifiedReferenceEndpoint.nativeProfileRootObservation(transaction, nativeFit, source);
                var footResult = CapsuleFootContacts.collect(root, capture, .25, FreeOwnerTransaction.ACCURACY);
                if (!(footResult instanceof CapsuleFootContacts.VerifiedFootObservations foot)) {
                    var failed = (CapsuleFootContacts.Failed) footResult;
                    if (failed.contextFailure().isPresent()) return captureUnavailable(failed.contextFailure().orElseThrow());
                    if (failed.fullObservation() instanceof CapsuleContactCollector.Failed full) {
                        if (full.contextFailure().isPresent()) return captureUnavailable(full.contextFailure().orElseThrow());
                        if (full.budgetFailure().isPresent())
                            return new Unavailable("Cardinal near-foot full-body budget exhausted", full.budgetFailure().map(failure -> (RuntimeException) failure));
                    }
                    return new Unavailable("Cardinal near-foot observation failed: " + failed.failure(),
                            failed.budgetFailure().map(failure -> (RuntimeException) failure));
                }
                if (FreeNearFootDrive.permitted(transaction, root, foot)) integratedVelocity = gaitVelocity;
                root.requireCurrent();
            }
            CapsulePlacementMath.Translation movementTube = integratedVelocity == null ? null
                    : CapsulePlacementMath.chargedTranslationTube(targetPose,
                            integratedVelocity.multiply(GravityEntityBinder.TICK_SECONDS), transaction.numeric());
            if (movementTube != null && !containsWindow(globalWindow, movementTube.sweptBounds(), transaction.numeric()))
                return new Unavailable("Cardinal gait movement escaped its fixed capture window", Optional.empty());
            var arcResult = CapsuleArcValidator.validate(source, targetPlacement, cardinal.frame().up());
            if (!(arcResult instanceof CapsuleArcValidator.VerifiedArc arc)) {
                var failed = (CapsuleArcValidator.Failed) arcResult;
                return new Unavailable("Cardinal-to-Free arc proof failed: " + failed.failure() + "; "
                        + cardinalFailureContext(accepted, geometry.actorGeometry(), request.rawDesiredQ(), initialPlacement)
                        + "; arc=" + arcDiagnostic(failed, transaction.queries()),
                        failed.budgetCause() instanceof RuntimeException runtime ? Optional.of(runtime) : Optional.empty());
            }
            var finalLineage = CapsulePathSource.continueAfter(arc, transaction.numeric());
            var phases = new ArrayList<PhaseProof>();
            phases.add(new ArcPhase(arc));
            double centerPath = 0;
            if (movementTube != null) {
                var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick, movementTube.requestedDisplacementUpper());
                if (!admission.accepted()) return new Unavailable("Cardinal-to-Free movement grant rejected: " + admission.failure(), Optional.empty());
                var exactTube = CapsulePlacementMath.chargedTranslationTube(finalLineage.start(), movementTube.delta(), transaction.numeric());
                var resolved = CapsuleMovementResolver.resolveContainedCardinalContinuation(transaction, finalLineage, exactTube, capture, 0, movementTube.requestedDisplacementUpper());
                if (!(resolved instanceof CapsuleMovementResolver.Resolved movement)) {
                    var failure = (CapsuleMovementResolver.Inconclusive) resolved;
                    return new Unavailable("Cardinal-to-Free movement continuation unresolved: " + failure.reason(), failure.cause());
                }
                phases.add(new MovementPhase(movement));
                finalLineage = CapsulePathSource.continueAfter(movement, transaction.numeric());
                targetPose = movement.end();
                targetPlacement = targetPose.placement();
                centerPath = movement.pathUpper();
            }
            if (!finalLineage.valid() || !finalLineage.current() || !isCurrentRequest(request))
                return new Unavailable("Cardinal-to-Free proof lineage changed before environment preparation", Optional.empty());
            var env = CapsuleEnvironmentObservation.observe(transaction, targetPose, transaction.resources().environmentObservationWork());
            if (!(env instanceof CapsuleEnvironmentObservation.Prepared environment)) {
                var unavailable = (CapsuleEnvironmentObservation.Unavailable) env;
                return new Unavailable("target capsule environment preparation unavailable: " + unavailable.reason(),
                        unavailable.cause());
            }
            transaction.requireCurrent();
            if (!finalLineage.valid() || !finalLineage.current() || !isCurrentRequest(request))
                return new Unavailable("Cardinal-to-Free preparation lost its captured world or request fence", Optional.empty());
            var prepared = new Prepared(transaction, request, null, accepted, targetPlacement, targetPose, capture, globalWindow,
                    finalLineage, phases, environment, centerPath, null, integratedVelocity);
            return prepared;
        } catch (RuntimeException failure) {
            return new Unavailable("Cardinal-to-Free preparation failed at its owner boundary", Optional.of(failure));
        }
    }

    static Prepared recovery(FreeOwnerTransaction tx, CapsuleDepenetrationPrepared.Verified proof,
            CapsulePathSource lineage, CapsuleEnvironmentObservation.Prepared environment) {
        return new Prepared(tx, null, null, tx.accepted(), proof.end().placement(), proof.end(),
                proof.source().capture(), proof.source().window(), lineage, List.of(new RecoveryPhase(proof)),
                environment, proof.pathUpper());
    }

    static Preparation prepareFreeTick(FreeOwnerTransaction transaction, Request request,
            TrustedResizeObservation trustedResize, Vec3d translationDelta) {
        return prepareFreeTick(transaction, request, trustedResize, translationDelta, transaction.tick());
    }
    static Preparation prepareFreeTick(FreeOwnerTransaction transaction, Request request,
            TrustedResizeObservation trustedResize, Vec3d translationDelta, long budgetTick) {
        return prepareFreeTick(transaction, request, trustedResize, translationDelta, budgetTick, null);
    }
    private static Preparation prepareFreeTick(FreeOwnerTransaction transaction, Request request,
            TrustedResizeObservation trustedResize, Vec3d translationDelta, long budgetTick,
            String rejectedAttitude) {
        Objects.requireNonNull(transaction); Objects.requireNonNull(translationDelta);
        try {
            transaction.requireCurrent();
            PlayerPhysicalSnapshot accepted = transaction.accepted();
            if (!(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule))
                return new Unavailable("Free phase preparation requires the actual accepted Free owner", Optional.empty());
            if (request != null && !isCurrentRequest(request))
                return new Unavailable("attitude request is stale before Free phase preparation", Optional.empty());
            if (request == null && trustedResize == null)
                return new Unavailable("Free phase preparation requires an authorized mode or resize intent", Optional.empty());

            StoredDyadic.Work numeric = transaction.numeric();
            FreeTickPlan plan = planFreeTick(accepted, rejectedAttitude == null ? request : null,
                    trustedResize, translationDelta, numeric);
            double reserveUpper = plan.resizeTarget() == null ? 0 : accumulateCenterPath(0, CapsulePlacementMath.monotoneCenterTravelUpper(
                    plan.resizeStart().center(), plan.resizeTarget().center(), numeric), numeric);
            if (plan.translationTube() != null)
                reserveUpper = accumulateCenterPath(reserveUpper, plan.translationTube().requestedDisplacementUpper(), numeric);
            Aabb globalWindow = plan.globalWindow();
            var captured = WorldCollisionCandidates.capture(transaction, globalWindow,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN, transaction.resources().environmentObservationWork());
            if (captured instanceof WorldCollisionCandidates.Unavailable unavailable)
                return captureUnavailable(unavailable);
            var capture = (WorldCollisionCandidates.Snapshot) captured;
            transaction.requireCurrent();
            if (capture.capsuleContextFailure(accepted, globalWindow).isPresent())
                return new Unavailable("Free source/global phase capture does not match the exact owner window", Optional.empty());

            var lineage = CapsulePathSource.actualFree(accepted, plan.startPose(), capture, globalWindow,
                    FreeOwnerTransaction.ACCURACY, transaction.queries(), numeric);
            var phases = new java.util.ArrayList<PhaseProof>(3);
            double centerPathUpper = 0;
            if (plan.arcTarget() != null) {
                var arcResult = CapsuleArcValidator.validate(lineage, plan.arcTarget(),
                        accepted.placement().bodyUp());
                if (!(arcResult instanceof CapsuleArcValidator.VerifiedArc arc)) {
                    var failed = (CapsuleArcValidator.Failed) arcResult;
                    String detail = arcFailureDetail(accepted, request, plan.arcTarget(), failed);
                    if (rejectedAttitude == null && (request instanceof PlayerAttitudeRequest || request instanceof ProneTransitionRequest)
                            && rejectsOnlyRequestedRotation(failed)) {
                        // No path has been reserved or installed. Discard the rejected target's
                        // capture/proofs and build a complete plan at the accepted orientation.
                        var retained = prepareFreeTick(transaction, request, trustedResize, translationDelta,
                                budgetTick, detail);
                        return retained instanceof Unavailable unavailable
                                ? new Unavailable("Requested attitude rejected: " + detail
                                        + "; retained-attitude movement failed: " + unavailable.reason(), unavailable.cause())
                                : retained;
                    }
                    return new Unavailable("Free attitude arc proof failed: " + detail,
                            failed.budgetCause() instanceof RuntimeException runtime ? Optional.of(runtime) : Optional.empty());
                }
                phases.add(new ArcPhase(arc));
                lineage = CapsulePathSource.continueAfter(arc, numeric);
                if (!lineage.valid() || !lineage.current() || !isCurrentRequest(request))
                    return new Unavailable("Free attitude proof lineage changed before the next phase", Optional.empty());
            }
            if (plan.resizeTarget() != null) {
                var resizeResult = CapsuleResizeValidator.validate(lineage, plan.resizeTarget());
                if (!(resizeResult instanceof CapsuleResizeValidator.VerifiedResize resize)) {
                    var failed = (CapsuleResizeValidator.Failed) resizeResult;
                    return new Unavailable("trusted Free resize proof failed: " + failed.failure(),
                            failed.budgetCause() instanceof RuntimeException runtime ? Optional.of(runtime) : Optional.empty());
                }
                phases.add(new ResizePhase(resize));
                double resizePath = CapsulePlacementMath.monotoneCenterTravelUpper(
                        plan.resizeStart().center(), plan.resizeTarget().center(), numeric);
                centerPathUpper = accumulateCenterPath(centerPathUpper, resizePath, numeric);
                lineage = CapsulePathSource.continueAfter(resize, numeric);
                if (!lineage.valid() || !lineage.current())
                    return new Unavailable("trusted Free resize lineage changed before the next phase", Optional.empty());
            }
            if (plan.translationTube() != null) {
                var admission = transaction.reserve(transaction.nextLedgerSequence(), budgetTick, reserveUpper);
                if (!admission.accepted()) return new Unavailable("Free phase center-path grant rejected: " + admission.failure(), Optional.empty());
                transaction.requireReservedPath(reserveUpper);
                var tube = CapsulePlacementMath.chargedTranslationTube(lineage.start(), plan.translationDelta(), numeric);
                var result = CapsuleMovementResolver.resolveContinuationWithSlide(transaction, lineage, tube, capture, centerPathUpper);
                if (!(result instanceof CapsuleMovementResolver.Resolved movement)) {
                    var failed = (CapsuleMovementResolver.Inconclusive) result;
                    return new Unavailable("Free movement continuation unresolved: " + failed.reason(), failed.cause());
                }
                phases.add(new MovementPhase(movement));
                centerPathUpper = accumulateCenterPath(centerPathUpper, movement.pathUpper(), numeric);
                lineage = CapsulePathSource.continueAfter(movement, numeric);
                if (!lineage.valid() || !lineage.current())
                    return new Unavailable("Free movement lineage changed before environment preparation", Optional.empty());
            }
            var targetPose = lineage.start();
            var env = CapsuleEnvironmentObservation.observe(transaction, targetPose, transaction.resources().environmentObservationWork());
            if (!(env instanceof CapsuleEnvironmentObservation.Prepared environment)) {
                var unavailable = (CapsuleEnvironmentObservation.Unavailable) env;
                return new Unavailable("Free phase target environment unavailable: " + unavailable.reason(),
                        unavailable.cause());
            }
            transaction.requireCurrent();
            if (!lineage.valid() || !lineage.current() || request != null && !isCurrentRequest(request))
                return new Unavailable("Free phase preparation lost its world or request fence", Optional.empty());
            if (UnigravMod.LOGGER.isDebugEnabled(FREE_RESIZE_TRACE))
                UnigravMod.LOGGER.debug(FREE_RESIZE_TRACE,
                        "UG_FREE_RESIZE_TRACE scope={} proposal={} stage=FREE_PLAN translationDelta={} phases={} kinds={}",
                        accepted.scope(), request instanceof PlayerAttitudeRequest attitude ? attitude.proposal() : null,
                        plan.translationDelta(), phases.size(),
                        phases.stream().map(phase -> phase.getClass().getSimpleName()).toList());
            var prepared = new Prepared(transaction, request, trustedResize, accepted, targetPose.placement(), targetPose,
                    capture, globalWindow, lineage, phases, environment, centerPathUpper);
            return rejectedAttitude == null ? prepared : new RetainedAttitude(prepared, rejectedAttitude);
        } catch (RuntimeException failure) {
            return new Unavailable("Free phase preparation failed at its owner boundary", Optional.of(failure));
        }
    }

    private static boolean rejectsOnlyRequestedRotation(CapsuleArcValidator.Failed failed) {
        if (failed.budgetCause() != null) return false;
        // These witnesses occur only after the complete START observation succeeded.
        // An overlapping padded enclosure alone is never a rotation rejection witness.
        return failed.failure() == CapsuleArcValidator.Failure.ACTUAL_OVERLAP
                    && failed.queries().stream().anyMatch(query -> query.stage() == CapsuleArcValidator.Stage.TARGET
                        && query.result().relation() == Relation.OVERLAPPING)
                || failed.failure() == CapsuleArcValidator.Failure.ARC_OVERLAP
                    && failed.queries().stream().anyMatch(query -> query.stage() == CapsuleArcValidator.Stage.MIDPOINT
                        && query.result().relation() == Relation.OVERLAPPING);
    }

    private static String arcFailureDetail(PlayerPhysicalSnapshot accepted, Request request,
            PlayerBodyPlacement target, CapsuleArcValidator.Failed failed) {
        var witness = failed.queries().stream()
                .filter(query -> query.stage() != CapsuleArcValidator.Stage.ENCLOSURE
                        && query.result().relation() == Relation.OVERLAPPING)
                .findFirst().orElse(failed.queries().isEmpty() ? null : failed.queries().getLast());
        return "failure=" + failed.failure() + ";scope=" + accepted.scope() + ";revision=" + accepted.revision()
                + ";inputSeq=" + (request instanceof PlayerAttitudeRequest attitude ? attitude.inputSequence() : "mode")
                + ";stage=" + (witness == null ? "NO_QUERY" : witness.stage())
                + ";ordinal=" + (witness == null ? -1 : witness.ordinal())
                + ";interval=" + (witness == null ? "none" : witness.from() + ".." + witness.to())
                + ";obstacle=" + (witness == null ? "none" : witness.box())
                + ";relation=" + (witness == null ? "none" : witness.result().relation())
                + ";acceptedCenter=" + accepted.placement().center()
                + ";acceptedQ=" + accepted.placement().attitude() + ";requestedQ=" + target.attitude();
    }

    private static FreeTickPlan planFreeTick(PlayerPhysicalSnapshot accepted, Request request,
            TrustedResizeObservation trustedResize, Vec3d translationDelta, StoredDyadic.Work numeric) {
        var startPose = CapsulePlacementMath.pose(accepted.placement(), numeric);
        PlayerBodyPlacement current = accepted.placement();
        CapsulePlacementMath.Pose currentPose = startPose;
        PlayerBodyPlacement arcTarget = null, resizeStart = null, resizeTarget = null;
        var windows = new java.util.ArrayList<Aabb>(4);
        windows.add(accepted.placement().bounds());
        // A current input can repeat the exact raw attitude. Keep its request fences, but retain
        // the accepted stored realization instead of making a new, redundant rotation proof.
        if (request != null && (!sameRawAttitude(current.attitude(), request.rawDesiredQ())
                || request instanceof ProneTransitionRequest prone
                    && !prone.requestedPose().equals(((PlayerBodyShape.FreeCapsule) current.shape()).pronePose()))) {
            PlayerBodyPlacement target = new PlayerBodyPlacement(current.center(), request.rawDesiredQ(),
                    request instanceof ProneTransitionRequest prone
                            ? prone.shape(current) : current.shape(), numeric);
            var path = CapsuleArcEnclosure.prepare(currentPose, target, current.bodyUp(), numeric);
            windows.add(path.allChildCaptureBounds(numeric));
            windows.add(target.bounds());
            arcTarget = target;
            current = target;
            currentPose = CapsulePlacementMath.pose(target, numeric);
        }
        if (trustedResize != null) {
            var dimensions = trustedResize.dimensions();
            var observed = trustedResize.observedGeometry();
            if (!observed.position().equals(accepted.placement().transport())
                    || Float.floatToIntBits(observed.width()) != Float.floatToIntBits((float) dimensions.width())
                    || Float.floatToIntBits(observed.height()) != Float.floatToIntBits((float) dimensions.height())
                    || !trustedResize.supportsPhysicalCapsule() || !trustedResize.bodyProfileCurrent())
                throw new IllegalArgumentException("trusted Free resize does not match its native pose observation");
            resizeStart = current;
            resizeTarget = trustedFreeResizeTarget(current, trustedResize, numeric);
            var path = CapsuleResizeEnclosure.prepare(currentPose, resizeTarget, numeric);
            var whole = path.encloseClosedInterval(0, 1, numeric);
            windows.add(allChildResizeCaptureBounds(whole, numeric));
            windows.add(resizeTarget.bounds());
            current = resizeTarget;
            currentPose = CapsulePlacementMath.pose(current, numeric);
        }
        CapsulePlacementMath.Translation translationTube = null;
        // A repeated exact attitude still needs a certified stationary segment when hover stops travel.
        // Keep the ordinary movement capture/lineage and all installation fences instead of admitting an empty proof.
        if (!zero(translationDelta) || arcTarget == null && resizeTarget == null) {
            translationTube = CapsulePlacementMath.chargedTranslationTube(currentPose, translationDelta, numeric);
            windows.add(CapsuleMovementResolver.window(accepted, translationTube));
            var translatedEnd = CapsulePlacementMath.chargedFullTranslation(translationTube, numeric);
            windows.add(translatedEnd.placement().bounds());
        }
        return new FreeTickPlan(startPose, arcTarget, resizeStart, resizeTarget, translationDelta,
                translationTube, union(windows.toArray(Aabb[]::new)));
    }

    private static boolean sameRawAttitude(Rotation3d left, Rotation3d right) {
        return Double.doubleToRawLongBits(left.x()) == Double.doubleToRawLongBits(right.x())
                && Double.doubleToRawLongBits(left.y()) == Double.doubleToRawLongBits(right.y())
                && Double.doubleToRawLongBits(left.z()) == Double.doubleToRawLongBits(right.z())
                && Double.doubleToRawLongBits(left.w()) == Double.doubleToRawLongBits(right.w());
    }

    private static double accumulateCenterPath(double accumulated, double phase, StoredDyadic.Work numeric) {
        numeric.charge(3);
        if (!Double.isFinite(accumulated) || accumulated < 0 || !Double.isFinite(phase) || phase < 0)
            throw new IllegalArgumentException("Free phase center path is not finite and non-negative");
        if (phase == 0) return accumulated;
        double upper = Math.nextUp(accumulated + phase);
        if (!Double.isFinite(upper)) throw new IllegalArgumentException("Free phase center path overflow");
        return upper;
    }

    /**
     * Derives a resize center from the accepted stored capsule's rounded physical foot, then the new
     * profile's recipe half-line and radius. The resulting placement is still independently validated
     * by the same-Work resize proof; native observed AABBs never supply this target.
     */
    private static Vec3d centerAtStoredPhysicalFoot(PlayerBodyPlacement source,
            PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work numeric) {
        var arithmetic = BootQueryArithmetic.fromSameWork(numeric);
        var foot = PlayerBodyPlacement.physicalFoot(source, arithmetic);
        var up = source.bodyUp();
        var halfLine = up.multiply(dimensions.centerlineHalfLength());
        double radius = dimensions.radius();
        return new Vec3d(centerComponent(foot.x(), up.x(), radius, halfLine.x(), arithmetic),
                centerComponent(foot.y(), up.y(), radius, halfLine.y(), arithmetic),
                centerComponent(foot.z(), up.z(), radius, halfLine.z(), arithmetic));
    }

    static PlayerBodyPlacement trustedFreeResizeTarget(PlayerBodyPlacement source,
            TrustedResizeObservation observation, StoredDyadic.Work numeric) {
        var dimensions = observation.dimensions();
        if (observation.bodyProfile().isEmpty()) return trustedFreeResizeTarget(source, dimensions, numeric);
        if (!observation.bodyProfileCurrent()) throw new IllegalStateException("native physical profile changed");
        // The independent body profile is resized about its accepted center. No native box or
        // pose height supplies the collider extent, and the ordinary foot recipe stays unchanged.
        var anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, source.center(), source.eye(), source.attitude());
        return new PlayerBodyPlacement(source.center(), source.attitude(),
                new PlayerBodyShape.FreeCapsule(dimensions, observation.bodyProfile().orElseThrow().physical(),
                        Optional.of(anchor), ((PlayerBodyShape.FreeCapsule) source.shape()).pronePose()), numeric);
    }

    /** Same-owner Free profile target shared by phase plans; its foot drift is bounded and then path-proved. */
    static PlayerBodyPlacement trustedFreeResizeTarget(PlayerBodyPlacement source,
            PlayerBodyShape.Dimensions dimensions, StoredDyadic.Work numeric) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(numeric, "numeric");
        if (!(source.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("trusted Free resize target requires an accepted Free capsule");
        Vec3d center = centerAtStoredPhysicalFoot(source, dimensions, numeric);
        PlayerBodyPlacement target = new PlayerBodyPlacement(center, source.attitude(),
                new PlayerBodyShape.FreeCapsule(dimensions), numeric);
        requireStoredPhysicalFootWithinRoundingBound(source, target, numeric);
        return target;
    }

    private static double centerComponent(double foot, double up, double radius, double halfLine,
            BootQueryArithmetic arithmetic) {
        var lower = arithmetic.add(arithmetic.exact(foot), arithmetic.multiply(arithmetic.exact(up),
                arithmetic.exact(radius)));
        return arithmetic.roundNearest(arithmetic.add(lower, arithmetic.exact(halfLine)));
    }

    /**
     * The inverse recipe has three RN steps: target center, target negative endpoint, then physical-foot
     * projection. The outward-bounded intermediate magnitudes cover all three result scales; together,
     * their RN errors are at most 1.5 ULPs at that bound, so two ULPs enclose the three RN error bounds.
     * Exact same-Work comparison refuses any larger or non-finite drift.
     */
    private static void requireStoredPhysicalFootWithinRoundingBound(PlayerBodyPlacement source,
            PlayerBodyPlacement target, StoredDyadic.Work numeric) {
        var arithmetic = BootQueryArithmetic.fromSameWork(numeric);
        Vec3d sourceFoot = PlayerBodyPlacement.physicalFoot(source, arithmetic);
        Vec3d targetFoot = PlayerBodyPlacement.physicalFoot(target, arithmetic);
        Vec3d targetHalfLine = target.storedCapsuleGeometry().storedHalfLine();
        Vec3d targetLower = target.storedCapsuleGeometry().negativeEndpoint();
        numeric.charge(24); // three axes: product bounds, outward sums, comparisons and finite guards
        for (int axis = 0; axis < 3; axis++) {
            double sourceValue = component(sourceFoot, axis);
            double targetValue = component(targetFoot, axis);
            double upRadius = outwardProductMagnitude(component(target.bodyUp(), axis),
                    target.shape().dimensions().radius());
            double centerInputBound = outwardMagnitudeSum(Math.abs(sourceValue), upRadius,
                    Math.abs(component(targetHalfLine, axis)));
            double lowerInputBound = outwardMagnitudeSum(Math.abs(component(target.center(), axis)),
                    Math.abs(component(targetHalfLine, axis)));
            double footInputBound = outwardMagnitudeSum(Math.abs(component(targetLower, axis)), upRadius);
            double scale = Math.max(centerInputBound, Math.max(lowerInputBound, footInputBound));
            double bound = 2 * Math.ulp(scale);
            if (!Double.isFinite(bound)) throw new IllegalArgumentException("Free foot rounding bound is non-finite");
            var difference = arithmetic.subtract(arithmetic.exact(sourceValue), arithmetic.exact(targetValue));
            if (arithmetic.sign(difference) < 0) difference = arithmetic.negate(difference);
            if (arithmetic.compare(difference, arithmetic.exact(bound)) > 0)
                throw new IllegalArgumentException("Free resize cannot preserve the stored physical foot within two ULPs");
        }
    }

    private static double outwardProductMagnitude(double first, double second) {
        double product = Math.abs(first * second);
        if (!Double.isFinite(product)) throw new IllegalArgumentException("Free foot product bound is non-finite");
        return product == 0 ? 0 : Math.nextUp(product);
    }

    private static double outwardMagnitudeSum(double... positiveValues) {
        double sum = 0;
        for (double value : positiveValues) {
            if (!Double.isFinite(value) || value < 0)
                throw new IllegalArgumentException("Free foot magnitude input is invalid");
            sum = Math.nextUp(sum + value);
            if (!Double.isFinite(sum)) throw new IllegalArgumentException("Free foot magnitude bound overflow");
        }
        return sum;
    }

    private static boolean containsWindow(Aabb outer, Aabb inner, StoredDyadic.Work work) {
        work.charge(6);
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    private static boolean zero(Vec3d delta) {
        return delta.x() == 0 && delta.y() == 0 && delta.z() == 0;
    }

    private static boolean validPhase(PhaseProof phase) {
        if (phase instanceof ArcPhase arc) return arc.proof() != null;
        if (phase instanceof ResizePhase resize) return resize.proof() != null;
        return phase instanceof TranslationPhase translation && translation.proof() != null;
    }

    /** Shared by the owner producer and its real-world fixture so both derive the same query window. */
    static Aabb cardinalToFreeCaptureWindow(PlayerPhysicalSnapshot accepted, EntityPlayerMP actor,
            Rotation3d desired, StoredDyadic.Work numeric) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(desired, "desired");
        Objects.requireNonNull(numeric, "numeric");
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal))
            throw new IllegalArgumentException("Cardinal-to-Free capture window requires a Cardinal accepted shape");
        return cardinalToFreeGeometry(accepted, actor, cardinal, desired, numeric).globalWindow();
    }

    private static CardinalToFreeGeometry proneCardinalGeometry(FreeOwnerTransaction transaction,
            PlayerBodyShape.Cardinal cardinal, ProneTransitionRequest request) {
        if (request.requestedPose().isEmpty() || !request.current())
            throw new IllegalArgumentException("Cardinal prone admission requires a current selected pose");
        var accepted = transaction.accepted();
        var actor = transaction.actor();
        var numeric = transaction.numeric();
        var observation = NativeCardinalBodyProfile.observe(transaction);
        var physical = observation.physical();
        if (!physical.equals(request.body().physical()))
            throw new IllegalStateException("Prone admission changed its trusted full body profile");
        var actual = WorldCollisionCandidates.ActorGeometry.read(actor);
        var raw = new PlayerBodyShape.Dimensions(actual.width(), actual.height(), actor.getEyeHeight());
        boolean shortNativeSource = raw.height() < physical.length();
        // A pre-existing native short box carries no accepted long-axis orientation. Its first
        // full-body representation must fit in the requested prone orientation. An existing
        // standing long body instead starts at its accepted orientation and proves the whole arc.
        var initialQ = shortNativeSource ? request.rawDesiredQ() : accepted.placement().attitude();
        FreeEyeAnchor anchor;
        if (shortNativeSource) {
            var arithmetic = BootQueryArithmetic.fromSameWork(numeric);
            var offset = arithmetic.subtract(arithmetic.exact(request.input().standingEyeHeight()),
                    arithmetic.multiply(arithmetic.exact(physical.length()), arithmetic.exact(.5)));
            var up = initialQ.basisY();
            var center = accepted.placement().center();
            var eye = new Vec3d(
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(center.x()), arithmetic.multiply(arithmetic.exact(up.x()), offset))),
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(center.y()), arithmetic.multiply(arithmetic.exact(up.y()), offset))),
                    arithmetic.roundNearest(arithmetic.add(arithmetic.exact(center.z()), arithmetic.multiply(arithmetic.exact(up.z()), offset))));
            anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center, eye, initialQ);
        } else anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, accepted.placement().center(),
                accepted.placement().eye(), initialQ);
        var shape = new PlayerBodyShape.FreeCapsule(raw, physical, Optional.of(anchor), request.requestedPose());
        var initial = new PlayerBodyPlacement(accepted.placement().center(), initialQ, shape, numeric);
        var target = sameRawAttitude(initialQ, request.rawDesiredQ()) ? initial
                : new PlayerBodyPlacement(initial.center(), request.rawDesiredQ(), shape, numeric);
        var initialPose = CapsulePlacementMath.pose(initial, numeric);
        var targetPose = target == initial ? initialPose : CapsulePlacementMath.pose(target, numeric);
        var arc = CapsuleArcEnclosure.prepare(initialPose, target, cardinal.frame().up(), numeric);
        return new CardinalToFreeGeometry(initial, target, initialPose, targetPose,
                union(accepted.placement().bounds(), initial.bounds(), arc.allChildCaptureBounds(numeric), target.bounds()),
                actual, null, observation);
    }

    private static CardinalToFreeGeometry cardinalToFreeGeometry(PlayerPhysicalSnapshot accepted,
            EntityPlayerMP actor, PlayerBodyShape.Cardinal cardinal, Rotation3d desired,
            StoredDyadic.Work numeric) {
        var actorGeometry = WorldCollisionCandidates.ActorGeometry.read(actor);
        var dimensions = new PlayerBodyShape.Dimensions(actorGeometry.width(), actorGeometry.height(), actor.getEyeHeight());
        // A qualified native producer owns the full physical body in standing and
        // short poses alike. Admission checks that capsule against the world;
        // its radius need not fit inside a separately rounded native AABB.
        NativeCardinalBodyProfile.Observation observedProfile = null;
        if (!dimensions.supportsCapsule()) observedProfile = NativeCardinalBodyProfile.observe(actor, accepted);
        else if (actor instanceof NativeBodyProfileProducer producer) {
            var candidate = producer.unigrav$nativeBodyProfile();
            if (candidate.isPresent() && (NativeCardinalBodyProfile.sample(actor).isPresent()
                        || !StoredRealizationLimits.sameRaw(dimensions.width(), (double) candidate.orElseThrow().diameter())
                        || !StoredRealizationLimits.sameRaw(dimensions.height(), (double) candidate.orElseThrow().length())))
                observedProfile = NativeCardinalBodyProfile.observe(actor, accepted);
        }
        if (observedProfile != null) {
            var anchor = new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, accepted.placement().center(),
                    accepted.placement().eye(), accepted.placement().attitude());
            var physical = observedProfile.physical();
            var independentShape = new PlayerBodyShape.FreeCapsule(dimensions, physical, Optional.of(anchor));
            var initial = new PlayerBodyPlacement(accepted.placement().center(), accepted.placement().attitude(), independentShape, numeric);
            var target = sameRawAttitude(initial.attitude(), desired) ? initial
                    : new PlayerBodyPlacement(initial.center(), desired, independentShape, numeric);
            var initialPose = CapsulePlacementMath.pose(initial, numeric);
            var targetPose = target == initial ? initialPose : CapsulePlacementMath.pose(target, numeric);
            var arc = CapsuleArcEnclosure.prepare(initialPose, target, cardinal.frame().up(), numeric);
            var window = union(accepted.placement().bounds(), initial.bounds(), arc.allChildCaptureBounds(numeric), target.bounds());
            return new CardinalToFreeGeometry(initial, target, initialPose, targetPose, window, actorGeometry, null, observedProfile);
        }
        var shape = new PlayerBodyShape.FreeCapsule(dimensions);
        var sourceResult = CardinalContainedCapsuleSource.prepare(accepted, dimensions, numeric);
        if (!(sourceResult instanceof CardinalContainedCapsuleSource.Prepared source)) {
            var unavailable = (CardinalContainedCapsuleSource.Unavailable) sourceResult;
            throw new IllegalStateException("Cardinal-to-Free contained source unavailable: "
                    + unavailable.reason() + "; " + unavailable.diagnostics(), unavailable.cause().orElse(null));
        }
        var initialPlacement = source.placement();
        var targetPlacement = new PlayerBodyPlacement(accepted.placement().center(), desired, shape, numeric);
        var initialPose = source.pose();
        var targetPose = CapsulePlacementMath.pose(targetPlacement, numeric);
        var arcPath = CapsuleArcEnclosure.prepare(initialPose, targetPlacement, cardinal.frame().up(), numeric);
        var allChildren = arcPath.allChildCaptureBounds(numeric);
        var globalWindow = union(accepted.placement().bounds(), initialPlacement.bounds(),
                allChildren, targetPlacement.bounds());
        return new CardinalToFreeGeometry(initialPlacement, targetPlacement, initialPose, targetPose,
                globalWindow, actorGeometry, source, null);
    }

    record ContainmentFace(String face, double sourceBoundary, double outwardBoundary, boolean contained) {
        @Override public String toString() {
            return face + "{source=" + Double.toHexString(sourceBoundary)
                    + ",outward=" + Double.toHexString(outwardBoundary) + ",contained=" + contained + "}";
        }
    }
    record ContainmentDiagnostic(Aabb outwardBounds, List<ContainmentFace> faces, long numericWork) { }
    record ArcDiagnostic(CapsuleArcValidator.Failure failure, List<CapsuleArcValidator.Query> queries,
            int regions, long numericWork, ContactAccuracy accuracy, long queryCalls,
            CapsuleContactQueryBudget.Totals queryWork) { }

    // Read only the already-produced evidence: diagnosis must not spend or replace either ledger.
    static ContainmentDiagnostic containmentDiagnostic(Aabb source, CardinalCapsuleContainment.Result result) {
        Aabb outward = result.outwardCapsuleBounds();
        return new ContainmentDiagnostic(outward, List.of(
                new ContainmentFace("minX", source.min().x(), outward.min().x(), source.min().x() <= outward.min().x()),
                new ContainmentFace("minY", source.min().y(), outward.min().y(), source.min().y() <= outward.min().y()),
                new ContainmentFace("minZ", source.min().z(), outward.min().z(), source.min().z() <= outward.min().z()),
                new ContainmentFace("maxX", source.max().x(), outward.max().x(), source.max().x() >= outward.max().x()),
                new ContainmentFace("maxY", source.max().y(), outward.max().y(), source.max().y() >= outward.max().y()),
                new ContainmentFace("maxZ", source.max().z(), outward.max().z(), source.max().z() >= outward.max().z())),
                result.numericWork());
    }

    static ArcDiagnostic arcDiagnostic(CapsuleArcValidator.Failed failed, CapsuleContactQueryBudget queries) {
        return new ArcDiagnostic(failed.failure(), failed.queries(), failed.regions(), failed.numericWork(),
                FreeOwnerTransaction.ACCURACY, queries.calls(), queries.work());
    }

    private static String cardinalFailureContext(PlayerPhysicalSnapshot accepted,
            WorldCollisionCandidates.ActorGeometry actual, Rotation3d requested, PlayerBodyPlacement initial) {
        var stored = initial.storedCapsuleGeometry();
        return "scope=" + accepted.scope() + "; revision=" + accepted.revision()
                + "; accepted=" + accepted.placement() + "; actual=" + actual
                + "; liveDimensions=" + initial.shape().dimensions() + "; requestQ=" + requested
                + "; initialStored=" + stored + "; nominalHalfLine=" + stored.nominalHalfLine()
                + "; storedHalfLine=" + stored.storedHalfLine();
    }

    private static boolean isCurrentRequest(Request request) {
        // The owning server closure checks exact Session/connection/scope/proposal/correction/TTL on every call.
        return request != null && request.rawDesiredQ() != null
                && (!(request instanceof MobilityTransitionRequest mobility) || mobility.current())
                && (!(request instanceof ProneTransitionRequest prone) || prone.current());
    }

    private static Unavailable captureUnavailable(WorldCollisionCandidates.Unavailable unavailable) {
        return new Unavailable("mode collision capture unavailable: " + unavailable.reason() + ": "
                + unavailable.detail(), unavailable.cause());
    }

    /**
     * Covers every validator child enclosure using the actual full-interval endpoint box.
     * The child's reference capsule can differ from the root reference by at most E at both
     * endpoints. Its binary64 distanceUpper is enclosed with the same outward operation order
     * as CapsuleArcEnclosure.distanceUpper, then translated back to the root reference.
     */
    static Aabb allChildArcCaptureBounds(CapsuleArcEnclosure.Enclosure wholeArc,
                                         StoredDyadic.Work sameWork) {
        Objects.requireNonNull(wholeArc); Objects.requireNonNull(sameWork);
        sameWork.charge(ARC_CAPTURE_WINDOW_WORK);
        if (!wholeArc.uses(sameWork))
            throw new IllegalArgumentException("all-child arc window requires the enclosure Work");

        var root = wholeArc.reference();
        var endpoints = new Vec3d[] {root.centerline().start(), root.centerline().end()};
        double radius = finite(root.radius(), "reference radius");
        double excess = finite(wholeArc.padded().radius() - radius, "full-interval epsilon");
        if (excess < 0)
            throw new IllegalStateException("full-interval padded radius is smaller than its reference radius");
        // A zero root epsilon proves that every corresponding endpoint is fixed. Preserve the
        // exact invariant window instead of introducing a subnormal-only artificial margin.
        if (excess == 0) return wholeArc.bounds();

        double e = nextUp(excess, "endpoint deviation upper bound");
        double d = nextUp(finite(e + e, "child endpoint difference"), "child endpoint difference upper bound");
        double q = nextUp(finite(d * d, "child endpoint square"), "child endpoint square upper bound");
        double squared = 0;
        for (int axis = 0; axis < 3; axis++)
            squared = nextUp(finite(squared + q, "child squared-distance accumulation"),
                    "child squared-distance upper bound");
        double childEpsilon = nextUp(finite(Math.sqrt(squared), "child distance square root"),
                "child distance upper bound");
        double childRadius = nextUp(finite(radius + childEpsilon, "child padded radius"),
                "child padded-radius upper bound");
        double rootRadius = nextUp(finite(childRadius + e, "root-reference transfer radius"),
                "root-reference transfer upper bound");

        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double first = component(endpoints[0], axis), second = component(endpoints[1], axis);
            lower[axis] = nextDown(finite(Math.min(first, second) - rootRadius,
                    "root-reference lower bound"), "root-reference outward lower bound");
            upper[axis] = nextUp(finite(Math.max(first, second) + rootRadius,
                    "root-reference upper bound"), "root-reference outward upper bound");
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]),
                new Vec3d(upper[0], upper[1], upper[2]));
    }

    /** Covers every resize validator child enclosure; even a zero excess follows the approved outward chain. */
    static Aabb allChildResizeCaptureBounds(CapsuleResizeEnclosure.TransitionCover wholeResize,
                                           StoredDyadic.Work sameWork) {
        Objects.requireNonNull(wholeResize); Objects.requireNonNull(sameWork);
        // Fixed scalar work: finite checks, nextUp/down, three axes and bounded array setup.
        sameWork.charge(RESIZE_CAPTURE_WINDOW_WORK);
        if (!wholeResize.uses(sameWork))
            throw new IllegalArgumentException("all-child resize window requires the enclosure Work");

        var root = wholeResize.reference();
        var endpoints = new Vec3d[] {root.centerline().start(), root.centerline().end()};
        double fullRadius = finite(wholeResize.padded().radius(), "full-interval padded radius");
        double referenceRadius = finite(root.radius(), "reference radius");
        double excess = finite(fullRadius - referenceRadius, "full-interval resize epsilon");
        if (excess < 0)
            throw new IllegalStateException("full-interval padded radius is smaller than its reference radius");
        double e = nextUp(excess, "resize endpoint deviation upper bound");
        double d = nextUp(finite(e + e, "resize child endpoint difference"), "resize child endpoint difference upper bound");
        double q = nextUp(finite(d * d, "resize child endpoint square"), "resize child endpoint square upper bound");
        double squared = 0;
        for (int axis = 0; axis < 3; axis++)
            squared = nextUp(finite(squared + q, "resize child squared-distance accumulation"),
                    "resize child squared-distance upper bound");
        double childEpsilon = nextUp(finite(Math.sqrt(squared), "resize child distance square root"),
                "resize child distance upper bound");
        double childRadius = nextUp(finite(fullRadius + childEpsilon, "resize child padded radius"),
                "resize child padded-radius upper bound");
        double rootRadius = nextUp(finite(childRadius + e, "resize root-reference transfer radius"),
                "resize root-reference transfer upper bound");

        double[] lower = new double[3], upper = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double first = component(endpoints[0], axis), second = component(endpoints[1], axis);
            lower[axis] = nextDown(finite(Math.min(first, second) - rootRadius,
                    "resize root-reference lower bound"), "resize root-reference outward lower bound");
            upper[axis] = nextUp(finite(Math.max(first, second) + rootRadius,
                    "resize root-reference upper bound"), "resize root-reference outward upper bound");
        }
        return new Aabb(new Vec3d(lower[0], lower[1], lower[2]),
                new Vec3d(upper[0], upper[1], upper[2]));
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            case 2 -> vector.z();
            default -> throw new IndexOutOfBoundsException(axis);
        };
    }

    private static double finite(double value, String operation) {
        if (!Double.isFinite(value))
            throw new IllegalArgumentException("all-child arc window " + operation + " is non-finite");
        return value;
    }

    private static double nextUp(double value, String operation) {
        return finite(Math.nextUp(finite(value, operation)), operation + " after outward rounding");
    }

    private static double nextDown(double value, String operation) {
        return finite(Math.nextDown(finite(value, operation)), operation + " after outward rounding");
    }

    private static Aabb union(Aabb... bounds) {
        Vec3d min = bounds[0].min(), max = bounds[0].max();
        for (int index = 1; index < bounds.length; index++) {
            min = min.min(bounds[index].min()); max = max.max(bounds[index].max());
        }
        return new Aabb(min, max);
    }
}
