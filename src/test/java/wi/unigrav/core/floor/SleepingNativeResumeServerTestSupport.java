package wi.unigrav.core.floor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import wi.unigrav.core.player.PlayerEnvironmentSampler;
import wi.unigrav.core.player.StandingConstraints;
import wi.unigrav.core.EntityGravitySources;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import java.net.SocketAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.network.NetworkManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import wi.unigrav.UnigravMod;
import wi.unigrav.api.math.Vec3;
import wi.unigrav.api.gravity.UniformGravityContribution;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.AmbientWorldFields;
import wi.unigrav.core.GravityMode;
import wi.unigrav.core.UnigravConfig;
import wi.unigrav.core.player.GIntentServer;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsServer;
import wi.unigrav.proxy.CommonProxy;
import static org.junit.jupiter.api.Assertions.*;

/** Normal FML peer: the actual TP mixins and this fixture share the production singletons. */
public final class SleepingNativeResumeServerTestSupport implements AutoCloseable {
    private BootWorldFixture world;
    private boolean plainNativeActor;
    private EntityPlayerMP player;
    private EmbeddedChannel channel;
    private NetworkManager networkManager;
    private NetworkManager earlyFlightRequestManager;
    private int flightHandshakeRequests, earlyFlightHandshakeRequests;
    private AutoCloseable binding;
    private AutoCloseable counterField;
    private final double previousLimit = UnigravConfig.maxEntitySpeed;

    public SleepingNativeResumeServerTestSupport(Path directory, UUID playerId) throws Exception {
        this(directory, playerId, true);
    }
    /** The Aqua profile needs genuine no-reference Free admission, without changing sleep's floor fixture. */
    public SleepingNativeResumeServerTestSupport(Path directory, UUID playerId, boolean floorReference) throws Exception {
        this(directory, playerId, floorReference, false);
    }
    /** Only the actual Artemis body-profile gate opts out of the specialized PosePlayer TEST producer. */
    public SleepingNativeResumeServerTestSupport(Path directory, UUID playerId, boolean floorReference,
                                                boolean plainNativeActor) throws Exception {
        this.plainNativeActor=plainNativeActor;
        try {
            UnigravConfig.maxEntitySpeed=37;
            var authentication = new YggdrasilAuthenticationService(Proxy.NO_PROXY, UUID.randomUUID().toString());
            var profiles = new PlayerProfileCache(authentication.createProfileRepository(), directory.resolve("usercache.json").toFile());
            world=BootWorldFixture.openForSleepNetwork(directory,new UUID(0,11981),profiles);
            if (plainNativeActor) {
                world.prepareServerPlayerSpawn();
                player=new EntityPlayerMP(world.server(),world.world(),
                        new com.mojang.authlib.GameProfile(playerId,"native-profile-fixture"),
                        new net.minecraft.server.management.PlayerInteractionManager(world.world()));
                new net.minecraft.network.NetHandlerPlayServer(world.server(),
                        new NetworkManager(net.minecraft.network.EnumPacketDirection.SERVERBOUND),player);
            } else player=world.serverPlayer(playerId);
            networkManager=player.connection.netManager;
            channel=new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel embedded) {
                    embedded.pipeline().addLast("packet_handler",networkManager);
                }
            }) {
                @Override protected SocketAddress remoteAddress0(){return new InetSocketAddress("127.0.0.1",28699);}
            };
            binding=UnigravMod.entityBinder.bind(player,GravityMode.REPLACE);
            if (floorReference) {
                for(int x=6;x<=15;x++)for(int z=6;z<=10;z++)
                    world.put(new BlockPos(x,60,z),FloorBlocks.GRAVITY_FLOOR.getDefaultState()
                            .withProperty(DirectionalFloorBlock.FACING,EnumFacing.UP));
            }
            // Empty geometry still needs a real floor-field lifecycle owner. Keep the scan
            // after optional floor writes, independently of whether reference blocks exist.
            var loadedChunk=world.world().getChunkProvider().getLoadedChunk(0,0);
            assertNotNull(loadedChunk,"login fixture's actual collision chunk must already be loaded");
            assertTrue(loadedChunk.isLoaded(),"unloaded chunks cannot establish floor-field lifecycle");
            FloorWorldFields.onChunkLoad(new ChunkEvent.Load(loadedChunk));
            var floorDecision=FloorWorldFields.captureDecision(world.world()).orElseThrow(
                    () -> new AssertionError("real loaded-chunk lifecycle did not establish a floor decision; floorReference="+floorReference));
            assertTrue(floorDecision.isLocalCompatibilityOwner(),"fixture must retain its real registered local floor owner");
            assertTrue(floorDecision.isCurrent(),"initial floor decision must be current before native login");
            if (floorReference) {
                counterField=AmbientWorldFields.field(world.world()).registerContribution(
                        "sleep-native-fml-test","cancel-floor-plateau",new UniformGravityContribution(new Vec3(0,32,0)));
            }
            var bytes=new PacketBuffer(Unpooled.buffer());
            try {
                new C00Handshake("localhost",28699,EnumConnectionState.LOGIN).writePacketData(bytes);
                var incoming=new C00Handshake();incoming.readPacketData(bytes);
                assertTrue(FMLCommonHandler.instance().handleServerHandshake(incoming,networkManager));
            } finally {bytes.release();}
            FMLNetworkHandler.fmlServerHandshake(world.server().getPlayerList(),networkManager,player);
        } catch (Throwable failure) {
            try {close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
            if(failure instanceof Error error)throw error;
            if(failure instanceof Exception exception)throw exception;
            throw new IllegalStateException("sleep peer construction failed",failure);
        }
    }
    public EntityPlayerMP player(){return player;}
    public void receive(net.minecraft.network.Packet<?> packet){
        // Dispatcher must observe REGISTER/FML handshake packets before the actual native handler.
        assertSame(networkManager,channel.pipeline().get("packet_handler"));
        if(packet instanceof net.minecraft.network.play.client.CPacketCustomPayload custom
                && "nfr_ui_flight".equals(custom.getChannelName())) {
            var bytes=custom.getBufferData();
            if(bytes.isReadable() && bytes.getUnsignedByte(bytes.readerIndex())==0) {
                flightHandshakeRequests++;
                if(player.connection==null) {
                    earlyFlightHandshakeRequests++;
                    earlyFlightRequestManager=networkManager;
                }
            }
        }
        channel.writeInbound(packet);channel.runPendingTasks();channel.checkException();
    }
    public void assertEarlyFlightHandshakeRequest() {
        assertEquals(1,flightHandshakeRequests,"one actual UIE capability request reaches the native network pipeline");
        assertEquals(1,earlyFlightHandshakeRequests,"the real request arrived before player.connection was initialized");
        assertSame(networkManager,earlyFlightRequestManager,"early request retained this exact NetworkManager");
    }
    public List<Object> drain(){
        assertTrue(world.server().isCallingFromMinecraftThread());
        // FINALIZING is completed by the real dispatcher handler ITickable.update, not channel tasks.
        networkManager.processReceivedPackets();
        channel.runPendingTasks();channel.checkException();var result=new ArrayList<Object>();Object value;
        while((value=channel.readOutbound())!=null)result.add(value);return result;
    }
    public void tick(){
        assertTrue(world.server().isCallingFromMinecraftThread());
        world.world().setTotalWorldTime(world.world().getTotalWorldTime()+1);
        GIntentServer.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
        PlayerPhysicsServer.INSTANCE.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
    }
    public PlayerPhysicalSnapshot accepted(){return PlayerPhysicsServer.INSTANCE.accepted(player.connection).orElseThrow();}
    public void assertOwned(){assertNotNull(player.connection);assertSame(networkManager,player.connection.netManager);
        assertSame(player.connection,networkManager.getNetHandler());assertTrue(PlayerPhysicsServer.INSTANCE.drivesMotion(player),this::admissionDiagnostics);}
    public String admissionDiagnostics(){return "intent="+GIntentServer.INSTANCE.observe(player)
            +" accepted="+PlayerPhysicsServer.INSTANCE.accepted(player.connection)
            +" owns="+PlayerPhysicsServer.INSTANCE.owns(player)
            +" drives="+PlayerPhysicsServer.INSTANCE.drivesMotion(player);}
    public void assertConfirmedGOff(){
        var observed=GIntentServer.INSTANCE.observe(player);
        assertTrue(observed.isPresent(),this::admissionDiagnostics);
        assertFalse(observed.orElseThrow().active(),this::admissionDiagnostics);
    }
    /** Establish the actual no-source world before G/owner admission; never a force-cancelling counterfield. */
    public void prepareNoReferenceAdmission() {
        assertLoggedIn();
        if (UnigravMod.worldGravity.isLoaded(world.world())) {
            assertTrue(UnigravMod.worldGravity.setWorldGravity(world.world(), null, false),
                    "Aqua fixture must disable its exact loaded world's gravity source");
            assertFalse(UnigravMod.worldGravity.isEnabled(world.world()),this::noReferenceDiagnostics);
        }
        assertTrue(wi.unigrav.api.field.GravityFields.get().contributions().isEmpty(),this::noReferenceDiagnostics);
        var worldSource=new wi.unigrav.api.field.GravityField.Id(wi.unigrav.core.WorldGravityManager.NAMESPACE,
                wi.unigrav.core.WorldGravityManager.PATH);
        assertTrue(AmbientWorldFields.field(world.world()).contributions().keySet().stream()
                .allMatch(id -> id.equals(worldSource) && UnigravMod.worldGravity.isLoaded(world.world())
                        && !UnigravMod.worldGravity.isEnabled(world.world())),this::noReferenceDiagnostics);
        assertEquals(Vec3.ZERO,EntityGravitySources.fieldAcceleration(player,new Vec3(player.posX,player.posY,player.posZ),
                world.world().getTotalWorldTime()*.05),this::noReferenceDiagnostics);
    }
    /** Actual source observation only; this does not grant mobility or install a snapshot. */
    public void assertNoReferenceEnvironment() {
        assertConfirmedGOff();
        var binding=UnigravMod.entityBinder.sampleFieldBinding(player);
        assertTrue(binding.active(),this::noReferenceDiagnostics);
        assertEquals(Optional.of(GravityMode.REPLACE),binding.mode(),this::noReferenceDiagnostics);
        assertTrue(player.hasNoGravity(),this::noReferenceDiagnostics);
        assertFalse(player.isInWater(),this::noReferenceDiagnostics);
        assertFalse(player.isInLava(),this::noReferenceDiagnostics);
        assertFalse(player.isElytraFlying(),this::noReferenceDiagnostics);
        assertTrue(player.getItemStackFromSlot(net.minecraft.inventory.EntityEquipmentSlot.FEET).isEmpty(),
                "no-reference fixture has no boot equipment to qualify an active support reference");
        var position=new Vec3(player.posX,player.posY,player.posZ);
        assertEquals(Vec3.ZERO,EntityGravitySources.fieldAcceleration(player,position,
                world.world().getTotalWorldTime()*.05),this::noReferenceDiagnostics);
        var result=new PlayerEnvironmentSampler(UnigravMod.entityBinder,MagneticResponseRules.builtIn(),.5)
                .sample(player,accepted(),Optional.of(false));
        var observed=assertInstanceOf(PlayerEnvironmentSampler.Sample.class,result,
                () -> "actual no-reference observation="+result+" "+noReferenceDiagnostics());
        assertEquals(Vec3.ZERO,observed.rawFields().gravityAcceleration(),this::noReferenceDiagnostics);
        assertEquals(Vec3.ZERO,observed.rawFields().magneticAcceleration(),this::noReferenceDiagnostics);
        assertTrue(observed.rawFields().faceEvidence().isEmpty(),() -> "raw faces="+observed.rawFields()+" "+noReferenceDiagnostics());
        assertTrue(observed.evidence().faceEvidence().isEmpty(),() -> "effective faces="+observed.evidence()+" "+noReferenceDiagnostics());
        var standing=StandingConstraints.resolve(StandingConstraints.State.initial(),
                new StandingConstraints.Request(world.world().getTotalWorldTime(),false,observed.evidence()));
        assertEquals(StandingConstraints.ConstraintPresence.ABSENT,standing.presence(),() -> "standing="+standing+" "+noReferenceDiagnostics());
        assertTrue(standing.targetReference().isEmpty());
    }
    private String noReferenceDiagnostics() {
        return admissionDiagnostics()+" worldGravityLoaded="+UnigravMod.worldGravity.isLoaded(world.world())
                +" worldGravityEnabled="+UnigravMod.worldGravity.isEnabled(world.world())
                +" worldGravityAcceleration="+UnigravMod.worldGravity.getAcceleration(world.world())
                +" localContributions="+AmbientWorldFields.field(world.world()).contributions()
                +" sharedContributions="+wi.unigrav.api.field.GravityFields.get().contributions()
                +" bindingMode="+UnigravMod.entityBinder.sampleFieldBinding(player).mode()
                +" noGravity="+player.hasNoGravity()+" water="+player.isInWater()+" lava="+player.isInLava()
                +" gliding="+player.isElytraFlying();
    }
    public void assertLoggedIn() {
        assertNotNull(player.connection);assertSame(networkManager,player.connection.netManager);
        assertSame(player.connection,networkManager.getNetHandler());
        assertSame(player,world.server().getPlayerList().getPlayerByUUID(player.getUniqueID()));
        assertEquals(1,world.server().getPlayerList().getPlayers().stream().filter(candidate->candidate==player).count());
        assertEquals(1,world.world().playerEntities.stream().filter(candidate->candidate==player).count());
    }
    public void assertDelegated(){assertTrue(PlayerPhysicsServer.INSTANCE.owns(player));assertFalse(PlayerPhysicsServer.INSTANCE.drivesMotion(player));}
    public void sleep(){
        world.world().setWorldTime(18000);world.world().setSkylightSubtracted(15);
        var bed=new BlockPos(8,65,8);world.put(bed,Blocks.BED.getDefaultState());
        new CommonProxy().runNativePose(player,()->assertEquals(EntityPlayer.SleepResult.OK,player.trySleep(bed)));
        assertTrue(player.isPlayerSleeping());
    }
    public void wake(){new CommonProxy().runNativePose(player,()->player.wakeUpPlayer(true,true,false));assertFalse(player.isPlayerSleeping());}
    public void assertFinalBudget(PlayerPhysicalSnapshot.Scope originalScope){
        assertOwned();assertEquals(originalScope,accepted().scope());
        assertEquals(0,accepted().worldVelocity().y(),0);assertEquals(0,accepted().worldVelocity().z(),0);
        UnigravConfig.maxEntitySpeed=500;
        UnigravMod.entityBinder.setBodyPhysics(player,new BodyPhysics(100,.1));
        UnigravMod.entityBinder.setForce(player,new Vec3(10_000_000,0,0));tick();
        assertEquals(37,accepted().worldVelocity().x(),1e-8);
    }
    @Override public void close() throws Exception {
        Throwable failure=null;
        if(player!=null&&channel!=null){
            try {
                var event=new FMLNetworkEvent.ServerDisconnectionFromClientEvent(networkManager);
                PlayerPhysicsServer.INSTANCE.onDisconnect(event);GIntentServer.INSTANCE.onDisconnect(event);
            }catch(Throwable thrown){failure=thrown;}
        }
        var cleanups=new ArrayList<AutoCloseable>();
        if(world!=null&&player!=null&&world.server().getPlayerList().getPlayers().contains(player))
            cleanups.add(()->world.server().getPlayerList().playerLoggedOut(player));
        if(counterField!=null)cleanups.add(counterField);if(binding!=null)cleanups.add(binding);
        if(channel!=null)cleanups.add(()->channel.finishAndReleaseAll());
        if(plainNativeActor&&player!=null)cleanups.add(()->player.getAdvancements().dispose());
        if(world!=null)cleanups.add(world);
        for(var cleanup:cleanups)try{cleanup.close();}catch(Throwable thrown){if(failure==null)failure=thrown;else failure.addSuppressed(thrown);}
        UnigravConfig.maxEntitySpeed=previousLimit;
        if(failure instanceof Error error)throw error;if(failure instanceof Exception exception)throw exception;
        if(failure!=null)throw new IllegalStateException("sleep peer cleanup failed",failure);
    }
}
