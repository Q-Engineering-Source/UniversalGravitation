package wi.unigrav.core.client.camera;

import wi.unigrav.api.math.Vec3;

/**
 * Pure, bounded numerical seam for the scene-depth ground-plane mask.
 *
 * <p>All positions are render-relative metres. Matrices use OpenGL column-major ordering and
 * window coordinates are local to the copied depth viewport. Invalid or numerically doubtful
 * input returns {@code null} (or a zero mask); callers must not substitute a world scan.
 */
final class SixDofSceneDepthMath {
    static final double D24_STEP = 1.0D / 16_777_215.0D;
    static final double RADIUS = 64.0D;
    static final double FULL_COVERAGE_RADIUS = 56.0D;
    static final double RECONSTRUCTION_GUARD = 328.0D;
    private static final double INVERSE_RESIDUAL = 1.0E-9D;
    private static final double FLOAT_INVERSE_RESIDUAL = 2.0E-4D;
    private static final double HOMOGENEOUS_EPSILON = 1.0E-10D;
    private static final double CLEAR_EPSILON = 2.0D * D24_STEP;
    private static final double HARD_PLANE_TOLERANCE = 1.0D / 64.0D;
    private static final double NEIGHBOUR_PLANE_TOLERANCE = 2.0D / 64.0D;
    private static final double COS_20 = Math.cos(Math.toRadians(20.0D));
    private static final double COS_12 = Math.cos(Math.toRadians(12.0D));

    private SixDofSceneDepthMath() {
    }

    static Frame prepare(double[] modelView, double[] projection, int width, int height,
                         double depthNear, double depthFar, double clearDepth,
                         Vec3 hitRelative, Vec3 outwardNormal) {
        if (!matrix(modelView) || !matrix(projection) || width <= 0 || height <= 0
                || !finite(depthNear, depthFar, clearDepth) || depthNear < 0.0D || depthNear > 1.0D
                || depthFar < 0.0D || depthFar > 1.0D || depthNear == depthFar
                || !nearClearEndpoint(clearDepth) || !finite(hitRelative) || !finite(outwardNormal)) {
            return null;
        }
        Vec3 normal = unit(outwardNormal);
        if (normal == null) return null;
        double[] clip = multiply(projection, modelView);
        double[] inverseClip = invert(clip);
        double[] inverseModelView = invert(modelView);
        float[] uploadedInverseClip = inverseClip == null ? null : toFloat(inverseClip);
        if (inverseClip == null || inverseModelView == null || residual(clip, inverseClip) > INVERSE_RESIDUAL
                || residual(modelView, inverseModelView) > INVERSE_RESIDUAL
                || uploadedInverseClip == null || floatResidual(clip, inverseClip) > FLOAT_INVERSE_RESIDUAL) return null;
        Vec3 eye = transformPoint(inverseModelView, 0.0D, 0.0D, 0.0D, 1.0D);
        Projection anchor = project(clip, width, height, depthNear, depthFar, hitRelative);
        if (eye == null || anchor == null || anchor.x() < 0.0D || anchor.x() >= width
                || anchor.y() < 0.0D || anchor.y() >= height || anchor.depth() < 0.0D || anchor.depth() > 1.0D) {
            return null;
        }
        Vec3 toHit = hitRelative.sub(eye);
        Vec3 ray = unit(toHit);
        if (ray == null) return null;
        Vec3[] basis = tangentBasis(normal);
        if (basis == null) return null;
        return new Frame(uploadedInverseClip, clip, eye, hitRelative, normal, basis[0], basis[1],
                anchor.x(), anchor.y(),
                depthNear, depthFar, clearDepth, width, height);
    }

    /** Projects a relative point using this frame's exact captured clip transform. */
    static Projection project(Frame frame, Vec3 point) {
        return frame == null || point == null ? null : project(frame.clip, frame.width,
                frame.height, frame.depthNear, frame.depthFar, point);
    }

    /** Unprojects a viewport-local depth sample. Returned positions are render-relative metres. */
    static Vec3 unproject(Frame frame, double windowX, double windowY, double depth) {
        if (frame == null || !finite(windowX, windowY, depth) || windowX < 0.0D || windowX > frame.width
                || windowY < 0.0D || windowY > frame.height || depth < 0.0D || depth > 1.0D) return null;
        double ndcZ = 2.0D * ((depth - frame.depthNear) / (frame.depthFar - frame.depthNear)) - 1.0D;
        return transformPoint(fromFloat(frame.inverseClip), 2.0D * windowX / frame.width - 1.0D,
                2.0D * windowY / frame.height - 1.0D, ndcZ, 1.0D);
    }

    /**
     * CPU oracle for the fragment surface gate. The five samples are center, left, right, down,
     * and up, all at exact viewport-local pixel centres. It deliberately returns zero at edges
     * and uncertain depth; this is not a world-geometry fallback.
     */
    static double surfaceMask(Frame frame, int pixelX, int pixelY, double centerDepth,
                              double leftDepth, double rightDepth, double downDepth, double upDepth) {
        if (frame == null || pixelX <= 0 || pixelY <= 0 || pixelX >= frame.width - 1 || pixelY >= frame.height - 1
                || clear(centerDepth, frame.clearDepth) || clear(leftDepth, frame.clearDepth)
                || clear(rightDepth, frame.clearDepth) || clear(downDepth, frame.clearDepth)
                || clear(upDepth, frame.clearDepth)) return 0.0D;
        double x = pixelX + 0.5D;
        double y = pixelY + 0.5D;
        Vec3 center = unproject(frame, x, y, centerDepth);
        Vec3 left = unproject(frame, x - 1.0D, y, leftDepth);
        Vec3 right = unproject(frame, x + 1.0D, y, rightDepth);
        Vec3 down = unproject(frame, x, y - 1.0D, downDepth);
        Vec3 up = unproject(frame, x, y + 1.0D, upDepth);
        if (!finite(center) || !finite(left) || !finite(right) || !finite(down) || !finite(up)
                || center.sub(frame.eyeRelative).norm() > RECONSTRUCTION_GUARD) return 0.0D;
        double uncertainty = normalUncertainty(frame, center, x, y, centerDepth);
        double distance = Math.abs(planeDistance(frame, center));
        double requiredTolerance = 1.0D / 1024.0D + 2.0D * uncertainty;
        if (!finite(uncertainty, requiredTolerance) || requiredTolerance >= HARD_PLANE_TOLERANCE
                || distance > requiredTolerance) return 0.0D;
        double coverage = coverageFade(frame, center);
        if (coverage <= 0.0D || !onNeighbourPlane(frame, left) || !onNeighbourPlane(frame, right)
                || !onNeighbourPlane(frame, down) || !onNeighbourPlane(frame, up)) return 0.0D;
        Vec3 horizontal = right.sub(left);
        Vec3 vertical = up.sub(down);
        double horizontalLength = horizontal.norm();
        double verticalLength = vertical.norm();
        Vec3 reconstructed = unit(horizontal.cross(vertical));
        if (reconstructed == null || horizontalLength <= 0.0D || verticalLength <= 0.0D
                || horizontalLength > 2.0D || verticalLength > 2.0D
                || !saneOpposite(left, center, right) || !saneOpposite(down, center, up)
                || horizontal.cross(vertical).norm() / (horizontalLength * verticalLength) <= 0.1D) return 0.0D;
        Vec3 toEye = unit(frame.eyeRelative.sub(center));
        if (toEye == null) return 0.0D;
        if (reconstructed.dot(toEye) < 0.0D) reconstructed = reconstructed.negate();
        double normalAgreement = reconstructed.dot(frame.normal);
        if (normalAgreement <= COS_20) return 0.0D;
        double facing = frame.normal.dot(toEye);
        if (facing <= 0.05D) return 0.0D;
        return clamp(coverage * smooth(normalAgreement, COS_20, COS_12) * smooth(facing, 0.05D, 0.15D)
                * (1.0D - smooth(requiredTolerance, 1.0D / 128.0D, HARD_PLANE_TOLERANCE)));
    }

    static boolean anchorMatches(Frame frame, int pixelX, int pixelY, double sampledDepth) {
        if (frame == null || pixelX < 0 || pixelY < 0 || pixelX >= frame.width || pixelY >= frame.height
                || clear(sampledDepth, frame.clearDepth)) {
            return false;
        }
        Vec3 sample = unproject(frame, pixelX + 0.5D, pixelY + 0.5D, sampledDepth);
        if (sample == null) return false;
        double uncertainty = normalUncertainty(frame, sample, pixelX + 0.5D, pixelY + 0.5D, sampledDepth);
        double requiredTolerance = 1.0D / 1024.0D + 2.0D * uncertainty;
        return finite(requiredTolerance) && requiredTolerance < HARD_PLANE_TOLERANCE
                && Math.abs(planeDistance(frame, sample)) <= requiredTolerance
                && Math.abs(sample.sub(frame.hitRelative).dot(frame.tangent0)) <= 1.0D
                && Math.abs(sample.sub(frame.hitRelative).dot(frame.tangent1)) <= 1.0D;
    }

    /**
     * CPU oracle for the vertex shader's bounded 3 by 3 exact-hit reconciliation probe.
     * Samples outside the copied viewport are never requested. A match is not a depth equality:
     * {@link #anchorMatches(Frame, int, int, double)} reconstructs and verifies the candidate
     * against the selected plane and the native hit's one-metre tangent square.
     */
    static boolean anchorProbe(Frame frame, DepthSamples samples) {
        if (frame == null || samples == null) return false;
        int baseX = (int) Math.floor(frame.anchorU);
        int baseY = (int) Math.floor(frame.anchorV);
        for (int offsetY = -1; offsetY <= 1; offsetY++) for (int offsetX = -1; offsetX <= 1; offsetX++) {
            int pixelX = baseX + offsetX;
            int pixelY = baseY + offsetY;
            if (pixelX < 0 || pixelY < 0 || pixelX >= frame.width || pixelY >= frame.height) continue;
            if (anchorMatches(frame, pixelX, pixelY, samples.depthAt(pixelX, pixelY))) return true;
        }
        return false;
    }

    private static boolean onNeighbourPlane(Frame frame, Vec3 point) {
        return point.sub(frame.eyeRelative).norm() <= RECONSTRUCTION_GUARD
                && Math.abs(planeDistance(frame, point)) <= NEIGHBOUR_PLANE_TOLERANCE
                && coverageFade(frame, point) > 0.0D;
    }

    private static boolean saneOpposite(Vec3 first, Vec3 center, Vec3 second) {
        double a = first.sub(center).norm();
        double b = second.sub(center).norm();
        return a > 0.0D && b > 0.0D && a <= 1.0D && b <= 1.0D && Math.max(a, b) / Math.min(a, b) <= 4.0D;
    }

    private static double normalUncertainty(Frame frame, Vec3 center, double x, double y, double depth) {
        Vec3 lower = unproject(frame, x, y, clamp(depth - D24_STEP));
        Vec3 upper = unproject(frame, x, y, clamp(depth + D24_STEP));
        return lower == null || upper == null ? Double.NaN
                : Math.max(Math.abs(lower.sub(center).dot(frame.normal)), Math.abs(upper.sub(center).dot(frame.normal)));
    }

    /** Production radial coverage seam: r<=56 m is full, r=60 m is half, r>=64 m is zero. */
    static double coverageFade(Frame frame, Vec3 point) {
        if (frame == null || !finite(point)) return 0.0D;
        Vec3 offset = point.sub(frame.hitRelative);
        double u = Math.abs(offset.dot(frame.tangent0));
        double v = Math.abs(offset.dot(frame.tangent1));
        double radius = Math.hypot(u, v);
        return finite(radius) ? 1.0D - smooth(radius, FULL_COVERAGE_RADIUS, RADIUS) : 0.0D;
    }

    private static double planeDistance(Frame frame, Vec3 point) {
        return point.sub(frame.hitRelative).dot(frame.normal);
    }

    private static boolean clear(double depth, double clearDepth) {
        return !Double.isFinite(depth) || depth < 0.0D || depth > 1.0D || Math.abs(depth - clearDepth) <= CLEAR_EPSILON;
    }

    private static boolean nearClearEndpoint(double depth) {
        return depth >= 0.0D && depth <= 1.0D && (depth <= CLEAR_EPSILON || depth >= 1.0D - CLEAR_EPSILON);
    }

    private static Vec3[] tangentBasis(Vec3 normal) {
        Vec3 reference = Math.abs(normal.x()) <= Math.abs(normal.y()) && Math.abs(normal.x()) <= Math.abs(normal.z())
                ? new Vec3(1.0D, 0.0D, 0.0D)
                : (Math.abs(normal.y()) <= Math.abs(normal.z()) ? new Vec3(0.0D, 1.0D, 0.0D) : new Vec3(0.0D, 0.0D, 1.0D));
        Vec3 first = unit(reference.cross(normal));
        Vec3 second = first == null ? null : unit(normal.cross(first));
        return first == null || second == null ? null : new Vec3[] {first, second};
    }

    private static Projection project(double[] clip, int width, int height, double depthNear, double depthFar, Vec3 point) {
        double[] value = multiply(clip, point.x(), point.y(), point.z(), 1.0D);
        if (!finite(value) || value[3] <= HOMOGENEOUS_EPSILON) return null;
        double invW = 1.0D / value[3];
        double nx = value[0] * invW;
        double ny = value[1] * invW;
        double nz = value[2] * invW;
        if (!finite(nx, ny, nz)) return null;
        return new Projection((nx + 1.0D) * width * 0.5D, (ny + 1.0D) * height * 0.5D,
                depthNear + (nz + 1.0D) * 0.5D * (depthFar - depthNear));
    }

    private static Vec3 transformPoint(double[] matrix, double x, double y, double z, double w) {
        double[] value = multiply(matrix, x, y, z, w);
        if (!finite(value) || Math.abs(value[3]) <= HOMOGENEOUS_EPSILON) return null;
        double inverseW = 1.0D / value[3];
        Vec3 result = new Vec3(value[0] * inverseW, value[1] * inverseW, value[2] * inverseW);
        return finite(result) ? result : null;
    }

    private static double[] invert(double[] source) {
        double[][] augmented = new double[4][8];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            augmented[row][column] = source[column * 4 + row];
            augmented[row][column + 4] = row == column ? 1.0D : 0.0D;
        }
        for (int pivot = 0; pivot < 4; pivot++) {
            int selected = pivot;
            for (int row = pivot + 1; row < 4; row++) if (Math.abs(augmented[row][pivot]) > Math.abs(augmented[selected][pivot])) selected = row;
            double rowScale = 0.0D;
            for (int column = pivot; column < 4; column++) rowScale = Math.max(rowScale, Math.abs(augmented[selected][column]));
            if (!Double.isFinite(augmented[selected][pivot]) || rowScale == 0.0D
                    || Math.abs(augmented[selected][pivot]) <= rowScale * 1.0E-12D) return null;
            double[] swap = augmented[pivot]; augmented[pivot] = augmented[selected]; augmented[selected] = swap;
            double scale = augmented[pivot][pivot];
            for (int column = 0; column < 8; column++) augmented[pivot][column] /= scale;
            for (int row = 0; row < 4; row++) if (row != pivot) {
                double factor = augmented[row][pivot];
                for (int column = 0; column < 8; column++) augmented[row][column] -= factor * augmented[pivot][column];
            }
        }
        double[] inverse = new double[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) inverse[column * 4 + row] = augmented[row][column + 4];
        return matrix(inverse) ? inverse : null;
    }

    private static double residual(double[] left, double[] right) {
        if (!matrix(left) || !matrix(right)) return Double.POSITIVE_INFINITY;
        double worst = 0.0D;
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) {
            double expected = row == column ? 1.0D : 0.0D;
            double product = multiply(left, right, row, column);
            double error = Math.abs(product - expected);
            if (!Double.isFinite(product) || !Double.isFinite(error)) return Double.POSITIVE_INFINITY;
            worst = Math.max(worst, error);
        }
        return worst;
    }

    private static double floatResidual(double[] left, double[] inverse) {
        float[] uploaded = toFloat(inverse);
        return uploaded == null ? Double.POSITIVE_INFINITY : residual(left, fromFloat(uploaded));
    }

    private static double multiply(double[] left, double[] right, int row, int column) {
        double sum = 0.0D;
        for (int i = 0; i < 4; i++) sum = Math.fma(left[i * 4 + row], right[column * 4 + i], sum);
        return sum;
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] result = new double[16];
        for (int row = 0; row < 4; row++) for (int column = 0; column < 4; column++) result[column * 4 + row] = multiply(left, right, row, column);
        return result;
    }

    private static double[] multiply(double[] matrix, double x, double y, double z, double w) {
        return new double[] {Math.fma(matrix[0], x, Math.fma(matrix[4], y, Math.fma(matrix[8], z, matrix[12] * w))),
                Math.fma(matrix[1], x, Math.fma(matrix[5], y, Math.fma(matrix[9], z, matrix[13] * w))),
                Math.fma(matrix[2], x, Math.fma(matrix[6], y, Math.fma(matrix[10], z, matrix[14] * w))),
                Math.fma(matrix[3], x, Math.fma(matrix[7], y, Math.fma(matrix[11], z, matrix[15] * w)))};
    }

    private static float[] toFloat(double[] values) {
        if (values == null || values.length != 16) return null;
        float[] result = new float[16];
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) values[i];
            if (!Float.isFinite(result[i])) return null;
        }
        return result;
    }

    private static double[] fromFloat(float[] values) {
        double[] result = new double[16];
        for (int i = 0; i < result.length; i++) result[i] = values[i];
        return result;
    }

    private static Vec3 unit(Vec3 value) {
        if (!finite(value)) return null;
        double length = value.norm();
        return Double.isFinite(length) && length > HOMOGENEOUS_EPSILON ? value.scale(1.0D / length) : null;
    }

    private static boolean matrix(double[] value) { return value != null && value.length == 16 && finite(value); }
    private static boolean finite(Vec3 value) { return value != null && finite(value.x(), value.y(), value.z()); }
    private static boolean finite(double... values) { for (double value : values) if (!Double.isFinite(value)) return false; return true; }
    private static double clamp(double value) { return Math.max(0.0D, Math.min(1.0D, value)); }
    private static double smooth(double value, double low, double high) {
        double t = clamp((value - low) / (high - low));
        return t * t * (3.0D - 2.0D * t);
    }

    static final class Frame {
        private final float[] inverseClip;
        private final double[] clip;
        private final Vec3 eyeRelative, hitRelative, normal, tangent0, tangent1;
        private final double anchorU, anchorV, depthNear, depthFar, clearDepth;
        private final int width, height;
        private Frame(float[] inverseClip, double[] clip, Vec3 eyeRelative, Vec3 hitRelative, Vec3 normal, Vec3 tangent0, Vec3 tangent1,
                      double anchorU, double anchorV,
                      double depthNear, double depthFar, double clearDepth, int width, int height) {
            this.inverseClip = inverseClip.clone(); this.clip = clip.clone();
            this.eyeRelative = eyeRelative; this.hitRelative = hitRelative; this.normal = normal;
            this.tangent0 = tangent0; this.tangent1 = tangent1; this.anchorU = anchorU; this.anchorV = anchorV;
            this.depthNear = depthNear;
            this.depthFar = depthFar; this.clearDepth = clearDepth; this.width = width; this.height = height;
        }
        float[] inverseClip() { return inverseClip.clone(); }
        Vec3 eyeRelative() { return eyeRelative; } Vec3 hitRelative() { return hitRelative; }
        Vec3 normal() { return normal; } Vec3 tangent0() { return tangent0; } Vec3 tangent1() { return tangent1; }
        double anchorU() { return anchorU; } double anchorV() { return anchorV; }
        double depthNear() { return depthNear; }
        double depthFar() { return depthFar; } double clearDepth() { return clearDepth; }
        int width() { return width; } int height() { return height; }
    }

    record Projection(double x, double y, double depth) { }

    @FunctionalInterface
    interface DepthSamples {
        double depthAt(int pixelX, int pixelY);
    }
}
