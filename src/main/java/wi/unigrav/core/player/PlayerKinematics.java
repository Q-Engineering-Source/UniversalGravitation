package wi.unigrav.core.player;

import net.minecraft.entity.player.EntityPlayer;

/** Native movement states shared by the two sides; they do not revoke Personal G intent. */
public final class PlayerKinematics {
    private PlayerKinematics() { }

    public static boolean requiresNative(EntityPlayer player) {
        return player.isRiding() || player.isPlayerSleeping() || player.isSpectator() || player.noClip;
    }
}
