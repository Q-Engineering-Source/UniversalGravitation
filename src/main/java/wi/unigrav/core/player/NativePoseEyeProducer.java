package wi.unigrav.core.player;

import java.util.Optional;

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
}
