package wi.unigrav.core.client;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.FreeTickResourcesTestAccess;
import static org.junit.jupiter.api.Assertions.*;

class ClientFreeEnvironmentResourcesTest {
    @Test void synchronizedWorldTimeChangesDoNotAdvanceTheInstalledClientSimulationFrame(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 966), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 967));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 968), actor.dimension, 1);
            actor.world.setTotalWorldTime(100);
            var simulationTick = new AtomicLong(1);
            var owner = new ClientFreeEnvironmentResources(actor, actor.world, scope, -1, simulationTick::get);
            var first = owner.acquire(actor.world, scope, -1);
            actor.world.setTotalWorldTime(95);
            assertSame(first, owner.acquire(actor.world, scope, -1),
                    "a server time synchronization is not a client simulation tick or a new grant");
            actor.world.setTotalWorldTime(1000);
            assertSame(first, owner.acquire(actor.world, scope, -1));
            assertDoesNotThrow(() -> first.requireCurrent(actor, actor.world, scope, -1));
            simulationTick.incrementAndGet();
            var next = owner.acquire(actor.world, scope, -1);
            assertNotSame(first, next);
            assertSame(next, owner.acquire(actor.world, scope, -1));
            assertThrows(IllegalStateException.class, () -> first.requireCurrent(actor, actor.world, scope, -1));
        }
    }

    @Test void unchangedInstalledScopeSharesTickAndNormalNewTickCreatesOnlyNextFrame(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 960), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 961));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 962), actor.dimension, 1);
            var simulationTick = new AtomicLong(1);
            var owner = new ClientFreeEnvironmentResources(actor, actor.world, scope, -1, simulationTick::get);
            var first = owner.acquire(actor.world, scope, -1);
            assertSame(first, owner.acquire(actor.world, scope, -1));
            actor.world.setTotalWorldTime(actor.world.getTotalWorldTime() + 1);
            simulationTick.incrementAndGet();
            assertNotSame(first, owner.acquire(actor.world, scope, -1));
        }
    }

    @Test void correctionRetiresAcrossTicksWithoutGrantingReplacementResources(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 963), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 964));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 965), actor.dimension, 1);
            var simulationTick = new AtomicLong(1);
            var owner = new ClientFreeEnvironmentResources(actor, actor.world, scope, -1, simulationTick::get);
            var first = owner.acquire(actor.world, scope, -1);
            assertThrows(IllegalStateException.class, () -> owner.acquire(actor.world, scope, 7));
            actor.world.setTotalWorldTime(actor.world.getTotalWorldTime() + 1);
            simulationTick.incrementAndGet();
            assertThrows(IllegalStateException.class, () -> owner.acquire(actor.world, scope, -1));
            assertThrows(IllegalStateException.class, () -> first.requireCurrent(actor, actor.world, scope, -1));
        }
    }
    @Test void preparedCorrectionPausesThenTransfersOnlyItsExactClientOwner(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 969), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 970));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 971), actor.dimension, 1);
            var simulationTick = new AtomicLong(1);
            var owner = new ClientFreeEnvironmentResources(actor, actor.world, scope, -1, simulationTick::get);
            var first = owner.acquire(actor.world, scope, -1);
            FreeTickResourcesTestAccess.chargeNumeric(first, 13);
            var transfer = owner.prepareCorrection(7);
            assertThrows(IllegalStateException.class, () -> FreeTickResourcesTestAccess.claimNativeAttempt(first));
            owner.completeCorrection(transfer, 7, () -> {});
            var completed = owner.acquire(actor.world, scope, 7);
            assertEquals(13, FreeTickResourcesTestAccess.numericUnits(completed));
            assertSame(completed, owner.acquire(actor.world, scope, 7), "ordinary reuse grants no new frame");
            assertThrows(IllegalStateException.class, () -> owner.completeCorrection(transfer, 7, () -> {}));
            var next = owner.prepareCorrection(8);
            owner.invalidate();
            assertThrows(IllegalStateException.class, () -> owner.completeCorrection(next, 8, () -> {}));
        }
    }

    @Test void facadeFenceAfterClockCapturePreventsResumePublication(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 972), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 973));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 974), actor.dimension, 1);
            var owner = new ClientFreeEnvironmentResources(actor, actor.world, scope, 91, () -> 1);
            var old = owner.acquire(actor.world, scope, 91);
            var transfer = owner.prepareResume();
            var lostContext = new IllegalStateException("accepted facade context replaced during clock capture");
            assertSame(lostContext, assertThrows(IllegalStateException.class, () -> owner.completeResume(transfer, () -> {
                owner.invalidate();
                throw lostContext;
            })));
            assertThrows(IllegalStateException.class, () -> FreeTickResourcesTestAccess.claimNativeAttempt(old));
            assertThrows(IllegalStateException.class, () -> owner.acquire(actor.world, scope, -1));
        }
    }

}
