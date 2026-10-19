package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.*;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PredictionEndpointBoundaryTest {
    @Test void rearmedEpochSharesSameTickWorkAndHeldJumpDoesNotBecomeNewPress(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,2731),true)) {
            var actor=fixture.serverPlayer(new UUID(0,2732));var snapshot=authority(actor);var lifetime=new Object();
            var controls=new PlayerPhysicsProtocol.Controls(ZERO,new Vec3d(0,0,1),true,false,false,false,1);
            var owner=new ClientCardinalPredictionOwner(lifetime,actor,1,packet(snapshot));
            var first=owner.predict(4,controls,environment(),box->java.util.List.of(),endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            assertEquals(1,first.jumpEdgeId());
            owner.retire("expected suspension");
            var next=new ClientCardinalPredictionOwner(lifetime,actor,2,packet(snapshot));next.inheritSuspendedInput(owner,true);
            assertEquals(0,next.predict(5,controls,environment(),box->java.util.List.of(),endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow().jumpEdgeId());
            assertEquals(0,next.predict(6,idle(),environment(),box->java.util.List.of(),endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow().jumpEdgeId());
            assertEquals(1,next.predict(7,controls,environment(),box->java.util.List.of(),endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow().jumpEdgeId());
            int[] queries={0};boolean exhausted=false;
            owner=new ClientCardinalPredictionOwner(lifetime,actor,3,packet(snapshot));
            for(int i=0;i<PlayerPhysicsStep.cardinalMovementLimits().maxQueries()+1;i++) {
                var result=owner.predict(20,idle(),environment(),box->{queries[0]++;return java.util.List.of();},endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed());
                if(result.isEmpty()){exhausted=true;break;}
                owner.retire("suspended");next=new ClientCardinalPredictionOwner(lifetime,actor,i+4,packet(snapshot));
                next.inheritSuspendedInput(owner,false);owner=next;
            }
            assertTrue(exhausted,"new epochs must not mint fresh same-tick query budgets");
            assertEquals(PlayerPhysicsStep.cardinalMovementLimits().maxQueries(),queries[0]);
        }
    }
    @Test void equipmentRefusalCarriesTheActuallyReadItemAndSampleStage(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,2733),true)) {
            var actor=fixture.serverPlayer(new UUID(0,2734));var snapshot=authority(actor);
            actor.setItemStackToSlot(net.minecraft.inventory.EntityEquipmentSlot.FEET,new net.minecraft.item.ItemStack(net.minecraft.init.Items.LEATHER_BOOTS));
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,packet(snapshot));
            var result=assertInstanceOf(CardinalPredictionEnvironmentSampler.Unsupported.class,
                    CardinalPredictionEnvironmentSampler.samplePlayer(actor,fixture.world(),owner.state(),idle(),owner.seed(),false));
            assertEquals(CardinalPredictionSeed.Reason.ACTIVE_BOOTS,result.reason());
            assertEquals(PlayerPhysicsProtocol.PredictionRefusalStage.SAMPLE,result.stage());
            assertEquals("not-applicable; feet=minecraft:leather_boots",result.support());
        }
    }
    private static final Vec3d ZERO = new Vec3d(0,0,0);
    private static final PlayerPhysicsProtocol.PredictionBootstrap BOOTSTRAP = new PlayerPhysicsProtocol.PredictionBootstrap(1,0);
    private static PlayerPhysicsProtocol.Controls idle() {
        return new PlayerPhysicsProtocol.Controls(ZERO,new Vec3d(0,0,1),false,false,false,false,1);
    }
    private static CardinalPredictionStep.Environment environment() {
        return new CardinalPredictionStep.Environment(new Vec3d(0,-32,0),.91F,.1F,.02F,false,.05F,.42,ZERO,.6,70,true,false,0);
    }
    private static PlayerPhysicalSnapshot authority(net.minecraft.entity.player.EntityPlayer actor) {
        var shape=new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,new PlayerBodyShape.Dimensions(.6F,1.8F,1.62F));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,296),0,1),1,
                new PlayerBodyPlacement(new Vec3d(8.5,65.9,8.5),CardinalFrame.Y_POS.attitude(),shape),new Vec3d(0,-1,0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false,false,false,0)));
    }
    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicalSnapshot snapshot) {
        var seed=new CardinalPredictionSeed.Ready(snapshot.revision(),1,CardinalFrame.Y_POS,new Vec3d(0,-32,0),Optional.of(wi.unigrav.core.GravityMode.REPLACE),true,false,true,true,70);
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OWNED,snapshot.scope(),1,0,Optional.empty(),
                Optional.of(snapshot),Optional.empty(),java.util.OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.of(seed),Optional.of(new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(BOOTSTRAP,snapshot.revision(),0)));
    }
    @Test void airborneActualUnsupportedLandingRejectsBeforeSlotCommit(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,290),true)) {
            var actor=fixture.serverPlayer(new UUID(0,291));
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.BEDROCK.getDefaultState()));
            var snapshot=authority(actor);
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,packet(snapshot)); var before=owner.state();
            var sampled=CardinalPredictionEnvironmentSampler.samplePlayer(actor,fixture.world(),before,idle(),owner.seed(),false);
            assertInstanceOf(CardinalPredictionEnvironmentSampler.Sampled.class,sampled,"airborne start does not probe the later support");
            var result=assertDoesNotThrow(()->owner.predict(4,idle(),environment(),box->fixture.world().getCollisionBoxes(actor,box),
                    endpoint->PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->true)));
            assertTrue(result.isEmpty()); assertFalse(owner.active()); assertSame(before,owner.state());
            assertFalse(before.contacts().onGround());
            assertEquals(Optional.of(true),owner.refusal().orElseThrow().refusalGround(),"the actual refused landing scratch is grounded while canonical start is airborne");
            assertEquals(Optional.of(true),owner.refusal().orElseThrow().atStage(PlayerPhysicsProtocol.PredictionRefusalStage.REPLAY).refusalGround());
            assertEquals(snapshot,owner.authority());
        }
    }
    @Test void actualWorldGetterCauseEscapesUnchanged(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,292),true)) {
            var actor=fixture.serverPlayer(new UUID(0,293));
            var start=authority(actor); var grounded=new CardinalPredictionStep.State(start.placement(),ZERO,new PlayerPhysicalSnapshot.Contacts(true,false,false,0),0);
            RuntimeException cause=new IllegalStateException("endpoint-getter-unique-X");
            fixture.onLoadedChunkReadWhen(()->true,()->{throw cause;});
            assertSame(cause,assertThrows(RuntimeException.class,()->PlayerPhysicsClient.predictionEndpoint(fixture.world(),grounded,()->true)));
        }
    }
    @Test void unloadedEndpointAndNormalSurfaceAreTyped(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,294),true)) {
            var actor=fixture.serverPlayer(new UUID(0,295)); var start=authority(actor);
            var grounded=new CardinalPredictionStep.State(start.placement(),ZERO,new PlayerPhysicalSnapshot.Contacts(true,false,false,0),0);
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.STONE.getDefaultState()));
            assertTrue(PlayerPhysicsClient.predictionEndpoint(fixture.world(),grounded,()->true).accepted());
            var shape=start.placement().shape();
            var far=new CardinalPredictionStep.State(new PlayerBodyPlacement(new Vec3d(80.5,65.9,8.5),CardinalFrame.Y_POS.attitude(),shape),ZERO,grounded.contacts(),0);
            var unavailable=assertDoesNotThrow(()->PlayerPhysicsClient.predictionEndpoint(fixture.world(),far,()->true));
            assertFalse(unavailable.accepted()); assertTrue(unavailable.reason().contains("WORLD_UNAVAILABLE"));
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.BEDROCK.getDefaultState()));
            assertInstanceOf(CardinalPredictionEnvironmentSampler.Unsupported.class,
                    CardinalPredictionEnvironmentSampler.samplePlayer(actor,fixture.world(),grounded,idle(),packet(start).predictionSeed().filter(CardinalPredictionSeed.Ready.class::isInstance).map(CardinalPredictionSeed.Ready.class::cast).orElseThrow(),false));
            assertFalse(PlayerPhysicsClient.predictionEndpoint(fixture.world(),grounded,()->true).accepted());
        }
    }
    @Test void endpointCallbackSuccessorLeavesOnlyOldPredictionTerminal(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,297),true)) {
            var actor=fixture.serverPlayer(new UUID(0,298)); var start=authority(actor);
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.STONE.getDefaultState()));
            Object oldLifetime=new Object(), newLifetime=new Object();
            var old=new ClientCardinalPredictionOwner(oldLifetime,actor,1,packet(start));
            var successor=new ClientCardinalPredictionOwner(newLifetime,actor,2,packet(start));
            var installed=new java.util.concurrent.atomic.AtomicReference<>(old);
            int[] queries={0}; var before=old.state();
            var result=old.predict(4,idle(),environment(),box->{queries[0]++;return fixture.world().getCollisionBoxes(actor,box);},endpoint->{
                fixture.onLoadedChunkReadWhen(()->true,()->installed.set(successor));
                return PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->installed.get()==old);
            });
            assertTrue(result.isEmpty()); assertFalse(old.active()); assertTrue(successor.active());
            assertSame(successor,installed.get()); assertSame(before,old.state()); assertTrue(queries[0]>0);
            int spent=queries[0]; assertTrue(old.predict(4,idle(),environment(),box->{queries[0]++;return java.util.List.of();},endpoint->ClientCardinalPredictionOwner.EndpointValidation.allowed()).isEmpty());
            assertEquals(spent,queries[0],"terminal retry does not execute another step or mint a new ledger");
        }
    }
    @Test void secondReplayEndpointRejectsWithoutIntermediatePublication(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,299),true)) {
            var actor=fixture.serverPlayer(new UUID(0,300)); var start=authority(actor);
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.STONE.getDefaultState()));
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,packet(start));
            var first=owner.predict(4,idle(),environment(),box->fixture.world().getCollisionBoxes(actor,box),endpoint->PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->true)).orElseThrow();
            owner.inputSendSucceeded(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT,start.scope(),7,0,Optional.empty(),Optional.empty(),Optional.of(idle()),java.util.OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(first),Optional.empty(),Optional.empty()));
            owner.predict(5,idle(),environment(),box->fixture.world().getCollisionBoxes(actor,box),endpoint->PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->true)).orElseThrow();
            owner.predict(6,idle(),environment(),box->fixture.world().getCollisionBoxes(actor,box),endpoint->PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->true)).orElseThrow();
            var before=owner.state();
            var canonical=new PlayerPhysicalSnapshot(start.scope(),2,start.placement(),start.worldVelocity(),start.contacts());
            var boundary=new PlayerPhysicsProtocol.PredictionBoundary(1,1,0,1,4,7,1,7,0,PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.DEFERRED);
            var checkpoint=new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE,start.scope(),2,0,Optional.empty(),Optional.of(canonical),Optional.empty(),java.util.OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(boundary),packet(canonical).predictionSeed(),Optional.of(new PlayerPhysicsProtocol.Anchored(BOOTSTRAP,2)));
            int[] endpoints={0};
            assertFalse(owner.reconcile(checkpoint,7,box->fixture.world().getCollisionBoxes(actor,box),endpoint->{
                assertSame(before,owner.state(),"scratch replay must not publish intermediate state");
                if(++endpoints[0]==2) assertTrue(endpoint.contacts().onGround(), "N2 endpoint must actually be grounded: " + endpoint + "; boundary=" + boundary);
                if(endpoints[0]==2) assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.BEDROCK.getDefaultState()));
                return PlayerPhysicsClient.predictionEndpoint(fixture.world(),endpoint,()->true);
            }));
            assertEquals(2,endpoints[0]); assertFalse(owner.active()); assertSame(before,owner.state());
            assertEquals(start,owner.authority()); assertSame(canonical,checkpoint.snapshot().orElseThrow());
            assertSame(boundary,checkpoint.predictionBoundary().orElseThrow());
        }
    }

    @Test void rejectedEndpointDoesNotRefundActualStepWork(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,301),true)) {
            var actor=fixture.serverPlayer(new UUID(0,302)); var start=authority(actor);
            assertTrue(fixture.world().setBlockState(new BlockPos(8,64,8),Blocks.BEDROCK.getDefaultState()));
            var ledger=new CardinalPredictionStep.WorkLedger(PlayerPhysicsStep.cardinalMovementLimits());
            var step=assertInstanceOf(CardinalPredictionStep.Predicted.class,CardinalPredictionStep.step(
                    new CardinalPredictionStep.State(start.placement(),start.worldVelocity().multiply(.05),start.contacts().orElseThrow(),0),
                    environment(),idle(),CardinalPredictionStep.Mode.NORMAL_DRY,box->fixture.world().getCollisionBoxes(actor,box),ledger));
            assertTrue(step.pose().contacts().onGround()); var spent=ledger.spent(); assertTrue(spent.queries()>0);
            assertFalse(PlayerPhysicsClient.predictionEndpoint(fixture.world(),step.pose(),()->true).accepted());
            assertEquals(spent,ledger.spent(),"endpoint refusal cannot refund the already executed movement");
            assertFalse(PlayerPhysicsClient.predictionEndpoint(fixture.world(),step.pose(),()->true).accepted());
            assertEquals(spent,ledger.spent());
        }
    }

}
