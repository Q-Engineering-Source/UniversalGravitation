package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleFootContactsTest {
    @TempDir Path temp;
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004231");
    static final QueryLimits LIMITS = new QueryLimits(8192,20000,128,32,2336,40000,64);
    static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6,1e-6,1e-5);
    static final CollisionQueryFootprint.Limits SCAN = new CollisionQueryFootprint.Limits(10000,16,100,100);

    @Test void sixDirectionsUseActualNegativeEndpointRegardlessOfCanonicalOrdering() throws Exception {
        boolean start=false,end=false;
        for (var frame : CardinalFrame.values()) {
            try(var f=BootWorldFixture.open(temp.resolve(frame.name()),PLAYER,false)) {
                var actor=f.serverPlayer(PLAYER);
                var a=accepted(actor,new Vec3d(8,66,8),frame.attitude(),2);
                var u=frame.toWorld(new Vec3d(0,1,0));
                block(f,(int)(8+(u.x()>0?-2:u.x()<0?1:0)),
                        (int)(66+(u.y()>0?-2:u.y()<0?1:0)),
                        (int)(8+(u.z()>0?-2:u.z()<0?1:0)));
                var capture=capture(f,actor,a);
                var pose=CapsulePlacementMath.pose(a.placement());
                var budget=new CapsuleContactQueryBudget(LIMITS,100);
                var result=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                        CapsuleFootContacts.collect(a,pose,capture,.25,ACCURACY,budget));
                assertTrue(result.queries().stream().anyMatch(q->q.classification()==CapsuleFootContacts.Classification.CONTACT),result::toString);
                assertEquals(a.placement().storedCapsuleGeometry().negativeEndpoint(),result.footSphere().centerline().start());
                assertTrue(result.matches(a,pose,capture,budget));
                start|=a.placement().storedCapsuleGeometry().negativeEndpointCanonicalStart();
                end|=!a.placement().storedCapsuleGeometry().negativeEndpointCanonicalStart();
                actor.setEntityBoundingBox(actor.getEntityBoundingBox().offset(.125,0,0));
                assertFalse(result.matches(a,pose,capture,budget));
            }
        }
        assertTrue(start); assertTrue(end);
    }

    @Test void directionalGapAndExactEndpointTouchAreNearAndFarIsOutside() throws Exception {
        for(double gap:new double[]{.125,.25,.375}) {
            try(var f=BootWorldFixture.open(temp.resolve("gap"+gap),PLAYER,false)) {
                var actor=f.serverPlayer(PLAYER);
                var a=accepted(actor,new Vec3d(8.5,66+gap,8.5),CardinalFrame.Y_POS.attitude(),2);
                block(f,8,64,8);
                var r=observe(f,actor,a,100);
                if (gap > .25) {
                    assertTrue(r.queries().isEmpty(), "beyond reach collider must be excluded by actual window");
                    continue;
                }
                var expected=CapsuleFootContacts.Classification.NEAR;
                assertEquals(expected,r.queries().getFirst().classification(),r::toString);
                assertFalse(r.queries().stream().anyMatch(q->q.classification()==CapsuleFootContacts.Classification.CONTACT));
                assertTrue(r.queries().getFirst().boundaries().size()>=1);
            }
        }
    }

    @Test void bodyHeadAndSideTouchNeverBecomeFootContact() throws Exception {
        for(int[] b:new int[][]{{8,67,8},{9,66,8},{9,65,8}}) {
            try(var f=BootWorldFixture.open(temp.resolve("other"+b[0]+b[1]),PLAYER,false)) {
                var actor=f.serverPlayer(PLAYER);
                var a=accepted(actor,new Vec3d(8.5,66,8.5),CardinalFrame.Y_POS.attitude(),2);
                block(f,b[0],b[1],b[2]);
                var r=observe(f,actor,a,100);
                assertFalse(r.queries().stream().anyMatch(q->q.classification()==CapsuleFootContacts.Classification.CONTACT),r::toString);
            }
        }
    }

    @Test void tiltedAndZeroLengthRetainAcceptedUpAndActualSphere() throws Exception {
        for(double height:new double[]{1,2}) {
            try(var f=BootWorldFixture.open(temp.resolve("tilt"+height),PLAYER,false)) {
                var actor=f.serverPlayer(PLAYER);
                var a=accepted(actor,new Vec3d(8.5,height==1?65.625:66.5,8.5),new Rotation3d(0,0,1,2),height);
                block(f,8,64,8);
                var capture=capture(f,actor,a);
                if(height==1) assertFalse(capture.boxes().isEmpty(), "tilted H=W must enter real native candidates");
                var r=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,CapsuleFootContacts.collect(a,
                        CapsulePlacementMath.pose(a.placement()),capture,.25,ACCURACY,new CapsuleContactQueryBudget(LIMITS,100)));
                assertEquals(a.placement().storedCapsuleGeometry().negativeEndpoint(),r.footSphere().centerline().start());
                assertEquals(a.placement().capsule().radius(),r.footSphere().radius());
                assertNotEquals(new Vec3d(0,1,0),a.placement().bodyUp());
                assertEquals(r.fullObservation().queries().size(),r.queries().size());
                if(height==1) {
                    assertFalse(r.queries().isEmpty());
                    var query=r.queries().getFirst();
                    assertEquals(dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation.SEPARATED,query.contact().relation());
                    assertTrue(query.contact().witness().isPresent());
                    assertFalse(query.boundaries().isEmpty());
                    assertEquals(CapsuleFootContacts.Classification.NEAR,query.classification());
                    assertEquals(dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind.CERTIFIED_BOUNDARY,
                            query.boundaries().getLast().kind());
                    System.out.println("tilted H=W actual-foot evidence: " + query);
                }
            }
        }
    }

    @Test void tiltedSideWithinEuclideanReachButDownwardPathMissesIsOutside() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("slanted-side"),PLAYER,false)) {
            var actor=f.serverPlayer(PLAYER);
            var a=accepted(actor,new Vec3d(8.525,66.45,8.5),new Rotation3d(0,0,-1,3),1.5);
            block(f,9,65,8);
            var r=observe(f,actor,a,100);
            var q=r.queries().getFirst();
            assertTrue(q.contact().signedClearanceMeters().orElseThrow().upper()<.25);
            assertEquals(CapsuleFootContacts.Classification.OUTSIDE,q.classification(),r::toString);
            assertEquals(dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind.SEPARATED_FULL,
                    q.boundaries().getLast().kind());
        }
    }

    @Test void overlapAndUnresolvedStayFullFailuresBeforeAnyFootQuery() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("failure"),PLAYER,false)) {
            var actor=f.serverPlayer(PLAYER);
            var a=accepted(actor,new Vec3d(8.5,66,8.5),CardinalFrame.Y_POS.attitude(),2);
            block(f,8,64,8); block(f,8,66,8);
            var c=capture(f,actor,a);
            var r=assertInstanceOf(CapsuleFootContacts.Failed.class,CapsuleFootContacts.collect(a,
                    CapsulePlacementMath.pose(a.placement()),c,.25,ACCURACY,new CapsuleContactQueryBudget(LIMITS,100)));
            assertEquals(CapsuleContactCollector.Failure.OVERLAPPING,assertInstanceOf(CapsuleContactCollector.Failed.class,r.fullObservation()).failure());
            assertTrue(r.queries().isEmpty());
            var limited=new QueryLimits(8192,1,128,32,2336,40000,64);
            r=assertInstanceOf(CapsuleFootContacts.Failed.class,CapsuleFootContacts.collect(a,
                    CapsulePlacementMath.pose(a.placement()),c,.25,ACCURACY,new CapsuleContactQueryBudget(limited,100)));
            assertEquals(CapsuleContactCollector.Failure.UNRESOLVED,assertInstanceOf(CapsuleContactCollector.Failed.class,r.fullObservation()).failure());
            assertTrue(r.queries().isEmpty());
        }
    }

    @Test void sharedFootBudgetRetainsPartialOrdinalAndLiveTickRejects() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("budget"),PLAYER,false)) {
            var actor=f.serverPlayer(PLAYER);
            var a=accepted(actor,new Vec3d(8.5,66,8.5),CardinalFrame.Y_POS.attitude(),2);
            block(f,8,64,8);
            var c=capture(f,actor,a); var pose=CapsulePlacementMath.pose(a.placement());
            var budget=new CapsuleContactQueryBudget(LIMITS,2);
            var r=assertInstanceOf(CapsuleFootContacts.Failed.class,CapsuleFootContacts.collect(a,pose,c,.25,ACCURACY,budget));
            assertEquals(CapsuleFootContacts.Failure.BUDGET_EXHAUSTED,r.failure());
            assertEquals(0,r.firstUnqueried()); assertEquals(1,r.candidateCount());
            assertEquals(1,r.queries().size()); assertEquals(3,r.calls()); assertEquals(budget.work(),r.work());
            assertTrue(r.budgetTerminal()); assertTrue(r.budgetFailure().isPresent());
            f.world().setTotalWorldTime(c.captureTick()+1);
            r=assertInstanceOf(CapsuleFootContacts.Failed.class,CapsuleFootContacts.collect(a,pose,c,.25,ACCURACY,new CapsuleContactQueryBudget(LIMITS,100)));
            assertEquals(CapsuleFootContacts.Failure.FULL_CAPSULE,r.failure());
            assertEquals(CapsuleContactCollector.Failure.CONTEXT_CHANGED,assertInstanceOf(CapsuleContactCollector.Failed.class,r.fullObservation()).failure());
        }
    }

    @Test void zeroReachUsesStaticDirectionWithoutExtendingPathAndInvalidReachRejects() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("zero"),PLAYER,false)) {
            var actor=f.serverPlayer(PLAYER);
            var a=accepted(actor,new Vec3d(8.5,66,8.5),CardinalFrame.Y_POS.attitude(),2);
            block(f,8,64,8);
            var c=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(f.world(),actor,
                    CapsuleFootContacts.window(a,0),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
            var budget=new CapsuleContactQueryBudget(LIMITS,100);
            var r=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,CapsuleFootContacts.collect(a,
                    CapsulePlacementMath.pose(a.placement()),c,0,ACCURACY,budget));
            assertEquals(CapsuleFootContacts.Classification.CONTACT,r.queries().getFirst().classification());
            assertTrue(r.queries().getFirst().boundaries().isEmpty()); assertEquals(2,budget.calls());
            assertThrows(IllegalArgumentException.class,()->CapsuleFootContacts.window(a,Double.NaN));
            assertThrows(IllegalArgumentException.class,()->CapsuleFootContacts.window(a,-.1));
            assertThrows(IllegalArgumentException.class,()->CapsuleFootContacts.window(a,Math.nextUp(.25)));
        }
    }

    private static CapsuleFootContacts.VerifiedFootObservations observe(BootWorldFixture f, EntityPlayerMP actor, PlayerPhysicalSnapshot a,int calls) {
        return assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,CapsuleFootContacts.collect(a,
                CapsulePlacementMath.pose(a.placement()),capture(f,actor,a),.25,ACCURACY,new CapsuleContactQueryBudget(LIMITS,calls)));
    }
    private static WorldCollisionCandidates.Snapshot capture(BootWorldFixture f,EntityPlayerMP actor,PlayerPhysicalSnapshot a) {
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(f.world(),actor,
                CapsuleFootContacts.window(a,.25),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
    }
    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP actor,Vec3d center,Rotation3d q,double height) {
        var p=new PlayerBodyPlacement(center,q,new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(1,height,.75)));
        actor.width=1; actor.height=(float)height;
        var t=p.transport(); actor.setPosition(t.x(),t.y(),t.z()); var b=p.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),actor.dimension,1),7,p,new Vec3d(0,0,0));
    }
    private static void block(BootWorldFixture f,int x,int y,int z) {
        var chunk=f.world().getChunkProvider().getLoadedChunk(x>>4,z>>4); assertNotNull(chunk);
        chunk.setBlockState(new BlockPos(x,y,z),Blocks.STONE.getDefaultState());
    }
}
