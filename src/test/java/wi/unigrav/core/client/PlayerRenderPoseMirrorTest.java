package wi.unigrav.core.client;

import java.nio.file.Path;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Aabb;
import wi.unigrav.core.player.CardinalFrame;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;

import static org.junit.jupiter.api.Assertions.*;

/** Headless real-WorldServer coverage for the cache seam; it does not simulate a WorldClient or GPU render. */
class PlayerRenderPoseMirrorTest {
    @Test void presentationReleaseAfterRemoveClearsRootAndPickButCannotReleaseANewLifetime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72921), true)) {
            var world = fixture.world();
            var local = fixture.serverPlayer(new UUID(0, 72922));
            var subject = fixture.serverPlayer(new UUID(0, 72923));
            assertTrue(world.spawnEntity(local)); assertTrue(world.spawnEntity(subject));
            UUID token = UUID.randomUUID(), physics = UUID.randomUUID();
            var cache = cache(world, local, token, 12, 9010);
            var geometry = new PlayerRenderPoseProtocol.CardinalGeometry(physics, 3,
                    new Vec3d(0, 0, 0), new Aabb(new Vec3d(-.3, 0, -.3), new Vec3d(.3, 1.8, .3)));
            var pose = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 1, 1,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Y_POS.ordinal(), IDENTITY, IDENTITY, geometry);
            assertTrue(cache.receive(pose, world, local, 1));
            assertTrue(cache.presentation(subject, world, local, .5F, 1).isPresent());
            subject.noClip = true;
            assertTrue(cache.presentation(subject, world, local, .5F, 1).isEmpty());
            assertEquals(1, cache.pickGeometry(world, local, local, .5F, 1).size());
            assertTrue(cache.pickGeometry(world, local, local, .5F, 1).getFirst().isSuppressed(),
                    "native-required flags cannot release a known Cardinal before the authoritative transition");
            subject.noClip = false;
            assertTrue(cache.receive(PlayerRenderPoseProtocol.Packet.remove(token, 12, subject.getUniqueID(),
                    subject.getEntityId(), 0, 9010, 1, 2, 1), world, local, 2));
            assertTrue(cache.pickGeometry(world, local, local, .5F, 2).getFirst().isSuppressed());
            var release = PlayerRenderPoseProtocol.Packet.presentationRelease(token, 12, subject.getUniqueID(),
                    subject.getEntityId(), 0, 9010, 1, 3, 2,
                    new PlayerRenderPoseProtocol.PresentationRelease(physics, 3, 1));
            assertTrue(cache.receive(PlayerRenderPoseProtocol.decode(PlayerRenderPoseProtocol.encode(release)), world, local, 3));
            assertTrue(cache.presentation(subject, world, local, .5F, 3).isEmpty());
            assertTrue(cache.geometry(subject, world, local, .5F, 3).isEmpty());
            assertTrue(cache.pickGeometry(world, local, local, .5F, 3).isEmpty());
            assertFalse(cache.receive(pose, world, local, 4));
            var fresh = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 2, 1, 3,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Y_POS.ordinal(), IDENTITY, IDENTITY, geometry);
            assertTrue(cache.receive(fresh, world, local, 4));
            assertFalse(cache.receive(release, world, local, 5));
            assertFalse(cache.pickGeometry(world, local, local, .5F, 5).getFirst().isSuppressed());
            var wrong = PlayerRenderPoseProtocol.Packet.presentationRelease(token, 12, subject.getUniqueID(),
                    subject.getEntityId(), 0, 9010, 2, 2, 3,
                    new PlayerRenderPoseProtocol.PresentationRelease(UUID.randomUUID(), 3, 2));
            assertFalse(cache.receive(wrong, world, local, 5));
            int entityId = subject.getEntityId(); UUID subjectId = subject.getUniqueID();
            world.removeEntity(subject);
            var replacement = fixture.serverPlayer(subjectId); replacement.setEntityId(entityId);
            assertTrue(world.spawnEntity(replacement));
            var reusedIdentityRelease = PlayerRenderPoseProtocol.Packet.presentationRelease(token, 12, subjectId,
                    entityId, 0, 9010, 2, 2, 3,
                    new PlayerRenderPoseProtocol.PresentationRelease(physics, 3, 2));
            assertFalse(cache.receive(reusedIdentityRelease, world, local, 6));
            cache.clear();
            assertFalse(cache.receive(reusedIdentityRelease, world, local, 7));
            assertTrue(cache.pickGeometry(world, local, local, .5F, 7).isEmpty());
        }
    }

    @Test void twoPendingTrackedPlayersCanArriveDuringOneImmutablePickSnapshot(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72911), true)) {
            var world = fixture.world();
            var local = fixture.serverPlayer(new UUID(0, 72912));
            var first = fixture.serverPlayer(new UUID(0, 72913));
            var second = fixture.serverPlayer(new UUID(0, 72914));
            assertTrue(world.spawnEntity(local));
            UUID token = UUID.randomUUID();
            var cache = cache(world, local, token, 12, 9010);
            var geometry = new PlayerRenderPoseProtocol.CardinalGeometry(UUID.randomUUID(), 3,
                    new Vec3d(0, 0, 0), new Aabb(new Vec3d(-.3, 0, -.3), new Vec3d(.3, 1.8, .3)));
            for (var player : java.util.List.of(first, second)) {
                var packet = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                        player.getUniqueID(), player.getEntityId(), 0, 9010, 1, 1, 0,
                        PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.Y_POS.ordinal(), IDENTITY, IDENTITY, geometry);
                assertTrue(cache.receive(packet, world, local, 1));
            }
            assertTrue(world.spawnEntity(first));
            assertTrue(world.spawnEntity(second));
            var samples = assertDoesNotThrow(() -> cache.pickGeometry(world, local, local, .5F, 2));
            assertEquals(2, samples.size());
            assertTrue(samples.stream().anyMatch(sample -> sample.entity() == first && !sample.isSuppressed()));
            assertTrue(samples.stream().anyMatch(sample -> sample.entity() == second && !sample.isSuppressed()));
            assertThrows(UnsupportedOperationException.class, samples::clear);
        }
    }

    @Test void trackedGeometryRefreshAbsenceScopeAndExpiryNeverReviveNativeYBox(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72901), true)) {
            var world = fixture.world();
            var local = fixture.serverPlayer(new UUID(0, 72902));
            var subject = fixture.serverPlayer(new UUID(0, 72903));
            assertTrue(world.spawnEntity(local));
            assertTrue(world.spawnEntity(subject));
            UUID token = UUID.randomUUID(), physics = UUID.randomUUID();
            var cache = cache(world, local, token, 12, 9010);
            var body = PlayerRenderPoseProtocol.Quaternion.fromUpForward(CardinalFrame.X_POS.up(), new Vec3d(0, 0, 1));
            var geometry = new PlayerRenderPoseProtocol.CardinalGeometry(physics, 3, new Vec3d(33.9, 88.1, 8.5),
                    new Aabb(new Vec3d(33, 88.1, 8.2), new Vec3d(34.8, 88.7, 8.8)));
            var first = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 1, 1,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, geometry);
            assertTrue(cache.receive(first, world, local, 1));
            assertTrue(cache.geometry(subject, world, local, .5F, 1).isPresent());
            var absent = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 2, 2,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body);
            assertTrue(cache.receive(absent, world, local, 2));
            assertTrue(cache.geometry(subject, world, local, .5F, 2).isEmpty());
            assertTrue(cache.presentation(subject, world, local, .5F, 2).isEmpty(),
                    "missing Cardinal geometry withdraws render ownership, not a Q with a guessed root");
            assertTrue(cache.pickGeometry(world, local, local, .5F, 2).getFirst().isSuppressed());
            var wrongScope = new PlayerRenderPoseProtocol.CardinalGeometry(UUID.randomUUID(), 3, geometry.transport(), geometry.bounds());
            var stale = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 3, 3,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, wrongScope);
            assertFalse(cache.receive(stale, world, local, 3));
            var wrongEpoch = new PlayerRenderPoseProtocol.CardinalGeometry(physics, 4, geometry.transport(), geometry.bounds());
            var epochPacket = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 3, 3,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, wrongEpoch);
            assertFalse(cache.receive(epochPacket, world, local, 3));
            var restored = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 3, 3,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, geometry);
            assertTrue(cache.receive(restored, world, local, 3));
            assertFalse(cache.pickGeometry(world, local, local, .5F, 3).getFirst().isSuppressed());
            var resized = new PlayerRenderPoseProtocol.CardinalGeometry(physics, 3, new Vec3d(33.9, 88.1, 8.5),
                    new Aabb(new Vec3d(32.1, 88.1, 7.9), new Vec3d(35.7, 89.3, 9.1)));
            var resizePacket = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 4, 4,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, resized);
            assertTrue(cache.receive(resizePacket, world, local, 4));
            var resizedSample = cache.geometry(subject, world, local, .5F, 4).orElseThrow();
            assertEquals(3.6, resizedSample.bounds().maxX - resizedSample.bounds().minX, 1e-10,
                    "same body quaternion must still refresh actual Resize dimensions");
            assertEquals(1.2, resizedSample.bounds().maxY - resizedSample.bounds().minY, 1e-10);
            cache.expire(world, local, 45);
            assertTrue(cache.pickGeometry(world, local, local, .5F, 45).getFirst().isSuppressed());
            var delayedWrongScope = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 1, 5, 5,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, wrongScope);
            assertFalse(cache.receive(delayedWrongScope, world, local, 45),
                    "numeric sequence advancement cannot replace the physics scope after entry TTL");
            var free = PlayerRenderPoseProtocol.Packet.pose(token, 12, subject.getUniqueID(), subject.getEntityId(),
                    0, 9010, 2, 1, 0, PlayerRenderPoseProtocol.Mode.FREE, -1, IDENTITY, IDENTITY);
            assertTrue(cache.receive(free, world, local, 46));
            assertTrue(cache.pickGeometry(world, local, local, .5F, 46).isEmpty());
            assertTrue(cache.presentation(subject, world, local, .5F, 46).isPresent(),
                    "a valid Free report retains its established render pose without a stale Cardinal box");
            var fresh = new PlayerRenderPoseProtocol.Packet(PlayerRenderPoseProtocol.Kind.POSE, token, 12, 0,
                    subject.getUniqueID(), subject.getEntityId(), 0, 9010, 3, 1, 1,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, CardinalFrame.X_POS.ordinal(), body, body, geometry);
            assertTrue(cache.receive(fresh, world, local, 47));
            assertTrue(cache.receive(PlayerRenderPoseProtocol.Packet.remove(token, 12, subject.getUniqueID(),
                    subject.getEntityId(), 0, 9010, 3, 2, 1), world, local, 48));
            assertTrue(cache.presentation(subject, world, local, .5F, 48).isEmpty());
            assertTrue(cache.pickGeometry(world, local, local, .5F, 48).getFirst().isSuppressed());
            cache.clear();
            assertTrue(cache.pickGeometry(world, local, local, .5F, 44).isEmpty());
        }
    }

    @Test void acceptedHorizontalBoxAndRootShareTransportInterpolationAndExcludeOldYOnlyRay() {
        var geometry = new PlayerRenderPoseProtocol.CardinalGeometry(UUID.randomUUID(), 3, new Vec3d(33.9, 88.1, 8.5),
                new Aabb(new Vec3d(33, 88.1, 8.2), new Vec3d(34.8, 88.7, 8.8)));
        var sample = PlayerRenderPoseMirror.PoseCache.sampleGeometry(geometry, CardinalFrame.X_POS.ordinal(),
                33.9, 88.1, 8.5, 35.9, 88.1, 8.5, .5F);
        assertEquals(34, sample.bounds().minX, 1e-10);
        assertEquals(-.9, sample.rootOffset().x(), 1e-10);
        assertEquals(.3, sample.rootOffset().y(), 1e-10);
        assertNotNull(sample.bounds().calculateIntercept(new net.minecraft.util.math.Vec3d(33, 88.4, 8.5),
                new net.minecraft.util.math.Vec3d(35, 88.4, 8.5)));
        assertNull(sample.bounds().calculateIntercept(new net.minecraft.util.math.Vec3d(34.9, 89.5, 7),
                new net.minecraft.util.math.Vec3d(34.9, 89.5, 10)));
    }
    private static final PlayerRenderPoseProtocol.Quaternion IDENTITY =
            new PlayerRenderPoseProtocol.Quaternion(0, 0, 0, 1);

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void sameServerWorldReentryRejectsUnconsumedIntermediateWelcomeAndOldRealmPackets(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72001), true)) {
            WorldServer world = fixture.world();
            UUID localId = new UUID(0, 72002);
            EntityPlayerMP firstLocal = fixture.serverPlayer(localId);
            EntityPlayerMP intermediateLocal = fixture.serverPlayer(localId);
            EntityPlayerMP currentLocal = fixture.serverPlayer(localId);
            assertNotEquals(firstLocal.getEntityId(), intermediateLocal.getEntityId());
            assertNotEquals(intermediateLocal.getEntityId(), currentLocal.getEntityId());

            NetHandlerPlayServer stableConnection = firstLocal.connection;
            UUID token = UUID.randomUUID();
            var gate = new PlayerRenderPoseMirror.WelcomeGate();
            gate.beginConnection(stableConnection, token);
            long firstGeneration = gate.bind(stableConnection, world, firstLocal, 0);
            assertEquals(1, firstGeneration);
            var firstWelcome = PlayerRenderPoseProtocol.Packet.welcome(token, 41, firstGeneration,
                    localId, firstLocal.getEntityId(), 0, 9001);
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.ACCEPTED,
                    gate.accept(firstWelcome, stableConnection, world, firstLocal, token, firstGeneration, 0));

            // The server may have emitted this same-dimension intermediate WELCOME, but the
            // client transitions twice before consuming it. A current-looking local binding
            // cannot make an older request generation eligible.
            long intermediateGeneration = gate.bind(stableConnection, world, intermediateLocal, 0);
            assertEquals(2, intermediateGeneration);
            currentLocal.setEntityId(intermediateLocal.getEntityId());
            assertEquals(intermediateLocal.getEntityId(), currentLocal.getEntityId(),
                    "the delayed WELCOME will match the reused local entity ID");
            assertEquals(intermediateLocal.getUniqueID(), currentLocal.getUniqueID());
            assertNotSame(intermediateLocal, currentLocal);
            long currentGeneration = gate.bind(stableConnection, world, currentLocal, 0);
            assertEquals(3, currentGeneration);
            var staleIntermediateWelcome = PlayerRenderPoseProtocol.Packet.welcome(token, 42,
                    intermediateGeneration, currentLocal.getUniqueID(), currentLocal.getEntityId(), 0, 9001);
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.REJECTED,
                    gate.accept(staleIntermediateWelcome, stableConnection, world, currentLocal,
                            token, currentGeneration, 0));

            var currentWelcome = PlayerRenderPoseProtocol.Packet.welcome(token, 43, currentGeneration,
                    currentLocal.getUniqueID(), currentLocal.getEntityId(), 0, 9001);
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.ACCEPTED,
                    gate.accept(currentWelcome, stableConnection, world, currentLocal, token, currentGeneration, 0));
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.DUPLICATE,
                    gate.accept(currentWelcome, stableConnection, world, currentLocal, token, currentGeneration, 0));

            var oldPose = PlayerRenderPoseProtocol.Packet.pose(token, 41, new UUID(0, 72003), 30001,
                    0, 9001, 1, 1, 0, PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            var intermediateLease = PlayerRenderPoseProtocol.Packet.freeLease(token, 42, localId,
                    currentLocal.getEntityId(), 0, 9001, 7, 1);
            assertFalse(gate.acceptsScoped(oldPose, stableConnection, world, currentLocal,
                    token, currentGeneration, 0), "POSE from the pre-reentry realm must not enter the new cache");
            assertFalse(gate.acceptsScoped(intermediateLease, stableConnection, world, currentLocal,
                    token, currentGeneration, 0), "FREE_LEASE from an unconsumed generation must not grant reports");

            var sameGenerationOlderRealm = PlayerRenderPoseProtocol.Packet.welcome(token, 42, currentGeneration,
                    localId, currentLocal.getEntityId(), 0, 9001);
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.REJECTED,
                    gate.accept(sameGenerationOlderRealm, stableConnection, world, currentLocal,
                            token, currentGeneration, 0), "realm high-water survives same-connection world reentry");
        }
    }

    @Test void actualWorldEntityIdentityAndLifetimeTombstonesPreventRebindingAndStaleRevival(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72101), true)) {
            WorldServer world = fixture.world();
            EntityPlayerMP local = fixture.serverPlayer(new UUID(0, 72102));
            UUID subjectId = new UUID(0, 72103);
            EntityPlayerMP subject = fixture.serverPlayer(subjectId);
            assertTrue(world.spawnEntity(local));
            assertTrue(world.spawnEntity(subject));

            UUID token = UUID.randomUUID();
            PlayerRenderPoseMirror.PoseCache cache = cache(world, local, token, 12, 9010);
            var first = pose(token, 12, subjectId, subject.getEntityId(), 9010, 1, 50, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(first, world, local, 1));
            assertTrue(cache.sample(subject, world, local, 0, 1).isPresent());
            double x = subject.posX;
            double y = subject.posY;
            double z = subject.posZ;
            double motionX = subject.motionX;
            float yaw = subject.rotationYaw;
            assertTrue(cache.sample(subject, world, local, .5F, 2).isPresent());
            assertEquals(x, subject.posX);
            assertEquals(y, subject.posY);
            assertEquals(z, subject.posZ);
            assertEquals(motionX, subject.motionX);
            assertEquals(yaw, subject.rotationYaw, "render sampling is read-only for entity and camera state");

            int oldEntityId = subject.getEntityId();
            world.removeEntity(subject);
            assertNull(world.getEntityByID(oldEntityId), "the old WorldServer slot must be vacant before reuse");
            cache.expire(world, local, 3);
            EntityPlayerMP replacement = fixture.serverPlayer(subjectId);
            replacement.setEntityId(oldEntityId);
            assertEquals(oldEntityId, replacement.getEntityId(), "replacement deliberately reuses the retired entity ID");
            assertNotSame(subject, replacement, "the new entity object must differ despite UUID and entity ID reuse");
            assertTrue(world.spawnEntity(replacement));
            assertSame(replacement, world.getEntityByID(oldEntityId),
                    "the real WorldServer occupant map must contain the replacement at the reused ID");
            assertEquals(oldEntityId, replacement.getEntityId());
            var sameLifetimeOldId = pose(token, 12, subjectId, oldEntityId, 9010, 1, 51, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            var sameLifetimeReplacementId = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 1, 52, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertFalse(cache.receive(sameLifetimeOldId, world, local, 4),
                    "an expired lifetime retains the actual bound entity identity");
            assertFalse(cache.receive(sameLifetimeReplacementId, world, local, 4),
                    "a new entity object cannot take over a UUID within the old lifetime");
            assertTrue(cache.sample(replacement, world, local, .5F, 4).isEmpty());

            var newerLifetime = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 2, 1, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(newerLifetime, world, local, 5));
            assertTrue(cache.sample(replacement, world, local, 0, 5).isPresent());
            var advanced = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 2, 50, 2,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(advanced, world, local, 6));
            var regressedRevision = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 2, 51, 1,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            var regressedSequence = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 2, 49, 2,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertFalse(cache.receive(regressedRevision, world, local, 6),
                    "a higher sequence cannot regress physical revision");
            assertFalse(cache.receive(regressedSequence, world, local, 6),
                    "a delayed sequence cannot replace the current pose");

            // A new lifetime's sequence restarts at one. It must still retire the prior entry.
            var newerLifetimeRemove = PlayerRenderPoseProtocol.Packet.remove(token, 12, subjectId,
                    replacement.getEntityId(), 0, 9010, 3, 1, 0);
            assertTrue(cache.receive(newerLifetimeRemove, world, local, 7));
            assertTrue(cache.sample(replacement, world, local, 0, 7).isEmpty(),
                    "higher lifetime REMOVE retires an older sequence regardless of its sequence number");
            var staleOldPose = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 2, 51, 2,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            var staleRemovedLifetimePose = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 3, 2, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertFalse(cache.receive(staleOldPose, world, local, 8));
            assertFalse(cache.receive(staleRemovedLifetimePose, world, local, 8),
                    "REMOVE tombstones the entire lifetime, including higher delayed sequences");
            var reentered = pose(token, 12, subjectId, replacement.getEntityId(), 9010, 4, 1, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(reentered, world, local, 9));
            assertTrue(cache.sample(replacement, world, local, 0, 9).isPresent());
        }
    }

    @Test void missingEntityHasTwentyTickPendingWindowAndHeartbeatRefreshesWithoutRestartingSlerp(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72201), true)) {
            WorldServer world = fixture.world();
            EntityPlayerMP local = fixture.serverPlayer(new UUID(0, 72202));
            EntityPlayerMP late = fixture.serverPlayer(new UUID(0, 72203));
            assertTrue(world.spawnEntity(local));
            UUID token = UUID.randomUUID();
            PlayerRenderPoseMirror.PoseCache cache = cache(world, local, token, 13, 9011);
            var pending = pose(token, 13, late.getUniqueID(), late.getEntityId(), 9011, 1, 1, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(pending, world, local, 0));
            assertTrue(cache.sample(late, world, local, 0, 0).isEmpty(),
                    "an unspawned real entity remains pending until it is actually in this world");
            assertTrue(world.spawnEntity(late));
            assertTrue(cache.sample(late, world, local, 0, 5).isPresent());

            EntityPlayerMP tooLate = fixture.serverPlayer(new UUID(0, 72204));
            var expiring = pose(token, 13, tooLate.getUniqueID(), tooLate.getEntityId(), 9011, 1, 1, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertTrue(cache.receive(expiring, world, local, 10));
            cache.expire(world, local, 31);
            assertTrue(world.spawnEntity(tooLate));
            assertTrue(cache.sample(tooLate, world, local, 0, 31).isEmpty(),
                    "the pending entity-arrival entry expires after twenty ticks");

            var rotation90 = new PlayerRenderPoseProtocol.Quaternion(0, Math.sqrt(.5), 0, Math.sqrt(.5));
            var rotation90Negated = new PlayerRenderPoseProtocol.Quaternion(0, -Math.sqrt(.5), 0, -Math.sqrt(.5));
            var head180X = new PlayerRenderPoseProtocol.Quaternion(1, 0, 0, 0);
            var turn = pose(token, 13, late.getUniqueID(), late.getEntityId(), 9011, 1, 2, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, rotation90, head180X);
            assertTrue(cache.receive(turn, world, local, 40));
            var halfwayBeforeHeartbeat = cache.sample(late, world, local, .5F, 40).orElseThrow();
            var heartbeat = pose(token, 13, late.getUniqueID(), late.getEntityId(), 9011, 1, 3, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, rotation90Negated,
                    new PlayerRenderPoseProtocol.Quaternion(-1, 0, 0, 0));
            assertTrue(cache.receive(heartbeat, world, local, 40));
            var halfwayAfterHeartbeat = cache.sample(late, world, local, .5F, 40).orElseThrow();
            assertQuaternionSame(halfwayBeforeHeartbeat.body(), halfwayAfterHeartbeat.body());
            assertQuaternionSame(halfwayBeforeHeartbeat.head(), halfwayAfterHeartbeat.head());
            assertTrue(Math.abs(halfwayAfterHeartbeat.body().dot(rotation90)) < .999,
                    "same-Q/q-minus-Q heartbeat cannot snap an in-progress interpolation to its endpoint");
            var settled = cache.sample(late, world, local, 0, 42).orElseThrow();
            assertQuaternionSame(rotation90, settled.body());
            assertQuaternionSame(head180X, settled.head());

            cache.expire(world, local, 80);
            assertTrue(cache.sample(late, world, local, 1, 80).isPresent(),
                    "forty-tick active TTL exceeds ten-tick server heartbeats");
            cache.expire(world, local, 81);
            assertTrue(cache.sample(late, world, local, 0, 81).isEmpty());
        }
    }

    @Test void fenceCapacityRefusesUnknownSubjectsWithoutEvictingRemoveTombstones(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72301), true)) {
            WorldServer world = fixture.world();
            EntityPlayerMP local = fixture.serverPlayer(new UUID(0, 72302));
            assertTrue(world.spawnEntity(local));
            UUID token = UUID.randomUUID();
            PlayerRenderPoseMirror.PoseCache cache = cache(world, local, token, 14, 9012);
            UUID firstRetired = new UUID(0, 80000);
            for (int index = 0; index < PlayerRenderPoseMirror.PoseCache.MAX_FENCES; index++) {
                UUID playerId = index == 0 ? firstRetired : new UUID(1, 80000L + index);
                var remove = PlayerRenderPoseProtocol.Packet.remove(token, 14, playerId, 100_000 + index,
                        0, 9012, 1, 1, 0);
                assertTrue(cache.receive(remove, world, local, index));
            }
            var stale = pose(token, 14, firstRetired, 100_000, 9012, 1, 2, 0,
                    PlayerRenderPoseProtocol.Mode.CARDINAL, 0, IDENTITY, IDENTITY);
            assertFalse(cache.receive(stale, world, local, 5000),
                    "capacity pressure must not evict an existing REMOVE fence and revive its pose");
            var unknown = PlayerRenderPoseProtocol.Packet.remove(token, 14, new UUID(2, 99999),
                    999_999, 0, 9012, 1, 1, 0);
            assertFalse(cache.receive(unknown, world, local, 5001),
                    "unknown subjects are explicitly rejected after the fence table reaches its bound");
        }
    }

    @Test void freePoseReportsRequireExactLeaseAndAdvanceOncePerClientTick(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 72401), true)) {
            WorldServer world = fixture.world();
            EntityPlayerMP local = fixture.serverPlayer(new UUID(0, 72402));
            NetHandlerPlayServer handler = local.connection;
            UUID token = UUID.randomUUID();
            PlayerRenderPoseMirror.WelcomeGate gate = new PlayerRenderPoseMirror.WelcomeGate();
            gate.beginConnection(handler, token);
            long generation = gate.bind(handler, world, local, 0);
            var welcome = PlayerRenderPoseProtocol.Packet.welcome(token, 51, generation,
                    local.getUniqueID(), local.getEntityId(), 0, 9013);
            assertEquals(PlayerRenderPoseMirror.WelcomeGate.WelcomeResult.ACCEPTED,
                    gate.accept(welcome, handler, world, local, token, generation, 0));

            PlayerRenderPoseMirror.FreePoseReportState reports = new PlayerRenderPoseMirror.FreePoseReportState();
            var staleLease = PlayerRenderPoseProtocol.Packet.freeLease(token, 50, local.getUniqueID(),
                    local.getEntityId(), 0, 9013, 7, 1);
            assertFalse(reports.acceptLease(staleLease, handler, world, local, token, generation, 0, gate),
                    "a lease from the prior realm cannot authorize a report");
            var activeLease = PlayerRenderPoseProtocol.Packet.freeLease(token, 51, local.getUniqueID(),
                    local.getEntityId(), 0, 9013, 8, 1);
            assertTrue(reports.acceptLease(activeLease, handler, world, local, token, generation, 0, gate));

            var body = new PlayerRenderPoseProtocol.Quaternion(0, Math.sqrt(.5), 0, Math.sqrt(.5));
            var head = new PlayerRenderPoseProtocol.Quaternion(Math.sqrt(.5), 0, 0, Math.sqrt(.5));
            var first = reports.prepare(body, head, handler, world, local, token, generation, 0, 100, gate)
                    .orElseThrow();
            assertEquals(PlayerRenderPoseProtocol.Kind.FREE_POSE, first.kind());
            assertEquals(51, first.viewerRealm());
            assertEquals(0, first.worldRequestGeneration());
            assertEquals(9013, first.worldEpoch());
            assertEquals(8, first.lifetime());
            assertEquals(1, first.sequence());
            assertEquals(body, first.body());
            assertEquals(head, first.head());
            assertTrue(reports.prepare(body, head, handler, world, local, token, generation, 0, 100, gate).isEmpty(),
                    "at most one FREE_POSE report is emitted per client tick");
            var second = reports.prepare(body, head, handler, world, local, token, generation, 0, 101, gate)
                    .orElseThrow();
            assertEquals(2, second.sequence());

            var revoke = PlayerRenderPoseProtocol.Packet.freeLease(token, 51, local.getUniqueID(),
                    local.getEntityId(), 0, 9013, 0, 2);
            assertTrue(reports.acceptLease(revoke, handler, world, local, token, generation, 0, gate));
            assertTrue(reports.prepare(body, head, handler, world, local, token, generation, 0, 102, gate).isEmpty(),
                    "a revoked lease cannot produce a report");
            var delayedLease = PlayerRenderPoseProtocol.Packet.freeLease(token, 51, local.getUniqueID(),
                    local.getEntityId(), 0, 9013, 9, 1);
            assertFalse(reports.acceptLease(delayedLease, handler, world, local, token, generation, 0, gate),
                    "out-of-order control sequence cannot restore an old lease");

            var activeAgain = PlayerRenderPoseProtocol.Packet.freeLease(token, 51, local.getUniqueID(),
                    local.getEntityId(), 0, 9013, 10, 3);
            assertTrue(reports.acceptLease(activeAgain, handler, world, local, token, generation, 0, gate));
            local.noClip = true;
            assertTrue(reports.prepare(body, head, handler, world, local, token, generation, 0, 103, gate).isEmpty(),
                    "native-only actor state is never reported as an owned free pose");
        }
    }

    private static PlayerRenderPoseMirror.PoseCache cache(WorldServer world, EntityPlayerMP local,
                                                           UUID token, long realm, long epoch) {
        PlayerRenderPoseMirror.PoseCache cache = new PlayerRenderPoseMirror.PoseCache();
        cache.bind(world, local, token, realm, world.provider.getDimension(), epoch);
        return cache;
    }

    private static PlayerRenderPoseProtocol.Packet pose(UUID token, long realm, UUID playerId, int entityId,
                                                         long epoch, long lifetime, long sequence, long revision,
                                                         PlayerRenderPoseProtocol.Mode mode, int frame,
                                                         PlayerRenderPoseProtocol.Quaternion body,
                                                         PlayerRenderPoseProtocol.Quaternion head) {
        return PlayerRenderPoseProtocol.Packet.pose(token, realm, playerId, entityId, 0, epoch,
                lifetime, sequence, revision, mode, frame, body, head);
    }

    private static void assertQuaternionSame(PlayerRenderPoseProtocol.Quaternion first,
                                             PlayerRenderPoseProtocol.Quaternion second) {
        assertEquals(1.0, Math.abs(first.dot(second)), 1.0e-10);
    }
}
