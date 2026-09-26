package wi.unigrav.core.client.camera;

import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightBodyPose;
import neofontrender.addons.api.flight.FlightCameraTracking;

/** UIE contract boundary: gait turns the visual root, never the camera's authoritative attitude. */
public final class SixDofBodyPresentation {
    private SixDofBodyPresentation() { }

    public record Sample(FlightBodyPose body, FlightCameraTracking tracking) { }

    public static Sample sample(FlightAttitude view, float bodyYawOffsetDegrees) {
        return new Sample(new FlightBodyPose(view.rotateLocal(0, 1, 0, -Math.toRadians(bodyYawOffsetDegrees))),
                FlightCameraTracking.rigid(view));
    }
}
