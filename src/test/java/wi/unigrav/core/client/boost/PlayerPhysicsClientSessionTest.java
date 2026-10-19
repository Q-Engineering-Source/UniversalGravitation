package wi.unigrav.core.client.boost;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.entity.player.EntityPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.client.ClientBoostRouting;
import wi.unigrav.core.client.PlayerPhysicsClientMirror;
import wi.unigrav.core.client.PlayerPhysicsClientInstallationTestSupport;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.PlayerPhysicsServerIntegrationTest;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsClientSessionTest {
    @Test void typedEnvironmentRecoveryRequiresExactPriorCustodyAndDuplicatesCannotMutateItsSuccessor(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(81, 82), true)) {
            var actor = world.serverPlayer(new UUID(83, 84));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(85, 86), actor.dimension, 3);
            var client = new PlayerPhysicsClientSession(() -> {}, () -> {});
            var installation = new PlayerPhysicsClientInstallationTestSupport(client, actor);
            prepare(client, scope);
            var accepted = mirrorSnapshot(scope, 0, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F, 8, 70, 8, new Vec3d(0, 0, 0));
            var owned = statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null);
            assertTrue(installation.install(owned));
            assertTrue(client.drivesMotion());
            var successor = new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(87, 88), scope.dimension(), 4);
            var ordinary = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, successor, 1, 1,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertFalse(client.receive(ordinary));
            var wrongScope = new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(89, 90), scope.dimension(), 3);
            for (var receipt : java.util.List.of(
                    new PlayerPhysicsProtocol.ServerEnvironmentRecovery(wrongScope, 1, OptionalInt.empty()),
                    new PlayerPhysicsProtocol.ServerEnvironmentRecovery(scope, 2, OptionalInt.empty()),
                    new PlayerPhysicsProtocol.ServerEnvironmentRecovery(scope, 1, OptionalInt.of(19)))) {
                assertFalse(client.receive(PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER,
                        successor, 1, 1, receipt)));
                assertSame(accepted, client.snapshot().orElseThrow());
            }
            var receipt = new PlayerPhysicsProtocol.ServerEnvironmentRecovery(scope, 1, OptionalInt.empty());
            var recovery = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER, successor, 1, 1, receipt);
            assertTrue(client.receive(recovery));
            long generation = client.ownerGeneration();
            assertFalse(client.owns());
            assertTrue(client.receive(recovery));
            assertEquals(generation, client.ownerGeneration());
            var applications = new ArrayList<PlayerPhysicsProtocol.Packet>();
            client.prepare(new CPacketPlayer.Position(8, 70, 8, false), new Vec3d(8, 70, 8), 0, 0,
                    false, Optional.empty(), applications::add);
            assertEquals(1, applications.size());
            assertEquals(successor, applications.getFirst().scope());
            var recovered = mirrorSnapshot(successor, 1, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F, 8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(installation.install(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 2, recovered, null)));
            generation = client.ownerGeneration();
            assertTrue(client.receive(recovery));
            assertEquals(generation, client.ownerGeneration());
            assertSame(recovered, client.snapshot().orElseThrow());
            assertTrue(client.drivesMotion());
            assertFalse(installation.install(owned), "the old scope cannot install after recovery");
            var olderReceipt = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER,
                    new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(91, 92), scope.dimension(), 5), 1, 1, receipt);
            assertFalse(client.receive(olderReceipt), "a new successor cannot be replaced using the obsolete predecessor");
            client.nativeCorrection(23);
            assertFalse(client.receive(recovery), "a new native TP invalidates the accepted recovery replay identity");
            assertSame(recovered, client.snapshot().orElseThrow());
        }
    }

    @Test void pendingNativeTeleportRejectsAnOtherwiseExactServerEnvironmentRecovery(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(93, 94), true)) {
            var actor = world.serverPlayer(new UUID(95, 96));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(97, 98), actor.dimension, 1);
            var client = new PlayerPhysicsClientSession(() -> {}, () -> {});
            var installation = new PlayerPhysicsClientInstallationTestSupport(client, actor);
            prepare(client, scope);
            var accepted = mirrorSnapshot(scope, 0, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F, 8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(installation.install(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            client.nativeCorrection(31);
            var next = new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(99, 100), scope.dimension(), 2);
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.OFFER, next, 1, 1,
                    new PlayerPhysicsProtocol.ServerEnvironmentRecovery(scope, 1, OptionalInt.of(31)));
            assertFalse(client.receive(packet));
            assertSame(accepted, client.snapshot().orElseThrow());
            assertTrue(client.drivesMotion());
        }
    }

    @Test void nativeCorrectionRetainedOwnerAcceptsOnlyFreshEpochOfferAfterRealMirrorInstall(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 991), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 992));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 993), actor.dimension, 3);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .6F, 1.8F, 1.62F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            var holder = new AtomicReference<PlayerPhysicsClientSession>();
            var recoveryOffer = new AtomicReference<PlayerPhysicsProtocol.Packet>();
            var client = new PlayerPhysicsClientSession(s -> {
                if (recoveryOffer.get() != null) {
                    assertThrows(IllegalStateException.class, () -> holder.get().receive(recoveryOffer.get()),
                            "an installer callback cannot reenter OFFER and replace its in-progress owner");
                }
                return PlayerPhysicsClientMirror.install(actor, s);
            }, () -> {}, () -> {});
            holder.set(client);
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            var fresh = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 994), actor.dimension, 4);
            var offer = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, fresh, 0, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertFalse(client.receive(offer), "healthy owners never accept unsolicited replacement offers");
            client.nativeCorrection(37);
            assertTrue(client.owns());
            assertTrue(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            for (var rejected : new PlayerPhysicalSnapshot.Scope[] {
                    scope, new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 995), actor.dimension, 3),
                    new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 996), actor.dimension, 2),
                    new PlayerPhysicalSnapshot.Scope(new UUID(0, 997), new UUID(0, 998), actor.dimension, 4),
                    new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 999), actor.dimension + 1, 4)}) {
                assertFalse(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, rejected,
                        0, 2, Optional.empty(), Optional.empty(), Optional.empty())));
                assertSame(accepted, client.snapshot().orElseThrow());
            }
            assertTrue(client.receive(offer), "native-correction custody must negotiate a strictly newer recovery epoch");
            assertFalse(client.owns());
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty(), "OFFER cannot publish a recovered presentation");
            assertTrue(client.input(idleControls()).isEmpty());
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            client.prepare(new CPacketPlayer.Position(30, 40, 50, false), new Vec3d(30, 40, 50), 0, 0,
                    false, Optional.empty(), sent::add);
            assertEquals(1, sent.size());
            assertEquals(PlayerPhysicsProtocol.Kind.APPLY, sent.getFirst().kind());
            assertEquals(fresh, sent.getFirst().scope());
            assertEquals(2, sent.getFirst().offerSequence());
            assertEquals(1, sent.getFirst().sequence());
            var recovered = mirrorSnapshot(fresh, 1, CardinalFrame.Z_NEG, .6F, 1.8F, 1.62F,
                    30, 40, 50, new Vec3d(0, 0, 0));
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, recovered, null)),
                    "a different proposal cannot acquire the new scope");
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, accepted, null)));
            recoveryOffer.set(offer);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, 2, recovered, null)));
            assertTrue(client.drivesMotion());
            assertSame(recovered, client.presentationSnapshot().orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, recovered));
            assertFalse(client.receive(offer), "duplicate OFFER cannot revoke a recovered owner");
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 3, accepted, null)));
            assertSame(recovered, client.presentationSnapshot().orElseThrow());
        }
    }
    @Test void freeMirrorClearsOnlyCardinalReferenceAndForwardsFullQuaternionWithPendingIntent(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 910), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 911));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 912), actor.dimension, 1);
            var cardinal = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            var client = new PlayerPhysicsClientSession(s -> PlayerPhysicsClientMirror.install(actor, s), () -> {}, () -> {});
            prepare(client, scope);
            var reference = new PlayerBodyReference.State(Optional.empty(),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT, CardinalFrame.X_POS,
                            new Vec3d(0, 0, 1)), PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, scope, 1, 1,
                    Optional.empty(), Optional.of(cardinal), Optional.empty(), OptionalInt.empty(), Optional.empty(),
                    Optional.empty(), Optional.of(reference))));
            long pending = client.requestBodyReferenceUnlock();
            var q = new Rotation3d(.2, .3, .4, .5);
            var free = new PlayerPhysicalSnapshot(scope, 2, new PlayerBodyPlacement(new Vec3d(8, 70, 8), q,
                    new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                    new Vec3d(1, 2, 3), cardinal.contacts());
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, free, null)));
            assertTrue(client.bodyReferenceState().isEmpty());
            assertTrue(client.acceptedEffectiveBodyReference(free).isEmpty());
            assertTrue(client.drivesMotion());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, free));
            assertEquals(free.placement().transport().y(), actor.posY);
            assertEquals(free.placement().bounds().min().y(), actor.getEntityBoundingBox().minY);
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                    false, false, false, false, 5, 1, PlayerPhysicsProtocol.BodyReferenceIntent.none(), Optional.of(q));
            var input = client.input(controls).orElseThrow();
            assertEquals(Optional.of(q), input.controls().orElseThrow().desiredBodyAttitude());
            assertEquals(pending, input.controls().orElseThrow().bodyReferenceIntent().requestId());
            assertEquals(PlayerPhysicsProtocol.BodyReferenceAction.UNLOCK,
                    input.controls().orElseThrow().bodyReferenceIntent().action(), "absence of receipt never terminates pending intent");
            actor.setElytraFlying();
            client.runLegacyMotion(actor, () -> fail("accepted Free owner retains native movement gate"));
            client.runNativePose(actor, () -> actor.setPosition(99, 100, 101));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, free));
        }
    }
    @Test void nativePoseRestoreFailureFencesActualMirrorAndKeepsCause(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 891), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 892));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 893), actor.dimension, 1);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(1, 2, 3));
            var fail = new AtomicBoolean();
            var writes = new AtomicInteger();
            var sentinel = new IllegalStateException("native mirror setter sentinel");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                writes.incrementAndGet();
                assertTrue(PlayerPhysicsClientMirror.install(actor, snapshot));
                if (fail.get()) throw sentinel;
                return true;
            }, () -> {}, () -> {});
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            fail.set(true);
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> client.runNativePose(actor, () -> actor.setPosition(50, 60, 70)));
            assertSame(sentinel, failure.getCause());
            assertTrue(client.installationFenceActive(actor));
            assertTrue(client.owns());
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            assertTrue(client.input(idleControls()).isEmpty());
            int afterFailure = writes.get();
            client.runNativePose(actor, () -> fail("fenced pose callback"));
            client.runLegacyMotion(actor, () -> fail("fenced motion callback"));
            assertEquals(afterFailure, writes.get());
            var request = client.recoveryRequest().orElseThrow();
            assertSame(request, client.recoveryRequest().orElseThrow());
            assertEquals(scope, request.scope());
            assertEquals(PlayerPhysicsProtocol.Kind.RECOVER, request.kind());
            assertEquals(2, request.sequence(), "RECOVER consumes one outgoing sequence after APPLY");
            var foreign = fixture.serverPlayer(new UUID(0, 894));
            var calls = new AtomicInteger();
            client.runNativePose(foreign, calls::incrementAndGet);
            assertEquals(1, calls.get());
            fail.set(false);
            var newScope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 895), actor.dimension, 2);
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, newScope, 3, 2,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, accepted, null)));
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            client.prepare(new CPacketPlayer.Position(30, 40, 50, false), new Vec3d(30, 40, 50), 0, 0,
                    false, Optional.empty(), sent::add);
            var recovered = mirrorSnapshot(newScope, 1, CardinalFrame.Z_POS, .4F, 1.1F, .99F,
                    31, 41, 51, new Vec3d(7, 8, 9));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, 2, recovered, null)));
            assertTrue(client.drivesMotion());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, recovered));
        }
    }

    @Test void nativePoseCallbackCauseAndRestoreSuppressedRemainDistinct(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 896), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 897));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 898), actor.dimension, 1);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(1, 2, 3));
            var failRestore = new AtomicBoolean();
            var restore = new AssertionError("setter failed after write");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                assertTrue(PlayerPhysicsClientMirror.install(actor, snapshot));
                if (failRestore.get()) throw restore;
                return true;
            }, () -> {}, () -> {});
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            var ordinary = new IllegalArgumentException("ordinary callback failed");
            assertSame(ordinary, assertThrows(IllegalArgumentException.class,
                    () -> client.runNativePose(actor, () -> { throw ordinary; })));
            assertTrue(client.drivesMotion());
            failRestore.set(true);
            var callback = new AssertionError("callback failed before cleanup");
            var result = assertThrows(RuntimeException.class,
                    () -> client.runNativePose(actor, () -> { throw callback; }));
            assertSame(callback, result.getCause());
            assertArrayEquals(new Throwable[] {restore}, callback.getSuppressed());
            assertTrue(client.installationFenceActive(actor));
        }
    }

    @Test void reentrantNativeRestoreCannotFenceTheLaterLifetime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 899), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 900));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 901), actor.dimension, 1);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(1, 2, 3));
            var reenter = new AtomicBoolean();
            var holder = new AtomicReference<PlayerPhysicsClientSession>();
            var restore = new IllegalStateException("old restoration failed after switching lifetime");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                assertTrue(PlayerPhysicsClientMirror.install(actor, snapshot));
                if (reenter.get()) {
                    holder.get().reset();
                    throw restore;
                }
                return true;
            }, () -> {}, () -> {});
            holder.set(client);
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            reenter.set(true);
            assertSame(restore, assertThrows(IllegalStateException.class,
                    () -> client.runNativePose(actor, () -> {})));
            assertFalse(client.installationFenceActive(actor));
            assertFalse(client.owns());
            assertTrue(client.recoveryRequest().isEmpty());
        }
    }

    @Test void acceptedReplacementFailureFencesRealMirrorUntilExactRetryAndNewerRevisionRecovery(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 801), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 802));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 803), actor.dimension, 1);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(1, 2, 3));
            var failed = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    20, 30, 40, new Vec3d(4, 5, 6));
            var failAfterWrite = new AtomicReference<PlayerPhysicalSnapshot>();
            var rejectAfterWrite = new AtomicReference<PlayerPhysicalSnapshot>();
            var failure = new IllegalStateException("mirror failed after changing native fields");
            var legacyWrites = new AtomicInteger();
            var poseWrites = new AtomicInteger();
            var client = new PlayerPhysicsClientSession(snapshot -> {
                if (!PlayerPhysicsClientMirror.install(actor, snapshot)) return false;
                if (failAfterWrite.compareAndSet(snapshot, null)) throw failure;
                if (rejectAfterWrite.compareAndSet(snapshot, null)) return false;
                return true;
            }, () -> {}, () -> {});
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            assertTrue(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isPresent());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, accepted));

            long unlock = client.requestBodyReferenceUnlock();
            var handoff = new PlayerPhysicsProtocol.BodyReferenceReceipt(unlock,
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var failedPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, failed, handoff);
            failAfterWrite.set(failed);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(failedPacket)));
            assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLICATION_THREW, client.lastStateDisposition());
            assertTrue(client.owns(), "protocol custody survives the failed replacement");
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            assertSame(accepted, client.snapshot().orElseThrow(), "the prior accepted snapshot remains diagnostic state only");
            assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING,
                    client.bodyReferenceStatus().status(), "a failed install cannot accept its body-reference receipt");
            assertTrue(client.input(idleControls()).isEmpty(), "a failed state cannot emit a body-reference ACK");
            assertEquals(1.45F, client.physicalEyeHeight(actor, 1.45F),
                    "failed custody keeps native eye height instead of the prior accepted snapshot");
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failed),
                    "the callback changed the real actor's dimensions, position, and collision box before throwing");
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, accepted),
                    "the old accepted snapshot no longer describes the real actor");

            actor.setElytraFlying();
            double failedMotionX = actor.motionX;
            client.runLegacyMotion(actor, () -> {
                legacyWrites.incrementAndGet();
                actor.motionX = 99;
            });
            assertEquals(0, legacyWrites.get(), "failed custody keeps the late legacy motion writer retired");
            assertEquals(failedMotionX, actor.motionX, "the native movement writer cannot mutate the failed mirror");
            assertTrue(client.nativeMovementMustRemainGated());

            var oldState = statePacket(PlayerPhysicsProtocol.Kind.STATE, 3, accepted, handoff);
            assertFalse(client.receive(oldState), "a newer sequence carrying the old accepted geometry cannot bypass the fence");
            var nonExactSameRevision = statePacket(PlayerPhysicsProtocol.Kind.STATE, 3, failed, handoff);
            assertFalse(client.receive(nonExactSameRevision), "only the exact failed packet may retry the failed revision");
            assertEquals(PlayerPhysicsClientSession.StateDisposition.ATTEMPT_FENCED, client.lastStateDisposition());
            assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING,
                    client.bodyReferenceStatus().status(), "stale or same-revision packets cannot ACK a rejected receipt");
            var refusedNative = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                    scope, 4, 1, new PlayerPhysicsProtocol.NativeHandoff(1, failed.revision(), failed.worldVelocity()));
            assertFalse(client.receiveNativeTransfer(refusedNative, () -> !actor.isDead,
                    (velocity, current) -> fail("a fenced mirror must not receive native velocity")),
                    "a NATIVE packet cannot release the retained custody during failure");
            var providerPlan = new ClientBoostRouting.Plan(new Vec3(1, 0, 0), 2, 10, 5, false);
            try (var registration = ClientBoostRouting.register(ignored ->
                    client.drivesMotion() ? providerPlan : null)) {
                assertNull(ClientBoostRouting.query(null),
                        "the real provider registry preserves its no-plan result during the session fence");
            }

            var foreignActor = fixture.serverPlayer(new UUID(0, 804));
            client.runLegacyMotion(foreignActor, legacyWrites::incrementAndGet);
            client.runNativePose(foreignActor, poseWrites::incrementAndGet);
            assertEquals(1, legacyWrites.get(), "an unrelated actor keeps its native writer");
            assertEquals(1, poseWrites.get(), "an unrelated actor keeps its native pose callback");
            client.runNativePose(actor, () -> {
                poseWrites.incrementAndGet();
                actor.setPosition(100, 110, 120);
            });
            assertEquals(1, poseWrites.get(), "failed accepted custody skips the current actor's native pose writer");
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failed),
                    "the skipped pose callback cannot further mutate the half-installed geometry");

            actor.clearElytraFlying();
            assertTrue(client.receive(failedPacket), "the exact failed packet must reinstall the real mirror");
            assertTrue(client.drivesMotion());
            assertTrue(client.presentationSnapshot().orElseThrow() == failed);
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failed));
            assertTrue(client.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "the Session compatibility installer does not include the production Presentation/decode commit chain");

            client.runNativePose(actor, () -> {
                poseWrites.incrementAndGet();
                actor.width = .3F;
                actor.height = .9F;
                actor.setPosition(50, 60, 70);
            });
            assertEquals(2, poseWrites.get(), "the pose callback resumes exactly once after recovery");
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failed),
                    "the recovered healthy path restores the current accepted geometry, not the prior revision");

            var rejected = mirrorSnapshot(scope, 3, CardinalFrame.Z_POS, .3F, .9F, .81F,
                    60, 70, 80, new Vec3d(7, 8, 9));
            var rejectedPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 3, rejected, null);
            rejectAfterWrite.set(rejected);
            assertFalse(client.receive(rejectedPacket), "a false installer result is still an installation failure");
            assertEquals(PlayerPhysicsClientSession.StateDisposition.INSTALL_REJECTED, client.lastStateDisposition());
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, rejected),
                    "the rejecting installer may already have changed native fields");
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 4, rejected, null)),
                    "a different packet at the failed revision is not an exact retry");

            var newer = mirrorSnapshot(scope, 4, CardinalFrame.Z_POS, .4F, 1.1F, .99F,
                    61, 71, 81, new Vec3d(10, 11, 12));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 4, newer, null)),
                    "a newer physical revision may recover the failed installation");
            assertTrue(client.drivesMotion());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, newer));
        }
    }

    @Test void resetAndNewLifetimeKeepTheirOwnInstallationFence(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 811), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 812));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 813), actor.dimension, 1);
            var resetDuringNextInstall = new AtomicBoolean(false);
            var rejectAfterWrite = new AtomicReference<PlayerPhysicalSnapshot>();
            var current = new AtomicReference<PlayerPhysicsClientSession>();
            var client = new PlayerPhysicsClientSession(snapshot -> {
                if (resetDuringNextInstall.compareAndSet(true, false)) {
                    current.get().reset();
                    return false;
                }
                if (!PlayerPhysicsClientMirror.install(actor, snapshot)) return false;
                return !rejectAfterWrite.compareAndSet(snapshot, null);
            }, () -> {}, () -> {});
            current.set(client);
            prepare(client, scope);
            var initial = mirrorSnapshot(scope, 1, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, initial, null)));
            resetDuringNextInstall.set(true);
            var abandoned = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .3F, .9F, .81F,
                    9, 71, 9, new Vec3d(0, 0, 0));
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, abandoned, null)));
            assertEquals(PlayerPhysicsClientSession.StateDisposition.LIFETIME_CHANGED, client.lastStateDisposition());
            assertFalse(client.owns());
            assertTrue(client.snapshot().isEmpty());
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());

            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, initial, null)));
            var failingNextLife = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .3F, .9F, .81F,
                    10, 72, 10, new Vec3d(0, 0, 0));
            rejectAfterWrite.set(failingNextLife);
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, failingNextLife, null)));
            assertTrue(client.owns());
            assertFalse(client.drivesMotion(), "the new lifetime must establish its own failed-install fence");
            assertTrue(client.presentationSnapshot().isEmpty());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failingNextLife));

            client.reset();
            assertFalse(client.owns());
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            prepare(client, scope);
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, initial, null)));
            var thirdLifeFailure = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .4F, 1.2F, 1.0F,
                    11, 73, 11, new Vec3d(0, 0, 0));
            rejectAfterWrite.set(thirdLifeFailure);
            assertFalse(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, thirdLifeFailure, null)));
            assertTrue(client.owns());
            assertFalse(client.drivesMotion(), "a later lifetime's new failure cannot be cleared by the old reset");
            assertTrue(client.presentationSnapshot().isEmpty());
        }
    }

    @Test void failedInstallationFenceRejectsSameScopeOfferAndPreservesExactRetryAcrossPrepare(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 821), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 822));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 823), actor.dimension, 1);
            var failAfterWrite = new AtomicReference<PlayerPhysicalSnapshot>();
            var failure = new IllegalStateException("same-scope proposal must not replace failed installation");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                if (!PlayerPhysicsClientMirror.install(actor, snapshot)) return false;
                if (failAfterWrite.compareAndSet(snapshot, null)) throw failure;
                return true;
            }, () -> {}, () -> {});
            prepare(client, scope);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));

            var failed = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    20, 30, 40, new Vec3d(4, 5, 6));
            var failedPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, failed, null);
            failAfterWrite.set(failed);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(failedPacket)));
            boolean fencedWhileFailed = !client.drivesMotion();

            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            var movement = new CPacketPlayer.Position(21, 31, 41, false);
            var prepared = client.prepare(movement, new Vec3d(21, 31, 41), 0, 0,
                    false, Optional.empty(), sent::add);
            var sameScopeOffer = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 3, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            boolean sameScopeOfferAccepted = client.receive(sameScopeOffer);
            boolean exactRetryAccepted = client.receive(failedPacket);
            long nextInputSequence = client.input(idleControls()).orElseThrow().sequence();

            assertAll(
                    () -> assertTrue(fencedWhileFailed),
                    () -> assertSame(movement, prepared),
                    () -> assertTrue(sent.isEmpty(), "prepare must not emit a legacy APPLY during failed custody"),
                    () -> assertFalse(sameScopeOfferAccepted, "same-scope offer must not replace the failed proposal"),
                    () -> assertTrue(exactRetryAccepted, "the original exact failed packet remains retryable"),
                    () -> assertEquals(2, nextInputSequence, "prepare must not consume the next outgoing sequence"),
                    () -> assertTrue(client.drivesMotion()),
                    () -> assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failed))
            );
        }
    }

    @Test void newerWorldEpochOfferEndsTheFailedInstallationLifetime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 831), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 832));
            var oldScope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 833), actor.dimension, 1);
            var newScope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 834), actor.dimension, 2);
            var failAfterWrite = new AtomicReference<PlayerPhysicalSnapshot>();
            var failure = new IllegalStateException("replacement failed before world-epoch handoff");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                if (!PlayerPhysicsClientMirror.install(actor, snapshot)) return false;
                if (failAfterWrite.compareAndSet(snapshot, null)) throw failure;
                return true;
            }, () -> {}, () -> {});
            prepare(client, oldScope);
            var accepted = mirrorSnapshot(oldScope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            var failed = mirrorSnapshot(oldScope, 2, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    20, 30, 40, new Vec3d(4, 5, 6));
            var failedPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, failed, null);
            failAfterWrite.set(failed);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(failedPacket)));

            var newScopeOffer = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, newScope, 3, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertTrue(client.receive(newScopeOffer), "a later world epoch on the same actor is a lifetime switch");
            assertFalse(client.receive(failedPacket), "the previous lifetime's retry cannot cross the scope switch");
            var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
            client.prepare(new CPacketPlayer.Position(30, 40, 50, false), new Vec3d(30, 40, 50), 0, 0,
                    false, Optional.empty(), sent::add);
            assertEquals(1, sent.size());
            assertEquals(PlayerPhysicsProtocol.Kind.APPLY, sent.getFirst().kind());
            assertEquals(newScope, sent.getFirst().scope());
            assertEquals(2, sent.getFirst().offerSequence());

            var recovered = mirrorSnapshot(newScope, 1, CardinalFrame.Z_POS, .4F, 1.1F, .99F,
                    31, 41, 51, new Vec3d(7, 8, 9));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, 2, recovered, null)));
            assertTrue(client.drivesMotion());
            assertSame(recovered, client.snapshot().orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, recovered));
        }
    }

    @Test void thrownInstallationOrRetirementRetainsCauseAndReportsNoFalseSuccess() {
        for (boolean throwDuringRetirement : new boolean[] {false, true}) {
            var fail = new AtomicBoolean(true);
            var failure = new IllegalStateException("application failure");
            var client = new PlayerPhysicsClientSession(snapshot -> {
                if (!throwDuringRetirement && fail.get()) throw failure;
                return true;
            }, () -> {
                if (throwDuringRetirement && fail.get()) throw failure;
            }, () -> {});
            prepare(client);
            var packet = state(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(packet)));
            assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLICATION_THREW, client.lastStateDisposition());
            assertFalse(client.drivesMotion());
            fail.set(false);
            assertTrue(client.receive(packet), "diagnostics must not change exact retry admission");
            assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLIED, client.lastStateDisposition());
        }
    }
    @Test void diagnosticsDistinguishRejectionRetryAndUnappliedRetransmission() {
        var allowed = new AtomicBoolean(false);
        var client = new PlayerPhysicsClientSession(snapshot -> allowed.get(), () -> {}, () -> {});
        prepare(client);
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9)));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.INSTALL_REJECTED, client.lastStateDisposition());
        allowed.set(true);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9)));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLIED, client.lastStateDisposition());
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 9)));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.RETRANSMISSION, client.lastStateDisposition());
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9)));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.STALE_SEQUENCE, client.lastStateDisposition());
        client.nativeCorrection(42);
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 3, 2, 9)));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.TELEPORT_MISMATCH, client.lastStateDisposition());
    }

    @Test void bodyReferenceViewRetainsAcceptedLockAndAcknowledgesOnlyInstalledSafeState() {
        var installationAllowed = new AtomicBoolean(true);
        var client = new PlayerPhysicsClientSession(snapshot -> installationAllowed.get(), () -> {}, () -> {});
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));

        long firstLock = client.requestBodyReferenceLock(CardinalFrame.X_POS, new Vec3d(0, 1, 0));
        var xSnapshot = bodySnapshot(1, CardinalFrame.X_POS);
        var locked = new PlayerPhysicsProtocol.BodyReferenceReceipt(firstLock,
                PlayerPhysicsProtocol.BodyReferenceDecision.LOCKED, firstLock, Optional.of(CardinalFrame.X_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.empty());
        assertTrue(client.receive(bodyState(2, xSnapshot, locked)));
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.LOCKED, client.bodyReferenceStatus().status());

        long rejectedRequest = client.requestBodyReferenceLock(CardinalFrame.Y_POS, new Vec3d(0, 0, 1));
        var pending = client.bodyReferenceStatus();
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, pending.status());
        assertEquals(firstLock, pending.acceptedLock().orElseThrow().requestId());
        assertEquals(CardinalFrame.X_POS, pending.acceptedLock().orElseThrow().frame());
        assertEquals(CardinalFrame.Y_POS, pending.pendingRequest().orElseThrow().frame().orElseThrow());
        var rejected = new PlayerPhysicsProtocol.BodyReferenceReceipt(rejectedRequest,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, firstLock, Optional.of(CardinalFrame.X_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED));
        assertTrue(client.receive(bodyState(3, xSnapshot, rejected)));
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.REJECTED, client.bodyReferenceStatus().status());
        assertEquals(firstLock, client.bodyReferenceStatus().acceptedLock().orElseThrow().requestId());

        long unlockRequest = client.requestBodyReferenceUnlock();
        var unlockPending = client.bodyReferenceStatus();
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, unlockPending.status());
        assertEquals(firstLock, unlockPending.acceptedLock().orElseThrow().requestId());
        var restorePending = new PlayerPhysicsProtocol.BodyReferenceReceipt(unlockRequest,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, firstLock, Optional.of(CardinalFrame.X_POS),
                Optional.of(new Vec3d(0, 1, 0)),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_RESTORE_PENDING));
        assertTrue(client.receive(bodyState(4, xSnapshot, restorePending)));
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, client.bodyReferenceStatus().status());
        assertEquals(CardinalFrame.X_POS, client.bodyReferenceStatus().acceptedLock().orElseThrow().frame());

        var ySnapshot = bodySnapshot(2, CardinalFrame.Y_POS);
        var restored = new PlayerPhysicsProtocol.BodyReferenceReceipt(unlockRequest,
                PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        var safeState = bodyState(5, ySnapshot, restored);
        installationAllowed.set(false);
        assertFalse(client.receive(safeState));
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, client.bodyReferenceStatus().status());
        assertTrue(client.input(idleControls()).isEmpty(),
                "a failed mirror installation fences INPUT and its safe geometry acknowledgement");
        assertTrue(client.presentationSnapshot().isEmpty());
        assertTrue(client.installationFenceActive());
        assertTrue(client.nativeMovementMustRemainGated());
        assertEquals(CardinalFrame.X_POS, client.bodyReferenceStatus().acceptedLock().orElseThrow().frame(),
                "the rejected replacement retains custody of the last accepted lock");

        installationAllowed.set(true);
        assertTrue(client.receive(safeState), "the exact failed state remains retryable");
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.UNLOCKED, client.bodyReferenceStatus().status());
        assertTrue(client.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                "Session-level snapshot acceptance alone cannot authorize the NativeRestore ACK");
    }

    @Test void installedManualReferenceDrivesTheRealSessionKeyboardConsumer() {
        var client = new PlayerPhysicsClientSession(snapshot -> true, () -> {}, () -> {});
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        var nativePlane = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1), true, true, false, false);
        assertEquals(-1, nativePlane.translation().x(), 1e-6,
                "without a manual/environment lock, forward follows the live yaw X component");
        assertEquals(0, nativePlane.translation().z(), 1e-6,
                "without a manual/environment lock, forward follows the live yaw Z component");
        assertEquals(0.0, nativePlane.translation().y(), 1e-12,
                "ordinary jump and sneak are flags, not free vertical propulsion");

        Vec3d heading = new Vec3d(0, 1, 0);
        long requestId = client.requestBodyReferenceLock(CardinalFrame.X_POS, heading);
        var requestedInput = client.input(keyboardControls(client, 0, 0, 0, new Vec3(0, 0, 1),
                false, false, false, false)).orElseThrow();
        assertEquals(requestId, requestedInput.controls().orElseThrow().bodyReferenceIntent().requestId());

        var manual = new PlayerBodyReference.ManualIntent(requestId, CardinalFrame.X_POS, heading);
        var reference = new PlayerBodyReference.State(Optional.of(manual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL, CardinalFrame.X_POS, heading),
                PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.LOCKED, requestId,
                Optional.of(CardinalFrame.X_POS), Optional.of(heading), Optional.empty());
        assertTrue(client.receive(bodyStateWithReference(2, bodySnapshot(1, CardinalFrame.X_POS), receipt, reference)));

        var controls = keyboardControls(client, 0, 1, 0, new Vec3(0, 0, 1), true, true, false, false);
        assertEquals(new Vec3d(0, 0, 1), controls.translation(),
                "forward follows the live view within the accepted physical-up plane");
        assertTrue(controls.jump(), "Space retains its ordinary jump meaning");
        assertTrue(controls.sneak(), "Shift retains its ordinary sneak meaning");

        long pendingRequest = client.requestBodyReferenceLock(CardinalFrame.Y_POS, new Vec3d(0, 0, 1));
        var pendingControls = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(new Vec3d(0, 0, 1), pendingControls.translation(),
                "a pending desired frame preserves accepted physical up while view heading remains live");
        assertEquals(pendingRequest, client.input(pendingControls).orElseThrow()
                .controls().orElseThrow().bodyReferenceIntent().requestId());

        var rejectedReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(pendingRequest,
                PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED, requestId,
                Optional.of(CardinalFrame.X_POS), Optional.of(heading),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED));
        assertTrue(client.receive(bodyStateWithReference(3, bodySnapshot(2, CardinalFrame.X_POS), rejectedReceipt, reference)));
        var rejectedControls = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(new Vec3d(0, 0, 1), rejectedControls.translation(),
                "a rejected target preserves accepted physical up while view heading remains live");

        long unlockId = client.requestBodyReferenceUnlock();
        assertEquals(new Vec3d(0, 0, 1), keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false).translation(),
                "pending unlock keeps accepted physical up without freezing the live view heading");
        var unlockedReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(unlockId,
                PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0,
                Optional.empty(), Optional.empty(), Optional.empty());
        var safeNative = new PlayerBodyReference.State(Optional.empty(),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE,
                        CardinalFrame.Y_POS, new Vec3d(0, 0, 1)),
                PlayerBodyReference.Transition.MANUAL_CLEARED, Optional.empty(), Optional.empty());
        assertTrue(client.receive(bodyStateWithReference(4, bodySnapshot(3, CardinalFrame.Y_POS),
                unlockedReceipt, safeNative)));
        var restoredControls = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false);
        assertEquals(-1, restoredControls.translation().x(), 1e-6,
                "accepted unlock restores view-relative WASD yaw instead of freezing SAFE_NATIVE heading");
        assertEquals(0, restoredControls.translation().z(), 1e-6);

        var naturalState = new PlayerBodyReference.State(Optional.empty(),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.NATURAL,
                        CardinalFrame.Y_POS, new Vec3d(1, 0, 0)),
                PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        var naturalPacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, 5, 1,
                Optional.empty(), Optional.of(bodySnapshot(4, CardinalFrame.Y_POS)), Optional.empty(), OptionalInt.empty(),
                Optional.empty(), Optional.empty(), Optional.of(naturalState));
        assertTrue(client.receive(naturalPacket));
        var naturalControls = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false);
        assertEquals(-1, naturalControls.translation().x(), 1e-6,
                "NATURAL accepted geometry without manual/environment preference remains yaw-relative");
        assertEquals(0, naturalControls.translation().z(), 1e-6);
    }

    @Test void persistentEffectiveReferenceChangesUnderOneManualReceiptOnlyAfterGeometryInstall() {
        var installationAllowed = new AtomicBoolean(true);
        var client = new PlayerPhysicsClientSession(snapshot -> installationAllowed.get(), () -> {}, () -> {});
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));

        long requestId = client.requestBodyReferenceLock(CardinalFrame.Z_POS, new Vec3d(0, 1, 0));
        var manual = new PlayerBodyReference.ManualIntent(requestId, CardinalFrame.Z_POS, new Vec3d(0, 1, 0));
        var manualState = new PlayerBodyReference.State(Optional.of(manual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL,
                        CardinalFrame.Z_POS, new Vec3d(0, 1, 0)),
                PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        var lockedReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.LOCKED, requestId, Optional.of(CardinalFrame.Z_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.empty());
        assertTrue(client.receive(bodyStateWithReference(2, bodySnapshot(1, CardinalFrame.Z_POS),
                lockedReceipt, manualState)));

        var environmentState = new PlayerBodyReference.State(Optional.of(manual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                        CardinalFrame.X_POS, new Vec3d(0, 0, 1)),
                PlayerBodyReference.Transition.ENVIRONMENT_OVERRIDE, Optional.empty(), Optional.empty());
        var deferredReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.DEFERRED, requestId, Optional.of(CardinalFrame.Z_POS),
                Optional.of(new Vec3d(0, 1, 0)), Optional.empty());
        assertTrue(client.receive(bodyStateWithReference(3, bodySnapshot(2, CardinalFrame.X_POS),
                deferredReceipt, environmentState)),
                "same manual request receipt must not block a later effective environment state");
        assertEquals(environmentState, client.bodyReferenceState().orElseThrow());
        assertEquals(CardinalFrame.Z_POS, client.bodyReferenceStatus().acceptedLock().orElseThrow().frame(),
                "the legacy request view keeps the saved manual preference");
        assertTrue(client.nativeMovementMustRemainGated(), "the effective non-native shape still gates native movement");
        assertEquals(new Vec3d(0, 0, 1), keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false).translation(),
                "an installed environment floor overrides the saved manual preference for actual movement");

        long unlockId = client.requestBodyReferenceUnlock();
        var pendingUnlock = client.input(idleControls()).orElseThrow();
        assertEquals(unlockId, pendingUnlock.controls().orElseThrow().bodyReferenceIntent().requestId());
        assertEquals(new Vec3d(0, 0, 1), keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false).translation(),
                "pending manual unlock cannot clear the still-effective environmental floor reference");
        var environmentalAfterUnlock = new PlayerBodyReference.State(Optional.empty(),
                environmentState.effective(), PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        var acceptedUnlock = new PlayerPhysicsProtocol.BodyReferenceReceipt(unlockId,
                PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(client.receive(bodyStateWithReference(4, bodySnapshot(3, CardinalFrame.X_POS),
                acceptedUnlock, environmentalAfterUnlock)));
        assertEquals(new Vec3d(0, 0, 1), keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false).translation(),
                "accepted unlock clears saved manual intent but leaves the valid environment axis effective");

        var failedInstall = new PlayerBodyReference.State(Optional.of(manual),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.NATURAL,
                        CardinalFrame.X_POS, new Vec3d(0, 1, 0)),
                PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        installationAllowed.set(false);
        var failedPacket = bodyStateWithReference(5, bodySnapshot(4, CardinalFrame.X_POS),
                deferredReceipt, failedInstall);
        assertFalse(client.receive(failedPacket));
        assertEquals(environmentalAfterUnlock, client.bodyReferenceState().orElseThrow(),
                "an uninstalled newer state cannot replace the accepted source");
        assertTrue(client.installationFenceActive());
        assertTrue(client.nativeMovementMustRemainGated());
        assertTrue(client.presentationSnapshot().isEmpty(), "failed native geometry is not presentation authority");
        assertTrue(client.input(idleControls()).isEmpty(), "retained reference cannot authorize INPUT after failure");
        installationAllowed.set(true);
        assertTrue(client.receive(failedPacket), "only a successful current installation publishes the new source");
        assertEquals(failedInstall, client.bodyReferenceState().orElseThrow());
        assertFalse(client.installationFenceActive());
        assertTrue(client.presentationSnapshot().isPresent());
        assertTrue(client.input(idleControls()).isPresent());
        var tangentControls = keyboardControls(client, 90, 1, 0, new Vec3(0, 0, 1),
                false, false, false, false);
        assertEquals(new Vec3d(0, 0, 1), tangentControls.translation(),
                "installed NATURAL X+ uses the live view on its physical tangent plane");
        assertEquals(0, tangentControls.translation().dot(failedInstall.effective().frame().up()), 1e-12,
                "the accepted source must not restore world-X movement through the physical up axis");
    }
    private static final PlayerPhysicalSnapshot.Scope SCOPE = new PlayerPhysicalSnapshot.Scope(
            new UUID(0, 1), new UUID(0, 2), 0, 1);

    @Test void failedNativeApplicationFencesAnOlderOwnedAndRetriesWithoutRetiringTwice(@TempDir Path directory) {
        PlayerPhysicsServerIntegrationTest.NativeEntryFailureTestSupport.verify(directory);
    }

    @Test void nativeDelegationKeepsCustodyAndResumesThroughFreshApplyWithMonotonicSequence(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 1811), true)) {
            var actor = fixture.serverPlayer(SCOPE.playerId());
            var retirements = new AtomicInteger();
            var nativeEntries = new AtomicInteger();
            var client = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot),
                    retirements::incrementAndGet, nativeEntries::incrementAndGet);
            prepare(client);
            assertTrue(client.receive(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
            var installation = new PlayerPhysicsClientInstallationTestSupport(client, actor);
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5);
            long lastInput = 0;
            for (int i = 0; i < 10; i++) lastInput = client.input(controls).orElseThrow().sequence();
            var restore = nativeRestoreState(2, 1, stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 9).snapshot().orElseThrow());
            installAndSendNativeRestore(client, installation, restore);
            var nativePacket = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 3, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(1, 1, restore.snapshot().orElseThrow().worldVelocity()));
            assertTrue(nativePacket.clientbound());
            assertEquals(nativePacket, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(nativePacket)));
            assertTrue(client.receiveNativeTransfer(nativePacket, () -> !actor.isDead, nativeVelocityWriter(actor)));
            assertEquals(9 * .05, actor.motionX, 0);
            assertTrue(client.owns(), "legacy UG writers stay retired");
            assertFalse(client.drivesMotion(), "native travel is explicitly allowed");
            assertTrue(client.input(controls).isEmpty());
            assertEquals(1, nativeEntries.get());
            var nativeAck = client.nativeTransferAcknowledgement().orElseThrow();
            client.nativeTransferAcknowledgementSent(nativeAck);
            assertFalse(client.receive(stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 4, 2, 7)),
                    "a newer old-proposal STATE cannot replace the required resume APPLY");
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 4, 2,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            var sent = new AtomicReference<PlayerPhysicsProtocol.Packet>();
            client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), sent::set);
            assertTrue(sent.get().sequence() > lastInput, "new APPLY must not restart below prior INPUT sequence");
            // Re-entry also needs its own accepted restore and actually sent ACK.
            var secondRestore = nativeRestoreState(5, 2,
                    stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 5, 2, 9).snapshot().orElseThrow());
            installAndSendNativeRestore(client, installation, secondRestore);
            var secondNative = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 6, 2,
                    new PlayerPhysicsProtocol.NativeHandoff(2, 2, secondRestore.snapshot().orElseThrow().worldVelocity()));
            assertTrue(client.receiveNativeTransfer(secondNative, () -> !actor.isDead, nativeVelocityWriter(actor)));
            var resumed = stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 7, 3, 0);
            assertFalse(client.receive(new PlayerPhysicsProtocol.Packet(resumed.kind(), SCOPE, 7, 2,
                    Optional.empty(), resumed.snapshot(), Optional.empty())), "re-entering native cancels an in-flight resume");
            assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 8, 3,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), sent::set);
            assertTrue(installation.install(new PlayerPhysicsProtocol.Packet(resumed.kind(), SCOPE, 9, 3,
                    Optional.empty(), resumed.snapshot(), Optional.empty())));
            assertTrue(client.drivesMotion());
            assertEquals(1, retirements.get(), "native delegation never resurrects and retires Remote twice");
            assertFalse(client.receiveNativeTransfer(nativePacket, () -> !actor.isDead, nativeVelocityWriter(actor)));
        }
    }

    @Test void nativeCorrectionRejectsOldAndForeignStatesButKeepsTheSameOwner() {
        var writes = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> { writes.incrementAndGet(); return true; }, () -> { }, () -> { });
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        client.nativeCorrection(37);
        assertTrue(client.owns());
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 7)));
        var recovered = state(PlayerPhysicsProtocol.Kind.STATE, 3, 2, 6);
        var wrong = new PlayerPhysicsProtocol.Packet(recovered.kind(), recovered.scope(), recovered.sequence(),
                recovered.offerSequence(), recovered.application(), recovered.snapshot(), recovered.controls(), OptionalInt.of(36));
        assertFalse(client.receive(wrong));
        var matching = new PlayerPhysicsProtocol.Packet(recovered.kind(), recovered.scope(), recovered.sequence(),
                recovered.offerSequence(), recovered.application(), recovered.snapshot(), recovered.controls(), OptionalInt.of(37));
        assertEquals(matching, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(matching)));
        assertTrue(client.receive(matching));
        assertEquals(2, writes.get());
        assertFalse(client.receive(matching));
    }

    @Test void presentationFenceClearsOnlyAfterMatchingCorrectionIsSuccessfullyInstalled() {
        var installationAllowed = new AtomicBoolean(true);
        var throwDuringInstall = new AtomicBoolean(false);
        var failure = new IllegalStateException("corrected mirror install failed");
        var current = new AtomicReference<PlayerPhysicsClientSession>();
        var client = new PlayerPhysicsClientSession(snapshot -> {
            assertTrue(current.get().presentationSnapshot().isEmpty(),
                    "an applying state is never visible as a completed presentation");
            if (throwDuringInstall.get()) throw failure;
            return installationAllowed.get();
        }, () -> {}, () -> {});
        current.set(client);
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9)));
        assertSame(client.snapshot().orElseThrow(), client.presentationSnapshot().orElseThrow());

        client.nativeCorrection(42);
        assertTrue(client.owns(), "correction must not revoke protocol custody");
        assertTrue(client.drivesMotion(), "presentation gating must not change motion ownership");
        assertTrue(client.presentationSnapshot().isEmpty());

        var duplicate = withNativeId(state(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 9), 42);
        assertTrue(client.receive(duplicate));
        assertEquals(PlayerPhysicsClientSession.StateDisposition.RETRANSMISSION, client.lastStateDisposition());
        assertTrue(client.presentationSnapshot().isEmpty(), "a retransmission did not install corrected geometry");

        var corrected = withNativeId(state(PlayerPhysicsProtocol.Kind.STATE, 3, 2, 6), 42);
        installationAllowed.set(false);
        assertFalse(client.receive(corrected));
        assertTrue(client.presentationSnapshot().isEmpty(), "rejected installation leaves the fence pending");
        installationAllowed.set(true);
        throwDuringInstall.set(true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(corrected)));
        assertTrue(client.presentationSnapshot().isEmpty(), "thrown installation leaves the fence pending");

        throwDuringInstall.set(false);
        assertTrue(client.receive(corrected), "the exact failed correction can be retried");
        assertEquals(2, client.presentationSnapshot().orElseThrow().revision());
        assertFalse(client.receive(corrected), "the now-stale retransmission does not reapply the correction");
        var oldFence = withNativeId(state(PlayerPhysicsProtocol.Kind.STATE, 4, 3, 5), 41);
        assertFalse(client.receive(oldFence), "successful presentation recovery retains the protocol teleport ID");
        assertEquals(PlayerPhysicsClientSession.StateDisposition.TELEPORT_MISMATCH, client.lastStateDisposition());
        client.reset();
        assertTrue(client.presentationSnapshot().isEmpty(), "reset invalidates accepted presentation");
    }

    @Test void correctionBeforeApplyDoesNotPoisonTheNextProposal() {
        var client = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty()));
        client.nativeCorrection(37);
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 1, 2,
                Optional.empty(), Optional.empty(), Optional.empty()));
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), packet -> { });
        var owned = state(PlayerPhysicsProtocol.Kind.OWNED, 2, 0, 9);
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(owned.kind(), owned.scope(), owned.sequence(), 2,
                owned.application(), owned.snapshot(), owned.controls())));
        assertTrue(client.owns());
    }

    @Test void correctedStateCanRecoverLostOwnedWithoutReopeningLegacy() {
        var retirements = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> true, retirements::incrementAndGet, () -> { });
        prepare(client);
        client.nativeCorrection(37);
        var corrected = state(PlayerPhysicsProtocol.Kind.STATE, 3, 2, 6);
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(corrected.kind(), corrected.scope(), corrected.sequence(),
                corrected.offerSequence(), corrected.application(), corrected.snapshot(), corrected.controls(), OptionalInt.of(37))));
        assertEquals(1, retirements.get());
        assertTrue(client.owns());
    }

    @Test void failedInstallationKeepsLegacyThenSuccessfulInstallationRetiresEveryLateOperation() {
        AtomicReference<Vec3> motion = new AtomicReference<>(Vec3.ZERO);
        AtomicBoolean writable = new AtomicBoolean(false);
        var legacy = new RemoteBoostSession(packet -> {
            motion.set(packet.operation() == BoostProtocol.Operation.ADD ? motion.get().add(packet.velocity()) : packet.velocity());
            return true;
        });
        legacy.acceptOffer(BoostProtocol.offer(11, 0, 70, true));
        legacy.requestMode(true, 0);
        assertTrue(legacy.acceptState(legacyState(1, BoostProtocol.Operation.REPLACE), 0));
        var client = new PlayerPhysicsClientSession(snapshot -> {
            if (!writable.get()) return false;
            motion.set(new Vec3(snapshot.worldVelocity().x(), snapshot.worldVelocity().y(), snapshot.worldVelocity().z()));
            return true;
        }, legacy::retireForPhysics, () -> { });
        prepare(client);
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        assertFalse(client.owns());
        assertTrue(legacy.acceptState(legacyState(2, BoostProtocol.Operation.ADD), 0));
        assertEquals(new Vec3(4, 0, 0), motion.get());
        writable.set(true);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        assertTrue(client.owns());
        for (var operation : BoostProtocol.Operation.values())
            assertFalse(legacy.acceptState(legacyState(10 + operation.ordinal(), operation), 0));
        assertFalse(legacy.acceptOffer(BoostProtocol.offer(12, 0, 70, true)), "late legacy offer cannot resurrect retired writer");
        assertNull(legacy.nextFrame(0, 5, false));
        assertNull(legacy.requestMode(false, 0));
        assertFalse(legacy.requestedIntent(), "G key choice remains mutable after retirement");
        assertEquals(new Vec3(9, 0, 0), motion.get());
    }

    @Test void stateRecoversLostOwnedAndDuplicateRevisionDoesNotReapply() {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger retirements = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> { writes.incrementAndGet(); return true; }, retirements::incrementAndGet, () -> { });
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 7)), "unsolicited state cannot install");
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 2, 1, 7)));
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 3, 1, 7)));
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 4, 1, 8)), "same revision cannot claim different contents");
        assertEquals(1, writes.get());
        assertEquals(1, retirements.get());
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 5, 2, 6)));
        assertEquals(2, writes.get());
        assertEquals(1, retirements.get());
    }

    @Test void resetDuringApplicationCannotGrantOwnershipOrRetireLegacy() {
        AtomicReference<PlayerPhysicsClientSession> current = new AtomicReference<>();
        AtomicInteger retirements = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> { current.get().reset(); return true; }, retirements::incrementAndGet, () -> { });
        current.set(client);
        prepare(client);
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        assertFalse(client.owns());
        assertEquals(0, retirements.get());
    }

    @Test void newerProposalInvalidatesOldApplyAndInputContinuesItsSequence() {
        var client = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(client);
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 1, 2,
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 2, 0, 9)));
        assertFalse(client.owns());
        client.reset();
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5);
        assertEquals(2, client.input(controls).orElseThrow().sequence());
        assertEquals(3, client.input(controls).orElseThrow().sequence());
        client.reset();
        assertTrue(client.input(controls).isEmpty());
    }

    @Test void failedNewerAbsoluteStateAllowsExactRetryButNotAnOlderState() {
        AtomicBoolean writable = new AtomicBoolean(false);
        var client = new PlayerPhysicsClientSession(snapshot -> writable.get(), () -> { }, () -> { });
        prepare(client);
        var newest = state(PlayerPhysicsProtocol.Kind.STATE, 4, 3, 6);
        assertFalse(client.receive(newest));
        writable.set(true);
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        assertFalse(client.owns());
        assertTrue(client.receive(newest));
        assertEquals(3, client.snapshot().orElseThrow().revision());
    }

    @Test void preparedInstallationIsPureSessionBoundAndPublishesAfterRealMirrorInstall(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 901), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 902));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 903), actor.dimension, 1);
            var installCalls = new AtomicInteger();
            var legacyRetirements = new AtomicInteger();
            var nativeEntries = new AtomicInteger();
            var client = new PlayerPhysicsClientSession(snapshot -> {
                installCalls.incrementAndGet();
                return PlayerPhysicsClientMirror.install(actor, snapshot);
            }, legacyRetirements::incrementAndGet, nativeEntries::incrementAndGet);
            prepare(client, scope);
            var candidate = mirrorSnapshot(scope, 1, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    12, 70, 12, new Vec3d(0, 0, 0));
            var packet = statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, candidate, null);

            var first = client.prepareInstallation(packet);
            var duplicatePreparation = client.prepareInstallation(packet);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.INSTALL, first.disposition());
            var prepared = first.prepared().orElseThrow();
            var duplicateToken = duplicatePreparation.prepared().orElseThrow();
            assertSame(candidate, prepared.snapshot(), "the owner installs the authorized prepared geometry");
            assertTrue(client.installationCurrent(prepared));
            assertTrue(client.installationCurrent(duplicateToken), "preparation alone must not consume authority");
            assertFalse(client.owns());
            assertTrue(client.snapshot().isEmpty());
            assertEquals(0, installCalls.get());
            assertEquals(0, legacyRetirements.get());
            assertEquals(0, nativeEntries.get());

            assertTrue(client.beginPreparedInstallation(prepared));
            assertTrue(client.installationCurrent(prepared), "begin must not invalidate its own token");
            assertFalse(client.installationCurrent(duplicateToken), "only one install attempt may be active");
            var duringApply = client.prepareInstallation(packet);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.REJECTED, duringApply.disposition());
            assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.ATTEMPT_FENCED), duringApply.rejection());
            assertEquals(0, installCalls.get(), "typed begin does not invoke the legacy installer callback");
            assertTrue(PlayerPhysicsClientMirror.install(actor, candidate));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, candidate));
            assertFalse(client.owns(), "native actor installation is not protocol publication");
            assertTrue(client.publishInstallation(prepared));
            assertSame(candidate, client.snapshot().orElseThrow());
            assertTrue(client.owns());
            assertEquals(0, installCalls.get());

            var foreign = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
            prepare(foreign, scope);
            assertFalse(foreign.installationCurrent(prepared));
            assertFalse(foreign.publishInstallation(prepared));
            assertFalse(client.publishInstallation(prepared), "published tokens are single-use");
            assertEquals(0, legacyRetirements.get());
            assertEquals(0, nativeEntries.get());

            var abandoned = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
            prepare(abandoned, scope);
            var abandonedToken = abandoned.prepareInstallation(packet).prepared().orElseThrow();
            assertTrue(abandoned.beginPreparedInstallation(abandonedToken));
            assertTrue(abandoned.revokePreparedInstallation(abandonedToken));
            assertFalse(abandoned.installationCurrent(abandonedToken));
            assertFalse(abandoned.publishInstallation(abandonedToken), "revoke retires the token without restoring actor state");
            assertEquals(PlayerPhysicsClientSession.StateDisposition.INSTALL_REJECTED, abandoned.lastStateDisposition());
        }
    }

    @Test void typedReplacementFailureFencesQueriesUntilSuccessfulPublishAndExactRetry(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 911), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 912));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 913), actor.dimension, 1);
            var legacyWrites = new AtomicInteger();
            var poseWrites = new AtomicInteger();
            var freePoseWrites = new AtomicInteger();
            var retirements = new AtomicInteger();
            var client = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot),
                    retirements::incrementAndGet, () -> { });
            prepare(client, scope);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.X_POS, .3F, .9F, .81F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            assertEquals(1, retirements.get());

            var failedReplacement = mirrorSnapshot(scope, 2, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    20, 30, 40, new Vec3d(4, 5, 6));
            var failedPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, failedReplacement, null);
            var preparation = client.prepareInstallation(failedPacket);
            var prepared = preparation.prepared().orElseThrow();
            assertTrue(client.beginPreparedInstallation(prepared));
            assertTrue(client.installationFenceActive(), "replacement begin immediately fences old ownership");
            assertFalse(client.drivesMotion());
            assertTrue(client.presentationSnapshot().isEmpty());
            client.runLegacyMotion(actor, legacyWrites::incrementAndGet);
            client.runNativePose(actor, poseWrites::incrementAndGet);
            client.runFreePosePublication(actor, freePoseWrites::incrementAndGet);
            assertEquals(0, legacyWrites.get());
            assertEquals(0, poseWrites.get());
            assertEquals(0, freePoseWrites.get());

            assertTrue(PlayerPhysicsClientMirror.install(actor, failedReplacement),
                    "the owner reports failure after the actual actor geometry has already changed");
            var originalFailure = new IllegalStateException("owner rejected post-write replacement");
            assertTrue(client.failPreparedInstallation(prepared, originalFailure));
            assertEquals(PlayerPhysicsClientSession.StateDisposition.APPLICATION_THREW, client.lastStateDisposition());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, failedReplacement));
            assertSame(accepted, client.snapshot().orElseThrow(), "failure cannot publish the replacement snapshot");
            assertTrue(client.installationFenceActive());

            var oldGeometry = statePacket(PlayerPhysicsProtocol.Kind.STATE, 3, accepted, null);
            var rejectedOld = client.prepareInstallation(oldGeometry);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.REJECTED, rejectedOld.disposition());
            assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.ATTEMPT_FENCED), rejectedOld.rejection());
            var sameRevisionDifferentPacket = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2,
                    mirrorSnapshot(scope, 2, CardinalFrame.Z_POS, .3F, .9F, .81F, 21, 31, 41, new Vec3d(0, 0, 0)), null);
            assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.ATTEMPT_FENCED),
                    client.prepareInstallation(sameRevisionDifferentPacket).rejection());
            assertTrue(client.installationFenceActive(), "rejected preparations leave the failure fence intact");

            var exactRetry = client.prepareInstallation(failedPacket);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.INSTALL, exactRetry.disposition());
            var retryToken = exactRetry.prepared().orElseThrow();
            assertTrue(client.beginPreparedInstallation(retryToken));
            assertTrue(PlayerPhysicsClientMirror.install(actor, failedReplacement));
            assertTrue(client.installationFenceActive(), "native success alone does not clear Session authority fence");
            assertTrue(client.publishInstallation(retryToken));
            assertFalse(client.installationFenceActive());
            assertTrue(client.drivesMotion());
            assertSame(failedReplacement, client.snapshot().orElseThrow());
            assertEquals(1, retirements.get(), "typed publish has no legacy callback");
        }
    }

    @Test void preparedRetransmissionDoesNotAdvanceSequenceReceiptOrAckUntilPublish(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 921), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 922));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 923), actor.dimension, 1);
            var installs = new AtomicInteger();
            var retirements = new AtomicInteger();
            var client = new PlayerPhysicsClientSession(snapshot -> {
                installs.incrementAndGet();
                return PlayerPhysicsClientMirror.install(actor, snapshot);
            }, retirements::incrementAndGet, () -> { });
            prepare(client, scope);
            var accepted = mirrorSnapshot(scope, 1, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                    8, 70, 8, new Vec3d(0, 0, 0));
            assertTrue(client.receive(statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null)));
            long requestId = client.requestBodyReferenceUnlock();
            var handoff = new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var retransmission = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2, accepted, handoff);

            var before = client.input(idleControls()).orElseThrow();
            assertTrue(before.bodyReferenceAcknowledgement().isEmpty());
            assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, client.bodyReferenceStatus().status());
            var first = client.prepareInstallation(retransmission);
            var second = client.prepareInstallation(retransmission);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION, first.disposition());
            assertTrue(client.installationCurrent(first.prepared().orElseThrow()));
            assertTrue(client.installationCurrent(second.prepared().orElseThrow()));
            assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, client.bodyReferenceStatus().status());
            var duringPrepare = client.input(idleControls()).orElseThrow();
            assertEquals(before.sequence() + 1, duringPrepare.sequence());
            assertTrue(duringPrepare.bodyReferenceAcknowledgement().isEmpty(), "prepare must not publish the receipt ACK");
            assertEquals(1, installs.get());
            assertEquals(1, retirements.get());
            assertFalse(client.publishInstallation(first.prepared().orElseThrow()),
                    "an intervening input sequence change retires the old preparation");

            var currentRetransmission = client.prepareInstallation(retransmission);
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION,
                    currentRetransmission.disposition());
            assertTrue(client.publishInstallation(currentRetransmission.prepared().orElseThrow()));
            assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.UNLOCKED, client.bodyReferenceStatus().status());
            assertEquals(1, installs.get(), "retransmission publication performs no absolute installation");
            assertEquals(1, retirements.get(), "retransmission publication does not repeat legacy retirement");
            assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.STALE_SEQUENCE),
                    client.prepareInstallation(retransmission).rejection());
            assertTrue(client.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "a Session-only retransmission publish still has no completed client install proof");
        }
    }

    @Test void preparationInvalidatesOnResetOfferCorrectionApplyAndBodyReferenceChanges(@TempDir Path directory) {
        var resetClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(resetClient);
        var resetToken = resetClient.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9))
                .prepared().orElseThrow();
        resetClient.reset();
        assertFalse(resetClient.installationCurrent(resetToken));
        assertFalse(resetClient.publishInstallation(resetToken));

        var offerClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(offerClient);
        var offerToken = offerClient.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9))
                .prepared().orElseThrow();
        assertTrue(offerClient.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 1, 2,
                Optional.empty(), Optional.empty(), Optional.empty())));
        assertFalse(offerClient.installationCurrent(offerToken));

        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 1821), true)) {
            var actor = fixture.serverPlayer(SCOPE.playerId());
            var nativeClient = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot), () -> { }, () -> { });
            prepare(nativeClient);
            var installation = new PlayerPhysicsClientInstallationTestSupport(nativeClient, actor);
            var restore = nativeRestoreState(1, 1, stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9).snapshot().orElseThrow());
            installAndSendNativeRestore(nativeClient, installation, restore);
            var nativeToken = nativeClient.prepareInstallation(nativeRestoreState(2, 1, restore.snapshot().orElseThrow()))
                    .prepared().orElseThrow();
            assertTrue(nativeClient.installationCurrent(nativeToken));
            var nativePacket = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 3, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(1, 1, restore.snapshot().orElseThrow().worldVelocity()));
            assertTrue(nativeClient.receiveNativeTransfer(nativePacket, () -> !actor.isDead, nativeVelocityWriter(actor)));
            assertFalse(nativeClient.installationCurrent(nativeToken), "accepted NATIVE delegation changes session authority");
        }

        var correctionClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(correctionClient);
        var correctionToken = correctionClient.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9))
                .prepared().orElseThrow();
        correctionClient.nativeCorrection(17);
        assertFalse(correctionClient.installationCurrent(correctionToken));
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.TELEPORT_MISMATCH),
                correctionClient.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9)).rejection());

        var applyClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(applyClient);
        var applyToken = applyClient.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9))
                .prepared().orElseThrow();
        var emitted = new AtomicReference<PlayerPhysicsProtocol.Packet>();
        applyClient.prepare(new CPacketPlayer.Position(1, 2, 3, false), new Vec3d(1, 2, 3), 0, 0,
                false, Optional.empty(), emitted::set);
        assertEquals(PlayerPhysicsProtocol.Kind.APPLY, emitted.get().kind());
        assertFalse(applyClient.installationCurrent(applyToken), "a submitted APPLY changes the proposal generation");

        var bodyClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(bodyClient);
        var accepted = stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9);
        assertTrue(bodyClient.receive(accepted));
        var replacement = stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 2, 2, 10);
        var bodyToken = bodyClient.prepareInstallation(replacement).prepared().orElseThrow();
        bodyClient.requestBodyReferenceUnlock();
        assertFalse(bodyClient.installationCurrent(bodyToken), "a body-reference qualification change retires the token");
    }

    @Test void rejectedPreparationsPreserveCurrentTokenAndCoverR4AdmissionReasons(@TempDir Path directory) {
        var client = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        var notSubmitted = client.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9));
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.PROPOSAL_MISMATCH), notSubmitted.rejection());
        prepare(client);
        var validPacket = stateWithContacts(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9);
        var valid = client.prepareInstallation(validPacket).prepared().orElseThrow();

        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.NOT_STATE), client.prepareInstallation(
                new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 0, 1,
                        Optional.empty(), Optional.empty(), Optional.empty())).rejection());
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.PROPOSAL_MISMATCH),
                client.prepareInstallation(statePacket(PlayerPhysicsProtocol.Kind.STATE, 1, 2,
                        validPacket.snapshot().orElseThrow(), null)).rejection());
        var foreignScope = new PlayerPhysicalSnapshot.Scope(SCOPE.playerId(), new UUID(0, 999), SCOPE.dimension(), 2);
        var foreignSnapshot = mirrorSnapshot(foreignScope, 1, CardinalFrame.Y_POS, .6F, 1.8F, 1.62F,
                2, 5, 3, new Vec3d(0, 0, 0));
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.PROPOSAL_MISMATCH),
                client.prepareInstallation(statePacket(PlayerPhysicsProtocol.Kind.STATE, 1, foreignSnapshot, null)).rejection());
        assertTrue(client.installationCurrent(valid), "rejected prepare calls must have zero effects on another token");

        assertTrue(client.receive(validPacket));
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.STALE_SEQUENCE),
                client.prepareInstallation(validPacket).rejection());
        var revisionConflict = stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 2, 0, 8);
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.REVISION_CONFLICT),
                client.prepareInstallation(revisionConflict).rejection());

        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 1831), true)) {
            var actor = fixture.serverPlayer(SCOPE.playerId());
            var delegated = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot), () -> { }, () -> { });
            prepare(delegated);
            var installation = new PlayerPhysicsClientInstallationTestSupport(delegated, actor);
            var restore = nativeRestoreState(1, 1, stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9).snapshot().orElseThrow());
            installAndSendNativeRestore(delegated, installation, restore);
            var nativePacket = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 2, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(1, 1, restore.snapshot().orElseThrow().worldVelocity()));
            assertTrue(delegated.receiveNativeTransfer(nativePacket, () -> !actor.isDead, nativeVelocityWriter(actor)));
            assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.NATIVE_DELEGATED),
                    delegated.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 3, 2, 9)).rejection());
        }

        var attempted = new PlayerPhysicsClientSession(snapshot -> false, () -> { }, () -> { });
        prepare(attempted);
        assertFalse(attempted.receive(stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 1, 1, 9)));
        assertEquals(Optional.of(PlayerPhysicsClientSession.StateDisposition.ATTEMPT_FENCED),
                attempted.prepareInstallation(stateWithContacts(PlayerPhysicsProtocol.Kind.STATE, 1, 2, 9)).rejection());
    }

    @Test void revokeBeforeBeginInvalidatesOnlyTheExactInstallToken() {
        var installCalls = new AtomicInteger();
        var legacyRetirements = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> { installCalls.incrementAndGet(); return true; },
                legacyRetirements::incrementAndGet, () -> { });
        prepare(client);
        var candidate = state(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9).snapshot().orElseThrow();
        var packet = statePacket(PlayerPhysicsProtocol.Kind.OWNED, 1, candidate, null);
        var revoked = client.prepareInstallation(packet).prepared().orElseThrow();
        var independent = client.prepareInstallation(packet).prepared().orElseThrow();
        assertTrue(client.installationCurrent(revoked));
        assertTrue(client.installationCurrent(independent));
        var foreignClient = new PlayerPhysicsClientSession(snapshot -> true, () -> { }, () -> { });
        prepare(foreignClient);
        var foreignToken = foreignClient.prepareInstallation(packet).prepared().orElseThrow();
        assertFalse(foreignClient.revokePreparedInstallation(revoked), "a different Session cannot revoke this token");
        assertFalse(client.revokePreparedInstallation(foreignToken), "this Session cannot revoke a foreign token");
        assertTrue(client.installationCurrent(revoked));
        assertTrue(foreignClient.installationCurrent(foreignToken));

        assertTrue(client.revokePreparedInstallation(revoked));
        assertFalse(client.installationCurrent(revoked));
        assertFalse(client.beginPreparedInstallation(revoked));
        assertFalse(client.publishInstallation(revoked));
        assertFalse(client.owns());
        assertTrue(client.snapshot().isEmpty());
        assertFalse(client.installationFenceActive());
        assertTrue(client.installationCurrent(independent), "revocation is token-local and leaves Session authority unchanged");

        assertTrue(client.beginPreparedInstallation(independent));
        assertFalse(client.revokePreparedInstallation(revoked), "repeating an old revoke cannot cancel a different active token");
        assertTrue(client.installationCurrent(independent));
        assertTrue(client.publishInstallation(independent));
        assertSame(candidate, client.snapshot().orElseThrow());
        assertTrue(client.owns());
        assertEquals(0, installCalls.get(), "typed begin/publish do not call compatibility installers");
        assertEquals(0, legacyRetirements.get(), "typed publish does not call compatibility retirement");
    }

    @Test void revokeBeforePublishInvalidatesRetransmissionWithoutAdvancingReceiptOrAck() {
        var installCalls = new AtomicInteger();
        var legacyRetirements = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> { installCalls.incrementAndGet(); return true; },
                legacyRetirements::incrementAndGet, () -> { });
        prepare(client);
        var accepted = state(PlayerPhysicsProtocol.Kind.OWNED, 1, 1, 9);
        assertTrue(client.receive(accepted));
        long requestId = client.requestBodyReferenceUnlock();
        var handoff = new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,
                PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        var retransmission = statePacket(PlayerPhysicsProtocol.Kind.STATE, 2,
                accepted.snapshot().orElseThrow(), handoff);
        var beforeReceipt = client.bodyReferenceStatus();
        assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.PENDING, beforeReceipt.status());
        assertTrue(client.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty());

        var preparation = client.prepareInstallation(retransmission);
        assertEquals(PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION, preparation.disposition());
        var prepared = preparation.prepared().orElseThrow();
        var independent = client.prepareInstallation(retransmission).prepared().orElseThrow();
        assertTrue(client.installationCurrent(independent));
        assertTrue(client.revokePreparedInstallation(prepared));
        assertFalse(client.installationCurrent(prepared));
        assertFalse(client.publishInstallation(prepared));
        assertTrue(client.installationCurrent(independent), "token-local revoke leaves the other preparation current");
        assertSame(accepted.snapshot().orElseThrow(), client.snapshot().orElseThrow());
        assertTrue(client.owns());
        assertTrue(client.drivesMotion());
        assertFalse(client.installationFenceActive());
        assertEquals(beforeReceipt, client.bodyReferenceStatus(), "revocation cannot publish the receipt");
        assertTrue(client.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                "revocation cannot publish the precomputed receipt acknowledgement");
        assertEquals(beforeReceipt, client.bodyReferenceStatus());
        assertEquals(1, installCalls.get(), "retransmission revoke performs no absolute installation");
        assertEquals(1, legacyRetirements.get(), "retransmission revoke does not repeat compatibility retirement");

        var stillUnconsumed = client.prepareInstallation(retransmission);
        assertEquals(PlayerPhysicsClientSession.InstallationDisposition.RETRANSMISSION, stillUnconsumed.disposition(),
                "incoming sequence remains unchanged until a current retransmission is published");
        assertTrue(client.installationCurrent(stillUnconsumed.prepared().orElseThrow()));
    }

    private static PlayerPhysicsProtocol.Packet nativeRestoreState(long sequence, long proposal, PlayerPhysicalSnapshot snapshot) {
        var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        var reference = new PlayerBodyReference.State(Optional.empty(),
                new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE, CardinalFrame.Y_POS, new Vec3d(1, 0, 0)),
                PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING, Optional.empty(), Optional.empty());
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, snapshot.scope(), sequence, proposal,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.of(receipt),
                Optional.empty(), Optional.of(reference));
    }

    private static void installAndSendNativeRestore(PlayerPhysicsClientSession client,
            PlayerPhysicsClientInstallationTestSupport installation, PlayerPhysicsProtocol.Packet restore) {
        assertTrue(installation.install(restore), "the actual Mirror/Presentation installation must complete");
        var acknowledgement = client.input(idleControls()).orElseThrow();
        assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(restore.sequence(), restore.snapshot().orElseThrow().revision()),
                acknowledgement.bodyReferenceAcknowledgement().orElseThrow());
        // Session-unit transport returns this exact packet, then the real send-success seam records it.
        var sent = new AtomicReference<PlayerPhysicsProtocol.Packet>();
        Consumer<PlayerPhysicsProtocol.Packet> transport = sent::set;
        transport.accept(acknowledgement);
        assertSame(acknowledgement, sent.get());
        installation.inputSendSucceeded(sent.get());
    }

    private static BiConsumer<Vec3d, BooleanSupplier> nativeVelocityWriter(EntityPlayer actor) {
        return (velocity, current) -> {
            assertTrue(current.getAsBoolean()); actor.motionX = velocity.x() * .05;
            assertTrue(current.getAsBoolean()); actor.motionY = velocity.y() * .05;
            assertTrue(current.getAsBoolean()); actor.motionZ = velocity.z() * .05;
            assertTrue(current.getAsBoolean());
        };
    }

    private static void prepare(PlayerPhysicsClientSession client) {
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty()));
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), packet -> { });
    }

    private static void prepare(PlayerPhysicsClientSession client, PlayerPhysicalSnapshot.Scope scope) {
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty()));
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), packet -> { });
    }

    private static PlayerPhysicsProtocol.Packet statePacket(PlayerPhysicsProtocol.Kind kind, long sequence,
            PlayerPhysicalSnapshot snapshot, PlayerPhysicsProtocol.BodyReferenceReceipt receipt) {
        return statePacket(kind, sequence, 1, snapshot, receipt);
    }

    private static PlayerPhysicsProtocol.Packet statePacket(PlayerPhysicsProtocol.Kind kind, long sequence,
            long offerSequence, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicsProtocol.BodyReferenceReceipt receipt) {
        return new PlayerPhysicsProtocol.Packet(kind, snapshot.scope(), sequence, offerSequence, Optional.empty(),
                Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.ofNullable(receipt),
                Optional.empty(), Optional.empty());
    }

    private static PlayerPhysicalSnapshot mirrorSnapshot(PlayerPhysicalSnapshot.Scope scope, long revision,
            CardinalFrame frame, float width, float height, float eyeHeight, double x, double y, double z,
            Vec3d velocity) {
        var shape = new PlayerBodyShape.Cardinal(frame,
                new PlayerBodyShape.Dimensions(width, height, eyeHeight));
        var placement = new PlayerBodyPlacement(new Vec3d(x, y, z), frame.attitude(), shape);
        return new PlayerPhysicalSnapshot(scope, revision, placement, velocity,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }

    /** These native-transfer client fixtures require the real Mirror contact payload. */
    private static PlayerPhysicsProtocol.Packet stateWithContacts(PlayerPhysicsProtocol.Kind kind,
            long sequence, long revision, double speed) {
        var packet = state(kind, sequence, revision, speed);
        var source = packet.snapshot().orElseThrow();
        var snapshot = new PlayerPhysicalSnapshot(source.scope(), source.revision(), source.placement(),
                source.worldVelocity(), Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
        return new PlayerPhysicsProtocol.Packet(kind, packet.scope(), packet.sequence(), packet.offerSequence(),
                packet.application(), Optional.of(snapshot), packet.controls());
    }

    private static PlayerPhysicsProtocol.Packet state(PlayerPhysicsProtocol.Kind kind, long sequence, long revision, double speed) {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var snapshot = new PlayerPhysicalSnapshot(SCOPE, revision,
                new PlayerBodyPlacement(new Vec3d(2, 5, 3), CardinalFrame.Y_POS.attitude(), shape), new Vec3d(speed, 0, 0));
        return new PlayerPhysicsProtocol.Packet(kind, SCOPE, sequence, 1, Optional.empty(), Optional.of(snapshot), Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet withNativeId(PlayerPhysicsProtocol.Packet packet, int teleportId) {
        return new PlayerPhysicsProtocol.Packet(packet.kind(), packet.scope(), packet.sequence(), packet.offerSequence(),
                packet.application(), packet.snapshot(), packet.controls(), OptionalInt.of(teleportId));
    }

    private static PlayerPhysicalSnapshot bodySnapshot(long revision, CardinalFrame frame) {
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return new PlayerPhysicalSnapshot(SCOPE, revision,
                new PlayerBodyPlacement(new Vec3d(2, 5, 3), frame.attitude(), shape), new Vec3d(0, 0, 0));
    }

    private static PlayerPhysicsProtocol.Packet bodyState(long sequence, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicsProtocol.BodyReferenceReceipt receipt) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, sequence, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(),
                Optional.of(receipt), Optional.empty());
    }

    private static PlayerPhysicsProtocol.Packet bodyStateWithReference(long sequence, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicsProtocol.BodyReferenceReceipt receipt, PlayerBodyReference.State reference) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, SCOPE, sequence, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(),
                Optional.of(receipt), Optional.empty(), Optional.of(reference));
    }

    private static PlayerPhysicsProtocol.Controls idleControls() {
        return new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5);
    }

    private static PlayerPhysicsProtocol.Controls keyboardControls(PlayerPhysicsClientSession client, float yaw,
            double forward, double strafe, Vec3 look, boolean jump, boolean sneak, boolean sprint, boolean boost) {
        var accepted = client.presentationSnapshot().flatMap(client::acceptedEffectiveBodyReference);
        return client.keyboardControls(yaw, forward, strafe, look, jump, sneak, sprint, boost, accepted);
    }

    private static BoostProtocol.Packet legacyState(long serial, BoostProtocol.Operation operation) {
        return BoostProtocol.state(11, 1, serial, 0, BoostProtocol.Mode.ACTIVE, 5, 70,
                operation, new Vec3(2, 0, 0), BoostProtocol.Reason.NONE);
    }
}
