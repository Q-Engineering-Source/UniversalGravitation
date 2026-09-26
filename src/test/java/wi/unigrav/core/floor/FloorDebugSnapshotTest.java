package wi.unigrav.core.floor;

import java.util.List;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class FloorDebugSnapshotTest {
    @Test void isolatedFloorProducesCenteredArrowsAndFadedOuterSamples() {
        var source = new FloorFieldIndex.Source(1, 64, 1, FloorFieldEmitter.Face.UP,
                FloorFieldIndex.Kind.GRAVITY, new FloorFieldProfile(32, 8, 16));
        var snapshot = FloorDebugSnapshot.sample(List.of(source), new Vec3(1.5, 65, 1.5));
        assertEquals(4, snapshot.arrows().size(), "a single 1x1 column must not miss the coarse world grid");
        var near = snapshot.arrows().getFirst();
        assertEquals(new Vec3(1.5, 65.5, 1.5), near.position());
        assertEquals(new Vec3(0, -32, 0), near.gravity());
        assertEquals(Vec3.ZERO, near.magnetic());
        var far = snapshot.arrows().getLast();
        assertTrue(far.gravity().norm() > 0 && far.gravity().norm() < 32);
    }

    @Test void independentMagneticAndGravityVectorsDoNotMaskOneAnother() {
        var profile = new FloorFieldProfile(32, 8, 16);
        var snapshot = FloorDebugSnapshot.sample(List.of(
                new FloorFieldIndex.Source(0, 64, 0, FloorFieldEmitter.Face.UP, FloorFieldIndex.Kind.GRAVITY, profile),
                new FloorFieldIndex.Source(0, 66, 0, FloorFieldEmitter.Face.DOWN, FloorFieldIndex.Kind.MAGNETIC, profile)),
                new Vec3(.5, 65.5, .5));
        var middle = snapshot.arrows().stream().filter(a -> a.position().equals(new Vec3(.5, 65.5, .5))).findFirst().orElseThrow();
        assertEquals(new Vec3(0, -32, 0), middle.gravity());
        assertEquals(new Vec3(0, 32, 0), middle.magnetic());
        assertEquals(Vec3.ZERO, middle.gravity().add(middle.magnetic()));
    }
}
