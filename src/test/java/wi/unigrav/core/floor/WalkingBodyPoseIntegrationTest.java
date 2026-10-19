package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Bootstrap;
import neofontrender.addons.api.flight.FlightAttitude;
import neofontrender.addons.api.flight.FlightVector;
import neofontrender.addons.flight.PlayerBodyRenderTransform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.client.camera.SixDofBodyPresentation;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

class WalkingBodyPoseIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void sampledProviderPoseUsesTheActualModelUpYTransform() {
        for (FlightAttitude uprightView : cardinalUpViews()) {
            for (int rollDegrees = 0; rollDegrees < 360; rollDegrees += 45) {
                FlightAttitude view = uprightView.rotateLocal(0, 0, 1,
                        Math.toRadians(rollDegrees));
                for (float gait : new float[] { 0, 35 }) {
                var sample = SixDofBodyPresentation.sample(view, gait);
                var transform = PlayerBodyRenderTransform.fromFlightPose(sample.body());
                FlightAttitude expectedBody = view.rotateLocal(0, 1, 0, -Math.toRadians(gait));
                assertVector(view.up(), transform.modelUpToWorld(), 1.0e-9,
                        "rendered model +Y must follow the sampled view up at gait=" + gait);
                assertVector(expectedBody.forward(), transform.modelForwardToWorld(), 1.0e-9,
                        "gait changes body heading in its local-up plane only");
                assertSame(view, sample.tracking().getAttitude(),
                        "body gait must not replace the camera tracking quaternion");

                FlightVector localLook = expectedBody.conjugate().rotate(
                        view.forward().x, view.forward().y, view.forward().z);
                var headAngles = transform.minecraftLocalAngles();
                double expectedYaw = -Math.toDegrees(Math.atan2(localLook.x, localLook.z));
                double expectedPitch = -Math.toDegrees(Math.atan2(
                        localLook.y, Math.hypot(localLook.x, localLook.z)));
                assertEquals(expectedYaw, headAngles.yawDegrees, 1.0e-8,
                        "head yaw comes from the independent view look in body-local axes");
                assertEquals(expectedPitch, headAngles.pitchDegrees, 1.0e-8,
                        "head pitch comes from the independent view look in body-local axes");
                double minecraftYaw = Math.toRadians(-headAngles.yawDegrees);
                double minecraftPitch = Math.toRadians(-headAngles.pitchDegrees);
                double pitchScale = Math.cos(minecraftPitch);
                FlightVector rebuiltLocalLook = new FlightVector(
                        Math.sin(minecraftYaw) * pitchScale,
                        Math.sin(minecraftPitch),
                        Math.cos(minecraftYaw) * pitchScale);
                FlightVector rebuiltWorldLook = expectedBody.rotate(
                        rebuiltLocalLook.x, rebuiltLocalLook.y, rebuiltLocalLook.z);
                assertVector(view.forward(), rebuiltWorldLook, 1.0e-8,
                        "body-relative head angles reconstruct the sampled world look");
                if (gait != 0) {
                    assertTrue(Math.abs(headAngles.yawDegrees) > 1.0,
                            "nonzero gait may produce nonzero relative head yaw");
                }

                FlightAttitude negatedView = new FlightAttitude(-view.x, -view.y, -view.z, -view.w);
                var negatedSample = SixDofBodyPresentation.sample(negatedView, gait);
                var negatedTransform = PlayerBodyRenderTransform.fromFlightPose(negatedSample.body());
                assertVector(transform.modelUpToWorld(), negatedTransform.modelUpToWorld(), 1.0e-9,
                        "q and -q preserve model up");
                assertVector(transform.modelForwardToWorld(), negatedTransform.modelForwardToWorld(), 1.0e-9,
                        "q and -q preserve rendered body heading");
                assertEquals(headAngles.yawDegrees, negatedTransform.minecraftLocalAngles().yawDegrees, 1.0e-8);
                assertEquals(headAngles.pitchDegrees, negatedTransform.minecraftLocalAngles().pitchDegrees, 1.0e-8);
                assertVector(view.forward(), negatedSample.tracking().getAttitude().forward(), 1.0e-9,
                        "q and -q preserve the sampled camera direction");
                }
            }
        }
    }

    @Test void inactiveCardinalPoseKeepsAcceptedUpInterpolatedHeadingAndWorldHeadLook() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            var dimensions = new PlayerBodyShape.Dimensions(.6, 1.8, 1.62);
            var placement = new PlayerBodyPlacement(new Vec3d(8, 65, 8), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, dimensions));
            var scope = new PlayerPhysicalSnapshot.Scope(new UUID(0, 9701), new UUID(0, 9702), 0, 1);
            var accepted = new PlayerPhysicalSnapshot(scope, 1, placement, new Vec3d(0, 0, 0),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 0)));
            FlightAttitude view = FlightAttitude.fromMinecraftDegrees(37, -23, 0);
            float previousYaw = 310;
            float bodyYaw = 30;
            float partialTicks = .5F;
            var sampled = SixDofBodyPresentation.sampleCardinal(accepted, safeNative(frame), view.forward(), null,
                    previousYaw, bodyYaw, partialTicks);
            var transform = PlayerBodyRenderTransform.fromFlightPose(sampled.body());

            assertVector(world(frame.up()), transform.modelUpToWorld(), 1.0e-9,
                    "inactive accepted Cardinal presentation keeps physical up " + frame);
            float interpolatedYaw = previousYaw + net.minecraft.util.math.MathHelper.wrapDegrees(bodyYaw - previousYaw)
                    * partialTicks;
            assertVector(projectedHeading(frame, interpolatedYaw), transform.modelForwardToWorld(), 1.0e-9,
                    "body heading interpolates then projects into the accepted tangent plane " + frame);
            assertVector(view.forward(), sampled.tracking().getAttitude().forward(), 1.0e-9,
                    "cardinal body sampling does not turn its world head look into a camera write");
            assertHeadLook(view.forward(), transform);

            FlightAttitude negatedView = new FlightAttitude(-view.x, -view.y, -view.z, -view.w);
            var negated = SixDofBodyPresentation.sampleCardinal(accepted, safeNative(frame), negatedView.forward(), null,
                    previousYaw, bodyYaw, partialTicks);
            var negatedTransform = PlayerBodyRenderTransform.fromFlightPose(negated.body());
            assertVector(transform.modelUpToWorld(), negatedTransform.modelUpToWorld(), 1.0e-9,
                    "q and -q keep the accepted cardinal up " + frame);
            assertVector(transform.modelForwardToWorld(), negatedTransform.modelForwardToWorld(), 1.0e-9,
                    "q and -q keep the interpolated body heading " + frame);
            assertHeadLook(negatedView.forward(), negatedTransform);

            double freeYawRadians = Math.toRadians(71);
            FlightVector freeViewForward = new FlightVector(-Math.sin(freeYawRadians), 0,
                    Math.cos(freeYawRadians));
            var viewRelative = SixDofBodyPresentation.sampleCardinal(accepted, safeNative(frame),
                    freeViewForward, freeViewForward, previousYaw, bodyYaw, partialTicks);
            assertVector(projectedHeading(frame, 71),
                    PlayerBodyRenderTransform.fromFlightPose(viewRelative.body()).modelForwardToWorld(), 1.0e-9,
                    "G/free view-relative body follows live camera heading inside accepted physical up " + frame);
        }

        // These native yaw headings point along the physical up axis and therefore have no
        // tangent projection; the accepted orientation supplies a stable heading instead.
        for (var caseData : new Object[][] {
                {CardinalFrame.X_POS, -90.0F}, {CardinalFrame.X_NEG, 90.0F},
                {CardinalFrame.Z_POS, 0.0F}, {CardinalFrame.Z_NEG, 180.0F}
        }) {
            CardinalFrame frame = (CardinalFrame) caseData[0];
            float yaw = (float) caseData[1];
            var placement = new PlayerBodyPlacement(new Vec3d(0, 0, 0), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62)));
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 9711),
                    new UUID(0, 9712), 0, 1), 1, placement, new Vec3d(0, 0, 0));
            var sampled = SixDofBodyPresentation.sampleCardinal(accepted, safeNative(frame),
                    world(frame.up()), null, yaw, yaw, 1);
            var transform = PlayerBodyRenderTransform.fromFlightPose(sampled.body());
            assertVector(world(placement.attitude().basisZ()), transform.modelForwardToWorld(), 1.0e-9,
                    "degenerate native body yaw falls back to accepted attitude " + frame);
            assertVector(world(frame.up()), transform.modelUpToWorld(), 1.0e-9,
                    "degenerate body yaw does not change accepted up " + frame);
            assertVector(world(frame.up()), sampled.tracking().getAttitude().forward(), 1.0e-9,
                    "vertical head look is preserved when camera-up basis is degenerate");
        }

        var baseline = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 9721),
                new UUID(0, 9722), 0, 1), 1,
                new PlayerBodyPlacement(new Vec3d(0, 0, 0), CardinalFrame.Y_POS.attitude(),
                        new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                                new PlayerBodyShape.Dimensions(.6, 1.8, 1.62))), new Vec3d(0, 0, 0));
        var vanilla = SixDofBodyPresentation.sampleCardinal(baseline, safeNative(CardinalFrame.Y_POS),
                new FlightVector(0, 0, 1), null, 0, 0, 1);
        var vanillaTransform = PlayerBodyRenderTransform.fromFlightPose(vanilla.body());
        assertVector(new FlightVector(0, 1, 0), vanillaTransform.modelUpToWorld(), 1.0e-9,
                "native Y+ yaw-zero baseline retains world up");
        assertVector(new FlightVector(0, 0, 1), vanillaTransform.modelForwardToWorld(), 1.0e-9,
                "native Y+ yaw-zero baseline retains +Z body heading");

        for (CardinalFrame frame : CardinalFrame.values()) {
            var placement = new PlayerBodyPlacement(new Vec3d(0, 0, 0), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62)));
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 9731),
                    new UUID(0, 9732), 0, 1), 1, placement, new Vec3d(0, 0, 0));
            Vec3d frozen = frame.attitude().basisX();
            var manual = new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL, frame, frozen);
            var sampled = SixDofBodyPresentation.sampleCardinal(accepted, manual,
                    FlightAttitude.fromMinecraftDegrees(113, -17, 0).forward(),
                    FlightAttitude.fromMinecraftDegrees(113, -17, 0).forward(), 10, 113, .5F);
            var transform = PlayerBodyRenderTransform.fromFlightPose(sampled.body());
            assertVector(projectedHeading(FlightAttitude.fromMinecraftDegrees(113, -17, 0).forward(), world(frame.up())),
                    transform.modelForwardToWorld(), 1.0e-9,
                    "explicit owner body forward follows presentation within accepted physical up " + frame);
            assertVector(world(frame.up()), transform.modelUpToWorld(), 1.0e-9,
                    "manual body presentation remains fenced to accepted physical up " + frame);
        }
    }

    private static PlayerBodyReference.Effective safeNative(CardinalFrame frame) {
        return new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE, frame,
                PlayerBodyReference.initialHeading(frame, 0));
    }

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
                        float bodyYaw = presentation.bodyYawOffset(player, 1);
                        var sample = SixDofBodyPresentation.sample(view, bodyYaw);
                        var transform = PlayerBodyRenderTransform.fromFlightPose(sample.body());
                        assertSame(view, sample.tracking().getAttitude(), "visual body correction cannot rotate camera tracking");
                        var bodyUp = view.rotateLocal(0, 1, 0, -Math.toRadians(bodyYaw));
                        assertVector(view.up(), transform.modelUpToWorld(), 1.0e-9,
                                "accepted movement gait cannot tilt the model away from camera up");
                        var rootForward = transform.modelForwardToWorld();
                        assertVector(bodyUp.forward(), rootForward, 1.0e-9,
                                "accepted gait reaches the actual UIE renderer transform");
                        double rightComponent = rootForward.x * right.x + rootForward.y * right.y + rootForward.z * right.z;
                        if (i == 8) assertTrue(rightComponent > .5, "visible UIE root must turn toward accepted strafe");
                        if (i == 24) assertEquals(0, rightComponent, .02, "visible UIE root must return on forward movement");
                        assertReconstructedHeadLook(bodyUp, view, transform);
                    }
                }
            }
        }
    }

    private static FlightAttitude[] cardinalUpViews() {
        double halfTurn = Math.PI / 2.0;
        return new FlightAttitude[] {
                FlightAttitude.IDENTITY,
                FlightAttitude.axisAngle(0, 0, 1, -halfTurn),
                FlightAttitude.axisAngle(0, 0, 1, halfTurn),
                FlightAttitude.axisAngle(1, 0, 0, Math.PI),
                FlightAttitude.axisAngle(1, 0, 0, halfTurn),
                FlightAttitude.axisAngle(1, 0, 0, -halfTurn)
        };
    }

    private static void assertReconstructedHeadLook(FlightAttitude bodyUp, FlightAttitude view,
                                                     PlayerBodyRenderTransform transform) {
        FlightVector localLook = bodyUp.conjugate().rotate(
                view.forward().x, view.forward().y, view.forward().z);
        var headAngles = transform.minecraftLocalAngles();
        double minecraftYaw = Math.toRadians(-headAngles.yawDegrees);
        double minecraftPitch = Math.toRadians(-headAngles.pitchDegrees);
        double pitchScale = Math.cos(minecraftPitch);
        FlightVector rebuiltWorldLook = bodyUp.rotate(
                Math.sin(minecraftYaw) * pitchScale,
                Math.sin(minecraftPitch), Math.cos(minecraftYaw) * pitchScale);
        assertVector(view.forward(), rebuiltWorldLook, 1.0e-8,
                "YSM head angles preserve the independent sampled camera look");
        assertTrue(Double.isFinite(localLook.x + localLook.y + localLook.z));
    }

    private static FlightVector projectedHeading(CardinalFrame frame, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        FlightVector up = world(frame.up());
        FlightVector heading = new FlightVector(-Math.sin(yaw), 0, Math.cos(yaw));
        double upComponent = heading.dot(up);
        FlightVector tangent = new FlightVector(heading.x - up.x * upComponent,
                heading.y - up.y * upComponent, heading.z - up.z * upComponent);
        if (tangent.lengthSquared() <= 1.0e-12) return world(frame.attitude().basisZ());
        return tangent.normalize();
    }

    private static FlightVector projectedHeading(FlightVector heading, FlightVector up) {
        double component = heading.dot(up);
        return new FlightVector(heading.x - up.x * component, heading.y - up.y * component,
                heading.z - up.z * component).normalize();
    }

    private static FlightVector world(dev.crlhitbox.api.geometry.Vec3d vector) {
        return new FlightVector(vector.x(), vector.y(), vector.z());
    }

    private static void assertHeadLook(FlightVector worldLook, PlayerBodyRenderTransform transform) {
        var root = transform.modelToWorld();
        FlightVector localLook = root.conjugate().rotate(worldLook.x, worldLook.y, worldLook.z);
        var angles = transform.minecraftLocalAngles();
        double expectedYaw = -Math.toDegrees(Math.atan2(localLook.x, localLook.z));
        double expectedPitch = -Math.toDegrees(Math.atan2(localLook.y, Math.hypot(localLook.x, localLook.z)));
        assertEquals(expectedYaw, angles.yawDegrees, 1.0e-7,
                "head yaw comes from the same interpolated world look in body-local axes");
        assertEquals(expectedPitch, angles.pitchDegrees, 1.0e-7,
                "head pitch comes from the same interpolated world look in body-local axes");
        double minecraftYaw = Math.toRadians(-angles.yawDegrees);
        double minecraftPitch = Math.toRadians(-angles.pitchDegrees);
        double pitchScale = Math.cos(minecraftPitch);
        FlightVector rebuiltWorld = root.rotate(Math.sin(minecraftYaw) * pitchScale,
                Math.sin(minecraftPitch), Math.cos(minecraftYaw) * pitchScale);
        assertVector(worldLook, rebuiltWorld, 1.0e-8,
                "head angles reconstruct the same world look after the accepted cardinal root");
    }

    private static void assertVector(FlightVector expected, FlightVector actual, double epsilon,
                                     String message) {
        assertEquals(expected.x, actual.x, epsilon, message + " (x)");
        assertEquals(expected.y, actual.y, epsilon, message + " (y)");
        assertEquals(expected.z, actual.z, epsilon, message + " (z)");
    }
}
