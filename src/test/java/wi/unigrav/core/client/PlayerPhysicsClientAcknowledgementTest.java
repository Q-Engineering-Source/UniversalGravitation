package wi.unigrav.core.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

final class PlayerPhysicsClientAcknowledgementTest {
    @Test void healthyDelegatedResumeQualificationRejectsReplaysForeignScopesAndPendingCorrection(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 3131), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 3132));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 3133), 0, 1);
            var session = delegatedCorrectionSession(scope, actor);
            var resume = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 7, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertTrue(session.allowsHealthyDelegatedResumeOffer(resume));
            assertFalse(session.allowsHealthyDelegatedResumeOffer(new PlayerPhysicsProtocol.Packet(
                    PlayerPhysicsProtocol.Kind.OFFER, scope, 7, 1, Optional.empty(), Optional.empty(), Optional.empty())));
            assertFalse(session.allowsHealthyDelegatedResumeOffer(new PlayerPhysicsProtocol.Packet(
                    PlayerPhysicsProtocol.Kind.OFFER, scope, 5, 2, Optional.empty(), Optional.empty(), Optional.empty())));
            var foreign = new PlayerPhysicalSnapshot.Scope(scope.playerId(), new UUID(0, 3134), 0, 1);
            assertFalse(session.allowsHealthyDelegatedResumeOffer(new PlayerPhysicsProtocol.Packet(
                    PlayerPhysicsProtocol.Kind.OFFER, foreign, 7, 2, Optional.empty(), Optional.empty(), Optional.empty())));
            var later = new PlayerPhysicalSnapshot.Scope(scope.playerId(), scope.sessionId(), 0, 2);
            assertFalse(session.allowsHealthyDelegatedResumeOffer(new PlayerPhysicsProtocol.Packet(
                    PlayerPhysicsProtocol.Kind.OFFER, later, 7, 2, Optional.empty(), Optional.empty(), Optional.empty())));
            assertFalse(preparedSession(scope).allowsHealthyDelegatedResumeOffer(resume), "ordinary negotiation has no delegated exception");
            session.nativeCorrection(73);
            assertThrows(IllegalStateException.class, session::correctionPredictionIdentity,
                    "delegated custody is not a normal installed correction owner");
            assertFalse(session.allowsHealthyDelegatedResumeOffer(resume), "pending native correction still fences a higher proposal");
        }
    }

    @Test void delegatedNativeCorrectionKeepsSubmittedIdentityUntilCompleteAndAllowsHigherProposal(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 3121), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 3122));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 3123), 0, 1);
            var session = delegatedCorrectionSession(scope, actor);
            var cue = session.nativeCorrectionCue();
            session.nativeCorrection(73);
            var application = session.prepareNativeCorrectionApplication(cue, 73);
            assertNotNull(application);
            application.positionApplied = true;
            application.confirmSent = true;
            var position = new CPacketPlayer.PositionRotation(actor.posX, actor.getEntityBoundingBox().minY,
                    actor.posZ, actor.rotationYaw, actor.rotationPitch, actor.onGround);
            var emitted = new ArrayList<PlayerPhysicsProtocol.Packet>();
            assertSame(position, session.prepare(position, new Vec3d(actor.posX, actor.posY, actor.posZ),
                    actor.rotationYaw, actor.rotationPitch, false, Optional.empty(), emitted::add));
            assertTrue(emitted.isEmpty(), "native correction must not resubmit the already delegated proposal");
            assertTrue(application.current(), "the exact submitted identity remains part of the strict RETURN proof");
            application.positionSent = true;
            session.nativeCorrectionApplicationReturned(application);
            assertTrue(session.receiveNativeCorrectionComplete(correctionComplete(scope, 6, 9, 73), () -> true));
            var acknowledgement = session.nativeTransferAcknowledgement().orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(9, 73, 6),
                    acknowledgement.nativeTransfer().orElseThrow());
            session.nativeTransferAcknowledgementSent(acknowledgement);
            assertFalse(session.drivesMotion(), "completion does not mint a new owned writer");

            var resume = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 7, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertTrue(session.receive(resume), "a real accepted higher proposal can negotiate same-scope resume");
            assertSame(position, session.prepare(position, new Vec3d(actor.posX, actor.posY, actor.posZ),
                    actor.rotationYaw, actor.rotationPitch, false, Optional.empty(), emitted::add));
            assertEquals(1, emitted.size());
            assertEquals(PlayerPhysicsProtocol.Kind.APPLY, emitted.getFirst().kind());
            assertEquals(scope, emitted.getFirst().scope()); assertEquals(2, emitted.getFirst().offerSequence());
            session.prepare(position, new Vec3d(actor.posX, actor.posY, actor.posZ), actor.rotationYaw,
                    actor.rotationPitch, false, Optional.empty(), emitted::add);
            assertEquals(2, emitted.size(), "the legitimate pending resume keeps its existing APPLY retry behavior");
            assertTrue(emitted.getLast().sequence() > emitted.getFirst().sequence());
            var resumed = cardinalSnapshot(scope, 5, CardinalFrame.Y_POS, 9, 70, 8);
            var owned = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, scope, 8, 2,
                    Optional.empty(), Optional.of(resumed), Optional.empty());
            assertNotNull(install(session, owned, new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> { }));
            assertTrue(session.drivesMotion(), "the higher proposal completes the real Mirror/Presentation/Session install");
            assertSame(resumed, session.snapshot().orElseThrow());
            var ordinary = session.input(idleControls()).orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(8, 5),
                    ordinary.bodyReferenceAcknowledgement().orElseThrow());
            session.inputSendSucceeded(ordinary);
        }
    }

    @Test void nativeRestoreInputRetainsPendingAndSentInstallationAcrossVisualReset(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 3101), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 3102));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 3103), 0, 1);
            var session = preparedSession(scope);
            var presentation = new PlayerPhysicsClientPresentation();
            var owner = new Object();
            var canonical = cardinalSnapshot(scope, 4, CardinalFrame.Y_POS, 8, 70, 8);
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var packet = state(PlayerPhysicsProtocol.Kind.STATE, 3, canonical, receipt, null);
            var stamp = install(session, packet, owner, actor, presentation, () -> { });
            assertNotNull(stamp);
            var pending = stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> true);
            assertNotNull(pending); assertTrue(pending.pending());
            var retired = new AtomicInteger();
            assertTrue(PlayerPhysicsClient.resetNativeRestorePresentation(pending, presentation, retired::incrementAndGet));
            var input = session.input(idleControls()).orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(3, 4), input.bodyReferenceAcknowledgement().orElseThrow());
            assertTrue(pending.current(), "without successful submission the exact ACK remains pending");
            session.inputSendSucceeded(input); // The real network boundary is covered by strictsleep1, not this local test.
            assertFalse(pending.current());
            var sent = stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> true);
            assertNotNull(sent); assertFalse(sent.pending());
            assertTrue(PlayerPhysicsClient.resetNativeRestorePresentation(sent, presentation, retired::incrementAndGet),
                    "the next native END must retain the sent restore backing while awaiting base");
            assertEquals(2, retired.get());
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(), "ACK is consumed exactly once");
            var writes = new AtomicInteger();
            var base = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, scope, 5, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(41, 4, new Vec3d(13, -2, .25)));
            assertTrue(session.receiveNativeTransfer(base, () -> true, (velocity, current) -> {
                assertTrue(current.getAsBoolean()); writes.incrementAndGet();
                actor.motionX = velocity.x()/20; actor.motionY = velocity.y()/20; actor.motionZ = velocity.z()/20;
            }));
            assertEquals(1, writes.get()); assertEquals(.65, actor.motionX);
            assertFalse(sent.current());
            assertNull(stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> true));
        }
    }

    @Test void nativeRestoreInputRejectsOrdinaryForeignGeometryAndReentrantLifetime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 3111), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 3112));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 3113), 0, 1);
            var canonical = cardinalSnapshot(scope, 4, CardinalFrame.Y_POS, 8, 70, 8);
            var ordinarySession = preparedSession(scope);
            var ordinaryOwner = new Object();
            var ordinary = install(ordinarySession, state(PlayerPhysicsProtocol.Kind.OWNED, 1, canonical, null, null),
                    ordinaryOwner, actor, new PlayerPhysicsClientPresentation(), () -> { });
            assertNotNull(ordinary);
            assertNull(ordinary.nativeRestoreInputLease(ordinaryOwner, ordinarySession, actor, actor.world, () -> true));
            var session = preparedSession(scope);
            var owner = new Object();
            var presentation = new PlayerPhysicsClientPresentation();
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var stamp = install(session, state(PlayerPhysicsProtocol.Kind.STATE, 3, canonical, receipt, null),
                    owner, actor, presentation, () -> { });
            assertNotNull(stamp);
            assertNull(stamp.nativeRestoreInputLease(new Object(), session, actor, actor.world, () -> true));
            var failure = new IllegalStateException("restore current original");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> { throw failure; })));
            var pending = stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> true);
            assertNotNull(pending);
            var bounds = actor.getEntityBoundingBox();
            actor.setEntityBoundingBox(bounds.offset(.125, 0, 0));
            assertFalse(pending.current(), "accepted mirror geometry is required independently of the ACK fields");
            actor.setEntityBoundingBox(bounds);
            assertTrue(pending.current());
            var visualResets = new AtomicInteger();
            assertFalse(PlayerPhysicsClient.resetNativeRestorePresentation(pending, presentation, () -> {
                visualResets.incrementAndGet(); session.nativeCorrection(73);
            }));
            assertEquals(1, visualResets.get());
            assertFalse(pending.current());
            assertNull(stamp.nativeRestoreInputLease(owner, session, actor, actor.world, () -> true));
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "a new TP cannot reuse the old restore ACK");
        }
    }

    @Test void delegatedCorrectionRequiresTheActualApplyReceiptAndOnlyClearsItsFence(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2961), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2962));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2963), 0, 1);
            var session = delegatedCorrectionSession(scope, actor);
            var canonical = session.snapshot().orElseThrow();
            actor.motionX = 13.0125; actor.motionY = -2.75; actor.fallDistance = 37;
            var bounds = actor.getEntityBoundingBox();
            var cue = session.nativeCorrectionCue();
            session.nativeCorrection(73);
            var control = correctionComplete(scope, 6, 9, 73);
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true), "HEAD cue alone is not APPLY");
            var apply = session.prepareNativeCorrectionApplication(cue, 73);
            assertNotNull(apply);
            session.nativeCorrectionApplicationReturned(apply);
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true), "early RETURN without the actual setter/replies is not APPLY");
            apply.positionApplied = true; apply.confirmSent = true;
            session.nativeCorrectionApplicationReturned(apply);
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true), "a failed/missing native position reply cannot complete");
            apply.positionSent = true;
            session.nativeCorrectionApplicationReturned(apply);
            assertTrue(session.receiveNativeCorrectionComplete(control, () -> true));
            var acknowledgement = session.nativeTransferAcknowledgement().orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_ACK, acknowledgement.kind());
            assertEquals(new PlayerPhysicsProtocol.NativeCorrectionAcknowledgement(9, 73, 6), acknowledgement.nativeTransfer().orElseThrow());
            session.nativeTransferAcknowledgementSent(acknowledgement);
            assertTrue(session.receiveNativeCorrectionComplete(control, () -> true), "exact duplicate only reACKs");
            assertTrue(session.nativeTransferAcknowledgement().isPresent());
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(scope, 7, 9, 73), () -> true), "another wire identity is not the retained outbox");
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(scope, 6, 10, 73), () -> true));
            assertSame(canonical, session.snapshot().orElseThrow());
            assertSame(bounds, actor.getEntityBoundingBox());
            assertEquals(13.0125, actor.motionX); assertEquals(-2.75, actor.motionY); assertEquals(37, actor.fallDistance);
            assertTrue(session.owns()); assertFalse(session.drivesMotion());
            assertTrue(session.input(idleControls()).isEmpty(), "control does not restore the old UG input writer");
            assertNull(session.prepareNativeCorrectionApplication(73), "completion cleared pending without a new transfer");
            var nextCue = session.nativeCorrectionCue();
            session.nativeCorrection(74);
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true));
            assertFalse(apply.current(), "new TP retires even an old completed APPLY receipt");
            var next = session.prepareNativeCorrectionApplication(nextCue, 74);
            assertNotNull(next);
            next.positionApplied = true; next.confirmSent = true; next.positionSent = true;
            session.nativeCorrectionApplicationReturned(next);
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(scope, 8, 9, 74), () -> true), "server correction identity must advance");
            assertTrue(session.receiveNativeCorrectionComplete(correctionComplete(scope, 8, 10, 74), () -> true));
        }
    }

    @Test void delegatedCorrectionCallbackCannotAcknowledgeSuccessorOrSwallowFailure(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2971), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2972));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2973), 0, 1);
            var session = delegatedCorrectionSession(scope, actor);
            session.nativeCorrection(73);
            var apply = session.prepareNativeCorrectionApplication(73);
            apply.positionApplied = true; apply.confirmSent = true; apply.positionSent = true;
            session.nativeCorrectionApplicationReturned(apply);
            var control = correctionComplete(scope, 6, 9, 73);
            var original = new IllegalStateException("native-correction-original-X");
            assertSame(original, assertThrows(IllegalStateException.class,
                    () -> session.receiveNativeCorrectionComplete(control, () -> { throw original; })));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> { session.nativeCorrection(74); return true; }));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertNotNull(session.prepareNativeCorrectionApplication(74), "old callback did not clear successor pending");
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true));
            var pending = session.prepareNativeCorrectionApplication(74);
            pending.positionApplied = true; pending.confirmSent = true; pending.positionSent = true;
            session.nativeCorrectionApplicationReturned(pending);
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(scope, 7, 10, 74), () -> {
                actor.setHealth(0); return actor.isEntityAlive() && !actor.isDead;
            }));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
        }
    }

    @Test void correctionCompletionRejectsOwnedForeignProposalAndOldApply(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2981), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2982));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2983), 0, 1);
            var ordinary = preparedSession(scope);
            var control = correctionComplete(scope, 6, 9, 73);
            assertFalse(ordinary.receiveNativeCorrectionComplete(control, () -> true));
            assertNotNull(install(ordinary, state(PlayerPhysicsProtocol.Kind.OWNED, 1,
                    freeSnapshot(scope, 1, 8, 70, 8), null, null), new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> { }));
            ordinary.nativeCorrection(73);
            assertNull(ordinary.prepareNativeCorrectionApplication(73), "Free custody is not native delegation");
            assertFalse(ordinary.receiveNativeCorrectionComplete(control, () -> true));
            var session = delegatedCorrectionSession(scope, actor);
            var outerCue = session.nativeCorrectionCue();
            session.nativeCorrection(73);
            session.nativeCorrection(73);
            assertNull(session.prepareNativeCorrectionApplication(outerCue, 73), "nested same-ID correction is not this invocation");
            var apply = session.prepareNativeCorrectionApplication(73);
            apply.positionApplied = true; apply.confirmSent = true; apply.positionSent = true;
            session.nativeCorrectionApplicationReturned(apply);
            var foreign = new PlayerPhysicalSnapshot.Scope(scope.playerId(), scope.sessionId(), 0, 2);
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(foreign, 6, 9, 73), () -> true));
            assertFalse(session.receiveNativeCorrectionComplete(PlayerPhysicsProtocol.nativeTransferPacket(
                    PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE, scope, 6, 2,
                    new PlayerPhysicsProtocol.NativeCorrectionCompleted(9, 73)), () -> true));
            assertFalse(session.receiveNativeCorrectionComplete(correctionComplete(scope, 6, 9, 72), () -> true));
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> { session.reset(); return true; }));
            assertFalse(apply.current());
            assertFalse(session.receiveNativeCorrectionComplete(control, () -> true));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
        }
    }

    private static PlayerPhysicsProtocol.Packet correctionComplete(PlayerPhysicalSnapshot.Scope scope,
            long sequence, long identity, int teleportId) {
        return PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_CORRECTION_COMPLETE,
                scope, sequence, 1, new PlayerPhysicsProtocol.NativeCorrectionCompleted(identity, teleportId));
    }

    private static PlayerPhysicsClientSession delegatedCorrectionSession(PlayerPhysicalSnapshot.Scope scope, EntityPlayer actor) {
        var session = preparedSession(scope);
        var pose = cardinalSnapshot(scope, 4, CardinalFrame.Y_POS, 8, 70, 8);
        var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
        assertNotNull(install(session, state(PlayerPhysicsProtocol.Kind.STATE, 3, pose, receipt, null), new Object(), actor,
                new PlayerPhysicsClientPresentation(), () -> { }));
        session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
        assertTrue(session.receiveNativeTransfer(PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                scope, 5, 1, new PlayerPhysicsProtocol.NativeHandoff(71, 4, new Vec3d(260.25, -1, .25))),
                () -> true, (velocity, proof) -> {
                    assertTrue(proof.getAsBoolean());
                    actor.motionX = velocity.x() / 20; actor.motionY = velocity.y() / 20; actor.motionZ = velocity.z() / 20;
                }));
        session.nativeTransferAcknowledgementSent(session.nativeTransferAcknowledgement().orElseThrow());
        return session;
    }

    @Test void nativeHandoffNeedsActuallySentRestoreAndMotionIsStopAndWait(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2911), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2912));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2913), 0, 1);
            var session = preparedSession(scope);
            var presentation = new PlayerPhysicsClientPresentation();
            var snapshot = cardinalSnapshot(scope, 4, CardinalFrame.Y_POS, 8, 70, 8);
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var restore = state(PlayerPhysicsProtocol.Kind.STATE, 3, snapshot, receipt, null);
            assertNotNull(install(session, restore, new Object(), actor, presentation, () -> {}));
            var base = new PlayerPhysicsProtocol.NativeHandoff(41, 4, new Vec3d(260.125, -260.5, .125));
            var handoff = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, scope, 5, 1, base);
            var writes = new AtomicInteger();
            java.util.function.BiConsumer<Vec3d, java.util.function.BooleanSupplier> writer = (v, proof) -> {
                assertTrue(proof.getAsBoolean()); writes.incrementAndGet();
                actor.motionX = v.x()/20; actor.motionY = v.y()/20; actor.motionZ = v.z()/20;
                assertTrue(proof.getAsBoolean());
            };
            assertFalse(session.receiveNativeTransfer(handoff, () -> true, writer), "numeric revision is not sent restore proof");
            var input = session.input(idleControls()).orElseThrow();
            session.inputSendSucceeded(input);
            assertTrue(session.receiveNativeTransfer(handoff, () -> true, writer));
            assertEquals(260.125/20, actor.motionX); assertEquals(-260.5/20, actor.motionY);
            assertEquals(1,writes.get());
            var ack = session.nativeTransferAcknowledgement().orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.NativeHandoffAcknowledgement(41,4),ack.nativeTransfer().orElseThrow());
            session.nativeTransferAcknowledgementSent(ack);
            actor.motionX = 17;
            assertTrue(session.receiveNativeTransfer(handoff, () -> true, writer));
            assertEquals(17,actor.motionX); assertEquals(1,writes.get(), "duplicate cannot reset native evolution");
            var motion = new PlayerPhysicsProtocol.NativeMotion(41,4,1,new Vec3d(520.25,0,-260.125));
            var motionPacket = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_MOTION,scope,6,1,motion);
            var future = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_MOTION,scope,7,1,
                    new PlayerPhysicsProtocol.NativeMotion(41,4,2,new Vec3d(1,2,3)));
            assertFalse(session.receiveNativeTransfer(future,()->true,writer));
            assertTrue(session.receiveNativeTransfer(motionPacket,()->true,writer));
            assertEquals(520.25/20,actor.motionX); assertEquals(2,writes.get());
            assertTrue(session.receiveNativeTransfer(motionPacket,()->true,writer)); assertEquals(2,writes.get());
            assertFalse(session.receiveNativeTransfer(PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_MOTION,
                    scope,8,1,new PlayerPhysicsProtocol.NativeMotion(41,4,1,new Vec3d(9,9,9))),()->true,writer));
            assertTrue(session.receiveNativeTransfer(future,()->true,writer)); assertEquals(3,writes.get());
            assertFalse(session.receiveNativeTransfer(motionPacket,()->true,writer),"older than retained receipt rejects without write");
            session.nativeCorrection(73);
            assertFalse(session.receiveNativeTransfer(future,()->true,writer)); assertEquals(3,writes.get());
        }
    }

    @Test void nativeTransferCallbackFailureAndSuccessorCannotAcknowledgeOldVelocity(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory,new UUID(0,2921),true)) {
            var actor = fixture.serverPlayer(new UUID(0,2922));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,2923),0,1);
            var session = preparedSession(scope);
            var pose = cardinalSnapshot(scope,4,CardinalFrame.Y_POS,8,70,8);
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1,PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0,Optional.empty(),Optional.empty(),Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.STATE,3,pose,receipt,null),new Object(),actor,
                    new PlayerPhysicsClientPresentation(),()->{}));
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,scope,5,1,
                    new PlayerPhysicsProtocol.NativeHandoff(51,4,new Vec3d(260,0,0)));
            var original = new IllegalStateException("native-velocity-original");
            assertSame(original,assertThrows(IllegalStateException.class,()->session.receiveNativeTransfer(packet,()->true,
                    (v,proof)->{throw original;})));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertTrue(session.owns(), "failure retains protocol custody");
            assertFalse(session.drivesMotion(), "uncertain installation fences the old UG writer");
            assertFalse(session.receiveNativeTransfer(packet,()->true,(v,proof)->fail("failed installation replay")));
            var reentrant = preparedSession(scope);
            assertNotNull(install(reentrant,state(PlayerPhysicsProtocol.Kind.STATE,3,pose,receipt,null),new Object(),actor,
                    new PlayerPhysicsClientPresentation(),()->{}));
            reentrant.inputSendSucceeded(reentrant.input(idleControls()).orElseThrow());
            assertFalse(reentrant.receiveNativeTransfer(packet,()->true,(v,proof)->{
                reentrant.reset();
                var successorScope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,2924),0,2);
                assertTrue(reentrant.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,successorScope,
                        0,1,Optional.empty(),Optional.empty(),Optional.empty())));
                assertFalse(proof.getAsBoolean());
            }));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertTrue(reentrant.nativeTransferAcknowledgement().isEmpty());
            assertFalse(reentrant.receiveNativeTransfer(packet,()->true,(v,proof)->fail("old actor write")));
        }
    }

    @Test void nativeBaseInstallationFailureSchedulesRecoveryWithoutReplayingUncertainVelocity(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2991), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2992));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2993), 0, 1);
            var session = preparedSession(scope);
            var pose = cardinalSnapshot(scope, 4, CardinalFrame.Y_POS, 8, 70, 8);
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            assertNotNull(install(session, state(PlayerPhysicsProtocol.Kind.STATE, 3, pose, receipt, null),
                    new Object(), actor, new PlayerPhysicsClientPresentation(), () -> {}));
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, scope, 5, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(91, 4, new Vec3d(260, 1, 2)));
            var original = new IllegalStateException("base-partial-original");
            assertSame(original, assertThrows(IllegalStateException.class, () -> session.receiveNativeTransfer(packet,
                    () -> true, (velocity, current) -> {
                        assertTrue(current.getAsBoolean()); actor.motionX = velocity.x() / 20;
                        throw original;
                    })));
            assertEquals(13, actor.motionX);
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertFalse(session.drivesMotion());
            assertFalse(session.receiveNativeTransfer(packet, () -> true, (v, current) -> fail("uncertain base replay")));
            var recovery = session.recoveryRequest().orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.RECOVER, recovery.kind());
            assertEquals(scope, recovery.scope()); assertEquals(1, recovery.offerSequence());
            assertSame(recovery, session.recoveryRequest().orElseThrow(), "feedback is one retained attempt");
            var failed = (PlayerPhysicsProtocol.NativeTransferFailure) recovery.nativeTransfer().orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.NativeTransferFailure(91, 4, 5, 0), failed);
            var next = cardinalSnapshot(scope, 5, CardinalFrame.Y_POS, 8, 70, 8);
            assertFalse(session.prepareInstallation(state(PlayerPhysicsProtocol.Kind.STATE, 6, next, receipt, null)).prepared().isPresent(),
                    "an arbitrary higher revision cannot clear native failure");
            var wrong = new PlayerPhysicsProtocol.NativeTransferFailure(92, 4, 5, 0);
            assertFalse(session.prepareInstallation(nativeFailureRestore(6, next, receipt, wrong)).prepared().isPresent());
            assertNotNull(install(session, nativeFailureRestore(7, next, receipt, failed), new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> {}));
            assertTrue(session.nativeMovementMustRemainGated());
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            assertTrue(session.receiveNativeTransfer(PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                    scope, 8, 1, new PlayerPhysicsProtocol.NativeHandoff(92, 5, new Vec3d(260, 1, 2))), () -> true,
                    (v, current) -> { assertTrue(current.getAsBoolean()); actor.motionX = v.x()/20;
                        actor.motionY = v.y()/20; actor.motionZ = v.z()/20; assertTrue(current.getAsBoolean()); }));
            assertFalse(session.nativeMovementMustRemainGated());
            assertFalse(session.receiveNativeTransfer(packet, () -> true, (v, current) -> fail("old base after recovery")));
        }
    }

    @Test void nativeMotionPartialInstallationFailureSchedulesRecoveryWhileKeepingCustody(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 2994), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 2995));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 2996), 0, 1);
            var session = delegatedCorrectionSession(scope, actor);
            var packet = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE_MOTION, scope, 6, 1,
                    new PlayerPhysicsProtocol.NativeMotion(71, 4, 1, new Vec3d(520, 3, 4)));
            var original = new IllegalStateException("motion-partial-original");
            assertSame(original, assertThrows(IllegalStateException.class, () -> session.receiveNativeTransfer(packet,
                    () -> true, (velocity, current) -> {
                        assertTrue(current.getAsBoolean()); actor.motionX = velocity.x() / 20;
                        throw original;
                    })));
            assertEquals(26, actor.motionX);
            assertTrue(session.owns()); assertFalse(session.drivesMotion());
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
            assertFalse(session.receiveNativeTransfer(packet, () -> true, (v, current) -> fail("uncertain motion replay")));
            var recovery = session.recoveryRequest().orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.RECOVER, recovery.kind());
            assertEquals(scope, recovery.scope()); assertEquals(1, recovery.offerSequence());
            assertSame(recovery, session.recoveryRequest().orElseThrow());
            var failed = (PlayerPhysicsProtocol.NativeTransferFailure) recovery.nativeTransfer().orElseThrow();
            assertEquals(new PlayerPhysicsProtocol.NativeTransferFailure(71, 4, 6, 1), failed);
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1, PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0, Optional.empty(), Optional.empty(), Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var next = cardinalSnapshot(scope, 5, CardinalFrame.Y_POS, 8, 70, 8);
            assertNotNull(install(session, nativeFailureRestore(7, next, receipt, failed), new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> {}));
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            assertTrue(session.receiveNativeTransfer(PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,
                    scope, 8, 1, new PlayerPhysicsProtocol.NativeHandoff(72, 5, new Vec3d(520, 3, 4))), () -> true,
                    (v, current) -> { assertTrue(current.getAsBoolean()); actor.motionX = v.x()/20;
                        actor.motionY = v.y()/20; actor.motionZ = v.z()/20; assertTrue(current.getAsBoolean()); }));
            assertFalse(session.receiveNativeTransfer(packet, () -> true, (v, current) -> fail("old motion after recovery")));
        }
    }

    private static PlayerPhysicsProtocol.Packet nativeFailureRestore(long sequence, PlayerPhysicalSnapshot snapshot,
            PlayerPhysicsProtocol.BodyReferenceReceipt receipt, PlayerPhysicsProtocol.NativeTransferFailure failed) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, snapshot.scope(), sequence, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.of(receipt),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(new PlayerPhysicsProtocol.PredictionUnavailable(snapshot.revision(),
                        PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE)), Optional.empty(), Optional.empty(),
                Optional.of(new PlayerPhysicsProtocol.NativeTransferRecovery(failed)));
    }

    @Test void nativeTransferForeignLifetimeAndDeathRefuseWithoutAck(@TempDir Path directory) {
        try(var fixture=BootWorldFixture.open(directory,new UUID(0,2931),true)) {
            var actor=fixture.serverPlayer(new UUID(0,2932));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,2933),0,1);
            var session=preparedSession(scope); var pose=cardinalSnapshot(scope,4,CardinalFrame.Y_POS,8,70,8);
            var receipt=new PlayerPhysicsProtocol.BodyReferenceReceipt(1,PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED,
                    0,Optional.empty(),Optional.empty(),Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.STATE,3,pose,receipt,null),new Object(),actor,new PlayerPhysicsClientPresentation(),()->{}));
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            var payload=new PlayerPhysicsProtocol.NativeHandoff(61,4,new Vec3d(260,0,0));
            for(var wrong : java.util.List.of(
                    PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),scope.sessionId(),0,2),5,1,payload),
                    PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,scope,5,2,payload),
                    PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,scope,5,1,new PlayerPhysicsProtocol.NativeHandoff(61,3,new Vec3d(260,0,0))))) {
                assertFalse(session.receiveNativeTransfer(wrong,()->true,(v,proof)->fail("foreign write")));
            }
            var packet=PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE,scope,5,1,payload);
            assertFalse(session.receiveNativeTransfer(packet,()->actor.isEntityAlive()&&!actor.isDead,(v,proof)->{
                actor.setHealth(0); assertFalse(proof.getAsBoolean());
            }));
            assertTrue(session.nativeTransferAcknowledgement().isEmpty());
        }
    }

    @Test void expectedRefusalMustRetainCanonicalCustodyAndAck(@TempDir Path directory) {
        try(var fixture=BootWorldFixture.open(directory,new UUID(0,2701),true)) {
            var actor=fixture.serverPlayer(new UUID(0,2702));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,2703),0,1);
            var session=preparedSession(scope); var presentation=new PlayerPhysicsClientPresentation(); var owner=new Object();
            var a=cardinalSnapshot(scope,1,CardinalFrame.Y_POS,8,70,8);
            assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.OWNED,1,a,null,null),owner,actor,presentation,()->{}));
            var b=cardinalSnapshot(scope,2,CardinalFrame.Y_POS,9,71,8);
            assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.STATE,2,b,null,null),owner,actor,presentation,()->{}));
            var token=session.predictionLifetime();
            var mutation=new ClientCardinalPredictionOwner.Mutation(actor,session.presentationSnapshot().orElseThrow(),()->token.current(session));
            assertTrue(session.completePredictionSuspension(token,mutation::apply));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor,b));
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor,a));
            assertTrue(session.drivesMotion(),"expected refusal must retain physical owner rather than quarantine it");
            assertSame(b,session.presentationSnapshot().orElseThrow());
            for(int i=0;i<100;i++) {
                var input=session.input(idleControls()).orElseThrow();
                assertEquals(scope,input.scope());
                assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(2,2),input.bodyReferenceAcknowledgement().orElseThrow());
            }
        }
    }
    @Test void suspensionCallbackCannotClearSuccessorCanonicalRestorerOrAck(@TempDir Path directory) {
        try(var fixture=BootWorldFixture.open(directory,new UUID(0,2711),true)) {
            var actor=fixture.serverPlayer(new UUID(0,2712));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,2713),0,1);
            var session=preparedSession(scope); var presentation=new PlayerPhysicsClientPresentation(); var owner=new Object();
            var a=cardinalSnapshot(scope,1,CardinalFrame.Y_POS,8,70,8);
            assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.OWNED,1,a,null,null),owner,actor,presentation,()->{}));
            var token=session.predictionLifetime(); var b=cardinalSnapshot(scope,2,CardinalFrame.Y_POS,10,72,8);
            var calls=new AtomicInteger();
            assertFalse(session.completePredictionSuspension(token,()->{
                assertNotNull(install(session,state(PlayerPhysicsProtocol.Kind.STATE,2,b,null,null),owner,actor,presentation,()->{}));
                session.predictedPoseRestoration(()->{calls.incrementAndGet();return true;}); return true;
            }));
            session.runNativePose(actor,()->{}); assertEquals(1,calls.get());
            assertSame(b,session.presentationSnapshot().orElseThrow());
            assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(2,2),session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().orElseThrow());
            var x=new IllegalStateException("suspension-original-X"); var next=session.predictionLifetime();
            assertSame(x,assertThrows(IllegalStateException.class,()->session.completePredictionSuspension(next,()->{throw x;})));
            assertTrue(session.drivesMotion());
        }
    }

    @Test void freeAndCardinalModeAcknowledgementsFollowTheRealMirrorPresentationSessionChain(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 931), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 932));
            for (int index = 0; index < 2; index++) {
                var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 933 + index),
                        actor.dimension, index + 1);
                var session = preparedSession(scope);
                PlayerPhysicalSnapshot snapshot = index == 0
                        ? freeSnapshot(scope, 1, 12, 70, 12)
                        : cardinalSnapshot(scope, 1, CardinalFrame.X_POS, 13, 70, 12);
                var packet = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
                var presentation = new PlayerPhysicsClientPresentation();
                Object owner = new Object();
                var finalized = new AtomicInteger();

                var stamp = install(session, packet, owner, actor, presentation, finalized::incrementAndGet);

                assertNotNull(stamp);
                assertEquals(1, finalized.get(), "decode completion follows both real commits");
                assertSame(snapshot, session.snapshot().orElseThrow());
                var input = session.input(idleControls()).orElseThrow();
                var acknowledgement = input.bodyReferenceAcknowledgement().orElseThrow();
                assertEquals(packet.sequence(), acknowledgement.stateSequence());
                assertEquals(snapshot.revision(), acknowledgement.snapshotRevision());
                var afterUnreturnedSend = session.input(idleControls()).orElseThrow();
                assertEquals(acknowledgement, afterUnreturnedSend.bodyReferenceAcknowledgement().orElseThrow(),
                        "packet creation without a successful network return must retain the exact ACK");
                session.inputSendSucceeded(afterUnreturnedSend);
                assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                        "the exact ACK is consumed only after normal network submission return");
            }
        }
    }

    @Test void rejectedReplacementRevokesThePreviousStateAcknowledgement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 981), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 982));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 983), actor.dimension, 1);
            var session = preparedSession(scope);
            var accepted = cardinalSnapshot(scope, 1, CardinalFrame.Y_POS, 8, 70, 8);
            var acceptedPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null, null);
            assertNotNull(install(session, acceptedPacket, new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> { }));
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isPresent());

            var rejected = cardinalSnapshot(scope, 2, CardinalFrame.Y_POS, 9, 70, 8);
            var rejectedPacket = state(PlayerPhysicsProtocol.Kind.STATE, 2, rejected, null, null);
            var prepared = session.prepareInstallation(rejectedPacket).prepared().orElseThrow();
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.INSTALL, prepared.disposition());
            assertTrue(session.revokePreparedInstallation(prepared),
                    "a pre-mutation owner rejection retires the attempted replacement");
            assertSame(accepted, session.snapshot().orElseThrow(), "failed preparation does not publish replacement geometry");
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "a failed replacement cannot leave an ACK for the previous STATE eligible");
        }
    }

    @Test void correctionAndAcceptedProposalReplacementCannotReuseThePriorInstallationStamp(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 991), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 992));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 993), actor.dimension, 1);
            var session = preparedSession(scope);
            var accepted = cardinalSnapshot(scope, 1, CardinalFrame.Y_POS, 8, 70, 8);
            var acceptedPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, accepted, null, null);
            var presentation = new PlayerPhysicsClientPresentation();
            Object originalOwner = new Object();
            var stamp = install(session, acceptedPacket, originalOwner, actor, presentation, () -> { });
            assertNotNull(stamp);

            var retransmission = state(PlayerPhysicsProtocol.Kind.STATE, 2, accepted, null, null);
            var beforeCorrection = session.prepareInstallation(retransmission).prepared().orElseThrow();
            session.nativeCorrection(4);
            assertFalse(stamp.matchesRetransmission(originalOwner, session, beforeCorrection, retransmission,
                    actor, actor.world, presentation, actor, accepted),
                    "a native correction retires the old preparation and accepted presentation proof");
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "native correction revokes an ordinary ACK before any later state arrives");

            var restored = cardinalSnapshot(scope, 2, CardinalFrame.Y_POS, 8, 70, 8);
            var restoreReceipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1,
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var restorePacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 2, 1,
                    Optional.empty(), Optional.of(restored), Optional.empty(), OptionalInt.of(4),
                    Optional.of(restoreReceipt), Optional.empty());
            assertNotNull(install(session, restorePacket, originalOwner, actor, presentation, () -> { }));
            session.inputSendSucceeded(session.input(idleControls()).orElseThrow());
            var nativePacket = PlayerPhysicsProtocol.nativeTransferPacket(PlayerPhysicsProtocol.Kind.NATIVE, scope, 3, 1,
                    new PlayerPhysicsProtocol.NativeHandoff(71, restored.revision(), restored.worldVelocity()));
            assertTrue(session.receiveNativeTransfer(nativePacket, () -> true, (velocity, proof) -> {
                assertTrue(proof.getAsBoolean());
                actor.motionX = velocity.x() / 20; actor.motionY = velocity.y() / 20; actor.motionZ = velocity.z() / 20;
                assertTrue(proof.getAsBoolean());
            }));
            var replacementOffer = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 0, 2,
                    Optional.empty(), Optional.empty(), Optional.empty());
            assertTrue(session.receive(replacementOffer), "the delegated Session accepts the newer offer proposal");
            session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), ignored -> { });

            // Restore STATE revision2 and NATIVE wire sequence3 already belong to the old proposal.
            var replacement = cardinalSnapshot(scope, 3, CardinalFrame.Y_POS, 9, 70, 8);
            var replacementState = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 4, 2,
                    Optional.empty(), Optional.of(replacement), Optional.empty());
            var newOwnerClient = new PlayerPhysicsClientInstallationTestSupport(session, actor);
            assertTrue(newOwnerClient.install(replacementState),
                    "the new proposal earns confirmation only through a new complete installation");
            var freshInput = session.input(idleControls()).orElseThrow();
            var freshAck = freshInput.bodyReferenceAcknowledgement().orElseThrow();
            assertEquals(replacementState.sequence(), freshAck.stateSequence());
            assertEquals(replacement.revision(), freshAck.snapshotRevision());
        }
    }

    @Test void retransmissionReusesOnlyTheStillExactInstalledMirrorAndPresentation(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 941), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 942));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 943), actor.dimension, 1);
            var session = preparedSession(scope);
            var snapshot = cardinalSnapshot(scope, 7, CardinalFrame.Y_POS, 8, 70, 8);
            var firstPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var presentation = new PlayerPhysicsClientPresentation();
            Object owner = new Object();
            var stamp = install(session, firstPacket, owner, actor, presentation, () -> { });
            assertNotNull(stamp);

            var retransmission = state(PlayerPhysicsProtocol.Kind.STATE, 2, snapshot, null, null);
            var prepared = session.prepareInstallation(retransmission).prepared().orElseThrow();
            assertTrue(stamp.matchesRetransmission(owner, session, prepared, retransmission, actor, actor.world,
                    presentation, actor, snapshot));
            var nextStamp = stamp.publishRetransmission(owner, session, prepared, retransmission, actor,
                    actor.world, presentation, actor, snapshot, () -> { });
            assertNotNull(nextStamp);
            var input = session.input(idleControls()).orElseThrow();
            var acknowledgement = input.bodyReferenceAcknowledgement().orElseThrow();
            assertEquals(retransmission.sequence(), acknowledgement.stateSequence());
            assertEquals(snapshot.revision(), acknowledgement.snapshotRevision());
            session.inputSendSucceeded(input);

            var consecutiveRetransmission = state(PlayerPhysicsProtocol.Kind.STATE, 3, snapshot, null, null);
            var consecutivePrepared = session.prepareInstallation(consecutiveRetransmission).prepared().orElseThrow();
            assertTrue(nextStamp.matchesRetransmission(owner, session, consecutivePrepared, consecutiveRetransmission,
                    actor, actor.world, presentation, actor, snapshot),
                    "a prior exact retransmission keeps the same accepted Presentation snapshot identity");
            var consecutiveStamp = nextStamp.publishRetransmission(owner, session, consecutivePrepared,
                    consecutiveRetransmission, actor, actor.world, presentation, actor, snapshot, () -> { });
            assertNotNull(consecutiveStamp);
            var consecutiveInput = session.input(idleControls()).orElseThrow();
            assertEquals(consecutiveRetransmission.sequence(), consecutiveInput.bodyReferenceAcknowledgement()
                    .orElseThrow().stateSequence());
            session.inputSendSucceeded(consecutiveInput);

            Object replacementOwner = new Object();
            var nextPacket = state(PlayerPhysicsProtocol.Kind.STATE, 4, snapshot, null, null);
            var nextPrepared = session.prepareInstallation(nextPacket).prepared().orElseThrow();
            assertFalse(consecutiveStamp.matchesRetransmission(replacementOwner, session, nextPrepared, nextPacket,
                    actor, actor.world, presentation, actor, snapshot),
                    "an offer/proposal owner replacement cannot reuse the prior installation stamp");
            assertTrue(consecutiveStamp.matchesRetransmission(owner, session, nextPrepared, nextPacket,
                    actor, actor.world, presentation, actor, snapshot));
            presentation.reset();
            assertFalse(consecutiveStamp.matchesRetransmission(owner, session, nextPrepared, nextPacket,
                    actor, actor.world, presentation, actor, null),
                    "a reset Presentation no longer proves the installed source");
            presentation = new PlayerPhysicsClientPresentation();
            var beforeLifetimeReset = session.prepareInstallation(nextPacket).prepared().orElseThrow();
            session.reset();
            assertFalse(consecutiveStamp.matchesRetransmission(owner, session, beforeLifetimeReset, nextPacket,
                    actor, actor.world, presentation, actor, snapshot),
                    "a replaced Session lifetime cannot reuse the prior installation stamp");
        }
    }

    @Test void mirrorStageEyeTransportOrBoundsMismatchCannotAuthorizeAStateAck(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 951), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 952));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 953), actor.dimension, 1);
            var session = preparedSession(scope);
            var snapshot = cardinalSnapshot(scope, 2, CardinalFrame.Y_POS, 8, 70, 8);
            var packet = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var presentation = new PlayerPhysicsClientPresentation();
            Object owner = new Object();

            var first = session.prepareInstallation(packet).prepared().orElseThrow();
            assertTrue(session.beginPreparedInstallation(first));
            var presentationToken = presentation.prepare(actor, snapshot);
            var preparedMirror = PlayerPhysicsClientMirror.prepare(actor, snapshot).prepared().orElseThrow();
            var boundsApplied = PlayerPhysicsClientMirror.commitPrepared(preparedMirror);
            assertTrue(PlayerPhysicsClientMirror.proveInstalled(boundsApplied).isEmpty(),
                    "the first mirror stage cannot prove the remaining commit");
            assertNull(PlayerPhysicsClient.completeInstalledState(owner, session, first, packet, actor,
                    actor.world, null, presentation, presentationToken, () -> { }),
                    "a missing completed mirror proof cannot publish or authorize ACK");
            assertTrue(session.input(idleControls()).isEmpty());
            PlayerPhysicsClientMirror.commitRemaining(boundsApplied);
            var proof = PlayerPhysicsClientMirror.proveInstalled(boundsApplied).orElseThrow();
            assertTrue(proof.matchesCurrent(actor, snapshot));

            float exactEyeBase = actor.eyeHeight;
            actor.eyeHeight = exactEyeBase + .01F;
            assertFalse(proof.matchesCurrent(actor, snapshot), "the Forge eyeHeight base is part of the proof");
            actor.eyeHeight = exactEyeBase;
            double exactTransportX = actor.posX;
            actor.posX = exactTransportX + 1;
            assertFalse(proof.matchesCurrent(actor, snapshot), "transport drift invalidates the proof");
            actor.posX = exactTransportX;
            AxisAlignedBB exactBounds = actor.getEntityBoundingBox();
            actor.setEntityBoundingBox(new AxisAlignedBB(exactBounds.minX, exactBounds.minY,
                    exactBounds.minZ, exactBounds.maxX + .25, exactBounds.maxY, exactBounds.maxZ));
            assertFalse(proof.matchesCurrent(actor, snapshot), "actual AABB drift invalidates the proof");
            actor.setEntityBoundingBox(exactBounds);

            var finalized = new AtomicInteger();
            assertNull(PlayerPhysicsClient.completeInstalledState(owner, session, first, packet, actor,
                    null, proof, presentation, presentationToken, finalized::incrementAndGet),
                    "the exact actor world is part of the successful-install identity");
            assertEquals(0, finalized.get(), "world mismatch refuses before decode finalization");
            var stamp = PlayerPhysicsClient.completeInstalledState(owner, session, first, packet, actor,
                    actor.world, proof, presentation, presentationToken, finalized::incrementAndGet);
            assertNotNull(stamp);
            assertEquals(1, finalized.get());
            var acceptedInput = session.input(idleControls()).orElseThrow();
            assertTrue(acceptedInput.bodyReferenceAcknowledgement().isPresent());
            session.inputSendSucceeded(acceptedInput);

            var retx = state(PlayerPhysicsProtocol.Kind.STATE, 2, snapshot, null, null);
            var retxPrepared = session.prepareInstallation(retx).prepared().orElseThrow();
            actor.eyeHeight = exactEyeBase + .01F;
            assertFalse(stamp.matchesRetransmission(owner, session, retxPrepared, retx, actor, actor.world,
                    presentation, actor, snapshot));
            session.revokeStateAcknowledgements();
            assertNull(stamp.publishRetransmission(owner, session, retxPrepared, retx, actor, actor.world,
                    presentation, actor, snapshot, () -> { }));
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty());
        }
    }

    @Test void presentationSessionAndNativeRestoreRefusalsDoNotMintAnOrdinaryAcknowledgement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 961), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 962));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 963), actor.dimension, 1);
            var session = preparedSession(scope);
            var snapshot = cardinalSnapshot(scope, 3, CardinalFrame.Y_POS, 8, 70, 8);
            var packet = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var presentation = new PlayerPhysicsClientPresentation();
            var prepared = session.prepareInstallation(packet).prepared().orElseThrow();
            assertTrue(session.beginPreparedInstallation(prepared));
            var presentationToken = presentation.prepare(actor, snapshot);
            var mirrorPrep = PlayerPhysicsClientMirror.prepare(actor, snapshot).prepared().orElseThrow();
            var applied = PlayerPhysicsClientMirror.commitPrepared(mirrorPrep);
            PlayerPhysicsClientMirror.commitRemaining(applied);
            var proof = PlayerPhysicsClientMirror.proveInstalled(applied).orElseThrow();
            presentation.reset();
            assertNull(PlayerPhysicsClient.completeInstalledState(new Object(), session, prepared, packet,
                    actor, actor.world, proof, presentation, presentationToken, () -> { }),
                    "a stale real Presentation token rejects the completed-install boundary");
            assertTrue(session.input(idleControls()).isEmpty());

            var resetSession = preparedSession(scope);
            var resetPrepared = resetSession.prepareInstallation(packet).prepared().orElseThrow();
            assertTrue(resetSession.beginPreparedInstallation(resetPrepared));
            var resetPresentation = new PlayerPhysicsClientPresentation();
            var resetPresentationToken = resetPresentation.prepare(actor, snapshot);
            var resetMirror = PlayerPhysicsClientMirror.prepare(actor, snapshot).prepared().orElseThrow();
            var resetBounds = PlayerPhysicsClientMirror.commitPrepared(resetMirror);
            PlayerPhysicsClientMirror.commitRemaining(resetBounds);
            var resetProof = PlayerPhysicsClientMirror.proveInstalled(resetBounds).orElseThrow();
            resetSession.reset();
            assertNull(PlayerPhysicsClient.completeInstalledState(new Object(), resetSession, resetPrepared,
                    packet, actor, actor.world, resetProof, resetPresentation, resetPresentationToken, () -> { }),
                    "a reset Session rejects the stale prepared install before minting ACK");
            assertTrue(resetSession.input(idleControls()).isEmpty());

            var nativeSession = preparedSession(scope);
            var nativePresentation = new PlayerPhysicsClientPresentation();
            var receipt = new PlayerPhysicsProtocol.BodyReferenceReceipt(1,
                    PlayerPhysicsProtocol.BodyReferenceDecision.UNLOCKED, 0, Optional.empty(), Optional.empty(),
                    Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.NATIVE_HANDOFF));
            var nativeReference = new PlayerBodyReference.State(Optional.empty(),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE, CardinalFrame.Y_POS,
                            new Vec3d(1, 0, 0)), PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING,
                    Optional.empty(), Optional.empty());
            var nativePacket = state(PlayerPhysicsProtocol.Kind.STATE, 1, snapshot, receipt, nativeReference);
            var nativeStamp = install(nativeSession, nativePacket, new Object(), actor, nativePresentation, () -> { });
            assertNotNull(nativeStamp);
            var nativeInput = nativeSession.input(idleControls()).orElseThrow();
            var nativeAck = nativeInput.bodyReferenceAcknowledgement().orElseThrow();
            assertEquals(nativePacket.sequence(), nativeAck.stateSequence());
            nativeSession.inputSendSucceeded(nativeInput);

            var wrongDomainSession = preparedSession(scope);
            var wrongDomainToken = wrongDomainSession.prepareInstallation(nativePacket).prepared().orElseThrow();
            assertTrue(wrongDomainSession.beginPreparedInstallation(wrongDomainToken));
            assertTrue(PlayerPhysicsClientMirror.install(actor, snapshot));
            assertTrue(wrongDomainSession.publishInstallation(wrongDomainToken));
            assertFalse(wrongDomainSession.authorizePublishedStateAcknowledgement(wrongDomainToken,
                    PlayerPhysicsClientSession.AcknowledgementDomain.ORDINARY_MODE));
            assertTrue(wrongDomainSession.input(idleControls()).orElseThrow()
                    .bodyReferenceAcknowledgement().isEmpty(),
                    "the shared wire payload cannot authorize a native-purpose state from the ordinary domain");
        }
    }

    @Test void sessionPublicationAloneAndFailedDecodeFinalizationCannotMintAcknowledgement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 971), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 972));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 973), actor.dimension, 1);
            var session = preparedSession(scope);
            var snapshot = cardinalSnapshot(scope, 1, CardinalFrame.Z_POS, 8, 70, 8);
            var packet = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var prepared = session.prepareInstallation(packet).prepared().orElseThrow();
            assertTrue(session.beginPreparedInstallation(prepared));
            assertTrue(PlayerPhysicsClientMirror.install(actor, snapshot));
            var presentation = new PlayerPhysicsClientPresentation();
            assertTrue(presentation.commitPrepared(presentation.prepare(actor, snapshot)));
            assertTrue(session.publishInstallation(prepared));
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty(),
                    "Session publication alone never grants ACK authority");

            var failingSession = preparedSession(scope);
            var failingPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var token = failingSession.prepareInstallation(failingPacket).prepared().orElseThrow();
            assertTrue(failingSession.beginPreparedInstallation(token));
            var actualPresentation = new PlayerPhysicsClientPresentation();
            var presentationToken = actualPresentation.prepare(actor, snapshot);
            var mirror = PlayerPhysicsClientMirror.prepare(actor, snapshot).prepared().orElseThrow();
            var applied = PlayerPhysicsClientMirror.commitPrepared(mirror);
            PlayerPhysicsClientMirror.commitRemaining(applied);
            var proof = PlayerPhysicsClientMirror.proveInstalled(applied).orElseThrow();
            IllegalStateException failure = new IllegalStateException("decode finalization sentinel");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> PlayerPhysicsClient.completeInstalledState(new Object(), failingSession, token,
                            failingPacket, actor, actor.world, proof, actualPresentation, presentationToken,
                            () -> { throw failure; })));
            assertTrue(failingSession.input(idleControls()).orElseThrow()
                    .bodyReferenceAcknowledgement().isEmpty());
        }
    }

    @Test void sessionLifetimeChangedByDecodeCompletionCannotReturnAnInstallationStamp(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 974), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 975));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 976), actor.dimension, 1);
            var snapshot = cardinalSnapshot(scope, 1, CardinalFrame.Y_POS, 8, 70, 8);

            var installSession = preparedSession(scope);
            var installPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            var finalized = new AtomicInteger();
            assertNull(install(installSession, installPacket, new Object(), actor,
                    new PlayerPhysicsClientPresentation(), () -> {
                        finalized.incrementAndGet();
                        installSession.reset();
                    }), "a Session lifetime change after decode completion refuses the stamp");
            assertEquals(1, finalized.get());
            assertTrue(installSession.input(idleControls()).isEmpty(),
                    "a replaced lifetime cannot expose a stale STATE acknowledgement");

            var retransmissionSession = preparedSession(scope);
            var originalPacket = state(PlayerPhysicsProtocol.Kind.OWNED, 1, snapshot, null, null);
            Object owner = new Object();
            var presentation = new PlayerPhysicsClientPresentation();
            var originalStamp = install(retransmissionSession, originalPacket, owner, actor, presentation, () -> { });
            assertNotNull(originalStamp);
            var retransmission = state(PlayerPhysicsProtocol.Kind.STATE, 2, snapshot, null, null);
            var prepared = retransmissionSession.prepareInstallation(retransmission).prepared().orElseThrow();
            var retransmissionStamp = originalStamp.publishRetransmission(owner, retransmissionSession, prepared,
                    retransmission, actor, actor.world, presentation, actor, snapshot, retransmissionSession::reset);
            assertNull(retransmissionStamp,
                    "a Session lifetime change after retransmission decode cannot return a success stamp");
            assertTrue(retransmissionSession.input(idleControls()).isEmpty());
        }
    }

    @Test void nativeTeleportCorrectionWithoutRestoreReceiptCompletesTheRealInstallAndAcknowledgement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 981), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 982));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 983), actor.dimension, 1);
            var session = preparedSession(scope);
            var presentation = new PlayerPhysicsClientPresentation();
            Object owner = new Object();
            assertNotNull(install(session, state(PlayerPhysicsProtocol.Kind.OWNED, 1,
                    cardinalSnapshot(scope, 1, CardinalFrame.Y_POS, 8, 70, 8), null, null),
                    owner, actor, presentation, () -> { }));
            session.nativeCorrection(37);
            var corrected = cardinalSnapshot(scope, 2, CardinalFrame.Y_POS, 8.5, 88, 8.5);
            var packet = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 2, 1,
                    Optional.empty(), Optional.of(corrected), Optional.empty(), OptionalInt.of(37));
            var finishes = new AtomicInteger();
            assertNotNull(install(session, packet, owner, actor, presentation, finishes::incrementAndGet),
                    "a normal teleport has no native-restore receipt but must finish installation");
            assertEquals(1, finishes.get());
            var input = session.input(idleControls()).orElseThrow();
            assertTrue(input.nativeTeleportId().isEmpty(), "INPUT uses its scope and ACK identity, not the STATE correction field");
            assertEquals(new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(2, 2),
                    input.bodyReferenceAcknowledgement().orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, corrected));
            var wrongCorrection = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, scope, 3, 1,
                    Optional.empty(), Optional.of(corrected), Optional.empty(), OptionalInt.of(36));
            assertEquals(PlayerPhysicsClientSession.InstallationDisposition.REJECTED,
                    session.prepareInstallation(wrongCorrection).disposition());
        }
    }
    @Test void mirrorChangedAfterDecodeCompletionCannotMintStampOrAck(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 984), true)) {
            EntityPlayer actor = fixture.serverPlayer(new UUID(0, 985));
            var scope = new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 986), actor.dimension, 1);
            var session = preparedSession(scope);
            var packet = state(PlayerPhysicsProtocol.Kind.OWNED, 1,
                    cardinalSnapshot(scope, 1, CardinalFrame.Y_POS, 8, 70, 8), null, null);
            var finished = new AtomicInteger();
            assertNull(install(session, packet, new Object(), actor, new PlayerPhysicsClientPresentation(), () -> {
                finished.incrementAndGet();
                actor.posX += 1;
            }));
            assertEquals(1, finished.get());
            assertTrue(session.input(idleControls()).orElseThrow().bodyReferenceAcknowledgement().isEmpty());
        }
    }

    private static PlayerPhysicsClient.SuccessfulInstallationStamp install(PlayerPhysicsClientSession session,
            PlayerPhysicsProtocol.Packet packet, Object owner, EntityPlayer actor,
            PlayerPhysicsClientPresentation presentation, Runnable finish) {
        var prepared = session.prepareInstallation(packet).prepared().orElseThrow();
        assertEquals(PlayerPhysicsClientSession.InstallationDisposition.INSTALL, prepared.disposition());
        assertTrue(session.beginPreparedInstallation(prepared));
        var presentationToken = presentation.prepare(actor, packet.snapshot().orElseThrow());
        var mirror = PlayerPhysicsClientMirror.prepare(actor, packet.snapshot().orElseThrow()).prepared().orElseThrow();
        var applied = PlayerPhysicsClientMirror.commitPrepared(mirror);
        PlayerPhysicsClientMirror.commitRemaining(applied);
        var proof = PlayerPhysicsClientMirror.proveInstalled(applied).orElseThrow();
        return PlayerPhysicsClient.completeInstalledState(owner, session, prepared, packet, actor, actor.world,
                proof, presentation, presentationToken, finish);
    }

    private static PlayerPhysicsClientSession preparedSession(PlayerPhysicalSnapshot.Scope scope) {
        var session = new PlayerPhysicsClientSession(() -> { }, () -> { });
        assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope,
                0, 1, Optional.empty(), Optional.empty(), Optional.empty())));
        session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), ignored -> { });
        return session;
    }

    private static PlayerPhysicsProtocol.Packet state(PlayerPhysicsProtocol.Kind kind, long sequence,
            PlayerPhysicalSnapshot snapshot, PlayerPhysicsProtocol.BodyReferenceReceipt receipt,
            PlayerBodyReference.State reference) {
        return new PlayerPhysicsProtocol.Packet(kind, snapshot.scope(), sequence, 1, Optional.empty(),
                Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.ofNullable(receipt),
                Optional.empty(), Optional.ofNullable(reference));
    }

    private static PlayerPhysicalSnapshot cardinalSnapshot(PlayerPhysicalSnapshot.Scope scope, long revision,
            CardinalFrame frame, double x, double y, double z) {
        var shape = new PlayerBodyShape.Cardinal(frame,
                new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return snapshot(scope, revision, new PlayerBodyPlacement(new Vec3d(x, y, z), frame.attitude(), shape));
    }

    private static PlayerPhysicalSnapshot freeSnapshot(PlayerPhysicalSnapshot.Scope scope, long revision,
            double x, double y, double z) {
        var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return snapshot(scope, revision, new PlayerBodyPlacement(new Vec3d(x, y, z),
                new Rotation3d(.2, .3, .4, .5), shape));
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerPhysicalSnapshot.Scope scope, long revision,
            PlayerBodyPlacement placement) {
        return new PlayerPhysicalSnapshot(scope, revision, placement, new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }

    private static PlayerPhysicsProtocol.Controls idleControls() {
        return new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1),
                false, false, false, false, 5);
    }
}
