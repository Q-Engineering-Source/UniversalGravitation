package wi.unigrav.core.player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.player.CollisionQueryFootprint.Column;
import wi.unigrav.core.player.CollisionQueryFootprint.Limits;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;

import static org.junit.jupiter.api.Assertions.*;

class CollisionQueryFootprintTest {
    private static final Limits LIMITS = new Limits(65_536, 64, 2048, 512);

    @Test
    void nativeQueryWideningIncludesStrictlyExcludedExactFaceContacts() {
        Aabb requested = box(-0.25, -1, -0.25, 0.25, 1, 0.25);
        AxisAlignedBB wall = new AxisAlignedBB(0.25, -2, -2, 1.25, 2, 2);
        assertFalse(nativeBox(requested).intersects(wall));
        var plan = CollisionQueryFootprint.plan(requested, 2, LIMITS);
        assertTrue(nativeBox(plan.nativeBounds()).intersects(wall));
        assertEquals(requested, plan.requestedBounds());
        assertEquals(Math.nextDown(requested.min().x()), plan.nativeBounds().min().x());
        assertEquals(Math.nextUp(requested.max().x()), plan.nativeBounds().max().x());
    }

    @Test
    void nativeStrictPolicyKeepsExactBoundsAndDoesNotInventEdgeSupport() {
        Aabb requested = box(1, -0.6, -0.25, 1.5, 1.15, 0.25);
        AxisAlignedBB floor = new AxisAlignedBB(-1, -1, -1, 1, 0, 1);
        var strict = CollisionQueryFootprint.plan(requested, 2, QueryPolicy.NATIVE_STRICT, LIMITS);
        var contact = CollisionQueryFootprint.plan(requested, 2, QueryPolicy.CAPSULE_CONTACT, LIMITS);
        assertSame(requested, strict.nativeBounds());
        assertEquals(QueryPolicy.NATIVE_STRICT, strict.policy());
        assertEquals(QueryPolicy.CAPSULE_CONTACT, contact.policy());
        assertFalse(nativeBox(strict.nativeBounds()).intersects(floor));
        assertTrue(nativeBox(contact.nativeBounds()).intersects(floor));
        assertEquals(CollisionQueryFootprint.plan(requested, 2, LIMITS).nativeBounds(), contact.nativeBounds());
    }

    @ParameterizedTest
    @EnumSource(QueryPolicy.class)
    void footprintContainsTheActualNativeBlockAndEntityLoopColumns(QueryPolicy policy) {
        for (Aabb requested : List.of(box(-0.25, -1, -0.25, 2.25, 1, 0.25),
                box(15.999999, 254, -16.25, 17.25, 258, -15.75),
                box(29_999_999.75, 299, -30_000_000.25, 30_000_002.25, 301, -29_999_999.75))) {
            for (double radius : new double[]{0, 2, 18}) {
                var plan = CollisionQueryFootprint.plan(requested, radius, policy, LIMITS);
                AxisAlignedBB bounds = nativeBox(plan.nativeBounds());
                int x0 = MathHelper.floor(bounds.minX) - 1;
                int y0 = MathHelper.floor(bounds.minY) - 1;
                int z0 = MathHelper.floor(bounds.minZ) - 1;
                int x1 = MathHelper.ceil(bounds.maxX) + 1;
                int y1 = MathHelper.ceil(bounds.maxY) + 1;
                int z1 = MathHelper.ceil(bounds.maxZ) + 1;
                assertEquals((long) (x1 - x0) * (y1 - y0) * (z1 - z0), plan.blockCellsUpperBound());
                Set<Column> required = new HashSet<>();
                for (int x = x0; x < x1; x++) {
                    for (int z = z0; z < z1; z++) required.add(new Column(x >> 4, z >> 4));
                }
                AxisAlignedBB entityQuery = bounds.grow(0.25);
                int ex0 = MathHelper.floor((entityQuery.minX - radius) / 16);
                int ex1 = MathHelper.floor((entityQuery.maxX + radius) / 16);
                int ez0 = MathHelper.floor((entityQuery.minZ - radius) / 16);
                int ez1 = MathHelper.floor((entityQuery.maxZ + radius) / 16);
                for (int x = ex0; x <= ex1; x++) {
                    for (int z = ez0; z <= ez1; z++) required.add(new Column(x, z));
                }
                assertTrue(new HashSet<>(plan.columns()).containsAll(required));
                assertEquals(MathHelper.clamp(MathHelper.floor((entityQuery.minY - radius) / 16), 0, 15),
                        plan.firstEntitySection());
                assertEquals(MathHelper.clamp(MathHelper.floor((entityQuery.maxY + radius) / 16), 0, 15),
                        plan.lastEntitySection());
            }
        }
    }

    @Test
    void highAndNegativeYDoNotInventMissingChunkColumns() {
        var ground = CollisionQueryFootprint.plan(box(4.5, 99, 4.5, 6.5, 101, 6.5), 2, LIMITS);
        var above = CollisionQueryFootprint.plan(box(4.5, 999, 4.5, 6.5, 1001, 6.5), 2, LIMITS);
        var below = CollisionQueryFootprint.plan(box(4.5, -1001, 4.5, 6.5, -999, 6.5), 2, LIMITS);
        assertEquals(ground.columns(), above.columns());
        assertEquals(ground.columns(), below.columns());
        assertEquals(15, above.firstEntitySection());
        assertEquals(15, above.lastEntitySection());
        assertEquals(0, below.firstEntitySection());
        assertEquals(0, below.lastEntitySection());
        assertEquals(ground.blockCellsUpperBound(), above.blockCellsUpperBound());
    }

    @Test
    void mutableRuntimeRadiusIsAnInputRatherThanAnAssumedTwoMetres() {
        Aabb requested = box(7, 7, 7, 9, 9, 9);
        var narrow = CollisionQueryFootprint.plan(requested, 0, LIMITS);
        var wide = CollisionQueryFootprint.plan(requested, 32, LIMITS);
        assertTrue(wide.columns().size() > narrow.columns().size());
        assertEquals(32, wide.entityRadius());
        assertThrows(UnsupportedOperationException.class, () -> wide.columns().clear());
    }

    @Test
    void hugeOverflowingOrOverBudgetQueriesFailBeforeEnumerating() {
        Aabb ordinary = box(-0.25, -1, -0.25, 0.25, 1, 0.25);
        assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(ordinary, 2, new Limits(1, 64, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(ordinary, 2, new Limits(65_536, 1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(
                box(-1_000_000_000, -1_000_000_000, -1_000_000_000, 1_000_000_000, 1_000_000_000, 1_000_000_000), 2, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(
                box(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, 1, 1), 2, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(
                box(Double.MAX_VALUE, 0, 0, Double.MAX_VALUE, 1, 1), 2, LIMITS));
        for (double radius : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> CollisionQueryFootprint.plan(ordinary, radius, LIMITS));
        }
        assertThrows(IllegalArgumentException.class, () -> new Limits(Long.MAX_VALUE, 64, 2048, 512));
    }

    private static Aabb box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new Aabb(new Vec3d(x0, y0, z0), new Vec3d(x1, y1, z1));
    }

    private static AxisAlignedBB nativeBox(Aabb box) {
        return new AxisAlignedBB(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z());
    }
}
