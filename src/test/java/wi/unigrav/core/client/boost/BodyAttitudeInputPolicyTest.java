package wi.unigrav.core.client.boost;

import dev.crlhitbox.api.geometry.Rotation3d;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.BodyAttitudeInputPolicy;
import wi.unigrav.core.client.PlayerPhysicsInput;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import static org.junit.jupiter.api.Assertions.*;

class BodyAttitudeInputPolicyTest {
    @Test void acceptedManualOrEnvironmentUpStripsOnlyPassiveBodyQFromActualControls() {
        var q = new Rotation3d(0, 0, 0, 1);
        var original = PlayerPhysicsInput.world(new Vec3(.3, 0, .4), new Vec3(1, 0, 0),
                true, true, true, true, 7, Optional.of(q));
        for (var source : new PlayerBodyReference.Source[] {
                PlayerBodyReference.Source.MANUAL, PlayerBodyReference.Source.ENVIRONMENT}) {
            for (var frame : CardinalFrame.values()) {
                var reference = new PlayerBodyReference.Effective(source, frame,
                        PlayerBodyReference.initialHeading(frame, 0));
                var guarded = BodyAttitudeInputPolicy.apply(original, true, Optional.of(reference));
                assertTrue(guarded.desiredBodyAttitude().isEmpty());
                assertEquals(original.translation(), guarded.translation());
                assertEquals(original.forward(), guarded.forward());
                assertEquals(original.jump(), guarded.jump());
                assertEquals(original.sneak(), guarded.sneak());
                assertEquals(original.sprint(), guarded.sprint());
                assertEquals(original.boost(), guarded.boost());
                assertEquals(original.boostGear(), guarded.boostGear());
                assertEquals(original.keyboardTravelGain(), guarded.keyboardTravelGain());
                assertEquals(original.bodyReferenceIntent(), guarded.bodyReferenceIntent());
            }
        }
    }

    @Test void disabledInputCannotSendPassiveQButEnabledFreeKeepsItEvenWithoutMovementKeys() {
        var original = PlayerPhysicsInput.world(Vec3.ZERO, new Vec3(0, 0, 1),
                false, false, false, false, 5, Optional.of(new Rotation3d(0, 0, 0, 1)));
        assertTrue(BodyAttitudeInputPolicy.apply(original, false, Optional.empty()).desiredBodyAttitude().isEmpty());
        assertEquals(original, BodyAttitudeInputPolicy.apply(original, true, Optional.empty()));
        for (var source : new PlayerBodyReference.Source[] {
                PlayerBodyReference.Source.NATURAL, PlayerBodyReference.Source.SAFE_NATIVE}) {
            var reference = new PlayerBodyReference.Effective(source, CardinalFrame.Y_POS,
                    PlayerBodyReference.initialHeading(CardinalFrame.Y_POS, 0));
            assertEquals(original, BodyAttitudeInputPolicy.apply(original, true, Optional.of(reference)));
        }
    }
    @Test void guardPreservesExplicitLockAndUnlockIntents() {
        var heading = PlayerBodyReference.initialHeading(CardinalFrame.Y_POS, 0);
        var reference = new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL,
                CardinalFrame.Y_POS, heading);
        var base = PlayerPhysicsInput.world(Vec3.ZERO, new Vec3(0, 0, 1),
                false, false, false, false, 5, Optional.of(new Rotation3d(0, 0, 0, 1)));
        for (var intent : new PlayerPhysicsProtocol.BodyReferenceIntent[] {
                PlayerPhysicsProtocol.BodyReferenceIntent.lock(41, CardinalFrame.Y_POS, heading),
                PlayerPhysicsProtocol.BodyReferenceIntent.unlock(42)}) {
            var controls = new PlayerPhysicsProtocol.Controls(base.translation(), base.forward(),
                    false, false, false, false, 5, 1, intent, base.desiredBodyAttitude());
            var guarded = BodyAttitudeInputPolicy.apply(controls, true, Optional.of(reference));
            assertEquals(intent, guarded.bodyReferenceIntent());
            assertTrue(guarded.desiredBodyAttitude().isEmpty());
        }
    }}