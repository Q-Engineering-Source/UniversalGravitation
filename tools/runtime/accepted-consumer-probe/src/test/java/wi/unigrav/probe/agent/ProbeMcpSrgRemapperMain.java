package wi.unigrav.probe.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Test-tool-only MCP to SRG remapper with a pinned original-client ABI oracle. */
public final class ProbeMcpSrgRemapperMain {
    private static final String MINECRAFT_PREFIX = "net/minecraft/";
    private static final Set<String> VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES = Set.of(
            "net/minecraft/launchwrapper/Launch",
            "net/minecraft/launchwrapper/LaunchClassLoader");

    private ProbeMcpSrgRemapperMain() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 10) {
            throw new IllegalArgumentException("Expected: <probe-mcp.jar> <mcp-srg.srg> <mcp-api.jar> "
                    + "<notch-srg.srg> <original-client.jar> <probe-srg.jar> <srg-api-verifier-shim.jar> "
                    + "<member-report.tsv> <verifier-shim-external-api-provider.jar> "
                    + "<launchwrapper-identity-provider.jar>");
        }
        Path probeInput = Path.of(arguments[0]);
        Path mcpSrgPath = Path.of(arguments[1]);
        Path mcpApiPath = Path.of(arguments[2]);
        Path notchSrgPath = Path.of(arguments[3]);
        Path originalClientPath = Path.of(arguments[4]);
        Path probeOutput = Path.of(arguments[5]);
        Path verifierShimOutput = Path.of(arguments[6]);
        Path reportPath = Path.of(arguments[7]);
        Path verifierShimExternalApiPath = Path.of(arguments[8]);
        Path launchWrapperIdentityProviderPath = Path.of(arguments[9]);
        for (Path output : List.of(probeOutput, verifierShimOutput, reportPath)) {
            if (Files.exists(output)) throw new IOException("Refusing to overwrite ABI/remap output: " + output);
        }

        Mappings mappings = Mappings.read(mcpSrgPath);
        NotchMappings notchMappings = NotchMappings.read(notchSrgPath);
            try (ClassIndex mcpApi = new ClassIndex(mcpApiPath, "pinned mapped MCP API");
                 ClassIndex originalClient = new ClassIndex(originalClientPath, "pinned original Cleanroom client");
                 ClassIndex verifierShimExternalApi = new ClassIndex(verifierShimExternalApiPath,
                     "pinned verifier-shim external API provider");
                 ClassIndex launchWrapperIdentityProvider = new ClassIndex(launchWrapperIdentityProviderPath,
                         "pinned LaunchWrapper identity provider")) {
            SourceReferences source = collectMinecraftReferences(probeInput);
            if (source.members.isEmpty()) {
                throw new IOException("Probe bytecode has no net.minecraft member references; refusing empty ABI pass");
            }

            TreeMap<MemberRef, MemberMapping> memberMappings = new TreeMap<>();
            TreeMap<MemberRef, RuntimeMemberMapping> runtimeMappings = new TreeMap<>();
            TreeMap<String, RuntimeClassMapping> classMappings = new TreeMap<>();
            for (String sourceClass : new TreeSet<>(source.classes)) {
                mcpApi.requireClass(sourceClass);
                String srgClass = mappings.mapClassRequired(sourceClass);
                String notchClass = notchMappings.toNotchClass(srgClass);
                originalClient.requireClass(notchClass);
                int apiClassAccess = mcpApi.classAccess(sourceClass);
                int runtimeClassAccess = originalClient.classAccess(notchClass);
                boolean apiInterface = (apiClassAccess & Opcodes.ACC_INTERFACE) != 0;
                boolean runtimeInterface = (runtimeClassAccess & Opcodes.ACC_INTERFACE) != 0;
                if (apiInterface != runtimeInterface) {
                    throw new IOException("Mapped MCP API and pinned original class disagree on interface shape for "
                            + sourceClass + " -> " + notchClass + "; MCP access=" + apiClassAccess
                            + " original access=" + runtimeClassAccess);
                }
                if ((apiClassAccess & Opcodes.ACC_PUBLIC) == 0 || (runtimeClassAccess & Opcodes.ACC_PUBLIC) == 0) {
                    throw new IOException("Probe references a non-public Minecraft class: " + sourceClass
                            + " -> " + notchClass + "; MCP access=" + apiClassAccess
                            + " original access=" + runtimeClassAccess);
                }
                classMappings.put(sourceClass, new RuntimeClassMapping(srgClass, notchClass,
                        apiClassAccess, runtimeClassAccess));
            }
            for (Map.Entry<MemberRef, ReferenceUses> entry : source.members.entrySet()) {
                MemberRef reference = entry.getKey();
                MemberDeclaration declaration = mcpApi.declaration(reference);
                validatePublicAccess(reference, declaration, "mapped MCP API");
                MemberMapping mapping = mappings.resolveRequired(reference, declaration);
                validateOpcodeStaticness(reference, entry.getValue(), declaration.access);
                int apiOwnerAccess = mcpApi.classAccess(reference.owner);
                boolean apiOwnerInterface = (apiOwnerAccess & Opcodes.ACC_INTERFACE) != 0;
                validateOwnerInterfaceUse(reference, entry.getValue(), apiOwnerInterface, "mapped MCP API");
                RuntimeMemberMapping runtime = notchMappings.resolveAndVerify(mapping, entry.getValue(),
                        apiOwnerInterface, originalClient);
                memberMappings.put(reference, mapping);
                runtimeMappings.put(reference, runtime);
            }

            Remapper strictProbeRemapper = mappings.asmRemapper(mcpApi, true, Set.of());
            StructuralShimClassMapping verifierShimClassMapping = StructuralShimClassMapping.build(mcpApi, mappings);
            StructuralShimMemberResolver verifierShimMemberResolver = new StructuralShimMemberResolver(mcpApi,
                    verifierShimExternalApi, launchWrapperIdentityProvider, mappings, verifierShimClassMapping);
            verifierShimMemberResolver.validateAllDeclaredMembers();
            Remapper verifierShimRemapper = verifierShimClassMapping.asmRemapper(verifierShimMemberResolver);
            remapArchive(probeInput, probeOutput, strictProbeRemapper);
            VerifierShimStructureReport verifierShimStructure = remapVerifierShim(mcpApiPath,
                    verifierShimOutput, verifierShimRemapper, verifierShimClassMapping);

            Map<MemberRef, ReferenceUses> remappedReferences = collectMinecraftReferences(probeOutput).members;
            Map<MemberRef, ReferenceUses> expectedReferences = new TreeMap<>();
            for (Map.Entry<MemberRef, ReferenceUses> entry : source.members.entrySet()) {
                MemberMapping mapping = memberMappings.get(entry.getKey());
                expectedReferences.merge(mapping.asReference(), entry.getValue(), ReferenceUses::merge);
            }
            if (!expectedReferences.equals(remappedReferences)) {
                throw new IOException("Remapped probe references differ from exact MCP-SRG mappings. expected="
                        + expectedReferences + " actual=" + remappedReferences);
            }

            int verifierShimFieldCount = (int) verifierShimStructure.members.stream()
                    .filter(member -> member.kind.equals("FIELD")).count();
            int verifierShimMethodCount = (int) verifierShimStructure.members.stream()
                    .filter(member -> member.kind.equals("METHOD")).count();
            int verifierShimThrowStubCount = verifierShimStructure.classes.stream()
                    .mapToInt(VerifierShimStructureRecord::throwStubCount).sum();
            int verifierShimAbstractNativeNoCodeCount = verifierShimStructure.classes.stream()
                    .mapToInt(VerifierShimStructureRecord::abstractNativeNoCodeCount).sum();
            StringBuilder report = new StringBuilder();
            report.append("state=PASS\n")
                    .append("scope=BYTECODE_ABI_PREFLIGHT_ONLY\n")
                    .append("verifierShimState=GENERATED_NOT_RUNTIME_ORACLE\n")
                    .append("verifierShimContents=MAPPED_NET_MINECRAFT_CLASS_STRUCTURE_WITH_NON_EXECUTABLE_METHOD_BODY_STUBS\n")
                    .append("verifierShimMethodBodyPolicy=ALL_CONCRETE_METHODS_CONSTRUCTORS_AND_CLINIT_THROW_UNSUPPORTED_OPERATION_EXCEPTION;"
                            + "ABSTRACT_NATIVE_RETAINED_WITHOUT_CODE\n")
                    .append("verifierShimSourceCodeMemberRemap=OMITTED_BY_STUBBING_BEFORE_CLASS_REMAPPER\n")
                    .append("verifierShimClassMappingCount=")
                    .append(verifierShimClassMapping.classDecisionRows().size()).append('\n')
                    .append("verifierShimMappedExactClassCount=")
                    .append(verifierShimClassMapping.exactMappingCount()).append('\n')
                    .append("verifierShimPreservedActualDeclarationClassCount=")
                    .append(verifierShimClassMapping.preservedDeclarationCount()).append('\n')
                    .append("verifierShimStructuralClassReferenceEdgeCount=")
                    .append(verifierShimClassMapping.edgeRows().size()).append('\n')
                    .append("verifierShimStructuralMemberDecisionCount=")
                    .append(verifierShimMemberResolver.decisionRows().size()).append('\n')
                    .append("verifierShimMemberResolutionPolicy=EXACT_MCP_SRG_MD_OR_FD_WHEN_PRESENT;"
                            + "OTHER_DIRECT_ACTUAL_DECLARATIONS_PRESERVE_SOURCE_NAME;"
                            + "SIBLING_DESCRIPTOR_ROWS_ARE_SEPARATE_MEMBERS\n")
                    .append("verifierShimStructureState=PASS\n")
                    .append("verifierShimStructureComparison=REMAPPED_MCP_API_CLASSNODE_VS_GENERATED_SHIM_CLASSNODE\n")
                    .append("verifierShimStructureClassCount=").append(verifierShimStructure.classes.size()).append('\n')
                    .append("verifierShimStructureFieldCount=").append(verifierShimFieldCount).append('\n')
                    .append("verifierShimStructureMethodCount=").append(verifierShimMethodCount).append('\n')
                    .append("verifierShimThrowStubCount=").append(verifierShimThrowStubCount).append('\n')
                    .append("verifierShimAbstractNativeNoCodeCount=")
                    .append(verifierShimAbstractNativeNoCodeCount).append('\n')
                    .append("verifierShimStructureUnknownAttributes=OUTSIDE_STANDARD_CLASSNODE_COMPARISON;"
                            + "NO_PRESERVATION_CLAIM;METHOD_CUSTOM_ATTRIBUTES_DROPPED\n")
                    .append("verifierShimIdentityExternalTypes=")
                    .append(String.join(",", new TreeSet<>(VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES))).append('\n')
                    .append("mcpSrgPath=").append(mcpSrgPath.toAbsolutePath().normalize()).append('\n')
                    .append("mcpSrgSha256=").append(sha256(mcpSrgPath)).append('\n')
                    .append("notchSrgPath=").append(notchSrgPath.toAbsolutePath().normalize()).append('\n')
                    .append("notchSrgSha256=").append(sha256(notchSrgPath)).append('\n')
                    .append("mcpApiPath=").append(mcpApiPath.toAbsolutePath().normalize()).append('\n')
                    .append("mcpApiSha256=").append(sha256(mcpApiPath)).append('\n')
                    .append("verifierShimExternalApiProviderPath=")
                    .append(verifierShimExternalApiPath.toAbsolutePath().normalize()).append('\n')
                    .append("verifierShimExternalApiProviderSha256=")
                    .append(sha256(verifierShimExternalApiPath)).append('\n')
                    .append("originalClientJarPath=").append(originalClientPath.toAbsolutePath().normalize()).append('\n')
                    .append("originalClientJarSha256=").append(sha256(originalClientPath)).append('\n')
                    .append("probeMcpSha256=").append(sha256(probeInput)).append('\n')
                    .append("probeSrgSha256=").append(sha256(probeOutput)).append('\n')
                    .append("verifierShimSha256=").append(sha256(verifierShimOutput)).append('\n')
                    .append("uniqueMinecraftReferences=").append(source.members.size()).append('\n')
                    .append("minecraftClassReferences=").append(source.classes.size()).append('\n')
                    .append("#classes\n")
                    .append("sourceClass\tmcpAccess\tmcpIsInterface\tsrgClass\tnotchClass\toriginalAccess"
                            + "\toriginalIsInterface\n");
            for (Map.Entry<String, RuntimeClassMapping> entry : classMappings.entrySet()) {
                RuntimeClassMapping mapping = entry.getValue();
                report.append(entry.getKey()).append('\t').append(mapping.mcpAccess).append('\t')
                        .append((mapping.mcpAccess & Opcodes.ACC_INTERFACE) != 0).append('\t')
                        .append(mapping.srgClass).append('\t').append(mapping.notchClass).append('\t')
                        .append(mapping.originalAccess).append('\t')
                        .append((mapping.originalAccess & Opcodes.ACC_INTERFACE) != 0).append('\n');
            }
            report.append("#members\n")
                    .append("kind\tsourceOwner\tsourceName\tsourceDescriptor\tmcpDeclarationOwner\tmcpAccess"
                            + "\tmcpSymbolicOwnerIsInterface\tsourceUses\tsrgSymbolicOwner"
                            + "\tsrgDeclarationOwner\tsrgName\tsrgDescriptor\tnotchSymbolicOwner"
                            + "\tnotchDeclarationOwner\tnotchName\tnotchDescriptor\tactualDeclaredOwner"
                            + "\tactualDeclaredName\tactualDeclaredDescriptor\truntimeAccess"
                            + "\toriginalSymbolicOwnerAccess\toriginalSymbolicOwnerIsInterface"
                            + "\toriginalDeclarationClassAccess\tcount\n");
            for (Map.Entry<MemberRef, MemberMapping> entry : memberMappings.entrySet()) {
                MemberRef reference = entry.getKey();
                MemberMapping mapping = entry.getValue();
                RuntimeMemberMapping runtime = runtimeMappings.get(reference);
                MemberDeclaration apiDeclaration = mcpApi.declaration(reference);
                report.append(reference.kind).append('\t').append(reference.owner).append('\t')
                        .append(reference.name).append('\t').append(reference.descriptor).append('\t')
                        .append(mapping.mcpDeclarationOwner).append('\t').append(apiDeclaration.access).append('\t')
                        .append((mcpApi.classAccess(reference.owner) & Opcodes.ACC_INTERFACE) != 0).append('\t')
                        .append(source.members.get(reference).summary()).append('\t')
                        .append(mapping.srgSymbolicOwner).append('\t').append(mapping.srgDeclarationOwner).append('\t')
                        .append(mapping.srgName).append('\t').append(mapping.srgDescriptor).append('\t')
                        .append(runtime.reference.owner).append('\t').append(runtime.declaredReference.owner).append('\t')
                        .append(runtime.reference.name).append('\t')
                        .append(runtime.reference.descriptor).append('\t').append(runtime.declaration.owner).append('\t')
                        .append(runtime.declaration.name).append('\t').append(runtime.declaration.descriptor).append('\t')
                        .append(runtime.declaration.access).append('\t')
                        .append(runtime.symbolicOwnerAccess).append('\t').append(runtime.symbolicOwnerInterface)
                        .append('\t').append(runtime.declarationClassAccess).append('\t')
                        .append(source.members.get(reference).count()).append('\n');
            }
            report.append("#verifierShimClassMappings\n")
                    .append("sourceClass\ttargetClass\tdecision\tactualDeclaration\tclosureSources"
                            + "\tnestHostSource\tnestHostTarget\tsourceRuntimePackage\ttargetRuntimePackage"
                            + "\tpackageState\n");
            for (StructuralShimClassDecision entry : verifierShimClassMapping.classDecisionRows()) {
                report.append(entry.toTsv()).append('\n');
            }
            report.append("#verifierShimClassReferenceEdges\n")
                    .append("sourceClass\treferencedClass\tdisposition\tmechanism\n");
            for (StructuralShimClassEdge entry : verifierShimClassMapping.edgeRows()) {
                report.append(entry.toTsv()).append('\n');
            }
            report.append("#verifierShimStructuralMemberDecisions\n")
                    .append("kind\tsourceOwner\tsourceName\tsourceDescriptor\tdeclarationAccess"
                            + "\ttargetOwner\ttargetName\ttargetDescriptor\tdecision\tuses\n");
            for (StructuralShimMemberDecision entry : verifierShimMemberResolver.decisionRows()) {
                report.append(entry.toTsv()).append('\n');
            }
            report.append("#verifierShimStructureClasses\n")
                    .append("sourceClass\tmappedClass\tclassAccess\tsuperName\tinterfaces\tfieldCount"
                            + "\tmethodCount\tthrowStubCount\tabstractNativeNoCodeCount\tsourceMappedStructureSha256"
                            + "\tgeneratedStructureSha256\tstructureMatch\n");
            for (VerifierShimStructureRecord entry : verifierShimStructure.classes) {
                report.append(entry.sourceClass).append('\t').append(entry.mappedClass).append('\t')
                        .append(entry.classAccess).append('\t').append(entry.superName).append('\t')
                        .append(String.join(";", entry.interfaces)).append('\t')
                        .append(entry.fieldCount).append('\t').append(entry.methodCount).append('\t')
                        .append(entry.throwStubCount).append('\t').append(entry.abstractNativeNoCodeCount)
                        .append('\t').append(entry.sourceMappedStructureSha256).append('\t')
                        .append(entry.generatedStructureSha256).append('\t').append(entry.structureMatch).append('\n');
            }
            report.append("#verifierShimStructureMembers\n")
                    .append("mappedClass\tkind\tname\taccess\tdescriptor\tsignature\texceptions"
                            + "\tsourceMappedStandardMetadataSha256\tgeneratedStandardMetadataSha256"
                            + "\tstructureMatch\n");
            for (VerifierShimMemberRecord entry : verifierShimStructure.members) {
                report.append(entry.mappedClass).append('\t').append(entry.kind).append('\t').append(entry.name)
                        .append('\t').append(entry.access).append('\t').append(entry.descriptor).append('\t')
                        .append(entry.signature).append('\t').append(entry.exceptions).append('\t')
                        .append(entry.sourceMappedStandardMetadataSha256).append('\t')
                        .append(entry.generatedStandardMetadataSha256).append('\t')
                        .append(entry.structureMatch).append('\n');
            }
            report.append("#verifierShimExternalTypes\n")
                    .append("className\tmcpClassAccess\truntimeClassAccess\tshape\n");
            for (String externalType : List.of("net/minecraftforge/registries/IForgeRegistryEntry$Impl",
                    "net/minecraftforge/registries/IRegistryDelegate")) {
                int mcpClassAccess = mcpApi.classAccess(externalType);
                int runtimeClassAccess = verifierShimExternalApi.classAccess(externalType);
                String shape = (runtimeClassAccess & Opcodes.ACC_INTERFACE) != 0 ? "INTERFACE" : "CLASS";
                report.append(externalType).append('\t').append(mcpClassAccess).append('\t')
                        .append(runtimeClassAccess).append('\t').append(shape).append('\n');
            }
            Files.writeString(reportPath, report, StandardCharsets.UTF_8);
            System.out.println("MCP_SRG_NOTCH_ORIGINAL_CLIENT_ABI_PASS members=" + source.members.size()
                    + " classes=" + source.classes.size() + " probeSha256=" + sha256(probeOutput)
                    + " verifierShimSha256=" + sha256(verifierShimOutput)
                    + " originalClientSha256=" + sha256(originalClientPath));
        }
    }

    private static void validateOpcodeStaticness(MemberRef reference, ReferenceUses uses, int access)
            throws IOException {
        boolean staticMember = (access & Opcodes.ACC_STATIC) != 0;
        if (uses.staticUse && !staticMember) {
            throw new IOException("Static bytecode use resolves to non-static MCP declaration: " + reference
                    + " access=" + access + " uses=" + uses.summary());
        }
        if (uses.instanceUse && staticMember) {
            throw new IOException("Instance bytecode use resolves to static MCP declaration: " + reference
                    + " access=" + access + " uses=" + uses.summary());
        }
    }

    private static void validatePublicAccess(MemberRef reference, MemberDeclaration declaration, String source)
            throws IOException {
        if ((declaration.access & Opcodes.ACC_PUBLIC) == 0) {
            throw new IOException("Probe helper is outside the package and cannot legally access " + source
                    + " declaration " + reference + " declared by " + declaration.owner
                    + "; access=" + declaration.access);
        }
    }

    private static void validateOwnerInterfaceUse(MemberRef reference, ReferenceUses uses,
                                                  boolean ownerIsInterface, String source) throws IOException {
        if (uses.ownerInterface && !ownerIsInterface || uses.ownerClass && ownerIsInterface) {
            throw new IOException("Member reference interface-owner flag disagrees with " + source + " owner "
                    + reference.owner + " for " + reference + "; uses=" + uses.summary()
                    + " ownerIsInterface=" + ownerIsInterface);
        }
    }

    private static void remapArchive(Path input, Path output, Remapper remapper) throws IOException {
        remapArchive(input, output, remapper, null);
    }

    private static VerifierShimStructureReport remapVerifierShim(Path input, Path output, Remapper remapper,
                                                                 StructuralShimClassMapping classMapping)
            throws IOException {
        remapArchive(input, output, remapper, classMapping.outputSourceClasses());
        return verifyVerifierShimStructure(input, output, remapper, classMapping);
    }

    private static void remapArchive(Path input, Path output, Remapper remapper,
                                    Set<String> verifierShimMappedClasses) throws IOException {
        Set<String> written = new HashSet<>();
        try (ZipFile source = new ZipFile(input.toFile());
             ZipOutputStream destination = new ZipOutputStream(Files.newOutputStream(output))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (verifierShimMappedClasses != null) {
                    if (!entry.getName().endsWith(".class")) continue;
                    String internalName = entry.getName().substring(0, entry.getName().length() - ".class".length());
                    if (!internalName.startsWith(MINECRAFT_PREFIX)
                            || !verifierShimMappedClasses.contains(internalName)) continue;
                }
                String outputName;
                byte[] payload;
                try {
                    try (var stream = source.getInputStream(entry)) {
                        payload = stream.readAllBytes();
                    }
                    outputName = entry.getName();
                    if (outputName.endsWith(".class")) {
                        ClassReader reader = new ClassReader(payload);
                        String mappedName = remapper.mapType(reader.getClassName());
                        outputName = mappedName + ".class";
                        if (verifierShimMappedClasses == null) {
                            ClassWriter writer = new ClassWriter(0);
                            reader.accept(new ClassRemapper(writer, remapper), 0);
                            payload = writer.toByteArray();
                        } else {
                            ClassNode sourceNode = new ClassNode(Opcodes.ASM9);
                            reader.accept(sourceNode, 0);
                            replaceVerifierShimMethodBodies(sourceNode, entry.getName());
                            ClassNode mappedNode = new ClassNode(Opcodes.ASM9);
                            sourceNode.accept(new ClassRemapper(mappedNode, remapper));
                            ClassWriter writer = new ClassWriter(0);
                            mappedNode.accept(writer);
                            payload = writer.toByteArray();
                        }
                    }
                } catch (IOException | RuntimeException cause) {
                    throw new IOException("Failed to remap archive entry input=" + input.toAbsolutePath().normalize()
                            + " entry=" + entry.getName() + " verifierShimFiltered="
                            + (verifierShimMappedClasses != null), cause);
                }
                if (!written.add(outputName)) throw new IOException("Duplicate remapped archive entry " + outputName);
                ZipEntry outputEntry = new ZipEntry(outputName);
                if (entry.getTime() >= 0) outputEntry.setTime(entry.getTime());
                destination.putNextEntry(outputEntry);
                destination.write(payload);
                destination.closeEntry();
            }
        }
    }

    private static void replaceVerifierShimMethodBodies(ClassNode owner, String entryName) throws IOException {
        for (MethodNode method : owner.methods) {
            boolean noCode = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
            if (noCode) {
                if (method.instructions.size() != 0
                        || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()
                        || method.localVariables != null && !method.localVariables.isEmpty()
                        || method.visibleLocalVariableAnnotations != null
                        && !method.visibleLocalVariableAnnotations.isEmpty()
                        || method.invisibleLocalVariableAnnotations != null
                        && !method.invisibleLocalVariableAnnotations.isEmpty()) {
                    throw new IOException("Abstract/native verifier-shim method unexpectedly has Code data in "
                            + entryName + ": " + owner.name + "." + method.name + method.desc);
                }
                method.attrs = null;
                continue;
            }
            if (method.instructions.size() == 0) {
                throw new IOException("Concrete verifier-shim method has no source Code to replace in " + entryName
                        + ": " + owner.name + "." + method.name + method.desc);
            }

            method.instructions.clear();
            if (method.tryCatchBlocks != null) method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            // Unknown method attributes may contain offsets into the original Code attribute.
            method.attrs = null;
            method.maxStack = 2;
            method.maxLocals = argumentLocalSlots(method);
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/UnsupportedOperationException", "<init>", "()V", false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
        }
    }

    private static int argumentLocalSlots(MethodNode method) {
        int slots = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) slots += argument.getSize();
        return slots;
    }

    private static VerifierShimStructureReport verifyVerifierShimStructure(Path sourceJar, Path shimJar,
                                                                            Remapper remapper,
                                                                            StructuralShimClassMapping classMapping)
            throws IOException {
        List<VerifierShimStructureRecord> classRecords = new ArrayList<>();
        List<VerifierShimMemberRecord> memberRecords = new ArrayList<>();
        Set<String> expectedShimEntries = new TreeSet<>();
        try (ZipFile source = new ZipFile(sourceJar.toFile()); ZipFile shim = new ZipFile(shimJar.toFile())) {
            for (String sourceName : classMapping.outputSourceClasses()) {
                ZipEntry sourceEntry = source.getEntry(sourceName + ".class");
                if (sourceEntry == null) {
                    throw new IOException("Structural verifier-shim source declaration disappeared from MCP API: "
                            + sourceName);
                }
                ClassNode sourceNode = new ClassNode(Opcodes.ASM9);
                try (var input = source.getInputStream(sourceEntry)) {
                    new ClassReader(input).accept(sourceNode, 0);
                }
                if (!sourceNode.name.equals(sourceName)) {
                    throw new IOException("Structural verifier-shim source entry/internal-name mismatch: entry="
                            + sourceName + ".class actual=" + sourceNode.name);
                }
                replaceVerifierShimMethodBodies(sourceNode, sourceEntry.getName());
                ClassNode expectedNode = new ClassNode(Opcodes.ASM9);
                sourceNode.accept(new ClassRemapper(expectedNode, remapper));
                String plannedName = classMapping.mapClass(sourceName);
                if (!expectedNode.name.equals(plannedName)) {
                    throw new IOException("Structural verifier-shim remapper output differs from its frozen class plan: "
                            + sourceName + " expected=" + plannedName + " actual=" + expectedNode.name);
                }
                String shimEntryName = plannedName + ".class";
                if (!expectedShimEntries.add(shimEntryName)) {
                    throw new IOException("Multiple mapped MCP API classes resolve to verifier-shim entry "
                            + shimEntryName);
                }
                ZipEntry shimEntry = shim.getEntry(shimEntryName);
                if (shimEntry == null) {
                    throw new IOException("Generated verifier shim is missing mapped API class " + shimEntryName
                            + " from structural closure source " + sourceEntry.getName());
                }
                byte[] shimBytes;
                try (var input = shim.getInputStream(shimEntry)) {
                    shimBytes = input.readAllBytes();
                }
                ClassNode actualNode = new ClassNode(Opcodes.ASM9);
                new ClassReader(shimBytes).accept(actualNode, 0);
                String expectedStructure = classStructureSignature(expectedNode);
                String actualStructure = classStructureSignature(actualNode);
                if (!expectedStructure.equals(actualStructure)) {
                    throw new IOException("Generated verifier-shim class structure differs from remapped source MCP API: "
                            + sourceEntry.getName() + " -> " + shimEntryName + "; expectedSha256="
                            + sha256Text(expectedStructure) + " actualSha256=" + sha256Text(actualStructure));
                }
                BodyPolicyCounts bodyCounts = verifyVerifierShimBodies(actualNode, shimBytes, shimEntryName);
                classRecords.add(new VerifierShimStructureRecord(sourceNode.name, actualNode.name, actualNode.access,
                        String.valueOf(actualNode.superName), List.copyOf(actualNode.interfaces),
                        actualNode.fields.size(), actualNode.methods.size(), bodyCounts.throwStubCount,
                        bodyCounts.abstractNativeNoCodeCount, sha256Text(expectedStructure),
                        sha256Text(actualStructure), "PASS"));
                for (int index = 0; index < actualNode.fields.size(); index++) {
                    FieldNode expectedField = expectedNode.fields.get(index);
                    FieldNode actualField = actualNode.fields.get(index);
                    String expectedFieldShape = fieldStructureSignature(expectedField);
                    String actualFieldShape = fieldStructureSignature(actualField);
                    if (!expectedFieldShape.equals(actualFieldShape)) {
                        throw new IOException("Verifier-shim field declaration differs after classfile emission: "
                                + shimEntryName + " fieldIndex=" + index + " expected=" + expectedField.name
                                + expectedField.desc + " actual=" + actualField.name + actualField.desc);
                    }
                    memberRecords.add(new VerifierShimMemberRecord(actualNode.name, "FIELD", actualField.name,
                            actualField.access, actualField.desc, actualField.signature, "",
                            sha256Text(expectedFieldShape), sha256Text(actualFieldShape), "PASS"));
                }
                for (int index = 0; index < actualNode.methods.size(); index++) {
                    MethodNode expectedMethod = expectedNode.methods.get(index);
                    MethodNode actualMethod = actualNode.methods.get(index);
                    String expectedMethodShape = methodDeclarationSignature(expectedMethod);
                    String actualMethodShape = methodDeclarationSignature(actualMethod);
                    if (!expectedMethodShape.equals(actualMethodShape)) {
                        throw new IOException("Verifier-shim method declaration differs after classfile emission: "
                                + shimEntryName + " methodIndex=" + index + " expected=" + expectedMethod.name
                                + expectedMethod.desc + " actual=" + actualMethod.name + actualMethod.desc);
                    }
                    memberRecords.add(new VerifierShimMemberRecord(actualNode.name, "METHOD", actualMethod.name,
                            actualMethod.access, actualMethod.desc, actualMethod.signature,
                            actualMethod.exceptions == null ? "" : String.join(";", actualMethod.exceptions),
                            sha256Text(expectedMethodShape), sha256Text(actualMethodShape), "PASS"));
                }
            }

            Set<String> actualShimEntries = new TreeSet<>();
            var shimEntries = shim.entries();
            while (shimEntries.hasMoreElements()) {
                ZipEntry entry = shimEntries.nextElement();
                if (entry.isDirectory()) continue;
                if (!entry.getName().endsWith(".class")) {
                    throw new IOException("Generated structural verifier shim contains a non-class entry: "
                            + entry.getName());
                }
                actualShimEntries.add(entry.getName());
            }
            if (!expectedShimEntries.equals(actualShimEntries)) {
                throw new IOException("Generated verifier-shim class entries differ from the actual-declaration "
                        + "structural closure; "
                        + "expected=" + expectedShimEntries.size() + " actual=" + actualShimEntries.size());
            }
        }
        if (classRecords.isEmpty()) throw new IOException("Verifier shim contains no mapped Minecraft API classes");
        return new VerifierShimStructureReport(List.copyOf(classRecords), List.copyOf(memberRecords));
    }

    private static BodyPolicyCounts verifyVerifierShimBodies(ClassNode owner, byte[] classBytes, String entryName)
            throws IOException {
        Set<String> methodsWithCode = methodsWithCode(classBytes);
        int throwStubCount = 0;
        int abstractNativeNoCodeCount = 0;
        for (MethodNode method : owner.methods) {
            String methodKey = method.name + "\u0000" + method.desc;
            boolean shouldHaveNoCode = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0;
            boolean hasCodeAttribute = methodsWithCode.contains(methodKey);
            if (shouldHaveNoCode) {
                if (hasCodeAttribute || method.instructions.size() != 0) {
                    throw new IOException("Abstract/native verifier-shim method unexpectedly has a Code attribute: "
                            + entryName + "!" + method.name + method.desc);
                }
                abstractNativeNoCodeCount++;
                continue;
            }
            if (!hasCodeAttribute || method.instructions.size() != 4
                    || !(method.instructions.get(0) instanceof TypeInsnNode newException)
                    || newException.getOpcode() != Opcodes.NEW
                    || !newException.desc.equals("java/lang/UnsupportedOperationException")
                    || method.instructions.get(1).getOpcode() != Opcodes.DUP
                    || !(method.instructions.get(2) instanceof MethodInsnNode constructor)
                    || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                    || !constructor.owner.equals("java/lang/UnsupportedOperationException")
                    || !constructor.name.equals("<init>") || !constructor.desc.equals("()V")
                    || constructor.itf
                    || method.instructions.get(3).getOpcode() != Opcodes.ATHROW) {
                throw new IOException("Concrete verifier-shim method does not contain the exact non-executable throw "
                        + "stub: " + entryName + "!" + method.name + method.desc);
            }
            if (method.maxStack != 2 || method.maxLocals != argumentLocalSlots(method)
                    || method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()
                    || method.localVariables != null && !method.localVariables.isEmpty()
                    || method.visibleLocalVariableAnnotations != null
                    && !method.visibleLocalVariableAnnotations.isEmpty()
                    || method.invisibleLocalVariableAnnotations != null
                    && !method.invisibleLocalVariableAnnotations.isEmpty()
                    || method.attrs != null && !method.attrs.isEmpty()) {
                throw new IOException("Concrete verifier-shim method retained stale Code metadata or invalid maxs: "
                        + entryName + "!" + method.name + method.desc + " maxStack=" + method.maxStack
                        + " maxLocals=" + method.maxLocals);
            }
            throwStubCount++;
        }
        if (methodsWithCode.size() != throwStubCount) {
            throw new IOException("Verifier shim contains a Code attribute outside its concrete-method declarations: "
                    + entryName + " codeMethods=" + methodsWithCode.size() + " concreteMethods=" + throwStubCount);
        }
        return new BodyPolicyCounts(throwStubCount, abstractNativeNoCodeCount);
    }

    private static Set<String> methodsWithCode(byte[] classBytes) {
        Set<String> result = new HashSet<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                String key = name + "\u0000" + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        if (!result.add(key)) {
                            throw new IllegalStateException("Duplicate Code attribute for " + key);
                        }
                    }
                };
            }
        }, 0);
        return result;
    }

    private static String classStructureSignature(ClassNode node) {
        StringBuilder result = new StringBuilder();
        appendValue(result, node.version);
        appendValue(result, node.access);
        appendValue(result, node.name);
        appendValue(result, node.signature);
        appendValue(result, node.superName);
        appendValue(result, node.interfaces);
        appendValue(result, node.sourceFile);
        appendValue(result, node.sourceDebug);
        appendValue(result, node.outerClass);
        appendValue(result, node.outerMethod);
        appendValue(result, node.outerMethodDesc);
        appendValue(result, node.nestHostClass);
        appendValue(result, node.nestMembers);
        appendValue(result, node.permittedSubclasses);
        appendAnnotations(result, node.visibleAnnotations);
        appendAnnotations(result, node.invisibleAnnotations);
        appendAnnotations(result, node.visibleTypeAnnotations);
        appendAnnotations(result, node.invisibleTypeAnnotations);
        appendValue(result, node.innerClasses == null ? null : node.innerClasses.stream()
                .map(inner -> List.of(inner.name, String.valueOf(inner.outerName), String.valueOf(inner.innerName),
                        Integer.toString(inner.access))).toList());
        if (node.module != null) throw new IllegalStateException("Module attribute is outside the pinned MCP shim scope");
        appendValue(result, node.recordComponents == null ? null : node.recordComponents.stream().map(component -> {
            StringBuilder signature = new StringBuilder();
            appendValue(signature, component.name);
            appendValue(signature, component.descriptor);
            appendValue(signature, component.signature);
            appendAnnotations(signature, component.visibleAnnotations);
            appendAnnotations(signature, component.invisibleAnnotations);
            appendAnnotations(signature, component.visibleTypeAnnotations);
            appendAnnotations(signature, component.invisibleTypeAnnotations);
            return signature.toString();
        }).toList());
        List<String> fields = node.fields.stream().map(ProbeMcpSrgRemapperMain::fieldStructureSignature).toList();
        appendValue(result, fields);
        List<String> methods = node.methods.stream()
                .map(ProbeMcpSrgRemapperMain::methodDeclarationSignature).toList();
        appendValue(result, methods);
        return result.toString();
    }

    private static String fieldStructureSignature(FieldNode field) {
        StringBuilder result = new StringBuilder();
        appendValue(result, field.access);
        appendValue(result, field.name);
        appendValue(result, field.desc);
        appendValue(result, field.signature);
        appendValue(result, field.value);
        appendAnnotations(result, field.visibleAnnotations);
        appendAnnotations(result, field.invisibleAnnotations);
        appendAnnotations(result, field.visibleTypeAnnotations);
        appendAnnotations(result, field.invisibleTypeAnnotations);
        return result.toString();
    }

    private static String methodDeclarationSignature(MethodNode method) {
        StringBuilder result = new StringBuilder();
        appendValue(result, method.access);
        appendValue(result, method.name);
        appendValue(result, method.desc);
        appendValue(result, method.signature);
        appendValue(result, method.exceptions);
        appendValue(result, method.parameters == null ? null : method.parameters.stream()
                .map(parameter -> List.of(String.valueOf(parameter.name), Integer.toString(parameter.access))).toList());
        appendValue(result, method.annotationDefault);
        appendAnnotations(result, method.visibleAnnotations);
        appendAnnotations(result, method.invisibleAnnotations);
        appendAnnotations(result, method.visibleTypeAnnotations);
        appendAnnotations(result, method.invisibleTypeAnnotations);
        appendValue(result, method.visibleAnnotableParameterCount);
        appendValue(result, method.invisibleAnnotableParameterCount);
        appendValue(result, method.visibleParameterAnnotations);
        appendValue(result, method.invisibleParameterAnnotations);
        return result.toString();
    }

    private static void appendAnnotations(StringBuilder result,
                                          List<? extends org.objectweb.asm.tree.AnnotationNode> annotations) {
        appendValue(result, annotations);
    }

    private static void appendValue(StringBuilder result, Object value) {
        if (value == null) {
            result.append("N;");
        } else if (value instanceof Type type) {
            appendToken(result, "TYPE");
            appendToken(result, type.getDescriptor());
        } else if (value instanceof Handle handle) {
            appendToken(result, "HANDLE");
            appendValue(result, handle.getTag());
            appendToken(result, handle.getOwner());
            appendToken(result, handle.getName());
            appendToken(result, handle.getDesc());
            appendValue(result, handle.isInterface());
        } else if (value instanceof ConstantDynamic constant) {
            appendToken(result, "CONSTANT_DYNAMIC");
            appendToken(result, constant.getName());
            appendToken(result, constant.getDescriptor());
            appendValue(result, constant.getBootstrapMethod());
            appendValue(result, constant.getBootstrapMethodArgumentCount());
            for (int index = 0; index < constant.getBootstrapMethodArgumentCount(); index++) {
                appendValue(result, constant.getBootstrapMethodArgument(index));
            }
        } else if (value instanceof org.objectweb.asm.tree.AnnotationNode annotation) {
            appendToken(result, "ANNOTATION");
            appendToken(result, annotation.desc);
            if (annotation instanceof org.objectweb.asm.tree.TypeAnnotationNode typeAnnotation) {
                appendValue(result, typeAnnotation.typeRef);
                appendToken(result, String.valueOf(typeAnnotation.typePath));
            }
            appendValue(result, annotation.values);
        } else if (value instanceof List<?> list) {
            appendToken(result, "LIST");
            appendValue(result, list.size());
            for (Object item : list) appendValue(result, item);
        } else if (value instanceof Object[] array) {
            appendToken(result, "ARRAY");
            appendValue(result, array.length);
            for (Object item : array) appendValue(result, item);
        } else {
            appendToken(result, value.getClass().getName());
            appendToken(result, String.valueOf(value));
        }
    }

    private static void appendToken(StringBuilder result, String token) {
        result.append(token.length()).append(':').append(token).append(';');
    }

    private static String sha256Text(String value) throws IOException {
        try {
            return java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException cause) {
            throw new IOException("SHA-256 is unavailable while verifying verifier-shim structure", cause);
        }
    }

    private static SourceReferences collectMinecraftReferences(Path archive) throws IOException {
        SourceReferences references = new SourceReferences();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (var input = zip.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName,
                                          String[] interfaces) {
                            addClass(references, name);
                            addClass(references, superName);
                            if (interfaces != null) for (String parent : interfaces) addClass(references, parent);
                        }

                        @Override
                        public void visitOuterClass(String owner, String name, String descriptor) {
                            addClass(references, owner);
                            if (descriptor != null) addDescriptorClasses(references, descriptor);
                        }

                        @Override
                        public void visitInnerClass(String name, String outerName, String innerName, int access) {
                            addClass(references, name);
                            addClass(references, outerName);
                        }

                        @Override
                        public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                                                                        String signature, Object value) {
                            addDescriptorClasses(references, descriptor);
                            addConstant(references, value);
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                         String[] exceptions) {
                            addDescriptorClasses(references, descriptor);
                            if (exceptions != null) for (String exception : exceptions) addClass(references, exception);
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitTypeInsn(int opcode, String type) {
                                    addTypeOperand(references, type);
                                }

                                @Override
                                public void visitFieldInsn(int opcode, String owner, String fieldName,
                                                           String fieldDescriptor) {
                                    addReference(references, new MemberRef(Kind.FIELD, owner, fieldName, fieldDescriptor),
                                            opcodeName(opcode));
                                }

                                @Override
                                public void visitMethodInsn(int opcode, String owner, String methodName,
                                                            String methodDescriptor, boolean isInterface) {
                                    addReference(references, new MemberRef(Kind.METHOD, owner, methodName,
                                            methodDescriptor), opcodeName(opcode), isInterface);
                                    addDescriptorClasses(references, methodDescriptor);
                                }

                                @Override
                                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                                   Object... bootstrapArguments) {
                                    addDescriptorClasses(references, descriptor);
                                    addHandle(references, bootstrap);
                                    for (Object value : bootstrapArguments) addConstant(references, value);
                                }

                                @Override
                                public void visitLdcInsn(Object value) {
                                    addConstant(references, value);
                                }

                                @Override
                                public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                                    addDescriptorClasses(references, descriptor);
                                }

                                @Override
                                public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                                               org.objectweb.asm.Label handler, String type) {
                                    addClass(references, type);
                                }

                                @Override
                                public void visitFrame(int type, int numLocal, Object[] local, int numStack,
                                                       Object[] stack) {
                                    addFrameClasses(references, local, numLocal);
                                    addFrameClasses(references, stack, numStack);
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG);
                }
            }
        }
        references.finish();
        return references;
    }

    private static void addFrameClasses(SourceReferences references, Object[] values, int count) {
        if (values == null) return;
        for (int index = 0; index < count; index++) {
            if (values[index] instanceof String name) addTypeOperand(references, name);
        }
    }

    private static void addConstant(SourceReferences references, Object value) {
        if (value instanceof Handle handle) {
            addHandle(references, handle);
        } else if (value instanceof Type type) {
            addType(references, type);
        } else if (value instanceof ConstantDynamic constant) {
            addDescriptorClasses(references, constant.getDescriptor());
            addHandle(references, constant.getBootstrapMethod());
            for (int index = 0; index < constant.getBootstrapMethodArgumentCount(); index++) {
                addConstant(references, constant.getBootstrapMethodArgument(index));
            }
        }
    }

    private static void addHandle(SourceReferences references, Handle handle) {
        int tag = handle.getTag();
        Kind kind = tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC ? Kind.FIELD : Kind.METHOD;
        addReference(references, new MemberRef(kind, handle.getOwner(), handle.getName(), handle.getDesc()),
                "HANDLE_" + tagName(tag), kind == Kind.METHOD ? handle.isInterface() : null);
        addDescriptorClasses(references, handle.getDesc());
    }

    private static String tagName(int tag) {
        return switch (tag) {
            case Opcodes.H_GETFIELD -> "GETFIELD";
            case Opcodes.H_GETSTATIC -> "GETSTATIC";
            case Opcodes.H_PUTFIELD -> "PUTFIELD";
            case Opcodes.H_PUTSTATIC -> "PUTSTATIC";
            case Opcodes.H_INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.H_INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.H_INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.H_NEWINVOKESPECIAL -> "NEWINVOKESPECIAL";
            case Opcodes.H_INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> throw new IllegalArgumentException("Unsupported method-handle tag " + tag);
        };
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.PUTFIELD -> "PUTFIELD";
            case Opcodes.PUTSTATIC -> "PUTSTATIC";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> throw new IllegalArgumentException("Unsupported bytecode member opcode " + opcode);
        };
    }

    private static void addReference(SourceReferences references, MemberRef reference, String use) {
        addReference(references, reference, use, null);
    }

    private static void addReference(SourceReferences references, MemberRef reference, String use,
                                     Boolean ownerIsInterface) {
        addClass(references, reference.owner);
        addDescriptorClasses(references, reference.descriptor);
        if (reference.owner.startsWith(MINECRAFT_PREFIX)) {
            references.mutableMembers.computeIfAbsent(reference, ignored -> new MutableReferenceUses())
                    .add(use, ownerIsInterface);
        }
    }

    private static void addClass(SourceReferences references, String name) {
        if (name != null && name.startsWith(MINECRAFT_PREFIX)) references.classes.add(name);
    }

    private static void addTypeOperand(SourceReferences references, String name) {
        if (name == null) return;
        if (name.startsWith("[")) addType(references, Type.getType(name));
        else addClass(references, name);
    }

    private static void addDescriptorClasses(SourceReferences references, String descriptor) {
        if (descriptor == null) return;
        Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
        addType(references, type);
    }

    private static void addType(SourceReferences references, Type type) {
        switch (type.getSort()) {
            case Type.OBJECT -> addClass(references, type.getInternalName());
            case Type.ARRAY -> addType(references, type.getElementType());
            case Type.METHOD -> {
                for (Type argument : type.getArgumentTypes()) addType(references, argument);
                addType(references, type.getReturnType());
            }
            default -> { }
        }
    }

    private static String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().withUpperCase().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private enum Kind { FIELD, METHOD }

    private record MemberRef(Kind kind, String owner, String name, String descriptor)
            implements Comparable<MemberRef> {
        @Override
        public int compareTo(MemberRef other) {
            return sortKey().compareTo(other.sortKey());
        }

        private String sortKey() {
            return kind + "\t" + owner + "\t" + name + "\t" + descriptor;
        }

        @Override
        public String toString() {
            return kind + " " + owner + "." + name + descriptor;
        }
    }

    private record MemberDeclaration(String owner, String name, String descriptor, int access) {}

    private record MemberMapping(MemberRef source, String mcpDeclarationOwner, int mcpAccess,
                                 String srgSymbolicOwner, String srgDeclarationOwner,
                                 String srgName, String srgDescriptor)
            implements Comparable<MemberMapping> {
        private MemberRef asReference() {
            return new MemberRef(source.kind, srgSymbolicOwner, srgName, srgDescriptor);
        }

        @Override
        public int compareTo(MemberMapping other) {
            return source.compareTo(other.source);
        }
    }

    private record RuntimeMemberMapping(MemberRef reference, MemberRef declaredReference,
                                       MemberDeclaration declaration, int symbolicOwnerAccess,
                                       boolean symbolicOwnerInterface, int declarationClassAccess) {}

    private record VerifierShimStructureRecord(String sourceClass, String mappedClass, int classAccess,
                                               String superName, List<String> interfaces, int fieldCount,
                                               int methodCount, int throwStubCount,
                                               int abstractNativeNoCodeCount, String sourceMappedStructureSha256,
                                               String generatedStructureSha256, String structureMatch) {}

    private record VerifierShimMemberRecord(String mappedClass, String kind, String name, int access,
                                            String descriptor, String signature, String exceptions,
                                            String sourceMappedStandardMetadataSha256,
                                            String generatedStandardMetadataSha256, String structureMatch) {}

    private record StructuralShimClassDecision(String sourceClass, String targetClass, String decision,
                                               String closureSources, String nestHostSource,
                                               String nestHostTarget, String sourceRuntimePackage,
                                               String targetRuntimePackage, String packageState) {
        private String toTsv() {
            return String.join("\t", sourceClass, targetClass, decision, "YES", closureSources,
                    nestHostSource, nestHostTarget, sourceRuntimePackage, targetRuntimePackage, packageState);
        }
    }

    private record StructuralShimClassEdge(String sourceClass, String referencedClass,
                                           String disposition, String mechanism) {
        private String toTsv() {
            return String.join("\t", sourceClass, referencedClass, disposition, mechanism);
        }
    }

    private record StructuralShimMemberDecision(String kind, String sourceOwner, String sourceName,
                                                String sourceDescriptor, int declarationAccess,
                                                String targetOwner, String targetName, String targetDescriptor,
                                                String decision, String uses) {
        private String toTsv() {
            return String.join("\t", kind, sourceOwner, sourceName, sourceDescriptor,
                    Integer.toString(declarationAccess), targetOwner, targetName, targetDescriptor,
                    decision, uses);
        }
    }

    private record VerifierShimStructureReport(List<VerifierShimStructureRecord> classes,
                                               List<VerifierShimMemberRecord> members) {}

    private record BodyPolicyCounts(int throwStubCount, int abstractNativeNoCodeCount) {}

    private record RuntimeClassMapping(String srgClass, String notchClass, int mcpAccess, int originalAccess) {}

    private record ReferenceUses(int count, Map<String, Integer> uses, boolean staticUse, boolean instanceUse,
                                 boolean ownerInterface, boolean ownerClass) {
        private static ReferenceUses merge(ReferenceUses first, ReferenceUses second) {
            TreeMap<String, Integer> combined = new TreeMap<>(first.uses);
            second.uses.forEach((name, count) -> combined.merge(name, count, Integer::sum));
            return new ReferenceUses(first.count + second.count, Map.copyOf(combined),
                    first.staticUse || second.staticUse, first.instanceUse || second.instanceUse,
                    first.ownerInterface || second.ownerInterface, first.ownerClass || second.ownerClass);
        }

        private String summary() {
            return uses + ";ownerInterface=" + ownerInterface + ";ownerClass=" + ownerClass;
        }
    }

    private static final class MutableReferenceUses {
        private int count;
        private final Map<String, Integer> uses = new TreeMap<>();
        private boolean staticUse;
        private boolean instanceUse;
        private boolean ownerInterface;
        private boolean ownerClass;

        private void add(String use, Boolean ownerIsInterface) {
            count++;
            uses.merge(use, 1, Integer::sum);
            if (ownerIsInterface != null) {
                ownerInterface |= ownerIsInterface;
                ownerClass |= !ownerIsInterface;
            }
            if (use.equals("GETSTATIC") || use.equals("PUTSTATIC") || use.equals("INVOKESTATIC")
                    || use.equals("HANDLE_GETSTATIC") || use.equals("HANDLE_PUTSTATIC")
                    || use.equals("HANDLE_INVOKESTATIC")) staticUse = true;
            else instanceUse = true;
        }

        private ReferenceUses snapshot() {
            return new ReferenceUses(count, Map.copyOf(new TreeMap<>(uses)), staticUse, instanceUse,
                    ownerInterface, ownerClass);
        }
    }

    private static final class SourceReferences {
        private final Map<MemberRef, MutableReferenceUses> mutableMembers = new LinkedHashMap<>();
        private final Set<String> classes = new LinkedHashSet<>();
        private Map<MemberRef, ReferenceUses> members = Map.of();

        private SourceReferences() {}

        private void finish() {
            TreeMap<MemberRef, ReferenceUses> ordered = new TreeMap<>();
            mutableMembers.forEach((reference, uses) -> ordered.put(reference, uses.snapshot()));
            members = java.util.Collections.unmodifiableMap(ordered);
        }
    }

    private static final class Mappings {
        private final Map<String, String> classes = new HashMap<>();
        private final Map<String, String> reverseClasses = new HashMap<>();
        private final Map<MemberRef, MethodNameMapping> methods = new HashMap<>();
        private final Map<String, FieldNameMapping> fields = new HashMap<>();

        private static Mappings read(Path path) throws IOException {
            Mappings result = new Mappings();
            List<MappingLine> lines = readLines(path);
            for (MappingLine line : lines) {
                String[] fields = line.fields;
                switch (fields[0]) {
                    case "CL:" -> {
                        requireLength(path, line, 3);
                        putUnique(result.classes, fields[1], fields[2], path, line.number);
                    }
                    case "FD:" -> {
                        requireLength(path, line, 3);
                        Name from = Name.parse(fields[1], path, line.number);
                        Name to = Name.parse(fields[2], path, line.number);
                        FieldNameMapping mapping = new FieldNameMapping(from.owner, from.name, to.owner, to.name,
                                line.number);
                        putUnique(result.fields, from.owner + "#" + from.name, mapping, path, line.number);
                    }
                    case "MD:" -> {
                        requireLength(path, line, 5);
                        Name from = Name.parse(fields[1], path, line.number);
                        Name to = Name.parse(fields[3], path, line.number);
                        MemberRef source = new MemberRef(Kind.METHOD, from.owner, from.name, fields[2]);
                        MemberRef target = new MemberRef(Kind.METHOD, to.owner, to.name, fields[4]);
                        putUnique(result.methods, source, new MethodNameMapping(source, target, line.number),
                                path, line.number);
                    }
                    case "PK:" -> requireLength(path, line, 3);
                    default -> throw new IOException("Unsupported MCP-SRG mapping record " + fields[0]
                            + " at " + path + ":" + line.number);
                }
            }
            result.buildReverseClasses(path);
            return result;
        }

        private void buildReverseClasses(Path path) throws IOException {
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                putUnique(reverseClasses, entry.getValue(), entry.getKey(), path, 0);
            }
        }

        private MemberMapping resolveRequired(MemberRef reference, MemberDeclaration declaration)
                throws IOException {
            if (!declaration.owner.startsWith(MINECRAFT_PREFIX) && !declaration.owner.startsWith("java/")) {
                throw new IOException("Probe member resolves outside the supported Minecraft/JDK API namespaces: "
                        + reference + " declared by " + declaration.owner);
            }
            String srgDeclaration = mapClassRequired(declaration.owner);
            String srgSymbolicOwner = mapClassRequired(reference.owner);
            String srgDescriptor = mapDescriptor(reference.descriptor);
            String srgName;
            String mappedDeclaration;
            if (reference.kind == Kind.METHOD) {
                if (declaration.owner.startsWith("java/")) {
                    if (!reference.name.equals(declaration.name)
                            || !srgDescriptor.equals(reference.descriptor)) {
                        throw new IOException("JDK-declared probe method is not an identity reference: "
                                + reference + " declared=" + declaration);
                    }
                    mappedDeclaration = declaration.owner;
                    srgName = declaration.name;
                    return new MemberMapping(reference, declaration.owner, declaration.access, srgSymbolicOwner,
                            srgDeclaration, srgName, srgDescriptor);
                }
                MethodNameMapping direct = methods.get(reference);
                MemberRef declaredRef = new MemberRef(Kind.METHOD, declaration.owner, reference.name,
                        reference.descriptor);
                MethodNameMapping declared = methods.get(declaredRef);
                if (direct != null && declared != null && !direct.target.equals(declared.target)) {
                    throw new IOException("Conflicting direct and inherited MCP-SRG method mappings for "
                            + reference + "; direct=" + direct + " declared=" + declared);
                }
                MethodNameMapping mapping = direct != null ? direct : declared;
                if (mapping == null) throw new IOException("Missing exact MCP-SRG method mapping for " + reference
                        + " declared by " + declaration.owner);
                if (!mapping.target.descriptor.equals(srgDescriptor)) {
                    throw new IOException("MCP-SRG method descriptor mismatch for " + reference + "; mapped="
                            + mapping.target.descriptor + " expected=" + srgDescriptor);
                }
                mappedDeclaration = mapping.target.owner;
                srgName = mapping.target.name;
            } else {
                if (declaration.owner.startsWith("java/")) {
                    if (!reference.name.equals(declaration.name)
                            || !srgDescriptor.equals(reference.descriptor)) {
                        throw new IOException("JDK-declared probe field is not an identity reference: "
                                + reference + " declared=" + declaration);
                    }
                    mappedDeclaration = declaration.owner;
                    srgName = declaration.name;
                    return new MemberMapping(reference, declaration.owner, declaration.access, srgSymbolicOwner,
                            srgDeclaration, srgName, srgDescriptor);
                }
                FieldNameMapping direct = fields.get(reference.owner + "#" + reference.name);
                FieldNameMapping declared = fields.get(declaration.owner + "#" + reference.name);
                if (direct != null && declared != null
                        && (!direct.targetOwner.equals(declared.targetOwner)
                        || !direct.targetName.equals(declared.targetName))) {
                    throw new IOException("Conflicting direct and inherited MCP-SRG field mappings for "
                            + reference + "; direct=" + direct + " declared=" + declared);
                }
                FieldNameMapping mapping = direct != null ? direct : declared;
                if (mapping == null) throw new IOException("Missing exact MCP-SRG field mapping for " + reference
                        + " declared by " + declaration.owner);
                mappedDeclaration = mapping.targetOwner;
                srgName = mapping.targetName;
            }
            if (!mappedDeclaration.equals(srgDeclaration)) {
                throw new IOException("MCP-SRG declaring owner mismatch for " + reference + "; MCP declaration="
                        + declaration.owner + " mapped declaration=" + mappedDeclaration
                        + " class mapping says=" + srgDeclaration);
            }
            return new MemberMapping(reference, declaration.owner, declaration.access, srgSymbolicOwner,
                    srgDeclaration, srgName, srgDescriptor);
        }

        private MemberMapping resolveOptional(MemberRef reference, ClassIndex api) throws IOException {
            MemberDeclaration declaration;
            try {
                declaration = api.declaration(reference);
            } catch (IOException missingDeclaration) {
                return null;
            }
            if (!declaration.owner.startsWith(MINECRAFT_PREFIX) && !declaration.owner.startsWith("java/")) {
                throw new IOException("Structural verifier shim member declaration resolves outside the supported "
                        + "Minecraft/JDK namespaces: " + reference + " declared by " + declaration.owner);
            }
            try {
                return resolveRequired(reference, declaration);
            } catch (IOException missingMapping) {
                if (missingMapping.getMessage() != null
                        && missingMapping.getMessage().startsWith("Missing exact MCP-SRG")) return null;
                throw missingMapping;
            }
        }

        private String mapClassRequired(String sourceClass) throws IOException {
            if (!sourceClass.startsWith(MINECRAFT_PREFIX)) return sourceClass;
            String target = classes.get(sourceClass);
            if (target == null) throw new IOException("Missing exact MCP-SRG class mapping for " + sourceClass);
            return target;
        }

        private String mapDescriptor(String descriptor) throws IOException {
            return mapDescriptor(descriptor, Set.of());
        }

        private String mapDescriptor(String descriptor, Set<String> identityMinecraftTypes) throws IOException {
            Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) {
                Type[] arguments = type.getArgumentTypes();
                Type[] mappedArguments = new Type[arguments.length];
                for (int index = 0; index < arguments.length; index++) {
                    mappedArguments[index] = mapType(arguments[index], identityMinecraftTypes);
                }
                return Type.getMethodDescriptor(mapType(type.getReturnType(), identityMinecraftTypes), mappedArguments);
            }
            return mapType(type, identityMinecraftTypes).getDescriptor();
        }

        private Type mapType(Type type) throws IOException {
            return mapType(type, Set.of());
        }

        private Type mapType(Type type, Set<String> identityMinecraftTypes) throws IOException {
            return switch (type.getSort()) {
                case Type.OBJECT -> Type.getObjectType(mapClassForRemap(type.getInternalName(), identityMinecraftTypes));
                case Type.ARRAY -> Type.getType("[".repeat(type.getDimensions())
                        + mapType(type.getElementType(), identityMinecraftTypes).getDescriptor());
                case Type.METHOD -> throw new IOException("Unexpected method type in nested descriptor mapping");
                default -> type;
            };
        }

        private String mapClassForRemap(String internalName, Set<String> identityMinecraftTypes) throws IOException {
            try {
                return mapClassRequired(internalName);
            } catch (IOException cause) {
                if (identityMinecraftTypes.contains(internalName)) return internalName;
                throw cause;
            }
        }

        private Remapper asmRemapper(ClassIndex api, boolean strictMembers,
                                     Set<String> identityMinecraftTypes) {
            return new Remapper() {
                @Override
                public String map(String internalName) {
                    try {
                        return mapClassForRemap(internalName, identityMinecraftTypes);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot map Minecraft class " + internalName, cause);
                    }
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    if (!owner.startsWith(MINECRAFT_PREFIX)) return name;
                    return memberName(new MemberRef(Kind.FIELD, owner, name, descriptor), strictMembers, api);
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    if (!owner.startsWith(MINECRAFT_PREFIX)) return name;
                    return memberName(new MemberRef(Kind.METHOD, owner, name, descriptor), strictMembers, api);
                }

                private String memberName(MemberRef reference, boolean strict, ClassIndex api) {
                    try {
                        MemberMapping mapping = strict ? resolveRequired(reference, api.declaration(reference))
                                : resolveOptional(reference, api);
                        if (mapping != null) return mapping.srgName;
                        if (!strict) return reference.name;
                        throw new IOException("No unique MCP-SRG mapping for probe bytecode member " + reference);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot remap Minecraft member " + reference, cause);
                    }
                }

                @Override
                public String mapDesc(String descriptor) {
                    try {
                        return mapDescriptor(descriptor, identityMinecraftTypes);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot remap Minecraft descriptor " + descriptor, cause);
                    }
                }
            };
        }
    }

    private record MethodNameMapping(MemberRef source, MemberRef target, int line) {}
    private record FieldNameMapping(String sourceOwner, String sourceName, String targetOwner,
                                    String targetName, int line) {}

    private static final class NotchMappings {
        private final Map<String, String> notchToSrgClasses = new HashMap<>();
        private final Map<String, String> srgToNotchClasses = new HashMap<>();
        private final Map<MemberRef, MemberRef> notchToSrgMethods = new HashMap<>();
        private final Map<MemberRef, MemberRef> srgToNotchMethods = new HashMap<>();
        private final Map<String, MemberRef> notchToSrgFields = new HashMap<>();
        private final Map<String, MemberRef> srgToNotchFields = new HashMap<>();

        private static NotchMappings read(Path path) throws IOException {
            NotchMappings result = new NotchMappings();
            List<MappingLine> lines = readLines(path);
            for (MappingLine line : lines) {
                String[] fields = line.fields;
                switch (fields[0]) {
                    case "CL:" -> {
                        requireLength(path, line, 3);
                        putUnique(result.notchToSrgClasses, fields[1], fields[2], path, line.number);
                        putUnique(result.srgToNotchClasses, fields[2], fields[1], path, line.number);
                    }
                    case "FD:" -> {
                        requireLength(path, line, 3);
                        Name notch = Name.parse(fields[1], path, line.number);
                        Name srg = Name.parse(fields[2], path, line.number);
                        MemberRef notchRef = new MemberRef(Kind.FIELD, notch.owner, notch.name, "");
                        MemberRef srgRef = new MemberRef(Kind.FIELD, srg.owner, srg.name, "");
                        putUnique(result.notchToSrgFields, notch.owner + "#" + notch.name, srgRef,
                                path, line.number);
                        putUnique(result.srgToNotchFields, srg.owner + "#" + srg.name, notchRef,
                                path, line.number);
                    }
                    case "MD:" -> {
                        requireLength(path, line, 5);
                        Name notch = Name.parse(fields[1], path, line.number);
                        Name srg = Name.parse(fields[3], path, line.number);
                        MemberRef notchRef = new MemberRef(Kind.METHOD, notch.owner, notch.name, fields[2]);
                        MemberRef srgRef = new MemberRef(Kind.METHOD, srg.owner, srg.name, fields[4]);
                        putUnique(result.notchToSrgMethods, notchRef, srgRef, path, line.number);
                        putUnique(result.srgToNotchMethods, srgRef, notchRef, path, line.number);
                    }
                    case "PK:" -> requireLength(path, line, 3);
                    default -> throw new IOException("Unsupported notch-SRG mapping record " + fields[0]
                            + " at " + path + ":" + line.number);
                }
            }
            result.validateMappings(path);
            return result;
        }

        private void validateMappings(Path path) throws IOException {
            for (Map.Entry<MemberRef, MemberRef> entry : notchToSrgMethods.entrySet()) {
                MemberRef notch = entry.getKey();
                MemberRef srg = entry.getValue();
                if (!toSrgClass(notch.owner).equals(srg.owner)
                        || !toSrgDescriptor(notch.descriptor).equals(srg.descriptor)) {
                    throw new IOException("Inconsistent notch-SRG method mapping: " + notch + " -> " + srg
                            + " in " + path);
                }
            }
            for (Map.Entry<String, MemberRef> entry : notchToSrgFields.entrySet()) {
                MemberRef notch = entry.getValue();
                String notchKeyOwner = entry.getKey().substring(0, entry.getKey().lastIndexOf('#'));
                if (!toSrgClass(notchKeyOwner).equals(notch.owner)) {
                    throw new IOException("Inconsistent notch-SRG field mapping owner " + entry + " in " + path);
                }
            }
        }

        private RuntimeMemberMapping resolveAndVerify(MemberMapping mapping, ReferenceUses uses,
                                                      boolean mcpOwnerInterface,
                                                      ClassIndex originalClient) throws IOException {
            String notchDeclarationOwner = toNotchClass(mapping.srgDeclarationOwner);
            String notchDescriptor = toNotchDescriptor(mapping.srgDescriptor);
            MemberRef declaredTarget;
            if (mapping.source.kind == Kind.METHOD) {
                MemberRef srgDeclaration = new MemberRef(Kind.METHOD, mapping.srgDeclarationOwner,
                        mapping.srgName, mapping.srgDescriptor);
                if (mapping.srgDeclarationOwner.startsWith("java/")) {
                    declaredTarget = new MemberRef(Kind.METHOD, mapping.srgDeclarationOwner,
                            mapping.srgName, mapping.srgDescriptor);
                } else {
                    declaredTarget = srgToNotchMethods.get(srgDeclaration);
                    if (declaredTarget == null) {
                        throw new IOException("Missing unique notch-SRG method mapping for " + srgDeclaration);
                    }
                }
            } else {
                String srgKey = mapping.srgDeclarationOwner + "#" + mapping.srgName;
                MemberRef field = srgToNotchFields.get(srgKey);
                if (field == null) throw new IOException("Missing unique notch-SRG field mapping for " + srgKey);
                declaredTarget = new MemberRef(Kind.FIELD, field.owner, field.name, notchDescriptor);
            }
            if (!declaredTarget.owner.equals(notchDeclarationOwner)
                    || !declaredTarget.descriptor.equals(notchDescriptor)) {
                throw new IOException("notch-SRG target disagrees with class/descriptor mappings for "
                        + mapping.source + "; mapped=" + declaredTarget + " expectedOwner=" + notchDeclarationOwner
                        + " expectedDescriptor=" + notchDescriptor);
            }
            String notchSymbolicOwner = toNotchClass(mapping.srgSymbolicOwner);
            int symbolicOwnerAccess = originalClient.classAccess(notchSymbolicOwner);
            boolean symbolicOwnerInterface = (symbolicOwnerAccess & Opcodes.ACC_INTERFACE) != 0;
            if (symbolicOwnerInterface != mcpOwnerInterface) {
                throw new IOException("Mapped MCP API and pinned original symbolic owner disagree on interface "
                        + "shape for " + mapping.source + "; MCP owner interface=" + mcpOwnerInterface
                        + " original owner=" + notchSymbolicOwner + " access=" + symbolicOwnerAccess);
            }
            validateOwnerInterfaceUse(mapping.source, uses, symbolicOwnerInterface, "pinned original client");
            MemberRef target = new MemberRef(mapping.source.kind, notchSymbolicOwner,
                    declaredTarget.name, declaredTarget.descriptor);
            MemberDeclaration actual = originalClient.declaration(target);
            if (!actual.owner.equals(declaredTarget.owner)) {
                throw new IOException("Pinned original client resolves the remapped symbolic reference to a "
                        + "different declaring owner for " + mapping.source + "; expected=" + declaredTarget.owner
                        + " actual=" + actual.owner + " symbolic=" + target);
            }
            validatePublicAccess(mapping.source, actual, "pinned original client");
            boolean mcpStatic = (mapping.mcpAccess & Opcodes.ACC_STATIC) != 0;
            boolean runtimeStatic = (actual.access & Opcodes.ACC_STATIC) != 0;
            if (mcpStatic != runtimeStatic) {
                throw new IOException("Staticness differs between mapped MCP API and original client for "
                        + mapping.source + "; MCP access=" + mapping.mcpAccess + " runtime access=" + actual.access);
            }
            if (uses.staticUse && !runtimeStatic || uses.instanceUse && runtimeStatic) {
                throw new IOException("Probe opcode use is incompatible with original client declaration for "
                        + mapping.source + "; runtime access=" + actual.access + " uses=" + uses.summary());
            }
            int declarationClassAccess = originalClient.classAccess(actual.owner);
            if ((declarationClassAccess & Opcodes.ACC_PUBLIC) == 0) {
                throw new IOException("Pinned original declaring class is not public for " + mapping.source
                        + "; declarationOwner=" + actual.owner + " access=" + declarationClassAccess);
            }
            return new RuntimeMemberMapping(target, declaredTarget, actual, symbolicOwnerAccess,
                    symbolicOwnerInterface, declarationClassAccess);
        }

        private String toNotchClass(String srgClass) throws IOException {
            if (!srgClass.startsWith(MINECRAFT_PREFIX)) return srgClass;
            String target = srgToNotchClasses.get(srgClass);
            if (target == null) throw new IOException("Missing unique notch-SRG class mapping for " + srgClass);
            return target;
        }

        private String toSrgClass(String notchClass) throws IOException {
            String target = notchToSrgClasses.get(notchClass);
            if (target != null) return target;
            if (notchClass.startsWith(MINECRAFT_PREFIX)) {
                throw new IOException("Missing unique notch-SRG class mapping for " + notchClass);
            }
            return notchClass;
        }

        private String toSrgDescriptor(String descriptor) throws IOException {
            return mapDescriptor(descriptor, this::toSrgClass);
        }

        private String toNotchDescriptor(String descriptor) throws IOException {
            return mapDescriptor(descriptor, this::toNotchClass);
        }
    }

    @FunctionalInterface
    private interface ClassNameMapper {
        String map(String internalName) throws IOException;
    }

    private static String mapDescriptor(String descriptor, ClassNameMapper classMapper) throws IOException {
        Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
        if (type.getSort() == Type.METHOD) {
            Type[] arguments = type.getArgumentTypes();
            Type[] mappedArguments = new Type[arguments.length];
            for (int index = 0; index < arguments.length; index++) {
                mappedArguments[index] = mapType(arguments[index], classMapper);
            }
            return Type.getMethodDescriptor(mapType(type.getReturnType(), classMapper), mappedArguments);
        }
        return mapType(type, classMapper).getDescriptor();
    }

    private static Type mapType(Type type, ClassNameMapper classMapper) throws IOException {
        return switch (type.getSort()) {
            case Type.OBJECT -> Type.getObjectType(classMapper.map(type.getInternalName()));
            case Type.ARRAY -> Type.getType("[".repeat(type.getDimensions())
                    + mapType(type.getElementType(), classMapper).getDescriptor());
            case Type.METHOD -> throw new IOException("Unexpected method type in nested descriptor mapping");
            default -> type;
        };
    }

    private static final class ClassIndex implements AutoCloseable {
        private final ZipFile archive;
        private final String description;
        private final Map<String, ClassNode> classes = new HashMap<>();
        private final Set<String> missing = new HashSet<>();

        private ClassIndex(Path archivePath, String description) throws IOException {
            this.archive = new ZipFile(archivePath.toFile());
            this.description = description;
        }

        private Map<String, String> classDeclarations() throws IOException {
            Map<String, String> declarations = new TreeMap<>();
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                String entryName = entry.getName();
                String entryInternalName = entryName.substring(0, entryName.length() - ".class".length());
                if (entryInternalName.isBlank() || entryInternalName.startsWith("/")
                        || entryInternalName.contains("\\") || entryInternalName.contains("//")
                        || entryInternalName.startsWith("../") || entryInternalName.contains("/../")
                        || entryInternalName.endsWith("/..")) {
                    throw new IOException(description + " has an invalid class entry path: " + entryName);
                }
                String declaredName;
                try (var input = archive.getInputStream(entry)) {
                    declaredName = new ClassReader(input).getClassName();
                } catch (IOException | RuntimeException cause) {
                    throw new IOException(description + " has an unreadable class declaration entry " + entryName,
                            cause);
                }
                if (!entryInternalName.equals(declaredName)) {
                    throw new IOException(description + " class entry/internal-name mismatch: entry=" + entryName
                            + " declared=" + declaredName);
                }
                String previous = declarations.putIfAbsent(declaredName, entryName);
                if (previous != null) {
                    throw new IOException(description + " contains duplicate class declarations for " + declaredName
                            + ": " + previous + " and " + entryName);
                }
            }
            return Collections.unmodifiableMap(declarations);
        }

        private ClassNode structuralClassNode(String name) throws IOException {
            ZipEntry entry = archive.getEntry(name + ".class");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            try (var input = entry != null
                    ? archive.getInputStream(entry)
                    : name.startsWith("java/")
                        ? ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")
                        : null) {
                if (input == null) throw new IOException(description + " does not declare class " + name);
                new ClassReader(input).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException | RuntimeException cause) {
                throw new IOException(description + " failed to read structural metadata for " + name, cause);
            }
            if (!node.name.equals(name)) {
                throw new IOException(description + " resolved structural class " + name
                        + " to unexpected declaration " + node.name);
            }
            return node;
        }

        private void requireClass(String name) throws IOException {
            if (classNode(name) == null) throw new IOException(description + " does not contain class " + name);
        }

        private int classAccess(String name) throws IOException {
            ClassNode node = classNode(name);
            if (node == null) throw new IOException(description + " does not contain class " + name);
            return node.access;
        }

        private MemberDeclaration declaration(MemberRef reference) throws IOException {
            MemberDeclaration declaration = reference.kind == Kind.FIELD
                    ? resolveField(reference.owner, reference, new HashSet<>())
                    : resolveMethod(reference);
            if (declaration == null) {
                throw new IOException(description + " does not declare or inherit exact member " + reference);
            }
            return declaration;
        }

        private MemberDeclaration declaredMember(MemberRef reference) throws IOException {
            ClassNode node = classNode(reference.owner);
            if (node == null) throw new IOException(description + " does not contain target owner " + reference.owner);
            MemberDeclaration declaration = declared(node, reference);
            if (declaration == null) {
                throw new IOException(description + " lacks exact declared member " + reference);
            }
            return declaration;
        }

        private MemberDeclaration directStructuralDeclaration(MemberRef reference) throws IOException {
            ClassNode node = structuralClassNode(reference.owner);
            MemberDeclaration declaration = declared(node, reference);
            if (declaration == null) return null;
            return declaration;
        }

        private MemberDeclaration findDeclaredMember(MemberRef reference) throws IOException {
            ClassNode node = classNode(reference.owner);
            if (node == null) {
                throw new IOException(description + " does not contain direct-member owner " + reference.owner);
            }
            return declared(node, reference);
        }

        private MemberRef declaredMethodByName(String owner, String name) throws IOException {
            ClassNode node = classNode(owner);
            if (node == null) throw new IOException(description + " does not contain method owner " + owner);
            MemberDeclaration match = null;
            for (var method : node.methods) {
                if (!method.name.equals(name)) continue;
                if (match != null) {
                    throw new IOException(description + " has overloaded direct methods for bounded name lookup "
                            + owner + "." + name + ": " + match.descriptor + " and " + method.desc);
                }
                match = new MemberDeclaration(node.name, method.name, method.desc, method.access);
            }
            if (match == null) throw new IOException(description + " lacks direct method " + owner + "." + name);
            return new MemberRef(Kind.METHOD, owner, match.name, match.descriptor);
        }

        private MemberDeclaration resolveField(String owner, MemberRef reference, Set<String> visited)
                throws IOException {
            if (owner == null || !visited.add(owner)) return null;
            ClassNode node = classNode(owner);
            if (node == null) return null;
            MemberDeclaration direct = declared(node, reference);
            if (direct != null) return direct;
            List<MemberDeclaration> interfaceMembers = new ArrayList<>();
            for (String interfaceName : node.interfaces) {
                MemberDeclaration inherited = resolveField(interfaceName, reference, visited);
                if (inherited != null) interfaceMembers.add(inherited);
            }
            MemberDeclaration interfaceMember = uniqueDeclaration(reference, interfaceMembers,
                    "ambiguous inherited interface field in " + owner);
            if (interfaceMember != null) return interfaceMember;
            return resolveField(node.superName, reference, visited);
        }

        private MemberDeclaration resolveMethod(MemberRef reference) throws IOException {
            List<String> interfaceRoots = new ArrayList<>();
            Set<String> visitedClasses = new HashSet<>();
            String owner = reference.owner;
            boolean interfaceOwner = false;
            while (owner != null && visitedClasses.add(owner)) {
                ClassNode node = classNode(owner);
                if (node == null) break;
                if (owner.equals(reference.owner)) {
                    interfaceOwner = (node.access & Opcodes.ACC_INTERFACE) != 0;
                }
                MemberDeclaration direct = declared(node, reference);
                if (direct != null) return direct;
                interfaceRoots.addAll(node.interfaces);
                if ((node.access & Opcodes.ACC_INTERFACE) != 0) break;
                owner = node.superName;
            }
            Map<String, MemberDeclaration> declarations = new LinkedHashMap<>();
            Set<String> visitedInterfaces = new HashSet<>();
            for (String interfaceRoot : interfaceRoots) {
                collectInterfaceMethods(interfaceRoot, reference, declarations, visitedInterfaces);
            }
            List<MemberDeclaration> mostSpecific = new ArrayList<>();
            for (MemberDeclaration candidate : declarations.values()) {
                boolean shadowed = false;
                for (MemberDeclaration other : declarations.values()) {
                    if (!other.owner.equals(candidate.owner)
                            && isInterfaceSubtype(other.owner, candidate.owner, new HashSet<>())) {
                        shadowed = true;
                        break;
                    }
                }
                if (!shadowed) mostSpecific.add(candidate);
            }
            MemberDeclaration inheritedInterface = uniqueDeclaration(reference, mostSpecific,
                    "ambiguous inherited interface method");
            if (inheritedInterface != null) return inheritedInterface;
            if (interfaceOwner && !reference.name.startsWith("<")) {
                ClassNode objectClass = classNode("java/lang/Object");
                if (objectClass != null) {
                    MemberDeclaration objectMethod = declared(objectClass, reference);
                    if (objectMethod != null && (objectMethod.access & Opcodes.ACC_PUBLIC) != 0
                            && (objectMethod.access & Opcodes.ACC_STATIC) == 0) return objectMethod;
                }
            }
            return null;
        }

        private void collectInterfaceMethods(String owner, MemberRef reference,
                                             Map<String, MemberDeclaration> declarations,
                                             Set<String> visited) throws IOException {
            if (owner == null || !visited.add(owner)) return;
            ClassNode node = classNode(owner);
            if (node == null) return;
            MemberDeclaration direct = declared(node, reference);
            if (direct != null && (direct.access & Opcodes.ACC_STATIC) == 0) {
                declarations.putIfAbsent(owner, direct);
            }
            for (String parent : node.interfaces) {
                collectInterfaceMethods(parent, reference, declarations, visited);
            }
        }

        private boolean isInterfaceSubtype(String child, String parent, Set<String> visited) throws IOException {
            if (child.equals(parent)) return true;
            if (!visited.add(child)) return false;
            ClassNode node = classNode(child);
            if (node == null) return false;
            for (String interfaceName : node.interfaces) {
                if (interfaceName.equals(parent) || isInterfaceSubtype(interfaceName, parent, visited)) return true;
            }
            return false;
        }

        private MemberDeclaration uniqueDeclaration(MemberRef reference, List<MemberDeclaration> declarations,
                                                    String reason) throws IOException {
            if (declarations.isEmpty()) return null;
            Map<String, MemberDeclaration> unique = new LinkedHashMap<>();
            for (MemberDeclaration declaration : declarations) unique.putIfAbsent(declaration.owner, declaration);
            if (unique.size() != 1) {
                throw new IOException(description + " has " + reason + " for " + reference + ": " + unique.keySet());
            }
            return unique.values().iterator().next();
        }

        private MemberDeclaration declared(ClassNode node, MemberRef reference) {
            if (reference.kind == Kind.METHOD) {
                for (var method : node.methods) {
                    if (method.name.equals(reference.name) && method.desc.equals(reference.descriptor)) {
                        return new MemberDeclaration(node.name, method.name, method.desc, method.access);
                    }
                }
            } else {
                for (var field : node.fields) {
                    if (field.name.equals(reference.name) && field.desc.equals(reference.descriptor)) {
                        return new MemberDeclaration(node.name, field.name, field.desc, field.access);
                    }
                }
            }
            return null;
        }

        private ClassNode classNode(String name) throws IOException {
            if (name == null || missing.contains(name)) return null;
            ClassNode known = classes.get(name);
            if (known != null) return known;
            ZipEntry entry = archive.getEntry(name + ".class");
            ClassNode node = new ClassNode(Opcodes.ASM9);
            if (entry != null) {
                try (var input = archive.getInputStream(entry)) {
                    new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            } else if (name.startsWith("java/")) {
                try (var input = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                    if (input == null) {
                        missing.add(name);
                        return null;
                    }
                    new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            } else {
                missing.add(name);
                return null;
            }
            if (!node.name.equals(name)) {
                throw new IOException(description + " resolved " + name + " to unexpected class " + node.name);
            }
            classes.put(name, node);
            return node;
        }

        @Override
        public void close() throws IOException {
            archive.close();
        }
    }

    private static final class StructuralShimClassMapping {
        private static final String REFERENCE_MECHANISM = "ASM_CLASS_REMAPPER_SKIP_CODE_STANDARD_METADATA";

        private final ClassIndex actualApi;
        private final Mappings strictMappings;
        private final Map<String, String> actualDeclarations;
        private final Map<String, String> targetNames;
        private final Set<String> outputSourceClasses;
        private final List<StructuralShimClassDecision> classDecisionRows;
        private final List<StructuralShimClassEdge> edgeRows;

        private StructuralShimClassMapping(ClassIndex actualApi, Mappings strictMappings,
                                           Map<String, String> actualDeclarations,
                                           Map<String, String> targetNames,
                                           Set<String> outputSourceClasses,
                                           List<StructuralShimClassDecision> classDecisionRows,
                                           List<StructuralShimClassEdge> edgeRows) {
            this.actualApi = actualApi;
            this.strictMappings = strictMappings;
            this.actualDeclarations = actualDeclarations;
            this.targetNames = targetNames;
            this.outputSourceClasses = outputSourceClasses;
            this.classDecisionRows = classDecisionRows;
            this.edgeRows = edgeRows;
        }

        private static StructuralShimClassMapping build(ClassIndex actualApi, Mappings strict) throws IOException {
            Map<String, String> actualDeclarations = actualApi.classDeclarations();
            for (String identityClass : VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES) {
                if (actualDeclarations.containsKey(identityClass)) {
                    throw new IOException("Fixed LaunchWrapper identity class is also declared by the mapped MCP API: "
                            + identityClass + " entry=" + actualDeclarations.get(identityClass));
                }
                String exactTarget = strict.classes.get(identityClass);
                if (exactTarget != null && !exactTarget.equals(identityClass)) {
                    throw new IOException("Fixed LaunchWrapper identity class has a conflicting MCP-SRG mapping: "
                            + identityClass + "->" + exactTarget);
                }
            }
            Map<String, String> targetNames = new TreeMap<>();
            Set<String> outputSourceClasses = new TreeSet<>();
            Map<String, Set<String>> closureSources = new TreeMap<>();
            Map<String, StructuralShimClassEdge> edges = new TreeMap<>();
            Deque<String> pending = new ArrayDeque<>();

            for (String sourceClass : new TreeSet<>(strict.classes.keySet())) {
                if (!sourceClass.startsWith(MINECRAFT_PREFIX) || !actualDeclarations.containsKey(sourceClass)) continue;
                String targetClass = strict.classes.get(sourceClass);
                targetNames.put(sourceClass, targetClass);
                outputSourceClasses.add(sourceClass);
                closureSources.computeIfAbsent(sourceClass, ignored -> new TreeSet<>())
                        .add("MCP_SRG_EXACT_CL_MAPPING");
                pending.addLast(sourceClass);
            }
            if (pending.isEmpty()) {
                throw new IOException("Pinned mapped MCP API has no actual net.minecraft declarations with exact "
                        + "MCP-SRG class mappings; refusing an empty structural shim plan");
            }

            Set<String> scanned = new HashSet<>();
            while (!pending.isEmpty()) {
                String sourceClass = pending.removeFirst();
                if (!scanned.add(sourceClass)) continue;
                ClassNode sourceNode;
                try {
                    sourceNode = actualApi.structuralClassNode(sourceClass);
                    StructuralReferenceRecordingRemapper recorder = new StructuralReferenceRecordingRemapper();
                    sourceNode.accept(new ClassRemapper(new ClassNode(Opcodes.ASM9), recorder));
                    for (String referencedClass : recorder.references()) {
                        if (!referencedClass.startsWith(MINECRAFT_PREFIX)) continue;
                        String disposition;
                        if (VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(referencedClass)) {
                            disposition = "FIXED_LAUNCHWRAPPER_IDENTITY";
                        } else if (!actualDeclarations.containsKey(referencedClass)) {
                            throw new IOException("Structural verifier-shim metadata references a Minecraft class "
                                    + "without an exact MCP-SRG mapping or actual MCP API declaration: source="
                                    + sourceClass + " referenced=" + referencedClass);
                        } else if (strict.classes.containsKey(referencedClass)) {
                            disposition = "EXACT_MCP_SRG_CL_MAPPING";
                            if (!targetNames.containsKey(referencedClass)) {
                                throw new IOException("Actual mapped MCP declaration was not seeded into the "
                                        + "structural shim plan: source=" + sourceClass + " referenced="
                                        + referencedClass);
                            }
                        } else {
                            disposition = "PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING";
                            if (!targetNames.containsKey(referencedClass)) {
                                targetNames.put(referencedClass, referencedClass);
                                outputSourceClasses.add(referencedClass);
                                pending.addLast(referencedClass);
                            }
                        }
                        closureSources.computeIfAbsent(referencedClass, ignored -> new TreeSet<>()).add(sourceClass);
                        String edgeKey = sourceClass + "\u0000" + referencedClass;
                        edges.putIfAbsent(edgeKey, new StructuralShimClassEdge(sourceClass, referencedClass,
                                disposition, REFERENCE_MECHANISM));
                    }
                } catch (IOException | RuntimeException cause) {
                    throw new IOException("Failed to derive structural verifier-shim declaration closure from "
                            + sourceClass, cause);
                }
            }

            validateTargetUniqueness(strict, targetNames);
            StructuralShimClassMapping plan = new StructuralShimClassMapping(actualApi, strict,
                    Collections.unmodifiableMap(new TreeMap<>(actualDeclarations)),
                    Collections.unmodifiableMap(new TreeMap<>(targetNames)),
                    Collections.unmodifiableSet(new TreeSet<>(outputSourceClasses)),
                    List.of(), List.copyOf(edges.values()));
            List<StructuralShimClassDecision> decisions = plan.validateRelationshipsAndBuildRows(closureSources);
            return new StructuralShimClassMapping(actualApi, strict,
                    Collections.unmodifiableMap(new TreeMap<>(actualDeclarations)),
                    Collections.unmodifiableMap(new TreeMap<>(targetNames)),
                    Collections.unmodifiableSet(new TreeSet<>(outputSourceClasses)),
                    decisions, List.copyOf(edges.values()));
        }

        private static void validateTargetUniqueness(Mappings strict, Map<String, String> targetNames)
                throws IOException {
            Map<String, String> targetOwners = new TreeMap<>(strict.reverseClasses);
            for (String identityClass : VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES) {
                String previous = targetOwners.putIfAbsent(identityClass, identityClass);
                if (previous != null && !previous.equals(identityClass)) {
                    throw new IOException("Fixed LaunchWrapper verifier-shim identity target collides with an "
                            + "MCP-SRG class target: identity=" + identityClass + " alreadyOwnedBy=" + previous);
                }
            }
            for (Map.Entry<String, String> entry : targetNames.entrySet()) {
                String sourceClass = entry.getKey();
                String targetClass = entry.getValue();
                if (strict.classes.containsKey(sourceClass)) {
                    String mappedTarget = strict.classes.get(sourceClass);
                    if (!targetClass.equals(mappedTarget)) {
                        throw new IOException("Structural shim class plan altered an exact MCP-SRG class mapping: "
                                + sourceClass + " mapped=" + mappedTarget + " plan=" + targetClass);
                    }
                    continue;
                }
                String previous = targetOwners.putIfAbsent(targetClass, sourceClass);
                if (previous != null && !previous.equals(sourceClass)) {
                    throw new IOException("Structural shim preserved declaration target collides with an existing "
                            + "MCP-SRG class target: source=" + sourceClass + " target=" + targetClass
                            + " alreadyOwnedBy=" + previous);
                }
            }
        }

        private List<StructuralShimClassDecision> validateRelationshipsAndBuildRows(
                Map<String, Set<String>> closureSources) throws IOException {
            List<StructuralShimClassDecision> decisions = new ArrayList<>();
            for (String sourceClass : outputSourceClasses) {
                ClassNode node = actualApi.structuralClassNode(sourceClass);
                String targetClass = mapClass(sourceClass);
                String sourcePackage = runtimePackage(sourceClass);
                String targetPackage = runtimePackage(targetClass);
                String nestHostSource = node.nestHostClass == null ? "" : node.nestHostClass;
                String nestHostTarget = nestHostSource.isEmpty() ? "" : mapClass(nestHostSource);
                String packageState = validateNestMetadata(sourceClass, node, targetClass, targetPackage);
                validateInnerMetadata(sourceClass, node);
                validateEnclosingOwner(sourceClass, node);
                String decision = strictMappings.classes.containsKey(sourceClass)
                        ? "EXACT_MCP_SRG_CL_MAPPING"
                        : "PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING";
                String sources = String.join(";", new TreeSet<>(closureSources.getOrDefault(sourceClass, Set.of())));
                decisions.add(new StructuralShimClassDecision(sourceClass, targetClass, decision, sources,
                        nestHostSource, nestHostTarget, sourcePackage, targetPackage, packageState));
            }
            return List.copyOf(decisions);
        }

        private String validateNestMetadata(String sourceClass, ClassNode node, String targetClass,
                                            String targetPackage) throws IOException {
            boolean hasHost = node.nestHostClass != null;
            boolean hasMembers = node.nestMembers != null && !node.nestMembers.isEmpty();
            if (hasHost) {
                requireActualOutputClass(node.nestHostClass, "NestHost of " + sourceClass);
                ClassNode host = actualApi.structuralClassNode(node.nestHostClass);
                if (host.nestMembers == null || !host.nestMembers.contains(sourceClass)) {
                    throw new IOException("Actual MCP nest member/host relationship is inconsistent: member="
                            + sourceClass + " host=" + node.nestHostClass + " hostNestMembers=" + host.nestMembers);
                }
                String mappedHost = mapClass(node.nestHostClass);
                if (!runtimePackage(targetClass).equals(runtimePackage(mappedHost))) {
                    throw new IOException("Structural shim remapping splits an actual nest across runtime packages: "
                            + "member=" + sourceClass + "->" + targetClass + " host=" + node.nestHostClass
                            + "->" + mappedHost);
                }
                rejectPreservedClassWhenParentChanged(sourceClass, node.nestHostClass);
            }
            if (hasMembers) {
                for (String memberClass : node.nestMembers) {
                    requireActualOutputClass(memberClass, "NestMembers of " + sourceClass);
                    ClassNode member = actualApi.structuralClassNode(memberClass);
                    if (!sourceClass.equals(member.nestHostClass)) {
                        throw new IOException("Actual MCP nest host/member relationship is inconsistent: host="
                                + sourceClass + " member=" + memberClass + " memberNestHost="
                                + member.nestHostClass);
                    }
                    String mappedMember = mapClass(memberClass);
                    if (!runtimePackage(targetClass).equals(runtimePackage(mappedMember))) {
                        throw new IOException("Structural shim remapping splits an actual nest across runtime "
                                + "packages: host=" + sourceClass + "->" + targetClass + " member="
                                + memberClass + "->" + mappedMember);
                    }
                    rejectPreservedClassWhenParentChanged(memberClass, sourceClass);
                }
            }
            if (hasHost) return "NEST_HOST_PACKAGE_MATCH";
            if (hasMembers) return "NEST_MEMBERS_PACKAGE_MATCH";
            return "NO_NEST_METADATA";
        }

        private void validateInnerMetadata(String sourceClass, ClassNode node) throws IOException {
            if (node.innerClasses == null) return;
            for (var inner : node.innerClasses) {
                if (inner.name == null || !inner.name.startsWith(MINECRAFT_PREFIX)) continue;
                requireActualOutputClass(inner.name, "InnerClasses of " + sourceClass);
                if (inner.outerName == null || !inner.outerName.startsWith(MINECRAFT_PREFIX)) continue;
                requireActualOutputClass(inner.outerName, "InnerClasses outer of " + sourceClass);
                String mappedInner = mapClass(inner.name);
                String mappedOuter = mapClass(inner.outerName);
                if (!runtimePackage(mappedInner).equals(runtimePackage(mappedOuter))) {
                    throw new IOException("Structural shim remapping splits an actual InnerClasses relation "
                            + "across runtime packages: inner=" + inner.name + "->" + mappedInner + " outer="
                            + inner.outerName + "->" + mappedOuter + " referencedFrom=" + sourceClass);
                }
                ClassNode innerNode = actualApi.structuralClassNode(inner.name);
                ClassNode outerNode = actualApi.structuralClassNode(inner.outerName);
                boolean innerDeclaresRelation = innerNode.innerClasses != null && innerNode.innerClasses.stream()
                        .anyMatch(candidate -> candidate.name.equals(inner.name)
                                && inner.outerName.equals(candidate.outerName));
                boolean outerDeclaresRelation = outerNode.innerClasses != null && outerNode.innerClasses.stream()
                        .anyMatch(candidate -> candidate.name.equals(inner.name)
                                && inner.outerName.equals(candidate.outerName));
                if (!innerDeclaresRelation && !outerDeclaresRelation) {
                    throw new IOException("InnerClasses reference lacks an actual reciprocal class declaration: "
                            + "inner=" + inner.name + " outer=" + inner.outerName + " source=" + sourceClass);
                }
                rejectPreservedClassWhenParentChanged(inner.name, inner.outerName);
            }
        }

        private void validateEnclosingOwner(String sourceClass, ClassNode node) throws IOException {
            if (node.outerClass == null || !node.outerClass.startsWith(MINECRAFT_PREFIX)
                    || VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(node.outerClass)) return;
            requireActualOutputClass(node.outerClass, "EnclosingMethod owner of " + sourceClass);
            rejectPreservedClassWhenParentChanged(sourceClass, node.outerClass);
            if (node.outerMethod == null && node.outerMethodDesc != null) {
                throw new IOException("EnclosingMethod has a descriptor without a method name: class=" + sourceClass
                        + " owner=" + node.outerClass + " descriptor=" + node.outerMethodDesc);
            }
            if (node.outerMethod != null && node.outerMethodDesc == null) {
                throw new IOException("EnclosingMethod has a method name without a full descriptor: class="
                        + sourceClass + " owner=" + node.outerClass + " method=" + node.outerMethod);
            }
        }

        private void requireActualOutputClass(String className, String use) throws IOException {
            if (!actualDeclarations.containsKey(className)) {
                throw new IOException("Structural Minecraft metadata refers to a class without an actual API "
                        + "declaration: use=" + use + " class=" + className);
            }
            if (!outputSourceClasses.contains(className)) {
                throw new IOException("Structural Minecraft metadata class is absent from the computed declaration "
                        + "closure: use=" + use + " class=" + className);
            }
        }

        private void rejectPreservedClassWhenParentChanged(String sourceClass, String parentClass)
                throws IOException {
            if (strictMappings.classes.containsKey(sourceClass)
                    || !strictMappings.classes.containsKey(parentClass)) return;
            String mappedParent = strictMappings.classes.get(parentClass);
            if (!parentClass.equals(mappedParent)) {
                throw new IOException("Cannot preserve an unmapped actual nested declaration while its related "
                        + "MCP-SRG parent changes name: preserved=" + sourceClass + " parent=" + parentClass
                        + " mappedParent=" + mappedParent);
            }
        }

        private static String runtimePackage(String internalName) {
            int separator = internalName.lastIndexOf('/');
            return separator < 0 ? "" : internalName.substring(0, separator);
        }

        private String mapClass(String internalName) throws IOException {
            if (internalName == null || !internalName.startsWith(MINECRAFT_PREFIX)) return internalName;
            if (VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(internalName)) return internalName;
            String target = targetNames.get(internalName);
            if (target == null) {
                throw new IOException("Minecraft type is outside the actual-declaration structural shim plan: "
                        + internalName);
            }
            return target;
        }

        private String mapDescriptor(String descriptor) throws IOException {
            try {
                Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
                if (type.getSort() == Type.METHOD) {
                    Type[] arguments = type.getArgumentTypes();
                    Type[] mappedArguments = new Type[arguments.length];
                    for (int index = 0; index < arguments.length; index++) {
                        mappedArguments[index] = mapType(type.getArgumentTypes()[index]);
                    }
                    return Type.getMethodDescriptor(mapType(type.getReturnType()), mappedArguments);
                }
                return mapType(type).getDescriptor();
            } catch (IllegalArgumentException cause) {
                throw new IOException("Invalid structural verifier-shim descriptor: " + descriptor, cause);
            }
        }

        private Type mapType(Type type) throws IOException {
            return switch (type.getSort()) {
                case Type.OBJECT -> Type.getObjectType(mapClass(type.getInternalName()));
                case Type.ARRAY -> Type.getType("[".repeat(type.getDimensions())
                        + mapType(type.getElementType()).getDescriptor());
                case Type.METHOD -> throw new IOException("Nested method type is invalid in a member descriptor: "
                        + type);
                default -> type;
            };
        }

        private Remapper asmRemapper(StructuralShimMemberResolver members) {
            return new Remapper() {
                @Override
                public String map(String internalName) {
                    try {
                        return mapClass(internalName);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot map structural verifier-shim class " + internalName,
                                cause);
                    }
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    try {
                        return members.mapFieldName(owner, name, descriptor);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot map structural verifier-shim field " + owner + "."
                                + name + descriptor, cause);
                    }
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    try {
                        return members.mapMethodName(owner, name, descriptor);
                    } catch (IOException cause) {
                        throw new IllegalStateException("Cannot map structural verifier-shim method " + owner + "."
                                + name + descriptor, cause);
                    }
                }
            };
        }

        private String mapClassForPlan(String internalName) throws IOException {
            return mapClass(internalName);
        }

        private Set<String> outputSourceClasses() {
            return outputSourceClasses;
        }

        private List<StructuralShimClassDecision> classDecisionRows() {
            return classDecisionRows;
        }

        private List<StructuralShimClassEdge> edgeRows() {
            return edgeRows;
        }

        private int exactMappingCount() {
            return (int) classDecisionRows.stream()
                    .filter(decision -> decision.decision.equals("EXACT_MCP_SRG_CL_MAPPING")).count();
        }

        private int preservedDeclarationCount() {
            return (int) classDecisionRows.stream()
                    .filter(decision -> decision.decision.equals("PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING"))
                    .count();
        }
    }

    private static final class StructuralReferenceRecordingRemapper extends Remapper {
        private final Set<String> references = new TreeSet<>();

        @Override
        public String map(String internalName) {
            if (internalName != null && internalName.startsWith(MINECRAFT_PREFIX)) references.add(internalName);
            return internalName;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return name;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return name;
        }

        private Set<String> references() {
            return Collections.unmodifiableSet(references);
        }
    }

    private static final class StructuralShimMemberResolver {
        private final ClassIndex actualApi;
        private final ClassIndex externalApi;
        private final ClassIndex launchWrapperIdentityProvider;
        private final Mappings strictMappings;
        private final StructuralShimClassMapping classMapping;
        private final Map<MemberRef, MutableStructuralShimMemberDecision> decisions = new TreeMap<>();
        private final Map<String, StructuralShimMemberDecision> enclosingClassDecisions = new TreeMap<>();
        private final Map<String, Set<String>> enclosingClassUses = new TreeMap<>();

        private StructuralShimMemberResolver(ClassIndex actualApi, ClassIndex externalApi,
                                             ClassIndex launchWrapperIdentityProvider,
                                             Mappings strictMappings, StructuralShimClassMapping classMapping) {
            this.actualApi = actualApi;
            this.externalApi = externalApi;
            this.launchWrapperIdentityProvider = launchWrapperIdentityProvider;
            this.strictMappings = strictMappings;
            this.classMapping = classMapping;
        }

        private void validateAllDeclaredMembers() throws IOException {
            Map<String, Set<MemberRef>> targetMembersByClass = new TreeMap<>();
            for (String sourceClass : classMapping.outputSourceClasses()) {
                ClassNode node = actualApi.structuralClassNode(sourceClass);
                Set<MemberRef> mappedDeclarations = new HashSet<>();
                for (FieldNode field : node.fields) {
                    MemberRef reference = new MemberRef(Kind.FIELD, sourceClass, field.name, field.desc);
                    StructuralShimMemberDecision decision = resolve(reference, field.access, "FIELD_DECLARATION");
                    MemberRef target = new MemberRef(Kind.FIELD, decision.targetOwner, decision.targetName,
                            decision.targetDescriptor);
                    if (!mappedDeclarations.add(target)) {
                        throw new IOException("Structural shim field remapping collides within class " + sourceClass
                                + ": " + target);
                    }
                }
                for (MethodNode method : node.methods) {
                    MemberRef reference = new MemberRef(Kind.METHOD, sourceClass, method.name, method.desc);
                    StructuralShimMemberDecision decision = resolve(reference, method.access, "METHOD_DECLARATION");
                    MemberRef target = new MemberRef(Kind.METHOD, decision.targetOwner, decision.targetName,
                            decision.targetDescriptor);
                    if (!mappedDeclarations.add(target)) {
                        throw new IOException("Structural shim method remapping collides within class " + sourceClass
                                + ": " + target);
                    }
                }
                targetMembersByClass.put(classMapping.mapClassForPlan(sourceClass), mappedDeclarations);
                validateEnclosingMethod(sourceClass, node);
            }
            if (targetMembersByClass.size() != classMapping.outputSourceClasses().size()) {
                throw new IOException("Structural shim member resolver did not validate every output class declaration");
            }
        }

        private void validateEnclosingMethod(String sourceClass, ClassNode node) throws IOException {
            if (node.outerClass == null) return;
            ClassIndex ownerIndex = classIndexForEnclosingOwner(node.outerClass);
            ClassNode ownerNode = ownerIndex.structuralClassNode(node.outerClass);
            if (node.outerClass.startsWith(MINECRAFT_PREFIX)
                    && !VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(node.outerClass)) {
                classMapping.requireActualOutputClass(node.outerClass,
                        "EnclosingMethod owner of " + sourceClass);
            }
            classMapping.mapClassForPlan(node.outerClass);
            if (node.outerMethod == null) {
                if (node.outerMethodDesc != null) {
                    throw new IOException("EnclosingMethod descriptor has no method name: class=" + sourceClass
                            + " owner=" + node.outerClass + " descriptor=" + node.outerMethodDesc);
                }
                String targetOwner = classMapping.mapClassForPlan(node.outerClass);
                StructuralShimMemberDecision decision = new StructuralShimMemberDecision("ENCLOSING_CLASS",
                        node.outerClass, "", "", ownerNode.access, targetOwner, "", "",
                        "ACTUAL_ENCLOSING_CLASS_METHOD_INDEX_ABSENT", "");
                StructuralShimMemberDecision previous = enclosingClassDecisions.putIfAbsent(node.outerClass, decision);
                if (previous != null && (previous.declarationAccess != decision.declarationAccess
                        || !previous.targetOwner.equals(decision.targetOwner)
                        || !previous.decision.equals(decision.decision))) {
                    throw new IOException("Actual enclosing-class metadata resolved inconsistently for "
                            + node.outerClass + ": existing=" + previous + " observed=" + decision);
                }
                enclosingClassUses.computeIfAbsent(node.outerClass, ignored -> new TreeSet<>()).add(sourceClass);
                return;
            }
            if (node.outerMethodDesc == null || !node.outerMethodDesc.startsWith("(")) {
                throw new IOException("EnclosingMethod lacks a complete method descriptor: class=" + sourceClass
                        + " owner=" + node.outerClass + " method=" + node.outerMethod
                        + " descriptor=" + node.outerMethodDesc);
            }
            MemberRef reference = new MemberRef(Kind.METHOD, node.outerClass, node.outerMethod,
                    node.outerMethodDesc);
            MemberRef providerReference = reference;
            if (ownerIndex == externalApi) {
                providerReference = new MemberRef(Kind.METHOD, reference.owner, reference.name,
                        classMapping.mapDescriptor(reference.descriptor));
            }
            MemberDeclaration declaration = ownerIndex.directStructuralDeclaration(providerReference);
            if (declaration == null) {
                throw new IOException("EnclosingMethod does not name an actual directly declared method: class="
                        + sourceClass + " sourceMethod=" + reference + " providerMethod=" + providerReference);
            }
            if (ownerIndex == actualApi && !VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(node.outerClass)) {
                resolve(reference, declaration.access, "ENCLOSING_METHOD");
            } else {
                rememberExternalIdentity(reference, providerReference, declaration.access, "ENCLOSING_METHOD",
                        ownerIndex);
            }
        }

        private ClassIndex classIndexForEnclosingOwner(String owner) throws IOException {
            if (VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(owner)) return launchWrapperIdentityProvider;
            if (owner.startsWith(MINECRAFT_PREFIX)) return actualApi;
            if (owner.startsWith("net/minecraftforge/")) return externalApi;
            if (owner.startsWith("java/")) return actualApi;
            throw new IOException("EnclosingMethod owner is outside the pinned MCP, Forge, JDK, and LaunchWrapper "
                    + "declaration providers: " + owner);
        }

        private StructuralShimMemberDecision rememberExternalIdentity(MemberRef reference,
                                                                       MemberRef providerReference,
                                                                       int access, String use,
                                                                       ClassIndex provider) throws IOException {
            MemberDeclaration actual = provider.directStructuralDeclaration(providerReference);
            if (actual == null) {
                throw new IOException("Pinned external structural provider does not directly declare member: source="
                        + reference + " provider=" + providerReference + " providerIndex=" + provider.description);
            }
            if (actual.access != access) {
                throw new IOException("Pinned external structural member access changed during resolution: source="
                        + reference + " provider=" + providerReference + " expected=" + actual.access
                        + " observed=" + access);
            }
            if (reference.kind == Kind.METHOD && strictMappings.methods.containsKey(reference)) {
                throw new IOException("External EnclosingMethod owner unexpectedly has an MCP-SRG method mapping: "
                        + reference);
            }
            if (reference.kind == Kind.FIELD
                    && strictMappings.fields.containsKey(reference.owner + "#" + reference.name)) {
                throw new IOException("External structural member unexpectedly has an MCP-SRG field mapping: "
                        + reference);
            }
            String targetOwner = classMapping.mapClassForPlan(reference.owner);
            String targetDescriptor = classMapping.mapDescriptor(reference.descriptor);
            if (!providerReference.owner.equals(targetOwner) || !providerReference.descriptor.equals(targetDescriptor)) {
                throw new IOException("Pinned external provider namespace differs from the structural shim target: "
                        + "source=" + reference + " provider=" + providerReference + " target=" + targetOwner
                        + "." + reference.name + targetDescriptor);
            }
            MutableStructuralShimMemberDecision existing = decisions.get(reference);
            if (existing != null) {
                existing.requireSame(access, targetOwner, reference.name, targetDescriptor,
                        "PINNED_EXTERNAL_PROVIDER_IDENTITY_DECLARATION");
                existing.uses.add(use);
                return existing.snapshot();
            }
            StructuralShimMemberDecision result = new StructuralShimMemberDecision(reference.kind.name(),
                    reference.owner, reference.name, reference.descriptor, access, targetOwner, reference.name,
                    targetDescriptor, "PINNED_EXTERNAL_PROVIDER_IDENTITY_DECLARATION", use);
            decisions.put(reference, new MutableStructuralShimMemberDecision(reference, access, targetOwner,
                    reference.name, targetDescriptor, result.decision, use));
            return result;
        }

        private StructuralShimMemberDecision resolve(MemberRef reference, int declarationAccess, String use)
                throws IOException {
            MutableStructuralShimMemberDecision existing = decisions.get(reference);
            if (existing != null) {
                if (existing.declarationAccess != declarationAccess) {
                    throw new IOException("Repeated structural member reference changed actual declaration access: "
                            + reference + " first=" + existing.declarationAccess + " observed=" + declarationAccess);
                }
                existing.uses.add(use);
                return existing.snapshot();
            }
            MemberDeclaration actual = actualApi.directStructuralDeclaration(reference);
            if (actual == null) {
                throw new IOException("Structural verifier-shim member is not an actual direct MCP API declaration: "
                        + reference);
            }
            if (actual.access != declarationAccess) {
                throw new IOException("Structural verifier-shim member access changed between class indexing and "
                        + "resolution: " + reference + " indexed=" + actual.access
                        + " observed=" + declarationAccess);
            }
            String targetOwner = classMapping.mapClassForPlan(reference.owner);
            String targetDescriptor = classMapping.mapDescriptor(reference.descriptor);
            String targetName = reference.name;
            String decision;
            if (reference.kind == Kind.FIELD) {
                FieldNameMapping mapping = strictMappings.fields.get(reference.owner + "#" + reference.name);
                if (mapping == null) {
                    decision = "NO_MCP_SRG_FD_PRESERVED_AFTER_ACTUAL_DIRECT_DECLARATION";
                } else {
                    if (!targetOwner.equals(mapping.targetOwner)) {
                        throw new IOException("Exact MCP-SRG field mapping owner disagrees with structural class "
                                + "plan: member=" + reference + " mappedOwner=" + mapping.targetOwner
                                + " expectedOwner=" + targetOwner);
                    }
                    targetName = mapping.targetName;
                    decision = "EXACT_MCP_SRG_FD";
                }
            } else {
                MethodNameMapping mapping = strictMappings.methods.get(reference);
                if (mapping == null) {
                    List<MemberRef> sameNameMappings = strictMappings.methods.keySet().stream()
                            .filter(candidate -> candidate.owner.equals(reference.owner)
                                    && candidate.name.equals(reference.name)).toList();
                    if (reference.name.startsWith("<")) {
                        decision = "NO_MCP_SRG_MD_JVM_SPECIAL_NAME_PRESERVED";
                    } else if (sameNameMappings.isEmpty()) {
                        decision = "NO_MCP_SRG_MD_PRESERVED_AFTER_ACTUAL_DIRECT_DECLARATION";
                    } else {
                        List<String> siblingDescriptors = sameNameMappings.stream()
                                .map(candidate -> candidate.descriptor).sorted().toList();
                        decision = "NO_EXACT_MCP_SRG_MD_PRESERVED_AFTER_ACTUAL_DIRECT_DECLARATION"
                                + ";OTHER_DESCRIPTORS_HAVE_EXACT_MD_ROWS=" + String.join(";", siblingDescriptors);
                    }
                } else {
                    if (!targetOwner.equals(mapping.target.owner)) {
                        throw new IOException("Exact MCP-SRG method mapping owner disagrees with structural class "
                                + "plan: member=" + reference + " mappedOwner=" + mapping.target.owner
                                + " expectedOwner=" + targetOwner);
                    }
                    if (!targetDescriptor.equals(mapping.target.descriptor)) {
                        throw new IOException("Exact MCP-SRG method mapping descriptor disagrees with structural "
                                + "class plan: member=" + reference + " mappedDescriptor="
                                + mapping.target.descriptor + " expectedDescriptor=" + targetDescriptor);
                    }
                    targetName = mapping.target.name;
                    decision = reference.name.startsWith("<")
                            ? "EXACT_MCP_SRG_MD_JVM_SPECIAL_NAME_PRESERVED" : "EXACT_MCP_SRG_MD";
                }
                if (reference.name.startsWith("<") && !targetName.equals(reference.name)) {
                    throw new IOException("MCP-SRG mapping attempts to rename JVM special method " + reference
                            + " to " + targetName);
                }
            }
            StructuralShimMemberDecision result = new StructuralShimMemberDecision(reference.kind.name(),
                    reference.owner, reference.name, reference.descriptor, declarationAccess, targetOwner,
                    targetName, targetDescriptor, decision, use);
            decisions.put(reference, new MutableStructuralShimMemberDecision(reference, declarationAccess,
                    targetOwner, targetName, targetDescriptor, decision, use));
            return result;
        }

        private String mapFieldName(String owner, String name, String descriptor) throws IOException {
            MemberRef reference = new MemberRef(Kind.FIELD, owner, name, descriptor);
            if (!owner.startsWith(MINECRAFT_PREFIX)) return name;
            if (VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(owner)) {
                MemberRef providerReference = new MemberRef(Kind.FIELD, owner, name,
                        classMapping.mapDescriptor(descriptor));
                MemberDeclaration declaration = launchWrapperIdentityProvider.directStructuralDeclaration(
                        providerReference);
                if (declaration == null) {
                    throw new IOException("Pinned LaunchWrapper identity provider lacks direct field declaration: "
                            + providerReference);
                }
                return rememberExternalIdentity(reference, providerReference, declaration.access,
                        "CLASS_REMAPPER_FIELD_DECLARATION", launchWrapperIdentityProvider).targetName;
            }
            MemberDeclaration declaration = actualApi.directStructuralDeclaration(reference);
            if (declaration == null) {
                throw new IOException("Structural remapper requested an undeclared field name: " + reference);
            }
            return resolve(reference, declaration.access, "CLASS_REMAPPER_FIELD_DECLARATION").targetName;
        }

        private String mapMethodName(String owner, String name, String descriptor) throws IOException {
            MemberRef reference = new MemberRef(Kind.METHOD, owner, name, descriptor);
            if (!owner.startsWith(MINECRAFT_PREFIX)) return name;
            if (VERIFIER_SHIM_IDENTITY_MINECRAFT_TYPES.contains(owner)) {
                MemberRef providerReference = new MemberRef(Kind.METHOD, owner, name,
                        classMapping.mapDescriptor(descriptor));
                MemberDeclaration declaration = launchWrapperIdentityProvider.directStructuralDeclaration(
                        providerReference);
                if (declaration == null) {
                    throw new IOException("Pinned LaunchWrapper identity provider lacks direct method declaration: "
                            + providerReference);
                }
                return rememberExternalIdentity(reference, providerReference, declaration.access,
                        "CLASS_REMAPPER_METHOD_DECLARATION", launchWrapperIdentityProvider).targetName;
            }
            MemberDeclaration declaration = actualApi.directStructuralDeclaration(reference);
            if (declaration == null) {
                throw new IOException("Structural remapper requested a method name without an actual direct "
                        + "declaration: " + reference);
            }
            return resolve(reference, declaration.access, "CLASS_REMAPPER_METHOD_DECLARATION").targetName;
        }

        private List<StructuralShimMemberDecision> decisionRows() {
            List<StructuralShimMemberDecision> rows = new ArrayList<>();
            for (MutableStructuralShimMemberDecision decision : decisions.values()) rows.add(decision.snapshot());
            enclosingClassDecisions.forEach((owner, decision) -> rows.add(new StructuralShimMemberDecision(
                    decision.kind, decision.sourceOwner, decision.sourceName, decision.sourceDescriptor,
                    decision.declarationAccess, decision.targetOwner, decision.targetName,
                    decision.targetDescriptor, decision.decision,
                    "ENCLOSING_CLASS_OF=" + String.join(";", enclosingClassUses.get(owner)))));
            rows.sort((first, second) -> String.join("\t", first.kind, first.sourceOwner, first.sourceName,
                    first.sourceDescriptor).compareTo(String.join("\t", second.kind, second.sourceOwner,
                    second.sourceName, second.sourceDescriptor)));
            return List.copyOf(rows);
        }

        private static final class MutableStructuralShimMemberDecision {
            private final MemberRef reference;
            private final int declarationAccess;
            private final String targetOwner;
            private final String targetName;
            private final String targetDescriptor;
            private final String decision;
            private final Set<String> uses = new TreeSet<>();

            private MutableStructuralShimMemberDecision(MemberRef reference, int declarationAccess,
                                                         String targetOwner, String targetName,
                                                         String targetDescriptor, String decision, String use) {
                this.reference = reference;
                this.declarationAccess = declarationAccess;
                this.targetOwner = targetOwner;
                this.targetName = targetName;
                this.targetDescriptor = targetDescriptor;
                this.decision = decision;
                this.uses.add(use);
            }

            private StructuralShimMemberDecision snapshot() {
                return new StructuralShimMemberDecision(reference.kind.name(), reference.owner, reference.name,
                        reference.descriptor, declarationAccess, targetOwner, targetName, targetDescriptor, decision,
                        String.join(";", uses));
            }

            private void requireSame(int access, String owner, String name, String descriptor, String expectedDecision)
                    throws IOException {
                if (declarationAccess != access || !targetOwner.equals(owner) || !targetName.equals(name)
                        || !targetDescriptor.equals(descriptor) || !decision.equals(expectedDecision)) {
                    throw new IOException("Repeated structural member reference resolved inconsistently: " + reference
                            + " existingAccess=" + declarationAccess + " requestedAccess=" + access
                            + " existingTarget=" + targetOwner + "." + targetName + targetDescriptor
                            + " requestedTarget=" + owner + "." + name + descriptor
                            + " existingDecision=" + decision + " requestedDecision=" + expectedDecision);
                }
            }
        }
    }

    private record MappingLine(int number, String[] fields) {}

    private static List<MappingLine> readLines(Path path) throws IOException {
        List<MappingLine> result = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lineNumber++;
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            result.add(new MappingLine(lineNumber, trimmed.split("\\s+")));
        }
        return result;
    }

    private static void requireLength(Path path, MappingLine line, int expected) throws IOException {
        if (line.fields.length != expected) {
            throw new IOException("Malformed mapping record at " + path + ":" + line.number
                    + " expected=" + expected + " fields actual=" + line.fields.length);
        }
    }

    private static <K, V> void putUnique(Map<K, V> target, K key, V value, Path path, int line)
            throws IOException {
        V previous = target.putIfAbsent(key, value);
        if (previous != null && !previous.equals(value)) {
            throw new IOException("Conflicting mapping at " + path + (line > 0 ? ":" + line : "")
                    + " key=" + key + " previous=" + previous + " actual=" + value);
        }
    }

    private record Name(String owner, String name) {
        private static Name parse(String value, Path path, int line) throws IOException {
            int separator = value.lastIndexOf('/');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IOException("Invalid mapped owner/name at " + path + ":" + line + ": " + value);
            }
            return new Name(value.substring(0, separator), value.substring(separator + 1));
        }
    }
}
