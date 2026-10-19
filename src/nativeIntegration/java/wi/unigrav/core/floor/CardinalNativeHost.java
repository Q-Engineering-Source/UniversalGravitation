package wi.unigrav.core.floor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** The fixture is discovered through the normal FML mods scanner. */
@Mod(modid="ug_cardinal_native_fixture",name="UG Cardinal Native Fixture",version="1")
public final class CardinalNativeHost {
    @Mod.EventHandler public void loaded(FMLLoadCompleteEvent event) {MinecraftForge.EVENT_BUS.register(this);}
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.START)return;
        MinecraftForge.EVENT_BUS.unregister(this);Minecraft mc=Minecraft.getMinecraft();
        Path result=Path.of(System.getProperty("unigrav.nativeIntegration.result"));
        String text;
        try {
            String nonce=System.getProperty("unigrav.nativeIntegration.nonce");
            String identity=System.getProperty("unigrav.nativeIntegration.identity");
            if(nonce==null||nonce.isBlank()||identity==null||identity.isBlank())
                throw new IllegalStateException("native suite requires this invocation nonce and input identity");
            if(!mc.isCallingFromMinecraftThread()||mc.ingameGUI==null||mc.renderEngine==null)
                throw new IllegalStateException("native suite requires initialized normal client main thread");
            var listener=new SummaryGeneratingListener();
            var request=LauncherDiscoveryRequestBuilder.request().selectors(
                    DiscoverySelectors.selectClass(CardinalNativeEntryIntegrationTest.class),
                    DiscoverySelectors.selectClass("wi.unigrav.core.player.InstalledMovementStatTest"),
                    DiscoverySelectors.selectClass("wi.unigrav.core.player.CardinalNativeBoundaryTest"),
                    DiscoverySelectors.selectClass("wi.unigrav.core.player.CardinalOwnerCallbackBoundaryTest"),
                    DiscoverySelectors.selectMethod("wi.unigrav.core.floor.PlayerPhysicsServerIntegrationTest",
                            "returningCardinalCallbackTeleportCannotWriteSuccessorMotionFallOrTail","java.lang.String,java.nio.file.Path"))
                    .configurationParameter("junit.jupiter.execution.parallel.enabled","false").build();
            var executions=new java.util.ArrayList<String>();
            var caseIds=new java.util.ArrayList<String>();
            var actualCases=new org.junit.platform.launcher.TestExecutionListener() {
                @Override public void executionFinished(org.junit.platform.launcher.TestIdentifier id,
                        org.junit.platform.engine.TestExecutionResult outcome) {
                    if(id.isTest()) {
                        executions.add(outcome.getStatus()+" "+id.getUniqueId()+" "+id.getDisplayName());
                        caseIds.add(id.getUniqueId());
                    }
                }
            };
            LauncherFactory.create().execute(request,listener,actualCases);var summary=listener.getSummary();
            var details=new StringWriter();summary.printFailuresTo(new PrintWriter(details));
            for(var failed:summary.getFailures()) {
                details.append("\nFULL ORIGINAL FAILURE ").append(failed.getTestIdentifier().getUniqueId()).append("\n");
                failed.getException().printStackTrace(new PrintWriter(details));
            }
            details.append("\nACTUAL CASE RESULTS\n").append(String.join("\n",executions)).append("\n");
            caseIds.sort(String::compareTo);
            String caseSetSha256=java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest((String.join("\n",caseIds)+"\n").getBytes(StandardCharsets.UTF_8)));
            int unique=new java.util.HashSet<>(caseIds).size();
            boolean passed=summary.getTestsFoundCount()==40&&summary.getTestsStartedCount()==40
                    &&summary.getTestsSucceededCount()==40&&caseIds.size()==40&&unique==40
                    &&caseSetSha256.equals("A878EFC56BCC17EFE80F3A6988856FF420BEA32CE45638833DA15397F29538F8")
                    &&summary.getTestsFailedCount()==0&&summary.getTestsAbortedCount()==0&&summary.getTestsSkippedCount()==0;
            text=(passed?"PASS":"FAIL")+" tests="+summary.getTestsFoundCount()+" started="+summary.getTestsStartedCount()+" unique="+unique+" succeeded="+summary.getTestsSucceededCount()
                    +" failed="+summary.getTestsFailedCount()+" aborted="+summary.getTestsAbortedCount()+" skipped="+summary.getTestsSkippedCount()+"\nnonce="+nonce+"\nidentity="+identity+"\ncaseSetSha256="+caseSetSha256+"\n"+details;
        } catch(Throwable failure) {var details=new StringWriter();failure.printStackTrace(new PrintWriter(details));text="FAIL\n"+details;}
        try {Files.createDirectories(result.getParent());Files.writeString(result,text,StandardCharsets.UTF_8);}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        finally {mc.shutdown();}
    }
}