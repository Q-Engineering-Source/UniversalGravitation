package wi.unigrav.core.player;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import static org.junit.jupiter.api.Assertions.*;
class PlayerPredictionTimingTest {
    private static PlayerPhysicsProtocol.PredictionBoundary boundary(long slot) {
        return new PlayerPhysicsProtocol.PredictionBoundary(1, 1, 100, slot, 20, 3, 8, 3, 0,
                PlayerPhysicsProtocol.JumpDecision.none(), PlayerPhysicsProtocol.StepOutcome.APPLIED);
    }
    private static PlayerPhysicsProtocol.Packet input(long sequence, long tick) {
        var scope = new PlayerPhysicalSnapshot.Scope(new UUID(1,2), new UUID(3,4), 0, 1);
        var controls = new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,0), new Vec3d(0,0,1),false,false,false,false,1);
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.INPUT, scope, sequence, 2,
                Optional.empty(),Optional.empty(),Optional.of(controls),OptionalInt.empty(),Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.of(new PlayerPhysicsProtocol.PredictionInput(1,20,tick,0)),Optional.empty());
    }
    @Test void resolvedTimeIsNotHeldPacketSequence() {
        assertEquals(20, PlayerPredictionTiming.resolvedClientTick(boundary(101)));
        assertEquals(23, PlayerPredictionTiming.resolvedClientTick(boundary(104)));
        var history = new PlayerPredictionTiming.History<String>(1,20,8,8);
        for(long tick=20;tick<=25;tick++) history.append(tick,"state"+tick);
        var replay = assertInstanceOf(PlayerPredictionTiming.Ready.class,history.after(boundary(104)));
        assertEquals(2,replay.slots().size());
        assertEquals(24,((PlayerPredictionTiming.Slot<?>)replay.slots().getFirst()).clientTick());
        assertEquals(replay,history.after(boundary(104)));
    }
    @Test void firstPacketCannotBeOverwrittenByLatestBeforeFreeze() {
        var latch=new PlayerPredictionTiming.FirstInputLatch();
        var first=input(3,20); var later=input(4,21);
        latch.accept(first);latch.accept(later);
        assertEquals(first,latch.freeze().orElseThrow());
        latch.firstSlotResolved();assertEquals(later,latch.freeze().orElseThrow());
        assertThrows(IllegalArgumentException.class,()->latch.accept(input(5,21)));
        latch.retire();assertThrows(IllegalStateException.class,()->latch.accept(input(6,22)));
    }
    @Test void newerPhysicsWithSameConsumedInputKeepsPendingHistory() {
        var history=new PlayerPredictionTiming.History<String>(1,20,8,8);
        history.append(20,"moving");history.append(21,"released");
        assertEquals(1,assertInstanceOf(PlayerPredictionTiming.Ready.class,history.after(boundary(101))).slots().size());
        var held=new PlayerPhysicsProtocol.PredictionBoundary(1,1,100,101,20,3,8,3,4,
                PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.DEFERRED);
        var replay=assertInstanceOf(PlayerPredictionTiming.Ready.class,history.after(held));
        assertEquals(1,replay.slots().size());
        assertEquals(21,((PlayerPredictionTiming.Slot<?>)replay.slots().getFirst()).clientTick());
    }
    @Test void missingFutureAndRetiredHistoryRequireExplicitRebase() {
        var history=new PlayerPredictionTiming.History<String>(1,20,2,2);
        assertThrows(IllegalArgumentException.class,()->history.append(21,"native"));
        for(long tick=20;tick<=24;tick++) history.append(tick,"ug");
        assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,history.after(boundary(101)));
        assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,history.after(boundary(106)));
        history.retire();assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,history.after(boundary(105)));
    }
    @Test void negativeAndOverflowAndFutureJumpAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new PlayerPhysicsProtocol.PredictionInput(0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new PlayerPhysicsProtocol.PredictionBoundary(1,1,0,3,Long.MAX_VALUE,0,0,0,0,
                PlayerPhysicsProtocol.JumpDecision.none(),PlayerPhysicsProtocol.StepOutcome.APPLIED));
        assertThrows(IllegalArgumentException.class,()->new PlayerPhysicsProtocol.PredictionBoundary(1,1,100,101,20,3,8,3,0,
                new PlayerPhysicsProtocol.JumpDecision(1,21,PlayerPhysicsProtocol.JumpOutcome.APPLIED),PlayerPhysicsProtocol.StepOutcome.APPLIED));
    }
    @Test void terminalJumpIsOnceOnlyAndOldCoveredEdgeCannotReplay() {
        var terminals=new PlayerPredictionTiming.JumpTerminals(1,2);
        var edge=new PlayerPhysicsProtocol.PredictionInput(1,20,21,1);
        assertTrue(terminals.mayReplay(edge,20));
        assertFalse(terminals.mayReplay(edge,21));
        var rejected=new PlayerPhysicsProtocol.JumpDecision(1,21,PlayerPhysicsProtocol.JumpOutcome.REJECTED);
        assertTrue(terminals.record(1,rejected));assertFalse(terminals.record(1,rejected));
        assertFalse(terminals.mayReplay(edge,20));
        assertThrows(IllegalArgumentException.class,()->terminals.record(1,
                new PlayerPhysicsProtocol.JumpDecision(1,21,PlayerPhysicsProtocol.JumpOutcome.APPLIED)));
        terminals.retire();assertThrows(IllegalStateException.class,()->terminals.mayReplay(edge,20));
    }
    @Test void actualInputCodecRoundTripAndMalformedTags() {
        var packet=input(3,20);var bytes=PlayerPhysicsProtocol.encode(packet);
        assertEquals(packet,PlayerPhysicsProtocol.decode(bytes,new PhysicsDecodeBudget(bytes.length,new StoredDyadic.Work())));
        bytes[0]=9;assertThrows(IllegalArgumentException.class,()->PlayerPhysicsProtocol.inspectFrame(bytes));
        bytes[0]=(byte)PlayerPhysicsProtocol.VERSION;bytes[bytes.length-1]=2;
        assertThrows(IllegalArgumentException.class,()->PlayerPhysicsProtocol.inspectFrame(bytes));
        assertThrows(IllegalArgumentException.class,()->new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER,packet.scope(),1,2,
                Optional.empty(),Optional.empty(),Optional.empty(),OptionalInt.empty(),Optional.empty(),Optional.empty(),Optional.empty(),packet.predictionInput(),Optional.empty()));
    }
    @Test void beforeFirstBoundaryIsReadOnlyAndRejectsMissingRetiredOrAlreadyMappedHistory() {
        var history=new PlayerPredictionTiming.History<String>(1,20,2,2);
        assertTrue(assertInstanceOf(PlayerPredictionTiming.Ready.class,history.beforeFirstBoundary()).slots().isEmpty());
        history.append(20,"a");history.append(21,"b");
        var pending=assertInstanceOf(PlayerPredictionTiming.Ready.class,history.beforeFirstBoundary());
        assertEquals(pending,history.beforeFirstBoundary());assertEquals(2,pending.slots().size());
        assertEquals(1,assertInstanceOf(PlayerPredictionTiming.Ready.class,history.after(boundary(101))).slots().size());
        assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,history.beforeFirstBoundary());
        history.retire();assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,history.beforeFirstBoundary());
        var lost=new PlayerPredictionTiming.History<String>(1,20,2,2);
        lost.append(20,"a");lost.append(21,"b");lost.append(22,"c");
        assertInstanceOf(PlayerPredictionTiming.NeedsRebase.class,lost.beforeFirstBoundary());
    }

}
