package wi.unigrav.core.player;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedPredictionTimelineTest {
    @Test void referencePausePreservesTransactionAndDropsOnlyTheOldMappingWork() {
        for(boolean anchored:new boolean[]{false,true}) {
            var timeline=timeline();
            if(anchored) {
                assertTrue(timeline.accept(context(),input(3,20,1),100));
                var frozen=timeline.freeze(context(),9,101,20,controls(true)).orElseThrow();
                timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,10).orElseThrow();
                assertTrue(timeline.accept(context(),input(4,21,1),102));
                timeline.freeze(context(),10,102,20,controls(true)).orElseThrow();
                assertTrue(timeline.hasInFlight());
            }
            assertTrue(timeline.suspendReference(context()));
            var paused=assertInstanceOf(PlayerPhysicsProtocol.PredictionSuspended.class,timeline.phase(context(),10,0));
            assertEquals(PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_REFERENCE,paused.origin());
            assertTrue(timeline.suspendReference(context()));assertTrue(timeline.suspendMobility(context()));
            assertEquals(paused,timeline.phase(context(),10,0));
            assertTrue(timeline.boundary(context()).isEmpty());assertFalse(timeline.hasInFlight());
            assertFalse(timeline.accept(context(),input(4,21,1),102));
            var wrong=new PlayerPhysicsProtocol.PredictionRequest(paused.requestId()+1,BOOTSTRAP,
                    PlayerPhysicsProtocol.PredictionRequestAction.REARM,Optional.empty(),paused.origin());
            assertFalse(timeline.request(context(),wrong));
            var exact=new PlayerPhysicsProtocol.PredictionRequest(paused.requestId(),BOOTSTRAP,
                    PlayerPhysicsProtocol.PredictionRequestAction.REARM,Optional.empty(),paused.origin());
            assertTrue(timeline.request(context(),exact));assertTrue(timeline.request(context(),exact));
            timeline.begin(context(),new PlayerPhysicsProtocol.PredictionBootstrap(2,0));
            assertFalse(timeline.request(context(),exact));assertFalse(timeline.accept(context(),input(5,22,1),103));
        }
    }

    @Test void referenceMetadataCleanupUsesTheActualProductionIdentity() {
        var frame=CardinalFrame.Y_POS;
        var stable=PlayerBodyReference.State.initial(frame,new Vec3d(0,0,1));
        var blocked=stable.blocked(PlayerBodyReference.Transition.ENVIRONMENT_ADOPTION_BLOCKED,
                frame,PlayerPhysicsProtocol.BodyReferenceReason.TARGET_OCCUPIED);
        assertTrue(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.of(stable),Optional.of(blocked)));
        var manualMetadata=stable.withManual(Optional.of(new PlayerBodyReference.ManualIntent(8,frame,new Vec3d(0,0,1))),
                PlayerBodyReference.Transition.MANUAL_DEFERRED);
        assertTrue(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.of(stable),Optional.of(manualMetadata)));
        assertTrue(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.of(manualMetadata),Optional.of(stable)));
        var environment=stable.accepted(new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                frame,new Vec3d(0,0,1)),PlayerBodyReference.Transition.STABLE);
        assertFalse(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.of(stable),Optional.of(environment)));
        assertTrue(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.of(environment),Optional.of(environment.accepted(
                environment.effective(),PlayerBodyReference.Transition.STABLE))));
        assertFalse(PlayerPhysicsServer.predictionReferenceEquivalent(Optional.empty(),Optional.of(environment)));
    }

    @Test void staleMetadataCannotRetireSuccessorBootstrap() {
        var timeline=timeline();
        var next=new PlayerPhysicsProtocol.PredictionBootstrap(2,0);
        timeline.begin(context(),next);
        assertFalse(timeline.accept(context(),input(3,20,0),100));
        var phase=assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),10,0));
        assertEquals(next,phase.bootstrap(),"late old metadata cannot revoke current bootstrap");
    }
    private final PlayerPhysicalSnapshot.Scope scope = new PlayerPhysicalSnapshot.Scope(new UUID(0,1),new UUID(0,2),0,1);
    private final Object connection=new Object(),owner=new Object(),world=new Object(),scopeHandle=new Object();
    private OwnedPredictionTimeline.Context context() { return new OwnedPredictionTimeline.Context(scope,5,connection,scopeHandle,owner,world,0); }
    private static final PlayerPhysicsProtocol.PredictionBootstrap BOOTSTRAP = new PlayerPhysicsProtocol.PredictionBootstrap(1, 0);
    private OwnedPredictionTimeline timeline() {
        var timeline = new OwnedPredictionTimeline(); timeline.begin(context(), BOOTSTRAP); return timeline;
    }
    private static PlayerPhysicsProtocol.Controls controls(boolean jump) {
        return new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,0),new Vec3d(0,0,1),jump,false,false,false,1);
    }
    private PlayerPhysicsProtocol.Packet input(long sequence,long tick,long edge) { return input(1,sequence,tick,edge); }
    private PlayerPhysicsProtocol.Packet input(long epoch,long sequence,long tick,long edge) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT,scope,sequence,5,Optional.empty(),
                Optional.empty(),Optional.of(controls(edge>0)),OptionalInt.empty(),Optional.empty(),Optional.empty(),
                Optional.empty(),Optional.of(new PlayerPhysicsProtocol.PredictionInput(epoch,20,tick,edge,Optional.of(BOOTSTRAP))),Optional.empty());
    }
    @Test void mobilityPausesAwaitingAndAnchoredWithoutReplacingAnExistingTransaction() {
        for (boolean anchored : new boolean[]{false,true}) {
            var timeline=timeline();
            if (anchored) {
                assertTrue(timeline.accept(context(),input(3,20,0),100));
                var frozen=timeline.freeze(context(),9,101,20,controls(false)).orElseThrow();
                timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,0).orElseThrow();
            }
            assertTrue(timeline.suspendMobility(context()));
            var paused=assertInstanceOf(PlayerPhysicsProtocol.PredictionSuspended.class,timeline.phase(context(),10,0));
            assertEquals(PlayerPhysicsProtocol.PredictionSuspensionOrigin.SERVER_MOBILITY,paused.origin());
            assertEquals(BOOTSTRAP,paused.bootstrap());
            assertTrue(timeline.suspendMobility(context()));
            assertEquals(paused,timeline.phase(context(),10,0));
            var wrong=new PlayerPhysicsProtocol.PredictionRequest(paused.requestId()+1,BOOTSTRAP,
                    PlayerPhysicsProtocol.PredictionRequestAction.REARM,Optional.empty(),paused.origin());
            assertFalse(timeline.request(context(),wrong));
            var rearm=new PlayerPhysicsProtocol.PredictionRequest(paused.requestId(),BOOTSTRAP,
                    PlayerPhysicsProtocol.PredictionRequestAction.REARM,Optional.empty(),paused.origin());
            assertTrue(timeline.request(context(),rearm));
            var next=new PlayerPhysicsProtocol.PredictionBootstrap(2,0); timeline.begin(context(),next);
            assertFalse(timeline.request(context(),rearm));
            assertEquals(next,assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),11,0)).bootstrap());
            timeline.retire();assertFalse(timeline.suspendMobility(context()),"a retired bootstrap cannot be revived by mobility");
        }
        var timeline=timeline();
        var refusal=new PlayerPhysicsProtocol.PredictionRefusal(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT);
        var original=new PlayerPhysicsProtocol.PredictionRequest(7,BOOTSTRAP,PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,refusal);
        assertTrue(timeline.request(context(),original));assertTrue(timeline.suspendMobility(context()));
        var unchanged=assertInstanceOf(PlayerPhysicsProtocol.PredictionSuspended.class,timeline.phase(context(),10,0));
        assertEquals(original.origin(),unchanged.origin());assertEquals(original.requestId(),unchanged.requestId());
    }

    @Test void suspensionAndRearmAreExactIdempotentAndDoNotReopenHistory() {
        var timeline=timeline();assertTrue(timeline.accept(context(),input(3,20,1),100));
        var refusal=new PlayerPhysicsProtocol.PredictionRefusal(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,PlayerPhysicsProtocol.PredictionRefusalStage.ENDPOINT);
        var request=new PlayerPhysicsProtocol.PredictionRequest(7,BOOTSTRAP,PlayerPhysicsProtocol.PredictionRequestAction.SUSPEND,refusal);
        assertTrue(timeline.request(context(),request));assertTrue(timeline.request(context(),request));
        assertFalse(timeline.active());assertTrue(timeline.boundary(context()).isEmpty());
        assertEquals(7,assertInstanceOf(PlayerPhysicsProtocol.PredictionSuspended.class,timeline.phase(context(),10,3)).requestId());
        assertFalse(timeline.request(context(),new PlayerPhysicsProtocol.PredictionRequest(8,BOOTSTRAP,PlayerPhysicsProtocol.PredictionRequestAction.REARM,refusal)));
        assertTrue(timeline.request(context(),new PlayerPhysicsProtocol.PredictionRequest(7,BOOTSTRAP,PlayerPhysicsProtocol.PredictionRequestAction.REARM,refusal)));
        assertTrue(timeline.rearmRequested());
        var next=new PlayerPhysicsProtocol.PredictionBootstrap(2,0);timeline.begin(context(),next);
        assertFalse(timeline.request(context(),request));assertFalse(timeline.accept(context(),input(4,21,1),101));
        assertEquals(next,assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),11,3)).bootstrap());
    }

    @Test void fullFirstPayloadSurvivesBurstAndFirstAnchorHasCorrectEndSlot() {
        var timeline=timeline();var first=input(3,20,1);var next=input(4,21,1);
        assertTrue(timeline.accept(context(),first,100));assertTrue(timeline.accept(context(),next,100));
        var frozen=timeline.freeze(context(),9,101,20,controls(false)).orElseThrow();
        assertEquals(first,frozen.packet());assertEquals(first.controls().orElseThrow(),frozen.controls());
        var boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,10).orElseThrow();
        assertEquals(0,boundary.anchorServerSlot());assertEquals(1,boundary.serverSlot());
        assertEquals(20,PlayerPredictionTiming.resolvedClientTick(boundary));assertEquals(3,boundary.heldInputSequence());
        assertEquals(9,boundary.preSnapshotRevision());assertEquals(PlayerPhysicsProtocol.JumpDecision.none(),boundary.jumpDecision());
        frozen=timeline.freeze(context(),10,102,20,controls(false)).orElseThrow();
        assertEquals(next,frozen.packet());assertEquals(20,frozen.jumpOriginClientTick());
    }
    @Test void repeatedHeldPhysicsDoesNotAcknowledgeFutureClientInputs() {
        var timeline=timeline();timeline.accept(context(),input(3,20,0),100);
        for(int i=1;i<=3;i++) {
            var frozen=timeline.freeze(context(),9,100+i,20,controls(false)).orElseThrow();
            var boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.DEFERRED,0).orElseThrow();
            assertEquals(3,boundary.heldInputSequence());assertEquals(20,PlayerPredictionTiming.resolvedClientTick(boundary));
            assertEquals(1,boundary.serverSlot());
            assertEquals(PlayerPhysicsProtocol.StepOutcome.DEFERRED,boundary.stepOutcome());
            assertThrows(IllegalStateException.class,()->timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,0));
        }
    }
    @Test void burstPreservesEveryInputAndNeutralReleaseInOrder() {
        var timeline=timeline();
        assertTrue(timeline.accept(context(),input(3,20,1),100));
        assertTrue(timeline.accept(context(),input(5,21,1),100));
        assertTrue(timeline.accept(context(),input(8,22,0),100));
        for (int i=0;i<3;i++) {
            var frozen=timeline.freeze(context(),9+i,101+i,20,controls(false)).orElseThrow();
            assertTrue(frozen.consumesInput());
            assertEquals(20+i,frozen.packet().predictionInput().orElseThrow().clientTick());
            assertEquals(i<2,frozen.controls().jump());
            if(i<2) assertEquals(20,frozen.jumpOriginClientTick());
            var boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,0).orElseThrow();
            assertEquals(20+i,PlayerPredictionTiming.resolvedClientTick(boundary));
        }
        var held=timeline.freeze(context(),12,104,20,controls(false)).orElseThrow();
        assertFalse(held.consumesInput());assertFalse(held.controls().jump());
        assertEquals(22,PlayerPredictionTiming.resolvedClientTick(timeline.complete(context(),held,
                PlayerPhysicsProtocol.StepOutcome.APPLIED,0).orElseThrow()));
    }
    @Test void missingSampleCannotCreateAnAffineAcknowledgementAcrossAGap() {
        var timeline=timeline();assertTrue(timeline.accept(context(),input(3,20,0),100));
        assertFalse(timeline.accept(context(),input(5,22,0),100));
        assertTrue(timeline.accept(context(),input(4,21,0),100));
    }
    @Test void timeoutFreezesActualNeutralWithoutInventingNewHeldSequence() {
        var timeline=timeline();timeline.accept(context(),input(3,20,1),100);
        var neutral=controls(false);var frozen=timeline.freeze(context(),9,121,20,neutral).orElseThrow();
        assertSame(neutral,frozen.controls());assertTrue(frozen.packet().controls().orElseThrow().jump());
        assertEquals(3,timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.DEFERRED,0).orElseThrow().heldInputSequence());
    }
    @Test void fencesRetireOldEpochWithoutCompletingOrReplayingIt() {
        var timeline=timeline();timeline.accept(context(),input(3,20,1),100);
        var frozen=timeline.freeze(context(),9,101,20,controls(false)).orElseThrow();timeline.retire();
        assertFalse(timeline.accept(context(),input(4,21,1),102));
        assertThrows(IllegalStateException.class,()->timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,0));
        assertFalse(timeline.accept(context(),input(2,5,20,0),102), "retired bootstrap cannot reopen for a new client epoch");
        assertTrue(timeline.boundary(context()).isEmpty());
    }
    @Test void changedConnectionOwnerAndCorrectionCannotCompleteOldSlot() {
        var timeline=timeline();timeline.accept(context(),input(3,20,0),100);
        var frozen=timeline.freeze(context(),9,101,20,controls(false)).orElseThrow();
        var replaced=new OwnedPredictionTimeline.Context(scope,5,new Object(),scopeHandle,owner,world,0);
        assertTrue(timeline.complete(replaced,frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,0).isEmpty());
        assertFalse(timeline.active());assertFalse(timeline.accept(replaced,input(4,20,0),102));
    }
    @Test void nonFirstOrStaleMetadataNeverCreatesOrReplacesFirstInput() {
        var timeline=timeline();assertFalse(timeline.accept(context(),input(3,21,0),100));
        assertTrue(timeline.accept(context(),input(4,20,2),100));
        assertFalse(timeline.accept(context(),input(5,21,1),100));
        assertFalse(timeline.accept(context(),input(4,21,2),100));
        assertEquals(4,timeline.freeze(context(),9,101,20,controls(false)).orElseThrow().packet().sequence());
    }
    @Test void actualTerminalStaysBoundToFirstOriginAcrossHeldAndNewSample() {
        var timeline=timeline();assertTrue(timeline.accept(context(),input(3,20,1),100));
        var frozen=timeline.freeze(context(),9,101,20,controls(false)).orElseThrow();
        var boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,10,
                PlayerPhysicsProtocol.JumpOutcome.APPLIED).orElseThrow();
        var terminal=new PlayerPhysicsProtocol.JumpDecision(1,20,PlayerPhysicsProtocol.JumpOutcome.APPLIED);
        assertEquals(terminal,boundary.jumpDecision());
        assertTrue(timeline.accept(context(),input(4,21,1),102));
        frozen=timeline.freeze(context(),10,102,20,controls(false)).orElseThrow();
        assertEquals(20,frozen.jumpOriginClientTick());
        boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.APPLIED,9,
                PlayerPhysicsProtocol.JumpOutcome.REJECTED).orElseThrow();
        assertEquals(terminal,boundary.jumpDecision(),"a later sample cannot revive or change a terminal edge");
    }
    @Test void timeoutProducesExpiredOnlyAfterAnActualNeutralProcessedSlot() {
        var timeline=timeline();timeline.accept(context(),input(3,20,1),100);
        var frozen=timeline.freeze(context(),9,121,20,controls(false)).orElseThrow();
        assertTrue(timeline.boundary(context()).isEmpty());
        var boundary=timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.DEFERRED,0,
                PlayerPhysicsProtocol.JumpOutcome.NONE).orElseThrow();
        assertEquals(new PlayerPhysicsProtocol.JumpDecision(1,20,PlayerPhysicsProtocol.JumpOutcome.EXPIRED),boundary.jumpDecision());
        timeline.retire();assertFalse(timeline.accept(context(),input(4,21,1),122));
        assertTrue(timeline.boundary(context()).isEmpty());
    }
    @Test void awaitingIsExplicitZeroCompletedAndRetirementNeverReopensIt() {
        var timeline=timeline();
        assertTrue(timeline.awaitingMetadata());
        var waiting=assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),10,9));
        assertEquals(9,waiting.jumpCooldown());assertEquals(BOOTSTRAP,waiting.bootstrap());
        assertTrue(timeline.accept(context(),input(3,20,1),100));
        assertFalse(timeline.awaitingMetadata());
        assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),10,9));
        var frozen=timeline.freeze(context(),10,101,20,controls(false)).orElseThrow();
        assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),10,9));
        timeline.complete(context(),frozen,PlayerPhysicsProtocol.StepOutcome.DEFERRED,8).orElseThrow();
        assertInstanceOf(PlayerPhysicsProtocol.Anchored.class,timeline.phase(context(),10,8));
        timeline.retire();
        assertEquals(PlayerPhysicsProtocol.PredictionUnavailableReason.RETIRED,
                assertInstanceOf(PlayerPhysicsProtocol.PredictionUnavailable.class,timeline.phase(context(),10,8)).reason());
        assertFalse(timeline.accept(context(),input(2,4,20,0),102));
        assertThrows(IllegalArgumentException.class,()->timeline.begin(context(),BOOTSTRAP));
    }
    @Test void wrongBootstrapEchoCannotRetireEvenBeforeFirstResolvedSlot() {
        var timeline=timeline();var original=input(3,20,0);
        var wrong=new PlayerPhysicsProtocol.Packet(original.kind(),original.scope(),original.sequence(),original.offerSequence(),
                original.application(),original.snapshot(),original.controls(),original.nativeTeleportId(),original.bodyReferenceReceipt(),
                original.bodyReferenceAcknowledgement(),original.bodyReferenceState(),
                Optional.of(new PlayerPhysicsProtocol.PredictionInput(1,20,20,0,Optional.of(new PlayerPhysicsProtocol.PredictionBootstrap(2,0)))),Optional.empty());
        assertFalse(timeline.accept(context(),wrong,100));
        assertEquals(BOOTSTRAP,assertInstanceOf(PlayerPhysicsProtocol.AwaitingFirstResolvedSlot.class,timeline.phase(context(),10,4)).bootstrap());
        assertTrue(timeline.awaitingMetadata());
    }

}
