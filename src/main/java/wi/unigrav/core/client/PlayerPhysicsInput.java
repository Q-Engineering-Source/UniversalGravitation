package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Optional;
import net.minecraft.util.math.MathHelper;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Bounded input construction, independent from key polling and from movement application. */
public final class PlayerPhysicsInput {
    private PlayerPhysicsInput() { }
    public static PlayerPhysicsProtocol.Controls keyboard(TangentViewSample view, double forward, double strafe,
            boolean jump, boolean sneak, boolean sprint, boolean boost) {
        if (view == null) throw new NullPointerException("view");
        validateKeyboardAxes(forward, strafe);
        return fromWorld(view.keyboardTranslation(forward, strafe), view.headForward(),
                jump, sneak, sprint, boost, 5, Math.max(1, Math.hypot(forward, strafe)));
    }
    public static PlayerPhysicsProtocol.Controls keyboard(float yaw, double forward, double strafe, Vec3 look,
                                                          boolean jump, boolean sneak, boolean sprint, boolean boost) {
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Non-finite input yaw");
        validateKeyboardAxes(forward, strafe);
        float radians = yaw * 0.017453292F;
        float sin = MathHelper.sin(radians), cos = MathHelper.cos(radians);
        double magnitude = Math.hypot(forward, strafe);
        double keyboardTravelGain = Math.max(1.0, magnitude);
        return fromWorld(new Vec3(strafe * cos - forward * sin, 0, forward * cos + strafe * sin), look,
                jump, sneak, sprint, boost, 5, keyboardTravelGain);
    }

    /** Native creative flight keeps world-Y lift, but horizontal input follows the live NFR view. */
    public static PlayerPhysicsProtocol.Controls keyboard(PlayerPhysicsClientPresentation.ViewBasis view,
            double forward, double strafe, boolean jump, boolean sneak, boolean sprint, boolean boost) {
        if (view == null) throw new NullPointerException("view");
        validateKeyboardAxes(forward, strafe);
        Vec3 up = new Vec3(0, 1, 0);
        var basis = ViewRelativeMovementBasis.sample(up, view.forward(), view.right().cross(up));
        Vec3 translation = basis.forward().scale(forward).add(basis.right().scale(strafe));
        return fromWorld(translation, view.forward(), jump, sneak, sprint, boost, 5,
                Math.max(1, Math.hypot(forward, strafe)));
    }

    /** Maps WASD from the live view onto the server-accepted physical tangent plane. */
    public static PlayerPhysicsProtocol.Controls keyboard(PlayerBodyReference.Effective reference,
                                                          double forward, double strafe, Vec3 look,
                                                          boolean jump, boolean sneak, boolean sprint, boolean boost) {
        if (reference == null) throw new NullPointerException("reference");
        validateKeyboardAxes(forward, strafe);
        Vec3d acceptedHeading = reference.tangentHeading();
        Vec3d physicalUp = reference.frame().up();
        var basis = ViewRelativeMovementBasis.sample(
                new Vec3(physicalUp.x(), physicalUp.y(), physicalUp.z()), look,
                new Vec3(acceptedHeading.x(), acceptedHeading.y(), acceptedHeading.z()));
        Vec3 heading = basis.forward();
        Vec3 right = basis.right();
        Vec3 translation = new Vec3(
                heading.x() * forward + right.x() * strafe,
                heading.y() * forward + right.y() * strafe,
                heading.z() * forward + right.z() * strafe);
        double keyboardTravelGain = Math.max(1.0, Math.hypot(forward, strafe));
        return fromWorld(translation, look, jump, sneak, sprint, boost, 5, keyboardTravelGain);
    }

    public static PlayerPhysicsProtocol.Controls world(Vec3 translation, Vec3 look, boolean jump, boolean sneak,
                                                       boolean sprint, boolean boost, int gear) {
        return fromWorld(translation, look, jump, sneak, sprint, boost, gear, 1.0);
    }

    public static PlayerPhysicsProtocol.Controls world(Vec3 translation, Vec3 look, boolean jump, boolean sneak,
                                                       boolean sprint, boolean boost, int gear,
                                                       Optional<Rotation3d> desiredBodyAttitude) {
        var controls = world(translation, look, jump, sneak, sprint, boost, gear);
        return new PlayerPhysicsProtocol.Controls(controls.translation(), controls.forward(), jump, sneak,
                sprint, boost, gear, controls.keyboardTravelGain(), controls.bodyReferenceIntent(), desiredBodyAttitude);
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

    private static void validateKeyboardAxes(double forward, double strafe) {
        if (!Double.isFinite(forward) || !Double.isFinite(strafe)
                || Math.abs(forward) > 1.0 || Math.abs(strafe) > 1.0) {
            throw new IllegalArgumentException("Invalid keyboard axes");
        }
    }
}
