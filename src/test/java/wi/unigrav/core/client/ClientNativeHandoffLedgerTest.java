package wi.unigrav.core.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientNativeHandoffLedgerTest {
    @Test void actualReturnedNaturalPositionPreventsSameTickSupplement() {
        var ledger = new ClientNativeHandoffLedger();
        ledger.positioned(4);
        ledger.positioned(4);
        assertFalse(ledger.advance(false, true, 4));
        assertTrue(ledger.advance(false, true, 5));
        assertEquals(0, ledger.submissions());
    }
    @Test void twelveReturnedSupplementalSubmissionsTerminateAndReportOnce() {
        var ledger = new ClientNativeHandoffLedger();
        for (int tick = 1; tick <= 12; tick++) {
            assertTrue(ledger.advance(false, true, tick));
            ledger.submitted();
        }
        assertFalse(ledger.advance(false, true, 13));
        assertEquals(12, ledger.submissions());
        assertTrue(ledger.reportExhaustion());
        assertFalse(ledger.reportExhaustion());
    }
    @Test void temporaryIneligibilityConsumesAgeButPausedTimeDoesNot() {
        var ledger = new ClientNativeHandoffLedger();
        for (int tick = 0; tick < 100; tick++) assertFalse(ledger.advance(true, true, tick));
        assertEquals(0, ledger.age());
        for (int tick = 1; tick < 80; tick++) assertFalse(ledger.advance(false, false, tick));
        assertFalse(ledger.advance(false, true, 80));
        assertTrue(ledger.reportExhaustion());
        assertFalse(ledger.advance(false, true, 81));
        assertEquals(80, ledger.age());
    }
    @Test void failureOrOwnershipEndsScopeWithoutManufacturingSubmissionOrExhaustion() {
        var ledger = new ClientNativeHandoffLedger();
        assertTrue(ledger.advance(false, true, 1));
        ledger.stop();
        assertFalse(ledger.advance(false, true, 2));
        assertEquals(0, ledger.submissions());
        assertFalse(ledger.reportExhaustion());
    }
    @Test void naturalTrafficAlsoConsumesFiniteLifetime() {
        var ledger = new ClientNativeHandoffLedger();
        for (int tick = 1; tick <= 80; tick++) {
            ledger.positioned(tick);
            assertFalse(ledger.advance(false, true, tick));
        }
        assertTrue(ledger.reportExhaustion());
        assertEquals(0, ledger.submissions());
    }
}
