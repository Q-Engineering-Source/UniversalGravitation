package wi.unigrav.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import wi.unigrav.core.player.InstalledFreeFall;
import wi.unigrav.core.player.InstalledFreeFallBridge;

/** Bypass only MP's empty override, retaining the actual Living/Entity inherited implementation. */
@Mixin(EntityPlayerMP.class)
abstract class InstalledFreeFallMixin extends EntityPlayer implements InstalledFreeFallBridge {
    protected InstalledFreeFallMixin(World world, GameProfile profile) { super(world,profile); }
    @Override public final void unigrav$installedFall(InstalledFreeFall.Request request) {
        request.invoke((EntityPlayerMP)(Object)this,
                (delta,ground,state,cell)->super.updateFallState(delta,ground,state,cell));
    }
}
