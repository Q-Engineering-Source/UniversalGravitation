package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.init.Bootstrap;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.MathHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

class TangentViewSampleTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void sixFramesShareWadAndLocalHeadYawWithoutChangingNativeYaw(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22001), true)) {
            var player = world.serverPlayer(new UUID(0, 22002));
            for (var frame : CardinalFrame.values()) {
                var presentation = new PlayerPhysicsClientPresentation();
                var session = installed(player, frame, presentation);
                var forward = vector(frame.toWorld(new Vec3d(-1, 0, 0)));
                var sample = session.prepareTangentView(player, 1, forward, null);
                assertNotNull(sample);
                assertEquals(90, sample.localHeadYaw(), 1e-6);
                assertVector(forward, PlayerPhysicsInput.keyboard(sample, 1, 0, false, false, false, false).translation());
                Vec3 left = vector(frame.up()).cross(forward);
                assertVector(left, PlayerPhysicsInput.keyboard(sample, 0, 1, false, false, false, false).translation());
                assertVector(left.negate(), PlayerPhysicsInput.keyboard(sample, 0, -1, false, false, false, false).translation());
                float nativeYaw = player.renderYawOffset, nativePrevious = player.prevRenderYawOffset;
                assertTrue(presentation.tickCardinal(player, sample, () -> session.canCommitTangentView(player, sample)));
                assertTrue(session.commitTangentView(player, sample));
                assertEquals(nativeYaw, player.renderYawOffset);
                assertEquals(nativePrevious, player.prevRenderYawOffset);
                assertVector(forward, presentation.bodyForward(player, session.snapshot().orElseThrow(), 1));
                assertSame(sample, session.tangentView(player).orElseThrow());
            }
        }
    }

    @Test void preparationIsPureAndCorrectionRejectsPreparedWithoutGaitOrContinuity(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22003), true)) {
            var player = world.serverPlayer(new UUID(0, 22004));
            var presentation = new PlayerPhysicsClientPresentation();
            var session = installed(player, CardinalFrame.X_POS, presentation);
            var first = session.prepareTangentView(player, 1, new Vec3(0, 0, 1), null);
            var replacement = session.prepareTangentView(player, 1, new Vec3(0, -1, 0), null);
            assertEquals(first.viewSequence(), replacement.viewSequence());
            assertTrue(session.tangentView(player).isEmpty());
            float limb = player.limbSwing;
            assertFalse(presentation.tickCardinal(player, first, () -> { session.nativeCorrection(22); return false; }));
            assertFalse(session.commitTangentView(player, first));
            assertEquals(limb, player.limbSwing);
            assertTrue(session.tangentView(player).isEmpty());
            assertNull(presentation.bodyForward(player, session.snapshot().orElseThrow(), 1));
        }
    }

    @Test void yPositiveKeyboardRetainsExactVanillaFloatTrigAndDiagonalGain(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22005), true)) {
            var player = world.serverPlayer(new UUID(0, 22006));
            var session = installed(player, CardinalFrame.Y_POS, new PlayerPhysicsClientPresentation());
            for (float yaw : new float[] {0, 13.37F, 90, 179.99F, -170}) {
                var look = new Vec3(.2, -.5, 1);
                var sample = session.prepareTangentView(player, 1, look, yaw);
                for (double forward : new double[] {-1, 0, 1}) for (double strafe : new double[] {-1, 0, 1})
                    assertEquals(PlayerPhysicsInput.keyboard(yaw, forward, strafe, look, true, false, true, false),
                            PlayerPhysicsInput.keyboard(sample, forward, strafe, true, false, true, false));
            }
        }
    }

    @Test void poleTransportUsesPreviousTangentAndAntipodalAxisWithoutGlobalFlip() {
        var prior = new Vec3(0, 0, -1);
        var pole = ViewRelativeMovementBasis.continuous(CardinalFrame.Y_POS, new Vec3(0, 1, 0),
                new Vec3(0, 0, 1), CardinalFrame.Y_POS, prior);
        assertEquals(prior, pole.forward());
        assertEquals(prior, ViewRelativeMovementBasis.transport(CardinalFrame.Y_POS, CardinalFrame.Y_NEG, prior));
        var exit = ViewRelativeMovementBasis.continuous(CardinalFrame.Y_POS, new Vec3(.2, 1, .1),
                new Vec3(0, 0, 1), CardinalFrame.Y_POS, prior);
        assertTrue(exit.forward().dot(new Vec3(.2, 0, .1)) > 0, "leaving a pole does not globally flip real view input");
    }

    @Test void nativeYPositivePoleYawUsesOneCanonicalInputAndBodyDirection(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22013), true)) {
            var player = world.serverPlayer(new UUID(0, 22014));
            var presentation = new PlayerPhysicsClientPresentation();
            var session = installed(player, CardinalFrame.Y_POS, presentation);
            float bodyYaw = 0;
            float[] yaws = {13, 113, 193, -47};
            float[] pitches = {20, 90, 90, 10};
            for (int index = 0; index < yaws.length; index++) {
                player.rotationYaw = player.prevRotationYaw = yaws[index];
                player.rotationPitch = player.prevRotationPitch = pitches[index];
                var nativeLook = player.getLook(1);
                // Cover both vanilla float's near-pole result and its exact finite pole limit.
                var look = index == 2 ? new Vec3(0, -1, 0) : new Vec3(nativeLook.x, nativeLook.y, nativeLook.z);
                var sample = session.prepareTangentView(player, index + 1, look, player.rotationYaw);
                var w = PlayerPhysicsInput.keyboard(sample, 1, 0, false, false, false, false);
                var a = PlayerPhysicsInput.keyboard(sample, 0, 1, false, false, false, false);
                var d = PlayerPhysicsInput.keyboard(sample, 0, -1, false, false, false, false);
                assertEquals(PlayerPhysicsInput.keyboard(player.rotationYaw, 1, 0, look, false, false, false, false), w);
                assertEquals(PlayerPhysicsInput.keyboard(player.rotationYaw, 0, 1, look, false, false, false, false), a);
                assertEquals(PlayerPhysicsInput.keyboard(player.rotationYaw, 0, -1, look, false, false, false, false), d);
                assertVector(vector(w.translation()).normalize(), sample.basis().forward());
                assertVector(vector(a.translation()).normalize(), sample.basis().right());
                assertVector(vector(d.translation()).normalize(), sample.basis().right().negate());
                float canonicalHead = (float) Math.toDegrees(Math.atan2(-sample.basis().forward().x(), sample.basis().forward().z()));
                assertEquals(canonicalHead, sample.localHeadYaw());
                assertEquals(look, sample.headForward(), "canonical tangent must not replace the actual head look");
                if (index == 0) bodyYaw = canonicalHead;
                // Swing gives the real .3 head-follow target; body clamping remains independent of camera.
                player.swingProgress = .5F;
                float next = bodyYaw + MathHelper.wrapDegrees(canonicalHead - bodyYaw) * .3F;
                float relative = MathHelper.clamp(MathHelper.wrapDegrees(canonicalHead - next), -75, 75);
                next = canonicalHead - relative;
                if (relative * relative > 2500) next += relative * .2F;
                assertTrue(presentation.tickCardinal(player, sample, () -> session.canCommitTangentView(player, sample)));
                assertTrue(session.commitTangentView(player, sample));
                var expectedBody = heading(CardinalFrame.Y_POS, next);
                var actualBody = presentation.bodyForward(player, session.snapshot().orElseThrow(), 1);
                assertEquals(expectedBody.x(), actualBody.x(), 1e-6);
                assertEquals(expectedBody.y(), actualBody.y(), 1e-6);
                assertEquals(expectedBody.z(), actualBody.z(), 1e-6);
                bodyYaw = next;
            }
        }
    }

    @Test void sixFrameGaitDropsOnlyLocalYAndConsumesPredictionOnce(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22007), true)) {
            var player = world.serverPlayer(new UUID(0, 22008));
            for (var frame : CardinalFrame.values()) {
                var presentation = new PlayerPhysicsClientPresentation();
                var session = installed(player, frame, presentation);
                var initial = session.snapshot().orElseThrow();
                var before = new CardinalPredictionStep.State(initial.placement(), new Vec3d(0, 0, 0),
                        initial.contacts().orElseThrow(), 0);
                var localUp = shifted(before, frame.toWorld(new Vec3d(0, 2, 0)));
                float distance = player.distanceWalkedModified;
                presentation.predictedMotion(player, before, localUp);
                var view = session.prepareTangentView(player, 1, vector(frame.toWorld(new Vec3d(0, 0, 1))), null);
                assertTrue(presentation.tickCardinal(player, view, () -> true));
                assertTrue(session.commitTangentView(player, view));
                assertEquals(distance, player.distanceWalkedModified, "local Y never contributes gait: " + frame);
                var moved = shifted(localUp, frame.toWorld(new Vec3d(.3, 0, .4)));
                presentation.predictedMotion(player, localUp, moved);
                var second = session.prepareTangentView(player, 2, view.headForward(), null);
                assertTrue(presentation.tickCardinal(player, second, () -> true));
                assertTrue(session.commitTangentView(player, second));
                assertEquals(distance + .3F, player.distanceWalkedModified, 1e-6);
                float once = player.distanceWalkedModified;
                assertFalse(presentation.tickCardinal(player, second, () -> true));
                assertEquals(once, player.distanceWalkedModified, "same slot cannot emit presentation twice");
            }
        }
    }

    @Test void naturalHeadingWrapSwingAndStalePreparedUsePrivateLocalYaw(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22009), true)) {
            var player = world.serverPlayer(new UUID(0, 22010));
            var presentation = new PlayerPhysicsClientPresentation();
            var session = installed(player, CardinalFrame.Z_NEG, presentation);
            var snapshot = session.snapshot().orElseThrow();
            var first = session.prepareTangentView(player, 1, heading(CardinalFrame.Z_NEG, 179), null);
            assertTrue(presentation.tickCardinal(player, first, () -> true));
            assertTrue(session.commitTangentView(player, first));
            var stale = presentation.prepare(player, snapshot);
            player.swingProgress = .5F;
            var next = session.prepareTangentView(player, 2, heading(CardinalFrame.Z_NEG, -179), null);
            assertTrue(presentation.tickCardinal(player, next, () -> true));
            assertTrue(session.commitTangentView(player, next));
            assertFalse(presentation.commitPrepared(stale));
            Vec3d local = CardinalFrame.Z_NEG.toLocal(asVector(presentation.bodyForward(player, snapshot, 1)));
            float yaw = (float) Math.toDegrees(Math.atan2(-local.x(), local.z()));
            assertEquals(179.6, yaw, 1e-4, "swing follows head by .3 across the wrap exactly once");
            assertEquals(179, Math.toDegrees(Math.atan2(-CardinalFrame.Z_NEG.toLocal(
                    asVector(presentation.bodyForward(player, snapshot, 0))).x(), CardinalFrame.Z_NEG.toLocal(
                    asVector(presentation.bodyForward(player, snapshot, 0))).z())), 1e-4);
        }
    }

    @Test void realStateBurstSumsGaitOnceAndReverseMovementKeepsBodyFacingHead(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 22011), true)) {
            var player = world.serverPlayer(new UUID(0, 22012));
            var presentation = new PlayerPhysicsClientPresentation();
            var frame = CardinalFrame.X_NEG;
            var session = installed(player, frame, presentation);
            var initial = session.snapshot().orElseThrow();
            var head = heading(frame, 0);
            var first = session.prepareTangentView(player, 1, head, null);
            assertTrue(presentation.tickCardinal(player, first, () -> true));
            assertTrue(session.commitTangentView(player, first));
            float distance = player.distanceWalkedModified;
            for (int revision = 2; revision <= 3; revision++) {
                var offset = frame.toWorld(new Vec3d(0, 0, -(revision - 1)));
                var next = new PlayerPhysicalSnapshot(initial.scope(), revision, new PlayerBodyPlacement(
                        initial.placement().center().add(offset), frame.attitude(), initial.placement().shape()),
                        new Vec3d(0, 0, 0), initial.contacts());
                assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,
                        initial.scope(), revision, 1, Optional.empty(), Optional.of(next), Optional.empty(),
                        OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.of(PlayerBodyReference.State.initial(
                                frame, frame.toWorld(new Vec3d(0, 0, 1)))))));
            }
            var nextView = session.prepareTangentView(player, 2, head, null);
            assertTrue(presentation.tickCardinal(player, nextView, () -> session.canCommitTangentView(player, nextView)));
            assertTrue(session.commitTangentView(player, nextView));
            assertEquals(distance + 1.2F, player.distanceWalkedModified, 1e-6, "both installed STATE deltas accumulate once");
            assertVector(head, presentation.bodyForward(player, session.snapshot().orElseThrow(), 1));
            var idle = session.prepareTangentView(player, 3, head, null);
            assertTrue(presentation.tickCardinal(player, idle, () -> true));
            assertEquals(distance + 1.2F, player.distanceWalkedModified, 1e-6, "no replay of consumed burst gait");
        }
    }

    private static CardinalPredictionStep.State shifted(CardinalPredictionStep.State state, Vec3d delta) {
        var placement = state.placement();
        return new CardinalPredictionStep.State(new PlayerBodyPlacement(placement.center().add(delta),
                placement.attitude(), placement.shape()), state.motionBlocksPerTick(), state.contacts(), state.jumpTicks());
    }
    private static Vec3 heading(CardinalFrame frame, double yaw) {
        double radians = Math.toRadians(yaw);
        return vector(frame.toWorld(new Vec3d(-Math.sin(radians), 0, Math.cos(radians))));
    }
    private static Vec3d asVector(Vec3 value) { return new Vec3d(value.x(), value.y(), value.z()); }

    private static PlayerPhysicsClientSession installed(net.minecraft.entity.player.EntityPlayer player,
            CardinalFrame frame, PlayerPhysicsClientPresentation presentation) {
        var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), UUID.randomUUID(), player.dimension, 1);
        var reference = PlayerBodyReference.State.initial(frame, frame.toWorld(new Vec3d(0, 0, 1)));
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        var snapshot = new PlayerPhysicalSnapshot(scope, 1,
                new PlayerBodyPlacement(new Vec3d(8, 70, 8), frame.attitude(), shape), new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, false, 0)));
        var session = new PlayerPhysicsClientSession(state -> presentation.install(player, state), () -> {}, presentation::reset);
        assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), packet -> {});
        assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, scope, 1, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), OptionalInt.empty(), Optional.empty(),
                Optional.empty(), Optional.of(reference))));
        return session;
    }

    private static Vec3 vector(Vec3d value) { return new Vec3(value.x(), value.y(), value.z()); }
    private static void assertVector(Vec3 expected, Vec3d actual) { assertVector(expected, vector(actual)); }
    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), 1e-12);
        assertEquals(expected.y(), actual.y(), 1e-12);
        assertEquals(expected.z(), actual.z(), 1e-12);
    }
}
