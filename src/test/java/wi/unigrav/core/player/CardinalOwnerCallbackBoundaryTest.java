package wi.unigrav.core.player;

import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class CardinalOwnerCallbackBoundaryTest {
    @TempDir Path temp;
    private static final CardinalMovementResolver.Limits LIMITS=PlayerPhysicsStep.cardinalMovementLimits();

    @ParameterizedTest @CsvSource({"Y_POS,true","X_POS,true","Y_POS,false","X_POS,false"})
    void actualLandingRelocationStopsWalkingAndWorldTailBeforeAnySuccessorWrites(CardinalFrame frame,boolean relocate) {
        try(var fixture=BootWorldFixture.open(temp.resolve("landing-"+frame),new UUID(0,91001),false)) {
            var actor=fixture.serverPlayer(new UUID(0,91002));var landed=new AtomicInteger();var walked=new AtomicInteger();
            var afterTeleport=new AtomicInteger();var relocated=new AtomicBoolean();
            var support=new BlockPos(8,64,8);
            put(fixture,support,new Block(Material.ROCK) {
                @Override public void onLanded(World world,Entity entity) {
                    landed.incrementAndGet();super.onLanded(world,entity);
                    if(!relocate)return;
                    entity.setPosition(12.5,70,8.5);entity.motionX=.123;entity.motionY=.234;entity.motionZ=.345;
                    entity.fallDistance=37;relocated.set(true);
                }
                @Override public void onEntityWalk(World world,BlockPos pos,Entity entity) {walked.incrementAndGet();}
            }.getDefaultState());
            put(fixture,new BlockPos(12,70,8),new Block(Material.ROCK) {
                @Override public void onEntityCollision(World world,BlockPos pos,net.minecraft.block.state.IBlockState state,Entity entity) {
                    if(relocated.get())afterTeleport.incrementAndGet();
                }
            }.getDefaultState());
            var shape=new PlayerBodyShape.Cardinal(frame,new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight()));
            var placement=new PlayerBodyPlacement(new Vec3d(8.5,64.5,8.5).add(frame.up().multiply(.5+actor.height/2.0)),frame.attitude(),shape);
            var box=placement.bounds();actor.setEntityBoundingBox(new AxisAlignedBB(box.min().x(),box.min().y(),box.min().z(),box.max().x(),box.max().y(),box.max().z()));
            actor.resetPositionToBB();actor.onGround=true;actor.stepHeight=0;
            var delta=frame.toWorld(new Vec3d(.1,-.08,.1));actor.motionX=delta.x();actor.motionY=delta.y();actor.motionZ=delta.z();
            var request=new CardinalMovementResolver.Request(frame,actor.getEntityBoundingBox(),delta,MoverType.PLAYER,true,false,0);
            var resolved=assertInstanceOf(CardinalMovementResolver.Resolved.class,CardinalMovementResolver.resolve(request,q->fixture.world().getCollisionBoxes(actor,q),LIMITS));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,91003),actor.dimension,1);
            var resources=new FreeTickResources.Store().acquire(actor,fixture.world(),scope,0);
            var ready=assertInstanceOf(CardinalMoveFinalizer.Ready.class,CardinalMoveFinalizer.preparePhysical(actor,request,resolved,resources.environmentObservationWork(),()->{}));
            var result=ready.commit();
            assertEquals(1,landed.get());
            assertTrue(resources.environmentObservationWork().usage().units()>0,"actual owner tail must consume this Frame");
            if(relocate) {
                assertInstanceOf(CardinalMoveFinalizer.Unavailable.class,result);
                assertEquals(0,walked.get());assertEquals(0,afterTeleport.get());
                assertEquals(12.5,actor.posX,0);assertEquals(70,actor.posY,0);
                assertEquals(.123,actor.motionX,0);assertEquals(.234,actor.motionY,0);assertEquals(.345,actor.motionZ,0);
                assertEquals(37,actor.fallDistance,0);
            } else {
                assertInstanceOf(CardinalMoveFinalizer.Applied.class,result);assertEquals(1,walked.get());
                assertEquals(0,frame.toLocal(new Vec3d(actor.motionX,actor.motionY,actor.motionZ)).y(),1e-12);
            }            assertThrows(IllegalStateException.class,ready::commit,"the tail remains one-shot after interruption");
        }
    }

    @ParameterizedTest @EnumSource(value=CardinalFrame.class,names={"Y_POS","X_POS"})
    void exhaustedOwnerFramePreventsFirstActualTailDispatchWithoutRefund(CardinalFrame frame) {
        try(var fixture=BootWorldFixture.open(temp.resolve("terminal-"+frame),new UUID(0,91011),false)) {
            var actor=fixture.serverPlayer(new UUID(0,91012));var landed=new AtomicInteger();
            put(fixture,new BlockPos(8,64,8),new Block(Material.ROCK) {
                @Override public void onLanded(World world,Entity entity) {landed.incrementAndGet();super.onLanded(world,entity);}
            }.getDefaultState());
            var shape=new PlayerBodyShape.Cardinal(frame,new PlayerBodyShape.Dimensions(actor.width,actor.height,actor.getEyeHeight()));
            var placement=new PlayerBodyPlacement(new Vec3d(8.5,64.5,8.5).add(frame.up().multiply(.5+actor.height/2.0)),frame.attitude(),shape);
            var box=placement.bounds();actor.setEntityBoundingBox(new AxisAlignedBB(box.min().x(),box.min().y(),box.min().z(),box.max().x(),box.max().y(),box.max().z()));
            actor.resetPositionToBB();actor.onGround=true;actor.stepHeight=0;
            var delta=frame.toWorld(new Vec3d(.1,-.08,.1));actor.motionX=delta.x();actor.motionY=delta.y();actor.motionZ=delta.z();
            var request=new CardinalMovementResolver.Request(frame,actor.getEntityBoundingBox(),delta,MoverType.PLAYER,true,false,0);
            var resolved=assertInstanceOf(CardinalMovementResolver.Resolved.class,CardinalMovementResolver.resolve(request,q->fixture.world().getCollisionBoxes(actor,q),LIMITS));
            var scope=new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),new UUID(0,91013),actor.dimension,1);
            var store=new FreeTickResources.Store();var resources=store.acquire(actor,fixture.world(),scope,0);
            resources.postprocessing().charge(FreePostprocessingWork.MAX_UNITS);
            var oldBounds=actor.getEntityBoundingBox();double oldX=actor.motionX,oldY=actor.motionY,oldZ=actor.motionZ;
            var failure=assertThrows(CardinalNativeBoundary.Abort.class,()->{
                var ready=assertInstanceOf(CardinalMoveFinalizer.Ready.class,CardinalMoveFinalizer.preparePhysical(actor,request,resolved,resources.environmentObservationWork(),()->{}));
                ready.commit();
            });
            assertEquals(CardinalNativeBoundary.AbortKind.EXECUTION_FAILED,failure.kind());
            assertInstanceOf(FreePostprocessingWork.Exhausted.class,failure.getCause());
            assertEquals(0,landed.get());assertEquals(oldBounds,actor.getEntityBoundingBox());
            assertEquals(oldX,actor.motionX,0);assertEquals(oldY,actor.motionY,0);assertEquals(oldZ,actor.motionZ,0);
            assertTrue(resources.postprocessing().terminal());assertEquals(FreePostprocessingWork.MAX_UNITS,resources.postprocessing().units());
            assertSame(resources,store.acquire(actor,fixture.world(),scope,0));
        }
    }
    private static void put(BootWorldFixture fixture,BlockPos pos,net.minecraft.block.state.IBlockState state) {
        fixture.world().getChunkProvider().loadedChunks.get(net.minecraft.util.math.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4)).setBlockState(pos,state);
    }}