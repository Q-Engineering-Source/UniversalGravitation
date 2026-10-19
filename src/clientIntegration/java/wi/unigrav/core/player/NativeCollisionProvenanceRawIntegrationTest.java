package wi.unigrav.core.player;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.GetCollisionBoxesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Must be selected by the real FML/LaunchClassLoader host. Plain JUnit is NOT a substitute. */
public class NativeCollisionProvenanceRawIntegrationTest {
    @TempDir Path temp;
    private static WorldCollisionCandidates.Snapshot capture(NativeCollisionProvenanceTest.Input i) {
        var result=WorldCollisionCandidates.capture(i.tx(),i.footprint().requestedBounds(),CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,
                NativeCollisionProvenanceTest.LIMITS,i.tx().resources().environmentObservationWork());
        // Missing/unapplied Mixin produces Unavailable and FAILS here; never assumption/skip.
        return assertInstanceOf(WorldCollisionCandidates.Snapshot.class,result);
    }
    public static final class EventEdit {
        final Entity actor; java.util.function.Consumer<List<AxisAlignedBB>> edit;
        EventEdit(Entity actor,java.util.function.Consumer<List<AxisAlignedBB>> edit){this.actor=actor;this.edit=edit;}
        @SubscribeEvent public void collision(GetCollisionBoxesEvent event) {
            if(event.getEntity()==actor)edit.accept(event.getCollisionBoxesList());
        }
    }
    @Test void actualWorldStoneAndForgeRemovalUseSameCapture() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("forge"),NativeCollisionProvenanceTest.PLAYER,false);
            var i=NativeCollisionProvenanceTest.input(f)) {
            var normal=capture(i); assertEquals(1,normal.boxes().size());
            assertEquals(NativeCollisionProvenance.Kind.BLOCK,normal.provenance().orElseThrow().entries().getFirst().kind());
            var listener=new EventEdit(i.tx().actor(),List::clear); MinecraftForge.EVENT_BUS.register(listener);
            try { var removed=capture(i); assertTrue(removed.boxes().isEmpty()); assertTrue(removed.provenance().orElseThrow().entries().isEmpty()); }
            finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        }
    }
    @Test void actualForgeCopiedObjectHasNoBlockAuthorityWhileSameObjectReinsertDoes() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("copy"),NativeCollisionProvenanceTest.PLAYER,false);
            var i=NativeCollisionProvenanceTest.input(f)) {
            var listener=new EventEdit(i.tx().actor(),list->{var box=list.remove(0);list.add(new AxisAlignedBB(box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ));});
            MinecraftForge.EVENT_BUS.register(listener);
            try {
                assertEquals(NativeCollisionProvenance.Kind.UNATTRIBUTED,capture(i).provenance().orElseThrow().entries().getFirst().kind());
                listener.edit=list->{var box=list.remove(0);list.add(box);};
                assertEquals(NativeCollisionProvenance.Kind.BLOCK,capture(i).provenance().orElseThrow().entries().getFirst().kind());
            } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        }
    }
    @Test void actualSlimeHasNativeProducerWithoutExpandingBootQualification() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("slime"),NativeCollisionProvenanceTest.PLAYER,false);
            var i=NativeCollisionProvenanceTest.input(f)) {
            f.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(NativeCollisionProvenanceTest.CELL,net.minecraft.init.Blocks.SLIME_BLOCK.getDefaultState());
            var captured=capture(i);var entry=captured.provenance().orElseThrow().entries().getFirst();
            assertEquals(NativeCollisionProvenance.Kind.BLOCK,entry.kind());assertSame(net.minecraft.init.Blocks.SLIME_BLOCK.getDefaultState(),entry.state());
            // This test establishes provenance, not the later bounce consumer's correctness.
        }
    }
    private static final class ActualProducer extends Block {
        AxisAlignedBB output; RuntimeException failure; Runnable after=()->{}; int calls;
        ActualProducer(AxisAlignedBB output){super(Material.ROCK);this.output=output;}
        @Override public void addCollisionBoxToList(IBlockState state,World world,BlockPos pos,AxisAlignedBB query,
                List<AxisAlignedBB> list,Entity actor,boolean actualState) {
            calls++;if(failure!=null)throw failure;
            if(output.intersects(query))list.add(output);after.run();
        }
    }
    @Test void realCustomProducerExceptionRetainsCauseAndStopsOldOwnerAfterTeleport() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("custom"),NativeCollisionProvenanceTest.PLAYER,false);
            var i=NativeCollisionProvenanceTest.input(f)) {
            var block=new ActualProducer(new AxisAlignedBB(8,68,8,9,69,9));
            f.world().getChunkProvider().getLoadedChunk(0,0).setBlockState(NativeCollisionProvenanceTest.CELL,block.getDefaultState());
            var failure=new IllegalStateException("real producer exception");block.failure=failure;
            var rejected=assertInstanceOf(WorldCollisionCandidates.Unavailable.class,WorldCollisionCandidates.capture(i.tx(),i.footprint().requestedBounds(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,NativeCollisionProvenanceTest.LIMITS,i.tx().resources().environmentObservationWork()));
            assertSame(failure,rejected.cause().orElseThrow());assertEquals(1,block.calls);assertFalse(NativeCollisionProvenance.scoped());
            block.failure=null;block.after=()->i.tx().actor().setPosition(i.tx().actor().posX+1,i.tx().actor().posY,i.tx().actor().posZ);
            assertInstanceOf(WorldCollisionCandidates.Unavailable.class,WorldCollisionCandidates.capture(i.tx(),i.footprint().requestedBounds(),
                    CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,NativeCollisionProvenanceTest.LIMITS,i.tx().resources().environmentObservationWork()));
            assertEquals(2,block.calls);assertFalse(NativeCollisionProvenance.scoped());
        }
    }
    @Test void equalGeometryFromSurvivingBIsNeverAssignedToRemovedAAndHiddenOutputChangeInvalidatesRecapture() throws Exception {
        try(var f=BootWorldFixture.open(temp.resolve("equal"),NativeCollisionProvenanceTest.PLAYER,false);
            var i=NativeCollisionProvenanceTest.input(f)) {
            var a=new ActualProducer(new AxisAlignedBB(8,68,8,9,69,9));var b=new ActualProducer(new AxisAlignedBB(8,68,8,9,69,9));
            var second=new BlockPos(9,68,8);var chunk=f.world().getChunkProvider().getLoadedChunk(0,0);
            chunk.setBlockState(NativeCollisionProvenanceTest.CELL,a.getDefaultState());chunk.setBlockState(second,b.getDefaultState());
            var listener=new EventEdit(i.tx().actor(),list->list.removeIf(box->box==a.output));MinecraftForge.EVENT_BUS.register(listener);
            try {
                var observed=capture(i);assertEquals(1,observed.boxes().size());
                var entry=observed.provenance().orElseThrow().entries().getFirst();assertEquals(second,entry.cell());assertSame(b.getDefaultState(),entry.state());
                b.output=new AxisAlignedBB(8,67.5,8,9,69,9);
                var changed=capture(i);assertFalse(observed.sameObservation(changed));
            } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        }
    }
}
