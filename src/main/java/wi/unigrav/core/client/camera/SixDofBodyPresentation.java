package wi.unigrav.core.client.camera;

import neofontrender.addons.api.flight.FlightApi;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightBodyGeometry;
import neofontrender.addons.api.flight.FlightBodyBounds;
import neofontrender.addons.api.flight.FlightBodyPoseProvider;
import neofontrender.addons.api.flight.FlightCameraTracking;
import neofontrender.addons.api.flight.FlightRegistration;
import neofontrender.addons.api.flight.FlightVector;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.client.FreeBodyRenderSample;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** UIE contract boundary: gait turns the visual root, never the camera's authoritative attitude. */
public final class SixDofBodyPresentation {
    static final int MODEL_UP_BODY_POSE_FLIGHT_API = 15;

    private SixDofBodyPresentation() { }

    public record Sample(FlightBodyPose body, FlightCameraTracking tracking) { }
    record FreePoseAttitudes(FlightAttitude body, FlightAttitude head) { }

    public static Sample sample(FlightAttitude view, float bodyYawOffsetDegrees) {
        FreePoseAttitudes attitudes = freePoseAttitudes(view, bodyYawOffsetDegrees);
        return new Sample(FlightBodyPose.modelUpY(attitudes.body(), attitudes.head()).withFreeShadowReference(), tracking(view));
    }

    /** The accepted body follows physical Q; live UIE head/camera Q never replaces collision geometry. */
    public static Sample sampleFree(PlayerPhysicalSnapshot snapshot, FlightAttitude view) {
        FreePoseAttitudes attitudes = acceptedFreePoseAttitudes(snapshot, view);
        return new Sample(FlightBodyPose.modelUpY(attitudes.body(), attitudes.head(),
                acceptedRootOffset(snapshot.placement())).withFreeShadowReference(), tracking(view));
    }

    /** Native rendering already interpolates transport; cancel that base before placing the physical root. */
    public static Sample sampleFree(FreeBodyRenderSample render, FlightAttitude view, Vec3d nativeRenderOrigin) {
        var q = render.attitude();
        return new Sample(FlightBodyPose.modelUpY(new FlightAttitude(q.x(), q.y(), q.z(), q.w()), view,
                fromWorld(render.feet().subtract(nativeRenderOrigin))).withGeometry(new FlightBodyGeometry(
                        fromWorld(render.capsuleStart()), fromWorld(render.capsuleEnd()), render.radius(), fromWorld(render.eye())))
                .withFreeShadowReference(), tracking(view));
    }

    /** Shared local/remote Free presentation: accepted body Q and the same live UIE head sample. */
    static FreePoseAttitudes acceptedFreePoseAttitudes(PlayerPhysicalSnapshot snapshot, FlightAttitude view) {
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.FreeCapsule))
            throw new IllegalArgumentException("Free presentation requires an accepted FreeCapsule shape");
        var q = snapshot.placement().attitude();
        return new FreePoseAttitudes(new FlightAttitude(q.x(), q.y(), q.z(), q.w()), view);
    }
    /** Computes the shared report/body attitudes without constructing a body pose. */
    static FreePoseAttitudes freePoseAttitudes(FlightAttitude view, float bodyYawOffsetDegrees) {
        FlightAttitude body = view.rotateLocal(0, 1, 0,
                -Math.toRadians(bodyYawOffsetDegrees));
        return new FreePoseAttitudes(body, view);
    }

    /** Accepted up and the owner's presented body forward are independent of the live render head look. */
    public static Sample sampleCardinal(PlayerPhysicalSnapshot snapshot,
                                        PlayerBodyReference.Effective effectiveReference,
                                        FlightVector worldLook, FlightVector viewRelativeBodyForward,
                                        float previousBodyYaw, float bodyYaw, float partialTicks) {
        CardinalBodyBasis basis = cardinalBodyBasis(snapshot, effectiveReference,
                viewRelativeBodyForward, previousBodyYaw, bodyYaw, partialTicks);
        FlightAttitude view = attitudeFromWorldLook(basis.up(), worldLook, basis.bodyHeading());
        return sampleCardinalAttitudes(snapshot, basis.body(), view);
    }

    /** Uses one complete render quaternion for both UIE tracking and the presented head pose. */
    static Sample sampleCardinalView(PlayerPhysicalSnapshot snapshot,
            PlayerBodyReference.Effective effectiveReference, CardinalCameraTransition.RenderSample render,
            FlightVector renderedEyeFromTransport, FlightVector rigidEye, FlightVector sampledWorldEye,
            FlightVector viewRelativeBodyForward, float previousBodyYaw, float bodyYaw,
            float partialTicks) {
        if (render == null) throw new IllegalArgumentException("render sample is required");
        CardinalBodyBasis basis = cardinalBodyBasis(snapshot, effectiveReference,
                viewRelativeBodyForward, previousBodyYaw, bodyYaw, partialTicks);
        // Apply only the shared reference transition to the body; live mouse pitch
        // stays in the head/camera and never tilts the standing collision box.
        Vec3d eyeFromFeet = snapshot.placement().eye()
                .subtract(snapshot.placement().physicalFeet().orElseThrow());
        FlightVector renderedEyeFromRoot = render.presentReferenceVector(fromWorld(eyeFromFeet));
        FlightVector root = new FlightVector(renderedEyeFromTransport.x - renderedEyeFromRoot.x,
                renderedEyeFromTransport.y - renderedEyeFromRoot.y,
                renderedEyeFromTransport.z - renderedEyeFromRoot.z);
        var bounds = snapshot.placement().bounds();
        Vec3d eye = snapshot.placement().eye();
        FlightVector center = render.presentReferenceVector(fromWorld(bounds.center().subtract(eye)));
        Vec3d half = bounds.max().subtract(bounds.min()).multiply(0.5);
        // Cardinal physics is an AABB, including short native poses that cannot be capsules.
        var geometry = FlightBodyGeometry.box(new FlightVector(rigidEye.x + center.x,
                        rigidEye.y + center.y, rigidEye.z + center.z),
                render.presentReferenceVector(new FlightVector(half.x(), 0, 0)),
                render.presentReferenceVector(new FlightVector(0, half.y(), 0)),
                render.presentReferenceVector(new FlightVector(0, 0, half.z())), sampledWorldEye);
        // The collision-clamped eye may differ from the rigid body eye during a reference transition.
        return new Sample(FlightBodyPose.modelUpY(render.presentBody(basis.body()), render.attitude(), root)
                .withGeometry(geometry)
                .withAxialShadowReference(fromWorld(effectiveReference.frame().up()))
                .withShadowBounds(shadowBounds(snapshot.placement(), rigidEye.x - eye.x(),
                        rigidEye.y - eye.y(), rigidEye.z - eye.z())),
                tracking(render.attitude()));
    }

    private static CardinalBodyBasis cardinalBodyBasis(PlayerPhysicalSnapshot snapshot,
            PlayerBodyReference.Effective effectiveReference, FlightVector viewRelativeBodyForward,
            float previousBodyYaw, float bodyYaw, float partialTicks) {
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal)) {
            throw new IllegalArgumentException("cardinal presentation requires an accepted cardinal shape");
        }
        if (!Float.isFinite(partialTicks)) throw new IllegalArgumentException("partialTicks must be finite");
        if (effectiveReference == null || effectiveReference.frame() != cardinal.frame()) {
            throw new IllegalArgumentException("accepted presentation reference must match cardinal geometry");
        }
        FlightVector up = fromWorld(cardinal.frame().up());
        FlightVector frameTangent = fromWorld(cardinal.frame().attitude().basisZ()).normalize();
        FlightVector bodyHeading;
        if (viewRelativeBodyForward != null) {
            bodyHeading = usableProjection(viewRelativeBodyForward, up);
            if (bodyHeading == null) throw new IllegalArgumentException("body forward is not tangent to its accepted frame");
        } else if (usesFrozenHeading(effectiveReference)) {
            bodyHeading = usableProjection(fromWorld(effectiveReference.tangentHeading()), up);
            if (bodyHeading == null) throw new IllegalArgumentException("accepted heading is not tangent to its frame");
        } else if (Float.isFinite(previousBodyYaw) && Float.isFinite(bodyYaw)) {
            float interpolatedYaw = previousBodyYaw
                    + MathHelper.wrapDegrees(bodyYaw - previousBodyYaw) * MathHelper.clamp(partialTicks, 0, 1);
            double yaw = Math.toRadians(interpolatedYaw);
            FlightVector nativeHeading = new FlightVector(-Math.sin(yaw), 0, Math.cos(yaw));
            FlightVector projected = usableProjection(nativeHeading, up);
            bodyHeading = projected == null ? frameTangent : projected;
        } else bodyHeading = frameTangent;
        FlightAttitude body = attitudeFromUpForward(up, bodyHeading);
        return new CardinalBodyBasis(up, bodyHeading, body);
    }

    private static Sample sampleCardinalAttitudes(PlayerPhysicalSnapshot snapshot,
            FlightAttitude body, FlightAttitude view) {
        return new Sample(FlightBodyPose.modelUpY(body, view, acceptedRootOffset(snapshot.placement()))
                        .withAxialShadowReference(fromWorld(((PlayerBodyShape.Cardinal) snapshot.placement().shape()).frame().up()))
                        .withShadowBounds(shadowBounds(snapshot.placement(), 0, 0, 0)),
                tracking(view));
    }

    private static FlightBodyBounds shadowBounds(PlayerBodyPlacement placement, double dx, double dy, double dz) {
        var bounds = placement.bounds();
        return new FlightBodyBounds(bounds.min().x() + dx, bounds.min().y() + dy, bounds.min().z() + dz,
                bounds.max().x() + dx, bounds.max().y() + dy, bounds.max().z() + dz);
    }

    private record CardinalBodyBasis(FlightVector up, FlightVector bodyHeading, FlightAttitude body) { }

    /** Keeps the accepted model root at its physical feet relative to this placement's transport. */
    private static FlightVector acceptedRootOffset(PlayerBodyPlacement placement) {
        Vec3d physicalFeet = placement.physicalFeet().orElseGet(() -> {
            if (!(placement.shape() instanceof PlayerBodyShape.FreeCapsule))
                throw new IllegalArgumentException("accepted root offset requires a supported body shape");
            var capsule = placement.capsule();
            Vec3d lower = capsule.centerline().start();
            Vec3d up = placement.bodyUp();
            double radius = capsule.radius();
            // Match the accepted Free foot's exact lower - up * radius operation with one final rounding.
            return new Vec3d(Math.fma(-up.x(), radius, lower.x()),
                    Math.fma(-up.y(), radius, lower.y()), Math.fma(-up.z(), radius, lower.z()));
        });
        Vec3d offset = physicalFeet.subtract(placement.transport());
        return new FlightVector(offset.x(), offset.y(), offset.z());
    }

    private static boolean usesFrozenHeading(PlayerBodyReference.Effective reference) {
        return reference.source() == PlayerBodyReference.Source.MANUAL
                || reference.source() == PlayerBodyReference.Source.ENVIRONMENT;
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

    /** Seeds one new accepted Cardinal camera lifecycle from its full current look and accepted frame. */
    static FlightAttitude seedCardinalCameraView(PlayerBodyReference.Effective effectiveReference,
            FlightAttitude currentView) {
        if (effectiveReference == null || currentView == null) {
            throw new IllegalArgumentException("accepted reference and current view are required");
        }
        return attitudeFromWorldLook(fromWorld(effectiveReference.frame().up()),
                currentView.forward(), fromWorld(effectiveReference.tangentHeading()));
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
