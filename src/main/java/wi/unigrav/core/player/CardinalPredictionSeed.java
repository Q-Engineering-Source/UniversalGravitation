package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.core.GravityMode;

import java.util.Objects;
import java.util.Optional;

/** Server-observed environment only; no native gravity, actor attributes or installation authority. */
public sealed interface CardinalPredictionSeed {
    enum Reason {
        UNSUPPORTED_SHAPE, UNSUPPORTED_STATE, ACTIVE_BOOTS, WORLD_UNAVAILABLE, ACTOR_CHANGED,
        PERSONAL_G_MISMATCH, NATIVE_BLOCK_EFFECT, INVALID_PARAMETERS, SAMPLING_FAILED
    }
    record Ready(long snapshotRevision, long worldTick, CardinalFrame frame, Vec3d environmentAcceleration,
                 Optional<GravityMode> bindingMode, boolean bindingActive, boolean personalZeroG,
                 boolean savedNativeNoGravity, boolean standingReference, double ceilingMps)
            implements CardinalPredictionSeed {
        public Ready {
            Objects.requireNonNull(frame); Objects.requireNonNull(environmentAcceleration); Objects.requireNonNull(bindingMode);
            if (snapshotRevision < 0 || worldTick < 0 || bindingActive && bindingMode.isEmpty()
                    || !Double.isFinite(environmentAcceleration.x()) || !Double.isFinite(environmentAcceleration.y())
                    || !Double.isFinite(environmentAcceleration.z()) || !Double.isFinite(ceilingMps) || ceilingMps <= 0)
                throw new IllegalArgumentException("Invalid prediction environment seed");
        }
    }
    record Unsupported(Reason reason) implements CardinalPredictionSeed {
        public Unsupported { Objects.requireNonNull(reason); }
    }
    record Unavailable(Reason reason, Optional<RuntimeException> cause) implements CardinalPredictionSeed {
        public Unavailable { Objects.requireNonNull(reason); Objects.requireNonNull(cause); }
    }
}
