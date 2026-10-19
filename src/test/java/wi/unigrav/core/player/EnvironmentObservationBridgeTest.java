package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentObservationBridgeTest {
    @TempDir Path temp;
    private static final UUID PLAYER = new UUID(0, 4921);
    private static final CollisionQueryFootprint.Limits LIMITS = new CollisionQueryFootprint.Limits(16384,16,4096,4096);
    private record Input(FreeOwnerTransaction tx, AtomicBoolean current) implements AutoCloseable {
        public void close() { tx.close(); }
    }
    private Input input(BootWorldFixture fixture) {
        var actor = fixture.serverPlayer(PLAYER);
        var placement = new PlayerBodyPlacement(new Vec3d(8.5,68.5,8.5), CardinalFrame.Y_POS.attitude(),
                new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.25,.25,.125)));
        actor.width=.25F;actor.height=.25F;actor.eyeHeight=.125F;
        var p=placement.transport();actor.setPosition(p.x(),p.y(),p.z());var b=placement.bounds();
        actor.setEntityBoundingBox(new AxisAlignedBB(b.min().x(),b.min().y(),b.min().z(),b.max().x(),b.max().y(),b.max().z()));
        var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(PLAYER,UUID.randomUUID(),actor.dimension,1),7,placement,new Vec3d(0,0,0));
        var travel=new PlayerPhysicsStep.TravelState();
        var current=new AtomicBoolean(true);
        return new Input(new FreeOwnerTransaction(actor,accepted,travel,
                new BoostMovementBudget(10,0,new Vec3(p.x(),p.y(),p.z())),current::get),current);
    }
    @Test void nativeBridgePreservesShellCostAndClaimsOnlyActualAggregateOnce() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("native"),PLAYER,false);var i=input(f)) {
            var tx=i.tx();var work=tx.resources().environmentObservationWork();
            long before=tx.numeric().units();
            assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(tx,
                    tx.accepted().placement().bounds(),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,LIMITS));
            long legacyShell=tx.numeric().units()-before;
            before=tx.numeric().units();
            assertInstanceOf(WorldCollisionCandidates.Snapshot.class,WorldCollisionCandidates.capture(tx,
                    tx.accepted().placement().bounds(),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,LIMITS,work));
            assertEquals(2,work.usage().nativeAttempts());
            assertEquals(legacyShell,tx.numeric().units()-before,"same native shell is charged once, not twice");
            assertTrue(work.usage().units()>1);
            var foreign=new FreeTickResources.Store().acquire(this,f.world(),tx.accepted().scope(),0).environmentObservationWork();
            assertThrows(IllegalArgumentException.class,()->WorldCollisionCandidates.capture(tx,
                    tx.accepted().placement().bounds(),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,LIMITS,foreign));
            assertEquals(2,work.usage().nativeAttempts());
            i.current().set(false);
            assertThrows(IllegalStateException.class,()->WorldCollisionCandidates.capture(tx,
                    tx.accepted().placement().bounds(),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,LIMITS,work));
        }
    }
    @Test void actualEnvironmentCallbackAndPreparedRecheckShareWorkWithoutNewObservation() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("trigger"),PLAYER,false);var i=input(f)) {
            var tx=i.tx();var work=tx.resources().environmentObservationWork();
            var block=new BurningProbe();f.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,68,8),block.getDefaultState());
            var pose=CapsulePlacementMath.pose(tx.accepted().placement(),tx.numeric());
            var prepared=assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,CapsuleEnvironmentObservation.observe(tx,pose,work));
            assertTrue(prepared.burning());assertEquals(1,block.calls);
            long units=work.usage().units();long queries=tx.queries().calls();
            assertTrue(prepared.matches(tx,pose));assertTrue(work.usage().units()>units);
            assertEquals(queries,tx.queries().calls());assertEquals(1,block.calls);
            i.current().set(false);units=work.usage().units();
            assertThrows(IllegalStateException.class,()->prepared.matches(tx,pose));
            assertEquals(units,work.usage().units());
        }
    }
    @Test void originalCallbackFailureIsNotSuccessAndSpentWorkIsNotRefunded() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("cause"),PLAYER,false);var i=input(f)) {
            var tx=i.tx();var work=tx.resources().environmentObservationWork();var block=new BurningProbe();
            f.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,68,8),block.getDefaultState());
            var pose=CapsulePlacementMath.pose(tx.accepted().placement(),tx.numeric());block.failure=new IllegalStateException("actual burning failure");
            assertSame(block.failure,assertThrows(IllegalStateException.class,()->CapsuleEnvironmentObservation.observe(tx,pose,work)));
            assertEquals(1,block.calls);assertTrue(work.usage().units()>0);
            tx.resources().postprocessing().charge(FreePostprocessingWork.MAX_UNITS-work.usage().units());
            assertThrows(FreePostprocessingWork.Exhausted.class,()->CapsuleEnvironmentObservation.observe(tx,pose,work));
            assertEquals(1,block.calls);assertEquals(FreePostprocessingWork.MAX_UNITS,tx.resources().postprocessing().units());
        }
    }
    @Test void cardinalRainPointsUseCertifiedCapsuleWithoutFreeStoredGeometry() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("cardinal-rain"), PLAYER, false); var i = input(f)) {
            var tx = i.tx();
            var placement = new PlayerBodyPlacement(new Vec3d(8.5, 68.5, 8.5), CardinalFrame.X_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.X_POS, new PlayerBodyShape.Dimensions(.25, 3, .75)));
            assertNull(placement.storedCapsuleGeometry());
            var pose = CapsulePlacementMath.pose(placement, tx.numeric());
            var points = new java.util.ArrayList<BlockPos>();
            try (var rain = f.rainQueries(point -> { points.add(point); return points.size() == 3; })) {
                var observed = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, tx.actor(), pose, tx.queries(), tx::requireCurrent,
                                FreeNativeEnvironment.serverLoadedChunks(f.world())));
                assertTrue(observed.wet());
                assertEquals(java.util.List.of(rainPoint(pose.capsule().centerline().start()),
                        rainPoint(pose.capsule().centerline().end()), rainPoint(placement.center())), points);
                assertTrue(observed.matches(tx, pose));
                i.current().set(false);
                assertThrows(IllegalStateException.class, () -> observed.matches(tx, pose));
            }
        }
    }
    @Test void freeRainPointsRetainStoredEndpointOrderAndShortCircuit() throws Exception {
        try (var f = BootWorldFixture.open(temp.resolve("free-rain"), PLAYER, false); var i = input(f)) {
            var tx = i.tx();
            var placement = new PlayerBodyPlacement(new Vec3d(8.5, 68.5, 8.5), CardinalFrame.X_NEG.attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.25, 3, .75)));
            var pose = CapsulePlacementMath.pose(placement, tx.numeric());
            var stored = placement.storedCapsuleGeometry();
            var points = new java.util.ArrayList<BlockPos>();
            try (var rain = f.rainQueries(point -> { points.add(point); return points.size() == 2; })) {
                var observed = assertInstanceOf(CapsuleEnvironmentObservation.Prepared.class,
                        CapsuleEnvironmentObservation.observe(tx, tx.actor(), pose, tx.queries(), tx::requireCurrent,
                                FreeNativeEnvironment.serverLoadedChunks(f.world())));
                assertTrue(observed.wet());
                assertEquals(java.util.List.of(rainPoint(stored.negativeEndpoint()),
                        rainPoint(stored.positiveEndpoint())), points);
            }
        }
    }
    private static BlockPos rainPoint(Vec3d point) { return new BlockPos(point.x(), point.y(), point.z()); }
    private static class BurningProbe extends Block {
        int calls;RuntimeException failure;
        BurningProbe(){super(Material.ROCK);}
        @Override public boolean isBurning(net.minecraft.world.IBlockAccess world,BlockPos position) {
            calls++;if(failure!=null)throw failure;return true;
        }
    }
}
