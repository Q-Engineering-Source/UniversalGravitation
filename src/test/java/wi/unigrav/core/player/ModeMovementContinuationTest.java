package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real WorldServer candidates and owner transactions; this fixture initializes a closed Free state. */
class ModeMovementContinuationTest {
    private GravityEntityBinder previousBinder;
    @BeforeEach void installUnboundNativeFixtureBinder() {
        previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
    }
    @AfterEach void restoreNativeFixtureBinder() { UnigravMod.entityBinder = previousBinder; }

    @TempDir Path directory;

    @Test void certifiedEndpointQueriesMovedFootWithoutReplacingItsPhysicalOwner() {
        try (var s = scene()) {
            var prepared = s.prepare(new Vec3d(-.4, 0, .1), null);
            var endpoint = CertifiedReferenceEndpoint.from(s.tx, prepared.lineage());
            assertSame(s.accepted, endpoint.owner());
            assertSame(prepared.targetPose(), endpoint.pose());
            assertNotSame(s.accepted.placement(), endpoint.pose().placement());
            var before = s.budget.snapshot();
            var actorBefore = WorldCollisionCandidates.ActorGeometry.read(s.actor);
            var window = CapsuleFootContacts.window(endpoint, .25);
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                            ModeTransitionPrepared.SCAN, s.tx.resources().environmentObservationWork()));
            var oldEntry = assertInstanceOf(CapsuleContactCollector.Failed.class,
                    CapsuleContactCollector.collectWithin(s.accepted, endpoint.pose(), capture, window,
                            FreeOwnerTransaction.ACCURACY, s.tx.queries()));
            assertEquals(CapsuleContactCollector.Failure.INVALID_POSE, oldEntry.failure(),
                    "accepted-only entry must not silently accept certified endpoint geometry");
            var support = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                    CapsuleSupportObservation.observe(endpoint, capture, window, .25, FreeOwnerTransaction.ACCURACY));
            assertTrue(support.matches(s.accepted, endpoint.pose(), capture, window, s.tx.queries(), s.tx.numeric()));
            var source = BootSurfaceGeometry.fromEndpoint(endpoint, s.tx.resources().environmentObservationWork().arithmetic());
            assertFalse(source.isAcceptedSensor());
            assertTrue(source.endpointCurrentFor(s.accepted));
            assertSame(s.accepted, source.physical());
            assertEquals(endpoint.pose().placement().storedCapsuleGeometry().negativeEndpoint(), source.anchorBounds().min());
            assertEquals(actorBefore, WorldCollisionCandidates.ActorGeometry.read(s.actor));
            assertEquals(before, s.budget.snapshot(), "reference observation neither reserves nor refunds movement");
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
            s.current.set(false);
            assertThrows(IllegalStateException.class, endpoint::current);
            assertThrows(IllegalStateException.class, () -> source.endpointCurrentFor(s.accepted));
            assertThrows(IllegalStateException.class, endpoint::requireCurrent);
        }
    }

    @Test void certifiedResponseCalculationPreservesTangentsAndReboundWithoutInstalling() {
        try(var s=scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);
            var movement=prepared.movement();
            assertEquals(java.util.Set.of(0),movement.blockedAxes());
            var before=new Vec3d(s.actor.motionX,s.actor.motionY,s.actor.motionZ);
            var clipped=CapsuleMoveFinalizer.translationResponse(movement,new Vec3d(-.4,.03,.1));
            assertEquals(new Vec3d(0,.03,.1),clipped.motion());
            assertTrue(clipped.horizontal()); assertFalse(clipped.vertical());
            assertEquals(new Vec3d(.2,.03,.1),
                    CapsuleMoveFinalizer.translationResponse(movement,new Vec3d(.2,.03,.1)).motion(),
                    "a legal callback rebound is opposite the blocked requested component");
            assertEquals(before,new Vec3d(s.actor.motionX,s.actor.motionY,s.actor.motionZ),
                    "pure response does not authorize or perform an actor write");
            assertThrows(IllegalStateException.class,()->CapsuleMoveFinalizer.applyCertifiedTranslationResponse(s.tx,movement),
                    "ordinary response still requires its exact installed movement journal");
            assertEquals(before,new Vec3d(s.actor.motionX,s.actor.motionY,s.actor.motionZ));
        }
    }

    @Test void containedCardinalContinuationRejectsForeignSourceStartAndCaptureWithoutSpendingAgain() {
        try (var s = scene(8.5, true)) {
            var work = s.tx.numeric();
            var window = new Aabb(new Vec3d(7,68,7),new Vec3d(10,72,10));
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window,
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN));
            var virtual = new PlayerBodyPlacement(s.accepted.placement().center(), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,2,1.75)));
            var contained = assertInstanceOf(CardinalCapsuleContainment.Contained.class,
                    CardinalCapsuleContainment.prepare(s.accepted, virtual, work));
            var pose = CapsulePlacementMath.pose(virtual, work);
            var source = CapsulePathSource.containedCardinal(contained, pose, capture, window,
                    FreeOwnerTransaction.ACCURACY, s.tx.queries(), work);
            var tube = CapsulePlacementMath.chargedTranslationTube(pose, new Vec3d(0,0,.1), work);
            double allowance = tube.requestedDisplacementUpper();
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(), 0, allowance).accepted());
            var before = s.budget.snapshot();
            var foreignTravel = new PlayerPhysicsStep.TravelState();
            var foreignStore = new FreeTickResources.Store();
            try (var foreignTx = new FreeOwnerTransaction(s.actor, s.accepted, foreignTravel, s.budget,
                    s.current::get, foreignTravel, foreignStore.acquire(foreignTravel, s.fixture.world(), s.accepted.scope(), 0))) {
                assertThrows(IllegalArgumentException.class, () -> CapsuleMovementResolver.resolveContainedCardinalContinuation(
                        foreignTx, source, tube, capture, 0, allowance));
            }
            var otherPose = CapsulePlacementMath.pose(virtual, work);
            var otherTube = CapsulePlacementMath.chargedTranslationTube(otherPose, tube.delta(), work);
            assertThrows(IllegalArgumentException.class, () -> CapsuleMovementResolver.resolveContainedCardinalContinuation(
                    s.tx, source, otherTube, capture, 0, allowance));
            var otherCapture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx, window,
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT, ModeTransitionPrepared.SCAN));
            assertThrows(IllegalArgumentException.class, () -> CapsuleMovementResolver.resolveContainedCardinalContinuation(
                    s.tx, source, tube, otherCapture, 0, allowance));
            assertEquals(before, s.budget.snapshot());
            var result = CapsuleMovementResolver.resolveContainedCardinalContinuation(s.tx, source, tube, capture, 0, allowance);
            var resolved = assertInstanceOf(CapsuleMovementResolver.Resolved.class, result, result::toString);
            assertTrue(resolved.matchesBound(source));
            assertSame(s.accepted, CapsulePathSource.continueAfter(resolved, work).actualSource());
            assertEquals(before, s.budget.snapshot());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    @Test void boundedContinuationCannotSpendTheCorrectionReservationOnResidualSlide() {
        try (var s = scene()) {
            var work = s.tx.numeric();
            var window = new Aabb(new Vec3d(7,68,7),new Vec3d(10,72,10));
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx,window,
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,ModeTransitionPrepared.SCAN));
            var source = CapsulePathSource.actualFree(s.accepted,
                    CapsulePlacementMath.pose(s.accepted.placement(),work),capture,window,
                    FreeOwnerTransaction.ACCURACY,s.tx.queries(),work);
            var tube = CapsulePlacementMath.chargedTranslationTube(source.start(),new Vec3d(-.4,0,.1),work);
            double allowance = tube.requestedDisplacementUpper();
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(),0,allowance+1).accepted());
            var before = s.budget.snapshot();
            var result = CapsuleMovementResolver.resolveContinuationWithSlide(s.tx,source,tube,capture,0,allowance);
            var resolved = assertInstanceOf(CapsuleMovementResolver.Resolved.class,result,()->result.toString());
            assertTrue(resolved.pathUpper()<=allowance,"correction allowance cannot enlarge movement's endpoint range");
            assertTrue(resolved.segments().size()>1,"the finite allowance is exercised by a genuine residual slide");
            assertEquals(java.util.Set.of(0),resolved.blockedAxes());
            for(var segment:resolved.segments()) assertSame(capture,segment.capture(),"one original context/window");
            assertTrue(resolved.matchesBound(source));
            assertSame(s.accepted,CapsulePathSource.continueAfter(resolved,work).actualSource());
            assertEquals(before,s.budget.snapshot(),"resolver does not reserve or refund a second grant");
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }

    @Test void boundedContinuationNumericFailureKeepsItsExistingReservationSpent() {
        try(var s=scene()) {
            var work=s.tx.numeric();
            var window=new Aabb(new Vec3d(7,68,7),new Vec3d(10,72,10));
            var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(s.tx,window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,ModeTransitionPrepared.SCAN));
            var source=CapsulePathSource.actualFree(s.accepted,CapsulePlacementMath.pose(s.accepted.placement(),work),
                    capture,window,FreeOwnerTransaction.ACCURACY,s.tx.queries(),work);
            var tube=CapsulePlacementMath.chargedTranslationTube(source.start(),new Vec3d(-.4,0,.1),work);
            assertTrue(s.tx.reserve(s.tx.nextLedgerSequence(),0,tube.requestedDisplacementUpper()+1).accepted());
            var reserved=s.budget.snapshot();
            work.charge(StoredDyadic.MAX_WORK_UNITS-work.units());
            assertThrows(NumericWorkExhaustedException.class,()->CapsuleMovementResolver.resolveContinuationWithSlide(
                    s.tx,source,tube,capture,0,tube.requestedDisplacementUpper()));
            assertEquals(reserved,s.budget.snapshot());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
            s.tx.close();
            assertEquals(reserved.distanceCredit(),s.budget.snapshot().distanceCredit());
        }
    }

    @Test void blockedTranslationStillCommitsTheCertifiedNonNoopAttitudeOnce() {
        try (var s = scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,0),null);
            assertNotNull(prepared.movement());
            assertEquals(java.util.Set.of(0),prepared.movement().blockedAxes());
            var before=s.budget.snapshot();
            var installed=ModeTransitionFinalizer.install(prepared,0);
            var applied=assertInstanceOf(ModeTransitionFinalizer.Applied.class,installed,()->installed.toString());
            assertEquals(s.accepted.revision()+1,applied.snapshot().revision());
            assertEquals(prepared.target().attitude(),applied.snapshot().placement().attitude());
            assertNotEquals(s.accepted.placement().attitude(),applied.snapshot().placement().attitude());
            assertTrue(applied.snapshot().placement().bounds().min().x()>=8);
            assertEquals(0,applied.snapshot().worldVelocity().x());
            assertTrue(s.actor.collidedHorizontally);assertTrue(s.actor.collided);assertFalse(s.actor.onGround);
            assertEquals(before.seenSequence(),s.budget.snapshot().seenSequence(),"finalizer consumes the existing reservation");
            assertFalse(s.budget.snapshot().pending());
        }
    }
    @Test void contactResponsePrecedesCallbackAndPreservesItsNewReboundMotion() {
        try(var s=scene()) {
            s.actor.onAddedToWorld();s.fixture.world().updateEntityWithOptionalForce(s.actor,false);
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);
            var called=new AtomicInteger();
            s.fixture.afterNextEntityUpdate(s.actor,()->{
                called.incrementAndGet();assertEquals(0,s.actor.motionX);assertTrue(s.actor.collidedHorizontally);
                s.actor.motionX=.02;s.actor.motionZ=.03;
            });
            var installed=ModeTransitionFinalizer.install(prepared,0);
            var applied=assertInstanceOf(ModeTransitionFinalizer.Applied.class,installed,()->installed.toString());
            assertEquals(1,called.get());assertEquals(.4,applied.snapshot().worldVelocity().x());
            assertEquals(.6,applied.snapshot().worldVelocity().z());
        }
    }
    @Test void callbackFailureRetainsOriginalCauseInstalledCustodyAndSingleDebit() {
        try(var s=scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);
            var before=s.budget.snapshot();var original=new IllegalStateException("contact-callback-original");
            var called=new AtomicInteger();s.actor.onAddedToWorld();
            s.fixture.afterNextEntityUpdate(s.actor,()->{called.incrementAndGet();assertEquals(0,s.actor.motionX);throw original;});
            var failure=assertInstanceOf(ModeTransitionFinalizer.CommittedFailure.class,ModeTransitionFinalizer.install(prepared,0));
            assertEquals(1,called.get());assertSame(original,failure.cause());assertSame(original,s.tx.originalFailure().orElseThrow());
            assertEquals(FreeOwnerTransaction.InstallationStage.INSTALLED,failure.custody().orElseThrow().stage());
            assertEquals(s.accepted.revision()+1,failure.snapshot().revision());
            assertEquals(before.seenSequence(),s.budget.snapshot().seenSequence());assertFalse(s.budget.snapshot().pending());
        }
    }
    @Test void preinstallWorldOrOwnerChangeCannotInstallOrApplyContactResponse() {
        for(boolean ownerChange:new boolean[]{false,true}) try(var s=scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);var before=s.budget.snapshot();
            if(ownerChange)s.current.set(false);else s.put(8,70,8);
            assertInstanceOf(ModeTransitionFinalizer.Unavailable.class,ModeTransitionFinalizer.install(prepared,0));
            assertEquals(-.4,s.actor.motionX);assertEquals(s.accepted.placement().bounds(),box(s.actor));
            assertEquals(before.seenSequence(),s.budget.snapshot().seenSequence());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }
    @Test void exactCurrentRawAttitudeIsNotAnArcAndCannotBlockTranslation() {
        try (var s = scene()) {
            var request = new PlayerAttitudeRequest(4, s.accepted.scope(), 1, 0, s.fixture.world(),
                    s.request.connection(), s.request.scopeHandle(), 0, s.accepted.placement().attitude());
            var delta = new Vec3d(0, 0, .1);
            var result = ModeTransitionPrepared.prepareFreeTick(s.tx, request, null, delta, 0);
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class, result, result::toString);
            assertTrue(prepared.phases().stream().noneMatch(ModeTransitionPrepared.ArcPhase.class::isInstance),
                    "the exact accepted raw Q must retain its stored realization instead of asking for another arc");
            assertTrue(prepared.phases().stream().anyMatch(ModeTransitionPrepared.MovementPhase.class::isInstance));
            assertSame(s.accepted.placement().shape(), prepared.target().shape());
            assertEquals(s.accepted.placement().attitude(), prepared.target().attitude());
            var applied = assertInstanceOf(ModeTransitionFinalizer.Applied.class,
                    ModeTransitionFinalizer.install(prepared, 0));
            assertEquals(s.accepted.placement().center().z() + .1, applied.snapshot().placement().center().z());
            assertEquals(applied.snapshot().placement().bounds(), box(s.actor));
            assertFalse(s.budget.snapshot().pending());
        }
    }

    @Test void exactCurrentRawAttitudeAtRestHasARealZeroMovementProof() {
        try (var s = scene()) {
            var request = new PlayerAttitudeRequest(4, s.accepted.scope(), 1, 0, s.fixture.world(),
                    s.request.connection(), s.request.scopeHandle(), 0, s.accepted.placement().attitude());
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class,
                    ModeTransitionPrepared.prepareFreeTick(s.tx, request, null, new Vec3d(0, 0, 0), 0));
            assertEquals(1, prepared.phases().size());
            var movement = assertInstanceOf(ModeTransitionPrepared.MovementPhase.class, prepared.phases().getFirst()).proof();
            assertEquals(0, movement.pathUpper());
            assertEquals(0, prepared.centerPathUpper());
            assertEquals(s.accepted.placement(), prepared.target());
            assertTrue(prepared.matches(s.tx, request));
            var ledger = s.budget.snapshot();
            s.actor.motionX = 0; s.actor.motionY = 0; s.actor.motionZ = 0;
            var applied = assertInstanceOf(ModeTransitionFinalizer.Applied.class, ModeTransitionFinalizer.install(prepared, 0));
            assertEquals(s.accepted.placement(), applied.snapshot().placement());
            assertEquals(new Vec3d(0, 0, 0), applied.snapshot().worldVelocity());
            assertEquals(s.accepted.revision() + 1, applied.snapshot().revision());
            assertEquals(ledger.seenSequence(), s.budget.snapshot().seenSequence());
            assertEquals(ledger.distanceCredit(), s.budget.snapshot().distanceCredit());
            assertFalse(s.budget.snapshot().pending());
        }
    }

    @Test void zeroMovementProofKeepsExactRequestAndOwnerFences() {
        for (boolean ownerChange : new boolean[] {false, true}) try (var s = scene()) {
            var qualified = new AtomicBoolean(true);
            var request = MobilityTransitionRequest.zeroReference(s.tx, s.travel, qualified::get);
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class,
                    ModeTransitionPrepared.prepareFreeTick(s.tx, request, null, new Vec3d(0, 0, 0), 0));
            if (ownerChange) s.current.set(false); else qualified.set(false);
            assertInstanceOf(ModeTransitionFinalizer.Unavailable.class, ModeTransitionFinalizer.install(prepared, 0));
            assertEquals(s.accepted.placement().bounds(), box(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    @Test void zeroMovementCallbackCorrectionCannotCommitAnOldOwner() {
        try (var s = scene()) {
            var request = new PlayerAttitudeRequest(4, s.accepted.scope(), 1, 0, s.fixture.world(),
                    s.request.connection(), s.request.scopeHandle(), 0, s.accepted.placement().attitude());
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class,
                    ModeTransitionPrepared.prepareFreeTick(s.tx, request, null, new Vec3d(0, 0, 0), 0));
            s.actor.onAddedToWorld();
            var callbacks = new AtomicInteger();
            s.fixture.afterNextEntityUpdate(s.actor, () -> {
                callbacks.incrementAndGet();
                s.actor.connection.setPlayerLocation(12.5, 70, 8.5, 0, 0);
                s.current.set(false);
            });
            var result = ModeTransitionFinalizer.install(prepared, 0);
            assertEquals(1, callbacks.get());
            assertFalse(result instanceof ModeTransitionFinalizer.Applied);
            assertEquals(12.5, s.actor.posX);
            assertEquals(8.5, s.actor.posZ);
            assertTrue(s.tx.installationStage() == FreeOwnerTransaction.InstallationStage.INSTALLED
                    || s.tx.installationStage() == FreeOwnerTransaction.InstallationStage.PARTIAL);
        }
    }

    @Test void exactCurrentRawAttitudeDoesNotAuthorizeAnOverlappingSource() {
        try (var s = scene()) {
            s.put(8, 70, 8);
            var request = new PlayerAttitudeRequest(4, s.accepted.scope(), 1, 0, s.fixture.world(),
                    s.request.connection(), s.request.scopeHandle(), 0, s.accepted.placement().attitude());
            var result = ModeTransitionPrepared.prepareFreeTick(s.tx, request, null, new Vec3d(0, 0, .1), 0);
            assertInstanceOf(ModeTransitionPrepared.Unavailable.class, result);
            assertEquals(s.accepted.placement().bounds(), box(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    @Test void overlappingStartAndCollidingArcRemainUnavailableWithoutNativeMutation() {
        try(var s=scene()) {
            s.put(8,70,8);
            var result=ModeTransitionPrepared.prepareFreeTick(s.tx,s.request,null,new Vec3d(-.4,0,.1),0);
            assertInstanceOf(ModeTransitionPrepared.Unavailable.class,result);
            assertEquals(s.accepted.placement().bounds(),box(s.actor));assertEquals(-.4,s.actor.motionX);
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
        try(var s=scene()) {
            var q=new Rotation3d(0,0,Math.sqrt(.5),Math.sqrt(.5));
            var request=new PlayerAttitudeRequest(4,s.accepted.scope(),1,0,s.fixture.world(),s.request.connection(),s.request.scopeHandle(),0,q);
            var result=ModeTransitionPrepared.prepareFreeTick(s.tx,request,null,new Vec3d(-.4,0,.1),0);
            var failure=assertInstanceOf(ModeTransitionPrepared.Unavailable.class,result);
            assertTrue(failure.reason().startsWith("Free attitude arc proof failed"),failure.toString());
            assertEquals(s.accepted.placement().bounds(),box(s.actor));assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }
    @Test void numericExhaustionRetainsCauseAndCannotCreateMovementOrAnInstallation() {
        try(var s=scene()) {
            s.tx.numeric().charge(StoredDyadic.MAX_WORK_UNITS);
            var result=ModeTransitionPrepared.prepareFreeTick(s.tx,s.request,null,new Vec3d(-.4,0,.1),0);
            var failure=assertInstanceOf(ModeTransitionPrepared.Unavailable.class,result);
            assertInstanceOf(NumericWorkExhaustedException.class,failure.cause().orElseThrow());
            assertEquals(s.accepted.placement().bounds(),box(s.actor));assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
        }
    }
    @Test void resizeAndSlideUseOneReservationAndDebitBothCertifiedCenterPaths() {
        try(var s=scene()) {
            s.put(8,68,8);
            var dims=new PlayerBodyShape.Dimensions(.5,(double)1.8F,(double)1.62F);
            s.actor.height=1.8F;s.actor.eyeHeight=1.62F;
            var observed=WorldCollisionCandidates.ActorGeometry.read(s.actor);
            s.actor.height=2;s.actor.eyeHeight=1.75F;
            var before=s.budget.snapshot();
            var prepared=s.prepare(new Vec3d(-.35,0,.05),new ModeTransitionPrepared.TrustedResizeObservation(dims,observed));
            assertTrue(prepared.centerPathUpper()>=Math.abs(prepared.target().center().y()-s.accepted.placement().center().y())+prepared.movement().pathUpper());
            var reserved=s.budget.snapshot();assertEquals(before.seenSequence()+1,reserved.seenSequence());
            var oldMovementEndpoint = CertifiedReferenceEndpoint.from(s.tx,
                    CapsulePathSource.continueAfter(prepared.movement(), s.tx.numeric()));
            assertNotNull(prepared.movement().source());
            assertThrows(IllegalStateException.class,
                    () -> CertifiedReferenceEndpoint.fromMovement(s.tx, prepared.movement()));

            var installed=ModeTransitionFinalizer.install(prepared,0);
            var applied=assertInstanceOf(ModeTransitionFinalizer.Applied.class,installed,()->installed.toString());
            assertEquals(dims,applied.snapshot().placement().shape().dimensions());
            var installedEndpoint = s.tx.installedFreeEndpoint();
            assertSame(prepared.targetPose(), installedEndpoint.pose());
            assertSame(prepared.lineage().start(), installedEndpoint.pose());
            assertSame(prepared.target(), installedEndpoint.owner().placement());
            assertTrue(installedEndpoint.current());
            assertFalse(oldMovementEndpoint.current());
            assertThrows(IllegalStateException.class, () -> CapsuleFootContacts.window(oldMovementEndpoint, 0));
            assertInstalledSupportJoin(s, prepared);

            assertEquals(reserved.seenSequence(),s.budget.snapshot().seenSequence());
            assertTrue(before.distanceCredit()-reserved.distanceCredit()>=prepared.centerPathUpper());
            assertEquals(reserved.distanceCredit(),s.budget.snapshot().distanceCredit(),"the already-spent reservation is never refunded");
            assertEquals(s.accepted.revision()+1,applied.snapshot().revision());assertEquals(0,s.actor.motionX);
        }
    }
    @Test void zeroTranslationDoesNotInheritOldContactFlagsOrEraseMotion() {
        try(var s=scene()) {
            s.put(8,68,8);
            s.actor.collidedHorizontally=true;s.actor.collided=true;
            var prepared=s.prepare(new Vec3d(0,0,0),null);
            assertNull(prepared.movement());
            var oldArcEndpoint = CertifiedReferenceEndpoint.from(s.tx, prepared.lineage());
            var installed=ModeTransitionFinalizer.install(prepared,0);
            var applied=assertInstanceOf(ModeTransitionFinalizer.Applied.class,installed,()->installed.toString());
            assertEquals(-8,applied.snapshot().worldVelocity().x(),"pure rotation cannot fabricate a contact proof");
            var installedEndpoint = s.tx.installedFreeEndpoint();
            assertSame(prepared.targetPose(), installedEndpoint.pose());
            assertSame(prepared.lineage().start(), installedEndpoint.pose());
            assertSame(prepared.target(), installedEndpoint.owner().placement());
            assertTrue(installedEndpoint.current());
            assertFalse(oldArcEndpoint.current());
            assertThrows(IllegalStateException.class, () -> CapsuleFootContacts.window(oldArcEndpoint, 0));
            assertInstalledSupportJoin(s, prepared);

        }
    }

    @Test void exactTouchingAllBlockedCanCommitItsNewAttitudeWithZeroTranslation() {
        try(var s=scene(8.25)) {
            var prepared=s.prepare(new Vec3d(-.1,0,0),null);
            assertEquals(0,prepared.movement().pathUpper());
            var result=ModeTransitionFinalizer.install(prepared,0);
            var applied=assertInstanceOf(ModeTransitionFinalizer.Applied.class,result,()->result.toString());
            assertEquals(s.accepted.placement().center(),applied.snapshot().placement().center());
            assertNotEquals(s.accepted.placement().attitude(),applied.snapshot().placement().attitude());
            assertEquals(0,s.actor.motionX);assertEquals(s.accepted.revision()+1,applied.snapshot().revision());
        }
    }
    @Test void ownerReplacementDuringRecaptureStopsBeforeInstallationAndContactResponse() {
        try(var s=scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);var count=new AtomicInteger();
            var reserved=s.budget.snapshot();
            s.fixture.onLoadedChunkReadWhen(()->true,()->{count.incrementAndGet();s.current.set(false);});
            assertInstanceOf(ModeTransitionFinalizer.Unavailable.class,ModeTransitionFinalizer.install(prepared,0));
            assertEquals(1,count.get());assertEquals(-.4,s.actor.motionX);assertEquals(s.accepted.placement().bounds(),box(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
            s.tx.close();assertEquals(reserved.distanceCredit(),s.budget.snapshot().distanceCredit());assertFalse(s.budget.snapshot().pending());
        }
    }
    @Test void continuationRejectsForeignLineageWorkAndTransactionWithoutMintingAnInstall() {
        try(var s=scene()) {
            var prepared=s.prepare(new Vec3d(-.4,0,.1),null);var movement=prepared.movement();var source=movement.source();
            assertThrows(CapsulePathSource.InvalidSourceException.class,()->CapsulePathSource.continueAfter(movement,new StoredDyadic.Work()));
            var foreignActor=s.fixture.serverPlayer(UUID.randomUUID());
            foreignActor.width=.5F;foreignActor.height=2;foreignActor.eyeHeight=1.75F;
            var b=s.accepted.placement().bounds();var t=s.accepted.placement().transport();
            foreignActor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
            foreignActor.posX=t.x();foreignActor.posY=t.y();foreignActor.posZ=t.z();
            var foreignAccepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(foreignActor.getUniqueID(),UUID.randomUUID(),0,1),7,s.accepted.placement(),new Vec3d(0,0,0));
            var foreignOwner=new PlayerPhysicsStep.TravelState();
            try(var foreign=new FreeOwnerTransaction(foreignActor,foreignAccepted,foreignOwner,
                    new BoostMovementBudget(70,0,new Vec3(t.x(),t.y(),t.z())),()->true,foreignOwner,
                    new FreeTickResources.Store().acquire(foreignOwner,s.fixture.world(),foreignAccepted.scope(),0))) {
                var foreignTube=CapsulePlacementMath.translationTube(source.start(),new Vec3d(-.1,0,0),foreign.numeric());
                assertThrows(IllegalArgumentException.class,()->CapsuleMovementResolver.resolveContinuationWithSlide(foreign,source,foreignTube,source.capture(),0));
                assertEquals(FreeOwnerTransaction.InstallationStage.NONE,foreign.installationStage());
            }
            var replacement=CapsulePathSource.actualFree(s.accepted,CapsulePlacementMath.pose(s.accepted.placement(),s.tx.numeric()),
                    source.capture(),source.window(),FreeOwnerTransaction.ACCURACY,s.tx.queries(),s.tx.numeric());
            assertFalse(movement.matchesBound(replacement));
        }
    }
    @Test void insufficientCompositeGrantFailsExplicitlyWithoutInstallingOrRefunding() {
        try(var s=scene()) {
            var before=s.budget.snapshot();
            var result=ModeTransitionPrepared.prepareFreeTick(s.tx,s.request,null,new Vec3d(-4,0,1),0);
            var failure=assertInstanceOf(ModeTransitionPrepared.Unavailable.class,result);
            assertInstanceOf(IllegalStateException.class,failure.cause().orElseThrow());
            assertEquals(before.seenSequence()+1,s.budget.snapshot().seenSequence());
            assertEquals(0,s.budget.snapshot().distanceCredit());
            assertEquals(s.accepted.placement().bounds(),box(s.actor));assertEquals(FreeOwnerTransaction.InstallationStage.NONE,s.tx.installationStage());
            s.tx.close();assertEquals(0,s.budget.snapshot().distanceCredit());assertFalse(s.budget.snapshot().pending());
        }
    }

    @Test void cardinalPreludeRejectsAChangedOwnerBudgetClockBeforeCapture() {
        try (var s = scene(8.5, true)) {
            var prelude = ModeTransitionPrepared.prepareCardinalSource(s.tx, s.request, 0);
            var before = s.budget.snapshot();
            long calls = s.tx.queries().calls();
            var result = ModeTransitionPrepared.prepareCardinalToFree(prelude,
                    s.accepted.worldVelocity(), null, 1);
            var unavailable = assertInstanceOf(ModeTransitionPrepared.Unavailable.class, result);
            assertTrue(unavailable.reason().contains("owner budget clock changed"), unavailable::toString);
            assertEquals(before, s.budget.snapshot());
            assertEquals(calls, s.tx.queries().calls());
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    @Test void actualCardinalToFreeMovementFinalizerJoinsInstalledFootAndProducer() {
        try (var s = scene(8.5, true)) {
            s.put(8,68,8);
            assertInstanceOf(PlayerBodyShape.Cardinal.class, s.accepted.placement().shape());
            var result = ModeTransitionPrepared.prepareCardinalToFree(s.tx, s.request,
                    s.accepted.worldVelocity(), 0);
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class, result, result::toString);
            assertNotNull(prepared.movement(), "integrated velocity must run the real conversion movement path");
            var oldMovementEndpoint = CertifiedReferenceEndpoint.from(s.tx,
                    CapsulePathSource.continueAfter(prepared.movement(), s.tx.numeric()));
            assertNotNull(prepared.movement().source());
            assertThrows(IllegalStateException.class,
                    () -> CertifiedReferenceEndpoint.fromMovement(s.tx, prepared.movement()));

            var reserved = s.budget.snapshot();
            var installed = ModeTransitionFinalizer.install(prepared, 0);
            var applied = assertInstanceOf(ModeTransitionFinalizer.Applied.class, installed, installed::toString);
            assertSame(s.accepted, s.tx.accepted());
            assertInstanceOf(PlayerBodyShape.FreeCapsule.class, applied.snapshot().placement().shape());
            assertFalse(oldMovementEndpoint.current());
            assertThrows(IllegalStateException.class, () -> CapsuleFootContacts.window(oldMovementEndpoint, 0));
            assertInstalledSupportJoin(s, prepared);
            assertEquals(reserved.seenSequence(), s.budget.snapshot().seenSequence());
            assertEquals(reserved.distanceCredit(), s.budget.snapshot().distanceCredit());
        }
    }

    private static void assertInstalledSupportJoin(Scene s, ModeTransitionPrepared.Prepared prepared) {
        var endpoint = s.tx.installedFreeEndpoint();
        assertSame(prepared.targetPose(), endpoint.pose());
        assertSame(prepared.lineage().start(), endpoint.pose());
        assertSame(prepared.target(), endpoint.owner().placement());
        var ledgerBefore = s.budget.snapshot();
        var window = CapsuleFootContacts.window(endpoint, 0);
        var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(s.tx, window, CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                        ModeTransitionPrepared.SCAN, s.tx.resources().environmentObservationWork()));
        var complete = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                CapsuleSupportObservation.observe(endpoint, capture, window, 0, FreeOwnerTransaction.ACCURACY));
        assertFalse(complete.supports().isEmpty(), () -> InstalledSupportDiagnostics.describe(endpoint, complete, capture));
        assertTrue(complete.matches(endpoint.owner(), endpoint.pose(), capture, window, s.tx.queries(), s.tx.numeric()));
        boolean joinedStone = false;
        for (var support : complete.supports()) {
            var producer = capture.blockProducer(endpoint, complete, support.ordinal());
            if (producer.isPresent() && producer.orElseThrow().cell().equals(new BlockPos(8,68,8))) {
                assertSame(Blocks.STONE.getDefaultState(), producer.orElseThrow().state());
                joinedStone = true;
            }
        }
        assertTrue(joinedStone, "same installed capture must join actual floor producer ordinal");
        assertFalse(complete.matches(s.accepted, endpoint.pose(), capture, window, s.tx.queries(), s.tx.numeric()));
        assertEquals(ledgerBefore, s.budget.snapshot(), "support observation cannot spend/refund movement");
        endpoint.requireCurrent();
    }

    // C44 regression source, NOT_RUN: recovery uses the real owner/collision capture seam.
    @Test void realOverlapPreparesAnOutwardPhaseWithoutChangingTheActor() {
        try (var s = scene()) {
            s.put(8, 70, 8);
            var before = WorldCollisionCandidates.ActorGeometry.read(s.actor);
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class,
                    CapsuleDepenetrationPrepared.prepare(s.tx).orElseThrow());
            assertTrue(prepared.recovery());
            assertTrue(prepared.matches(s.tx, null));
            assertNotEquals(s.accepted.placement().center(), prepared.target().center());
            assertEquals(s.accepted.placement().attitude(), prepared.target().attitude());
            assertEquals(before, WorldCollisionCandidates.ActorGeometry.read(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    @Test void multipleInitialObstaclesMustShareOneCertifiedOutwardDirection() {
        try (var s = scene(8.875)) {
            s.put(8, 70, 8); s.put(9, 70, 8);
            var prepared = assertInstanceOf(ModeTransitionPrepared.Prepared.class,
                    CapsuleDepenetrationPrepared.prepare(s.tx).orElseThrow());
            // Opposite x half-spaces forbid crossing either block: use a common z exit.
            assertEquals(s.accepted.placement().center().x(), prepared.target().center().x());
            assertNotEquals(s.accepted.placement().center().z(), prepared.target().center().z());
            assertTrue(prepared.matches(s.tx, null));
        }
    }

    @Test void obstructedConservativeExitsReturnExplicitFailureWithoutNativeMutation() {
        try (var s = scene()) {
            for (int x = 7; x <= 9; x++) for (int y = 69; y <= 71; y++)
                for (int z = 7; z <= 9; z++) s.put(x, y, z);
            var before = WorldCollisionCandidates.ActorGeometry.read(s.actor);
            var failed = assertInstanceOf(ModeTransitionPrepared.Unavailable.class,
                    CapsuleDepenetrationPrepared.prepare(s.tx).orElseThrow());
            assertTrue(failed.reason().contains("depenetration"));
            assertEquals(before, WorldCollisionCandidates.ActorGeometry.read(s.actor));
            assertEquals(FreeOwnerTransaction.InstallationStage.NONE, s.tx.installationStage());
        }
    }

    private Scene scene() { return scene(8.5); }
    private Scene scene(double centerX) { return scene(centerX, false); }
    private Scene scene(double centerX, boolean cardinal) {
        var fixture=BootWorldFixture.open(directory.resolve(UUID.randomUUID().toString()),UUID.randomUUID(),true);
        try {
            EntityPlayerMP actor;
            if (cardinal) {
                // This component scene tests the ordinary contained path, not an independent body producer.
                fixture.prepareServerPlayerSpawn();
                actor = new EntityPlayerMP(fixture.server(), fixture.world(),
                        new GameProfile(UUID.randomUUID(), "contained-scene"), new PlayerInteractionManager(fixture.world()));
                assertFalse(actor instanceof NativeBodyProfileProducer);
                new NetHandlerPlayServer(fixture.server(), new NetworkManager(EnumPacketDirection.SERVERBOUND), actor);
            } else actor = fixture.serverPlayer(UUID.randomUUID());
            var channel=new EmbeddedChannel(actor.connection.netManager);
            PlayerPhysicsInboundConnections.onServerConnectedEvent(new FMLNetworkEvent.ServerConnectionFromClientEvent(actor.connection.netManager));
            var dims=new PlayerBodyShape.Dimensions(.5,2,1.75);
            var placement=new PlayerBodyPlacement(new Vec3d(centerX,70,8.5),Rotation3d.identity(),(cardinal ? new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dims) : new PlayerBodyShape.FreeCapsule(dims)));
            actor.width=.5F;actor.height=2;actor.eyeHeight=1.75F;
            var b=placement.bounds();var t=placement.transport();
            actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
            actor.posX=t.x();actor.posY=t.y();actor.posZ=t.z();actor.motionX=-.4;actor.motionZ=.1;
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),0,1),7,placement,new Vec3d(-8,0,2));
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(4,65,4),new Vec3d(13,76,13)),World.MAX_ENTITY_RADIUS,
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,ModeTransitionPrepared.SCAN));
            // Install neighboring empty columns without pretending one giant query fits the production scan bound.
            for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) {
                var point=new Vec3d(x*16+8,70,z*16+8);
                fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(point,point),World.MAX_ENTITY_RADIUS,
                        CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,ModeTransitionPrepared.SCAN));
            }
            var travel=new PlayerPhysicsStep.TravelState();var budget=new BoostMovementBudget(70,0,new Vec3(t.x(),t.y(),t.z()));
            var current=new AtomicBoolean(true);var store=new FreeTickResources.Store();
            var tx=new FreeOwnerTransaction(actor,accepted,travel,budget,current::get,travel,store.acquire(travel,fixture.world(),accepted.scope(),0));
            var connection=PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(actor.connection.netManager,actor.connection,EnumPacketDirection.SERVERBOUND).orElseThrow();
            var handle=PlayerPhysicsInboundConnections.INSTANCE.openScopeForOwner(connection,accepted.scope()).orElseThrow();
            var q=new Rotation3d(0,Math.sin(.2),0,Math.cos(.2));
            var request=new PlayerAttitudeRequest(4,accepted.scope(),1,0,fixture.world(),connection,handle,0,q);
            var scene=new Scene(fixture,actor,accepted,budget,tx,current,channel,request,travel);
            for(int y=69;y<=71;y++)scene.put(7,y,8);
            return scene;
        }catch(RuntimeException|Error failure){fixture.close();throw failure;}
    }
    private record Scene(BootWorldFixture fixture,EntityPlayerMP actor,PlayerPhysicalSnapshot accepted,
            BoostMovementBudget budget,FreeOwnerTransaction tx,AtomicBoolean current,EmbeddedChannel channel,PlayerAttitudeRequest request,PlayerPhysicsStep.TravelState travel) implements AutoCloseable {
        ModeTransitionPrepared.Prepared prepare(Vec3d delta,ModeTransitionPrepared.TrustedResizeObservation resize) {
            var result=ModeTransitionPrepared.prepareFreeTick(tx,request,resize,delta,0);
            return assertInstanceOf(ModeTransitionPrepared.Prepared.class,result,()->result.toString());
        }
        void put(int x,int y,int z){fixture.world().getChunkProvider().getLoadedChunk(x>>4,z>>4).setBlockState(new BlockPos(x,y,z),Blocks.STONE.getDefaultState());}
        public void close(){try{tx.close();}finally{PlayerPhysicsInboundConnections.onServerDisconnectedEvent(new FMLNetworkEvent.ServerDisconnectionFromClientEvent(actor.connection.netManager));channel.finishAndReleaseAll();try { if (!(actor instanceof NativeBodyProfileProducer)) actor.getAdvancements().dispose(); } finally { fixture.close(); }}}
    }
    private static Aabb box(EntityPlayerMP actor){var b=actor.getEntityBoundingBox();return new Aabb(new Vec3d(b.minX,b.minY,b.minZ),new Vec3d(b.maxX,b.maxY,b.maxZ));}
}
