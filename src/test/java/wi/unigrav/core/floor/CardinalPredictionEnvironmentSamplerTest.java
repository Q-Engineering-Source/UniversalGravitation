package wi.unigrav.core.floor;

import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.client.CardinalPredictionEnvironmentSampler;
import wi.unigrav.core.client.CardinalPredictionStep;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalMoveFinalizer;
import wi.unigrav.core.player.CardinalPredictionSeed;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsProtocol;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CardinalPredictionEnvironmentSamplerTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static CardinalPredictionStep.State state(CardinalFrame frame, boolean grounded) {
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6, 1.8, 1.62));
        return new CardinalPredictionStep.State(new PlayerBodyPlacement(new Vec3d(8.5, 65, 8.5),
                frame.attitude(), shape), ZERO, new PlayerPhysicalSnapshot.Contacts(grounded, false, false, 0), 0);
    }
    private static PlayerPhysicsProtocol.Controls input(CardinalFrame frame, boolean sprint) {
        return new PlayerPhysicsProtocol.Controls(ZERO, frame.toWorld(new Vec3d(0, 0, 1)), false, false, sprint, false, 1);
    }
    private static CardinalPredictionSeed.Ready seed(CardinalFrame frame, Optional<GravityMode> mode,
            boolean zeroG, boolean noGravity) {
        return new CardinalPredictionSeed.Ready(4, 300, frame, new Vec3d(1, 2, 3), mode,
                mode.isPresent(), zeroG, noGravity, true, 70);
    }
    private static CardinalPredictionStep.Environment sampled(CardinalPredictionEnvironmentSampler.Result result) {
        return assertInstanceOf(CardinalPredictionEnvironmentSampler.Sampled.class, result).environment();
    }

    @Test void currentSprintAndPotionAreActuallySampled(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 812))) {
            fixture.installColumn(0, 0);
            var actor = fixture.actor(); actor.setPosition(8.5, 65, 8.5);
            var state = state(CardinalFrame.Y_POS, false);
            var seed = seed(CardinalFrame.Y_POS, Optional.empty(), false, false);
            double base = actor.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
            sampled(CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(), state, input(CardinalFrame.Y_POS, true), seed, false));
            assertTrue(actor.isSprinting());
            assertTrue(actor.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue() > base);
            actor.addPotionEffect(new PotionEffect(MobEffects.JUMP_BOOST, 100, 1));
            var environment = sampled(CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(), state,
                    input(CardinalFrame.Y_POS, false), seed, false));
            assertFalse(actor.isSprinting());
            assertEquals(base, actor.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue());
            assertEquals((double) actor.getJumpUpwardsMotion() + 2 * .1F, environment.jumpLaunch());
            assertEquals(actor.getAIMoveSpeed(), environment.aiMoveSpeed());
        }
    }

    @Test void serverSeedForcesComposeNativePolicyExactlyOnce(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 813))) {
            fixture.installColumn(0, 0); var actor = fixture.actor(); actor.setPosition(8.5, 65, 8.5);
            for (var mode : java.util.List.of(Optional.<GravityMode>empty(), Optional.of(GravityMode.ADDITIVE), Optional.of(GravityMode.REPLACE))) {
                for (boolean zeroG : new boolean[]{false, true}) for (boolean noGravity : new boolean[]{false, true})
                    for (boolean flying : new boolean[]{false, true}) {
                        actor.capabilities.isFlying = flying;
                        var seed = seed(CardinalFrame.Y_POS, mode, zeroG, noGravity);
                        var result = sampled(CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(),
                                state(CardinalFrame.Y_POS, false), input(CardinalFrame.Y_POS, false), seed, zeroG));
                        Vec3d expected = new Vec3d(1, 2, 3);
                        if (!zeroG && !noGravity && !flying && mode.filter(m -> m == GravityMode.REPLACE).isEmpty())
                            expected = expected.add(new Vec3d(0, -GravityEntityBinder.VANILLA_PLAYER_GRAVITY, 0));
                        assertEquals(expected, result.worldAcceleration());
                    }
            }
        }
    }

    @Test void sixAxesUseActualSupportSlipperinessAndCurrentForward(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 814))) {
            fixture.installColumn(0, 0); var actor = fixture.actor(); actor.setPosition(8.5, 65, 8.5);
            for (var frame : CardinalFrame.values()) {
                var state = state(frame, true);
                var bounds = state.placement().bounds();
                var box = new net.minecraft.util.math.AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                        bounds.max().x(), bounds.max().y(), bounds.max().z());
                BlockPos position = frame == CardinalFrame.Y_POS ? new BlockPos(actor.posX, box.minY - 1, actor.posZ)
                        : CardinalMoveFinalizer.physicalSupport(frame, box);
                fixture.put(position, Blocks.IRON_BLOCK.getDefaultState());
                var environment = sampled(CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(), state,
                        input(frame, true), seed(frame, Optional.empty(), false, false), false));
                assertEquals(.91F * Blocks.IRON_BLOCK.getSlipperiness(Blocks.IRON_BLOCK.getDefaultState(), fixture.world(), position, actor), environment.friction());
                assertEquals(frame.toWorld(new Vec3d(0, 0, (double) .2F)), environment.jumpSprintImpulse());
            }
        }
    }

    @Test void unknownBootsSpecialLandingAndMissingWorldAreExplicit(@TempDir Path path) {
        try (var fixture = BootWorldFixture.open(path, new UUID(0, 815))) {
            fixture.installColumn(0, 0); var actor = fixture.actor(); actor.setPosition(8.5, 65, 8.5);
            var state = state(CardinalFrame.Y_POS, false); var seed = seed(CardinalFrame.Y_POS, Optional.empty(), false, false);
            actor.setItemStackToSlot(EntityEquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            assertEquals(CardinalPredictionSeed.Reason.ACTIVE_BOOTS, assertInstanceOf(CardinalPredictionEnvironmentSampler.Unsupported.class,
                    CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(), state, input(CardinalFrame.Y_POS, false), seed, false)).reason());
            actor.setItemStackToSlot(EntityEquipmentSlot.FEET, ItemStack.EMPTY);
            var grounded = state(CardinalFrame.Y_POS, true);
            fixture.put(new BlockPos(8, 63, 8), Blocks.SLIME_BLOCK.getDefaultState());
            assertInstanceOf(CardinalPredictionEnvironmentSampler.UnsupportedSurface.class,
                    CardinalPredictionEnvironmentSampler.checkEndpoint(fixture.world(), grounded));
            var far = new CardinalPredictionStep.State(new PlayerBodyPlacement(new Vec3d(1000, 65, 1000),
                    grounded.placement().attitude(), grounded.placement().shape()), ZERO, grounded.contacts(), 0);
            assertEquals(CardinalPredictionSeed.Reason.WORLD_UNAVAILABLE, assertInstanceOf(CardinalPredictionEnvironmentSampler.Unavailable.class,
                    CardinalPredictionEnvironmentSampler.samplePlayer(actor, fixture.world(), far, input(CardinalFrame.Y_POS, false), seed, false)).reason());
            var cause = new IllegalStateException("original sample failure");
            var failingActor = new net.minecraft.entity.player.EntityPlayer(fixture.world(),
                    new com.mojang.authlib.GameProfile(new UUID(0, 816), "sampler-boundary")) {
                boolean rejectSprint;
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
                @Override public void setSprinting(boolean sprinting) {
                    if (rejectSprint) throw cause;
                    super.setSprinting(sprinting);
                }
            };
            failingActor.setPosition(8.5, 65, 8.5);
            failingActor.rejectSprint = true;
            var failed = assertInstanceOf(CardinalPredictionEnvironmentSampler.Unavailable.class,
                    CardinalPredictionEnvironmentSampler.samplePlayer(failingActor, fixture.world(), state,
                            input(CardinalFrame.Y_POS, true), seed, false));
            assertEquals(CardinalPredictionSeed.Reason.SAMPLING_FAILED, failed.reason());
            assertSame(cause, failed.cause().orElseThrow());
            assertTrue(java.util.Arrays.stream(cause.getStackTrace()).anyMatch(frame ->
                    frame.getMethodName().equals("unknownBootsSpecialLandingAndMissingWorldAreExplicit")));
            assertThrows(IllegalArgumentException.class, () -> new CardinalPredictionSeed.Ready(-1, 1, CardinalFrame.Y_POS,
                    ZERO, Optional.empty(), false, false, false, true, 70));
        }
    }
}
