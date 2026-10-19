package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerPhysicsStep;
import wi.unigrav.core.player.StandingConstraints;

import static org.junit.jupiter.api.Assertions.*;

/** Real server-world floor sampling and physics adoption; no mocked geometry or support. */
class FloorAutoReferenceIntegrationTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void sideFloorAdoptsAgainstLiveDownwardAmbientGravity(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var policy = FloorPolicy.set(false, false);
             var fixture = BootWorldFixture.open(directory, new UUID(0, 81001), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 81002));
            // Preserve the original near-contact scene: the native float body height leaves
            // a small positive gap at the proposed +X face, so adoption must not invent support.
            fixture.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0, 0)));
            player.setPosition(9.9, 63.8, 8.5);
            var accepted = yPlusState(player);
            var budget = new BoostMovementBudget(70, 0,
                    new Vec3(accepted.placement().transport().x(), accepted.placement().transport().y(),
                            accepted.placement().transport().z()));
            var travel = new PlayerPhysicsStep.TravelState();
            var sampler = new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), 0.5);
            var step = new PlayerPhysicsStep(sampler);
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                    new Vec3d(0, 0, 1), false, false, false, false, 5);

            try (var binding = binder.bind(player, GravityMode.REPLACE);
                 var ambient = GravityFields.get().registerContribution("floor-auto-reference",
                         "live-downward-ambient", new UniformGravityContribution(new Vec3(0, -32, 0)))) {
                var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, accepted, Optional.of(false)));
                assertFalse(sampled.personalZeroG(), "the sampled G intent remains OFF");
                assertEquals(-32.0, sampled.evidence().gravityAcceleration().y(), 1.0e-9,
                        "the ordinary downward ambient field remains effective");
                assertTrue(sampled.evidence().gravityAcceleration().x() < 0.0,
                        "the real side floor contributes its inward force");

                fixture.world().setTotalWorldTime(1);
                PlayerPhysicsStep.Applied applied = assertInstanceOf(PlayerPhysicsStep.Applied.class,
                        step.step(player, accepted, Optional.of(false), controls, budget, 1,
                                false, travel));
                accepted = applied.snapshot();

                assertEquals(CardinalFrame.X_POS,
                        ((PlayerBodyShape.Cardinal) accepted.placement().shape()).frame(),
                        "an effective floor reference takes priority over ambient Y+ continuity");
                assertFalse(travel.lastAdoptionGrounded(), "nearby field adoption does not invent physical support");
                assertEquals(accepted.placement().bounds(), actualBounds(player),
                        "the published placement must match the installed native collider");
                double actualFaceGap = accepted.placement().bounds().min().x() - 9.0;
                assertTrue(actualFaceGap > 0.0 && actualFaceGap < 1.0e-6,
                        "the real native dimensions leave a small positive gap to the floor face");
            }
        } finally {
            FloorWorldFields.clearAll();
        }
    }

    @Test
    void exactFloatDerivedSideFloorContactReDerivesRealSupport(@TempDir Path directory) {
        var binder = new GravityEntityBinder();
        try (var policy = FloorPolicy.set(false, false);
             var fixture = BootWorldFixture.open(directory, new UUID(0, 81201), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 81202));
            double halfHeight = (double) player.height / 2.0;
            double centerY = 64.5;
            player.setPosition(9.0 + halfHeight, centerY - halfHeight, 8.5);
            fixture.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(fixture.world().getChunk(0, 0)));
            var accepted = yPlusState(player);
            var budget = new BoostMovementBudget(70, 0,
                    new Vec3(accepted.placement().transport().x(), accepted.placement().transport().y(),
                            accepted.placement().transport().z()));
            var travel = new PlayerPhysicsStep.TravelState();
            var step = new PlayerPhysicsStep(new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), 0.5));
            var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 0),
                    new Vec3d(0, 0, 1), false, false, false, false, 5);

            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                fixture.world().setTotalWorldTime(1);
                PlayerPhysicsStep.Applied applied = assertInstanceOf(PlayerPhysicsStep.Applied.class,
                        step.step(player, accepted, Optional.of(false), controls, budget, 1,
                                false, travel));

                assertEquals(CardinalFrame.X_POS,
                        ((PlayerBodyShape.Cardinal) applied.snapshot().placement().shape()).frame());
                var feet = applied.snapshot().placement().physicalFeet().orElseThrow();
                assertEquals(9.0, feet.x(), 1.0e-12, "the physical foot plane is exactly at the real floor face");
                assertEquals(centerY, feet.y(), 1.0e-12);
                assertTrue(travel.lastAdoptionGrounded(), "zero-gap support is re-derived from the real block geometry");
                assertEquals(applied.snapshot().placement().bounds(), actualBounds(player));
            }
        } finally {
            FloorWorldFields.clearAll();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false,false,true,true",
            "false,true,true,false",
            "true,false,false,true",
            "true,true,false,false"
    })
    void personalGFloorForceAndReferencePoliciesAreIndependent(boolean suppressForce, boolean suppressReference,
                                                                boolean expectForce, boolean expectReference,
                                                                @TempDir Path directory) {
        boolean previousForce = UnigravConfig.personalGSuppressesFloorGravity;
        boolean previousReference = UnigravConfig.personalGSuppressesFloorReference;
        UnigravConfig.personalGSuppressesFloorGravity = suppressForce;
        UnigravConfig.personalGSuppressesFloorReference = suppressReference;
        try (var fixture = FloorSetup.open(directory)) {
            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                    fixture.sampler().sample(fixture.player(), yPlusState(fixture.player()), Optional.of(true)));

            assertTrue(sampled.personalZeroG(), "field eligibility must not change the supplied G intent");
            assertEquals(expectForce, sampled.evidence().gravityAcceleration().x() < 0.0,
                    "floor force follows its own personal-G switch");
            assertEquals(expectReference,
                    sampled.evidence().floorGravityStrengths().strength(CardinalFrame.X_POS) > 0.0,
                    "floor reference follows its own personal-G switch");
            var resolved = StandingConstraints.resolve(StandingConstraints.State.initial(),
                    new StandingConstraints.Request(sampled.worldTick(), false, sampled.evidence()));
            assertEquals(expectReference ? Optional.of(CardinalFrame.X_POS) : Optional.empty(),
                    resolved.targetReference());
            assertEquals(sampled.evidence().gravityAcceleration(),
                    resolved.authoritativeForce().orElseThrow().gravityAcceleration());
        } finally {
            UnigravConfig.personalGSuppressesFloorGravity = previousForce;
            UnigravConfig.personalGSuppressesFloorReference = previousReference;
            FloorWorldFields.clearAll();
        }
    }

    @Test
    void validPhysicsSampleAppliesOrdinaryFloorWithoutAnActiveGravityBinding(@TempDir Path directory) {
        try (var policy = FloorPolicy.set(false, false);
             var fixture = FloorSetup.open(directory)) {
            fixture.binding().close();
            fixture.player().setNoGravity(true);

            var sampled = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                    fixture.sampler().sample(fixture.player(), yPlusState(fixture.player()), Optional.of(false)));

            assertFalse(sampled.bindingActive());
            assertTrue(sampled.evidence().gravityAcceleration().x() < 0.0,
                    "floor eligibility comes from the valid actor/lifetime sample, not binding.active");
            assertEquals(Optional.of(CardinalFrame.X_POS),
                    StandingConstraints.resolve(StandingConstraints.State.initial(),
                            new StandingConstraints.Request(sampled.worldTick(), false, sampled.evidence()))
                            .targetReference());
        } finally {
            FloorWorldFields.clearAll();
        }
    }

    @Test
    void policyChangeDuringRealFieldSamplingRejectsTheMixedObservation(@TempDir Path directory) {
        try (var policy = FloorPolicy.set(false, false);
             var fixture = FloorSetup.open(directory);
             var binding = fixture.binder().bind(fixture.player(), GravityMode.REPLACE);
             var mutator = GravityFields.get().registerContribution("floor-auto-reference", "policy-mutator",
                     (position, time) -> {
                         UnigravConfig.personalGSuppressesFloorGravity = true;
                         return new Vec3(0, -32, 0);
                     })) {
            var result = fixture.sampler().sample(fixture.player(), yPlusState(fixture.player()), Optional.of(false));

            var unavailable = assertInstanceOf(PlayerEnvironmentSampler.Unavailable.class, result);
            assertEquals(PlayerEnvironmentSampler.Reason.CONFIG_CHANGED, unavailable.reason());
        } finally {
            FloorWorldFields.clearAll();
        }
    }

    private static PlayerPhysicalSnapshot yPlusState(EntityPlayerMP player) {
        var box = player.getEntityBoundingBox();
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
        var placement = PlayerBodyPlacement.fromCardinalBounds(new Aabb(
                        new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ)),
                new Vec3d(player.posX, player.posY, player.posZ), CardinalFrame.Y_POS.attitude(), shape);
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                new UUID(0, 81003), player.dimension, 1), 0, placement, new Vec3d(0, 0, 0));
    }

    private static Aabb actualBounds(EntityPlayerMP player) {
        var box = player.getEntityBoundingBox();
        return new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ));
    }

    private record FloorSetup(BootWorldFixture world, EntityPlayerMP player, GravityEntityBinder binder,
                              PlayerEnvironmentSampler sampler, GravityEntityBinder.Registration binding)
            implements AutoCloseable {
        static FloorSetup open(Path directory) {
            var world = BootWorldFixture.open(directory, new UUID(0, 81101), true);
            try {
                EntityPlayerMP player = world.serverPlayer(new UUID(0, 81102));
                player.setPosition(9.9, 63.6, 8.5);
                world.put(new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST));
                FloorWorldFields.onChunkLoad(new ChunkEvent.Load(world.world().getChunk(0, 0)));
                var binder = new GravityEntityBinder();
                var binding = binder.bind(player, GravityMode.REPLACE);
                return new FloorSetup(world, player, binder,
                        new PlayerEnvironmentSampler(binder, MagneticResponseRules.builtIn(), 0.5), binding);
            } catch (RuntimeException | Error failure) {
                try {
                    FloorWorldFields.clearAll();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                } finally {
                    world.close();
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                binding.close();
            } finally {
                try {
                    FloorWorldFields.clearAll();
                } finally {
                    world.close();
                }
            }
        }
    }

    private record FloorPolicy(boolean gravity, boolean reference) implements AutoCloseable {
        static FloorPolicy set(boolean gravity, boolean reference) {
            var previous = new FloorPolicy(UnigravConfig.personalGSuppressesFloorGravity,
                    UnigravConfig.personalGSuppressesFloorReference);
            UnigravConfig.personalGSuppressesFloorGravity = gravity;
            UnigravConfig.personalGSuppressesFloorReference = reference;
            return previous;
        }

        @Override
        public void close() {
            UnigravConfig.personalGSuppressesFloorGravity = gravity;
            UnigravConfig.personalGSuppressesFloorReference = reference;
        }
    }
}
