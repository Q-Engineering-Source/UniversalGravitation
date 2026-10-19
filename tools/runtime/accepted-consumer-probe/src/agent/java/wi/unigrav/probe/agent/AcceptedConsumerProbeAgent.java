package wi.unigrav.probe.agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Test-only, exact-signature observation hooks for the isolated native candidate. */
public final class AcceptedConsumerProbeAgent {
    private static final String HOOKS = "wi/unigrav/probe/ProbeHooks";
    private static final String PLAYER_SP = "net/minecraft/client/entity/EntityPlayerSP";
    private static final String CAMERA_API = "neofontrender/addons/api/camera/CameraApi";
    private static final String CAMERA_CONTEXT = "neofontrender/addons/api/camera/CameraEyeAnchorContext";
    private static final String CAMERA_ANCHOR = "neofontrender/addons/api/camera/CameraEyeAnchor";
    private static final String CAMERA_PROVIDER = "neofontrender/addons/api/camera/CameraEyeAnchorProvider";
    private static final Map<String, Target> TARGETS = targets();
    private static final Map<String, AtomicInteger> MATCH_COUNTS = matchCounts();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static Path output;
    private static long evidenceBytes;
    private static long recordCount;
    private static long maxEvidenceBytes;
    private static long maxRecords;

    private AcceptedConsumerProbeAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        String configured = System.getProperty("ug.acceptedProbe.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("-Dug.acceptedProbe.dir is required for the native probe agent");
        }
        output = Path.of(configured).toAbsolutePath().normalize();
        try {
            Files.createDirectories(output);
            maxEvidenceBytes = positiveLongProperty("ug.acceptedProbe.maxEvidenceBytes", 64L * 1024 * 1024);
            maxRecords = positiveLongProperty("ug.acceptedProbe.maxRecords", 100_000L);
            append("agent-start", "java=" + System.getProperty("java.version")
                    + " pid=" + ProcessHandle.current().pid());
            TARGETS.keySet().forEach(key -> append("required", key));
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot open accepted-consumer probe evidence directory " + output, cause);
        }
        instrumentation.addTransformer(new ProbeTransformer(), false);
        Runtime.getRuntime().addShutdownHook(new Thread(AcceptedConsumerProbeAgent::writeSummary,
                "ug-accepted-consumer-agent-summary"));
    }

    private static Map<String, Target> targets() {
        Map<String, Target> result = new LinkedHashMap<>();
        add(result, "wi/unigrav/core/client/PlayerPhysicsClient", "onClientTick",
                "(Lnet/minecraftforge/fml/common/gameevent/TickEvent$ClientTickEvent;)V", Hook.TICK,
                Opcodes.ACC_PUBLIC, false, 2, 1);
        add(result, "wi/unigrav/core/client/PlayerPhysicsClient", "clear", "()V", Hook.CLEAR,
                Opcodes.ACC_PRIVATE, false, 1, 2);
        add(result, "wi/unigrav/core/client/PlayerPhysicsClient", "onDisconnect",
                "(Lnet/minecraftforge/fml/common/network/FMLNetworkEvent$ClientDisconnectionFromServerEvent;)V",
                Hook.DISCONNECT, Opcodes.ACC_PUBLIC, false, 2, 2);
        add(result, "wi/unigrav/core/client/camera/RevoUiSixDofCamera$1", "anchor",
                "(L" + CAMERA_CONTEXT + ";)L" + CAMERA_ANCHOR + ";", Hook.REVO_PROVIDER,
                Opcodes.ACC_PUBLIC, false, 2, 2);
        add(result, "neofontrender/addons/camera/CameraEyeAnchorSampler", "sample",
                "(L" + PLAYER_SP + ";JF)Lneofontrender/addons/camera/CameraEyeAnchorSampler$Sample;",
                Hook.UIE_SAMPLER, 0, true, 4, 1);
        add(result, "neofontrender/addons/camera/CameraRuntime", "frame",
                "(F)Lneofontrender/addons/api/camera/CameraFrame;", Hook.UIE_FRAME,
                Opcodes.ACC_PUBLIC, true, 1, 1);
        add(result, CAMERA_API, "sampleEyeAnchor",
                "(L" + CAMERA_CONTEXT + ";)L" + CAMERA_ANCHOR + ";", Hook.UIE_API_SAMPLE,
                Opcodes.ACC_PUBLIC, true, 1, 1);
        add(result, CAMERA_API, "clearEyeAnchorSampleCache", "()V", Hook.UIE_CACHE_CLEAR,
                Opcodes.ACC_PUBLIC, true, 0, 1);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, AtomicInteger> matchCounts() {
        Map<String, AtomicInteger> result = new LinkedHashMap<>();
        TARGETS.keySet().forEach(key -> result.put(key, new AtomicInteger()));
        return Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, Target> targets, String className, String method, String descriptor,
                            Hook hook, int expectedVisibility, boolean isStatic,
                            int minimumLocals, int minimumHookSites) {
        targets.put(className + "#" + method + descriptor,
                new Target(className, method, descriptor, hook, expectedVisibility, isStatic,
                        minimumLocals, minimumHookSites));
    }

    private static final class ProbeTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (className == null) return null;
            boolean targetClass = TARGETS.values().stream().anyMatch(target -> target.className.equals(className));
            if (!targetClass) return null;
            Map<String, Target> matches = new LinkedHashMap<>();
            int[] hookSites = {0};
            Map<String, String> observedFields = new LinkedHashMap<>();
            try {
                ClassReader reader = new ClassReader(classfileBuffer);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                                     String signature, Object value) {
                        observedFields.put(name, descriptor + "/" + access);
                        return super.visitField(access, name, descriptor, signature, value);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                     String[] exceptions) {
                        MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                        Target target = TARGETS.get(className + "#" + name + descriptor);
                        if (target == null) return delegate;
                        String key = className + "#" + name + descriptor;
                        if (((access & Opcodes.ACC_STATIC) != 0) != target.isStatic) {
                            markFailure("transform-error", key + " staticness mismatch access=" + access);
                            return delegate;
                        }
                        int visibility = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                        if (visibility != target.expectedVisibility) {
                            markFailure("transform-error", key + " visibility mismatch expected="
                                    + target.expectedVisibility + " actual=" + visibility + " access=" + access);
                            return delegate;
                        }
                        matches.put(key, target);
                        int[] methodHookSites = {0};
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                if (maxLocals < target.minimumLocals) {
                                    String message = key + " expected maxLocals >= " + target.minimumLocals
                                            + " but actual=" + maxLocals;
                                    markFailure("transform-error", message);
                                    throw new IllegalStateException(message);
                                }
                                if (methodHookSites[0] < target.minimumHookSites) {
                                    String message = key + " expected at least " + target.minimumHookSites
                                            + " injected hook sites but actual=" + methodHookSites[0];
                                    markFailure("transform-error", message);
                                    throw new IllegalStateException(message);
                                }
                                append("transform-method-hooks", key + " access=" + access
                                        + " maxLocals=" + maxLocals + " injectedSites=" + methodHookSites[0]
                                        + " requiredMinimum=" + target.minimumHookSites);
                                super.visitMaxs(maxStack, maxLocals);
                            }

                            @Override
                            public void visitCode() {
                                super.visitCode();
                                if (target.hook == Hook.CLEAR) {
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitFieldInsn(Opcodes.GETFIELD, target.className, "player", "L" + PLAYER_SP + ";");
                                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforePhysicsClear",
                                            "(L" + PLAYER_SP + ";)V", false);
                                    hookSites[0]++;
                                    methodHookSites[0]++;
                                } else if (target.hook == Hook.DISCONNECT) {
                                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforePhysicsDisconnect", "()V", false);
                                    hookSites[0]++;
                                    methodHookSites[0]++;
                                } else if (target.hook == Hook.REVO_PROVIDER) {
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitVarInsn(Opcodes.ALOAD, 1);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "beforeEyeProvider",
                                            "(L" + CAMERA_PROVIDER + ";L" + CAMERA_CONTEXT + ";)V", false);
                                    hookSites[0]++;
                                    methodHookSites[0]++;
                                } else if (target.hook == Hook.UIE_SAMPLER) {
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    visitVarInsn(Opcodes.LLOAD, 1);
                                    visitVarInsn(Opcodes.FLOAD, 3);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onUieEyeSampler",
                                            "(L" + PLAYER_SP + ";JF)V", false);
                                    hookSites[0]++;
                                    methodHookSites[0]++;
                                } else if (target.hook == Hook.UIE_FRAME) {
                                    visitVarInsn(Opcodes.FLOAD, 0);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "onUieCameraFrame", "(F)V", false);
                                    hookSites[0]++;
                                    methodHookSites[0]++;
                                }
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.RETURN) {
                                    switch (target.hook) {
                                        case TICK -> {
                                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterPhysicsTick", "()V", false);
                                            hookSites[0]++;
                                            methodHookSites[0]++;
                                        }
                                        case CLEAR -> {
                                            visitVarInsn(Opcodes.ALOAD, 0);
                                            visitFieldInsn(Opcodes.GETFIELD, target.className, "player", "L" + PLAYER_SP + ";");
                                            visitVarInsn(Opcodes.ALOAD, 0);
                                            visitFieldInsn(Opcodes.GETFIELD, target.className, "world", "Lnet/minecraft/world/World;");
                                            visitVarInsn(Opcodes.ALOAD, 0);
                                            visitFieldInsn(Opcodes.GETFIELD, target.className, "session",
                                                    "Lwi/unigrav/core/client/PlayerPhysicsClientSession;");
                                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterPhysicsClear",
                                                    "(L" + PLAYER_SP + ";Lnet/minecraft/world/World;Ljava/lang/Object;)V", false);
                                            hookSites[0]++;
                                            methodHookSites[0]++;
                                        }
                                        case DISCONNECT -> {
                                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterPhysicsDisconnect", "()V", false);
                                            hookSites[0]++;
                                            methodHookSites[0]++;
                                        }
                                        case UIE_CACHE_CLEAR -> {
                                            visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterUiEyeCacheClear", "()V", false);
                                            hookSites[0]++;
                                            methodHookSites[0]++;
                                        }
                                        default -> { }
                                    }
                                } else if (opcode == Opcodes.ARETURN) {
                                    if (target.hook == Hook.REVO_PROVIDER) {
                                        visitInsn(Opcodes.DUP);
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterEyeProvider",
                                                "(L" + CAMERA_ANCHOR + ";L" + CAMERA_PROVIDER + ";L" + CAMERA_CONTEXT + ";)V",
                                                false);
                                        hookSites[0]++;
                                        methodHookSites[0]++;
                                    } else if (target.hook == Hook.UIE_API_SAMPLE) {
                                        visitInsn(Opcodes.DUP);
                                        visitFieldInsn(Opcodes.GETSTATIC, target.className, "evaluatedEyeAnchorProviderId",
                                                "Ljava/lang/String;");
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS, "afterUiEyeApiSample",
                                                "(L" + CAMERA_ANCHOR + ";Ljava/lang/String;L" + CAMERA_CONTEXT + ";)V", false);
                                        hookSites[0]++;
                                        methodHookSites[0]++;
                                    }
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                long expected = TARGETS.values().stream().filter(target -> target.className.equals(className)).count();
                if (matches.size() != expected) {
                    markFailure("transform-error", className + " expected=" + expected
                            + " exact target methods; matched=" + matches.keySet());
                    return null;
                }
                validateFields(className, observedFields);
                matches.forEach((key, target) -> {
                    MATCH_COUNTS.get(key).incrementAndGet();
                    append("transform-match", key + " static=" + target.isStatic + " loader="
                            + (loader == null ? "bootstrap" : loader.getClass().getName()));
                });
                append("transform-class", className + " method-count=" + matches.size() + " hook-sites=" + hookSites[0]);
                return writer.toByteArray();
            } catch (Throwable cause) {
                markFailure("transform-error", className + " transform failed", cause);
                return null;
            }
        }
    }

    private static void validateFields(String className, Map<String, String> observedFields) {
        Map<String, String> required = new LinkedHashMap<>();
        if (className.equals("wi/unigrav/core/client/PlayerPhysicsClient")) {
            required.put("player", "L" + PLAYER_SP + ";/2");
            required.put("world", "Lnet/minecraft/world/World;/2");
            required.put("session", "Lwi/unigrav/core/client/PlayerPhysicsClientSession;/2");
        } else if (className.equals(CAMERA_API)) {
            required.put("evaluatedEyeAnchorProviderId", "Ljava/lang/String;/10");
        }
        for (Map.Entry<String, String> field : required.entrySet()) {
            String actual = observedFields.get(field.getKey());
            if (!field.getValue().equals(actual)) {
                String message = className + " required field " + field.getKey() + "=" + field.getValue()
                        + " but actual=" + actual;
                markFailure("transform-error", message);
                throw new IllegalStateException(message);
            }
        }
        if (!required.isEmpty()) append("field-match", className + " fields=" + required);
    }

    private enum Hook { TICK, CLEAR, DISCONNECT, REVO_PROVIDER, UIE_SAMPLER, UIE_FRAME, UIE_API_SAMPLE, UIE_CACHE_CLEAR }
    private record Target(String className, String method, String descriptor, Hook hook,
                          int expectedVisibility, boolean isStatic, int minimumLocals, int minimumHookSites) {}

    private static synchronized void append(String type, String detail) {
        if (output == null) return;
        String line = System.currentTimeMillis() + "\t" + type + "\t" + detail
                .replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
                .replace("\t", "\\t") + System.lineSeparator();
        long bytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (recordCount + 1 > maxRecords || evidenceBytes + bytes > maxEvidenceBytes) {
            String failure = "Native probe agent evidence bound reached: records=" + recordCount
                    + "/" + maxRecords + " bytes=" + evidenceBytes + "/" + maxEvidenceBytes;
            FAILED.set(true);
            persistFailure(failure, null);
            throw new IllegalStateException(failure);
        }
        try {
            Files.writeString(output.resolve("agent-transforms.tsv"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            recordCount++;
            evidenceBytes += bytes;
        } catch (IOException cause) {
            FAILED.set(true);
            throw new IllegalStateException("Cannot append native probe agent evidence", cause);
        }
    }

    private static void markFailure(String type, String detail) {
        FAILED.set(true);
        try {
            append(type, detail);
        } catch (RuntimeException appendFailure) {
            persistFailure(detail + "\nerror while appending the failure event", appendFailure);
            throw appendFailure;
        }
        persistFailure(detail, null);
    }

    private static void persistFailure(String detail, Throwable relatedFailure) {
        try {
            Files.writeString(output.resolve("agent-failed.txt"), detail + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cause) {
            if (relatedFailure != null) cause.addSuppressed(relatedFailure);
            cause.printStackTrace(System.err);
            throw new IllegalStateException("Cannot persist native probe transform failure", cause);
        }
    }

    private static void markFailure(String type, String detail, Throwable cause) {
        FAILED.set(true);
        java.io.StringWriter trace = new java.io.StringWriter();
        cause.printStackTrace(new java.io.PrintWriter(trace));
        markFailure(type, detail + "\noriginal cause/stack/suppressed:\n" + trace);
    }

    private static long positiveLongProperty(String name, long fallback) {
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

    private static void writeSummary() {
        boolean allMatched = MATCH_COUNTS.values().stream().allMatch(count -> count.get() == 1);
        if (!allMatched) FAILED.set(true);
        StringBuilder summary = new StringBuilder("state=").append(FAILED.get() ? "FAILED" : "OBSERVED")
                .append("\nrecords=").append(recordCount).append(" bytes=").append(evidenceBytes)
                .append(" maxRecords=").append(maxRecords).append(" maxBytes=").append(maxEvidenceBytes).append('\n');
        TARGETS.forEach((key, target) -> summary.append("required=").append(key)
                .append(" matched=").append(MATCH_COUNTS.get(key).get())
                .append(" state=").append(MATCH_COUNTS.get(key).get() == 1 ? "MATCHED" : "UNMATCHED")
                .append('\n'));
        try {
            Files.writeString(output.resolve("agent-targets.txt"), summary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            TARGETS.keySet().stream().filter(key -> MATCH_COUNTS.get(key).get() != 1).forEach(key -> {
                FAILED.set(true);
                try {
                    Files.writeString(output.resolve("agent-failed.txt"), "unmatched required target: " + key
                                    + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException cause) {
                    cause.printStackTrace(System.err);
                }
            });
        } catch (IOException cause) {
            FAILED.set(true);
            persistFailure("cannot write final target list", cause);
            cause.printStackTrace(System.err);
        }
    }
}
