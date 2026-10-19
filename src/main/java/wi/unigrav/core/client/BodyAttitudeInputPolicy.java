package wi.unigrav.core.client;

import java.util.Optional;
import java.util.Objects;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

/** Separates passive camera attitude from accepted physical standing reference. */
public final class BodyAttitudeInputPolicy {
    private BodyAttitudeInputPolicy() { }

    public static PlayerPhysicsProtocol.Controls apply(PlayerPhysicsProtocol.Controls controls,
            boolean inputEnabled, Optional<PlayerBodyReference.Effective> acceptedReference) {
        return apply(controls, inputEnabled, acceptedReference, false, false);
    }

    /**
     * Keeps the camera quaternion out of a standing Cardinal WALK body update. A confirmed
     * FLIGHT mode remains an explicit body-attitude intent, including while an earlier
     * Cardinal reference is still being handed off to Free.
     */
    public static PlayerPhysicsProtocol.Controls apply(PlayerPhysicsProtocol.Controls controls,
            boolean inputEnabled, Optional<PlayerBodyReference.Effective> acceptedReference,
            boolean cardinalWalkHeadLookOnly, boolean confirmedFlight) {
        Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(acceptedReference, "acceptedReference");
        if (controls.desiredBodyAttitude().isEmpty()) return controls;
        if (cardinalWalkHeadLookOnly) return withoutBodyAttitude(controls);
        if (!inputEnabled) return withoutBodyAttitude(controls);
        if (confirmedFlight) return controls;
        boolean standingReference = acceptedReference.map(reference ->
                reference.source() == PlayerBodyReference.Source.MANUAL
                        || reference.source() == PlayerBodyReference.Source.ENVIRONMENT).orElse(false);
        if (inputEnabled && !standingReference) return controls;
        return withoutBodyAttitude(controls);
    }

    private static PlayerPhysicsProtocol.Controls withoutBodyAttitude(PlayerPhysicsProtocol.Controls controls) {
        return new PlayerPhysicsProtocol.Controls(controls.translation(), controls.forward(),
                controls.jump(), controls.sneak(), controls.sprint(), controls.boost(), controls.boostGear(),
                controls.keyboardTravelGain(), controls.bodyReferenceIntent(), Optional.empty(), controls.mobility());
    }
}
