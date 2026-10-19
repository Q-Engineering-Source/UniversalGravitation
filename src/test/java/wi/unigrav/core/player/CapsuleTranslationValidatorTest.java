package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.PrefixKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Reason;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CapsuleTranslationValidator.Failed;
import wi.unigrav.core.player.CapsuleTranslationValidator.Failure;
import wi.unigrav.core.player.CapsuleTranslationValidator.Query;
import wi.unigrav.core.player.CapsuleTranslationValidator.Stage;
import wi.unigrav.core.player.CapsuleTranslationValidator.VerifiedTranslation;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import static org.junit.jupiter.api.Assertions.*;

/** Native WorldServer capture and actual CHB calls; this does not claim movement-owner acceptance. */
class CapsuleTranslationValidatorTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004461");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000004462");
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits QUERY = new QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(10000, 16, 100, 100);
    private static final Aabb WINDOW = new Aabb(new Vec3d(5, 62, 5), new Vec3d(12, 70, 12));
    private static final Vec3d AXIS = new Vec3d(0, 1, 0);

    @Test void freeTranslationReturnsFullTargetProofAndContinuesOnSameContext() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("empty"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var capture = capture(fixture, actor, WINDOW);
            assertTrue(capture.boxes().isEmpty());
            var work = new StoredDyadic.Work();
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 128), work);
            long before = work.units();
            var proof = assertInstanceOf(VerifiedTranslation.class,
                    CapsuleTranslationValidator.validateToCenter(source, new Vec3d(9.5, 66, 8.5)));

            assertSame(source, proof.source());
            assertEquals(new Vec3d(1, 0, 0), proof.delta());
            assertEquals(new Vec3d(9.5, 66, 8.5), proof.actualEnd().placement().center());
            assertEquals(1.0, proof.centerPathUpper());
            assertEquals(before, work.units() - proof.numericWork());
            assertTrue(proof.queries().isEmpty());

            var next = CapsulePathSource.continueAfter(proof, work);
            assertTrue(source.sameContext(next));
            assertSame(actual, next.actualSource());
            assertSame(capture, next.capture());
            assertSame(source.queries(), next.queries());
            assertSame(work, next.work());
            assertSame(proof.target(), next.start().placement());
            assertEquals(1, next.completedSegments());
            assertTrue(next.current());

            actor.setPosition(actor.posX + .1, actor.posY, actor.posZ);
            assertThrows(CapsulePathSource.InvalidSourceException.class,
                    () -> CapsulePathSource.continueAfter(proof, work));
        }
    }

    @Test void zeroDeltaUsesWholeIntervalCertificatesForEverySeparatedOrdinal() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("zero-ordinals"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            chunk.setBlockState(new BlockPos(6, 66, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(11, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            assertEquals(2, capture.boxes().size());

            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 64);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var proof = assertInstanceOf(VerifiedTranslation.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(0, 0, 0)));

            assertEquals(2, proof.queries().size());
            for (int ordinal = 0; ordinal < 2; ordinal++) {
                var query = proof.queries().get(ordinal);
                assertEquals(Stage.PATH, query.stage());
                assertEquals(ordinal, query.ordinal());
                assertEquals(capture.boxes().get(ordinal), query.box());
                assertNotNull(query.planeProof());
                assertTrue(query.planeProof().matches(proof.tube(), query.box()));
                assertNull(query.actualContact(), "a whole-interval certificate is not a fabricated contact");
                assertNull(query.paddedEnvelopeBoundary());
            }
            assertEquals(0, budget.calls(), "whole-interval exact separation already covers both endpoints");
        }
    }

    @Test void safeActualEndpointsAndACrossedWallRetryOnlyNoPrefixOnce() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("wall"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(9, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            assertEquals(1, capture.boxes().size());
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var result = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(2, 0, 0)));

            assertTrue(result.queries().stream().filter(q -> q.stage() == Stage.START || q.stage() == Stage.TARGET)
                    .allMatch(q -> q.actualContact().relation() == Relation.SEPARATED));
            var pathEvidence = result.queries().stream()
                    .filter(query -> query.stage() == Stage.PATH && query.paddedEnvelopeBoundary() != null)
                    .toList();
            assertEquals(2, pathEvidence.size(), "only NO_PREFIX_CERTIFICATE receives one bounded retry");
            assertTrue(pathEvidence.stream().allMatch(query -> query.actualContact() == null),
                    "padded envelope contacts must not be represented as actual endpoint contacts");
            assertEquals(Failure.UNRESOLVED, result.failure());
            for (var query : pathEvidence) {
                var boundary = query.paddedEnvelopeBoundary();
                assertEquals(BoundaryKind.UNRESOLVED, boundary.kind());
                assertEquals(Reason.NO_PREFIX_CERTIFICATE, boundary.reason().orElseThrow());
                assertEquals(Relation.SEPARATED, boundary.startRelation());
                assertTrue(boundary.prefix().isEmpty());
                assertTrue(boundary.installation().isEmpty());
            }
            assertEquals(4, budget.calls(), "two actual endpoints, first path attempt, and exactly one retry");
            assertNull(result.budgetCause());
        }
    }

    @Test void wholeIntervalFarPlanesDoNotExhaustEndpointQueryPool() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("far-plane-pool"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            for (int x : new int[]{6, 11}) for (int y = 64; y < 68; y++) for (int z = 6; z < 11; z++)
                chunk.setBlockState(new BlockPos(x, y, z), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            assertEquals(40, capture.boxes().size());
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 2);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var proof = assertInstanceOf(VerifiedTranslation.class, CapsuleTranslationValidator.validate(source, new Vec3d(0, .125, 0)));
            assertEquals(0, budget.calls());
            assertFalse(budget.terminal());
            assertEquals(40, proof.queries().size());
            for (int ordinal = 0; ordinal < 40; ordinal++) {
                var query = proof.queries().get(ordinal);
                assertEquals(ordinal, query.ordinal());
                assertEquals(capture.boxes().get(ordinal), query.box());
                assertEquals(Stage.PATH, query.stage());
                assertNotNull(query.planeProof());
                assertTrue(query.planeProof().matches(proof.tube(), query.box()));
                assertNull(query.actualContact());
            }
            assertTrue(source.current());
        }
    }

    @Test void certainOverlapIsNotMaskedByUnrelatedEndpointBudget() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("overlap-before-budget"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(6, 66, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(8, 66, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(11, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 1);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var failed = assertInstanceOf(Failed.class, CapsuleTranslationValidator.validate(source, new Vec3d(0, 0, 0)));
            assertEquals(Failure.ACTUAL_OVERLAP, failed.failure());
            assertEquals(1, budget.calls());
            assertTrue(budget.terminal(), "the contact consumed the last allowed call; its known overlap still wins");
            assertTrue(failed.queries().stream().anyMatch(q -> q.actualContact() != null && q.actualContact().relation() == Relation.OVERLAPPING));
            assertTrue(source.current());
        }
    }

    @Test void sparseEndpointReceiptsStayBoundToTheirCaptureOrdinal() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("sparse-ordinals"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(6, 66, 8), Blocks.STONE.getDefaultState());
            chunk.setBlockState(new BlockPos(9, 66, 9), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 3);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var proof = assertInstanceOf(VerifiedTranslation.class, CapsuleTranslationValidator.validate(source, new Vec3d(.2, 0, .2)));
            assertEquals(3, budget.calls());
            assertEquals(2, proof.queries().stream().filter(q -> q.actualContact() != null).count());
            for (var query : proof.queries()) assertEquals(capture.boxes().get(query.ordinal()), query.box());
            assertEquals(1, proof.queries().stream().filter(q -> q.planeProof() != null).count());
            assertEquals(1, proof.queries().stream().filter(q -> q.paddedEnvelopeBoundary() != null).count());
        }
    }

    @Test void diagonalSeparatedPathConsumesTheFullChbReceiptForTheInstalledTarget() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("diagonal-full-receipt"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            chunk.setBlockState(new BlockPos(9, 66, 9), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            assertEquals(1, capture.boxes().size());
            assertEquals(new Aabb(new Vec3d(9, 66, 9), new Vec3d(10, 67, 10)), capture.boxes().getFirst());

            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);
            var chbArtifact = Path.of(System.getProperty("unigrav.test.chbArtifact")).toRealPath();
            var loadedArtifact = Path.of(Capsule.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toRealPath();
            assertEquals(chbArtifact, loadedArtifact, "the real CHB class must load from the selected runtime JAR");

            var outcome = CapsuleTranslationValidator.validate(source, new Vec3d(.2, 0, .2));
            var proof = assertInstanceOf(VerifiedTranslation.class, outcome, "actual outcome: " + outcome);

            var actualEndpointEvidence = proof.queries().stream()
                    .filter(query -> query.stage() == Stage.START || query.stage() == Stage.TARGET)
                    .toList();
            assertEquals(2, actualEndpointEvidence.size());
            assertEquals(1, actualEndpointEvidence.stream().filter(query -> query.stage() == Stage.START).count());
            assertEquals(1, actualEndpointEvidence.stream().filter(query -> query.stage() == Stage.TARGET).count());
            assertTrue(actualEndpointEvidence.stream().allMatch(query -> query.actualContact() != null
                            && query.actualContact().relation() == Relation.SEPARATED),
                    "the real actual-radius START and TARGET observations must both be separated");

            var pathEvidence = proof.queries().stream().filter(query -> query.stage() == Stage.PATH).toList();
            assertEquals(1, pathEvidence.size());
            var path = pathEvidence.getFirst();
            assertEquals(0, path.ordinal());
            assertNull(path.planeProof(), "the diagonal sweep has no supported whole-axis plane certificate");
            assertNull(path.actualContact(), "PATH evidence is not an actual endpoint contact receipt");
            assertNotNull(path.paddedEnvelopeBoundary());
            var boundary = path.paddedEnvelopeBoundary();
            assertEquals(Relation.SEPARATED, boundary.startRelation());
            assertTrue(boundary.kind() == BoundaryKind.SEPARATED_FULL
                            || boundary.kind() == BoundaryKind.NONPENETRATING_FULL
                            || boundary.kind() == BoundaryKind.CERTIFIED_BOUNDARY,
                    "the consumer accepts only a full-path boundary result: " + boundary);
            var prefix = boundary.prefix().orElseThrow();
            assertTrue(prefix.kind() == PrefixKind.CLEAR_SEPARATED || prefix.kind() == PrefixKind.NONPENETRATING);
            assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(prefix.endFraction()));
            var installed = boundary.installation().orElseThrow();
            assertEquals(Double.doubleToRawLongBits(1.0), Double.doubleToRawLongBits(installed.fraction()));
            assertRawVector(proof.actualEnd().placement().center(), installed.storedCenter());
            assertRawVector(proof.actualEnd().storedHalfLine(), installed.halfLine());
            assertRawVector(proof.actualEnd().capsule().centerline().start(),
                    installed.storedCapsule().centerline().start());
            assertRawVector(proof.actualEnd().capsule().centerline().end(),
                    installed.storedCapsule().centerline().end());
            assertEquals(Double.doubleToRawLongBits(proof.tube().queryCapsule().radius()),
                    Double.doubleToRawLongBits(installed.storedCapsule().radius()));
            assertNotEquals(Double.doubleToRawLongBits(proof.actualEnd().capsule().radius()),
                    Double.doubleToRawLongBits(installed.storedCapsule().radius()),
                    "the installed contact geometry is the padded proof envelope");
            assertEquals(Relation.SEPARATED, installed.storedContact().relation());
            assertEquals(3, budget.calls(), "two endpoint observations plus one real full-path refinement");

            var next = CapsulePathSource.continueAfter(proof, work);
            assertTrue(source.sameContext(next));
            assertSame(work, next.work());
            assertSame(budget, next.queries());
            assertEquals(proof.actualEnd().placement(), next.start().placement());
        }
    }

    @Test void exactCenterRequestRejectsTheKnownRoundToNearestCounterexampleBeforeQueries() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("raw-target"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            for (int chunkX = -3; chunkX < 0; chunkX++) {
                var chunk = new Chunk(fixture.world(), chunkX, 0);
                chunk.markLoaded(true);
                fixture.world().getChunkProvider().loadedChunks.put(ChunkPos.asLong(chunkX, 0), chunk);
            }
            var center = new Vec3d(-32, 66, 8.5);
            var actual = accepted(actor, .5, 2, center);
            var wide = new Aabb(new Vec3d(-34, 62, 5), new Vec3d(4, 70, 12));
            var capture = capture(fixture, actor, wide);
            assertTrue(capture.boxes().isEmpty());
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, wide, ACCURACY, budget, work);
            var exactTarget = new Vec3d(Math.nextUp(1.0), 66, 8.5);

            var failure = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validateToCenter(source, exactTarget));

            assertEquals(33.0, exactTarget.x() - center.x());
            assertEquals(Failure.TARGET_NOT_REALIZED, failure.failure());
            assertTrue(failure.queries().isEmpty());
            assertEquals(0, budget.calls());
            assertNull(failure.budgetCause(), "this is a normal exact-target refusal, not budget exhaustion");
        }
    }

    @Test void numericExhaustionKeepsTheOriginalCauseAndNeverEntersChb() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("numeric-limit"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var capture = capture(fixture, actor, WINDOW);
            var work = new StoredDyadic.Work();
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 32), work);
            work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 64);
            var budget = source.queries();
            long usedBeforeAttempt = work.units();

            var failure = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(1, 0, 0)));

            assertEquals(Failure.NUMERIC_BUDGET, failure.failure());
            var original = assertInstanceOf(NumericWorkExhaustedException.class, failure.budgetCause());
            assertEquals("charge", original.getStackTrace()[0].getMethodName());
            assertEquals(StoredDyadic.MAX_WORK_UNITS - 64, original.used());
            assertEquals(128, original.requested(), "the validator's result-shell reserve is prepaid");
            assertEquals(StoredDyadic.MAX_WORK_UNITS, original.limit());
            assertEquals(0, budget.calls());
            assertEquals(0, failure.numericWork(), "rejected precharge consumes no ledger work");
            assertEquals(usedBeforeAttempt, work.units(), "the failed reserve must not mutate the shared ledger");
        }
    }

    @Test void queryBudgetRefusalRetainsItsCauseAfterBothEndpointReceipts() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("query-limit"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                    .setBlockState(new BlockPos(9, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 2);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);

            var failure = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(2, 0, 0)));

            assertEquals(Failure.QUERY_BUDGET, failure.failure());
            var original = assertInstanceOf(CapsuleContactQueryBudget.BudgetExhausted.class, failure.budgetCause());
            assertEquals("enter", original.getStackTrace()[0].getMethodName());
            assertEquals(2, failure.queries().size());
            assertEquals(3, budget.calls());
            assertTrue(budget.terminal());
            assertTrue(failure.numericWork() > 0);
        }
    }

    @Test void sixteenthLineagePhaseIsTheLastOneAndTheNextTranslationDoesNotQuery() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("phase-limit"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, .5, 2, new Vec3d(8.5, 66, 8.5));
            var capture = capture(fixture, actor, WINDOW);
            assertTrue(capture.boxes().isEmpty());
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 32);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, ACCURACY, budget, work);

            for (int segment = 0; segment < CapsulePathSource.MAX_SEGMENTS; segment++) {
                var outcome = CapsuleArcValidator.validate(source, source.start().placement(), AXIS);
                var proof = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class, outcome,
                        "phase=" + segment + " outcome=" + outcome + " units=" + work.units()
                                + " calls=" + budget.calls() + " chb=" + budget.work());
                source = CapsulePathSource.continueAfter(proof, work);
                assertEquals(segment + 1, source.completedSegments());
            }

            long callsBeforeRejectedTranslation = budget.calls();
            var failure = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(1, 0, 0)));
            assertEquals(Failure.PHASE_LIMIT, failure.failure());
            assertTrue(failure.queries().isEmpty());
            assertEquals(callsBeforeRejectedTranslation, budget.calls());
            assertEquals(0, budget.calls());
            assertEquals(CapsulePathSource.MAX_SEGMENTS, source.completedSegments());
        }
    }

    @Test void cardinalContainmentTranslationResizeArcAndReturnShareOneFreeContinuation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("cardinal-chain"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = acceptedCardinal(actor);
            var virtual = new PlayerBodyPlacement(actual.placement().center(), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.25, 1, .75)));
            var work = new StoredDyadic.Work();
            var contained = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(actual, virtual, work));
            var capture = capture(fixture, actor, WINDOW);
            var budget = new CapsuleContactQueryBudget(QUERY, 128);
            var source = CapsulePathSource.containedCardinal(contained, CapsulePlacementMath.pose(virtual, work),
                    capture, WINDOW, ACCURACY, budget, work);

            var translation = assertInstanceOf(VerifiedTranslation.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(.25, 0, 0)));
            var afterTranslation = CapsulePathSource.continueAfter(translation, work);
            var grown = new PlayerBodyPlacement(translation.target().center(), translation.target().attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.4, 1.5, 1.125)));
            var resize = assertInstanceOf(CapsuleResizeValidator.VerifiedResize.class,
                    CapsuleResizeValidator.validate(afterTranslation, grown));
            var afterResize = CapsulePathSource.continueAfter(resize, work);
            var turned = new PlayerBodyPlacement(grown.center(), new Rotation3d(0, .5, 0, Math.sqrt(.75)), grown.shape());
            var arc = assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(afterResize, turned, AXIS));
            var afterArc = CapsulePathSource.continueAfter(arc, work);
            var returned = assertInstanceOf(VerifiedTranslation.class,
                    CapsuleTranslationValidator.validateToCenter(afterArc, virtual.center()));

            assertEquals(4, returned.completedSegments());
            assertEquals(virtual.center(), returned.actualEnd().placement().center());
            assertEquals(.25, returned.centerPathUpper());
            assertSame(actual, afterArc.actualSource());
            assertSame(capture, afterArc.capture());
            assertSame(budget, afterArc.queries());
            assertSame(work, afterArc.work());
            assertTrue(source.sameContext(afterArc));
            assertTrue(returned.numericWork() > returned.centerPathUpper());
            assertInstanceOf(PlayerBodyShape.Cardinal.class, actual.placement().shape());
        }
    }

    @Test void nonNominalStoredHalfLineAndCanonicalEndpointSurviveTranslation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("stored-half-line"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new StoredDyadic.Work();
            var dimensions = new PlayerBodyShape.Dimensions(.5, 2, 1.5);
            var attitude = Rotation3d.identity();
            var reference = new Vec3d(8.5, 66, 8.5);
            var center = new Vec3d(8.5, Math.nextUp(66.0), 8.5);
            var limits = StoredRealizationLimits.derive(reference, attitude, dimensions, work);
            double support = 65.0;
            var halfLine = new Vec3d(0, center.y() - (support + dimensions.radius()), 0);
            var relation = RealizationRelation.axisPlane(limits, 1, 1, support, center, halfLine, work);
            var geometry = StoredCapsuleGeometry.fromRecipe(center, attitude, dimensions, halfLine, relation);
            var placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(center, attitude,
                    new PlayerBodyShape.FreeCapsule(dimensions), geometry, geometry.capsule().bounds(),
                    new Vec3d(center.x(), geometry.capsule().bounds().min().y(), center.z()));
            installActorGeometry(actor, placement);
            var nominal = new PlayerBodyPlacement(center, attitude, new PlayerBodyShape.FreeCapsule(dimensions));
            assertFalse(StoredRealizationLimits.sameRaw(geometry.storedHalfLine(),
                    nominal.storedCapsuleGeometry().storedHalfLine()));
            assertTrue(geometry.negativeEndpointCanonicalStart());
            var actual = snapshot(actor, placement);
            var capture = capture(fixture, actor, WINDOW);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(placement, work),
                    capture, WINDOW, ACCURACY, new CapsuleContactQueryBudget(QUERY, 64), work);

            var proof = assertInstanceOf(VerifiedTranslation.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(.25, 0, 0)));

            var endGeometry = proof.actualEnd().placement().storedCapsuleGeometry();
            assertTrue(StoredRealizationLimits.sameRaw(geometry.storedHalfLine(), endGeometry.storedHalfLine()));
            assertEquals(geometry.negativeEndpointCanonicalStart(), endGeometry.negativeEndpointCanonicalStart());
            assertEquals(geometry.negativeEndpointCanonicalParameter(),
                    endGeometry.negativeEndpointCanonicalParameter());
            assertTrue(source.current());
        }
    }

    @Test void unresolvedEndpointDoesNotHideLaterCertainOverlap() throws Exception {
        assertAccuracyLimitedEndpointPriority(true);
    }

    @Test void accuracyLimitedEndpointWithoutLaterOverlapRemainsUnresolved() throws Exception {
        assertAccuracyLimitedEndpointPriority(false);
    }

    private void assertAccuracyLimitedEndpointPriority(boolean includeOverlap) throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("accuracy-priority-" + includeOverlap), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var actual = accepted(actor, 1.5, 2, new Vec3d(8.5, 66, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(7, 66, 7), Blocks.STONE.getDefaultState());
            if (includeOverlap) chunk.setBlockState(new BlockPos(8, 66, 8), Blocks.STONE.getDefaultState());
            var capture = capture(fixture, actor, WINDOW);
            assertEquals(includeOverlap ? 2 : 1, capture.boxes().size());
            var work = new StoredDyadic.Work();
            var budget = new CapsuleContactQueryBudget(QUERY, 64);
            // Nonzero irrational distance needs a binary64 enclosure; zero distance is exact.
            var accuracy = new ContactAccuracy(1e-30, 1e-6, 1e-5);
            var source = CapsulePathSource.actualFree(actual, CapsulePlacementMath.pose(actual.placement(), work),
                    capture, WINDOW, accuracy, budget, work);
            var result = assertInstanceOf(Failed.class,
                    CapsuleTranslationValidator.validate(source, new Vec3d(0, 0, 0)));
            assertEquals(includeOverlap ? Failure.ACTUAL_OVERLAP : Failure.UNRESOLVED, result.failure());
            assertEquals(includeOverlap ? 3 : 2, result.queries().size());
            for (int index = 0; index < 2; index++) {
                Query query = result.queries().get(index);
                assertEquals(index == 0 ? Stage.START : Stage.TARGET, query.stage());
                assertEquals(0, query.ordinal());
                assertEquals(capture.boxes().get(0), query.box());
                assertEquals(Relation.UNRESOLVED, query.actualContact().relation());
                assertEquals(Reason.ACCURACY_UNREACHABLE, query.actualContact().reason().orElseThrow());
                assertNull(query.planeProof());
            }
            if (includeOverlap) {
                Query overlap = result.queries().get(2);
                assertEquals(Stage.START, overlap.stage());
                assertEquals(1, overlap.ordinal());
                assertEquals(capture.boxes().get(1), overlap.box());
                assertEquals(Relation.OVERLAPPING, overlap.actualContact().relation());
            }
            assertFalse(budget.terminal(), "accuracy failure must not exhaust the shared query ledger");
            assertEquals(includeOverlap ? 3 : 2, budget.calls());
            assertNull(result.budgetCause());
            assertTrue(source.current());
        }
    }

    private static void assertRawVector(Vec3d expected, Vec3d actual) {
        assertEquals(Double.doubleToRawLongBits(expected.x()), Double.doubleToRawLongBits(actual.x()));
        assertEquals(Double.doubleToRawLongBits(expected.y()), Double.doubleToRawLongBits(actual.y()));
        assertEquals(Double.doubleToRawLongBits(expected.z()), Double.doubleToRawLongBits(actual.z()));
    }

    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture fixture,
            EntityPlayerMP actor, Aabb window) {
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(fixture.world(), actor, window, QueryPolicy.CAPSULE_CONTACT, SCAN));
    }

    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor, double width, double height, Vec3d center) {
        var placement = new PlayerBodyPlacement(center, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(width, height, height * .75)));
        installActorGeometry(actor, placement);
        return snapshot(actor, placement);
    }

    private static PlayerPhysicalSnapshot acceptedCardinal(EntityPlayerMP actor) {
        var dimensions = new PlayerBodyShape.Dimensions(.5, 2, 1.5);
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, 66, 8.5), Rotation3d.identity(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
        installActorGeometry(actor, placement);
        return snapshot(actor, placement);
    }

    private static PlayerPhysicalSnapshot snapshot(EntityPlayerMP actor, PlayerBodyPlacement placement) {
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), CONNECTION,
                actor.dimension, 1), 1, placement, new Vec3d(0, 0, 0));
    }

    private static void installActorGeometry(EntityPlayerMP actor, PlayerBodyPlacement placement) {
        actor.width = (float) placement.shape().dimensions().width();
        actor.height = (float) placement.shape().dimensions().height();
        var transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        var bounds = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
    }
}
