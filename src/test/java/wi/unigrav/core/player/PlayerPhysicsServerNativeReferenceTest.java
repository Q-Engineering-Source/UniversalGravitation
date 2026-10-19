package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.crlhitbox.api.geometry.Vec3d;

final class PlayerPhysicsServerNativeReferenceTest {
    @Test void freeWithoutPreviousEffectiveUsesTheExistingInitialYPlusHeadingPolicy() {
        var manual = new PlayerBodyReference.ManualIntent(7, CardinalFrame.X_POS, new Vec3d(0, 0, 1));
        var free = new PlayerBodyReference.FreeReference(Optional.of(manual), Optional.empty(), Optional.empty());

        var prepared = PlayerPhysicsServer.prepareSafeNativeReference(free, 37.0F,
                PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING);

        assertEquals(Optional.of(manual), prepared.manualIntent());
        assertEquals(PlayerBodyReference.Source.SAFE_NATIVE, prepared.effective().source());
        assertEquals(CardinalFrame.Y_POS, prepared.effective().frame());
        assertEquals(PlayerBodyReference.initialHeading(CardinalFrame.Y_POS, 37.0F),
                prepared.effective().tangentHeading());
        assertEquals(PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING, prepared.transition());
        assertEquals(Optional.empty(), prepared.pendingFrame());
        assertEquals(Optional.empty(), prepared.reason());
        assertEquals(Optional.empty(), free.previousEffective(), "preparation must not invent Free effective state");
    }

    @Test void freeWithPreviousEffectiveTransportsItsHeadingAndRetainsManualIntent() {
        var manual = new PlayerBodyReference.ManualIntent(9, CardinalFrame.Z_NEG, new Vec3d(0, 1, 0));
        var previous = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT,
                CardinalFrame.X_POS, new Vec3d(0, 0, 1));
        var free = new PlayerBodyReference.FreeReference(Optional.of(manual), Optional.empty(), Optional.of(previous));

        var prepared = PlayerPhysicsServer.prepareSafeNativeReference(free, -81.0F,
                PlayerBodyReference.Transition.MANUAL_DEFERRED);

        assertEquals(Optional.of(manual), prepared.manualIntent());
        assertEquals(PlayerBodyReference.transportHeading(CardinalFrame.X_POS, CardinalFrame.Y_POS,
                previous.tangentHeading()), prepared.effective().tangentHeading());
        assertEquals(PlayerBodyReference.Transition.MANUAL_DEFERRED, prepared.transition());
        assertEquals(Optional.of(previous), free.previousEffective());
    }

    @Test void cardinalPreparationRetainsItsAcceptedHeadingAndManualIntent() {
        var manual = new PlayerBodyReference.ManualIntent(11, CardinalFrame.Y_NEG, new Vec3d(1, 0, 0));
        var effective = new PlayerBodyReference.Effective(PlayerBodyReference.Source.MANUAL,
                CardinalFrame.Z_POS, new Vec3d(0, 1, 0));
        var previous = new PlayerBodyReference.State(Optional.of(manual), effective,
                PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
        var reference = new PlayerBodyReference.CardinalReference(previous);

        var prepared = PlayerPhysicsServer.prepareSafeNativeReference(reference, 12.0F,
                PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING);

        assertEquals(Optional.of(manual), prepared.manualIntent());
        assertEquals(PlayerBodyReference.transportHeading(CardinalFrame.Z_POS, CardinalFrame.Y_POS,
                effective.tangentHeading()), prepared.effective().tangentHeading());
        assertEquals(PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING, prepared.transition());
        assertSame(previous, reference.cardinalState().orElseThrow());
    }

    @Test void freeWithoutPreviousEffectiveRejectsNonFiniteYawWithoutChangingItsReference() {
        var free = new PlayerBodyReference.FreeReference(Optional.empty(), Optional.empty(), Optional.empty());
        var before = free;

        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsServer.prepareSafeNativeReference(free,
                Float.NaN, PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING));

        assertSame(before, free);
        assertEquals(Optional.empty(), free.previousEffective());
    }
}
