package wi.unigrav.core.floor;

import wi.unigrav.core.AmbientWorldFields;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.ImpactDamageHandler;

import com.mojang.authlib.GameProfile;
import net.minecraft.advancements.AdvancementManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import wi.unigrav.core.player.PlayerEquipmentMutation;
import wi.unigrav.core.player.NativePoseEyeProducer;
import wi.unigrav.core.player.NativeBodyProfileProducer;
import wi.unigrav.core.player.CollisionQueryFootprint;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

/**
 * Test-only mapped WorldServer in temporary directories, without socket transport.
 * Headless worlds have no generator; the sleep network world uses a real provider generator.
 */
public final class BootWorldFixture implements AutoCloseable {
    private final TestServer server;
    private final TestWorld world;
    private final EntityPlayer actor;
    private final List<EntityPlayerMP> serverPlayers = new ArrayList<>();
    private final List<TestWorld> worlds;

    private BootWorldFixture(TestServer server, TestWorld world, EntityPlayer actor, List<TestWorld> worlds) {
        this.server = server;
        this.world = world;
        this.actor = actor;
        this.worlds = worlds;
    }

    static BootWorldFixture open(Path dataDirectory, UUID playerId) {
        return open(dataDirectory, playerId, false);
    }

    public static BootWorldFixture open(Path dataDirectory, UUID playerId, boolean dedicated) {
        return open(dataDirectory, playerId, dedicated, null, false);
    }

    /** Sleep network fixture alone uses real login persistence and WorldServer initialization. */
    static BootWorldFixture openForSleepNetwork(Path dataDirectory, UUID playerId, PlayerProfileCache profileCache) {
        return open(dataDirectory, playerId, true, Objects.requireNonNull(profileCache), true);
    }

    private static BootWorldFixture open(Path dataDirectory, UUID playerId, boolean dedicated,
                                         PlayerProfileCache profileCache, boolean sleepNetwork) {
        if (DimensionManager.getWorld(0) != null) {
            throw new IllegalStateException("headless fixture refuses to replace an existing dimension-zero world");
        }
        bootstrapForTests();
        TestServer server = new TestServer(dataDirectory.toFile(), dedicated, profileCache);
        try {
            TestWorld world = TestWorld.open(server, dataDirectory.toFile(), 0, sleepNetwork);
            if (sleepNetwork) {
                world.init();
                // Match normal loadAllWorlds before real login populates tracking and self wake animations.
                world.addEventListener(new ServerWorldEventHandler(server, world));
                server.getPlayerList().setPlayerManager(server.worlds);
            }
            EntityPlayer actor = new TestPlayer(world, playerId);
            FloorWorldFields.registerLocalTestOwner(world);
            AmbientWorldFields.load(world);
            return new BootWorldFixture(server, world, actor, new ArrayList<>(List.of(world)));
        } catch (RuntimeException | Error failure) {
            try {
                release(server, (TestWorld) null);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                server.getPlayerUsageSnooper().stopSnooper();
            }
            throw failure;
        }
    }

    /** Fixture-only producer callback at the real native chunk-loaded predicate. */
    public void onNextChunkLoaded(Runnable callback) {
        if(world.nextChunkLoaded!=null)throw new IllegalStateException("area-loaded callback already armed");
        world.nextChunkLoaded=Objects.requireNonNull(callback);
    }
    /** Scoped rain-query control for actual environment-observation tests; default World behavior is unchanged. */
    public AutoCloseable rainQueries(java.util.function.Function<BlockPos, Boolean> query) {
        if (world.rainQuery != null) throw new IllegalStateException("rain query already scoped");
        var exact = Objects.requireNonNull(query);
        world.rainQuery = exact;
        return () -> {
            if (world.rainQuery != exact) throw new IllegalStateException("rain query scope replaced");
            world.rainQuery = null;
        };
    }

    public WorldServer world() {
        return world;
    }

    void shareMapStorage(WorldServer target, WorldServer source) {
        testWorld(target).shareMapStorage(source);
    }

    public MinecraftServer server() {
        return server;
    }

    static void bootstrapForTests() {
        Bootstrap.register();
        registerTestFloors();
    }

    private static void registerTestFloors() {
        boolean registered = Block.REGISTRY.getObject(FloorBlocks.GRAVITY_FLOOR.getRegistryName()) == FloorBlocks.GRAVITY_FLOOR
                && Block.REGISTRY.getObject(FloorBlocks.MAGNETIC_FLOOR.getRegistryName()) == FloorBlocks.MAGNETIC_FLOOR
                && Block.REGISTRY.getObject(FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getRegistryName())
                == FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR;
        if (registered) return;
        if (Block.REGISTRY.containsKey(FloorBlocks.GRAVITY_FLOOR.getRegistryName())
                || Block.REGISTRY.containsKey(FloorBlocks.MAGNETIC_FLOOR.getRegistryName())
                || Block.REGISTRY.containsKey(FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getRegistryName())) {
            throw new IllegalStateException("headless test registry partially owns the UG floor IDs");
        }
        ForgeRegistry<Block> blocks = RegistryManager.ACTIVE.getRegistry(GameData.BLOCKS);
        blocks.unfreeze();
        try {
            blocks.registerAll(FloorBlocks.GRAVITY_FLOOR, FloorBlocks.MAGNETIC_FLOOR,
                    FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR);
        } finally {
            blocks.freeze();
        }
    }

    /** Retire this fixture's exact registered owner before constructing a same-dimension successor. */
    WorldServer replaceWorld(int dimensionId, Path dataDirectory) {
        WorldServer registered = DimensionManager.getWorld(dimensionId);
        TestWorld previous = testWorld(registered);
        FloorWorldFields.onWorldUnload(new WorldEvent.Unload(previous));
        FloorWorldFields.unregisterLocalTestOwner(previous);
        AmbientWorldFields.onWorldUnload(new WorldEvent.Unload(previous));
        DimensionManager.setWorld(dimensionId, null, server);
        worlds.remove(previous);
        return additionalWorld(dimensionId, dataDirectory);
    }

    WorldServer additionalWorld(int dimensionId, Path dataDirectory) {
        if (DimensionManager.getWorld(dimensionId) != null) {
            throw new IllegalStateException("headless fixture refuses to replace an existing dimension " + dimensionId);
        }
        TestWorld additional = TestWorld.open(server, dataDirectory.toFile(), dimensionId);
        worlds.add(additional);
        FloorWorldFields.registerLocalTestOwner(additional);
        AmbientWorldFields.load(additional);
        return additional;
    }

    public void publishWorldRoster(WorldServer... loadedWorlds) {
        Objects.requireNonNull(loadedWorlds, "loadedWorlds");
        for (WorldServer loaded : loadedWorlds) {
            if (loaded == null || loaded.getMinecraftServer() != server || !worlds.contains(loaded)) {
                throw new IllegalArgumentException("server roster may contain only this fixture's exact worlds");
            }
        }
        server.worlds = loadedWorlds.clone();
    }

    void onNextServerLookup(WorldServer target, Runnable callback) {
        testWorld(target).onNextServerLookup(callback);
    }

    void onNextChunkStorageRead(WorldServer target, int chunkX, int chunkZ, Runnable callback) {
        testWorld(target).onNextChunkStorageRead(chunkX, chunkZ, callback);
    }

    void replaceChunkWithInvalidStorage(WorldServer target, int chunkX, int chunkZ) {
        testWorld(target).replaceChunkWithInvalidStorage(chunkX, chunkZ);
    }

    private TestWorld testWorld(WorldServer target) {
        if (!(target instanceof TestWorld fixtureWorld) || !worlds.contains(fixtureWorld)) {
            throw new IllegalArgumentException("world does not belong to this fixture");
        }
        return fixtureWorld;
    }

    EntityPlayer actor() {
        return actor;
    }

    EntityPlayer player(UUID id) { return new TestPlayer(world, id); }

    int positionUpdates(Entity entity) { return world.positionUpdates.getOrDefault(entity, 0); }

    /** An actual MP and native connection handler, with no channel/socket opened. */
    public EntityPlayerMP serverPlayer(UUID id) {
        return serverPlayer(id, null);
    }

    EntityPlayerMP serverPlayer(UUID id, Consumer<EntityPlayerMP> afterJump) {
        return serverPlayer(id, afterJump, false);
    }

    EntityPlayerMP serverPlayerWithCachedEye(UUID id, Consumer<EntityPlayerMP> afterJump) {
        return serverPlayer(id, afterJump, true);
    }

    /** Prepare the real native MP constructor spawn before a specialized test subclass calls super. */
    /** Ordinary contained-body scene: no independent native body-profile producer is installed. */
    EntityPlayerMP serverPlayerWithoutBodyProfile(UUID id) {
        prepareServerPlayerSpawn();
        var player = new EntityPlayerMP(server, world, new GameProfile(id, "contained-body-fixture"),
                new PlayerInteractionManager(world));
        serverPlayers.add(player);
        new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), player);
        return player;
    }

    public void prepareServerPlayerSpawn() {
        // MP construction consults randomized spawn and collision columns before the test can position it.
        world.getGameRules().setOrCreateGameRule("spawnRadius", "0");
        world.setSpawnPoint(new BlockPos(8, 65, 8));
        world.install(0, 0);
    }

    private EntityPlayerMP serverPlayer(UUID id, Consumer<EntityPlayerMP> afterJump, boolean cachedEyeModel) {
        prepareServerPlayerSpawn();
        EntityPlayerMP player = cachedEyeModel ? new CachedEyePlayer(server, world, id, afterJump)
                : new PosePlayer(server, world, id, afterJump);
        serverPlayers.add(player);
        new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), player);
        return player;
    }

    static void updateNativePose(EntityPlayerMP player) {
        ((PosePlayer) player).updateSize();
    }

    static void resizePlayer(EntityPlayerMP player, float width, float height) {
        ((PosePlayer) player).resize(width, height);
    }

    public static void afterNextBoundingBox(EntityPlayerMP player, Runnable callback) {
        ((PosePlayer) player).afterBoundingBox(callback);
    }

    public static void afterNextPlayerSleepingPredicate(EntityPlayerMP player, Runnable callback) {
        ((PosePlayer) player).afterSleepingPredicate(callback);
    }

    public void afterNextEntityUpdate(Entity entity, Runnable callback) {
        world.afterNextEntityUpdate(entity, callback);
    }

    static void afterNextPlayerServerLookup(EntityPlayerMP player, Runnable callback) {
        ((PosePlayer) player).afterServerLookup(callback);
    }

    static void afterNextPlayerWaterPredicate(EntityPlayerMP player, Runnable callback) {
        ((PosePlayer) player).afterWaterPredicate(callback);
    }

    /** Models a pose integration whose effective eye is cached independently of raw eyeHeight. */
    static void cachedEffectiveEye(EntityPlayerMP player, float eye) {
        if (!Float.isFinite(eye) || eye < 0) throw new IllegalArgumentException("invalid cached eye");
        ((PosePlayer) player).cachedEffectiveEye = eye;
        ((PosePlayer) player).cachedEyeRevision++;
    }

    /** Only the explicit headless cached-eye model overrides the real optional Aqua producer. */
    private static final class CachedEyePlayer extends PosePlayer implements NativePoseEyeProducer {
        private CachedEyePlayer(MinecraftServer server, WorldServer world, UUID id,
                Consumer<EntityPlayerMP> afterJump) {
            super(server, world, id, afterJump);
        }

        @Override public Optional<NativePoseEyeProducer.Profile> unigrav$nativePoseEyeProfile() {
            var source = (PosePlayer) this;
            return source.cachedEffectiveEye == null ? Optional.empty()
                    : Optional.of(new NativePoseEyeProducer.Profile(
                            source.cachedEyeRevision, source.cachedEffectiveEye, 1F));
        }
    }

    private static class PosePlayer extends EntityPlayerMP implements NativeBodyProfileProducer {
        private final NativeBodyProfileProducer.Profile testConstructedBody;
        private final Consumer<EntityPlayerMP> afterJump;
        private Float cachedEffectiveEye;
        private long cachedEyeRevision;

        private Runnable afterMovementStat;
        private Runnable afterBoundingBox;
        private Runnable afterSleepingPredicate;
        private Runnable afterServerLookup;
        private Runnable afterWaterPredicate;

        private PosePlayer(MinecraftServer server, WorldServer world, UUID id, Consumer<EntityPlayerMP> afterJump) {
            super(server, world, new GameProfile(id, "native-move-fixture"), new PlayerInteractionManager(world));
            // TEST_CONSTRUCTOR_CAPTURE: read this actual MP immediately after its real constructor.
            testConstructedBody = new NativeBodyProfileProducer.Profile(NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR,
                    width, height, width, height);
            this.afterJump = afterJump;
        }

        @Override public Optional<NativeBodyProfileProducer.Profile> unigrav$nativeBodyProfile() {
            return Optional.ofNullable(testConstructedBody);
        }

        @Override public void updateSize() { super.updateSize(); }

        @Override public float getEyeHeight() {
            float nativeEye = super.getEyeHeight();
            return cachedEffectiveEye == null ? nativeEye : cachedEffectiveEye;
        }

        @Override public MinecraftServer getServer() {
            MinecraftServer server = super.getServer();
            Runnable callback = afterServerLookup;
            if (callback != null) {
                afterServerLookup = null;
                callback.run();
            }
            return server;
        }

        @Override public boolean isInWater() {
            boolean water = super.isInWater();
            Runnable callback = afterWaterPredicate;
            if (callback != null) {
                afterWaterPredicate = null;
                callback.run();
            }
            return water;
        }

        private void afterServerLookup(Runnable callback) {
            if (afterServerLookup != null) throw new IllegalStateException("server lookup callback already armed");
            afterServerLookup = Objects.requireNonNull(callback, "callback");
        }

        private void afterWaterPredicate(Runnable callback) {
            if (afterWaterPredicate != null) throw new IllegalStateException("water predicate callback already armed");
            afterWaterPredicate = Objects.requireNonNull(callback, "callback");
        }

        @Override public boolean isPlayerSleeping() {
            boolean sleeping = super.isPlayerSleeping();
            Runnable callback = afterSleepingPredicate;
            if (callback != null) {
                afterSleepingPredicate = null;
                callback.run();
            }
            return sleeping;
        }

        private void resize(float width, float height) { setSize(width, height); }

        private void afterNextMovementStat(Runnable callback) {
            if (afterMovementStat != null) throw new IllegalStateException("movement-stat callback already armed");
            afterMovementStat = Objects.requireNonNull(callback, "callback");
        }

        private void afterBoundingBox(Runnable callback) {
            if (afterBoundingBox != null) throw new IllegalStateException("bounding-box callback already armed");
            afterBoundingBox = Objects.requireNonNull(callback, "callback");
        }

        private void afterSleepingPredicate(Runnable callback) {
            if (afterSleepingPredicate != null) throw new IllegalStateException("sleep predicate callback already armed");
            afterSleepingPredicate = Objects.requireNonNull(callback, "callback");
        }

        @Override public void setEntityBoundingBox(AxisAlignedBB box) {
            super.setEntityBoundingBox(box);
            Runnable callback = afterBoundingBox;
            if (callback != null) {
                afterBoundingBox = null;
                callback.run();
            }
        }

        @Override public void setItemStackToSlot(EntityEquipmentSlot slot, ItemStack stack) {
            // Headless JVM has no Mixin transformer: exercise the production mutation boundary
            // around the real native setter. Runtime injection remains a separate acceptance gate.
            PlayerEquipmentMutation.observe(this, () -> {
                super.setItemStackToSlot(slot, stack);
                return null;
            });
        }

        @Override public void fall(float distance, float multiplier) {
            // Headless fixtures do not install Forge's transformed event listener lists.
            // Invoke the real UG listener before the remaining native fall implementation.
            var event = new LivingFallEvent(this, distance, multiplier) {
                // Forge's @Cancelable transformer supplies this in the game, not in plain JUnit.
                @Override public boolean isCancelable() { return true; }
            };
            if (UnigravMod.entityBinder != null) ImpactDamageHandler.INSTANCE.onLivingFall(event);
            if (!event.isCanceled()) super.fall(event.getDistance(), event.getDamageMultiplier());
        }

        @Override public void jump() {
            super.jump();
            if (afterJump != null) afterJump.accept(this);
        }

        @Override public void addMovementStat(double x, double y, double z) {
            super.addMovementStat(x, y, z);
            Runnable callback = afterMovementStat;
            if (callback != null) {
                afterMovementStat = null;
                callback.run();
            }
        }
    }

    void install(BootQueryFootprint footprint) {
        for (BootQueryFootprint.Column column : footprint.columns()) world.install(column.x(), column.z());
    }

    /** Installs only the real empty TestChunk columns required by a planned collision scan. */
    public void installCollisionFootprint(CollisionQueryFootprint footprint) {
        Objects.requireNonNull(footprint, "footprint");
        for (CollisionQueryFootprint.Column column : footprint.columns()) world.install(column.x(), column.z());
    }

    void installColumn(int x, int z) { world.install(x, z); }

    void installColumn(WorldServer target, int x, int z) { testWorld(target).install(x, z); }

    void put(BlockPos cell, IBlockState state) {
        world.put(cell, state);
    }

    void put(WorldServer target, BlockPos cell, IBlockState state) {
        testWorld(target).put(cell, state);
    }

    void onNextLoadedChunkRead(Runnable callback) {
        world.onNextLoadedChunkRead(callback);
    }

    /** Runs at a real loaded-chunk read only after the supplied live owner condition holds. */
    public void onLoadedChunkReadWhen(BooleanSupplier condition, Runnable callback) {
        world.onLoadedChunkReadWhen(condition, callback);
    }

    void afterNextMovementStat(EntityPlayerMP player, Runnable callback) {
        ((PosePlayer) player).afterNextMovementStat(callback);
    }

    void removeColumn(int chunkX, int chunkZ) {
        world.remove(chunkX, chunkZ);
    }

    void removeColumn(WorldServer target, int chunkX, int chunkZ) {
        testWorld(target).remove(chunkX, chunkZ);
    }

    @Override public void close() {
        Throwable failure = null;
        boolean released = false;
        try {
            for (EntityPlayerMP player : serverPlayers) player.getAdvancements().dispose();
        } catch (RuntimeException | Error cleanupFailure) {
            failure = cleanupFailure;
        } finally {
            serverPlayers.clear();
            try {
                FloorRuntime active = FloorWorldFields.currentRuntime();
                if (active != null && active.server() == server) {
                    FloorWorldFields.stop(server, active.lifetime());
                }
                for (TestWorld fixtureWorld : worlds) {
                    FloorWorldFields.unregisterLocalTestOwner(fixtureWorld);
                    AmbientWorldFields.retire(fixtureWorld);
                }
                release(server, worlds);
                released = true;
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
            try {
                if (released) closeOwnedRegions();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            } finally {
                try {
                    server.getPlayerUsageSnooper().stopSnooper();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error error) throw error;
    }

    // Vanilla exposes only a global cache flush. This fixture must not close another world's files.
    private static final Field REGION_FILES = regionFilesField();

    private static Field regionFilesField() {
        try {
            Field field = RegionFileCache.class.getDeclaredField("REGIONS_BY_FILE");
            if (field.getDeclaringClass() != RegionFileCache.class || field.getType() != Map.class
                    || !Modifier.isPrivate(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("unexpected RegionFileCache.REGIONS_BY_FILE descriptor");
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot access fixture-owned region cache for cleanup", failure);
        }
    }

    private void closeOwnedRegions() {
        synchronized (RegionFileCache.class) {
            try {
                Object cache = REGION_FILES.get(null);
                if (!(cache instanceof Map<?, ?> regions)) {
                    throw new IllegalStateException("unexpected region cache value");
                }
                Throwable failure = null;
                var entries = regions.entrySet().iterator();
                while (entries.hasNext()) {
                    var entry = entries.next();
                    try {
                        if (!(entry.getKey() instanceof File path) || !(entry.getValue() instanceof RegionFile region)) {
                            throw new IllegalStateException("unexpected region cache entry");
                        }
                        File canonical = path.getCanonicalFile();
                        boolean owned = worlds.stream().anyMatch(w -> w.regionDirectory.equals(canonical.getParentFile()));
                        if (!owned || !canonical.getName().endsWith(".mca")) continue;
                        region.close();
                        entries.remove();
                    } catch (IOException | RuntimeException | Error cleanupFailure) {
                        if (failure == null) failure = cleanupFailure;
                        else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
                    }
                }
                if (failure instanceof IOException ioFailure) throw ioFailure;
                if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
                if (failure instanceof Error error) throw error;
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("cannot read fixture-owned region cache for cleanup", failure);
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot close fixture-owned region files", failure);
            }
        }
    }

    private static void release(TestServer server, TestWorld expected) {
        WorldServer registered = DimensionManager.getWorld(0);
        if (expected != null && registered != expected) {
            throw new IllegalStateException("headless fixture lost dimension-zero ownership before cleanup");
        }
        if (expected == null && registered != null && registered.getMinecraftServer() != server) {
            throw new IllegalStateException("headless fixture must not clear another server's dimension-zero world");
        }
        DimensionManager.setWorld(0, null, server);
        if (DimensionManager.getWorld(0) != null || server.worldTickTimes.containsKey(0)) {
            throw new IllegalStateException("headless fixture did not release its dimension-zero registration");
        }
        if (expected != null) {
            for (WorldServer published : server.worlds) {
                if (published == expected) {
                    throw new IllegalStateException("headless fixture remains published by its server");
                }
            }
        }
    }

    private static void release(TestServer server, List<TestWorld> expectedWorlds) {
        for (TestWorld expected : expectedWorlds) {
            int dimensionId = expected.provider.getDimension();
            if (DimensionManager.getWorld(dimensionId) != expected) {
                throw new IllegalStateException("headless fixture lost dimension " + dimensionId + " ownership before cleanup");
            }
            DimensionManager.setWorld(dimensionId, null, server);
            if (DimensionManager.getWorld(dimensionId) != null || server.worldTickTimes.containsKey(dimensionId)) {
                throw new IllegalStateException("headless fixture did not release dimension " + dimensionId);
            }
        }
        for (WorldServer published : server.worlds) {
            if (published != null && !expectedWorlds.contains(published)) {
                throw new IllegalStateException("headless fixture must not clear another world's server roster entry");
            }
        }
        server.worlds = new WorldServer[0];
    }

    private static final class TestWorld extends WorldServer {
        private static final ThreadLocal<File> CHUNK_ROOT = new ThreadLocal<>();
        private static final ThreadLocal<Boolean> SLEEP_NETWORK = new ThreadLocal<>();
        private final File regionDirectory;
        private final Map<Entity, Integer> positionUpdates = new IdentityHashMap<>();
        private final Map<Entity, Runnable> afterEntityUpdate = new IdentityHashMap<>();
        private Runnable nextLoadedChunkRead;
        private BooleanSupplier loadedChunkReadCondition;
        private java.util.function.Function<BlockPos, Boolean> rainQuery;
        @Override public boolean isRainingAt(BlockPos position) {
            var query = rainQuery;
            return query == null ? super.isRainingAt(position) : query.apply(position.toImmutable());
        }
        private Runnable nextServerLookup;
        private Runnable nextChunkLoaded;

        @Override protected boolean isChunkLoaded(int chunkX,int chunkZ,boolean allowEmpty) {
            Runnable callback=nextChunkLoaded;
            if(callback!=null){nextChunkLoaded=null;callback.run();}
            return super.isChunkLoaded(chunkX,chunkZ,allowEmpty);
        }
        private void onNextLoadedChunkRead(Runnable callback) {
            loadedChunkReadCondition = null;
            nextLoadedChunkRead = Objects.requireNonNull(callback, "callback");
        }

        private void onLoadedChunkReadWhen(BooleanSupplier condition, Runnable callback) {
            loadedChunkReadCondition = Objects.requireNonNull(condition, "condition");
            nextLoadedChunkRead = Objects.requireNonNull(callback, "callback");
        }

        private void onNextServerLookup(Runnable callback) {
            nextServerLookup = Objects.requireNonNull(callback, "callback");
        }

        private void onNextChunkStorageRead(int chunkX, int chunkZ, Runnable callback) {
            Chunk chunk = getChunkProvider().getLoadedChunk(chunkX, chunkZ);
            if (!(chunk instanceof TestChunk testChunk)) {
                throw new IllegalStateException("fixture chunk is not an instrumented real Chunk");
            }
            testChunk.onNextStorageRead(callback);
        }

        private void replaceChunkWithInvalidStorage(int chunkX, int chunkZ) {
            TestChunk malformed = new TestChunk(this, chunkX, chunkZ);
            malformed.markLoaded(true);
            malformed.invalidStorage = true;
            getChunkProvider().loadedChunks.put(ChunkPos.asLong(chunkX, chunkZ), malformed);
        }

        @Override
        public MinecraftServer getMinecraftServer() {
            Runnable callback = nextServerLookup;
            if (callback != null) {
                nextServerLookup = null;
                callback.run();
            }
            return super.getMinecraftServer();
        }

        @Override
        public void updateEntityWithOptionalForce(Entity entity, boolean forceUpdate) {
            positionUpdates.merge(entity, 1, Integer::sum);
            super.updateEntityWithOptionalForce(entity, forceUpdate);
            Runnable callback = afterEntityUpdate.remove(entity);
            if (callback != null) callback.run();
        }

        private void afterNextEntityUpdate(Entity entity, Runnable callback) {
            if (afterEntityUpdate.putIfAbsent(Objects.requireNonNull(entity), Objects.requireNonNull(callback)) != null)
                throw new IllegalStateException("entity update callback already armed");
        }

        private static TestWorld open(MinecraftServer server, File chunkRoot) {
            return open(server, chunkRoot, 0);
        }

        private static TestWorld open(MinecraftServer server, File chunkRoot, int dimensionId) {
            return open(server, chunkRoot, dimensionId, false);
        }

        private static TestWorld open(MinecraftServer server, File chunkRoot, int dimensionId,
                                      boolean sleepNetwork) {
            CHUNK_ROOT.set(chunkRoot);
            SLEEP_NETWORK.set(sleepNetwork);
            try {
                return new TestWorld(server, dimensionId);
            } finally {
                SLEEP_NETWORK.remove();
                CHUNK_ROOT.remove();
            }
        }

        private void shareMapStorage(WorldServer source) { this.mapStorage = source.getMapStorage(); }

        private TestWorld(MinecraftServer server, int dimensionId) {
            super(server, fixtureSaveHandler(), new WorldInfo(new NBTTagCompound()), dimensionId, new Profiler());
            try {
                this.regionDirectory = new File(Objects.requireNonNull(CHUNK_ROOT.get()), "region").getCanonicalFile();
            } catch (IOException failure) {
                throw new UncheckedIOException("cannot retain fixture region directory", failure);
            }
            // Headless callers install persistence without terrain generation. The sleep network
            // world additionally uses the real provider generator and WorldServer.init.
            this.mapStorage = new MapStorage(getSaveHandler());
        }

        private static SaveHandlerMP fixtureSaveHandler() {
            File directory = CHUNK_ROOT.get();
            if (directory == null) throw new AssertionError("headless fixture requires a test-only world root");
            // PlayerList derives stats/advancement paths from this, not from the Anvil loader.
            return new SaveHandlerMP() {
                @Override public File getWorldDirectory() { return directory; }
                @Override public IPlayerFileData getPlayerNBTManager() {
                    return new SaveHandler(directory, "player-state", true, new DataFixer(0));
                }
            };
        }

        @Override
        protected ChunkProviderServer createChunkProvider() {
            File chunkRoot = CHUNK_ROOT.get();
            if (chunkRoot == null) throw new AssertionError("headless fixture requires a test-only chunk root");
            // Called from WorldServer's constructor, before TestWorld instance fields initialize.
            // Only the explicit sleep construction path enables the real provider generator.
            return new ChunkProviderServer(this, new AnvilChunkLoader(chunkRoot, getMinecraftServer().getDataFixer()),
                    Boolean.TRUE.equals(SLEEP_NETWORK.get()) ? provider.createChunkGenerator() : null) {
                @Override public Chunk getLoadedChunk(int chunkX, int chunkZ) {
                    Runnable callback = nextLoadedChunkRead;
                    BooleanSupplier condition = loadedChunkReadCondition;
                    if (callback != null && (condition == null || condition.getAsBoolean())) {
                        nextLoadedChunkRead = null;
                        loadedChunkReadCondition = null;
                        callback.run();
                    }
                    return super.getLoadedChunk(chunkX, chunkZ);
                }
            };
        }

        private void install(int chunkX, int chunkZ) {
            if (getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null) return;
            Chunk chunk = new TestChunk(this, chunkX, chunkZ);
            chunk.markLoaded(true);
            getChunkProvider().loadedChunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
        }

        private void put(BlockPos cell, IBlockState state) {
            Chunk chunk = getChunkProvider().getLoadedChunk(cell.getX() >> 4, cell.getZ() >> 4);
            if (chunk == null) throw new AssertionError("fixture omitted planned chunk for " + cell);
            chunk.setBlockState(cell, state);
        }

        private void remove(int chunkX, int chunkZ) {
            getChunkProvider().loadedChunks.remove(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    private static final class TestChunk extends Chunk {
        private Runnable nextStorageRead;
        private boolean invalidStorage;

        private TestChunk(WorldServer world, int chunkX, int chunkZ) {
            super(world, chunkX, chunkZ);
        }

        private void onNextStorageRead(Runnable callback) {
            nextStorageRead = Objects.requireNonNull(callback, "callback");
        }

        @Override
        public ExtendedBlockStorage[] getBlockStorageArray() {
            Runnable callback = nextStorageRead;
            if (callback != null) {
                nextStorageRead = null;
                callback.run();
            }
            return invalidStorage ? null : super.getBlockStorageArray();
        }
    }

    private static final class TestPlayer extends EntityPlayer {
        private TestPlayer(WorldServer world, UUID playerId) {
            super(world, new GameProfile(playerId, "boot-world-fixture"));
        }

        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    private static final class TestServer extends MinecraftServer {
        private final Thread ownerThread;
        private final AdvancementManager advancements;
        private final boolean dedicated;

        private TestServer(File dataDirectory, boolean dedicated, PlayerProfileCache profileCache) {
            super(dataDirectory, Proxy.NO_PROXY, new DataFixer(0), null, null, null, profileCache);
            this.dedicated = dedicated;
            ownerThread = Thread.currentThread();
            advancements = new AdvancementManager(new File(dataDirectory, "advancements"));
            setPlayerList(new PlayerList(this) { });
        }

        @Override public boolean init() { return false; }
        @Override public AdvancementManager getAdvancementManager() { return advancements; }
        @Override public boolean canStructuresSpawn() { return false; }
        @Override public GameType getGameType() { return GameType.SURVIVAL; }
        @Override public EnumDifficulty getDifficulty() { return EnumDifficulty.NORMAL; }
        @Override public boolean isHardcore() { return false; }
        @Override public int getOpPermissionLevel() { return 0; }
        @Override public boolean shouldBroadcastRconToOps() { return false; }
        @Override public boolean shouldBroadcastConsoleToOps() { return false; }
        @Override public boolean isDedicatedServer() { return dedicated; }
        @Override public boolean shouldUseNativeTransport() { return false; }
        @Override public boolean isCommandBlockEnabled() { return false; }
        @Override public String shareToLAN(GameType gameType, boolean allowCheats) { return null; }
        @Override public boolean isCallingFromMinecraftThread() { return Thread.currentThread() == ownerThread; }
    }
}
