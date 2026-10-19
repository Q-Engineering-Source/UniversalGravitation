package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;

class NativeTargetFitWorldTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004991");
    private static final PlayerBodyShape.Dimensions DIMENSIONS = new PlayerBodyShape.Dimensions(.6, 1.8, .75);
    private static final BlockPos TARGET_BLOCK = new BlockPos(8, 68, 8);

    @TempDir Path temp;

    @Test
    void strictTargetTouchIsEmptyButOneUlpIntrusionIsOccupied() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("touch"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(9, 68, 8), Blocks.STONE.getDefaultState());
            try (var transaction = transaction(fixture, actor, accepted)) {
                PlayerBodyPlacement touching = cardinal(new Vec3d(8.7, 68.5, 8.5));
                var empty = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, touching, NativeTargetFit.SCAN));
                assertTrue(empty.snapshot().boxes().isEmpty(), "exact face touch must use strict native emptiness");

                Aabb bounds = touching.bounds();
                Aabb intrudedBounds = new Aabb(bounds.min(), new Vec3d(Math.nextUp(bounds.max().x()),
                        bounds.max().y(), bounds.max().z()));
                PlayerBodyPlacement intruded = PlayerBodyPlacement.fromCardinalBounds(intrudedBounds,
                        new Vec3d(intrudedBounds.center().x(), intrudedBounds.min().y(), intrudedBounds.center().z()),
                        CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, DIMENSIONS));
                var occupied = assertInstanceOf(NativeTargetFit.Rejected.class,
                        NativeTargetFit.prepare(transaction, intruded, NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.TARGET_OCCUPIED, occupied.reason());
                assertFalse(occupied.observation().boxes().isEmpty());
            }
        }
    }

    @Test
    void emptyQueriesUseRealRadiusAndShareMonotonicBudgetAcrossTransactions() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("shared-budget"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            var travel = new PlayerPhysicsStep.TravelState();
            Object owner = new Object();
            var store = new FreeTickResources.Store();
            var resources = store.acquire(owner, fixture.world(), accepted.scope(), travel.correctionRevision());
            NativeTargetFit.Observed observation;
            try (var first = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true,
                    owner, resources)) {
                long before = resources.numeric().units();
                observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(first, target, NativeTargetFit.SCAN));
                assertCommonEmptyCost(1, before, resources, observation.snapshot());
            }

            try (var second = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true,
                    owner, store.acquire(owner, fixture.world(), accepted.scope(), travel.correctionRevision()))) {
                long before = resources.numeric().units();
                var foreign = assertInstanceOf(NativeTargetFit.Unavailable.class, observation.recapture(second, target));
                assertEquals(NativeTargetFit.Reason.FOREIGN_TRANSACTION, foreign.reason());
                assertEquals(before, resources.numeric().units(), "a foreign transaction must not trigger another native query");
                observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(second, target, NativeTargetFit.SCAN));
                assertCommonEmptyCost(2, before, resources, observation.snapshot());

                before = resources.numeric().units();
                observation = assertInstanceOf(NativeTargetFit.Observed.class, observation.recapture(second, target));
                assertCommonEmptyCost(3, before, resources, observation.snapshot());
            }
            assertEquals(3, resources.nativeAttempts());
            assertEquals(0, resources.queries().calls(), "native attempts remain distinct from CHB query calls");
        }
    }

    @Test
    void missingColumnStopsBeforeNativeCallbackAndDoesNotRefundAttemptOrShellWork() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("missing-column"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            AtomicInteger callbacks = new AtomicInteger();
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(15, 68, 8), callbackBlock(callbacks::incrementAndGet).getDefaultState());
            try (var transaction = transaction(fixture, actor, accepted)) {
                long before = transaction.numeric().units();
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(16.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.MISSING_CHUNK, failure.reason());
                assertEquals(0, callbacks.get(), "preflight must stop before entering the native collision callback");
                assertEquals(1, transaction.resources().nativeAttempts());
                assertTrue(transaction.numeric().units() > before + 1,
                        "the claimed attempt, planned cell bound and reached column work remain charged");
                assertFalse(failure.column().isEmpty());
                assertEquals(0, transaction.queries().calls());
            }
        }
    }

    @Test
    void callbackFailuresPreserveOriginalCauseAndActorOrTickMutationIsUnavailable() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("callback-throw"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            RuntimeException original = new IllegalStateException("Free movement actor geometry changed outside its installation");
            placeCallback(fixture, () -> { throw original; });
            try (var transaction = transaction(fixture, actor, accepted)) {
                long before = transaction.numeric().units();
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.NATIVE_QUERY_FAILED, failure.reason());
                assertSame(original, failure.cause().orElseThrow());
                assertTrue(original.getStackTrace().length > 0);
                assertEquals(1, transaction.resources().nativeAttempts());
                assertTrue(transaction.numeric().units() > before, "a throwing native callback cannot refund prior work");
            }
        }

        try (var fixture = BootWorldFixture.open(temp.resolve("callback-actor-mutation"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            placeCallback(fixture, () -> actor.setPosition(actor.posX + .125, actor.posY, actor.posZ));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, failure.reason(), failure::toString);
                assertInstanceOf(OwnerContextChangedException.class, failure.cause().orElseThrow());
            }
        }

        try (var fixture = BootWorldFixture.open(temp.resolve("callback-tick-mutation"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            placeCallback(fixture, () -> fixture.world().getWorldInfo()
                    .setWorldTotalTime(fixture.world().getTotalWorldTime() + 1));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, failure.reason(), failure::toString);
                assertInstanceOf(OwnerContextChangedException.class, failure.cause().orElseThrow());
            }
        }
    }

    @Test
    void recaptureObservesSameTickBlockAndBorderChangesAndRejectsChangedTargetIdentity() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("block-change"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                        .setBlockState(TARGET_BLOCK, Blocks.STONE.getDefaultState());
                var occupied = assertInstanceOf(NativeTargetFit.Rejected.class,
                        observation.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.TARGET_OCCUPIED, occupied.reason());
            }
        }

        try (var fixture = BootWorldFixture.open(temp.resolve("border-change"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                fixture.world().getWorldBorder().setSize(fixture.world().getWorldBorder().getSize() - 1);
                var changed = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        observation.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, changed.reason());
            }
        }

        try (var fixture = BootWorldFixture.open(temp.resolve("target-identity"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                var movedTarget = cardinal(new Vec3d(Math.nextUp(8.5), 68.5, 8.5));
                long attempts = transaction.resources().nativeAttempts();
                var changed = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        observation.recapture(transaction, movedTarget));
                assertEquals(NativeTargetFit.Reason.TARGET_CHANGED, changed.reason());
                assertEquals(attempts, transaction.resources().nativeAttempts());
            }
        }
    }

    @Test
    void connectionScopeAndCorrectionChangesInvalidateTheBoundObservation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("owner-fences"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            var travel = new PlayerPhysicsStep.TravelState();
            Object owner = new Object();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(),
                    travel.correctionRevision());
            try (var transaction = new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true,
                    owner, resources)) {
                var observation = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                var originalConnection = actor.connection;
                actor.connection = null;
                var disconnected = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        observation.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, disconnected.reason());
                assertTrue(disconnected.cause().orElseThrow() instanceof IllegalStateException);
                actor.connection = originalConnection;

                int dimension = actor.dimension;
                actor.dimension = dimension + 1;
                var wrongScope = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        observation.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, wrongScope.reason());
                actor.dimension = dimension;

                travel.invalidate();
                var wrongCorrection = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        observation.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.CONTEXT_CHANGED, wrongCorrection.reason());
                assertTrue(wrongCorrection.cause().orElseThrow() instanceof IllegalStateException);
            }
        }
    }

    @Test
    void firstOutsideFlagMaintenanceIsRecordedAndLaterCaptureUsesAfterAsBaseline() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("outside-maintenance"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            fixture.world().getWorldBorder().setCenter(8.5, 8.5);
            fixture.world().getWorldBorder().setSize(16);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(20.5, 68.5, 8.5));
            assertFalse(actor.isOutsideBorder());
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var observed = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                assertFalse(observed.snapshot().outsideBorderBefore());
                assertTrue(observed.snapshot().outsideBorderAfter(),
                        "first native outside-flag maintenance is recorded as an observation");
                var recaptured = assertInstanceOf(NativeTargetFit.Observed.class,
                        observed.recapture(transaction, target));
                assertTrue(recaptured.snapshot().outsideBorderBefore());
                assertTrue(recaptured.snapshot().outsideBorderAfter());
            }
        }
    }

    @Test
    void numericAndAttemptLimitsAreDistinctAndFailedWorkIsNotRefunded() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("numeric-limit"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            AtomicInteger callbacks = new AtomicInteger();
            placeCallback(fixture, callbacks::incrementAndGet);
            try (var transaction = transaction(fixture, actor, accepted)) {
                transaction.numeric().charge(131_071);
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.NATIVE_WORK_EXHAUSTED, failure.reason());
                assertInstanceOf(NumericWorkExhaustedException.class, failure.cause().orElseThrow());
                assertEquals(131_072, transaction.numeric().units());
                assertEquals(1, transaction.resources().nativeAttempts());
                assertEquals(0, callbacks.get(), "numeric exhaustion stops before the native callback");
            }
        }

        try (var fixture = BootWorldFixture.open(temp.resolve("attempt-limit"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            Aabb overLimit = new Aabb(new Vec3d(-100, -100, -100), new Vec3d(100, 100, 100));
            try (var transaction = transaction(fixture, actor, accepted)) {
                for (int attempt = 0; attempt < FreeTickResources.MAX_NATIVE_ATTEMPTS; attempt++) {
                    var failure = assertInstanceOf(WorldCollisionCandidates.Unavailable.class,
                            WorldCollisionCandidates.capture(transaction, overLimit, QueryPolicy.NATIVE_STRICT,
                                    NativeTargetFit.SCAN));
                    assertEquals(WorldCollisionCandidates.Reason.QUERY_FOOTPRINT, failure.reason());
                }
                assertEquals(FreeTickResources.MAX_NATIVE_ATTEMPTS, transaction.resources().nativeAttempts());
                assertEquals(FreeTickResources.MAX_NATIVE_ATTEMPTS, transaction.numeric().units());
                var exhausted = assertInstanceOf(WorldCollisionCandidates.Unavailable.class,
                        WorldCollisionCandidates.capture(transaction, overLimit, QueryPolicy.NATIVE_STRICT,
                                NativeTargetFit.SCAN));
                assertEquals(WorldCollisionCandidates.Reason.NATIVE_ATTEMPT_LIMIT, exhausted.reason());
                assertEquals(FreeTickResources.MAX_NATIVE_ATTEMPTS, transaction.numeric().units());
                assertEquals(0, transaction.queries().calls());
            }
        }
    }

    @Test
    void nativeCandidateLimitIsChargedBeforeRejectingTheActualForgeList() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("candidate-limit"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            PlayerBodyPlacement target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger attempted = new AtomicInteger();
            AtomicInteger acceptedOutputs = new AtomicInteger();
            long[] callbackWork = new long[1];
            try (var transaction = transaction(fixture, actor, accepted)) {
                var prior = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                WorldCollisionCandidates.Snapshot baseline = prior.snapshot();
                long before = transaction.numeric().units();
                fixture.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(TARGET_BLOCK,
                        candidateBoxesBlock(callbacks, target.bounds(), 4_097, attempted, acceptedOutputs,
                                () -> callbackWork[0] = transaction.postprocessing().units(), false).getDefaultState());
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        prior.recapture(transaction, target));
                assertEquals(NativeTargetFit.Reason.CANDIDATE_LIMIT, failure.reason(), failure::toString);
                assertEquals("native producer declaration limit exhausted: 4096", failure.detail());
                var cause = assertInstanceOf(NativeCollisionProvenance.DeclarationLimit.class,
                        failure.cause().orElseThrow());
                assertEquals(4_096, cause.limit());
                assertEquals(4_097, cause.attempted());
                assertEquals(4_096, cause.accepted());
                assertTrue(cause.getStackTrace().length > 0);
                assertEquals(4_097, attempted.get());
                assertEquals(4_096, acceptedOutputs.get());
                assertEquals(36_864L, transaction.postprocessing().units() - callbackWork[0],
                        "4096 declarations * 8, 4095 repeated-identity comparisons, and the rejected classification");
                assertEquals(1, callbacks.get(), "the real native block collision callback must supply the aggregate");
                assertEquals(2, transaction.resources().nativeAttempts());
                CollisionQueryFootprint footprint = baseline.footprint();
                long columns = footprint.columns().size();
                long sections = footprint.lastEntitySection() - footprint.firstEntitySection() + 1L;
                long expectedFailureCharge = 1L + footprint.blockCellsUpperBound() + columns
                        + columns * sections + baseline.rootEntitiesObserved();
                assertEquals(before + expectedFailureCharge, transaction.numeric().units(),
                        "producer rejects before aggregate copy; native shell and reached preflight work remain charged");
            }
        }
    }

    @Test
    void addAllDeclarationLimitRejectsBeforeWritesAndKeepsActualClassificationWork() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("candidate-add-all-limit"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            var target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            var callbacks = new AtomicInteger();
            var acceptedOutputs = new AtomicInteger();
            long[] callbackWork = new long[1];
            try (var transaction = transaction(fixture, actor, accepted)) {
                fixture.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(TARGET_BLOCK,
                        candidateBoxesBlock(callbacks, target.bounds(), 4_097, new AtomicInteger(), acceptedOutputs,
                                () -> callbackWork[0] = transaction.postprocessing().units(), true).getDefaultState());
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                assertEquals(NativeTargetFit.Reason.CANDIDATE_LIMIT, failure.reason(), failure::toString);
                var cause = assertInstanceOf(NativeCollisionProvenance.DeclarationLimit.class, failure.cause().orElseThrow());
                assertEquals(4_096, cause.limit());
                assertEquals(4_097, cause.attempted());
                assertEquals(0, cause.accepted());
                assertEquals(0, acceptedOutputs.get());
                assertEquals(1, callbacks.get());
                assertEquals(1, transaction.resources().nativeAttempts());
                assertEquals(4_097L, transaction.postprocessing().units() - callbackWork[0]);
                assertTrue(transaction.numeric().units() > 1, "preflight work cannot be refunded");
            }
        }
    }

    @Test
    void strictSnapshotGetterFailureKeepsOriginalCauseWithoutAnotherQuery() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("strict-snapshot-getter"), PLAYER, false)) {
            fixture.serverPlayer(PLAYER); // Establish the fixture's actual spawn/collision columns.
            boolean[] armed = {false};
            var calls = new AtomicInteger();
            var original = new IllegalStateException("Free movement actor geometry changed outside its installation");
            EntityPlayerMP actor = new EntityPlayerMP(fixture.server(), fixture.world(),
                    new GameProfile(UUID.randomUUID(), "strict-getter"), new PlayerInteractionManager(fixture.world())) {
                @Override public AxisAlignedBB getEntityBoundingBox() {
                    AxisAlignedBB box = super.getEntityBoundingBox();
                    if (armed[0]) {
                        armed[0] = false;
                        calls.incrementAndGet();
                        throw original;
                    }
                    return box;
                }
            };
            new NetHandlerPlayServer(fixture.server(), new NetworkManager(EnumPacketDirection.SERVERBOUND), actor);
            assertNotNull(actor.connection);
            assertSame(actor, actor.connection.player);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            var target = cardinal(new Vec3d(8.5, 68.5, 8.5));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var observed = assertInstanceOf(NativeTargetFit.Observed.class,
                        NativeTargetFit.prepare(transaction, target, NativeTargetFit.SCAN));
                long numeric = transaction.numeric().units();
                int attempts = transaction.resources().nativeAttempts();
                armed[0] = true;
                var failure = observed.snapshot().strictNativeContextFailure(accepted, target.bounds()).orElseThrow();
                assertEquals(1, calls.get());
                assertEquals(WorldCollisionCandidates.Reason.NATIVE_QUERY_FAILED, failure.reason());
                assertSame(original, failure.cause().orElseThrow());
                assertEquals(attempts, transaction.resources().nativeAttempts());
                assertEquals(numeric, transaction.numeric().units());
            }
        }
    }

    @Test
    void initialOwnerGetterExceptionKeepsOriginalCauseBeforeAnyNativeAttempt() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("initial-getter-throw"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            var original = new IllegalStateException("Free movement actor geometry changed outside its installation");
            var getterCalls = new AtomicInteger();
            try (var transaction = transaction(fixture, actor, accepted)) {
                long before = transaction.numeric().units();
                BootWorldFixture.afterNextPlayerSleepingPredicate(actor, () -> {
                    getterCalls.incrementAndGet();
                    throw original;
                });
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(1, getterCalls.get());
                assertEquals(NativeTargetFit.Reason.NATIVE_QUERY_FAILED, failure.reason(), failure::toString);
                assertEquals("Free owner transaction is not current before native target observation", failure.detail());
                assertSame(original, failure.cause().orElseThrow());
                assertEquals(0, transaction.resources().nativeAttempts());
                assertEquals(before, transaction.numeric().units());
            }
        }
    }

    @Test
    void externalGeometryGetterExceptionKeepsOriginalCauseDespiteMatchingInvariantMessage() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("callback-getter-throw"), PLAYER, false)) {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            PlayerPhysicalSnapshot accepted = accepted(actor, new Vec3d(8.5, 68.5, 8.5));
            var original = new IllegalStateException("Free movement actor geometry changed outside its installation");
            var getterCalls = new AtomicInteger();
            placeCallback(fixture, () -> BootWorldFixture.afterNextPlayerSleepingPredicate(actor, () -> {
                getterCalls.incrementAndGet();
                throw original;
            }));
            try (var transaction = transaction(fixture, actor, accepted)) {
                var failure = assertInstanceOf(NativeTargetFit.Unavailable.class,
                        NativeTargetFit.prepare(transaction, cardinal(new Vec3d(8.5, 68.5, 8.5)), NativeTargetFit.SCAN));
                assertEquals(1, getterCalls.get(), "real eye-height getter must reach the armed sleeping predicate");
                assertEquals(NativeTargetFit.Reason.NATIVE_QUERY_FAILED, failure.reason(), failure::toString);
                assertSame(original, failure.cause().orElseThrow());
                assertTrue(original.getStackTrace().length > 0);
                assertEquals(1, transaction.resources().nativeAttempts());
                assertTrue(transaction.numeric().units() > 1);
            }
        }
    }

    private static void assertCommonEmptyCost(int query, long before, FreeTickResources resources,
                                              WorldCollisionCandidates.Snapshot snapshot) {
        CollisionQueryFootprint footprint = snapshot.footprint();
        assertEquals(Double.doubleToLongBits(World.MAX_ENTITY_RADIUS),
                Double.doubleToLongBits(footprint.entityRadius()));
        int columns = footprint.columns().size();
        int sections = footprint.lastEntitySection() - footprint.firstEntitySection() + 1;
        long roots = snapshot.rootEntitiesObserved();
        long candidates = snapshot.boxes().size();
        long expected = 1L + footprint.blockCellsUpperBound() + 2L * columns
                + 2L * columns * sections + 2L * roots + candidates;
        long actual = resources.numeric().units() - before;
        assertTrue(snapshot.boxes().isEmpty());
        assertEquals(expected, actual);
        System.out.println("NativeTargetFit empty query=" + query + " MAX_ENTITY_RADIUS=" + World.MAX_ENTITY_RADIUS
                + " C=" + footprint.blockCellsUpperBound() + " K=" + columns + " S=" + sections
                + " R=" + roots + " B=" + candidates + " units=" + actual);
    }

    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor, Vec3d center) {
        PlayerBodyPlacement placement = new PlayerBodyPlacement(center, CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(DIMENSIONS));
        actor.width = (float) DIMENSIONS.width();
        actor.height = (float) DIMENSIONS.height();
        Vec3d transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        Aabb bounds = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        FreeOwnerTransaction.installEffectiveEyeHeight(actor, DIMENSIONS.eyeHeight());
        var expectedGeometry = new WorldCollisionCandidates.ActorGeometry(actor.getUniqueID(), actor.dimension,
                placement.transport(), placement.bounds(), (float) DIMENSIONS.width(), (float) DIMENSIONS.height(),
                false);
        assertEquals(expectedGeometry, WorldCollisionCandidates.ActorGeometry.read(actor));
        assertEquals(Float.floatToIntBits((float) DIMENSIONS.eyeHeight()),
                Float.floatToIntBits(actor.getEyeHeight()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(),
                actor.dimension, 1), 7, placement, new Vec3d(0, 0, 0));
    }

    private static PlayerBodyPlacement cardinal(Vec3d center) {
        return new PlayerBodyPlacement(center, CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, DIMENSIONS));
    }

    private static FreeOwnerTransaction transaction(BootWorldFixture fixture, EntityPlayerMP actor,
                                                    PlayerPhysicalSnapshot accepted) {
        var travel = new PlayerPhysicsStep.TravelState();
        var owner = new Object();
        var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(),
                travel.correctionRevision());
        return new FreeOwnerTransaction(actor, accepted, travel, ledger(accepted), () -> true, owner, resources);
    }

    private static BoostMovementBudget ledger(PlayerPhysicalSnapshot accepted) {
        Vec3d transport = accepted.placement().transport();
        return new BoostMovementBudget(10, 0, new Vec3(transport.x(), transport.y(), transport.z()));
    }

    private static Block callbackBlock(Runnable callback) {
        return new Block(Material.ROCK) {
            @Override public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos,
                    AxisAlignedBB query, List<AxisAlignedBB> boxes, Entity actor, boolean actualState) {
                callback.run();
                super.addCollisionBoxToList(state, world, pos, query, boxes, actor, actualState);
            }
        };
    }

    private static Block candidateBoxesBlock(AtomicInteger callbacks, Aabb bounds, int count,
            AtomicInteger attempted, AtomicInteger acceptedOutputs, Runnable beforeOutput, boolean addAll) {
        AxisAlignedBB candidate = new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
        return new Block(Material.ROCK) {
            @Override public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos,
                    AxisAlignedBB query, List<AxisAlignedBB> boxes, Entity actor, boolean actualState) {
                callbacks.incrementAndGet();
                beforeOutput.run();
                if (addAll) {
                    boxes.addAll(java.util.Collections.nCopies(count, candidate));
                    acceptedOutputs.addAndGet(count);
                } else {
                    for (int index = 0; index < count; index++) {
                        attempted.incrementAndGet();
                        boxes.add(candidate);
                        acceptedOutputs.incrementAndGet();
                    }
                }
            }
        };
    }

    private static void placeCallback(BootWorldFixture fixture, Runnable callback) {
        fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                .setBlockState(TARGET_BLOCK, callbackBlock(callback).getDefaultState());
    }

}
