package wi.unigrav.core.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** Minecraft field projection only; the caller owns side, connection and physical lifetime checks. */
public final class PlayerPhysicsClientMirror {
    private PlayerPhysicsClientMirror() { }
    public enum Rejection { NONE, NO_ACTOR, DEAD, NO_CONTACTS, PLAYER_ID, DIMENSION, SHAPE, FRAME, WIDTH, HEIGHT }
    public static Rejection rejection(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (player == null) return Rejection.NO_ACTOR;
        if (player.isDead) return Rejection.DEAD;
        if (snapshot.contacts().isEmpty()) return Rejection.NO_CONTACTS;
        if (!player.getUniqueID().equals(snapshot.scope().playerId())) return Rejection.PLAYER_ID;
        if (player.dimension != snapshot.scope().dimension()) return Rejection.DIMENSION;
        if (!(snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape)) return Rejection.SHAPE;
        if (shape.dimensions().width() != player.width) return Rejection.WIDTH;
        if (shape.dimensions().height() != player.height
                && !(player.width == .6F && nativeHeight(player.height) && nativeHeight(shape.dimensions().height())))
            return Rejection.HEIGHT;
        return Rejection.NONE;
    }

    private static boolean nativeHeight(double height) {
        // updateSize uses float constants; compare their exact promoted values, not rounded decimal doubles.
        return height == (double) .6F || height == (double) 1.65F || height == (double) 1.8F;
    }
    public static boolean install(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (rejection(player, snapshot) != Rejection.NONE) return false;
        var position = snapshot.placement().transport();
        var bounds = snapshot.placement().bounds();
        var velocity = snapshot.worldVelocity();
        // The server already collision-checked this native pose. A local sneak/glide edge may lead or
        // lag it by a tick; adopt the accepted dimensions and bounds together instead of losing movement.
        player.height = (float) snapshot.placement().shape().dimensions().height();
        // Do not call setPosition: its nominal box rebuild would discard accepted collision geometry.
        player.prevPosX = player.posX;
        player.prevPosY = player.posY;
        player.prevPosZ = player.posZ;
        player.posX = position.x();
        player.posY = position.y();
        player.posZ = position.z();
        player.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        player.motionX = velocity.x() * GravityEntityBinder.TICK_SECONDS;
        player.motionY = velocity.y() * GravityEntityBinder.TICK_SECONDS;
        player.motionZ = velocity.z() * GravityEntityBinder.TICK_SECONDS;
        var contacts = snapshot.contacts().orElseThrow();
        player.onGround = contacts.onGround();
        player.collidedHorizontally = contacts.horizontal();
        player.collidedVertically = contacts.vertical();
        player.collided = contacts.horizontal() || contacts.vertical();
        player.fallDistance = contacts.fallDistance();
        return true;
    }
}
