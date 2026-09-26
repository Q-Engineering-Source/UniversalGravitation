package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.List;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCast3d;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.Kind;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.NormalEstimate;
import dev.crlhitbox.api.geometry.cast.CapsuleAabbCastResult3d.NormalSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static wi.unigrav.core.player.CapsuleTranslationResolver.Disposition.*;
import static wi.unigrav.core.player.CapsuleTranslationResolver.FailureKind.*;
import static wi.unigrav.core.player.CapsuleTranslationResolver.Stage.*;

class CapsuleTranslationResolverTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
    private static final CapsuleTranslationResolver.Limits LIMITS = new CapsuleTranslationResolver.Limits(64, 512);
    private static final Aabb TOUCHING_WALL = box(0.25, -2, -2, 1.25, 2, 2);

    @Test
    void clearAndEmptySnapshotsRetainTheOneRequestAndExactNumericalEndpoint() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(2, 0, 0));
        for (List<Aabb> boxes : List.of(List.<Aabb>of(), List.of(box(3, -1, -1, 4, 1, 1)))) {
            var result = CapsuleTranslationResolver.resolve(tube, boxes, LIMITS);
            assertEquals(FULL, result.disposition());
            assertEquals(1, result.fraction());
            assertEquals(new Vec3d(2, 0, 0), result.end().placement().center());
            assertSame(tube, result.request());
            assertTrue(result.failure().isEmpty());
            assertEquals(2 * boxes.size(), result.queriesUsed());
        }
    }

    @Test
    void multipleThinWallsUseTheMinimumPaddedPrefixIndependentOfEnumerationOrder() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(2, 0, 0));
        Aabb first = box(1, -0.125, -0.5, 1.125, 0.125, 0.5);
        Aabb second = box(0.5, -0.125, -0.5, 0.625, 0.125, 0.5);
        double expected = Math.min(CapsuleAabbCast3d.cast(tube.queryCapsule(), tube.delta(), first).safePrefixEnd().orElseThrow(),
                CapsuleAabbCast3d.cast(tube.queryCapsule(), tube.delta(), second).safePrefixEnd().orElseThrow());
        var a = CapsuleTranslationResolver.resolve(tube, List.of(first, second), LIMITS);
        var b = CapsuleTranslationResolver.resolve(tube, List.of(second, first), LIMITS);
        assertEquals(LIMITED, a.disposition());
        assertEquals(expected, a.fraction());
        assertEquals(a.end().placement(), b.end().placement());
        assertTrue(a.fraction() > 0 && a.fraction() < 0.125);
        assertEquals(4, a.queriesUsed());
        for (Aabb wall : List.of(first, second)) {
            assertEquals(Kind.CLEAR_SEPARATED, CapsuleAabbCast3d.cast(a.end().capsule(), ZERO, wall).kind());
        }
        assertSame(first, a.evidence().getFirst().obstacle());
        assertEquals(0, a.evidence().getFirst().candidateIndex());
        assertEquals(PADDED_PATH, a.evidence().getFirst().stage());
        assertEquals(tube.requestedDisplacementUpper(), a.request().requestedDisplacementUpper());
    }

    @Test
    void realTangentStartRequiresItsOwnActualContactEvidence() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(0, 0.5, 0));
        var result = CapsuleTranslationResolver.resolve(tube, List.of(TOUCHING_WALL), LIMITS);
        assertEquals(FULL, result.disposition());
        assertEquals(new Vec3d(0, 0.5, 0), result.end().placement().center());
        assertTrue(result.evidence().stream().anyMatch(e -> e.stage() == ACTUAL_INITIAL_PATH
                && e.query().kind() == Kind.CLEAR_INITIAL_TANGENT));
        assertTrue(result.evidence().stream().anyMatch(e -> e.stage() == ACTUAL_FINAL_POSE
                && e.query().kind() == Kind.CLEAR_INITIAL_TANGENT));
    }

    @Test
    void outwardDetachmentMayLeaveTheContactBandButInwardMotionIsRejected() {
        var outward = tube(ZERO, Rotation3d.identity(), new Vec3d(-0.5, 0, 0));
        var accepted = CapsuleTranslationResolver.resolve(outward, List.of(TOUCHING_WALL), LIMITS);
        assertEquals(FULL, accepted.disposition());
        var initial = CapsuleAabbCast3d.cast(outward.start().capsule(), outward.delta(), TOUCHING_WALL);
        assertEquals(Kind.CLEAR_INITIAL_OUTWARD, initial.kind());
        assertTrue(CapsulePlacementMath.preservesInitialContact(outward, accepted.end(), TOUCHING_WALL,
                initial.normalEstimate().orElseThrow(), initial.scale().orElseThrow().contactBandWidth()));
        var inward = tube(ZERO, Rotation3d.identity(), new Vec3d(0.1, 0, 0));
        var rejected = CapsuleTranslationResolver.resolve(inward, List.of(TOUCHING_WALL), LIMITS);
        assertRejectedAtStart(rejected, NO_USABLE_PATH);
        assertEquals(1, rejected.queriesUsed());
        assertEquals(PADDED_PATH, rejected.evidence().getFirst().stage());
        assertTrue(rejected.evidence().stream().anyMatch(e -> e.query().kind() == Kind.HIT_INITIAL_INWARD));
    }

    @Test
    void tangentContactAndAnotherBlockingBoxDoNotShareOrOverrideEvidence() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(0, 2, 0));
        Aabb ceiling = box(-1, 1.5, -1, 1, 1.625, 1);
        var result = CapsuleTranslationResolver.resolve(tube, List.of(TOUCHING_WALL, ceiling), LIMITS);
        assertEquals(LIMITED, result.disposition());
        assertTrue(result.fraction() > 0 && result.fraction() < 0.25);
        assertTrue(result.evidence().stream().anyMatch(e -> e.candidateIndex() == 0 && e.stage() == ACTUAL_INITIAL_PATH));
        assertTrue(result.evidence().stream().anyMatch(e -> e.candidateIndex() == 1 && e.stage() == ACTUAL_FINAL_POSE
                && e.query().kind() == Kind.CLEAR_SEPARATED));
    }

    @Test
    void overlapAndUnsupportedCandidatesCannotBeSilentlyDropped() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(2, 0, 0));
        assertRejectedAtStart(CapsuleTranslationResolver.resolve(tube, List.of(box(-1, -1, -1, 1, 1, 1)), LIMITS),
                NO_USABLE_PATH);
        var oversized = CapsuleTranslationResolver.resolve(tube, List.of(box(3, -1, -1, 200, 1, 1)), LIMITS);
        assertRejectedAtStart(oversized, UNSUPPORTED_DOMAIN);
        assertEquals(1, oversized.queriesUsed());
        assertEquals(PADDED_PATH, oversized.evidence().getFirst().stage());
        assertEquals(Kind.UNRESOLVED, oversized.evidence().getFirst().query().kind());
    }

    @Test
    void queryAndCandidateLimitsRejectAtomicallyWithoutCreatingAdditionalCredit() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(0, 0.5, 0));
        for (int budget : new int[]{1, 2}) {
            var result = CapsuleTranslationResolver.resolve(tube, List.of(TOUCHING_WALL),
                    new CapsuleTranslationResolver.Limits(1, budget));
            assertRejectedAtStart(result, QUERY_LIMIT);
            assertEquals(budget, result.queriesUsed());
            assertSame(tube, result.request());
            assertEquals(tube.requestedDisplacementUpper(), result.request().requestedDisplacementUpper());
        }
        var tooMany = CapsuleTranslationResolver.resolve(tube, List.of(TOUCHING_WALL, TOUCHING_WALL),
                new CapsuleTranslationResolver.Limits(1, 16));
        assertRejectedAtStart(tooMany, CANDIDATE_LIMIT);
        assertEquals(0, tooMany.queriesUsed());
    }

    @Test
    void zeroAndUnrepresentableDisplacementsDoNotSkipActualCollisionClassification() {
        var zero = tube(ZERO, Rotation3d.identity(), ZERO);
        var contact = CapsuleTranslationResolver.resolve(zero, List.of(TOUCHING_WALL), LIMITS);
        assertEquals(UNCHANGED, contact.disposition());
        assertSame(zero.start(), contact.end());
        assertTrue(contact.queriesUsed() > 0);
        var overlap = CapsuleTranslationResolver.resolve(zero, List.of(box(-1, -1, -1, 1, 1, 1)), LIMITS);
        assertRejectedAtStart(overlap, NO_USABLE_PATH);
        var tiny = tube(new Vec3d(30_000_000, 0, 0), Rotation3d.identity(), new Vec3d(Double.MIN_VALUE, 0, 0));
        var unchanged = CapsuleTranslationResolver.resolve(tiny, List.of(), LIMITS);
        assertEquals(UNCHANGED, unchanged.disposition());
        assertEquals(0, unchanged.request().requestedDisplacementUpper());
    }

    @Test
    void arbitraryBodyAxisAndWorldBorderScaleUseTheSameRealQueryPipeline() {
        Vec3d center = new Vec3d(30_000_000, 257, -30_000_000);
        var tube = tube(center, new Rotation3d(1, 2, 3, 4), new Vec3d(2, 0, 0));
        var wall = box(30_000_001.5, 255, -30_000_002, 30_000_001.625, 259, -29_999_998);
        var result = CapsuleTranslationResolver.resolve(tube, List.of(wall), LIMITS);
        assertEquals(LIMITED, result.disposition());
        assertEquals(Kind.CLEAR_SEPARATED, CapsuleAabbCast3d.cast(result.end().capsule(), ZERO, wall).kind());
        assertEquals(tube.start().placement().attitude(), result.end().placement().attitude());
    }

    @Test
    void contactCertificateRejectsWrongNormalScopeInwardProjectionAndChangedShape() {
        var tube = tube(ZERO, Rotation3d.identity(), new Vec3d(0, 0.5, 0));
        var end = CapsulePlacementMath.atPrefix(tube, 1);
        var initial = CapsuleAabbCast3d.cast(tube.start().capsule(), tube.delta(), TOUCHING_WALL);
        var normal = initial.normalEstimate().orElseThrow();
        double band = initial.scale().orElseThrow().contactBandWidth();
        assertTrue(CapsulePlacementMath.preservesInitialContact(tube, end, TOUCHING_WALL, normal, band));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, end, TOUCHING_WALL,
                new NormalEstimate(normal.direction(), 0, NormalSource.LAST_SEPARATED), band));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, end, TOUCHING_WALL,
                new NormalEstimate(normal.direction(), 0.5, NormalSource.CURRENT_WITNESS), band));
        var inward = CapsulePlacementMath.pose(free(new Vec3d(0.01, 0, 0), Rotation3d.identity()));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, inward, TOUCHING_WALL, normal, band));
        var rotated = CapsulePlacementMath.pose(free(ZERO, new Rotation3d(1, 0, 0, 0)));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, rotated, TOUCHING_WALL, normal, band));
        var resized = CapsulePlacementMath.pose(new PlayerBodyPlacement(ZERO, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(0.5, 1.5, 1))));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, resized, TOUCHING_WALL, normal, band));
        assertFalse(CapsulePlacementMath.preservesInitialContact(tube, end, box(2, -2, -2, 3, 2, 2), normal, band));
    }

    @Test
    void resultEvidenceIsImmutableAndCardinalMovementCannotUseTheInscribedCapsule() {
        var candidates = new ArrayList<>(List.of(TOUCHING_WALL));
        var result = CapsuleTranslationResolver.resolve(tube(ZERO, Rotation3d.identity(), new Vec3d(0, 0.5, 0)), candidates, LIMITS);
        int queries = result.queriesUsed();
        candidates.clear();
        assertEquals(queries, result.queriesUsed());
        assertThrows(UnsupportedOperationException.class, () -> result.evidence().clear());
        var cardinal = new PlayerBodyPlacement(ZERO, Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, SIZE));
        var request = CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(cardinal), new Vec3d(1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CapsuleTranslationResolver.resolve(request, List.of(), LIMITS));
    }

    private static void assertRejectedAtStart(CapsuleTranslationResolver.Result result,
                                               CapsuleTranslationResolver.FailureKind kind) {
        assertEquals(REJECTED, result.disposition());
        assertSame(result.request().start(), result.end());
        assertEquals(0, result.fraction());
        assertEquals(kind, result.failure().orElseThrow().kind());
    }

    private static CapsulePlacementMath.Translation tube(Vec3d center, Rotation3d q, Vec3d delta) {
        return CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(free(center, q)), delta);
    }

    private static PlayerBodyPlacement free(Vec3d center, Rotation3d q) {
        return new PlayerBodyPlacement(center, q, new PlayerBodyShape.FreeCapsule(SIZE));
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }
}
