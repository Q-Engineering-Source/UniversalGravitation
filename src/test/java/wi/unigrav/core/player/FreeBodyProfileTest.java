package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FreeBodyProfileTest {
    private static final PlayerBodyShape.Dimensions RAW =
            new PlayerBodyShape.Dimensions(.6000000238418579, .5999999642372131, .4);
    private static final PlayerBodyShape.CollisionProfile BODY =
            new PlayerBodyShape.CollisionProfile((double).6F, (double)1.8F);

    private static PlayerBodyPlacement placement(Vec3d center, Rotation3d q, Vec3d eye) {
        return new PlayerBodyPlacement(center, q, new PlayerBodyShape.FreeCapsule(RAW, BODY,
                Optional.of(new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1, center, eye, q))));
    }

    @Test void nativeShortDimensionsDoNotShortenTheIndependentPhysicalCapsule() {
        var center = new Vec3d(12, 65.3, 8);
        var eye = new Vec3d(12, 65.4, 8);
        var body = placement(center, Rotation3d.identity(), eye);
        assertSame(RAW, body.shape().dimensions());
        assertEquals(BODY, ((PlayerBodyShape.FreeCapsule)body.shape()).collisionProfile());
        assertEquals(BODY.diameter() / 2, body.capsule().radius());
        assertEquals((BODY.length() - BODY.diameter()) / 2,
                body.storedCapsuleGeometry().storedHalfLine().y());
        assertEquals(center, body.center());
        assertEquals(eye, body.eye());
    }

    @Test void nonCardinalAnchorTranslationAndReturnPreserveTheirExactWorldEye() {
        var center = new Vec3d(0x1.0p20, -0x1.0p19, 9);
        var eye = new Vec3d(Math.nextUp(center.x()), Math.nextDown(center.y()), 9.125);
        var q = new Rotation3d(.1, .2, .3, .9);
        var source = placement(center, q, eye);
        var anchor = ((PlayerBodyShape.FreeCapsule)source.shape()).eyeAnchor().orElseThrow();
        var arithmetic = BootQueryArithmetic.fromSameWork(new StoredDyadic.Work());
        assertSame(eye, anchor.at(center, q, arithmetic));
        var moved = center.add(new Vec3d(.25, -.5, 1));
        assertEquals(eye.add(new Vec3d(.25, -.5, 1)), anchor.at(moved, q, arithmetic));
        var turned = new PlayerBodyPlacement(center, Rotation3d.identity(), source.shape());
        var returned = new PlayerBodyPlacement(center, q, turned.shape());
        assertEquals(eye, returned.eye());
        assertSame(anchor, ((PlayerBodyShape.FreeCapsule)returned.shape()).eyeAnchor().orElseThrow());
    }

    @Test void codecRetainsRawDimensionsFullProfileAndTheOriginalEyeAnchor() {
        var source = placement(new Vec3d(12, 65.3, 8), new Rotation3d(.1,.2,.3,.9), new Vec3d(12,65.4,8));
        var moved = source.withTranslatedCenter(source.center().add(new Vec3d(.125,0,0)));
        var wire = PlayerPlacementCodec.exportFreeV1(moved);
        var restored = PlayerPlacementCodec.restoreFreeV1(wire,
                FreeCapsulePlacementV1.RawQuaternion.from(moved.attitude()), RAW,
                FreeCapsulePlacementV1.RawAabb.from(moved.bounds()),
                FreeCapsulePlacementV1.RawVec3.from(moved.transport()),
                new PhysicsDecodeBudget(PlayerPhysicsProtocol.MAX_BYTES, new StoredDyadic.Work()));
        assertEquals(moved.shape(), restored.shape());
        assertEquals(moved.eye(), restored.eye());
        assertEquals(moved.bounds(), restored.bounds());
        assertEquals(moved.transport(), restored.transport());
        assertEquals(wire, PlayerPlacementCodec.exportFreeV1(restored));
    }

    @Test void ordinaryFreeKeepsItsOriginalFootResizeAndUnprofiledWireRecipe() {
        var ordinary = new PlayerBodyPlacement(new Vec3d(8,66,8),Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,2,1.75)));
        var target = ModeTransitionPrepared.trustedFreeResizeTarget(ordinary,
                new PlayerBodyShape.Dimensions(.75,3,2),new StoredDyadic.Work());
        assertEquals(new Vec3d(8,66.5,8),target.center());
        assertEquals(new Vec3d(8,65,8),PlayerBodyPlacement.physicalFoot(target,
                BootQueryArithmetic.fromSameWork(new StoredDyadic.Work())));
        assertEquals(new Vec3d(8,67,8),target.eye());
        assertTrue(((PlayerBodyShape.FreeCapsule)target.shape()).eyeAnchor().isEmpty());
        assertSame(target.shape().dimensions(),target.shape().geometryDimensions());
        assertTrue(PlayerPlacementCodec.exportFreeV1(target).bodyProfile().isEmpty());
    }

    @Test void exhaustedEyeWorkCannotMutateTheAcceptedBody() {
        var source = placement(new Vec3d(12,65.3,8), new Rotation3d(.1,.2,.3,.9), new Vec3d(12,65.4,8));
        var work = new StoredDyadic.Work();
        work.charge(StoredDyadic.MAX_WORK_UNITS);
        var anchor = ((PlayerBodyShape.FreeCapsule)source.shape()).eyeAnchor().orElseThrow();
        assertThrows(NumericWorkExhaustedException.class, () -> anchor.at(source.center(), Rotation3d.identity(),
                BootQueryArithmetic.fromSameWork(work)));
        assertEquals(new Vec3d(12,65.3,8), source.center());
        assertEquals(new Vec3d(12,65.4,8), source.eye());
        assertEquals(RAW, source.shape().dimensions());
    }
}
