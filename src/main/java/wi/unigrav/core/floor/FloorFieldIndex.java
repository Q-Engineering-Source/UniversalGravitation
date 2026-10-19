package wi.unigrav.core.floor;

import java.util.Objects;

import wi.unigrav.core.player.EnvironmentObservationWork;

import java.util.Collections;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.Spliterator;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorChunkCoverage.PlaneGroup;
import wi.unigrav.core.floor.FloorChunkCoverage.SectionCoverage;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

/**
 * Mutable, single-owner sparse index of floor-field sources for one world.
 *
 * <p>Each face routes by one tangent page and an ordered normal slice. A routed
 * slice reads immutable 256-cell coverage instead of retaining a source and
 * emitter object for every occupied block face.</p>
 */
public final class FloorFieldIndex {
    private static final Face[] CANONICAL_FACE_ORDER = Face.values();
    private static final int ALL_FACE_BITS = (1 << CANONICAL_FACE_ORDER.length) - 1;
    private static final CanonicalFaceSetTable CANONICAL_FACE_SETS = buildCanonicalFaceSets();

    /** One directional emitter; one physical block may own two opposite-face sources. */
    public record Source(int x, int y, int z, Face face, Kind kind, FloorFieldProfile profile) {
        public Source {
            if (face == null) {
                throw new IllegalArgumentException("face must not be null");
            }
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
        }
    }

    /** Whether a source contributes ambient gravity or actor-filtered magnetism. */
    public enum Kind {
        GRAVITY,
        MAGNETIC
    }

    /** X/Z chunk identity supplied by the world-owning caller. */
    public record ChunkKey(int x, int z) {
    }

    /** Immutable result of a single point sample. */
    public record Sample(Vec3 gravityAccel, Vec3 magneticAccel,
                         Set<Face> effectiveGravityFaces, Set<Face> effectiveMagneticFaces) {
        public Sample {
            requireFiniteVector(gravityAccel, "gravityAccel");
            requireFiniteVector(magneticAccel, "magneticAccel");
            effectiveGravityFaces = immutableFaceCopy(effectiveGravityFaces, "effectiveGravityFaces");
            effectiveMagneticFaces = immutableFaceCopy(effectiveMagneticFaces, "effectiveMagneticFaces");
        }

        private static Set<Face> immutableFaceCopy(Set<Face> faces, String name) {
            if (faces == null) {
                throw new IllegalArgumentException(name + " must not be null");
            }
            if (faces instanceof CanonicalFaceSet) {
                return faces;
            }
            EnumSet<Face> copy = faces.isEmpty()
                    ? EnumSet.noneOf(Face.class)
                    : EnumSet.copyOf(faces);
            return Collections.unmodifiableSet(copy);
        }
    }

    /** Immutable, privately-created canonical face set used only by indexed samples. */
    private static final class CanonicalFaceSet implements Set<Face> {
        private final Set<Face> delegate;

        private CanonicalFaceSet(Set<Face> delegate) {
            this.delegate = delegate;
        }

        @Override public int size() { return delegate.size(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public boolean contains(Object value) { return delegate.contains(value); }
        @Override public Iterator<Face> iterator() { return delegate.iterator(); }
        @Override public Object[] toArray() { return delegate.toArray(); }
        @Override public <T> T[] toArray(T[] target) { return delegate.toArray(target); }
        @Override public <T> T[] toArray(IntFunction<T[]> generator) { return delegate.toArray(generator); }
        @Override public boolean containsAll(Collection<?> values) { return delegate.containsAll(values); }
        @Override public Spliterator<Face> spliterator() { return delegate.spliterator(); }
        @Override public Stream<Face> stream() { return delegate.stream(); }
        @Override public Stream<Face> parallelStream() { return delegate.parallelStream(); }
        @Override public void forEach(Consumer<? super Face> action) { delegate.forEach(action); }
        @Override public boolean equals(Object other) { return other == this || delegate.equals(other); }
        @Override public int hashCode() { return delegate.hashCode(); }
        @Override public String toString() { return delegate.toString(); }

        @Override public boolean add(Face value) { throw immutable(); }
        @Override public boolean remove(Object value) { throw immutable(); }
        @Override public boolean addAll(Collection<? extends Face> values) { throw immutable(); }
        @Override public boolean retainAll(Collection<?> values) { throw immutable(); }
        @Override public boolean removeAll(Collection<?> values) { throw immutable(); }
        @Override public void clear() { throw immutable(); }
        @Override public boolean removeIf(Predicate<? super Face> filter) { throw immutable(); }

        private static UnsupportedOperationException immutable() {
            return new UnsupportedOperationException("canonical sample face sets are immutable");
        }
    }

    private record CanonicalFaceSetTable(CanonicalFaceSet[] sets, int delegateEnumSetCount,
                                         int unmodifiableDelegateCount, int markerCount) {
    }

    record CanonicalFaceSetCounts(int tableEntries, int delegateEnumSets,
                                  int unmodifiableDelegates, int canonicalWrappers) {
    }

    private static CanonicalFaceSetTable buildCanonicalFaceSets() {
        if (CANONICAL_FACE_ORDER.length != 6) {
            throw new ExceptionInInitializerError("canonical floor face table requires exactly six faces");
        }
        CanonicalFaceSet[] sets = new CanonicalFaceSet[ALL_FACE_BITS + 1];
        int delegateEnumSetCount = 0;
        int unmodifiableDelegateCount = 0;
        int markerCount = 0;
        for (int mask = 0; mask < sets.length; mask++) {
            EnumSet<Face> members = EnumSet.noneOf(Face.class);
            for (int ordinal = 0; ordinal < CANONICAL_FACE_ORDER.length; ordinal++) {
                if ((mask & (1 << ordinal)) != 0) {
                    members.add(CANONICAL_FACE_ORDER[ordinal]);
                }
            }
            delegateEnumSetCount++;
            Set<Face> immutableDelegate = Collections.unmodifiableSet(members);
            unmodifiableDelegateCount++;
            sets[mask] = new CanonicalFaceSet(immutableDelegate);
            markerCount++;
        }
        return new CanonicalFaceSetTable(sets, delegateEnumSetCount,
                unmodifiableDelegateCount, markerCount);
    }

    private static Set<Face> canonicalFaces(int mask) {
        if ((mask & ~ALL_FACE_BITS) != 0) {
            throw new IllegalArgumentException("sample face mask contains unknown face bits: " + mask);
        }
        return CANONICAL_FACE_SETS.sets()[mask];
    }

    static CanonicalFaceSetCounts canonicalFaceSetCounts() {
        CanonicalFaceSetTable table = CANONICAL_FACE_SETS;
        return new CanonicalFaceSetCounts(table.sets().length, table.delegateEnumSetCount(),
                table.unmodifiableDelegateCount(), table.markerCount());
    }

    /** Forces and pre-cancellation standing strengths from one traversal. */
    public static final class StandingSample {
        private final Sample forces;
        private final Map<Face, Double> gravityStrengths;
        private final Map<Face, Double> magneticStrengths;

        private StandingSample(Sample forces, EnumMap<Face, Double> gravityStrengths,
                               EnumMap<Face, Double> magneticStrengths) {
            this.forces = forces;
            this.gravityStrengths = Collections.unmodifiableMap(new EnumMap<>(gravityStrengths));
            this.magneticStrengths = Collections.unmodifiableMap(new EnumMap<>(magneticStrengths));
        }

        public Sample forces() {
            return forces;
        }

        public Map<Face, Double> gravityStrengths() {
            return gravityStrengths;
        }

        public Map<Face, Double> magneticStrengths() {
            return magneticStrengths;
        }
    }

    private final Map<ChunkKey, ChunkView> chunks = new HashMap<>();
    private final EnumMap<Face, Map<PageKey, RoutePage>> routes = new EnumMap<>(Face.class);
    private final NavigableMap<Double, Long> registeredRanges = new TreeMap<>();
    private final Runnable beforeLinearizationHook;
    private final PublicationStageHook publicationStageHook;
    private double maxRegisteredRange;
    private long clearEpoch;
    private long rangeEpoch;
    private boolean closed;
    private boolean invalid;
    private Throwable invalidCause;

    public FloorFieldIndex() {
        this(null);
    }

    /** Internal pre-publication failure seam; production construction leaves it unset. */
    FloorFieldIndex(Runnable beforeLinearizationHook) {
        this(beforeLinearizationHook, null);
    }

    /** Internal staged-publication failure seam; production construction leaves it unset. */
    FloorFieldIndex(Runnable beforeLinearizationHook, PublicationStageHook publicationStageHook) {
        this.beforeLinearizationHook = beforeLinearizationHook;
        this.publicationStageHook = publicationStageHook;
        for (Face face : Face.values()) {
            routes.put(face, new HashMap<>());
        }
    }

    /**
     * Atomically replaces all indexed sources owned by one loaded chunk generation.
     *
     * <p>The supplied batch must contain only sources belonging to {@code key} and
     * no duplicate emitter identities (block position plus face). Validation and
     * coverage compilation finish before the prior generation is changed.</p>
     */
    public void replaceChunk(ChunkKey key, Object generationOwner, List<Source> sources) {
        requireChunkAndOwner(key, generationOwner);
        FloorChunkCoverage coverage = FloorChunkCoverage.compile(key, sources);
        replaceChunkCoverage(key, generationOwner, coverage);
    }

    /** Publishes a prepared immutable scanner snapshot without expanding it back to per-emitter objects. */
    void replaceChunkCoverage(ChunkKey key, Object generationOwner, FloorChunkCoverage coverage) {
        PreparedPublication prepared = prepareReplacement(key, generationOwner, coverage);
        commitPrepared(prepared);
    }

    FloorChunkCoverage coverageFor(ChunkKey key, Object generationOwner) {
        ensureHealthy();
        ChunkView view = chunks.get(key);
        PublishedChunk current = view == null ? null : view.current;
        return current != null && current.owner() == generationOwner ? current.coverage() : null;
    }

    /**
     * Counts the currently published structures for the candidate-only perf diagnostic.
     * The mask byte count includes only the four primitive {@code long} fields of each
     * identity-distinct live mask; it is not an estimate of total retained heap.
     */
    RetainedCounts retainedCounts() {
        ensureHealthy();

        long publishedChunks = 0L;
        long coverageCount = 0L;
        long sections = 0L;
        long routeBuckets = 0L;
        long routeShards = 0L;
        long routePageNormalPrimitivePayloadBytes = 0L;
        long routePagePresencePrimitivePayloadBytes = 0L;
        long compactRoutePages = 0L;
        long wideRoutePages = 0L;
        long routeShardReferenceSlots = 0L;
        long coverageBuckets = 0L;
        long groups = 0L;
        long physicalBlocks = 0L;
        long emitters = 0L;
        Set<FloorChunkCoverage> seenCoverage = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<FloorChunkCoverage.Mask256> seenMasks = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<short[]> seenTangentLookups = Collections.newSetFromMap(new IdentityHashMap<>());

        for (ChunkView view : chunks.values()) {
            PublishedChunk current = view.current;
            if (current == null) {
                continue;
            }
            publishedChunks = Math.addExact(publishedChunks, 1L);
            FloorChunkCoverage coverage = current.coverage();
            if (!seenCoverage.add(coverage)) {
                continue;
            }

            coverageCount = Math.addExact(coverageCount, 1L);
            FloorChunkCoverage.Stats stats = coverage.stats();
            sections = Math.addExact(sections, stats.nonemptySectionCount());
            groups = Math.addExact(groups, stats.groupCount());
            physicalBlocks = Math.addExact(physicalBlocks, stats.physicalBlockCount());
            emitters = Math.addExact(emitters, stats.emitterCount());
            coverage.addTangentNormalLookupIdentities(seenTangentLookups);

            for (int sectionIndex = 0; sectionIndex < coverage.sectionCount(); sectionIndex++) {
                SectionCoverage section = coverage.section(coverage.sectionYAt(sectionIndex));
                for (Face face : Face.values()) {
                    int presence = section.normalPresence(face);
                    while (presence != 0) {
                        int normal = Integer.numberOfTrailingZeros(presence);
                        presence &= presence - 1;
                        coverageBuckets = Math.addExact(coverageBuckets, 1L);
                        for (PlaneGroup group : section.groups(face, normal)) {
                            seenMasks.add(group.cells());
                        }
                    }
                }
            }
        }

        for (Map<PageKey, RoutePage> faceRoutes : routes.values()) {
            for (RoutePage routePage : faceRoutes.values()) {
                if (routePage.normalCount() != 0) {
                    routeBuckets = Math.addExact(routeBuckets, 1L);
                    routeShards = Math.addExact(routeShards, routePage.normalCount());
                    routePageNormalPrimitivePayloadBytes = Math.addExact(routePageNormalPrimitivePayloadBytes,
                            Math.multiplyExact((long) routePage.normalCount(), Integer.BYTES));
                    routePagePresencePrimitivePayloadBytes = Math.addExact(routePagePresencePrimitivePayloadBytes,
                            Long.BYTES);
                    if (routePage.hasCompactNormalPresence()) {
                        compactRoutePages = Math.addExact(compactRoutePages, 1L);
                    } else {
                        wideRoutePages = Math.addExact(wideRoutePages, 1L);
                    }
                    routeShardReferenceSlots = Math.addExact(routeShardReferenceSlots, routePage.normalCount());
                }
            }
        }

        long maskPayloadBytes = Math.multiplyExact((long) seenMasks.size(), 4L * Long.BYTES);
        long tangentNormalLookupPayloadBytes = Math.multiplyExact((long) seenTangentLookups.size(),
                256L * Short.BYTES);
        return new RetainedCounts(publishedChunks, coverageCount, sections, routeBuckets, routeShards,
                coverageBuckets, groups, physicalBlocks, emitters, seenMasks.size(), maskPayloadBytes,
                seenTangentLookups.size(), tangentNormalLookupPayloadBytes,
                routePageNormalPrimitivePayloadBytes, routeShardReferenceSlots,
                routePagePresencePrimitivePayloadBytes, compactRoutePages, wideRoutePages);
    }

    long revisionFor(ChunkKey key, Object generationOwner) {
        ensureHealthy();
        ChunkView view = chunks.get(key);
        PublishedChunk current = view == null ? null : view.current;
        return current != null && current.owner() == generationOwner ? current.revision() : 0L;
    }

    /** Removes a chunk generation only when its owner is the exact current owner. */
    public boolean removeChunk(ChunkKey key, Object generationOwner) {
        requireChunkAndOwner(key, generationOwner);
        PreparedPublication prepared = prepareRemoval(key, generationOwner);
        if (prepared == null) {
            return false;
        }
        commitPrepared(prepared);
        return true;
    }

    /** Releases every indexed source while keeping this index reusable. */
    public void clear() {
        if (closed) {
            throw new IllegalStateException("floor field index is closed");
        }
        clearPublishedState();
        invalid = false;
        invalidCause = null;
    }

    void close() {
        if (!closed) {
            closed = true;
            clearPublishedState();
        }
    }

    private void clearPublishedState() {
        clearEpoch = Math.incrementExact(clearEpoch);
        rangeEpoch = Math.incrementExact(rangeEpoch);
        for (ChunkView view : chunks.values()) {
            view.invalidate();
        }
        chunks.clear();
        for (Map<PageKey, RoutePage> faceRoutes : routes.values()) {
            faceRoutes.clear();
        }
        registeredRanges.clear();
        maxRegisteredRange = 0.0;
    }

    PreparedPublication prepareReplacement(ChunkKey key, Object generationOwner, FloorChunkCoverage coverage) {
        requireChunkAndOwner(key, generationOwner);
        ensureHealthy();
        if (coverage == null || !coverage.belongsTo(key)) {
            throw new IllegalArgumentException("coverage must belong to the replacement chunk");
        }

        ChunkView expectedView = chunks.get(key);
        PublishedChunk base = expectedView == null ? null : requirePublished(expectedView);
        ChunkView targetView = base != null && base.owner() == generationOwner ? expectedView : new ChunkView();
        long revision = base == null ? 1L : Math.incrementExact(base.revision());
        PublishedChunk next = new PublishedChunk(generationOwner, revision, coverage);
        RoutePatch[] routePatches = replacementRoutePatches(key, expectedView, targetView,
                base == null ? null : base.coverage(), coverage);
        RoutePagePatch[] routePagePatches = prepareRoutePagePatches(routePatches);
        Map<Double, Long> rangeDeltas = new HashMap<>();
        if (base != null) {
            addRangeCounts(rangeDeltas, rangeCounts(base.coverage()), -1);
        }
        addRangeCounts(rangeDeltas, rangeCounts(coverage), 1);
        RangePatch[] rangePatches = prepareRangePatches(rangeDeltas);
        return new PreparedPublication(key, clearEpoch, rangeEpoch, nextRangeEpoch(rangePatches),
                expectedView, base, targetView, next,
                routePatches, routePagePatches, rangePatches, maxRangeAfter(rangePatches));
    }

    PreparedPublication prepareDelta(ChunkKey key, Object generationOwner, FloorChunkSources.CoverageDelta delta) {
        requireChunkAndOwner(key, generationOwner);
        ensureHealthy();
        if (delta == null) {
            throw new IllegalArgumentException("coverage delta must not be null");
        }
        ChunkView expectedView = chunks.get(key);
        PublishedChunk base = expectedView == null ? null : requirePublished(expectedView);
        if (base == null || base.owner() != generationOwner || base.coverage() != delta.base()) {
            throw new IllegalStateException("floor coverage delta no longer matches its published generation");
        }
        long revision = Math.incrementExact(base.revision());
        PublishedChunk next = new PublishedChunk(generationOwner, revision, delta.next());
        RoutePatch[] routePatches = deltaRoutePatches(key, expectedView, delta);
        RoutePagePatch[] routePagePatches = prepareRoutePagePatches(routePatches);
        RangePatch[] rangePatches = prepareRangePatches(delta.rangeDeltas());
        return new PreparedPublication(key, clearEpoch, rangeEpoch, nextRangeEpoch(rangePatches),
                expectedView, base, expectedView, next,
                routePatches, routePagePatches, rangePatches, maxRangeAfter(rangePatches));
    }

    PreparedPublication prepareRemoval(ChunkKey key, Object generationOwner) {
        requireChunkAndOwner(key, generationOwner);
        ensureHealthy();
        ChunkView expectedView = chunks.get(key);
        PublishedChunk base = expectedView == null ? null : requirePublished(expectedView);
        if (base == null || base.owner() != generationOwner) {
            return null;
        }
        RoutePatch[] routePatches = removalRoutePatches(key, expectedView, base.coverage());
        RoutePagePatch[] routePagePatches = prepareRoutePagePatches(routePatches);
        Map<Double, Long> rangeDeltas = new HashMap<>();
        addRangeCounts(rangeDeltas, rangeCounts(base.coverage()), -1);
        RangePatch[] rangePatches = prepareRangePatches(rangeDeltas);
        return new PreparedPublication(key, clearEpoch, rangeEpoch, nextRangeEpoch(rangePatches),
                expectedView, base, expectedView, null,
                routePatches, routePagePatches, rangePatches, maxRangeAfter(rangePatches));
    }

    boolean isCurrent(PreparedPublication prepared) {
        if (!isCurrentBase(prepared, false)) {
            return false;
        }
        for (RoutePagePatch patch : prepared.routePagePatches) {
            if (routes.get(patch.face).get(patch.page) != patch.expectedPage) {
                return false;
            }
        }
        return true;
    }

    private boolean isCurrentBase(PreparedPublication prepared, boolean directoryPutAttempted) {
        if (prepared == null || closed || invalid || prepared.consumed || prepared.clearEpoch != clearEpoch
                || prepared.rangeEpoch != rangeEpoch) {
            return false;
        }
        ChunkView expectedDirectory = directoryPutAttempted ? prepared.targetView : prepared.expectedView;
        if (chunks.get(prepared.key) != expectedDirectory) {
            return false;
        }
        if (prepared.expectedView == null) {
            return prepared.base == null && prepared.targetView != null && prepared.targetView.current == null
                    && !prepared.targetView.invalid;
        }
        return (prepared.targetView == prepared.expectedView
                || !prepared.targetView.invalid && prepared.targetView.current == null)
                && !prepared.expectedView.invalid && prepared.expectedView.current == prepared.base
                && prepared.base != null
                && prepared.base.owner() == prepared.expectedOwner()
                && prepared.base.coverage() == prepared.baseCoverage()
                && prepared.base.revision() == prepared.baseRevision();
    }

    ChunkView commitPrepared(PreparedPublication prepared) {
        if (!isCurrent(prepared)) {
            throw new IllegalStateException("prepared floor publication is stale or already consumed");
        }
        validatePatches(prepared);
        boolean chunkDirectoryPutAttempted = false;
        boolean linearized = false;
        try {
            stageRouteAndRangeAdditions(prepared);
            validateStagedPublication(prepared, false);
            if (beforeLinearizationHook != null) {
                beforeLinearizationHook.run();
            }
            validateStagedPublication(prepared, false);
            if (prepared.next != null && prepared.targetView != prepared.expectedView) {
                chunkDirectoryPutAttempted = true;
                chunks.put(prepared.key, prepared.targetView);
                afterPublicationStage(PublicationStage.CHUNK_DIRECTORY_INSERTED, 1);
                validateStagedPublication(prepared, true);
            }

            // The shared pointer is the publication boundary observed by the scanner and routes.
            prepared.targetView.current = prepared.next;
            linearized = true;

            if (prepared.expectedView != null && prepared.expectedView != prepared.targetView) {
                prepared.expectedView.invalidate();
            }
            if (prepared.next == null) {
                prepared.targetView.invalidate();
                chunks.remove(prepared.key);
            }
            finishRoutePagePatches(prepared.routePagePatches);
            finishNegativeRangeChanges(prepared.rangePatches);
            maxRegisteredRange = prepared.nextMaxRange;
            rangeEpoch = prepared.nextRangeEpoch;
            prepared.consumed = true;
            return prepared.next == null ? null : prepared.targetView;
        } catch (RuntimeException | Error failure) {
            if (linearized) {
                invalid = true;
                invalidCause = failure;
            } else {
                rollbackStagedPublication(prepared, chunkDirectoryPutAttempted, failure);
            }
            throw failure;
        }
    }

    private void validatePatches(PreparedPublication prepared) {
        for (RoutePatch patch : prepared.routePatches) {
            RoutedShard current = routeAt(patch.face, patch.page, patch.normal);
            if (!sameShard(current, patch.expected)) {
                throw new IllegalStateException("floor route changed after publication preparation");
            }
        }
        for (RangePatch patch : prepared.rangePatches) {
            Long current = registeredRanges.get(patch.range);
            if (!sameCount(current, patch.expected)) {
                throw new IllegalStateException("floor range counts changed after publication preparation");
            }
        }
    }

    private void stageRouteAndRangeAdditions(PreparedPublication prepared) {
        for (RoutePatch routePatch : prepared.routePatches) {
            if (routePatch.next == null || sameShard(routePatch.next, routePatch.expected)) {
                continue;
            }
            RoutePagePatch pagePatch = prepared.routePagePatches[routePatch.pagePatchIndex];
            if (!pagePatch.stageAttempted && pagePatch.stagedPage != pagePatch.expectedPage) {
                validateStagedPublication(prepared, false);
                Map<PageKey, RoutePage> byPage = routes.get(pagePatch.face);
                if (byPage.get(pagePatch.page) != pagePatch.expectedPage) {
                    throw new IllegalStateException("floor route page changed before staging");
                }
                pagePatch.stageAttempted = true;
                byPage.put(pagePatch.page, pagePatch.stagedPage);
                if (byPage.get(pagePatch.page) != pagePatch.stagedPage) {
                    throw new IllegalStateException("floor route page staging did not install its prepared value");
                }
            }
            afterPublicationStage(PublicationStage.ROUTE_PATCH_STAGED, routePatch.hookOrdinal);
            validateStagedPublication(prepared, false);
        }
        int stagedRanges = 0;
        for (RangePatch patch : prepared.rangePatches) {
            if (patch.next != null && patch.isIncrease()) {
                registeredRanges.put(patch.range, patch.next);
                afterPublicationStage(PublicationStage.RANGE_PATCH_STAGED, ++stagedRanges);
                validateStagedPublication(prepared, false);
            }
        }
    }

    private void validateStagedPublication(PreparedPublication prepared, boolean directoryPutAttempted) {
        if (!isCurrentBase(prepared, directoryPutAttempted)) {
            throw new IllegalStateException("floor publication lost its expected chunk base before linearization");
        }
        for (RoutePagePatch patch : prepared.routePagePatches) {
            RoutePage expected = patch.stageAttempted ? patch.stagedPage : patch.expectedPage;
            if (routes.get(patch.face).get(patch.page) != expected) {
                throw new IllegalStateException("floor route page changed during staged publication");
            }
        }
    }

    private void afterPublicationStage(PublicationStage stage, int ordinal) {
        if (publicationStageHook != null) {
            publicationStageHook.after(stage, ordinal);
        }
    }

    private void rollbackStagedPublication(PreparedPublication prepared, boolean stagedChunkDirectory,
                                           Throwable failure) {
        if (stagedChunkDirectory) {
            try {
                ChunkView current = chunks.get(prepared.key);
                if (current == prepared.targetView) {
                    if (prepared.expectedView == null) {
                        chunks.remove(prepared.key);
                    } else {
                        chunks.put(prepared.key, prepared.expectedView);
                    }
                } else if (current != prepared.expectedView) {
                    throw new IllegalStateException("cannot restore staged chunk view directory entry");
                }
            } catch (Throwable restoreFailure) {
                suppress(failure, restoreFailure);
                invalid = true;
                invalidCause = failure;
            }
        }
        for (int i = prepared.rangePatches.length - 1; i >= 0; i--) {
            RangePatch patch = prepared.rangePatches[i];
            if (patch.next == null || !patch.isIncrease()) {
                continue;
            }
            try {
                Long current = registeredRanges.get(patch.range);
                if (sameCount(current, patch.next)) {
                    if (patch.expected == null) {
                        registeredRanges.remove(patch.range);
                    } else {
                        registeredRanges.put(patch.range, patch.expected);
                    }
                } else if (!sameCount(current, patch.expected)) {
                    throw new IllegalStateException("cannot restore staged floor range count");
                }
            } catch (Throwable restoreFailure) {
                suppress(failure, restoreFailure);
                invalid = true;
                invalidCause = failure;
            }
        }
        for (int i = prepared.routePagePatches.length - 1; i >= 0; i--) {
            RoutePagePatch patch = prepared.routePagePatches[i];
            if (!patch.stageAttempted || patch.stagedPage == patch.expectedPage) {
                continue;
            }
            try {
                Map<PageKey, RoutePage> byPage = routes.get(patch.face);
                RoutePage current = byPage.get(patch.page);
                if (current == patch.stagedPage) {
                    if (patch.expectedPage == null) {
                        byPage.remove(patch.page);
                    } else {
                        byPage.put(patch.page, patch.expectedPage);
                    }
                } else if (current != patch.expectedPage) {
                    throw new IllegalStateException("cannot restore staged floor route page identity");
                }
                patch.stageAttempted = false;
            } catch (Throwable restoreFailure) {
                suppress(failure, restoreFailure);
                invalid = true;
                invalidCause = failure;
            }
        }
    }

    private void finishRoutePagePatches(RoutePagePatch[] patches) {
        for (RoutePagePatch patch : patches) {
            if (patch.finalPage == patch.stagedPage) {
                continue;
            }
            Map<PageKey, RoutePage> byPage = routes.get(patch.face);
            if (patch.finalPage == null) {
                if (!byPage.remove(patch.page, patch.stagedPage)) {
                    throw new IllegalStateException("missing floor route page during publication retirement");
                }
            } else if (!byPage.replace(patch.page, patch.stagedPage, patch.finalPage)) {
                throw new IllegalStateException("floor route page changed during publication retirement");
            }
        }
    }

    private void finishNegativeRangeChanges(RangePatch[] patches) {
        for (RangePatch patch : patches) {
            if (patch.isIncrease() || patch.expected == null) {
                continue;
            }
            if (patch.next == null) {
                if (registeredRanges.remove(patch.range) != patch.expected) {
                    throw new IllegalStateException("missing floor range during publication retirement");
                }
            } else {
                if (!sameCount(registeredRanges.get(patch.range), patch.expected)) {
                    throw new IllegalStateException("floor range changed during publication retirement");
                }
                registeredRanges.put(patch.range, patch.next);
            }
        }
    }

    private static void suppress(Throwable failure, Throwable secondary) {
        if (failure != secondary) {
            failure.addSuppressed(secondary);
        }
    }

    enum PublicationStage {
        ROUTE_PATCH_STAGED,
        RANGE_PATCH_STAGED,
        CHUNK_DIRECTORY_INSERTED
    }

    @FunctionalInterface
    interface PublicationStageHook {
        void after(PublicationStage stage, int ordinal);
    }

    private void ensureHealthy() {
        if (closed) {
            throw new IllegalStateException("floor field index is closed");
        }
        if (invalid) {
            throw new IllegalStateException("floor field index was invalidated after a failed publication", invalidCause);
        }
    }

    private static PublishedChunk requirePublished(ChunkView view) {
        if (view.invalid || view.current == null) {
            throw new IllegalStateException("floor chunk view has no current publication");
        }
        return view.current;
    }

    private static boolean sameCount(Long first, Long second) {
        return first == null ? second == null : second != null && first.longValue() == second.longValue();
    }

    public Sample sample(Vec3 point, double magneticResponse) {
        return sample(point, magneticResponse, null, null, null);
    }

    public Sample sample(Vec3 point, double magneticResponse, EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return sample(point, magneticResponse, null, null, work);
    }

    /** Samples forces and pre-cancellation standing strengths in one pass. */
    public StandingSample sampleForStanding(Vec3 point, double magneticResponse) {
        return sampleForStandingInternal(point, magneticResponse, null);
    }

    public StandingSample sampleForStanding(Vec3 point, double magneticResponse,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return sampleForStandingInternal(point, magneticResponse, work);
    }

    private StandingSample sampleForStandingInternal(Vec3 point, double magneticResponse,
            EnvironmentObservationWork work) {
        EnumMap<Face, Double> gravityStrengths = new EnumMap<>(Face.class);
        EnumMap<Face, Double> magneticStrengths = new EnumMap<>(Face.class);
        Sample forces = sample(point, magneticResponse, gravityStrengths, magneticStrengths, work);
        return new StandingSample(forces, gravityStrengths, magneticStrengths);
    }

    private Sample sample(Vec3 point, double magneticResponse, EnumMap<Face, Double> gravityStrengths,
                          EnumMap<Face, Double> magneticStrengths,
                          EnvironmentObservationWork work) {
        ensureHealthy();
        requireFiniteVector(point, "point");
        requireFinite(magneticResponse, "magneticResponse");
        if (magneticResponse < 0.0) {
            throw new IllegalArgumentException("magneticResponse must be nonnegative");
        }

        double gravityX = 0.0;
        double gravityY = 0.0;
        double gravityZ = 0.0;
        double magneticX = 0.0;
        double magneticY = 0.0;
        double magneticZ = 0.0;
        int gravityFaceMask = 0;
        int magneticFaceMask = 0;
        if (maxRegisteredRange == 0.0) {
            return new Sample(Vec3.ZERO, Vec3.ZERO, canonicalFaces(0), canonicalFaces(0));
        }

        for (Face face : Face.values()) {
            observationCharge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            Integer first = tangentA(face, point);
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            Integer second = tangentB(face, point);
            if (first == null || second == null) {
                continue;
            }
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            PageKey page = pageKey(first, second);
            RoutePage routePage = routes.get(face).get(page);
            if (routePage == null) {
                continue;
            }
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            int bit = Math.floorMod(first, 16) + 16 * Math.floorMod(second, 16);
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            double coordinate = normalCoordinate(face, point);
            int lower;
            int upper;
            switch (face) {
                case UP, SOUTH, EAST -> {
                    observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                    lower = floorBound(coordinate - maxRegisteredRange - 1.0);
                    observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                    upper = floorBound(coordinate);
                }
                case DOWN, NORTH, WEST -> {
                    observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                    lower = floorBound(coordinate);
                    observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                    upper = floorBound(coordinate + maxRegisteredRange);
                }
                default -> throw new IllegalStateException("unknown floor face");
            }
            if (lower > upper) {
                continue;
            }
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            int lowerSlice = Math.floorDiv(lower, 16);
            observationCharge(work, EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
            int upperSlice = Math.floorDiv(upper, 16);
            for (int ordinal = routePage.lowerBound(lowerSlice, work); ordinal < routePage.normalCount(); ordinal++) {
                observationCharge(work, EnvironmentObservationWork.Unit.COMPARISON);
                int normalSlice = routePage.normalAt(ordinal);
                if (normalSlice > upperSlice) {
                    break;
                }
                int sliceBase = Math.multiplyExact(normalSlice, 16);
                RoutedShard shard = routePage.shardAt(ordinal);
                PublishedChunk publication = requirePublished(shard.view());
                SectionCoverage section = publication.coverage().section(shard.sectionY());
                if (section == null) {
                    throw new IllegalStateException("routed floor coverage lost its section");
                }
                observationCharge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
                int faceNormals = section.normalPresence(face);
                boolean singleNormalFace = Integer.bitCount(faceNormals) == 1;
                int presence = (singleNormalFace ? faceNormals : section.normalPresenceAtBit(face, bit))
                        & localNormalMaskWithinClosedBounds(sliceBase, lower, upper);
                while (presence != 0) {
                    observationCharge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
                    int localNormal = Integer.numberOfTrailingZeros(presence);
                    presence &= presence - 1;
                    int normal = Math.addExact(sliceBase, localNormal);
                    PlaneGroup group = section.groupAt(face, localNormal, bit);
                    if (group == null) {
                        if (singleNormalFace) {
                            continue;
                        }
                        throw new IllegalStateException("tangent lookup points to a missing floor plane group");
                    }
                    observationCharge(work, EnvironmentObservationWork.Unit.FACE_PREDICATE);
                    double contribution = strengthAtMatchedCell(face, normal, coordinate, group.profile(), work);
                    if (contribution != 0.0) {
                        double contributionX = 0.0;
                        double contributionY = 0.0;
                        double contributionZ = 0.0;
                        switch (face) {
                            case DOWN -> contributionY = contribution;
                            case UP -> contributionY = contribution;
                            case NORTH -> contributionZ = contribution;
                            case SOUTH -> contributionZ = contribution;
                            case WEST -> contributionX = contribution;
                            case EAST -> contributionX = contribution;
                            default -> throw new IllegalStateException("unknown floor face");
                        }
                        if (group.kind() == Kind.GRAVITY) {
                            gravityX = checkedFinite(gravityX + contributionX, "adding gravity contributions");
                            gravityY = checkedFinite(gravityY + contributionY, "adding gravity contributions");
                            gravityZ = checkedFinite(gravityZ + contributionZ, "adding gravity contributions");
                            gravityFaceMask |= 1 << face.ordinal();
                            addStrength(gravityStrengths, face, contributionX, contributionY, contributionZ);
                        } else if (magneticResponse > 0.0) {
                            double scaledX = checkedFinite(contributionX * magneticResponse,
                                    "applying magnetic response");
                            double scaledY = checkedFinite(contributionY * magneticResponse,
                                    "applying magnetic response");
                            double scaledZ = checkedFinite(contributionZ * magneticResponse,
                                    "applying magnetic response");
                            magneticX = checkedFinite(magneticX + scaledX, "adding magnetic contributions");
                            magneticY = checkedFinite(magneticY + scaledY, "adding magnetic contributions");
                            magneticZ = checkedFinite(magneticZ + scaledZ, "adding magnetic contributions");
                            magneticFaceMask |= 1 << face.ordinal();
                            addStrength(magneticStrengths, face, scaledX, scaledY, scaledZ);
                        }
                    }
                }
            }
        }
        Vec3 gravity = gravityFaceMask == 0 ? Vec3.ZERO : new Vec3(gravityX, gravityY, gravityZ);
        Vec3 magnetic = magneticFaceMask == 0 ? Vec3.ZERO : new Vec3(magneticX, magneticY, magneticZ);
        return new Sample(gravity, magnetic, canonicalFaces(gravityFaceMask), canonicalFaces(magneticFaceMask));
    }

    private static void observationCharge(EnvironmentObservationWork work,
            EnvironmentObservationWork.Unit unit) {
        if (work != null) work.charge(unit);
    }

    private static void addStrength(EnumMap<Face, Double> strengths, Face face,
                                    double contributionX, double contributionY, double contributionZ) {
        if (strengths == null) {
            return;
        }
        double strength = Math.max(Math.abs(contributionX),
                Math.max(Math.abs(contributionY), Math.abs(contributionZ)));
        if (strength > 0.0) {
            strengths.put(face, checkedFinite(strengths.getOrDefault(face, 0.0) + strength,
                    "adding standing strengths"));
        }
    }

    /** Calculates face-signed strength after the routed bit has matched this tangent cell. */
    private static double strengthAtMatchedCell(Face face, int normal, double coordinate,
                                                FloorFieldProfile profile,
                                                EnvironmentObservationWork work) {
        observationCharge(work, EnvironmentObservationWork.Unit.CLASSIFICATION);
        double outwardDistance = switch (face) {
            case DOWN, NORTH, WEST -> (double) normal - coordinate;
            case UP, SOUTH, EAST -> coordinate - ((double) normal + 1.0);
        };
        if (outwardDistance < 0.0 || outwardDistance >= profile.range()) {
            return 0.0;
        }
        double strength = profile.strengthAt(outwardDistance);
        if (strength == 0.0) {
            return 0.0;
        }
        return switch (face) {
            case DOWN, NORTH, WEST -> strength;
            case UP, SOUTH, EAST -> -strength;
        };
    }

    private RoutePatch[] replacementRoutePatches(ChunkKey key, ChunkView expectedView, ChunkView targetView,
                                                  FloorChunkCoverage oldCoverage,
                                                  FloorChunkCoverage nextCoverage) {
        Map<RouteSlot, RoutedShard> oldRoutes = oldCoverage == null
                ? Map.of() : routeMap(routeAddresses(key, expectedView, oldCoverage));
        Map<RouteSlot, RoutedShard> nextRoutes = routeMap(routeAddresses(key, targetView, nextCoverage));
        List<RoutePatch> patches = new java.util.ArrayList<>();
        for (Map.Entry<RouteSlot, RoutedShard> entry : oldRoutes.entrySet()) {
            RouteSlot slot = entry.getKey();
            RoutedShard expected = entry.getValue();
            RoutedShard next = nextRoutes.remove(slot);
            if (!sameShard(routeAt(slot.face(), slot.page(), slot.normal()), expected)) {
                throw new IllegalStateException("floor route directory disagrees with its published chunk");
            }
            if (!sameShard(next, expected)) {
                patches.add(routePatch(slot, expected, next));
            }
        }
        for (Map.Entry<RouteSlot, RoutedShard> entry : nextRoutes.entrySet()) {
            RouteSlot slot = entry.getKey();
            if (routeAt(slot.face(), slot.page(), slot.normal()) != null) {
                throw new IllegalStateException("floor coverage route is already owned by another chunk");
            }
            patches.add(routePatch(slot, null, entry.getValue()));
        }
        return patches.toArray(RoutePatch[]::new);
    }

    private RoutePatch[] deltaRoutePatches(ChunkKey key, ChunkView view, FloorChunkSources.CoverageDelta delta) {
        List<RoutePatch> patches = new java.util.ArrayList<>();
        int previousSectionY = 0;
        Face previousFace = null;
        boolean hasPrevious = false;
        for (FloorChunkCoverage.BucketKey changed : delta.changedBuckets()) {
            int sectionY = changed.sectionY();
            Face face = changed.face();
            if (hasPrevious && previousSectionY == sectionY && previousFace == face) {
                continue;
            }
            previousSectionY = sectionY;
            previousFace = face;
            hasPrevious = true;
            SectionCoverage oldSection = delta.base().section(sectionY);
            SectionCoverage nextSection = delta.next().section(sectionY);
            boolean oldPresent = oldSection != null && oldSection.normalPresence(face) != 0;
            boolean nextPresent = nextSection != null && nextSection.normalPresence(face) != 0;
            if (oldPresent == nextPresent) {
                continue;
            }
            RouteSlot slot = routeSlot(key, face, sectionY);
            RoutedShard expected = oldPresent ? new RoutedShard(view, face, sectionY) : null;
            RoutedShard next = nextPresent ? new RoutedShard(view, face, sectionY) : null;
            if (!sameShard(routeAt(slot.face(), slot.page(), slot.normal()), expected)) {
                throw new IllegalStateException("floor route directory disagrees with changed coverage section");
            }
            patches.add(routePatch(slot, expected, next));
        }
        return patches.toArray(RoutePatch[]::new);
    }

    private RoutePatch[] removalRoutePatches(ChunkKey key, ChunkView view, FloorChunkCoverage coverage) {
        List<RoutePatch> patches = new java.util.ArrayList<>();
        for (RouteAddress address : routeAddresses(key, view, coverage)) {
            if (!sameShard(routeAt(address.face(), address.page(), address.normal()), address.shard())) {
                throw new IllegalStateException("floor route directory disagrees with removal coverage");
            }
            patches.add(routePatch(new RouteSlot(address.face(), address.page(), address.normal()),
                    address.shard(), null));
        }
        return patches.toArray(RoutePatch[]::new);
    }

    private RoutePatch routePatch(RouteSlot slot, RoutedShard expected, RoutedShard next) {
        return new RoutePatch(slot.face(), slot.page(), slot.normal(), expected, next);
    }

    private RoutePagePatch[] prepareRoutePagePatches(RoutePatch[] patches) {
        if (patches.length == 0) {
            return new RoutePagePatch[0];
        }
        int nextHookOrdinal = 0;
        for (RoutePatch patch : patches) {
            if (patch.next != null && !sameShard(patch.next, patch.expected)) {
                patch.hookOrdinal = Math.incrementExact(nextHookOrdinal);
                nextHookOrdinal = patch.hookOrdinal;
            }
        }
        Map<RoutePageKey, List<RoutePatch>> grouped = new LinkedHashMap<>();
        for (RoutePatch patch : patches) {
            RoutePageKey key = new RoutePageKey(patch.face, patch.page);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(patch);
        }

        RoutePagePatch[] preparedPages = new RoutePagePatch[grouped.size()];
        int pageIndex = 0;
        for (Map.Entry<RoutePageKey, List<RoutePatch>> entry : grouped.entrySet()) {
            RoutePageKey key = entry.getKey();
            List<RoutePatch> pagePatches = entry.getValue();
            pagePatches.sort((first, second) -> Integer.compare(first.normal, second.normal));
            RoutePage expectedPage = routes.get(key.face).get(key.page);
            boolean stagedChanges = false;
            boolean hasRemoval = false;
            int previousNormal = 0;
            boolean hasPreviousNormal = false;
            for (RoutePatch patch : pagePatches) {
                if (hasPreviousNormal && previousNormal == patch.normal) {
                    throw new IllegalStateException("duplicate route patch for one face/page/normal");
                }
                hasPreviousNormal = true;
                previousNormal = patch.normal;
                RoutedShard current = expectedPage == null ? null : expectedPage.shardAtNormal(patch.normal);
                if (!sameShard(current, patch.expected)) {
                    throw new IllegalStateException("floor route page disagrees with a prepared route patch");
                }
                if (!sameShard(patch.next, patch.expected)) {
                    stagedChanges |= patch.next != null;
                    hasRemoval |= patch.next == null;
                }
            }

            RoutePage stagedPage = stagedChanges
                    ? mergeRoutePage(expectedPage, pagePatches, true)
                    : expectedPage;
            RoutePage finalPage = hasRemoval
                    ? mergeRoutePage(expectedPage, pagePatches, false)
                    : stagedPage;
            RoutePagePatch routePagePatch = new RoutePagePatch(key.face, key.page, expectedPage,
                    stagedPage, finalPage, pagePatches.toArray(RoutePatch[]::new));
            preparedPages[pageIndex] = routePagePatch;
            for (RoutePatch patch : routePagePatch.logicalPatches) {
                patch.pagePatchIndex = pageIndex;
            }
            pageIndex++;
        }
        return preparedPages;
    }

    private static RoutePage mergeRoutePage(RoutePage base, List<RoutePatch> patches, boolean retainRemovals) {
        int baseCount = base == null ? 0 : base.normalCount();
        int resultCount = baseCount;
        for (RoutePatch patch : patches) {
            if (patch.expected == null && patch.next != null) {
                resultCount = Math.incrementExact(resultCount);
            } else if (!retainRemovals && patch.expected != null && patch.next == null) {
                resultCount = Math.decrementExact(resultCount);
            }
        }
        if (resultCount == 0) {
            return null;
        }

        int[] normals = new int[resultCount];
        RoutedShard[] shards = new RoutedShard[resultCount];
        int baseIndex = 0;
        int patchIndex = 0;
        int outputIndex = 0;
        while (baseIndex < baseCount || patchIndex < patches.size()) {
            if (patchIndex == patches.size()
                    || baseIndex < baseCount && base.normalAt(baseIndex) < patches.get(patchIndex).normal) {
                normals[outputIndex] = base.normalAt(baseIndex);
                shards[outputIndex] = base.shardAt(baseIndex);
                baseIndex++;
                outputIndex++;
                continue;
            }

            RoutePatch patch = patches.get(patchIndex);
            if (baseIndex == baseCount || patch.normal < base.normalAt(baseIndex)) {
                if (patch.expected != null || patch.next == null) {
                    throw new IllegalStateException("prepared route patch lost its expected normal");
                }
                normals[outputIndex] = patch.normal;
                shards[outputIndex] = patch.next;
                outputIndex++;
                patchIndex++;
                continue;
            }

            RoutedShard current = base.shardAt(baseIndex);
            if (!sameShard(current, patch.expected)) {
                throw new IllegalStateException("prepared route patch no longer matches its base page");
            }
            if (patch.next != null || retainRemovals) {
                normals[outputIndex] = patch.normal;
                shards[outputIndex] = patch.next == null ? current : patch.next;
                outputIndex++;
            }
            baseIndex++;
            patchIndex++;
        }
        if (outputIndex != resultCount) {
            throw new IllegalStateException("prepared route page size disagrees with its merged entries");
        }
        return new RoutePage(normals, shards);
    }

    private static Map<RouteSlot, RoutedShard> routeMap(List<RouteAddress> addresses) {
        Map<RouteSlot, RoutedShard> result = new HashMap<>(addresses.size());
        for (RouteAddress address : addresses) {
            RouteSlot slot = new RouteSlot(address.face(), address.page(), address.normal());
            if (result.putIfAbsent(slot, address.shard()) != null) {
                throw new IllegalStateException("duplicate route generated for one floor section face");
            }
        }
        return result;
    }

    private static List<RouteAddress> routeAddresses(ChunkKey key, ChunkView view, FloorChunkCoverage coverage) {
        java.util.ArrayList<RouteAddress> result = new java.util.ArrayList<>();
        for (int sectionIndex = 0; sectionIndex < coverage.sectionCount(); sectionIndex++) {
            int sectionY = coverage.sectionYAt(sectionIndex);
            SectionCoverage section = coverage.section(sectionY);
            for (Face face : Face.values()) {
                if (section.normalPresence(face) == 0) {
                    continue;
                }
                RouteSlot slot = routeSlot(key, face, sectionY);
                result.add(new RouteAddress(slot.face(), slot.page(), slot.normal(),
                        new RoutedShard(view, face, sectionY)));
            }
        }
        return result;
    }

    private static RouteSlot routeSlot(ChunkKey key, Face face, int sectionY) {
        int normalSlice = switch (face) {
            case DOWN, UP -> sectionY;
            case NORTH, SOUTH -> key.z();
            case WEST, EAST -> key.x();
        };
        PageKey page = switch (face) {
            case DOWN, UP -> new PageKey(key.x(), key.z());
            case NORTH, SOUTH -> new PageKey(key.x(), sectionY);
            case WEST, EAST -> new PageKey(sectionY, key.z());
        };
        return new RouteSlot(face, page, normalSlice);
    }

    private static int localNormalMaskWithinClosedBounds(int sliceBase, int lowerInclusive, int upperInclusive) {
        long low = Math.max(0L, (long) lowerInclusive - sliceBase);
        long high = Math.min(15L, (long) upperInclusive - sliceBase);
        if (low > high) {
            return 0;
        }
        return (0xFFFF << (int) low) & (0xFFFF >>> (15 - (int) high));
    }

    private static Map<Double, Long> rangeCounts(FloorChunkCoverage coverage) {
        return rangeCounts(coverage, null);
    }

    private static Map<Double, Long> rangeCounts(FloorChunkCoverage coverage, int[] sectionYs) {
        Map<Double, Long> counts = new HashMap<>();
        if (sectionYs == null) {
            for (int sectionIndex = 0; sectionIndex < coverage.sectionCount(); sectionIndex++) {
                addSectionRangeCounts(counts, coverage.section(coverage.sectionYAt(sectionIndex)));
            }
        } else {
            for (int sectionY : sectionYs) {
                addSectionRangeCounts(counts, coverage.section(sectionY));
            }
        }
        return counts;
    }

    private static void addSectionRangeCounts(Map<Double, Long> counts, SectionCoverage section) {
        if (section == null) {
            return;
        }
        for (Face face : Face.values()) {
            int presence = section.normalPresence(face);
            while (presence != 0) {
                int localNormal = Integer.numberOfTrailingZeros(presence);
                presence &= presence - 1;
                for (PlaneGroup group : section.groups(face, localNormal)) {
                    long count = group.cells().cardinality();
                    counts.merge(group.profile().range(), count, Math::addExact);
                }
            }
        }
    }

    private static void addRangeCounts(Map<Double, Long> target, Map<Double, Long> changes, int sign) {
        for (Map.Entry<Double, Long> entry : changes.entrySet()) {
            long delta = sign > 0 ? entry.getValue() : Math.negateExact(entry.getValue());
            long next = Math.addExact(target.getOrDefault(entry.getKey(), 0L), delta);
            if (next == 0L) {
                target.remove(entry.getKey());
            } else {
                target.put(entry.getKey(), next);
            }
        }
    }

    private RangePatch[] prepareRangePatches(Map<Double, Long> deltas) {
        List<RangePatch> patches = new java.util.ArrayList<>(deltas.size());
        for (Map.Entry<Double, Long> entry : deltas.entrySet()) {
            long delta = entry.getValue();
            if (delta == 0L) {
                continue;
            }
            Double range = entry.getKey();
            Long oldCount = registeredRanges.get(range);
            long nextValue = Math.addExact(oldCount == null ? 0L : oldCount, delta);
            if (nextValue < 0L) {
                throw new IllegalStateException("floor range delta would remove unowned emitters");
            }
            Long nextCount = nextValue == 0L ? null : Long.valueOf(nextValue);
            patches.add(new RangePatch(range, oldCount, nextCount));
        }
        return patches.toArray(RangePatch[]::new);
    }

    private long nextRangeEpoch(RangePatch[] patches) {
        return patches.length == 0 ? rangeEpoch : Math.incrementExact(rangeEpoch);
    }

    private double maxRangeAfter(RangePatch[] patches) {
        Map<Double, RangePatch> byRange = new HashMap<>(patches.length);
        for (RangePatch patch : patches) {
            byRange.put(patch.range, patch);
        }
        double result = maxRegisteredRange;
        for (RangePatch patch : patches) {
            if (patch.next != null && patch.range > result) {
                result = patch.range;
            }
        }
        if (result == maxRegisteredRange) {
            RangePatch maximumPatch = byRange.get(maxRegisteredRange);
            if (maximumPatch != null && maximumPatch.next == null) {
                Double prior = registeredRanges.lowerKey(maxRegisteredRange);
                while (prior != null) {
                    RangePatch patch = byRange.get(prior);
                    if (patch == null || patch.next != null) {
                        result = prior;
                        break;
                    }
                    prior = registeredRanges.lowerKey(prior);
                }
                if (prior == null) {
                    result = 0.0;
                }
            }
        }
        return result;
    }

    private RoutedShard routeAt(Face face, PageKey page, int normal) {
        RoutePage routePage = routes.get(face).get(page);
        return routePage == null ? null : routePage.shardAtNormal(normal);
    }

    private static boolean sameShard(RoutedShard first, RoutedShard second) {
        return first == second || first != null && second != null && first.view() == second.view()
                && first.face() == second.face() && first.sectionY() == second.sectionY();
    }

    private static PageKey pageKey(int first, int second) {
        return new PageKey(Math.floorDiv(first, 16), Math.floorDiv(second, 16));
    }

    private static Integer tangentA(Face face, Vec3 point) {
        return switch (face) {
            case DOWN, UP, NORTH, SOUTH -> blockCoordinate(point.x());
            case WEST, EAST -> blockCoordinate(point.y());
        };
    }

    private static Integer tangentB(Face face, Vec3 point) {
        return switch (face) {
            case DOWN, UP, WEST, EAST -> blockCoordinate(point.z());
            case NORTH, SOUTH -> blockCoordinate(point.y());
        };
    }

    private static double normalCoordinate(Face face, Vec3 point) {
        return switch (face) {
            case DOWN, UP -> point.y();
            case NORTH, SOUTH -> point.z();
            case WEST, EAST -> point.x();
        };
    }

    private static void requireFiniteVector(Vec3 vector, String name) {
        if (vector == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        requireFinite(vector.x(), name + ".x");
        requireFinite(vector.y(), name + ".y");
        requireFinite(vector.z(), name + ".z");
    }

    private static double checkedFinite(double value, String operation) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("floor-field acceleration overflow while " + operation);
        }
        return value;
    }

    private static void requireChunkAndOwner(ChunkKey key, Object generationOwner) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (generationOwner == null) {
            throw new IllegalArgumentException("generationOwner must not be null");
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static Integer blockCoordinate(double coordinate) {
        if (coordinate < Integer.MIN_VALUE || coordinate >= (double) Integer.MAX_VALUE + 1.0) {
            return null;
        }
        return (int) Math.floor(coordinate);
    }

    private static int floorBound(double value) {
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(value);
    }

    private record PageKey(int a, int b) {
    }

    private record RoutePageKey(Face face, PageKey page) {
    }

    private static final class RoutePage {
        private final int[] normals;
        private final RoutedShard[] shards;
        private final long compactNormalPresence;

        private RoutePage(int[] normals, RoutedShard[] shards) {
            if (normals == null || shards == null || normals.length == 0 || normals.length != shards.length) {
                throw new IllegalArgumentException("route page arrays must be nonempty and have equal lengths");
            }
            for (int index = 0; index < normals.length; index++) {
                if (shards[index] == null || index > 0 && normals[index - 1] >= normals[index]) {
                    throw new IllegalArgumentException("route page normals must be strictly ordered with no null shards");
                }
            }
            long first = normals[0];
            long last = normals[normals.length - 1];
            long width = last - first + 1L;
            long presence = 0L;
            if (width <= Long.SIZE) {
                for (int normal : normals) {
                    int offset = (int) ((long) normal - first);
                    presence |= 1L << offset;
                }
            }
            this.normals = normals;
            this.shards = shards;
            this.compactNormalPresence = presence;
        }

        private int normalCount() {
            return normals.length;
        }

        private int lowerBound(int normal) {
            return lowerBound(normal, null);
        }

        private int lowerBound(int normal, EnvironmentObservationWork work) {
            observationCharge(work, EnvironmentObservationWork.Unit.COMPARISON);
            if (normal <= normals[0]) {
                return 0;
            }
            observationCharge(work, EnvironmentObservationWork.Unit.COMPARISON);
            if (normal > normals[normals.length - 1]) {
                return normals.length;
            }
            if (compactNormalPresence != 0L) {
                int offset = (int) ((long) normal - (long) normals[0]);
                long before = -1L >>> (Long.SIZE - offset);
                return Long.bitCount(compactNormalPresence & before);
            }
            int low = 0;
            int high = normals.length;
            while (low < high) {
                observationCharge(work, EnvironmentObservationWork.Unit.COMPARISON);
                int middle = (low + high) >>> 1;
                if (normals[middle] < normal) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low;
        }

        private boolean hasCompactNormalPresence() {
            return compactNormalPresence != 0L;
        }

        private int normalAt(int ordinal) {
            return normals[ordinal];
        }

        private RoutedShard shardAt(int ordinal) {
            return shards[ordinal];
        }

        private RoutedShard shardAtNormal(int normal) {
            int ordinal = lowerBound(normal);
            return ordinal < normals.length && normals[ordinal] == normal ? shards[ordinal] : null;
        }
    }

    private record PublishedChunk(Object owner, long revision, FloorChunkCoverage coverage) {
        private PublishedChunk {
            if (owner == null || revision <= 0L || coverage == null) {
                throw new IllegalArgumentException("published chunk fields must be valid");
            }
        }
    }

    /** Immutable actual-live-structure counts; not a JVM heap-size estimate. */
    record RetainedCounts(long publishedChunks, long coverages, long sections,
                          long routeBuckets, long routeShards, long coverageBuckets,
                          long groups, long physicalBlocks, long emitters,
                          long identityDistinctMasks, long maskPayloadBytes,
                          long tangentNormalLookupFaces, long tangentNormalLookupPayloadBytes,
                          long routePageNormalPrimitivePayloadBytes, long routeShardReferenceSlots,
                          long routePagePresencePrimitivePayloadBytes, long compactRoutePages,
                          long wideRoutePages) {
    }

    static final class ChunkView {
        private PublishedChunk current;
        private boolean invalid;

        FloorChunkCoverage currentCoverage(Object expectedOwner) {
            PublishedChunk publication = requirePublished(this);
            if (publication.owner() != expectedOwner) {
                throw new IllegalStateException("floor scanner attachment no longer owns the shared chunk view");
            }
            return publication.coverage();
        }

        private void invalidate() {
            current = null;
            invalid = true;
        }
    }

    static final class PreparedPublication {
        private final ChunkKey key;
        private final long clearEpoch;
        private final long rangeEpoch;
        private final long nextRangeEpoch;
        private final ChunkView expectedView;
        private final PublishedChunk base;
        private final ChunkView targetView;
        private final PublishedChunk next;
        private final RoutePatch[] routePatches;
        private final RoutePagePatch[] routePagePatches;
        private final RangePatch[] rangePatches;
        private final double nextMaxRange;
        private boolean consumed;

        private PreparedPublication(ChunkKey key, long clearEpoch, long rangeEpoch, long nextRangeEpoch,
                                    ChunkView expectedView,
                                    PublishedChunk base, ChunkView targetView, PublishedChunk next,
                                    RoutePatch[] routePatches, RoutePagePatch[] routePagePatches,
                                    RangePatch[] rangePatches,
                                    double nextMaxRange) {
            this.key = key;
            this.clearEpoch = clearEpoch;
            this.rangeEpoch = rangeEpoch;
            this.nextRangeEpoch = nextRangeEpoch;
            this.expectedView = expectedView;
            this.base = base;
            this.targetView = targetView;
            this.next = next;
            this.routePatches = routePatches;
            this.routePagePatches = routePagePatches;
            this.rangePatches = rangePatches;
            this.nextMaxRange = nextMaxRange;
        }

        private Object expectedOwner() {
            return base == null ? null : base.owner();
        }

        ChunkView targetView() {
            return targetView;
        }

        private FloorChunkCoverage baseCoverage() {
            return base == null ? null : base.coverage();
        }

        private long baseRevision() {
            return base == null ? 0L : base.revision();
        }
    }

    private record RoutedShard(ChunkView view, Face face, int sectionY) {
    }

    private record RouteSlot(Face face, PageKey page, int normal) {
    }

    private record RouteAddress(Face face, PageKey page, int normal, RoutedShard shard) {
    }

    private static final class RoutePagePatch {
        private final Face face;
        private final PageKey page;
        private final RoutePage expectedPage;
        private final RoutePage stagedPage;
        private final RoutePage finalPage;
        private final RoutePatch[] logicalPatches;
        private boolean stageAttempted;

        private RoutePagePatch(Face face, PageKey page, RoutePage expectedPage, RoutePage stagedPage,
                               RoutePage finalPage, RoutePatch[] logicalPatches) {
            this.face = face;
            this.page = page;
            this.expectedPage = expectedPage;
            this.stagedPage = stagedPage;
            this.finalPage = finalPage;
            this.logicalPatches = logicalPatches;
        }
    }

    private static final class RoutePatch {
        private final Face face;
        private final PageKey page;
        private final int normal;
        private final RoutedShard expected;
        private final RoutedShard next;
        private int pagePatchIndex;
        private int hookOrdinal;

        private RoutePatch(Face face, PageKey page, int normal, RoutedShard expected, RoutedShard next) {
            this.face = face;
            this.page = page;
            this.normal = normal;
            this.expected = expected;
            this.next = next;
        }
    }

    private static final class RangePatch {
        private final Double range;
        private final Long expected;
        private final Long next;

        private RangePatch(Double range, Long expected, Long next) {
            this.range = range;
            this.expected = expected;
            this.next = next;
        }

        private boolean isIncrease() {
            return next != null && (expected == null || next.longValue() > expected.longValue());
        }
    }
}
