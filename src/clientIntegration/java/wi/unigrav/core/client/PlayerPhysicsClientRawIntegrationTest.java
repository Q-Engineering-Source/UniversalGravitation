package wi.unigrav.core.client;

import wi.unigrav.proxy.CommonProxy;
import com.fuzs.aquaacrobatics.entity.player.IPlayerResizeable;
import com.fuzs.aquaacrobatics.entity.Pose;
import com.artemis.artemislib.util.attributes.ArtemisLibAttributes;
import wi.unigrav.core.player.NativePoseEyeProducer;
import wi.unigrav.core.player.NativeBodyProfileProducer;
import java.util.UUID;
import neofontrender.addons.api.flight.FlightAttitude;
import wi.unigrav.core.player.RawFixturePacketDecoder;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketAnimation;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeHandoff;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeMotion;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeTransferFailure;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeTransferRecovery;
import wi.unigrav.core.floor.SleepingNativeResumeServerTestSupport;
import wi.unigrav.core.player.NativePlayerMovement;
import wi.unigrav.core.player.PlayerPhysicsServer;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeCorrectionCompleted;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeHandoffAcknowledgement;
import wi.unigrav.core.player.PlayerPhysicsProtocol.NativeCorrectionAcknowledgement;
import org.apache.logging.log4j.message.StringMapMessage;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.stats.RecipeBook;
import net.minecraft.stats.StatisticsManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.internal.FMLNetworkHandler;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import wi.unigrav.UnigravMod;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.CardinalPredictionSeed;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerBodyReference;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.PlayerPhysicsInboundConnections;
import wi.unigrav.core.player.PlayerPhysicsNetwork;
import wi.unigrav.core.player.PlayerPhysicsProtocol;
import wi.unigrav.core.player.GIntentProtocol;
import wi.unigrav.core.player.PlayerRenderPoseProtocol;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static wi.unigrav.core.player.PlayerPhysicsProtocol.*;

/**
 * Loaded only by the clientIntegration source set. The normal Cleanroom client host runs the ten
 * JUnit cases on its main thread after FML load and Minecraft initialization have both completed.
 */
@Mod(modid = "ug_client_route_integration", name = "UG Client Route Integration Fixture", version = "1")
public class PlayerPhysicsClientRawIntegrationTest {
    private static final String RESULT_PROPERTY = "unigrav.clientIntegration.result";
    private static final int EXPECTED_TEST_COUNT = 25;

    @Mod.EventHandler
    public void registerAfterFmlLoad(FMLLoadCompleteEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !mc.isCallingFromMinecraftThread()
                || FMLClientHandler.instance().getClient() != mc) {
            throw new IllegalStateException(
                    "Client route integration fixture was not loaded by the normal FML client main thread");
        }
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void runAfterMinecraftInitialization(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getMinecraft();
        Throwable failure = null;
        String result = "FAIL: client route integration did not complete";

        try {
            MinecraftForge.EVENT_BUS.unregister(this);
            if (mc == null || !mc.isCallingFromMinecraftThread()) {
                throw new IllegalStateException("Client integration tick is not on the actual Minecraft main thread");
            }
            if (FMLClientHandler.instance().getClient() != mc) {
                throw new IllegalStateException("FML did not retain the actual Minecraft client instance");
            }
            if (mc.ingameGUI == null || mc.renderEngine == null) {
                throw new IllegalStateException(
                        "Minecraft init has not completed its GUI and renderer resource setup");
            }
            String reportPath = System.getProperty(RESULT_PROPERTY);
            if (reportPath == null || reportPath.isBlank()) {
                throw new IllegalStateException("Missing required client integration result path");
            }

            boolean provenance = Boolean.getBoolean("unigrav.clientIntegration.nativeProvenance");
            boolean sleeping = Boolean.getBoolean("unigrav.clientIntegration.sleepNativeResume");
            boolean nativeRecovery = Boolean.getBoolean("unigrav.clientIntegration.nativeTransferRecovery");
            boolean environmentRecovery = Boolean.getBoolean("unigrav.clientIntegration.serverEnvironmentRecovery");
            boolean aquaEye = Boolean.getBoolean("unigrav.clientIntegration.aquaNativeEye");
            boolean artemisBodyScale = Boolean.getBoolean("unigrav.clientIntegration.artemisBodyScale");
            boolean nativeDoubleSpace = Boolean.getBoolean("unigrav.clientIntegration.nativeDoubleSpace");
            boolean gOnActualInput = Boolean.getBoolean("unigrav.clientIntegration.gOnActualInput");
            boolean constructorProbe = Boolean.getBoolean("unigrav.clientIntegration.nativeBodyConstructorProbe");
            boolean installedImpact = Boolean.getBoolean("unigrav.clientIntegration.installedFreeImpact");
            if ((artemisBodyScale ? 1 : 0) + (nativeDoubleSpace ? 1 : 0) + (constructorProbe ? 1 : 0) + (gOnActualInput ? 1 : 0) + (aquaEye ? 1 : 0) + (installedImpact ? 1 : 0) + (environmentRecovery ? 1 : 0) + (sleeping ? 1 : 0) + (provenance ? 1 : 0) + (nativeRecovery ? 1 : 0) > 1)
                throw new IllegalStateException("Select only one client integration profile");
            var selectors = artemisBodyScale
                    ? java.util.List.of(DiscoverySelectors.selectClass(ArtemisScaleBodyProfile.class))
                    : nativeDoubleSpace
                    ? java.util.List.of(DiscoverySelectors.selectClass(NativeDoubleSpaceAbilitiesOwnedStateProfile.class))
                    : constructorProbe
                    ? java.util.List.of(DiscoverySelectors.selectClass(NativeBodyConstructorProbeProfile.class))
                    : gOnActualInput
                    ? java.util.List.of(DiscoverySelectors.selectClass(GOnActualInputProfile.class))
                    : aquaEye
                    ? java.util.List.of(DiscoverySelectors.selectClass(AquaNativeEyeProfile.class))
                    : installedImpact
                    ? java.util.List.of(DiscoverySelectors.selectMethod("wi.unigrav.core.player.InstalledFreeImpactTest", "actualLandingConsumesConfiguredSpeedAfterNativeFallExactlyOnce"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.InstalledFreeImpactTest", "duplicateAxisAndTwoAxisClipsAggregateIntoOneWallAttack"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.InstalledFreeImpactTest", "realDamageCallbackFailureKeepsOriginalCauseAndCannotReplayAttack"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.InstalledFreeImpactTest", "impactCallbackRetirementStopsTheOldTailWithoutASecondAttack"))
                    : environmentRecovery
                    ? java.util.List.of(DiscoverySelectors.selectClass(ServerEnvironmentRecoveryCases.class))
                    : nativeRecovery
                    ? java.util.List.of(DiscoverySelectors.selectClass(NativeTransferRecoveryCases.class))
                    : sleeping
                    ? java.util.List.of(DiscoverySelectors.selectClass(SleepNativeResumeProfile.class))
                    : provenance
                    ? java.util.List.of(DiscoverySelectors.selectMethod("wi.unigrav.core.player.NativeCollisionProvenanceRawIntegrationTest", "actualForgeCopiedObjectHasNoBlockAuthorityWhileSameObjectReinsertDoes"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.NativeCollisionProvenanceRawIntegrationTest", "actualSlimeHasNativeProducerWithoutExpandingBootQualification"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.NativeCollisionProvenanceRawIntegrationTest", "actualWorldStoneAndForgeRemovalUseSameCapture"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.NativeCollisionProvenanceRawIntegrationTest", "equalGeometryFromSurvivingBIsNeverAssignedToRemovedAAndHiddenOutputChangeInvalidatesRecapture"),
                            DiscoverySelectors.selectMethod("wi.unigrav.core.player.NativeCollisionProvenanceRawIntegrationTest", "realCustomProducerExceptionRetainsCauseAndStopsOldOwnerAfterTeleport"))
                    : java.util.List.of(DiscoverySelectors.selectClass(PlayerPhysicsClientRawIntegrationTest.class));
            var request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectors)
                    .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                    .build();
            var listener = new SummaryGeneratingListener();
            var completedCases = new java.util.TreeSet<String>();
            var caseListener = new org.junit.platform.launcher.TestExecutionListener() {
                @Override public void executionFinished(org.junit.platform.launcher.TestIdentifier id,
                        org.junit.platform.engine.TestExecutionResult result) {
                    if (id.isTest() && result.getStatus()
                            == org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL) {
                        completedCases.add(id.getSource().filter(org.junit.platform.engine.support.descriptor.MethodSource.class::isInstance)
                                .map(org.junit.platform.engine.support.descriptor.MethodSource.class::cast)
                                .orElseThrow().getMethodName());
                    }
                }
            };
            LauncherFactory.create().execute(request, listener, caseListener);
            TestExecutionSummary summary = listener.getSummary();
            failure = summarizeUnexpectedResult(summary);
            if (failure == null && provenance && !completedCases.equals(new java.util.TreeSet<>(java.util.List.of("actualForgeCopiedObjectHasNoBlockAuthorityWhileSameObjectReinsertDoes","actualSlimeHasNativeProducerWithoutExpandingBootQualification","actualWorldStoneAndForgeRemovalUseSameCapture","equalGeometryFromSurvivingBIsNeverAssignedToRemovedAAndHiddenOutputChangeInvalidatesRecapture","realCustomProducerExceptionRetainsCauseAndStopsOldOwnerAfterTeleport")))) {
                failure = new AssertionError("Native provenance successful case identities differ: " + completedCases);
            }
            if (failure == null && sleeping && (summary.getTestsFoundCount() != 1
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of("actualSleepWakeCompletesNativeTransferAndResumesOriginalBudget")))))
                failure = new AssertionError("Sleep native resume case identity/count differs: " + completedCases);
            if (failure == null && nativeRecovery && (summary.getTestsStartedCount() != 2
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualClientBasePartialFailureSchedulesExactRecoveryAndInstallsNewBase",
                            "actualClientMotionPartialFailureSchedulesExactRecoveryAndInstallsNewBase")))))
                failure = new AssertionError("Native transfer recovery case identity/started count differs: " + completedCases);
            if (failure == null && environmentRecovery && (summary.getTestsFoundCount() != 2
                    || summary.getTestsStartedCount() != 2 || summary.getTestsSucceededCount() != 2
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "healthyOwnerAcceptsExactRecoveryAndDuplicatesPreserveInstalledSuccessor",
                            "ordinaryHigherEpochAndRecoveryAfterNewTeleportCannotReplaceHealthyCustody")))))
                failure = new AssertionError("Server environment recovery case identity/count differs: " + completedCases);
            if (failure == null && installedImpact && (summary.getTestsFoundCount() != 4
                    || summary.getTestsStartedCount() != 4 || summary.getTestsSucceededCount() != 4
                    || summary.getTestsFailedCount() != 0 || summary.getTestsAbortedCount() != 0
                    || summary.getTestsSkippedCount() != 0
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualLandingConsumesConfiguredSpeedAfterNativeFallExactlyOnce",
                            "duplicateAxisAndTwoAxisClipsAggregateIntoOneWallAttack",
                            "realDamageCallbackFailureKeepsOriginalCauseAndCannotReplayAttack",
                            "impactCallbackRetirementStopsTheOldTailWithoutASecondAttack")))))
                failure = new AssertionError("Installed Free impact case identity/count differs: " + completedCases);
            if (failure == null && aquaEye && (summary.getTestsFoundCount() != 1
                    || summary.getTestsStartedCount() != 1 || summary.getTestsSucceededCount() != 1
                    || summary.getTestsFailedCount() != 0 || summary.getTestsAbortedCount() != 0
                    || summary.getTestsSkippedCount() != 0
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualAquaCrouchEyePublishesAndReversesThroughOwnedState")))))
                failure = new AssertionError("Actual Aqua eye case identity/count differs: " + completedCases);
            if (failure == null && gOnActualInput && (summary.getTestsFoundCount() != 1
                    || summary.getTestsStartedCount() != 1 || summary.getTestsSucceededCount() != 1
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualKeyboardFrameTraversesOwnedGOnInputAndAdvancesFree")))))
                failure = new AssertionError("G ON actual input case identity/count differs: " + completedCases);
            if (failure == null && artemisBodyScale && (summary.getTestsFoundCount() != 1
                    || summary.getTestsStartedCount() != 1 || summary.getTestsSucceededCount() != 1
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualArtemisScaleProducerSurvivesManualLockAndFreeUnlock")))))
                failure = new AssertionError("Actual Artemis body-scale identity/count differs: " + completedCases);
            if (failure == null && nativeDoubleSpace && (summary.getTestsFoundCount() != 1
                    || summary.getTestsStartedCount() != 1 || summary.getTestsSucceededCount() != 1
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualNativeDoubleSpaceSendsAbilitiesAndKeepsOwnedState")))))
                failure = new AssertionError("Native double-space abilities-owned-state identity/count differs: " + completedCases);
            if (failure == null && constructorProbe && (summary.getTestsFoundCount() != 1
                    || summary.getTestsStartedCount() != 1 || summary.getTestsSucceededCount() != 1
                    || !completedCases.equals(new java.util.TreeSet<>(java.util.List.of(
                            "actualEntityConstructorCapturesFullFieldsBeforeVirtualInitialization")))))
                failure = new AssertionError("Native body constructor probe identity/count differs: " + completedCases);
            result = failure == null
                    ? "PASS tests=" + summary.getTestsFoundCount() + " succeeded=" + summary.getTestsSucceededCount()
                            + " failed=0 aborted=0 skipped=0" + System.lineSeparator()
                            + "cases=" + String.join(",", completedCases)
                    : "FAIL found=" + summary.getTestsFoundCount()
                            + " succeeded=" + summary.getTestsSucceededCount()
                            + " failed=" + summary.getTestsFailedCount()
                            + " aborted=" + summary.getTestsAbortedCount()
                            + " skipped=" + summary.getTestsSkippedCount();
            if (artemisBodyScale && failure == null) {
                result += System.lineSeparator() + "profile=artemisBodyScale"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (nativeDoubleSpace && failure == null) {
                result += System.lineSeparator() + "profile=nativeDoubleSpaceAbilitiesOwnedState"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (constructorProbe && failure == null) {
                result += System.lineSeparator() + "profile=nativeBodyConstructorProbe"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (gOnActualInput && failure == null) {
                result += System.lineSeparator() + "profile=gOnActualInput"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (aquaEye && failure == null) {
                result += System.lineSeparator() + "profile=aquaNativeEye"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (installedImpact && failure == null) {
                result += System.lineSeparator() + "profile=installedFreeImpact"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (environmentRecovery && failure == null) {
                result += System.lineSeparator() + "profile=serverEnvironmentRecovery"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (nativeRecovery && failure == null) {
                result += System.lineSeparator() + "profile=nativeTransferRecovery"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount();
            }
            if (sleeping && failure == null) {
                result += System.lineSeparator() + "profile=sleepNativeResume"
                        + System.lineSeparator() + "started=" + summary.getTestsStartedCount()
                        + System.lineSeparator() + "nonce=" + System.getProperty("unigrav.sleepEvidence.nonce", "MISSING")
                        + System.lineSeparator() + "identity=" + System.getProperty("unigrav.sleepEvidence.identity", "MISSING");
            }
        } catch (Throwable testHostFailure) {
            failure = testHostFailure;
        }

        // shutdown only stops the next main-loop iteration; this START tick must remain safe.
        if (Boolean.getBoolean("unigrav.clientIntegration.sleepNativeResume")
                || Boolean.getBoolean("unigrav.clientIntegration.aquaNativeEye")
                || Boolean.getBoolean("unigrav.clientIntegration.gOnActualInput")
                || Boolean.getBoolean("unigrav.clientIntegration.nativeDoubleSpace")
                || Boolean.getBoolean("unigrav.clientIntegration.artemisBodyScale")) {
            try {
                if (mc == null) throw new IllegalStateException("sleep host lost Minecraft before menu handoff");
                assertNull(mc.world, "sleep fixture must release its world before menu handoff");
                assertNull(mc.player, "sleep fixture must release its player before menu handoff");
                mc.displayGuiScreen(new GuiMainMenu());
                assertNull(mc.world, "menu handoff must not create a world");
                assertNull(mc.player, "menu handoff must not create a player");
                assertNotNull(mc.currentScreen, "sleep host must return to a real menu before shutdown");
                assertFalse(mc.currentScreen.allowUserInput, "menu must block the remaining game's input tick");
            } catch (Throwable menuFailure) {
                if (failure == null) failure = menuFailure;
                else if (failure != menuFailure) failure.addSuppressed(menuFailure);
                result = "FAIL: sleep host menu handoff failed";
            }
        }

        System.out.println("[UG_RAW_RESULT] " + result);
        if (failure != null) failure.printStackTrace(System.out);
        // Persist the outcome before normal client shutdown can terminate the JVM.
        try {
            writeResult(result, failure);
        } catch (Throwable reportFailure) {
            reportFailure.printStackTrace(System.out);
            if (failure == null) failure = reportFailure;
            else if (failure != reportFailure) failure.addSuppressed(reportFailure);
        }
        try {
            if (mc != null) mc.shutdown();
        } catch (Throwable shutdownFailure) {
            if (failure == null) failure = shutdownFailure;
            else if (failure != shutdownFailure) failure.addSuppressed(shutdownFailure);
        }

        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure != null) {
            throw new IllegalStateException("Client route integration host failed", failure);
        }
    }

    private static Throwable summarizeUnexpectedResult(TestExecutionSummary summary) {
        if (summary.getTestsFoundCount() == (Boolean.getBoolean("unigrav.clientIntegration.installedFreeImpact") ? 4 : ((Boolean.getBoolean("unigrav.clientIntegration.nativeTransferRecovery") || Boolean.getBoolean("unigrav.clientIntegration.serverEnvironmentRecovery")) ? 2 : ((Boolean.getBoolean("unigrav.clientIntegration.sleepNativeResume") || Boolean.getBoolean("unigrav.clientIntegration.aquaNativeEye") || Boolean.getBoolean("unigrav.clientIntegration.gOnActualInput") || Boolean.getBoolean("unigrav.clientIntegration.nativeBodyConstructorProbe") || Boolean.getBoolean("unigrav.clientIntegration.nativeDoubleSpace") || Boolean.getBoolean("unigrav.clientIntegration.artemisBodyScale")) ? 1 : (Boolean.getBoolean("unigrav.clientIntegration.nativeProvenance") ? 5 : EXPECTED_TEST_COUNT))))
                && summary.getTestsSucceededCount() == (Boolean.getBoolean("unigrav.clientIntegration.installedFreeImpact") ? 4 : ((Boolean.getBoolean("unigrav.clientIntegration.nativeTransferRecovery") || Boolean.getBoolean("unigrav.clientIntegration.serverEnvironmentRecovery")) ? 2 : ((Boolean.getBoolean("unigrav.clientIntegration.sleepNativeResume") || Boolean.getBoolean("unigrav.clientIntegration.aquaNativeEye") || Boolean.getBoolean("unigrav.clientIntegration.gOnActualInput") || Boolean.getBoolean("unigrav.clientIntegration.nativeBodyConstructorProbe") || Boolean.getBoolean("unigrav.clientIntegration.nativeDoubleSpace") || Boolean.getBoolean("unigrav.clientIntegration.artemisBodyScale")) ? 1 : (Boolean.getBoolean("unigrav.clientIntegration.nativeProvenance") ? 5 : EXPECTED_TEST_COUNT))))
                && summary.getTestsFailedCount() == 0
                && summary.getTestsAbortedCount() == 0
                && summary.getTestsSkippedCount() == 0) {
            return null;
        }
        var failure = new AssertionError("Expected exactly " + (Boolean.getBoolean("unigrav.clientIntegration.installedFreeImpact") ? 4 : ((Boolean.getBoolean("unigrav.clientIntegration.nativeTransferRecovery") || Boolean.getBoolean("unigrav.clientIntegration.serverEnvironmentRecovery")) ? 2 : ((Boolean.getBoolean("unigrav.clientIntegration.sleepNativeResume") || Boolean.getBoolean("unigrav.clientIntegration.aquaNativeEye") || Boolean.getBoolean("unigrav.clientIntegration.gOnActualInput") || Boolean.getBoolean("unigrav.clientIntegration.nativeBodyConstructorProbe") || Boolean.getBoolean("unigrav.clientIntegration.nativeDoubleSpace") || Boolean.getBoolean("unigrav.clientIntegration.artemisBodyScale")) ? 1 : (Boolean.getBoolean("unigrav.clientIntegration.nativeProvenance") ? 5 : EXPECTED_TEST_COUNT)))) + " successful client integration tests; found="
                + summary.getTestsFoundCount()
                + ", succeeded=" + summary.getTestsSucceededCount()
                + ", failed=" + summary.getTestsFailedCount()
                + ", aborted=" + summary.getTestsAbortedCount()
                + ", skipped=" + summary.getTestsSkippedCount());
        summary.getFailures().forEach(testFailure -> {
            Throwable cause = testFailure.getException();
            if (cause != failure) failure.addSuppressed(cause);
        });
        return failure;
    }

    private static void writeResult(String result, Throwable failure) throws Exception {
        String reportPath = System.getProperty(RESULT_PROPERTY);
        if (reportPath == null || reportPath.isBlank()) {
            throw new IllegalStateException("Missing required client integration result path");
        }
        Path path = Path.of(reportPath);
        Files.createDirectories(path.getParent());
        String content = result + System.lineSeparator();
        if (failure != null) {
            var stack = new StringWriter();
            try (var writer = new PrintWriter(stack)) {
                failure.printStackTrace(writer);
            }
            content += stack;
        }
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("[UG_RAW_REPORT_WRITTEN] path=" + path.toAbsolutePath()
                + " bytes=" + Files.size(path));
    }

    @Test void normalConstructionAndRawOfferStayOnRealClientTickOwner() {
        try (var fixture = new Fixture()) {
            assertSame(fixture.mc, Minecraft.getMinecraft());
            assertTrue(fixture.mc.isCallingFromMinecraftThread());
            assertSame(fixture.handler, fixture.mc.getConnection());
            assertSame(fixture.manager, fixture.handler.getNetworkManager());
            assertSame(fixture.handler, fixture.manager.getNetHandler());
            assertSame(fixture.world, fixture.player.world);
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty());
            fixture.enqueue(offer(fixture.scope(1)));
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty(), "ingress cannot accept the OFFER before START");
            fixture.tickStart();
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isPresent(), "the real OFFER drain creates its targeted Session");
            assertFalse(PlayerPhysicsClient.owns(fixture.player), "OFFER does not grant physics custody");
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty());
            assertSame(fixture.connection, PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(
                    fixture.manager, fixture.handler, EnumPacketDirection.CLIENTBOUND).orElseThrow());
        }
    }

    /** Run only after the independent constructor/OFFER smoke and real Forge outbound route are available. */
    @Test void realApplyOwnedStateAndBoundsFailureFenceExactActorLifetime() {
        // Exercise the production START event and public eye provider without predicting a local slot.
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            var reference = new PlayerBodyReference.State(Optional.empty(),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.NATURAL,
                            CardinalFrame.Y_POS, CardinalFrame.Y_POS.attitude().basisZ()),
                    PlayerBodyReference.Transition.STABLE, Optional.empty(), Optional.empty());
            var first = snapshot(scope, 1, 8);
            fixture.deliver(withReference(state(Kind.OWNED, 1, first), reference));
            var end = snapshot(scope, 2, 10);
            fixture.deliver(withReference(state(Kind.STATE, 2, end), reference));
            assertEquals(end, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, end));
            assertEquals(reference.effective(),
                    PlayerPhysicsClient.acceptedEffectiveBodyReference(fixture.player).orElseThrow());
            var startEye = first.placement().eye();
            var endEye = end.placement().eye();
            assertEquals(startEye.add(endEye.subtract(startEye).multiply((double) .66F)),
                    PlayerPhysicsClient.acceptedEyePosition(fixture.player, .66F).orElseThrow());
            fixture.tickStart(); // No new STATE: the completed span must not play again after partial resets.
            assertEquals(endEye, PlayerPhysicsClient.acceptedEyePosition(fixture.player, .32F).orElseThrow(),
                    "a new client tick without installation must retire the completed accepted eye span");
            assertEquals(endEye, PlayerPhysicsClient.acceptedEyePosition(fixture.player, .32F).orElseThrow(),
                    "provider reads do not advance presentation time");
            assertEquals(end, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            var middle = snapshot(scope, 3, 11);
            var finalState = snapshot(scope, 4, 12);
            fixture.enqueue(withReference(state(Kind.STATE, 3, middle), reference));
            fixture.enqueue(withReference(state(Kind.STATE, 4, finalState), reference));
            fixture.tickStart();
            assertEquals(endEye, PlayerPhysicsClient.acceptedEyePosition(fixture.player, 0).orElseThrow(),
                    "all installations in one START drain share the preceding tick endpoint");
            assertEquals(finalState.placement().eye(),
                    PlayerPhysicsClient.acceptedEyePosition(fixture.player, 1).orElseThrow());
        }

        try (var fixture = new Fixture(); var failures = new FailureLogCapture()) {
            fixture.requireActualForgeOutboundRoute();
            // The normal UG preInit has installed the production SimpleNetworkWrapper. The APPLY
            // assertion below observes that sender through Forge; the fixture installs no sender.
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            var accepted = snapshot(scope, 1, 8);
            fixture.deliver(state(Kind.OWNED, 1, accepted));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, accepted));
            assertEquals(accepted, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());

            var failed = snapshot(scope, 2, 20);
            var failedPacket = state(Kind.STATE, 2, failed);
            var sentinel = new IllegalStateException("bounds callback failed after the real native write");
            fixture.player.boundsFailure = sentinel;
            fixture.enqueue(failedPacket);
            assertDoesNotThrow(fixture::tickStart, "a reliably retired installation fails only its captured drain task");
            assertTrue(failures.contains(sentinel), "the responsibility boundary logs the original cause and stack");
            assertTrue(sentinel.getStackTrace().length > 0);
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, failed));
            assertFenced(fixture.player);
            fixture.deliver(failedPacket);
            assertFenced(fixture.player);
            fixture.deliver(offer(scope));
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty(), "a retired epoch cannot open another Session");
            var sameEpochAnotherSession = new PlayerPhysicalSnapshot.Scope(scope.playerId(), UUID.randomUUID(),
                    scope.dimension(), scope.worldEpoch());
            fixture.deliver(offer(sameEpochAnotherSession));
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty(), "a different session UUID cannot reuse the retired epoch");
            var staleMovement = new CPacketPlayer(false);
            assertSame(staleMovement, PlayerPhysicsClient.prepareOutgoing(fixture.handler, staleMovement));
            fixture.channel.runPendingTasks();
            fixture.channel.checkException();
            assertNull(fixture.channel.readOutbound(), "replayed OFFER cannot emit recovery APPLY");
            fixture.deliver(state(Kind.OWNED, 1, accepted));
            assertFenced(fixture.player);
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty());
            var recoveryScope = fixture.scope(2);
            fixture.deliver(offer(recoveryScope));
            assertFenced(fixture.player);
            var vanilla = new CPacketPlayer(false);
            assertSame(vanilla, PlayerPhysicsClient.prepareOutgoing(fixture.handler, vanilla),
                    "recovery APPLY does not publish the partial native position");
            fixture.channel.runPendingTasks();
            fixture.channel.checkException();
            fixture.assertActualApply(recoveryScope);
            assertFenced(fixture.player);
            var recoveredSameActor = snapshot(recoveryScope, 1, 25);
            fixture.deliver(state(Kind.OWNED, 1, recoveredSameActor));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player), "only a new scope's complete real installation releases the fence");
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertEquals(recoveredSameActor, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());

            var oldActorFailure = new IllegalStateException("old actor failure");
            fixture.player.boundsFailure = oldActorFailure;
            fixture.enqueue(state(Kind.STATE, 2, snapshot(recoveryScope, 2, 30)));
            assertDoesNotThrow(fixture::tickStart);
            assertTrue(failures.contains(oldActorFailure));
            CallbackPlayer retired = fixture.player;
            MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(fixture.world));
            assertFenced(retired);
            fixture.player = new CallbackPlayer(fixture.mc, fixture.world, fixture.handler);
            fixture.mc.player = fixture.player;
            assertEquals(retired.getUniqueID(), fixture.player.getUniqueID());
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player), "the same UUID does not identify the failed Java object");
            var newScope = fixture.scope(3);
            fixture.offerAndApply(newScope);
            var recovered = snapshot(newScope, 1, 40);
            fixture.deliver(state(Kind.OWNED, 1, recovered));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertEquals(recovered, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            assertFenced(retired);
        }
    }

    @Test void firstOwnedFailureEndsItsDrainAndEmitsSameRecoverIdentityFromFencedEndTicks() {
        try (var fixture = new Fixture(); var failures = new FailureLogCapture()) {
            fixture.requireActualForgeOutboundRoute();
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            var sentinel = new IllegalStateException("first OWNED bounds callback failed after the native write");
            fixture.player.boundsFailure = sentinel;
            fixture.enqueue(state(Kind.OWNED, 1, snapshot(scope, 1, 20)));
            assertDoesNotThrow(fixture::tickStart);
            assertTrue(failures.contains(sentinel));
            assertFenced(fixture.player);
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty());
            for (int tick = 1; tick <= 20; tick++) fixture.tickEnd();
            var first = fixture.readActualPhysicsPacket(Kind.RECOVER, scope);
            assertEquals(1, first.offerSequence());
            assertEquals(2, first.sequence());
            assertFenced(fixture.player);
            for (int tick = 1; tick <= 20; tick++) fixture.tickEnd();
            assertEquals(first, fixture.readActualPhysicsPacket(Kind.RECOVER, scope));
            assertFenced(fixture.player);
            fixture.deliver(offer(scope));
            fixture.deliver(state(Kind.OWNED, 2, snapshot(scope, 2, 21)));
            assertFenced(fixture.player);
            var next = fixture.scope(2);
            fixture.offerAndApply(next);
            assertFenced(fixture.player);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(next, 1, 25)));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        }
    }

    @Test void uncertainCleanupStillEscapesWithOriginalInstallationCauseAndSuppressedCleanup() {
        assertFixtureConstructionRollback();
        try (var fixture = new Fixture()) {
            fixture.requireActualForgeOutboundRoute();
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(scope, 1, 8)));
            var original = new IllegalStateException("bounds write failed");
            var cleanup = new IllegalStateException("native no-gravity restoration failed");
            fixture.player.boundsFailure = original;
            fixture.player.restorationFailure = cleanup;
            fixture.enqueue(state(Kind.STATE, 2, snapshot(scope, 2, 20)));
            var escaped = assertThrows(Throwable.class, fixture::tickStart);
            assertTrue(containsIdentity(escaped, original));
            assertTrue(containsIdentity(original, cleanup));
            assertTrue(original.getSuppressed().length > 0);
            assertFenced(fixture.player);
        }
    }

    private static void actualHealthyDelegatedOwnerNegotiatesSameScopeHigherProposal() {
        try (var fixture = new Fixture()) {
            fixture.requireActualForgeOutboundRoute();
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            var pose = snapshot(scope, 1, 12);
            var receipt = new BodyReferenceReceipt(1, BodyReferenceDecision.UNLOCKED, 0,
                    Optional.empty(), Optional.empty(), Optional.of(BodyReferenceReason.NATIVE_HANDOFF));
            fixture.deliver(new Packet(Kind.STATE, scope, 1, 1, Optional.empty(), Optional.of(pose),
                    Optional.empty(), OptionalInt.empty(), Optional.of(receipt), Optional.empty()));
            fixture.tickEnd();
            assertEquals(new BodyReferenceAcknowledgement(1, 1), fixture.readActualPhysicsPacket(Kind.INPUT, scope)
                    .bodyReferenceAcknowledgement().orElseThrow());
            var base = new NativeHandoff(95, 1, new Vec3d(13, 14, 15));
            fixture.deliver(PlayerPhysicsProtocol.nativeTransferPacket(Kind.NATIVE, scope, 2, 1, base));
            assertEquals(new NativeHandoffAcknowledgement(95, 1), fixture.readActualPhysicsPacket(Kind.NATIVE_ACK, scope)
                    .nativeTransfer().orElseThrow());
            assertTrue(PlayerPhysicsClient.owns(fixture.player));
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertEquals(13.0 / 20, fixture.player.motionX, 0);
            assertEquals(14.0 / 20, fixture.player.motionY, 0);
            assertEquals(15.0 / 20, fixture.player.motionZ, 0);
            fixture.deliver(new Packet(Kind.OFFER, scope, 3, 2,
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player), "OFFER cannot grant movement");
            var position = new CPacketPlayer.Position(fixture.player.posX, fixture.player.getEntityBoundingBox().minY,
                    fixture.player.posZ, fixture.player.onGround);
            PlayerPhysicsClient.prepareOutgoing(fixture.handler, position);
            var apply = fixture.readActualPhysicsPacket(Kind.APPLY, scope);
            assertEquals(2, apply.offerSequence());
            var resumed = snapshot(scope, 2, 12);
            fixture.deliver(new Packet(Kind.OWNED, scope, 4, 2,
                    Optional.empty(), Optional.of(resumed), Optional.empty()));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertEquals(resumed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, resumed));
            fixture.tickEnd();
            assertEquals(new BodyReferenceAcknowledgement(4, 2), fixture.readActualPhysicsPacket(Kind.INPUT, scope)
                    .bodyReferenceAcknowledgement().orElseThrow());
            fixture.deliver(new Packet(Kind.OFFER, scope, 5, 1,
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player), "old proposal cannot reopen the installed epoch");
            assertEquals(resumed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
        }
    }

    private static void actualCorrectedFreeResourcesSurviveDelegatedSameScopeResume() {
        try (var fixture = new Fixture()) {
            fixture.requireActualForgeOutboundRoute();
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var cardinal = snapshot(scope, 1, 12);
            var free = new PlayerPhysicalSnapshot(scope, 1,
                    new PlayerBodyPlacement(cardinal.placement().center(), cardinal.placement().attitude(),
                            new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                    cardinal.worldVelocity(), cardinal.contacts());
            fixture.deliver(state(Kind.OWNED, 1, free));
            int[] nativeOperations = {0};
            Runnable operation = () -> { nativeOperations[0]++; fixture.player.isInLava(); };
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(1, nativeOperations[0]);
            PlayerPhysicsClient.nativeCorrection(fixture.handler, 91);
            var corrected = new PlayerPhysicalSnapshot(scope, 2, free.placement(), free.worldVelocity(), free.contacts());
            fixture.deliver(new Packet(Kind.STATE, scope, 2, 1, Optional.empty(), Optional.of(corrected),
                    Optional.empty(), OptionalInt.of(91)));
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(2, nativeOperations[0]);
            var restore = new PlayerPhysicalSnapshot(scope, 3, cardinal.placement(), cardinal.worldVelocity(), cardinal.contacts());
            var receipt = new BodyReferenceReceipt(1, BodyReferenceDecision.UNLOCKED, 0,
                    Optional.empty(), Optional.empty(), Optional.of(BodyReferenceReason.NATIVE_HANDOFF));
            fixture.deliver(new Packet(Kind.STATE, scope, 3, 1, Optional.empty(), Optional.of(restore),
                    Optional.empty(), OptionalInt.of(91), Optional.of(receipt), Optional.empty()));
            fixture.tickEnd();
            assertEquals(new BodyReferenceAcknowledgement(3, 3), fixture.readActualPhysicsPacket(Kind.INPUT, scope)
                    .bodyReferenceAcknowledgement().orElseThrow());
            fixture.deliver(PlayerPhysicsProtocol.nativeTransferPacket(Kind.NATIVE, scope, 4, 1,
                    new NativeHandoff(96, restore.revision(), new Vec3d(13, 14, 15))));
            assertEquals(new NativeHandoffAcknowledgement(96, restore.revision()), fixture.readActualPhysicsPacket(Kind.NATIVE_ACK, scope)
                    .nativeTransfer().orElseThrow());
            assertTrue(PlayerPhysicsClient.owns(fixture.player));
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
            fixture.deliver(new Packet(Kind.OFFER, scope, 5, 2, Optional.empty(), Optional.empty(), Optional.empty()));
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty(),
                    "resume OFFER cannot publish an installed Free observer");
            PlayerPhysicsClient.prepareOutgoing(fixture.handler,
                    new CPacketPlayer.Position(fixture.player.posX, fixture.player.getEntityBoundingBox().minY,
                            fixture.player.posZ, fixture.player.onGround));
            assertEquals(2, fixture.readActualPhysicsPacket(Kind.APPLY, scope).offerSequence());
            var resumed = new PlayerPhysicalSnapshot(scope, 4, free.placement(), free.worldVelocity(), free.contacts());
            fixture.deliver(new Packet(Kind.OFFER, scope, 6, 3, Optional.empty(), Optional.empty(), Optional.empty()));
            fixture.deliver(new Packet(Kind.OWNED, scope, 7, 2, Optional.empty(), Optional.of(resumed), Optional.empty()));
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player), "superseded proposal cannot consume the resume token");
            assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty());
            PlayerPhysicsClient.prepareOutgoing(fixture.handler,
                    new CPacketPlayer.Position(fixture.player.posX, fixture.player.getEntityBoundingBox().minY,
                            fixture.player.posZ, fixture.player.onGround));
            assertEquals(3, fixture.readActualPhysicsPacket(Kind.APPLY, scope).offerSequence());
            var owned = new Packet(Kind.OWNED, scope, 8, 3, Optional.empty(), Optional.of(resumed), Optional.empty());
            fixture.deliver(owned);
            assertEquals(resumed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, resumed));
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(3, nativeOperations[0], "new proposal installation must transfer correction91 accounting to empty correction");
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            fixture.deliver(owned);
            assertEquals(resumed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            var ordinary = new PlayerPhysicalSnapshot(scope, 5, resumed.placement(), resumed.worldVelocity(), resumed.contacts());
            fixture.deliver(new Packet(Kind.STATE, scope, 9, 3, Optional.empty(), Optional.of(ordinary), Optional.empty()));
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(4, nativeOperations[0], "ordinary STATE continues the same resource owner");
            PlayerPhysicsClient.nativeCorrection(fixture.handler, 1);
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(4, nativeOperations[0]);
            var next = new PlayerPhysicalSnapshot(scope, 6, resumed.placement(), resumed.worldVelocity(), resumed.contacts());
            fixture.deliver(new Packet(Kind.STATE, scope, 10, 3, Optional.empty(), Optional.of(next),
                    Optional.empty(), OptionalInt.of(1)));
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(5, nativeOperations[0], "normal correction after resume uses the new proposal identity, not old91");
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        }
    }

    @Test void teleportCorrectionAndPostCompletionAckRefusalSettleOnlyTheirExactDecode() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var cardinal = snapshot(scope, 1, 8);
            var free = new PlayerPhysicalSnapshot(scope, 1,
                    new PlayerBodyPlacement(cardinal.placement().center(), cardinal.placement().attitude(),
                            new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                    cardinal.worldVelocity(), cardinal.contacts());
            fixture.deliver(state(Kind.OWNED, 1, free));
            int[] nativeOperations = {0};
            Runnable operation = () -> { nativeOperations[0]++; fixture.player.isInLava(); };
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(1, nativeOperations[0]);
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            PlayerPhysicsClient.nativeCorrection(fixture.handler, 91);
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(1, nativeOperations[0], "pending correction pauses native environment work");
            var corrected = new PlayerPhysicalSnapshot(scope, 2, free.placement(), free.worldVelocity(), free.contacts());
            fixture.deliver(new Packet(Kind.STATE, scope, 2, 1, Optional.empty(), Optional.of(corrected),
                    Optional.empty(), OptionalInt.of(91)));
            assertEquals(corrected, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            PlayerPhysicsClient.runNativeEnvironment(fixture.player, operation);
            assertEquals(2, nativeOperations[0],
                    "matching completed same-scope Free correction must resume the real native operation");
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        }

        actualHealthyDelegatedOwnerNegotiatesSameScopeHigherProposal();
        actualCorrectedFreeResourcesSurviveDelegatedSameScopeResume();
        try (var fixture = new Fixture()) {
            fixture.requireActualForgeOutboundRoute();
            var scope = fixture.scope(1);
            fixture.offerAndApply(scope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(scope, 1, 8)));
            PlayerPhysicsClient.nativeCorrection(fixture.handler, 37);
            var corrected = snapshot(scope, 2, 8.5);
            var packet = new Packet(Kind.STATE, scope, 2, 1, Optional.empty(), Optional.of(corrected),
                    Optional.empty(), OptionalInt.of(37));
            assertDoesNotThrow(() -> fixture.deliver(packet));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertEquals(corrected, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();
            var input = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertTrue(input.nativeTeleportId().isEmpty());
            assertEquals(new BodyReferenceAcknowledgement(2, 2), input.bodyReferenceAcknowledgement().orElseThrow());

            var retransmission = new Packet(Kind.STATE, scope, 3, 1, Optional.empty(), Optional.of(corrected),
                    Optional.empty(), OptionalInt.of(37));
            assertDoesNotThrow(() -> fixture.deliver(retransmission));
            fixture.tickEnd();
            assertEquals(new BodyReferenceAcknowledgement(3, 2),
                    fixture.readActualPhysicsPacket(Kind.INPUT, scope).bodyReferenceAcknowledgement().orElseThrow());

            // The explicit pending reference is sufficient restoration purpose even without a receipt.
            var pendingReference = new PlayerBodyReference.State(Optional.empty(),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE,
                            CardinalFrame.Y_POS, new Vec3d(1, 0, 0)),
                    PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING, Optional.empty(), Optional.empty());
            var legalRestore = new Packet(Kind.STATE, scope, 4, 1, Optional.empty(),
                    Optional.of(snapshot(scope, 3, 20)), Optional.empty(), OptionalInt.of(37),
                    Optional.empty(), Optional.empty(), Optional.of(pendingReference));
            assertDoesNotThrow(() -> fixture.deliver(legalRestore));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertEquals(legalRestore.snapshot().orElseThrow(),
                    PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();
            assertEquals(new BodyReferenceAcknowledgement(4, 3),
                    fixture.readActualPhysicsPacket(Kind.INPUT, scope).bodyReferenceAcknowledgement().orElseThrow());

            // A native restoration target must be canonical Y_POS. The packet is structurally valid,
            // but its matching X_POS reference cannot authorize the native installation ACK domain.
            var sideShape = new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                    legalRestore.snapshot().orElseThrow().placement().shape().dimensions());
            var invalidTarget = new PlayerPhysicalSnapshot(scope, 4,
                    new PlayerBodyPlacement(new Vec3d(21, 70, 8), CardinalFrame.X_POS.attitude(), sideShape),
                    new Vec3d(1, 2, 3), legalRestore.snapshot().orElseThrow().contacts());
            var invalidRestore = new Packet(Kind.STATE, scope, 5, 1, Optional.empty(),
                    Optional.of(invalidTarget), Optional.empty(), OptionalInt.of(37),
                    Optional.empty(), Optional.empty(), Optional.of(new PlayerBodyReference.State(
                            Optional.empty(), new PlayerBodyReference.Effective(PlayerBodyReference.Source.SAFE_NATIVE,
                                    CardinalFrame.X_POS, new Vec3d(0, 0, 1)),
                            PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING, Optional.empty(), Optional.empty())));
            assertDoesNotThrow(() -> fixture.deliver(invalidRestore));
            assertFenced(fixture.player);
            assertTrue(PlayerPhysicsClient.bodyReferenceStatus().isEmpty());
            var recoveredScope = fixture.scope(2);
            fixture.offerAndApply(recoveredScope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(recoveredScope, 1, 25)));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        }
    }

    @Test void predictionFirstInputProvidersAndStrictRetransmissionShareActualCurrentPose() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var authority = predictionSnapshot(scope, 1, 8);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, authority, Optional.empty()));
            assertEquals(authority, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();
            var first = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            var identity = first.predictionInput().orElseThrow();
            var tangent = PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow();
            assertEquals(identity.clientTick(), tangent.clientTick());
            assertNotNull(PlayerPhysicsClient.cardinalBodyForward(fixture.player, .5F));
            assertSame(tangent, PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow(),
                    "provider reads cannot advance the committed input/body sample");
            var predicted = PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow();
            assertNotEquals(authority.placement(), predicted.placement());
            assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty(), "accepted-only cannot bless a predicted actor");
            assertTrue(PlayerPhysicsClient.acceptedEyePosition(fixture.player, 1).isEmpty());
            assertCurrentUieProviders(fixture, predicted);
            fixture.tickStart(); fixture.tickEnd();
            fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            var boundary = new PredictionBoundary(identity.epoch(), 1, 10, 11, identity.firstClientTick(),
                    first.sequence(), authority.revision(), first.sequence(), 0, JumpDecision.none(), StepOutcome.APPLIED);
            // Exact authority geometry is a retransmission. It must restore its real anchor before strict ACK proof.
            fixture.deliver(predictionPacket(Kind.STATE, 2, authority, Optional.of(boundary)));
            var replayed = PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow();
            assertEquals(predicted.placement(), replayed.placement(), "only one saved remaining slot is replayed from authority");
            assertCurrentUieProviders(fixture, replayed);
            assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty());
            fixture.tickEnd();
            var acknowledged = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertEquals(2, acknowledged.bodyReferenceAcknowledgement().orElseThrow().stateSequence());
        }
    }

    @Test void partialPredictionTeleportSetterTailIsQuarantinedUntilUnwindAndRecoversOnce() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, predictionSnapshot(scope, 1, 8), Optional.empty()));
            double beforeMotion = fixture.player.motionX;
            var tail = new AxisAlignedBB(39, 68, 7, 41, 72, 9);
            fixture.player.boundsCallback = () -> {
                PlayerPhysicsClient.nativeCorrection(fixture.handler, 51);
                PlayerPhysicsClient.nativeCorrection(fixture.handler, 52);
                fixture.player.boundsTail = tail;
                assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty());
            };
            fixture.tickEnd();
            assertEquals(tail, fixture.player.getEntityBoundingBox(), "old setter tail returned before physical cleanup");
            assertEquals(beforeMotion, fixture.player.motionX, "old prediction remaining phase never overwrites motion after TP");
            assertFenced(fixture.player);
            for (int tick = 0; tick < 20; tick++) fixture.tickEnd();
            var recovery = fixture.readActualPhysicsPacket(Kind.RECOVER, scope);
            assertEquals(1, recovery.offerSequence());
            assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty());
            for (int tick = 0; tick < 20; tick++) fixture.tickEnd();
            assertEquals(recovery, fixture.readActualPhysicsPacket(Kind.RECOVER, scope), "one recovery identity is retried, not recreated");
        }
    }

    @Test void nestedSuccessfulStateCannotAuthorizeOldTailAndMissingBoundaryRebases() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var authority = predictionSnapshot(scope, 1, 8);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, authority, Optional.empty()));
            var newer = snapshot(scope, 3, 46);
            fixture.player.boundsCallback = () -> {
                fixture.deliver(state(Kind.STATE, 2, snapshot(scope, 2, 45)));
                fixture.deliver(state(Kind.STATE, 3, newer));
                PlayerPhysicsClient.nativeCorrection(fixture.handler, 53); // clears stamp; serial must not have an ABA reset.
                fixture.player.boundsTail = new AxisAlignedBB(18, 68, 7, 22, 72, 9);
            };
            fixture.tickEnd();
            assertEquals(newer.worldVelocity().x() * .05, fixture.player.motionX,
                    "old mutation cannot continue remaining after new successful state");
            assertFenced(fixture.player);
            for (int tick = 0; tick < 20; tick++) fixture.tickEnd();
            fixture.readActualPhysicsPacket(Kind.RECOVER, scope);
            var next = fixture.scope(2); fixture.offerAndApply(next); fixture.player.boundsTail = null;
            var restarted = predictionSnapshot(next, 1, 8);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, restarted, Optional.empty()));
            fixture.tickEnd();
            var beforeRetirementInput = fixture.readActualPhysicsPacket(Kind.INPUT, next);
            fixture.deliver(predictionUnavailablePacket(2, restarted));
            assertFenced(fixture.player);
            for (int tick = 0; tick < 20; tick++) fixture.tickEnd();
            var retiredRecovery = fixture.readActualPhysicsPacket(Kind.RECOVER, next);
            // A typed retirement needs a real handshake; the retired bootstrap never revives in-place.
            var recoveredScope = fixture.scope(3);
            fixture.offerAndApply(recoveredScope);
            var recoveredAuthority = predictionSnapshot(recoveredScope, 1, 8);
            var recoveredOwned = predictionPacket(Kind.OWNED, 1, recoveredAuthority, Optional.empty());
            var frame = ((PlayerBodyShape.Cardinal) recoveredAuthority.placement().shape()).frame();
            var environmentReference = new PlayerBodyReference.State(Optional.empty(),
                    new PlayerBodyReference.Effective(PlayerBodyReference.Source.ENVIRONMENT, frame,
                            frame.attitude().basisZ()), PlayerBodyReference.Transition.STABLE,
                    Optional.empty(), Optional.empty());
            fixture.deliver(withReference(recoveredOwned, environmentReference));
            assertEquals(recoveredAuthority, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();
            var recoveredInput = fixture.readActualPhysicsPacket(Kind.INPUT, recoveredScope);
            assertTrue(recoveredInput.predictionInput().orElseThrow().epoch()
                    > beforeRetirementInput.predictionInput().orElseThrow().epoch());
            assertNotEquals(retiredRecovery.scope(), recoveredInput.scope());
            for (int tick = 0; tick < 3; tick++) {
                fixture.deliver(withReference(predictionPacket(Kind.STATE, tick + 2, recoveredAuthority,
                        Optional.empty()), environmentReference));
                assertCurrentUieProviders(fixture,
                        PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
                fixture.tickEnd();
                var input = fixture.readActualPhysicsPacket(Kind.INPUT, recoveredScope);
                assertEquals(recoveredInput.predictionInput().orElseThrow().epoch(),
                        input.predictionInput().orElseThrow().epoch());
                assertEquals(recoveredInput.predictionInput().orElseThrow().bootstrap(),
                        input.predictionInput().orElseThrow().bootstrap());
            }
        }
    }

    private static Packet withReference(Packet packet, PlayerBodyReference.State reference) {
        return new Packet(packet.kind(), packet.scope(), packet.sequence(), packet.offerSequence(), packet.application(),
                packet.snapshot(), packet.controls(), packet.nativeTeleportId(), packet.bodyReferenceReceipt(),
                packet.bodyReferenceAcknowledgement(), Optional.of(reference), packet.predictionInput(),
                packet.predictionBoundary(), packet.predictionSeed(), packet.predictionPhase());
    }

    @Test void ordinaryTeleportWithNoPartialPredictionKeepsCustodyWithoutRecovery() {
        deadDrainGetterCannotFenceSuccessor();
        nestedNativeDeathRunsOriginalsOnce();
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(scope, 1, 8)));
            PlayerPhysicsClient.nativeCorrection(fixture.handler, 61);
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty());
            for (int tick = 0; tick < 20; tick++) {
                fixture.tickEnd();
                assertEquals(Kind.INPUT, fixture.readActualPhysicsPacket(Kind.INPUT, scope).kind());
            }
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
        }
    }

    private static void nestedNativeDeathRunsOriginalsOnce() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(scope, 1, 8)));
            int[] originals = {0, 0};
            assertDoesNotThrow(() -> PlayerPhysicsClient.runNativePose(fixture.player, () -> {
                originals[0]++;
                PlayerPhysicsClient.runNativePose(fixture.player, () -> {
                    originals[1]++;
                    fixture.player.setHealth(0);
                    fixture.player.isDead = true;
                });
                fixture.player.setPosition(91, 40, 12);
            }));
            assertArrayEquals(new int[]{1, 1}, originals);
            assertEquals(91, fixture.player.posX, "neither old mirror can overwrite the outer native tail");
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty());
            fixture.tickEnd();
        }
    }
    private static void deadDrainGetterCannotFenceSuccessor() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.deliver(state(Kind.OWNED, 1, snapshot(scope, 1, 8)));
            var successor = snapshot(scope, 2, 43);
            int[] reads = {0};
            int[] originals = {0};
            PlayerPhysicsClient.runNativePose(fixture.player, () -> {
                originals[0]++;
                fixture.player.setHealth(0);
                fixture.player.aliveCallback = () -> {
                    if (++reads[0] == 3) {
                        fixture.player.aliveCallback = null;
                        fixture.player.setHealth(20);
                        fixture.deliver(state(Kind.STATE, 2, successor));
                    }
                };
            });
            assertEquals(1, originals[0]);
            assertEquals(3, reads[0], "restore, defer and drain use the actual actor liveness getter");
            assertEquals(successor.placement().transport().x(), fixture.player.posX);
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
            assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player),
                    "the old dead-drain task cannot mark a new successful installation broken");
            fixture.tickEnd();
            assertEquals(Kind.INPUT, fixture.readActualPhysicsPacket(Kind.INPUT, scope).kind());
        }
    }
    @Test void predictionPartialThrowCleanupCallbackCannotResetSuccessorOwner() {
        try (var fixture = new Fixture(); var failures = new FailureLogCapture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, predictionSnapshot(scope, 1, 8), Optional.empty()));
            var successorScope = fixture.scope(2);
            var successor = snapshot(successorScope, 1, 55);
            fixture.player.restorationCallback = () -> {
                fixture.offerAndApply(successorScope);
                fixture.deliver(state(Kind.OWNED, 1, successor));
            };
            var cause = new IllegalStateException("prediction setter partial throw before BoundsApplied exists");
            fixture.player.boundsFailure = cause;
            assertSame(cause, assertThrows(IllegalStateException.class, fixture::tickEnd));
            assertTrue(failures.contains(cause));
            assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty(),
                    "a successor installed inside old cleanup is quarantined before setter tail resumes");
            assertEquals(successor.worldVelocity().x() * .05, fixture.player.motionX);
            fixture.tickEnd();
            assertFenced(fixture.player);
            for (int tick = 1; tick < 20; tick++) fixture.tickEnd();
            var request = fixture.readActualPhysicsPacket(Kind.RECOVER, successorScope);
            assertEquals(successorScope, request.scope());
        }
    }

    @Test void endpointUnsupportedLandingSuspendsOnlyPredictionAndKeepsCanonicalOrdinaryInput() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            var authority = endpointAuthority(scope); prepareEndpoint(fixture, net.minecraft.init.Blocks.BEDROCK);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, authority, Optional.empty()));
            float gait = fixture.player.limbSwing, amount = fixture.player.limbSwingAmount;
            assertDoesNotThrow(fixture::tickEnd);
            assertTrue(((EndpointWorld) fixture.world).endpointReads > 0);
            var input = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            var request = assertSuspendedOrdinary(fixture, authority, input);
            assertEquals(PredictionRefusalStage.ENDPOINT, request.refusal().orElseThrow().stage());
            assertEquals(CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT,
                    request.refusal().orElseThrow().reason());
            assertEquals(gait, fixture.player.limbSwing); assertEquals(amount, fixture.player.limbSwingAmount);
            // More than two original recovery windows: custody/controls remain, no whole-owner RECOVER.
            try(var routing=routedControls(1,false)) {
            for (int tick = 0; tick < 45; tick++) {
                fixture.tickStart(); fixture.tickEnd();
                var ordinary = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
                assertEquals(request, assertSuspendedOrdinary(fixture, authority, ordinary));
                assertTrue(ordinary.controls().orElseThrow().translation().lengthSquared() > 0);
            }
            }
        }
    }

    @Test void sampleRefusalAfterCommittedPredictionRestoresCanonicalWithoutGaitOrFailedMetadata() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            prepareEndpoint(fixture, net.minecraft.init.Blocks.STONE);
            var authority = endpointAuthority(scope);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, authority, Optional.empty()));
            fixture.tickEnd();
            assertTrue(fixture.readActualPhysicsPacket(Kind.INPUT, scope).predictionInput().isPresent());
            assertNotEquals(authority.placement(), PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow().placement());
            prepareEndpoint(fixture, net.minecraft.init.Blocks.BEDROCK);
            long priorViewTick=PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow().clientTick();
            float gait = fixture.player.limbSwing, amount = fixture.player.limbSwingAmount;
            fixture.tickStart(); fixture.tickEnd();
            assertTrue(PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow().clientTick()>priorViewTick);
            var request = assertSuspendedOrdinary(fixture, authority, fixture.readActualPhysicsPacket(Kind.INPUT, scope));
            assertEquals(PredictionRefusalStage.PREFRICTION, request.refusal().orElseThrow().stage());
            assertEquals(gait, fixture.player.limbSwing); assertEquals(amount, fixture.player.limbSwingAmount);
        }
    }

    @Test void replayRefusalAfterStateBInstallationRestoresLatestBAndAcknowledgesItsExactState() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var a = predictionSnapshot(scope, 1, 8);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, a, Optional.empty()));
            fixture.tickEnd(); var first = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            long priorViewTick=PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow().clientTick();
            fixture.tickStart(); fixture.tickEnd(); fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertTrue(PlayerPhysicsClient.currentTangentView(fixture.player).orElseThrow().clientTick()>priorViewTick);
            var identity = first.predictionInput().orElseThrow();
            var b = predictionSnapshot(scope, 2, 9);
            var boundary = new PredictionBoundary(identity.epoch(), 1, 10, 11, identity.firstClientTick(),
                    first.sequence(), a.revision(), first.sequence(), 0, JumpDecision.none(), StepOutcome.APPLIED);
            var stateB = predictionPacket(Kind.STATE, 2, b, Optional.of(boundary));
            stateB = new Packet(stateB.kind(), scope, stateB.sequence(), stateB.offerSequence(), stateB.application(),
                    stateB.snapshot(), stateB.controls(), stateB.nativeTeleportId(), stateB.bodyReferenceReceipt(),
                    stateB.bodyReferenceAcknowledgement(), stateB.bodyReferenceState(), stateB.predictionInput(),
                    stateB.predictionBoundary(), Optional.of(new CardinalPredictionSeed.Unsupported(
                            CardinalPredictionSeed.Reason.NATIVE_BLOCK_EFFECT)), stateB.predictionPhase());
            fixture.deliver(stateB);
            assertCanonicalSuspension(fixture, b);
            fixture.tickEnd();
            var input = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            var request = assertSuspendedOrdinary(fixture, b, input);
            assertEquals(PredictionRefusalStage.REPLAY, request.refusal().orElseThrow().stage());
            assertEquals(new BodyReferenceAcknowledgement(2, b.revision()), input.bodyReferenceAcknowledgement().orElseThrow());
        }
    }

    @Test void rearmSamplingGetterCannotSendOldEndAcrossStateScopeActorOrClear() {
        for (int change = 0; change < 4; change++) try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            prepareEndpoint(fixture, net.minecraft.init.Blocks.BEDROCK);
            var a = endpointAuthority(scope);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, a, Optional.empty()));
            fixture.tickEnd();
            var suspend = fixture.readActualPhysicsPacket(Kind.INPUT, scope).predictionRequest().orElseThrow();
            prepareEndpoint(fixture, net.minecraft.init.Blocks.STONE);
            fixture.deliver(suspensionState(2, a, suspend.requestId(), suspend.bootstrap(), suspend.origin()));
            var successorScope = change == 0 ? scope : fixture.scope(2);
            var successor = snapshot(successorScope, 2, 43);
            int variant = change; int[] calls = {0};
            fixture.player.sampleSpeedCallback = () -> {
                calls[0]++;
                if (variant == 0) fixture.deliver(state(Kind.STATE, 3, successor));
                else {
                    MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(fixture.world));
                    if (variant == 2) {
                        fixture.player = new CallbackPlayer(fixture.mc, fixture.world, fixture.handler);
                        fixture.mc.player = fixture.player;
                    }
                    if (variant != 3) {
                        fixture.offerAndApply(successorScope);
                        fixture.deliver(state(Kind.OWNED, 3, successor));
                    }
                }
            };
            fixture.tickEnd();
            assertEquals(1, calls[0], "actual rearm sampler getter must execute");
            assertTrue(fixture.collectPhysicsPackets().isEmpty(), "old END emits zero INPUT after any getter lifetime change");
            if (variant == 3) {
                assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
                fixture.offerAndApply(successorScope);
                fixture.deliver(state(Kind.OWNED, 3, successor));
            }
            assertEquals(successor, PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();
            var next = fixture.readActualPhysicsPacket(Kind.INPUT, successorScope);
            assertTrue(next.predictionInput().isEmpty(), "successor END independently sends ordinary controls");
        }
    }

    @Test void initialMobilityAdoptionRejectsHealthZeroProofAndAcceptsExactCanonicalRetransmission() {
        try (var fixture = new Fixture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            var cardinal = predictionSnapshot(scope, 1, 8);
            var a = new PlayerPhysicalSnapshot(scope, 1, new PlayerBodyPlacement(cardinal.placement().center(),
                    cardinal.placement().attitude(), new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                    cardinal.worldVelocity(), cardinal.contacts());
            fixture.deliver(state(Kind.OWNED, 1, a));
            var c = new PlayerPhysicalSnapshot(scope, 2,
                    new PlayerBodyPlacement(a.placement().center().add(new Vec3d(0, 0, .12)), a.placement().attitude(), a.placement().shape()),
                    a.worldVelocity(), a.contacts());
            fixture.deliver(state(Kind.STATE, 2, c));
            assertEquals(c, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(fixture.player, c));
            var rendered = PlayerPhysicsClient.acceptedEyePosition(fixture.player, .62F).orElseThrow();
            assertEquals(a.placement().eye().add(c.placement().eye().subtract(a.placement().eye()).multiply((double).62F)), rendered);
            fixture.tickStart();
            var afterStart = PlayerPhysicsClient.acceptedEyePosition(fixture.player, .28F).orElseThrow();
            assertEquals(c.placement().eye(), afterStart,
                    "real START without a Free install must finish its accepted eye span");
            assertTrue(afterStart.z() >= rendered.z());
            assertEquals(afterStart, PlayerPhysicsClient.acceptedEyePosition(fixture.player, .28F).orElseThrow());
            var b = new PlayerPhysicalSnapshot(scope, 3,
                    new PlayerBodyPlacement(c.placement().center().add(new Vec3d(0, 0, .12)), c.placement().attitude(), c.placement().shape()),
                    c.worldVelocity(), c.contacts());
            var d = new PlayerPhysicalSnapshot(scope, 4,
                    new PlayerBodyPlacement(b.placement().center().add(new Vec3d(0, 0, .12)), b.placement().attitude(), b.placement().shape()),
                    b.worldVelocity(), b.contacts());
            fixture.enqueue(state(Kind.STATE, 3, b)); fixture.enqueue(state(Kind.STATE, 4, d)); fixture.tickStart();
            assertEquals(c.placement().eye(), PlayerPhysicsClient.acceptedEyePosition(fixture.player, 0).orElseThrow());
            assertEquals(d.placement().eye(), PlayerPhysicsClient.acceptedEyePosition(fixture.player, 1).orElseThrow());
            fixture.tickStart();
            assertEquals(d.placement().eye(), PlayerPhysicsClient.acceptedEyePosition(fixture.player, .28F).orElseThrow());
        }
        try(var fixture=new Fixture()) {
            var scope=fixture.scope(1);fixture.offerAndApply(scope);
            var cardinal=predictionSnapshot(scope,1,8);
            var free=new PlayerPhysicalSnapshot(scope,1,new PlayerBodyPlacement(cardinal.placement().center(),
                    cardinal.placement().attitude(),new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                    cardinal.worldVelocity(),cardinal.contacts());
            fixture.deliver(new Packet(Kind.OWNED,scope,1,1,Optional.empty(),Optional.of(free),Optional.empty()));
            var canonical=PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow();
            var notice=new Packet(Kind.STATE,scope,2,1,Optional.empty(),Optional.of(free),Optional.empty(),OptionalInt.empty(),
                    Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                    Optional.of(new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE)),
                    Optional.of(new PredictionSuspended(1,new PredictionBootstrap(1,0),71,PredictionSuspensionOrigin.SERVER_MOBILITY)));
            notice=suspensionWithReceipt(notice,700);
            int[] aliveCalls={0},boundsCalls={0};
            fixture.player.aliveCallback=()->{
                if(PlayerPhysicsClient.bodyReferenceStatus().map(status->status.requestId()!=700).orElse(true)) return;
                fixture.player.aliveCallback=null;aliveCalls[0]++;
                fixture.player.boundsReadCallback=()->{
                    fixture.player.boundsReadCallback=null;boundsCalls[0]++;
                    fixture.player.setHealth(0);assertFalse(fixture.player.isDead);
                };
            };
            try {fixture.deliver(notice);} finally {fixture.player.aliveCallback=null;fixture.player.boundsReadCallback=null;}
            assertEquals(1,aliveCalls[0]);assertEquals(1,boundsCalls[0]);assertEquals(0,fixture.player.getHealth());
            fixture.player.setHealth(20);
            fixture.tickEnd();var ordinary=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
            assertTrue(ordinary.predictionInput().isEmpty());assertTrue(ordinary.predictionRequest().isEmpty(),
                    "health-zero geometry proof cannot adopt the mobility transaction even when isDead remains false");
            // A new legal wire sequence decodes the same snapshot revision/value and receipt transaction.
            var retransmission=new Packet(notice.kind(),notice.scope(),3,notice.offerSequence(),notice.application(),
                    notice.snapshot(),notice.controls(),notice.nativeTeleportId(),notice.bodyReferenceReceipt(),
                    notice.bodyReferenceAcknowledgement(),notice.bodyReferenceState(),notice.predictionInput(),
                    notice.predictionBoundary(),notice.predictionSeed(),notice.predictionPhase(),notice.predictionRequest(),notice.mobilityState());
            fixture.deliver(retransmission);
            assertSame(canonical,PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
            fixture.tickEnd();var resumed=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
            assertEquals(71,resumed.predictionRequest().orElseThrow().requestId());
            assertEquals(PredictionSuspensionOrigin.SERVER_MOBILITY,resumed.predictionRequest().orElseThrow().origin());
            assertEquals(PredictionRequestAction.SUSPEND,resumed.predictionRequest().orElseThrow().action());
            assertFalse(ClientGravityTicker.isPersonalZeroGActive(fixture.player));
            fixture.player.rotationYaw = 37; fixture.player.rotationPitch = -23;
            assertCanonicalSuspension(fixture,free);
        }
    }

    @Test void mobilityFreeToWalkRearmsSameCustodyWithAndWithoutPreviousPrediction() {
        for (boolean previousPrediction : new boolean[]{false,true}) {
            try (var fixture=new Fixture()) {
                var scope=fixture.scope(1); fixture.offerAndApply(scope);
                fixture.world.getChunkProvider().loadChunk(0,0);
                var cardinal=predictionSnapshot(scope,1,8);
                var free=new PlayerPhysicalSnapshot(scope,2,new PlayerBodyPlacement(cardinal.placement().center(),
                        cardinal.placement().attitude(),new PlayerBodyShape.FreeCapsule(cardinal.placement().shape().dimensions())),
                        new Vec3d(0,0,0),cardinal.contacts());
                long sequence;
                if (previousPrediction) {
                    fixture.deliver(predictionPacket(Kind.OWNED,1,cardinal,Optional.empty()));
                    fixture.tickEnd();fixture.readActualPhysicsPacket(Kind.INPUT,scope);sequence=2;
                } else sequence=1;
                var bootstrap=new PredictionBootstrap(1,0);
                var paused=new Packet(previousPrediction ? Kind.STATE : Kind.OWNED,scope,sequence,1,
                        Optional.empty(),Optional.of(free),Optional.empty(),OptionalInt.empty(),Optional.empty(),Optional.empty(),
                        Optional.empty(),Optional.empty(),Optional.empty(),
                        Optional.of(new CardinalPredictionSeed.Unsupported(CardinalPredictionSeed.Reason.UNSUPPORTED_STATE)),
                        Optional.of(new PredictionSuspended(free.revision(),bootstrap,71,PredictionSuspensionOrigin.SERVER_MOBILITY)),
                        Optional.empty(),Optional.of(new wi.unigrav.core.player.PlayerMobilityIntent.State(1,
                        wi.unigrav.core.player.PlayerMobilityIntent.Mode.FLIGHT,wi.unigrav.core.player.PlayerMobilityIntent.Hover.OFF,
                        wi.unigrav.core.player.PlayerMobilityIntent.EffectiveMode.FREE,wi.unigrav.core.player.PlayerMobilityIntent.HoverStatus.OFF,
                        wi.unigrav.core.player.PlayerMobilityIntent.Reason.NONE,false,0)));
                fixture.deliver(paused);
                assertFalse(ClientGravityTicker.isPersonalZeroGActive(fixture.player));
                fixture.player.rotationYaw = 37; fixture.player.rotationPitch = -23;
                assertCanonicalSuspension(fixture,free);
                fixture.tickEnd();var ordinary=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
                assertTrue(ordinary.predictionInput().isEmpty());assertEquals(PredictionRequestAction.SUSPEND,ordinary.predictionRequest().orElseThrow().action());
                var walk=predictionSnapshot(scope,3,8);
                var walkReceipt=suspensionState(sequence+1,walk,71,bootstrap,PredictionSuspensionOrigin.SERVER_MOBILITY);
                fixture.deliver(new Packet(walkReceipt.kind(),walkReceipt.scope(),walkReceipt.sequence(),walkReceipt.offerSequence(),
                        walkReceipt.application(),walkReceipt.snapshot(),walkReceipt.controls(),walkReceipt.nativeTeleportId(),
                        walkReceipt.bodyReferenceReceipt(),walkReceipt.bodyReferenceAcknowledgement(),walkReceipt.bodyReferenceState(),
                        walkReceipt.predictionInput(),walkReceipt.predictionBoundary(),walkReceipt.predictionSeed(),walkReceipt.predictionPhase(),
                        walkReceipt.predictionRequest(),Optional.of(new wi.unigrav.core.player.PlayerMobilityIntent.State(2,
                        wi.unigrav.core.player.PlayerMobilityIntent.Mode.WALK,wi.unigrav.core.player.PlayerMobilityIntent.Hover.OFF,
                        wi.unigrav.core.player.PlayerMobilityIntent.EffectiveMode.WALK,wi.unigrav.core.player.PlayerMobilityIntent.HoverStatus.OFF,
                        wi.unigrav.core.player.PlayerMobilityIntent.Reason.NONE,false,0))));
                assertCanonicalSuspension(fixture,walk);
                // The existing bounded probe cadence is retained; no extra wait budget is introduced.
                Packet rearm=null;
                for(int tick=0;tick<=20;tick++) {
                    fixture.tickStart();fixture.tickEnd();var next=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
                    assertTrue(next.predictionInput().isEmpty());
                    if(next.predictionRequest().orElseThrow().action()==PredictionRequestAction.REARM) {rearm=next;break;}
                }
                assertNotNull(rearm);var first=rearmAndAnchor(fixture,walk,rearm.predictionRequest().orElseThrow(),sequence+2);
                assertEquals(scope,first.scope());assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
                fixture.deliver(paused);fixture.tickEnd();var afterLate=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
                assertEquals(first.predictionInput().orElseThrow().epoch(),afterLate.predictionInput().orElseThrow().epoch());
                assertTrue(afterLate.predictionRequest().isEmpty());
            }
        }
        // A legitimate initial Cardinal publication may already carry a reference pause,
        // before this client has any prediction owner. It still needs the exact install proof.
        try(var fixture=new Fixture()) {
            var scope=fixture.scope(1);fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0,0);
            var cardinal=predictionSnapshot(scope,1,8);
            var paused=suspensionState(1,cardinal,73,new PredictionBootstrap(1,0),PredictionSuspensionOrigin.SERVER_REFERENCE);
            fixture.deliver(new Packet(Kind.OWNED,paused.scope(),paused.sequence(),paused.offerSequence(),
                    paused.application(),paused.snapshot(),paused.controls(),paused.nativeTeleportId(),paused.bodyReferenceReceipt(),
                    paused.bodyReferenceAcknowledgement(),paused.bodyReferenceState(),paused.predictionInput(),
                    paused.predictionBoundary(),paused.predictionSeed(),paused.predictionPhase(),paused.predictionRequest(),paused.mobilityState()));
            assertCanonicalSuspension(fixture,cardinal);
            fixture.tickEnd();var ordinary=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
            assertEquals(PredictionSuspensionOrigin.SERVER_REFERENCE,ordinary.predictionRequest().orElseThrow().origin());
            // The published server pause is already acknowledged; its Ready checkpoint permits the first bounded probe.
            assertEquals(PredictionRequestAction.REARM,ordinary.predictionRequest().orElseThrow().action());
            assertEquals(paused.predictionPhase().map(value -> ((PredictionSuspended)value).bootstrap()).orElseThrow(),
                    ordinary.predictionRequest().orElseThrow().bootstrap());
            assertEquals(73,ordinary.predictionRequest().orElseThrow().requestId());
            assertTrue(ordinary.predictionInput().isEmpty());
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        }
    }

    @Test void serverUnmappedSuspensionKeepsClientCanonicalAndRearmsSameScopeWithRealFirstBoundary() {
        var mc = Minecraft.getMinecraft();
        boolean previousFocus = mc.inGameHasFocus;
        var previousScreen = mc.currentScreen;
        try (var fixture = new Fixture(); var routing=routedControls(0,true)) {
            mc.inGameHasFocus = true;
            mc.currentScreen = null;
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            fixture.world.getChunkProvider().loadChunk(0, 0);
            var a = predictionSnapshot(scope, 1, 8);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, a, Optional.empty()));
            fixture.tickEnd(); var first = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertTrue(first.controls().orElseThrow().jump(),"actual routed wire carries held jump");
            var old = first.predictionInput().orElseThrow();
            assertTrue(old.jumpEdgeId()>0,"first held press has an actual edge");
            var boundary = new PredictionBoundary(old.epoch(), 1, 10, 11, old.firstClientTick(), first.sequence(),
                    a.revision(), first.sequence(), 0, JumpDecision.none(), StepOutcome.APPLIED);
            fixture.deliver(predictionPacket(Kind.STATE, 2, a, Optional.of(boundary)));
            var notification = suspensionState(3, a, 71, old.bootstrap().orElseThrow(), PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS);
            fixture.deliver(notification);
            assertCanonicalSuspension(fixture, a);
            fixture.tickEnd(); var rearm = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertTrue(rearm.predictionInput().isEmpty());
            assertEquals(PredictionRequestAction.REARM, rearm.predictionRequest().orElseThrow().action());
            assertEquals(71, rearm.predictionRequest().orElseThrow().requestId());
            var c = rearmAndAnchor(fixture, a, rearm.predictionRequest().orElseThrow(), 4);
            assertNotEquals(old.epoch(), c.predictionInput().orElseThrow().epoch());
            assertEquals(0, c.predictionInput().orElseThrow().jumpEdgeId(), "held jump is inherited, not replayed as a new edge");
            fixture.deliver(notification); // Delayed real B notification retains its original wire sequence.
            fixture.tickEnd(); var afterLateB = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertEquals(c.predictionInput().orElseThrow().epoch(), afterLateB.predictionInput().orElseThrow().epoch());
            assertTrue(afterLateB.predictionRequest().isEmpty());
            assertTrue(afterLateB.controls().orElseThrow().mobility().held());
            assertFalse(afterLateB.controls().orElseThrow().jump(), "the sustained physical press cannot replay its short jump");
            assertEquals(first.controls().orElseThrow().mobility().pressId(),
                    afterLateB.controls().orElseThrow().mobility().pressId());
            mc.inGameHasFocus = false;
            fixture.tickStart();fixture.tickEnd();
            var released = fixture.readActualPhysicsPacket(Kind.INPUT, scope).controls().orElseThrow();
            assertFalse(released.jump());
            assertFalse(released.mobility().held());
            assertFalse(released.mobility().focused());
            mc.inGameHasFocus = true;
            fixture.tickStart();fixture.tickEnd();
            var refocused = fixture.readActualPhysicsPacket(Kind.INPUT, scope).controls().orElseThrow();
            assertTrue(refocused.jump());
            assertTrue(refocused.mobility().held());
            assertTrue(refocused.mobility().pressId() > released.mobility().pressId());
        } finally {
            mc.inGameHasFocus = previousFocus;
            mc.currentScreen = previousScreen;
        }
    }

    @Test void inFlightServerSuspensionAdoptsLocalClientTransactionWithoutRetiringRestartedC() {
        try (var fixture = new Fixture();var commits=new AdoptionCommitCapture()) {
            var scope = fixture.scope(1); fixture.offerAndApply(scope);
            prepareEndpoint(fixture, net.minecraft.init.Blocks.BEDROCK);
            var a = endpointAuthority(scope);
            fixture.deliver(predictionPacket(Kind.OWNED, 1, a, Optional.empty()));
            // Server's B notification exists before the local refusal, but arrives afterwards.
            var notification = suspensionState(2, a, 89, new PredictionBootstrap(1, 0), PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS);
            fixture.tickEnd(); var local = fixture.readActualPhysicsPacket(Kind.INPUT, scope).predictionRequest().orElseThrow();
            assertEquals(PredictionSuspensionOrigin.CLIENT_REFUSAL, local.origin());
            assertNotEquals(89, local.requestId());
            prepareEndpoint(fixture, net.minecraft.init.Blocks.STONE);
            var retainedCanonical=PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow();
            fixture.deliver(notification);
            assertSame(retainedCanonical,PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow(),
                    "decoded same-value STATE must retain the actual canonical object");
            assertCanonicalSuspension(fixture, a);
            assertEquals(1,commits.count(scope,2),"normal cross proves the DEBUG commit observer is active");
            fixture.tickEnd(); var adopted = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertTrue(adopted.predictionInput().isEmpty());
            var request = adopted.predictionRequest().orElseThrow();
            assertEquals(PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS, request.origin());
            assertEquals(89, request.requestId()); assertEquals(PredictionRequestAction.REARM, request.action());
            var c = rearmAndAnchor(fixture, a, request, 3);
            fixture.deliver(notification);
            fixture.tickEnd(); var afterLateB = fixture.readActualPhysicsPacket(Kind.INPUT, scope);
            assertEquals(c.predictionInput().orElseThrow().epoch(), afterLateB.predictionInput().orElseThrow().epoch());
            assertEquals(c.predictionInput().orElseThrow().bootstrap(), afterLateB.predictionInput().orElseThrow().bootstrap());
            assertTrue(afterLateB.predictionRequest().isEmpty());
        }
    }

    @Test void higherRevisionServerSuspensionInstallsCanonicalBAndRearmsOnlyItsPublishedOwner() {
        try(var fixture=new Fixture();var commits=new AdoptionCommitCapture()) {
            var scope=fixture.scope(1);fixture.offerAndApply(scope);prepareEndpoint(fixture,net.minecraft.init.Blocks.BEDROCK);
            var a=endpointAuthority(scope);fixture.deliver(predictionPacket(Kind.OWNED,1,a,Optional.empty()));
            fixture.tickEnd();var local=fixture.readActualPhysicsPacket(Kind.INPUT,scope).predictionRequest().orElseThrow();
            assertEquals(PredictionSuspensionOrigin.CLIENT_REFUSAL,local.origin());
            var canonicalA=PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow();
            prepareEndpoint(fixture,net.minecraft.init.Blocks.STONE);
            var b=new PlayerPhysicalSnapshot(scope,2,a.placement(),a.worldVelocity(),a.contacts());
            var notification=suspensionState(2,b,89,new PredictionBootstrap(1,0),PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS);
            fixture.deliver(notification);
            assertCanonicalSuspension(fixture,b);
            assertNotSame(canonicalA,PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow(),
                    "higher revision actually installs canonical B rather than retaining retransmission A");
            assertEquals(1,commits.count(scope,2),"only the published B installation commits adoption");
            fixture.tickEnd();var input=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
            assertEquals(new BodyReferenceAcknowledgement(2,b.revision()),input.bodyReferenceAcknowledgement().orElseThrow());
            assertTrue(input.predictionInput().isEmpty());
            var request=input.predictionRequest().orElseThrow();assertEquals(89,request.requestId());
            assertEquals(PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS,request.origin());
            assertEquals(PredictionRequestAction.REARM,request.action());
            var c=rearmAndAnchor(fixture,b,request,3);
            assertTrue(c.predictionInput().orElseThrow().bootstrap().orElseThrow().generation()>request.bootstrap().generation());
        }
    }

    private static Packet suspensionWithReceipt(Packet packet,long requestId) {
        var receipt=new PlayerPhysicsProtocol.BodyReferenceReceipt(requestId,PlayerPhysicsProtocol.BodyReferenceDecision.REJECTED,
                0,Optional.empty(),Optional.empty(),Optional.of(PlayerPhysicsProtocol.BodyReferenceReason.WORLD_UNAVAILABLE));
        return new Packet(packet.kind(),packet.scope(),packet.sequence(),packet.offerSequence(),packet.application(),packet.snapshot(),
                packet.controls(),packet.nativeTeleportId(),Optional.of(receipt),packet.bodyReferenceAcknowledgement(),
                packet.bodyReferenceState(),packet.predictionInput(),packet.predictionBoundary(),packet.predictionSeed(),
                packet.predictionPhase(),packet.predictionRequest());
    }

    @Test void publishedSuspensionQueuedStateRemainsFifoAndCannotReplaceTransaction() {
        try(var fixture=new Fixture()) {
            var scope=fixture.scope(1);fixture.offerAndApply(scope);prepareEndpoint(fixture,net.minecraft.init.Blocks.BEDROCK);
            var a=endpointAuthority(scope);fixture.deliver(predictionPacket(Kind.OWNED,1,a,Optional.empty()));
            fixture.tickEnd();fixture.readActualPhysicsPacket(Kind.INPUT,scope);prepareEndpoint(fixture,net.minecraft.init.Blocks.STONE);
            int[] calls={0};
            fixture.player.aliveCallback=()->{
                if(PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().requestId()!=700)return;
                fixture.player.aliveCallback=null;calls[0]++;
                fixture.deliver(suspensionWithReceipt(suspensionState(3,a,90,new PredictionBootstrap(1,0),
                        PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS),701));
                assertEquals(700,PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().requestId(),"active drain cannot reenter its decode");
            };
            try {fixture.deliver(suspensionWithReceipt(suspensionState(2,a,89,new PredictionBootstrap(1,0),
                    PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS),700));}
            finally {fixture.player.aliveCallback=null;}
            assertEquals(1,calls[0]);assertEquals(701,PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().requestId());
            fixture.tickEnd();var input=fixture.readActualPhysicsPacket(Kind.INPUT,scope);
            var adopted=input.predictionRequest().orElseThrow();assertEquals(89,adopted.requestId());
            assertEquals(PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS,adopted.origin());
            assertEquals(PredictionRequestAction.REARM,adopted.action());rearmAndAnchor(fixture,a,adopted,4);
        }
    }

    @Test void synchronousSuspensionProofTeleportCannotCommitOldAdoption() {
        suspensionProofLifecycle(false,false);
    }
    @Test void synchronousSuspensionProofDeathCannotCommitOldAdoption() {
        suspensionProofLifecycle(true,true);
        suspensionProofLifecycle(true,false);
    }
    private static void suspensionProofLifecycle(boolean death,boolean markDead) {
        try(var fixture=new Fixture();var commits=new AdoptionCommitCapture()) {
            var scope=fixture.scope(1);fixture.offerAndApply(scope);prepareEndpoint(fixture,net.minecraft.init.Blocks.BEDROCK);
            var a=endpointAuthority(scope);fixture.deliver(predictionPacket(Kind.OWNED,1,a,Optional.empty()));
            fixture.tickEnd();fixture.readActualPhysicsPacket(Kind.INPUT,scope);prepareEndpoint(fixture,net.minecraft.init.Blocks.STONE);
            int[] aliveEntries={0},actions={0};
            fixture.player.aliveCallback=()->{
                if(PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().requestId()!=700)return;
                fixture.player.aliveCallback=null;aliveEntries[0]++;
                fixture.player.boundsReadCallback=()->{
                    fixture.player.boundsReadCallback=null;actions[0]++;
                    if(death){fixture.player.setHealth(0);
                        if(markDead)fixture.player.isDead=true;
                        assertEquals(0,fixture.player.getHealth());assertEquals(markDead,fixture.player.isDead);}
                    else PlayerPhysicsClient.nativeCorrection(fixture.handler,73);
                };
            };
            try {fixture.deliver(suspensionWithReceipt(suspensionState(2,a,89,new PredictionBootstrap(1,0),
                    PredictionSuspensionOrigin.SERVER_UNMAPPED_CONTROLS),700));}
            finally {fixture.player.aliveCallback=null;fixture.player.boundsReadCallback=null;}
            assertEquals(1,aliveEntries[0]);assertEquals(1,actions[0]);
            assertEquals(0,commits.count(scope,2),"invalidated old proof must not commit adoption/checkpoint before END");
            assertTrue(PlayerPhysicsClient.currentProvenPresentation(fixture.player).isEmpty());
            fixture.tickEnd();
            if(death)assertTrue(fixture.collectPhysicsPackets().stream().noneMatch(packet->packet.kind()==Kind.INPUT),"dead actor emits no old INPUT");
            else {
                var input=fixture.readActualPhysicsPacket(Kind.INPUT,scope);assertTrue(input.predictionInput().isEmpty());
                assertTrue(input.predictionRequest().isEmpty(),"old adoption cannot submit its request after native correction");
            }
        }
    }

    private static PredictionRequest assertSuspendedOrdinary(Fixture fixture, PlayerPhysicalSnapshot authority, Packet input) {
        assertEquals(Kind.INPUT, input.kind()); assertEquals(authority.scope(), input.scope());
        assertTrue(input.predictionInput().isEmpty(), "failed/past scratch cannot be submitted as metadata");
        var request = input.predictionRequest().orElseThrow();
        assertEquals(PredictionRequestAction.SUSPEND, request.action());
        assertCanonicalSuspension(fixture, authority);
        return request;
    }

    private static void assertCanonicalSuspension(Fixture fixture, PlayerPhysicalSnapshot authority) {
        assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
        assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
        assertEquals(authority, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
        assertEquals(authority, PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
        assertEquals(authority.placement().transport().x(), fixture.player.posX);
        assertEquals(authority.placement().transport().y(), fixture.player.posY);
        assertEquals(authority.placement().transport().z(), fixture.player.posZ);
        assertEquals(authority.worldVelocity().x() * .05, fixture.player.motionX);
        assertEquals(authority.worldVelocity().y() * .05, fixture.player.motionY);
        assertEquals(authority.worldVelocity().z() * .05, fixture.player.motionZ);
        assertEquals(authority.contacts().orElseThrow().onGround(), fixture.player.onGround);
        assertCurrentUieProviders(fixture, authority);
    }

    private static Packet suspensionState(long seq, PlayerPhysicalSnapshot authority, long requestId,
            PredictionBootstrap bootstrap, PredictionSuspensionOrigin origin) {
        return withPredictionPhase(predictionPacket(Kind.STATE, seq, authority, Optional.empty()),
                new PredictionSuspended(authority.revision(), bootstrap, requestId, origin));
    }

    private static Packet withPredictionPhase(Packet packet, PredictionPhase phase) {
        return new Packet(packet.kind(), packet.scope(), packet.sequence(), packet.offerSequence(), packet.application(),
                packet.snapshot(), packet.controls(), packet.nativeTeleportId(), packet.bodyReferenceReceipt(),
                packet.bodyReferenceAcknowledgement(), packet.bodyReferenceState(), packet.predictionInput(),
                packet.predictionBoundary(), packet.predictionSeed(), Optional.of(phase), packet.predictionRequest(), packet.mobilityState());
    }

    private static Packet rearmAndAnchor(Fixture fixture, PlayerPhysicalSnapshot authority, PredictionRequest rearm, long seq) {
        var bootstrap = new PredictionBootstrap(rearm.bootstrap().generation() + 1, rearm.bootstrap().correctionRevision());
        var awaiting = withPredictionPhase(predictionPacket(Kind.STATE, seq, authority, Optional.empty()),
                new AwaitingFirstResolvedSlot(bootstrap, authority.revision(), 0));
        fixture.deliver(awaiting);
        assertTrue(fixture.collectPhysicsPackets().isEmpty(), "Awaiting installs before any new prediction INPUT");
        fixture.tickEnd(); var first = fixture.readActualPhysicsPacket(Kind.INPUT, authority.scope());
        var identity = first.predictionInput().orElseThrow();
        assertEquals(bootstrap, identity.bootstrap().orElseThrow()); assertTrue(first.predictionRequest().isEmpty());
        var boundary = new PredictionBoundary(identity.epoch(), 1, 20, 21, identity.firstClientTick(), first.sequence(),
                authority.revision(), first.sequence(), 0, JumpDecision.none(), StepOutcome.APPLIED);
        var anchored = withPredictionPhase(predictionPacket(Kind.STATE, seq + 1, authority, Optional.of(boundary)),
                new Anchored(bootstrap, authority.revision()));
        fixture.deliver(anchored); fixture.tickEnd();
        var next = fixture.readActualPhysicsPacket(Kind.INPUT, authority.scope());
        assertEquals(identity.epoch(), next.predictionInput().orElseThrow().epoch());
        assertEquals(bootstrap, next.predictionInput().orElseThrow().bootstrap().orElseThrow());
        assertTrue(next.predictionRequest().isEmpty());
        assertNotNull(PlayerPhysicsClient.currentProvenPresentation(fixture.player).orElseThrow());
        return first;
    }

    @Test void endpointGetterFailureKeepsOriginalCauseAtActualClientBoundary() {
        try(var fixture=new Fixture();var failures=new FailureLogCapture()) {
            var scope=fixture.scope(1); fixture.offerAndApply(scope); prepareEndpoint(fixture,net.minecraft.init.Blocks.STONE);
            fixture.deliver(predictionPacket(Kind.OWNED,1,endpointAuthority(scope),Optional.empty()));
            var cause=new IllegalStateException("raw-endpoint-getter-unique-X");
            ((EndpointWorld)fixture.world).endpointCallback=()->{throw cause;};
            assertSame(cause,assertThrows(IllegalStateException.class,fixture::tickEnd));
            assertTrue(failures.contains(cause)); assertTrue(fixture.collectPhysicsPackets().isEmpty());
            assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
        }
    }
    @Test void endpointScopeSuccessorCannotBeRetiredByOldPrediction() { endpointSuccessor(false); }
    @Test void endpointActorSuccessorCannotBeRetiredByOldPrediction() { endpointSuccessor(true); }
    private static void endpointSuccessor(boolean replaceActor) {
        try(var fixture=new Fixture()) {
            var scope=fixture.scope(1); fixture.offerAndApply(scope); prepareEndpoint(fixture,net.minecraft.init.Blocks.STONE);
            fixture.deliver(predictionPacket(Kind.OWNED,1,endpointAuthority(scope),Optional.empty()));
            var successorScope=fixture.scope(2); var successor=snapshot(successorScope,2,43);
            ((EndpointWorld)fixture.world).endpointCallback=()->{
                MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(fixture.world));
                if(replaceActor) {
                    fixture.player=new CallbackPlayer(fixture.mc,fixture.world,fixture.handler);
                    fixture.mc.player=fixture.player;
                }
                fixture.offerAndApply(successorScope);
                fixture.deliver(state(Kind.OWNED,2,successor));
            };
            assertDoesNotThrow(fixture::tickEnd);
            assertTrue(((EndpointWorld)fixture.world).endpointReads>0);
            assertEquals(successor.placement().transport().x(),fixture.player.posX);
            assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player)); assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
            assertTrue(fixture.collectPhysicsPackets().isEmpty(),"old endpoint must not emit INPUT or recover the successor");
            fixture.tickEnd();
            var input=fixture.collectPhysicsPackets(); assertEquals(1,input.size());
            assertEquals(Kind.INPUT,input.getFirst().kind()); assertEquals(successorScope,input.getFirst().scope());
            assertTrue(input.getFirst().predictionInput().isEmpty(),"new nonpredicting owner remains authoritative");
        }
    }
    private static PlayerPhysicalSnapshot endpointAuthority(PlayerPhysicalSnapshot.Scope scope) {
        var shape=new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS,new PlayerBodyShape.Dimensions(.6F,1.8F,1.62F));
        return new PlayerPhysicalSnapshot(scope,1,new PlayerBodyPlacement(new Vec3d(8.5,65.9,8.5),CardinalFrame.Y_POS.attitude(),shape),
                new Vec3d(0,-1,0),Optional.of(new PlayerPhysicalSnapshot.Contacts(false,false,false,0)));
    }
    private static void prepareEndpoint(Fixture fixture,net.minecraft.block.Block block) {
        fixture.world.getChunkProvider().loadChunk(0,0);
        assertTrue(fixture.world.setBlockState(new net.minecraft.util.math.BlockPos(8,64,8),block.getDefaultState()));
        ((EndpointWorld)fixture.world).afterCollision=false;
    }
    /** Real WorldClient calls remain intact; only the post-collision support getter can reenter. */
    private static final class EndpointWorld extends WorldClient {
        private int collisionDepth,endpointReads;
        private boolean afterCollision;
        private Runnable endpointCallback;
        EndpointWorld(NetHandlerPlayClient handler,WorldSettings settings,int dimension,EnumDifficulty difficulty,
                      net.minecraft.profiler.Profiler profiler) {super(handler,settings,dimension,difficulty,profiler);}
        @Override public java.util.List<AxisAlignedBB> getCollisionBoxes(net.minecraft.entity.Entity actor,AxisAlignedBB bounds) {
            collisionDepth++;
            try {return super.getCollisionBoxes(actor,bounds);}
            finally {collisionDepth--;afterCollision=true;}
        }
        @Override public net.minecraft.block.state.IBlockState getBlockState(net.minecraft.util.math.BlockPos position) {
            var state=super.getBlockState(position);
            if(afterCollision&&collisionDepth==0&&position.getX()==8&&position.getY()==64&&position.getZ()==8) {
                endpointReads++; var callback=endpointCallback;endpointCallback=null;
                if(callback!=null) callback.run();
            }
            return state;
        }
    }

    private static PlayerPhysicalSnapshot predictionSnapshot(PlayerPhysicalSnapshot.Scope scope, long revision, double x) {
        var frame = CardinalFrame.X_POS;
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return new PlayerPhysicalSnapshot(scope, revision,
                new PlayerBodyPlacement(new Vec3d(x, 70, 8), frame.attitude(), shape), new Vec3d(1, 2, 3),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }

    private static Packet predictionPacket(Kind kind, long sequence, PlayerPhysicalSnapshot authority,
            Optional<PredictionBoundary> boundary) {
        var frame = ((PlayerBodyShape.Cardinal) authority.placement().shape()).frame();
        var reference = new PlayerBodyReference.State(Optional.empty(), new PlayerBodyReference.Effective(
                PlayerBodyReference.Source.NATURAL, frame, frame.attitude().basisZ()), PlayerBodyReference.Transition.STABLE,
                Optional.empty(), Optional.empty());
        var seed = new wi.unigrav.core.player.CardinalPredictionSeed.Ready(authority.revision(), 1, frame,
                new Vec3d(0, 0, 0), Optional.empty(), false, false, true, true, 70);
        return new Packet(kind, authority.scope(), sequence, 1, Optional.empty(), Optional.of(authority), Optional.empty(),
                OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.of(reference), Optional.empty(), boundary, Optional.of(seed),
                Optional.of(boundary.isPresent() ? new PlayerPhysicsProtocol.Anchored(
                        new PlayerPhysicsProtocol.PredictionBootstrap(1,0),authority.revision())
                        : new PlayerPhysicsProtocol.AwaitingFirstResolvedSlot(
                        new PlayerPhysicsProtocol.PredictionBootstrap(1,0),authority.revision(),0)));
    }

    private static Packet predictionUnavailablePacket(long sequence, PlayerPhysicalSnapshot authority) {
        var packet=predictionPacket(Kind.STATE,sequence,authority,Optional.empty());
        return new Packet(packet.kind(),packet.scope(),packet.sequence(),packet.offerSequence(),packet.application(),packet.snapshot(),
                packet.controls(),packet.nativeTeleportId(),packet.bodyReferenceReceipt(),packet.bodyReferenceAcknowledgement(),
                packet.bodyReferenceState(),packet.predictionInput(),packet.predictionBoundary(),packet.predictionSeed(),
                Optional.of(new PlayerPhysicsProtocol.PredictionUnavailable(authority.revision(),
                        PlayerPhysicsProtocol.PredictionUnavailableReason.RETIRED)));
    }
    /** Exercises the public UIE physical-routing seam, independently of native LWJGL key polling. */
    private static ClientBoostRouting.Registration routedControls(double forward, boolean jump) {
        var revo=wi.unigrav.core.client.camera.RevoUiSixDofCamera.install(UnigravMod.entityBinder);
        var registration=ClientBoostRouting.registerSampledPhysical((actor,view)->
                PlayerPhysicsInput.keyboard(view,forward,0,jump,false,false,false));
        return ()->{
            Throwable failure=null;
            try {registration.close();} catch(Throwable cause) {failure=cause;}
            try {revo.close();} catch(Throwable cause) {if(failure==null)failure=cause;else failure.addSuppressed(cause);}
            try {wi.unigrav.core.client.camera.RevoUiSixDofCamera.install(UnigravMod.entityBinder);}
            catch(Throwable cause) {if(failure==null)failure=cause;else failure.addSuppressed(cause);}
            if(failure!=null) Fixture.rethrowConstructionFailure(failure);
        };
    }

    private static void assertCurrentUieProviders(Fixture fixture, PlayerPhysicalSnapshot current) {
        wi.unigrav.core.client.camera.RevoUiSixDofCamera.install(UnigravMod.entityBinder);
        var body = neofontrender.addons.api.flight.FlightApi.queryBodyPose(fixture.player, 1);
        var tracking = neofontrender.addons.api.flight.FlightApi.queryCameraTracking(fixture.player, 1);
        assertNotNull(body);
        if (current.placement().shape() instanceof PlayerBodyShape.Cardinal cardinal && cardinal.frame() == CardinalFrame.Y_POS)
            assertNull(tracking, "native Y_POS delegates camera tracking without an override");
        else assertNotNull(tracking);
        var expected = current.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                ? wi.unigrav.core.client.camera.SixDofBodyPresentation.sampleFree(current,
                        new neofontrender.addons.api.flight.FlightAttitude(0,0,0,1))
                : wi.unigrav.core.client.camera.SixDofBodyPresentation.sampleCardinal(current,
                PlayerPhysicsClient.acceptedEffectiveBodyReference(fixture.player).orElseThrow(),
                new neofontrender.addons.api.flight.FlightVector(fixture.player.getLook(1).x, fixture.player.getLook(1).y, fixture.player.getLook(1).z),
                null, fixture.player.prevRenderYawOffset, fixture.player.renderYawOffset, 1);
        if (current.placement().shape() instanceof PlayerBodyShape.FreeCapsule
                && !ClientGravityTicker.isPersonalZeroGActive(fixture.player)) {
            var nativeHead = FlightAttitude.fromMinecraftDegrees(fixture.player.rotationPitch, fixture.player.rotationYaw, 0);
            assertEquals(nativeHead.x, body.headWorldAttitude.x);
            assertEquals(nativeHead.y, body.headWorldAttitude.y);
            assertEquals(nativeHead.z, body.headWorldAttitude.z);
            assertEquals(nativeHead.w, body.headWorldAttitude.w);
            assertEquals(nativeHead.x, tracking.getAttitude().x);
            assertEquals(nativeHead.y, tracking.getAttitude().y);
            assertEquals(nativeHead.z, tracking.getAttitude().z);
            assertEquals(nativeHead.w, tracking.getAttitude().w);
            assertEquals(expected.body().attitude.x, body.attitude.x);
            assertEquals(expected.body().attitude.y, body.attitude.y);
            assertEquals(expected.body().attitude.z, body.attitude.z);
            assertEquals(expected.body().attitude.w, body.attitude.w);
        }
        assertEquals(expected.body().rootWorldOffset.x, body.rootWorldOffset.x);
        assertEquals(expected.body().rootWorldOffset.y, body.rootWorldOffset.y);
        assertEquals(expected.body().rootWorldOffset.z, body.rootWorldOffset.z);
        var eye = current.placement().eye();
        neofontrender.addons.api.camera.CameraApi.clearEyeAnchorSampleCache();
        var anchor = neofontrender.addons.api.camera.CameraApi.sampleEyeAnchor(
                new neofontrender.addons.api.camera.CameraEyeAnchorContext(fixture.player, current.revision() + 100, 1,
                        new neofontrender.addons.api.camera.CameraVector(0, 0, 0)));
        assertNotNull(anchor); assertEquals(eye.x(), anchor.x); assertEquals(eye.y(), anchor.y); assertEquals(eye.z(), anchor.z);
    }

    private static void assertFixtureConstructionRollback() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient previousWorld = mc.world;
        EntityPlayerSP previousPlayer = mc.player;
        assertNull(previousWorld, "construction rollback probe starts before a user world is opened");
        assertNull(previousPlayer, "construction rollback probe starts before a user player is installed");
        assertNull(mc.getConnection(), "the original host connection is empty before fixture setup");

        Fixture.FixtureResources[] observed = new Fixture.FixtureResources[1];
        var sentinel = new IllegalStateException("fixture construction failed after actual lifecycle bind");
        var escaped = assertThrows(IllegalStateException.class, () -> {
            new Fixture(resources -> {
                observed[0] = resources;
                throw sentinel;
            });
        });

        assertSame(sentinel, escaped, "constructor rollback preserves the exact setup failure");
        Fixture.FixtureResources resources = observed[0];
        assertNotNull(resources, "the failure seam runs after real fixture resources exist");
        assertSame(mc, resources.mc());
        assertSame(previousWorld, resources.previousWorld());
        assertSame(previousPlayer, resources.previousPlayer());
        assertSame(previousWorld, mc.world, "partial construction restores the previous host world");
        assertSame(previousPlayer, mc.player, "partial construction restores the previous host player");
        assertNotSame(resources.world(), mc.world, "the failed fixture world is no longer the host world");
        assertNotSame(resources.player(), mc.player, "the failed fixture player is no longer the host player");
        assertNull(mc.getConnection(), "restoring the player restores the host connection");
        assertNull(FMLCommonHandler.instance().getClientToServerNetworkManager(),
                "Forge no longer resolves the discarded fixture manager as the client route");
        assertTrue(PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(
                resources.manager(), resources.handler(), EnumPacketDirection.CLIENTBOUND)
                .filter(current -> current == resources.connection()).isEmpty(),
                "a failed constructor retires its attempted client lifecycle bind");
        assertFalse(resources.channel().isOpen(), "a failed constructor closes and releases its embedded channel");
    }

    private static final class AdoptionCommitCapture extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.Logger logger;
        private final org.apache.logging.log4j.Level previousLevel;
        private final List<java.util.Map<String,String>> commits=new ArrayList<>();
        AdoptionCommitCapture() {
            super("ug-adoption-commit-"+UUID.randomUUID(),null,null,false,Property.EMPTY_ARRAY);
            logger=assertInstanceOf(org.apache.logging.log4j.core.Logger.class,UnigravMod.LOGGER);
            previousLevel=logger.getLevel();start();logger.addAppender(this);logger.setLevel(org.apache.logging.log4j.Level.DEBUG);
        }
        @Override public void append(LogEvent event) {
            if(event.getMessage() instanceof StringMapMessage fields
                    && "prediction_suspension_adoption_commit".equals(fields.get("event")))
                commits.add(java.util.Map.copyOf(fields.getData()));
        }
        long count(PlayerPhysicalSnapshot.Scope scope,long sequence) {
            return commits.stream().filter(fields->scope.toString().equals(fields.get("scope"))
                    && Long.toString(sequence).equals(fields.get("packetSequence"))).count();
        }
        @Override public void close() {
            try {logger.setLevel(previousLevel);} finally {logger.removeAppender(this);stop();}
        }
    }

    /** Observes the ordinary diagnostic boundary; it neither changes FML routing nor the product API. */
    private static final class FailureLogCapture extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.Logger logger;
        private final List<Throwable> causes = new ArrayList<>();

        private FailureLogCapture() {
            super("ug-installation-failure-capture-" + UUID.randomUUID(), null, null, false, Property.EMPTY_ARRAY);
            logger = assertInstanceOf(org.apache.logging.log4j.core.Logger.class, UnigravMod.LOGGER);
            start();
            logger.addAppender(this);
        }
        @Override public void append(LogEvent event) {
            if (event.getThrown() != null) causes.add(event.getThrown());
        }
        private boolean contains(Throwable exactCause) {
            return causes.stream().anyMatch(cause -> containsIdentity(cause, exactCause));
        }
        @Override public void close() { logger.removeAppender(this); stop(); }
    }

    private static void assertFenced(CallbackPlayer actor) {
        assertTrue(PlayerPhysicsClient.isInstallationFenced(actor));
        assertFalse(PlayerPhysicsClient.drivesMotion(actor));
        assertTrue(PlayerPhysicsClient.acceptedPresentation(actor).isEmpty());
        assertTrue(PlayerPhysicsClient.acceptedEyePosition(actor, 1).isEmpty());
        assertEquals(1.45F, PlayerPhysicsClient.physicalEyeHeight(actor, 1.45F));
        int[] nativeCalls = {0};
        PlayerPhysicsClient.runNativePose(actor, () -> nativeCalls[0]++);
        PlayerPhysicsClient.runLegacyMotion(actor, () -> nativeCalls[0]++);
        assertEquals(0, nativeCalls[0], "the fenced actor cannot reach competing native writers");
    }

    private static boolean containsIdentity(Throwable failure, Throwable expected) {
        if (failure == expected) return true;
        if (failure.getCause() != null && failure.getCause() != failure && containsIdentity(failure.getCause(), expected)) return true;
        for (Throwable suppressed : failure.getSuppressed()) if (containsIdentity(suppressed, expected)) return true;
        return false;
    }

    private static Packet offer(PlayerPhysicalSnapshot.Scope scope) {
        return new Packet(Kind.OFFER, scope, 0, 1, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Packet state(Kind kind, long sequence, PlayerPhysicalSnapshot snapshot) {
        return new Packet(kind, snapshot.scope(), sequence, 1, Optional.empty(), Optional.of(snapshot), Optional.empty());
    }

    private static PlayerPhysicalSnapshot snapshot(PlayerPhysicalSnapshot.Scope scope, long revision, double x) {
        var frame = CardinalFrame.Y_POS;
        var shape = new PlayerBodyShape.Cardinal(frame, new PlayerBodyShape.Dimensions(.6F, 1.8F, 1.62F));
        return new PlayerPhysicalSnapshot(scope, revision,
                new PlayerBodyPlacement(new Vec3d(x, 70, 8), frame.attitude(), shape), new Vec3d(1, 2, 3),
                Optional.of(new PlayerPhysicalSnapshot.Contacts(false, false, false, 0)));
    }

    /** Dedicated SOURCE selector; execution requires its own explicit normal-FML profile. */
    public static final class ServerEnvironmentRecoveryCases {
        @Test void healthyOwnerAcceptsExactRecoveryAndDuplicatesPreserveInstalledSuccessor() throws Exception {
            try (var fixture = new Fixture()) {
                var oldScope = fixture.scope(1);
                fixture.offerAndApply(oldScope);
                var oldSnapshot = snapshot(oldScope, 1, 12);
                fixture.deliver(state(Kind.OWNED, 1, oldSnapshot));
                assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
                var nextScope = fixture.scope(2);
                var receipt = new PlayerPhysicsProtocol.ServerEnvironmentRecovery(oldScope, 1, OptionalInt.empty());
                var recovery = PlayerPhysicsProtocol.nativeTransferPacket(Kind.OFFER, nextScope, 1, 1, receipt);
                fixture.deliver(recovery);
                assertFalse(PlayerPhysicsClient.isInstallationFenced(fixture.player));
                assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player), "the new OFFER still requires its OWNED");
                double actualX = fixture.player.posX;
                double actualY = fixture.player.getEntityBoundingBox().minY;
                double actualZ = fixture.player.posZ;
                var outgoing = new CPacketPlayer(false);
                var positioned = assertInstanceOf(CPacketPlayer.Position.class,
                        PlayerPhysicsClient.prepareOutgoing(fixture.handler, outgoing));
                assertEquals(actualX, positioned.getX(Double.NaN), 0);
                assertEquals(actualY, positioned.getY(Double.NaN), 0);
                assertEquals(actualZ, positioned.getZ(Double.NaN), 0);
                assertEquals(outgoing.isOnGround(), positioned.isOnGround());
                fixture.channel.runPendingTasks();
                fixture.channel.checkException();
                fixture.assertActualApply(nextScope);
                fixture.deliver(recovery);
                var successor = snapshot(nextScope, 2, 12);
                fixture.deliver(state(Kind.OWNED, 2, successor));
                assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
                var installed = PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow();
                fixture.deliver(recovery);
                assertSame(installed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
                assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player));
                fixture.deliver(state(Kind.STATE, 3, successor));
                assertTrue(PlayerPhysicsClient.drivesMotion(fixture.player),
                        "duplicate OFFER must retain the successful installation and its exact opened scope");
                assertEquals(successor, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
            }
        }

        @Test void ordinaryHigherEpochAndRecoveryAfterNewTeleportCannotReplaceHealthyCustody() throws Exception {
            try (var fixture = new Fixture()) {
                var oldScope = fixture.scope(1);
                fixture.offerAndApply(oldScope);
                fixture.deliver(state(Kind.OWNED, 1, snapshot(oldScope, 1, 12)));
                var installed = PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow();
                fixture.deliver(offer(fixture.scope(2)));
                assertSame(installed, PlayerPhysicsClient.acceptedPresentation(fixture.player).orElseThrow());
                PlayerPhysicsClient.nativeCorrection(fixture.handler, 31);
                var recovery = PlayerPhysicsProtocol.nativeTransferPacket(Kind.OFFER, fixture.scope(2), 1, 1,
                        new PlayerPhysicsProtocol.ServerEnvironmentRecovery(oldScope, 1, OptionalInt.empty()));
                fixture.deliver(recovery);
                assertTrue(PlayerPhysicsClient.acceptedPresentation(fixture.player).isEmpty(),
                        "the new pending teleport remains fenced; a stale origin cannot install presentation");
            }
        }
    }

    /** Dedicated selector: does not change the accepted default raw25/provenance5/sleep profiles. */
    public static final class NativeTransferRecoveryCases {
        @Test void actualClientBasePartialFailureSchedulesExactRecoveryAndInstallsNewBase() throws Exception {
            actualClientFailure(false);
        }
        @Test void actualClientMotionPartialFailureSchedulesExactRecoveryAndInstallsNewBase() throws Exception {
            actualClientFailure(true);
        }
        private static void actualClientFailure(boolean motion) throws Exception {
            try (var fixture = new Fixture(); var failures = new FailureLogCapture()) {
                var scope = fixture.scope(1); fixture.offerAndApply(scope);
                var originalPose = snapshot(scope, 1, 12);
                var receipt = new BodyReferenceReceipt(1, BodyReferenceDecision.UNLOCKED, 0,
                        Optional.empty(), Optional.empty(), Optional.of(BodyReferenceReason.NATIVE_HANDOFF));
                fixture.deliver(new Packet(Kind.STATE, scope, 1, 1, Optional.empty(), Optional.of(originalPose),
                        Optional.empty(), OptionalInt.empty(), Optional.of(receipt), Optional.empty()));
                fixture.tickEnd();
                assertEquals(new BodyReferenceAcknowledgement(1, 1), fixture.readActualPhysicsPacket(Kind.INPUT, scope)
                        .bodyReferenceAcknowledgement().orElseThrow());
                long failedSequence = motion ? 3 : 2;
                if (motion) {
                    fixture.deliver(PlayerPhysicsProtocol.nativeTransferPacket(Kind.NATIVE, scope, 2, 1,
                            new NativeHandoff(91, 1, new Vec3d(260, 1, 2))));
                    assertEquals(new NativeHandoffAcknowledgement(91, 1), fixture.readActualPhysicsPacket(Kind.NATIVE_ACK, scope)
                            .nativeTransfer().orElseThrow());
                }
                double partialX = motion ? 26 : 13;
                var original = new IllegalStateException("actual-client-native-partial-original");
                fixture.player.aliveCallback = () -> {
                    if (fixture.player.motionX == partialX) {
                        fixture.player.aliveCallback = null;
                        throw original;
                    }
                };
                var failedPacket = PlayerPhysicsProtocol.nativeTransferPacket(motion ? Kind.NATIVE_MOTION : Kind.NATIVE,
                        scope, failedSequence, 1, motion ? new NativeMotion(91, 1, 1, new Vec3d(520, 3, 4))
                                : new NativeHandoff(91, 1, new Vec3d(260, 1, 2)));
                assertDoesNotThrow(() -> fixture.deliver(failedPacket));
                assertTrue(failures.contains(original), "actual client responsibility boundary retains original cause");
                assertEquals(partialX, fixture.player.motionX);
                assertFalse(PlayerPhysicsClient.drivesMotion(fixture.player));
                for (int i = 0; i < 20; i++) { fixture.tickStart(); fixture.tickEnd(); }
                var feedback = fixture.readActualPhysicsPacket(Kind.RECOVER, scope);
                var failed = new NativeTransferFailure(91, 1, failedSequence, motion ? 1 : 0);
                assertEquals(failed, feedback.nativeTransfer().orElseThrow());
                var recovered = snapshot(scope, 2, 12);
                var recovery = new Packet(Kind.STATE, scope, failedSequence + 1, 1, Optional.empty(), Optional.of(recovered),
                        Optional.empty(), OptionalInt.empty(), Optional.of(receipt), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(new PlayerPhysicsProtocol.PredictionUnavailable(2,
                                PlayerPhysicsProtocol.PredictionUnavailableReason.INELIGIBLE)), Optional.empty(), Optional.empty(),
                        Optional.of(new NativeTransferRecovery(failed)));
                fixture.deliver(recovery); fixture.tickEnd();
                assertEquals(new BodyReferenceAcknowledgement(recovery.sequence(), 2),
                        fixture.readActualPhysicsPacket(Kind.INPUT, scope).bodyReferenceAcknowledgement().orElseThrow());
                fixture.deliver(PlayerPhysicsProtocol.nativeTransferPacket(Kind.NATIVE, scope, failedSequence + 2, 1,
                        new NativeHandoff(92, 2, new Vec3d(partialX * 20, 3, 4))));
                assertEquals(new NativeHandoffAcknowledgement(92, 2), fixture.readActualPhysicsPacket(Kind.NATIVE_ACK, scope)
                        .nativeTransfer().orElseThrow());
                assertEquals(partialX, fixture.player.motionX); assertEquals(.15, fixture.player.motionY);
                assertEquals(.2, fixture.player.motionZ);
                assertDoesNotThrow(() -> fixture.deliver(failedPacket));
                assertEquals(partialX, fixture.player.motionX);
            }
        }
    }

    private static final class CallbackPlayer extends EntityPlayerSP {
        private Runnable boundsProbe;
        private Runnable boundsReadCallback;
        @Override public AxisAlignedBB getEntityBoundingBox() {
            var bounds=super.getEntityBoundingBox();
            var callback=boundsReadCallback;
            if(callback!=null) callback.run();
            return bounds;
        }
        private Runnable aliveCallback;
        private Runnable sampleSpeedCallback;
        @Override public float getAIMoveSpeed() {
            float speed = super.getAIMoveSpeed();
            var callback = sampleSpeedCallback; sampleSpeedCallback = null;
            if (callback != null) callback.run();
            return speed;
        }

        @Override public boolean isEntityAlive() {
            boolean alive = super.isEntityAlive();
            Runnable callback = aliveCallback;
            if (callback != null) callback.run();
            return alive;
        }

        private RuntimeException boundsFailure;
        private RuntimeException restorationFailure;
        private Runnable boundsCallback;
        private AxisAlignedBB boundsTail;
        private boolean inBoundsCallback;
        private Runnable restorationCallback;

        private CallbackPlayer(Minecraft mc, WorldClient world, NetHandlerPlayClient handler) {
            super(mc, world, handler, new StatisticsManager(), new RecipeBook());
        }

        @Override public void setNoGravity(boolean noGravity) {
            if (!noGravity && restorationCallback != null) {
                var callback = restorationCallback; restorationCallback = null; callback.run();
            }
            if (!noGravity && restorationFailure != null) {
                RuntimeException failure = restorationFailure;
                restorationFailure = null;
                throw failure;
            }
            super.setNoGravity(noGravity);
        }

        @Override public void setEntityBoundingBox(AxisAlignedBB bounds) {
            super.setEntityBoundingBox(bounds);
            if(boundsProbe!=null) boundsProbe.run();
            Runnable callback = boundsCallback;
            if (!inBoundsCallback && callback != null) {
                boundsCallback = null; inBoundsCallback = true;
                try { callback.run(); } finally { inBoundsCallback = false; }
                if (boundsTail != null) super.setEntityBoundingBox(boundsTail);
            }
            RuntimeException failure = boundsFailure;
            boundsFailure = null;
            if (failure != null) throw failure;
        }
    }

    /** Independent actual-Mixin gate; it does not need a player, G key or network admission. */
    public static final class NativeBodyConstructorProbeProfile {
        @Test void actualEntityConstructorCapturesFullFieldsBeforeVirtualInitialization() {
            var first=new ConstructorProbeEntity();
            var second=new ConstructorProbeEntity();
            assertNotSame(first,second);
            assertTrue(first.initialized && second.initialized, "real Entity constructor invoked entityInit");
            var firstProducer=assertInstanceOf(wi.unigrav.core.player.NativeBodyProfileProducer.class,first,
                    "the actual transformed Entity implements the production interface");
            var secondProducer=assertInstanceOf(wi.unigrav.core.player.NativeBodyProfileProducer.class,second);
            var firstBase=firstProducer.unigrav$nativeBodyProfile().orElseThrow();
            var secondBase=secondProducer.unigrav$nativeBodyProfile().orElseThrow();
            assertConstructorBase(first,firstBase);
            assertConstructorBase(second,secondBase);
            assertEquals(first.atInitWidth*.5F,first.width,0);
            assertEquals(first.atInitHeight*.5F,first.height,0);
            assertNotEquals(Float.floatToRawIntBits(first.height),Float.floatToRawIntBits(firstBase.baseLength()),
                    "entityInit really changed raw pose size after the production constructor capture");
            float secondWidth=second.width,secondHeight=second.height;
            first.resizeAfterConstruction(first.atInitWidth*.25F,first.atInitHeight*.25F);
            assertEquals(firstBase,firstProducer.unigrav$nativeBodyProfile().orElseThrow(),
                    "later real setSize must not replace this entity's constructor base");
            assertEquals(secondWidth,second.width,0);
            assertEquals(secondHeight,second.height,0);
            assertEquals(secondBase,secondProducer.unigrav$nativeBodyProfile().orElseThrow(),
                    "changing the first entity cannot replace the second entity's observation");
            second.resizeAfterConstruction(second.atInitWidth*.75F,second.atInitHeight*.75F);
            assertEquals(firstBase,firstProducer.unigrav$nativeBodyProfile().orElseThrow());
            assertEquals(secondBase,secondProducer.unigrav$nativeBodyProfile().orElseThrow());
            System.out.println("UG_NATIVE_BODY_CONSTRUCTOR_PROBE first="+firstBase+" second="+secondBase
                    +" firstInit="+first.atInitWidth+","+first.atInitHeight
                    +" secondInit="+second.atInitWidth+","+second.atInitHeight);
        }
        private static void assertConstructorBase(ConstructorProbeEntity entity,
                wi.unigrav.core.player.NativeBodyProfileProducer.Profile profile) {
            assertTrue(entity.atInitWidth>0 && entity.atInitHeight>=entity.atInitWidth);
            assertEquals(wi.unigrav.core.player.NativeBodyProfileProducer.Source.VANILLA_CONSTRUCTOR,profile.source());
            assertEquals(Float.floatToRawIntBits(entity.atInitWidth),Float.floatToRawIntBits(profile.baseDiameter()));
            assertEquals(Float.floatToRawIntBits(entity.atInitHeight),Float.floatToRawIntBits(profile.baseLength()));
            assertEquals(Float.floatToRawIntBits(entity.atInitWidth),Float.floatToRawIntBits(profile.diameter()));
            assertEquals(Float.floatToRawIntBits(entity.atInitHeight),Float.floatToRawIntBits(profile.length()));
        }
        private static final class ConstructorProbeEntity extends net.minecraft.entity.Entity {
            // No initializers: entityInit is dispatched from super(), before subclass initialization.
            private float atInitWidth,atInitHeight;
            private boolean initialized;
            ConstructorProbeEntity() { super(null); }
            @Override protected void entityInit() {
                assertFalse(initialized,"Entity construction must initialize each probe once");
                atInitWidth=width; atInitHeight=height; initialized=true;
                width=atInitWidth*.5F; height=atInitHeight*.5F;
            }
            void resizeAfterConstruction(float width,float height) { setSize(width,height); }
            @Override protected void readEntityFromNBT(net.minecraft.nbt.NBTTagCompound tag) { }
            @Override protected void writeEntityToNBT(net.minecraft.nbt.NBTTagCompound tag) { }
        }
    }

    /** Independent normal-FML profile: real keyboard capture and routed frame, never synthetic Controls. */
    /** Finite native key/living-update/abilities gate; does not claim raw-keyboard movement acceptance. */
    /** Actual AQ/Artemis transformed actors and native attribute events; no TEST body producer. */
    public static final class ArtemisScaleBodyProfile {
        private static final UUID WIDTH_ID=UUID.fromString("8ae42c7a-166c-42d0-aea5-1d032b389d51");
        private static final UUID HEIGHT_ID=UUID.fromString("517df852-24bf-453a-9473-809def0c1068");

        @Test void actualArtemisScaleProducerSurvivesManualLockAndFreeUnlock(
                @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            assertTrue(net.minecraftforge.fml.common.Loader.isModLoaded("aquaacrobatics"));
            assertTrue(net.minecraftforge.fml.common.Loader.isModLoaded("artemislib"));
            try(var client=new Fixture();
                var server=new SleepingNativeResumeServerTestSupport(directory,client.mc.player.getUniqueID(),false,true);
                var keys=new GOnActualInputProfile.ActualInputKeys(client.mc);
                AutoCloseable releaseWorld=() -> client.mc.loadWorld(null)) {
                var link=new SleepWireLink(client,server);
                link.pump(); server.assertEarlyFlightHandshakeRequest(); server.assertLoggedIn();
                assertEquals(1,link.flightCapabilityResponses); assertEquals(1,link.joinGames);
                assertEquals(net.minecraft.entity.player.EntityPlayerMP.class,server.player().getClass(),
                        "plain native MP has no PosePlayer TEST producer override");
                assertEquals(EntityPlayerSP.class,client.mc.player.getClass(),"JoinGame created the actual native SP");
                var serverBase=bodyProfile(server.player());
                var clientBase=bodyProfile(client.mc.player);
                assertEquals(serverBase,clientBase,"independent production actors observe the same initial standing profile");
                assertEquals(serverBase.baseDiameter(),serverBase.diameter(),0);
                assertEquals(serverBase.baseLength(),serverBase.length(),0);
                float baseEye=server.player().getEyeHeight();
                assertEquals(baseEye,client.mc.player.getEyeHeight(),0);
                server.prepareNoReferenceAdmission();
                server.player().connection.sendPacket(new net.minecraft.network.play.server.SPacketChunkData(
                        server.player().world.getChunk(0,0),65535));
                link.pump();
                var loaded=client.mc.world.getChunkProvider().getLoadedChunk(0,0);
                assertNotNull(loaded); assertTrue(loaded.isLoaded());
                server.tick();link.pump();client.tickEnd();link.pump();
                client.tickStart();client.tickEnd();link.pump();server.assertConfirmedGOff();
                server.tick();link.pump();link.position();server.tick();link.pump();link.position();
                server.assertOwned();server.assertNoReferenceEnvironment();
                var scope=server.accepted().scope();
                server.player().setGameType(GameType.CREATIVE);link.pump();
                assertTrue(server.player().isCreative());assertTrue(client.mc.player.isCreative());
                client.mc.currentScreen=null;client.mc.inGameHasFocus=true;
                net.minecraft.client.settings.KeyBinding.onTick(ClientGravityTicker.ZERO_G_KEY.getKeyCode());
                client.tickEnd();link.pump();
                assertTrue(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG());
                assertTrue(wi.unigrav.core.player.GIntentServer.INSTANCE.observe(server.player()).orElseThrow().active());
                try(AutoCloseable restoreScale=() -> {
                        removeScale(server.player());removeScale(client.mc.player);
                        nativeScaleTick(client,server,link);
                        traceScale("RESTORED",client,server,link,baseEye);
                        assertEquals(serverBase,bodyProfile(server.player()),"server native standing producer restored");
                        assertEquals(clientBase,bodyProfile(client.mc.player),"client native standing producer restored");
                        assertEquals(baseEye,server.player().getEyeHeight(),0);
                        assertEquals(baseEye,client.mc.player.getEyeHeight(),0);
                        assertPhysicalProfile(server.accepted(),serverBase);
                    };
                    AutoCloseable restoreG=() -> {
                        if(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG()) {
                            keys.neutral();net.minecraft.client.settings.KeyBinding.onTick(ClientGravityTicker.ZERO_G_KEY.getKeyCode());
                            client.tickEnd();link.pump();server.assertConfirmedGOff();
                        }
                    }) {
                    applyScale(server.player());applyScale(client.mc.player);
                    nativeScaleTick(client,server,link);
                    traceScale("SCALED",client,server,link,baseEye);
                    var scaledServer=assertScaled(server.player(),serverBase);
                    var scaledClient=assertScaled(client.mc.player,clientBase);
                    assertEquals(scaledServer,scaledClient);
                    assertPhysicalProfile(server.accepted(),scaledServer);
                    assertEquals((double)expectedScaledStandingEye(server.player(),serverBase),
                            server.accepted().placement().shape().dimensions().eyeHeight(),0);
                    SleepNativeResumeProfile.assertSameSnapshot(server,client,scope,link);
                    var lock=PlayerPhysicsClient.requestBodyReferenceLock(CardinalFrame.X_POS,new Vec3d(0,0,1));
                    assertTrue(lock.isPresent(),"actual owned client admitted the public manual request");
                    client.tickEnd();link.pump();
                    assertTrue(link.actualBodyReferenceIntents.stream().anyMatch(intent -> intent.requestId()==lock.orElseThrow()
                            && intent.action()==PlayerPhysicsProtocol.BodyReferenceAction.LOCK));
                    server.tick();link.pump();server.assertOwned();
                    var cardinal=assertInstanceOf(PlayerBodyShape.Cardinal.class,server.accepted().placement().shape());
                    assertEquals(CardinalFrame.X_POS,cardinal.frame());
                    assertEquals((double)scaledServer.diameter(),cardinal.dimensions().width(),0);
                    assertEquals((double)scaledServer.length(),cardinal.dimensions().height(),0);
                    assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.LOCKED,
                            PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().status(),link::loginDiagnostics);
                    var lockAuthority=AquaNativeEyeProfile.installedEyeAuthority(link,server.accepted(),server.accepted());
                    client.tickEnd();link.pump();AquaNativeEyeProfile.assertActualAcknowledgement(link,lockAuthority);
                    var unlock=PlayerPhysicsClient.requestBodyReferenceUnlock();assertTrue(unlock.isPresent());
                    client.tickEnd();link.pump();
                    assertTrue(link.actualBodyReferenceIntents.stream().anyMatch(intent -> intent.requestId()==unlock.orElseThrow()
                            && intent.action()==PlayerPhysicsProtocol.BodyReferenceAction.UNLOCK));
                    server.tick();link.pump();server.assertOwned();
                    assertEquals(PlayerPhysicsClientSession.BodyReferenceStatus.UNLOCKED,
                            PlayerPhysicsClient.bodyReferenceStatus().orElseThrow().status(),link::loginDiagnostics);
                    assertInstanceOf(PlayerBodyShape.FreeCapsule.class,server.accepted().placement().shape());
                    assertPhysicalProfile(server.accepted(),scaledServer);
                    assertScaled(server.player(),serverBase);assertScaled(client.mc.player,clientBase);
                    SleepNativeResumeProfile.assertSameSnapshot(server,client,scope,link);
                    var finalAuthority=AquaNativeEyeProfile.installedEyeAuthority(link,server.accepted(),server.accepted());
                    client.tickEnd();link.pump();AquaNativeEyeProfile.assertActualAcknowledgement(link,finalAuthority);
                    System.out.println("UG_ARTEMIS_BODY_SCALE scope="+scope+" MP="+server.player().getClass().getName()
                            +" SP="+client.mc.player.getClass().getName()+" base="+serverBase+" scaled="+scaledServer
                            +" native="+server.player().width+","+server.player().height+","+server.player().getEyeHeight()
                            +" accepted="+server.accepted()+" wire="+link.physicsWire);
                }
            }
        }

        private static NativeBodyProfileProducer.Profile bodyProfile(net.minecraft.entity.player.EntityPlayer actor) {
            assertInstanceOf(IPlayerResizeable.class,actor,"actual Aqua transformed player ABI");
            var producer=assertInstanceOf(NativeBodyProfileProducer.class,actor);
            var observed=producer.unigrav$nativeBodyProfile().orElseThrow();
            assertEquals(NativeBodyProfileProducer.Source.AQUA_STANDING,observed.source(),
                    "production Aqua Mixin, never PosePlayer VANILLA TEST capture");
            assertEquals(Pose.STANDING,((IPlayerResizeable)actor).getPose());
            return observed;
        }
        private static void applyScale(net.minecraft.entity.player.EntityPlayer actor) {
            var width=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_WIDTH);
            var height=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_HEIGHT);
            assertNotNull(width,"actual Artemis EntityConstructing registered width");assertNotNull(height);
            assertEquals(1D,width.getAttributeValue(),0);assertEquals(1D,height.getAttributeValue(),0);
            assertNull(width.getModifier(WIDTH_ID));assertNull(height.getModifier(HEIGHT_ID));
            width.applyModifier(new net.minecraft.entity.ai.attributes.AttributeModifier(WIDTH_ID,"UG actual scale test width",-.5,2));
            height.applyModifier(new net.minecraft.entity.ai.attributes.AttributeModifier(HEIGHT_ID,"UG actual scale test height",-.5,2));
            assertEquals(.5D,width.getAttributeValue(),0);assertEquals(.5D,height.getAttributeValue(),0);
        }
        private static void removeScale(net.minecraft.entity.player.EntityPlayer actor) {
            var width=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_WIDTH);
            var height=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_HEIGHT);
            var wm=width.getModifier(WIDTH_ID);if(wm!=null)width.removeModifier(wm);
            var hm=height.getModifier(HEIGHT_ID);if(hm!=null)height.removeModifier(hm);
        }
        private static void nativeScaleTick(Fixture client,SleepingNativeResumeServerTestSupport server,
                                            SleepWireLink link) throws Exception {
            // Public native update reaches AQ updateSize -> registered Artemis post-player-tick
            // -> updateEyeHeight. Server drain also invokes actual NetHandler/player.onUpdateEntity.
            client.mc.player.onUpdate();link.pump();server.tick();link.pump();
            client.tickStart();client.tickEnd();link.pump();server.assertOwned();
        }
        private static float expectedScaledStandingEye(net.minecraft.entity.player.EntityPlayer actor,
                                                       NativeBodyProfileProducer.Profile base) {
            float height=(float)(base.baseLength()*.5D);
            assertEquals(.9F,height,0);assertTrue(height<1.6F);
            // Artemis 1.0.6 AttachAttributes.onPlayerTick: double attribute multiply -> float,
            // then the height<1.6F standing branch multiplies by the float short-body factor.
            float eye=(float)(actor.getDefaultEyeHeight()*.5D);
            return eye*.9876542F;
        }
        private static void traceScale(String phase,Fixture client,SleepingNativeResumeServerTestSupport server,
                                       SleepWireLink link,float baseEye) {
            for (var actor:java.util.List.of(server.player(),client.mc.player)) {
                var width=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_WIDTH);
                var height=actor.getEntityAttribute(ArtemisLibAttributes.ENTITY_HEIGHT);
                var aqua=(IPlayerResizeable)actor;
                System.out.println("UG_ARTEMIS_SCALE_EYE phase="+phase+" actor="+actor.getClass().getName()
                        +" baseEye="+baseEye+" baseBits="+Float.floatToIntBits(baseEye)
                        +" defaultEye="+actor.getDefaultEyeHeight()+" rawEye="+actor.eyeHeight
                        +" rawBits="+Float.floatToIntBits(actor.eyeHeight)
                        +" cachedProfile="+((NativePoseEyeProducer)actor).unigrav$nativePoseEyeProfile()
                        +" effectiveEye="+actor.getEyeHeight()+" native="+actor.width+","+actor.height
                        +" pose="+aqua.getPose()+" aquaSize="+aqua.getSize(aqua.getPose())
                        +" body="+((NativeBodyProfileProducer)actor).unigrav$nativeBodyProfile()
                        +" widthAttr="+width.getAttributeValue()+" widthModifiers="+width.getModifiers()
                        +" heightAttr="+height.getAttributeValue()+" heightModifiers="+height.getModifiers()
                        +" accepted="+server.accepted()+" owner="+link.loginDiagnostics());
            }
        }
        private static NativeBodyProfileProducer.Profile assertScaled(net.minecraft.entity.player.EntityPlayer actor,
                NativeBodyProfileProducer.Profile base) {
            var profile=bodyProfile(actor);
            float diameter=(float)(base.baseDiameter()*.5D),length=(float)(base.baseLength()*.5D);
            assertEquals(base.baseDiameter(),profile.baseDiameter(),0);assertEquals(base.baseLength(),profile.baseLength(),0);
            assertEquals(diameter,profile.diameter(),0);assertEquals(length,profile.length(),0);
            assertEquals(.3F,profile.diameter(),0);assertEquals(.9F,profile.length(),0);
            assertEquals(expectedScaledStandingEye(actor,base),actor.getEyeHeight(),0);
            assertEquals(diameter,actor.width,0);assertEquals(length,actor.height,0);

            return profile;
        }
        private static void assertPhysicalProfile(PlayerPhysicalSnapshot snapshot,NativeBodyProfileProducer.Profile nativeProfile) {
            var free=assertInstanceOf(PlayerBodyShape.FreeCapsule.class,snapshot.placement().shape());
            assertEquals((double)nativeProfile.diameter(),free.collisionProfile().diameter(),0);
            assertEquals((double)nativeProfile.length(),free.collisionProfile().length(),0);
        }
    }

    public static final class NativeDoubleSpaceAbilitiesOwnedStateProfile {
        @Test void actualNativeDoubleSpaceSendsAbilitiesAndKeepsOwnedState(
                @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            try (var client=new Fixture();
                 var server=new SleepingNativeResumeServerTestSupport(directory,client.mc.player.getUniqueID(),false);
                 var keys=new GOnActualInputProfile.ActualInputKeys(client.mc);
                 AutoCloseable releaseWorld=() -> client.mc.loadWorld(null)) {
                var link=new SleepWireLink(client,server);
                link.pump(); server.assertEarlyFlightHandshakeRequest(); server.assertLoggedIn();
                assertEquals(1,link.flightCapabilityResponses,link::loginDiagnostics);
                server.prepareNoReferenceAdmission();
                assertEquals(1,link.joinGames,link::loginDiagnostics);
                assertTrue(link.actualConfirmIds.contains(link.lastTeleportId),link::loginDiagnostics);
                server.tick(); link.pump();
                client.tickEnd(); link.pump();
                client.tickStart(); client.tickEnd(); link.pump();
                server.assertConfirmedGOff();
                server.tick(); link.pump(); link.position();
                server.tick(); link.pump(); link.position();
                server.assertOwned(); server.assertNoReferenceEnvironment();
                assertInstanceOf(PlayerBodyShape.FreeCapsule.class,server.accepted().placement().shape());
                server.player().setGameType(GameType.CREATIVE); link.pump();
                assertTrue(server.player().isCreative()); assertFalse(server.player().isSpectator());
                assertEquals(GameType.CREATIVE,client.mc.playerController.getCurrentGameType());
                assertTrue(client.mc.player.isCreative()); assertFalse(client.mc.player.isSpectator());
                assertTrue(server.player().capabilities.allowFlying);
                assertTrue(client.mc.player.capabilities.allowFlying);
                assertFalse(server.player().capabilities.isFlying);
                assertFalse(client.mc.player.capabilities.isFlying);
                assertFalse(server.player().onGround,"actual owned no-support admission must be airborne");
                assertFalse(client.mc.player.onGround,"do not force the native landing guard off");
                assertFalse(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG());
                client.mc.currentScreen=null; client.mc.inGameHasFocus=true;
                neofontrender.addons.input.VanillaInputBridge.install();
                var scope=server.accepted().scope();
                // JoinGame replaced the probe WorldClient. Native living/fluid callbacks need
                // its actual loaded collision column, supplied by the real server chunk packet.
                var bounds=server.accepted().placement().bounds();
                assertEquals(0,net.minecraft.util.math.MathHelper.floor(bounds.min().x())>>4);
                assertEquals(0,net.minecraft.util.math.MathHelper.floor(bounds.max().x())>>4);
                assertEquals(0,net.minecraft.util.math.MathHelper.floor(bounds.min().z())>>4);
                assertEquals(0,net.minecraft.util.math.MathHelper.floor(bounds.max().z())>>4);
                var serverChunk=server.player().world.getChunk(0,0);
                assertTrue(serverChunk.isLoaded(),"native fixture server column is actually loaded");
                server.player().connection.sendPacket(new net.minecraft.network.play.server.SPacketChunkData(serverChunk,65535));
                link.pump();
                var clientChunk=client.mc.world.getChunkProvider().getLoadedChunk(0,0);
                assertNotNull(clientChunk,"real ChunkData must load the JoinGame-created WorldClient column");
                assertTrue(clientChunk.isLoaded(),"fluid observation requires loaded, not merely allocated, client column");
                for(int y=65;y<=66;y++) {
                    var cell=new BlockPos(8,y,8);
                    assertEquals(serverChunk.getBlockState(cell),clientChunk.getBlockState(cell),
                            "real ChunkData preserves the actual capsule voxel state");
                }
                System.out.println("UG_NATIVE_DOUBLE_SPACE_COLUMN chunk=0,0 serverLoaded="+serverChunk.isLoaded()
                        +" clientLoaded="+clientChunk.isLoaded()+" bounds="+bounds);
                int firstAbility=link.actualClientFlyingAbilities.size();
                // onLivingUpdate saves the previous jump before updating MovementInput itself.
                // Pre-updating MovementInput here would erase the real native rising edge.
                boolean[] held={false,true,false,true,false,false,false,false};
                for(int tick=0;tick<held.length;tick++) {
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(
                            client.mc.gameSettings.keyBindJump.getKeyCode(),held[tick]);
                    neofontrender.addons.input.VanillaInputBridge.capture(client.mc,0,0);
                    var frame=neofontrender.addons.api.input.InputApi.beginFrame(0,true);
                    assertTrue(frame.getSampleId()>0 && frame.getContext().isGameFocused());
                    assertNotEquals(neofontrender.addons.api.input.InputDisposition.BLOCK,
                            frame.disposition(neofontrender.addons.api.input.InputAction.PLAYER_JUMP),
                            "public router must not block the native jump lifecycle");
                    client.tickStart();
                    boolean oldJump=client.mc.player.movementInput.jump;
                    client.mc.player.onLivingUpdate();
                    assertEquals(held[tick],client.mc.player.movementInput.jump,"actual native MovementInput reads public held key");
                    boolean afterNativeFlying=client.mc.player.capabilities.isFlying;
                    boolean afterNativeGround=client.mc.player.onGround;
                    int firstInput=link.actualInputs.size();
                    client.tickEnd(); link.pump();
                    assertEquals(firstInput+1,link.actualInputs.size(),"one real END emits one actual INPUT");
                    var input=link.actualInputs.get(firstInput);
                    assertEquals(scope,input.scope());
                    var before=server.accepted();
                    server.tick(); link.pump(); server.assertOwned(); server.assertNoReferenceEnvironment();
                    assertEquals(scope,server.accepted().scope());
                    assertTrue(server.accepted().revision()>before.revision(),link::loginDiagnostics);
                    SleepNativeResumeProfile.assertSameSnapshot(server,client,scope,link);
                    System.out.println("UG_NATIVE_DOUBLE_SPACE tick="+tick+" held="+held[tick]+" oldJump="+oldJump
                            +" nativeFlying="+afterNativeFlying+" nativeGround="+afterNativeGround
                            +" clientFlying="+client.mc.player.capabilities.isFlying
                            +" serverFlying="+server.player().capabilities.isFlying
                            +" abilities="+link.actualClientFlyingAbilities+" controls="+input.controls().orElseThrow()
                            +" clientMotion="+client.mc.player.motionX+","+client.mc.player.motionY+","+client.mc.player.motionZ
                            +" accepted="+server.accepted());
                    if(tick<3) assertEquals(firstAbility,link.actualClientFlyingAbilities.size(),
                            "only the second actual rising edge may toggle native flight");
                    if(tick>=3) {
                        assertTrue(link.actualClientFlyingAbilities.subList(firstAbility,
                                link.actualClientFlyingAbilities.size()).contains(true),
                                "native second rising edge must emit the original flying abilities packet");
                        assertFalse(afterNativeGround,"actual landing closes native flight; report it rather than forcing airborne");
                        assertTrue(afterNativeFlying,"native double-space must enable flight in this no-support scene");
                        assertTrue(client.mc.player.capabilities.isFlying,"client flight remains enabled after real wire processing");
                        assertTrue(server.player().capabilities.isFlying,"server accepted actual abilities under Creative allowFlying");
                        assertFalse(link.actualClientFlyingAbilities.subList(firstAbility,
                                link.actualClientFlyingAbilities.size()).contains(false),"native lifecycle unexpectedly disabled flight");
                    }
                    assertFalse(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG());
                }
                keys.neutral();
                neofontrender.addons.input.VanillaInputBridge.capture(client.mc,0,0);
                neofontrender.addons.api.input.InputApi.beginFrame(0,true);
                client.tickStart(); client.tickEnd(); link.pump();
                var authority=AquaNativeEyeProfile.installedEyeAuthority(link,server.accepted(),server.accepted());
                AquaNativeEyeProfile.assertActualAcknowledgement(link,authority);
                server.assertConfirmedGOff();
            }
        }
    }

    public static final class GOnActualInputProfile {
        @Test void actualKeyboardFrameTraversesOwnedGOnInputAndAdvancesFree(
                @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            try (var client = new Fixture();
                 var server = new SleepingNativeResumeServerTestSupport(directory, client.mc.player.getUniqueID(), false)) {
                var link = new SleepWireLink(client, server);
                // try-with-resources preserves the original failure if lifecycle cleanup also fails.
                try (var keys = new ActualInputKeys(client.mc);
                     AutoCloseable releaseWorld = () -> client.mc.loadWorld(null);
                     AutoCloseable restoreIntent = () -> {
                         if (client.mc.player != null
                                 && wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG()) {
                             keys.neutral();
                             net.minecraft.client.settings.KeyBinding.onTick(ClientGravityTicker.ZERO_G_KEY.getKeyCode());
                             client.tickEnd(); link.pump();
                             assertFalse(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG(),
                                     "cleanup must consume the real G OFF edge");
                         }
                     }) {
                    link.pump();
                    server.assertEarlyFlightHandshakeRequest();
                    assertEquals(1, link.flightCapabilityResponses, link::loginDiagnostics);
                    server.assertLoggedIn();
                    server.prepareNoReferenceAdmission();
                    assertEquals(1, link.joinGames, link::loginDiagnostics);
                    assertTrue(link.actualConfirmIds.contains(link.lastTeleportId), link::loginDiagnostics);
                    server.tick(); link.pump();
                    client.tickEnd(); link.pump();
                    client.tickStart(); client.tickEnd(); link.pump();
                    assertEquals(GIntentProtocol.State.OFF, link.gClientPackets.get(1).state(), link::loginDiagnostics);
                    server.assertConfirmedGOff();
                    server.tick(); link.pump(); link.position();
                    server.tick(); link.pump(); link.position();
                    server.assertOwned();
                    server.assertNoReferenceEnvironment();
                    assertInstanceOf(PlayerBodyShape.FreeCapsule.class, server.accepted().placement().shape());
                    assertFalse(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG(),
                            "isolated profile must start with the actual OFF key intent");
                    // The sleep fixture logs in using the real server's SURVIVAL default. G is
                    // Creative-only: use the native game-type operation and its emitted wire packets,
                    // rather than setting capabilities or bypassing the G eligibility exchange.
                    int previousModes=link.creativeModePackets;
                    int previousAbilities=link.creativeAbilityPackets;
                    server.player().setGameType(GameType.CREATIVE);
                    link.pump();
                    assertTrue(link.creativeModePackets>previousModes,"real setGameType emitted ChangeGameState(3,CREATIVE)");
                    assertTrue(link.creativeAbilityPackets>previousAbilities,"real setGameType emitted Creative PlayerAbilities");
                    assertEquals(GameType.CREATIVE,server.player().interactionManager.getGameType());
                    assertTrue(server.player().isCreative(),link::loginDiagnostics);
                    assertFalse(server.player().isSpectator());
                    assertEquals(GameType.CREATIVE,client.mc.playerController.getCurrentGameType());
                    assertTrue(client.mc.player.capabilities.isCreativeMode);
                    assertTrue(client.mc.player.isCreative(),link::loginDiagnostics);
                    assertFalse(client.mc.player.isSpectator());
                    System.out.println("UG_G_CREATIVE_ELIGIBILITY joinGame="+link.joinedGameType
                            +" server="+server.player().interactionManager.getGameType()
                            +" client="+client.mc.playerController.getCurrentGameType()
                            +" serverCreative="+server.player().isCreative()+" clientCreative="+client.mc.player.isCreative()
                            +" modePackets="+link.creativeModePackets+" abilityPackets="+link.creativeAbilityPackets);
                    // JoinGame changes the real GUI/focus after ActualInputKeys captured the host state.
                    // Enter the public vanilla sampling lifecycle before attempting an in-game key edge.
                    var joinedScreen = client.mc.currentScreen;
                    client.mc.currentScreen=null; client.mc.inGameHasFocus=true;
                    neofontrender.addons.input.VanillaInputBridge.install();
                    neofontrender.addons.input.VanillaInputBridge.capture(client.mc, 0, 0);
                    var neutralFrame=neofontrender.addons.api.input.InputApi.beginFrame(0, true);
                    assertTrue(neutralFrame.getSampleId()>0 && neutralFrame.getContext().isGameFocused());
                    var gKey=ClientGravityTicker.ZERO_G_KEY;
                    int gCode=gKey.getKeyCode();
                    System.out.println("UG_G_ACTUAL_KEY_BOOT code="+gCode+" context="+gKey.getKeyConflictContext()
                            +" modifier="+gKey.getKeyModifier()+" contextActive="+gKey.getKeyConflictContext().isActive()
                            +" activeMatches="+gKey.isActiveAndMatches(gCode)
                            +" registered="+java.util.Arrays.stream(client.mc.gameSettings.keyBindings).anyMatch(key -> key==gKey)
                            +" joinedScreen="+(joinedScreen==null ? "null" : joinedScreen.getClass().getName())
                            +" owned="+PlayerPhysicsClient.owns(client.mc.player)
                            +" requested="+wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG());
                    assertNotEquals(0,gCode,"actual G binding is not unbound");
                    assertTrue(java.util.Arrays.stream(client.mc.gameSettings.keyBindings).anyMatch(key -> key==gKey),
                            "ClientProxy registered the exact G KeyBinding on the actual GameSettings");
                    assertTrue(gKey.isActiveAndMatches(gCode),"actual G conflict context/modifier accepts its configured key");
                    // Observe public map routing without fabricating a press counter. The probe drains one
                    // edge, then the same public routing queues exactly one edge for the real END listener.
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(gCode, true);
                    net.minecraft.client.settings.KeyBinding.onTick(gCode);
                    assertTrue(gKey.isPressed(),"FIRST_RETURN: KeyBinding.onTick did not route to the exact registered G key");
                    assertFalse(gKey.isPressed(),"isolated G fixture starts with no pre-existing queued presses");
                    net.minecraft.client.settings.KeyBinding.onTick(gCode);
                    client.tickEnd();
                    assertTrue(wi.unigrav.core.client.boost.RemoteBoostClient.requestedPersonalZeroG(),
                            "FIRST_RETURN: real remote END key drain did not retain the queued G ON intent");
                    link.pump();
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(ClientGravityTicker.ZERO_G_KEY.getKeyCode(), false);
                    assertTrue(link.gClientPackets.stream().anyMatch(packet -> packet.kind()==GIntentProtocol.Kind.INTENT
                            && packet.state()==GIntentProtocol.State.ON), link::loginDiagnostics);
                    assertTrue(wi.unigrav.core.player.GIntentServer.INSTANCE.observe(server.player()).orElseThrow().active(),
                            link::loginDiagnostics);
                    assertTrue(ClientGravityTicker.isPersonalZeroGActive(client.mc.player), link::loginDiagnostics);
                    client.mc.currentScreen=null; client.mc.inGameHasFocus=true;
                    wi.unigrav.core.client.camera.RevoUiSixDofCamera.install(UnigravMod.entityBinder);
                    neofontrender.addons.input.VanillaInputBridge.install();
                    var start = server.accepted();
                    long lastInput = -1;
                    for (int tick=0; tick<4; tick++) {
                        net.minecraft.client.settings.KeyBinding.setKeyBindState(client.mc.gameSettings.keyBindForward.getKeyCode(), true);
                        neofontrender.addons.input.VanillaInputBridge.capture(client.mc, 0, 0);
                        var frame = neofontrender.addons.api.input.InputApi.beginFrame(0, true);
                        assertSame(frame, neofontrender.addons.api.input.InputApi.getFrame(0));
                        assertTrue(frame.getSampleId()>0 && frame.getContext().isGameFocused());
                        var owner = new net.minecraft.util.ResourceLocation("unigrav", "six_dof_camera");
                        for (var action : java.util.List.of(
                                neofontrender.addons.api.input.InputAction.PLAYER_MOVE_FORWARD,
                                neofontrender.addons.api.input.InputAction.CAMERA_TRANSLATE_X,
                                neofontrender.addons.api.input.InputAction.CAMERA_TRANSLATE_Y,
                                neofontrender.addons.api.input.InputAction.CAMERA_TRANSLATE_Z))
                            assertEquals(owner, frame.owner(action), () -> "actual router ownership: "
                                    + neofontrender.addons.api.input.InputApi.diagnostics());
                        assertEquals(neofontrender.addons.api.input.InputDisposition.BLOCK,
                                frame.disposition(neofontrender.addons.api.input.InputAction.PLAYER_MOVE_FORWARD));
                        assertEquals(0F, frame.get(neofontrender.addons.api.input.InputAction.PLAYER_MOVE_FORWARD).getAxis());
                        assertEquals(neofontrender.addons.api.input.InputDisposition.CLAIM,
                                frame.disposition(neofontrender.addons.api.input.InputAction.CAMERA_TRANSLATE_Z));
                        assertEquals(1F, frame.get(neofontrender.addons.api.input.InputAction.CAMERA_TRANSLATE_Z).getAxis());
                        client.tickStart();
                        var routed = ClientBoostRouting.queryPhysical(client.mc.player, null);
                        assertNotNull(routed, "actual Revo physical provider is active");
                        assertTrue(routed.translation().lengthSquared()>0, "actual routed translation is nonzero");
                        assertTrue(routed.desiredBodyAttitude().isPresent(), "actual Revo supplies the current Q");
                        int firstInput = link.actualInputs.size();
                        client.tickEnd(); link.pump();
                        assertEquals(firstInput+1, link.actualInputs.size(), "one END emits one actual INPUT");
                        var input = link.actualInputs.get(firstInput);
                        assertEquals(start.scope(), input.scope());
                        assertTrue(input.sequence()>lastInput); lastInput=input.sequence();
                        var wireControls = input.controls().orElseThrow();
                        assertEquals(routed.translation(), wireControls.translation(), "END uses the actual routed world intent");
                        assertEquals(routed.desiredBodyAttitude(), wireControls.desiredBodyAttitude());
                        var before = server.accepted();
                        server.tick(); link.pump(); server.assertOwned();
                        var after = server.accepted();
                        assertEquals(start.scope(), after.scope());
                        assertInstanceOf(PlayerBodyShape.FreeCapsule.class, after.placement().shape());
                        assertTrue(after.revision()>before.revision(), link::loginDiagnostics);
                        assertNotEquals(before.placement().center(), after.placement().center(), link::loginDiagnostics);
                        SleepNativeResumeProfile.assertSameSnapshot(server, client, start.scope(), link);
                    }
                    assertNotEquals(start.placement().center(), server.accepted().placement().center());
                    // ACK the exact final delivered STATE using another real neutral sampled END.
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(client.mc.gameSettings.keyBindForward.getKeyCode(), false);
                    neofontrender.addons.input.VanillaInputBridge.capture(client.mc, 0, 0);
                    neofontrender.addons.api.input.InputApi.beginFrame(0, true);
                    client.tickStart(); client.tickEnd(); link.pump();
                    var authority = AquaNativeEyeProfile.installedEyeAuthority(link, server.accepted(), server.accepted());
                    AquaNativeEyeProfile.assertActualAcknowledgement(link, authority);
                    // Restore explicit key intent through the same real route before disconnecting.
                    net.minecraft.client.settings.KeyBinding.onTick(ClientGravityTicker.ZERO_G_KEY.getKeyCode());
                    client.tickEnd(); link.pump();
                    server.assertConfirmedGOff();
                }
            }
        }

        /** Own only test key/focus state; do not replace or force a routing context. */
        private static final class ActualInputKeys implements AutoCloseable {
            private final Minecraft mc;
            private final boolean focus;
            private final GuiScreen screen;
            private final net.minecraft.client.settings.KeyBinding[] keys;
            private final boolean[] down;
            ActualInputKeys(Minecraft mc) {
                this.mc=mc; focus=mc.inGameHasFocus; screen=mc.currentScreen;
                var settings=mc.gameSettings;
                keys=new net.minecraft.client.settings.KeyBinding[]{settings.keyBindForward,settings.keyBindBack,
                        settings.keyBindLeft,settings.keyBindRight,settings.keyBindJump,settings.keyBindSneak,
                        settings.keyBindSprint,ClientGravityTicker.ZERO_G_KEY,ClientGravityTicker.BOOST_KEY};
                down=new boolean[keys.length];
                for(int index=0;index<keys.length;index++) {
                    down[index]=keys[index].isKeyDown();
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(keys[index].getKeyCode(),false);
                }
                mc.inGameHasFocus=true; mc.currentScreen=null;
            }
            private void neutral() {
                for(var key:keys) net.minecraft.client.settings.KeyBinding.setKeyBindState(key.getKeyCode(),false);
            }
            @Override public void close() {
                for(int index=0;index<keys.length;index++)
                    net.minecraft.client.settings.KeyBinding.setKeyBindState(keys[index].getKeyCode(),down[index]);
                neofontrender.addons.input.VanillaInputBridge.reset();
                neofontrender.addons.api.input.InputApi.flush(neofontrender.addons.api.input.InputFlushReason.FOCUS_LOST);
                mc.inGameHasFocus=focus; mc.currentScreen=screen;
            }
        }
    }

    /** Actual transformed Aqua/Artemis public ABI; selected independently, never part of raw25. */
    public static final class AquaNativeEyeProfile {
        @Test void actualAquaCrouchEyePublishesAndReversesThroughOwnedState(
                @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            assertTrue(net.minecraftforge.fml.common.Loader.isModLoaded("aquaacrobatics"));
            assertTrue(net.minecraftforge.fml.common.Loader.isModLoaded("artemislib"));
            try (var client = new Fixture();
                 var server = new SleepingNativeResumeServerTestSupport(directory, client.mc.player.getUniqueID(), false)) {
                try (var keys = new GOnActualInputProfile.ActualInputKeys(client.mc);
                     AutoCloseable releaseWorld = () -> client.mc.loadWorld(null)) {
                    var link = new SleepWireLink(client, server);
                    link.pump();
                    server.assertEarlyFlightHandshakeRequest();
                    assertEquals(1, link.flightCapabilityResponses);
                    server.assertLoggedIn();
                    server.prepareNoReferenceAdmission();
                    assertEquals(1, link.joinGames);
                    assertTrue(link.actualConfirmIds.contains(link.lastTeleportId));
                    // These are the same finite real handshake stages as the accepted sleep fixture.
                    server.tick(); link.pump();
                    assertEquals(1, link.gOffers.size(), link::loginDiagnostics);
                    client.tickEnd(); link.pump();
                    assertEquals(GIntentProtocol.Kind.HELLO, link.gClientPackets.get(0).kind());
                    assertEquals(GIntentProtocol.State.UNKNOWN, link.gStates.get(0).state());
                    client.tickStart(); client.tickEnd(); link.pump();
                    assertEquals(GIntentProtocol.Kind.INTENT, link.gClientPackets.get(1).kind());
                    assertEquals(GIntentProtocol.State.OFF, link.gClientPackets.get(1).state());
                    server.assertConfirmedGOff();
                    server.tick(); link.pump(); link.position();
                    server.tick(); link.pump(); link.position();
                    server.assertOwned();
                    server.assertNoReferenceEnvironment();
                    assertTrue(link.actualBodyReferenceIntents.stream().allMatch(intent -> intent.action()==PlayerPhysicsProtocol.BodyReferenceAction.NONE),
                            () -> "Aqua no-reference admission sent manual body intent: "+link.actualBodyReferenceIntents);
                    var initial = server.accepted();
                    assertInstanceOf(PlayerBodyShape.FreeCapsule.class, initial.placement().shape());
                    var authority=link.deliveredAuthorities.values().stream()
                            .filter(packet -> packet.snapshot().map(snapshot -> snapshot.equals(initial)).orElse(false))
                            .findFirst().orElseThrow(() -> new AssertionError("initial Free snapshot has no actual delivered authority"));
                    assertTrue(authority.bodyReferenceState().isEmpty(),"Free authority must not publish an effective Cardinal reference");

                    SleepNativeResumeProfile.assertSameSnapshot(server, client, initial.scope(), link);
                    assertNotNull(server.player().getEntityAttribute(ArtemisLibAttributes.ENTITY_HEIGHT));
                    assertNotNull(server.player().getEntityAttribute(ArtemisLibAttributes.ENTITY_WIDTH));
                    assertNotNull(client.mc.player.getEntityAttribute(ArtemisLibAttributes.ENTITY_HEIGHT));
                    assertNotNull(client.mc.player.getEntityAttribute(ArtemisLibAttributes.ENTITY_WIDTH));
                    assertInstanceOf(NativePoseEyeProducer.class, server.player());
                    assertInstanceOf(NativePoseEyeProducer.class, client.mc.player);
                    assertEquals(EntityPlayerSP.class, client.mc.player.getClass(), "actual JoinGame created the native Aqua-transformed client actor");
                    assertEquals(1.62F, server.player().getEyeHeight(), 0);
                    var standingProducer = ((NativePoseEyeProducer) server.player()).unigrav$nativePoseEyeProfile().orElseThrow();

                    // Native AQ lifecycle requires a real continuing sneak condition. A manual pose
                    // without that condition is legitimately reset by the next EntityPlayer update.
                    holdNativeSneak(client, server, true);
                    new CommonProxy().runNativePose(server.player(), () ->
                            ((IPlayerResizeable) server.player()).setPose(Pose.CROUCHING));
                    var crouchMetadata = server.accepted();
                    assertEquals(Pose.CROUCHING, ((IPlayerResizeable) server.player()).getPose());
                    assertEquals(1.27F, server.player().getEyeHeight(), 0);
                    assertEquals((double) 1.27F, crouchMetadata.placement().shape().dimensions().eyeHeight());
                    assertEquals(Math.incrementExact(initial.revision()), crouchMetadata.revision());
                    assertEquals(initial.scope(), crouchMetadata.scope());
                    assertEquals(initial.placement().capsule(), crouchMetadata.placement().capsule());
                    assertEquals(initial.placement().transport(), crouchMetadata.placement().transport());
                    assertEquals(initial.worldVelocity(), crouchMetadata.worldVelocity());
                    var crouchProducer = ((NativePoseEyeProducer) server.player()).unigrav$nativePoseEyeProfile().orElseThrow();
                    assertTrue(crouchProducer.revision() > standingProducer.revision());
                    assertEquals(1.27F, crouchProducer.cachedEye(), 0);
                    assertEquals(1F, crouchProducer.scale(), 0, "Artemis eye factor is one outside SWIMMING");
                    PlayerPhysicsClient.runNativePose(client.mc.player, () ->
                            ((IPlayerResizeable) client.mc.player).setPose(Pose.CROUCHING));
                    // Dispatch the actual public FML player event, including the installed Artemis listener.
                    FMLCommonHandler.instance().onPlayerPostTick(server.player());
                    FMLCommonHandler.instance().onPlayerPostTick(client.mc.player);
                    server.tick(); link.pump();
                    assertDeliveredEye(link, crouchMetadata);
                    assertNativePhase(client, server, link, Pose.CROUCHING, true, 1.27F);
                    SleepNativeResumeProfile.assertSameSnapshot(server, client, initial.scope(), link);
                    var crouchAuthority = installedEyeAuthority(link, crouchMetadata, server.accepted());
                    client.tickEnd(); link.pump();
                    assertActualAcknowledgement(link, crouchAuthority);

                    holdNativeSneak(client, server, false);
                    var beforeStanding = server.accepted();
                    new CommonProxy().runNativePose(server.player(), () ->
                            ((IPlayerResizeable) server.player()).setPose(Pose.STANDING));
                    var standingMetadata = server.accepted();
                    assertEquals(Pose.STANDING, ((IPlayerResizeable) server.player()).getPose());
                    assertEquals(1.62F, server.player().getEyeHeight(), 0);
                    assertEquals((double) 1.62F, standingMetadata.placement().shape().dimensions().eyeHeight());
                    assertEquals(Math.incrementExact(beforeStanding.revision()), standingMetadata.revision());
                    assertEquals(beforeStanding.placement().capsule(), standingMetadata.placement().capsule());
                    assertEquals(beforeStanding.placement().transport(), standingMetadata.placement().transport());
                    assertEquals(beforeStanding.worldVelocity(), standingMetadata.worldVelocity());
                    assertTrue(((NativePoseEyeProducer) server.player()).unigrav$nativePoseEyeProfile().orElseThrow().revision()
                            > crouchProducer.revision());
                    PlayerPhysicsClient.runNativePose(client.mc.player, () ->
                            ((IPlayerResizeable) client.mc.player).setPose(Pose.STANDING));
                    FMLCommonHandler.instance().onPlayerPostTick(server.player());
                    FMLCommonHandler.instance().onPlayerPostTick(client.mc.player);
                    server.tick(); link.pump();
                    assertDeliveredEye(link, standingMetadata);
                    assertNativePhase(client, server, link, Pose.STANDING, false, 1.62F);
                    SleepNativeResumeProfile.assertSameSnapshot(server, client, initial.scope(), link);
                    assertEquals(1.62F, client.mc.player.getEyeHeight(), 0);
                    var standingAuthority = installedEyeAuthority(link, standingMetadata, server.accepted());
                    client.tickEnd(); link.pump();
                    assertActualAcknowledgement(link, standingAuthority);
                    assertTrue(PlayerPhysicsServer.INSTANCE.drivesMotion(server.player()));
                    assertTrue(PlayerPhysicsClient.drivesMotion(client.mc.player));
                }
            }
        }
        /** Native-actor/AQ API test only; KeyBinding state is not a claim about LWJGL INPUT.sneak. */
        private static void holdNativeSneak(Fixture client, SleepingNativeResumeServerTestSupport server,
                boolean held) {
            client.mc.currentScreen=null; client.mc.inGameHasFocus=true;
            var sneak=client.mc.gameSettings.keyBindSneak;
            net.minecraft.client.settings.KeyBinding.setKeyBindState(sneak.getKeyCode(),held);
            assertEquals(held,sneak.isKeyDown(),"public key state is held for native MovementInput");
            assertNotNull(client.mc.player.movementInput);
            client.mc.player.movementInput.updatePlayerMoveState();
            client.mc.player.setSneaking(held);
            server.player().setSneaking(held);
            assertEquals(held,client.mc.player.movementInput.sneak);
            assertEquals(held,((IPlayerResizeable)client.mc.player).isActuallySneaking());
            assertEquals(held,((IPlayerResizeable)server.player()).isActuallySneaking());
        }
        private static void assertNativePhase(Fixture client, SleepingNativeResumeServerTestSupport server,
                SleepWireLink link, Pose expected, boolean sneak, float eye) {
            String diagnostics="UG_AQ_NATIVE_PHASE expected="+expected+" serverPose="+((IPlayerResizeable)server.player()).getPose()
                    +" clientPose="+((IPlayerResizeable)client.mc.player).getPose()
                    +" serverSneak="+server.player().isSneaking()
                    +" serverActualSneak="+((IPlayerResizeable)server.player()).isActuallySneaking()
                    +" clientSneak="+client.mc.player.isSneaking()
                    +" clientActualSneak="+((IPlayerResizeable)client.mc.player).isActuallySneaking()
                    +" movementSneak="+client.mc.player.movementInput.sneak
                    +" serverEye="+server.player().getEyeHeight()+" clientEye="+client.mc.player.getEyeHeight()
                    +" "+link.loginDiagnostics();
            System.out.println(diagnostics);
            assertEquals(expected,((IPlayerResizeable)server.player()).getPose(),diagnostics);
            assertEquals(expected,((IPlayerResizeable)client.mc.player).getPose(),diagnostics);
            assertEquals(sneak,((IPlayerResizeable)server.player()).isActuallySneaking(),diagnostics);
            assertEquals(sneak,((IPlayerResizeable)client.mc.player).isActuallySneaking(),diagnostics);
            assertEquals(eye,server.player().getEyeHeight(),0,diagnostics);
            assertEquals(eye,client.mc.player.getEyeHeight(),0,diagnostics);
        }
        private static Packet installedEyeAuthority(SleepWireLink link, PlayerPhysicalSnapshot metadata,
                PlayerPhysicalSnapshot installed) {
            // A later same-tick physical STATE may supersede the metadata-only STATE. Session
            // legitimately ACKs the final installed authority, retaining the same trusted eye.
            assertEquals(metadata.scope(), installed.scope());
            assertEquals(metadata.placement().shape().dimensions().eyeHeight(),
                    installed.placement().shape().dimensions().eyeHeight(), 0);
            assertTrue(installed.revision() >= metadata.revision());
            return link.deliveredAuthorities.values().stream()
                    .filter(packet -> packet.kind() == Kind.STATE && packet.snapshot().filter(installed::equals).isPresent())
                    .max(java.util.Comparator.comparingLong(Packet::sequence))
                    .orElseThrow(() -> new AssertionError("Final installed eye authority missing: " + link.loginDiagnostics()));
        }
        private static void assertActualAcknowledgement(SleepWireLink link, Packet authority) {
            var expected = new PlayerPhysicsProtocol.BodyReferenceAcknowledgement(
                    authority.sequence(), authority.snapshot().orElseThrow().revision());
            assertTrue(link.actualAcknowledgementInputs.stream().anyMatch(input -> input.kind() == Kind.INPUT
                    && input.scope().equals(authority.scope()) && input.offerSequence() == authority.offerSequence()
                    && input.bodyReferenceAcknowledgement().filter(expected::equals).isPresent()),
                    () -> "No actual client INPUT acknowledged exact installed eye authority " + authority
                            + "; observed=" + link.actualAcknowledgementInputs + "; " + link.loginDiagnostics());
        }
        private static void assertDeliveredEye(SleepWireLink link, PlayerPhysicalSnapshot metadata) {
            var authority = link.deliveredAuthorities.values().stream()
                    .filter(packet -> packet.kind() == Kind.STATE && packet.snapshot().filter(metadata::equals).isPresent())
                    .findFirst().orElseThrow(() -> new AssertionError("Actual eye metadata STATE missing: " + link.loginDiagnostics()));
            assertFalse(PlayerPhysicsProtocol.nativeRestorePurpose(authority));
            assertEquals(metadata.scope(), authority.scope());
            assertEquals(metadata.revision(), authority.snapshot().orElseThrow().revision());
        }
    }

    /** Selected only by the independent sleepNativeResume profile; never part of raw25. */
    public static final class SleepNativeResumeProfile {
        @Test void actualSleepWakeCompletesNativeTransferAndResumesOriginalBudget(
                @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
            try (var client = new Fixture();
                 var server = new SleepingNativeResumeServerTestSupport(directory,client.mc.player.getUniqueID())) {
                try {
                    var link=new SleepWireLink(client,server);
                    link.pump();
                    server.assertEarlyFlightHandshakeRequest();
                    assertEquals(1,link.flightCapabilityResponses,
                            "the actual early UIE request receives exactly one real capability response");
                    server.assertLoggedIn();
                    assertEquals(1,link.joinGames,"normal FML handshake delivers exactly one actual JoinGame");
                    assertTrue(link.lastTeleportId>=0&&link.actualConfirmIds.contains(link.lastTeleportId),
                            "initial real login correction is applied and confirmed before sleep");
                    // END drives the real G handshake; UNKNOWN is not an implicit OFF.
                    server.tick();link.pump();
                    assertEquals(1,link.gOffers.size(),link::loginDiagnostics);
                    assertTrue(link.gClientPackets.isEmpty(),link::loginDiagnostics);
                    client.tickEnd();link.pump();
                    assertEquals(1,link.gClientPackets.size(),link::loginDiagnostics);
                    assertEquals(GIntentProtocol.Kind.HELLO,link.gClientPackets.get(0).kind());
                    assertEquals(link.gOffers.get(0).key(),link.gClientPackets.get(0).key());
                    assertEquals(1,link.gStates.size(),link::loginDiagnostics);
                    assertEquals(GIntentProtocol.State.UNKNOWN,link.gStates.get(0).state());
                    client.tickStart();client.tickEnd();link.pump();
                    assertEquals(2,link.gClientPackets.size(),link::loginDiagnostics);
                    var intent=link.gClientPackets.get(1);
                    assertEquals(GIntentProtocol.Kind.INTENT,intent.kind());
                    assertEquals(GIntentProtocol.State.OFF,intent.state());
                    assertEquals(link.gOffers.get(0).key(),intent.key());
                    assertEquals(2,link.gStates.size(),link::loginDiagnostics);
                    assertEquals(intent.key(),link.gStates.get(1).key());
                    assertEquals(intent.sequence(),link.gStates.get(1).sequence());
                    assertEquals(intent.state(),link.gStates.get(1).state());
                    server.assertConfirmedGOff();
                    // Admission still needs the original two distinct world-tick position receipts.
                    server.tick();link.pump();link.position();
                    server.tick();link.pump();link.position();
                    System.out.println("UG-SLEEP-ADMISSION "+link.loginDiagnostics());
                    server.assertOwned();
                    var original=server.accepted();
                    assertSameSnapshot(server,client,original.scope(),link);
                    server.sleep();link.pump();
                    int sleepId=link.lastTeleportId;
                    assertTrue(sleepId>=0,"sleep emitted and applied an actual TP");
                    server.tick();link.pump();client.tickEnd();link.pump();
                    assertTrue(link.restoreAcknowledgements>0,"actual client ordinary INPUT acknowledged restore STATE");
                    server.tick();link.pump();
                    assertTrue(link.nativeAcknowledgements>0,"actual client applied and ACKed frozen native base");
                    server.tick();link.pump();server.assertDelegated();
                    new CommonProxy().runLegacyMotion(server.player(),server.player()::jump);
                    assertTrue(server.player().motionY>.4,"the delegated native jump actually executes");
                    server.wake();link.pump();
                    assertEquals(1,link.wakeAnimations,"real tracking-and-self delivered exactly one wake animation");
                    assertFalse(server.player().isPlayerSleeping(),"actual server wake completed");
                    assertFalse(client.mc.player.isPlayerSleeping(),"actual client consumed the native wake animation");
                    assertNotEquals(sleepId,link.lastTeleportId,"wake has its own real TP id");
                    assertTrue(link.correctionAcknowledgements>0,()->"RETURN-gated real client emitted completion control ACK; "
                            +link.loginDiagnostics()+" lastTeleportId="+link.lastTeleportId
                            +" actualConfirmIds="+link.actualConfirmIds+" completion="+link.completion
                            +" server="+server.admissionDiagnostics());
                    server.tick();link.pump();link.position();
                    server.tick();link.pump();link.position();
                    assertTrue(PlayerPhysicsServer.INSTANCE.drivesMotion(server.player()),()->"UG-SLEEP-WAKE-RESUME "
                            +link.loginDiagnostics()+" confirmIds="+link.actualConfirmIds+" completion="+link.completion
                            +" server="+server.admissionDiagnostics()
                            +" actor[width="+server.player().width+",height="+server.player().height
                            +",eye="+server.player().getEyeHeight()+",position="+server.player().getPositionVector()
                            +",motion=("+server.player().motionX+","+server.player().motionY+","+server.player().motionZ+")"
                            +",velocityChanged="+server.player().velocityChanged+",sleep="+server.player().isPlayerSleeping()
                            +",noClip="+server.player().noClip+"] worldTick="+server.player().world.getTotalWorldTime()
                            +" nativeReceipt="+NativePlayerMovement.INSTANCE.current(server.player().connection)
                            +" nativeCenters="+NativePlayerMovement.INSTANCE.acceptedCenters(server.player().connection));
                    server.assertOwned();
                    assertSameSnapshot(server,client,original.scope(),link);
                    client.tickEnd();link.pump();
                    assertTrue(link.resumedOrdinaryAcknowledgements>0,"actual resumed OWNED/STATE received its exact ordinary installation ACK");
                    server.assertFinalBudget(original.scope());link.pump();
                    assertEquals(37,PlayerPhysicsClient.acceptedPresentation(client.mc.player).orElseThrow().worldVelocity().x(),1e-8,
                            ()->"UG-SLEEP-FINAL-BUDGET "+link.loginDiagnostics()
                                    +" deliveredAuthorities="+link.deliveredAuthorities
                                    +" serverAccepted="+server.accepted()
                                    +" clientAccepted="+PlayerPhysicsClient.acceptedPresentation(client.mc.player)
                                    +" clientProven="+PlayerPhysicsClient.currentProvenPresentation(client.mc.player)
                                    +" clientOwns="+PlayerPhysicsClient.owns(client.mc.player)
                                    +" clientDrives="+PlayerPhysicsClient.drivesMotion(client.mc.player)
                                    +" clientFence="+PlayerPhysicsClient.isInstallationFenced(client.mc.player)
                                    +" mirrorMatchesServer="+PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(
                                            client.mc.player,server.accepted()));
                    assertTrue(link.actualConfirmIds.contains(sleepId));
                    assertTrue(link.actualConfirmIds.contains(link.lastTeleportId));
                    assertTrue(link.cuesValidatedByControl,"only actual RETURN-gated control ACK satisfies completion");
                } finally {
                    // JoinGame creates a real WorldClient/EntityPlayerSP distinct from the initial route probe.
                    client.mc.loadWorld(null);
                }
            }
        }
        private static void assertSameSnapshot(SleepingNativeResumeServerTestSupport server,
                Fixture client,PlayerPhysicalSnapshot.Scope scope,SleepWireLink link) {
            var accepted=PlayerPhysicsClient.acceptedPresentation(client.mc.player).orElseThrow(()->
                    new NoSuchElementException("UG-SLEEP-PRESENTATION "+link.loginDiagnostics()
                            +" confirmIds="+link.actualConfirmIds+" completion="+link.completion
                            +" server="+server.admissionDiagnostics()+" serverAccepted="+server.accepted()
                            +" clientOwns="+PlayerPhysicsClient.owns(client.mc.player)
                            +" clientDrives="+PlayerPhysicsClient.drivesMotion(client.mc.player)
                            +" clientFence="+PlayerPhysicsClient.isInstallationFenced(client.mc.player)
                            +" clientProven="+PlayerPhysicsClient.currentProvenPresentation(client.mc.player)
                            +" mirrorMatchesServer="+PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(
                                    client.mc.player,server.accepted())
                            +" actor[width="+client.mc.player.width+",height="+client.mc.player.height
                            +",rawEye="+client.mc.player.eyeHeight+",effectiveEye="+client.mc.player.getEyeHeight()
                            +",position="+client.mc.player.getPositionVector()+",bounds="+client.mc.player.getEntityBoundingBox()
                            +",riding="+client.mc.player.isRiding()+",sleep="+client.mc.player.isPlayerSleeping()
                            +",spectator="+client.mc.player.isSpectator()+",noClip="+client.mc.player.noClip+"]"));
            assertEquals(scope,accepted.scope());assertEquals(server.accepted(),accepted);
            assertTrue(PlayerPhysicsClient.drivesMotion(client.mc.player));
            assertTrue(PlayerPhysicsClientMirror.matchesAcceptedPresentationGeometry(client.mc.player,accepted));
        }
    }

    /** Routes real packets in emitted order; no correction-stage/session flags are called here. */
    private static final class SleepWireLink {
        private final Fixture client;
        private final SleepingNativeResumeServerTestSupport server;
        private final RawFixturePacketDecoder decoder=new RawFixturePacketDecoder();
        private final java.util.Set<Integer> actualConfirmIds=new java.util.HashSet<>();
        private int lastTeleportId=-1,restoreAcknowledgements,nativeAcknowledgements,correctionAcknowledgements,joinGames;
        private int flightCapabilityResponses;
        private final java.util.List<Boolean> actualClientFlyingAbilities=new java.util.ArrayList<>();
        private GameType joinedGameType;
        private int creativeModePackets,creativeAbilityPackets;
        private int wakeAnimations;
        private int resumedOrdinaryAcknowledgements;
        private record AuthorityIdentity(PlayerPhysicalSnapshot.Scope scope,long proposal,long sequence) { }
        private final Map<AuthorityIdentity,Packet> deliveredAuthorities=new HashMap<>();
        private final java.util.List<PlayerPhysicsProtocol.BodyReferenceIntent> actualBodyReferenceIntents=new java.util.ArrayList<>();
        private final java.util.List<Packet> actualAcknowledgementInputs=new java.util.ArrayList<>();
        private final java.util.List<Packet> actualInputs=new java.util.ArrayList<>();
        private final java.util.List<GIntentProtocol.Packet> gOffers=new java.util.ArrayList<>();
        private final java.util.List<GIntentProtocol.Packet> gStates=new java.util.ArrayList<>();
        private final java.util.List<GIntentProtocol.Packet> gClientPackets=new java.util.ArrayList<>();
        private final java.util.List<String> physicsWire=new java.util.ArrayList<>();
        private String loginDiagnostics(){return "G offers="+gOffers+" states="+gStates+" client="+gClientPackets
                +" physics="+physicsWire+" server="+server.admissionDiagnostics();}
        private GIntentProtocol.Packet inspectG(net.minecraft.network.PacketBuffer bytes){
            assertEquals(0,bytes.getUnsignedByte(bytes.readerIndex()));
            byte[] body=new byte[bytes.readableBytes()-1];bytes.getBytes(bytes.readerIndex()+1,body);
            return GIntentProtocol.decode(body);
        }
        private boolean cuesValidatedByControl;
        private Packet restoreState,nativeBase,completion;
        private final RawFixturePacketDecoder serverDecoder=new RawFixturePacketDecoder();
        private SleepWireLink(Fixture client,SleepingNativeResumeServerTestSupport server){this.client=client;this.server=server;}
        private void position() throws java.io.IOException {
            client.handler.sendPacket(new CPacketPlayer.Position(client.mc.player.posX,client.mc.player.posY,client.mc.player.posZ,false));
            pump();
        }
        private void pump() throws java.io.IOException {
            boolean progress;
            do {
                progress=false;
                for(Object outgoing:server.drain()){
                    progress=true;
                    if(outgoing instanceof FMLProxyPacket proxy){
                        for(var packet:proxy.toS3FPackets())toClient(packet);
                    }else if(outgoing instanceof net.minecraft.network.Packet<?> packet)toClient(packet);
                    else fail("Unknown actual server wire object: "+outgoing.getClass().getName());
                }
                client.tickStart();client.channel.runPendingTasks();client.channel.checkException();
                Object outgoing;
                while((outgoing=client.channel.readOutbound())!=null){
                    progress=true;
                    net.minecraft.network.Packet<?> packet;
                    if(outgoing instanceof FMLProxyPacket proxy)packet=proxy.toC17Packet();
                    else if(outgoing instanceof net.minecraft.network.Packet<?> nativePacket)packet=nativePacket;
                    else {fail("Unknown actual client wire object: "+outgoing.getClass().getName());return;}
                    if(packet instanceof CPacketConfirmTeleport confirm){
                        actualConfirmIds.add(confirm.getTeleportId());
                        assertEquals(lastTeleportId,confirm.getTeleportId(),"confirm follows the exact actually applied correction");
                    }
                    if(packet instanceof CPacketCustomPayload custom && GIntentProtocol.CHANNEL.equals(custom.getChannelName()))
                        gClientPackets.add(inspectG(custom.getBufferData()));
                    if(packet instanceof CPacketCustomPayload custom && CHANNEL.equals(custom.getChannelName())){
                        var bytes=custom.getBufferData();assertEquals(0,bytes.getUnsignedByte(bytes.readerIndex()));
                        byte[] body=new byte[bytes.readableBytes()-1];bytes.getBytes(bytes.readerIndex()+1,body);
                        var decoded=decoder.decode(body);
                        physicsWire.add("C>S "+decoded);
                        if(decoded.kind()==Kind.INPUT) actualInputs.add(decoded);
                        if(decoded.kind()==Kind.INPUT) actualBodyReferenceIntents.add(decoded.controls().orElseThrow().bodyReferenceIntent());
                        if(decoded.kind()!=Kind.APPLY && decoded.kind()!=Kind.RECOVER)
                            assertEquals(server.accepted().scope(),decoded.scope());
                        if(decoded.kind()==Kind.INPUT&&decoded.bodyReferenceAcknowledgement().isPresent()){
                            var ack=decoded.bodyReferenceAcknowledgement().orElseThrow();
                            var authority=deliveredAuthorities.get(new AuthorityIdentity(decoded.scope(),decoded.offerSequence(),ack.stateSequence()));
                            assertNotNull(authority,"INPUT ACK must name a real authority packet delivered to this client: "+decoded);
                            assertEquals(authority.scope(),decoded.scope());
                            assertEquals(authority.offerSequence(),decoded.offerSequence());
                            assertEquals(authority.sequence(),ack.stateSequence());
                            assertEquals(authority.snapshot().orElseThrow().revision(),ack.snapshotRevision());
                            actualAcknowledgementInputs.add(decoded);
                            if(PlayerPhysicsProtocol.nativeRestorePurpose(authority)){
                                assertNotNull(restoreState);
                                assertEquals(restoreState,authority,"native restore ACK must name the current exact restore packet");
                                restoreAcknowledgements++;
                            }else if(nativeBase!=null && completion!=null && authority.offerSequence()>nativeBase.offerSequence()){
                                resumedOrdinaryAcknowledgements++;
                            }
                        }
                        if(decoded.kind()==Kind.NATIVE_ACK){
                            var ack=(NativeHandoffAcknowledgement)decoded.nativeTransfer().orElseThrow();
                            assertNotNull(nativeBase);var base=(NativeHandoff)nativeBase.nativeTransfer().orElseThrow();
                            assertEquals(base.handoffId(),ack.handoffId());assertEquals(base.restoreRevision(),ack.restoreRevision());
                            assertEquals(nativeBase.offerSequence(),decoded.offerSequence());nativeAcknowledgements++;
                        }
                        if(decoded.kind()==Kind.NATIVE_CORRECTION_ACK){
                            var ack=(NativeCorrectionAcknowledgement)decoded.nativeTransfer().orElseThrow();
                            assertNotNull(completion);var completed=(NativeCorrectionCompleted)completion.nativeTransfer().orElseThrow();
                            assertEquals(completed.correctionIdentity(),ack.correctionIdentity());assertEquals(completion.sequence(),ack.completedSequence());
                            assertEquals(completion.offerSequence(),decoded.offerSequence());
                            assertEquals(lastTeleportId,ack.nativeTeleportId());assertTrue(actualConfirmIds.contains(lastTeleportId));
                            correctionAcknowledgements++;cuesValidatedByControl=true;
                        }
                    }
                    if(packet instanceof net.minecraft.network.play.client.CPacketPlayerAbilities abilities)
                        actualClientFlyingAbilities.add(abilities.isFlying());
                    server.receive(packet);
                }
            }while(progress);
        }
        private void toClient(net.minecraft.network.Packet<?> packet){
            Packet deliveredAuthority=null;
            if(packet instanceof SPacketJoinGame joined) joinedGameType=joined.getGameType();
            if(packet instanceof net.minecraft.network.play.server.SPacketChangeGameState change
                    && change.getGameState()==3 && change.getValue()==GameType.CREATIVE.getID()) creativeModePackets++;
            if(packet instanceof net.minecraft.network.play.server.SPacketPlayerAbilities abilities
                    && abilities.isCreativeMode()) creativeAbilityPackets++;
            if(packet instanceof SPacketAnimation animation && animation.getAnimationType()==2){
                assertEquals(server.player().getEntityId(),animation.getEntityID(),"wake animation names the actual logged-in player");
                wakeAnimations++;
            }
            EntityPlayerSP preJoinPlayer=null;
            if(packet instanceof SPacketJoinGame) {
                assertEquals(0,joinGames,"this login fixture admits exactly its first real JoinGame");
                assertSame(client.player,client.mc.player,"REGISTER kept the original client-to-server route");
                assertSame(client.world,client.mc.world);
                assertSame(client.handler,client.mc.player.connection);
                assertSame(client.manager,client.handler.getNetworkManager());
                assertFalse(PlayerPhysicsClient.owns(client.mc.player),"pre-login route has no physical owner to discard");
                preJoinPlayer=client.mc.player;
                // loadWorld(newWorld) creates the real player only when player is null. Retain the
                // handshake route until this exact JoinGame; let vanilla unload the old probe world.
                client.mc.player=null;
            }
            if(packet instanceof SPacketCustomPayload custom && "nfr_ui_flight".equals(custom.getChannelName())) {
                var bytes=custom.getBufferData();
                if(bytes.isReadable() && bytes.getUnsignedByte(bytes.readerIndex())==2) flightCapabilityResponses++;
            }
            if(packet instanceof SPacketCustomPayload custom && GIntentProtocol.CHANNEL.equals(custom.getChannelName())) {
                var observed=inspectG(custom.getBufferData());
                if(observed.kind()==GIntentProtocol.Kind.OFFER)gOffers.add(observed);
                else {assertEquals(GIntentProtocol.Kind.STATE,observed.kind());gStates.add(observed);}
            }
            if(packet instanceof SPacketCustomPayload custom && CHANNEL.equals(custom.getChannelName())){
                var bytes=custom.getBufferData();assertEquals(0,bytes.getUnsignedByte(bytes.readerIndex()));
                byte[] body=new byte[bytes.readableBytes()-1];bytes.getBytes(bytes.readerIndex()+1,body);
                var decoded=serverDecoder.decode(body);
                if(decoded.kind()==Kind.STATE || decoded.kind()==Kind.OWNED)deliveredAuthority=decoded;
                physicsWire.add("S>C "+decoded);
                if(decoded.kind()==Kind.STATE&&decoded.bodyReferenceState().map(reference->reference.transition()
                        ==PlayerBodyReference.Transition.NATIVE_RESTORE_PENDING).orElse(false))restoreState=decoded;
                if(decoded.kind()==Kind.NATIVE){
                    assertNotNull(restoreState);var base=(NativeHandoff)decoded.nativeTransfer().orElseThrow();
                    assertEquals(restoreState.scope(),decoded.scope());assertEquals(restoreState.snapshot().orElseThrow().revision(),base.restoreRevision());
                    assertTrue(restoreAcknowledgements>0);nativeBase=decoded;
                }
                if(decoded.kind()==Kind.NATIVE_CORRECTION_COMPLETE)completion=decoded;
            }
            if(packet instanceof SPacketPlayerPosLook correction){
                assertNotEquals(lastTeleportId,correction.getTeleportId());lastTeleportId=correction.getTeleportId();
            }
            client.channel.writeInbound(packet);client.channel.runPendingTasks();client.channel.checkException();
            if(deliveredAuthority!=null){
                var identity=new AuthorityIdentity(deliveredAuthority.scope(),deliveredAuthority.offerSequence(),deliveredAuthority.sequence());
                var previous=deliveredAuthorities.putIfAbsent(identity,deliveredAuthority);
                if(previous!=null)assertEquals(previous,deliveredAuthority,"repeated authority identity must keep its exact payload");
            }
            if(packet instanceof SPacketJoinGame){
                joinGames++;
                assertNotNull(client.mc.player);
                assertNotSame(preJoinPlayer,client.mc.player,"actual JoinGame must create a fresh native player");
                assertEquals(EntityPlayerSP.class,client.mc.player.getClass(),"PlayerControllerMP creates the native player");
                assertSame(client.handler,client.mc.player.connection);
                assertSame(client.manager,client.mc.player.connection.getNetworkManager());
                assertSame(client.mc.world,client.mc.player.world);
                assertNotSame(client.world,client.mc.world,"actual JoinGame creates the new WorldClient");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        /** Actual fixture handles exposed only to the deterministic constructor-rollback probe. */
        private record FixtureResources(Minecraft mc, WorldClient previousWorld,
                EntityPlayerSP previousPlayer, NetworkManager manager, NetHandlerPlayClient handler,
                EmbeddedChannel channel, WorldClient world, CallbackPlayer player,
                PlayerPhysicsInboundConnections.ConnectionHandle connection) { }

        @FunctionalInterface
        private interface AfterConnectionBound {
            void accept(FixtureResources resources);
        }

        private final wi.unigrav.core.player.RawFixturePacketDecoder outboundDecoder = new wi.unigrav.core.player.RawFixturePacketDecoder();
        private final Minecraft mc;
        private final WorldClient previousWorld;
        private final EntityPlayerSP previousPlayer;
        private NetworkManager manager;
        private NetHandlerPlayClient handler;
        private EmbeddedChannel channel;
        private WorldClient world;
        private PlayerPhysicsInboundConnections.ConnectionHandle connection;
        private CallbackPlayer player;

        private Fixture() {
            this(resources -> { });
        }

        /**
         * The probe runs only after the real FML route and owner bind exist. It lets the original
         * cleanup case prove constructor rollback without replacing either production owner.
         */
        private Fixture(AfterConnectionBound afterConnectionBound) {
            mc = Minecraft.getMinecraft();
            assertNotNull(mc, "the fixture requires the real Minecraft singleton from runClient");
            assertTrue(mc.isCallingFromMinecraftThread(), "fixture construction stays on the real client thread");
            assertSame(mc, FMLClientHandler.instance().getClient(),
                    "the FML client handler must point at the normal client host");
            previousWorld = mc.world;
            previousPlayer = mc.player;
            assertNull(previousWorld, "the client integration task runs before a user world is opened");
            assertNull(previousPlayer, "the client integration task runs before a user player is installed");

            UUID id = new UUID(0, 11801);
            NetworkManager createdManager = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
            NetHandlerPlayClient createdHandler = new NetHandlerPlayClient(
                    mc, null, createdManager, new GameProfile(id, "raw-client"));
            createdManager.setNetHandler(createdHandler);
            EmbeddedChannel createdChannel = null;
            WorldClient createdWorld = null;
            CallbackPlayer createdPlayer = null;
            boolean hostStateReplacementAttempted = false;
            boolean connectionBindAttempted = false;

            try {
                createdChannel = createChannel(createdManager);
                assertSame(createdChannel, createdManager.channel());
                assertSame(createdManager, createdChannel.pipeline().get("packet_handler"));

                createdWorld = new EndpointWorld(createdHandler,
                        new WorldSettings(0, GameType.CREATIVE, false, false, WorldType.DEFAULT),
                        0, EnumDifficulty.NORMAL, mc.profiler);
                createdPlayer = new CallbackPlayer(mc, createdWorld, createdHandler);
                hostStateReplacementAttempted = true;
                mc.world = createdWorld;
                mc.player = createdPlayer;

                // Use Forge's public handshake entry to install its real dispatcher and pipeline handler.
                // The embedded channel has no peer, so this intentionally does not complete a handshake.
                FMLNetworkHandler.fmlClientHandshake(createdManager);
                requireActualForgeOutboundRoute(
                        mc, createdPlayer, createdWorld, createdHandler, createdManager, createdChannel);
                createdChannel.runPendingTasks();
                createdChannel.checkException();

                // Pair the real public lifecycle for every registered client module. The embedded
                // channel still has no peer and this event does not complete its FML handshake.
                connectionBindAttempted = true;
                MinecraftForge.EVENT_BUS.post(new FMLNetworkEvent.ClientConnectedToServerEvent(
                        createdManager, "embedded-no-peer-fixture"));
                var createdConnection = PlayerPhysicsInboundConnections.INSTANCE.findCurrentConnection(
                        createdManager, createdHandler, EnumPacketDirection.CLIENTBOUND)
                        .orElseThrow(() -> new IllegalStateException(
                                "The real connect event did not bind the exact clientbound lifetime"));
                PlayerPhysicsClient.install();
                discardKnownConnectionTraffic(createdChannel);

                afterConnectionBound.accept(new FixtureResources(mc, previousWorld, previousPlayer,
                        createdManager, createdHandler, createdChannel, createdWorld,
                        createdPlayer, createdConnection));

                manager = createdManager;
                handler = createdHandler;
                channel = createdChannel;
                world = createdWorld;
                player = createdPlayer;
                connection = createdConnection;
            } catch (Throwable constructionFailure) {
                Throwable escapedFailure = cleanupFixtureResources(
                        mc, previousWorld, previousPlayer, createdManager, createdHandler,
                        createdChannel, createdWorld, hostStateReplacementAttempted,
                        connectionBindAttempted, constructionFailure);
                rethrowConstructionFailure(escapedFailure);
                throw new AssertionError("unreachable after rethrowing fixture construction failure");
            }
        }

        private PlayerPhysicalSnapshot.Scope scope(long epoch) {
            return new PlayerPhysicalSnapshot.Scope(player.getUniqueID(), new UUID(0, 11802), player.dimension, epoch);
        }

        private void enqueue(Packet packet) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PlayerPhysicsNetwork.Message(packet).toBytes(bytes);
                byte[] canonicalWire = PlayerPhysicsProtocol.encode(packet);
                assertEquals(20, Byte.toUnsignedInt(bytes.getByte(bytes.readerIndex())),
                        "the real ingress must use the V20 layout");
                assertEquals(629, PlayerPhysicsProtocol.MAX_BYTES);
                assertEquals(canonicalWire.length, bytes.readableBytes(),
                        "Message must retain the actual encoded payload length without padding");
                assertTrue(canonicalWire.length <= PlayerPhysicsProtocol.MAX_BYTES);
                byte[] actualWire = new byte[bytes.readableBytes()];
                bytes.getBytes(bytes.readerIndex(), actualWire);
                assertArrayEquals(canonicalWire, actualWire);
                if (packet.nativeTransfer().isEmpty()) {
                    assertEquals(0, Byte.toUnsignedInt(actualWire[actualWire.length - 1]),
                            "V17 ends ordinary frames with the native-transfer union absence marker");
                }
                var inbound = new PlayerPhysicsNetwork.Message();
                inbound.fromBytes(bytes);
                assertEquals(0, bytes.readableBytes());
                PlayerPhysicsClient.receiveRaw(inbound.rawFrame(), handler);
            } finally {
                bytes.release();
            }
        }

        private void tickStart() {
            MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        }

        private void tickEnd() {
            MinecraftForge.EVENT_BUS.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            channel.runPendingTasks();
            channel.checkException();
        }

        private void deliver(Packet packet) { enqueue(packet); tickStart(); }

        private void offerAndApply(PlayerPhysicalSnapshot.Scope scope) {
            requireActualForgeOutboundRoute();
            boolean fenced = PlayerPhysicsClient.isInstallationFenced(player);
            deliver(offer(scope));
            assertEquals(!fenced, PlayerPhysicsClient.bodyReferenceStatus().isPresent());
            assertFalse(PlayerPhysicsClient.drivesMotion(player));
            var nativePacket = new CPacketPlayer(false);
            var prepared = PlayerPhysicsClient.prepareOutgoing(handler, nativePacket);
            if (fenced) {
                assertTrue(PlayerPhysicsClient.isInstallationFenced(player));
                assertSame(nativePacket, prepared);
            } else {
                assertInstanceOf(CPacketPlayer.Position.class, prepared);
            }
            channel.runPendingTasks();
            channel.checkException();
            assertActualApply(scope);
        }

        private void requireActualForgeOutboundRoute() {
            requireActualForgeOutboundRoute(mc, player, world, handler, manager, channel);
        }

        private static void requireActualForgeOutboundRoute(Minecraft mc, CallbackPlayer player,
                WorldClient world, NetHandlerPlayClient handler, NetworkManager manager,
                EmbeddedChannel channel) {
            if (Minecraft.getMinecraft() != mc || !mc.isCallingFromMinecraftThread()
                    || FMLClientHandler.instance().getClient() != mc) {
                throw new IllegalStateException(
                        "UG outbound integration requires the normal FML Minecraft instance on its client thread");
            }
            if (mc.player != player || mc.world != world || mc.getConnection() != handler
                    || handler.getNetworkManager() != manager || manager.channel() != channel
                    || channel.pipeline().get("packet_handler") != manager) {
                throw new IllegalStateException(
                        "Client fixture objects are not the exact active player/world/handler/channel route");
            }

            NetworkManager routed;
            try {
                routed = FMLCommonHandler.instance().getClientToServerNetworkManager();
            } catch (RuntimeException routeLookupFailure) {
                throw new IllegalStateException(
                        "CLIENT_INTEGRATION_BLOCKED: actual Forge TOSERVER lookup failed before APPLY; "
                                + "the normal FML client host and its real sided delegate are required",
                        routeLookupFailure);
            }
            if (routed != manager) {
                throw new IllegalStateException(
                        "CLIENT_INTEGRATION_BLOCKED: actual Forge TOSERVER did not resolve the fixture manager");
            }
            NetworkDispatcher dispatcher = NetworkDispatcher.get(manager);
            if (dispatcher == null || dispatcher.manager != manager
                    || channel.pipeline().get("fml:packet_handler") != dispatcher) {
                throw new IllegalStateException(
                        "CLIENT_INTEGRATION_BLOCKED: public FML client handshake did not install the expected dispatcher");
            }
        }

        private static EmbeddedChannel createChannel(NetworkManager manager) {
            EmbeddedChannel[] constructing = new EmbeddedChannel[1];
            try {
                return new EmbeddedChannel(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel embedded) {
                        constructing[0] = (EmbeddedChannel) embedded;
                        embedded.pipeline().addLast("packet_handler", manager);
                    }
                }) {
                    @Override protected SocketAddress remoteAddress0() {
                        // Address metadata for ordinary connection listeners; no socket is opened.
                        return new InetSocketAddress("127.0.0.1", 28699);
                    }
                };
            } catch (Throwable creationFailure) {
                if (constructing[0] != null) {
                    attachCleanupFailure(creationFailure, constructing[0]::finishAndReleaseAll);
                }
                rethrowConstructionFailure(creationFailure);
                throw new AssertionError("unreachable after rethrowing embedded-channel construction failure");
            }
        }

        private static Throwable cleanupFixtureResources(Minecraft mc,
                WorldClient previousWorld, EntityPlayerSP previousPlayer,
                NetworkManager manager, NetHandlerPlayClient handler, EmbeddedChannel channel,
                WorldClient world, boolean hostStateReplacementAttempted,
                boolean connectionBindAttempted, Throwable primaryFailure) {
            Throwable failure = primaryFailure;
            if (hostStateReplacementAttempted && world != null) {
                failure = attemptCleanup(failure,
                        () -> MinecraftForge.EVENT_BUS.post(new WorldEvent.Unload(world)));
            }
            if (channel == null && connectionBindAttempted && manager != null && handler != null) {
                failure = attemptCleanup(failure, () ->
                        MinecraftForge.EVENT_BUS.post(
                                new FMLNetworkEvent.ClientDisconnectionFromServerEvent(manager)));
            }
            if (channel != null) {
                failure = attemptCleanup(failure, () -> {
                    // The real dispatcher emits disconnect while closing. Observe its future:
                    // finishAndReleaseAll alone can hide a failed close and leave the channel open.
                    ChannelFuture close = channel.close();
                    channel.runPendingTasks();
                    System.out.println("[UG_RAW_FIXTURE_CLOSE] done=" + close.isDone()
                            + " success=" + close.isSuccess() + " cause=" + close.cause());
                    close.syncUninterruptibly();
                    if (channel.isOpen()) throw new IllegalStateException("Fixture channel did not close");
                });
                failure = attemptCleanup(failure, channel::finishAndReleaseAll);
            }
            if (hostStateReplacementAttempted) {
                failure = attemptCleanup(failure, () -> {
                    mc.world = previousWorld;
                    mc.player = previousPlayer;
                });
            }
            return failure;
        }

        private static Throwable attemptCleanup(Throwable primaryFailure, Runnable cleanup) {
            try {
                cleanup.run();
            } catch (Throwable cleanupFailure) {
                if (primaryFailure == null) return cleanupFailure;
                if (primaryFailure != cleanupFailure) primaryFailure.addSuppressed(cleanupFailure);
            }
            return primaryFailure;
        }

        private static void attachCleanupFailure(Throwable primaryFailure, Runnable cleanup) {
            try {
                cleanup.run();
            } catch (Throwable cleanupFailure) {
                if (primaryFailure != cleanupFailure) primaryFailure.addSuppressed(cleanupFailure);
            }
        }

        private static void rethrowConstructionFailure(Throwable failure) {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            throw new IllegalStateException("Client fixture construction failed", failure);
        }

        private static void rethrowCleanupFailure(Throwable failure) {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure != null) throw new IllegalStateException("Client fixture cleanup failed", failure);
        }

        private void assertActualApply(PlayerPhysicalSnapshot.Scope scope) {
            readActualPhysicsPacket(Kind.APPLY, scope);
        }

        private static void discardKnownConnectionTraffic(EmbeddedChannel channel) {
            channel.runPendingTasks();
            channel.checkException();
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ByteBuf payload;
                String channelName;
                if (outbound instanceof CPacketCustomPayload custom) {
                    channelName = custom.getChannelName();
                    payload = custom.getBufferData();
                } else if (outbound instanceof FMLProxyPacket proxy) {
                    channelName = proxy.channel();
                    payload = proxy.payload();
                } else {
                    fail("Unexpected outbound before UG initialization: " + outbound.getClass().getName());
                    return;
                }
                try {
                    assertTrue(isForgeHandshakeControl(channelName) || consumeRecognizedAuxiliary(channelName, payload),
                            "an unknown packet cannot precede physics initialization: " + channelName);
                } finally {
                    payload.release();
                }
            }
        }

        private List<Packet> collectPhysicsPackets() {
            var packets=new ArrayList<Packet>(); Object outbound;
            while((outbound=channel.readOutbound())!=null) {
                ByteBuf payload; String channelName;
                if(outbound instanceof CPacketCustomPayload custom) {channelName=custom.getChannelName();payload=custom.getBufferData();}
                else if(outbound instanceof FMLProxyPacket proxy) {channelName=proxy.channel();payload=proxy.payload();}
                else return fail("Unexpected real outbound type: "+outbound.getClass().getName());
                try {
                    if(isForgeHandshakeControl(channelName)||consumeRecognizedAuxiliary(channelName,payload)) continue;
                    assertEquals(CHANNEL,channelName);assertEquals(0,payload.readUnsignedByte());
                    byte[] bytes=new byte[payload.readableBytes()];payload.readBytes(bytes);packets.add(outboundDecoder.decode(bytes));
                } finally {payload.release();}
            }
            return packets;
        }

        private Packet readActualPhysicsPacket(Kind kind, PlayerPhysicalSnapshot.Scope scope) {
            Packet found = null;
            Object outbound;
            while ((outbound = channel.readOutbound()) != null) {
                ByteBuf payload;
                String channelName;
                if (outbound instanceof CPacketCustomPayload custom) {
                    channelName = custom.getChannelName();
                    payload = custom.getBufferData();
                } else if (outbound instanceof FMLProxyPacket proxy) {
                    channelName = proxy.channel();
                    payload = proxy.payload();
                } else {
                    return fail("Unexpected real outbound type: " + outbound.getClass().getName());
                }
                try {
                    if (isForgeHandshakeControl(channelName)) {
                        continue;
                    }
                    if (consumeRecognizedAuxiliary(channelName, payload)) continue;
                    assertEquals(CHANNEL, channelName, "an unknown custom channel cannot masquerade as physics");
                    assertEquals(0, payload.readUnsignedByte(), "the real Forge message discriminator");
                    byte[] bytes = new byte[payload.readableBytes()];
                    payload.readBytes(bytes);
                    Packet packet = outboundDecoder.decode(bytes);
                    assertEquals(kind, packet.kind(),
                            "handshake control traffic is excluded; this is the UG physics message");
                    assertEquals(scope, packet.scope());
                    assertNull(found, "one due tick emits exactly one physics request");
                    found = packet;
                } finally {
                    payload.release();
                }
            }
            assertNotNull(found, "the request must traverse actual Forge outbound into the real NetworkManager channel");
            return found;
        }

        private static boolean consumeRecognizedAuxiliary(String channelName, ByteBuf payload) {
            if (PlayerRenderPoseProtocol.CHANNEL.equals(channelName)) {
                assertEquals(0, payload.readUnsignedByte(), "the render-pose Forge discriminator");
                byte[] poseBytes = new byte[payload.readableBytes()];
                payload.readBytes(poseBytes);
                var pose = PlayerRenderPoseProtocol.decode(poseBytes);
                assertTrue(pose.kind() == PlayerRenderPoseProtocol.Kind.HELLO
                        || pose.kind() == PlayerRenderPoseProtocol.Kind.FREE_POSE,
                        "only valid client render reports may share this outbound route");
                System.out.println("[UG_RAW_FIXTURE_AUX] channel=" + channelName + " kind=" + pose.kind());
                return true;
            }
            if ("nfr_ui_chatpol".equals(channelName)) {
                // UIE's public connect probe is discriminator 0, protocol int, request long.
                assertEquals(13, payload.readableBytes());
                assertEquals(0, payload.readUnsignedByte());
                assertEquals(1, payload.readInt());
                assertTrue(payload.readLong() > 0);
                assertEquals(0, payload.readableBytes());
                System.out.println("[UG_RAW_FIXTURE_AUX] channel=" + channelName + " kind=CONNECT_PROBE");
                return true;
            }
            if ("nfr_ui_selfmsg".equals(channelName)) {
                // UIE's public self-message connect probe has only discriminator 0.
                assertEquals(1, payload.readableBytes());
                assertEquals(0, payload.readUnsignedByte());
                assertEquals(0, payload.readableBytes());
                System.out.println("[UG_RAW_FIXTURE_AUX] channel=" + channelName + " kind=CONNECT_PROBE");
                return true;
            }
            return false;
        }

        private static boolean isForgeHandshakeControl(String channelName) {
            return "FML|HS".equals(channelName)
                    || "REGISTER".equals(channelName)
                    || "UNREGISTER".equals(channelName);
        }

        @Override
        public void close() {
            Throwable failure = cleanupFixtureResources(mc, previousWorld, previousPlayer,
                    manager, handler, channel, world, true, true, null);
            rethrowCleanupFailure(failure);
        }
    }
}
