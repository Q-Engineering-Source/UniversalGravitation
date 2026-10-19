package wi.unigrav.core.player;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.stats.StatBase;
import net.minecraft.stats.StatList;
import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real native calls: guard cases require transformed EntityPlayer, never a simulated native body. */
class InstalledMovementStatTest {
    private GravityEntityBinder previousBinder;
    @BeforeEach void installUnboundNativeFixtureBinder() {
        previousBinder = UnigravMod.entityBinder;
        UnigravMod.entityBinder = new GravityEntityBinder();
    }
    @AfterEach void restoreNativeFixtureBinder() { UnigravMod.entityBinder = previousBinder; }

    @TempDir Path directory;
    private static final CollisionQueryFootprint.Limits SCAN =
            new CollisionQueryFootprint.Limits(16384,16,4096,4096);

    @Test void realFreeFinalizerAccountsNetTranslationOnceAndCannotReplay() throws Exception {
        var fixture = new FreeNativeTailTest(); fixture.directory = directory;
        try (var scene = fixture.scene("native-stat-finalizer")) {
            fixture.put(scene,net.minecraft.init.Blocks.AIR);
            var ready = fixture.prepare(scene,new Vec3d(0,0,.4));
            int before = scene.tx().actor().getStatFile().readStat(StatList.FLY_ONE_CM);
            ready.commit();
            assertEquals(before+40,scene.tx().actor().getStatFile().readStat(StatList.FLY_ONE_CM));
            assertThrows(IllegalStateException.class,ready::commit);
            assertEquals(before+40,scene.tx().actor().getStatFile().readStat(StatList.FLY_ONE_CM));
        }
    }

    @Test void purePoseTailMakesNoNativeStatisticsCall() throws Exception {
        try (var scene = installed("pure-pose")) {
            var tails = new AtomicInteger();
            FreeNativeTail.run(scene.endpoint,null,tails::incrementAndGet);
            assertEquals(1,tails.get()); assertEquals(0,scene.actor.stats);
        }
    }

    @Test void predicateAuthorityLossStopsBeforeFirstStatAndConsumesJournalAttempt() throws Exception {
        try (var scene = installed("predicate-retire")) {
            scene.actor.predicate = () -> scene.current.set(false);
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(0,scene.actor.stats); assertEquals(0,scene.actor.exhaustions);
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(0,scene.actor.stats);
        }
    }

    @Test void caughtPredicateReentryRemainsFailureBeforeAddStat() throws Exception {
        try (var scene = installed("predicate-recursion")) {
            scene.actor.predicate = () -> assertThrows(IllegalStateException.class,
                    ()->scene.actor.addMovementStat(.4,0,0));
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(0,scene.actor.stats); assertEquals(0,scene.actor.exhaustions);
        }
    }

    @Test void successfulAddStatThenAuthorityLossCannotReachExhaustionOrReplay() throws Exception {
        try (var scene = installed("after-stat-retire")) {
            scene.actor.afterStat = () -> scene.current.set(false);
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(1,scene.actor.stats); assertEquals(0,scene.actor.exhaustions);
            assertEquals(40,scene.actor.getStatFile().readStat(StatList.WALK_ONE_CM));
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(1,scene.actor.stats);
        }
    }

    @Test void predicateThrowableIsOriginalAndLexicalCleanupAllowsLaterUnownedNativeCall() throws Exception {
        try (var scene = installed("predicate-original")) {
            var original = new IllegalArgumentException("actual predicate failure");
            scene.actor.predicate = () -> {throw original;};
            assertSame(original,assertThrows(IllegalArgumentException.class,()->consume(scene)));
            assertEquals(0,scene.actor.stats);
            scene.actor.addMovementStat(.4,0,0);
            assertEquals(1,scene.actor.stats); assertEquals(1,scene.actor.exhaustions);
        }
    }

    @Test void noInstalledRequestNativeAndCardinalScopeEachCallNativeOnlyOnce() throws Exception {
        try (var scene = installed("ordinary-native")) {
            scene.actor.addMovementStat(.4,0,0);
            assertEquals(1,scene.actor.stats); assertEquals(1,scene.actor.exhaustions);
            try (var scope = CardinalNativeBoundary.open(new Object(),scene.actor,
                    scene.tx.resources().environmentObservationWork(),scene.endpoint::requireCurrent)) {
                scene.actor.addMovementStat(.4,0,0);
                scope.check("Cardinal statistics once");
            }
            assertEquals(2,scene.actor.stats); assertEquals(2,scene.actor.exhaustions);
        }
    }

    @Test void oneJournalCannotBeConsumedThroughAnotherEndpointObject() throws Exception {
        try (var scene = installed("journal-once")) {
            consume(scene);
            assertEquals(1,scene.actor.stats);
            var another = scene.tx.installedFreeEndpoint();
            try (var scope = CardinalNativeBoundary.open(scene.tx,scene.actor,
                    scene.tx.resources().environmentObservationWork(),another::requireCurrent)) {
                assertThrows(IllegalStateException.class,
                        ()->InstalledMovementStat.consume(another,new Vec3d(.4,0,0)));
            }
            assertEquals(1,scene.actor.stats);
        }
    }

    @Test void realSlideRoundsOneNetWorldDeltaRatherThanEachSegment() throws Exception {
        var fixture = new FreeNativeTailTest(); fixture.directory=directory;
        try (var scene=fixture.scene("slide-net-stat")) {
            fixture.put(scene,net.minecraft.init.Blocks.AIR);
            scene.fixture().world().getChunkProvider().getLoadedChunk(0,0).setBlockState(
                    new net.minecraft.util.math.BlockPos(9,69,8),net.minecraft.init.Blocks.STONE.getDefaultState());
            var requested=new Vec3d(.4,0,.25);
            var actor=scene.tx().actor(); actor.motionX=requested.x();actor.motionY=0;actor.motionZ=requested.z();
            var tx=scene.tx();
            var pose=CapsulePlacementMath.pose(tx.accepted().placement(),tx.numeric());
            var tube=CapsulePlacementMath.translationTube(pose,requested,tx.numeric());
            var grant=tx.reserve(tx.nextLedgerSequence(),tx.tick(),tube.requestedDisplacementUpper());
            assertTrue(grant.accepted());
            assertTrue(grant.grant()>=tube.requestedDisplacementUpper(),
                    "the actual test request must fit its admitted center-path grant: requested="
                            +tube.requestedDisplacementUpper()+" grant="+grant.grant());
            var capture=assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                    WorldCollisionCandidates.capture(tx,CapsuleMovementResolver.window(tx.accepted(),tube),
                            CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN,
                            tx.resources().environmentObservationWork()));
            var movement=assertInstanceOf(CapsuleMovementResolver.Resolved.class,
                    CapsuleMovementResolver.resolveWithSlide(tx,tube,capture));
            assertTrue(movement.segments().size()>1,"actual slide must have multiple segments");
            var delta=FreeNativeTail.actualDelta(movement);
            int expected=Math.round(net.minecraft.util.math.MathHelper.sqrt(delta.x()*delta.x()+delta.z()*delta.z())*100F);
            assertTrue(expected>25);
            int before=actor.getStatFile().readStat(StatList.FLY_ONE_CM);
            assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(scene.tx(),movement)).commit();
            assertEquals(before+expected,actor.getStatFile().readStat(StatList.FLY_ONE_CM));
        }
    }

    @Test void exhaustedSameFrameBudgetCannotDispatchNativeStatisticsAndDoesNotRefund() throws Exception {
        try(var scene=installed("stat-budget")) {
            var work=scene.tx.resources().postprocessing();
            work.charge(FreePostprocessingWork.MAX_UNITS-work.units());
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(0,scene.actor.stats);assertEquals(0,scene.actor.exhaustions);
            assertEquals(FreePostprocessingWork.MAX_UNITS,work.units());assertTrue(work.terminal());
        }
    }

    @Test void sequentialNativeSuperReentryCannotApplyASecondStat() throws Exception {
        try(var scene=installed("double-native-super")) {
            scene.actor.doubleNative=true;
            assertThrows(RuntimeException.class,()->consume(scene));
            assertEquals(1,scene.actor.stats);assertEquals(1,scene.actor.exhaustions);
            assertEquals(40,scene.actor.getStatFile().readStat(StatList.WALK_ONE_CM));
        }
    }

    @Test void missingOwnerScopeCannotMakeAnUnmeteredAttempt() throws Exception {
        try(var scene=installed("missing-owner-scope")) {
            assertThrows(IllegalStateException.class,
                    ()->InstalledMovementStat.consume(scene.endpoint,new Vec3d(.4,0,0)));
            assertEquals(0,scene.actor.stats);
            consume(scene);assertEquals(1,scene.actor.stats);
        }
    }

    private static void consume(Scene scene) {
        try (var scope = CardinalNativeBoundary.open(scene.tx,scene.actor,
                scene.tx.resources().environmentObservationWork(),scene.endpoint::requireCurrent)) {
            InstalledMovementStat.consume(scene.endpoint,new Vec3d(.4,0,0));
            scope.check("statistics completed");
        }
    }

    private Scene installed(String name) throws Exception {
        var world = BootWorldFixture.open(directory.resolve(name),UUID.randomUUID(),false);
        world.serverPlayer(UUID.randomUUID()); // Establish the real loaded spawn column.
        var actor = new ProbePlayer(world);
        new NetHandlerPlayServer(world.server(),new NetworkManager(EnumPacketDirection.SERVERBOUND),actor);
        actor.width=.5F; actor.height=1F; actor.eyeHeight=.25F;
        var shape = new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,1,actor.getEyeHeight()));
        var placement = new PlayerBodyPlacement(new Vec3d(8.5,70.5,8.5),CardinalFrame.Y_POS.attitude(),shape);
        var transport = placement.transport(); var bounds=placement.bounds();
        actor.setPosition(transport.x(),transport.y(),transport.z());
        actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),
                bounds.max().x(),bounds.max().y(),bounds.max().z()));
        var accepted = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(),
                UUID.randomUUID(),actor.dimension,1),7,placement,new Vec3d(0,0,0));
        var current = new AtomicBoolean(true);
        var tx = new FreeOwnerTransaction(actor,accepted,new PlayerPhysicsStep.TravelState(),
                new BoostMovementBudget(10,0,new Vec3(transport.x(),transport.y(),transport.z())),current::get);
        var pose = CapsulePlacementMath.pose(placement,tx.numeric());
        var window = CapsuleFootContacts.window(accepted,0);
        var captured = assertInstanceOf(WorldCollisionCandidates.Snapshot.class,
                WorldCollisionCandidates.capture(tx,window,CollisionQueryFootprint.QueryPolicy.CAPSULE_CONTACT,SCAN,
                        tx.resources().environmentObservationWork()));
        var root = CapsulePathSource.actualFree(accepted,pose,captured,window,FreeOwnerTransaction.ACCURACY,
                tx.queries(),tx.numeric());
        var endpoint = CertifiedReferenceEndpoint.from(tx,root);
        var prepared = tx.prepareInstallation(placement,endpoint);
        tx.beginInstallation(prepared); tx.recordInstalled(prepared);
        actor.onGround=true;
        return new Scene(world,actor,tx,current,prepared.installedEndpoint());
    }

    private record Scene(BootWorldFixture world,ProbePlayer actor,FreeOwnerTransaction tx,
                         AtomicBoolean current,InstalledFreeEndpoint endpoint) implements AutoCloseable {
        public void close() {try {tx.close();} finally {actor.getAdvancements().dispose();world.close();}}
    }

    private static final class ProbePlayer extends EntityPlayerMP {
        Runnable predicate,afterStat;
        int stats,exhaustions;
        boolean doubleNative;
        ProbePlayer(BootWorldFixture world) {
            super(world.server(),world.world(),new GameProfile(UUID.randomUUID(),"stat-probe"),
                    new PlayerInteractionManager(world.world()));
        }
        @Override public void addMovementStat(double x,double y,double z) {
            super.addMovementStat(x,y,z);
            if(doubleNative)super.addMovementStat(x,y,z);
        }
        @Override public boolean isRiding() {
            Runnable action=predicate; predicate=null;
            if(action!=null)action.run();
            return super.isRiding();
        }
        @Override public void addStat(StatBase stat,int amount) {
            super.addStat(stat,amount); stats++;
            Runnable action=afterStat; afterStat=null;
            if(action!=null)action.run();
        }
        @Override public void addExhaustion(float amount) {super.addExhaustion(amount);exhaustions++;}
    }
}
