package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Native tangent representation and the real continuous recovery under one original ledger. */
class CardinalNativeTargetTangentTest {
    @Test void twoUlpTranslatedCandidateKeepsActualEndpointsAndPassesRealArc(@TempDir Path directory) {
        try(var fixture=BootWorldFixture.open(directory,new UUID(0,12621),true)) {
            var actor=fixture.serverPlayer(new UUID(0,12622));
            var bounds=new Aabb(new Vec3d(37.19999998807907,88.59999996423721,1),
                    new Vec3d(37.80000001192093,89.19999998807907,2.7999999523162846));
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),bounds.max().x(),bounds.max().y(),bounds.max().z()));
            actor.resetPositionToBB();
            var dims=new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight());
            var placement=PlayerBodyPlacement.fromCardinalBounds(bounds,new Vec3d(actor.posX,actor.posY,actor.posZ),
                    CardinalFrame.Z_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS,dims));
            assertEquals(new Vec3d(37.5,88.89999997615814,1.8999999761581423),placement.center());
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,12623),0,2),12178,placement,new Vec3d(0,0,0));
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(35,86,-1),new Vec3d(40,92,5)),World.MAX_ENTITY_RADIUS,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,new CollisionQueryFootprint.Limits(16384,16,4096,4096)));
            fixture.world().getChunkProvider().getLoadedChunk(2,0).setBlockState(new BlockPos(37,88,0),Blocks.STONE.getDefaultState());fixture.world().getChunkProvider().getLoadedChunk(2,0).setBlockState(new BlockPos(37,89,0),Blocks.STONE.getDefaultState());
            var budget=new BoostMovementBudget(70,0,new Vec3(actor.posX,actor.posY,actor.posZ));var budgetBefore=budget.snapshot();
            var owner=new PlayerPhysicsStep.TravelState();
            var resources=new FreeTickResources.Store().acquire(owner,fixture.world(),accepted.scope(),owner.correctionRevision());
            try(var tx=new FreeOwnerTransaction(actor,accepted,owner,budget,()->true,owner,resources)) {
                var work=tx.numeric();
                var source=assertInstanceOf(CardinalContainedCapsuleSource.Prepared.class,CardinalContainedCapsuleSource.prepare(accepted,dims,work));
                var oldTarget=new PlayerBodyPlacement(placement.center(),CardinalFrame.Y_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,dims));
                assertTrue(StoredDyadic.of(placement.center().z(),work).add(StoredDyadic.of(dims.radius(),work),work)
                        .compare(StoredDyadic.of(oldTarget.bounds().max().z(),work),work)>0,"radius support exceeds native target independently of half length");
                var shifted=new Vec3d(placement.center().x(),placement.center().y(),Math.nextUp(placement.center().z()));
                var target=new PlayerBodyPlacement(shifted,CardinalFrame.Y_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,dims));
                var window=new Aabb(new Vec3d(35,86,-1),new Vec3d(40,92,5));
                var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(tx,window,
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,CardinalClearancePrepared.SCAN));
                for(var box:capture.boxes())assertFalse(CardinalClearancePrepared.overlaps(box,target.bounds()));
                var root=CapsulePathSource.containedCardinal(source.containment(),CapsulePlacementMath.pose(source.placement(),work),capture,window,
                        FreeOwnerTransaction.ACCURACY,tx.queries(),work);
                var translation=assertInstanceOf(CapsuleTranslationValidator.VerifiedTranslation.class,CapsuleTranslationValidator.validateToCenter(root,shifted));
                var continuation=CapsulePathSource.continueAfter(translation,work);
                var recipe=assertInstanceOf(CardinalRealizedArcRecipe.Prepared.class,CardinalRealizedArcRecipe.prepareTranslated(source.containment(),continuation,translation,target,work));
                assertSame(translation.target(),recipe.recipe().start().placement());
                var arc=assertInstanceOf(CapsuleArcValidator.VerifiedArc.class,CapsuleArcValidator.validateCardinal(continuation,recipe));
                var prepared=new CardinalClearancePrepared.Prepared(tx,capture,window,root,translation,continuation,recipe,arc,target,translation.centerPathUpper());
                assertTrue(prepared.current());assertEquals(budgetBefore,budget.snapshot());
                var result=assertInstanceOf(CardinalReorientation.Recovered.class,CardinalClearanceFinalizer.install(prepared,0,false));
                assertEquals(shifted,result.snapshot().placement().center());assertEquals(accepted.revision()+1,result.snapshot().revision());
                assertTrue(work.units()<StoredDyadic.MAX_WORK_UNITS);

            }
        }
    }
    private static final PlayerBodyShape.Dimensions NATIVE = new PlayerBodyShape.Dimensions((double).6F,(double)1.8F,(double)1.62F);
    private static final double ORIGINAL = 1.8999999761581423;
    @Test void exactNativeRadiusIncompatibilityDoesNotDependOnHalfLength() {
        var center=new Vec3d(37.5,88.89999997615814,ORIGINAL);
        var work=new StoredDyadic.Work();
        assertEquals(CardinalClearancePrepared.TangentRadiusStatus.Z_INCOMPATIBLE,CardinalClearancePrepared.tangentRadiusStatus(center,NATIVE,work));
        var shorter=new PlayerBodyShape.Dimensions(NATIVE.width(),1.2,NATIVE.eyeHeight()/2);
        assertEquals(CardinalClearancePrepared.TangentRadiusStatus.Z_INCOMPATIBLE,CardinalClearancePrepared.tangentRadiusStatus(center,shorter,work));
        var r=StoredDyadic.of(NATIVE.radius(),work);
        var sum=StoredDyadic.of(ORIGINAL,work).add(r,work);
        double rounded=ORIGINAL+NATIVE.radius();
        assertEquals(0,sum.subtract(StoredDyadic.of(rounded,work),work).compare(StoredDyadic.of(Math.scalb(1,-52),work),work));
    }
    @Test void firstJointGridCenterIsExactAndPreviousBinary64IsIncompatible() {
        var work=new StoredDyadic.Work();
        var selected=assertInstanceOf(CardinalClearancePrepared.TangentCenter.class,
                CardinalClearancePrepared.tangentCandidate(ORIGINAL,ORIGINAL,Math.nextUp(1.0)*NATIVE.centerlineHalfLength(),NATIVE.radius(),1,work));
        assertEquals(Math.nextUp(ORIGINAL),selected.value());
        assertEquals(CardinalClearancePrepared.TangentRadiusStatus.COMPATIBLE,CardinalClearancePrepared.tangentRadiusStatus(new Vec3d(37.5,88.89999997615814,selected.value()),NATIVE,work));
        assertEquals(ORIGINAL,Math.nextDown(selected.value()));
        assertEquals(CardinalClearancePrepared.TangentRadiusStatus.Z_INCOMPATIBLE,CardinalClearancePrepared.tangentRadiusStatus(new Vec3d(37.5,88.89999997615814,Math.nextDown(selected.value())),NATIVE,work));
    }
    @Test void negativeOrCrossedOrUnrepresentableGridDomainsRemainTypedUnavailable() {
        var work=new StoredDyadic.Work();
        assertEquals(CardinalClearancePrepared.TangentCandidateFailure.POSITIVE_NORMAL_DOMAIN,
                assertInstanceOf(CardinalClearancePrepared.TangentUnavailable.class,CardinalClearancePrepared.tangentCandidate(-2,-2,.6,.3,0,work)).reason());
        assertEquals(CardinalClearancePrepared.TangentCandidateFailure.BINADE_DOMAIN,
                assertInstanceOf(CardinalClearancePrepared.TangentUnavailable.class,CardinalClearancePrepared.tangentCandidate(Math.nextDown(Math.scalb(1.0,52)),Math.nextDown(Math.scalb(1.0,52)),.6,.25,0,work)).reason());
        assertEquals(CardinalClearancePrepared.TangentCandidateFailure.RADIUS_GRID,
                assertInstanceOf(CardinalClearancePrepared.TangentUnavailable.class,CardinalClearancePrepared.tangentCandidate(ORIGINAL,ORIGINAL,.6,Double.MIN_NORMAL,0,work)).reason());
        assertEquals(CardinalClearancePrepared.TangentCandidateFailure.CENTER_UNREPRESENTABLE,
                assertInstanceOf(CardinalClearancePrepared.TangentUnavailable.class,CardinalClearancePrepared.tangentCandidate(Math.scalb(1.0,51),Math.scalb(1.0,51),.6,.25,0,work)).reason());
        assertEquals(CardinalClearancePrepared.TangentCandidateFailure.SUPPORT_DOMAIN,
                assertInstanceOf(CardinalClearancePrepared.TangentUnavailable.class,CardinalClearancePrepared.tangentCandidate(ORIGINAL,ORIGINAL,.6,NATIVE.radius(),2,work)).reason());
    }
    @Test void tangentProofChargesTheOriginalLedgerAndDoesNotReplaceExhaustion() {
        var work=new StoredDyadic.Work();work.charge(StoredDyadic.MAX_WORK_UNITS-4);
        var cause=assertThrows(NumericWorkExhaustedException.class,()->CardinalClearancePrepared.tangentCandidate(ORIGINAL,ORIGINAL,.6,NATIVE.radius(),1,work));
        assertEquals(StoredDyadic.MAX_WORK_UNITS-4,cause.used());
        assertEquals(StoredDyadic.MAX_WORK_UNITS-4,work.units());
    }

}
