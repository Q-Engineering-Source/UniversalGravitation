package wi.unigrav.core.client.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;

/** Pure m/s receive-side vector clamp tests. */
class RemoteBoostVelocityTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void additive490Plus20ClampsToFinite500RatherThanStopping() {
        Vec3 result = RemoteBoostVelocity.clamp(new Vec3(510.0, 0.0, 0.0), 500.0, 500.0);
        assertEquals(500.0, result.x(), EPSILON);
        assertEquals(0.0, result.y(), EPSILON);
    }

    @Test
    void negotiatedCeilingWinsOverHigherGlobalLimit() {
        Vec3 result = RemoteBoostVelocity.clamp(new Vec3(100.0, 0.0, 0.0), 50.0, 500.0);
        assertEquals(50.0, result.x(), EPSILON);
    }

    @Test
    void clampUsesVectorNormRatherThanIndependentAxes() {
        Vec3 result = RemoteBoostVelocity.clamp(new Vec3(80.0, 80.0, 0.0), 100.0, 500.0);
        assertEquals(100.0, result.norm(), EPSILON);
        assertEquals(Math.sqrt(5_000.0), result.x(), EPSILON);
        assertEquals(Math.sqrt(5_000.0), result.y(), EPSILON);
    }

    @Test
    void invalidFiniteInputsFailClosedAndZeroGlobalMeansDisabled() {
        assertNull(RemoteBoostVelocity.clamp(new Vec3(Double.NaN, 0.0, 0.0), 50.0, 500.0));
        assertNull(RemoteBoostVelocity.clamp(Vec3.ZERO, Double.NaN, 500.0));
        assertEquals(50.0, RemoteBoostVelocity.clamp(new Vec3(100.0, 0.0, 0.0),
                50.0, 0.0).x(), EPSILON);
    }
}
