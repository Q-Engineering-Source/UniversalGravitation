package wi.unigrav.core.player;

import java.util.Objects;

/**
 * Shape parameters, independent of placement, view, support and propulsion.
 * Dimensions and eye height are in metres; callers supply the actual player profile.
 */
public sealed interface PlayerBodyShape {
    Dimensions dimensions();

    /**
     * Square tangent cross-section and an inscribed capsule of the same exterior height.
     * Eye height is an independent native presentation anchor, not a collider extent:
     * vanilla sneaking while gliding can put it above the 0.6 m body.
     */
    record Dimensions(double width, double height, double eyeHeight) {
        public Dimensions {
            if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(eyeHeight)
                    || width / 2 <= 0 || height < width || eyeHeight < 0) {
                throw new IllegalArgumentException("finite dimensions require 0 < width <= height and eyeHeight >= 0"
                        + " (width=" + width + ", height=" + height + ", eyeHeight=" + eyeHeight + ")");
            }
        }

        public double radius() {
            return width / 2;
        }

        public double centerlineHalfLength() {
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

    /** A capsule whose long axis follows the independently accepted body attitude. */
    record FreeCapsule(Dimensions dimensions) implements PlayerBodyShape {
        public FreeCapsule {
            Objects.requireNonNull(dimensions, "dimensions");
        }
    }
}
