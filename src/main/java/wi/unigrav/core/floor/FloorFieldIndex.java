package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;

/**
 * Mutable, single-owner sparse index of floor-field sources for one world.
 *
 * <p>Callers own world isolation and invoke this index from their authoritative
 * server thread. A chunk replacement is validated in full before it replaces
 * that chunk's reverse-owned entries. Samples inspect six face-specific sparse
 * axis columns only; they never scan every registered source.
 */
public final class FloorFieldIndex {

    /** One physical floor block to index. */
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
            EnumSet<Face> copy = faces.isEmpty()
                    ? EnumSet.noneOf(Face.class)
                    : EnumSet.copyOf(faces);
            return Collections.unmodifiableSet(copy);
        }
    }

    /**
     * One traversal's forces and pre-cancellation standing strengths, in m/s².
     * Only the index can construct this pair; callers cannot splice observations
     * from different traversals. Missing strength entries mean zero, not missing
     * world data. Magnetic strengths already include the actor response.
     */
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

    private final Map<ChunkKey, ChunkContents> chunks = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> downColumns = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> upColumns = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> northColumns = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> southColumns = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> westColumns = new HashMap<>();
    private final Map<ColumnKey, NavigableMap<Integer, IndexedSource>> eastColumns = new HashMap<>();
    private final NavigableMap<Double, Integer> registeredRanges = new TreeMap<>();
    private double maxRegisteredRange;

    /**
     * Atomically replaces all indexed sources owned by one loaded chunk generation.
     *
     * <p>The supplied batch must contain only sources belonging to {@code key} and
     * no duplicate physical block positions. The owner is identity-compared by
     * {@link #removeChunk(ChunkKey, Object)} so a stale unload cannot erase a
     * newer chunk generation.
     */
    public void replaceChunk(ChunkKey key, Object generationOwner, List<Source> sources) {
        requireChunkAndOwner(key, generationOwner);
        List<Source> validatedSources = validateBatch(key, sources);
        List<IndexedSource> indexedSources = new ArrayList<>(validatedSources.size());
        for (Source source : validatedSources) {
            indexedSources.add(new IndexedSource(source,
                    new FloorFieldEmitter(source.x(), source.y(), source.z(), source.face(), source.profile())));
        }

        ChunkContents previous = chunks.remove(key);
        if (previous != null) {
            for (IndexedSource source : previous.sources()) {
                removeIndexed(source);
            }
        }
        for (IndexedSource source : indexedSources) {
            addIndexed(source);
        }
        chunks.put(key, new ChunkContents(generationOwner, List.copyOf(indexedSources)));
    }

    /**
     * Removes a chunk generation if, and only if, {@code generationOwner} is the
     * exact same object supplied to {@link #replaceChunk(ChunkKey, Object, List)}.
     *
     * @return true when this call removed the current generation
     */
    public boolean removeChunk(ChunkKey key, Object generationOwner) {
        requireChunkAndOwner(key, generationOwner);
        ChunkContents current = chunks.get(key);
        if (current == null || current.generationOwner() != generationOwner) {
            return false;
        }
        chunks.remove(key);
        for (IndexedSource source : current.sources()) {
            removeIndexed(source);
        }
        return true;
    }

    /** Releases every indexed source. */
    public void clear() {
        chunks.clear();
        downColumns.clear();
        upColumns.clear();
        northColumns.clear();
        southColumns.clear();
        westColumns.clear();
        eastColumns.clear();
        registeredRanges.clear();
        maxRegisteredRange = 0.0;
    }

    /**
     * Samples gravity and magnetic acceleration at a finite world point.
     *
     * <p>Magnetic acceleration is multiplied by {@code magneticResponse}; a
     * response of zero reports no magnetic acceleration and no magnetic faces.
     * The returned face sets record every individually nonzero contribution,
     * even where opposing vectors cancel in the summed acceleration.
     */
    public Sample sample(Vec3 point, double magneticResponse) {
        return sample(point, magneticResponse, null, null);
    }

    /**
     * Samples the same sparse columns as {@link #sample}, accumulating reference
     * strengths before opposing vectors cancel. The legacy face sets are not
     * scores: a positive magnetic response can underflow a contribution to zero,
     * leaving a legacy face but no effective standing strength.
     */
    public StandingSample sampleForStanding(Vec3 point, double magneticResponse) {
        EnumMap<Face, Double> gravityStrengths = new EnumMap<>(Face.class);
        EnumMap<Face, Double> magneticStrengths = new EnumMap<>(Face.class);
        Sample forces = sample(point, magneticResponse, gravityStrengths, magneticStrengths);
        return new StandingSample(forces, gravityStrengths, magneticStrengths);
    }

    private Sample sample(Vec3 point, double magneticResponse, EnumMap<Face, Double> gravityStrengths,
                          EnumMap<Face, Double> magneticStrengths) {
        requireFiniteVector(point, "point");
        requireFinite(magneticResponse, "magneticResponse");
        if (magneticResponse < 0.0) {
            throw new IllegalArgumentException("magneticResponse must be nonnegative");
        }

        Vec3 gravity = Vec3.ZERO;
        Vec3 magnetic = Vec3.ZERO;
        EnumSet<Face> gravityFaces = EnumSet.noneOf(Face.class);
        EnumSet<Face> magneticFaces = EnumSet.noneOf(Face.class);

        if (maxRegisteredRange == 0.0) {
            return new Sample(gravity, magnetic, gravityFaces, magneticFaces);
        }

        for (Face face : Face.values()) {
            ColumnKey key = columnKey(face, point);
            if (key == null) {
                continue;
            }
            NavigableMap<Integer, IndexedSource> column = columnsFor(face).get(key);
            if (column == null) {
                continue;
            }
            double coordinate = normalCoordinate(face, point);
            for (IndexedSource indexed : boundedEntries(face, column, coordinate)) {
                Vec3 contribution = indexed.emitter().accelerationAt(point);
                if (!hasNonzeroComponent(contribution)) {
                    continue;
                }
                if (indexed.source().kind() == Kind.GRAVITY) {
                    gravity = addChecked(gravity, contribution, "adding gravity contributions");
                    gravityFaces.add(face);
                    addStrength(gravityStrengths, face, contribution);
                } else if (magneticResponse > 0.0) {
                    Vec3 scaled = scaleChecked(contribution, magneticResponse);
                    magnetic = addChecked(magnetic, scaled, "adding magnetic contributions");
                    magneticFaces.add(face);
                    addStrength(magneticStrengths, face, scaled);
                }
            }
        }
        return new Sample(gravity, magnetic, gravityFaces, magneticFaces);
    }

    private static void addStrength(EnumMap<Face, Double> strengths, Face face, Vec3 contribution) {
        if (strengths == null) {
            return; // The legacy force-only path allocates no standing maps.
        }
        // Every floor contribution has exactly one axial component; no norm
        // squaring (which could overflow/underflow) or net-force projection.
        double strength = Math.max(Math.abs(contribution.x()),
                Math.max(Math.abs(contribution.y()), Math.abs(contribution.z())));
        if (strength > 0.0) {
            strengths.put(face, checkedFinite(strengths.getOrDefault(face, 0.0) + strength,
                    "adding standing strengths"));
        }
    }

    private List<Source> validateBatch(ChunkKey key, List<Source> sources) {
        if (sources == null) {
            throw new IllegalArgumentException("sources must not be null");
        }
        List<Source> copy = new ArrayList<>(sources.size());
        Set<BlockKey> physicalBlocks = new HashSet<>();
        for (Source source : sources) {
            if (source == null) {
                throw new IllegalArgumentException("sources must not contain null");
            }
            if (!sourceChunk(source).equals(key)) {
                throw new IllegalArgumentException("source does not belong to replacement chunk");
            }
            if (!physicalBlocks.add(new BlockKey(source.x(), source.y(), source.z()))) {
                throw new IllegalArgumentException("sources must not duplicate a physical block");
            }
            copy.add(source);
        }
        return List.copyOf(copy);
    }

    private void addIndexed(IndexedSource indexed) {
        Map<ColumnKey, NavigableMap<Integer, IndexedSource>> columns = columnsFor(indexed.source().face());
        ColumnKey key = columnKey(indexed.source().face(), indexed.source());
        int coordinate = normalCoordinate(indexed.source().face(), indexed.source());
        NavigableMap<Integer, IndexedSource> column = columns.computeIfAbsent(key, ignored -> new TreeMap<>());
        IndexedSource prior = column.putIfAbsent(coordinate, indexed);
        if (prior != null) {
            throw new IllegalStateException("duplicate indexed floor-field source");
        }
        addRange(indexed.source().profile().range());
    }

    private void removeIndexed(IndexedSource indexed) {
        Map<ColumnKey, NavigableMap<Integer, IndexedSource>> columns = columnsFor(indexed.source().face());
        ColumnKey key = columnKey(indexed.source().face(), indexed.source());
        NavigableMap<Integer, IndexedSource> column = columns.get(key);
        if (column == null) {
            throw new IllegalStateException("missing indexed floor-field column");
        }
        int coordinate = normalCoordinate(indexed.source().face(), indexed.source());
        IndexedSource removed = column.remove(coordinate);
        if (removed != indexed) {
            throw new IllegalStateException("floor-field index ownership mismatch");
        }
        if (column.isEmpty()) {
            columns.remove(key);
        }
        removeRange(indexed.source().profile().range());
    }

    private Iterable<IndexedSource> boundedEntries(Face face, NavigableMap<Integer, IndexedSource> column,
                                                    double pointCoordinate) {
        int lower;
        int upper;
        switch (face) {
            case UP, SOUTH, EAST -> {
                lower = floorBound(pointCoordinate - maxRegisteredRange - 1.0);
                upper = floorBound(pointCoordinate);
            }
            case DOWN, NORTH, WEST -> {
                lower = floorBound(pointCoordinate);
                upper = floorBound(pointCoordinate + maxRegisteredRange);
            }
            default -> throw new IllegalStateException("unknown floor face");
        }
        if (lower > upper) {
            return List.of();
        }
        return column.subMap(lower, true, upper, true).values();
    }

    private void addRange(double range) {
        registeredRanges.merge(range, 1, Integer::sum);
        maxRegisteredRange = registeredRanges.lastKey();
    }

    private void removeRange(double range) {
        Integer count = registeredRanges.get(range);
        if (count == null) {
            throw new IllegalStateException("missing floor-field range ownership");
        }
        if (count == 1) {
            registeredRanges.remove(range);
        } else {
            registeredRanges.put(range, count - 1);
        }
        maxRegisteredRange = registeredRanges.isEmpty() ? 0.0 : registeredRanges.lastKey();
    }

    private static ColumnKey columnKey(Face face, Source source) {
        return switch (face) {
            case DOWN, UP -> new ColumnKey(source.x(), source.z());
            case NORTH, SOUTH -> new ColumnKey(source.x(), source.y());
            case WEST, EAST -> new ColumnKey(source.y(), source.z());
        };
    }

    private static ColumnKey columnKey(Face face, Vec3 point) {
        return switch (face) {
            case DOWN, UP -> columnKey(blockCoordinate(point.x()), blockCoordinate(point.z()));
            case NORTH, SOUTH -> columnKey(blockCoordinate(point.x()), blockCoordinate(point.y()));
            case WEST, EAST -> columnKey(blockCoordinate(point.y()), blockCoordinate(point.z()));
        };
    }

    private static ColumnKey columnKey(Integer first, Integer second) {
        return first == null || second == null ? null : new ColumnKey(first, second);
    }

    private static int normalCoordinate(Face face, Source source) {
        return switch (face) {
            case DOWN, UP -> source.y();
            case NORTH, SOUTH -> source.z();
            case WEST, EAST -> source.x();
        };
    }

    private static double normalCoordinate(Face face, Vec3 point) {
        return switch (face) {
            case DOWN, UP -> point.y();
            case NORTH, SOUTH -> point.z();
            case WEST, EAST -> point.x();
        };
    }

    private Map<ColumnKey, NavigableMap<Integer, IndexedSource>> columnsFor(Face face) {
        return switch (face) {
            case DOWN -> downColumns;
            case UP -> upColumns;
            case NORTH -> northColumns;
            case SOUTH -> southColumns;
            case WEST -> westColumns;
            case EAST -> eastColumns;
        };
    }

    private static ChunkKey sourceChunk(Source source) {
        return new ChunkKey(Math.floorDiv(source.x(), 16), Math.floorDiv(source.z(), 16));
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

    private static Vec3 addChecked(Vec3 left, Vec3 right, String operation) {
        return new Vec3(
                checkedFinite(left.x() + right.x(), operation),
                checkedFinite(left.y() + right.y(), operation),
                checkedFinite(left.z() + right.z(), operation));
    }

    private static Vec3 scaleChecked(Vec3 vector, double factor) {
        return new Vec3(
                checkedFinite(vector.x() * factor, "applying magnetic response"),
                checkedFinite(vector.y() * factor, "applying magnetic response"),
                checkedFinite(vector.z() * factor, "applying magnetic response"));
    }

    private static double checkedFinite(double value, String operation) {
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("floor-field acceleration overflow while " + operation);
        }
        return value;
    }

    private static boolean hasNonzeroComponent(Vec3 vector) {
        return vector.x() != 0.0 || vector.y() != 0.0 || vector.z() != 0.0;
    }

    private static void requireChunkAndOwner(ChunkKey key, Object generationOwner) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (generationOwner == null) {
            throw new IllegalArgumentException("generationOwner must not be null");
        }
    }

    private static void requireFiniteVector(Vec3 vector, String name) {
        if (vector == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        requireFinite(vector.x(), name + ".x");
        requireFinite(vector.y(), name + ".y");
        requireFinite(vector.z(), name + ".z");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private record BlockKey(int x, int y, int z) {
    }

    private record ColumnKey(int firstTangent, int secondTangent) {
    }

    private record IndexedSource(Source source, FloorFieldEmitter emitter) {
    }

    private record ChunkContents(Object generationOwner, List<IndexedSource> sources) {
    }
}
