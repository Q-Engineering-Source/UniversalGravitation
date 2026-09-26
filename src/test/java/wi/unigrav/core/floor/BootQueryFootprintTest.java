package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

class BootQueryFootprintTest {
    private static final BootQueryFootprint.Limits LIMITS = new BootQueryFootprint.Limits(1024, 4096, 64, 512);
    private static final PlayerBodyShape.Dimensions BODY = new PlayerBodyShape.Dimensions(0.6F, 1.8F, 1.62F);

    @ParameterizedTest
    @EnumSource(CardinalFrame.class)
    void everyDeclaredProducerAndItsReadHaloIsIncluded(CardinalFrame frame) {
        var source = source(new Vec3d(0.5, 64.5, -0.5), frame.attitude(), new PlayerBodyShape.Cardinal(frame, BODY));
        var plan = BootQueryFootprint.plan(source, 1.5, LIMITS);
        var producers = new HashSet<>(plan.producers());
        var dependencies = new HashSet<>(plan.dependencies());
        var columns = new HashSet<>(plan.columns());
        assertSame(source, plan.source());
        assertEquals(plan.producers().size(), producers.size());
        assertEquals(plan.dependencies().size(), dependencies.size());
        assertEquals(plan.columns().size(), columns.size());
        assertTrue(producers.size() <= LIMITS.maxProducerCells());
        assertTrue(dependencies.size() <= LIMITS.maxDependencyCells());
        // Independent finite grid contains every possible producer near this source.
        // Boxes here describe the declared profile, not a fabricated Minecraft World.
        for (int x = -5; x <= 5; x++) {
            for (int y = 59; y <= 70; y++) {
                for (int z = -6; z <= 4; z++) {
                    Aabb box = new Aabb(new Vec3d(x, y, z), new Vec3d(x + 1, y + 1.5, z + 1));
                    if (closedIntersects(box, plan.nativeBounds())) assertTrue(producers.contains(new BlockPos(x, y, z)));
                }
            }
        }
        for (BlockPos cell : producers) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -2; dz <= 2; dz++) assertTrue(dependencies.contains(cell.add(dx, dy, dz)));
                }
            }
        }
        for (BlockPos cell : dependencies) {
            assertTrue(columns.contains(new BootQueryFootprint.Column(Math.floorDiv(cell.getX(), 16), Math.floorDiv(cell.getZ(), 16))));
        }
    }

    @Test
    void exactContactSurvivesTheRealNativeStrictIntersectionPredicate() {
        var exactBody = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
        var source = source(new Vec3d(0.5, 65, 0.5), Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, exactBody));
        var plan = BootQueryFootprint.plan(source, 0, LIMITS);
        Aabb query = plan.nativeBounds();
        var nativeQuery = new AxisAlignedBB(query.min().x(), query.min().y(), query.min().z(),
                query.max().x(), query.max().y(), query.max().z());
        assertTrue(nativeQuery.intersects(new AxisAlignedBB(0, 63, 0, 1, 64, 1)));
        assertTrue(plan.producers().contains(new BlockPos(0, 63, 0)));
        assertEquals(0, plan.reachMetres());
    }

    @Test
    void overhangingProducerIsKeptEvenWhenItsUnitCellMissesQuery() {
        var exactBody = new PlayerBodyShape.Dimensions(0.5, 2, 1.75);
        var source = source(new Vec3d(0.5, 65.25, 0.5), Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, exactBody));
        var plan = BootQueryFootprint.plan(source, 0, LIMITS);
        Aabb unit = new Aabb(new Vec3d(0, 63, 0), new Vec3d(1, 64, 1));
        Aabb tall = new Aabb(unit.min(), new Vec3d(1, 64.5, 1));
        assertFalse(closedIntersects(unit, plan.nativeBounds()));
        assertTrue(closedIntersects(tall, plan.nativeBounds()));
        assertTrue(plan.producers().contains(new BlockPos(0, 63, 0)));
    }

    @Test
    void dependencyBudgetIsIndependentOfProducerBudgetAndNoPartialPlanEscapes() {
        var source = source(new Vec3d(0.5, 64.5, 0.5), Rotation3d.identity(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, BODY));
        var plan = BootQueryFootprint.plan(source, 1.5, LIMITS);
        assertTrue(plan.dependencies().size() > 256);
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(source, 1.5,
                new BootQueryFootprint.Limits(plan.producers().size(), plan.dependencies().size() - 1, 64, 512)));
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(source, 1.5,
                new BootQueryFootprint.Limits(plan.producers().size() - 1, 4096, 64, 512)));
        var exact = BootQueryFootprint.plan(source, 1.5, new BootQueryFootprint.Limits(
                plan.producers().size(), plan.dependencies().size(), plan.columns().size(), 512));
        assertEquals(plan.producers(), exact.producers());
        assertEquals(plan.dependencies(), exact.dependencies());
    }

    @Test
    void negativeChunkEdgesUseFloorDivisionAndColumnBudget() {
        var source = source(new Vec3d(-16, 64, -16), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(BODY));
        var plan = BootQueryFootprint.plan(source, 1.5, LIMITS);
        assertTrue(plan.columns().contains(new BootQueryFootprint.Column(-2, -2)));
        assertTrue(plan.columns().contains(new BootQueryFootprint.Column(-1, -1)));
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(source, 1.5,
                new BootQueryFootprint.Limits(1024, 4096, plan.columns().size() - 1, 512)));
    }

    @Test
    void buildHeightClipsDependenciesButDoesNotLoseTheTopCellsOverhang() {
        var sphere = new PlayerBodyShape.Dimensions(0.5, 0.5, 0.25);
        var topSource = source(new Vec3d(0.5, 256.75, 0.5), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(sphere));
        var top = BootQueryFootprint.plan(topSource, 0, LIMITS);
        assertTrue(top.producers().contains(new BlockPos(0, 255, 0)));
        assertTrue(top.dependencies().stream().allMatch(p -> p.getY() >= 0 && p.getY() < 256));
        var below = BootQueryFootprint.plan(source(new Vec3d(0.5, -0.25, 0.5), Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(sphere)), 0, LIMITS);
        assertTrue(below.producers().contains(new BlockPos(0, 0, 0)));
        assertTrue(below.dependencies().stream().allMatch(p -> p.getY() >= 0 && p.getY() < 256));
    }

    @Test
    void emptyHighSpaceDoesNotRequestNonexistentVerticalChunks() {
        for (double y : new double[] {260, -10, 1e12, -1e12}) {
            var plan = BootQueryFootprint.plan(source(new Vec3d(0, y, 0), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(BODY)), 1.5, LIMITS);
            assertTrue(plan.producers().isEmpty());
            assertTrue(plan.dependencies().isEmpty());
            assertTrue(plan.columns().isEmpty());
        }
    }

    @Test
    void freeBodyPlansFromLowerBallAndKeepsSnapshotIdentity() {
        var source = source(new Vec3d(16, 64, 16), new Rotation3d(1, 2, 3, 4), new PlayerBodyShape.FreeCapsule(BODY));
        var plan = BootQueryFootprint.plan(source, 1.5, LIMITS);
        assertSame(source.physical(), plan.source().physical());
        assertTrue(closedIntersects(source.queryBounds(1.5), plan.nativeBounds()));
        assertThrows(UnsupportedOperationException.class, () -> plan.producers().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.columns().clear());
    }

    @Test
    void unsafeCoordinatesAndUnboundedWorkFailBeforeEnumeration() {
        var source = source(new Vec3d(0, 64, 0), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(BODY));
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(source, 1_000_000, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(source, -1, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> new BootQueryFootprint.Limits(0, 4096, 64, 512));
        var remote = source(new Vec3d(1e12, 64, 0), Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(BODY));
        assertThrows(IllegalArgumentException.class, () -> BootQueryFootprint.plan(remote, 1.5, LIMITS));
    }

    private static BootSurfaceGeometry.Source source(Vec3d center, Rotation3d attitude, PlayerBodyShape shape) {
        return BootSurfaceGeometry.fromAccepted(new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(
                new UUID(0, 1), new UUID(0, 2), 0, 3), 4, new PlayerBodyPlacement(center, attitude, shape), new Vec3d(0, 0, 0)));
    }

    private static boolean closedIntersects(Aabb a, Aabb b) {
        return a.min().x() <= b.max().x() && a.max().x() >= b.min().x()
                && a.min().y() <= b.max().y() && a.max().y() >= b.min().y()
                && a.min().z() <= b.max().z() && a.max().z() >= b.min().z();
    }
}
