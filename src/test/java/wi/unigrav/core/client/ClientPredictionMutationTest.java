package wi.unigrav.core.client;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.*;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ClientPredictionMutationTest {
    private static final Vec3d ZERO=new Vec3d(0,0,0);
    private static final class Actor extends EntityPlayer {
        Runnable callback;
        Runnable spectatorCallback;
        AxisAlignedBB tail;
        RuntimeException failure;
        Actor(World world) { super(world,new GameProfile(UUID.randomUUID(),"prediction-mutation")); }
        @Override public boolean isSpectator(){
            var cue=spectatorCallback; spectatorCallback=null;
            if(cue!=null) cue.run();
            return false;
        }
        @Override public boolean isCreative(){return false;}
        @Override public void setEntityBoundingBox(AxisAlignedBB bounds) {
            super.setEntityBoundingBox(bounds);
            var cue=callback; callback=null;
            if(cue!=null) cue.run();
            if(tail!=null) super.setEntityBoundingBox(tail);
            if(failure!=null) {var cause=failure;failure=null;throw cause;}
        }
    }
    private static PlayerPhysicalSnapshot pose(Actor actor,long revision,double x, double motion) {
        var frame=CardinalFrame.Y_POS;
        var shape=new PlayerBodyShape.Cardinal(frame,new PlayerBodyShape.Dimensions(.6F,1.8F,1.62F));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,412),actor.dimension,1),revision,
                new PlayerBodyPlacement(new Vec3d(x,70,8),frame.attitude(),shape),new Vec3d(motion,0,0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(true,true,true,7)));
    }
    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicalSnapshot pose,PlayerPhysicsProtocol.Kind kind,long sequence) {
        return new PlayerPhysicsProtocol.Packet(kind,pose.scope(),sequence,1,Optional.empty(),Optional.of(pose),Optional.empty());
    }
    private static PlayerPhysicsClientSession session(Actor actor,PlayerPhysicalSnapshot authority) {
        var session=new PlayerPhysicsClientSession(snapshot->PlayerPhysicsClientMirror.install(actor,snapshot),()->{},()->{});
        assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,authority.scope(),0,1,
                Optional.empty(),Optional.empty(),Optional.empty())));
        session.prepare(new CPacketPlayer.Position(0,0,0,false),ZERO,0,0,false,Optional.empty(),ignored->{});
        assertTrue(new PlayerPhysicsClientInstallationTestSupport(session,actor).install(packet(authority,PlayerPhysicsProtocol.Kind.OWNED,1)));
        return session;
    }
    @Test void nativeRestorerEndingItsOwnLeaseDoesNotManufactureMirrorRejection(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 501), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            session.predictedPoseRestoration(() -> { session.reset(); return false; });
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> actor.posX = 101));
            assertEquals(101, actor.posX); assertFalse(session.owns());
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void deadBeforeNativeCallbackNeverWritesAnAcceptedMirror(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 502), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            actor.isDead = true;
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> actor.posX = 102));
            assertEquals(102, actor.posX); assertFalse(session.installationFenceActive());
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void nestedPoseDeathRunsBothOriginalsOnceAndDoesNotRestoreDeadGeometry(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 503), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> {
                calls.incrementAndGet();
                session.runNativePose(actor, () -> { calls.incrementAndGet(); actor.isDead = true; actor.posX = 103; });
                actor.posX = 104;
            }));
            assertEquals(2, calls.get()); assertEquals(104, actor.posX);
            assertFalse(session.installationFenceActive()); assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void originalFailureRemainsPrimaryWhenItsLeaseEnds(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 504), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var original = new AssertionError("original native callback");
            session.predictedPoseRestoration(() -> { session.reset(); return false; });
            assertSame(original, assertThrows(AssertionError.class,
                    () -> session.runNativePose(actor, () -> { throw original; })));
            assertEquals(0, original.getSuppressed().length); assertFalse(session.owns());
        }
    }
    @Test void liveSameLeaseFalseStillFencesAndPreservesOriginalAsPrimary(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 505), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var original = new AssertionError("original native callback");
            session.predictedPoseRestoration(() -> false);
            var result = assertThrows(PlayerPhysicsClientSession.NativePoseRestorationFailure.class,
                    () -> session.runNativePose(actor, () -> { throw original; }));
            assertSame(original, result.getCause()); assertEquals(1, original.getSuppressed().length);
            assertInstanceOf(IllegalStateException.class, original.getSuppressed()[0]);
            assertTrue(session.installationFenceActive()); assertTrue(result.current(session));
        }
    }
    @Test void newStateDuringNativeCallbackKeepsSuccessorGeometryAndRestorer(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 506), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var oldCalls = new java.util.concurrent.atomic.AtomicInteger();
            var newCalls = new java.util.concurrent.atomic.AtomicInteger();
            session.predictedPoseRestoration(() -> { oldCalls.incrementAndGet(); return false; });
            var newer = pose(actor, 2, 40, 17);
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> {
                assertTrue(new PlayerPhysicsClientInstallationTestSupport(session, actor)
                        .install(packet(newer, PlayerPhysicsProtocol.Kind.STATE, 2)));
                session.predictedPoseRestoration(() -> { newCalls.incrementAndGet(); return true; });
            }));
            assertEquals(40, actor.posX); assertEquals(17 * .05, actor.motionX);
            assertEquals(0, oldCalls.get()); assertEquals(0, newCalls.get());
            session.runNativePose(actor, () -> {});
            assertEquals(1, newCalls.get()); assertFalse(session.installationFenceActive());
        }
    }
    @Test void teleportOrWorldChangeDuringNativeCallbackCannotWriteOldMirror(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 507), true)) {
            for (boolean teleport : new boolean[] { false, true }) {
                var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                session.predictedPoseRestoration(() -> { calls.incrementAndGet(); return false; });
                assertDoesNotThrow(() -> session.runNativePose(actor, () -> {
                    actor.posX = 105;
                    if (teleport) session.nativeCorrection(8); else actor.world = null;
                }));
                assertEquals(105, actor.posX); assertEquals(0, calls.get());
                assertFalse(session.installationFenceActive()); assertTrue(session.recoveryRequest().isEmpty());
                actor.world = fixture.world();
            }
        }
    }
    @Test void ownerLeaseEndsForRespawnIdentityEvenWithSameSessionTuple(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 508), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var currentActor = new AtomicReference<EntityPlayer>(actor);
            var lease = session.nativePoseLease(actor, () -> currentActor.get() == actor);
            var successor = fixture.serverPlayer(actor.getUniqueID()); successor.posX = 600;
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> {
                actor.posX = 106; currentActor.set(successor);
            }, lease));
            assertEquals(106, actor.posX); assertEquals(600, successor.posX);
            assertFalse(session.installationFenceActive()); assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void originalFailureKeepsRealRestoreThrowSuppressedAfterLeaseEnds(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 509), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var original = new AssertionError("original native callback X");
            var restore = new IllegalStateException("actual restore callback Y");
            session.predictedPoseRestoration(() -> { session.reset(); throw restore; });
            assertSame(original, assertThrows(AssertionError.class,
                    () -> session.runNativePose(actor, () -> { throw original; })));
            assertArrayEquals(new Throwable[] { restore }, original.getSuppressed());
            assertFalse(session.owns()); assertFalse(session.installationFenceActive());
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(strings = { "reset", "state", "death" })
    void finalKinematicsGetterCannotAuthorizeOldRestorer(String change, @TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 510), true)) {
            var actor = new Actor(fixture.world()); var session = session(actor, pose(actor, 1, 8, 2));
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            session.predictedPoseRestoration(() -> { calls.incrementAndGet(); actor.posX = 999; return false; });
            var newer = pose(actor, 2, 40, 17);
            assertDoesNotThrow(() -> session.runNativePose(actor, () -> {
                actor.posX = 107;
                actor.spectatorCallback = () -> {
                    switch (change) {
                        case "reset" -> session.reset();
                        case "state" -> assertTrue(new PlayerPhysicsClientInstallationTestSupport(session, actor)
                                .install(packet(newer, PlayerPhysicsProtocol.Kind.STATE, 2)));
                        case "death" -> actor.isDead = true;
                        default -> throw new AssertionError(change);
                    }
                };
            }));
            assertEquals(0, calls.get(), "the last getter ended the exact restoration lease");
            assertEquals("state".equals(change) ? 40 : 107, actor.posX);
            assertFalse(session.installationFenceActive()); assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void projectionAndAnchorStopRemainingAfterTeleportTailWritesOldBounds(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,411),true)) {
            for(boolean anchor:new boolean[]{false,true}) {
                var actor=new Actor(fixture.world());var authority=pose(actor,1,8,2);var session=session(actor,authority);
                var token=session.predictionLifetime();var correction=new AtomicReference<PlayerPhysicsClientSession.PredictionLifetime>();
                var target=anchor?authority:pose(actor,1,20,90);
                var oldTail=new AxisAlignedBB(19,68,7,21,72,9);
                actor.motionX=123; actor.onGround=false;actor.collidedHorizontally=false;actor.collidedVertically=false;actor.fallDistance=3;
                actor.callback=()->{
                    session.nativeCorrection(44);correction.set(session.correctionLifetime());session.quarantinePrediction(correction.get());
                    actor.tail=oldTail;
                    assertTrue(session.drivesMotion(),"correction must retain custody");assertFalse(session.predictionEligible());
                };
                var mutation=new ClientCardinalPredictionOwner.Mutation(actor,target,()->token.current(session)&&session.predictionEligible());
                assertFalse(mutation.apply());assertTrue(mutation.possiblyPartial());
                assertEquals(oldTail,actor.getEntityBoundingBox(),"callback returned before old setter tail wrote bounds");
                assertEquals(123,actor.motionX);assertFalse(actor.onGround);assertFalse(actor.collidedHorizontally);assertEquals(3,actor.fallDistance);
                assertTrue(session.failPrediction(correction.get()));
                var request=session.recoveryRequest().orElseThrow();assertSame(request,session.recoveryRequest().orElseThrow());
                assertEquals(PlayerPhysicsProtocol.Kind.RECOVER,request.kind());
                assertTrue(session.input(new PlayerPhysicsProtocol.Controls(ZERO,new Vec3d(0,0,1),false,false,false,false,1)).isEmpty());
            }
        }
    }
    @Test void setterThrowBeforeBoundsTokenStillReportsPartialAndOriginalCause(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,413),true)) {
            var actor=new Actor(fixture.world());var authority=pose(actor,1,8,2);var session=session(actor,authority);
            var token=session.predictionLifetime();actor.motionX=321;
            var cause=new IllegalStateException("setter after field write");actor.failure=cause;
            var target=pose(actor,1,25,70);
            var mutation=new ClientCardinalPredictionOwner.Mutation(actor,target,()->token.current(session));
            assertFalse(mutation.apply());assertTrue(mutation.possiblyPartial());assertSame(cause,mutation.failure());
            assertEquals(25,actor.posX);assertEquals(321,actor.motionX);
            assertTrue(session.failPrediction(token));assertTrue(session.recoveryRequest().isPresent());
        }
    }
    @Test void callbackNewStateOwnsItsFieldsAndOldTransactionCannotRollbackOrFailIt(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,414),true)) {
            var actor=new Actor(fixture.world());var initial=pose(actor,1,8,2);var session=session(actor,initial);
            var token=session.predictionLifetime();var newer=pose(actor,2,40,17);
            actor.callback=()->assertTrue(new PlayerPhysicsClientInstallationTestSupport(session,actor).install(packet(newer,PlayerPhysicsProtocol.Kind.STATE,2)));
            var mutation=new ClientCardinalPredictionOwner.Mutation(actor,pose(actor,1,20,90),()->token.current(session));
            assertFalse(mutation.apply());assertEquals(40,actor.posX);assertEquals(17*.05,actor.motionX);
            assertFalse(session.failPrediction(token));assertFalse(session.installationFenceActive());
        }
    }
    @Test void secondCorrectionAndResetInvalidateOnlyTheirOwnFailureAuthority(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,415),true)) {
            var actor=new Actor(fixture.world());var authority=pose(actor,1,8,2);var session=session(actor,authority);
            session.nativeCorrection(1);var first=session.correctionLifetime();session.quarantinePrediction(first);
            session.nativeCorrection(2);var second=session.correctionLifetime();
            assertFalse(first.current(session));assertFalse(session.failPrediction(first));
            assertTrue(second.current(session));session.reset();
            assertFalse(second.current(session));assertFalse(session.failPrediction(second));
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void ordinaryTeleportDoesNotCreateRecoveryAndPredicateSessionUsesPredictionRestoration(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,416),true)) {
            var actor=new Actor(fixture.world());var authority=pose(actor,1,8,2);var session=session(actor,authority);
            var target=pose(actor,1,23,11);
            session.predictedPoseRestoration(()->new ClientCardinalPredictionOwner.Mutation(actor,target,session::predictionEligible).apply());
            session.runNativePose(actor,()->actor.posX=100);
            assertEquals(23,actor.posX);assertEquals(11*.05,actor.motionX);
            session.nativeCorrection(7);assertTrue(session.drivesMotion());assertFalse(session.predictionEligible());
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }
    @Test void normalCorrectionIdentitySurvivesActualStateReplacementAndConsumesOnce(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,417),true)) {
            var actor=new Actor(fixture.world());var session=session(actor,pose(actor,1,8,2));
            session.nativeCorrection(7);var identity=session.correctionPredictionIdentity();
            assertTrue(identity.current(session));assertFalse(identity.consume(session));
            for(int revision=2;revision<=3;revision++) {
                var next=pose(actor,revision,30+revision,4);
                var state=new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,next.scope(),revision,1,
                        Optional.empty(),Optional.of(next),Optional.empty(),java.util.OptionalInt.of(7));
                assertTrue(new PlayerPhysicsClientInstallationTestSupport(session,actor).install(state));
                assertTrue(identity.current(session));assertTrue(session.predictionEligible());
                assertTrue(session.recoveryRequest().isEmpty());
            }
            assertTrue(identity.consume(session));assertFalse(identity.current(session));assertFalse(identity.consume(session));
        }
    }
    @Test void correctionIdentityCannotSurviveNewTeleportQuarantineOrReset(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,418),true)) {
            var actor=new Actor(fixture.world());var session=session(actor,pose(actor,1,8,2));
            session.nativeCorrection(7);var first=session.correctionPredictionIdentity();
            session.nativeCorrection(8);assertFalse(first.current(session));
            var second=session.correctionPredictionIdentity();session.quarantinePrediction(session.correctionLifetime());
            assertFalse(second.current(session));assertThrows(IllegalStateException.class,session::correctionPredictionIdentity);
            session.reset();assertFalse(second.current(session));
        }
    }

    @Test void legacyRetirementDoesNotInvalidateHealthyCorrectionIdentity(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 419), true)) {
            var actor = new Actor(fixture.world()); var authority = pose(actor, 1, 8, 2);
            var retirements = new java.util.concurrent.atomic.AtomicInteger();
            var session = new PlayerPhysicsClientSession(snapshot -> PlayerPhysicsClientMirror.install(actor, snapshot),
                    retirements::incrementAndGet, () -> { });
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, authority.scope(), 0, 1,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            session.prepare(new CPacketPlayer.Position(0, 0, 0, false), ZERO, 0, 0, false, Optional.empty(), ignored -> { });
            assertTrue(session.receive(packet(authority, PlayerPhysicsProtocol.Kind.OWNED, 1)));
            assertEquals(1, retirements.get());
            session.nativeCorrection(7);
            var first = session.correctionPredictionIdentity(); assertTrue(first.current(session));
            var next = pose(actor, 2, 9, 2);
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, next.scope(), 2, 1,
                    Optional.empty(), Optional.of(next), Optional.empty(), java.util.OptionalInt.of(7))));
            assertTrue(first.current(session)); assertTrue(first.consume(session));
            assertEquals(1, retirements.get(), "a correction cannot retire legacy writers again");
            session.nativeCorrection(8); var second = session.correctionPredictionIdentity();
            session.nativeCorrection(9); assertFalse(second.current(session));
            var third = session.correctionPredictionIdentity();
            var later = new PlayerPhysicalSnapshot.Scope(authority.scope().playerId(), authority.scope().sessionId(),
                    authority.scope().dimension(), authority.scope().worldEpoch() + 1);
            assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, later, 3, 2,
                    Optional.empty(), Optional.empty(), Optional.empty())));
            assertFalse(third.current(session));
            assertThrows(IllegalStateException.class, session::correctionPredictionIdentity);
            session.reset(); assertFalse(third.current(session)); assertEquals(1, retirements.get());
        }
    }


}
