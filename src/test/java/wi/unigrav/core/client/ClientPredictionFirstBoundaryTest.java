package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.player.EntityPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Arrival order reproduced by run26: a STATE generated before first metadata arrives after local prediction. */
class ClientPredictionFirstBoundaryTest {
    private static final PlayerPhysicsProtocol.PredictionBootstrap BOOTSTRAP = new PlayerPhysicsProtocol.PredictionBootstrap(1, 0);
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static PlayerPhysicalSnapshot snapshot(EntityPlayer actor, long revision, double z) {
        var frame = CardinalFrame.Y_POS;
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), new UUID(0, 920), 0, 1), revision,
                new PlayerBodyPlacement(new Vec3d(8, 70, z), frame.attitude(), shape), ZERO,
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }
    private static CardinalPredictionSeed.Ready seed(PlayerPhysicalSnapshot snapshot) {
        return new CardinalPredictionSeed.Ready(snapshot.revision(), snapshot.revision(), CardinalFrame.Y_POS, ZERO,
                Optional.empty(), false, true, true, true, 70);
    }
    private static CardinalPredictionStep.Environment environment() {
        return new CardinalPredictionStep.Environment(ZERO, .91F, .1F, .02F, false, .05F, .42, ZERO, .6, 70, true, true, 0);
    }
    private static PlayerPhysicsProtocol.Controls controls() {
        return new PlayerPhysicsProtocol.Controls(new Vec3d(0, 0, 1), new Vec3d(0, 0, 1), false, false, false, false, 1);
    }
    private static PlayerPhysicsProtocol.Packet state(PlayerPhysicalSnapshot snapshot, long sequence,
            OptionalInt teleportId, Optional<PlayerPhysicsProtocol.PredictionBoundary> boundary) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.STATE, snapshot.scope(), sequence, 1,
                Optional.empty(), Optional.of(snapshot), Optional.empty(), teleportId, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), boundary, Optional.of(seed(snapshot)), Optional.of(boundary.isPresent()
                        ? new PlayerPhysicsProtocol.Anchored(BOOTSTRAP, snapshot.revision())
                        : new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(BOOTSTRAP, snapshot.revision(), 0)));
    }
    private static void sent(ClientCardinalPredictionOwner owner, PlayerPhysicalSnapshot pose, long sequence,
            PlayerPhysicsProtocol.PredictionInput identity) {
        sent(owner,pose,sequence,identity,controls());
    }
    private static void sent(ClientCardinalPredictionOwner owner, PlayerPhysicalSnapshot pose, long sequence,
            PlayerPhysicsProtocol.PredictionInput identity, PlayerPhysicsProtocol.Controls controls) {
        owner.inputSendSucceeded(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, pose.scope(), sequence, 1,
                Optional.empty(), Optional.empty(), Optional.of(controls), OptionalInt.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(identity), Optional.empty(), Optional.empty()));
    }
    private static CardinalPredictionStep.State advance(CardinalPredictionStep.State initial) {
        return ((CardinalPredictionStep.Predicted) CardinalPredictionStep.step(initial, environment(), controls(),
                CardinalPredictionStep.Mode.NORMAL_DRY, box -> List.of(),
                new CardinalPredictionStep.WorkLedger(PlayerPhysicsStep.cardinalMovementLimits()))).pose();
    }
    @Test void crossedClientAndServerSuspensionAdoptsOnlySameBootstrapServerTransaction(@TempDir Path path) {
        try(var world=BootWorldFixture.open(path,new UUID(0,934),true)) {
            var actor=world.serverPlayer(new UUID(0,935));
            var initial=snapshot(actor,0,8);
            var previous=new ClientCardinalPredictionOwner(new Object(),actor,1,state(initial,1,OptionalInt.empty(),Optional.empty()));
            var refusal=new PlayerPhysicsProtocol.PredictionRefusal(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT);
            var transaction=new PlayerPhysicsClient.PredictionSuspension(null,previous,7,refusal);
            transaction.jumpHeld=true;
            var notice=new PlayerPhysicsProtocol.PredictionSuspended(1,BOOTSTRAP,24,PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS);
            transaction.acceptReceipt(notice);
            assertTrue(transaction.acknowledged,"matching proved server transaction must end the crossed-suspension deadlock");
            assertEquals(notice.origin(),transaction.suspend.origin());assertEquals(24,transaction.suspend.requestId());
            assertSame(previous,transaction.previous.orElseThrow());assertTrue(transaction.jumpHeld);
            transaction.rearmSent=true;
            assertEquals(PlayerPhysicsProtocol.PredictionRequestAction.REARM,transaction.request().action());
            assertEquals(24,transaction.request().requestId());assertTrue(transaction.request().refusal().isEmpty());
            transaction.acceptReceipt(new PlayerPhysicsProtocol.PredictionSuspended(2,new PlayerPhysicsProtocol.PredictionBootstrap(2,0),25,notice.origin()));
            assertEquals(24,transaction.request().requestId(),"a different bootstrap notification has no authority over this transaction");
        }
    }

    @Test void startupInFlightStateDoesNotRetireFirstPrediction(@TempDir Path path) {
        inFlightStateBeforeFirstBoundary(path, 1, OptionalInt.empty());
    }
    @Test void teleportInFlightStateDoesNotRetireRearmedPrediction(@TempDir Path path) {
        inFlightStateBeforeFirstBoundary(path, 3, OptionalInt.of(2));
    }
    private static void inFlightStateBeforeFirstBoundary(Path path, long epoch, OptionalInt teleportId) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 921), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 922));
            var initial = snapshot(actor, 29, 8);
            var owner = new ClientCardinalPredictionOwner(new Object(), actor, epoch, state(initial, 6, teleportId, Optional.empty()));
            sent(owner, initial, 10, owner.predict(92, controls(), environment(), box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow());
            owner.predict(93, controls(), environment(), box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            // This newer authority was generated without any knowledge of our first metadata INPUT.
            var preAnchor = snapshot(actor, 30, 8.25);
            assertTrue(PlayerPhysicsClientMirror.install(actor, preAnchor));
            var replayed = new AtomicInteger();
            assertTrue(owner.reconcile(state(preAnchor, 7, teleportId, Optional.empty()), 94,
                    box -> List.of(), pose -> { replayed.incrementAndGet(); return ClientCardinalPredictionOwner.EndpointValidation.allowed(); }), "pre-anchor STATE is not a lost current-epoch boundary");
            assertTrue(owner.active()); assertNull(owner.retiredReason());
            assertEquals(2, replayed.get(), "replay all unconfirmed saved slots exactly once");
            var anchoredState = new CardinalPredictionStep.State(preAnchor.placement(), ZERO, preAnchor.contacts().orElseThrow(), 0);
            assertEquals(advance(advance(anchoredState)), owner.state());
            assertSame(preAnchor, owner.authority());
            assertTrue(new ClientCardinalPredictionOwner.Mutation(actor, owner.predictedSnapshot(), owner::active).apply());
            assertTrue(actor.posZ > preAnchor.placement().transport().z());
            var firstBoundary = new PlayerPhysicsProtocol.PredictionBoundary(epoch, 1, 0, 1, 92, 10, 32, 10, 0,
                    PlayerPhysicsProtocol.JumpDecision.none(), PlayerPhysicsProtocol.StepOutcome.APPLIED);
            replayed.set(0);
            var resolvedAuthority = snapshot(actor,33,8.25);
            assertTrue(owner.reconcile(state(resolvedAuthority, 10, teleportId, Optional.of(firstBoundary)), 95,
                    box -> List.of(), pose -> { replayed.incrementAndGet(); return ClientCardinalPredictionOwner.EndpointValidation.allowed(); }));
            assertEquals(1, replayed.get(), "first real boundary confirms slot92 and replays only slot93");
            assertEquals(advance(anchoredState), owner.state());
            assertFalse(owner.reconcile(state(resolvedAuthority, 11, teleportId, Optional.empty()), 96, box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertEquals("Prediction awaiting phase regressed after anchor", owner.retiredReason(), "awaiting after actual mapping remains an error");
        }
    }
    @Test void missingBoundaryAfterActualMappingRemainsFailure(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 923), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 924)); var initial = snapshot(actor, 1, 8);
            var owner = new ClientCardinalPredictionOwner(new Object(), actor, 1, state(initial, 1, OptionalInt.empty(), Optional.empty()));
            sent(owner, initial, 1, owner.predict(4, controls(), environment(), box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow());
            var boundary = new PlayerPhysicsProtocol.PredictionBoundary(1, 1, 0, 1, 4, 1, 1, 1, 0,
                    PlayerPhysicsProtocol.JumpDecision.none(), PlayerPhysicsProtocol.StepOutcome.APPLIED);
            assertTrue(owner.reconcile(state(initial, 2, OptionalInt.empty(), Optional.of(boundary)), 5, box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertFalse(owner.reconcile(state(initial, 3, OptionalInt.empty(), Optional.empty()), 6, box -> List.of(), pose -> ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertEquals("Prediction awaiting phase regressed after anchor", owner.retiredReason());
        }
    }
    private static PlayerPhysicsProtocol.Packet phase(PlayerPhysicsProtocol.Packet packet, PlayerPhysicsProtocol.PredictionPhase phase) {
        return new PlayerPhysicsProtocol.Packet(packet.kind(),packet.scope(),packet.sequence(),packet.offerSequence(),packet.application(),
                packet.snapshot(),packet.controls(),packet.nativeTeleportId(),packet.bodyReferenceReceipt(),packet.bodyReferenceAcknowledgement(),
                packet.bodyReferenceState(),packet.predictionInput(),packet.predictionBoundary(),packet.predictionSeed(),Optional.of(phase));
    }
    @Test void repeatedAwaitingWithoutInputPreservesRealCooldownAndLaterFirstWireIdentity(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,925),true)) {
            var actor=fixture.serverPlayer(new UUID(0,926));var initial=snapshot(actor,1,8);
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,
                    phase(state(initial,1,OptionalInt.empty(),Optional.empty()),new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(BOOTSTRAP,1,9)));
            assertEquals(9,owner.state().jumpTicks());
            var replays=new AtomicInteger();
            for(int revision=2;revision<=3;revision++) {
                var current=snapshot(actor,revision,8+revision);
                assertTrue(owner.reconcile(phase(state(current,revision,OptionalInt.empty(),Optional.empty()),
                        new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(BOOTSTRAP,revision,8)),50,box->List.of(),pose -> { replays.incrementAndGet(); return ClientCardinalPredictionOwner.EndpointValidation.allowed(); }));
                assertEquals(8,owner.state().jumpTicks());assertTrue(owner.active());
            }
            assertEquals(0,replays.get());
            var heldJump=new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,1),new Vec3d(0,0,1),true,false,false,false,1);
            var input=owner.predict(50,heldJump,environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            assertEquals(50,input.firstClientTick());assertEquals(1,input.epoch());sent(owner,owner.authority(),14,input,heldJump);
            assertEquals(7,owner.state().jumpTicks());
            var boundary=new PlayerPhysicsProtocol.PredictionBoundary(1,1,0,1,50,14,3,14,7,
                    PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.DEFERRED);
            assertTrue(owner.reconcile(state(snapshot(actor,4,11),4,OptionalInt.empty(),Optional.of(boundary)),51,
                    box->List.of(),pose -> { replays.incrementAndGet(); return ClientCardinalPredictionOwner.EndpointValidation.allowed(); }));
            assertEquals(0,replays.get());assertEquals(7,owner.state().jumpTicks());assertTrue(owner.active());
        }
    }
    @Test void firstBoundaryCannotChangeWireSequenceEpochTickGenerationOrPreRevision(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,927),true)) {
            for(int wrong=0;wrong<6;wrong++) {
                var actor=fixture.serverPlayer(new UUID(0,930+wrong));var initial=snapshot(actor,10,8);
                var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,state(initial,1,OptionalInt.empty(),Optional.empty()));
                sent(owner,initial,20,owner.predict(4,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow());
                var boundary=new PlayerPhysicsProtocol.PredictionBoundary(wrong==0?2:1,1,0,1,wrong==1?5:4,wrong==2?21:20,
                        wrong==3?9:wrong==4?13:10,wrong==2?21:20,0,PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.APPLIED);
                var packet=state(snapshot(actor,12,8),2,OptionalInt.empty(),Optional.of(boundary));
                if(wrong==5) packet=phase(packet,new PlayerPhysicsProtocol.Anchored(new PlayerPhysicsProtocol.PredictionBootstrap(2,0),12));
                assertFalse(owner.reconcile(packet,5,box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()),"invalid first mapping variant "+wrong);
                assertFalse(owner.active());assertNotNull(owner.retiredReason());
            }
        }
    }
    @Test void duplicateAwaitingIsIdempotentAndNewAwaitingReplaySharesExistingTickLedger(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,937),true)) {
            var actor=fixture.serverPlayer(new UUID(0,938));var initial=snapshot(actor,1,8);
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,state(initial,1,OptionalInt.empty(),Optional.empty()));
            int slots=PlayerPhysicsStep.cardinalMovementLimits().maxQueries()/2+1;
            for(int tick=0;tick<slots;tick++) owner.predict(tick,controls(),environment(),box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()).orElseThrow();
            var first=state(snapshot(actor,2,8),2,OptionalInt.empty(),Optional.empty());var queries=new AtomicInteger();
            assertTrue(owner.reconcile(first,1000,box->{queries.incrementAndGet();return List.of();},pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            int spent=queries.get();assertEquals(slots,spent);
            var retained=owner.state();
            assertTrue(owner.reconcile(first,1000,box->{fail("duplicate must not query or replay");return List.of();},pose->{fail("duplicate endpoint"); return ClientCardinalPredictionOwner.EndpointValidation.allowed();}));
            assertSame(retained,owner.state());assertEquals(spent,queries.get());
            assertFalse(owner.reconcile(state(snapshot(actor,3,8),3,OptionalInt.empty(),Optional.empty()),1000,
                    box->{queries.incrementAndGet();return List.of();},pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()));
            assertEquals(PlayerPhysicsStep.cardinalMovementLimits().maxQueries(),queries.get());
            assertFalse(owner.active());assertNotNull(owner.retiredReason());
        }
    }
    @Test void unavailableAndLegacyMissingPhaseNeverAuthorizeDryPrediction(@TempDir Path path) {
        try(var fixture=BootWorldFixture.open(path,new UUID(0,939),true)) {
            var actor=fixture.serverPlayer(new UUID(0,940));var initial=snapshot(actor,1,8);
            var valid=state(initial,1,OptionalInt.empty(),Optional.empty());
            var legacy=new PlayerPhysicsProtocol.Packet(valid.kind(),valid.scope(),1,1,Optional.empty(),valid.snapshot(),Optional.empty(),
                    OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),valid.predictionSeed());
            assertThrows(IllegalArgumentException.class,()->new ClientCardinalPredictionOwner(new Object(),actor,1,legacy));
            var owner=new ClientCardinalPredictionOwner(new Object(),actor,1,valid);
            var unavailable=phase(state(snapshot(actor,2,8),2,OptionalInt.empty(),Optional.empty()),
                    new PlayerPhysicsProtocol.PredictionUnavailable(2,PlayerPhysicsProtocol.PredictionUnavailableReason.RETIRED));
            assertFalse(owner.reconcile(unavailable,4,box->List.of(),pose->ClientCardinalPredictionOwner.EndpointValidation.allowed()));assertFalse(owner.active());
        }
    }

}
