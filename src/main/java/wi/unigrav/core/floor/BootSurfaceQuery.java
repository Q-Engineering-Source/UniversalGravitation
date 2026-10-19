package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import wi.unigrav.core.floor.BootSurfaceCompiler.Fragment;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceCompiler.SurfaceSet;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedSnapshot;
import wi.unigrav.core.floor.BootWorldSurfaces.Snapshot;
import wi.unigrav.core.player.BootQueryArithmetic;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.BootSurfaceGeometry.Proximity;
import wi.unigrav.core.player.BootSurfaceGeometry.Source;
import wi.unigrav.core.player.BootSurfaceGeometry.SourceKind;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/**
 * Bounded finite-surface range and closest-witness visibility queries (metres).
 *
 * <p>A clear witness proves only that witness's open path through the supplied
 * raw boxes. A blocked closest path does not prove the whole fragment hidden:
 * it makes the complete query unavailable, even when another candidate exists.
 * The sole whole-fragment exception is a zero-radius Cardinal foot rectangle
 * and one same-capture blocker whose bounds strictly separate the entire source
 * and fragment along one axis while closed-containing their full transverse
 * hull. That certificate proves every direct source-to-fragment segment meets
 * that blocker; it does not combine boxes or relax closed-box tangency. For all
 * other blocked paths, tangencies inside the path remain conservatively
 * unresolved. Only the start/end contacts and a lower-ball sensor's own radial prefix are omitted.
 * This is not a body collision, support, force, or persistent target identity.
 */
public final class BootSurfaceQuery {
    private BootSurfaceQuery() {
    }

    public record Limits(int maxFragments, int maxVisibilityBoxTests) {
        public Limits {
            if (maxFragments < 1 || maxFragments > 16384
                    || maxVisibilityBoxTests < 1 || maxVisibilityBoxTests > 1_048_576) {
                throw new IllegalArgumentException("surface query limits exceed the finite work profile");
            }
        }
    }

    public enum Reason {
        COMPILATION, CAPTURE_REACH, FRAGMENT_LIMIT, VISIBILITY_BUDGET,
        NUMERIC, SIDE_UNRESOLVED, VISIBILITY_UNRESOLVED
    }

    public sealed interface Result permits Measurements, Unavailable {
    }

    /** Fixed-size evidence for one blocked visibility box; it never retains surface contributors. */
    public record VisibilityDiagnostic(SourceKind sourceKind, PlayerPhysicalSnapshot.Scope sourceScope,
                                       long sourceRevision, Aabb sourceAnchorBounds, double sourceRadius,
                                       Vec3d sourceAnchor, Vec3d surfaceAnchor,
                                       BootSurfaceGeometry.FaceRectangle fragmentGeometry,
                                       Aabb offendingRawBounds, BlockPos offendingCell, IBlockState offendingState,
                                       int observationOrdinal, int observationCount, int primitiveOrdinal, int boxesTested,
                                       int visibilityBoxTestLimit, OptionalLong captureTick,
                                       OptionalLong rulesRevision) {
        public VisibilityDiagnostic {
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(sourceScope, "sourceScope");
            if (sourceRevision < 0L || !Double.isFinite(sourceRadius) || sourceRadius < 0.0
                    || observationOrdinal < 0 || observationCount < 1 || observationOrdinal >= observationCount
                    || primitiveOrdinal < 0 || boxesTested < 1
                    || visibilityBoxTestLimit < boxesTested) {
                throw new IllegalArgumentException("invalid bounded visibility diagnostic");
            }
            Objects.requireNonNull(sourceAnchorBounds, "sourceAnchorBounds");
            Objects.requireNonNull(sourceAnchor, "sourceAnchor");
            Objects.requireNonNull(surfaceAnchor, "surfaceAnchor");
            Objects.requireNonNull(fragmentGeometry, "fragmentGeometry");
            Objects.requireNonNull(offendingRawBounds, "offendingRawBounds");
            offendingCell = Objects.requireNonNull(offendingCell, "offendingCell").toImmutable();
            Objects.requireNonNull(offendingState, "offendingState");
            Objects.requireNonNull(captureTick, "captureTick");
            Objects.requireNonNull(rulesRevision, "rulesRevision");
        }

        /** Bounded single-line context suitable for the existing owner failure message. */
        public String summary() {
            return "visibility{source=" + sourceKind + ",scope=" + sourceScope + ",revision=" + sourceRevision
                    + ",anchorBounds=" + sourceAnchorBounds + ",radius=" + sourceRadius
                    + ",anchors=" + sourceAnchor + "->" + surfaceAnchor
                    + ",face=" + fragmentGeometry.outward() + "/" + fragmentGeometry.bounds()
                    + ",blocker=" + offendingCell + "/" + boundedStateSummary(offendingState) + "/" + offendingRawBounds
                    + ",observation=" + observationOrdinal + "/" + observationCount
                    + ",primitiveOrdinal=" + primitiveOrdinal
                    + ",boxes=" + boxesTested + "/" + visibilityBoxTestLimit
                    + ",captureTick=" + captureTick + ",rulesRevision=" + rulesRevision + "}";
        }

        private static String boundedStateSummary(IBlockState state) {
            String value = state.toString().replace('\r', ' ').replace('\n', ' ');
            return value.length() <= 128 ? value : value.substring(0, 125) + "...";
        }
    }

    public record Unavailable(Reason reason, Optional<Fragment> fragment, String detail,
                              Optional<ArithmeticException> cause,
                              Optional<VisibilityDiagnostic> visibilityDiagnostic) implements Result {
        public Unavailable(Reason reason, Optional<Fragment> fragment, String detail,
                           Optional<ArithmeticException> cause) {
            this(reason, fragment, detail, cause, Optional.empty());
        }

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(fragment, "fragment");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(visibilityDiagnostic, "visibilityDiagnostic");
        }
    }

    /** Range/side/path prerequisites only; an effective tier strength and actor eligibility remain separate. */
    public record Candidate(Fragment fragment, Proximity proximity) {
        public Candidate {
            Objects.requireNonNull(fragment, "fragment");
            Objects.requireNonNull(proximity, "proximity");
        }
    }

    public static final class Measurements implements Result {
        private final Source source;
        private final SurfaceSet surfaces;
        private final Optional<Snapshot> capture;
        private final Optional<QualifiedSnapshot> qualifiedCapture;
        private final List<Candidate> candidates;
        private final Optional<BootQueryArithmetic> arithmetic;
        private final Optional<BootQueryArithmetic.Usage> numericBefore, numericAfter;

        private Measurements(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
                             Optional<QualifiedSnapshot> qualifiedCapture, List<Candidate> candidates) {
            this(source, surfaces, capture, qualifiedCapture, candidates, Optional.empty(), Optional.empty(), Optional.empty());
        }
        private Measurements(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
                             Optional<QualifiedSnapshot> qualifiedCapture, List<Candidate> candidates,
                             Optional<BootQueryArithmetic> arithmetic,
                             Optional<BootQueryArithmetic.Usage> numericBefore, Optional<BootQueryArithmetic.Usage> numericAfter) {
            this.arithmetic = arithmetic; this.numericBefore = numericBefore; this.numericAfter = numericAfter;
            this.source = source;
            this.surfaces = surfaces;
            this.capture = capture;
            this.qualifiedCapture = qualifiedCapture;
            this.candidates = List.copyOf(candidates);
        }

        public Optional<BootQueryArithmetic> arithmetic() { return arithmetic; }
        public Optional<BootQueryArithmetic.Usage> numericBefore() { return numericBefore; }
        public Optional<BootQueryArithmetic.Usage> numericAfter() { return numericAfter; }
        public boolean usesArithmetic(BootQueryArithmetic expected) { return arithmetic.filter(a -> a == expected).isPresent(); }
        public Source source() { return source; }
        public SurfaceSet surfaces() { return surfaces; }
        public List<Candidate> candidates() { return candidates; }

        /** Empty for supplied-geometry queries. Presence still does not prove current live-owner validity. */
        public Optional<Snapshot> capture() { return capture; }

        /** Same-read material selection/revision when the qualified capture entry was used. */
        public Optional<QualifiedSnapshot> qualifiedCapture() { return qualifiedCapture; }
    }

    /**
     * Uses the capture's own accepted source, never a separately supplied revision.
     * The owner must still establish current material/rule validity, world lifetime
     * and actor authority before consuming a result. No live world is read here.
     * All supplied qualifications must fit the capture reach, including those whose
     * surfaces are fully occluded. The caller still owns coverage of unobserved rules.
     */
    public static Result evaluate(Snapshot capture, Map<Primitive, Qualification> qualifications,
                                  BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits) {
        requireUnboundSource(Objects.requireNonNull(capture, "capture").footprint().source());
        return evaluateCaptured(capture, qualifications, Optional.empty(), compilerLimits, queryLimits);
    }

    /**
     * Binds explicit effective boot profiles to the material selectors preserved
     * by captureQualified. Missing profiles are invalid caller/configuration data,
     * not a reason to invent a default tier or reread the world. The caller owns
     * equipment qualification and must refresh this mapping when it changes.
     * The mapping must contain the complete active selector-profile domain for
     * this observation, not only selectors encountered inside the footprint.
     * Its largest reach must fit the capture even when no magnetic box was found;
     * an unobserved larger profile cannot be silently interpreted as no target.
     */
    public static Result evaluateQualified(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
                                           BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits) {
        return evaluateQualifiedInternal(capture, effectiveTiers, compilerLimits, queryLimits, null);
    }

    public static Result evaluateQualified(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
            BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return evaluateQualifiedInternal(capture, effectiveTiers, compilerLimits, queryLimits, work);
    }

    private static Result evaluateQualifiedInternal(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
            BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(capture, "capture");
        requireUnboundSource(capture.snapshot().footprint().source());
        Objects.requireNonNull(compilerLimits, "compilerLimits");
        Objects.requireNonNull(queryLimits, "queryLimits");
        Map<String, BootTier> profiles = Map.copyOf(effectiveTiers);
        Map<Primitive, Qualification> qualifications = new HashMap<>();
        for (var entry : capture.materialMatches().entrySet()) {
            charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            String selected = entry.getValue().selectionId();
            BootTier tier = profiles.get(selected);
            if (tier == null) throw new IllegalArgumentException("missing effective boot profile for selected material: " + selected);
            qualifications.put(entry.getKey(), new Qualification(selected, tier));
        }
        // Validate missing observed profiles first: invalid configuration must not
        // be hidden by a range failure. Reach coverage is independent of whether
        // a selector happened to produce any visible fragment in this capture.
        for (BootTier tier : profiles.values()) {
            charge(work, EnvironmentObservationWork.Unit.COMPARISON);
            if (tier.captureReachMetres() > capture.snapshot().footprint().reachMetres()) {
                return failure(Reason.CAPTURE_REACH, null, "active profile reach exceeds the observed producer footprint");
            }
        }
        return evaluateCaptured(capture.snapshot(), qualifications, Optional.of(capture), compilerLimits, queryLimits, work);
    }

    /** Explicit shared arithmetic. Captured material/source identity is not live equipment/world authority. */
    public static Result evaluateQualified(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
                                           BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits,
                                           BootQueryArithmetic arithmetic) {
        return evaluateQualifiedExact(capture, effectiveTiers, compilerLimits, queryLimits, arithmetic, null);
    }

    public static Result evaluateQualified(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
            BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits, BootQueryArithmetic arithmetic,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        if (!work.arithmetic().sameCapability(arithmetic)) throw new IllegalArgumentException("foreign observation arithmetic");
        return evaluateQualifiedExact(capture, effectiveTiers, compilerLimits, queryLimits, arithmetic, work);
    }

    private static Result evaluateQualifiedExact(QualifiedSnapshot capture, Map<String, BootTier> effectiveTiers,
            BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits, BootQueryArithmetic arithmetic,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(capture); Objects.requireNonNull(compilerLimits); Objects.requireNonNull(queryLimits);
        Objects.requireNonNull(arithmetic);
        Source source = capture.snapshot().footprint().source();
        if (!source.usesArithmetic(arithmetic)) throw new IllegalArgumentException("qualified boot source arithmetic differs");
        var before = arithmetic.usage();
        Map<String, BootTier> profiles = Map.copyOf(effectiveTiers);
        Map<Primitive, Qualification> qualifications = new HashMap<>();
        for (var entry : capture.materialMatches().entrySet()) {
            String selected = entry.getValue().selectionId();
            BootTier tier = profiles.get(selected);
            if (tier == null) throw new IllegalArgumentException("missing effective boot profile for selected material: " + selected);
            qualifications.put(entry.getKey(), new Qualification(selected, tier));
        }
        for (BootTier tier : profiles.values()) {
            if (arithmetic.compare(arithmetic.exact(tier.captureReachMetres()),
                    arithmetic.exact(capture.snapshot().footprint().reachMetres())) > 0)
                return failure(Reason.CAPTURE_REACH, null, "active profile reach exceeds the observed producer footprint");
        }
        // Compiler endpoint operations remain under its existing per-call primitive limits, not numeric/CHB totals.
        var compiled = work == null ? BootSurfaceCompiler.compile(capture.snapshot().primitives(), qualifications, compilerLimits)
                : BootSurfaceCompiler.compile(capture.snapshot().primitives(), qualifications, compilerLimits, work);
        if (compiled instanceof BootSurfaceCompiler.Unavailable unavailable)
            return failure(Reason.COMPILATION, null, unavailable.reason() + ": " + unavailable.detail());
        SurfaceSet surfaces = (SurfaceSet) compiled;
        if (surfaces.fragments().size() > queryLimits.maxFragments())
            return failure(Reason.FRAGMENT_LIMIT, null, "complete surface set exceeds the query fragment limit");
        int boxTests = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Fragment fragment : surfaces.fragments()) {
            charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            Proximity proximity = (Proximity) BootSurfaceGeometry.measure(source, fragment.geometry(),
                    fragment.qualification().effectiveTier().captureReachMetres(), arithmetic);
            if (proximity.range() == BootSurfaceGeometry.Range.OUT_OF_RANGE) continue;
            if (proximity.minimumSide() == BootSurfaceGeometry.MinimumSide.BEHIND_PLANE) {
                if (arithmetic.sign(maximumSide(source, fragment, arithmetic)) < 0) continue;
                return failure(Reason.SIDE_UNRESOLVED, fragment, "sensor straddles this face plane; this is not a finite-solid penetration verdict");
            }
            SharedPath path = new SharedPath(proximity, arithmetic);
            List<Primitive> observations = surfaces.observations();
            boolean fragmentFullyOccluded = false;
            for (int observationOrdinal = 0; observationOrdinal < observations.size(); observationOrdinal++) {
                Primitive solid = observations.get(observationOrdinal);
                if (boxTests == queryLimits.maxVisibilityBoxTests())
                    return failure(Reason.VISIBILITY_BUDGET, fragment, "complete visibility work exceeds its limit");
                boxTests++;
                charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
                if (path.blockedBy(solid.rawBounds())) {
                    if (fullyOccludedBy(source, fragment, solid.rawBounds(), arithmetic)) {
                        fragmentFullyOccluded = true;
                        break;
                    }
                    return visibilityFailure(source, fragment, proximity, solid, observationOrdinal,
                            observations.size(), boxTests,
                            queryLimits.maxVisibilityBoxTests(), Optional.of(capture.snapshot()), Optional.of(capture));
                }
            }
            if (fragmentFullyOccluded) continue;
            candidates.add(new Candidate(fragment, proximity));
        }
        return new Measurements(source, surfaces, Optional.of(capture.snapshot()), Optional.of(capture), candidates,
                Optional.of(arithmetic), Optional.of(before), Optional.of(arithmetic.usage()));
    }

    private static BootQueryArithmetic.Number maximumSide(Source source, Fragment fragment, BootQueryArithmetic arithmetic) {
        Vec3d outward = fragment.geometry().outward().up();
        int axis = outward.x() != 0 ? 0 : outward.y() != 0 ? 1 : 2;
        boolean positive = component(outward, axis) > 0;
        var anchor = arithmetic.exact(component(positive ? source.anchorBounds().max() : source.anchorBounds().min(), axis));
        var plane = arithmetic.exact(component(fragment.geometry().bounds().min(), axis));
        return arithmetic.add(positive ? arithmetic.subtract(anchor, plane) : arithmetic.subtract(plane, anchor), arithmetic.exact(source.radius()));
    }
    private static final class SharedPath {
        private final BootQueryArithmetic arithmetic;
        private final BootQueryArithmetic.Number[] start = new BootQueryArithmetic.Number[3], delta = new BootQueryArithmetic.Number[3];
        private final BootQueryArithmetic.Number distanceSquared, radiusSquared;
        private final BootQueryArithmetic.Fraction zero, one;
        private SharedPath(Proximity proximity, BootQueryArithmetic arithmetic) {
            this.arithmetic = arithmetic;
            var square = arithmetic.exact(0);
            for (int axis = 0; axis < 3; axis++) {
                start[axis] = arithmetic.exact(component(proximity.sourceAnchor(), axis));
                delta[axis] = arithmetic.subtract(arithmetic.exact(component(proximity.surfaceAnchor(), axis)), start[axis]);
                square = arithmetic.add(square, arithmetic.multiply(delta[axis], delta[axis]));
            }
            distanceSquared = square;
            var radius = arithmetic.exact(proximity.source().radius());
            radiusSquared = arithmetic.multiply(radius, radius);
            var unit = arithmetic.exact(1);
            zero = arithmetic.fraction(arithmetic.exact(0), unit); one = arithmetic.fraction(unit, unit);
        }
        private boolean blockedBy(Aabb box) {
            if (arithmetic.compare(distanceSquared, radiusSquared) <= 0) return false;
            var enter = zero; var exit = one;
            for (int axis = 0; axis < 3; axis++) {
                var low = arithmetic.subtract(arithmetic.exact(component(box.min(), axis)), start[axis]);
                var high = arithmetic.subtract(arithmetic.exact(component(box.max(), axis)), start[axis]);
                if (arithmetic.sign(delta[axis]) == 0) {
                    if (arithmetic.sign(low) > 0 || arithmetic.sign(high) < 0) return false;
                    continue;
                }
                var first = arithmetic.fraction(low, delta[axis]); var last = arithmetic.fraction(high, delta[axis]);
                if (arithmetic.compareFractions(first, last) > 0) { var swap = first; first = last; last = swap; }
                if (arithmetic.compareFractions(first, enter) > 0) enter = first;
                if (arithmetic.compareFractions(last, exit) < 0) exit = last;
                if (arithmetic.compareFractions(enter, exit) > 0) return false;
            }
            if (arithmetic.compareFractions(exit, zero) <= 0 || arithmetic.compareFractions(enter, one) >= 0) return false;
            return arithmetic.compare(arithmetic.multiply(arithmetic.multiply(exit.numerator(), exit.numerator()), distanceSquared),
                    arithmetic.multiply(arithmetic.multiply(radiusSquared, exit.denominator()), exit.denominator())) > 0;
        }
    }
    private static Result evaluateCaptured(Snapshot capture, Map<Primitive, Qualification> qualifications,
                                           Optional<QualifiedSnapshot> qualifiedCapture,
                                           BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits) {
        return evaluateCaptured(capture, qualifications, qualifiedCapture, compilerLimits, queryLimits, null);
    }

    private static Result evaluateCaptured(Snapshot capture, Map<Primitive, Qualification> qualifications,
            Optional<QualifiedSnapshot> qualifiedCapture, BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(queryLimits, "queryLimits");
        Map<Primitive, Qualification> ownedQualifications = Map.copyOf(qualifications);
        var compiled = work == null ? BootSurfaceCompiler.compile(capture.primitives(), ownedQualifications, compilerLimits)
                : BootSurfaceCompiler.compile(capture.primitives(), ownedQualifications, compilerLimits, work);
        if (compiled instanceof BootSurfaceCompiler.Unavailable unavailable) {
            return failure(Reason.COMPILATION, null, unavailable.reason() + ": " + unavailable.detail());
        }
        SurfaceSet surfaces = (SurfaceSet) compiled;
        for (Qualification qualification : ownedQualifications.values()) {
            if (qualification.effectiveTier().captureReachMetres() > capture.footprint().reachMetres()) {
                return failure(Reason.CAPTURE_REACH, null, "tier reach exceeds the observed producer footprint");
            }
        }
        return evaluate(capture.footprint().source(), surfaces, Optional.of(capture), qualifiedCapture, queryLimits, work);
    }

    /**
     * Pure supplied-geometry entry point. It cannot establish capture completeness
     * or bind a surface set to a real world, source scope, or physical revision.
     * The returned Measurements deliberately has no capture provenance.
     */
    public static Result evaluateGeometry(Source source, SurfaceSet surfaces, Limits limits) {
        requireUnboundSource(source);
        return evaluate(source, surfaces, Optional.empty(), Optional.empty(), limits);
    }

    private static void requireUnboundSource(Source source) {
        if (Objects.requireNonNull(source, "source").hasArithmetic())
            throw new IllegalArgumentException("bound boot source requires explicit shared query entry");
    }

    private static Result evaluate(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
                                    Optional<QualifiedSnapshot> qualifiedCapture, Limits limits) {
        return evaluate(source, surfaces, capture, qualifiedCapture, limits, null);
    }

    private static Result evaluate(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
            Optional<QualifiedSnapshot> qualifiedCapture, Limits limits,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(source, "source");
        if (source.hasArithmetic()) throw new IllegalArgumentException("bound boot source requires explicit shared query entry");
        Objects.requireNonNull(surfaces, "surfaces");
        Objects.requireNonNull(limits, "limits");
        if (surfaces.fragments().size() > limits.maxFragments()) {
            return failure(Reason.FRAGMENT_LIMIT, null, "complete surface set exceeds the query fragment limit");
        }
        int boxTests = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Fragment fragment : surfaces.fragments()) {
            charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            var measured = BootSurfaceGeometry.measure(source, fragment.geometry(),
                    fragment.qualification().effectiveTier().captureReachMetres());
            if (measured instanceof BootSurfaceGeometry.Unavailable unavailable) {
                return new Unavailable(Reason.NUMERIC, Optional.of(fragment), "finite proximity is unrepresentable",
                        Optional.of(unavailable.cause()));
            }
            Proximity proximity = (Proximity) measured;
            if (proximity.range() == BootSurfaceGeometry.Range.OUT_OF_RANGE) continue;
            if (proximity.minimumSide() == BootSurfaceGeometry.MinimumSide.BEHIND_PLANE) {
                if (maximumSide(source, fragment).signum() < 0) continue;
                return failure(Reason.SIDE_UNRESOLVED, fragment,
                        "sensor straddles this face plane; this is not a finite-solid penetration verdict");
            }
            Path path = new Path(proximity);
            List<Primitive> observations = surfaces.observations();
            boolean fragmentFullyOccluded = false;
            for (int observationOrdinal = 0; observationOrdinal < observations.size(); observationOrdinal++) {
                Primitive solid = observations.get(observationOrdinal);
                if (boxTests == limits.maxVisibilityBoxTests()) {
                    return failure(Reason.VISIBILITY_BUDGET, fragment, "complete visibility work exceeds its limit");
                }
                boxTests++;
                charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
                if (path.blockedBy(solid.rawBounds())) {
                    if (fullyOccludedBy(source, fragment, solid.rawBounds(), null)) {
                        fragmentFullyOccluded = true;
                        break;
                    }
                    return visibilityFailure(source, fragment, proximity, solid, observationOrdinal,
                            observations.size(), boxTests,
                            limits.maxVisibilityBoxTests(), capture, qualifiedCapture);
                }
            }
            if (fragmentFullyOccluded) continue;
            candidates.add(new Candidate(fragment, proximity));
        }
        return new Measurements(source, surfaces, capture, qualifiedCapture, candidates);
    }

    /**
     * Proves that one already-tested captured box blocks every line segment from
     * this exact Cardinal foot rectangle to this exact fragment. No coordinates
     * are expanded, rounded, merged, or sampled. Free lower-ball sources and
     * positive-radius sensors retain the ordinary unresolved behavior.
     */
    private static boolean fullyOccludedBy(Source source, Fragment fragment, Aabb blocker,
                                           BootQueryArithmetic arithmetic) {
        if (source.kind() != SourceKind.CARDINAL_FOOT_RECTANGLE || source.radius() != 0.0) return false;
        Aabb anchors = source.anchorBounds();
        Aabb face = fragment.geometry().bounds();
        for (int axis = 0; axis < 3; axis++) {
            double anchorMin = component(anchors.min(), axis), anchorMax = component(anchors.max(), axis);
            double faceMin = component(face.min(), axis), faceMax = component(face.max(), axis);
            double blockerMin = component(blocker.min(), axis), blockerMax = component(blocker.max(), axis);
            boolean sourceOnPositiveSide = compare(anchorMin, faceMax, arithmetic) > 0;
            boolean fragmentOnPositiveSide = compare(faceMin, anchorMax, arithmetic) > 0;
            if (sourceOnPositiveSide
                    && compare(maximum(faceMax, blockerMin, arithmetic),
                    minimum(anchorMin, blockerMax, arithmetic), arithmetic) < 0
                    && blockerContainsTransverseHull(anchors, face, blocker, axis, arithmetic)) {
                return true;
            }
            if (fragmentOnPositiveSide
                    && compare(maximum(anchorMax, blockerMin, arithmetic),
                    minimum(faceMin, blockerMax, arithmetic), arithmetic) < 0
                    && blockerContainsTransverseHull(anchors, face, blocker, axis, arithmetic)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockerContainsTransverseHull(Aabb anchors, Aabb face, Aabb blocker, int separatedAxis,
                                                         BootQueryArithmetic arithmetic) {
        for (int axis = 0; axis < 3; axis++) {
            if (axis == separatedAxis) continue;
            double hullMin = minimum(component(anchors.min(), axis), component(face.min(), axis), arithmetic);
            double hullMax = maximum(component(anchors.max(), axis), component(face.max(), axis), arithmetic);
            if (compare(component(blocker.min(), axis), hullMin, arithmetic) > 0
                    || compare(component(blocker.max(), axis), hullMax, arithmetic) < 0) {
                return false;
            }
        }
        return true;
    }

    private static double minimum(double left, double right, BootQueryArithmetic arithmetic) {
        return compare(left, right, arithmetic) <= 0 ? left : right;
    }

    private static double maximum(double left, double right, BootQueryArithmetic arithmetic) {
        return compare(left, right, arithmetic) >= 0 ? left : right;
    }

    private static int compare(double left, double right, BootQueryArithmetic arithmetic) {
        if (arithmetic != null) return arithmetic.compare(arithmetic.exact(left), arithmetic.exact(right));
        return left < right ? -1 : left > right ? 1 : 0;
    }

    private static BigDecimal maximumSide(Source source, Fragment fragment) {
        Vec3d outward = fragment.geometry().outward().up();
        int axis = outward.x() != 0 ? 0 : outward.y() != 0 ? 1 : 2;
        boolean positive = component(outward, axis) > 0;
        BigDecimal anchor = exact(component(positive ? source.anchorBounds().max() : source.anchorBounds().min(), axis));
        BigDecimal plane = exact(component(fragment.geometry().bounds().min(), axis));
        return (positive ? anchor.subtract(plane) : plane.subtract(anchor)).add(exact(source.radius()));
    }

    private static void charge(EnvironmentObservationWork work,
            EnvironmentObservationWork.Unit unit) {
        if (work != null) work.charge(unit);
    }

    private static Unavailable failure(Reason reason, Fragment fragment, String detail) {
        return new Unavailable(reason, Optional.ofNullable(fragment), detail, Optional.empty());
    }

    private static Unavailable visibilityFailure(Source source, Fragment fragment, Proximity proximity,
                                                 Primitive blocker, int observationOrdinal, int observationCount,
                                                 int boxesTested, int boxTestLimit, Optional<Snapshot> capture,
                                                 Optional<QualifiedSnapshot> qualifiedCapture) {
        var physical = source.physical();
        var diagnostic = new VisibilityDiagnostic(source.kind(), physical.scope(), physical.revision(),
                source.anchorBounds(), source.radius(), proximity.sourceAnchor(), proximity.surfaceAnchor(),
                fragment.geometry(), blocker.rawBounds(), blocker.cell(), blocker.storedState(), observationOrdinal,
                observationCount, blocker.ordinal(), boxesTested, boxTestLimit,
                capture.isPresent() ? OptionalLong.of(capture.orElseThrow().captureTick()) : OptionalLong.empty(),
                qualifiedCapture.isPresent() ? OptionalLong.of(qualifiedCapture.orElseThrow().rulesRevision())
                        : OptionalLong.empty());
        return new Unavailable(Reason.VISIBILITY_UNRESOLVED, Optional.of(fragment),
                "closest open path is blocked or tangent; other points on this fragment are not disproved",
                Optional.empty(), Optional.of(diagnostic));
    }

    /** Exact rational slab parameters avoid dividing/rounding tiny gaps into endpoint contacts. */
    private record Fraction(BigDecimal numerator, BigDecimal denominator) implements Comparable<Fraction> {
        private static final Fraction ZERO = new Fraction(BigDecimal.ZERO, BigDecimal.ONE);
        private static final Fraction ONE = new Fraction(BigDecimal.ONE, BigDecimal.ONE);

        private Fraction {
            if (denominator.signum() == 0) throw new IllegalArgumentException("zero path denominator");
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
        }

        @Override
        public int compareTo(Fraction other) {
            return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
        }
    }

    private static final class Path {
        private final BigDecimal[] start = new BigDecimal[3];
        private final BigDecimal[] delta = new BigDecimal[3];
        private final BigDecimal distanceSquared;
        private final BigDecimal radiusSquared;

        private Path(Proximity proximity) {
            BigDecimal square = BigDecimal.ZERO;
            for (int axis = 0; axis < 3; axis++) {
                start[axis] = exact(component(proximity.sourceAnchor(), axis));
                delta[axis] = exact(component(proximity.surfaceAnchor(), axis)).subtract(start[axis]);
                square = square.add(delta[axis].multiply(delta[axis]));
            }
            distanceSquared = square;
            BigDecimal radius = exact(proximity.source().radius());
            radiusSquared = radius.multiply(radius);
        }

        private boolean blockedBy(Aabb box) {
            if (distanceSquared.compareTo(radiusSquared) <= 0) return false;
            Fraction enter = Fraction.ZERO;
            Fraction exit = Fraction.ONE;
            for (int axis = 0; axis < 3; axis++) {
                BigDecimal low = exact(component(box.min(), axis)).subtract(start[axis]);
                BigDecimal high = exact(component(box.max(), axis)).subtract(start[axis]);
                if (delta[axis].signum() == 0) {
                    if (low.signum() > 0 || high.signum() < 0) return false;
                    continue;
                }
                Fraction first = new Fraction(low, delta[axis]);
                Fraction last = new Fraction(high, delta[axis]);
                if (first.compareTo(last) > 0) {
                    Fraction swap = first;
                    first = last;
                    last = swap;
                }
                if (first.compareTo(enter) > 0) enter = first;
                if (last.compareTo(exit) < 0) exit = last;
                if (enter.compareTo(exit) > 0) return false;
            }
            if (exit.compareTo(Fraction.ZERO) <= 0 || enter.compareTo(Fraction.ONE) >= 0) return false;
            // Includes singleton tangencies. Equality is still inside the excluded
            // sensor prefix; strict greater proves a point after its true ball surface.
            return exit.numerator.multiply(exit.numerator).multiply(distanceSquared)
                    .compareTo(radiusSquared.multiply(exit.denominator).multiply(exit.denominator)) > 0;
        }
    }

    private static BigDecimal exact(double value) { return new BigDecimal(value); }
    private static double component(Vec3d value, int axis) {
        return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
    }
}
