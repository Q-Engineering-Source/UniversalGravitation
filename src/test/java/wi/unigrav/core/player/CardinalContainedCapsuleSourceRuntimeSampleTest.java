package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalContainedCapsuleSourceRuntimeSampleTest {
    @Test void observesExactR19WallSampleWithoutRebuildingAcceptedBounds() {
        var dimensions = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
        var bounds = new Aabb(new Vec3d(40.19999998807907, 88.59999996423721, 1),
                new Vec3d(40.80000001192093, 89.19999998807907, 2.7999999523162833));
        var transport = new Vec3d(40.5, 88.59999996423721, 1.8999999761581416);
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, transport,
                CardinalFrame.Z_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, dimensions));
        var source = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 195),
                new UUID(0, 196), 0, 2), 7960, placement, new Vec3d(0, 0, 0));
        assertEquals(bounds, placement.bounds());
        assertEquals(new Vec3d(40.5, 88.89999997615814, 1.8999999761581416), placement.center());
        var work = new StoredDyadic.Work();
        var limits = StoredRealizationLimits.derive(placement.center(), placement.attitude(), dimensions, work);
        System.out.println("R19 nominalHalfLine=" + limits.nominalHalfLine()
                + "; axisDomainBound=" + limits.halfLineBound(2).roundUp(work));
        var result = CardinalContainedCapsuleSource.prepare(source, dimensions, work);
        System.out.println("R19 factoryResult=" + result + "; numericWork=" + work.units());
        if (result instanceof CardinalContainedCapsuleSource.Prepared prepared) {
            var stored = prepared.placement().storedCapsuleGeometry();
            System.out.println("R19 relation=" + stored.relation().kind() + "; storedHalfLine="
                    + stored.storedHalfLine() + "; outward=" + prepared.containment().outwardCapsuleBounds());
            assertEquals(dimensions, prepared.placement().shape().dimensions());
            assertEquals(placement.center(), prepared.placement().center());
            assertTrue(prepared.containment().matches(source, prepared.placement(), work));
            assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(source, prepared.placement(), work));
        } else {
            var unavailable = assertInstanceOf(CardinalContainedCapsuleSource.Unavailable.class, result);
            assertNotNull(unavailable.reason());
            assertFalse(unavailable.diagnostics().isBlank());
        }
        assertEquals(bounds, source.placement().bounds());
        assertEquals(transport, source.placement().transport());
    }
}
