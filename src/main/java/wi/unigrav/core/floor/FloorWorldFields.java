package wi.unigrav.core.floor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import wi.unigrav.Reference;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;

/**
 * World-thread-owned discovery and sampling of server-side built-in floors.
 * Neither this adapter nor its storage observer applies forces or grants magnetic
 * eligibility. Client prediction must consume a separate authoritative snapshot.
 */
@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class FloorWorldFields {
    private static final FloorFieldProfile BUILT_IN_PROFILE = new FloorFieldProfile(32.0, 8.0, 16.0);
    private static final Map<World, WorldFields> BY_WORLD = new IdentityHashMap<>();

    private FloorWorldFields() {
    }

    /**
     * Samples committed storage with an explicit actor response. Empty means
     * deferred/unavailable, NOT zero gravity: capture/restore is in progress,
     * a reentrant mutation awaits another bounded refresh, or this world has no
     * current discovery context. Callers retain their last valid environment
     * decision rather than interpreting empty as weightlessness.
     */
    public static Optional<Sample> sample(World world, Vec3 position, double magneticResponse) {
        requireServerThread(world);
        requireQuery(position, magneticResponse);
        WorldFields fields = BY_WORLD.get(world);
        return fields == null ? Optional.empty()
                : fields.sample(position, magneticResponse, deferred(world));
    }

    /** Forces and standing strengths from one refresh and traversal, with the same availability as {@link #sample}. */
    public static Optional<StandingSample> sampleForStanding(World world, Vec3 position, double magneticResponse) {
        requireServerThread(world);
        requireQuery(position, magneticResponse);
        WorldFields fields = BY_WORLD.get(world);
        return fields == null ? Optional.empty()
                : fields.sampleForStanding(position, magneticResponse, deferred(world));
    }

    /** Server-thread-only, bounded immutable visualization; never shares the mutable index with a renderer. */
    public static Optional<FloorDebugSnapshot> debugSnapshot(World world, Vec3 center) {
        requireServerThread(world);
        requireQuery(center, 1);
        WorldFields fields = BY_WORLD.get(world);
        return fields == null ? Optional.empty() : fields.debugSnapshot(center, deferred(world));
    }

    /** Releases world/chunk references on the normal server shutdown thread. */
    public static void clearAll() {
        for (World world : BY_WORLD.keySet()) {
            requireServerThread(world);
        }
        for (WorldFields fields : BY_WORLD.values()) {
            fields.close();
        }
        BY_WORLD.clear();
    }

    /** Records coordinates, never the requested state or nullable mutation result. */
    public static void markDirty(Chunk chunk, BlockPos position) {
        if (chunk == null || position == null || !chunk.isLoaded()) {
            return;
        }
        World world = chunk.getWorld();
        if (!isServerWorld(world)) {
            return;
        }
        requireServerThread(world);
        WorldFields fields = BY_WORLD.get(world);
        if (fields != null) {
            fields.markDirty(key(chunk), chunk, position.getX(), position.getY(), position.getZ());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        World world = event.getWorld();
        if (!isServerWorld(world)) {
            return;
        }
        requireServerThread(world);
        Chunk chunk = event.getChunk();
        if (!chunk.isLoaded()) {
            return;
        }
        WorldFields fields = BY_WORLD.computeIfAbsent(world, ignored -> new WorldFields());
        fields.load(key(chunk), chunk,
                () -> FloorChunkSources.scanExisting(chunk.x, chunk.z,
                        chunk::getBlockStorageArray, BUILT_IN_PROFILE), deferred(world));
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        World world = event.getWorld();
        if (!isServerWorld(world)) {
            return;
        }
        requireServerThread(world);
        WorldFields fields = BY_WORLD.get(world);
        if (fields != null) {
            fields.unload(key(event.getChunk()), event.getChunk());
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isServerWorld(event.world)) {
            return;
        }
        requireServerThread(event.world);
        WorldFields fields = BY_WORLD.get(event.world);
        if (fields != null) {
            fields.flush(deferred(event.world));
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!isServerWorld(world)) {
            return;
        }
        requireServerThread(world);
        WorldFields fields = BY_WORLD.remove(world);
        if (fields != null) {
            fields.close();
        }
    }

    private static boolean deferred(World world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }

    private static boolean isServerWorld(World world) {
        return world != null && !world.isRemote;
    }

    private static void requireServerThread(World world) {
        if (!isServerWorld(world) || !(world instanceof WorldServer server)) {
            throw new IllegalArgumentException("floor fields require an authoritative WorldServer");
        }
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("floor discovery and mutation must run on the server world thread");
        }
    }

    private static void requireQuery(Vec3 position, double response) {
        if (position == null || !Double.isFinite(position.x()) || !Double.isFinite(position.y())
                || !Double.isFinite(position.z()) || !Double.isFinite(response) || response < 0.0) {
            throw new IllegalArgumentException("floor query requires finite position and nonnegative response");
        }
    }

    private static ChunkKey key(Chunk chunk) {
        return new ChunkKey(chunk.x, chunk.z);
    }

    /** The production lifecycle seam, without constructing a Minecraft World. */
    static final class WorldFields {
        private final FloorFieldIndex index = new FloorFieldIndex();
        private final Map<ChunkKey, ChunkState> chunks = new HashMap<>();
        private final Set<ChunkKey> pending = new LinkedHashSet<>();
        private boolean closed;

        void load(ChunkKey key, Object owner, Supplier<FloorChunkSources> reader, boolean deferred) {
            if (closed) {
                throw new IllegalStateException("cannot load a chunk into closed floor fields");
            }
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(reader, "reader");
            ChunkState previous = chunks.put(key, new ChunkState(owner, reader));
            if (previous != null) {
                index.removeChunk(key, previous.owner);
            }
            // Replacing this entry also releases the old scanner and dirty set.
            pending.add(key);
            flush(deferred);
        }

        void unload(ChunkKey key, Object owner) {
            ChunkState current = chunks.get(key);
            if (current != null && current.owner == owner) {
                chunks.remove(key);
                pending.remove(key);
                index.removeChunk(key, owner);
            }
        }

        void markDirty(ChunkKey key, Object owner, int x, int y, int z) {
            ChunkState current = chunks.get(key);
            if (closed || current == null || current.owner != owner || y < 0 || y >= 256
                    || Math.floorDiv(x, 16) != key.x() || Math.floorDiv(z, 16) != key.z()) {
                return;
            }
            current.dirty.add(new Position(x, y, z));
            pending.add(key);
        }

        void flush(boolean deferred) {
            if (closed || deferred) {
                return;
            }
            // A bounded detached batch: marks created during a read stay pending.
            Map<ChunkKey, ChunkState> work = new HashMap<>();
            for (ChunkKey key : pending) {
                work.put(key, chunks.get(key));
            }
            pending.clear();
            for (Map.Entry<ChunkKey, ChunkState> entry : work.entrySet()) {
                ChunkKey key = entry.getKey();
                ChunkState state = entry.getValue();
                if (state == null || chunks.get(key) != state) {
                    continue;
                }
                Set<Position> dirty = new HashSet<>(state.dirty);
                state.dirty.clear();
                // Keep pending evidence if storage reading or publication fails.
                pending.add(key);
                try {
                    if (state.sources == null) {
                        state.sources = Objects.requireNonNull(state.reader.get(), "floor source reader result");
                        state.reader = null;
                    }
                    for (Position position : dirty) {
                        state.sources.reread(position.x(), position.y(), position.z());
                    }
                    if (chunks.get(key) != state) {
                        continue;
                    }
                    index.replaceChunk(key, state.owner, state.sources.snapshot());
                    if (state.dirty.isEmpty()) {
                        pending.remove(key);
                    }
                } catch (RuntimeException | Error failure) {
                    if (chunks.get(key) == state) {
                        state.dirty.addAll(dirty);
                        pending.add(key);
                    }
                    throw failure;
                }
            }
        }

        Optional<Sample> sample(Vec3 position, double response, boolean deferred) {
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sample(position, response)) : Optional.empty();
        }

        Optional<StandingSample> sampleForStanding(Vec3 position, double response, boolean deferred) {
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sampleForStanding(position, response)) : Optional.empty();
        }

        private boolean prepareSample(boolean deferred) {
            if (closed || deferred) {
                return false;
            }
            flush(false);
            return !closed && pending.isEmpty();
        }

        Optional<FloorDebugSnapshot> debugSnapshot(Vec3 center, boolean deferred) {
            if (!prepareSample(deferred)) return Optional.empty();
            var sources = new ArrayList<FloorFieldIndex.Source>();
            int cx = Math.floorDiv((int) Math.floor(center.x()), 16);
            int cz = Math.floorDiv((int) Math.floor(center.z()), 16);
            for (int x = cx - 2; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 2; z++) {
                var chunk = chunks.get(new ChunkKey(x, z));
                if (chunk == null) continue;
                for (var source : chunk.sources.snapshot()) {
                    if (Math.abs(source.x() + .5 - center.x()) > 33
                            || Math.abs(source.y() + .5 - center.y()) > 33
                            || Math.abs(source.z() + .5 - center.z()) > 33) continue;
                    sources.add(source);
                    if (sources.size() > 1024) return Optional.empty();
                }
            }
            return Optional.of(FloorDebugSnapshot.sample(sources, center));
        }

        void close() {
            closed = true;
            index.clear();
            chunks.clear();
            pending.clear();
        }
    }

    private static final class ChunkState {
        private final Object owner;
        private Supplier<FloorChunkSources> reader;
        private FloorChunkSources sources;
        private final Set<Position> dirty = new HashSet<>();

        private ChunkState(Object owner, Supplier<FloorChunkSources> reader) {
            this.owner = owner;
            this.reader = reader;
        }
    }

    private record Position(int x, int y, int z) {
    }
}
