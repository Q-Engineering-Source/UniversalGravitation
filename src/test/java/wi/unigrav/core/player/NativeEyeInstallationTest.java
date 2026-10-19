package wi.unigrav.core.player;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;

class NativeEyeInstallationTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000008751");
    @TempDir Path temp;

    @Test void cachedEffectiveDoesNotOverwriteRawAcrossPoseAndAttributeRecovery() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("cache"), PLAYER, false)) {
            var actor = actor(fixture);
            // Models the observed public getter contract, not an Aqua implementation:
            // its effective cache can lag independent writes to the Forge raw field.
            actor.cached = true;
            actor.cachedEye = .18333330750465393F;
            actor.height = 1.5F;
            actor.eyeHeight = .5333333F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, actor.cachedEye);
            assertBits(.5333333F, actor.eyeHeight);
            actor.height = 1.8F;
            actor.eyeHeight = 1.62F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, actor.cachedEye);
            assertBits(1.62F, actor.eyeHeight);
            actor.cachedEye = actor.eyeHeight; // the public profile subsequently refreshes its own cache
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, 1.62F);
            assertBits(1.62F, actor.eyeHeight);
            assertBits(1.62F, actor.getEyeHeight());
        }
    }

    @Test void restoredRawSurvivesLaggingEffectiveCacheThenRefreshes() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("restored-cache"), PLAYER, false)) {
            var actor = actor(fixture);
            actor.cached = true;
            actor.cachedEye = .18333330750465393F;
            actor.height = 1.8F;
            actor.eyeHeight = 1.62F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, actor.cachedEye);
            assertBits(1.62F, actor.eyeHeight);
            actor.cachedEye = actor.eyeHeight;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, 1.62F);
            assertBits(1.62F, actor.eyeHeight);
            assertBits(1.62F, actor.getEyeHeight());
        }
    }

    @Test void vanillaStandingAndSubtractedProfilesStillInstallUnequalEffective() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("native"), PLAYER, false)) {
            var actor = actor(fixture);
            actor.height = 1.8F;
            actor.eyeHeight = 1.62F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .75F);
            assertBits(.75F, actor.eyeHeight);
            assertBits(.75F, actor.getEyeHeight());
            actor.setSneaking(true);
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .5F);
            assertBits(.5F, actor.getEyeHeight());
            actor.setSneaking(false);
            actor.height = 1.65F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .7F);
            assertBits(.7F, actor.getEyeHeight());
        }
    }

    @Test void fixedNativeProfilesPreserveRawAndRejectImpossibleEffective() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("fixed"), PLAYER, false)) {
            var actor = actor(fixture);
            actor.eyeHeight = 1.62F;
            actor.sleeping = true;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .2F);
            assertBits(1.62F, actor.eyeHeight);
            assertThrows(IllegalStateException.class, () -> FreeOwnerTransaction.installEffectiveEyeHeight(actor, .3F));
            actor.sleeping = false;
            actor.height = .6F;
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .4F);
            assertBits(1.62F, actor.eyeHeight);
            assertThrows(IllegalStateException.class, () -> FreeOwnerTransaction.installEffectiveEyeHeight(actor, .3F));
            actor.height = 1.8F;
            actor.flying(true);
            FreeOwnerTransaction.installEffectiveEyeHeight(actor, .4F);
            assertBits(1.62F, actor.eyeHeight);
            assertThrows(IllegalStateException.class, () -> FreeOwnerTransaction.installEffectiveEyeHeight(actor, .3F));
        }
    }

    @Test void mismatchedCachedEffectiveFailsExplicitlyRatherThanClaimingAnInverse() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("mismatched-cache"), PLAYER, false)) {
            var actor = actor(fixture);
            actor.cached = true;
            actor.cachedEye = .4F;
            actor.eyeHeight = 1.62F;
            var failure = assertThrows(IllegalStateException.class,
                    () -> FreeOwnerTransaction.installEffectiveEyeHeight(actor, .75F));
            assertTrue(failure.getMessage().contains("did not round-trip exactly"));
            assertBits(.4F, actor.getEyeHeight());
        }
    }

    @Test void getterExceptionKeepsOriginalIdentityAndRawUntouched() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("throw"), PLAYER, false)) {
            var actor = actor(fixture);
            actor.eyeHeight = 1.62F;
            var cause = new IllegalStateException("original eye getter failure");
            actor.eyeFailure = cause;
            assertSame(cause, assertThrows(IllegalStateException.class,
                    () -> FreeOwnerTransaction.installEffectiveEyeHeight(actor, .75F)));
            assertBits(1.62F, actor.eyeHeight);
        }
    }

    @Test void transactionGetterReentryRejectsBeforeAnyRawWriteEvenForEqualEffective() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("reentry"), PLAYER, false)) {
            var actor = actor(fixture);
            var dimensions = new PlayerBodyShape.Dimensions((double) .6F, (double) 1.8F, (double) 1.62F);
            var placement = new PlayerBodyPlacement(new Vec3d(8.5, 68.9, 8.5),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            actor.setPosition(placement.transport().x(), placement.transport().y(), placement.transport().z());
            var bounds = placement.bounds();
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                    bounds.max().x(), bounds.max().y(), bounds.max().z()));
            var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(), 0, 1),
                    7, placement, new Vec3d(0, 0, 0));
            var current = new AtomicBoolean(true);
            var travel = new PlayerPhysicsStep.TravelState();
            var owner = new Object();
            var resources = new FreeTickResources.Store().acquire(owner, fixture.world(), accepted.scope(), 0);
            var ledger = new BoostMovementBudget(10, 0, new Vec3(actor.posX, actor.posY, actor.posZ));
            try (var tx = new FreeOwnerTransaction(actor, accepted, travel, ledger, current::get, owner, resources)) {
                actor.cached = true;
                actor.cachedEye = .75F;
                actor.eyeHeight = 1.62F;
                actor.afterEye = () -> current.set(false);
                assertThrows(IllegalStateException.class, () -> tx.installEffectiveEyeHeight(.75F));
                assertBits(1.62F, actor.eyeHeight);
            }
        }
    }

    private static EyePlayer actor(BootWorldFixture fixture) {
        fixture.serverPlayer(PLAYER); // initialize actual spawn/collision columns through the existing fixture
        var actor = new EyePlayer(fixture);
        new NetHandlerPlayServer(fixture.server(), new NetworkManager(EnumPacketDirection.SERVERBOUND), actor);
        return actor;
    }
    private static void assertBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
    private static final class EyePlayer extends EntityPlayerMP {
        boolean cached;
        float cachedEye;
        boolean sleeping;
        RuntimeException eyeFailure;
        Runnable afterEye;
        EyePlayer(BootWorldFixture fixture) {
            super(fixture.server(), fixture.world(), new GameProfile(PLAYER, "eye-installation-fixture"),
                    new PlayerInteractionManager(fixture.world()));
        }
        @Override public float getEyeHeight() {
            if (eyeFailure != null) throw eyeFailure;
            float result = cached ? cachedEye : super.getEyeHeight();
            Runnable callback = afterEye;
            afterEye = null;
            if (callback != null) callback.run();
            return result;
        }
        @Override public boolean isPlayerSleeping() { return sleeping; }
        void flying(boolean value) { setFlag(7, value); }
    }
}
