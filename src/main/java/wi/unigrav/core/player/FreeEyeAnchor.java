package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

/** Exact stored-basis eye mapping. This value carries no pose or installation authority. */
public record FreeEyeAnchor(int recipeVersion, Vec3d center, Vec3d eye, Rotation3d attitude) {
    public static final int STORED_BASIS_V1 = 1;

    public FreeEyeAnchor {
        if (recipeVersion != STORED_BASIS_V1) throw new IllegalArgumentException("unknown Free eye recipe");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(attitude, "attitude");
        requireFinite(center); requireFinite(eye);
    }

    Vec3d at(Vec3d currentCenter, Rotation3d currentAttitude, BootQueryArithmetic arithmetic) {
        Objects.requireNonNull(currentCenter); Objects.requireNonNull(currentAttitude);
        Objects.requireNonNull(arithmetic); requireFinite(currentCenter);
        if (sameRaw(attitude, currentAttitude)) {
            if (sameRaw(center, currentCenter)) return eye;
            return new Vec3d(translated(eye.x(), center.x(), currentCenter.x(), arithmetic),
                    translated(eye.y(), center.y(), currentCenter.y(), arithmetic),
                    translated(eye.z(), center.z(), currentCenter.z(), arithmetic));
        }
        // The stored binary64 basis is not assumed exactly orthogonal. Compute adj(B0)*d
        // and det(B0) as dyadics; no rounded local offset or transposed inverse is stored.
        var b0 = column(attitude.basisX(), arithmetic);
        var b1 = column(attitude.basisY(), arithmetic);
        var b2 = column(attitude.basisZ(), arithmetic);
        var d = new BootQueryArithmetic.Number[] {
                arithmetic.subtract(arithmetic.exact(eye.x()), arithmetic.exact(center.x())),
                arithmetic.subtract(arithmetic.exact(eye.y()), arithmetic.exact(center.y())),
                arithmetic.subtract(arithmetic.exact(eye.z()), arithmetic.exact(center.z())) };
        var row0 = cross(b1, b2, arithmetic);
        var row1 = cross(b2, b0, arithmetic);
        var row2 = cross(b0, b1, arithmetic);
        var determinant = dot(b0, row0, arithmetic);
        if (arithmetic.sign(determinant) == 0) throw new IllegalArgumentException("singular stored eye-anchor basis");
        var a = new BootQueryArithmetic.Number[] {
                dot(row0, d, arithmetic), dot(row1, d, arithmetic), dot(row2, d, arithmetic) };
        var c0 = currentAttitude.basisX();
        var c1 = currentAttitude.basisY();
        var c2 = currentAttitude.basisZ();
        return new Vec3d(mapped(currentCenter.x(), c0.x(), c1.x(), c2.x(), a, determinant, arithmetic),
                mapped(currentCenter.y(), c0.y(), c1.y(), c2.y(), a, determinant, arithmetic),
                mapped(currentCenter.z(), c0.z(), c1.z(), c2.z(), a, determinant, arithmetic));
    }

    private static double translated(double eye, double anchor, double current, BootQueryArithmetic arithmetic) {
        return arithmetic.roundNearest(arithmetic.add(arithmetic.exact(eye),
                arithmetic.subtract(arithmetic.exact(current), arithmetic.exact(anchor))));
    }
    private static double mapped(double center, double x, double y, double z,
                                 BootQueryArithmetic.Number[] a, BootQueryArithmetic.Number determinant,
                                 BootQueryArithmetic arithmetic) {
        var numerator = arithmetic.add(arithmetic.multiply(determinant, arithmetic.exact(center)),
                arithmetic.add(arithmetic.add(arithmetic.multiply(arithmetic.exact(x), a[0]),
                        arithmetic.multiply(arithmetic.exact(y), a[1])),
                        arithmetic.multiply(arithmetic.exact(z), a[2])));
        return arithmetic.roundNearest(arithmetic.fraction(numerator, determinant));
    }
    private static BootQueryArithmetic.Number[] column(Vec3d value, BootQueryArithmetic arithmetic) {
        return new BootQueryArithmetic.Number[] {
                arithmetic.exact(value.x()), arithmetic.exact(value.y()), arithmetic.exact(value.z()) };
    }
    private static BootQueryArithmetic.Number[] cross(BootQueryArithmetic.Number[] a,
                                                     BootQueryArithmetic.Number[] b, BootQueryArithmetic arithmetic) {
        return new BootQueryArithmetic.Number[] {
                arithmetic.subtract(arithmetic.multiply(a[1], b[2]), arithmetic.multiply(a[2], b[1])),
                arithmetic.subtract(arithmetic.multiply(a[2], b[0]), arithmetic.multiply(a[0], b[2])),
                arithmetic.subtract(arithmetic.multiply(a[0], b[1]), arithmetic.multiply(a[1], b[0])) };
    }
    private static BootQueryArithmetic.Number dot(BootQueryArithmetic.Number[] a,
                                                 BootQueryArithmetic.Number[] b, BootQueryArithmetic arithmetic) {
        return arithmetic.add(arithmetic.add(arithmetic.multiply(a[0], b[0]),
                arithmetic.multiply(a[1], b[1])), arithmetic.multiply(a[2], b[2]));
    }
    private static boolean sameRaw(Rotation3d a, Rotation3d b) {
        return sameRaw(a.x(), b.x()) && sameRaw(a.y(), b.y()) && sameRaw(a.z(), b.z()) && sameRaw(a.w(), b.w());
    }
    private static boolean sameRaw(Vec3d a, Vec3d b) {
        return sameRaw(a.x(), b.x()) && sameRaw(a.y(), b.y()) && sameRaw(a.z(), b.z());
    }
    private static boolean sameRaw(double a, double b) {
        return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
    }
    private static void requireFinite(Vec3d vector) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y()) || !Double.isFinite(vector.z()))
            throw new IllegalArgumentException("finite eye anchor required");
    }
}
