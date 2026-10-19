package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.config.FloorConfigLoader;
import wi.unigrav.core.config.FloorConfigLoader.CompiledFloorConfig;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.floor.ActiveBootRules.QualifiedBoot;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints;
import wi.unigrav.core.player.StandingConstraints.BootTier;

class FloorConfigGenerationIntegrationTest {
    private static final BlockPos LOW_FACE = new BlockPos(0, 0, 10);

    @BeforeAll
    static void bootstrap() {
        BootWorldFixture.bootstrapForTests();
    }

    @Test
    void packagedJsonResolvesRealMetadataNbtAndEquippedTierReach(@TempDir Path directory) throws Exception {
        CompiledFloorConfig config = load(directory, 1L, source -> source);
        var catalog = config.floorProfiles();
        assertEquals(16.0, catalog.resolveStored(FloorBlocks.GRAVITY_FLOOR.getDefaultState()).orElseThrow()
                .profile().strength());
        assertEquals(64.0, catalog.resolveStored(FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, EnumFacing.NORTH)).orElseThrow()
                .profile().strength());
        assertEquals(32.0, catalog.resolveStored(FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST)).orElseThrow()
                .profile().strength(), "the floor-facing metadata bits remain independent of profile strength");

        ItemStack namedIron = new ItemStack(Items.IRON_BOOTS);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", "Magnetic Low");
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("display", display);
        namedIron.setTagCompound(tag);
        QualifiedBoot low = assertInstanceOf(QualifiedBoot.class,
                ActiveBootRules.qualifySnapshot(namedIron, false, false, config.bootRules()));
        QualifiedBoot standard = assertInstanceOf(QualifiedBoot.class,
                ActiveBootRules.qualifySnapshot(new ItemStack(Items.IRON_BOOTS), false, false, config.bootRules()));
        QualifiedBoot high = assertInstanceOf(QualifiedBoot.class,
                ActiveBootRules.qualifySnapshot(new ItemStack(Items.DIAMOND_BOOTS), false, false, config.bootRules()));
        assertEquals(1.0, low.tier().captureReachMetres());
        assertEquals(1.5, standard.tier().captureReachMetres());
        assertEquals(2.5, high.tier().captureReachMetres());
        Map<String, BootTier> lowMaterials = config.bootRules().effectiveTiers(low);
        assertEquals(16.0, lowMaterials.get("iron_block").profile().strength());
        assertEquals(64.0, lowMaterials.get("lit_furnace_core").profile().strength(),
                "the configured furnace tier override remains explicit for a low-tier wearer");
        assertEquals(2.5, config.bootRules().maximumCaptureReachMetres(low));
        assertEquals(EntityEquipmentSlot.FEET, config.bootRules().equipmentRules().getFirst().slot());
    }

    @Test
    void lateInitializationAndTwoWorldReloadKeepTheWholeGenerationAtomic(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("worlds"), new UUID(0, 801))) {
            WorldServer overworld = fixture.world();
            WorldServer second = fixture.additionalWorld(1, directory.resolve("dimension-one"));
            fixture.installColumn(overworld, 0, 0);
            fixture.installColumn(overworld, 1, 0);
            fixture.installColumn(second, 0, 0);
            fixture.installColumn(second, 1, 0);
            fixture.put(overworld, LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.put(overworld, new BlockPos(2, 0, 0), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.put(overworld, new BlockPos(16, 0, 10), FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.NORTH));
            fixture.put(second, new BlockPos(0, 0, 0), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.put(second, new BlockPos(16, 0, 0), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.publishWorldRoster(overworld, second);

            CompiledFloorConfig initial = load(directory.resolve("config"), 1L, source -> source);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
            FloorRuntime runtime = FloorWorldFields.currentRuntime();
            ActiveFloorGeneration before = runtime.generation();
            assertEquals(2, before.worlds().size());
            assertEquals(4, chunkCount(before));
            assertEquals(new Vec3(0, -16, 0), before.fields(overworld)
                    .sample(new Vec3(2.5, 1, 0.5), 0, false).orElseThrow().gravityAccel());
            assertEquals(new Vec3(0, 0, 64), before.fields(overworld)
                    .sample(new Vec3(16.5, 0.5, 9.5), 0, false).orElseThrow().gravityAccel());

            FloorDecision decision = FloorWorldFields.captureDecision(overworld).orElseThrow();
            var editedChunk = overworld.getChunkProvider().getLoadedChunk(0, 0);
            editedChunk.setBlockState(LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                    .withProperty(DirectionalFloorBlock.FACING, EnumFacing.NORTH));
            FloorWorldFields.markDirty(editedChunk, LOW_FACE);
            assertFalse(decision.isCurrent(), "a real dirty epoch invalidates the sampler's captured root even before reread");
            FloorDecision dirtyDecision = FloorWorldFields.captureDecision(overworld).orElseThrow();
            assertEquals(new Vec3(0, 0, 64), dirtyDecision.sample(new Vec3(0.5, 0.5, 9.5), 0).orElseThrow().gravityAccel(),
                    "dirty reread resolves the same ordered metadata/state selector as initial scan");

            CompiledFloorConfig next = load(directory.resolve("config"), 2L,
                    source -> source.replace("\"low\": { \"strength\": 16.0", "\"low\": { \"strength\": 24.0"));
            FloorRuntime.PreparedFloorReload prepared = runtime.prepareReload(next);
            FloorRuntime.ReloadReceipt receipt = runtime.commitReload(prepared);
            ActiveFloorGeneration published = runtime.generation();
            assertEquals(new FloorRuntime.ReloadReceipt(2L, 2, 4), receipt);
            assertNotSame(before, published);
            assertEquals(new Vec3(0, -24, 0), published.fields(overworld)
                    .sample(new Vec3(2.5, 1, 0.5), 0, false).orElseThrow().gravityAccel(),
                    "the reloaded selector catalog is active on every loaded world");
            assertFalse(dirtyDecision.isCurrent(), "one-root publication invalidates earlier floor decisions");

            ActiveFloorGeneration stableRoot = runtime.generation();
            FloorWorldFields.WorldFields stableOverworld = stableRoot.fields(overworld);
            FloorWorldFields.WorldFields stableSecond = stableRoot.fields(second);
            fixture.replaceChunkWithInvalidStorage(second, 1, 0);
            FloorRuntime.ReloadFailure failedWorld = assertThrows(FloorRuntime.ReloadFailure.class,
                    () -> runtime.prepareReload(load(directory.resolve("config"), 3L, source -> source)));
            assertTrue(failedWorld.stage().startsWith("prepare-world:"));
            assertInstanceOf(IllegalStateException.class, failedWorld.getCause());
            assertSame(stableRoot, runtime.generation(), "a later-world scanner failure cannot partially publish earlier worlds");
            assertSame(stableOverworld, runtime.generation().fields(overworld));
            assertSame(stableSecond, runtime.generation().fields(second));
            assertSame(stableRoot.profiles(), runtime.generation().profiles());
            assertSame(stableRoot.bootRules(), runtime.generation().bootRules());
            assertSame(stableRoot.passiveResponses(), runtime.generation().passiveResponses());
            assertEquals(new Vec3(0, -24, 0), stableOverworld.sample(new Vec3(2.5, 1, 0.5), 0, false)
                    .orElseThrow().gravityAccel(), "the previous all-world profile remains queryable after prepare abort");
            assertEquals(new Vec3(0, -24, 0), stableSecond.sample(new Vec3(16.5, 1, 0.5), 0, false)
                    .orElseThrow().gravityAccel(), "the later world's old content also remains queryable after prepare abort");

            Chunk bad = second.getChunkProvider().getLoadedChunk(1, 0);
            FloorWorldFields.onChunkUnload(new ChunkEvent.Unload(bad));
            fixture.removeColumn(second, 1, 0);
            fixture.installColumn(second, 1, 0);
            fixture.put(second, new BlockPos(16, 0, 0), FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            Chunk replacement = second.getChunkProvider().getLoadedChunk(1, 0);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(replacement));
            FloorWorldFields.onChunkUnload(new ChunkEvent.Unload(bad));
            assertEquals(new Vec3(0, -24, 0), runtime.generation().fields(second)
                    .sample(new Vec3(16.5, 1, 0.5), 0, false).orElseThrow().gravityAccel(),
                    "a late unload from the previous chunk owner cannot erase its replacement");

            FloorRuntime.PreparedFloorReload deferredCandidate = runtime.prepareReload(
                    load(directory.resolve("config"), 4L, source -> source));
            second.captureBlockSnapshots = true;
            FloorRuntime.ReloadFailure deferredFailure = assertThrows(FloorRuntime.ReloadFailure.class,
                    () -> runtime.commitReload(deferredCandidate));
            second.captureBlockSnapshots = false;
            assertEquals("commit-fence", deferredFailure.stage());
            assertSame(stableRoot, runtime.generation(), "a deferred flag without any lifecycle epoch still rejects publication");
            assertSame(stableOverworld, runtime.generation().fields(overworld));
            assertSame(stableSecond, runtime.generation().fields(second));

            assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            var lateChunk = overworld.getChunkProvider().getLoadedChunk(0, 0);
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(lateChunk));
            FloorWorldFields.onWorldTick(new TickEvent.WorldTickEvent(Side.SERVER, TickEvent.Phase.END, overworld));
            assertTrue(FloorWorldFields.captureDecision(overworld).isEmpty(),
                    "old owner events after exact stop cannot create a local-test floor owner");
            assertNull(FloorWorldFields.currentRuntime());
        }
    }

    @Test
    void commitRosterMutationRejectsCandidateAndKeepsTheOldPendingDirtyRoot(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("world"), new UUID(0, 880))) {
            WorldServer world = fixture.world();
            fixture.installColumn(world, 0, 0);
            fixture.put(world, LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.publishWorldRoster(world);

            CompiledFloorConfig initial = load(directory.resolve("config"), 1L, source -> source);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
            try {
                FloorRuntime runtime = FloorWorldFields.currentRuntime();
                ActiveFloorGeneration oldRoot = runtime.generation();
                FloorWorldFields.WorldFields oldFields = oldRoot.fields(world);
                FloorProfileCatalog oldProfiles = oldRoot.profiles();
                ActiveBootRules.Snapshot oldBootRules = oldRoot.bootRules();
                MagneticResponseRules oldPassiveResponses = oldRoot.passiveResponses();
                Chunk chunk = world.getChunkProvider().getLoadedChunk(0, 0);
                FloorDecision oldDecision = FloorWorldFields.captureDecision(world).orElseThrow();

                CompiledFloorConfig next = load(directory.resolve("config"), 2L, source -> {
                    String changed = source.replace("\"high\": { \"strength\": 64.0",
                            "\"high\": { \"strength\": 96.0");
                    if (changed.equals(source)) throw new AssertionError("packaged high-profile selector anchor is missing");
                    return changed;
                });
                assertNotSame(oldProfiles, next.floorProfiles());
                assertNotSame(oldBootRules, next.bootRules());
                assertNotSame(oldPassiveResponses, next.passiveResponses());
                var northFace = FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                        .withProperty(DirectionalFloorBlock.FACING, EnumFacing.NORTH);
                assertEquals(96.0, next.floorProfiles().resolveStored(northFace).orElseThrow().profile().strength(),
                        "the rejected candidate carries a distinguishable replacement selector");

                FloorRuntime.PreparedFloorReload prepared = runtime.prepareReload(next);
                assertEquals(2L, prepared.revision());
                assertEquals(1, prepared.chunkCount(), "the detached candidate owns its scanned loaded chunk before commit");

                AtomicBoolean callbackReached = new AtomicBoolean();
                fixture.onNextServerLookup(world, () -> {
                    callbackReached.set(true);
                    chunk.setBlockState(LOW_FACE, northFace);
                    FloorWorldFields.markDirty(chunk, LOW_FACE);
                });
                FloorRuntime.ReloadFailure rejected = assertThrows(FloorRuntime.ReloadFailure.class,
                        () -> runtime.commitReload(prepared));

                assertEquals("commit-fence", rejected.stage());
                assertTrue(callbackReached.get(), "the real world-owner lookup during commit roster reobservation mutates the loaded chunk");
                assertEquals(northFace, chunk.getBlockState(LOW_FACE));
                assertSame(oldRoot, runtime.generation(), "the dirty callback must not publish the detached candidate root");
                assertSame(oldFields, runtime.generation().fields(world));
                assertSame(oldProfiles, runtime.generation().profiles());
                assertSame(oldBootRules, runtime.generation().bootRules());
                assertSame(oldPassiveResponses, runtime.generation().passiveResponses());
                assertEquals(1L, runtime.generation().configurationRevision());
                assertEquals(0, prepared.chunkCount(), "rejected candidate retirement releases its scanned chunk fields");
                assertTrue(oldFields.hasPending(), "the old root keeps the callback dirtiness pending after candidate rejection");
                assertFalse(oldDecision.isCurrent(), "the old sampled decision is fenced by the real block mutation");

                FloorDecision refreshedOldRoot = FloorWorldFields.captureDecision(world).orElseThrow();
                assertEquals(1L, refreshedOldRoot.configurationRevision());
                assertSame(oldBootRules, refreshedOldRoot.bootRules());
                assertSame(oldPassiveResponses, refreshedOldRoot.passiveResponses());
                assertFalse(oldFields.hasPending(), "the old root flushes its original pending dirty reread when queried");
                assertEquals(new Vec3(0, 0, 64), refreshedOldRoot.sample(new Vec3(0.5, 0.5, 9.5), 0)
                        .orElseThrow().gravityAccel(),
                        "the old high-profile selector observes the changed north-facing state instead of the rejected 96 m/s² profile");
            } finally {
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    @Test
    void worldLoadPublishesNewOwnedWorldOnTheCurrentRoot(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("worlds"), new UUID(0, 805))) {
            WorldServer primary = fixture.world();
            WorldServer arriving = fixture.additionalWorld(1, directory.resolve("arriving-world"));
            fixture.installColumn(primary, 0, 0);
            fixture.put(primary, LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.installColumn(arriving, 0, 0);
            fixture.put(arriving, LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.publishWorldRoster(primary);

            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(),
                    load(directory.resolve("config"), 1L, source -> source));
            FloorRuntime runtime = FloorWorldFields.currentRuntime();
            ActiveFloorGeneration beforeWorldLoad = runtime.generation();
            assertNull(beforeWorldLoad.fields(arriving), "a non-rostered world is not scanned during startup");

            fixture.publishWorldRoster(primary, arriving);
            FloorWorldFields.onWorldLoad(new WorldEvent.Load(arriving));
            ActiveFloorGeneration afterWorldLoad = runtime.generation();
            assertNotSame(beforeWorldLoad, afterWorldLoad);
            assertEquals(2, afterWorldLoad.worlds().size());
            assertEquals(new Vec3(0, -16, 0), FloorWorldFields.captureDecision(arriving).orElseThrow()
                    .sample(new Vec3(0.5, 1, 10.5), 0.0).orElseThrow().gravityAccel(),
                    "the exact lifetime's WorldEvent.Load path publishes its current loaded chunk and selector");
            assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
        }
    }

    @Test
    void emptyStartupReentrantStartupAndGetterReplacementFailClosed(@TempDir Path directory) throws Exception {
        CompiledFloorConfig initial = load(directory.resolve("empty-config"), 1L, source -> source);
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("empty-world"), new UUID(0, 811))) {
            fixture.publishWorldRoster();
            assertTrue(fixture.server().worlds.length == 0);
            ServerLifetime emptyLifetime = FloorWorldFields.start(fixture.server(), initial);
            assertTrue(FloorWorldFields.currentRuntime().generation().worlds().isEmpty(),
                    "normal about-to-start with no loaded worlds publishes an empty root");
            assertTrue(FloorWorldFields.stop(fixture.server(), emptyLifetime));
        }

        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("reentrant-world"), new UUID(0, 812))) {
            WorldServer world = fixture.world();
            fixture.installColumn(world, 0, 0);
            fixture.put(world, LOW_FACE, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
            fixture.publishWorldRoster(world);
            var chunk = world.getChunkProvider().getLoadedChunk(0, 0);
            fixture.onNextChunkStorageRead(world, 0, 0,
                    () -> FloorWorldFields.onChunkLoad(new ChunkEvent.Load(chunk)));
            FloorRuntime.ReloadFailure reentrantStart = assertThrows(FloorRuntime.ReloadFailure.class,
                    () -> FloorWorldFields.start(fixture.server(), initial));
            assertInstanceOf(IllegalStateException.class, reentrantStart.getCause());
            assertNull(FloorWorldFields.currentRuntime());
            assertTrue(FloorWorldFields.captureDecision(world).isEmpty(),
                    "scan-time lifecycle reentry cannot fall through into local compatibility ownership");

            fixture.onNextChunkStorageRead(world, 0, 0,
                    () -> FloorWorldFields.markDirty(chunk, LOW_FACE));
            FloorRuntime.ReloadFailure dirtyDuringStart = assertThrows(FloorRuntime.ReloadFailure.class,
                    () -> FloorWorldFields.start(fixture.server(), initial));
            assertInstanceOf(IllegalStateException.class, dirtyDuringStart.getCause());
            assertNull(FloorWorldFields.currentRuntime());
            assertTrue(FloorWorldFields.captureDecision(world).isEmpty(),
                    "a dirty callback during late initialization fails the candidate instead of being lost in local fallback");

            ServerLifetime oldLifetime = FloorWorldFields.start(fixture.server(), initial);
            FloorRuntime oldRuntime = FloorWorldFields.currentRuntime();
            FloorDecision oldDecision = FloorWorldFields.captureDecision(world).orElseThrow();
            var replacement = load(directory.resolve("empty-config"), 2L, source -> source);
            fixture.onNextServerLookup(world, () -> {
                assertTrue(FloorWorldFields.stop(fixture.server(), oldLifetime));
                FloorWorldFields.start(fixture.server(), replacement);
            });
            assertFalse(oldDecision.isCurrent(), "virtual owner lookup may stop and replace the runtime; final identity fence must reject old evidence");
            assertTrue(oldDecision.sample(new Vec3(0.5, 0.5, 9.5), 0.0).isEmpty(),
                    "a floor query already captured under the old lifetime cannot survive reentrant replacement");
            ServerLifetime newLifetime = FloorWorldFields.currentRuntime().lifetime();
            assertNotSame(oldRuntime, FloorWorldFields.currentRuntime());
            assertFalse(FloorWorldFields.stop(fixture.server(), oldLifetime),
                    "an old lifetime stop request cannot clear the replacement runtime");
            assertSame(newLifetime, FloorWorldFields.currentRuntime().lifetime());
            assertTrue(FloorWorldFields.stop(fixture.server(), newLifetime));
            assertTrue(FloorWorldFields.captureDecision(world).isEmpty());
        }
    }

    @Test
    void productionSamplerUsesPassiveRulesFromTheCapturedGeneration(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("sampler-world"), new UUID(0, 821))) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 822));
            player.setPosition(8.5, 65, 8.5);
            player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            fixture.put(new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
            fixture.publishWorldRoster(fixture.world());

            Path file = FloorConfigLoader.ensureDefault(directory.resolve("sampler-config"));
            CompiledFloorConfig initial = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
            FloorRuntime runtime = FloorWorldFields.currentRuntime();
            GravityEntityBinder binder = new GravityEntityBinder();
            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                binder.sweepEntity(player);
                PlayerEnvironmentSampler sampler = new PlayerEnvironmentSampler(binder,
                        MagneticResponseRules.builtIn(), 0.5);
                PlayerPhysicalSnapshot accepted = physicalState(player);
                PlayerEnvironmentSampler.Sample first = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, accepted, java.util.Optional.of(false)));
                assertEquals(1.0, first.magneticResponse());
                assertEquals(new Vec3(0, -32, 0), first.evidence().magneticAcceleration());

                FloorDecision pinned = FloorWorldFields.captureDecision(fixture.world()).orElseThrow();
                String json = Files.readString(file);
                String updated = json.replace("\"item\": \"minecraft:iron_boots\", \"response\": 1.0",
                        "\"item\": \"minecraft:iron_boots\", \"response\": 0.25");
                assertFalse(updated.equals(json), "the example's actual passive boots rule must be edited");
                Files.writeString(file, updated);
                FloorRuntime.ReloadReceipt receipt = runtime.reload(file);
                assertEquals(2L, receipt.revision());
                assertFalse(pinned.isCurrent());
                assertTrue(pinned.sample(new Vec3(8.5, 65.9, 8.5), 1.0).isEmpty(),
                        "a decision captured during the old generation becomes stale after root publication");

                PlayerEnvironmentSampler.Sample second = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, accepted, java.util.Optional.of(false)));
                assertEquals(0.25, second.magneticResponse(),
                        "production sampler uses the captured generation, not the constructor's built-in response");
                assertEquals(new Vec3(0, -8, 0), second.evidence().magneticAcceleration());
            } finally {
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    @Test
    void packagedBootNbtChangesRealCaptureTierForceAndReach(@TempDir Path directory) throws Exception {
        for (boolean distantSurface : new boolean[] { false, true }) {
            String scenario = distantSurface ? "reach" : "force";
            try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve(scenario + "-world"),
                    new UUID(0, distantSurface ? 861 : 860))) {
                EntityPlayerMP player = fixture.serverPlayer(new UUID(0, distantSurface ? 863 : 862));
                player.setPosition(8.5, 65, 8.5);
                player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                BlockPos surface = new BlockPos(8, 64, distantSurface ? 10 : 8);
                fixture.installColumn(fixture.world(), 0, 0);
                fixture.put(fixture.world(), surface, Blocks.IRON_BLOCK.getDefaultState());
                fixture.publishWorldRoster(fixture.world());

                Path file = configureBootsWithoutMaterialOverrides(directory.resolve(scenario + "-config"));
                CompiledFloorConfig config = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
                assertTrue(config.bootRules().explicitMaterialTierOverrides().isEmpty(),
                        "the iron-block surface must inherit the actual equipped tier, not a material override");
                ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), config);
                GravityEntityBinder binder = new GravityEntityBinder();
                try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                    binder.sweepEntity(player);
                    PlayerEnvironmentSampler sampler = new PlayerEnvironmentSampler(binder,
                            MagneticResponseRules.builtIn(), 0.5, ActiveBootRules.core());

                    PlayerEnvironmentSampler.Sample standard = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                            sampler.sample(player, physicalState(player), java.util.Optional.of(false)));
                    StandingConstraints.BootCandidate standardCandidate = candidateAt(standard, surface);
                    assertEquals(1L, standardCandidate.surface().identity().orElseThrow().rulesRevision());
                    assertEquals(32.0, standardCandidate.tier().profile().strength());
                    assertEquals(1.5, standardCandidate.tier().captureReachMetres());
                    if (distantSurface) {
                        double expectedGap = surface.getZ() - (player.posZ + (double) player.width / 2.0);
                        assertEquals(expectedGap, standardCandidate.gap().lowerMetres(), 1.0e-12,
                                "the captured gap follows the real mapped player collision width");
                        assertTrue(standardCandidate.gap().lowerMetres() > 1.0
                                        && standardCandidate.gap().lowerMetres() <= standardCandidate.tier()
                                                .captureReachMetres(),
                                "the captured iron-block top face is outside low-tier reach but inside standard reach");
                        assertEquals(StandingConstraints.ConstraintPresence.PRESENT,
                                resolveBoots(standard).presence());
                        assertTrue(standardCandidate.pullMagnitude() > 0.0,
                                "the standard FEET tier produces a real finite-query force");
                        Vec3 expected = new Vec3(
                                standardCandidate.pullDirection().x() * standardCandidate.pullMagnitude(),
                                standardCandidate.pullDirection().y() * standardCandidate.pullMagnitude(),
                                standardCandidate.pullDirection().z() * standardCandidate.pullMagnitude());
                        assertEquals(expected, resolveBoots(standard).authoritativeForce().orElseThrow().bootAcceleration());
                    } else {
                        assertEquals(0.0, standardCandidate.gap().upperMetres());
                        assertEquals(32.0, standardCandidate.pullMagnitude());
                        assertEquals(new Vec3(0, -32, 0),
                                resolveBoots(standard).authoritativeForce().orElseThrow().bootAcceleration());
                    }

                    player.setItemStackToSlot(EntityEquipmentSlot.FEET, namedIronBoots("Magnetic Low"));
                    PlayerEnvironmentSampler.Sample low = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                            sampler.sample(player, physicalState(player), java.util.Optional.of(false)));
                    boolean lowTierCanReachSurface = low.evidence().bootCandidates().stream().anyMatch(candidate ->
                            candidate.surface().identity().orElseThrow().primaryContributorCell().equals(surface));
                    assertEquals(!distantSurface, lowTierCanReachSurface,
                            "only the standard tier can reach the distant iron-block surface");
                    if (distantSurface) {
                        assertEquals(new Vec3(0, 0, 0),
                                resolveBoots(low).authoritativeForce().orElseThrow().bootAcceleration());
                        assertTrue(low.evidence().bootCandidates().isEmpty(),
                                "the low-tier capture footprint does not admit the farther real surface");
                    } else {
                        StandingConstraints.BootCandidate lowCandidate = candidateAt(low, surface);
                        assertEquals(1L, lowCandidate.surface().identity().orElseThrow().rulesRevision());
                        assertEquals(16.0, lowCandidate.tier().profile().strength());
                        assertEquals(1.0, lowCandidate.tier().captureReachMetres());
                        assertEquals(16.0, lowCandidate.pullMagnitude());
                        assertEquals(new Vec3(0, -16, 0),
                                resolveBoots(low).authoritativeForce().orElseThrow().bootAcceleration());
                    }
                } finally {
                    assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
                }
            }
        }
    }

    @Test
    void activeCaptureRejectsAReentrantJsonReloadAndNextSampleUsesNewRevision(@TempDir Path directory)
            throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("world"), new UUID(0, 871))) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 872));
            player.setPosition(8.5, 65, 8.5);
            player.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            fixture.installColumn(fixture.world(), 0, 0);
            fixture.put(fixture.world(), new BlockPos(8, 64, 8), Blocks.IRON_BLOCK.getDefaultState());
            fixture.put(fixture.world(), new BlockPos(10, 64, 8), Blocks.OAK_STAIRS.getDefaultState());
            fixture.publishWorldRoster(fixture.world());

            Path file = configureBootsWithoutMaterialOverrides(directory.resolve("config"));
            CompiledFloorConfig config = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), config);
            FloorRuntime runtime = FloorWorldFields.currentRuntime();
            GravityEntityBinder binder = new GravityEntityBinder();
            try (var binding = binder.bind(player, GravityMode.REPLACE)) {
                binder.sweepEntity(player);
                PlayerEnvironmentSampler sampler = new PlayerEnvironmentSampler(binder,
                        MagneticResponseRules.builtIn(), 0.5, ActiveBootRules.core());
                FloorDecision oldDecision = FloorWorldFields.captureDecision(fixture.world()).orElseThrow();

                PlayerEnvironmentSampler.Sample beforeReload = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, physicalState(player), java.util.Optional.of(false)));
                StandingConstraints.BootCandidate oldCandidate = candidateAt(beforeReload, new BlockPos(8, 64, 8));
                var oldIdentity = oldCandidate.surface().identity().orElseThrow();
                StandingConstraints.Resolution beforeResolution = resolveBoots(beforeReload);
                assertEquals(oldIdentity, beforeResolution.nextState().bootMemory().orElseThrow().surface()
                        .identity().orElseThrow());

                AtomicBoolean callbackReached = new AtomicBoolean();
                AtomicBoolean callbackWasInsideNativeCapture = new AtomicBoolean();
                AtomicReference<FloorRuntime.ReloadReceipt> reload = new AtomicReference<>();
                fixture.onLoadedChunkReadWhen(() -> insideNativeStairCaptureCallback(), () -> {
                    callbackReached.set(true);
                    callbackWasInsideNativeCapture.set(insideNativeStairCaptureCallback());
                    reload.set(runtime.reload(file));
                });

                PlayerEnvironmentSampler.Result duringReload =
                        sampler.sample(player, physicalState(player), java.util.Optional.of(false));
                PlayerEnvironmentSampler.Unavailable stale = assertInstanceOf(PlayerEnvironmentSampler.Unavailable.class,
                        duringReload, "a capture pinned before the reentrant generation swap must not publish evidence");
                assertEquals(PlayerEnvironmentSampler.Reason.ACTIVE_BOOT_UNAVAILABLE, stale.reason());
                assertTrue(callbackReached.get(), "the real BlockStairs collision capture must trigger the reload callback");
                assertTrue(callbackWasInsideNativeCapture.get(),
                        "reload must happen while the real mapped stair collision callback runs inside capture");
                assertEquals(new FloorRuntime.ReloadReceipt(2L, 1, 1), reload.get());
                assertEquals(2L, runtime.generation().configurationRevision());
                assertFalse(oldDecision.isCurrent());
                assertTrue(stale.cause().isPresent());
                assertTrue(stale.cause().orElseThrow().getMessage().contains("CONTEXT_CHANGED"),
                        "the unavailable result must retain the stale-observation reason");

                StandingConstraints.Resolution unavailableResolution = StandingConstraints.resolve(
                        beforeResolution.nextState(), new StandingConstraints.Request(fixture.world().getTotalWorldTime(),
                                false, StandingConstraints.Evidence.unavailable()));
                assertEquals(StandingConstraints.ConstraintPresence.UNAVAILABLE, unavailableResolution.presence());
                assertEquals(beforeResolution.nextState().bootMemory(), unavailableResolution.nextState().bootMemory(),
                        "the stale capture cannot replace or advance the previously accepted boot reference");
                assertEquals(beforeResolution.nextState().acceptedReference(),
                        unavailableResolution.nextState().acceptedReference());
                assertTrue(unavailableResolution.authoritativeForce().isEmpty());

                PlayerEnvironmentSampler.Sample afterReload = assertInstanceOf(PlayerEnvironmentSampler.Sample.class,
                        sampler.sample(player, physicalState(player), java.util.Optional.of(false)));
                StandingConstraints.BootCandidate newCandidate = candidateAt(afterReload, new BlockPos(8, 64, 8));
                var newIdentity = newCandidate.surface().identity().orElseThrow();
                assertEquals(2L, newIdentity.rulesRevision());
                assertNotEquals(oldIdentity, newIdentity,
                        "the next stable same-world query must retain the newly published active generation");
                StandingConstraints.Resolution afterResolution = StandingConstraints.resolve(
                        beforeResolution.nextState(), new StandingConstraints.Request(afterReload.worldTick(),
                                false, afterReload.evidence()));
                assertEquals(newIdentity, afterResolution.nextState().bootMemory().orElseThrow().surface()
                        .identity().orElseThrow());
            } finally {
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    @Test
    void configuredPassiveItemSelectorsDriveBoundAndIndependentMotionOnce(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("world"), new UUID(0, 831))) {
            fixture.installColumn(fixture.world(), 0, 0);
            fixture.put(fixture.world(), new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
            fixture.publishWorldRoster(fixture.world());
            Path file = configurePassiveItems(directory.resolve("config"));
            CompiledFloorConfig config = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), config);
            GravityEntityBinder binder = new GravityEntityBinder();
            try {
                EntityItem stackedBound = droppedIron(fixture.world(), 8.5, 65.0, 8.5, 64, 0, null);
                fixture.world().loadedEntityList.add(stackedBound);
                try (var binding = binder.bind(stackedBound, GravityMode.REPLACE, new BodyPhysics(1, 0.1))) {
                    binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                    assertEquals(-0.02, stackedBound.motionY, 1e-12,
                            "stack count and a repeated matching rule do not multiply the 0.25 response; a bound item is handled once");
                } finally {
                    fixture.world().loadedEntityList.remove(stackedBound);
                }

                EntityItem metadataOnly = droppedIron(fixture.world(), 8.5, 65.0, 8.5, 1, 1, null);
                metadataOnly.motionY = 0.125;
                fixture.world().loadedEntityList.add(metadataOnly);
                binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                assertEquals(0.085, metadataOnly.motionY, 1e-12,
                        "metadata 1 adds exactly the configured 0.5 passive response to the existing vertical motion");
                fixture.world().loadedEntityList.remove(metadataOnly);

                EntityItem matchingNbt = droppedIron(fixture.world(), 8.5, 65.0, 8.5, 1, 1, "Magnetic");
                fixture.world().loadedEntityList.add(matchingNbt);
                binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                assertEquals(-0.06, matchingNbt.motionY, 1e-12,
                        "the exact metadata and NBT selector changes the actual independent magnetic impulse");
                fixture.world().loadedEntityList.remove(matchingNbt);

                EntityItem mismatchingNbt = droppedIron(fixture.world(), 8.5, 65.0, 8.5, 1, 1, "Other");
                fixture.world().loadedEntityList.add(mismatchingNbt);
                binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                assertEquals(-0.04, mismatchingNbt.motionY, 1e-12,
                        "an NBT mismatch falls through only to the complete metadata selector");
                fixture.world().loadedEntityList.remove(mismatchingNbt);
            } finally {
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    @Test
    void binderRejectsFloorResponseCapturedBeforeAReentrantReload(@TempDir Path directory) throws Exception {
        for (boolean bound : new boolean[] { true, false }) {
            Path scenario = directory.resolve(bound ? "bound" : "independent");
            try (BootWorldFixture fixture = BootWorldFixture.open(scenario.resolve("world"),
                    new UUID(0, bound ? 841 : 842))) {
                fixture.installColumn(fixture.world(), 0, 0);
                fixture.put(fixture.world(), new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
                fixture.publishWorldRoster(fixture.world());
                Path file = FloorConfigLoader.ensureDefault(scenario.resolve("config"));
                String initialJson = Files.readString(file).replace(
                        "\"item\": \"minecraft:iron_ingot\", \"response\": 1.0",
                        "\"item\": \"minecraft:iron_ingot\", \"response\": 0.25");
                Files.writeString(file, initialJson);
                CompiledFloorConfig initial = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
                ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
                FloorRuntime runtime = FloorWorldFields.currentRuntime();
                GravityEntityBinder binder = new GravityEntityBinder();
                AtomicReference<FloorDecision> observedBeforeReload = new AtomicReference<>();
                String noResponseJson = initialJson.replace(
                        "\"item\": \"minecraft:iron_ingot\", \"response\": 0.25",
                        "\"item\": \"minecraft:iron_ingot\", \"response\": 0.0");
                Runnable reloadFromPassiveRead = () -> {
                    observedBeforeReload.set(FloorWorldFields.captureDecision(fixture.world()).orElseThrow());
                    try {
                        Files.writeString(file, noResponseJson);
                    } catch (java.io.IOException cause) {
                        throw new UncheckedIOException(cause);
                    }
                    assertEquals(2L, runtime.reload(file).revision());
                };
                EntityItem item = new ReloadOnReadItem(fixture.world(), 8.5, 65.0, 8.5,
                        new ItemStack(Items.IRON_INGOT), reloadFromPassiveRead);
                item.addedToChunk = true;
                fixture.world().loadedEntityList.add(item);
                GravityEntityBinder.Registration binding = bound
                        ? binder.bind(item, GravityMode.REPLACE, new BodyPhysics(1, 0.1)) : null;
                try {
                    binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                    assertEquals(2L, runtime.generation().configurationRevision());
                    assertFalse(observedBeforeReload.get().isCurrent(),
                            "the captured old decision is rejected after publication even though reload occurs inside item matching");
                    assertEquals(0.0, item.motionY, 0.0,
                            "neither the bound force path nor the independent path may combine old passive response with the new root");
                    binder.tickServerWorlds(new WorldServer[] { fixture.world() });
                    assertEquals(0.0, item.motionY, 0.0,
                            "the stable iron=0 generation produces no magnetic velocity in either consumer path");
                } finally {
                    if (binding != null) binding.close();
                    fixture.world().loadedEntityList.remove(item);
                    assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
                }
            }
        }
    }

    @Test
    void binderDoesNotCommitFloorForceAfterEntityLeavesCapturedWorld(@TempDir Path directory) throws Exception {
        int scenarioId = 850;
        for (String mutation : new String[] { "world", "removed" }) {
            for (boolean bound : new boolean[] { true, false }) {
                Path scenario = directory.resolve(mutation + (bound ? "-bound" : "-independent"));
                try (BootWorldFixture fixture = BootWorldFixture.open(scenario.resolve("world"),
                        new UUID(0, scenarioId++))) {
                    WorldServer original = fixture.world();
                    WorldServer replacement = null;
                    fixture.installColumn(original, 0, 0);
                    fixture.put(original, new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
                    if (mutation.equals("world")) {
                        replacement = fixture.additionalWorld(1, scenario.resolve("replacement-world"));
                        fixture.installColumn(replacement, 0, 0);
                        fixture.publishWorldRoster(original, replacement);
                    } else {
                        fixture.publishWorldRoster(original);
                    }
                    Path file = FloorConfigLoader.ensureDefault(scenario.resolve("config"));
                    CompiledFloorConfig initial = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
                    ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
                    GravityEntityBinder binder = new GravityEntityBinder();
                    WorldServer targetWorld = replacement;
                    AtomicReference<EntityItem> entityRef = new AtomicReference<>();
                    Runnable leaveCapturedOwner = () -> {
                        EntityItem entity = entityRef.get();
                        if (mutation.equals("world")) entity.world = targetWorld;
                        else {
                            entity.addedToChunk = false;
                            entity.isDead = true;
                        }
                    };
                    EntityItem item = new ReloadOnReadItem(original, 8.5, 65.0, 8.5,
                            new ItemStack(Items.IRON_INGOT), leaveCapturedOwner);
                    entityRef.set(item);
                    item.addedToChunk = true;
                    original.loadedEntityList.add(item);
                    GravityEntityBinder.Registration binding = bound
                            ? binder.bind(item, GravityMode.REPLACE, new BodyPhysics(1, 0.1)) : null;
                    try {
                        WorldServer[] roster = replacement == null
                                ? new WorldServer[] { original } : new WorldServer[] { original, replacement };
                        binder.tickServerWorlds(roster);
                        assertEquals(0.0, item.motionY, 0.0,
                                "a floor sample captured before a world move/removal must not reach the motion commit");
                        if (mutation.equals("world")) assertSame(replacement, item.world);
                        else {
                            assertFalse(item.addedToChunk);
                            assertTrue(item.isDead);
                        }
                    } finally {
                        if (binding != null) binding.close();
                        original.loadedEntityList.remove(item);
                        assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
                    }
                }
            }
        }
    }

    @Test
    void finalOwnerLookupCannotMigrateEntityBetweenFloorFenceAndMotionWrite(@TempDir Path directory) throws Exception {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory.resolve("world"), new UUID(0, 859))) {
            WorldServer original = fixture.world();
            WorldServer replacement = fixture.additionalWorld(1, directory.resolve("replacement-world"));
            fixture.installColumn(original, 0, 0);
            fixture.installColumn(replacement, 0, 0);
            fixture.put(original, new BlockPos(8, 64, 8), FloorBlocks.MAGNETIC_FLOOR.getDefaultState());
            fixture.publishWorldRoster(original, replacement);
            Path file = FloorConfigLoader.ensureDefault(directory.resolve("config"));
            CompiledFloorConfig initial = FloorConfigLoader.compile(FloorConfigLoader.parse(file), 1L);
            ServerLifetime lifetime = FloorWorldFields.start(fixture.server(), initial);
            GravityEntityBinder binder = new GravityEntityBinder();
            EntityItem item = new MoveOnFinalOwnerLookupItem(original, fixture, replacement,
                    8.5, 65.0, 8.5, new ItemStack(Items.IRON_INGOT));
            item.addedToChunk = true;
            original.loadedEntityList.add(item);
            try (var binding = binder.bind(item, GravityMode.REPLACE, new BodyPhysics(1, 0.1))) {
                binder.tickServerWorlds(new WorldServer[] { original, replacement });
                assertSame(replacement, item.world,
                        "the virtual server-owner callback runs during the final generation-currentness observation");
                assertEquals(0.0, item.motionY, 0.0,
                        "pure entity ownership checks after that callback reject the old-world magnetic impulse");
            } finally {
                original.loadedEntityList.remove(item);
                assertTrue(FloorWorldFields.stop(fixture.server(), lifetime));
            }
        }
    }

    private static int chunkCount(ActiveFloorGeneration generation) {
        int chunks = 0;
        for (FloorWorldFields.WorldFields fields : generation.worlds().values()) {
            chunks = Math.addExact(chunks, fields.chunkCount());
        }
        return chunks;
    }

    private static PlayerPhysicalSnapshot physicalState(EntityPlayerMP player) {
        var box = player.getEntityBoundingBox();
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions(player.width, player.height, player.getEyeHeight()));
        var placement = PlayerBodyPlacement.fromCardinalBounds(
                new Aabb(new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.maxY, box.maxZ)),
                new Vec3d(player.posX, player.posY, player.posZ), CardinalFrame.Y_POS.attitude(), shape);
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(player.getUniqueID(),
                new UUID(0, 823), player.dimension, 1L), 0L, placement, new Vec3d(0, 0, 0));
    }

    private static Path configureBootsWithoutMaterialOverrides(Path directory) throws Exception {
        Path file = FloorConfigLoader.ensureDefault(directory);
        String source = Files.readString(file);
        String overriddenFurnace = "\"id\": \"lit_furnace_core\", \"block\": \"minecraft:furnace\", "
                + "\"state\": { \"facing\": \"north\" }, \"nbtSnbt\": \"{BurnTime:0}\", \"tier\": \"high\"";
        String inheritedFurnace = "\"id\": \"lit_furnace_core\", \"block\": \"minecraft:furnace\", "
                + "\"state\": { \"facing\": \"north\" }, \"nbtSnbt\": \"{BurnTime:0}\"";
        if (!source.contains(overriddenFurnace)) {
            throw new AssertionError("packaged active-material override anchor is missing");
        }
        Files.writeString(file, source.replace(overriddenFurnace, inheritedFurnace));
        return file;
    }

    private static ItemStack namedIronBoots(String name) {
        ItemStack boots = new ItemStack(Items.IRON_BOOTS);
        NBTTagCompound display = new NBTTagCompound();
        display.setString("Name", name);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("display", display);
        boots.setTagCompound(tag);
        return boots;
    }

    private static StandingConstraints.BootCandidate candidateAt(PlayerEnvironmentSampler.Sample sample,
                                                                    BlockPos cell) {
        return sample.evidence().bootCandidates().stream()
                .filter(candidate -> candidate.surface().frame() == CardinalFrame.Y_POS)
                .filter(candidate -> candidate.surface().identity().orElseThrow().primaryContributorCell().equals(cell))
                .findFirst().orElseThrow(() -> new AssertionError("active query did not expose top face at " + cell));
    }

    private static StandingConstraints.Resolution resolveBoots(PlayerEnvironmentSampler.Sample sample) {
        return StandingConstraints.resolve(StandingConstraints.State.initial(),
                new StandingConstraints.Request(sample.worldTick(), false, sample.evidence()));
    }

    private static boolean insideNativeStairCaptureCallback() {
        boolean capture = false;
        boolean stairs = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            capture |= BootWorldSurfaces.class.getName().equals(frame.getClassName())
                    && "collectNativeCell".equals(frame.getMethodName());
            stairs |= "net.minecraft.block.BlockStairs".equals(frame.getClassName());
        }
        return capture && stairs;
    }

    private static CompiledFloorConfig load(Path directory, long revision,
                                            java.util.function.UnaryOperator<String> edit) throws Exception {
        Path file = FloorConfigLoader.ensureDefault(directory);
        String source = Files.readString(file);
        String configured = edit.apply(source);
        Files.writeString(file, configured);
        return FloorConfigLoader.compile(FloorConfigLoader.parse(file), revision);
    }

    private static Path configurePassiveItems(Path directory) throws Exception {
        Path file = FloorConfigLoader.ensureDefault(directory);
        String source = Files.readString(file).replace(
                "\"item\": \"minecraft:iron_ingot\", \"response\": 1.0",
                "\"item\": \"minecraft:iron_ingot\", \"response\": 0.25");
        String marker = "      { \"item\": \"minecraft:iron_nugget\", \"response\": 1.0 },";
        String selectors = "      { \"item\": \"minecraft:iron_ingot\", \"response\": 0.25 },\n"
                + "      { \"item\": \"minecraft:iron_ingot\", \"metadata\": 1, \"response\": 0.5 },\n"
                + "      { \"item\": \"minecraft:iron_ingot\", \"metadata\": 1, \"nbtSnbt\": \"{display:{Name:\\\"Magnetic\\\"}}\", \"response\": 0.75 },\n"
                + marker;
        if (!source.contains(marker) || !source.contains("\"response\": 0.25")) {
            throw new AssertionError("packaged floors.json no longer contains the expected passive selector anchors");
        }
        Files.writeString(file, source.replace(marker, selectors));
        return file;
    }

    private static EntityItem droppedIron(WorldServer world, double x, double y, double z,
                                          int count, int metadata, String displayName) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT, count, metadata);
        if (displayName != null) {
            NBTTagCompound display = new NBTTagCompound();
            display.setString("Name", displayName);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag("display", display);
            stack.setTagCompound(tag);
        }
        EntityItem entity = new EntityItem(world, x, y, z, stack);
        entity.addedToChunk = true;
        setMotion(entity, 0.0, 0.0, 0.0);
        return entity;
    }

    private static void setMotion(EntityItem entity, double x, double y, double z) {
        entity.motionX = x;
        entity.motionY = y;
        entity.motionZ = z;
    }

    private static final class ReloadOnReadItem extends EntityItem {
        private Runnable onFirstRead;

        private ReloadOnReadItem(WorldServer world, double x, double y, double z, ItemStack stack,
                                 Runnable onFirstRead) {
            super(world, x, y, z, stack);
            this.onFirstRead = onFirstRead;
            this.addedToChunk = true;
            setMotion(this, 0.0, 0.0, 0.0);
        }

        @Override
        public ItemStack getItem() {
            Runnable callback = onFirstRead;
            if (callback != null) {
                onFirstRead = null;
                callback.run();
            }
            return super.getItem();
        }
    }

    private static final class MoveOnFinalOwnerLookupItem extends EntityItem {
        private final BootWorldFixture fixture;
        private final WorldServer capturedWorld;
        private final WorldServer replacement;
        private boolean callbackScheduled;

        private MoveOnFinalOwnerLookupItem(WorldServer world, BootWorldFixture fixture, WorldServer replacement,
                                           double x, double y, double z, ItemStack stack) {
            super(world, x, y, z, stack);
            this.fixture = fixture;
            this.capturedWorld = world;
            this.replacement = replacement;
            setMotion(this, 0.0, 0.0, 0.0);
        }

        @Override
        public boolean isInWater() {
            if (!callbackScheduled) {
                callbackScheduled = true;
                fixture.onNextServerLookup(capturedWorld, () -> this.world = replacement);
            }
            return false;
        }
    }
}
