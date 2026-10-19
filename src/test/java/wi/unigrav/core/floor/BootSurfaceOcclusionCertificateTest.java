package wi.unigrav.core.floor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import dev.crlhitbox.api.geometry.Aabb;
import dev.crlhitbox.api.geometry.Vec3d;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import wi.unigrav.core.floor.BootSurfaceCompiler.Limits;
import wi.unigrav.core.floor.BootSurfaceQuery.Measurements;
import wi.unigrav.core.floor.BootWorldSurfaces.QualifiedSnapshot;
import wi.unigrav.core.player.BootSurfaceGeometry;
import wi.unigrav.core.player.CardinalFrame;
import wi.unigrav.core.player.PlayerBodyPlacement;
import wi.unigrav.core.player.PlayerBodyShape;
import wi.unigrav.core.player.PlayerPhysicalSnapshot;
import wi.unigrav.core.player.StandingConstraints.BootTier;

/** Reproduces R22's accepted Cardinal-foot visibility failure through a real test world capture. */
class BootSurfaceOcclusionCertificateTest {
    private static final Limits COMPILER = new Limits(512, 3072, 96, 65536, 2_000_000, 4096);
    private static final BootSurfaceQuery.Limits QUERY = new BootSurfaceQuery.Limits(4096, 65536);
    private static final BootQueryFootprint.Limits FOOTPRINT = new BootQueryFootprint.Limits(1024, 4096, 64, 512);
    private static final double SOURCE_MIN_Z = 8.199999988079071;
    private static final double SOURCE_MAX_Z = 8.800000011920929;
    private static final Aabb R22_HIDDEN_FACE = box(33, 88, 8, 33, 89, 9);
    private static final Aabb R22_STONE = box(32, 89, 8, 33, 90, 9);
    private static final BlockPos HIDDEN_IRON_CELL = new BlockPos(32, 88, 8);
    private static final BlockPos STONE_CELL = new BlockPos(32, 89, 8);
    private static final BlockPos VISIBLE_IRON_CELL = new BlockPos(34, 88, 9);

    @BeforeAll
    static void bootstrap() { Bootstrap.register(); }

    @ParameterizedTest(name = "R22 reconnect geometry {0}")
    @MethodSource("r22Reconnects")
    void fullyBlockedR22FaceDoesNotAbortLaterVisibleFace(
            String label, double sourceMinY, double sourceMaxY, long worldEpoch, long revision,
            UUID sessionId, @TempDir Path directory) {
        try (BootWorldFixture fixture = BootWorldFixture.open(directory, new UUID(0, worldEpoch))) {
            var actor = fixture.actor();
            var bounds = box(33, sourceMinY, SOURCE_MIN_Z, 34.8, sourceMaxY, SOURCE_MAX_Z);
            var transport = new Vec3d(bounds.center().x(), bounds.min().y(), bounds.center().z());
            var placement = PlayerBodyPlacement.fromCardinalBounds(bounds, transport, CardinalFrame.X_POS.attitude(),
                    new PlayerBodyShape.Cardinal(CardinalFrame.X_POS,
                            new PlayerBodyShape.Dimensions(0.6, 1.8, 1.62)));
            actor.setPosition(transport.x(), transport.y(), transport.z());
            actor.setEntityBoundingBox(new AxisAlignedBB(bounds.min().x(), bounds.min().y(), bounds.min().z(),
                    bounds.max().x(), bounds.max().y(), bounds.max().z()));

            var physical = new PlayerPhysicalSnapshot(new PlayerPhysicalSnapshot.Scope(
                    actor.getUniqueID(), sessionId, actor.dimension, worldEpoch), revision, placement, new Vec3d(0, 0, 0));
            var source = BootSurfaceGeometry.fromAccepted(physical);
            assertEquals(box(33, sourceMinY, SOURCE_MIN_Z, 33, sourceMaxY, SOURCE_MAX_Z), source.anchorBounds());
            assertEquals(0.0, source.radius());

            var footprint = BootQueryFootprint.plan(source, 2.0, FOOTPRINT);
            fixture.install(footprint);
            fixture.put(HIDDEN_IRON_CELL, Blocks.IRON_BLOCK.getDefaultState());
            fixture.put(STONE_CELL, Blocks.STONE.getDefaultState());
            fixture.put(VISIBLE_IRON_CELL, Blocks.IRON_BLOCK.getDefaultState());

            var ruleSet = new MagneticBlockReader.RuleSet(1, List.of(new MagneticBlockReader.RuleBinding(
                    "fixture:iron", new MagneticBlockRule("minecraft:iron_block", Map.of(), null))));
            var capture = assertInstanceOf(QualifiedSnapshot.class,
                    BootWorldSurfaces.captureQualified(fixture.world(), actor, footprint, ruleSet));
            var result = BootSurfaceQuery.evaluateQualified(capture,
                    Map.of("fixture:iron", new BootTier(new FloorFieldProfile(32, 0.5, 8), 2.0, 4)),
                    COMPILER, QUERY);

            var measurements = assertInstanceOf(Measurements.class, result,
                    "the exact stone cross-section occludes the whole R22 face; it must not abort the later visible face: "
                            + result);
            assertSame(capture.snapshot(), measurements.capture().orElseThrow());
            assertSame(capture, measurements.qualifiedCapture().orElseThrow());
            assertTrue(measurements.surfaces().fragments().stream().anyMatch(fragment ->
                            fragment.geometry().outward() == CardinalFrame.X_POS
                                    && fragment.geometry().bounds().equals(R22_HIDDEN_FACE)
                                    && fragment.contributors().stream()
                                            .anyMatch(primitive -> primitive.cell().equals(HIDDEN_IRON_CELL))),
                    "the captured hidden target compiles to the exact recorded X_POS fragment");
            assertTrue(measurements.candidates().stream().anyMatch(candidate ->
                            candidate.fragment().geometry().outward() == CardinalFrame.Z_NEG
                                    && candidate.fragment().contributors().stream()
                                            .anyMatch(primitive -> primitive.cell().equals(VISIBLE_IRON_CELL))),
                    "a separate clear Z_NEG face remains a valid candidate after the proven-hidden face is skipped");
            assertTrue(measurements.candidates().stream().noneMatch(candidate ->
                            candidate.fragment().geometry().outward() == CardinalFrame.X_POS
                                    && candidate.fragment().geometry().bounds().equals(R22_HIDDEN_FACE)
                                    && candidate.fragment().contributors().stream()
                                            .anyMatch(primitive -> primitive.cell().equals(HIDDEN_IRON_CELL))),
                    "the stone-hidden X_POS face must not be returned as visible");
            assertTrue(capture.snapshot().primitives().stream().anyMatch(primitive ->
                            primitive.cell().equals(STONE_CELL) && primitive.rawBounds().equals(R22_STONE)),
                    "the blocker must come from the real captured stone collision box");
        }
    }

    private static Stream<Arguments> r22Reconnects() {
        return Stream.of(
                Arguments.of("original scope", 89.19999992847443, 89.79999995231628, 2L, 1801L,
                        UUID.fromString("39216756-7adb-4354-8f6b-dd89bcd20633")),
                Arguments.of("fresh reconnect scope", 89.79999989271164, 90.3999999165535, 3L, 1L,
                        UUID.fromString("ed3244fd-ab92-467b-b8e2-619d9b698ef0")));
    }

    private static Aabb box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new Aabb(new Vec3d(minX, minY, minZ), new Vec3d(maxX, maxY, maxZ));
    }
}
