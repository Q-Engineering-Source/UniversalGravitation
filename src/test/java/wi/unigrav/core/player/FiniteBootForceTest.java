package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldProfile;

class FiniteBootForceTest {
    @Test
    void exactCaptureRangeDecisionSurvivesAnEnclosingRoundedUpperGap() {
        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(10, 0.5, 3), 1.5, 4);
        var gap = new BootSurfaceGeometry.Gap(Math.nextDown(1.5), Math.nextUp(1.5));
        var candidate = new StandingConstraints.BootCandidate(surface, gap, BootSurfaceGeometry.Range.IN_RANGE,
                new Vec3d(0, -1, 0), tier);
        assertEquals(gap, candidate.gap());
        assertTrue(candidate.isEffective());
        assertTrue(candidate.pullMagnitude() <= 6.0 && candidate.pullMagnitude() > 5.99);
    }

    @Test
    void exactFalloffCutoffIsProvenZeroRatherThanUnknown() {
        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(32, 0.5, 1), 1.5, 4);
        var candidate = new StandingConstraints.BootCandidate(surface, new BootSurfaceGeometry.Gap(1, 1),
                BootSurfaceGeometry.Range.IN_RANGE, new Vec3d(0, -1, 0), tier);
        assertFalse(candidate.hasUnresolvedStrength());
        assertFalse(candidate.isEffective());
        assertEquals(0.0, candidate.pullMagnitude());
    }

    @Test
    void uncertainFalloffCutoffDoesNotBecomeAbsenceOrRemoveTheRememberedReference() {
        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(32, 0.5, 1), 1.5, 4);
        var candidate = new StandingConstraints.BootCandidate(surface,
                new BootSurfaceGeometry.Gap(Math.nextDown(1.0), 1.0), BootSurfaceGeometry.Range.IN_RANGE,
                new Vec3d(0, -1, 0), tier);
        var previous = StandingConstraints.State.attached(new StandingConstraints.BootMemory(surface, tier),
                CardinalFrame.Y_POS, surface);
        assertThrows(IllegalStateException.class, candidate::pullMagnitude);
        var evidence = new StandingConstraints.Evidence(StandingConstraints.Availability.AVAILABLE,
                Vec3.ZERO, Vec3.ZERO, Map.of(), List.of(candidate), Optional.empty());

        var result = StandingConstraints.resolve(previous, new StandingConstraints.Request(2, false, evidence));

        assertEquals(StandingConstraints.ConstraintPresence.UNAVAILABLE, result.presence());
        assertEquals(previous.bootMemory(), result.nextState().bootMemory());
        assertEquals(previous.acceptedReference(), result.nextState().acceptedReference());
        assertTrue(result.authoritativeForce().isEmpty());
        assertTrue(result.actualSupport().isEmpty());
    }

    @Test
    void finiteEdgePullKeepsTangentialDirectionWhileReferenceStaysCardinal() {
        var surface = new StandingConstraints.Surface(0, 0, 0, Face.UP);
        var tier = new StandingConstraints.BootTier(new FloorFieldProfile(10, 1, 2), 1.5, 4);
        var candidate = new StandingConstraints.BootCandidate(surface, new BootSurfaceGeometry.Gap(0.5, 0.5),
                BootSurfaceGeometry.Range.IN_RANGE, new Vec3d(-0.6, -0.8, 0), tier);
        var evidence = new StandingConstraints.Evidence(StandingConstraints.Availability.AVAILABLE,
                new Vec3(2, 0, 0), new Vec3(0, 0, 3), Map.of(), List.of(candidate), Optional.empty());

        var result = StandingConstraints.resolve(StandingConstraints.State.initial(),
                new StandingConstraints.Request(1, false, evidence));

        assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
        assertEquals(new Vec3(-6, -8, 0), result.authoritativeForce().orElseThrow().bootAcceleration());
        assertEquals(new Vec3(-4, -8, 3), result.authoritativeForce().orElseThrow().combinedAcceleration());
        assertTrue(result.actualSupport().isEmpty());
    }
}
