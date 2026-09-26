package wi.unigrav.core.client.boost;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.network.play.client.CPacketPlayer;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.client.PlayerPhysicsClientOffer;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsOutgoingTest {
    private static final PlayerPhysicalSnapshot.Scope SCOPE =
            new PlayerPhysicalSnapshot.Scope(new UUID(0, 1), new UUID(0, 2), 0, 1);
    private static final Vec3d POSITION = new Vec3d(2, 64, 5);

    @Test void ordinaryPlayerPairsApplyWithTheSamePromotedPositionSample() {
        var offer = new PlayerPhysicsClientOffer();
        assertTrue(offer.accept(offer(SCOPE, 1)));
        var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
        var result = offer.prepare(new CPacketPlayer.Rotation(30, -20, true), POSITION,
                99, 98, false, Optional.empty(), sent::add);
        assertEquals(1, sent.size());
        assertEquals(PlayerPhysicsProtocol.Kind.APPLY, sent.getFirst().kind());
        assertTrue(sent.getFirst().application().isEmpty());
        assertEquals(2, result.getX(-1));
        assertEquals(64, result.getY(-1));
        assertEquals(5, result.getZ(-1));
        assertEquals(30, result.getYaw(-1));
        assertEquals(-20, result.getPitch(-1));
        assertTrue(result.isOnGround());
    }

    @Test void remoteActualApplicationPrecedesFrameAndPositionWithoutAnExtraMove() {
        var boost = new RemoteBoostSession(ignored -> true);
        boost.acceptOffer(BoostProtocol.offer(7, 0, 70, true));
        boost.acceptState(BoostProtocol.state(7, 1, 1, 0, BoostProtocol.Mode.ACTIVE,
                5, 70, BoostProtocol.Operation.REPLACE, Vec3.ZERO, BoostProtocol.Reason.NONE), 0);
        var offer = new PlayerPhysicsClientOffer();
        assertTrue(offer.accept(offer(SCOPE, 1)));
        var sent = new ArrayList<Object>();
        var original = new CPacketPlayer.Position(2, 64, 5, false);
        var result = offer.prepare(original, POSITION, 0, 0, true, boost.applicationReceipt(), sent::add);
        sent.add(boost.nextFrame(0, 5, false));
        sent.add(result);
        assertEquals(3, sent.size());
        var apply = assertInstanceOf(PlayerPhysicsProtocol.Packet.class, sent.get(0));
        assertEquals(BoostProtocol.Operation.REPLACE, apply.application().orElseThrow().state().operation());
        assertEquals(BoostProtocol.Kind.FRAME, assertInstanceOf(BoostProtocol.Packet.class, sent.get(1)).kind());
        assertSame(original, sent.get(2));
    }

    @Test void brokenApplicationDoesNotEmitApplyOrPromoteMovement() {
        var boost = new RemoteBoostSession(ignored -> false);
        boost.acceptOffer(BoostProtocol.offer(7, 0, 70, true));
        boost.acceptState(BoostProtocol.state(7, 1, 1, 0, BoostProtocol.Mode.ACTIVE,
                5, 70, BoostProtocol.Operation.ADD, Vec3.ZERO, BoostProtocol.Reason.NONE), 0);
        var offer = new PlayerPhysicsClientOffer();
        assertTrue(offer.accept(offer(SCOPE, 1)));
        var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
        var original = new CPacketPlayer(false);
        assertSame(original, offer.prepare(original, POSITION, 0, 0, true, boost.applicationReceipt(), sent::add));
        assertTrue(sent.isEmpty());
    }

    @Test void staleOfferCannotRewindPendingScopeOrSequence() {
        var offer = new PlayerPhysicsClientOffer();
        assertTrue(offer.accept(offer(SCOPE, 3)));
        assertFalse(offer.accept(offer(SCOPE, 2)));
        var older = new PlayerPhysicalSnapshot.Scope(SCOPE.playerId(), new UUID(0, 9), 0, 0);
        assertFalse(offer.accept(offer(older, 99)));
        var sent = new ArrayList<PlayerPhysicsProtocol.Packet>();
        offer.prepare(new CPacketPlayer(false), POSITION, 0, 0, false, Optional.empty(), sent::add);
        offer.prepare(new CPacketPlayer(false), POSITION, 0, 0, false, Optional.empty(), sent::add);
        assertEquals(3, sent.getFirst().offerSequence());
        assertEquals(1, sent.getFirst().sequence());
        assertEquals(2, sent.getLast().sequence());
    }

    private static PlayerPhysicsProtocol.Packet offer(PlayerPhysicalSnapshot.Scope scope, long sequence) {
        return new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, scope, sequence, sequence,
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
