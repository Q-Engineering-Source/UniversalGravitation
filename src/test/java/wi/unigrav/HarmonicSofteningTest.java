package wi.unigrav;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.gravity.BodyGravitySource;
import wi.unigrav.api.gravity.GravitySource;
import wi.unigrav.api.gravity.HarmonicDamping;
import wi.unigrav.api.gravity.SphericalHarmonics;
import wi.unigrav.api.math.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression tests for softening the monopole without changing harmonic corrections. */
class HarmonicSofteningTest {

    private static final double MU = 1.0e8;
    private static final double REFERENCE_RADIUS = 10.0;
    private static final double SOFTENING = 5.0;
    private static final double J2 = 1.0e-2;

    @Test
    void nonNullZeroCoefficientModelStillUsesSoftenedPointMass() {
        SphericalHarmonics zeroModel = model(0.0, null);
        BodyGravitySource source = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(zeroModel)
                .withSoftening(SOFTENING);
        Vec3 offset = new Vec3(3.0, 4.0, 6.0);

        Vec3 expected = GravitySource.pointMassAcceleration(MU, offset, SOFTENING);
        Vec3 actual = source.accelerationAt(offset, 0.0);

        assertVectorEquals(expected, actual, expected.norm() * 1.0e-12);
        assertVectorEquals(Vec3.ZERO, source.accelerationAt(Vec3.ZERO, 0.0), 0.0);
    }

    @Test
    void ignoredDegreeTwoOrderOneCoefficientsStillUseSoftenedPointMassAtCentre() {
        double[][] c = new double[3][3];
        double[][] s = new double[3][3];
        c[2][1] = 1.0;
        s[2][1] = -1.0;
        SphericalHarmonics ignoredOnly = new SphericalHarmonics(
                REFERENCE_RADIUS, c, s);
        BodyGravitySource source = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(ignoredOnly)
                .withSoftening(SOFTENING);

        assertEquals(Vec3.ZERO, source.accelerationAt(Vec3.ZERO, 0.0));
        assertEquals(-MU / SOFTENING,
                ignoredOnly.potential(Vec3.ZERO, MU, SOFTENING), 0.0);
    }

    @Test
    void softenedPotentialMatchesSoftenedAccelerationForZeroCoefficientModel() {
        SphericalHarmonics zeroModel = model(0.0, null);
        Vec3 offset = new Vec3(30.0, -40.0, 50.0);
        double step = 1.0e-3;

        Vec3 numericalGradient = new Vec3(
                centralPotentialDerivative(zeroModel, offset, new Vec3(step, 0.0, 0.0)),
                centralPotentialDerivative(zeroModel, offset, new Vec3(0.0, step, 0.0)),
                centralPotentialDerivative(zeroModel, offset, new Vec3(0.0, 0.0, step)));
        Vec3 acceleration = zeroModel.acceleration(offset, MU, SOFTENING);

        assertVectorEquals(acceleration, numericalGradient.negate(),
                acceleration.norm() * 1.0e-9);
        assertEquals(-MU / SOFTENING,
                zeroModel.potential(Vec3.ZERO, MU, SOFTENING), 0.0);
    }

    @Test
    void softenedTotalPotentialGradientMatchesAccelerationInDampingTransition() {
        SphericalHarmonics harmonics = model(J2, new HarmonicDamping(30.0));
        Vec3 offset = new Vec3(45.0, -20.0, 18.0);
        double step = 1.0e-3;

        Vec3 numericalGradient = new Vec3(
                centralPotentialDerivative(harmonics, offset, new Vec3(step, 0.0, 0.0)),
                centralPotentialDerivative(harmonics, offset, new Vec3(0.0, step, 0.0)),
                centralPotentialDerivative(harmonics, offset, new Vec3(0.0, 0.0, step)));
        Vec3 acceleration = harmonics.acceleration(offset, MU, SOFTENING);

        assertVectorEquals(acceleration, numericalGradient.negate(),
                acceleration.norm() * 1.0e-8);
    }

    @Test
    void softeningChangesOnlyThePointMassPotentialAcrossDampingRegions() {
        SphericalHarmonics harmonics = model(J2, new HarmonicDamping(30.0));
        Vec3[] offsets = {
                new Vec3(18.0, -7.0, 6.0),
                new Vec3(45.0, -20.0, 18.0),
                new Vec3(100.0, 15.0, -20.0)
        };

        for (Vec3 offset : offsets) {
            double hardPointMass = -MU / offset.norm();
            double softPointMass = -MU
                    / Math.sqrt(offset.normSquared() + SOFTENING * SOFTENING);
            double hardCorrection = harmonics.potential(offset, MU) - hardPointMass;
            double softCorrection = harmonics.potential(offset, MU, SOFTENING) - softPointMass;
            double tolerance = Math.max(1.0, Math.abs(hardCorrection)) * 1.0e-10;
            assertEquals(hardCorrection, softCorrection, tolerance);
        }
    }

    @Test
    void softeningChangesOnlyThePointMassAcrossDampingRegions() {
        HarmonicDamping damping = new HarmonicDamping(30.0);
        SphericalHarmonics harmonics = model(J2, damping);
        BodyGravitySource unsoftened = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(harmonics);
        BodyGravitySource softened = unsoftened.withSoftening(SOFTENING);

        Vec3[] offsets = {
                new Vec3(18.0, -7.0, 6.0),   // inside: sigma = 1
                new Vec3(45.0, -20.0, 18.0), // transition: 0 < sigma < 1
                new Vec3(100.0, 15.0, -20.0) // outside: sigma = 0
        };
        for (Vec3 offset : offsets) {
            assertHarmonicCorrectionUnchanged(unsoftened, softened, offset, 0.0);
        }

        Vec3 outside = offsets[2];
        Vec3 expectedOutside = GravitySource.pointMassAcceleration(MU, outside, SOFTENING);
        assertVectorEquals(expectedOutside, softened.accelerationAt(outside, 0.0), 0.0);
    }

    @Test
    void spinRotatesOnlyTheHarmonicCorrectionNotTheSofteningContract() {
        SphericalHarmonics harmonics = model(J2, new HarmonicDamping(30.0));
        Vec3 axis = new Vec3(1.0, 2.0, 3.0);
        BodyGravitySource unsoftened = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(harmonics)
                .withSpin(0.7, axis, 0.2);
        BodyGravitySource softened = unsoftened.withSoftening(SOFTENING);

        assertHarmonicCorrectionUnchanged(
                unsoftened, softened, new Vec3(30.0, -40.0, 50.0), 2.3);
    }

    @Test
    void zeroSofteningPreservesLegacyBodyFixedAndInertialResultsExactly() {
        SphericalHarmonics harmonics = model(J2, new HarmonicDamping(30.0));
        Vec3 offset = new Vec3(30.0, -40.0, 50.0);
        Vec3 axis = new Vec3(1.0, 2.0, 3.0);
        double angle = 1.11;

        assertVectorEquals(harmonics.acceleration(offset, MU),
                harmonics.acceleration(offset, MU, 0.0), 0.0);
        assertVectorEquals(harmonics.accelerationInertial(offset, MU, axis, angle),
                harmonics.accelerationInertial(offset, MU, axis, angle, 0.0), 0.0);
        assertEquals(harmonics.potential(offset, MU),
                harmonics.potential(offset, MU, 0.0), 0.0);

        BodyGravitySource source = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(harmonics)
                .withSpin(0.7, axis, 0.2);
        double time = 1.3;
        assertVectorEquals(
                harmonics.accelerationInertial(offset, MU, axis, 0.2 + 0.7 * time),
                source.accelerationAt(offset, time), 0.0);
    }

    @Test
    void inertialOverloadAppliesTheExistingRotationAroundANonUnitAxis() {
        double[][] c = new double[4][4];
        double[][] s = new double[4][4];
        c[2][1] = 2.0e-3;
        c[3][2] = -1.0e-3;
        s[2][1] = 1.5e-3;
        s[3][2] = 0.8e-3;
        SphericalHarmonics harmonics = new SphericalHarmonics(REFERENCE_RADIUS, c, s);
        Vec3 axis = new Vec3(1.0, 2.0, 3.0);
        Vec3 inertialOffset = new Vec3(30.0, -40.0, 50.0);
        double angle = 1.11;

        Vec3 bodyFixedOffset = rotate(inertialOffset, axis, -angle);
        Vec3 expected = rotate(
                harmonics.acceleration(bodyFixedOffset, MU, SOFTENING), axis, angle);

        assertVectorEquals(expected,
                harmonics.accelerationInertial(
                        inertialOffset, MU, axis, angle, SOFTENING),
                expected.norm() * 1.0e-12);
    }

    @Test
    void immutableModifierOrderPreservesHarmonicsSpinAndSoftening() {
        SphericalHarmonics harmonics = model(J2, new HarmonicDamping(30.0));
        Vec3 axis = new Vec3(1.0, 2.0, 3.0);
        BodyGravitySource harmonicsFirst = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withHarmonics(harmonics)
                .withSpin(0.7, axis, 0.2)
                .withSoftening(SOFTENING);
        BodyGravitySource softeningFirst = BodyGravitySource.fixed(MU, Vec3.ZERO)
                .withSoftening(SOFTENING)
                .withSpin(0.7, axis, 0.2)
                .withHarmonics(harmonics);
        Vec3 offset = new Vec3(30.0, -40.0, 50.0);

        assertEquals(SOFTENING, softeningFirst.softeningLength(), 0.0);
        assertEquals(harmonics, softeningFirst.harmonics());
        assertVectorEquals(harmonicsFirst.accelerationAt(offset, 2.3),
                softeningFirst.accelerationAt(offset, 2.3), 0.0);
    }

    private static SphericalHarmonics model(double j2, HarmonicDamping damping) {
        double[][] c = new double[3][3];
        c[2][0] = -j2 / Math.sqrt(5.0);
        return new SphericalHarmonics(
                REFERENCE_RADIUS, c, new double[3][3], damping);
    }

    private static void assertHarmonicCorrectionUnchanged(
            BodyGravitySource unsoftened, BodyGravitySource softened,
            Vec3 offset, double time) {
        Vec3 purePointMass = GravitySource.pointMassAcceleration(MU, offset);
        Vec3 softenedPointMass = GravitySource.pointMassAcceleration(MU, offset, SOFTENING);
        Vec3 unsoftenedCorrection = unsoftened.accelerationAt(offset, time).sub(purePointMass);
        Vec3 softenedCorrection = softened.accelerationAt(offset, time).sub(softenedPointMass);
        double tolerance = Math.max(1.0, unsoftenedCorrection.norm()) * 1.0e-10;
        assertVectorEquals(unsoftenedCorrection, softenedCorrection, tolerance);
    }

    private static double centralPotentialDerivative(
            SphericalHarmonics harmonics, Vec3 offset, Vec3 delta) {
        return (harmonics.potential(offset.add(delta), MU, SOFTENING)
                - harmonics.potential(offset.sub(delta), MU, SOFTENING))
                / (2.0 * delta.norm());
    }

    private static Vec3 rotate(Vec3 vector, Vec3 axis, double angle) {
        Vec3 normalizedAxis = axis.normalize();
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return vector.scale(cos)
                .add(normalizedAxis.cross(vector).scale(sin))
                .add(normalizedAxis.scale(
                        normalizedAxis.dot(vector) * (1.0 - cos)));
    }

    private static void assertVectorEquals(Vec3 expected, Vec3 actual, double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
        assertEquals(expected.z(), actual.z(), tolerance);
    }
}
