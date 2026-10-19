package wi.unigrav.core.floor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.sun.management.ThreadMXBean;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import wi.unigrav.api.math.Vec3;
import wi.unigrav.core.floor.FloorFieldEmitter.Face;
import wi.unigrav.core.floor.FloorFieldIndex.ChunkKey;
import wi.unigrav.core.floor.FloorFieldIndex.Kind;
import wi.unigrav.core.floor.FloorFieldIndex.Sample;
import wi.unigrav.core.floor.FloorFieldIndex.Source;
import wi.unigrav.core.floor.FloorFieldIndex.StandingSample;
import wi.unigrav.core.floor.FloorWorldFields.WorldFields;

/**
 * Common scalar-versus-candidate driver. It is compiled once against the frozen
 * F07 scalar/API10 inputs and launched with exactly one floor implementation in
 * each child class path.
 */
public final class FloorAggregationPerfRunner {
    private static final String CLASS_NAME = FloorAggregationPerfRunner.class.getName();
    private static final String PROTOCOL = "UG_FLOOR_PERF_V1";
    static final long SEED = 0x55474608L;
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURE_ROUNDS = 15;
    private static final int SAMPLE_OPERATIONS = 65_536;
    private static final int DIRTY_OPERATIONS = 512;
    private static final int TRACE_MAGIC = 0x55474608;
    private static final int TRACE_RECORD_BYTES = 480;
    private static final double[] RESPONSES = {0.0, 0.25, 1.0, 2.0, 1.0e-320};
    private static final Face[] ALL_FACES = Face.values();
    private static final String GC_SCOPE_NOTE = "GC count/time deltas span RoundClock.beginRound through finish, including harness loop/checksum bookkeeping; they are not per-operation attribution. Elapsed/allocation windows are operation-specific; sample windows cover only the production API call and return, with checksum consumption allocation-free. Fixture cleanup after finish is excluded.";
    private static final FloorFieldProfile BUILTIN = new FloorFieldProfile(32.0, 8.0, 16.0);
    private static final List<FloorFieldProfile> PROFILE_SET = List.of(
            new FloorFieldProfile(8.0, 2.0, 8.0),
            new FloorFieldProfile(16.0, 4.0, 12.0),
            new FloorFieldProfile(32.0, 8.0, 16.0),
            new FloorFieldProfile(48.0, 6.0, 20.0));
    private static volatile long blackhole;
    private static volatile WorldFields histogramWorld;

    private FloorAggregationPerfRunner() {
    }

    public static void main(String[] args) {
        try {
            Map<String, String> options = parseArgs(args);
            switch (required(options, "mode")) {
                case "preflight" -> runPreflight(options);
                case "compare" -> runComparison(options);
                case "child" -> runChild(options);
                case "histogram-child" -> runHistogramChild(options);
                default -> throw new IllegalArgumentException("unsupported runner mode: " + options.get("mode"));
            }
        } catch (Throwable failure) {
            System.err.println("FLOOR_PERF_FAILURE stage=runner cause=" + failure.getClass().getName()
                    + " message=" + String.valueOf(failure.getMessage()));
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }

    public static void runComparison(Map<String, String> rawOptions) throws Exception {
        Options options = Options.from(rawOptions);
        validatePreflightReceipt(options.preflightReceipt());
        Path output = options.output();
        Files.createDirectories(output);
        Path equivalencePath = output.resolve("equivalence.json");
        Path measurementsPath = output.resolve("measurements.jsonl");
        if (Files.exists(equivalencePath) || Files.exists(measurementsPath)) {
            throw new IOException("refusing to overwrite an existing floor performance comparison output");
        }
        writeEnvironment(output.resolve("environment.json"), options);
        Files.copy(options.baselineManifest(), output.resolve("baseline-manifest.json"));
        writeCommandSummary(output.resolve("commands-and-classpaths.txt"), options);

        List<EquivalenceResult> equivalence = new ArrayList<>();
        List<String> measurementLines = new ArrayList<>();
        List<FixtureId> fixtures = List.of(FixtureId.values());
        List<HistogramResult> histograms = List.of();
        try {
            for (FixtureId fixture : fixtures) {
                for (int fork = 0; fork < 3; fork++) {
                    ChildPair pair = launchTracePair(options, fixture, fork);
                    try {
                        TraceSummary baseline = pair.baseline().awaitTrace();
                        TraceSummary candidate = pair.candidate().awaitTrace();
                        verifyEquivalentTrace(baseline, candidate);
                        equivalence.add(new EquivalenceResult(fixture, fork, baseline, candidate, "PASS"));

                        boolean baselineFirst = (fork & 1) == 0;
                        ChildHandle first = baselineFirst ? pair.baseline() : pair.candidate();
                        ChildHandle second = baselineFirst ? pair.candidate() : pair.baseline();
                        first.measureAndCollect(measurementLines);
                        second.measureAndCollect(measurementLines);
                    } catch (Throwable failure) {
                        pair.stopIfRunning(failure);
                        throw failure;
                    }
                }
            }
            histograms = captureHistograms(options, output);
            writeEquivalenceReport(equivalencePath, equivalence, "PASS", null);
            writeMeasurementLines(measurementsPath, measurementLines);
            writeSummary(output.resolve("summary.csv"), measurementLines);
            writeHistogramReport(output.resolve("retained.txt"), histograms,
                    "Candidate typed retainedCounts integration is a separate hook; "
                            + "this file contains only real dedicated-child class histograms.");
            writeHistogramManifest(output.resolve("histogram-manifest.json"), histograms);
            writeOverallStatus(output.resolve("result.json"), equivalence, measurementLines, histograms,
                    options.candidateDiagnosticsReady());
            if (!options.candidateDiagnosticsReady()) {
                throw new IllegalStateException("common comparison completed, but candidate typed retainedCounts "
                        + "were not supplied; result.json is NON_PASS and F08 cannot be marked complete");
            }
        } catch (Throwable failure) {
            preserveFailureReports(output, equivalencePath, measurementsPath, equivalence,
                    measurementLines, histograms, options.candidateDiagnosticsReady(), failure);
            throw failure;
        }
    }

    public static void runChild(Map<String, String> rawOptions) throws Exception {
        Variant variant = Variant.valueOf(required(rawOptions, "variant").toUpperCase());
        FixtureId id = FixtureId.valueOf(required(rawOptions, "fixture").toUpperCase());
        int width = id.wide ? 16 : 8;
        long fork = Long.parseLong(required(rawOptions, "fork"));
        Path ready = Path.of(required(rawOptions, "ready"));
        Path tracePath = Path.of(required(rawOptions, "trace"));
        Path measurementsPath = Path.of(required(rawOptions, "measurements"));
        Path expectedFloorRoot = Path.of(required(rawOptions, "expected-floor-root"));
        Files.createDirectories(ready.getParent());
        Files.createDirectories(tracePath.getParent());
        verifyFloorClassOrigins(expectedFloorRoot);
        Bootstrap.register();

        Fixture fixture = createFixture(new FixtureSpec(id, width, SEED));
        FixtureWorkload workload = prepareWorkload(fixture);
        TraceSummary trace = writeEquivalenceTrace(fixture, workload, tracePath);
        writeFixtureManifest(ready.resolveSibling("fixture.json"), fixture, trace);
        writeTraceReady(ready, variant, id, fork, trace);
        System.out.println("TRACE_READY variant=" + variant + " fixture=" + id + " fork=" + fork
                + " rows=" + trace.rows() + " sha256=" + trace.rawSha256());

        String command = awaitChildCommand();
        if (!"MEASURE".equals(command)) {
            throw new IllegalStateException("comparison parent released child with " + command);
        }
        List<RoundRow> rows;
        try {
            rows = measureFixture(fixture, workload, variant, fork);
            writeRoundRows(measurementsPath, rows);
        } finally {
            workload.close();
        }
        System.out.println("MEASUREMENT_COMPLETE variant=" + variant + " fixture=" + id + " fork=" + fork
                + " rows=" + rows.size() + " checksum=" + Long.toUnsignedString(blackhole));
    }

    private static void runPreflight(Map<String, String> options) throws Exception {
        Variant variant = Variant.valueOf(required(options, "variant").toUpperCase());
        Path expectedFloorRoot = Path.of(required(options, "expected-floor-root"));
        verifyFloorClassOrigins(expectedFloorRoot);
        Bootstrap.register();

        ExtendedBlockStorage[] storage = new ExtendedBlockStorage[16];
        storage[1] = new ExtendedBlockStorage(16, true);
        storage[1].set(0, 0, 0, FloorBlocks.GRAVITY_FLOOR.getDefaultState());
        ChunkKey key = new ChunkKey(0, 0);
        Object owner = new Object();

        FloorChunkSources scanner = FloorChunkSources.scanExisting(0, 0, () -> storage, BUILTIN);
        List<Source> initial = scanner.snapshot();
        if (initial.size() != 1) {
            throw new IllegalStateException("preflight real scanner expected one emitter, got " + initial.size());
        }
        FloorFieldIndex index = new FloorFieldIndex();
        index.replaceChunk(key, owner, initial);
        Vec3 point = new Vec3(0.5, 17.5, 0.5);
        assertNonzero(index.sample(point, 1.0), "preflight index sample");
        consume(index.sampleForStanding(point, 1.0));

        WorldFields fields = new WorldFields();
        fields.load(key, owner, () -> FloorChunkSources.scanExisting(0, 0, () -> storage, BUILTIN), true);
        fields.flush(false);
        assertNonzero(fields.sample(point, 1.0, false).orElseThrow(), "preflight WorldFields sample");
        consume(fields.sampleForStanding(point, 1.0, false).orElseThrow());

        storage[1].set(0, 0, 0, Blocks.STONE.getDefaultState());
        fields.markDirty(key, owner, 0, 16, 0);
        fields.flush(false);
        if (!fields.sample(point, 1.0, false).orElseThrow().gravityAccel().equals(Vec3.ZERO)) {
            throw new IllegalStateException("preflight real markDirty/flush did not remove the emitter");
        }
        fields.unload(key, owner);
        index.removeChunk(key, owner);
        consume(fields.sampleForStanding(point, 1.0, false).orElseThrow());
        fields.close();
        index.clear();
        System.out.println("PREFLIGHT_PASS protocol=" + PROTOCOL + " variant=" + variant
                + " bootstrap=real scan=real loadFlush=real sample=real standing=real dirty=real unload=real");
    }

    private static void runHistogramChild(Map<String, String> rawOptions) throws Exception {
        Variant variant = Variant.valueOf(required(rawOptions, "variant").toUpperCase());
        FixtureId id = FixtureId.valueOf(required(rawOptions, "fixture").toUpperCase());
        Path ready = Path.of(required(rawOptions, "ready"));
        Path expectedFloorRoot = Path.of(required(rawOptions, "expected-floor-root"));
        verifyFloorClassOrigins(expectedFloorRoot);
        Bootstrap.register();
        Fixture fixture = createFixture(new FixtureSpec(id, id.wide ? 16 : 8, SEED));
        WorldFields fields = loadRealWorldFields(fixture);
        histogramWorld = fields;
        long pid = ProcessHandle.current().pid();
        writeUtf8Atomically(ready, "pid=" + pid + "\nvariant=" + variant + "\nfixture=" + id + "\n");
        fixture = null;
        fields = null;
        System.out.println("HISTOGRAM_READY pid=" + pid + " variant=" + variant + " fixture=" + id);
        String command = awaitChildCommand();
        if (!"CAPTURED".equals(command)) {
            throw new IllegalStateException("histogram parent released child with " + command);
        }
        WorldFields retained = histogramWorld;
        histogramWorld = null;
        retained.close();
        System.out.println("HISTOGRAM_CHILD_COMPLETE pid=" + pid);
    }

    private static ChildPair launchTracePair(Options options, FixtureId fixture, int fork) throws Exception {
        String token = fixture.name().toLowerCase() + "-fork-" + fork;
        Path directory = options.output().resolve("children").resolve(token);
        Files.createDirectories(directory);
        ChildHandle baseline = launchChild(options, Variant.BASELINE, fixture, fork,
                options.baselineClasspath(), options.expectedBaselineFloorRoot(), directory.resolve("baseline"));
        try {
            ChildHandle candidate = launchChild(options, Variant.CANDIDATE, fixture, fork,
                    options.candidateClasspath(), options.expectedCandidateFloorRoot(), directory.resolve("candidate"));
            return new ChildPair(baseline, candidate);
        } catch (Throwable failure) {
            baseline.stopIfRunning(failure);
            throw failure;
        }
    }

    private static ChildHandle launchChild(Options options, Variant variant, FixtureId fixture, int fork,
                                           List<String> classpath, Path expectedFloorRoot, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path ready = directory.resolve("trace-ready.json");
        Path trace = directory.resolve("equivalence.trace.gz");
        Path measurements = directory.resolve("measurements.jsonl");
        Path stdout = directory.resolve("stdout.log");
        Path stderr = directory.resolve("stderr.log");
        List<String> command = javaCommand(String.join(System.getProperty("path.separator"), classpath), "--mode=child",
                "--variant=" + variant.name().toLowerCase(),
                "--fixture=" + fixture.name().toLowerCase(),
                "--fork=" + fork,
                "--ready=" + ready,
                "--trace=" + trace,
                "--measurements=" + measurements,
                "--expected-floor-root=" + expectedFloorRoot);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        ChildHandle handle = new ChildHandle(variant, fixture, fork, process, ready, trace,
                measurements, stdout, stderr, command);
        try {
            handle.awaitReady();
            return handle;
        } catch (Throwable failure) {
            terminateOwnedProcessTree(process, failure, "trace child readiness failure");
            throw failure;
        }
    }

    private static List<HistogramResult> captureHistograms(Options options, Path output) throws Exception {
        List<HistogramResult> results = new ArrayList<>();
        for (FixtureId fixture : List.of(FixtureId.DENSE, FixtureId.DOUBLE_SIX_FACE, FixtureId.MULTILAYER)) {
            for (Variant variant : Variant.values()) {
                String name = variant.name().toLowerCase() + "-" + fixture.name().toLowerCase();
                Path directory = output.resolve("histograms").resolve(name);
                Files.createDirectories(directory);
                Path ready = directory.resolve("ready.txt");
                Path stdout = directory.resolve("stdout.log");
                Path stderr = directory.resolve("stderr.log");
                Path raw = directory.resolve("jcmd-GC-class-histogram.txt");
                List<String> cp = variant == Variant.BASELINE
                        ? options.baselineClasspath() : options.candidateClasspath();
                Path floorRoot = variant == Variant.BASELINE
                        ? options.expectedBaselineFloorRoot() : options.expectedCandidateFloorRoot();
                List<String> command = javaCommand(String.join(System.getProperty("path.separator"), cp),
                        "--mode=histogram-child",
                        "--variant=" + variant.name().toLowerCase(),
                        "--fixture=" + fixture.name().toLowerCase(),
                        "--ready=" + ready,
                        "--expected-floor-root=" + floorRoot);
                Process child = new ProcessBuilder(command).directory(directory.toFile())
                        .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
                boolean released = false;
                Throwable primaryFailure = null;
                try {
                    waitForFileOrExit(ready, child, stdout, stderr, 10, TimeUnit.MINUTES);
                    Map<String, String> identity = readProperties(ready);
                    long pid = Long.parseLong(required(identity, "pid"));
                    Path jcmd = javaExecutable("jcmd");
                    List<String> jcmdCommand = List.of(jcmd.toString(), Long.toString(pid), "GC.class_histogram");
                    Path jcmdError = directory.resolve("jcmd-stderr.log");
                    Process jcmdProcess = new ProcessBuilder(jcmdCommand).directory(directory.toFile())
                            .redirectOutput(raw.toFile()).redirectError(jcmdError.toFile()).start();
                    int jcmdExit = waitFor(jcmdProcess, 5, TimeUnit.MINUTES,
                            "jcmd GC.class_histogram", jcmdCommand, jcmdError);
                    if (jcmdExit != 0) {
                        throw new IOException("jcmd GC.class_histogram exited " + jcmdExit
                                + "; stdout=" + raw + "; stderr=" + jcmdError);
                    }
                    sendChildCommand(child, "CAPTURED");
                    released = true;
                    int childExit = waitFor(child, 2, TimeUnit.MINUTES,
                            "histogram child", command, stderr);
                    if (childExit != 0) {
                        throw new IOException("histogram child exited " + childExit
                                + "; stdout=" + stdout + "; stderr=" + stderr);
                    }
                    results.add(new HistogramResult(variant, fixture, pid, command, jcmdCommand,
                            raw, sha256(raw), stdout, stderr));
                } catch (Throwable failure) {
                    primaryFailure = failure;
                    throw failure;
                } finally {
                    if (!released && child.isAlive()) {
                        Throwable cleanupFailure = null;
                        try {
                            sendChildCommand(child, "STOP");
                        } catch (Throwable stopFailure) {
                            if (child.isAlive()) cleanupFailure = stopFailure;
                        }
                        try {
                            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                                cleanupFailure = terminateOwnedProcessTree(child,
                                        cleanupFailure, "histogram child cleanup");
                            }
                        } catch (Throwable waitFailure) {
                            cleanupFailure = combineFailures(cleanupFailure, waitFailure);
                            cleanupFailure = terminateOwnedProcessTree(child,
                                    cleanupFailure, "histogram child cleanup after wait failure");
                        }
                        if (cleanupFailure != null) {
                            if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                            else rethrow(cleanupFailure);
                        }
                    }
                }
            }
        }
        return List.copyOf(results);
    }

    private static List<RoundRow> measureFixture(Fixture fixture, FixtureWorkload workload,
                                                  Variant variant, long fork) throws Exception {
        List<RoundRow> rows = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            if (!operation.applicable(fixture)) {
                rows.add(RoundRow.notApplicable(variant, fixture.id, fork, operation,
                        operation.notApplicableReason));
                continue;
            }
            rows.addAll(measure(operation, WARMUP_ROUNDS, MEASURE_ROUNDS,
                    fixture, workload, variant, fork));
        }
        return rows;
    }

    static List<RoundRow> measure(Operation operation, int warmupRounds, int measuredRounds,
                                  Fixture fixture, FixtureWorkload workload,
                                  Variant variant, long fork) throws Exception {
        if (warmupRounds < 0 || measuredRounds <= 0) {
            throw new IllegalArgumentException("warmup/measurement rounds must be nonnegative/positive");
        }
        List<RoundRow> rows = new ArrayList<>(measuredRounds);
        for (int round = 0; round < warmupRounds + measuredRounds; round++) {
            boolean collect = round >= warmupRounds;
            RoundStats stats = executeRound(fixture, workload, operation, collect);
            if (operation.usesSampleQuerySchedule() && stats.operations() != SAMPLE_OPERATIONS) {
                throw new IllegalStateException("sample operation did not consume the complete fixed query schedule operation="
                        + operation.label + " expected=" + SAMPLE_OPERATIONS + " actual=" + stats.operations());
            }
            blackhole = Long.rotateLeft(blackhole, 9) ^ stats.checksum();
            if (collect) {
                boolean sampleOperation = operation.usesSampleQuerySchedule();
                rows.add(new RoundRow(variant, fixture.id, fork, operation, round - warmupRounds,
                        stats.operations(), stats.elapsedNanos(), stats.allocatedBytes(),
                        stats.gcCounts(), stats.gcMillis(), Long.toUnsignedString(stats.checksum()),
                        sampleOperation ? fixture.sampleQueryCategoryCounts : Map.of(),
                        sampleOperation ? fixture.sampleQueryScheduleSha256 : "",
                        "MEASURED", ""));
            }
        }
        return rows;
    }

    private static RoundStats executeRound(Fixture fixture, FixtureWorkload workload,
                                           Operation operation, boolean collect) throws Exception {
        return switch (operation) {
            case INITIAL_SCAN -> initialScanRound(fixture, collect);
            case COMPILE_REPLACE -> compileReplaceRound(workload, fixture, collect);
            case INITIAL_WORLD_FLUSH -> initialWorldFlushRound(fixture, collect);
            case WORLD_LOAD -> worldLoadRound(fixture, collect);
            case STORAGE_MUTATION_ONE -> storageMutationOneRound(fixture, collect);
            case STORAGE_MUTATION_BATCH_256 -> storageMutationBatchRound(fixture, collect);
            case DIRTY_ONE -> dirtyOneRound(fixture, collect);
            case DIRTY_BATCH -> dirtyBatchRound(fixture, collect);
            case DIRTY_NO_CHANGE -> dirtyNoChangeRound(fixture, collect);
            case IDLE_FLUSH -> idleFlushRound(fixture, collect);
            case INDEX_SAMPLE -> indexSampleRound(workload, collect);
            case INDEX_STANDING_SAMPLE -> indexStandingSampleRound(workload, collect);
            case WORLD_SAMPLE -> worldSampleRound(workload, collect);
            case STANDING_SAMPLE -> standingSampleRound(workload, collect);
            case UNLOAD_RELOAD -> unloadReloadRound(fixture, collect);
        };
    }

    private static RoundStats initialScanRound(Fixture fixture, boolean collect) throws Exception {
        List<FloorChunkSources> scanners = new ArrayList<>(fixture.chunks.size());
        List<ChunkData> chunks = new ArrayList<>(fixture.chunks.values());
        RoundClock clock = new RoundClock(collect);
        clock.begin();
        for (ChunkData chunk : chunks) {
            scanners.add(FloorChunkSources.scanExisting(chunk.key.x(), chunk.key.z(),
                    () -> chunk.storage, BUILTIN));
        }
        long elapsed = clock.end();
        RoundStats timed = clock.finish(1, elapsed, 0L, clock.allocatedBytes());
        FloorFieldIndex consumeIndex = new FloorFieldIndex();
        for (int i = 0; i < scanners.size(); i++) {
            ChunkData chunk = chunks.get(i);
            consumeIndex.replaceChunk(chunk.key, chunk.owner, scanners.get(i).snapshot());
        }
        long checksum = consumeIndexSample(consumeIndex, fixture.sampleQueries);
        consumeIndex.clear();
        return withChecksum(timed, checksum);
    }

    private static RoundStats compileReplaceRound(FixtureWorkload workload, Fixture fixture,
                                                   boolean collect) throws Exception {
        FloorFieldIndex index = new FloorFieldIndex();
        for (SourceBatch batch : workload.sourceBatches) {
            index.removeChunk(batch.key(), batch.owner());
        }
        RoundClock clock = new RoundClock(collect);
        clock.begin();
        for (SourceBatch batch : workload.sourceBatches) {
            index.replaceChunk(batch.key(), batch.owner(), batch.sources());
        }
        long elapsed = clock.end();
        RoundStats timed = clock.finish(workload.sourceBatches.size(), elapsed, 0L, clock.allocatedBytes());
        long checksum = consumeIndexSample(index, fixture.sampleQueries);
        index.clear();
        return withChecksum(timed, checksum);
    }

    private static RoundStats initialWorldFlushRound(Fixture fixture, boolean collect) throws Exception {
        PreparedWorld world = prepareWorld(fixture, true);
        Throwable primaryFailure = null;
        try {
            RoundClock clock = new RoundClock(collect);
            clock.begin();
            world.fields.flush(false);
            long elapsed = clock.end();
            RoundStats timed = clock.finish(1, elapsed, 0L, clock.allocatedBytes());
            long checksum = consumeWorldSample(world.fields, fixture.sampleQueries);
            return withChecksum(timed, checksum);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, world.fields::close);
        }
    }

    private static RoundStats worldLoadRound(Fixture fixture, boolean collect) throws Exception {
        WorldFields fields = new WorldFields();
        Throwable primaryFailure = null;
        try {
            RoundClock clock = new RoundClock(collect);
            clock.begin();
            for (ChunkData chunk : fixture.chunks.values()) {
                fields.load(chunk.key, chunk.owner, reader(chunk), false);
            }
            long elapsed = clock.end();
            RoundStats timed = clock.finish(1, elapsed, 0L, clock.allocatedBytes());
            long checksum = consumeWorldSample(fields, fixture.sampleQueries);
            return withChecksum(timed, checksum);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, fields::close);
        }
    }

    private static RoundStats dirtyOneRound(Fixture fixture, boolean collect) throws Exception {
        DirtyCellRef target = firstPhysicalCell(fixture);
        ChunkData chunk = target.chunk();
        DirtyCell cell = target.cell();
        ExtendedBlockStorage section = chunk.storage[cell.y() >> 4];
        IBlockState original = section.get(cell.x() & 15, cell.y() & 15, cell.z() & 15);
        IBlockState source = floorState(Face.UP, Kind.GRAVITY);
        IBlockState alternate = floorState(Face.EAST, Kind.GRAVITY);
        PreparedWorld world = prepareWorld(fixture, false);
        long elapsed = 0L;
        long allocated = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int i = 0; i < DIRTY_OPERATIONS; i++) {
                section.set(cell.x() & 15, cell.y() & 15, cell.z() & 15,
                        (i & 1) == 0 ? alternate : source);
                long before = clock.allocated();
                long start = System.nanoTime();
                world.fields.markDirty(chunk.key, chunk.owner, cell.x(), cell.y(), cell.z());
                world.fields.flush(false);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats timed = clock.finish(DIRTY_OPERATIONS, elapsed, 0L, allocated);
            Sample sample = world.fields.sample(new Vec3(cell.x() + 0.5, cell.y() + 1.5,
                    cell.z() + 0.5), 1.0, false).orElseThrow();
            assertNonzero(sample, "dirty_one postcondition");
            return withChecksum(timed, consume(sample));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure,
                    () -> section.set(cell.x() & 15, cell.y() & 15, cell.z() & 15, original),
                    world.fields::close);
        }
    }

    private static RoundStats storageMutationOneRound(Fixture fixture, boolean collect) throws Exception {
        DirtyCellRef target = firstPhysicalCell(fixture);
        DirtyCell cell = target.cell();
        ExtendedBlockStorage section = target.chunk().storage[cell.y() >> 4];
        IBlockState original = section.get(cell.x() & 15, cell.y() & 15, cell.z() & 15);
        IBlockState up = floorState(Face.UP, Kind.GRAVITY);
        IBlockState east = floorState(Face.EAST, Kind.GRAVITY);
        long elapsed = 0L;
        long allocated = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int iteration = 0; iteration < DIRTY_OPERATIONS; iteration++) {
                long before = clock.allocated();
                long start = System.nanoTime();
                section.set(cell.x() & 15, cell.y() & 15, cell.z() & 15,
                        (iteration & 1) == 0 ? east : up);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats timed = clock.finish(DIRTY_OPERATIONS, elapsed, 0L, allocated);
            return withChecksum(timed, System.identityHashCode(
                    section.get(cell.x() & 15, cell.y() & 15, cell.z() & 15)));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure,
                    () -> section.set(cell.x() & 15, cell.y() & 15, cell.z() & 15, original));
        }
    }

    private static RoundStats storageMutationBatchRound(Fixture fixture, boolean collect) throws Exception {
        List<DirtyCellRef> cells = createDirtyBatchCells(fixture);
        IBlockState[] originals = snapshotStates(cells);
        IBlockState up = floorState(Face.UP, Kind.GRAVITY);
        IBlockState east = floorState(Face.EAST, Kind.GRAVITY);
        long elapsed = 0L;
        long allocated = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int iteration = 0; iteration < DIRTY_OPERATIONS; iteration++) {
                IBlockState next = (iteration & 1) == 0 ? east : up;
                long before = clock.allocated();
                long start = System.nanoTime();
                for (DirtyCellRef target : cells) set(target, next);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats timed = clock.finish(DIRTY_OPERATIONS, elapsed, 0L, allocated);
            long checksum = cells.size();
            for (DirtyCellRef target : cells) {
                checksum = mix(checksum, System.identityHashCode(state(target)));
            }
            return withChecksum(timed, checksum);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, () -> restoreStates(cells, originals));
        }
    }

    private static RoundStats dirtyBatchRound(Fixture fixture, boolean collect) throws Exception {
        List<DirtyCellRef> cells = createDirtyBatchCells(fixture);
        IBlockState[] originals = snapshotStates(cells);
        IBlockState up = floorState(Face.UP, Kind.GRAVITY);
        IBlockState east = floorState(Face.EAST, Kind.GRAVITY);
        PreparedWorld world = prepareWorld(fixture, false);
        long elapsed = 0L;
        long allocated = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int iteration = 0; iteration < DIRTY_OPERATIONS; iteration++) {
                IBlockState next = (iteration & 1) == 0 ? east : up;
                for (DirtyCellRef target : cells) set(target, next);
                long before = clock.allocated();
                long start = System.nanoTime();
                for (DirtyCellRef target : cells) {
                    DirtyCell cell = target.cell();
                    world.fields.markDirty(target.chunk().key, target.chunk().owner,
                            cell.x(), cell.y(), cell.z());
                }
                world.fields.flush(false);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats timed = clock.finish(DIRTY_OPERATIONS, elapsed, 0L, allocated);
            DirtyCell cell = cells.get(0).cell();
            Sample sample = world.fields.sample(new Vec3(cell.x() + 0.5, cell.y() + 1.5,
                    cell.z() + 0.5), 1.0, false).orElseThrow();
            assertNonzero(sample, "dirty_batch postcondition");
            return withChecksum(timed, mix(consume(sample), cells.size()));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, () -> restoreStates(cells, originals), world.fields::close);
        }
    }

    private static RoundStats dirtyNoChangeRound(Fixture fixture, boolean collect) throws Exception {
        DirtyCellRef target = firstPhysicalCell(fixture);
        ChunkData chunk = target.chunk();
        DirtyCell cell = target.cell();
        IBlockState unchanged = chunk.storage[cell.y() >> 4].get(cell.x() & 15, cell.y() & 15, cell.z() & 15);
        PreparedWorld world = prepareWorld(fixture, false);
        long elapsed = 0L;
        long allocated = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int i = 0; i < DIRTY_OPERATIONS; i++) {
                chunk.storage[cell.y() >> 4].set(cell.x() & 15, cell.y() & 15, cell.z() & 15, unchanged);
                long before = clock.allocated();
                long start = System.nanoTime();
                world.fields.markDirty(chunk.key, chunk.owner, cell.x(), cell.y(), cell.z());
                world.fields.flush(false);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats stats = clock.finish(DIRTY_OPERATIONS, elapsed, 0L, allocated);
            Sample sample = world.fields.sample(new Vec3(cell.x() + 0.5, cell.y() + 1.5,
                    cell.z() + 0.5), 1.0, false).orElseThrow();
            return withChecksum(stats, consume(sample));
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, world.fields::close);
        }
    }

    private static RoundStats idleFlushRound(Fixture fixture, boolean collect) throws Exception {
        PreparedWorld world = prepareWorld(fixture, false);
        RoundClock clock = new RoundClock(collect);
        long elapsed = 0L;
        long allocated = 0L;
        Throwable primaryFailure = null;
        try {
            clock.beginRound();
            for (int i = 0; i < SAMPLE_OPERATIONS; i++) {
                long before = clock.allocated();
                long start = System.nanoTime();
                world.fields.flush(false);
                elapsed += System.nanoTime() - start;
                allocated += clock.allocatedDelta(before);
            }
            RoundStats stats = clock.finish(SAMPLE_OPERATIONS, elapsed, 0L, allocated);
            Query query = fixture.sampleQueries.get(0);
            long checksum = consume(world.fields.sample(query.position(), query.response(), false).orElseThrow());
            checksum ^= consume(world.fields.sampleForStanding(query.position(), query.response(), false).orElseThrow());
            return withChecksum(stats, checksum);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, world.fields::close);
        }
    }

    private static RoundStats indexSampleRound(FixtureWorkload workload, boolean collect) {
        RoundClock clock = new RoundClock(collect);
        long checksum = 0L;
        long allocated = 0L;
        clock.beginRound();
        for (int i = 0; i < SAMPLE_OPERATIONS; i++) {
            Query query = workload.fixture.sampleQueries.get(i);
            long allocationBefore = clock.allocated();
            long start = System.nanoTime();
            Sample sample = workload.index.sample(query.position(), query.response());
            long elapsed = System.nanoTime() - start;
            allocated += clock.allocatedDelta(allocationBefore);
            clock.addElapsed(elapsed);
            checksum ^= consume(sample);
        }
        return clock.finish(SAMPLE_OPERATIONS, clock.elapsedNanos(), checksum, allocated);
    }

    private static RoundStats indexStandingSampleRound(FixtureWorkload workload, boolean collect) {
        RoundClock clock = new RoundClock(collect);
        long checksum = 0L;
        long allocated = 0L;
        clock.beginRound();
        for (int i = 0; i < SAMPLE_OPERATIONS; i++) {
            Query query = workload.fixture.sampleQueries.get(i);
            long allocationBefore = clock.allocated();
            long start = System.nanoTime();
            StandingSample sample = workload.index.sampleForStanding(query.position(), query.response());
            long elapsed = System.nanoTime() - start;
            allocated += clock.allocatedDelta(allocationBefore);
            clock.addElapsed(elapsed);
            checksum ^= consume(sample);
        }
        return clock.finish(SAMPLE_OPERATIONS, clock.elapsedNanos(), checksum, allocated);
    }

    private static RoundStats worldSampleRound(FixtureWorkload workload, boolean collect) {
        PreparedWorld world = workload.world;
        RoundClock clock = new RoundClock(collect);
        long checksum = 0L;
        long allocated = 0L;
        clock.beginRound();
        for (int i = 0; i < SAMPLE_OPERATIONS; i++) {
            Query query = workload.fixture.sampleQueries.get(i);
            long allocationBefore = clock.allocated();
            long start = System.nanoTime();
            Optional<Sample> sample = world.fields.sample(query.position(), query.response(), false);
            long elapsed = System.nanoTime() - start;
            allocated += clock.allocatedDelta(allocationBefore);
            clock.addElapsed(elapsed);
            checksum ^= consumeSampleOptional(sample, 0x6a09e667f3bcc909L);
        }
        return clock.finish(SAMPLE_OPERATIONS, clock.elapsedNanos(), checksum, allocated);
    }

    private static RoundStats standingSampleRound(FixtureWorkload workload, boolean collect) {
        PreparedWorld world = workload.world;
        RoundClock clock = new RoundClock(collect);
        long checksum = 0L;
        long allocated = 0L;
        clock.beginRound();
        for (int i = 0; i < SAMPLE_OPERATIONS; i++) {
            Query query = workload.fixture.sampleQueries.get(i);
            long allocationBefore = clock.allocated();
            long start = System.nanoTime();
            Optional<StandingSample> sample =
                    world.fields.sampleForStanding(query.position(), query.response(), false);
            long elapsed = System.nanoTime() - start;
            allocated += clock.allocatedDelta(allocationBefore);
            clock.addElapsed(elapsed);
            checksum ^= consumeStandingOptional(sample, 0xbb67ae8584caa73bL);
        }
        return clock.finish(SAMPLE_OPERATIONS, clock.elapsedNanos(), checksum, allocated);
    }

    private static RoundStats unloadReloadRound(Fixture fixture, boolean collect) throws Exception {
        WorldFields fields = new WorldFields();
        Map<ChunkData, Object> originalOwners = new LinkedHashMap<>();
        long elapsed = 0L;
        long allocated = 0L;
        long checksum = 0L;
        RoundClock clock = new RoundClock(collect);
        Throwable primaryFailure = null;
        try {
            for (ChunkData chunk : fixture.chunks.values()) {
                originalOwners.put(chunk, chunk.owner);
                fields.load(chunk.key, chunk.owner, reader(chunk), true);
            }
            fields.flush(false);
            clock.beginRound();
            long beforeUnload = clock.allocated();
            long unloadStart = System.nanoTime();
            for (ChunkData chunk : fixture.chunks.values()) {
                fields.unload(chunk.key, originalOwners.get(chunk));
            }
            elapsed += System.nanoTime() - unloadStart;
            allocated += clock.allocatedDelta(beforeUnload);
            clock.checkpointGc();

            ProbeSource probe = fixture.probeSources.get(0);
            Vec3 probePosition = facePoint(probe, 0.0);
            Sample emptyAfterUnload = fields.sample(probePosition, 1.0, false).orElseThrow();
            assertZero(emptyAfterUnload, "unload_reload fully-unloaded sample");
            checksum ^= consume(emptyAfterUnload);
            StandingSample emptyStanding = fields.sampleForStanding(probePosition, 1.0, false).orElseThrow();
            assertZero(emptyStanding, "unload_reload fully-unloaded standing sample");
            checksum ^= consume(emptyStanding);
            clock.resetGcBaseline();

            long beforeLoad = clock.allocated();
            long loadStart = System.nanoTime();
            for (ChunkData chunk : fixture.chunks.values()) {
                Object newOwner = new Object();
                chunk.owner = newOwner;
                fields.load(chunk.key, newOwner, reader(chunk), true);
            }
            fields.flush(false);
            for (ChunkData chunk : fixture.chunks.values()) {
                fields.unload(chunk.key, originalOwners.get(chunk));
            }
            elapsed += System.nanoTime() - loadStart;
            allocated += clock.allocatedDelta(beforeLoad);
            RoundStats timed = clock.finish(1, elapsed, 0L, allocated);

            Sample restored = fields.sample(probePosition, 1.0, false).orElseThrow();
            assertNonzero(restored, "unload_reload post-reload sample");
            checksum ^= consume(restored);
            checksum ^= consume(fields.sampleForStanding(probePosition, 1.0, false).orElseThrow());
            Vec3 outsideRange = new Vec3(fixture.maxWorldX + 64.0, 17.5, fixture.maxWorldZ + 64.0);
            Sample zeroRange = fields.sample(outsideRange, 1.0, false).orElseThrow();
            assertZero(zeroRange, "unload_reload outside-range sample");
            checksum ^= consume(zeroRange);

            ChunkData countChunk = fixture.firstDirtyChunk();
            FloorChunkSources reloadedScanner = FloorChunkSources.scanExisting(countChunk.key.x(),
                    countChunk.key.z(), () -> countChunk.storage, BUILTIN);
            long actualSourceCount = reloadedScanner.snapshot().size();
            if (actualSourceCount != countChunk.expectedEmitterCount) {
                throw new IllegalStateException("unload_reload scanner source count mismatch for "
                        + countChunk.key + " expected=" + countChunk.expectedEmitterCount
                        + " actual=" + actualSourceCount);
            }
            checksum = mix(checksum, actualSourceCount);
            return withChecksum(timed, checksum);
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, () -> {
                for (Map.Entry<ChunkData, Object> entry : originalOwners.entrySet()) {
                    entry.getKey().owner = entry.getValue();
                }
            }, fields::close);
        }
    }

    private static FixtureWorkload prepareWorkload(Fixture fixture) throws Exception {
        List<SourceBatch> batches = sourceBatches(fixture);
        FloorFieldIndex index = buildIndex(batches);
        try {
            PreparedWorld world = fixture.scannerApplicable ? prepareWorld(fixture, false) : null;
            return new FixtureWorkload(fixture, batches, index, world);
        } catch (Throwable failure) {
            try {
                index.clear();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static PreparedWorld prepareWorld(Fixture fixture, boolean deferred) {
        if (!fixture.scannerApplicable) {
            throw new IllegalArgumentException("public mixed-profile fixture has no real scanner/world profile writer");
        }
        WorldFields fields = new WorldFields();
        try {
            for (ChunkData chunk : fixture.chunks.values()) {
                fields.load(chunk.key, chunk.owner, reader(chunk), true);
            }
            fields.flush(deferred);
            return new PreparedWorld(fields);
        } catch (Throwable failure) {
            try {
                fields.close();
            } catch (Throwable cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static WorldFields loadRealWorldFields(Fixture fixture) {
        return prepareWorld(fixture, false).fields;
    }

    static java.util.function.Supplier<FloorChunkSources> reader(ChunkData chunk) {
        int chunkX = chunk.key.x();
        int chunkZ = chunk.key.z();
        ExtendedBlockStorage[] storage = chunk.storage;
        return () -> FloorChunkSources.scanExisting(chunkX, chunkZ, () -> storage, BUILTIN);
    }

    private static List<SourceBatch> sourceBatches(Fixture fixture) {
        List<SourceBatch> result = new ArrayList<>(fixture.chunks.size());
        if (fixture.id == FixtureId.PUBLIC_MIXED_PROFILES) {
            for (ChunkData chunk : fixture.chunks.values()) {
                result.add(new SourceBatch(chunk.key, chunk.owner, List.copyOf(chunk.publicSources)));
            }
            return List.copyOf(result);
        }
        for (ChunkData chunk : fixture.chunks.values()) {
            FloorChunkSources scanner = FloorChunkSources.scanExisting(chunk.key.x(), chunk.key.z(),
                    () -> chunk.storage, BUILTIN);
            result.add(new SourceBatch(chunk.key, chunk.owner, scanner.snapshot()));
        }
        return List.copyOf(result);
    }

    private static FloorFieldIndex buildIndex(List<SourceBatch> batches) {
        FloorFieldIndex index = new FloorFieldIndex();
        for (SourceBatch batch : batches) {
            index.replaceChunk(batch.key(), batch.owner(), batch.sources());
        }
        return index;
    }

    private static TraceSummary writeEquivalenceTrace(Fixture fixture, FixtureWorkload workload,
                                                       Path output) throws Exception {
        MessageDigest digest = sha256Digest();
        long rows = 0L;
        try (DataOutputStream data = new DataOutputStream(new BufferedOutputStream(
                new DigestOutputStream(new GZIPOutputStream(Files.newOutputStream(output)), digest)))) {
            data.writeInt(TRACE_MAGIC);
            data.writeInt(fixture.id.ordinal());
            rows = forEachProbe(fixture, (position, response) -> {
                writeProbeRow(data, workload, 0, position, response);
            });
            if (fixture.scannerApplicable) {
                ChunkData target = fixture.firstDirtyChunk();
                DirtyCell cell = fixture.firstDirtyCell();
                IBlockState original = target.storage[cell.y() >> 4].get(cell.x() & 15,
                        cell.y() & 15, cell.z() & 15);
                IBlockState[] states = {
                        Blocks.AIR.getDefaultState(),
                        FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getStateFromMeta(EnumFacing.UP.getIndex()),
                        FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getStateFromMeta(EnumFacing.WEST.getIndex()),
                        FloorBlocks.MAGNETIC_FLOOR.getDefaultState()
                                .withProperty(DirectionalFloorBlock.FACING, EnumFacing.EAST),
                        Blocks.AIR.getDefaultState(),
                        original
                };
                int phase = 1;
                for (IBlockState state : states) {
                    target.storage[cell.y() >> 4].set(cell.x() & 15, cell.y() & 15, cell.z() & 15, state);
                    workload.world.fields.markDirty(target.key, target.owner, cell.x(), cell.y(), cell.z());
                    workload.world.fields.flush(false);
                    refreshIndex(workload.index, fixture);
                    rows += writeLifecycleTraceRows(data, workload, fixture, phase++);
                }
                Object oldOwner = target.owner;
                workload.world.fields.unload(target.key, oldOwner);
                workload.index.removeChunk(target.key, oldOwner);
                rows += writeLifecycleTraceRows(data, workload, fixture, phase++);
                Object newOwner = new Object();
                workload.world.fields.load(target.key, newOwner, reader(target), true);
                workload.world.fields.flush(false);
                workload.index.replaceChunk(target.key, newOwner,
                        FloorChunkSources.scanExisting(target.key.x(), target.key.z(),
                                () -> target.storage, BUILTIN).snapshot());
                target.owner = newOwner;
                IBlockState stableState = target.storage[cell.y() >> 4].get(
                        cell.x() & 15, cell.y() & 15, cell.z() & 15);
                for (int repeat = 0; repeat < 3; repeat++) {
                    workload.world.fields.markDirty(target.key, target.owner, cell.x(), cell.y(), cell.z());
                    workload.world.fields.flush(false);
                }
                rows += writeLifecycleTraceRows(data, workload, fixture, phase++);
                if (!target.storage[cell.y() >> 4].get(cell.x() & 15, cell.y() & 15,
                        cell.z() & 15).equals(stableState)) {
                    throw new IllegalStateException("NoChange trace mutated its retained fixture state");
                }
                if (fixture.id == FixtureId.DIRTY_BATCH_CROSS_SECTION) {
                    rows += writeCrossSectionBatchTrace(data, workload, fixture, phase, phase + 1);
                    phase += 2;
                    rows += writeLifecycleTraceRows(data, workload, fixture, phase++);
                }
            }
        }
        String rawHash = hex(digest.digest());
        String compressedHash = sha256(output);
        return new TraceSummary(output, rows, rawHash, compressedHash);
    }

    private static long writeCrossSectionBatchTrace(DataOutputStream data, FixtureWorkload workload,
                                                    Fixture fixture, int changedPhase,
                                                    int noChangePhase) throws Exception {
        List<DirtyCellRef> cells = createDirtyBatchCells(fixture);
        if (cells.size() != 256) {
            throw new IllegalStateException("cross-section trace requires exactly 256 real cells");
        }
        IBlockState[] originalStates = snapshotStates(cells);
        List<ProbeSource> probes = new ArrayList<>(cells.size());
        Throwable primaryFailure = null;
        try {
            for (int i = 0; i < cells.size(); i++) {
                DirtyCellRef target = cells.get(i);
                DirtyCell cell = target.cell();
                Face face = ALL_FACES[(i + 1) % ALL_FACES.length];
                Kind kind = (i & 1) == 0 ? Kind.GRAVITY : Kind.MAGNETIC;
                set(target, floorState(face, kind));
                probes.add(new ProbeSource(cell.x(), cell.y(), cell.z(), face, kind, BUILTIN));
                workload.world.fields.markDirty(target.chunk().key, target.chunk().owner,
                        cell.x(), cell.y(), cell.z());
            }
            workload.world.fields.flush(false);
            refreshIndex(workload.index, fixture);
            long rows = writeBatchProbeRows(data, workload, probes, changedPhase);

            for (DirtyCellRef target : cells) {
                DirtyCell cell = target.cell();
                workload.world.fields.markDirty(target.chunk().key, target.chunk().owner,
                        cell.x(), cell.y(), cell.z());
            }
            workload.world.fields.flush(false);
            refreshIndex(workload.index, fixture);
            rows += writeBatchProbeRows(data, workload, probes, noChangePhase);
            return rows;
        } catch (Throwable failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            cleanupAfter(primaryFailure, () -> {
                restoreStates(cells, originalStates);
                for (DirtyCellRef target : cells) {
                    DirtyCell cell = target.cell();
                    workload.world.fields.markDirty(target.chunk().key, target.chunk().owner,
                            cell.x(), cell.y(), cell.z());
                }
                workload.world.fields.flush(false);
                refreshIndex(workload.index, fixture);
            });
        }
    }

    private static long writeBatchProbeRows(DataOutputStream data, FixtureWorkload workload,
                                            List<ProbeSource> probes, int phase) throws IOException {
        long rows = 0L;
        for (ProbeSource source : probes) {
            double[] distances = {0.0, source.profile().plateau(),
                    Math.nextDown(source.profile().range()), source.profile().range(),
                    Math.nextUp(source.profile().range())};
            for (double distance : distances) {
                Vec3 point = facePoint(source, distance);
                for (double response : RESPONSES) {
                    writeProbeRow(data, workload, phase, point, response);
                    rows++;
                }
            }
            for (Vec3 tangent : tangentEdges(source)) {
                writeProbeRow(data, workload, phase, tangent, 1.0);
                rows++;
            }
        }
        return rows;
    }

    private static long forEachProbe(Fixture fixture, ProbeConsumer consumer) throws Exception {
        long rows = 0L;
        long gridIndex = 0L;
        for (GridProbe grid : fixture.gridProbes) {
            Vec3 point = new Vec3(grid.x() + 0.5, grid.y() + 1.5, grid.z() + 0.5);
            consumer.accept(point, RESPONSES[(int) (gridIndex++ % RESPONSES.length)]);
            rows++;
        }
        Set<String> visited = new LinkedHashSet<>();
        for (ProbeSource source : fixture.probeSources) {
            String key = source.face() + "|" + source.kind() + "|" + source.profile();
            if (!visited.add(key)) {
                continue;
            }
            double[] distances = {0.0, source.profile().plateau(),
                    Math.nextDown(source.profile().range()), source.profile().range(),
                    Math.nextUp(source.profile().range())};
            for (double distance : distances) {
                Vec3 point = facePoint(source, distance);
                for (double response : RESPONSES) {
                    consumer.accept(point, response);
                    rows++;
                }
            }
            for (Vec3 tangent : tangentEdges(source)) {
                consumer.accept(tangent, 1.0);
                rows++;
            }
        }
        ProbeSource anchor = fixture.probeSources.getFirst();
        for (AxisBoundary axisBoundary : chunkBoundaries(fixture)) {
            double boundary = axisBoundary.value();
            double below = Math.nextDown(boundary);
            double above = Math.nextUp(boundary);
            for (double edge : new double[]{below, boundary, above}) {
                Vec3 point = axisBoundary.axis() == 0
                        ? new Vec3(edge, anchor.y() + 0.5, anchor.z() + 0.5)
                        : new Vec3(anchor.x() + 0.5, anchor.y() + 0.5, edge);
                consumer.accept(point, RESPONSES[(int) (gridIndex++ % RESPONSES.length)]);
                rows++;
            }
        }
        Vec3 sectionAnchor = sectionBoundaryAnchor(fixture);
        for (int y : fixture.sectionBoundaries) {
            for (double edge : new double[]{Math.nextDown((double) y), y, Math.nextUp((double) y)}) {
                consumer.accept(new Vec3(sectionAnchor.x(), edge, sectionAnchor.z()), 1.0);
                rows++;
            }
        }
        return rows;
    }

    private static Vec3 facePoint(ProbeSource source, double distance) {
        double x = source.x() + 0.5;
        double y = source.y() + 0.5;
        double z = source.z() + 0.5;
        return switch (source.face()) {
            case DOWN -> new Vec3(x, source.y() - distance, z);
            case UP -> new Vec3(x, source.y() + 1.0 + distance, z);
            case NORTH -> new Vec3(x, y, source.z() - distance);
            case SOUTH -> new Vec3(x, y, source.z() + 1.0 + distance);
            case WEST -> new Vec3(source.x() - distance, y, z);
            case EAST -> new Vec3(source.x() + 1.0 + distance, y, z);
        };
    }

    private static List<Vec3> tangentEdges(ProbeSource source) {
        double x = source.x() + 0.5;
        double y = source.y() + 0.5;
        double z = source.z() + 0.5;
        double xEnd = source.x() + 1.0;
        double yEnd = source.y() + 1.0;
        double zEnd = source.z() + 1.0;
        return switch (source.face()) {
            case DOWN, UP -> List.of(new Vec3(source.x(), y, z), new Vec3(Math.nextDown(xEnd), y, z),
                    new Vec3(xEnd, y, z), new Vec3(x, y, source.z()), new Vec3(x, y, Math.nextDown(zEnd)),
                    new Vec3(x, y, zEnd));
            case NORTH, SOUTH -> List.of(new Vec3(source.x(), y, z), new Vec3(Math.nextDown(xEnd), y, z),
                    new Vec3(xEnd, y, z), new Vec3(x, source.y(), z), new Vec3(x, Math.nextDown(yEnd), z),
                    new Vec3(x, yEnd, z));
            case WEST, EAST -> List.of(new Vec3(x, source.y(), z), new Vec3(x, Math.nextDown(yEnd), z),
                    new Vec3(x, yEnd, z), new Vec3(x, y, source.z()), new Vec3(x, y, Math.nextDown(zEnd)),
                    new Vec3(x, y, zEnd));
        };
    }

    private static long writeLifecycleTraceRows(DataOutputStream data, FixtureWorkload workload,
                                                Fixture fixture, int phase) throws Exception {
        long count = 0L;
        int limit = Math.min(256, fixture.sampleQueries.size());
        for (int i = 0; i < limit; i++) {
            Query query = fixture.sampleQueries.get(i);
            writeProbeRow(data, workload, phase, query.position(), query.response());
            count++;
        }
        Vec3 local = new Vec3(0.5, 17.5, 0.5);
        for (double response : RESPONSES) {
            writeProbeRow(data, workload, phase, local, response);
            count++;
        }
        return count;
    }

    private static void refreshIndex(FloorFieldIndex index, Fixture fixture) {
        for (ChunkData chunk : fixture.chunks.values()) {
            FloorChunkSources scanner = FloorChunkSources.scanExisting(chunk.key.x(), chunk.key.z(),
                    () -> chunk.storage, BUILTIN);
            index.replaceChunk(chunk.key, chunk.owner, scanner.snapshot());
        }
    }

    private static void writeProbeRow(DataOutputStream data, FixtureWorkload workload, int phase,
                                      Vec3 point, double response) throws IOException {
        data.writeInt(phase);
        data.writeLong(Double.doubleToRawLongBits(point.x()));
        data.writeLong(Double.doubleToRawLongBits(point.y()));
        data.writeLong(Double.doubleToRawLongBits(point.z()));
        data.writeLong(Double.doubleToRawLongBits(response));
        writeSample(data, Optional.of(workload.index.sample(point, response)));
        writeStanding(data, Optional.of(workload.index.sampleForStanding(point, response)));
        if (workload.world == null) {
            writeSample(data, Optional.empty());
            writeStanding(data, Optional.empty());
        } else {
            writeSample(data, workload.world.fields.sample(point, response, false));
            writeStanding(data, workload.world.fields.sampleForStanding(point, response, false));
        }
    }

    private static void writeSample(DataOutputStream data, Optional<Sample> optional) throws IOException {
        data.writeBoolean(optional.isPresent());
        if (optional.isEmpty()) {
            for (int i = 0; i < 6; i++) data.writeLong(0L);
            data.writeInt(0);
            data.writeInt(0);
            return;
        }
        Sample sample = optional.orElseThrow();
        writeVector(data, sample.gravityAccel());
        writeVector(data, sample.magneticAccel());
        data.writeInt(faceMask(sample.effectiveGravityFaces()));
        data.writeInt(faceMask(sample.effectiveMagneticFaces()));
    }

    private static void writeStanding(DataOutputStream data, Optional<StandingSample> optional) throws IOException {
        data.writeBoolean(optional.isPresent());
        if (optional.isEmpty()) {
            for (int i = 0; i < 6; i++) data.writeLong(0L);
            data.writeInt(0);
            data.writeInt(0);
            for (int i = 0; i < 12; i++) {
                data.writeBoolean(false);
                data.writeLong(0L);
            }
            return;
        }
        StandingSample sample = optional.orElseThrow();
        writeVector(data, sample.forces().gravityAccel());
        writeVector(data, sample.forces().magneticAccel());
        data.writeInt(faceMask(sample.forces().effectiveGravityFaces()));
        data.writeInt(faceMask(sample.forces().effectiveMagneticFaces()));
        writeStrengths(data, sample.gravityStrengths());
        writeStrengths(data, sample.magneticStrengths());
    }

    private static void writeStrengths(DataOutputStream data, Map<Face, Double> strengths) throws IOException {
        for (Face face : ALL_FACES) {
            Double value = strengths.get(face);
            data.writeBoolean(value != null);
            data.writeLong(value == null ? 0L : Double.doubleToRawLongBits(value));
        }
    }

    private static void writeVector(DataOutputStream data, Vec3 vector) throws IOException {
        data.writeLong(Double.doubleToRawLongBits(vector.x()));
        data.writeLong(Double.doubleToRawLongBits(vector.y()));
        data.writeLong(Double.doubleToRawLongBits(vector.z()));
    }

    private static int faceMask(Set<Face> faces) {
        int mask = 0;
        for (Face face : ALL_FACES) {
            if (faces.contains(face)) mask |= 1 << face.ordinal();
        }
        return mask;
    }

    public static void verifyEquivalentTrace(TraceSummary baseline, TraceSummary candidate) throws IOException {
        if (baseline.rows() == candidate.rows() && baseline.rawSha256().equals(candidate.rawSha256())) {
            return;
        }
        long byteOffset = firstDifferentUncompressedByte(baseline.path(), candidate.path());
        long row = byteOffset < 8L ? -1L : (byteOffset - 8L) / TRACE_RECORD_BYTES;
        throw new IllegalStateException("scalar/candidate semantic trace mismatch rows="
                + baseline.rows() + "/" + candidate.rows() + " sha256="
                + baseline.rawSha256() + "/" + candidate.rawSha256()
                + " firstDifferingUncompressedByte=" + byteOffset + " row=" + row
                + " baselineTrace=" + baseline.path() + " candidateTrace=" + candidate.path());
    }

    private static long firstDifferentUncompressedByte(Path left, Path right) throws IOException {
        try (DataInputStream a = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(left))));
             DataInputStream b = new DataInputStream(new BufferedInputStream(
                     new GZIPInputStream(Files.newInputStream(right))))) {
            long offset = 0L;
            while (true) {
                int va = a.read();
                int vb = b.read();
                if (va != vb) return offset;
                if (va < 0) return -1L;
                offset++;
            }
        }
    }

    private static void writeTraceReady(Path path, Variant variant, FixtureId fixture,
                                        long fork, TraceSummary trace) throws IOException {
        writeUtf8Atomically(path, "protocol=" + PROTOCOL + "\nvariant=" + variant.name().toLowerCase()
                + "\nfixture=" + fixture.name().toLowerCase() + "\nfork=" + fork
                + "\nrows=" + trace.rows() + "\nrawSha256=" + trace.rawSha256()
                + "\ncompressedSha256=" + trace.compressedSha256() + "\ntrace=" + trace.path() + "\n");
    }

    private static void writeFixtureManifest(Path path, Fixture fixture, TraceSummary trace) throws IOException {
        long physical = 0L;
        long emitters = 0L;
        Set<String> profiles = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        for (ChunkData chunk : fixture.chunks.values()) {
            physical = Math.addExact(physical, chunk.expectedPhysicalCount);
            emitters = Math.addExact(emitters, chunk.expectedEmitterCount);
            for (int sectionIndex = 0; sectionIndex < chunk.storage.length; sectionIndex++) {
                if (chunk.storage[sectionIndex] != null) {
                    sections.add(Integer.toString(sectionIndex));
                }
            }
        }
        for (ProbeSource source : fixture.probeSources) {
            profiles.add(source.kind() + "|" + source.profile().strength() + "|"
                    + source.profile().plateau() + "|" + source.profile().range());
        }
        writeUtf8(path, "{\n"
                + "  \"fixture\": " + quote(fixture.id.name().toLowerCase()) + ",\n"
                + "  \"seed\": " + quote("0x" + Long.toHexString(fixture.seed)) + ",\n"
                + "  \"chunkWidth\": " + fixture.width + ",\n"
                + "  \"chunkCount\": " + fixture.chunks.size() + ",\n"
                + "  \"physicalBlocks\": " + physical + ",\n"
                + "  \"emitters\": " + emitters + ",\n"
                + "  \"dirtyBatchCrossSectionCells\": "
                + (fixture.id == FixtureId.DIRTY_BATCH_CROSS_SECTION ? 256 : 0) + ",\n"
                + "  \"storageSectionCount\": " + sections.size() + ",\n"
                + "  \"profileKindGroups\": " + profiles.size() + ",\n"
                + "  \"sectionIndices\": " + jsonArray(new ArrayList<>(sections)) + ",\n"
                + "  \"multiLayerWideShape\": " + new Gson().toJson(multiLayerWideShape(fixture)) + ",\n"
                + "  \"sampleOperationsPerRound\": " + SAMPLE_OPERATIONS + ",\n"
                + "  \"sampleRawProbePoolCount\": " + fixture.sampleRawQueryCount + ",\n"
                + "  \"sampleQueryCount\": " + fixture.sampleQueries.size() + ",\n"
                + "  \"sampleQueryCategoryCountsPerRound\": " + mapJson(fixture.sampleQueryCategoryCounts) + ",\n"
                + "  \"sampleQueryScheduleSha256\": " + quote(fixture.sampleQueryScheduleSha256) + ",\n"
                + "  \"scannerApplicable\": " + fixture.scannerApplicable + ",\n"
                + "  \"equivalenceRows\": " + trace.rows() + ",\n"
                + "  \"equivalenceRawSha256\": " + quote(trace.rawSha256()) + "\n"
                + "}\n");
    }

    private static void validatePreflightReceipt(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("missing verifyFloorPerfCommonLinkage receipt: " + path);
        }
        try {
            Object decoded = new Gson().fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), Map.class);
            if (!(decoded instanceof Map<?, ?> receipt)
                    || !"PASS".equals(receipt.get("status"))
                    || !PROTOCOL.equals(receipt.get("protocol"))
                    || !(receipt.get("baseline") instanceof Map<?, ?> baseline)
                    || !(receipt.get("candidate") instanceof Map<?, ?> candidate)
                    || !"PASS".equals(baseline.get("status"))
                    || !"PASS".equals(candidate.get("status"))) {
                throw new IOException("common linkage receipt is not a matching baseline/candidate PASS: " + path);
            }
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IOException("could not parse common linkage receipt: " + path, malformed);
        }
    }

    private static void preserveFailureReports(Path output, Path equivalencePath, Path measurementsPath,
                                               List<EquivalenceResult> equivalence,
                                               List<String> measurements, List<HistogramResult> histograms,
                                               boolean candidateDiagnosticsReady, Throwable failure) {
        attemptReportWrite(failure, () -> writeEquivalenceReport(equivalencePath, equivalence, "NON_PASS", failure));
        attemptReportWrite(failure, () -> writeMeasurementLines(measurementsPath, measurements));
        attemptReportWrite(failure, () -> writeSummary(output.resolve("summary.csv"), measurements));
        attemptReportWrite(failure, () -> writeHistogramReport(output.resolve("retained.txt"), histograms,
                "NON_PASS: comparison terminated before all required evidence was collected."));
        attemptReportWrite(failure, () -> writeUtf8(output.resolve("histogram-manifest.json"),
                failureManifest(histograms, failure)));
        attemptReportWrite(failure, () -> writeUtf8(output.resolve("result.json"),
                failureResult(equivalence, measurements, histograms, candidateDiagnosticsReady, failure)));
        attemptReportWrite(failure, () -> {
            StringWriter buffer = new StringWriter();
            try (PrintWriter writer = new PrintWriter(buffer)) {
                failure.printStackTrace(writer);
            }
            writeUtf8(output.resolve("failure.txt"), buffer.toString());
        });
    }

    private static String failureManifest(List<HistogramResult> histograms, Throwable failure) {
        StringBuilder json = new StringBuilder("{\n  \"status\": \"NON_PASS\",\n  \"failureType\": ")
                .append(quote(failure.getClass().getName()))
                .append(",\n  \"failureMessage\": ").append(quote(String.valueOf(failure.getMessage())))
                .append(",\n  \"capturedHistograms\": [");
        for (int i = 0; i < histograms.size(); i++) {
            HistogramResult result = histograms.get(i);
            if (i != 0) json.append(',');
            json.append("\n    {\"variant\": ").append(quote(result.variant().name().toLowerCase()))
                    .append(", \"fixture\": ").append(quote(result.fixture().name().toLowerCase()))
                    .append(", \"pid\": ").append(result.pid())
                    .append(", \"sha256\": ").append(quote(result.rawSha256())).append('}');
        }
        if (!histograms.isEmpty()) json.append('\n').append("  ");
        return json.append("]\n}\n").toString();
    }

    private static String failureResult(List<EquivalenceResult> equivalence, List<String> measurements,
                                        List<HistogramResult> histograms, boolean candidateDiagnosticsReady,
                                        Throwable failure) {
        return "{\n  \"status\": \"NON_PASS\",\n  \"equivalenceRows\": " + equivalence.size()
                + ",\n  \"measurementRows\": " + measurements.size()
                + ",\n  \"histogramRows\": " + histograms.size()
                + ",\n  \"candidateTypedRetainedCounts\": "
                + quote(candidateDiagnosticsReady ? "integrated" : "NOT_PROVIDED")
                + ",\n  \"failureType\": " + quote(failure.getClass().getName())
                + ",\n  \"failureMessage\": " + quote(String.valueOf(failure.getMessage())) + "\n}\n";
    }

    private static void attemptReportWrite(Throwable primary, ReportWrite write) {
        try {
            write.run();
        } catch (Throwable reportFailure) {
            if (reportFailure != primary) primary.addSuppressed(reportFailure);
        }
    }

    private static void verifyMeasurementRows(List<String> rows, Variant variant, FixtureId fixture, int fork)
            throws IOException {
        Map<Operation, Set<Integer>> measuredRounds = new LinkedHashMap<>();
        Set<Operation> notApplicable = new LinkedHashSet<>();
        for (String line : rows) {
            Map<String, String> fields;
            try {
                fields = jsonFields(line);
                if (!variant.name().equalsIgnoreCase(fields.get("variant"))
                        || !fixture.name().equalsIgnoreCase(fields.get("fixture"))
                        || Integer.parseInt(fields.get("fork")) != fork) {
                    throw new IOException("measurement row identity mismatch for " + variant + "/" + fixture
                            + "/" + fork + ": " + line);
                }
                String label = fields.get("operation");
                Operation operation = Arrays.stream(Operation.values())
                        .filter(candidate -> candidate.label.equals(label)).findFirst()
                        .orElseThrow(() -> new IOException("unknown measured operation label: " + label));
                if (!operation.applicable(fixture)) {
                    if (!"NOT_APPLICABLE".equals(fields.get("status"))
                            || Integer.parseInt(fields.get("round")) != -1
                            || fields.getOrDefault("reason", "").isBlank()
                            || !notApplicable.add(operation)) {
                        throw new IOException("invalid/duplicate NOT_APPLICABLE row: " + line);
                    }
                } else {
                    if (!"MEASURED".equals(fields.get("status"))) {
                        throw new IOException("applicable operation did not produce a measured row: " + line);
                    }
                    int round = Integer.parseInt(fields.get("round"));
                    if (round < 0 || round >= MEASURE_ROUNDS
                            || !measuredRounds.computeIfAbsent(operation, ignored -> new LinkedHashSet<>()).add(round)) {
                        throw new IOException("invalid/duplicate measured round: " + line);
                    }
                }
            } catch (NumberFormatException | NullPointerException malformed) {
                throw new IOException("malformed measurement row for " + variant + "/" + fixture + "/" + fork,
                        malformed);
            }
        }
        for (Operation operation : Operation.values()) {
            if (operation.applicable(fixture)) {
                Set<Integer> rounds = measuredRounds.get(operation);
                if (rounds == null || rounds.size() != MEASURE_ROUNDS) {
                    throw new IOException("missing measured rounds for " + variant + "/" + fixture + "/" + fork
                            + " operation=" + operation.label + " expected=" + MEASURE_ROUNDS
                            + " actual=" + (rounds == null ? 0 : rounds.size()));
                }
            } else if (!notApplicable.contains(operation)) {
                throw new IOException("missing explicit N/A row for " + variant + "/" + fixture + "/" + fork
                        + " operation=" + operation.label);
            }
        }
        if (rows.size() != measuredRounds.values().stream().mapToInt(Set::size).sum() + notApplicable.size()) {
            throw new IOException("measurement rows contain unexpected duplicates or omissions for "
                    + variant + "/" + fixture + "/" + fork);
        }
    }

    private static List<String> javaCommand(String classpath, String... args) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable("java").toString());
        command.add("-Xms2g");
        command.add("-Xmx2g");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(classpath);
        command.add(CLASS_NAME);
        command.addAll(List.of(args));
        return command;
    }

    private static Path javaExecutable(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "";
        Path path = Path.of(System.getProperty("java.home"), "bin", name + suffix);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("required Java 25 executable is missing: " + path);
        }
        return path;
    }

    private static void waitForFileOrExit(Path ready, Process process, Path stdout, Path stderr,
                                          long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!Files.isRegularFile(ready)) {
            if (!process.isAlive()) {
                throw new IOException("child exited before readiness with " + process.exitValue()
                        + "; stdout=" + stdout + "; stderr=" + stderr);
            }
            if (System.nanoTime() >= deadline) {
                throw new IOException("timed out waiting for child readiness: " + ready
                        + "; stdout=" + stdout + "; stderr=" + stderr);
            }
            Thread.sleep(50L);
        }
    }

    private static int waitFor(Process process, long timeout, TimeUnit unit, String operation,
                               List<String> command, Path stderr) throws Exception {
        try {
            if (!process.waitFor(timeout, unit)) {
                IOException failure = new IOException("timed out waiting for " + operation + " command=" + command
                        + " stderr=" + stderr);
                terminateOwnedProcessTree(process, failure, operation + " timeout");
                throw failure;
            }
        } catch (InterruptedException interrupted) {
            terminateOwnedProcessTree(process, interrupted, operation + " interrupted");
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        return process.exitValue();
    }

    private static Throwable terminateOwnedProcessTree(Process process, Throwable primary, String operation) {
        if (!process.isAlive()) return primary;
        Throwable cleanupFailure = null;
        List<ProcessHandle> descendants = List.of();
        try (var stream = process.toHandle().descendants()) {
            descendants = stream.toList();
        } catch (Throwable discoveryFailure) {
            cleanupFailure = combineFailures(cleanupFailure, discoveryFailure);
        }
        for (ProcessHandle descendant : descendants) {
            try {
                if (descendant.isAlive()) descendant.destroy();
            } catch (Throwable destroyFailure) {
                cleanupFailure = combineFailures(cleanupFailure, destroyFailure);
            }
        }
        try {
            process.destroy();
        } catch (Throwable destroyFailure) {
            cleanupFailure = combineFailures(cleanupFailure, destroyFailure);
        }

        boolean interrupted = awaitOwnedExit(process, descendants, 3, TimeUnit.SECONDS);
        if (ownedTreeAlive(process, descendants)) {
            for (ProcessHandle descendant : descendants) {
                try {
                    if (descendant.isAlive()) descendant.destroyForcibly();
                } catch (Throwable forceFailure) {
                    cleanupFailure = combineFailures(cleanupFailure, forceFailure);
                }
            }
            try {
                if (process.isAlive()) process.destroyForcibly();
            } catch (Throwable forceFailure) {
                cleanupFailure = combineFailures(cleanupFailure, forceFailure);
            }
            interrupted |= awaitOwnedExit(process, descendants, 5, TimeUnit.SECONDS);
        }
        if (ownedTreeAlive(process, descendants)) {
            cleanupFailure = combineFailures(cleanupFailure, new IOException(operation
                    + " could not terminate owned process tree; pid=" + process.pid()));
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (cleanupFailure != null) {
            if (primary != null) {
                if (cleanupFailure != primary) primary.addSuppressed(cleanupFailure);
                return primary;
            }
            return cleanupFailure;
        }
        return primary;
    }

    private static boolean awaitOwnedExit(Process process, List<ProcessHandle> descendants,
                                          long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        boolean interrupted = false;
        while (ownedTreeAlive(process, descendants)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) break;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            } catch (InterruptedException ignored) {
                interrupted = true;
                break;
            }
        }
        return interrupted;
    }

    private static boolean ownedTreeAlive(Process process, List<ProcessHandle> descendants) {
        return process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
    }

    private static Throwable combineFailures(Throwable first, Throwable next) {
        if (first == null) return next;
        if (next != first) first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("unexpected non-Exception throwable", failure);
    }

    private static void cleanupAfter(Throwable primary, CleanupAction... actions) throws Exception {
        Throwable cleanupFailure = null;
        for (CleanupAction action : actions) {
            try {
                action.run();
            } catch (Throwable failure) {
                if (primary != null) {
                    if (failure != primary) primary.addSuppressed(failure);
                } else {
                    cleanupFailure = combineFailures(cleanupFailure, failure);
                }
            }
        }
        if (primary == null && cleanupFailure != null) rethrow(cleanupFailure);
    }

    private static String awaitChildCommand() throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command = reader.readLine();
            if (command == null) {
                throw new IOException("comparison parent closed the child control stream before measurement");
            }
            return command;
        }
    }

    private static void sendChildCommand(Process process, String command) throws IOException {
        process.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();
    }

    private static Map<String, String> readProperties(Path file) throws IOException {
        Map<String, String> result = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IOException("malformed child identity line: " + line);
            result.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return result;
    }

    private static void writeEnvironment(Path path, Options options) throws IOException {
        String content = "{\n"
                + "  \"createdUtc\": " + quote(Instant.now().toString()) + ",\n"
                + "  \"javaVersion\": " + quote(System.getProperty("java.version")) + ",\n"
                + "  \"javaHome\": " + quote(System.getProperty("java.home")) + ",\n"
                + "  \"vmName\": " + quote(System.getProperty("java.vm.name")) + ",\n"
                + "  \"os\": " + quote(System.getProperty("os.name") + " " + System.getProperty("os.version")) + ",\n"
                + "  \"architecture\": " + quote(System.getProperty("os.arch")) + ",\n"
                + "  \"processors\": " + Runtime.getRuntime().availableProcessors() + ",\n"
                + "  \"maxHeapBytes\": " + Runtime.getRuntime().maxMemory() + ",\n"
                + "  \"seed\": " + quote("0x55474608") + ",\n"
                + "  \"warmupRounds\": " + WARMUP_ROUNDS + ",\n"
                + "  \"measureRounds\": " + MEASURE_ROUNDS + ",\n"
                + "  \"sampleOperationsPerRound\": " + SAMPLE_OPERATIONS + ",\n"
                + "  \"dirtyOperationsPerRound\": " + DIRTY_OPERATIONS + ",\n"
                + "  \"baselineManifestSha256\": " + quote(sha256(options.baselineManifest())) + ",\n"
                + "  \"externalClasspathManifestSha256\": " + quote(sha256(options.externalManifest())) + "\n"
                + "}\n";
        writeUtf8(path, content);
    }

    private static void writeCommandSummary(Path path, Options options) throws IOException {
        String text = "protocol=" + PROTOCOL + "\n"
                + "java=" + javaExecutable("java") + "\n"
                + "baselineClasspathEntryCount=" + options.baselineClasspath().size() + "\n"
                + "candidateClasspathEntryCount=" + options.candidateClasspath().size() + "\n"
                + "baselineClasspathSha256=" + sha256Text(String.join(System.getProperty("path.separator"),
                        options.baselineClasspath())) + "\n"
                + "candidateClasspathSha256=" + sha256Text(String.join(System.getProperty("path.separator"),
                        options.candidateClasspath())) + "\n"
                + "baselineClasspath=" + String.join(System.lineSeparator(), options.baselineClasspath()) + "\n"
                + "candidateClasspath=" + String.join(System.lineSeparator(), options.candidateClasspath()) + "\n"
                + "jvmArgs=-Xms2g -Xmx2g -Dfile.encoding=UTF-8\n";
        writeUtf8(path, text);
    }

    private static void writeEquivalenceReport(Path path, List<EquivalenceResult> results,
                                               String status, Throwable failure) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"protocol\": ").append(quote(PROTOCOL))
                .append(",\n  \"status\": ").append(quote(status)).append(",\n  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            EquivalenceResult result = results.get(i);
            json.append("    {\"fixture\": ").append(quote(result.fixture().name().toLowerCase()))
                    .append(", \"fork\": ").append(result.fork())
                    .append(", \"status\": ").append(quote(result.status()))
                    .append(", \"rows\": ").append(result.baseline().rows())
                    .append(", \"scalarRawSha256\": ").append(quote(result.baseline().rawSha256()))
                    .append(", \"candidateRawSha256\": ").append(quote(result.candidate().rawSha256()))
                    .append(", \"scalarTrace\": ").append(quote(result.baseline().path().toString()))
                    .append(", \"candidateTrace\": ").append(quote(result.candidate().path().toString()))
                    .append("}");
            if (i + 1 < results.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]");
        if (failure != null) {
            json.append(",\n  \"failure\": {\"type\": ").append(quote(failure.getClass().getName()))
                    .append(", \"message\": ").append(quote(String.valueOf(failure.getMessage())))
                    .append("}");
        }
        json.append("\n}\n");
        writeUtf8(path, json.toString());
    }

    private static void writeMeasurementLines(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
        }
    }

    private static void writeRoundRows(Path path, List<RoundRow> rows) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (RoundRow row : rows) {
                writer.write(row.toJson());
                writer.newLine();
            }
        }
    }

    private static void writeSummary(Path path, List<String> lines) throws IOException {
        Map<String, List<Double>> latency = new LinkedHashMap<>();
        Map<String, List<Double>> allocationPerOperation = new LinkedHashMap<>();
        try {
            for (String line : lines) {
                Map<String, String> fields = jsonFields(line);
                if (!"MEASURED".equals(fields.get("status"))) continue;
                String prefix = fields.get("variant") + "," + fields.get("fixture") + ","
                        + fields.get("operation");
                String fork = fields.get("fork");
                int operations = Integer.parseInt(fields.get("operations"));
                double nsPerOperation = Double.parseDouble(fields.get("nsPerOperation"));
                String allocatedText = fields.get("allocatedBytes");
                Double bytesPerOperation = allocatedText == null || "unavailable".equals(allocatedText)
                        ? null : (double) Long.parseLong(allocatedText) / operations;
                for (String key : new String[]{prefix + ",pooled", prefix + "," + fork}) {
                    latency.computeIfAbsent(key, ignored -> new ArrayList<>()).add(nsPerOperation);
                    if (bytesPerOperation != null) {
                        allocationPerOperation.computeIfAbsent(key, ignored -> new ArrayList<>())
                                .add(bytesPerOperation);
                    }
                }
            }
        } catch (RuntimeException failure) {
            throw new IOException("could not aggregate raw floor measurement rows", failure);
        }
        StringBuilder csv = new StringBuilder("variant,fixture,operation,fork,roundCount,p50NsPerOperation,"
                + "p95NsPerOperation,p99NsPerOperation,p50AllocatedBytesPerOperation,latencyMeaning\n");
        latency.forEach((key, times) -> {
            times.sort(Double::compare);
            List<Double> allocation = allocationPerOperation.get(key);
            if (allocation != null) allocation.sort(Double::compare);
            csv.append(key).append(',').append(times.size()).append(',')
                    .append(percentile(times, 0.50)).append(',')
                    .append(percentile(times, 0.95)).append(',')
                    .append(percentile(times, 0.99))
                    .append(',').append(allocation == null || allocation.isEmpty()
                            ? "unavailable" : percentile(allocation, 0.50))
                    .append(",round-level JVM operation distribution; not player latency\n");
        });
        writeUtf8(path, csv.toString());
    }

    private static void writeHistogramReport(Path path, List<HistogramResult> histograms, String note)
            throws IOException {
        StringBuilder content = new StringBuilder("FLOOR RETAINED-OBJECT EVIDENCE\n")
                .append("jcmd GC.class_histogram ran in dedicated child JVMs outside all timed intervals.\n")
                .append("The histogram triggers GC and is not exact application retained heap.\n")
                .append("Primitive coverage mask payload is not inferable from histogram totals.\n")
                .append(note).append('\n');
        for (HistogramResult result : histograms) {
            content.append('\n').append("variant=").append(result.variant().name().toLowerCase())
                    .append(" fixture=").append(result.fixture().name().toLowerCase())
                    .append(" pid=").append(result.pid())
                    .append(" raw=").append(result.rawPath())
                    .append(" sha256=").append(result.rawSha256()).append('\n');
        }
        writeUtf8(path, content.toString());
    }

    private static void writeHistogramManifest(Path path, List<HistogramResult> histograms) throws IOException {
        StringBuilder json = new StringBuilder("{\n  \"status\": \"CAPTURED\",\n  \"histograms\": [\n");
        for (int i = 0; i < histograms.size(); i++) {
            HistogramResult h = histograms.get(i);
            json.append("    {\"variant\": ").append(quote(h.variant().name().toLowerCase()))
                    .append(", \"fixture\": ").append(quote(h.fixture().name().toLowerCase()))
                    .append(", \"pid\": ").append(h.pid())
                    .append(", \"jcmd\": ").append(jsonArray(h.jcmdCommand()))
                    .append(", \"rawPath\": ").append(quote(h.rawPath().toString()))
                    .append(", \"sha256\": ").append(quote(h.rawSha256())).append("}");
            if (i + 1 < histograms.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        writeUtf8(path, json.toString());
    }

    private static void writeOverallStatus(Path path, List<EquivalenceResult> equivalence,
                                           List<String> measurements, List<HistogramResult> histograms,
                                           boolean candidateDiagnosticsReady) throws IOException {
        boolean complete = candidateDiagnosticsReady && !equivalence.isEmpty()
                && equivalence.stream().allMatch(result -> "PASS".equals(result.status()))
                && !measurements.isEmpty() && !histograms.isEmpty();
        writeUtf8(path, "{\n  \"status\": " + quote(complete ? "PASS" : "NON_PASS")
                + ",\n  \"equivalenceRows\": " + equivalence.size()
                + ",\n  \"measurementRows\": " + measurements.size()
                + ",\n  \"histogramRows\": " + histograms.size()
                + ",\n  \"candidateTypedRetainedCounts\": "
                + quote(candidateDiagnosticsReady ? "integrated" : "NOT_PROVIDED")
                + ",\n  \"reason\": "
                + quote(complete ? "All runner evidence present; engineering thresholds still require review."
                : "Required candidate-specific retained structure evidence is not integrated.")
                + "\n}\n");
    }

    private static String jsonArray(List<String> values) {
        return values.stream().map(FloorAggregationPerfRunner::quote)
                .reduce("[", (left, right) -> left.equals("[") ? left + right : left + "," + right) + "]";
    }

    private static Map<String, String> jsonFields(String jsonLine) {
        Map<String, String> result = new HashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*(\"((?:\\\\.|[^\"])*)\"|[^,}]+)")
                .matcher(jsonLine);
        while (matcher.find()) {
            String raw = matcher.group(2).trim();
            result.put(matcher.group(1), raw.startsWith("\"") ? matcher.group(3) : raw);
        }
        return result;
    }

    private static double percentile(List<Double> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static void verifyFloorClassOrigins(Path expectedFloorRoot) throws Exception {
        Path expected = expectedFloorRoot.toRealPath();
        for (Class<?> type : List.of(FloorFieldIndex.class, FloorWorldFields.class, FloorChunkSources.class)) {
            Path actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if (!actual.equals(expected)) {
                throw new IllegalStateException("wrong " + type.getName() + " class origin expected="
                        + expected + " actual=" + actual);
            }
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            String hash;
            try (var in = type.getResourceAsStream(resource)) {
                if (in == null) throw new IOException("missing loaded class bytes for " + type.getName());
                hash = sha256(in.readAllBytes());
            }
            System.out.println("CLASS_ORIGIN name=" + type.getName() + " codeSource=" + actual + " sha256=" + hash);
        }
        Path runnerRoot = Path.of(FloorAggregationPerfRunner.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        System.out.println("CLASS_ORIGIN name=" + CLASS_NAME + " codeSource=" + runnerRoot);
    }

    static Fixture createFixture(FixtureSpec spec) {
        FixtureId id = spec.id();
        int width = spec.width();
        if (id == FixtureId.DIRTY_BATCH_CROSS_SECTION) {
            return createCrossSectionBatchFixture(spec.seed());
        }
        int firstChunk = -width / 2;
        LinkedHashMap<ChunkKey, ChunkData> chunks = new LinkedHashMap<>();
        List<GridProbe> grid = new ArrayList<>();
        List<ProbeSource> probeSources = new ArrayList<>();
        int minX = firstChunk * 16;
        int minZ = firstChunk * 16;
        int maxX = (firstChunk + width) * 16;
        int maxZ = (firstChunk + width) * 16;
        int[] layers = id == FixtureId.MULTILAYER || id == FixtureId.MULTILAYER_WIDE
                ? new int[]{16, 20, 24, 28}
                : new int[]{16};
        Set<Integer> sectionBoundaries = new LinkedHashSet<>(List.of(0, 16, 32, 48));
        for (int chunkX = firstChunk; chunkX < firstChunk + width; chunkX++) {
            for (int chunkZ = firstChunk; chunkZ < firstChunk + width; chunkZ++) {
                ChunkKey key = new ChunkKey(chunkX, chunkZ);
                ChunkData chunk = new ChunkData(key);
                chunks.put(key, chunk);
                for (int localZ = 0; localZ < 16; localZ++) {
                    int z = chunkZ * 16 + localZ;
                    for (int localX = 0; localX < 16; localX++) {
                        int x = chunkX * 16 + localX;
                        for (int layer = 0; layer < layers.length; layer++) {
                            int y = layers[layer];
                            grid.add(new GridProbe(x, y, z));
                            boolean hole = id == FixtureId.QUARTER_HOLES
                                    && Math.floorMod(x * 3 + z * 5 + (int) spec.seed(), 4) == 0;
                            if (hole) continue;
                            if (id == FixtureId.PUBLIC_MIXED_PROFILES) {
                                addPublicSources(chunk, probeSources, x, y, z, layer);
                            } else {
                                addStorageCell(chunk, probeSources, x, y, z, id, layer);
                            }
                        }
                    }
                }
            }
        }
        Fixture fixture = new Fixture(id, width, spec.seed(), chunks, grid, probeSources,
                minX, maxX, minZ, maxZ, List.copyOf(sectionBoundaries));
        if (id == FixtureId.MULTILAYER_WIDE) {
            verifyMultiLayerWideFixture(fixture);
        }
        return fixture;
    }

    private static void verifyMultiLayerWideFixture(Fixture fixture) {
        long cellsPerLayer = Math.multiplyExact(Math.multiplyExact((long) fixture.width, fixture.width), 256L);
        Map<Integer, Long> layerYCounts = new java.util.TreeMap<>();
        Map<String, Long> layerFaceCounts = new java.util.TreeMap<>();
        long physicalBlocks = 0L;
        long emitters = 0L;
        long storageSections = 0L;
        int upNormalMask = 0;
        int downNormalMask = 0;
        for (ChunkData chunk : fixture.chunks.values()) {
            long chunkPhysical = 0L;
            long chunkEmitters = 0L;
            for (int sectionIndex = 0; sectionIndex < chunk.storage.length; sectionIndex++) {
                ExtendedBlockStorage section = chunk.storage[sectionIndex];
                if (section == null || section.isEmpty()) {
                    continue;
                }
                if (sectionIndex != 1 || section.getYLocation() != 16) {
                    throw new IllegalStateException("wide multilayer fixture has unexpected storage section"
                            + " index=" + sectionIndex + " y=" + section.getYLocation() + " chunk=" + chunk.key);
                }
                storageSections++;
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            IBlockState state = section.get(localX, localY, localZ);
                            if (state.getBlock() == Blocks.AIR) {
                                continue;
                            }
                            if (state.getBlock() != FloorBlocks.GRAVITY_FLOOR) {
                                throw new IllegalStateException("wide multilayer fixture contains a non-gravity block"
                                        + " chunk=" + chunk.key + " local=" + localX + "," + localY + "," + localZ);
                            }
                            int y = section.getYLocation() + localY;
                            Face face = Face.valueOf(state.getValue(DirectionalFloorBlock.FACING).name());
                            layerYCounts.merge(y, 1L, Math::addExact);
                            layerFaceCounts.merge(y + "|" + face.name(), 1L, Math::addExact);
                            if (face == Face.UP) {
                                upNormalMask |= 1 << Math.floorMod(y, 16);
                            } else if (face == Face.DOWN) {
                                downNormalMask |= 1 << Math.floorMod(y, 16);
                            } else {
                                throw new IllegalStateException("wide multilayer fixture must use UP and DOWN faces");
                            }
                            chunkPhysical++;
                            chunkEmitters++;
                        }
                    }
                }
            }
            if (chunkPhysical != 1024L || chunkEmitters != 1024L
                    || chunk.expectedPhysicalCount != chunkPhysical
                    || chunk.expectedEmitterCount != chunkEmitters) {
                throw new IllegalStateException("wide multilayer chunk physical/emitter count mismatch chunk="
                        + chunk.key + " physical=" + chunkPhysical + " emitters=" + chunkEmitters);
            }
            physicalBlocks = Math.addExact(physicalBlocks, chunkPhysical);
            emitters = Math.addExact(emitters, chunkEmitters);
        }
        Map<Integer, Long> expectedLayers = Map.of(16, cellsPerLayer, 20, cellsPerLayer,
                24, cellsPerLayer, 28, cellsPerLayer);
        Map<String, Long> expectedFaces = Map.of("16|UP", cellsPerLayer, "20|DOWN", cellsPerLayer,
                "24|UP", cellsPerLayer, "28|DOWN", cellsPerLayer);
        int expectedUpMask = (1 << 0) | (1 << 8);
        int expectedDownMask = (1 << 4) | (1 << 12);
        if (fixture.width != 16 || fixture.chunks.size() != 256
                || physicalBlocks != 262_144L || emitters != 262_144L || storageSections != 256L
                || !layerYCounts.equals(expectedLayers) || !layerFaceCounts.equals(expectedFaces)
                || upNormalMask != expectedUpMask || downNormalMask != expectedDownMask) {
            throw new IllegalStateException("wide multilayer fixture does not contain the expected four actual layers"
                    + " chunks=" + fixture.chunks.size() + " physical=" + physicalBlocks
                    + " emitters=" + emitters + " sections=" + storageSections + " yCounts=" + layerYCounts
                    + " faceCounts=" + layerFaceCounts + " upMask=" + upNormalMask
                    + " downMask=" + downNormalMask);
        }
    }

    private static Map<String, Object> multiLayerWideShape(Fixture fixture) {
        if (fixture.id != FixtureId.MULTILAYER_WIDE) {
            return null;
        }
        verifyMultiLayerWideFixture(fixture);
        long cellsPerLayer = Math.multiplyExact(Math.multiplyExact((long) fixture.width, fixture.width), 256L);
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("physicalBlocks", 262_144L);
        shape.put("emitters", 262_144L);
        shape.put("retainedStorageSections", 256L);
        shape.put("layerYCounts", Map.of("16", cellsPerLayer, "20", cellsPerLayer,
                "24", cellsPerLayer, "28", cellsPerLayer));
        shape.put("layerFaceCounts", Map.of("16|UP", cellsPerLayer, "20|DOWN", cellsPerLayer,
                "24|UP", cellsPerLayer, "28|DOWN", cellsPerLayer));
        shape.put("upLocalNormalMask", (1 << 0) | (1 << 8));
        shape.put("downLocalNormalMask", (1 << 4) | (1 << 12));
        return Map.copyOf(shape);
    }

    private static Fixture createCrossSectionBatchFixture(long seed) {
        ChunkData chunk = new ChunkData(new ChunkKey(0, 0));
        List<GridProbe> grid = new ArrayList<>(256);
        List<ProbeSource> probes = new ArrayList<>(256);
        for (int sectionIndex = 0; sectionIndex < 16; sectionIndex++) {
            int baseY = sectionIndex * 16;
            ExtendedBlockStorage section = new ExtendedBlockStorage(baseY, true);
            chunk.storage[sectionIndex] = section;
            for (int x = 0; x < 16; x++) {
                section.set(x, 0, 0, floorState(Face.UP, Kind.GRAVITY));
                grid.add(new GridProbe(x, baseY, 0));
                probes.add(new ProbeSource(x, baseY, 0, Face.UP, Kind.GRAVITY, BUILTIN));
                chunk.expectedPhysicalCount++;
                chunk.expectedEmitterCount++;
            }
        }
        Set<Integer> boundaries = new LinkedHashSet<>();
        for (int y = 0; y <= 256; y += 16) boundaries.add(y);
        return new Fixture(FixtureId.DIRTY_BATCH_CROSS_SECTION, 1, seed,
                Map.of(chunk.key, chunk), grid, probes, 0.0, 16.0, 0.0, 16.0,
                List.copyOf(boundaries));
    }

    private static void addStorageCell(ChunkData chunk, List<ProbeSource> probes, int x, int y, int z,
                                       FixtureId id, int layer) {
        Face face;
        boolean doubleSided = id == FixtureId.DOUBLE_SIX_FACE;
        if (id == FixtureId.MULTILAYER || id == FixtureId.MULTILAYER_WIDE) {
            face = (layer & 1) == 0 ? Face.UP : Face.DOWN;
        } else if (id == FixtureId.MIXED_SIX_FACE || doubleSided) {
            face = Face.values()[Math.floorMod(x + 3 * z + y, Face.values().length)];
        } else {
            face = Face.UP;
        }
        IBlockState state;
        if (doubleSided) {
            state = FloorBlocks.DOUBLE_SIDED_GRAVITY_FLOOR.getStateFromMeta(facing(face).getIndex());
            probes.add(new ProbeSource(x, y, z, face, Kind.GRAVITY, BUILTIN));
            probes.add(new ProbeSource(x, y, z, opposite(face), Kind.GRAVITY, BUILTIN));
            chunk.expectedEmitterCount += 2L;
        } else {
            Kind kind = id == FixtureId.MIXED_SIX_FACE && Math.floorMod(x + z, 4) == 0
                    ? Kind.MAGNETIC : Kind.GRAVITY;
            state = floorState(face, kind);
            probes.add(new ProbeSource(x, y, z, face, kind, BUILTIN));
            chunk.expectedEmitterCount++;
        }
        ExtendedBlockStorage section = chunk.storage[y >> 4];
        if (section == null) {
            section = new ExtendedBlockStorage((y >> 4) << 4, true);
            chunk.storage[y >> 4] = section;
        }
        section.set(x & 15, y & 15, z & 15, state);
        chunk.expectedPhysicalCount++;
    }

    private static void addPublicSources(ChunkData chunk, List<ProbeSource> probes,
                                         int x, int y, int z, int layer) {
        int selector = Math.floorMod((int) mix64(x, y, z, layer), PROFILE_SET.size());
        FloorFieldProfile profile = PROFILE_SET.get(selector);
        Kind kind = (Math.floorMod(x - z + layer, 3) == 0) ? Kind.MAGNETIC : Kind.GRAVITY;
        Face face = Face.values()[Math.floorMod(x + z + 5 * layer, Face.values().length)];
        Source source = new Source(x, y, z, face, kind, profile);
        chunk.publicSources.add(source);
        probes.add(new ProbeSource(x, y, z, face, kind, profile));
        chunk.expectedPhysicalCount++;
        chunk.expectedEmitterCount++;
    }

    private static long mix64(long x, long y, long z, long layer) {
        long value = SEED ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL)
                ^ (z * 0x165667B19E3779F9L) ^ layer;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static IBlockState floorState(Face face, Kind kind) {
        return (kind == Kind.GRAVITY ? FloorBlocks.GRAVITY_FLOOR : FloorBlocks.MAGNETIC_FLOOR)
                .getDefaultState().withProperty(DirectionalFloorBlock.FACING, facing(face));
    }

    private static EnumFacing facing(Face face) {
        return EnumFacing.valueOf(face.name());
    }

    private static Face opposite(Face face) {
        return switch (face) {
            case DOWN -> Face.UP;
            case UP -> Face.DOWN;
            case NORTH -> Face.SOUTH;
            case SOUTH -> Face.NORTH;
            case WEST -> Face.EAST;
            case EAST -> Face.WEST;
        };
    }

    private static List<Query> createSampleQueryPool(Fixture fixture) {
        List<Query> queries = new ArrayList<>(fixture.gridProbes.size() + 512);
        long index = 0L;
        for (GridProbe grid : fixture.gridProbes) {
            queries.add(new Query(new Vec3(grid.x() + 0.5, grid.y() + 1.5, grid.z() + 0.5),
                    RESPONSES[(int) (index++ % RESPONSES.length)], QueryCategory.CELL_CENTER));
        }
        Set<String> visited = new LinkedHashSet<>();
        for (ProbeSource source : fixture.probeSources) {
            String key = source.face() + "|" + source.kind() + "|" + source.profile();
            if (!visited.add(key)) continue;
            double[] distances = {0.0, source.profile().plateau(),
                    Math.nextDown(source.profile().range()), source.profile().range(),
                    Math.nextUp(source.profile().range())};
            QueryCategory[] categories = {
                    QueryCategory.FACE_ORIGIN, QueryCategory.FACE_PLATEAU,
                    QueryCategory.FACE_RANGE_BELOW, QueryCategory.FACE_RANGE_EXACT,
                    QueryCategory.FACE_RANGE_ABOVE
            };
            for (int distanceIndex = 0; distanceIndex < distances.length; distanceIndex++) {
                double distance = distances[distanceIndex];
                for (double response : RESPONSES) {
                    queries.add(new Query(facePoint(source, distance), response, categories[distanceIndex]));
                }
            }
            addTangentEdgeQueries(queries, source);
        }
        QueryCategory[] edgeCategories = {
                QueryCategory.SECTION_EDGE_BELOW, QueryCategory.SECTION_EDGE_EXACT,
                QueryCategory.SECTION_EDGE_ABOVE
        };
        Vec3 sectionAnchor = sectionBoundaryAnchor(fixture);
        for (int boundary : fixture.sectionBoundaries) {
            double[] edges = {Math.nextDown((double) boundary), boundary, Math.nextUp((double) boundary)};
            for (int edgeIndex = 0; edgeIndex < edges.length; edgeIndex++) {
                queries.add(new Query(new Vec3(sectionAnchor.x(), edges[edgeIndex], sectionAnchor.z()), 1.0,
                        edgeCategories[edgeIndex]));
            }
        }
        QueryCategory[] chunkCategories = {
                QueryCategory.CHUNK_EDGE_BELOW, QueryCategory.CHUNK_EDGE_EXACT,
                QueryCategory.CHUNK_EDGE_ABOVE
        };
        ProbeSource anchor = fixture.probeSources.getFirst();
        for (AxisBoundary axisBoundary : chunkBoundaries(fixture)) {
            double boundary = axisBoundary.value();
            double[] edges = {Math.nextDown(boundary), boundary, Math.nextUp(boundary)};
            for (int edgeIndex = 0; edgeIndex < edges.length; edgeIndex++) {
                Vec3 point = axisBoundary.axis() == 0
                        ? new Vec3(edges[edgeIndex], anchor.y() + 0.5, anchor.z() + 0.5)
                        : new Vec3(anchor.x() + 0.5, anchor.y() + 0.5, edges[edgeIndex]);
                queries.add(new Query(point, RESPONSES[(int) (index++ % RESPONSES.length)],
                        chunkCategories[edgeIndex]));
            }
        }
        return List.copyOf(queries);
    }

    private static Vec3 sectionBoundaryAnchor(Fixture fixture) {
        ProbeSource source = fixture.probeSources.getFirst();
        return new Vec3(source.x() + 0.5, source.y() + 0.5, source.z() + 0.5);
    }

    private static List<AxisBoundary> chunkBoundaries(Fixture fixture) {
        return List.of(new AxisBoundary(0, fixture.minWorldX),
                new AxisBoundary(0, fixture.maxWorldX),
                new AxisBoundary(2, fixture.minWorldZ),
                new AxisBoundary(2, fixture.maxWorldZ));
    }

    private static void addTangentEdgeQueries(List<Query> queries, ProbeSource source) {
        Vec3 faceCenter = facePoint(source, 0.0);
        int[] tangentAxes = switch (source.face()) {
            case DOWN, UP -> new int[]{0, 2};
            case NORTH, SOUTH -> new int[]{0, 1};
            case WEST, EAST -> new int[]{1, 2};
        };
        QueryCategory[] categories = {
                QueryCategory.TANGENT_EDGE_BELOW, QueryCategory.TANGENT_EDGE_EXACT,
                QueryCategory.TANGENT_EDGE_ABOVE
        };
        for (int axis : tangentAxes) {
            double start = switch (axis) {
                case 0 -> source.x();
                case 1 -> source.y();
                case 2 -> source.z();
                default -> throw new IllegalStateException("unknown tangent axis " + axis);
            };
            double end = start + 1.0;
            for (double boundary : new double[]{start, end}) {
                double[] edges = {Math.nextDown(boundary), boundary, Math.nextUp(boundary)};
                for (int edgeIndex = 0; edgeIndex < edges.length; edgeIndex++) {
                    Vec3 point = switch (axis) {
                        case 0 -> new Vec3(edges[edgeIndex], faceCenter.y(), faceCenter.z());
                        case 1 -> new Vec3(faceCenter.x(), edges[edgeIndex], faceCenter.z());
                        case 2 -> new Vec3(faceCenter.x(), faceCenter.y(), edges[edgeIndex]);
                        default -> throw new IllegalStateException("unknown tangent axis " + axis);
                    };
                    queries.add(new Query(point, 1.0, categories[edgeIndex]));
                }
            }
        }
    }

    private static SampleQuerySchedule scheduleSampleQueries(List<Query> rawQueries) {
        if (rawQueries.isEmpty()) {
            throw new IllegalStateException("sample fixture produced no real probe queries");
        }
        EnumMap<QueryCategory, List<Query>> pools = new EnumMap<>(QueryCategory.class);
        for (Query query : rawQueries) {
            pools.computeIfAbsent(query.category(), ignored -> new ArrayList<>()).add(query);
        }
        List<QueryCategory> active = new ArrayList<>(pools.keySet());
        if (active.isEmpty() || (long) active.size() * MIN_SAMPLE_QUERIES_PER_CATEGORY > SAMPLE_OPERATIONS) {
            throw new IllegalStateException("sample query categories cannot fit in the fixed round: " + active.size());
        }

        int[] quotas = new int[active.size()];
        long[] remainders = new long[active.size()];
        int reserved = MIN_SAMPLE_QUERIES_PER_CATEGORY * active.size();
        int remaining = SAMPLE_OPERATIONS - reserved;
        long rawCount = rawQueries.size();
        int distributed = 0;
        for (int i = 0; i < active.size(); i++) {
            int poolSize = pools.get(active.get(i)).size();
            long weighted = (long) remaining * poolSize;
            int share = (int) (weighted / rawCount);
            quotas[i] = MIN_SAMPLE_QUERIES_PER_CATEGORY + share;
            remainders[i] = weighted % rawCount;
            distributed += share;
        }
        int remainder = remaining - distributed;
        boolean[] receivedRemainder = new boolean[active.size()];
        while (remainder > 0) {
            int best = -1;
            for (int i = 0; i < active.size(); i++) {
                if (receivedRemainder[i]) continue;
                if (best < 0 || remainders[i] > remainders[best]) best = i;
            }
            if (best < 0) throw new IllegalStateException("sample quota apportionment remainder exceeded category count");
            quotas[best]++;
            receivedRemainder[best] = true;
            remainder--;
        }

        int[] emitted = new int[active.size()];
        long[] current = new long[active.size()];
        List<Query> schedule = new ArrayList<>(SAMPLE_OPERATIONS);
        for (int step = 0; step < SAMPLE_OPERATIONS; step++) {
            int selected = -1;
            for (int i = 0; i < active.size(); i++) {
                current[i] += quotas[i];
                if (emitted[i] < quotas[i]
                        && (selected < 0 || current[i] > current[selected])) {
                    selected = i;
                }
            }
            if (selected < 0) throw new IllegalStateException("sample schedule exhausted categories before fixed round end");
            current[selected] -= SAMPLE_OPERATIONS;
            QueryCategory category = active.get(selected);
            List<Query> pool = pools.get(category);
            schedule.add(pool.get(emitted[selected]++ % pool.size()));
        }
        for (int i = 0; i < active.size(); i++) {
            if (emitted[i] != quotas[i] || quotas[i] < MIN_SAMPLE_QUERIES_PER_CATEGORY) {
                throw new IllegalStateException("sample category was not represented by the fixed schedule: " + active.get(i));
            }
        }

        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (QueryCategory category : QueryCategory.values()) {
            counts.put(category.name().toLowerCase(Locale.ROOT), 0L);
        }
        MessageDigest digest = sha256Digest();
        ByteBuffer encoded = ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 4);
        for (Query query : schedule) {
            String label = query.category().name().toLowerCase(Locale.ROOT);
            counts.put(label, Math.addExact(counts.get(label), 1L));
            encoded.clear();
            encoded.putInt(query.category().ordinal())
                    .putLong(Double.doubleToRawLongBits(query.position().x()))
                    .putLong(Double.doubleToRawLongBits(query.position().y()))
                    .putLong(Double.doubleToRawLongBits(query.position().z()))
                    .putLong(Double.doubleToRawLongBits(query.response()));
            digest.update(encoded.array());
        }
        for (Map.Entry<String, Long> category : counts.entrySet()) {
            if (category.getValue() <= 0L) {
                throw new IllegalStateException("fixed sample schedule omitted a required probe category: " + category.getKey());
            }
        }
        long scheduledCount = counts.values().stream().mapToLong(Long::longValue).sum();
        if (schedule.size() != SAMPLE_OPERATIONS || scheduledCount != SAMPLE_OPERATIONS) {
            throw new IllegalStateException("sample schedule count changed expected=" + SAMPLE_OPERATIONS
                    + " actual=" + schedule.size() + "/" + scheduledCount);
        }
        return new SampleQuerySchedule(List.copyOf(schedule), rawCount,
                Collections.unmodifiableMap(counts), hex(digest.digest()));
    }

    private static final int MIN_SAMPLE_QUERIES_PER_CATEGORY = 64;

    private static DirtyCellRef firstPhysicalCell(Fixture fixture) {
        for (ChunkData chunk : orderedChunksCenterFirst(fixture)) {
            for (int sectionIndex = 0; sectionIndex < chunk.storage.length; sectionIndex++) {
                ExtendedBlockStorage section = chunk.storage[sectionIndex];
                if (section == null) continue;
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            if (section.get(localX, localY, localZ).getBlock() != Blocks.AIR) {
                                int x = chunk.key.x() * 16 + localX;
                                int y = sectionIndex * 16 + localY;
                                int z = chunk.key.z() * 16 + localZ;
                                return new DirtyCellRef(chunk, new DirtyCell(x, y, z));
                            }
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("fixture has no physical floor block: " + fixture.id);
    }

    private static List<DirtyCellRef> createDirtyBatchCells(Fixture fixture) {
        if (!fixture.scannerApplicable) {
            throw new IllegalArgumentException("public mixed-profile fixture has no real storage batch path");
        }
        List<DirtyCellRef> cells = new ArrayList<>(256);
        for (ChunkData chunk : orderedChunksCenterFirst(fixture)) {
            for (int sectionIndex = 0; sectionIndex < chunk.storage.length && cells.size() < 256; sectionIndex++) {
                ExtendedBlockStorage section = chunk.storage[sectionIndex];
                if (section == null) continue;
                for (int localY = 0; localY < 16 && cells.size() < 256; localY++) {
                    for (int localZ = 0; localZ < 16 && cells.size() < 256; localZ++) {
                        for (int localX = 0; localX < 16 && cells.size() < 256; localX++) {
                            if (section.get(localX, localY, localZ).getBlock() == Blocks.AIR) continue;
                            int x = chunk.key.x() * 16 + localX;
                            int y = sectionIndex * 16 + localY;
                            int z = chunk.key.z() * 16 + localZ;
                            cells.add(new DirtyCellRef(chunk, new DirtyCell(x, y, z)));
                        }
                    }
                }
            }
            if (cells.size() == 256) break;
        }
        if (cells.size() != 256) {
            throw new IllegalStateException("fixture cannot supply 256 real floor storage cells: fixture="
                    + fixture.id + " found=" + cells.size());
        }
        return List.copyOf(cells);
    }

    private static List<ChunkData> orderedChunksCenterFirst(Fixture fixture) {
        List<ChunkData> chunks = new ArrayList<>(fixture.chunks.size());
        ChunkData center = fixture.chunks.get(new ChunkKey(0, 0));
        if (center != null) chunks.add(center);
        for (ChunkData chunk : fixture.chunks.values()) {
            if (chunk != center) chunks.add(chunk);
        }
        return chunks;
    }

    private static IBlockState[] snapshotStates(List<DirtyCellRef> cells) {
        IBlockState[] states = new IBlockState[cells.size()];
        for (int index = 0; index < cells.size(); index++) states[index] = state(cells.get(index));
        return states;
    }

    private static IBlockState state(DirtyCellRef target) {
        DirtyCell cell = target.cell();
        return target.chunk().storage[cell.y() >> 4]
                .get(cell.x() & 15, cell.y() & 15, cell.z() & 15);
    }

    private static void set(DirtyCellRef target, IBlockState state) {
        DirtyCell cell = target.cell();
        target.chunk().storage[cell.y() >> 4].set(
                cell.x() & 15, cell.y() & 15, cell.z() & 15, state);
    }

    private static void restoreStates(List<DirtyCellRef> cells, IBlockState[] originals) {
        if (cells.size() != originals.length) {
            throw new IllegalArgumentException("dirty batch state snapshot length mismatch");
        }
        for (int index = 0; index < cells.size(); index++) set(cells.get(index), originals[index]);
    }

    private static long consume(Sample sample) {
        long value = 0x9E3779B97F4A7C15L;
        value = mix(value, Double.doubleToRawLongBits(sample.gravityAccel().x()));
        value = mix(value, Double.doubleToRawLongBits(sample.gravityAccel().y()));
        value = mix(value, Double.doubleToRawLongBits(sample.gravityAccel().z()));
        value = mix(value, Double.doubleToRawLongBits(sample.magneticAccel().x()));
        value = mix(value, Double.doubleToRawLongBits(sample.magneticAccel().y()));
        value = mix(value, Double.doubleToRawLongBits(sample.magneticAccel().z()));
        value = mix(value, faceMask(sample.effectiveGravityFaces()));
        return mix(value, faceMask(sample.effectiveMagneticFaces()));
    }

    private static long consume(StandingSample sample) {
        long value = consume(sample.forces());
        for (Face face : ALL_FACES) {
            Double gravity = sample.gravityStrengths().get(face);
            Double magnetic = sample.magneticStrengths().get(face);
            value = mix(value, gravity == null ? 0L : Double.doubleToRawLongBits(gravity));
            value = mix(value, gravity == null ? 0L : 1L);
            value = mix(value, magnetic == null ? 0L : Double.doubleToRawLongBits(magnetic));
            value = mix(value, magnetic == null ? 0L : 1L);
        }
        return value;
    }

    private static long consumeSampleOptional(Optional<Sample> sample, long emptyValue) {
        return sample.isPresent() ? consume(sample.get()) : emptyValue;
    }

    private static long consumeStandingOptional(Optional<StandingSample> sample, long emptyValue) {
        return sample.isPresent() ? consume(sample.get()) : emptyValue;
    }

    private static void assertNonzero(Sample sample, String description) {
        if (sample.gravityAccel().equals(Vec3.ZERO) && sample.magneticAccel().equals(Vec3.ZERO)) {
            throw new IllegalStateException(description + " returned no force for the real gravity block");
        }
    }

    private static void assertZero(Sample sample, String description) {
        if (!sample.gravityAccel().equals(Vec3.ZERO) || !sample.magneticAccel().equals(Vec3.ZERO)
                || !sample.effectiveGravityFaces().isEmpty() || !sample.effectiveMagneticFaces().isEmpty()) {
            throw new IllegalStateException(description + " retained a force or effective face after removal");
        }
    }

    private static void assertZero(StandingSample sample, String description) {
        assertZero(sample.forces(), description);
        if (!sample.gravityStrengths().isEmpty() || !sample.magneticStrengths().isEmpty()) {
            throw new IllegalStateException(description + " retained standing strengths after removal");
        }
    }

    private static long consumeIndexSample(FloorFieldIndex index, List<Query> queries) {
        long checksum = 0L;
        for (Query query : queries) {
            checksum ^= consume(index.sample(query.position(), query.response()));
        }
        return checksum;
    }

    private static RoundStats withChecksum(RoundStats stats, long checksum) {
        return new RoundStats(stats.operations(), stats.elapsedNanos(), stats.allocatedBytes(),
                stats.gcCounts(), stats.gcMillis(), checksum);
    }

    private static long consumeWorldSample(WorldFields fields, List<Query> queries) {
        long checksum = 0L;
        for (Query query : queries) {
            checksum ^= consume(fields.sample(query.position(), query.response(), false).orElseThrow());
            checksum ^= consumeStandingOptional(
                    fields.sampleForStanding(query.position(), query.response(), false),
                    0xa54ff53a5f1d36f1L);
        }
        return checksum;
    }

    private static void writeUtf8(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static void writeUtf8Atomically(Path path, String value) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("atomic report requires a parent directory: " + path);
        Files.createDirectories(parent);
        if (Files.exists(path)) throw new IOException("refusing to replace existing child readiness file: " + path);
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static String sha256(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            MessageDigest digest = sha256Digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return hex(digest.digest(bytes));
    }

    private static String sha256Text(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
                    else result.append(ch);
                }
            }
        }
        return result.append('"').toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || arg.indexOf('=') < 3) {
                throw new IllegalArgumentException("arguments must use --name=value: " + arg);
            }
            int equals = arg.indexOf('=');
            String name = arg.substring(2, equals);
            String value = arg.substring(equals + 1);
            if (result.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("duplicate argument: --" + name);
            }
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + key);
        return value;
    }

    private static long mix(long left, long right) {
        long value = left ^ (right + 0x9E3779B97F4A7C15L + (left << 6) + (left >>> 2));
        value ^= value >>> 29;
        value *= 0x165667919E3779F9L;
        return value ^ (value >>> 32);
    }

    private record Options(List<String> baselineClasspath, List<String> candidateClasspath,
                           Path output, Path baselineManifest, Path externalManifest,
                           Path preflightReceipt, Path expectedBaselineFloorRoot,
                           Path expectedCandidateFloorRoot, boolean candidateDiagnosticsReady) {
        static Options from(Map<String, String> values) {
            return new Options(splitPathList(required(values, "baseline-classpath")),
                    splitPathList(required(values, "candidate-classpath")),
                    Path.of(required(values, "output")),
                    Path.of(required(values, "baseline-manifest")),
                    Path.of(required(values, "external-manifest")),
                    Path.of(required(values, "preflight-receipt")),
                    Path.of(required(values, "expected-baseline-floor-root")),
                    Path.of(required(values, "expected-candidate-floor-root")),
                    Boolean.parseBoolean(values.getOrDefault("candidate-diagnostics-ready", "false")));
        }

        private static List<String> splitPathList(String value) {
            return Arrays.asList(value.split(java.util.regex.Pattern.quote(System.getProperty("path.separator"))));
        }
    }

    private enum Variant { BASELINE, CANDIDATE }

    enum FixtureId {
        DENSE(false), QUARTER_HOLES(false), MIXED_SIX_FACE(false), DOUBLE_SIX_FACE(false),
        PUBLIC_MIXED_PROFILES(false), MULTILAYER(false), DENSE_WIDE(true), MULTILAYER_WIDE(true),
        DIRTY_BATCH_CROSS_SECTION(false);
        final boolean wide;
        FixtureId(boolean wide) { this.wide = wide; }
    }

    enum Operation {
        INITIAL_SCAN("initial_scan", "public multi-profile fixture has no source-storage profile writer"),
        COMPILE_REPLACE("compile_replace", ""),
        INITIAL_WORLD_FLUSH("initial_world_flush", "public multi-profile fixture has no source-storage profile writer"),
        WORLD_LOAD("load_false", "public multi-profile fixture has no source-storage profile writer"),
        STORAGE_MUTATION_ONE("storage_mutation_one", "public multi-profile fixture has no source-storage profile writer"),
        STORAGE_MUTATION_BATCH_256("storage_mutation_batch_256", "dedicated cross-section batch fixture only"),
        DIRTY_ONE("dirty_one", "public multi-profile fixture has no source-storage profile writer"),
        DIRTY_BATCH("dirty_batch_256", "dedicated cross-section batch fixture only"),
        DIRTY_NO_CHANGE("dirty_no_change", "public multi-profile fixture has no source-storage profile writer"),
        IDLE_FLUSH("idle_flush", "public multi-profile fixture has no source-storage profile writer"),
        INDEX_SAMPLE("sample_index", ""),
        INDEX_STANDING_SAMPLE("standing_sample_index", ""),
        WORLD_SAMPLE("sample_world_fields", "public multi-profile fixture has no source-storage profile writer"),
        STANDING_SAMPLE("standing_sample_world_fields", "public multi-profile fixture has no source-storage profile writer"),
        UNLOAD_RELOAD("unload_reload", "public multi-profile fixture has no source-storage profile writer");

        final String label;
        final String notApplicableReason;
        Operation(String label, String reason) {
            this.label = label;
            this.notApplicableReason = reason;
        }
        boolean applicable(Fixture fixture) {
            return applicable(fixture.id);
        }
        boolean applicable(FixtureId fixture) {
            if (this == DIRTY_BATCH || this == STORAGE_MUTATION_BATCH_256) {
                return fixture == FixtureId.DIRTY_BATCH_CROSS_SECTION;
            }
            return fixture != FixtureId.PUBLIC_MIXED_PROFILES
                    || this == COMPILE_REPLACE || this == INDEX_SAMPLE
                    || this == INDEX_STANDING_SAMPLE;
        }
        boolean usesSampleQuerySchedule() {
            return this == INDEX_SAMPLE || this == INDEX_STANDING_SAMPLE
                    || this == WORLD_SAMPLE || this == STANDING_SAMPLE;
        }
    }

    record FixtureSpec(FixtureId id, int width, long seed) { }
    private record GridProbe(int x, int y, int z) { }
    private record ProbeSource(int x, int y, int z, Face face, Kind kind, FloorFieldProfile profile) { }
    private enum QueryCategory {
        CELL_CENTER,
        FACE_ORIGIN, FACE_PLATEAU, FACE_RANGE_BELOW, FACE_RANGE_EXACT, FACE_RANGE_ABOVE,
        TANGENT_EDGE_BELOW, TANGENT_EDGE_EXACT, TANGENT_EDGE_ABOVE,
        SECTION_EDGE_BELOW, SECTION_EDGE_EXACT, SECTION_EDGE_ABOVE,
        CHUNK_EDGE_BELOW, CHUNK_EDGE_EXACT, CHUNK_EDGE_ABOVE
    }

    private record AxisBoundary(int axis, double value) { }
    private record Query(Vec3 position, double response, QueryCategory category) { }
    private record SampleQuerySchedule(List<Query> queries, long rawQueryCount,
                                      Map<String, Long> categoryCounts, String sha256) { }
    private record DirtyCell(int x, int y, int z) { }
    private record DirtyCellRef(ChunkData chunk, DirtyCell cell) { }
    private record SourceBatch(ChunkKey key, Object owner, List<Source> sources) { }
    private record PreparedWorld(WorldFields fields) { }
    private record FixtureWorkload(Fixture fixture, List<SourceBatch> sourceBatches,
                                   FloorFieldIndex index, PreparedWorld world) implements AutoCloseable {
        @Override public void close() {
            index.clear();
            if (world != null) world.fields.close();
        }
    }

    static final class ChunkData {
        final ChunkKey key;
        final ExtendedBlockStorage[] storage = new ExtendedBlockStorage[16];
        final List<Source> publicSources = new ArrayList<>();
        Object owner = new Object();
        long expectedPhysicalCount;
        long expectedEmitterCount;
        ChunkData(ChunkKey key) { this.key = key; }
    }

    static final class Fixture {
        final FixtureId id;
        final int width;
        final long seed;
        final LinkedHashMap<ChunkKey, ChunkData> chunks;
        final List<GridProbe> gridProbes;
        final List<ProbeSource> probeSources;
        final List<Query> sampleQueries;
        final long sampleRawQueryCount;
        final Map<String, Long> sampleQueryCategoryCounts;
        final String sampleQueryScheduleSha256;
        final double minWorldX;
        final double maxWorldX;
        final double minWorldZ;
        final double maxWorldZ;
        final List<Integer> sectionBoundaries;
        final boolean scannerApplicable;

        Fixture(FixtureId id, int width, long seed, Map<ChunkKey, ChunkData> chunks,
                List<GridProbe> gridProbes, List<ProbeSource> probeSources,
                double minWorldX, double maxWorldX, double minWorldZ, double maxWorldZ,
                List<Integer> sectionBoundaries) {
            this.id = id;
            this.width = width;
            this.seed = seed;
            this.chunks = new LinkedHashMap<>(chunks);
            this.gridProbes = List.copyOf(gridProbes);
            this.probeSources = List.copyOf(probeSources);
            this.minWorldX = minWorldX;
            this.maxWorldX = maxWorldX;
            this.minWorldZ = minWorldZ;
            this.maxWorldZ = maxWorldZ;
            this.sectionBoundaries = List.copyOf(sectionBoundaries);
            this.scannerApplicable = id != FixtureId.PUBLIC_MIXED_PROFILES;
            SampleQuerySchedule schedule = scheduleSampleQueries(createSampleQueryPool(this));
            this.sampleQueries = schedule.queries();
            this.sampleRawQueryCount = schedule.rawQueryCount();
            this.sampleQueryCategoryCounts = schedule.categoryCounts();
            this.sampleQueryScheduleSha256 = schedule.sha256();
        }

        ChunkData firstDirtyChunk() {
            if (id == FixtureId.PUBLIC_MIXED_PROFILES) {
                throw new IllegalStateException("public profile fixture has no dirty storage path");
            }
            return chunks.get(new ChunkKey(0, 0));
        }

        DirtyCell firstDirtyCell() {
            GridProbe probe = gridProbes.stream().filter(candidate -> candidate.x() == 0 && candidate.z() == 0)
                    .findFirst().orElseThrow();
            return new DirtyCell(probe.x(), probe.y(), probe.z());
        }
    }

    private record TraceSummary(Path path, long rows, String rawSha256, String compressedSha256) { }

    private record EquivalenceResult(FixtureId fixture, int fork, TraceSummary baseline,
                                     TraceSummary candidate, String status) { }

    private record HistogramResult(Variant variant, FixtureId fixture, long pid,
                                   List<String> command, List<String> jcmdCommand,
                                   Path rawPath, String rawSha256, Path stdout, Path stderr) { }

    private record RoundStats(int operations, long elapsedNanos, Long allocatedBytes,
                              Map<String, Long> gcCounts, Map<String, Long> gcMillis,
                              long checksum) { }

    private record RoundRow(Variant variant, FixtureId fixture, long fork, Operation operation,
                            int round, int operations, long elapsedNanos, Long allocatedBytes,
                            Map<String, Long> gcCounts, Map<String, Long> gcMillis,
                            String checksum, Map<String, Long> sampleQueryCategoryCounts,
                            String sampleQueryScheduleSha256, String status, String reason) {
        double nsPerOperation() { return operations == 0 ? Double.NaN : (double) elapsedNanos / operations; }
        String toJson() {
            return "{\"variant\":" + quote(variant.name().toLowerCase())
                    + ",\"fixture\":" + quote(fixture.name().toLowerCase())
                    + ",\"fork\":" + fork + ",\"operation\":" + quote(operation.label)
                    + ",\"round\":" + round + ",\"operations\":" + operations
                    + ",\"elapsedNanos\":" + elapsedNanos + ",\"nsPerOperation\":" + nsPerOperation()
                    + ",\"allocatedBytes\":" + (allocatedBytes == null ? quote("unavailable") : allocatedBytes)
                    + ",\"gcCounts\":" + mapJson(gcCounts) + ",\"gcMillis\":" + mapJson(gcMillis)
                    + ",\"sampleQueryCategoryCounts\":" + mapJson(sampleQueryCategoryCounts)
                    + ",\"sampleQueryScheduleSha256\":" + quote(sampleQueryScheduleSha256)
                    + ",\"gcScope\":" + quote(GC_SCOPE_NOTE)
                    + ",\"checksum\":" + quote(checksum) + ",\"status\":" + quote(status)
                    + ",\"reason\":" + quote(reason) + "}";
        }
        static RoundRow notApplicable(Variant variant, FixtureId fixture, long fork,
                                      Operation operation, String reason) {
            return new RoundRow(variant, fixture, fork, operation, -1, 0, 0, null, Map.of(), Map.of(),
                    "0", Map.of(), "", "NOT_APPLICABLE", reason);
        }
    }

    private static String mapJson(Map<String, Long> values) {
        StringBuilder result = new StringBuilder("{");
        int index = 0;
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (index++ != 0) result.append(',');
            result.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return result.append('}').toString();
    }

    private static final class RoundClock {
        private final boolean collect;
        private final ThreadMXBean allocationBean;
        private final Map<String, Long> gcLastCount = new LinkedHashMap<>();
        private final Map<String, Long> gcLastMillis = new LinkedHashMap<>();
        private final Map<String, Long> gcCountsAccumulated = new LinkedHashMap<>();
        private final Map<String, Long> gcMillisAccumulated = new LinkedHashMap<>();
        private long elapsedNanos;
        private long allocatedBytes;
        private long allocationStart = -1L;
        private long spanStartNanos;
        private boolean allocationAvailable;

        RoundClock(boolean collect) {
            this.collect = collect;
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            allocationBean = bean instanceof ThreadMXBean extended ? extended : null;
            if (collect && allocationBean != null && allocationBean.isThreadAllocatedMemorySupported()) {
                try {
                    if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                        allocationBean.setThreadAllocatedMemoryEnabled(true);
                    }
                    allocationAvailable = true;
                } catch (UnsupportedOperationException | SecurityException unavailable) {
                    allocationAvailable = false;
                }
            }
        }

        void begin() {
            beginRound();
            spanStartNanos = System.nanoTime();
        }

        void beginRound() {
            elapsedNanos = 0L;
            allocatedBytes = 0L;
            allocationStart = -1L;
            gcLastCount.clear();
            gcLastMillis.clear();
            gcCountsAccumulated.clear();
            gcMillisAccumulated.clear();
            if (!collect) return;
            resetGcBaseline();
            allocationStart = allocated();
        }

        long allocated() {
            return collect && allocationAvailable ? allocationBean.getCurrentThreadAllocatedBytes() : -1L;
        }

        long allocatedBytes() { return allocatedBytes; }

        long allocatedDelta(long before) {
            long after = allocated();
            if (before < 0L || after < 0L) return 0L;
            return Math.max(0L, after - before);
        }

        void addElapsed(long nanos) { elapsedNanos += nanos; }
        long elapsedNanos() { return elapsedNanos; }

        long end() {
            elapsedNanos = System.nanoTime() - spanStartNanos;
            allocatedBytes = allocatedDelta(allocationStart);
            return elapsedNanos;
        }

        void checkpointGc() {
            if (!collect) return;
            Map<String, Long> nowCount = new LinkedHashMap<>();
            Map<String, Long> nowMillis = new LinkedHashMap<>();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                nowCount.put(bean.getName(), bean.getCollectionCount());
                nowMillis.put(bean.getName(), bean.getCollectionTime());
            }
            for (Map.Entry<String, Long> entry : nowCount.entrySet()) {
                accumulateGc(gcCountsAccumulated, entry.getKey(),
                        gcLastCount.getOrDefault(entry.getKey(), -1L), entry.getValue());
                accumulateGc(gcMillisAccumulated, entry.getKey(),
                        gcLastMillis.getOrDefault(entry.getKey(), -1L),
                        nowMillis.getOrDefault(entry.getKey(), -1L));
            }
            gcLastCount.clear();
            gcLastCount.putAll(nowCount);
            gcLastMillis.clear();
            gcLastMillis.putAll(nowMillis);
        }

        void resetGcBaseline() {
            if (!collect) return;
            gcLastCount.clear();
            gcLastMillis.clear();
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcLastCount.put(bean.getName(), bean.getCollectionCount());
                gcLastMillis.put(bean.getName(), bean.getCollectionTime());
            }
        }

        private static void accumulateGc(Map<String, Long> totals, String name, long before, long after) {
            long delta = before < 0L || after < 0L ? -1L : Math.max(0L, after - before);
            Long existing = totals.get(name);
            if (delta < 0L || existing != null && existing < 0L) totals.put(name, -1L);
            else totals.put(name, Math.addExact(existing == null ? 0L : existing, delta));
        }

        RoundStats finish(int operations, long elapsed, long checksum) {
            long measuredAllocation = allocationAvailable ? allocatedDelta(allocationStart) : 0L;
            return finish(operations, elapsed, checksum, measuredAllocation);
        }

        RoundStats finish(int operations, long elapsed, long checksum, long allocated) {
            checkpointGc();
            return new RoundStats(operations, elapsed, collect && allocationAvailable ? allocated : null,
                    Collections.unmodifiableMap(new LinkedHashMap<>(gcCountsAccumulated)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(gcMillisAccumulated)), checksum);
        }
    }

    private static final class ChildHandle {
        private final Variant variant;
        private final FixtureId fixture;
        private final int fork;
        private final Process process;
        private final Path ready;
        private final Path trace;
        private final Path measurements;
        private final Path stdout;
        private final Path stderr;
        private final List<String> command;

        ChildHandle(Variant variant, FixtureId fixture, int fork, Process process,
                    Path ready, Path trace, Path measurements, Path stdout, Path stderr,
                    List<String> command) {
            this.variant = variant;
            this.fixture = fixture;
            this.fork = fork;
            this.process = process;
            this.ready = ready;
            this.trace = trace;
            this.measurements = measurements;
            this.stdout = stdout;
            this.stderr = stderr;
            this.command = List.copyOf(command);
        }

        void awaitReady() throws Exception {
            waitForFileOrExit(ready, process, stdout, stderr, 30, TimeUnit.MINUTES);
        }

        TraceSummary awaitTrace() throws IOException {
            Map<String, String> values = readProperties(ready);
            if (!PROTOCOL.equals(values.get("protocol"))
                    || !variant.name().equalsIgnoreCase(values.get("variant"))
                    || !fixture.name().equalsIgnoreCase(values.get("fixture"))
                    || Integer.parseInt(values.get("fork")) != fork) {
                throw new IOException("child trace receipt identity mismatch: " + ready);
            }
            return new TraceSummary(Path.of(required(values, "trace")),
                    Long.parseLong(required(values, "rows")),
                    required(values, "rawSha256"), required(values, "compressedSha256"));
        }

        void measureAndCollect(List<String> aggregate) throws Exception {
            sendChildCommand(process, "MEASURE");
            int exit = waitFor(process, 90, TimeUnit.MINUTES,
                    "measurement child " + variant + "/" + fixture + "/" + fork, command, stderr);
            if (exit != 0) {
                throw new IOException("measurement child exited " + exit + "; stdout=" + stdout + "; stderr=" + stderr);
            }
            if (!Files.isRegularFile(measurements)) {
                throw new IOException("measurement child omitted measurements file: " + measurements);
            }
            List<String> rows = Files.readAllLines(measurements, StandardCharsets.UTF_8);
            verifyMeasurementRows(rows, variant, fixture, fork);
            aggregate.addAll(rows);
        }

        void stopIfRunning(Throwable primaryFailure) {
            if (!process.isAlive()) return;
            try {
                sendChildCommand(process, "STOP");
            } catch (Throwable stopFailure) {
                if (process.isAlive() && stopFailure != primaryFailure) primaryFailure.addSuppressed(stopFailure);
            }
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    terminateOwnedProcessTree(process, primaryFailure,
                            "child cleanup " + variant + "/" + fixture + "/" + fork);
                }
            } catch (InterruptedException interrupted) {
                primaryFailure.addSuppressed(interrupted);
                terminateOwnedProcessTree(process, primaryFailure,
                        "interrupted child cleanup " + variant + "/" + fixture + "/" + fork);
                Thread.currentThread().interrupt();
            }
        }
    }

    private record ChildPair(ChildHandle baseline, ChildHandle candidate) {
        void stopIfRunning(Throwable primaryFailure) {
            baseline.stopIfRunning(primaryFailure);
            candidate.stopIfRunning(primaryFailure);
        }
    }

    private interface ProbeConsumer {
        void accept(Vec3 position, double response) throws Exception;
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Throwable;
    }

    @FunctionalInterface
    private interface ReportWrite {
        void run() throws Exception;
    }
}
