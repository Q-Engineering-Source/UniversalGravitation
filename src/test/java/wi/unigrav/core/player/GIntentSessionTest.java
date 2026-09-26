package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class GIntentSessionTest {
    private static final GIntentProtocol.Key KEY = new GIntentProtocol.Key(new UUID(1, 2), new UUID(3, 4), 0);

    @Test
    void negotiatedExplicitIntentBecomesKnownWithoutBorrowingClientState() {
        var session = new GIntentSession(KEY);
        assertTrue(session.observe(true).isEmpty());
        session.receive(wire(GIntentProtocol.Kind.HELLO, 0, GIntentProtocol.State.UNKNOWN), true).orElseThrow();
        assertTrue(session.observe(true).isEmpty(), "HELLO is not an implicit G-off request");
        var acknowledgement = session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), true)
                .orElseThrow();
        assertEquals(GIntentProtocol.State.ON, acknowledgement.state());
        assertEquals(true, session.observe(true).orElseThrow());
    }

    @Test
    void unnegotiatedAndForeignScopeRequestsDoNotEstablishIntent() {
        var session = new GIntentSession(KEY);
        assertTrue(session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), true).isEmpty());
        var foreign = new GIntentProtocol.Key(new UUID(8, 9), KEY.playerId(), 0);
        assertTrue(session.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.HELLO, foreign, 0,
                GIntentProtocol.State.UNKNOWN), true).isEmpty());
        assertTrue(session.observe(true).isEmpty());
        assertFalse(session.negotiated());
    }

    @Test
    void explicitOffIsImmediateAndStaleOnCannotRestoreIt() {
        var session = negotiated();
        session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), true).orElseThrow();
        session.receive(wire(GIntentProtocol.Kind.INTENT, 2, GIntentProtocol.State.OFF), true).orElseThrow();
        assertTrue(session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), true).isEmpty());
        assertTrue(session.receive(wire(GIntentProtocol.Kind.INTENT, 2, GIntentProtocol.State.ON), true).isEmpty());
        assertEquals(false, session.observe(true).orElseThrow());
        assertEquals(2, session.revision());
    }

    @Test
    void duplicateIntentAndHelloRetryAcknowledgeWithoutChangingRevision() {
        var session = negotiated();
        var on = wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON);
        var first = session.receive(on, true).orElseThrow();
        assertEquals(first, session.receive(on, true).orElseThrow());
        assertEquals(first, session.receive(wire(GIntentProtocol.Kind.HELLO, 0, GIntentProtocol.State.UNKNOWN), true).orElseThrow());
        assertEquals(1, session.revision());
    }

    @Test
    void survivalCannotObtainOnAndEligibilityLossDoesNotRewriteAcceptedKeyIntent() {
        var session = negotiated();
        assertTrue(session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), false).isEmpty());
        assertTrue(session.observe(false).isEmpty());
        assertEquals(0, session.revision());
        session.receive(wire(GIntentProtocol.Kind.INTENT, 1, GIntentProtocol.State.ON), true).orElseThrow();
        assertTrue(session.observe(false).isEmpty());
        assertEquals(true, session.observe(true).orElseThrow());
        session.receive(wire(GIntentProtocol.Kind.INTENT, 2, GIntentProtocol.State.OFF), false).orElseThrow();
        assertEquals(false, session.observe(false).orElseThrow());
    }

    @Test
    void wrongPlayerAndDimensionAndServerOnlyPacketsCannotWriteIntent() {
        var session = negotiated();
        for (var foreign : new GIntentProtocol.Key[] {
                new GIntentProtocol.Key(KEY.sessionId(), new UUID(7, 8), 0),
                new GIntentProtocol.Key(KEY.sessionId(), KEY.playerId(), -1)}) {
            assertTrue(session.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.INTENT,
                    foreign, 1, GIntentProtocol.State.ON), true).isEmpty());
        }
        assertTrue(session.receive(wire(GIntentProtocol.Kind.STATE, 1, GIntentProtocol.State.ON), true).isEmpty());
        assertTrue(session.receive(wire(GIntentProtocol.Kind.OFFER, 0, GIntentProtocol.State.UNKNOWN), true).isEmpty());
        assertTrue(session.observe(true).isEmpty());
    }

    private static GIntentSession negotiated() {
        var session = new GIntentSession(KEY);
        session.receive(wire(GIntentProtocol.Kind.HELLO, 0, GIntentProtocol.State.UNKNOWN), true).orElseThrow();
        return session;
    }

    private static GIntentProtocol.Packet wire(GIntentProtocol.Kind kind, long sequence, GIntentProtocol.State state) {
        return GIntentProtocol.decode(GIntentProtocol.encode(new GIntentProtocol.Packet(kind, KEY, sequence, state)));
    }
}
