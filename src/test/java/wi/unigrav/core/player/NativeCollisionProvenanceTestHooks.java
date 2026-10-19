package wi.unigrav.core.player;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

/** TEST-classpath adapter; this is not evidence that the production World Mixin applied. */
public final class NativeCollisionProvenanceTestHooks {
    private NativeCollisionProvenanceTestHooks() { }

    public static List<AxisAlignedBB> nativeCall(World world, Entity actor, AxisAlignedBB bounds,
            Supplier<List<AxisAlignedBB>> original) {
        if (!NativeCollisionProvenance.scoped()) return original.get();
        try (NativeCollisionProvenance.NativeCall call =
                NativeCollisionProvenance.enterNative(world, actor, bounds)) {
            List<AxisAlignedBB> result = original.get();
            call.returned(result);
            return result;
        }
    }

    public static boolean collectorCall(World world, Entity actor, AxisAlignedBB bounds,
            List<AxisAlignedBB> output, Supplier<Boolean> original) {
        if (!NativeCollisionProvenance.scoped()) return original.get();
        try (NativeCollisionProvenance.CollectorCall ignored =
                NativeCollisionProvenance.enterCollector(world, actor, bounds, output)) {
            return original.get();
        }
    }
}
