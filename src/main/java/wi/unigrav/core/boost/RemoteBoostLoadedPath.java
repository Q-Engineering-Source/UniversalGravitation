package wi.unigrav.core.boost;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongPredicate;

import wi.unigrav.api.math.Vec3;

/**
 * Pure, bounded guard for a native collision query whose XZ box grows from a start box by a
 * requested movement vector.
 *
 * <p>The native query sees the full XZ rectangle formed by its start box and its translated end
 * box, rather than only the area occupied by a continuously moving entity box. Consequently this
 * helper deliberately checks every column in that rectangle, including diagonal corners that a
 * moving footprint would not enter. The supplied predicate is a non-loading chunk-column lookup;
 * it receives {@link RemoteBoostLoadedColumns#key(int, int)} values only.
 */
public final class RemoteBoostLoadedPath {
    /** Hard upper bound for both predicate calls and the candidate rectangle's column count. */
    public static final int MAX_COLUMN_PROBES = 64;

    private static final int GEOMETRIC_MARGIN_ULPS = 8;

    private RemoteBoostLoadedPath() {
    }

    /**
     * Clips a finite proposed displacement before the first unloaded XZ column enters the growing
     * native query envelope.
     *
     * <p>{@code startQueryBounds} is the caller's already-expanded XZ portion of the initial
     * native collision AABB. No entity dimensions, Y bounds, query margin, Minecraft classes, or
     * loading operations are assumed here. The returned endpoint always equals
     * {@code start.lerp(candidate, fraction)} when clipping occurs, so X, Y, and Z retain the same
     * displacement fraction. An all-loaded candidate returns {@code candidate} exactly.
     *
     * @throws IllegalArgumentException if coordinates are non-finite, the translated query bounds
     *         cannot be represented as finite int-indexed chunk columns, or their rectangle has
     *         more than {@value #MAX_COLUMN_PROBES} columns
     */
    public static Result clip(Vec3 start, Vec3 candidate, XzBounds startQueryBounds,
            LongPredicate loadedChunk) {
        requireFinite(Objects.requireNonNull(start, "start"), "start");
        requireFinite(Objects.requireNonNull(candidate, "candidate"), "candidate");
        Objects.requireNonNull(startQueryBounds, "startQueryBounds");
        Objects.requireNonNull(loadedChunk, "loadedChunk");

        double deltaX = candidate.x() - start.x();
        double deltaY = candidate.y() - start.y();
        double deltaZ = candidate.z() - start.z();
        if (!Double.isFinite(deltaX) || !Double.isFinite(deltaY) || !Double.isFinite(deltaZ)) {
            throw new IllegalArgumentException("Movement delta must be finite");
        }

        double candidateMinX = translated(startQueryBounds.minX(), deltaX);
        double candidateMaxX = translated(startQueryBounds.maxX(), deltaX);
        double candidateMinZ = translated(startQueryBounds.minZ(), deltaZ);
        double candidateMaxZ = translated(startQueryBounds.maxZ(), deltaZ);
        int minChunkX = chunkAt(Math.min(startQueryBounds.minX(), candidateMinX));
        int maxChunkX = chunkAt(Math.max(startQueryBounds.maxX(), candidateMaxX));
        int minChunkZ = chunkAt(Math.min(startQueryBounds.minZ(), candidateMinZ));
        int maxChunkZ = chunkAt(Math.max(startQueryBounds.maxZ(), candidateMaxZ));
        requireBoundedColumnCount(minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        MissingColumn earliestMissing = null;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long key = RemoteBoostLoadedColumns.key(chunkX, chunkZ);
                if (loadedChunk.test(key)) continue;

                double enterX = firstAxisEntry(startQueryBounds.minX(), startQueryBounds.maxX(),
                        deltaX, chunkX);
                double enterZ = firstAxisEntry(startQueryBounds.minZ(), startQueryBounds.maxZ(),
                        deltaZ, chunkZ);
                double entry = Math.max(enterX, enterZ);
                MissingColumn missing = new MissingColumn(chunkX, chunkZ, entry, enterX, enterZ);
                if (entry == 0.0D) {
                    return stoppedAtStart(start, key);
                }
                if (earliestMissing == null || entry < earliestMissing.entry()) {
                    earliestMissing = missing;
                }
            }
        }

        if (earliestMissing == null) {
            return new Result(candidate, 1.0D, OptionalLong.empty());
        }

        double fraction = fractionBefore(startQueryBounds, deltaX, deltaZ, earliestMissing);
        Vec3 endpoint = start.lerp(candidate, fraction);
        return new Result(endpoint, fraction, OptionalLong.of(
                RemoteBoostLoadedColumns.key(earliestMissing.chunkX(), earliestMissing.chunkZ())));
    }

    private static Result stoppedAtStart(Vec3 start, long missingKey) {
        return new Result(start, 0.0D, OptionalLong.of(missingKey));
    }

    private static double translated(double coordinate, double delta) {
        double translated = coordinate + delta;
        if (!Double.isFinite(translated)) {
            throw new IllegalArgumentException("Translated query bounds must be finite");
        }
        return translated;
    }

    private static int chunkAt(double coordinate) {
        double chunk = Math.floor(coordinate / 16.0D);
        if (!Double.isFinite(chunk) || chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Query bounds exceed int-indexed chunk columns");
        }
        return (int) chunk;
    }

    private static void requireBoundedColumnCount(int minChunkX, int maxChunkX, int minChunkZ,
            int maxChunkZ) {
        long width = (long) maxChunkX - minChunkX + 1L;
        long depth = (long) maxChunkZ - minChunkZ + 1L;
        if (width <= 0L || depth <= 0L || width > MAX_COLUMN_PROBES
                || depth > MAX_COLUMN_PROBES / width) {
            throw new IllegalArgumentException("Native query envelope exceeds " + MAX_COLUMN_PROBES
                    + " chunk columns");
        }
    }

    private static double firstAxisEntry(double startMin, double startMax, double delta,
            int chunk) {
        int startMinChunk = chunkAt(startMin);
        int startMaxChunk = chunkAt(startMax);
        if (chunk >= startMinChunk && chunk <= startMaxChunk) return 0.0D;

        double chunkMin = (double) chunk * 16.0D;
        if (delta > 0.0D) {
            return (chunkMin - startMax) / delta;
        }
        if (delta < 0.0D) {
            return (startMin - (chunkMin + 16.0D)) / -delta;
        }
        throw new IllegalStateException("Candidate envelope introduced a column without XZ movement");
    }

    private static double fractionBefore(XzBounds bounds, double deltaX, double deltaZ,
            MissingColumn missing) {
        double margin = 0.0D;
        if (missing.enterX() >= missing.enterZ()) {
            margin = Math.max(margin, axisMargin(bounds.minX(), bounds.maxX(), deltaX,
                    missing.chunkX()));
        }
        if (missing.enterZ() >= missing.enterX()) {
            margin = Math.max(margin, axisMargin(bounds.minZ(), bounds.maxZ(), deltaZ,
                    missing.chunkZ()));
        }
        double beforeBoundary = missing.entry() - margin;
        return beforeBoundary > 0.0D ? Math.nextDown(beforeBoundary) : 0.0D;
    }

    private static double axisMargin(double startMin, double startMax, double delta, int chunk) {
        if (delta == 0.0D) return 0.0D;
        double boundary = delta > 0.0D ? (double) chunk * 16.0D
                : (double) chunk * 16.0D + 16.0D;
        double relevantStartEdge = delta > 0.0D ? startMax : startMin;
        double relevantEndEdge = relevantStartEdge + delta;
        double magnitude = Math.max(Math.abs(boundary),
                Math.max(Math.abs(relevantStartEdge), Math.abs(relevantEndEdge)));
        double spatialMargin = GEOMETRIC_MARGIN_ULPS * Math.ulp(magnitude);
        return spatialMargin / Math.abs(delta);
    }

    private static void requireFinite(Vec3 vector, String name) {
        if (!Double.isFinite(vector.x()) || !Double.isFinite(vector.y())
                || !Double.isFinite(vector.z())) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    /** Immutable XZ portion of the initial, caller-expanded native collision-query AABB. */
    public record XzBounds(double minX, double maxX, double minZ, double maxZ) {
        public XzBounds {
            if (!Double.isFinite(minX) || !Double.isFinite(maxX) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxZ)) {
                throw new IllegalArgumentException("Query bounds must be finite");
            }
            if (minX > maxX || minZ > maxZ) {
                throw new IllegalArgumentException("Inverted XZ query bounds");
            }
        }
    }

    /** Immutable clipping outcome; an empty missing key means the original candidate was covered. */
    public record Result(Vec3 endpoint, double fraction, OptionalLong firstMissingColumn) {
        public Result {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(firstMissingColumn, "firstMissingColumn");
            if (!Double.isFinite(fraction) || fraction < 0.0D || fraction > 1.0D) {
                throw new IllegalArgumentException("Fraction must be finite and in [0, 1]");
            }
        }

        public boolean fullyLoaded() {
            return firstMissingColumn.isEmpty();
        }
    }

    private record MissingColumn(int chunkX, int chunkZ, double entry, double enterX,
            double enterZ) {
    }
}
