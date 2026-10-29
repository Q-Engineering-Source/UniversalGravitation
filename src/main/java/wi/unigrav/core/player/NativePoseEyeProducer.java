package wi.unigrav.core.player;

import java.util.Optional;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/** Read-only native pose producer observation; it grants no owner, geometry or installation authority. */
public interface NativePoseEyeProducer {
    record Profile(long revision, float cachedEye, float scale) {
        public Profile {
            if (revision < 0 || !Float.isFinite(cachedEye) || cachedEye < 0
                    || !Float.isFinite(scale) || scale <= 0 || !Float.isFinite(cachedEye * scale))
                throw new IllegalArgumentException("Invalid native cached-eye producer profile");
        }
        public float effectiveEye() { return cachedEye * scale; }
    }

    Optional<Profile> unigrav$nativePoseEyeProfile();
    /** Only the tail of a controlled real native eye-field write may finish its producer update.
     * This grants no geometry, owner, installation or acknowledgement authority. */
    interface NativeWriteCompletion extends NativePoseEyeProducer {
        void unigrav$completeNativeEyeWrite();
    }

    /** A real native adapter may mirror a proved standing successor during its owner mutation. */
    interface OwnedStandingMirror extends NativeWriteCompletion {
        void unigrav$installStandingEye(StandingEyeWrite write);
    }

    final class StandingEyeWrite {
        private final EntityPlayerMP actor;
        private final float rawEye;
        private final float effectiveEye;
        private final Runnable fence;
        StandingEyeWrite(EntityPlayerMP actor, float rawEye, float effectiveEye, Runnable fence) {
            this.actor = actor; this.rawEye = rawEye; this.effectiveEye = effectiveEye; this.fence = fence;
        }
        public void requireCurrent(EntityPlayer actor) {
            if (actor != this.actor) throw new IllegalArgumentException("Standing eye write belongs to another actor");
            fence.run();
        }
        public float rawEye() { return rawEye; }
        public float effectiveEye() { return effectiveEye; }
    }
}
