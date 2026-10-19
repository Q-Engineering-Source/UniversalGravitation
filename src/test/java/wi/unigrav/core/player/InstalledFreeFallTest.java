package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real MP, real Ready.commit, and explicit TEST inherited-body bridge, never production-Mixin evidence. */
class InstalledFreeFallTest {
    private GravityEntityBinder previousBinder;
    @BeforeEach void installUnboundNativeFixtureBinder() {
        previousBinder=UnigravMod.entityBinder;
        UnigravMod.entityBinder=new GravityEntityBinder();
    }
    @AfterEach void restoreNativeFixtureBinder() { UnigravMod.entityBinder=previousBinder; }

    @TempDir Path directory;
    private FreeNativeTailTest helper() { var h=new FreeNativeTailTest();h.directory=directory;return h; }

    @Test void unsupportedActualTranslationAccumulatesNativeFloatFormulaWithoutBlock() throws Exception {
        var h=helper();
        try(var s=h.scene("fall-air")) {
            h.put(s,Blocks.AIR);var actor=s.tx().actor();actor.fallDistance=2.5F;
            var movement=h.resolve(s,new Vec3d(0,-.125,0));
            assertEquals(-.125,FreeNativeTail.actualDelta(movement).y());
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(s.tx(),movement)).commit();
            assertFalse(actor.onGround);assertEquals((float)(2.5F+.125),actor.fallDistance);
            assertInstanceOf(InstalledFreeFallBridge.class,actor,"TEST inherited MP bridge is required, never skipped");
        }
    }
    @Test void touchStartExistingFallIsSettledExactlyOnceAfterWalkingAndWorldTail() throws Exception {
        var h=helper();var sequence=new ArrayList<String>();
        try(var s=h.scene("fall-touch-start")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onLanded(World w,Entity a){sequence.add("landed");super.onLanded(w,a);}
                @Override public void onEntityWalk(World w,BlockPos p,Entity a){sequence.add("walking");}
                @Override public void onEntityCollision(World w,BlockPos p,net.minecraft.block.state.IBlockState st,Entity a){sequence.add("inside");}
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){sequence.add("fall");assertEquals(2F,d);}
            });
            s.tx().actor().fallDistance=2F;var ready=h.prepare(s,new Vec3d(0,-.125,0));ready.commit();
            assertEquals(1,sequence.stream().filter("fall"::equals).count());
            assertTrue(sequence.indexOf("fall")>sequence.indexOf("walking"),sequence::toString);
            if(sequence.contains("inside"))assertTrue(sequence.indexOf("fall")>sequence.lastIndexOf("inside"),sequence::toString);
            assertEquals(0,s.tx().actor().fallDistance);
            assertThrows(IllegalStateException.class,ready::commit);
            assertEquals(1,sequence.stream().filter("fall"::equals).count());
        }
    }
    @Test void staticSupportSettlesExistingDistanceWithoutManufacturingOnLanded() throws Exception {
        var h=helper();var landed=new AtomicInteger();var falls=new AtomicInteger();
        try(var s=h.scene("fall-static")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onLanded(World w,Entity a){landed.incrementAndGet();}
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){falls.incrementAndGet();}
            });
            s.tx().actor().fallDistance=2F;h.prepare(s,new Vec3d(0,0,0)).commit();
            assertEquals(0,landed.get());assertEquals(1,falls.get());assertEquals(0,s.tx().actor().fallDistance);
        }
    }
    @Test void sixAxisActualTranslationAccumulatesAgainstEachActualAttitude() throws Exception {
        var h=helper();
        for(var frame:CardinalFrame.values())try(var s=h.oriented("fall-air-"+frame,frame.attitude(),false)) {
            h.put(s,Blocks.AIR);s.tx().actor().fallDistance=1F;
            var delta=frame.attitude().rotate(new Vec3d(0,-.125,0));
            h.prepare(s,delta).commit();assertFalse(s.tx().actor().onGround);
            assertEquals(1.125F,s.tx().actor().fallDistance,frame.toString());
        }
    }
    @Test void originalFallThrowConsumesAttemptedDistanceAndKeepsInstalledCustody() throws Exception {
        var h=helper();var original=new IllegalStateException("actual native fallen callback");
        try(var s=h.scene("fall-throw")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){throw original;}
            });
            var actor=s.tx().actor();actor.fallDistance=2F;var ready=h.prepare(s,new Vec3d(0,0,0));
            var failure=assertThrows(RuntimeException.class,ready::commit);
            assertTrue(failure==original || failure.getCause()==original,failure::toString);
            assertSame(failure,s.tx().originalFailure().orElseThrow());assertEquals(0F,actor.fallDistance);
            assertTrue(s.tx().installationCustody().isPresent());
        }
    }
    @Test void callbackTeleportCannotResetSuccessorFallDistance() throws Exception {
        var h=helper();
        try(var s=h.scene("fall-teleport")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){a.setPosition(a.posX+8,a.posY,a.posZ);a.fallDistance=19F;}
            });
            var actor=s.tx().actor();actor.fallDistance=2F;
            assertThrows(RuntimeException.class,()->h.prepare(s,new Vec3d(0,0,0)).commit());
            assertEquals(19F,actor.fallDistance);
        }
    }
    @Test void callbackProducerChangeFailsBeforeNativeReset() throws Exception {
        var h=helper();
        try(var s=h.scene("fall-source-change")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){
                    s.fixture().world().getChunkProvider().getLoadedChunk(0,0).setBlockState(new BlockPos(8,68,8),Blocks.DIAMOND_BLOCK.getDefaultState());
                }
            });
            var actor=s.tx().actor();actor.fallDistance=2F;
            assertThrows(RuntimeException.class,()->h.prepare(s,new Vec3d(0,0,0)).commit());
            assertEquals(2F,actor.fallDistance);
        }
    }
    @Test void equalReplacementSupportHasNoBlockAuthorityButDoesNotLeakDistance() throws Exception {
        var h=helper();var forbidden=new AtomicInteger();
        try(var s=h.scene("fall-unattributed")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){forbidden.incrementAndGet();}
            });
            var listenerCalls=new AtomicInteger();var replacedBoxes=new AtomicInteger();
            var listener=new CollisionBoxesTestListener(event -> {
                    if(event.getEntity()!=s.tx().actor())return;
                    listenerCalls.incrementAndGet();
                    var boxes=event.getCollisionBoxesList();
                    for(int i=0;i<boxes.size();i++){var old=boxes.get(i);
                        var replacement=new AxisAlignedBB(old.minX,old.minY,old.minZ,old.maxX,old.maxY,old.maxZ);
                        assertNotSame(old,replacement);boxes.set(i,replacement);replacedBoxes.incrementAndGet();}
            });
            MinecraftForge.EVENT_BUS.register(listener);
            try {s.tx().actor().fallDistance=2F;h.prepare(s,new Vec3d(0,0,0)).commit();
                assertTrue(listenerCalls.get()>0,"real EventBus listener must execute inside World.getCollisionBoxes");
                assertTrue(replacedBoxes.get()>0,"the event must replace at least one actual collider identity");
                assertTrue(s.tx().actor().onGround);assertEquals(0,forbidden.get());assertEquals(0,s.tx().actor().fallDistance);
            } finally {MinecraftForge.EVENT_BUS.unregister(listener);}
        }
    }
    @Test void sameRealMpAcrossThreeNewTicksAccumulatesWithoutResetOrSecondInstall() throws Exception {
        var h=helper();
        try(var first=h.scene("fall-multiple-ticks")) {
            h.put(first,Blocks.AIR);var actor=first.tx().actor();actor.fallDistance=1F;
            h.prepare(first,new Vec3d(0,-.125,0)).commit();assertEquals(1.125F,actor.fallDistance);
            var accepted=first.tx().installedJournal().orElseThrow();first.tx().close();
            var store=new FreeTickResources.Store();
            for(int tick=1;tick<=2;tick++) {
                first.fixture().world().setTotalWorldTime(first.fixture().world().getTotalWorldTime()+1);
                var p=accepted.placement().transport();
                var ledger=new wi.unigrav.core.boost.BoostMovementBudget(20,first.fixture().world().getTotalWorldTime(),new wi.unigrav.api.math.Vec3(p.x(),p.y(),p.z()));
                var resources=store.acquire(first.travel(),first.fixture().world(),accepted.scope(),first.travel().correctionRevision());
                try(var tx=new FreeOwnerTransaction(actor,accepted,first.travel(),ledger,first.current()::get,first.travel(),resources)) {
                    var next=new InstalledFreeEndpointTest.Scene(first.fixture(),tx,first.current(),first.travel(),ledger,accepted.placement(),null);
                    h.prepare(next,new Vec3d(0,-.125,0)).commit();
                    assertEquals(1F+(tick+1)*.125F,actor.fallDistance);
                    accepted=tx.installedJournal().orElseThrow();
                }
            }
        }
    }
    @Test void callbackDeathCannotPerformTheOldNativeReset() throws Exception {
        var h=helper();
        try(var s=h.scene("fall-death")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){a.setDead();a.fallDistance=23F;}
            });
            var actor=s.tx().actor();actor.fallDistance=2F;
            assertThrows(RuntimeException.class,()->h.prepare(s,new Vec3d(0,0,0)).commit());
            assertEquals(23F,actor.fallDistance);
        }
    }
    @Test void callbackOwnerRetirementStopsBeforeResetAndDoesNotRefundWork() throws Exception {
        var h=helper();
        try(var s=h.scene("fall-retire")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){s.current().set(false);a.fallDistance=29F;}
            });
            var actor=s.tx().actor();actor.fallDistance=2F;
            assertThrows(RuntimeException.class,()->h.prepare(s,new Vec3d(0,0,0)).commit());
            assertEquals(29F,actor.fallDistance);
            assertTrue(s.tx().queries().calls()>0);
        }
    }

    @Test void nonAxisActualTranslationUsesItsOwnAttitudeRatherThanWorldY() throws Exception {
        var h=helper();var q=new dev.crlhitbox.api.geometry.Rotation3d(Math.sqrt(.2),0,0,Math.sqrt(.8));
        try(var s=h.oriented("fall-non-axis",q,true)) {
            h.put(s,Blocks.AIR);s.tx().actor().fallDistance=1F;
            var delta=q.rotate(new Vec3d(0,-.125,0));
            var movement=h.resolve(s,delta);
            double local=0;
            for(var segment:movement.segments())local+=segment.tube().start().placement().attitude().inverseRotate(
                segment.end().placement().transport().subtract(segment.tube().start().placement().transport())).y();
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(s.tx(),movement)).commit();
            assertEquals((float)(1F-local),s.tx().actor().fallDistance);
            assertNotEquals((float)(1F-delta.y()),s.tx().actor().fallDistance);
        }
    }
    @Test void livingLandingEffectsUseTheActualNativeChainOnceBeforeFall() throws Exception {
        var h=helper();var order=new ArrayList<String>();
        try(var s=h.scene("fall-effects")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public boolean addLandingEffects(net.minecraft.block.state.IBlockState state,
                    net.minecraft.world.WorldServer w,BlockPos p,net.minecraft.block.state.IBlockState source,
                    net.minecraft.entity.EntityLivingBase actor,int count) {order.add("effects");assertTrue(count>0);return true;}
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float distance){order.add("fall");assertEquals(4F,distance);}
            });
            s.tx().actor().fallDistance=4F;h.prepare(s,new Vec3d(0,0,0)).commit();
            assertEquals(java.util.List.of("effects","fall"),order);assertEquals(0,s.tx().actor().fallDistance);
        }
    }

    @Test void realModeFinalizerSettlesFallOnceWithTheSameInstalledConsumer() throws Exception {
        var h=helper();var falls=new AtomicInteger();
        try(var s=h.scene("fall-real-mode")) {
            h.put(s,new Block(Material.ROCK) {
                @Override public void onFallenUpon(World w,BlockPos p,Entity a,float d){falls.incrementAndGet();}
            });
            var actor=s.tx().actor();actor.fallDistance=2F;
            var channel=new io.netty.channel.embedded.EmbeddedChannel(actor.connection.netManager);
            PlayerPhysicsInboundConnections.onServerConnectedEvent(new net.minecraftforge.fml.common.network.FMLNetworkEvent.ServerConnectionFromClientEvent(actor.connection.netManager));
            try {
                var connection=PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(actor.connection.netManager,
                    actor.connection,net.minecraft.network.EnumPacketDirection.SERVERBOUND).orElseThrow();
                var handle=PlayerPhysicsInboundConnections.INSTANCE.openScopeForOwner(connection,s.tx().accepted().scope()).orElseThrow();
                var request=new PlayerAttitudeRequest(4,s.tx().accepted().scope(),1,0,s.fixture().world(),connection,handle,0,
                    s.tx().accepted().placement().attitude());
                var result=ModeTransitionPrepared.prepareFreeTick(s.tx(),request,null,new Vec3d(0,0,.125),0);
                var prepared=assertInstanceOf(ModeTransitionPrepared.Prepared.class,result,result::toString);
                var installed=ModeTransitionFinalizer.install(prepared,0);
                assertInstanceOf(ModeTransitionFinalizer.Applied.class,installed,installed::toString);
                assertEquals(1,falls.get());assertEquals(0,actor.fallDistance);
            } finally {
                PlayerPhysicsInboundConnections.onServerDisconnectedEvent(new net.minecraftforge.fml.common.network.FMLNetworkEvent.ServerDisconnectionFromClientEvent(actor.connection.netManager));
                channel.finishAndReleaseAll();
            }
        }
    }

    @Test void nativeLandingEffectsFailureBeforeFallAttemptCannotConsumeDistance() throws Exception {
        var h = helper();
        var failure = new IllegalStateException("actual landing effects failed before onFallenUpon");
        var effects = new AtomicInteger(); var falls = new AtomicInteger();
        try (var s = h.scene("fall-pre-attempt-failure")) {
            h.put(s, new Block(Material.ROCK) {
                @Override public boolean addLandingEffects(net.minecraft.block.state.IBlockState state,
                        net.minecraft.world.WorldServer world, BlockPos cell,
                        net.minecraft.block.state.IBlockState source,
                        net.minecraft.entity.EntityLivingBase actor, int count) {
                    effects.incrementAndGet(); throw failure;
                }
                @Override public void onFallenUpon(World world, BlockPos cell, Entity actor, float distance) {
                    falls.incrementAndGet();
                }
            });
            var actor = s.tx().actor(); actor.fallDistance = 4F;
            var ready = h.prepare(s, new Vec3d(0, 0, 0));
            assertSame(failure, assertThrows(IllegalStateException.class, ready::commit));
            assertEquals(1, effects.get()); assertEquals(0, falls.get());
            assertEquals(4F, actor.fallDistance, "entering the inherited call does not prove a fall attempt");
            assertSame(failure, s.tx().originalFailure().orElseThrow());
        }
    }

}
