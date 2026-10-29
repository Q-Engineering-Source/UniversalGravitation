package wi.unigrav.proxy;

import wi.unigrav.core.player.PlayerPhysicsServer;
import java.util.Objects;
import java.util.Optional;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import dev.crlhitbox.api.geometry.Vec3d;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.Entity;

public class CommonProxy implements IProxy {
    private final PlayerPhysicsServer physics;
    public CommonProxy() { this(PlayerPhysicsServer.INSTANCE); }
    public CommonProxy(PlayerPhysicsServer physics) { this.physics = Objects.requireNonNull(physics, "physics"); }

    @Override public void runNativeEnvironment(Entity actor, Runnable original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote)
            physics.runNativeEnvironment(player, original);
        else original.run();
    }

    @Override public boolean nativeWaterMovement(Entity actor, BooleanSupplier original) {
        return actor instanceof EntityPlayerMP player && !actor.world.isRemote
                ? physics.nativeWaterMovement(player, original) : original.getAsBoolean();
    }

    @Override public boolean nativeInLava(Entity actor, BooleanSupplier original) {
        return actor instanceof EntityPlayerMP player && !actor.world.isRemote
                ? physics.nativeInLava(player, original) : original.getAsBoolean();
    }

    @Override public void runLegacyMotion(EntityLivingBase actor, Runnable original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote && physics.drivesMotion(player)) return;
        original.run();
    }

    @Override public void runNativePose(EntityLivingBase actor, Runnable original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote) physics.runNativePose(player, original);
        else original.run();
    }

    @Override public Optional<PlayerPhysicalSnapshot> ownedPhysicalBody(EntityPlayer actor) {
        return actor instanceof EntityPlayerMP player && !actor.world.isRemote
                ? physics.ownedPhysicalBody(player) : Optional.empty();
    }

    @Override public void runPoseMove(EntityLivingBase actor, MoverType type, Vec3d delta, Consumer<Vec3d> original) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote)
            physics.runPoseMove(player, type, delta, original);
        else original.accept(delta);
    }

    @Override public AxisAlignedBB aquaPoseClearanceBox(EntityPlayer actor, AxisAlignedBB aquaCandidate) {
        if (actor instanceof EntityPlayerMP player && !actor.world.isRemote)
            return physics.aquaPoseClearanceBox(player, aquaCandidate);
        return aquaCandidate;
    }
}
