package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.util.datafix.DataFixer;
import net.minecraftforge.common.DimensionManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wi.unigrav.core.floor.BootSurfaceCompiler.Qualification;
import wi.unigrav.core.floor.BootSurfaceQuery.Measurements;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/**
 * Public-chain observation tests using genuine mapped WorldServer, Chunk and EntityPlayer objects.
 * The fixture never starts MinecraftServer, opens a socket, generates/saves a world, or uses a game directory.
 */
class BootCaptureIntegrationTest {
    private static final UUID SESSION = new UUID(0, 71);
    private static final BlockPos FLOOR = new BlockPos(8, 64, 8);
    private static final BootQueryFootprint.Limits FOOTPRINT_LIMITS =
            new BootQueryFootprint.Limits(1024, 4096, 64, 512);
    private static final BootSurfaceCompiler.Limits COMPILER_LIMITS =
            new BootSurfaceCompiler.Limits(512, 3072, 96, 65536, 2_000_000, 4096);
    private static final BootSurfaceQuery.Limits QUERY_LIMITS = new BootSurfaceQuery.Limits(4096, 65536);

    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void qualifiedCaptureFeedsPublicEvaluationWithSameReadSelectorProvenance(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            MagneticBlockReader.RuleSet rules = rules(41);

            BootWorldSurfaces.QualifiedSnapshot capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules));

            assertEquals(41, capture.rulesRevision());
            assertEquals(1, capture.snapshot().primitives().size());
            BootWorldSurfaces.Primitive primitive = capture.snapshot().primitives().getFirst();
            assertEquals(FLOOR, primitive.cell());
            assertTrue(primitive.magnetic());
            assertEquals(new MagneticBlockReader.MaterialMatch("fixture:iron",
                    MagneticBlockReader.EvidenceKind.STORED_STATE), capture.materialMatches().get(primitive));

            BootTier tier = tier(1.5);
            Measurements measurements = assertInstanceOf(Measurements.class, BootSurfaceQuery.evaluateQualified(capture,
                    Map.of("fixture:iron", tier), COMPILER_LIMITS, QUERY_LIMITS));
            assertSame(capture, measurements.qualifiedCapture().orElseThrow());
            assertSame(capture.snapshot(), measurements.capture().orElseThrow());
            assertEquals(1, measurements.candidates().size());
            assertEquals(CardinalFrame.Y_POS, measurements.candidates().getFirst().fragment().geometry().outward());
        }
    }

    @Test
    void legacyCaptureReportsCaptureReachForAnObservedMagneticSurface(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            BootWorldSurfaces.Snapshot capture = assertInstanceOf(BootWorldSurfaces.Snapshot.class,
                    BootWorldSurfaces.capture(fixture.world(), fixture.actor(), fixture.footprint(), List.of(ironRule())));
            BootWorldSurfaces.Primitive primitive = capture.primitives().getFirst();

            BootSurfaceQuery.Unavailable unavailable = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluate(capture, Map.of(primitive, new Qualification("fixture:iron", tier(2.0))),
                            COMPILER_LIMITS, QUERY_LIMITS));
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH, unavailable.reason());
        }
    }

    @Test
    void qualifiedEvaluationRejectsMissingProfileWithoutRereadingTheWorld(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            BootWorldSurfaces.QualifiedSnapshot capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(42)));

            assertThrows(IllegalArgumentException.class,
                    () -> BootSurfaceQuery.evaluateQualified(capture, Map.of(), COMPILER_LIMITS, QUERY_LIMITS));
        }
    }

    @Test
    void qualifiedCaptureReportsMissingDependencyInsteadOfLoadingOrGeneratingIt(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            BootQueryFootprint.Column absent = fixture.footprint().columns().getFirst();
            fixture.worldFixture().removeColumn(absent.x(), absent.z());

            BootWorldSurfaces.Unavailable unavailable = assertInstanceOf(BootWorldSurfaces.Unavailable.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(46)));
            assertEquals(BootWorldSurfaces.Reason.MISSING_DEPENDENCY, unavailable.reason());
            assertTrue(fixture.world().getChunkProvider().getLoadedChunk(absent.x(), absent.z()) == null);
        }
    }

    @Test
    void qualifiedCaptureReportsDeferredWorldSnapshotObservation(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            fixture.world().captureBlockSnapshots = true;

            BootWorldSurfaces.Unavailable unavailable = assertInstanceOf(BootWorldSurfaces.Unavailable.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(47)));
            assertEquals(BootWorldSurfaces.Reason.DEFERRED_WORLD, unavailable.reason());
        }
    }

    @Test
    void qualifiedCaptureRejectsAnActorWhoseLivePositionNoLongerMatchesItsSource(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            fixture.actor().setPosition(fixture.actor().posX + 0.25, fixture.actor().posY, fixture.actor().posZ);

            BootWorldSurfaces.Unavailable unavailable = assertInstanceOf(BootWorldSurfaces.Unavailable.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(48)));
            assertEquals(BootWorldSurfaces.Reason.ACTOR_SCOPE, unavailable.reason());
        }
    }

    @Test
    void qualifiedEmptyCaptureStillRejectsAProfileBeyondItsObservedFootprint(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.AIR.getDefaultState())) {
            BootWorldSurfaces.QualifiedSnapshot capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(43)));
            assertTrue(capture.materialMatches().isEmpty());

            BootSurfaceQuery.Unavailable unavailable = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluateQualified(capture, Map.of("unseen:metal", tier(2.0)),
                            COMPILER_LIMITS, QUERY_LIMITS));
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH, unavailable.reason());
        }
    }

    @Test
    void qualifiedObservedMaterialAndAnUnrelatedOversizedProfileRejectTheCapture(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            BootWorldSurfaces.QualifiedSnapshot capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(44)));

            BootSurfaceQuery.Unavailable unavailable = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluateQualified(capture, Map.of("fixture:iron", tier(1.5), "unseen:metal", tier(2.0)),
                            COMPILER_LIMITS, QUERY_LIMITS));
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH, unavailable.reason());
        }
    }

    @Test
    void qualifiedMissingObservedProfileWinsOverAnUnrelatedOversizedProfile(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, Blocks.IRON_BLOCK.getDefaultState())) {
            BootWorldSurfaces.QualifiedSnapshot capture = assertInstanceOf(BootWorldSurfaces.QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), fixture.actor(), fixture.footprint(), rules(45)));

            assertThrows(IllegalArgumentException.class, () -> BootSurfaceQuery.evaluateQualified(capture,
                    Map.of("unseen:metal", tier(2.0)), COMPILER_LIMITS, QUERY_LIMITS));
        }
    }

    @Test
    void legacyFullyOccludedMagneticSurfaceStillRejectsAnOversizedQualification(@TempDir Path dataDirectory) {
        try (Fixture fixture = fixture(dataDirectory, fullyOccludedIron())) {
            BootWorldSurfaces.Snapshot capture = assertInstanceOf(BootWorldSurfaces.Snapshot.class,
                    BootWorldSurfaces.capture(fixture.world(), fixture.actor(), fixture.footprint(), List.of(ironRule())));
            BootWorldSurfaces.Primitive iron = capture.primitives().stream()
                    .filter(primitive -> primitive.cell().equals(FLOOR)).findFirst().orElseThrow();
            Qualification oversized = new Qualification("fixture:iron", tier(2.0));
            BootSurfaceCompiler.SurfaceSet compiled = assertInstanceOf(BootSurfaceCompiler.SurfaceSet.class,
                    BootSurfaceCompiler.compile(capture.primitives(), Map.of(iron, oversized), COMPILER_LIMITS));
            assertTrue(compiled.fragments().isEmpty(), "six real nonmagnetic neighbour cubes must bury every iron face");

            BootSurfaceQuery.Unavailable unavailable = assertInstanceOf(BootSurfaceQuery.Unavailable.class,
                    BootSurfaceQuery.evaluate(capture, Map.of(iron, oversized), COMPILER_LIMITS, QUERY_LIMITS));
            assertEquals(BootSurfaceQuery.Reason.CAPTURE_REACH, unavailable.reason());
        }
    }

    private static Fixture fixture(Path dataDirectory, IBlockState floorState) {
        return fixture(dataDirectory, Map.of(FLOOR, floorState));
    }

    private static Fixture fixture(Path dataDirectory, Map<BlockPos, IBlockState> states) {
        Map<BlockPos, IBlockState> ownedStates = Map.copyOf(states);
        BootWorldFixture worldFixture = BootWorldFixture.open(dataDirectory, new UUID(0, 70));
        try {
            EntityPlayer actor = worldFixture.actor();
            actor.setPosition(8.5, 65.0, 8.5);
            PlayerBodyShape.Dimensions dimensions = new PlayerBodyShape.Dimensions(actor.width, actor.height, actor.getEyeHeight());
            PlayerBodyPlacement placement = new PlayerBodyPlacement(
                    new Vec3d(actor.posX, actor.posY + actor.height / 2.0, actor.posZ),
                    CardinalFrame.Y_POS.attitude(), new PlayerBodyShape.Cardinal(CardinalFrame.Y_POS, dimensions));
            PlayerPhysicalSnapshot physical = new PlayerPhysicalSnapshot(
                    new PlayerPhysicalSnapshot.Scope(actor.getUniqueID(), SESSION, actor.dimension, 0), 1, placement,
                    new Vec3d(0, 0, 0));
            BootQueryFootprint footprint = BootQueryFootprint.plan(BootSurfaceGeometry.fromAccepted(physical), 1.5,
                    FOOTPRINT_LIMITS);
            worldFixture.install(footprint);
            for (var entry : ownedStates.entrySet()) worldFixture.put(entry.getKey(), entry.getValue());
            return new Fixture(worldFixture, footprint);
        } catch (RuntimeException | Error failure) {
            try { worldFixture.close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static Map<BlockPos, IBlockState> fullyOccludedIron() {
        return Map.of(FLOOR, Blocks.IRON_BLOCK.getDefaultState(), FLOOR.up(), Blocks.STONE.getDefaultState(),
                FLOOR.down(), Blocks.STONE.getDefaultState(), FLOOR.east(), Blocks.STONE.getDefaultState(),
                FLOOR.west(), Blocks.STONE.getDefaultState(), FLOOR.north(), Blocks.STONE.getDefaultState(),
                FLOOR.south(), Blocks.STONE.getDefaultState());
    }

    private static MagneticBlockReader.RuleSet rules(long revision) {
        return new MagneticBlockReader.RuleSet(revision,
                List.of(new MagneticBlockReader.RuleBinding("fixture:iron", ironRule())));
    }

    private static MagneticBlockRule ironRule() {
        return new MagneticBlockRule("minecraft:iron_block", Map.of(), null);
    }

    private static BootTier tier(double captureReachMetres) {
        return new BootTier(new FloorFieldProfile(32, 0.5, 3), captureReachMetres, 4);
    }

    private record Fixture(BootWorldFixture worldFixture, BootQueryFootprint footprint)
            implements AutoCloseable {
        private WorldServer world() { return worldFixture.world(); }
        private EntityPlayer actor() { return worldFixture.actor(); }
        @Override
        public void close() { worldFixture.close(); }
    }
}
