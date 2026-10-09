package wi.unigrav.core.client.boost;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsClientSessionTest {
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
    private static final PlayerPhysicalSnapshot.Scope SCOPE = new PlayerPhysicalSnapshot.Scope(
            new UUID(0, 1), new UUID(0, 2), 0, 1);

    @Test void failedNativeApplicationFencesAnOlderOwnedAndRetriesWithoutRetiringTwice() {
        var fail = new AtomicBoolean(true);
        var retirements = new AtomicInteger();
        var failure = new IllegalStateException("native restore unavailable");
        var client = new PlayerPhysicsClientSession(snapshot -> true, retirements::incrementAndGet,
                () -> { if (fail.get()) throw failure; });
        prepare(client);
        var delegated = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 2, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.receive(delegated)));
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        fail.set(false);
        assertTrue(client.receive(delegated));
        assertTrue(client.owns(), "a native delegation can recover a lost OWNED");
        assertFalse(client.drivesMotion());
        assertEquals(1, retirements.get());
    }

    @Test void nativeDelegationKeepsCustodyAndResumesThroughFreshApplyWithMonotonicSequence() {
        var retirements = new AtomicInteger();
        var nativeEntries = new AtomicInteger();
        var client = new PlayerPhysicsClientSession(snapshot -> true, retirements::incrementAndGet, nativeEntries::incrementAndGet);
        prepare(client);
        assertTrue(client.receive(state(PlayerPhysicsProtocol.Kind.OWNED, 1, 0, 9)));
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0), new Vec3d(0, 0, 1), false, false, false, false, 5);
        long lastInput = 0;
        for (int i = 0; i < 10; i++) lastInput = client.input(controls).orElseThrow().sequence();
        var nativePacket = new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 2, 1,
                Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(nativePacket.clientbound());
        assertEquals(nativePacket, PlayerPhysicsProtocol.decode(PlayerPhysicsProtocol.encode(nativePacket)));
        assertTrue(client.receive(nativePacket));
        assertTrue(client.owns(), "legacy UG writers stay retired");
        assertFalse(client.drivesMotion(), "native travel is explicitly allowed");
        assertTrue(client.input(controls).isEmpty());
        assertEquals(1, nativeEntries.get());
        assertFalse(client.receive(state(PlayerPhysicsProtocol.Kind.STATE, 3, 1, 7)),
                "a newer old-proposal STATE cannot replace the required resume APPLY");
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 3, 2,
                Optional.empty(), Optional.empty(), Optional.empty())));
        var sent = new AtomicReference<PlayerPhysicsProtocol.Packet>();
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), sent::set);
        assertTrue(sent.get().sequence() > lastInput, "new APPLY must not restart below prior INPUT sequence");
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.NATIVE, SCOPE, 4, 2,
                Optional.empty(), Optional.empty(), Optional.empty())));
        var resumed = state(PlayerPhysicsProtocol.Kind.OWNED, 5, 1, 0);
        assertFalse(client.receive(new PlayerPhysicsProtocol.Packet(resumed.kind(), SCOPE, 5, 2,
                Optional.empty(), resumed.snapshot(), Optional.empty())), "re-entering native cancels an in-flight resume");
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 6, 3,
                Optional.empty(), Optional.empty(), Optional.empty())));
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), sent::set);
        assertTrue(client.receive(new PlayerPhysicsProtocol.Packet(resumed.kind(), SCOPE, 7, 3,
                Optional.empty(), resumed.snapshot(), Optional.empty())));
        assertTrue(client.drivesMotion());
        assertEquals(1, retirements.get(), "native delegation never resurrects and retires Remote twice");
        assertFalse(client.receive(nativePacket));
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

    private static void prepare(PlayerPhysicsClientSession client) {
        client.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, SCOPE, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty()));
        client.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), packet -> { });
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

    private static BoostProtocol.Packet legacyState(long serial, BoostProtocol.Operation operation) {
        return BoostProtocol.state(11, 1, serial, 0, BoostProtocol.Mode.ACTIVE, 5, 70,
                operation, new Vec3(2, 0, 0), BoostProtocol.Reason.NONE);
    }
}
