package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.Map;

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

    private static FloorChunkSources scan(Supplier<ExtendedBlockStorage[]> storage) {
        return FloorChunkSources.scanExisting(0, 0, storage, PROFILE);
    }
}
