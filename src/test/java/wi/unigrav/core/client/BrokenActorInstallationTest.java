package wi.unigrav.core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

class BrokenActorInstallationTest {
    @Test
    void installedEpochFloorSurvivesResetCompletionAndNewAttemptForExactActorManager(@TempDir Path directory) {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory, UUID.randomUUID(), false)) {
            EntityPlayerMP actor = fixture.serverPlayer(UUID.randomUUID());
            NetworkManager manager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetworkManager laterManager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            BrokenActorInstallation installation = new BrokenActorInstallation(actor, fixture.world(), 1, UUID.randomUUID());
            var failed = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 7);
            installation.recordScope(manager, failed);
            assertFalse(installation.allowsOffer(manager, failed));
            var reusedEpoch = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 7);
            assertFalse(installation.allowsOffer(manager, reusedEpoch), "a changed session UUID does not establish a new server generation");
            var staleEpoch = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 6);
            assertFalse(installation.allowsOffer(manager, staleEpoch));
            var advanced = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 8);
            assertTrue(installation.allowsOffer(manager, advanced));
            assertTrue(installation.allowsOffer(laterManager, staleEpoch), "another actual manager has a separate transport lifetime");

            installation.complete();
            assertFalse(installation.matchesActor(actor));
            assertTrue(installation.matchesActorIdentity(actor));
            assertFalse(installation.allowsOffer(manager, reusedEpoch), "clearing the physical fence cannot discard the retired generation");
            installation.beginAttempt(fixture.world(), 2);
            installation.recordScope(manager, advanced);
            assertTrue(installation.matchesActor(actor));
            assertFalse(installation.allowsOffer(manager, advanced));
            assertFalse(installation.allowsOffer(manager, failed));
            var next = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), UUID.randomUUID(), actor.dimension, 9);
            assertTrue(installation.allowsOffer(manager, next));
            installation.recordScope(laterManager, staleEpoch);
            assertFalse(installation.allowsOffer(laterManager, staleEpoch));
            assertFalse(installation.allowsOffer(manager, advanced), "changing manager does not erase the original manager's epoch floor");
            EntityPlayerMP sameUuidReplacement = fixture.serverPlayer(actor.getUniqueID());
            assertFalse(installation.matchesActorIdentity(sameUuidReplacement));
        }
    }

    @Test
    void brokenFenceUsesWeakActualActorIdentityAndAdvancingAttemptStamp(@TempDir Path directory) {
        UUID playerId = UUID.randomUUID();
        try (BootWorldFixture fixture = BootWorldFixture.open(directory, UUID.randomUUID(), false)) {
            EntityPlayerMP brokenActor = fixture.serverPlayer(playerId);
            EntityPlayerMP replacementWithSameId = fixture.serverPlayer(playerId);
            assertNotSame(brokenActor, replacementWithSameId);
            assertEquals(brokenActor.getUniqueID(), replacementWithSameId.getUniqueID());

            UUID diagnosticId = UUID.randomUUID();
            BrokenActorInstallation installation = new BrokenActorInstallation(
                    brokenActor, fixture.world(), 1, diagnosticId);
            assertTrue(installation.matchesActor(brokenActor));
            assertFalse(installation.matchesActor(replacementWithSameId),
                    "a replacement Entity object never inherits a partial-install fence by UUID");
            assertTrue(installation.matchesAttempt(brokenActor, fixture.world(), 1));

            installation.beginAttempt(fixture.world(), 2);
            assertTrue(installation.matchesActor(brokenActor),
                    "the same actual Entity remains fenced when its installation attempt advances");
            assertFalse(installation.matchesAttempt(brokenActor, fixture.world(), 1));
            assertTrue(installation.matchesAttempt(brokenActor, fixture.world(), 2));
            assertEquals(diagnosticId, installation.diagnosticId());
        }
    }
}
