package wi.unigrav.core.client.boost;

import org.junit.jupiter.api.Test;
import dev.crlhitbox.api.geometry.Vec3d;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.client.PlayerPhysicsInput;
import wi.unigrav.core.client.PlayerPhysicsClientPresentation;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyReference;

import static org.junit.jupiter.api.Assertions.*;

class PlayerPhysicsInputTest {
    @Test void nativeFlightUsesNfrHeadingAndKeepsWorldYAtThePole() {
        var turned=new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(1,0,0),new Vec3(0,0,-1));
        var input=PlayerPhysicsInput.keyboard(turned,1,0,true,false,false,false);
        assertEquals(new Vec3d(1,0,0),input.translation());assertTrue(input.jump());
        var pole=new PlayerPhysicsClientPresentation.ViewBasis(new Vec3(0,1,0),new Vec3(1,0,0));
        var upward=PlayerPhysicsInput.keyboard(pole,1,0,false,false,false,false);
        assertEquals(new Vec3d(0,0,1),upward.translation());
        assertEquals(new Vec3d(0,1,0),upward.forward());
    }
    @Test void keyboardUsesYawPlaneAndBoundsDiagonalWhileNormalizingLook() {
        var input = PlayerPhysicsInput.keyboard(90, 1, 1, new Vec3(0, .3, .4), true, false, true, true);
        assertEquals(-1 / Math.sqrt(2), input.translation().x(), 1e-7);
        assertEquals(0, input.translation().y());
        assertEquals(1 / Math.sqrt(2), input.translation().z(), 1e-7);
        assertEquals(.6, input.forward().y(), 1e-12);
        assertEquals(.8, input.forward().z(), 1e-12);
        assertTrue(input.jump());
        assertTrue(input.sprint());
        assertTrue(input.boost());
        assertEquals(5, input.boostGear());
        assertEquals(Math.sqrt(2.0), input.keyboardTravelGain(), 1e-15,
                "keyboard direction retains the native diagonal magnitude separately");
    }

    @Test void keyboardTravelGainTracksRawMagnitudeAndWorldInputKeepsUnitGain() {
        var cardinal = PlayerPhysicsInput.keyboard(37, 0, -1, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(1.0, cardinal.keyboardTravelGain(), 0);
        var partial = PlayerPhysicsInput.keyboard(0, .6, .8, new Vec3(0, 0, 1), false, false, false, false);
        assertEquals(1.0, partial.keyboardTravelGain(), 0,
                "sub-unit key axes keep native analog speed rather than being amplified");
        var world = PlayerPhysicsInput.world(new Vec3(.6, 0, .8), new Vec3(0, 0, 1),
                false, false, false, false, 5);
        assertEquals(1.0, world.keyboardTravelGain(), 0,
                "world and camera-relative callers carry no keyboard-only magnitude");
    }

    @Test void keyboardRejectsNonFiniteAndOutOfRangeRawAxes() {
        for (double[] axes : new double[][]{
                {Double.NaN, 0}, {0, Double.POSITIVE_INFINITY}, {1.000001, 0}, {-1.1, 0}
        }) {
            assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsInput.keyboard(0,
                    axes[0], axes[1], new Vec3(0, 0, 1), false, false, false, false));
        }
        assertThrows(IllegalArgumentException.class, () -> PlayerPhysicsInput.keyboard(Float.NaN,
                0, 0, new Vec3(0, 0, 1), false, false, false, false));
    }
    @Test void acceptedPhysicalUpDoesNotFreezeKeyboardHeadingWhenTheViewTurns() {
        for (var frame : CardinalFrame.values()) {
            var heading = PlayerBodyReference.initialHeading(frame, 0);
            var up = frame.up();
            var turned = new Vec3(up.y() * heading.z() - up.z() * heading.y(),
                    up.z() * heading.x() - up.x() * heading.z(),
                    up.x() * heading.y() - up.y() * heading.x());
            var reference = new PlayerBodyReference.Effective(
                    PlayerBodyReference.Source.MANUAL, frame, heading);
            var input = PlayerPhysicsInput.keyboard(reference, 1, 0, turned,
                    false, false, false, false);
            assertEquals(turned.x(), input.translation().x(), 1e-12, frame.name());
            assertEquals(turned.y(), input.translation().y(), 1e-12, frame.name());
            assertEquals(turned.z(), input.translation().z(), 1e-12, frame.name());
        }
    }    @Test void tangentKeyboardPreservesAnalogMagnitudeBoundsDiagonalAndCancelsOpposites() {
        for (var frame : CardinalFrame.values()) {
            var heading = PlayerBodyReference.initialHeading(frame, 0);
            var reference = new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT, frame, heading);
            var look = new Vec3(heading.x(), heading.y(), heading.z());
            var partial = PlayerPhysicsInput.keyboard(reference, .3, .4, look, false, false, false, false);
            assertEquals(.5, Math.sqrt(partial.translation().x() * partial.translation().x()
                    + partial.translation().y() * partial.translation().y()
                    + partial.translation().z() * partial.translation().z()), 1e-12);
            var diagonal = PlayerPhysicsInput.keyboard(reference, 1, 1, look, false, false, false, false);
            assertEquals(Math.sqrt(2), diagonal.keyboardTravelGain(), 1e-12);
            assertEquals(1, Math.sqrt(diagonal.translation().x() * diagonal.translation().x()
                    + diagonal.translation().y() * diagonal.translation().y()
                    + diagonal.translation().z() * diagonal.translation().z()), 1e-12);
            var cancelled = PlayerPhysicsInput.keyboard(reference, 0, 0, look, false, false, false, false);
            assertEquals(new Vec3d(0, 0, 0), cancelled.translation());
            var up = frame.up();
            var polar = PlayerPhysicsInput.keyboard(reference, 1, 0, new Vec3(up.x(), up.y(), up.z()),
                    false, false, false, false);
            assertEquals(heading, polar.translation(), "exact pole keeps the explicit accepted fallback");
        }
    }}
