package wi.unigrav.core.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemoteBoostCorridorTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    void heightBandIsInclusiveAndTerrainIndependent() {
        assertFalse(RemoteBoostCorridor.isRestricted(-Double.MIN_VALUE));
        assertTrue(RemoteBoostCorridor.isRestricted(0));
        assertTrue(RemoteBoostCorridor.isRestricted(256));
        assertFalse(RemoteBoostCorridor.isRestricted(256 + Math.ulp(256)));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBoostCorridor.isRestricted(Double.NaN));
    }

    @Test
    void linearDecayHasExactCarrierAndIntegratedDistanceAtKeyTimes() {
        assertDecay(448, 23.45, RemoteBoostCorridor.decay(490, 70, 840, 0.05));
        assertDecay(280, 96.25, RemoteBoostCorridor.decay(490, 70, 840, 0.25));
        assertDecay(70, 140, RemoteBoostCorridor.decay(490, 70, 840, 0.5));
    }

    @Test
    void decaySubdivisionsAreEquivalentToOneHalfSecondScalarEnvelope() {
        double tenthsOfSecond = integrateSubsteps(0.1, 5);
        double twentiethsOfSecond = integrateSubsteps(0.05, 10);
        RemoteBoostCorridor.Decay direct = RemoteBoostCorridor.decay(490, 70, 840, 0.5);

        assertEquals(direct.distanceMeters(), tenthsOfSecond, EPSILON);
        assertEquals(direct.distanceMeters(), twentiethsOfSecond, EPSILON);
        assertEquals(direct.endCeilingMps(), 70, EPSILON);
    }

    @Test
    void targetNeverRaisesLowSelectedSpeeds() {
        for (double selected : new double[] {20, 40, 60, 70}) {
            assertEquals(selected, RemoteBoostCorridor.propulsionTarget(selected, 490, true), EPSILON);
            assertEquals(selected, RemoteBoostCorridor.propulsionTarget(selected, 490, false), EPSILON);
        }
        assertEquals(70, RemoteBoostCorridor.propulsionTarget(490, 490, true), EPSILON);
        assertEquals(40, RemoteBoostCorridor.propulsionTarget(490, 40, true), EPSILON);
    }

    @Test
    void decayCannotRaiseStartBelowFloorAndRejectsNonFiniteInputs() {
        RemoteBoostCorridor.Decay belowFloor = RemoteBoostCorridor.decay(40, 70, 840, 1);
        assertDecay(40, 40, belowFloor);
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBoostCorridor.decay(490, 70, Double.NaN, 0.05));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBoostCorridor.decay(490, 70, 840, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBoostCorridor.propulsionTarget(Double.POSITIVE_INFINITY, 490, false));
    }

    private static double integrateSubsteps(double seconds, int count) {
        double carrier = 490;
        double distance = 0;
        for (int index = 0; index < count; index++) {
            RemoteBoostCorridor.Decay step = RemoteBoostCorridor.decay(carrier, 70, 840, seconds);
            carrier = step.endCeilingMps();
            distance += step.distanceMeters();
        }
        return distance;
    }

    private static void assertDecay(double ceiling, double distance, RemoteBoostCorridor.Decay decay) {
        assertEquals(ceiling, decay.endCeilingMps(), EPSILON);
        assertEquals(distance, decay.distanceMeters(), EPSILON);
    }
}
