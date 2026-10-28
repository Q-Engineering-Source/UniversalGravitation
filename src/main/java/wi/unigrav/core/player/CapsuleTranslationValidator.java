package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.InstalledPoseReceipt;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixReceipt;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.CapsulePlacementMath.Translation;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;

/** Full-fraction fixed-size translation proof; it grants neither partial movement nor owner authority. */
final class CapsuleTranslationValidator {
    private CapsuleTranslationValidator() { }

    enum Failure {
        INVALID_INPUT, CONTEXT_CHANGED, WINDOW_TOO_SMALL, TARGET_NOT_REALIZED, ACTUAL_OVERLAP,
        PATH_NOT_CERTIFIED, UNRESOLVED, PHASE_LIMIT, QUERY_BUDGET, NUMERIC_BUDGET,
        PROFILE_NOT_CERTIFIED
    }
    enum Stage { START, TARGET, PATH }

    /**
     * Actual-radius contacts are endpoint observations. A path boundary is only the padded
     * translation-envelope receipt; its installed contact is not an actual endpoint observation.
     */
    record Query(Stage stage, int ordinal, Aabb box, ContactResult actualContact,
                 BoundaryResult paddedEnvelopeBoundary,
                 CapsuleTranslationPlaneProof.Certificate planeProof) { }

    sealed interface Result permits VerifiedTranslation, Failed {
        List<Query> queries();
        long numericWork();
    }

    record Failed(Failure failure, List<Query> queries, long numericWork,
                  Throwable budgetCause) implements Result {
        Failed { queries = List.copyOf(queries); }
    }

    static final class VerifiedTranslation implements Result {
        private final CapsulePathSource source;
        private final PlayerPhysicalSnapshot accepted;
        private final Pose start;
        private final Translation tube;
        private final Pose actualEnd;
        private final Snapshot capture;
        private final Aabb window;
        private final CapsuleContactQueryBudget budget;
        private final StoredDyadic.Work numeric;
        private final Vec3d delta;
        private final double centerPathUpper;
        private final List<Query> queries;
        private final long numericWork;

        private VerifiedTranslation(CapsulePathSource source, PlayerPhysicalSnapshot accepted,
                Pose start, Translation tube, Pose actualEnd, Snapshot capture, Aabb window,
                CapsuleContactQueryBudget budget, StoredDyadic.Work numeric, Vec3d delta,
                double centerPathUpper, List<Query> queries, long numericWork) {
            this.source = source;
            this.accepted = accepted;
            this.start = start;
            this.tube = tube;
            this.actualEnd = actualEnd;
            this.capture = capture;
            this.window = window;
            this.budget = budget;
            this.numeric = numeric;
            this.delta = delta;
            this.centerPathUpper = centerPathUpper;
            this.queries = List.copyOf(queries);
            this.numericWork = numericWork;
        }

        boolean matchesBound(CapsulePathSource exactSource) {
            return source == exactSource && start == source.start() && accepted == source.actualSource()
                    && capture == source.capture() && window == source.window() && budget == source.queries()
                    && numeric == source.work() && source.valid() && source.current()
                    && tube.start() == start && actualEnd.placement() == target();
        }

        CapsulePathSource source() { return source; }
        PlayerBodyPlacement target() { return actualEnd.placement(); }
        Pose actualEnd() { return actualEnd; }
        Translation tube() { return tube; }
        Vec3d delta() { return delta; }
        double centerPathUpper() { return centerPathUpper; }
        Aabb window() { return window; }
        int completedSegments() { return source.completedSegments() + 1; }
        public List<Query> queries() { return queries; }
        public long numericWork() { return numericWork; }
    }

    static Result validate(CapsulePathSource source, Vec3d requestedDelta) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(requestedDelta, "requestedDelta");
        return validate(source, requestedDelta, null, source.work().units());
    }

    static Result validateToCenter(CapsulePathSource source, Vec3d exactRequiredCenter) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(exactRequiredCenter, "exactRequiredCenter");
        long before = source.work().units();
        List<Query> queries = List.of();
        try {
            // Reserve the immutable result shell before any typed early-return path.
            source.work().charge(128);
            source.work().charge(19);
            Vec3d center = source.start().placement().center();
            if (!Double.isFinite(exactRequiredCenter.x() - center.x())
                    || !Double.isFinite(exactRequiredCenter.y() - center.y())
                    || !Double.isFinite(exactRequiredCenter.z() - center.z())) {
                return failed(Failure.TARGET_NOT_REALIZED, queries, source.work(), before, null);
            }
            Vec3d delta = new Vec3d(
                    StoredDyadic.of(exactRequiredCenter.x(), source.work())
                            .subtract(StoredDyadic.of(center.x(), source.work()), source.work())
                            .roundNearest(source.work()),
                    StoredDyadic.of(exactRequiredCenter.y(), source.work())
                            .subtract(StoredDyadic.of(center.y(), source.work()), source.work())
                            .roundNearest(source.work()),
                    StoredDyadic.of(exactRequiredCenter.z(), source.work())
                            .subtract(StoredDyadic.of(center.z(), source.work()), source.work())
                            .roundNearest(source.work()));
            return validate(source, delta, exactRequiredCenter, before, true);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, queries, source.work(), before, exhausted);
        }
    }

    private static Result validate(CapsulePathSource source, Vec3d requestedDelta,
            Vec3d exactRequiredCenter, long before) {
        return validate(source, requestedDelta, exactRequiredCenter, before, false);
    }

    private static Result validate(CapsulePathSource source, Vec3d requestedDelta,
            Vec3d exactRequiredCenter, long before, boolean resultShellPrepaid) {
        PlayerPhysicalSnapshot accepted = source.actualSource();
        Pose exactStart = source.start();
        Snapshot capture = source.capture();
        Aabb window = source.window();
        ContactAccuracy accuracy = source.accuracy();
        CapsuleContactQueryBudget queryBudget = source.queries();
        StoredDyadic.Work work = source.work();
        List<Query> queries = List.of();
        ContactResult[] startContacts;
        ContactResult[] endContacts;
        try {
            if (!resultShellPrepaid) work.charge(128);
            source.chargeMetadata();
            if (!source.valid()) return failed(Failure.INVALID_INPUT, queries, work, before, null);
            if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
            if (source.completedSegments() >= CapsulePathSource.MAX_SEGMENTS)
                return failed(Failure.PHASE_LIMIT, queries, work, before, null);
            if (!(exactStart.placement().shape() instanceof PlayerBodyShape.FreeCapsule))
                return failed(Failure.INVALID_INPUT, queries, work, before, null);

            int ordinalCount = capture.boxes().size();
            if (ordinalCount > 4096)
                return failed(Failure.INVALID_INPUT, queries, work, before, null);
            int queryCapacity = Math.multiplyExact(ordinalCount, 4);
            // Reserve every bounded backing-array slot before allocation. Each evidence record later
            // prepays its own three-slot immutable-copy credit as it is appended.
            work.charge(6L * ordinalCount);
            queries = new ArrayList<>(queryCapacity);
            startContacts = new ContactResult[ordinalCount];
            endContacts = new ContactResult[ordinalCount];

            Pose observedStart = CapsulePlacementMath.chargedTranslationPose(exactStart.placement(), work);
            work.charge(64);
            if (!sameObservedPose(exactStart, observedStart))
                return failed(Failure.INVALID_INPUT, queries, work, before, null);
            if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);

            Translation tube = CapsulePlacementMath.chargedTranslationTube(exactStart, requestedDelta, work);
            work.charge(24); // Four AABB containment tests, six ordered coordinate comparisons each.
            if (!contains(window, accepted.placement().bounds())
                    || !contains(window, exactStart.placement().bounds())
                    || !contains(window, tube.sweptBounds())) {
                return failed(Failure.WINDOW_TOO_SMALL, queries, work, before, null);
            }
            if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);

            Pose actualEnd = CapsulePlacementMath.chargedFullTranslation(tube, work);
            if (exactRequiredCenter != null) {
                work.charge(11);
                if (!StoredRealizationLimits.sameRaw(actualEnd.placement().center(), exactRequiredCenter)) {
                    return failed(Failure.TARGET_NOT_REALIZED, queries, work, before, null);
                }
            }
            if (!contains(window, actualEnd.placement().bounds()))
                return failed(Failure.WINDOW_TOO_SMALL, queries, work, before, null);
            CapsulePlacementMath.requireWholeTranslationProfile(tube, actualEnd, work);
            double centerPathUpper = CapsulePlacementMath.monotoneCenterTravelUpper(
                    exactStart.placement().center(), actualEnd.placement().center(), work);
            if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);

            boolean endpointUnresolved = false;
            for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
                Aabb box = capture.boxes().get(ordinal);
                Optional<CapsuleTranslationPlaneProof.Certificate> plane =
                        CapsuleTranslationPlaneProof.certify(tube, box, work);
                if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
                if (plane.isPresent()) {
                    work.charge(16);
                    queries.add(new Query(Stage.PATH, ordinal, box, null, null, plane.orElseThrow()));
                    continue;
                }
                ContactResult startContact = observe(queryBudget, exactStart.capsule(), box, accuracy, work);
                queries.add(new Query(Stage.START, ordinal, box, startContact, null, null));
                startContacts[ordinal] = startContact;
                if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
                if (startContact.relation() == Relation.OVERLAPPING)
                    return failed(Failure.ACTUAL_OVERLAP, queries, work, before, null);
                endpointUnresolved |= startContact.relation() == Relation.UNRESOLVED;

                ContactResult endContact = observe(queryBudget, actualEnd.capsule(), box, accuracy, work);
                queries.add(new Query(Stage.TARGET, ordinal, box, endContact, null, null));
                endContacts[ordinal] = endContact;
                if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
                if (endContact.relation() == Relation.OVERLAPPING)
                    return failed(Failure.ACTUAL_OVERLAP, queries, work, before, null);
                endpointUnresolved |= endContact.relation() == Relation.UNRESOLVED;
            }
            if (endpointUnresolved) return failed(Failure.UNRESOLVED, queries, work, before, null);

            boolean pathUnresolved = false;
            boolean pathRejected = false;
            Capsule paddedEnvelopeEnd = null;
            for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
                Aabb box = capture.boxes().get(ordinal);
                // Null slots have an already-recorded whole-interval plane certificate.
                if (startContacts[ordinal] == null) continue;

                if (paddedEnvelopeEnd == null) {
                    paddedEnvelopeEnd = paddedEndpointEnvelope(tube, actualEnd, work);
                }
                BoundaryResult boundary = refineFullPath(source, tube, actualEnd, paddedEnvelopeEnd,
                        box, ordinal, queries, work);
                if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
                if (boundary.kind() == BoundaryKind.UNRESOLVED
                        || boundary.reason().filter(reason -> reason == Reason.EXACT_WORK_LIMIT
                                || reason == Reason.ACCURACY_UNREACHABLE
                                || reason == Reason.NUMERICAL_ENCLOSURE).isPresent()) {
                    pathUnresolved = true;
                } else if (!fullPathReceiptMatches(boundary, tube, actualEnd, paddedEnvelopeEnd,
                        startContacts[ordinal], endContacts[ordinal])) {
                    pathRejected = true;
                }
            }
            if (!source.current()) return failed(Failure.CONTEXT_CHANGED, queries, work, before, null);
            if (pathUnresolved) return failed(Failure.UNRESOLVED, queries, work, before, null);
            if (pathRejected) return failed(Failure.PATH_NOT_CERTIFIED, queries, work, before, null);

            return new VerifiedTranslation(source, accepted, exactStart, tube, actualEnd, capture, window,
                    queryBudget, work, requestedDelta, centerPathUpper, queries, work.units() - before);
        } catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
            return failed(Failure.QUERY_BUDGET, queries, work, before, exhausted);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, queries, work, before, exhausted);
        } catch (CapsulePlacementMath.TranslationProfileNotCertifiedException failure) {
            return failed(Failure.PROFILE_NOT_CERTIFIED, queries, work, before, failure);
        }
        // Unexpected failures retain their original type, cause, and stack at the owner boundary.
    }

    /** Reuses the ordinary whole-path proof for a box which did not overlap at the recovery start. */
    static boolean clearRecoveryObstacle(CapsulePathSource source, Translation tube, Pose end,
            Aabb box, int ordinal, ContactResult startContact, ContactResult endContact, List<Query> evidence) {
        if (!nonpenetrating(startContact.relation()) || !nonpenetrating(endContact.relation())) return false;
        var plane = CapsuleTranslationPlaneProof.certify(tube, box, source.work());
        if (plane.isPresent()) {
            evidence.add(new Query(Stage.PATH, ordinal, box, null, null, plane.orElseThrow()));
            return true;
        }
        Capsule paddedEnd = paddedEndpointEnvelope(tube, end, source.work());
        var boundary = refineFullPath(source, tube, end, paddedEnd, box, ordinal, evidence, source.work());
        return fullPathReceiptMatches(boundary, tube, end, paddedEnd, startContact, endContact);
    }

    private static ContactResult observe(CapsuleContactQueryBudget budget, Capsule capsule,
            Aabb box, ContactAccuracy accuracy, StoredDyadic.Work work) {
        // Observe dispatch, Query record plus append, contact append, and relation flags are prepaid
        // before entering the separately budgeted CHB query pool.
        work.charge(29);
        return budget.query(capsule, box, accuracy);
    }

    private static Capsule paddedEndpointEnvelope(Translation tube, Pose actualEnd, StoredDyadic.Work work) {
        // This is a real caller-side construction, prepaid once and reused for all path attempts.
        work.charge(64);
        return new Capsule(actualEnd.capsule().centerline(), tube.queryCapsule().radius());
    }

    private static BoundaryResult refineFullPath(CapsulePathSource source, Translation tube,
            Pose actualEnd, Capsule paddedEnvelopeEnd, Aabb box, int ordinal,
            List<Query> queries, StoredDyadic.Work work) {
        work.charge(51);
        BoundarySearchHint firstHint = new BoundarySearchHint(0.0, 1.0);
        StoredInstallCandidate install = installation(tube, actualEnd, paddedEnvelopeEnd);
        BoundaryResult first = source.queries().refineTranslationBoundary(tube.queryCapsule(),
                tube.delta(), box, firstHint, install, source.accuracy());
        try {
            work.charge(80);
        } catch (NumericWorkExhaustedException exhausted) {
            // The attempt precharge includes this evidence node and its copy credit. Retain the
            // returned typed result before propagating the exhausted same-Work boundary.
            queries.add(new Query(Stage.PATH, ordinal, box, null, first, null));
            throw exhausted;
        }
        queries.add(new Query(Stage.PATH, ordinal, box, null, first, null));
        if (first.reason().filter(reason -> reason == Reason.NO_PREFIX_CERTIFICATE).isEmpty()) return first;

        work.charge(51); // Retry creates a new hint and reuses the installation candidate.
        BoundaryResult retry = source.queries().refineTranslationBoundary(tube.queryCapsule(),
                tube.delta(), box, new BoundarySearchHint(1.0, 1.0), install, source.accuracy());
        try {
            work.charge(80);
        } catch (NumericWorkExhaustedException exhausted) {
            queries.add(new Query(Stage.PATH, ordinal, box, null, retry, null));
            throw exhausted;
        }
        queries.add(new Query(Stage.PATH, ordinal, box, null, retry, null));
        return retry;
    }

    private static StoredInstallCandidate installation(Translation tube, Pose actualEnd, Capsule paddedEnvelopeEnd) {
        var geometry = actualEnd.placement().storedCapsuleGeometry();
        if (geometry == null) throw new IllegalStateException("translation full end lost actual stored capsule geometry");
        return new StoredInstallCandidate(1.0, tube.start().placement().center(),
                actualEnd.placement().center(), geometry.storedHalfLine(), paddedEnvelopeEnd);
    }

    private static boolean fullPathReceiptMatches(BoundaryResult result, Translation tube, Pose actualEnd,
            Capsule paddedEnvelopeEnd,
            ContactResult actualStartContact, ContactResult actualEndContact) {
        if (result == null || result.reason().isPresent()
                || !nonpenetrating(actualStartContact.relation())
                || !nonpenetrating(actualEndContact.relation())
                || !nonpenetrating(result.startRelation())
                || !StoredRealizationLimits.sameRaw(paddedEnvelopeEnd.radius(), tube.queryCapsule().radius())) {
            return false;
        }
        Optional<PrefixReceipt> prefix = result.prefix();
        if (prefix.isEmpty() || !Double.isFinite(prefix.orElseThrow().endFraction())
                || !StoredRealizationLimits.sameRaw(prefix.orElseThrow().endFraction(), 1.0)) return false;
        PrefixKind prefixKind = prefix.orElseThrow().kind();
        if (prefixKind != PrefixKind.CLEAR_SEPARATED && prefixKind != PrefixKind.NONPENETRATING) return false;
        boolean kindAllowsFull = result.kind() == BoundaryKind.SEPARATED_FULL
                || result.kind() == BoundaryKind.NONPENETRATING_FULL
                || result.kind() == BoundaryKind.CERTIFIED_BOUNDARY;
        if (!kindAllowsFull) return false;
        Optional<InstalledPoseReceipt> receipt = result.installation();
        if (receipt.isEmpty()) return false;
        InstalledPoseReceipt installed = receipt.orElseThrow();
        if (!StoredRealizationLimits.sameRaw(installed.fraction(), 1.0)
                || !StoredRealizationLimits.sameRaw(installed.storedCenter(), actualEnd.placement().center())
                || !StoredRealizationLimits.sameRaw(installed.halfLine(), actualEnd.storedHalfLine())
                || !sameCapsuleGeometry(installed.storedCapsule(), paddedEnvelopeEnd)) return false;
        ContactResult contact = installed.storedContact();
        return contact != null && nonpenetrating(contact.relation());
    }

    private static boolean nonpenetrating(Relation relation) {
        return relation == Relation.SEPARATED || relation == Relation.TOUCHING;
    }

    private static boolean sameObservedPose(Pose expected, Pose observed) {
        return expected.placement() == observed.placement() && expected.capsule() == observed.capsule()
                && StoredRealizationLimits.sameRaw(expected.storedHalfLine(), observed.storedHalfLine())
                && sameCapsuleGeometry(expected.capsule(), observed.capsule());
    }

    private static boolean sameCapsuleGeometry(Capsule first, Capsule second) {
        return first != null && second != null
                && StoredRealizationLimits.sameRaw(first.radius(), second.radius())
                && StoredRealizationLimits.sameRaw(first.centerline().start(), second.centerline().start())
                && StoredRealizationLimits.sameRaw(first.centerline().end(), second.centerline().end());
    }

    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    private static Failed failed(Failure failure, List<Query> queries,
            StoredDyadic.Work numeric, long before, Throwable cause) {
        return new Failed(failure, queries, numeric.units() - before, cause);
    }
}
