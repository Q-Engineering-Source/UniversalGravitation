package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.play.client.CPacketPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import static org.junit.jupiter.api.Assertions.*;

class ClientNativeEnvironmentLifetimeTest {
    @Test void attributedFailureFencesAcceptedMirrorAndMintsIdentityOnlyRecovery(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 950), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 951));
            var accepted = snapshot(actor.getUniqueID(), actor.dimension, 1);
            var session = new PlayerPhysicsClientSession(s -> PlayerPhysicsClientMirror.install(actor, s), () -> {}, () -> {});
            own(session, accepted);
            var provenance = session.nativeEnvironmentLifetime(accepted);
            var cause = new IllegalStateException("actual fluid responsibility sentinel");
            var failure = session.failNativeEnvironment(provenance, cause);
            assertSame(cause, failure.getCause());
            assertTrue(failure.current(session));
            assertTrue(session.installationFenceActive(actor));
            assertEquals(PlayerPhysicsClientSession.StateDisposition.NATIVE_ENVIRONMENT_THREW, session.lastStateDisposition());
            var recovery = session.recoveryRequest().orElseThrow();
            assertEquals(PlayerPhysicsProtocol.Kind.RECOVER, recovery.kind());
            assertEquals(accepted.scope(), recovery.scope());
            assertTrue(recovery.snapshot().isEmpty());
            assertTrue(recovery.controls().isEmpty());
            assertSame(recovery, session.recoveryRequest().orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, accepted));
        }
    }

    @Test void staleRevisionCannotRetireLaterAcceptedState(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 952), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 953));
            var accepted = snapshot(actor.getUniqueID(), actor.dimension, 1);
            var session = new PlayerPhysicsClientSession(s -> PlayerPhysicsClientMirror.install(actor, s), () -> {}, () -> {});
            own(session, accepted);
            var old = session.nativeEnvironmentLifetime(accepted);
            var next = new PlayerPhysicalSnapshot(accepted.scope(), 2, accepted.placement(), accepted.worldVelocity(), accepted.contacts());
            assertTrue(session.receive(packet(PlayerPhysicsProtocol.Kind.STATE, 3, next)));
            assertFalse(old.current(session));
            var cause = new IllegalStateException("old fluid failure");
            assertSame(cause, assertThrows(IllegalStateException.class, () -> session.failNativeEnvironment(old, cause)));
            assertFalse(session.installationFenceActive());
            assertSame(next, session.presentationSnapshot().orElseThrow());
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }

    @Test void correctionAndResetInvalidateExactProvenance(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 954), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 955));
            var accepted = snapshot(actor.getUniqueID(), actor.dimension, 1);
            var session = new PlayerPhysicsClientSession(s -> PlayerPhysicsClientMirror.install(actor, s), () -> {}, () -> {});
            own(session, accepted);
            var provenance = session.nativeEnvironmentLifetime(accepted);
            assertEquals(-1, provenance.correctionId());
            session.nativeCorrection(7);
            assertFalse(provenance.current(session));
            assertTrue(session.nativeEnvironmentMustPause(actor));
            assertTrue(session.nativeEnvironmentSnapshot(actor).isEmpty());
            var cause = new IllegalStateException("stale correction");
            assertSame(cause, assertThrows(IllegalStateException.class, () -> session.failNativeEnvironment(provenance, cause)));
            session.reset();
            assertFalse(provenance.current(session));
            assertFalse(session.nativeEnvironmentMustPause(actor));
            assertTrue(session.recoveryRequest().isEmpty());
        }
    }

    @Test void ownedFreeGeometryDriftRetainsCustodyAndReachesAttributedFailure(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 957), true)) {
            var actor = fixture.serverPlayer(new UUID(0, 958));
            var accepted = snapshot(actor.getUniqueID(), actor.dimension, 1);
            var session = new PlayerPhysicsClientSession(s -> PlayerPhysicsClientMirror.install(actor, s), () -> {}, () -> {});
            own(session, accepted);
            actor.setPosition(99, 100, 101);
            actor.setElytraFlying();
            assertFalse(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(actor, accepted));
            assertSame(accepted, session.nativeEnvironmentSnapshot(actor).orElseThrow());
            assertFalse(session.nativeEnvironmentMustPause(actor));
            var provenance = session.nativeEnvironmentLifetime(accepted);
            var cause = new IllegalStateException("actual Free mirror geometry drift");
            var failure = session.failNativeEnvironment(provenance, cause);
            assertSame(cause, failure.getCause());
            assertTrue(failure.current(session));
            assertTrue(session.nativeEnvironmentMustPause(actor));
            assertTrue(session.recoveryRequest().isPresent());
        }
    }

    private static PlayerPhysicalSnapshot snapshot(UUID actor, int dimension, long revision) {
        var scope = new PlayerPhysicalSnapshot.Scope(actor, new UUID(0, 956), dimension, 1);
        var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.6D, 1.8D, 1.62D));
        return new PlayerPhysicalSnapshot(scope, revision,
                new PlayerBodyPlacement(new Vec3d(8, 70, 8), CardinalFrame.Y_POS.attitude(), shape), new Vec3d(0, 0, 0),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }
    private static void own(PlayerPhysicsClientSession session, PlayerPhysicalSnapshot accepted) {
        assertTrue(session.receive(new PlayerPhysicsProtocol.Packet(PlayerPhysicsProtocol.Kind.OFFER, accepted.scope(), 0, 1,
                Optional.empty(), Optional.empty(), Optional.empty())));
        session.prepare(new CPacketPlayer.Position(0, 0, 0, false), new Vec3d(0, 0, 0), 0, 0,
                false, Optional.empty(), p -> {});
        var dimensions = accepted.placement().shape().dimensions();
        var shape = new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,
                new PlayerBodyShape.Dimensions((float) dimensions.width(), (float) dimensions.height(), (float) dimensions.eyeHeight()));
        var initial = new PlayerPhysicalSnapshot(accepted.scope(), 0,
                new PlayerBodyPlacement(accepted.placement().center(), CardinalFrame.Y_POS.attitude(), shape),
                accepted.worldVelocity(), accepted.contacts());
        assertTrue(session.receive(packet(PlayerPhysicsProtocol.Kind.OWNED, 1, initial)));
        assertTrue(session.receive(packet(PlayerPhysicsProtocol.Kind.STATE, 2, accepted)));
    }
    private static PlayerPhysicsProtocol.Packet packet(PlayerPhysicsProtocol.Kind kind, long sequence, PlayerPhysicalSnapshot accepted) {
        return new PlayerPhysicsProtocol.Packet(kind, accepted.scope(), sequence, 1,
                Optional.empty(), Optional.of(accepted), Optional.empty());
    }
}
