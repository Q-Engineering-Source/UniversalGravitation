package wi.unigrav.core.client.camera;

import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.camera.CameraEyeAnchorContext;
import neofontrender.addons.api.camera.CameraEyeAnchorProvider;
import neofontrender.addons.api.camera.CameraRegistration;
import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightVector;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UieFlightBodyPoseCompatibilityTest {
    @Test void runtimeCameraApiThreeAcceptsAndReleasesAnEyeAnchorProvider() {
        assertTrue(CameraApi.getApiVersion() >= 3,
                "accepted physical eye anchoring requires the selected UIE Camera API 3+");
        ResourceLocation id = new ResourceLocation("unigrav_test",
                "eye_anchor_" + UUID.randomUUID().toString().replace('-', '_'));
        CameraEyeAnchorProvider provider = new CameraEyeAnchorProvider() {
            @Override public ResourceLocation id() { return id; }
            @Override public neofontrender.addons.api.camera.CameraEyeAnchor anchor(
                    CameraEyeAnchorContext context) { return null; }
        };
        CameraRegistration registration = CameraApi.registerEyeAnchorProvider(provider);
        assertNotNull(registration,
                "the runtime API must return a real lifecycle handle for the registered eye provider");
        registration.close();
    }

    @Test void runtimeFlightApiVersionSelectsTheModelUpProviderBoundary() {
        int runtimeVersion = FlightApi.getApiVersion();
        assertTrue(runtimeVersion >= 9, "the shared camera adapter still requires Flight API v9+");
        assertFalse(SixDofBodyPresentation.supportsModelUpYBodyPose(10),
                "API 10 lacks accepted model-root transport offsets and must not register this provider");
        assertFalse(SixDofBodyPresentation.supportsModelUpYBodyPose(11),
                "API 11 lacks the sampled body geometry used by fade and light");
        assertFalse(SixDofBodyPresentation.supportsModelUpYBodyPose(12),
                "API 12 does not expose the owner's free versus axial shadow reference");
        assertTrue(SixDofBodyPresentation.supportsModelUpYBodyPose(13),
                "sampled shadow reference requires Flight API 13+");
        String expectedVersion = System.getenv("UG_EXPECTED_UIE_FLIGHT_API");
        if (expectedVersion != null && !expectedVersion.isBlank()) {
            assertEquals(Integer.parseInt(expectedVersion), runtimeVersion,
                    "the linkage probe must execute against the selected UIE runtime class set");
        }
        assertEquals(runtimeVersion >= SixDofBodyPresentation.MODEL_UP_BODY_POSE_FLIGHT_API,
                SixDofBodyPresentation.supportsModelUpYBodyPose(runtimeVersion),
                "provider registration must use the dynamic UIE API version");

        FlightAttitude view = FlightAttitude.fromMinecraftDegrees(35, -20, 41);
        var tracking = SixDofBodyPresentation.tracking(view);
        assertTrue(tracking.isRigid());
        assertSame(view, tracking.getAttitude(),
                "camera tracking remains available without constructing the API 11 body pose");

        ResourceLocation id = new ResourceLocation("unigrav_test",
                "body_pose_" + UUID.randomUUID().toString().replace('-', '_'));
        List<String> before = List.copyOf(FlightApi.bodyPoseProviderIds());
        var registration = SixDofBodyPresentation.registerModelUpBodyPoseProvider(
                id, Integer.MAX_VALUE, (player, partialTicks) -> null);
        try {
            List<String> after = FlightApi.bodyPoseProviderIds();
            if (runtimeVersion >= SixDofBodyPresentation.MODEL_UP_BODY_POSE_FLIGHT_API) {
                assertNotNull(registration,
                        "API 11+ must install the actual UIE body pose provider");
                assertTrue(after.contains(id.toString()),
                        "the real UIE registry must own the API 11+ provider");
            } else {
                assertNull(registration,
                        "an older UIE API must not receive the model-up-Y callback");
                assertEquals(before, after,
                        "an older UIE API must leave its actual provider registry unchanged");
            }
        } finally {
            if (registration != null) registration.close();
        }
        assertFalse(FlightApi.bodyPoseProviderIds().contains(id.toString()),
                "the test registration must be released from the real UIE API registry");
    }

    @Test void publicLocalCardinalProviderTranslatesAcceptedPhysicalFeetFromTransport() {
        assertTrue(FlightApi.getApiVersion() >= 11,
                "root-world offsets require the public UIE Flight API 11 pose overload");
        var snapshot = presentationSnapshot(CardinalFrame.X_POS.attitude(), new PlayerBodyShape.Cardinal(
                CardinalFrame.X_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F)));
        var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                CardinalFrame.X_POS, new Vec3d(0, 0, 1));
        ResourceLocation id = new ResourceLocation("unigrav_test",
                "accepted_cardinal_root_" + UUID.randomUUID().toString().replace('-', '_'));
        var registration = FlightApi.registerBodyPoseProvider(id, Integer.MAX_VALUE,
                (player, partialTicks) -> SixDofBodyPresentation.sampleCardinal(snapshot, effective,
                        new FlightVector(0, 0, 1), null, 0, 0, partialTicks).body());
        try {
            var sampled = FlightApi.queryBodyPose(null, .5F);
            assertNotNull(sampled, "the public Flight API must return the registered local body pose");
            assertEquals(id.toString(), FlightApi.diagnostics().bodyPoseProviderId());
            assertEquals(-1.8F / 2.0F, sampled.rootWorldOffset.x, 1.0e-12,
                    "X_POS model root must retain accepted physical feet relative to transport");
            assertEquals(.6F / 2.0F, sampled.rootWorldOffset.y, 1.0e-12);
            assertEquals(0, sampled.rootWorldOffset.z, 1.0e-10);
        } finally {
            registration.close();
        }
    }

    @Test void publicLocalAcceptedFreeProviderTranslatesPhysicalFeetFromTransport() {
        assertTrue(FlightApi.getApiVersion() >= 11,
                "accepted local roots require the public UIE Flight API 11 pose overload");
        var snapshot = presentationSnapshot(new Vec3d(.1, .2, .3),
                new Rotation3d(.2, .3, .4, .5), new PlayerBodyShape.FreeCapsule(
                        new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F)));
        var placement = snapshot.placement();
        var capsule = placement.capsule();
        var lower = capsule.centerline().start();
        var up = placement.bodyUp();
        var exactFoot = new Vec3d(Math.fma(-up.x(), capsule.radius(), lower.x()),
                Math.fma(-up.y(), capsule.radius(), lower.y()), Math.fma(-up.z(), capsule.radius(), lower.z()));
        var roundedMultiplyFoot = lower.subtract(up.multiply(capsule.radius()));
        assertTrue(Double.doubleToLongBits(exactFoot.x()) != Double.doubleToLongBits(roundedMultiplyFoot.x())
                        || Double.doubleToLongBits(exactFoot.y()) != Double.doubleToLongBits(roundedMultiplyFoot.y())
                        || Double.doubleToLongBits(exactFoot.z()) != Double.doubleToLongBits(roundedMultiplyFoot.z()),
                "the accepted Free fixture must exercise a non-exact up*radius product");
        assertTrue(placement.center().x() != 0 || placement.center().y() != 0 || placement.center().z() != 0,
                "the accepted Free fixture must retain a nonzero world center");
        var expectedOffset = exactFoot.subtract(placement.transport());
        var view = FlightAttitude.fromMinecraftDegrees(20, 35, -15);
        ResourceLocation id = new ResourceLocation("unigrav_test",
                "accepted_free_root_" + UUID.randomUUID().toString().replace('-', '_'));
        var registration = FlightApi.registerBodyPoseProvider(id, Integer.MAX_VALUE,
                (player, partialTicks) -> SixDofBodyPresentation.sampleFree(snapshot, view).body());
        try {
            var sampled = FlightApi.queryBodyPose(null, .5F);
            assertNotNull(sampled, "the public Flight API must return the registered accepted Free pose");
            assertEquals(id.toString(), FlightApi.diagnostics().bodyPoseProviderId());
            assertEquals(expectedOffset.x(), sampled.rootWorldOffset.x, 1.0e-12,
                    "accepted Free model root must retain its physical feet relative to transport");
            assertEquals(expectedOffset.y(), sampled.rootWorldOffset.y, 1.0e-12);
            assertEquals(expectedOffset.z(), sampled.rootWorldOffset.z, 1.0e-12);
        } finally {
            registration.close();
        }
    }

    @Test void activeFreePoseReportCanSampleItsExistingAttitudeWithoutAFlightBodyPoseProvider() {
        FlightAttitude view = FlightAttitude.fromMinecraftDegrees(35, -20, 41);
        SixDofBodyPresentation.FreePoseAttitudes expected = expectedFreePoseAttitudes(view, 23.0F);
        SixDofBodyPresentation.FreePoseAttitudes reported =
                SixDofBodyPresentation.freePoseAttitudes(view, 23.0F);
        assertAttitudeEquals(expected.body(), reported.body());
        assertSame(view, reported.head(),
                "the existing view quaternion is reported directly as world head orientation");

        if (FlightApi.getApiVersion() >= SixDofBodyPresentation.MODEL_UP_BODY_POSE_FLIGHT_API) {
            var sampled = SixDofBodyPresentation.sample(view, 23.0F);
            assertAttitudeEquals(reported.body(), sampled.body().attitude);
            assertAttitudeEquals(reported.head(), sampled.body().headWorldAttitude);
        }
    }

    @Test void acceptedFreeBodyAndLiveHeadUseTheSameProductionAttitudesWithoutGait() {
        assertTrue(FlightApi.getApiVersion() >= 11, "this slice must execute actual UIE API 11 body objects");
        for (var q : new Rotation3d[] {Rotation3d.identity(), new Rotation3d(.2, .3, .4, .5)}) {
            var snapshot = presentationSnapshot(q, new PlayerBodyShape.FreeCapsule(
                    new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F)));
            var expectedBody = new FlightAttitude(q.x(), q.y(), q.z(), q.w());
            for (var view : new FlightAttitude[] {FlightAttitude.fromMinecraftDegrees(0, 0, 90),
                    FlightAttitude.fromMinecraftDegrees(35, -20, 41)}) {
                var reported = SixDofBodyPresentation.acceptedFreePoseAttitudes(snapshot, view);
                var local = SixDofBodyPresentation.sampleFree(snapshot, view);
                assertEquals(FlightBodyPose.ShadowReference.FREE, local.body().shadowReference,
                        "Free remains Free even for the identity quaternion; G is irrelevant");
                assertNull(local.body().shadowAxisUp);
                assertAttitudeEquals(expectedBody, reported.body());
                assertSame(view, reported.head());
                assertAttitudeEquals(reported.body(), local.body().attitude);
                assertAttitudeEquals(reported.head(), local.body().headWorldAttitude);
                assertTrue(local.tracking().isRigid());
                assertSame(view, local.tracking().getAttitude());
            }
        }
        var identity = FlightAttitude.fromMinecraftDegrees(0, 0, 0);
        var rolled = FlightAttitude.fromMinecraftDegrees(0, 0, 90);
        assertEquals(identity.forward().x, rolled.forward().x, 1e-12);
        assertEquals(identity.forward().y, rolled.forward().y, 1e-12);
        assertEquals(identity.forward().z, rolled.forward().z, 1e-12);
        assertTrue(Math.abs(rolled.z) > .7, "identical forward must preserve distinct roll in live head Q");
        var cardinal = presentationSnapshot(Rotation3d.identity(), new PlayerBodyShape.Cardinal(
                CardinalFrame.Y_POS, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F)));
        assertThrows(IllegalArgumentException.class,
                () -> SixDofBodyPresentation.acceptedFreePoseAttitudes(cardinal, rolled));
        assertThrows(IllegalArgumentException.class, () -> SixDofBodyPresentation.sampleFree(cardinal, rolled));
    }

    /** Source regression only: NOT_RUN under user-owned validation. */
    @Test void allAcceptedCardinalAxesRemainIndependentOfTheVisualTransition() {
        for (var frame : CardinalFrame.values()) {
            var snapshot = presentationSnapshot(frame.attitude(), new PlayerBodyShape.Cardinal(
                    frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F)));
            var tangent = frame.attitude().basisZ();
            var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                    frame, tangent);
            var sampled = SixDofBodyPresentation.sampleCardinal(snapshot, effective,
                    new FlightVector(tangent.x(), tangent.y(), tangent.z()), null, 0, 0, .5F).body();
            assertEquals(FlightBodyPose.ShadowReference.AXIAL, sampled.shadowReference);
            assertEquals(frame.up().x(), sampled.shadowAxisUp.x);
            assertEquals(frame.up().y(), sampled.shadowAxisUp.y);
            assertEquals(frame.up().z(), sampled.shadowAxisUp.z);
            var correction = FlightAttitude.fromMinecraftDegrees(0, 0, 25);
            var render = new CardinalCameraTransition.RenderSample(new FlightAttitude(0, 0, 0, 1),
                    new FlightVector(0, 0, 0), correction);
            var physicalEye = snapshot.placement().eye();
            var eye = new FlightVector(physicalEye.x(), physicalEye.y(), physicalEye.z());
            var transitioning = SixDofBodyPresentation.sampleCardinalView(snapshot, effective, render,
                    new FlightVector(0, 0, 0), eye, eye, null, 0, 0, .5F).body();
            assertEquals(FlightBodyPose.ShadowReference.AXIAL, transitioning.shadowReference);
            assertEquals(frame.up().x(), transitioning.shadowAxisUp.x);
            assertEquals(frame.up().y(), transitioning.shadowAxisUp.y);
            assertEquals(frame.up().z(), transitioning.shadowAxisUp.z);
            double dot = sampled.attitude.x * transitioning.attitude.x
                    + sampled.attitude.y * transitioning.attitude.y
                    + sampled.attitude.z * transitioning.attitude.z
                    + sampled.attitude.w * transitioning.attitude.w;
            assertTrue(Math.abs(dot) < .999,
                    "the actual visual reference correction must differ while the accepted shadow axis stays fixed");
        }
    }

    private static PlayerPhysicalSnapshot presentationSnapshot(Rotation3d attitude, PlayerBodyShape shape) {
        return presentationSnapshot(new Vec3d(8, 70, 8), attitude, shape);
    }
    private static PlayerPhysicalSnapshot presentationSnapshot(Vec3d center, Rotation3d attitude,
                                                               PlayerBodyShape shape) {
        var scope = new PlayerPhysicalSnapshot.Scope(new UUID(0, 931), new UUID(0, 932), 0, 1);
        return new PlayerPhysicalSnapshot(scope, 1,
                new PlayerBodyPlacement(center, attitude, shape), new Vec3d(0, 0, 0), Optional.empty());
    }
    private static SixDofBodyPresentation.FreePoseAttitudes expectedFreePoseAttitudes(
            FlightAttitude view, double bodyYawOffsetDegrees) {
        double halfAngle = -Math.toRadians(bodyYawOffsetDegrees) * 0.5;
        double sine = Math.sin(halfAngle);
        double cosine = Math.cos(halfAngle);
        return new SixDofBodyPresentation.FreePoseAttitudes(
                new FlightAttitude(
                        view.x * cosine - view.z * sine,
                        view.y * cosine + view.w * sine,
                        view.z * cosine + view.x * sine,
                        view.w * cosine - view.y * sine),
                view);
    }

    private static void assertAttitudeEquals(FlightAttitude expected, FlightAttitude actual) {
        assertEquals(expected.x, actual.x, 1.0e-12);
        assertEquals(expected.y, actual.y, 1.0e-12);
        assertEquals(expected.z, actual.z, 1.0e-12);
        assertEquals(expected.w, actual.w, 1.0e-12);
    }
}
