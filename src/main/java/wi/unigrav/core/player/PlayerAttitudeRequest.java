package wi.unigrav.core.player;

import java.util.Objects;

import dev.crlhitbox.api.geometry.Rotation3d;
import net.minecraft.world.WorldServer;

/**
 * An untrusted, short-lived owner input sample. It carries no geometry,
 * installation, velocity, budget, or movement authority.
 */
record PlayerAttitudeRequest(long inputSequence,
                             PlayerPhysicalSnapshot.Scope scope,
                             long proposal,
                             long correctionRevision,
                             WorldServer world,
                             PlayerPhysicsInboundConnections.ConnectionHandle connection,
                             PlayerPhysicsInboundConnections.ScopeHandle scopeHandle,
                             long receivedWorldTick,
                             Rotation3d rawDesiredQ) implements ModeTransitionPrepared.Request {
    PlayerAttitudeRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(scopeHandle, "scopeHandle");
        Objects.requireNonNull(rawDesiredQ, "rawDesiredQ");
    }
}
