package wi.unigrav.core.client;

import java.nio.file.Path;
import java.util.UUID;

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
