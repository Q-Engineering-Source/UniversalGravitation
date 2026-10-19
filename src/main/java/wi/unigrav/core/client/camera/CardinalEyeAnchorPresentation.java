package wi.unigrav.core.client.camera;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import neofontrender.addons.api.camera.CameraApi;
import neofontrender.addons.api.camera.CameraCollisionQuery;
import neofontrender.addons.api.camera.CameraVector;
import neofontrender.addons.api.flight.FlightVector;

/** Render-only eye correction and obstruction clamp; accepted body placement remains authoritative. */
final class CardinalEyeAnchorPresentation {

    private static final double RADIUS = 0.1D;
    private static final double HIT_CLEARANCE = 0.01D;

    private CardinalEyeAnchorPresentation() { }

    static net.minecraft.util.math.Vec3d resolve(World world, Vec3d provenEye,
            FlightVector referenceCorrection) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(provenEye, "provenEye");
        Objects.requireNonNull(referenceCorrection, "referenceCorrection");
        requireFinite(provenEye.x(), provenEye.y(), provenEye.z(), "proven eye");
        requireFinite(referenceCorrection.x, referenceCorrection.y, referenceCorrection.z,
                "eye reference correction");

        double length = Math.hypot(Math.hypot(referenceCorrection.x, referenceCorrection.y),
                referenceCorrection.z);
        if (!Double.isFinite(length)) {
            throw new IllegalStateException("Cardinal eye reference correction length is non-finite");
        }
        net.minecraft.util.math.Vec3d start = new net.minecraft.util.math.Vec3d(
                provenEye.x(), provenEye.y(), provenEye.z());
        if (length < 1.0E-8D) return start;

        net.minecraft.util.math.Vec3d desired = start.add(referenceCorrection.x,
                referenceCorrection.y, referenceCorrection.z);
        net.minecraft.util.math.Vec3d vanillaClamped = clampWorldObstruction(world, start, desired);
        CameraVector external = CameraApi.resolveCollision(new CameraCollisionQuery(
                new CameraVector(start.x, start.y, start.z),
                new CameraVector(vanillaClamped.x, vanillaClamped.y, vanillaClamped.z), RADIUS));
        net.minecraft.util.math.Vec3d resolved = vanillaClamped;
        if (external != null && !samePosition(external, vanillaClamped)) {
            net.minecraft.util.math.Vec3d externalCandidate = new net.minecraft.util.math.Vec3d(
                    external.x, external.y, external.z);
            requireFinite(externalCandidate.x, externalCandidate.y, externalCandidate.z,
                    "external eye collision result");
            // Third-party camera providers may move their result; keep UG's real-world eye sweep
            // authoritative after that callback as well.
            resolved = clampWorldObstruction(world, start, externalCandidate);
        }
        requireFinite(resolved.x, resolved.y, resolved.z, "resolved eye anchor");
        return resolved;
    }

    private static net.minecraft.util.math.Vec3d clampWorldObstruction(World world,
            net.minecraft.util.math.Vec3d start, net.minecraft.util.math.Vec3d candidate) {
        net.minecraft.util.math.Vec3d movement = candidate.subtract(start);
        double length = Math.hypot(Math.hypot(movement.x, movement.y), movement.z);
        if (!Double.isFinite(length)) {
            throw new IllegalStateException("Cardinal eye obstruction sweep length is non-finite");
        }
        if (length < 1.0E-8D) return start;

        double allowed = length;
        for (int i = 0; i <= 8; i++) {
            net.minecraft.util.math.Vec3d corner = i == 8
                    ? new net.minecraft.util.math.Vec3d(0.0D, 0.0D, 0.0D)
                    : new net.minecraft.util.math.Vec3d((i & 1) == 0 ? -RADIUS : RADIUS,
                            (i & 2) == 0 ? -RADIUS : RADIUS,
                            (i & 4) == 0 ? -RADIUS : RADIUS);
            net.minecraft.util.math.Vec3d rayStart = start.add(corner);
            RayTraceResult hit = world.rayTraceBlocks(rayStart, rayStart.add(movement),
                    false, true, false);
            if (hit != null && hit.hitVec != null) {
                allowed = Math.min(allowed,
                        Math.max(0.0D, rayStart.distanceTo(hit.hitVec) - HIT_CLEARANCE));
            }
        }
        return start.add(movement.scale(Math.min(1.0D, allowed / length)));
    }

    private static boolean samePosition(CameraVector first, net.minecraft.util.math.Vec3d second) {
        return Double.doubleToLongBits(first.x) == Double.doubleToLongBits(second.x)
                && Double.doubleToLongBits(first.y) == Double.doubleToLongBits(second.y)
                && Double.doubleToLongBits(first.z) == Double.doubleToLongBits(second.z);
    }

    private static void requireFinite(double x, double y, double z, String description) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException(description + " must be finite");
        }
    }
}
