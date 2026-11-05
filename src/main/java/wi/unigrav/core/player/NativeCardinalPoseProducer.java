package wi.unigrav.core.player;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Native ordinary-pose samples, retained with an input rather than reread during replay. */
public interface NativeCardinalPoseProducer {
    record Profile(PlayerBodyShape.Dimensions standing, PlayerBodyShape.Dimensions crouching,
                   float rawStandingEye, boolean crouchAllowed) {
        public Profile {
            Objects.requireNonNull(standing); Objects.requireNonNull(crouching);
            if (!Float.isFinite(rawStandingEye) || rawStandingEye < 0)
                throw new IllegalArgumentException("Invalid native standing eye source");
        }
        public boolean represents(PlayerBodyShape.Dimensions dimensions) {
            return standing.equals(dimensions) || crouching.equals(dimensions);
        }
        public PlayerBodyShape.Dimensions desired(boolean sneak) {
            return sneak && crouchAllowed ? crouching : standing;
        }
        public boolean sameForInput(Profile other, boolean sneak) {
            return standing.equals(other.standing) && crouching.equals(other.crouching)
                    && Float.floatToIntBits(rawStandingEye) == Float.floatToIntBits(other.rawStandingEye)
                    && desired(sneak).equals(other.desired(sneak));
        }
    }

    Optional<Profile> unigrav$cardinalPoseProfile();

    static Optional<Profile> sample(EntityPlayer actor, PlayerBodyPlacement placement) {
        if (!(placement.shape() instanceof PlayerBodyShape.Cardinal cardinal)
                || !(actor instanceof NativeCardinalPoseProducer producer)
                || actor.isDead || actor.noClip || actor.isRiding() || actor.isPlayerSleeping()
                || actor.isSpectator() || actor.isInWater() || actor.isInLava()
                || actor.isElytraFlying() || actor.isOnLadder()) return Optional.empty();
        return producer.unigrav$cardinalPoseProfile().filter(profile -> profile.represents(cardinal.dimensions()));
    }

    /** Aqua's ordinary fit policy: retain the current pose when the complete target is occupied.
     * The query must preserve real collider filtering; an unavailable query must throw, not return empty.
     */
    static PlayerBodyPlacement resolve(PlayerBodyPlacement current, Profile profile, boolean sneak,
                                       Function<AxisAlignedBB, List<AxisAlignedBB>> collisions) {
        Objects.requireNonNull(current); Objects.requireNonNull(profile); Objects.requireNonNull(collisions);
        if (!(current.shape() instanceof PlayerBodyShape.Cardinal)
                || !profile.represents(current.shape().dimensions()))
            throw new IllegalArgumentException("Native pose sample does not represent this input's collider");
        var dimensions = profile.desired(sneak);
        if (dimensions.equals(current.shape().dimensions())) return current;
        var candidate = current.proposeDimensionsAtFeet(dimensions);
        var bounds = candidate.bounds();
        var box = new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z());
        return collisions.apply(box).isEmpty() ? candidate : current;
    }
}
