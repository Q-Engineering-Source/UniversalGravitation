package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Real WorldServer and CHB linked mathematical proofs; no virtual phase is installed. */
class CapsulePathSourceTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004451");
    private static final Aabb WINDOW = new Aabb(new Vec3d(5, 62, 5), new Vec3d(12, 70, 12));
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits QUERY = new QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128);
    private static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(10000, 16, 100, 100);
    private static final Vec3d AXIS = new Vec3d(0, 1, 0);

    @Test void cardinalContainedResizeThenArcKeepsActualCardinalAndOneContext() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("cardinal-chain"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, true);
            var box = actor.getEntityBoundingBox();
            var work = new StoredDyadic.Work();
            var virtual = placement(actual.placement().center(), Rotation3d.identity(), .25, 1);
            var contained = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(actual, virtual, work));
            var pose = CapsulePlacementMath.pose(virtual, work);
            var capture = capture(f, actor);
            var queries = new CapsuleContactQueryBudget(QUERY, 1024);
            var root = CapsulePathSource.containedCardinal(contained, pose, capture, WINDOW, ACCURACY, queries, work);
            assertThrows(CapsulePathSource.InvalidSourceException.class,
                    () -> CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, queries, work));
            var grown = placement(virtual.center(), virtual.attitude(), .4, 1.5);
            var resize = assertInstanceOf(CapsuleResizeValidator.VerifiedResize.class,
                    CapsuleResizeValidator.validate(root, grown));
            var afterResize = CapsulePathSource.continueAfter(resize, work);
            var turned = new PlayerBodyPlacement(grown.center(), new Rotation3d(0, .5, 0, Math.sqrt(.75)), grown.shape());
            var arc = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(afterResize, turned, AXIS));
            var afterArc = CapsulePathSource.continueAfter(arc, work);
            assertSame(actual, afterArc.actualSource());
            assertSame(turned, afterArc.start().placement());
            assertSame(turned.capsule(), afterArc.start().capsule());
            assertSame(capture, afterArc.capture());
            assertSame(queries, afterArc.queries());
            assertSame(work, afterArc.work());
            assertTrue(root.sameContext(afterArc));
            assertEquals(2, afterArc.completedSegments());
            assertInstanceOf(PlayerBodyShape.Cardinal.class, actual.placement().shape());
            assertSame(box, actor.getEntityBoundingBox());
            assertTrue(afterArc.current());
        }
    }

    @Test void freeArcThenResizeUsesExactTargetsAndDifferentRootIsNotSameCapability() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("free-chain"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var work = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(actual.placement(), work);
            var capture = capture(f, actor);
            var queries = new CapsuleContactQueryBudget(QUERY, 1024);
            var source = CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, queries, work);
            var foreignCapability = CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, queries, work);
            var turned = new PlayerBodyPlacement(actual.placement().center(), new Rotation3d(0, .5, 0, Math.sqrt(.75)), actual.placement().shape());
            var arc = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(source, turned, AXIS));
            assertTrue(arc.matchesBound(source));
            assertFalse(arc.matchesBound(foreignCapability));
            assertFalse(source.sameContext(foreignCapability));
            var phase = CapsulePathSource.continueAfter(arc, work);
            var shrunk = placement(turned.center(), turned.attitude(), .25, 1);
            var resize = assertInstanceOf(CapsuleResizeValidator.VerifiedResize.class,
                    CapsuleResizeValidator.validate(phase, shrunk));
            assertTrue(resize.matchesBound(phase));
            assertFalse(resize.matchesBound(source));
            assertSame(shrunk, CapsulePathSource.continueAfter(resize, work).start().placement());
            var badRawQ = placement(shrunk.center(), Rotation3d.identity(), .25, 1);
            assertEquals(CapsuleResizeValidator.Failure.INVALID_INPUT,
                    assertInstanceOf(CapsuleResizeValidator.Failed.class,
                            CapsuleResizeValidator.validate(phase, badRawQ)).failure());
        }
    }

    @Test void foreignLedgerAndChangedOriginalActorCannotContinueProof() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("stale"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var work = new StoredDyadic.Work();
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture(f, actor), WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 32), work);
            var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            var foreign = new StoredDyadic.Work();
            assertThrows(CapsulePathSource.InvalidSourceException.class, () -> CapsulePathSource.continueAfter(proof, foreign));
            assertEquals(0, foreign.units());
            actor.setPosition(actor.posX + .1, actor.posY, actor.posZ);
            assertFalse(proof.matchesBound(source));
            assertThrows(CapsulePathSource.InvalidSourceException.class, () -> CapsulePathSource.continueAfter(proof, work));
            assertEquals(CapsuleArcValidator.Failure.CONTEXT_CHANGED,
                    assertInstanceOf(CapsuleArcValidator.Failed.class,
                            CapsuleArcValidator.validate(source, actual.placement(), AXIS)).failure());
        }
    }

    @Test void sixteenthProofCanContinueButSeventeenthArcAndResizeCannotBeIssued() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("limit"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var capture = capture(f, actor);
            assertTrue(capture.boxes().isEmpty());
            var work = new StoredDyadic.Work();
            var queries = new CapsuleContactQueryBudget(QUERY, 1024);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, queries, work);
            long before = work.units();
            for (int n = 0; n < CapsulePathSource.MAX_SEGMENTS; n++) {
                var outcome = CapsuleArcValidator.validate(source, actual.placement(), AXIS);
                var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class, outcome,
                        "phase=" + (n + 1) + " outcome=" + outcome + " units=" + work.units()
                        + " calls=" + queries.calls() + " chb=" + queries.work());
                source = CapsulePathSource.continueAfter(proof, work);
                assertEquals(n + 1, source.completedSegments());
                assertSame(work, source.work());
            }
            assertTrue(work.units() > before);
            long calls = queries.calls();
            long spent = work.units();
            var arc = assertInstanceOf(CapsuleArcValidator.Failed.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            assertEquals(CapsuleArcValidator.Failure.PHASE_LIMIT, arc.failure());
            assertEquals(0, arc.regions());
            assertTrue(arc.queries().isEmpty());
            assertTrue(arc.numericWork() > 0);
            var resize = assertInstanceOf(CapsuleResizeValidator.Failed.class,
                    CapsuleResizeValidator.validate(source, actual.placement()));
            assertEquals(CapsuleResizeValidator.Failure.PHASE_LIMIT, resize.failure());
            assertEquals(0, resize.regions());
            assertTrue(resize.queries().isEmpty());
            assertTrue(resize.numericWork() > 0);
            assertEquals(calls, queries.calls());
            assertTrue(work.units() > spent);
            assertNull(arc.budgetCause());
            assertNull(resize.budgetCause());
        }
    }

    @Test void continuationPoseExhaustionRetainsOriginalCauseAndSpentMetadata() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("exhaust"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var work = new StoredDyadic.Work();
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture(f, actor), WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 32), work);
            var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 10);
            long before = work.units();
            var original = assertThrows(NumericWorkExhaustedException.class,
                    () -> CapsulePathSource.continueAfter(proof, work));
            assertTrue(original.getStackTrace().length > 0);
            assertTrue(work.units() > before);
            assertSame(work, proof.source().work());
        }
    }

    @Test void legacyFactoryExhaustionAndStalenessRetainResultClassification() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("adapter"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var work = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(actual.placement(), work);
            var capture = capture(f, actor);
            var queries = new CapsuleContactQueryBudget(QUERY, 32);
            var legacy = CapsuleResizeValidator.PathSource.actualFree(actual, pose, work);
            actor.setPosition(actor.posX + .1, actor.posY, actor.posZ);
            assertEquals(CapsuleResizeValidator.Failure.CONTEXT_CHANGED,
                    assertInstanceOf(CapsuleResizeValidator.Failed.class, CapsuleResizeValidator.validate(legacy,
                            actual.placement(), capture, WINDOW, ACCURACY, queries, work)).failure());
            work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
            var arc = assertInstanceOf(CapsuleArcValidator.Failed.class, CapsuleArcValidator.validate(actual,
                    pose, actual.placement(), capture, WINDOW, AXIS, ACCURACY, queries, work));
            assertEquals(CapsuleArcValidator.Failure.NUMERIC_BUDGET, arc.failure());
            assertInstanceOf(NumericWorkExhaustedException.class, arc.budgetCause());
            var resize = assertInstanceOf(CapsuleResizeValidator.Failed.class, CapsuleResizeValidator.validate(legacy,
                    actual.placement(), capture, WINDOW, ACCURACY, queries, work));
            assertEquals(CapsuleResizeValidator.Failure.NUMERIC_BUDGET, resize.failure());
            assertInstanceOf(NumericWorkExhaustedException.class, resize.budgetCause());
        }
    }

    @Test void linkedResizeQueriesEveryOrdinalAndRejectedSegmentHasNoSuccessCapability() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("ordinals"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            f.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(new BlockPos(6, 66, 8), Blocks.STONE.getDefaultState());
            f.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(new BlockPos(11, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(f, actor);
            assertEquals(2, capture.boxes().size());
            var work = new StoredDyadic.Work();
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 1024), work);
            var arc = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            var phase = CapsulePathSource.continueAfter(arc, work);
            var safe = assertInstanceOf(CapsuleResizeValidator.VerifiedResize.class,
                    CapsuleResizeValidator.validate(phase, placement(actual.placement().center(), Rotation3d.identity(), .4, 1.5)));
            assertEquals(2, safe.queries().stream().filter(q -> q.stage() == CapsuleResizeValidator.Stage.START).count());
            assertEquals(2, safe.queries().stream().filter(q -> q.stage() == CapsuleResizeValidator.Stage.TARGET).count());
            var rejected = CapsuleResizeValidator.validate(phase,
                    placement(new Vec3d(11.5, 66, 8.5), Rotation3d.identity(), .5, 2));
            assertInstanceOf(CapsuleResizeValidator.Failed.class, rejected);
            assertFalse(rejected instanceof CapsuleResizeValidator.VerifiedResize);
        }
    }

    @Test void rootWindowAndEqualGeometryResourceSubstitutionsCannotTransferCapability() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("resources"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var actual = actual(actor, false);
            var work = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(actual.placement(), work);
            var capture = capture(f, actor);
            var queries = new CapsuleContactQueryBudget(QUERY, 32);
            var source = CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, queries, work);
            var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            var tiny = new Aabb(new Vec3d(8.4, 65.5, 8.4), new Vec3d(8.6, 66.5, 8.6));
            assertThrows(CapsulePathSource.InvalidSourceException.class,
                    () -> CapsulePathSource.actualFree(actual, pose, capture, tiny, ACCURACY, queries, work));
            var equalWindow = new Aabb(WINDOW.min(), WINDOW.max());
            var equalAccuracy = new ContactAccuracy(1e-6, 1e-6, 1e-5);
            var equalActual = new PlayerPhysicalSnapshot(actual.scope(), actual.revision(),
                    actual.placement(), actual.worldVelocity());
            var roots = new CapsulePathSource[] {
                CapsulePathSource.actualFree(actual, pose, capture(f, actor), WINDOW, ACCURACY, queries, work),
                CapsulePathSource.actualFree(actual, pose, capture, equalWindow, ACCURACY, queries, work),
                CapsulePathSource.actualFree(actual, pose, capture, WINDOW, equalAccuracy, queries, work),
                CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 32), work),
                CapsulePathSource.actualFree(actual, pose, capture, WINDOW, ACCURACY, queries, new StoredDyadic.Work()),
                CapsulePathSource.actualFree(equalActual, pose, capture, WINDOW, ACCURACY, queries, work)
            };
            for (var other : roots) {
                assertTrue(other.current());
                assertFalse(proof.matchesBound(other));
                assertFalse(source.sameContext(other));
            }
            assertTrue(proof.matchesBound(source));
        }
    }

    @Test void realOrdinalChainSpendsSharedExactBudgetAndNeverContinuesUnresolvedProof() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("finite-query-chain"), PLAYER, false)) {
            var actor = f.serverPlayer(PLAYER);
            var originalSnapshot = actual(actor, false);
            var cornerPlacement = placement(new Vec3d(8.75, 66, 8.75), Rotation3d.identity(), .5, 2);
            var transport = cornerPlacement.transport();
            actor.setPosition(transport.x(), transport.y(), transport.z());
            var box = cornerPlacement.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(box.min().x(), box.min().y(), box.min().z(),
                    box.max().x(), box.max().y(), box.max().z()));
            var actual = new PlayerPhysicalSnapshot(originalSnapshot.scope(), originalSnapshot.revision(), cornerPlacement, originalSnapshot.worldVelocity());
            // The actual support AABB touches these diagonal corners, so broadphase cannot
            // skip them, while the rounded capsule remains strictly clear of both boxes.
            f.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(new BlockPos(9, 65, 9), Blocks.STONE.getDefaultState());
            f.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(new BlockPos(9, 66, 9), Blocks.STONE.getDefaultState());
            var capture = capture(f, actor);
            assertEquals(2, capture.boxes().size());
            var work = new StoredDyadic.Work();
            var measured = CapsuleAabbContact3d.query(actual.placement().capsule(), capture.boxes().getFirst(), ACCURACY, QUERY);
            assertEquals(CapsuleAabbContact3d.Relation.SEPARATED, measured.relation());
            int exactCap = Math.min(QUERY.maxExactOps(), Math.multiplyExact(measured.work().exactOps(), 24));
            var sharedLimits = new QueryLimits(QUERY.maxExactBits(), exactCap, QUERY.maxStaticEvaluations(),
                    QUERY.maxCastQueries(), QUERY.maxCastEvaluations(), QUERY.maxCandidateUnits(), QUERY.maxRefinementSteps());
            var queries = new CapsuleContactQueryBudget(sharedLimits, 1024);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, queries, work);
            CapsuleArcValidator.Failed unresolved = null;
            int completed = 0;
            for (int n = 0; n < CapsulePathSource.MAX_SEGMENTS; n++) {
                var outcome = CapsuleArcValidator.validate(source, actual.placement(), AXIS);
                if (outcome instanceof CapsuleArcValidator.Failed failed) {
                    unresolved = failed;
                    break;
                }
                var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class, outcome);
                assertEquals(2, proof.queries().stream().filter(q -> q.stage() == CapsuleArcValidator.Stage.START).count());
                assertEquals(2, proof.queries().stream().filter(q -> q.stage() == CapsuleArcValidator.Stage.TARGET).count());
                assertEquals(2, proof.queries().stream().filter(q -> q.stage() == CapsuleArcValidator.Stage.ENCLOSURE).count());
                source = CapsulePathSource.continueAfter(proof, work);
                assertEquals(++completed, source.completedSegments());
                assertSame(queries, source.queries());
                assertSame(work, source.work());
            }
            assertNotNull(unresolved);
            assertTrue(completed > 1 && completed < CapsulePathSource.MAX_SEGMENTS);
            assertEquals(CapsuleArcValidator.Failure.UNRESOLVED, unresolved.failure());
            var originalContact = unresolved.queries().getLast().result();
            assertEquals("EXACT_WORK_LIMIT", originalContact.reason().orElseThrow().toString());
            assertEquals(sharedLimits.maxExactOps(), queries.work().exactOps());
            assertTrue(queries.work().exactAttempts() > queries.work().exactOps());
            assertTrue(queries.terminal());
            assertNull(unresolved.budgetCause(), "CHB unresolved reason is the original explicit result, not a fabricated exception");
            long spent = work.units();
            long calls = queries.calls();
            var denied = assertInstanceOf(CapsuleArcValidator.Failed.class,
                    CapsuleArcValidator.validate(source, actual.placement(), AXIS));
            assertEquals(CapsuleArcValidator.Failure.QUERY_BUDGET, denied.failure());
            var original = assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class, denied.budgetCause());
            assertEquals("enter", original.getStackTrace()[0].getMethodName());
            assertEquals(calls + 1, queries.calls());
            assertTrue(work.units() > spent);
            assertEquals(completed, source.completedSegments());
        }
    }

    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture f, EntityPlayerMP actor) {
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(f.world(), actor, WINDOW, QueryPolicy.CAPSULE_CONTACT, SCAN));
    }
    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d q, double width, double height) {
        return new PlayerBodyPlacement(center, q,
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(width, height, height * .75)));
    }
    private static PlayerPhysicalSnapshot actual(EntityPlayerMP actor, boolean cardinal) {
        var dimensions = new PlayerBodyShape.Dimensions(.5, 2, 1.5);
        PlayerBodyShape shape = cardinal ? new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions)
                : new PlayerBodyShape.FreeCapsule(dimensions);
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, 66, 8.5), Rotation3d.identity(), shape);
        actor.width = .5f;
        actor.height = 2;
        var transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        var b = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                UUID.fromString("00000000-0000-0000-0000-000000004452"), actor.dimension, 1),
                1, placement, new Vec3d(0, 0, 0));
    }
}
