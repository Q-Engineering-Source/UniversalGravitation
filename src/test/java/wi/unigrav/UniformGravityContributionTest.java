package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uniform contributions in a {@link GravityField}: constant acceleration,
 * superposition with point masses, replacement semantics, and the
 * source/contribution view split (orbit-center lookups must stay clean).
 */
class UniformGravityContributionTest {

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
    void uniformAccelerationIsPositionAndTimeIndependent() {
        UniformGravityContribution down = UniformGravityContribution.downward(32.0);
        assertEquals(new Vec3(0.0, -32.0, 0.0), down.accelerationAt(Vec3.ZERO, 0.0));
        assertEquals(new Vec3(0.0, -32.0, 0.0),
                down.accelerationAt(new Vec3(1.0e6, -300.0, 42.0), 12345.6));
    }

    @Test
    void uniformSuperposesWithPointMass() {
        GravityField field = new GravityField();
        field.registerContribution("unigrav", "world", UniformGravityContribution.downward(32.0));
        field.register("test", "mass", pointMass(10.0, new Vec3(0.0, 10.0, 0.0)));
        // At the origin the point mass pulls +y with magnitude μ/r² = 0.1.
        Vec3 acc = field.accelerationAt(Vec3.ZERO, 0.0);
        assertEquals(0.0, acc.x(), 1.0e-12);
        assertEquals(0.1 - 32.0, acc.y(), 1.0e-12);
        assertEquals(0.0, acc.z(), 1.0e-12);
    }

    @Test
    void sameIdReplacesAcrossKindsAndStaleHandleKeepsReplacement() {
        GravityField field = new GravityField();
        GravityField.Registration reg1 = field.registerContribution(
                "unigrav", "world", UniformGravityContribution.downward(32.0));
        field.registerContribution("unigrav", "world", UniformGravityContribution.downward(1.0));
        assertEquals(1, field.size());
        reg1.close(); // stale: must not remove the replacement
        assertEquals(new Vec3(0.0, -1.0, 0.0), field.accelerationAt(Vec3.ZERO, 0.0));

        // A source may replace a contribution under the same id and vice versa.
        field.register("unigrav", "world", pointMass(1.0, Vec3.ZERO));
        assertEquals(1, field.size());
        assertTrue(field.snapshot().containsKey(new GravityField.Id("unigrav", "world")));
        assertTrue(field.contributions().isEmpty());
    }

    @Test
    void sourceAndContributionViewsAreDisjoint() {
        GravityField field = new GravityField();
        field.register("test", "mass", pointMass(1.0, Vec3.ZERO));
        field.registerContribution("unigrav", "world", UniformGravityContribution.downward(32.0));

        // Source views see only the source: orbit-center id lookups stay clean.
        assertEquals(1, field.snapshot().size());
        assertEquals(1, field.sources().size());
        assertTrue(field.snapshot().containsKey(new GravityField.Id("test", "mass")));
        // Contribution views see only the uniform entry.
        assertEquals(1, field.contributions().size());
        assertTrue(field.contributions().containsKey(new GravityField.Id("unigrav", "world")));

        // Registration exposes null source() for plain contributions.
        GravityField.Registration reg = field.registerContribution(
                "unigrav", "other", UniformGravityContribution.downward(1.0));
        assertNull(reg.source());
        reg.close();
    }
}
