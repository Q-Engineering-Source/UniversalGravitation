package wi.unigrav.core.client.camera;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.core.client.FreeBodyRenderSample;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import static org.junit.jupiter.api.Assertions.*;

/** Regression source only: runtime/test execution remains user-owned for this repair. */
class FreeBodyRenderSampleTest {
    @Test void rotationUsesOneCenterAndBodyEyePivotWhileNativeTransportChanges() {
        var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var center = new Vec3d(8, 70, 12);
        var start = new PlayerBodyPlacement(center, Rotation3d.identity(), shape);
        var end = new PlayerBodyPlacement(center, new Rotation3d(0, 0, Math.sin(Math.PI / 4), Math.cos(Math.PI / 4)), shape);
        assertNotEquals(start.transport().y(), end.transport().y());
        var render = FreeBodyRenderSample.between(start, end, .5);
        assertEquals(center, render.center());
        var expectedEye = center.add(render.attitude().rotate(start.eye().subtract(center)));
        near(expectedEye, render.eye());
        near(render.attitude().rotate(start.capsule().centerline().start().subtract(center)).add(center), render.capsuleStart());
        assertEquals(start.eye(), FreeBodyRenderSample.between(start, end, 0).eye());
        assertEquals(end.eye(), FreeBodyRenderSample.between(start, end, 1).eye());
        var view = neofontrender.addons.api.flight.FlightAttitude.fromMinecraftDegrees(20, 35, 0);
        for (var nativeBase : new Vec3d[]{start.transport(), end.transport(), new Vec3d(3, 4, 5)}) {
            var body = SixDofBodyPresentation.sampleFree(render, view, nativeBase).body();
            near(render.feet(), nativeBase.add(new Vec3d(body.rootWorldOffset.x, body.rootWorldOffset.y, body.rootWorldOffset.z)));
            assertSame(view, body.headWorldAttitude);
            assertNotNull(body.geometry);
            assertEquals(-render.radius(), body.geometry.signedDistance(
                    new neofontrender.addons.api.flight.FlightVector(center.x(), center.y(), center.z())), 1e-10);
        }
        var moved = new PlayerBodyPlacement(center.add(new Vec3d(2, 4, 6)), end.attitude(), shape);
        near(center.add(new Vec3d(1, 2, 3)), FreeBodyRenderSample.between(start, moved, .5).center());
    }

    private static void near(Vec3d expected, Vec3d actual) {
        assertEquals(expected.x(), actual.x(), 1e-10);
        assertEquals(expected.y(), actual.y(), 1e-10);
        assertEquals(expected.z(), actual.z(), 1e-10);
    }
}
