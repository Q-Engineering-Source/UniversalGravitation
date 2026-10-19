package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundaryKind;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.BoundarySearchHint;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.StoredInstallCandidate;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real CHB plus contained-root foot/current/recapture APIs on the explicit TEST World bridge. */
class FreeNearFootExactSubpathTest {
    @TempDir Path directory;
    private static final Vec3d C=new Vec3d(8.5,65.50000001192093,8.5);
    private static final double R=.30000001192092896,T=.8000000000000114;
    private static final Vec3d D=new Vec3d(0,-.25,0),UP=new Vec3d(0,1,0);
    private static final Aabb BOX=new Aabb(new Vec3d(8,64,8),new Vec3d(9,65,9));
    private static Capsule sphere(Vec3d c,double r){return new Capsule(new Segment3d(c,c),r);}
    private InstalledFreeEndpointTest.Scene owner(String name) throws Exception {
        var helper=new InstalledFreeEndpointTest();helper.directory=directory;return helper.scene(false,name);
    }
    @Test void originalFullCastNgIsPreservedAndGenuineExactSubcastCertifiesOne() throws Exception {
        try(var s=owner("original-fullcast-red")) {
            var start=sphere(C,R);var at=C.add(D.multiply(T));var end=sphere(at,R);
            var actual=s.tx().queries().query(end,BOX,FreeOwnerTransaction.ACCURACY);
            assertEquals(Relation.TOUCHING,actual.relation());assertTrue(actual.witness().isPresent());
            var old=s.tx().queries().refineTranslationBoundary(start,D,BOX,new BoundarySearchHint(0,1),
                new StoredInstallCandidate(T,C,at,new Vec3d(0,0,0),end),FreeOwnerTransaction.ACCURACY);
            assertEquals(BoundaryKind.UNRESOLVED,old.kind(),old::toString);
            assertEquals("ROUNDED_INSTALL_UNSAFE",old.reason().orElseThrow().name());
            assertTrue(old.prefix().orElseThrow().endFraction()<T);assertTrue(old.installation().isEmpty());
            long calls=s.tx().queries().calls();
            var proof=FreeNearFootDrive.proveCandidate(s.tx(),start,D,T,BOX,UP);
            assertNotNull(proof);assertEquals(calls+2,s.tx().queries().calls(),"one real static and one real subcast");
            assertEquals(BoundaryKind.NONPENETRATING_FULL,proof.boundary().kind());
            assertEquals(1,proof.boundary().prefix().orElseThrow().endFraction());
            var installed=proof.boundary().installation().orElseThrow();
            assertEquals(1,installed.fraction());assertEquals(at,installed.storedCenter());
            assertEquals(end,installed.storedCapsule());assertEquals(new Vec3d(0,0,0),installed.halfLine());
            assertEquals(Relation.TOUCHING,installed.storedContact().relation());assertTrue(installed.storedContact().witness().isPresent());
        }
    }
    @Test void allSignedAxesAtHalfAndFullReachUseExactSubcasts() throws Exception {
        for(var frame:CardinalFrame.values())for(double gap:new double[]{.125,.25})try(var s=owner("axis-"+frame+"-"+gap)) {
            var up=frame.up();var box=new Aabb(new Vec3d(-1,-1,-1),new Vec3d(1,1,1));
            var center=up.multiply(1+.25+gap);var delta=up.multiply(-.25);double fraction=gap/.25;
            var proof=FreeNearFootDrive.proveCandidate(s.tx(),sphere(center,.25),delta,fraction,box,up);
            assertNotNull(proof,frame+" gap="+gap);assertEquals(1,proof.boundary().prefix().orElseThrow().endFraction());
            assertEquals(Relation.TOUCHING,proof.boundary().installation().orElseThrow().storedContact().relation());
        }
    }
    @Test void candidateBeyondOriginalReachCannotQueryOrAuthorize() throws Exception {
        try(var s=owner("beyond-reach")) {
            long before=s.tx().queries().calls();
            assertThrows(IllegalArgumentException.class,()->FreeNearFootDrive.proveCandidate(s.tx(),sphere(C,R),D,Math.nextUp(1D),BOX,UP));
            assertEquals(before,s.tx().queries().calls());
        }
    }
    @Test void actualTouchWithoutExactStoredSubpathIsExplicitlyInsufficient() throws Exception {
        try(var s=owner("non-exact-subpath")) {
            var delta=new Vec3d(0,-.3,0);double t=(65+R-C.y())/delta.y();var sub=delta.multiply(t);var at=C.add(sub);
            var actual=s.tx().queries().query(sphere(at,R),BOX,FreeOwnerTransaction.ACCURACY);
            assertEquals(Relation.TOUCHING,actual.relation(),actual::toString);
            assertFalse(FreeNearFootDrive.exactSubpath(C,delta,t,sub,at,s.tx().numeric()));
            var failure=assertThrows(FreeNearFootDrive.ProofInsufficient.class,
                ()->FreeNearFootDrive.proveCandidate(s.tx(),sphere(C,R),delta,t,BOX,UP));
            assertTrue(failure.getMessage().contains("not an exact subpath"));
        }
    }
    @Test void numericBudgetOriginalFailureIsNotCaughtAsNoGround() throws Exception {
        try(var s=owner("numeric-budget")) {
            s.tx().numeric().charge(StoredDyadic.MAX_WORK_UNITS-s.tx().numeric().units());
            var failure=assertThrows(NumericWorkExhaustedException.class,
                ()->FreeNearFootDrive.proveCandidate(s.tx(),sphere(C,R),D,T,BOX,UP));
            assertEquals(StoredDyadic.Work.class.getName(),failure.getStackTrace()[0].getClassName());
        }
    }
    @Test void actualQueryBudgetTerminalDenialIsNotCaughtOrRefunded() throws Exception {
        try(var s=owner("query-budget")) {
            var start=sphere(C,R);
            while(!s.tx().queries().terminal())s.tx().queries().query(start,BOX,FreeOwnerTransaction.ACCURACY);
            long calls=s.tx().queries().calls();var spent=s.tx().queries().work();
            assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,
                ()->FreeNearFootDrive.proveCandidate(s.tx(),start,D,T,BOX,UP));
            assertEquals(calls+1,s.tx().queries().calls());assertEquals(spent,s.tx().queries().work());
        }
    }
    @Test void actualOwnerLossRejectsBeforeAnyCandidateQuery() throws Exception {
        try(var s=owner("stale-owner")) {
            s.current().set(false);long calls=s.tx().queries().calls();
            assertThrows(IllegalStateException.class,()->FreeNearFootDrive.proveCandidate(s.tx(),sphere(C,R),D,T,BOX,UP));
            assertEquals(calls,s.tx().queries().calls());
        }
    }

    private record Root(BootWorldFixture fixture,FreeOwnerTransaction tx,
            CertifiedReferenceEndpoint.CardinalRootObservation cap) implements AutoCloseable {
        public void close(){tx.close();fixture.close();}
    }
    private Root contained(CardinalFrame frame,double gap) { return contained(frame,gap,false); }
    private Root contained(CardinalFrame frame,double gap,boolean overlappingBody) {
        return contained(frame,gap,overlappingBody,false);
    }
    private Root contained(CardinalFrame frame,double gap,boolean overlappingBody,boolean optionalSideBox) {
        UUID id=UUID.randomUUID();var fixture=BootWorldFixture.open(directory.resolve("root-"+frame+"-"+gap),id,false);
        try {
            var actor=fixture.serverPlayer(id);var dims=new PlayerBodyShape.Dimensions(.5,1,.25);
            var placement=new PlayerBodyPlacement(new Vec3d(8.5,68.5,8.5).add(frame.up().multiply(1+gap)),
                frame.attitude(),new PlayerBodyShape.Cardinal(frame,dims));
            actor.width=.5F;actor.height=1;actor.eyeHeight=.25F;var p=placement.transport();var b=placement.bounds();
            actor.setPosition(p.x(),p.y(),p.z());actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
            fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,68,8),Blocks.STONE.getDefaultState());
            if(overlappingBody)fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,69,8),Blocks.STONE.getDefaultState());
            if(optionalSideBox)fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(9,68,8),Blocks.STONE.getDefaultState());
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(id,UUID.randomUUID(),actor.dimension,1),7,placement,new Vec3d(0,0,0));
            var travel=new PlayerPhysicsStep.TravelState();var tx=new FreeOwnerTransaction(actor,accepted,travel,
                new BoostMovementBudget(20,0,new Vec3(p.x(),p.y(),p.z())),()->true);
            var prepared=assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,CardinalContainedCapsuleSource.prepare(accepted,dims,tx.numeric()));
            var window=new Aabb(new Vec3d(6,66,6),new Vec3d(11,72,11));
            var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(tx,window,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,new CollisionQueryFootprint.Limits(16384,16,4096,4096),tx.resources().environmentObservationWork()));
            var source=CapsulePathSource.containedCardinal(prepared.containment(),prepared.pose(),capture,window,
                FreeOwnerTransaction.ACCURACY,tx.queries(),tx.numeric());
            return new Root(fixture,tx,CertifiedReferenceEndpoint.containedRootObservation(tx,prepared,source));
        }catch(RuntimeException|Error failure){fixture.close();throw failure;}
    }
    @Test void verifiedCompleteFootTouchStopsOnlyLaterOptionalEventExploration() {
        try(var s=contained(CardinalFrame.Y_POS,0,false,true)) {
            var foot=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                CapsuleFootContacts.collect(s.cap,s.cap.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertEquals(s.cap.capture().boxes().size(),foot.queries().size(),
                "all mandatory full-body and per-box foot/refinement observations completed first");
            assertTrue(foot.queries().size()>1);
            assertEquals(Relation.TOUCHING,foot.queries().getFirst().contact().relation());
            assertEquals(CapsuleSupportSlope.SlopeEvidence.SUFFICIENT,
                CapsuleSupportSlope.evaluate(foot.queries().getFirst().contact().witness().orElseThrow(),
                    CardinalFrame.Y_POS.up(),s.tx.numeric()));
            long calls=s.tx.queries().calls();
            assertTrue(FreeNearFootDrive.permitted(s.tx,s.cap,foot));
            assertEquals(calls,s.tx.queries().calls(),
                "a complete touching event must not run optional candidate proofs for later boxes");
            assertTrue(s.cap.current());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }
    @Test void realSixAxisContainedFootUsesFixedFullWindowAndRecaptureAtHalfAndFullReach() {
        for(var frame:CardinalFrame.values())for(double gap:new double[]{.125,.25})try(var s=contained(frame,gap)) {
            var foot=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                CapsuleFootContacts.collect(s.cap,s.cap.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertFalse(foot.queries().isEmpty());assertSame(s.cap.queryWindow(),foot.window());
            long before=s.tx.queries().calls();
            assertTrue(FreeNearFootDrive.permitted(s.tx,s.cap,foot),frame+" gap="+gap);
            assertTrue(s.tx.queries().calls()>before);assertTrue(s.cap.current());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }
    @Test void realContainedFootBeyondReachReturnsNoPermissionWithoutChangingOwner() {
        try(var s=contained(CardinalFrame.Y_POS,.375)) {
            var foot=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                CapsuleFootContacts.collect(s.cap,s.cap.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertFalse(FreeNearFootDrive.permitted(s.tx,s.cap,foot));assertTrue(s.cap.current());
        }
    }
    @Test void unrelatedFullBodyOverlapStillRejectsBeforeAnyNearFootPermission() {
        try(var s=contained(CardinalFrame.Y_POS,.125,true)) {
            var failure=assertInstanceOf(CapsuleFootContacts.Failed.class,
                CapsuleFootContacts.collect(s.cap,s.cap.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertEquals(CapsuleFootContacts.Failure.FULL_CAPSULE,failure.failure());
            var full=assertInstanceOf(CapsuleContactCollector.Failed.class,failure.fullObservation());
            assertEquals(CapsuleContactCollector.Failure.OVERLAPPING,full.failure());
            assertTrue(s.cap.current());assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }

}
