package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Capsule;
import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Segment3d;
import dev.crlhitbox.api.geometry.Vec3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d;
import dev.crlhitbox.api.geometry.contact.CapsuleAabbContact3d.Relation;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class CapsuleSupportSlopeTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004273");
    private static final CapsuleAabbContact3d.QueryLimits LIMITS =
            new CapsuleAabbContact3d.QueryLimits(8192, 20000, 128, 32, 2336, 40000, 64);

    @Test void actualFlatFootContactCertifiesOnlyItsExactObservationAndSharedWork() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("flat"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var observation = observe(fixture, new Vec3d(8.5, 69.25, 8.5), CardinalFrame.Y_POS.attitude(), work);
            var certificate = assertInstanceOf(CapsuleSupportSlope.Certificate.class,
                    CapsuleSupportSlope.certify(observation, 0, work));
            assertTrue(certificate.workDelta() > 0);
            assertTrue(certificate.matches(observation, 0, work));
            assertFalse(certificate.matches(observation, 0, new StoredDyadic.Work()));
            assertEquals(Relation.TOUCHING, observation.queries().getFirst().contact().relation());
            assertTrue(observation.queries().getFirst().contact().normal().isEmpty());
            fixture.world().setTotalWorldTime(fixture.world().getTotalWorldTime() + 1);
            assertFalse(certificate.matches(observation, 0, work));
            assertEquals(CapsuleSupportSlope.Reason.CONTEXT_CHANGED,
                    assertInstanceOf(CapsuleSupportSlope.Uncertain.class,
                            CapsuleSupportSlope.certify(observation, 0, work)).reason());
        }
    }

    @Test void realTiltedBodyUpOnBothSidesOfHalfSlopeUsesTheActualWitness() throws Exception {
        for (double cosine : new double[]{.5001, .4999}) {
            try (var fixture = BootWorldFixture.open(temp.resolve("half-" + cosine), PLAYER, false)) {
                double angle = Math.acos(cosine);
                var attitude = new Rotation3d(0, 0, Math.sin(angle / 2), Math.cos(angle / 2));
                var work = new StoredDyadic.Work();
                var observation = observe(fixture, new Vec3d(8.5, 69.25, 8.5), attitude, work);
                assertEquals(CapsuleFootContacts.Classification.CONTACT, observation.queries().getFirst().classification());
                var result = CapsuleSupportSlope.certify(observation, 0, work);
                if (cosine > .5) assertInstanceOf(CapsuleSupportSlope.Certificate.class, result);
                else assertEquals(CapsuleSupportSlope.Reason.SLOPE_UNCERTAIN,
                        assertInstanceOf(CapsuleSupportSlope.Uncertain.class, result).reason());
            }
        }
    }

    @Test void realDownwardWallContactAndOppositeCeilingDoNotCertifyStandingSlope() throws Exception {
        for (boolean wall : new boolean[]{true, false}) {
            try (var fixture = BootWorldFixture.open(temp.resolve(wall ? "wall" : "opposite"), PLAYER, false)) {
                double angle = -Math.asin(.2);
                var attitude = wall ? new Rotation3d(0, 0, Math.sin(angle / 2), Math.cos(angle / 2))
                        : CardinalFrame.Y_POS.attitude();
                var work = new StoredDyadic.Work();
                var observation = observe(fixture, wall ? new Vec3d(9.25, 68.5, 8.5) : new Vec3d(8.5, 67.75, 8.5),
                        attitude, work);
                assertEquals(Relation.TOUCHING, observation.queries().getFirst().contact().relation());
                if (wall) assertEquals(CapsuleFootContacts.Classification.CONTACT,
                        observation.queries().getFirst().classification());
                assertInstanceOf(CapsuleSupportSlope.Uncertain.class, CapsuleSupportSlope.certify(observation, 0, work));
            }
        }
    }

    @Test void realNearFootIsNotAStandingContactCertificate() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("near"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var observation = observe(fixture, new Vec3d(8.5, 69.375, 8.5), CardinalFrame.Y_POS.attitude(), work);
            assertEquals(CapsuleFootContacts.Classification.NEAR, observation.queries().getFirst().classification());
            assertEquals(CapsuleSupportSlope.Reason.NOT_FOOT_CONTACT,
                    assertInstanceOf(CapsuleSupportSlope.Uncertain.class,
                            CapsuleSupportSlope.certify(observation, 0, work)).reason());
        }
    }

    @Test void realRationalChbWitnessNearHalfSlopeBoundaryRemainsNumericUncertain() {
        var capsule = new Capsule(new Segment3d(new Vec3d(8, 68, 8), new Vec3d(11, 72, 8)), 1);
        var point = new Aabb(new Vec3d(9.25, 68, 8), new Vec3d(9.25, 68, 8));
        var contact = CapsuleAabbContact3d.query(capsule, point, FreeOwnerTransaction.ACCURACY, LIMITS);
        assertEquals(Relation.TOUCHING, contact.relation());
        var witness = contact.witness().orElseThrow();
        assertTrue(witness.segmentX().lower() < witness.segmentX().upper());
        assertTrue(witness.segmentY().lower() < witness.segmentY().upper());
        double angle = Math.atan2(.8, .6) + Math.PI / 3;
        var up = new Rotation3d(0, 0, Math.sin(angle / 2), Math.cos(angle / 2)).basisY();
        var work = new StoredDyadic.Work();
        assertEquals(CapsuleSupportSlope.SlopeEvidence.UNCERTAIN, CapsuleSupportSlope.evaluate(witness, up, work));
        assertTrue(work.units() > 0);
        // This is numeric interval coverage from a real non-foot witness; it cannot mint the private foot certificate.
    }

    @Test void numericExhaustionRetainsActualCauseAndAlreadySpentPoseWork() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("work"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var observation = observe(fixture, new Vec3d(8.5, 69.25, 8.5), CardinalFrame.Y_POS.attitude(), work);
            long calls = observation.fullObservation().budget().calls();
            long poseWork = work.units();
            assertTrue(poseWork > 0);
            work.charge(StoredDyadic.MAX_WORK_UNITS - work.units() - 1);
            long before = work.units();
            var result = assertInstanceOf(CapsuleSupportSlope.Uncertain.class,
                    CapsuleSupportSlope.certify(observation, 0, work));
            assertEquals(CapsuleSupportSlope.Reason.NUMERIC_WORK_EXHAUSTED, result.reason());
            var cause = result.cause().orElseThrow();
            assertEquals(StoredDyadic.MAX_WORK_UNITS, cause.used());
            assertTrue(cause.requested() > 0);
            assertEquals(before, result.workBefore());
            assertEquals(StoredDyadic.MAX_WORK_UNITS, result.workAfter());
            assertEquals(calls, observation.fullObservation().budget().calls());
        }
    }

    @Test void zeroAndNegativeZeroActualContactCertifyThroughTheRealCollectors() throws Exception {
        for (double reach : new double[]{0.0, -0.0}) {
            try (var fixture = BootWorldFixture.open(temp.resolve("zero-" + Double.doubleToRawLongBits(reach)), PLAYER, false)) {
                var work = new StoredDyadic.Work();
                var foot = observe(fixture, new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(), work, reach);
                assertEquals(1, foot.queries().size());
                var query = foot.queries().getFirst();
                assertEquals(CapsuleFootContacts.Classification.CONTACT, query.classification());
                assertEquals(Relation.TOUCHING, query.contact().relation());
                assertTrue(query.boundaries().isEmpty());
                assertTrue(query.direction().orElseThrow().lower() > 0);
                assertInstanceOf(CapsuleSupportSlope.Certificate.class, CapsuleSupportSlope.certify(foot, 0, work));
                var full = foot.fullObservation();
                var complete = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                        CapsuleSupportObservation.observe(full.accepted(), full.pose(), full.capture(), foot.window(), reach,
                                FreeOwnerTransaction.ACCURACY, full.budget(), work));
                assertEquals(1, complete.supports().size());
            }
        }
    }

    @Test void zeroReachGapWallCeilingAndSteepWitnessCannotBecomeSupport() throws Exception {
        var centers = new Vec3d[]{new Vec3d(8.5,69.375,8.5), new Vec3d(9.25,68.5,8.5),
                new Vec3d(8.5,67.75,8.5), new Vec3d(8.5,69.25,8.5)};
        double angle = Math.acos(.4999);
        for (int n = 0; n < centers.length; n++) {
            try (var fixture = BootWorldFixture.open(temp.resolve("zero-reject-" + n), PLAYER, false)) {
                var work = new StoredDyadic.Work();
                var q = n == 3 ? new Rotation3d(0,0,Math.sin(angle/2),Math.cos(angle/2)) : CardinalFrame.Y_POS.attitude();
                var foot = observe(fixture, centers[n], q, work, 0);
                if (n > 0) {
                    assertFalse(foot.queries().isEmpty(), "wall/ceiling/steep must exercise actual contact, not empty capture");
                    assertFalse(foot.fullObservation().queries().isEmpty());
                }
                for (var query : foot.queries()) {
                    assertTrue(query.boundaries().isEmpty());
                    var result = assertInstanceOf(CapsuleSupportSlope.Uncertain.class,
                            CapsuleSupportSlope.certify(foot, query.ordinal(), work));
                    if (n > 0) {
                        assertEquals(Relation.TOUCHING, query.contact().relation());
                        assertTrue(query.contact().witness().isPresent());
                        assertEquals(Relation.TOUCHING, foot.fullObservation().queries().get(query.ordinal()).contact().relation());
                        var direction = query.direction().orElseThrow();
                        if (n == 3) {
                            assertTrue(direction.lower() > 0, "steep contact must reach the actual slope predicate");
                            assertEquals(CapsuleFootContacts.Classification.CONTACT, query.classification());
                            assertEquals(CapsuleSupportSlope.Reason.SLOPE_UNCERTAIN, result.reason());
                        } else {
                            if (n == 1) {
                                assertTrue(direction.lower() <= 0 && direction.upper() >= 0,
                                        "side witness direction interval must enclose zero");
                            } else assertTrue(direction.upper() < 0, "ceiling witness points opposite body up");
                            assertEquals(CapsuleSupportSlope.Reason.NOT_FOOT_CONTACT, result.reason());
                        }
                    }
                }
                var full = foot.fullObservation();
                var complete = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                        CapsuleSupportObservation.observe(full.accepted(), full.pose(), full.capture(), foot.window(), 0,
                                FreeOwnerTransaction.ACCURACY, full.budget(), work));
                assertTrue(complete.supports().isEmpty());
            }
        }
    }

    @Test void smallestPositiveReachKeepsTheActualBoundaryRequirement() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("minimum-positive-reach"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var foot = observe(fixture, new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(), work, Double.MIN_VALUE);
            assertEquals(Double.MIN_VALUE, foot.reach());
            assertFalse(foot.queries().getFirst().boundaries().isEmpty(), "positive reach must execute the real cast path");
            var result = CapsuleSupportSlope.certify(foot, 0, work);
            if (result instanceof CapsuleSupportSlope.Certificate) {
                assertEquals(CapsuleAabbContact3d.BoundaryKind.BLOCKED_AT_START,
                        foot.queries().getFirst().boundaries().getLast().kind());
                assertEquals(CapsuleFootContacts.Classification.CONTACT, foot.queries().getFirst().classification());
            } else assertInstanceOf(CapsuleSupportSlope.Uncertain.class, result);
        }
    }

    @Test void zeroReachActualUnresolvedFullBodyCannotProduceFootCertificate() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("zero-unresolved"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var foot = observe(fixture, new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(), work, 0);
            var full = foot.fullObservation();
            var limited = new CapsuleAabbContact3d.QueryLimits(8192,1,128,32,2336,40000,64);
            var result = assertInstanceOf(CapsuleFootContacts.Failed.class,
                    CapsuleFootContacts.collect(full.accepted(), full.pose(), full.capture(), 0,
                            FreeOwnerTransaction.ACCURACY, new CapsuleContactQueryBudget(limited,100)));
            assertEquals(CapsuleContactCollector.Failure.UNRESOLVED,
                    assertInstanceOf(CapsuleContactCollector.Failed.class, result.fullObservation()).failure());
            assertTrue(result.queries().isEmpty());
        }
    }

    @Test void zeroReachFullBodyOverlapStillRefusesTheWholeObservation() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("zero-full-overlap"), PLAYER, false)) {
            var work = new StoredDyadic.Work();
            var actor = fixture.serverPlayer(PLAYER);
            var foot = observe(fixture, actor, new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(), work, 0);
            var full = foot.fullObservation();
            fixture.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,69,8), Blocks.STONE.getDefaultState());
            var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(fixture.world(), actor, foot.window(),
                            new CollisionQueryFootprint.Limits(10000,16,100,100)));
            var result = assertInstanceOf(CapsuleSupportObservation.Failed.class,
                    CapsuleSupportObservation.observe(full.accepted(), full.pose(), capture, foot.window(), 0,
                            FreeOwnerTransaction.ACCURACY, full.budget(), work));
            var failed = assertInstanceOf(CapsuleFootContacts.Failed.class, result.foot().orElseThrow());
            assertEquals(CapsuleContactCollector.Failure.OVERLAPPING,
                    assertInstanceOf(CapsuleContactCollector.Failed.class, failed.fullObservation()).failure());
        }
    }

    private CapsuleFootContacts.VerifiedFootObservations observe(BootWorldFixture fixture, Vec3d center,
                                                                  Rotation3d attitude, StoredDyadic.Work work) {
        return observe(fixture, center, attitude, work, .25);
    }

    private CapsuleFootContacts.VerifiedFootObservations observe(BootWorldFixture fixture, Vec3d center,
            Rotation3d attitude, StoredDyadic.Work work, double reach) {
        return observe(fixture, fixture.serverPlayer(PLAYER), center, attitude, work, reach);
    }

    private CapsuleFootContacts.VerifiedFootObservations observe(BootWorldFixture fixture, EntityPlayerMP actor,
            Vec3d center, Rotation3d attitude, StoredDyadic.Work work, double reach) {
        var placement = new PlayerBodyPlacement(center, attitude,
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5, .5, .25)), work);
        var transport = placement.transport();
        actor.width = .5F; actor.height = .5F;
        actor.setPosition(transport.x(), transport.y(), transport.z());
        var bounds = placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.max().x(), bounds.max().y(), bounds.max().z()));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER, UUID.randomUUID(),
                actor.dimension, 1), 7, placement, new Vec3d(0, 0, 0));
        fixture.world().getChunkProvider().loadedChunks.get(net.minecraft.util.math.ChunkPos.asLong(0, 0))
                .setBlockState(new BlockPos(8, 68, 8), Blocks.STONE.getDefaultState());
        var pose = CapsulePlacementMath.pose(placement, work);
        var capture = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(fixture.world(), actor, CapsuleFootContacts.window(accepted, reach),
                        new CollisionQueryFootprint.Limits(10000, 16, 100, 100)));
        if (reach > 0) assertEquals(1, capture.boxes().size());
        var result = assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,
                CapsuleFootContacts.collect(accepted, pose, capture, reach, FreeOwnerTransaction.ACCURACY,
                        new CapsuleContactQueryBudget(LIMITS, 100)));
        assertEquals(capture.boxes().size(), result.fullObservation().queries().size());
        assertEquals(capture.boxes().size(), result.queries().size());
        return result;
    }
}
