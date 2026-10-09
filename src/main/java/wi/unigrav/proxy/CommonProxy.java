package wi.unigrav.proxy;

import wi.unigrav.core.player.PlayerPhysicsServer;
import java.util.Objects;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.MoverType;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.function.Consumer;

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

    @Override public void runPoseMove(EntityLivingBase actor, MoverType type, Vec3d delta, Consumer<Vec3d> original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote)
            physics.runPoseMove(player, type, delta, original);
        else original.accept(delta);
    }
}
