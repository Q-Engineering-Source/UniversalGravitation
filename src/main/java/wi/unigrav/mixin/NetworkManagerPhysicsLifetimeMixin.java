package wi.unigrav.mixin;

import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;

@Mixin(NetworkManager.class)
abstract class NetworkManagerPhysicsLifetimeMixin {
    @Shadow private INetHandler packetListener;

    @Redirect(method = "setNetHandler(Lnet/minecraft/network/INetHandler;)V",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/network/NetworkManager;packetListener:Lnet/minecraft/network/INetHandler;",
                    opcode = Opcodes.PUTFIELD), require = 1, allow = 1)
    private void unigrav$replacePacketListener(NetworkManager manager, INetHandler next) {
        synchronized (manager) {
            PlayerPhysicsInboundConnections.INSTANCE.beforeActualHandlerAssignment(
                    manager, packetListener, next);
            packetListener = next;
        }
    }
}
