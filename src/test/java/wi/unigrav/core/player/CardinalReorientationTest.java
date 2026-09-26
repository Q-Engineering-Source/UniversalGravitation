package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact rotation-sweep geometry for a cardinal reorientation.
 *
 * <p>These are the numbers the production adapter relies on. Extents are measured at the
 * origin because subtracting coordinates near a real world position loses up to one ulp of
 * that magnitude (~1.8e-15 at x=8.9); measuring there would test binary64 cancellation
 * instead of the geometry. The one property that must hold at real coordinates is checked
 * separately and bit-exactly by {@link #feetFaceStaysExactlyTangentToTheAcceptedCollider()}.
 */
class CardinalReorientationTest {
    private static final double HEIGHT = 1.8;
    private static final double WIDTH = 0.6;
    private static final double HALF_HEIGHT = HEIGHT / 2;
    private static final double RADIUS = WIDTH / 2;
    private static final Vec3d ORIGIN = new Vec3d(0, 0, 0);
    /** A realistic stand: feet on y = 65 with a non-integer centre, as in the world tests. */
    private static final Vec3d STANDING = new Vec3d(8.9, 65.9, 8.5);

    @Test
    void unchangedFrameSweepsExactlyTheAcceptedColliderBox() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            Aabb envelope = envelope(ORIGIN, frame, frame);
            for (int axis = 0; axis < 3; axis++) {
                double expected = component(frame.up(), axis) == 0 ? RADIUS : HALF_HEIGHT;
                assertEquals(expected, extent(envelope, axis), 0.0,
                        frame + " axis " + axis + " must reproduce the accepted half extent exactly");
            }
        }
    }

    @Test
    void orthogonalFrameChangeSweepsTheTwoAxisPlane() {
        // Y+ to X+ rotates in the world X/Y plane; Z keeps only the capsule radius.
        assertExtents(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.X_POS, HALF_HEIGHT, HALF_HEIGHT, RADIUS);
        assertExtents(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.X_NEG, HALF_HEIGHT, HALF_HEIGHT, RADIUS);
        assertExtents(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.Z_POS, RADIUS, HALF_HEIGHT, HALF_HEIGHT);
        assertExtents(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.Z_NEG, RADIUS, HALF_HEIGHT, HALF_HEIGHT);
        assertExtents(ORIGIN, CardinalFrame.X_POS, CardinalFrame.Z_POS, HALF_HEIGHT, RADIUS, HALF_HEIGHT);
        assertExtents(ORIGIN, CardinalFrame.X_POS, CardinalFrame.Y_POS, HALF_HEIGHT, HALF_HEIGHT, RADIUS);
    }

    @Test
    void invertedFrameSweepsThePlanePerpendicularToItsDeterministicAxis() {
        // A 180 degree flip has no unique axis; the fixed fallback axis keeps the envelope
        // reproducible, and the swept plane follows that axis rather than the flipped one.
        assertExtents(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.Y_NEG, RADIUS, HALF_HEIGHT, HALF_HEIGHT);
        assertExtents(ORIGIN, CardinalFrame.X_POS, CardinalFrame.X_NEG, HALF_HEIGHT, RADIUS, HALF_HEIGHT);
        assertExtents(ORIGIN, CardinalFrame.Z_POS, CardinalFrame.Z_NEG, RADIUS, HALF_HEIGHT, HALF_HEIGHT);
    }

    /**
     * The invariant that keeps ordinary floors usable: the sweep must be exactly tangent to
     * the surface the body already stands on. A one-ulp overshoot would make every floor block
     * intersect the envelope and reject every reorientation.
     */
    @Test
    void feetFaceStaysExactlyTangentToTheAcceptedCollider() {
        for (CardinalFrame from : CardinalFrame.values()) {
            for (CardinalFrame to : CardinalFrame.values()) {
                Aabb accepted = collider(STANDING, from);
                Aabb envelope = envelope(STANDING, from, to);
                assertEquals(feetPlane(accepted, from), feetPlane(envelope, from), 0.0,
                        from + " to " + to + " must not reach past the accepted feet face");
                assertEquals(CENTER_ALIGNED_TANGENT, tangencyError(accepted, envelope, from), 0.0,
                        from + " to " + to + " tangency must be exact, not merely close");
            }
        }
    }

    /**
     * Documents why the envelope must not be derived from the capsule's own exterior length:
     * {@code centerlineHalfLength + radius} is mathematically {@code height/2} but is one ulp
     * too large in binary64 at the real player profile, which would dig into the floor.
     */
    @Test
    void capsuleExteriorSumIsNotBitExactSoTheEnvelopeMustNotUseIt() {
        PlayerBodyShape.Dimensions dimensions = dimensions();
        double capsuleHalfLength = dimensions.centerlineHalfLength() + dimensions.radius();
        assertNotEquals(HALF_HEIGHT, capsuleHalfLength,
                "if this ever becomes bit-exact the derivation guard is unnecessary but still correct");
        assertEquals(HALF_HEIGHT, capsuleHalfLength, 1.0e-12, "the difference must stay a rounding artefact only");
        // The envelope keeps the exact half height, so the capsule box can exceed it by that ulp.
        Aabb envelope = envelope(ORIGIN, CardinalFrame.Y_POS, CardinalFrame.Y_POS);
        assertTrue(capsuleHalfLength > extent(envelope, 1),
                "the production envelope must stay strictly inside the capsule's rounded exterior");
    }

    @Test
    void envelopeContainsEveryColliderAlongTheWholeRotation() {
        for (CardinalFrame from : CardinalFrame.values()) {
            for (CardinalFrame to : CardinalFrame.values()) {
                Aabb envelope = envelope(ORIGIN, from, to);
                assertTrue(contains(envelope, collider(ORIGIN, from)), from + " envelope must contain the start collider");
                assertTrue(contains(envelope, collider(ORIGIN, to)), from + " to " + to + " must contain the target collider");
            }
        }
    }

    /**
     * The swept solid is the capsule, but its own rounded exterior length exceeds the exact
     * half height by one ulp (see {@link #capsuleExteriorSumIsNotBitExactSoTheEnvelopeMustNotUseIt()}).
     * Production sizes the envelope from the exact collider half height so the feet face stays
     * bit-exactly tangent to the surface the body stands on; the envelope therefore under-covers
     * the capsule's rounded box by at most that single ulp, which is sixteen orders of magnitude
     * below the 1/16-block granularity of every real collision box. The shortfall is bounded here
     * so it cannot silently grow into a real gap.
     */
    @Test
    void envelopeContainsTheStartAndTargetCapsuleWithinOneRoundingStep() {
        double slack = Math.ulp(HALF_HEIGHT);
        for (CardinalFrame from : CardinalFrame.values()) {
            for (CardinalFrame to : CardinalFrame.values()) {
                Aabb envelope = envelope(ORIGIN, from, to);
                assertTrue(containsWithin(envelope, capsule(ORIGIN, from), slack),
                        from + " envelope must reach its start capsule within one rounding step");
                assertTrue(containsWithin(envelope, capsule(ORIGIN, to), slack),
                        from + " to " + to + " must reach the target capsule within one rounding step");
            }
        }
    }

    @Test
    void envelopeIsCentredOnThePreservedBodyCentre() {
        for (CardinalFrame from : CardinalFrame.values()) {
            for (CardinalFrame to : CardinalFrame.values()) {
                assertEquals(STANDING, envelope(STANDING, from, to).center(),
                        "a reorientation must never displace the physical centre");
            }
        }
    }

    @Test
    void invalidSweepExtentsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CardinalReorientation.rotationSweepEnvelope(
                STANDING, CardinalFrame.Y_POS.up(), CardinalFrame.X_POS.up(), 0.0, WIDTH));
        assertThrows(IllegalArgumentException.class, () -> CardinalReorientation.rotationSweepEnvelope(
                STANDING, CardinalFrame.Y_POS.up(), CardinalFrame.X_POS.up(), HEIGHT, 0.0));
        // height < width is not a cardinal body profile.
        assertThrows(IllegalArgumentException.class, () -> CardinalReorientation.rotationSweepEnvelope(
                STANDING, CardinalFrame.Y_POS.up(), CardinalFrame.X_POS.up(), 1.0, 2.0));
    }

    /** Sign of the signed distance between the two feet faces along the source up axis. */
    private static double tangencyError(Aabb accepted, Aabb envelope, CardinalFrame from) {
        Vec3d up = from.up();
        double difference = feetPlane(envelope, from) - feetPlane(accepted, from);
        return Math.signum(up.x() + up.y() + up.z()) * difference;
    }

    private static final double CENTER_ALIGNED_TANGENT = 0.0;

    private static void assertExtents(Vec3d center, CardinalFrame from, CardinalFrame to,
                                      double x, double y, double z) {
        Aabb envelope = envelope(center, from, to);
        assertEquals(x, extent(envelope, 0), 0.0, from + " to " + to + " world X extent");
        assertEquals(y, extent(envelope, 1), 0.0, from + " to " + to + " world Y extent");
        assertEquals(z, extent(envelope, 2), 0.0, from + " to " + to + " world Z extent");
    }

    private static Aabb envelope(Vec3d center, CardinalFrame from, CardinalFrame to) {
        return CardinalReorientation.rotationSweepEnvelope(center, from.up(), to.up(), HEIGHT, WIDTH);
    }

    private static Aabb collider(Vec3d center, CardinalFrame frame) {
        return new PlayerBodyPlacement(center, frame.attitude(), shape(frame)).bounds();
    }

    private static Aabb capsule(Vec3d center, CardinalFrame frame) {
        return new PlayerBodyPlacement(center, frame.attitude(), shape(frame)).capsule().bounds();
    }

    private static PlayerBodyShape.Cardinal shape(CardinalFrame frame) {
        return new PlayerBodyShape.Cardinal(frame, dimensions());
    }

    private static PlayerBodyShape.Dimensions dimensions() {
        return new PlayerBodyShape.Dimensions(WIDTH, HEIGHT, 1.62);
    }

    /** Coordinate of the physical feet face along this frame's up axis. */
    private static double feetPlane(Aabb box, CardinalFrame frame) {
        return switch (frame) {
            case Y_POS -> box.min().y();
            case Y_NEG -> box.max().y();
            case X_POS -> box.min().x();
            case X_NEG -> box.max().x();
            case Z_POS -> box.min().z();
            case Z_NEG -> box.max().z();
        };
    }

    private static boolean contains(Aabb outer, Aabb inner) {
        return containsWithin(outer, inner, 0.0);
    }

    private static boolean containsWithin(Aabb outer, Aabb inner, double tolerance) {
        return outer.min().x() <= inner.min().x() + tolerance && outer.min().y() <= inner.min().y() + tolerance
                && outer.min().z() <= inner.min().z() + tolerance && outer.max().x() >= inner.max().x() - tolerance
                && outer.max().y() >= inner.max().y() - tolerance && outer.max().z() >= inner.max().z() - tolerance;
    }

    private static double extent(Aabb box, int axis) {
        return (component(box.max(), axis) - component(box.min(), axis)) / 2;
    }

    private static double component(Vec3d vector, int axis) {
        return switch (axis) {
            case 0 -> vector.x();
            case 1 -> vector.y();
            default -> vector.z();
        };
    }
}
