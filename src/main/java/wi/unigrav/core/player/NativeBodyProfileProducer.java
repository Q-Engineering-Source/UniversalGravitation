package wi.unigrav.core.player;

import java.util.Optional;

/** Native pose-independent dimensions only; this observation grants no owner or installation authority. */
public interface NativeBodyProfileProducer {
    enum Source { VANILLA_CONSTRUCTOR, AQUA_STANDING }

    record Profile(Source source, float baseDiameter, float baseLength, float diameter, float length) {
        public Profile {
            if (source == null || !Float.isFinite(baseDiameter) || baseDiameter <= 0
                    || !Float.isFinite(baseLength) || baseLength < baseDiameter
                    || !Float.isFinite(diameter) || diameter <= 0
                    || !Float.isFinite(length) || length < diameter)
                throw new IllegalArgumentException("Native body profile must be a finite full-length capsule");
        }
    }

    /** The producer must read its real native source; raw pose height is not a standing-body source. */
    Optional<Profile> unigrav$nativeBodyProfile();
}
