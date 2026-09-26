package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

class NativePlayerMovementIntegrationTest {
    private static final UUID PLAYER = new UUID(0, 211);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void nativeMoveIsPendingUntilFinalCommitAndCommitsOnlyOnce(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            assertEquals(directory, fixture.world().getSaveHandler().getWorldDirectory().toPath());
            player.setPosition(8.5, 65, 8.5);
            var dimensions = new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight());
            var placement = PlayerBodyPlacement.fromTransport(new Vec3d(8.5, 65, 8.5), CardinalFrame.Y_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            var fixturePhysical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, PLAYER, 0, 0),
                    0, placement, new Vec3d(0, 0, 0));
            fixture.install(BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(fixturePhysical), 1.5,
                    new BootQueryFootprint.Limits(1024, 4096, 64, 512)));
            var movement = new NativePlayerMovement();
            player.motionX = 0.125;
            movement.begin(player.connection, false);
            player.move(MoverType.PLAYER, 0.25, 0, 0);
            movement.captureNativeMove(player.connection);
            assertTrue(movement.current(player.connection).isEmpty());
            movement.commitBeforeTracking(player.connection, false);
            var receipt = movement.current(player.connection).orElseThrow();
            assertEquals(new Vec3d(8.75, 65, 8.5), receipt.placement().transport());
            assertEquals(new Vec3d(0.125, 0, 0), receipt.observedNativeMotionBlocksPerTick());
            movement.commitBeforeTracking(player.connection, false);
            movement.finish(player.connection);
            assertEquals(receipt, movement.current(player.connection).orElseThrow());
            capture(movement, player);
            movement.commitBeforeTracking(player.connection, false);
            var next = movement.current(player.connection).orElseThrow();
            assertEquals(receipt.scope(), next.scope());
            assertEquals(receipt.delegateRevision() + 1, next.delegateRevision());
        }
    }

    @Test
    void overlappingBeginsCannotRebindAnOldCallbackToANewLifetime(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            player.setPosition(8.5, 65, 8.5);
            var movement = new NativePlayerMovement();
            movement.begin(player.connection, false);
            movement.begin(player.connection, false);
            player.move(MoverType.PLAYER, 0.25, 0, 0);
            movement.captureNativeMove(player.connection);
            movement.commitBeforeTracking(player.connection, false);
            movement.finish(player.connection);
            assertTrue(movement.current(player.connection).isEmpty());
        }
    }

    @Test
    void finalAssignmentMismatchAndUnfinishedReturnDoNotCertifyMovement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            player.setPosition(8.5, 65, 8.5);
            var movement = new NativePlayerMovement();
            capture(movement, player);
            player.setPosition(9.25, 65, 8.5);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isEmpty());
            assertEquals(9.25, player.posX); // The receipt observer must not repair the entity.

            capture(movement, player);
            movement.finish(player.connection);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isEmpty());
        }
    }

    @Test
    void tickTeleportAndMotionChangesRetireEvidenceWithoutChangingThePlayer(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            player.setPosition(8.5, 65, 8.5);
            var movement = new NativePlayerMovement();
            capture(movement, player);
            fixture.world().setTotalWorldTime(1);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isEmpty());
            capture(movement, player);
            movement.commitBeforeTracking(player.connection, true);
            assertTrue(movement.current(player.connection).isEmpty());
            capture(movement, player);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isPresent());
            player.motionZ = 0.5;
            assertTrue(movement.current(player.connection).isEmpty());
            assertEquals(0.5, player.motionZ);
            capture(movement, player);
            movement.commitBeforeTracking(player.connection, false);
            var beforeTeleport = movement.current(player.connection).orElseThrow();
            movement.invalidate(player.connection);
            assertTrue(movement.current(player.connection).isEmpty());
            capture(movement, player);
            movement.commitBeforeTracking(player.connection, false);
            assertNotEquals(beforeTeleport.scope().sessionId(),
                    movement.current(player.connection).orElseThrow().scope().sessionId());
        }
    }

    @Test
    void lifecycleCallbacksRetireScopeAndOldLogoutCannotRemoveReplacement(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            player.setPosition(8.5, 65, 8.5);
            var movement = new NativePlayerMovement();
            // Actual event payloads and public handlers; FML event-bus registration requires a running Mod container.
            try {
                capture(movement, player);
                movement.commitBeforeTracking(player.connection, false);
                var first = movement.current(player.connection).orElseThrow();
                movement.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
                assertTrue(movement.current(player.connection).isEmpty());

                var replacement = fixture.serverPlayer(PLAYER);
                replacement.connection = player.connection;
                replacement.connection.player = replacement;
                replacement.setPosition(8.5, 65, 8.5);
                capture(movement, replacement);
                movement.commitBeforeTracking(replacement.connection, false);
                var second = movement.current(replacement.connection).orElseThrow();
                assertNotEquals(first.scope().sessionId(), second.scope().sessionId());
                movement.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
                assertEquals(second, movement.current(replacement.connection).orElseThrow());

                movement.onWorldUnload(new WorldEvent.Unload(fixture.world()));
                assertTrue(movement.current(replacement.connection).isEmpty());
            } finally {
                movement.stop();
            }
        }
    }

    @Test
    void unsupportedStateAndForeignReceiptGeometryAreNotAccepted(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var player = fixture.serverPlayer(PLAYER);
            player.setPosition(8.5, 65, 8.5);
            var movement = new NativePlayerMovement();
            player.noClip = true;
            movement.begin(player.connection, false);
            movement.captureNativeMove(player.connection);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isEmpty());
            player.noClip = false;
            var original = player.getEntityBoundingBox();
            player.setEntityBoundingBox(original.grow(0.01));
            movement.begin(player.connection, false);
            movement.captureNativeMove(player.connection);
            movement.commitBeforeTracking(player.connection, false);
            assertTrue(movement.current(player.connection).isEmpty());
            assertEquals(original.grow(0.01), player.getEntityBoundingBox());
            var foreign = new PlayerBodyPlacement(new Vec3d(8.5, 65, 8.5), CardinalFrame.X_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                            new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight())));
            assertThrows(IllegalArgumentException.class, () -> new NativePlayerMovement.Receipt(
                    new PlayerPhysicalSnapshot.Scope(PLAYER, PLAYER, 0, 0), 0, foreign, new Vec3d(0, 0, 0)));
        }
    }

    private static void capture(NativePlayerMovement movement, EntityPlayerMP player) {
        movement.begin(player.connection, false);
        player.move(MoverType.PLAYER, 0.25, 0, 0);
        movement.captureNativeMove(player.connection);
    }
}
