package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

class RemoteBoostLoadedPathTest {
    private static final double EPSILON = 1.0e-10;

    @Test
    void allLoadedPathReturnsTheExactCandidateIncludingPureVerticalMovementAtHighY() {
        Vec3 start = new Vec3(2.0D, 300.0D, 3.0D);
        Vec3 candidate = new Vec3(2.0D, 347.25D, 3.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(1.5D, 2.5D, 2.5D, 3.5D), key -> true);

        assertEquals(candidate, result.endpoint());
        assertEquals(1.0D, result.fraction());
        assertTrue(result.fullyLoaded());
        assertEquals(OptionalLong.empty(), result.firstMissingColumn());
    }

    @Test
    void clipsAheadBeforeAnUnknownColumnAndScalesEveryCoordinateByOneFraction() {
        Vec3 start = new Vec3(15.0D, 40.0D, 8.0D);
        Vec3 candidate = new Vec3(20.0D, 50.0D, 13.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(14.0D, 15.0D, 7.5D, 8.5D), loadedExcept(1, 0));

        assertFalse(result.fullyLoaded());
        assertEquals(RemoteBoostLoadedColumns.key(1, 0), result.firstMissingColumn().getAsLong());
        assertTrue(result.fraction() > 0.0D);
        assertTrue(result.fraction() < 0.2D);
        assertEquals(start.lerp(candidate, result.fraction()), result.endpoint());
        assertTrue(result.endpoint().x() < 16.0D);
    }

    @Test
    void callerProvidedQueryMarginCanMakeTheSameUnknownColumnEnterEarlier() {
        Vec3 start = new Vec3(15.0D, 0.0D, 8.0D);
        Vec3 candidate = new Vec3(20.0D, 10.0D, 8.0D);

        RemoteBoostLoadedPath.Result withoutMargin = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(14.5D, 15.0D, 7.5D, 8.5D), loadedExcept(1, 0));
        RemoteBoostLoadedPath.Result withMargin = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(14.5D, 15.75D, 7.5D, 8.5D), loadedExcept(1, 0));

        assertTrue(withMargin.fraction() < withoutMargin.fraction());
        assertEquals(start.lerp(candidate, withMargin.fraction()), withMargin.endpoint());
    }

    @Test
    void respectsNegativeChunkCoordinatesAndExactChunkEdges() {
        Vec3 start = new Vec3(-15.25D, 0.0D, 1.0D);
        Vec3 candidate = new Vec3(-20.25D, 0.0D, 1.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(-15.5D, -15.0D, 0.0D, 1.0D), loadedExcept(-2, 0));

        assertEquals(RemoteBoostLoadedColumns.key(-2, 0), result.firstMissingColumn().getAsLong());
        assertTrue(result.fraction() > 0.0D);
        assertTrue(result.fraction() < 0.1D);
        assertTrue(result.endpoint().x() > -16.0D);
    }

    @Test
    void clipsForAnUnknownDiagonalCornerInsideTheNativeBroadEnvelope() {
        Vec3 start = new Vec3(2.0D, 0.0D, 2.0D);
        Vec3 candidate = new Vec3(22.0D, 6.0D, 22.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(1.0D, 2.0D, 1.0D, 2.0D), loadedExcept(1, 0));

        assertEquals(RemoteBoostLoadedColumns.key(1, 0), result.firstMissingColumn().getAsLong());
        assertTrue(result.fraction() > 0.0D);
        assertTrue(result.fraction() < 0.7D);
        assertEquals(start.lerp(candidate, result.fraction()), result.endpoint());
    }

    @Test
    void initiallyUnknownStartVolumeStopsBeforeAnyDisplacement() {
        Vec3 start = new Vec3(4.0D, -20.0D, 4.0D);
        Vec3 candidate = new Vec3(9.0D, 100.0D, 4.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(3.0D, 5.0D, 3.0D, 5.0D), key -> false);

        assertEquals(start, result.endpoint());
        assertEquals(0.0D, result.fraction());
        assertEquals(RemoteBoostLoadedColumns.key(0, 0), result.firstMissingColumn().getAsLong());
    }

    @Test
    void retainsACommonFractionForVerticalAndHorizontalComponentsWhenClipped() {
        Vec3 start = new Vec3(15.0D, -50.0D, 2.0D);
        Vec3 candidate = new Vec3(20.0D, 75.0D, 7.0D);

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(start, candidate,
                bounds(14.0D, 15.0D, 1.0D, 3.0D), loadedExcept(1, 0));

        assertEquals(result.fraction(), (result.endpoint().x() - start.x())
                / (candidate.x() - start.x()), EPSILON);
        assertEquals(result.fraction(), (result.endpoint().y() - start.y())
                / (candidate.y() - start.y()), EPSILON);
        assertEquals(result.fraction(), (result.endpoint().z() - start.z())
                / (candidate.z() - start.z()), EPSILON);
    }

    @Test
    void boundsPredicateWorkToAtMostSixtyFourDistinctColumns() {
        AtomicInteger probes = new AtomicInteger();
        Set<Long> visited = new HashSet<>();

        RemoteBoostLoadedPath.Result result = RemoteBoostLoadedPath.clip(Vec3.ZERO, Vec3.ZERO,
                bounds(0.0D, 127.9D, 0.0D, 127.9D), key -> {
                    probes.incrementAndGet();
                    assertTrue(visited.add(key), "predicate must not receive a duplicate column");
                    return true;
                });

        assertTrue(result.fullyLoaded());
        assertEquals(64, probes.get());
        assertEquals(64, visited.size());
    }

    @Test
    void rejectsEnvelopesBeyondTheFixedProbeBudgetBeforeCallingThePredicate() {
        AtomicInteger probes = new AtomicInteger();

        assertThrows(IllegalArgumentException.class, () -> RemoteBoostLoadedPath.clip(Vec3.ZERO,
                Vec3.ZERO, bounds(0.0D, 128.0D, 0.0D, 128.0D), key -> {
                    probes.incrementAndGet();
                    return true;
                }));

        assertEquals(0, probes.get());
    }

    @Test
    void keepsTheEndpointOnTheLoadedSideOfLargePositiveAndNegativeWorldBoundaries() {
        int positiveChunk = 1_875_000;
        Vec3 positiveStart = new Vec3(29_999_999.75D, 0.0D, 4.0D);
        Vec3 positiveCandidate = new Vec3(30_000_000.25D, 0.0D, 4.0D);
        RemoteBoostLoadedPath.Result positive = RemoteBoostLoadedPath.clip(positiveStart,
                positiveCandidate, bounds(29_999_999.5D, 29_999_999.75D, 3.0D, 5.0D),
                loadedExcept(positiveChunk, 0));
        assertTrue(positive.endpoint().x() < 30_000_000.0D);

        int negativeChunk = -1_875_000;
        Vec3 negativeStart = new Vec3(-29_999_999.75D, 0.0D, 4.0D);
        Vec3 negativeCandidate = new Vec3(-30_000_000.25D, 0.0D, 4.0D);
        RemoteBoostLoadedPath.Result negative = RemoteBoostLoadedPath.clip(negativeStart,
                negativeCandidate, bounds(-29_999_999.75D, -29_999_999.5D, 3.0D, 5.0D),
                loadedExcept(negativeChunk, 0));
        assertTrue(negative.endpoint().x() > -30_000_000.0D);
    }

    @Test
    void failsFastForNonFiniteCoordinatesAndInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> RemoteBoostLoadedPath.clip(
                new Vec3(Double.NaN, 0.0D, 0.0D), Vec3.ZERO,
                bounds(0.0D, 1.0D, 0.0D, 1.0D), key -> true));
        assertThrows(IllegalArgumentException.class, () -> RemoteBoostLoadedPath.clip(Vec3.ZERO,
                new Vec3(Double.POSITIVE_INFINITY, 0.0D, 0.0D),
                bounds(0.0D, 1.0D, 0.0D, 1.0D), key -> true));
        assertThrows(IllegalArgumentException.class, () -> bounds(1.0D, 0.0D, 0.0D, 1.0D));
    }

    private static LongPredicate loadedExcept(int missingChunkX, int missingChunkZ) {
        long missing = RemoteBoostLoadedColumns.key(missingChunkX, missingChunkZ);
        return key -> key != missing;
    }

    private static RemoteBoostLoadedPath.XzBounds bounds(double minX, double maxX, double minZ,
            double maxZ) {
        return new RemoteBoostLoadedPath.XzBounds(minX, maxX, minZ, maxZ);
    }
}
