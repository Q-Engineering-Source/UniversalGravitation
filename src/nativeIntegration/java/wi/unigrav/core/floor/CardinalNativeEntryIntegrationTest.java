package wi.unigrav.core.floor;

import com.mojang.authlib.GameProfile;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ug.poolobserver.PoolEvents;
import wi.unigrav.core.player.CardinalNativeBoundary;
import wi.unigrav.core.player.FreeTickResources;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.NativeBudgetFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the normal FML host and transformed native classes; never a plain-JUnit substitute. */
public class CardinalNativeEntryIntegrationTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings={"normal","relocate","throw"})
    void actualWorldWaterUnwindReleasesItsActualPoolObjectOnce(String mode) {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var actor=new EntityPlayerMP(fixture.server(),fixture.world(),new GameProfile(UUID.randomUUID(),"native"),
                    new PlayerInteractionManager(fixture.world()));
            actor.setPosition(8.5,80,8.5);
            var calls=new AtomicInteger();var pooled=new AtomicReference<BlockPos>();
            var sentinel=new IllegalStateException("actual native water producer");
            Block water=new Block(Material.WATER) {
                @Override public Boolean isEntityInsideMaterial(net.minecraft.world.IBlockAccess world,BlockPos position,
                        net.minecraft.block.state.IBlockState state,Entity entity,double y,Material material,boolean testingHead) {
                    return material==Material.WATER?Boolean.TRUE:null;
                }
                @Override public Vec3d modifyAcceleration(World world,BlockPos position,Entity entity,Vec3d acceleration) {
                    calls.incrementAndGet();pooled.set(position);
                    if(mode.equals("relocate")) {
                        actor.setPosition(12.5,84,12.5);actor.motionX=.123;actor.motionY=.234;actor.motionZ=.345;actor.fallDistance=37;
                    }
                    if(mode.equals("throw"))throw sentinel;
                    return acceleration.add(1,0,0);
                }
            };
            fixture.put(new BlockPos(8,80,8),water.getDefaultState());
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),actor.dimension,1);
            var work=new FreeTickResources.Store().acquire(actor,fixture.world(),scope,0).environmentObservationWork();
            AxisAlignedBB initial=actor.getEntityBoundingBox();Object token=new Object();
            try(var session=PoolEvents.begin("actual-water-"+mode)) {
                try(var boundary=CardinalNativeBoundary.open(token,actor,work,()->{
                    if(!initial.equals(actor.getEntityBoundingBox()))throw new CardinalNativeBoundary.AuthorityLost("actual endpoint changed");
                })) {
                    Runnable nativeCall=()->fixture.world().handleMaterialAcceleration(
                            new AxisAlignedBB(8.1,80.1,8.1,8.9,80.9,8.9),Material.WATER,actor);
                    if(mode.equals("normal")) {nativeCall.run();assertEquals(.014,actor.motionX,1e-12);}
                    else if(mode.equals("throw"))assertSame(sentinel,assertThrows(IllegalStateException.class,nativeCall::run));
                    else {
                        var abort=assertThrows(CardinalNativeBoundary.Abort.class,nativeCall::run);
                        assertTrue(abort.belongsTo(token));assertEquals(CardinalNativeBoundary.AbortKind.AUTHORITY_LOST,abort.kind());
                        assertEquals(.123,actor.motionX);assertEquals(.234,actor.motionY);assertEquals(.345,actor.motionZ);assertEquals(37,actor.fallDistance);
                    }
                }
                assertEquals(1,calls.get());assertNotNull(pooled.get());
                long identity=session.objectId(pooled.get());
                assertEquals(1,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RETAIN")).count());
                assertEquals(1,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RELEASE")).count());
        writeEvents(session);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualAreaLoadedPreflightFencesBothNativeReturnBranches(boolean loaded) {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var actor=new EntityPlayerMP(fixture.server(),fixture.world(),new GameProfile(UUID.randomUUID(),"preflight"),
                    new PlayerInteractionManager(fixture.world()));actor.setPosition(8.5,80,8.5);
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),actor.dimension,1);
            var work=new FreeTickResources.Store().acquire(actor,fixture.world(),scope,0).environmentObservationWork();
            var initial=actor.getEntityBoundingBox();var calls=new AtomicInteger();Object token=new Object();
            fixture.onNextChunkLoaded(()->{calls.incrementAndGet();actor.setPosition(12.5,84,12.5);actor.motionX=.123;});
            try(var session=PoolEvents.begin("actual-preflight-"+loaded);
                var boundary=CardinalNativeBoundary.open(token,actor,work,()->{
                    if(!initial.equals(actor.getEntityBoundingBox()))throw new CardinalNativeBoundary.AuthorityLost("preflight actor changed");
                })) {
                double x=loaded?8.1:40.1;
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->fixture.world().handleMaterialAcceleration(
                        new AxisAlignedBB(x,80.1,8.1,x+.8,80.9,8.9),Material.WATER,actor));
                assertTrue(abort.belongsTo(token));assertEquals(1,calls.get());assertEquals(.123,actor.motionX);
                assertTrue(session.events().isEmpty(),"preflight returns before actual pool retain");
            }
        }
    }

    @org.junit.jupiter.api.Test
    void actualPreexhaustedFrameNeverDispatchesNativePreflightOrRetainsPool() {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var actor=player(fixture);var resource=resource(fixture,actor);
            NativeBudgetFixture.exhaust(resource);
            var calls=new AtomicInteger();fixture.onNextChunkLoaded(calls::incrementAndGet);Object token=new Object();
            try(var session=PoolEvents.begin("actual-exhausted");
                var boundary=CardinalNativeBoundary.open(token,actor,resource.environmentObservationWork(),()->{})) {
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->fixture.world().handleMaterialAcceleration(
                        new AxisAlignedBB(8.1,80.1,8.1,8.9,80.9,8.9),Material.WATER,actor));
                assertTrue(abort.belongsTo(token));NativeBudgetFixture.assertOriginalExhaustedCause(abort.getCause());
                assertEquals(0,calls.get());assertTrue(session.events().isEmpty());
                NativeBudgetFixture.assertSpentWithoutRefund(resource);
                assertEquals(0,actor.motionX);
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualMpLandingKeepsBothEffectsBranchesAndFallenCallbackOnce(boolean effects) {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var actor=player(fixture);actor.setPosition(8.5,81,8.5);actor.fallDistance=6;
            var landing=new AtomicInteger();var fallen=new AtomicInteger();
            Block support=new Block(Material.ROCK) {
                @Override public boolean addLandingEffects(net.minecraft.block.state.IBlockState state,
                        net.minecraft.world.WorldServer world,BlockPos position,net.minecraft.block.state.IBlockState actual,
                        net.minecraft.entity.EntityLivingBase entity,int particles) {landing.incrementAndGet();return effects;}
                @Override public void onFallenUpon(World world,BlockPos position,Entity entity,float distance) {fallen.incrementAndGet();}
            };
            fixture.put(new BlockPos(8,80,8),support.getDefaultState());
            try(var boundary=CardinalNativeBoundary.open(new Object(),actor,resource(fixture,actor).environmentObservationWork(),()->{})) {
                actor.handleFalling(-1,true);boundary.check("actual landing complete");
            }
            assertEquals(1,landing.get());assertEquals(1,fallen.get());assertEquals(0,actor.fallDistance);
        }
    }

    @org.junit.jupiter.api.Test
    void nativeOverrideCannotClearLatchByCatchingWaterAbort() {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var caught=new AtomicReference<CardinalNativeBoundary.Abort>();
            var actor=new EntityPlayerMP(fixture.server(),fixture.world(),new GameProfile(UUID.randomUUID(),"caught"),
                    new PlayerInteractionManager(fixture.world())) {
                @Override public boolean handleWaterMovement() {
                    try{return super.handleWaterMovement();}
                    catch(CardinalNativeBoundary.Abort abort){caught.set(abort);return true;}
                }
            };actor.setPosition(8.5,80,8.5);actor.fallDistance=6;var initial=actor.getEntityBoundingBox();
            var pooled=new AtomicReference<BlockPos>();
            fixture.put(new BlockPos(8,80,8),new Block(Material.WATER) {
                @Override public Boolean isEntityInsideMaterial(net.minecraft.world.IBlockAccess world,BlockPos position,
                        net.minecraft.block.state.IBlockState state,Entity entity,double y,Material material,boolean testingHead) {
                    return material==Material.WATER?Boolean.TRUE:null;
                }
                @Override public Vec3d modifyAcceleration(World world,BlockPos position,Entity entity,Vec3d vector) {
                    pooled.set(position);actor.setPosition(12.5,84,12.5);actor.fallDistance=37;actor.motionX=.123;return vector.add(1,0,0);
                }
            }.getDefaultState());Object token=new Object();
            try(var session=PoolEvents.begin("actual-caught-abort");
                var boundary=CardinalNativeBoundary.open(token,actor,resource(fixture,actor).environmentObservationWork(),()->{
                    if(!initial.equals(actor.getEntityBoundingBox()))throw new CardinalNativeBoundary.AuthorityLost("successor");
                })) {
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->actor.handleFalling(-1,true));
                assertSame(caught.get(),abort);assertEquals(37,actor.fallDistance);assertEquals(.123,actor.motionX);
                assertPoolOnce(session,pooled.get());
            }
        }
    }

    @org.junit.jupiter.api.Test
    void actualNestedSuccessorWaterCompletesBeforeOldActorUnwind() {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var a=player(fixture);var b=player(fixture);b.setPosition(10.5,80,8.5);
            var initial=a.getEntityBoundingBox();var pa=new AtomicReference<BlockPos>();var pb=new AtomicReference<BlockPos>();
            var bCalls=new AtomicInteger();var bWork=resource(fixture,b).environmentObservationWork();
            fixture.put(new BlockPos(10,80,8),new Block(Material.WATER) {
                @Override public Boolean isEntityInsideMaterial(net.minecraft.world.IBlockAccess world,BlockPos position,
                        net.minecraft.block.state.IBlockState state,Entity entity,double y,Material material,boolean testingHead) {
                    return material==Material.WATER?Boolean.TRUE:null;
                }
                @Override public Vec3d modifyAcceleration(World world,BlockPos position,Entity entity,Vec3d vector) {
                    pb.set(position);bCalls.incrementAndGet();return vector.add(1,0,0);
                }
            }.getDefaultState());
            fixture.put(new BlockPos(8,80,8),new Block(Material.WATER) {
                @Override public Boolean isEntityInsideMaterial(net.minecraft.world.IBlockAccess world,BlockPos position,
                        net.minecraft.block.state.IBlockState state,Entity entity,double y,Material material,boolean testingHead) {
                    return material==Material.WATER?Boolean.TRUE:null;
                }
                @Override public Vec3d modifyAcceleration(World world,BlockPos position,Entity entity,Vec3d vector) {
                    pa.set(position);a.setPosition(12.5,84,12.5);a.motionX=.123;
                    try(var successor=CardinalNativeBoundary.open(new Object(),b,bWork,()->{})) {
                        assertTrue(fixture.world().handleMaterialAcceleration(new AxisAlignedBB(10.1,80.1,8.1,10.9,80.9,8.9),Material.WATER,b));
                        successor.check("B actual completed");
                    }
                    return vector.add(1,0,0);
                }
            }.getDefaultState());Object token=new Object();
            try(var session=PoolEvents.begin("actual-nested");
                var boundary=CardinalNativeBoundary.open(token,a,resource(fixture,a).environmentObservationWork(),()->{
                    if(!initial.equals(a.getEntityBoundingBox()))throw new CardinalNativeBoundary.AuthorityLost("A retired");
                })) {
                var abort=assertThrows(CardinalNativeBoundary.Abort.class,()->fixture.world().handleMaterialAcceleration(
                        new AxisAlignedBB(8.1,80.1,8.1,8.9,80.9,8.9),Material.WATER,a));
                assertTrue(abort.belongsTo(token));assertEquals(1,bCalls.get());assertEquals(.014,b.motionX,1e-12);assertEquals(.123,a.motionX);
                assertPoolOnce(session,pa.get());assertPoolOnce(session,pb.get());assertNotEquals(session.objectId(pa.get()),session.objectId(pb.get()));
            }
        }
    }

    @org.junit.jupiter.api.Test
    void noOwnerContextKeepsActualNativeCallbackThrowable() {
        assertNativeLoaders();
        try(var fixture=BootWorldFixture.open(directory,UUID.randomUUID(),false)) {
            prepareSpawn(fixture);
            var actor=player(fixture);var sentinel=new IllegalStateException("unowned original");var calls=new AtomicInteger();
            var pooled=new AtomicReference<BlockPos>();
            fixture.put(new BlockPos(8,80,8),new Block(Material.WATER) {
                @Override public Boolean isEntityInsideMaterial(net.minecraft.world.IBlockAccess world,BlockPos position,
                        net.minecraft.block.state.IBlockState state,Entity entity,double y,Material material,boolean testingHead) {
                    return material==Material.WATER?Boolean.TRUE:null;
                }
                @Override public Vec3d modifyAcceleration(World world,BlockPos position,Entity entity,Vec3d vector) {
                    pooled.set(position);calls.incrementAndGet();throw sentinel;
                }
            }.getDefaultState());
            try(var session=PoolEvents.begin("actual-unowned-throw")) {
                assertSame(sentinel,assertThrows(IllegalStateException.class,()->fixture.world().handleMaterialAcceleration(
                        new AxisAlignedBB(8.1,80.1,8.1,8.9,80.9,8.9),Material.WATER,actor)));
                assertEquals(1,calls.get());long identity=session.objectId(pooled.get());
                assertEquals(1,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RETAIN")).count());
                assertEquals(0,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RELEASE")).count(),
                        "unowned original native exception semantics are unchanged");
            }
        }
    }

    private static void prepareSpawn(BootWorldFixture fixture) {
        // Match the existing real serverPlayer constructor preflight, while retaining raw native MP here.
        fixture.world().getGameRules().setOrCreateGameRule("spawnRadius","0");
        fixture.world().setSpawnPoint(new BlockPos(8,65,8));
        fixture.installColumn(0,0);
    }
    private static EntityPlayerMP player(BootWorldFixture fixture) {
        var actor=new EntityPlayerMP(fixture.server(),fixture.world(),new GameProfile(UUID.randomUUID(),"native"),
                new PlayerInteractionManager(fixture.world()));actor.setPosition(8.5,80,8.5);return actor;
    }
    private static FreeTickResources resource(BootWorldFixture fixture,EntityPlayerMP actor) {
        return new FreeTickResources.Store().acquire(actor,fixture.world(),
                new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),UUID.randomUUID(),actor.dimension,1),0);
    }
    private static void assertPoolOnce(PoolEvents.Session session,BlockPos actual) {
        assertNotNull(actual);long identity=session.objectId(actual);
        assertEquals(1,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RETAIN")).count());
        assertEquals(1,session.events().stream().filter(e->e.objectId()==identity&&e.kind().equals("RELEASE")).count());
        writeEvents(session);
    }
    private static void writeEvents(PoolEvents.Session session) {
        try {
            Path report=Path.of(System.getProperty("unigrav.nativeIntegration.result")).getParent()
                    .resolve("pool-events-"+session.correlation()+".txt");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report,session.events().toString(),java.nio.charset.StandardCharsets.UTF_8);
        } catch(java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
    }
    private static void assertNativeLoaders() {
        assertNull(PoolEvents.class.getClassLoader(),"observer must have exactly one bootstrap state");
        assertEquals("net.minecraft.launchwrapper.LaunchClassLoader",World.class.getClassLoader().getClass().getName());
        assertSame(World.class.getClassLoader(),EntityPlayerMP.class.getClassLoader());        try {
            Path report=Path.of(System.getProperty("unigrav.nativeIntegration.result")).getParent().resolve("classloaders.txt");
            java.nio.file.Files.createDirectories(report.getParent());
            java.nio.file.Files.writeString(report,"observer="+PoolEvents.class.getClassLoader()
                    +"\nWorld="+World.class.getClassLoader()+"\nEntity="+Entity.class.getClassLoader()
                    +"\nLiving="+net.minecraft.entity.EntityLivingBase.class.getClassLoader()
                    +"\nMP="+EntityPlayerMP.class.getClassLoader()+"\n",java.nio.charset.StandardCharsets.UTF_8);
        } catch(java.io.IOException failure) {throw new java.io.UncheckedIOException(failure);}
    }
}