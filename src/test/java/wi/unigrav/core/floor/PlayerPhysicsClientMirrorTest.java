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
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import static org.junit.jupiter.api.Assertions.*;

/** Real Entity storage projection, not a client runtime or GPU fixture. */
class PlayerPhysicsClientMirrorTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

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

    @Test void unknownResizesStillRejectBeforeAnyWrite(@TempDir Path directory) {
        try (var world = BootWorldFixture.open(directory, new UUID(0, 9903), true)) {
            var player = world.serverPlayer(new UUID(0, 9904));
            player.setPosition(1, 65, 2);
            var bounds = player.getEntityBoundingBox();
            var state = state(player, 2.0F);
            assertEquals(PlayerPhysicsClientMirror.Rejection.HEIGHT, PlayerPhysicsClientMirror.rejection(player, state));
            assertFalse(PlayerPhysicsClientMirror.install(player, state));
            assertEquals(1, player.posX);
            assertEquals(bounds, player.getEntityBoundingBox());
            player.height = 2.0F;
            assertFalse(PlayerPhysicsClientMirror.install(player, state(player, 1.8F)));
            player.height = 1.8F;
            player.width = .7F;
            assertEquals(PlayerPhysicsClientMirror.Rejection.WIDTH, PlayerPhysicsClientMirror.rejection(player, state));
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
