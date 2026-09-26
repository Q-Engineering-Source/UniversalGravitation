package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

import static org.junit.jupiter.api.Assertions.*;

class CardinalFrameTest {
    @Test
    void everyFrameIsAnExactRightHandedPermutationAndInverse() {
        Vec3d x = new Vec3d(1, 0, 0);
        Vec3d y = new Vec3d(0, 1, 0);
        Vec3d z = new Vec3d(0, 0, 1);
        Vec3d asymmetric = new Vec3d(2.25, -3.5, 7.125);
        for (CardinalFrame frame : CardinalFrame.values()) {
            assertEquals(asymmetric, frame.toLocal(frame.toWorld(asymmetric)));
            assertEquals(asymmetric, frame.toWorld(frame.toLocal(asymmetric)));
            assertEquals(frame.toWorld(z), frame.toWorld(x).cross(frame.up()));
            assertEquals(frame.up(), frame.toWorld(y));
            assertEquals(1, frame.up().lengthSquared());
            assertEquals(0, frame.attitude().basisY().subtract(frame.up()).lengthSquared(), 1e-28);
        }
    }

    @Test
    void floorOutwardNormalsMapToTheExpectedWorldUp() {
        Face[] faces = {Face.UP, Face.DOWN, Face.EAST, Face.WEST, Face.SOUTH, Face.NORTH};
        Vec3d[] up = {new Vec3d(0, 1, 0), new Vec3d(0, -1, 0), new Vec3d(1, 0, 0),
                new Vec3d(-1, 0, 0), new Vec3d(0, 0, 1), new Vec3d(0, 0, -1)};
        for (int i = 0; i < faces.length; i++) {
            assertEquals(up[i], CardinalFrame.fromFloorFace(faces[i]).up());
        }
    }

    @Test
    void boxPermutationContainsEveryTransformedCornerAndPreservesVolume() {
        Aabb box = new Aabb(new Vec3d(-2, 3, -7), new Vec3d(1, 9, -5));
        for (CardinalFrame frame : CardinalFrame.values()) {
            Aabb transformed = frame.toWorld(box);
            for (double x : new double[]{-2, 1}) {
                for (double y : new double[]{3, 9}) {
                    for (double z : new double[]{-7, -5}) {
                        assertTrue(transformed.contains(frame.toWorld(new Vec3d(x, y, z))));
                    }
                }
            }
            Vec3d span = transformed.max().subtract(transformed.min());
            assertEquals(36, span.x() * span.y() * span.z());
        }
    }

    @Test
    void nullDoesNotSilentlyPassThroughTheIdentityFrame() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            assertThrows(NullPointerException.class, () -> frame.toWorld((Vec3d) null));
            assertThrows(NullPointerException.class, () -> frame.toLocal(null));
        }
    }
}
