package wi.unigrav.core.player;

import java.util.Optional;
import java.util.List;
import java.util.Objects;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.MagneticResponseRules;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.PlayerPhysicalSnapshot.Scope;

/** Narrow same-package bridge for integration assertions over the server's immutable budget snapshot. */
public final class PlayerPhysicsServerBudgetTestAccess {
    /** Real WorldServer owner-tick transaction for the cross-package Cardinal runtime fixture. */
    public static CardinalReorientation.Outcome adoptCardinalOwned(WorldServer world, EntityPlayerMP actor,
            PlayerPhysicalSnapshot accepted, CardinalFrame target, BoostMovementBudget budget, long budgetTick) {
        var owner = new PlayerPhysicsStep.TravelState();
        var store = new FreeTickResources.Store();
        try (var transaction = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true,
                owner, store.acquire(owner, world, accepted.scope(), owner.correctionRevision()))) {
            try {
                return CardinalReorientation.adopt(transaction, target, budget, budgetTick);
            } catch (RuntimeException failure) {
                transaction.recordFailure(failure);
                throw failure;
            }
        }
    }

    /** Read-only observation of the real lexical installed journal; grants no transaction operations. */
    public record NativeInstallationObservation(Object transactionIdentity, PlayerPhysicalSnapshot journal) { }

    public static Optional<NativeInstallationObservation> nativeInstallationObservation(EntityPlayerMP actor) {
        var active = FreeOwnerTransaction.nativeInstallation(actor);
        return active == null ? Optional.empty() : Optional.of(new NativeInstallationObservation(
                active.transaction(), active.journal()));
    }

    private PlayerPhysicsServerBudgetTestAccess() { }

    /** Reads the actual stored Free foot recipe without duplicating its arithmetic. */
    public static Vec3d freePhysicalFoot(PlayerBodyPlacement placement) {
        return placement.freePhysicalFoot();
    }

    public static Optional<BoostMovementBudget.Snapshot> snapshot(PlayerPhysicsServer server,
                                                                   NetHandlerPlayServer handler) {
        return server.movementBudgetSnapshot(handler);
    }

    public static Optional<FreeOwnerTransaction.InstallationCustody> failedInstallationCustody(
            PlayerPhysicsServer server, NetHandlerPlayServer handler) {
        return server.failedInstallationCustody(handler);
    }

    public static Optional<InstallationCustodyObservation> failedInstallationObservation(
            PlayerPhysicsServer server, NetHandlerPlayServer handler) {
        return server.failedInstallationCustody(handler).map(custody -> {
            var observed = custody.observedGeometry();
            return new InstallationCustodyObservation(custody.stage().name(), custody.expectedJournal(),
                    custody.expectedPlacement().transport(), custody.expectedPlacement().bounds(),
                    observed.map(WorldCollisionCandidates.ActorGeometry::position),
                    observed.map(WorldCollisionCandidates.ActorGeometry::bounds), custody.failure());
        });
    }

    public record InstallationCustodyObservation(String stage,
            PlayerPhysicalSnapshot expectedJournal, Vec3d expectedTransport, Aabb expectedBounds,
            Optional<Vec3d> observedPosition, Optional<Aabb> observedBounds,
            Optional<RuntimeException> failure) { }

    /** Test-only view of current untrusted intent; it cannot construct or submit owner authority. */
    public static Optional<AttitudeObservation> attitudeIntent(PlayerPhysicsServer server,
                                                                NetHandlerPlayServer handler) {
        return server.pendingAttitudeRequest(handler).map(request -> new AttitudeObservation(
                request.inputSequence(), request.scope(), request.proposal(), request.correctionRevision(),
                request.world(), request.receivedWorldTick(), request.rawDesiredQ()));
    }

    public record AttitudeObservation(long inputSequence, Scope scope, long proposal, long correctionRevision,
                                      WorldServer world, long receivedWorldTick, Rotation3d rawDesiredQ) { }

    /** Derives and installs the exact Cardinal-to-Free owner capture footprint into real empty chunks. */
    public static ModeCaptureFootprint installCardinalToFreeCaptureFootprint(BootWorldFixture fixture,
            PlayerPhysicalSnapshot accepted, EntityPlayerMP actor, Rotation3d desired) {
        Objects.requireNonNull(fixture, "fixture");
        var window = ModeTransitionPrepared.cardinalToFreeCaptureWindow(accepted, actor, desired,
                new StoredDyadic.Work());
        var footprint = CollisionQueryFootprint.plan(window, World.MAX_ENTITY_RADIUS,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN);
        fixture.installCollisionFootprint(footprint);
        return new ModeCaptureFootprint(window, footprint.columns().stream()
                .map(column -> new ChunkPos(column.x(), column.z())).toList());
    }

    /** Pure preload planning from accepted dimensions while a larger native resize remains pending. */
    public static ModeCaptureFootprint installAcceptedCardinalToFreeCaptureFootprint(BootWorldFixture fixture,
            PlayerPhysicalSnapshot accepted, Rotation3d desired) {
        Objects.requireNonNull(fixture, "fixture");
        var cardinal = (PlayerBodyShape.Cardinal) accepted.placement().shape();
        var numeric = new StoredDyadic.Work();
        var result = CardinalContainedCapsuleSource.prepare(accepted, cardinal.dimensions(), numeric);
        if (!(result instanceof CardinalContainedCapsuleSource.Prepared source)) {
            var unavailable = (CardinalContainedCapsuleSource.Unavailable) result;
            throw new IllegalStateException("Accepted Cardinal capture preload unavailable: "
                    + unavailable.reason() + "; " + unavailable.diagnostics(), unavailable.cause().orElse(null));
        }
        var target = new PlayerBodyPlacement(accepted.placement().center(), desired,
                new PlayerBodyShape.FreeCapsule(cardinal.dimensions()), numeric);
        var arc = CapsuleArcEnclosure.prepare(source.pose(), target, cardinal.frame().up(), numeric);
        var bounds = List.of(accepted.placement().bounds(), source.placement().bounds(),
                arc.allChildCaptureBounds(numeric), target.bounds());
        var min = bounds.getFirst().min();
        var max = bounds.getFirst().max();
        for (var box : bounds) { min = min.min(box.min()); max = max.max(box.max()); }
        var window = new Aabb(min, max);
        var footprint = CollisionQueryFootprint.plan(window, World.MAX_ENTITY_RADIUS,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN);
        fixture.installCollisionFootprint(footprint);
        return new ModeCaptureFootprint(window, footprint.columns().stream()
                .map(column -> new ChunkPos(column.x(), column.z())).toList());
    }

    /** Installs the exact Free-to-Cardinal capture window derived from this accepted owner proposal. */
    public static ModeCaptureFootprint installFreeToCardinalCaptureFootprint(BootWorldFixture fixture,
            PlayerPhysicalSnapshot accepted, CardinalFrame desired, Vec3d translationDelta) {
        Objects.requireNonNull(fixture, "fixture");
        var planning = FreeReferenceModePrepared.previewCapture(accepted, null, desired, translationDelta,
                new StoredDyadic.Work());
        if (planning instanceof FreeReferenceModePrepared.Unavailable failure)
            throw new AssertionError("Free-to-Cardinal capture preview unavailable: "
                    + failure.reason() + ": " + failure.detail(), failure.cause().orElse(null));
        var plan = (FreeReferenceModePrepared.CapturePlan) planning;
        var footprint = CollisionQueryFootprint.plan(plan.globalWindow(), World.MAX_ENTITY_RADIUS,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, FreeReferenceModePrepared.SCAN);
        fixture.installCollisionFootprint(footprint);
        return new ModeCaptureFootprint(plan.globalWindow(), footprint.columns().stream()
                .map(column -> new ChunkPos(column.x(), column.z())).toList());
    }

    public record ModeCaptureFootprint(Aabb window, List<ChunkPos> columns) {
        public ModeCaptureFootprint { columns = List.copyOf(columns); }
    }

    /**
     * Caller-owned cumulative decode work for clientbound integration-test wire round-trips.
     * Keep one instance for the simulated receiver window; each frame gets a fresh byte-counted
     * view over this same Work, matching the production decode-budget ownership rule.
     */
    public static final class ClientboundWireDecodeOwner {
        private final StoredDyadic.Work work = new StoredDyadic.Work();

        private ClientboundWireDecodeOwner() { }

        public PlayerPhysicsProtocol.Packet roundTrip(PlayerPhysicsProtocol.Packet packet) {
            Objects.requireNonNull(packet, "packet");
            if (!packet.clientbound()) {
                throw new IllegalArgumentException("clientbound test decode owner cannot receive a serverbound packet");
            }
            byte[] bytes = PlayerPhysicsProtocol.encode(packet);
            return PlayerPhysicsProtocol.decode(bytes, new PhysicsDecodeBudget(bytes.length, work));
        }

        public long chargedWorkUnits() { return work.units(); }
    }

    public static ClientboundWireDecodeOwner newClientboundWireDecodeOwner() {
        return new ClientboundWireDecodeOwner();
    }

    /** Captures the real Free-owner environment seam under the exact live physics owner snapshot. */
    public static FreeSamplerResult sampleCurrentFreeEnvironment(PlayerPhysicsServer server,
            NetHandlerPlayServer handler, EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
            Optional<Boolean> personalZeroG) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(personalZeroG, "personalZeroG");
        if (!server.owns(actor) || server.accepted(handler).filter(current -> current == accepted).isEmpty()) {
            throw new IllegalStateException("Free sampler test capture requires the exact current physics owner");
        }

        var travel = new PlayerPhysicsStep.TravelState();
        var store = new FreeTickResources.Store();
        var world = actor.getServerWorld();
        var resources = store.acquire(travel, world, accepted.scope(), travel.correctionRevision());
        var transport = accepted.placement().transport();
        var ledger = new BoostMovementBudget(10, 0, new Vec3(transport.x(), transport.y(), transport.z()));
        java.util.function.BooleanSupplier ownerCurrent = () -> server.owns(actor)
                && server.accepted(handler).filter(current -> current == accepted).isPresent();
        var sampler = new PlayerEnvironmentSampler(UnigravMod.entityBinder, MagneticResponseRules.builtIn(),
                0.5, ActiveBootRules.core());
        try (var transaction = new FreeOwnerTransaction(actor, accepted, travel, ledger, ownerCurrent,
                travel, resources)) {
            var result = sampler.sampleFree(transaction, personalZeroG,
                    new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096));
            if (result instanceof PlayerEnvironmentSampler.FreeUnavailable unavailable) {
                return new FreeSamplerUnavailable(unavailable.reason(), unavailable.evidence(), unavailable.cause());
            }
            var sampled = (PlayerEnvironmentSampler.FreeSample) result;
            var observation = sampled.observation();
            if (observation.accepted() != accepted) {
                throw new IllegalStateException("Free sampler returned a different accepted owner snapshot");
            }
            return new FreeSamplerSample(observation.accepted(), transaction.tick(), sampled.bindingActive(),
                    sampled.bindingMode(), sampled.personalZeroG(), sampled.environmentAcceleration(), sampled.fields(),
                    sampled.detachedAcceleration(), sampled.rawFields());
        } finally {
            store.invalidate();
        }
    }

    public sealed interface FreeSamplerResult permits FreeSamplerSample, FreeSamplerUnavailable { }

    public record FreeSamplerSample(PlayerPhysicalSnapshot physical, long worldTick, boolean bindingActive,
            Optional<GravityMode> bindingMode, boolean personalZeroG, Vec3 environmentAcceleration,
            StandingConstraints.Evidence evidence, Vec3 detachedAcceleration,
            StandingConstraints.Evidence rawEvidence) implements FreeSamplerResult { }

    public record FreeSamplerUnavailable(PlayerEnvironmentSampler.Reason reason, Object evidence,
            Optional<RuntimeException> cause) implements FreeSamplerResult { }
}
