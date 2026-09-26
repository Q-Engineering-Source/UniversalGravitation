package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsStep;

import static org.junit.jupiter.api.Assertions.*;

/** Real MP, floor field, collision and movement budget; no native/GPU client is launched. */
class PlayerCardinalRuntimeIntegrationTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void acceptedCardinalPlayerFallsOntoItsPhysicalFloor(CardinalFrame frame, @TempDir Path directory) {
        exercise(frame, directory, "fall");
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void walkingDrivesBothPhysicalTangentAxesWithoutLiftingFeet(CardinalFrame frame, @TempDir Path directory) {
        exercise(frame, directory, "walk");
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void jumpLeavesPhysicalFloorAlongUpWithNativeOneGHeight(CardinalFrame frame, @TempDir Path directory) {
        exercise(frame, directory, "jump");
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void opposingFieldsRetainCurrentReferenceAndActualSupport(CardinalFrame frame, @TempDir Path directory) {
        exercise(frame, directory, "balanced");
    }

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void slipperinessComesFromTheActualPhysicalFloorOnBothSigns(CardinalFrame frame, @TempDir Path directory) {
        exercise(frame, directory, "slippery");
    }

    private static void exercise(CardinalFrame frame, Path directory, String action) {
        var previousBinder = UnigravMod.entityBinder;
        float previousSlipperiness = FloorBlocks.GRAVITY_FLOOR.slipperiness;
        if (action.equals("slippery")) FloorBlocks.GRAVITY_FLOOR.slipperiness = .98F;
        var binder = new GravityEntityBinder();
        UnigravMod.entityBinder = binder;
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10011), true)) {
            var player = fixture.serverPlayer(new UUID(0, 10012));
            var face = switch (frame) {
                case Y_POS -> EnumFacing.UP;
                case Y_NEG -> EnumFacing.DOWN;
                case X_POS -> EnumFacing.EAST;
                case X_NEG -> EnumFacing.WEST;
                case Z_POS -> EnumFacing.SOUTH;
                case Z_NEG -> EnumFacing.NORTH;
            };
            fixture.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, face));
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0, 0)));
            var up = frame.up();
            var surface = new Vec3d(8.5, 64.5, 8.5).add(up.multiply(.5));
            var shape = new PlayerBodyShape.Cardinal(frame,
                    new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
            var placement = new PlayerBodyPlacement(surface.add(up.multiply(player.height / 2.0 + .5)),
                    frame.attitude(), shape);
            var box = placement.bounds();
            player.setEntityBoundingBox(new AxisAlignedBB(box.min().x(), box.min().y(), box.min().z(),
                    box.max().x(), box.max().y(), box.max().z()));
            player.resetPositionToBB();
            var state = new PlayerPhysicalSnapshot(
                    new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 10013), 0, 1),
                    1, placement, up.multiply(-2),
                    Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
            var position = placement.transport();
            var budget = new BoostMovementBudget(70, 0, new Vec3(position.x(), position.y(), position.z()));
            var travel = new PlayerPhysicsStep.TravelState();
            var step = new PlayerPhysicsStep(new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), .5));
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                    new Vec3d(0, 0, 1), false, false, false, false, 5);
            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                for (int tick = 1; tick <= 12; tick++) {
                    fixture.world().setTotalWorldTime(tick);
                    var result = step.step(player, state, Optional.of(false), controls, budget, tick, false, travel);
                    state = assertInstanceOf(PlayerPhysicsStep.Applied.class, result, frame + " tick " + tick).snapshot();
                    if (tick == 1) {
                        assertEquals(.4, frame.toLocal(state.placement().physicalFeet().orElseThrow().subtract(surface)).y(),
                                1e-9, "2 m/s must move exactly .1 m toward the selected floor");
                        assertEquals(-3.5280000686645505, frame.toLocal(state.worldVelocity()).y(), 1e-9,
                                "one 32 m/s2 kick, then native vertical drag, in the physical up axis");
                    }
                }
                assertEquals(0, frame.toLocal(state.placement().physicalFeet().orElseThrow().subtract(surface)).y(),
                        1e-9, "collision must stop at the physical floor, not world minY");
                assertTrue(state.contacts().orElseThrow().onGround(), "physical ground contact in " + frame);
                assertFalse(state.contacts().orElseThrow().horizontal(), "landing is not a sideways impact");
                assertEquals(frame, ((PlayerBodyShape.Cardinal) state.placement().shape()).frame());
                if (action.equals("balanced")) {
                    fixture.put(new BlockPos(8 + up.x() * 4, 64 + up.y() * 4, 8 + up.z() * 4),
                            FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                                    .withProperty(DirectionalFloorBlock.FACING, face.getOpposite()));
                    FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0, 0)));
                }
                if (action.equals("walk") || action.equals("balanced") || action.equals("slippery")) {
                    var input = frame.toWorld(new Vec3d(Math.sqrt(.5), 0, Math.sqrt(.5)));
                    var walking = new PlayerPhysicsProtocol.Controls(input, input, false, false, false, false, 5);
                    var before = state.placement().center();
                    fixture.world().setTotalWorldTime(13);
                    state = assertInstanceOf(PlayerPhysicsStep.Applied.class, step.step(player, state,
                            Optional.of(false), walking, budget, 13, false, travel)).snapshot();
                    var delta = frame.toLocal(state.placement().center().subtract(before));
                    if (action.equals("slippery"))
                        assertTrue(delta.x() > .015 && delta.x() < .017,
                                "slippery floor must use its coefficient instead of neighboring air: " + delta);
                    else assertTrue(delta.x() > .06 && delta.x() < .08, "local right input must cause normal ground movement: " + delta);
                    assertEquals(delta.x(), delta.z(), 1e-7, "diagonal input must drive both tangent axes equally");
                    assertEquals(0, delta.y(), 1e-9, "walking must not become a world-Y lift");
                    if (action.equals("balanced")) {
                        assertEquals(0, frame.toLocal(state.worldVelocity()).y(), 1e-9, "opposing forces must cancel");
                        for (int tick = 14; tick <= 16; tick++) {
                            fixture.world().setTotalWorldTime(tick);
                            state = assertInstanceOf(PlayerPhysicsStep.Applied.class, step.step(player, state,
                                    Optional.of(false), controls, budget, tick, false, travel)).snapshot();
                            assertTrue(state.contacts().orElseThrow().onGround(),
                                    "zero resultant is not loss of the real surface under the feet");
                            assertEquals(frame, ((PlayerBodyShape.Cardinal) state.placement().shape()).frame());
                        }
                    }
                } else if (action.equals("jump")) {
                    var jumping = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                            frame.toWorld(new Vec3d(0, 0, 1)), true, false, false, false, 5);
                    double peak = 0;
                    for (int tick = 13; tick <= 29; tick++) {
                        fixture.world().setTotalWorldTime(tick);
                        state = assertInstanceOf(PlayerPhysicsStep.Applied.class, step.step(player, state,
                                Optional.of(false), tick == 13 ? jumping : controls, budget, tick, false, travel)).snapshot();
                        double height = frame.toLocal(state.placement().physicalFeet().orElseThrow().subtract(surface)).y();
                        if (tick == 13) {
                            assertEquals((double) .42F, height, 1e-8, "native launch height along physical up");
                            assertFalse(state.contacts().orElseThrow().onGround());
                        }
                        peak = Math.max(peak, height);
                    }
                    assertTrue(peak > 1.2 && peak < 1.3, "1G jump should retain native height, got " + peak);
                    assertEquals(0, frame.toLocal(state.placement().physicalFeet().orElseThrow().subtract(surface)).y(), 1e-9);
                }
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
            FloorBlocks.GRAVITY_FLOOR.slipperiness = previousSlipperiness;
        }
    }
}
