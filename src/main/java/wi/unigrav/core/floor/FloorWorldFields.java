package wi.unigrav.core.floor;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.util.math.BlockPos;
import net.minecraft.server.MinecraftServer;
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
import wi.unigrav.core.config.FloorConfigLoader.CompiledFloorConfig;
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
    private static final Comparator<FloorFieldIndex.Source> DEBUG_SOURCE_ORDER = Comparator
            .comparingInt(FloorFieldIndex.Source::x)
            .thenComparingInt(FloorFieldIndex.Source::y)
            .thenComparingInt(FloorFieldIndex.Source::z)
            .thenComparingInt(source -> source.face().ordinal());
    private static final Map<World, WorldFields> LOCAL_TEST_BY_WORLD = new IdentityHashMap<>();
    private static final java.util.Set<World> LOCAL_TEST_OWNERS = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile FloorRuntime RUNTIME;
    private static volatile MinecraftServer STARTING_SERVER;

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
        return captureDecision(world).flatMap(decision -> decision.sample(position, magneticResponse));
    }

    /** Forces and standing strengths from one refresh and traversal, with the same availability as {@link #sample}. */
    public static Optional<StandingSample> sampleForStanding(World world, Vec3 position, double magneticResponse) {
        requireServerThread(world);
        requireQuery(position, magneticResponse);
        return captureDecision(world).flatMap(decision -> decision.sampleForStanding(position, magneticResponse));
    }

    /** Captures one active root and its mutation/lifecycle fence for all sampler decisions. */
    public static Optional<FloorDecision> captureDecision(World world) {
        requireServerThread(world);
        if (!(world instanceof WorldServer serverWorld)) return Optional.empty();
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            return Optional.ofNullable(runtime.captureDecision(serverWorld));
        }
        if (STARTING_SERVER != null) return Optional.empty();
        if (deferred(world)) return Optional.empty();
        WorldFields fields = LOCAL_TEST_BY_WORLD.get(world);
        if (fields == null) return Optional.empty();
        fields.flush(false);
        if (fields.hasPending() || deferred(world) || LOCAL_TEST_BY_WORLD.get(world) != fields) return Optional.empty();
        ActiveBootRules.Snapshot localRules = ActiveBootRules.core().snapshot();
        return Optional.of(new FloorDecision(null, null, serverWorld, fields, localRules,
                MagneticResponseRules.builtIn(), 0L, 0L, true));
    }

    /** Server-thread-only, bounded immutable visualization; never shares the mutable index with a renderer. */
    public static Optional<FloorDebugSnapshot> debugSnapshot(World world, Vec3 center) {
        requireServerThread(world);
        requireQuery(center, 1);
        return captureDecision(world).flatMap(decision -> decision.debugSnapshot(center));
    }

    /** Releases world/chunk references on the normal server shutdown thread. */
    public static void clearAll() {
        if (RUNTIME != null || STARTING_SERVER != null) {
            throw new IllegalStateException("clearAll is local-test compatibility only; stop the exact server lifetime");
        }
        for (World world : LOCAL_TEST_BY_WORLD.keySet()) {
            requireServerThread(world);
        }
        for (WorldFields fields : LOCAL_TEST_BY_WORLD.values()) {
            fields.close();
        }
        LOCAL_TEST_BY_WORLD.clear();
    }

    public static synchronized ServerLifetime start(MinecraftServer server, CompiledFloorConfig initial) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(initial, "initial");
        if (RUNTIME != null) throw new IllegalStateException("a floor server lifetime is already active");
        if (STARTING_SERVER != null) throw new IllegalStateException("a floor server lifetime is already initializing");
        if (!LOCAL_TEST_BY_WORLD.isEmpty()) {
            throw new IllegalStateException("local-test floor owners must be retired before a server lifetime starts");
        }
        // A world previously used by a local test fixture must never become a new local owner
        // after this exact production lifetime stops and emits a late lifecycle event.
        LOCAL_TEST_OWNERS.clear();
        STARTING_SERVER = server;
        try {
            FloorRuntime runtime = FloorRuntime.create(server, initial);
            if (STARTING_SERVER != server || RUNTIME != null || !runtime.isReadyForPublication()) {
                throw new IllegalStateException("floor runtime initialization lost its exact server lifetime fence");
            }
            RUNTIME = runtime;
            STARTING_SERVER = null;
            return runtime.lifetime();
        } finally {
            if (STARTING_SERVER == server) STARTING_SERVER = null;
        }
    }

    public static synchronized boolean stop(MinecraftServer server, ServerLifetime lifetime) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(lifetime, "lifetime");
        FloorRuntime runtime = RUNTIME;
        if (runtime == null || runtime.server() != server || runtime.lifetime() != lifetime) return false;
        runtime.requireStopOwner(server, lifetime);
        if (RUNTIME != runtime) return false;
        try {
            return runtime.stop(server, lifetime);
        } finally {
            if (RUNTIME == runtime && runtime.isRetired()) RUNTIME = null;
        }
    }

    static FloorRuntime currentRuntime() {
        return RUNTIME;
    }

    static boolean isRuntimeCurrent(FloorRuntime runtime) {
        return RUNTIME == runtime;
    }

    static boolean isStartingServer(MinecraftServer server) {
        return STARTING_SERVER == server;
    }

    static synchronized void registerLocalTestOwner(WorldServer world) {
        Objects.requireNonNull(world, "world");
        requireServerThread(world);
        if (RUNTIME != null || STARTING_SERVER != null) {
            throw new IllegalStateException("local-test floor ownership cannot overlap a server generation");
        }
        LOCAL_TEST_OWNERS.add(world);
    }

    static synchronized void unregisterLocalTestOwner(WorldServer world) {
        Objects.requireNonNull(world, "world");
        requireServerThread(world);
        LOCAL_TEST_OWNERS.remove(world);
        WorldFields fields = LOCAL_TEST_BY_WORLD.remove(world);
        if (fields != null) fields.close();
    }

    public static FloorRuntime.ReloadReceipt reloadFloors(MinecraftServer server, java.nio.file.Path configFile) {
        FloorRuntime runtime = RUNTIME;
        if (runtime == null || runtime.server() != server) {
            throw new IllegalStateException("no floor configuration runtime belongs to this server");
        }
        return runtime.reload(configFile);
    }

    public static boolean isLocalOwnerCurrent(WorldServer world, WorldFields fields) {
        return RUNTIME == null && STARTING_SERVER == null && LOCAL_TEST_BY_WORLD.get(world) == fields && !fields.closed;
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
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            if (world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) runtime.markDirty(chunk, position);
        } else {
            rejectLocalFallbackDuringStart("block mutation");
            if (isLocalTestOwner(world)) {
                WorldFields fields = LOCAL_TEST_BY_WORLD.get(world);
                if (fields != null) {
                    fields.markDirty(key(chunk), chunk, position.getX(), position.getY(), position.getZ());
                }
            }
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
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            if (world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) runtime.onChunkLoad(serverWorld, chunk);
            return;
        }
        rejectLocalFallbackDuringStart("chunk load");
        if (!isLocalTestOwner(world)) return;
        WorldFields fields = LOCAL_TEST_BY_WORLD.computeIfAbsent(world, ignored -> new WorldFields());
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
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            if (world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) runtime.onChunkUnload(serverWorld, event.getChunk());
            return;
        }
        rejectLocalFallbackDuringStart("chunk unload");
        if (!isLocalTestOwner(world)) return;
        WorldFields fields = LOCAL_TEST_BY_WORLD.get(world);
        if (fields != null) {
            fields.unload(key(event.getChunk()), event.getChunk(), deferred(world));
        }
    }

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isServerWorld(event.world)) {
            return;
        }
        requireServerThread(event.world);
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            if (event.world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) runtime.onWorldTick(serverWorld);
        } else {
            rejectLocalFallbackDuringStart("world tick");
            if (!isLocalTestOwner(event.world)) return;
            WorldFields fields = LOCAL_TEST_BY_WORLD.get(event.world);
            if (fields != null) fields.flush(deferred(event.world));
        }
    }

    @SubscribeEvent
    public static void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (!isServerWorld(world)) return;
        requireServerThread(world);
        FloorRuntime runtime = RUNTIME;
        if (runtime != null && world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) {
            runtime.onWorldLoad(serverWorld);
        } else if (runtime == null) {
            rejectLocalFallbackDuringStart("world load");
        }
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (!isServerWorld(world)) {
            return;
        }
        requireServerThread(world);
        FloorRuntime runtime = RUNTIME;
        if (runtime != null) {
            if (world instanceof WorldServer serverWorld && runtime.owns(serverWorld)) runtime.onWorldUnload(serverWorld);
            return;
        }
        rejectLocalFallbackDuringStart("world unload");
        if (!isLocalTestOwner(world)) return;
        WorldFields fields = LOCAL_TEST_BY_WORLD.remove(world);
        if (fields != null) {
            fields.close();
        }
    }

    private static boolean deferred(World world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }

    private static void rejectLocalFallbackDuringStart(String operation) {
        if (STARTING_SERVER != null) {
            throw new IllegalStateException("floor " + operation + " reentered before the server generation was published");
        }
    }

    private static boolean isLocalTestOwner(World world) {
        return STARTING_SERVER == null && LOCAL_TEST_OWNERS.contains(world);
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
        private final FloorFieldIndex index;
        private final Map<ChunkKey, ChunkState> chunks = new HashMap<>();
        private final Set<ChunkKey> pending = new LinkedHashSet<>();
        private boolean closed;
        private long detachedDirtyBatchCount;

        WorldFields() {
            this(new FloorFieldIndex());
        }

        WorldFields(FloorFieldIndex index) {
            this.index = Objects.requireNonNull(index, "index");
        }

        void load(ChunkKey key, Object owner, Supplier<FloorChunkSources> reader, boolean deferred) {
            if (closed) {
                throw new IllegalStateException("cannot load a chunk into closed floor fields");
            }
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(reader, "reader");
            ChunkState previous = chunks.get(key);
            if (previous != null) {
                previous.unloadRequested = true;
                pending.add(key);
                finishUnload(key, previous);
            }
            chunks.put(key, new ChunkState(owner, reader));
            pending.add(key);
            flush(deferred);
        }

        void unload(ChunkKey key, Object owner) {
            unload(key, owner, false);
        }

        void unload(ChunkKey key, Object owner, boolean deferred) {
            ChunkState current = chunks.get(key);
            if (current != null && current.owner == owner) {
                current.unloadRequested = true;
                pending.add(key);
                flush(deferred);
            }
        }

        void markDirty(ChunkKey key, Object owner, int x, int y, int z) {
            ChunkState current = chunks.get(key);
            if (closed || current == null || current.owner != owner || y < 0 || y >= 256
                    || current.unloadRequested
                    || Math.floorDiv(x, 16) != key.x() || Math.floorDiv(z, 16) != key.z()) {
                return;
            }
            int localX = Math.floorMod(x, 16);
            int localZ = Math.floorMod(z, 16);
            current.markDirty(y * 256 + localZ * 16 + localX);
            pending.add(key);
        }

        void flush(boolean deferred) {
            if (closed || deferred || pending.isEmpty()) {
                return;
            }
            WorkItem[] work = new WorkItem[pending.size()];
            int workCount = 0;
            for (ChunkKey key : pending) {
                ChunkState state = chunks.get(key);
                if (state != null) {
                    DirtyBank dirty = state.unloadRequested ? null : state.detachDirty();
                    if (dirty != null) {
                        detachedDirtyBatchCount = Math.incrementExact(detachedDirtyBatchCount);
                    }
                    work[workCount++] = new WorkItem(key, state, dirty);
                }
            }
            pending.clear();
            for (int workIndex = 0; workIndex < workCount; workIndex++) {
                WorkItem item = work[workIndex];
                if (!isCurrentState(item.key(), item.state())) {
                    discard(item.dirty());
                    continue;
                }
                try {
                    if (item.state().unloadRequested) {
                        finishUnload(item.key(), item.state());
                    } else {
                        refreshChunk(item.key(), item.state(), item.dirty());
                    }
                    if (!isCurrentState(item.key(), item.state())) {
                        discard(item.dirty());
                        continue;
                    }
                    discard(item.dirty());
                    if (item.state().hasDirty()) {
                        pending.add(item.key());
                    }
                    discard(item.dirty());
                } catch (RuntimeException | Error failure) {
                    for (int failedIndex = workIndex; failedIndex < workCount; failedIndex++) {
                        preserveFailedWork(work[failedIndex], failure);
                    }
                    throw failure;
                }
            }
        }

        private void preserveFailedWork(WorkItem item, Throwable failure) {
            boolean current = false;
            try {
                current = isCurrentState(item.key(), item.state());
            } catch (Throwable cleanupFailure) {
                addSuppressed(failure, cleanupFailure);
            }
            if (current) {
                try {
                    restoreDirty(item.state(), item.dirty());
                } catch (Throwable cleanupFailure) {
                    addSuppressed(failure, cleanupFailure);
                }
                try {
                    pending.add(item.key());
                } catch (Throwable cleanupFailure) {
                    addSuppressed(failure, cleanupFailure);
                }
            } else {
                try {
                    discard(item.dirty());
                } catch (Throwable cleanupFailure) {
                    addSuppressed(failure, cleanupFailure);
                }
            }
        }

        private static void addSuppressed(Throwable failure, Throwable cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }

        private void refreshChunk(ChunkKey key, ChunkState state, DirtyBank dirty) {
            if (state.sources == null) {
                FloorChunkSources scanner = Objects.requireNonNull(state.reader.get(), "floor source reader result");
                if (!isCurrentState(key, state)) {
                    return;
                }
                FloorChunkCoverage coverage = scanner.initialCoverage();
                FloorFieldIndex.PreparedPublication prepared = index.prepareReplacement(key, state.owner, coverage);
                scanner.attachPublishedCoverage(state.owner, prepared.targetView());
                try {
                    if (!isCurrentState(key, state)) {
                        scanner.retirePublishedCoverage(state.owner);
                        return;
                    }
                    index.commitPrepared(prepared);
                } catch (RuntimeException | Error failure) {
                    try {
                        scanner.retirePublishedCoverage(state.owner);
                    } catch (RuntimeException | Error cleanupFailure) {
                        addSuppressed(failure, cleanupFailure);
                    }
                    throw failure;
                }
                state.sources = scanner;
                state.reader = null;
                return;
            }

            FloorChunkSources.Refresh refresh = state.sources.prepareReread(state.owner, dirty);
            if (!isCurrentState(key, state)) {
                return;
            }
            state.sources.validateCommit(state.owner, refresh);
            if (refresh instanceof FloorChunkSources.CoverageDelta delta) {
                FloorFieldIndex.PreparedPublication prepared = index.prepareDelta(key, state.owner, delta);
                if (!isCurrentState(key, state)) {
                    return;
                }
                state.sources.validateCommit(state.owner, refresh);
                index.commitPrepared(prepared);
            }
        }

        private void finishUnload(ChunkKey key, ChunkState state) {
            if (!isCurrentState(key, state) || !state.unloadRequested) {
                return;
            }
            FloorFieldIndex.PreparedPublication prepared = index.prepareRemoval(key, state.owner);
            if (prepared != null) {
                index.commitPrepared(prepared);
            }
            if (state.sources != null) {
                state.sources.retirePublishedCoverage(state.owner);
            }
            if (chunks.get(key) == state) {
                chunks.remove(key);
            }
            pending.remove(key);
            state.releaseDirtyBanks();
        }

        private boolean isCurrentState(ChunkKey key, ChunkState state) {
            return !closed && chunks.get(key) == state && state.owner != null;
        }

        private void restoreDirty(ChunkState state, DirtyBank detached) {
            if (detached != null) {
                state.mergeIntoActive(detached);
            }
        }

        private static void discard(DirtyBank dirty) {
            if (dirty != null) {
                dirty.clear();
            }
        }

        long detachedDirtyBatchCount() {
            return detachedDirtyBatchCount;
        }

        int chunkCount() {
            return chunks.size();
        }

        boolean isClosed() {
            return closed;
        }

        boolean ownsChunk(ChunkKey key, Object owner) {
            ChunkState state = chunks.get(key);
            return !closed && state != null && state.owner == owner && !state.unloadRequested;
        }

        boolean matchesChunkOwners(java.util.List<Chunk> expected) {
            if (closed || !pending.isEmpty() || chunks.size() != expected.size()) return false;
            Set<Chunk> owners = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (ChunkState state : chunks.values()) {
                if (state.unloadRequested || !(state.owner instanceof Chunk chunk) || !owners.add(chunk)) return false;
            }
            if (owners.size() != expected.size()) return false;
            for (Chunk chunk : expected) if (!owners.remove(chunk)) return false;
            return owners.isEmpty();
        }

        boolean hasPending() {
            return !pending.isEmpty();
        }

        FloorChunkCoverage publishedCoverage(ChunkKey key, Object owner) {
            return index.coverageFor(key, owner);
        }

        long publicationRevision(ChunkKey key, Object owner) {
            return index.revisionFor(key, owner);
        }

        FloorFieldIndex.RetainedCounts retainedCounts() {
            return index.retainedCounts();
        }

        Optional<Sample> sample(Vec3 position, double response, boolean deferred) {
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sample(position, response)) : Optional.empty();
        }

        Optional<StandingSample> sampleForStanding(Vec3 position, double response, boolean deferred) {
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sampleForStanding(position, response)) : Optional.empty();
        }

        Optional<StandingSample> sampleForStanding(Vec3 position, double response, boolean deferred,
                EnvironmentObservationWork work) {
            Objects.requireNonNull(work).requireCurrent();
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sampleForStanding(position, response, work)) : Optional.empty();
        }

        Optional<Sample> sample(Vec3 position, double response, boolean deferred,
                EnvironmentObservationWork work) {
            Objects.requireNonNull(work).requireCurrent();
            requireQuery(position, response);
            return prepareSample(deferred) ? Optional.of(index.sample(position, response, work)) : Optional.empty();
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
                if (chunk == null || chunk.sources == null) continue;
                FloorChunkCoverage coverage = chunk.sources.committedCoverage();
                sources.addAll(coverage.nearbySources(center, 33.0, 1024 - sources.size()));
                if (sources.size() > 1024) {
                    return Optional.empty();
                }
            }
            sources.sort(DEBUG_SOURCE_ORDER);
            return Optional.of(FloorDebugSnapshot.sample(sources, center));
        }

        void close() {
            closed = true;
            RuntimeException runtimeFailure = null;
            Error errorFailure = null;
            try {
                index.close();
            } catch (RuntimeException failure) {
                runtimeFailure = failure;
            } catch (Error failure) {
                errorFailure = failure;
            }
            for (ChunkState state : chunks.values()) {
                if (state.sources != null) {
                    try {
                        state.sources.retirePublishedCoverage(state.owner);
                    } catch (RuntimeException failure) {
                        if (runtimeFailure == null) runtimeFailure = failure;
                        else runtimeFailure.addSuppressed(failure);
                    } catch (Error failure) {
                        if (errorFailure == null) errorFailure = failure;
                        else errorFailure.addSuppressed(failure);
                    }
                }
                try {
                    state.releaseDirtyBanks();
                } catch (RuntimeException failure) {
                    if (runtimeFailure == null) runtimeFailure = failure;
                    else runtimeFailure.addSuppressed(failure);
                } catch (Error failure) {
                    if (errorFailure == null) errorFailure = failure;
                    else errorFailure.addSuppressed(failure);
                }
            }
            chunks.clear();
            pending.clear();
            if (errorFailure != null) {
                if (runtimeFailure != null) errorFailure.addSuppressed(runtimeFailure);
                throw errorFailure;
            }
            if (runtimeFailure != null) throw runtimeFailure;
        }
    }

    private static final class ChunkState {
        private final Object owner;
        private Supplier<FloorChunkSources> reader;
        private FloorChunkSources sources;
        private final DirtyBank[] dirtyBanks = new DirtyBank[2];
        private int activeDirtyBank;
        private boolean unloadRequested;

        private ChunkState(Object owner, Supplier<FloorChunkSources> reader) {
            this.owner = owner;
            this.reader = reader;
        }

        private void markDirty(int encoded) {
            DirtyBank active = dirtyBanks[activeDirtyBank];
            if (active == null) {
                active = new DirtyBank();
                dirtyBanks[activeDirtyBank] = active;
            }
            active.add(encoded);
        }

        private DirtyBank detachDirty() {
            DirtyBank active = dirtyBanks[activeDirtyBank];
            if (active == null || active.isEmpty()) {
                return null;
            }
            int nextIndex = activeDirtyBank ^ 1;
            DirtyBank next = dirtyBanks[nextIndex];
            if (next == null) {
                next = new DirtyBank();
                dirtyBanks[nextIndex] = next;
            } else if (!next.isEmpty()) {
                throw new IllegalStateException("floor dirty bank reuse would overwrite pending coordinates");
            }
            activeDirtyBank = nextIndex;
            return active;
        }

        private boolean hasDirty() {
            DirtyBank active = dirtyBanks[activeDirtyBank];
            return active != null && !active.isEmpty();
        }

        private void mergeIntoActive(DirtyBank detached) {
            DirtyBank active = dirtyBanks[activeDirtyBank];
            if (active == null) {
                active = new DirtyBank();
                dirtyBanks[activeDirtyBank] = active;
            }
            active.merge(detached);
            detached.clear();
        }

        private void releaseDirtyBanks() {
            for (int i = 0; i < dirtyBanks.length; i++) {
                if (dirtyBanks[i] != null) {
                    dirtyBanks[i].clear();
                    dirtyBanks[i] = null;
                }
            }
        }
    }

    static final class DirtyBank {
        private static final int WORDS = 65_536 / Long.SIZE;
        private long[] words;
        private int wordsInUse;

        void add(int encoded) {
            if (encoded < 0 || encoded >= 65_536) {
                throw new IllegalArgumentException("dirty floor index must be in [0, 65536)");
            }
            if (words == null) {
                words = new long[WORDS];
            }
            int wordIndex = encoded >>> 6;
            words[wordIndex] |= 1L << (encoded & 63);
            wordsInUse = Math.max(wordsInUse, wordIndex + 1);
        }

        boolean isEmpty() {
            return wordsInUse == 0;
        }

        int wordCount() {
            return wordsInUse;
        }

        long wordAt(int index) {
            if (index < 0 || index >= wordsInUse || words == null) {
                return 0L;
            }
            return words[index];
        }

        private void merge(DirtyBank other) {
            if (other.wordsInUse == 0) {
                return;
            }
            if (words == null) {
                words = new long[WORDS];
            }
            for (int i = 0; i < other.wordsInUse; i++) {
                words[i] |= other.words[i];
            }
            wordsInUse = Math.max(wordsInUse, other.wordsInUse);
        }

        private void clear() {
            if (words != null) {
                Arrays.fill(words, 0, wordsInUse, 0L);
            }
            wordsInUse = 0;
        }
    }

    private record WorkItem(ChunkKey key, ChunkState state, DirtyBank dirty) {
    }
}
