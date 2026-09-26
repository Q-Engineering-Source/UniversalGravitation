package wi.unigrav.core.client.boost;

import org.junit.jupiter.api.Test;
import wi.unigrav.core.client.ClientMotionTrace;
import wi.unigrav.core.client.PlayerPhysicsClientSession.StateDisposition;
import static org.junit.jupiter.api.Assertions.*;

class ClientMotionTraceTest {
    @Test void countsActualApplicationSeparatelyFromConfirmedRetransmissions() {
        var trace = new ClientMotionTrace();
        trace.state(StateDisposition.APPLIED);
        trace.state(StateDisposition.RETRANSMISSION);
        trace.state(StateDisposition.INSTALL_REJECTED);
        trace.applied(3, 0, 0, 0);
        trace.applied(5, 0, .25, 0);
        var report = trace.drain();
        assertEquals(1, report.states().get(StateDisposition.APPLIED));
        assertEquals(1, report.states().get(StateDisposition.INSTALL_REJECTED));
        assertEquals(2, report.maximumRevisionStep());
        assertEquals(.25, report.maximumAcceptedStep());
        assertEquals(0, trace.drain().states().get(StateDisposition.APPLIED));
        trace.applied(6, 0, .5, 0);
        assertEquals(.25, trace.drain().maximumAcceptedStep(), "window drain retains continuous position baseline");
    }

    @Test void recordsRenderHistoryCollapseWithoutMovingThePlayer() {
        var trace = new ClientMotionTrace();
        trace.render(0, 0, 0, 0, 1, 0, .5F, 5);
        trace.render(0, 1, 0, 0, 1, 0, .75F, 5);
        var report = trace.drain();
        assertEquals(2, report.frames());
        assertEquals(1, report.zeroSpanFrames());
        assertEquals(1, report.maximumHistorySpan());
        assertEquals(.5, report.maximumRenderStep());
        assertEquals(1, report.movingZeroSpanFrames());
        assertEquals(5, report.maximumMotionMps());
        trace.reset();
        trace.applied(500, 10000, 0, 0);
        trace.render(10000, 0, 0, 10000, 0, 0, 0, 0);
        assertEquals(0, trace.drain().maximumAcceptedStep(), "new world must not count a teleport as movement");
    }
}
