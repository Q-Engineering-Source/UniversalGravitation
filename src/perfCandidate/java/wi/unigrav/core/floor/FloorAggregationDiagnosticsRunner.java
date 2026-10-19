package wi.unigrav.core.floor;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.init.Bootstrap;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import wi.unigrav.core.floor.FloorChunkCoverage.Stats;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.CanonicalFaceSetCounts;
import wi.unigrav.core.floor.FloorFieldIndex.Source;
import wi.unigrav.core.floor.FloorFieldIndex.RetainedCounts;
import wi.unigrav.core.floor.FloorFieldProfile;
import wi.unigrav.core.floor.FloorWorldFields.WorldFields;
import wi.unigrav.core.floor.FloorAggregationPerfRunner.ChunkData;
import wi.unigrav.core.floor.FloorAggregationPerfRunner.Fixture;
import wi.unigrav.core.floor.FloorAggregationPerfRunner.FixtureId;
import wi.unigrav.core.floor.FloorAggregationPerfRunner.FixtureSpec;

/** Candidate-only diagnostic of the actual published compressed structures. */
public final class FloorAggregationDiagnosticsRunner {
    private static final String PROTOCOL = "UG_FLOOR_PERF_V1";
    private static final long MASK_BYTES = 4L * Long.BYTES;
    private static final long TANGENT_LOOKUP_BYTES = 256L * Short.BYTES;
    private static final FloorFieldProfile BUILTIN = new FloorFieldProfile(32.0, 8.0, 16.0);
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    private FloorAggregationDiagnosticsRunner() {
    }

    public static void main(String[] args) {
        try {
            run(parseArgs(args));
        } catch (Throwable failure) {
            System.err.println("FLOOR_CANDIDATE_DIAGNOSTICS_FAILURE stage=retained-counts cause="
                    + failure.getClass().getName() + " message=" + String.valueOf(failure.getMessage()));
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void run(Map<String, String> options) throws Throwable {
        if (!"verify".equals(required(options, "mode"))) {
            throw new IllegalArgumentException("unsupported diagnostics mode: " + options.get("mode"));
        }
        Path output = Path.of(required(options, "output")).toAbsolutePath().normalize();
        Path expectedFloorRoot = Path.of(required(options, "expected-floor-root")).toRealPath();
        if (Files.exists(output)) {
            throw new IOException("refusing to replace existing candidate retained-count report: " + output);
        }

        List<Map<String, Object>> classIdentities = List.of(
                classIdentity(FloorFieldIndex.class, expectedFloorRoot),
                classIdentity(FloorChunkSources.class, expectedFloorRoot),
                classIdentity(FloorChunkCoverage.class, expectedFloorRoot),
                classIdentity(FloorWorldFields.class, expectedFloorRoot));

        Bootstrap.register();
        List<Map<String, Object>> fixtureRows = new ArrayList<>();
        for (FixtureId id : FixtureId.values()) {
            int width = id.wide ? 16 : 8;
            Fixture fixture = FloorAggregationPerfRunner.createFixture(
                    new FixtureSpec(id, width, FloorAggregationPerfRunner.SEED));
            fixtureRows.add(captureFixture(fixture));
        }
        if (fixtureRows.size() != FixtureId.values().length) {
            throw new IllegalStateException("candidate diagnostics did not visit every common fixture");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("protocol", PROTOCOL);
        report.put("status", "PASS");
        report.put("candidateFloorClassRoot", expectedFloorRoot.toString());
        report.put("seed", FloorAggregationPerfRunner.SEED);
        report.put("fixtureCount", fixtureRows.size());
        report.put("maskPayloadDefinition", "identity-distinct live Mask256 primitive long fields only; 4 longs per mask");
        report.put("maskPayloadIsTotalHeapEstimate", false);
        report.put("tangentLookupPayloadDefinition", "identity-distinct live 256-entry primitive short arrays; 256 shorts per multi-normal face");
        report.put("tangentLookupPayloadIsTotalHeapEstimate", false);
        CanonicalFaceSetCounts faceSetCounts = FloorFieldIndex.canonicalFaceSetCounts();
        Map<String, Object> sharedFaceSetCounts = new LinkedHashMap<>();
        sharedFaceSetCounts.put("scope", "global shared immutable sample-face table");
        sharedFaceSetCounts.put("globalCanonicalFaceSetCount", faceSetCounts.tableEntries());
        sharedFaceSetCounts.put("globalCanonicalFaceSetWrapperCount", faceSetCounts.canonicalWrappers());
        sharedFaceSetCounts.put("globalDelegateEnumSetCount", faceSetCounts.delegateEnumSets());
        sharedFaceSetCounts.put("globalUnmodifiableDelegateWrapperCount", faceSetCounts.unmodifiableDelegates());
        sharedFaceSetCounts.put("isPerIndexRetainedCount", false);
        sharedFaceSetCounts.put("isTotalHeapEstimate", false);
        report.put("globalSharedSampleFaceSets", sharedFaceSetCounts);
        report.put("classIdentities", classIdentities);
        report.put("fixtures", fixtureRows);

        writeUtf8Atomically(output, JSON.toJson(report) + System.lineSeparator());
        System.out.println("CANDIDATE_RETAINED_COUNTS_PASS protocol=" + PROTOCOL
                + " fixtures=" + fixtureRows.size() + " path=" + output
                + " sha256=" + sha256(Files.readAllBytes(output)));
    }

    private static Map<String, Object> captureFixture(Fixture fixture) throws Throwable {
        Map<String, Object> multiLayerWideShape = fixture.id == FixtureId.MULTILAYER_WIDE
                ? verifyMultiLayerWideStorage(fixture) : null;
        long expectedPhysical = 0L;
        long expectedEmitters = 0L;
        long expectedSections = 0L;
        for (ChunkData chunk : fixture.chunks.values()) {
            expectedPhysical = Math.addExact(expectedPhysical, chunk.expectedPhysicalCount);
            expectedEmitters = Math.addExact(expectedEmitters, chunk.expectedEmitterCount);
            if (fixture.scannerApplicable) {
                for (ExtendedBlockStorage section : chunk.storage) {
                    if (section != null && !section.isEmpty()) {
                        expectedSections = Math.addExact(expectedSections, 1L);
                    }
                }
            } else {
                boolean any = !chunk.publicSources.isEmpty();
                if (any) expectedSections = Math.addExact(expectedSections, 1L);
            }
        }

        RetainedCounts counts;
        if (fixture.scannerApplicable) {
            WorldFields fields = FloorAggregationPerfRunner.loadRealWorldFields(fixture);
            Throwable failure = null;
            try {
                for (ChunkData chunk : fixture.chunks.values()) {
                    FloorChunkCoverage coverage = fields.publishedCoverage(chunk.key, chunk.owner);
                    if (coverage == null) {
                        throw new IllegalStateException("real WorldFields scanner did not publish " + chunk.key);
                    }
                    verifyChunkCoverage(fixture, chunk, coverage.stats());
                }
                counts = fields.retainedCounts();
            } catch (Throwable scanFailure) {
                failure = scanFailure;
                throw scanFailure;
            } finally {
                closePreserving(fields, failure);
            }
        } else {
            FloorFieldIndex index = new FloorFieldIndex();
            Throwable failure = null;
            try {
                for (ChunkData chunk : fixture.chunks.values()) {
                    index.replaceChunk(chunk.key, chunk.owner, List.copyOf(chunk.publicSources));
                    FloorChunkCoverage coverage = index.coverageFor(chunk.key, chunk.owner);
                    if (coverage == null) {
                        throw new IllegalStateException("public replaceChunk did not publish " + chunk.key);
                    }
                    verifyChunkCoverage(fixture, chunk, coverage.stats());
                }
                counts = index.retainedCounts();
            } catch (Throwable indexFailure) {
                failure = indexFailure;
                throw indexFailure;
            } finally {
                closePreserving(index, failure);
            }
        }

        if (counts.publishedChunks() != fixture.chunks.size()
                || counts.coverages() != fixture.chunks.size()
                || counts.physicalBlocks() != expectedPhysical
                || counts.emitters() != expectedEmitters
                || counts.sections() != expectedSections
                || counts.identityDistinctMasks() > counts.groups()
                || counts.maskPayloadBytes() != Math.multiplyExact(counts.identityDistinctMasks(), MASK_BYTES)
                || counts.tangentNormalLookupPayloadBytes()
                != Math.multiplyExact(counts.tangentNormalLookupFaces(), TANGENT_LOOKUP_BYTES)
                || counts.routePageNormalPrimitivePayloadBytes()
                != Math.multiplyExact(counts.routeShards(), Integer.BYTES)
                || counts.routePagePresencePrimitivePayloadBytes()
                != Math.multiplyExact(counts.routeBuckets(), Long.BYTES)
                || Math.addExact(counts.compactRoutePages(), counts.wideRoutePages()) != counts.routeBuckets()
                || counts.routeShardReferenceSlots() != counts.routeShards()
                || counts.coverageBuckets() > counts.groups()
                || counts.routeShards() < counts.routeBuckets()) {
            throw new IllegalStateException("live candidate retained counts do not match fixture contract id="
                    + fixture.id + " expectedPhysical=" + expectedPhysical + " expectedEmitters=" + expectedEmitters
                    + " expectedSections=" + expectedSections + " observed=" + counts);
        }
        if ((fixture.id == FixtureId.DENSE || fixture.id == FixtureId.DENSE_WIDE)
                && (counts.groups() != fixture.chunks.size()
                || counts.identityDistinctMasks() != fixture.chunks.size())) {
            throw new IllegalStateException("dense same-profile plane did not retain one mask group per chunk id="
                    + fixture.id + " chunks=" + fixture.chunks.size() + " observed=" + counts);
        }
        if (fixture.id == FixtureId.DOUBLE_SIX_FACE && expectedEmitters != Math.multiplyExact(expectedPhysical, 2L)) {
            throw new IllegalStateException("double-sided fixture does not contain two actual emitters per physical block");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("fixture", fixture.id.name());
        row.put("width", fixture.width);
        row.put("seed", fixture.seed);
        row.put("expectedChunks", fixture.chunks.size());
        row.put("sourcePath", fixture.scannerApplicable
                ? "real ExtendedBlockStorage scan through WorldFields.load/flush"
                : "real public FloorFieldIndex.replaceChunk with deterministic mixed-profile source batches");
        row.put("worldFieldsApplicable", fixture.scannerApplicable);
        row.put("expectedPhysicalBlocks", expectedPhysical);
        row.put("expectedEmitters", expectedEmitters);
        row.put("expectedStorageSections", expectedSections);
        row.put("multiLayerWideShape", multiLayerWideShape);
        row.put("counts", countsMap(counts));
        return row;
    }

    private static void verifyChunkCoverage(Fixture fixture, ChunkData chunk, Stats stats) {
        if (stats.physicalBlockCount() != chunk.expectedPhysicalCount
                || stats.emitterCount() != chunk.expectedEmitterCount) {
            throw new IllegalStateException("published coverage disagrees with actual fixture source counts fixture="
                    + fixture.id + " chunk=" + chunk.key + " expectedPhysical=" + chunk.expectedPhysicalCount
                    + " expectedEmitters=" + chunk.expectedEmitterCount + " actual=" + stats);
        }
    }

    private static Map<String, Object> countsMap(RetainedCounts counts) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publishedChunks", counts.publishedChunks());
        row.put("coverages", counts.coverages());
        row.put("sections", counts.sections());
        row.put("routeBuckets", counts.routeBuckets());
        row.put("routeShards", counts.routeShards());
        row.put("routePageNormalPrimitivePayloadBytes", counts.routePageNormalPrimitivePayloadBytes());
        row.put("routePagePresencePrimitivePayloadBytes", counts.routePagePresencePrimitivePayloadBytes());
        row.put("compactRoutePages", counts.compactRoutePages());
        row.put("wideRoutePages", counts.wideRoutePages());
        row.put("routePagePresencePrimitivePayloadScope",
                "one long per live route page, including the zero sentinel for wide pages; payload only, not total heap");
        row.put("routeShardReferenceSlots", counts.routeShardReferenceSlots());
        row.put("coverageBuckets", counts.coverageBuckets());
        row.put("groups", counts.groups());
        row.put("physicalBlocks", counts.physicalBlocks());
        row.put("emitters", counts.emitters());
        row.put("identityDistinctMasks", counts.identityDistinctMasks());
        row.put("maskPayloadBytes", counts.maskPayloadBytes());
        row.put("tangentNormalLookupFaces", counts.tangentNormalLookupFaces());
        row.put("tangentNormalLookupPayloadBytes", counts.tangentNormalLookupPayloadBytes());
        return row;
    }

    private static Map<String, Object> verifyMultiLayerWideStorage(Fixture fixture) {
        if (fixture.width != 16 || fixture.chunks.size() != 256) {
            throw new IllegalStateException("wide multilayer fixture has the wrong chunk shape");
        }
        long cellsPerLayer = 65_536L;
        Map<String, Long> layerYCounts = new TreeMap<>();
        Map<String, Long> layerFaceCounts = new TreeMap<>();
        Set<BlockCoordinate> physicalBlocks = new HashSet<>();
        long emitters = 0L;
        long storageSections = 0L;
        int upNormalMask = 0;
        int downNormalMask = 0;
        for (ChunkData chunk : fixture.chunks.values()) {
            int nonemptySections = 0;
            for (int sectionIndex = 0; sectionIndex < chunk.storage.length; sectionIndex++) {
                ExtendedBlockStorage section = chunk.storage[sectionIndex];
                if (section != null && !section.isEmpty()) {
                    nonemptySections++;
                    if (sectionIndex != 1 || section.getYLocation() != 16) {
                        throw new IllegalStateException("wide multilayer actual storage has unexpected section"
                                + " chunk=" + chunk.key + " index=" + sectionIndex
                                + " y=" + section.getYLocation());
                    }
                    storageSections++;
                }
            }
            if (nonemptySections != 1) {
                throw new IllegalStateException("wide multilayer chunk must retain exactly sectionY=1 chunk="
                        + chunk.key + " sections=" + nonemptySections);
            }
            List<Source> actual = FloorChunkSources.scanExisting(chunk.key.x(), chunk.key.z(),
                    () -> chunk.storage, BUILTIN).snapshot();
            if (actual.size() != 1024 || chunk.expectedPhysicalCount != 1024L
                    || chunk.expectedEmitterCount != 1024L) {
                throw new IllegalStateException("wide multilayer actual storage has wrong per-chunk source count"
                        + " chunk=" + chunk.key + " sources=" + actual.size());
            }
            for (Source source : actual) {
                if (!physicalBlocks.add(new BlockCoordinate(source.x(), source.y(), source.z()))) {
                    throw new IllegalStateException("wide multilayer storage has more than one emitter per physical cell"
                            + " source=" + source);
                }
                String y = Integer.toString(source.y());
                layerYCounts.merge(y, 1L, Math::addExact);
                layerFaceCounts.merge(y + "|" + source.face().name(), 1L, Math::addExact);
                if (source.face() == Face.UP) {
                    upNormalMask |= 1 << Math.floorMod(source.y(), 16);
                } else if (source.face() == Face.DOWN) {
                    downNormalMask |= 1 << Math.floorMod(source.y(), 16);
                } else {
                    throw new IllegalStateException("wide multilayer actual source has a non-UP/DOWN face: " + source);
                }
                emitters++;
            }
        }
        Map<String, Long> expectedLayers = Map.of("16", cellsPerLayer, "20", cellsPerLayer,
                "24", cellsPerLayer, "28", cellsPerLayer);
        Map<String, Long> expectedFaces = Map.of("16|UP", cellsPerLayer, "20|DOWN", cellsPerLayer,
                "24|UP", cellsPerLayer, "28|DOWN", cellsPerLayer);
        long actualPhysical = physicalBlocks.size();
        if (actualPhysical != 262_144L || emitters != 262_144L || storageSections != 256L
                || !layerYCounts.equals(expectedLayers) || !layerFaceCounts.equals(expectedFaces)
                || upNormalMask != ((1 << 0) | (1 << 8))
                || downNormalMask != ((1 << 4) | (1 << 12))) {
            throw new IllegalStateException("wide multilayer actual storage differs from the 4-layer contract"
                    + " physical=" + actualPhysical + " emitters=" + emitters
                    + " storageSections=" + storageSections + " yCounts=" + layerYCounts
                    + " faceCounts=" + layerFaceCounts + " upMask=" + upNormalMask
                    + " downMask=" + downNormalMask);
        }
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("physicalBlocks", actualPhysical);
        shape.put("emitters", emitters);
        shape.put("retainedStorageSections", storageSections);
        shape.put("layerYCounts", layerYCounts);
        shape.put("layerFaceCounts", layerFaceCounts);
        shape.put("upLocalNormalMask", upNormalMask);
        shape.put("downLocalNormalMask", downNormalMask);
        return shape;
    }

    private record BlockCoordinate(int x, int y, int z) {
    }

    private static Map<String, Object> classIdentity(Class<?> type, Path expectedRoot)
            throws IOException, URISyntaxException, NoSuchAlgorithmException {
        Path actualRoot = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        if (!actualRoot.equals(expectedRoot)) {
            throw new IllegalStateException("candidate floor class was shadowed: type=" + type.getName()
                    + " expectedRoot=" + expectedRoot + " actualRoot=" + actualRoot);
        }
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("loaded candidate class bytes are missing: " + type.getName());
            bytes = input.readAllBytes();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", type.getName());
        row.put("codeSource", actualRoot.toString());
        row.put("bytes", bytes.length);
        row.put("sha256", sha256(bytes));
        return row;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
                throw new IllegalArgumentException("arguments must use --name=value: " + arg);
            }
            int equals = arg.indexOf('=');
            String name = arg.substring(2, equals);
            if (values.putIfAbsent(name, arg.substring(equals + 1)) != null) {
                throw new IllegalArgumentException("duplicate diagnostics argument: --" + name);
            }
        }
        return values;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing diagnostics argument --" + key);
        }
        return value;
    }

    private static void closePreserving(WorldFields fields, Throwable primary) throws Throwable {
        try {
            fields.close();
        } catch (Throwable closeFailure) {
            if (primary != null) {
                if (primary != closeFailure) primary.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private static void closePreserving(FloorFieldIndex index, Throwable primary) throws Throwable {
        try {
            index.close();
        } catch (Throwable closeFailure) {
            if (primary != null) {
                if (primary != closeFailure) primary.addSuppressed(closeFailure);
            } else {
                throw closeFailure;
            }
        }
    }

    private static void writeUtf8Atomically(Path output, String value) throws IOException {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("retained-count report requires a parent directory: " + output);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02X", value & 0xff));
        return result.toString();
    }
}
