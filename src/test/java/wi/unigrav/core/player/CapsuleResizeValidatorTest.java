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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CapsuleResizeValidator.Failed;
import wi.unigrav.core.player.CapsuleResizeValidator.Failure;
import wi.unigrav.core.player.CapsuleResizeValidator.Stage;
import wi.unigrav.core.player.CapsuleResizeValidator.VerifiedResize;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Native WorldServer candidate capture and actual CHB; does not claim owner/runtime acceptance. */
class CapsuleResizeValidatorTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004431");
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits QUERY = new QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(10000, 16, 100, 100);
    private static final Aabb WINDOW = new Aabb(new Vec3d(5, 62, 5), new Vec3d(12, 70, 12));

    @Test void emptyWorldAllowsGrowShrinkCenterEyeAndBindsExactTransactionObjects() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("empty"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var target = new PlayerBodyPlacement(new Vec3d(10.5, 66.25, 8.5), accepted.placement().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.75, 2.5, 1.7)));
            var capture = capture(fixture, actor);
            assertTrue(capture.boxes().isEmpty());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var source = CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric);
            var result = assertInstanceOf(VerifiedResize.class, CapsuleResizeValidator.validate(source, target, capture, WINDOW, ACCURACY, budget, numeric));
            assertTrue(result.matches(source, target, capture, budget, numeric));
            assertFalse(result.matches(source, target, capture, budget, new StoredDyadic.Work()));
            assertFalse(result.matches(source, target(accepted, target.attitude()), capture, budget, numeric));
            assertTrue(result.numericWork() > 0);
            assertTrue(result.regions() >= 1 && result.regions() <= CapsuleResizeValidator.MAX_REGIONS,
                    "coarse floating-point enclosure may need bounded subdivision even in empty space");
            actor.setPosition(actor.posX + 0.1, actor.posY, actor.posZ);
            assertFalse(result.matches(source, target, capture, budget, numeric));
        }
    }

    @Test void touchingFloorShrinkUsesClosedRegionPlaneProof() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("touching-shrink"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, .5, 2, 66);
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(8, 64, 8), Blocks.STONE.getDefaultState());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var target = new PlayerBodyPlacement(new Vec3d(8.5, 65.75, 8.5), accepted.placement().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5, 1.5, 1.2)));
            assertEquals(65, accepted.placement().bounds().min().y());
            assertEquals(65, target.bounds().min().y());
            var proof = assertInstanceOf(VerifiedResize.class, validate(accepted, pose, target,
                    capture(fixture, actor), new CapsuleContactQueryBudget(QUERY, 1024), numeric));
            assertTrue(proof.queries().stream().anyMatch(q -> q.stage() == Stage.ENCLOSURE
                    && q.result().relation() == Relation.OVERLAPPING));
            assertTrue(proof.numericWork() > 0);
        }
    }

    @Test void floorPlaneCannotAuthorizeAnotherBoxPenetration() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("floor-and-wall"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, .5, 2, 66);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(8, 64, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(9, 65, 8), Blocks.STONE.getDefaultState());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var target = new PlayerBodyPlacement(new Vec3d(10.5, 65.75, 8.5), accepted.placement().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5, 1.5, 1.2)));
            var failed = assertInstanceOf(Failed.class, validate(accepted, pose, target,
                    capture(fixture, actor), new CapsuleContactQueryBudget(QUERY, 1024), numeric));
            assertEquals(Failure.RESIZE_OVERLAP, failed.failure());
            assertTrue(failed.queries().stream().anyMatch(q -> q.stage() == Stage.MIDPOINT
                    && q.result().relation() == Relation.OVERLAPPING));
        }
    }

    @Test void safeEndpointsDoNotPermitCenterTravelThroughWall() throws Exception {
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
                    new PlayerBodyPlacement(new Vec3d(10.5, 66, 8.5), accepted.placement().attitude(),
                            accepted.placement().shape()), capture, budget, numeric));
            assertTrue(result.queries().stream().filter(q -> q.stage() == Stage.START || q.stage() == Stage.TARGET)
                    .allMatch(q -> q.result().relation() == Relation.SEPARATED));
            assertTrue(result.queries().stream().anyMatch(q -> q.stage() == Stage.ENCLOSURE
                    && q.result().relation() == Relation.OVERLAPPING));
            assertNotEquals(Failure.ACTUAL_OVERLAP, result.failure(),
                    "padded overlap cannot be relabelled an actual endpoint collision");
            assertEquals(Failure.RESIZE_OVERLAP, result.failure());
            assertTrue(result.queries().stream().anyMatch(q -> q.stage() == Stage.MIDPOINT
                    && q.result().relation() == Relation.OVERLAPPING));
            assertTrue(budget.calls() > 2);
        }
    }

    @Test void knownInternalProfileFailureRetainsOriginalTypedException() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("profile"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            double boundary = 0x1p-1021;
            var accepted = accepted(actor, Math.nextDown(Math.nextDown(boundary)), boundary, 66);
            var target = new PlayerBodyPlacement(accepted.placement().center(), accepted.placement().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(boundary, Math.nextUp(boundary), 0)));
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var source = CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var failure = assertInstanceOf(Failed.class, CapsuleResizeValidator.validate(source,
                    target, capture(fixture, actor), WINDOW, ACCURACY, budget, numeric));
            assertEquals(Failure.PROFILE_NOT_CERTIFIED, failure.failure());
            var original = assertInstanceOf(CapsuleResizeEnclosure.ProfileNotCertifiedException.class,
                    failure.budgetCause());
            assertEquals(CapsuleResizeEnclosure.ProfileReason.POSITIVE_HALF_LENGTH, original.reason());
            assertEquals("checkProfile", original.getStackTrace()[0].getMethodName());
            assertTrue(original.getStackTrace()[0].getClassName().startsWith(CapsuleResizeEnclosure.class.getName()));
            assertEquals(0, budget.calls());
            assertTrue(failure.numericWork() > 0);
        }
    }

    @Test void exhaustedNumericLedgerKeepsOriginalFailureAndDoesNotQueryChb() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("numeric"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var capture = capture(fixture, actor);
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var source = CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric);
            numeric.charge(StoredDyadic.MAX_WORK_UNITS - numeric.units() - 25);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(Failed.class, CapsuleResizeValidator.validate(source,
                    target(accepted, Rotation3d.identity()), capture, WINDOW, ACCURACY, budget, numeric));
            assertEquals(Failure.NUMERIC_BUDGET, result.failure());
            assertInstanceOf(NumericWorkExhaustedException.class, result.budgetCause());
            assertTrue(numeric.units() > StoredDyadic.MAX_WORK_UNITS - 25);
            assertTrue(numeric.units() <= StoredDyadic.MAX_WORK_UNITS);
            assertTrue(result.numericWork() > 0);
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
                    target(accepted, Rotation3d.identity()), capture, budget, numeric));
            assertEquals(Failure.QUERY_BUDGET, result.failure());
            assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class, result.budgetCause());
            assertEquals(2, result.queries().size());
            assertEquals(3, budget.calls());
            assertTrue(budget.terminal());
            assertTrue(budget.work().exactOps() > 0);
            assertTrue(result.numericWork() > 0);
        }
    }

    @Test void staleNativeCaptureCannotAuthorizeResizeOrConsumeCollisionQueries() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("stale"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var capture = capture(fixture, actor);
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            actor.setPosition(actor.posX + 0.1, actor.posY, actor.posZ);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, Rotation3d.identity()), capture, budget, numeric));
            assertEquals(Failure.CONTEXT_CHANGED, result.failure());
            assertEquals(0, budget.calls());
        }
    }

    @Test void cardinalVirtualRetainsOriginalSourceAndCertificateIdentity() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("cardinal"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var free = accepted(actor, 0.5, 2, 66);
            var original = new PlayerBodyPlacement(free.placement().center(), CardinalFrame.Y_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, free.placement().shape().dimensions()));
            var actual = new PlayerPhysicalSnapshot(free.scope(), free.revision(), original, free.worldVelocity());
            var virtual = new PlayerBodyPlacement(original.center(), original.attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.25, 1, 0.75)));
            var numeric = new StoredDyadic.Work();
            var certificate = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(actual, virtual, numeric));
            var pose = CapsulePlacementMath.pose(virtual, numeric);
            var source = CapsuleResizeValidator.PathSource.cardinalVirtual(certificate, pose, numeric);
            assertSame(actual, source.actualSource());
            assertSame(original, source.actualSource().placement());
            assertInstanceOf(PlayerBodyShape.Cardinal.class, source.actualSource().placement().shape());
            assertThrows(IllegalArgumentException.class, () -> CapsuleResizeValidator.PathSource.actualFree(actual, pose, numeric));
            assertThrows(IllegalArgumentException.class, () -> CapsuleResizeValidator.PathSource.cardinalVirtual(
                    certificate, pose, new StoredDyadic.Work()));
            var target = new PlayerBodyPlacement(original.center(), original.attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.5, 2, 1.5)));
            var capture = capture(fixture, actor);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var verified = assertInstanceOf(VerifiedResize.class, CapsuleResizeValidator.validate(source,
                    target, capture, WINDOW, ACCURACY, budget, numeric));
            assertTrue(verified.matches(source, target, capture, budget, numeric));
            assertSame(original, actual.placement());
            actor.setPosition(actor.posX + 0.1, actor.posY, actor.posZ);
            assertFalse(verified.matches(source, target, capture, budget, numeric));
        }
    }

    @Test void allOrdinalsRemainObservedEvenWhenActualEndpointOverlaps() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("ordinals"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 0.5, 2, 66);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(8, 66, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(10, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor);
            assertEquals(2, capture.boxes().size());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var result = assertInstanceOf(Failed.class, validate(accepted, pose,
                    target(accepted, Rotation3d.identity()), capture, new CapsuleContactQueryBudget(QUERY, 32), numeric));
            assertEquals(Failure.ACTUAL_OVERLAP, result.failure());
            assertEquals(2, result.queries().size());
            assertEquals(0, result.queries().get(0).ordinal());
            assertEquals(1, result.queries().get(1).ordinal());
        }
    }

    @Test void emptyWorldShrinkUsesActualSmallerTargetAndChangedEye() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("shrink"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, 1, 3, 66);
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var source = CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric);
            var target = new PlayerBodyPlacement(new Vec3d(8.75, 66.5, 8.5), accepted.placement().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.25, 1, .1)));
            var capture = capture(fixture, actor);
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var verified = assertInstanceOf(VerifiedResize.class, CapsuleResizeValidator.validate(source,
                    target, capture, WINDOW, ACCURACY, budget, numeric));
            assertSame(target, verified.target());
            assertTrue(verified.matches(source, target, capture, budget, numeric));
            var foreign = new StoredDyadic.Work();
            var rejected = assertInstanceOf(Failed.class, CapsuleResizeValidator.validate(source,
                    target, capture, WINDOW, ACCURACY, budget, foreign));
            assertEquals(Failure.INVALID_INPUT, rejected.failure());
            assertEquals(1, foreign.units());
        }
    }

    @Test void outsideWindowCoverNeverQueriesIncompleteCandidates() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("window"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var accepted = accepted(actor, .5, 2, 66);
            var target = new PlayerBodyPlacement(new Vec3d(10.5, 66, 8.5), accepted.placement().attitude(),
                    accepted.placement().shape());
            var tight = new Aabb(new Vec3d(8.25, 65, 8.25), new Vec3d(10.75, 67, 8.75));
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(9, 66, 8), Blocks.STONE.getDefaultState());
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(fixture.world(), actor, tight, QueryPolicy.CAPSULE_CONTACT, SCAN));
            assertEquals(1, capture.boxes().size());
            var numeric = new StoredDyadic.Work();
            var pose = CapsulePlacementMath.pose(accepted.placement(), numeric);
            var source = CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric);
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var failure = assertInstanceOf(Failed.class, CapsuleResizeValidator.validate(source,
                    target, capture, tight, ACCURACY, budget, numeric));
            assertEquals(Failure.WINDOW_TOO_SMALL, failure.failure());
            assertEquals(2, failure.queries().size());
            assertTrue(failure.queries().stream().allMatch(q -> q.stage() == Stage.START || q.stage() == Stage.TARGET));
            assertEquals(2, budget.calls());
        }
    }

    private static CapsuleResizeValidator.Result validate(PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
            PlayerBodyPlacement target, WorldCollisionCandidates.Snapshot capture,
            CapsuleContactQueryBudget budget, StoredDyadic.Work numeric) {
        return CapsuleResizeValidator.validate(CapsuleResizeValidator.PathSource.actualFree(accepted, pose, numeric),
                target, capture, WINDOW, ACCURACY, budget, numeric);
    }
    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture fixture, EntityPlayerMP actor) {
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(fixture.world(), actor, WINDOW, QueryPolicy.CAPSULE_CONTACT, SCAN));
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
