package wi.unigrav.core.player;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.boost.BoostMovementBudget;
import wi.unigrav.core.floor.BootWorldFixture;
import static org.junit.jupiter.api.Assertions.*;

class InstalledFreeImpactBudgetTest {
    @TempDir Path directory;
    private static final class CountingPlayer extends EntityPlayerMP {
        boolean armed; int waterReads,glideReads;
        CountingPlayer(WorldServer world,UUID id) {
            super(world.getMinecraftServer(),world,new GameProfile(id,"impact-budget"),new PlayerInteractionManager(world));
            new NetHandlerPlayServer(world.getMinecraftServer(),new NetworkManager(EnumPacketDirection.SERVERBOUND),this);
        }
        @Override public boolean isInWater() { if(armed)waterReads++;return super.isInWater(); }
        @Override public boolean isElytraFlying() { if(armed)glideReads++;return super.isElytraFlying(); }
    }
    @Test void disabledImpactDoesNotReadNewEligibilityEvenAtTheSharedTerminalBudget() throws Exception {
        runBudgetCase(false);
    }
    @Test void enabledClipComparisonExhaustionFailsBeforeEligibilityOrDamageWithoutRefund() throws Exception {
        runBudgetCase(true);
    }
    private void runBudgetCase(boolean enabled) throws Exception {
        boolean original=UnigravConfig.impactDamage;UnigravConfig.impactDamage=false;
        UUID id=UUID.randomUUID();
        try(var fixture=BootWorldFixture.open(directory.resolve("budget-"+enabled),id,false)) {
            fixture.serverPlayer(id); // Real spawn/loaded-column setup before constructing the observed MP.
            var actor=new CountingPlayer(fixture.world(),id);
            actor.width=.5F;actor.height=1F;actor.eyeHeight=.25F;
            var placement=new PlayerBodyPlacement(new Vec3d(8.5,69.5,8.5),CardinalFrame.Y_POS.attitude(),
                    new PlayerBodyShape.FreeCapsule(new PlayerBodyShape.Dimensions(.5,1,.25)));
            var pos=placement.transport();var bounds=placement.bounds();actor.setPosition(pos.x(),pos.y(),pos.z());
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(),bounds.min().y(),bounds.min().z(),
                    bounds.max().x(),bounds.max().y(),bounds.max().z()));
            var accepted=new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(id,UUID.randomUUID(),actor.dimension,1),
                    7,placement,new Vec3d(0,0,0));
            var travel=new PlayerPhysicsStep.TravelState();var current=new AtomicBoolean(true);
            var ledger=new BoostMovementBudget(10,0,new Vec3(pos.x(),pos.y(),pos.z()));
            try(var tx=new FreeOwnerTransaction(actor,accepted,travel,ledger,current::get)) {
                var scene=new InstalledFreeEndpointTest.Scene(fixture,tx,current,travel,ledger,placement,null);
                var helpers=new FreeNativeTailTest();helpers.directory=directory;
                helpers.put(scene,net.minecraft.init.Blocks.STONE);
                var movement=helpers.resolve(scene,new Vec3d(0,-.125,0));
                assertFalse(movement.clipEvents().isEmpty());
                actor.motionY=-.125;
                var ready=assertInstanceOf(CapsuleMoveFinalizer.Ready.class,CapsuleMoveFinalizer.prepare(tx,movement));
                ready.commit();
                var endpoint=tx.installedFreeEndpoint();var work=tx.resources().environmentObservationWork();
                actor.armed=true;actor.waterReads=0;actor.glideReads=0;
                endpoint.requireCurrent();
                int proofWaterReads=actor.waterReads,proofGlideReads=actor.glideReads;
                actor.waterReads=0;actor.glideReads=0;float health=actor.getHealth();
                var journal=tx.installedJournal().orElseThrow();
                long remaining=enabled?2:0;
                tx.resources().postprocessing().charge(FreePostprocessingWork.MAX_UNITS-work.usage().units()-remaining);
                long spent=work.usage().units();UnigravConfig.impactDamage=enabled;
                if(enabled) {
                    assertThrows(FreePostprocessingWork.Exhausted.class,()->InstalledFreeImpact.prepare(endpoint,movement,
                            new Vec3d(0,-1,0),new Vec3d(0,0,0),true,true));
                    assertEquals(FreePostprocessingWork.MAX_UNITS,tx.resources().postprocessing().units());
                    assertTrue(tx.resources().postprocessing().terminal());
                    assertThrows(FreePostprocessingWork.Exhausted.class,()->work.charge(EnvironmentObservationWork.Unit.COMPARISON));
                } else {
                    InstalledFreeImpact.prepare(endpoint,movement,new Vec3d(0,-1,0),new Vec3d(0,0,0),true,true);
                    assertEquals(spent,tx.resources().postprocessing().units());
                }
                assertEquals(proofWaterReads,actor.waterReads,"no added water eligibility read beyond the same endpoint proof");
                assertEquals(proofGlideReads,actor.glideReads,"no added glide eligibility read beyond the same endpoint proof");
                assertEquals(health,actor.getHealth());assertSame(journal,tx.installedJournal().orElseThrow());
                assertTrue(tx.resources().postprocessing().units()>=spent);
            }
        } finally {UnigravConfig.impactDamage=original;}
    }
}
