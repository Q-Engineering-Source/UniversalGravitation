package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMobilityInputTest {
    private static final PlayerPhysicsProtocol.Controls JUMP = new PlayerPhysicsProtocol.Controls(
            new Vec3d(0,0,0),new Vec3d(0,0,1),true,false,false,false,1);
    private static PlayerPhysicsClientSession session() {
        return new PlayerPhysicsClientSession(snapshot -> true, () -> {}, () -> {});
    }
    @Test void heldSpaceSamplesOneImmediateJumpAndOnePressIdentity() {
        var session = session();
        var first = session.sampleMobility(JUMP,true,true,true);
        assertTrue(first.jump()); assertTrue(first.mobility().held());
        for(int tick=0;tick<30;tick++) {
            var held = session.sampleMobility(JUMP,true,true,true);
            assertFalse(held.jump()); assertTrue(held.mobility().held());
            assertEquals(first.mobility().pressId(),held.mobility().pressId());
        }
        assertFalse(session.sampleMobility(JUMP,false,true,true).jump());
        var next = session.sampleMobility(JUMP,true,true,true);
        assertTrue(next.jump()); assertTrue(next.mobility().pressId()>first.mobility().pressId());
    }
    @Test void ordinaryHeldSpaceRemainsHeldForPredictionAcrossMultipleJumps() {
        var session = session();
        long press = 0;
        for (int tick = 0; tick < 40; tick++) {
            var sample = session.sampleMobility(JUMP, true, true, false);
            assertTrue(sample.jump());
            assertTrue(sample.mobility().held());
            if (tick == 0) press = sample.mobility().pressId();
            assertEquals(press, sample.mobility().pressId());
        }
        assertFalse(session.sampleMobility(JUMP, false, true, false).jump());
        assertFalse(session.sampleMobility(JUMP, true, false, false).jump());
    }
    @Test void ordinaryHeldSpaceStillRequiresReleaseAfterCorrection() {
        var session = session();
        session.sampleMobility(JUMP, true, true, false);
        session.nativeCorrection(7);
        assertFalse(session.sampleMobility(JUMP, true, true, false).jump());
        session.sampleMobility(JUMP, false, true, false);
        assertTrue(session.sampleMobility(JUMP, true, true, false).jump());
        assertTrue(session.sampleMobility(JUMP, true, true, false).jump());
    }
    @Test void teleportCannotRemintStillHeldSpaceBeforeAnActualRelease() {
        var session = session();
        var before = session.sampleMobility(JUMP,true,true,true);
        session.nativeCorrection(7);
        for(int tick=0;tick<20;tick++) {
            var blocked = session.sampleMobility(JUMP,true,true,true);
            assertFalse(blocked.jump()); assertFalse(blocked.mobility().held());
            assertEquals(before.mobility().pressId(),blocked.mobility().pressId());
        }
        session.sampleMobility(JUMP,false,true,true);
        var next = session.sampleMobility(JUMP,true,true,true);
        assertTrue(next.jump()); assertTrue(next.mobility().held());
        assertTrue(next.mobility().pressId()>before.mobility().pressId());
    }
    @Test void focusLossPublishesReleaseAndCannotContinueOldPressDuration() {
        var session = session();
        var before = session.sampleMobility(JUMP,true,true,true);
        var unfocused = session.sampleMobility(JUMP,true,false,true);
        assertFalse(unfocused.jump()); assertFalse(unfocused.mobility().held());
        assertFalse(unfocused.mobility().focused());
        var resumed = session.sampleMobility(JUMP,true,true,true);
        assertTrue(resumed.jump()); assertTrue(resumed.mobility().pressId()>before.mobility().pressId());
    }
}
