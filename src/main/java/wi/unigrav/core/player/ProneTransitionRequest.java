package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Objects;
import java.util.Optional;

/** Same-tick swim/crawl intent. The ordinary mode path still has to prove every physical phase. */
record ProneTransitionRequest(FreeOwnerTransaction transaction, NativeCardinalBodyProfile.Value body,
        ProneBodyProducer producer, ProneBodyProducer.Input input,
        Optional<PlayerBodyShape.PronePose> requestedPose, Rotation3d rawDesiredQ)
        implements ModeTransitionPrepared.Request {
    ProneTransitionRequest {
        Objects.requireNonNull(transaction); Objects.requireNonNull(body); Objects.requireNonNull(producer);
        Objects.requireNonNull(input); Objects.requireNonNull(requestedPose); Objects.requireNonNull(rawDesiredQ);
        if (body.actor() != transaction.actor() || body.actor() != producer)
            throw new IllegalArgumentException("Prone intent belongs to another body producer");
    }

    boolean current() {
        transaction.requireCurrent();
        boolean result = body.current() && producer.unigrav$proneBodyInput().filter(input::equals).isPresent();
        transaction.requireCurrent();
        return result;
    }

    PlayerBodyShape.FreeCapsule shape(PlayerBodyPlacement placement) {
        var source = (PlayerBodyShape.FreeCapsule) placement.shape();
        var anchor = source.eyeAnchor().or(() -> Optional.of(new FreeEyeAnchor(FreeEyeAnchor.STORED_BASIS_V1,
                placement.center(), placement.eye(), placement.attitude())));
        return new PlayerBodyShape.FreeCapsule(source.dimensions(), source.collisionProfile(), anchor, requestedPose);
    }
}
