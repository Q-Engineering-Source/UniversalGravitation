package wi.unigrav.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static wi.unigrav.core.MotionTimingWindow.Signal.*;

class MotionTimingWindowTest {
    @Test void requestedSummaryIncludesNormalWindowsAndCapturesShortApplicationGaps() {
        var window = new MotionTimingWindow();
        window.interval(STATE_APPLY, 0);
        window.interval(STATE_APPLY, 100_000_000L);
        var report = window.poll(5_000_000_000L, true).orElseThrow();
        assertEquals(1, report.samples().get(STATE_APPLY).outliers());
        window.reset();
        window.interval(FRAME, 0);
        window.interval(FRAME, 16_000_000L);
        assertEquals(16, window.poll(5_000_000_000L, true).orElseThrow().samples().get(FRAME).maximumMs());
        assertTrue(window.poll(5_000_000_001L, true).isEmpty());
    }
    @Test void normalFramesAreSilentAndDoNotAccumulateUnboundedSamples() {
        var window = new MotionTimingWindow();
        for (long i = 0; i <= 1000; i++) {
            window.interval(FRAME, i * 16_000_000L);
            assertTrue(window.poll(i * 16_000_000L).isEmpty());
        }
    }

    @Test void spikesAreAggregatedOnlyOnceAtTheWindowBoundary() {
        var window = new MotionTimingWindow();
        window.interval(FRAME, 0);
        window.interval(FRAME, 80_000_000);
        window.interval(CLIENT_TICK, 0);
        window.interval(CLIENT_TICK, 50_000_000);
        window.duration(STATE_QUEUE, 120_000_000, 120_000_000);
        assertTrue(window.poll(4_999_999_999L).isEmpty());
        var report = window.poll(5_000_000_000L).orElseThrow();
        assertEquals(80, report.samples().get(FRAME).maximumMs(), 1e-9);
        assertEquals(1, report.samples().get(FRAME).outliers());
        assertEquals(0, report.samples().get(CLIENT_TICK).outliers());
        assertEquals(120, report.samples().get(STATE_QUEUE).maximumMs(), 1e-9);
        assertTrue(window.poll(5_000_000_001L).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> report.samples().clear());
    }

    @Test void resetDoesNotTurnMenusOrWorldReloadIntoAStutter() {
        var window = new MotionTimingWindow();
        window.interval(STATE_APPLY, 0);
        window.reset();
        window.interval(STATE_APPLY, 20_000_000_000L);
        window.interval(STATE_APPLY, 20_050_000_000L);
        assertTrue(window.poll(25_000_000_000L).isEmpty());
    }

    @Test void serverWorkAndCadenceAreSeparateMeasurements() {
        var window = new MotionTimingWindow();
        window.interval(SERVER_TICK, 0);
        window.interval(SERVER_TICK, 150_000_000);
        window.duration(SERVER_WORK, 130_000_000, 130_000_000);
        var report = window.poll(5_000_000_000L).orElseThrow();
        assertEquals(150, report.samples().get(SERVER_TICK).maximumMs(), 1e-9);
        assertEquals(130, report.samples().get(SERVER_WORK).maximumMs(), 1e-9);
    }
}
