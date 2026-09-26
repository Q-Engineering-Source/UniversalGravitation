package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registry semantics of {@link GravityField}: registration handles,
 * replacement, idempotent close, and acceleration superposition.
 */
class GravityFieldTest {

    private static GravitySource pointMass(double mu, Vec3 position) {
        return new GravitySource() {
            @Override
            public double gravitationalParameter() {
                return mu;
            }

            @Override
            public Vec3 position(double time) {
                return position;
            }
        };
    }

    @Test
    void closeUnregistersIdempotently() {
        GravityField field = new GravityField();
        GravityField.Registration reg = field.register("test", "earth", pointMass(1.0, Vec3.ZERO));
        assertEquals(1, field.size());
        reg.close();
        assertEquals(0, field.size());
        reg.close(); // idempotent: no exception, no effect
        assertEquals(0, field.size());
    }

    @Test
    void sameIdReplacesAndStaleHandleDoesNotRemoveReplacement() {
        GravityField field = new GravityField();
        GravitySource first = pointMass(1.0, Vec3.ZERO);
        GravitySource second = pointMass(2.0, Vec3.ZERO);
        GravityField.Registration reg1 = field.register("test", "earth", first);
        field.register("test", "earth", second);
        assertEquals(1, field.size());
        reg1.close(); // stale: must not remove the replacement
        assertEquals(1, field.size());
        assertTrue(field.snapshot().containsValue(second));
        assertFalse(field.snapshot().containsValue(first));
    }

    @Test
    void accelerationsSuperpose() {
        GravityField field = new GravityField();
        field.register("test", "a", pointMass(6.0, new Vec3(-1.0, 0.0, 0.0)));
        field.register("test", "b", pointMass(8.0, new Vec3(1.0, 0.0, 0.0)));
        // At the origin both pulls are along ±x with magnitude μ.
        Vec3 acc = field.accelerationAt(Vec3.ZERO, 0.0);
        assertEquals(2.0, acc.x(), 1.0e-12);
        assertEquals(0.0, acc.y(), 1.0e-12);
        assertEquals(0.0, acc.z(), 1.0e-12);
    }
}
