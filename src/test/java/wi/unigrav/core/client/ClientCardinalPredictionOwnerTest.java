package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ClientCardinalPredictionOwnerTest {
    private static final Vec3d ZERO = new Vec3d(0,0,0);
    private static PlayerPhysicalSnapshot snapshot(net.minecraft.entity.player.EntityPlayer actor) {
        var frame = CardinalFrame.Y_POS;
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F,1.8F,1.62F));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0,876),0,1),1,
                new PlayerBodyPlacement(new Vec3d(8,70,8),frame.attitude(),shape),ZERO,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false,false,false,0)));
    }
    private static CardinalPredictionSeed.Ready seed(PlayerPhysicalSnapshot snapshot) {
        return new CardinalPredictionSeed.Ready(snapshot.revision(),1,CardinalFrame.Y_POS,ZERO,Optional.empty(),false,true,true,true,70);
    }
    private static CardinalPredictionStep.Environment environment() {
        return new CardinalPredictionStep.Environment(ZERO,.91F,.1F,.02F,false,.05F,.42,ZERO,.6,70,true,true,0);
    }
    private static PlayerPhysicsProtocol.Controls controls() {
        return new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,1),new Vec3d(0,0,1),false,false,false,false,1);
    }
    private static final PlayerPhysicsProtocol.PredictionBootstrap BOOTSTRAP = new PlayerPhysicsProtocol.PredictionBootstrap(1, 0);
    private static PlayerPhysicsProtocol.Packet initial(PlayerPhysicalSnapshot pose) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED, pose.scope(), 1, 0, Optional.empty(),
                Optional.of(pose), Optional.empty(), java.util.OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(seed(pose)),
                Optional.of(new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(BOOTSTRAP, pose.revision(), 0)));
    }
    private static void sent(ClientCardinalPredictionOwner owner, PlayerPhysicalSnapshot pose, PlayerPhysicsProtocol.PredictionInput identity) {
        owner.inputSendSucceeded(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, pose.scope(), 1, 0,
                Optional.empty(), Optional.empty(), Optional.of(controls()), java.util.OptionalInt.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(identity), Optional.empty(), Optional.empty()));
    }
    @Test void firstInputMovesActualActorWithoutStateAndAuthorityCanBeProvenAgain(@TempDir Path path) {
        try (var fixture=BootWorldFixture.open(path,new UUID(0,875), true)) {
            var actor=fixture.serverPlayer(new UUID(0,878)); var authority=snapshot(actor); Object lifetime=new Object();
            assertTrue(PlayerPhysicsClientMirror.install(actor,authority));
            var owner=new ClientCardinalPredictionOwner(lifetime,actor,1,initial(authority));
            var input=owner.predict(4,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            assertEquals(4,input.firstClientTick()); assertEquals(4,input.clientTick());
            assertTrue(new ClientCardinalPredictionOwner.Mutation(actor,owner.predictedSnapshot(),owner::active).apply()); assertTrue(actor.posZ>8);
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor,authority));
            assertEquals(1,owner.authority().revision());
            assertTrue(new ClientCardinalPredictionOwner.Mutation(actor,owner.authority(),owner::active).apply());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor,authority));
            assertTrue(new ClientCardinalPredictionOwner.Mutation(actor,owner.predictedSnapshot(),owner::active).apply()); assertTrue(actor.posZ>8);
            assertFalse(owner.current(new Object(),actor));
            owner.retire("teleport"); assertFalse(new ClientCardinalPredictionOwner.Mutation(actor,owner.predictedSnapshot(),owner::active).apply());
            assertTrue(owner.predict(5,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).isEmpty());
        }
    }
    @Test void boundaryReplaysOnlyRemainingSavedEnvironmentAndCauseIsPreserved(@TempDir Path path) {
        try (var fixture=BootWorldFixture.open(path,new UUID(0,877), true)) {
            var actor=fixture.serverPlayer(new UUID(0,878)); var authority=snapshot(actor);
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,2,initial(authority));
            sent(owner, authority, owner.predict(4,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow());
            owner.predict(5,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            var boundary=new PlayerPhysicsProtocol.PredictionBoundary(2,1,10,11,4,1,1,1,0,
                    PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.APPLIED);
            var packet=new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,authority.scope(),4,0,Optional.empty(),
                    Optional.of(authority),Optional.empty(),java.util.OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                    Optional.empty(),Optional.of(boundary),Optional.of(seed(authority)),Optional.of(new PlayerPhysicsProtocol.Anchored(BOOTSTRAP,authority.revision())));
            var count=new java.util.concurrent.atomic.AtomicInteger();
            assertTrue(owner.reconcile(packet,6,box->{count.incrementAndGet();return List.of();},pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertEquals(1,count.get());
            var expected=CardinalPredictionStep.step(new CardinalPredictionStep.State(authority.placement(),ZERO,authority.contacts().orElseThrow(),0),
                    environment(),controls(),CardinalPredictionStep.Mode.NORMAL_DRY,box->List.of(),
                    new CardinalPredictionStep.WorkLedger(PlayerPhysicsStep.cardinalMovementLimits()));
            assertEquals(((CardinalPredictionStep.Predicted)expected).pose(),owner.state());
            RuntimeException cause=new IllegalStateException("world collision fault");
            var failed=assertThrows(IllegalStateException.class,()->owner.predict(6,controls(),environment(),box->{throw cause;},pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertSame(cause,failed.getCause()); assertFalse(owner.active());
        }
    }
}
