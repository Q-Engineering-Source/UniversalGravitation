package wi.unigrav;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wi.unigrav.api.integrator.SymplecticMethods;
import wi.unigrav.api.sim.NBodySimulation;
import wi.unigrav.core.ChunkGravityManager;
import wi.unigrav.core.GlobalGravityTakeover;
import wi.unigrav.core.GravityEntityBinder;
import wi.unigrav.core.ImpactDamageHandler;
import wi.unigrav.core.KnockbackHandler;
import wi.unigrav.core.RegionGravityManager;
import wi.unigrav.core.TickDriver;
import wi.unigrav.core.UnigravCommand;
import wi.unigrav.core.WorldGravityManager;
import wi.unigrav.core.ServerMotionTimingDiagnostics;
import wi.unigrav.core.config.EntityBindingsLoader;
import wi.unigrav.core.config.GravitySourcesLoader;
import wi.unigrav.core.config.MassPresetsLoader;
import wi.unigrav.core.collision.ChbDependency;
import wi.unigrav.core.floor.FloorWorldFields;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.GIntentNetwork;
import wi.unigrav.core.player.GIntentServer;
import wi.unigrav.core.player.PlayerPhysicsNetwork;
import wi.unigrav.core.player.PlayerPhysicsServer;

import wi.unigrav.core.network.ImpactReportMessage;
import wi.unigrav.core.boost.BoostNetwork;
import wi.unigrav.core.boost.RemoteBoostServer;
import wi.unigrav.core.boost.RemoteBoostCommand;
import wi.unigrav.proxy.IProxy;

/**
 * Universal Gravitation — library mod providing N-body gravitation APIs
 * (gravity fields, spherical-harmonic geopotential, symplectic integration).
 * Celestial simulation is left to downstream mods.
 */
@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION,
     acceptableRemoteVersions = "*",
     dependencies = "required-after:crlhitbox@[0.1.0-SNAPSHOT];after:neofontrender_ui_enhancements")
public class UnigravMod {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SidedProxy(modId = Reference.MOD_ID,
                clientSide = "wi.unigrav.proxy.ClientProxy",
                serverSide = "wi.unigrav.proxy.CommonProxy")
    public static IProxy proxy;

    /** Entity binding layer; valid after preInit. */
    public static GravityEntityBinder entityBinder;
    /** World gravity (uniform contribution) manager; valid after preInit. */
    public static WorldGravityManager worldGravity;
    /** Chunk gravity override manager; valid after preInit. */
    public static ChunkGravityManager chunkGravity;
    /** Region gravity manager; valid after preInit. */
    public static RegionGravityManager regionGravity;
    /** Default simulation clock; downstream mods may add bodies to {@link TickDriver#simulation()}. */
    public static TickDriver tickDriver;

    private final GravitySourcesLoader sourcesLoader = new GravitySourcesLoader();
    private final EntityBindingsLoader bindingsLoader = new EntityBindingsLoader();
    private final GlobalGravityTakeover takeover = new GlobalGravityTakeover();
    private final ServerMotionTimingDiagnostics motionTiming = new ServerMotionTimingDiagnostics();

    /** Network channel; currently only the client→server impact report. */
    public static SimpleNetworkWrapper NETWORK;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ChbDependency.requireSupportedContract();
        LOGGER.info("Universal Gravitation {} loading", Reference.VERSION);
        NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel(Reference.MOD_ID);
        NETWORK.registerMessage(ImpactReportMessage.Handler.class,
                ImpactReportMessage.class, 0, Side.SERVER);
        BoostNetwork.initialize();
        GIntentNetwork.initialize();
        GIntentServer.INSTANCE.install();
        RemoteBoostServer.INSTANCE.install();
        NativePlayerMovement.INSTANCE.install();
        entityBinder = new GravityEntityBinder();
        entityBinder.registerOnEventBus();
        PlayerPhysicsNetwork.initialize(PlayerPhysicsServer.INSTANCE::receive);
        PlayerPhysicsServer.INSTANCE.install();
        MinecraftForge.EVENT_BUS.register(motionTiming);
        MassPresetsLoader.load(event.getModConfigurationDirectory());
        worldGravity = new WorldGravityManager();
        worldGravity.registerOnEventBus();
        chunkGravity = new ChunkGravityManager();
        chunkGravity.registerOnEventBus();
        regionGravity = new RegionGravityManager();
        regionGravity.registerOnEventBus();
        MinecraftForge.EVENT_BUS.register(new KnockbackHandler());
        MinecraftForge.EVENT_BUS.register(ImpactDamageHandler.INSTANCE);
        tickDriver = new TickDriver(new NBodySimulation(
                SymplecticMethods.BLANES_MOAN_2002_S6, TickDriver.TICK_SECONDS));
        tickDriver.registerOnEventBus();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        motionTiming.start();
        event.registerServerCommand(new UnigravCommand());
        event.registerServerCommand(new RemoteBoostCommand());
        File sourcesDir = new File(Loader.instance().getConfigDir(), GravitySourcesLoader.SOURCES_SUBDIR);
        int loaded = sourcesLoader.loadAll(sourcesDir);
        LOGGER.info("Loaded {} gravity source(s) from {}", loaded, sourcesDir);
        bindingsLoader.loadAll(Loader.instance().getConfigDir());
        // Registered last so explicit entities.json rules always win.
        takeover.enable(bindingsLoader);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        motionTiming.stop();
        // Retire physical ownership while binding restoration and its G source still exist.
        PlayerPhysicsServer.INSTANCE.stop();
        GIntentServer.INSTANCE.stop();
        NativePlayerMovement.INSTANCE.stop();
        FloorWorldFields.clearAll();
        RemoteBoostServer.INSTANCE.stop();
        // Unbind entities first: restores noGravity on REPLACE-bound
        // entities before the world is saved.
        takeover.disable();
        bindingsLoader.unloadAll();
        for (var world : FMLCommonHandler.instance().getMinecraftServerInstance().worlds) entityBinder.releaseWorld(world);
        sourcesLoader.unloadAll();
    }

}
