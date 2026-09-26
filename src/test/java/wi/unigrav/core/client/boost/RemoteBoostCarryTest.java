package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.RemoteBoostCorridor;

/** Pure local re-entry envelope tests; no STATE packet can advance this reducer. */
class RemoteBoostCarryTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void inclusiveLocalBandKeepsLowVelocityAndDoesNotInstantlyTrimEntry() {
        RemoteBoostCarry carry = new RemoteBoostCarry();
        assertEquals(70.0, carry.sample(490.0, 40.0, true, 0.05), EPSILON);
        assertEquals(70.0, carry.sample(490.0, 60.0, true, 0.05), EPSILON);
        assertEquals(70.0, carry.sample(490.0, 70.0, true, 0.05), EPSILON);

        carry.clear();
        assertEquals(490.0, carry.sample(490.0, 490.0, true, 0.05), EPSILON);

        carry.clear();
        assertEquals(40.0, carry.sample(40.0, 30.0, true, 0.05), EPSILON);
    }

    @Test
    void reentryDecaysLinearlyFrom490To70WithoutDirectionPolicy() {
        RemoteBoostCarry carry = new RemoteBoostCarry();
        assertEquals(490.0, carry.sample(490.0, 490.0, true, 0.05), EPSILON);
        assertEquals(448.0, carry.sample(490.0, 490.0, true, 0.05), EPSILON);
        for (int tick = 0; tick < 9; tick++) {
            carry.sample(490.0, 490.0, true, 0.05);
        }
        assertEquals(70.0, carry.currentCeilingMps(), EPSILON);
    }

    @Test
    void heightBandIsInclusiveAndOneCarrierClampPreservesAnObliqueDirection() {
        assertEquals(true, RemoteBoostCorridor.isRestricted(0.0));
        assertEquals(true, RemoteBoostCorridor.isRestricted(256.0));
        assertEquals(false, RemoteBoostCorridor.isRestricted(-0.000_001));
        assertEquals(false, RemoteBoostCorridor.isRestricted(256.000_001));

        RemoteBoostCarry carry = new RemoteBoostCarry();
        carry.sample(490.0, 490.0, true, 0.05);
        double carrier = carry.sample(490.0, 490.0, true, 0.05);
        Vec3 oblique = new Vec3(3.0, 4.0, 12.0).scale(490.0 / 13.0);
        Vec3 clamped = RemoteBoostVelocity.clamp(oblique, carrier, 500.0);
        assertEquals(448.0, clamped.norm(), EPSILON);
        assertEquals(3.0 / 4.0, clamped.x() / clamped.y(), EPSILON);
        assertEquals(3.0 / 12.0, clamped.x() / clamped.z(), EPSILON);
    }

    @Test
    void continuousRestrictionNeverRaisesCarrierButOpenCorridorCanRestoreServerEnvelope() {
        RemoteBoostCarry carry = new RemoteBoostCarry();
        assertEquals(300.0, carry.sample(300.0, 490.0, true, 0.05), EPSILON);
        assertEquals(258.0, carry.sample(490.0, 490.0, true, 0.05), EPSILON);
        assertEquals(490.0, carry.sample(490.0, 258.0, false, 0.05), EPSILON);
    }

    @Test
    void matchingServerCarrierRampDoesNotDoubleApplyTheSameBrakeTick() {
        RemoteBoostCarry carry = new RemoteBoostCarry();
        assertEquals(490.0, carry.sample(490.0, 490.0, true, 0.05), EPSILON);
        assertEquals(448.0, carry.sample(448.0, 490.0, true, 0.05), EPSILON);
        assertEquals(406.0, carry.sample(406.0, 448.0, true, 0.05), EPSILON);
    }
}
