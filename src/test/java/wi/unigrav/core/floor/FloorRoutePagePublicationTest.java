package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkView;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.PreparedPublication;
import wi.unigrav.core.floor.FloorFieldIndex.Source;

class FloorRoutePagePublicationTest {

    private static final FloorFieldProfile COMMON_PROFILE = new FloorFieldProfile(8.0, 2.0, 6.0);

    @Test
    void samePageOwnerReplacementWithUnchangedRangeFencesStalePublicationAndAllowsFreshRetry() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey firstKey = new ChunkKey(0, 0);
        Object firstOwner = new Object();
        FloorChunkCoverage oldFirst = coverage(firstKey, source(0, 0, 0, Face.UP, COMMON_PROFILE));
        index.replaceChunkCoverage(firstKey, firstOwner, oldFirst);

        ChunkKey secondKey = new ChunkKey(0, 1);
        Object oldSecondOwner = new Object();
        Object newSecondOwner = new Object();
        Source secondSource = source(0, 0, 16, Face.NORTH, COMMON_PROFILE);
        FloorChunkCoverage oldSecond = coverage(secondKey, secondSource);
        FloorChunkCoverage newSecond = coverage(secondKey, secondSource);
        index.replaceChunkCoverage(secondKey, oldSecondOwner, oldSecond);

        FloorChunkCoverage nextFirst = coverage(firstKey, source(0, 0, 0, Face.NORTH, COMMON_PROFILE));
        PreparedPublication staleFirst = index.prepareReplacement(firstKey, firstOwner, nextFirst);
        index.replaceChunkCoverage(secondKey, newSecondOwner, newSecond);

        assertFalse(index.isCurrent(staleFirst),
                "same-range owner replacement changed the shared NORTH route page identity");
        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(staleFirst));
        assertTrue(stale.getMessage().contains("stale"));

        assertSame(oldFirst, index.coverageFor(firstKey, firstOwner));
        assertSame(newSecond, index.coverageFor(secondKey, newSecondOwner));
        assertEquals(new Vec3(0.0, -8.0, 0.0),
                index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, 0.0, 8.0),
                index.sample(northPoint(16), 0.0).gravityAccel());

        PreparedPublication freshFirst = index.prepareReplacement(firstKey, firstOwner, nextFirst);
        index.commitPrepared(freshFirst);
        assertSame(nextFirst, index.coverageFor(firstKey, firstOwner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 1.5, 0.5), 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, 0.0, 8.0),
                index.sample(northPoint(0), 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, 0.0, 8.0),
                index.sample(northPoint(16), 0.0).gravityAccel());
    }

    @Test
    void sameRangeOwnerReplacementOnAnotherRoutePageDoesNotFencePreparedPublication() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey firstKey = new ChunkKey(0, 0);
        Object firstOwner = new Object();
        FloorChunkCoverage oldFirst = coverage(firstKey, source(0, 0, 0, Face.UP, COMMON_PROFILE));
        index.replaceChunkCoverage(firstKey, firstOwner, oldFirst);

        ChunkKey secondKey = new ChunkKey(1, 1);
        Object oldSecondOwner = new Object();
        Object newSecondOwner = new Object();
        Source secondSource = source(16, 0, 16, Face.NORTH, COMMON_PROFILE);
        FloorChunkCoverage oldSecond = coverage(secondKey, secondSource);
        FloorChunkCoverage newSecond = coverage(secondKey, secondSource);
        index.replaceChunkCoverage(secondKey, oldSecondOwner, oldSecond);

        FloorChunkCoverage nextFirst = coverage(firstKey, source(0, 0, 0, Face.NORTH, COMMON_PROFILE));
        PreparedPublication preparedFirst = index.prepareReplacement(firstKey, firstOwner, nextFirst);
        index.replaceChunkCoverage(secondKey, newSecondOwner, newSecond);

        assertTrue(index.isCurrent(preparedFirst),
                "a route-page replacement on a different page keeps this publication current");
        index.commitPrepared(preparedFirst);

        assertSame(nextFirst, index.coverageFor(firstKey, firstOwner));
        assertSame(newSecond, index.coverageFor(secondKey, newSecondOwner));
        assertEquals(new Vec3(0.0, 0.0, 8.0),
                index.sample(northPoint(0), 0.0).gravityAccel());
        assertEquals(new Vec3(0.0, 0.0, 8.0),
                index.sample(new Vec3(16.5, 0.5, 15.5), 0.0).gravityAccel());
    }

    @Test
    void upRoutePageKeepsAscendingSectionNormalsAcrossAddReplaceDeleteAndRemoval() {
        FloorFieldIndex index = new FloorFieldIndex();
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();
        FloorFieldProfile large = new FloorFieldProfile(Math.scalb(1.0, 53), 44.0, 45.0);
        FloorFieldProfile unit = new FloorFieldProfile(1.0, 44.0, 45.0);
        List<Source> initial = List.of(
                source(0, 32, 0, Face.UP, unit),
                source(0, 0, 0, Face.UP, large),
                source(0, 16, 0, Face.UP, unit));
        index.replaceChunk(key, owner, initial);

        double ascendingNormalSum = Math.scalb(1.0, 53);
        ascendingNormalSum += 1.0;
        ascendingNormalSum += 1.0;
        assertEquals(new Vec3(0.0, -ascendingNormalSum, 0.0),
                index.sample(new Vec3(0.5, 40.0, 0.5), 0.0).gravityAccel(),
                "UP sections sum in ascending normal order, independently of input list order");

        FloorFieldProfile replacementProfile = new FloorFieldProfile(3.0, 60.0, 64.0);
        FloorFieldProfile addedProfile = new FloorFieldProfile(2.0, 60.0, 64.0);
        index.replaceChunk(key, owner, List.of(
                source(0, 48, 0, Face.UP, addedProfile),
                source(0, 16, 0, Face.UP, replacementProfile)));

        assertEquals(new Vec3(0.0, -3.0, 0.0),
                index.sample(new Vec3(0.5, 40.0, 0.5), 0.0).gravityAccel(),
                "the old y=0 and y=32 sections are completely removed");
        assertEquals(new Vec3(0.0, -5.0, 0.0),
                index.sample(new Vec3(0.5, 50.0, 0.5), 0.0).gravityAccel(),
                "the replaced y=16 section and added y=48 section are sampled");

        assertTrue(index.removeChunk(key, owner));
        assertNull(index.coverageFor(key, owner));
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 40.0, 0.5), 0.0).gravityAccel());
        assertEquals(Vec3.ZERO, index.sample(new Vec3(0.5, 50.0, 0.5), 0.0).gravityAccel());
    }

    @Test
    void routeStageFailureRetainsForeignSamePageViewAndPreservesRollbackEvidence() {
        ChunkKey firstKey = new ChunkKey(0, 2);
        ChunkKey secondKey = new ChunkKey(0, 1);
        Object firstOwner = new Object();
        Object oldSecondOwner = new Object();
        Object newSecondOwner = new Object();
        Source oldFirstSource = source(0, 0, 32, Face.UP, COMMON_PROFILE);
        FloorChunkCoverage oldFirst = coverage(firstKey, oldFirstSource);
        Source secondSource = source(0, 0, 16, Face.NORTH, COMMON_PROFILE);
        FloorChunkCoverage oldSecond = coverage(secondKey, secondSource);
        FloorChunkCoverage newSecond = coverage(secondKey, secondSource);
        AtomicReference<FloorFieldIndex> indexReference = new AtomicReference<>();
        AtomicReference<ChunkView> newSecondView = new AtomicReference<>();
        AtomicReference<Vec3> observedSecondForce = new AtomicReference<>();
        AtomicBoolean mutateAtRouteStage = new AtomicBoolean();
        IllegalStateException injectedFailure = new IllegalStateException("outer route stage interrupted");

        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.ROUTE_PATCH_STAGED
                    && mutateAtRouteStage.compareAndSet(true, false)) {
                FloorFieldIndex current = indexReference.get();
                ChunkView published = publish(current, secondKey, newSecondOwner, newSecond);
                newSecondView.set(published);
                observedSecondForce.set(current.sample(northPoint(16), 0.0).gravityAccel());
                throw injectedFailure;
            }
        });
        indexReference.set(index);
        ChunkView firstView = publish(index, firstKey, firstOwner, oldFirst);
        publish(index, secondKey, oldSecondOwner, oldSecond);
        FloorChunkCoverage nextFirst = coverage(firstKey, source(0, 0, 32, Face.NORTH, COMMON_PROFILE));
        PreparedPublication preparedFirst = index.prepareReplacement(firstKey, firstOwner, nextFirst);

        mutateAtRouteStage.set(true);
        assertSame(injectedFailure, assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(preparedFirst)));

        assertSame(oldFirst, firstView.currentCoverage(firstOwner));
        assertSame(newSecond, newSecondView.get().currentCoverage(newSecondOwner));
        assertEquals(new Vec3(0.0, 0.0, 8.0), observedSecondForce.get());
        assertEquals(1, injectedFailure.getSuppressed().length);
        assertTrue(injectedFailure.getSuppressed()[0].getMessage()
                .contains("cannot restore staged floor route page identity"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> index.sample(northPoint(16), 0.0));
        assertSame(injectedFailure, invalid.getCause());
    }

    @Test
    void beforeLinearizationFenceRejectsForeignSamePageViewAndPreservesOldOwnerPointer() {
        ChunkKey firstKey = new ChunkKey(0, 2);
        ChunkKey secondKey = new ChunkKey(0, 1);
        Object firstOwner = new Object();
        Object oldSecondOwner = new Object();
        Object newSecondOwner = new Object();
        FloorChunkCoverage oldFirst = coverage(firstKey, source(0, 0, 32, Face.UP, COMMON_PROFILE));
        Source secondSource = source(0, 0, 16, Face.NORTH, COMMON_PROFILE);
        FloorChunkCoverage oldSecond = coverage(secondKey, secondSource);
        FloorChunkCoverage newSecond = coverage(secondKey, secondSource);
        AtomicReference<FloorFieldIndex> indexReference = new AtomicReference<>();
        AtomicReference<ChunkView> newSecondView = new AtomicReference<>();
        AtomicReference<Vec3> observedSecondForce = new AtomicReference<>();
        AtomicBoolean mutateBeforeLinearization = new AtomicBoolean();

        FloorFieldIndex index = new FloorFieldIndex(() -> {
            if (mutateBeforeLinearization.compareAndSet(true, false)) {
                FloorFieldIndex current = indexReference.get();
                ChunkView published = publish(current, secondKey, newSecondOwner, newSecond);
                newSecondView.set(published);
                observedSecondForce.set(current.sample(northPoint(16), 0.0).gravityAccel());
            }
        });
        indexReference.set(index);
        ChunkView firstView = publish(index, firstKey, firstOwner, oldFirst);
        publish(index, secondKey, oldSecondOwner, oldSecond);
        PreparedPublication preparedFirst = index.prepareReplacement(firstKey, firstOwner,
                coverage(firstKey, source(0, 0, 32, Face.NORTH, COMMON_PROFILE)));

        mutateBeforeLinearization.set(true);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(preparedFirst));

        assertTrue(failure.getMessage().contains("floor route page changed during staged publication"));
        assertSame(oldFirst, firstView.currentCoverage(firstOwner));
        assertSame(newSecond, newSecondView.get().currentCoverage(newSecondOwner));
        assertEquals(new Vec3(0.0, 0.0, 8.0), observedSecondForce.get());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage()
                .contains("cannot restore staged floor route page identity"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> index.sample(northPoint(16), 0.0));
        assertSame(failure, invalid.getCause());
    }

    @Test
    void chunkDirectoryInsertionFenceRejectsFirstLoadWithoutReplacingForeignSamePageView() {
        ChunkKey firstKey = new ChunkKey(0, 2);
        ChunkKey secondKey = new ChunkKey(0, 1);
        Object firstOwner = new Object();
        Object oldSecondOwner = new Object();
        Object newSecondOwner = new Object();
        Source secondSource = source(0, 0, 16, Face.NORTH, COMMON_PROFILE);
        FloorChunkCoverage oldSecond = coverage(secondKey, secondSource);
        FloorChunkCoverage newSecond = coverage(secondKey, secondSource);
        AtomicReference<FloorFieldIndex> indexReference = new AtomicReference<>();
        AtomicReference<ChunkView> newSecondView = new AtomicReference<>();
        AtomicReference<Vec3> observedSecondForce = new AtomicReference<>();
        AtomicBoolean mutateAfterDirectoryInsertion = new AtomicBoolean();

        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.CHUNK_DIRECTORY_INSERTED
                    && mutateAfterDirectoryInsertion.compareAndSet(true, false)) {
                FloorFieldIndex current = indexReference.get();
                ChunkView published = publish(current, secondKey, newSecondOwner, newSecond);
                newSecondView.set(published);
                observedSecondForce.set(current.sample(northPoint(16), 0.0).gravityAccel());
            }
        });
        indexReference.set(index);
        publish(index, secondKey, oldSecondOwner, oldSecond);
        PreparedPublication firstLoad = index.prepareReplacement(firstKey, firstOwner,
                coverage(firstKey, source(0, 0, 32, Face.NORTH, COMMON_PROFILE)));

        mutateAfterDirectoryInsertion.set(true);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> index.commitPrepared(firstLoad));

        assertTrue(failure.getMessage().contains("floor route page changed during staged publication"));
        assertThrows(IllegalStateException.class,
                () -> firstLoad.targetView().currentCoverage(firstOwner),
                "the first-load view never crossed its shared publication pointer");
        assertSame(newSecond, newSecondView.get().currentCoverage(newSecondOwner));
        assertEquals(new Vec3(0.0, 0.0, 8.0), observedSecondForce.get());
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage()
                .contains("cannot restore staged floor route page identity"));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> index.sample(northPoint(16), 0.0));
        assertSame(failure, invalid.getCause());
    }

    private static FloorChunkCoverage coverage(ChunkKey key, Source... sources) {
        return FloorChunkCoverage.compile(key, List.of(sources));
    }

    private static Source source(int x, int y, int z, Face face, FloorFieldProfile profile) {
        return new Source(x, y, z, face, Kind.GRAVITY, profile);
    }

    private static ChunkView publish(FloorFieldIndex index, ChunkKey key, Object owner,
                                     FloorChunkCoverage coverage) {
        PreparedPublication prepared = index.prepareReplacement(key, owner, coverage);
        return index.commitPrepared(prepared);
    }

    private static Vec3 northPoint(int sourceZ) {
        return new Vec3(0.5, 0.5, sourceZ - 0.5);
    }
}