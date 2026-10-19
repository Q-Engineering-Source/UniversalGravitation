package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.floor.MagneticResponseRules;
import static org.junit.jupiter.api.Assertions.*;

class CardinalClearanceTransactionTest {
    @TempDir Path directory;
    @Test void installsOnceAndCommitsActualCenterCostWithoutReplayingMovement() {
        try (var s = scene(70)) {
            var prepared = s.prepare();
            var before = s.budget.snapshot();
            var installs = new AtomicInteger();
            BootWorldFixture.afterNextBoundingBox(s.actor, installs::incrementAndGet);
            var result = CardinalClearanceFinalizer.install(prepared,0,false);
            var applied = assertInstanceOf(CardinalReorientation.Recovered.class,result);
            assertEquals(1,installs.get());
            assertEquals(s.accepted.revision()+1,applied.snapshot().revision());
            assertEquals(prepared.target(),applied.snapshot().placement());
            assertEquals(before.seenSequence()+1,s.budget.snapshot().seenSequence());
            assertFalse(s.budget.snapshot().pending());
            assertEquals(before.distanceCredit()-prepared.centerPathUpper(),s.budget.snapshot().distanceCredit());
            assertFalse(applied.onGround(),"the old wall contact cannot become Y support");
            assertFalse(applied.snapshot().contacts().orElseThrow().onGround());
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED,s.tx.installationStage());
            assertEquals(prepared.target().bounds(),box(s.actor));
            assertThrows(IllegalStateException.class,()->s.tx.reserve(s.tx.nextLedgerSequence(),0,prepared.centerPathUpper()));
        }
    }
    @Test void insufficientMovementCreditNeverInstallsAndDoesNotRefundOrRepeatAdmission() {
        try (var s=scene(1e-15)) {
            var prepared=s.prepare();var before=s.budget.snapshot();
            var result=assertInstanceOf(CardinalReorientation.Rejected.class,CardinalClearanceFinalizer.install(prepared,0,false));
            assertTrue(result.cause().isPresent());
            assertEquals(s.accepted.placement().bounds(),box(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
            assertEquals(0,s.budget.snapshot().distanceCredit());
            s.tx.close();assertFalse(s.budget.snapshot().pending());
            assertEquals(before.seenSequence()+1,s.budget.snapshot().seenSequence());
        }
    }
    @Test void changedWorldAfterProofRejectsBeforeReservation() {
        try(var s=scene(70)) {
            var prepared=s.prepare();var before=s.budget.snapshot();
            s.put(37,89,2,Blocks.STONE);
            assertInstanceOf(CardinalReorientation.Rejected.class,CardinalClearanceFinalizer.install(prepared,0,false));
            assertEquals(before,s.budget.snapshot());assertEquals(s.accepted.placement().bounds(),box(s.actor));
        }
    }
    @Test void expiredOwnerNeverInstallsOrAdmits() {
        try(var s=scene(70)) {
            var prepared=s.prepare();var before=s.budget.snapshot();s.current.set(false);
            var result=assertInstanceOf(CardinalReorientation.Rejected.class,CardinalClearanceFinalizer.install(prepared,0,false));
            assertTrue(result.cause().isPresent());assertEquals(before,s.budget.snapshot());assertEquals(s.accepted.placement().bounds(),box(s.actor));
        }
    }
    @Test void postcommitSupportFailureRetainsOriginalCauseActualJournalAndSingleDebit() {
        try(var s=scene(70)) {
            var prepared=s.prepare();var before=s.budget.snapshot();
            var original=new IllegalStateException("clearance-support-original");
            Runnable failOnlyAfterCommit = new Runnable() {
                public void run() {
                    if (s.tx.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                            && !s.budget.snapshot().pending()) throw original;
                    BootWorldFixture.afterNextPlayerSleepingPredicate(s.actor,this);
                }
            };
            BootWorldFixture.afterNextBoundingBox(s.actor,()->BootWorldFixture.afterNextPlayerSleepingPredicate(s.actor,failOnlyAfterCommit));
            var failure=assertInstanceOf(CardinalReorientation.CommittedFailure.class,CardinalClearanceFinalizer.install(prepared,0,false));
            assertSame(original,failure.cause());assertSame(original,s.tx.originalFailure().orElseThrow());
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED,failure.custody().orElseThrow().stage());
            assertEquals(s.accepted.revision()+1,failure.snapshot().revision());
            assertEquals(prepared.target(),failure.snapshot().placement());assertEquals(prepared.target().bounds(),box(s.actor));
            assertEquals(before.seenSequence()+1,s.budget.snapshot().seenSequence());assertFalse(s.budget.snapshot().pending());
            assertEquals(before.distanceCredit()-prepared.centerPathUpper(),s.budget.snapshot().distanceCredit());
            assertFalse(s.actor.onGround);
        }
    }
    @Test void supportCallbackFailureRemainsPrimaryWhenItsOwnerExpiresBeforeTheResultReturns() {
        try (var s = scene(70)) {
            var prepared = s.prepare();
            var before = s.budget.snapshot();
            var original = new IllegalStateException("clearance-support-replaced-owner-original");
            var callbacks = new AtomicInteger();
            var installs = new AtomicInteger();
            BootWorldFixture.afterNextBoundingBox(s.actor, () -> {
                installs.incrementAndGet();
                s.fixture.onLoadedChunkReadWhen(
                        () -> s.tx.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                                && !s.budget.snapshot().pending(),
                        () -> {
                            callbacks.incrementAndGet();
                            s.current.set(false);
                            s.fixture.world().setTotalWorldTime(s.fixture.world().getTotalWorldTime() + 2);
                            // Values belong to the successor callback and must not be overwritten by the old tail.
                            s.actor.onGround = true;
                            s.actor.motionX = .75;
                            throw original;
                        });
            });
            var failure = assertInstanceOf(CardinalReorientation.CommittedFailure.class,
                    CardinalClearanceFinalizer.install(prepared, 0, false));
            assertEquals(1, installs.get());
            assertEquals(1, callbacks.get());
            assertSame(original, failure.cause());
            assertSame(original, s.tx.originalFailure().orElseThrow());
            assertTrue(Arrays.stream(original.getSuppressed())
                    .anyMatch(OwnerContextChangedException.class::isInstance),
                    "the post-observation current fence must remain attached to the original callback failure");
            assertSame(s.owner, failure.custody().orElseThrow().ownerIdentity());
            assertEquals(s.accepted.revision() + 1, failure.snapshot().revision());
            assertEquals(prepared.target(), failure.snapshot().placement());
            assertEquals(prepared.target().bounds(), box(s.actor));
            assertTrue(s.actor.onGround, "the old stack must not publish its own contact result over the successor");
            assertEquals(.75, s.actor.motionX, 0);
            assertFalse(s.current.get());
            assertEquals(before.seenSequence() + 1, s.budget.snapshot().seenSequence());
            assertFalse(s.budget.snapshot().pending());
            assertEquals(before.distanceCredit() - prepared.centerPathUpper(), s.budget.snapshot().distanceCredit());
        }
    }
    @Test void reentryDuringInstallPreservesPartialCustodyWithoutRestoringOriginalBody() {
        try(var s=scene(70)) {
            var prepared=s.prepare();var original=new IllegalStateException("clearance-reentry-original");
            BootWorldFixture.afterNextBoundingBox(s.actor,()->{s.current.set(false);throw original;});
            var failure=assertInstanceOf(CardinalReorientation.CommittedFailure.class,CardinalClearanceFinalizer.install(prepared,0,false));
            assertSame(original,failure.cause());assertEquals(FreeOwnerTransaction.InstallationStage.PARTIAL,failure.custody().orElseThrow().stage());
            assertEquals(prepared.target().bounds(),box(s.actor));assertFalse(s.current.get());
            assertSame(s.owner,failure.custody().orElseThrow().ownerIdentity());
        }
    }
    @Test void foreignWorkAndWrongContinuationCannotMintARecipe() {
        try(var s=scene(70)) {
            var prepared=s.prepare();var work=s.tx.numeric();
            var recipe=prepared.arcRecipe().recipe();
            assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_IDENTITY,
                    assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,CardinalRealizedArcRecipe.prepareTranslated(
                            recipe.sourceContainment(),prepared.continuation(),prepared.translation(),prepared.target(),new StoredDyadic.Work())).reason());
            assertEquals(CardinalRealizedArcRecipe.Reason.SOURCE_IDENTITY,
                    assertInstanceOf(CardinalRealizedArcRecipe.Unavailable.class,CardinalRealizedArcRecipe.prepareTranslated(
                            recipe.sourceContainment(),prepared.root(),prepared.translation(),prepared.target(),work)).reason());
            assertFalse(recipe.matchesPathSource(prepared.root()));
        }
    }
    @Test void reassembledPreparedTargetOrPathFeeCannotAuthorizeAnotherInstall() {
        try(var s=scene(70)) {
            var p=s.prepare();
            var wrongTarget=new PlayerBodyPlacement(p.target().center(),p.target().attitude(),p.target().shape());
            var forgedTarget=new CardinalClearancePrepared.Prepared(s.tx,p.capture(),p.window(),p.root(),p.translation(),p.continuation(),
                    p.arcRecipe(),p.arc(),wrongTarget,p.centerPathUpper());
            assertFalse(forgedTarget.current());
            var forgedCost=new CardinalClearancePrepared.Prepared(s.tx,p.capture(),p.window(),p.root(),p.translation(),p.continuation(),
                    p.arcRecipe(),p.arc(),p.target(),0);
            assertFalse(forgedCost.current());assertEquals(s.accepted.placement().bounds(),box(s.actor));
        }
    }
    @Test void exhaustedNumericLedgerRetainsTheActualCauseAndNeverReserves() {
        try(var s=scene(70)) {
            var before=s.budget.snapshot();s.tx.numeric().charge(StoredDyadic.MAX_WORK_UNITS);
            var failure=assertInstanceOf(CardinalClearancePrepared.Unavailable.class,CardinalClearancePrepared.prepare(s.tx,CardinalFrame.Y_POS));
            assertInstanceOf(NumericWorkExhaustedException.class,failure.rejection().cause().orElseThrow());
            assertEquals(before,s.budget.snapshot());assertEquals(s.accepted.placement().bounds(),box(s.actor));
        }
    }
    @Test void fullCardinalTargetObstacleIsRejectedBeforeTranslationOrDebit() {
        try(var s=scene(70)) {
            s.put(37,89,2,Blocks.STONE);var before=s.budget.snapshot();
            var failure=assertInstanceOf(CardinalClearancePrepared.Unavailable.class,CardinalClearancePrepared.prepare(s.tx,CardinalFrame.Y_POS));
            assertEquals(CardinalReorientation.Reason.TARGET_OCCUPIED,failure.rejection().reason());
            assertEquals(before,s.budget.snapshot());assertEquals(s.accepted.placement().bounds(),box(s.actor));
        }
    }
    @Test void productionStepConsumesRecoveryTickAndPublishesTheNewTypedReference() {
        var previous=UnigravMod.entityBinder;var binder=new GravityEntityBinder();UnigravMod.entityBinder=binder;
        try(var s=scene(70);var binding=binder.bind(s.actor,GravityMode.REPLACE);
            var field=AmbientWorldFields.field(s.fixture.world()).registerContribution(
                    "clearance-test", "production-recovery-downward",
                    new UniformGravityContribution(new Vec3(0,-32,0)))) {
            var step=new PlayerPhysicsStep(new PlayerEnvironmentSampler(binder,MagneticResponseRules.builtIn(),.5));
            var controls=new PlayerPhysicsProtocol.Controls(new Vec3d(.6,0,.8),new Vec3d(0,0,1),false,false,false,false,5);
            var before=s.budget.snapshot();var installs=new AtomicInteger();
            BootWorldFixture.afterNextBoundingBox(s.actor,installs::incrementAndGet);
            var result=step.stepOwned(s.actor,s.accepted,Optional.of(false),controls,s.budget,0,false,s.owner,s.current::get,s.tx);
            var applied=assertInstanceOf(PlayerPhysicsStep.Applied.class,result,()->result.toString());
            assertEquals(1,installs.get());assertEquals(s.accepted.revision()+1,applied.snapshot().revision());
            assertEquals(s.accepted.placement().center().y(),applied.snapshot().placement().center().y());
            assertEquals(Math.nextUp(s.accepted.placement().center().z()),applied.snapshot().placement().center().z());
            assertEquals(CardinalFrame.Y_POS,((PlayerBodyShape.Cardinal)applied.snapshot().placement().shape()).frame());
            assertEquals(CardinalFrame.Y_POS,s.owner.bodyReference().effective().frame());
            assertEquals(1,s.owner.adoptions());assertEquals(before.seenSequence()+1,s.budget.snapshot().seenSequence());
            assertFalse(s.budget.snapshot().pending());
        } finally {UnigravMod.entityBinder=previous;}
    }

    private Scene scene(double ceiling) {
        var fixture=BootWorldFixture.open(directory.resolve(UUID.randomUUID().toString()),UUID.randomUUID(),true);
        try {
        var actor=fixture.serverPlayer(UUID.randomUUID());
        var bounds=new Aabb(new Vec3d(37.19999998807907,88.59999996423721,1),new Vec3d(37.80000001192093,89.19999998807907,2.7999999523162837));
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),bounds.max().x(),bounds.max().y(),bounds.max().z()));actor.resetPositionToBB();
        var dims=new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight());
        var placement=PlayerBodyPlacement.fromCardinalBounds(bounds,new Vec3d(actor.posX,actor.posY,actor.posZ),CardinalFrame.Z_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS,dims));
        var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),0,2),12178,placement,new Vec3d(0,-10,0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true,false,false,0,false)));
        fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(34,85,-2),new Vec3d(41,93,6)),World.MAX_ENTITY_RADIUS,
                CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,CardinalClearancePrepared.SCAN));
        wi.unigrav.core.floor.FloorWorldFields.onChunkLoad(new net.minecraftforge.event.world.ChunkEvent.Load(fixture.world().getChunk(2,0)));
        var owner=new PlayerPhysicsStep.TravelState();var budget=new BoostMovementBudget(ceiling,0,new Vec3(actor.posX,actor.posY,actor.posZ));var current=new AtomicBoolean(true);
        var resources=new FreeTickResources.Store().acquire(owner,fixture.world(),accepted.scope(),owner.correctionRevision());
        var tx=new FreeOwnerTransaction(actor,accepted,owner,budget,current::get,owner,resources);
        var scene=new Scene(fixture,actor,accepted,owner,budget,current,tx);scene.put(37,88,0,Blocks.STONE);scene.put(37,89,0,Blocks.STONE);return scene;
        } catch (RuntimeException | Error failure) {fixture.close();throw failure;}
    }
    private record Scene(BootWorldFixture fixture,EntityPlayerMP actor,PlayerPhysicalSnapshot accepted,PlayerPhysicsStep.TravelState owner,
            BoostMovementBudget budget,AtomicBoolean current,FreeOwnerTransaction tx) implements AutoCloseable {
        CardinalClearancePrepared.Prepared prepare() {
            var result=CardinalClearancePrepared.prepare(tx,CardinalFrame.Y_POS);
            return assertInstanceOf(CardinalClearancePrepared.Prepared.class,result,()->result.toString());
        }
        void put(int x,int y,int z,net.minecraft.block.Block block) {fixture.world().getChunkProvider().getLoadedChunk(x>>4,z>>4).setBlockState(new BlockPos(x,y,z),block.getDefaultState());}
        public void close(){try{tx.close();}finally{fixture.close();wi.unigrav.core.floor.FloorWorldFields.clearAll();}}
    }
    private static Aabb box(EntityPlayerMP actor) {var b=actor.getEntityBoundingBox();return new Aabb(new Vec3d(b.minX,b.minY,b.minZ),new Vec3d(b.maxX,b.maxY,b.maxZ));}
}
