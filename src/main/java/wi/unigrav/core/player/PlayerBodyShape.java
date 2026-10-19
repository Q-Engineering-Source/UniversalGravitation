package wi.unigrav.core.player;

import java.util.Objects;
import java.util.Optional;

/**
 * Shape parameters, independent of placement, view, support and propulsion.
 * Dimensions and eye height are in metres; callers supply the actual player profile.
 */
public sealed interface PlayerBodyShape {
    Dimensions dimensions();

    /** Collision recipe dimensions; native actor dimensions remain independently exact. */
    default Dimensions geometryDimensions() { return dimensions(); }

    /**
     * Native positive dimensions; capsule representability is a separate admission.
     * Eye height is an independent native presentation anchor, not a collider extent:
     * vanilla sneaking while gliding can put it above the 0.6 m body.
     */
    record Dimensions(double width, double height, double eyeHeight) {
        public Dimensions {
            if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(eyeHeight)
                    || width / 2 <= 0 || height <= 0 || eyeHeight < 0) {
                throw new IllegalArgumentException("finite dimensions require width > 0, height > 0 and eyeHeight >= 0"
                        + " (width=" + width + ", height=" + height + ", eyeHeight=" + eyeHeight + ")");
            }
        }

        public double radius() {
            return width / 2;
        }

        public boolean supportsCapsule() {
            return height >= width;
        }

        public double centerlineHalfLength() {
            if (!supportsCapsule()) {
                throw new IllegalArgumentException("capsule height must be at least its width");
            }
            return (height - width) / 2;
        }

        public double eyeOffsetFromCenter() {
            return eyeHeight - height / 2;
        }
    }

    /** Exact six-axis AABB, not an oriented box following tangent body heading. */
    record Cardinal(CardinalFrame frame, Dimensions dimensions) implements PlayerBodyShape {
        public Cardinal {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(dimensions, "dimensions");
        }
    }

    /** Full physical body dimensions, independent of a native short-pose W/H/eye record. */
    record CollisionProfile(double diameter, double length) {
        public CollisionProfile {
            if (!Double.isFinite(diameter) || diameter / 2 <= 0 || !Double.isFinite(length) || length < diameter)
                throw new IllegalArgumentException("physical capsule requires finite length >= diameter > 0");
        }
        public static CollisionProfile fromNative(NativeBodyProfileProducer.Profile observed) {
            Objects.requireNonNull(observed, "observed");
            return new CollisionProfile(observed.diameter(), observed.length());
        }
    }

    /** A capsule whose long axis follows the independently accepted body attitude. */
    record FreeCapsule(Dimensions dimensions, CollisionProfile collisionProfile, Optional<FreeEyeAnchor> eyeAnchor) implements PlayerBodyShape {
        public FreeCapsule(Dimensions dimensions) {
            this(dimensions, new CollisionProfile(dimensions.width(), dimensions.height()), Optional.empty());
        }
        public FreeCapsule(Dimensions dimensions, CollisionProfile collisionProfile) {
            this(dimensions, collisionProfile, Optional.empty());
        }
        public FreeCapsule {
            Objects.requireNonNull(dimensions, "dimensions");
            Objects.requireNonNull(collisionProfile, "collisionProfile");
            Objects.requireNonNull(eyeAnchor, "eyeAnchor");
        }
        @Override public Dimensions geometryDimensions() {
            // Preserve the exact original object and arithmetic path for every ordinary profile.
            return Double.doubleToRawLongBits(dimensions.width()) == Double.doubleToRawLongBits(collisionProfile.diameter())
                    && Double.doubleToRawLongBits(dimensions.height()) == Double.doubleToRawLongBits(collisionProfile.length())
                    ? dimensions : new Dimensions(collisionProfile.diameter(), collisionProfile.length(), dimensions.eyeHeight());
        }
    }
}
