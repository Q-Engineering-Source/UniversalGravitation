package wi.unigrav.core.player;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import wi.unigrav.api.math.Vec3;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMobilityIntentTest {
    private static PlayerMobilityIntent.Input held(long id) {
        return new PlayerMobilityIntent.Input(Optional.empty(), id, true, true);
    }
    private static PlayerMobilityIntent.Owner qualifiedOwner() {
        var owner = new PlayerMobilityIntent.Owner();
        owner.reconcileControl(true);
        owner.advance(1, 0, new PlayerMobilityIntent.Input(Optional.empty(), 0, false, true), true, 20);
        return owner;
    }
    @Test void nativeVerticalHeldReceiptExpiresOnCorrectionTimeoutAndFocusRelease() {
        var owner = new PlayerMobilityIntent.Owner();
        assertTrue(owner.advance(1, 1, held(1), true, 12).shortJump());
        assertTrue(owner.heldCurrent(held(1), 1));
        assertFalse(owner.advance(2, 2, held(1), true, 12).shortJump());
        assertTrue(owner.heldCurrent(held(1), 2));
        assertFalse(owner.heldCurrent(held(1), 3), "a read cannot advance simulation time");
        owner.correction();
        owner.advance(3, 3, held(1), true, 12);
        assertFalse(owner.heldCurrent(held(1), 3), "TP cannot remint the consumed physical press");
        owner.advance(4, 4, PlayerMobilityIntent.Input.idle(), true, 12);
        assertTrue(owner.advance(5, 5, held(2), true, 12).shortJump());
        assertTrue(owner.heldCurrent(held(2), 5));
        owner.advance(6, 5, held(2), false, 12);
        assertFalse(owner.heldCurrent(held(2), 6));
        owner.advance(7, 6, held(2), true, 12);
        assertFalse(owner.heldCurrent(held(2), 7));
        owner.advance(8, 7, PlayerMobilityIntent.Input.idle(), true, 12);
        owner.advance(9, 8, held(3), true, 12);
        assertTrue(owner.heldCurrent(held(3), 9));
        owner.advance(10, 9, new PlayerMobilityIntent.Input(Optional.empty(), 3, false, false), true, 12);
        assertFalse(owner.heldCurrent(held(3), 10));
    }

    @Test void shortPressJumpsImmediatelyAndHeldPressCannotRepeatAcrossCooldown() {
        var owner = new PlayerMobilityIntent.Owner();
        assertTrue(owner.advance(10, 1, held(1), true, 12).shortJump());
        for (int tick = 11; tick < 35; tick++)
            assertFalse(owner.advance(tick, tick, held(1), true, 12).shortJump());
        assertFalse(owner.blocksCapture(), "G-off held Space cannot activate backpack detachment");
        owner.reconcileControl(true);
        assertFalse(owner.advance(35, 40, held(1), true, 12).detachedNow(),
                "enabling G during the same Space press cannot inherit its hold time");
        owner.advance(36, 41, new PlayerMobilityIntent.Input(Optional.empty(), 1, false, true), true, 12);
        assertTrue(owner.advance(37, 42, held(2), true, 12).shortJump());
        assertFalse(owner.blocksCapture());
    }
    @Test void oneFreshHeldInputAdvancesOnlyActualSimulationTicksUntilTimeout() {
        var owner = qualifiedOwner();
        assertTrue(owner.advance(10, 1, held(1), true, 3).shortJump());
        assertEquals(PlayerMobilityIntent.Tick.NONE, owner.advance(10, 1, held(1), true, 3));
        assertFalse(owner.advance(11, 1, held(1), true, 3).detachedNow());
        assertTrue(owner.advance(12, 1, held(1), true, 3).detachedNow());
        assertFalse(owner.advance(13, 1, held(1), true, 3).detachedNow());
        var stale = qualifiedOwner();
        stale.advance(10, 1, held(1), true, 3);
        stale.advance(11, 1, held(1), false, 3);
        assertFalse(stale.advance(12, 1, held(1), true, 3).detachedNow());
        assertFalse(stale.blocksCapture());
    }
    @Test void packetsCannotCreateSimulationTicksOrExtendAStaleInput() {
        var owner = qualifiedOwner();
        owner.advance(10, 1, held(1), true, 3);
        for (int seq = 2; seq < 200; seq++) assertEquals(PlayerMobilityIntent.Tick.NONE,
                owner.advance(10, seq, held(1), true, 3));
        assertFalse(owner.blocksCapture());
        owner.advance(11, 200, held(1), false, 3);
        owner.advance(12, 201, held(1), true, 3);
        assertFalse(owner.blocksCapture());
        assertFalse(owner.advance(13, 202, held(1), true, 3).shortJump());
    }
    @Test void thresholdDetachesOnceAndRetainsExplicitHoverSelection() {
        var owner = qualifiedOwner();
        assertTrue(owner.accept(new PlayerMobilityIntent.Request(1, PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.OFF)));
        owner.advance(10, 1, held(1), true, 3);
        owner.advance(11, 2, held(1), true, 3);
        assertTrue(owner.advance(12, 3, held(1), true, 3).detachedNow());
        long generation = owner.generation();
        assertFalse(owner.advance(13, 4, held(1), true, 3).detachedNow());
        assertEquals(generation, owner.generation());
        assertEquals(PlayerMobilityIntent.Hover.OFF, owner.hover());
        assertFalse(owner.accept(new PlayerMobilityIntent.Request(2, PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.OFF)));
        owner.advance(14, 5, PlayerMobilityIntent.Input.idle(), true, 3);
        assertTrue(owner.accept(new PlayerMobilityIntent.Request(2, PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.OFF)));
        assertFalse(owner.blocksCapture());
    }
    @Test void exactRequestRetransmissionDoesNotChangeGenerationAndPayloadReuseFails() {
        var owner = qualifiedOwner();
        var request = new PlayerMobilityIntent.Request(5, PlayerMobilityIntent.Mode.FLIGHT, PlayerMobilityIntent.Hover.ON);
        assertTrue(owner.accept(request)); long generation = owner.generation();
        assertTrue(owner.accept(request)); assertEquals(generation, owner.generation());
        assertFalse(owner.accept(new PlayerMobilityIntent.Request(5, PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.ON)));
        owner.correction(); assertTrue(owner.generation() > generation);
        assertTrue(owner.blocksCapture());
    }
    @Test void releaseRequiredRequestRetriesSameIdOnlyInTheOriginalControlEpoch() {
        var owner = qualifiedOwner();
        owner.advance(2, 1, held(1), true, 20);
        var request = new PlayerMobilityIntent.Request(1,
                PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.OFF);
        assertFalse(owner.accept(request));
        assertEquals(2, owner.nextRequestId(), "the rejected id remains consumed while it awaits release");
        owner.advance(3, 2, PlayerMobilityIntent.Input.idle(), true, 20);
        assertTrue(owner.accept(request), "the exact request may complete after release in the same eligibility epoch");
        assertEquals(1, owner.state(PlayerMobilityIntent.EffectiveMode.WALK,
                PlayerMobilityIntent.HoverStatus.OFF, PlayerMobilityIntent.Reason.NONE).acceptedRequest());

        var revoked = qualifiedOwner();
        revoked.advance(2, 1, held(1), true, 20);
        assertFalse(revoked.accept(request));
        revoked.reconcileControl(false);
        revoked.advance(3, 2, PlayerMobilityIntent.Input.idle(), true, 20);
        revoked.reconcileControl(true);
        assertFalse(revoked.accept(request), "a prior-epoch id cannot be replayed after G eligibility returns");
        assertEquals(2, revoked.nextRequestId());
    }
    @Test void unqualifiedOwnerRejectsFlightAndHoverButConsumesRequestIdsAndAllowsSafeWalkOff() {
        var owner = new PlayerMobilityIntent.Owner();
        owner.reconcileControl(false);
        assertFalse(owner.accept(new PlayerMobilityIntent.Request(4,
                PlayerMobilityIntent.Mode.FLIGHT, PlayerMobilityIntent.Hover.ON)));
        assertEquals(5, owner.nextRequestId(), "a rejected mobility id cannot be reused");
        assertEquals(PlayerMobilityIntent.Mode.WALK, owner.mode());
        assertEquals(PlayerMobilityIntent.Hover.OFF, owner.hover());
        assertTrue(owner.accept(new PlayerMobilityIntent.Request(5,
                PlayerMobilityIntent.Mode.WALK, PlayerMobilityIntent.Hover.OFF)));
        assertFalse(owner.accept(new PlayerMobilityIntent.Request(4,
                PlayerMobilityIntent.Mode.FLIGHT, PlayerMobilityIntent.Hover.ON)));
        owner.reconcileControl(true);
        assertEquals(PlayerMobilityIntent.Mode.WALK, owner.mode(), "G activation does not restore stale flight intent");
    }
    @Test void timeoutAndCorrectionCannotCarryHoldTimeIntoAnotherPress() {
        var owner = qualifiedOwner();
        owner.advance(10, 1, held(1), true, 3); owner.advance(11, 2, held(1), true, 3);
        owner.correction();
        owner.advance(12, 3, held(2), true, 3);
        assertFalse(owner.blocksCapture());
        owner.advance(13, 4, held(2), false, 3);
        owner.advance(14, 5, held(3), true, 3);
        assertFalse(owner.blocksCapture());
    }
    @Test void dissipativeHoverBrakesAtConfiguredBoundWithoutOvershootOrEnergyInjection() {
        var result = PlayerMobilityIntent.hover(new Vec3(3, 4, 0), Vec3.ZERO, Vec3.ZERO,
                false, PlayerMobilityIntent.Hover.ON, false, 32, .05);
        assertEquals(3.4, result.velocity().norm(), 1e-12);
        assertEquals(PlayerMobilityIntent.HoverStatus.DISSIPATIVE, result.status());
        var near = PlayerMobilityIntent.hover(new Vec3(.01, 0, 0), Vec3.ZERO, Vec3.ZERO,
                false, PlayerMobilityIntent.Hover.ON, false, 32, .05);
        assertEquals(0, near.velocity().norm(), 1e-12);
        var off = PlayerMobilityIntent.hover(new Vec3(3, 4, 0), Vec3.ZERO, Vec3.ZERO,
                false, PlayerMobilityIntent.Hover.OFF, false, 32, .05);
        assertEquals(new Vec3(3, 4, 0), off.velocity());
    }
    @Test void personalCompensationCannotMasqueradeAsRawZeroGravity() {
        var result = PlayerMobilityIntent.hover(new Vec3(0, 2, 0), Vec3.ZERO, new Vec3(0, -32, 0),
                false, PlayerMobilityIntent.Hover.ON, false, 32, .05);
        assertEquals(PlayerMobilityIntent.HoverStatus.REJECTED, result.status());
        assertEquals(new Vec3(0, 2, 0), result.velocity());
        var source = PlayerMobilityIntent.hover(new Vec3(0, 2, 0), Vec3.ZERO, Vec3.ZERO,
                true, PlayerMobilityIntent.Hover.ON, false, 32, .05);
        assertEquals(PlayerMobilityIntent.HoverStatus.REJECTED, source.status());
    }
    @Test void onlyRealPoweredAuthorityMayCancelNonzeroForceAndUnexpectedValuesFail() {
        var powered = PlayerMobilityIntent.hover(new Vec3(0, -1.6, 0), new Vec3(0, -32, 0), new Vec3(0, -32, 0),
                false, PlayerMobilityIntent.Hover.ON, true, 32, .05);
        assertEquals(0, powered.velocity().norm(), 1e-12);
        assertEquals(PlayerMobilityIntent.HoverStatus.POWERED, powered.status());
        assertThrows(IllegalArgumentException.class, () -> PlayerMobilityIntent.hover(new Vec3(Double.NaN, 0, 0),
                Vec3.ZERO, Vec3.ZERO, false, PlayerMobilityIntent.Hover.ON, false, 32, .05));
    }
}
