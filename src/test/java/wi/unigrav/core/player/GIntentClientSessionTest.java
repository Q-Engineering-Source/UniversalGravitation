package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class GIntentClientSessionTest {
    private static final GIntentProtocol.Key KEY = new GIntentProtocol.Key(new UUID(1, 2), new UUID(3, 4), 0);

    @Test
    void clientAndServerExchangeEncodedExplicitIntentWithoutSharingState() {
        var client = new GIntentClientSession(KEY);
        var server = new GIntentSession(KEY);
        assertTrue(client.request(true).isEmpty());
        assertEquals(client.hello(), client.retry().orElseThrow());
        client.receive(wire(server.receive(wire(client.hello()), true).orElseThrow()));
        assertTrue(client.acknowledgedIntent().isEmpty());
        var on = client.request(true).orElseThrow();
        assertTrue(client.acknowledgedIntent().isEmpty());
        assertEquals(on, client.retry().orElseThrow());
        client.receive(wire(server.receive(wire(on), true).orElseThrow()));
        assertEquals(true, client.acknowledgedIntent().orElseThrow());
        assertTrue(client.retry().isEmpty());
        assertTrue(client.request(true).isEmpty());
        var off = client.request(false).orElseThrow();
        client.receive(wire(server.receive(wire(off), true).orElseThrow()));
        assertEquals(false, client.acknowledgedIntent().orElseThrow());
        assertEquals(false, server.observe(true).orElseThrow());
    }

    @Test
    void lostAcknowledgementIsRetriedWithoutASecondIntentRevision() {
        var client = new GIntentClientSession(KEY);
        var server = new GIntentSession(KEY);
        client.receive(server.receive(client.hello(), true).orElseThrow());
        var on = client.request(true).orElseThrow();
        var lost = server.receive(on, true).orElseThrow();
        var retry = client.retry().orElseThrow();
        assertEquals(on, retry);
        assertEquals(lost, server.receive(retry, true).orElseThrow());
        client.receive(lost);
        assertTrue(client.retry().isEmpty());
        assertEquals(1, server.revision());
    }

    @Test
    void staleForeignAndUnsentAcknowledgementsCannotOverrideTheLatestState() {
        var client = new GIntentClientSession(KEY);
        var server = new GIntentSession(KEY);
        client.receive(server.receive(client.hello(), true).orElseThrow());
        var on = server.receive(client.request(true).orElseThrow(), true).orElseThrow();
        var off = server.receive(client.request(false).orElseThrow(), true).orElseThrow();
        client.receive(off);
        client.receive(on);
        client.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.STATE, KEY, 500, GIntentProtocol.State.ON));
        client.receive(new GIntentProtocol.Packet(GIntentProtocol.Kind.STATE,
                new GIntentProtocol.Key(new UUID(9, 9), KEY.playerId(), 0), 2, GIntentProtocol.State.ON));
        assertEquals(false, client.acknowledgedIntent().orElseThrow());
    }

    @Test
    void newGSessionDoesNotInheritAnOldAcknowledgementOrPhysicalAuthority() {
        var client = new GIntentClientSession(KEY);
        var server = new GIntentSession(KEY);
        client.receive(server.receive(client.hello(), true).orElseThrow());
        var old = server.receive(client.request(true).orElseThrow(), true).orElseThrow();
        var fresh = new GIntentClientSession(new GIntentProtocol.Key(new UUID(8, 9), KEY.playerId(), 0));
        fresh.receive(old);
        assertTrue(fresh.acknowledgedIntent().isEmpty());
        assertTrue(fresh.request(true).isEmpty());
    }

    private static GIntentProtocol.Packet wire(GIntentProtocol.Packet packet) {
        return GIntentProtocol.decode(GIntentProtocol.encode(packet));
    }

    @Test
    void ordinarySurvivalGetsExplicitOffWithoutLosingThePersistentCreativeChoice() {
        var client = new GIntentClientSession(KEY);
        var server = new GIntentSession(KEY);
        client.receive(server.receive(client.hello(), false).orElseThrow());
        boolean persistentChoice = true;
        var effectiveOff = client.requestEffective(persistentChoice, false).orElseThrow();
        assertEquals(GIntentProtocol.State.OFF, effectiveOff.state());
        client.receive(server.receive(effectiveOff, false).orElseThrow());
        assertEquals(false, server.observe(false).orElseThrow());
        var effectiveOn = client.requestEffective(persistentChoice, true).orElseThrow();
        assertEquals(GIntentProtocol.State.ON, effectiveOn.state());
        client.receive(server.receive(effectiveOn, true).orElseThrow());
        assertEquals(true, server.observe(true).orElseThrow());
    }
}
