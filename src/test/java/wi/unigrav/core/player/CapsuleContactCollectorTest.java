package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CapsuleContactCollector.Failed;
import wi.unigrav.core.player.CapsuleContactCollector.Failure;
import wi.unigrav.core.player.CapsuleContactCollector.VerifiedContacts;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Actual WorldServer/native block collision capture and frozen CHB query; no owner acceptance is inferred. */
class CapsuleContactCollectorTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004221");
    private static final QueryLimits QUERY = new QueryLimits(8192, 20000, 128, 32, 2336, 40000, 64);
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(10000, 16, 100, 100);

    @Test void allSeparatedNativeCandidatesProduceExactObservationBinding() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("separated"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            block(fixture.world(), 7, 65, 7);
            block(fixture.world(), 9, 65, 9);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            assertEquals(2, capture.boxes().size());
            var pose = CapsulePlacementMath.pose(accepted.placement());
            var budget = new CapsuleContactQueryBudget(QUERY, 8);
            var result = assertInstanceOf(VerifiedContacts.class,
                    CapsuleContactCollector.collect(accepted, pose, capture, ACCURACY, budget));
            assertTrue(result.queries().stream().allMatch(q -> q.contact().relation() == Relation.SEPARATED));
            assertEquals(capture.boxes().size(), result.queries().size());
            assertTrue(result.matches(accepted, pose, capture, budget));
            assertFalse(result.matches(new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision(),
                    accepted.placement(), accepted.worldVelocity()), pose, capture, budget));
            assertFalse(result.matches(accepted, CapsulePlacementMath.pose(accepted.placement()), capture, budget));
            assertFalse(result.matches(accepted, pose, capture, new CapsuleContactQueryBudget(QUERY, 8)));
            assertSame(accepted, result.accepted());
            assertSame(pose.capsule(), result.actualCapsule());
            assertSame(capture.footprint().requestedBounds(), result.window());
            assertEquals(accepted.scope(), result.scope());
            assertEquals(accepted.revision(), result.revision());
            assertEquals(capture.captureTick(), result.tick());
            assertEquals(pose.numericWorkDelta(), result.numericWork());
            assertEquals(pose.peakDyadicBits(), result.peakDyadicBits());
            assertEquals(budget.work(), result.work());
            for (var q : result.queries()) assertSame(capture.boxes().get(q.ordinal()), q.rawAabb());
        }
    }

    @Test void touchingFloorNeverHidesAnotherOverlappingNativeBox() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("overlap"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            block(fixture.world(), 8, 64, 8);
            block(fixture.world(), 8, 66, 8);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            assertEquals(2, capture.boxes().size());
            var budget = new CapsuleContactQueryBudget(QUERY, 8);
            var result = assertInstanceOf(Failed.class, CapsuleContactCollector.collect(accepted,
                    CapsulePlacementMath.pose(accepted.placement()), capture, ACCURACY, budget));
            assertEquals(Failure.OVERLAPPING, result.failure());
            assertTrue(result.queries().stream().anyMatch(q -> q.contact().relation() == Relation.TOUCHING));
            assertTrue(result.queries().stream().anyMatch(q -> q.contact().relation() == Relation.OVERLAPPING));
            assertEquals(capture.boxes().size(), result.firstUnqueried());
            assertEquals(capture.boxes().size(), result.queries().size());
            assertEquals(2, result.calls());
            assertEquals(budget.work(), result.work());
        }
    }

    @Test void completeTouchingKeepsActualWitnessOrdinalAndRawObjectWithoutInventingSupport() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("touch"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            block(fixture.world(), 8, 64, 8);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            assertEquals(1, capture.boxes().size());
            var pose = CapsulePlacementMath.pose(accepted.placement());
            var budget = new CapsuleContactQueryBudget(QUERY, 8);
            var result = assertInstanceOf(VerifiedContacts.class,
                    CapsuleContactCollector.collect(accepted, pose, capture, ACCURACY, budget));
            var query = result.queries().getFirst();
            assertEquals(0, query.ordinal());
            assertSame(capture.boxes().getFirst(), query.rawAabb());
            assertEquals(Relation.TOUCHING, query.contact().relation());
            assertTrue(query.contact().witness().isPresent());
            assertTrue(query.contact().reason().isEmpty());
            assertEquals(query.contact().work().exactOps(), result.work().exactOps());
            assertThrows(UnsupportedOperationException.class, result.queries()::clear);
        }
    }

    @Test void sameGeometryNewPlacementPoseIsRejectedWithoutChargingQuery() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("pose"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            var replacement = new PlayerBodyPlacement(accepted.placement().center(), accepted.placement().attitude(),
                    accepted.placement().shape());
            assertEquals(accepted.placement().bounds(), replacement.bounds());
            var budget = new CapsuleContactQueryBudget(QUERY, 8);
            var result = assertInstanceOf(Failed.class, CapsuleContactCollector.collect(accepted,
                    CapsulePlacementMath.pose(replacement), capture, ACCURACY, budget));
            assertEquals(Failure.INVALID_POSE, result.failure());
            assertEquals(0, budget.calls());
        }
    }

    @Test void capturedActorGeometryNativeDimensionsAndTickMustRemainCurrent() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("context"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var pose = CapsulePlacementMath.pose(accepted.placement());
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            actor.setEntityBoundingBox(actor.getEntityBoundingBox().offset(0.125, 0, 0));
            assertContextRejected(accepted, pose, capture);
            install(actor, accepted.placement());
            capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            actor.width = Math.nextUp(actor.width);
            assertContextRejected(accepted, pose, capture);
            install(actor, accepted.placement());
            capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            fixture.world().setTotalWorldTime(capture.captureTick() + 1);
            assertContextRejected(accepted, pose, capture);
        }
    }

    @Test void strictNativeWindowCannotMasqueradeAsExactCapsuleContactCapture() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("policy"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.NATIVE_STRICT);
            assertContextRejected(accepted, CapsulePlacementMath.pose(accepted.placement()), capture);
        }
    }

    @Test void midCollectionBudgetExhaustionRetainsCompletedCostAndRemainingOrdinals() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("budget"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            block(fixture.world(), 7, 65, 7);
            block(fixture.world(), 9, 65, 9);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            assertEquals(2, capture.boxes().size());
            var budget = new CapsuleContactQueryBudget(QUERY, 1);
            var result = assertInstanceOf(Failed.class, CapsuleContactCollector.collect(accepted,
                    CapsulePlacementMath.pose(accepted.placement()), capture, ACCURACY, budget));
            assertEquals(Failure.BUDGET_EXHAUSTED, result.failure());
            assertEquals(1, result.queries().size());
            assertEquals(1, result.firstUnqueried());
            assertEquals(2, result.candidateCount());
            assertEquals(2, result.calls());
            assertTrue(result.budgetTerminal());
            assertTrue(result.budgetFailure().isPresent());
            assertTrue(result.work().exactOps() > 0);
            assertEquals(result.queries().getFirst().contact().work().exactOps(), result.work().exactOps());
            assertEquals(budget.work(), result.work());
        }
    }

    @Test void actualChbUnresolvedRetainsOriginalReasonWorkAndUnqueriedRange() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("unresolved"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor);
            block(fixture.world(), 7, 65, 7);
            block(fixture.world(), 9, 65, 9);
            var capture = capture(fixture.world(), actor, accepted, QueryPolicy.CAPSULE_CONTACT);
            var budget = new CapsuleContactQueryBudget(new QueryLimits(8192, 1, 128, 32, 2336, 40000, 64), 8);
            var result = assertInstanceOf(Failed.class, CapsuleContactCollector.collect(accepted,
                    CapsulePlacementMath.pose(accepted.placement()), capture, ACCURACY, budget));
            assertEquals(Failure.UNRESOLVED, result.failure());
            var actual = result.queries().getFirst().contact();
            assertEquals(Relation.UNRESOLVED, actual.relation());
            assertEquals(Reason.EXACT_WORK_LIMIT, actual.reason().orElseThrow());
            assertEquals(1, actual.work().exactOps());
            assertEquals(actual.work().exactOps(), result.work().exactOps());
            assertEquals(actual.work().exactAttempts(), result.work().exactAttempts());
            assertEquals(1, result.calls());
            assertEquals(1, result.firstUnqueried());
            assertEquals(2, result.candidateCount());
            assertTrue(result.budgetTerminal());
        }
    }

    private static void assertContextRejected(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                                              WorldCollisionCandidates.Snapshot capture) {
        var budget = new CapsuleContactQueryBudget(QUERY, 8);
        var result = assertInstanceOf(Failed.class,
                CapsuleContactCollector.collect(accepted, pose, capture, ACCURACY, budget));
        assertEquals(Failure.CONTEXT_CHANGED, result.failure());
        assertTrue(result.contextFailure().isPresent());
        assertEquals(0, budget.calls());
    }

    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor) {
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, 66, 8.5), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(1, 2, 1.5)));
        install(actor, placement);
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                UUID.fromString("00000000-0000-0000-0000-000000004222"), actor.dimension, 1),
                7, placement, new Vec3d(0, 0, 0));
    }
    private static void install(EntityPlayerMP actor, PlayerBodyPlacement placement) {
        actor.width = (float) placement.shape().dimensions().width();
        actor.height = (float) placement.shape().dimensions().height();
        var p = placement.transport();
        actor.setPosition(p.x(), p.y(), p.z());
        var box = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(box.min().x(), box.min().y(), box.min().z(),
                box.max().x(), box.max().y(), box.max().z()));
    }
    private static void block(WorldServer world, int x, int y, int z) {
        var chunk = world.getChunkProvider().getLoadedChunk(x >> 4, z >> 4);
        assertNotNull(chunk, "fixture omitted actual loaded chunk");
        chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
    }
    private static WorldCollisionCandidates.Snapshot capture(WorldServer world, EntityPlayerMP actor,
                                                             PlayerPhysicalSnapshot accepted, QueryPolicy policy) {
        var actual = WorldCollisionCandidates.capture(world, actor, accepted.placement().bounds(), policy, SCAN);
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class, actual, actual::toString);
    }
}
