package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import java.util.Objects;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;

/** A bounded mathematical lineage; original actor authority and native world fit remain owner duties. */
final class CapsulePathSource {
    static final int MAX_SEGMENTS = 16;
    private record Context(PlayerPhysicalSnapshot original, Snapshot capture, Aabb window,
            ContactAccuracy accuracy, CapsuleContactQueryBudget queries, StoredDyadic.Work work) { }
    private final Context context;
    private final Pose pose;
    private final CapsulePathSource previous;
    private final CapsuleArcValidator.VerifiedArc arc;
    private final CapsuleResizeValidator.VerifiedResize resize;
    private final CapsuleTranslationValidator.VerifiedTranslation translation;
    private final CardinalCapsuleContainment.Contained containment;
    private final CapsuleMovementResolver.Resolved movement;
    private final CapsuleDepenetrationPrepared.Verified recovery;
    private final int completedSegments;
    private final NativeCardinalBodyProfile.Fit nativeProfileFit;

    private CapsulePathSource(Context context, Pose pose, CardinalCapsuleContainment.Contained containment,
            CapsulePathSource previous, CapsuleArcValidator.VerifiedArc arc,
            CapsuleResizeValidator.VerifiedResize resize,
            CapsuleTranslationValidator.VerifiedTranslation translation, int completedSegments) {
        this(context, pose, containment, previous, arc, resize, translation, null, completedSegments);
    }
    private CapsulePathSource(Context context, Pose pose, CardinalCapsuleContainment.Contained containment,
            CapsulePathSource previous, CapsuleArcValidator.VerifiedArc arc,
            CapsuleResizeValidator.VerifiedResize resize, CapsuleTranslationValidator.VerifiedTranslation translation,
            CapsuleMovementResolver.Resolved movement, int completedSegments) {
        this(context, pose, containment, previous, arc, resize, translation, movement, completedSegments, null);
    }
    private CapsulePathSource(Context context, Pose pose, CardinalCapsuleContainment.Contained containment,
            CapsulePathSource previous, CapsuleArcValidator.VerifiedArc arc,
            CapsuleResizeValidator.VerifiedResize resize, CapsuleTranslationValidator.VerifiedTranslation translation,
            CapsuleMovementResolver.Resolved movement, int completedSegments, NativeCardinalBodyProfile.Fit nativeProfileFit) {
        this.nativeProfileFit = nativeProfileFit;
        this.recovery = null;
        this.movement = movement;
        this.context = context;
        this.pose = pose;
        this.containment = containment;
        this.previous = previous;
        this.arc = arc;
        this.resize = resize;
        this.translation = translation;
        this.completedSegments = completedSegments;
    }

    private CapsulePathSource(CapsulePathSource source, CapsuleDepenetrationPrepared.Verified proof) {
        context = source.context; pose = proof.end(); previous = source; recovery = proof;
        arc = null; resize = null; translation = null; movement = null; containment = null; nativeProfileFit = null;
        completedSegments = source.completedSegments + 1;
    }
    static CapsulePathSource continueAfter(CapsuleDepenetrationPrepared.Verified proof, StoredDyadic.Work work) {
        var source = Objects.requireNonNull(proof).source();
        if (source.work() != work || !proof.matchesBound(source) || source.completedSegments >= MAX_SEGMENTS
                || !contains(source.window(), proof.end().placement().bounds()))
            throw new InvalidSourceException("depenetration proof/source context changed");
        source.chargeMetadata();
        return new CapsulePathSource(source, proof);
    }

    static CapsulePathSource actualFree(PlayerPhysicalSnapshot originalActual, Pose exactAcceptedPose,
            Snapshot capture, Aabb globalWindow, ContactAccuracy exactAccuracy,
            CapsuleContactQueryBudget sameQueries, StoredDyadic.Work sameWork) {
        return initial(originalActual, exactAcceptedPose, null, capture, globalWindow,
                exactAccuracy, sameQueries, sameWork);
    }
    static CapsulePathSource containedCardinal(CardinalCapsuleContainment.Contained exactCertificate,
            Pose exactInitialVirtualPose, Snapshot capture, Aabb globalWindow, ContactAccuracy exactAccuracy,
            CapsuleContactQueryBudget sameQueries, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(exactCertificate, "certificate");
        return initial(exactCertificate.source(), exactInitialVirtualPose, exactCertificate, capture,
                globalWindow, exactAccuracy, sameQueries, sameWork);
    }
    static CapsulePathSource nativeProfileCardinal(FreeOwnerTransaction transaction,
            NativeCardinalBodyProfile.Fit fit, Pose pose, Snapshot capture, Aabb globalWindow) {
        Objects.requireNonNull(fit);
        transaction.requireCurrent();
        var context = new Context(transaction.accepted(), capture, globalWindow,
                FreeOwnerTransaction.ACCURACY, transaction.queries(), transaction.numeric());
        var result = new CapsulePathSource(context, pose, null, null, null, null, null, null, 0, fit);
        transaction.numeric().charge(1);
        if (!contains(globalWindow, transaction.accepted().placement().bounds())
                || !contains(globalWindow, pose.placement().bounds()) || !result.valid() || !result.current())
            throw new InvalidSourceException("native profile root lost full fit or exact owner");
        return result;
    }
    private static CapsulePathSource initial(PlayerPhysicalSnapshot original, Pose pose,
            CardinalCapsuleContainment.Contained certificate, Snapshot capture, Aabb window,
            ContactAccuracy accuracy, CapsuleContactQueryBudget queries, StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work").charge(1);
        var context = new Context(Objects.requireNonNull(original, "original"),
                Objects.requireNonNull(capture, "capture"), Objects.requireNonNull(window, "window"),
                Objects.requireNonNull(accuracy, "accuracy"), Objects.requireNonNull(queries, "queries"), work);
        var result = new CapsulePathSource(context, Objects.requireNonNull(pose, "pose"), certificate,
                null, null, null, null, 0);
        if (!result.valid()) throw new InvalidSourceException("root pose/certificate identity mismatch");
        if (!contains(window, original.placement().bounds()) || !contains(window, pose.placement().bounds()))
            throw new InvalidSourceException("global window excludes actual or initial virtual bounds");
        return result;
    }

    static CapsulePathSource continueAfter(CapsuleArcValidator.VerifiedArc proof, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(proof, "proof");
        return continuation(proof.source(), proof.target(), proof, null, null, sameWork);
    }
    static CapsulePathSource continueAfter(CapsuleResizeValidator.VerifiedResize proof, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(proof, "proof");
        return continuation(proof.source(), proof.target(), null, proof, null, sameWork);
    }
    static CapsulePathSource continueAfter(CapsuleTranslationValidator.VerifiedTranslation proof,
            StoredDyadic.Work sameWork) {
        Objects.requireNonNull(proof, "proof");
        return continuation(proof.source(), proof.target(), null, null, proof, sameWork);
    }
    static CapsulePathSource continueAfter(CapsuleMovementResolver.Resolved movement, StoredDyadic.Work sameWork) {
        Objects.requireNonNull(movement);
        CapsulePathSource source = movement.source();
        if (source == null || source.work() != sameWork || !source.valid() || !source.current()
                || !movement.matchesBound(source)) throw new InvalidSourceException("movement/source context changed");
        source.chargeMetadata();
        if (source.completedSegments >= MAX_SEGMENTS || !contains(source.window(), movement.end().placement().bounds()))
            throw new InvalidSourceException("movement continuation exceeds its source window or segment limit");
        return new CapsulePathSource(source.context, movement.end(), null, source, null, null, null, movement,
                source.completedSegments + 1);
    }
    private static CapsulePathSource continuation(CapsulePathSource source, PlayerBodyPlacement target,
            CapsuleArcValidator.VerifiedArc arc, CapsuleResizeValidator.VerifiedResize resize,
            CapsuleTranslationValidator.VerifiedTranslation translation, StoredDyadic.Work work) {
        Objects.requireNonNull(work, "work");
        if (source.work() != work) throw new InvalidSourceException("foreign numeric ledger");
        source.chargeMetadata();
        if (source.completedSegments >= MAX_SEGMENTS)
            throw new InvalidSourceException("capsule lineage segment limit " + MAX_SEGMENTS);
        boolean proofBound = arc != null ? resize == null && translation == null && arc.matchesBound(source)
                : resize != null ? translation == null && resize.matchesBound(source)
                : translation != null && translation.matchesBound(source);
        if (!source.valid() || !source.current() || !proofBound)
            throw new InvalidSourceException("proof/source context is no longer current");
        Pose pose = translation == null
                ? CapsulePlacementMath.pose(target, work)
                : CapsulePlacementMath.chargedTranslationPose(target, work);
        if (!source.current()) throw new InvalidSourceException("actor context changed while observing target");
        return new CapsulePathSource(source.context, pose, null, source, arc, resize, translation,
                source.completedSegments + 1);
    }

    void requireBodyProfileCurrentAfterInstall() {
        CapsulePathSource root = this;
        for (int left = completedSegments; left > 0; left--) root = root.previous;
        if (root.nativeProfileFit != null) root.nativeProfileFit.requireInstalledProfileCurrent();
    }
    void chargeMetadata() { context.work.charge(completedSegments + 1); }
    boolean valid() {
        CapsulePathSource node = this;
        for (int left = completedSegments; left > 0; left--) {
            if (node.previous == null || node.context != node.previous.context
                    || node.completedSegments != node.previous.completedSegments + 1
                    || node.pose.capsule() != node.pose.placement().capsule()) return false;
            if (node.recovery != null) {
                if (node.arc != null || node.resize != null || node.translation != null || node.movement != null
                        || node.recovery.source() != node.previous || node.recovery.end() != node.pose
                        || !node.recovery.matchesBound(node.previous)) return false;
            } else if (node.movement != null) {
                if (node.arc != null || node.resize != null || node.translation != null
                        || node.movement.source() != node.previous || node.movement.end() != node.pose
                        || !node.movement.matchesBound(node.previous)) return false;
            } else if (node.arc != null) {
                if (node.resize != null || node.translation != null || node.arc.source() != node.previous
                        || node.arc.target() != node.pose.placement()) return false;
            } else if (node.resize != null) {
                if (node.translation != null || node.resize.source() != node.previous
                        || node.resize.target() != node.pose.placement()) return false;
            } else if (node.translation == null || node.translation.source() != node.previous
                    || node.translation.target() != node.pose.placement()) return false;
            node = node.previous;
        }
        if (node.previous != null || node.completedSegments != 0
                || node.pose.capsule() != node.pose.placement().capsule()) return false;
        if (node.nativeProfileFit != null)
            return node.containment == null && node.context.original.placement().shape() instanceof PlayerBodyShape.Cardinal
                    && node.nativeProfileFit.matches(node.context.original, node.pose, node.context.capture,
                            node.context.window, node.context.work);
        if (node.containment == null)
            return node.context.original.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    && node.pose.placement() == node.context.original.placement();
        return node.context.original.placement().shape() instanceof PlayerBodyShape.Cardinal
                && node.containment.matches(node.context.original, node.pose.placement(), context.work);
    }
    boolean isContainedRoot(CardinalCapsuleContainment.Contained exactCertificate) {
        return completedSegments == 0 && previous == null && containment == exactCertificate && valid();
    }
    boolean isNativeProfileRoot(NativeCardinalBodyProfile.Fit exactFit) {
        return exactFit != null && completedSegments == 0 && previous == null
                && containment == null && nativeProfileFit == exactFit && valid() && current();
    }
    /** Only the two public arc root families may mint a generic signed half-space receipt. */
    boolean hasGenericArcRoot() {
        CapsulePathSource root = this;
        for (int left = completedSegments; left > 0; left--) {
            if (root.previous == null) return false;
            root = root.previous;
        }
        if (root.previous != null || root.completedSegments != 0 || root.nativeProfileFit != null) return false;
        if (root.containment != null)
            return root.context.original.placement().shape() instanceof PlayerBodyShape.Cardinal;
        return root.context.original.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && root.pose.placement() == root.context.original.placement();
    }
    boolean isTranslatedCardinalRoot(CardinalCapsuleContainment.Contained certificate,
            CapsuleTranslationValidator.VerifiedTranslation proof) {
        return completedSegments == 1 && previous != null && translation == proof
                && proof != null && proof.source() == previous && proof.matchesBound(previous)
                && previous.isContainedRoot(certificate) && pose.placement() == proof.target()
                && valid() && current();
    }
    boolean current() {
        if (context.capture.capsuleContextFailure(context.original, context.window).isPresent()) return false;
        var root = this;
        for (int i = completedSegments; i > 0; i--) {
            if (root.previous == null) return false;
            root = root.previous;
        }
        return root.nativeProfileFit == null || root.nativeProfileFit.current();
    }
    boolean sameContext(CapsulePathSource other) { return other != null && context == other.context; }
    PlayerPhysicalSnapshot actualSource() { return context.original; }
    Pose start() { return pose; }
    Snapshot capture() { return context.capture; }
    Aabb window() { return context.window; }
    ContactAccuracy accuracy() { return context.accuracy; }
    CapsuleContactQueryBudget queries() { return context.queries; }
    StoredDyadic.Work work() { return context.work; }
    int completedSegments() { return completedSegments; }

    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    static final class InvalidSourceException extends IllegalArgumentException {
        private InvalidSourceException(String message) { super(message); }
    }
}
