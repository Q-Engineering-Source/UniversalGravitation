package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class R26MiddleUlpArcWitnessTest {
    @Test void actualDefaultNominalPathHasRealStoredMicroPenetration() {
        var work = new StoredDyadic.Work();
        var dims = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
        var bounds = new Aabb(new Vec3d(37.19999998807907, 88.59999996423721, 1),
                new Vec3d(37.80000001192093, 89.19999998807907, 2.7999999523162837));
        var placement = PlayerBodyPlacement.fromCardinalBounds(bounds,
                new Vec3d(37.5, 88.59999996423721, 1.8999999761581419), CardinalFrame.Z_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS, dims));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 2),
                12178, placement, new Vec3d(0, 0, 0));
        var contained = assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                CardinalContainedCapsuleSource.prepare(accepted, dims, work));
        var prepared = assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,
                CardinalRealizedArcRecipe.prepare(contained.containment(), CardinalFrame.Y_POS, work));
        assertEquals(dims.centerlineHalfLength(), prepared.recipe().halfLength());
        double t = 0x1.004p-54;
        var capsule = prepared.path().enclose(t, t, work).reference();
        var contact = CapsuleAabbContact3d.query(capsule,
                new Aabb(new Vec3d(37, 88, 0), new Vec3d(38, 89, 1)), FreeOwnerTransaction.ACCURACY,
                new CapsuleAabbContact3d.QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128));
        System.out.println("R26_ACTUAL_POINT t=" + Double.toHexString(t) + " capsule=" + capsule + " contact=" + contact);
        assertEquals(CapsuleAabbContact3d.Relation.OVERLAPPING, contact.relation(),
                "this default path needs a safe recovery step; its real collision cannot be certified clear");
    }
}