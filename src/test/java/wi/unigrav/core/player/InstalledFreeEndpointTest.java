package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.entity.player.EntityPlayerMP;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner/install/query APIs on the TEST mapped-World bridge; not normal-FML evidence. */
class InstalledFreeEndpointTest {
    @TempDir Path directory;
    private static final UUID PLAYER = new UUID(0, 9574);
    private static final CollisionQueryFootprint.Limits LIMITS =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final BlockPos CELL = new BlockPos(8, 68, 8);

    record Scene(BootWorldFixture fixture, FreeOwnerTransaction tx, AtomicBoolean current,
            PlayerPhysicsStep.TravelState travel, BoostMovementBudget ledger,
            PlayerBodyPlacement target, CertifiedReferenceEndpoint endpoint) implements AutoCloseable {
        public void close() { tx.close(); fixture.close(); }
        void writeTarget() {
            var actor = tx.actor(); var p = target.transport(); var b = target.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(),
                    b.max().x(), b.max().y(), b.max().z()));
            actor.posX = p.x(); actor.posY = p.y(); actor.posZ = p.z();
        }
        FreeOwnerTransaction.PreparedInstallation prepare() { return tx.prepareInstallation(target, endpoint); }
    }

    Scene scene(boolean cardinal, String name) throws Exception { return scene(cardinal, name, 10); }

    Scene scene(boolean cardinal, String name, double speed) throws Exception {
        return scene(cardinal, name, speed, actor -> { });
    }

    Scene scene(boolean cardinal, String name, double speed, Consumer<EntityPlayerMP> beforeGeometry) throws Exception {
        var fixture = BootWorldFixture.open(directory.resolve(name), PLAYER, false);
        var actor = fixture.serverPlayer(PLAYER);
        actor.width = .5F; actor.height = 1F; actor.eyeHeight = .25F;
        beforeGeometry.accept(actor);
        // Capture the real effective eye after the test establishes its native posture.
        var dimensions = new PlayerBodyShape.Dimensions(.5, 1, actor.getEyeHeight());
        var shape = cardinal ? new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions)
                : new PlayerBodyShape.FreeCapsule(dimensions);
        var placement = new PlayerBodyPlacement(new Vec3d(8.5, 69.5, 8.5),
                CardinalFrame.Y_POS.attitude(), shape);
        var p = placement.transport(); var b = placement.bounds();
        actor.setPosition(p.x(), p.y(), p.z());
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(),
                b.max().x(), b.max().y(), b.max().z()));
        fixture.world().getChunkProvider().getLoadedChunk(0, 0).setBlockState(CELL, Blocks.STONE.getDefaultState());
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,
                UUID.randomUUID(), actor.dimension, 1), 7, placement, new Vec3d(0, 0, 0));
        var travel = new PlayerPhysicsStep.TravelState(); var current = new AtomicBoolean(true);
        var ledger = new BoostMovementBudget(speed, 0, new Vec3(p.x(), p.y(), p.z()));
        var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, current::get);
        var target = cardinal ? new PlayerBodyPlacement(placement.center(), placement.attitude(),
                new PlayerBodyShape.FreeCapsule(dimensions)) : placement;
        var pose = CapsulePlacementMath.pose(target, tx.numeric());
        var window = CapsuleFootContacts.window(new PlayerPhysicalSnapshot(accepted.scope(), accepted.revision(),
                target, accepted.worldVelocity()), 0);
        var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        LIMITS, tx.resources().environmentObservationWork()));
        CapsulePathSource lineage;
        if (cardinal) {
            var contained = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(accepted, target, tx.numeric()));
            lineage = CapsulePathSource.containedCardinal(contained, pose, capture, window,
                    FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
        } else lineage = CapsulePathSource.actualFree(accepted, pose, capture, window,
                FreeOwnerTransaction.ACCURACY, tx.queries(), tx.numeric());
        return new Scene(fixture, tx, current, travel, ledger, target, CertifiedReferenceEndpoint.from(tx, lineage));
    }

    void installAndJoin(boolean cardinal) throws Exception {
        try (var s = scene(cardinal, cardinal ? "cardinal-first" : "free-first")) {
            var ledgerBefore = s.ledger.snapshot();
            var prepared = s.prepare();
            assertThrows(IllegalStateException.class, prepared::installedEndpoint);
            assertTrue(s.endpoint.current());
            s.tx.beginInstallation(prepared);
            assertFalse(s.endpoint.current(), "equal geometry must not resurrect a pre-install phase");
            s.writeTarget(); s.tx.recordInstalled(prepared);
            var installed = prepared.installedEndpoint();
            assertSame(s.tx, installed.transaction());
            assertSame(prepared.journal(), installed.owner());
            assertSame(s.endpoint.pose(), installed.pose());
            assertSame(s.tx.accepted(), s.endpoint.owner());
            assertEquals(s.tx.accepted().revision() + 1, installed.owner().revision());
            assertTrue(installed.current());
            assertFalse(s.endpoint.current());
            var window = CapsuleFootContacts.window(installed, 0);
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                            LIMITS, s.tx.resources().environmentObservationWork()));
            var support = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                    CapsuleSupportObservation.observe(installed, capture, window, 0, FreeOwnerTransaction.ACCURACY));
            assertFalse(support.supports().isEmpty(), () -> InstalledSupportDiagnostics.describe(installed, support, capture));
            assertTrue(support.matches(installed.owner(), installed.pose(), capture, window,
                    s.tx.queries(), s.tx.numeric()));
            int ordinal = support.supports().getFirst().ordinal();
            assertEquals(CELL, capture.blockProducer(installed, support, ordinal).orElseThrow().cell());
            assertFalse(support.matches(s.tx.accepted(), installed.pose(), capture, window,
                    s.tx.queries(), s.tx.numeric()));
            assertEquals(ledgerBefore, s.ledger.snapshot(), "observation cannot reserve/refund movement");
            var impostor = new PlayerPhysicalSnapshot(installed.owner().scope(), installed.owner().revision(),
                    installed.owner().placement(), installed.owner().worldVelocity());
            assertThrows(IllegalStateException.class, () -> s.tx.requireInstalledEndpoint(prepared, impostor, installed.pose()));
            var poseCopy = CapsulePlacementMath.pose(s.target, s.tx.numeric());
            assertThrows(IllegalStateException.class, () -> s.tx.requireInstalledEndpoint(prepared, installed.owner(), poseCopy));
            s.current.set(false);
            assertThrows(IllegalStateException.class, installed::requireCurrent);
            assertThrows(IllegalStateException.class, () -> support.matches(installed.owner(), installed.pose(),
                    capture, window, s.tx.queries(), s.tx.numeric()));
        }
    }
    @Test void firstCardinalToFreeJoinsActualInstalledFootAndProducer() throws Exception { installAndJoin(true); }
    @Test void originalFreeEqualGeometryInvalidatesOldPhaseAndJoinsInstalledSupport() throws Exception { installAndJoin(false); }

    @Test void ordinaryFreeActualReadyCommitJoinsInstalledFootAndProducer() throws Exception {
        try (var s = scene(false, "actual-free-commit")) {
            var pose = CapsulePlacementMath.pose(s.tx.accepted().placement(), s.tx.numeric());
            var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(0, 0, .125), s.tx.numeric());
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(), s.tx.tick(), tube.requestedDisplacementUpper()).accepted());
            var reserved = s.ledger.snapshot();
            var movementCapture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, CapsuleMovementResolver.window(s.tx.accepted(), tube),
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, LIMITS,
                            s.tx.resources().environmentObservationWork()));
            var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                    CapsuleMovementResolver.resolve(s.tx, tube, movementCapture));
            assertNull(movement.source());
            assertThrows(CapsulePathSource.InvalidSourceException.class,
                    () -> CapsulePathSource.continueAfter(movement, s.tx.numeric()));
            var preEndpoint = CertifiedReferenceEndpoint.fromMovement(s.tx, movement);
            assertTrue(preEndpoint.matches(s.tx, movement.end()));
            var equalPose = CapsulePlacementMath.pose(movement.end().placement(), s.tx.numeric());
            assertFalse(preEndpoint.matches(s.tx, equalPose));
            try (var foreign = new FreeOwnerTransaction(s.tx.actor(), s.tx.accepted(), s.travel,
                    s.ledger, s.current::get, s.travel, s.tx.resources())) {
                assertFalse(preEndpoint.matches(foreign, movement.end()));
                assertThrows(IllegalStateException.class, () -> CertifiedReferenceEndpoint.fromMovement(foreign, movement));
            }
            var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(s.tx, movement));
            var journal = ready.commit();
            assertFalse(preEndpoint.current());
            var endpoint = s.tx.installedFreeEndpoint();
            assertSame(movement.end(), endpoint.pose());
            assertCommitReceiptAndInstalledJournal(s, journal, movement, endpoint);
            assertFalse(s.endpoint.current());
            var window = CapsuleFootContacts.window(endpoint, 0);
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                            LIMITS, s.tx.resources().environmentObservationWork()));
            var complete = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                    CapsuleSupportObservation.observe(endpoint, capture, window, 0, FreeOwnerTransaction.ACCURACY));
            assertFalse(complete.supports().isEmpty(), () -> InstalledSupportDiagnostics.describe(endpoint, complete, capture));
            boolean joined = false;
            for (var support : complete.supports()) {
                var producer = capture.blockProducer(endpoint, complete, support.ordinal());
                if (producer.isPresent() && producer.orElseThrow().cell().equals(CELL)) joined = true;
            }
            assertTrue(joined, "real ordinary movement commit must retain exact floor producer evidence");
            assertEquals(reserved.seenSequence(), s.ledger.snapshot().seenSequence());
            assertEquals(reserved.distanceCredit(), s.ledger.snapshot().distanceCredit());
        }
    }

    @Test void ordinaryMultiCaptureSlideCommitsAndJoinsActualInstalledSupport() throws Exception {
        try (var s = scene(false, "actual-multicapture-slide", 60)) {
            var chunk = s.fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            chunk.setBlockState(new BlockPos(9,68,8), Blocks.STONE.getDefaultState());
            for (int y = 69; y <= 70; y++) chunk.setBlockState(new BlockPos(10,y,8), Blocks.STONE.getDefaultState());
            var pose = CapsulePlacementMath.pose(s.tx.accepted().placement(), s.tx.numeric());
            var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(2,0,.125), s.tx.numeric());
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(), s.tx.tick(), tube.requestedDisplacementUpper()).accepted());
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, CapsuleMovementResolver.window(s.tx.accepted(), tube),
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, LIMITS,
                            s.tx.resources().environmentObservationWork()));
            var result = CapsuleMovementResolver.resolveWithSlide(s.tx, tube, capture);
            var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class, result, result::toString);
            assertNull(movement.source());
            assertTrue(movement.segments().size() > 1);
            assertNotSame(movement.segments().getFirst().capture(), movement.segments().getLast().capture());
            assertNotEquals(movement.segments().getFirst().capture().footprint().requestedBounds(),
                    movement.segments().getLast().capture().footprint().requestedBounds());
            var preEndpoint = CertifiedReferenceEndpoint.fromMovement(s.tx, movement);
            var reserved = s.ledger.snapshot();
            var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(s.tx, movement));
            var journal = ready.commit();
            assertFalse(preEndpoint.current());
            var endpoint = s.tx.installedFreeEndpoint();
            assertSame(movement.end(), endpoint.pose()); assertCommitReceiptAndInstalledJournal(s, journal, movement, endpoint);
            var window = CapsuleFootContacts.window(endpoint, 0);
            var installedCapture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                            LIMITS, s.tx.resources().environmentObservationWork()));
            var complete = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                    CapsuleSupportObservation.observe(endpoint, installedCapture, window, 0, FreeOwnerTransaction.ACCURACY));
            assertFalse(complete.supports().isEmpty(), () -> InstalledSupportDiagnostics.describe(endpoint, complete, installedCapture));
            boolean floor = false;
            for (var support : complete.supports()) {
                var producer = installedCapture.blockProducer(endpoint, complete, support.ordinal());
                if (producer.isPresent() && producer.orElseThrow().cell().equals(new BlockPos(9,68,8))) floor = true;
            }
            assertTrue(floor);
            assertEquals(reserved.seenSequence(), s.ledger.snapshot().seenSequence());
            assertEquals(reserved.distanceCredit(), s.ledger.snapshot().distanceCredit());
        }
    }

    @Test void ordinaryZeroDeltaEqualGeometryDoesNotReviveMovementProofAfterInstall() throws Exception {
        try (var s = scene(false, "ordinary-zero-delta")) {
            var pose = CapsulePlacementMath.pose(s.tx.accepted().placement(), s.tx.numeric());
            var tube = CapsulePlacementMath.translationTube(pose, new Vec3d(0,0,0), s.tx.numeric());
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(), s.tx.tick(), tube.requestedDisplacementUpper()).accepted());
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, CapsuleMovementResolver.window(s.tx.accepted(), tube),
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, LIMITS,
                            s.tx.resources().environmentObservationWork()));
            var movement = assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                    CapsuleMovementResolver.resolve(s.tx, tube, capture));
            var preEndpoint = CertifiedReferenceEndpoint.fromMovement(s.tx, movement);
            assertTrue(preEndpoint.current());
            var ready = assertInstanceOf(CapsuleMoveFinalizer.Ready.class, CapsuleMoveFinalizer.prepare(s.tx, movement));
            ready.commit();
            assertFalse(preEndpoint.current());
            assertThrows(IllegalStateException.class, preEndpoint::requireCurrent);
            assertTrue(s.tx.installedFreeEndpoint().current());
        }
    }

    private static void assertCommitReceiptAndInstalledJournal(Scene s, PlayerPhysicalSnapshot receipt,
            CapsuleMovementResolver.Resolved movement, InstalledFreeEndpoint endpoint) {
        var journal = s.tx.installedJournal().orElseThrow();
        assertSame(journal, endpoint.owner());
        assertNotSame(receipt, journal, "commit receipt and pre-mutation custody journal are distinct objects");
        assertEquals(receipt.scope(), journal.scope());
        assertEquals(receipt.revision(), journal.revision());
        assertSame(movement.end().placement(), journal.placement());
        assertSame(movement.end().placement(), receipt.placement());
        assertEquals(receipt.worldVelocity(), journal.worldVelocity());
        assertTrue(receipt.contacts().isEmpty(), "the existing finalizer receipt omits contact metadata");
        assertEquals(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0, false),
                journal.contacts().orElseThrow(), "custody journal retains actual pre-mutation contacts");
    }

    @Test void mutationFailureCannotMintEvenWhenActualGeometryMatchesInstalledStage() throws Exception {
        try (var s = scene(false, "mutation-failure")) {
            var prepared = s.prepare(); s.tx.beginInstallation(prepared); s.writeTarget();
            var cause = new IllegalStateException("actual setter failure");
            s.tx.recordMutationFailure(prepared, cause);
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED, s.tx.installationStage());
            assertSame(cause, s.tx.originalFailure().orElseThrow());
            assertThrows(IllegalStateException.class, prepared::installedEndpoint);
        }
    }
    @Test void partialGeometryAndPostInstalledFailureRefuseCapability() throws Exception {
        try (var s = scene(false, "partial")) {
            var prepared = s.prepare(); s.tx.beginInstallation(prepared);
            s.tx.actor().posX += .125;
            assertThrows(IllegalStateException.class, () -> s.tx.recordInstalled(prepared));
            assertEquals(FreeOwnerTransaction.InstallationStage.PARTIAL, s.tx.installationStage());
            assertThrows(IllegalStateException.class, prepared::installedEndpoint);
        }
        try (var s = scene(false, "post-installed-failure")) {
            var prepared = s.prepare(); s.tx.beginInstallation(prepared); s.writeTarget(); s.tx.recordInstalled(prepared);
            var endpoint = prepared.installedEndpoint(); assertTrue(endpoint.current());
            var cause = new IllegalStateException("actual committed-tail failure");
            s.tx.recordFailure(cause);
            assertThrows(IllegalStateException.class, endpoint::requireCurrent);
        }
    }
    @Test void wrongPoseAndSameFrameForeignTransactionCannotBindInstallation() throws Exception {
        try (var s = scene(false, "foreign")) {
            var otherPose = s.target.withTranslatedCenter(s.target.center().add(new Vec3d(0, .125, 0)));
            assertThrows(IllegalArgumentException.class, () -> s.tx.prepareInstallation(otherPose, s.endpoint));
            try (var foreign = new FreeOwnerTransaction(s.tx.actor(), s.tx.accepted(), s.travel,
                    s.ledger, s.current::get, s.travel, s.tx.resources())) {
                assertSame(s.tx.queries(), foreign.queries()); assertSame(s.tx.numeric(), foreign.numeric());
                assertThrows(IllegalArgumentException.class, () -> foreign.prepareInstallation(s.target, s.endpoint));
            }
        }
    }
}
