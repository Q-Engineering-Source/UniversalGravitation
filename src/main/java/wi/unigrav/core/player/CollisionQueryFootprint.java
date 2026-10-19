package wi.unigrav.core.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;

/** Bounded native block/entity scan footprint, not a collision or loaded-state certificate. */
public final class CollisionQueryFootprint {
    /** Native support probes must not inherit the capsule's exact-contact expansion. */
    public enum QueryPolicy { NATIVE_STRICT, CAPSULE_CONTACT }

    public record Limits(long maxBlockCells, int maxChunkColumns, int maxRootEntities, int maxCandidateBoxes) {
        public Limits {
            if (maxBlockCells < 1 || maxBlockCells > 1_000_000 || maxChunkColumns < 1 || maxChunkColumns > 256
                    || maxRootEntities < 1 || maxRootEntities > 100_000 || maxCandidateBoxes < 1 || maxCandidateBoxes > 4096) {
                throw new IllegalArgumentException("native collision limits exceed the bounded observation profile");
            }
        }
    }

    public record Column(int x, int z) {
    }

    private final Aabb requestedBounds;
    private final Aabb nativeBounds;
    private final QueryPolicy policy;
    private final double entityRadius;
    private final long blockCells;
    private final List<Column> columns;
    private final int firstEntitySection;
    private final int lastEntitySection;

    private CollisionQueryFootprint(Aabb requestedBounds, Aabb nativeBounds, QueryPolicy policy, double entityRadius, long blockCells,
                                    List<Column> columns, int firstEntitySection, int lastEntitySection) {
        this.requestedBounds = requestedBounds;
        this.nativeBounds = nativeBounds;
        this.policy = policy;
        this.entityRadius = entityRadius;
        this.blockCells = blockCells;
        this.columns = List.copyOf(columns);
        this.firstEntitySection = firstEntitySection;
        this.lastEntitySection = lastEntitySection;
    }

    public static CollisionQueryFootprint plan(Aabb requestedBounds, double entityRadius, Limits limits) {
        return plan(requestedBounds, entityRadius, QueryPolicy.CAPSULE_CONTACT, limits);
    }

    public static CollisionQueryFootprint plan(Aabb requestedBounds, double entityRadius, QueryPolicy policy, Limits limits) {
        return planInternal(requestedBounds, entityRadius, policy, limits, null);
    }

    static CollisionQueryFootprint plan(Aabb requestedBounds, double entityRadius, QueryPolicy policy, Limits limits,
            EnvironmentObservationWork work) {
        Objects.requireNonNull(work).requireCurrent();
        return planInternal(requestedBounds, entityRadius, policy, limits, work);
    }

    private static CollisionQueryFootprint planInternal(Aabb requestedBounds, double entityRadius,
            QueryPolicy policy, Limits limits, EnvironmentObservationWork work) {
        Objects.requireNonNull(requestedBounds, "requestedBounds");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(limits, "limits");
        if (!Double.isFinite(entityRadius) || entityRadius < 0) {
            throw new IllegalArgumentException("native MAX_ENTITY_RADIUS must be finite and nonnegative");
        }
        // Capsule casts need exact face contacts even when the tube bound is
        // exact. Native cardinal sneak probes need the opposite: preserve the
        // original strict emptiness result, including exact edge contact.
        Aabb nativeBounds = policy == QueryPolicy.NATIVE_STRICT ? requestedBounds
                : new Aabb(outward(requestedBounds.min(), false), outward(requestedBounds.max(), true));
        long minX = nativeIndex(nativeBounds.min().x(), false, work) - 1L;
        long minY = nativeIndex(nativeBounds.min().y(), false, work) - 1L;
        long minZ = nativeIndex(nativeBounds.min().z(), false, work) - 1L;
        long maxX = nativeIndex(nativeBounds.max().x(), true, work);
        long maxY = nativeIndex(nativeBounds.max().y(), true, work);
        long maxZ = nativeIndex(nativeBounds.max().z(), true, work);
        long cellsX = maxX - minX + 1;
        long cellsY = maxY - minY + 1;
        long cellsZ = maxZ - minZ + 1;
        long cellsXY = productWithin(cellsX, cellsY, limits.maxBlockCells(), "block-cell XY",
                cellsX, cellsY, cellsZ);
        long cells = productWithin(cellsXY, cellsZ, limits.maxBlockCells(), "block-cell XYZ",
                cellsX, cellsY, cellsZ);

        // Reproduce the rounded native grow(.25), radius and /16 chains. These
        // are bounds of the actual native loop, not an ideal unrounded formula.
        int minChunkX = Math.min((int) Math.floorDiv(minX, 16), entityChunk(nativeBounds.min().x(), entityRadius, false));
        int minChunkZ = Math.min((int) Math.floorDiv(minZ, 16), entityChunk(nativeBounds.min().z(), entityRadius, false));
        int maxChunkX = Math.max((int) Math.floorDiv(maxX, 16), entityChunk(nativeBounds.max().x(), entityRadius, true));
        int maxChunkZ = Math.max((int) Math.floorDiv(maxZ, 16), entityChunk(nativeBounds.max().z(), entityRadius, true));
        long columnsX = (long) maxChunkX - minChunkX + 1;
        long columnsZ = (long) maxChunkZ - minChunkZ + 1;
        long columnCount = productWithin(columnsX, columnsZ, limits.maxChunkColumns(), "chunk-columns XZ",
                columnsX, columnsZ, -1);
        List<Column> columns = new ArrayList<>((int) columnCount);
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
                columns.add(new Column(x, z));
            }
        }
        return new CollisionQueryFootprint(requestedBounds, nativeBounds, policy, entityRadius, cells, columns,
                entitySection(nativeBounds.min().y(), entityRadius, false),
                entitySection(nativeBounds.max().y(), entityRadius, true));
    }

    public Aabb requestedBounds() { return requestedBounds; }
    public Aabb nativeBounds() { return nativeBounds; }
    public QueryPolicy policy() { return policy; }
    public double entityRadius() { return entityRadius; }
    public long blockCellsUpperBound() { return blockCells; }
    public List<Column> columns() { return columns; }
    public int firstEntitySection() { return firstEntitySection; }
    public int lastEntitySection() { return lastEntitySection; }

    private static Vec3d outward(Vec3d v, boolean upper) {
        return new Vec3d(upper ? Math.nextUp(v.x()) : Math.nextDown(v.x()),
                upper ? Math.nextUp(v.y()) : Math.nextDown(v.y()),
                upper ? Math.nextUp(v.z()) : Math.nextDown(v.z()));
    }

    private static int nativeIndex(double coordinate, boolean ceiling) {
        return nativeIndex(coordinate, ceiling, null);
    }

    private static int nativeIndex(double coordinate, boolean ceiling, EnvironmentObservationWork work) {
        if (work != null) work.charge(EnvironmentObservationWork.Unit.COORDINATE_CONVERSION);
        double integer = ceiling ? Math.ceil(coordinate) : Math.floor(coordinate);
        if (!Double.isFinite(integer) || integer < Integer.MIN_VALUE + 2.0 || integer > Integer.MAX_VALUE - 2.0) {
            throw new IllegalArgumentException("native query exceeds safe int-indexed coordinates");
        }
        return (int) integer;
    }

    private static double entitySectionCoordinate(double bound, double radius, boolean upper) {
        double grown = upper ? bound + 0.25 : bound - 0.25;
        double expanded = upper ? grown + radius : grown - radius;
        double result = Math.floor(expanded / 16.0);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("entity query range is not representable");
        return result;
    }

    private static int entityChunk(double bound, double radius, boolean upper) {
        return nativeIndex(entitySectionCoordinate(bound, radius, upper), false);
    }

    private static int entitySection(double bound, double radius, boolean upper) {
        return (int) Math.clamp(entitySectionCoordinate(bound, radius, upper), 0, 15);
    }

    private static long productWithin(long a, long b, long limit, String phase,
                                      long firstExtent, long secondExtent, long thirdExtent) {
        if (a <= 0 || b <= 0 || a > limit / b) {
            String extents = thirdExtent < 0
                    ? "extents=(xChunks=" + firstExtent + ", zChunks=" + secondExtent + ")"
                    : "extents=(xCells=" + firstExtent + ", yCells=" + secondExtent
                            + ", zCells=" + thirdExtent + ")";
            throw new IllegalArgumentException("native query footprint exceeds the configured work limit"
                    + ": phase=" + phase + ", left=" + a + ", right=" + b + ", limit=" + limit
                    + ", " + extents);
        }
        return a * b;
    }
}
