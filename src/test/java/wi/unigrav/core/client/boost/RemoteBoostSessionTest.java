package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;

/** Pure ordering and ownership tests for the remote client reducer. */
class RemoteBoostSessionTest {

    @Test
    void onlySuccessfulVelocityApplicationCanProduceAHandoffReceipt() {
        AtomicReference<Vec3> motion = new AtomicReference<>(new Vec3(20.0, 0.0, 0.0));
        RemoteBoostSession session = new RemoteBoostSession(packet -> {
            if (packet.operation() == BoostProtocol.Operation.ADD) {
                motion.set(motion.get().add(packet.velocity()));
            }
            return true;
        });
        assertTrue(session.acceptOffer(BoostProtocol.offer(71L, 4, 490.0, true)));
        assertTrue(session.applicationReceipt().isEmpty());
        BoostProtocol.Packet add = state(71L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD);
        assertTrue(session.acceptState(add, 4));
        assertEquals(new Vec3(32.0, -4.0, 1.0), motion.get());
        var receipt = session.applicationReceipt().orElseThrow();
        assertEquals(add, receipt.state());
        assertEquals(add, receipt.lastVelocityOperation());
        assertFalse(session.acceptState(add, 4));
        assertEquals(new Vec3(32.0, -4.0, 1.0), motion.get());
        assertEquals(receipt, session.applicationReceipt().orElseThrow());
    }

    @Test
    void failedVelocityIsNotReplayedOrHiddenByALaterMetadataState() {
        RemoteBoostSession session = new RemoteBoostSession(packet ->
                packet.operation() != BoostProtocol.Operation.ADD);
        assertTrue(session.acceptOffer(BoostProtocol.offer(72L, 4, 490.0, true)));
        BoostProtocol.Packet add = state(72L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD);
        assertFalse(session.acceptState(add, 4));
        assertTrue(session.applicationReceipt().isEmpty());
        assertFalse(session.acceptState(add, 4));
        assertTrue(session.acceptState(state(72L, 1L, 3L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.NONE), 4));
        assertTrue(session.applicationReceipt().isEmpty());
        BoostProtocol.Packet replace = state(72L, 1L, 4L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.REPLACE);
        assertTrue(session.acceptState(replace, 4));
        assertEquals(replace, session.applicationReceipt().orElseThrow().lastVelocityOperation());
    }

    @Test
    void applicationExceptionKeepsItsCauseAndCannotIssueAReceipt() {
        IllegalStateException cause = new IllegalStateException("motion writer failed");
        RemoteBoostSession session = new RemoteBoostSession(packet -> { throw cause; });
        assertTrue(session.acceptOffer(BoostProtocol.offer(73L, 4, 490.0, true)));
        BoostProtocol.Packet add = state(73L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD);
        assertEquals(cause, assertThrows(IllegalStateException.class, () -> session.acceptState(add, 4)));
        assertTrue(session.applicationReceipt().isEmpty());
        assertFalse(session.acceptState(add, 4));
    }

    @Test
    void containmentDuringApplicationCannotBeOverwrittenByTheSuccessfulCallbackReturn() {
        AtomicReference<RemoteBoostSession> current = new AtomicReference<>();
        RemoteBoostSession session = new RemoteBoostSession(packet -> {
            current.get().invalidateApplication();
            return true;
        });
        current.set(session);
        assertTrue(session.acceptOffer(BoostProtocol.offer(74L, 4, 490.0, true)));
        assertFalse(session.acceptState(state(74L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.REPLACE), 4));
        assertTrue(session.applicationReceipt().isEmpty());
    }

    @Test
    void metadataStatesPreserveTheActualVelocityOperationAcrossEpochChanges() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(75L, 4, 490.0, true)));
        BoostProtocol.Packet add = state(75L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD);
        assertTrue(session.acceptState(add, 4));
        BoostProtocol.Packet off = state(75L, 2L, 3L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE);
        assertTrue(session.acceptState(off, 4));
        var receipt = session.applicationReceipt().orElseThrow();
        assertEquals(off, receipt.state());
        assertEquals(add, receipt.lastVelocityOperation());
        session.reset();
        assertTrue(session.applicationReceipt().isEmpty());
        assertTrue(session.acceptOffer(BoostProtocol.offer(76L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(76L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNull(session.applicationReceipt().orElseThrow().lastVelocityOperation());
    }

    @Test
    void resetDuringApplicationRetiresTheOldReceiptWithoutChangingGIntent() {
        AtomicReference<RemoteBoostSession> current = new AtomicReference<>();
        RemoteBoostSession session = new RemoteBoostSession(packet -> {
            current.get().reset();
            return true;
        });
        current.set(session);
        assertTrue(session.acceptOffer(BoostProtocol.offer(77L, 4, 490.0, true)));
        session.requestMode(true, 4);
        assertFalse(session.acceptState(state(77L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD), 4));
        assertTrue(session.applicationReceipt().isEmpty());
        assertTrue(session.requestedIntent());
        assertFalse(session.hasOffer());
    }

    @Test
    void voluntaryReleaseKeepsSameEpochCustodyUntilClosingHandoff() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(17L, 4, 490.0, true)));
        assertFalse(session.canRequestEnable());
        assertNull(session.requestMode(true, 4));
        assertTrue(session.acceptState(state(17L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));

        BoostProtocol.Packet enable = session.requestMode(true, 4);
        assertNotNull(enable);
        assertFalse(session.canRequestEnable());
        assertNull(session.requestMode(true, 4));
        assertTrue(session.acceptState(state(17L, 3L, 1L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.NONE), 4));
        assertTrue(session.personalZeroG());
        assertTrue(session.hasCustody());

        BoostProtocol.Packet disable = session.requestMode(false, 4);
        assertNotNull(disable);
        assertFalse(session.personalZeroG());
        // The local release must not invent a new epoch or drop frame pairing.
        assertTrue(session.hasCustody());
        assertTrue(session.acceptState(state(17L, 3L, 2L,
                BoostProtocol.Mode.CLOSING, BoostProtocol.Operation.NONE), 4));
        assertTrue(session.hasCustody());
        assertNull(session.nextFrame(4, 0, true));
        // CLOSING still emits the paired coasting frame from its retained
        // session gear even when the UIE boost plan is unavailable.
        assertNotNull(session.nextFrame(4, session.gear(), true));
    }

    @Test
    void serialAndEpochMakeVelocityOperationsExactlyOnce() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(23L, 4, 490.0, true)));
        assertTrue(session.acceptState(BoostProtocol.state(23L, 0L, 1L, 4,
                BoostProtocol.Mode.READY, 5, 490.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE), 4));
        assertTrue(session.acceptState(state(23L, 5L, 9L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD), 4));
        assertNull(session.requestMode(true, 4));
        // The caller applies ADD only after this acceptance; replay is rejected.
        assertFalse(session.acceptState(state(23L, 5L, 9L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD), 4));
        assertFalse(session.acceptState(state(23L, 4L, 99L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.REPLACE), 4));

        assertTrue(session.acceptState(state(23L, 6L, 1L,
                BoostProtocol.Mode.SUSPENDED, BoostProtocol.Operation.STOP), 4));
        assertFalse(session.hasCustody());
        assertFalse(session.personalZeroG());
    }

    @Test
    void invalidOfferAndStateNeverCreateRemoteAuthority() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertFalse(session.acceptOffer(BoostProtocol.offer(1L, 0, 0.0, false)));
        assertFalse(session.acceptOffer(BoostProtocol.offer(2L, 0, 500.000001, true)));
        assertNull(session.requestMode(true, 0));

        assertTrue(session.acceptOffer(BoostProtocol.offer(1L, 0, 490.0, true)));
        BoostProtocol.Packet wrongWorld = BoostProtocol.state(1L, 0L, 1L, 1,
                BoostProtocol.Mode.ACTIVE, 5, 490.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        assertFalse(session.acceptState(wrongWorld, 0));
        assertFalse(session.hasCustody());
    }

    @Test
    void oversizedActiveCeilingIsRejectedBeforeCustody() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(41L, 4, 490.0, true)));
        BoostProtocol.Packet oversized = BoostProtocol.state(41L, 1L, 1L, 4,
                BoostProtocol.Mode.ACTIVE, 5, 500.000001, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        assertFalse(session.acceptState(oversized, 4));
        assertFalse(session.hasCustody());
    }

    @Test
    void onlyFreshMatchingStateIdentityClassifiesAnOversizedCeilingAsContentFault() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(51L, 4, 490.0, true)));
        BoostProtocol.Packet wrongNonce = BoostProtocol.state(52L, 1L, 2L, 4,
                BoostProtocol.Mode.ACTIVE, 5, 501.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        BoostProtocol.Packet wrongDimension = BoostProtocol.state(51L, 1L, 2L, 5,
                BoostProtocol.Mode.ACTIVE, 5, 501.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        assertFalse(session.matchesCurrentFreshState(wrongNonce, 4));
        assertFalse(session.matchesCurrentFreshState(wrongDimension, 4));

        assertTrue(session.acceptState(state(51L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        BoostProtocol.Packet duplicate = BoostProtocol.state(51L, 0L, 1L, 4,
                BoostProtocol.Mode.ACTIVE, 5, 501.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        BoostProtocol.Packet stale = BoostProtocol.state(51L, 0L, 0L, 4,
                BoostProtocol.Mode.ACTIVE, 5, 501.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        assertFalse(session.matchesCurrentFreshState(duplicate, 4));
        assertFalse(session.matchesCurrentFreshState(stale, 4));

        BoostProtocol.Packet freshOversized = BoostProtocol.state(51L, 1L, 2L, 4,
                BoostProtocol.Mode.ACTIVE, 5, 501.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.NONE);
        assertTrue(session.matchesCurrentFreshState(freshOversized, 4));
        assertFalse(session.acceptState(freshOversized, 4));
    }

    @Test
    void newEpochStartsFrameSequenceAtOneWithoutResettingControlSequence() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(31L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(31L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.requestMode(true, 4));
        assertTrue(session.acceptState(state(31L, 1L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.NONE), 4));
        assertEquals(1L, session.nextFrame(4, 5, false).sequence());
        assertEquals(2L, session.nextFrame(4, 5, false).sequence());

        assertTrue(session.acceptState(state(31L, 2L, 3L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.NONE), 4));
        assertEquals(1L, session.nextFrame(4, 5, false).sequence());
    }

    @Test
    void denialKeepsExplicitIntentButDoesNotRetryUntilAReadyBaselineIsClean() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(37L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(37L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.requestMode(true, 4));
        assertFalse(session.canRequestEnable());
        BoostProtocol.Packet denied = BoostProtocol.state(37L, 0L, 2L, 4,
                BoostProtocol.Mode.READY, 5, 490.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.UNSUPPORTED_STATE);
        assertTrue(session.acceptState(denied, 4));
        assertTrue(session.requestedIntent());
        assertNull(session.nextEligibleModeRequest(4));
        assertTrue(session.acceptState(state(37L, 0L, 3L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.nextEligibleModeRequest(4));
    }

    @Test
    void lateActiveAfterExplicitOffNeverReclaimsPersonalZeroG() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(61L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(61L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.requestMode(true, 4));
        assertNull(session.requestMode(false, 4));
        assertTrue(session.acceptState(state(61L, 2L, 2L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.ADD), 4));
        assertFalse(session.personalZeroG());
        assertTrue(session.acceptState(state(61L, 2L, 3L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.REPLACE), 4));
        assertFalse(session.personalZeroG());
        assertNotNull(session.nextEligibleModeRequest(4));
    }

    @Test
    void transportResetRetainsOnlyTheExplicitKeyIntent() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        session.requestMode(true, 4);
        session.reset();
        assertTrue(session.requestedIntent());
        assertTrue(session.acceptOffer(BoostProtocol.offer(62L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(62L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.nextEligibleModeRequest(4));
    }

    @Test
    void sameEpochRestrictionAndBudgetReasonLeaveAcceptedGIntentOn() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(63L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(63L, 0L, 1L,
                BoostProtocol.Mode.READY, BoostProtocol.Operation.NONE), 4));
        assertNotNull(session.requestMode(true, 4));
        BoostProtocol.Packet restrictedBudget = BoostProtocol.state(63L, 1L, 2L, 4,
                BoostProtocol.Mode.ACTIVE, 9, 448.0, BoostProtocol.Operation.NONE,
                Vec3.ZERO, BoostProtocol.Reason.BUDGET, true);
        assertTrue(session.acceptState(restrictedBudget, 4));
        assertTrue(session.personalZeroG());
        assertTrue(session.requestedIntent());
        assertTrue(session.restrictedCorridor());
    }

    @Test
    void frameCarriesFiniteContinuityHintWithoutChangingItsSettledFlag() {
        RemoteBoostSession session = new RemoteBoostSession(ignored -> true);
        assertTrue(session.acceptOffer(BoostProtocol.offer(64L, 4, 490.0, true)));
        assertTrue(session.acceptState(state(64L, 1L, 1L,
                BoostProtocol.Mode.ACTIVE, BoostProtocol.Operation.NONE), 4));
        Vec3 hint = new Vec3(3.0, -4.0, 12.0);
        BoostProtocol.Packet frame = session.nextFrame(4, 9, false, hint);
        assertEquals(hint, frame.velocity());
        assertFalse(frame.flag());
    }

    @Test
    void everyOwnedLogicalSampleHasOnePositionBearingPacketPlan() {
        assertEquals(RemoteBoostPacketPlan.Action.KEEP, RemoteBoostPacketPlan.action(true));
        assertEquals(RemoteBoostPacketPlan.Action.REPLACE_WITH_POSITION,
                RemoteBoostPacketPlan.action(false));
    }

    private static BoostProtocol.Packet state(long nonce, long epoch, long serial,
            BoostProtocol.Mode mode, BoostProtocol.Operation operation) {
        return BoostProtocol.state(nonce, epoch, serial, 4, mode, 5, 490.0,
                operation, new Vec3(12.0, -4.0, 1.0), BoostProtocol.Reason.NONE);
    }
}
