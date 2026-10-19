package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.field.GravityFields;
import wi.unigrav.api.field.GravityField;
import wi.unigrav.api.field.RegionGravityContribution;
import wi.unigrav.api.gravity.BodyGravitySource;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.ChunkGravityData;
import wi.unigrav.core.ChunkGravityManager;
import wi.unigrav.core.EntityGravitySources;
import wi.unigrav.core.RegionGravityData;
import wi.unigrav.core.RegionGravityManager;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.WorldGravityData;
import wi.unigrav.core.WorldGravityManager;
import wi.unigrav.core.WorldPositionGravity;
import wi.unigrav.core.config.FloorConfigLoader;
import wi.unigrav.core.config.GravitySourcesLoader;

/** Uses real mapped WorldServer, persisted managers, actual JSON loader and floor lifecycle. */
class WorldPositionGravityIntegrationTest {
    private static final Vec3 POINT = new Vec3(8.5, 65.5, 8.5);
    private static BootWorldFixture fixture(Path directory) {
        BootWorldFixture fixture = BootWorldFixture.open(directory, UUID.randomUUID(), true);
        emptyFloorWorld(fixture, fixture.world());
        return fixture;
    }
    private static void emptyFloorWorld(BootWorldFixture fixture, WorldServer world) {
        fixture.installColumn(world, 0, 0);
        FloorWorldFields.onChunkLoad(new ChunkEvent.Load(world.getChunk(0, 0)));
    }
    private static void floor(BootWorldFixture fixture, WorldServer world) {
        fixture.put(world, new BlockPos(8, 64, 8), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.UP));
        FloorWorldFields.onChunkLoad(new ChunkEvent.Load(world.getChunk(0, 0)));
    }
    private static WorldPositionGravity.Available available(WorldServer world) {
        WorldPositionGravity.Result result = WorldPositionGravity.sample(world, POINT);
        return assertInstanceOf(WorldPositionGravity.Available.class, result, () -> "actual=" + result);
    }
    @Test void managersAndPointSourceStackAtTheSamePositionAndCanCancelToLegalZero(@TempDir Path dir) {
        WorldGravityManager uniform = new WorldGravityManager();
        ChunkGravityManager chunks = new ChunkGravityManager();
        RegionGravityManager regions = new RegionGravityManager();
        try (var fixture = fixture(dir)) {
            WorldServer world = fixture.world();
            uniform.onWorldLoad(new WorldEvent.Load(world));
            chunks.onWorldLoad(new WorldEvent.Load(world));
            regions.onWorldLoad(new WorldEvent.Load(world));
            try {
                floor(fixture, world);
                uniform.setWorldGravity(world, new Vec3(0, 32, 0), true);
                chunks.set(world, 0, 0, new Vec3(4, 0, 0));
                regions.add(world, new RegionGravityContribution(new RegionGravityContribution.Box(
                        new Vec3(0, 0, 0), new Vec3(16, 100, 16)),
                        new RegionGravityContribution.Directional(new Vec3(-3, 0, 0))));
                world.setTotalWorldTime(120);
                try (var point = AmbientWorldFields.field(world).register("position-test", "point",
                        BodyGravitySource.fixed(1, POINT.add(new Vec3(-1, 0, 0))))) {
                    var result = available(world);
                    assertEquals(POINT, result.position());
                    assertEquals(6, result.timeSeconds());
                    assertEquals(Vec3.ZERO, result.acceleration());
                    assertTrue(result.isCurrent());
                    chunks.set(world, 0, 0, new Vec3(9, 0, 0));
                    assertFalse(result.isCurrent());
                    assertEquals(new Vec3(5, 0, 0), available(world).acceleration());
                }
            } finally {
                uniform.onWorldUnload(new WorldEvent.Unload(world));
                chunks.onWorldUnload(new WorldEvent.Unload(world));
                regions.onWorldUnload(new WorldEvent.Unload(world));
            }
        }
    }
    @Test void worldIdentityIsolatedAcrossSameDimensionAndOldOwnerRetirement(@TempDir Path dir) {
        try (var fixture = fixture(dir)) {
            WorldServer first = fixture.world();
            try (var one = AmbientWorldFields.field(first).registerContribution("position-test", "uniform",
                         new UniformGravityContribution(new Vec3(1, 0, 0)))) {
                var old = available(first);
                assertEquals(new Vec3(1, 0, 0), old.acceleration());
                WorldServer replacement = fixture.replaceWorld(0, dir.resolve("replacement"));
                emptyFloorWorld(fixture, replacement);
                try (var two = AmbientWorldFields.field(replacement).registerContribution("position-test", "uniform",
                             new UniformGravityContribution(new Vec3(2, 0, 0)))) {
                    assertNotSame(first, replacement);
                    assertEquals(first.provider.getDimension(), replacement.provider.getDimension());
                    assertFalse(old.isCurrent());
                    assertInstanceOf(WorldPositionGravity.Unavailable.class, WorldPositionGravity.sample(first, POINT));
                    assertEquals(new Vec3(2, 0, 0), available(replacement).acceleration());
                    one.close();
                    assertEquals(new Vec3(2, 0, 0), available(replacement).acceleration(),
                            "retired registration cannot remove a same-id source owned by the new actual World");
                    AmbientWorldFields.load(first);
                    assertFalse(old.isCurrent(), "reloading the same object must not revive its old owner fence");
                    AmbientWorldFields.retire(first);
                }
            }
        }
    }
    @Test void sharedMapStorageStillUsesDimensionSpecificPersistedRecords(@TempDir Path dir) {
        try (var fixture = fixture(dir)) {
            WorldServer first = fixture.world();
            WorldServer second = fixture.additionalWorld(1, dir.resolve("dimension1"));
            emptyFloorWorld(fixture, second);
            fixture.shareMapStorage(second, first);
            assertSame(first.getMapStorage(), second.getMapStorage());
            assertNotSame(WorldGravityData.get(first), WorldGravityData.get(second));
            assertNotSame(ChunkGravityData.get(first), ChunkGravityData.get(second));
            assertNotSame(RegionGravityData.get(first), RegionGravityData.get(second));
            WorldGravityData.get(first).setAcceleration(new Vec3(11, 12, 13));
            assertNotEquals(new Vec3(11, 12, 13), WorldGravityData.get(second).getAcceleration());
            ChunkGravityData.get(first).set(0, 0, new Vec3(14, 15, 16));
            assertNull(ChunkGravityData.get(second).get(0, 0));
            assertEquals(WorldGravityData.DATA_NAME, WorldGravityData.get(first).mapName);
            assertEquals(WorldGravityData.DATA_NAME + "_dimension_1", WorldGravityData.get(second).mapName);
        }
    }
    @Test void absentJsonDimensionAppliesToEveryWorldAndOptionalSelectorFilters(@TempDir Path dir) throws Exception {
        GravitySourcesLoader loader = new GravitySourcesLoader();
        try (var fixture = fixture(dir.resolve("world"))) {
            WorldServer first = fixture.world();
            WorldServer second = fixture.additionalWorld(1, dir.resolve("dimension1"));
            emptyFloorWorld(fixture, second);
            WorldServer nether = fixture.additionalWorld(-1, dir.resolve("nether"));
            emptyFloorWorld(fixture, nether);
            Path sources = Files.createDirectories(dir.resolve("sources"));
            Files.writeString(sources.resolve("shared.json"), "{\"id\":\"position-test:shared\",\"mu\":1,\"kind\":\"static\",\"position\":[7.5,65.5,8.5]}");
            Files.writeString(sources.resolve("selected.json"), "{\"id\":\"position-test:selected\",\"dimension\":1,\"mu\":2,\"kind\":\"static\",\"position\":[7.5,65.5,8.5]}");
            Files.writeString(sources.resolve("nether.json"), "{\"id\":\"position-test:nether\",\"dimension\":-1,\"mu\":3,\"kind\":\"static\",\"position\":[7.5,65.5,8.5]}");
            Files.writeString(sources.resolve("fraction.json"), "{\"id\":\"position-test:fraction\",\"dimension\":1.5,\"mu\":99,\"kind\":\"static\",\"position\":[7.5,65.5,8.5]}");
            Files.writeString(sources.resolve("overflow.json"), "{\"id\":\"position-test:overflow\",\"dimension\":2147483648,\"mu\":99,\"kind\":\"static\",\"position\":[7.5,65.5,8.5]}");
            try {
                assertEquals(3, loader.loadAll(sources.toFile()), "fractional and overflowing dimension selectors are rejected");
                assertFalse(GravityFields.get().snapshot().containsKey(new GravityField.Id("position-test", "fraction")));
                assertFalse(GravityFields.get().snapshot().containsKey(new GravityField.Id("position-test", "overflow")));
                var old = available(first);
                assertEquals(new Vec3(-1, 0, 0), old.acceleration());
                assertEquals(new Vec3(-3, 0, 0), available(second).acceleration());
                assertEquals(new Vec3(-4, 0, 0), available(nether).acceleration());
                loader.unloadAll();
                assertFalse(old.isCurrent());
                assertEquals(Vec3.ZERO, available(first).acceleration());
            } finally { loader.unloadAll(); }
        }
    }
    @Test void mutationDuringSourceCallbackCannotProduceAnAvailableResult(@TempDir Path dir) {
        try (var fixture = fixture(dir)) {
            var world = fixture.world();
            AtomicBoolean once = new AtomicBoolean();
            try (var callback = AmbientWorldFields.field(world).registerContribution("position-test", "callback", (p,t) -> {
                if (once.compareAndSet(false, true)) AmbientWorldFields.field(world).registerContribution(
                        "position-test", "reentrant", new UniformGravityContribution(new Vec3(3, 0, 0)));
                return new Vec3(1, 0, 0);
            })) {
                assertInstanceOf(WorldPositionGravity.Unavailable.class, WorldPositionGravity.sample(world, POINT));
                assertEquals(new Vec3(4, 0, 0), available(world).acceleration());
            }
        }
    }
    @Test void floorReloadDuringAmbientCallbackInvalidatesTheCapturedDecision(@TempDir Path dir) throws Exception {
        try (var fixture = BootWorldFixture.open(dir.resolve("world"), UUID.randomUUID(), true)) {
            var world = fixture.world();
            fixture.publishWorldRoster(world);
            Path config = FloorConfigLoader.ensureDefault(dir.resolve("config"));
            FloorWorldFields.start(fixture.server(), FloorConfigLoader.compile(FloorConfigLoader.parse(config), 1));
            AtomicBoolean once = new AtomicBoolean();
            try (var callback = AmbientWorldFields.field(world).registerContribution("position-test", "callback", (p,t) -> {
                if (once.compareAndSet(false, true)) FloorWorldFields.currentRuntime().reload(config);
                return Vec3.ZERO;
            })) {
                assertInstanceOf(WorldPositionGravity.Unavailable.class, WorldPositionGravity.sample(world, POINT));
                assertTrue(available(world).isCurrent());
            }
        }
    }
    @Test void originalSourceFailureAndSuppressedEvidenceArePreserved(@TempDir Path dir) {
        try (var fixture = fixture(dir)) {
            IllegalStateException failure = new IllegalStateException("source failed", new RuntimeException("root"));
            failure.addSuppressed(new RuntimeException("cleanup"));
            try (var callback = GravityFields.get().registerContribution("position-test", "failure", (p,t) -> { throw failure; })) {
                var result = assertInstanceOf(WorldPositionGravity.Failed.class,
                        WorldPositionGravity.sample(fixture.world(), POINT));
                assertSame(failure, result.cause());
                assertSame(failure.getCause(), result.cause().getCause());
                assertSame(failure.getSuppressed()[0], result.cause().getSuppressed()[0]);
            }
        }
    }
    @Test void unavailableFloorAndServerRetirementAreNotLegalZero(@TempDir Path dir) {
        try (var fixture = fixture(dir)) {
            var world = fixture.world();
            var result = available(world);
            world.captureBlockSnapshots = true;
            assertFalse(result.isCurrent());
            assertInstanceOf(WorldPositionGravity.Unavailable.class, WorldPositionGravity.sample(world, POINT));
            world.captureBlockSnapshots = false;
            world.setTotalWorldTime(world.getTotalWorldTime() + 1);
            assertFalse(result.isCurrent());
            AmbientWorldFields.stop(fixture.server());
            assertInstanceOf(WorldPositionGravity.Unavailable.class, WorldPositionGravity.sample(world, POINT));
        }
    }
    @Test void entitySourcesStayInTheirWorldAndMovingSourceInvalidatesOldFence(@TempDir Path dir) {
        double threshold = UnigravConfig.entityGravityThreshold;
        try (var fixture = fixture(dir)) {
            var first = fixture.world();
            var second = fixture.additionalWorld(1, dir.resolve("dimension1"));
            emptyFloorWorld(fixture, second);
            var entity = fixture.serverPlayer(UUID.randomUUID());
            entity.setPosition(7.5, 65.5 - entity.height * 0.5, 8.5);
            UnigravConfig.entityGravityThreshold = 1;
            EntityGravitySources.update(entity, new BodyPhysics(1 / EntityGravitySources.G_SI, 1));
            try {
                var old = available(first);
                assertTrue(old.acceleration().x() < 0);
                assertEquals(Vec3.ZERO, available(second).acceleration());
                assertEquals(Vec3.ZERO, EntityGravitySources.fieldAcceleration(entity, POINT, 0), "self is excluded");
                entity.setPosition(entity.posX - 1, entity.posY, entity.posZ);
                assertFalse(old.isCurrent());
                assertTrue(available(first).isCurrent());
                EntityGravitySources.onWorldUnload(new WorldEvent.Unload(first));
                assertEquals(Vec3.ZERO, available(first).acceleration());
            } finally { EntityGravitySources.remove(entity); }
        } finally { UnigravConfig.entityGravityThreshold = threshold; }
    }
}
