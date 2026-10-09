package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.UUID;
import java.util.Optional;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Bootstrap;
import net.minecraft.entity.player.EntityPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.client.PlayerPhysicsClientMirror;
import wi.unigrav.core.client.PlayerPhysicsClientSession;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import net.minecraft.network.play.client.CPacketPlayer;
import static org.junit.jupiter.api.Assertions.*;

/** Real Entity storage projection, not a client runtime or GPU fixture. */
class PlayerPhysicsClientMirrorTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void clientPoseCallbackRetainsOwnedSideColliderButPreservesCorrections(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10031), true)) {
            var player = world.serverPlayer(new UUID(0, 10032));
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                    new PlayerBodyShape.Dimensions(.3F, .9F, .81F));
            var placement = new PlayerBodyPlacement(new Vec3d(8, 70, 9), CardinalFrame.X_POS.attitude(), shape);
            var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 10033), player.dimension, 1);
            var snapshot = new PlayerPhysicalSnapshot(scope, 2, placement, new Vec3d(0, 0, 0),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
            var session = new PlayerPhysicsClientSession(state -> PlayerPhysicsClientMirror.install(player, state),
                    () -> {}, () -> {});
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, 0, 1,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), packet -> {});
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, scope, 1, 1,
                    Optional.empty(), Optional.of(snapshot), Optional.empty())));
            var bounds = player.getEntityBoundingBox();
            session.runNativePose(player, () -> {
                player.width = .6F;
                player.height = 1.8F;
                player.eyeHeight = 1.62F;
                player.setPosition(player.posX, player.posY, player.posZ);
            });
            assertEquals(bounds, player.getEntityBoundingBox(), "local upright pose must not overwrite an owned side collider");
            assertEquals(.3F, player.width);
            assertEquals(.9F, player.height);
            assertEquals(.81F, session.physicalEyeHeight(player, .4F), "the actual mod getter can differ from the physical eye");
            var failure = new IllegalStateException("resize callback failed after writing");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> session.runNativePose(player, () -> {
                player.width = .6F;
                player.setPosition(player.posX, player.posY, player.posZ);
                throw failure;
            })));
            assertEquals(bounds, player.getEntityBoundingBox(), "failed pose cannot replace accepted geometry");
            session.runNativePose(player, () -> {
                session.nativeCorrection(42);
                player.setPosition(100, 65, 100);
            });
            assertEquals(100, player.posX, "a real correction cannot be rolled back by pose projection");
            session.reset();
            assertEquals(.4F, session.physicalEyeHeight(player, .4F), "retired lifetime must not retain the physical eye");
        }
    }

    @Test void pendingNativeCorrectionHidesTheRetainedSnapshotFromPresentation(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10101), true)) {
            var player = world.serverPlayer(new UUID(0, 10102));
            var snapshot = state(player, 1.8F);
            var session = new PlayerPhysicsClientSession(
                    candidate -> PlayerPhysicsClientMirror.install(player, candidate), () -> {}, () -> {});
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,
                    snapshot.scope(), 0, 1, Optional.empty(), Optional.empty(), Optional.empty())));
            session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                    false, Optional.empty(), packet -> {});
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED,
                    snapshot.scope(), 1, 1, Optional.empty(), Optional.of(snapshot), Optional.empty())));
            assertTrue(session.presentationSnapshot().isPresent());

            session.nativeCorrection(42);

            assertTrue(session.owns(), "native correction retains the existing custody contract");
            assertFalse(session.presentationSnapshot().isPresent(),
                    "a pending vanilla correction must not be presented as the accepted mirror");
        }
    }

    @Test void staleLiveTransportIsNotEligibleForAcceptedPresentation(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10111), true)) {
            var player = world.serverPlayer(new UUID(0, 10112));
            var snapshot = state(player, 1.8F);
            assertTrue(PlayerPhysicsClientMirror.install(player, snapshot));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, snapshot));

            player.posX += .25;

            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, snapshot),
                    "read-only pose eligibility must reject transport drift without reinstalling the mirror");
            assertEquals(snapshot.placement().transport().x() + .25, player.posX);

            player.posX = snapshot.placement().transport().x();
            player.width += .1F;
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, snapshot),
                    "the projected float width must still equal the accepted shape");
            player.width = (float) snapshot.placement().shape().dimensions().width();
            player.height -= .1F;
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, snapshot),
                    "the projected float height must still equal the accepted shape");
            player.height = (float) snapshot.placement().shape().dimensions().height();
            var acceptedBox = player.getEntityBoundingBox();
            player.setEntityBoundingBox(new net.minecraft.util.math.AxisAlignedBB(acceptedBox.minX + .1,
                    acceptedBox.minY, acceptedBox.minZ, acceptedBox.maxX + .1, acceptedBox.maxY, acceptedBox.maxZ));
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, snapshot),
                    "current AABB must still equal the accepted collision geometry");
        }
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void authoritativeResizeInstallsWidthHeightAndActualBoxTogether(CardinalFrame frame, @TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10021), true)) {
            var player = world.serverPlayer(new UUID(0, 10022));
            player.setPosition(1, 65, 2);
            var dimensions = new PlayerBodyShape.Dimensions(.3F, .9F, .81F);
            var placement = new PlayerBodyPlacement(new Vec3d(8, 70, 9), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, dimensions));
            var snapshot = new PlayerPhysicalSnapshot(
                    new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 10023), player.dimension, 1),
                    2, placement, new Vec3d(2, 0, 0),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
            assertTrue(PlayerPhysicsClientMirror.install(player, snapshot));
            assertEquals(.3F, player.width);
            assertEquals(.9F, player.height);
            assertEquals(.81F, player.getEyeHeight(), "authoritative modded eye must follow the installed dimensions");
            var box = player.getEntityBoundingBox();
            assertEquals(placement.bounds().min().x(), box.minX);
            assertEquals(placement.bounds().max().y(), box.maxY);
            assertEquals(placement.bounds().max().z(), box.maxZ);
            assertEquals(placement.transport().x(), player.posX);
            assertEquals(.1, player.motionX);
        }
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void installsEveryAcceptedCardinalColliderWithoutRebuildingWorldUpright(CardinalFrame frame,
                                                                         @TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 10001), true)) {
            var player = world.serverPlayer(new UUID(0, 10002));
            player.setPosition(1, 65, 2);
            player.rotationYaw = 47;
            player.rotationPitch = -31;
            var dimensions = new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight());
            var placement = new PlayerBodyPlacement(new Vec3d(8, 70, 9), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, dimensions));
            var snapshot = new PlayerPhysicalSnapshot(
                    new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 10003), 0, 1),
                    1, placement, new Vec3d(2, 3, 4),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, true, 0)));
            assertTrue(PlayerPhysicsClientMirror.install(player, snapshot), "accepted " + frame + " must be applicable");
            var box = player.getEntityBoundingBox();
            boolean xUp = frame == CardinalFrame.X_POS || frame == CardinalFrame.X_NEG;
            boolean yUp = frame == CardinalFrame.Y_POS || frame == CardinalFrame.Y_NEG;
            boolean zUp = frame == CardinalFrame.Z_POS || frame == CardinalFrame.Z_NEG;
            assertEquals(xUp ? (double) 1.8F : (double) .6F, box.maxX - box.minX, 1e-12);
            assertEquals(yUp ? (double) 1.8F : (double) .6F, box.maxY - box.minY, 1e-12);
            assertEquals(zUp ? (double) 1.8F : (double) .6F, box.maxZ - box.minZ, 1e-12);
            assertEquals(box.minY, player.posY, 0, "native transport is not physical feet");
            assertEquals(.1, player.motionX, 1e-12);
            assertEquals(.15, player.motionY, 1e-12);
            assertEquals(.2, player.motionZ, 1e-12);
            assertEquals(47, player.rotationYaw, "UIE owns view orientation");
            assertEquals(-31, player.rotationPitch);
        }
    }

    @Test void installsAcceptedBoundsAndConvertsVelocityWithoutChangingCamera(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 771), true)) {
            var player = world.serverPlayer(new UUID(0, 772));
            player.setPosition(1, 65, 2);
            player.rotationYaw = 47;
            player.rotationPitch = -31;
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                    new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
            var placement = new PlayerBodyPlacement(new Vec3d(8, 70, 9), CardinalFrame.Y_POS.attitude(), shape);
            var scope = new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 773), player.dimension, 1);
            var snapshot = new PlayerPhysicalSnapshot(scope, 4, placement, new Vec3d(40, -20, 10),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(true, true, false, 3.5F)));
            assertFalse(PlayerPhysicsClientMirror.install(player, new PlayerPhysicalSnapshot(scope, 4, placement,
                    new Vec3d(40, -20, 10))), "geometry-only state cannot fabricate contact presentation");
            assertEquals(PlayerPhysicsClientMirror.Rejection.NO_CONTACTS,
                    PlayerPhysicsClientMirror.rejection(player, new PlayerPhysicalSnapshot(scope, 4, placement,
                            new Vec3d(40, -20, 10))));
            assertEquals(1, player.posX);
            assertTrue(PlayerPhysicsClientMirror.install(player, snapshot));
            assertEquals(8, player.posX);
            assertEquals(placement.transport().y(), player.posY);
            assertEquals(9, player.posZ);
            assertEquals(placement.bounds().min().y(), player.getEntityBoundingBox().minY);
            assertEquals(placement.bounds().max().y(), player.getEntityBoundingBox().maxY);
            assertEquals(2, player.motionX);
            assertEquals(-1, player.motionY);
            assertEquals(.5, player.motionZ);
            assertEquals(47, player.rotationYaw);
            assertEquals(-31, player.rotationPitch);
            assertEquals(1, player.prevPosX);
            assertTrue(player.onGround);
            assertTrue(player.collidedHorizontally);
            assertFalse(player.collidedVertically);
            assertTrue(player.collided);
            assertEquals(3.5F, player.fallDistance);
            player.setSneaking(true);
            BootWorldFixture.updateNativePose(player);
            assertEquals(PlayerPhysicsClientMirror.Rejection.NONE, PlayerPhysicsClientMirror.rejection(player, snapshot));
            assertTrue(PlayerPhysicsClientMirror.install(player, snapshot),
                    "a local sneak edge must not discard an otherwise current server state");
            assertEquals((double) 1.8F, player.height);
        }
    }

    @Test void nativePoseMismatchInstallsServerHeightBoundsAndMotionTogether(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9901), true)) {
            var player = world.serverPlayer(new UUID(0, 9902));
            for (float local : new float[]{1.8F, 1.65F, .6F}) {
                for (float accepted : new float[]{1.8F, 1.65F, .6F}) {
                    player.height = local;
                    var state = state(player, accepted);
                    assertTrue(PlayerPhysicsClientMirror.install(player, state), local + " -> " + accepted);
                    assertEquals(accepted, player.height);
                    assertEquals(state.placement().bounds().max().y(), player.getEntityBoundingBox().maxY);
                    assertEquals(state.placement().transport().y(), player.posY);
                    assertEquals(.25, player.motionY);
                }
            }
        }
    }

    @Test void differentPlayerResizeStillRejectsBeforeAnyWrite(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9903), true)) {
            var player = world.serverPlayer(new UUID(0, 9904));
            player.setPosition(1, 65, 2);
            var bounds = player.getEntityBoundingBox();
            var otherPlayer = world.serverPlayer(new UUID(0, 9906));
            var state = state(otherPlayer, 2.0F);
            assertEquals(PlayerPhysicsClientMirror.Rejection.PLAYER_ID, PlayerPhysicsClientMirror.rejection(player, state));
            assertFalse(PlayerPhysicsClientMirror.install(player, state));
            assertEquals(1, player.posX);
            assertEquals(bounds, player.getEntityBoundingBox());
            player.isDead = true;
            assertEquals(PlayerPhysicsClientMirror.Rejection.DEAD,
                    PlayerPhysicsClientMirror.rejection(player, state(player, 2.0F)));
            assertFalse(PlayerPhysicsClientMirror.install(player, state(player, 2.0F)));
            assertEquals(bounds, player.getEntityBoundingBox());
        }
    }

    private static PlayerPhysicalSnapshot state(EntityPlayer player, float height) {
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(.6F, height, 1.54F));
        return new PlayerPhysicalSnapshot(
                new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 9905), player.dimension, 1), 1,
                new PlayerBodyPlacement(new Vec3d(8, 70, 9), CardinalFrame.Y_POS.attitude(), shape),
                new Vec3d(0, 5, 0), Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }
}
