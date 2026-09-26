package wi.unigrav.proxy;

import wi.unigrav.core.player.PlayerPhysicsServer;
import java.util.Objects;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;

public class CommonProxy implements IProxy {
    private final PlayerPhysicsServer physics;
    public CommonProxy() { this(PlayerPhysicsServer.INSTANCE); }
    public CommonProxy(PlayerPhysicsServer physics) { this.physics = Objects.requireNonNull(physics, "physics"); }

    @Override public void runLegacyMotion(EntityLivingBase actor, Runnable original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote && physics.drivesMotion(player)) return;
        original.run();
    }

    @Override public void runNativePose(EntityLivingBase actor, Runnable original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote) physics.runNativePose(player, original);
        else original.run();
    }
}
