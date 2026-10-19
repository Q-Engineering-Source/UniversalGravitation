package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Throwaway feasibility experiment, never an installation or recovery permission. */
class CardinalClearancePreparedTest {
    @Test void exactDerivedClearanceThenGenericArcUsesOneOriginalOwnerLedger(@TempDir Path directory) {
        try(var fixture=BootWorldFixture.open(directory,new UUID(0,12621),true)) {
            var actor=fixture.serverPlayer(new UUID(0,12622));
            var bounds=new Aabb(new Vec3d(37.19999998807907,88.59999996423721,1),
                    new Vec3d(37.80000001192093,89.19999998807907,2.7999999523162837));
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),bounds.max().x(),bounds.max().y(),bounds.max().z()));
            actor.resetPositionToBB();
            var dims=new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight());
            var placement=PlayerBodyPlacement.fromCardinalBounds(bounds,new Vec3d(actor.posX,actor.posY,actor.posZ),
                    CardinalFrame.Z_POS.attitude(),new PlayerBodyShape.Cardinal(CardinalFrame.Z_POS,dims));
            assertEquals(new Vec3d(37.5,88.89999997615814,1.8999999761581419),placement.center());
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,12623),0,2),12178,placement,new Vec3d(0,0,0));
            fixture.installCollisionFootprint(CollisionQueryFootprint.plan(new Aabb(new Vec3d(35,86,-1),new Vec3d(40,92,5)),World.MAX_ENTITY_RADIUS,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,new CollisionQueryFootprint.Limits(16384,16,4096,4096)));
            fixture.world().getChunkProvider().getLoadedChunk(2,0).setBlockState(new BlockPos(37,88,0),Blocks.STONE.getDefaultState());fixture.world().getChunkProvider().getLoadedChunk(2,0).setBlockState(new BlockPos(37,89,0),Blocks.STONE.getDefaultState());
            var budget=new BoostMovementBudget(70,0,new Vec3(actor.posX,actor.posY,actor.posZ));var budgetBefore=budget.snapshot();
            var owner=new PlayerPhysicsStep.TravelState();
            var resources=new FreeTickResources.Store().acquire(owner,fixture.world(),accepted.scope(),owner.correctionRevision());
            try(var tx=new FreeOwnerTransaction(actor,accepted,owner,budget,()->true,owner,resources)) {
                var result=CardinalClearancePrepared.prepare(tx,CardinalFrame.Y_POS);
                assertEquals(bounds,new Aabb(new Vec3d(actor.getEntityBoundingBox().minX,actor.getEntityBoundingBox().minY,actor.getEntityBoundingBox().minZ),
                        new Vec3d(actor.getEntityBoundingBox().maxX,actor.getEntityBoundingBox().maxY,actor.getEntityBoundingBox().maxZ)));
                assertEquals(budgetBefore,budget.snapshot());
                var prepared=assertInstanceOf(CardinalClearancePrepared.Prepared.class,result,()->result.toString());
                assertTrue(prepared.current());
                assertSame(accepted,prepared.continuation().actualSource());
                assertSame(prepared.translation().target(),prepared.arcRecipe().recipe().start().placement());
                assertEquals(Math.nextUp(placement.center().z()),prepared.target().center().z());
                assertTrue(prepared.arcRecipe().path().provesAxisHalfSpace(2,1,1,0,1,tx.numeric()));
                System.out.println("CLEARANCE_PREPARED work="+tx.numeric().units()+" queries="+tx.queries().calls()+" path="+prepared.centerPathUpper());

            }
        }
    }
}
