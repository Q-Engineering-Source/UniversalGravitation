package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.MathHelper;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Bounded input construction, independent from key polling and from movement application. */
public final class PlayerPhysicsInput {
    private PlayerPhysicsInput() { }
    public static PlayerPhysicsProtocol.Controls keyboard(float yaw, double forward, double strafe, Vec3 look,
                                                          boolean jump, boolean sneak, boolean sprint, boolean boost) {
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Non-finite input yaw");
        float radians = yaw * 0.017453292F;
        float sin = MathHelper.sin(radians), cos = MathHelper.cos(radians);
        return world(new Vec3(strafe * cos - forward * sin, 0, forward * cos + strafe * sin), look,
                jump, sneak, sprint, boost, 5);
    }

    public static PlayerPhysicsProtocol.Controls world(Vec3 translation, Vec3 look, boolean jump, boolean sneak,
                                                       boolean sprint, boolean boost, int gear) {
        double length = Math.hypot(Math.hypot(translation.x(), translation.y()), translation.z());
        double lookLength = Math.hypot(Math.hypot(look.x(), look.y()), look.z());
        if (!Double.isFinite(length) || !Double.isFinite(lookLength) || lookLength <= 0)
            throw new IllegalArgumentException("Invalid physical input vector");
        Vec3 bounded = length > 1 ? translation.scale(1 / length) : translation;
        Vec3 unit = look.scale(1 / lookLength);
        return new PlayerPhysicsProtocol.Controls(new Vec3d(bounded.x(), bounded.y(), bounded.z()),
                new Vec3d(unit.x(), unit.y(), unit.z()), jump, sneak, sprint, boost, gear);
    }
}
