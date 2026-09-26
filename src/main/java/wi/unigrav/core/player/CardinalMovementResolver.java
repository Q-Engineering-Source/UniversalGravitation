package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Native ordered AABB movement in a cardinal physical frame, in metres.
 *
 * <p>This is the normal player SELF/PLAYER collision path, after web scaling and
 * before sneak reduction. The caller must already have excluded noClip and
 * kinematic owners; piston displacement requires its native tick clamp first.
 * This module does not sample constraints, integrate force, install an entity
 * box, run block/fall callbacks, authorize movement, or grant standing ability.
 * In particular an AABB retained pending a capsule transition does not authorize
 * the caller to enable walking, sneak protection, or stepping.
 *
 * <p>Queries and returned colliders are in WORLD space. The query provider must
 * preserve native aggregate semantics and reject unavailable/partial worlds;
 * it may throw with the original cause. No fake World or transformed Entity is
 * needed. Its side effects cannot be rolled back by this pure movement result.
 * Algorithm ordering follows Entity.move in the pinned mapped Cleanroom JAR;
 * see docs/progress/ug-chb/native-cardinal-movement-facts-20260912.md.
 */
public final class CardinalMovementResolver {
    private CardinalMovementResolver() {}

    public record Request(CardinalFrame frame, AxisAlignedBB worldBounds, Vec3d worldDelta,
                          MoverType moverType, boolean onGround, boolean sneaking, double stepHeight) {
        public Request {
            Objects.requireNonNull(frame, "frame");
            requireBox(worldBounds);
            requireVector(worldDelta);
            if (moverType != MoverType.SELF && moverType != MoverType.PLAYER) {
                throw new IllegalArgumentException("normal player collision requires SELF or PLAYER owner");
            }
            if (!Double.isFinite(stepHeight) || stepHeight < 0.0) {
                throw new IllegalArgumentException("stepHeight must be finite and nonnegative");
            }
            if (!(worldBounds.maxX > worldBounds.minX && worldBounds.maxY > worldBounds.minY
                    && worldBounds.maxZ > worldBounds.minZ)) {
                throw new IllegalArgumentException("player AABB must have positive dimensions");
            }
        }
    }

    /** Total observations include repeated boxes in different native queries. */
    public record Limits(int maxQueries, int maxCandidateObservations, int maxOffsetTests) {
        public Limits {
            if (maxQueries < 1 || maxQueries > 8192 || maxCandidateObservations < 1
                    || maxCandidateObservations > 65536 || maxOffsetTests < 1 || maxOffsetTests > 1_000_000) {
                throw new IllegalArgumentException("invalid cardinal work limits");
            }
        }
    }

    public record Work(int queries, int candidateObservations, int offsetTests) {}

    /** Native local-axis flags, not a substitute for separately observed support. */
    public record CollisionFlags(boolean x, boolean y, boolean z, boolean onGround) {}

    public enum StepChoice { NONE, A, B, ORDINARY_FALLBACK }
    public enum Failure { QUERY_LIMIT, CANDIDATE_LIMIT, OFFSET_LIMIT, QUERY_FAILED, INVALID_COLLIDER, NUMERICAL_RANGE }

    public record QueryObservation(AxisAlignedBB worldBounds, List<AxisAlignedBB> worldCandidates) {
        public QueryObservation {
            requireBox(worldBounds);
            worldCandidates = List.copyOf(worldCandidates);
        }
    }

    public sealed interface Result permits Resolved, Rejected {
        AxisAlignedBB endBounds();
        Work work();
        List<QueryObservation> queries();
    }

    /** A collision calculation, never a world/physical commit receipt. */
    public static final class Resolved implements Result {
        private final Request request;
        private final AxisAlignedBB endBounds;
        private final Vec3d postSneakLocalDelta;
        private final Vec3d nativeResolvedLocalDelta;
        private final Vec3d ordinaryResolvedLocalDelta;
        private final double attemptedStepRise;
        private final double attemptedStepDown;
        private final Vec3d actualLocalBoundsDelta;
        private final Vec3d actualWorldBoundsDelta;
        private final CollisionFlags flags;
        private final StepChoice step;
        private final Work work;
        private final List<QueryObservation> queries;

        private Resolved(Request request, AxisAlignedBB endBounds, Vec3d postSneakLocalDelta, Vec3d nativeResolvedLocalDelta,
                         Vec3d ordinaryResolvedLocalDelta, double attemptedStepRise, double attemptedStepDown,
                         Vec3d actualLocalBoundsDelta, Vec3d actualWorldBoundsDelta, CollisionFlags flags,
                         StepChoice step, Work work, List<QueryObservation> queries) {
            this.request = request;
            this.endBounds = endBounds;
            this.postSneakLocalDelta = postSneakLocalDelta;
            this.nativeResolvedLocalDelta = nativeResolvedLocalDelta;
            this.ordinaryResolvedLocalDelta = ordinaryResolvedLocalDelta;
            this.attemptedStepRise = attemptedStepRise;
            this.attemptedStepDown = attemptedStepDown;
            this.actualLocalBoundsDelta = actualLocalBoundsDelta;
            this.actualWorldBoundsDelta = actualWorldBoundsDelta;
            this.flags = flags;
            this.step = step;
            this.work = work;
            this.queries = List.copyOf(queries);
        }

        @Override public AxisAlignedBB endBounds() { return endBounds; }
        public Request request() { return request; }
        public Vec3d postSneakLocalDelta() { return postSneakLocalDelta; }
        /** For a chosen step, Y is the final downward leg, NOT net displacement. */
        public Vec3d nativeResolvedLocalDelta() { return nativeResolvedLocalDelta; }
        public Vec3d ordinaryResolvedLocalDelta() { return ordinaryResolvedLocalDelta; }
        /** Attempted alternate, even if ordinary ultimately wins; zero when no step is attempted. */
        public double attemptedStepRise() { return attemptedStepRise; }
        public double attemptedStepDown() { return attemptedStepDown; }
        /** Actual min-corner differences; binary64 boxes must not be rebuilt from a rounded centre. */
        public Vec3d actualLocalBoundsDelta() { return actualLocalBoundsDelta; }
        public Vec3d actualWorldBoundsDelta() { return actualWorldBoundsDelta; }
        public CollisionFlags flags() { return flags; }
        public StepChoice step() { return step; }
        @Override public Work work() { return work; }
        @Override public List<QueryObservation> queries() { return queries; }
    }

    public record Rejected(AxisAlignedBB endBounds, Failure failure, RuntimeException cause,
                           Work work, List<QueryObservation> queries) implements Result {
        public Rejected {
            requireBox(endBounds);
            Objects.requireNonNull(failure, "failure");
            queries = List.copyOf(queries);
        }
    }

    public static Result resolve(Request request, Function<AxisAlignedBB, List<AxisAlignedBB>> worldQuery,
                                 Limits limits) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(worldQuery, "worldQuery");
        Objects.requireNonNull(limits, "limits");
        var context = new QueryContext(request.frame(), worldQuery, limits);
        try {
            return calculate(request, context);
        } catch (QueryFailure failure) {
            return new Rejected(request.worldBounds(), failure.reason, failure.original,
                    context.work(), context.observations);
        }
    }

    private static Resolved calculate(Request request, QueryContext context) {
        AxisAlignedBB start = transform(request.worldBounds(), request.frame()::toLocal);
        Vec3d requested = request.frame().toLocal(request.worldDelta());
        double x = requested.x();
        double y = requested.y();
        double z = requested.z();
        if (request.onGround() && request.sneaking()) {
            while (x != 0.0 && context.query(checked(start.offset(x, -request.stepHeight(), 0))).isEmpty()) {
                x = sneakReduction(x);
            }
            while (z != 0.0 && context.query(checked(start.offset(0, -request.stepHeight(), z))).isEmpty()) {
                z = sneakReduction(z);
            }
            while (x != 0.0 && z != 0.0
                    && context.query(checked(start.offset(x, -request.stepHeight(), z))).isEmpty()) {
                x = sneakReduction(x);
                z = sneakReduction(z);
            }
        }
        Vec3d saved = new Vec3d(x, y, z);
        List<AxisAlignedBB> ordinaryCandidates = context.query(checked(start.expand(x, y, z)));
        AxisAlignedBB box = start;
        if (y != 0.0) {
            y = context.clip(1, ordinaryCandidates, box, y);
            box = checked(box.offset(0, y, 0));
        }
        if (x != 0.0) {
            x = context.clip(0, ordinaryCandidates, box, x);
            if (x != 0.0) box = checked(box.offset(x, 0, 0));
        }
        if (z != 0.0) {
            z = context.clip(2, ordinaryCandidates, box, z);
            if (z != 0.0) box = checked(box.offset(0, 0, z));
        }
        StepChoice choice = StepChoice.NONE;
        Vec3d ordinaryDelta = new Vec3d(x, y, z);
        double attemptedRise = 0.0;
        double attemptedDown = 0.0;
        boolean mayStep = request.onGround() || (saved.y() != y && saved.y() < 0.0);
        if (request.stepHeight() > 0.0 && mayStep && (saved.x() != x || saved.z() != z)) {
            AxisAlignedBB ordinaryBox = box;
            List<AxisAlignedBB> stepCandidates = context.query(
                    checked(start.expand(saved.x(), request.stepHeight(), saved.z())));
            double riseA = context.clip(1, stepCandidates,
                    checked(start.expand(saved.x(), 0, saved.z())), request.stepHeight());
            StepPath a = horizontal(context, stepCandidates, start, riseA, saved.x(), saved.z());
            double riseB = context.clip(1, stepCandidates, start, request.stepHeight());
            StepPath b = horizontal(context, stepCandidates, start, riseB, saved.x(), saved.z());
            StepPath selected;
            if (horizontalSquared(a.x(), a.z()) > horizontalSquared(b.x(), b.z())) {
                selected = a;
                choice = StepChoice.A;
            } else {
                selected = b;
                choice = StepChoice.B;
            }
            x = selected.x();
            z = selected.z();
            y = context.clip(1, stepCandidates, selected.box(), -selected.rise());
            attemptedRise = selected.rise();
            attemptedDown = y;
            box = checked(selected.box().offset(0, y, 0));
            if (horizontalSquared(ordinaryDelta.x(), ordinaryDelta.z()) >= horizontalSquared(x, z)) {
                box = ordinaryBox;
                x = ordinaryDelta.x();
                y = ordinaryDelta.y();
                z = ordinaryDelta.z();
                choice = StepChoice.ORDINARY_FALLBACK;
            }
        }
        AxisAlignedBB worldEnd = transform(box, request.frame()::toWorld);
        boolean blockedY = saved.y() != y;
        return new Resolved(request, worldEnd, saved, new Vec3d(x, y, z), ordinaryDelta, attemptedRise, attemptedDown,
                difference(box, start),
                difference(worldEnd, request.worldBounds()),
                new CollisionFlags(saved.x() != x, blockedY, saved.z() != z, blockedY && saved.y() < 0.0),
                choice, context.work(), context.observations);
    }

    private record StepPath(AxisAlignedBB box, double x, double z, double rise) {}

    private static StepPath horizontal(QueryContext context, List<AxisAlignedBB> candidates,
                                       AxisAlignedBB start, double rise, double requestedX, double requestedZ) {
        AxisAlignedBB box = checked(start.offset(0, rise, 0));
        double x = context.clip(0, candidates, box, requestedX);
        box = checked(box.offset(x, 0, 0));
        double z = context.clip(2, candidates, box, requestedZ);
        return new StepPath(checked(box.offset(0, 0, z)), x, z, rise);
    }

    private static double sneakReduction(double value) {
        if (value < 0.05 && value >= -0.05) return 0.0;
        return value > 0.0 ? value - 0.05 : value + 0.05;
    }

    private static double horizontalSquared(double x, double z) {
        double value = x * x + z * z;
        if (!Double.isFinite(value)) throw new QueryFailure(Failure.NUMERICAL_RANGE, null);
        return value;
    }

    private static Vec3d difference(AxisAlignedBB end, AxisAlignedBB start) {
        Vec3d delta = new Vec3d(end.minX - start.minX, end.minY - start.minY, end.minZ - start.minZ);
        if (!Double.isFinite(delta.x()) || !Double.isFinite(delta.y()) || !Double.isFinite(delta.z())) {
            throw new QueryFailure(Failure.NUMERICAL_RANGE, null);
        }
        return delta;
    }

    private static AxisAlignedBB transform(AxisAlignedBB box, Function<Vec3d, Vec3d> mapping) {
        Vec3d first = mapping.apply(new Vec3d(box.minX, box.minY, box.minZ));
        Vec3d second = mapping.apply(new Vec3d(box.maxX, box.maxY, box.maxZ));
        return checked(new AxisAlignedBB(first.x(), first.y(), first.z(), second.x(), second.y(), second.z()));
    }

    private static AxisAlignedBB checked(AxisAlignedBB box) {
        try {
            requireBox(box);
            return box;
        } catch (IllegalArgumentException failure) {
            throw new QueryFailure(Failure.NUMERICAL_RANGE, failure);
        }
    }

    private static void requireBox(AxisAlignedBB box) {
        Objects.requireNonNull(box, "box");
        if (!Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
                || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)) {
            throw new IllegalArgumentException("box coordinates must be finite");
        }
    }

    private static void requireVector(Vec3d value) {
        Objects.requireNonNull(value, "worldDelta");
        if (!Double.isFinite(value.x()) || !Double.isFinite(value.y()) || !Double.isFinite(value.z())) {
            throw new IllegalArgumentException("worldDelta must be finite");
        }
    }

    private static final class QueryFailure extends RuntimeException {
        private final Failure reason;
        private final RuntimeException original;

        private QueryFailure(Failure reason, RuntimeException original) {
            super(reason.name(), original);
            this.reason = reason;
            this.original = original;
        }
    }

    private static final class QueryContext {
        private final CardinalFrame frame;
        private final Function<AxisAlignedBB, List<AxisAlignedBB>> source;
        private final Limits limits;
        private final List<QueryObservation> observations = new ArrayList<>();
        private int queryCount;
        private int candidateCount;
        private int offsetCount;

        private QueryContext(CardinalFrame frame, Function<AxisAlignedBB, List<AxisAlignedBB>> source, Limits limits) {
            this.frame = frame;
            this.source = source;
            this.limits = limits;
        }

        private Work work() { return new Work(queryCount, candidateCount, offsetCount); }

        private List<AxisAlignedBB> query(AxisAlignedBB localBounds) {
            if (queryCount == limits.maxQueries()) throw new QueryFailure(Failure.QUERY_LIMIT, null);
            AxisAlignedBB worldBounds = transform(localBounds, frame::toWorld);
            queryCount++;
            List<AxisAlignedBB> raw;
            try {
                raw = source.apply(worldBounds);
            } catch (RuntimeException failure) {
                throw new QueryFailure(Failure.QUERY_FAILED, failure);
            }
            if (raw == null) throw new QueryFailure(Failure.INVALID_COLLIDER, new NullPointerException("collision list"));
            int count;
            try {
                count = raw.size();
            } catch (RuntimeException failure) {
                throw new QueryFailure(Failure.INVALID_COLLIDER, failure);
            }
            if (count < 0 || count > limits.maxCandidateObservations() - candidateCount) {
                throw new QueryFailure(Failure.CANDIDATE_LIMIT, null);
            }
            candidateCount += count;
            List<AxisAlignedBB> world = new ArrayList<>(count);
            List<AxisAlignedBB> local = new ArrayList<>(count);
            try {
                for (int index = 0; index < count; index++) {
                    AxisAlignedBB collider = raw.get(index);
                    requireBox(collider);
                    world.add(collider);
                    local.add(transform(collider, frame::toLocal));
                }
            } catch (RuntimeException failure) {
                throw new QueryFailure(Failure.INVALID_COLLIDER, failure);
            }
            observations.add(new QueryObservation(worldBounds, world));
            return local;
        }

        private double clip(int axis, List<AxisAlignedBB> candidates, AxisAlignedBB box, double delta) {
            for (AxisAlignedBB collider : candidates) {
                if (offsetCount == limits.maxOffsetTests()) throw new QueryFailure(Failure.OFFSET_LIMIT, null);
                offsetCount++;
                delta = switch (axis) {
                    case 0 -> collider.calculateXOffset(box, delta);
                    case 1 -> collider.calculateYOffset(box, delta);
                    case 2 -> collider.calculateZOffset(box, delta);
                    default -> throw new AssertionError("unknown local axis");
                };
            }
            return delta;
        }
    }
}
