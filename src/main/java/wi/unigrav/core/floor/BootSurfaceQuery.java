package wi.unigrav.core.floor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.floor.BootSurfaceCompiler.Fragment;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceCompiler.SurfaceSet;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedSnapshot;
import wi.unigrav.core.floor.BootWorldSurfaces.Snapshot;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.BootSurfaceGeometry.Proximity;
import wi.unigrav.core.player.BootSurfaceGeometry.Source;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/**
 * Bounded finite-surface range and closest-witness visibility queries (metres).
 *
 * <p>A clear witness proves only that witness's open path through the supplied
 * raw boxes. A blocked closest path does not prove the whole fragment hidden:
 * it makes the complete query unavailable, even when another candidate exists.
 * Closed-box tangencies inside the path are conservatively unresolved. Only
 * the start/end contacts and a lower-ball sensor's own radial prefix are omitted.
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

    public record Unavailable(Reason reason, Optional<Fragment> fragment, String detail,
                              Optional<ArithmeticException> cause) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(fragment, "fragment");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
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

        private Measurements(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
                             Optional<QualifiedSnapshot> qualifiedCapture, List<Candidate> candidates) {
            this.source = source;
            this.surfaces = surfaces;
            this.capture = capture;
            this.qualifiedCapture = qualifiedCapture;
            this.candidates = List.copyOf(candidates);
        }

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
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(compilerLimits, "compilerLimits");
        Objects.requireNonNull(queryLimits, "queryLimits");
        Map<String, BootTier> profiles = Map.copyOf(effectiveTiers);
        Map<Primitive, Qualification> qualifications = new HashMap<>();
        for (var entry : capture.materialMatches().entrySet()) {
            String selected = entry.getValue().selectionId();
            BootTier tier = profiles.get(selected);
            if (tier == null) throw new IllegalArgumentException("missing effective boot profile for selected material: " + selected);
            qualifications.put(entry.getKey(), new Qualification(selected, tier));
        }
        // Validate missing observed profiles first: invalid configuration must not
        // be hidden by a range failure. Reach coverage is independent of whether
        // a selector happened to produce any visible fragment in this capture.
        for (BootTier tier : profiles.values()) {
            if (tier.captureReachMetres() > capture.snapshot().footprint().reachMetres()) {
                return failure(Reason.CAPTURE_REACH, null, "active profile reach exceeds the observed producer footprint");
            }
        }
        return evaluateCaptured(capture.snapshot(), qualifications, Optional.of(capture), compilerLimits, queryLimits);
    }

    private static Result evaluateCaptured(Snapshot capture, Map<Primitive, Qualification> qualifications,
                                           Optional<QualifiedSnapshot> qualifiedCapture,
                                           BootSurfaceCompiler.Limits compilerLimits, Limits queryLimits) {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(queryLimits, "queryLimits");
        Map<Primitive, Qualification> ownedQualifications = Map.copyOf(qualifications);
        var compiled = BootSurfaceCompiler.compile(capture.primitives(), ownedQualifications, compilerLimits);
        if (compiled instanceof BootSurfaceCompiler.Unavailable unavailable) {
            return failure(Reason.COMPILATION, null, unavailable.reason() + ": " + unavailable.detail());
        }
        SurfaceSet surfaces = (SurfaceSet) compiled;
        for (Qualification qualification : ownedQualifications.values()) {
            if (qualification.effectiveTier().captureReachMetres() > capture.footprint().reachMetres()) {
                return failure(Reason.CAPTURE_REACH, null, "tier reach exceeds the observed producer footprint");
            }
        }
        return evaluate(capture.footprint().source(), surfaces, Optional.of(capture), qualifiedCapture, queryLimits);
    }

    /**
     * Pure supplied-geometry entry point. It cannot establish capture completeness
     * or bind a surface set to a real world, source scope, or physical revision.
     * The returned Measurements deliberately has no capture provenance.
     */
    public static Result evaluateGeometry(Source source, SurfaceSet surfaces, Limits limits) {
        return evaluate(source, surfaces, Optional.empty(), Optional.empty(), limits);
    }

    private static Result evaluate(Source source, SurfaceSet surfaces, Optional<Snapshot> capture,
                                    Optional<QualifiedSnapshot> qualifiedCapture, Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(surfaces, "surfaces");
        Objects.requireNonNull(limits, "limits");
        if (surfaces.fragments().size() > limits.maxFragments()) {
            return failure(Reason.FRAGMENT_LIMIT, null, "complete surface set exceeds the query fragment limit");
        }
        int boxTests = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Fragment fragment : surfaces.fragments()) {
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
            for (Primitive solid : surfaces.observations()) {
                if (boxTests == limits.maxVisibilityBoxTests()) {
                    return failure(Reason.VISIBILITY_BUDGET, fragment, "complete visibility work exceeds its limit");
                }
                boxTests++;
                if (path.blockedBy(solid.rawBounds())) {
                    return failure(Reason.VISIBILITY_UNRESOLVED, fragment,
                            "closest open path is blocked or tangent; other points on this fragment are not disproved");
                }
            }
            candidates.add(new Candidate(fragment, proximity));
        }
        return new Measurements(source, surfaces, capture, qualifiedCapture, candidates);
    }

    private static BigDecimal maximumSide(Source source, Fragment fragment) {
        Vec3d outward = fragment.geometry().outward().up();
        int axis = outward.x() != 0 ? 0 : outward.y() != 0 ? 1 : 2;
        boolean positive = component(outward, axis) > 0;
        BigDecimal anchor = exact(component(positive ? source.anchorBounds().max() : source.anchorBounds().min(), axis));
        BigDecimal plane = exact(component(fragment.geometry().bounds().min(), axis));
        return (positive ? anchor.subtract(plane) : plane.subtract(anchor)).add(exact(source.radius()));
    }

    private static Unavailable failure(Reason reason, Fragment fragment, String detail) {
        return new Unavailable(reason, Optional.ofNullable(fragment), detail, Optional.empty());
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
