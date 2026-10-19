package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.util.Random;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/** The existing native walking sequence; spatial permission stays with its caller. */
final class NativeWalkingEvents {
    private NativeWalkingEvents() { }
    static class Calls {
        <T> T dispatch(Entity actor, String stage, Supplier<T> action) {
            return CardinalNativeBoundary.dispatch(actor, stage, action);
        }
        void dispatch(Entity actor, String stage, Runnable action) {
            CardinalNativeBoundary.dispatch(actor, stage, action);
        }
        void check(Entity actor, String stage) { CardinalNativeBoundary.check(actor, stage); }
        Block block(Entity actor, IBlockState state) { return state.getBlock(); }
        Material material(Entity actor, IBlockState state) { return state.getMaterial(); }
        float nextFloat(Entity actor, Random random) { return random.nextFloat(); }
    }
    static final Calls CARDINAL = new Calls();
    static void finish(EntityPlayer player, Supplier<Vec3d> localDelta, IBlockState state, BlockPos support, Calls calls) {
        Entity actor = player;
        Block block = calls.block(actor, state);
        if (calls.dispatch(actor,"canTriggerWalking",actor::canTriggerWalking) && !(actor.onGround && calls.dispatch(actor,"isSneaking",actor::isSneaking)) && !calls.dispatch(actor,"isRiding",actor::isRiding)) {
            Vec3d local = localDelta.get();
            double dx = local.x();
            double dy = block == Blocks.LADDER ? local.y() : 0;
            double dz = local.z();
            if (actor.onGround) calls.dispatch(actor,"onEntityWalk",()->block.onEntityWalk(actor.world,support,actor));
            actor.distanceWalkedModified = (float) (actor.distanceWalkedModified + MathHelper.sqrt(dx * dx + dz * dz) * 0.6D);
            actor.distanceWalkedOnStepModified = (float) (actor.distanceWalkedOnStepModified
                    + MathHelper.sqrt(dx * dx + dy * dy + dz * dz) * 0.6D);
            if (actor.distanceWalkedOnStepModified > actor.nextStepDistance && calls.material(actor, state) != Material.AIR) {
                actor.nextStepDistance = (int) actor.distanceWalkedOnStepModified + 1;
                if (calls.dispatch(actor,"isInWater",actor::isInWater)) {
                    Entity sounding = calls.dispatch(actor,"isBeingRidden",actor::isBeingRidden) && calls.dispatch(actor,"getControllingPassenger",actor::getControllingPassenger) != null
                            ? calls.dispatch(actor,"getControllingPassenger",actor::getControllingPassenger) : actor;
                    float volume = MathHelper.sqrt(sounding.motionX * sounding.motionX * (double) 0.2F
                            + sounding.motionY * sounding.motionY + sounding.motionZ * sounding.motionZ * (double) 0.2F)
                            * (sounding == actor ? 0.35F : 0.4F);
                    var sound=calls.dispatch(actor,"getSwimSound",actor::getSwimSound);
                    var random=calls.dispatch(actor,"getRNG",player::getRNG);
                    float pitch=1+(calls.nextFloat(actor,random)-calls.nextFloat(actor,random))*.4F;
                    calls.dispatch(actor,"playSwimSound",()->actor.playSound(sound,Math.min(1,volume),pitch));
                } else {
                    calls.dispatch(actor,"playStepSound",()->actor.playStepSound(support,block));
                }
            } else if (actor.distanceWalkedOnStepModified > actor.nextFlap && calls.dispatch(actor,"makeFlySound",actor::makeFlySound)
                    && calls.material(actor, state) == Material.AIR) {
                float flap=calls.dispatch(actor,"playFlySound",()->actor.playFlySound(actor.distanceWalkedOnStepModified));
                calls.check(actor,"nextFlap write");actor.nextFlap = flap;
            }
        }
    }
}
