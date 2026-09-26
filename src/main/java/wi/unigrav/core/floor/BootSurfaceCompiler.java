package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.player.BootSurfaceGeometry.FaceRectangle;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/**
 * Finite exterior rectangles of the supplied raw collision-box union (metres).
 *
 * <p>This is a geometry compiler, not a world sampler or an attachment decision.
 * Exterior means relative to these observations only. A caller must retain their
 * capture/source scope, establish world coverage, and separately resolve range,
 * visibility and fit before using a fragment as a boot target. The supplied
 * qualification records an already-selected rule/tier; this compiler neither
 * validates equipment/NBT nor chooses how floor and boot profiles combine.
 * Nonmagnetic solids remove internal faces just like magnetic solids.
 */
public final class BootSurfaceCompiler {
    private BootSurfaceCompiler() {
    }

    /** Explicit engineering work limits; these do not choose equipment tier defaults. */
    public record Limits(int maxPrimitives, int maxRawFaces, int maxCutsPerAxis,
                         int maxTiles, int maxCoverageChecks, int maxFragments) {
        public Limits {
            if (maxPrimitives < 1 || maxPrimitives > 4096 || maxRawFaces < 1 || maxRawFaces > 24576
                    || maxCutsPerAxis < 2 || maxCutsPerAxis > 512 || maxTiles < 1 || maxTiles > 1_048_576
                    || maxCoverageChecks < 1 || maxCoverageChecks > 33_554_432
                    || maxFragments < 1 || maxFragments > 16384) {
                throw new IllegalArgumentException("surface compiler limits exceed the finite work profile");
            }
        }
    }

    public enum Reason {
        PRIMITIVE_LIMIT, RAW_FACE_LIMIT, CUT_LIMIT, TILE_LIMIT, COVERAGE_LIMIT, FRAGMENT_LIMIT,
        DEGENERATE_COLLIDER, MATERIAL_UNAVAILABLE
    }

    public sealed interface Compilation permits SurfaceSet, Unavailable {
    }

    /** No partial surfaces escape after a budget or unsupported-geometry failure. */
    public record Unavailable(Reason reason, String detail) implements Compilation {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** An explicit owner-selected effective rule/tier, not inferred from a material boolean. */
    public record Qualification(String ruleId, BootTier effectiveTier) {
        public Qualification {
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(effectiveTier, "effectiveTier");
            if (ruleId.isBlank()) throw new IllegalArgumentException("surface qualification needs a rule ID");
        }
    }

    /**
     * Closed exterior patch with exact callback provenance, not a persistent boot-memory key.
     * Contributors cover its whole relative interior. A material/tier owner may later combine
     * compatible adjacent fragments; this compiler never erases a contributor transition.
     */
    public record Fragment(FaceRectangle geometry, Qualification qualification, List<Primitive> contributors) {
        public Fragment {
            Objects.requireNonNull(geometry, "geometry");
            Objects.requireNonNull(qualification, "qualification");
            contributors = List.copyOf(contributors);
            if (contributors.isEmpty()) throw new IllegalArgumentException("exterior fragment needs a source");
        }
    }

    public static final class SurfaceSet implements Compilation {
        private final List<Primitive> observations;
        private final List<Fragment> fragments;

        private SurfaceSet(List<Primitive> observations, List<Fragment> fragments) {
            this.observations = List.copyOf(observations);
            this.fragments = List.copyOf(fragments);
        }

        public List<Primitive> observations() { return observations; }
        public List<Fragment> fragments() { return fragments; }
    }

    private static final Comparator<Primitive> SOURCE_ORDER = Comparator
            .comparingInt((Primitive p) -> p.cell().getX()).thenComparingInt(p -> p.cell().getY())
            .thenComparingInt(p -> p.cell().getZ()).thenComparingInt(Primitive::ordinal);
    private static final Comparator<Qualification> QUALIFICATION_ORDER = Comparator.comparing(Qualification::ruleId)
            .thenComparingDouble(q -> q.effectiveTier().profile().strength())
            .thenComparingDouble(q -> q.effectiveTier().profile().plateau())
            .thenComparingDouble(q -> q.effectiveTier().profile().range())
            .thenComparingDouble(q -> q.effectiveTier().captureReachMetres())
            .thenComparingLong(q -> q.effectiveTier().jumpProtectionTicks());
    private static final Comparator<Plane> PLANE_ORDER = Comparator
            .comparingInt((Plane p) -> p.outward().ordinal()).thenComparingDouble(Plane::coordinate)
            .thenComparing(Plane::qualification, QUALIFICATION_ORDER);

    public static Compilation compile(List<Primitive> observations, Map<Primitive, Qualification> qualifications, Limits limits) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(qualifications, "qualifications");
        Objects.requireNonNull(limits, "limits");
        if (observations.size() > limits.maxPrimitives()) return unavailable(Reason.PRIMITIVE_LIMIT);
        if (qualifications.size() > observations.size()) {
            throw new IllegalArgumentException("surface qualification without an observed primitive");
        }
        Map<Primitive, Qualification> ownedQualifications = Map.copyOf(qualifications);
        List<Primitive> ordered = new ArrayList<>(List.copyOf(observations));
        ordered.sort(SOURCE_ORDER);
        int rawFaces = 0;
        boolean degenerate = false;
        for (int index = 0; index < ordered.size(); index++) {
            Primitive current = ordered.get(index);
            if (index > 0 && SOURCE_ORDER.compare(ordered.get(index - 1), current) == 0) {
                throw new IllegalArgumentException("duplicate cell/callback ordinal in surface observations");
            }
            Aabb box = current.rawBounds();
            for (int axis = 0; axis < 3; axis++) {
                if (component(box.min(), axis) >= component(box.max(), axis)) {
                    degenerate = true;
                }
            }
        }
        Set<Primitive> observed = Set.copyOf(ordered);
        for (Primitive qualified : ownedQualifications.keySet()) {
            if (!observed.contains(qualified) || !qualified.magnetic()) {
                throw new IllegalArgumentException("surface qualification requires an observed magnetic primitive");
            }
        }
        if (degenerate) return unavailable(Reason.DEGENERATE_COLLIDER);
        for (Primitive current : ordered) {
            if (current.magnetic()) {
                if (!ownedQualifications.containsKey(current)) return unavailable(Reason.MATERIAL_UNAVAILABLE);
                rawFaces += 6;
            }
        }
        if (rawFaces > limits.maxRawFaces()) return unavailable(Reason.RAW_FACE_LIMIT);

        Map<Plane, List<Candidate>> groups = new TreeMap<>(PLANE_ORDER);
        for (Primitive primitive : ordered) {
            if (!primitive.magnetic()) continue;
            for (CardinalFrame outward : CardinalFrame.values()) {
                int axis = axis(outward);
                double plane = component(positive(outward) ? primitive.rawBounds().max() : primitive.rawBounds().min(), axis);
                groups.computeIfAbsent(new Plane(outward, plane, ownedQualifications.get(primitive)), ignored -> new ArrayList<>())
                        .add(new Candidate(project(primitive.rawBounds(), axis), primitive));
            }
        }
        Budget budget = new Budget(limits);
        List<Fragment> fragments = new ArrayList<>();
        try {
            for (Map.Entry<Plane, List<Candidate>> group : groups.entrySet()) {
                fragments.addAll(compilePlane(group.getKey(), group.getValue(), ordered, budget));
            }
            return new SurfaceSet(ordered, fragments);
        } catch (LimitFailure failure) {
            return unavailable(failure.reason);
        }
    }

    private static List<Fragment> compilePlane(Plane plane, List<Candidate> candidates,
                                               List<Primitive> solids, Budget budget) {
        TreeSet<Double> uCuts = new TreeSet<>();
        TreeSet<Double> vCuts = new TreeSet<>();
        for (Candidate candidate : candidates) addCuts(candidate.rectangle(), uCuts, vCuts, budget);
        Rect domain = new Rect(uCuts.first(), uCuts.last(), vCuts.first(), vCuts.last());
        List<Rect> blockers = new ArrayList<>();
        int axis = axis(plane.outward());
        for (Primitive solid : solids) {
            budget.coverage();
            Aabb box = solid.rawBounds();
            double low = component(box.min(), axis);
            double high = component(box.max(), axis);
            boolean occupiesOutside = positive(plane.outward()) ? low <= plane.coordinate() && high > plane.coordinate()
                    : low < plane.coordinate() && high >= plane.coordinate();
            if (!occupiesOutside) continue;
            Rect clipped = intersection(project(box, axis), domain);
            if (clipped != null) {
                blockers.add(clipped);
                addCuts(clipped, uCuts, vCuts, budget);
            }
        }
        List<Double> us = List.copyOf(uCuts);
        List<Double> vs = List.copyOf(vCuts);
        budget.tiles((long) (us.size() - 1) * (vs.size() - 1));
        List<Patch> patches = new ArrayList<>();
        Map<Span, Patch> previousRow = Map.of();
        for (int u = 0; u + 1 < us.size(); u++) {
            Map<Span, Patch> row = new HashMap<>();
            List<Primitive> runOwners = List.of();
            double runStart = vs.getFirst();
            for (int v = 0; v + 1 < vs.size(); v++) {
                Rect tile = new Rect(us.get(u), us.get(u + 1), vs.get(v), vs.get(v + 1));
                List<Primitive> owners = owners(tile, candidates, blockers, budget);
                if (!owners.equals(runOwners)) {
                    if (!runOwners.isEmpty()) finishRun(us.get(u), us.get(u + 1), runStart, vs.get(v),
                            runOwners, previousRow, row, patches, budget);
                    runOwners = owners;
                    runStart = vs.get(v);
                }
            }
            if (!runOwners.isEmpty()) finishRun(us.get(u), us.get(u + 1), runStart, vs.getLast(),
                    runOwners, previousRow, row, patches, budget);
            previousRow = row;
        }
        List<Fragment> fragments = new ArrayList<>(patches.size());
        for (Patch patch : patches) fragments.add(new Fragment(rectangle(plane, patch.bounds()), plane.qualification(), patch.owners));
        return fragments;
    }

    private static List<Primitive> owners(Rect tile, List<Candidate> candidates, List<Rect> blockers, Budget budget) {
        for (Rect blocker : blockers) {
            budget.coverage();
            if (contains(blocker, tile)) return List.of();
        }
        List<Primitive> owners = new ArrayList<>();
        for (Candidate candidate : candidates) {
            budget.coverage();
            if (contains(candidate.rectangle(), tile)) owners.add(candidate.primitive());
        }
        return List.copyOf(owners);
    }

    private static void finishRun(double u0, double u1, double v0, double v1, List<Primitive> owners,
                                  Map<Span, Patch> previous, Map<Span, Patch> row,
                                  List<Patch> patches, Budget budget) {
        Span span = new Span(v0, v1, owners);
        Patch patch = previous.get(span);
        if (patch == null || patch.u1 != u0) {
            budget.fragment();
            patch = new Patch(u0, u1, v0, v1, owners);
            patches.add(patch);
        } else {
            patch.u1 = u1;
        }
        row.put(span, patch);
    }

    private static void addCuts(Rect rectangle, TreeSet<Double> us, TreeSet<Double> vs, Budget budget) {
        us.add(rectangle.u0());
        us.add(rectangle.u1());
        vs.add(rectangle.v0());
        vs.add(rectangle.v1());
        if (us.size() > budget.limits.maxCutsPerAxis() || vs.size() > budget.limits.maxCutsPerAxis()) {
            throw new LimitFailure(Reason.CUT_LIMIT);
        }
    }

    /** Endpoint containment avoids midpoint overflow and adjacent-double midpoint collapse. */
    private static boolean contains(Rect outer, Rect inner) {
        return outer.u0() <= inner.u0() && outer.u1() >= inner.u1()
                && outer.v0() <= inner.v0() && outer.v1() >= inner.v1();
    }

    private static Rect intersection(Rect a, Rect b) {
        double u0 = Math.max(a.u0(), b.u0());
        double u1 = Math.min(a.u1(), b.u1());
        double v0 = Math.max(a.v0(), b.v0());
        double v1 = Math.min(a.v1(), b.v1());
        return u0 < u1 && v0 < v1 ? new Rect(u0, u1, v0, v1) : null;
    }

    private static Rect project(Aabb box, int axis) {
        int u = (axis + 1) % 3;
        int v = (axis + 2) % 3;
        return new Rect(component(box.min(), u), component(box.max(), u), component(box.min(), v), component(box.max(), v));
    }

    private static FaceRectangle rectangle(Plane plane, Rect bounds) {
        int axis = axis(plane.outward());
        double[] low = new double[3];
        double[] high = new double[3];
        low[axis] = high[axis] = plane.coordinate();
        low[(axis + 1) % 3] = bounds.u0();
        high[(axis + 1) % 3] = bounds.u1();
        low[(axis + 2) % 3] = bounds.v0();
        high[(axis + 2) % 3] = bounds.v1();
        return new FaceRectangle(new Aabb(new Vec3d(low[0], low[1], low[2]), new Vec3d(high[0], high[1], high[2])), plane.outward());
    }

    private static int axis(CardinalFrame outward) {
        return switch (outward) {
            case X_POS, X_NEG -> 0;
            case Y_POS, Y_NEG -> 1;
            case Z_POS, Z_NEG -> 2;
        };
    }

    private static boolean positive(CardinalFrame outward) {
        return outward == CardinalFrame.X_POS || outward == CardinalFrame.Y_POS || outward == CardinalFrame.Z_POS;
    }

    private static double component(Vec3d value, int axis) {
        double coordinate = axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
        return coordinate == 0 ? 0 : coordinate;
    }

    private static Unavailable unavailable(Reason reason) {
        return new Unavailable(reason, "surface compilation is incomplete: " + reason);
    }

    private record Plane(CardinalFrame outward, double coordinate, Qualification qualification) {
    }

    private record Rect(double u0, double u1, double v0, double v1) {
    }

    private record Candidate(Rect rectangle, Primitive primitive) {
    }

    private record Span(double v0, double v1, List<Primitive> owners) {
    }

    private static final class Patch {
        private final double u0;
        private double u1;
        private final double v0;
        private final double v1;
        private final List<Primitive> owners;

        private Patch(double u0, double u1, double v0, double v1, List<Primitive> owners) {
            this.u0 = u0;
            this.u1 = u1;
            this.v0 = v0;
            this.v1 = v1;
            this.owners = owners;
        }

        private Rect bounds() { return new Rect(u0, u1, v0, v1); }
    }

    private static final class Budget {
        private final Limits limits;
        private long tiles;
        private long checks;
        private int fragments;

        private Budget(Limits limits) { this.limits = limits; }
        private void tiles(long count) {
            tiles += count;
            if (tiles > limits.maxTiles()) throw new LimitFailure(Reason.TILE_LIMIT);
        }
        private void coverage() {
            if (++checks > limits.maxCoverageChecks()) throw new LimitFailure(Reason.COVERAGE_LIMIT);
        }
        private void fragment() {
            if (++fragments > limits.maxFragments()) throw new LimitFailure(Reason.FRAGMENT_LIMIT);
        }
    }

    private static final class LimitFailure extends RuntimeException {
        private final Reason reason;
        private LimitFailure(Reason reason) { this.reason = reason; }
    }
}
