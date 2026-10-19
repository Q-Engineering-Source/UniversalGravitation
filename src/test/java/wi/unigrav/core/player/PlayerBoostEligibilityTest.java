package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerBoostEligibilityTest {
    @Test void sampledSprintKeepsAllIntentExceptIneligibleBoost() {
        var raw = new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,1),new Vec3d(0,0,1),
                true,false,true,true,5);
        for (boolean personalG : new boolean[]{false,true}) {
            for (boolean creative : new boolean[]{false,true}) {
                var normalized = PlayerBoostEligibility.normalize(raw,personalG,creative);
                assertEquals(personalG && creative, normalized.boost());
                assertEquals(raw.translation(),normalized.translation());
                assertEquals(raw.forward(),normalized.forward());
                assertEquals(raw.jump(),normalized.jump()); assertEquals(raw.sprint(),normalized.sprint());
                assertEquals(raw.sneak(),normalized.sneak()); assertEquals(raw.mobility(),normalized.mobility());
                assertEquals(raw.bodyReferenceIntent(),normalized.bodyReferenceIntent());
                assertEquals(raw.desiredBodyAttitude(),normalized.desiredBodyAttitude());
                assertEquals(raw.keyboardTravelGain(),normalized.keyboardTravelGain());
                assertEquals(raw.boostGear(),normalized.boostGear());
                if (personalG && creative) assertSame(raw,normalized);
                assertEquals(PlayerBoostEligibility.propulsionAllowed(personalG,creative),
                        PlayerBoostEligibility.requested(raw.boost(),personalG,creative));
            }
        }
    }
    @Test void doubleForwardSprintWithoutBoostIsNeverRewritten() {
        var raw = new PlayerPhysicsProtocol.Controls(new Vec3d(0,0,1),new Vec3d(0,0,1),
                false,false,true,false,5);
        assertSame(raw,PlayerBoostEligibility.normalize(raw,false,true));
        assertSame(raw,PlayerBoostEligibility.normalize(raw,true,true));
        assertFalse(PlayerBoostEligibility.requested(false,true,true));
    }
}
