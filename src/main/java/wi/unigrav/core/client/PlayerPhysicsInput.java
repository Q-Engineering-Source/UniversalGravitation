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
        if (!Double.isFinite(forward) || !Double.isFinite(strafe)
                || Math.abs(forward) > 1.0 || Math.abs(strafe) > 1.0) {
            throw new IllegalArgumentException("Invalid keyboard axes");
        }
        float radians = yaw * 0.017453292F;
        float sin = MathHelper.sin(radians), cos = MathHelper.cos(radians);
        double magnitude = Math.hypot(forward, strafe);
        double keyboardTravelGain = Math.max(1.0, magnitude);
        return fromWorld(new Vec3(strafe * cos - forward * sin, 0, forward * cos + strafe * sin), look,
                jump, sneak, sprint, boost, 5, keyboardTravelGain);
    }

    public static PlayerPhysicsProtocol.Controls world(Vec3 translation, Vec3 look, boolean jump, boolean sneak,
                                                       boolean sprint, boolean boost, int gear) {
        return fromWorld(translation, look, jump, sneak, sprint, boost, gear, 1.0);
    }

    private static PlayerPhysicsProtocol.Controls fromWorld(Vec3 translation, Vec3 look, boolean jump, boolean sneak,
                                                            boolean sprint, boolean boost, int gear,
                                                            double keyboardTravelGain) {
        double length = Math.hypot(Math.hypot(translation.x(), translation.y()), translation.z());
        double lookLength = Math.hypot(Math.hypot(look.x(), look.y()), look.z());
        if (!Double.isFinite(length) || !Double.isFinite(lookLength) || lookLength <= 0)
            throw new IllegalArgumentException("Invalid physical input vector");
        if (keyboardTravelGain > 1.0 && length == 0) throw new IllegalArgumentException("Invalid keyboard direction");
        Vec3 bounded = length > 1 || keyboardTravelGain > 1.0 ? translation.scale(1 / length) : translation;
        Vec3 unit = look.scale(1 / lookLength);
        return new PlayerPhysicsProtocol.Controls(new Vec3d(bounded.x(), bounded.y(), bounded.z()),
                new Vec3d(unit.x(), unit.y(), unit.z()), jump, sneak, sprint, boost, gear, keyboardTravelGain);
    }
}
