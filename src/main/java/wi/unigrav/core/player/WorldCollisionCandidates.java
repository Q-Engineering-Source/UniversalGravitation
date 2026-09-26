package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import wi.unigrav.core.player.CollisionQueryFootprint.Column;
import wi.unigrav.core.player.CollisionQueryFootprint.Limits;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;

/**
 * Server-thread observations through the real public native collision query.
 *
 * <p>Preflights known loop bounds and loaded columns, never generates a missing
 * chunk and never truncates a candidate list. The public query preserves block,
 * border, entity and Forge behavior; its opaque output has aggregate ordinal
 * provenance only. Custom callback allocation/time/side effects cannot be
 * preempted through this API and are not covered by these work limits.
 *
 * <p>Non-loading is not side-effect-free: getLoadedChunk clears unloadQueued,
 * and native collection may update outsideBorder and invokes Forge listeners.
 * Neither these effects nor arbitrary callback changes are rolled back here.
 * Capture/recapture comparisons are observations, not a world mutation revision
 * or an atomic physical-state commit. Client prediction requires its own adapter.
 */
public final class WorldCollisionCandidates {
    private WorldCollisionCandidates() {
    }

    public enum Reason {
        ACTOR_SCOPE, DEFERRED_WORLD, QUERY_FOOTPRINT, MISSING_CHUNK, CHUNK_NOT_READY,
        ROOT_ENTITY_LIMIT, CANDIDATE_LIMIT, CONTEXT_CHANGED, MALFORMED_COLLIDER, NATIVE_QUERY_FAILED
    }

    public sealed interface Capture permits Snapshot, Unavailable {
    }

    public record Unavailable(Reason reason, Optional<Column> column, String detail,
                              Optional<RuntimeException> cause) implements Capture {
    }

    /** Immutable geometry observation; retained world/chunk references are scoped to the caller's transaction. */
    public static final class Snapshot implements Capture {
        private final WorldServer world;
        private final Entity actor;
        private final long tick;
        private final CollisionQueryFootprint footprint;
        private final ActorGeometry actorGeometry;
        private final List<Chunk> chunks;
        private final long rootEntities;
        private final List<Aabb> boxes;
        private final boolean outsideBorderBefore;
        private final boolean outsideBorderAfter;

        private Snapshot(WorldServer world, Entity actor, long tick, CollisionQueryFootprint footprint,
                         ActorGeometry actorGeometry, List<Chunk> chunks, long rootEntities, List<Aabb> boxes,
                         boolean outsideBorderBefore, boolean outsideBorderAfter) {
            this.world = world;
            this.actor = actor;
            this.tick = tick;
            this.footprint = footprint;
            this.actorGeometry = actorGeometry;
            this.chunks = List.copyOf(chunks);
            this.rootEntities = rootEntities;
            this.boxes = boxes;
            this.outsideBorderBefore = outsideBorderBefore;
            this.outsideBorderAfter = outsideBorderAfter;
        }

        public long captureTick() { return tick; }
        public UUID actorId() { return actorGeometry.id(); }
        public CollisionQueryFootprint footprint() { return footprint; }
        public long rootEntitiesObserved() { return rootEntities; }
        public List<Aabb> boxes() { return boxes; }
        public boolean outsideBorderBefore() { return outsideBorderBefore; }
        public boolean outsideBorderAfter() { return outsideBorderAfter; }

        /** Binds a pure query result to exactly this observed query window, not to a commit permission. */
        public CapsuleTranslationResolver.Result resolve(CapsulePlacementMath.Translation tube,
                                                          CapsuleTranslationResolver.Limits limits) {
            if (footprint.policy() != QueryPolicy.CAPSULE_CONTACT) {
                throw new IllegalArgumentException("capsule resolution requires exact-contact candidate policy");
            }
            if (!footprint.requestedBounds().equals(tube.sweptBounds())) {
                throw new IllegalArgumentException("translation and native candidate window differ");
            }
            if (!actorGeometry.position().equals(tube.start().placement().transport())
                    || !actorGeometry.bounds().equals(tube.start().placement().bounds())) {
                throw new IllegalArgumentException("translation start differs from the observed actor geometry");
            }
            return CapsuleTranslationResolver.resolve(tube, boxes, limits);
        }

        /** Compares two explicit observations; this does not call the world or pretend tick is a mutation revision. */
        public boolean sameObservation(Snapshot other) {
            if (world != other.world || actor != other.actor || tick != other.tick
                    || !actorGeometry.equals(other.actorGeometry) || rootEntities != other.rootEntities
                    || footprint.policy() != other.footprint.policy()
                    || !footprint.requestedBounds().equals(other.footprint.requestedBounds())
                    || !footprint.nativeBounds().equals(other.footprint.nativeBounds())
                    || Double.doubleToLongBits(footprint.entityRadius()) != Double.doubleToLongBits(other.footprint.entityRadius())
                    || outsideBorderAfter != other.outsideBorderAfter || !boxes.equals(other.boxes)
                    || chunks.size() != other.chunks.size()) return false;
            for (int index = 0; index < chunks.size(); index++) {
                if (chunks.get(index) != other.chunks.get(index)) return false;
            }
            return true;
        }
    }

    public static Capture capture(WorldServer world, Entity actor, Aabb requestedBounds, Limits limits) {
        return capture(world, actor, requestedBounds, QueryPolicy.CAPSULE_CONTACT, limits);
    }

    public static Capture capture(WorldServer world, Entity actor, Aabb requestedBounds, QueryPolicy policy, Limits limits) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestedBounds, "requestedBounds");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(limits, "limits");
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("collision capture requires the server thread");
        if (actor.world != world || actor.isDead) return unavailable(Reason.ACTOR_SCOPE, "actor is not live in this world");
        if (deferred(world)) return unavailable(Reason.DEFERRED_WORLD, "block snapshot capture/restore is active");
        CollisionQueryFootprint footprint;
        try {
            footprint = CollisionQueryFootprint.plan(requestedBounds, World.MAX_ENTITY_RADIUS, policy, limits);
        } catch (IllegalArgumentException unsupported) {
            return unavailable(Reason.QUERY_FOOTPRINT, unsupported);
        }
        try {
            long tick = world.getTotalWorldTime();
            ActorGeometry before = ActorGeometry.read(actor);
            ObservedChunks loaded = preflight(world, footprint, limits);
            boolean outsideBefore = actor.isOutsideBorder();
            List<AxisAlignedBB> nativeBoxes = world.getCollisionBoxes(actor, nativeBox(footprint.nativeBounds()));
            boolean outsideAfter = actor.isOutsideBorder();
            List<Aabb> boxes = copyNativeBoxes(nativeBoxes, limits.maxCandidateBoxes());
            ObservedChunks after = preflight(world, footprint, limits);
            if (actor.world != world || !before.equals(ActorGeometry.read(actor)) || tick != world.getTotalWorldTime()
                    || Double.doubleToLongBits(footprint.entityRadius()) != Double.doubleToLongBits(World.MAX_ENTITY_RADIUS)
                    || !loaded.sameIdentityAndCount(after)) {
                return unavailable(Reason.CONTEXT_CHANGED, "native observation changed actor/chunk/tick/radius context");
            }
            if (deferred(world)) return unavailable(Reason.DEFERRED_WORLD, "block snapshots became active during capture");
            return new Snapshot(world, actor, tick, footprint, before, loaded.chunks, loaded.rootEntities,
                    boxes, outsideBefore, outsideAfter);
        } catch (ObservationFailure failure) {
            return new Unavailable(failure.reason, Optional.ofNullable(failure.column), failure.getMessage(),
                    Optional.ofNullable((RuntimeException) failure.getCause()));
        } catch (RuntimeException callbackFailure) {
            return unavailable(Reason.NATIVE_QUERY_FAILED, callbackFailure);
        }
    }

    /**
     * One cardinal calculation with its actual native observations. No physical
     * revision/constraint/motion authority is inferred from it. Earlier query
     * snapshots are NOT recaptured and tick is NOT a world mutation revision:
     * callbacks can change block collision data between observations. A future
     * movement owner must establish its world-validity/commit policy separately.
     */
    public static final class CardinalObservation {
        private final PlayerPhysicalSnapshot basePhysicalState;
        private final Optional<CardinalMovementResolver.Result> calculation;
        private final List<Snapshot> snapshots;
        private final Optional<Unavailable> worldFailure;

        private CardinalObservation(PlayerPhysicalSnapshot basePhysicalState,
                                    Optional<CardinalMovementResolver.Result> calculation, List<Snapshot> snapshots,
                                    Optional<Unavailable> worldFailure) {
            this.basePhysicalState = basePhysicalState;
            this.calculation = calculation;
            this.snapshots = List.copyOf(snapshots);
            this.worldFailure = worldFailure;
        }

        public PlayerPhysicalSnapshot basePhysicalState() { return basePhysicalState; }
        /** Empty when entry validation fails before any collision calculation. */
        public Optional<CardinalMovementResolver.Result> calculation() { return calculation; }
        public List<Snapshot> snapshots() { return snapshots; }
        public Optional<Unavailable> worldFailure() { return worldFailure; }
    }

    /**
     * Resolves normal cardinal movement through the real strict native queries.
     * Actor geometry must match the request at entry and remain unchanged through
     * the observation sequence. Input/step/ground eligibility remains the caller's
     * responsibility; this method neither grants those abilities nor mutates actor.
     * Caller must own the supplied accepted snapshot's session/world epoch and
     * current revision; an arbitrary snapshot with matching geometry is not authority.
     * Per-query scan limits and whole-kernel limits are independent and cumulative
     * root callback cost remains subject to the public native API's stated limits.
     */
    public static CardinalObservation resolveCardinal(WorldServer world, EntityPlayer actor,
                                                       PlayerPhysicalSnapshot accepted,
                                                       CardinalMovementResolver.Request request, Limits scanLimits,
                                                       CardinalMovementResolver.Limits movementLimits) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(scanLimits, "scanLimits");
        Objects.requireNonNull(movementLimits, "movementLimits");
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("cardinal query requires the server thread");
        List<Snapshot> snapshots = new ArrayList<>();
        if (actor.world != world || actor.isDead || actor.noClip) {
            return rejectCardinal(accepted, request, null, snapshots,
                    unavailable(Reason.ACTOR_SCOPE, "normal cardinal actor must be live, collidable and in this world"));
        }
        if (deferred(world)) {
            return rejectCardinal(accepted, request, null, snapshots,
                    unavailable(Reason.DEFERRED_WORLD, "block snapshot capture/restore is active before cardinal calculation"));
        }
        ActorGeometry beginning;
        try {
            beginning = ActorGeometry.read(actor);
        } catch (RuntimeException invalid) {
            return rejectCardinal(accepted, request, null, snapshots, unavailable(Reason.ACTOR_SCOPE, invalid));
        }
        Optional<Unavailable> startFailure = cardinalStartFailure(accepted, request, beginning);
        if (startFailure.isPresent()) return rejectCardinal(accepted, request, null, snapshots, startFailure.orElseThrow());
        long tick = world.getTotalWorldTime();
        long radiusBits = Double.doubleToLongBits(World.MAX_ENTITY_RADIUS);
        CardinalMovementResolver.Result result = CardinalMovementResolver.resolve(request, query -> {
            Aabb requested = new Aabb(new Vec3d(query.minX, query.minY, query.minZ),
                    new Vec3d(query.maxX, query.maxY, query.maxZ));
            Capture captured = capture(world, actor, requested, QueryPolicy.NATIVE_STRICT, scanLimits);
            if (captured instanceof Unavailable unavailable) throw new CaptureProblem(unavailable);
            Snapshot snapshot = (Snapshot) captured;
            snapshots.add(snapshot);
            if (snapshot.tick != tick || !snapshot.actorGeometry.equals(beginning)
                    || Double.doubleToLongBits(snapshot.footprint.entityRadius()) != radiusBits || actor.noClip) {
                throw new CaptureProblem(unavailable(Reason.CONTEXT_CHANGED,
                        "cardinal probes no longer observe the original actor/tick/radius context"));
            }
            return snapshot.boxes.stream().map(WorldCollisionCandidates::nativeBox).toList();
        }, movementLimits);
        if (result instanceof CardinalMovementResolver.Rejected rejected) {
            Optional<Unavailable> failure = rejected.cause() instanceof CaptureProblem problem
                    ? Optional.of(problem.failure) : Optional.empty();
            return new CardinalObservation(accepted, Optional.of(result), snapshots, failure);
        }
        try {
            if (actor.world != world || actor.noClip || !beginning.equals(ActorGeometry.read(actor))
                    || world.getTotalWorldTime() != tick || Double.doubleToLongBits(World.MAX_ENTITY_RADIUS) != radiusBits) {
                return rejectCardinal(accepted, request, result, snapshots,
                        unavailable(Reason.CONTEXT_CHANGED, "cardinal final actor/tick/radius context changed"));
            }
            if (deferred(world)) return rejectCardinal(accepted, request, result, snapshots,
                    unavailable(Reason.DEFERRED_WORLD, "block snapshots became active during cardinal resolution"));
        } catch (RuntimeException invalid) {
            return rejectCardinal(accepted, request, result, snapshots, unavailable(Reason.CONTEXT_CHANGED, invalid));
        }
        return new CardinalObservation(accepted, Optional.of(result), snapshots, Optional.empty());
    }

    /** Copied actor values are observations, never a replacement for the caller's live lifetime authority. */
    static Optional<Unavailable> cardinalStartFailure(PlayerPhysicalSnapshot accepted,
                                                       CardinalMovementResolver.Request request, ActorGeometry actor) {
        if (!accepted.scope().playerId().equals(actor.id()) || accepted.scope().dimension() != actor.dimension() || actor.dead()) {
            return Optional.of(unavailable(Reason.ACTOR_SCOPE, "physical snapshot does not describe this live player/dimension"));
        }
        if (!(accepted.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || cardinal.frame() != request.frame()) {
            return Optional.of(unavailable(Reason.CONTEXT_CHANGED, "cardinal request differs from the accepted physical shape/frame"));
        }
        if (!accepted.placement().bounds().equals(actor.bounds())
                || !accepted.placement().transport().equals(actor.position())
                || !sameCoordinates(actor.bounds(), request.worldBounds())) {
            return Optional.of(unavailable(Reason.CONTEXT_CHANGED, "physical snapshot, native actor and request geometry differ"));
        }
        return Optional.empty();
    }

    private static boolean sameCoordinates(Aabb observed, AxisAlignedBB requested) {
        // Native equals uses Double.compare and distinguishes signed zeros;
        // CHB canonicalizes them. Numeric equality adds no tolerance and still
        // rejects even a one-ulp change to any non-equivalent coordinate.
        return observed.min().x() == requested.minX && observed.min().y() == requested.minY
                && observed.min().z() == requested.minZ && observed.max().x() == requested.maxX
                && observed.max().y() == requested.maxY && observed.max().z() == requested.maxZ;
    }

    private static CardinalObservation rejectCardinal(PlayerPhysicalSnapshot accepted, CardinalMovementResolver.Request request,
                                                       CardinalMovementResolver.Result previous,
                                                       List<Snapshot> snapshots, Unavailable failure) {
        if (previous == null) return new CardinalObservation(accepted, Optional.empty(), snapshots, Optional.of(failure));
        var rejected = new CardinalMovementResolver.Rejected(request.worldBounds(),
                CardinalMovementResolver.Failure.QUERY_FAILED, new CaptureProblem(failure), previous.work(), previous.queries());
        return new CardinalObservation(accepted, Optional.of(rejected), snapshots, Optional.of(failure));
    }

    private static final class CaptureProblem extends RuntimeException {
        private final Unavailable failure;

        private CaptureProblem(Unavailable failure) {
            super(failure.detail(), failure.cause().orElse(null));
            this.failure = failure;
        }
    }

    private static ObservedChunks preflight(WorldServer world, CollisionQueryFootprint footprint, Limits limits) {
        List<Chunk> chunks = new ArrayList<>(footprint.columns().size());
        long roots = 0;
        for (Column column : footprint.columns()) {
            if (!world.isBlockLoaded(new BlockPos(column.x() * 16, 64, column.z() * 16), true)) {
                throw new ObservationFailure(Reason.MISSING_CHUNK, column, "native query column is not loaded", null);
            }
            Chunk chunk = world.getChunkProvider().getLoadedChunk(column.x(), column.z());
            if (chunk == null) throw new ObservationFailure(Reason.MISSING_CHUNK, column, "loaded chunk identity disappeared", null);
            if (!chunk.isLoaded()) throw new ObservationFailure(Reason.CHUNK_NOT_READY, column, "chunk lifecycle is not ready", null);
            ClassInheritanceMultiMap<Entity>[] sections = chunk.getEntityLists();
            if (sections.length != 16) {
                throw new ObservationFailure(Reason.CHUNK_NOT_READY, column, "unsupported native entity section layout", null);
            }
            for (int section = footprint.firstEntitySection(); section <= footprint.lastEntitySection(); section++) {
                roots = Math.addExact(roots, sections[section].size());
                if (roots > limits.maxRootEntities()) {
                    throw new ObservationFailure(Reason.ROOT_ENTITY_LIMIT, column, "native root entity count exceeds limit", null);
                }
            }
            chunks.add(chunk);
        }
        return new ObservedChunks(List.copyOf(chunks), roots);
    }

    /** Shared conversion seam uses real native AABBs; no fake World or full-block substitution. */
    static List<Aabb> copyNativeBoxes(List<AxisAlignedBB> nativeBoxes, int maximum) {
        Objects.requireNonNull(nativeBoxes, "nativeBoxes");
        if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("invalid candidate limit");
        if (nativeBoxes.size() > maximum) {
            throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null, "native aggregate exceeds candidate limit", null);
        }
        List<Aabb> result = new ArrayList<>(nativeBoxes.size());
        for (AxisAlignedBB box : nativeBoxes) {
            if (result.size() == maximum) {
                throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null, "native aggregate grew beyond candidate limit", null);
            }
            try {
                if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
                    throw new IllegalArgumentException("null or inverted native collision box");
                }
                result.add(new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ)));
            } catch (IllegalArgumentException invalid) {
                throw new ObservationFailure(Reason.MALFORMED_COLLIDER, null, "native collision box is not representable", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static AxisAlignedBB nativeBox(Aabb box) {
        return new AxisAlignedBB(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z());
    }

    private static boolean deferred(World world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }

    private static Unavailable unavailable(Reason reason, String detail) {
        return new Unavailable(reason, Optional.empty(), detail, Optional.empty());
    }

    private static Unavailable unavailable(Reason reason, RuntimeException cause) {
        return new Unavailable(reason, Optional.empty(), cause.getMessage(), Optional.of(cause));
    }

    record ActorGeometry(UUID id, int dimension, Vec3d position, Aabb bounds, float width, float height, boolean dead) {
        static ActorGeometry read(Entity actor) {
            AxisAlignedBB box = actor.getEntityBoundingBox();
            return new ActorGeometry(actor.getUniqueID(), actor.dimension, new Vec3d(actor.posX, actor.posY, actor.posZ),
                    new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ)),
                    actor.width, actor.height, actor.isDead);
        }
    }

    private record ObservedChunks(List<Chunk> chunks, long rootEntities) {
        boolean sameIdentityAndCount(ObservedChunks other) {
            if (rootEntities != other.rootEntities || chunks.size() != other.chunks.size()) return false;
            for (int index = 0; index < chunks.size(); index++) if (chunks.get(index) != other.chunks.get(index)) return false;
            return true;
        }
    }

    static final class ObservationFailure extends RuntimeException {
        private final Reason reason;
        private final Column column;

        private ObservationFailure(Reason reason, Column column, String detail, RuntimeException cause) {
            super(detail, cause);
            this.reason = reason;
            this.column = column;
        }

        Reason reason() { return reason; }
    }
}
