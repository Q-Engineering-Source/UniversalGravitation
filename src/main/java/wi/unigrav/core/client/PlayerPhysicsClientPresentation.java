package wi.unigrav.core.client;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerKinematics;

/** Client tick presentation of accepted motion; never a movement or camera owner. Calls are confined to the client owner thread. */
public final class PlayerPhysicsClientPresentation {
    private EntityPlayer actor;
    private PlayerPhysicalSnapshot previous;
    private PlayerPhysicalSnapshot eyePrevious;
    private Vec3d tickPosition;
    private double pendingGroundDistance;
    private double pendingLimbDistance;
    private Vec3 pendingGroundDelta = Vec3.ZERO;
    private float bodyYaw;
    private float bodyOffset;
    private float previousBodyOffset;
    private long presentationGeneration;
    private long eyeTick = Long.MIN_VALUE;
    private boolean tickEyeSpan;
    private boolean predictedMotion;
    private CardinalFrame localFrame;
    private float localYaw, previousLocalYaw;
    private boolean localYawInitialized;
    private long lastViewSequence = -1;

    /** Same sampled view basis as FREE; separate from physical placement and mouse input. */
    public record ViewBasis(Vec3 forward, Vec3 right) { }

    /**
     * Opaque immutable result of presentation preparation, bound to one owner, actor, snapshot, and generation.
     * A result is current only until the owner's next presentation state change.
     */
    public static final class PreparedPresentation {
        private final PlayerPhysicsClientPresentation owner;
        private final EntityPlayer actor;
        private final PlayerPhysicalSnapshot snapshot;
        private final long generation;
        private final PlayerPhysicalSnapshot eyePrevious;
        private final Vec3d tickPosition;
        private final double pendingGroundDistance;
        private final double pendingLimbDistance;
        private final Vec3 pendingGroundDelta;
        private final float bodyYaw;
        private final float bodyOffset;
        private final float previousBodyOffset;
        private final CardinalFrame localFrame;
        private final float localYaw, previousLocalYaw;
        private final boolean localYawInitialized;

        private PreparedPresentation(PlayerPhysicsClientPresentation owner, EntityPlayer actor,
                                     PlayerPhysicalSnapshot snapshot, long generation,
                                     PlayerPhysicalSnapshot eyePrevious, Vec3d tickPosition,
                                     double pendingGroundDistance, double pendingLimbDistance,
                                     Vec3 pendingGroundDelta, float bodyYaw, float bodyOffset,
                                     float previousBodyOffset) {
            this.owner = owner;
            this.actor = actor;
            this.snapshot = snapshot;
            this.generation = generation;
            this.eyePrevious = eyePrevious;
            this.tickPosition = tickPosition;
            this.pendingGroundDistance = pendingGroundDistance;
            this.pendingLimbDistance = pendingLimbDistance;
            this.pendingGroundDelta = pendingGroundDelta;
            this.bodyYaw = bodyYaw;
            this.bodyOffset = bodyOffset;
            this.previousBodyOffset = previousBodyOffset;
            localFrame = snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape ? shape.frame() : null;
            boolean continuous = actor == owner.actor && owner.previous != null
                    && snapshot.scope().equals(owner.previous.scope()) && localFrame == owner.localFrame;
            localYaw = continuous ? owner.localYaw : 0;
            previousLocalYaw = continuous ? owner.previousLocalYaw : 0;
            localYawInitialized = continuous && owner.localYawInitialized;
        }

        /**
         * Checks exact identities and that no intervening commit, tick, or reset made this result stale.
         * Call on the client owner thread; this check is not cross-thread synchronization.
         */
        public boolean isBoundTo(PlayerPhysicsClientPresentation expectedOwner, EntityPlayer expectedActor,
                                 PlayerPhysicalSnapshot expectedSnapshot) {
            return owner == expectedOwner && actor == expectedActor && snapshot == expectedSnapshot
                    && generation == owner.presentationGeneration;
        }
    }

    /** Advance accepted interpolation once, before this tick's inbound installation drain. */
    public void beginClientTick(long clientTick) {
        if (eyeTick == clientTick) return;
        eyeTick = clientTick;
        tickEyeSpan = true;
        // Invalidate preparations captured before this boundary without touching physical authority.
        presentationGeneration = Math.incrementExact(presentationGeneration);
        if (previous != null)
            eyePrevious = previous;
    }

    /**
     * Computes the state the next accepted presentation would install without touching the actor or this instance.
     */
    public PreparedPresentation prepare(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshot, "snapshot");

        PlayerPhysicalSnapshot prior = previous;
        boolean continuous = actor == player && prior != null && prior.scope().equals(snapshot.scope())
                && sameAcceptedShape(prior, snapshot);
        float nextBodyYaw = bodyYaw;
        float nextBodyOffset = bodyOffset;
        float nextPreviousBodyOffset = previousBodyOffset;
        Vec3d nextTickPosition = tickPosition;
        if (!continuous) {
            nextBodyYaw = player.renderYawOffset;
            nextBodyOffset = nextPreviousBodyOffset = 0;
            nextTickPosition = snapshot.placement().transport();
        }

        double nextPendingGroundDistance = pendingGroundDistance;
        double nextPendingLimbDistance = pendingLimbDistance;
        Vec3 nextPendingGroundDelta = pendingGroundDelta;
        if (!predictedMotion && continuous && snapshot.revision() > prior.revision()) {
            var before = prior.placement().transport();
            var after = snapshot.placement().transport();
            Vec3d delta = gaitDelta(snapshot, after.subtract(before));
            double distance = Math.hypot(delta.x(), delta.z());
            // Native player travel animates horizontal airborne motion too; it does not require onGround.
            nextPendingLimbDistance += distance;
            if (snapshot.contacts().orElseThrow().onGround() || snapshot.contacts().orElseThrow().nearFootLocomotion()) {
                nextPendingGroundDistance += distance;
                nextPendingGroundDelta = nextPendingGroundDelta.add(new Vec3(after.x() - before.x(),
                        after.y() - before.y(), after.z() - before.z()));
            }
        } else if (!continuous) {
            nextPendingGroundDistance = 0;
            nextPendingLimbDistance = 0;
            nextPendingGroundDelta = Vec3.ZERO;
        }

        PlayerPhysicalSnapshot nextEyePrevious = continuous ? prior : snapshot;
        if (continuous && tickEyeSpan && eyePrevious != null) nextEyePrevious = eyePrevious;
        return new PreparedPresentation(this, player, snapshot, presentationGeneration, nextEyePrevious,
                nextTickPosition, nextPendingGroundDistance, nextPendingLimbDistance,
                nextPendingGroundDelta, nextBodyYaw, nextBodyOffset, nextPreviousBodyOffset);
    }

    /**
     * Applies only precomputed internal presentation fields. Wrong-owner and stale results are rejected.
     */
    public boolean commitPrepared(PreparedPresentation prepared) {
        if (prepared == null || prepared.owner != this || prepared.generation != presentationGeneration) return false;
        long nextGeneration = Math.incrementExact(presentationGeneration);
        actor = prepared.actor;
        previous = prepared.snapshot;
        eyePrevious = prepared.eyePrevious;
        tickPosition = prepared.tickPosition;
        pendingGroundDistance = prepared.pendingGroundDistance;
        pendingLimbDistance = prepared.pendingLimbDistance;
        pendingGroundDelta = prepared.pendingGroundDelta;
        bodyYaw = prepared.bodyYaw;
        bodyOffset = prepared.bodyOffset;
        previousBodyOffset = prepared.previousBodyOffset;
        localFrame = prepared.localFrame;
        localYaw = prepared.localYaw;
        previousLocalYaw = prepared.previousLocalYaw;
        localYawInitialized = prepared.localYawInitialized;
        if (!localYawInitialized) lastViewSequence = -1;
        presentationGeneration = nextGeneration;
        return true;
    }

    public boolean install(EntityPlayer player, PlayerPhysicalSnapshot snapshot) {
        if (!PlayerPhysicsClientMirror.install(player, snapshot)) return false;
        // Mirror projection does not change renderYawOffset or this instance's presentation fields, so preparing
        // after a successful mirror sees the same legacy presentation inputs that install read after that mirror.
        return commitPrepared(prepare(player, snapshot));
    }

    /** Render interpolation is bound to the exact currently installed snapshot and its accepted frame. */
    public Vec3d acceptedEyePosition(EntityPlayer player, PlayerPhysicalSnapshot current,
                                     PlayerBodyReference.Effective reference, float partialTicks) {
        if (player != actor || current != previous || current == null || reference == null
                || !Float.isFinite(partialTicks) || PlayerKinematics.requiresNative(player)
                || !sameCardinalFrame(current, reference.frame())
                || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, current)) return null;
        double alpha = Math.max(0.0, Math.min(1.0, partialTicks));
        Vec3d end = current.placement().eye();
        PlayerPhysicalSnapshot startSnapshot = eyePrevious;
        if (startSnapshot == null || !startSnapshot.scope().equals(current.scope())
                || !sameCardinalFrame(startSnapshot, reference.frame())
                || startSnapshot.revision() > current.revision()) return end;
        Vec3d start = startSnapshot.placement().eye();
        return new Vec3d(start.x() + (end.x() - start.x()) * alpha,
                start.y() + (end.y() - start.y()) * alpha,
                start.z() + (end.z() - start.z()) * alpha);
    }

    private static boolean sameCardinalFrame(PlayerPhysicalSnapshot first, PlayerPhysicalSnapshot second) {
        return first != null && second != null
                && first.placement().shape() instanceof PlayerBodyShape.Cardinal firstShape
                && second.placement().shape() instanceof PlayerBodyShape.Cardinal secondShape
                && firstShape.frame() == secondShape.frame();
    }

    private static boolean sameAcceptedShape(PlayerPhysicalSnapshot first, PlayerPhysicalSnapshot second) {
        return sameCardinalFrame(first, second) || first != null && second != null
                && first.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && second.placement().shape() instanceof PlayerBodyShape.FreeCapsule;
    }

    /** Accepted Free eye history needs no invented cardinal reference or camera-desired geometry. */
    public Vec3d acceptedFreeEyePosition(EntityPlayer player, PlayerPhysicalSnapshot current, float partialTicks) {
        if (player != actor || current != previous || current == null || !Float.isFinite(partialTicks)
                || PlayerKinematics.requiresNative(player)
                || !(current.placement().shape() instanceof PlayerBodyShape.FreeCapsule)
                || !PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(player, current)) return null;
        Vec3d end = current.placement().eye();
        PlayerPhysicalSnapshot startSnapshot = eyePrevious;
        if (startSnapshot == null || !startSnapshot.scope().equals(current.scope())
                || !sameAcceptedShape(startSnapshot, current) || startSnapshot.revision() > current.revision()) return end;
        double alpha = Math.max(0.0, Math.min(1.0, partialTicks));
        Vec3d start = startSnapshot.placement().eye();
        return new Vec3d(start.x() + (end.x() - start.x()) * alpha,
                start.y() + (end.y() - start.y()) * alpha, start.z() + (end.z() - start.z()) * alpha);
    }

    private static boolean sameCardinalFrame(PlayerPhysicalSnapshot snapshot, CardinalFrame frame) {
        return snapshot != null
                && snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape
                && shape.frame() == frame;
    }

    /** Only a newly executed local slot contributes visual distance; replay contributes no events. */
    public void predictedMotion(EntityPlayer player, CardinalPredictionStep.State before, CardinalPredictionStep.State after) {
        if (player != actor || previous == null) return;
        predictedMotion = true;
        if (!(after.placement().shape() instanceof PlayerBodyShape.Cardinal shape)
                || !sameCardinalFrame(previous, shape.frame())) return;
        var worldDelta = after.placement().transport().subtract(before.placement().transport());
        var delta = gaitDelta(previous, worldDelta);
        double distance = Math.hypot(delta.x(), delta.z());
        pendingLimbDistance += distance;
        if (after.contacts().onGround() || after.contacts().nearFootLocomotion()) {
            pendingGroundDistance += distance;
            pendingGroundDelta = pendingGroundDelta.add(new Vec3(worldDelta.x(), worldDelta.y(), worldDelta.z()));
        }
    }
    public void retirePredictedMotion() { predictedMotion = false; }

    private static Vec3d gaitDelta(PlayerPhysicalSnapshot snapshot, Vec3d delta) {
        return snapshot.placement().shape() instanceof PlayerBodyShape.Cardinal shape ? shape.frame().toLocal(delta) : delta;
    }

    /** Computes the complete local heading before committing any continuity or actor presentation fields. */
    public boolean tickCardinal(EntityPlayer player, TangentViewSample sample, BooleanSupplier current) {
        long generation = presentationGeneration;
        var accepted = previous;
        if (player != actor || accepted == null || sample.frame() != localFrame || sample.viewSequence() <= lastViewSequence)
            return false;
        boolean nativeMovement = PlayerKinematics.requiresNative(player);
        if (nativeMovement || !current.getAsBoolean() || generation != presentationGeneration || previous != accepted) return false;
        float head = sample.localHeadYaw();
        float before = localYawInitialized ? localYaw : head;
        float target = before;
        // Keep pending motion in world coordinates for the legacy Free view consumer; only this
        // Cardinal owner evaluates body follow in its installed local tangent plane.
        var localDelta = localFrame.toLocal(new Vec3d(pendingGroundDelta.x(), pendingGroundDelta.y(), pendingGroundDelta.z()));
        Vec3 delta = new Vec3(localDelta.x(), 0, localDelta.z());
        if (Math.hypot(delta.x(), delta.z()) > 1e-7) {
            float direction = (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
            if (Math.abs(MathHelper.wrapDegrees(direction - head)) > 95) direction += 180;
            target = direction;
        }
        if (player.swingProgress > 0) target = head;
        float next = before + MathHelper.wrapDegrees(target - before) * .3F;
        float relative = MathHelper.clamp(MathHelper.wrapDegrees(head - next), -75, 75);
        next = head - relative;
        if (relative * relative > 2500) next += relative * .2F;
        if (!current.getAsBoolean() || generation != presentationGeneration || previous != accepted) return false;
        // From here only plain fields are written. No setter or external callback can split this commit.
        previousLocalYaw = next - MathHelper.wrapDegrees(next - before);
        localYaw = next; localYawInitialized = true; lastViewSequence = sample.viewSequence();
        presentationGeneration = Math.incrementExact(presentationGeneration);
        visualTail(player);
        return true;
    }

    public Vec3 bodyForward(EntityPlayer player, PlayerPhysicalSnapshot current, float partialTicks) {
        if (player != actor || previous == null || !localYawInitialized || !Float.isFinite(partialTicks)
                || !previous.scope().equals(current.scope()) || !sameCardinalFrame(current, localFrame)) return null;
        float yaw = previousLocalYaw + MathHelper.wrapDegrees(localYaw - previousLocalYaw) * MathHelper.clamp(partialTicks, 0, 1);
        double radians = Math.toRadians(yaw);
        var world = localFrame.toWorld(new Vec3d(-Math.sin(radians), 0, Math.cos(radians)));
        return new Vec3(world.x(), world.y(), world.z());
    }

    public void tick(EntityPlayer player) {
        tick(player, null);
    }

    public void tick(EntityPlayer player, ViewBasis view) {
        if (player != null && PlayerKinematics.requiresNative(player)) { reset(); return; }
        if (player != actor || previous == null) return;
        // Any tick consumes or advances prepared presentation state, even if later entity work fails.
        presentationGeneration = Math.incrementExact(presentationGeneration);
        updateHeading(player, view, pendingGroundDelta);
        visualTail(player);
    }

    private void visualTail(EntityPlayer player) {
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
        long nextGeneration = Math.incrementExact(presentationGeneration);
        predictedMotion = false;
        localFrame = null;
        localYawInitialized = false;
        localYaw = previousLocalYaw = 0;
        lastViewSequence = -1;
        actor = null;
        previous = null;
        eyePrevious = null;
        tickPosition = null;
        pendingGroundDistance = 0;
        pendingLimbDistance = 0;
        pendingGroundDelta = Vec3.ZERO;
        bodyOffset = previousBodyOffset = 0;
        presentationGeneration = nextGeneration;
    }
}
