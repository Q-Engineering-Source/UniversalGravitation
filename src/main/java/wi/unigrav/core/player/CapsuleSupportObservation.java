package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Witness;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete geometry observations, without standing, owner or installation authority. */
final class CapsuleSupportObservation {
    private CapsuleSupportObservation() { }
    enum Reason { WINDOW_MISMATCH, FOOT_FAILED, CONTEXT_CHANGED, NUMERIC_WORK_EXHAUSTED }
    sealed interface Result permits Complete, Failed { }
    record Evaluation(int ordinal, CapsuleSupportSlope.Result slope) { }
    record Failed(Reason reason, Optional<CapsuleFootContacts.Result> foot, List<Evaluation> evaluations,
                  long workBefore, long workAfter) implements Result {
        Failed { evaluations = List.copyOf(evaluations); }
    }

    static Result observe(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose actualPose,
                          WorldCollisionCandidates.Snapshot capture, Aabb expectedWindow, double reach,
                          ContactAccuracy accuracy, CapsuleContactQueryBudget sameQueries,
                          StoredDyadic.Work sameWork) {
        return observe(accepted, actualPose, capture, expectedWindow, reach, accuracy, sameQueries, sameWork, null);
    }

    static Result observe(CertifiedReferenceEndpoint endpoint, WorldCollisionCandidates.Snapshot capture,
                          Aabb expectedWindow, double reach, ContactAccuracy accuracy) {
        endpoint.requireCurrent();
        return observe(endpoint.owner(), endpoint.pose(), capture, expectedWindow, reach, accuracy,
                endpoint.transaction().queries(), endpoint.transaction().numeric(), endpoint);
    }

    static Result observe(InstalledFreeEndpoint endpoint, WorldCollisionCandidates.Snapshot capture,
                          Aabb expectedWindow, double reach, ContactAccuracy accuracy) {
        endpoint.requireCurrent();
        return observe(endpoint.owner(), endpoint.pose(), capture, expectedWindow, reach, accuracy,
                endpoint.transaction().queries(), endpoint.transaction().numeric(), endpoint);
    }

    private static Result observe(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose actualPose,
                          WorldCollisionCandidates.Snapshot capture, Aabb expectedWindow, double reach,
                          ContactAccuracy accuracy, CapsuleContactQueryBudget sameQueries,
                          StoredDyadic.Work sameWork, CapsuleObservationEndpoint endpoint) {
        Objects.requireNonNull(accepted); Objects.requireNonNull(actualPose); Objects.requireNonNull(capture);
        Objects.requireNonNull(expectedWindow); Objects.requireNonNull(accuracy);
        Objects.requireNonNull(sameQueries); Objects.requireNonNull(sameWork);
        long before = sameWork.units();
        if (!expectedWindow.equals(endpoint == null ? CapsuleFootContacts.window(accepted, reach)
                : endpoint instanceof InstalledFreeEndpoint installed ? CapsuleFootContacts.window(installed, reach)
                : CapsuleFootContacts.window((CertifiedReferenceEndpoint) endpoint, reach)))
            return new Failed(Reason.WINDOW_MISMATCH, Optional.empty(), List.of(), before, sameWork.units());
        var foot = endpoint == null
                ? CapsuleFootContacts.collect(accepted, actualPose, capture, reach, accuracy, sameQueries)
                : endpoint instanceof InstalledFreeEndpoint installed ? CapsuleFootContacts.collect(installed, capture, reach, accuracy)
                : CapsuleFootContacts.collect((CertifiedReferenceEndpoint) endpoint, capture, reach, accuracy);
        if (!(foot instanceof CapsuleFootContacts.VerifiedFootObservations verified))
            return new Failed(Reason.FOOT_FAILED, Optional.of(foot), List.of(), before, sameWork.units());
        var evaluations = new ArrayList<Evaluation>();
        var supports = new ArrayList<Support>();
        // Query completeness comes from the private full-body and foot collectors, not an early first contact.
        for (var query : verified.queries()) {
            var slope = CapsuleSupportSlope.certify(verified, query.ordinal(), sameWork);
            evaluations.add(new Evaluation(query.ordinal(), slope));
            if (slope instanceof CapsuleSupportSlope.Uncertain uncertain) {
                if (uncertain.reason() == CapsuleSupportSlope.Reason.NUMERIC_WORK_EXHAUSTED)
                    return new Failed(Reason.NUMERIC_WORK_EXHAUSTED, Optional.of(foot), evaluations, before, sameWork.units());
                if (uncertain.reason() == CapsuleSupportSlope.Reason.CONTEXT_CHANGED)
                    return new Failed(Reason.CONTEXT_CHANGED, Optional.of(foot), evaluations, before, sameWork.units());
            } else {
                supports.add(new Support(query, (CapsuleSupportSlope.Certificate) slope));
            }
        }
        if (!verified.matches(accepted, actualPose, capture, sameQueries))
            return new Failed(Reason.CONTEXT_CHANGED, Optional.of(foot), evaluations, before, sameWork.units());
        return new Complete(verified, evaluations, supports, sameWork.identity(), before, sameWork.units());
    }

    /** The original witness intervals are retained; no unit normal or Cardinal reference is invented. */
    static final class Support {
        private final CapsuleFootContacts.Query query;
        private final CapsuleSupportSlope.Certificate slope;
        private Support(CapsuleFootContacts.Query query, CapsuleSupportSlope.Certificate slope) {
            this.query = query; this.slope = slope;
        }
        int ordinal() { return query.ordinal(); }
        Aabb rawAabb() { return query.rawAabb(); }
        Witness witness() { return query.contact().witness().orElseThrow(); }
        CapsuleFootContacts.Query query() { return query; }
    }

    /** Empty supports do not prove absence when an evaluation is uncertain. */
    static final class Complete implements Result {
        private final CapsuleFootContacts.VerifiedFootObservations foot;
        private final List<Evaluation> evaluations;
        private final List<Support> supports;
        private final Object workIdentity;
        private final long workBefore;
        private final long workAfter;
        private Complete(CapsuleFootContacts.VerifiedFootObservations foot, List<Evaluation> evaluations,
                         List<Support> supports, Object workIdentity, long workBefore, long workAfter) {
            this.foot = foot; this.evaluations = List.copyOf(evaluations); this.supports = List.copyOf(supports);
            this.workIdentity = workIdentity; this.workBefore = workBefore; this.workAfter = workAfter;
        }
        CapsuleFootContacts.VerifiedFootObservations foot() { return foot; }
        List<Evaluation> evaluations() { return evaluations; }
        List<Support> supports() { return supports; }
        long workBefore() { return workBefore; }
        long workAfter() { return workAfter; }
        // Work identity covers this operation. Historical placement/pose construction is the transaction's duty.
        boolean matches(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                        WorldCollisionCandidates.Snapshot capture, Aabb window,
                        CapsuleContactQueryBudget queries, StoredDyadic.Work work) {
            return workIdentity == work.identity() && foot.window().equals(window)
                    && foot.matches(accepted, pose, capture, queries)
                    && supports.stream().allMatch(s -> s.slope.matches(foot, s.ordinal(), work));
        }
    }
}

