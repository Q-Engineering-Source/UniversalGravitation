package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;
import wi.unigrav.core.player.WorldCollisionCandidates.Unavailable;

/** Static actual-capsule observations. Session ownership and movement/commit validity belong to the caller. */
final class CapsuleContactCollector {
    private CapsuleContactCollector() { }

    enum Failure { INVALID_POSE, CONTEXT_CHANGED, OVERLAPPING, UNRESOLVED, BUDGET_EXHAUSTED }

    record Query(int ordinal, Aabb rawAabb, ContactResult contact) { }

    sealed interface Result permits VerifiedContacts, Failed {
        List<Query> queries();
        long calls();
        CapsuleContactQueryBudget.Totals work();
        long numericWork();
        int peakDyadicBits();
    }

    /** All captured candidates were resolved as separated/touching; this is not support or commit permission. */
    static final class VerifiedContacts implements Result {
        private final PlayerPhysicalSnapshot accepted;
        private final Pose pose;
        private final Snapshot capture;
        private final CapsuleContactQueryBudget budget;
        private final Aabb expectedWindow;
        private final CapsuleObservationEndpoint endpoint;
        private final CertifiedReferenceEndpoint.CardinalRootObservation containedRoot;
        private final List<Query> queries;
        private final long calls;
        private final CapsuleContactQueryBudget.Totals work;

        private VerifiedContacts(PlayerPhysicalSnapshot accepted, Pose pose, Snapshot capture,
                                 CapsuleContactQueryBudget budget, Aabb expectedWindow, List<Query> queries,
                                 CapsuleObservationEndpoint endpoint,
                                 CertifiedReferenceEndpoint.CardinalRootObservation containedRoot) {
            this.accepted = accepted;
            this.pose = pose;
            this.capture = capture;
            this.budget = budget;
            this.expectedWindow = expectedWindow;
            this.endpoint = endpoint;
            this.containedRoot = containedRoot;
            this.queries = List.copyOf(queries);
            this.calls = budget.calls();
            this.work = budget.work();
        }

        /** Exact transaction objects plus a current live geometry/tick observation; never owner authority. */
        boolean matches(PlayerPhysicalSnapshot exactAccepted, Pose exactPose, Snapshot exactCapture,
                        CapsuleContactQueryBudget exactBudget) {
            if (accepted != exactAccepted || pose != exactPose || capture != exactCapture || budget != exactBudget)
                return false;
            if (endpoint != null && (endpoint.pose() != pose || endpoint.owner() != accepted || !endpoint.current()))
                return false;
            if (containedRoot != null && !containedRoot.matches((CertifiedReferenceEndpoint) endpoint,capture,expectedWindow)) return false;
            boolean context = capture.capsuleContextFailure(accepted, expectedWindow).isEmpty();
            return context && (endpoint == null || endpoint.current())
                    && (containedRoot == null || containedRoot.current());
        }

        PlayerPhysicalSnapshot accepted() { return accepted; }
        Pose pose() { return pose; }
        Snapshot capture() { return capture; }
        CapsuleContactQueryBudget budget() { return budget; }
        PlayerPhysicalSnapshot.Scope scope() { return accepted.scope(); }
        long revision() { return accepted.revision(); }
        long tick() { return capture.captureTick(); }
        Aabb window() { return capture.footprint().requestedBounds(); }
        Capsule actualCapsule() { return pose.capsule(); }
        public List<Query> queries() { return queries; }
        public long calls() { return calls; }
        public CapsuleContactQueryBudget.Totals work() { return work; }
        public long numericWork() { return pose.numericWorkDelta(); }
        public int peakDyadicBits() { return pose.peakDyadicBits(); }
    }

    /** firstUnqueried..candidateCount describes the exact remaining native ordinals. */
    record Failed(Failure failure, List<Query> queries, int firstUnqueried, int candidateCount,
                  long calls, CapsuleContactQueryBudget.Totals work, boolean budgetTerminal,
                  long numericWork, int peakDyadicBits, Optional<Unavailable> contextFailure,
                  Optional<CapsuleContactQueryBudget.BudgetExhausted> budgetFailure) implements Result {
        Failed { queries = List.copyOf(queries); }
    }

    static Result collect(PlayerPhysicalSnapshot accepted, Pose actualPose, Snapshot capture,
                          ContactAccuracy accuracy, CapsuleContactQueryBudget sameBudget) {
        return collectWithin(accepted, actualPose, capture, accepted.placement().bounds(), accuracy, sameBudget);
    }

    static Result collectWithin(PlayerPhysicalSnapshot accepted, Pose actualPose, Snapshot capture,
                                Aabb expectedWindow, ContactAccuracy accuracy, CapsuleContactQueryBudget sameBudget) {
        return collectWithin(accepted, actualPose, capture, expectedWindow, accuracy, sameBudget, null);
    }

    static Result collectWithin(CertifiedReferenceEndpoint endpoint, Snapshot capture,
                                Aabb expectedWindow, ContactAccuracy accuracy) {
        Objects.requireNonNull(endpoint).requireCurrent();
        return collectWithin(endpoint.owner(), endpoint.pose(), capture, expectedWindow, accuracy,
                endpoint.transaction().queries(), endpoint);
    }

    static Result collectWithin(InstalledFreeEndpoint endpoint, Snapshot capture,
                                Aabb expectedWindow, ContactAccuracy accuracy) {
        endpoint.requireCurrent();
        return collectWithin(endpoint.owner(), endpoint.pose(), capture, expectedWindow, accuracy,
                endpoint.transaction().queries(), endpoint);
    }

    static Result collectWithin(CertifiedReferenceEndpoint.CardinalRootObservation root, Snapshot capture,
                                Aabb certifiedQueryWindow, ContactAccuracy accuracy) {
        Objects.requireNonNull(root).requireCurrent();
        if (!root.matches(root.endpoint(),capture,certifiedQueryWindow) || accuracy != FreeOwnerTransaction.ACCURACY)
            throw new IllegalArgumentException("contained root collector requires its exact capture and certified query window");
        var endpoint=root.endpoint();
        return collectWithin(endpoint.owner(),root.pose(),capture,certifiedQueryWindow,accuracy,
                endpoint.transaction().queries(),endpoint,root);
    }

    private static Result collectWithin(PlayerPhysicalSnapshot accepted, Pose actualPose, Snapshot capture,
                                Aabb expectedWindow, ContactAccuracy accuracy, CapsuleContactQueryBudget sameBudget,
                                CapsuleObservationEndpoint endpoint) {
        return collectWithin(accepted,actualPose,capture,expectedWindow,accuracy,sameBudget,endpoint,null);
    }

    private static Result collectWithin(PlayerPhysicalSnapshot accepted, Pose actualPose, Snapshot capture,
                                Aabb expectedWindow, ContactAccuracy accuracy, CapsuleContactQueryBudget sameBudget,
                                CapsuleObservationEndpoint endpoint,
                                CertifiedReferenceEndpoint.CardinalRootObservation containedRoot) {
        Objects.requireNonNull(expectedWindow, "expectedWindow");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(actualPose, "actualPose");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(accuracy, "accuracy");
        Objects.requireNonNull(sameBudget, "sameBudget");
        List<Query> queries = new ArrayList<>();
        if (!contains(expectedWindow, actualPose.placement().bounds())
                || (containedRoot == null ? !(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                    : !(endpoint instanceof CertifiedReferenceEndpoint certified)
                        || !containedRoot.matches(certified,capture,expectedWindow))
                || !(actualPose.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || (endpoint == null ? actualPose.placement() != accepted.placement()
                    || actualPose.capsule() != accepted.placement().capsule()
                    : endpoint.owner() != accepted || endpoint.pose() != actualPose || !endpoint.current())) {
            return failed(Failure.INVALID_POSE, queries, capture, actualPose, sameBudget,
                    Optional.empty(), Optional.empty());
        }
        var context = capture.capsuleContextFailure(accepted, expectedWindow);
        if (context.isPresent()) return failed(Failure.CONTEXT_CHANGED, queries, capture, actualPose, sameBudget,
                context, Optional.empty());
        boolean overlapping = false;
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
            Aabb box = capture.boxes().get(ordinal);
            ContactResult contact;
            try {
                contact = sameBudget.query(actualPose.capsule(), box, accuracy);
            } catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
                return failed(Failure.BUDGET_EXHAUSTED, queries, capture, actualPose, sameBudget,
                        Optional.empty(), Optional.of(exhausted));
            }
            // Unexpected CHB failures propagate unchanged; no unknown partial Work becomes zero.
            queries.add(new Query(ordinal, box, contact));
            if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
            context = capture.capsuleContextFailure(accepted, expectedWindow);
            if (context.isPresent()) return failed(Failure.CONTEXT_CHANGED, queries, capture, actualPose, sameBudget,
                    context, Optional.empty());
            if (contact.relation() == Relation.UNRESOLVED) {
                return failed(Failure.UNRESOLVED, queries, capture, actualPose, sameBudget,
                        Optional.empty(), Optional.empty());
            }
            overlapping |= contact.relation() == Relation.OVERLAPPING;
        }
        context = capture.capsuleContextFailure(accepted, expectedWindow);
        if (context.isPresent()) return failed(Failure.CONTEXT_CHANGED, queries, capture, actualPose, sameBudget,
                context, Optional.empty());
        if (overlapping) return failed(Failure.OVERLAPPING, queries, capture, actualPose, sameBudget,
                Optional.empty(), Optional.empty());
        if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
        return new VerifiedContacts(accepted, actualPose, capture, sameBudget, expectedWindow, queries, endpoint, containedRoot);
    }

    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }

    private static Failed failed(Failure failure, List<Query> queries, Snapshot capture, Pose pose,
                                 CapsuleContactQueryBudget budget, Optional<Unavailable> context,
                                 Optional<CapsuleContactQueryBudget.BudgetExhausted> exhausted) {
        return new Failed(failure, queries, queries.size(), capture.boxes().size(), budget.calls(), budget.work(),
                budget.terminal(), pose.numericWorkDelta(), pose.peakDyadicBits(), context, exhausted);
    }
}
