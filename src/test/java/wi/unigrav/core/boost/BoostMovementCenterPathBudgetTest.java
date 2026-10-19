package wi.unigrav.core.boost;

import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.*;

class BoostMovementCenterPathBudgetTest {
    private static final Vec3 ZERO = new Vec3(0, 0, 0);
    private static BoostMovementBudget budget() { return new BoostMovementBudget(100, 0, ZERO); }

    @Test void closedCenterPathMustSpendTravelledDistance() {
        var b = budget();
        double before = b.snapshot().distanceCredit();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 2);
        assertTrue(ticket.accepted());
        assertEquals(before - 2, b.snapshot().distanceCredit());
        assertTrue(b.commitCenterPath(ticket, ZERO, ZERO, 2));
        assertEquals(before - 2, b.snapshot().distanceCredit(), "closed path does not refund spent distance");
    }

    @Test void zeroCenterTravelCanChangeTransportWithoutMintingCredit() {
        var b = budget();
        var transport = new Vec3(0, -3, 0);
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 0);
        assertTrue(b.commitCenterPath(ticket, ZERO, transport, 0));
        assertEquals(transport, b.position());
        assertEquals(5, b.snapshot().distanceCredit());
        assertEquals(1e-4, b.snapshot().roundingCredit());
        assertEquals(0, b.snapshot().lastTick());
    }

    @Test void grantsUseProposalCapAndActualCreditWithoutRoundingReserve() {
        var b = budget();
        var first = b.reserveCenterPath(1, 0, ZERO, ZERO, 100);
        assertEquals(5, first.grant());
        b.abortCenterPath(first);
        var second = b.reserveCenterPath(2, 0, ZERO, ZERO, 100);
        assertEquals(0, second.grant());
        assertTrue(b.commitCenterPath(second, ZERO, ZERO, 0));
        assertEquals(0, b.snapshot().distanceCredit());
        assertEquals(1e-4, b.snapshot().roundingCredit());
    }

    @Test void abortAndRebaseDoNotRefundAndOnlyTimeAdvanceRefills() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 3);
        b.abortCenterPath(ticket);
        assertEquals(2, b.snapshot().distanceCredit());
        var next = b.reserveCenterPath(2, 0, ZERO, ZERO, 1);
        b.rebase(new Vec3(7, 0, 0), 0);
        assertEquals(1, b.snapshot().distanceCredit());
        assertFalse(b.commitCenterPath(next, ZERO, ZERO, 0));
        b.advance(1);
        assertEquals(6, b.snapshot().distanceCredit());
    }

    @Test void pendingCenterExcludesBothLegacyModesAndNullAbortCannotClearIt() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 1);
        assertTrue(b.snapshot().pending());
        assertEquals(BoostMovementBudget.Failure.PENDING, b.prepare(2, 0, ZERO).failure());
        assertEquals(BoostMovementBudget.Failure.PENDING, b.prepareClipped(2, 0, ZERO).failure());
        b.abort(null);
        assertTrue(b.snapshot().pending());
        assertTrue(b.commitCenterPath(ticket, ZERO, ZERO, 0));
    }

    @Test void pendingLegacyModesExcludeCenterReservations() {
        for (boolean clipped : new boolean[] {false, true}) {
            var b = budget();
            var ticket = clipped ? b.prepareClipped(1, 0, ZERO) : b.prepare(1, 0, ZERO);
            assertEquals(BoostMovementBudget.Failure.PENDING, b.reserveCenterPath(2, 0, ZERO, ZERO, 1).failure());
            b.abort(ticket);
            assertTrue(b.reserveCenterPath(2, 0, ZERO, ZERO, 0).accepted());
        }
    }

    @Test void exactTicketIdentityAndReplayAreRejectedWithoutClearingPending() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 1);
        var forged = new BoostMovementBudget.CenterPathAdmission(ticket.sequence(), ticket.serverTick(),
                ticket.transportOrigin(), ticket.centerOrigin(), ticket.grant(), ticket.failure());
        assertFalse(b.commitCenterPath(forged, ZERO, ZERO, 0));
        b.abortCenterPath(forged);
        assertTrue(b.snapshot().pending());
        assertTrue(b.commitCenterPath(ticket, ZERO, ZERO, 0));
        assertFalse(b.commitCenterPath(ticket, ZERO, ZERO, 0));
        assertEquals(BoostMovementBudget.Failure.SEQUENCE, b.reserveCenterPath(1, 0, ZERO, ZERO, 0).failure());
    }

    @Test void advancingTickRejectsOldCommitButExactAbortSettlesSpentTicket() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 2);
        b.advance(1);
        double credit = b.snapshot().distanceCredit();
        assertFalse(b.commitCenterPath(ticket, ZERO, new Vec3(3, 0, 0), 0));
        assertEquals(ZERO, b.position());
        b.abortCenterPath(ticket);
        assertFalse(b.snapshot().pending());
        assertEquals(credit, b.snapshot().distanceCredit());
        assertEquals(BoostMovementBudget.Failure.TIME, b.reserveCenterPath(2, 0, ZERO, ZERO, 0).failure());
    }

    @Test void confirmedCenterAndTransportOriginsRejectStaleValues() {
        var b = budget();
        var center = new Vec3(1, 0, 0);
        var transport = new Vec3(0, -1, 0);
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 1);
        assertTrue(b.commitCenterPath(ticket, center, transport, 1));
        assertFalse(b.reserveCenterPath(2, 100, ZERO, center, 0).accepted());
        assertFalse(b.reserveCenterPath(2, 100, transport, ZERO, 0).accepted());
        assertEquals(0, b.snapshot().lastTick(), "bad origin cannot refill time");
        assertTrue(b.reserveCenterPath(2, 0, transport, center, 0).accepted());
    }

    @Test void failedReservationDoesNotConfirmCenterAndLegacyCommitInvalidatesMapping() {
        var b = budget();
        var arbitrary = new Vec3(20, 0, 0);
        var ticket = b.reserveCenterPath(1, 0, ZERO, arbitrary, 0);
        assertFalse(b.commitCenterPath(ticket, ZERO, ZERO, 0));
        b.abortCenterPath(ticket);
        var valid = b.reserveCenterPath(2, 0, ZERO, ZERO, 0);
        assertTrue(b.commitCenterPath(valid, ZERO, ZERO, 0));
        var nativeTicket = b.prepareClipped(3, 0, new Vec3(1, 0, 0));
        assertTrue(b.commitNative(nativeTicket, new Vec3(1, 0, 0)));
        assertTrue(b.reserveCenterPath(4, 0, b.position(), arbitrary, 0).accepted());
    }

    @Test void invalidPathBoundsAndVectorsCannotMutatePositionOrCommit() {
        for (double value : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var b = budget();
            assertFalse(b.reserveCenterPath(1, 0, ZERO, ZERO, value).accepted());
            assertEquals(5, b.snapshot().distanceCredit());
            var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 1);
            assertFalse(b.commitCenterPath(ticket, ZERO, ZERO, value));
            assertFalse(b.commitCenterPath(ticket, new Vec3(Double.NaN, 0, 0), ZERO, 0));
            assertFalse(b.commitCenterPath(ticket, ZERO, new Vec3(0, Double.POSITIVE_INFINITY, 0), 0));
            assertEquals(ZERO, b.position());
            assertTrue(b.snapshot().pending());
        }
    }

    @Test void pathMustCoverActualNetDistanceAndStayWithinGrant() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 2);
        assertFalse(b.commitCenterPath(ticket, new Vec3(1, 0, 0), ZERO, .5));
        assertFalse(b.commitCenterPath(ticket, ZERO, ZERO, Math.nextUp(2.0)));
        assertEquals(ZERO, b.position());
        assertTrue(b.commitCenterPath(ticket, new Vec3(1, 0, 0), ZERO, 2));
        assertEquals(3, b.snapshot().distanceCredit());
    }

    @Test void exactBinary64SquaredCheckHasNoRoundedSqrtPermit() {
        var b = budget();
        var ticket = b.reserveCenterPath(1, 0, ZERO, ZERO, 2);
        var endpoint = new Vec3(1, 1, 0);
        assertFalse(b.commitCenterPath(ticket, endpoint, ZERO, Math.nextDown(Math.sqrt(2))));
        assertTrue(b.commitCenterPath(ticket, endpoint, ZERO, Math.nextUp(Math.sqrt(2))));
    }

    @Test void finiteCoordinateSubtractionOverflowCannotCreateDistancePermit() {
        var b = budget();
        var from = new Vec3(-Double.MAX_VALUE, 0, 0);
        var ticket = b.reserveCenterPath(1, 0, ZERO, from, 5);
        assertFalse(b.commitCenterPath(ticket, new Vec3(Double.MAX_VALUE, 0, 0), ZERO, 5));
        assertEquals(ZERO, b.position());
        assertEquals(0, b.snapshot().distanceCredit());
    }
}