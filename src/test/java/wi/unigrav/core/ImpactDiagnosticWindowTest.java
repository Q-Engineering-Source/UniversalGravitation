package wi.unigrav.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpactDiagnosticWindowTest {
    @Test void tickResetIsNotReportedAsStalledAndInvalidValuesStayExplicitlyUnknown() {
        var window = new ImpactDiagnosticWindow();
        var identity = new ImpactDiagnosticWindow.Identity("invalid", 1, "minecraft:rabbit", 0,
                Double.NaN, 64, 0);
        window.record(new ImpactDiagnosticWindow.Observation(identity, false, Double.POSITIVE_INFINITY,
                false, Double.NaN, false, Double.NaN, true, 120, 5, false, false, false, false));
        var report = window.drain().orElseThrow();
        assertEquals(0, report.ticksStalled());
        assertEquals(1, report.ticksDiscontinuous());
        assertEquals(1, report.impactSpeedUnknown());
        assertEquals(1, report.cachedMotionUnknown());
        assertEquals(1, report.positionDeltaUnknown());
        assertTrue(Double.isNaN(report.peak().identity().x()), "invalid position is not fabricated as origin");
        assertTrue(Double.isInfinite(report.peak().impactSpeedMps()));
    }

    @Test
    void aggregatesRequestedAndAcceptedHitsWhileKeepingTheFastestIdentityAcrossWorlds() {
        var window = new ImpactDiagnosticWindow();
        var rabbit = new ImpactDiagnosticWindow.Identity(
                "00000000-0000-0000-0000-000000000495", 495, "minecraft:rabbit", 0,
                10.0, 64.0, -3.0);
        var skeleton = new ImpactDiagnosticWindow.Identity(
                "00000000-0000-0000-0000-000000000007", 7, "minecraft:skeleton", -1,
                -4.0, 72.0, 8.0);

        window.record(new ImpactDiagnosticWindow.Observation(
                rabbit, true, 500.0, true, 495.0, true, 3.0, true, 120, 121,
                true, true, false, false));
        window.record(new ImpactDiagnosticWindow.Observation(
                skeleton, true, 30.0, true, 24.0, true, 20.0, true, 50, 51,
                false, false, true, true));

        var report = window.drain().orElseThrow();

        assertEquals(2, report.requestedHits());
        assertEquals(1, report.acceptedHits());
        assertEquals(1, report.rejectedHits());
        assertEquals(2, report.impactSpeed().count());
        assertEquals(500.0, report.impactSpeed().maximumMps(), 0.0);
        assertEquals(2, report.cachedMotion().count());
        assertEquals(495.0, report.cachedMotion().maximumMps(), 0.0);
        assertEquals(2, report.positionDelta().count());
        assertEquals(20.0, report.positionDelta().maximumMps(), 0.0);
        assertEquals(0, report.positionDeltaUnknown());
        assertEquals(2, report.ticksAdvanced());
        assertEquals(0, report.ticksStalled());
        assertEquals(1, report.onGround());
        assertEquals(1, report.collidedHorizontally());
        assertEquals(1, report.collidedVertically());
        assertEquals(rabbit, report.peak().identity());
        assertEquals(500.0, report.peak().impactSpeedMps(), 0.0);
        assertEquals(495.0, report.peak().cachedMotionMps(), 0.0);
        assertEquals(3.0, report.peak().positionDeltaMps(), 0.0);
        assertEquals("all-server-worlds", report.scope());
        assertTrue(window.drain().isEmpty());
    }

    @Test
    void marksPositionDeltaUnknownWhenTheEntityWasNotContinuouslyReseen() {
        var window = new ImpactDiagnosticWindow();
        var rabbit = new ImpactDiagnosticWindow.Identity(
                "00000000-0000-0000-0000-000000000495", 495, "minecraft:rabbit", 0,
                10.0, 64.0, -3.0);

        window.record(new ImpactDiagnosticWindow.Observation(
                rabbit, true, 500.0, true, 495.0, false, 0.0, true, 120, 120,
                false, false, false, false));

        var report = window.drain().orElseThrow();

        assertEquals(1, report.requestedHits());
        assertEquals(0, report.acceptedHits());
        assertEquals(1, report.rejectedHits());
        assertEquals(1, report.positionDeltaUnknown());
        assertEquals(0, report.positionDelta().count());
        assertEquals(0, report.ticksAdvanced());
        assertEquals(1, report.ticksStalled());
        assertEquals(0, report.ticksDiscontinuous());
        assertFalse(report.peak().positionDeltaKnown());
    }

    @Test
    void retainsMeasuredZeroWorldTickDisplacementWhenEntityTicksStall() {
        var window = new ImpactDiagnosticWindow();
        var rabbit = new ImpactDiagnosticWindow.Identity(
                "00000000-0000-0000-0000-000000000495", 495, "minecraft:rabbit", 0,
                10.0, 64.0, -3.0);

        window.record(new ImpactDiagnosticWindow.Observation(
                rabbit, true, 495.0, true, 495.0, true, 0.0, true, 120, 120,
                true, false, false, false));

        var report = window.drain().orElseThrow();

        assertEquals(1, report.positionDelta().count());
        assertEquals(0.0, report.positionDelta().maximumMps(), 0.0);
        assertEquals(0, report.positionDeltaUnknown());
        assertEquals(1, report.ticksStalled());
        assertTrue(report.peak().positionDeltaKnown());
    }
}
