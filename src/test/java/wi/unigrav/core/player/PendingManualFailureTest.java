package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import static org.junit.jupiter.api.Assertions.*;

class PendingManualFailureTest {
    @TempDir Path directory;

    @Test void actualBlockedNewRequestPreservesSavedPreferenceAndOneShotReceipt() {
        try (var scenario = new Scenario(directory)) {
            var result = scenario.run();
            assertInstanceOf(PlayerPhysicsStep.Unavailable.class, result);
            var failure = scenario.travel.consumePendingManualFailure(scenario.accepted).orElseThrow();
            assertSame(scenario.request, failure.request());
            assertTrue(failure.reason() == PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED
                    || failure.reason() == PlayerPhysicsProtocol.BodyReferenceReason.PATH_BLOCKED);
            assertTrue(failure.cause().isEmpty());
            var free = assertInstanceOf(PlayerBodyReference.FreeReference.class, scenario.travel.ownerReference());
            assertEquals(Optional.of(scenario.saved), free.manualIntent(), "B must never replace saved A before installation");
            var receipt = PlayerPhysicsServer.rejectedPendingManualReceipt(failure, free.manualIntent());
            assertEquals(2, receipt.requestId());
            assertEquals(PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, receipt.decision());
            assertEquals(1, receipt.acceptedLockRequestId());
            assertEquals(Optional.of(scenario.saved.frame()), receipt.acceptedFrame());
            assertEquals(Optional.of(scenario.saved.tangentHeading()), receipt.tangentHeading());
            assertTrue(scenario.travel.consumePendingManualFailure(scenario.accepted).isEmpty());
            assertEquals(scenario.accepted.placement().bounds().min().x(), scenario.actor.getEntityBoundingBox().minX);
        }
    }

    @Test void lateFailureCannotConsumeReplacementRequestOrDifferentScope() {
        try (var scenario = new Scenario(directory)) {
            assertInstanceOf(PlayerPhysicsStep.Unavailable.class, scenario.run());
            var replacement = new PlayerBodyReference.ManualIntent(3, CardinalFrame.Y_POS, new Vec3d(0,0,1));
            scenario.travel.pendingManualIntent(Optional.of(replacement));
            assertTrue(scenario.travel.consumePendingManualFailure(scenario.accepted).isEmpty());
            assertSame(replacement, scenario.travel.pendingManualIntent().orElseThrow());
        }
        try (var scenario = new Scenario(directory.resolve("scope"))) {
            assertInstanceOf(PlayerPhysicsStep.Unavailable.class, scenario.run());
            var differentScope = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(scenario.actor.getUniqueID(),
                    UUID.randomUUID(), scenario.actor.dimension, 2), 7, scenario.accepted.placement(), new Vec3d(0,0,0));
            assertTrue(scenario.travel.consumePendingManualFailure(differentScope).isEmpty());
            assertSame(scenario.request, scenario.travel.pendingManualIntent().orElseThrow());
        }
    }

    @Test void cancellationCorrectionAndOwnerFenceRetireFailureIdentity() {
        for (int fence = 0; fence < 3; fence++) try (var scenario = new Scenario(directory.resolve("fence" + fence))) {
            assertInstanceOf(PlayerPhysicsStep.Unavailable.class, scenario.run());
            if (fence == 0) scenario.travel.pendingManualIntent(Optional.empty());
            if (fence == 1) scenario.travel.invalidate();
            if (fence == 2) scenario.current.set(false);
            assertTrue(scenario.travel.consumePendingManualFailure(scenario.accepted).isEmpty());
        }
    }

    @Test void technicalBudgetFailureKeepsCauseAndTickLedgerThenAllowsNextFreeStep() {
        try (var scenario = new Scenario(directory, new Vec3d(0,0,2))) {
            scenario.travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(),Optional.empty()));
            scenario.fixture.world().getChunk(0,0).setBlockState(new BlockPos(9,69,8),Blocks.AIR.getDefaultState());
            var resources = scenario.store.acquire(scenario.travel,scenario.fixture.world(),scenario.accepted.scope(),scenario.travel.correctionRevision());
            var original = new AtomicReference<RuntimeException>();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.actor.motionZ != 0, () -> {
                try { resources.numeric().charge(StoredDyadic.MAX_WORK_UNITS,1); }
                catch (RuntimeException cause) { original.set(cause); throw cause; }
            });
            var result = assertInstanceOf(PlayerPhysicsStep.Unavailable.class,scenario.run(true));
            assertInstanceOf(NumericWorkExhaustedException.class,original.get(),
                    () -> "actual owner result: " + result.reason() + "; cause=" + result.cause());
            assertSame(original.get(),result.cause().orElseThrow());
            var failure = scenario.travel.consumePendingManualFailure(scenario.accepted).orElseThrow();
            assertSame(original.get(),failure.cause().orElseThrow());
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE,failure.reason());
            var receipt = PlayerPhysicsServer.rejectedPendingManualReceipt(failure,Optional.empty());
            assertEquals(Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE),receipt.reason());
            long used = resources.numeric().units();
            assertTrue(used > 0);
            assertSame(resources,scenario.store.acquire(scenario.travel,scenario.fixture.world(),scenario.accepted.scope(),scenario.travel.correctionRevision()));
            assertEquals(used,resources.numeric().units(),"failed preparation does not reset or refund same-tick work");
            scenario.travel.pendingManualIntent(Optional.empty());
            scenario.travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(),Optional.empty()));
            scenario.fixture.world().setTotalWorldTime(Math.incrementExact(scenario.fixture.world().getTotalWorldTime()));
            var nextResources = scenario.store.acquire(scenario.travel,scenario.fixture.world(),scenario.accepted.scope(),scenario.travel.correctionRevision());
            assertNotSame(resources,nextResources);
            var next = assertInstanceOf(PlayerPhysicsStep.Applied.class,scenario.run(true));
            assertInstanceOf(PlayerBodyShape.FreeCapsule.class,next.snapshot().placement().shape());
            assertEquals(Math.incrementExact(scenario.accepted.revision()),next.snapshot().revision());
            assertNotEquals(scenario.accepted.placement().transport(),next.snapshot().placement().transport());
        }
    }

    @Test void actualSharedChbQueryExhaustionKeepsCauseAndAllowsNextTickFreeMotion() {
        try (var scenario = new Scenario(directory, new Vec3d(0,0,2))) {
            scenario.travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(),Optional.empty()));
            scenario.fixture.world().getChunk(0,0).setBlockState(new BlockPos(9,69,8),Blocks.AIR.getDefaultState());
            var resources = scenario.store.acquire(scenario.travel,scenario.fixture.world(),scenario.accepted.scope(),scenario.travel.correctionRevision());
            // Constructed boundary fixture: these values are not claimed as a captured run21 obstacle/producer.
            var capsule = new Capsule(new Segment3d(
                    new Vec3d(38.5,81.30000001192093,6.5),new Vec3d(38.5,82.49999994039536,6.5)),.30000001192092896);
            var box = new Aabb(new Vec3d(38,80,6),new Vec3d(39,81,7));
            var accuracy = new ContactAccuracy(1e-6,1e-6,1e-5);
            var original = new AtomicReference<CapsuleContactQueryBudget.BudgetExhausted>();
            var last = new AtomicReference<ContactResult>();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.actor.motionZ != 0, () -> {
                var queries = resources.queries();
                for (int attempt = 0; attempt <= 512 && !queries.terminal(); attempt++)
                    last.set(queries.query(capsule,box,accuracy));
                assertTrue(queries.terminal(),"actual bounded CHB queries must reach their real shared limit");
                try { queries.query(capsule,box,accuracy); }
                catch (CapsuleContactQueryBudget.BudgetExhausted cause) { original.set(cause); throw cause; }
                fail("terminal shared query ledger must reject the next real entry");
            });
            var result = assertInstanceOf(PlayerPhysicsStep.Unavailable.class,scenario.run(true));
            assertNotNull(original.get(),
                    () -> "actual owner result: " + result.reason() + "; cause=" + result.cause());
            assertSame(original.get(),result.cause().orElseThrow());
            var failure = scenario.travel.consumePendingManualFailure(scenario.accepted).orElseThrow();
            assertSame(original.get(),failure.cause().orElseThrow());
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE,failure.reason());
            assertEquals(Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE),
                    PlayerPhysicsServer.rejectedPendingManualReceipt(failure,Optional.empty()).reason());
            var queries = resources.queries();
            var spent = queries.work();
            long calls = queries.calls();
            assertTrue(spent.exactOps() > 0,"real CHB work must be charged before the denial");
            String dimension = spent.exactOps() >= 100000 ? "EXACT_OPS" : spent.staticEvaluations() >= 256
                    ? "STATIC_EVALUATIONS" : spent.castQueries() >= 128 ? "CAST_QUERIES"
                    : spent.castEvaluations() >= 9344 ? "CAST_EVALUATIONS" : spent.totalCandidateUnits() >= 150000
                    ? "CANDIDATE_UNITS" : spent.refinementSteps() >= 128 ? "REFINEMENT_STEPS" : "CALLS";
            System.out.println("UG_MANUAL_SHARED_CHB_BOUNDARY constructedFixture=true terminal="+dimension
                    +" calls="+calls+" totals="+spent+" lastRelation="+last.get().relation()+" lastReason="+last.get().reason());
            assertSame(resources,scenario.store.acquire(scenario.travel,scenario.fixture.world(),scenario.accepted.scope(),scenario.travel.correctionRevision()));
            assertEquals(spent,queries.work());
            assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,() -> queries.query(capsule,box,accuracy));
            assertEquals(spent,queries.work(),"same-tick denied attempt must never reset or refund CHB work");
            assertEquals(Math.incrementExact(calls),queries.calls());
            scenario.travel.pendingManualIntent(Optional.empty());
            scenario.travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.empty(),Optional.empty()));
            scenario.fixture.world().setTotalWorldTime(Math.incrementExact(scenario.fixture.world().getTotalWorldTime()));
            var next = assertInstanceOf(PlayerPhysicsStep.Applied.class,scenario.run(true));
            assertInstanceOf(PlayerBodyShape.FreeCapsule.class,next.snapshot().placement().shape());
            assertEquals(Math.incrementExact(scenario.accepted.revision()),next.snapshot().revision());
            assertNotEquals(scenario.accepted.placement().transport(),next.snapshot().placement().transport());
        }
    }

    private static final class Scenario implements AutoCloseable {
        final BootWorldFixture fixture;
        final EntityPlayerMP actor;
        final PlayerPhysicalSnapshot accepted;
        final PlayerPhysicsStep.TravelState travel = new PlayerPhysicsStep.TravelState();
        final PlayerBodyReference.ManualIntent saved = new PlayerBodyReference.ManualIntent(1, CardinalFrame.Z_POS,new Vec3d(0,1,0));
        final PlayerBodyReference.ManualIntent request = new PlayerBodyReference.ManualIntent(2, CardinalFrame.X_POS,new Vec3d(0,1,0));
        final AtomicBoolean current = new AtomicBoolean(true);
        final BoostMovementBudget budget;
        final FreeTickResources.Store store = new FreeTickResources.Store();
        final PlayerPhysicsStep step;
        Scenario(Path path) { this(path, new Vec3d(0,0,0)); }
        Scenario(Path path, Vec3d acceptedVelocity) {
            fixture = BootWorldFixture.open(path, new UUID(0,4382), false);
            actor = fixture.serverPlayer(new UUID(0,4382)); actor.setNoGravity(true);
            var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions((double)actor.width,(double)actor.height,actor.getEyeHeight()));
            var placement = new PlayerBodyPlacement(new Vec3d(8.5,70,8.5),Rotation3d.identity(),shape);
            actor.setPosition(placement.transport().x(),placement.transport().y(),placement.transport().z());
            var b = placement.bounds(); actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
            accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),actor.dimension,1),7,placement,acceptedVelocity);
            travel.nativeNoGravity = true;
            travel.ownerReference(new PlayerBodyReference.FreeReference(Optional.of(saved),Optional.empty()));
            travel.pendingManualIntent(Optional.of(request));
            budget = new BoostMovementBudget(500,fixture.world().getTotalWorldTime(),new Vec3(placement.transport().x(),placement.transport().y(),placement.transport().z()));
            var footprint = PlayerPhysicsServerBudgetTestAccess.installFreeToCardinalCaptureFootprint(fixture,accepted,CardinalFrame.X_POS,new Vec3d(0,0,0));
            fixture.world().getChunk(0,0).setBlockState(new BlockPos(9,69,8),Blocks.STONE.getDefaultState());
            for (var column : footprint.columns()) FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(column.x,column.z)));
            step = new PlayerPhysicsStep(new PlayerEnvironmentSampler(new GravityEntityBinder(),MagneticResponseRules.builtIn(),.5,ActiveBootRules.core()));
        }
        PlayerPhysicsStep.Result run() { return run(false); }
        PlayerPhysicsStep.Result run(boolean moving) {
            try (var tx = new FreeOwnerTransaction(actor,accepted,travel,budget,current::get,travel,
                    store.acquire(travel,fixture.world(),accepted.scope(),travel.correctionRevision()))) {
                return step.stepOwned(actor,accepted,Optional.of(false),new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,moving ? 1 : 0),new Vec3d(0,0,1),false,false,false,false,5),
                        budget,fixture.world().getTotalWorldTime(),false,travel,current::get,tx);
            }
        }
        public void close() { FloorWorldFields.clearAll();fixture.close(); }
    }
}

