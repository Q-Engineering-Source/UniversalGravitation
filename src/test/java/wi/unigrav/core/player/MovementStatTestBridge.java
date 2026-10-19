package wi.unigrav.core.player;
import java.util.ArrayDeque;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.stats.StatBase;
/** TEST ONLY actual mapped native body; not production Mixin evidence. */
public final class MovementStatTestBridge {
    private MovementStatTestBridge() { }
    public interface Body { void unigrav$test$movementStat(double x,double y,double z); }
    private static final ThreadLocal<ArrayDeque<InstalledMovementStat.NativeCall>> CALLS=ThreadLocal.withInitial(ArrayDeque::new);
    public static void run(EntityPlayer actor,double x,double y,double z) {
        try(var call=InstalledMovementStat.enter(actor)) {
            var stack=CALLS.get();stack.push(call);
            try {((Body)actor).unigrav$test$movementStat(x,y,z);call.check();}
            finally {stack.pop();if(stack.isEmpty())CALLS.remove();}
        }
    }
    public static boolean isRiding(EntityPlayer actor) {return CALLS.get().getFirst().external("isRiding",actor::isRiding);}
    public static boolean isInWater(EntityPlayer actor) {return CALLS.get().getFirst().external("isInWater",actor::isInWater);}
    public static boolean isOnLadder(EntityPlayer actor) {return CALLS.get().getFirst().external("isOnLadder",actor::isOnLadder);}
    public static boolean isSprinting(EntityPlayer actor) {return CALLS.get().getFirst().external("isSprinting",actor::isSprinting);}
    public static boolean isSneaking(EntityPlayer actor) {return CALLS.get().getFirst().external("isSneaking",actor::isSneaking);}
    public static boolean isElytraFlying(EntityPlayer actor) {return CALLS.get().getFirst().external("isElytraFlying",actor::isElytraFlying);}
    public static boolean isInsideOfMaterial(EntityPlayer actor,Material material) {
        return CALLS.get().getFirst().external("isInsideOfMaterial",()->actor.isInsideOfMaterial(material));
    }
    public static void addStat(EntityPlayer actor,StatBase stat,int amount) {
        CALLS.get().getFirst().external("addStat",()->{actor.addStat(stat,amount);});
    }
    public static void addExhaustion(EntityPlayer actor,float amount) {
        CALLS.get().getFirst().external("addExhaustion",()->{actor.addExhaustion(amount);});
    }
}
