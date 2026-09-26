package wi.unigrav.core.floor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.BlockPos;
import wi.unigrav.core.player.BootSurfaceGeometry;

/**
 * Finite producer/dependency plan for the known native boot-surface callback profile.
 *
 * <p>Supported producers emit inside local [0,1] X/Z and [0,1.5] Y; callbacks
 * may read neighbours up to two cells horizontally and one vertically: a native
 * fence/wall can ask an adjacent stair for its face shape, which reads that
 * stair's own horizontal neighbours. This is not a guarantee for arbitrary
 * mod callbacks or a loaded-world certificate. A collector must admit its actual
 * callbacks separately, preflight all dependency columns and retain failures.
 */
public final class BootQueryFootprint {
    /** Explicit caller-supplied work limits, not equipment tier or capture-distance defaults. */
    public record Limits(int maxProducerCells, int maxDependencyCells, int maxChunkColumns, int maxCandidateBoxes) {
        public Limits {
            if (maxProducerCells < 1 || maxProducerCells > 8192
                    || maxDependencyCells < 1 || maxDependencyCells > 32768
                    || maxChunkColumns < 1 || maxChunkColumns > 256
                    || maxCandidateBoxes < 1 || maxCandidateBoxes > 4096) {
                throw new IllegalArgumentException("boot observation limits exceed the finite query profile");
            }
        }
    }

    public record Column(int x, int z) {
    }

    private final BootSurfaceGeometry.Source source;
    private final double reachMetres;
    private final Aabb nativeBounds;
    private final List<BlockPos> producers;
    private final List<BlockPos> dependencies;
    private final List<Column> columns;
    private final Limits limits;

    private BootQueryFootprint(BootSurfaceGeometry.Source source, double reachMetres, Aabb nativeBounds,
                               List<BlockPos> producers, List<BlockPos> dependencies, List<Column> columns, Limits limits) {
        this.source = source;
        this.reachMetres = reachMetres;
        this.nativeBounds = nativeBounds;
        this.producers = List.copyOf(producers);
        this.dependencies = List.copyOf(dependencies);
        this.columns = List.copyOf(columns);
        this.limits = limits;
    }

    public static BootQueryFootprint plan(BootSurfaceGeometry.Source source, double reachMetres, Limits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Aabb sensorQuery = source.queryBounds(reachMetres);
        // Native addCollisionBoxToList uses strict intersects. Expand the query,
        // not the actual surface or capture reach, to retain exact face contacts.
        Aabb nativeQuery = new Aabb(outward(sensorQuery.min(), false), outward(sensorQuery.max(), true));
        // The declared native profile has no producer below Y=0 or above
        // the highest cell's 1.5m extension. Empty space needs no chunk reads.
        if (nativeQuery.max().y() < 0.0 || nativeQuery.min().y() > 256.5) {
            return new BootQueryFootprint(source, reachMetres, nativeQuery, List.of(), List.of(), List.of(), limits);
        }
        int minX = index(nativeQuery.min().x()) - 1;
        int maxX = index(nativeQuery.max().x());
        int minZ = index(nativeQuery.min().z()) - 1;
        int maxZ = index(nativeQuery.max().z());
        int minY = nativeQuery.min().y() <= 0.0 ? 0 : Math.max(0, index(nativeQuery.min().y()) - 1);
        int maxY = nativeQuery.max().y() >= 255.0 ? 255 : Math.min(255, index(nativeQuery.max().y()));
        if (minY > maxY) {
            return new BootQueryFootprint(source, reachMetres, nativeQuery, List.of(), List.of(), List.of(), limits);
        }

        // floor(qMin)-1 also contains any [cellY,cellY+1.5] producer intersecting
        // the closed query. A unit-cell-only scan would lose those overhanging boxes.
        int producerCount = cellCount(minX, minY, minZ, maxX, maxY, maxZ, limits.maxProducerCells());
        int dependencyMinY = Math.max(0, minY - 1);
        int dependencyMaxY = Math.min(255, maxY + 1);
        int dependencyCount = cellCount(minX - 2, dependencyMinY, minZ - 2, maxX + 2, dependencyMaxY, maxZ + 2,
                limits.maxDependencyCells());
        int minChunkX = Math.floorDiv(minX - 2, 16);
        int maxChunkX = Math.floorDiv(maxX + 2, 16);
        int minChunkZ = Math.floorDiv(minZ - 2, 16);
        int maxChunkZ = Math.floorDiv(maxZ + 2, 16);
        int columnCount = product((long) maxChunkX - minChunkX + 1, (long) maxChunkZ - minChunkZ + 1,
                limits.maxChunkColumns());

        // All cardinalities are checked before allocating or iterating a planned domain.
        List<BlockPos> producers = cells(minX, minY, minZ, maxX, maxY, maxZ, producerCount);
        List<BlockPos> dependencies = cells(minX - 2, dependencyMinY, minZ - 2, maxX + 2, dependencyMaxY, maxZ + 2,
                dependencyCount);
        List<Column> columns = new ArrayList<>(columnCount);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) columns.add(new Column(x, z));
        }
        return new BootQueryFootprint(source, reachMetres, nativeQuery, producers, dependencies, columns, limits);
    }

    public BootSurfaceGeometry.Source source() { return source; }
    public double reachMetres() { return reachMetres; }
    public Aabb nativeBounds() { return nativeBounds; }
    public List<BlockPos> producers() { return producers; }
    public List<BlockPos> dependencies() { return dependencies; }
    public List<Column> columns() { return columns; }
    public Limits limits() { return limits; }

    private static List<BlockPos> cells(int x0, int y0, int z0, int x1, int y1, int z1, int count) {
        List<BlockPos> cells = new ArrayList<>(count);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    private static int cellCount(int x0, int y0, int z0, int x1, int y1, int z1, int limit) {
        return product(product((long) x1 - x0 + 1, (long) y1 - y0 + 1, limit), (long) z1 - z0 + 1, limit);
    }

    private static int product(long a, long b, int limit) {
        if (a < 1 || b < 1 || a > limit / b) {
            throw new IllegalArgumentException("boot query exceeds its configured work limit");
        }
        return (int) (a * b);
    }

    private static int index(double value) {
        double floor = Math.floor(value);
        if (!Double.isFinite(floor) || floor < Integer.MIN_VALUE + 3.0 || floor > Integer.MAX_VALUE - 3.0) {
            throw new IllegalArgumentException("boot query is outside safe int-indexed coordinates");
        }
        return (int) floor;
    }

    private static Vec3d outward(Vec3d value, boolean upper) {
        double x = upper ? Math.nextUp(value.x()) : Math.nextDown(value.x());
        double y = upper ? Math.nextUp(value.y()) : Math.nextDown(value.y());
        double z = upper ? Math.nextUp(value.z()) : Math.nextDown(value.z());
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new ArithmeticException("native contact query cannot be represented");
        }
        return new Vec3d(x, y, z);
    }
}
