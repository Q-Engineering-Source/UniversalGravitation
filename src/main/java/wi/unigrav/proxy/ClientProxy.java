package wi.unigrav.proxy;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Optional;
import net.minecraftforge.fml.client.registry.ClientRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.network.INetHandler;

import wi.unigrav.UnigravMod;
import wi.unigrav.core.BodyPhysics;
import wi.unigrav.core.client.ClientGravityTicker;
import wi.unigrav.core.client.ClientGIntent;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;
import wi.unigrav.core.client.PlayerRenderPoseMirror;
import wi.unigrav.core.client.ClientImpactDetector;
import wi.unigrav.core.client.GravityDebugCommand;
import wi.unigrav.core.client.GravityFieldRenderer;
import wi.unigrav.core.client.camera.RevoUiSixDofCamera;
import wi.unigrav.core.boost.BoostProtocol;
import wi.unigrav.core.client.boost.RemoteBoostClient;

public class ClientProxy extends CommonProxy {

    @Override public void runNativePose(EntityLivingBase actor, Runnable original) {
        if (actor.world.isRemote && actor instanceof EntityPlayerSP player)
            PlayerPhysicsClient.runNativePose(player, original);
        else super.runNativePose(actor, original);
    }

    @Override public float physicalEyeHeight(EntityLivingBase actor, float nativeHeight) {
        return actor.world.isRemote && actor instanceof EntityPlayerSP player
                ? PlayerPhysicsClient.physicalEyeHeight(player, nativeHeight) : nativeHeight;
    }

    @Override public void runLegacyMotion(EntityLivingBase actor, Runnable original) {
        if (actor.world.isRemote && actor instanceof EntityPlayerSP player)
            PlayerPhysicsClient.runLegacyMotion(player, original);
        else super.runLegacyMotion(actor, original);
    }

    private static final String REVO_UI_MOD_ID = "neofontrender_ui_enhancements";

    /** Process-lifetime registration handle; typed generically to keep UIE out of this surface. */
    private AutoCloseable sixDofCamera;

    @Override
    public void preInit() {
        RemoteBoostClient.install();
        ClientGIntent.install();
        PlayerPhysicsClient.install();
        PlayerRenderPoseMirror.INSTANCE.install();
        // Client-side Δv application for the local player (single-player
        // consistency with the integrated server).
        MinecraftForge.EVENT_BUS.register(new ClientGravityTicker(UnigravMod.entityBinder));
        ClientRegistry.registerKeyBinding(ClientGravityTicker.ZERO_G_KEY);
        ClientRegistry.registerKeyBinding(ClientGravityTicker.BOOST_KEY);
        // Install drift after the gravity ticker so a G toggle is visible in
        // the same END phase, but before impact detection samples final motion.
        if (Loader.isModLoaded(REVO_UI_MOD_ID)) {
            installRevoUiCamera();
        }
        // AFTER ClientGravityTicker and optional drift (same bus/phase): its
        // stored motion must include this tick's complete local-player Δv.
        MinecraftForge.EVENT_BUS.register(new ClientImpactDetector());
        // Gravity-field debug visualization (/ugv to toggle).
        MinecraftForge.EVENT_BUS.register(new GravityFieldRenderer());
        ClientCommandHandler.instance.registerCommand(new GravityDebugCommand());
    }

    @Override
    public void receiveRemoteBoost(BoostProtocol.Packet packet, INetHandler handler) {
        RemoteBoostClient.receive(packet, handler);
    }

    @Override
    public void receiveGIntent(GIntentProtocol.Packet packet, INetHandler handler) {
        ClientGIntent.receive(packet, handler);
    }

    @Override
    public void receivePlayerPhysics(PlayerPhysicsProtocol.Packet packet, INetHandler handler) {
        PlayerPhysicsClient.receive(packet, handler);
    }

    @Override
    public void receivePlayerRenderPose(PlayerRenderPoseProtocol.Packet packet, INetHandler handler) {
        PlayerRenderPoseMirror.INSTANCE.receive(packet, handler);
    }

    @Optional.Method(modid = REVO_UI_MOD_ID)
    private void installRevoUiCamera() {
        if (sixDofCamera == null) {
            RevoUiSixDofCamera.registerKeyBindings();
            sixDofCamera = RevoUiSixDofCamera.install(UnigravMod.entityBinder);
        }
    }

    @Override
    public void applyLocalPlayerMass(double massKg) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null || UnigravMod.entityBinder == null) {
            return;
        }
        BodyPhysics old = UnigravMod.entityBinder.bodyPhysicsOf(player);
        double volume = old != null ? old.volumeM3() : BodyPhysics.estimate(player).volumeM3();
        UnigravMod.entityBinder.setBodyPhysics(player, new BodyPhysics(massKg, volume));
    }
}
