package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModeTransitionPreparedDiagnosticsTest {
    @Test void finalRuntimeSampleNominalCapsuleIsExactlyContained() {
        var size = new PlayerBodyShape.Dimensions(0.6000000238418579, 1.7999999523162842, 1.6200000047683716);
        var cardinal = PlayerBodyPlacement.fromTransport(
                new Vec3d(34.01196263193376, 91.59340279684535, 8.093212564301698),
                CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, size));
        var virtual = new PlayerBodyPlacement(cardinal.center(), cardinal.attitude(), new PlayerBodyShape.FreeCapsule(size));
        var result = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                CardinalCapsuleContainment.prepare(snapshot(cardinal), virtual, new StoredDyadic.Work()));
        var diagnosis = ModeTransitionPrepared.containmentDiagnostic(cardinal.bounds(), result);
        assertEquals(6, diagnosis.faces().size());
        assertTrue(diagnosis.faces().stream().allMatch(ModeTransitionPrepared.ContainmentFace::contained));
        diagnosis.faces().forEach(face -> assertEquals(face.sourceBoundary(), face.outwardBoundary()));
    }

    @Test void outsideDiagnosticKeepsAllSixExactBoundariesWithoutSpendingWork() {
        var size = new PlayerBodyShape.Dimensions(0.5, 2, 1.5);
        var virtual = new PlayerBodyPlacement(new Vec3d(0.1, 0, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(size));
        var box = virtual.bounds();
        var cardinal = PlayerBodyPlacement.fromCardinalBounds(box,
                new Vec3d(box.center().x(), box.min().y(), box.center().z()), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, size));
        var work = new StoredDyadic.Work();
        var outside = assertInstanceOf(CardinalCapsuleContainment.Outside.class,
                CardinalCapsuleContainment.prepare(snapshot(cardinal), virtual, work));
        long before = work.units();
        var diagnosis = ModeTransitionPrepared.containmentDiagnostic(box, outside);
        assertEquals(before, work.units());
        assertSame(outside.outwardCapsuleBounds(), diagnosis.outwardBounds());
        assertEquals(outside.numericWork(), diagnosis.numericWork());
        assertEquals(List.of("minX", "minY", "minZ", "maxX", "maxY", "maxZ"),
                diagnosis.faces().stream().map(ModeTransitionPrepared.ContainmentFace::face).toList());
        var maxX = diagnosis.faces().get(3);
        assertFalse(maxX.contained());
        assertEquals(Double.toHexString(box.max().x()), maxX.toString().split("source=")[1].split(",")[0]);
        assertThrows(UnsupportedOperationException.class, () -> diagnosis.faces().clear());
    }

    @Test void arcDiagnosticPreservesActualUnresolvedContactAndSharedLedger() {
        var box = new Aabb(new Vec3d(0, 0, 0), new Vec3d(1, 1, 1));
        var capsule = new Capsule(new Segment3d(new Vec3d(-2, .5, .5), new Vec3d(-2, .5, .5)), .25);
        var budget = new CapsuleContactQueryBudget(new QueryLimits(8192, 1, 128, 32, 2336, 40000, 64), 8);
        var contact = budget.query(capsule, box, FreeOwnerTransaction.ACCURACY);
        assertEquals(Relation.UNRESOLVED, contact.relation());
        var query = new CapsuleArcValidator.Query(CapsuleArcValidator.Stage.TARGET, 1, 1, 7, box, contact);
        var failed = new CapsuleArcValidator.Failed(CapsuleArcValidator.Failure.UNRESOLVED, List.of(query), 0, 42, null);
        var before = budget.work();
        long calls = budget.calls();
        var diagnosis = ModeTransitionPrepared.arcDiagnostic(failed, budget);
        assertEquals(CapsuleArcValidator.Failure.UNRESOLVED, diagnosis.failure());
        assertSame(query, diagnosis.queries().getFirst());
        assertSame(contact, diagnosis.queries().getFirst().result());
        assertEquals(7, diagnosis.queries().getFirst().ordinal());
        assertEquals(1, diagnosis.queries().getFirst().from());
        assertEquals(1, diagnosis.queries().getFirst().to());
        assertEquals(42, diagnosis.numericWork());
        assertSame(FreeOwnerTransaction.ACCURACY, diagnosis.accuracy());
        assertEquals(before, diagnosis.queryWork());
        assertEquals(calls, diagnosis.queryCalls());
        assertEquals(before, budget.work());
        assertEquals(calls, budget.calls());
        assertThrows(UnsupportedOperationException.class, () -> diagnosis.queries().clear());
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerBodyPlacement placement) {
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(
                new UUID(0, 4531), new UUID(0, 4532), 0, 1), 1283, placement, new Vec3d(0, 0, 0));
    }
}
