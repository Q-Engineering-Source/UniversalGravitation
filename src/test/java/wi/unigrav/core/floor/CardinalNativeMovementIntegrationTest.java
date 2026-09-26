package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.MoverType;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.CollisionQueryFootprint;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.WorldCollisionCandidates;

/** Uses native Entity.move as a Y+ collision oracle, not as a six-direction gameplay receipt. */
class CardinalNativeMovementIntegrationTest {
    private static final UUID PLAYER = new UUID(0, 201);
    private static final UUID SESSION = new UUID(0, 202);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384, 16, 4096, 4096);
    private static final CardinalMovementResolver.Limits MOVEMENT =
            new CardinalMovementResolver.Limits(512, 16384, 100000);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @Test
    void realNativeMoveAndStrictWorldKernelAgreeForFloorAndWall(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, PLAYER)) {
            var actor = fixture.actor();
            actor.setPosition(8.5, 65, 8.5);
            actor.onGround = true;
            actor.stepHeight = 0;
            var dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
            var placement = new PlayerBodyPlacement(new Vec3d(actor.posX, actor.posY + actor.height / 2.0, actor.posZ),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, 0, 0),
                    0, placement, new Vec3d(0, 0, 0));
            fixture.install(BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(physical), 1.5,
                    new BootQueryFootprint.Limits(1024, 4096, 64, 512)));
            for (int x = 7; x <= 10; x++) {
                for (int z = 7; z <= 10; z++) fixture.put(new BlockPos(x, 64, z), Blocks.STONE.getDefaultState());
            }
            fixture.put(new BlockPos(9, 65, 8), Blocks.STONE.getDefaultState());
            var delta = new Vec3d(1, -0.08, 0.1);
            var request = new CardinalMovementResolver.Request(CardinalFrame.Y_POS, actor.getEntityBoundingBox(),
                    delta, MoverType.SELF, actor.onGround, false, actor.stepHeight);
            var observed = WorldCollisionCandidates.resolveCardinal(fixture.world(), actor, physical, request, SCAN, MOVEMENT);
            assertTrue(observed.worldFailure().isEmpty(), observed.worldFailure()::toString);
            var resolved = assertInstanceOf(CardinalMovementResolver.Resolved.class, observed.calculation().orElseThrow());

            actor.move(MoverType.SELF, delta.x(), delta.y(), delta.z());

            assertEquals(resolved.endBounds(), actor.getEntityBoundingBox());
            assertEquals(resolved.flags().onGround(), actor.onGround);
            assertEquals(resolved.flags().y(), actor.collidedVertically);
            assertEquals(resolved.flags().x() || resolved.flags().z(), actor.collidedHorizontally);
        }
    }
}
