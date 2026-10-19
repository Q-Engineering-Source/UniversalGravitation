package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;

/**
 * Raw proof-bearing FreeCapsule placement fields for the first restore codec.
 * Raw IEEE-754 bits are retained until the codec rejects non-finite values and
 * non-canonical negative zero, before constructing CHB vectors or bounds.
 */
record FreeCapsulePlacementV1(RawVec3 currentCenter, RawVec3 storedHalfLine,
                              RawVec3 centerReference, Proof proof, Optional<BodyProfile> bodyProfile) {
    static final int NOMINAL_TAG = 0;
    static final int AXIS_PLANE_TAG = 1;

    FreeCapsulePlacementV1(RawVec3 currentCenter, RawVec3 storedHalfLine, RawVec3 centerReference, Proof proof) {
        this(currentCenter, storedHalfLine, centerReference, proof, Optional.empty());
    }

    record BodyProfile(long diameterBits, long lengthBits, int eyeRecipeVersion,
                       RawVec3 eyeCenter, RawVec3 worldEye, RawQuaternion eyeAttitude) {
        BodyProfile {
            Objects.requireNonNull(eyeCenter); Objects.requireNonNull(worldEye); Objects.requireNonNull(eyeAttitude);
        }
    }

    FreeCapsulePlacementV1 {
        Objects.requireNonNull(currentCenter, "currentCenter");
        Objects.requireNonNull(storedHalfLine, "storedHalfLine");
        Objects.requireNonNull(centerReference, "centerReference");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(bodyProfile, "bodyProfile");
    }

    sealed interface Proof permits Nominal, AxisPlane { }

    /** The realized center is exactly centerReference for this sealed variant. */
    record Nominal() implements Proof { }

    /** The original one-axis witness; it does not grant path or collision authority. */
    record AxisPlane(RawVec3 realizedCenter, int modifiedAxis, int outwardSign,
                     long supportCoordinateBits) implements Proof {
        AxisPlane {
            Objects.requireNonNull(realizedCenter, "realizedCenter");
        }
    }

    /** Unknown tags fail while decoding the bounded proof variant. */
    static Proof decodeProofTag(int tag, RawVec3 realizedCenter, int modifiedAxis,
                                int outwardSign, long supportCoordinateBits) {
        return switch (tag) {
            case NOMINAL_TAG -> new Nominal();
            case AXIS_PLANE_TAG -> new AxisPlane(realizedCenter, modifiedAxis, outwardSign,
                    supportCoordinateBits);
            default -> throw new IllegalArgumentException("unknown FreeCapsule realization proof tag: " + tag);
        };
    }

    int rawFieldCount() {
        // Three vectors and one tag, plus the optional axis-plane witness fields.
        return (proof instanceof AxisPlane ? 16 : 10) + (bodyProfile.isPresent() ? 13 : 0);
    }

    record RawVec3(long xBits, long yBits, long zBits) {
        static RawVec3 from(Vec3d vector) {
            Objects.requireNonNull(vector, "vector");
            return new RawVec3(Double.doubleToRawLongBits(vector.x()),
                    Double.doubleToRawLongBits(vector.y()), Double.doubleToRawLongBits(vector.z()));
        }

        double x() { return Double.longBitsToDouble(xBits); }
        double y() { return Double.longBitsToDouble(yBits); }
        double z() { return Double.longBitsToDouble(zBits); }

        void requireFiniteCanonical(String label) {
            requireScalar(xBits, label + ".x");
            requireScalar(yBits, label + ".y");
            requireScalar(zBits, label + ".z");
        }

        Vec3d toVector(String label) {
            requireFiniteCanonical(label);
            return new Vec3d(x(), y(), z());
        }

        private static void requireScalar(long bits, String label) {
            double value = Double.longBitsToDouble(bits);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(label + " must be finite");
            }
            if (bits == Long.MIN_VALUE) {
                throw new IllegalArgumentException(label + " must use canonical positive zero");
            }
        }
    }

    record RawQuaternion(long xBits, long yBits, long zBits, long wBits) {
        static RawQuaternion from(Rotation3d rotation) {
            Objects.requireNonNull(rotation, "rotation");
            return new RawQuaternion(Double.doubleToRawLongBits(rotation.x()),
                    Double.doubleToRawLongBits(rotation.y()), Double.doubleToRawLongBits(rotation.z()),
                    Double.doubleToRawLongBits(rotation.w()));
        }

        double x() { return Double.longBitsToDouble(xBits); }
        double y() { return Double.longBitsToDouble(yBits); }
        double z() { return Double.longBitsToDouble(zBits); }
        double w() { return Double.longBitsToDouble(wBits); }

        void requireFiniteCanonical() {
            RawVec3.requireScalar(xBits, "raw quaternion.x");
            RawVec3.requireScalar(yBits, "raw quaternion.y");
            RawVec3.requireScalar(zBits, "raw quaternion.z");
            RawVec3.requireScalar(wBits, "raw quaternion.w");
        }

        Rotation3d reconstructExact() {
            requireFiniteCanonical();
            return Rotation3d.reconstructExact(x(), y(), z(), w());
        }

        boolean exactlyMatches(Rotation3d rotation) {
            return rotation != null
                    && xBits == Double.doubleToRawLongBits(rotation.x())
                    && yBits == Double.doubleToRawLongBits(rotation.y())
                    && zBits == Double.doubleToRawLongBits(rotation.z())
                    && wBits == Double.doubleToRawLongBits(rotation.w());
        }
    }

    record RawAabb(RawVec3 min, RawVec3 max) {
        RawAabb {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }

        static RawAabb from(Aabb bounds) {
            Objects.requireNonNull(bounds, "bounds");
            return new RawAabb(RawVec3.from(bounds.min()), RawVec3.from(bounds.max()));
        }

        Aabb toBounds(String label) {
            min.requireFiniteCanonical(label + ".min");
            max.requireFiniteCanonical(label + ".max");
            if (!(min.x() < max.x() && min.y() < max.y() && min.z() < max.z())) {
                throw new IllegalArgumentException(label + " must have positive finite extents");
            }
            Vec3d minimum = min.toVector(label + ".min");
            Vec3d maximum = max.toVector(label + ".max");
            return new Aabb(minimum, maximum);
        }
    }
}
