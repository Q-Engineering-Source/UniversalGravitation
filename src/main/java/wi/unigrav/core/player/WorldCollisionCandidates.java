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
import net.minecraft.world.border.EnumBorderStatus;
import net.minecraft.world.border.WorldBorder;
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
        ROOT_ENTITY_LIMIT, CANDIDATE_LIMIT, CONTEXT_CHANGED, MALFORMED_COLLIDER, NATIVE_QUERY_FAILED,
        NATIVE_WORK_EXHAUSTED, NATIVE_ATTEMPT_LIMIT
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
        private final NativeCollisionProvenance.Observation provenance;
        private final boolean outsideBorderBefore;
        private final boolean outsideBorderAfter;
        private final BorderContext borderBefore;
        private final BorderContext borderAfter;

        private Snapshot(WorldServer world, Entity actor, long tick, CollisionQueryFootprint footprint,
                         ActorGeometry actorGeometry, List<Chunk> chunks, long rootEntities, List<Aabb> boxes,
                         boolean outsideBorderBefore, boolean outsideBorderAfter,
                         BorderContext borderBefore, BorderContext borderAfter,
                         NativeCollisionProvenance.Observation provenance) {
            this.world = world;
            this.actor = actor;
            this.tick = tick;
            this.footprint = footprint;
            this.actorGeometry = actorGeometry;
            this.chunks = List.copyOf(chunks);
            this.rootEntities = rootEntities;
            this.boxes = boxes;
            this.provenance = provenance;
            this.outsideBorderBefore = outsideBorderBefore;
            this.outsideBorderAfter = outsideBorderAfter;
            this.borderBefore = borderBefore;
            this.borderAfter = borderAfter;
        }

        public long captureTick() { return tick; }
        public UUID actorId() { return actorGeometry.id(); }
        public CollisionQueryFootprint footprint() { return footprint; }
        public long rootEntitiesObserved() { return rootEntities; }
        public List<Aabb> boxes() { return boxes; }
        Optional<NativeCollisionProvenance.Observation> provenance() { return Optional.ofNullable(provenance); }
        /** Join only the certified aggregate support's actual final ordinal, never equal geometry elsewhere. */
        Optional<NativeCollisionProvenance.Entry> blockProducer(CertifiedReferenceEndpoint endpoint,
                CapsuleSupportObservation.Complete support, int exactOrdinal) {
            endpoint.requireCurrent();
            var tx = endpoint.transaction();
            if (provenance == null || !provenance.matchesOwner(tx) || !support.matches(tx.accepted(), endpoint.pose(), this,
                    support.foot().window(), tx.queries(), tx.numeric())) return Optional.empty();
            provenance.requireOwnerCurrent();
            for (var actual : support.supports()) {
                tx.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.COMPARISON);
                if (actual.ordinal() != exactOrdinal) continue;
                var entry = provenance.entries().get(exactOrdinal);
                if (entry.ordinal() != exactOrdinal || !entry.bounds().equals(actual.rawAabb()))
                    throw new IllegalStateException("native provenance differs from certified aggregate support ordinal");
                endpoint.requireCurrent();
                if (!provenance.matchesOwner(tx)) return Optional.empty();
                return entry.kind() == NativeCollisionProvenance.Kind.BLOCK ? Optional.of(entry) : Optional.empty();
            }
            endpoint.requireCurrent();
            return Optional.empty();
        }
        Optional<NativeCollisionProvenance.Entry> blockProducer(InstalledFreeEndpoint endpoint,
                CapsuleSupportObservation.Complete support, int exactOrdinal) {
            endpoint.requireCurrent();
            var tx = endpoint.transaction();
            if (provenance == null || !provenance.matchesOwner(tx)
                    || !support.matches(endpoint.owner(), endpoint.pose(), this,
                            support.foot().window(), tx.queries(), tx.numeric())) return Optional.empty();
            endpoint.requireCurrent();
            for (var actual : support.supports()) {
                tx.resources().environmentObservationWork().charge(EnvironmentObservationWork.Unit.COMPARISON);
                if (actual.ordinal() != exactOrdinal) continue;
                var entry = provenance.entries().get(exactOrdinal);
                if (entry.ordinal() != exactOrdinal || !entry.bounds().equals(actual.rawAabb()))
                    throw new IllegalStateException("installed provenance differs from certified support ordinal");
                endpoint.requireCurrent();
                if (!provenance.matchesOwner(tx)) return Optional.empty();
                endpoint.requireCurrent();
                return entry.kind() == NativeCollisionProvenance.Kind.BLOCK ? Optional.of(entry) : Optional.empty();
            }
            endpoint.requireCurrent();
            return Optional.empty();
        }
        private boolean sameProvenance(Snapshot other) {
            return provenance == null ? other.provenance == null
                    : other.provenance != null && provenance.sameDescription(other.provenance);
        }
        public boolean outsideBorderBefore() { return outsideBorderBefore; }
        public boolean outsideBorderAfter() { return outsideBorderAfter; }

        /** Live geometry/tick observation only; caller still owns session scope/revision authority. */
        Optional<Unavailable> capsuleContextFailure(PlayerPhysicalSnapshot accepted) {
            Objects.requireNonNull(accepted, "accepted");
            return capsuleContextFailure(accepted, accepted.placement().bounds());
        }

        Optional<Unavailable> capsuleContextFailure(PlayerPhysicalSnapshot accepted, Aabb exactExpectedWindow) {
            Objects.requireNonNull(accepted, "accepted");
            Objects.requireNonNull(exactExpectedWindow, "exactExpectedWindow");
            if (!world.isCallingFromMinecraftThread()) {
                throw new IllegalStateException("capsule contacts require the server thread");
            }
            if (actor.world != world || !actor.isEntityAlive()
                    || !actor.getUniqueID().equals(accepted.scope().playerId())
                    || actor.dimension != accepted.scope().dimension()
                    || world.provider.getDimension() != accepted.scope().dimension()) {
                return Optional.of(unavailable(Reason.ACTOR_SCOPE,
                        "accepted contacts do not describe this live actor/world/dimension"));
            }
            if (deferred(world)) return Optional.of(unavailable(Reason.DEFERRED_WORLD,
                    "block snapshot capture/restore is active during capsule contacts"));
            var placement = accepted.placement();
            var dimensions = placement.shape().dimensions();
            if (footprint.policy() != QueryPolicy.CAPSULE_CONTACT
                    || !footprint.requestedBounds().equals(exactExpectedWindow)
                    || tick != world.getTotalWorldTime()
                    || !actorGeometry.equals(ActorGeometry.read(actor))
                    || !actorGeometry.position().equals(placement.transport())
                    || !actorGeometry.bounds().equals(placement.bounds())
                    || Float.floatToIntBits(actorGeometry.width()) != Float.floatToIntBits((float) dimensions.width())
                    || Float.floatToIntBits(actorGeometry.height()) != Float.floatToIntBits((float) dimensions.height())
                    || Double.doubleToLongBits(footprint.entityRadius()) != Double.doubleToLongBits(World.MAX_ENTITY_RADIUS)) {
                return Optional.of(unavailable(Reason.CONTEXT_CHANGED,
                        "capsule contact window or captured/live actor geometry/tick/radius differ"));
            }
            if (borderBefore != null && actor.isOutsideBorder() != outsideBorderAfter)
                return Optional.of(unavailable(Reason.CONTEXT_CHANGED,
                        "owner collision observation lost its captured outside-border state"));
            return Optional.empty();
        }

        /** Native strict observation context; independent of capsule-contact expansion semantics. */
        Optional<Unavailable> strictNativeContextFailure(PlayerPhysicalSnapshot accepted, Aabb exactExpectedWindow) {
            Objects.requireNonNull(accepted, "accepted");
            Objects.requireNonNull(exactExpectedWindow, "exactExpectedWindow");
            if (!world.isCallingFromMinecraftThread()) {
                throw new IllegalStateException("native strict observation requires the server thread");
            }
            if (actor.world != world || !actor.isEntityAlive()
                    || !actor.getUniqueID().equals(accepted.scope().playerId())
                    || actor.dimension != accepted.scope().dimension()
                    || world.provider.getDimension() != accepted.scope().dimension()) {
                return Optional.of(unavailable(Reason.ACTOR_SCOPE,
                        "accepted native observation does not describe this live actor/world/dimension"));
            }
            if (deferred(world)) return Optional.of(unavailable(Reason.DEFERRED_WORLD,
                    "block snapshot capture/restore is active during native strict observation"));
            PlayerBodyPlacement placement = accepted.placement();
            if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule)) {
                return Optional.of(unavailable(Reason.CONTEXT_CHANGED,
                        "native strict source must retain its accepted FreeCapsule placement"));
            }
            ActorGeometry liveActor;
            BorderContext liveBorder;
            try {
                liveActor = ActorGeometry.read(actor);
                liveBorder = BorderContext.read(world.getWorldBorder());
            } catch (RuntimeException invalid) {
                return Optional.of(unavailable(Reason.NATIVE_QUERY_FAILED, invalid));
            }
            PlayerBodyShape.Dimensions dimensions = placement.shape().dimensions();
            if (footprint.policy() != QueryPolicy.NATIVE_STRICT
                    || !footprint.requestedBounds().equals(exactExpectedWindow)
                    || !footprint.nativeBounds().equals(exactExpectedWindow)
                    || tick != world.getTotalWorldTime()
                    || !actorGeometry.equals(liveActor)
                    || !actorGeometry.position().equals(placement.transport())
                    || !actorGeometry.bounds().equals(placement.bounds())
                    || Float.floatToIntBits(actorGeometry.width()) != Float.floatToIntBits((float) dimensions.width())
                    || Float.floatToIntBits(actorGeometry.height()) != Float.floatToIntBits((float) dimensions.height())
                    || actor.isOutsideBorder() != outsideBorderAfter
                    || borderBefore == null || borderAfter == null
                    || !borderBefore.equals(borderAfter) || !borderAfter.equals(liveBorder)) {
                return Optional.of(unavailable(Reason.CONTEXT_CHANGED,
                        "strict target window or source actor/outside-border/world-border context changed"));
            }
            return Optional.empty();
        }

        /** Exact installed strict query for a native Cardinal target; unlike source validation it has no Free-only rule. */
        Optional<Unavailable> strictInstalledTargetContextFailure(PlayerPhysicalSnapshot installed,
                PlayerBodyPlacement exactTarget) {
            Objects.requireNonNull(installed, "installed");
            Objects.requireNonNull(exactTarget, "exactTarget");
            if (!world.isCallingFromMinecraftThread())
                throw new IllegalStateException("installed target observation requires the server thread");
            if (actor.world != world || !actor.isEntityAlive()
                    || !actor.getUniqueID().equals(installed.scope().playerId())
                    || actor.dimension != installed.scope().dimension()
                    || world.provider.getDimension() != installed.scope().dimension())
                return Optional.of(unavailable(Reason.ACTOR_SCOPE,
                        "installed strict observation does not describe the live actor/world/dimension"));
            if (deferred(world)) return Optional.of(unavailable(Reason.DEFERRED_WORLD,
                    "block snapshot capture/restore is active during installed strict observation"));
            ActorGeometry liveActor;
            BorderContext liveBorder;
            try {
                liveActor = ActorGeometry.read(actor);
                liveBorder = BorderContext.read(world.getWorldBorder());
            } catch (RuntimeException invalid) {
                return Optional.of(unavailable(Reason.NATIVE_QUERY_FAILED, invalid));
            }
            var dimensions = exactTarget.shape().dimensions();
            if (installed.placement() != exactTarget || footprint.policy() != QueryPolicy.NATIVE_STRICT
                    || !footprint.requestedBounds().equals(exactTarget.bounds())
                    || !footprint.nativeBounds().equals(exactTarget.bounds())
                    || tick != world.getTotalWorldTime() || !actorGeometry.equals(liveActor)
                    || !actorGeometry.position().equals(exactTarget.transport())
                    || !actorGeometry.bounds().equals(exactTarget.bounds())
                    || Float.floatToIntBits(actorGeometry.width()) != Float.floatToIntBits((float) dimensions.width())
                    || Float.floatToIntBits(actorGeometry.height()) != Float.floatToIntBits((float) dimensions.height())
                    || actor.isOutsideBorder() != outsideBorderAfter || borderBefore == null || borderAfter == null
                    || !borderBefore.equals(borderAfter) || !borderAfter.equals(liveBorder))
                return Optional.of(unavailable(Reason.CONTEXT_CHANGED,
                        "installed strict target window or actor/outside-border/world-border context changed"));
            return Optional.empty();
        }

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
                    || chunks.size() != other.chunks.size() || !sameProvenance(other)) return false;
            if (borderBefore != null || other.borderBefore != null) {
                if (borderBefore == null || borderAfter == null || other.borderBefore == null || other.borderAfter == null
                        || !borderAfter.equals(other.borderBefore) || !other.borderBefore.equals(other.borderAfter)
                        || outsideBorderAfter != other.outsideBorderBefore
                        || other.outsideBorderBefore != other.outsideBorderAfter) return false;
            }
            for (int index = 0; index < chunks.size(); index++) {
                if (chunks.get(index) != other.chunks.get(index)) return false;
            }
            return true;
        }

        /**
         * Bridges one sealed pre-install observation to a capture made from the exact installed owner.
         * The source actor geometry was checked against the accepted snapshot before sealing; the new
         * capture is checked against the installed journal here. Only that one geometry transition is
         * allowed. World, query, candidate, provenance, border, and chunk evidence remain exact.
         */
        boolean sameInstalledTransition(Snapshot other,
                CapsuleMovementResolver.Resolved.InstalledEvidence evidence,
                InstalledFreeEndpoint endpoint, Aabb exactWindow) {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(exactWindow, "exactWindow");
            evidence.requireBridgeSource(this, endpoint, exactWindow);
            var transaction = endpoint.transaction();
            if (other == null || world != transaction.world() || actor != transaction.actor()
                    || other.world != world || other.actor != actor || tick != transaction.tick() || other.tick != tick
                    || !actorGeometry.id().equals(endpoint.owner().scope().playerId())
                    || !other.actorGeometry.id().equals(actorGeometry.id())
                    || footprint.policy() != CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT
                    || other.footprint.policy() != footprint.policy()
                    || !footprint.requestedBounds().equals(exactWindow)
                    || !other.footprint.requestedBounds().equals(footprint.requestedBounds())
                    || !other.footprint.nativeBounds().equals(footprint.nativeBounds())
                    || Double.doubleToLongBits(footprint.entityRadius())
                            != Double.doubleToLongBits(other.footprint.entityRadius())
                    || rootEntities != other.rootEntities || outsideBorderBefore != other.outsideBorderBefore
                    || outsideBorderAfter != other.outsideBorderAfter
                    || !Objects.equals(borderBefore, other.borderBefore)
                    || !Objects.equals(borderAfter, other.borderAfter)
                    || !boxes.equals(other.boxes) || chunks.size() != other.chunks.size()
                    || !sameProvenance(other)) return false;
            var installedContext = other.capsuleContextFailure(endpoint.owner(), exactWindow);
            if (installedContext.isPresent()) {
                var unavailable = installedContext.orElseThrow();
                RuntimeException cause = unavailable.cause().orElseGet(() -> new IllegalStateException(
                        "installed transition capture context unavailable: " + unavailable.reason()
                                + ": " + unavailable.detail()));
                throw new IllegalStateException("installed transition capture context unavailable: "
                        + unavailable.reason() + ": " + unavailable.detail(), cause);
            }
            for (int index = 0; index < chunks.size(); index++) {
                if (chunks.get(index) != other.chunks.get(index)) return false;
            }
            evidence.requireBridgeSource(this, endpoint, exactWindow);
            return true;
        }

        /** Only consecutive native strict observations can satisfy a recapture fence. */
        boolean sameNativeObservation(Snapshot other) {
            if (other == null || world != other.world || actor != other.actor || tick != other.tick
                    || !actorGeometry.equals(other.actorGeometry) || rootEntities != other.rootEntities
                    || footprint.policy() != QueryPolicy.NATIVE_STRICT
                    || other.footprint.policy() != QueryPolicy.NATIVE_STRICT
                    || !footprint.requestedBounds().equals(other.footprint.requestedBounds())
                    || !footprint.nativeBounds().equals(other.footprint.nativeBounds())
                    || Double.doubleToLongBits(footprint.entityRadius())
                    != Double.doubleToLongBits(other.footprint.entityRadius())
                    || outsideBorderAfter != other.outsideBorderBefore
                    || other.outsideBorderBefore != other.outsideBorderAfter
                    || borderAfter == null || other.borderBefore == null || other.borderAfter == null
                    || !borderAfter.equals(other.borderBefore) || !other.borderBefore.equals(other.borderAfter)
                    || !boxes.equals(other.boxes) || chunks.size() != other.chunks.size() || !sameProvenance(other)) return false;
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
        return captureInternal(world, actor, requestedBounds, policy, limits, null);
    }

    /** Native collision capture charged to the existing owner tick resources. */
    static Capture capture(FreeOwnerTransaction transaction, Aabb requestedBounds,
                           QueryPolicy policy, Limits limits) {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(requestedBounds, "requestedBounds");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(limits, "limits");
        if (policy != QueryPolicy.NATIVE_STRICT && policy != QueryPolicy.CAPSULE_CONTACT) {
            return unavailable(Reason.QUERY_FOOTPRINT,
                    "owner resource capture requires a supported native collision policy");
        }
        try {
            transaction.requireCurrent();
            transaction.resources().claimNativeAttempt();
            transaction.resources().chargeNativeShell(1);
        } catch (FreeTickResources.NativeAttemptLimitException exhausted) {
            return unavailable(Reason.NATIVE_ATTEMPT_LIMIT, exhausted);
        } catch (NumericWorkExhaustedException exhausted) {
            return unavailable(Reason.NATIVE_WORK_EXHAUSTED, exhausted);
        } catch (RuntimeException invalid) {
            return unavailable(Reason.CONTEXT_CHANGED, invalid);
        }
        return captureInternal(transaction.world(), transaction.actor(), requestedBounds, policy, limits, transaction);
    }

    /** Keeps transaction fences while charging observation work to that exact owner handle. */
    static Capture capture(FreeOwnerTransaction transaction, Aabb requestedBounds,
            QueryPolicy policy, Limits limits, EnvironmentObservationWork work) {
        Objects.requireNonNull(transaction).requireCurrent();
        Objects.requireNonNull(work).requireCurrent(transaction.actor());
        if (transaction.resources().environmentObservationWork() != work)
            throw new IllegalArgumentException("foreign observation work");
        return captureInternal(transaction.world(), transaction.actor(), requestedBounds, policy, limits, transaction, work);
    }

    private static Capture captureInternal(WorldServer world, Entity actor, Aabb requestedBounds,
                                           QueryPolicy policy, Limits limits,
                                           FreeOwnerTransaction transaction) {
        return captureInternal(world, actor, requestedBounds, policy, limits, transaction, null);
    }

    public static Capture capture(WorldServer world, Entity actor, Aabb requestedBounds,
            QueryPolicy policy, Limits limits, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(actor);
        return captureInternal(world, actor, requestedBounds, policy, limits, null, work);
    }

    private static Capture captureInternal(WorldServer world, Entity actor, Aabb requestedBounds,
            QueryPolicy policy, Limits limits, FreeOwnerTransaction transaction, EnvironmentObservationWork work) {
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
            footprint = work == null ? CollisionQueryFootprint.plan(requestedBounds, World.MAX_ENTITY_RADIUS, policy, limits)
                    : CollisionQueryFootprint.plan(requestedBounds, World.MAX_ENTITY_RADIUS, policy, limits, work);
        } catch (IllegalArgumentException unsupported) {
            return unavailable(Reason.QUERY_FOOTPRINT,
                    "native query footprint planning failed: requestedBounds=" + boundsDetail(requestedBounds)
                            + ", policy=" + policy + ", limits=" + limits
                            + ", entityRadius=" + World.MAX_ENTITY_RADIUS + "; " + unsupported.getMessage(),
                    unsupported);
        }
        try {
            if (transaction != null) {
                requireTransactionCurrent(transaction);
                if (work != null) chargeNativeShell(transaction, 1);
                chargeNativeShell(transaction, footprint.blockCellsUpperBound());
            }
            long tick = world.getTotalWorldTime();
            ActorGeometry before = ActorGeometry.read(actor);
            ObservedChunks loaded = preflight(world, footprint, limits, transaction, work);
            boolean outsideBefore = actor.isOutsideBorder();
            BorderContext borderBefore = transaction == null ? null : BorderContext.read(world.getWorldBorder());
            if (transaction != null) requireTransactionCurrent(transaction);
            if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            AxisAlignedBB nativeQuery = nativeBox(footprint.nativeBounds());
            if (work != null) {
                work.claimNativeAttempt();
            }
            List<AxisAlignedBB> nativeBoxes;
            NativeCollisionProvenance.Observation provenance = null;
            if (transaction == null) {
                nativeBoxes = world.getCollisionBoxes(actor, nativeQuery);
            } else {
                var observationWork = transaction.resources().environmentObservationWork();
                try (var scope = NativeCollisionProvenance.open(transaction, nativeQuery, observationWork,
                        footprint, limits.maxCandidateBoxes())) {
                    nativeBoxes = world.getCollisionBoxes(actor, nativeQuery);
                    provenance = scope.finish(nativeBoxes);
                }
            }
            if (work != null) work.requireCurrent();
            boolean outsideAfter = actor.isOutsideBorder();
            BorderContext borderAfter = transaction == null ? null : BorderContext.read(world.getWorldBorder());
            if (transaction != null) {
                requireTransactionCurrent(transaction);
                if (!borderBefore.equals(borderAfter)) {
                    return unavailable(Reason.CONTEXT_CHANGED,
                            "world border changed during owner native collision capture");
                }
            }
            List<Aabb> boxes = transaction == null
                    ? copyNativeBoxes(nativeBoxes, limits.maxCandidateBoxes(), work)
                    : copyNativeBoxes(nativeBoxes, limits.maxCandidateBoxes(), transaction, work);
            ObservedChunks after = preflight(world, footprint, limits, transaction, work);
            if (transaction != null) requireTransactionCurrent(transaction);
            if (actor.world != world || !before.equals(ActorGeometry.read(actor)) || tick != world.getTotalWorldTime()
                    || Double.doubleToLongBits(footprint.entityRadius()) != Double.doubleToLongBits(World.MAX_ENTITY_RADIUS)
                    || !loaded.sameIdentityAndCount(after)
                    || transaction != null && !Objects.equals(borderBefore, borderAfter)) {
                return unavailable(Reason.CONTEXT_CHANGED,
                        "native observation changed actor/chunk/tick/radius/border context");
            }
            if (deferred(world)) return unavailable(Reason.DEFERRED_WORLD, "block snapshots became active during capture");
            return new Snapshot(world, actor, tick, footprint, before, loaded.chunks, loaded.rootEntities,
                    boxes, outsideBefore, outsideAfter, borderBefore, borderAfter, provenance);
        } catch (ObservationFailure failure) {
            return new Unavailable(failure.reason, Optional.ofNullable(failure.column), failure.getMessage(),
                    Optional.ofNullable((RuntimeException) failure.getCause()));
        } catch (NumericWorkExhaustedException exhausted) {
            return unavailable(Reason.NATIVE_WORK_EXHAUSTED, exhausted);
        } catch (FreeTickResources.NativeAttemptLimitException exhausted) {
            return unavailable(Reason.NATIVE_ATTEMPT_LIMIT, exhausted);
        } catch (OwnerContextChangedException changed) {
            return unavailable(Reason.CONTEXT_CHANGED, changed);
        } catch (NativeCollisionProvenance.DeclarationLimit exhausted) {
            return unavailable(Reason.CANDIDATE_LIMIT, exhausted);
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
        return resolveCardinal(world, actor, accepted, request, scanLimits, movementLimits, false);
    }

    public static CardinalObservation resolveCardinal(WorldServer world, EntityPlayer actor,
            PlayerPhysicalSnapshot accepted, CardinalMovementResolver.Request request, Limits scanLimits,
            CardinalMovementResolver.Limits movementLimits, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent(actor);
        return resolveCardinal(world, actor, accepted, request, scanLimits, movementLimits, false, work);
    }

    /**
     * Read-only support/collision calculation for the physical owner's explicit safe-native
     * restore. Native noClip suppresses ordinary movement queries, but it does not make the
     * server's strict block-collision observation unavailable. All actor, scope, geometry,
     * tick, radius, query and snapshot consistency fences remain active.
     */
    public static CardinalObservation resolveCardinalForNativeRestore(WorldServer world, EntityPlayer actor,
                                                                       PlayerPhysicalSnapshot accepted,
                                                                       CardinalMovementResolver.Request request,
                                                                       Limits scanLimits,
                                                                       CardinalMovementResolver.Limits movementLimits) {
        return resolveCardinal(world, actor, accepted, request, scanLimits, movementLimits, true);
    }

    private static CardinalObservation resolveCardinal(WorldServer world, EntityPlayer actor,
                                                        PlayerPhysicalSnapshot accepted,
                                                        CardinalMovementResolver.Request request, Limits scanLimits,
                                                        CardinalMovementResolver.Limits movementLimits,
                                                        boolean nativeRestore) {
        return resolveCardinal(world, actor, accepted, request, scanLimits, movementLimits, nativeRestore, null);
    }

    private static CardinalObservation resolveCardinal(WorldServer world, EntityPlayer actor,
            PlayerPhysicalSnapshot accepted, CardinalMovementResolver.Request request, Limits scanLimits,
            CardinalMovementResolver.Limits movementLimits, boolean nativeRestore, EnvironmentObservationWork work) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(scanLimits, "scanLimits");
        Objects.requireNonNull(movementLimits, "movementLimits");
        if (!world.isCallingFromMinecraftThread()) throw new IllegalStateException("cardinal query requires the server thread");
        List<Snapshot> snapshots = new ArrayList<>();
        if (actor.world != world || actor.isDead || actor.noClip && !nativeRestore) {
            return rejectCardinal(accepted, request, null, snapshots,
                    unavailable(Reason.ACTOR_SCOPE, "cardinal actor must be live and in this world; noClip is allowed only for native restore"));
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
            Capture captured = work == null ? capture(world, actor, requested, QueryPolicy.NATIVE_STRICT, scanLimits)
                    : capture(world, actor, requested, QueryPolicy.NATIVE_STRICT, scanLimits, work);
            if (captured instanceof Unavailable unavailable) throw new CaptureProblem(unavailable);
            Snapshot snapshot = (Snapshot) captured;
            snapshots.add(snapshot);
            if (snapshot.tick != tick || !snapshot.actorGeometry.equals(beginning)
                    || Double.doubleToLongBits(snapshot.footprint.entityRadius()) != radiusBits
                    || actor.noClip && !nativeRestore) {
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
            if (actor.world != world || actor.noClip && !nativeRestore
                    || !beginning.equals(ActorGeometry.read(actor))
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
        return preflight(world, footprint, limits, null);
    }

    private static ObservedChunks preflight(WorldServer world, CollisionQueryFootprint footprint, Limits limits,
                                            FreeOwnerTransaction transaction) {
        return preflight(world, footprint, limits, transaction, null);
    }

    private static ObservedChunks preflight(WorldServer world, CollisionQueryFootprint footprint, Limits limits,
            FreeOwnerTransaction transaction, EnvironmentObservationWork work) {
        List<Chunk> chunks = new ArrayList<>(footprint.columns().size());
        long roots = 0;
        for (Column column : footprint.columns()) {
            if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            if (transaction != null) chargeNativeShell(transaction, 1);
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
                if (work != null) work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
                if (transaction != null) chargeNativeShell(transaction, 1);
                int sectionSize = sections[section].size();
                if (transaction != null) chargeNativeShell(transaction, sectionSize);
                roots = Math.addExact(roots, sectionSize);
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
        return copyNativeBoxes(nativeBoxes, maximum, (EnvironmentObservationWork) null);
    }

    private static List<Aabb> copyNativeBoxes(List<AxisAlignedBB> nativeBoxes, int maximum,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(nativeBoxes, "nativeBoxes");
        if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("invalid candidate limit");
        if (nativeBoxes.size() > maximum) {
            throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null, "native aggregate exceeds candidate limit", null);
        }
        List<Aabb> result = new ArrayList<>(nativeBoxes.size());
        for (AxisAlignedBB box : nativeBoxes) {
            if (work != null) work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            if (result.size() == maximum) {
                throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null, "native aggregate grew beyond candidate limit", null);
            }
            try {
                if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
                    throw new IllegalArgumentException("null or inverted native collision box");
                }
                if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                result.add(new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ)));
            } catch (IllegalArgumentException invalid) {
                throw new ObservationFailure(Reason.MALFORMED_COLLIDER, null, "native collision box is not representable", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static List<Aabb> copyNativeBoxes(List<AxisAlignedBB> nativeBoxes, int maximum,
                                              FreeOwnerTransaction transaction, EnvironmentObservationWork work) {
        Objects.requireNonNull(nativeBoxes, "nativeBoxes");
        if (maximum < 1 || maximum > 4096) throw new IllegalArgumentException("invalid candidate limit");
        int reserved = nativeBoxes.size();
        chargeNativeShell(transaction, reserved);
        if (reserved > maximum) {
            throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null,
                    "native aggregate size " + reserved + " exceeds candidate limit " + maximum, null);
        }
        List<Aabb> result = new ArrayList<>(reserved);
        int accounted = reserved;
        for (int index = 0; ; index++) {
            int currentSize = nativeBoxes.size();
            if (currentSize > accounted) {
                chargeNativeShell(transaction, currentSize - (long) accounted);
                accounted = currentSize;
                if (accounted > maximum) {
                    throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null,
                            "native aggregate grew beyond candidate limit", null);
                }
            }
            if (index >= currentSize) break;
            if (result.size() == maximum) {
                throw new ObservationFailure(Reason.CANDIDATE_LIMIT, null,
                        "native aggregate grew beyond candidate limit", null);
            }
            if (work != null) work.charge(EnvironmentObservationWork.Unit.CLASSIFICATION);
            AxisAlignedBB box = nativeBoxes.get(index);
            try {
                if (box == null || box.minX > box.maxX || box.minY > box.maxY || box.minZ > box.maxZ) {
                    throw new IllegalArgumentException("null or inverted native collision box");
                }
                if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                result.add(new Aabb(new Vec3d(box.minX, box.minY, box.minZ),
                        new Vec3d(box.maxX, box.maxY, box.maxZ)));
            } catch (IllegalArgumentException invalid) {
                throw new ObservationFailure(Reason.MALFORMED_COLLIDER, null,
                        "native collision box is not representable", invalid);
            }
        }
        return List.copyOf(result);
    }

    private static void chargeNativeShell(FreeOwnerTransaction transaction, long units) {
        try {
            transaction.resources().chargeNativeShell(units);
        } catch (NumericWorkExhaustedException exhausted) {
            throw exhausted;
        } catch (RuntimeException invalid) {
            throw new ObservationFailure(Reason.CONTEXT_CHANGED, null,
                    "Free owner transaction changed during native collision accounting", invalid);
        }
    }

    private static void requireTransactionCurrent(FreeOwnerTransaction transaction) {
        try {
            transaction.requireCurrent();
        } catch (RuntimeException invalid) {
            throw new ObservationFailure(Reason.CONTEXT_CHANGED, null,
                    "Free owner transaction changed during native collision capture", invalid);
        }
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

    private static Unavailable unavailable(Reason reason, String detail, RuntimeException cause) {
        return new Unavailable(reason, Optional.empty(), detail, Optional.of(cause));
    }

    private static String boundsDetail(Aabb bounds) {
        return "min=(" + bounds.min().x() + "," + bounds.min().y() + "," + bounds.min().z()
                + "),max=(" + bounds.max().x() + "," + bounds.max().y() + "," + bounds.max().z() + ")";
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

    private record BorderContext(EnumBorderStatus status, double minX, double minZ, double maxX, double maxZ,
                                 double centerX, double centerZ, double diameter, long timeUntilTarget,
                                 double targetSize, double size, double damageBuffer, double damageAmount,
                                 int warningTime, int warningDistance) {
        static BorderContext read(WorldBorder border) {
            Objects.requireNonNull(border, "border");
            return new BorderContext(border.getStatus(), border.minX(), border.minZ(), border.maxX(), border.maxZ(),
                    border.getCenterX(), border.getCenterZ(), border.getDiameter(), border.getTimeUntilTarget(),
                    border.getTargetSize(), border.getSize(), border.getDamageBuffer(), border.getDamageAmount(),
                    border.getWarningTime(), border.getWarningDistance());
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
