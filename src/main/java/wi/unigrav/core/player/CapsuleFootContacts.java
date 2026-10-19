package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Interval;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Witness;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete actual-foot observations only; never support, grounded state or commit authority. */
final class CapsuleFootContacts {
    private CapsuleFootContacts() { }
    enum Classification { CONTACT, NEAR, OUTSIDE, UNCERTAIN }
    enum Failure { FULL_CAPSULE, CONTEXT_CHANGED, BUDGET_EXHAUSTED, UNRESOLVED, OVERLAPPING }
    record Query(int ordinal, Aabb rawAabb, ContactResult contact, Optional<Interval> direction,
                 List<BoundaryResult> boundaries, Classification classification) {
        Query { boundaries = List.copyOf(boundaries); }
    }
    record QualifiedProducerFoot(ContactResult bodyContact, ContactResult footContact, Interval direction) {
        QualifiedProducerFoot {
            Objects.requireNonNull(bodyContact, "bodyContact");
            Objects.requireNonNull(footContact, "footContact");
            Objects.requireNonNull(direction, "direction");
        }
    }
    sealed interface Result permits VerifiedFootObservations, Failed { }
    record Failed(Failure failure, CapsuleContactCollector.Result fullObservation, List<Query> queries,
                  int firstUnqueried, int candidateCount, long calls, CapsuleContactQueryBudget.Totals work,
                  boolean budgetTerminal, Optional<WorldCollisionCandidates.Unavailable> contextFailure,
                  Optional<CapsuleContactQueryBudget.BudgetExhausted> budgetFailure) implements Result {
        Failed { queries = List.copyOf(queries); }
    }
    static final class VerifiedFootObservations implements Result {
        private final CapsuleContactCollector.VerifiedContacts fullObservation;
        private final Capsule footSphere;
        private final Aabb window;
        private final Aabb requiredFootWindow;
        private final double reach;
        private final List<Query> queries;
        private final long calls;
        private final CapsuleContactQueryBudget.Totals work;
        private VerifiedFootObservations(CapsuleContactCollector.VerifiedContacts fullObservation, Capsule footSphere,
                                         Aabb window, Aabb requiredFootWindow, double reach, List<Query> queries, long calls,
                                         CapsuleContactQueryBudget.Totals work) {
            this.fullObservation=fullObservation; this.footSphere=footSphere; this.window=window;
            this.requiredFootWindow=requiredFootWindow;
            this.reach=reach; this.queries=List.copyOf(queries); this.calls=calls; this.work=work;
        }
        CapsuleContactCollector.VerifiedContacts fullObservation() { return fullObservation; }
        Capsule footSphere() { return footSphere; }
        Aabb window() { return window; }
        Aabb requiredFootWindow() { return requiredFootWindow; }
        double reach() { return reach; }
        List<Query> queries() { return queries; }
        long calls() { return calls; }
        CapsuleContactQueryBudget.Totals work() { return work; }
        boolean matches(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                        WorldCollisionCandidates.Snapshot capture, CapsuleContactQueryBudget budget) {
            return fullObservation.matches(accepted, pose, capture, budget);
        }
    }

    static Capsule footSphere(PlayerPhysicalSnapshot accepted) {
        return footSphere(accepted.placement());
    }

    private static Capsule footSphere(PlayerBodyPlacement placement) {
        if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("foot observation requires actual FreeCapsule");
        Vec3d negative = placement.storedCapsuleGeometry().negativeEndpoint();
        return new Capsule(new Segment3d(negative, negative), placement.capsule().radius());
    }

    /** Pure query-envelope recipe only; this helper mints no observation or owner authority. */
    static Aabb requiredWindow(CapsulePlacementMath.Pose exactVirtualPose, double reach) {
        return window(Objects.requireNonNull(exactVirtualPose).placement(), reach);
    }

    static Aabb window(PlayerPhysicalSnapshot accepted, double reach) {
        return window(accepted.placement(), reach);
    }

    static Aabb window(CertifiedReferenceEndpoint endpoint, double reach) {
        endpoint.requireCurrent();
        return window(endpoint.pose().placement(), reach);
    }

    static Aabb window(InstalledFreeEndpoint endpoint, double reach) {
        endpoint.requireCurrent();
        return window(endpoint.pose().placement(), reach);
    }

    private static Aabb window(CapsuleObservationEndpoint endpoint, double reach) {
        endpoint.requireCurrent();
        return window(endpoint.pose().placement(), reach);
    }

    private static Aabb window(PlayerBodyPlacement placement, double reach) {
        if (!Double.isFinite(reach) || reach < 0 || reach > 0.25)
            throw new IllegalArgumentException("foot reach must be finite within [0,0.25] metres");
        var sphere = footSphere(placement);
        var p = sphere.centerline().start();
        double r = sphere.radius();
        Vec3d delta = placement.bodyUp().multiply(-reach);
        var actual = placement.bounds();
        return new Aabb(new Vec3d(Math.min(actual.min().x(), sweepMin(p.x(),delta.x(),r)),
                Math.min(actual.min().y(), sweepMin(p.y(),delta.y(),r)),
                Math.min(actual.min().z(), sweepMin(p.z(),delta.z(),r))),
                new Vec3d(Math.max(actual.max().x(), sweepMax(p.x(),delta.x(),r)),
                Math.max(actual.max().y(), sweepMax(p.y(),delta.y(),r)),
                Math.max(actual.max().z(), sweepMax(p.z(),delta.z(),r))));
    }

    /**
     * Independently queries one qualified producer box against the stored Free capsule and its
     * physical lower-endpoint sphere. The returned witness comes from that producer box itself,
     * not from a generic world-collision box with matching coordinates.
     */
    static Optional<QualifiedProducerFoot> queryQualifiedProducerFoot(CapsulePlacementMath.Pose actualPose,
            Capsule footSphere, Aabb producerBounds, Vec3d bodyUp, ContactAccuracy accuracy,
            CapsuleContactQueryBudget budget) {
        Objects.requireNonNull(actualPose, "actualPose");
        Objects.requireNonNull(footSphere, "footSphere");
        Objects.requireNonNull(producerBounds, "producerBounds");
        Objects.requireNonNull(bodyUp, "bodyUp");
        Objects.requireNonNull(accuracy, "accuracy");
        Objects.requireNonNull(budget, "budget");

        ContactResult body = budget.query(actualPose.capsule(), producerBounds, accuracy);
        if (body.relation() == Relation.UNRESOLVED)
            throw new IllegalStateException("qualified producer Free-capsule contact is unresolved");
        if (body.relation() == Relation.OVERLAPPING)
            throw new IllegalStateException("qualified producer overlaps the accepted Free capsule");
        if (body.relation() != Relation.TOUCHING) return Optional.empty();

        ContactResult foot = budget.query(footSphere, producerBounds, accuracy);
        if (foot.relation() == Relation.UNRESOLVED)
            throw new IllegalStateException("qualified producer Free-foot contact is unresolved");
        if (foot.relation() == Relation.OVERLAPPING)
            throw new IllegalStateException("qualified producer overlaps the accepted Free foot");
        if (foot.relation() != Relation.TOUCHING || foot.witness().isEmpty()) return Optional.empty();
        Interval direction = direction(foot.witness().orElseThrow(), bodyUp);
        if (direction.lower() <= 0) return Optional.empty();
        return Optional.of(new QualifiedProducerFoot(body, foot, direction));
    }

    private static double sweepMin(double start,double delta,double radius) {
        return Math.min(down(start-radius),down(down(start+delta)-radius));
    }
    private static double sweepMax(double start,double delta,double radius) {
        return Math.max(up(start+radius),up(up(start+delta)+radius));
    }

    static Result collect(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                          WorldCollisionCandidates.Snapshot capture, double reach,
                          ContactAccuracy accuracy, CapsuleContactQueryBudget budget) {
        return collect(accepted, pose, capture, reach, accuracy, budget, null);
    }

    static Result collect(CertifiedReferenceEndpoint endpoint, WorldCollisionCandidates.Snapshot capture,
                          double reach, ContactAccuracy accuracy) {
        endpoint.requireCurrent();
        return collect(endpoint.owner(), endpoint.pose(), capture, reach, accuracy,
                endpoint.transaction().queries(), endpoint);
    }

    static Result collect(InstalledFreeEndpoint endpoint, WorldCollisionCandidates.Snapshot capture,
                          double reach, ContactAccuracy accuracy) {
        endpoint.requireCurrent();
        return collect(endpoint.owner(), endpoint.pose(), capture, reach, accuracy,
                endpoint.transaction().queries(), endpoint);
    }

    static Result collect(CertifiedReferenceEndpoint.CardinalRootObservation root,
                          WorldCollisionCandidates.Snapshot capture, double reach, ContactAccuracy accuracy) {
        Objects.requireNonNull(root).requireCurrent();
        if (!root.matches(root.endpoint(),capture,root.queryWindow())
                || accuracy != FreeOwnerTransaction.ACCURACY
                || !contains(root.queryWindow(),requiredWindow(root.pose(),reach)))
            throw new IllegalArgumentException("contained foot observation requires its exact capture/window/accuracy and complete foot envelope");
        var endpoint=root.endpoint();
        return collect(endpoint.owner(),root.pose(),capture,reach,accuracy,endpoint.transaction().queries(),endpoint,root);
    }

    private static Result collect(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                          WorldCollisionCandidates.Snapshot capture, double reach,
                          ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                          CapsuleObservationEndpoint endpoint) {
        return collect(accepted,pose,capture,reach,accuracy,budget,endpoint,null);
    }

    private static Result collect(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                          WorldCollisionCandidates.Snapshot capture, double reach, ContactAccuracy accuracy,
                          CapsuleContactQueryBudget budget, CapsuleObservationEndpoint endpoint,
                          CertifiedReferenceEndpoint.CardinalRootObservation containedRoot) {
        Objects.requireNonNull(accepted); Objects.requireNonNull(pose); Objects.requireNonNull(capture);
        Objects.requireNonNull(accuracy); Objects.requireNonNull(budget);
        Aabb requiredFootWindow = endpoint == null ? window(accepted, reach) : window(endpoint, reach);
        Aabb window = containedRoot == null ? requiredFootWindow : containedRoot.queryWindow();
        var full = containedRoot != null
                ? CapsuleContactCollector.collectWithin(containedRoot,capture,window,accuracy)
                : endpoint == null
                ? CapsuleContactCollector.collectWithin(accepted, pose, capture, window, accuracy, budget)
                : endpoint instanceof InstalledFreeEndpoint installed
                    ? CapsuleContactCollector.collectWithin(installed, capture, window, accuracy)
                    : CapsuleContactCollector.collectWithin((CertifiedReferenceEndpoint) endpoint, capture, window, accuracy);
        var queries = new ArrayList<Query>();
        if (!(full instanceof CapsuleContactCollector.VerifiedContacts verified))
            return failed(Failure.FULL_CAPSULE, full, queries, capture, budget, Optional.empty(), Optional.empty());
        Capsule sphere = footSphere(pose.placement());
        Vec3d negative = sphere.centerline().start();
        Vec3d delta = pose.placement().bodyUp().multiply(-reach);
        Vec3d zero = new Vec3d(0,0,0);
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
            Aabb box = capture.boxes().get(ordinal);
            ContactResult contact = null;
            List<BoundaryResult> boundaries = new ArrayList<>();
            Optional<Interval> direction = Optional.empty();
            try {
                contact = budget.query(sphere, box, accuracy);
                direction = contact.witness().map(w -> direction(w, pose.placement().bodyUp()));
                if (reach > 0 && (contact.relation() == Relation.SEPARATED || contact.relation() == Relation.TOUCHING)) {
                    var first = budget.refineTranslationBoundary(sphere, delta, box, new BoundarySearchHint(0,1),
                            new StoredInstallCandidate(0,negative,negative,zero,sphere), accuracy);
                    boundaries.add(first);
                    if (first.kind() == BoundaryKind.UNRESOLVED && first.reason().orElseThrow() == Reason.NO_PREFIX_CERTIFICATE) {
                        Vec3d end = negative.add(delta);
                        Capsule endSphere = new Capsule(new Segment3d(end,end), sphere.radius());
                        boundaries.add(budget.refineTranslationBoundary(sphere, delta, box, new BoundarySearchHint(0,1),
                                new StoredInstallCandidate(1,negative,end,zero,endSphere), accuracy));
                    }
                }
            } catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
                if (contact != null) queries.add(new Query(ordinal, box, contact, direction, boundaries, Classification.UNCERTAIN));
                return new Failed(Failure.BUDGET_EXHAUSTED, full, queries, ordinal, capture.boxes().size(),
                        budget.calls(), budget.work(), budget.terminal(), Optional.empty(), Optional.of(exhausted));
            }
            Classification classification = reach == 0 && contact.relation() == Relation.TOUCHING
                    && direction.isPresent() && direction.get().lower() > 0 ? Classification.CONTACT
                    : classify(contact, direction, boundaries);
            queries.add(new Query(ordinal, box, contact, direction, boundaries, classification));
            if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
            var context = capture.capsuleContextFailure(accepted, window);
            if (context.isPresent()) return failed(Failure.CONTEXT_CHANGED, full, queries, capture, budget, context, Optional.empty());
            if (contact.relation() == Relation.OVERLAPPING)
                return failed(Failure.OVERLAPPING, full, queries, capture, budget, Optional.empty(), Optional.empty());
            if (contact.relation() == Relation.UNRESOLVED || boundaries.stream().anyMatch(b ->
                    b.kind() == BoundaryKind.UNRESOLVED && b.reason().orElseThrow() != Reason.NO_PREFIX_CERTIFICATE))
                return failed(Failure.UNRESOLVED, full, queries, capture, budget, Optional.empty(), Optional.empty());
        }
        var context = capture.capsuleContextFailure(accepted, window);
        if (context.isPresent()) return failed(Failure.CONTEXT_CHANGED, full, queries, capture, budget, context, Optional.empty());
        if (containedRoot != null) containedRoot.requireCurrent();
            else if (endpoint != null) endpoint.requireCurrent();
        return new VerifiedFootObservations(verified, sphere, window, requiredFootWindow, reach, queries, budget.calls(), budget.work());
    }

    private static boolean contains(Aabb outer,Aabb inner) {
        return outer.min().x()<=inner.min().x() && outer.min().y()<=inner.min().y() && outer.min().z()<=inner.min().z()
                && outer.max().x()>=inner.max().x() && outer.max().y()>=inner.max().y() && outer.max().z()>=inner.max().z();
    }

    private static Classification classify(ContactResult contact, Optional<Interval> direction, List<BoundaryResult> boundaries) {
        if (boundaries.isEmpty()) return Classification.UNCERTAIN;
        var last = boundaries.getLast();
        if (contact.relation() == Relation.TOUCHING && last.kind() == BoundaryKind.BLOCKED_AT_START) return Classification.CONTACT;
        if (contact.relation() == Relation.SEPARATED && (last.kind() == BoundaryKind.CERTIFIED_BOUNDARY
                || last.kind() == BoundaryKind.NONPENETRATING_FULL)) return Classification.NEAR;
        if (last.kind() == BoundaryKind.SEPARATED_FULL || direction.isPresent() && direction.get().upper() <= 0)
            return Classification.OUTSIDE;
        return Classification.UNCERTAIN;
    }
    private static Interval direction(Witness w, Vec3d up) {
        return add(add(mul(sub(w.segmentX(),w.boxX()),up.x()),mul(sub(w.segmentY(),w.boxY()),up.y())),
                mul(sub(w.segmentZ(),w.boxZ()),up.z()));
    }
    private static Interval sub(Interval a, Interval b) { return new Interval(down(a.lower()-b.upper()),up(a.upper()-b.lower())); }
    private static Interval mul(Interval a, double b) {
        if (b == 0) return new Interval(0,0);
        return b > 0 ? new Interval(down(a.lower()*b),up(a.upper()*b)) : new Interval(down(a.upper()*b),up(a.lower()*b));
    }
    private static Interval add(Interval a, Interval b) {
        if (a.lower()==0 && a.upper()==0) return b;
        if (b.lower()==0 && b.upper()==0) return a;
        return new Interval(down(a.lower()+b.lower()),up(a.upper()+b.upper()));
    }
    private static double down(double v) { double r=Math.nextDown(v); if(!Double.isFinite(r)) throw new IllegalArgumentException("non-finite foot enclosure"); return r; }
    private static double up(double v) { double r=Math.nextUp(v); if(!Double.isFinite(r)) throw new IllegalArgumentException("non-finite foot enclosure"); return r; }
    private static Failed failed(Failure failure, CapsuleContactCollector.Result full, List<Query> queries,
                                 WorldCollisionCandidates.Snapshot capture, CapsuleContactQueryBudget budget,
                                 Optional<WorldCollisionCandidates.Unavailable> context,
                                 Optional<CapsuleContactQueryBudget.BudgetExhausted> exhausted) {
        return new Failed(failure, full, queries, queries.size(), capture.boxes().size(), budget.calls(),
                budget.work(), budget.terminal(), context, exhausted);
    }
}
