package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteBoostIncidentLogGateTest {
    @Test
    void reportsFirstAttemptAndCoalescesRepeatedAttemptsAcrossTicks() {
        var gate = new RemoteBoostIncidentLogGate(20);
        assertTrue(gate.tryAcquire(0));
        assertEquals(0, gate.takeSuppressed());
        for (int tick = 0; tick < 20; tick++) {
            for (int attempt = 0; attempt < 8; attempt++) {
                assertFalse(gate.tryAcquire(tick));
            }
        }
        assertTrue(gate.tryAcquire(20));
        assertEquals(160, gate.takeSuppressed());
        assertEquals(0, gate.takeSuppressed());
        assertFalse(gate.tryAcquire(20));
        assertFalse(gate.tryAcquire(39));
        assertTrue(gate.tryAcquire(40));
        assertEquals(2, gate.takeSuppressed());
    }

    @Test
    void independentSessionsDoNotSuppressEachOther() {
        var first = new RemoteBoostIncidentLogGate(20);
        var second = new RemoteBoostIncidentLogGate(20);
        assertTrue(first.tryAcquire(100));
        assertFalse(first.tryAcquire(101));
        assertTrue(second.tryAcquire(101));
        assertEquals(0, second.takeSuppressed());
        assertEquals(1, first.takeSuppressed());
    }

    @Test
    void rejectsInvalidIntervalsAndBackwardsTicks() {
        assertThrows(IllegalArgumentException.class, () -> new RemoteBoostIncidentLogGate(0));
        var gate = new RemoteBoostIncidentLogGate(20);
        assertThrows(IllegalArgumentException.class, () -> gate.tryAcquire(-1));
        assertTrue(gate.tryAcquire(10));
        assertThrows(IllegalArgumentException.class, () -> gate.tryAcquire(9));
    }
}
