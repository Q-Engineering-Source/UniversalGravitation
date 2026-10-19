package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CapsuleArcValidator.Failed;
import wi.unigrav.core.player.CapsuleArcValidator.Failure;
import wi.unigrav.core.player.CapsuleArcValidator.Stage;
import wi.unigrav.core.player.CapsuleArcValidator.VerifiedArc;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Native WorldServer candidate capture and actual CHB; does not claim owner/runtime acceptance. */
class CapsuleArcValidatorTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004431");
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits QUERY = new QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(10000, 16, 100, 100);
    private static final Aabb WINDOW = new Aabb(new Vec3d(5, 62, 5), new Vec3d(12, 70, 12));

    @Test void emptyWorldAllowsWholeHalfTurnAndBindsExactTransactionObjects() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("empty"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var target = target(accepted, new Rotation3d(0, 0, 1, 0));
            var capture = capture(fixture, actor);
            assertTrue(capture.boxes().isEmpty());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var result = assertInstanceOf(VerifiedArc.class, validate(accepted, pose, target, capture, budget, numeric));
            assertTrue(result.matches(accepted, pose, target, capture, budget, numeric));
            assertFalse(result.matches(accepted, pose, target, capture, budget, new StoredDyadic.Work()));
            assertFalse(result.matches(accepted, pose, target(accepted, target.attitude()), capture, budget, numeric));
            assertTrue(result.numericWork() > 0);
            assertTrue(result.regions() >= 1 && result.regions() <= CapsuleArcValidator.MAX_REGIONS,
                    "coarse floating-point enclosure may need bounded subdivision even in empty space");
            actor.setPosition(actor.posX + 0.1, actor.posY, actor.posZ);
            assertFalse(result.matches(accepted, pose, target, capture, budget, numeric));
        }
    }

    @Test void safeEndpointsDoNotPermitHalfTurnThroughThinWall() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("arc-wall"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            chunk.setBlockState(new BlockPos(9, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, new Rotation3d(0, 0, 1, 0)), capture, budget, numeric));
            assertTrue(result.queries().stream().filter(q -> q.stage() == Stage.START || q.stage() == Stage.TARGET)
                    .allMatch(q -> q.result().relation() == Relation.SEPARATED));
            assertTrue(result.queries().stream().anyMatch(q -> q.stage() == Stage.ENCLOSURE
                    && q.result().relation() == Relation.OVERLAPPING));
            assertNotEquals(Failure.ACTUAL_OVERLAP, result.failure(),
                    "padded overlap cannot be relabelled an actual endpoint collision");
            assertEquals(Failure.ARC_OVERLAP, result.failure());
            assertTrue(result.queries().stream().anyMatch(q -> q.stage() == Stage.MIDPOINT
                    && q.result().relation() == Relation.OVERLAPPING));
            assertEquals(result.queries().size(), budget.calls(), "only real CHB calls enter the query list");
            assertTrue(result.separations().stream().anyMatch(skip -> skip.stage() == Stage.START));
            assertTrue(result.separations().stream().anyMatch(skip -> skip.stage() == Stage.TARGET));
        }
    }

    @Test void fullIntervalWindowCoversObstacleRefinedChildEnclosures() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("arc-refinement-window"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var target = target(accepted, new Rotation3d(0, 0, 1, 0));
            var numeric = new StoredDyadic.Work();
            var startPose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var path = CapsuleArcEnclosure.prepare(startPose, target, new Vec3d(0, 0, 1), numeric);
            var whole = path.enclose(0, 1, numeric);
            var allChildren = ModeTransitionPrepared.allChildArcCaptureBounds(whole, numeric);
            var window = union(accepted.placement().bounds(), allChildren, target.bounds());

            // This block lies outside the actual rounded path, but the coarse root enclosure
            // overlaps it. The validator must subdivide the arc and prove the child regions.
            var footprint = CollisionQueryFootprint.plan(window, World.MAX_ENTITY_RADIUS,
                    QueryPolicy.CAPSULE_CONTACT, SCAN);
            assertTrue(footprint.columns().contains(new CollisionQueryFootprint.Column(1, 0)),
                    "native entity-radius expansion must include the neighboring empty column");
            fixture.installCollisionFootprint(footprint);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(6, 67, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, window);
            assertEquals(1, capture.boxes().size(), "the relevant obstacle must be in the captured window");
            var budget = new CapsuleContactQueryBudget(QUERY, 4096);
            var source = CapsulePathSource.actualFree(accepted, startPose, capture, window,
                    ACCURACY, budget, numeric);

            var proof = assertInstanceOf(VerifiedArc.class,
                    CapsuleArcValidator.validate(source, target, new Vec3d(0, 0, 1)));
            assertTrue(proof.regions() > 1, "obstacle uncertainty must actually refine the accepted arc");
            assertTrue(proof.queries().stream().anyMatch(q -> q.stage() == Stage.ENCLOSURE),
                    "every accepted child remains subject to the frozen contact validator");
            assertTrue(proof.matchesBound(source));
        }
    }

    @Test void sphereTouchingFloorRetainsFullAttitudeFreedom() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("sphere-floor"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 1, 1, 66.5);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(8, 65, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(VerifiedArc.class, validate(accepted, pose,
                    target(accepted, new Rotation3d(1, 2, 3, 4)), capture, budget, numeric));
            assertEquals(3, result.queries().size());
            assertTrue(result.queries().stream().allMatch(q -> q.result().relation() == Relation.TOUCHING));
        }
    }

    @Test void exhaustedNumericLedgerKeepsOriginalFailureAndDoesNotQueryChb() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("numeric"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var capture = capture(fixture, actor);
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            numeric.charge(StoredDyadic.MAX_WORK_UNITS - numeric.units());
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, new Rotation3d(0, 0, 1, 0)), capture, budget, numeric));
            assertEquals(Failure.NUMERIC_BUDGET, result.failure());
            assertInstanceOf(NumericWorkExhaustedException.class, result.budgetCause());
            assertEquals(StoredDyadic.MAX_WORK_UNITS, numeric.units());
            assertEquals(0, budget.calls());
        }
    }

    @Test void queryLimitAfterEndpointsRetainsSpentWorkAndOriginalBudgetCause() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("query-limit"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 1, 1, 66.5);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(8, 65, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 2);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, new Rotation3d(1, 2, 3, 4)), capture, budget, numeric));
            assertEquals(Failure.QUERY_BUDGET, result.failure());
            assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class, result.budgetCause());
            assertEquals(2, result.queries().size());
            assertEquals(3, budget.calls());
            assertTrue(budget.terminal());
            assertTrue(budget.work().exactOps() > 0);
            assertTrue(result.numericWork() > 0);
        }
    }

    @Test void staleNativeCaptureCannotAuthorizeArcOrConsumeCollisionQueries() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("stale"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(6, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size(), "the stale capture includes a box otherwise eligible for broadphase skip");
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            actor.setPosition(actor.posX + 0.1, actor.posY, actor.posZ);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, new Rotation3d(0, 0, 1, 0)), capture, budget, numeric));
            assertEquals(Failure.CONTEXT_CHANGED, result.failure());
            assertEquals(0, budget.calls());
            assertTrue(result.separations().isEmpty(), "context rejection precedes all skip receipts");
        }
    }

    @Test void r20ActualCapsuleAndObservedFarWallPrefixDoNotExhaustContactBudget() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("r20-wide-wall"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var size = new PlayerBodyShape.Dimensions(.6000000238418579, 1.7999999523162842, 1.6200000047683716);
            var placement = PlayerBodyPlacement.fromTransport(new Vec3d(34.01196263193376, 91.59340279684535, 8.093212564301698),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, size));
            actor.width = (float) size.width(); actor.height = (float) size.height();
            actor.setPosition(placement.transport().x(), placement.transport().y(), placement.transport().z());
            var b = placement.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z()));
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,
                    UUID.fromString("00000000-0000-0000-0000-000000004432"), 0, 2), 3240, placement, new Vec3d(0, 0, 0));
            var numeric = new StoredDyadic.Work();
            var initial = new PlayerBodyPlacement(placement.center(), placement.attitude(), new PlayerBodyShape.FreeCapsule(size), numeric);
            var pose = CapsulePlacementMath.pose(initial, numeric);
            var desired = new Rotation3d(.6135645939333103, -.05799436091370809, .04525924347379307, .7862103688287523);
            var target = new PlayerBodyPlacement(placement.center(), desired, initial.shape(), numeric);
            var whole = CapsuleArcEnclosure.prepare(pose, target, CardinalFrame.Y_POS.up(), numeric).enclose(0, 1, numeric);
            var wallWindow = new Aabb(new Vec3d(32, 87, 3), new Vec3d(33, 97, 12));
            var window = union(placement.bounds(), ModeTransitionPrepared.allChildArcCaptureBounds(whole, numeric), target.bounds(), wallWindow);
            var scan = new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(window, World.MAX_ENTITY_RADIUS, QueryPolicy.CAPSULE_CONTACT, scan));
            // Exact 86 observed START query boxes, in the native z/y capture order.
            for (int z = 3; z <= 11; z++) for (int y = 87; y <= (z == 11 ? 92 : 96); y++)
                fixture.world().getChunkProvider().getLoadedChunk(2, 0).setBlockState(new BlockPos(32, y, z), Blocks.STONE.getDefaultState());
            var captured = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(fixture.world(), actor, window, QueryPolicy.CAPSULE_CONTACT, scan));
            assertEquals(86, captured.boxes().size());
            var contained = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(accepted, initial, numeric));
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var source = CapsulePathSource.containedCardinal(contained, pose, captured, window, ACCURACY, budget, numeric);
            var result = CapsuleArcValidator.validate(source, target, CardinalFrame.Y_POS.up());
            var proof = assertInstanceOf(VerifiedArc.class, result, () -> "result=" + result + "; queryCalls=" + budget.calls() + "; work=" + budget.work());
            assertEquals(86, proof.separations().stream().filter(skip -> skip.stage() == Stage.START).count());
            assertEquals(86, proof.separations().stream().filter(skip -> skip.stage() == Stage.TARGET).count());
            assertTrue(proof.separations().stream().anyMatch(skip -> skip.stage() == Stage.ENCLOSURE));
            assertEquals(proof.queries().size(), budget.calls());
            assertTrue(proof.matchesBound(source));
            assertThrows(UnsupportedOperationException.class, () -> proof.separations().clear());
            assertTrue(budget.work().exactOps() < QUERY.maxExactOps());
            System.out.println("R20_ARC_FIXED queryCalls=" + budget.calls() + "; work=" + budget.work()
                    + "; separations=" + proof.separations().size() + "; regions=" + proof.regions());
            actor.setPosition(actor.posX + .1, actor.posY, actor.posZ);
            assertFalse(proof.matchesBound(source), "skip evidence cannot outlive its captured actor context");
        }
    }

    @Test void touchingAndOverlapStillQueryWhileOneUlpGapSkipsActualEndpoints() throws Exception {
        double[] centers = {66.5, Math.nextUp(66.5), 66.25};
        for (int index = 0; index < centers.length; index++) {
            try (var fixture = BootWorldFixture.open(temp.resolve("strict-gap-" + index), PLAYER, false)) {
                var actor = fixture.serverPlayer(PLAYER);
                var accepted = accepted(actor, 1, 1, centers[index]);
                fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                        .setBlockState(new BlockPos(8, 65, 8), Blocks.STONE.getDefaultState());
                var capture = capture(fixture, actor);
                var numeric = new StoredDyadic.Work();
                var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
                var budget = new CapsuleContactQueryBudget(QUERY, 32);
                var result = validate(accepted, pose, target(accepted, Rotation3d.identity()), capture, budget, numeric);
                if (index == 0) {
                    var proof = assertInstanceOf(VerifiedArc.class, result);
                    assertEquals(2, budget.calls(), "exact identity also retains the existing whole-arc Y-plane receipt");
                    assertTrue(proof.queries().stream().allMatch(q -> q.result().relation() == Relation.TOUCHING));
                    assertTrue(proof.separations().isEmpty());
                } else if (index == 1) {
                    var proof = assertInstanceOf(VerifiedArc.class, result);
                    assertTrue(proof.separations().stream().anyMatch(skip -> skip.stage() == Stage.START));
                    assertTrue(proof.separations().stream().anyMatch(skip -> skip.stage() == Stage.TARGET));
                    assertFalse(proof.queries().stream().anyMatch(q -> q.stage() == Stage.START || q.stage() == Stage.TARGET));
                } else {
                    var failed = assertInstanceOf(Failed.class, result);
                    assertEquals(Failure.ACTUAL_OVERLAP, failed.failure());
                    assertEquals(1, budget.calls());
                    assertEquals(Stage.START, failed.queries().getFirst().stage());
                    assertEquals(Relation.OVERLAPPING, failed.queries().getFirst().result().relation());
                }
            }
        }
    }

    @Test void targetCollisionIsQueriedAfterStartBroadphaseSeparation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("target-block"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, .5, 2, 66);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(9, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            var numeric = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(Failed.class, validate(accepted, CapsulePlacementMath.pose(accepted.placement(), numeric),
                    target(accepted, CardinalFrame.X_POS.attitude()), capture, budget, numeric));
            assertEquals(Failure.ACTUAL_OVERLAP, result.failure());
            assertTrue(result.separations().stream().anyMatch(skip -> skip.stage() == Stage.START));
            assertEquals(Stage.TARGET, result.queries().getFirst().stage());
            assertEquals(Relation.OVERLAPPING, result.queries().getFirst().result().relation());
        }
    }

    @Test void thinPaneOnInteriorOfHalfTurnCannotBeFilteredBySafeEndpoints() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("thin-pane"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, .5, 2, 66);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(9, 66, 8), Blocks.GLASS_PANE.getDefaultState());
            var capture = capture(fixture, actor);
            assertFalse(capture.boxes().isEmpty());
            var numeric = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var result = assertInstanceOf(Failed.class, validate(accepted, CapsulePlacementMath.pose(accepted.placement(), numeric),
                    target(accepted, new Rotation3d(0, 0, 1, 0)), capture, budget, numeric));
            assertEquals(Failure.ARC_OVERLAP, result.failure());
            assertTrue(result.queries().stream().anyMatch(q -> q.stage() == Stage.MIDPOINT && q.result().relation() == Relation.OVERLAPPING));
        }
    }

    @Test void outwardBoundsUsesActualStoredEndpointsAndNeverNearestRoundedProxy() {
        var dimensions = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
        var attitude = new Rotation3d(0, 0, .3826834323650898, .9238795325112867);
        var center = new Vec3d(0, Double.longBitsToDouble(0x3fe72d2bcfebf80aL), 0);
        var numeric = new StoredDyadic.Work();
        var limits = StoredRealizationLimits.derive(center, attitude, dimensions, numeric);
        var half = new Vec3d(limits.nominalHalfLine().x(), Double.longBitsToDouble(0x3fdb27245fd7f014L), limits.nominalHalfLine().z());
        var relation = RealizationRelation.axisPlane(limits, 1, 1, 0, center, half, numeric);
        var stored = StoredCapsuleGeometry.fromRecipe(center, attitude, dimensions, half, relation);
        assertNotEquals(stored.nominalHalfLine(), stored.storedHalfLine());
        var bounds = CapsuleOutwardBounds.of(stored.capsule(), numeric);
        var exactMaxY = StoredDyadic.of(Math.max(stored.negativeEndpoint().y(), stored.positiveEndpoint().y()), numeric)
                .add(StoredDyadic.of(stored.radius(), numeric), numeric).roundUp(numeric);
        assertEquals(exactMaxY, bounds.max().y());
        assertEquals(0, bounds.min().y(), "the actual stored endpoint is exactly radius above the support plane");
        var touching = new Aabb(new Vec3d(-1, -1, -1), new Vec3d(1, 0, 1));
        assertFalse(CapsuleOutwardBounds.strictlySeparated(bounds, touching, numeric));
        var strict = new Aabb(new Vec3d(-1, -1, -1), new Vec3d(1, Math.nextDown(0.0), 1));
        assertTrue(CapsuleOutwardBounds.strictlySeparated(bounds, strict, numeric));
        var overlap = new Aabb(new Vec3d(-1, -1, -1), new Vec3d(1, Math.nextUp(0.0), 1));
        assertFalse(CapsuleOutwardBounds.strictlySeparated(bounds, overlap, numeric));
    }

    private static CapsuleArcValidator.Result validate(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
            PlayerBodyPlacement target, WorldCollisionCandidates.Snapshot capture,
            CapsuleContactQueryBudget budget, StoredDyadic.Work numeric) {
        return CapsuleArcValidator.validate(accepted, pose, target, capture, WINDOW,
                new Vec3d(0, 0, 1), ACCURACY, budget, numeric);
    }
    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture fixture, EntityPlayerMP actor) {
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(fixture.world(), actor, WINDOW, QueryPolicy.CAPSULE_CONTACT, SCAN));
    }
    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture fixture, EntityPlayerMP actor,
                                                               Aabb window) {
        var result = WorldCollisionCandidates.capture(fixture.world(), actor, window,
                QueryPolicy.CAPSULE_CONTACT, SCAN);
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class, result,
                "native capture failed for " + window + ": " + result);
    }
    private static Aabb union(Aabb... bounds) {
        Vec3d min = bounds[0].min(), max = bounds[0].max();
        for (int index = 1; index < bounds.length; index++) {
            min = min.min(bounds[index].min());
            max = max.max(bounds[index].max());
        }
        return new Aabb(min, max);
    }
    private static PlayerBodyPlacement target(PlayerPhysicalSnapshot accepted, Rotation3d rotation) {
        return new PlayerBodyPlacement(accepted.placement().center(), rotation, accepted.placement().shape());
    }
    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor, double width, double height, double centerY) {
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, centerY, 8.5), Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(width, height, height * 0.75)));
        actor.width = (float) width;
        actor.height = (float) height;
        var transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        var b = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(),
                b.max().x(), b.max().y(), b.max().z()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                UUID.fromString("00000000-0000-0000-0000-000000004432"), actor.dimension, 1),
                1, placement, new Vec3d(0, 0, 0));
    }
}
