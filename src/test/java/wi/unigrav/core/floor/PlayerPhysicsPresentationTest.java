package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.ClientPhysicsTickQueue;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import net.minecraft.network.play.client.CPacketPlayer;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsPresentationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void risingFallingAndTangentMovementRetainAllThreeHistoryComponents(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9931), true)) {
            var player = world.serverPlayer(new UUID(0, 9932));
            for (var delta : new Vec3d[]{new Vec3d(1, 0, 0), new Vec3d(-1, 0, 0),
                    new Vec3d(0, 1, 0), new Vec3d(0, -1, 0), new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)}) {
                var presentation = new PlayerPhysicsClientPresentation();
                var first = snapshot(player, 1, 8, false);
                assertTrue(presentation.install(player, first));
                presentation.tick(player);
                double startX = player.posX, startY = player.posY, startZ = player.posZ;
                var next = new PlayerPhysicalSnapshot(first.scope(), 2,
                        new PlayerBodyPlacement(new Vec3d(8 + delta.x(), 65 + delta.y(), 8 + delta.z()),
                                first.placement().attitude(), first.placement().shape()),
                        delta.multiply(20), first.contacts());
                assertTrue(presentation.install(player, next));
                player.onEntityUpdate();
                presentation.tick(player);
                assertEquals(startX, player.prevPosX);
                assertEquals(startY, player.prevPosY);
                assertEquals(startZ, player.prevPosZ);
                assertEquals(startX, player.lastTickPosX);
                assertEquals(startY, player.lastTickPosY);
                assertEquals(startZ, player.lastTickPosZ);
                assertEquals(startX + delta.x(), player.posX, 1e-12);
                assertEquals(startY + delta.y(), player.posY, 1e-12);
                assertEquals(startZ + delta.z(), player.posZ, 1e-12);
            }
        }
    }

    @Test void queuedSessionStatesKeepLinearRenderHistoryAcrossNativeTicks(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9921), true)) {
            var player = world.serverPlayer(new UUID(0, 9922));
            var presentation = new PlayerPhysicsClientPresentation();
            var queue = new ClientPhysicsTickQueue();
            var dispatcher = new RemoteBoostBoundedDispatcher<PlayerPhysicsProtocol.Packet>(8, queue::schedule);
            var session = new PlayerPhysicsClientSession(state -> presentation.install(player, state),
                    () -> {}, presentation::reset);
            var scope = snapshot(player, 1, 8, false).scope();
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,
                    scope, 0, 1, Optional.empty(), Optional.empty(), Optional.empty())));
            session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), packet -> {});
            for (int tick = 0; tick < 6; tick++) {
                var state = snapshot(player, tick + 1, 8 + tick, false);
                // A native sneak transition can precede an incoming standing snapshot.
                if (tick == 3) { player.setSneaking(true); BootWorldFixture.updateNativePose(player); }
                double beforeArrival = player.posZ;
                dispatcher.submit(dispatcher.reserve(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,
                        scope, tick + 1, 1, Optional.empty(), Optional.of(state), Optional.empty()), session::receive));
                assertEquals(beforeArrival, player.posZ, "no render-frame writes on network arrival");
                queue.drain();
                assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLIED, session.lastStateDisposition());
                player.onEntityUpdate();
                presentation.tick(player);
                assertEquals(8 + tick, player.posZ);
                double from = tick == 0 ? 8 : 7 + tick;
                assertEquals(from, player.prevPosZ);
                assertEquals(from, player.lastTickPosZ);
                assertEquals(from + (tick == 0 ? 0 : .5),
                        player.prevPosZ + (player.posZ - player.prevPosZ) * .5, 1e-12);
            }
            assertEquals(6, session.snapshot().orElseThrow().revision());
            assertTrue(session.drivesMotion());
        }
    }

    @Test void nativeTickCannotEraseAcceptedInterpolationAndBurstsShareOneTickAnchor(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9911), true)) {
            var player = world.serverPlayer(new UUID(0, 9912));
            var presentation = new PlayerPhysicsClientPresentation();
            assertTrue(presentation.install(player, snapshot(player, 1, 8, false)));
            presentation.tick(player);
            assertTrue(presentation.install(player, snapshot(player, 2, 8.5, false)));
            assertTrue(presentation.install(player, snapshot(player, 3, 9, false)));
            player.onEntityUpdate(); // Real vanilla method resets prevPos to the already-installed current position.
            assertEquals(9, player.prevPosZ);
            presentation.tick(player);
            assertEquals(8, player.prevPosZ, "the previous client tick, not the final packet in a burst");
            assertEquals(8, player.lastTickPosZ);
            assertEquals(8.5, player.prevPosZ + (player.posZ - player.prevPosZ) * .5);
            assertEquals(9, player.posZ, "presentation must not move the authoritative actor");
            player.onEntityUpdate();
            presentation.tick(player);
            assertEquals(9, player.prevPosZ, "no extrapolation/repeated travel while waiting for the next state");
            assertEquals(9, player.lastTickPosZ);
        }
    }

    @Test void correctionResetNeverInterpolatesAcrossTheOldWorldPosition(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9913), true)) {
            var player = world.serverPlayer(new UUID(0, 9914));
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, false));
            presentation.tick(player);
            presentation.reset();
            presentation.install(player, snapshot(player, 2, 500, false));
            presentation.tick(player);
            assertEquals(500, player.prevPosZ);
            assertEquals(500, player.lastTickPosZ);
            assertEquals(500, player.posZ);
        }
    }

    @Test void acceptedWalkFeedsLimbsAndBobbingDistanceOncePerClientTick(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 771), true)) {
            var player = world.serverPlayer(new UUID(0, 772));
            var presentation = new PlayerPhysicsClientPresentation();
            assertTrue(presentation.install(player, snapshot(player, 1, 8, true)));
            presentation.tick(player);
            assertEquals(0, player.distanceWalkedModified, "first adoption must not count as a step");
            assertTrue(presentation.install(player, snapshot(player, 2, 8.1, true)));
            assertTrue(presentation.install(player, snapshot(player, 3, 8.2, true)));
            assertEquals(0, player.limbSwingAmount, "network bursts must not advance animation per packet");
            presentation.tick(player);
            assertEquals(.32, player.limbSwingAmount, 1e-6);
            assertEquals(.32, player.limbSwing, 1e-6);
            assertEquals(.12, player.distanceWalkedModified, 1e-6, "vanilla bobbing consumes this distance");
            assertEquals(8.2, player.posZ, 0, "presentation cannot move the physical player again");
            presentation.tick(player);
            assertEquals(.192, player.limbSwingAmount, 1e-6, "idle animation decays");
            assertEquals(.12, player.distanceWalkedModified, 1e-6, "no repeated distance without accepted movement");
            assertEquals(.12, player.prevDistanceWalkedModified, 1e-6);
        }
    }

    @Test void correctionResetAndAirborneMotionDoNotInventGroundedSteps(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 773), true)) {
            var player = world.serverPlayer(new UUID(0, 774));
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, true));
            presentation.reset();
            presentation.install(player, snapshot(player, 2, 500, true));
            presentation.tick(player);
            assertEquals(0, player.distanceWalkedModified);
            presentation.install(player, snapshot(player, 3, 501, false));
            presentation.tick(player);
            assertEquals(0, player.distanceWalkedModified);
            assertEquals(.4, player.limbSwingAmount, 1e-6, "airborne motion still feeds vanilla limbs, not ground steps");
        }
    }

    @Test void nearFootWalkAnimatesWithoutForgingGroundContact(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 778), true)) {
            var player = world.serverPlayer(new UUID(0, 779));
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, false));
            var next = snapshot(player, 2, 8.025, false);
            presentation.install(player, new PlayerPhysicalSnapshot(next.scope(), next.revision(), next.placement(),
                    next.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0, true))));
            presentation.tick(player);
            assertEquals(.04, player.limbSwingAmount, 1e-6);
            assertEquals(.015, player.distanceWalkedModified, 1e-6);
            assertTrue(player.cameraYaw > 0, "near-foot presentation restores bob amplitude independently of onGround");
            assertFalse(player.onGround);
        }
    }

    @Test void airborneAcceptedMotionAnimatesOnceWithoutBobbingOrChangingTurning(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9701), true)) {
            var player = world.serverPlayer(new UUID(0, 9702));
            player.capabilities.isFlying = true;
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, false));
            presentation.install(player, snapshot(player, 2, 8.1, false));
            presentation.install(player, snapshot(player, 3, 8.2, false));
            assertEquals(0, player.limbSwingAmount, "packets must not independently tick animation");
            presentation.tick(player);
            assertEquals(.32, player.limbSwingAmount, 1e-6);
            assertEquals(.32, player.limbSwing, 1e-6);
            assertEquals(0, player.distanceWalkedModified);
            assertEquals(0, player.cameraYaw);
            assertEquals(0, presentation.bodyYawOffset(player, 1));
            assertEquals(8.2, player.posZ);
            assertFalse(player.onGround);
            presentation.tick(player);
            assertEquals(.192, player.limbSwingAmount, 1e-6);
            assertEquals(0, player.distanceWalkedModified);
        }
    }

    @Test void localNativeDelegationDiscardsPendingStepsBeforeTheNetworkAcknowledgesIt(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 780), true)) {
            var player = world.serverPlayer(new UUID(0, 781));
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, true));
            presentation.install(player, snapshot(player, 2, 8.2, true));
            player.noClip = true;
            player.limbSwingAmount = .7F;
            presentation.tick(player);
            assertEquals(.7F, player.limbSwingAmount, "native visual tail must be the only writer during delegation");
            assertEquals(0, player.distanceWalkedModified);
            player.noClip = false;
            presentation.install(player, snapshot(player, 3, 100, true));
            presentation.tick(player);
            assertEquals(0, player.distanceWalkedModified, "no displacement accumulated across the native window");
        }
    }

    private static PlayerPhysicalSnapshot snapshot(EntityPlayer player, long revision, double z, boolean ground) {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
        var placement = new PlayerBodyPlacement(new Vec3d(8, 65, z), CardinalFrame.Y_POS.attitude(), shape);
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 775),
                player.dimension, 1), revision, placement, new Vec3d(0, 0, 4),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(ground, false, ground, 0)));
    }

    @Test void acceptedStrafeTurnsBodyAndForwardStepsReturnItWithoutChangingLook(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9602), true)) {
            var player = world.serverPlayer(new UUID(0, 9603));
            player.rotationYaw = 0;
            player.rotationPitch = 15;
            player.renderYawOffset = 0;
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.install(player, snapshot(player, 1, 8, true));
            for (int i = 1; i <= 8; i++) {
                var state = snapshot(player, i + 1, 8, true);
                var placement = state.placement();
                presentation.install(player, new PlayerPhysicalSnapshot(state.scope(), state.revision(),
                        new PlayerBodyPlacement(new Vec3d(8 + i * .2, 65, 8), placement.attitude(), placement.shape()),
                        new Vec3d(4, 0, 0), state.contacts()));
                presentation.tick(player);
            }
            assertTrue(player.renderYawOffset < -20, "accepted +X strafe must turn the body toward +X");
            for (int i = 1; i <= 16; i++) {
                var state = snapshot(player, i + 9, 8 + i * .2, true);
                var placement = state.placement();
                presentation.install(player, new PlayerPhysicalSnapshot(state.scope(), state.revision(),
                        new PlayerBodyPlacement(new Vec3d(9.6, 65, 8 + i * .2), placement.attitude(), placement.shape()),
                        new Vec3d(0, 0, 4), state.contacts()));
                presentation.tick(player);
            }
            assertEquals(0, player.renderYawOffset, 1, "forward movement must straighten the body again");
            assertEquals(0, player.rotationYaw);
            assertEquals(15, player.rotationPitch);
        }
    }

    @Test void nearFootSlowStepsTurnFreeBodyInViewLocalAxesAndForwardReturnsIt(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9605), true)) {
            var player = world.serverPlayer(new UUID(0, 9606));
            var presentation = new PlayerPhysicsClientPresentation();
            // Rolled 90 degrees: view forward +Z, view right -Y. No world-up projection is allowed.
            var basis = new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(0, 0, 1), new Vec3(0, -1, 0));
            presentation.install(player, nearFoot(player, 1, 65, 8));
            for (int i = 1; i <= 8; i++) {
                presentation.install(player, nearFoot(player, i + 1, 65 - i * .0125, 8));
                presentation.tick(player, basis);
            }
            assertTrue(presentation.bodyYawOffset(player, 1) > 20, "slow motion uses the actual rolled view basis");
            assertFalse(player.onGround);
            for (int i = 1; i <= 16; i++) {
                presentation.install(player, nearFoot(player, i + 9, 64.9, 8 + i * .0125));
                presentation.tick(player, basis);
            }
            assertEquals(0, presentation.bodyYawOffset(player, 1), 1);
            assertEquals(0, player.rotationYaw);
            assertEquals(0, player.rotationPitch);
            presentation.reset();
            assertEquals(0, presentation.bodyYawOffset(player, 1));
        }
    }

    @Test void backwardsAndAirborneSnapshotsDoNotSpinBodyAround(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9607), true)) {
            var player = world.serverPlayer(new UUID(0, 9608));
            var presentation = new PlayerPhysicsClientPresentation();
            var basis = new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(0, 0, 1), new Vec3(-1, 0, 0));
            presentation.install(player, snapshot(player, 1, 8, true));
            presentation.install(player, snapshot(player, 2, 7.8, true));
            presentation.tick(player, basis);
            assertEquals(0, player.renderYawOffset, 1e-6);
            assertEquals(0, presentation.bodyYawOffset(player, 1), 1e-6);
            player.rotationYaw = 179;
            presentation.install(player, snapshot(player, 3, 500, false));
            presentation.tick(player, basis);
            assertEquals(0, presentation.bodyYawOffset(player, 1), 1e-6);
            assertTrue(Math.abs(player.renderYawOffset - player.prevRenderYawOffset) < 180);
        }
    }

    private static PlayerPhysicalSnapshot nearFoot(EntityPlayer player, long revision, double y, double z) {
        var state = snapshot(player, revision, z, false);
        return new PlayerPhysicalSnapshot(state.scope(), revision,
                new PlayerBodyPlacement(new Vec3d(8, y, z), state.placement().attitude(), state.placement().shape()),
                new Vec3d(0, 0, 0), Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0, true)));
    }
}
