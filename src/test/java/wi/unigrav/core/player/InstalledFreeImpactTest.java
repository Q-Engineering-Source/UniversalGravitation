package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Rotation3d;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.util.math.BlockPos;
import net.minecraft.init.Blocks;
import net.minecraftforge.common.MinecraftForge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Actual installed native tail + real resolver clips/Forge damage entry; not FML evidence. */
class InstalledFreeImpactTest {
    @TempDir Path directory;
    private FreeNativeTailTest fixtures() {
        var result=new FreeNativeTailTest();result.directory=directory;return result;
    }
    private static void finishJoinInvulnerability(net.minecraft.entity.player.EntityPlayerMP actor) {
        for (int tick = 0; tick < 60; tick++) actor.onUpdate();
        actor.width = .5F; actor.height = 1F; actor.eyeHeight = .25F;
        actor.motionX = 0; actor.motionY = 0; actor.motionZ = 0; actor.fallDistance = 0;
    }
    private InstalledFreeEndpointTest.Scene damageScene(String name) throws Exception {
        var fixtures = new InstalledFreeEndpointTest(); fixtures.directory = directory;
        return fixtures.scene(false, name, 10, InstalledFreeImpactTest::finishJoinInvulnerability);
    }
    private static CapsuleMoveFinalizer.Ready prepareIncoming(FreeNativeTailTest fixtures,
            InstalledFreeEndpointTest.Scene scene, Vec3d delta, Vec3d incomingBlocks) {
        var movement=fixtures.resolve(scene,delta);
        var actor=scene.tx().actor();
        actor.motionX=incomingBlocks.x();actor.motionY=incomingBlocks.y();actor.motionZ=incomingBlocks.z();
        return assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement));
    }
    private static final class Config implements AutoCloseable {
        private final GravityEntityBinder binder=UnigravMod.entityBinder;
        private final boolean enabled=UnigravConfig.impactDamage;
        Config(boolean enable) { UnigravMod.entityBinder=new GravityEntityBinder();UnigravConfig.impactDamage=enable; }
        public void close() { UnigravMod.entityBinder=binder;UnigravConfig.impactDamage=enabled; }
    }
    @Test void actualLandingConsumesConfiguredSpeedAfterNativeFallExactlyOnce() throws Exception {
        var fixtures=fixtures();
        try(var config=new Config(true);var scene=damageScene("impact-fall");
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            var landed=new AtomicInteger();var attacks=new AtomicInteger();var actor=scene.tx().actor();
            fixtures.put(scene,new Block(Material.ROCK) {
                @Override public void onLanded(World world,Entity entity) { landed.incrementAndGet();super.onLanded(world,entity); }
            });
            var listener=new FreeImpactAttackTestListener(event->{if(event.getEntityLiving()==actor)attacks.incrementAndGet();});
            listener.registerChecked();
            try {
                actor.fallDistance=8;
                var ready=prepareIncoming(fixtures,scene,new Vec3d(0,-.125,0),new Vec3d(0,-1,0));
                ready.commit();
                assertEquals(15.716,actor.getHealth(),1e-4);assertEquals(0,actor.fallDistance);
                assertEquals(1,landed.get());assertEquals(1,attacks.get());
                long calls=scene.tx().queries().calls();
                assertThrows(RuntimeException.class,ready::commit);
                assertEquals(1,attacks.get());assertTrue(scene.tx().queries().calls()>=calls);
            } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        }
    }
    @Test void configurationOffKeepsActualNativeDistanceDamage() throws Exception {
        var fixtures=fixtures();
        try(var config=new Config(false);var scene=damageScene("native-fall");
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            scene.tx().actor().fallDistance=8;
            var ready=prepareIncoming(fixtures,scene,new Vec3d(0,-.125,0),new Vec3d(0,-1,0));
            ready.commit();assertEquals(15,scene.tx().actor().getHealth());
            assertEquals(0,scene.tx().actor().fallDistance);
        }
    }
    @Test void obliqueWallOnlyChargesRemovedNormalAndPreservesUnclippedTangent() throws Exception {
        var fixtures=fixtures();
        try(var config=new Config(true);var scene=fixtures.oriented("wall-tangent",CardinalFrame.Y_POS.attitude(),true,new Vec3d(-.75,-.5,0),InstalledFreeImpactTest::finishJoinInvulnerability);
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            fixtures.put(scene,new Block(Material.ROCK));
            var movement=fixtures.resolve(scene,new Vec3d(.125,0,.125));
            assertTrue(movement.clipEvents().stream().anyMatch(event->event.axis()==0));
            assertFalse(movement.clipEvents().stream().anyMatch(event->event.axis()==2));
            var actor=scene.tx().actor();actor.motionX=1;actor.motionZ=.75;
            var ready=assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement));
            ready.commit();
            assertEquals(15.716,actor.getHealth(),1e-4,"15m/s unremoved tangent must not increase the 20m/s impact");
            assertEquals(.75,actor.motionZ,0);
        }
    }
    @Test void arbitraryAttitudeTransformsRemovedVectorInsteadOfRelabelingWorldAxis() throws Exception {
        var fixtures=fixtures();var q=new Rotation3d(0,0,Math.sin(Math.PI/8),Math.cos(Math.PI/8));
        try(var config=new Config(true);var scene=fixtures.oriented("rotated-wall",q,true,new Vec3d(-.75,-.5,0),InstalledFreeImpactTest::finishJoinInvulnerability);
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            fixtures.put(scene,new Block(Material.ROCK));
            var movement=fixtures.resolve(scene,new Vec3d(.125,0,0));
            assertTrue(movement.clipEvents().stream().anyMatch(event->event.axis()==0));
            var actor=scene.tx().actor();actor.motionX=2;
            var ready=assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement));
            double tangentSpeed=Math.abs(q.inverseRotate(new Vec3d(40,0,0)).x());
            ready.commit();assertEquals(20-.021*(tangentSpeed*tangentSpeed-196),actor.getHealth(),1e-4);
        }
    }
    @Test void duplicateAxisAndTwoAxisClipsAggregateIntoOneWallAttack() throws Exception {
        for(boolean doubleAxis:new boolean[]{false,true}) {
            var fixtures=fixtures();
            var offset=doubleAxis?new Vec3d(-.75,-.5,-.75):new Vec3d(-.75,-.5,-.5);
            try(var config=new Config(true);var scene=fixtures.oriented("aggregate-"+doubleAxis,CardinalFrame.Y_POS.attitude(),true,offset,InstalledFreeImpactTest::finishJoinInvulnerability);
                    var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
                var chunk=scene.fixture().world().getChunkProvider().getLoadedChunk(0,0);
                chunk.setBlockState(new BlockPos(8,68,7),Blocks.STONE.getDefaultState());
                chunk.setBlockState(new BlockPos(doubleAxis?7:8,68,8),Blocks.STONE.getDefaultState());
                var movement=fixtures.resolve(scene,doubleAxis?new Vec3d(.125,0,.125):new Vec3d(.125,0,0));
                assertTrue(movement.clipEvents().stream().filter(event->event.axis()==0).count()>=(doubleAxis?1:2),
                        "must actually observe the duplicate-axis clips, not infer them from placed boxes");
                if(doubleAxis)assertTrue(movement.clipEvents().stream().anyMatch(event->event.axis()==2));
                var actor=scene.tx().actor();actor.motionX=.75;actor.motionZ=doubleAxis?.75:0;
                var ready=assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement));
                var attacks=new AtomicInteger();
                var listener=new FreeImpactAttackTestListener(event->{if(event.getEntityLiving()==actor)attacks.incrementAndGet();});
                listener.registerChecked();
                try {
                    ready.commit();double speed=doubleAxis?Math.hypot(15,15):15;
                    assertEquals(20-.021*(speed*speed-196),actor.getHealth(),1e-4);assertEquals(1,attacks.get());
                } finally {MinecraftForge.EVENT_BUS.unregister(listener);}
            }
        }
    }
    @Test void noClipOrOutgoingMotionCannotInventAnImpact() throws Exception {
        var fixtures=fixtures();
        try(var config=new Config(true);var scene=fixtures.oriented("outgoing",CardinalFrame.Y_POS.attitude(),true,new Vec3d(-.75,-.5,0),InstalledFreeImpactTest::finishJoinInvulnerability);
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            fixtures.put(scene,new Block(Material.ROCK));
            var ready=prepareIncoming(fixtures,scene,new Vec3d(-.125,0,0),new Vec3d(-2,0,0));
            ready.commit();assertEquals(20,scene.tx().actor().getHealth());
        }
    }
    @Test void realDamageCallbackFailureKeepsOriginalCauseAndCannotReplayAttack() throws Exception {
        var fixtures=fixtures();
        try(var config=new Config(true);var scene=damageScene("damage-throw");
                var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
            var actor=scene.tx().actor();var attacks=new AtomicInteger();var original=new IllegalStateException("actual impact callback sentinel");
            var listener=new FreeImpactAttackTestListener(event->{if(event.getEntityLiving()==actor){attacks.incrementAndGet();throw original;}});
            listener.registerChecked();
            try {
                var ready=prepareIncoming(fixtures,scene,new Vec3d(0,-.125,0),new Vec3d(0,-1,0));
                assertSame(original,assertThrows(RuntimeException.class,ready::commit));
                assertSame(original,scene.tx().originalFailure().orElseThrow());assertEquals(1,attacks.get());
                long calls=scene.tx().queries().calls();assertThrows(RuntimeException.class,ready::commit);
                assertEquals(1,attacks.get());assertTrue(scene.tx().queries().calls()>=calls);
            } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        }
    }
    @Test void impactCallbackRetirementStopsTheOldTailWithoutASecondAttack() throws Exception {
        for(boolean teleport:new boolean[]{false,true}) {
            var fixtures=fixtures();
            try(var config=new Config(true);var scene=damageScene("damage-retire-"+teleport);
                    var binding=UnigravMod.entityBinder.bind(scene.tx().actor(),GravityMode.REPLACE)) {
                var actor=scene.tx().actor();var attacks=new AtomicInteger();
                var listener=new FreeImpactAttackTestListener(event->{if(event.getEntityLiving()!=actor)return;
                    attacks.incrementAndGet();event.setCanceled(true);
                    if(teleport){actor.setPosition(actor.posX+4,actor.posY,actor.posZ);actor.motionX=7;actor.motionY=8;actor.motionZ=9;}
                    else scene.current().set(false);
                });
                listener.registerChecked();
                try {
                    var ready=prepareIncoming(fixtures,scene,new Vec3d(0,-.125,0),new Vec3d(0,-1,0));
                    long calls=scene.tx().queries().calls();
                    var stopped=assertThrows(FreeNativeTail.Stop.class,ready::commit);assertTrue(stopped.belongsTo(scene.tx()));
                    assertEquals(1,attacks.get());assertEquals(20,actor.getHealth());
                    assertTrue(scene.tx().queries().calls()>=calls);
                    if(teleport)assertEquals(new Vec3d(7,8,9),new Vec3d(actor.motionX,actor.motionY,actor.motionZ));
                } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
            }
        }
    }
}
