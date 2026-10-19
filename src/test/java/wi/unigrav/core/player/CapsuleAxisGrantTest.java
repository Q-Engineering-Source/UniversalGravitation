package wi.unigrav.core.player;

import java.math.BigDecimal;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;

import static org.junit.jupiter.api.Assertions.*;

class CapsuleAxisGrantTest {
    private static CapsulePlacementMath.Pose start(StoredDyadic.Work work) {
        return CapsulePlacementMath.pose(new PlayerBodyPlacement(new Vec3d(8, 68.34999997615814, 8),
                Rotation3d.identity(), new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(
                        0.6000000238418579, 1.7999999523162842, 1.6200000047683716))), work);
    }

    @Test void exactCapTranslationReservesReconstructsAndCommitsTheRealLedgerGrant() {
        var work = new StoredDyadic.Work();
        var start = start(work);
        var tube = CapsulePlacementMath.translationTube(start, new Vec3d(0, 3.5, 0), work);
        assertEquals(3.5, tube.requestedDisplacementUpper(), 0);
        var ledger = new BoostMovementBudget(70, 0, vector(start.placement().transport()));
        var ticket = ledger.reserveCenterPath(1, 0, vector(start.placement().transport()),
                vector(start.placement().center()), tube.requestedDisplacementUpper());
        assertTrue(ticket.accepted());
        assertEquals(3.5, ticket.grant(), 0);
        long beforePrefix = work.units();
        var end = CapsulePlacementMath.atPrefix(tube, 1, work);
        assertTrue(work.units() > beforePrefix, "endpoint proof charges the same numeric ledger");
        assertEquals(3.5, CapsulePlacementMath.displacementUpper(start, end, work), 0);
        assertTrue(ledger.commitCenterPath(ticket, vector(end.placement().center()),
                vector(end.placement().transport()), tube.requestedDisplacementUpper()));
        assertFalse(ledger.snapshot().pending());
    }

    @Test void allSixStoredAxisDirectionsKeepTheExactCapAndSharedWork() {
        for (var delta : new Vec3d[] {new Vec3d(3.5,0,0), new Vec3d(-3.5,0,0),
                new Vec3d(0,3.5,0), new Vec3d(0,-3.5,0), new Vec3d(0,0,3.5), new Vec3d(0,0,-3.5)}) {
            var work = new StoredDyadic.Work(); var start = start(work); long before = work.units();
            var tube = CapsulePlacementMath.translationTube(start, delta, work);
            assertEquals(3.5, tube.requestedDisplacementUpper(), 0);
            var end = CapsulePlacementMath.atPrefix(tube, 1, work);
            assertEquals(3.5, CapsulePlacementMath.displacementUpper(start, end, work), 0);
            assertTrue(work.units() > before);
        }
    }

    @Test void nonAxisStoredTravelRetainsAConservativeSquaredDistanceBound() {
        var work = new StoredDyadic.Work(); var start = start(work);
        var tube = CapsulePlacementMath.translationTube(start, new Vec3d(1.25, 2.5, -0.75), work);
        var end = CapsulePlacementMath.atPrefix(tube, 1, work);
        double upper = CapsulePlacementMath.displacementUpper(start, end, work);
        BigDecimal squared = BigDecimal.ZERO;
        double[] a = {start.placement().center().x(), start.placement().center().y(), start.placement().center().z()};
        double[] b = {end.placement().center().x(), end.placement().center().y(), end.placement().center().z()};
        for (int i=0; i<3; i++) {
            var difference = new BigDecimal(b[i]).subtract(new BigDecimal(a[i]));
            squared = squared.add(difference.multiply(difference));
        }
        var bound = new BigDecimal(upper);
        assertTrue(bound.multiply(bound).compareTo(squared) >= 0);
        assertEquals(tube.requestedDisplacementUpper(), upper, 0);
    }

    @Test void axisEndpointCheckCannotEscapeTheExhaustedCallerWork() {
        var work = new StoredDyadic.Work(); var start = start(work);
        var tube = CapsulePlacementMath.translationTube(start, new Vec3d(0,3.5,0), work);
        var end = CapsulePlacementMath.atPrefix(tube,1,work);
        work.charge(StoredDyadic.MAX_WORK_UNITS - work.units());
        var failure = assertThrows(NumericWorkExhaustedException.class,
                () -> CapsulePlacementMath.displacementUpper(start,end,work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS, failure.used());
        assertEquals(StoredDyadic.MAX_WORK_UNITS, work.units());
    }

    private static Vec3 vector(Vec3d value) { return new Vec3(value.x(),value.y(),value.z()); }
}
