package wi.unigrav.core.floor;

import java.io.File;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.ImpactDamageHandler;

import com.mojang.authlib.GameProfile;
import net.minecraft.advancements.AdvancementManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import wi.unigrav.core.player.PlayerEquipmentMutation;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.datafix.DataFixer;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.SaveHandler;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;

/** Test-only real mapped WorldServer fixture; it never starts, saves, generates, or opens a socket. */
public final class BootWorldFixture implements AutoCloseable {
    private final TestServer server;
    private final TestWorld world;
    private final EntityPlayer actor;
    private final List<EntityPlayerMP> serverPlayers = new ArrayList<>();

    private BootWorldFixture(TestServer server, TestWorld world, EntityPlayer actor) {
        this.server = server;
        this.world = world;
        this.actor = actor;
    }

    static BootWorldFixture open(Path dataDirectory, UUID playerId) {
        return open(dataDirectory, playerId, false);
    }

    public static BootWorldFixture open(Path dataDirectory, UUID playerId, boolean dedicated) {
        if (DimensionManager.getWorld(0) != null) {
            throw new IllegalStateException("headless fixture refuses to replace an existing dimension-zero world");
        }
        TestServer server = new TestServer(dataDirectory.toFile(), dedicated);
        try {
            TestWorld world = TestWorld.open(server, dataDirectory.toFile());
            return new BootWorldFixture(server, world, new TestPlayer(world, playerId));
        } catch (RuntimeException | Error failure) {
            try {
                release(server, null);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } finally {
                server.getPlayerUsageSnooper().stopSnooper();
            }
            throw failure;
        }
    }

    public WorldServer world() {
        return world;
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
        // MP construction consults randomized spawn and collision columns before the test can position it.
        world.getGameRules().setOrCreateGameRule("spawnRadius", "0");
        world.setSpawnPoint(new BlockPos(8, 65, 8));
        world.install(0, 0);
        EntityPlayerMP player = new PosePlayer(server, world, id, afterJump);
        serverPlayers.add(player);
        new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), player);
        return player;
    }

    static void updateNativePose(EntityPlayerMP player) {
        ((PosePlayer) player).updateSize();
    }

    private static final class PosePlayer extends EntityPlayerMP {
        private final Consumer<EntityPlayerMP> afterJump;

        private PosePlayer(MinecraftServer server, WorldServer world, UUID id, Consumer<EntityPlayerMP> afterJump) {
            super(server, world, new GameProfile(id, "native-move-fixture"), new PlayerInteractionManager(world));
            this.afterJump = afterJump;
        }

        @Override public void updateSize() { super.updateSize(); }

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
    }

    void install(BootQueryFootprint footprint) {
        for (BootQueryFootprint.Column column : footprint.columns()) world.install(column.x(), column.z());
    }

    void installColumn(int x, int z) { world.install(x, z); }

    void put(BlockPos cell, IBlockState state) {
        world.put(cell, state);
    }

    void removeColumn(int chunkX, int chunkZ) {
        world.remove(chunkX, chunkZ);
    }

    @Override public void close() {
        try {
            for (EntityPlayerMP player : serverPlayers) player.getAdvancements().dispose();
        } finally {
            serverPlayers.clear();
            try {
                release(server, world);
            } finally {
                server.getPlayerUsageSnooper().stopSnooper();
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

    private static final class TestWorld extends WorldServer {
        private static final ThreadLocal<File> CHUNK_ROOT = new ThreadLocal<>();
        private final Map<Entity, Integer> positionUpdates = new IdentityHashMap<>();

        @Override
        public void updateEntityWithOptionalForce(Entity entity, boolean forceUpdate) {
            positionUpdates.merge(entity, 1, Integer::sum);
            super.updateEntityWithOptionalForce(entity, forceUpdate);
        }

        private static TestWorld open(MinecraftServer server, File chunkRoot) {
            CHUNK_ROOT.set(chunkRoot);
            try {
                return new TestWorld(server);
            } finally {
                CHUNK_ROOT.remove();
            }
        }

        private TestWorld(MinecraftServer server) {
            super(server, fixtureSaveHandler(), new WorldInfo(new NBTTagCompound()), 0, new Profiler());
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
            return new ChunkProviderServer(this, new AnvilChunkLoader(chunkRoot, getMinecraftServer().getDataFixer()), null);
        }

        private void install(int chunkX, int chunkZ) {
            if (getChunkProvider().getLoadedChunk(chunkX, chunkZ) != null) return;
            Chunk chunk = new Chunk(this, chunkX, chunkZ);
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

        private TestServer(File dataDirectory, boolean dedicated) {
            super(dataDirectory, Proxy.NO_PROXY, new DataFixer(0), null, null, null, null);
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
