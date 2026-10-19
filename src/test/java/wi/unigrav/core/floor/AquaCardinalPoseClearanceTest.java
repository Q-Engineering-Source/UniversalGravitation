package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.UUID;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.ai.attributes.RangedAttribute;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.player.AquaCardinalPoseClearance;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

import static org.junit.jupiter.api.Assertions.*;

class AquaCardinalPoseClearanceTest {
    private static final Vec3d ZERO = new Vec3d(0, 0, 0);
    private static final UUID SESSION = new UUID(0, 82002);
    private static final UUID AQUA_SWIMMING_HEIGHT_MODIFIER =
            UUID.fromString("F5ED4D20-4DAB-11EB-AE93-0242AC130002");

    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    @Test
    void standingBodyProfileUsesNativeFloatProductsWithoutMutatingTheLiveSwimModifier(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82101), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82102));
            float baseDiameter = player.width, baseLength = player.height;
            installArtemisAttributes(player, .5, 1.5, true);
            var heightAttribute = player.getAttributeMap().getAttributeInstanceByName("artemislib.entityHeight");
            var swimModifier = heightAttribute.getModifier(AQUA_SWIMMING_HEIGHT_MODIFIER);
            player.height = .5999999642372131F;
            var rawHeight = player.height;
            var profile = AquaCardinalPoseClearance.standingBodyProfile(player, baseDiameter, baseLength);
            assertEquals(Float.floatToRawIntBits((float) (baseDiameter * .5)), Float.floatToRawIntBits(profile.diameter()));
            assertEquals(Float.floatToRawIntBits((float) (baseLength * 1.5)), Float.floatToRawIntBits(profile.length()));
            assertEquals(Float.floatToRawIntBits(rawHeight), Float.floatToRawIntBits(player.height));
            assertSame(swimModifier, heightAttribute.getModifier(AQUA_SWIMMING_HEIGHT_MODIFIER));
            assertTrue(profile.length() > player.height);
        }
    }

    @Test
    void standingBodyProfileWithoutArtemisKeepsActualNativeBaseBits(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82111), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82112));
            float width = player.width, length = player.height;
            var profile = AquaCardinalPoseClearance.standingBodyProfile(player, width, length);
            assertEquals(Float.floatToRawIntBits(width), Float.floatToRawIntBits(profile.diameter()));
            assertEquals(Float.floatToRawIntBits(length), Float.floatToRawIntBits(profile.length()));
            assertEquals(Float.floatToRawIntBits(width), Float.floatToRawIntBits(profile.baseDiameter()));
            assertEquals(Float.floatToRawIntBits(length), Float.floatToRawIntBits(profile.baseLength()));
        }
    }

    @Test
    void cardinalCandidateUsesResizeAttributesOnceAndKeepsAcceptedFootForEveryAxis(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82001), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82003));
            installArtemisAttributes(player, .5, .5, false);
            AxisAlignedBB aquaStanding = new AxisAlignedBB(7.7, 95.4, 7.7, 8.3, 97.2, 8.3);

            for (CardinalFrame frame : CardinalFrame.values()) {
                PlayerPhysicalSnapshot accepted = accepted(player, frame, .3, .3, frame == CardinalFrame.Y_NEG
                        ? new Vec3d(8, 95.85, 8) : new Vec3d(8, 65, 8));
                AxisAlignedBB result = AquaCardinalPoseClearance.candidate(player, accepted, aquaStanding);
                var expected = accepted.placement().proposeDimensionsAtFeet(
                        new PlayerBodyShape.Dimensions(.3, .9, .2)).bounds();

                assertBounds(expected, result, frame);
                assertEquals(.3, extentAlongTangentX(result, frame), 1.0e-12,
                        frame + " candidate width applies the Resize potion multiplier once");
                assertEquals(.9, extentAlongBodyUp(result, frame), 1.0e-12,
                        frame + " candidate height applies Artemis resize scale once");
                assertEquals(accepted.placement().physicalFeet().orElseThrow(), physicalFeet(result, frame),
                        frame + " clearance keeps the accepted cardinal foot");
            }
        }
    }

    @Test
    void aquaSwimmingModifierDoesNotDoubleScaleStandingOrSwimmingPoseCandidates(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82031), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82032));
            installArtemisAttributes(player, .5, .5, true);
            IAttributeInstance swimmingHeight = player.getAttributeMap()
                    .getAttributeInstanceByName("artemislib.entityHeight");
            PlayerPhysicalSnapshot accepted = accepted(player, CardinalFrame.Y_NEG, .3, .3,
                    new Vec3d(8, 95.85, 8));
            AxisAlignedBB aquaStanding = new AxisAlignedBB(7.7, 95.4, 7.7, 8.3, 97.2, 8.3);
            AxisAlignedBB aquaSwimming = new AxisAlignedBB(7.7, 95.4, 7.7, 8.3, 96.0, 8.3);

            AxisAlignedBB standing = AquaCardinalPoseClearance.candidate(player, accepted, aquaStanding);
            AxisAlignedBB swimming = AquaCardinalPoseClearance.candidate(player, accepted, aquaSwimming);

            assertEquals(.3, standing.maxX - standing.minX, 1.0e-12);
            assertEquals(.9, standing.maxY - standing.minY, 1.0e-12,
                    "standing candidate uses the Resize scale after excluding the exact Aqua swimming modifier");
            assertEquals(.3, swimming.maxX - swimming.minX, 1.0e-12);
            assertEquals(.3, swimming.maxY - swimming.minY, 1.0e-12,
                    "Aqua's 0.6 swimming base already accounts for pose; the Resize scale is applied only once");
            assertNotNull(swimmingHeight.getModifier(AQUA_SWIMMING_HEIGHT_MODIFIER),
                    "clearance computation must not mutate Aqua's live swimming attribute modifier");

            EntityPlayerMP standingPlayer = fixture.serverPlayer(new UUID(0, 82033));
            installArtemisAttributes(standingPlayer, .5, .5, false);
            PlayerPhysicalSnapshot standingAccepted = accepted(standingPlayer, CardinalFrame.Y_NEG, .3, .9,
                    new Vec3d(8, 95.55, 8));
            AxisAlignedBB candidateSwimmingFromStanding = AquaCardinalPoseClearance.candidate(
                    standingPlayer, standingAccepted, aquaSwimming);
            assertEquals(.3, candidateSwimmingFromStanding.maxY - candidateSwimmingFromStanding.minY, 1.0e-12,
                    "a standing source has no Aqua modifier; candidate swimming uses 0.6 base × 0.5 external scale");
            assertNull(standingPlayer.getAttributeMap().getAttributeInstanceByName("artemislib.entityHeight")
                    .getModifier(AQUA_SWIMMING_HEIGHT_MODIFIER),
                    "candidate-pose evaluation must not install a swimming modifier");
        }
    }

    @Test
    void standingSwimmingAndCrouchingCandidatesUseEachAquaBaseTimesOneResizeScale(@TempDir Path directory) {
        double[] scales = {1.0, .5, 1.5};
        double[] poseHeights = {1.8, .6, 1.5};
        String[] poses = {"standing", "swimming", "crouching"};
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82034), true)) {
            long id = 82040;
            for (int scaleIndex = 0; scaleIndex < scales.length; scaleIndex++) {
                double scale = scales[scaleIndex];
                EntityPlayerMP player = fixture.serverPlayer(new UUID(0, id++));
                installArtemisAttributes(player, scale, scale, false);
                PlayerPhysicalSnapshot accepted = accepted(player, CardinalFrame.Y_NEG, .6 * scale,
                        1.8 * scale, new Vec3d(8, 95.1, 8));
                for (int poseIndex = 0; poseIndex < poseHeights.length; poseIndex++) {
                    double baseHeight = poseHeights[poseIndex];
                    AxisAlignedBB aquaCandidate = new AxisAlignedBB(7.7, 95.4, 7.7,
                            8.3, 95.4 + baseHeight, 8.3);
                    AxisAlignedBB result = AquaCardinalPoseClearance.candidate(player, accepted, aquaCandidate);
                    assertEquals(.6 * scale, result.maxX - result.minX, 1.0e-12,
                            poses[poseIndex] + " width at resize scale " + scale);
                    assertEquals(baseHeight * scale, result.maxY - result.minY, 1.0e-12,
                            poses[poseIndex] + " Aqua base height at resize scale " + scale);
                }
            }
        }
    }

    @Test
    void aquaSwimmingCandidateAtReportedZPosFallLocationKeepsSquareClearance(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82051), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82052));
            double x = 40.5;
            // The crash report records z=1.74 to two decimals. This derives a representative exact
            // position from the pre-removal observation and its measured one-tick motion; it is a
            // mechanism repro, not a claim that the crash report captured this full-precision input.
            double z = 1.899999976158142 - 2.0 * 0.07840000152587892;
            player.setPosition(x, 88.0, z);

            float aquaWidth = 0.6F;
            float halfWidth = aquaWidth / 2.0F;
            float swimmingHeight = 0.6F;
            AxisAlignedBB aquaSwimming = new AxisAlignedBB(
                    x - halfWidth, 88.0, z - halfWidth,
                    x + halfWidth, 88.0 + swimmingHeight, z + halfWidth);
            double rawWidth = aquaSwimming.maxX - aquaSwimming.minX;
            double rawDepth = aquaSwimming.maxZ - aquaSwimming.minZ;
            assertNotEquals(rawWidth, rawDepth,
                    "Aqua's same float half-width loses a different final bit at x=40.5 and the observed z≈1.74");
            assertEquals(2.0 * Math.ulp(rawWidth), Math.abs(rawWidth - rawDepth), 0.0,
                    "this actual-coordinate construction differs by only two extent ULPs");
            assertEquals(1.7431999731063843, z, 0.0, "derived two-tick position rounds to the reported z=1.74");

            PlayerPhysicalSnapshot accepted = accepted(player, CardinalFrame.Y_POS, 0.6, 0.6,
                    new Vec3d(x, 88.3, z));
            AxisAlignedBB clearance = AquaCardinalPoseClearance.candidate(player, accepted, aquaSwimming);

            assertTrue(clearance.minX <= aquaSwimming.minX && clearance.maxX >= aquaSwimming.maxX
                            && clearance.minZ <= aquaSwimming.minZ && clearance.maxZ >= aquaSwimming.maxZ,
                    "the reoriented square conservatively contains Aqua's original pose candidate");

            AxisAlignedBB rectangular = new AxisAlignedBB(aquaSwimming.minX, aquaSwimming.minY, aquaSwimming.minZ,
                    aquaSwimming.maxX, aquaSwimming.maxY, aquaSwimming.maxZ + 1.0e-6);
            assertThrows(IllegalArgumentException.class,
                    () -> AquaCardinalPoseClearance.candidate(player, accepted, rectangular),
                    "a material tangent-plane rectangle remains an error");
        }
    }

    @Test
    void yNegativeStandingCandidateClearsCeilingButRealBlockTowardBodyUpStillBlocks(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82011), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82012));
            PlayerPhysicalSnapshot accepted = accepted(player, CardinalFrame.Y_NEG, .6, .6,
                    new Vec3d(8, 95.7, 8));
            AxisAlignedBB aquaStanding = new AxisAlignedBB(7.7, 95.4, 7.7, 8.3, 97.2, 8.3);
            AxisAlignedBB smallPose = new AxisAlignedBB(7.7, 95.4, 7.7, 8.3, 96.0, 8.3);

            AxisAlignedBB standing = AquaCardinalPoseClearance.candidate(player, accepted, aquaStanding);

            BlockPos ceilingBlock = new BlockPos(8, 96, 8);
            BlockPos growthBlock = new BlockPos(8, 94, 8);
            fixture.world().setBlockState(ceilingBlock, Blocks.STONE.getDefaultState(), 3);
            assertFalse(fixture.world().getCollisionBoxes(player, aquaStanding).isEmpty(),
                    "the old world-up candidate collides with the actual ceiling block");
            assertTrue(fixture.world().getCollisionBoxes(player, standing).isEmpty(),
                    "the accepted Y_NEG candidate clears the actual ceiling block");

            fixture.world().setBlockState(ceilingBlock, Blocks.AIR.getDefaultState(), 3);
            fixture.world().setBlockState(growthBlock, Blocks.STONE.getDefaultState(), 3);
            assertFalse(fixture.world().getCollisionBoxes(player, standing).isEmpty(),
                    "a genuine obstruction along bodyUp still blocks the full candidate");
            assertTrue(fixture.world().getCollisionBoxes(player, smallPose).isEmpty(),
                    "Aqua can keep its low pose while the growth path is blocked");

            fixture.world().setBlockState(growthBlock, Blocks.AIR.getDefaultState(), 3);
            assertTrue(fixture.world().getCollisionBoxes(player, standing).isEmpty(),
                    "after blocker removal the next real clearance check permits Aqua to restore standing");
        }
    }

    @Test
    void nonCardinalOrUnscaledActorsKeepAquaCandidateUnchanged(@TempDir Path directory) {
        try (var fixture = BootWorldFixture.open(directory, new UUID(0, 82021), true)) {
            EntityPlayerMP player = fixture.serverPlayer(new UUID(0, 82022));
            AxisAlignedBB aquaCandidate = new AxisAlignedBB(7.7, 65, 7.7, 8.3, 66.8, 8.3);
            PlayerPhysicalSnapshot free = new PlayerPhysicalSnapshot(scope(player), 1,
                    new PlayerBodyPlacement(new Vec3d(8, 66, 8), CardinalFrame.Y_POS.attitude(),
                            new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.6, 1.8, 1.62))), ZERO);
            assertSame(aquaCandidate, AquaCardinalPoseClearance.candidate(player, free, aquaCandidate));

            PlayerPhysicalSnapshot cardinal = accepted(player, CardinalFrame.Y_POS, .6, 1.8,
                    new Vec3d(8, 65.9, 8));
            AxisAlignedBB cardinalCandidate = AquaCardinalPoseClearance.candidate(player, cardinal, aquaCandidate);
            assertBounds(cardinal.placement().bounds(), cardinalCandidate, CardinalFrame.Y_POS);
        }
    }

    private static void installArtemisAttributes(EntityPlayerMP player, double widthScale, double heightScale,
                                                 boolean swimmingModifier) {
        IAttributeInstance width = player.getAttributeMap().registerAttribute(
                new RangedAttribute(null, "artemislib.entityWidth", 1.0, 1.0e-6, Double.MAX_VALUE));
        IAttributeInstance height = player.getAttributeMap().registerAttribute(
                new RangedAttribute(null, "artemislib.entityHeight", 1.0, 1.0e-6, Double.MAX_VALUE));
        width.applyModifier(new AttributeModifier(new UUID(0, 82101), "Resize potion width", widthScale - 1.0, 2));
        height.applyModifier(new AttributeModifier(new UUID(0, 82102), "Resize potion height", heightScale - 1.0, 2));
        if (swimmingModifier)
            height.applyModifier(new AttributeModifier(AQUA_SWIMMING_HEIGHT_MODIFIER,
                    "Aqua swimming height", -2.0 / 3.0, 2));
    }

    private static PlayerPhysicalSnapshot accepted(EntityPlayerMP player, CardinalFrame frame,
                                                     double width, double height, Vec3d center) {
        var placement = new PlayerBodyPlacement(center, frame.attitude(),
                new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(width, height, .2)));
        return new PlayerPhysicalSnapshot(scope(player), 1, placement, ZERO);
    }

    private static PlayerPhysicalSnapshot.Scope scope(EntityPlayerMP player) {
        return new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), SESSION, player.dimension, 1);
    }

    private static void assertBounds(dev.crlhitbox.api.geometry.Aabb expected, AxisAlignedBB actual,
                                     CardinalFrame frame) {
        assertEquals(expected.min().x(), actual.minX, 1.0e-12, frame + " minX");
        assertEquals(expected.min().y(), actual.minY, 1.0e-12, frame + " minY");
        assertEquals(expected.min().z(), actual.minZ, 1.0e-12, frame + " minZ");
        assertEquals(expected.max().x(), actual.maxX, 1.0e-12, frame + " maxX");
        assertEquals(expected.max().y(), actual.maxY, 1.0e-12, frame + " maxY");
        assertEquals(expected.max().z(), actual.maxZ, 1.0e-12, frame + " maxZ");
    }

    private static double extentAlongTangentX(AxisAlignedBB bounds, CardinalFrame frame) {
        return frame == CardinalFrame.X_POS || frame == CardinalFrame.X_NEG
                ? bounds.maxY - bounds.minY : bounds.maxX - bounds.minX;
    }

    private static double extentAlongBodyUp(AxisAlignedBB bounds, CardinalFrame frame) {
        return switch (frame) {
            case X_POS, X_NEG -> bounds.maxX - bounds.minX;
            case Y_POS, Y_NEG -> bounds.maxY - bounds.minY;
            case Z_POS, Z_NEG -> bounds.maxZ - bounds.minZ;
        };
    }

    private static Vec3d physicalFeet(AxisAlignedBB bounds, CardinalFrame frame) {
        return switch (frame) {
            case Y_POS -> new Vec3d(centerX(bounds), bounds.minY, centerZ(bounds));
            case Y_NEG -> new Vec3d(centerX(bounds), bounds.maxY, centerZ(bounds));
            case X_POS -> new Vec3d(bounds.minX, centerY(bounds), centerZ(bounds));
            case X_NEG -> new Vec3d(bounds.maxX, centerY(bounds), centerZ(bounds));
            case Z_POS -> new Vec3d(centerX(bounds), centerY(bounds), bounds.minZ);
            case Z_NEG -> new Vec3d(centerX(bounds), centerY(bounds), bounds.maxZ);
        };
    }

    private static double centerX(AxisAlignedBB bounds) { return (bounds.minX + bounds.maxX) / 2.0; }
    private static double centerY(AxisAlignedBB bounds) { return (bounds.minY + bounds.maxY) / 2.0; }
    private static double centerZ(AxisAlignedBB bounds) { return (bounds.minZ + bounds.maxZ) / 2.0; }

}
