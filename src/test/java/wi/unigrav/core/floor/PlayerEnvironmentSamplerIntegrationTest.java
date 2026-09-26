package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints;

import static org.junit.jupiter.api.Assertions.*;

class PlayerEnvironmentSamplerIntegrationTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void actualIronBootFloorResponseCombinesWithBoundGravityWithoutApplyingMotion(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 701))) {
            var player = fixture.serverPlayer(new UUID(0, 702));
            player.setPosition(8.5, 65, 8.5);
            player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            fixture.put(new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
            var chunk = fixture.world().getChunkProvider().loadedChunks.get(0L);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(chunk));
            var binder = new GravityEntityBinder();
            try (var binding = binder.bind(player, GravityMode.REPLACE);
                 var background = GravityFields.get().registerContribution("fixture", "player-environment",
                         new UniformGravityContribution(new Vec3(0, -8, 0)))) {
                binder.sweepEntity(player);
                player.motionX = 3;
                player.motionY = -4;
                player.motionZ = 5;
                var state = state(player);
                var sampler = new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), 0.5);
                var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, state, Optional.of(false)));
                assertTrue(sampled.bindingActive());
                assertEquals(Optional.of(GravityMode.REPLACE), sampled.bindingMode());
                assertEquals(new Vec3(0, -8, 0), sampled.evidence().gravityAcceleration());
                assertEquals(new Vec3(0, -32, 0), sampled.evidence().magneticAcceleration());
                var result = StandingConstraints.resolve(StandingConstraints.State.initial(),
                        new StandingConstraints.Request(sampled.worldTick(), false, sampled.evidence()));
                assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
                assertEquals(new Vec3(0, -40, 0), result.authoritativeForce().orElseThrow().combinedAcceleration());
                assertEquals(3, player.motionX);
                assertEquals(-4, player.motionY);
                assertEquals(5, player.motionZ);
                assertEquals(state.placement().transport(), new Vec3d(player.posX, player.posY, player.posZ));
            } finally {
                FloorWorldFields.clearAll();
            }
        }
    }

    @Test
    void actualBoundForceContributesToEnvironmentAccelerationWithoutChangingStandingOrMotion(@TempDir Path directory) {
        try (var fixture = Environment.open(directory);
             var background = GravityFields.get().registerContribution("fixture", "player-environment-force",
                     new UniformGravityContribution(new Vec3(0, -8, 0)))) {
            fixture.binder.setBodyPhysics(fixture.player, new BodyPhysics(100, 0.1));
            fixture.binder.setForce(fixture.player, new Vec3(200, 0, 0));
            fixture.player.motionX = 3;
            fixture.player.motionY = -4;
            fixture.player.motionZ = 5;

            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(false)));

            assertEquals(new Vec3(2, -40, 0), sampled.environmentAcceleration());
            assertEquals(new Vec3(0, -8, 0), sampled.evidence().gravityAcceleration());
            assertEquals(new Vec3(0, -32, 0), sampled.evidence().magneticAcceleration());
            var result = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(sampled.worldTick(), false, sampled.evidence()));
            assertEquals(Optional.of(CardinalFrame.Y_POS), result.targetReference());
            assertEquals(3, fixture.player.motionX);
            assertEquals(-4, fixture.player.motionY);
            assertEquals(5, fixture.player.motionZ);
        }
    }

    @Test
    void waterBuoyancyScalesOnlyGravityNotMagnetismOrPersistentForce(@TempDir Path directory) {
        try (var fixture = Environment.open(directory);
             var background = GravityFields.get().registerContribution("fixture", "player-environment-water",
                     new UniformGravityContribution(new Vec3(0, -8, 0)))) {
            fixture.binder.setBodyPhysics(fixture.player, new BodyPhysics(100, 0.1));
            fixture.binder.setForce(fixture.player, new Vec3(200, 0, 0));
            fixture.world.put(new BlockPos(8, 65, 8), Blocks.WATER.getDefaultState());
            fixture.world.put(new BlockPos(8, 66, 8), Blocks.WATER.getDefaultState());
            fixture.player.handleWaterMovement();
            assertTrue(fixture.player.isInWater());

            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(false)));

            assertEquals(new Vec3(0, -8, 0), sampled.evidence().gravityAcceleration());
            assertEquals(new Vec3(0, -32, 0), sampled.evidence().magneticAcceleration());
            assertEquals(new Vec3(2, -33.04, 0), sampled.environmentAcceleration());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmedGShieldsCreativeButNotSurvivalMagnetism(boolean creative, @TempDir Path directory) {
        try (var fixture = Environment.open(directory);
             var background = GravityFields.get().registerContribution("fixture", "personal-g-background",
                     new UniformGravityContribution(new Vec3(0, -8, 0)))) {
            fixture.player.capabilities.isCreativeMode = creative;
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(true)));
            assertTrue(sampled.personalZeroG());
            assertTrue(sampled.bindingActive());
            assertEquals(Vec3.ZERO, sampled.evidence().gravityAcceleration());
            assertEquals(creative ? Vec3.ZERO : new Vec3(0, -32, 0), sampled.evidence().magneticAcceleration());
            assertEquals(creative ? Vec3.ZERO : new Vec3(0, -32, 0), sampled.environmentAcceleration());
            assertEquals(creative ? 0 : 1, sampled.magneticResponse());
            assertTrue(fixture.player.hasNoGravity());
        }
    }

    @Test
    void noGravityFlagDoesNotInventPersonalGIntent(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            assertTrue(fixture.player.hasNoGravity());
            assertReason(PlayerEnvironmentSampler.Reason.PERSONAL_G_UNKNOWN, fixture.sample(Optional.empty()));
        }
    }

    @Test
    void gIntentCannotHideNativeGravityThatHasNotBeenSuppressed(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.binding.close();
            assertFalse(fixture.player.hasNoGravity());
            assertFalse(fixture.player.capabilities.isFlying);
            assertReason(PlayerEnvironmentSampler.Reason.NATIVE_GRAVITY_UNRESOLVED,
                    fixture.sample(Optional.of(true)));
            assertFalse(fixture.player.hasNoGravity());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void additiveOrUnboundVanillaGravityCannotBeReportedAsWeightless(boolean additive, @TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.binding.close();
            assertFalse(fixture.player.hasNoGravity());
            if (additive) {
                try (var ignored = fixture.binder.bind(fixture.player, GravityMode.ADDITIVE)) {
                    assertReason(PlayerEnvironmentSampler.Reason.NATIVE_GRAVITY_UNRESOLVED,
                            fixture.sample(Optional.of(false)));
                }
            } else {
                assertReason(PlayerEnvironmentSampler.Reason.NATIVE_GRAVITY_UNRESOLVED,
                        fixture.sample(Optional.of(false)));
            }
        }
    }

    @Test
    void passiveEligibilityDoesNotRequireGravityBindingOrMultiplyDuplicateRules(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.binding.close();
            // Native suppression is explicit, not inferred from the absent binding.
            fixture.player.setNoGravity(true);
            var rule = new MagneticItemRule("minecraft:iron_boots", null, null, 0.5);
            var materials = new MagneticResponseRules(List.of(), List.of(rule, rule));
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                    new PlayerEnvironmentSampler(fixture.binder, materials, 0.5)
                            .sample(fixture.player, state(fixture.player), Optional.of(false)));
            assertFalse(sampled.bindingActive());
            assertEquals(Optional.empty(), sampled.bindingMode());
            assertEquals(0.5, sampled.magneticResponse());
            assertEquals(new Vec3(0, -16, 0), sampled.evidence().magneticAcceleration());
            assertTrue(sampled.evidence().bootCandidates().isEmpty());
            assertTrue(sampled.evidence().actualSupport().isEmpty());
        }
    }

    @Test
    void floorFalloffUsesActualBodyCenterRatherThanTransportFeet(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.player.setPosition(8.5, 72.5, 8.5);
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(false)));
            // Surface Y=65; centre Y~73.4 lies 0.4m past the 8m plateau.
            assertEquals(-30.4, sampled.evidence().magneticAcceleration().y(), 0.000001);
        }
    }

    @Test
    void opposingRealGravityFloorsCancelForceButKeepCardinalEvidence(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.player.setItemStackToSlot(EntityEquipmentSlot.FEET, ItemStack.EMPTY);
            fixture.world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.world.put(new BlockPos(8, 68, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.DOWN));
            fixture.refreshFloors();
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(false)));
            assertEquals(Vec3.ZERO, sampled.evidence().gravityAcceleration());
            assertEquals(32, sampled.evidence().faceEvidence().get(CardinalFrame.Y_POS).preSumReferenceScore());
            assertEquals(32, sampled.evidence().faceEvidence().get(CardinalFrame.Y_NEG).preSumReferenceScore());
            var resolved = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(sampled.worldTick(), false, sampled.evidence()));
            assertEquals(StandingConstraints.ConstraintPresence.PRESENT, resolved.presence());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"equipment", "position", "tick", "binding", "force", "force-aba"})
    void sourceCallbacksCannotPublishMixedActorObservations(String mutation, @TempDir Path directory) {
        try (var fixture = Environment.open(directory);
             var source = GravityFields.get().registerContribution("fixture", "mutating-source", (position, time) -> {
                 switch (mutation) {
                     case "equipment" -> fixture.player.setItemStackToSlot(EntityEquipmentSlot.FEET,
                             new ItemStack(Items.LEATHER_BOOTS));
                     case "position" -> fixture.player.setPosition(8.6, 65, 8.5);
                      case "tick" -> fixture.world.world().setTotalWorldTime(17);
                      case "binding" -> fixture.binder.setBodyPhysics(fixture.player, new BodyPhysics(90, 0.09));
                      case "force" -> fixture.binder.setForce(fixture.player, new Vec3(200, 0, 0));
                      case "force-aba" -> {
                          fixture.binder.setForce(fixture.player, new Vec3(200, 0, 0));
                          fixture.binder.setForce(fixture.player, null);
                      }
                      default -> throw new AssertionError(mutation);
                 }
                 return new Vec3(0, -8, 0);
             })) {
            assertReason(mutation.equals("binding") || mutation.equals("force") || mutation.equals("force-aba")
                            ? PlayerEnvironmentSampler.Reason.BINDING_CHANGED
                            : PlayerEnvironmentSampler.Reason.ACTOR_CHANGED,
                    fixture.sample(Optional.of(false)));
            assertEquals(0, fixture.player.motionX);
            assertEquals(0, fixture.player.motionY);
            assertEquals(0, fixture.player.motionZ);
        }
    }

    @Test
    void originalSourceFailureIsRetainedAndNotConvertedToZero(@TempDir Path directory) {
        var cause = new IllegalStateException("fixture source failed");
        try (var fixture = Environment.open(directory);
             var source = GravityFields.get().registerContribution("fixture", "throwing-source", (position, time) -> {
                 throw cause;
             })) {
            var unavailable = assertInstanceOf(PlayerEnvironmentSampler.Unavailable.class,
                    fixture.sample(Optional.of(false)));
            assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED, unavailable.reason());
            assertSame(cause, unavailable.cause().orElseThrow());
        }
    }

    @Test
    void nonFinitePersistentForceIsUnavailableWithItsOriginalCause(@TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            fixture.binder.setForce(fixture.player, new Vec3(Double.NaN, 0, 0));

            var unavailable = assertInstanceOf(PlayerEnvironmentSampler.Unavailable.class,
                    fixture.sample(Optional.of(false)));

            assertEquals(PlayerEnvironmentSampler.Reason.SOURCE_FAILED, unavailable.reason());
            assertEquals("non-finite applied force", unavailable.cause().orElseThrow().getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingOrDeferredFloorContextIsNotAnEmptyEnvironment(boolean deferred, @TempDir Path directory) {
        try (var fixture = Environment.open(directory)) {
            if (deferred) fixture.world.world().captureBlockSnapshots = true;
            else FloorWorldFields.clearAll();
            assertReason(PlayerEnvironmentSampler.Reason.WORLD_UNAVAILABLE, fixture.sample(Optional.of(false)));
        }
    }

    @Test
    void aMassivePlayerDoesNotSampleItsOwnGravity(@TempDir Path directory) {
        try (var fixture = Environment.open(directory);
             var background = GravityFields.get().registerContribution("fixture", "self-exclusion-background",
                     new UniformGravityContribution(new Vec3(0, -8, 0)))) {
            fixture.binder.setBodyPhysics(fixture.player, new BodyPhysics(1.0e14, 0.09));
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class, fixture.sample(Optional.of(false)));
            assertEquals(-8, sampled.evidence().gravityAcceleration().y(), 0.000001);
            assertEquals(0, sampled.evidence().gravityAcceleration().x());
            assertEquals(0, sampled.evidence().gravityAcceleration().z());
        }
    }

    private static void assertReason(PlayerEnvironmentSampler.Reason reason, PlayerEnvironmentSampler.Result result) {
        assertEquals(reason, assertInstanceOf(PlayerEnvironmentSampler.Unavailable.class, result).reason());
    }

    private record Environment(BootWorldFixture world, EntityPlayerMP player, GravityEntityBinder binder,
                               GravityEntityBinder.Registration binding) implements AutoCloseable {
        static Environment open(Path directory) {
            var world = BootWorldFixture.open(directory, new UUID(0, 710));
            try {
                var player = world.serverPlayer(new UUID(0, 711));
                player.setPosition(8.5, 65, 8.5);
                player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                world.put(new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
                var binder = new GravityEntityBinder();
                var fixture = new Environment(world, player, binder, binder.bind(player, GravityMode.REPLACE));
                fixture.refreshFloors();
                return fixture;
            } catch (RuntimeException | Error cause) {
                try { FloorWorldFields.clearAll(); } finally { world.close(); }
                throw cause;
            }
        }

        void refreshFloors() {
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(world.world().getChunkProvider().loadedChunks.get(0L)));
        }

        PlayerEnvironmentSampler.Result sample(Optional<Boolean> personalG) {
            return new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), 0.5)
                    .sample(player, state(player), personalG);
        }

        @Override
        public void close() {
            try { binding.close(); }
            finally { try { FloorWorldFields.clearAll(); } finally { world.close(); } }
        }
    }

    private static PlayerPhysicalSnapshot state(EntityPlayerMP player) {
        var box = player.getEntityBoundingBox();
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
        var placement = PlayerBodyPlacement.fromCardinalBounds(new Aabb(new Vec3d(box.minX, box.minY, box.minZ),
                new Vec3d(box.maxX, box.maxY, box.maxZ)), new Vec3d(player.posX, player.posY, player.posZ),
                CardinalFrame.Y_POS.attitude(), shape);
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                new UUID(0, 703), player.dimension, 1), 0, placement, new Vec3d(0, 0, 0));
    }
}
