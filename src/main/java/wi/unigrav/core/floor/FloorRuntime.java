package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import wi.unigrav.core.config.FloorConfigLoader;
import wi.unigrav.core.config.FloorConfigLoader.CompiledFloorConfig;
import wi.unigrav.core.config.FloorConfigSpec;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;

/** Server-thread owner for atomic, loaded-world floor configuration generations. */
public final class FloorRuntime {
    public static final class ReloadFailure extends RuntimeException {
        private final String stage;

        ReloadFailure(String stage, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
        }

        public String stage() {
            return stage;
        }
    }

    public static final class PublishedRetirementFailure extends RuntimeException {
        private final long publishedRevision;

        PublishedRetirementFailure(long publishedRevision, Throwable cause) {
            super("floor revision " + publishedRevision + " is active, but prior generation retirement failed", cause);
            this.publishedRevision = publishedRevision;
        }

        public long publishedRevision() {
            return publishedRevision;
        }
    }

    public static final class PreparedFloorReload {
        private final FloorRuntime runtime;
        private final ServerLifetime owner;
        private final ActiveFloorGeneration expected;
        private final ActiveFloorGeneration candidate;
        private final RosterSnapshot roster;
        private final long mutationEpoch;
        private final long rosterEpoch;
        private boolean completed;

        private PreparedFloorReload(FloorRuntime runtime, ServerLifetime owner, ActiveFloorGeneration expected,
                                    ActiveFloorGeneration candidate, RosterSnapshot roster,
                                    long mutationEpoch, long rosterEpoch) {
            this.runtime = runtime;
            this.owner = owner;
            this.expected = expected;
            this.candidate = candidate;
            this.roster = roster;
            this.mutationEpoch = mutationEpoch;
            this.rosterEpoch = rosterEpoch;
        }

        public long revision() {
            return candidate.configurationRevision();
        }

        public int worldCount() {
            return candidate.worlds().size();
        }

        public int chunkCount() {
            int count = 0;
            for (FloorWorldFields.WorldFields fields : candidate.worlds().values()) {
                count = Math.addExact(count, fields.chunkCount());
            }
            return count;
        }
    }

    public record ReloadReceipt(long revision, int worlds, int loadedChunks) {
    }

    private record WorldRoster(WorldServer world, MinecraftServer observedOwner, List<Chunk> chunks) {
        private WorldRoster {
            chunks = List.copyOf(chunks);
        }
    }

    private record RosterSnapshot(List<WorldServer> serverWorlds, List<WorldRoster> loadedWorlds) {
        private RosterSnapshot {
            serverWorlds = List.copyOf(serverWorlds);
            loadedWorlds = List.copyOf(loadedWorlds);
        }
    }

    private final MinecraftServer server;
    private final ServerLifetime lifetime;
    private volatile ActiveFloorGeneration current;
    private long mutationEpoch;
    private long rosterEpoch;

    private FloorRuntime(MinecraftServer server, CompiledFloorConfig initial) {
        this.server = Objects.requireNonNull(server, "server");
        this.lifetime = new ServerLifetime(server);
        requireServerThread();
        this.current = buildInitialGeneration(initial);
    }

    static FloorRuntime create(MinecraftServer server, CompiledFloorConfig initial) {
        return new FloorRuntime(server, initial);
    }

    public MinecraftServer server() {
        return server;
    }

    public ServerLifetime lifetime() {
        return lifetime;
    }

    public ActiveFloorGeneration generation() {
        return current;
    }

    public boolean owns(WorldServer world) {
        return lifetime.owns(world);
    }

    boolean isReadyForPublication() {
        ActiveFloorGeneration generation = current;
        return lifetime.isActive() && generation != null && generation.owner() == lifetime;
    }

    public long nextRevision() {
        ActiveFloorGeneration active = requireGeneration();
        return Math.incrementExact(active.configurationRevision());
    }

    public FloorDecision captureDecision(WorldServer world) {
        requireWorldThread(world);
        boolean ownerObserved = lifetime.owns(world);
        if (!ownerObserved || world.isRemote || deferred(world)) {
            return null;
        }
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields fields = generation.fields(world);
        if (fields == null) return null;
        fields.flush(false);
        long capturedMutationEpoch = mutationEpoch;
        long capturedRosterEpoch = rosterEpoch;
        boolean finalOwnerObserved = lifetime.owns(world);
        if (!finalOwnerObserved || !FloorWorldFields.isRuntimeCurrent(this) || !lifetime.isActive()
                || current != generation || generation.owner() != lifetime || generation.fields(world) != fields
                || fields.isClosed() || fields.hasPending() || mutationEpoch != capturedMutationEpoch
                || rosterEpoch != capturedRosterEpoch || deferred(world)) return null;
        return new FloorDecision(this, generation, world, fields, generation.bootRules(),
                generation.passiveResponses(), capturedMutationEpoch, capturedRosterEpoch, false);
    }

    public boolean isCurrent(FloorDecision decision, ActiveFloorGeneration generation, WorldServer world,
                             FloorWorldFields.WorldFields fields, long expectedMutationEpoch,
                             long expectedRosterEpoch) {
        boolean ownerObserved = lifetime.owns(world);
        return decision != null && decision.belongsTo(this, generation, world, fields,
                expectedMutationEpoch, expectedRosterEpoch) && ownerObserved
                && FloorWorldFields.isRuntimeCurrent(this) && current == generation
                && generation.owner() == lifetime && lifetime.isActive() && generation.fields(world) == fields
                && !fields.isClosed() && mutationEpoch == expectedMutationEpoch && rosterEpoch == expectedRosterEpoch
                && !world.captureBlockSnapshots && !world.restoringBlockSnapshots;
    }

    void markDirty(Chunk chunk, BlockPos position) {
        WorldServer world = requireChunkWorld(chunk);
        requireWorldThread(world);
        if (!owns(world)) return;
        mutationEpoch = Math.incrementExact(mutationEpoch);
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields fields = generation.fields(world);
        if (fields != null) fields.markDirty(new ChunkKey(chunk.x, chunk.z), chunk,
                position.getX(), position.getY(), position.getZ());
    }

    void onChunkLoad(WorldServer world, Chunk chunk) {
        requireWorldThread(world);
        if (!owns(world) || !chunk.isLoaded() || chunk.getWorld() != world) return;
        rosterEpoch = Math.incrementExact(rosterEpoch);
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields fields = generation.fields(world);
        if (fields == null) {
            long expectedMutation = mutationEpoch;
            long expectedRoster = rosterEpoch;
            List<Chunk> loaded = new ArrayList<>(loadedChunks(world));
            boolean found = false;
            for (Chunk currentChunk : loaded) if (currentChunk == chunk) found = true;
            if (!found) loaded.add(chunk);
            fields = scanWorld(world, generation.profiles(), loaded, deferred(world));
            boolean loadedMatches = sameWorldChunks(world, loaded);
            if (!loadedMatches || !pureFence(generation, expectedMutation, expectedRoster)
                    || deferred(world) || !fields.matchesChunkOwners(loaded)) {
                ReloadFailure stale = new ReloadFailure("chunk-load-fence",
                        "server root or loaded-world mutation changed while a new world index was built", null);
                retireCandidateWorlds(Map.of(world, fields), stale);
                throw stale;
            }
            current = generation.withWorld(world, fields);
            return;
        }
        FloorWorldFields.WorldFields owner = fields;
        long expectedMutation = mutationEpoch;
        long expectedRoster = rosterEpoch;
        List<Chunk> expectedChunks = new ArrayList<>(loadedChunks(world));
        boolean includesChunk = false;
        for (Chunk loaded : expectedChunks) if (loaded == chunk) includesChunk = true;
        if (!includesChunk) expectedChunks.add(chunk);
        fields.load(new ChunkKey(chunk.x, chunk.z), chunk,
                () -> FloorChunkSources.scanExisting(chunk.x, chunk.z, chunk::getBlockStorageArray,
                        generation.profiles()), deferred(world));
        boolean loadedMatches = sameWorldChunks(world, expectedChunks);
        boolean ownerObservedAfterScan = lifetime.owns(world);
        if (!loadedMatches || !ownerObservedAfterScan || !pureFence(generation, expectedMutation, expectedRoster)
                || deferred(world) || owner.isClosed() || owner.hasPending()
                || !owner.ownsChunk(new ChunkKey(chunk.x, chunk.z), chunk)) {
            throw new ReloadFailure("chunk-load-fence",
                    "loaded chunk owner or server generation changed while an existing world index was updated", null);
        }
    }

    void onChunkUnload(WorldServer world, Chunk chunk) {
        requireWorldThread(world);
        if (!owns(world) || chunk.getWorld() != world) return;
        rosterEpoch = Math.incrementExact(rosterEpoch);
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields fields = generation.fields(world);
        if (fields != null) fields.unload(new ChunkKey(chunk.x, chunk.z), chunk, deferred(world));
    }

    void onWorldLoad(WorldServer world) {
        requireWorldThread(world);
        if (!owns(world) || world.isRemote) return;
        rosterEpoch = Math.incrementExact(rosterEpoch);
        ActiveFloorGeneration generation = requireGeneration();
        long expectedMutation = mutationEpoch;
        long expectedRoster = rosterEpoch;
        List<Chunk> loaded = loadedChunks(world);
        FloorWorldFields.WorldFields replacement = scanWorld(world, generation.profiles(), loaded,
                deferred(world));
        boolean loadedMatches = sameWorldChunks(world, loaded);
        if (!loadedMatches || !pureFence(generation, expectedMutation, expectedRoster)
                || deferred(world) || !replacement.matchesChunkOwners(loaded)) {
            ReloadFailure stale = new ReloadFailure("world-load-fence",
                    "server root or loaded-world mutation changed while a world index was built", null);
            retireCandidateWorlds(Map.of(world, replacement), stale);
            throw stale;
        }
        ActiveFloorGeneration next = generation.withWorld(world, replacement);
        current = next;
        if (next != generation) {
            FloorWorldFields.WorldFields previous = generation.fields(world);
            if (previous != null && previous != replacement) retireWorldAfterPublish(generation, previous);
        }
    }

    void onWorldUnload(WorldServer world) {
        requireWorldThread(world);
        if (!owns(world) || world.isRemote) return;
        rosterEpoch = Math.incrementExact(rosterEpoch);
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields removed = generation.fields(world);
        if (removed == null) return;
        current = generation.withoutWorld(world);
        retireWorldAfterPublish(generation, removed);
    }

    void onWorldTick(WorldServer world) {
        requireWorldThread(world);
        if (!owns(world) || world.isRemote) return;
        ActiveFloorGeneration generation = requireGeneration();
        FloorWorldFields.WorldFields fields = generation.fields(world);
        if (fields != null) fields.flush(deferred(world));
    }

    public PreparedFloorReload prepareReload(CompiledFloorConfig config) {
        Objects.requireNonNull(config, "config");
        requireServerThread();
        ActiveFloorGeneration expected = requireGeneration();
        if (!lifetime.isActive()) throw new ReloadFailure("prepare", "server lifetime is retired", null);
        if (config.revision() <= expected.configurationRevision()) {
            throw new ReloadFailure("prepare", "configuration revision must advance monotonically", null);
        }
        long expectedMutation = mutationEpoch;
        long expectedRoster = rosterEpoch;
        RosterSnapshot before;
        try {
            before = snapshotRoster();
            ensureNotDeferred(before.loadedWorlds());
        } catch (RuntimeException | Error failure) {
            throw new ReloadFailure("prepare-roster", "could not capture exact loaded server world/chunk roster", failure);
        }

        IdentityHashMap<WorldServer, FloorWorldFields.WorldFields> candidateWorlds = new IdentityHashMap<>();
        ActiveFloorGeneration candidate = null;
        try {
            for (WorldRoster roster : before.loadedWorlds()) {
                WorldServer world = roster.world();
                try {
                    FloorWorldFields.WorldFields fields = scanWorld(world, config.floorProfiles(), roster.chunks(), false);
                    candidateWorlds.put(world, fields);
                } catch (RuntimeException | Error failure) {
                    throw new ReloadFailure("prepare-world:" + world.provider.getDimension(),
                            "could not build detached floor index for a loaded world", failure);
                }
            }
            candidate = new ActiveFloorGeneration(lifetime, config, candidateWorlds);
            boolean rosterMatches = sameRoster(before);
            ensureNotDeferred(before.loadedWorlds());
            if (!rosterMatches || !pureFence(expected, expectedMutation, expectedRoster)
                    || !matchesCandidateRoster(candidate, before)) {
                throw new ReloadFailure("prepare-fence", "loaded world/chunk roster or mutation changed during floor preparation", null);
            }
            return new PreparedFloorReload(this, lifetime, expected, candidate, before,
                    expectedMutation, expectedRoster);
        } catch (RuntimeException | Error failure) {
            if (candidate != null) retireCandidate(candidate, failure);
            else retireCandidateWorlds(candidateWorlds, failure);
            throw failure;
        }
    }

    /** The publish point is one root assignment after internal fence checks; no suppliers or logs run before it. */
    public ReloadReceipt commitReload(PreparedFloorReload prepared) {
        Objects.requireNonNull(prepared, "prepared");
        requireServerThread();
        if (prepared.runtime != this || prepared.owner != lifetime || prepared.completed) {
            throw new IllegalArgumentException("prepared floor reload belongs to another runtime or is already consumed");
        }
        ReloadReceipt receipt = new ReloadReceipt(prepared.candidate.configurationRevision(),
                prepared.candidate.worlds().size(), prepared.chunkCount());
        final boolean rosterMatches;
        try {
            rosterMatches = sameRoster(prepared.roster);
        } catch (RuntimeException | Error observationFailure) {
            ReloadFailure stale = new ReloadFailure("commit-roster",
                    "could not reobserve the exact loaded world/chunk roster before publication", observationFailure);
            abortReload(prepared, stale);
            throw stale;
        }
        if (!rosterMatches || !pureFence(prepared.expected, prepared.mutationEpoch, prepared.rosterEpoch)
                || !matchesCandidateRoster(prepared.candidate, prepared.roster)) {
            ReloadFailure stale = new ReloadFailure("commit-fence",
                    "floor reload owner, root, mutation, roster, world flags, or chunk identities changed before publication", null);
            abortReload(prepared, stale);
            throw stale;
        }
        ActiveFloorGeneration previous = current;
        current = prepared.candidate;
        prepared.completed = true;
        try {
            previous.retire();
        } catch (RuntimeException | Error cleanupFailure) {
            throw new PublishedRetirementFailure(receipt.revision(), cleanupFailure);
        }
        return receipt;
    }

    public void abortReload(PreparedFloorReload prepared, Throwable failure) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(failure, "failure");
        requireServerThread();
        if (prepared.runtime != this || prepared.owner != lifetime || prepared.completed) {
            throw new IllegalArgumentException("prepared floor reload belongs to another runtime or is already consumed");
        }
        prepared.completed = true;
        retireCandidate(prepared.candidate, failure);
    }

    public ReloadReceipt reload(Path file) {
        requireServerThread();
        ActiveFloorGeneration before = requireGeneration();
        FloorConfigSpec spec = FloorConfigLoader.parse(file);
        CompiledFloorConfig compiled = FloorConfigLoader.compile(spec, Math.incrementExact(before.configurationRevision()));
        PreparedFloorReload prepared = prepareReload(compiled);
        try {
            return commitReload(prepared);
        } catch (RuntimeException | Error failure) {
            if (!prepared.completed) abortReload(prepared, failure);
            throw failure;
        }
    }

    boolean stop(MinecraftServer expectedServer, ServerLifetime expectedLifetime) {
        if (server != expectedServer || lifetime != expectedLifetime || current == null
                || current.owner() != lifetime || !FloorWorldFields.isRuntimeCurrent(this)) return false;
        requireServerThread();
        ActiveFloorGeneration retiring = current;
        if (server != expectedServer || lifetime != expectedLifetime || !lifetime.isActive()
                || current != retiring || retiring.owner() != lifetime || !FloorWorldFields.isRuntimeCurrent(this)) {
            return false;
        }
        current = null;
        lifetime.retire();
        try {
            retiring.retire();
        } catch (RuntimeException | Error cleanupFailure) {
            throw new PublishedRetirementFailure(retiring.configurationRevision(), cleanupFailure);
        }
        return true;
    }

    boolean isRetired() {
        return current == null && !lifetime.isActive();
    }

    void requireStopOwner(MinecraftServer expectedServer, ServerLifetime expectedLifetime) {
        if (server != expectedServer || lifetime != expectedLifetime || current == null
                || current.owner() != lifetime) {
            throw new IllegalStateException("floor stop does not match the active exact server lifetime");
        }
        requireServerThread();
    }

    private ActiveFloorGeneration buildInitialGeneration(CompiledFloorConfig config) {
        Objects.requireNonNull(config, "config");
        IdentityHashMap<WorldServer, FloorWorldFields.WorldFields> worlds = new IdentityHashMap<>();
        long expectedMutation = mutationEpoch;
        long expectedRoster = rosterEpoch;
        RosterSnapshot roster;
        try {
            roster = snapshotRoster();
            ensureNotDeferred(roster.loadedWorlds());
            for (WorldRoster entry : roster.loadedWorlds()) {
                WorldServer world = entry.world();
                requireWorldThread(world);
                FloorWorldFields.WorldFields fields = scanWorld(world, config.floorProfiles(), entry.chunks(), false);
                worlds.put(world, fields);
            }
            boolean rosterMatches = sameRoster(roster);
            ensureNotDeferred(roster.loadedWorlds());
            ActiveFloorGeneration generation = new ActiveFloorGeneration(lifetime, config, worlds);
            if (!rosterMatches || !pureInitializationFence(expectedMutation, expectedRoster)
                    || !matchesCandidateRoster(generation, roster)) {
                throw new ReloadFailure("initialize-fence",
                        "loaded world/chunk roster or mutation changed during initial floor generation build", null);
            }
            return generation;
        } catch (RuntimeException | Error failure) {
            retireCandidateWorlds(worlds, failure);
            throw new ReloadFailure("initialize", "could not initialize floor generation from loaded server worlds", failure);
        }
    }

    private FloorWorldFields.WorldFields scanWorld(WorldServer world, FloorProfileCatalog profiles,
                                                    Collection<Chunk> chunks, boolean deferred) {
        requireWorldThread(world);
        FloorWorldFields.WorldFields fields = new FloorWorldFields.WorldFields();
        try {
            for (Chunk chunk : chunks) {
                if (chunk == null || !chunk.isLoaded() || chunk.getWorld() != world) continue;
                fields.load(new ChunkKey(chunk.x, chunk.z), chunk,
                        () -> FloorChunkSources.scanExisting(chunk.x, chunk.z, chunk::getBlockStorageArray, profiles),
                        deferred);
            }
            return fields;
        } catch (RuntimeException | Error failure) {
            try {
                fields.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private RosterSnapshot snapshotRoster() {
        List<WorldServer> serverRoster = serverWorlds();
        List<WorldRoster> roster = new ArrayList<>();
        for (WorldServer world : serverRoster) {
            if (world == null) continue;
            MinecraftServer observedOwner = world.getMinecraftServer();
            if (observedOwner != server || !lifetime.isActive() || world.isRemote) continue;
            requireWorldThread(world);
            roster.add(new WorldRoster(world, observedOwner, loadedChunks(world)));
        }
        return new RosterSnapshot(serverRoster, roster);
    }

    private boolean sameRoster(RosterSnapshot expected) {
        RosterSnapshot actual = snapshotRoster();
        if (!sameWorldArray(expected.serverWorlds(), actual.serverWorlds())
                || expected.loadedWorlds().size() != actual.loadedWorlds().size()) return false;
        for (int index = 0; index < expected.loadedWorlds().size(); index++) {
            WorldRoster first = expected.loadedWorlds().get(index);
            WorldRoster second = actual.loadedWorlds().get(index);
            if (first.world() != second.world() || first.observedOwner() != second.observedOwner()
                    || !sameIdentitySet(first.chunks(), second.chunks())) return false;
        }
        return true;
    }

    private static boolean sameWorldArray(List<WorldServer> expected, List<WorldServer> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index) != actual.get(index)) return false;
        }
        return true;
    }

    private static boolean sameIdentitySet(List<Chunk> first, List<Chunk> second) {
        if (first.size() != second.size()) return false;
        Set<Chunk> identity = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        identity.addAll(first);
        for (Chunk chunk : second) if (!identity.remove(chunk)) return false;
        return identity.isEmpty();
    }

    private void ensureNotDeferred(List<WorldRoster> roster) {
        for (WorldRoster entry : roster) {
            if (deferred(entry.world())) {
                throw new ReloadFailure("prepare-deferred", "world capture/restore is active in dimension "
                        + entry.world().provider.getDimension(), null);
            }
        }
    }

    private boolean sameWorldChunks(WorldServer world, List<Chunk> expected) {
        return sameIdentitySet(expected, loadedChunks(world));
    }

    private boolean pureFence(ActiveFloorGeneration expected, long expectedMutation, long expectedRoster) {
        return FloorWorldFields.isRuntimeCurrent(this) && lifetime.isActive()
                && current == expected && expected.owner() == lifetime
                && mutationEpoch == expectedMutation && rosterEpoch == expectedRoster;
    }

    private boolean pureInitializationFence(long expectedMutation, long expectedRoster) {
        return FloorWorldFields.isStartingServer(server) && FloorWorldFields.currentRuntime() == null
                && lifetime.isActive() && mutationEpoch == expectedMutation && rosterEpoch == expectedRoster;
    }

    /** No World/Chunk getter is called here; all potentially reentrant observation ran before this fence. */
    private boolean matchesCandidateRoster(ActiveFloorGeneration generation, RosterSnapshot roster) {
        if (generation.owner() != lifetime || !sameWorldArray(roster.serverWorlds(), liveServerWorldsPure())) return false;
        if (generation.worlds().size() != roster.loadedWorlds().size()) return false;
        for (WorldRoster entry : roster.loadedWorlds()) {
            WorldServer world = entry.world();
            FloorWorldFields.WorldFields fields = generation.fields(world);
            if (entry.observedOwner() != server || fields == null || fields.isClosed() || fields.hasPending()
                    || deferred(world) || !fields.matchesChunkOwners(entry.chunks())) return false;
        }
        return true;
    }

    private List<WorldServer> liveServerWorldsPure() {
        WorldServer[] worlds = server.worlds;
        List<WorldServer> snapshot = new ArrayList<>(worlds.length);
        for (WorldServer world : worlds) if (world != null) snapshot.add(world);
        return snapshot;
    }

    private List<Chunk> loadedChunks(WorldServer world) {
        Collection<Chunk> loaded = world.getChunkProvider().getLoadedChunks();
        List<Chunk> snapshot = new ArrayList<>(loaded.size());
        for (Chunk chunk : loaded) {
            if (chunk != null && chunk.isLoaded() && chunk.getWorld() == world) snapshot.add(chunk);
        }
        return List.copyOf(snapshot);
    }

    private List<WorldServer> serverWorlds() {
        WorldServer[] worlds = server.worlds;
        List<WorldServer> snapshot = new ArrayList<>(worlds.length);
        for (WorldServer world : worlds) if (world != null) snapshot.add(world);
        return List.copyOf(snapshot);
    }

    private void retireWorldAfterPublish(ActiveFloorGeneration published, FloorWorldFields.WorldFields previous) {
        try {
            previous.close();
        } catch (RuntimeException | Error cleanupFailure) {
            throw new PublishedRetirementFailure(published.configurationRevision(), cleanupFailure);
        }
    }

    private static void retireCandidate(ActiveFloorGeneration candidate, Throwable failure) {
        try {
            candidate.retire();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    private static void retireCandidateWorlds(Map<WorldServer, FloorWorldFields.WorldFields> worlds,
                                              Throwable failure) {
        for (FloorWorldFields.WorldFields fields : worlds.values()) {
            try {
                fields.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private ActiveFloorGeneration requireGeneration() {
        ActiveFloorGeneration generation = current;
        if (generation == null) throw new IllegalStateException("floor runtime has no active generation");
        return generation;
    }

    private void requireServerThread() {
        if (!server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("floor runtime mutations require the exact server owner thread");
        }
    }

    private void requireWorldThread(WorldServer world) {
        if (world == null || world.isRemote || world.getMinecraftServer() != server) {
            throw new IllegalArgumentException("floor runtime requires a server world owned by its exact server");
        }
        if (!world.isCallingFromMinecraftThread() || !server.isCallingFromMinecraftThread()) {
            throw new IllegalStateException("floor runtime work requires the server world owner thread");
        }
    }

    private static WorldServer requireChunkWorld(Chunk chunk) {
        if (chunk == null || !(chunk.getWorld() instanceof WorldServer world)) {
            throw new IllegalArgumentException("floor mutation requires a real server chunk");
        }
        return world;
    }

    private static boolean deferred(WorldServer world) {
        return world.captureBlockSnapshots || world.restoringBlockSnapshots;
    }
}
