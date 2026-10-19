package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import wi.unigrav.core.floor.BootSurfaceQuery.Candidate;
import wi.unigrav.core.floor.BootSurfaceQuery.Measurements;
import wi.unigrav.core.floor.BootWorldSurfaces.Primitive;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedSnapshot;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CollisionQueryFootprint;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.Surface;
import wi.unigrav.core.player.WorldCollisionCandidates;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

/** Derives same-observation Cardinal support from exact exposed raw collision faces. */
public final class PlayerSupportObservation {
    private static final int MAX_RECTANGLE_WORK = 262_144;
    private static final int MAX_REMAINDER_PATCHES = 8_192;
    private static final CollisionQueryFootprint.Limits NATIVE_SUPPORT_SCAN =
            new CollisionQueryFootprint.Limits(16_384, 16, 4_096, 4_096);
    private static final CardinalMovementResolver.Limits NATIVE_SUPPORT_WORK =
            new CardinalMovementResolver.Limits(512, 16_384, 100_000);
    private static final double NATIVE_SUPPORT_PROBE_METRES = 1.0e-4;

    private PlayerSupportObservation() { }

    public enum Reason { UNSUPPORTED_SHAPE, CAPTURE_MISMATCH, BODY_OVERLAP, SUPPORT_BUDGET, NATIVE_QUERY_FAILED }

    public sealed interface Result permits Observed, Unavailable { }

    public record Observed(Optional<Surface> actualSupport) implements Result {
        public Observed { Objects.requireNonNull(actualSupport, "actualSupport"); }
    }

    public record Unavailable(Reason reason, String detail, Optional<RuntimeException> cause) implements Result {
        public Unavailable {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(cause, "cause");
        }
        public Unavailable(Reason reason, String detail) {
            this(reason, detail, Optional.empty());
        }
    }

    /**
     * A qualified boot face is accepted only when the exact same captured exterior patch
     * positively overlaps the accepted foot rectangle at zero gap. Ordinary colliders use a
     * cell/face value with no boot identity. The optional qualification flag is owner-derived;
     * material alone never makes ordinary support a magnetic-boot support target.
     */
    public static Result observeCardinal(PlayerPhysicalSnapshot accepted, QualifiedSnapshot capture,
                                         Measurements measurements, boolean activeBootQualified) {
        return observeCardinalInternal(accepted, capture, measurements, activeBootQualified, null);
    }

    public static Result observeCardinal(PlayerPhysicalSnapshot accepted, QualifiedSnapshot capture,
            Measurements measurements, boolean activeBootQualified, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return observeCardinalInternal(accepted, capture, measurements, activeBootQualified, work);
    }

    private static Result observeCardinalInternal(PlayerPhysicalSnapshot accepted, QualifiedSnapshot capture,
            Measurements measurements, boolean activeBootQualified, EnvironmentObservationWork work) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(measurements, "measurements");
        if (!measurements.source().physical().equals(accepted)
                || capture.snapshot() != measurements.capture().orElse(null)
                || capture.snapshot() != measurements.qualifiedCapture().map(QualifiedSnapshot::snapshot).orElse(null)
                || capture.snapshot().captureTick() < 0
                || capture.rulesRevision() != measurements.qualifiedCapture().orElseThrow().rulesRevision()) {
            return new Unavailable(Reason.CAPTURE_MISMATCH,
                    "support must use the exact accepted source and qualified capture retained by its query");
        }
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)) {
            return new Unavailable(Reason.UNSUPPORTED_SHAPE,
                    "P3 support observation is defined only for accepted Cardinal geometry");
        }

        BootSurfaceGeometry.Source source = BootSurfaceGeometry.fromAccepted(accepted);
        var foot = source.anchorBounds();
        CardinalFrame outward = cardinal.frame();
        var primitives = capture.snapshot().primitives();
        RectangleBudget budget = new RectangleBudget(work);
        try {
            for (Primitive primitive : primitives) {
                charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
                if (positiveVolumeIntersection(accepted.placement().bounds(), primitive.rawBounds())) {
                    return new Unavailable(Reason.BODY_OVERLAP,
                            "accepted Cardinal body overlaps a captured native collision box at " + primitive.cell());
                }
            }
            List<Candidate> candidates = measurements.candidates();
            if (activeBootQualified) {
                for (Candidate candidate : candidates) {
                    budget.consume();
                    var face = candidate.fragment().geometry();
                    if (face.outward() != outward || !samePlane(face.bounds(), foot, outward)
                            || !positiveIntersection(face.bounds(), foot, outward)
                            || candidate.proximity().gap().lowerMetres() != 0.0
                            || candidate.proximity().gap().upperMetres() != 0.0) continue;
                    // BootSurfaceCompiler already removed faces occluded by any captured raw solid.
                    return new Observed(Optional.of(Surface.fromBootIdentity(
                            BootSurfaceIdentity.from(measurements, candidate))));
                }
            }

            Primitive best = null;
            for (Primitive primitive : primitives) {
                budget.consume();
                Aabb bounds = primitive.rawBounds();
                if (!isSupportFace(bounds, foot, outward)) continue;
                if (hasExposedContact(bounds, foot, outward, primitive, primitives, budget)) {
                    if (best == null || comparePrimitive(primitive, best) < 0) best = primitive;
                }
            }
            if (best == null) return new Observed(Optional.empty());
            var position = best.cell();
            return new Observed(Optional.of(new Surface(position.getX(), position.getY(), position.getZ(), floorFace(outward))));
        } catch (SupportLimitFailure failure) {
            return new Unavailable(Reason.SUPPORT_BUDGET, failure.getMessage(), Optional.of(failure));
        }
    }

    /**
     * Observes ordinary support through the server's native cardinal collision query. This
     * branch is for actors that did not qualify active FEET boots; it deliberately does not
     * inspect magnetic materials or synthesize a boot identity. The tiny inward probe is
     * read-only: exact contact must clip the whole probe to zero and expose a real collider face
     * with positive tangent overlap. A positive gap, a penetrating start, or an unavailable
     * native callback is not support.
     */
    public static Result observeNativeCardinal(WorldServer world, EntityPlayerMP actor,
                                                PlayerPhysicalSnapshot accepted) {
        return observeNativeCardinalInternal(world, actor, accepted, null);
    }

    public static Result observeNativeCardinal(WorldServer world, EntityPlayerMP actor,
            PlayerPhysicalSnapshot accepted, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return observeNativeCardinalInternal(world, actor, accepted, work);
    }

    private static Result observeNativeCardinalInternal(WorldServer world, EntityPlayerMP actor,
            PlayerPhysicalSnapshot accepted, EnvironmentObservationWork work) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        if (!world.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("native Cardinal support observation requires the server thread");
        }
        if (actor.world != world || actor.isDead
                || !(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)) {
            return new Unavailable(Reason.CAPTURE_MISMATCH,
                    "native support requires the live actor's accepted Cardinal geometry");
        }
        Aabb foot = BootSurfaceGeometry.fromAccepted(accepted).anchorBounds();
        AxisAlignedBB body = actor.getEntityBoundingBox();
        var request = new CardinalMovementResolver.Request(cardinal.frame(), body,
                cardinal.frame().toWorld(new Vec3d(0.0, -NATIVE_SUPPORT_PROBE_METRES, 0.0)),
                MoverType.PLAYER, false, false, 0.0);
        WorldCollisionCandidates.CardinalObservation observation = work == null ? WorldCollisionCandidates.resolveCardinal(
                world, actor, accepted, request, NATIVE_SUPPORT_SCAN, NATIVE_SUPPORT_WORK)
                : WorldCollisionCandidates.resolveCardinal(world, actor, accepted, request, NATIVE_SUPPORT_SCAN, NATIVE_SUPPORT_WORK, work);
        if (observation.worldFailure().isPresent()) {
            WorldCollisionCandidates.Unavailable failure = observation.worldFailure().orElseThrow();
            return new Unavailable(failure.reason() == WorldCollisionCandidates.Reason.NATIVE_QUERY_FAILED
                            ? Reason.NATIVE_QUERY_FAILED : Reason.CAPTURE_MISMATCH,
                    "native support query " + failure.reason() + ": " + failure.detail(), failure.cause());
        }
        CardinalMovementResolver.Result calculation = observation.calculation().orElse(null);
        if (!(calculation instanceof CardinalMovementResolver.Resolved resolved)) {
            if (calculation instanceof CardinalMovementResolver.Rejected rejected) {
                RuntimeException cause = rejected.cause() != null ? rejected.cause()
                        : new IllegalStateException("native support calculation rejected: " + rejected.failure());
                return new Unavailable(Reason.CAPTURE_MISMATCH,
                        "native support calculation rejected: " + rejected.failure(), Optional.of(cause));
            }
            return new Unavailable(Reason.CAPTURE_MISMATCH,
                    "native support query returned no movement calculation");
        }

        List<Aabb> rawBoxes = new ArrayList<>();
        for (Snapshot snapshot : observation.snapshots()) {
            charge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            if (snapshot.captureTick() != world.getTotalWorldTime()) {
                return new Unavailable(Reason.CAPTURE_MISMATCH,
                        "native support query crossed a world tick boundary");
            }
            rawBoxes.addAll(snapshot.boxes());
        }
        for (Aabb raw : rawBoxes) {
            charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
            if (positiveVolumeIntersection(accepted.placement().bounds(), raw)) {
                return new Unavailable(Reason.BODY_OVERLAP,
                        "accepted Cardinal body overlaps a native collision box during support observation");
            }
        }

        // Native clipping alone can report a stop when a tiny positive gap is smaller than the
        // probe. Require the resolved delta to be exactly zero and an exact raw face witness.
        if (!resolved.flags().onGround() || resolved.nativeResolvedLocalDelta().y() != 0.0) {
            return new Observed(Optional.empty());
        }
        List<Aabb> contacts = rawBoxes.stream().filter(box -> {
            charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
            return isSupportFace(box, foot, cardinal.frame());
        }).sorted((left, right) -> {
            charge(work, EnvironmentObservationWork.Unit.COMPARISON);
            return compareBounds(left, right);
        }).toList();
        if (contacts.isEmpty()) return new Observed(Optional.empty());
        Aabb contact = contacts.get(0);
        charge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        return new Observed(Optional.of(nativeSurface(contact, foot, cardinal.frame())));
    }

    private static Surface nativeSurface(Aabb collider, Aabb foot, CardinalFrame outward) {
        Rect overlap = intersection(project(collider, outward), project(foot, outward));
        if (overlap == null) throw new IllegalArgumentException("native support contact has no positive tangent area");
        int normal = axis(outward);
        int u = (normal + 1) % 3;
        int v = (normal + 2) % 3;
        double plane = component(foot.min(), normal);
        // Native aggregate callbacks provide a collider AABB, not a source BlockPos.
        // Anchor just inside its support volume so partial shapes (for example a slab
        // face at y=64.5) map to the geometric cell under the contact; this is not
        // provenance for a block, entity, border, or Forge callback source.
        double normalAnchor = positive(outward) ? Math.nextDown(plane) : Math.nextUp(plane);
        int normalCell = floorCell(normalAnchor);
        int uCell = floorCell((overlap.u0 + overlap.u1) * 0.5);
        int vCell = floorCell((overlap.v0 + overlap.v1) * 0.5);
        int[] coordinates = new int[3];
        coordinates[normal] = normalCell;
        coordinates[u] = uCell;
        coordinates[v] = vCell;
        return new Surface(coordinates[0], coordinates[1], coordinates[2], floorFace(outward));
    }

    private static int compareBounds(Aabb left, Aabb right) {
        int compared = Double.compare(left.min().x(), right.min().x());
        if (compared != 0) return compared;
        compared = Double.compare(left.min().y(), right.min().y());
        if (compared != 0) return compared;
        compared = Double.compare(left.min().z(), right.min().z());
        if (compared != 0) return compared;
        compared = Double.compare(left.max().x(), right.max().x());
        if (compared != 0) return compared;
        compared = Double.compare(left.max().y(), right.max().y());
        return compared != 0 ? compared : Double.compare(left.max().z(), right.max().z());
    }

    private static int floorCell(double value) {
        double floored = Math.floor(value);
        if (!Double.isFinite(value) || !Double.isFinite(floored)
                || floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new ArithmeticException("native support coordinate is outside block position range");
        }
        return Math.toIntExact((long) floored);
    }

    private static Face floorFace(CardinalFrame outward) {
        return switch (outward) {
            case Y_POS -> Face.UP;
            case Y_NEG -> Face.DOWN;
            case X_POS -> Face.EAST;
            case X_NEG -> Face.WEST;
            case Z_POS -> Face.SOUTH;
            case Z_NEG -> Face.NORTH;
        };
    }

    private static boolean hasExposedContact(Aabb box, Aabb foot, CardinalFrame outward,
                                             Primitive source, List<Primitive> primitives, RectangleBudget budget) {
        if (!isSupportFace(box, foot, outward)) return false;
        Rect contact = intersection(project(box, outward), project(foot, outward));
        if (contact == null) return false;
        List<Rect> remainder = new ArrayList<>(List.of(contact));
        for (Primitive blocker : primitives) {
            budget.consume();
            if (blocker == source) continue;
            Aabb blocking = blocker.rawBounds();
            if (!occupiesOutside(blocking, box, outward)) continue;
            Rect projection = project(blocking, outward);
            List<Rect> next = new ArrayList<>();
            for (Rect patch : remainder) {
                budget.consume();
                subtract(patch, projection, next);
                if (next.size() > MAX_REMAINDER_PATCHES) {
                    throw new SupportLimitFailure("Cardinal support remainder patches exhausted: used="
                            + next.size() + ", limit=" + MAX_REMAINDER_PATCHES);
                }
            }
            remainder = next;
            if (remainder.isEmpty()) return false;
        }
        return !remainder.isEmpty();
    }

    private static boolean isSupportFace(Aabb box, Aabb foot, CardinalFrame outward) {
        int normal = axis(outward);
        double plane = component(foot.min(), normal);
        if (component(foot.max(), normal) != plane) return false;
        boolean positive = positive(outward);
        double face = component(positive ? box.max() : box.min(), normal);
        double depth = component(positive ? box.min() : box.max(), normal);
        return face == plane && (positive ? depth < plane : depth > plane)
                && positiveIntersection(box, foot, outward);
    }

    private static boolean occupiesOutside(Aabb candidate, Aabb source, CardinalFrame outward) {
        int normal = axis(outward);
        double plane = component(positive(outward) ? source.max() : source.min(), normal);
        double low = component(candidate.min(), normal);
        double high = component(candidate.max(), normal);
        return positive(outward) ? low <= plane && high > plane : low < plane && high >= plane;
    }

    private static boolean samePlane(Aabb left, Aabb right, CardinalFrame outward) {
        int axis = axis(outward);
        return component(left.min(), axis) == component(right.min(), axis)
                && component(left.max(), axis) == component(right.max(), axis);
    }

    private static boolean positiveIntersection(Aabb left, Aabb right, CardinalFrame outward) {
        Rect intersection = intersection(project(left, outward), project(right, outward));
        return intersection != null;
    }

    private static boolean positiveVolumeIntersection(Aabb left, Aabb right) {
        return left.min().x() < right.max().x() && left.max().x() > right.min().x()
                && left.min().y() < right.max().y() && left.max().y() > right.min().y()
                && left.min().z() < right.max().z() && left.max().z() > right.min().z();
    }

    private static Rect project(Aabb box, CardinalFrame outward) {
        int normal = axis(outward);
        int u = (normal + 1) % 3;
        int v = (normal + 2) % 3;
        return new Rect(component(box.min(), u), component(box.max(), u),
                component(box.min(), v), component(box.max(), v));
    }

    private static Rect intersection(Rect left, Rect right) {
        double u0 = Math.max(left.u0, right.u0);
        double u1 = Math.min(left.u1, right.u1);
        double v0 = Math.max(left.v0, right.v0);
        double v1 = Math.min(left.v1, right.v1);
        return u0 < u1 && v0 < v1 ? new Rect(u0, u1, v0, v1) : null;
    }

    private static void subtract(Rect source, Rect blocker, List<Rect> output) {
        Rect overlap = intersection(source, blocker);
        if (overlap == null) {
            output.add(source);
            return;
        }
        add(output, new Rect(source.u0, overlap.u0, source.v0, source.v1));
        add(output, new Rect(overlap.u1, source.u1, source.v0, source.v1));
        add(output, new Rect(overlap.u0, overlap.u1, source.v0, overlap.v0));
        add(output, new Rect(overlap.u0, overlap.u1, overlap.v1, source.v1));
    }

    private static void add(List<Rect> values, Rect value) {
        if (value.u0 < value.u1 && value.v0 < value.v1) values.add(value);
    }

    private static int comparePrimitive(Primitive left, Primitive right) {
        int compare = Integer.compare(left.cell().getX(), right.cell().getX());
        if (compare != 0) return compare;
        compare = Integer.compare(left.cell().getY(), right.cell().getY());
        if (compare != 0) return compare;
        compare = Integer.compare(left.cell().getZ(), right.cell().getZ());
        return compare != 0 ? compare : Integer.compare(left.ordinal(), right.ordinal());
    }

    private static int axis(CardinalFrame frame) {
        return switch (frame) {
            case X_POS, X_NEG -> 0;
            case Y_POS, Y_NEG -> 1;
            case Z_POS, Z_NEG -> 2;
        };
    }

    private static boolean positive(CardinalFrame frame) {
        return frame == CardinalFrame.X_POS || frame == CardinalFrame.Y_POS || frame == CardinalFrame.Z_POS;
    }

    private static double component(Vec3d value, int axis) {
        return axis == 0 ? value.x() : axis == 1 ? value.y() : value.z();
    }

    private record Rect(double u0, double u1, double v0, double v1) { }

    private static void charge(EnvironmentObservationWork work,
            EnvironmentObservationWork.Unit unit) {
        if (work != null) work.charge(unit);
    }

    private static final class RectangleBudget {
        private final EnvironmentObservationWork work;
        private RectangleBudget(EnvironmentObservationWork work) { this.work = work; }
        private int used;
        private void consume() {
            charge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
            used = Math.incrementExact(used);
            if (used > MAX_RECTANGLE_WORK) {
                throw new SupportLimitFailure(used, MAX_RECTANGLE_WORK);
            }
        }
    }

    private static final class SupportLimitFailure extends RuntimeException {
        private SupportLimitFailure(int used, int limit) {
            this("Cardinal support rectangle work exhausted: used=" + used + ", limit=" + limit);
        }
        private SupportLimitFailure(String message) {
            super(message);
        }
    }
}
