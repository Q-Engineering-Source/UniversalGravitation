package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalReorientation;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsStep;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Production standing-reference adoption through the real movement transaction.
 *
 * <p>Every scene starts with a body genuinely standing on a Y+ gravity floor, plus a second
 * gravity floor whose outward face is exactly the plane the rotated body's feet would land on.
 * The source floor is then re-aimed downward: that is a collision no-op but removes its standing
 * field, leaving the target frame as the only effective constraint. Nothing here hand-installs a
 * non-Y+ accepted state, so the target frame can only appear if the production step adopts it.
 *
 * <p>Geometry is exact for the native 0.6 x 1.8 profile, whose half height is 0.9. Each target
 * floor face is placed so the rotated feet plane is bit-exactly tangent to it, and each floor the
 * body already occupies is bit-exactly tangent to the rotation sweep envelope. A one-ulp error in
 * either place would turn these cases into rejections, so an adopted frame is itself evidence that
 * the envelope stays tangent to ordinary floors.
 */
class CardinalReorientationRuntimeTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final double EXACT = 1.0e-9;
    /**
     * Native {@code width}/{@code height} are floats, so a 0.6 m body is {@code 0.6000000238418579}
     * once widened to double. The scene's integer block faces only line up because the widened
     * half height is still the 0.9 m the sweep envelope is built from.
     */
    private static final double FLOAT_EXACT = 1.0e-6;

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void shortWideNativeShapeRejectsRotationWithoutChangingActorOrBudget(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10421), true)) {
            var player = fixture.serverPlayer(new UUID(0, 10422));
            player.width = .6F;
            player.height = .5999999642372131F;
            player.setPosition(8, 65, 8);
            var dimensions = new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight());
            var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions);
            var placement = PlayerBodyPlacement.fromTransport(new Vec3d(8, 65, 8), CardinalFrame.Y_POS.attitude(), shape);
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                    new UUID(0, 10423), player.dimension, 1), 1, placement, ZERO);
            var budget = new BoostMovementBudget(70, 0, new Vec3(8, 65, 8));
            var before = budget.snapshot();
            var bounds = player.getEntityBoundingBox();
            var result = CardinalReorientation.adopt(fixture.world(), player, accepted, CardinalFrame.X_POS, budget, 0);
            var rejected = assertInstanceOf(CardinalReorientation.Rejected.class, result);
            assertEquals(CardinalReorientation.Reason.UNSUPPORTED_SHAPE, rejected.reason());
            assertEquals(before, budget.snapshot());
            assertEquals(bounds, player.getEntityBoundingBox());
            assertEquals(8, player.posX);
            assertEquals(CardinalFrame.Y_POS, frame(accepted));
        }
    }

    @ParameterizedTest
    @EnumSource(value = CardinalFrame.class, names = {"Y_NEG", "X_POS", "X_NEG", "Z_POS", "Z_NEG"})
    void effectiveConstraintAdoptsTheTargetStandingFrame(CardinalFrame target, @TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        var binder = new GravityEntityBinder();
        UnigravMod.entityBinder = binder;
        Scene scene = Scene.of(target);
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10401), true)) {
            var player = fixture.serverPlayer(new UUID(0, 10402));
            placeFloors(fixture, scene, EnumFacing.UP);
            var accepted = standOnSourceFloor(player, scene);
            var transport = accepted.placement().transport();
            var budget = new BoostMovementBudget(70, 0, new Vec3(transport.x(), transport.y(), transport.z()));
            var travel = new PlayerPhysicsStep.TravelState();
            var step = new PlayerPhysicsStep(new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), .5));

            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                // Both fields are effective, so the already accepted reference is retained.
                fixture.world().setTotalWorldTime(1);
                var retained = apply(step, player, accepted, budget, travel, 1);
                assertEquals(CardinalFrame.Y_POS, frame(retained), "an eligible accepted reference must be retained");
                assertEquals(0, travel.adoptions(), "no reorientation may happen while Y+ is still effective");
                assertTrue(travel.pendingReference().isEmpty(), "a retained reference leaves no pending target");
                assertEquals(scene.center(), retained.placement().center(), "retention must not move the body");

                // Re-aim the source floor downward: collision is unchanged, its standing field is gone.
                placeFloors(fixture, scene, EnumFacing.DOWN);
                fixture.world().setTotalWorldTime(2);
                var adopted = apply(step, player, retained, budget, travel, 2);

                assertEquals(target, frame(adopted), target + " must become the accepted standing reference");
                assertEquals(1, travel.adoptions(), "exactly one reorientation may have been installed");
                assertTrue(travel.pendingReference().isEmpty(), "a successful adoption clears the pending target");
                assertEquals(scene.grounded, travel.lastAdoptionGrounded(),
                        target + " support must be re-derived in the new frame, not inherited");
                assertEquals(scene.grounded, adopted.contacts().orElseThrow().onGround(),
                        "the published contact must be the re-derived one");

                // The whole state moved together: bounds, transport mirror, frame and dimensions.
                var placement = adopted.placement();
                // The prior native-style tail carries gravity velocity into this movement tick.
                // A side frame permits that Y component as tangent motion; rotation itself remains atomic.
                double dy = target == CardinalFrame.Y_NEG ? 0 : retained.worldVelocity().y() * GravityEntityBinder.TICK_SECONDS;
                assertEquals(scene.center().x(), placement.center().x(), EXACT);
                assertEquals(scene.center().y() + dy, placement.center().y(), EXACT,
                        "only the preceding tick's velocity may translate the adopted body");
                assertEquals(scene.center().z(), placement.center().z(), EXACT);
                assertBounds(scene.expectedBounds().offset(0, dy, 0), placement.bounds(), target);
                assertEquals(scene.expectedTransport().x(), placement.transport().x(), EXACT,
                        "the native transport mirror must follow the new collider");
                assertEquals(scene.expectedTransport().y() + dy, placement.transport().y(), EXACT,
                        "an X/Z frame's minimum Y is not the Y+ body's minimum Y");
                assertEquals(scene.expectedTransport().z(), placement.transport().z(), EXACT,
                        "the native transport mirror must follow the new collider");
                assertEquals(player.width, placement.shape().dimensions().width(), EXACT,
                        "width is frame independent");
                assertEquals(player.height, placement.shape().dimensions().height(), EXACT,
                        "height is frame independent");
                assertEquals(EnumFacing.DOWN, floorFacing(fixture, scene.sourceFloor()),
                        "the source floor keeps the direction deliberately set before this tick");
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test
    void occupiedTargetKeepsTheWholeAcceptedStateAndTheSameBudget(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        var binder = new GravityEntityBinder();
        UnigravMod.entityBinder = binder;
        // Standing 0.4 m closer to the target floor, so rotating into X+ would bury the body in it.
        Scene scene = Scene.of(CardinalFrame.X_POS).blocked();
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10411), true)) {
            var player = fixture.serverPlayer(new UUID(0, 10412));
            placeFloors(fixture, scene, EnumFacing.DOWN);
            var accepted = standOnSourceFloor(player, scene);
            var transport = accepted.placement().transport();
            var budget = new BoostMovementBudget(70, 0, new Vec3(transport.x(), transport.y(), transport.z()));
            var travel = new PlayerPhysicsStep.TravelState();
            var step = new PlayerPhysicsStep(new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), .5));
            var boxBefore = player.getEntityBoundingBox();
            double x = player.posX;
            double y = player.posY;
            double z = player.posZ;
                budget.advance(1);
                var creditBefore = budget.snapshot().distanceCredit();

            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                fixture.world().setTotalWorldTime(1);
                var result = apply(step, player, accepted, budget, travel, 1);

                assertEquals(CardinalFrame.Y_POS, frame(result), "a rejected transition must keep the old frame");
                assertEquals(CardinalFrame.X_POS, travel.pendingReference().orElseThrow(),
                        "the rejected target must stay explicit so it can be retried");
                assertTrue(travel.pendingReason().isPresent(), "a rejected transition must record why");
                assertEquals(0, travel.adoptions(), "a rejected transition installs nothing");

                // Nothing half-installed: no new frame with old bounds, and no free displacement.
                assertEquals(boxBefore, player.getEntityBoundingBox(),
                        "a rejected transition must leave the native collider untouched");
                assertEquals(x, player.posX, 0.0, "a rejected transition must not move the actor");
                assertEquals(y, player.posY, 0.0, "a rejected transition must not move the actor");
                assertEquals(z, player.posZ, 0.0, "a rejected transition must not move the actor");
                assertEquals(transport.y(), result.placement().transport().y(), 0.0,
                        "the accepted transport mirror must not have been rewritten");
                assertEquals(CardinalFrame.Y_POS, frame(result), "the accepted frame must not have been rewritten");
                // The move itself debits credit; a rejected reorientation must not add or refund any.
                assertTrue(budget.snapshot().distanceCredit() <= creditBefore + EXACT,
                        "a rejected reorientation must not mint movement credit");
            }
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    @Test
    void adoptionInstallsOneAtomicStateWithoutSpendingBudgetCredit(@TempDir Path directory) {
        var previousBinder = UnigravMod.entityBinder;
        var binder = new GravityEntityBinder();
        UnigravMod.entityBinder = binder;
        Scene scene = Scene.of(CardinalFrame.X_POS);
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 10421), true)) {
            var player = fixture.serverPlayer(new UUID(0, 10422));
            placeFloors(fixture, scene, EnumFacing.DOWN);
            var accepted = standOnSourceFloor(player, scene);
            var transport = accepted.placement().transport();
            var budget = new BoostMovementBudget(70, 0, new Vec3(transport.x(), transport.y(), transport.z()));
            budget.advance(1);
            var creditBefore = budget.snapshot().distanceCredit();
            var ceilingBefore = budget.ceilingMps();
            var sequenceBefore = budget.lastSequence();

            var outcome = CardinalReorientation.adopt(fixture.world(), player, accepted,
                    CardinalFrame.X_POS, budget, 1);

            var installed = assertInstanceOf(CardinalReorientation.Installed.class, outcome);
            assertEquals(CardinalFrame.X_POS, installed.frame());
            assertEquals(scene.center(), installed.snapshot().placement().center(),
                    "the body centre is preserved exactly; only the collider orientation changes");
            assertEquals(Math.incrementExact(accepted.revision()), installed.snapshot().revision(),
                    "one reorientation is exactly one physical revision, not two half commits");
            assertBounds(scene.expectedBounds(), installed.snapshot().placement().bounds(), CardinalFrame.X_POS);
            assertEquals(scene.expectedTransport(), installed.snapshot().placement().transport(),
                    "the anchor is the new collider's reset mirror, not a displaced body");
            assertNotEquals(transport.y(), installed.snapshot().placement().transport().y(),
                    "an X+ body's minimum Y legitimately differs from a Y+ body's");
            assertTrue(installed.onGround(), "the rotated feet are exactly on the target floor face");
            // ...but that anchor change is not displacement and must not mint or spend movement.
            assertEquals(creditBefore, budget.snapshot().distanceCredit(), 0.0,
                    "an anchor rebase must not mint or consume a single metre of credit");
            assertEquals(ceilingBefore, budget.ceilingMps(), 0.0, "a reorientation must not change the ceiling");
            assertEquals(sequenceBefore, budget.lastSequence(), "a reorientation is not a movement sequence");
            assertEquals(new Vec3(scene.expectedTransport().x(), scene.expectedTransport().y(),
                            scene.expectedTransport().z()), budget.position(),
                    "the one budget must be rebased onto the new accepted anchor");
        } finally {
            FloorWorldFields.clearAll();
            UnigravMod.entityBinder = previousBinder;
        }
    }

    private static PlayerPhysicalSnapshot apply(PlayerPhysicsStep step, EntityPlayerMP player,
                                                PlayerPhysicalSnapshot accepted, BoostMovementBudget budget,
                                                PlayerPhysicsStep.TravelState travel, long tick) {
        var controls = new PlayerPhysicsProtocol.Controls(ZERO, new Vec3d(0, 0, 1),
                false, false, false, false, 5);
        var result = step.step(player, accepted, Optional.of(false), controls, budget, tick, false, travel);
        return assertInstanceOf(PlayerPhysicsStep.Applied.class, result, "tick " + tick).snapshot();
    }

    private static CardinalFrame frame(PlayerPhysicalSnapshot snapshot) {
        return ((PlayerBodyShape.Cardinal) snapshot.placement().shape()).frame();
    }

    private static void assertBounds(AxisAlignedBB expected, dev.crlhitbox.api.geometry.Aabb actual,
                                     CardinalFrame frame) {
        assertEquals(expected.minX, actual.min().x(), EXACT, frame + " collider min X");
        assertEquals(expected.minY, actual.min().y(), EXACT, frame + " collider min Y");
        assertEquals(expected.minZ, actual.min().z(), EXACT, frame + " collider min Z");
        assertEquals(expected.maxX, actual.max().x(), EXACT, frame + " collider max X");
        assertEquals(expected.maxY, actual.max().y(), EXACT, frame + " collider max Y");
        assertEquals(expected.maxZ, actual.max().z(), EXACT, frame + " collider max Z");
    }

    private static void placeFloors(BootWorldFixture fixture, Scene scene, EnumFacing sourceFacing) {
        fixture.put(scene.sourceFloor(),
                FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, sourceFacing));
        fixture.put(scene.targetFloor(),
                FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, scene.targetFacing()));
        FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0, 0)));
    }

    private static EnumFacing floorFacing(BootWorldFixture fixture, BlockPos cell) {
        return fixture.world().getBlockState(cell).getValue(DirectionalFloorBlock.FACING);
    }

    private static PlayerPhysicalSnapshot standOnSourceFloor(EntityPlayerMP player, Scene scene) {
        // native width/height are floats; the scene numbers below assume the native profile.
        assertEquals(0.6, player.width, FLOAT_EXACT, "scenes assume the native 0.6 m body width");
        assertEquals(1.8, player.height, FLOAT_EXACT, "scenes assume the native 1.8 m body height");
        // The scene's integer block faces only line up because half height is 0.9 and half width 0.3.
        assertEquals(0.9, player.height / 2.0, FLOAT_EXACT, "scenes assume a 0.9 m half height");
        assertEquals(0.3, player.width / 2.0, FLOAT_EXACT, "scenes assume a 0.3 m half width");
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
        var placement = new PlayerBodyPlacement(scene.center(), CardinalFrame.Y_POS.attitude(), shape);
        var bounds = placement.bounds();
        player.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        player.resetPositionToBB();
        player.onGround = true;
        player.motionX = 0;
        player.motionY = 0;
        player.motionZ = 0;
        assertEquals(scene.center().x(), player.posX, EXACT, "the fixture must start at the scene centre");
        assertEquals(scene.center().y(), player.posY + player.height / 2.0, EXACT,
                "the fixture must start from a real Y+ stand at the scene centre");
        assertEquals(scene.center().z(), player.posZ, EXACT, "the fixture must start at the scene centre");
        // The body must actually rest on a real block top, or the scene is not a stand at all.
        assertEquals(65.0, player.getEntityBoundingBox().minY, EXACT,
                "the Y+ stand must have its feet exactly on the source floor's top face");
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                new UUID(0, 10400), 0, 1), 1, placement, ZERO,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true, false, false, 0)));
    }

    /**
     * Exact scene for one target frame. For the native 0.6 x 1.8 profile the body half height is
     * 0.9 and its half width 0.3, so every target floor face is chosen so that
     * {@code centre - up * 0.9} lands on an integer block face.
     */
    private record Scene(Vec3d center, BlockPos sourceFloor, BlockPos targetFloor, EnumFacing targetFacing,
                         CardinalFrame target, boolean grounded, AxisAlignedBB expectedBounds,
                         Vec3d expectedTransport) {

        static Scene of(CardinalFrame target) {
            double h = (double) 1.8F / 2;
            double r = (double) .6F / 2;
            double y = 65 + h;
            return switch (target) {
                // Feet land on x = 8, the east face of the floor in column 7.
                case X_POS -> scene(new Vec3d(8 + h, y, 8.5), new BlockPos(8, 64, 8), new BlockPos(7, 65, 8),
                        EnumFacing.EAST, target, true, new Vec3d(8, y - r, 8.5 - r), new Vec3d(8 + 2 * h, y + r, 8.5 + r));
                // Feet land on x = 8, the west face of the floor in column 8.
                case X_NEG -> scene(new Vec3d(8 - h, y, 8.5), new BlockPos(7, 64, 8), new BlockPos(8, 65, 8),
                        EnumFacing.WEST, target, true, new Vec3d(8 - 2 * h, y - r, 8.5 - r), new Vec3d(8, y + r, 8.5 + r));
                // Feet land on z = 8, the south face of the floor in column 7.
                case Z_POS -> scene(new Vec3d(8.5, y, 8 + h), new BlockPos(8, 64, 8), new BlockPos(8, 65, 7),
                        EnumFacing.SOUTH, target, true, new Vec3d(8.5 - r, y - r, 8), new Vec3d(8.5 + r, y + r, 8 + 2 * h));
                // Feet land on z = 8, the north face of the floor in column 8.
                case Z_NEG -> scene(new Vec3d(8.5, y, 8 - h), new BlockPos(8, 64, 7), new BlockPos(8, 65, 8),
                        EnumFacing.NORTH, target, true, new Vec3d(8.5 - r, y - r, 8 - 2 * h), new Vec3d(8.5 + r, y + r, 8));
                // An inverted body cannot rest on a floor and on its own stand at once, so its
                // floor sits two blocks above the sweep envelope and the body falls up towards it.
                case Y_NEG -> scene(new Vec3d(8.5, y, 8.5), new BlockPos(8, 64, 8), new BlockPos(8, 67, 8),
                        EnumFacing.DOWN, target, false, new Vec3d(8.5 - r, 65, 8.5 - r), new Vec3d(8.5 + r, 65 + 2 * h, 8.5 + r));
                case Y_POS -> throw new IllegalArgumentException("Y_POS is the accepted reference, not a target");
            };
        }

        /** The same two floors, but the body stands 0.4 m closer so the rotation would enter the target floor. */
        Scene blocked() {
            return new Scene(new Vec3d(8.5, center.y(), 8.5), sourceFloor, targetFloor, targetFacing, target,
                    grounded, expectedBounds, expectedTransport);
        }

        private static Scene scene(Vec3d center, BlockPos sourceFloor, BlockPos targetFloor, EnumFacing facing,
                                   CardinalFrame target, boolean grounded, Vec3d min, Vec3d max) {
            return new Scene(center, sourceFloor, targetFloor, facing, target, grounded,
                    new AxisAlignedBB(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()),
                    // resetPositionToBB's mirror: box-centre tangent coordinates with the minimum Y.
                    new Vec3d((min.x() + max.x()) / 2, min.y(), (min.z() + max.z()) / 2));
        }
    }
}
