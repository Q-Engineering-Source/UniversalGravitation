package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Vec3d;
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
 * Continuous construction containment plus CHB observations; never an owner or installation permit.
 * The captured candidates are immutable observations, not a world mutation revision. The owning
 * transaction must recapture/compare after intervening native callbacks and before installing.
 */
final class CapsuleArcValidator {
    private CapsuleArcValidator() { }

    // Bounds are independent of obstacle count. Query and numeric ledgers remain transaction-wide.
    static final int MAX_REGIONS = 127;
    static final int MAX_DEPTH = 16;

    enum Failure {
        INVALID_INPUT, CONTEXT_CHANGED, WINDOW_TOO_SMALL, ACTUAL_OVERLAP, ARC_OVERLAP,
        UNRESOLVED, SUBDIVISION_LIMIT, PHASE_LIMIT, QUERY_BUDGET, NUMERIC_BUDGET
    }
    enum Stage { START, TARGET, ENCLOSURE, MIDPOINT }
    record Query(Stage stage, double from, double to, int ordinal, Aabb box, ContactResult result) { }
    record Separation(Stage stage, double from, double to, int ordinal, Aabb outwardBounds, Aabb box) { }
    sealed interface Result permits VerifiedArc, Failed {
        List<Query> queries();
        List<Separation> separations();
        long numericWork();
    }
    record Failed(Failure failure, List<Query> queries, int regions, long numericWork,
                  Throwable budgetCause, List<Separation> separations) implements Result {
        Failed { queries = List.copyOf(queries); separations = List.copyOf(separations); }
        Failed(Failure failure, List<Query> queries, int regions, long numericWork, Throwable budgetCause) {
            this(failure, queries, regions, numericWork, budgetCause, List.of());
        }
    }

    static final class VerifiedArc implements Result {
        private final CapsulePathSource source;
        private final PlayerPhysicalSnapshot accepted;
        private final Pose start;
        private final PlayerBodyPlacement target;
        private final Snapshot capture;
        private final Aabb window;
        private final CapsuleContactQueryBudget budget;
        private final StoredDyadic.Work numeric;
        private final List<Query> queries;
        private final List<Separation> separations;
        private final List<GenericArcPlaneReceipt> signedHalfspaces;
        private final long numericWork;
        private final int regions;

        private VerifiedArc(CapsulePathSource source, PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target,
                Snapshot capture, Aabb window, CapsuleContactQueryBudget budget, StoredDyadic.Work numeric,
                List<Query> queries, long numericWork, int regions, List<Separation> separations,
                List<GenericArcPlaneReceipt> signedHalfspaces) {
            this.source = source;
            this.accepted = accepted;
            this.start = start;
            this.target = target;
            this.capture = capture;
            this.window = window;
            this.budget = budget;
            this.numeric = numeric;
            this.queries = List.copyOf(queries);
            this.separations = List.copyOf(separations);
            this.signedHalfspaces = List.copyOf(signedHalfspaces);
            this.numericWork = numericWork;
            this.regions = regions;
        }

        boolean matches(PlayerPhysicalSnapshot exactAccepted, Pose exactStart, PlayerBodyPlacement exactTarget,
                Snapshot exactCapture, CapsuleContactQueryBudget exactBudget, StoredDyadic.Work exactNumeric) {
            return accepted == exactAccepted && start == exactStart && target == exactTarget
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
        public List<Separation> separations() { return separations; }
        int signedHalfspaceCount() { return signedHalfspaces.size(); }
        public long numericWork() { return numericWork; }
    }

    private record Region(double from, double to, int depth) { }

    private record BoxBits(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        static BoxBits of(Aabb box) {
            return new BoxBits(raw(box.min().x()), raw(box.min().y()), raw(box.min().z()),
                    raw(box.max().x()), raw(box.max().y()), raw(box.max().z()));
        }
        private static long raw(double value) { return Double.doubleToRawLongBits(value); }
    }

    /** Cardinal-only receipt; the Free Y-plane eligibility and proof remain unchanged. */
    static final class CardinalPlaneReceipt {
        private final CardinalRealizedArcRecipe.Prepared prepared;
        private final CapsulePathSource source;
        private final Snapshot capture;
        private final Aabb window;
        private final CapsuleContactQueryBudget queries;
        private final StoredDyadic.Work work;
        private final int ordinal, axis, side;
        private final Aabb box;
        private final BoxBits bits;
        private final long planeBits, fromBits, toBits;
        private CardinalPlaneReceipt(CardinalRealizedArcRecipe.Prepared prepared, CapsulePathSource source,
                int ordinal, Aabb box, int axis, int side, double plane, double from, double to) {
            this.prepared = prepared; this.source = source; this.capture = source.capture();
            this.window = source.window(); this.queries = source.queries(); this.work = source.work();
            this.ordinal = ordinal; this.box = box; this.bits = BoxBits.of(box); this.axis = axis; this.side = side;
            planeBits = Double.doubleToRawLongBits(plane);
            fromBits = Double.doubleToRawLongBits(from); toBits = Double.doubleToRawLongBits(to);
        }
        static CardinalPlaneReceipt tryIssue(CardinalRealizedArcRecipe.Prepared prepared, CapsulePathSource source,
                int ordinal, Aabb box, double from, double to) {
            source.work().charge(1);
            if (!eligible(prepared, source, ordinal, box)) return null;
            for (int axis = 0; axis < 3; axis++) for (int side = 1; side >= -1; side -= 2) {
                double plane = StoredRealizationLimits.component(side > 0 ? box.max() : box.min(), axis);
                if (prepared.path().provesAxisHalfSpace(axis, side, plane, from, to, source.work())
                        && eligible(prepared, source, ordinal, box))
                    return new CardinalPlaneReceipt(prepared, source, ordinal, box, axis, side, plane, from, to);
            }
            return null;
        }
        private static boolean eligible(CardinalRealizedArcRecipe.Prepared prepared, CapsulePathSource source,
                int ordinal, Aabb box) {
            source.chargeMetadata();
            return prepared.recipe().matchesPathSource(source)
                    && source.actualSource() == prepared.recipe().sourceContainment().source()
                    && source.start() == prepared.recipe().start()
                    && prepared.recipe().matches(source.start(), prepared.recipe().target(), source.work())
                    && prepared.path().preparedFor(source.start(), prepared.recipe().target(), source.work())
                    && ordinal >= 0 && ordinal < source.capture().boxes().size()
                    && source.capture().boxes().get(ordinal) == box && source.current();
        }
        boolean matches(CardinalRealizedArcRecipe.Prepared exactPrepared, CapsulePathSource exactSource,
                int exactOrdinal, Aabb exactBox, double from, double to) {
            work.charge(1);
            return prepared == exactPrepared && source == exactSource && capture == exactSource.capture()
                    && window == exactSource.window() && queries == exactSource.queries() && work == exactSource.work()
                    && ordinal == exactOrdinal && box == exactBox && bits.equals(BoxBits.of(exactBox))
                    && planeBits == Double.doubleToRawLongBits(StoredRealizationLimits.component(
                            side > 0 ? exactBox.max() : exactBox.min(), axis))
                    && fromBits == Double.doubleToRawLongBits(from) && toBits == Double.doubleToRawLongBits(to)
                    && eligible(exactPrepared, exactSource, exactOrdinal, exactBox);
        }
    }

    /** Exact per-box/region signed nonpenetration; it does not mean strict separation or contact. */
    static final class GenericArcPlaneReceipt {
        private final CapsuleArcEnclosure.Path path;
        private final CapsulePathSource source;
        private final PlayerPhysicalSnapshot accepted;
        private final Pose start;
        private final PlayerBodyPlacement target;
        private final Snapshot capture;
        private final Aabb window;
        private final ContactAccuracy accuracy;
        private final CapsuleContactQueryBudget budget;
        private final StoredDyadic.Work numeric;
        private final int ordinal, axis, side;
        private final Aabb box;
        private final BoxBits boxBits;
        private final long planeBits, fromBits, toBits;

        private GenericArcPlaneReceipt(CapsuleArcEnclosure.Path path, CapsulePathSource source,
                PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target, Snapshot capture,
                Aabb window, ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                StoredDyadic.Work numeric, int ordinal, Aabb box, int axis, int side,
                double plane, double from, double to) {
            this.path = path; this.source = source; this.accepted = accepted; this.start = start;
            this.target = target; this.capture = capture; this.window = window; this.accuracy = accuracy;
            this.budget = budget; this.numeric = numeric; this.ordinal = ordinal; this.box = box;
            this.boxBits = BoxBits.of(box); this.axis = axis; this.side = side;
            planeBits = Double.doubleToRawLongBits(plane);
            fromBits = Double.doubleToRawLongBits(from); toBits = Double.doubleToRawLongBits(to);
        }

        static GenericArcPlaneReceipt tryIssue(CapsuleArcEnclosure.Path path, CapsulePathSource source,
                PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target, Snapshot capture,
                Aabb window, ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                StoredDyadic.Work numeric, int ordinal, Aabb box, double from, double to) {
            numeric.charge(1);
            if (!eligible(path, source, accepted, start, target, capture, window, accuracy, budget,
                    numeric, ordinal, box)) return null;
            for (int axis = 0; axis < 3; axis++) {
                for (int side : new int[] {1, -1}) {
                    numeric.charge(1);
                    double plane = StoredRealizationLimits.component(side > 0 ? box.max() : box.min(), axis);
                    if (!path.provesGenericAxisHalfSpace(axis, side, plane, from, to, numeric)) continue;
                    if (!eligible(path, source, accepted, start, target, capture, window, accuracy, budget,
                            numeric, ordinal, box)) return null;
                    return new GenericArcPlaneReceipt(path, source, accepted, start, target, capture,
                            window, accuracy, budget, numeric, ordinal, box, axis, side, plane, from, to);
                }
            }
            return null;
        }

        private static boolean eligible(CapsuleArcEnclosure.Path path, CapsulePathSource source,
                PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target, Snapshot capture,
                Aabb window, ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                StoredDyadic.Work numeric, int ordinal, Aabb box) {
            return source != null && source.actualSource() == accepted && source.hasGenericArcRoot()
                    && source.valid() && source.start() == start && source.capture() == capture
                    && source.window() == window && source.accuracy() == accuracy
                    && source.queries() == budget && source.work() == numeric
                    && ordinal >= 0 && ordinal < capture.boxes().size() && capture.boxes().get(ordinal) == box
                    && path.preparedFor(start, target, numeric) && source.current();
        }

        boolean matches(CapsuleArcEnclosure.Path exactPath, CapsulePathSource exactSource,
                PlayerPhysicalSnapshot exactAccepted, Pose exactStart, PlayerBodyPlacement exactTarget,
                Snapshot exactCapture, Aabb exactWindow, ContactAccuracy exactAccuracy,
                CapsuleContactQueryBudget exactBudget, StoredDyadic.Work exactNumeric,
                int exactOrdinal, Aabb exactBox, double from, double to) {
            exactNumeric.charge(1);
            if (path != exactPath || source != exactSource || accepted != exactAccepted || start != exactStart
                    || target != exactTarget || capture != exactCapture || window != exactWindow
                    || accuracy != exactAccuracy || budget != exactBudget || numeric != exactNumeric
                    || ordinal != exactOrdinal || box != exactBox || ordinal < 0
                    || ordinal >= capture.boxes().size() || capture.boxes().get(ordinal) != box
                    || !boxBits.equals(BoxBits.of(exactBox))
                    || planeBits != Double.doubleToRawLongBits(StoredRealizationLimits.component(
                            side > 0 ? exactBox.max() : exactBox.min(), axis))
                    || fromBits != Double.doubleToRawLongBits(from) || toBits != Double.doubleToRawLongBits(to)
                    || !exactSource.hasGenericArcRoot() || !path.preparedFor(exactStart, exactTarget, exactNumeric))
                return false;
            return exactSource.valid() && exactSource.current();
        }

        int ordinal() { return ordinal; }
        int axis() { return axis; }
        int side() { return side; }
        Aabb box() { return box; }
        double plane() { return Double.longBitsToDouble(planeBits); }
        double from() { return Double.longBitsToDouble(fromBits); }
        double to() { return Double.longBitsToDouble(toBits); }
    }

    /** Ephemeral proof: one signed path, one source context, one captured box ordinal and range. */
    private static final class YPlaneReceipt {
        private final CapsuleArcEnclosure.Path path;
        private final CapsulePathSource source;
        private final PlayerPhysicalSnapshot accepted;
        private final Pose start;
        private final PlayerBodyPlacement target;
        private final Snapshot capture;
        private final Aabb window;
        private final ContactAccuracy accuracy;
        private final CapsuleContactQueryBudget budget;
        private final StoredDyadic.Work numeric;
        private final int ordinal;
        private final Aabb box;
        private final BoxBits boxBits;
        private final long planeBits, fromBits, toBits;

        private YPlaneReceipt(CapsuleArcEnclosure.Path path, CapsulePathSource source,
                PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target, Snapshot capture,
                Aabb window, ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                StoredDyadic.Work numeric, int ordinal, Aabb box, double from, double to) {
            this.path = path;
            this.source = source;
            this.accepted = accepted;
            this.start = start;
            this.target = target;
            this.capture = capture;
            this.window = window;
            this.accuracy = accuracy;
            this.budget = budget;
            this.numeric = numeric;
            this.ordinal = ordinal;
            this.box = box;
            boxBits = BoxBits.of(box);
            planeBits = Double.doubleToRawLongBits(box.max().y());
            fromBits = Double.doubleToRawLongBits(from);
            toBits = Double.doubleToRawLongBits(to);
        }

        static YPlaneReceipt tryIssue(CapsuleArcEnclosure.Path path, CapsulePathSource source,
                PlayerPhysicalSnapshot accepted, Pose start, PlayerBodyPlacement target, Snapshot capture,
                Aabb window, ContactAccuracy accuracy, CapsuleContactQueryBudget budget,
                StoredDyadic.Work numeric, int ordinal, Aabb box, double from, double to) {
            numeric.charge(1);
            if (source == null || source.actualSource() != accepted
                    || !(accepted.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                    || source.start() != start
                    || source.capture() != capture || source.window() != window || source.accuracy() != accuracy
                    || source.queries() != budget || source.work() != numeric || ordinal < 0
                    || ordinal >= capture.boxes().size() || capture.boxes().get(ordinal) != box
                    || !path.preparedFor(start, target, numeric)) return null;
            source.chargeMetadata();
            if (!source.valid() || !source.current()) return null;
            double plane = box.max().y();
            if (!path.provesYLowerHalfSpace(plane, from, to, numeric)) return null;
            return new YPlaneReceipt(path, source, accepted, start, target, capture, window,
                    accuracy, budget, numeric, ordinal, box, from, to);
        }

        boolean matches(CapsuleArcEnclosure.Path exactPath, CapsulePathSource exactSource,
                PlayerPhysicalSnapshot exactAccepted, Pose exactStart, PlayerBodyPlacement exactTarget,
                Snapshot exactCapture, Aabb exactWindow, ContactAccuracy exactAccuracy,
                CapsuleContactQueryBudget exactBudget, StoredDyadic.Work exactNumeric,
                int exactOrdinal, Aabb exactBox, double from, double to) {
            exactNumeric.charge(1);
            if (path != exactPath || source != exactSource || accepted != exactAccepted || start != exactStart
                    || target != exactTarget || capture != exactCapture || window != exactWindow
                    || accuracy != exactAccuracy || budget != exactBudget || numeric != exactNumeric
                    || ordinal != exactOrdinal || box != exactBox || ordinal < 0
                    || ordinal >= capture.boxes().size() || capture.boxes().get(ordinal) != box
                    || !boxBits.equals(BoxBits.of(exactBox))
                    || planeBits != Double.doubleToRawLongBits(exactBox.max().y())
                    || fromBits != Double.doubleToRawLongBits(from) || toBits != Double.doubleToRawLongBits(to)
                    || !path.preparedFor(exactStart, exactTarget, exactNumeric)) return false;
            source.chargeMetadata();
            return source.valid() && source.current();
        }
    }

    static Result validate(PlayerPhysicalSnapshot accepted, Pose actualStart, PlayerBodyPlacement target,
            Snapshot capture, Aabb expectedWindow, Vec3d preferredAxis, ContactAccuracy accuracy,
            CapsuleContactQueryBudget sameBudget, StoredDyadic.Work sameNumeric) {
        Objects.requireNonNull(sameNumeric, "sameNumeric");
        long before = sameNumeric.units();
        try {
            var source = CapsulePathSource.actualFree(accepted, actualStart, capture, expectedWindow,
                    accuracy, sameBudget, sameNumeric);
            return validate(source, target, preferredAxis, before);
        } catch (CapsulePathSource.InvalidSourceException invalid) {
            return failed(Failure.INVALID_INPUT, List.of(), 0, sameNumeric, before, null);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, List.of(), 0, sameNumeric, before, exhausted);
        }
    }

    static Result validate(CapsulePathSource source, PlayerBodyPlacement target, Vec3d preferredAxis) {
        Objects.requireNonNull(source, "source");
        return validate(source, target, preferredAxis, source.work().units());
    }
    static Result validateCardinal(CapsulePathSource source, CardinalRealizedArcRecipe.Prepared prepared) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(prepared, "prepared");
        return validate(source, prepared.recipe().target(), new Vec3d(0, 1, 0), source.work().units(), prepared);
    }
    private static Result validate(CapsulePathSource source, PlayerBodyPlacement target,
            Vec3d preferredAxis, long before) {
        return validate(source, target, preferredAxis, before, null);
    }
    private static Result validate(CapsulePathSource source, PlayerBodyPlacement target,
            Vec3d preferredAxis, long before, CardinalRealizedArcRecipe.Prepared cardinal) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(preferredAxis, "preferredAxis");
        var accepted = source.actualSource();
        var actualStart = source.start();
        var capture = source.capture();
        var expectedWindow = source.window();
        var accuracy = source.accuracy();
        var sameBudget = source.queries();
        var sameNumeric = source.work();
        List<Query> queries = new ArrayList<>();
        List<Separation> separations = new ArrayList<>();
        List<GenericArcPlaneReceipt> signedHalfspaces = new ArrayList<>();
        int regions = 0;
        try {
            source.chargeMetadata();
            if (!source.valid()
                    || !contains(expectedWindow, actualStart.placement().bounds())
                    || !contains(expectedWindow, accepted.placement().bounds())
                    || !contains(expectedWindow, target.bounds())) {
                return failed(Failure.INVALID_INPUT, queries, regions, sameNumeric, before, null, separations);
            }
            if (changed(accepted, capture, expectedWindow)) {
                return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
            }
            if (source.completedSegments() >= CapsulePathSource.MAX_SEGMENTS) {
                return failed(Failure.PHASE_LIMIT, queries, regions, sameNumeric, before, null, separations);
            }
            if (cardinal != null && (!cardinal.recipe().matchesPathSource(source)
                    || cardinal.recipe().sourceContainment().source() != accepted
                    || !cardinal.recipe().matches(actualStart, target, sameNumeric)
                    || !cardinal.path().preparedFor(actualStart, target, sameNumeric)))
                return failed(Failure.INVALID_INPUT, queries, regions, sameNumeric, before, null, separations);
            var path = cardinal == null ? CapsuleArcEnclosure.prepare(actualStart, target, preferredAxis, sameNumeric)
                    : cardinal.path();
            Failure endpoint = observe(actualStart.capsule(), Stage.START, 0, 0,
                    accepted, capture, expectedWindow, accuracy, sameBudget, queries, sameNumeric, separations);
            if (endpoint != null) return failed(endpoint, queries, regions, sameNumeric, before, null, separations);
            endpoint = observe(target.capsule(), Stage.TARGET, 1, 1,
                    accepted, capture, expectedWindow, accuracy, sameBudget, queries, sameNumeric, separations);
            if (endpoint != null) return failed(endpoint, queries, regions, sameNumeric, before, null, separations);

            var pending = new ArrayDeque<Region>();
            pending.push(new Region(0, 1, 0));
            while (!pending.isEmpty()) {
                if (++regions > MAX_REGIONS) {
                    return failed(Failure.SUBDIVISION_LIMIT, queries, regions, sameNumeric, before, null, separations);
                }
                sameNumeric.charge(1);
                Region region = pending.pop();
                if (changed(accepted, capture, expectedWindow)) {
                    return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                }
                var enclosure = path.enclose(region.from(), region.to(), sameNumeric);
                if (!enclosure.uses(sameNumeric))
                    return failed(Failure.INVALID_INPUT, queries, regions, sameNumeric, before, null, separations);
                boolean outsideWindow = !contains(expectedWindow, enclosure.bounds());
                boolean refine = outsideWindow;
                Aabb midpointBounds = null;
                // No observation against an incomplete window may authorize any part of this region.
                // Subdivision can tighten an overly coarse construction envelope without new capture.
                for (int ordinal = 0; !outsideWindow && ordinal < capture.boxes().size(); ordinal++) {
                    Aabb box = capture.boxes().get(ordinal);
                    if (changed(accepted, capture, expectedWindow))
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                    boolean separated = CapsuleOutwardBounds.strictlySeparated(enclosure.bounds(), box, sameNumeric);
                    if (changed(accepted, capture, expectedWindow))
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                    if (separated) {
                        separations.add(new Separation(Stage.ENCLOSURE, region.from(), region.to(), ordinal, enclosure.bounds(), box));
                        continue;
                    }
                    CardinalPlaneReceipt cardinalPlane = cardinal == null ? null
                            : CardinalPlaneReceipt.tryIssue(cardinal, source, ordinal, box, region.from(), region.to());
                    if (cardinalPlane != null && cardinalPlane.matches(cardinal, source, ordinal, box,
                            region.from(), region.to())) {
                        if (changed(accepted, capture, expectedWindow))
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                        continue;
                    }
                    GenericArcPlaneReceipt signedPlane = GenericArcPlaneReceipt.tryIssue(path, source, accepted,
                            actualStart, target, capture, expectedWindow, accuracy, sameBudget, sameNumeric,
                            ordinal, box, region.from(), region.to());
                    if (changed(accepted, capture, expectedWindow))
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                    if (signedPlane != null && signedPlane.matches(path, source, accepted, actualStart, target,
                            capture, expectedWindow, accuracy, sameBudget, sameNumeric, ordinal, box,
                            region.from(), region.to())) {
                        signedHalfspaces.add(signedPlane);
                        if (changed(accepted, capture, expectedWindow))
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                        continue;
                    }
                    YPlaneReceipt planeReceipt = YPlaneReceipt.tryIssue(path, source, accepted, actualStart,
                            target, capture, expectedWindow, accuracy, sameBudget, sameNumeric,
                            ordinal, box, region.from(), region.to());
                    if (changed(accepted, capture, expectedWindow)) {
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                    }
                    if (planeReceipt != null && planeReceipt.matches(path, source, accepted, actualStart,
                            target, capture, expectedWindow, accuracy, sameBudget, sameNumeric,
                            ordinal, box, region.from(), region.to())) {
                        if (changed(accepted, capture, expectedWindow)) {
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                        }
                        continue;
                    }
                    ContactResult contact = sameBudget.query(enclosure.padded(), box, accuracy);
                    queries.add(new Query(Stage.ENCLOSURE, region.from(), region.to(), ordinal, box, contact));
                    if (changed(accepted, capture, expectedWindow)) {
                        return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                    }
                    if (contact.relation() == Relation.UNRESOLVED) {
                        return failed(Failure.UNRESOLVED, queries, regions, sameNumeric, before, null, separations);
                    }
                    // Frozen CHB exact equality is TOUCHING. Inflated-shape overlap is only uncertainty.
                    if (contact.relation() == Relation.OVERLAPPING) {
                        refine = true;
                        // A true midpoint collision is a rejection witness, never a sampling acceptance.
                        if (midpointBounds == null) midpointBounds = CapsuleOutwardBounds.of(enclosure.reference(), sameNumeric);
                        boolean midpointSeparated = CapsuleOutwardBounds.strictlySeparated(midpointBounds, box, sameNumeric);
                        if (changed(accepted, capture, expectedWindow))
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                        if (midpointSeparated) {
                            separations.add(new Separation(Stage.MIDPOINT, region.from(), region.to(), ordinal, midpointBounds, box));
                            continue; // The padded region still needs refinement; no midpoint collision query is needed.
                        }
                        ContactResult midpoint = sameBudget.query(enclosure.reference(), box, accuracy);
                        queries.add(new Query(Stage.MIDPOINT, region.from(), region.to(), ordinal, box, midpoint));
                        if (changed(accepted, capture, expectedWindow)) {
                            return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
                        }
                        if (midpoint.relation() == Relation.UNRESOLVED) {
                            return failed(Failure.UNRESOLVED, queries, regions, sameNumeric, before, null, separations);
                        }
                        if (midpoint.relation() == Relation.OVERLAPPING) {
                            return failed(Failure.ARC_OVERLAP, queries, regions, sameNumeric, before, null, separations);
                        }
                    }
                }
                if (refine) {
                    if (region.depth() >= MAX_DEPTH) {
                        return failed(outsideWindow ? Failure.WINDOW_TOO_SMALL : Failure.SUBDIVISION_LIMIT,
                                queries, regions, sameNumeric, before, null, separations);
                    }
                    sameNumeric.charge(2);
                    double middle = region.from() + (region.to() - region.from()) * 0.5;
                    if (!(middle > region.from() && middle < region.to())) {
                        return failed(Failure.SUBDIVISION_LIMIT, queries, regions, sameNumeric, before, null, separations);
                    }
                    if (cardinal != null) {
                        // Do not let an early near-critical interval starve its Cardinal sibling regions.
                        pending.addLast(new Region(region.from(), middle, region.depth() + 1));
                        pending.addLast(new Region(middle, region.to(), region.depth() + 1));
                    } else {
                        pending.push(new Region(middle, region.to(), region.depth() + 1));
                        pending.push(new Region(region.from(), middle, region.depth() + 1));
                    }
                }
            }
            if (changed(accepted, capture, expectedWindow)) {
                return failed(Failure.CONTEXT_CHANGED, queries, regions, sameNumeric, before, null, separations);
            }
            return new VerifiedArc(source, accepted, actualStart, target, capture, expectedWindow, sameBudget,
                    sameNumeric, queries, sameNumeric.units() - before, regions, separations, signedHalfspaces);
        } catch (CapsuleContactQueryBudget.BudgetExhausted exhausted) {
            return failed(Failure.QUERY_BUDGET, queries, regions, sameNumeric, before, exhausted, separations);
        } catch (NumericWorkExhaustedException exhausted) {
            return failed(Failure.NUMERIC_BUDGET, queries, regions, sameNumeric, before, exhausted, separations);
        }
        // Other exceptions preserve their cause/stack and reach the owning transaction's failure boundary.
    }

    private static Failure observe(Capsule actual, Stage stage, double from, double to,
            PlayerPhysicalSnapshot accepted, Snapshot capture, Aabb window, ContactAccuracy accuracy,
            CapsuleContactQueryBudget budget, List<Query> queries, StoredDyadic.Work numeric, List<Separation> separations) {
        Aabb actualBounds = CapsuleOutwardBounds.of(actual, numeric);
        boolean overlapping = false;
        for (int ordinal = 0; ordinal < capture.boxes().size(); ordinal++) {
            Aabb box = capture.boxes().get(ordinal);
            if (changed(accepted, capture, window)) return Failure.CONTEXT_CHANGED;
            boolean separated = CapsuleOutwardBounds.strictlySeparated(actualBounds, box, numeric);
            if (changed(accepted, capture, window)) return Failure.CONTEXT_CHANGED;
            if (separated) {
                separations.add(new Separation(stage, from, to, ordinal, actualBounds, box));
                continue;
            }
            ContactResult contact = budget.query(actual, box, accuracy);
            queries.add(new Query(stage, from, to, ordinal, box, contact));
            if (changed(accepted, capture, window)) return Failure.CONTEXT_CHANGED;
            if (contact.relation() == Relation.UNRESOLVED) return Failure.UNRESOLVED;
            overlapping |= contact.relation() == Relation.OVERLAPPING;
        }
        if (changed(accepted, capture, window)) return Failure.CONTEXT_CHANGED;
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
            StoredDyadic.Work numeric, long before, Throwable cause, List<Separation> separations) {
        return new Failed(failure, queries, regions, numeric.units() - before, cause, separations);
    }
    private static Failed failed(Failure failure, List<Query> queries, int regions,
            StoredDyadic.Work numeric, long before, Throwable cause) {
        return new Failed(failure, queries, regions, numeric.units() - before, cause);
    }
}
