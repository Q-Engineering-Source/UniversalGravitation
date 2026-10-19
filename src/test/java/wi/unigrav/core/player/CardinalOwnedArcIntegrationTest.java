package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;

import static org.junit.jupiter.api.Assertions.*;

class CardinalOwnedArcIntegrationTest {
    @TempDir Path directory;
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);

    @Test void foreignBudgetAndRetiredOwnerCannotInstallOrRebase() throws Exception {
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID()); var accepted = source(fixture, actor);
            var owner = new PlayerPhysicsStep.TravelState(); var current = new AtomicBoolean(true);
            var budget = budget(accepted); var foreign = budget(accepted); var before = foreign.snapshot();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, current::get, owner, resources)) {
                var rejected = assertInstanceOf(CardinalReorientation.Rejected.class,
                        CardinalReorientation.adopt(tx, CardinalFrame.Y_POS, foreign, 0));
                assertEquals(CardinalReorientation.Reason.ACTOR_CHANGED, rejected.reason());
                assertEquals(before, foreign.snapshot()); assertEquals(0, resources.queries().calls());
                current.set(false);
                assertThrows(IllegalStateException.class, () -> CardinalReorientation.adopt(tx, CardinalFrame.Y_POS, budget, 0));
                assertEquals(accepted.placement().bounds(), box(actor));
            }
        }
    }

    @Test void numericAndQueryExhaustionKeepActualColliderAndOriginalCause() throws Exception {
        for (boolean numeric : new boolean[] {true, false}) {
            try (var fixture = BootWorldFixture.open(directory.resolve(Boolean.toString(numeric)), UUID.randomUUID(), true)) {
                var actor = fixture.serverPlayer(UUID.randomUUID()); var accepted = source(fixture, actor);
                fixture.world().getChunkProvider().getLoadedChunk(2, 0).setBlockState(new BlockPos(40, 88, 0), Blocks.STONE.getDefaultState());
                fixture.world().getChunkProvider().getLoadedChunk(2, 0).setBlockState(new BlockPos(40, 89, 0), Blocks.STONE.getDefaultState());
                var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted); var before = budget.snapshot();
                var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
                if (numeric) resources.numeric().charge(StoredDyadic.MAX_WORK_UNITS);
                else {
                    var capsule = new Capsule(new Segment3d(ZERO, ZERO), .1);
                    var obstacle = new Aabb(new Vec3d(100, 100, 100), new Vec3d(101, 101, 101));
                    while (!resources.queries().terminal())
                        resources.queries().query(capsule, obstacle, FreeOwnerTransaction.ACCURACY);
                }
                try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                    var rejected = assertInstanceOf(CardinalReorientation.Rejected.class,
                            CardinalReorientation.adopt(tx, CardinalFrame.Y_POS, budget, 0));
                    if (numeric) assertInstanceOf(NumericWorkExhaustedException.class,
                            rejected.cause().orElseThrow());
                    else assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class, rejected.cause().orElseThrow());
                    assertEquals(before, budget.snapshot()); assertEquals(accepted.placement().bounds(), box(actor));
                }
            }
        }
    }

    @Test void cardinalReceiptBindsExactPreparedPathCaptureOrdinalBoxAndWork() throws Exception {
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID()); var accepted = source(fixture, actor);
            fixture.world().getChunkProvider().getLoadedChunk(2, 0).setBlockState(new BlockPos(40, 88, 0), Blocks.STONE.getDefaultState());
            fixture.world().getChunkProvider().getLoadedChunk(2, 0).setBlockState(new BlockPos(40, 89, 0), Blocks.STONE.getDefaultState());
            var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted);
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                var contained = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                        CardinalContainedCapsuleSource.prepare(accepted, accepted.placement().shape().dimensions(), tx.numeric()));
                var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                        CardinalRealizedArcRecipe.prepare(contained.containment(), CardinalFrame.Y_POS, tx.numeric()));
                Aabb window = new Aabb(new Vec3d(36, 84, -4), new Vec3d(45, 94, 8));
                var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
                var source = CapsulePathSource.containedCardinal(prepared.recipe().sourceContainment(), prepared.recipe().start(),
                        capture, window, FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
                assertTrue(capture.boxes().size() >= 2);
                var box = capture.boxes().getFirst();
                var receipt = CapsuleArcValidator.CardinalPlaneReceipt.tryIssue(prepared, source, 0, box, 0, 1);
                assertNotNull(receipt); assertTrue(receipt.matches(prepared, source, 0, box, 0, 1));
                assertFalse(receipt.matches(prepared, source, 1, box, 0, 1));
                assertFalse(receipt.matches(prepared, source, 0, new Aabb(box.min(), box.max()), 0, 1));
                assertFalse(receipt.matches(prepared, source, 0, box, 0, .5));
                var otherCapture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
                var otherSource = CapsulePathSource.containedCardinal(prepared.recipe().sourceContainment(), prepared.recipe().start(),
                        otherCapture, window, FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
                assertFalse(receipt.matches(prepared, otherSource, 0, box, 0, 1));
                var foreignWork = new StoredDyadic.Work();
                var foreignContained = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                        CardinalContainedCapsuleSource.prepare(accepted, accepted.placement().shape().dimensions(), foreignWork));
                var foreignPrepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                        CardinalRealizedArcRecipe.prepare(foreignContained.containment(), CardinalFrame.Y_POS, foreignWork));
                var rejected = assertInstanceOf(CapsuleArcValidator.Failed.class,
                        CapsuleArcValidator.validateCardinal(source, foreignPrepared));
                assertEquals(CapsuleArcValidator.Failure.INVALID_INPUT, rejected.failure());
                var mismatched = new CardinalRealizedArcRecipe.Prepared(prepared.recipe(), foreignPrepared.path());
                assertEquals(CapsuleArcValidator.Failure.INVALID_INPUT,
                        assertInstanceOf(CapsuleArcValidator.Failed.class, CapsuleArcValidator.validateCardinal(source, mismatched)).failure());
                actor.setPosition(actor.posX + .125, actor.posY, actor.posZ);
                assertFalse(receipt.matches(prepared, source, 0, box, 0, 1));
                assertEquals(CapsuleArcValidator.Failure.CONTEXT_CHANGED,
                        assertInstanceOf(CapsuleArcValidator.Failed.class, CapsuleArcValidator.validateCardinal(source, prepared)).failure());
            }
        }
    }

    @Test void ordinaryNativeSourceStillAdoptsCardinalWithOneOwnerBudget() throws Exception {
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID());
            actor.setPosition(8, 65, 8);
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                    new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight()));
            var placement = PlayerBodyPlacement.fromCardinalBounds(box(actor), new Vec3d(actor.posX, actor.posY, actor.posZ),
                    CardinalFrame.Y_POS.attitude(), shape);
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 1), 1, placement, ZERO);
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(4, 60, 4), new Vec3d(12, 70, 12)),
                    World.MAX_ENTITY_RADIUS, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
            var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted);
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                var outcome = CardinalReorientation.adopt(tx, CardinalFrame.X_POS, budget, 0);
                assertInstanceOf(CardinalReorientation.Installed.class, outcome, () -> "ordinary native source: " + outcome);
            }
        }
    }
    @Test void automaticTwoBoxCornerUsesOneOwnerFrameWithoutBudgetOrRevisionMint() throws Exception {
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID());
            actor.setPosition(8.899999976158142, 65, 8.5);
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                    new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight()));
            var placement = PlayerBodyPlacement.fromCardinalBounds(box(actor), new Vec3d(actor.posX, actor.posY, actor.posZ),
                    CardinalFrame.Y_POS.attitude(), shape);
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 1), 0, placement, ZERO);
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(4, 60, 4), new Vec3d(12, 70, 12)),
                    World.MAX_ENTITY_RADIUS, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(7, 65, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(8, 64, 8), Blocks.STONE.getDefaultState());
            var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted); var before = budget.snapshot();
            var store = new FreeTickResources.Store();
            var resources = store.acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                var installed = assertInstanceOf(CardinalReorientation.Installed.class,
                        CardinalReorientation.adopt(tx, CardinalFrame.X_POS, budget, 0));
                assertEquals(accepted.revision() + 1, installed.snapshot().revision());
                assertEquals(accepted.placement().center(), installed.snapshot().placement().center());
                assertEquals(installed.snapshot().placement().bounds(), box(actor));
                assertEquals(before.distanceCredit(), budget.snapshot().distanceCredit());
                assertEquals(before.roundingCredit(), budget.snapshot().roundingCredit());
                assertEquals(before.seenSequence(), budget.snapshot().seenSequence());
                assertTrue(resources.numeric().units() > 0 && resources.numeric().units() < StoredDyadic.MAX_WORK_UNITS);
                assertTrue(resources.queries().calls() > 0 && !resources.queries().terminal());
                var again = store.acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
                assertSame(resources.numeric(), again.numeric()); assertSame(resources.queries(), again.queries());
                System.out.println("OWNER_TWO_BOX_CARDINAL numeric=" + resources.numeric().units()
                        + " queryCalls=" + resources.queries().calls() + " revision=" + installed.snapshot().revision());
            }
        }
    }
    @Test void actualZToYIntermediateObstacleHasRealChbWitnessAndNeverInstalls() throws Exception {
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID()); var initial = source(fixture, actor);
            actor.setEntityBoundingBox(actor.getEntityBoundingBox().offset(0, -.4, 0)); actor.resetPositionToBB();
            var placement = PlayerBodyPlacement.fromCardinalBounds(box(actor), new Vec3d(actor.posX, actor.posY, actor.posZ),
                    CardinalFrame.Z_POS.attitude(), (PlayerBodyShape.Cardinal) initial.placement().shape());
            var accepted = new PlayerPhysicalSnapshot(initial.scope(), initial.revision(), placement, ZERO);
            fixture.world().getChunkProvider().getLoadedChunk(2, 0)
                    .setBlockState(new BlockPos(40, 89, 2), Blocks.IRON_BARS.getDefaultState());
            var obstacle = new Aabb(new Vec3d(40.4375, 89, 2.4375), new Vec3d(40.5625, 90, 2.5625));
            var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted); var before = budget.snapshot();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                var contained = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                        CardinalContainedCapsuleSource.prepare(accepted, placement.shape().dimensions(), tx.numeric()));
                var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                        CardinalRealizedArcRecipe.prepare(contained.containment(), CardinalFrame.Y_POS, tx.numeric()));
                var enclosure = prepared.path().enclose(0, 1, tx.numeric());
                assertTrue(enclosure.uses(tx.numeric()));
                var start = tx.queries().query(prepared.recipe().start().capsule(), obstacle, FreeOwnerTransaction.ACCURACY);
                var target = tx.queries().query(prepared.recipe().target().capsule(), obstacle, FreeOwnerTransaction.ACCURACY);
                var middle = tx.queries().query(enclosure.reference(), obstacle, FreeOwnerTransaction.ACCURACY);
                assertEquals(Relation.SEPARATED, start.relation());
                assertEquals(Relation.SEPARATED, target.relation());
                assertEquals(Relation.OVERLAPPING, middle.relation());
                var rejected = assertInstanceOf(CardinalReorientation.Rejected.class,
                        CardinalReorientation.adopt(tx, CardinalFrame.Y_POS, budget, 0));
                assertEquals(CardinalReorientation.Reason.PATH_BLOCKED, rejected.reason());
                assertTrue(rejected.cause().isEmpty(), () -> "real midpoint obstacle must not be a budget surrogate: " + rejected);
                assertEquals(accepted.placement().bounds(), box(actor)); assertEquals(before, budget.snapshot());
                System.out.println("OWNER_REAL_MIDPOINT_OBSTACLE start=" + start.relation() + " target=" + target.relation()
                        + " middle=" + middle.relation() + " outcome=" + rejected + " numeric=" + tx.numeric().units());
            }
        }
    }

    @Test void r48ZNegativeStairArcDiagnostic() throws Exception {
        // WARN-after snapshot reconstruction; not the exact failed server invocation.
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID());
            Aabb scene = new Aabb(new Vec3d(258, 198, 178), new Vec3d(287, 215, 187));
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(scene,
                    World.MAX_ENTITY_RADIUS, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
            for (int x = 260; x <= 284; x++) for (int y = 200; y <= 212; y++) {
                BlockPos cell = new BlockPos(x, y, 184);
                fixture.world().getChunkProvider().getLoadedChunk(x >> 4, 184 >> 4).setBlockState(cell,
                        wi.unigrav.core.floor.FloorBlocks.GRAVITY_FLOOR.getStateFromMeta(2));
            }
            for (int x = 270; x <= 274; x++) {
                var chunk = fixture.world().getChunkProvider().getLoadedChunk(x >> 4, 183 >> 4);
                chunk.setBlockState(new BlockPos(x, 206, 183), Blocks.STONE_STAIRS.getStateFromMeta(2));
                for (int y = 203; y <= 205; y++) chunk.setBlockState(new BlockPos(x, y, 183), Blocks.STONE.getDefaultState());
            }
            System.out.println("R48_SCENE floor325=gravity_floor/meta2 stairs5=stone_stairs/meta2 stone15; "
                    + "floor force profile high64/16/32 is not sampled by this arc-only diagnostic");
            for (String context : new String[] {"primary-original-order", "independent-witness-0.5", "independent-witness-0.25", "independent-witness-0.75", "independent-safe-control"}) {
                boolean safe = context.equals("independent-safe-control");
                actor.setPosition(272.5, safe ? 206.2 : 206.4, safe ? 181.5 : 182.6);
                var dims = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
                var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dims);
                var placement = PlayerBodyPlacement.fromCardinalBounds(box(actor),
                        new Vec3d(actor.posX, actor.posY, actor.posZ), CardinalFrame.Y_POS.attitude(), shape);
                var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                        UUID.randomUUID(), actor.dimension, 1), 417, placement,
                        new Vec3d(0, -.7840000152587892, 0),
                        Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 0F, false)));
                var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted);
                var budgetBefore = budget.snapshot(); var originalBox = box(actor);
                var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
                try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                    if (safe) {
                        // Capture the whole conservative scene before asserting this deliberately separated control.
                        var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                                WorldCollisionCandidates.capture(tx, scene, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
                        assertFalse(capture.boxes().isEmpty());
                        assertTrue(capture.boxes().stream().allMatch(b -> b.min().z() >= 183));
                        System.out.println("R48_SAFE_CAPTURE boxes=" + capture.boxes());
                        var outcome = CardinalReorientation.adopt(tx, CardinalFrame.Z_NEG, budget, 0);
                        System.out.println("R48_SAFE outcome=" + outcome + " work=" + tx.numeric().units());
                        assertInstanceOf(CardinalReorientation.Installed.class, outcome);
                        continue;
                    }
                    var contained = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                            CardinalContainedCapsuleSource.prepare(accepted, dims, tx.numeric()));
                    var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                            CardinalRealizedArcRecipe.prepare(contained.containment(), CardinalFrame.Z_NEG, tx.numeric()));
                    var whole = prepared.path().enclose(0, 1, tx.numeric());
                    var fullTarget = new PlayerBodyPlacement(placement.center(), CardinalFrame.Z_NEG.attitude(),
                            new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG, dims));
                    var a = placement.bounds(); var b = fullTarget.bounds(); var c = whole.bounds();
                    var window = new Aabb(new Vec3d(Math.min(a.min().x(), Math.min(b.min().x(), c.min().x())),
                            Math.min(a.min().y(), Math.min(b.min().y(), c.min().y())), Math.min(a.min().z(), Math.min(b.min().z(), c.min().z()))),
                            new Vec3d(Math.max(a.max().x(), Math.max(b.max().x(), c.max().x())),
                            Math.max(a.max().y(), Math.max(b.max().y(), c.max().y())), Math.max(a.max().z(), Math.max(b.max().z(), c.max().z()))));
                    var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                            WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
                    System.out.println("R48_CONTEXT " + context + " snapshot=" + accepted + " halfLength="
                            + Double.toHexString(prepared.recipe().halfLength()) + " window=" + window + " orderedBoxes=" + capture.boxes());
                    if (context.equals("primary-original-order")) {
                        for (var obstacle : capture.boxes()) {
                            tx.numeric().charge(6);
                            assertFalse(obstacle.min().x() < b.max().x() && b.min().x() < obstacle.max().x()
                                    && obstacle.min().y() < b.max().y() && b.min().y() < obstacle.max().y()
                                    && obstacle.min().z() < b.max().z() && b.min().z() < obstacle.max().z(),
                                    "production full-target occupancy precedes arc validation");
                        }
                        var source = CapsulePathSource.containedCardinal(prepared.recipe().sourceContainment(), prepared.recipe().start(),
                                capture, window, FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
                        long before = tx.numeric().units();
                        var result = CapsuleArcValidator.validateCardinal(source, prepared);
                        System.out.println("R48_PRIMARY outcome=" + result + " workBefore=" + before + " workAfter="
                                + tx.numeric().units() + " queryCalls=" + tx.queries().calls() + " queryRecords=" + result.queries().size());
                        if (result instanceof CapsuleArcValidator.Failed failed && failed.budgetCause() != null)
                            failed.budgetCause().printStackTrace(System.out);
                    } else {
                        // Independent diagnostic context, no authority/receipt crosses from the exhausted primary Work.
                        double t = Double.parseDouble(context.substring("independent-witness-".length()));
                        long probeBefore = tx.numeric().units();
                        var reference = prepared.path().enclose(t, t, tx.numeric()).reference();
                        int firstOverlap = -1;
                        for (int i = 0; i < capture.boxes().size(); i++) {
                            var obstacle = capture.boxes().get(i);
                            var contact = tx.queries().query(reference, obstacle, FreeOwnerTransaction.ACCURACY);
                            if (firstOverlap < 0 && contact.relation() == Relation.OVERLAPPING) firstOverlap = i;
                            System.out.println("R48_PROBE_QUERY t=" + t + " ordinal=" + i + " relation=" + contact.relation()
                                    + " clearance=" + contact.signedClearanceMeters() + " queryCalls=" + tx.queries().calls());
                        }
                        System.out.println("R48_PROBE_SUMMARY t=" + t + " actualReference=" + reference
                                + " firstOverlap=" + firstOverlap + " box="
                                + (firstOverlap < 0 ? "NONE" : capture.boxes().get(firstOverlap))
                                + " workBefore=" + probeBefore + " workAfter=" + tx.numeric().units()
                                + " queryCalls=" + tx.queries().calls()
                                + "; diagnostic points never certify a clear interval");
                    }
                    tx.requireCurrent();
                    assertEquals(originalBox, box(actor));
                    assertEquals(budgetBefore, budget.snapshot());
                }
            }
        }
    }


    @Test void r48ReconstructedBlockedArcRejectsWithinOwnerBudget() throws Exception {
        // WARN-after reconstruction with a separately established actual overlap witness; not exact runtime input.
        try (var fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true)) {
            var actor = fixture.serverPlayer(UUID.randomUUID());
            Aabb scene = new Aabb(new Vec3d(258, 198, 178), new Vec3d(287, 215, 187));
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(scene,
                    World.MAX_ENTITY_RADIUS, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
            for (int x = 260; x <= 284; x++) for (int y = 200; y <= 212; y++) {
                BlockPos cell = new BlockPos(x, y, 184);
                fixture.world().getChunkProvider().getLoadedChunk(x >> 4, 184 >> 4).setBlockState(cell,
                        wi.unigrav.core.floor.FloorBlocks.GRAVITY_FLOOR.getStateFromMeta(2));
            }
            for (int x = 270; x <= 274; x++) {
                var chunk = fixture.world().getChunkProvider().getLoadedChunk(x >> 4, 183 >> 4);
                chunk.setBlockState(new BlockPos(x, 206, 183), Blocks.STONE_STAIRS.getStateFromMeta(2));
                for (int y = 203; y <= 205; y++) chunk.setBlockState(new BlockPos(x, y, 183), Blocks.STONE.getDefaultState());
            }
            actor.setPosition(272.5, 206.4, 182.6);
            var dims = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
            var placement = PlayerBodyPlacement.fromCardinalBounds(box(actor),
                    new Vec3d(actor.posX, actor.posY, actor.posZ), CardinalFrame.Y_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dims));
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                    UUID.randomUUID(), actor.dimension, 1), 417, placement,
                    new Vec3d(0, -.7840000152587892, 0),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 0F, false)));
            var owner = new PlayerPhysicsStep.TravelState(); var budget = budget(accepted);
            var budgetBefore = budget.snapshot(); var originalBox = box(actor);
            var originalTransport = new Vec3d(actor.posX, actor.posY, actor.posZ);
            var originalMotion = new Vec3d(actor.motionX, actor.motionY, actor.motionZ);
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), owner.correctionRevision());
            try (var tx = new FreeOwnerTransaction(actor, accepted, owner, budget, () -> true, owner, resources)) {
                var outcome = CardinalReorientation.adopt(tx, CardinalFrame.Z_NEG, budget, 0);
                System.out.println("R48_ADOPT_REGRESSION outcome=" + outcome + " numericWork=" + tx.numeric().units()
                        + " queryCalls=" + tx.queries().calls());
                if (outcome instanceof CardinalReorientation.Rejected rejected && rejected.cause().isPresent())
                    rejected.cause().orElseThrow().printStackTrace(System.out);
                var rejected = assertInstanceOf(CardinalReorientation.Rejected.class, outcome);
                assertEquals(CardinalReorientation.Reason.PATH_BLOCKED, rejected.reason());
                assertEquals("Cardinal arc ARC_OVERLAP", rejected.detail());
                assertTrue(rejected.cause().isEmpty(), "an actual overlap is a typed rejection, not resource failure");
                tx.requireCurrent();
                assertEquals(originalBox, box(actor));
                assertEquals(originalTransport, new Vec3d(actor.posX, actor.posY, actor.posZ));
                assertEquals(originalMotion, new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
                assertEquals(placement, accepted.placement());
                assertEquals(417, accepted.revision());
                assertEquals(budgetBefore, budget.snapshot());
                assertTrue(tx.numeric().units() <= StoredDyadic.MAX_WORK_UNITS);
            }
        }
    }

    private static PlayerPhysicalSnapshot source(BootWorldFixture fixture, EntityPlayerMP actor) {
        var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1),
                new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833));
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z())); actor.resetPositionToBB();
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS,
                new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight()));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, new Vec3d(actor.posX, actor.posY, actor.posZ),
                CardinalFrame.Z_POS.attitude(), shape);
        fixture.installCollisionFootprint(CollisionQueryFootprint.plan(
                new Aabb(new Vec3d(36, 84, -4), new Vec3d(45, 94, 8)), World.MAX_ENTITY_RADIUS,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, SCAN));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 1),
                1, placement, ZERO);
    }
    private static Aabb box(EntityPlayerMP actor) {
        var box = actor.getEntityBoundingBox();
        return new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ));
    }
    private static BoostMovementBudget budget(PlayerPhysicalSnapshot accepted) {
        var p = accepted.placement().transport(); return new BoostMovementBudget(70, 0, new Vec3(p.x(), p.y(), p.z()));
    }
}
