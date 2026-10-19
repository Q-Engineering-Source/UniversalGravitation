package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactAccuracy;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.ContactResult;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.QueryLimits;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import wi.unigrav.core.floor.BootWorldFixture;
import wi.unigrav.core.player.CapsuleArcValidator.Failed;
import wi.unigrav.core.player.CapsuleArcValidator.Failure;
import wi.unigrav.core.player.CapsuleArcValidator.Stage;
import wi.unigrav.core.player.CapsuleArcValidator.VerifiedArc;
import wi.unigrav.core.player.CollisionQueryFootprint.QueryPolicy;
import wi.unigrav.core.player.WorldCollisionCandidates.Snapshot;
import static org.junit.jupiter.api.Assertions.*;

/** Actual stored geometry and WorldServer facts for the Free-to-Card proof frontier. */
class FreeCardinalPlaneProofTest {
    @TempDir Path temp;

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004531");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000004532");
    private static final double SUPPORT_Y = 64.0;
    private static final PlayerBodyShape.Dimensions PROFILE = new PlayerBodyShape.Dimensions(
            (double) 0.6F, (double) 1.8F, (double) 1.62F);
    private static final ContactAccuracy ACCURACY = new ContactAccuracy(1e-6, 1e-6, 1e-5);
    private static final QueryLimits QUERY = new QueryLimits(16384, 100000, 256, 128, 9344, 150000, 128);
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(10000, 16, 100, 100);
    private static final Aabb WINDOW = new Aabb(new Vec3d(5, 62, 5), new Vec3d(12, 70, 12));

    @Test
    void actualStoredTwentyAndFortyFiveDegreeAxisPlaneProfilesRecordNearGroundBitsAndMargins() {
        for (double degrees : new double[] {20.0, 45.0}) {
            var work = new StoredDyadic.Work();
            var source = axisPlaneContact(degrees, work);
            assertEquals(RealizationRelation.Kind.AXIS_PLANE,
                    source.placement().storedCapsuleGeometry().relation().kind());
            assertEquals(0, endpointMarginSign(
                    source.placement().storedCapsuleGeometry().negativeEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work),
                    "the actual stored negative endpoint is a real support-plane contact");
            var target = new PlayerBodyPlacement(source.placement().center(), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var pose = CapsulePlacementMath.pose(source.placement(), work);
            var path = CapsuleArcEnclosure.prepare(pose, target, new Vec3d(0, 0, 1), work);
            var whole = path.enclose(0, 1, work);
            double nominalTargetNegativeY = target.center().y() - PROFILE.centerlineHalfLength();
            int targetMargin = endpointMarginSign(nominalTargetNegativeY,
                    PROFILE.radius(), SUPPORT_Y, work);
            Vec3d up = source.placement().attitude().basisY();
            var sourceGeometry = source.placement().storedCapsuleGeometry();
            var targetGeometry = target.storedCapsuleGeometry();
            int sourceNegativeMargin = endpointMarginSign(sourceGeometry.negativeEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int sourcePositiveMargin = endpointMarginSign(sourceGeometry.positiveEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int targetNegativeMargin = endpointMarginSign(targetGeometry.negativeEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int targetPositiveMargin = endpointMarginSign(targetGeometry.positiveEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            System.out.println("FREE_CARDINAL_PROBE profile=" + degrees
                    + " qRaw=" + rawQuat(source.placement().attitude())
                    + " centerRaw=" + rawVec(source.placement().center())
                    + " dimensionsRaw=" + raw(PROFILE.width()) + "," + raw(PROFILE.height()) + "," + raw(PROFILE.eyeHeight())
                    + " radiusRaw=" + raw(PROFILE.radius()) + " halfLengthRaw=" + raw(PROFILE.centerlineHalfLength())
                    + " basisYActual=" + vec(up)
                    + " basisYIdealZRotation=" + Double.toHexString(Math.cos(Math.toRadians(degrees)))
                    + " basisYError=" + Double.toHexString(up.y() - Math.cos(Math.toRadians(degrees)))
                    + " storedHalfLine=" + vec(source.placement().storedCapsuleGeometry().storedHalfLine())
                    + " sourceEndpoints=" + vec(source.placement().storedCapsuleGeometry().negativeEndpoint())
                    + "|" + vec(source.placement().storedCapsuleGeometry().positiveEndpoint())
                    + " sourceActualEndpointMargins=" + sourceNegativeMargin + "," + sourcePositiveMargin
                    + " targetActualEndpointMargins=" + targetNegativeMargin + "," + targetPositiveMargin
                    + " sameCenterIdentityPN=" + Double.toHexString(nominalTargetNegativeY)
                    + " sameCenterIdentityPNMarginSign=" + targetMargin
                    + " wholeIntervalBoundsY=" + Double.toHexString(whole.bounds().min().y())
                    + ".." + Double.toHexString(whole.bounds().max().y())
                    + " wholeIntervalPaddedRadius=" + Double.toHexString(whole.padded().radius())
                    + " enclosureNumericWorkDelta=" + whole.numericWorkDelta()
                    + " cumulativeWork=" + work.units());
            assertTrue(sourceNegativeMargin >= 0 && sourcePositiveMargin >= 0,
                    "actual stored AxisPlane source endpoints must each be checked against the half-space");
            assertTrue(targetNegativeMargin < 0,
                    "the same-center upright target is physically below the support plane");
            assertTrue(targetMargin < 0,
                    "same-center upright target is physically below the support plane; this is not an enclosure-padding defect");
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {20.0, 45.0})
    void worldValidatorRejectsSameCenterUprightTargetAsPhysicalFloorCollision(double degrees) throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("unsafe-target-" + degrees), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new StoredDyadic.Work();
            var sourceRecipe = axisPlaneContact(degrees, work);
            var accepted = installSnapshot(actor, sourceRecipe.placement());
            installFloor(fixture);
            Snapshot capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size());

            var pose = CapsulePlacementMath.pose(accepted.placement(), work);
            var target = new PlayerBodyPlacement(accepted.placement().center(), Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var path = CapsuleArcEnclosure.prepare(pose, target, new Vec3d(0, 0, 1), work);
            var entire = path.enclose(0, 1, work);
            var budget = new CapsuleContactQueryBudget(QUERY, 1024);
            var result = CapsuleArcValidator.validate(accepted, pose, target, capture, WINDOW,
                    new Vec3d(0, 0, 1), ACCURACY, budget, work);
            var rejected = assertInstanceOf(Failed.class, result);
            assertEquals(Failure.ACTUAL_OVERLAP, rejected.failure());
            assertTrue(rejected.queries().stream().anyMatch(query ->
                    query.stage() == Stage.START && query.result().relation() == Relation.TOUCHING));
            assertTrue(rejected.queries().stream().anyMatch(query ->
                    query.stage() == Stage.TARGET && query.result().relation() == Relation.OVERLAPPING));
            System.out.println("FREE_CARDINAL_PROBE physicalFloorCollision profile=" + degrees
                    + " result=" + rejected.failure()
                    + " startTargetRelations=" + relations(rejected.queries())
                    + " pathBoundsY=" + Double.toHexString(entire.bounds().min().y())
                    + ".." + Double.toHexString(entire.bounds().max().y())
                    + " floorBox=" + capture.boxes().get(0)
                    + " cumulativeWork=" + work.units()
                    + " chbCalls=" + budget.calls());
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {20.0, 45.0})
    void safeNearGroundTiltToIdentityArcIsVerifiedInActualWorld(double degrees) throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("safe-arc-" + degrees), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new StoredDyadic.Work();
            double uprightBottomCenter = (SUPPORT_Y + PROFILE.radius()) + PROFILE.centerlineHalfLength();
            Vec3d center = new Vec3d(8.5, uprightBottomCenter, 8.5);
            Rotation3d tilt = zRotation(degrees);
            var start = new PlayerBodyPlacement(center, tilt, new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var accepted = installSnapshot(actor, start);
            installFloor(fixture);
            Snapshot capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size());
            var pose = CapsulePlacementMath.pose(accepted.placement(), work);
            var target = new PlayerBodyPlacement(center, Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var path = CapsuleArcEnclosure.prepare(pose, target, new Vec3d(0, 0, 1), work);
            var whole = path.enclose(0, 1, work);
            var sourceGeometry = accepted.placement().storedCapsuleGeometry();
            var targetGeometry = target.storedCapsuleGeometry();
            double nominalVerticalHalfSpace = target.center().y() - PROFILE.centerlineHalfLength();
            int nominalMargin = endpointMarginSign(nominalVerticalHalfSpace,
                    PROFILE.radius(), SUPPORT_Y, work);
            int sourceNegativeMargin = endpointMarginSign(sourceGeometry.negativeEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int sourcePositiveMargin = endpointMarginSign(sourceGeometry.positiveEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int targetNegativeMargin = endpointMarginSign(targetGeometry.negativeEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            int targetPositiveMargin = endpointMarginSign(targetGeometry.positiveEndpoint().y(),
                    PROFILE.radius(), SUPPORT_Y, work);
            Rotation3d actualStartAttitude = accepted.placement().attitude();
            assertEquals(0.0, actualStartAttitude.x());
            assertEquals(0.0, actualStartAttitude.y());
            assertTrue(Math.abs(actualStartAttitude.z()) <= 13.0 / 32.0);
            assertTrue(actualStartAttitude.w() >= 7.0 / 8.0 && actualStartAttitude.w() <= 1.0);
            assertEquals(0.0, target.attitude().x());
            assertEquals(0.0, target.attitude().y());
            assertEquals(0.0, target.attitude().z());
            assertEquals(1.0, target.attitude().w());
            assertEquals(0, nominalMargin, "the identity target has exact zero plane margin");
            assertTrue(sourceNegativeMargin >= 0 && sourcePositiveMargin >= 0
                            && targetNegativeMargin >= 0 && targetPositiveMargin >= 0,
                    "all four actual stored endpoint margins must independently be nonnegative");
            assertEquals(0, targetNegativeMargin, "the actual target negative endpoint is zero-gap");
            var budget = new CapsuleContactQueryBudget(QUERY, 4096);
            var result = CapsuleArcValidator.validate(accepted, pose, target, capture, WINDOW,
                    new Vec3d(0, 0, 1), ACCURACY, budget, work);
            String resultDetail;
            if (result instanceof VerifiedArc verifiedResult) {
                resultDetail = "VerifiedArc regions=" + verifiedResult.regions()
                        + " startTargetRelations=" + relations(verifiedResult.queries());
            } else {
                var failed = (Failed) result;
                resultDetail = "Failed failure=" + failed.failure() + " regions=" + failed.regions()
                        + " startTargetAndEnclosureRelations=" + relations(failed.queries());
            }
            System.out.println("FREE_CARDINAL_PROBE candidateSafeNearGroundWorldArc profile=" + degrees
                    + " result=" + resultDetail
                    + " sourceQRaw=" + rawQuat(actualStartAttitude)
                    + " targetQRaw=" + rawQuat(target.attitude())
                    + " actualYPlaneGuard=true"
                    + " centerRaw=" + rawVec(center)
                    + " dimensionsRaw=" + raw(PROFILE.width()) + "," + raw(PROFILE.height())
                    + "," + raw(PROFILE.eyeHeight())
                    + " radiusRaw=" + raw(PROFILE.radius())
                    + " halfLengthRaw=" + raw(PROFILE.centerlineHalfLength())
                    + " PN=" + Double.toHexString(nominalVerticalHalfSpace)
                    + " exactNominalMarginSign=" + nominalMargin
                    + " sourceEndpointMargins=" + sourceNegativeMargin + "," + sourcePositiveMargin
                    + " targetEndpointMargins=" + targetNegativeMargin + "," + targetPositiveMargin
                    + " pathBoundsY=" + Double.toHexString(whole.bounds().min().y())
                    + ".." + Double.toHexString(whole.bounds().max().y())
                    + " paddedRadius=" + Double.toHexString(whole.padded().radius())
                    + " floorBox=" + capture.boxes().get(0)
                    + " cumulativeWork=" + work.units()
                    + " chbCalls=" + budget.calls());
            var verified = assertInstanceOf(VerifiedArc.class, result,
                    "the actual current validator must prove this safe zero-gap candidate-domain arc; baseline failure is retained as RED evidence");
            assertEquals(1, verified.regions(), "the plane receipt should certify the complete actual short arc");
            assertTrue(verified.queries().stream().allMatch(query ->
                    query.stage() == Stage.START || query.stage() == Stage.TARGET),
                    "a certified floor ordinal should not run the coarse padded enclosure query");
            assertEquals(1, budget.calls(), "strict start separation avoids CHB; the zero-gap target still queries");
            assertEquals(Stage.TARGET, verified.queries().getFirst().stage());
            assertEquals(Relation.TOUCHING, verified.queries().getFirst().result().relation());
            assertTrue(verified.separations().stream().anyMatch(skip -> skip.stage() == Stage.START));
            assertTrue(verified.matches(accepted, pose, target, capture, budget, work));
        }
    }

    @Test
    void yPlaneProofUsesActualAxisPlaneEndpointInsteadOfNominalReconstruction() {
        var work = new StoredDyadic.Work();
        Recipe recipe = axisPlaneContact(0, work);
        PlayerBodyPlacement actualStart = recipe.placement();
        assertEquals(RealizationRelation.Kind.AXIS_PLANE,
                actualStart.storedCapsuleGeometry().relation().kind());
        var pose = CapsulePlacementMath.pose(actualStart, work);
        var target = new PlayerBodyPlacement(actualStart.center(), Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(PROFILE), work);
        var path = CapsuleArcEnclosure.prepare(pose, target, new Vec3d(0, 0, 1), work);
        double plane = Math.nextUp(SUPPORT_Y);
        double actualNegativeY = actualStart.storedCapsuleGeometry().negativeEndpoint().y();
        double nominalNegativeY = actualStart.center().y() - PROFILE.centerlineHalfLength();
        int actualMargin = endpointMarginSign(actualNegativeY, PROFILE.radius(), plane, work);
        int nominalMargin = endpointMarginSign(nominalNegativeY, PROFILE.radius(), plane, work);
        assertEquals(-1, actualMargin,
                "the actual stored AxisPlane endpoint is one exact plane ulp below this captured threshold");
        assertEquals(0, nominalMargin,
                "the reconstructed nominal negative endpoint would incorrectly appear to touch safely");
        assertFalse(path.provesYLowerHalfSpace(plane, 0, 1, work),
                "the path proof must check the stored actual endpoint, not only RN(Cy-h)");
        System.out.println("FREE_CARDINAL_PROBE axisPlaneEndpointGate actualNegative="
                + Double.toHexString(actualNegativeY) + " nominalNegative=" + Double.toHexString(nominalNegativeY)
                + " plane=" + Double.toHexString(plane) + " actualMargin=" + actualMargin
                + " nominalMargin=" + nominalMargin + " work=" + work.units());
    }

    @Test
    void yPlaneProofRefusesQuaternionOutsideItsSufficientDomainAndNonIdentityTarget() {
        Vec3d center = new Vec3d(8.5, 68.0, 8.5);
        var outsideWork = new StoredDyadic.Work();
        var outsideStart = new PlayerBodyPlacement(center, zRotation(90),
                new PlayerBodyShape.FreeCapsule(PROFILE), outsideWork);
        var outsidePose = CapsulePlacementMath.pose(outsideStart, outsideWork);
        var identityTarget = new PlayerBodyPlacement(center, Rotation3d.identity(),
                new PlayerBodyShape.FreeCapsule(PROFILE), outsideWork);
        var outsidePath = CapsuleArcEnclosure.prepare(outsidePose, identityTarget,
                new Vec3d(0, 0, 1), outsideWork);
        assertFalse(outsidePath.provesYLowerHalfSpace(60, 0, 1, outsideWork),
                "a 90-degree starting quaternion is outside the proof guard and must use normal CHB subdivision");

        var targetWork = new StoredDyadic.Work();
        var ordinaryStart = new PlayerBodyPlacement(center, zRotation(20),
                new PlayerBodyShape.FreeCapsule(PROFILE), targetWork);
        var ordinaryPose = CapsulePlacementMath.pose(ordinaryStart, targetWork);
        var tiltedTarget = new PlayerBodyPlacement(center, zRotation(10),
                new PlayerBodyShape.FreeCapsule(PROFILE), targetWork);
        var nonIdentityTargetPath = CapsuleArcEnclosure.prepare(ordinaryPose, tiltedTarget,
                new Vec3d(0, 0, 1), targetWork);
        assertFalse(nonIdentityTargetPath.provesYLowerHalfSpace(60, 0, 1, targetWork),
                "the proof applies only when the actual sign-selected target quaternion is exact identity");
    }

    @Test
    void worldYPlaneReceiptAndStrictBoundsKeepSeparateCapturedOrdinalEvidence() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("plane-ordinal"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new StoredDyadic.Work();
            Vec3d center = new Vec3d(8.5,
                    SUPPORT_Y + PROFILE.radius() + PROFILE.centerlineHalfLength(), 8.5);
            var start = new PlayerBodyPlacement(center, zRotation(20),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var accepted = installSnapshot(actor, start);
            installFloor(fixture);
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            chunk.setBlockState(new BlockPos(10, 64, 8), Blocks.STONE.getDefaultState());
            Snapshot capture = capture(fixture, actor);
            assertEquals(2, capture.boxes().size());
            Aabb floorBox = capture.boxes().stream().filter(box -> box.max().y() == SUPPORT_Y)
                    .findFirst().orElseThrow();
            Aabb highSideBox = capture.boxes().stream().filter(box -> box.max().y() == SUPPORT_Y + 1)
                    .findFirst().orElseThrow();
            int floorOrdinal = capture.boxes().indexOf(floorBox);
            int highSideOrdinal = capture.boxes().indexOf(highSideBox);
            assertTrue(endpointMarginSign(center.y() - PROFILE.centerlineHalfLength(), PROFILE.radius(),
                    highSideBox.max().y(), work) < 0,
                    "the higher side box has a negative exact Y-plane margin and cannot receive the separator proof");

            var pose = CapsulePlacementMath.pose(accepted.placement(), work);
            var target = new PlayerBodyPlacement(center, Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var budget = new CapsuleContactQueryBudget(QUERY, 4096);
            var result = CapsuleArcValidator.validate(accepted, pose, target, capture, WINDOW,
                    new Vec3d(0, 0, 1), ACCURACY, budget, work);
            var verified = assertInstanceOf(VerifiedArc.class, result);
            assertTrue(verified.queries().stream().noneMatch(query ->
                    query.stage() == Stage.ENCLOSURE && query.ordinal() == floorOrdinal),
                    "only the exact captured floor ordinal may be skipped by its bound plane receipt");
            var highSideSkip = verified.separations().stream().filter(skip -> skip.stage() == Stage.ENCLOSURE
                    && skip.ordinal() == highSideOrdinal).findFirst().orElseThrow();
            assertSame(highSideBox, highSideSkip.box());
            assertTrue(CapsuleOutwardBounds.strictlySeparated(highSideSkip.outwardBounds(), highSideBox, work),
                    "the negative Y-plane margin cannot issue a plane receipt; strict X separation independently proves this ordinal");
            assertEquals(1, verified.regions());
            assertEquals(1, budget.calls(), "only the zero-gap floor target remains a real CHB query");
            assertEquals(Stage.TARGET, verified.queries().getFirst().stage());
            assertEquals(floorOrdinal, verified.queries().getFirst().ordinal());
            assertEquals(Relation.TOUCHING, verified.queries().getFirst().result().relation());
            System.out.println("FREE_CARDINAL_PROBE ordinalPlaneReceipt floorOrdinal=" + floorOrdinal
                    + " floorBox=" + floorBox + " highSideOrdinal=" + highSideOrdinal
                    + " highSideBox=" + highSideBox + " exactHighSideMarginSign=negative"
                    + " queries=" + relations(verified.queries()) + " calls=" + budget.calls()
                    + " numericWork=" + verified.numericWork());
        }
    }

    @Test
    void worldShowsCapsuleClearWhileCardinalTargetBoxCornerIsOccupied() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("cardinal-corner"), PLAYER, false)) {
            var actor = fixture.serverPlayer(PLAYER);
            var work = new StoredDyadic.Work();
            Vec3d center = new Vec3d(8.75, 66.0, 8.75);
            var free = new PlayerBodyPlacement(center, Rotation3d.identity(),
                    new PlayerBodyShape.FreeCapsule(PROFILE), work);
            var accepted = installSnapshot(actor, free);
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(WINDOW, World.MAX_ENTITY_RADIUS,
                    QueryPolicy.CAPSULE_CONTACT, SCAN));
            var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
            assertNotNull(chunk);
            chunk.setBlockState(new BlockPos(9, 65, 9), Blocks.STONE.getDefaultState());
            Snapshot capture = capture(fixture, actor);
            assertEquals(1, capture.boxes().size());
            Aabb actualBlock = capture.boxes().get(0);

            var cardinal = new PlayerBodyPlacement(center, Rotation3d.identity(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, PROFILE), work);
            assertTrue(strictlyIntersects(cardinal.bounds(), actualBlock),
                    "the actual target Cardinal AABB reaches into the occupied voxel corner");
            Snapshot targetWindow = captureNativeStrict(fixture, actor, cardinal.bounds());
            assertEquals(1, targetWindow.boxes().size());
            assertEquals(cardinal.bounds(), targetWindow.footprint().requestedBounds());
            assertEquals(QueryPolicy.NATIVE_STRICT, targetWindow.footprint().policy());
            assertTrue(strictlyIntersects(cardinal.bounds(), targetWindow.boxes().get(0)),
                    "the actual native strict target-bounds scan must capture the occupied target corner");
            ContactResult capsuleContact = dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.query(
                    accepted.placement().capsule(), targetWindow.boxes().get(0), ACCURACY, QUERY);
            assertEquals(Relation.SEPARATED, capsuleContact.relation(),
                    "the actual capsule remains clear; capsule clearance alone says nothing about this Cardinal box");
            System.out.println("FREE_CARDINAL_PROBE cardinalCorner targetCapturePolicy="
                    + targetWindow.footprint().policy() + " block=" + targetWindow.boxes().get(0)
                    + " cardBounds=" + cardinal.bounds()
                    + " capsule=" + accepted.placement().capsule()
                    + " capsuleRelation=" + capsuleContact.relation()
                    + " cardinalStrictOverlap=" + strictlyIntersects(cardinal.bounds(), actualBlock)
                    + " chbWork=" + capsuleContact.work());
        }
    }

    private static Recipe axisPlaneContact(double degrees, StoredDyadic.Work work) {
        Rotation3d attitude = zRotation(degrees);
        double halfLength = PROFILE.centerlineHalfLength();
        var preview = StoredRealizationLimits.derive(new Vec3d(8.5, SUPPORT_Y, 8.5), attitude, PROFILE, work);
        double planePlusRadius = SUPPORT_Y + PROFILE.radius();
        double centerY = Math.nextUp(planePlusRadius + preview.nominalHalfLine().y());
        Vec3d center = new Vec3d(8.5, centerY, 8.5);
        var limits = StoredRealizationLimits.derive(center, attitude, PROFILE, work);
        Vec3d nominal = limits.nominalHalfLine();
        Vec3d halfLine = new Vec3d(nominal.x(), centerY - planePlusRadius, nominal.z());
        var relation = RealizationRelation.axisPlane(limits, 1, 1, SUPPORT_Y, center, halfLine, work);
        var geometry = StoredCapsuleGeometry.fromRecipe(center, attitude, PROFILE, halfLine, relation);
        var placement = PlayerBodyPlacement.fromAcceptedFreeGeometry(center, attitude,
                new PlayerBodyShape.FreeCapsule(PROFILE), geometry, geometry.capsule().bounds(),
                new Vec3d(center.x(), geometry.capsule().bounds().min().y(), center.z()));
        assertEquals(0, endpointMarginSign(geometry.negativeEndpoint().y(), PROFILE.radius(), SUPPORT_Y, work));
        return new Recipe(placement, attitude);
    }

    private static Rotation3d zRotation(double degrees) {
        double half = Math.toRadians(degrees * 0.5);
        return new Rotation3d(0.0, 0.0, Math.sin(half), Math.cos(half));
    }

    private static PlayerPhysicalSnapshot installSnapshot(EntityPlayerMP actor, PlayerBodyPlacement placement) {
        actor.width = (float) placement.shape().dimensions().width();
        actor.height = (float) placement.shape().dimensions().height();
        Vec3d transport = placement.transport();
        actor.setPosition(transport.x(), transport.y(), transport.z());
        Aabb bounds = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        return new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, SESSION, actor.dimension, 1),
                1, placement, new Vec3d(0, 0, 0));
    }

    private static void installFloor(BootWorldFixture fixture) {
        fixture.installCollisionFootprint(CollisionQueryFootprint.plan(WINDOW, World.MAX_ENTITY_RADIUS,
                QueryPolicy.CAPSULE_CONTACT, SCAN));
        var chunk = fixture.world().getChunkProvider().getLoadedChunk(0, 0);
        assertNotNull(chunk);
        chunk.setBlockState(new BlockPos(8, 63, 8), Blocks.STONE.getDefaultState());
    }

    private static Snapshot capture(BootWorldFixture fixture, EntityPlayerMP actor) {
        var result = WorldCollisionCandidates.capture(fixture.world(), actor, WINDOW,
                QueryPolicy.CAPSULE_CONTACT, SCAN);
        return assertInstanceOf(Snapshot.class, result);
    }

    private static Snapshot captureNativeStrict(BootWorldFixture fixture, EntityPlayerMP actor, Aabb bounds) {
        var result = WorldCollisionCandidates.capture(fixture.world(), actor, bounds,
                QueryPolicy.NATIVE_STRICT, SCAN);
        return assertInstanceOf(Snapshot.class, result);
    }

    private static int endpointMarginSign(double endpointY, double radius, double plane, StoredDyadic.Work work) {
        return StoredDyadic.of(endpointY, work).subtract(StoredDyadic.of(radius, work), work)
                .subtract(StoredDyadic.of(plane, work), work).signum();
    }

    private static boolean strictlyIntersects(Aabb first, Aabb second) {
        return first.min().x() < second.max().x() && first.max().x() > second.min().x()
                && first.min().y() < second.max().y() && first.max().y() > second.min().y()
                && first.min().z() < second.max().z() && first.max().z() > second.min().z();
    }

    private static String relations(java.util.List<CapsuleArcValidator.Query> queries) {
        return queries.stream().map(query -> query.stage() + ":" + query.from() + "-" + query.to()
                + ":" + query.result().relation()).toList().toString();
    }

    private static String raw(double value) {
        return String.format(Locale.ROOT, "0x%016X", Double.doubleToRawLongBits(value));
    }

    private static String rawQuat(Rotation3d q) {
        return raw(q.x()) + "," + raw(q.y()) + "," + raw(q.z()) + "," + raw(q.w());
    }

    private static String rawVec(Vec3d value) {
        return raw(value.x()) + "," + raw(value.y()) + "," + raw(value.z());
    }

    private static String vec(Vec3d value) {
        return Double.toHexString(value.x()) + "," + Double.toHexString(value.y()) + "," + Double.toHexString(value.z());
    }

    private record Recipe(PlayerBodyPlacement placement, Rotation3d attitude) {
    }
}
