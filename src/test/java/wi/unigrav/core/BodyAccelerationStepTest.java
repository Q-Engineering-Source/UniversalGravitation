package wi.unigrav.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Source;
import wi.unigrav.core.floor.FloorFieldProfile;

import static org.junit.jupiter.api.Assertions.*;

class BodyAccelerationStepTest {
    @Test
    void buoyancyOnlyModifiesGravityWhileMagnetismAndAppliedForceRemainIndependent() {
        Vec3 gravity = new Vec3(4, -32, 0);
        Vec3 magnetism = new Vec3(0, -16, 0);
        Vec3 force = new Vec3(1000, 0, 0);
        var neutralDensity = new BodyPhysics(1000, 1);
        assertVector(new Vec3(1, -16, 0), BodyAccelerationStep.acceleration(
                gravity, magnetism, force, neutralDensity, true, true, 0));
        assertVector(new Vec3(5, -48, 0), BodyAccelerationStep.acceleration(
                gravity, magnetism, force, neutralDensity, true, false, 0));
        assertVector(new Vec3(5, -48, 0), BodyAccelerationStep.acceleration(
                gravity, magnetism, force, neutralDensity, false, true, 0));
    }

    @Test
    void externalForceUsesMassAndConfiguredFloorResponseIsAlreadyAcceleration() {
        Vec3 force = new Vec3(100, 0, 0);
        Vec3 magnetism = new Vec3(0, 8, 0);
        assertVector(new Vec3(2, 8, 0), BodyAccelerationStep.acceleration(
                Vec3.ZERO, magnetism, force, new BodyPhysics(50, 0), false, true, 0.13));
        assertVector(new Vec3(1, 8, 0), BodyAccelerationStep.acceleration(
                Vec3.ZERO, magnetism, force, new BodyPhysics(100, 0), false, true, 0.13));
    }

    @Test
    void nativeUnitConversionAndZeroForcePreserveExistingMotion() {
        Vec3 initial = new Vec3(1, 2, 3);
        assertVector(new Vec3(1.01, 1.92, 3.02), BodyAccelerationStep.motion(
                initial, new Vec3(4, -32, 8), 0, false, 1));
        assertEquals(initial, BodyAccelerationStep.motion(initial, Vec3.ZERO, 0, false, 1));
        Vec3 falling = Vec3.ZERO;
        for (int tick = 0; tick < 20; tick++) {
            falling = BodyAccelerationStep.motion(falling, new Vec3(0, -32, 0), 0, false, 1);
        }
        assertEquals(-32.0, falling.y() * 20, 1e-12);
    }

    @Test
    void groundFrictionOnlyCompensatesTheNewHorizontalDelta() {
        Vec3 result = BodyAccelerationStep.motion(new Vec3(2, 3, 4), new Vec3(8, -32, 16), 0, true, 0.5);
        assertVector(new Vec3(2.01, 2.92, 4.02), result);
    }

    @Test
    void impulseAndAccelerationUseTheSameSphericalSafetyLimitAndEstablishedOrdering() {
        assertVector(new Vec3(0.6, 0.8, 0), BodyAccelerationStep.limitMotion(new Vec3(3, 4, 0), 20));
        assertVector(new Vec3(3, 4, 0), BodyAccelerationStep.limitMotion(new Vec3(3, 4, 0), 0));
        assertVector(new Vec3(0.6, 0.8, 0), BodyAccelerationStep.motion(Vec3.ZERO, new Vec3(1200, 1600, 0), 20, false, 1));
        assertVector(new Vec3(0.5, 0, 0), BodyAccelerationStep.motion(new Vec3(0.5, 0, 0), new Vec3(400, 0, 0), 20, true, 0.5));
    }

    @Test
    void actualFloorIndexSumsGravityAndEligibleMagnetismBeforeTheSharedMotionStep() {
        var index = new FloorFieldIndex();
        index.replaceChunk(new FloorFieldIndex.ChunkKey(0, 0), new Object(), List.of(
                new Source(0, 10, 0, Face.UP, Kind.GRAVITY, new FloorFieldProfile(8, 8, 16)),
                new Source(0, 9, 0, Face.UP, Kind.MAGNETIC, new FloorFieldProfile(16, 8, 16)),
                new Source(1, 11, 0, Face.WEST, Kind.GRAVITY, new FloorFieldProfile(4, 8, 16))));
        var magnetic = index.sample(new Vec3(0.5, 11, 0.5), 1.0);
        assertVector(new Vec3(4, -8, 0), magnetic.gravityAccel());
        assertVector(new Vec3(0, -16, 0), magnetic.magneticAccel());
        Vec3 gravity = new Vec3(0, -32, 0).add(magnetic.gravityAccel());
        Vec3 total = BodyAccelerationStep.acceleration(gravity, magnetic.magneticAccel(), null,
                new BodyPhysics(1000, 1), false, true, 0);
        assertVector(new Vec3(0.01, -0.14, 0), BodyAccelerationStep.motion(Vec3.ZERO, total, 0, false, 1));
        Vec3 submerged = BodyAccelerationStep.acceleration(gravity, magnetic.magneticAccel(), null,
                new BodyPhysics(1000, 1), true, true, 0);
        assertVector(new Vec3(0, -0.04, 0), BodyAccelerationStep.motion(Vec3.ZERO, submerged, 0, false, 1));
        var nonMagnetic = index.sample(new Vec3(0.5, 11, 0.5), 0.0);
        assertEquals(Vec3.ZERO, nonMagnetic.magneticAccel());
        assertTrue(nonMagnetic.effectiveMagneticFaces().isEmpty());
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1e-12);
        assertEquals(expected.y(), actual.y(), 1e-12);
        assertEquals(expected.z(), actual.z(), 1e-12);
    }
}
