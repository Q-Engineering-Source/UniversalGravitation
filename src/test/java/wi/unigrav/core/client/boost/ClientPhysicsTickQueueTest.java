package wi.unigrav.core.client.boost;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import wi.unigrav.core.boost.RemoteBoostBoundedDispatcher;
import wi.unigrav.core.client.ClientPhysicsTickQueue;
import static org.junit.jupiter.api.Assertions.*;

class ClientPhysicsTickQueueTest {
    @Test void reservationsWaitForTickBoundaryAndRetainFifoAndCapacity() {
        var queue = new ClientPhysicsTickQueue();
        var dispatcher = new RemoteBoostBoundedDispatcher<Integer>(2, queue::schedule);
        var applied = new ArrayList<Integer>();
        dispatcher.submit(dispatcher.reserve(1, applied::add));
        dispatcher.submit(dispatcher.reserve(2, applied::add));
        assertTrue(applied.isEmpty(), "arrival/render frames must not apply physical state mid-tick");
        assertNull(dispatcher.reserve(3, applied::add), "waiting for tick still consumes the existing bounded capacity");
        queue.drain();
        assertEquals(List.of(1, 2), applied);
        var next = dispatcher.reserve(3, applied::add);
        assertNotNull(next);
        dispatcher.submit(next);
        dispatcher.invalidate();
        queue.drain();
        assertEquals(List.of(1, 2), applied, "stale-world tasks never install state");
    }

    @Test void arrivalsDuringDrainWaitUntilNextTick() {
        var queue = new ClientPhysicsTickQueue();
        var applied = new ArrayList<Integer>();
        queue.schedule(() -> { applied.add(1); queue.schedule(() -> applied.add(3)); });
        queue.schedule(() -> applied.add(2));
        queue.drain();
        assertEquals(List.of(1, 2), applied);
        queue.drain();
        assertEquals(List.of(1, 2, 3), applied);
    }

    @Test void throwingConsumerReleasesItsReservationWithoutDroppingFollowingTasks() {
        var queue = new ClientPhysicsTickQueue();
        var dispatcher = new RemoteBoostBoundedDispatcher<Integer>(2, queue::schedule);
        var applied = new ArrayList<Integer>();
        var failure = new IllegalStateException("install failure");
        dispatcher.submit(dispatcher.reserve(1, value -> { throw failure; }));
        dispatcher.submit(dispatcher.reserve(2, applied::add));
        assertSame(failure, assertThrows(IllegalStateException.class, queue::drain));
        var next = dispatcher.reserve(3, applied::add);
        assertNotNull(next);
        dispatcher.submit(next);
        queue.drain();
        assertEquals(List.of(2, 3), applied);
    }
}
