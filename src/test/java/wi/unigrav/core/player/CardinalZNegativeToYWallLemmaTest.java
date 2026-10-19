package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardinalZNegativeToYWallLemmaTest {
    private static final PlayerBodyShape.Dimensions SIZE = new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F);
    @Test void actualAUsesH1AndSeparatesTangentWallAtExactUpperSupport() {
        var work = new StoredDyadic.Work(); var p = prepare(true, SIZE, work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.JOINT_RN, p.path().fixedDomainProof());
        assertEquals(0x3fe333331ffffffcL, Double.doubleToRawLongBits(p.recipe().halfLength()));
        var r = mint(p, components(CardinalFrame.Z_NEG), components(CardinalFrame.Y_POS), work);
        assertNotNull(r);
        var geometry = p.recipe().start().placement().storedCapsuleGeometry();
        double product = Math.nextUp(1.0) * p.recipe().halfLength();
        double positive = p.recipe().start().placement().center().z() + product;
        assertEquals(0, StoredDyadic.of(positive, work).add(StoredDyadic.of(geometry.radius(), work), work)
                .compare(StoredDyadic.of(16, work), work));
        for (var g : new StoredCapsuleGeometry[] {geometry, p.recipe().target().storedCapsuleGeometry()})
            for (double z : new double[] {g.negativeEndpoint().z(), g.positiveEndpoint().z()})
                assertTrue(StoredDyadic.of(z, work).add(StoredDyadic.of(g.radius(), work), work)
                        .compare(StoredDyadic.of(16, work), work) <= 0);
        for (double[] interval : new double[][] {{0,0}, {1,1}, {0,1}, {.25,.75}}) {
            assertTrue(r.proves(p.path(), p.recipe(), 2, -1, 16, interval[0], interval[1], work));
            assertFalse(r.proves(p.path(), p.recipe(), 2, -1, Math.nextDown(16.0), interval[0], interval[1], work));
        }
        assertTrue(r.proves(p.path(), p.recipe(), 2, -1, Math.nextUp(16.0), 0, 1, work));
        long before = work.units(); var path = CapsuleArcEnclosure.prepareFixed(p.recipe(), CardinalFrame.Z_NEG.up(), work);
        assertEquals(work.units() - before, path.numericWorkDelta());
        assertTrue(path.provesAxisHalfSpace(2, -1, 16, 0, 1, work));
    }
    @Test void actualDefaultNominalPathGetsOnlyTheSameLiteralChainAndDynamicSupport() {
        var work = new StoredDyadic.Work(); var p = prepare(false, SIZE, work);
        assertEquals(CapsuleArcEnclosure.Path.FixedDomainProof.NOMINAL_IDENTITY, p.path().fixedDomainProof());
        var r = mint(p, components(CardinalFrame.Z_NEG), components(CardinalFrame.Y_POS), work);
        assertNotNull(r);
        for (int axis : new int[] {1,2}) {
            double center = StoredRealizationLimits.component(p.recipe().start().placement().center(), axis);
            double h = p.recipe().halfLength() * (axis == 2 ? Math.nextUp(1.0) : 1);
            var geometry = p.recipe().start().placement().storedCapsuleGeometry();
            for (int side : new int[] {-1,1}) {
                double endpoint = side > 0 ? center-h : center+h;
                for (var g : new StoredCapsuleGeometry[] {geometry, p.recipe().target().storedCapsuleGeometry()})
                    for (var v : new Vec3d[] {g.negativeEndpoint(),g.positiveEndpoint()})
                        endpoint = side > 0 ? Math.min(endpoint,StoredRealizationLimits.component(v,axis))
                                : Math.max(endpoint,StoredRealizationLimits.component(v,axis));
                var exact = StoredDyadic.of(endpoint,work);
                var radius = StoredDyadic.of(geometry.radius(),work);
                exact = side > 0 ? exact.subtract(radius,work) : exact.add(radius,work);
                double plane = side > 0 ? endpoint-geometry.radius() : endpoint+geometry.radius();
                int comparison = exact.compare(StoredDyadic.of(plane,work),work);
                if (side > 0 && comparison < 0) plane=Math.nextDown(plane);
                if (side < 0 && comparison > 0) plane=Math.nextUp(plane);
                assertTrue(r.proves(p.path(),p.recipe(),axis,side,plane,0,1,work));
                assertFalse(r.proves(p.path(),p.recipe(),axis,side,side > 0 ? Math.nextUp(plane) : Math.nextDown(plane),0,1,work));
            }
        }
    }
    @Test void wrongPathWorkLiteralProfileAndIntervalsCannotMintOrTransfer() {
        var work=new StoredDyadic.Work(); var p=prepare(true,SIZE,work);
        var q0=components(CardinalFrame.Z_NEG);var q1=components(CardinalFrame.Y_POS);
        var r=mint(p,q0,q1,work);assertNotNull(r);
        var other=CapsuleArcEnclosure.prepareFixed(p.recipe(),CardinalFrame.Z_NEG.up(),work);
        assertNull(mint(new CardinalRealizedArcRecipe.Prepared(p.recipe(),other), components(CardinalFrame.Z_POS),q1,work));
        assertFalse(r.proves(other,p.recipe(),2,-1,16,0,1,work));
        var independent=prepare(false,SIZE,work);
        assertNull(mint(new CardinalRealizedArcRecipe.Prepared(p.recipe(),independent.path()),q0,q1,work));
        var scalarChanged=CardinalRealizedArcRecipe.prepareCandidate(p.recipe().sourceContainment(),CardinalFrame.Y_POS,
                Math.nextUp(SIZE.centerlineHalfLength()),work);
        if (scalarChanged instanceof CardinalRealizedArcRecipe.Prepared changedScalar)
            assertNull(mint(changedScalar,q0,q1,work));
        else assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_DOMAIN,
                assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,scalarChanged).reason());
        assertNull(mint(p,q0,q1,new StoredDyadic.Work()));
        assertFalse(r.proves(p.path(),p.recipe(),2,-1,16,0,1,new StoredDyadic.Work()));
        q0[1]=Double.MIN_VALUE;assertNull(mint(p,q0,q1,work));
        assertNull(mint(p,components(CardinalFrame.Z_NEG),components(CardinalFrame.X_POS),work));
        assertFalse(r.proves(p.path(),p.recipe(),0,-1,16,0,1,work));
        assertFalse(r.proves(p.path(),p.recipe(),2,-1,16,-.1,1,work));
        assertFalse(r.proves(p.path(),p.recipe(),2,-1,Double.NaN,0,1,work));
        var changed=prepare(false,new PlayerBodyShape.Dimensions(.6F,1.8F,Math.nextUp((double)1.62F)),work);
        assertNull(mint(changed,components(CardinalFrame.Z_NEG),q1,work));
    }
    @Test void proofBudgetFailurePreservesOriginalStackAndAnActualObstacleGetsNoFacePermission() {
        var work=new StoredDyadic.Work();var p=prepare(true,SIZE,work);
        var r=mint(p,components(CardinalFrame.Z_NEG),components(CardinalFrame.Y_POS),work);
        var middle=p.path().enclose(0,1,work).reference();
        var c=p.recipe().start().placement().center();
        var obstacle=new Aabb(c.subtract(new Vec3d(.1,.1,.1)),c.add(new Vec3d(.1,.1,.1)));
        var contact=CapsuleAabbContact3d.query(middle,obstacle,FreeOwnerTransaction.ACCURACY,
                new CapsuleAabbContact3d.QueryLimits(16384,100000,256,128,9344,150000,128));
        assertEquals(CapsuleAabbContact3d.Relation.OVERLAPPING,contact.relation());
        for(int axis:new int[]{1,2}) {
            assertFalse(r.proves(p.path(),p.recipe(),axis,1,StoredRealizationLimits.component(obstacle.max(),axis),0,1,work));
            assertFalse(r.proves(p.path(),p.recipe(),axis,-1,StoredRealizationLimits.component(obstacle.min(),axis),0,1,work));
        }
        work.charge(StoredDyadic.MAX_WORK_UNITS-work.units()-32);
        var failure=assertThrows(NumericWorkExhaustedException.class,()->mint(p,components(CardinalFrame.Z_NEG),components(CardinalFrame.Y_POS),work));
        assertNull(failure.getCause());assertEquals(StoredDyadic.MAX_WORK_UNITS,work.units());
        assertTrue(java.util.Arrays.stream(failure.getStackTrace()).anyMatch(f->f.getClassName().equals(CardinalZNegativeToYWallLemma.class.getName())));
    }
    private static CardinalZNegativeToYWallLemma.Receipt mint(CardinalRealizedArcRecipe.Prepared p,double[]q0,double[]q1,StoredDyadic.Work w) {
        return CardinalZNegativeToYWallLemma.prepare(p.path(),p.recipe(),q0,q1,w);
    }
    private static double[] components(CardinalFrame f){var q=f.attitude();return new double[]{q.x(),q.y(),q.z(),q.w()};}
    private static CardinalRealizedArcRecipe.Prepared prepare(boolean actual,PlayerBodyShape.Dimensions dims,StoredDyadic.Work work) {
        var shape=new PlayerBodyShape.Cardinal(CardinalFrame.Z_NEG,dims);
        var placement=actual ? PlayerBodyPlacement.fromCardinalBounds(
                new Aabb(new Vec3d(37.19999998807907,89.19999992847443,14.200000047683716),
                        new Vec3d(37.80000001192093,89.79999995231628,16)),
                new Vec3d(37.5,89.19999992847443,15.100000023841858),CardinalFrame.Z_NEG.attitude(),shape)
                : new PlayerBodyPlacement(new Vec3d(40.5,88.89999997615814,4),CardinalFrame.Z_NEG.attitude(),shape);
        var snapshot=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(new UUID(0,135),new UUID(0,136),0,2),7960,placement,new Vec3d(0,0,0));
        var source=assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,CardinalContainedCapsuleSource.prepare(snapshot,dims,work));
        var result=CardinalRealizedArcRecipe.prepare(source.containment(),CardinalFrame.Y_POS,work);
        return assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,result,()->result.toString());
    }
}
