package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeCardinalPoseProducerTest {
    @Test void flightToggleCanRetainAnIdenticalStandingInputButNotADifferentSneakTarget() {
        var flight = new NativeCardinalPoseProducer.Profile(PROFILE.standing(), PROFILE.crouching(),
                PROFILE.rawStandingEye(), false);
        assertTrue(PROFILE.sameForInput(flight, false));
        assertFalse(PROFILE.sameForInput(flight, true));
        var resizedTarget = new NativeCardinalPoseProducer.Profile(PROFILE.standing(),
                new PlayerBodyShape.Dimensions(.6, 1.4, 1.1), PROFILE.rawStandingEye(), true);
        assertFalse(PROFILE.sameForInput(resizedTarget, false), "matching current size cannot hide a changed profile");
    }
    private static final NativeCardinalPoseProducer.Profile PROFILE = new NativeCardinalPoseProducer.Profile(
            new PlayerBodyShape.Dimensions(.6, 1.8, 1.62),
            new PlayerBodyShape.Dimensions(.6, 1.5, 1.27), 1.62F, true);

    @Test void ordinaryConversionPreservesThePhysicalFootInAllSixFrames() {
        for (var frame : CardinalFrame.values()) {
            var standing = new PlayerBodyPlacement(frame.toWorld(new Vec3d(2, 5.9, 3)), frame.attitude(),
                    new PlayerBodyShape.Cardinal(frame, PROFILE.standing()));
            var crouched = NativeCardinalPoseProducer.resolve(standing, PROFILE, true, box -> List.of());
            assertEquals(standing.physicalFeet(), crouched.physicalFeet());
            assertEquals(PROFILE.crouching(), crouched.shape().dimensions());
            var risen = NativeCardinalPoseProducer.resolve(crouched, PROFILE, false, box -> List.of());
            assertEquals(crouched.physicalFeet(), risen.physicalFeet());
            assertEquals(PROFILE.standing(), risen.shape().dimensions());
        }
    }

    @Test void blockedStandingRetainsTheWholeAcceptedShortPoseAndUnknownDoesNotMeanClear() {
        var shortPose = new PlayerBodyPlacement(new Vec3d(0, .75, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, PROFILE.crouching()));
        assertSame(shortPose, NativeCardinalPoseProducer.resolve(shortPose, PROFILE, false,
                box -> List.of(new AxisAlignedBB(-1, 1.5, -1, 1, 2, 1))));
        var failure = new IllegalStateException("scene unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                NativeCardinalPoseProducer.resolve(shortPose, PROFILE, false, box -> { throw failure; })));
    }

    @Test void retainedFlyingSampleDoesNotShrinkOnVerticalSneakInput() {
        var standing = new PlayerBodyPlacement(new Vec3d(0, .9, 0), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, PROFILE.standing()));
        var flying = new NativeCardinalPoseProducer.Profile(PROFILE.standing(), PROFILE.crouching(), 1.62F, false);
        assertSame(standing, NativeCardinalPoseProducer.resolve(standing, flying, true,
                box -> { fail("No resize query is required for unchanged flight pose"); return List.of(); }));
    }
}
