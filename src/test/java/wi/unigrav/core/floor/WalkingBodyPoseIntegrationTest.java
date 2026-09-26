package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Bootstrap;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.flight.FlightBodyPresentationPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.camera.SixDofBodyPresentation;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

class WalkingBodyPoseIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void acceptedGroundAndNearFootStepsReachUieRootWithoutChangingCamera(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9610), true)) {
            var player = world.serverPlayer(new UUID(0, 9611));
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                    new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
            for (boolean ground : new boolean[] { true, false }) {
                for (double roll : new double[] { 0, 90, 180 }) {
                    var view = FlightAttitude.fromMinecraftDegrees(10, 30, roll);
                    var right = view.right();
                    var forward = view.forward();
                    var basis = new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(forward.x, forward.y, forward.z),
                            new Vec3(right.x, right.y, right.z));
                    var presentation = new PlayerPhysicsClientPresentation();
                    var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 9612), 0, 1);
                    var contacts = Optional.of(new PlayerPhysicalSnapshot.Contacts(ground, false, ground, 0, !ground));
                    double x = 8, y = 65, z = 8;
                    for (int i = 0; i <= 24; i++) {
                        if (i > 0 && i <= 8) { x += right.x * .0125; y += right.y * .0125; z += right.z * .0125; }
                        if (i > 8) { x += forward.x * .0125; y += forward.y * .0125; z += forward.z * .0125; }
                        presentation.install(player, new PlayerPhysicalSnapshot(scope, i + 1,
                                new PlayerBodyPlacement(new Vec3d(x, y, z), CardinalFrame.Y_POS.attitude(), shape),
                                new Vec3d(0, 0, 0), contacts));
                        presentation.tick(player, basis);
                        var sample = SixDofBodyPresentation.sample(view, presentation.bodyYawOffset(player, 1));
                        assertSame(view, sample.tracking().getAttitude(), "visual body correction cannot rotate camera tracking");
                        var policy = FlightBodyPresentationPolicy.resolve(sample.body(), false);
                        assertTrue(policy.ownsRootQuaternion());
                        assertTrue(policy.neutralizesRelativeHead());
                        assertEquals(0, policy.relativeHeadYawDegrees());
                        assertEquals(0, policy.relativeHeadPitchDegrees());
                        var rootForward = policy.rootAttitude().forward();
                        double rightComponent = rootForward.x * right.x + rootForward.y * right.y + rootForward.z * right.z;
                        if (i == 8) assertTrue(rightComponent > .5, "visible UIE root must turn toward accepted strafe");
                        if (i == 24) assertEquals(0, rightComponent, .02, "visible UIE root must return on forward movement");
                        var rootUp = policy.rootAttitude().up();
                        assertEquals(view.up().x, rootUp.x, 1e-9);
                        assertEquals(view.up().y, rootUp.y, 1e-9);
                        assertEquals(view.up().z, rootUp.z, 1e-9);
                    }
                }
            }
        }
    }
}
