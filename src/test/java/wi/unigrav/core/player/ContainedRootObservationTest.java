package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
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

/** Query capability behavior on the actual TEST World bridge; no installed/standing authority is exercised. */
class ContainedRootObservationTest {
    @TempDir Path directory;
    private static final CollisionQueryFootprint.Limits SCAN=new CollisionQueryFootprint.Limits(16384,16,4096,4096);
    private static final Aabb GLOBAL=new Aabb(new Vec3d(6,65,6),new Vec3d(11,73,11));
    private record Scene(BootWorldFixture fixture,FreeOwnerTransaction tx,AtomicBoolean current,
            CardinalContainedCapsuleSource.Prepared contained,CapsulePathSource source,
            CertifiedReferenceEndpoint.CardinalRootObservation cap) implements AutoCloseable {
        public void close(){tx.close();fixture.close();}
    }
    private Scene scene(CardinalFrame frame,Aabb window) {
        UUID id=UUID.randomUUID();var fixture=BootWorldFixture.open(directory.resolve(id.toString()),id,false);
        try {
            var actor=fixture.serverPlayer(id);var dims=new PlayerBodyShape.Dimensions(.5,1,.25);
            var placement=new PlayerBodyPlacement(new Vec3d(8.5,68.5,8.5).add(frame.up()),frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame,dims));
            actor.width=.5F;actor.height=1;actor.eyeHeight=.25F;
            var b=placement.bounds();var p=placement.transport();actor.setPosition(p.x(),p.y(),p.z());
            actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
            fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,68,8),Blocks.STONE.getDefaultState());
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(id,UUID.randomUUID(),actor.dimension,1),
                    7,placement,new Vec3d(0,0,0));
            var travel=new PlayerPhysicsStep.TravelState();var current=new AtomicBoolean(true);
            var ledger=new BoostMovementBudget(10,0,new Vec3(p.x(),p.y(),p.z()));
            var tx=new FreeOwnerTransaction(actor,accepted,travel,ledger,current::get);
            var contained=assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                    CardinalContainedCapsuleSource.prepare(accepted,dims,tx.numeric()));
            var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(tx,window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
            var source=CapsulePathSource.containedCardinal(contained.containment(),contained.pose(),capture,window,
                    FreeOwnerTransaction.ACCURACY,tx.queries(),tx.numeric());
            var cap=CertifiedReferenceEndpoint.containedRootObservation(tx,contained,source);
            return new Scene(fixture,tx,current,contained,source,cap);
        }catch(RuntimeException|Error failure){fixture.close();throw failure;}
    }
    @Test void sixActualCardinalRootsQueryOneGlobalCaptureWithoutForgingAcceptedFree() {
        for(var frame:CardinalFrame.values())try(var s=scene(frame,GLOBAL)) {
            var accepted=s.tx.accepted();var geometry=WorldCollisionCandidates.ActorGeometry.read(s.tx.actor());
            var required=CapsuleFootContacts.requiredWindow(s.contained.pose(),.25);
            var old=assertInstanceOf(CapsuleContactCollector.Failed.class,CapsuleContactCollector.collectWithin(
                    accepted,s.contained.pose(),s.source.capture(),GLOBAL,FreeOwnerTransaction.ACCURACY,s.tx.queries()));
            assertEquals(CapsuleContactCollector.Failure.INVALID_POSE,old.failure());
            var ordinaryEndpoint=CertifiedReferenceEndpoint.from(s.tx,s.source);
            assertFalse(s.cap.matches(ordinaryEndpoint,s.source.capture(),GLOBAL),"same geometry does not replace the exact capability endpoint");
            assertEquals(CapsuleContactCollector.Failure.INVALID_POSE,assertInstanceOf(CapsuleContactCollector.Failed.class,
                    CapsuleContactCollector.collectWithin(ordinaryEndpoint,s.source.capture(),GLOBAL,FreeOwnerTransaction.ACCURACY)).failure());
            var foot=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                    CapsuleFootContacts.collect(s.cap,s.source.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertSame(accepted,foot.fullObservation().accepted());assertSame(s.contained.pose(),foot.fullObservation().pose());
            assertEquals(required,foot.requiredFootWindow());assertEquals(GLOBAL,foot.window());assertNotEquals(required,foot.window());
            assertFalse(foot.queries().isEmpty(),"actual floor candidate must be observed");
            assertEquals(s.source.capture().boxes().size(),foot.queries().size());
            assertTrue(foot.matches(accepted,s.contained.pose(),s.source.capture(),s.tx.queries()));
            assertInstanceOf(PlayerBodyShape.Cardinal.class,accepted.placement().shape());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
            assertEquals(geometry,WorldCollisionCandidates.ActorGeometry.read(s.tx.actor()));
            for(int n=0;n<foot.queries().size();n++)assertEquals(n,foot.queries().get(n).ordinal());
        }
    }
    @Test void sameGeometryNewPoseOrSplicedContainmentCannotMintRootObservation() {
        try(var s=scene(CardinalFrame.Y_POS,GLOBAL)) {
            var copy=CapsulePlacementMath.pose(s.contained.placement(),s.tx.numeric());
            var spliced=new CardinalContainedCapsuleSource.Prepared(s.contained.placement(),copy,s.contained.containment());
            assertThrows(IllegalStateException.class,()->CertifiedReferenceEndpoint.containedRootObservation(s.tx,spliced,s.source));
            var anotherWork=new StoredDyadic.Work();
            var other=assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,
                    CardinalContainedCapsuleSource.prepare(s.tx.accepted(),s.contained.placement().shape().dimensions(),anotherWork));
            assertThrows(IllegalStateException.class,()->CertifiedReferenceEndpoint.containedRootObservation(s.tx,other,s.source));
        }
    }
    @Test void anotherActualCaptureOrNarrowWindowCannotBorrowRootLease() {
        try(var s=scene(CardinalFrame.Y_POS,GLOBAL)) {
            var other=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx,GLOBAL,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN));
            assertThrows(IllegalArgumentException.class,()->CapsuleFootContacts.collect(s.cap,other,.25,FreeOwnerTransaction.ACCURACY));
            var narrow=CapsuleFootContacts.requiredWindow(s.contained.pose(),.25);
            assertThrows(IllegalArgumentException.class,()->CapsuleContactCollector.collectWithin(s.cap,s.source.capture(),narrow,FreeOwnerTransaction.ACCURACY));
            assertTrue(s.cap.current());
        }
    }
    @Test void fixedRootWindowMissingRequiredFootSweepIsRejectedRatherThanSilentlyExpanded() {
        var window=new Aabb(new Vec3d(8.25,69,8.25),new Vec3d(8.75,70,8.75));
        try(var s=scene(CardinalFrame.Y_POS,window)) {
            assertTrue(s.cap.current());
            assertThrows(IllegalArgumentException.class,()->CapsuleFootContacts.collect(s.cap,s.source.capture(),.25,FreeOwnerTransaction.ACCURACY));
        }
    }
    @Test void realContinuationIsNotAContainedRootAndPhaseInvalidationIsRetained() {
        try(var s=scene(CardinalFrame.Y_POS,GLOBAL)) {
            var arcTarget=new PlayerBodyPlacement(s.contained.placement().center(),
                    new Rotation3d(0,Math.sin(.2),0,Math.cos(.2)),s.contained.placement().shape());
            var arc=assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,
                    CapsuleArcValidator.validate(s.source,arcTarget,new Vec3d(0,1,0)));
            var continuation=CapsulePathSource.continueAfter(arc,s.tx.numeric());
            assertThrows(IllegalStateException.class,()->CertifiedReferenceEndpoint.containedRootObservation(s.tx,s.contained,continuation));
            var prepared=s.tx.prepareInstallation(arcTarget,CertifiedReferenceEndpoint.from(s.tx,continuation));
            s.tx.beginInstallation(prepared);
            assertFalse(s.cap.current());
            assertThrows(IllegalStateException.class,()->CapsuleFootContacts.collect(s.cap,s.source.capture(),.25,FreeOwnerTransaction.ACCURACY));
        }
    }
    @Test void retainedFootMatchesRechecksActualOwnerAfterTheQuery() {
        try(var s=scene(CardinalFrame.Y_POS,GLOBAL)) {
            var foot=assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                    CapsuleFootContacts.collect(s.cap,s.source.capture(),.25,FreeOwnerTransaction.ACCURACY));
            long spent=s.tx.queries().calls();s.current.set(false);
            assertThrows(IllegalStateException.class,()->foot.matches(s.tx.accepted(),s.contained.pose(),s.source.capture(),s.tx.queries()));
            assertThrows(IllegalStateException.class,()->CapsuleFootContacts.collect(s.cap,s.source.capture(),.25,FreeOwnerTransaction.ACCURACY));
            assertEquals(spent,s.tx.queries().calls(),"invalid owner cannot run or refund another query");
        }
    }
}
