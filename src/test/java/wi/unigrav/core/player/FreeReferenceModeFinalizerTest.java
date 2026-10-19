package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.Entity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.UnigravMod;
import wi.unigrav.proxy.CommonProxy;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.ActiveBootRules;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.floor.MagneticResponseRules;
import wi.unigrav.core.floor.PlayerPhysicsServerIntegrationTest;

import static org.junit.jupiter.api.Assertions.*;

class FreeReferenceModeFinalizerTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004381");

    @ParameterizedTest @ValueSource(strings = {"ready", "sample-failure", "world-read-failure", "owner-change", "teleport"})
    void installedPredictionSamplingRetainsExactJournalAndCommittedFailureCustody(String outcome) {
        var previousProxy = UnigravMod.proxy;
        try (var scenario = preparedWithCeiling(temp.resolve("installed-seed-" + outcome), 70, true, false, false, false)) {
            var tailCompleted = new AtomicBoolean();
            var tailObserved = new AtomicBoolean();
            var sampling = new AtomicBoolean();
            var sampled = new AtomicInteger();
            var tails = new AtomicInteger();
            var injected = new IllegalStateException("post-install prediction sample original");
            scenario.actor.onAddedToWorld();
            scenario.fixture.world().updateEntityWithOptionalForce(scenario.actor, false);
            scenario.fixture.afterNextEntityUpdate(scenario.actor, () -> {
                tails.incrementAndGet();
                tailObserved.set(true);
            });
            // The real native-environment operation must have completed its actual world tail before
            // this observer identifies the separate prediction sampling operation. No invocation ordinal.
            UnigravMod.proxy = new CommonProxy() {
                @Override public void runNativeEnvironment(Entity actor, Runnable operation) {
                    var active = FreeOwnerTransaction.nativeInstallation(scenario.actor);
                    assertNotNull(active);
                    assertSame(scenario.tx, active.transaction());
                    assertSame(scenario.tx.installedJournal().orElseThrow(), active.journal());
                    boolean predictionOperation = tailCompleted.get();
                    if (predictionOperation) {
                        sampled.incrementAndGet();
                        sampling.set(true);
                    }
                    try {
                        super.runNativeEnvironment(actor, operation);
                        assertSame(active, FreeOwnerTransaction.nativeInstallation(scenario.actor));
                        if (tailObserved.get()) tailCompleted.set(true);
                    } finally {
                        sampling.set(false);
                    }
                }
            };
            var callbacks = new AtomicInteger();
            if (outcome.equals("world-read-failure") || outcome.equals("owner-change") || outcome.equals("teleport")) scenario.fixture.onLoadedChunkReadWhen(sampling::get, () -> {
                callbacks.incrementAndGet();
                if (outcome.equals("world-read-failure")) throw injected;
                if (outcome.equals("owner-change")) scenario.travel.invalidate();
                else scenario.actor.connection.setPlayerLocation(12.5, 70, 8.5, 0, 0);
            });
            var binder = new GravityEntityBinder();
            var sampler = new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(),
                    .5, ActiveBootRules.core());
            var step = new PlayerPhysicsStep(sampler);
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 1);
            scenario.travel.nativeNoGravity = true;
            var ledger = scenario.ledger.snapshot();
            var referenceCommit = scenario.travel.referenceCommitSnapshot();
            PlayerPhysicsStep.Result result;
            try (var binding = outcome.equals("sample-failure") ? binder.bind(scenario.actor, GravityMode.REPLACE) : null;
                 var source = outcome.equals("sample-failure")
                         ? AmbientWorldFields.field(scenario.fixture.world()).registerContribution("installed-seed-test", "throwing", (point, time) -> {
                             if (!sampling.get()) return Vec3.ZERO;
                             callbacks.incrementAndGet();
                             throw injected;
                         }) : null) {
                result = step.stepOwned(scenario.actor, scenario.accepted, Optional.of(false), controls,
                        scenario.ledger, scenario.tx.tick(), false, scenario.travel, () -> true, scenario.tx);
            }
            assertEquals(1, tails.get(), "post-install prediction cannot replay the world tail");
            assertEquals(1, sampled.get(), "one seed operation must retain the original installed journal");
            assertEquals(ledger.seenSequence() + 1, scenario.ledger.snapshot().seenSequence());
            assertFalse(scenario.ledger.snapshot().pending());
            assertNull(FreeOwnerTransaction.nativeInstallation(scenario.actor), "lexical authority must clear on every exit");
            var nextActor = scenario.fixture.serverPlayer(UUID.randomUUID());
            assertNull(FreeOwnerTransaction.nativeInstallation(nextActor), "authority must not leak to the next actor");
            if (outcome.equals("ready")) {
                var applied = assertInstanceOf(PlayerPhysicsStep.Applied.class, result, result::toString);
                assertInstanceOf(PlayerBodyShape.Cardinal.class, applied.snapshot().placement().shape());
                assertTrue(applied.snapshot().contacts().isPresent());
                var seed = assertInstanceOf(CardinalPredictionSeed.Ready.class, scenario.travel.predictionSeed(applied.snapshot()));
                assertEquals(applied.snapshot().revision(), seed.snapshotRevision());
                assertEquals(CardinalFrame.X_POS, seed.frame());
                assertEquals(scenario.accepted.revision() + 1, applied.snapshot().revision());
                assertEquals(applied.snapshot().worldVelocity(), new Vec3d(scenario.actor.motionX,
                        scenario.actor.motionY, scenario.actor.motionZ).multiply(20));
            } else {
                assertEquals(1, callbacks.get(), "failure must occur in the real post-tail seed sample");
                var failed = assertInstanceOf(PlayerPhysicsStep.CommittedFailure.class, result, () -> {
                    var endpoint = result instanceof PlayerPhysicsStep.Applied applied ? applied.snapshot() : scenario.accepted;
                    var observedSeed = scenario.travel.predictionSeed(endpoint);
                    var detail = new java.io.StringWriter();
                    if (observedSeed instanceof CardinalPredictionSeed.Unavailable unavailable)
                        unavailable.cause().ifPresent(cause -> cause.printStackTrace(new java.io.PrintWriter(detail)));
                    return result + " seed=" + observedSeed + " cause=" + detail;
                });
                assertSame(scenario.tx.originalFailure().orElseThrow(), failed.cause());
                if (outcome.equals("sample-failure") || outcome.equals("world-read-failure")) {
                    assertSame(injected, failed.cause());
                    assertArrayEquals(injected.getStackTrace(), failed.cause().getStackTrace());
                }
                assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED, failed.custody().orElseThrow().stage());
                assertSame(scenario.tx.installedJournal().orElseThrow().placement(), failed.snapshot().placement());
                assertSame(referenceCommit, scenario.travel.referenceCommitSnapshot(),
                        "failed sampling must not publish a successful reference snapshot");
                var seed = assertInstanceOf(CardinalPredictionSeed.Unavailable.class, scenario.travel.predictionSeed(failed.snapshot()));
                assertEquals(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, seed.reason(), "no successful endpoint seed was published");
                if (outcome.equals("teleport")) assertEquals(12.5, scenario.actor.posX);
            }
        } finally {
            UnigravMod.proxy = previousProxy;
        }
    }

    @Test void qualifiedEndpointIronReplacedBySameBoxStoneInNativeTailFailsAfterOneCommit() {
        try (var scenario = preparedWithCeiling(temp.resolve("qualified-endpoint-tail"), 70, true, false, true)) {
            var before = scenario.ledger.snapshot();
            var tail = new AtomicInteger();
            scenario.actor.onAddedToWorld();
            scenario.fixture.world().updateEntityWithOptionalForce(scenario.actor, false);
            scenario.fixture.afterNextEntityUpdate(scenario.actor, () -> {
                tail.incrementAndGet();
                assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED, scenario.tx.installationStage());
                scenario.fixture.world().getChunkProvider().getLoadedChunk(0, 0)
                        .setBlockState(new BlockPos(7, 68, 8), Blocks.STONE.getDefaultState());
            });
            var sampler = new PlayerEnvironmentSampler(new GravityEntityBinder(), MagneticResponseRules.builtIn(),
                    .5, ActiveBootRules.core());
            var step = new PlayerPhysicsStep(sampler);
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 1);
            scenario.travel.nativeNoGravity = true;
            var result = step.stepOwned(scenario.actor, scenario.accepted, Optional.of(false), controls,
                    scenario.ledger, scenario.tx.tick(), false, scenario.travel, () -> true, scenario.tx);
            var failure = assertInstanceOf(PlayerPhysicsStep.CommittedFailure.class, result, () -> result.toString());
            assertEquals(1, tail.get());
            assertEquals("post-install qualified boot producer dependencies changed", failure.cause().getMessage());
            assertSame(failure.cause(), scenario.tx.originalFailure().orElseThrow());
            assertEquals(scenario.accepted.revision() + 1, failure.snapshot().revision());
            assertSame(failure.snapshot().placement(), scenario.tx.installedJournal().orElseThrow().placement());
            assertEquals(before.seenSequence() + 1, scenario.ledger.snapshot().seenSequence());
            assertFalse(scenario.ledger.snapshot().pending());
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED, failure.custody().orElseThrow().stage());
            // The real server retires this failed owner through failNativeEnvironment.
            // A second same-owner Step would not be a legal production continuation.

        }
    }

    @Test void touchingNativeSizedDownwardRequestClipsBeforeOneCardinalInstallation() {
        try (var scenario = preparedWithCeiling(temp.resolve("touching-clipped-prefix"),70,true,true)) {
            var movement = scenario.prepared.movement();
            assertNotNull(movement);
            assertSame(scenario.accepted.placement(),movement.end().placement(),"zero certified prefix retains exact query geometry");
            assertEquals(new Vec3d(0,-.08,0),movement.requestedDelta());
            assertEquals(java.util.Set.of(1),movement.blockedAxes());
            assertEquals(0,movement.pathUpper());
            assertTrue(scenario.prepared.preReserved());
            var reserved = scenario.ledger.snapshot();
            assertTrue(reserved.pending());
            var result = FreeReferenceModeFinalizer.install(scenario.prepared,scenario.travel,scenario.tx.tick());
            var applied = assertInstanceOf(FreeReferenceModeFinalizer.Applied.class,result,()->result.toString());
            assertInstanceOf(PlayerBodyShape.Cardinal.class,applied.snapshot().placement().shape());
            assertEquals(scenario.accepted.revision()+1,applied.snapshot().revision());
            assertEquals(0,applied.snapshot().worldVelocity().y());
            assertTrue(applied.snapshot().contacts().orElseThrow().vertical());
            assertTrue(applied.snapshot().contacts().orElseThrow().onGround());
            assertEquals(scenario.accepted.placement().center(),applied.snapshot().placement().center());
            assertEquals(reserved.seenSequence(),scenario.ledger.snapshot().seenSequence(),"single pre-reservation, no finalizer debit");
            assertEquals(reserved.distanceCredit(),scenario.ledger.snapshot().distanceCredit(),"unused correction grant is not refunded");
            assertFalse(scenario.ledger.snapshot().pending());
        }
    }

    @Test void pendingManualChangeAtReservedFinalRecaptureAbortsWithoutInstalling() {
        try (var scenario = prepared(temp.resolve("pending-manual-change"))) {
            var originalGeometry = WorldCollisionCandidates.ActorGeometry.read(scenario.actor);
            float originalEye = scenario.actor.getEyeHeight();
            var originalCredit = scenario.ledger.snapshot().distanceCredit();
            long expectedSequence = Math.incrementExact(scenario.ledger.snapshot().seenSequence());
            var replacement = new PlayerBodyReference.ManualIntent(2, CardinalFrame.Z_NEG,
                    new Vec3d(0, 1, 0));
            var callbackCount = new AtomicInteger();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.ledger.snapshot().pending(), () -> {
                callbackCount.incrementAndGet();
                assertReservationAtCallback(scenario, expectedSequence, originalGeometry, originalEye, originalCredit);
                scenario.travel.pendingManualIntent(Optional.of(replacement));
            });

            var result = FreeReferenceModeFinalizer.install(scenario.prepared, scenario.travel, scenario.tx.tick());

            var unavailable = assertInstanceOf(FreeReferenceModeFinalizer.Unavailable.class, result);
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.ACTOR_CHANGED, unavailable.reason());
            assertEquals(1, callbackCount.get(), "the final loaded-chunk recapture must happen exactly once");
            assertSame(replacement, scenario.travel.pendingManualIntent().orElseThrow());
            assertSame(scenario.sourceReference, scenario.travel.ownerReference());
            assertUninstalledAndAbortRetainsDebit(scenario, originalGeometry, originalEye, originalCredit,
                    expectedSequence);
        }
    }

    @Test void ownerReferenceChangeAtReservedFinalRecaptureAbortsWithoutInstalling() {
        try (var scenario = prepared(temp.resolve("owner-reference-change"))) {
            var originalGeometry = WorldCollisionCandidates.ActorGeometry.read(scenario.actor);
            float originalEye = scenario.actor.getEyeHeight();
            var originalCredit = scenario.ledger.snapshot().distanceCredit();
            long expectedSequence = Math.incrementExact(scenario.ledger.snapshot().seenSequence());
            var replacementReference = new PlayerBodyReference.FreeReference(Optional.empty(), Optional.empty());
            var callbackCount = new AtomicInteger();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.ledger.snapshot().pending(), () -> {
                callbackCount.incrementAndGet();
                assertReservationAtCallback(scenario, expectedSequence, originalGeometry, originalEye, originalCredit);
                scenario.travel.ownerReference(replacementReference);
            });

            var result = FreeReferenceModeFinalizer.install(scenario.prepared, scenario.travel, scenario.tx.tick());

            var unavailable = assertInstanceOf(FreeReferenceModeFinalizer.Unavailable.class, result);
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.ACTOR_CHANGED, unavailable.reason());
            assertEquals(1, callbackCount.get(), "the final loaded-chunk recapture must happen exactly once");
            assertSame(replacementReference, scenario.travel.ownerReference());
            assertSame(scenario.manual, scenario.travel.pendingManualIntent().orElseThrow());
            assertUninstalledAndAbortRetainsDebit(scenario, originalGeometry, originalEye, originalCredit,
                    expectedSequence);
        }
    }

    @Test void finalRecaptureExceptionRetainsExactCauseAndDoesNotInstall() {
        try (var scenario = prepared(temp.resolve("final-recapture-exception"))) {
            var originalGeometry = WorldCollisionCandidates.ActorGeometry.read(scenario.actor);
            float originalEye = scenario.actor.getEyeHeight();
            var originalCredit = scenario.ledger.snapshot().distanceCredit();
            long expectedSequence = Math.incrementExact(scenario.ledger.snapshot().seenSequence());
            var original = new IllegalStateException("injected loaded-chunk failure after center-path reservation");
            var originalStack = original.getStackTrace();
            var callbackCount = new AtomicInteger();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.ledger.snapshot().pending(), () -> {
                callbackCount.incrementAndGet();
                assertReservationAtCallback(scenario, expectedSequence, originalGeometry, originalEye, originalCredit);
                throw original;
            });

            var result = FreeReferenceModeFinalizer.install(scenario.prepared, scenario.travel, scenario.tx.tick());

            var unavailable = assertInstanceOf(FreeReferenceModeFinalizer.Unavailable.class, result);
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE, unavailable.reason());
            assertSame(original, unavailable.cause().orElseThrow(), "the owner boundary must preserve the original cause");
            assertArrayEquals(originalStack, original.getStackTrace(), "the original exception stack must remain intact");
            assertEquals(1, callbackCount.get(), "the final loaded-chunk recapture must happen exactly once");
            assertSame(scenario.sourceReference, scenario.travel.ownerReference());
            assertSame(scenario.manual, scenario.travel.pendingManualIntent().orElseThrow());
            assertUninstalledAndAbortRetainsDebit(scenario, originalGeometry, originalEye, originalCredit,
                    expectedSequence);
        }
    }

    @Test void insufficientTenMetersPerSecondGrantRejectsBeforeFinalRecaptureWithoutRefundingSpentDebit() {
        try (var scenario = preparedWithInsufficientGrant(temp.resolve("insufficient-center-path-grant"))) {
            var originalGeometry = WorldCollisionCandidates.ActorGeometry.read(scenario.actor);
            float originalEye = scenario.actor.getEyeHeight();
            var before = scenario.ledger.snapshot();
            double proposalPerTick = before.proposalCeilingMps() / 20.0D;
            assertTrue(scenario.prepared.centerPathUpper() > proposalPerTick,
                    "the real full-path upper bound must exceed the original 10 m/s one-tick proposal grant");
            assertTrue(scenario.prepared.centerPathUpper() > before.distanceCredit(),
                    "the real full-path upper bound must exceed available ledger credit");
            double expectedDebit = Math.min(scenario.prepared.centerPathUpper(),
                    Math.min(proposalPerTick, before.distanceCredit()));
            long expectedSequence = Math.incrementExact(before.seenSequence());
            var callbackCount = new AtomicInteger();
            scenario.fixture.onLoadedChunkReadWhen(() -> scenario.ledger.snapshot().pending(), callbackCount::incrementAndGet);

            var result = FreeReferenceModeFinalizer.install(scenario.prepared, scenario.travel, scenario.tx.tick());

            var unavailable = assertInstanceOf(FreeReferenceModeFinalizer.Unavailable.class, result);
            assertEquals(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE, unavailable.reason());
            var cause = unavailable.cause().orElseThrow();
            assertInstanceOf(IllegalStateException.class, cause);
            assertTrue(cause.getMessage().contains("exact reserved center-path grant"),
                    "the original production grant rejection must remain observable");
            assertEquals(0, callbackCount.get(), "an underfunded path must fail before final recapture");
            var reserved = scenario.ledger.snapshot();
            assertTrue(reserved.pending(), "the rejected finalization must retain its real reservation until close");
            assertEquals(expectedSequence, reserved.seenSequence());
            assertEquals(expectedDebit, before.distanceCredit() - reserved.distanceCredit(), 0.0,
                    "the ledger must debit exactly the clipped real grant");
            assertUninstalledAndAbortRetainsDebit(scenario, originalGeometry, originalEye,
                    before.distanceCredit(), expectedSequence);
        }
    }

    private static void assertReservationAtCallback(Scenario scenario, long expectedSequence,
            WorldCollisionCandidates.ActorGeometry originalGeometry, float originalEye, double beforeCredit) {
        var snapshot = scenario.ledger.snapshot();
        assertTrue(snapshot.pending(), "the final capture callback must observe the live center-path reservation");
        assertEquals(expectedSequence, snapshot.seenSequence(),
                "the callback must observe the sequence consumed by this reservation");
        assertEquals(Math.max(0.0D, beforeCredit - scenario.prepared.centerPathUpper()), snapshot.distanceCredit(), 0.0D,
                "the callback must observe the exact full center-path debit");
        assertSame(scenario.accepted, scenario.tx.accepted(),
                "the final capture callback must still observe the accepted owner snapshot");
        assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scenario.tx.installationStage());
        assertTrue(scenario.tx.installedPlacement().isEmpty());
        assertTrue(scenario.tx.installedJournal().isEmpty());
        assertEquals(originalGeometry, WorldCollisionCandidates.ActorGeometry.read(scenario.actor),
                "final recapture must run before any native geometry mutation");
        assertEquals(originalEye, scenario.actor.getEyeHeight(), 0.0F,
                "final recapture must run before any effective eye mutation");
    }

    private static void assertUninstalledAndAbortRetainsDebit(Scenario scenario,
            WorldCollisionCandidates.ActorGeometry originalGeometry, float originalEye, double originalCredit,
            long expectedSequence) {
        assertSame(scenario.accepted, scenario.tx.accepted(), "failed recapture must preserve accepted owner authority");
        assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scenario.tx.installationStage());
        assertTrue(scenario.tx.installedPlacement().isEmpty(), "failure before install cannot stage a placement");
        assertTrue(scenario.tx.installedJournal().isEmpty(), "failure before install cannot commit a successor journal");
        assertEquals(originalGeometry, WorldCollisionCandidates.ActorGeometry.read(scenario.actor),
                "pre-install failure cannot mutate native geometry");
        assertEquals(originalEye, scenario.actor.getEyeHeight(), 0.0F,
                "pre-install failure must preserve the effective eye height");
        assertTrue(scenario.ledger.snapshot().pending(), "the real center-path reservation must remain pending until close");
        assertEquals(expectedSequence, scenario.ledger.snapshot().seenSequence());
        double debitedCredit = scenario.ledger.snapshot().distanceCredit();
        assertTrue(debitedCredit < originalCredit, "the accepted reservation debit must be observable");

        scenario.tx.close();

        assertFalse(scenario.ledger.snapshot().pending(), "closing an unsettled owner transaction must abort its ticket");
        assertEquals(debitedCredit, scenario.ledger.snapshot().distanceCredit(), 0.0,
                "aborting the failed tail must not refund the already reserved path");
        assertSame(scenario.accepted, scenario.tx.accepted());
        assertEquals(FreeOwnerTransaction.InstallationStage.NONE, scenario.tx.installationStage());
        assertTrue(scenario.tx.installedPlacement().isEmpty());
        assertTrue(scenario.tx.installedJournal().isEmpty());
        assertEquals(originalGeometry, WorldCollisionCandidates.ActorGeometry.read(scenario.actor));
        assertEquals(originalEye, scenario.actor.getEyeHeight(), 0.0F,
                "aborting the unsettled owner transaction must preserve the effective eye height");
    }

    private static Scenario prepared(Path directory) {
        // Read the real ordinary owner profile first and close that server fixture before opening this independent world.
        var ordinaryProfile = PlayerPhysicsServerIntegrationTest.ordinaryProfileSnapshot(
                directory.resolve("ordinary-owner-profile"));
        assertTrue(ordinaryProfile.ceilingMps() > 0.0D,
                "the callback fixture must use a positive ceiling observed from the real ordinary owner");
        return preparedWithCeiling(directory.resolve("finalizer-target"), ordinaryProfile.ceilingMps(), true);
    }

    private static Scenario preparedWithInsufficientGrant(Path directory) {
        return preparedWithCeiling(directory.resolve("insufficient-grant-target"), 10.0D, false);
    }

    /**
     * Builds an independent component ledger. Callback cases use the real ordinary server owner's observed ceiling;
     * this ledger is profile-matched, not the server ledger or its movement custody.
     */
    private static Scenario preparedWithCeiling(Path directory, double ceilingMps, boolean requireFullGrant) {
        return preparedWithCeiling(directory,ceilingMps,requireFullGrant,false);
    }

    private static Scenario preparedWithCeiling(Path directory, double ceilingMps, boolean requireFullGrant, boolean touching) {
        return preparedWithCeiling(directory, ceilingMps, requireFullGrant, touching, false);
    }

    private static Scenario preparedWithCeiling(Path directory, double ceilingMps, boolean requireFullGrant,
            boolean touching, boolean qualifiedEndpoint) {
        return preparedWithCeiling(directory, ceilingMps, requireFullGrant, touching, qualifiedEndpoint, true);
    }

    private static Scenario preparedWithCeiling(Path directory, double ceilingMps, boolean requireFullGrant,
            boolean touching, boolean qualifiedEndpoint, boolean prepareFinalizer) {
        var fixture = BootWorldFixture.open(directory, PLAYER, false);
        FreeOwnerTransaction tx = null;
        try {
            EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
            actor.width = .6F;
            actor.height = 1.8F;
            actor.setNoGravity(true);
            var dimensions = new PlayerBodyShape.Dimensions((double) actor.width, (double) actor.height, actor.getEyeHeight());
            var placement = new PlayerBodyPlacement(new Vec3d(8.5, touching ? 65+dimensions.height()/2 : 70, 8.5), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(dimensions));
            actor.setPosition(placement.transport().x(), placement.transport().y(), placement.transport().z());
            actor.setEntityBoundingBox(new AxisAlignedBB(placement.bounds().min().x(), placement.bounds().min().y(),
                    placement.bounds().min().z(), placement.bounds().max().x(), placement.bounds().max().y(),
                    placement.bounds().max().z()));
            var scope = new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(), actor.dimension, 1);
            var accepted = new PlayerPhysicalSnapshot(scope, 7, placement, new Vec3d(0, 0, 0));
            var travel = new PlayerPhysicsStep.TravelState();
            var frame = touching ? CardinalFrame.Y_POS : CardinalFrame.X_POS;
            var manual = new PlayerBodyReference.ManualIntent(1, frame, touching ? new Vec3d(0,0,1) : new Vec3d(0, 1, 0));
            var sourceReference = new PlayerBodyReference.FreeReference(qualifiedEndpoint ? Optional.empty() : Optional.of(manual), Optional.empty());
            travel.ownerReference(sourceReference);
            travel.pendingManualIntent(qualifiedEndpoint ? Optional.empty() : Optional.of(manual));
            var ledger = new BoostMovementBudget(ceilingMps, fixture.world().getTotalWorldTime(),
                    new Vec3(placement.transport().x(), placement.transport().y(), placement.transport().z()));
            var store = new FreeTickResources.Store();
            var resources = store.acquire(travel, fixture.world(), scope, travel.correctionRevision());
            tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, () -> true, travel, resources);

            var binder = new GravityEntityBinder();
            var sampler = new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), .5,
                    ActiveBootRules.core());
            var baseChunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(baseChunk);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(baseChunk));
            var delta = touching ? new Vec3d(0,-.08,0) : new Vec3d(0,0,0);
            var footprint = PlayerPhysicsServerBudgetTestAccess.installFreeToCardinalCaptureFootprint(
                    fixture, accepted, frame, delta);
            if(touching) {
                baseChunk.setBlockState(new net.minecraft.util.math.BlockPos(8,64,8),net.minecraft.init.Blocks.STONE.getDefaultState());
                assertEquals(65,placement.bounds().min().y());
            }
            assertFalse(footprint.columns().isEmpty());
            for (ChunkPos column : footprint.columns()) {
                var chunk = fixture.world().getChunkProvider().getLoadedChunk(column.x, column.z);
                assertNotNull(chunk, "the exact prepared capture columns must be loaded before evidence is sampled");
                FloorWorldFields.onChunkLoad(new ChunkEvent.Load(chunk));
            }
            if (qualifiedEndpoint) {
                actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                baseChunk.setBlockState(new BlockPos(7, 68, 8), Blocks.IRON_BLOCK.getDefaultState());
            }
            if (!prepareFinalizer)
                return new Scenario(fixture, actor, accepted, travel, manual, sourceReference, tx, ledger, null);
            var sample = assertInstanceOf(PlayerEnvironmentSampler.FreeSample.class,
                    sampler.sampleFree(tx, Optional.of(false), FreeReferenceModePrepared.SCAN));
            FreeStandingObservation sourceObservation = sample.observation();
            if (qualifiedEndpoint) {
                var pose = CapsulePlacementMath.pose(accepted.placement(), tx.numeric());
                var queryWindow = CapsuleFootContacts.window(accepted, .25);
                var captured = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                        WorldCollisionCandidates.capture(tx, queryWindow, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                                FreeReferenceModePrepared.SCAN, tx.resources().environmentObservationWork()));
                var root = CapsulePathSource.actualFree(accepted, pose, captured, queryWindow,
                        FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
                var endpoint = CertifiedReferenceEndpoint.from(tx, root);
                sourceObservation = assertInstanceOf(PlayerEnvironmentSampler.EndpointReference.class,
                        sample.observeReference(endpoint, false)).observation();
                assertTrue(sourceObservation.isEndpointObservation());
                assertFalse(sourceObservation.boots().measurements().orElseThrow().candidates().isEmpty());
                return new Scenario(fixture, actor, accepted, travel, manual, sourceReference, tx, ledger, null);
            }

            var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL,
                    frame, manual.tangentHeading());
            var installedState = new PlayerBodyReference.State(Optional.of(manual), effective,
                    PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
            var selection = new FreeReferenceModePrepared.Selection(effective, Optional.of(manual), installedState,
                    manual.requestId());
            if(touching) actor.motionY=-.08; // same pre-Prepared integrated motion seam used by Step
            var ledgerBeforePrepare = ledger.snapshot();
            var preparation = FreeReferenceModePrepared.prepare(tx, sourceObservation, sourceReference,
                    manual, null, selection, delta);
            if (!(preparation instanceof FreeReferenceModePrepared.Prepared prepared)) {
                var unavailable = (FreeReferenceModePrepared.Unavailable) preparation;
                throw new AssertionError("the real fixture must prepare the accepted Free-to-Cardinal path: "
                        + unavailable.reason() + ": " + unavailable.detail(), unavailable.cause().orElse(null));
            }
            if(touching) {
                assertTrue(ledger.snapshot().pending());
                assertEquals(ledgerBeforePrepare.seenSequence()+1,ledger.snapshot().seenSequence());
                return new Scenario(fixture,actor,accepted,travel,manual,sourceReference,tx,ledger,prepared);
            }
            assertFalse(ledger.snapshot().pending(), "preparation must finish without a center-path reservation");
            assertEquals(ledgerBeforePrepare.seenSequence(), ledger.snapshot().seenSequence(),
                    "preparation must not consume the finalizer's reservation sequence");
            assertTrue(prepared.centerPathUpper() > 0, "the installation must reserve a nonzero verified center path");
            var availableBeforeInstall = ledger.snapshot();
            double proposalPerTick = availableBeforeInstall.proposalCeilingMps() / 20.0D;
            if (requireFullGrant) {
                assertTrue(prepared.centerPathUpper() <= proposalPerTick,
                        "the real ordinary profile's one-tick proposal must cover the full prepared path");
                assertTrue(prepared.centerPathUpper() <= availableBeforeInstall.distanceCredit(),
                        "the profile-matched component ledger must have credit for the full prepared path");
            } else {
                assertTrue(prepared.centerPathUpper() > proposalPerTick,
                        "the original 10 m/s fixture must remain a genuine underfunded proposal");
                assertTrue(prepared.centerPathUpper() > availableBeforeInstall.distanceCredit(),
                        "the original 10 m/s fixture must remain a genuine underfunded credit bucket");
            }
            return new Scenario(fixture, actor, accepted, travel, manual, sourceReference, tx, ledger, prepared);
        } catch (RuntimeException | Error failure) {
            FreeOwnerTransaction transaction = tx;
            if (transaction != null) addSuppressedCleanup(failure, transaction::close);
            addSuppressedCleanup(failure, FloorWorldFields::clearAll);
            addSuppressedCleanup(failure, fixture::close);
            throw failure;
        }
    }

    private static void addSuppressedCleanup(Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
        }
    }

    private static Throwable collectCleanupFailure(Throwable primary, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary == null) return cleanupFailure;
            if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
        }
        return primary;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new AssertionError("unexpected checked cleanup failure", failure);
    }

    private static final class Scenario implements AutoCloseable {
        private final BootWorldFixture fixture;
        private final EntityPlayerMP actor;
        private final PlayerPhysicalSnapshot accepted;
        private final PlayerPhysicsStep.TravelState travel;
        private final PlayerBodyReference.ManualIntent manual;
        private final PlayerBodyReference.FreeReference sourceReference;
        private final FreeOwnerTransaction tx;
        private final BoostMovementBudget ledger;
        private final FreeReferenceModePrepared.Prepared prepared;

        private Scenario(BootWorldFixture fixture, EntityPlayerMP actor, PlayerPhysicalSnapshot accepted,
                PlayerPhysicsStep.TravelState travel, PlayerBodyReference.ManualIntent manual,
                PlayerBodyReference.FreeReference sourceReference, FreeOwnerTransaction tx,
                BoostMovementBudget ledger, FreeReferenceModePrepared.Prepared prepared) {
            this.fixture = fixture;
            this.actor = actor;
            this.accepted = accepted;
            this.travel = travel;
            this.manual = manual;
            this.sourceReference = sourceReference;
            this.tx = tx;
            this.ledger = ledger;
            this.prepared = prepared;
        }

        @Override public void close() {
            Throwable failure = collectCleanupFailure(null, tx::close);
            failure = collectCleanupFailure(failure, FloorWorldFields::clearAll);
            failure = collectCleanupFailure(failure, fixture::close);
            rethrowCleanupFailure(failure);
        }
    }
}
