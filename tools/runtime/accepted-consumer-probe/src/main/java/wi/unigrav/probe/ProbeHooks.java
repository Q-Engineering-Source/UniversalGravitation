package wi.unigrav.probe;

import dev.crlhitbox.api.geometry.Vec3d;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import neofontrender.addons.api.camera.CameraEyeAnchor;
import neofontrender.addons.api.camera.CameraEyeAnchorContext;
import neofontrender.addons.api.camera.CameraEyeAnchorProvider;
import neofontrender.addons.api.camera.CameraVector;
import wi.unigrav.core.client.PlayerPhysicsClient;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;

/** Read-only runtime observation called by the candidate mod and exact bytecode hooks. */
public final class ProbeHooks {
    private static final AtomicLong EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicLong TICK_COUNT = new AtomicLong();
    private static final AtomicLong FRAME_COUNT = new AtomicLong();
    private static final AtomicLong EYE_SAMPLE_COUNT = new AtomicLong();
    private static final AtomicLong PROVIDER_CALL_COUNT = new AtomicLong();
    private static final AtomicLong API_SAMPLE_COUNT = new AtomicLong();
    private static final long STARTED_NANOS = System.nanoTime();
    private static final long DEADLINE_SECONDS = positiveProperty("ug.acceptedProbe.deadlineSeconds", 420);
    private static final long MAX_BYTES = positiveProperty("ug.acceptedProbe.maxEventBytes", 32L * 1024 * 1024);
    private static final long MAX_RECORDS = positiveProperty("ug.acceptedProbe.maxEventRecords", 50_000);
    private static final long MAX_FAILURE_BYTES = positiveProperty("ug.acceptedProbe.maxFailureBytes", 1024 * 1024);
    private static final long MAX_FAILURE_RECORDS = positiveProperty("ug.acceptedProbe.maxFailureRecords", 100);
    private static final ThreadLocal<SampleTrace> SAMPLE = new ThreadLocal<>();
    private static Path runDirectory;
    private static Path eventLog;
    private static Path readySignal;
    private static Path failureLog;
    private static volatile boolean disconnectStarted;
    private static volatile boolean disconnectCompleted;
    private static volatile boolean physicsClearAfterDisconnect;
    private static volatile boolean cameraLifecycleClearAfterDisconnect;
    private static volatile boolean shutdownRequested;
    private static long eventBytes;
    private static long eventRecords;
    private static long failureBytes;
    private static long failureRecords;
    private static volatile boolean firstAcceptedEyeLogged;
    private static String shutdownReason = "not-requested";

    private ProbeHooks() {}

    public static synchronized void initialize() {
        if (runDirectory != null) return;
        String configured = System.getProperty("ug.acceptedProbe.runDir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("-Dug.acceptedProbe.runDir is required for probe output");
        }
        runDirectory = Path.of(configured).toAbsolutePath().normalize();
        eventLog = runDirectory.resolve("native-events.jsonl");
        readySignal = runDirectory.resolve("ready-for-server-stop.json");
        failureLog = runDirectory.resolve("probe-failed.txt");
        try {
            Files.createDirectories(runDirectory);
            if (Files.exists(eventLog) || Files.exists(failureLog)) {
                throw new IOException("Run directory already contains probe evidence; use a fresh run directory");
            }
            emit("probe-init", "javaVersion", System.getProperty("java.version"),
                    "pid", ProcessHandle.current().pid(), "deadlineSeconds", DEADLINE_SECONDS,
                    "maxEventBytes", MAX_BYTES, "maxEventRecords", MAX_RECORDS);
            Runtime.getRuntime().addShutdownHook(new Thread(ProbeHooks::writeFinalSummary,
                    "ug-accepted-consumer-probe-summary"));
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot initialize native probe output at " + runDirectory, cause);
        }
    }

    public static void afterPhysicsTick() {
        long tick = TICK_COUNT.incrementAndGet();
        if (tick == 1 || tick % 20 == 0) {
            Minecraft minecraft = Minecraft.getMinecraft();
            EntityPlayerSP actor = minecraft.player;
            emit("ug-client-tick", "tick", tick, "actor", actorIdentity(actor, minecraft),
                    "owns", actor != null && PlayerPhysicsClient.owns(actor),
                    "drivesMotion", actor != null && PlayerPhysicsClient.drivesMotion(actor),
                    "accepted", accepted(actor, 1.0F), "bodyReferenceStatus", referenceStatus());
        }
        maybeShutdown();
    }

    /** Independent client-tick deadline path, so a missing UG tick hook cannot hang the capture forever. */
    public static void onProbeClientTick() {
        checkAgentFailure();
        maybeShutdown();
    }

    public static void beforePhysicsDisconnect() {
        disconnectStarted = true;
        emit("ug-disconnect-handler-enter", "actor", actorIdentity(Minecraft.getMinecraft().player,
                Minecraft.getMinecraft()));
    }

    public static void afterPhysicsDisconnect() {
        disconnectCompleted = true;
        emit("ug-disconnect-handler-return", "acceptedCurrentActor",
                accepted(Minecraft.getMinecraft().player, 1.0F), "bodyReferenceStatus", referenceStatus());
    }

    /** The actual pre-clear actor is consumed synchronously; only its stable identity is written. */
    public static void beforePhysicsClear(EntityPlayerSP actor) {
        emit("ug-clear-enter", "actor", actorIdentity(actor, Minecraft.getMinecraft()),
                "acceptedBefore", accepted(actor, 1.0F));
    }

    /** Arguments are read during the injected return and discarded immediately. */
    public static void afterPhysicsClear(EntityPlayerSP currentActor, World currentWorld, Object currentSession) {
        if (disconnectStarted) physicsClearAfterDisconnect = true;
        Minecraft minecraft = Minecraft.getMinecraft();
        emit("ug-clear-return", "internalActorNull", currentActor == null, "internalWorldNull", currentWorld == null,
                "internalSessionNull", currentSession == null, "mcActor", actorIdentity(minecraft.player, minecraft),
                "acceptedCurrentActor", accepted(minecraft.player, 1.0F),
                "bodyReferenceStatus", referenceStatus());
    }

    public static void beforeEyeProvider(CameraEyeAnchorProvider provider, CameraEyeAnchorContext context) {
        SampleTrace trace = currentSample(context.player(), context.sampleId());
        trace.providerId = provider.id().toString();
        trace.providerPriority = provider.priority();
        trace.nativeEye = cameraVector(context.nativeEye());
        trace.providerStack = shortStack();
        trace.acceptedAtProvider = accepted(context.player(), context.partialTicks());
        SAMPLE.set(trace);
    }

    /** Receives the actual Revo provider return object before CameraApi evaluates the next provider. */
    public static void afterEyeProvider(CameraEyeAnchor returned, CameraEyeAnchorProvider provider,
                                        CameraEyeAnchorContext context) {
        SampleTrace trace = currentSample(context.player(), context.sampleId());
        trace.providerId = provider.id().toString();
        trace.providerPriority = provider.priority();
        trace.providerReturned = coordinates(returned);
        trace.providerReturnIdentity = returned == null ? "null" : Integer.toHexString(System.identityHashCode(returned));
        trace.nativeEye = cameraVector(context.nativeEye());
        Optional<PlayerPhysicalSnapshot> snapshot = PlayerPhysicsClient.acceptedPresentation(context.player());
        Optional<Vec3d> acceptedEye = PlayerPhysicsClient.acceptedEyePosition(context.player(), context.partialTicks());
        trace.acceptedAtProviderReturn = snapshot.map(ProbeHooks::snapshot).orElse("empty");
        trace.expectedAcceptedEye = acceptedEye.map(ProbeHooks::vec).orElse("empty");
        trace.providerComparedWithAcceptedEye = matches(returned, acceptedEye);
        trace.returnStack = shortStack();
        SAMPLE.set(trace);
        long call = PROVIDER_CALL_COUNT.incrementAndGet();
        if (call <= 12 || (call % 120 == 0) || (trace.providerComparedWithAcceptedEye && !firstAcceptedEyeLogged)) {
            emit("revo-eye-provider-return", "call", call, "sampleId", trace.sampleId,
                    "actor", trace.actorIdentity, "partialTicks", trace.partialTicks,
                    "providerId", trace.providerId, "providerPriority", trace.providerPriority,
                    "nativeEye", trace.nativeEye, "returnedAnchor", trace.providerReturned,
                    "returnIdentity", trace.providerReturnIdentity,
                    "acceptedPresentation", trace.acceptedAtProviderReturn,
                    "acceptedEyeComparison", trace.expectedAcceptedEye,
                    "matchesAcceptedEye", trace.providerComparedWithAcceptedEye,
                    "cameraRuntimeFrameOnStack", trace.cameraRuntimeFrameOnStack,
                    "entryStack", trace.providerStack, "stack", trace.returnStack);
        }
        if (trace.providerComparedWithAcceptedEye) firstAcceptedEyeLogged = true;
    }

    /** Runs at the real UIE sampler boundary; stack ancestry distinguishes camera-frame use from helper calls. */
    public static void onUieEyeSampler(EntityPlayerSP actor, long sampleId, float partialTicks) {
        SAMPLE.remove();
        SampleTrace trace = currentSample(actor, sampleId);
        trace.partialTicks = partialTicks;
        trace.cameraRuntimeFrameOnStack = hasFrameAncestor();
        trace.samplerStack = shortStack();
        SAMPLE.set(trace);
        long count = EYE_SAMPLE_COUNT.incrementAndGet();
        if (count <= 6 || count % 120 == 0) {
            emit("uie-camera-eye-sampler", "sample", count, "sampleId", sampleId,
                    "actor", trace.actorIdentity, "partialTicks", partialTicks,
                    "acceptedPresentation", accepted(actor, partialTicks),
                    "cameraRuntimeFrameOnStack", trace.cameraRuntimeFrameOnStack, "stack", trace.samplerStack);
        }
    }

    public static void onUieCameraFrame(float partialTicks) {
        long count = FRAME_COUNT.incrementAndGet();
        if (count <= 4 || count % 120 == 0) {
            Minecraft minecraft = Minecraft.getMinecraft();
            emit("uie-camera-runtime-frame", "frame", count, "partialTicks", partialTicks,
                    "actor", actorIdentity(minecraft.player, minecraft), "stack", shortStack());
        }
    }

    /** This hook executes after the actual UIE API method returns, not by calling the API itself. */
    public static void afterUiEyeApiSample(CameraEyeAnchor returned, String selectedOwner,
                                           CameraEyeAnchorContext context) {
        SampleTrace trace = currentSample(context.player(), context.sampleId());
        long sample = API_SAMPLE_COUNT.incrementAndGet();
        String apiResult = coordinates(returned);
        String apiIdentity = returned == null ? "null" : Integer.toHexString(System.identityHashCode(returned));
        Optional<Vec3d> acceptedEye = PlayerPhysicsClient.acceptedEyePosition(context.player(), context.partialTicks());
        boolean matchesExpected = matches(returned, acceptedEye);
        boolean revoSelected = "unigrav:accepted_physical_eye".equals(selectedOwner);
        boolean resultIsProviderObject = trace.providerReturnIdentity != null
                && trace.providerReturnIdentity.equals(apiIdentity);
        boolean completeNativeSample = trace.cameraRuntimeFrameOnStack
                && revoSelected && trace.providerId != null
                && trace.providerId.equals(selectedOwner) && resultIsProviderObject && matchesExpected
                && !trace.acceptedAtProviderReturn.equals("empty");
        if (sample <= 12 || sample % 120 == 0 || (completeNativeSample && !firstAcceptedEyeLogged)) {
            emit("uie-api-eye-selected", "sample", sample, "sampleId", context.sampleId(),
                    "actor", actorIdentity(context.player(), Minecraft.getMinecraft()),
                    "partialTicks", context.partialTicks(), "selectedOwner", selectedOwner,
                    "result", apiResult, "resultIdentity", apiIdentity,
                    "revoCallbackIdentity", trace.providerReturnIdentity,
                    "revoReturned", trace.providerReturned,
                    "acceptedEyeComparison", acceptedEye.map(ProbeHooks::vec).orElse("empty"),
                    "matchesAcceptedEye", matchesExpected,
                    "cameFromCameraRuntimeFrame", trace.cameraRuntimeFrameOnStack,
                    "completeNativeAcceptedSample", completeNativeSample,
                    "acceptedPresentation", trace.acceptedAtProviderReturn,
                    "stack", shortStack());
        }
        if (completeNativeSample) {
            firstAcceptedEyeLogged = true;
            writeReadySignal(context, selectedOwner, returned, trace);
        }
        SAMPLE.remove();
    }

    public static void afterUiEyeCacheClear() {
        String stack = shortStack();
        boolean disconnectLifecycle = stack.contains("CameraModule.disconnected");
        if (disconnectLifecycle) cameraLifecycleClearAfterDisconnect = true;
        SAMPLE.remove();
        emit("uie-eye-cache-clear-return", "disconnectStarted", disconnectStarted,
                "calledByDisconnectLifecycle", disconnectLifecycle,
                "stack", stack);
    }

    private static SampleTrace currentSample(EntityPlayer actor, long sampleId) {
        SampleTrace existing = SAMPLE.get();
        if (existing != null && existing.sampleId == sampleId
                && existing.actorIdentity.equals(actorIdentity(actor, Minecraft.getMinecraft()))) return existing;
        SampleTrace fresh = new SampleTrace();
        fresh.sampleId = sampleId;
        fresh.actorIdentity = actorIdentity(actor, Minecraft.getMinecraft());
        fresh.partialTicks = Float.NaN;
        fresh.cameraRuntimeFrameOnStack = hasFrameAncestor();
        fresh.samplerStack = shortStack();
        return fresh;
    }

    private static String accepted(EntityPlayer actor, float partialTicks) {
        if (actor == null) return "empty";
        Optional<PlayerPhysicalSnapshot> accepted = PlayerPhysicsClient.acceptedPresentation(actor);
        if (accepted.isEmpty()) return "empty";
        PlayerPhysicalSnapshot snapshot = accepted.orElseThrow();
        Optional<Vec3d> eye = PlayerPhysicsClient.acceptedEyePosition(actor, partialTicks);
        return "scope=" + snapshot.scope() + ";revision=" + snapshot.revision()
                + ";shape=" + snapshot.placement().shape()
                + ";center=" + vec(snapshot.placement().center())
                + ";bounds=" + snapshot.placement().bounds()
                + ";bodyUp=" + vec(snapshot.placement().bodyUp())
                + ";placementEye=" + vec(snapshot.placement().eye())
                + ";interpolatedAcceptedEye=" + eye.map(ProbeHooks::vec).orElse("empty");
    }

    private static String snapshot(PlayerPhysicalSnapshot snapshot) {
        return "scope=" + snapshot.scope() + ";revision=" + snapshot.revision()
                + ";shape=" + snapshot.placement().shape()
                + ";center=" + vec(snapshot.placement().center())
                + ";bounds=" + snapshot.placement().bounds()
                + ";bodyUp=" + vec(snapshot.placement().bodyUp())
                + ";placementEye=" + vec(snapshot.placement().eye());
    }

    private static String referenceStatus() {
        return PlayerPhysicsClient.bodyReferenceStatus().map(Object::toString).orElse("empty");
    }

    private static String actorIdentity(EntityPlayer actor, Minecraft minecraft) {
        if (actor == null) return "null";
        return "uuid=" + actor.getUniqueID() + ";entityId=" + actor.getEntityId()
                + ";identityHash=" + Integer.toHexString(System.identityHashCode(actor))
                + ";dimension=" + actor.dimension + ";actorWorldHash="
                + (actor.world == null ? "null" : Integer.toHexString(System.identityHashCode(actor.world)))
                + ";matchesClientWorld=" + (actor.world != null && actor.world == minecraft.world);
    }

    private static String cameraVector(CameraVector value) {
        return value == null ? "null" : value.x + "," + value.y + "," + value.z;
    }

    private static String coordinates(CameraEyeAnchor value) {
        return value == null ? "null" : value.x + "," + value.y + "," + value.z;
    }

    private static String vec(Vec3d value) {
        return value.x() + "," + value.y() + "," + value.z();
    }

    private static boolean matches(CameraEyeAnchor actual, Optional<Vec3d> expected) {
        if (actual == null || expected.isEmpty()) return false;
        Vec3d value = expected.orElseThrow();
        return Math.abs(actual.x - value.x()) <= 1.0E-9D
                && Math.abs(actual.y - value.y()) <= 1.0E-9D
                && Math.abs(actual.z - value.z()) <= 1.0E-9D;
    }

    private static boolean hasFrameAncestor() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getClassName().equals("neofontrender.addons.camera.CameraRuntime")
                    && element.getMethodName().equals("frame")) return true;
        }
        return false;
    }

    private static String shortStack() {
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        int recorded = 0;
        for (StackTraceElement element : elements) {
            String className = element.getClassName();
            if (className.equals(Thread.class.getName()) || className.equals(ProbeHooks.class.getName())) continue;
            if (recorded++ > 0) stack.append(" <- ");
            stack.append(className).append('.').append(element.getMethodName());
            if (recorded >= 10) break;
        }
        return stack.toString();
    }

    private static void writeReadySignal(CameraEyeAnchorContext context, String selectedOwner,
                                         CameraEyeAnchor returned, SampleTrace trace) {
        if (readySignal == null) throw new IllegalStateException("Probe initialized before ready-signal path");
        String content = "{\"state\":\"READY_FOR_NORMAL_SERVER_STOP\",\"sampleId\":" + context.sampleId()
                + ",\"actor\":\"" + escape(trace.actorIdentity) + "\",\"scopeRevisionGeometry\":\""
                + escape(trace.acceptedAtProviderReturn) + "\",\"provider\":\"" + escape(selectedOwner)
                + "\",\"acceptedEye\":\"" + escape(coordinates(returned)) + "\"}" + System.lineSeparator();
        try {
            if (!Files.exists(readySignal)) {
                Files.writeString(readySignal, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                emit("ready-for-server-stop", "sampleId", context.sampleId(), "provider", selectedOwner);
            }
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot write server-stop signal " + readySignal, cause);
        }
    }

    private static void maybeShutdown() {
        if (shutdownRequested || runDirectory == null) return;
        if (disconnectCompleted && physicsClearAfterDisconnect && cameraLifecycleClearAfterDisconnect) {
            requestShutdown("disconnect-and-both-consumer-clears-observed");
            return;
        }
        if (System.nanoTime() - STARTED_NANOS >= DEADLINE_SECONDS * 1_000_000_000L) {
            requestShutdown("deadline-incomplete");
        }
    }

    private static synchronized void requestShutdown(String reason) {
        if (shutdownRequested) return;
        shutdownRequested = true;
        shutdownReason = reason;
        RuntimeException failure = null;
        try {
            emit("client-normal-shutdown-request", "reason", reason, "ugTicks", TICK_COUNT.get(),
                    "cameraFrames", FRAME_COUNT.get(), "eyeSamples", EYE_SAMPLE_COUNT.get(),
                    "providerCalls", PROVIDER_CALL_COUNT.get(), "apiSamples", API_SAMPLE_COUNT.get(),
                    "disconnectCompleted", disconnectCompleted,
                    "physicsClearAfterDisconnect", physicsClearAfterDisconnect,
                    "cameraLifecycleClearAfterDisconnect", cameraLifecycleClearAfterDisconnect,
                    "eventRecords", eventRecords, "eventBytes", eventBytes);
        } catch (RuntimeException cause) {
            failure = cause;
        }
        try {
            Minecraft.getMinecraft().shutdown();
        } catch (RuntimeException cause) {
            if (failure == null) failure = cause;
            else failure.addSuppressed(cause);
        }
        if (failure != null) throw failure;
    }

    private static void checkAgentFailure() {
        if (runDirectory == null) return;
        Path failure = runDirectory.resolve("agent-failed.txt");
        if (!Files.exists(failure)) return;
        try {
            IllegalStateException transformFailure = new IllegalStateException("Probe bytecode transform failed: "
                    + Files.readString(failure, StandardCharsets.UTF_8));
            persistFailure("agent-transform-failed", transformFailure);
            requestShutdown("agent-transform-failure");
            throw transformFailure;
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot read agent failure receipt " + failure, cause);
        }
    }

    private static long positiveProperty(String name, long fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException("must be positive");
            return parsed;
        } catch (NumberFormatException cause) {
            throw new IllegalArgumentException("Invalid " + name + "=" + value, cause);
        }
    }

    private static synchronized void emit(String event, Object... fields) {
        if (runDirectory == null) initialize();
        if ((fields.length & 1) != 0) throw new IllegalArgumentException("Probe event fields must be key/value pairs");
        StringBuilder line = new StringBuilder("{\"seq\":").append(EVENT_SEQUENCE.incrementAndGet())
                .append(",\"time\":\"").append(Instant.now()).append("\",\"event\":\"")
                .append(escape(event)).append('\"');
        for (int index = 0; index < fields.length; index += 2) {
            line.append(",\"").append(escape(String.valueOf(fields[index]))).append("\":\"")
                    .append(escape(String.valueOf(fields[index + 1]))).append('\"');
        }
        line.append('}').append(System.lineSeparator());
        byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
        if (eventRecords + 1 > MAX_RECORDS || eventBytes + bytes.length > MAX_BYTES) {
            IllegalStateException failure = new IllegalStateException(
                    "Native probe event evidence limit reached: records=" + eventRecords
                            + "/" + MAX_RECORDS + " bytes=" + eventBytes + "/" + MAX_BYTES);
            persistFailure("event-log-bound-reached", failure);
            throw failure;
        }
        try {
            Files.write(eventLog, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            eventRecords++;
            eventBytes += bytes.length;
        } catch (IOException cause) {
            IllegalStateException failure = new IllegalStateException(
                    "Cannot append native probe event log " + eventLog, cause);
            persistFailure("event-log-write-failed", failure);
            throw failure;
        }
    }

    private static synchronized void persistFailure(String boundary, Throwable failure) {
        StringBuilder record = new StringBuilder("time=").append(Instant.now())
                .append(" boundary=").append(boundary).append(System.lineSeparator());
        java.io.StringWriter stack = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(stack));
        record.append(stack);
        if (!record.toString().endsWith(System.lineSeparator())) record.append(System.lineSeparator());
        byte[] bytes = record.toString().getBytes(StandardCharsets.UTF_8);
        try {
            long currentBytes = Files.exists(failureLog) ? Files.size(failureLog) : 0;
            if (failureRecords + 1 > MAX_FAILURE_RECORDS || currentBytes + bytes.length > MAX_FAILURE_BYTES) {
                System.err.println("Probe failure evidence bound reached: records=" + failureRecords + "/"
                        + MAX_FAILURE_RECORDS + " bytes=" + currentBytes + "/" + MAX_FAILURE_BYTES);
                failure.printStackTrace(System.err);
                return;
            }
            Files.write(failureLog, bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            failureRecords++;
            failureBytes += bytes.length;
        } catch (IOException persistenceFailure) {
            persistenceFailure.addSuppressed(failure);
            persistenceFailure.printStackTrace(System.err);
            throw new IllegalStateException("Cannot persist accepted-consumer probe failure to " + failureLog,
                    persistenceFailure);
        }
    }

    private static void writeFinalSummary() {
        if (runDirectory == null) return;
        boolean lifecycleComplete = "disconnect-and-both-consumer-clears-observed".equals(shutdownReason);
        boolean failed = Files.exists(failureLog);
        String state = failed ? "FAILED" : lifecycleComplete ? "OBSERVED_COMPLETE" : "INCOMPLETE";
        String summary = "{\"state\":\"" + state + "\",\"shutdownReason\":\""
                + escape(shutdownReason) + "\",\"ugTicks\":" + TICK_COUNT.get()
                + ",\"cameraFrames\":" + FRAME_COUNT.get() + ",\"eyeSamples\":" + EYE_SAMPLE_COUNT.get()
                + ",\"providerCalls\":" + PROVIDER_CALL_COUNT.get() + ",\"apiSamples\":" + API_SAMPLE_COUNT.get()
                + ",\"disconnectStarted\":" + disconnectStarted + ",\"disconnectCompleted\":"
                + disconnectCompleted + ",\"physicsClearAfterDisconnect\":" + physicsClearAfterDisconnect
                + ",\"cameraLifecycleClearAfterDisconnect\":" + cameraLifecycleClearAfterDisconnect
                + ",\"eventRecords\":" + eventRecords + ",\"eventBytes\":" + eventBytes
                + ",\"maxEventRecords\":" + MAX_RECORDS + ",\"maxEventBytes\":" + MAX_BYTES
                + ",\"failureRecords\":" + failureRecords + ",\"failureBytes\":" + failureBytes
                + ",\"maxFailureRecords\":" + MAX_FAILURE_RECORDS + ",\"maxFailureBytes\":"
                + MAX_FAILURE_BYTES + "}" + System.lineSeparator();
        Path summaryPath = runDirectory.resolve("native-summary.json");
        try {
            Files.writeString(summaryPath, summary, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (IOException cause) {
            IllegalStateException failure = new IllegalStateException(
                    "Cannot create final native probe summary " + summaryPath, cause);
            try {
                persistFailure("summary-write-failed", failure);
            } catch (RuntimeException persistenceFailure) {
                failure.addSuppressed(persistenceFailure);
            }
            failure.printStackTrace(System.err);
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class SampleTrace {
        private long sampleId;
        private String actorIdentity = "unknown";
        private float partialTicks;
        private boolean cameraRuntimeFrameOnStack;
        private String samplerStack = "";
        private String providerId;
        private int providerPriority;
        private String providerStack = "";
        private String providerReturned;
        private String providerReturnIdentity;
        private String nativeEye;
        private String acceptedAtProvider;
        private String acceptedAtProviderReturn;
        private String expectedAcceptedEye;
        private boolean providerComparedWithAcceptedEye;
        private String returnStack = "";
    }
}
