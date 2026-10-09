package wi.unigrav.core.client.camera;

import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightAttitude;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UieFlightBodyPoseCompatibilityTest {
    @Test void runtimeFlightApiVersionSelectsTheModelUpProviderBoundary() {
        int runtimeVersion = FlightApi.getApiVersion();
        assertTrue(runtimeVersion >= 9, "the shared camera adapter still requires Flight API v9+");
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
                "camera tracking remains available without constructing an API 10 body pose");

        ResourceLocation id = new ResourceLocation("unigrav_test",
                "body_pose_" + UUID.randomUUID().toString().replace('-', '_'));
        List<String> before = List.copyOf(FlightApi.bodyPoseProviderIds());
        var registration = SixDofBodyPresentation.registerModelUpBodyPoseProvider(
                id, Integer.MAX_VALUE, (player, partialTicks) -> null);
        try {
            List<String> after = FlightApi.bodyPoseProviderIds();
            if (runtimeVersion >= SixDofBodyPresentation.MODEL_UP_BODY_POSE_FLIGHT_API) {
                assertNotNull(registration,
                        "API 10+ must install the actual UIE body pose provider");
                assertTrue(after.contains(id.toString()),
                        "the real UIE registry must own the API 10+ provider");
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
