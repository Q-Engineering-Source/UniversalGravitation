package wi.unigrav.core.client;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerKinematics;

/** Client tick presentation of accepted motion; never a movement or camera owner. */
public final class PlayerPhysicsClientPresentation {
    private EntityPlayer actor;
    private PlayerPhysicalSnapshot previous;
    private Vec3d tickPosition;
    private double pendingGroundDistance;
    private double pendingLimbDistance;
    private Vec3 pendingGroundDelta = Vec3.ZERO;
    private float bodyYaw;
    private float bodyOffset;
    private float previousBodyOffset;

    /** Same sampled view basis as FREE; separate from physical placement and mouse input. */
    public record ViewBasis(Vec3 forward, Vec3 right) { }

    public boolean install(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (!PlayerPhysicsClientMirror.install(player, snapshot)) return false;
        boolean continuous = actor == player && previous != null && previous.scope().equals(snapshot.scope());
        if (!continuous) {
            bodyYaw = player.renderYawOffset;
            bodyOffset = previousBodyOffset = 0;
            tickPosition = snapshot.placement().transport();
        }
        if (continuous && snapshot.revision() > previous.revision()) {
            var before = previous.placement().transport();
            var after = snapshot.placement().transport();
            double distance = Math.hypot(after.x() - before.x(), after.z() - before.z());
            // Native player travel animates horizontal airborne motion too; it does not require onGround.
            pendingLimbDistance += distance;
            if (snapshot.contacts().orElseThrow().onGround() || snapshot.contacts().orElseThrow().nearFootLocomotion()) {
                pendingGroundDistance += distance;
                pendingGroundDelta = pendingGroundDelta.add(new Vec3(after.x() - before.x(),
                        after.y() - before.y(), after.z() - before.z()));
            } else {
                pendingGroundDistance = 0;
                pendingGroundDelta = Vec3.ZERO;
            }
        } else {
            pendingGroundDistance = 0;
            pendingLimbDistance = 0;
            pendingGroundDelta = Vec3.ZERO;
        }
        actor = player;
        previous = snapshot;
        return true;
    }

    public void tick(EntityPlayer player) {
        tick(player, null);
    }

    public void tick(EntityPlayer player, ViewBasis view) {
        if (player != null && PlayerKinematics.requiresNative(player)) { reset(); return; }
        if (player != actor || previous == null) return;
        // STATE is applied at ClientTick START. Vanilla's base tick then copies current into prevPos;
        // restore the last completed client tick, not the preceding network packet in a burst.
        // Both UIE's player anchor (prevPos) and vanilla entity rendering (lastTickPos) use this span.
        player.prevPosX = player.lastTickPosX = tickPosition.x();
        player.prevPosY = player.lastTickPosY = tickPosition.y();
        player.prevPosZ = player.lastTickPosZ = tickPosition.z();
        tickPosition = new Vec3d(player.posX, player.posY, player.posZ);
        double distance = pendingGroundDistance;
        double limbDistance = pendingLimbDistance;
        pendingGroundDistance = 0;
        pendingLimbDistance = 0;
        updateHeading(player, view, pendingGroundDelta);
        pendingGroundDelta = Vec3.ZERO;
        // Native travel's visual tail, now driven by accepted displacement instead of a second travel().
        player.prevLimbSwingAmount = player.limbSwingAmount;
        player.limbSwingAmount += ((float) Math.min(1, limbDistance * 4) - player.limbSwingAmount) * 0.4F;
        player.limbSwing += player.limbSwingAmount;
        // EntityRenderer's bobbing consumes these counters. Native onLivingUpdate retains cameraYaw/Pitch.
        player.prevDistanceWalkedModified = player.distanceWalkedModified;
        player.distanceWalkedModified += (float) (distance * 0.6);
        if (previous.contacts().orElseThrow().nearFootLocomotion() && !player.onGround) {
            // Native living update drives airborne bob amplitude toward zero. Reuse its pre-tick baseline,
            // not its already-smoothed value, so this replaces that visual branch rather than damping twice.
            float target = (float) Math.min(.1, distance);
            player.cameraYaw = player.prevCameraYaw + (target - player.prevCameraYaw) * .4F;
        }
    }

    private void updateHeading(EntityPlayer player, ViewBasis view, Vec3 delta) {
        // Native onUpdate sees zero displacement after its prevPos reset: travel is intentionally retired.
        // Recompute its body-only tail from accepted motion, using our last presented baseline once per tick.
        float target = bodyYaw;
        if (Math.hypot(delta.x(), delta.z()) > 1e-7) {
            float direction = (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
            float relative = MathHelper.wrapDegrees(direction - player.rotationYaw);
            if (Math.abs(relative) > 95) direction += 180;
            target = direction;
        }
        if (player.swingProgress > 0) target = player.rotationYaw;
        float before = bodyYaw;
        bodyYaw += MathHelper.wrapDegrees(target - bodyYaw) * .3F;
        float headRelative = MathHelper.clamp(MathHelper.wrapDegrees(player.rotationYaw - bodyYaw), -75, 75);
        bodyYaw = player.rotationYaw - headRelative;
        if (headRelative * headRelative > 2500) bodyYaw += headRelative * .2F;
        player.prevRenderYawOffset = bodyYaw - MathHelper.wrapDegrees(bodyYaw - before);
        player.renderYawOffset = bodyYaw;

        // UIE neutralizes vanilla body yaw while it owns the root quaternion. Supply the same gait
        // correction through its BodyPose provider instead; never rotate the camera or transport frame.
        previousBodyOffset = bodyOffset;
        double desired = 0;
        if (view != null && player.swingProgress == 0) {
            double forward = delta.dot(view.forward());
            double right = delta.dot(view.right());
            if (Math.hypot(forward, right) > 1e-7) {
                desired = Math.toDegrees(Math.atan2(right, forward));
                if (Math.abs(desired) > 95) desired = MathHelper.wrapDegrees(desired + 180);
                desired = Math.max(-75, Math.min(75, desired));
            }
        }
        bodyOffset += ((float) desired - bodyOffset) * .3F;
    }

    public float bodyYawOffset(EntityPlayer player, float partialTicks) {
        if (player != actor || previous == null || PlayerKinematics.requiresNative(player)) return 0;
        return previousBodyOffset + (bodyOffset - previousBodyOffset) * MathHelper.clamp(partialTicks, 0, 1);
    }

    public void reset() {
        actor = null;
        previous = null;
        tickPosition = null;
        pendingGroundDistance = 0;
        pendingLimbDistance = 0;
        pendingGroundDelta = Vec3.ZERO;
        bodyOffset = previousBodyOffset = 0;
    }
}
