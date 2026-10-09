package wi.unigrav.core.client.camera;

import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightBodyPoseProvider;
import neofontrender.addons.api.flight.FlightCameraTracking;
import neofontrender.addons.api.flight.FlightRegistration;
import neofontrender.addons.api.flight.FlightVector;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** UIE contract boundary: gait turns the visual root, never the camera's authoritative attitude. */
public final class SixDofBodyPresentation {
    static final int MODEL_UP_BODY_POSE_FLIGHT_API = 10;

    private SixDofBodyPresentation() { }

    public record Sample(FlightBodyPose body, FlightCameraTracking tracking) { }
    record FreePoseAttitudes(FlightAttitude body, FlightAttitude head) { }

    public static Sample sample(FlightAttitude view, float bodyYawOffsetDegrees) {
        FreePoseAttitudes attitudes = freePoseAttitudes(view, bodyYawOffsetDegrees);
        return new Sample(FlightBodyPose.modelUpY(attitudes.body(), attitudes.head()), tracking(view));
    }

    /** Computes the shared report/body attitudes without linking the API 10 pose wrapper. */
    static FreePoseAttitudes freePoseAttitudes(FlightAttitude view, float bodyYawOffsetDegrees) {
        FlightAttitude body = view.rotateLocal(0, 1, 0,
                -Math.toRadians(bodyYawOffsetDegrees));
        return new FreePoseAttitudes(body, view);
    }

    /** Builds a cardinal model from accepted physical up and interpolated native body heading. */
    public static Sample sampleCardinal(PlayerPhysicalSnapshot snapshot, FlightVector worldLook,
                                        float previousBodyYaw, float bodyYaw, float partialTicks) {
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)) {
            throw new IllegalArgumentException("cardinal presentation requires an accepted cardinal shape");
        }
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("partialTicks must be finite");
        FlightVector up = fromWorld(cardinal.frame().up());
        FlightVector acceptedForward = fromWorld(snapshot.placement().attitude().basisZ());
        FlightVector tangentFallback = usableProjection(acceptedForward, up);
        if (tangentFallback == null) tangentFallback = fromWorld(cardinal.frame().attitude().basisZ()).normalize();

        FlightVector bodyHeading = tangentFallback;
        if (Float.isFinite(previousBodyYaw) && Float.isFinite(bodyYaw)) {
            float interpolatedYaw = previousBodyYaw
                    + MathHelper.wrapDegrees(bodyYaw - previousBodyYaw) * MathHelper.clamp(partialTicks, 0, 1);
            double yaw = Math.toRadians(interpolatedYaw);
            FlightVector nativeHeading = new FlightVector(-Math.sin(yaw), 0, Math.cos(yaw));
            FlightVector projected = usableProjection(nativeHeading, up);
            if (projected != null) bodyHeading = projected;
        }
        FlightAttitude body = attitudeFromUpForward(up, bodyHeading);
        FlightAttitude view = attitudeFromWorldLook(up, worldLook, tangentFallback);
        return new Sample(FlightBodyPose.modelUpY(body, view), tracking(view));
    }

    private static FlightAttitude attitudeFromWorldLook(FlightVector up, FlightVector worldLook,
                                                        FlightVector tangentFallback) {
        FlightVector forward = normalized(worldLook);
        if (forward == null) throw new IllegalArgumentException("worldLook must be finite and nonzero");
        FlightVector viewUp = usableProjection(up, forward);
        if (viewUp == null) viewUp = usableProjection(tangentFallback, forward);
        if (viewUp == null) viewUp = usableProjection(frameTangent(up), forward);
        if (viewUp == null) throw new IllegalArgumentException("worldLook cannot form a view basis");
        return attitudeFromUpForward(viewUp, forward);
    }

    private static FlightAttitude attitudeFromUpForward(FlightVector up, FlightVector forward) {
        FlightVector right = forward.cross(up).normalize();
        return FlightAttitude.fromBasis(right, up, forward);
    }

    private static FlightVector frameTangent(FlightVector up) {
        FlightVector axis = Math.abs(up.x) < .5 ? new FlightVector(1, 0, 0) : new FlightVector(0, 0, 1);
        FlightVector tangent = usableProjection(axis, up);
        return tangent == null ? new FlightVector(0, 1, 0) : tangent;
    }

    private static FlightVector usableProjection(FlightVector value, FlightVector normal) {
        double component = value.dot(normal);
        FlightVector projection = new FlightVector(value.x - normal.x * component,
                value.y - normal.y * component, value.z - normal.z * component);
        return normalized(projection);
    }

    private static FlightVector normalized(FlightVector value) {
        double lengthSquared = value.lengthSquared();
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)
                || !Double.isFinite(lengthSquared) || lengthSquared <= 1.0e-12) return null;
        return value.scale(1.0 / Math.sqrt(lengthSquared));
    }

    private static FlightVector fromWorld(Vec3d value) {
        return new FlightVector(value.x(), value.y(), value.z());
    }

    static FlightCameraTracking tracking(FlightAttitude view) {
        return FlightCameraTracking.rigid(view);
    }

    static boolean supportsModelUpYBodyPose(int flightApiVersion) {
        return flightApiVersion >= MODEL_UP_BODY_POSE_FLIGHT_API;
    }

    static FlightRegistration registerModelUpBodyPoseProvider(ResourceLocation id, int priority,
                                                               FlightBodyPoseProvider provider) {
        int flightApiVersion = FlightApi.getApiVersion();
        if (!supportsModelUpYBodyPose(flightApiVersion)) {
            UnigravMod.LOGGER.warn("Six-DOF model-up-Y body pose provider not registered: "
                            + "UIE Flight API {} found; API {}+ is required",
                    flightApiVersion, MODEL_UP_BODY_POSE_FLIGHT_API);
            return null;
        }
        return FlightApi.registerBodyPoseProvider(id, priority, provider);
    }
}
