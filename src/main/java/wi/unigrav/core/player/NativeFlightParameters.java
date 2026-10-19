package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;

/** Immutable blocks/tick flight sample, retained with prediction rather than resampled on replay. */
public record NativeFlightParameters(float flySpeed, float verticalModifier, boolean cancelInertia, float jumpBonus,
                                     String source) {
    public NativeFlightParameters {
        if (!Float.isFinite(flySpeed) || flySpeed < 0 || !Float.isFinite(verticalModifier) || verticalModifier < 0
                || !Float.isFinite(jumpBonus) || jumpBonus < 0)
            throw new IllegalArgumentException("Invalid native equipment flight parameters");
        java.util.Objects.requireNonNull(source);
    }

    /** DE applies its equipment effects before the ordinary flight input/travel kernel. */
    public Vec3d beforeTravel(Vec3d motion, boolean onGround, PlayerPhysicsProtocol.Controls controls) {
        double y = motion.y();
        if (!onGround && y != 0 && verticalModifier > 0 && controls.jump() != controls.sneak())
            y = (controls.jump() ? 1 : -1) * (double) (0.225F * verticalModifier);
        boolean brake = cancelInertia && controls.translation().x() == 0 && controls.translation().z() == 0;
        return new Vec3d(brake ? motion.x() * .5 : motion.x(), y, brake ? motion.z() * .5 : motion.z());
    }
}
