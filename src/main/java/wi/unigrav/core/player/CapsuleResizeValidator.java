package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import wi.unigrav.core.player.CapsulePlacementMath.Pose;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;

/**
 * Continuous resize containment plus CHB observations; never an owner or installation permit.
 * The captured candidates are immutable observations, not a world mutation revision. The owning
 * transaction must recapture/compare after intervening native callbacks and before installing.
 */
final class CapsuleResizeValidator {
    private CapsuleResizeValidator() { }

    // Bounds are independent of obstacle count. Query and numeric ledgers remain transaction-wide.
    static final int MAX_REGIONS = 127;
    static final int MAX_DEPTH = 16;

    enum Failure {
        INVALID_INPUT, CONTEXT_CHANGED, WINDOW_TOO_SMALL, ACTUAL_OVERLAP, RESIZE_OVERLAP,
        UNRESOLVED, SUBDIVISION_LIMIT, PHASE_LIMIT, QUERY_BUDGET, NUMERIC_BUDGET, PROFILE_NOT_CERTIFIED
    }
    enum Stage { START, TARGET, ENCLOSURE, MIDPOINT }
    record Query(Stage stage, double from, double to, int ordinal, Aabb box, ContactResult result) { }
    sealed interface Result permits VerifiedResize, Failed {
        List<Query> queries();
        long numericWork();
    }
    record Failed(Failure failure, List<Query> queries, int regions, long numericWork,
                  Throwable budgetCause) implements Result {
        Failed { queries = List.copyOf(queries); }
    }

    static final class VerifiedResize implements Result {
        private final PathSource legacySource;
        private final CapsulePathSource source;
        private final PlayerPhysicalSnapshot accepted;
        private final Pose start;
        private final PlayerBodyPlacement target;
        private final Snapshot capture;
        private final Aabb window;
        private final CapsuleContactQueryBudget budget;
        private final StoredDyadic.Work numeric;
        private final List<Query> queries;
        private final long numericWork;
        private final int regions;

        private VerifiedResize(PathSource legacySource, CapsulePathSource source, PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target,
                Snapshot capture, Aabb window, CapsuleContactQueryBudget budget, StoredDyadic.Work numeric,
                List<Query> queries, long numericWork, int regions) {
            this.legacySource = legacySource;
            this.source = source;
            this.accepted = accepted;
            this.start = start;
            this.target = target;
            this.capture = capture;
            this.window = window;
            this.budget = budget;
            this.numeric = numeric;
            this.queries = List.copyOf(queries);
            this.numericWork = numericWork;
            this.regions = regions;
        }

        boolean matches(PathSource exactSource, PlayerBodyPlacement exactTarget,
                Snapshot exactCapture, CapsuleContactQueryBudget exactBudget, StoredDyadic.Work exactNumeric) {
            return legacySource == exactSource && legacySource != null && legacySource.valid(numeric) && target == exactTarget
                    && capture == exactCapture && budget == exactBudget && numeric == exactNumeric
                    && capture.capsuleContextFailure(accepted, window).isEmpty();
        }
        CapsulePathSource source() { return source; }
        boolean matchesBound(CapsulePathSource exactSource) {
            return source == exactSource && start == source.start() && accepted == source.actualSource()
                    && capture == source.capture() && window == source.window() && budget == source.queries()
                    && numeric == source.work() && source.valid() && source.current();
        }
        PlayerBodyPlacement target() { return target; }
        Aabb window() { return window; }
        int regions() { return regions; }
        public List<Query> queries() { return queries; }
        public long numericWork() { return numericWork; }
    }

    static final class PathSource {
        private enum Kind { ACTUAL_FREE, CARDINAL_VIRTUAL }
        private final Kind kind;
        private final PlayerPhysicalSnapshot actualSource;
        private final Pose start;
        private final StoredDyadic.Work work;
        private final CardinalCapsuleContainment.Contained containment;

        private PathSource(Kind kind, PlayerPhysicalSnapshot source, Pose start,
                StoredDyadic.Work work, CardinalCapsuleContainment.Contained containment) {
            this.kind = kind;
            this.actualSource = source;
            this.start = start;
            this.work = work;
            this.containment = containment;
        }
        static PathSource actualFree(PlayerPhysicalSnapshot source, Pose exactPose, StoredDyadic.Work sameWork) {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(exactPose, "exactPose");
            Objects.requireNonNull(sameWork, "sameWork").charge(1);
            var result = new PathSource(Kind.ACTUAL_FREE, source, exactPose, sameWork, null);
            if (!result.valid(sameWork)) throw new IllegalArgumentException("actual Free source pose identity mismatch");
            return result;
        }
        static PathSource cardinalVirtual(CardinalCapsuleContainment.Contained certificate,
                Pose exactVirtualPose, StoredDyadic.Work sameWork) {
            Objects.requireNonNull(certificate, "certificate");
            Objects.requireNonNull(exactVirtualPose, "exactVirtualPose");
            Objects.requireNonNull(sameWork, "sameWork").charge(1);
            var result = new PathSource(Kind.CARDINAL_VIRTUAL, certificate.source(), exactVirtualPose,
                    sameWork, certificate);
            if (!result.valid(sameWork)) throw new IllegalArgumentException("Cardinal virtual source certificate identity mismatch");
            return result;
        }
        private boolean valid(StoredDyadic.Work sameWork) {
            if (work != sameWork || start.capsule() != start.placement().capsule()) return false;
            if (kind == Kind.ACTUAL_FREE) return actualSource.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                    && start.placement() == actualSource.placement();
            return actualSource.placement().shape() instanceof PlayerBodyShape.Cardinal
                    && containment.matches(actualSource, start.placement(), sameWork);
        }
        PlayerPhysicalSnapshot actualSource() { return actualSource; }
        Pose start() { return start; }
    }

    private record Region(double from, double to, int depth) { }

    static Result validate(PathSource source, PlayerBodyPlacement target,
            Snapshot capture, Aabb expectedWindow, ContactAccuracy accuracy,
            CapsuleContactQueryBudget sameBudget, StoredDyadic.Work sameNumeric) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sameNumeric, "sameNumeric");
        long before = sameNumeric.units();
        try {
            if (!source.valid(sameNumeric)) {
                sameNumeric.charge(1);
                return failed(Failure.INVALID_INPUT, List.of(), 0, sameNumeric, before, null);
            }
            var bound = source.containment == null
                    ? CapsulePathSource.actualFree(source.actualSource, source.start, capture, expectedWindow,
                            accuracy, sameBudget, sameNumeric)
                    : CapsulePathSource.containedCardinal(source.containment, source.start, capture, expectedWindow,
                            accuracy, sameBudget, sameNumeric);
            return validate(bound, source, target, before);
        } catch (CapsulePathSource.InvalidSourceException invalid) {
            return failed(Failure.INVALID_INPUT, List.of(), 0, sameNumeric, before, null);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, List.of(), 0, sameNumeric, before, exhausted);
        }
    }

    /** A padded overlap is uncertainty; an exact closed-region halfspace can discharge only this box. */
    private static boolean certifiedOutsideBox(CapsuleResizeEnclosure.Path path, Aabb box,
            Region region, StoredDyadic.Work sameWork) {
        for (int axis = 0; axis < 3; axis++) {
            for (int sign : new int[] { 1, -1 }) {
                var corner = sign > 0 ? box.max() : box.min();
                double plane = switch (axis) { case 0 -> corner.x(); case 1 -> corner.y(); default -> corner.z(); };
                var result = CapsuleResizePlaneProof.certify(path, axis, sign, plane,
                        region.from(), region.to(), sameWork);
                if (result instanceof CapsuleResizePlaneProof.Certificate certificate
                        && certificate.matches(path, axis, sign, plane, region.from(), region.to(), sameWork))
                    return true;
            }
        }
        return false;
    }

    static Result validate(CapsulePathSource source, PlayerBodyPlacement target) {
        Objects.requireNonNull(source, "source");
        return validate(source, null, target, source.work().units());
    }
    private static Result validate(CapsulePathSource source, PathSource legacySource,
            PlayerBodyPlacement target, long before) {
        Objects.requireNonNull(target, "target");
        var accepted = source.actualSource();
        var actualStart = source.start();
        var capture = source.capture();
        var expectedWindow = source.window();
        var accuracy = source.accuracy();
        var sameBudget = source.queries();
        var sameNumeric = source.work();
        List<Query> queries = new ArrayList<>();
        int regions = 0;
        try {
            source.chargeMetadata();
            if (!source.valid()
                    || !(target.shape() instanceof PlayerBodyShape.FreeCapsule)
                    || !StoredRealizationLimits.sameRaw(actualStart.placement().attitude(), target.attitude())
                    || !contains(expectedWindow, accepted.placement().bounds())
                    || !contains(expectedWindow, actualStart.placement().bounds())
                    || !contains(expectedWindow, target.bounds())) {
                return failed(Failure.INVALID_INPUT, queries, regions, sameNumeric, before, null);
            }
            if (changed(accepted, capture, expectedWindow)) {
                return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
            }
            if (source.completedSegments() >= CapsulePathSource.MAX_SEGMENTS) {
                return failed(Failure.PHASE_LIMIT, queries, regions, sameNumeric, before, null);
            }
            var path = CapsuleResizeEnclosure.prepare(actualStart, target, sameNumeric);
            Failure endpoint = observe(actualStart.capsule(), Stage.START, 0, 0,
                    accepted, capture, expectedWindow, accuracy, sameBudget, queries);
            if (endpoint != null) return failed(endpoint, queries, regions, sameNumeric, before, null);
            endpoint = observe(target.capsule(), Stage.TARGET, 1, 1,
                    accepted, capture, expectedWindow, accuracy, sameBudget, queries);
            if (endpoint != null) return failed(endpoint, queries, regions, sameNumeric, before, null);

            var pending = new ArrayDeque<Region>();
            pending.push(new Region(0, 1, 0));
            while (!pending.isEmpty()) {
                if (++regions > MAX_REGIONS) {
                    return failed(Failure.SUBDIVISION_LIMIT, queries, regions, sameNumeric, before, null);
                }
                sameNumeric.charge(1);
                Region region = pending.pop();
                if (changed(accepted, capture, expectedWindow)) {
                    return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
                }
                var enclosure = path.encloseClosedInterval(region.from(), region.to(), sameNumeric);
                boolean outsideWindow = !contains(expectedWindow, enclosure.bounds());
                boolean refine = outsideWindow;
                boolean actualMidpointOverlap = false;
                // No observation against an incomplete window may authorize any part of this region.
                // Subdivision can tighten an overly coarse construction envelope without new capture.
                for (int ordinal = 0; !outsideWindow && ordinal < capture.boxes().size(); ordinal++) {
                    Aabb box = capture.boxes().get(ordinal);
                    ContactResult contact = sameBudget.query(enclosure.padded(), box, accuracy);
                    queries.add(new Query(Stage.ENCLOSURE, region.from(), region.to(), ordinal, box, contact));
                    if (changed(accepted, capture, expectedWindow)) {
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
                    }
                    if (contact.relation() == Relation.UNRESOLVED) {
                        return failed(Failure.UNRESOLVED, queries, regions, sameNumeric, before, null);
                    }
                    // Frozen CHB exact equality is TOUCHING. Inflated-shape overlap is only uncertainty.
                    if (contact.relation() == Relation.OVERLAPPING) {
                        boolean separated = certifiedOutsideBox(path, box, region, sameNumeric);
                        if (changed(accepted, capture, expectedWindow))
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
                        if (separated) continue;
                        refine = true;
                        // A true midpoint collision is a rejection witness, never a sampling acceptance.
                        ContactResult midpoint = sameBudget.query(enclosure.reference(), box, accuracy);
                        queries.add(new Query(Stage.MIDPOINT, region.from(), region.to(), ordinal, box, midpoint));
                        if (changed(accepted, capture, expectedWindow)) {
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
                        }
                        if (midpoint.relation() == Relation.UNRESOLVED) {
                            return failed(Failure.UNRESOLVED, queries, regions, sameNumeric, before, null);
                        }
                        if (midpoint.relation() == Relation.OVERLAPPING) {
                            actualMidpointOverlap = true;
                        }
                    }
                }
                if (actualMidpointOverlap) {
                    return failed(Failure.RESIZE_OVERLAP, queries, regions, sameNumeric, before, null);
                }
                if (refine) {
                    if (region.depth() >= MAX_DEPTH) {
                        return failed(outsideWindow ? Failure.WINDOW_TOO_SMALL : Failure.SUBDIVISION_LIMIT,
                                queries, regions, sameNumeric, before, null);
                    }
                    sameNumeric.charge(3);
                    double middle = region.from() + (region.to() - region.from()) * 0.5;
                    if (!(middle > region.from() && middle < region.to())) {
                        return failed(Failure.SUBDIVISION_LIMIT, queries, regions, sameNumeric, before, null);
                    }
                    pending.push(new Region(middle, region.to(), region.depth() + 1));
                    pending.push(new Region(region.from(), middle, region.depth() + 1));
                }
            }
            if (changed(accepted, capture, expectedWindow)) {
                return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null);
            }
            return new VerifiedResize(legacySource, source, accepted, actualStart, target, capture, expectedWindow, sameBudget,
                    sameNumeric, queries, sameNumeric.units() - before, regions);
        } catch (CapsuleResizeEnclosure.ProfileNotCertifiedException failure) {
            return failed(Failure.PROFILE_NOT_CERTIFIED, queries, regions, sameNumeric, before, failure);
        } catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
            return failed(Failure.QUERY_BUDGET, queries, regions, sameNumeric, before, exhausted);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, queries, regions, sameNumeric, before, exhausted);
        }
        // Other exceptions preserve their cause/stack and reach the owning transaction's failure boundary.
    }

    private static Failure observe(Capsule actual, Stage stage, double from, double to,
            PlayerPhysicalSnapshot accepted, Snapshot capture, Aabb window, ContactAccuracy accuracy,
            CapsuleContactQueryBudget budget, List<Query> queries) {
        boolean overlapping = false;
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            Aabb box = capture.boxes().get(ordinal);
            ContactResult contact = budget.query(actual, box, accuracy);
            queries.add(new Query(stage, from, to, ordinal, box, contact));
            if (changed(accepted, capture, window)) return Failure.CONTEXT_CHANGED;
            if (contact.relation() == Relation.UNRESOLVED) return Failure.UNRESOLVED;
            overlapping |= contact.relation() == Relation.OVERLAPPING;
        }
        return overlapping ? Failure.ACTUAL_OVERLAP : null;
    }

    private static boolean changed(PlayerPhysicalSnapshot accepted, Snapshot capture, Aabb window) {
        return capture.capsuleContextFailure(accepted, window).isPresent();
    }
    private static boolean contains(Aabb outer, Aabb inner) {
        return outer.min().x() <= inner.min().x() && outer.min().y() <= inner.min().y()
                && outer.min().z() <= inner.min().z() && outer.max().x() >= inner.max().x()
                && outer.max().y() >= inner.max().y() && outer.max().z() >= inner.max().z();
    }
    private static Failed failed(Failure failure, List<Query> queries, int regions,
            StoredDyadic.Work numeric, long before, Throwable cause) {
        return new Failed(failure, queries, regions, numeric.units() - before, cause);
    }
}
