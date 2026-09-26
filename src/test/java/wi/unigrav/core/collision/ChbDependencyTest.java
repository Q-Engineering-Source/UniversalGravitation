package wi.unigrav.core.collision;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion;
import net.minecraftforge.fml.common.versioning.VersionParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ChbDependencyTest {
    @Test
    void compiledLoaderMetadataRequiresTheExactApprovedChbVersion() throws Exception {
        var dependencies = new AtomicReference<String>();
        try (InputStream input = getClass().getResourceAsStream("/wi/unigrav/UnigravMod.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!descriptor.equals("Lnet/minecraftforge/fml/common/Mod;")) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if (name.equals("dependencies")) dependencies.set((String) value);
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertNotNull(dependencies.get());
        String[] entries = dependencies.get().split(";");
        assertEquals(2, entries.length);
        assertEquals("after:neofontrender_ui_enhancements", entries[1]);
        assertTrue(entries[0].startsWith("required-after:"));
        var required = VersionParser.parseVersionReference(entries[0].substring("required-after:".length()));
        assertEquals("crlhitbox", required.getLabel());
        assertTrue(required.containsVersion(new DefaultArtifactVersion("crlhitbox", "0.1.0-SNAPSHOT")));
        assertFalse(required.containsVersion(new DefaultArtifactVersion("crlhitbox", "0.0.9")));
        assertFalse(required.containsVersion(new DefaultArtifactVersion("crlhitbox", "0.1.0")));
        assertFalse(required.containsVersion(new DefaultArtifactVersion("crlhitbox", "0.2.0-SNAPSHOT")));
    }

    @Test
    void missingLibraryFailsInARealIsolatedJvmAndRetainsTheLinkageCause() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(ChbDependency.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(ChbDependencyProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", classpath,
                "wi.unigrav.core.collision.ChbDependencyProbe").redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "isolated dependency probe timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertNotEquals(0, process.exitValue(), output);
            assertTrue(output.contains("requires CHB-Lib capsule contract revision 1"), output);
            assertTrue(output.contains("Caused by: java.lang.NoClassDefFoundError"), output);
            assertTrue(output.contains("dev/crlhitbox/api/geometry/cast/CapsuleAabbCast3d"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
