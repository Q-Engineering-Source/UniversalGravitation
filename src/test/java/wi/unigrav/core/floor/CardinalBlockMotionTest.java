package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.UUID;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.player.CardinalBlockMotion;
import wi.unigrav.core.player.CardinalFrame;

import static org.junit.jupiter.api.Assertions.*;
import static wi.unigrav.core.player.CardinalBlockMotion.Axis.*;

class CardinalBlockMotionTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void actualNativeSlimeBouncesAndSlowsInThePhysicalFrame(CardinalFrame frame, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10126))) {
            var actor = fixture.serverPlayer(new UUID(0, 10127));
            for (boolean sneak : new boolean[]{false, true}) {
                actor.setSneaking(sneak);
                Vec3d before = frame.toWorld(new Vec3d(.2, -.4, .3));
                actor.motionX = before.x(); actor.motionY = before.y(); actor.motionZ = before.z();
                CardinalBlockMotion.run(actor, frame, () -> Blocks.SLIME_BLOCK.onLanded(fixture.world(), actor));
                Vec3d after = frame.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
                assertEquals(.2, after.x(), 1e-12);
                assertEquals(sneak ? 0 : .4, after.y(), 1e-12, "sneak disables native slime bounce");
                assertEquals(.3, after.z(), 1e-12);
            }
            actor.setSneaking(false);
            Vec3d before = frame.toWorld(new Vec3d(.2, .05, .3));
            actor.motionX = before.x(); actor.motionY = before.y(); actor.motionZ = before.z();
            CardinalBlockMotion.run(actor, frame, () -> Blocks.SLIME_BLOCK.onEntityWalk(fixture.world(), new BlockPos(8, 64, 8), actor));
            Vec3d after = frame.toLocal(new Vec3d(actor.motionX, actor.motionY, actor.motionZ));
            assertEquals(.082, after.x(), 1e-12);
            assertEquals(.05, after.y(), 1e-12);
            assertEquals(.123, after.z(), 1e-12);
        }
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void landingNormalAccessPreservesWorldStorageAndTangentialMotion(CardinalFrame frame, @TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10121))) {
            var actor = fixture.serverPlayer(new UUID(0, 10122));
            actor.motionX = 1; actor.motionY = -2; actor.motionZ = 3;
            double expectedNormal = switch (frame) {
                case Y_POS -> -2; case Y_NEG -> 2; case X_POS -> 1;
                case X_NEG -> -1; case Z_POS -> 3; case Z_NEG -> -3;
            };
            CardinalBlockMotion.run(actor, frame, () -> {
                assertEquals(1, actor.motionX, "external callback sees world motion, not a rotated entity");
                assertEquals(-2, actor.motionY);
                assertEquals(3, actor.motionZ);
                assertEquals(expectedNormal, CardinalBlockMotion.read(actor, Y));
                CardinalBlockMotion.write(actor, Y, 0);
            });
            assertEquals(frame == CardinalFrame.X_POS || frame == CardinalFrame.X_NEG ? 0 : 1, actor.motionX);
            assertEquals(frame == CardinalFrame.Y_POS || frame == CardinalFrame.Y_NEG ? 0 : -2, actor.motionY);
            assertEquals(frame == CardinalFrame.Z_POS || frame == CardinalFrame.Z_NEG ? 0 : 3, actor.motionZ);
            assertFalse(CardinalBlockMotion.active(actor));
        }
    }

    @Test void nestedActorScopeAndExceptionRestoreOuterFrame(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10123))) {
            var actor = fixture.serverPlayer(new UUID(0, 10124));
            var other = fixture.serverPlayer(new UUID(0, 10125));
            actor.motionX = 2; actor.motionY = 3; actor.motionZ = 4;
            other.motionX = 5; other.motionY = 6; other.motionZ = 7;
            var failure = new IllegalStateException("callback failed");
            CardinalBlockMotion.run(actor, CardinalFrame.X_POS, () -> {
                assertTrue(CardinalBlockMotion.active(actor));
                assertFalse(CardinalBlockMotion.active(other));
                assertSame(failure, assertThrows(IllegalStateException.class, () ->
                        CardinalBlockMotion.run(other, CardinalFrame.Z_NEG, () -> {
                            assertFalse(CardinalBlockMotion.active(actor));
                            assertEquals(-7, CardinalBlockMotion.read(other, Y));
                            CardinalBlockMotion.write(other, X, 9);
                            throw failure;
                        })));
                assertEquals(2, CardinalBlockMotion.read(actor, Y));
                assertFalse(CardinalBlockMotion.active(other));
                assertEquals(6, CardinalBlockMotion.read(other, Y));
            });
            assertFalse(CardinalBlockMotion.active(actor));
            assertEquals(9, other.motionX);
            assertEquals(6, other.motionY);
            assertEquals(7, other.motionZ);
        }
    }
}
