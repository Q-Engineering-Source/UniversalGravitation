package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorWorldFields.WorldFields;

class FloorWorldFieldsLifecycleTest {
    private static final ChunkKey KEY = new ChunkKey(0, 0);
    private static final Vec3 POINT = new Vec3(0.5, 1.0, 0.5);
    private static final Vec3 PULL = new Vec3(0.0, -32.0, 0.0);
    private static final FloorFieldProfile PROFILE = new FloorFieldProfile(32.0, 8.0, 16.0);

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void deferredInitialLoadAndRollbackReadOnlyTheFinalStoredState() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = gravityFloor();
        AtomicInteger reads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> {
            reads.incrementAndGet();
            return scan(() -> sections);
        }, true);
        assertEquals(0, reads.get());
        sections[0].set(0, 0, 0, FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        fields.flush(true);
        assertTrue(fields.sample(POINT, 1.0, true).isEmpty());
        assertEquals(0, reads.get());
        sections[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        var result = fields.sample(POINT, 1.0, false).orElseThrow();
        assertEquals(PULL, result.gravityAccel());
        assertEquals(Vec3.ZERO, result.magneticAccel());
        assertEquals(1, reads.get());
    }

    @Test
    void debugSnapshotIsImmutableAndFollowsCommittedRemovalAndUnload() {
        var fields = new WorldFields();
        var sections = gravityFloor();
        Object owner = new Object();
        fields.load(KEY, owner, () -> scan(() -> sections), false);
        var first = fields.debugSnapshot(POINT, false).orElseThrow();
        assertTrue(!first.arrows().isEmpty());
        assertEquals(PULL, first.arrows().getFirst().gravity());
        assertThrows(UnsupportedOperationException.class, () -> first.arrows().clear());
        sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        assertTrue(fields.debugSnapshot(POINT, true).isEmpty(), "deferred is not an empty field");
        assertTrue(fields.debugSnapshot(POINT, false).orElseThrow().arrows().isEmpty());
        assertEquals(PULL, first.arrows().getFirst().gravity(), "published snapshot cannot mutate under the renderer");
        fields.unload(KEY, owner);
        assertTrue(fields.debugSnapshot(POINT, false).orElseThrow().arrows().isEmpty());
    }

    @Test
    void debugSnapshotExpandsOnlyTheBoundedWindowAndDetectsThe1025thEmitter() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        for (int sectionIndex = 0; sectionIndex < 4; sectionIndex++) {
            sections[sectionIndex] = new ExtendedBlockStorage(sectionIndex * 16, true);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    sections[sectionIndex].set(x, 0, z, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
                }
            }
        }
        sections[4] = new ExtendedBlockStorage(64, true);
        sections[4].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        Object owner = new Object();
        fields.load(KEY, owner, () -> scan(() -> sections), false);

        FloorChunkCoverage coverage = fields.publishedCoverage(KEY, owner);
        assertEquals(1025, coverage.nearbySources(new Vec3(8.0, 32.0, 8.0), 33.0, 1024).size(),
                "bounded expansion reads at most one source beyond the public limit");
        assertTrue(fields.debugSnapshot(new Vec3(8.0, 32.0, 8.0), false).isEmpty(),
                "the debug view rejects a truncated source set instead of publishing partial arrows");
    }

    @Test
    void dirtyChangesAfterTickFlushAreReadBeforeTheNextSample() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = gravityFloor();
        Object owner = new Object();
        fields.load(KEY, owner, () -> scan(() -> sections), false);
        assertEquals(PULL, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
        fields.flush(false);
        sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        assertTrue(fields.sample(POINT, 0.0, true).isEmpty());
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void emptyPendingFlushDoesNotReadStorageOrDetachADirtyBatch() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        AtomicInteger storageReads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> FloorChunkSources.scanExisting(0, 0, () -> {
            storageReads.incrementAndGet();
            return sections;
        }, PROFILE), false);
        long detaches = fields.detachedDirtyBatchCount();

        fields.flush(false);

        assertEquals(1, storageReads.get(), "only the initial real storage scan ran");
        assertEquals(detaches, fields.detachedDirtyBatchCount(), "an empty flush created no dirty batch");
    }

    @Test
    void ordinaryBlockMutationReadsFinalStorageButDoesNotPublishCoverage() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        AtomicInteger storageReads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> FloorChunkSources.scanExisting(0, 0, () -> {
            storageReads.incrementAndGet();
            return sections;
        }, PROFILE), false);
        FloorChunkCoverage published = fields.publishedCoverage(KEY, owner);
        long revision = fields.publicationRevision(KEY, owner);
        sections[0].set(0, 0, 0, Blocks.STONE.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);

        var sample = fields.sample(new Vec3(0.5, 1.0, 0.5), 0.0, false).orElseThrow();

        assertEquals(Vec3.ZERO, sample.gravityAccel());
        assertEquals(2, storageReads.get(), "a real dirty cell reads its final stored state once");
        assertSame(published, fields.publishedCoverage(KEY, owner), "ordinary blocks produce NoChange");
        assertEquals(revision, fields.publicationRevision(KEY, owner), "NoChange does not publish a new revision");
        assertEquals(1, fields.detachedDirtyBatchCount());
    }

    @Test
    void reentrantMarkDuringNoChangeFlushSurvivesInTheOtherDirtyBank() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        AtomicInteger storageReads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> FloorChunkSources.scanExisting(0, 0, () -> {
            int read = storageReads.incrementAndGet();
            if (read == 2) {
                sections[0].set(1, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
                fields.markDirty(KEY, owner, 1, 0, 0);
            }
            return sections;
        }, PROFILE), false);
        FloorChunkCoverage original = fields.publishedCoverage(KEY, owner);
        long originalRevision = fields.publicationRevision(KEY, owner);
        sections[0].set(0, 0, 0, Blocks.STONE.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);

        assertTrue(fields.sample(POINT, 0.0, false).isEmpty(),
                "the reentrant bank is deferred to the next bounded refresh");
        assertSame(original, fields.publishedCoverage(KEY, owner), "the first detached batch was NoChange");
        assertEquals(originalRevision, fields.publicationRevision(KEY, owner));
        assertEquals(2, storageReads.get());
        assertEquals(1, fields.detachedDirtyBatchCount());

        var next = fields.sample(new Vec3(1.5, 1.0, 0.5), 0.0, false).orElseThrow();
        assertEquals(PULL, next.gravityAccel());
        assertEquals(3, storageReads.get());
        assertEquals(2, fields.detachedDirtyBatchCount());
        assertEquals(originalRevision + 1, fields.publicationRevision(KEY, owner));
    }

    @Test
    void oppositePrimaryStateForDoubleSidedFloorIsNoChangeAtWorldFieldsBoundary() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = doubleSidedFloor(EnumFacing.UP);
        AtomicInteger storageReads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> FloorChunkSources.scanExisting(0, 0, () -> {
            storageReads.incrementAndGet();
            return sections;
        }, PROFILE), false);
        FloorChunkCoverage published = fields.publishedCoverage(KEY, owner);
        long revision = fields.publicationRevision(KEY, owner);
        sections[0].set(0, 0, 0, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getStateFromMeta(EnumFacing.DOWN.getIndex()));
        fields.markDirty(KEY, owner, 0, 0, 0);

        fields.sampleForStanding(new Vec3(0.5, 1.0, 0.5), 0.0, false).orElseThrow();

        assertEquals(2, storageReads.get(), "the final double-sided state was read");
        assertSame(published, fields.publishedCoverage(KEY, owner));
        assertEquals(revision, fields.publicationRevision(KEY, owner));
    }

    @Test
    void oneDirtyBatchCanRotateAndAddAdjacentEmittersSharingAPlane() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        for (int x = 1; x <= 2; x++) {
            sections[0].set(x, 0, 1, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.UP));
        }
        Object owner = new Object();
        fields.load(KEY, owner, () -> scan(() -> sections), false);

        for (int x = 1; x <= 3; x++) {
            sections[0].set(x, 0, 1, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.NORTH));
            fields.markDirty(KEY, owner, x, 0, 1);
        }

        fields.flush(false);

        FloorChunkCoverage published = fields.publishedCoverage(KEY, owner);
        for (int x = 1; x <= 3; x++) {
            assertFalse(published.hasEmitterAt(x, 0, 1, Face.UP));
            assertTrue(published.hasEmitterAt(x, 0, 1, Face.NORTH));
            assertEquals(new Vec3(0.0, 0.0, 32.0),
                    fields.sample(new Vec3(x + 0.5, 0.5, 0.5), 0.0, false).orElseThrow().gravityAccel());
        }
    }

    @Test
    void oldGenerationDirtyWorkAndUnloadCannotEraseAReplacement() {
        WorldFields fields = new WorldFields();
        Object oldOwner = new Object();
        Object newOwner = new Object();
        ExtendedBlockStorage[] oldStorage = gravityFloor();
        fields.load(KEY, oldOwner, () -> scan(() -> oldStorage), false);
        fields.markDirty(KEY, oldOwner, 0, 0, 0);
        ExtendedBlockStorage[] current = gravityFloor();
        current[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
        fields.load(KEY, newOwner, () -> scan(() -> current), false);
        fields.markDirty(KEY, oldOwner, 0, 0, 0);
        fields.unload(KEY, oldOwner);
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
        assertEquals(new Vec3(-32.0, 0.0, 0.0),
                fields.sample(new Vec3(1.0, 0.5, 0.5), 0.0, false).orElseThrow().gravityAccel());
        fields.unload(KEY, newOwner);
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void doubleSidedSourcesPublishAndRetireTogetherAcrossChunkGenerations() {
        WorldFields fields = new WorldFields();
        Object oldOwner = new Object();
        Object currentOwner = new Object();
        ExtendedBlockStorage[] oldStorage = doubleSidedFloor(EnumFacing.UP);
        fields.load(KEY, oldOwner, () -> scan(() -> oldStorage), false);

        var oldUpper = fields.sampleForStanding(new Vec3(0.5, 1.0, 0.5), 0.0, false).orElseThrow();
        var oldLower = fields.sampleForStanding(new Vec3(0.5, 0.0, 0.5), 0.0, false).orElseThrow();
        assertEquals(new Vec3(0.0, -32.0, 0.0), oldUpper.forces().gravityAccel());
        assertEquals(Map.of(Face.UP, 32.0), oldUpper.gravityStrengths());
        assertEquals(new Vec3(0.0, 32.0, 0.0), oldLower.forces().gravityAccel());
        assertEquals(Map.of(Face.DOWN, 32.0), oldLower.gravityStrengths());
        var debug = fields.debugSnapshot(new Vec3(0.5, 0.5, 0.5), false).orElseThrow();
        assertEquals(8, debug.arrows().size());
        assertTrue(debug.arrows().stream().anyMatch(arrow -> arrow.gravity().y() < 0.0));
        assertTrue(debug.arrows().stream().anyMatch(arrow -> arrow.gravity().y() > 0.0));

        ExtendedBlockStorage[] currentStorage = doubleSidedFloor(EnumFacing.EAST);
        fields.load(KEY, currentOwner, () -> scan(() -> currentStorage), false);
        fields.unload(KEY, oldOwner);
        var east = fields.sampleForStanding(new Vec3(1.0, 0.5, 0.5), 0.0, false).orElseThrow();
        var west = fields.sampleForStanding(new Vec3(0.0, 0.5, 0.5), 0.0, false).orElseThrow();
        assertEquals(new Vec3(-32.0, 0.0, 0.0), east.forces().gravityAccel());
        assertEquals(Map.of(Face.EAST, 32.0), east.gravityStrengths());
        assertEquals(Set.of(Face.EAST), east.forces().effectiveGravityFaces());
        assertEquals(new Vec3(32.0, 0.0, 0.0), west.forces().gravityAccel());
        assertEquals(Map.of(Face.WEST, 32.0), west.gravityStrengths());
        assertEquals(Set.of(Face.WEST), west.forces().effectiveGravityFaces());

        fields.unload(KEY, currentOwner);
        assertEquals(Vec3.ZERO, fields.sample(new Vec3(1.0, 0.5, 0.5), 0.0, false).orElseThrow().gravityAccel());
        assertEquals(Vec3.ZERO, fields.sample(new Vec3(0.0, 0.5, 0.5), 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void aMarkMadeDuringInitialReadingSurvivesTheDetachedBatch() {
        WorldFields fields = new WorldFields();
        Object owner = new Object();
        ExtendedBlockStorage[] sections = gravityFloor();
        fields.load(KEY, owner, () -> {
            FloorChunkSources sources = scan(() -> sections);
            sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
            fields.markDirty(KEY, owner, 0, 0, 0);
            return sources;
        }, false);
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void failedRereadRetainsItsDirtyPositionsAndOriginalCause() {
        WorldFields fields = new WorldFields();
        Object owner = new Object();
        ExtendedBlockStorage[] sections = gravityFloor();
        AtomicBoolean fail = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("storage read failed");
        fields.load(KEY, owner, () -> scan(() -> {
            if (fail.get()) throw failure;
            return sections;
        }), false);
        sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        fail.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fields.sample(POINT, 0.0, false)));
        fail.set(false);
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void failedUnloadRetainsThePublishedOwnerAndRetriesRemovalWithoutReadingStorage() {
        AtomicBoolean failNext = new AtomicBoolean();
        IllegalStateException injectedFailure = new IllegalStateException("unload publication rejected");
        FloorFieldIndex index = new FloorFieldIndex(() -> {
            if (failNext.compareAndSet(true, false)) {
                throw injectedFailure;
            }
        });
        WorldFields fields = new WorldFields(index);
        ExtendedBlockStorage[] sections = gravityFloor();
        AtomicInteger storageReads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> {
            storageReads.incrementAndGet();
            return scan(() -> sections);
        }, false);
        FloorChunkCoverage beforeUnload = fields.publishedCoverage(KEY, owner);
        failNext.set(true);

        assertSame(injectedFailure, assertThrows(IllegalStateException.class, () -> fields.unload(KEY, owner)));
        assertSame(beforeUnload, fields.publishedCoverage(KEY, owner),
                "failed removal keeps the exact owner publication available for a bounded retry");
        assertEquals(1, storageReads.get(), "unload retry does not reread storage after chunk retirement");

        fields.flush(false);

        assertEquals(1, storageReads.get());
        assertEquals(Vec3.ZERO, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
        assertNull(fields.publishedCoverage(KEY, owner));
    }

    @Test
    void failedInitialPublicationRetiresItsScannerAndRetryPublishesANewSharedView() {
        AtomicBoolean failFirstDirectoryInsert = new AtomicBoolean(true);
        IllegalStateException failure = new IllegalStateException("initial view insertion failed");
        FloorFieldIndex index = new FloorFieldIndex(null, (stage, ordinal) -> {
            if (stage == FloorFieldIndex.PublicationStage.CHUNK_DIRECTORY_INSERTED
                    && failFirstDirectoryInsert.compareAndSet(true, false)) {
                throw failure;
            }
        });
        WorldFields fields = new WorldFields(index);
        ExtendedBlockStorage[] sections = gravityFloor();
        Object owner = new Object();
        AtomicInteger storageReads = new AtomicInteger();
        List<FloorChunkSources> scanners = new ArrayList<>();
        fields.load(KEY, owner, () -> {
            storageReads.incrementAndGet();
            FloorChunkSources scanner = scan(() -> sections);
            scanners.add(scanner);
            return scanner;
        }, true);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fields.flush(false)));

        assertEquals(1, storageReads.get());
        assertNull(fields.publishedCoverage(KEY, owner));
        assertThrows(IllegalStateException.class, scanners.getFirst()::committedCoverage,
                "a scanner attached to a failed initial publication is retired");

        fields.flush(false);

        assertEquals(2, storageReads.get(), "the still-current reader is retried with a new scanner");
        assertEquals(2, scanners.size());
        FloorChunkCoverage published = fields.publishedCoverage(KEY, owner);
        assertSame(published, scanners.get(1).committedCoverage(),
                "the retry scanner and index observe the exact same published coverage");
        assertEquals(PULL, fields.sample(POINT, 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void failedChunkRefreshRestoresLaterDetachedChunksForRetry() {
        WorldFields fields = new WorldFields();
        ChunkKey firstKey = new ChunkKey(0, 0);
        ChunkKey secondKey = new ChunkKey(1, 0);
        Object firstOwner = new Object();
        Object secondOwner = new Object();
        ExtendedBlockStorage[] firstStorage = emptyStorage();
        ExtendedBlockStorage[] secondStorage = emptyStorage();
        AtomicBoolean failFirstRead = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("first chunk storage failed");
        fields.load(firstKey, firstOwner, () -> scan(0, 0, () -> {
            if (failFirstRead.get()) {
                throw failure;
            }
            return firstStorage;
        }), false);
        fields.load(secondKey, secondOwner, () -> scan(1, 0, () -> secondStorage), false);

        firstStorage[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        secondStorage[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        fields.markDirty(firstKey, firstOwner, 0, 0, 0);
        fields.markDirty(secondKey, secondOwner, 16, 0, 0);
        failFirstRead.set(true);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fields.flush(false)));

        failFirstRead.set(false);
        assertEquals(PULL, fields.sample(new Vec3(16.5, 1.0, 0.5), 0.0, false)
                .orElseThrow().gravityAccel(),
                "the second chunk's detached dirty bank must be retried after the first chunk fails");
    }

    @Test
    void staleStorageFailureStillPropagatesAfterReplacingItsOwner() {
        WorldFields fields = new WorldFields();
        Object oldOwner = new Object();
        Object replacementOwner = new Object();
        ExtendedBlockStorage[] oldStorage = gravityFloor();
        ExtendedBlockStorage[] replacementStorage = emptyStorage();
        replacementStorage[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
        AtomicBoolean replaceAndFail = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("old generation storage failed");
        fields.load(KEY, oldOwner, () -> scan(0, 0, () -> {
            if (replaceAndFail.compareAndSet(true, false)) {
                fields.load(KEY, replacementOwner, () -> scan(0, 0, () -> replacementStorage), false);
                throw failure;
            }
            return oldStorage;
        }), false);
        oldStorage[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
        fields.markDirty(KEY, oldOwner, 0, 0, 0);
        replaceAndFail.set(true);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> fields.flush(false)));

        FloorChunkCoverage replacement = fields.publishedCoverage(KEY, replacementOwner);
        assertFalse(replacement.hasEmitterAt(0, 0, 0, Face.UP),
                "the stale generation cannot restore old coverage into its replacement");
        assertTrue(replacement.hasEmitterAt(0, 0, 0, Face.EAST));
        assertEquals(new Vec3(-32.0, 0.0, 0.0),
                fields.sample(new Vec3(1.5, 0.5, 0.5), 0.0, false).orElseThrow().gravityAccel());
    }

    @Test
    void closeDuringStorageReadingCannotReturnAZeroFieldSuccess() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = gravityFloor();
        fields.load(KEY, new Object(), () -> {
            fields.close();
            return scan(() -> sections);
        }, true);
        assertTrue(fields.sample(POINT, 0.0, false).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> fields.load(KEY, new Object(), () -> scan(() -> sections), false));
    }

    @Test
    void standingObservationReadsFinalStorageOnceAndRefreshesForcesAndScoresTogether() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = gravityFloor();
        AtomicInteger reads = new AtomicInteger();
        Object owner = new Object();
        fields.load(KEY, owner, () -> {
            reads.incrementAndGet();
            return scan(() -> sections);
        }, true);
        sections[0].set(0, 0, 0, FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        assertTrue(fields.sampleForStanding(POINT, 2.0, true).isEmpty());
        assertEquals(0, reads.get());
        sections[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        var gravity = fields.sampleForStanding(POINT, 2.0, false).orElseThrow();
        assertEquals(1, reads.get());
        assertEquals(PULL, gravity.forces().gravityAccel());
        assertEquals(Map.of(Face.UP, 32.0), gravity.gravityStrengths());
        assertTrue(gravity.magneticStrengths().isEmpty());
        sections[0].set(0, 0, 0, FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        var magnetic = fields.sampleForStanding(POINT, 2.0, false).orElseThrow();
        assertEquals(Vec3.ZERO, magnetic.forces().gravityAccel());
        assertTrue(magnetic.gravityStrengths().isEmpty());
        assertEquals(new Vec3(0.0, -64.0, 0.0), magnetic.forces().magneticAccel());
        assertEquals(Map.of(Face.UP, 64.0), magnetic.magneticStrengths());
        assertEquals(magnetic.forces(), fields.sample(POINT, 2.0, false).orElseThrow());
        assertEquals(Map.of(Face.UP, 32.0), gravity.gravityStrengths());
    }

    @Test
    void standingPendingMutationCannotPublishOldForcesOrScores() {
        WorldFields fields = new WorldFields();
        Object owner = new Object();
        ExtendedBlockStorage[] sections = gravityFloor();
        fields.load(KEY, owner, () -> {
            FloorChunkSources sources = scan(() -> sections);
            sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
            fields.markDirty(KEY, owner, 0, 0, 0);
            return sources;
        }, true);
        // This query performs the initial read which creates a new pending mark.
        assertTrue(fields.sampleForStanding(POINT, 1.0, false).isEmpty());
        var refreshed = fields.sampleForStanding(POINT, 1.0, false).orElseThrow();
        assertEquals(Vec3.ZERO, refreshed.forces().gravityAccel());
        assertTrue(refreshed.gravityStrengths().isEmpty());
    }

    @Test
    void standingOldGenerationCannotEraseReplacementScores() {
        WorldFields fields = new WorldFields();
        Object oldOwner = new Object();
        Object currentOwner = new Object();
        ExtendedBlockStorage[] old = gravityFloor();
        fields.load(KEY, oldOwner, () -> scan(() -> old), false);
        fields.markDirty(KEY, oldOwner, 0, 0, 0);
        ExtendedBlockStorage[] current = gravityFloor();
        current[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
        fields.load(KEY, currentOwner, () -> scan(() -> current), true);
        fields.unload(KEY, oldOwner);
        fields.markDirty(KEY, oldOwner, 0, 0, 0);
        assertTrue(fields.sampleForStanding(POINT, 1.0, true).isEmpty());
        var result = fields.sampleForStanding(new Vec3(1.0, 0.5, 0.5), 1.0, false).orElseThrow();
        assertEquals(new Vec3(-32.0, 0.0, 0.0), result.forces().gravityAccel());
        assertEquals(Map.of(Face.EAST, 32.0), result.gravityStrengths());
        fields.unload(KEY, currentOwner);
        assertTrue(fields.sampleForStanding(POINT, 1.0, false).orElseThrow().gravityStrengths().isEmpty());
    }

    @Test
    void standingFailurePreservesCauseAndDirtyWorkForRetry() {
        WorldFields fields = new WorldFields();
        Object owner = new Object();
        ExtendedBlockStorage[] sections = gravityFloor();
        AtomicBoolean fail = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("standing storage read failed");
        fields.load(KEY, owner, () -> scan(() -> {
            if (fail.get()) throw failure;
            return sections;
        }), false);
        sections[0].set(0, 0, 0, Blocks.AIR.getDefaultState());
        fields.markDirty(KEY, owner, 0, 0, 0);
        fail.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fields.sampleForStanding(POINT, 1.0, false)));
        fail.set(false);
        var result = fields.sampleForStanding(POINT, 1.0, false).orElseThrow();
        assertEquals(Vec3.ZERO, result.forces().gravityAccel());
        assertTrue(result.gravityStrengths().isEmpty());
    }

    @Test
    void standingCloseDuringReadStaysUnavailableAndStillValidatesQueries() {
        WorldFields fields = new WorldFields();
        ExtendedBlockStorage[] sections = gravityFloor();
        fields.load(KEY, new Object(), () -> {
            fields.close();
            return scan(() -> sections);
        }, true);
        assertTrue(fields.sampleForStanding(POINT, 1.0, false).isEmpty());
        assertTrue(fields.sampleForStanding(POINT, 1.0, false).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fields.sampleForStanding(null, 0.0, false));
        assertThrows(IllegalArgumentException.class, () -> fields.sampleForStanding(POINT, -1.0, true));
        assertThrows(IllegalArgumentException.class, () -> FloorWorldFields.sampleForStanding(null, POINT, 0.0));
    }

    private static ExtendedBlockStorage[] gravityFloor() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        return sections;
    }

    private static ExtendedBlockStorage[] emptyStorage() {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        return sections;
    }

    private static ExtendedBlockStorage[] doubleSidedFloor(EnumFacing facing) {
        ExtendedBlockStorage[] sections = new ExtendedBlockStorage[16];
        sections[0] = new ExtendedBlockStorage(0, true);
        sections[0].set(0, 0, 0, FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getStateFromMeta(facing.getIndex()));
        return sections;
    }

    private static FloorChunkSources scan(Supplier<ExtendedBlockStorage[]> storage) {
        return FloorChunkSources.scanExisting(0, 0, storage, PROFILE);
    }

    private static FloorChunkSources scan(int chunkX, int chunkZ, Supplier<ExtendedBlockStorage[]> storage) {
        return FloorChunkSources.scanExisting(chunkX, chunkZ, storage, PROFILE);
    }
}
