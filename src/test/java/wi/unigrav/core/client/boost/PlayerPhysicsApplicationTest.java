package wi.unigrav.core.client.boost;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real application callback, not merely the received STATE metadata. */
class PlayerPhysicsApplicationTest {
    @Test void wireIdentityIsUnavailableUntilTheRealVelocityWriteSucceeds() {
        AtomicReference<Vec3> velocity = new AtomicReference<>(Vec3.ZERO);
        AtomicInteger writes = new AtomicInteger();
        var session = new RemoteBoostSession(packet -> {
            writes.incrementAndGet();
            velocity.set(velocity.get().add(packet.velocity()));
            return true;
        });
        session.acceptOffer(BoostProtocol.offer(11, 0, 70, true));
        assertTrue(session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from).isEmpty());
        var add = state(1, BoostProtocol.Operation.ADD);
        assertTrue(session.acceptState(add, 0));
        assertEquals(new Vec3(2, 0, 0), velocity.get());
        var id = session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from).orElseThrow();
        assertEquals(new PlayerPhysicsProtocol.OperationId(11, 1, 1, BoostProtocol.Operation.ADD), id.state());
        assertEquals(id.state(), id.lastVelocityOperation().orElseThrow());
        assertFalse(session.acceptState(add, 0));
        assertEquals(1, writes.get());
    }

    @Test void failedAddAndLaterNoneDoNotFabricateAppliedIdentity() {
        var session = new RemoteBoostSession(packet -> packet.operation() != BoostProtocol.Operation.ADD);
        session.acceptOffer(BoostProtocol.offer(11, 0, 70, true));
        assertFalse(session.acceptState(state(1, BoostProtocol.Operation.ADD), 0));
        assertTrue(session.acceptState(state(2, BoostProtocol.Operation.NONE), 0));
        assertTrue(session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from).isEmpty());
        assertTrue(session.acceptState(state(3, BoostProtocol.Operation.REPLACE), 0));
        var id = session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from).orElseThrow();
        assertEquals(3, id.lastVelocityOperation().orElseThrow().serial());
    }

    @Test void lifetimeInvalidatedInsideTheWriteCannotYieldAppliedIdentity() {
        AtomicReference<RemoteBoostSession> current = new AtomicReference<>();
        var session = new RemoteBoostSession(packet -> {
            current.get().invalidateApplication();
            return true;
        });
        current.set(session);
        session.acceptOffer(BoostProtocol.offer(11, 0, 70, true));
        assertFalse(session.acceptState(state(1, BoostProtocol.Operation.REPLACE), 0));
        assertTrue(session.applicationReceipt().map(PlayerPhysicsProtocol.ApplicationId::from).isEmpty());
    }

    private static BoostProtocol.Packet state(long serial, BoostProtocol.Operation operation) {
        return BoostProtocol.state(11, 1, serial, 0, BoostProtocol.Mode.ACTIVE, 5, 70,
                operation, new Vec3(2, 0, 0), BoostProtocol.Reason.NONE);
    }
}
