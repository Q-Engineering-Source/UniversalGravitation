package wi.unigrav.core.client.camera;

import java.util.Objects;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.ViewRelativeMovementBasis;
import wi.unigrav.core.client.TangentViewSample;
import wi.unigrav.core.player.PlayerBodyReference;

/** Immutable world-space movement basis, independent from the live camera quaternion. */
record SixDofMovementFrame(Vec3 forward, Vec3 right, Vec3 up) {

    private static final double BASIS_EPSILON = 1.0e-9;

    SixDofMovementFrame {
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(up, "up");
        if (!isUnit(forward) || !isUnit(right) || !isUnit(up)
                || Math.abs(forward.dot(right)) > BASIS_EPSILON
                || Math.abs(forward.dot(up)) > BASIS_EPSILON
                || Math.abs(right.dot(up)) > BASIS_EPSILON) {
            throw new IllegalArgumentException("movement frame must be finite and orthonormal");
        }
    }

    static SixDofMovementFrame fromCamera(SixDofCameraOrientation.Snapshot attitude) {
        if (attitude == null || attitude.forward() == null || attitude.right() == null || attitude.up() == null) {
            return null;
        }
        if (!isUnit(attitude.forward()) || !isUnit(attitude.right()) || !isUnit(attitude.up())
                || Math.abs(attitude.forward().dot(attitude.right())) > BASIS_EPSILON
                || Math.abs(attitude.forward().dot(attitude.up())) > BASIS_EPSILON
                || Math.abs(attitude.right().dot(attitude.up())) > BASIS_EPSILON) {
            return null;
        }
        return new SixDofMovementFrame(attitude.forward(), attitude.right(), attitude.up());
    }

    static SixDofMovementFrame fromSample(TangentViewSample sample) {
        var basis = Objects.requireNonNull(sample, "sample").basis();
        return new SixDofMovementFrame(basis.forward(), basis.right(), basis.up());
    }

    static SixDofMovementFrame fromAcceptedReference(PlayerBodyReference.Effective reference) {
        Objects.requireNonNull(reference, "reference");
        var acceptedForward = reference.tangentHeading();
        var frameUp = reference.frame().up();
        Vec3 forward = new Vec3(acceptedForward.x(), acceptedForward.y(), acceptedForward.z());
        Vec3 up = new Vec3(frameUp.x(), frameUp.y(), frameUp.z());
        Vec3 right = up.cross(forward);
        return new SixDofMovementFrame(forward, right, up);
    }

    static SixDofMovementFrame fromView(PlayerBodyReference.Effective reference,
            SixDofCameraOrientation.Snapshot sampled) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(sampled, "sampled");
        var up = reference.frame().up();
        var heading = reference.tangentHeading();
        var basis = ViewRelativeMovementBasis.sample(new Vec3(up.x(), up.y(), up.z()),
                sampled.forward(), new Vec3(heading.x(), heading.y(), heading.z()));
        return new SixDofMovementFrame(basis.forward(), basis.right(), basis.up());
    }
    static SixDofMovementFrame locked(SixDofAlignmentInput.Reference reference,
            SixDofCameraOrientation.Snapshot attitude) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(attitude, "attitude");
        SixDofHorizonAlignment.Frame horizon = SixDofHorizonAlignment.frame(
                reference.normal(), attitude.forward(), attitude.up());
        Vec3 normal = horizon.normal();
        Vec3 left = horizon.left();
        return new SixDofMovementFrame(left.cross(normal), left.scale(-1.0), normal);
    }

    private static boolean isUnit(Vec3 vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z())
                && Math.abs(vector.normSquared() - 1.0) <= BASIS_EPSILON;
    }
}
