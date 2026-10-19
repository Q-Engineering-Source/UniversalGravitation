package wi.unigrav.core.client;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMovementResolver;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CardinalPredictionStepTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static CardinalPredictionStep.WorkLedger ledger() {
        return new CardinalPredictionStep.WorkLedger(new CardinalMovementResolver.Limits(512, 16384, 100000));
    }
    private static CardinalPredictionStep.State state(CardinalFrame frame, Vec3d motion, boolean ground) {
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62));
        return new CardinalPredictionStep.State(new PlayerBodyPlacement(frame.toWorld(new Vec3d(0, .9, 0)),
                frame.attitude(), shape), motion, new PlayerPhysicalSnapshot.Contacts(ground, false, false, 0), 0);
    }
    private static CardinalPredictionStep.Environment env(CardinalFrame frame) {
        return new CardinalPredictionStep.Environment(frame.toWorld(new Vec3d(0, -32, 0)), .91F * .6F,
                .1F, .02F, false, .05F, (double) .42F, frame.toWorld(new Vec3d(0, 0, (double) .2F)),
                .6, 70, true, true, 0);
    }
    private static PlayerPhysicsProtocol.Controls controls(CardinalFrame frame, boolean forward, boolean jump, boolean sneak) {
        return new PlayerPhysicsProtocol.Controls(frame.toWorld(new Vec3d(0, 0, forward ? 1 : 0)),
                frame.toWorld(new Vec3d(0, 0, 1)), jump, sneak, false, false, 1);
    }
    private static Function<AxisAlignedBB, List<AxisAlignedBB>> terrain(CardinalFrame frame, Aabb... localBoxes) {
        return query -> java.util.Arrays.stream(localBoxes).map(frame::toWorld).map(b ->
                new AxisAlignedBB(b.min().x(), b.min().y(), b.min().z(), b.max().x(), b.max().y(), b.max().z()))
                .filter(query::intersects).toList();
    }
    private static final Aabb FLOOR = new Aabb(new Vec3d(-10, -1, -10), new Vec3d(10, 0, 10));
    private static CardinalPredictionStep.Predicted step(CardinalPredictionStep.State state, CardinalFrame frame,
            PlayerPhysicsProtocol.Controls controls, Function<AxisAlignedBB, List<AxisAlignedBB>> terrain,
            CardinalPredictionStep.WorkLedger ledger) {
        return assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(state, env(frame), controls,
                CardinalPredictionStep.Mode.NORMAL_DRY, terrain, ledger));
    }

    @Test void newInputMovesRealBoundsInAllSixFramesWithoutServerReply() {
        for (var frame : CardinalFrame.values()) {
            var start = state(frame, ZERO, true);
            var predicted = step(start, frame, controls(frame, true, false, false), terrain(frame, FLOOR), ledger());
            assertTrue(frame.toLocal(predicted.pose().placement().center()).z() > 0);
            assertNotEquals(start.placement().bounds(), predicted.pose().placement().bounds());
            assertTrue(frame.toLocal(predicted.pose().motionBlocksPerTick()).z() > 0);
        }
    }

    @Test void jumpIsImmediateThenLandsAndReleaseClearsCooldown() {
        for (var frame : CardinalFrame.values()) {
            var floor = terrain(frame, FLOOR);
            var first = step(state(frame, ZERO, true), frame, controls(frame, false, true, false), floor, ledger());
            assertTrue(first.jumped());
            assertTrue(frame.toLocal(first.pose().placement().center()).y() > .9);
            assertEquals(10, first.pose().jumpTicks());
            var current = step(first.pose(), frame, controls(frame, false, false, false), floor, ledger()).pose();
            assertEquals(0, current.jumpTicks());
            for (int tick = 0; tick < 30 && !current.contacts().onGround(); tick++)
                current = step(current, frame, controls(frame, false, false, false), floor, ledger()).pose();
            assertTrue(current.contacts().onGround());
            assertEquals(.9, frame.toLocal(current.placement().center()).y(), 1.0e-14);
        }
    }

    @Test void wallsStopMotionAndHalfBlockStepsUseRealResolver() {
        for (var frame : CardinalFrame.values()) {
            var wall = new Aabb(new Vec3d(-2, 0, .4), new Vec3d(2, 3, 1));
            var hit = step(state(frame, frame.toWorld(new Vec3d(0, 0, .3)), true), frame,
                    controls(frame, true, false, false), terrain(frame, FLOOR, wall), ledger());
            assertTrue(hit.pose().contacts().horizontal());
            assertEquals(0, frame.toLocal(hit.pose().motionBlocksPerTick()).z());
            var slab = new Aabb(new Vec3d(-2, 0, .4), new Vec3d(2, .5, 1));
            var climb = step(state(frame, frame.toWorld(new Vec3d(0, -.08, .3)), true), frame,
                    controls(frame, true, false, false), terrain(frame, FLOOR, slab), ledger());
            assertTrue(frame.toLocal(climb.pose().placement().center()).y() > .9);
        }
    }

    @Test void supportAndMovementShareLedgerAndFailureDoesNotRefund() {
        var frame = CardinalFrame.Y_POS;
        var ledger = new CardinalPredictionStep.WorkLedger(new CardinalMovementResolver.Limits(1, 16384, 100000));
        var input = controls(frame, true, false, false);
        var result = CardinalPredictionStep.step(state(frame, ZERO, true), env(frame), input,
                CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), ledger);
        assertInstanceOf(CardinalPredictionStep.Rejected.class, result);
        assertEquals(1, ledger.spent().queries());
        var spent = ledger.spent();
        assertInstanceOf(CardinalPredictionStep.Rejected.class, CardinalPredictionStep.step(state(frame, ZERO, true),
                env(frame), input, CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), ledger));
        assertEquals(spent, ledger.spent());
    }

    @Test void edgeSneakProtectsSupportAndReleaseDecelerates() {
        for (var frame : CardinalFrame.values()) {
            var edge = terrain(frame, new Aabb(new Vec3d(-2, -1, -2), new Vec3d(2, 0, .35)));
            var start = state(frame, frame.toWorld(new Vec3d(0, -.08, .8)), true);
            var normal = step(start, frame, controls(frame, false, false, false), edge, ledger());
            var sneak = step(start, frame, controls(frame, false, false, true), edge, ledger());
            assertTrue(frame.toLocal(sneak.pose().placement().center()).z()
                    < frame.toLocal(normal.pose().placement().center()).z());
            assertTrue(sneak.pose().contacts().onGround());
            var walking = step(state(frame, ZERO, true), frame, controls(frame, true, false, false), terrain(frame, FLOOR), ledger());
            var released = step(walking.pose(), frame, controls(frame, false, false, false), terrain(frame, FLOOR), ledger());
            assertTrue(frame.toLocal(released.pose().motionBlocksPerTick()).z()
                    < frame.toLocal(walking.pose().motionBlocksPerTick()).z());
        }
    }

    @Test void unavailableWorldRetainsOriginalCauseAndUnsupportedDoesNoWork() {
        var failure = new IllegalStateException("missing actual client chunk");
        var frame = CardinalFrame.Y_POS;
        var ledger = ledger();
        var result = assertInstanceOf(CardinalPredictionStep.Rejected.class, CardinalPredictionStep.step(state(frame, ZERO, true),
                env(frame), controls(frame, true, false, false), CardinalPredictionStep.Mode.NORMAL_DRY,
                bounds -> { throw failure; }, ledger));
        assertSame(failure, result.cause());
        var before = ledger.spent();
        assertInstanceOf(CardinalPredictionStep.Unsupported.class, CardinalPredictionStep.step(state(frame, ZERO, true),
                env(frame), controls(frame, true, false, false), CardinalPredictionStep.Mode.FLUID, terrain(frame), ledger));
        assertEquals(before, ledger.spent());
    }

    @Test void tailForceCannotExceedNativeSpeedCeiling() {
        for (var frame : CardinalFrame.values()) {
            var e = new CardinalPredictionStep.Environment(frame.toWorld(new Vec3d(0, -32, 0)), .546F,
                    .1F, .02F, false, .05F, .42, ZERO, .6, .1, true, true, 0, false);
            var predicted = assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(
                    state(frame, ZERO, false), e, controls(frame, false, false, false),
                    CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame), ledger()));
            Vec3d tail = frame.toWorld(new Vec3d(0, -32 * (.05 * .05) * .9800000190734863D, 0));
            double speed = Math.hypot(Math.hypot(tail.x(), tail.y()), tail.z());
            // Exact native cap arithmetic, including its final binary64 rounding.
            assertEquals(tail.multiply((.1 * .05) / speed), predicted.pose().motionBlocksPerTick());
        }
    }

    @Test void boundTangentEndpointRetainsOnlyZeroGapSupportAndAllowsNextJump() {
        for (var frame : CardinalFrame.values()) {
            var e = new CardinalPredictionStep.Environment(ZERO, .546F, .1F, .02F, false, .05F,
                    .42, ZERO, .6, 70, true, true, 0, true);
            for (boolean moving : new boolean[]{false, true}) {
                var start = state(frame, ZERO, true);
                var first = assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(start,
                        e, controls(frame, moving, false, false), CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), ledger()));
                assertTrue(first.pose().contacts().onGround());
                var jump = assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(first.pose(),
                        e, controls(frame, false, true, false), CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), ledger()));
                assertTrue(jump.jumped());
            }
            var start = state(frame, ZERO, false);
            var gap = new Aabb(new Vec3d(-10, -1, -10), new Vec3d(10, -.00005, 10));
            var result = assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(start, e,
                    controls(frame, false, false, false), CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, gap), ledger()));
            assertFalse(result.pose().contacts().onGround());
            assertEquals(start.placement().bounds(), result.pose().placement().bounds());
        }
    }

    @Test void endpointSupportUsesSameLedgerAndRequiresActiveBinding() {
        var frame = CardinalFrame.Y_POS;
        var e = new CardinalPredictionStep.Environment(ZERO, .546F, .1F, .02F, false, .05F,
                .42, ZERO, .6, 70, true, true, 0, true);
        var ledger = new CardinalPredictionStep.WorkLedger(new CardinalMovementResolver.Limits(2, 16384, 100000));
        assertInstanceOf(CardinalPredictionStep.Rejected.class, CardinalPredictionStep.step(state(frame, ZERO, true), e,
                controls(frame, false, false, false), CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), ledger));
        assertEquals(2, ledger.spent().queries());
        var unbound = new CardinalPredictionStep.Environment(ZERO, .546F, .1F, .02F, false, .05F,
                .42, ZERO, .6, 70, true, true, 0, false);
        var unboundLedger = ledger();
        var result = assertInstanceOf(CardinalPredictionStep.Predicted.class, CardinalPredictionStep.step(state(frame, ZERO, true), unbound,
                controls(frame, false, false, false), CardinalPredictionStep.Mode.NORMAL_DRY, terrain(frame, FLOOR), unboundLedger));
        assertFalse(result.pose().contacts().onGround());
        assertEquals(2, unboundLedger.spent().queries());
    }
}
