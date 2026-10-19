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

class CapsuleSupportObservationTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000004273");
    private static final CapsuleAabbContact3d.QueryLimits LIMITS =
            new CapsuleAabbContact3d.QueryLimits(8192, 20000, 128, 32, 2336, 40000, 64);

    private record Inputs(EntityPlayerMP actor, PlayerPhysicalSnapshot accepted, CapsulePlacementMath.Pose pose,
                          WorldCollisionCandidates.Snapshot capture, CapsuleContactQueryBudget queries,
                          StoredDyadic.Work work, CapsuleSupportObservation.Complete result) {
        CapsuleSupportObservation.Result repeat() {
            return CapsuleSupportObservation.observe(accepted, pose, capture,
                    CapsuleFootContacts.window(accepted, .25), .25, FreeOwnerTransaction.ACCURACY, queries, work);
        }
    }
    @Test void flatSupportRetainsExactWitnessAndOperationIdentity() throws Exception {
        try (var fixture = BootWorldFixture.open(temp.resolve("flat"), PLAYER, false)) {
            var input = inputs(fixture, new Vec3d(8.5,69.25,8.5), CardinalFrame.Y_POS.attitude(), new StoredDyadic.Work());
            var support = input.result.supports().getFirst();
            assertEquals(1, input.result.supports().size());
            assertSame(input.result.foot().queries().getFirst().contact().witness().orElseThrow(), support.witness());
            assertEquals(input.capture.boxes().getFirst(), support.rawAabb());
            assertTrue(input.result.matches(input.accepted,input.pose,input.capture,input.result.foot().window(),input.queries,input.work));
            assertFalse(input.result.matches(input.accepted,input.pose,input.capture,input.result.foot().window(),input.queries,new StoredDyadic.Work()));
            assertTrue(input.result.workAfter()>input.result.workBefore());
        }
    }
    @Test void tiltedContactOnBothSidesPreservesSlopeUncertainty() throws Exception {
        for(double cosine : new double[]{.5001,.4999}) {
            try(var fixture=BootWorldFixture.open(temp.resolve("tilt"+cosine),PLAYER,false)) {
                double angle=Math.acos(cosine);
                var input=inputs(fixture,new Vec3d(8.5,69.25,8.5),new Rotation3d(0,0,Math.sin(angle/2),Math.cos(angle/2)),new StoredDyadic.Work());
                assertEquals(cosine>.5?1:0,input.result.supports().size());
                assertEquals(1,input.result.evaluations().size());
                if(cosine<.5) assertEquals(CapsuleSupportSlope.Reason.SLOPE_UNCERTAIN,
                        assertInstanceOf(CapsuleSupportSlope.Uncertain.class,input.result.evaluations().getFirst().slope()).reason());
            }
        }
    }
    @Test void nearDoesNotBecomeStanding() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("near"),PLAYER,false)) {
            var input=inputs(fixture,new Vec3d(8.5,69.375,8.5),CardinalFrame.Y_POS.attitude(),new StoredDyadic.Work());
            assertEquals(CapsuleFootContacts.Classification.NEAR,input.result.foot().queries().getFirst().classification());
            assertTrue(input.result.supports().isEmpty());
        }
    }
    @Test void contextAndWrongWindowFailWithoutCertifying() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("context"),PLAYER,false)) {
            var input=inputs(fixture,new Vec3d(8.5,69.25,8.5),CardinalFrame.Y_POS.attitude(),new StoredDyadic.Work());
            var wrong=assertInstanceOf(CapsuleSupportObservation.Failed.class,CapsuleSupportObservation.observe(input.accepted,input.pose,input.capture,
                    input.accepted.placement().bounds(),.25,FreeOwnerTransaction.ACCURACY,input.queries,input.work));
            assertEquals(CapsuleSupportObservation.Reason.WINDOW_MISMATCH,wrong.reason());
            fixture.world().setTotalWorldTime(fixture.world().getTotalWorldTime()+1);
            assertFalse(input.result.matches(input.accepted,input.pose,input.capture,input.result.foot().window(),input.queries,input.work));
            assertEquals(CapsuleSupportObservation.Reason.FOOT_FAILED,assertInstanceOf(CapsuleSupportObservation.Failed.class,input.repeat()).reason());
        }
    }
    @Test void numericExhaustionRetainsFootQueriesAndActualCause() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("numeric"),PLAYER,false)) {
            var input=inputs(fixture,new Vec3d(8.5,69.25,8.5),CardinalFrame.Y_POS.attitude(),new StoredDyadic.Work());
            input.work.charge(StoredDyadic.MAX_WORK_UNITS-input.work.units()-1);
            var failed=assertInstanceOf(CapsuleSupportObservation.Failed.class,input.repeat());
            assertEquals(CapsuleSupportObservation.Reason.NUMERIC_WORK_EXHAUSTED,failed.reason());
            assertInstanceOf(CapsuleFootContacts.VerifiedFootObservations.class,failed.foot().orElseThrow());
            var uncertain=assertInstanceOf(CapsuleSupportSlope.Uncertain.class,failed.evaluations().getFirst().slope());
            assertEquals(StoredDyadic.MAX_WORK_UNITS,uncertain.cause().orElseThrow().used());
            assertEquals(StoredDyadic.MAX_WORK_UNITS,failed.workAfter());
        }
    }
    @Test void fullBodyOverlapCannotBeHiddenByOldFootContact() throws Exception {
        try(var fixture=BootWorldFixture.open(temp.resolve("overlap"),PLAYER,false)) {
            var input=inputs(fixture,new Vec3d(8.5,69.25,8.5),CardinalFrame.Y_POS.attitude(),new StoredDyadic.Work());
            fixture.world().getChunkProvider().loadedChunks.get(net.minecraft.util.math.ChunkPos.asLong(0,0))
                    .setBlockState(new BlockPos(8,69,8),Blocks.STONE.getDefaultState());
            var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(fixture.world(),input.actor,
                    input.result.foot().window(),new CollisionQueryFootprint.Limits(10000,16,100,100)));
            assertEquals(2,capture.boxes().size());
            var failed=assertInstanceOf(CapsuleSupportObservation.Failed.class,CapsuleSupportObservation.observe(input.accepted,input.pose,capture,
                    input.result.foot().window(),.25,FreeOwnerTransaction.ACCURACY,input.queries,input.work));
            var foot=assertInstanceOf(CapsuleFootContacts.Failed.class,failed.foot().orElseThrow());
            var full=assertInstanceOf(CapsuleContactCollector.Failed.class,foot.fullObservation());
            assertEquals(CapsuleContactCollector.Failure.OVERLAPPING,full.failure());
            assertEquals(2,full.queries().size());
        }
    }
    private Inputs inputs(BootWorldFixture fixture, Vec3d center,
                                                                  Rotation3d attitude, StoredDyadic.Work work) {
        EntityPlayerMP actor = fixture.serverPlayer(PLAYER);
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
                WorldCollisionCandidates.capture(fixture.world(), actor, CapsuleFootContacts.window(accepted, .25),
                        new CollisionQueryFootprint.Limits(10000, 16, 100, 100)));
        assertEquals(1, capture.boxes().size());
        var queries = new CapsuleContactQueryBudget(LIMITS, 100);
        var result = assertInstanceOf(CapsuleSupportObservation.Complete.class,
                CapsuleSupportObservation.observe(accepted, pose, capture, CapsuleFootContacts.window(accepted, .25), .25, FreeOwnerTransaction.ACCURACY,
                        queries, work));
        assertEquals(1, result.foot().fullObservation().queries().size());
        assertEquals(1, result.foot().queries().size());
        return new Inputs(actor, accepted, pose, capture, queries, work, result);
    }
}


