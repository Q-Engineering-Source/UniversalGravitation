package wi.unigrav.core.floor;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.MathHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalTravelKernel;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardinalTravelKernelTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test void nativeDryMoveRelativeMatchesBitForBit(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 808))) {
            var actor = fixture.actor();
            actor.setPosition(8.5, 65, 8.5);
            fixture.installColumn(0, 0);
            var initial = new Vec3d(.03125, -.0625, .125);
            for (float yaw : new float[]{0, 37, -90, 179, 360}) {
                for (boolean sneak : new boolean[]{false, true}) {
                    for (Vec3d translation : new Vec3d[]{new Vec3d(0, 0, 0), new Vec3d(1, 0, 1), new Vec3d(.01, 0, -.7)}) {
                        actor.rotationYaw = yaw;
                        actor.motionX = initial.x(); actor.motionY = initial.y(); actor.motionZ = initial.z();
                        float sin = MathHelper.sin(yaw * .017453292F), cos = MathHelper.cos(yaw * .017453292F);
                        float x = (float) translation.x(), z = (float) translation.z();
                        float right = x * cos + z * sin, forward = z * cos - x * sin;
                        if (sneak) { right = (float) (right * .3D); forward = (float) (forward * .3D); }
                        actor.moveRelative(right * .98F, 0, forward * .98F, .1F);
                        var result = CardinalTravelKernel.accelerate(initial,
                                new CardinalTravelKernel.Input(CardinalFrame.Y_POS, translation, 1, sneak, false, yaw),
                                new CardinalTravelKernel.Parameters(false, .91F, .1F, .1F, false, .05F, false));
                        assertEquals(new Vec3d(actor.motionX, actor.motionY, actor.motionZ), result);
                    }
                }
            }
        }
    }

    @Test void dryDragAndForceUsePhysicalUpExactlyOnce() {
        for (CardinalFrame frame : CardinalFrame.values()) {
            var motion = frame.toWorld(new Vec3d(.25, -.5, .75));
            var acceleration = frame.toWorld(new Vec3d(0, -32, 0));
            var expected = frame.toWorld(new Vec3d(.25 * .91F,
                    -.5 * .9800000190734863D + -32 * .05 * .05 * .9800000190734863D, .75 * .91F));
            assertEquals(expected, CardinalTravelKernel.finish(motion, frame, acceleration, .05, .91F, -.5));
            var climbing = frame.toWorld(new Vec3d(.25 * .91F,
                    .2 * .9800000190734863D + -32 * .05 * .05 * .9800000190734863D, .75 * .91F));
            assertEquals(climbing, CardinalTravelKernel.finish(motion, frame, acceleration, .05, .91F, .2));
        }
    }

    @Test void flightOpposingKeysCancelAndReleaseClearsJumpCooldown() {
        assertEquals(.123, CardinalTravelKernel.flightInput(.123, true, true, .05F));
        assertEquals(0, CardinalTravelKernel.tickJumpCooldown(10, false));
        assertEquals(9, CardinalTravelKernel.tickJumpCooldown(10, true));
        assertEquals(true, CardinalTravelKernel.shouldJump(0, true, true, false));
        assertEquals(false, CardinalTravelKernel.shouldJump(0, true, true, true));
    }
}
