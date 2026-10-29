package wi.unigrav.core.player;

import java.util.Optional;

/** Read-only Aqua adapter inputs. Neither a pose request nor collision/installation authority. */
public interface ProneBodyProducer {
    record Input(float standingEyeHeight, float standingRawEyeHeight, boolean forcedCrawl, boolean eyesInWater) {
        public Input {
            if (!Float.isFinite(standingEyeHeight) || standingEyeHeight < 0
                    || !Float.isFinite(standingRawEyeHeight) || standingRawEyeHeight < 0)
                throw new IllegalArgumentException("Standing eye source must be finite and nonnegative");
        }
    }
    Optional<Input> unigrav$proneBodyInput();
}
