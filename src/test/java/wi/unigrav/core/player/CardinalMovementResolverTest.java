package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.player.CardinalMovementResolver.CollisionFlags;
import wi.unigrav.core.player.CardinalMovementResolver.Failure;
import wi.unigrav.core.player.CardinalMovementResolver.Limits;
import wi.unigrav.core.player.CardinalMovementResolver.Rejected;
import wi.unigrav.core.player.CardinalMovementResolver.Request;
import wi.unigrav.core.player.CardinalMovementResolver.Resolved;
import wi.unigrav.core.player.CardinalMovementResolver.StepChoice;

class CardinalMovementResolverTest {
    private static final AxisAlignedBB PLAYER = new AxisAlignedBB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final AxisAlignedBB GROUND = new AxisAlignedBB(-10, -1, -10, 10, 0, 10);
    private static final Limits LIMITS = new Limits(256, 4096, 32768);
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void strictQueryPolicyDoesNotInheritCapsuleWideningAtTheSneakEdge(CardinalFrame frame) {
        var exactPlayer = new AxisAlignedBB(-0.25, 0, -0.25, 0.25, 1.75, 0.25);
        var worldFloor = world(frame, new AxisAlignedBB(-2, -1, -2, 1, 0, 2));
        var request = new Request(frame, world(frame, exactPlayer), frame.toWorld(new Vec3d(1.25, -0.08, 0)),
                MoverType.SELF, true, true, 0.6);
        var scanLimits = new CollisionQueryFootprint.Limits(65_536, 64, 2048, 512);
        Function<CollisionQueryFootprint.QueryPolicy, Resolved> calculate = policy -> assertInstanceOf(Resolved.class,
                CardinalMovementResolver.resolve(request, probe -> {
                    var requested = new Aabb(new Vec3d(probe.minX, probe.minY, probe.minZ),
                            new Vec3d(probe.maxX, probe.maxY, probe.maxZ));
                    var nativeBounds = CollisionQueryFootprint.plan(requested, 2, policy, scanLimits).nativeBounds();
                    var actualQuery = new AxisAlignedBB(nativeBounds.min().x(), nativeBounds.min().y(), nativeBounds.min().z(),
                            nativeBounds.max().x(), nativeBounds.max().y(), nativeBounds.max().z());
                    return worldFloor.intersects(actualQuery) ? List.of(worldFloor) : List.of();
                }, LIMITS));
        var strict = calculate.apply(CollisionQueryFootprint.QueryPolicy.NATIVE_STRICT);
        var widenedCounterexample = calculate.apply(CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT);
        assertEquals(1.2, strict.postSneakLocalDelta().x(), 1e-12);
        assertEquals(1.25, widenedCounterexample.postSneakLocalDelta().x(), 0);
        assertTrue(strict.work().queries() > widenedCounterexample.work().queries());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void groundWallAndCeilingUsePhysicalAxesInEveryFrame(CardinalFrame frame) {
        var wall = new AxisAlignedBB(1, 0, -5, 2, 3, 5);
        var result = solve(frame, new Vec3d(2, -0.08, 0.5), true, false, 0, List.of(GROUND, wall));
        assertVector(new Vec3d(0.7, 0, 0.5), result.nativeResolvedLocalDelta());
        assertEquals(new CollisionFlags(true, true, false, true), result.flags());
        assertBox(world(frame, PLAYER.offset(0.7, 0, 0.5)), result.endBounds());
        assertVector(frame.toWorld(new Vec3d(0.7, 0, 0.5)), result.actualWorldBoundsDelta());
        assertEquals(1, result.queries().size());
        assertBox(world(frame, PLAYER.expand(2, -0.08, 0.5)), result.queries().getFirst().worldBounds());

        var ceiling = new AxisAlignedBB(-3, 2, -3, 3, 3, 3);
        var up = solve(frame, new Vec3d(0, 1, 0), false, false, 0, List.of(ceiling));
        assertEquals(0.2, up.nativeResolvedLocalDelta().y(), 1e-12);
        assertTrue(up.flags().y());
        assertFalse(up.flags().onGround());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void stepUsesSavedHorizontalRequestAndReportsNetRiseSeparately(CardinalFrame frame) {
        var ledge = new AxisAlignedBB(0.5, 0, -2, 2, 0.5, 2);
        var result = solve(frame, new Vec3d(1, -0.08, 0), true, false, 0.6, List.of(GROUND, ledge));
        assertEquals(StepChoice.B, result.step()); // Equal alternates select B.
        assertEquals(0.2, result.ordinaryResolvedLocalDelta().x(), 1e-12);
        assertEquals(1, result.nativeResolvedLocalDelta().x(), 0);
        assertEquals(0.6, result.attemptedStepRise(), 0);
        assertEquals(-0.1, result.attemptedStepDown(), 1e-12);
        assertEquals(-0.1, result.nativeResolvedLocalDelta().y(), 1e-12);
        assertEquals(0.5, result.actualLocalBoundsDelta().y(), 1e-12);
        assertTrue(result.flags().onGround());
        assertBox(world(frame, PLAYER.offset(1, 0.5, 0)), result.endBounds());
        assertEquals(2, result.queries().size());
        assertBox(world(frame, PLAYER.expand(1, 0.6, 0)), result.queries().get(1).worldBounds());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void sneakReducedRequestFeedsStepInsteadOfOriginalOrOrdinaryClippedRequest(CardinalFrame frame) {
        var shortGround = new AxisAlignedBB(-10, -1, -10, 1, 0, 10);
        var shortLedge = new AxisAlignedBB(0.5, 0, -2, 1, 0.5, 2);
        var result = solve(frame, new Vec3d(2, -0.08, 0), true, true, 0.6, List.of(shortGround, shortLedge));
        double afterSneak = result.postSneakLocalDelta().x();
        assertTrue(afterSneak > 1.2 && afterSneak < 1.3);
        assertEquals(0.2, result.ordinaryResolvedLocalDelta().x(), 1e-12);
        assertEquals(StepChoice.B, result.step());
        assertEquals(afterSneak, result.nativeResolvedLocalDelta().x(), 0);
        assertEquals(0.5, result.actualLocalBoundsDelta().y(), 1e-12);
        assertBox(world(frame, PLAYER.expand(afterSneak, 0.6, 0)), result.queries().getLast().worldBounds());
        assertTrue(result.queries().size() > 2);
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void ordinaryCornerClipsXBeforeZInEveryFrame(CardinalFrame frame) {
        var xWall = new AxisAlignedBB(1, -1, -1, 2, 3, 1);
        var zWall = new AxisAlignedBB(0.8, -1, 0.8, 2, 3, 1.2);
        var result = solve(frame, new Vec3d(2, 0, 2), false, false, 0, List.of(xWall, zWall));
        assertVector(new Vec3d(0.7, 0, 0.5), result.nativeResolvedLocalDelta());
        assertEquals(new CollisionFlags(true, false, true, false), result.flags());
        assertBox(world(frame, PLAYER.offset(0.7, 0, 0.5)), result.endBounds());
        // Moving Z first would miss both walls and reach (2, 0, 2), not this endpoint.
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void expandedRiseAlternateCanWinUnderFutureCeiling(CardinalFrame frame) {
        var ledge = new AxisAlignedBB(0.5, 0, -2, 2, 0.1, 2);
        var futureCeiling = new AxisAlignedBB(0.9, 2.2, -2, 3, 3, 2);
        var result = solve(frame, new Vec3d(1, -0.08, 0), true, false, 0.6,
                List.of(GROUND, ledge, futureCeiling));
        assertEquals(StepChoice.A, result.step());
        assertEquals(1, result.nativeResolvedLocalDelta().x(), 0);
        assertEquals(0.4, result.attemptedStepRise(), 1e-12);
        assertEquals(0.1, result.actualLocalBoundsDelta().y(), 1e-12);
        assertBox(world(frame, PLAYER.offset(1, 0.1, 0)), result.endBounds());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void equalStepAndOrdinaryProgressFallsBackWithoutRaisingBody(CardinalFrame frame) {
        var tallWall = new AxisAlignedBB(0.5, 0, -2, 2, 3, 2);
        var result = solve(frame, new Vec3d(1, -0.08, 0), true, false, 0.6, List.of(GROUND, tallWall));
        assertEquals(StepChoice.ORDINARY_FALLBACK, result.step());
        assertVector(new Vec3d(0.2, 0, 0), result.actualLocalBoundsDelta());
        assertEquals(result.ordinaryResolvedLocalDelta(), result.nativeResolvedLocalDelta());
        assertBox(world(frame, PLAYER.offset(0.2, 0, 0)), result.endBounds());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void airborneDownwardContactMayStepButAirborneSneakHasNoProtection(CardinalFrame frame) {
        var ledge = new AxisAlignedBB(0.5, 0, -2, 2, 0.5, 2);
        var landed = solve(frame, new Vec3d(1, -0.08, 0), false, false, 0.6, List.of(GROUND, ledge));
        assertEquals(StepChoice.B, landed.step());
        assertTrue(landed.flags().onGround());
        var airborne = solve(frame, new Vec3d(2, 0, 2), false, true, 0.6, List.of());
        assertVector(new Vec3d(2, 0, 2), airborne.postSneakLocalDelta());
        assertEquals(1, airborne.work().queries());
        assertFalse(airborne.flags().onGround());
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void sneakThirdLoopPreservesCornerSupportInEveryFrame(CardinalFrame frame) {
        // Two arms separately support X and Z probes; only the joint probe sees the missing corner.
        var xArm = new AxisAlignedBB(-1, -1, -1, 3, 0, 0.35);
        var zArm = new AxisAlignedBB(-1, -1, -1, 0.35, 0, 3);
        var result = solve(frame, new Vec3d(1, -0.08, 1), true, true, 0.6, List.of(xArm, zArm));
        assertTrue(result.postSneakLocalDelta().x() < 0.65);
        assertTrue(result.postSneakLocalDelta().x() > 0.59);
        assertEquals(result.postSneakLocalDelta().x(), result.postSneakLocalDelta().z(), 0);
        assertTrue(result.work().queries() > 3);
        assertBox(world(frame, PLAYER.offset(1, -0.6, 0)), result.queries().get(0).worldBounds());
        assertBox(world(frame, PLAYER.offset(0, -0.6, 1)), result.queries().get(1).worldBounds());
        assertBox(world(frame, PLAYER.offset(1, -0.6, 1)), result.queries().get(2).worldBounds());
        assertFalse(result.flags().x()); // Sneak reduction is not an obstacle collision flag.
        assertFalse(result.flags().z());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, -0.05, 0.05000000000000001, -0.05000000000000001, 0.049, -0.049})
    void exactSneakStepBoundariesConvergeWithoutOscillation(double value) {
        var result = solve(CardinalFrame.Y_POS, new Vec3d(value, 0, value), true, true, 0.6, List.of());
        assertVector(ZERO, result.postSneakLocalDelta());
        assertBox(PLAYER, result.endBounds());
        int probesPerAxis = Math.abs(value) > 0.05 ? 2 : 1;
        assertEquals(2 * probesPerAxis + 1, result.work().queries());
    }

    @Test
    void realNativeOffsetPrimitivesAndReorderedMultiBoxesAgree() {
        var farWall = new AxisAlignedBB(1.2, -1, -1, 1.3, 3, 1);
        var nearPlane = new AxisAlignedBB(0.8, -1, -1, 0.8, 3, 1);
        var first = solve(CardinalFrame.Y_POS, new Vec3d(2, 0, 0), false, false, 0, List.of(farWall, nearPlane));
        var reversed = solve(CardinalFrame.Y_POS, new Vec3d(2, 0, 0), false, false, 0, List.of(nearPlane, farWall));
        double nativeOffset = nearPlane.calculateXOffset(PLAYER, farWall.calculateXOffset(PLAYER, 2));
        assertEquals(nativeOffset, first.nativeResolvedLocalDelta().x(), 0);
        assertEquals(first.endBounds(), reversed.endBounds());
        assertEquals(0.5, first.nativeResolvedLocalDelta().x(), 1e-12);
        assertEquals(List.of(farWall, nearPlane), first.queries().getFirst().worldCandidates());
        assertThrows(UnsupportedOperationException.class, () -> first.queries().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.queries().getFirst().worldCandidates().clear());
    }

    @Test
    void budgetsRejectWholeMoveEvenAfterAnOrdinaryEndpointExists() {
        var ledge = new AxisAlignedBB(0.5, 0, -2, 2, 0.5, 2);
        var request = request(CardinalFrame.Y_POS, new Vec3d(1, -0.08, 0), true, false, 0.6);
        var queryLimit = rejected(request, fixture(List.of(GROUND, ledge)), new Limits(1, 100, 100));
        assertEquals(Failure.QUERY_LIMIT, queryLimit.failure());
        assertSame(PLAYER, queryLimit.endBounds());
        assertEquals(1, queryLimit.queries().size());
        var offsetLimit = rejected(request, fixture(List.of(GROUND, ledge)), new Limits(10, 100, 1));
        assertEquals(Failure.OFFSET_LIMIT, offsetLimit.failure());
        assertSame(PLAYER, offsetLimit.endBounds());

        List<AxisAlignedBB> untraversable = new AbstractList<>() {
            @Override public AxisAlignedBB get(int index) { throw new AssertionError("must check count before traversal"); }
            @Override public int size() { return 3; }
        };
        var candidateLimit = rejected(request, bounds -> untraversable, new Limits(10, 2, 100));
        assertEquals(Failure.CANDIDATE_LIMIT, candidateLimit.failure());
        assertSame(PLAYER, candidateLimit.endBounds());
    }

    @Test
    void repeatedObservationsSpendOneTotalCandidateBudget() {
        var ledge = new AxisAlignedBB(0.5, 0, -2, 2, 0.5, 2);
        var result = rejected(request(CardinalFrame.Y_POS, new Vec3d(1, -0.08, 0), true, false, 0.6),
                fixture(List.of(GROUND, ledge)), new Limits(10, 2, 100));
        assertEquals(Failure.CANDIDATE_LIMIT, result.failure());
        assertEquals(2, result.work().queries());
        assertSame(PLAYER, result.endBounds());
    }

    @Test
    void failedOrMalformedProviderPreservesCauseAndStart() {
        var request = request(CardinalFrame.Y_POS, new Vec3d(1, 0, 0), false, false, 0);
        var original = new IllegalStateException("world observation unavailable");
        var failure = rejected(request, bounds -> { throw original; }, LIMITS);
        assertEquals(Failure.QUERY_FAILED, failure.failure());
        assertSame(original, failure.cause());
        assertSame(PLAYER, failure.endBounds());
        for (var malformed : Arrays.asList(null, Arrays.asList((AxisAlignedBB) null),
                List.of(new AxisAlignedBB(Double.NaN, 0, 0, 1, 1, 1)))) {
            var result = rejected(request, bounds -> malformed, LIMITS);
            assertEquals(Failure.INVALID_COLLIDER, result.failure());
            assertSame(PLAYER, result.endBounds());
            assertTrue(result.cause() != null);
        }
    }

    @Test
    void zeroMoveStillObservesWorldAndInvalidEntryFailsFast() {
        var result = solve(CardinalFrame.Y_POS, ZERO, false, false, 0, List.of());
        assertBox(PLAYER, result.endBounds());
        assertEquals(1, result.work().queries());
        assertEquals(0, result.work().offsetTests());
        assertThrows(IllegalArgumentException.class, () -> new Request(CardinalFrame.Y_POS, PLAYER, ZERO,
                MoverType.PISTON, false, false, 0));
        assertThrows(IllegalArgumentException.class, () -> request(CardinalFrame.Y_POS, ZERO, false, false, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1, 1));
    }

    private static Resolved solve(CardinalFrame frame, Vec3d localDelta, boolean grounded,
                                  boolean sneaking, double stepHeight, List<AxisAlignedBB> localColliders) {
        var worldColliders = localColliders.stream().map(box -> world(frame, box)).toList();
        return assertInstanceOf(Resolved.class, CardinalMovementResolver.resolve(
                request(frame, localDelta, grounded, sneaking, stepHeight), fixture(worldColliders), LIMITS));
    }

    private static Request request(CardinalFrame frame, Vec3d localDelta, boolean grounded,
                                   boolean sneaking, double stepHeight) {
        AxisAlignedBB bounds = frame == CardinalFrame.Y_POS ? PLAYER : world(frame, PLAYER);
        return new Request(frame, bounds, frame.toWorld(localDelta), MoverType.SELF, grounded, sneaking, stepHeight);
    }

    private static Rejected rejected(Request request, Function<AxisAlignedBB, List<AxisAlignedBB>> provider, Limits limits) {
        return assertInstanceOf(Rejected.class, CardinalMovementResolver.resolve(request, provider, limits));
    }

    /** A complete immutable static scene, not a fake World or an alternate movement solver. */
    private static Function<AxisAlignedBB, List<AxisAlignedBB>> fixture(List<AxisAlignedBB> colliders) {
        var snapshot = List.copyOf(colliders);
        return bounds -> new ArrayList<>(snapshot.stream().filter(collider -> collider.intersects(bounds)).toList());
    }

    private static AxisAlignedBB world(CardinalFrame frame, AxisAlignedBB local) {
        var first = frame.toWorld(new Vec3d(local.minX, local.minY, local.minZ));
        var second = frame.toWorld(new Vec3d(local.maxX, local.maxY, local.maxZ));
        return new AxisAlignedBB(first.x(), first.y(), first.z(), second.x(), second.y(), second.z());
    }

    private static void assertVector(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1e-12);
        assertEquals(expected.y(), actual.y(), 1e-12);
        assertEquals(expected.z(), actual.z(), 1e-12);
    }

    private static void assertBox(AxisAlignedBB expected, AxisAlignedBB actual) {
        assertEquals(expected.minX, actual.minX, 1e-12);
        assertEquals(expected.minY, actual.minY, 1e-12);
        assertEquals(expected.minZ, actual.minZ, 1e-12);
        assertEquals(expected.maxX, actual.maxX, 1e-12);
        assertEquals(expected.maxY, actual.maxY, 1e-12);
        assertEquals(expected.maxZ, actual.maxZ, 1e-12);
    }
}
