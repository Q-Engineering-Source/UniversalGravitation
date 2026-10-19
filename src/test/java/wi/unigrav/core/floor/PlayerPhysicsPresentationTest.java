package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.PlayerPhysicsClientMirror;
import wi.unigrav.core.client.ClientPhysicsTickQueue;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import net.minecraft.network.play.client.CPacketPlayer;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsPresentationTest {
    @Test void freeEyeUsesAcceptedQuaternionAndOnlySameScopeShapeHistory(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 921), true)) {
            var player = fixture.serverPlayer(new UUID(0, 922));
            var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 923), player.dimension, 1);
            var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
            var contacts = Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0));
            var first = new PlayerPhysicalSnapshot(scope, 1,
                    new PlayerBodyPlacement(new Vec3d(8, 70, 8), Rotation3d.identity(), shape), new Vec3d(0, 0, 0), contacts);
            var next = new PlayerPhysicalSnapshot(scope, 2,
                    new PlayerBodyPlacement(new Vec3d(10, 71, 12), new Rotation3d(.3, .4, .2, .5), shape),
                    new Vec3d(1, 2, 3), contacts);
            var presentation = new PlayerPhysicsClientPresentation();
            assertTrue(presentation.install(player, first));
            assertTrue(presentation.install(player, next));
            assertEquals(first.placement().eye(), presentation.acceptedFreeEyePosition(player, next, 0));
            assertEquals(next.placement().eye(), presentation.acceptedFreeEyePosition(player, next, 1));
            var changedScope = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                    new UUID(0, 924), player.dimension, 2), 1, next.placement(), next.worldVelocity(), contacts);
            assertTrue(presentation.install(player, changedScope));
            assertEquals(changedScope.placement().eye(), presentation.acceptedFreeEyePosition(player, changedScope, 0));
            player.posX += 1;
            assertNull(presentation.acceptedFreeEyePosition(player, changedScope, .5F));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void freeTickSpanUsesRealStartAndRetainsIndependentEyeAnchor(boolean anchored, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 925), true)) {
            var player = fixture.serverPlayer(new UUID(0, 926));
            var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 927), player.dimension, 1);
            var dimensions = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
            var center = new Vec3d(8, 70, 8);
            var q = new Rotation3d(.1, .2, .3, .9);
            var shape = anchored ? new PlayerBodyShape.FreeCapsule(dimensions,
                    new PlayerBodyShape.CollisionProfile(dimensions.width(), dimensions.height()),
                    Optional.of(new wi.unigrav.core.player.FreeEyeAnchor(
                            wi.unigrav.core.player.FreeEyeAnchor.STORED_BASIS_V1, center,
                            new Vec3d(8.125, 70.375, 8.25), q))) : new PlayerBodyShape.FreeCapsule(dimensions);
            var placement = new PlayerBodyPlacement(center, q, shape);
            var contacts = Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0));
            var a = new PlayerPhysicalSnapshot(scope, 1, placement, new Vec3d(0, 0, 0), contacts);
            var c = new PlayerPhysicalSnapshot(scope, 2,
                    new PlayerBodyPlacement(center.add(new Vec3d(0, 0, .12)), q, shape), new Vec3d(0, 0, 2.4), contacts);
            var presentation = new PlayerPhysicsClientPresentation();
            presentation.beginClientTick(1); assertTrue(presentation.install(player, a));
            presentation.beginClientTick(2); assertTrue(presentation.install(player, c));
            var rendered = presentation.acceptedFreeEyePosition(player, c, .62F);
            assertEquals(a.placement().eye().add(c.placement().eye().subtract(a.placement().eye()).multiply((double).62F)), rendered);
            assertEquals(rendered, presentation.acceptedFreeEyePosition(player, c, .62F), "getters do not advance time");
            presentation.beginClientTick(3);
            var afterStart = presentation.acceptedFreeEyePosition(player, c, .28F);
            assertEquals(c.placement().eye(), afterStart,
                    "same current revision without a new install must not replay the old Free span after START");
            assertTrue(afterStart.z() >= rendered.z(), "forward authority cannot reverse merely because partial reset");
            var b = new PlayerPhysicalSnapshot(scope, 3,
                    new PlayerBodyPlacement(center.add(new Vec3d(0, 0, .24)), q, shape), c.worldVelocity(), contacts);
            var d = new PlayerPhysicalSnapshot(scope, 4,
                    new PlayerBodyPlacement(center.add(new Vec3d(0, 0, .36)), q, shape), c.worldVelocity(), contacts);
            var stale = presentation.prepare(player, b);
            presentation.beginClientTick(4); assertFalse(presentation.commitPrepared(stale));
            assertTrue(presentation.install(player, b)); assertTrue(presentation.install(player, d));
            assertEquals(c.placement().eye(), presentation.acceptedFreeEyePosition(player, d, 0), "burst shares tick start");
            assertEquals(d.placement().eye(), presentation.acceptedFreeEyePosition(player, d, 1));
            var sameTick = presentation.prepare(player, d); presentation.beginClientTick(4);
            assertTrue(sameTick.isBoundTo(presentation, player, d));
            presentation.beginClientTick(5);
            assertEquals(d.placement().eye(), presentation.acceptedFreeEyePosition(player, d, .28F));
            var reverse = new PlayerPhysicalSnapshot(scope, 5, placement, new Vec3d(0, 0, -2.4), contacts);
            assertTrue(presentation.install(player, reverse));
            assertEquals(d.placement().eye().add(reverse.placement().eye().subtract(d.placement().eye()).multiply(.5)),
                    presentation.acceptedFreeEyePosition(player, reverse, .5F), "real reverse motion is not clamped");
            var successor = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(0, 928),
                    scope.dimension(), 2), 1, d.placement(), d.worldVelocity(), contacts);
            assertTrue(presentation.install(player, successor));
            assertEquals(successor.placement().eye(), presentation.acceptedFreeEyePosition(player, successor, 0));
            var other = fixture.serverPlayer(new UUID(0, 929));
            var otherPose = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(other.getUniqueID(), new UUID(0, 930),
                    other.dimension, 1), 1, placement, c.worldVelocity(), contacts);
            assertTrue(presentation.install(other, otherPose));
            assertNull(presentation.acceptedFreeEyePosition(player, successor, .5F));
            assertEquals(otherPose.placement().eye(), presentation.acceptedFreeEyePosition(other, otherPose, 0));
            var cardinal = snapshot(other, 2, 12, false);
            assertTrue(presentation.install(other, cardinal));
            assertNull(presentation.acceptedFreeEyePosition(other, cardinal, .5F));
            assertTrue(presentation.install(other, otherPose));
            assertEquals(otherPose.placement().eye(), presentation.acceptedFreeEyePosition(other, otherPose, 0), "shape cut");
            presentation.reset(); assertNull(presentation.acceptedFreeEyePosition(other, otherPose, .5F));
            assertTrue(presentation.install(other, otherPose));
            assertEquals(otherPose.placement().eye(), presentation.acceptedFreeEyePosition(other, otherPose, 0));
        }
    }

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

    @Test void acceptedPhysicalEyeInterpolatesOnlyWithinCurrentInstalledScopeAndGeometry(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9935), true)) {
            var player = world.serverPlayer(new UUID(0, 9936));
            var presentation = new PlayerPhysicsClientPresentation();
            var first = snapshot(player, 1, 8, false);
            var second = snapshot(player, 2, 10, false);
            var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    CardinalFrame.Y_POS, new Vec3d(0, 0, 1));
            assertTrue(presentation.install(player, first));
            assertTrue(presentation.install(player, second));

            Vec3d before = first.placement().eye();
            Vec3d after = second.placement().eye();
            assertEquals(before, presentation.acceptedEyePosition(player, second, effective, 0));
            assertEquals(new Vec3d((before.x() + after.x()) / 2, (before.y() + after.y()) / 2,
                    (before.z() + after.z()) / 2),
                    presentation.acceptedEyePosition(player, second, effective, .5F));
            assertEquals(after, presentation.acceptedEyePosition(player, second, effective, 1));
            assertNull(presentation.acceptedEyePosition(player,
                    new PlayerPhysicalSnapshot(second.scope(), second.revision(), second.placement(),
                            second.worldVelocity(), second.contacts()), effective, .5F),
                    "an equal but separately received snapshot is not the installed presentation identity");

            player.posZ += .25;
            assertNull(presentation.acceptedEyePosition(player, second, effective, .5F),
                    "live actor drift beyond accepted geometry fences the eye provider");

            presentation.reset();
            var replacement = snapshot(player, 3, 500, false);
            assertTrue(presentation.install(player, replacement));
            assertEquals(replacement.placement().eye(),
                    presentation.acceptedEyePosition(player, replacement, effective, 0),
                    "reset/lifetime replacement cannot interpolate from the prior world location");
        }
    }

    @Test void cardinalTickSpanKeepsBurstAnchorAndInvalidatesEarlierPreparation(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9981), true)) {
            var player = world.serverPlayer(new UUID(0, 9982));
            var presentation = new PlayerPhysicsClientPresentation();
            var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    CardinalFrame.Y_POS, new Vec3d(0, 0, 1));
            var a = snapshot(player, 1, 8, false);
            presentation.beginClientTick(1);
            assertTrue(presentation.install(player, a));
            var b = snapshot(player, 2, 9, false);
            var stale = presentation.prepare(player, b);
            presentation.beginClientTick(2);
            assertFalse(presentation.commitPrepared(stale));
            assertTrue(presentation.install(player, b));
            var c = snapshot(player, 3, 10, false);
            assertTrue(presentation.install(player, c));
            assertEquals(a.placement().eye(), presentation.acceptedEyePosition(player, c, effective, 0));
            var sameTick = presentation.prepare(player, c);
            presentation.beginClientTick(2);
            assertTrue(sameTick.isBoundTo(presentation, player, c), "duplicate tick does not mutate the span");
            presentation.beginClientTick(3);
            assertEquals(c.placement().eye(), presentation.acceptedEyePosition(player, c, effective, .32F));
            var reverse = snapshot(player, 4, 7, false);
            assertTrue(presentation.install(player, reverse));
            assertEquals(c.placement().eye(), presentation.acceptedEyePosition(player, reverse, effective, 0));
            assertEquals(reverse.placement().eye(), presentation.acceptedEyePosition(player, reverse, effective, 1));
            assertEquals(c.placement().eye().add(reverse.placement().eye().subtract(c.placement().eye()).multiply(.5)),
                    presentation.acceptedEyePosition(player, reverse, effective, .5F));
            presentation.reset();
            var replacement = snapshot(player, 5, 500, false);
            assertTrue(presentation.install(player, replacement));
            assertEquals(replacement.placement().eye(), presentation.acceptedEyePosition(player, replacement, effective, 0));
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

    @Test void preparationIsPureAndCommitOnlyChangesPresentationState(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9841), true)) {
            var player = world.serverPlayer(new UUID(0, 9842));
            var otherActor = world.player(new UUID(0, 9848));
            var first = snapshot(player, 1, 8, true);
            var state = strafeSnapshot(player, 2, 8.2);
            var presentation = new PlayerPhysicsClientPresentation();
            var reference = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    CardinalFrame.Y_POS, new Vec3d(0, 0, 1));
            assertTrue(presentation.install(player, first));
            Vec3d visibleEyeBefore = presentation.acceptedEyePosition(player, first, reference, .5F);
            float bodyOffsetBefore = presentation.bodyYawOffset(player, 1);
            ActorState beforePrepare = actorState(player);

            var prepared = presentation.prepare(player, state);
            assertTrue(prepared.isBoundTo(presentation, player, state));
            assertFalse(prepared.isBoundTo(new PlayerPhysicsClientPresentation(), player, state));
            assertFalse(prepared.isBoundTo(presentation, otherActor, state));
            assertFalse(prepared.isBoundTo(presentation, player, strafeSnapshot(player, 2, 8.2)));
            assertActorStateEquals(beforePrepare, player);
            assertEquals(visibleEyeBefore, presentation.acceptedEyePosition(player, first, reference, .5F),
                    "preparation leaves the currently committed eye presentation unchanged");
            assertEquals(bodyOffsetBefore, presentation.bodyYawOffset(player, 1), 0);

            player.setPosition(40, 90, 42);
            player.prevPosX = -101;
            player.prevPosY = -102;
            player.prevPosZ = -103;
            player.width = .65F;
            player.height = 1.4F;
            player.eyeHeight = 1.1F;
            player.motionX = 11;
            player.motionY = 12;
            player.motionZ = 13;
            player.onGround = false;
            player.collidedHorizontally = true;
            player.collidedVertically = false;
            player.collided = true;
            player.fallDistance = 17;
            ActorState beforeCommit = actorState(player);

            assertTrue(presentation.commitPrepared(prepared));
            assertActorStateEquals(beforeCommit, player);
            assertFalse(prepared.isBoundTo(presentation, player, state),
                    "successful commit advances the presentation generation");
            assertNull(presentation.acceptedEyePosition(player, state, reference, .5F),
                    "commit does not repair actor geometry through the mirror");

            assertTrue(PlayerPhysicsClientMirror.install(player, state));
            Vec3d firstEye = first.placement().eye();
            Vec3d nextEye = state.placement().eye();
            assertEquals(new Vec3d((firstEye.x() + nextEye.x()) / 2,
                            (firstEye.y() + nextEye.y()) / 2,
                            (firstEye.z() + nextEye.z()) / 2),
                    presentation.acceptedEyePosition(player, state, reference, .5F));
        }
    }

    @Test void preparedAndCompatibilityInstallsProduceEquivalentTickAndEyePresentation(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9843), true)) {
            var compatibilityActor = world.serverPlayer(new UUID(0, 9844));
            var preparedActor = world.serverPlayer(new UUID(0, 9845));
            compatibilityActor.rotationYaw = preparedActor.rotationYaw = 0;
            var compatibility = new PlayerPhysicsClientPresentation();
            var split = new PlayerPhysicsClientPresentation();
            var reference = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    CardinalFrame.Y_POS, new Vec3d(0, 0, 1));

            for (int revision = 1; revision <= 4; revision++) {
                double x = 8 + revision * .1;
                var compatibilityState = strafeSnapshot(compatibilityActor, revision, x);
                var preparedState = strafeSnapshot(preparedActor, revision, x);
                assertTrue(compatibility.install(compatibilityActor, compatibilityState));

                assertTrue(PlayerPhysicsClientMirror.install(preparedActor, preparedState));
                var prepared = split.prepare(preparedActor, preparedState);
                assertTrue(prepared.isBoundTo(split, preparedActor, preparedState));
                assertTrue(split.commitPrepared(prepared));

                assertEquals(compatibility.acceptedEyePosition(compatibilityActor, compatibilityState, reference, .5F),
                        split.acceptedEyePosition(preparedActor, preparedState, reference, .5F),
                        "both APIs retain the same accepted eye interpolation");
                compatibilityActor.onEntityUpdate();
                preparedActor.onEntityUpdate();
                compatibility.tick(compatibilityActor);
                split.tick(preparedActor);
                assertEquals(compatibilityActor.prevPosX, preparedActor.prevPosX, 0);
                assertEquals(compatibilityActor.lastTickPosX, preparedActor.lastTickPosX, 0);
                assertEquals(compatibilityActor.limbSwingAmount, preparedActor.limbSwingAmount, 0);
                assertEquals(compatibilityActor.limbSwing, preparedActor.limbSwing, 0);
                assertEquals(compatibilityActor.distanceWalkedModified, preparedActor.distanceWalkedModified, 0);
                assertEquals(compatibilityActor.renderYawOffset, preparedActor.renderYawOffset, 0);
                assertEquals(compatibility.bodyYawOffset(compatibilityActor, 1),
                        split.bodyYawOffset(preparedActor, 1), 0);
            }
        }
    }

    @Test void resetRepeatedCommitAndForeignPreparationCannotRestoreOrOverwriteState(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9846), true)) {
            var player = world.serverPlayer(new UUID(0, 9847));
            var first = snapshot(player, 1, 8, true);
            var second = strafeSnapshot(player, 2, 8.2);
            var third = strafeSnapshot(player, 3, 8.4);
            var presentation = new PlayerPhysicsClientPresentation();
            var reference = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    CardinalFrame.Y_POS, new Vec3d(0, 0, 1));

            assertTrue(presentation.install(player, first));
            assertNotNull(presentation.acceptedEyePosition(player, first, reference, .5F));
            var staleAfterTick = presentation.prepare(player, second);
            presentation.tick(player);
            assertFalse(staleAfterTick.isBoundTo(presentation, player, second));
            assertFalse(presentation.commitPrepared(staleAfterTick));
            var stale = presentation.prepare(player, second);
            presentation.reset();
            assertFalse(stale.isBoundTo(presentation, player, second));
            assertFalse(presentation.commitPrepared(stale));
            assertNull(presentation.acceptedEyePosition(player, first, reference, .5F),
                    "a stale result cannot restore presentation cleared by reset");
            assertEquals(0, presentation.bodyYawOffset(player, 1));

            var winning = presentation.prepare(player, second);
            var competing = presentation.prepare(player, third);
            assertTrue(presentation.commitPrepared(winning));
            assertFalse(presentation.commitPrepared(competing),
                    "another successful commit invalidates preparations from its prior generation");
            assertFalse(presentation.commitPrepared(winning), "a prepared result can commit only once");

            var otherPresentation = new PlayerPhysicsClientPresentation();
            var foreign = otherPresentation.prepare(player, third);
            assertFalse(presentation.commitPrepared(foreign));
            assertTrue(foreign.isBoundTo(otherPresentation, player, third));
            assertTrue(otherPresentation.commitPrepared(foreign),
                    "rejecting a foreign result leaves its owning presentation unchanged");
        }
    }

    private record ActorState(double posX, double posY, double posZ,
                              double prevPosX, double prevPosY, double prevPosZ,
                              float width, float height, float eyeHeight,
                              double motionX, double motionY, double motionZ,
                              boolean onGround, boolean collidedHorizontally,
                              boolean collidedVertically, boolean collided,
                              float fallDistance, Object bounds) { }

    private static ActorState actorState(EntityPlayer player) {
        return new ActorState(player.posX, player.posY, player.posZ,
                player.prevPosX, player.prevPosY, player.prevPosZ,
                player.width, player.height, player.eyeHeight,
                player.motionX, player.motionY, player.motionZ,
                player.onGround, player.collidedHorizontally, player.collidedVertically,
                player.collided, player.fallDistance, player.getEntityBoundingBox());
    }

    private static void assertActorStateEquals(ActorState expected, EntityPlayer actual) {
        ActorState observed = actorState(actual);
        assertEquals(expected.posX(), observed.posX(), 0);
        assertEquals(expected.posY(), observed.posY(), 0);
        assertEquals(expected.posZ(), observed.posZ(), 0);
        assertEquals(expected.prevPosX(), observed.prevPosX(), 0);
        assertEquals(expected.prevPosY(), observed.prevPosY(), 0);
        assertEquals(expected.prevPosZ(), observed.prevPosZ(), 0);
        assertEquals(expected.width(), observed.width(), 0);
        assertEquals(expected.height(), observed.height(), 0);
        assertEquals(expected.eyeHeight(), observed.eyeHeight(), 0);
        assertEquals(expected.motionX(), observed.motionX(), 0);
        assertEquals(expected.motionY(), observed.motionY(), 0);
        assertEquals(expected.motionZ(), observed.motionZ(), 0);
        assertEquals(expected.onGround(), observed.onGround());
        assertEquals(expected.collidedHorizontally(), observed.collidedHorizontally());
        assertEquals(expected.collidedVertically(), observed.collidedVertically());
        assertEquals(expected.collided(), observed.collided());
        assertEquals(expected.fallDistance(), observed.fallDistance(), 0);
        assertSame(expected.bounds(), observed.bounds());
    }

    private static PlayerPhysicalSnapshot strafeSnapshot(EntityPlayer player, long revision, double x) {
        var base = snapshot(player, revision, 8, true);
        var placement = base.placement();
        return new PlayerPhysicalSnapshot(base.scope(), revision,
                new PlayerBodyPlacement(new Vec3d(x, 65, 8), placement.attitude(), placement.shape()),
                new Vec3d(4, 0, 0), base.contacts());
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
