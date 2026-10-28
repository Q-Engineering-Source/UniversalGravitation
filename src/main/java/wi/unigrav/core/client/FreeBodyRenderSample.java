package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;

/** Render-only rigid sampling about the accepted body centre; never collision or installation authority. */
public record FreeBodyRenderSample(Vec3d center, Rotation3d attitude, Vec3d eye, Vec3d feet,
                                 Vec3d capsuleStart, Vec3d capsuleEnd, double radius) {
    public static FreeBodyRenderSample between(PlayerBodyPlacement start, PlayerBodyPlacement end, double alpha) {
        if (!(start.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !(end.shape() instanceof PlayerBodyShape.FreeCapsule)
                || !Double.isFinite(alpha)) throw new IllegalArgumentException("finite Free render span required");
        if (alpha <= 0) return endpoint(start);
        if (alpha >= 1 || start == end) return endpoint(end);
        Vec3d center = lerp(start.center(), end.center(), alpha);
        Rotation3d from = start.attitude(), to = end.attitude();
        // Preserve straight translation and exact stored eye offsets when the attitude did not change.
        if (from.equals(to)) return new FreeBodyRenderSample(center, to,
                lerp(start.eye(), end.eye(), alpha), lerp(foot(start), foot(end), alpha),
                lerp(start.capsule().centerline().start(), end.capsule().centerline().start(), alpha),
                lerp(start.capsule().centerline().end(), end.capsule().centerline().end(), alpha),
                start.capsule().radius() + (end.capsule().radius() - start.capsule().radius()) * alpha);
        Rotation3d attitude = slerp(from, to, alpha);
        return new FreeBodyRenderSample(center, attitude,
                point(start.eye(), end.eye(), start, end, center, attitude, alpha),
                point(foot(start), foot(end), start, end, center, attitude, alpha),
                point(start.capsule().centerline().start(), end.capsule().centerline().start(), start, end, center, attitude, alpha),
                point(start.capsule().centerline().end(), end.capsule().centerline().end(), start, end, center, attitude, alpha),
                start.capsule().radius() + (end.capsule().radius() - start.capsule().radius()) * alpha);
    }

    private static Vec3d point(Vec3d first, Vec3d last, PlayerBodyPlacement start, PlayerBodyPlacement end,
                              Vec3d center, Rotation3d attitude, double alpha) {
        Vec3d localStart = start.attitude().inverseRotate(first.subtract(start.center()));
        Vec3d localEnd = end.attitude().inverseRotate(last.subtract(end.center()));
        return center.add(attitude.rotate(lerp(localStart, localEnd, alpha)));
    }

    private static FreeBodyRenderSample endpoint(PlayerBodyPlacement placement) {
        return new FreeBodyRenderSample(placement.center(), placement.attitude(), placement.eye(), foot(placement),
                placement.capsule().centerline().start(), placement.capsule().centerline().end(), placement.capsule().radius());
    }

    private static Vec3d foot(PlayerBodyPlacement placement) {
        Vec3d lower = placement.capsule().centerline().start(), up = placement.bodyUp();
        double radius = placement.capsule().radius();
        return new Vec3d(Math.fma(-up.x(), radius, lower.x()), Math.fma(-up.y(), radius, lower.y()),
                Math.fma(-up.z(), radius, lower.z()));
    }

    private static Vec3d lerp(Vec3d start, Vec3d end, double alpha) {
        return new Vec3d(start.x() + (end.x() - start.x()) * alpha,
                start.y() + (end.y() - start.y()) * alpha, start.z() + (end.z() - start.z()) * alpha);
    }

    private static Rotation3d slerp(Rotation3d first, Rotation3d second, double alpha) {
        double dot = first.x() * second.x() + first.y() * second.y()
                + first.z() * second.z() + first.w() * second.w();
        double sign = dot < 0 ? -1 : 1;
        dot = Math.min(1, Math.abs(dot));
        double left = 1 - alpha, right = alpha;
        if (dot < .9995) {
            double angle = Math.acos(dot), sine = Math.sin(angle);
            left = Math.sin((1 - alpha) * angle) / sine;
            right = Math.sin(alpha * angle) / sine;
        }
        return new Rotation3d(first.x() * left + second.x() * sign * right,
                first.y() * left + second.y() * sign * right,
                first.z() * left + second.z() * sign * right,
                first.w() * left + second.w() * sign * right);
    }
}
