package wi.unigrav.core.player;

import dev.crlhitbox.api.minecraft.packet.PacketMovementHooks;
import wi.unigrav.Reference;

/** Access to the existing per-handler UG transaction; CHB alone owns the shared redirects. */
public interface PlayerPacketMovementAccess {
    PlayerMovementRouter unigrav$packetMovement();
    boolean unigrav$packetTeleportPending();

    static void register() {
        PacketMovementHooks.registerTransaction(Reference.MOD_ID, (call, next) -> {
            var access = (PlayerPacketMovementAccess) call.handler();
            return access.unigrav$packetMovement().move(call, next);
        }, (call, next) -> {
            var access = (PlayerPacketMovementAccess) call.handler();
            return access.unigrav$packetMovement().assignEndpoint(call, next,
                    access.unigrav$packetTeleportPending());
        });
    }
}
