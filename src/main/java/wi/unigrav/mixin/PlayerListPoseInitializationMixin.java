package wi.unigrav.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.world.WorldServer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import wi.unigrav.core.player.PlayerPosePersistence;

/** Qualify only vanilla's actual initial correction; unrelated login-event teleports cannot reuse it. */
@Mixin(PlayerList.class)
abstract class PlayerListPoseInitializationMixin {
    @WrapOperation(method = "playerLoggedIn(Lnet/minecraft/entity/player/EntityPlayerMP;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;spawnEntity(Lnet/minecraft/entity/Entity;)Z"),
            require = 1, allow = 1)
    private boolean unigrav$initialPoseWorldJoin(WorldServer world, Entity actor, Operation<Boolean> original) {
        if (actor instanceof EntityPlayerMP player)
            return PlayerPosePersistence.initialJoin(player, () -> original.call(world, actor));
        return original.call(world, actor);
    }

    @WrapOperation(method = "initializeConnectionToPlayer(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetHandlerPlayServer;setPlayerLocation(DDDFF)V"),
            require = 1, allow = 1)
    private void unigrav$initialPoseHandshake(NetHandlerPlayServer handler, double x, double y, double z,
            float yaw, float pitch, Operation<Void> original) {
        PlayerPosePersistence.initialTeleport(handler, x, y, z, yaw, pitch,
                () -> original.call(handler, x, y, z, yaw, pitch));
    }
}
