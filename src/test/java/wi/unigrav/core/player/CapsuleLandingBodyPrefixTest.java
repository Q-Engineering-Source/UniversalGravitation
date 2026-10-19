package wi.unigrav.core.player;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.*;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real mapped World capture and CHB API; not normal-FML or callback runtime acceptance. */
class CapsuleLandingBodyPrefixTest {
    @TempDir Path directory;
    private static final UUID PLAYER = new UUID(0, 9084);
    private static final Vec3d DELTA = new Vec3d(0,-1.08,0);
    private static final Aabb FLOOR = new Aabb(new Vec3d(8,64,8),new Vec3d(9,65,9));
    private static final QueryLimits LIMITS = new QueryLimits(8192,100000,256,128,9344,150000,128);
    private static final ContactAccuracy ACCURACY = FreeOwnerTransaction.ACCURACY;
    record Scene(BootWorldFixture fixture, FreeOwnerTransaction tx, AtomicBoolean current,
                 CapsulePlacementMath.Translation tube, WorldCollisionCandidates.Snapshot capture) implements AutoCloseable {
        public void close() { tx.close(); fixture.close(); }
        CapsuleMovementResolver.Resolved resolve() {
            return assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                    CapsuleMovementResolver.resolveWithSlide(tx,tube,capture));
        }
    }
    private Scene scene(String name) throws Exception {
        var fixture=BootWorldFixture.open(directory.resolve(name),PLAYER,false);
        var actor=fixture.serverPlayer(PLAYER);
        actor.width=.6000000238418579F;actor.height=1.7999999523162842F;
        var size=new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight());
        var placement=new PlayerBodyPlacement(new Vec3d(8.5,66.39999997615814,8.5),
                CardinalFrame.Y_POS.attitude(),new PlayerBodyShape.FreeCapsule(size));
        var transport=placement.transport();var bounds=placement.bounds();
        actor.setPosition(transport.x(),transport.y(),transport.z());
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),
                bounds.max().x(),bounds.max().y(),bounds.max().z()));
        var chunk=fixture.world().getChunkProvider().getLoadedChunk(0,0);
        chunk.setBlockState(new BlockPos(8,64,8),Blocks.STONE.getDefaultState());
        chunk.setBlockState(new BlockPos(10,64,8),Blocks.STONE.getDefaultState());
        var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),0,1),
                7,placement,new Vec3d(0,-21.6,0));
        var current=new AtomicBoolean(true);
        var ledger=new BoostMovementBudget(70,0,new Vec3(transport.x(),transport.y(),transport.z()));
        var tx=new FreeOwnerTransaction(actor,accepted,new PlayerPhysicsStep.TravelState(),ledger,current::get);
        var tube=CapsulePlacementMath.translationTube(CapsulePlacementMath.pose(placement,tx.numeric()),DELTA,tx.numeric());
        assertTrue(tx.reserve(tx.nextLedgerSequence(),0,tube.requestedDisplacementUpper()).accepted());
        var window=new Aabb(new Vec3d(7,63,7),new Vec3d(11,69,10));
        var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(tx,window,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,new CollisionQueryFootprint.Limits(16384,16,4096,4096),
                tx.resources().environmentObservationWork()));
        return new Scene(fixture,tx,current,tube,capture);
    }
    @Test void realFloorAndFarBoxProduceAnExactTouchWithWholeBodyReceipts() throws Exception {
        try(var scene=scene("touch-and-far")) {
            assertTrue(scene.capture().boxes().size()>=2,"far collider must actually participate");
            var result=scene.resolve();
            assertEquals(65,result.end().placement().transport().y(),0);
            assertFalse(result.clipEvents().isEmpty(),"landing event must come from a real limiting proof");
            assertTrue(result.matches(scene.tx()));
            var proofs=result.queries().stream().filter(q->q.bodyPrefix().isPresent()).toList();
            assertEquals(scene.capture().boxes().size(),proofs.size());
            assertTrue(proofs.stream().anyMatch(q->q.actualFinal().orElseThrow().relation()==Relation.TOUCHING));
            assertTrue(proofs.stream().anyMatch(q->q.actualFinal().orElseThrow().relation()==Relation.SEPARATED));
            for(var query:proofs) {
                var proof=query.bodyPrefix().orElseThrow();
                assertSame(scene.tube().start().capsule(),proof.startCapsule());
                assertSame(DELTA,proof.originalDelta());
                assertSame(result.end().capsule(),proof.installation().storedCapsule());
                assertEquals(result.fraction(),proof.endFraction(),0);
            }
        }
    }
    private static Capsule startCapsule() {
        return new Capsule(new Segment3d(new Vec3d(8.5,65.80000001192093,8.5),
                new Vec3d(8.5,66.99999994039536,8.5)),.30000001192092896);
    }
    private static StoredInstallCandidate candidate(double fraction) {
        var center=new Vec3d(8.5,66.39999997615814,8.5);
        var at=center.add(DELTA.multiply(fraction));var half=new Vec3d(0,.5999999642372131,0);
        var capsule=new Capsule(new Segment3d(at.subtract(half),at.add(half)),.30000001192092896);
        return new StoredInstallCandidate(fraction,center,at,half,capsule);
    }
    @Test void sameStoredTouchDoesNotAuthorizeTheNextUpOriginalParameter() {
        double fraction=.4629629629629629;
        var budget=new CapsuleContactQueryBudget(LIMITS,10);
        var good=budget.proveOriginalCapsuleTranslationPrefix(startCapsule(),DELTA,fraction,FLOOR,candidate(fraction),ACCURACY);
        assertTrue(good.proof().isPresent());
        assertEquals(Relation.TOUCHING,good.proof().orElseThrow().installation().storedContact().relation());
        var spent=budget.work();double invalid=Math.nextUp(fraction);
        var bad=budget.proveOriginalCapsuleTranslationPrefix(startCapsule(),DELTA,invalid,FLOOR,candidate(invalid),ACCURACY);
        assertTrue(bad.proof().isEmpty());assertEquals(Reason.NO_PREFIX_CERTIFICATE,bad.reason().orElseThrow());
        assertTrue(budget.work().exactOps()>spent.exactOps(),"failed original parameter consumes actual work");
    }
    @Test void completedProofCannotSurviveItsOwnerBecomingNonCurrent() throws Exception {
        try(var scene=scene("retired-contact")) {
            var result=scene.resolve();var spent=scene.tx().queries().work();
            scene.current().set(false);
            assertThrows(IllegalStateException.class,()->result.matches(scene.tx()));
            assertEquals(spent,scene.tx().queries().work());
        }
    }
    @Test void finalAllowedBodyPrefixCallLatchesTerminalWithoutRefundOrReuse() {
        var budget=new CapsuleContactQueryBudget(LIMITS,1);double fraction=.4629629629629629;
        var result=budget.proveOriginalCapsuleTranslationPrefix(startCapsule(),DELTA,fraction,FLOOR,candidate(fraction),ACCURACY);
        assertTrue(result.proof().isPresent());assertTrue(budget.terminal());var spent=budget.work();
        assertThrows(CapsuleContactQueryBudget.BudgetExhausted.class,()->budget.proveOriginalCapsuleTranslationPrefix(
                startCapsule(),DELTA,fraction,FLOOR,candidate(fraction),ACCURACY));
        assertEquals(spent,budget.work());
    }
}
